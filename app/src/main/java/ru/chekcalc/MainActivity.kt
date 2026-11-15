package ru.chekcalc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import ru.chekcalc.api.RecognitionClient
import ru.chekcalc.model.Receipt
import ru.chekcalc.api.RecognitionResult
import ru.chekcalc.storage.EncryptedSecretStore
import ru.chekcalc.storage.HistoryEntry
import ru.chekcalc.storage.SettingsRepository
import ru.chekcalc.ui.ChekCalcTheme
import ru.chekcalc.ui.history.HistoryDetailScreen
import ru.chekcalc.ui.history.HistoryFailure
import ru.chekcalc.ui.history.HistoryScreen
import ru.chekcalc.ui.history.HistoryViewModel
import ru.chekcalc.ui.home.HomeScreen
import ru.chekcalc.ui.import.PhotoImportScreen
import ru.chekcalc.ui.manual.ManualEntryScreen
import ru.chekcalc.ui.manual.ManualReceipt
import ru.chekcalc.ui.review.ReceiptDraft
import ru.chekcalc.ui.review.ReviewPhotoViewModel
import ru.chekcalc.ui.review.ReviewScreen
import ru.chekcalc.ui.review.deepRecognizeSummary
import ru.chekcalc.ui.select.SelectionScreen
import ru.chekcalc.storage.Payer
import ru.chekcalc.storage.PayerStore
import ru.chekcalc.ui.select.SelectionState
import ru.chekcalc.ui.summary.ReceiptSummary
import ru.chekcalc.ui.summary.SummaryScreen
import ru.chekcalc.ui.settings.SettingsScreen

private enum class Screen { HOME, IMPORT, REVIEW, MANUAL, SELECT, SUMMARY, HISTORY, HISTORY_DETAIL, SETTINGS }

/** Сохранение реквизитов в состоянии экрана: две строки переживают поворот и смерть процесса. */
val PayerSaver: Saver<Payer, Any> = listSaver(
    save = { listOf(it.name, it.phone) },
    restore = { saved -> Payer(saved[0].toString(), saved[1].toString()) },
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChekCalcTheme {
                val context = LocalContext.current
                val repository = remember {
                    SettingsRepository(EncryptedSecretStore(context.applicationContext))
                }
                val recognition = remember { RecognitionClient() }
                // Фото чека живёт в памяти, пока идёт проверка: поворот экрана
                // не должен его стирать, а на диск оно не попадает.
                val reviewPhoto: ReviewPhotoViewModel = viewModel()
                // История живёт в ViewModel: запись переживает поворот экрана.
                val historyModel: HistoryViewModel = viewModel()
                // Открытая запись хранится JSON-ом: поворот не должен
                // выкидывать из просмотра, пока список ещё не перечитан.
                var historyEntryJson by rememberSaveable { mutableStateOf<String?>(null) }
                // Одна сессия расчёта = одна запись в истории: повторный
                // «Дальше» не плодит дубли, а новый чек получает новый id.
                var checkSessionId by rememberSaveable { mutableStateOf<String?>(null) }
                // rememberSaveable: поворот экрана или пересоздание Activity
                // не должны выбрасывать человека с текущего экрана.
                var screenName by rememberSaveable { mutableStateOf(Screen.HOME.name) }
                // Чек переживает поворот экрана: храним его JSON-ом.
                val receiptJson = remember { Json { ignoreUnknownKeys = true } }
                var lastReceiptJson by rememberSaveable { mutableStateOf<String?>(null) }
                val lastReceipt = lastReceiptJson?.let {
                    runCatching { receiptJson.decodeFromString<Receipt>(it) }.getOrNull()
                }
                // Черновик проверки переживает поворот: правки не теряются.
                var draftJson by rememberSaveable { mutableStateOf<String?>(null) }
                // Выбор позиций тоже переживает поворот.
                var selectionJson by rememberSaveable { mutableStateOf<String?>(null) }
                // Из какого черновика построен выбор: возврат «Назад → Дальше»
                // без правок не должен сбрасывать отметки и чаевые.
                var selectionSourceJson by rememberSaveable { mutableStateOf<String?>(null) }
                // Итог фиксируется на момент перехода: экран итога не пересчитывает.
                var summaryJson by rememberSaveable { mutableStateOf<String?>(null) }
                val summary = summaryJson?.let {
                    runCatching { receiptJson.decodeFromString<ReceiptSummary>(it) }.getOrNull()
                }
                // Реквизиты платившего: вводятся один раз, живут только на устройстве.
                val payerStore = remember { PayerStore(this@MainActivity) }
                var payer by rememberSaveable(stateSaver = PayerSaver) {
                    mutableStateOf(payerStore.load())
                }
                val selection = selectionJson?.let {
                    runCatching { receiptJson.decodeFromString<SelectionState>(it) }.getOrNull()
                }
                // Форма ручного ввода тоже переживает поворот экрана.
                val scope = rememberCoroutineScope()
                // Глубокий разбор: состояние кнопки на экране проверки.
                // remember, а не rememberSaveable: поворот отменяет запрос,
                // и сохранённый true оставил бы кнопку заблокированной навсегда.
                var deepBusy by remember { mutableStateOf(false) }
                var deepSummary by rememberSaveable { mutableStateOf<String?>(null) }
                // Результат глубокого разбора, ждущий подтверждения: пока он шёл,
                // человек мог править черновик руками.
                // saveable: поворот не должен выбрасывать оплаченный результат.
                var pendingDeepJson by rememberSaveable { mutableStateOf<String?>(null) }
                var pendingDeepBeforeJson by rememberSaveable { mutableStateOf<String?>(null) }
                var reviewEditing by remember { mutableStateOf(false) }
                var manualJson by rememberSaveable { mutableStateOf<String?>(null) }
                val manualDraft = manualJson?.let {
                    runCatching { receiptJson.decodeFromString<ReceiptDraft>(it) }.getOrNull()
                } ?: ReceiptDraft()
                val draft = draftJson?.let {
                    runCatching { receiptJson.decodeFromString<ReceiptDraft>(it) }.getOrNull()
                }
                val screen = Screen.valueOf(screenName)

                if (!reviewEditing) pendingDeepJson?.let { pending ->
                    AlertDialog(
                        onDismissRequest = {
                            pendingDeepJson = null
                            pendingDeepBeforeJson = null
                            deepSummary = "Оставили ваши правки"
                        },
                        title = { Text("Применить глубокий разбор?") },
                        text = {
                            Text(
                                "Пока шло распознавание, вы правили черновик. " +
                                    "Заменить его результатом глубокого разбора?",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                draftJson = pending
                                val fresh = runCatching {
                                    receiptJson.decodeFromString<ReceiptDraft>(pending)
                                }.getOrNull()
                                val before = pendingDeepBeforeJson?.let {
                                    runCatching { receiptJson.decodeFromString<ReceiptDraft>(it) }.getOrNull()
                                }
                                deepSummary = if (fresh != null && before != null) {
                                    deepRecognizeSummary(before, fresh)
                                } else {
                                    "Результат глубокого разбора применён"
                                }
                                pendingDeepJson = null
                                pendingDeepBeforeJson = null
                            }) { Text("Заменить") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                pendingDeepJson = null
                                pendingDeepBeforeJson = null
                                deepSummary = "Оставили ваши правки"
                            }) { Text("Оставить") }
                        },
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            lastReceipt = lastReceipt,
                            historyFailure = historyModel.failures.contains(HistoryFailure.SAVE),
                            historyCount = historyModel.entries.size,
                            onImport = { screenName = Screen.IMPORT.name },
                            onManual = {
                                reviewPhoto.clear()
                                manualJson = null
                                screenName = Screen.MANUAL.name
                            },
                            onHistory = { screenName = Screen.HISTORY.name },
                            onOpenSettings = { screenName = Screen.SETTINGS.name },
                        )

                        Screen.IMPORT -> PhotoImportScreen(
                            recognize = { bytes ->
                                val settings = repository.load()
                                if (settings.apiKey.isBlank()) {
                                    RecognitionResult.Failure(
                                        ru.chekcalc.api.FailureKind.PROVIDER,
                                        "Сначала задайте ключ в настройках",
                                        retryable = false,
                                    )
                                } else {
                                    recognition.recognize(bytes, settings)
                                }
                            },
                            onRecognized = { receipt, photoBytes ->
                                reviewPhoto.set(photoBytes)
                                checkSessionId = UUID.randomUUID().toString()
                                lastReceiptJson = receiptJson.encodeToString(Receipt.serializer(), receipt)
                                val draftValue = ReceiptDraft.fromReceipt(receipt)
                                draftJson = receiptJson.encodeToString(ReceiptDraft.serializer(), draftValue)
                                screenName = Screen.REVIEW.name
                            },
                            onManualEntry = {
                                reviewPhoto.clear()
                                manualJson = null
                                screenName = Screen.MANUAL.name
                            },
                            onBack = { screenName = Screen.HOME.name },
                        )

                        Screen.MANUAL -> ManualEntryScreen(
                            draft = manualDraft,
                            onChange = { updated ->
                                manualJson = receiptJson.encodeToString(ReceiptDraft.serializer(), updated)
                            },
                            onNext = { entered ->
                                // Ручной чек идёт тем же путём, что распознанный:
                                // сначала receipt.v1, потом черновик проверки. Фото нет.
                                reviewPhoto.clear()
                                checkSessionId = UUID.randomUUID().toString()
                                val receipt = ManualReceipt.toReceipt(entered)
                                lastReceiptJson = receiptJson.encodeToString(Receipt.serializer(), receipt)
                                val reviewDraft = ReceiptDraft.fromReceipt(receipt)
                                draftJson = receiptJson.encodeToString(ReceiptDraft.serializer(), reviewDraft)
                                manualJson = null
                                screenName = Screen.REVIEW.name
                            },
                            onBack = { screenName = Screen.HOME.name },
                        )

                        Screen.REVIEW -> {
                            val current = draft
                            // Глубокий режим меняет тело запроса только у DeepSeek:
                            // у остальных провайдеров кнопка платила бы за пустой повтор.
                            val deepAvailable = remember(current) {
                                runCatching {
                                    ru.chekcalc.api.disablesThinking(repository.load().baseUrl)
                                }.getOrDefault(false)
                            }
                            if (current == null) {
                                screenName = Screen.HOME.name
                            } else {
                                ReviewScreen(
                                    draft = current,
                                    onChange = { updated ->
                                        draftJson = receiptJson.encodeToString(ReceiptDraft.serializer(), updated)
                                    },
                                    onNext = {
                                        val source = draftJson
                                        if (selection == null || selectionSourceJson != source) {
                                            val fromDraft = SelectionState.fromDraft(current)
                                            selectionJson = receiptJson.encodeToString(
                                                SelectionState.serializer(),
                                                fromDraft,
                                            )
                                            selectionSourceJson = source
                                        }
                                        screenName = Screen.SELECT.name
                                    },
                                    onBack = {
                                        reviewPhoto.clear()
                                        deepSummary = null
                                        pendingDeepJson = null
                                        pendingDeepBeforeJson = null
                                        screenName = Screen.HOME.name
                                    },
                                    photo = reviewPhoto.photo,
                                    deepBusy = deepBusy,
                                    deepSummary = deepSummary,
                                    onEditingChange = { reviewEditing = it },
                                    deepAvailable = deepAvailable,
                                    onDeepRecognize = {
                                        // Снимок фото и черновика на момент старта:
                                        // по ним проверяем, что ответ всё ещё «наш».
                                        val bytes = reviewPhoto.photo
                                        if (bytes != null && !deepBusy) {
                                            val before = current
                                            val startedJson = draftJson
                                            deepBusy = true
                                            deepSummary = null
                                            scope.launch {
                                                val settings = repository.load()
                                                val result = withContext(Dispatchers.IO) {
                                                    recognition.recognize(
                                                        image = bytes,
                                                        settings = settings,
                                                        forceDeepThinking = true,
                                                    )
                                                }
                                                // Пока шёл запрос, могли уйти назад и открыть
                                                // другой чек: чужой ответ не применяем.
                                                if (reviewPhoto.photo !== bytes ||
                                                    screenName != Screen.REVIEW.name
                                                ) {
                                                    deepBusy = false
                                                    return@launch
                                                }
                                                deepBusy = false
                                                when (result) {
                                                    is RecognitionResult.Success -> {
                                                        val fresh = ReceiptDraft.fromReceipt(result.receipt)
                                                        val freshJson = receiptJson.encodeToString(
                                                            ReceiptDraft.serializer(),
                                                            fresh,
                                                        )
                                                        if (reviewEditing) {
                                                            // Открыт редактор: замена сдвинула бы id строк,
                                                            // но результат уже оплачен — откладываем его.
                                                            pendingDeepJson = freshJson
                                                            pendingDeepBeforeJson = receiptJson.encodeToString(
                                                                ReceiptDraft.serializer(),
                                                                before,
                                                            )
                                                            deepSummary =
                                                                "Результат готов — закройте редактор и подтвердите замену"
                                                        } else if (draftJson != startedJson) {
                                                            // Правки во время запроса не стираем молча.
                                                            pendingDeepJson = freshJson
                                                            pendingDeepBeforeJson =
                                                                receiptJson.encodeToString(
                                                                    ReceiptDraft.serializer(),
                                                                    before,
                                                                )
                                                            deepSummary = "Результат готов — подтвердите замену правок"
                                                        } else {
                                                            draftJson = freshJson
                                                            deepSummary = deepRecognizeSummary(before, fresh)
                                                        }
                                                    }
                                                    is RecognitionResult.Failure -> {
                                                        deepSummary = "Не вышло: ${result.message}"
                                                    }
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        Screen.SELECT -> {
                            val current = selection
                            if (current == null) {
                                screenName = Screen.HOME.name
                            } else {
                                SelectionScreen(
                                    state = current,
                                    onChange = { updated ->
                                        selectionJson = receiptJson.encodeToString(
                                            SelectionState.serializer(),
                                            updated,
                                        )
                                    },
                                    onNext = {
                                        // Показываем итог, ничего не пересчитывая;
                                        // в историю чек попадёт на «Готово».
                                        summaryJson = receiptJson.encodeToString(
                                            ReceiptSummary.serializer(),
                                            ReceiptSummary.from(current),
                                        )
                                        screenName = Screen.SUMMARY.name
                                    },
                                    onBack = { screenName = Screen.REVIEW.name },
                                )
                            }
                        }

                        Screen.SUMMARY -> {
                            val current = summary
                            if (current == null) {
                                screenName = Screen.HOME.name
                            } else {
                                SummaryScreen(
                                    summary = current,
                                    payer = payer,
                                    onPayerChange = { updated ->
                                        payer = updated
                                        payerStore.save(updated)
                                    },
                                    onDone = {
                                        // Расчёт завершён: записываем его в историю,
                                        // затем чистим сессию.
                                        val finishedDraft = draft
                                        val finishedSelection = selection
                                        if (finishedDraft != null && finishedSelection != null) {
                                            historyModel.save(
                                                HistoryEntry(
                                                    id = checkSessionId
                                                        ?: UUID.randomUUID().toString(),
                                                    createdAt = System.currentTimeMillis(),
                                                    merchant = lastReceipt?.merchant,
                                                    draft = finishedDraft,
                                                    selection = finishedSelection,
                                                ),
                                            )
                                        }
                                        reviewPhoto.clear()
                                        screenName = Screen.HOME.name
                                        lastReceiptJson = null
                                        draftJson = null
                                        selectionJson = null
                                        selectionSourceJson = null
                                        summaryJson = null
                                    },
                                    onBack = { screenName = Screen.SELECT.name },
                                )
                            }
                        }

                        Screen.HISTORY -> HistoryScreen(
                            entries = historyModel.entries,
                            onOpen = { entry ->
                                historyEntryJson = receiptJson.encodeToString(
                                    HistoryEntry.serializer(),
                                    entry,
                                )
                                screenName = Screen.HISTORY_DETAIL.name
                            },
                            failures = historyModel.failures,
                            onDelete = { entry -> historyModel.delete(entry.id) },
                            onClear = { historyModel.clear() },
                            onBack = { screenName = Screen.HOME.name },
                        )

                        Screen.HISTORY_DETAIL -> {
                            val entry = historyEntryJson?.let {
                                runCatching {
                                    receiptJson.decodeFromString(HistoryEntry.serializer(), it)
                                }.getOrNull()
                            }
                            if (entry == null) {
                                screenName = Screen.HISTORY.name
                            } else {
                                HistoryDetailScreen(
                                    entry = entry,
                                    onBack = { screenName = Screen.HISTORY.name },
                                )
                            }
                        }

                        Screen.SETTINGS -> SettingsScreen(
                            repository = repository,
                            onBack = { screenName = Screen.HOME.name },
                        )
                    }
                }
            }
        }
    }
}

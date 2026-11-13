package ru.chekcalc.ui.import

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.chekcalc.api.RecognitionResult
import ru.chekcalc.image.PhotoCompressor
import ru.chekcalc.image.PhotoOrientation
import ru.chekcalc.image.PhotoSizing
import ru.chekcalc.model.Receipt
import java.io.File

/**
 * Импорт чека: камера или галерея, затем подготовка фото к отправке.
 *
 * Камера вызывается системным приложением через ActivityResultContracts,
 * поэтому разрешение CAMERA не нужно: доступ даёт само системное приложение,
 * а мы получаем файл через FileProvider в кэше приложения.
 *
 * Автоматических повторов нет: «Повторить» заново готовит и отправляет фото.
 * Временные файлы съёмки удаляются при замене, отмене и уходе с экрана.
 */
@Composable
fun PhotoImportScreen(
    recognize: suspend (ByteArray) -> RecognitionResult,
    onRecognized: (Receipt) -> Unit = {},
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val compressor = remember { PhotoCompressor(context.applicationContext) }

    // Строки, а не Uri: состояние переживает пересоздание Activity.
    var pickedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var canRetry by rememberSaveable { mutableStateOf(false) }
    // Пустой чек нельзя переслать тем же фото: нужен другой кадр.
    // Оба поля переживают поворот экрана, иначе блокировка слетала бы.
    var needsNewPhoto by rememberSaveable { mutableStateOf(false) }
    var failedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }

    val picked = pickedUri?.let { Uri.parse(it) }

    fun deletePickedCapture() {
        val path = capturePath ?: return
        runCatching { File(path).delete() }
        capturePath = null
    }

    /** Уход с экрана: снимок чека в кэше не оставляем. */
    fun leave() {
        deletePickedCapture()
        pendingPath?.let { runCatching { File(it).delete() } }
        pendingPath = null
        onBack()
    }

    // Системная кнопка и жест Back идут через ту же уборку, что и «Назад».
    BackHandler { leave() }

    fun submit() {
        val uri = picked ?: return
        sending = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    recognize(compressor.compress(uri))
                } catch (failure: Exception) {
                    RecognitionResult.Failure(
                        ru.chekcalc.api.FailureKind.INVALID_RESPONSE,
                        failure.message ?: "не удалось подготовить фото",
                    )
                }
            }
            sending = false
            when (result) {
                is RecognitionResult.Success -> {
                    deletePickedCapture()
                    canRetry = false
                    onRecognized(result.receipt)
                }
                is RecognitionResult.Failure -> {
                    message = result.message
                    canRetry = result.retryable
                    needsNewPhoto = !result.retryable
                    failedUri = if (result.retryable) null else pickedUri
                }
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) {
            // Успешная съёмка заменяет прежний снимок, только теперь его удаляем.
            deletePickedCapture()
            pendingPath?.let {
                capturePath = it
                pickedUri = Uri.fromFile(File(it)).toString()
            }
            message = null
            needsNewPhoto = false
            canRetry = false
            failedUri = null
        } else {
            // Отмена: удаляем только что созданный файл, выбранное фото не трогаем.
            pendingPath?.let { runCatching { File(it).delete() } }
            message = "Съёмка отменена"
        }
        pendingPath = null
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            deletePickedCapture()
            pickedUri = uri.toString()
            message = null
            canRetry = false
            // То же самое фото после неповторяемой ошибки не считается новым:
            // запоминаем его, пока не выберут другое.
            val chosen = uri.toString()
            needsNewPhoto = chosen == failedUri
            if (!needsNewPhoto) failedUri = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
    ) {
        TextButton(onClick = { leave() }) { Text("Назад") }
        Text("Чек", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Сфотографируйте чек целиком или выберите готовое фото. Фото сжимается на телефоне и никуда не сохраняется.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )

        Button(
            enabled = !sending,
            onClick = {
                val file = File(
                    File(context.cacheDir, "photos").apply { mkdirs() },
                    "receipt-${System.currentTimeMillis()}.jpg",
                )
                pendingPath = file.absolutePath
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val launched = runCatching { cameraLauncher.launch(uri) }.isSuccess
                if (!launched) {
                    // На устройстве без камеры intent не находится.
                    runCatching { file.delete() }
                    pendingPath = null
                    message = "Камера недоступна: приложение не найдено"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Сфотографировать")
        }

        Button(
            enabled = !sending,
            onClick = {
                galleryLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Выбрать из галереи")
        }

        picked?.let { uri ->
            val preview by produceState<Bitmap?>(initialValue = null, uri) {
                value = withContext(Dispatchers.IO) { loadPreview(context, uri) }
            }
            preview?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Выбранный чек",
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                )
            }

            Button(
                enabled = !sending && !needsNewPhoto,
                onClick = { submit() },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text(if (sending) "Распознаём…" else "Распознать")
            }
        }

        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            // Пустой чек повторять бессмысленно: кнопку показываем только
            // для ошибок, которые повтор действительно может исправить.
            if (picked != null && !sending && canRetry) {
                TextButton(onClick = { submit() }) { Text("Повторить") }
            }
        }
    }
}

/** Предпросмотр не тянет полное фото в память: уменьшаем по длинной стороне. */
internal fun loadPreview(context: Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val sample = PhotoSizing.inSampleSize(bounds.outWidth, bounds.outHeight, PREVIEW_SIDE)
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = context.contentResolver.openInputStream(uri)
        ?.use { BitmapFactory.decodeStream(it, null, options) } ?: return@runCatching null
    val orientation = PhotoOrientation.read { context.contentResolver.openInputStream(uri) }
    PhotoOrientation.apply(decoded, orientation)
}.getOrNull()

private const val PREVIEW_SIDE = 1024

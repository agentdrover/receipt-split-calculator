package ru.chekcalc.api

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.abs
import okhttp3.Response
import ru.chekcalc.model.Discount
import ru.chekcalc.model.Receipt
import ru.chekcalc.model.ReceiptItem
import ru.chekcalc.model.ServiceCharge
import ru.chekcalc.storage.ProviderSettings
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Чем закончилась попытка распознать чек. */
sealed interface RecognitionResult {
    data class Success(val receipt: Receipt, val attempts: Int) : RecognitionResult

    /**
     * Ошибка для экрана. [retryable] — имеет ли смысл кнопка «Повторить»
     * (у пустого чека нет: посылать то же фото ещё раз бессмысленно).
     */
    data class Failure(
        val kind: FailureKind,
        val message: String,
        val retryable: Boolean = true,
    ) : RecognitionResult
}

enum class FailureKind { NETWORK, TIMEOUT, PROVIDER, INVALID_RESPONSE, UNREADABLE }

/**
 * Клиент распознавания: фото уходит провайдеру напрямую, ответ разбирается
 * в модели receipt.v1.
 *
 * Правила (§7 спеки):
 * - только https, ключ из настроек, заголовки не логируются;
 * - невалидный ответ модели — ровно один повтор, дальше ошибка;
 * - пустой items — это «чек не распознан», а не сетевой сбой.
 */
class RecognitionClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        // Повтор — только наш, по правилу «невалидный ответ один раз».
        .retryOnConnectionFailure(false)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val attempts: Int = 2,
    /** Два независимых прогона на DeepSeek: ошибки модели взаимно закрываются. */
    private val doubleSample: (String) -> Boolean = ::disablesThinking,
) {

    /**
     * Останавливает диспетчер OkHttp. В приложении клиент живёт всё время,
     * а тестам нужно освободить фоновые потоки, иначе JVM не завершится.
     */
    fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    suspend fun recognize(
        image: ByteArray,
        settings: ProviderSettings,
        // Принудительно глубокий разбор: кнопка «Распознать точнее» на проверке.
        forceDeepThinking: Boolean = false,
    ): RecognitionResult {
        require(isSecure(settings.baseUrl)) { "нужен https-адрес" }
        require(settings.apiKey.isNotBlank()) { "не задан ключ" }
        require(settings.model.isNotBlank()) { "не задана модель" }

        var lastFailure: RecognitionResult.Failure? = null
        var thinkingAttempted = false
        for (attempt in 1..attempts) {
            val exchange = exchange(
                image = image,
                settings = settings,
                allowThinking = !thinkingAttempted,
                forceDeepThinking = forceDeepThinking,
            )
            thinkingAttempted = thinkingAttempted || exchange.thinkingAttempted
            when (val result = exchange.result) {
                is RecognitionResult.Success -> return result.copy(attempts = attempt)
                is RecognitionResult.Failure -> {
                    lastFailure = result
                    // Повторяем только невалидный ответ модели: сеть, таймаут и
                    // пустой чек повтором не лечатся. Если размышление уже
                    // включали — второй раз не платим.
                    if (result.kind != FailureKind.INVALID_RESPONSE || thinkingAttempted) return result
                }
            }
        }
        return lastFailure ?: RecognitionResult.Failure(FailureKind.INVALID_RESPONSE, "не удалось распознать")
    }

    /**
     * Прогон распознавания: на DeepSeek — два параллельных запроса с
     * объединением позиций, у остальных провайдеров — один.
     *
     * Если итоги двух ответов расходятся больше чем на 1 %, ошибка масштаба
     * не случайна: повторяем один запрос с включённым размышлением.
     */
    private suspend fun exchange(
        image: ByteArray,
        settings: ProviderSettings,
        allowThinking: Boolean,
        forceDeepThinking: Boolean = false,
    ): Exchange {
        // Глубокий режим — явный выбор человека (настройка или кнопка на
        // проверке): вместо двух быстрых прогонов сразу один с размышлением.
        if (forceDeepThinking || settings.deepThinking) {
            return Exchange(
                call(image, settings, disableThinking = false),
                thinkingAttempted = true,
            )
        }
        if (!doubleSample(settings.baseUrl)) {
            return Exchange(
                call(image, settings, disableThinking = disablesThinking(settings.baseUrl)),
                thinkingAttempted = false,
            )
        }
        val (first, second) = coroutineScope {
            val a = async { call(image, settings, disableThinking = true) }
            val b = async { call(image, settings, disableThinking = true) }
            a.await() to b.await()
        }
        val ok = listOf(first, second).filterIsInstance<RecognitionResult.Success>()
        if (ok.isEmpty()) {
            // Если один ответ — отказ провайдера (ключ, лимит), повтор не лечит:
            // возвращаем именно его, иначе внешний цикл сделает ещё пару запросов.
            val failures = listOf(first, second).filterIsInstance<RecognitionResult.Failure>()
            val decisive = failures.firstOrNull { it.kind != FailureKind.INVALID_RESPONSE }
            return Exchange(decisive ?: first, thinkingAttempted = false)
        }
        if (ok.size == 1) return Exchange(ok.single(), thinkingAttempted = false)
        val a = ok[0].receipt
        val b = ok[1].receipt
        if (totalsDiffer(a.total, b.total) && allowThinking) {
            // Размышление включаем только на подозрительном чеке: это дороже,
            // но именно там модель путает масштаб сумм.
            return Exchange(call(image, settings, disableThinking = false), thinkingAttempted = true)
        }
        return Exchange(
            RecognitionResult.Success(pickReceipt(a, b), attempts = 1),
            thinkingAttempted = false,
        )
    }

    private class Exchange(val result: RecognitionResult, val thinkingAttempted: Boolean)

    /**
     * Один обмен с провайдером.
     *
     * Отмена корутины (например, поворот экрана) отменяет Call на всё время
     * запроса, включая чтение тела: `invokeOnCancellation` живёт до тех пор,
     * пока продолжение не завершено.
     */
    private suspend fun call(
        image: ByteArray,
        settings: ProviderSettings,
        disableThinking: Boolean,
    ): RecognitionResult {
        val request = Request.Builder()
            .url(settings.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .post(buildBody(image, settings, disableThinking).toRequestBody(JSON_MEDIA))
            .build()

        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (!continuation.isActive) return
                    val failure = if (error is java.io.InterruptedIOException) {
                        RecognitionResult.Failure(FailureKind.TIMEOUT, "Провайдер не ответил вовремя")
                    } else {
                        RecognitionResult.Failure(FailureKind.NETWORK, "Нет связи с провайдером")
                    }
                    continuation.resumeWith(Result.success(failure))
                }

                override fun onResponse(call: Call, response: Response) {
                    // Ошибку чтения тела нельзя выпускать из колбэка: тогда
                    // продолжение не завершится и вызов повиснет навсегда.
                    val result = try {
                        response.use { readResult(it) }
                    } catch (timeout: java.io.InterruptedIOException) {
                        RecognitionResult.Failure(FailureKind.TIMEOUT, "Провайдер не ответил вовремя")
                    } catch (error: IOException) {
                        RecognitionResult.Failure(FailureKind.NETWORK, "Нет связи с провайдером")
                    }
                    if (continuation.isActive) continuation.resumeWith(Result.success(result))
                }
            })
        }
    }

    private fun readResult(response: Response): RecognitionResult {
        if (!response.isSuccessful) {
            return RecognitionResult.Failure(
                FailureKind.PROVIDER,
                "Провайдер ответил ${response.code}",
            )
        }
        val size = response.body?.contentLength() ?: -1
        if (size > MAX_RESPONSE_BYTES) {
            return RecognitionResult.Failure(
                FailureKind.INVALID_RESPONSE,
                "Ответ провайдера больше допустимого",
            )
        }
        val payload = response.body?.byteStream()?.let { ResponseReader.read(it) }
            ?: return RecognitionResult.Failure(
                FailureKind.INVALID_RESPONSE,
                "Пустой или слишком большой ответ провайдера",
            )
        return parse(payload)
    }

    private fun parse(payload: String): RecognitionResult {
        val content = try {
            val root = json.parseToJsonElement(payload).jsonObject
            val choices = root["choices"]?.jsonArray ?: return invalid("нет choices в ответе")
            val first = choices.firstOrNull()?.jsonObject ?: return invalid("пустой choices")
            val message = first["message"]?.jsonObject ?: return invalid("нет message")
            message["content"]?.jsonPrimitive?.content ?: return invalid("нет content")
        } catch (error: SerializationException) {
            return invalid("ответ не JSON")
        } catch (error: IllegalArgumentException) {
            return invalid("ответ не JSON")
        }

        val receipt = try {
            json.decodeFromString<Receipt>(stripCodeFence(content))
        } catch (error: SerializationException) {
            return invalid("ответ не по схеме receipt.v1")
        } catch (error: IllegalArgumentException) {
            return invalid("ответ не по схеме receipt.v1")
        }

        validate(receipt)?.let { return it }
        if (receipt.items.isEmpty()) {
            return RecognitionResult.Failure(
                FailureKind.UNREADABLE,
                "Чек не распознан: модель не нашла позиций",
                retryable = false,
            )
        }
        return RecognitionResult.Success(receipt, attempts = 1)
    }

    /** Ограничения схемы, которые не выразить в типах. */
    private fun validate(receipt: Receipt): RecognitionResult.Failure? {
        if (receipt.currency.isBlank()) return invalid("пустая валюта")
        if (!inRange(receipt.total)) return invalid("итог вне допустимого диапазона")
        if (!inRange(receipt.discount.amount)) return invalid("скидка вне диапазона")
        if (!inRange(receipt.serviceCharge.amount)) return invalid("обслуживание вне диапазона")
        for (item in receipt.items) {
            if (item.name.isBlank()) return invalid("пустое наименование позиции")
            if (!inRange(item.amount)) return invalid("сумма позиции вне диапазона")
            item.unitPrice?.let { if (!inRange(it)) return invalid("цена вне диапазона") }
        }
        return null
    }

    /** Суммы в рублях: не больше миллиарда, иначе не переживут перевод в копейки. */
    private fun inRange(value: Double): Boolean =
        value.isFinite() && value >= 0.0 && value <= MAX_RUBLES

    private fun invalid(message: String) =
        RecognitionResult.Failure(FailureKind.INVALID_RESPONSE, message)

    private fun buildBody(image: ByteArray, settings: ProviderSettings, disableThinking: Boolean): String {
        val encoded = Base64.getEncoder().encodeToString(image)
        return buildJsonObject(
            model = settings.model,
            dataUrl = "data:image/jpeg;base64,$encoded",
            disableThinking = disableThinking,
        )
    }

    internal fun buildJsonObject(model: String, dataUrl: String, disableThinking: Boolean): String {
        // deepseek-flash по умолчанию думает: на чек это лишние ~1 000 токенов
        // рассуждений и секунды ожидания, а JSON-ответ от них не меняется.
        val thinking = if (disableThinking) ""","thinking":{"type":"disabled"}""" else ""
        return """
            {"model":${JsonPrimitive(model)}$thinking,
             "response_format":{"type":"json_object"},
             "messages":[{"role":"user","content":[
               {"type":"text","text":${JsonPrimitive(PROMPT)}},
               {"type":"image_url","image_url":{"url":${JsonPrimitive(dataUrl)}}}
             ]}]}
        """.trimIndent()
    }

    /** Допуск: 1 % от большей суммы, но не меньше копейки. */
    internal fun tolerance(a: Double, b: Double): Double =
        maxOf(0.01, 0.01 * maxOf(abs(a), abs(b)))

    /** Итоги расходятся больше чем на 1 %: вероятна ошибка масштаба. */
    internal fun totalsDiffer(a: Double, b: Double): Boolean =
        abs(a - b) > tolerance(a, b)

    /**
     * Выбор лучшего из двух ответов.
     *
     * Объединять позиции нельзя: одна и та же строка может быть сгруппирована
     * по-разному («капучино 600» против двух по 300), и сумма объединения
     * случайно сходится с итогом, пряча пропуск. Поэтому берём ответ, который
     * сходится с итогом, а при равенстве — более подробный.
     */
    internal fun pickReceipt(first: Receipt, second: Receipt): Receipt {
        val firstOk = matchesTotal(first)
        val secondOk = matchesTotal(second)
        return when {
            firstOk && secondOk -> if (second.items.size > first.items.size) second else first
            firstOk -> first
            secondOk -> second
            // Ни один не сходится: более подробный обычно ближе к правде,
            // а расхождение с итогом экран покажет как остаток.
            second.items.size > first.items.size -> second
            else -> first
        }
    }

    /** Сумма позиций сходится с итогом чека с учётом скидки и обслуживания. */
    internal fun matchesTotal(receipt: Receipt): Boolean {
        val expected = expectedTotal(receipt)
        return abs(expected - receipt.total) <= tolerance(expected, receipt.total)
    }

    /** Ожидаемый итог по позициям: сумма − скидка + обслуживание. */
    private fun expectedTotal(receipt: Receipt): Double {
        val discount = if (receipt.discount.present) receipt.discount.amount else 0.0
        val service = if (receipt.serviceCharge.present) receipt.serviceCharge.amount else 0.0
        return receipt.items.sumOf { it.amount } - discount + service
    }

    /** Одна и та же позиция: похожее название и сумма в пределах 1 %. */

    /** Одна и та же позиция: похожее название и сумма в пределах 1 %. */
    internal fun sameItem(a: ReceiptItem, b: ReceiptItem): Boolean =
        similarName(a.name, b.name) >= 0.8 && abs(a.amount - b.amount) <= tolerance(a.amount, b.amount)

    /** Похожесть названий 0..1 по расстоянию Левенштейна. */
    internal fun similarName(a: String, b: String): Double {
        val x = a.lowercase().trim().replace(WHITESPACE, " ")
        val y = b.lowercase().trim().replace(WHITESPACE, " ")
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return 1.0 - levenshtein(x, y).toDouble() / maxOf(x.length, y.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }


    private fun stripCodeFence(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    /** Секрет можно слать только по https; петля — исключение для тестов. */
    private fun isSecure(url: String): Boolean =
        url.startsWith("https://") ||
            url.startsWith("http://localhost") ||
            url.startsWith("http://127.0.0.1")

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val MAX_RESPONSE_BYTES = ResponseReader.MAX_RESPONSE_BYTES
        const val MAX_RUBLES = 1_000_000_000.0

        val PROMPT = """
            Ты распознаёшь кассовый чек по фотографии и возвращаешь строго JSON по схеме:
            {"currency":"RUB","items":[{"name":"...","qty":1,"unit_price":100.0,"amount":100.0}],
             "discount":{"present":false,"amount":0},"service_charge":{"present":false,"amount":0},
             "total":100.0,"warnings":[]}

            Правила:
            - только позиции товаров и услуг; строки «Итого», «Скидка», «НДС», «Обслуживание»,
              «Чаевые», «Наличные», «Сдача», номера карт и ФИО кассира в items не попадают;
            - qty — число (весовой товар можно дробно), unit_price — цена за единицу;
            - скидку и обслуживание вынеси в discount и service_charge, их сумма уже внутри total;
            - если строка нечитаема, не выбрасывай её: qty = null и строка в warnings;
            - если чек не читается целиком, верни пустой items и объясни в warnings;
            - суммы — числа в валюте чека, без пробелов и символов валюты;
              запятая с тремя цифрами после неё — разделитель тысяч («281,435» это
              281435, «64,500» это 64500), а с одной-двумя — десятичный разделитель
              («390,25» это 390.25); в валютах без копеек (KRW, JPY, VND, IDR)
              суммы всегда целые;
            - доставку, упаковку и сервисный сбор вынеси в service_charge
              (present = true, amount), в items их не добавляй; в warnings —
              только если сумма нечитаема.
        """.trimIndent()
    }
}

/** Отключаем размышление только у официального DeepSeek: чужие шлюзы могут не знать поле. */
internal fun disablesThinking(baseUrl: String): Boolean =
    runCatching {
        // Хвостовая точка в FQDN — тот же хост, но строка другая.
        java.net.URI(baseUrl).host?.trimEnd('.')
            ?.equals("api.deepseek.com", ignoreCase = true) == true
    }.getOrDefault(false)

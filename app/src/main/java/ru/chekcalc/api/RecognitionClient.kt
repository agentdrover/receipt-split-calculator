package ru.chekcalc.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.chekcalc.model.Receipt
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
) {

    suspend fun recognize(image: ByteArray, settings: ProviderSettings): RecognitionResult {
        require(isSecure(settings.baseUrl)) { "нужен https-адрес" }
        require(settings.apiKey.isNotBlank()) { "не задан ключ" }
        require(settings.model.isNotBlank()) { "не задана модель" }

        var lastFailure: RecognitionResult.Failure? = null
        for (attempt in 1..attempts) {
            when (val result = call(image, settings)) {
                is RecognitionResult.Success -> return result.copy(attempts = attempt)
                is RecognitionResult.Failure -> {
                    lastFailure = result
                    // Повторяем только невалидный ответ модели: сеть, таймаут и
                    // пустой чек повтором не лечатся.
                    if (result.kind != FailureKind.INVALID_RESPONSE) return result
                }
            }
        }
        return lastFailure ?: RecognitionResult.Failure(FailureKind.INVALID_RESPONSE, "не удалось распознать")
    }

    private suspend fun call(image: ByteArray, settings: ProviderSettings): RecognitionResult =
        withContext(Dispatchers.IO) {
            val body = buildBody(image, settings.model)
            val request = Request.Builder()
                .url(settings.baseUrl.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer ${settings.apiKey}")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()

            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext RecognitionResult.Failure(
                            FailureKind.PROVIDER,
                            "Провайдер ответил ${response.code}",
                        )
                    }
                    val size = response.body?.contentLength() ?: -1
                    if (size > MAX_RESPONSE_BYTES) {
                        return@withContext RecognitionResult.Failure(
                            FailureKind.INVALID_RESPONSE,
                            "Ответ провайдера больше допустимого",
                        )
                    }
                    val payload = response.body?.byteStream()?.let { ResponseReader.read(it) }
                        ?: return@withContext RecognitionResult.Failure(
                            FailureKind.INVALID_RESPONSE,
                            "Пустой или слишком большой ответ провайдера",
                        )
                    parse(payload)
                }
            } catch (timeout: java.io.InterruptedIOException) {
                RecognitionResult.Failure(FailureKind.TIMEOUT, "Провайдер не ответил вовремя")
            } catch (network: IOException) {
                RecognitionResult.Failure(FailureKind.NETWORK, "Нет связи с провайдером")
            }
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

    private fun buildBody(image: ByteArray, model: String): String {
        val encoded = Base64.getEncoder().encodeToString(image)
        val payload = buildJsonObject(
            model = model,
            dataUrl = "data:image/jpeg;base64,$encoded",
        )
        return payload
    }

    private fun buildJsonObject(model: String, dataUrl: String): String = """
        {"model":${JsonPrimitive(model)},
         "response_format":{"type":"json_object"},
         "messages":[{"role":"user","content":[
           {"type":"text","text":${JsonPrimitive(PROMPT)}},
           {"type":"image_url","image_url":{"url":${JsonPrimitive(dataUrl)}}}
         ]}]}
    """.trimIndent()

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
            - суммы — числа, без пробелов и символов валюты.
        """.trimIndent()
    }
}

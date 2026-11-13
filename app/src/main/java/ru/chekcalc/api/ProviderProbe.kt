package ru.chekcalc.api

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface ProbeResult {
    data object Ok : ProbeResult
    data class Failed(val reason: String) : ProbeResult
}

/**
 * Проверка ключа одним запросом: GET {base}/models.
 *
 * Не распознаёт чек — это отдельная задача. Здесь важно только понять,
 * принимает ли провайдер ключ и адрес.
 */
class ProviderProbe(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    fun check(baseUrl: String, apiKey: String, probePath: String = "models"): ProbeResult {
        val url = baseUrl.trim().trimEnd('/') + "/" + probePath.trimStart('/')

        return try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> ProbeResult.Ok
                    401, 403 -> ProbeResult.Failed("Ключ не принят провайдером")
                    else -> ProbeResult.Failed("Провайдер ответил HTTP ${response.code}")
                }
            }
        } catch (error: IllegalArgumentException) {
            ProbeResult.Failed("Некорректный адрес провайдера")
        } catch (error: IOException) {
            ProbeResult.Failed("Нет связи с провайдером")
        }
    }
}

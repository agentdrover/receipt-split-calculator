package ru.chekcalc.storage

/** Настройки провайдера, которые пользователь вводит на телефоне. */
data class ProviderSettings(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}

sealed interface SaveResult {
    /** Ключ сохранён и привязан к адресу. */
    data class Saved(val keyReentryRequired: Boolean = false) : SaveResult

    /** Адрес не https или пустой — ключ не сохраняется. */
    data class InvalidUrl(val reason: String) : SaveResult
}

/**
 * Хранит настройки и следит за двумя инвариантами:
 * адрес только https, ключ привязан к адресу.
 *
 * Смена адреса стирает ключ: иначе секрет ушёл бы новому хосту без ведома
 * пользователя.
 */
class SettingsRepository(private val store: SecretStore) {

    fun load(): ProviderSettings = ProviderSettings(
        apiKey = store.get(KEY_API_KEY).orEmpty(),
        baseUrl = store.get(KEY_BASE_URL).orEmpty().ifEmpty { ProviderSettings.DEFAULT_BASE_URL },
        model = store.get(KEY_MODEL).orEmpty().ifEmpty { ProviderSettings.DEFAULT_MODEL },
    )

    fun save(input: ProviderSettings): SaveResult {
        val url = input.baseUrl.trim().trimEnd('/')
        if (!isHttps(url)) {
            return SaveResult.InvalidUrl("Нужен адрес https://, без него ключ отправлять нельзя")
        }
        val previousUrl = store.get(KEY_BOUND_URL)
        val urlChanged = previousUrl != null && previousUrl != url
        val key = if (urlChanged) "" else input.apiKey.trim()
        val model = input.model.trim().ifEmpty { ProviderSettings.DEFAULT_MODEL }

        store.put(KEY_BASE_URL, url)
        store.put(KEY_MODEL, model)
        store.put(KEY_BOUND_URL, url)
        store.put(KEY_API_KEY, key)

        return SaveResult.Saved(keyReentryRequired = urlChanged && input.apiKey.isNotBlank())
    }

    fun clearKey() {
        store.remove(KEY_API_KEY)
    }

    /** Показываем только последние четыре символа. */
    fun maskedKey(): String {
        val key = store.get(KEY_API_KEY).orEmpty()
        return maskKey(key)
    }

    private fun isHttps(url: String): Boolean =
        url.startsWith("https://") && url.length > "https://".length

    private companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_BOUND_URL = "key_bound_url"
    }
}

/** Маска ключа: последние четыре символа видны, остальное скрыто. */
fun maskKey(key: String): String {
    if (key.isEmpty()) return ""
    val tail = key.takeLast(4)
    return "••••" + tail
}

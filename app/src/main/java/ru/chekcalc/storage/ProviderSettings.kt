package ru.chekcalc.storage

/** Настройки активного провайдера, которые пользователь вводит на телефоне. */
data class ProviderSettings(
    val providerId: String = ProviderPreset.DEFAULT.id,
    val apiKey: String = "",
    val baseUrl: String = "",
    val model: String = "",
)

sealed interface SaveResult {
    /** Настройки сохранены; [keyReentryRequired] — адрес сменился, ключ стёрт. */
    data class Saved(val keyReentryRequired: Boolean = false) : SaveResult

    /** Адрес не https или пустой — ключ не сохраняется. */
    data class InvalidUrl(val reason: String) : SaveResult
}

/**
 * Хранит настройки по каждому провайдеру отдельно.
 *
 * Инварианты: адрес только https; ключ привязан к адресу и к провайдеру.
 * Смена адреса стирает ключ этого провайдера, чужие ключи не трогает.
 */
class SettingsRepository(private val store: SecretStore) {

    fun activeProvider(): ProviderPreset {
        migrateLegacyOnce()
        return ProviderPreset.byId(store.get(KEY_ACTIVE_PROVIDER))
    }

    fun load(): ProviderSettings {
        val preset = activeProvider()
        return ProviderSettings(
            providerId = preset.id,
            apiKey = store.get(keyApiKey(preset.id)).orEmpty(),
            baseUrl = store.get(keyBaseUrl(preset.id)).orEmpty().ifEmpty { preset.defaultBaseUrl },
            model = store.get(keyModel(preset.id)).orEmpty().ifEmpty { preset.defaultModel },
        )
    }

    /** Делает провайдера активным и возвращает его сохранённые настройки. */
    fun switchTo(preset: ProviderPreset): ProviderSettings {
        store.put(KEY_ACTIVE_PROVIDER, preset.id)
        return load()
    }

    fun save(input: ProviderSettings): SaveResult {
        val preset = ProviderPreset.byId(input.providerId)
        val url = input.baseUrl.trim().trimEnd('/')
        if (!isHttps(url)) {
            return SaveResult.InvalidUrl("Нужен адрес https://, без него ключ отправлять нельзя")
        }

        val previousUrl = store.get(keyBoundUrl(preset.id))
        val urlChanged = previousUrl != null && previousUrl != url
        val key = if (urlChanged) "" else input.apiKey.trim()
        val model = input.model.trim().ifEmpty { preset.defaultModel }

        store.put(keyBaseUrl(preset.id), url)
        store.put(keyModel(preset.id), model)
        store.put(keyBoundUrl(preset.id), url)
        store.put(keyApiKey(preset.id), key)
        store.put(KEY_ACTIVE_PROVIDER, preset.id)

        return SaveResult.Saved(keyReentryRequired = urlChanged && input.apiKey.isNotBlank())
    }

    fun clearKey() {
        store.remove(keyApiKey(activeProvider().id))
    }

    /** Показываем только последние четыре символа ключа активного провайдера. */
    fun maskedKey(): String = maskKey(store.get(keyApiKey(activeProvider().id)).orEmpty())

    /**
     * Настройки из первой версии (#2305) лежали под общими ключами.
     * Переносим их в провайдера «Свой адрес», чтобы человек не потерял ключ.
     */
    private fun migrateLegacyOnce() {
        val legacyKey = store.get(LEGACY_API_KEY)
        val legacyUrl = store.get(LEGACY_BASE_URL)
        val legacyModel = store.get(LEGACY_MODEL)
        if (legacyKey == null && legacyUrl == null && legacyModel == null) return

        // Порядок важен: сначала переносим данные, и только потом помечаем
        // миграцию сделанной. Если процесс убьют посередине, старые ключи
        // останутся на месте и следующий запуск повторит перенос.
        val custom = ProviderPreset.CUSTOM.id
        if (legacyKey != null) store.put(keyApiKey(custom), legacyKey)
        if (legacyUrl != null) {
            store.put(keyBaseUrl(custom), legacyUrl)
            store.put(keyBoundUrl(custom), legacyUrl)
        }
        if (legacyModel != null) store.put(keyModel(custom), legacyModel)
        if (store.get(KEY_ACTIVE_PROVIDER) == null) store.put(KEY_ACTIVE_PROVIDER, custom)
        store.remove(LEGACY_API_KEY)
        store.remove(LEGACY_BASE_URL)
        store.remove(LEGACY_MODEL)
        store.remove(LEGACY_BOUND_URL)
    }

    private fun isHttps(url: String): Boolean =
        url.startsWith("https://") && url.length > "https://".length

    private fun keyApiKey(id: String) = "api_key_$id"
    private fun keyBaseUrl(id: String) = "base_url_$id"
    private fun keyModel(id: String) = "model_$id"
    private fun keyBoundUrl(id: String) = "key_bound_url_$id"

    private companion object {
        const val KEY_ACTIVE_PROVIDER = "active_provider"
        const val LEGACY_API_KEY = "api_key"
        const val LEGACY_BASE_URL = "base_url"
        const val LEGACY_MODEL = "model"
        const val LEGACY_BOUND_URL = "key_bound_url"
    }
}

/** Маска ключа: последние четыре символа видны, остальное скрыто. */
fun maskKey(key: String): String {
    if (key.isEmpty()) return ""
    return "••••" + key.takeLast(4)
}

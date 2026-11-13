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
        val baseUrl = store.get(keyBaseUrl(preset.id)).orEmpty().ifEmpty { preset.defaultBaseUrl }
        val storedKey = store.get(keyApiKey(preset.id)).orEmpty()
        // Ключ отдаём только если он привязан именно к этому адресу:
        // иначе он мог бы уйти на чужой хост.
        val key = if (storedKey.isNotEmpty() && store.get(keyBoundUrl(preset.id)) == baseUrl) {
            storedKey
        } else {
            ""
        }
        return ProviderSettings(
            providerId = preset.id,
            apiKey = key,
            baseUrl = baseUrl,
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
        val keyWithoutBinding = previousUrl == null && store.get(keyApiKey(preset.id)) != null
        val urlChanged = keyWithoutBinding || (previousUrl != null && previousUrl != url)
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
        val currentUrl = store.get(keyBaseUrl(custom))
        // Ключ из старых настроек принадлежит старому адресу. Переносим его,
        // только если «свой адрес» ещё не задан или совпадает со старым.
        val sameTarget = currentUrl == null ||
            (legacyUrl != null && currentUrl == legacyUrl.trim().trimEnd('/'))

        if (legacyUrl != null && currentUrl == null) {
            store.put(keyBaseUrl(custom), legacyUrl)
        }
        // Привязку ставим только тому ключу, который действительно пришёл из
        // старых настроек. Чужой непривязанный ключ привязывать нельзя.
        val existingKey = store.get(keyApiKey(custom))
        val keyIsLegacy = existingKey == null || existingKey == legacyKey
        if (legacyUrl != null && sameTarget && keyIsLegacy && store.get(keyBoundUrl(custom)) == null) {
            store.put(keyBoundUrl(custom), legacyUrl.trim().trimEnd('/'))
        }
        // Ключ без адреса переносить некуда: без привязки он не должен
        // использоваться вообще.
        if (legacyKey != null && legacyUrl != null && sameTarget && store.get(keyApiKey(custom)) == null) {
            store.put(keyApiKey(custom), legacyKey)
        }
        if (legacyModel != null && store.get(keyModel(custom)) == null) {
            store.put(keyModel(custom), legacyModel)
        }
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

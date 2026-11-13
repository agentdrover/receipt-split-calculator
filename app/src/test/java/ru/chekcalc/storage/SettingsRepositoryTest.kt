package ru.chekcalc.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class InMemorySecretStore : SecretStore {
    val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

class SettingsRepositoryTest {

    private val store = InMemorySecretStore()
    private val repository = SettingsRepository(store)

    private fun savePreset(preset: ProviderPreset, key: String, url: String = preset.defaultBaseUrl) =
        repository.save(ProviderSettings(preset.id, key, url, preset.defaultModel))

    @Test
    fun `по умолчанию активен DeepSeek с его адресом и моделью`() {
        assertEquals(ProviderPreset.DEEPSEEK, repository.activeProvider())
        assertEquals("https://api.deepseek.com", repository.load().baseUrl)
        assertEquals("deepseek-flash", repository.load().model)
    }

    @Test
    fun `ключ DeepSeek не подставляется в OpenRouter`() {
        savePreset(ProviderPreset.DEEPSEEK, "sk-deepseek-1111")

        val openRouter = repository.switchTo(ProviderPreset.OPENROUTER)

        assertTrue("ключ чужого провайдера протёк", openRouter.apiKey.isEmpty())
        assertEquals("https://openrouter.ai/api/v1", openRouter.baseUrl)
        assertFalse(store.values.values.any { it.contains("1111") && it != "sk-deepseek-1111" })
    }

    @Test
    fun `возврат на DeepSeek восстанавливает его ключ`() {
        savePreset(ProviderPreset.DEEPSEEK, "sk-deepseek-1111")
        repository.switchTo(ProviderPreset.OPENROUTER)
        savePreset(ProviderPreset.OPENROUTER, "sk-openrouter-2222")

        val back = repository.switchTo(ProviderPreset.DEEPSEEK)

        assertEquals("sk-deepseek-1111", back.apiKey)
        assertEquals("https://api.deepseek.com", back.baseUrl)
        assertEquals("••••1111", repository.maskedKey())
    }

    @Test
    fun `ключи провайдеров не пересекаются в хранилище`() {
        savePreset(ProviderPreset.DEEPSEEK, "sk-deepseek-1111")
        savePreset(ProviderPreset.OPENROUTER, "sk-openrouter-2222")

        assertTrue(store.values["api_key_deepseek"] == "sk-deepseek-1111")
        assertTrue(store.values["api_key_openrouter"] == "sk-openrouter-2222")
    }

    @Test
    fun `смена адреса стирает ключ только своего провайдера`() {
        savePreset(ProviderPreset.DEEPSEEK, "sk-deepseek-1111")
        savePreset(ProviderPreset.CUSTOM, "sk-custom-3333", "https://a.example/v1")

        val result = repository.save(
            ProviderSettings(ProviderPreset.CUSTOM.id, "sk-custom-3333", "https://b.example/v1", "m1"),
        )

        assertTrue((result as SaveResult.Saved).keyReentryRequired)
        assertTrue(repository.switchTo(ProviderPreset.CUSTOM).apiKey.isEmpty())
        assertEquals("sk-deepseek-1111", repository.switchTo(ProviderPreset.DEEPSEEK).apiKey)
    }

    @Test
    fun `http-адрес отклоняется и ключ не сохраняется`() {
        val result = repository.save(
            ProviderSettings(ProviderPreset.CUSTOM.id, "sk-test-1234", "http://evil.example/v1", "m1"),
        )

        assertTrue(result is SaveResult.InvalidUrl)
        assertTrue(repository.load().apiKey.isEmpty())
    }

    @Test
    fun `старые настройки переезжают в своего провайдера`() {
        store.values["api_key"] = "sk-legacy-9999"
        store.values["base_url"] = "https://legacy.example/v1"
        store.values["model"] = "legacy-model"

        val settings = repository.load()

        assertEquals(ProviderPreset.CUSTOM, repository.activeProvider())
        assertEquals("sk-legacy-9999", settings.apiKey)
        assertEquals("https://legacy.example/v1", settings.baseUrl)
        assertEquals("legacy-model", settings.model)
        assertFalse("старые ключи должны быть удалены", store.values.containsKey("api_key"))
    }

    @Test
    fun `прерванная миграция повторяется при следующем запуске`() {
        // Активный провайдер уже записан, а данные не перенесены — как если бы
        // процесс убили посередине.
        store.values["active_provider"] = ProviderPreset.CUSTOM.id
        store.values["api_key"] = "sk-legacy-7777"
        store.values["base_url"] = "https://legacy.example/v1"

        val settings = repository.load()

        assertEquals("sk-legacy-7777", settings.apiKey)
        assertEquals("https://legacy.example/v1", settings.baseUrl)
        assertFalse("старые ключи должны быть удалены", store.values.containsKey("api_key"))
    }

    @Test
    fun `миграция не переключает выбранного провайдера`() {
        store.values["active_provider"] = ProviderPreset.DEEPSEEK.id
        store.values["api_key"] = "sk-legacy-8888"
        store.values["base_url"] = "https://legacy.example/v1"

        repository.load()

        assertEquals(ProviderPreset.DEEPSEEK, repository.activeProvider())
        assertEquals("sk-legacy-8888", repository.switchTo(ProviderPreset.CUSTOM).apiKey)
    }

    @Test
    fun `миграция не затирает уже сохранённый ключ своего адреса`() {
        // Пользователь уже настроил «свой адрес», а в хранилище остались
        // старые общие ключи: перенос не должен затереть рабочую настройку.
        store.values["active_provider"] = ProviderPreset.CUSTOM.id
        store.values["api_key_custom"] = "sk-new-5555"
        store.values["base_url_custom"] = "https://new.example/v1"
        store.values["key_bound_url_custom"] = "https://new.example/v1"
        store.values["api_key"] = "sk-legacy-6666"
        store.values["base_url"] = "https://old.example/v1"

        val settings = repository.load()

        assertEquals("sk-new-5555", settings.apiKey)
        assertEquals("https://new.example/v1", settings.baseUrl)
    }

    @Test
    fun `OpenRouter проверяет ключ на своём эндпоинте`() {
        assertEquals("key", ProviderPreset.OPENROUTER.probePath)
        assertEquals("models", ProviderPreset.DEEPSEEK.probePath)
    }

    @Test
    fun `миграция доставляет привязку ключа при обрыве`() {
        // Адрес уже перенесён, привязки ещё нет — как после падения между записями.
        store.values["api_key_custom"] = "sk-legacy-7777"
        store.values["base_url_custom"] = "https://legacy.example/v1"
        store.values["api_key"] = "sk-legacy-7777"
        store.values["base_url"] = "https://legacy.example/v1"

        repository.load()

        assertEquals("https://legacy.example/v1", store.values["key_bound_url_custom"])
    }

    @Test
    fun `ключ без привязки к адресу требует повторного ввода`() {
        store.values["api_key_custom"] = "sk-orphan-9999"
        store.values["base_url_custom"] = "https://a.example/v1"

        val result = repository.save(
            ProviderSettings(ProviderPreset.CUSTOM.id, "sk-orphan-9999", "https://a.example/v1", "m"),
        )

        assertTrue((result as SaveResult.Saved).keyReentryRequired)
        assertTrue(repository.load().apiKey.isEmpty())
    }

    @Test
    fun `старый ключ не переносится на другой адрес`() {
        store.values["base_url_custom"] = "https://new.example/v1"
        store.values["api_key"] = "sk-legacy-6666"
        store.values["base_url"] = "https://old.example/v1"

        val settings = repository.load()

        assertTrue("ключ старого адреса не должен достаться новому", settings.apiKey.isEmpty())
        assertEquals("https://new.example/v1", settings.baseUrl)
    }

    @Test
    fun `ключ без привязки не отдаётся и не отправляется`() {
        store.values["active_provider"] = ProviderPreset.CUSTOM.id
        store.values["api_key_custom"] = "sk-orphan-9999"
        store.values["base_url_custom"] = "https://a.example/v1"

        assertTrue(repository.load().apiKey.isEmpty())
    }

    @Test
    fun `миграция не привязывает чужой ключ к старому адресу`() {
        store.values["api_key_custom"] = "sk-other-1234"
        store.values["api_key"] = "sk-legacy-6666"
        store.values["base_url"] = "https://old.example/v1"

        repository.load()

        assertEquals("привязки быть не должно", null, store.values["key_bound_url_custom"])
        assertTrue("непривязанный ключ не отдаётся", repository.load().apiKey.isEmpty())
    }

    @Test
    fun `пустой ключ маскируется в пустую строку`() {
        assertEquals("", repository.maskedKey())
    }
}

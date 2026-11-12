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

    @Test
    fun `ключ сохраняется на https-адрес`() {
        val result = repository.save(
            ProviderSettings(
                apiKey = "sk-test-1234",
                baseUrl = "https://api.example.org/v1",
                model = "vision-1",
            ),
        )

        assertTrue(result is SaveResult.Saved)
        assertEquals("sk-test-1234", repository.load().apiKey)
        assertEquals("https://api.example.org/v1", repository.load().baseUrl)
        assertEquals("vision-1", repository.load().model)
    }

    @Test
    fun `http-адрес отклоняется и ключ не сохраняется`() {
        val result = repository.save(
            ProviderSettings(apiKey = "sk-test-1234", baseUrl = "http://evil.example/v1"),
        )

        assertTrue(result is SaveResult.InvalidUrl)
        assertTrue(repository.load().apiKey.isEmpty())
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun `смена адреса стирает ключ и требует ввода заново`() {
        repository.save(ProviderSettings("sk-old-9999", "https://a.example/v1"))

        val result = repository.save(ProviderSettings("sk-new-1111", "https://b.example/v1"))

        assertTrue(result is SaveResult.Saved)
        assertTrue((result as SaveResult.Saved).keyReentryRequired)
        assertTrue(repository.load().apiKey.isEmpty())
    }

    @Test
    fun `тот же адрес сохраняет ключ без повторного ввода`() {
        repository.save(ProviderSettings("sk-keep-7777", "https://a.example/v1"))

        val result = repository.save(ProviderSettings("sk-keep-7777", "https://a.example/v1"))

        assertFalse((result as SaveResult.Saved).keyReentryRequired)
        assertEquals("sk-keep-7777", repository.load().apiKey)
    }

    @Test
    fun `ключ маскируется до последних четырёх символов`() {
        repository.save(ProviderSettings("sk-secret-4321", "https://a.example/v1"))

        assertEquals("••••4321", repository.maskedKey())
        assertFalse(repository.maskedKey().contains("secret"))
    }

    @Test
    fun `пустой ключ маскируется в пустую строку`() {
        assertEquals("", repository.maskedKey())
    }
}

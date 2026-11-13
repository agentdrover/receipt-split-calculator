package ru.chekcalc.api

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProviderProbeTest {

    private lateinit var server: MockWebServer
    private val probe = ProviderProbe()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun `двухсотый ответ значит ключ принят`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = probe.check(server.url("/v1").toString().trimEnd('/'), "sk-test")

        assertTrue(result is ProbeResult.Ok)
        val request = server.takeRequest()
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
    }

    @Test
    fun `сорок первый ответ значит ключ не принят`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = probe.check(server.url("/v1").toString().trimEnd('/'), "sk-bad")

        assertTrue(result is ProbeResult.Failed)
        assertTrue((result as ProbeResult.Failed).reason.contains("Ключ не принят"))
    }

    @Test
    fun `пятисотый ответ показывает код`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = probe.check(server.url("/v1").toString().trimEnd('/'), "sk-test")

        assertTrue(result is ProbeResult.Failed)
        assertTrue((result as ProbeResult.Failed).reason.contains("500"))
    }

    @Test
    fun `проверка идёт по указанному пути`() {
        server.enqueue(MockResponse().setResponseCode(200))

        probe.check(server.url("/v1").toString().trimEnd('/'), "sk-test", "key")

        assertEquals("/v1/key", server.takeRequest().path)
    }

    @Test
    fun `пустой адрес не роняет приложение`() {
        val result = probe.check("", "sk-test")

        assertTrue(result is ProbeResult.Failed)
    }

    @Test
    fun `кривой адрес не роняет приложение`() {
        val result = probe.check("https://?", "sk-test")

        assertTrue(result is ProbeResult.Failed)
    }

    @Test
    fun `недоступный провайдер даёт понятную ошибку`() {
        server.shutdown()

        val result = probe.check("https://127.0.0.1:1/v1", "sk-test")

        assertTrue(result is ProbeResult.Failed)
    }
}

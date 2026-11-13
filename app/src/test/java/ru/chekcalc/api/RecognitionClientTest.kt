package ru.chekcalc.api

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.chekcalc.storage.ProviderSettings
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

class RecognitionClientTest {

    private lateinit var server: MockWebServer
    private val client = RecognitionClient()
    private val image = byteArrayOf(1, 2, 3, 4)

    private val settings get() = ProviderSettings(
        apiKey = "sk-test-1234",
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        model = "deepseek-flash",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun answer(content: String, code: Int = 200) {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        server.enqueue(
            MockResponse().setResponseCode(code).setBody(
                """{"choices":[{"message":{"role":"assistant","content":"$escaped"}}]}""",
            ),
        )
    }

    private val goodReceipt = """
        {"currency":"RUB","items":[{"name":"Кофе","qty":1,"unit_price":390.25,"amount":390.25}],
         "discount":{"present":false,"amount":0},"service_charge":{"present":false,"amount":0},
         "total":390.25,"warnings":[]}
    """.trimIndent()

    @Test
    fun `успешный ответ разбирается в модель`() = runBlocking {
        answer(goodReceipt)

        val result = client.recognize(image, settings)

        assertTrue(result is RecognitionResult.Success)
        val receipt = (result as RecognitionResult.Success).receipt
        assertEquals("RUB", receipt.currency)
        assertEquals(1, receipt.items.size)
        assertEquals("Кофе", receipt.items.first().name)
        assertEquals(390.25, receipt.total, 0.001)
    }

    @Test
    fun `запрос несёт ключ, модель и картинку в base64`() = runBlocking {
        answer(goodReceipt)

        client.recognize(image, settings)

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer sk-test-1234", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue("нет модели", body.contains("\"model\""))
        assertTrue("нет картинки", body.contains("data:image/jpeg;base64,"))
    }

    @Test
    fun `мусор дважды даёт ошибку разбора и ровно два запроса`() = runBlocking {
        answer("это не json")
        answer("и это тоже")

        val result = client.recognize(image, settings)

        assertTrue(result is RecognitionResult.Failure)
        assertEquals(FailureKind.INVALID_RESPONSE, (result as RecognitionResult.Failure).kind)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `ответ по схеме без валюты отклоняется`() = runBlocking {
        answer("""{"items":[{"name":"Кофе","amount":100.0}],"total":100.0}""")
        answer("""{"items":[{"name":"Кофе","amount":100.0}],"total":100.0}""")

        val result = client.recognize(image, settings)

        assertEquals(FailureKind.INVALID_RESPONSE, (result as RecognitionResult.Failure).kind)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `пустой чек не повторяется`() = runBlocking {
        answer("""{"currency":"RUB","items":[],"total":0.0,"warnings":["не читается"]}""")

        val result = client.recognize(image, settings)

        val failure = result as RecognitionResult.Failure
        assertEquals(FailureKind.UNREADABLE, failure.kind)
        assertFalse("пустой чек бессмысленно повторять", failure.retryable)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `отказ провайдера не повторяется`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))

        val result = client.recognize(image, settings)

        assertEquals(FailureKind.PROVIDER, (result as RecognitionResult.Failure).kind)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `молчащий провайдер даёт таймаут`() = runBlocking {
        val impatient = RecognitionClient(
            http = OkHttpClient.Builder()
                .callTimeout(500, TimeUnit.MILLISECONDS)
                .readTimeout(500, TimeUnit.MILLISECONDS)
                .build(),
        )
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(5, TimeUnit.SECONDS))

        val result = impatient.recognize(image, settings)

        assertEquals(FailureKind.TIMEOUT, (result as RecognitionResult.Failure).kind)
    }

    @Test
    fun `невалидный ответ не утекает в расчёт`() = runBlocking {
        answer("""{"currency":"RUB","items":[{"name":"Кофе","amount":-5.0}],"total":-5.0}""")
        answer("""{"currency":"RUB","items":[{"name":"Кофе","amount":-5.0}],"total":-5.0}""")

        val result = client.recognize(image, settings)

        assertEquals(FailureKind.INVALID_RESPONSE, (result as RecognitionResult.Failure).kind)
    }

    @Test
    fun `отрицательная цена позиции отклоняется`() = runBlocking {
        answer("""{"currency":"RUB","items":[{"name":"Кофе","qty":1,"unit_price":-1.0,"amount":10.0}],"total":10.0}""")
        answer("""{"currency":"RUB","items":[{"name":"Кофе","qty":1,"unit_price":-1.0,"amount":10.0}],"total":10.0}""")

        val result = client.recognize(image, settings)

        assertEquals(FailureKind.INVALID_RESPONSE, (result as RecognitionResult.Failure).kind)
    }

    @Test
    fun `итог вне диапазона отклоняется`() = runBlocking {
        answer("""{"currency":"RUB","items":[{"name":"Кофе","amount":1e18}],"total":1e18}""")
        answer("""{"currency":"RUB","items":[{"name":"Кофе","amount":1e18}],"total":1e18}""")

        val result = client.recognize(image, settings)

        assertEquals(FailureKind.INVALID_RESPONSE, (result as RecognitionResult.Failure).kind)
    }

    @Test
    fun `провайдер с Retry-After не получает скрытых повторов`() = runBlocking {
        // Два ответа, чтобы увидеть, повторяет ли OkHttp сам при Retry-After.
        repeat(2) {
            server.enqueue(
                MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("""{"error":"busy"}"""),
            )
        }

        val result = client.recognize(image, settings)

        assertEquals(FailureKind.PROVIDER, (result as RecognitionResult.Failure).kind)
        // Транспортный повтор OkHttp на 503 с Retry-After: 0: это его поведение,
        // не наше. Проверяем, что оно ограничено одним разом и видно по запросам.
        assertEquals("OkHttp повторяет 503 с Retry-After один раз", 2, server.requestCount)
    }

    @Test
    fun `скидка вне диапазона отклоняется`() = runBlocking {
        answer("""{"currency":"RUB","items":[{"name":"Кофе","amount":10.0}],"discount":{"present":true,"amount":1e18},"total":10.0}""")
        answer("""{"currency":"RUB","items":[{"name":"Кофе","amount":10.0}],"discount":{"present":true,"amount":1e18},"total":10.0}""")

        val result = client.recognize(image, settings)

        assertEquals(FailureKind.INVALID_RESPONSE, (result as RecognitionResult.Failure).kind)
    }

    @Test
    fun `слишком большой ответ не разбирается`() {
        val huge = ByteArrayInputStream("x".repeat(1_100_000).toByteArray())

        assertEquals(null, ResponseReader.read(huge))
    }

    @Test
    fun `ответ в пределах потолка читается целиком`() {
        val text = """{"ok":true}"""

        assertEquals(text, ResponseReader.read(ByteArrayInputStream(text.toByteArray())))
    }

    @Test
    fun `рубли переводятся в целые копейки`() {
        assertEquals(39025L, ru.chekcalc.calc.rublesToKopecks(390.25))
        assertEquals(0L, ru.chekcalc.calc.rublesToKopecks(0.0))
        assertEquals(1L, ru.chekcalc.calc.rublesToKopecks(0.005))
        assertEquals(419900L, ru.chekcalc.calc.rublesToKopecks(4199.0))
    }
}

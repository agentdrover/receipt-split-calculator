package ru.chekcalc.golden

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.chekcalc.model.Receipt
import java.io.File

/**
 * Golden set: эталонные чеки и их разметка по контракту receipt.v1.
 *
 * Набор читается из каталога ресурсов, а не из списка слагов: новый эталон,
 * положенный рядом с остальными, проверяется сам. Тест держит две вещи:
 * разметка не разошлась с моделями и у каждого набора есть фото. Точность
 * распознавания здесь не измеряется — для этого нужен живой провайдер.
 */
class GoldenSetTest {
    private val json = Json { ignoreUnknownKeys = false }

    private val fixtureRoot: File by lazy {
        val url = javaClass.getResource("/golden") ?: error("в classpath нет каталога golden")
        require(url.protocol == "file") {
            "golden должен быть распакован в каталог, а не упакован в архив: ${url.protocol}"
        }
        File(url.toURI())
    }

    private val slugs: List<String> by lazy {
        val directories =
            fixtureRoot
                .listFiles { file -> file.isDirectory }
                ?.map { it.name }
                ?.sorted()
                .orEmpty()
        require(directories.isNotEmpty()) { "в ${fixtureRoot.path} нет ни одного эталона" }
        directories
    }

    private fun load(slug: String): Receipt {
        val stream = javaClass.getResourceAsStream("/golden/$slug/expected.json")
        assertNotNull("нет эталона для $slug", stream)
        return json.decodeFromString(stream!!.bufferedReader().use { it.readText() })
    }

    @Test
    fun `набор не пуст и каждый эталон разбирается в модель`() {
        assertTrue("в наборе нет ни одного эталона", slugs.isNotEmpty())
        for (slug in slugs) {
            val receipt = load(slug)

            assertTrue("$slug: пустой список позиций", receipt.items.isNotEmpty())
            assertTrue("$slug: итог не положительный", receipt.total > 0)
            for (item in receipt.items) {
                assertTrue("$slug: пустое наименование", item.name.isNotBlank())
                assertTrue("$slug: сумма позиции ≤ 0 у «${item.name}»", item.amount > 0)
                assertTrue("$slug: валюта пустая", receipt.currency.isNotBlank())
            }
        }
    }

    @Test
    fun `у каждого эталона есть фото`() {
        for (slug in slugs) {
            val photo = javaClass.getResourceAsStream("/golden/$slug/photo.jpg")
            assertNotNull("нет фото для $slug", photo)
            photo!!.close()
        }
    }

    @Test
    fun `нечитаемый чек размечен как нераспознанный`() {
        val receipt = load("aheqvr-check")

        assertEquals("магазин не должен угадываться", null, receipt.merchant)
        assertTrue("должна быть пометка о нечитаемом верхе", receipt.warnings.isNotEmpty())
        assertTrue("позиции при этом разобраны", receipt.items.size >= 10)
    }

    @Test(expected = kotlinx.serialization.SerializationException::class)
    fun `чек без валюты и позиций не разбирается`() {
        // receipt.v1 требует currency и items: пустой чек должен падать,
        // а не превращаться в рублёвый чек без позиций.
        json.decodeFromString<Receipt>("""{"total":100}""")
    }

    @Test
    fun `скидка и обслуживание размечены там, где они есть`() {
        val withDiscount = load("cord-cafe-004")
        assertTrue("у CORD 004 скидка 10%", withDiscount.discount.present)
        assertTrue(withDiscount.discount.amount > 0)

        val withService = load("nepal-restaurant")
        assertTrue("в Непале service charge 10% + VAT", withService.serviceCharge.present)
        assertTrue(withService.serviceCharge.amount > 0)
    }
}

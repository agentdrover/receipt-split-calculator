package ru.chekcalc.golden

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.chekcalc.model.Receipt

/**
 * Golden set: эталонные чеки и их разметка по контракту receipt.v1.
 *
 * Тест держит две вещи: разметка не разошлась с моделями, и в наборе есть
 * кейс нечитаемого чека. Точность распознавания здесь не измеряется —
 * для этого нужен живой провайдер.
 */
class GoldenSetTest {

    private val json = Json { ignoreUnknownKeys = false }

    private val slugs = listOf(
        "aheqvr-check",
        "bakery-cafe",
        "california-restaurant",
        "cord-cafe-004",
        "cord-cafe-007",
        "cord-cafe-009",
        "cord-cafe-013",
        "cord-cafe-014",
        "cord-cafe-015",
        "cord-cafe-020",
        "dns-store-fiscal",
        "fine-bill-meal",
        "hk-star-seafood",
        "mcdonalds-keelung",
        "mcdonalds-kunming",
        "metropole-cafe",
        "nepal-restaurant",
        "ohsho-kyoto",
        "railway-fiscal",
        "taebaek-restaurant",
        "thai-restaurant",
    )

    private fun load(slug: String): Receipt {
        val stream = javaClass.getResourceAsStream("/golden/$slug/expected.json")
        assertNotNull("нет эталона для $slug", stream)
        return json.decodeFromString(stream!!.bufferedReader().use { it.readText() })
    }

    @Test
    fun `каждый эталон разбирается в модель и содержит позиции`() {
        assertEquals(21, slugs.size)
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

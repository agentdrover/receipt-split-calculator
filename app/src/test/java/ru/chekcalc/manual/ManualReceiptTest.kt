package ru.chekcalc.manual

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.chekcalc.model.Receipt
import ru.chekcalc.ui.manual.ManualReceipt
import ru.chekcalc.ui.review.DraftItem
import ru.chekcalc.ui.review.ReceiptDraft

class ManualReceiptTest {

    private val json = Json { ignoreUnknownKeys = false }

    private fun draft(): ReceiptDraft = ReceiptDraft(
        items = listOf(
            DraftItem(1, "Кофе", 1.0, 25_000),
            DraftItem(2, "Десерт", 2.0, 19_990),
            DraftItem(3, "Сок", 1.0, 15_000),
        ),
        discountKopecks = 10_000,
        serviceKopecks = 5_000,
        totalKopecks = 74_980,
    )

    @Test
    fun `ручной чек превращается в receipt v1 с суммами в рублях`() {
        val receipt = ManualReceipt.toReceipt(draft())

        assertEquals("RUB", receipt.currency)
        assertEquals(3, receipt.items.size)
        assertEquals(250.0, receipt.items[0].amount, 1e-9)
        assertEquals(399.80, receipt.items[1].amount, 1e-9)
        assertEquals(749.80, receipt.total, 1e-9)
        assertNull(receipt.confidence)
        assertEquals(emptyList<String>(), receipt.warnings)
    }

    @Test
    fun `скидка и обслуживание попадают в чек с признаком present`() {
        val receipt = ManualReceipt.toReceipt(draft())

        assertTrue(receipt.discount.present)
        assertEquals(100.0, receipt.discount.amount, 1e-9)
        assertTrue(receipt.serviceCharge.present)
        assertEquals(50.0, receipt.serviceCharge.amount, 1e-9)
        assertNull(receipt.serviceCharge.percent)
    }

    @Test
    fun `сериализованный ручной чек разбирается по receipt v1 без потерь`() {
        val receipt = ManualReceipt.toReceipt(draft())

        val text = json.encodeToString(Receipt.serializer(), receipt)
        val back = json.decodeFromString(Receipt.serializer(), text)

        assertEquals(receipt, back)
        assertTrue("нет currency", text.contains("\"currency\""))
        assertTrue("нет items", text.contains("\"items\""))
        assertTrue("нет total", text.contains("\"total\""))
        assertTrue("нет unit_price", text.contains("\"unit_price\""))

        // Обратный путь в копейки не должен терять ни копейки.
        val redraft = ReceiptDraft.fromReceipt(back)
        assertEquals(3, redraft.items.size)
        assertEquals(74_980, redraft.totalKopecks)
        assertEquals(0, redraft.residualKopecks)
    }

    @Test
    fun `полностью пустые строки не попадают в чек`() {
        val withEmpty = draft().copy(items = draft().items + DraftItem(4, "", 1.0, 0))

        assertEquals(3, ManualReceipt.toReceipt(withEmpty).items.size)
    }

    @Test
    fun `дробное количество считается по цене за единицу`() {
        val receipt = ManualReceipt.toReceipt(
            ReceiptDraft(
                items = listOf(DraftItem(1, "Сыр", 0.384, 104_100)),
                totalKopecks = 39_974,
            ),
        )

        assertEquals(399.74, receipt.items.single().amount, 1e-9)
    }

    @Test
    fun `форма готова к переходу только с названной позицией`() {
        assertFalse("пустая форма не готова", ManualReceipt.isReady(ReceiptDraft()))
        assertTrue("три позиции готовы", ManualReceipt.isReady(draft()))
        assertFalse(
            "позиция с суммой без названия не готова",
            ManualReceipt.isReady(ReceiptDraft(items = listOf(DraftItem(1, "", 1.0, 100)))),
        )
    }

    @Test
    fun `нулевое количество не готово к переходу`() {
        assertFalse(
            "ноль штук потеряет цену на экране проверки",
            ManualReceipt.isReady(ReceiptDraft(items = listOf(DraftItem(1, "Кофе", 0.0, 25_000)))),
        )
    }

    @Test
    fun `сумма строки сверх предела схемы не готова к переходу`() {
        assertFalse(
            "1,2 млрд ₽ схема не примет",
            ManualReceipt.isReady(
                ReceiptDraft(items = listOf(DraftItem(1, "Кофе", 2.0, 60_000_000_000L))),
            ),
        )
        assertTrue(
            "ровно 1 млрд ₽ — предел схемы, ещё можно",
            ManualReceipt.isReady(
                ReceiptDraft(items = listOf(DraftItem(1, "Кофе", 2.0, 50_000_000_000L))),
            ),
        )
    }
}

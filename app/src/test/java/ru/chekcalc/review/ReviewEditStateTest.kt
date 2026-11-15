package ru.chekcalc.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.chekcalc.ui.review.DraftItem
import ru.chekcalc.ui.review.ItemEditState
import ru.chekcalc.ui.review.ReceiptDraft
import ru.chekcalc.ui.review.TotalsEditState

class ReviewEditStateTest {

    private fun draft(): ReceiptDraft = ReceiptDraft(
        items = listOf(DraftItem(1, "Кофе", 2.0, 18_000)),
        totalKopecks = 36_000,
    )

    @Test
    fun `правка существующей позиции меняет название, количество и цену`() {
        val state = ItemEditState.of(draft().items.single())
            .copy(name = "Кофе с молоком", qtyText = "3", priceText = "200")

        val item = state.apply(draft()).items.single()

        assertEquals("Кофе с молоком", item.name)
        assertEquals(3.0, item.qty!!, 1e-9)
        assertEquals(20_000, item.unitPriceKopecks)
        assertEquals(60_000, item.amountKopecks)
    }

    @Test
    fun `новая позиция добавляется и получает цену`() {
        val state = ItemEditState.new().copy(name = "Сок", qtyText = "1", priceText = "150")

        val updated = state.apply(draft())

        assertEquals(2, updated.items.size)
        assertEquals("Сок", updated.items.last().name)
        assertEquals(15_000, updated.items.last().unitPriceKopecks)
    }

    @Test
    fun `невалидное состояние не меняет черновик`() {
        val base = draft()

        assertFalse(ItemEditState(name = "", priceText = "100").valid)
        assertEquals(base, ItemEditState(name = "", priceText = "100").apply(base))
        assertEquals(base, ItemEditState(name = "Кофе", priceText = "мусор").apply(base))
        assertEquals(base, ItemEditState(name = "Кофе", qtyText = "-1", priceText = "100").apply(base))
    }

    @Test
    fun `пустое количество допустимо и означает одну штуку`() {
        val state = ItemEditState.new().copy(name = "Чай", priceText = "90")

        assertTrue(state.valid)
        val item = state.apply(draft()).items.last()
        assertNull(item.qty)
        assertEquals(9_000, item.unitPriceKopecks)
        assertEquals(9_000, item.amountKopecks)
    }

    @Test
    fun `сумма с пробелом разбирается`() {
        val state = TotalsEditState.of(draft()).copy(totalText = "1 240,50")

        assertTrue(state.valid)
        assertEquals(124_050L, state.total)
    }

    @Test
    fun `лист итогов сохраняет три суммы`() {
        val state = TotalsEditState.of(draft()).copy(
            totalText = "1 000",
            discountText = "100",
            serviceText = "50",
        )

        assertTrue(state.valid)
        val updated = state.apply(draft())
        assertEquals(100_000, updated.totalKopecks)
        assertEquals(10_000, updated.discountKopecks)
        assertEquals(5_000, updated.serviceKopecks)
    }

    @Test
    fun `невалидный итог не меняет черновик`() {
        val base = draft()
        val state = TotalsEditState.of(base).copy(totalText = "")

        assertFalse(state.valid)
        assertEquals(base, state.apply(base))
    }

    @Test
    fun `скидка больше суммы позиций делает лист невалидным`() {
        val state = TotalsEditState(
            totalText = "1000",
            discountText = "1200",
            serviceText = "0",
            itemsSumKopecks = 100_000,
        )

        assertTrue(!state.valid)
        assertTrue(state.discountTooBig)
    }

    @Test
    fun `скидка ровно 100 процентов валидна`() {
        val state = TotalsEditState(
            totalText = "0",
            discountText = "1000",
            serviceText = "0",
            itemsSumKopecks = 100_000,
        )

        assertTrue(state.valid)
        assertTrue(!state.discountTooBig)
    }

    @Test
    fun `невалидная скидка не меняет черновик`() {
        val draft = ReceiptDraft(
            items = listOf(DraftItem(1, "Кофе", 1.0, 100_000)),
            totalKopecks = 100_000,
        )
        val state = TotalsEditState(
            totalText = "1000",
            discountText = "1200",
            serviceText = "0",
            itemsSumKopecks = 100_000,
        )

        assertEquals(draft, state.apply(draft))
    }

    @Test
    fun `старое состояние без суммы позиций не блокирует скидку`() {
        val state = TotalsEditState(
            totalText = "1000",
            discountText = "200",
            serviceText = "0",
            itemsSumKopecks = null,
        )

        assertTrue(state.valid)
        assertTrue(!state.discountTooBig)
    }
}

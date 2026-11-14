package ru.chekcalc.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.chekcalc.model.Discount
import ru.chekcalc.model.Receipt
import ru.chekcalc.model.ReceiptItem
import ru.chekcalc.model.ServiceCharge
import ru.chekcalc.ui.review.ReceiptDraft

class ReceiptDraftTest {

    private fun receipt(vararg amounts: Double, total: Double = amounts.sum()) = Receipt(
        currency = "RUB",
        items = amounts.mapIndexed { index, amount ->
            ReceiptItem(name = "Позиция ${index + 1}", qty = 1.0, unitPrice = amount, amount = amount)
        },
        discount = Discount(),
        serviceCharge = ServiceCharge(),
        total = total,
    )

    @Test
    fun `распознанный чек превращается в черновик с суммой позиций`() {
        val draft = ReceiptDraft.fromReceipt(receipt(100.0, 250.50, 30.0))

        assertEquals(3, draft.items.size)
        assertEquals(38_050L, draft.itemsSumKopecks)
        assertTrue(draft.residualOk)
        assertNull(draft.residualWarning())
    }

    @Test
    fun `правка цены пересчитывает сумму шапки`() {
        val draft = ReceiptDraft.fromReceipt(receipt(100.0, 250.50))
        val first = draft.items.first()

        val edited = draft.changeUnitPrice(first.id, 15_000L)

        assertEquals(40_050L, edited.itemsSumKopecks)
        assertEquals(15_000L, edited.items.first().amountKopecks)
    }

    @Test
    fun `расхождение на пятьдесят рублей объясняется числом`() {
        val draft = ReceiptDraft.fromReceipt(receipt(100.0, 200.0, total = 350.0))

        val warning = draft.residualWarning()!!

        assertFalse(draft.residualOk)
        // Проверяем все три числа: ожидание по формуле, итог чека и разницу.
        assertTrue("ожидаемая сумма по формуле: $warning", warning.contains("ожидалось 300 ₽"))
        assertTrue("итог чека: $warning", warning.contains("в чеке 350 ₽"))
        assertTrue("разница: $warning", warning.contains("разница 50 ₽"))
        assertTrue("итог больше — подсказка про пропущенную строку", warning.contains("пропущена"))
    }

    @Test
    fun `итог меньше позиций подсказывает про скидку`() {
        val draft = ReceiptDraft.fromReceipt(receipt(100.0, total = 80.0))

        val warning = draft.residualWarning()!!

        assertTrue("ожидалось 100 ₽: $warning", warning.contains("ожидалось 100 ₽"))
        assertTrue("разница −20 ₽: $warning", warning.contains("разница −20 ₽"))
        assertTrue("подсказка должна говорить про скидку или цену", warning.contains("скидку"))
    }

    @Test
    fun `скидка учитывается в ожидаемой сумме`() {
        // Позиции 100, скидка 50 → ожидаем 50, в чеке 80, разница +30.
        val draft = ReceiptDraft(
            items = listOf(ru.chekcalc.ui.review.DraftItem(1, "Кофе", 1.0, 100_00)),
            discountKopecks = 50_00,
            totalKopecks = 80_00,
        )

        val warning = draft.residualWarning()!!

        assertTrue("скидка должна вычитаться: $warning", warning.contains("ожидалось 50 ₽"))
        assertTrue("итог чека: $warning", warning.contains("в чеке 80 ₽"))
        assertTrue("разница +30: $warning", warning.contains("разница 30 ₽"))
    }

    @Test
    fun `обслуживание прибавляется к ожидаемой сумме`() {
        // Позиции 100, обслуживание 10 → ожидаем 110, в чеке 130, разница +20.
        val draft = ReceiptDraft(
            items = listOf(ru.chekcalc.ui.review.DraftItem(1, "Кофе", 1.0, 100_00)),
            serviceKopecks = 10_00,
            totalKopecks = 130_00,
        )

        val warning = draft.residualWarning()!!

        assertTrue("обслуживание должно прибавляться: $warning", warning.contains("ожидалось 110 ₽"))
        assertTrue("итог чека: $warning", warning.contains("в чеке 130 ₽"))
        assertTrue("разница +20: $warning", warning.contains("разница 20 ₽"))
    }

    @Test
    fun `расхождение до рубля считается нормой`() {
        val draft = ReceiptDraft.fromReceipt(receipt(100.0, total = 101.0))

        assertTrue(draft.residualOk)
        assertNull(draft.residualWarning())
    }

    @Test
    fun `скидка и обслуживание входят в сверку`() {
        val draft = ReceiptDraft(
            items = listOf(ru.chekcalc.ui.review.DraftItem(1, "Кофе", 1.0, 100_00)),
            discountKopecks = 10_00,
            serviceKopecks = 5_00,
            totalKopecks = 95_00,
        )

        assertTrue("100 − 10 + 5 = 95", draft.residualOk)
    }

    @Test
    fun `добавление и удаление позиций`() {
        val draft = ReceiptDraft.fromReceipt(receipt(100.0))

        val added = draft.addItem("Хлеб", 5_000L)
        assertEquals(2, added.items.size)
        assertEquals(15_000L, added.itemsSumKopecks)

        val removed = added.removeItem(added.items.first().id)
        assertEquals(1, removed.items.size)
        assertEquals("Хлеб", removed.items.first().name)
    }

    @Test
    fun `дальше не блокируется расхождением`() {
        val draft = ReceiptDraft.fromReceipt(receipt(100.0, total = 500.0))

        assertFalse(draft.residualOk)
        assertTrue(draft.canProceed)
    }

    @Test
    fun `сумма принимает запятую и точку`() {
        assertEquals(1_250L, ru.chekcalc.ui.review.parseRubles("12,50"))
        assertEquals(1_250L, ru.chekcalc.ui.review.parseRubles("12.50"))
        assertEquals(1_200L, ru.chekcalc.ui.review.parseRubles("12"))
    }

    @Test
    fun `пустой и мусорный ввод не меняют сумму`() {
        assertEquals(null, ru.chekcalc.ui.review.parseRubles(""))
        assertEquals(null, ru.chekcalc.ui.review.parseRubles("  "))
        assertEquals(null, ru.chekcalc.ui.review.parseRubles("NaN"))
        // «12.» — незаконченный ввод, но число уже валидное: 12.00
        assertEquals(1_200L, ru.chekcalc.ui.review.parseRubles("12."))
        assertEquals(null, ru.chekcalc.ui.review.parseRubles("1e18"))
        assertEquals(null, ru.chekcalc.ui.review.parseRubles("-5"))
    }

    @Test
    fun `правка количества пересчитывает сумму по прежней цене`() {
        val draft = ReceiptDraft.fromReceipt(receipt(150.0))
        val item = draft.items.first()

        val edited = draft.changeQty(item.id, 2.0)

        assertEquals(15_000L, edited.items.first().unitPriceKopecks)
        assertEquals(30_000L, edited.items.first().amountKopecks)
        assertEquals(30_000L, edited.itemsSumKopecks)
    }

    @Test
    fun `правка итога, скидки и обслуживания меняет сверку`() {
        val draft = ReceiptDraft.fromReceipt(receipt(100.0))

        val edited = draft.changeTotal(150_00).changeDiscount(10_00).changeService(5_00)

        assertEquals(150_00L, edited.totalKopecks)
        // 150 − (100 − 10 + 5) = 55 ₽
        assertEquals(55_00L, edited.residualKopecks)
        assertFalse(edited.residualOk)
        assertTrue(edited.residualWarning()!!.contains("55"))
    }

    @Test
    fun `позиция без количества сохраняет сумму из чека`() {
        val receipt = Receipt(
            currency = "RUB",
            items = listOf(ReceiptItem(name = "Сыр", qty = null, unitPrice = 100.0, amount = 300.0)),
            total = 300.0,
        )

        val draft = ReceiptDraft.fromReceipt(receipt)

        assertEquals(30_000L, draft.itemsSumKopecks)
    }

    @Test
    fun `дробное количество считается без ошибки округления`() {
        // 199 ₽ × 0,205 = 40,795 → 40,80; через Double вышло бы 40,79.
        val draft = ReceiptDraft(items = listOf(ru.chekcalc.ui.review.DraftItem(1, "Сыр", 0.205, 199_00)))

        assertEquals(40_80L, draft.itemsSumKopecks)
    }

    @Test
    fun `количество принимает запятую и отклоняет мусор`() {
        assertEquals(2.5, ru.chekcalc.ui.review.parseQty("2,5")!!, 0.0001)
        assertEquals(3.0, ru.chekcalc.ui.review.parseQty("3")!!, 0.0001)
        assertEquals(null, ru.chekcalc.ui.review.parseQty(""))
        assertEquals(null, ru.chekcalc.ui.review.parseQty("NaN"))
        assertEquals(null, ru.chekcalc.ui.review.parseQty("-1"))
        assertEquals(null, ru.chekcalc.ui.review.parseQty("100000"))
    }

    @Test
    fun `переименование не меняет сумму`() {
        val draft = ReceiptDraft.fromReceipt(receipt(100.0))
        val renamed = draft.rename(draft.items.first().id, "Кофе с молоком")

        assertEquals("Кофе с молоком", renamed.items.first().name)
        assertEquals(draft.itemsSumKopecks, renamed.itemsSumKopecks)
    }
}

package ru.chekcalc.select

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.chekcalc.ui.review.DraftItem
import ru.chekcalc.ui.review.ReceiptDraft
import ru.chekcalc.ui.select.Choice
import ru.chekcalc.ui.select.SelectionState

class SelectionStateTest {

    private fun state(vararg amounts: Long): SelectionState = SelectionState(
        items = amounts.mapIndexed { index, amount ->
            ru.chekcalc.ui.select.SelectableItem(index + 1L, "Позиция ${index + 1}", amount)
        },
    )

    @Test
    fun `по умолчанию ничего не отмечено и сумма ноль`() {
        val selected = state(78_000, 20_000, 15_000)

        assertEquals(0L, selected.totalKopecks)
        assertTrue(selected.items.all { it.choice is Choice.None })
    }

    @Test
    fun `позиция целиком попадает в сумму`() {
        val selected = state(78_000).toggleWhole(1)

        assertEquals(78_000L, selected.totalKopecks)
    }

    @Test
    fun `общая позиция на троих даёт треть`() {
        val selected = state(90_000).setShared(1, 3)

        assertEquals(30_000L, selected.totalKopecks)
    }

    @Test
    fun `чаевые десять процентов дают сто рублей на тысячу`() {
        val selected = state(100_000).toggleWhole(1).setTipPercent(10)

        assertEquals(100_000L, selected.billShareKopecks)
        assertEquals(10_000L, selected.tipKopecks)
        assertEquals(110_000L, selected.totalKopecks)
    }

    @Test
    fun `снятие галочки с общей позиции обнуляет выбор`() {
        val selected = state(90_000).setShared(1, 3)

        val cleared = selected.toggleWhole(1)

        assertEquals(0L, cleared.totalKopecks)
        assertTrue(cleared.items.first().choice is Choice.None)
    }

    @Test
    fun `процент чаевых считается от точной суммы, а не от округлённой`() {
        // Своя часть 100,46 ₽: 10 % — это 10,05 ₽, итог 110,51 → 111 ₽.
        val selected = state(10_046).toggleWhole(1).setTipPercent(10)

        assertEquals(10_046, selected.billShareExactKopecks.toInt())
        assertEquals(1_005L, selected.tipKopecks)
        assertEquals(11_100L, selected.totalKopecks)
    }

    @Test
    fun `процент считается до округления доли`() {
        // 119,09 ₽ пополам = 59,545 ₽; 10 % = 5,9545; итог 65,4995 → 65 ₽.
        val selected = state(11_909).setShared(1, 2).setTipPercent(10)

        assertEquals(595L, selected.tipKopecks)
        assertEquals(6_500L, selected.totalKopecks)
    }

    @Test
    fun `округляется только итог, а не чаевые`() {
        // 100,45 ₽ + 10 % = 110,495 → 110 ₽ (промежуточное округление дало бы 111).
        val selected = state(10_045).toggleWhole(1).setTipPercent(10)

        assertEquals(1_005L, selected.tipKopecks)
        assertEquals(11_000L, selected.totalKopecks)
    }

    @Test
    fun `скидка на чужую позицию тоже распределяется`() {
        // Моя 100 ₽, чужая 10 ₽, скидка 0,50 ₽ на весь чек, чаевые 10 %.
        val selected = SelectionState(
            items = listOf(
                ru.chekcalc.ui.select.SelectableItem(1, "Моя", 10_000, Choice.Whole),
                ru.chekcalc.ui.select.SelectableItem(2, "Чужая", 1_000, Choice.None),
            ),
            discountKopecks = 50,
        ).setTipPercent(10)

        assertEquals(11_000L, selected.totalKopecks)
    }

    @Test
    fun `итог на ровной границе считается одной дробью`() {
        // Позиции 20 + 3 ₽, моя 20 ₽, скидка 2,50 ₽, чаевые 15 % → ровно 20,50 ₽ → 21 ₽.
        val selected = SelectionState(
            items = listOf(
                ru.chekcalc.ui.select.SelectableItem(1, "Моя", 2_000, Choice.Whole),
                ru.chekcalc.ui.select.SelectableItem(2, "Чужая", 300, Choice.None),
            ),
            discountKopecks = 250,
        ).setTipPercent(15)

        assertEquals(2_100L, selected.totalKopecks)
    }

    @Test
    fun `повторный выбор доли снимает её`() {
        val selected = state(90_000).setShared(1, 3).setShared(1, 3)

        assertEquals(0L, selected.totalKopecks)
    }

    @Test
    fun `повторное нажатие на позицию снимает отметку`() {
        val selected = state(78_000).toggleWhole(1).toggleWhole(1)

        assertEquals(0L, selected.totalKopecks)
    }

    @Test
    fun `своя сумма чаевых важнее процента`() {
        val selected = state(100_000).toggleWhole(1).setTipFixed(25_000)

        assertEquals(25_000L, selected.tipKopecks)
        assertEquals(125_000L, selected.totalKopecks)
    }

    @Test
    fun `скидка и обслуживание распределяются до чаевых`() {
        val selected = state(100_000)
            .toggleWhole(1)
            .copy(discountKopecks = 10_000, serviceKopecks = 5_000)
            .setTipPercent(10)

        assertEquals(95_000L, selected.billShareKopecks)
        assertEquals(9_500L, selected.tipKopecks)
        assertEquals(104_500L, selected.totalKopecks)
    }

    @Test
    fun `черновик проверки превращается в выбор без отметок`() {
        val draft = ReceiptDraft(
            items = listOf(DraftItem(1, "Кофе", 1.0, 30_000), DraftItem(2, "Торт", 1.0, 45_000)),
            totalKopecks = 75_000,
        )

        val selected = SelectionState.fromDraft(draft)

        assertEquals(2, selected.items.size)
        assertEquals(75_000L, selected.items.sumOf { it.amountKopecks })
        assertEquals(0L, selected.totalKopecks)
    }
}

package ru.chekcalc.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.chekcalc.ui.select.Choice
import ru.chekcalc.ui.select.SelectableItem
import ru.chekcalc.ui.select.SelectionState
import ru.chekcalc.storage.Payer
import ru.chekcalc.ui.summary.ReceiptSummary

class ReceiptSummaryTest {

    private fun state(vararg items: SelectableItem) = SelectionState(items = items.toList())

    @Test
    fun `итог повторяет сумму выбора`() {
        val selected = state(
            SelectableItem(1, "Кофе", 39_025, Choice.Whole),
            SelectableItem(2, "Торт", 45_000, Choice.None),
        )

        val summary = ReceiptSummary.from(selected)

        assertEquals(selected.totalKopecks, summary.totalKopecks)
        assertEquals(39_025L, summary.itemsShareKopecks)
    }

    @Test
    fun `разбивка показывает чаевые и итог`() {
        val selected = state(SelectableItem(1, "Ужин", 100_000, Choice.Whole)).setTipPercent(10)

        val lines = ReceiptSummary.from(selected).lines()

        assertEquals(listOf("Ваши позиции", "Чаевые", "Итого"), lines.map { it.first })
        assertEquals("1000 ₽", lines[0].second)
        assertEquals("100 ₽", lines[1].second)
        assertEquals("1100 ₽", lines[2].second)
    }

    @Test
    fun `скидка и обслуживание попадают в разбивку`() {
        val selected = state(
            SelectableItem(1, "Моя", 10_000, Choice.Whole),
            SelectableItem(2, "Чужая", 1_000, Choice.None),
        ).copy(discountKopecks = 50, serviceKopecks = 100)

        val summary = ReceiptSummary.from(selected)
        val labels = summary.lines().map { it.first }

        assertTrue("Скидка" in labels)
        assertTrue("Обслуживание" in labels)
        assertTrue(summary.discountKopecks > 0)
        assertTrue(summary.serviceKopecks > 0)
    }

    @Test
    fun `в тексте одна строка итога, без дубля`() {
        val summary = ReceiptSummary.from(state(SelectableItem(1, "Обед", 78_000, Choice.Whole)))

        val text = summary.shareText()

        assertEquals(1, text.lines().count { it.startsWith("Итого") })
        assertTrue("Итого к переводу" !in text)
    }

    @Test
    fun `чужая валюта не подписывается рублём`() {
        val selected = SelectionState(
            items = listOf(SelectableItem(1, "Обед", 17_460_000, Choice.Whole)),
            currency = "KRW",
        )

        val summary = ReceiptSummary.from(selected)
        val text = summary.shareText()

        assertTrue(text.contains("KRW"))
        assertTrue("₽" !in text)
    }

    @Test
    fun `текст для шаринга содержит итог`() {
        val summary = ReceiptSummary.from(
            state(SelectableItem(1, "Обед", 78_000, Choice.Whole)),
        )

        val text = summary.shareText()

        assertTrue(text.contains("Расчёт по чеку"))
        assertTrue(text.contains("Итого: 780 ₽"))
    }

    @Test
    fun `текст шаринга называет кому переводить`() {
        val summary = ReceiptSummary.from(state(SelectableItem(1, "Обед", 78_000, Choice.Whole)))

        val text = summary.shareText(Payer(name = "Аня", phone = "+7 999 123-45-67"))

        assertTrue(text.contains("Перевод на +7 999 123-45-67 (Аня)"))
    }

    @Test
    fun `без реквизитов строка перевода не появляется`() {
        val summary = ReceiptSummary.from(state(SelectableItem(1, "Обед", 78_000, Choice.Whole)))

        val text = summary.shareText(Payer())

        assertTrue(text.isNotBlank())
        assertTrue("Перевод на" !in text)
    }

    @Test
    fun `телефон без имени пишется один`() {
        val summary = ReceiptSummary.from(state(SelectableItem(1, "Обед", 78_000, Choice.Whole)))

        val text = summary.shareText(Payer(phone = "+7 900 000-00-00"))

        assertTrue(text.contains("Перевод на +7 900 000-00-00"))
        assertTrue("()" !in text)
    }

    @Test
    fun `реквизиты переживают сохранение состояния`() {
        val payer = Payer(name = "Аня", phone = "+7 999 123-45-67")

        val restored = ru.chekcalc.PayerSaver.restore(listOf(payer.name, payer.phone))
            ?: error("Saver не восстановил реквизиты")

        assertEquals(payer, restored)
    }

    @Test
    fun `считает свои и общие позиции`() {
        val summary = ReceiptSummary.from(
            state(
                SelectableItem(1, "Кофе", 30_000, Choice.Whole),
                SelectableItem(2, "Пицца", 90_000, Choice.Shared(3)),
                SelectableItem(3, "Чужое", 10_000, Choice.None),
            ),
        )

        assertEquals(1, summary.ownItems)
        assertEquals(1, summary.sharedItems)
        assertEquals(30_000L + 30_000L, summary.totalKopecks)
    }

    @Test
    fun `пустой выбор даёт ноль и только итог в разбивке`() {
        val summary = ReceiptSummary.from(state(SelectableItem(1, "Кофе", 30_000)))

        assertEquals(0L, summary.totalKopecks)
        assertEquals(listOf("Ваши позиции", "Итого"), summary.lines().map { it.first })
        assertEquals("0 ₽", summary.lines().last().second)
    }
}

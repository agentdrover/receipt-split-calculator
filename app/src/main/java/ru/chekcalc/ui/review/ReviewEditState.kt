package ru.chekcalc.ui.review

/**
 * Состояние листа правки позиции.
 *
 * Живёт отдельно от вёрстки: лист — только поля, а правила (что считать
 * валидным и как применить правку к черновику) проверяются тестами.
 * Пустое количество означает «одна штука» — так же, как в черновике.
 */
data class ItemEditState(
    val itemId: Long? = null,
    val name: String = "",
    val qtyText: String = "",
    val priceText: String = "",
) {
    val qty: Double? get() = if (qtyText.isBlank()) null else parseQty(qtyText)

    val priceKopecks: Long? get() = parseRubles(priceText)

    val valid: Boolean
        get() = name.isNotBlank() && priceKopecks != null && (qtyText.isBlank() || qty != null)

    /** Применяет правку к черновику; невалидное состояние не меняет его. */
    fun apply(draft: ReceiptDraft): ReceiptDraft {
        if (!valid) return draft
        val price = priceKopecks ?: return draft
        val quantity = qty
        return if (itemId != null) {
            draft
                .rename(itemId, name.trim())
                .changeQty(itemId, quantity)
                .changeUnitPrice(itemId, price)
        } else {
            val added = draft.addItem(name = name.trim())
            val id = added.items.last().id
            added.changeQty(id, quantity).changeUnitPrice(id, price)
        }
    }

    companion object {
        fun of(item: DraftItem): ItemEditState =
            ItemEditState(
                itemId = item.id,
                name = item.name,
                qtyText = item.qty?.let { formatQty(it) } ?: "",
                priceText = rublesText(item.unitPriceKopecks),
            )

        fun new(): ItemEditState = ItemEditState()
    }
}

/** Состояние листа «Итог чека»: итог, скидка и обслуживание. */
data class TotalsEditState(
    val totalText: String = "",
    val discountText: String = "",
    val serviceText: String = "",
    /** Сумма позиций; null — состояние прежней версии, сверять не с чем. */
    val itemsSumKopecks: Long? = null,
) {
    val total: Long? get() = parseRubles(totalText)

    val discount: Long? get() = parseRubles(discountText)

    val service: Long? get() = parseRubles(serviceText)

    /** Скидка не может превышать сумму позиций; ровно 100 % — допустимо. */
    val discountTooBig: Boolean
        get() = itemsSumKopecks?.let { (discount ?: 0) > it } ?: false

    val valid: Boolean get() = total != null && discount != null && service != null && !discountTooBig

    fun apply(draft: ReceiptDraft): ReceiptDraft {
        if (!valid) return draft
        val t = total ?: return draft
        val d = discount ?: return draft
        val s = service ?: return draft
        return draft.changeTotal(t).changeDiscount(d).changeService(s)
    }

    companion object {
        fun of(draft: ReceiptDraft): TotalsEditState =
            TotalsEditState(
                totalText = rublesText(draft.totalKopecks),
                discountText = rublesText(draft.discountKopecks),
                serviceText = rublesText(draft.serviceKopecks),
                itemsSumKopecks = draft.itemsSumKopecks,
            )
    }
}

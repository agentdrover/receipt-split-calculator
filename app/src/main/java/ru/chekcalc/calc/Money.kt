package ru.chekcalc.calc

import kotlin.math.abs

/** Деньги считаем целыми копейками, чтобы не ловить 0.1 + 0.2. */
object Money {
    /**
     * Сумма с валютой чека.
     *
     * Рубли печатаются знаком «₽», остальные — кодом валюты: символов для всех
     * валют нет, а подписать воны рублём хуже, чем показать код (#2327).
     */
    fun format(
        kopecks: Long,
        currency: String = "RUB",
    ): String {
        val body = formatKopecks(kopecks)
        if (currency == "RUB") return body
        // «1 000,00 KRW»: отрезаем « ₽» и подставляем код.
        return body.removeSuffix(" ₽") + " " + currency
    }

    private fun formatKopecks(kopecks: Long): String {
        // abs(Long.MIN_VALUE) переполняется и остаётся отрицательным.
        if (kopecks == Long.MIN_VALUE) return "−92233720368547758,08 ₽"
        val sign = if (kopecks < 0) "−" else ""
        val value = abs(kopecks)
        val rubles = value / 100
        val rest = value % 100
        return if (rest == 0L) {
            "$sign$rubles ₽"
        } else {
            "$sign$rubles,${rest.toString().padStart(2, '0')} ₽"
        }
    }
}

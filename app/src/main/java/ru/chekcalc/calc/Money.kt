package ru.chekcalc.calc

import kotlin.math.abs

/** Деньги считаем целыми копейками, чтобы не ловить 0.1 + 0.2. */
object Money {
    fun format(kopecks: Long): String {
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

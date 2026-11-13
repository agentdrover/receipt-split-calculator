package ru.chekcalc.calc

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Рубли из ответа провайдера → целые копейки.
 *
 * Провайдер присылает числа в рублях (390.25), расчёт работает в копейках.
 * Перевод в одном месте, чтобы не разъехался масштаб: 390.25 → 39025.
 */
fun rublesToKopecks(value: Double): Long {
    require(value.isFinite()) { "сумма не число" }
    val kopecks = BigDecimal.valueOf(value).movePointRight(2).setScale(0, RoundingMode.HALF_UP)
    // Молчаливое переполнение превратило бы крупную сумму в отрицательные копейки.
    require(kopecks.abs() <= BigDecimal.valueOf(Long.MAX_VALUE)) { "сумма вне допустимого диапазона" }
    return kopecks.toLong()
}

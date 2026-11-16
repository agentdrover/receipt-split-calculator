package ru.chekcalc.ui.review

import androidx.compose.ui.geometry.Offset

/**
 * Масштаб и сдвиг фото на экране проверки.
 *
 * Держим математику отдельно от вёрстки: жесты меняют состояние, а границы
 * (не мельче исходного и не крупнее [MAX_SCALE]) проверяются здесь и тестами.
 */
data class PhotoViewState(
    val scale: Float = MIN_SCALE,
    val offset: Offset = Offset.Zero,
) {
    /** Щипок: масштаб в границах; на исходном масштабе сдвиг сбрасывается. */
    fun zoom(factor: Float): PhotoViewState {
        if (!factor.isFinite() || factor <= 0f) return this
        val next = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        return copy(scale = next, offset = if (next == MIN_SCALE) Offset.Zero else offset)
    }

    /** Сдвиг пальцем: на исходном масштабе фото и так целиком, сдвиг не нужен. */
    fun pan(delta: Offset): PhotoViewState = if (scale <= MIN_SCALE) this else copy(offset = offset + delta)

    fun reset(): PhotoViewState = PhotoViewState()

    companion object {
        /** Масштаб «фото целиком», меньше показывать нечего. */
        const val MIN_SCALE = 1f

        /** Потолок увеличения: дальше JPEG уже мыло, а память кончается. */
        const val MAX_SCALE = 4f
    }
}

package ru.chekcalc.image

/** Размер в пикселях; свой, чтобы расчёт проверялся на JVM без Android. */
data class PixelSize(val width: Int, val height: Int)

/**
 * Геометрия подготовки фото к отправке: длинная сторона не больше 1600 px,
 * тело — не больше 1,5 МБ (§5 спеки).
 */
object PhotoSizing {
    const val MAX_SIDE = 1600
    const val MAX_BYTES = 1_500_000

    /** Целевой размер с сохранением пропорций; мелкие фото не растягиваем. */
    fun targetSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): PixelSize {
        if (width <= 0 || height <= 0) return PixelSize(0, 0)
        val longest = maxOf(width, height)
        if (longest <= maxSide) return PixelSize(width, height)
        val scale = maxSide.toDouble() / longest
        return PixelSize(
            width = (width * scale).toInt().coerceAtLeast(1),
            height = (height * scale).toInt().coerceAtLeast(1),
        )
    }

    /**
     * Во сколько раз уменьшать при декодировании: берём так, чтобы после
     * чтения длинная сторона была не меньше целевой, но не в разы больше.
     */
    fun inSampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    fun fitsLimit(bytes: Int): Boolean = bytes <= MAX_BYTES
}

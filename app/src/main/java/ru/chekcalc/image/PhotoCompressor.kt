package ru.chekcalc.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Готовит фото к отправке: уменьшает до 1600 px, разворачивает по EXIF,
 * сжимает в JPEG с потолком 1,5 МБ.
 *
 * Работает с Uri из камеры и из галереи — в обоих случаях читает через
 * contentResolver, поэтому файловая система не важна.
 */
class PhotoCompressor(private val context: Context) {

    fun compress(source: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(source).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "не удалось прочитать изображение" }

        val options = BitmapFactory.Options().apply {
            inSampleSize = PhotoSizing.inSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = openStream(source).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("не удалось прочитать изображение")

        val oriented = applyOrientation(source, decoded)
        val target = PhotoSizing.targetSize(oriented.width, oriented.height)
        var scaled = if (target.width != oriented.width || target.height != oriented.height) {
            Bitmap.createScaledBitmap(oriented, target.width, target.height, true)
        } else {
            oriented
        }

        // Если на минимальном качестве всё ещё больше потолка — уменьшаем
        // сам кадр, но не больше двух раз, иначе честно падаем.
        repeat(MAX_SHRINK_STEPS) {
            val bytes = encode(scaled)
            if (PhotoSizing.fitsLimit(bytes.size)) return bytes
            scaled = Bitmap.createScaledBitmap(
                scaled,
                (scaled.width * SHRINK_FACTOR).toInt().coerceAtLeast(1),
                (scaled.height * SHRINK_FACTOR).toInt().coerceAtLeast(1),
                true,
            )
        }
        throw IllegalStateException(
            "не удалось сжать фото до ${PhotoSizing.MAX_BYTES / 1000} КБ",
        )
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        var quality = 85
        var bytes = encodeWith(bitmap, quality)
        // Потолок 1,5 МБ: снижаем качество, но ниже 45 не падаем — лучше
        // уменьшить кадр, чем отправить нечитаемое фото.
        while (!PhotoSizing.fitsLimit(bytes.size) && quality > MIN_QUALITY) {
            quality -= 10
            bytes = encodeWith(bitmap, quality)
        }
        return bytes
    }

    private fun encodeWith(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    private fun applyOrientation(source: Uri, bitmap: Bitmap): Bitmap {
        val orientation = PhotoOrientation.read { openStream(source) }
        return PhotoOrientation.apply(bitmap, orientation)
    }

    private fun openStream(uri: Uri) =
        context.contentResolver.openInputStream(uri) ?: error("нет доступа к файлу")

    private companion object {
        const val MIN_QUALITY = 45
        const val SHRINK_FACTOR = 0.75
        const val MAX_SHRINK_STEPS = 2
    }
}

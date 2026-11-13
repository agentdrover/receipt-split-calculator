package ru.chekcalc.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.chekcalc.image.PhotoSizing
import ru.chekcalc.image.PixelSize

class PhotoSizingTest {

    @Test
    fun `фото 4000 на 3000 уменьшается до 1600 на 1200`() {
        assertEquals(PixelSize(1600, 1200), PhotoSizing.targetSize(4000, 3000))
    }

    @Test
    fun `портрет 3000 на 4000 даёт 1200 на 1600`() {
        assertEquals(PixelSize(1200, 1600), PhotoSizing.targetSize(3000, 4000))
    }

    @Test
    fun `мелкое фото не растягивается`() {
        assertEquals(PixelSize(1000, 800), PhotoSizing.targetSize(1000, 800))
    }

    @Test
    fun `вырожденные размеры дают ноль`() {
        assertEquals(PixelSize(0, 0), PhotoSizing.targetSize(0, 100))
        assertEquals(PixelSize(0, 0), PhotoSizing.targetSize(-5, 100))
    }

    @Test
    fun `стороны остаются целыми и не нулевыми`() {
        val size = PhotoSizing.targetSize(4001, 3)
        assertTrue(size.width > 0 && size.height > 0)
        assertEquals(1600, size.width)
    }

    @Test
    fun `предпросмотр уменьшается по длинной стороне`() {
        assertEquals(2, PhotoSizing.inSampleSize(4000, 3000, 1024))
        assertEquals(4, PhotoSizing.inSampleSize(8000, 6000, 1024))
    }

    @Test
    fun `потолок тела полтора мегабайта`() {
        assertTrue(PhotoSizing.fitsLimit(1_500_000))
        assertFalse(PhotoSizing.fitsLimit(1_500_001))
    }

    @Test
    fun `декодирование уменьшает настолько, чтобы не читать лишнее`() {
        assertEquals(1, PhotoSizing.inSampleSize(1600, 1200))
        assertEquals(2, PhotoSizing.inSampleSize(3200, 2400))
        assertEquals(4, PhotoSizing.inSampleSize(6400, 4800))
    }
}

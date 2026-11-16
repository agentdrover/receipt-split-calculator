package ru.chekcalc.review

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.chekcalc.ui.review.PhotoViewState

class PhotoViewStateTest {
    @Test
    fun `щипок увеличивает фото в границах`() {
        assertEquals(2f, PhotoViewState().zoom(2f).scale, 1e-6f)
        assertEquals(PhotoViewState.MAX_SCALE, PhotoViewState().zoom(10f).scale, 1e-6f)
    }

    @Test
    fun `уменьшение ниже исходного не уходит`() {
        assertEquals(PhotoViewState.MIN_SCALE, PhotoViewState(scale = 2f).zoom(0.1f).scale, 1e-6f)
    }

    @Test
    fun `на исходном масштабе сдвиг не копится`() {
        assertEquals(Offset.Zero, PhotoViewState().pan(Offset(50f, 50f)).offset)
    }

    @Test
    fun `увеличенное фото сдвигается`() {
        assertEquals(Offset(10f, -5f), PhotoViewState().zoom(2f).pan(Offset(10f, -5f)).offset)
    }

    @Test
    fun `сброс возвращает исходный вид`() {
        assertEquals(
            PhotoViewState(),
            PhotoViewState().zoom(3f).pan(Offset(20f, 20f)).reset(),
        )
    }

    @Test
    fun `мусорный коэффициент не ломает масштаб`() {
        assertEquals(PhotoViewState.MIN_SCALE, PhotoViewState().zoom(Float.NaN).scale, 1e-6f)
        assertEquals(PhotoViewState.MIN_SCALE, PhotoViewState().zoom(0f).scale, 1e-6f)
    }
}

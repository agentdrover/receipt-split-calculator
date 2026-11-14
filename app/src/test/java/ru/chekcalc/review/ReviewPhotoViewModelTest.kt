package ru.chekcalc.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.chekcalc.ui.review.ReviewPhotoViewModel

class ReviewPhotoViewModelTest {

    @Test
    fun `фото ставится и очищается`() {
        val model = ReviewPhotoViewModel()
        assertNull("до распознавания фото нет", model.photo)

        model.set(byteArrayOf(1, 2, 3))
        assertEquals(3, model.photo?.size)

        model.clear()
        assertNull("после ухода с проверки фото не хранится", model.photo)
    }
}

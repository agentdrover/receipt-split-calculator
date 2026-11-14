package ru.chekcalc.ui.review

import androidx.lifecycle.ViewModel

/**
 * Фото чека на время сессии проверки.
 *
 * Байты держим в ViewModel, а не в remember: поворот экрана не должен стирать
 * фото, но и на диск оно не попадает — живёт только пока живёт экран проверки.
 */
class ReviewPhotoViewModel : ViewModel() {
    var photo: ByteArray? = null
        private set

    fun set(bytes: ByteArray?) {
        photo = bytes
    }

    fun clear() {
        photo = null
    }
}

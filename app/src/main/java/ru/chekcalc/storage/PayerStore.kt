package ru.chekcalc.storage

import android.content.Context

/**
 * Реквизиты платившего: имя и телефон.
 *
 * Хранятся только на устройстве и никуда не отправляются. Это не секрет,
 * поэтому обычные SharedPreferences: бэкап отключён в манифесте, так что
 * данные не уезжают и в облако.
 */
class PayerStore(context: Context) {
    private val prefs = context.getSharedPreferences("payer", Context.MODE_PRIVATE)

    fun load(): Payer = Payer(
        name = prefs.getString(KEY_NAME, "").orEmpty(),
        phone = prefs.getString(KEY_PHONE, "").orEmpty(),
    )

    fun save(payer: Payer) {
        prefs.edit()
            .putString(KEY_NAME, payer.name.trim())
            .putString(KEY_PHONE, payer.phone.trim())
            .apply()
    }

    private companion object {
        const val KEY_NAME = "name"
        const val KEY_PHONE = "phone"
    }
}

/** Кому переводить: имя не обязательно, телефон — то, чем платят. */
data class Payer(val name: String = "", val phone: String = "") {
    val isEmpty: Boolean get() = name.isBlank() && phone.isBlank()

    /** «+7 999 123-45-67 (Аня)» или пустая строка. */
    fun display(): String = when {
        phone.isBlank() && name.isBlank() -> ""
        name.isBlank() -> phone
        phone.isBlank() -> name
        else -> "$phone ($name)"
    }
}

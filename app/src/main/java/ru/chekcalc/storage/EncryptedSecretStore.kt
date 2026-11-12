package ru.chekcalc.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Шифрованное хранилище на EncryptedSharedPreferences.
 *
 * Ключ шифрования живёт в Android Keystore и не покидает устройство;
 * файл настроек попадает в резервную копию только если приложение это
 * разрешает, а мы запрещаем (allowBackup=false).
 */
class EncryptedSecretStore(context: Context) : SecretStore {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private companion object {
        const val FILE_NAME = "chekcalc_secrets"
    }
}

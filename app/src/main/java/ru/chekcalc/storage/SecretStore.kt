package ru.chekcalc.storage

/** Хранилище секретов: в приложении — шифрованное, в тестах — в памяти. */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

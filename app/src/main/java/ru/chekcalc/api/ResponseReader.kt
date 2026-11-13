package ru.chekcalc.api

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Читает тело ответа целиком, но не больше потолка.
 *
 * Обрезанный ответ нельзя разбирать: валидное начало с мусорным хвостом
 * выглядело бы как успех. Поэтому при превышении возвращаем null.
 */
internal object ResponseReader {

    fun read(stream: InputStream, maxBytes: Int = MAX_RESPONSE_BYTES): String? {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            if (out.size() + read > maxBytes) return null
            out.write(chunk, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    const val MAX_RESPONSE_BYTES = 1_000_000
}

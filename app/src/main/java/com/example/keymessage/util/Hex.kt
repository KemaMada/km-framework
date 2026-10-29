package com.example.keymessage.util

/**
 * Hexadecimal en la app.
 *
 * NO se usa `com.km.model.toHexString`: es `internal` de km-core, y una funcion
 * interna no es parte de la superficie de un artefacto publicado. Copiarla es
 * preferible a subirla a `public` en el framework por una necesidad de un
 * consumidor: el prefijo de dominio de los identificadores es una regla de
 * KM-ID-0001 y no debe quedar en manos de quien compone las pantallas.
 */
fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX[v ushr 4])
        out.append(HEX[v and 0x0F])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
/**
 * Hexadecimal a bytes. Devuelve `null` si el texto no es hex valido, en vez de
 * lanzar: quien lo usa —el resolvedor de claves de firma— trata "no hay clave"
 * como un resultado legitimo, no como un fallo.
 */
fun String.hexToBytes(): ByteArray? {
    if (isEmpty() || length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

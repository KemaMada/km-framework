package com.km.auth

/**
 * Base64URL sin padding -- RFC 4648 seccion 5.
 *
 * 3F.1 de KM-0002.
 *
 * REGLAS ESTRICTAS DE WIRE
 *
 * - Alfabeto URL-safe: A-Z a-z 0-9 '-' '_'.
 * - `=` (padding) NO se acepta en wire: no aparece en el alfabeto y se
 *   rechaza con un error dedicado para que el codigo del protocolo pueda
 *   distinguir "padding transmitido" de "caracter invalido generico".
 * - Longitud imposible (len % 4 == 1) -> rechazo.
 * - Caracter ajeno al alfabeto (incluido espacio, '\n', '+', '/', '%') ->
 *   rechazo. No se tolera ninguna variante ambigua.
 * - "" decodifica a 0 bytes (valor presente y vacio). Donde haya que
 *   distinguir "ausente", la decision queda en la capa codec, no aqui.
 *
 * Ninguna libreria JSON decide silenciosamente estas reglas.
 */
object Base64Url {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private val DECODE = IntArray(128) { -1 }.also { arr ->
        ALPHABET.forEachIndexed { i, c -> arr[c.code] = i }
    }

    sealed class DecodeError(message: String) : Exception(message) {
        class ImpossibleLength(len: Int) : DecodeError(
            "longitud $len imposible: len % 4 == 1 en Base64URL sin padding"
        )
        class PaddingNotAllowed : DecodeError("padding '=' no permitido en wire")
        class InvalidChar(ch: Char) : DecodeError(
            "caracter '${ch}' (U+${ch.code.toString(16)}) fuera del alfabeto Base64URL"
        )
    }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = ((data[i].toInt() and 0xff) shl 16) or
                ((data[i + 1].toInt() and 0xff) shl 8) or
                (data[i + 2].toInt() and 0xff)
            sb.append(ALPHABET[(n ushr 18) and 63])
            sb.append(ALPHABET[(n ushr 12) and 63])
            sb.append(ALPHABET[(n ushr 6) and 63])
            sb.append(ALPHABET[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xff) shl 16
                sb.append(ALPHABET[(n ushr 18) and 63])
                sb.append(ALPHABET[(n ushr 12) and 63])
            }
            2 -> {
                val n = ((data[i].toInt() and 0xff) shl 16) or
                    ((data[i + 1].toInt() and 0xff) shl 8)
                sb.append(ALPHABET[(n ushr 18) and 63])
                sb.append(ALPHABET[(n ushr 12) and 63])
                sb.append(ALPHABET[(n ushr 6) and 63])
            }
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        if (text.any { it.code >= 128 || DECODE[it.code] < 0 }) {
            val bad = text.first { it.code >= 128 || DECODE.getOrElse(it.code) { -1 } < 0 }
            if (bad == '=') throw DecodeError.PaddingNotAllowed()
            throw DecodeError.InvalidChar(bad)
        }
        if (text.length % 4 == 1) throw DecodeError.ImpossibleLength(text.length)

        val out = ByteArray(
            text.length / 4 * 3 + when (text.length % 4) {
                2 -> 1
                3 -> 2
                else -> 0
            }
        )
        var o = 0
        var buffer = 0
        var bits = 0
        for (c in text) {
            buffer = (buffer shl 6) or DECODE[c.code]
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = ((buffer ushr bits) and 0xff).toByte()
            }
        }
        return out
    }

    fun isValid(text: String): Boolean =
        try {
            decode(text)
            true
        } catch (_: DecodeError) {
            false
        }
}
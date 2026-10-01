package com.km.daemon.util

import java.util.Base64

/**
 * Base64 ESTÁNDAR (RFC 4648 §4, alfabeto `A-Za-z0-9+/` con padding `=`).
 *
 * Es la codificacion del campo `data` de `MESSAGE` (KM-WIRE-RELAY §10). No es
 * la misma que la de los campos de autenticacion, que viajan en Base64URL sin
 * padding (`+` y `/` no son seguros en un identificador de fragmento, y el
 * `=` no pertenece al alfabeto). Mezclarlas seria un fallo de interoperabilidad
 * dificil de ver, asi que viven en dos sitios distintos a proposito:
 *
 * - `data` de `MESSAGE`: aqui, Base64 estándar.
 * - `nonce`, `publicKey`, `signature`: `com.km.auth.Base64Url` de km-core.
 *
 * `java.util.Base64.getDecoder()` es YA estricto en lo que §14.1 exige:
 *
 * - rechaza cualquier caracter fuera del alfabeto, incluidos espacios, saltos
 *   de linea, `-` y `_`;
 * - rechaza longitudes imposibles (`len % 4 == 1`);
 * - acepta el padding ausente, que §14.3 marca como tolerado.
 *
 * Lo que NO hace es adivinar ni reparar, y por eso [decode] devuelve `Result`:
 * un fallo es un frame entero descartado, nunca una carga parcial.
 */
object Base64Standard {

    private val encoder: Base64.Encoder = Base64.getEncoder()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /**
     * Decodificacion ATOMICA (§14.1). Un fallo devuelve `Result.failure` y
     * ningun byte: nunca un array parcial que el destinatario pudiera leer como
     * un `SecureFrame` truncado.
     */
    fun decode(text: String): Result<ByteArray> = runCatching { decoder.decode(text) }

    /**
     * Longitud maxima que puede tener la representacion Base64 estandar de
     * [maxPayloadBytes] bytes, con padding incluido.
     *
     * Se usa como guardia de admision ANTES de decodificar: materializar un
     * array de 80 KB a partir de una cadena mayor no sirve de nada cuando el
     * limite de politica ya lo va a rechazar. La comprobacion que decide sigue
     * siendo la de `RelayServer` (`maxDataPayloadBytes`), que va en bytes de
     * payload; esta va en caracteres de transporte.
     */
    fun maxEncodedLength(maxPayloadBytes: Int): Int = (maxPayloadBytes + 2) / 3 * 4
}

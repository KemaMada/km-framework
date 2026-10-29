package com.example.keymessage.contact

import com.km.identity.CarrierFileCodec
import java.io.InputStream
import java.io.OutputStream

/**
 * TERCER portador del MISMO documento: el fichero `.km`.
 *
 * ## QUE SE MUDO AL FRAMEWORK
 *
 * El movimiento de bytes y el TOPE de lectura, a [CarrierFileCodec]. La app
 * conserva su vocabulario (`FILE_OVERSIZED`, `FILE_READ_FAILED`) y una decision
 * de PRODUCTO: leer un fichero de cero bytes devuelve `""` y el rechazo lo emite
 * `ContactImporter` con `EMPTY_ENVELOPE`. El codec compartido si rechaza el vacio;
 * aqui se traduce para no cambiar lo que la pantalla ya decia.
 *
 * ## POR QUE ISO-8859-1 Y NO UTF-8
 *
 * El portador se produce con ISO-8859-1 y esa es la regla con la que se mide y
 * se descomprime. UTF-8 infla el fichero y, al releerlo, convierte cada byte alto
 * en `U+00XX`: otro documento y la firma no cuadra.
 *
 * ## SIN `trim()`
 *
 * El `\n` que anade un editor queda fuera del stream zlib y se tolera. Recortarlo
 * antes seria normalizar un documento que no es nuestro.
 *
 * ## QUIEN CIERRA LOS FLUJOS
 *
 * Nadie aqui: [write] hace `flush()` y [read] no toca el flujo.
 */
object ContactFileCodec {

    /** Tope del portador en bytes ISO-8859-1: 65536. El limite es ESTRICTO. */
    const val MAX_BYTES: Int = CarrierFileCodec.MAX_BYTES

    /** Rechazo por exceder [MAX_BYTES]. `rejectionCodeOf` lo traduce a `FILE_TOO_LARGE`. */
    const val CODE_OVERSIZED: String = "FILE_OVERSIZED"

    /** Fallo de I/O al leer. */
    const val CODE_READ_FAILED: String = CarrierFileCodec.CODE_READ_FAILED

    /**
     * Portador de cero bytes.
     *
     * Se declara para que el mapeo de `rejectionCodeOf` sea TOTAL sobre los
     * codigos del codec, aunque [read] devuelva `""` y el rechazo lo emita
     * `ContactImporter`.
     */
    const val CODE_EMPTY: String = "FILE_EMPTY"

    /** Escribe [text] como sus bytes ISO-8859-1 exactos. Hace flush, no cierra. */
    fun write(out: OutputStream, text: String) {
        CarrierFileCodec.write(out, text)
    }

    /**
     * Lee el portador de [input] como texto ISO-8859-1, byte a byte. NO cierra y
     * NO hace `trim()`.
     *
     * @throws CodecException con [CODE_OVERSIZED] si el flujo supera [MAX_BYTES]
     *   —sin leer el cuerpo entero—, o con [CODE_READ_FAILED] si el flujo falla.
     */
    fun read(input: InputStream): String {
        val bytes = try {
            CarrierFileCodec.readBytes(input)
        } catch (e: CarrierFileCodec.CarrierFileException) {
            throw CodecException(
                when (e.code) {
                    CarrierFileCodec.CODE_TOO_LARGE -> CODE_OVERSIZED
                    CarrierFileCodec.CODE_READ_FAILED -> CODE_READ_FAILED
                    else -> e.code
                },
                e.message ?: "portador ilegible",
            )
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

    /**
     * Rechazo del codec, con CODIGO ESTABLE.
     *
     * Una excepcion tipada y no `IllegalArgumentException`: el mapeo que ve la
     * pantalla colapsa los `IllegalArgumentException` a "sobre vacio", y un
     * fichero de 10 MB no esta vacio. El codigo no se deduce del mensaje.
     */
    class CodecException(val code: String, message: String) : Exception(message)
}

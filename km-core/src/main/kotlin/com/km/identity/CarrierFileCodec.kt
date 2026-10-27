package com.km.identity

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * El portador como FICHERO: el tercer transporte del MISMO documento.
 *
 * Un `.km` no tiene cabecera, ni magic, ni JSON, ni version: los bytes del
 * fichero son los del texto del QR. Este codec solo los mueve entre flujos y
 * aplica el tope de LECTURA; no interpreta el contenido —eso es [CarrierCodec]—
 * y no anade nada.
 *
 * ## POR QUE ISO-8859-1 Y NO UTF-8
 *
 * El portador se produce con ISO-8859-1 y esa es la regla con la que se mide y
 * se descomprime. Escribir en UTF-8 infla el fichero entre un 30% y un 50%, y
 * releerlo como ISO-8859-1 convierte cada byte alto en `U+00XX`: otro texto, no
 * el mismo documento, y la firma deja de cuadrar. La codificacion esta explicita
 * en los dos sentidos.
 *
 * ## POR QUE NO HAY `trim()`
 *
 * El `\n` de un editor lo tolera [CarrierCodec.decompress] porque queda fuera
 * del stream zlib. Recortarlo antes seria normalizar un documento que no es
 * nuestro.
 *
 * ## EL TOPE, EN LA LECTURA
 *
 * [MAX_BYTES] (64 KiB) se comprueba MIENTRAS se lee, sin acumular el resto: un
 * fichero equivocado de 1 GB se corta en el primer bloque que pasa del tope, en
 * vez de convertirse en un OOM. El limite es estricto: exactamente `MAX_BYTES`
 * se acepta, `MAX_BYTES + 1` se rechaza.
 *
 * ## QUIEN CIERRA LOS FLUJOS
 *
 * Nadie aqui. [write] hace `flush()` y [read] no toca el flujo: cerrarlos es del
 * llamador. Un doble cierre sobre SAF se come la excepcion real.
 */
object CarrierFileCodec {

    /** Tope del portador en bytes ISO-8859-1. */
    const val MAX_BYTES: Int = 64 * 1024

    /** El fichero supera [MAX_BYTES]. */
    const val CODE_TOO_LARGE: String = "FILE_TOO_LARGE"

    /** El fichero no tiene ni un byte: no hay portador que importar. */
    const val CODE_EMPTY: String = "EMPTY_ENVELOPE"

    /** Fallo de I/O al leer. */
    const val CODE_READ_FAILED: String = "FILE_READ_FAILED"

    private const val BUFFER_BYTES: Int = 8 * 1024

    /** Escribe [text] en [out] como sus bytes ISO-8859-1 exactos. Hace flush, no cierra. */
    fun write(out: OutputStream, text: String) {
        out.write(CarrierCodec.toBytes(text))
        out.flush()
    }

    /**
     * Lee el portador de [input] como texto ISO-8859-1.
     *
     * NO cierra [input] y NO normaliza.
     *
     * @throws CarrierFileException con [CODE_TOO_LARGE], [CODE_EMPTY] o
     *   [CODE_READ_FAILED].
     */
    fun read(input: InputStream): String {
        val bytes = readBytes(input)
        if (bytes.isEmpty()) {
            throw CarrierFileException(CODE_EMPTY, "el portador esta vacio: no hay nada que importar")
        }
        return CarrierCodec.fromBytes(bytes)
    }

    /** Los bytes del portador, con el tope aplicado durante la lectura. */
    fun readBytes(input: InputStream): ByteArray {
        val buffer = ByteArray(BUFFER_BYTES)
        val acumulado = ByteArrayOutputStream()
        var total = 0
        try {
            while (true) {
                val leidos = input.read(buffer)
                if (leidos < 0) break
                total += leidos
                if (total > MAX_BYTES) {
                    throw CarrierFileException(
                        CODE_TOO_LARGE,
                        "el portador supera $MAX_BYTES bytes: leidos $total sin llegar al final",
                    )
                }
                acumulado.write(buffer, 0, leidos)
            }
        } catch (e: CarrierFileException) {
            throw e
        } catch (e: IOException) {
            throw CarrierFileException(CODE_READ_FAILED, "fallo de I/O leyendo el portador: ${e.message}")
        }
        return acumulado.toByteArray()
    }

    /**
     * Rechazo del codec de fichero, con CODIGO ESTABLE.
     *
     * Una excepcion tipada y no `IllegalArgumentException`: el mapeo que ve la
     * pantalla colapsa los `IllegalArgumentException` a "sobre vacio", y un
     * fichero de 10 MB no esta vacio. El codigo no se deduce del mensaje.
     */
    class CarrierFileException(val code: String, message: String) : Exception(message)
}

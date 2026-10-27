package com.km.identity

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/**
 * El portador de un contacto: UN documento, UNA sola forma de escribirlo y de leerlo.
 *
 * ## QUE ES EL PORTADOR
 *
 * El `.km`/`.kmc` y el contenido de un QR son el MISMO documento: los bytes de un
 * sobre KCE comprimidos con zlib (`Deflater`) y leidos como ISO-8859-1 —un byte
 * por caracter—. Android lo produce asi desde el principio, y hasta ahora la
 * unica copia de esa regla vivia en la app: el escritorio intentaba leerlo con
 * `ContactBundle.decodeAndVerify`, que espera base64url SIN comprimir, y
 * rechazaba todo `.km` real con `ENVELOPE_MALFORMED`.
 *
 * ## POR QUE ESTA EN km-core
 *
 * Porque un portador es un CONTRATO entre clientes, no una decision de interfaz.
 * Dos implementaciones no son "dos caminos equivalentes": son dos formatos que
 * se divergen en cuanto una cambia el nivel de compresion, el charset o el orden
 * de verificacion. Aqui hay uno, y los clientes lo consumen.
 *
 * ## EL ORDEN NO ADMITE VARIANTES
 *
 * Descomprimir -> validar el sobre KCE -> verificar el bundle. Verificar antes
 * de descomprimir seria verificar otro documento; validar despues de verificar
 * no valida nada, porque [ContactBundle.verified] ya presupone un sobre KCE.
 *
 * ## LOS DOS LIMITES
 *
 * 1. Entrada: [MAX_CARRIER_BYTES] (16 KiB). Se comprueba ANTES de descomprimir:
 *    lo caro es inflar, y un portador legitimo mide cientos de bytes.
 * 2. Salida: [MAX_INFLATED_BYTES] (1 MiB). Es la defensa contra la bomba de
 *    descompresion: un portador de un kilo puede inflarse a un gigabyte.
 *
 * Los dos rechazos llevan codigo PROPIO y no `ENVELOPE_MALFORMED`: "demasiado
 * grande" y "no es un portador" son cosas distintas, y confundirlas haria que
 * un rechazo por tamano pareciera corrupcion.
 *
 * ## SIN `trim()`
 *
 * El texto se mide y se descomprime tal cual. El `\n` que anade un editor queda
 * FUERA del stream zlib, y [decompress] solo tolera bytes colgantes que sean
 * whitespace. Normalizar antes cambaria el documento y, en un portador cuyo
 * ultimo caracter ya fuera whitespace, lo mutaria.
 */
object CarrierCodec {

    /** El portador (comprimido) no pasa de 16 KiB. */
    const val MAX_CARRIER_BYTES: Int = 16 * 1024

    /** La salida descomprimida no pasa de 1 MiB. */
    const val MAX_INFLATED_BYTES: Int = 1024 * 1024

    /** El portador supera [MAX_CARRIER_BYTES]. */
    const val CODE_CARRIER_TOO_LARGE: String = "BUNDLE_CARRIER_TOO_LARGE"

    /** El portador descomprime a mas de [MAX_INFLATED_BYTES]. */
    const val CODE_INFLATED_TOO_LARGE: String = "BUNDLE_INFLATED_TOO_LARGE"

    /** Los bytes no son un flujo zlib valido, o hay basura no-whitespace detras. */
    const val CODE_MALFORMED: String = "ENVELOPE_MALFORMED"

    /**
     * Comprime [kceBytes] y devuelve el TEXTO del portador.
     *
     * El nivel es [Deflater.BEST_COMPRESSION] porque es el que usa Android: dos
     * niveles distintos producen bytes distintos para el mismo documento, y
     * aunque los dos descompriman, dejan de ser el MISMO portador. Los vectores
     * de los golden se comparan byte a byte, y esa comparacion es la que
     * sostiene la interoperabilidad.
     */
    fun encode(kceBytes: ByteArray): String {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            val baos = ByteArrayOutputStream()
            DeflaterOutputStream(baos, deflater).use { it.write(kceBytes) }
            return String(baos.toByteArray(), Charsets.ISO_8859_1)
        } finally {
            deflater.end()
        }
    }

    /**
     * Los bytes REALES que ocupa [text] como portador: uno por caracter.
     *
     * No es el tamano en UTF-8: los bytes deflados pasan de `0x7F` y en UTF-8
     * valen dos. Medir con la regla equivocada infla la cifra entre un 30% y un
     * 50%, y con ella se decide mal si un QR cabe en pantalla.
     */
    fun bytes(text: String): Int = text.toByteArray(Charsets.ISO_8859_1).size

    /** Los bytes del portador, sin normalizar nada. */
    fun toBytes(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    /** El texto de un portador a partir de los bytes que viajaron. */
    fun fromBytes(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)

    /**
     * Lee el portador [text] y devuelve el bundle VERIFICADO.
     *
     * Es el UNICO punto del framework que hace `decompress -> Kce.validate ->
     * ContactBundle.verified`; los clientes no repiten el orden.
     *
     * @param expectedIdentityRoot raiz esperada, o `null` para aceptar la que
     *   firme el bundle. Un `null` NO relaja la verificacion: la firma se
     *   comprueba igual; solo no se exige que sea una raiz concreta.
     * @throws Kce.KceException con [CODE_CARRIER_TOO_LARGE],
     *   [CODE_INFLATED_TOO_LARGE] o [CODE_MALFORMED].
     * @throws KmIdVerificationException si el bundle no verifica o la raiz no es
     *   la esperada.
     * @throws KmIdSchemaException si el documento no cumple el esquema.
     */
    fun decode(text: String, expectedIdentityRoot: ByteArray? = null): VerifiedContactBundle {
        val inflated = decompress(text)
        val bundleMap = Kce.validate(inflated) as Map<String, Any?>
        return ContactBundle.verified(bundleMap, expectedIdentityRoot)
    }

    /**
     * Descomprime el portador y devuelve los bytes KCE, sin verificar.
     *
     * Es `public` porque el diagnostico y los tests necesitan ver los bytes
     * inflados; lo que un CLIENTE debe usar es [decode], que ademas verifica.
     */
    fun decompress(text: String): ByteArray {
        val deflated = toBytes(text)
        if (deflated.size > MAX_CARRIER_BYTES) {
            throw Kce.KceException(
                CODE_CARRIER_TOO_LARGE,
                "el portador ocupa ${deflated.size} B y el limite es $MAX_CARRIER_BYTES B",
            )
        }
        return try {
            inflar(deflated)
        } catch (e: Kce.KceException) {
            throw e
        } catch (e: Exception) {
            throw Kce.KceException(CODE_MALFORMED, "no es un bundle comprimido valido: ${e.message}")
        }
    }

    private fun inflar(deflated: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(deflated)
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n > 0) {
                    if (baos.size() + n > MAX_INFLATED_BYTES) {
                        throw Kce.KceException(
                            CODE_INFLATED_TOO_LARGE,
                            "el portador descomprime a mas de $MAX_INFLATED_BYTES B",
                        )
                    }
                    baos.write(buf, 0, n)
                } else if (!inflater.finished()) {
                    // `inflate` devolvio 0 y el stream no termino. Si no hay mas
                    // entrada es truncado; si la hay y no la consume, el
                    // inflater esta ATASCADO (necesita diccionario o los bytes
                    // no son zlib) y seguir preguntando seria un bucle infinito
                    // con un portador de un byte.
                    if (inflater.needsInput()) break
                    throw IllegalStateException("inflater atascado: no consume la entrada")
                }
            }
            if (!inflater.finished()) {
                throw IllegalStateException("stream zlib truncado")
            }
            // Los bytes que vienen DESPUES del fin del stream no son payload.
            // Se toleran solo si son whitespace (un salto de linea de pegado);
            // cualquier otra cosa es corrupcion y no se adivina.
            val consumed = inflater.totalIn.toInt()
            for (i in consumed until deflated.size) {
                val ch = (deflated[i].toInt() and 0xFF).toChar()
                if (!ch.isWhitespace()) {
                    throw IllegalStateException("bytes no-whitespace tras el fin del stream zlib")
                }
            }
            return baos.toByteArray()
        } finally {
            inflater.end()
        }
    }
}

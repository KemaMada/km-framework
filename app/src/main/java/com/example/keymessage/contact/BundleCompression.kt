package com.example.keymessage.contact

import com.km.identity.CarrierCodec

/**
 * El portador de la app: DELEGACION al codec compartido de km-core.
 *
 * ## POR QUE YA NO HAY DEFLATE AQUI
 *
 * Porque la regla del portador —deflate + ISO-8859-1, topes de 16 KiB/1 MiB y
 * orden decompress -> validate -> verify— es un CONTRATO entre clientes, no una
 * decision de la app. Estaba solo aqui, y el escritorio intentaba leer otro
 * formato. Ahora vive en [CarrierCodec] y los dos clientes la consumen.
 *
 * Los codigos y constantes se conservan con el MISMO valor para no cambiar el
 * vocabulario de la pantalla ni de los tests.
 */
object BundleCompression {

    /** El portador (comprimido) no pasa de 16 KiB. */
    const val MAX_CARRIER_BYTES: Int = CarrierCodec.MAX_CARRIER_BYTES

    /** La salida descomprimida no pasa de 1 MiB. */
    const val MAX_INFLATED_BYTES: Int = CarrierCodec.MAX_INFLATED_BYTES

    /** El portador es mas grande que el limite. */
    const val CODE_CARRIER_TOO_LARGE: String = CarrierCodec.CODE_CARRIER_TOO_LARGE

    /** El portador descomprime a mas de 1 MiB. */
    const val CODE_INFLATED_TOO_LARGE: String = CarrierCodec.CODE_INFLATED_TOO_LARGE

    /** Comprime el sobre KCE y lo devuelve como texto ISO-8859-1. */
    fun compress(kceBytes: ByteArray): String = CarrierCodec.encode(kceBytes)

    /**
     * Los bytes REALES del portador: uno por caracter ISO-8859-1.
     *
     * No es el tamano en UTF-8: ese infla la cifra entre un 30% y un 50% y mide
     * otro documento.
     */
    fun carrierBytes(text: String): Int = CarrierCodec.bytes(text)

    /** Descomprime el portador con los topes compartidos. */
    fun decompress(text: String): ByteArray = CarrierCodec.decompress(text)
}

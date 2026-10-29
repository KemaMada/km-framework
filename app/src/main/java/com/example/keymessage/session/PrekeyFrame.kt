package com.example.keymessage.session

/**
 * Lectura MINIMA del wire de `SecureFrame` para saber si es un frame PREKEY.
 *
 * ## DONDE VIVE AHORA
 *
 * En `com.km.frame.PrekeyFrame`. El escritorio necesitaba exactamente la misma
 * clasificacion (bytes opacos del canal de par -> "esto es un PREKEY"), y un
 * segundo lector de cabecera es un segundo parser del wire. Aqui queda la API que
 * ya usan [com.example.keymessage.AppContainer] y sus tests.
 *
 * ## POR QUE NO SE DECODIFICA EL FRAME ENTERO
 *
 * Porque un PREKEY completo lo decodifica y AUTENTICA `SecureSessionEstablishment`
 * de km-core. Aqui solo se responde "¿de que tipo es?", con el byte de version y
 * el de tipo de una cabecera PublicE congelada. Un byte que no empieza por la
 * version de SecureFrame v1 NO es un SecureFrame y devuelve `false`.
 */
object PrekeyFrame {

    /** Si [wire] es un `SecureFrame` v1 de tipo PREKEY. */
    fun esPrekey(wire: ByteArray): Boolean = com.km.frame.PrekeyFrame.esPrekey(wire)
}

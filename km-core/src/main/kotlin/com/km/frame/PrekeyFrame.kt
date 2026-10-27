package com.km.frame

/**
 * Lectura MINIMA del wire de `SecureFrame` para saber si es un frame PREKEY.
 *
 * ## POR QUE EXISTE
 *
 * El canal de par entrega bytes OPACOS: el motor mueve la carga intacta y quien
 * entiende los bytes es la capa de sesion. Pero un frame PREKEY es especial:
 * llega ANTES de que exista ninguna sesion con ese par, asi que `SecureInbound`
 * no tiene a quien preguntar y lo cuenta como `unknownPeerFrames` (correcto: no
 * hay sesion). Alguien tiene que RECONOCERLO y llevarlo al camino de aceptacion.
 *
 * ## POR QUE NO SE DECODIFICA EL FRAME ENTERO
 *
 * Porque decodificar un PREKEY completo es trabajo de km-core
 * (`SecureSessionEstablishment.accept`), que ademas lo AUTENTICA. Aqui solo se
 * responde "¿de que tipo es?", que es un dato de cabecera PublicE y congelado
 * (byte 0: version; byte 1: tipo). Mirar mas seria duplicar un parser.
 *
 * Un byte que no empieza por la version de SecureFrame v1 NO es un SecureFrame y
 * devuelve `false`: aqui no se adivina.
 */
object PrekeyFrame {

    /**
     * Si [wire] es un `SecureFrame` v1 de tipo PREKEY.
     *
     * Comprueba solo lo que puede comprobar sin arriesgar: longitud suficiente
     * para la cabecera, la version y el tipo.
     */
    fun esPrekey(wire: ByteArray): Boolean {
        if (wire.size < MIN_HEADER_BYTES) return false
        if (wire[SecureFrameSpec.VERSION_OFFSET].toUByte() != SecureFrameSpec.VERSION) return false
        val tipo = wire[SecureFrameSpec.TYPE_OFFSET].toUByte()
        return FrameType.fromCode(tipo) == FrameType.PREKEY
    }

    /** Cuantos bytes hay que poder leer como minimo para clasificar. */
    private const val MIN_HEADER_BYTES: Int = SecureFrameSpec.TYPE_OFFSET + 1
}

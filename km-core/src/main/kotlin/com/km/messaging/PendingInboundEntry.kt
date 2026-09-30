package com.km.messaging

import com.km.frame.SecureFrameSpec

/**
 * 3Q.5.3 — Un frame ENTRANTE que todavia no se ha convertido en plaintext.
 *
 * ## QUE ES
 *
 * Lo que la bandeja de [SecureMessagingSession] guarda cuando el transporte
 * entrega bytes y todavia no han podido usarse. Son los bytes DEL CABLE —el
 * SecureFrame entero, sin descifrar— porque lo que se guarda es lo que LLEGO,
 * no lo que se obtuvo con el: rehacer un frame no es recuperar un frame.
 *
 * ## POR QUE LLEVA LA IDENTIDAD Y ADEMAS LOS BYTES
 *
 * La identidad se puede leer del header ([FrameIdentity.fromWire]) y aun asi
 * se guarda. No es redundancia inutil: es la clave de la DEDUPLICACION, y la
 * bandeja ([PendingInbox]) tiene que poder responder "de este frame ya tengo
 * una copia?" sin descifrar nada. Ademas permite que el codec CONTRASTE las
 * dos copias —que la identidad guardada sea la que lleva el header de verdad— y
 * convierta una unidad manipulada en un rechazo tipado en vez de una bandeja
 * silenciosamente incorrecta.
 *
 * ## NO ES ESTADO DEL RATCHET
 *
 * Un frame pendiente no es material criptografico: es un mensaje que todavia
 * no ha pasado por el ratchet. Por eso
 * [com.km.ratchet.DoubleRatchetSnapshot] no lo lleva: meterlo
 * obligaria al ratchet a saber de mensajeria, que es la frontera de 3Q.5.2a.
 */
class PendingInboundEntry(
    /** La identidad del frame. */
    val frameIdentity: FrameIdentity,
    /** El SecureFrame COMPLETO, tal y como llego del transporte. */
    wireFrame: ByteArray,
) {
    /** El frame, intacto. Copia defensiva. */
    val wireFrame: ByteArray = wireFrame.copyOf()

    init {
        require(this.wireFrame.size >= SecureFrameSpec.CIPHERTEXT_OFFSET) {
            "un frame de ${this.wireFrame.size}B no contiene header completo"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingInboundEntry) return false
        return frameIdentity == other.frameIdentity && wireFrame.contentEquals(other.wireFrame)
    }

    override fun hashCode(): Int = 31 * frameIdentity.hashCode() + wireFrame.contentHashCode()

    override fun toString(): String = "PendingInboundEntry($frameIdentity, ${wireFrame.size}B)"
}
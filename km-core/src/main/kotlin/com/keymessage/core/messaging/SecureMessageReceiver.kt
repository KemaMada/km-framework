package com.keymessage.core.messaging

import com.keymessage.core.model.MessageId
import com.keymessage.core.sf.SecureRatchetProtocol

/**
 * Recepcion de mensajes con semantica de entrega (3Q.5.1b).
 *
 * ## El problema que resuelve
 *
 * Un frame repetido (por ejemplo, reenviado por otro medio de transporte tras
 * una reconexion) es rechazado por el ratchet como `REPLAY_OR_UNKNOWN`. Pero
 * con `REPLAY_OR_UNKNOWN` el receptor no sabe QUE mensaje era, y por tanto no
 * puede reenviar el ACK que exige KM-0004 §9.4.
 *
 * ## La solucion, y por que no toca el ratchet
 *
 * La identidad del frame `(DH, PN, N)` es el AAD: esta en el header, en claro
 * y autenticado. Se puede leer SIN descifrar y SIN el ratchet. Con ahi una
 * tabla `identidad -> messageId`, el orden correcto es:
 *
 * ```
 * 1. leer (DH, PN, N) del header
 * 2. ¿esta en el registro?
 *      si -> DUPLICADO: se devuelve el messageId registrado.
 *            El ratchet NO se toca, ni se avanza, ni se muta.
 *            El llamante reenvia el ACK con ese messageId (§9.4).
 *      no -> 3. ratchet.decrypt()
 *               Ok       -> 4. decodificar el sobre
 *                              registro(identidad -> messageId)
 *                              -> NUEVO
 *               fallo    -> -> RECHAZADO, y NO se registra nada
 * ```
 *
 * ## La frontera
 *
 * Esta capa NO es una segunda ruta criptografica. El ratchet sigue siendo la
 * unica puerta al plaintext: un frame desconocido SIEMPRE pasa por el. Y el
 * ratchet sigue sin saber que existe un `messageId`, un ACK o una entrega.
 *
 * Perder el registro degrada al comportamiento anterior: el duplicado llega al
 * ratchet y lo rechaza. Nunca abre una ruta de texto plano.
 */
class SecureMessageReceiver(
    /** Ratchet de la sesion. La capa no lo modifica ni lo extiende. */
    private val ratchet: SecureRatchetProtocol,
    /** Registro por sesion. Se destruye con ella. */
    private val records: DeliveryRecordTable = DeliveryRecordTable(),
) {

    /** Resultado de ofrecer un frame al receptor. */
    sealed class Result {
        /** Frame nuevo: sobre decodificado, ya registrado. */
        data class Fresh(val envelope: MessageEnvelope) : Result()

        /**
         * Frame ya procesado: se conserva su `messageId` para reenviar el ACK.
         *
         * El ratchet NO se toco. Este es el caso que KM-0004 §9.4 exige tratar
         * como duplicado, y antes era imposible distinguirlo de un replay.
         */
        data class Duplicate(val messageId: MessageId) : Result()

        /** El frame no es utilizable. Nada se registro. */
        data class Rejected(val reason: String) : Result()
    }

    /**
     * Procesa un frame entrante.
     *
     * @param wire bytes de `SecureFrame` tal cual llegaron del transporte.
     */
    fun receive(wire: ByteArray): Result {
        // 1. Identidad del frame, desde el header y solo el header.
        val identity = try {
            FrameIdentity.fromWire(wire)
        } catch (e: IllegalArgumentException) {
            // Ni siquiera hay header legible. Se rechaza sin tocar el ratchet.
            return Result.Rejected("header ilegible: ${e.message}")
        }

        // 2. Duplicado conocido: NO se toca el ratchet.
        records.lookup(identity)?.let { known ->
            return Result.Duplicate(known)
        }

        // 3. Frame desconocido: el ratchet es la unica puerta al plaintext.
        val decrypted = when (val r = ratchet.decrypt(wire)) {
            is SecureRatchetProtocol.DecryptResult.Ok -> r
            is SecureRatchetProtocol.DecryptResult.Unauthenticated ->
                return Result.Rejected("AEAD no verifico: ${r.reason}")
            is SecureRatchetProtocol.DecryptResult.Rejected ->
                return Result.Rejected("ratchet rechazo: ${r.reason}")
        }

        // 4. Sobre. Si no es valido, N ya se consumio pero NO se registra nada:
        //    una entrada sin messageId valido seria un duplicado que no se
        //    puede acusar, es decir, peor que no tenerla.
        val envelope = try {
            MessageEnvelopeCodec.decode(decrypted.plaintext)
        } catch (e: EnvelopeFormatException) {
            return Result.Rejected("sobre invalido: ${e.message}")
        }

        // 5. Solo ahora, con un messageId obtained de plaintext AUTENTICADO.
        records.record(identity, envelope.messageId)
        return Result.Fresh(envelope)
    }

    /** `messageId` conocido para una identidad de frame, si la hay. */
    fun knownMessageId(wire: ByteArray): MessageId? =
        try {
            records.lookup(FrameIdentity.fromWire(wire))
        } catch (e: IllegalArgumentException) {
            null
        }

    /** Numero de entradas viva. Solo para diagnostico. */
    fun recordCount(): Int = records.size()
}

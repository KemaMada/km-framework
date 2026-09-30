package com.km.frame

import com.km.crypto.AeadAuthenticationException
import com.km.ratchet.DoubleRatchetSession
import com.km.ratchet.RejectReason
import com.km.ratchet.SymmetricRatchetSpec

/**
 * KM-0004 — Double Ratchet completo sobre SecureFrame (3O.4).
 *
 * ESTA ES LA API PUBLICA DEL PROTOCOLO.
 *
 * <pre>
 *   cifrado :  plaintext
 *                │
 *                ▼
 *          DoubleRatchet ──► messageKey
 *                │
 *                ▼
 *          SecureFrameProtector  (deriva clave+nonce, construye AAD, AEAD)
 *                │
 *                ▼
 *          SecureFrameCodec  ──► bytes de wire
 *
 *   descifrado:  bytes de wire
 *                │
 *                ▼
 *          SecureFrameCodec  ──► SecureFrame
 *                │
 *                ▼
 *          DoubleRatchet (previewReceive) ──► messageKey
 *                │
 *                ▼
 *          SecureFrameProtector (AEAD verify) ──► plaintext
 * </pre>
 *
 * INVARIANTES:
 * 1. No existe camino que cifre o descifre SIN pasar por SecureFrame.
 * 2. Un descifrado fallido NO muta el estado de la sesion: se descarta.
 * 3. El consumidor nunca manipula RK, DHs, DHr, CKs, CKr, Ns, Nr ni PN
 *    directamente; solo invoca estas operaciones.
 */
class SecureRatchetProtocol(
    private val session: DoubleRatchetSession,
    private val protector: SecureFrameProtector,
    private val codec: SecureFrameCodec = BinarySecureFrameCodec,
) {
    /**
     * Cifra un mensaje y devuelve los bytes de wire.
     *
     * Recorre obligatoriamente: ratchet -> SecureFrame -> AEAD -> codec.
     *
     * @param plaintext contenido a cifrar.
     * @param type tipo de frame (tambien entra en el AAD).
     */
    fun encrypt(plaintext: ByteArray, type: FrameType = FrameType.MESSAGE): ByteArray {
        val send = session.previewSend()
        val frame = protector.protect(
            type = type,
            ratchetHeader = RatchetHeader(
                dhPublicKey = send.dhPublicKey,
                previousChainLength = send.previousChainLength,
                messageNumber = send.messageNumber,
            ),
            messageKey = send.messageKey,
            plaintext = plaintext,
        )
        val wire = codec.encode(frame)
        session.commitSend()
        return wire
    }

    /** Resultado de intentar descifrar unos bytes de wire. */
    sealed class DecryptResult {
        data class Ok(val plaintext: ByteArray, val fromSkipped: Boolean) : DecryptResult()
        data class Rejected(val reason: RejectReason) : DecryptResult()

        /** El frame era valido pero no autenticado. */
        data class Unauthenticated(val reason: String) : DecryptResult()
    }

    /**
     * Descifra unos bytes de wire.
     *
     * Ante cualquier fallo (formato, ratchet o AEAD) devuelve [DecryptResult.Rejected]
     * o [DecryptResult.Unauthenticated] y NO muta el estado de la sesion.
     */
    fun decrypt(wire: ByteArray): DecryptResult {
        // 1. Decodificar el frame. Un formato invalido no toca el ratchet.
        val frame = try {
            codec.decode(wire)
        } catch (e: SecureFrameFormatException) {
            return DecryptResult.Rejected(RejectReason.REPLAY_OR_UNKNOWN)
        }

        // 2. Obtener la clave del ratchet. Rechazos no tocan el estado.
        val outcome = session.previewReceive(
            headerDhPublicKey = frame.ratchetHeader.dhPublicKey,
            previousChainLength = frame.ratchetHeader.previousChainLength,
            messageNumber = frame.ratchetHeader.messageNumber,
        )
        if (outcome is DoubleRatchetSession.ReceiveResult.Rejected) {
            return DecryptResult.Rejected(outcome.reason)
        }
        val ready = outcome as DoubleRatchetSession.ReceiveResult.Ready

        // 3. Verificar el AEAD. Si falla, se DESCARTA todo el estado candidato.
        val plaintext = try {
            protector.unprotect(frame, ready.messageKey)
        } catch (e: AeadAuthenticationException) {
            session.discardReceive()
            return DecryptResult.Unauthenticated("AEAD no verifico: ${e.message}")
        }

        // 4. Solo tras verificar, se confirma el estado.
        session.commitReceive()
        return DecryptResult.Ok(plaintext, ready.fromSkipped)
    }

    /** Huella del estado de la sesion, para verificar que un rechazo no muto nada. */
    fun stateFingerprint(): ByteArray = session.stateFingerprint()

    /**
     * Inicia una nueva epoca de envio (ratchet DH del lado emisor).
     *
     * `internal`, y no parte de la API publica del protocolo, por lo que dice
     * [DoubleRatchetSession.initiateEpoch]: solo es correcta en el
     * establecimiento. Publicarla aqui era la trampa —una llamada a esta clase
     * es, para quien no conoce el ratchet, "abrir una epoch" y nada mas— y
     * `RatchetSessionBootstrap` no la usa nunca. La Costura de estado
     * (`snapshot`/`restore`) es la via que el protocolo tiene para sobrevivir
     * a un corte, y no necesita rotar por su cuenta.
     */
    internal fun initiateEpoch() = session.initiateEpoch()

    /** Numero de mensajes enviados. */
    fun sentCount(): UInt = session.currentSendMessageNumber()

    /** Longitud de la cadena de envio anterior (PN). */
    fun previousChainLength(): UInt = session.currentPreviousChainLength()
}

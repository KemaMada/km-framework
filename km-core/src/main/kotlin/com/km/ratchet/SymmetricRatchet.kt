package com.km.ratchet

import com.km.crypto.Kdf

/**
 * Ratchet simetrico KM-0004 v1: `ChainKey` → `MessageKey` + siguiente `ChainKey`.
 *
 * NO incluye el DH ratchet (3O.3) ni la raiz (3O.3). Solo el ratchet
 * simetrico, que es la base sobre la que el DH ratchet se apoyara.
 *
 * INVARIANTES:
 * 1. La `ChainKey` NUNCA se expone fuera de esta clase.
 * 2. La `MessageKey` NUNCA se usa para derivar la siguiente `ChainKey`.
 * 3. `KDF_CK` usa dominios distintos para MESSAGE y CHAIN.
 * 4. Cada envio avanza `N` exactamente una vez.
 * 5. Ninguna operacion consume estado antes de que el mensaje este listo
 *    (preview/commit).
 * 6. Las skipped keys son de UN SOLO USO y se identifican por
 *    `(chainId, messageNumber)`.
 * 7. Un salto mayor que [SymmetricRatchetSpec.MAX_SKIP] se rechaza.
 */
class SymmetricRatchet(
    initialChainKey: ByteArray,
    private val kdf: Kdf,
) {
    // Estado de envio.
    private var sendChainKey: ByteArray = initialChainKey.copyOf()
    private var sendMessageNumber: UInt = 0u

    // Estado de recepcion.
    private var receiveChainKey: ByteArray = initialChainKey.copyOf()
    private var receiveMessageNumber: UInt = 0u

    /**
     * Claves de mensaje retenidas por salto `(chainId, N)`.
     *
     * Una clave de este almacen es de un solo uso: se retira al servirla.
     * Nunca hay dos entradas para la misma clave primaria.
     */
    private val skipped = LinkedHashMap<Pair<ChainIdentifier, UInt>, ByteArray>()

    // ------------------------------------------------------------------
    // KDF_CK
    // ------------------------------------------------------------------

    /**
     * KDF_CK: un paso del ratchet simetrico.
     *
     * Dos salidas del MISMO secreto con dominios separados. La `MessageKey`
     * se usa una sola vez y no alimenta la siguiente `ChainKey`.
     */
    private fun kdfCk(chainKey: ByteArray): Pair<ByteArray, ByteArray> {
        val salt = ByteArray(32)
        val messageKey = kdf.hkdf(
            salt = salt,
            ikm = chainKey,
            info = SymmetricRatchetSpec.INFO_MESSAGE.toByteArray(),
            length = SymmetricRatchetSpec.MESSAGE_KEY_LENGTH,
        )
        val nextChainKey = kdf.hkdf(
            salt = salt,
            ikm = chainKey,
            info = SymmetricRatchetSpec.INFO_CHAIN.toByteArray(),
            length = SymmetricRatchetSpec.CHAIN_KEY_LENGTH,
        )
        return messageKey to nextChainKey
    }

    // ==================================================================
    // ENVIO — transaccional
    // ==================================================================

    /**
     * Prepara el siguiente envio SIN consumir estado.
     *
     * El llamador cifra con [RatchetStep.messageKey] y el `messageNumber`
     * obtido; si el AEAD falla, no debe llamar a [commitSend].
     */
    fun previewSend(): RatchetStep {
        val (messageKey, _) = kdfCk(sendChainKey)
        return RatchetStep(messageKey, sendMessageNumber)
    }

    /**
     * Confirma el envio preparado por [previewSend].
     *
     * @return `true` si avanzo, `false` si no habia nada pendiente o ya se
     *   habia confirmado (evita confirmaciones dobles).
     */
    fun commitSend(): Boolean {
        val (messageKey, nextChainKey) = kdfCk(sendChainKey)
        sendChainKey = nextChainKey
        sendMessageNumber++
        lastSendKey = messageKey
        return true
    }

    /** Ultima message key confirmada en envio (util para diagnostico). */
    private var lastSendKey: ByteArray? = null

    /** Numero de mensajes enviados hasta ahora. */
    val sentMessageCount: UInt get() = sendMessageNumber

    // ==================================================================
    // RECEPCION — transaccional
    // ==================================================================

    /**
     * Prepara la recepcion de un mensaje SIN consumir estado.
     *
     * @param chainId identificador opaco de la cadena. En 3O.3 sera la
     *   clave publica DH. Se usa junto a `N` para localizar skipped keys.
     * @param messageNumber indice del mensaje dentro de la cadena.
     */
    fun previewReceive(chainId: ByteArray, messageNumber: UInt): ReceiveOutcome {
        val key = ChainIdentifier(chainId) to messageNumber

        // 1. Servir desde skipped keys (fuera de orden). Son de un solo uso:
        //    se retiran en el preview, y si el llamador no confirma, el
        //    estado ya no podra volver a usarlas.
        skipped.remove(key)?.let { messageKey ->
            return ReceiveOutcome.FromSkipped(RatchetStep(messageKey, messageNumber))
        }

        // 2. N anterior al actual sin clave saltada: replay o invalido.
        if (messageNumber < receiveMessageNumber) {
            return ReceiveOutcome.Rejected(RejectReason.REPLAY_OR_UNKNOWN)
        }

        // 3. Salto mayor que MAX_SKIP: coste de CPU denial-of-service.
        val gap = messageNumber - receiveMessageNumber
        if (gap > SymmetricRatchetSpec.MAX_SKIP.toUInt()) {
            return ReceiveOutcome.Rejected(RejectReason.SKIP_LIMIT_EXCEEDED)
        }

        // 4. Avanzar la cadena: las intermedias se retienen como skipped.
        var chainKey = receiveChainKey
        var n = receiveMessageNumber
        val derivedSkipped = LinkedHashMap<Pair<ChainIdentifier, UInt>, ByteArray>()
        var messageKey: ByteArray
        while (true) {
            val (mk, nextCk) = kdfCk(chainKey)
            if (n == messageNumber) {
                messageKey = mk
                chainKey = nextCk
                n++
                break
            }
            derivedSkipped[ChainIdentifier(chainId) to n] = mk
            chainKey = nextCk
            n++
        }

        // Preparado: no se consume estado hasta commitReceive.
        pendingReceive = PendingReceive(
            nextChainKey = chainKey,
            nextMessageNumber = n,
            skippedToAdd = derivedSkipped,
        )
        return ReceiveOutcome.InOrder(RatchetStep(messageKey, messageNumber))
    }

    /** Confirma la recepcion preparada por [previewReceive]. */
    fun commitReceive(): Boolean {
        val pending = pendingReceive ?: return false
        receiveChainKey = pending.nextChainKey
        receiveMessageNumber = pending.nextMessageNumber
        // Sin claves duplicadas: la clave primaria (chainId, N) es unica.
        skipped.putAll(pending.skippedToAdd)
        pendingReceive = null
        return true
    }

    /**
     * Descarta la recepcion preparada sin avanzar la cadena.
     *
     * Los pasos de derivacion ya realizados no se conservan.
     */
    fun abortReceive() {
        pendingReceive = null
    }

    private class PendingReceive(
        val nextChainKey: ByteArray,
        val nextMessageNumber: UInt,
        val skippedToAdd: Map<Pair<ChainIdentifier, UInt>, ByteArray>,
    )

    private var pendingReceive: PendingReceive? = null

    // ==================================================================
    // OBSERVABILIDAD
    // ==================================================================

    /** Numero de mensajes recibidos hasta ahora. */
    val receivedMessageCount: UInt get() = receiveMessageNumber

    /** Claves de mensaje actualmente retenidas por salto. */
    fun skippedKeyCount(): Int = skipped.size

    /** Indice del proximo envio. */
    val nextSendMessageNumber: UInt get() = sendMessageNumber

    /** Indice del proximo mensaje esperado en recepcion. */
    val nextReceiveMessageNumber: UInt get() = receiveMessageNumber

    /**
     * Copia profunda del estado del ratchet.
     *
     * El DH ratchet construye un estado CANDIDATO y solo lo adopta si el
     * AEAD verifica. Para eso necesita clonar el ratchet de forma
     * independiente: si compartiera el estado real, un fallo de
     * autenticacion habria consumido claves que el mensaje siguiente
     * necesita para descifrar.
     */
    fun snapshot(): SymmetricRatchetSnapshot = SymmetricRatchetSnapshot(
        sendChainKey = sendChainKey.copyOf(),
        sendMessageNumber = sendMessageNumber,
        receiveChainKey = receiveChainKey.copyOf(),
        receiveMessageNumber = receiveMessageNumber,
        skipped = skipped.mapValues { it.value.copyOf() },
    )

    /** Restaura el estado desde una instantanea. */
    fun restore(s: SymmetricRatchetSnapshot) {
        sendChainKey = s.sendChainKey.copyOf()
        sendMessageNumber = s.sendMessageNumber
        receiveChainKey = s.receiveChainKey.copyOf()
        receiveMessageNumber = s.receiveMessageNumber
        skipped.clear()
        skipped.putAll(s.skipped.mapValues { it.value.copyOf() })
    }

    /** Crea un ratchet INDEPENDIENTE con el mismo estado. */
    fun copyRatchet(): SymmetricRatchet {
        val copy = SymmetricRatchet(ByteArray(0), kdf)
        copy.restore(snapshot())
        return copy
    }
}

// ======================================================================
// Instantanea — necesaria para la atomicidad del DH ratchet (3O.3)
// ======================================================================

/**
 * Estado instantaneo de un [SymmetricRatchet], con copia profunda.
 */
class SymmetricRatchetSnapshot internal constructor(
    internal val sendChainKey: ByteArray,
    internal val sendMessageNumber: UInt,
    internal val receiveChainKey: ByteArray,
    internal val receiveMessageNumber: UInt,
    internal val skipped: Map<Pair<ChainIdentifier, UInt>, ByteArray>,
)

/**
 * Huella criptografica del estado interno de un [SymmetricRatchet].
 *
 * Permite afirmar en tests que un frame rechazado NO muto el estado:
 * la huella antes del rechazo debe ser identica a la de despues.
 * Sin esto, "no hubo mutacion" quedaria como una afirmacion no verificada.
 */
fun SymmetricRatchet.stateFingerprint(): ByteArray {
    val s = snapshot()
    val material = java.io.ByteArrayOutputStream()
    fun put(b: ByteArray) {
        material.write(b.size)
        material.write(b)
    }
    put(s.sendChainKey)
    put(s.sendMessageNumber.toString().toByteArray())
    put(s.receiveChainKey)
    put(s.receiveMessageNumber.toString().toByteArray())
    // Las claves saltadas se ordenan para que la huella sea estable.
    s.skipped.entries.sortedWith(compareBy({ it.key.second }, { it.key.first.toString() })).forEach { (k, v) ->
        put(k.first.bytes)
        put(k.second.toString().toByteArray())
        put(v)
    }
    return java.security.MessageDigest.getInstance("SHA-256").digest(material.toByteArray())
}

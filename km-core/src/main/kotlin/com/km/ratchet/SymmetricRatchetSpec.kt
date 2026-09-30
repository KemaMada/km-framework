package com.km.ratchet

/**
 * KM-0004 — Simetrico Ratchet v1 (3O.2).
 *
 * ALCANCE DEL INCREMENTO:
 * Esta capa sabe SOLO de `ChainKey`, `MessageKey`, `N` y skipped keys.
 * NO conoce `RootKey`, X25519 ni el DH ratchet: eso es 3O.3.
 *
 * SEPARACION DE DOMINIO:
 * `KDF_CK` produce dos salidas del mismo secreto con `info` distintas:
 * <pre>
 *   messageKey   = HKDF(salt=0^32, ikm=chainKey,
 *                       info="KM-0004/SYM-RATCHET/V1/MESSAGE", len=32)
 *   nextChainKey = HKDF(salt=0^32, ikm=chainKey,
 *                       info="KM-0004/SYM-RATCHET/V1/CHAIN",   len=32)
 * </pre>
 * La `MessageKey` NUNCA se usa para obtener la siguiente `ChainKey`.
 *
 * TRANSACCIONALIDAD:
 * Ninguna operacion consume estado antes de que el mensaje este listo.
 * `previewSend`/`commitSend` y `previewReceive`/`commitReceive` permiten
 * preparar las claves, intentar el AEAD y solo entonces avanzar el estado.
 * Un fallo de procesamiento NO consume estado.
 *
 * IDENTIFICACION DE CADENAS:
 * Una cadena se identifica por un `chainId` opaco, NO solo por `N`. Cuando
 * exista un DH ratchet una cadena nueva tendra tambien `N=0`, asi que `N`
 * por si solo no identifica globalmente una clave. En 3O.3 el `chainId`
 * sera la clave publica DH del ratchet.
 */
object SymmetricRatchetSpec {
    /** Etiquetas de dominio HKDF. Un output nunca tiene dos significados. */
    const val INFO_MESSAGE: String = "KM-0004/SYM-RATCHET/V1/MESSAGE"
    const val INFO_CHAIN: String = "KM-0004/SYM-RATCHET/V1/CHAIN"

    /** Longitud de ChainKey y MessageKey. */
    const val CHAIN_KEY_LENGTH: Int = 32
    const val MESSAGE_KEY_LENGTH: Int = 32

    /**
     * Limite de mensajes saltados (MAX_SKIP).
     *
     * Constante de PROTOCOLO, no parametro del llamador: un remitente
     * malicioso podria obligar al receptor a ejecutar millones de KDFs
     * enviando un `N` enorme.
     */
    const val MAX_SKIP: Int = 1000
}

/** Resultado de intentar obtener una clave de recepcion. */
sealed class ReceiveOutcome {
    /**
     * Mensaje en orden: la clave se derivo avanzando la cadena de recepcion.
     * El receptor debe descifrar y luego llamar a [SymmetricRatchet.commitReceive].
     */
    data class InOrder(val step: RatchetStep) : ReceiveOutcome()

    /**
     * Mensaje fuera de orden servido desde las skipped keys.
     * La clave es de UN SOLO USO: ya fue retirada del almacen.
     */
    data class FromSkipped(val step: RatchetStep) : ReceiveOutcome()

    /** El frame fue rechazado. No se consumio estado. */
    data class Rejected(val reason: RejectReason) : ReceiveOutcome()
}

/** Motivos de rechazo en recepcion. */
enum class RejectReason {
    /** `N` es anterior al actual y no hay skipped key: replay o invalido. */
    REPLAY_OR_UNKNOWN,

    /** El salto supera MAX_SKIP: coste de CPU denial-of-service. */
    SKIP_LIMIT_EXCEEDED,

    /** Ya se sirvio esta skipped key: es de un solo uso. */
    SKIPPED_KEY_ALREADY_USED,

    /**
     * La clave publica DH del header es de ORDEN PEQUEÑO: el acuerdo X25519
     * devuelve el secreto de todo cero y RFC 7748 §6.1 obliga a abortar.
     *
     * ## POR QUE UN MOTIVO PROPIO Y NO `REPLAY_OR_UNKNOWN`
     *
     * [REPLAY_OR_UNKNOWN] dice "el ratchet no tiene una clave que sirva para
     * este frame": es el motivo de la CADENA. Este dice otra cosa, y mas
     * fuerte: "este material no puede derivar ninguna clave, ni ahora ni
     * nunca, con ninguna cadena". Un frame de orden pequeño no esta replay,
     * no esta fuera de orden y no se parece a ningun frame legitimo que haya
     * pasado por aqui: es un frame que nadie con una clave real puede haber
     * producido. Colapsarlo en `REPLAY_OR_UNKNOWN` lo camufla de problema de
     * entrega cuando en realidad es material criptografico inservible, y esa
     * distincion es la que permite a quien recibe la traza decir "me estan
     * mandando claves publicas de orden pequeno" en vez de "el otro extremo me
     * esta reenviando mensajes".
     *
     * ## POR QUE NO CUBRE TAMBIEN LA LONGITUD INVALIDA
     *
     * Una DH de 31 bytes es tambien material que no puede ser clave, pero su
     * rechazo lo decide `previewReceive` MIRANDO SOLO EL MATERIAL, antes de
     * abrir ninguna rama, y devuelve `REPLAY_OR_UNKNOWN`. Aqui se decide
     * DESPUES, porque la unica forma de saber que una clave de 32 bytes es de
     * orden pequeno es intentar el acuerdo y ver que aborta. Son dos
     * comprobaciones en dos momentos distintos, con consecuencias distintas
     * sobre el estado, y unificarlas es un cambio de comportamiento del
     * rechazo por longitud que este checkpoint no toca.
     *
     * ## «DH NO UTILIZABLE» NO ES UN MOTIVO UNICO, Y TAMPOCO EN EL MOTOR
     *
     * La misma frontera existe, con los mismos DOS motivos, un layer mas
     * arriba: en el establecimiento (`X3dh` y `RatchetSessionBootstrap`) una
     * clave publica de acuerdo que no se puede usar se rechaza por
     * `IllegalArgumentException` si no mide 32 bytes, y por
     * [com.km.crypto.AllZeroSharedSecretException] si los mide
     * pero es de orden pequeno. El motor no tiene ningun canal de resultado
     * —no devuelve `ReceiveResult`— y no se le ha inventado uno para hacer
     * caber los dos casos en uno.
     *
     * La duplicidad es INTENCIONAL y esta fijada por dos checkpoints
     * distintos: unificarla exigiria decidir cual de los dos motivos se
     * extiende al otro, y esa decision cambia comportamiento ya establecido
     * (el del receptor, aqui; el de la validacion estructural de longitud, en
     * el motor). Ver `AgreementKeyContentionTest` (`AGREE-09` y `AGREE-10`),
     * que demuestra las DOS ramas en los dos lados y falla si alguien las
     * mezcla.
     */
    LOW_ORDER_DH_PUBLIC_KEY,
}

/**
 * Material criptografico derivado para un unico mensaje.
 *
 * NO contiene la `ChainKey`: esta nunca se expone fuera del ratchet.
 */
class RatchetStep internal constructor(
    /** Clave de UN SOLO mensaje. */
    val messageKey: ByteArray,
    /** Indice del mensaje dentro de su cadena. */
    val messageNumber: UInt,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RatchetStep) return false
        return messageNumber == other.messageNumber && messageKey.contentEquals(other.messageKey)
    }

    override fun hashCode(): Int = 31 * messageNumber.hashCode() + messageKey.contentHashCode()
}

/**
 * Identificador de cadena, con IGUALDAD POR VALOR.
 *
 * CRITICO: `ByteArray` en Kotlin usa igualdad por REFERENCIA. Usar
 * `Pair<ByteArray, UInt>` como clave de un mapa haria que una skipped key
 * nunca se encontrase, desactivando en silencio la entrega fuera de orden.
 * Esta clase compara contenido.
 *
 * En 3O.3 el identificador sera la clave publica DH del ratchet.
 */
class ChainIdentifier(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChainIdentifier) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String =
        "ChainIdentifier(" + bytes.joinToString("") { "%02x".format(it) } + ")"
}

/**
 * Clave de mensaje saltada, retenida para un envio fuera de orden.
 *
 * Se identifica por `(chainId, messageNumber)`: `N` por si solo no
 * identifica una clave, porque un DH ratchet genera cadenas nuevas que
 * vuelven a empezar en `N=0`.
 */
data class SkippedMessageKey(
    val chainId: ChainIdentifier,
    val messageNumber: UInt,
    val messageKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SkippedMessageKey) return false
        return messageNumber == other.messageNumber &&
            chainId == other.chainId &&
            messageKey.contentEquals(other.messageKey)
    }

    override fun hashCode(): Int {
        var result = chainId.hashCode()
        result = 31 * result + messageNumber.hashCode()
        result = 31 * result + messageKey.contentHashCode()
        return result
    }
}

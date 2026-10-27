package com.km.messaging

import com.km.model.FailureCode
import com.km.model.Message

/**
 * R1 — EL PUNTO DE DECISION DEL CAMINO DE ENVIO, Y LO QUE NO ES.
 *
 * `SenderImpl` pregunta: "¿tengo algo cifrado que entregar?". Si la respuesta es
 * `null`, no. Y aqui esta lo que hay que decir sin rodeos:
 *
 * - **`SecureOutbound == null` (registro no inyectado) -> el mensaje sale EN
 *   CLARO** por `Encoder` + `Transport.send`. Es el camino legado y se conserva
 *   por COMPATIBILIDAD: ningun consumidor actual construye el registro y romper
 *   todos no demostraria cifrado, demostraria rotura. Ese camino **no puede
 *   describirse como cifrado** en ningun sitio, ni en su KDoc, ni en el
 *   CHANGELOG, ni en un comentario.
 * - **Registro inyectado y sin sesion con el destinatario -> RECHAZO.** Nada
 *   encolado, nada entregado a `Transport.send`, estado `FAILED` con codigo de
 *   fallo. Nunca un reintento en claro como "plan B".
 *
 * La razon de que el rechazo sea observable y no una excepcion es que quien
 * llama necesita poder distinguir "no salio" de "salio", y el estado del mensaje
 * en el store es la unica verdad que sobrevive al proceso.
 *
 * ## EL ESPEJO EN RECEPCION, Y POR QUE ESTA AQUI
 *
 * El envio nunca se degrada a `Transport.send` con el registro presente. Sin un
 * espejo, la recepcion si se degradaba: un `MESSAGE` EN CLARO del camino legado
 * llegaba al handler con la misma forma y el mismo `from` que un mensaje E2E, sin
 * marca de origen, y el consumidor no podia saber que estaba leyendo algo sin
 * cifrar. Con el registro presente ese camino esta ahora CERRADO, con el mismo
 * criterio de rechazo observable que aqui: se cuenta por API publica en
 * `LegacyInboundObservation`, no se traga. Se declara aqui porque esta clase es
 * el punto de decision de R1 y una regla que solo se cumple a medias en un lado
 * no es una regla.
 *
 * LA CRIPTOGRAFIA NO ESTA AQUI
 *
 * Esta clase no cifra: envuelve un `MessageEnvelope` y lo entrega a
 * [SecureMessagingSession], que es donde vive el ratchet. El `messageId` viaja
 * DENTRO del sobre, que a su vez va dentro del plaintext cifrado, y no en el
 * header del `SecureFrame`: el header v1 son 44 bytes congelados y su AAD es el
 * header COMPLETO, asi que un identificador ahi quedaria fuera de la
 * autenticacion.
 */
interface SecureOutbound {

    /** Cifra y entrega [message] al destinatario que lleva su `to`. */
    fun send(message: Message): SecureSendOutcome

    /**
     * Reentrega [wire] tal cual, sin volver a cifrar.
     *
     * @param message el mensaje cuyo `to` decide la sesion. Viaja entero solo
     *   por su `messageId` y su `to`; su `payload` no se toca.
     */
    fun retransmit(message: Message, wire: ByteArray): SecureSendOutcome
}

/** Lo que devuelve [SecureOutbound]. Nunca `null`: siempre se sabe que paso. */
sealed class SecureSendOutcome {

    /** Salieron bytes CIFRADOS, y [wire] son exactamente los que salieron. */
    data class Encrypted(val wire: ByteArray, val bytes: Int) : SecureSendOutcome() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Encrypted) return false
            return bytes == other.bytes && wire.contentEquals(other.wire)
        }

        override fun hashCode(): Int = 31 * bytes + wire.contentHashCode()
    }

    /** No salio nada. [reason] dice por que, y [failureCode] como lo ve el API. */
    data class Rejected(
        val reason: SecureSendReject,
        val failureCode: FailureCode,
        /**
         * El frame que SI se produjo, cuando el rechazo fue del TRANSPORTE.
         *
         * `TRANSPORT_REJECTED` no es "no se cifro": el ratchet ya consumio su
         * clave y el wire existe. Si se tirara aqui, el reintento tendria que
         * re-cifrar y quemaria OTRA clave de la cadena por el mismo mensaje
         * (R5: el reenvio reutiliza el mismo wire). Se transporta para que
         * `SenderImpl` lo cachee y el reintento sea un `retransmit`.
         *
         * Vacio en los demas rechazos: ahi no hay frame que conservar.
         */
        val wire: ByteArray = ByteArray(0),
    ) : SecureSendOutcome() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Rejected) return false
            return reason == other.reason &&
                failureCode == other.failureCode &&
                wire.contentEquals(other.wire)
        }

        override fun hashCode(): Int =
            31 * (31 * reason.hashCode() + failureCode.hashCode()) + wire.contentHashCode()
    }
}

/** Motivos de rechazo del camino cifrado. */
enum class SecureSendReject {
    /** No hay sesion establecida con el destinatario. */
    NO_SESSION,

    /** El `PeerContext` de la sesion no esta AUTHENTICATED. */
    NOT_AUTHENTICATED,

    /** El ratchet no pudo producir el frame. */
    ENCRYPTION_FAILED,

    /** El `PeerTransportManager` no acepto los bytes. */
    TRANSPORT_REJECTED,

    /** La sesion esta cerrada. */
    CLOSED,

    /** El sobre no se pudo codificar. Es un fallo de emisor, no de transporte. */
    ENVELOPE_FAILED,
}

/**
 * La unica implementacion: el registro decide, la sesion ejecuta.
 *
 * NO posee sesiones ni las abre. [SecureSessionEstablishment] las abre,
 * [SecureSessionRegistry] las ancla, y esta clase solo las usa.
 *
 * ## LO QUE ESTO NO HACE: LIMITES DECLARADOS, NO TESTS
 *
 * Los cuatro limites siguientes son reales y no tienen arreglo en este slice.
 * Van aqui, y no en un test, porque un test que comprueba la REDACCION de un
 * KDoc pasa por un cambio de redaccion y falla por un cambio de codigo: eso no
 * es una invariante. La evidencia de estos limites es esta prosa y el CHANGELOG.
 *
 * 1. km-desktop sigue siendo TEST-ONLY para este camino. No tiene origen de
 *    `VerifiedContactBundle`: sin bundle verificado no hay `BootstrapPrekeys`,
 *    sin `BootstrapPrekeys` no hay `X3dh.initiate`, y sin sesion establecida el
 *    escritorio no puede entrar por aqui. Su `send` sigue por el camino legado,
 *    EN CLARO. No se ha escrito ningun test que fije ese comportamiento como
 *    correcto: documentaria un defecto como si fuera un requisito.
 * 2. El `ACK` sigue FIRMADO y EN CLARO, por `Transport.sendAck`. Cifrarlo
 *    exigiria `EnvelopeType.DELIVERY_ACK` dentro del sobre mas un
 *    `SecureMessageReceiver` que sepa tratar el duplicado, y eso es otro slice.
 *    Lo que si se hizo aqui es que la entrega del mensaje cifrado reutilice el
 *    emisor de `ACK` que ya existe, de modo que el `ACK` de un mensaje cifrado
 *    sale por el canal `ACK` y no genera el ciclo de amplificacion.
 * 3. El payload sigue persistido EN CLARO en el `MessageStore`:
 *    `createMessage` guarda `Message.payload` sin cifrar. El cifrado at-rest es
 *    otra fase, con su propio diseno de clave de almacenamiento. Que el cable
 *    vaya cifrado no dice nada del disco.
 * 4. `MessageEnvelope` v1 no lleva `timestamp`. Desde R9, el `Message` que se
 *    reconstruye al recibir se sella con el instante de LLEGADA
 *    (`System.currentTimeMillis()`), no con la hora del emisor: el formato no
 *    la lleva y fabricarla seria mentir. Se declara hora de RECEPCION, y con
 *    ella el `ORDER BY timestamp ASC` de `MessageDao` deja de empatar y de
 *    depender del rowid. Ampliar el sobre para llevar la hora de envio seria
 *    cambiar un formato versionado con vectores congelados (opcion B, diferida).
 */
class SecureOutboundImpl(
    private val registry: SecureSessionRegistry,
) : SecureOutbound {

    override fun send(message: Message): SecureSendOutcome {
        val session = registry.find(message.to)
            ?: return rejected(SecureSendReject.NO_SESSION)

        val plaintext = try {
            MessageEnvelopeCodec.encode(
                MessageEnvelope.userMessage(message.messageId, message.payload),
            )
        } catch (e: RuntimeException) {
            return rejected(SecureSendReject.ENVELOPE_FAILED)
        }

        return when (val result = session.send(plaintext)) {
            is SendResult.Ok -> SecureSendOutcome.Encrypted(result.wire, result.bytes)
            is SendResult.Rejected -> rejected(result.reason.toSendReject(), result.wire)
        }
    }

    override fun retransmit(message: Message, wire: ByteArray): SecureSendOutcome {
        if (wire.isEmpty()) return rejected(SecureSendReject.ENVELOPE_FAILED)
        val session = registry.find(message.to)
            ?: return rejected(SecureSendReject.NO_SESSION)
        return when (val result = session.sendTranscript(wire)) {
            // El wire que vuelve es el MISMO objeto conceptual que entro: no se
            // re-codifica, y por eso la cache del emisor sigue siendo la fuente.
            is SendResult.Ok -> SecureSendOutcome.Encrypted(result.wire, result.bytes)
            is SendResult.Rejected -> rejected(result.reason.toSendReject(), result.wire)
        }
    }

    /**
     * El codigo de fallo que ve el consumidor del API publico.
     *
     * [wire] solo viene con contenido en `TRANSPORT_REJECTED`: es el frame que
     * el ratchet ya produjo y que el transporte no acepto. Se propaga para que
     * el reintento no tenga que volver a cifrar.
     */
    private fun rejected(reason: SecureSendReject, wire: ByteArray = ByteArray(0)): SecureSendOutcome.Rejected =
        SecureSendOutcome.Rejected(reason, reason.toFailureCode(), wire)

    private fun SendReject.toSendReject(): SecureSendReject = when (this) {
        SendReject.NOT_AUTHENTICATED -> SecureSendReject.NOT_AUTHENTICATED
        SendReject.ENCRYPTION_FAILED -> SecureSendReject.ENCRYPTION_FAILED
        SendReject.TRANSPORT_REJECTED -> SecureSendReject.TRANSPORT_REJECTED
        SendReject.CLOSED -> SecureSendReject.CLOSED
    }
}

/**
 * R1 — por que el fallo del camino cifrado se reporta como `AUTH_FAILED`.
 *
 * Todos los rechazos de [SecureOutbound] son, desde el punto de vista de quien
 * tiene el mensaje en la mano, lo mismo: **no hay con quien hablar de forma
 * segura**, y el motivo concreto —no hay sesion, no esta autenticado, el
 * transporte no acepto— no cambia lo que el usuario puede hacer a continuacion
 * (volver a intentarlo mas tarde, o revisar la conexion). Inventar un codigo
 * por cada causa obligaria al consumidor a distinguir casos que hoy son
 * indistinguibles para el, y `NO_SESSION` se confunde con un problema de red si
 * se proyecta como `AUTH_FAILED` sin explicacion. El motivo fino vive en
 * [SecureSendOutcome.Rejected.reason] y en el log; el codigo del store es el
 * que la aplicacion ya sabe tratar.
 */
internal fun SecureSendReject.toFailureCode(): FailureCode = when (this) {
    SecureSendReject.NO_SESSION,
    SecureSendReject.NOT_AUTHENTICATED,
    -> FailureCode.AUTH_FAILED

    SecureSendReject.ENCRYPTION_FAILED,
    SecureSendReject.ENVELOPE_FAILED,
    -> FailureCode.INTERNAL_ERROR

    SecureSendReject.TRANSPORT_REJECTED -> FailureCode.PEER_NOT_FOUND
    SecureSendReject.CLOSED -> FailureCode.INTERNAL_ERROR
}

/**
 * El envio cifrado fue rechazado.
 *
 * No sustituye al estado `FAILED` del store, que es la verdad observable por
 * API publica; solo evita que un `Result.failure` sin tipo llegue al consumidor.
 * El motivo fino vive en [SecureSendOutcome.Rejected.reason] y en el estado.
 */
class SecureSendRejectedException(
    val reason: SecureSendReject,
) : RuntimeException("envio cifrado rechazado: $reason")

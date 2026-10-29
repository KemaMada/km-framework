package com.example.keymessage.core

import com.km.api.KeyMessageCore
import com.km.api.KeyMessageCoreImpl
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.messaging.LegacyInboundObservation
import com.km.messaging.SecureInbound
import com.km.messaging.SecureSessionRegistry
import com.km.model.IdentityId
import com.km.node.PeerTransportManager
import com.km.protocol.ConnectionState
import com.km.messaging.SecureSendRejectedException
import com.km.protocol.Transport
import com.km.storage.DuplicateStore
import com.km.storage.MessageStore
import com.km.storage.OfflineQueue
import com.example.keymessage.contact.ContactBook
import com.example.keymessage.util.KMLog
import com.example.keymessage.util.hexToBytes

/**
 * 2.1 — Las piezas del nucleo, y la garantia de que NO hay camino en claro.
 *
 * ## LA INYECCION QUE NO SE PUEDE OLVIDAR
 *
 * [CoreFactory.create] construye el nucleo con `secureSessionRegistry` PRESENTE
 * y `allowLegacyCleartextReceive = false`. Las dos cosas van juntas y por eso
 * estan en una sola fabrica, sin parametros que las puedan desactivar:
 *
 *  - Con el registro presente y sin sesion con el destinatario, el envio se
 *    RECHAZA: estado `FAILED` con codigo de fallo y
 *    `SecureSendRejectedException`. El mensaje no sale, no se encola y no se
 *    reintenta en claro.
 *  - Con el registro presente y el opt-in en `false`, un `MESSAGE` en claro
 *    entrante NO llega al handler, y el rechazo se cuenta en
 *    [LegacyInboundObservation.rejectedCleartext].
 *
 * Sin el registro —o con el opt-in— el camino legado sigue abierto y el envio
 * sale en claro. Que el nucleo lo permita es una decision de compatibilidad de
 * km-core, ya escrita alli; que la app lo active, ninguna.
 *
 * ## LO QUE ESTA FABRICA NO HACE
 *
 * No conecta, no importa contactos y no abre sesiones. Es composicion: por eso
 * `SecureSendRejectionTest` puede construir el mismo nucleo que en produccion en
 * un test de JVM y medir lo que sale por el backend.
 */
class CoreFactory(
    private val ed25519: Ed25519 = Ed25519Impl(),
) {

    /** Resultado de [create]: el nucleo y las tres piezas que hay que mirar. */
    class CoreBundle(
        val core: KeyMessageCoreImpl,
        val registry: SecureSessionRegistry,
        val manager: PeerTransportManager,
    ) {
        /** Lo que el camino legacy ha hecho. observable por API publica. */
        val legacyInbound: LegacyInboundObservation get() = core.legacyInbound

        /** El puente de recepcion cifrada, presente porque hay las dos inyecciones. */
        val secureInbound: SecureInbound? get() = core.secureInbound
    }

    /**
     * @param peerPublicKeyResolver clave de FIRMA del par, para autenticar los
     *   `ACK`. La app la resuelve del registro de contactos VERIFICADOS: sin
     *   bundle verificado no hay clave de firma que verificar, y un resolvedor
     *   que inventa una seria peor que uno que devuelve `null`.
     */
    fun create(
        localIdentity: IdentityId,
        localPrivateKey: ByteArray,
        transport: Transport,
        manager: PeerTransportManager,
        messageStore: MessageStore,
        offlineQueue: OfflineQueue,
        duplicateStore: DuplicateStore,
        contactBook: ContactBook,
    ): CoreBundle {
        val registry = SecureSessionRegistry(localIdentity)
        val core = KeyMessageCoreImpl(
            localIdentity = localIdentity,
            localPrivateKey = localPrivateKey,
            transport = transport,
            ed25519 = ed25519,
            messageStore = messageStore,
            offlineQueue = offlineQueue,
            duplicateStore = duplicateStore,
            peerPublicKeyResolver = { id ->
                // La clave de FIRMA sale del bundle VERIFICADO que hay en el
                // registro de contactos. Si el contacto no esta verificado, no
                // hay clave: un resolvedor que devolviera una seria peor que
                // uno que devuelve `null`, porque firmaria con una clave
                // inventada.
                contactBook.find(id)?.subjectSigningKey?.let { hex ->
                    runCatching { hex.hexToBytes() }.getOrNull()
                }
            },
            secureSessionRegistry = registry,
            // El opt-in en `false` es el DEFAULT de km-core y tambien se escribe
            // aqui a proposito: que dependa de un default que nadie ve es la
            // forma de que un dia cambie sin que nadie lo decida.
            allowLegacyCleartextReceive = false,
            peerTransportManager = manager,
        )
        KMLog.event("[CONFIG]", "nucleo construido con registro de sesiones: sin camino en claro")
        return CoreBundle(core, registry, manager)
    }
}

/**
 * Traduce el resultado de un envio al texto que la UI muestra.
 *
 * ## POR QUE ESTA AQUI Y NO EN LA PANTALLA
 *
 * Porque el texto que se ve no puede decidirse en un `@Composable`: "FAILED" y
 * "SENT" se distinguen por un codigo de fallo y una razon, y si cada pantalla
 * escribe su propio texto, dos pantallas diran cosas distintas del mismo
 * resultado. Y porque hay UN caso que no admite matiz: un rechazo por no tener
 * sesion significa que el mensaje NO salio, y ningun texto puede dejarlo
 * dudoso.
 */
object SendOutcome {

    /** Lo que la pantalla pinta junto al mensaje. */
    data class Ui(val deliveredLabel: String, val rejectionReason: String? = null, val failed: Boolean = false)

    /**
     * Traduce el resultado del nucleo.
     *
     * El caso que no admite matiz es `FAILED`: significa que el mensaje NO
     * salio, y la pantalla no puede pintarlo como enviado por mucho que el
     * fallo se haya producido "despues de escribir en disco". Un `QUEUED` es un
     * caso aparte y tambien es honesto: encolado no es entregado.
     */
    fun of(sendResult: com.km.protocol.SendResult): Ui = when (sendResult.state) {
        // R12: `SENT` = el socket local acepto los bytes. Se rotula OUT, no
        // "entregado", y se dice que falta la confirmacion.
        com.km.model.MessageState.SENT -> Ui(
            deliveredLabel = "OUT",
            rejectionReason = "al rele, SIN confirmar (sin ACK del par)",
            failed = false,
        )
        com.km.model.MessageState.QUEUED -> Ui(
            deliveredLabel = "ENCOLADO",
            rejectionReason = "sin canal: encolado, NO entregado",
            failed = true,
        )
        com.km.model.MessageState.DELIVERED -> Ui(deliveredLabel = "DELIVERED", failed = false)
        else -> Ui(
            deliveredLabel = "NOT SENT",
            rejectionReason = sendResult.failureCode?.name ?: "sin motivo declarado",
            failed = true,
        )
    }
}

/**
 * Traduce el fallo del nucleo al texto que la pantalla muestra.
 *
 * @throws SecureSendRejectedException NO se envuelve ni se convierte en texto
 *   suelto: quien lo recibe tiene que poder distinguir "no hay sesion cifrada"
 *   de un fallo de transporte, y [KMLog] lo registra con su codigo.
 */
fun rejectedReasonOf(failure: Throwable): String = when (failure) {
    is SecureSendRejectedException -> "sin sesion cifrada: mensaje NO enviado (${failure.reason})"
    else -> failure.message ?: failure::class.simpleName ?: "fallo desconocido"
}

/** Estado del rele para la pantalla de inicio. */
fun ConnectionState.isUsable(): Boolean = this == ConnectionState.ONLINE
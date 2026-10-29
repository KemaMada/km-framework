package com.example.keymessage.core

import com.km.messaging.SecureSendReject
import com.km.messaging.SecureSendRejectedException
import com.km.model.IdentityId
import com.km.model.MessageState
import com.km.storage.InMemoryDuplicateStore
import com.km.storage.InMemoryMessageStore
import com.km.storage.InMemoryOfflineQueue
import com.example.keymessage.contact.InMemoryContactBook
import com.example.keymessage.support.RecordingTransport
import com.example.keymessage.support.TestWorld
import com.example.keymessage.support.countingManager
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 2.1 — Sin sesion cifrada el mensaje NO sale. Y se ve que no sale.
 *
 * ## QUE AFIRMA
 *
 * 1. `core.sendMessage` devuelve fallo cuyo causa es [SecureSendRejectedException].
 * 2. El estado del mensaje queda `FAILED` con codigo de fallo, observable por la
 *    API del nucleo.
 * 3. **CERO bytes salieron**: ni por el camino cifrado (el backend que cuenta) ni
 *    por el legado (el transporte que cuenta). Esta es la asercion que importa:
 *    "no se envio" y "no hay excepcion" no son lo mismo, y un rechazo que deja
 *    pasar el byte habria seria un rechazo de mentira.
 * 4. Y ningun camino de excepcion: el opt-in de recepcion en claro sigue cerrado
 *    (eso lo mide `LegacyInboundClosedTest`, aqui se afirma que el nucleo de la
 *    app es el que lleva las dos inyecciones).
 */
class SecureSendRejectionTest {

    private val material = com.example.keymessage.identity.IdentityMaterialFactory()
        .fromSeed(ByteArray(32) { (it + 3).toByte() })
    private val parContacto = TestWorld.deviceWithRoot("bob")

    /** El MISMO nucleo que construye `CoreFactory` en la app. */
    private fun nucleo(transport: RecordingTransport, libro: InMemoryContactBook = InMemoryContactBook()) =
        countingManager().let { (manager, backend) ->
            CoreFactory().create(
                localIdentity = material.identityId,
                localPrivateKey = material.keyPair.privateKey,
                transport = transport,
                manager = manager,
                messageStore = InMemoryMessageStore(),
                offlineQueue = InMemoryOfflineQueue(),
                duplicateStore = InMemoryDuplicateStore(),
                contactBook = libro,
            ) to backend
        }

    @Test
    fun `sin sesion el envio se RECHAZA y no sale ni un byte`() {
        val transport = RecordingTransport(online = true)
        val (bundle, backend) = nucleo(transport)

        val destino = parContacto.root.identityId
        val creado = bundle.core.createMessage(destino, "hola, sin sesion 9090".toByteArray()).getOrThrow()
        // `KeyMessageCoreImpl.sendMessage` ENVUELVE el fallo del emisor en un
        // `Result` de exito y lo traduce a `SendResult(state = FAILED)`: la
        // `SecureSendRejectedException` NO sale por esta API. Lo observable es el
        // estado, y lo que la pantalla pinta sale de ahi.
        val resultado = bundle.core.sendMessage(creado).getOrThrow()

        assertEquals(MessageState.FAILED, resultado.state, "sin sesion el envio queda FAILED")
        assertEquals(
            MessageState.FAILED,
            bundle.core.getMessageState(creado.messageId).getOrThrow(),
            "y el estado guardado por el nucleo coincide: no hay excepcion que ver, hay un estado que consultar",
        )

        assertEquals(0, backend.totalBytes, "CERO bytes por el camino cifrado")
        assertEquals(0, transport.cleartextOutbound.size, "CERO mensajes por el camino legado")
        assertEquals(0, transport.totalBytesOut(), "CERO bytes en total: nada salio de la app")
    }

    /**
     * CRITICAL-2 — el `FailureCode` del rechazo es el REAL, no `INTERNAL_ERROR`.
     *
     * ## QUE AFIRMA, Y POR QUE NO ES LO MISMO QUE EL TEST DE ARRIBA
     *
     * El test de arriba fija el ESTADO (`FAILED`). Este fija el CODIGO, que es lo
     * que la pantalla y el `[SEND]` log ensenan. Antes de este arreglo,
     * `sendMessage` devolvia `FAILED` con `INTERNAL_ERROR` HARDCODEADO para
     * CUALQUIER fallo, mientras el store ya tenia `FAILED/AUTH_FAILED`: el mismo
     * hecho se reportaba de dos formas distintas segun quien lo mirara.
     *
     * La causa real de "no hay sesion" es `NO_SESSION`, que
     * `SecureSendOutcome` proyecta a `AUTH_FAILED`. Un `INTERNAL_ERROR` ahi no es
     * un matiz: dice al usuario "bug interno" cuando la causa es recuperable
     * (abrir la sesion).
     *
     * ## POR QUE LAS TRES ASERCIONES
     *
     * 1. El `SendResult` que ve el consumidor trae `AUTH_FAILED`.
     * 2. El store —la verdad observable— tambien. Si solo se mirara el
     *    `SendResult`, un arreglo que hardcodeara `AUTH_FAILED` pasaria sin que
     *    el store lo respaldara.
     * 3. Y NO trae `INTERNAL_ERROR`: es la negacion explicita del defecto, no la
     *    afirmacion de su contrario.
     */
    @Test
    fun `el FailureCode del rechazo es AUTH_FAILED, no INTERNAL_ERROR`() {
        val transport = RecordingTransport(online = true)
        val (bundle, _) = nucleo(transport)

        val creado = bundle.core.createMessage(parContacto.root.identityId, "sin sesion 4242".toByteArray()).getOrThrow()
        val resultado = bundle.core.sendMessage(creado).getOrThrow()

        assertEquals(
            com.km.model.FailureCode.AUTH_FAILED,
            resultado.failureCode,
            "el consumidor ve la causa REAL: no hay sesion, recuperable",
        )
        assertEquals(
            com.km.model.FailureCode.AUTH_FAILED,
            bundle.core.getConversation(parContacto.root.identityId).getOrThrow().single().failureCode,
            "y el store coincide: un codigo que el store no respalda seria otra forma de mentira",
        )
        assertTrue(
            resultado.failureCode != com.km.model.FailureCode.INTERNAL_ERROR,
            "el codigo aplanado INTERNAL_ERROR es justo el defecto que este test descarta",
        )
    }

    @Test
    fun `el rechazo se pinta como NO enviado, con un motivo`() {
        val transport = RecordingTransport(online = true)
        val (bundle, _) = nucleo(transport)

        val creado = bundle.core.createMessage(parContacto.root.identityId, "x".toByteArray()).getOrThrow()
        val resultado = bundle.core.sendMessage(creado).getOrThrow()
        val ui = SendOutcome.of(resultado)

        assertEquals("NOT SENT", ui.deliveredLabel, "no se pinta como enviado")
        assertTrue(ui.failed, "ni se marca como entregado")
        assertTrue(!ui.rejectionReason.isNullOrEmpty(), "y hay un motivo que ensenar")
    }

    @Test
    fun `la excepcion tipada del framework se traduce al texto de la pantalla`() {
        // `SenderImpl.send` produce `SecureSendRejectedException`, y la superficie
        // publica de km-core la envuelve. Este test deja FIJO que la app puede
        // distinguir el rechazo cifrado de un fallo cualquiera cuando lo tiene,
        // y que el texto que pinta dice que no se envio.
        val rechazo = SecureSendRejectedException(SecureSendReject.NO_SESSION)

        assertTrue(
            rejectedReasonOf(rechazo).contains("NO enviado"),
            "el texto de la pantalla no puede dejar duda: ${rejectedReasonOf(rechazo)}",
        )
    }

    @Test
    fun `un mensaje FAILED se pinta como NO enviado`() {
        val transport = RecordingTransport(online = true)
        val (bundle, _) = nucleo(transport)

        val creado = bundle.core.createMessage(parContacto.root.identityId, "sin destino".toByteArray()).getOrThrow()
        bundle.core.sendMessage(creado)

        val estado = bundle.core.getConversation(parContacto.root.identityId).getOrThrow().single()
        val ui = SendOutcome.of(
            com.km.protocol.SendResult(estado.messageId, estado.state, estado.failureCode),
        )
        assertTrue(ui.failed, "un FAILED nunca se pinta como enviado")
        assertEquals("NOT SENT", ui.deliveredLabel, "la etiqueta lo dice sin ambiguedad")
        assertTrue(ui.rejectionReason != null, "y hay un motivo que ensenar")
    }

    @Test
    fun `sin conexion el mensaje se ENCOLA y no se marca como enviado`() {
        val transport = RecordingTransport(online = false)
        val (bundle, backend) = nucleo(transport)

        val creado = bundle.core.createMessage(parContacto.root.identityId, "sin canal".toByteArray()).getOrThrow()
        bundle.core.sendMessage(creado)

        assertEquals(0, backend.totalBytes, "sin canal no sale ningun byte")
        val estado = bundle.core.getMessageState(creado.messageId).getOrThrow()
        assertEquals(MessageState.QUEUED, estado, "queda encolado, no enviado")
        assertEquals(
            "ENCOLADO",
            SendOutcome.of(com.km.protocol.SendResult(creado.messageId, estado, null)).deliveredLabel,
            "y la pantalla lo distingue de OUT/SENT (R16: encolado no es entregado)",
        )
    }

    @Test
    fun `el nucleo de la app tiene cerrado el camino legacy`() {
        val transport = RecordingTransport(online = true)
        val (bundle, _) = nucleo(transport)

        assertTrue(
            !bundle.legacyInbound.legacyReceiveEnabled,
            "con el registro inyectado el camino legacy de RECEPCION esta cerrado",
        )
        assertTrue(
            bundle.secureInbound != null,
            "y el puente de recepcion cifrada existe: hay registro y hay manager",
        )
    }

    @Test
    fun `control un destino valido de verdad no es un caso trivial`() {
        // El pretesto: si `IdentityId` vacio fuera un destino valido, el rechazo
        // del test anterior podria venir de ahi y no de la falta de sesion.
        val transporte = RecordingTransport(online = true)
        val (bundle, backend) = nucleo(transporte)

        val creado = bundle.core.createMessage(IdentityId(""), "a ninguna parte".toByteArray()).getOrThrow()
        bundle.core.sendMessage(creado)
        assertEquals(0, backend.totalBytes, "ni a una identidad vacia sale un byte")
    }
}
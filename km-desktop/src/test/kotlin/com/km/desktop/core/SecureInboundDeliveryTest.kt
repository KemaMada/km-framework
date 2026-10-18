package com.km.desktop.core

import com.km.desktop.session.SessionCoordinator
import com.km.desktop.support.RelayProbe
import com.km.desktop.support.SecureTestIdentities
import com.km.desktop.support.recordingTransport
import com.km.desktop.support.relayProbeManager
import com.km.identity.VerifiedContactBundle
import com.km.messaging.LocalInitiatorKeys
import com.km.messaging.LocalResponderKeys
import com.km.messaging.SecureSessionRegistry
import com.km.model.IdentityId
import com.km.model.Message
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 3.2 — Un `SecureFrame` que CRUZA el backend llega al handler con el MISMO
 * `messageId` y el MISMO payload.
 *
 * ## QUE AFIRMA
 *
 * Cuando A envia por el camino cifrado y el frame se entrega por el cable de
 * ENTRADA del backend de B, el `Message` que ve el handler de B tiene el
 * `messageId` que A genero y el payload exacto, byte a byte.
 *
 * ## POR QUE EL `messageId` Y NO SOLO EL PAYLOAD
 *
 * Porque el `messageId` viaja DENTRO del plaintext cifrado, no en el sobre del
 * `SecureFrame`. Reconstruirlo fuera —con un UUID nuevo pero valido— daria un
 * mensaje que parece autentico y que el emisor no puede correlacionar: su
 * `ACK` apuntaria a un identificador que el receptor nunca vio. Es el ataque de
 * valor que verify-v4 #243 ya midio, y por eso la asercion es sobre el VALOR.
 *
 * ## POR QUE NO SE USA UN DOBLE DE TRANSPORTE PARA LA ENTREGA
 *
 * Porque el wire se captura de la SALIDA REAL del manager y se entrega por el
 * cable de ENTRADA. Un doble que se pasara el byte a si mismo probaria que dos
 * objetos se hablan, no que dos extremos comparten sesion.
 */
class SecureInboundDeliveryTest {

    private val coordinator = SessionCoordinator()

    private val parAlice = SecureTestIdentities.deviceWithRoot("alice")
    private val parBob = SecureTestIdentities.deviceWithRoot("bob")

    private val alice: SecureTestIdentities.Device get() = parAlice.device
    private val bob: SecureTestIdentities.Device get() = parBob.device

    private val aliceId: IdentityId = parAlice.root.identityId
    private val bobId: IdentityId = parBob.root.identityId

    private val opkId: Long = SecureTestIdentities.ONE_TIME_PREKEY_ID

    private class Extremo(
        val bundle: CoreFactory.CoreBundle,
        val probe: RelayProbe,
        val manager: com.km.node.PeerTransportManager,
        val recibido: MutableList<Message>,
    )

    /** Un extremo COMPLETO: nucleo con registro y manager, mas su cable. */
    private fun extremo(identidad: IdentityId, clavePrivada: ByteArray): Extremo {
        val (manager, probe) = relayProbeManager()
        val bundle = CoreFactory().create(
            localIdentity = identidad,
            localPrivateKey = clavePrivada,
            transport = recordingTransport(),
            manager = manager,
            peerPublicKeyResolver = { null },
        )
        val recibido = mutableListOf<Message>()
        bundle.core.registerMessageHandler { recibido += it }
        return Extremo(bundle, probe, manager, recibido)
    }

    private class Pareja(val a: Extremo, val b: Extremo)

    /** Establece sesion entre los dos extremos, con el PREKEY cruzando el cable. */
    private fun emparejar(): Pareja {
        bob.issueOneTimePreKey(opkId)
        val a = extremo(aliceId, parAlice.root.keyPair.privateKey)
        val b = extremo(bobId, parBob.root.keyPair.privateKey)

        val iniciada = coordinator.iniciar(
            localIdentity = aliceId,
            peerContext = coordinator.contextoHacia(
                VerifiedContactBundle.verified(parBob.root.bundleMapFor(listOf(bob), bob, listOf(opkId))),
            ),
            localKeys = LocalInitiatorKeys(alice.deviceId, alice.agreementKey),
            manager = a.manager,
            registry = a.bundle.registry,
        )
        conectar(iniciada.session, a.manager)
        iniciada.session.sendTranscript(iniciada.prekeyFrame)

        coordinator.aceptar(
            localIdentity = bobId,
            peerContext = coordinator.contextoHacia(
                VerifiedContactBundle.verified(parAlice.root.bundleMapFor(listOf(alice), alice, emptyList())),
            ),
            localKeys = LocalResponderKeys(
                deviceId = bob.deviceId,
                agreementKey = bob.agreementKey,
                signedPreKey = bob.signedPreKey,
                oneTimePreKey = requireNotNull(bob.oneTimePreKey(opkId)) { "B emitio el OPK $opkId" },
                oneTimePreKeyId = opkId,
            ),
            prekeyFrameWire = a.probe.enviados.last().second,
            manager = b.manager,
            registry = b.bundle.registry,
        )
        val sesionB = requireNotNull(b.bundle.registry.find(aliceId)) { "B tiene sesion con A" }
        conectar(sesionB, b.manager)
        return Pareja(a, b)
    }

    private fun conectar(sesion: com.km.messaging.SecureMessagingSession, manager: com.km.node.PeerTransportManager) {
        manager.sendOffer(sesion.localIdentity, sesion.peerContext, "sdp-offer", sesion.remotePeerId).getOrThrow()
        manager.receiveAnswer(sesion.localIdentity, sesion.remotePeerId, "sdp-answer").getOrThrow()
        manager.markConnected(sesion.localIdentity, sesion.peerContext).getOrThrow()
    }

    @Test
    fun `3-2 el SecureFrame que cruza el backend llega con el MISMO messageId y payload`() {
        val par = emparejar()

        val carga = "contenido que tiene que llegar intacto al otro extremo 8291".toByteArray(Charsets.UTF_8)
        val creado = par.a.bundle.core.createMessage(bobId, carga).getOrThrow()
        val enviado = par.a.bundle.core.sendMessage(creado)
        assertTrue(enviado.isSuccess, "A envia por el camino cifrado")

        assertEquals(2, par.a.probe.enviados.size, "el frame PREKEY y el del mensaje: dos frames, no uno")
        par.b.probe.deliver(aliceId, par.a.probe.enviados.last().second)

        assertEquals(1, par.b.recibido.size, "y B lo recibe UNA vez")
        val entregado = par.b.recibido.single()
        assertEquals(creado.messageId, entregado.messageId, "el MISMO messageId: es lo que correlaciona el ACK")
        assertArrayEquals(carga, entregado.payload, "y el payload byte a byte")
        assertEquals(aliceId, entregado.from, "del emisor que el transporte entrego")
        assertEquals(bobId, entregado.to, "al receptor local")
    }

    @Test
    fun `3-2 el puente cuenta la entrega y no cuenta el frame PREKEY como mensaje`() {
        val par = emparejar()
        val puente = requireNotNull(par.b.bundle.secureInbound) { "el puente existe: hay registro y manager" }
        val entregadosAntes = puente.delivered.get()

        // El frame PREKEY ya cruzo el cable de A, pero se entrego DIRECTAMENTE a
        // `aceptar` y no por el backend de B: no es un mensaje de aplicacion.
        assertEquals(0, par.b.recibido.size, "el PREKEY no llega al handler de mensajes")
        assertEquals(0, entregadosAntes, "y el puente no lo cuenta como entrega")

        val carga = "un mensaje de aplicacion normal 1102".toByteArray(Charsets.UTF_8)
        val creado = par.a.bundle.core.createMessage(bobId, carga).getOrThrow()
        par.a.bundle.core.sendMessage(creado)
        par.b.probe.deliver(aliceId, par.a.probe.enviados.last().second)

        assertEquals(1, puente.delivered.get(), "el puente cuenta UNA entrega de aplicacion")
    }

    @Test
    fun `3-2 el texto en claro NO aparece en el frame que cruza el backend`() {
        val par = emparejar()

        val carga = "marca-inequivoca-que-no-debe-ir-en-claro-7734".toByteArray(Charsets.UTF_8)
        par.a.bundle.core.createMessage(bobId, carga).getOrThrow()
        par.a.bundle.core.sendMessage(
            par.a.bundle.core.getConversation(bobId).getOrThrow().single(),
        )

        val wire = par.a.probe.enviados.last().second
        assertTrue(
            !String(wire, Charsets.ISO_8859_1).contains("marca-inequivoca"),
            "el texto en claro no aparece en ningun byte del frame",
        )
        assertTrue(wire.size > carga.size, "y el frame es mas grande que el payload")
    }

    /**
     * CONTROL NEGATIVO: un `messageId` RECONSTRUIDO con un UUID valido pero
     * DISTINTO es indistinguible a simple vista y no es el mismo.
     *
     * Esta prueba no modifica el codigo de produccion: fija el hecho de que
     * el identificador del sobre es el que cuenta, de modo que la mutacion que
     * lo reconstruya tenga contra que fallar.
     */
    @Test
    fun `control negativo dos UUID validos NO son el mismo identificador`() {
        val par = emparejar()
        val carga = "contenido para el contraste de identificadores 3390".toByteArray(Charsets.UTF_8)
        val creado = par.a.bundle.core.createMessage(bobId, carga).getOrThrow()
        par.a.bundle.core.sendMessage(creado)
        par.b.probe.deliver(aliceId, par.a.probe.enviados.last().second)

        val entregado = par.b.recibido.single()
        val otroUuid = java.util.UUID.randomUUID()

        assertEquals(creado.messageId.value, entregado.messageId.value, "el del mensaje entregado es el de A")
        assertNotEquals(
            otroUuid.toString(),
            entregado.messageId.value,
            "y un UUID nuevo, aunque sea VALIDO, no es el mismo: por eso la asercion es sobre el VALOR",
        )
    }

    /**
     * CONTROL POSITIVO: sin entrega, el handler no ve nada.
     *
     * Sin esta prueba, "llega con el mismo messageId" seria verdad tambien con
     * un handler que recibiera un mensaje inventado.
     */
    @Test
    fun `control positivo sin frame entregado, el handler de B esta vacio`() {
        val par = emparejar()
        val carga = "contenido que se entrega despues 5528".toByteArray(Charsets.UTF_8)
        par.a.bundle.core.createMessage(bobId, carga).getOrThrow()
        par.a.bundle.core.sendMessage(par.a.bundle.core.getConversation(bobId).getOrThrow().single())

        assertEquals(0, par.b.recibido.size, "A envio, pero nada cruzó el cable de ENTRADA de B: no hay entrega")
        assertEquals(0, par.b.probe.recibidos.size, "y el cable de entrada no registra nada")

        par.b.probe.deliver(aliceId, par.a.probe.enviados.last().second)

        assertEquals(1, par.b.recibido.size, "un frame, una entrega: la asercion anterior ya no era trivial")
    }
}

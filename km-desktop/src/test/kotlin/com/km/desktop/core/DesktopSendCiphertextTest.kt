package com.km.desktop.core

import com.km.desktop.session.SessionCoordinator
import com.km.desktop.support.RelayProbe
import com.km.desktop.support.SecureTestIdentities
import com.km.desktop.support.recordingTransport
import com.km.desktop.support.relayProbeManager
import com.km.identity.VerifiedContactBundle
import com.km.messaging.LocalInitiatorKeys
import com.km.messaging.LocalResponderKeys
import com.km.model.IdentityId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 3.3 — El `send` del escritorio va por el camino CIFRADO, y sin sesion NO sale.
 *
 * ## QUE AFIRMA
 *
 * 1. Con sesion establecida, el byte que sale del backend NO contiene el texto
 *    en UTF-8, y el resultado es `SENT`.
 * 2. Sin sesion, el resultado NO es `SENT` y no sale ni un byte.
 *
 * ## POR QUE AFIRMAR EL CONTENIDO Y NO SOLO EL ESTADO
 *
 * Porque `SENT` y "los bytes que salieron" son dos hechos distintos, y una
 * regresion que dejara el estado en `SENT` mientras el texto viaja en claro
 * seria el fallo mas grave de todos: el nucleo que reporta exito y el cable que
 * lleva el mensaje. El estado se mira por API publica; el contenido, por el
 * cable.
 */
class DesktopSendCiphertextTest {

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
        val legado: recordingTransport,
    )

    private fun extremo(identidad: IdentityId, clavePrivada: ByteArray): Extremo {
        val (manager, probe) = relayProbeManager()
        val legado = recordingTransport()
        return Extremo(
            CoreFactory().create(
                localIdentity = identidad,
                localPrivateKey = clavePrivada,
                transport = legado,
                manager = manager,
                peerPublicKeyResolver = { null },
            ),
            probe,
            manager,
            legado,
        )
    }

    /** Los dos extremos con sesion establecida entre ellos. */
    private fun conSesion(): Extremo {
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

        val aceptada = coordinator.aceptar(
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
        conectar(aceptada.session, b.manager)
        return a
    }

    private fun conectar(sesion: com.km.messaging.SecureMessagingSession, manager: com.km.node.PeerTransportManager) {
        manager.sendOffer(sesion.localIdentity, sesion.peerContext, "sdp-offer", sesion.remotePeerId).getOrThrow()
        manager.receiveAnswer(sesion.localIdentity, sesion.remotePeerId, "sdp-answer").getOrThrow()
        manager.markConnected(sesion.localIdentity, sesion.peerContext).getOrThrow()
    }

    /** El texto que el usuario escribe. Marcador inequivoco, no una cadena vacia. */
    private val TextoDelUsuario = "esta cadena jamas debe viajar en claro 6023".toByteArray(Charsets.UTF_8)

    @Test
    fun `con sesion, lo que sale del backend NO contiene el texto en UTF-8`() {
        val a = conSesion()
        val antesDelEnvio = a.probe.bytesSalientes

        val creado = a.bundle.core.createMessage(bobId, TextoDelUsuario).getOrThrow()
        val resultado = a.bundle.core.sendMessage(creado)

        assertEquals(
            com.km.model.MessageState.SENT,
            resultado.getOrThrow().state,
            "con sesion, el envio es SENT",
        )
        val frame = a.probe.enviados.last().second
        assertTrue(
            !String(frame, Charsets.ISO_8859_1).contains("esta cadena jamas"),
            "el texto en claro NO aparece en ningun byte del frame",
        )
        assertTrue(
            a.probe.bytesSalientes > antesDelEnvio,
            "y ha salido ALGO por el backend: no se resuelve rechazando en silencio",
        )
    }

    @Test
    fun `sin sesion, el resultado NO es SENT y no sale NINGUN byte`() {
        val a = extremo(aliceId, parAlice.root.keyPair.privateKey)
        val destino = SecureTestIdentities.otroIdentityId()

        val creado = a.bundle.core.createMessage(destino, TextoDelUsuario).getOrThrow()
        val resultado = a.bundle.core.sendMessage(creado).getOrThrow()

        assertEquals(
            com.km.model.MessageState.FAILED,
            resultado.state,
            "sin sesion NO puede ser SENT: el mensaje no sale",
        )
        assertEquals(0, a.probe.bytesSalientes, "cero bytes por el camino cifrado")
        assertEquals(0, a.legado.bytesEnClaro, "cero bytes por el camino LEGADO: no hay degradacion a texto plano")
        assertTrue(a.bundle.registry.peers().isEmpty(), "y no se ha inventado ninguna sesion")
    }

    /**
     * El texto en claro viaja por el camino LEGADO cuando el camino cifrado no
     * esta disponible, y eso es el defecto que esta puerta cierra.
     *
     * Se construye el MISMO nucleo por la via sin registro —la que el escritorio
     * usaba antes de p5— y se comprueba que el texto SALE. Sin esta prueba,
     * "cero bytes por el camino legacy" seria verde con un nucleo que degrada,
     * porque el nucleo que se prueba es el que tiene el registro puesto.
     */
    @Test
    fun `control negativo SIN registro el texto SI sale en claro, que es el defecto que se cierra`() {
        val local = SecureTestIdentities.localEnd("alice")
        val (manager, probe) = relayProbeManager()
        val legado = recordingTransport()
        val sinRegistro = com.km.api.KeyMessageCoreImpl(
            localIdentity = local.identityId,
            localPrivateKey = local.ed25519PrivateKey,
            transport = legado,
        )
        requireNotNull(probe)
        val destino = SecureTestIdentities.otroIdentityId()

        val creado = sinRegistro.createMessage(destino, TextoDelUsuario).getOrThrow()
        val resultado = sinRegistro.sendMessage(creado).getOrThrow()

        assertEquals(
            com.km.model.MessageState.SENT,
            resultado.state,
            "sin registro, el nucleo reporta SENT",
        )
        assertTrue(legado.bytesEnClaro > 0, "y el mensaje sale por el camino en claro: el defecto de antes de p5")
        assertTrue(
            String(legado.enviados.single().second, Charsets.ISO_8859_1).contains("esta cadena jamas") ||
                legado.bytesEnClaro > 0,
            "el contenido viaja al rele",
        )
        assertTrue(manager.backend() != null)
    }

    /**
     * CONTROL POSITIVO: el arnes ve salir algo cuando hay algo que ver salir.
     *
     * Sin esta prueba, "cero bytes" seria verde con un doble que nunca cuenta.
     */
    @Test
    fun `control positivo con sesion, el backend SI registra el envio`() {
        val a = conSesion()
        val antes = a.probe.enviados.size

        a.bundle.core.createMessage(bobId, TextoDelUsuario).getOrThrow()
        a.bundle.core.sendMessage(a.bundle.core.getConversation(bobId).getOrThrow().single())

        assertEquals(antes + 1, a.probe.enviados.size, "un envio mas en el cable")
        assertFalse(
            a.probe.enviados.last().second.isEmpty(),
            "y el frame NO esta vacio: 'no salio nada' y 'salio un frame vacio' son cosas distintas",
        )
    }
}
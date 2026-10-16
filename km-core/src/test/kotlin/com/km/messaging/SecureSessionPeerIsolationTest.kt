package com.km.messaging

import com.km.identity.KmTestDevice
import com.km.identity.KmTestIdentityRoot
import com.km.node.FakeTransportBackend
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.node.PeerTransportState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tarea 5.1 — AISLAMIENTO POR PAR, y lo que cuesta no hacerlo.
 *
 * Una sola identidad local, DOS pares, y UN `PeerTransportManager`. Es el
 * escenario real de una aplicacion con mas de un contacto, y es donde el
 * `IdentityId` que entrega el manager deja de ser decorativo.
 *
 * ## POR QUE NO ES UNA OPTIMIZACION
 *
 * `PeerTransportManager` multiplexa: `sendData` notifica a TODOS los handlers
 * registrados con la identidad del par de destino. Sin filtrar, el frame de
 * Carol entra en la sesion de Bob, y lo que el ratchet hace con un frame que
 * no es suyo no es "ignorar un mensaje": `previewReceive` deriva contra una DH
 * que no conocia y toca el estado. El caso grave no es un mensaje perdido: es
 * el estado criptografico de dos pares contaminado.
 *
 * ## LO QUE SE AFIRMA, Y LO QUE NO
 *
 * Se afirma que el frame ajeno NO entra en la sesion ajena y que el otro par no
 * entrega plaintext. No se afirma nada sobre la interna del ratchet: ese es un
 * checkpoint cerrado y aqui no se modifica.
 */
class SecureSessionPeerIsolationTest {

    private val root = KmTestIdentityRoot()
    private val alice = KmTestDevice("alice")
    private val bob = KmTestDevice("bob")
    private val carol = KmTestDevice("carol")

    /** Los OPK se emiten ANTES de construir los bundles que los publican. */
    private val bobOpk = bob.issueOneTimePreKey(1000L)
    private val carolOpk = carol.issueOneTimePreKey(2000L)

    /** Un solo manager para las dos sesiones, como en produccion. */
    private val manager = PeerTransportManager(FakeTransportBackend(), now = { 0L })
    private val establishment = SecureWorld.establishment()

    private val aliceToBob = SecureWorld.contextTowards(alice, bob, listOf(1000L), root)
    private val aliceToCarol = SecureWorld.contextTowards(alice, carol, listOf(2000L), root)

    /** Sesion de Alice con Bob, sobre el manager compartido. */
    private val conBob: SecureMessagingSession = run {
        val iniciada = establishment.initiate(
            localIdentity = alice.identityId,
            peerContext = aliceToBob,
            localKeys = LocalInitiatorKeys(alice.deviceId, alice.agreementKey),
            transport = manager,
        )
        establishment.accept(
            localIdentity = bob.identityId,
            peerContext = SecureWorld.contextTowards(bob, alice, emptyList(), root),
            localKeys = LocalResponderKeys(
                deviceId = bob.deviceId,
                agreementKey = bob.agreementKey,
                signedPreKey = bob.signedPreKey,
                oneTimePreKey = bobOpk,
                oneTimePreKeyId = 1000L,
            ),
            prekeyFrameWire = iniciada.prekeyFrame,
            transport = manager,
        )
        connect(iniciada.session)
        iniciada.session
    }

    /** Sesion de Alice con Carol, sobre el MISMO manager. */
    private val conCarol: SecureMessagingSession = run {
        val iniciada = establishment.initiate(
            localIdentity = alice.identityId,
            peerContext = aliceToCarol,
            localKeys = LocalInitiatorKeys(alice.deviceId, alice.agreementKey),
            transport = manager,
        )
        establishment.accept(
            localIdentity = carol.identityId,
            peerContext = SecureWorld.contextTowards(carol, alice, emptyList(), root),
            localKeys = LocalResponderKeys(
                deviceId = carol.deviceId,
                agreementKey = carol.agreementKey,
                signedPreKey = carol.signedPreKey,
                oneTimePreKey = carolOpk,
                oneTimePreKeyId = 2000L,
            ),
            prekeyFrameWire = iniciada.prekeyFrame,
            transport = manager,
        )
        connect(iniciada.session)
        iniciada.session
    }

    private fun connect(session: SecureMessagingSession) {
        manager.sendOffer(session.localIdentity, session.peerContext, "sdp-offer", session.remotePeerId).getOrThrow()
        manager.receiveAnswer(session.localIdentity, session.remotePeerId, "sdp-answer").getOrThrow()
        manager.markConnected(session.localIdentity, session.peerContext).getOrThrow()
    }

    @Test
    fun `el wire de un par no entra en la sesion del otro`() {
        // Ambas sesiones viven en el mismo manager, que es el escenario.
        assertEquals(bob.identityId, conBob.remotePeerId)
        assertEquals(carol.identityId, conCarol.remotePeerId)
        assertEquals(PeerTransportState.CONNECTED, conBob.bindingState())
        assertEquals(PeerTransportState.CONNECTED, conCarol.bindingState())

        val deBob = mutableListOf<ByteArray>()
        val deCarol = mutableListOf<ByteArray>()
        conBob.onPlaintext { deBob.add(it) }
        conCarol.onPlaintext { deCarol.add(it) }

        val pendienteAntesDeBob = conBob.pendingInboundCount()
        val pendienteAntesDeCarol = conCarol.pendingInboundCount()

        // El wire de Carol sale por el manager, que lo entrega a TODOS.
        val texto = "esto va solo a carol, no a bob 6633".toByteArray()
        assertTrue(conCarol.send(texto) is SendResult.Ok, "la sesion de Carol envia")

        assertTrue(deBob.isEmpty(), "el handler de plaintext de Bob no se dispara")
        assertEquals(
            pendienteAntesDeBob,
            conBob.pendingInboundCount(),
            "y el frame ajeno ni siquiera entra en la cola de la sesion de Bob",
        )
        assertEquals(
            pendienteAntesDeCarol + 1,
            conCarol.pendingInboundCount(),
            "el frame propio si entra en la cola de su sesion",
        )
    }

    @Test
    fun `varios frames del otro par tampoco tocan esta sesion`() {
        // Un solo frame podria ser casualidad del guion. Con tres, de tamanos
        // distintos y contenido no constante, la afirmacion es sobre el
        // COMPORTAMIENTO y no sobre un caso.
        val deBob = mutableListOf<ByteArray>()
        conBob.onPlaintext { deBob.add(it) }
        val pendientesAntes = conBob.pendingInboundCount()
        val wireEsperado = mutableListOf<ByteArray>()
        manager.onData { _, data -> wireEsperado.add(data) }

        val cargas = listOf(
            "uno".toByteArray(),
            "dos-con-mas-longitud-para-que-el-tamano-no-sea-el-mismo-1122".toByteArray(),
            byteArrayOf(7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7),
        )
        cargas.forEach {
            assertTrue(conCarol.send(it) is SendResult.Ok, "Carol envia")
        }
        assertEquals(3, wireEsperado.size, "tres frames en el cable")

        assertTrue(deBob.isEmpty(), "la sesion del otro par no entrega plaintext")
        assertEquals(
            pendientesAntes,
            conBob.pendingInboundCount(),
            "y su cola de entrada sigue intacta: ni uno de los tres frames entro",
        )
    }

    @Test
    fun `el registro ancla una sesion por par`() {
        val registro = SecureSessionRegistry(alice.identityId)
        registro.install(conBob)
        registro.install(conCarol)
        assertEquals(2, registro.size(), "dos pares, dos sesiones")
        assertEquals(conBob, registro.find(bob.identityId))
        assertEquals(conCarol, registro.find(carol.identityId))
    }

    /** `PeerContext` AUTENTICADO: el aislamiento se apoya en el `remotePeerId` real. */
    @Test
    fun `cada sesion toma su remotePeerId de SU PeerContext`() {
        val ctx: PeerContext = conBob.peerContext
        assertEquals(bob.identityId, ctx.remotePeerId)
        assertEquals(carol.identityId, conCarol.peerContext.remotePeerId)
        assertTrue(ctx.remotePeerId != conCarol.peerContext.remotePeerId)
    }
}
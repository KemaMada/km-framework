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

    // UNA RAIZ POR PAR. Con una sola raiz compartida, `forContact` derivaria el
    // MISMO `remotePeerId` para Bob y para Carol, las dos sesiones colgarían
    // del mismo binding y la segunda rebotaria con `BINDING_ALREADY_EXISTS`:
    // ese fallo seria la prueba de que la clave es la raiz.
    private val aliceRoot = KmTestIdentityRoot()
    private val bobRoot = KmTestIdentityRoot()
    private val carolRoot = KmTestIdentityRoot()
    private val alice = KmTestDevice("alice")
    private val bob = KmTestDevice("bob")
    private val carol = KmTestDevice("carol")

    /** Los OPK se emiten ANTES de construir los bundles que los publican. */
    private val bobOpk = bob.issueOneTimePreKey(1000L)
    private val carolOpk = carol.issueOneTimePreKey(2000L)

    /** Un solo manager para las dos sesiones, como en produccion. */
    private val manager = PeerTransportManager(FakeTransportBackend(), now = { 0L })
    private val establishment = SecureWorld.establishment()

    private val aliceToBob = SecureWorld.contextTowards(bob, listOf(1000L), bobRoot)
    private val aliceToCarol = SecureWorld.contextTowards(carol, listOf(2000L), carolRoot)

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
            peerContext = SecureWorld.contextTowards(alice, emptyList(), aliceRoot),
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
            peerContext = SecureWorld.contextTowards(alice, emptyList(), aliceRoot),
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
        assertEquals(bobRoot.identityId, conBob.remotePeerId, "el par remoto se indexa por RAIZ")
        assertEquals(carolRoot.identityId, conCarol.remotePeerId, "y por raiz distinta son claves distintas")
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
        assertEquals(conBob, registro.find(bobRoot.identityId))
        assertEquals(conCarol, registro.find(carolRoot.identityId))
    }

    // El aislamiento se apoya en el `remotePeerId` DERIVADO, no en el del dispositivo.
    @Test
    fun `cada sesion toma su remotePeerId de SU PeerContext`() {
        val ctx: PeerContext = conBob.peerContext
        assertEquals(bobRoot.identityId, ctx.remotePeerId)
        assertEquals(carolRoot.identityId, conCarol.peerContext.remotePeerId)
        assertTrue(ctx.remotePeerId != conCarol.peerContext.remotePeerId)
        // Y NO es el id del dispositivo: la clave es la raiz, no la caja.
        assertTrue(ctx.remotePeerId != bob.identityId, "la clave no es el id del dispositivo")
        assertTrue(conCarol.peerContext.remotePeerId != carol.identityId, "tampoco en el otro sentido")
    }
}
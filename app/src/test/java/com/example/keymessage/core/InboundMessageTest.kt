package com.example.keymessage.core

import com.km.identity.ContactBundle
import com.km.messaging.LocalResponderKeys
import com.km.messaging.SecureMessagingSession
import com.km.model.IdentityId
import com.km.model.Message
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.storage.InMemoryDuplicateStore
import com.km.storage.InMemoryMessageStore
import com.km.storage.InMemoryOfflineQueue
import com.example.keymessage.contact.InMemoryContactBook
import com.example.keymessage.identity.IdentityMaterial
import com.example.keymessage.identity.IdentityMaterialFactory
import com.example.keymessage.session.SessionCoordinator
import com.example.keymessage.support.CountingBackend
import com.example.keymessage.support.RecordingTransport
import com.example.keymessage.support.TestWorld
import com.example.keymessage.support.countingManager
import com.example.keymessage.util.toHex
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 2.3 — Un `SecureFrame` que entra por el cable llega al handler con el MISMO
 * `messageId` y el MISMO payload.
 *
 * ## QUE AFIRMA
 *
 * El camino INBOUND completo, con las piezas que la app compone:
 *
 * ```
 * SecureSessionEstablishment.initiate  ->  SecureFrame
 *        core.sendMessage              ->  manager.sendData -> backend (wire)
 *   backend.eventCallbacks.onDataReceived  (el cable del otro lado)
 *        SecureMessagingSession        ->  descifra
 *        SecureInbound                 ->  Message
 *        core.registerMessageHandler   ->  la aplicacion
 * ```
 *
 * El `messageId` y el payload viajan DENTRO del plaintext cifrado, en un
 * `MessageEnvelope`. Por eso el unico sitio donde pueden cambiar es la
 * reconstruccion del sobre: si ahi se fabricase un identificador nuevo —o se
 * releyera el del frame, que no existe— el mensaje llegaria con OTRO
 * `messageId` y el receptor no podria deduplicarlo ni correlacionarlo con lo que
 * salio.
 *
 * ## POR QUE NO SE ENTREGA CON `manager.sendData`
 *
 * Porque `sendData` es el camino de SALIDA: el manager notifica a sus handlers
 * el eco de lo que este proceso acaba de enviar. La entrega entrante de verdad
 * es `TransportBackend.eventCallbacks.onDataReceived`, que es donde un byte remoto
 * entra en un proceso real.
 */
class InboundMessageTest {

    private val coordinator = SessionCoordinator()

    private class Lado(
        val identity: IdentityMaterial,
        val manager: PeerTransportManager,
        val backend: CountingBackend,
        val transport: RecordingTransport,
        val core: com.km.api.KeyMessageCoreImpl,
    )

    private fun lado(nombre: String): Lado {
        val identity = IdentityMaterialFactory().fromSeed(ByteArray(32) { (it * 3 + nombre.length).toByte() })
        val (manager, backend) = countingManager()
        val transport = RecordingTransport(online = true)
        val bundle = CoreFactory().create(
            localIdentity = identity.identityId,
            localPrivateKey = identity.keyPair.privateKey,
            transport = transport,
            manager = manager,
            messageStore = InMemoryMessageStore(),
            offlineQueue = InMemoryOfflineQueue(),
            duplicateStore = InMemoryDuplicateStore(),
            contactBook = InMemoryContactBook(),
        )
        return Lado(identity, manager, backend, transport, bundle.core)
    }

    private fun conectar(sesion: SecureMessagingSession, manager: PeerTransportManager) {
        manager.sendOffer(sesion.localIdentity, sesion.peerContext, "sdp-offer", sesion.remotePeerId).getOrThrow()
        manager.receiveAnswer(sesion.localIdentity, sesion.remotePeerId, "sdp-answer").getOrThrow()
        manager.markConnected(sesion.localIdentity, sesion.peerContext).getOrThrow()
    }

    private val opkId = TestWorld.BOB_ONE_TIME_PREKEY_ID

    @Test
    fun `el SecureFrame entrante llega con el MISMO messageId y el MISMO payload`() {
        val alice = lado("alice")
        val bob = lado("bob")

        val parAlice = TestWorld.deviceWithRoot("alice")
        val parBob = TestWorld.deviceWithRoot("bob")
        val aliceDevice = parAlice.device
        val aliceRoot = parAlice.root
        val bobDevice = parBob.device
        val bobRoot = parBob.root
        bobDevice.issueOneTimePreKey(opkId)

        val aliceSessionIdentity = IdentityId(aliceDevice.deviceId.toHex())
        val bobSessionIdentity = IdentityId(bobDevice.deviceId.toHex())

        // --- A abre sesion hacia el bundle de B ---
        val contextoAB = coordinator.contextoHacia(
            ContactBundle.verified(bobRoot.bundleMapFor(listOf(bobDevice), bobDevice, listOf(opkId))),
        )
        val registroA = com.km.messaging.SecureSessionRegistry(alice.identity.identityId)
        val iniciada = coordinator.iniciar(
            localIdentity = aliceSessionIdentity,
            peerContext = contextoAB,
            localKeys = com.km.messaging.LocalInitiatorKeys(aliceDevice.deviceId, aliceDevice.agreementKey),
            manager = alice.manager,
            registry = registroA,
        )
        conectar(iniciada.session, alice.manager)

        // --- B acepta ---
        val contextoBA = PeerContext.forContact(
            ContactBundle.verified(aliceRoot.bundleMapFor(listOf(aliceDevice), aliceDevice, emptyList())),
        )
        val registroB = com.km.messaging.SecureSessionRegistry(bob.identity.identityId)
        val aceptada = coordinator.aceptar(
            localIdentity = bobSessionIdentity,
            peerContext = contextoBA,
            localKeys = LocalResponderKeys(
                deviceId = bobDevice.deviceId,
                agreementKey = bobDevice.agreementKey,
                signedPreKey = bobDevice.signedPreKey,
                oneTimePreKey = assertNotNull(bobDevice.oneTimePreKey(opkId)),
                oneTimePreKeyId = opkId,
            ),
            prekeyFrameWire = iniciada.prekeyFrame,
            manager = bob.manager,
            registry = registroB,
        )
        conectar(aceptada.session, bob.manager)

        // La sesion de B queda instalada en el REGISTRO que observa su `SecureInbound`.
        registroB.install(aceptada.session)
        // Y la de A, en el suyo: el envio sale por el camino cifrado de km-core.
        registroA.install(iniciada.session)

        // Un nucleo con el registro y el manager COMPARTIDOS, que es como se
        // compone en la app: `CoreFactory` los mete a los dos.
        val coreA = com.km.api.KeyMessageCoreImpl(
            localIdentity = alice.identity.identityId,
            localPrivateKey = alice.identity.keyPair.privateKey,
            transport = alice.transport,
            messageStore = InMemoryMessageStore(),
            offlineQueue = InMemoryOfflineQueue(),
            duplicateStore = InMemoryDuplicateStore(),
            secureSessionRegistry = registroA,
            peerTransportManager = alice.manager,
        )
        val coreB = com.km.api.KeyMessageCoreImpl(
            localIdentity = bob.identity.identityId,
            localPrivateKey = bob.identity.keyPair.privateKey,
            transport = bob.transport,
            messageStore = InMemoryMessageStore(),
            offlineQueue = InMemoryOfflineQueue(),
            duplicateStore = InMemoryDuplicateStore(),
            secureSessionRegistry = registroB,
            peerTransportManager = bob.manager,
        )

        val recibidos = mutableListOf<Message>()
        coreB.registerMessageHandler { recibidos += it }

        // --- A envia un mensaje de contenido NO constante ---
        val carga = "contenido que cruza el cable de la app, longitud propia 8123".toByteArray()
        val creado = coreA.createMessage(bobRoot.identityId, carga).getOrThrow()
        coreA.sendMessage(creado).getOrThrow()

        val wire = assertNotNull(alice.backend.sent.lastOrNull(), "un SecureFrame salio por el backend")
        assertTrue(wire.second.isNotEmpty(), "y no estaba vacio")

        // --- El byte entra por el cable de B, como lo haria un proceso remoto ---
        bob.backend.eventCallbacks.onDataReceived?.invoke(aliceRoot.identityId, wire.second)

        assertEquals(1, recibidos.size, "un unico mensaje entregado")
        val entregado = recibidos.single()
        assertEquals(creado.messageId, entregado.messageId, "el MISMO messageId que salio, no uno reconstruido")
        assertContentEquals(carga, entregado.payload, "y el MISMO payload")
        // `from` es la identidad que el TRANSPORTE notifico —la de quien firmo el
        // bundle del emisor—, no la que el emisor se dio a si mismo.
        assertEquals(aliceRoot.identityId.value, entregado.from.value, "el emisor es la raiz que el transporte notifico")
        assertEquals(bob.identity.identityId.value, entregado.to.value, "y el receptor, la identidad local del nucleo de B")

        val puente = assertNotNull(coreB.secureInbound, "el puente de recepcion existe")
        assertEquals(1, puente.delivered.get(), "exactamente una entrega: el puente no descifra dos veces")
        assertEquals(0, puente.rejectedFrames.get(), "y ninguna voz de rechazo")
        assertEquals(0, puente.unknownPeerFrames.get(), "ni de un par sin sesion")
    }
}
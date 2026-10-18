package com.km.transport

import com.km.api.KeyMessageCoreImpl
import com.km.codec.JsonDecoder
import com.km.codec.JsonEncoder
import com.km.codec.ValidatorImpl
import com.km.frame.BinarySecureFrameCodec
import com.km.frame.FrameType
import com.km.frame.SecureFrameSpec
import com.km.identity.KmIds
import com.km.messaging.LocalInitiatorKeys
import com.km.messaging.LocalResponderKeys
import com.km.messaging.SecureSessionEstablishment
import com.km.messaging.SecureSessionRegistry
import com.km.model.IdentityId
import com.km.model.Message
import com.km.node.PeerTransportManager
import com.km.storage.InMemoryMessageStore
import com.km.storage.InMemoryOfflineQueue
import com.km.transport.SocketSecureWorld.Device
import com.km.transport.SocketSecureWorld.IdentityRoot
import com.km.transport.SocketSecureWorld.awaitOrFail
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList

/**
 * E2E — el criterio de aceptacion de la TAREA 3.5: dos NUCLEOS cifrados hablando
 * por un SOCKET REAL.
 *
 * ## QUE ES ESTE TEST Y QUE NO ES NINGUNO DE LOS OTROS
 *
 * - `RB-01` (`RelayBackendFaceTest`) prueba que un byte opaco sale y vuelve por
 *   el rele real. No mira criptografia: `sendData` podria reescribir el byte y
 *   seguira verde.
 * - `R3-13` (`RealRelayTransportTest`) prueba el camino cifrado, pero el rele es
 *   el doble de PROCESO (`RealRelayTransport` contra un `RelayServer` en el
 *   mismo JVM). No hay socket, no hay base64, no hay envelope, no hay
 *   `PEER_ONLINE`.
 * - Este test es el de la CADENA COMPLETA, y lo completo significa las tres
 *   cosas a la vez:
 *
 *   1. `X3DH` de verdad: `SecureSessionEstablishment.initiate`/`accept` sobre un
 *      `VerifiedContactBundle` firmado por una raiz real. No hay `SecureFrame`
 *      fabricado a mano en ninguna parte de este fichero.
 *   2. `SecureFrame` v1 de verdad: lo que sale lo produce el ratchet y lo
 *      comprueba el codec, con su header de 44 B.
 *   3. SOCKET de verdad: `RelayWebSocketServer` de `km-daemon` en un puerto
 *      libre, `ws://127.0.0.1:$port/km`, y los dos motores haciendo el handshake
 *      `AUTH` completo.
 *
 * ## LA COSTE RAIZ/RELE, DECLARADA Y NO OCULTADA
 *
 * El `from` que el receptor ve es la identidad que el RELÉ autentico, y esa
 * identidad es la del motor. La sesion, en cambio, filtra por
 * `remotePeerId`, que `PeerContext.forContact` deriva de la RAIZ del contacto.
 * Si las dos no coinciden, `SecureMessagingSession` filtra el frame entero y
 * todo acaba en `unknownPeerFrames` — es la costura cross-fase que el design
 * declara (D-G) y que "que identidad se autentica en el rele" sigue siendo
 * decision de p3.
 *
 * Para que este test pueda observar `delivered == 1`, la arnes hace que ambas
 * sean la MISMA: el motor se autentica con el par de claves de la RAIZ. Es una
 * decision de la FIXTURE, no una propiedad del framework, y por eso esta escrita
 * aqui con su motivo. Lo que este test demuestra es la CADENA —cifrado, cable,
 * socket, descifrado, entrega—; NO demuestra que la costura raiz/rele este
 * resuelta, porque el framework no la resuelve todavia.
 *
 * ## LO QUE NO SE DEMUESTRA
 *
 * Que el mensaje se vea en una PANTALLA, y que Ktor CIO funcione en Android.
 * Ninguna de las dos cosas es de este modulo.
 */
class RelaySocketSecureE2ETest {

    private val trace = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private lateinit var relay: LocalRelay
    private val open = CopyOnWriteArrayList<JvmRelayTransport>()
    private val cores = CopyOnWriteArrayList<KeyMessageCoreImpl>()

    @BeforeEach
    fun startRelay() {
        relay = LocalRelay.start("e2e")
    }

    @AfterEach
    fun tearDown() {
        cores.forEach { runCatching { it.stop() } }
        cores.clear()
        open.forEach { runCatching { it.close() } }
        open.clear()
        relay.shutdown()
    }

    // ------------------------------------------------------------------
    // Un extremo: motor real + manager real + nucleo real con registro
    // ------------------------------------------------------------------

    /**
     * Un extremo completo.
     *
     * @param device el dispositivo, para el material X3DH.
     * @param root la raiz. Su par de claves es TAMBIEN el que el motor anuncia
     *   al rele, y su clave privada la que firma los `ACK` del nucleo. Ver la
     *   nota de clase sobre por que.
     */
    private class Party(
        val device: Device,
        val root: IdentityRoot,
        val engine: JvmRelayTransport,
        val manager: PeerTransportManager,
        val registry: SecureSessionRegistry,
        val core: KeyMessageCoreImpl,
    )

    private fun party(name: String, peerRootOf: (IdentityId) -> ByteArray?): Party {
        val device = Device(name)
        val root = IdentityRoot()
        val engine = JvmRelayTransport(
            identity = root.identityId,
            ed25519PublicKey = root.keyPair.publicKey,
            ed25519PrivateKey = root.keyPair.privateKey,
            endpoint = relay.endpoint(),
            relayIdentity = relay.relayIdentityId,
            ed25519 = SocketSecureWorld.ed25519,
            handshakeTimeoutMs = 20_000,
            log = { message -> trace.add("$name: $message") },
        )
        open.add(engine)
        val manager = PeerTransportManager(engine)
        val registry = SecureSessionRegistry(root.identityId)
        val core = KeyMessageCoreImpl(
            localIdentity = root.identityId,
            localPrivateKey = root.keyPair.privateKey,
            transport = engine,
            ed25519 = SocketSecureWorld.ed25519,
            messageStore = InMemoryMessageStore(),
            offlineQueue = InMemoryOfflineQueue(),
            encoder = JsonEncoder(),
            decoder = JsonDecoder(),
            validator = ValidatorImpl(),
            // El `ACK` lo firma `ReceiverImpl` con `localPrivateKey` —la de la
            // RAIZ— y `AckManagerImpl` lo verifica contra la clave que este
            // resolver devuelve para `ack.from`, que tambien es la raiz. Sin
            // esto el `ACK` llega y no se procesa, y el cierre del ciclo de
            // entrega no seria observable por nadie.
            peerPublicKeyResolver = peerRootOf,
            secureSessionRegistry = registry,
            peerTransportManager = manager,
        )
        cores.add(core)
        return Party(device, root, engine, manager, registry, core)
    }

    private fun JvmRelayTransport.connectAndAwait() {
        connect().getOrThrow()
        awaitOrFail("el motor debe quedar ONLINE", trace = { trace.toString() }) { isOnline() }
    }

    // ==================================================================
    // E2E-01 — la cadena completa, por socket de verdad
    // ==================================================================

    @Test
    @Timeout(300)
    fun `E2E-01 dos nucleos cifrados se entregan el plaintext por un socket real`() {
        // El receptor primero: si no, el rele no tiene a quien rutear el `MESSAGE`.
        val bob = party("bob") { null }
        val alice = party("alice") { id ->
            if (id == bob.root.identityId) bob.root.keyPair.publicKey else null
        }
        bob.engine.connectAndAwait()
        alice.engine.connectAndAwait()

        // ------------------------------------------------------------------
        // 1. Sesion E2E de verdad: X3DH sobre el bundle VERIFICADO de cada raiz.
        // ------------------------------------------------------------------
        bob.device.issueOneTimePreKey(SocketSecureWorld.BOB_ONE_TIME_PREKEY_ID)
        val aliceToBob = SocketSecureWorld.contextTowards(
            subject = bob.device,
            oneTimePreKeyIds = listOf(SocketSecureWorld.BOB_ONE_TIME_PREKEY_ID),
            root = bob.root,
        )
        val bobToAlice = SocketSecureWorld.contextTowards(
            subject = alice.device,
            oneTimePreKeyIds = emptyList(),
            root = alice.root,
        )

        val establishment = SecureSessionEstablishment(
            SocketSecureWorld.x25519,
            SocketSecureWorld.kdf,
            SocketSecureWorld.protector,
        )
        // El `localIdentity` del establecimiento es la IDENTIDAD DE RELE de cada
        // motor, no la del dispositivo: es la mitad LOCAL de la clave del binding
        // (`localIdentity to remotePeerId`) y tiene que coincidir con la que
        // usa `RelayPeerChannelDriver` para abrir ese binding. Aqui las tres
        // capas —nucleo, sesion y motor— hablan con la MISMA raiz.
        val initiated = establishment.initiate(
            localIdentity = alice.root.identityId,
            peerContext = aliceToBob,
            localKeys = LocalInitiatorKeys(alice.device.deviceId, alice.device.agreementKey),
            transport = alice.manager,
        )
        val accepted = establishment.accept(
            localIdentity = bob.root.identityId,
            peerContext = bobToAlice,
            localKeys = LocalResponderKeys(
                deviceId = bob.device.deviceId,
                agreementKey = bob.device.agreementKey,
                signedPreKey = bob.device.signedPreKey,
                oneTimePreKey = requireNotNull(
                    bob.device.oneTimePreKey(SocketSecureWorld.BOB_ONE_TIME_PREKEY_ID),
                ),
                oneTimePreKeyId = SocketSecureWorld.BOB_ONE_TIME_PREKEY_ID,
            ),
            prekeyFrameWire = initiated.prekeyFrame,
            transport = bob.manager,
        )

        // ------------------------------------------------------------------
        // 2. El enlace lo abre el CONDUCTOR, no el test.
        // ------------------------------------------------------------------
        // `RelayPeerChannelDriver` es quien conduce el binding a `CONNECTED`:
        // `markConnected` exige haber pasado por `OFFER_SENT`/`ANSWER_RECEIVED`
        // y el rele no negocia nada. Hacerlo a mano aqui seria probar el camino
        // del ultimo, no el del framework, y dejaria la pieza que p3 tiene que
        // montar sin una sola prueba.
        val aliceStack = RelayClientStack.compose(alice.engine, alice.manager)
        val bobStack = RelayClientStack.compose(bob.engine, bob.manager)

        val aliceDriver = aliceStack.driverFor(aliceToBob)
        val bobDriver = bobStack.driverFor(bobToAlice)
        // Suscripcion real al evento del motor, ademas de la llamada explicita
        // de abajo: si el `PEER_ONLINE` llegase despues, el conductor tambien
        // debe poder conectarse solo.
        alice.engine.onPeerOnline { aliceDriver.onPeerOnline(it) }
        bob.engine.onPeerOnline { bobDriver.onPeerOnline(it) }

        awaitOrFail("el rele debe anunciar a los dos pares", trace = { trace.toString() }) {
            alice.engine.isPeerPresent(bob.root.identityId) &&
                bob.engine.isPeerPresent(alice.root.identityId)
        }
        aliceDriver.onBindingCreated(aliceToBob.remotePeerId)
        aliceDriver.onPeerPresent(aliceToBob.remotePeerId)
        bobDriver.onBindingCreated(bobToAlice.remotePeerId)
        bobDriver.onPeerPresent(bobToAlice.remotePeerId)

        awaitOrFail("los dos enlaces deben alcanzar CONNECTED", trace = { trace.toString() }) {
            aliceDriver.isConnected(aliceToBob.remotePeerId) && bobDriver.isConnected(bobToAlice.remotePeerId)
        }

        // ------------------------------------------------------------------
        // 3. Las sesiones se instalan DESPUES de que exista el puente de
        //    recepcion: `SecureInbound.attach` se suscribe por sesion instalada,
        //    y ese es el mecanismo que garantiza el orden de los handlers.
        // ------------------------------------------------------------------
        alice.registry.install(initiated.session)
        bob.registry.install(accepted.session)

        // ------------------------------------------------------------------
        // 4. El byte que baja por el socket REAL, observado en el manager del
        //    receptor con el `from` que el rele autentico. Esto no lo descifra
        //    nadie: lo mira el codec para comprobar que lo que viaja por el cable
        //    es un `SecureFrame` v1 con su header de 44 B.
        //
        //    Se captura la TUPLA y no solo el byte a proposito. El `from` es la
        //    costura mas fragile de la cadena —la mutacion M1-b lo sustituye
        //    por la identidad local y el byte sigue llegando—, y sin una
        //    asercion que mire el identificador, ese fallo se manifestaria como
        //    un "el mensaje no llego" sin distinguirlo de "no se envio nada"
        //    (mutacion M1-a). Los dos fallos son distintos y tienen que ser
        //    observables por separado.
        // ------------------------------------------------------------------
        val wireEnBob = CopyOnWriteArrayList<Pair<IdentityId, ByteArray>>()
        bob.manager.onData { from, data -> wireEnBob.add(from to data) }

        val recibidoEnBob = CopyOnWriteArrayList<Message>()
        val recibidoEnAlice = CopyOnWriteArrayList<Message>()
        bob.core.registerMessageHandler { recibidoEnBob.add(it) }
        alice.core.registerMessageHandler { recibidoEnAlice.add(it) }

        // Contenido NO constante y de tamano DISTINTO al de cualquier otro test:
        // una comparacion que solo mirara la longitud pasaria con un payload
        // truncado o con el sobre de otro mensaje.
        val contenido = ByteArray(311) { ((it * 37 + 0x5B) and 0xFF).toByte() }

        val creado = alice.core.createMessage(aliceToBob.remotePeerId, contenido).getOrThrow()
        alice.core.sendMessage(creado).getOrThrow()

        // 4.a El byte sale de verdad. Si aqui no llega nada, el problema es de
        //     SALIDA y cualquier asercion posterior seria ruido.
        awaitOrFail("el byte debe bajar por el socket al manager del receptor", trace = { trace.toString() }) {
            wireEnBob.isNotEmpty()
        }
        assertEquals(1, wireEnBob.size, "el receptor debe ver el wire exactamente una vez")

        // 4.b Y llega CON LA IDENTIDAD DEL EMISOR, que es la unica que el rele
        //     autentico. Con `from` falseado a la identidad local (mutacion
        //     M1-b-bis) el byte llegaria igual y solo aqui se veria.
        assertEquals(
            alice.root.identityId,
            wireEnBob.single().first,
            "el `from` es el emisor autenticado por el rele, no la identidad local del receptor",
        )

        // 4.c Y ahora si, el mensaje entregado.
        awaitOrFail("Bob debe recibir el mensaje descifrado", trace = { trace.toString() }) {
            recibidoEnBob.size == 1
        }

        // ------------------------------------------------------------------
        // 5. Lo entregado es el mensaje, con SU identificador.
        // ------------------------------------------------------------------
        val entregado = recibidoEnBob.single()
        assertEquals(creado.messageId, entregado.messageId, "el messageId debe sobrevivir al cifrado y al socket")
        assertEquals(alice.root.identityId, entregado.from, "el `from` es el emisor autenticado por el rele")
        assertEquals(bob.root.identityId, entregado.to, "el `to` es la identidad local del receptor")
        assertArrayEquals(contenido, entregado.payload, "el plaintext llega intacto")

        val puenteBob = requireNotNull(bob.core.secureInbound)
        assertEquals(1, puenteBob.delivered.get(), "una entrega, una sola vez")
        assertEquals(0, puenteBob.rejectedFrames.get(), "nada rechazado en el receptor")
        assertEquals(0, puenteBob.unknownPeerFrames.get(), "el par es conocido: no hay frames huerfanos")

        val puenteAlice = requireNotNull(alice.core.secureInbound)
        assertEquals(0, puenteAlice.delivered.get(), "el emisor no entrega su propio eco")
        assertTrue(
            puenteAlice.rejectedFrames.get() >= 1,
            "el eco local del emisor queda CONTADO como rechazo, no tragado (medido, M5)",
        )

        // ------------------------------------------------------------------
        // 6. El byte del cable es un `SecureFrame` v1 con el header de 44 B.
        // ------------------------------------------------------------------
        assertEquals(44, SecureFrameSpec.HEADER_LENGTH, "header v1 congelado: 44 bytes")
        val frame = BinarySecureFrameCodec.decode(wireEnBob.single().second)
        assertEquals(FrameType.MESSAGE, frame.type, "un mensaje de sesion, no un PREKEY de establecimiento")
        assertTrue(
            wireEnBob.single().second.size > SecureFrameSpec.HEADER_LENGTH,
            "el wire es header + ciphertext, no solo header: ${wireEnBob.single().second.size} B",
        )

        // ------------------------------------------------------------------
        // 7. Y el emisor NO recibe de vuelta lo que envio.
        // ------------------------------------------------------------------
        // El rele es un fan-out a `envelope.to`, no un hub: si devolviera el
        // `MESSAGE` al emisor, la aplicacion presentaria el mensaje dos veces.
        Thread.sleep(500)
        assertEquals(
            0,
            recibidoEnAlice.size,
            "el emisor no recibe su propio mensaje por el camino cifrado",
        )
        assertEquals(1, recibidoEnBob.size, "el receptor recibe UNA vez, no una por entrega")
    }
}

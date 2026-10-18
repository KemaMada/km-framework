package com.km.transport

import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.Kdf
import com.km.crypto.X25519
import com.km.crypto.X25519KeyPair
import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.crypto.provider.BcHkdfSha256
import com.km.crypto.provider.BcX25519
import com.km.daemon.config.DaemonConfig
import com.km.daemon.server.AuthAdapter
import com.km.daemon.server.JsonWireCodec
import com.km.daemon.server.MessageRouter
import com.km.daemon.server.RelayWebSocketServer
import com.km.frame.SecureFrameProtector
import com.km.identity.ContactBundle
import com.km.identity.DeviceRoster
import com.km.identity.KmIds
import com.km.model.IdentityId
import com.km.model.RelayLimits
import com.km.node.PeerContext
import com.km.node.RelayServer
import com.km.node.RelayServiceImpl
import com.km.storage.InMemoryRelayStore
import com.km.transport.SocketSecureWorld.awaitOrFail
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Arnes compartido por los dos tests que necesitan un RELE DE VERDAD por socket:
 * [RelayPeerChannelDriverTest] (tarea 3.3) y [RelaySocketSecureE2ETest]
 * (tarea 3.5).
 *
 * ## POR QUE EXISTE Y POR QUE NO ES `SecureWorld`
 *
 * `km-core/src/test/.../SecureWorld.kt` es la fixture de dos partes con
 * `FakeTransportBackend`, y `FakeTransportBackend` es exactamente lo que estos
 * dos tests NO pueden usar: `RelayPeerChannelDriver.intentarConectar` consulta
 * `JvmRelayTransport.isPeerPresent`, que exige `ConnectionState.ONLINE` —que
 * solo alcanza un `AUTH_OK`— Y un `PEER_ONLINE` que solo emite el rele. Sin un
 * socket real, el conductor no tiene nada que observar y el test probaria un
 * doble.
 *
 * Y el SO de `SecureWorld` no esta disponible: `KmTestDevice` y
 * `KmTestIdentityRoot` viven en el source set de TEST de km-core, y km-transport
 * consume km-core POR ARTEFACTO (`api("com.km:km-core:0.1.0-SNAPSHOT")`), que no
 * arrastra los tests. Copiarlos aqui es lo que deja este arnes con las mismas
 * primitivas del framework —`DeviceRoster`, `ContactBundle`, `Ed25519Impl`,
 * `BcX25519`, todo firmado de verdad— y sin fabricar ni un byte a mano.
 *
 * ## LO QUE ESTE ARNES NO HACE
 *
 * No decide a quien se autentica el motor, ni inventa identidad, ni relaja
 * ninguna comprobacion. Solo arranca un rele real en un puerto libre y expone las
 * cuatro piezas que un cliente necesita.
 */
object SocketSecureWorld {

    val ed25519: Ed25519 = Ed25519Impl()
    val x25519: X25519 = BcX25519()
    val kdf: Kdf = BcHkdfSha256()
    val protector: SecureFrameProtector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)

    /** `keyId` del SPK de todos los dispositivos de esta arnes. */
    const val SIGNED_PREKEY_ID: Long = 42L

    /** `keyId` del OPK que emite Bob para el establecimiento de la sesion. */
    const val BOB_ONE_TIME_PREKEY_ID: Long = 1000L

    /**
     * Espera a que [predicate] sea cierto y, si no lo es en [timeoutMs],
     * FALLA con un mensaje que dice QUE se esperaba y cual era la traza.
     *
     * Falla con `AssertionError`, no con un timeout de JUnit: un timeout mata el
     * test entero sin decir nada de este mundo, que es justo la confusion entre
     * "no llego" y "llego y se rechazo" que este proyecto ya ha pagado una vez.
     */
    fun awaitOrFail(what: String, timeoutMs: Long = 20_000, trace: () -> String = { "" }, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
        }
        throw AssertionError("llego el limite ($timeoutMs ms) esperando: $what. traza: ${trace()}")
    }

    /** Identidad derivada de una clave publica de Ed25519, como la deriva el rele. */
    fun identityOf(publicKey: ByteArray): IdentityId =
        IdentityId(KmIds.identityId(publicKey).joinToString("") { "%02x".format(it) })

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * Un dispositivo real: clave de FIRMA (`Ed25519`), clave de ACUERDO
     * (`X25519`), SPK firmado y OPK a demanda.
     *
     * La separacion entre la clave de firma y la de acuerdo es normativa
     * (KM-ID-0001) y la sostiene el TIPO, no una convencion: X3DH rechaza una
     * clave de firma donde espera una de acuerdo.
     */
    class Device(val name: String, signedPreKeyId: Long = SIGNED_PREKEY_ID) {
        val signingKeyPair = ed25519.generateKeyPair()

        /** `deviceId` DERIVADO de la clave de firma, como exige KM-ID-0001. */
        val deviceId: ByteArray = KmIds.deviceId(signingKeyPair.publicKey)

        /** La `IK` de X3DH: clave de ACUERDO, jamas la de firma. */
        val agreementKey: X25519KeyPair = x25519.generateKeyPair()

        /** El `SPK`, que el bundle publica firmado por la clave de FIRMA. */
        val signedPreKey: X25519KeyPair = x25519.generateKeyPair()

        val signedPreKeyId: Long = signedPreKeyId

        /** Identidad de DISPOSITIVO: la que deriva de la clave de firma. */
        val identityId: IdentityId = IdentityId(KmIds.identityId(signingKeyPair.publicKey).toHex())

        private val oneTimePreKeys = LinkedHashMap<Long, X25519KeyPair>()

        /** Emite (o devuelve) el OPK [keyId]. */
        fun issueOneTimePreKey(keyId: Long): X25519KeyPair =
            oneTimePreKeys.getOrPut(keyId) { x25519.generateKeyPair() }

        /** El OPK [keyId], o `null` si este dispositivo no lo ha emitido. */
        fun oneTimePreKey(keyId: Long): X25519KeyPair? = oneTimePreKeys[keyId]
    }

    /**
     * Raiz de identidad que FIRMA los bundles que publica.
     *
     * `PeerContext.forContact` deriva el `remotePeerId` de `KmIds.identityId` de
     * esta raiz, y el `identityId` que el motor anuncia al rele lo deriva de su
     * clave de FIRMA. Que ambos coincidan —raiz del contacto = identidad de
     * rele— es una DECISION de esta arnes, no una propiedad del framework, y
     * esta escrita en el KDoc de [RelaySocketSecureE2ETest] con su motivo.
     */
    class IdentityRoot {
        val keyPair = ed25519.generateKeyPair()

        val identityId: IdentityId = IdentityId(KmIds.identityId(keyPair.publicKey).toHex())

        /**
         * Bundle CONTACT firmado y publicado para [subject].
         *
         * `@param oneTimePreKeyIds` los `keyId` de los OPK que el bundle ofrece.
         * Las claves privadas se quedan en el dispositivo: un bundle solo lleva
         * las publicas, igual que un QR real.
         */
        fun bundleFor(
            devices: List<Device>,
            subject: Device,
            oneTimePreKeyIds: List<Long>,
        ): Map<String, Any?> {
            val roster = DeviceRoster.sign(
                DeviceRoster.build(
                    identityRootPublicKey = keyPair.publicKey,
                    devices = devices.map {
                        DeviceRoster.makeDeviceEntry(
                            signingPublicKey = it.signingKeyPair.publicKey,
                            agreementPublicKey = it.agreementKey.publicKey,
                            name = it.name,
                        )
                    },
                    sequence = 1,
                ),
                keyPair.privateKey,
            )

            val signedPrekey = ContactBundle.signSignedPrekey(
                ContactBundle.buildSignedPrekey(
                    deviceSigningPublicKey = subject.signingKeyPair.publicKey,
                    publicKey = subject.signedPreKey.publicKey,
                    keyId = subject.signedPreKeyId,
                    deviceIdBytes = subject.deviceId,
                ),
                subject.signingKeyPair.privateKey,
            )

            val oneTimePrekeys = oneTimePreKeyIds.map { keyId ->
                ContactBundle.buildOnetimePrekey(
                    publicKey = requireNotNull(subject.oneTimePreKey(keyId)) {
                        "el dispositivo ${subject.name} no ha emitido el OPK $keyId"
                    }.publicKey,
                    keyId = keyId,
                    deviceIdBytes = subject.deviceId,
                )
            }

            return ContactBundle.signBundle(
                ContactBundle.buildBundle(
                    identityRootPublicKey = keyPair.publicKey,
                    signedRoster = roster,
                    subjectDeviceId = subject.deviceId,
                    signedPrekey = signedPrekey,
                    oneTimePrekeys = oneTimePrekeys,
                ),
                keyPair.privateKey,
            )
        }
    }

    /**
     * `PeerContext` de CONTACTO de este proceso hacia [subject], con su bundle
     * VERIFICADO.
     *
     * No es una copia de `SecureWorld.contextTowards` con otro nombre: es la
     * MISMA politica —la autoridad es el bundle verificado y el `remotePeerId`
     * lo deriva la fabrica—, pero construida aqui porque el original no es
     * visible desde este modulo.
     */
    fun contextTowards(subject: Device, oneTimePreKeyIds: List<Long>, root: IdentityRoot): PeerContext =
        PeerContext.forContact(
            ContactBundle.verified(root.bundleFor(listOf(subject), subject, oneTimePreKeyIds)),
        )
}

/**
 * Un rele DE VERDAD de `km-daemon` en un puerto libre.
 *
 * Es el mismo montaje que hacen `RelayBackendFaceTest` y `RelayLegacyEchoTest`,
 * aqui para que los dos tests de las tareas 3.3 y 3.5 no repitan veinte lineas
 * de arranque. Lo que cambia no es el rele: es que estos tests lo necesitan
 * para observar PRESENCIA y no solo entrega de bytes.
 */
class LocalRelay private constructor(
    private val server: RelayWebSocketServer,
    val port: Int,
    val relayIdentityId: IdentityId,
) {
    fun endpoint(): String = "ws://127.0.0.1:$port/km"

    fun shutdown() = server.shutdown()

    companion object {
        /**
         * Arranca el rele y ESPERA a que el puerto este escuchando.
         *
         * @throws AssertionError si el rele no arranca o no puede ligar.
         */
        fun start(tag: String): LocalRelay {
            val relayKeyPair = SocketSecureWorld.ed25519.generateKeyPair()
            val relayId = SocketSecureWorld.identityOf(relayKeyPair.publicKey)

            val limits = RelayLimits(maxConnections = 16)
            val relayServer = RelayServer(
                relayKeyPair = relayKeyPair,
                ed25519 = SocketSecureWorld.ed25519,
                relayService = RelayServiceImpl(
                    relayStore = InMemoryRelayStore(
                        maxStoredMessages = limits.maxStoredMessages,
                        maxStorageBytes = limits.maxStorageBytes,
                    ),
                    limits = limits,
                ),
                relayIdentityId = relayId,
            )

            val codec = JsonWireCodec()
            val port = ServerSocket(0).use { it.localPort }
            val config = DaemonConfig(listenPort = port, maxConnections = 16)
            val router = MessageRouter(
                relayServer = relayServer,
                codec = codec,
                authAdapter = AuthAdapter(relayServer, relayId, codec),
                relayIdentityId = relayId,
                config = config,
                logger = LoggerFactory.getLogger("relay-$tag"),
            )

            val started = CountDownLatch(1)
            val failure = AtomicReference<Exception?>(null)
            val server = RelayWebSocketServer(
                config = config,
                router = router,
                codec = codec,
                onStarted = { started.countDown() },
                onFatal = { started.countDown(); failure.set(it) },
            )
            server.start()
            val ok = started.await(20, TimeUnit.SECONDS)
            if (!ok || failure.get() != null) {
                server.shutdown()
                throw AssertionError(
                    "el rele '$tag' no arranco en 20 s: ok=$ok, fallo=${failure.get()?.message}",
                )
            }
            return LocalRelay(server, port, relayId)
        }
    }
}

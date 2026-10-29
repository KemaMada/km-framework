package com.example.keymessage.support

import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.X25519
import com.km.crypto.X25519KeyPair
import com.km.crypto.provider.BcX25519
import com.km.identity.ContactBundle
import com.km.identity.DeviceRoster
import com.km.identity.KceEnvelope
import com.km.identity.KmIds
import com.km.model.IdentityId
import com.km.node.PeerTransportManager
import com.km.node.TransportBackend
import com.km.node.TransportEventCallbacks
import com.km.node.TransportResult
import com.km.protocol.ConnectionState
import com.km.protocol.Transport
import com.example.keymessage.util.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Arnes de pruebas de la app: dispositivos, raices y bundles DE VERDAD.
 *
 * ## POR QUE NO SE REUSA `SecureWorld` DE km-core
 *
 * Porque `KmTestDevice` y `KmTestIdentityRoot` viven en el source set de TEST
 * de km-core, y la app lo consume POR ARTEFACTO: un jar publicado no arrastra
 * los tests de quien lo publico. Copiar la fixture —con las MISMAS primitivas del
 * framework— es lo que deja estos tests con material criptografico real y sin
 * fabricar ni un byte a mano.
 *
 * Lo que NO hace esta arnes es decidir nada: no relaja comprobaciones, no
 * inventa identidades y no sustituye firmas por ceros. Si un bundle verifica aqui,
 * verifica porque la firma de la raiz cuadra.
 */
object TestWorld {

    val ed25519: Ed25519 = Ed25519Impl()
    val x25519: X25519 = BcX25519()

    /** `keyId` del SPK de todos los dispositivos de la arnes. */
    const val SIGNED_PREKEY_ID: Long = 4242L

    /** `keyId` del OPK que emite Bob para el establecimiento. */
    const val BOB_ONE_TIME_PREKEY_ID: Long = 9911L

    /** `identityId` derivada de una clave publica de Ed25519. */
    fun identityOf(publicKey: ByteArray): IdentityId = IdentityId(KmIds.identityId(publicKey).toHex())

    /**
     * Un dispositivo real: clave de FIRMA (Ed25519), clave de ACUERDO (X25519),
     * SPK firmado y OPKs a demanda.
     *
     * La separacion entre clave de firma y clave de acuerdo la sostiene el TIPO
     * de km-core: X3DH rechaza una clave de firma donde espera una de acuerdo.
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

        /** Identidad de la RAIZ a la que pertenece este dispositivo. */
        var root: IdentityRoot? = null

        private val oneTimePreKeys = LinkedHashMap<Long, X25519KeyPair>()

        fun issueOneTimePreKey(keyId: Long): X25519KeyPair =
            oneTimePreKeys.getOrPut(keyId) { x25519.generateKeyPair() }

        fun oneTimePreKey(keyId: Long): X25519KeyPair? = oneTimePreKeys[keyId]
    }

    /** Raiz de identidad que FIRMA los bundles que publica. */
    class IdentityRoot {
        val keyPair = ed25519.generateKeyPair()
        val identityId: IdentityId = IdentityId(KmIds.identityId(keyPair.publicKey).toHex())

        /**
         * Bundle CONTACT firmado, en su forma de MAP.
         *
         * @param oneTimePreKeyIds `keyId` de los OPK que el bundle ofrece. Las
         *   claves privadas se quedan en el dispositivo: un bundle solo lleva
         *   las publicas, igual que un QR real.
         */
        fun bundleMapFor(
            devices: List<Device>,
            subject: Device,
            oneTimePreKeyIds: List<Long>,
        ): Map<String, Any?> {
            val rosterUnsigned = DeviceRoster.build(
                identityRootPublicKey = keyPair.publicKey,
                devices = devices.map {
                    DeviceRoster.makeDeviceEntry(
                        signingPublicKey = it.signingKeyPair.publicKey,
                        agreementPublicKey = it.agreementKey.publicKey,
                        name = it.name,
                    )
                },
                sequence = 1,
            )
            val roster = DeviceRoster.sign(
                com.example.keymessage.contact.BundlePruner.pruneRoster(rosterUnsigned),
                keyPair.privateKey,
            )

            val signedPrekeyUnsigned = ContactBundle.buildSignedPrekey(
                deviceSigningPublicKey = subject.signingKeyPair.publicKey,
                publicKey = subject.signedPreKey.publicKey,
                keyId = subject.signedPreKeyId,
                deviceIdBytes = subject.deviceId,
            )
            val signedPrekey = ContactBundle.signSignedPrekey(
                com.example.keymessage.contact.BundlePruner.pruneSignedPrekey(signedPrekeyUnsigned),
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

            val unsigned = ContactBundle.buildBundle(
                identityRootPublicKey = keyPair.publicKey,
                signedRoster = roster,
                subjectDeviceId = subject.deviceId,
                signedPrekey = signedPrekey,
                oneTimePrekeys = oneTimePrekeys,
            )
            return ContactBundle.signBundle(
                com.example.keymessage.contact.BundlePruner.pruneBundle(unsigned),
                keyPair.privateKey,
            )
        }

        /** El mismo bundle, envuelto como lo travelingia un QR o un `.kmc`. */
        fun bundleTextFor(devices: List<Device>, subject: Device, oneTimePreKeyIds: List<Long>): String {
            val map = bundleMapFor(devices, subject, oneTimePreKeyIds)
            // prune roster before? bundle already pruned in bundleMapFor; re-prune harmless
            val kceBytes = com.km.identity.Kce.encode(map)
            return com.example.keymessage.contact.BundleCompression.compress(kceBytes)
        }
    }

    /** Un dispositivo con la raiz que lo autoriza. */
    class DeviceWithRoot(val device: Device, val root: IdentityRoot)

    /**
     * Dispositivo con su raiz ya asignada.
     *
     * Devuelve un TIPO y no un `Pair`: una destructuracion en la declaracion de
     * una propiedad de clase no es Kotlin, y `val (a, b) = ...` en el cuerpo de
     * una clase de test no compila.
     */
    fun deviceWithRoot(name: String, signedPreKeyId: Long = SIGNED_PREKEY_ID): DeviceWithRoot {
        val root = IdentityRoot()
        val device = Device(name, signedPreKeyId)
        device.root = root
        return DeviceWithRoot(device, root)
    }
}

/**
 * `Transport` de pruebas que CUENTA lo que saldria en claro.
 *
 * ## POR QUE NO `FakeTransportBackend` PARA ESTO
 *
 * `FakeTransportBackend` no lleva cuenta de nada: `sendData` es una excepcion y
 * `send` no existe en el. Para afirmar "CERO bytes salieron", hace falta un
 * transporte que pueda demostrar que no recibio ninguno, y eso es una propiedad
 * del doble, no del framework.
 */
class RecordingTransport(private val online: Boolean = true) : Transport {

    /** Bytes que el camino LEGADO de envio dejaria salir en claro. */
    val cleartextOutbound = mutableListOf<ByteArray>()

    /** Bytes que el camino cifrado deja salir por el backend. */
    val secureOutbound = mutableListOf<ByteArray>()

    /** Handler que el nucleo instala para el camino LEGADO de recepcion. */
    private var legacyMessageHandler: (ByteArray) -> Unit = {}
    private var ackHandlerRegistrado: (ByteArray) -> Unit = {}

    override fun connect(): Result<Unit> = Result.success(Unit)

    override fun disconnect() {
        // No hay nada que desconectar: este transporte no tiene socket.
    }

    override fun send(data: ByteArray, to: IdentityId): Result<Unit> {
        cleartextOutbound += data.copyOf()
        return Result.success(Unit)
    }

    override fun sendAck(ackBytes: ByteArray): Result<Unit> = Result.success(Unit)

    override fun setMessageHandler(handler: (ByteArray) -> Unit) {
        legacyMessageHandler = handler
    }

    override fun setAckHandler(handler: (ByteArray) -> Unit) {
        ackHandlerRegistrado = handler
    }

    override fun isOnline(): Boolean = online

    override fun connectionState(): ConnectionState = if (online) ConnectionState.ONLINE else ConnectionState.DISCONNECTED

    override fun onStateChange(handler: (ConnectionState) -> Unit) = Unit

    override fun onPeerOnline(handler: (IdentityId) -> Unit) = Unit

    override fun onPeerOffline(handler: (IdentityId) -> Unit) = Unit

    /** Entrega al camino LEGADO de recepcion lo que le llegaria en claro. */
    fun deliverLegacyCleartext(bytes: ByteArray) = legacyMessageHandler(bytes)

    /** Bytes de los dos caminos sumados: lo que REALMENTE saldría de la app. */
    fun totalBytesOut(): Int = cleartextOutbound.sumOf { it.size } + secureOutbound.sumOf { it.size }
}

/**
 * Backend que cuenta los bytes del camino cifrado.
 *
 * `TransportBackend` es la mitad de `TransportBackend` que usa `PeerTransportManager`
 * para los `SecureFrame`. Es el unico sitio por el que un byte cifrado puede
 * salir, asi que es el unico sitio donde "cero bytes" es una afirmacion con
 * dientes.
 */
class CountingBackend : TransportBackend {

    override var eventCallbacks: TransportEventCallbacks = TransportEventCallbacks()

    val sent = mutableListOf<Pair<IdentityId, ByteArray>>()

    val totalBytes: Int get() = sent.sumOf { it.second.size }

    override fun initialize(): TransportResult<Unit> = TransportResult.Success(Unit)

    override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        TransportResult.Success(Unit)

    override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        TransportResult.Success(Unit)

    override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String): TransportResult<Unit> =
        TransportResult.Success(Unit)

    override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String): TransportResult<Unit> =
        TransportResult.Success(Unit)

    override fun sendData(remotePeerId: IdentityId, data: ByteArray): TransportResult<Unit> {
        sent += remotePeerId to data.copyOf()
        return TransportResult.Success(Unit)
    }

    override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId): TransportResult<Unit> =
        TransportResult.Success(Unit)

    override fun shutdown(): TransportResult<Unit> = TransportResult.Success(Unit)
}

/** El `PeerTransportManager` de un [CountingBackend], ya enlazado. */
fun countingManager(): Pair<PeerTransportManager, CountingBackend> {
    val backend = CountingBackend()
    return PeerTransportManager(backend, now = { 0L }) to backend
}
/**
 * `MessageDao` en memoria.
 *
 * Las consultas reales de Room exigen el runtime de Android, pero el DAO es una
 * INTERFAZ: lo que la app escribe por el camino de envio —el `messageId`, el
 * estado, el codigo de fallo— se puede observar igual con una implementacion en
 * memoria. Es lo que permite probar `ConversationRepository` sin dispositivo.
 */
class InMemoryMessageDao : com.example.keymessage.storage.room.dao.MessageDao {

    val filas = java.util.LinkedHashMap<String, com.example.keymessage.storage.room.entity.MessageEntity>()

    private val flujos = mutableMapOf<String, kotlinx.coroutines.flow.MutableStateFlow<List<com.example.keymessage.storage.room.entity.MessageEntity>>>()

    private fun flujo(id1: String, id2: String) =
        flujos.getOrPut("$id1|$id2") { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }

    override suspend fun insert(message: com.example.keymessage.storage.room.entity.MessageEntity) {
        filas[message.messageId] = message
        refrescar()
    }

    override suspend fun updateState(messageId: String, state: String, failureCode: String?) {
        filas[messageId]?.let { filas[messageId] = it.copy(state = state, failureCode = failureCode) }
        refrescar()
    }

    override suspend fun get(messageId: String) = filas[messageId]

    private fun de(id1: String, id2: String) = filas.values.filter {
        (it.from == id1 && it.to == id2) || (it.from == id2 && it.to == id1)
    }.sortedWith(compareBy({ it.timestamp }, { it.messageId }))

    override suspend fun listConversation(id1: String, id2: String) = de(id1, id2)

    override fun observeConversation(id1: String, id2: String) =
        flujo(id1, id2).also { it.value = de(id1, id2) }

    override suspend fun delete(messageId: String) {
        filas.remove(messageId)
        refrescar()
    }

    /**
     * F2 — el ultimo mensaje por par, como lo haria la consulta agregada.
     *
     * Se implementa con la MISMA regla que el SQL (mayor `timestamp`, y en el
     * empate el `message_id` mayor) para que el doble no mienta a los tests que
     * lo usan.
     */
    override fun observeUltimos(local: String): kotlinx.coroutines.flow.Flow<List<com.example.keymessage.storage.room.entity.MessageEntity>> =
        kotlinx.coroutines.flow.MutableStateFlow(
            filas.values
                .groupBy { if (it.from == local) it.to else it.from }
                .map { (_, delPar) ->
                    delPar.sortedWith(compareByDescending<com.example.keymessage.storage.room.entity.MessageEntity> { it.timestamp }
                        .thenByDescending { it.messageId })
                        .first()
                },
        )

    private fun refrescar() {
        flujos.forEach { (clave, flow) ->
            val partes = clave.split("|")
            flow.value = de(partes[0], partes[1])
        }
    }
}

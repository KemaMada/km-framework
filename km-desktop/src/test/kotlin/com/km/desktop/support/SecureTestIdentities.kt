package com.km.desktop.support

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

/**
 * El `Transport` de km-core que CUENTA lo que saldria EN CLARO.
 *
 * ## POR QUE NO BASTA CON EL BACKEND
 *
 * Hay dos caminos por los que un byte puede salir de este proceso, y solo uno
 * es el cifrado: `PeerTransportManager` -> `TransportBackend.sendData` lleva los
 * `SecureFrame`, y `Transport.send` lleva el `Message` de km-core SIN cifrar.
 * Una puerta anti-texto-plano que solo mirase el primero dejaria el segundo sin
 * vigilar, y el segundo es exactamente el que se quiere cerrar.
 *
 * ## LO QUE CUENTA Y LO QUE NO
 *
 * Cuenta BYTES de `send`, y no cuenta NADA mas: ni las conexiones, ni los
 * handlers que se registran, ni las llamadas a `sendAck`. `sendAck` va por su
 * propio canal (`Transport.sendAck` existe precisamente para que no comparta
 * camino con `send`), asi que un byte de `ACK` no es una fuga de contenido de
 * mensaje; aun asi se cuenta aparte para que el test pueda distinguirlo.
 */
class recordingTransport(private val online: Boolean = true) : Transport {

    /** Bytes que el camino LEGADO dejaria salir en claro. */
    val enviados: MutableList<Pair<IdentityId, ByteArray>> = mutableListOf()

    /** Bytes de `sendAck`, en un canal distinto y por eso aparte. */
    val acks: MutableList<ByteArray> = mutableListOf()

    /** Bytes de `send`: el total que un rechazo afirma que son cero. */
    val bytesEnClaro: Int get() = enviados.sumOf { it.second.size }

    private var messageHandler: (ByteArray) -> Unit = {}
    private var ackHandler: (ByteArray) -> Unit = {}

    override fun connect(): Result<Unit> = Result.success(Unit)

    override fun disconnect() = Unit

    override fun send(data: ByteArray, to: IdentityId): Result<Unit> {
        enviados += to to data.copyOf()
        return Result.success(Unit)
    }

    override fun sendAck(ackBytes: ByteArray): Result<Unit> {
        acks += ackBytes.copyOf()
        return Result.success(Unit)
    }

    override fun setMessageHandler(handler: (ByteArray) -> Unit) {
        messageHandler = handler
    }

    override fun setAckHandler(handler: (ByteArray) -> Unit) {
        ackHandler = handler
    }

    override fun isOnline(): Boolean = online

    override fun connectionState(): ConnectionState =
        if (online) ConnectionState.ONLINE else ConnectionState.DISCONNECTED

    override fun onStateChange(handler: (ConnectionState) -> Unit) = Unit

    override fun onPeerOnline(handler: (IdentityId) -> Unit) = Unit

    override fun onPeerOffline(handler: (IdentityId) -> Unit) = Unit

    /** Entrega al camino LEGADO lo que le llegaria en claro. */
    fun deliverLegacyCleartext(bytes: ByteArray) = messageHandler(bytes)
}

/**
 * Un backend que no hace NADA y se usa para el `Transport` cuando el test
 * necesita una segunda cara distinta de [relayProbeManager].
 */
class inertBackend : TransportBackend {
    override var eventCallbacks: TransportEventCallbacks = TransportEventCallbacks()
    override fun initialize(): TransportResult<Unit> = TransportResult.Success(Unit)
    override fun onCreateBinding(localIdentity: IdentityId, remotePeerId: IdentityId) =
        TransportResult.Success(Unit)

    override fun onLocalSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) =
        TransportResult.Success(Unit)

    override fun onRemoteSdp(localIdentity: IdentityId, remotePeerId: IdentityId, sdp: String) =
        TransportResult.Success(Unit)

    override fun onIceCandidate(localIdentity: IdentityId, remotePeerId: IdentityId, candidate: String) =
        TransportResult.Success(Unit)

    override fun sendData(remotePeerId: IdentityId, data: ByteArray) = TransportResult.Success(Unit)
    override fun onCloseBinding(localIdentity: IdentityId, remotePeerId: IdentityId) =
        TransportResult.Success(Unit)

    override fun shutdown(): TransportResult<Unit> = TransportResult.Success(Unit)
}

/** El `PeerTransportManager` de un [inertBackend]. */
fun inertManager(): Pair<PeerTransportManager, inertBackend> {
    val backend = inertBackend()
    return PeerTransportManager(backend, now = { 0L }) to backend
}

/**
 * Dispositivos y raices REALES para las pruebas del escritorio.
 *
 * ## POR QUE NO SE REUSA LA FIXTURE DE km-core
 *
 * `KmTestDevice` y `KmTestIdentityRoot` viven en el source set de TEST de
 * km-core, y el escritorio lo consume POR ARTEFACTO: un jar publicado no
 * arrastra los tests de quien lo publico. Copiar la fixture —con las MISMAS
 * primitivas del framework— es lo que deja estos tests con material
 * criptografico real sin fabricar ni un byte a mano.
 *
 * ## LO QUE NO HACE
 *
 * No decide nada: no relaja comprobaciones, no inventa identidades y no
 * sustituye firmas por ceros. Si un bundle verifica aqui, verifica porque la
 * firma de la raiz cuadra.
 */
object SecureTestIdentities {

    private val ed25519: Ed25519 = Ed25519Impl()
    private val x25519: X25519 = BcX25519()

    /** `keyId` del SPK de todos los dispositivos de la arnes. */
    const val SIGNED_PREKEY_ID: Long = 4242L

    /** `keyId` del OPK que emite el receptor para el establecimiento. */
    const val ONE_TIME_PREKEY_ID: Long = 9911L

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

        private val oneTimePreKeys = LinkedHashMap<Long, X25519KeyPair>()

        fun issueOneTimePreKey(keyId: Long): X25519KeyPair =
            oneTimePreKeys.getOrPut(keyId) { x25519.generateKeyPair() }

        fun oneTimePreKey(keyId: Long): X25519KeyPair? = oneTimePreKeys[keyId]
    }

    /** Raiz de identidad que FIRMA los bundles que publica. */
    class IdentityRoot {
        val keyPair = ed25519.generateKeyPair()
        val identityId: IdentityId = identityOf(keyPair.publicKey)

        fun bundleMapFor(
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

        /**
         * El mismo bundle, en el PORTADOR REAL que viaja por QR o `.km`.
         *
         * G1: el portador que produce Android es zlib + ISO-8859-1, no base64url
         * sin comprimir. Un fixture con `KceEnvelope.encode` probaba un formato
         * que ningun cliente emite; ahora el fixture pasa por el MISMO codec
         * compartido que el producto.
         */
        fun bundleTextFor(
            devices: List<Device>,
            subject: Device,
            oneTimePreKeyIds: List<Long>,
        ): String = com.km.identity.CarrierCodec.encode(
            com.km.identity.Kce.encode(bundleMapFor(devices, subject, oneTimePreKeyIds)),
        )
    }

    /** Un dispositivo con la raiz que lo autoriza. */
    class DeviceWithRoot(val device: Device, val root: IdentityRoot)

    /** Dispositivo con su raiz ya asignada. */
    fun deviceWithRoot(name: String, signedPreKeyId: Long = SIGNED_PREKEY_ID): DeviceWithRoot {
        val root = IdentityRoot()
        val device = Device(name, signedPreKeyId)
        return DeviceWithRoot(device, root)
    }

    /**
     * El papel LOCAL de una sesion: identidad de cable y material privado.
     *
     * Es lo que un `DesktopIdentity` mas un almacen de prekeys darian. Se agrupa
     * aqui para que los tests no tengan que reconstruirlo en cada uno, y para
     * que el nombre [local] no se confunda con el `deviceId` del bundle: la
     * identidad de CABLE sale de la clave de firma de la raiz local, que es lo
     * que `IdentityKeystore` produce.
     */
    class LocalEnd(
        val identityId: IdentityId,
        val ed25519PublicKey: ByteArray,
        val ed25519PrivateKey: ByteArray,
        val device: Device,
        val root: IdentityRoot,
    )

    /** Un extremo local completo, con raiz que lo firma. */
    fun localEnd(name: String): LocalEnd {
        val par = deviceWithRoot(name)
        return LocalEnd(
            identityId = par.root.identityId,
            ed25519PublicKey = par.root.keyPair.publicKey,
            ed25519PrivateKey = par.root.keyPair.privateKey,
            device = par.device,
            root = par.root,
        )
    }

    /** Una identidad de cable que NO es ni la local ni el otro extremo del test. */
    fun otroIdentityId(): IdentityId = identityOf(ed25519.generateKeyPair().publicKey)
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
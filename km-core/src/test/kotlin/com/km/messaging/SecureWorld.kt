package com.km.messaging

import com.km.api.KeyMessageCoreImpl
import com.km.codec.JsonDecoder
import com.km.codec.JsonEncoder
import com.km.codec.ValidatorImpl
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.X25519
import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.crypto.provider.BcHkdfSha256
import com.km.crypto.provider.BcX25519
import com.km.frame.SecureFrameProtector
import com.km.identity.ContactBundle
import com.km.identity.KmTestDevice
import com.km.identity.KmTestIdentityRoot
import com.km.model.IdentityId
import com.km.node.FakeTransportBackend
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.protocol.ConnectionState
import com.km.protocol.Transport
import com.km.storage.InMemoryMessageStore
import com.km.storage.InMemoryOfflineQueue

/**
 * FIXTURE COMPARTIDA del slice 4: dos dispositivos, dos pilas de transporte y
 * un `KeyMessageCoreImpl` por lado.
 *
 * ## QUE APORTA Y QUE NO
 *
 * Es ADITIVA. `SecureSessionEstablishmentTest` conserva sus helpers privados y
 * su guion; aqui se reproduce el MISMO guion —`PeerContext` AUTENTICADO de
 * verdad, bundle CONTACT verificado, `SecureSessionEstablishment` y el binding
 * conducido a CONNECTED por el API del manager— porque las pruebas del slice 4
 * necesitan lo mismo y duplicar el guion es preferible a tocar un fichero de
 * pruebas que ya es evidencia de otra cosa.
 *
 * NO se fabrica ningun byte criptografico a mano: los dispositivos salen de
 * `KmTestDevice`, que construye roster, `ContactBundle` y claves X25519
 * reales, y las sesiones salen de `SecureSessionEstablishment`, no de un
 * `DoubleRatchetSession` montado a mano.
 *
 * ## LAS DOS PILAS, Y POR QUE ESTAN LAS DOS
 *
 * - [Party.peerTransport] (`PeerTransportManager` + `FakeTransportBackend`) es
 *   por donde sale el `SecureFrame`. Es la pila que exige la criptografia.
 * - [Party.transport] (`RecordingTransport`) es el `Transport` legado de
 *   km-core, el que usa `SenderImpl` cuando no hay `SecureOutbound` inyectado.
 *   Registra lo que sale, y permite ponerlo offline sin tocar el otro stack.
 *
 * Que las dos coexistan en la fixture es deliberado: el slice 4 tiene que poder
 * demostrar las DOS ramas (cifrada con sesion, y legado en claro sin sesion
 * inyectada) sin dos fixtures distintas.
 */
object SecureWorld {

    val ed25519: Ed25519 = Ed25519Impl()
    val x25519: X25519 = BcX25519()
    val kdf = BcHkdfSha256()
    val protector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)

    const val BOB_OPK_ID = 1000L

    /** OPK propio del re-key, para que la sesion nueva no beba el del primer OPK. */
    const val REKEY_BOB_OPK_ID = 2000L

    fun establishment(): SecureSessionEstablishment = SecureSessionEstablishment(x25519, kdf, protector)

    /**
     * Un extremo del mundo: dispositivo, las dos pilas, el registro y el nucleo.
     *
     * @param secure si es `true`, el [core] recibe el registro y el manager de
     *   par, y por tanto su `SenderImpl` sale por la rama cifrada. Si es
     *   `false` el nucleo se construye con los argumentos LEGADOS y su envio
     *   sale en claro: ese es el camino de compatibilidad, y la fixture no lo
     *   disimula.
     * @param legacyCleartextReceive el opt-in de R1 en RECEPCION: con el
     *   registro presente, deja pasar `MESSAGE` EN CLARO al handler. No es E2E,
     *   y queda marcado en `core.legacyInbound.deliveredCleartext`.
     * @param peerSigningKey Resolver de clave de FIRMA por identidad, que es lo
     *   que permite a `AckManagerImpl` VERIFICAR el `ACK` que llega. Sin el,
     *   `onAckReceived` devuelve exito sin procesar nada —defecto D3— y ningun
     *   test del camino cifrado podria observar el cierre del ciclo de entrega.
     *   Sale del `ContactBundle` del otro, no de un valor inventado aqui.
     */
    class Party(
        val device: KmTestDevice,
        val root: KmTestIdentityRoot,
        secure: Boolean,
        legacyCleartextReceive: Boolean = false,
        peerSigningKey: (IdentityId) -> ByteArray? = { null },
    ) {
        val peerTransport: PeerTransportManager = PeerTransportManager(FakeTransportBackend(), now = { 0L })
        val transport: RecordingTransport = RecordingTransport(device.identityId)
        val registry: SecureSessionRegistry = SecureSessionRegistry(device.identityId)

        /**
         * Store y cola propios, expuestos para poder MIRARLOS.
         *
         * El nucleo los crearia por defecto y no habria forma de saber si un
         * mensaje quedo pendiente, quedo FAILED, o salio. Una cola vacia no
         * demuestra que no se encolo: demuestra que no se ve.
         */
        val messageStore: InMemoryMessageStore = InMemoryMessageStore()
        val offlineQueue: InMemoryOfflineQueue = InMemoryOfflineQueue()

        val core: KeyMessageCoreImpl = KeyMessageCoreImpl(
            // LA IDENTIDAD DE LA APLICACION ES LA RAIZ, no el dispositivo.
            //
            // No es una eleccion estetica: `AckManagerImpl.onAckReceived`
            // correlaciona con `ack.from != message.to`, y `ack.from` lo pone
            // `ReceiverImpl` con la `localIdentity` de ESTE nucleo. Como
            // `message.to` es el id de RAIZ que Alice uso para indexar la sesion,
            // un `localIdentity` a nivel de dispositivo haria que el `ACK` de B
            // no cerrara nunca el ciclo: se descartaria en silencio. La
            // granularidad tiene que coincidir en los dos lados de la
            // correlacion.
            //
            // La ASIMETRIA que declara D-C sigue intacta donde importa: el
            // `localIdentity` de la SESION es el del dispositivo, y lo remoto
            // es la raiz. Son dos capas distintas con dos granularidades
            // distintas, y cada una es coherente consigo misma.
            localIdentity = root.identityId,
            localPrivateKey = device.signingKeyPair.privateKey,
            transport = transport,
            messageStore = messageStore,
            offlineQueue = offlineQueue,
            encoder = JsonEncoder(),
            decoder = JsonDecoder(),
            validator = ValidatorImpl(),
            secureSessionRegistry = if (secure) registry else null,
            allowLegacyCleartextReceive = legacyCleartextReceive,
            peerTransportManager = if (secure) peerTransport else null,
            peerPublicKeyResolver = peerSigningKey,
        )

        fun initiatorKeys(): LocalInitiatorKeys = LocalInitiatorKeys(device.deviceId, device.agreementKey)

        fun responderKeys(opkId: Long?): LocalResponderKeys = LocalResponderKeys(
            deviceId = device.deviceId,
            agreementKey = device.agreementKey,
            signedPreKey = device.signedPreKey,
            oneTimePreKey = opkId?.let {
                requireNotNull(device.oneTimePreKey(it)) { "OPK $it sin emitir en ${device.name}" }
            },
            oneTimePreKeyId = opkId,
        )

        /** Conduce SU binding a CONNECTED por el API del manager. */
        fun driveConnected(session: SecureMessagingSession) {
            val m = session.transportManager
            val ctx = session.peerContext
            m.sendOffer(session.localIdentity, ctx, "sdp-offer", session.remotePeerId).getOrThrow()
            m.receiveAnswer(session.localIdentity, session.remotePeerId, "sdp-answer").getOrThrow()
            m.markConnected(session.localIdentity, ctx).getOrThrow()
        }

        /** Los bytes que salen del `SecureMessagingSession` por su transporte. */
        fun captureOutbound(): MutableList<ByteArray> {
            val captured = mutableListOf<ByteArray>()
            peerTransport.onData { _, data -> captured.add(data) }
            return captured
        }

        /**
         * Entrega a esta parte unos bytes que llegaron del par de [peerContext].
         *
         * `PeerTransportManager` no tiene un metodo publico de entrega entrante:
         * la unica via por la que sus `dataHandlers` se disparan es `sendData`.
         * Invocarlo aqui produce exactamente la tupla `(from, wire)` que recibe
         * un proceso real, que es lo que la sesion y el puente de recepcion
         * necesitan para decidir.
         *
         * ## LOS DOS IDENTIFICADORES, Y DE QUE GRANO ES CADA UNO
         *
         * `sendData` recibe DOS ids y no son el mismo ni del mismo grano. Decirlo
         * aqui evita el error que hacia esta firma mentir:
         *
         * - `peerContext.remotePeerId` es el `from` que ven los `dataHandlers`.
         *   Con `forContact` es la **RAIZ** del contacto, no su dispositivo: el
         *   llamante no lo elige y por eso la fixture no lo acepta como
         *   parametro.
         * - `localIdentity` es la mitad LOCAL de la clave del binding
         *   (`localIdentity to remotePeerId`) y no sale por los handlers. Aqui se
         *   pasa `device.identityId`, o sea **DISPOSITIVO**, y es correcto: es
         *   quien abre el enlace, no quien es el par.
         *
         * Antes el parametro `from` ocupaba el hueco de `localIdentity` y se
         * llenaba con el id del **DISPOSITIVO** del par, mientras el manager
         * notificaba con el `remotePeerId` de **RAIZ**: dos identificadores
         * distintos describiendo la misma cosa, y el parametro decia algo que
         * no ocurria. Por eso desaparecio de la firma.
         *
         * El que llama sigue siendo responsable de que los bytes sean los de
         * verdad: por eso el parametro no se llama `data` a secas.
         */
        fun deliverInboundAs(peerContext: PeerContext, wire: ByteArray) {
            peerTransport.sendData(device.identityId, peerContext, wire).getOrThrow()
        }
    }

    /**
     * Mundo de dos partes con sesion establecida entre ellas.
     *
     * @param secure construye los nucleos con la inyeccion opcional activada.
     * @param establish si es `false` NO se establece sesion: el mundo existe,
     *   pero el registro queda vacio. Es el escenario de rechazo explicito.
     * @param installOnReceiver si es `false` el receptor se queda sin sesion.
     * @param legacyCleartextReceive el opt-in de R1 en recepcion. Solo importa
     *   con `secure = true`: sin registro el camino legado esta abierto igual.
     */
    class World(
        val secure: Boolean = true,
        val establish: Boolean = true,
        val installOnReceiver: Boolean = true,
        val legacyCleartextReceive: Boolean = false,
    ) {
        private val aliceDevice = KmTestDevice("alice")
        private val bobDevice = KmTestDevice("bob")

        // UNA RAIZ POR PAR, nunca compartida. `PeerContext.forContact` deriva el
        // `remotePeerId` del `identityRoot` del bundle, luego con UNA sola raiz
        // los DOS SENTIDOS llevarian el MISMO valor de `remotePeerId`.
        //
        // Lo que eso rompe aqui NO es un binding: cada parte tiene su propio
        // `PeerTransportManager` (ver [Party.peerTransport]), luego los bindings
        // de A y de B nunca se ven. Lo que se rompe es la DIRECCION: con un solo
        // valor no hay forma de distinguir "me lo mando yo" de "me lo manda el
        // otro", y la correlacion del `ACK` (`ack.from` frente a `message.to`,
        // ver [Party.core]) dejaria de distinguir los dos sentidos.
        val aliceRoot = KmTestIdentityRoot()
        val bobRoot = KmTestIdentityRoot()

        val alice: Party = Party(aliceDevice, aliceRoot, secure, legacyCleartextReceive) { id ->
            // El `ACK` lo firma `ReceiverImpl` con la `localIdentity` del NUCLEO,
            // que aqui es el id de RAIZ (ver [Party.core]): luego la clave se
            // resuelve por id de raiz y no por id de dispositivo.
            if (id == bobRoot.identityId) bobDevice.signingKeyPair.publicKey else null
        }
        val bob: Party = Party(bobDevice, bobRoot, secure, legacyCleartextReceive) { id ->
            if (id == aliceRoot.identityId) aliceDevice.signingKeyPair.publicKey else null
        }
        val establisher: SecureSessionEstablishment = establishment()

        val aliceToBob: PeerContext
        val bobToAlice: PeerContext

        /** Sesiones ya instaladas en el registro de cada parte. */
        var sessionA: SecureMessagingSession? = null
            private set
        var sessionB: SecureMessagingSession? = null
            private set

        init {
            bob.device.issueOneTimePreKey(BOB_OPK_ID)
            aliceToBob = alice.contextTowards(bob, bobRoot, listOf(BOB_OPK_ID))
            bobToAlice = bob.contextTowards(alice, aliceRoot, emptyList())
            if (establish) handshake()
        }

        /** Abre e instala las dos sesiones. Idempotente por construccion del mundo. */
        fun handshake(): Pair<SecureMessagingSession, SecureMessagingSession> {
            val initiated = establisher.initiate(
                localIdentity = alice.device.identityId,
                peerContext = aliceToBob,
                localKeys = alice.initiatorKeys(),
                transport = alice.peerTransport,
            )
            val accepted = establisher.accept(
                localIdentity = bob.device.identityId,
                peerContext = bobToAlice,
                localKeys = bob.responderKeys(initiated.usedOneTimePreKeyId),
                prekeyFrameWire = initiated.prekeyFrame,
                transport = bob.peerTransport,
            )
            alice.driveConnected(initiated.session)
            bob.driveConnected(accepted.session)
            alice.registry.install(initiated.session)
            // `installOnReceiver = false` deja al RECEPTOR sin sesion: el caso
            // real de "me llega algo cifrado de alguien con quien no he
            // establecido nada", que es distinto del caso "nadie ha establecido
            // nada" y merece su propio escenario.
            if (installOnReceiver) bob.registry.install(accepted.session)
            sessionA = initiated.session
            sessionB = if (installOnReceiver) accepted.session else null
            return initiated.session to accepted.session
        }

        /**
         * Abre un SEGUNDO par de sesiones para los MISMOS dos pares: un re-key.
         *
         * No reutiliza nada del primer establecimiento. Emite un OPK NUEVO de B,
         * vuelve a construir los dos `PeerContext` autenticados con el bundle
         * verificado actualizado y llama a `initiate`/`accept` otra vez, de modo
         * que las dos sesiones tienen cadenas de ratchet DISTINTAS y no pueden
         * descifrarse entre si.
         *
         * NO reinicia el binding: los dos `PeerTransportManager` ya tienen uno
         * CONNECTED para ese par, y `initiate`/`accept` no emiten ningun byte —el
         * frame PREKEY se pasa de mano en mano como valor de retorno—. Asi que
         * el enlace es el mismo y lo que cambia es la sesion, que es lo que se
         * quiere observar.
         *
         * NO instala nada en ningun registro: quien llama decide si sustituye,
         * porque instalar es precisamente lo que dispara la observacion.
         */
        fun rekey(): Pair<SecureMessagingSession, SecureMessagingSession> {
            bob.device.issueOneTimePreKey(REKEY_BOB_OPK_ID)
            val aliceToBob2 = contextTowards(
                bob.device, listOf(REKEY_BOB_OPK_ID), bobRoot,
            )
            val bobToAlice2 = contextTowards(
                alice.device, emptyList(), aliceRoot,
            )
            val initiated = establisher.initiate(
                localIdentity = alice.device.identityId,
                peerContext = aliceToBob2,
                localKeys = alice.initiatorKeys(),
                transport = alice.peerTransport,
            )
            val accepted = establisher.accept(
                localIdentity = bob.device.identityId,
                peerContext = bobToAlice2,
                localKeys = bob.responderKeys(initiated.usedOneTimePreKeyId),
                prekeyFrameWire = initiated.prekeyFrame,
                transport = bob.peerTransport,
            )
            return initiated.session to accepted.session
        }
    }

    /** Abre un mundo ya establecido, que es el caso normal. */
    fun open(secure: Boolean = true, legacyCleartextReceive: Boolean = false): World =
        World(secure = secure, establish = true, legacyCleartextReceive = legacyCleartextReceive)

    /** Abre un mundo SIN sesion: el registro existe pero esta vacio. */
    fun openWithoutSession(secure: Boolean = true): World = World(secure = secure, establish = false)

    /**
     * Abre un mundo donde SOLO el emisor tiene sesion.
     *
     * Es el escenario del rechazo al recibir: el frame de A es de verdad, pero
     * B no ha establecido nada con nadie, asi que no hay a quien preguntarle.
     * Un mundo sin ninguna sesion no lo demuestra: en ese caso A tampoco podria
     * emitir, y no habria ningun frame que descartar.
     */
    fun openWithoutRecipientSession(secure: Boolean = true): World =
        World(secure = secure, establish = true, installOnReceiver = false)

    /**
     * `Transport` que REGISTRA lo que sale, en vez de enviarlo.
     *
     * Es el unico punto de observacion del camino legado: si aqui no hay
     * bytes, no los hay emitted por ninguna parte, y eso es lo que permite
     * afirmar que la rama cifrada NO llama a `Transport.send`.
     */
    class RecordingTransport(
        val localIdentity: IdentityId,
    ) : Transport {

        /** Cargas de aplicacion que salieron por `send`. */
        val sent = mutableListOf<ByteArray>()

        /** Destinos de [sent], en el mismo orden. */
        val sentTo = mutableListOf<IdentityId>()

        /** `ACK` que salieron por el canal propio. */
        val sentAcks = mutableListOf<ByteArray>()

        var online: Boolean = true

        private var messageHandler: ((ByteArray) -> Unit)? = null
        private var ackHandler: ((ByteArray) -> Unit)? = null
        private val stateHandlers = mutableListOf<(ConnectionState) -> Unit>()
        private val peerOnlineHandlers = mutableListOf<(IdentityId) -> Unit>()
        private val peerOfflineHandlers = mutableListOf<(IdentityId) -> Unit>()

        val sendCount: Int get() = sent.size

        override fun connect(): Result<Unit> {
            online = true
            return Result.success(Unit)
        }

        override fun disconnect() {
            online = false
        }

        override fun send(data: ByteArray, to: IdentityId): Result<Unit> {
            sent.add(data.copyOf())
            sentTo.add(to)
            return Result.success(Unit)
        }

        override fun sendAck(ackBytes: ByteArray): Result<Unit> {
            sentAcks.add(ackBytes.copyOf())
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

        override fun onStateChange(handler: (ConnectionState) -> Unit) {
            stateHandlers.add(handler)
        }

        override fun onPeerOnline(handler: (IdentityId) -> Unit) {
            peerOnlineHandlers.add(handler)
        }

        override fun onPeerOffline(handler: (IdentityId) -> Unit) {
            peerOfflineHandlers.add(handler)
        }

        /** Entrega bytes por el camino heredado de `MESSAGE`. */
        fun deliverLegacyMessage(data: ByteArray) {
            messageHandler?.invoke(data)
        }

        /** Entrega un `ACK` por el camino heredado de `ACK`. */
        fun deliverLegacyAck(data: ByteArray) {
            ackHandler?.invoke(data)
        }

        /** Simula que el par vuelve a estar en linea. */
        fun goOnline(peerId: IdentityId) {
            online = true
            peerOnlineHandlers.forEach { it(peerId) }
        }

        fun goOffline() {
            online = false
        }
    }

    // ------------------------------------------------------------------
    // PeerContext de CONTACTO: la autoridad es un bundle verificado
    // ------------------------------------------------------------------

    private fun Party.contextTowards(other: Party, otherRoot: KmTestIdentityRoot, otherOpkIds: List<Long>): PeerContext =
        contextTowards(other.device, otherOpkIds, otherRoot)

    /**
     * `PeerContext` de CONTACTO de `from` hacia `to`, con el bundle de `to`
     * VERIFICADO.
     *
     * Aqui es donde esta la fractura que se cierra: antes esta funcion
     * fabricaba, EN NOMBRE DEL PAR, un `AuthSession`, un `AuthOk` y un
     * `NodeAnnouncement` firmado —material de nodo que en produccion exige el
     * handshake completo— y despues anadia el bundle con `withContactBundle`.
     * Eso hacia posible un guion de test que en produccion es imposible, y por
     * eso el camino de contacto no se podia probar de verdad.
     *
     * Ahora el contexto sale de `PeerContext.forContact`: la autoridad es el
     * bundle verificado y el `remotePeerId` lo deriva la propia fabrica de la
     * RAIZ que lo firma. `from` ya no aparece, y no es un parametro muerto: es
     * que el emisor no participa en la identidad del par.
     */
    fun contextTowards(
        to: KmTestDevice,
        toOneTimePreKeyIds: List<Long>,
        root: KmTestIdentityRoot,
    ): PeerContext = PeerContext.forContact(
        ContactBundle.verified(root.bundleFor(listOf(to), to, toOneTimePreKeyIds)),
    )
}

/** Busca los bytes de [needle] dentro de [this], sin depender de conversiones. */
fun ByteArray.containsBytesOf(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    for (i in 0..size - needle.size) {
        var match = true
        for (j in needle.indices) if (this[i + j] != needle[j]) { match = false; break }
        if (match) return true
    }
    return false
}

package com.km.messaging

import com.km.crypto.Kdf
import com.km.crypto.X25519
import com.km.crypto.X25519KeyPair
import com.km.frame.BinarySecureFrameCodec
import com.km.frame.FrameType
import com.km.frame.RatchetHeader
import com.km.frame.SecureFrameCodec
import com.km.frame.SecureFrameFormatException
import com.km.frame.SecureFrameProtector
import com.km.frame.SecureRatchetProtocol
import com.km.model.IdentityId
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import com.km.ratchet.RatchetSessionBootstrap
import com.km.x3dh.BootstrapPrekeys
import com.km.x3dh.X3dh
import com.km.x3dh.X3dhSpec
import java.security.SecureRandom

/**
 * ESTABLECIMIENTO DE SESION — el punto de entrada UNICO del slice 3.
 *
 * <pre>
 *   EMISOR                                        RECEPTOR
 *   ------                                        --------
 *   PeerContext(contactBundle verificado)         PeerContext(contactBundle verificado)
 *        |                                              ^
 *        v                                              |
 *   [BootstrapPrekeys.fromVerified]  --- IK_A, EK_A --->  [X3dh.respond]
 *        |                                              |
 *        v                                              v
 *   [X3dh.initiate] ------------------------------> SK, K_prekey, F
 *        |                                              ^
 *        v                                              | unprotect con K_prekey
 *   [RatchetSessionBootstrap.initiatorProtocol]       |
 *        |                                              v
 *        +------- SecureFrame PREKEY con F ----------> [RatchetSessionBootstrap.responderProtocol]
 *        |                                              |
 *        v                                              v
 *   SecureMessagingSession                     SecureMessagingSession
 * </pre>
 *
 * ## QUE RESUELVE
 *
 * Antes de este slice, obtener una sesion de mensajeria exigia conocer tres
 * capas a la vez: el `ContactBundle` verificado, el motor X3DH y el bootstrap
 * del Double Ratchet, y ademas hay que mantener el orden correcto
 * (`X3dh.initiate` -> `commit` del receptor -> ratchet). Cada uno de esos
 * pasos puede hacerse mal en silencio. Aqui hay UN metodo por lado, y el
 * llamante aporta material LOCAL y un [PeerContext]; la criptografia no sale
 * de km-core.
 *
 * LO QUE ESTE TIPO NO HACE, Y POR QUE
 *
 * - **No enruta.** [transport] entra como collaborator ya construido. Esta
 *   clase no sabe de WebRTC, relay ni Tor: es lo que sostiene la separacion
 *   crypto/transporte de `SecureMessagingSession` y lo que permite probarla
 *   contra `FakeTransportBackend`.
 * - **No conduce el enlace a CONNECTED.** Abrir el binding es de km-core; la
 *   negociacion SDP/ICE es del transporte. El binding se crea si no existe,
 *   pero su avance es del medio.
 * - **No consume OPKs.** Dice cual es el `keyId` de cada lado
 *   ([InitiatedSession.usedOneTimePreKeyId] y
 *   [AcceptedSession.consumedOneTimePreKeyId]) y decide el llamante: el
 *   receptor solo marca el suyo consumido cuando ha confirmado la sesion, que es
 *   exactamente lo que hace [com.km.x3dh.X3dhSessionPreparation.commit].
 * - **No registra la sesion.** Ver [SecureSessionRegistry]: el registro es del
 *   consumidor y su conexion con el camino de envio es de otro slice.
 */
class SecureSessionEstablishment(
    private val x25519: X25519,
    private val kdf: Kdf,
    private val protector: SecureFrameProtector,
    private val codec: SecureFrameCodec = BinarySecureFrameCodec,
    private val random: SecureRandom = SecureRandom(),
) {
    private val x3dh: X3dh = X3dh(x25519, kdf)

    // ===================================================================
    // Lado emisor
    // ===================================================================

    /**
     * Inicia una sesion contra el bundle verificado que lleva el [peerContext].
     *
     * @param peerContext contexto AUTENTICADO del par, con
     *   [PeerContext.contactBundle] ya vinculado. Sin bundle no hay nada que
     *   verificar y por tanto nada que arrancar: esto LANZA.
     * @param oneTimePrekeyIndex posicion del OPK a usar, o `null` para una
     *   sesion sin `DH4`. Ver [BootstrapPrekeys.fromVerified], que es quien
     *   fija el contrato y lanza si la posicion no existe.
     * @return la sesion, el `SecureFrame` PREKEY que hay que entregar al
     *   receptor, y el `keyId` del OPK consumido por el emisor.
     * @throws IllegalArgumentException si falta el bundle verificado.
     */
    fun initiate(
        localIdentity: IdentityId,
        peerContext: PeerContext,
        localKeys: LocalInitiatorKeys,
        transport: PeerTransportManager,
        oneTimePrekeyIndex: Int? = 0,
    ): InitiatedSession {
        // El bundle verificado es la unica fuente de material remoto. Si no
        // esta, no hay ruta: no existe una sobrecarga que acepte un mapa.
        val remote = requireNotNull(peerContext.contactBundle) {
            "el PeerContext no lleva ContactBundle verificado: no hay material remoto que verificar"
        }
        val prekeys = BootstrapPrekeys.fromVerified(remote, oneTimePrekeyIndex)

        // `F` es ALEATORIO y lo genera el framework: es el segredo que Alice
        // conoce de antemano y sin el cual dos sesiones contra el mismo SPK
        // derivarian el mismo SK (invariante 7 de KM-0006).
        val bootstrapValue = ByteArray(X3dhSpec.F_LENGTH).also { random.nextBytes(it) }

        val init = x3dh.initiate(
            initiator = com.km.x3dh.InitiatorKeyMaterial(
                deviceId = localKeys.deviceId.copyOf(),
                identityAgreementKey = localKeys.agreementKey,
            ),
            prekeys = prekeys,
            bootstrapValue = bootstrapValue,
        )

        val protocol = RatchetSessionBootstrap.initiatorProtocol(
            bootstrapValue = bootstrapValue,
            // La MISMA efimera que X3DH genero: es el `DHs` con el que el
            // ratchet continua. Generar otra seria tirar material ya gastado.
            ephemeral = init.ephemeral,
            remoteSignedPreKey = prekeys.signedPreKey,
            x25519 = x25519,
            kdf = kdf,
            protector = protector,
        )

        // El primer frame lleva `F` protegido con `K_prekey`, que es un
        // dominio DISTINTO del que protege los mensajes de la sesion. El
        // receptor lo abre con esa clave y con ninguna otra.
        val prekeyFrame = codec.encode(
            protector.protect(
                type = FrameType.PREKEY,
                ratchetHeader = RatchetHeader(
                    // `EK_A` viaja en el header del frame: es lo unico que el
                    // receptor necesita para el `DH2`/`DH3`.
                    dhPublicKey = init.ephemeralPublic,
                    previousChainLength = 0u,
                    messageNumber = 0u,
                ),
                messageKey = init.preKeyKey,
                plaintext = bootstrapValue,
            ),
        )

        return InitiatedSession(
            session = openSession(localIdentity, peerContext, protocol, transport),
            prekeyFrame = prekeyFrame,
            usedOneTimePreKeyId = init.usedOneTimePreKeyId,
        )
    }

    // ===================================================================
    // Lado receptor
    // ===================================================================

    /**
     * Acepta el `SecureFrame` PREKEY del emisor y abre la sesion equivalente.
     *
     * El receptor NO necesita que el emisor le diga que OPK uso: lo deduce de
     * las tres claves privadas que el propio [localKeys] contiene, y la
     * coincidencia de ambos `SK` es lo que demuestra que la eleccion fue la
     * misma. Si [localKeys] lleva un OPK que el emisor no uso, los `SK` no
     * coinciden y la sesion no descifra nada.
     *
     * @param peerContext contexto AUTENTICADO del emisor, con SU bundle
     *   verificado. Es OBLIGATORIO por KM-0006 §7.3: el `DH1` necesita
     *   `IK_A`, que no viaja en el `SecureFrame` (header fijo de 44 bytes).
     * @param prekeyFrameWire bytes de wire del frame PREKEY recibido.
     * @throws SecureFrameFormatException si los bytes no son un SecureFrame v1.
     * @throws IllegalArgumentException si el frame no es de tipo PREKEY, o si
     *   falta el bundle verificado del emisor.
     * @throws com.km.crypto.AeadAuthenticationException si `F` no abre con
     *   `K_prekey`, es decir, si el frame fue alterado o no es de esta sesion.
     */
    fun accept(
        localIdentity: IdentityId,
        peerContext: PeerContext,
        localKeys: LocalResponderKeys,
        prekeyFrameWire: ByteArray,
        transport: PeerTransportManager,
    ): AcceptedSession {
        val remote = requireNotNull(peerContext.contactBundle) {
            "el PeerContext no lleva el ContactBundle verificado del emisor: sin IK_A no hay DH1 (§7.3)"
        }
        val frame = codec.decode(prekeyFrameWire)
        require(frame.type == FrameType.PREKEY) {
            "el frame recibido es ${frame.type}, no PREKEY: no es un frame de establecimiento"
        }

        // `X3dh.respond` solo consume `remotePrekeys.identityAgreementKey`
        // (`IK_A`). Se derivan las prekeys del bundle del EMISOR pasando
        // `null`: sus one-time prekeys no son material de este lado y
        // confundirlos con los del receptor seria el error clasico.
        val preparation = x3dh.respond(
            responder = com.km.x3dh.ResponderKeyMaterial(
                deviceId = localKeys.deviceId.copyOf(),
                identityAgreementKey = localKeys.agreementKey,
                signedPreKey = localKeys.signedPreKey,
                oneTimePreKey = localKeys.oneTimePreKey,
                oneTimePreKeyId = localKeys.oneTimePreKeyId,
            ),
            remotePrekeys = BootstrapPrekeys.fromVerified(remote, oneTimePrekeyIndex = null),
            // `EK_A` viene del header, que es material remoto: `X3dh.respond`
            // lo pasa por el guard de claves de acuerdo antes de derivar nada.
            ephemeralPublic = frame.ratchetHeader.dhPublicKey,
        )

        // ORDEN: `F` se abre ANTES de confirmar. `commit()` es la decision de
        // consumir el OPK, y esa decision se toma "tras descifrar
        // correctamente el frame PREKEY" (X3dh.kt). Si el AEAD falla, este
        // metodo lanza y el OPK sigue disponible.
        val bootstrapValue = protector.unprotect(frame, preparation.preKeyKey)
        val responderSession = preparation.commit()

        val protocol = RatchetSessionBootstrap.responderProtocol(
            bootstrapValue = bootstrapValue,
            signedPreKey = localKeys.signedPreKey,
            x25519 = x25519,
            kdf = kdf,
            protector = protector,
        )

        return AcceptedSession(
            session = openSession(localIdentity, peerContext, protocol, transport),
            consumedOneTimePreKeyId = responderSession.consumedOneTimePreKeyId,
        )
    }

    /**
     * Envuelve el protocolo en una [SecureMessagingSession] y ASEGURA que
     * exista el binding de transporte.
     *
     * El binding se crea si falta y no se toca si ya esta: `createBinding`
     * falla con `BINDING_ALREADY_EXISTS` ante uno activo, y un consumidor que
     * ya habia abierto el enlace —porque negocia antes de cifrar— no debe ver
     * fallar el establecimiento por eso.
     *
     * Que la sesion exista NO significa que se pueda enviar: `send` exige
     * `CONNECTED`, y a ese estado lo conduce el transporte.
     */
    private fun openSession(
        localIdentity: IdentityId,
        peerContext: PeerContext,
        protocol: SecureRatchetProtocol,
        transport: PeerTransportManager,
    ): SecureMessagingSession {
        if (transport.getBinding(localIdentity, peerContext.remotePeerId) == null) {
            transport.createBinding(localIdentity, peerContext).getOrThrow()
        }
        return SecureMessagingSession(
            localIdentity = localIdentity,
            peerContext = peerContext,
            protocol = protocol,
            transport = transport,
        )
    }
}

/**
 * Material PRIVADO del dispositivo LOCAL para INICIAR una sesion.
 *
 * Es el unico dato de este slice que el consumidor tiene que saber nombrar: su
 * `deviceId` y su clave de ACUERDO (`IK`). El nombre `agreementKey` y no
 * `signingKey` no es cosmetico: X3DH rechaza cualquier clave de firma aqui, y
 * la confusion entre las dos es el error que la separacion de KM-ID-0001
 * prohibe.
 */
class LocalInitiatorKeys(
    val deviceId: ByteArray,
    val agreementKey: X25519KeyPair,
) {
    override fun toString(): String = "LocalInitiatorKeys(<${deviceId.size}B deviceId, <${agreementKey.publicKey.size}B agreementKey>)"
}

/**
 * Material PRIVADO del dispositivo LOCAL para RESPONDER una sesion.
 *
 * INVARIANTE: [oneTimePreKey] y [oneTimePreKeyId] van juntos o no van. El
 * `keyId` es lo que permite al consumidor marcar el consumo en su almacen, y
 * un OPK sin id no se puede registrar. Se comprueba aqui y no en X3DH porque es
 * un dato del CONSUMIDOR: [com.km.x3dh.ResponderKeyMaterial] admite la
 * combinacion imposible (`oneTimePreKey` sin `oneTimePreKeyId`) y nadie la
 * detecta despues —la sesion funciona igual y el consumo se pierde en
 * silencio—.
 */
class LocalResponderKeys(
    val deviceId: ByteArray,
    val agreementKey: X25519KeyPair,
    val signedPreKey: X25519KeyPair,
    val oneTimePreKey: X25519KeyPair? = null,
    val oneTimePreKeyId: Long? = null,
) {
    init {
        require((oneTimePreKey == null) == (oneTimePreKeyId == null)) {
            "oneTimePreKey y oneTimePreKeyId van juntos: " +
                "un OPK sin keyId no se puede registrar como consumido"
        }
    }

    override fun toString(): String =
        "LocalResponderKeys(<${deviceId.size}B deviceId, opkId=$oneTimePreKeyId)"
}

/**
 * Resultado de [SecureSessionEstablishment.initiate].
 *
 * @property session la sesion lista para cifrar y descifrar.
 * @property prekeyFrame los bytes que hay que entregar al RECEPTOR por el
 *   transporte. Contienen `F` cifrado con `K_prekey` y `EK_A` en el header;
 *   no son un mensaje de la sesion y no se descifran con el ratchet.
 * @property usedOneTimePreKeyId `keyId` del OPK que el emisor ha usado, o
 *   `null` si la sesion se establecio sin `DH4`. El emisor debe descartar ESE
 *   OPK de su almacen: no volver a ofrecerlo.
 */
class InitiatedSession internal constructor(
    val session: SecureMessagingSession,
    val prekeyFrame: ByteArray,
    val usedOneTimePreKeyId: Long?,
)

/**
 * Resultado de [SecureSessionEstablishment.accept].
 *
 * @property session la sesion lista para cifrar y descifrar.
 * @property consumedOneTimePreKeyId `keyId` del OPK que este dispositivo ha
 *   consumido al responder, o `null` si no habia ninguno. Se devuelve DESPUES
 *   de que el frame PREKEY abriera con `K_prekey`: si [accept] lanza, este
 *   valor no existe y el OPK sigue disponible.
 */
class AcceptedSession internal constructor(
    val session: SecureMessagingSession,
    val consumedOneTimePreKeyId: Long?,
)
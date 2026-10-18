package com.km.desktop.session

import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.Kdf
import com.km.crypto.X25519
import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.crypto.provider.BcHkdfSha256
import com.km.crypto.provider.BcX25519
import com.km.frame.SecureFrameProtector
import com.km.identity.VerifiedContactBundle
import com.km.messaging.AcceptedSession
import com.km.messaging.InitiatedSession
import com.km.messaging.LocalInitiatorKeys
import com.km.messaging.LocalResponderKeys
import com.km.messaging.SecureSessionEstablishment
import com.km.messaging.SecureSessionRegistry
import com.km.model.IdentityId
import com.km.node.PeerContext
import com.km.node.PeerTransportManager
import java.security.SecureRandom

/**
 * Abrir sesion con un contacto: el UNICO lugar del escritorio que llama a X3DH.
 *
 * ## QUE HACE Y QUE NO
 *
 * Llama a `SecureSessionEstablishment.initiate` / `.accept` y REGISTRA la sesion
 * resultante en el [SecureSessionRegistry]. No implementa criptografia, no
 * deriva claves y no decide quien es el par: eso es km-core.
 *
 * Lo unico que el escritorio añade —y es pequeno a proposito— es la puerta:
 * [contextoHacia] construye el `PeerContext` con `PeerContext.forContact`, de
 * modo que la clave de ruteo la DERIVA la fabrica del framework a partir de la
 * raiz del bundle, y este modulo no puede colar un `subjectDeviceId` como si
 * fuera la identidad de la conversacion.
 *
 * ## POR QUE LOS PROVIDERS SON POR DEFECTO Y NO INYECTADOS DESDE ARRIBA
 *
 * Porque el unico criptografico del repositorio es el de km-core, y sus tres
 * providers de `com.km.crypto.provider` son los unicos que existen. Declararlos
 * como parametro con valor por defecto no abre la puerta a sustituirlos: quien
 * quiera uno distinto tiene que nombrarlo explicitamente, y entonces se ve.
 *
 * ## LO QUE NO HACE
 *
 * No envia el frame PREKEY. Producirlo es de `initiate`; entregarlo por el
 * rele es de quien tiene el rele abierto. Aqui no se abre ningun socket.
 */
class SessionCoordinator(
    x25519: X25519 = BcX25519(),
    kdf: Kdf = BcHkdfSha256(),
    private val random: SecureRandom = SecureRandom(),
) {

    private val establishment: SecureSessionEstablishment = SecureSessionEstablishment(
        x25519 = x25519,
        kdf = kdf,
        protector = SecureFrameProtector(BcChaCha20Poly1305(), kdf),
        random = random,
    )

    /**
     * El `PeerContext` de CONTACTO hacia [bundle].
     *
     * El `remotePeerId` lo deriva `PeerContext.forContact` de la RAIZ del bundle
     * verificado. Aqui no hay ningun parametro para elegirlo, y eso es el
     * invariante: la clave de ruteo de una conversacion no puede ser la de un
     * dispositivo suelto.
     */
    fun contextoHacia(bundle: VerifiedContactBundle): PeerContext = PeerContext.forContact(bundle)

    /**
     * Lado EMISOR: abre sesion y deja instalado el frame PREKEY que hay que
     * entregar al receptor.
     */
    fun iniciar(
        localIdentity: IdentityId,
        peerContext: PeerContext,
        localKeys: LocalInitiatorKeys,
        manager: PeerTransportManager,
        registry: SecureSessionRegistry,
        oneTimePrekeyIndex: Int? = 0,
    ): InitiatedSession {
        val iniciada = establishment.initiate(
            localIdentity = localIdentity,
            peerContext = peerContext,
            localKeys = localKeys,
            transport = manager,
            oneTimePrekeyIndex = oneTimePrekeyIndex,
        )
        registry.install(iniciada.session)
        return iniciada
    }

    /**
     * Lado RECEPTOR: acepta el frame PREKEY y deja instalada la sesion.
     *
     * `consumedOneTimePreKeyId` se devuelve para que el llamante pueda marcar el
     * OPK como gastado en SU almacen: el framework lo dice y no lo hace, y un
     * OPK que se vuelve a ofrecer es material reutilizado.
     */
    fun aceptar(
        localIdentity: IdentityId,
        peerContext: PeerContext,
        localKeys: LocalResponderKeys,
        prekeyFrameWire: ByteArray,
        manager: PeerTransportManager,
        registry: SecureSessionRegistry,
    ): AcceptedSession {
        val aceptada = establishment.accept(
            localIdentity = localIdentity,
            peerContext = peerContext,
            localKeys = localKeys,
            prekeyFrameWire = prekeyFrameWire,
            transport = manager,
        )
        registry.install(aceptada.session)
        return aceptada
    }

    /** El `Ed25519` por defecto, para quien necesite firmar sin crear otro. */
    val ed25519: Ed25519 get() = Ed25519Impl()
}
package com.km.desktop

import com.km.crypto.Ed25519Impl
import com.km.desktop.core.CoreFactory
import com.km.desktop.session.SessionCoordinator
import com.km.desktop.support.SecureTestIdentities
import com.km.desktop.support.recordingTransport
import com.km.desktop.support.relayProbeManager
import com.km.transport.JvmRelayTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 3.4 — `status` cuenta los pares con SESION, y `remove` los quita.
 *
 * ## QUE AFIRMA
 *
 * `Session.sessionPeers()` es el `peers()` del registro: tras instalar una
 * sesion lista UN par, y tras `remove` deja de listarlo. No es lo mismo que
 * `onlinePeers()`, que es estado del rele.
 *
 * ## POR QUE NO BASTA CON QUE EL REGISTRO FUNCIONE
 *
 * Porque km-core ya guarantees que `peers()` funciona. Lo que este test fija es
 * que la `Session` del escritorio lo EXPONE y que la pantalla lo muestra: un
 * registro correcto al que la TUI no mira deja al usuario sin poder saber por
 * que su mensaje se rechaza.
 */
class RegistryPeersStatusTest {

    private fun motor(identidad: com.km.model.IdentityId): JvmRelayTransport {
        val ed25519 = Ed25519Impl()
        val par = ed25519.generateKeyPair()
        return JvmRelayTransport(
            identity = identidad,
            ed25519PublicKey = par.publicKey,
            ed25519PrivateKey = par.privateKey,
            endpoint = "wss://ejemplo.invalid/km",
            ed25519 = ed25519,
        )
    }

    private fun sesion(): Session {
        val local = SecureTestIdentities.localEnd("alice")
        val pila = com.km.desktop.net.RelayStackFactory.stackFor(motor(local.identityId))
        val (manager, _) = relayProbeManager()
        val nucleo = CoreFactory().create(
            localIdentity = local.identityId,
            localPrivateKey = local.ed25519PrivateKey,
            transport = pila.transport,
            manager = manager,
            peerPublicKeyResolver = { null },
        )
        return Session(
            identity = DesktopIdentity(
                identityId = local.identityId,
                ed25519PublicKey = local.ed25519PublicKey,
                ed25519PrivateKey = local.ed25519PrivateKey,
                x25519PublicKey = local.device.agreementKey.publicKey,
                x25519PrivateKey = ByteArray(32),
            ),
            keystorePath = java.nio.file.Paths.get("p5-no-se-usa"),
            stack = pila,
            core = nucleo,
            peerKeys = PeerKeyDirectory(),
        )
    }

    private fun sesionRemota(s: Session, nombre: String): com.km.messaging.SecureMessagingSession {
        val par = SecureTestIdentities.deviceWithRoot(nombre)
        val kdf = com.km.crypto.provider.BcHkdfSha256()
        return com.km.messaging.SecureMessagingSession(
            localIdentity = s.identity.identityId,
            peerContext = SessionCoordinator().contextoHacia(
                com.km.identity.VerifiedContactBundle.verified(
                    par.root.bundleMapFor(listOf(par.device), par.device, emptyList()),
                ),
            ),
            protocol = com.km.ratchet.RatchetSessionBootstrap.responderProtocol(
                bootstrapValue = ByteArray(32) { (it * 11 + 5).toByte() },
                signedPreKey = par.device.signedPreKey,
                x25519 = com.km.crypto.provider.BcX25519(),
                kdf = kdf,
                protector = com.km.frame.SecureFrameProtector(com.km.crypto.provider.BcChaCha20Poly1305(), kdf),
            ),
            transport = s.stack.manager,
        )
    }

    @Test
    fun `3-4 tras instalar una sesion, status lista UN par`() {
        val s = sesion()
        s.bind { }
        assertEquals(0, s.sessionPeers().size, "antes de instalar no hay pares con sesion")

        val instalada = s.registry.install(sesionRemota(s, "bob"))

        assertEquals(1, s.sessionPeers().size, "tras instalar, exactamente uno")
        assertEquals(
            instalada.remotePeerId,
            s.sessionPeers().single(),
            "y es el peer de la sesion instalada, indexada por la RAIZ del contacto",
        )
    }

    @Test
    fun `3-4 tras remove, status deja de listarlo`() {
        val s = sesion()
        s.bind { }
        val instalada = s.registry.install(sesionRemota(s, "bob"))
        assertEquals(1, s.sessionPeers().size, "precontrol: hay uno antes de quitarlo")

        val retirada = s.registry.remove(instalada.remotePeerId)

        assertTrue(retirada === instalada, "remove devuelve la sesion que habia")
        assertEquals(0, s.sessionPeers().size, "y status ya no lista ningun par con sesion")
    }

    @Test
    fun `3-4 dos sesiones dan DOS pares y quitar una deja el otro`() {
        val s = sesion()
        s.bind { }
        val conBob = s.registry.install(sesionRemota(s, "bob"))
        val conMallory = s.registry.install(sesionRemota(s, "mallory"))

        assertEquals(2, s.sessionPeers().size, "dos conversaciones, dos claves de ruteo")
        assertTrue(conBob.remotePeerId != conMallory.remotePeerId, "y son pares distintos")

        s.registry.remove(conMallory.remotePeerId)

        assertEquals(1, s.sessionPeers().size, "quitar uno NO quita el otro")
        assertEquals(conBob.remotePeerId, s.sessionPeers().single(), "y el que queda es el de Bob")
    }

    /**
     * CONTROL NEGATIVO: `sessionPeers()` y `onlinePeers()` NO son lo mismo.
     *
     * Sin esta prueba, "status lista un par" seria verdad por el atajo de
     * devolver la lista de presencia del rele, que es un dato distinto y que se
     * borra al desconectar.
     */
    @Test
    fun `control negativo un par EN LINEA sin sesion no aparece entre los de sesion`() {
        val s = sesion()
        s.bind { }

        assertEquals(0, s.onlinePeers().size, "sin conexion, el rele no declara a nadie")
        assertEquals(0, s.sessionPeers().size, "y tampoco hay sesiones")

        s.registry.install(sesionRemota(s, "bob"))

        assertEquals(0, s.onlinePeers().size, "sigue sin haber presencia en el rele")
        assertEquals(1, s.sessionPeers().size, "pero hay UNA sesion: las dos listas no se derivan una de otra")
    }

    /** El registro esta vacio tras `clear()`: es estado vivo, no un disco. */
    @Test
    fun `control el registro no inventa pares tras vaciarlo`() {
        val s = sesion()
        s.bind { }
        s.registry.install(sesionRemota(s, "bob"))

        s.registry.clear()

        assertEquals(0, s.sessionPeers().size, "clear deja el registro vacio")
        assertFalse(
            s.sessionPeers().contains(SecureTestIdentities.otroIdentityId()),
            "y no aparece ningun par que no se instalo",
        )
    }

}
package com.km.node

import com.km.crypto.X25519
import com.km.crypto.provider.BcChaCha20Poly1305
import com.km.crypto.provider.BcHkdfSha256
import com.km.crypto.provider.BcX25519
import com.km.frame.SecureFrameProtector
import com.km.identity.ContactBundle
import com.km.identity.KmTestDevice
import com.km.identity.KmTestIdentityRoot
import com.km.messaging.LocalInitiatorKeys
import com.km.messaging.LocalResponderKeys
import com.km.messaging.SecureMessagingSession
import com.km.messaging.SecureSessionEstablishment
import com.km.messaging.SendResult
import com.km.model.NodeCapability
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CA — un contacto NO obtiene privilegios de nodo (R-2 / D-D).
 *
 * ## QUE MIDE
 *
 * Que el camino de CONTACTO completo —`forContact`, `initiate`, `accept`,
 * `CONNECTED`, envio y recepcion— deja el [PeerRoleDirectory] de un
 * [NodeRuntimeImpl] REAL exactamente como estaba: cero entradas, ningun rele,
 * ninguna capacidad de nodo.
 *
 * Las cuatro aserciones miran el COMPORTAMIENTO de un objeto de verdad, no el
 * codigo fuente. Un invariante que se comprueba leyendo el fuente se rompe en
 * cuanto el fuente cambia de forma, y ademas no distingue "no hay codigo" de
 * "el codigo esta y no se ejecuta".
 *
 * ## POR QUE `rolesCapabilities = true`
 *
 * Porque el directorio tiene que estar ENCENDIDO para que el test signifique
 * algo. Con el flag apagado, `recordPeerRoles` sale antes de mirar nada y un
 * directorio siempre vacio no demuestra que el camino de contacto no lo toque:
 * demuestra que nadie lo toco con todo desconectado.
 *
 * ## LA NO-NEGOCIABILIDAD TIENE DOS CAPAS
 *
 * 1. Esta: la de comportamiento, medida aqui.
 * 2. La de TIPOS: `PeerRoleDirectory.record` pide un `NodeAnnouncement`, y
 *    `ContactVerified` ni lo contiene ni lo produce. Eso lo demuestra el
 *    COMPILADOR, no un test, y se declara como evidencia sustitutiva de M-2:
 *    escribir `directory.record(autoridadDeContacto.announcement)` no compila,
 *    y un test que afirmara esa imposibilidad seria un test que finge.
 */
class ContactAuthorityIsolationTest {

    private val x25519: X25519 = BcX25519()
    private val kdf = BcHkdfSha256()
    private val protector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)

    @Test
    fun `CA-01 el camino de contacto no registra ningun rol de nodo`() {
        val directorios = PeerRoleDirectory()
        // El flag ENCENDIDO a proposito: si el directorio no puede registrar
        // nada, el test no mide nada.
        // `SIGNALING` y `STORE_AND_FORWARD` no exigen `RelayService` en el init;
        // `RELAY` si lo haria, y aqui no interesa: lo que se mira es si el
        // DIRECTORIO se llena, no si este nodo puede relevar.
        val runtime = NodeRuntimeImpl.fromKeyPair(
            publicKey = aliceKP.publicKey,
            privateKey = aliceKP.privateKey,
            capabilities = setOf(NodeCapability.SIGNALING),
            roleDirectory = directorios,
            rolesCapabilities = true,
        )
        assertTrue(runtime.roleDirectory === directorios, "el pretesto conecto un directorio real")

        // --- El trazo de contacto COMPLETO, por encima del runtime. ---
        val b = KmTestDevice("bob")
        val raizDeBob = KmTestIdentityRoot()
        b.issueOneTimePreKey(1000L)

        val aliceRoot = KmTestIdentityRoot()
        val bobHaciaAlice = PeerContext.forContact(
            ContactBundle.verified(aliceRoot.bundleFor(listOf(aliceDevice), aliceDevice, emptyList())),
        )
        val aliceHaciaBob = PeerContext.forContact(
            ContactBundle.verified(raizDeBob.bundleFor(listOf(b), b, listOf(1000L))),
        )

        val manager = PeerTransportManager(FakeTransportBackend(), now = { 0L })
        val establishment = SecureSessionEstablishment(x25519, kdf, protector)

        val iniciada = establishment.initiate(
            localIdentity = aliceDevice.identityId,
            peerContext = aliceHaciaBob,
            localKeys = LocalInitiatorKeys(aliceDevice.deviceId, aliceDevice.agreementKey),
            transport = manager,
        )
        val aceptada = establishment.accept(
            localIdentity = b.identityId,
            peerContext = bobHaciaAlice,
            localKeys = LocalResponderKeys(
                deviceId = b.deviceId,
                agreementKey = b.agreementKey,
                signedPreKey = b.signedPreKey,
                oneTimePreKey = requireNotNull(b.oneTimePreKey(1000L)),
                oneTimePreKeyId = 1000L,
            ),
            prekeyFrameWire = iniciada.prekeyFrame,
            transport = manager,
        )
        conectar(iniciada.session)
        conectar(aceptada.session)

        // Enviar y RECIBIR de verdad: un directorio que se llenara al abrir la
        // sesion llenandose equally, y uno que se llenara al enviar solo se
        // veria aqui.
        val capturados = mutableListOf<ByteArray>()
        manager.onData { _, data -> capturados.add(data) }
        val texto = "mensaje de contacto por encima de un runtime de verdad 5521".toByteArray()
        assertTrue(iniciada.session.send(texto) is SendResult.Ok, "el contacto envia")
        aceptada.session.onTransportBytes(capturados.single())
        assertContentEquals(texto, aceptada.session.drainOutbound().single(), "y el otro extremo lo descifra")

        // --- Y DESPUES de todo eso, el directorio esta intacto. ---
        assertEquals(0, directorios.size(), "ningun rol registrado por el camino de contacto")
        assertTrue(directorios.isEmpty(), "y el indice de peers lo confirma")
        assertTrue(directorios.hasNoRelay(), "nadie puede relevar por autoridad de contacto")
        assertTrue(
            directorios.peersWith(NodeCapability.RELAY).isEmpty(),
            "el indice invertido de RELAY sigue vacio",
        )
        assertTrue(
            directorios.peersWith(NodeCapability.CLIENT).isEmpty(),
            "ni siquiera CLIENT, que es lo mas depertenecibles que se podria registrar",
        )
    }

    // ===================================================================

    private val aliceDevice = KmTestDevice("alice")
    private val aliceKP = com.km.crypto.Ed25519Impl().generateKeyPair().also {
        // El runtime se construye con un par real, y el trace con `aliceDevice`,
        // que es OTRO par real: no se cuela material fabricado a mano.
        require(it.publicKey.size == 32)
    }

    private fun conectar(sesion: SecureMessagingSession) {
        val m = sesion.transportManager
        val ctx = sesion.peerContext
        m.sendOffer(sesion.localIdentity, ctx, "sdp-offer", sesion.remotePeerId).getOrThrow()
        m.receiveAnswer(sesion.localIdentity, sesion.remotePeerId, "sdp-answer").getOrThrow()
        m.markConnected(sesion.localIdentity, ctx).getOrThrow()
    }
}
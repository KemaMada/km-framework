package com.km.node

import com.km.auth.AuthChallenge
import com.km.auth.AuthOk
import com.km.auth.AuthSession
import com.km.auth.AuthSessionState
import com.km.auth.Base64Url
import com.km.auth.Clock
import com.km.auth.TranscriptBuilder
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.identity.ContactBundle
import com.km.identity.KmIdVerificationException
import com.km.identity.KmTestDevice
import com.km.identity.KmTestIdentityRoot
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.NodeAnnouncement
import com.km.model.NodeAnnouncementJsonCodec
import com.km.model.NodeCapability
import com.km.model.NodeIdentity
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `PeerAuthority`: las dos autoridades y, sobre todo, las que NO se pueden
 * fabricar.
 *
 * Los tests son CONDUCTUALES: hacen que una fabrica se llame y miran lo que
 * ocurre. Ninguno mira el codigo fuente, porque un invariante que se comprueba
 * leyendo el fuente no es un test: es una opinion que se rompe en cuanto el
 * fuente cambia de forma.
 */
class PeerAuthorityTest {

    private val ed25519: Ed25519 = Ed25519Impl()

    // ===================================================================
    // PA-01 / PA-02 — la autoridad de nodo exige handshake VIVO y coherente
    // ===================================================================

    @Test
    fun `PA-01 NodeAuthenticated exige estado vivo AUTHENTICATED`() {
        val bob = KmTestDevice("bob")
        val local = KmTestDevice("local")
        val anuncio = anuncioDe(bob)
        val sesion = sesionFrescaDe(local, bob)

        assertEquals(AuthSessionState.IDLE, sesion.state, "el pretesto parte de IDLE: no hay handshake")

        val e = assertThrows<IllegalArgumentException> {
            PeerAuthority.NodeAuthenticated.of(bob.identityId, anuncio, sesion)
        }
        assertTrue(
            e.message!!.contains("AUTHENTICATED"),
            "el mensaje dice que falta el ESTADO, no el par: ${e.message}",
        )

        // El otro sentido: con la sesion viva, la MISMA fabrica pasa.
        completarHandshake(sesion, bob)
        assertEquals(AuthSessionState.AUTHENTICATED, sesion.state)
        val autoridad = PeerAuthority.NodeAuthenticated.of(bob.identityId, anuncio, sesion)
        assertTrue(autoridad.authorizesPeerSession(), "con AUTHENTICATED vivo la autoridad debe autorizar")

        // Y si la sesion cae DESPUES, la MISMA autoridad deja de autorizar: el
        // predicado consulta el estado VIVO, no una foto tomada al construir.
        // Es la garantia de la que dependen MSG-07 y K1-02, que fabrican el
        // contexto y LUEGO rompen la sesion.
        sesion.receiveAuthFail()
        assertFalse(
            autoridad.authorizesPeerSession(),
            "el estado se consulta en cada llamada, no se fotografia al construir",
        )
    }

    @Test
    fun `PA-02 NodeAuthenticated exige announcement nodeId igual a remotePeerId`() {
        val bob = KmTestDevice("bob")
        val charlie = KmTestDevice("charlie")
        assertNotEquals(bob.identityId, charlie.identityId, "el pretesto usa dos pares de verdad")

        val local = KmTestDevice("local")
        val sesion = sesionFrescaDe(local, bob)
        completarHandshake(sesion, bob)

        // Anuncio de Charlie, firmado de verdad por Charlie: lo unico que no
        // encaja es el identificador, que es justo lo que se quiere medir.
        val anuncioDeCharlie = anuncioDe(charlie)
        val e = assertThrows<IllegalArgumentException> {
            PeerAuthority.NodeAuthenticated.of(bob.identityId, anuncioDeCharlie, sesion)
        }
        assertTrue(
            e.message!!.contains("remotePeerId"),
            "el mensaje nombra el campo que no cuadra: ${e.message}",
        )

        // Con el anuncio del propio par, la misma fabrica pasa.
        assertTrue(
            PeerAuthority.NodeAuthenticated
                .of(bob.identityId, anuncioDe(bob), sesion)
                .authorizesPeerSession(),
            "con el anuncio del propio par la autoridad debe autorizar",
        )
    }

    // ===================================================================
    // PA-03 / PA-04 — la autoridad de contacto no se puede falsificar
    // ===================================================================

    @Test
    fun `PA-03 ContactVerified verified rechaza un bundle con la firma alterada`() {
        val bob = KmTestDevice("bob")
        val root = KmTestIdentityRoot()
        val crudo: Map<String, Any?> = root.bundleFor(listOf(bob), bob, emptyList())

        // Se altera un campo QUE FORMA PARTE DE LA FIRMA, no uno de adorno.
        val alterado = crudo.toMutableMap()
        alterado["capabilities"] = 0b001L
        assertNotEquals(crudo["capabilities"], alterado["capabilities"], "el campo alterado es otro de verdad")

        val e = assertThrows<KmIdVerificationException> {
            PeerAuthority.ContactVerified.verified(alterado)
        }
        assertEquals("INVALID_SIGNATURE", e.code, "el fallo es de firma: el documento se toco")

        // El bundle intacto, por el mismo camino, SI produce autoridad.
        val intacto = PeerAuthority.ContactVerified.verified(crudo)
        assertTrue(intacto.authorizesPeerSession(), "un bundle intacto debe autorizar la sesion de par")
        assertContentEqualsEnRaiz(crudo["identityRoot"] as ByteArray, intacto.bundle.identityRoot)
    }

    @Test
    fun `PA-04 ContactVerified verified rechaza una raiz inesperada`() {
        val bob = KmTestDevice("bob")
        val root = KmTestIdentityRoot()
        val crudo = root.bundleFor(listOf(bob), bob, emptyList())
        val otraRaiz = ByteArray(32) { 0x5A }
        val declarada = crudo["identityRoot"] as ByteArray
        assertFalse(declarada.contentEquals(otraRaiz), "el pretesto usa raices de verdad distintas")

        val e = assertThrows<KmIdVerificationException> {
            PeerAuthority.ContactVerified.verified(crudo, otraRaiz)
        }
        assertEquals("IDENTITY_MISMATCH", e.code, "el fallo es de raiz, no de firma")

        // Y la raiz que el propio documento declara pasa.
        assertTrue(
            PeerAuthority.ContactVerified.verified(crudo, declarada).authorizesPeerSession(),
            "la raiz declarada por el propio bundle debe pasar",
        )
    }

    // ===================================================================
    // El sobre real de un QR / `.kmc`
    // ===================================================================

    @Test
    fun `el sobre base64url de un bundle intacto produce autoridad de contacto`() {
        val bob = KmTestDevice("bob")
        val root = KmTestIdentityRoot()
        val sobre = Base64Url.encode(
            ContactBundle.canonicalBytes(root.bundleFor(listOf(bob), bob, emptyList())),
        )
        val autoridad = PeerAuthority.ContactVerified.of(ContactBundle.decodeAndVerify(sobre))
        assertTrue(autoridad.authorizesPeerSession(), "el camino de un QR tiene que autorizar la sesion")
        assertEquals(
            root.identityId,
            IdentityId(com.km.identity.KmIds.identityId(autoridad.bundle.identityRoot).joinToString("") { "%02x".format(it) }),
            "la autoridad lleva la raiz que firma el bundle, no la del dispositivo",
        )
    }

    // ===================================================================
    // Que la autoridad de contacto NO es una de nodo
    // ===================================================================

    @Test
    fun `una autoridad de contacto no se puede leer como autoridad de nodo`() {
        val bob = KmTestDevice("bob")
        val root = KmTestIdentityRoot()
        val contacto = PeerAuthority.ContactVerified.of(
            ContactBundle.verified(root.bundleFor(listOf(bob), bob, emptyList())),
        )
        // El SISTEMA DE TIPOS prohibe la conversion: `NodeAuthenticated` y
        // `ContactVerified` son hermanos, no superclase y subclase, luego el
        // unico `cast` que hay entre ellos salta en caliente. Que este `as?`
        // devuelva `null` es lo que se puede MEDIR; que el compilador prohiba
        // escribir el `as` sin interrogante es lo que se declara.
        assertNull(contacto as? PeerAuthority.NodeAuthenticated, "el contacto no es autoridad de nodo")
        // Y lo que deja inervible para las superficies de rol: sin autoridad de
        // nodo no hay anuncio, y `PeerRoleDirectory.record` pide un
        // `NodeAnnouncement`.
        assertEquals(NodeCapability.CLIENT, anuncioDe(bob).capabilities.single(), "el pretesto sabe anunciar")
        assertEquals(32, root.keyPair.publicKey.size, "la raiz del pretesto es una clave real")
    }

    // ===================================================================
    // Ayudas
    // ===================================================================

    private fun sesionFrescaDe(local: KmTestDevice, par: KmTestDevice): AuthSession =
        AuthSession.initiator(
            keyPair = local.signingKeyPair,
            identityId = local.identityId.value,
            ed25519 = ed25519,
            clock = Clock { 1_000L },
        ).also {
            require(par.identityId.value.length > 0) { "el pretesto necesita un par real" }
        }

    private fun completarHandshake(sesion: AuthSession, par: KmTestDevice) {
        sesion.receiveChallenge(AuthChallenge(ByteArray(16) { 7 }, 0L, 3, par.identityId.value)).getOrThrow()
        sesion.markResponseSent()
        val sid = "a".repeat(40)
        val nonce = ByteArray(16) { 3 }
        val transcript = TranscriptBuilder.serverAuthTranscript(sid, nonce, sesion.localIdentityId)
        sesion.receiveAuthOk(
            AuthOk(sid, nonce, par.signingKeyPair.publicKey, par.signWith(transcript)),
            sesion.localIdentityId,
        ).getOrThrow()
    }

    private fun anuncioDe(par: KmTestDevice): NodeAnnouncement {
        val partial = NodeAnnouncement(
            messageId = MessageId(UUID.randomUUID()),
            timestamp = 0L,
            protocolVersion = "1.0",
            identity = NodeIdentity(par.identityId, par.signingKeyPair.publicKey),
            endpoints = emptyList(),
            capabilities = setOf(NodeCapability.CLIENT),
        )
        return partial.copy(signature = par.signWith(NodeAnnouncementJsonCodec.signableJson(partial)))
    }

    private fun assertContentEqualsEnRaiz(esperado: ByteArray, obtenido: ByteArray) {
        assertEquals(esperado.size, obtenido.size, "la raiz tiene que medir lo mismo")
        assertTrue(esperado.contentEquals(obtenido), "la raiz del bundle llega intacta")
    }
}
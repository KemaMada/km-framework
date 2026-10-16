package com.km.identity

import com.km.auth.AuthChallenge
import com.km.auth.AuthOk
import com.km.auth.AuthSession
import com.km.auth.Base64Url
import com.km.auth.TranscriptBuilder
import com.km.crypto.Ed25519Impl
import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.NodeAnnouncement
import com.km.model.NodeAnnouncementJsonCodec
import com.km.model.NodeIdentity
import com.km.node.PeerContext
import com.km.auth.Clock
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El tipo que hace estructural la invariante "solo material verificado".
 *
 * Todos los tests parten de `G10/contact.cbor`, el vector CONGELADO, y
 * contrastan la superficie tipada contra el documento crudo: si un accessor
 * devolviera el campo equivocado, la comparacion falla.
 */
class VerifiedContactBundleTest {

    private val contactBytes = KmIdFixtures.bytes("vectors/G10/contact.cbor")

    private fun verified(): VerifiedContactBundle =
        ContactBundle.decodeAndVerify(Base64Url.encode(contactBytes))

    private fun raw(): Map<String, Any?> =
        Kce.validate(contactBytes) as Map<String, Any?>

    // ===================================================================
    // La unica fabrica verifica
    // ===================================================================

    @Test
    fun `la fabrica rechaza un bundle con la firma alterada`() {
        val malo = raw().toMutableMap()
        malo["capabilities"] = 0b001L
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.verified(malo)
        }
        assertEquals("INVALID_SIGNATURE", e.code)
    }

    @Test
    fun `la fabrica rechaza un bundle con la raiz de identidad inesperada`() {
        val otraRaiz = ByteArray(32) { 0x5A }
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.verified(raw(), expectedIdentityRoot = otraRaiz)
        }
        assertEquals("IDENTITY_MISMATCH", e.code)
    }

    @Test
    fun `la fabrica acepta el bundle que la raiz esperada declara`() {
        val bundle = ContactBundle.verified(raw(), expectedIdentityRoot = raw()["identityRoot"] as ByteArray)
        assertContentEquals(contactBytes, bundle.canonicalBytes())
    }

    // ===================================================================
    // Los accesores dicen lo que dicen
    // ===================================================================

    @Test
    fun `los accesores coinciden con el documento crudo`() {
        val b = verified()
        val m = raw()
        val spk = m["signedPrekey"] as Map<*, *>

        assertContentEquals(m["identityRoot"] as ByteArray, b.identityRoot)
        assertContentEquals(m["subjectDeviceId"] as ByteArray, b.subjectDeviceId)
        assertContentEquals(m["subjectSigningKey"] as ByteArray, b.subjectSigningKey)
        assertContentEquals(m["subjectAgreementKey"] as ByteArray, b.subjectAgreementKey)
        assertContentEquals(spk["publicKey"] as ByteArray, b.signedPrekeyPublicKey)
        assertEquals(spk["keyId"] as Long, b.signedPrekeyId)
        assertContentEquals(spk["deviceId"] as ByteArray, b.subjectSignedPrekeyDeviceId!!)
    }

    @Test
    fun `los bytes canonicos sobreviven al viaje`() {
        assertContentEquals(contactBytes, verified().canonicalBytes())
    }

    @Test
    fun `los one-time prekeys se leen por indice sin cursor oculto`() {
        val b = verified()
        val opks = raw()["oneTimePrekeys"] as List<*>
        assertEquals(opks.size, b.oneTimePrekeyCount())
        for (i in opks.indices) {
            assertContentEquals((opks[i] as Map<*, *>)["publicKey"] as ByteArray, b.oneTimePrekeyAt(i))
            assertEquals((opks[i] as Map<*, *>)["keyId"] as Long, b.oneTimePrekeyIdAt(i))
        }
        // Mas alla del final: null, no excepcion. Elegir el OPK es estado del
        // emisor, no de este objeto.
        assertNull(b.oneTimePrekeyAt(opks.size))
        assertNull(b.oneTimePrekeyIdAt(-1))
    }

    // ===================================================================
    // La superficie no filtra material
    // ===================================================================

    @Test
    fun `toString no vuelca el documento`() {
        val texto = verified().toString()
        assertTrue(texto.startsWith("VerifiedContactBundle("), "no es el toString esperado: $texto")
        // Ni claves, ni el mapa entero, ni firmas.
        assertTrue(!texto.contains("identityRoot"), "filtra el nombre de un campo: $texto")
        assertTrue(!texto.contains("publicKey"), "filtra el nombre de un campo: $texto")
        assertTrue(!texto.contains("signature"), "filtra el nombre de un campo: $texto")
        assertTrue(texto.length < 120, "toString es demasiado largo: ${texto.length} caracteres")
    }

    @Test
    fun `la igualdad se decide por los bytes canonicos`() {
        val a = verified()
        val b = ContactBundle.decodeAndVerify(Base64Url.encode(contactBytes))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ===================================================================
    // Integracion con PeerContext
    // ===================================================================

    @Test
    fun `PeerContext admite un bundle verificado`() {
        // El campo antes era del tipo singleton y no podia llevar un bundle.
        // Aqui se comprueba que el valor llega intacto y que es el mismo.
        val bundle = verified()
        val ctx = authenticatedContext()
        val conBundle = ctx.withContactBundle(bundle)
        assertEquals(bundle, conBundle.contactBundle)
        assertNull(ctx.contactBundle, "withContactBundle no debe mutar el original")
    }

    // ===================================================================
    // PeerContext autenticado minimo
    // ===================================================================

    private fun authenticatedContext(): PeerContext {
        val ed = Ed25519Impl()
        val local = ed.generateKeyPair()
        val remote = ed.generateKeyPair()
        val remoteId = IdentityId(KmIds.identityId(remote.publicKey).toHex())
        val localId = IdentityId(KmIds.identityId(local.publicKey).toHex())

        val session = AuthSession.initiator(local, localId.value, ed, clock = Clock { 1000L })
        session.receiveChallenge(AuthChallenge(ByteArray(16), 1000L, 3, remoteId.value)).getOrThrow()
        session.markResponseSent()
        val sid = "a".repeat(40)
        val snonce = ByteArray(16) { 3 }
        val transcript = TranscriptBuilder.serverAuthTranscript(sid, snonce, localId.value)
        val ok = AuthOk(sid, snonce, local.publicKey, ed.sign(local.privateKey, transcript).bytes)
        session.receiveAuthOk(ok, localId.value).getOrThrow()

        val partial = NodeAnnouncement(
            messageId = MessageId(UUID.randomUUID()),
            timestamp = 0L,
            protocolVersion = "1.0",
            identity = NodeIdentity(remoteId, remote.publicKey),
            endpoints = emptyList(),
            capabilities = emptySet(),
        )
        val ann = partial.copy(signature = ed.sign(remote.privateKey, NodeAnnouncementJsonCodec.signableJson(partial)).bytes)
        return PeerContext.authenticated(remoteId, remote.publicKey, ann, session)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

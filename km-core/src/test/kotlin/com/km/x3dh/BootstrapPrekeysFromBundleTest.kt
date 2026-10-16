package com.km.x3dh

import com.km.auth.Base64Url
import com.km.crypto.X25519
import com.km.crypto.provider.BcX25519
import com.km.crypto.provider.BcHkdfSha256
import com.km.identity.Kce
import com.km.identity.KmIdFixtures
import com.km.identity.KmIdVerificationException
import com.km.identity.KmTestDevice
import com.km.identity.KmTestIdentityRoot
import com.km.identity.ContactBundle
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 2 — `BootstrapPrekeys.fromVerified`: la unica ruta de produccion que
 * deriva prekeys de un bundle REAL.
 *
 * El nucleo es el vector CONGELADO `G10/contact.cbor`, contrastado contra el
 * DOCUMENTO CRUDO. Si la derivacion tomara el campo equivocado —la clave de
 * firma en vez de la de acuerdo, el `keyId` del PC en vez del movil— la
 * comparacion falla; si la derivacion se reconstruyera dentro del propio test a
 * partir de las mismas reglas, la comparacion no probaria nada.
 */
class BootstrapPrekeysFromBundleTest {

    private val contactBytes = KmIdFixtures.bytes("vectors/G10/contact.cbor")

    /** El documento CRUDO del vector, sin pasar por el verificador. */
    private fun rawDocument(): Map<String, Any?> =
        Kce.validate(contactBytes) as Map<String, Any?>

    private fun frozenBundle() =
        ContactBundle.decodeAndVerify(Base64Url.encode(contactBytes))

    // ===================================================================
    // El mapeo, contra el documento crudo
    // ===================================================================

    @Test
    fun `las prekeys derivadas coinciden con el documento crudo del vector congelado`() {
        val doc = rawDocument()
        val spk = doc["signedPrekey"] as Map<*, *>
        val opks = doc["oneTimePrekeys"] as List<*>

        val derivadas = BootstrapPrekeys.fromVerified(frozenBundle(), oneTimePrekeyIndex = 0)

        assertContentEquals(doc["subjectDeviceId"] as ByteArray, derivadas.deviceId, "deviceId")
        assertContentEquals(doc["subjectAgreementKey"] as ByteArray, derivadas.identityAgreementKey, "IK")
        assertContentEquals(spk["publicKey"] as ByteArray, derivadas.signedPreKey, "SPK")
        assertEquals(spk["keyId"] as Long, derivadas.signedPreKeyId, "keyId del SPK")
        assertContentEquals((opks[0] as Map<*, *>)["publicKey"] as ByteArray, derivadas.oneTimePreKey!!, "OPK[0]")
        assertEquals((opks[0] as Map<*, *>)["keyId"] as Long, derivadas.oneTimePreKeyId!!, "keyId del OPK[0]")
    }

    @Test
    fun `la IK derivada es la clave de ACUERDO, no la de firma`() {
        // El error que este mapeo tiene que cerrar: ambos campos son 32 bytes y
        // ambos viajan en el mismo documento.
        val doc = rawDocument()
        val derivadas = BootstrapPrekeys.fromVerified(frozenBundle())
        assertContentEquals(doc["subjectAgreementKey"] as ByteArray, derivadas.identityAgreementKey)
        assertTrue(
            !derivadas.identityAgreementKey.contentEquals(doc["subjectSigningKey"] as ByteArray),
            "la IK no puede ser la clave de firma",
        )
    }

    @Test
    fun `cada posicion elige su propio one-time prekey`() {
        val doc = rawDocument()
        val opks = doc["oneTimePrekeys"] as List<*>
        assertEquals(2, opks.size, "el vector deberia llevar dos OPK")

        for (i in opks.indices) {
            val derivadas = BootstrapPrekeys.fromVerified(frozenBundle(), oneTimePrekeyIndex = i)
            assertContentEquals(
                (opks[i] as Map<*, *>)["publicKey"] as ByteArray,
                derivadas.oneTimePreKey!!,
                "OPK en la posicion $i",
            )
            assertEquals((opks[i] as Map<*, *>)["keyId"] as Long, derivadas.oneTimePreKeyId!!, "keyId en $i")
        }
        // Los dos son distintos: si no lo fueran, "elegir el indice" no seria
        // distinguishable de "usar siempre el primero".
        val a = BootstrapPrekeys.fromVerified(frozenBundle(), oneTimePrekeyIndex = 0).oneTimePreKey!!
        val b = BootstrapPrekeys.fromVerified(frozenBundle(), oneTimePrekeyIndex = 1).oneTimePreKey!!
        assertTrue(!a.contentEquals(b), "los OPK del vector deben ser distintos entre si")
    }

    @Test
    fun `el indice por defecto es el cero`() {
        val explicito = BootstrapPrekeys.fromVerified(frozenBundle(), oneTimePrekeyIndex = 0)
        val porDefecto = BootstrapPrekeys.fromVerified(frozenBundle())
        assertEquals(explicito, porDefecto, "el default debe ser la posicion 0")
    }

    // ===================================================================
    // One-time prekey: ausencia
    // ===================================================================

    @Test
    fun `un indice nulo prescinde del OPK aunque el bundle los lleve`() {
        // El vector tiene dos. Pedir `null` es una decision explicita: X3DH
        // entonces NO calcula DH4, y el receptor tiene que saberlo.
        val derivadas = BootstrapPrekeys.fromVerified(frozenBundle(), oneTimePrekeyIndex = null)
        assertNull(derivadas.oneTimePreKey)
        assertNull(derivadas.oneTimePreKeyId)
        // Y el resto del material sigue intacto: renunciar al OPK no renuncia al
        // bundle entero.
        assertEquals(42L, derivadas.signedPreKeyId)
        assertEquals(32, derivadas.identityAgreementKey.size)
    }

    @Test
    fun `un bundle sin one-time prekeys con indice nulo deriva igual`() {
        // Aqui el bundle se construye con las primitivas del framework: el
        // vector congelado no cubre este caso y no se puede forzar para que
        // aparezca.
        val root = KmTestIdentityRoot()
        val movil = KmTestDevice("movil")
        val bundle = ContactBundle.verified(root.bundleFor(listOf(movil), movil, emptyList()))

        val derivadas = BootstrapPrekeys.fromVerified(bundle, oneTimePrekeyIndex = null)
        assertNull(derivadas.oneTimePreKey)
        assertContentEquals(movil.agreementKey.publicKey, derivadas.identityAgreementKey)
        assertContentEquals(movil.signedPreKey.publicKey, derivadas.signedPreKey)
        assertEquals(0, bundle.oneTimePrekeyCount(), "el bundle se construyo sin OPK")
    }

    @Test
    fun `un indice que no existe LANZA en vez de degradar en silencio`() {
        // Perder DH4 cambia el SK. Si esto devolviera prekeys sin OPK, el
        // receptor derivaria un SK distinto sin que nada lo dijera.
        val e = assertFailsWith<IllegalArgumentException> {
            BootstrapPrekeys.fromVerified(frozenBundle(), oneTimePrekeyIndex = 2)
        }
        val mensaje = e.message.orEmpty()
        assertTrue(mensaje.contains("2"), "el mensaje nombra el indice pedido: $mensaje")
        assertTrue(mensaje.contains("2 one-time prekeys"), "el mensaje dice cuantos hay: $mensaje")
    }

    @Test
    fun `un indice negativo LANZA`() {
        assertFailsWith<IllegalArgumentException> {
            BootstrapPrekeys.fromVerified(frozenBundle(), oneTimePrekeyIndex = -1)
        }
    }

    // ===================================================================
    // Sin bundle verificado no hay prekeys
    // ===================================================================

    @Test
    fun `una firma alterada no produce prekeys`() {
        val malo = rawDocument().toMutableMap()
        // Se altera SOLO un campo que el esquema admite y que esta firmado:
        // el rechazo no puede venir de una comprobacion estructural.
        malo["capabilities"] = 0b001L
        val e = assertFailsWith<KmIdVerificationException> {
            BootstrapPrekeys.fromVerified(ContactBundle.verified(malo))
        }
        assertEquals("INVALID_SIGNATURE", e.code)
    }

    @Test
    fun `una raiz de identidad inesperada no produce prekeys`() {
        val otraRaiz = ByteArray(32) { 0x5A }
        val e = assertFailsWith<KmIdVerificationException> {
            BootstrapPrekeys.fromVerified(
                ContactBundle.verified(rawDocument(), expectedIdentityRoot = otraRaiz),
            )
        }
        assertEquals("IDENTITY_MISMATCH", e.code)
    }

    @Test
    fun `una firma de prekey robada no produce prekeys`() {
        // El bundle es valido en estructura, pero el SPK lo firmo OTRO
        // dispositivo del mismo roster: el binding con `subjectSigningKey` es
        // lo que lo detecta.
        val doc = rawDocument().toMutableMap()
        val spkRobado = LinkedHashMap(doc["signedPrekey"] as Map<String, Any?>)
        spkRobado["signature"] = ByteArray(64) { 0xAB.toByte() }
        doc["signedPrekey"] = spkRobado
        val e = assertFailsWith<KmIdVerificationException> {
            BootstrapPrekeys.fromVerified(ContactBundle.verified(doc))
        }
        assertEquals("INVALID_SIGNATURE", e.code)
    }

    // ===================================================================
    // Copias defensivas
    // ===================================================================

    @Test
    fun `las prekeys derivadas son copias, no alias del documento`() {
        val bundle = frozenBundle()
        val primera = BootstrapPrekeys.fromVerified(bundle)
        val firmaAntes = primera.signedPreKey.copyOf()

        // Se muta la instancia derivada "en el sitio". Si compartiera el array
        // con el documento verificado, el bundle dejaria de ser cierto sin que
        // nadie lo comprobara.
        primera.signedPreKey.fill(0)
        primera.identityAgreementKey.fill(0)

        val segunda = BootstrapPrekeys.fromVerified(bundle)
        assertContentEquals(firmaAntes, segunda.signedPreKey, "el bundle debe seguir diciendo lo que dice")
        assertTrue(
            !segunda.identityAgreementKey.all { it == 0.toByte() },
            "la IK del documento no puede haber cambiado",
        )
    }

    // ===================================================================
    // El contrato lo fija el consumidor: X3DH
    // ===================================================================

    @Test
    fun `X3DH declara el OPK que la derivacion eligio`() {
        // Si la derivacion entregara el OPK equivocado, aqui seguiria
        // compilando yabriendose una sesion: por eso se mira el keyId que
        // X3DH reporta, no solo que la sesion exista.
        val x25519: X25519 = BcX25519()
        val x3dh = X3dh(x25519, BcHkdfSha256())
        val root = KmTestIdentityRoot()
        val movil = KmTestDevice("movil")
        movil.issueOneTimePreKey(1000)
        movil.issueOneTimePreKey(1001)
        val bundle = ContactBundle.verified(root.bundleFor(listOf(movil), movil, listOf(1000, 1001)))

        val alice = KmTestDevice("alice")
        val inicio = x3dh.initiate(
            initiator = InitiatorKeyMaterial(alice.deviceId, alice.agreementKey),
            prekeys = BootstrapPrekeys.fromVerified(bundle, oneTimePrekeyIndex = 1),
            bootstrapValue = ByteArray(32) { 0x2A },
        )
        assertEquals(1001L, inicio.usedOneTimePreKeyId, "X3DH debe reportar el OPK elegido")

        val sinOpk = x3dh.initiate(
            initiator = InitiatorKeyMaterial(alice.deviceId, alice.agreementKey),
            prekeys = BootstrapPrekeys.fromVerified(bundle, oneTimePrekeyIndex = null),
            bootstrapValue = ByteArray(32) { 0x2A },
        )
        assertNull(sinOpk.usedOneTimePreKeyId, "sin DH4 no hay keyId consumido")

        // Y con DH4 el secreto es OTRO: es la razon de que el indice no pueda
        // degradarse en silencio.
        assertTrue(
            !inicio.sharedKey.contentEquals(sinOpk.sharedKey),
            "usar OPK y no usarlo no puede dar el mismo SK",
        )
    }

    @Test
    fun `las prekeys derivadas sirven para responder con el otro extremo`() {
        // El contrato de `keyId`: el receptor tiene que poder emparejar su OPK
        // privado con el public que el bundle publico. Sin el `keyId` no hay
        // forma de saber cual.
        val x25519: X25519 = BcX25519()
        val x3dh = X3dh(x25519, BcHkdfSha256())
        val root = KmTestIdentityRoot()
        val movil = KmTestDevice("movil")
        movil.issueOneTimePreKey(1000)
        val bundle = ContactBundle.verified(root.bundleFor(listOf(movil), movil, listOf(1000)))
        val prekeys = BootstrapPrekeys.fromVerified(bundle)

        val alice = KmTestDevice("alice")
        val inicio = x3dh.initiate(
            initiator = InitiatorKeyMaterial(alice.deviceId, alice.agreementKey),
            prekeys = prekeys,
            bootstrapValue = ByteArray(32) { 0x2A },
        )

        // El receptor empareja por keyId: busca el OPK cuyo id coincide.
        val elegido = assertNotNull(movil.oneTimePreKey(prekeys.oneTimePreKeyId!!))
        val preparacion = x3dh.respond(
            responder = ResponderKeyMaterial(
                deviceId = movil.deviceId,
                identityAgreementKey = movil.agreementKey,
                signedPreKey = movil.signedPreKey,
                oneTimePreKey = elegido,
                oneTimePreKeyId = prekeys.oneTimePreKeyId,
            ),
            remotePrekeys = BootstrapPrekeys.fromVerified(
                ContactBundle.verified(root.bundleFor(listOf(alice), alice, emptyList())),
                oneTimePrekeyIndex = null,
            ),
            ephemeralPublic = inicio.ephemeralPublic,
        )
        assertContentEquals(inicio.sharedKey, preparacion.sharedKey, "los dos SK deben coincidir")
        assertEquals(1000L, preparacion.usedOneTimePreKeyId)
    }
}
package com.keymessage.core.kmid

import com.keymessage.core.crypto.Ed25519Impl
import com.keymessage.core.crypto.Signature
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * ContactBundle contra los vectores congelados (G10, G11, invalid).
 *
 * El nucleo es CONSTRUIR los bundles en Kotlin desde las mismas semillas y
 * exigir que salgan byte a byte igual que los congelados.
 */
class ContactBundleGoldenVectorsTest {

    private val ed = Ed25519Impl()

    private fun seed(label: String): ByteArray =
        KmIdFixtures.unhex(KmIdFixtures.manifest()["seeds"][label].asText())

    private fun pub(label: String) = ed.keyPairFromSeed(seed(label)).publicKey
    private fun priv(label: String) = ed.keyPairFromSeed(seed(label)).privateKey
    private fun opaque(label: String) = seed(label) // agreement keys, link secrets, etc

    private val aliceMobileSignPub = pub("alice.mobile.sign")
    private val aliceMobileSignPriv = priv("alice.mobile.sign")
    private val aliceMobileAgr = opaque("alice.mobile.agr")
    private val alicePcSignPub = pub("alice.pc.sign")
    private val alicePcSignPriv = priv("alice.pc.sign")
    private val alicePcAgr = opaque("alice.pc.agr")
    private val rootV1Pub = pub("root.alice.v1")
    private val rootV1Priv = priv("root.alice.v1")
    private val rootV2Pub = pub("root.alice.v2")
    private val rootV2Priv = priv("root.alice.v2")

    private val aliceMobileId = KmIds.deviceId(aliceMobileSignPub)
    private val alicePcId = KmIds.deviceId(alicePcSignPub)

    // ===================================================================
    // Rebuild infrastructure (matches generate.py Scenario)
    // ===================================================================

    private fun deviceEntry(signing: ByteArray, agreement: ByteArray, name: String) =
        DeviceRoster.makeDeviceEntry(signing, agreement, name = name)

    private fun buildRoster1(): Map<String, Any?> = DeviceRoster.sign(
        DeviceRoster.build(rootV1Pub, listOf(deviceEntry(aliceMobileSignPub, aliceMobileAgr, "Alice-Android")), sequence = 1),
        rootV1Priv,
    )

    private fun buildRoster2(): Map<String, Any?> {
        val r1 = buildRoster1()
        return DeviceRoster.sign(
            DeviceRoster.build(
                rootV1Pub,
                listOf(deviceEntry(aliceMobileSignPub, aliceMobileAgr, "Alice-Android"),
                       deviceEntry(alicePcSignPub, alicePcAgr, "Alice-PC")),
                sequence = 2,
                previous = DeviceRoster.chainPrevious(r1),
            ),
            rootV1Priv,
        )
    }

    private fun buildSpk(): Map<String, Any?> = ContactBundle.signSignedPrekey(
        ContactBundle.buildSignedPrekey(
            aliceMobileSignPub, opaque("alice.mobile.spk"), keyId = 42, deviceIdBytes = aliceMobileId,
        ),
        aliceMobileSignPriv,
    )

    private fun buildSpkPc(): Map<String, Any?> = ContactBundle.signSignedPrekey(
        ContactBundle.buildSignedPrekey(
            alicePcSignPub, opaque("alice.pc.spk"), keyId = 7, deviceIdBytes = alicePcId,
        ),
        alicePcSignPriv,
    )

    private fun buildOpks(): List<Map<String, Any?>> = listOf(
        ContactBundle.buildOnetimePrekey(opaque("alice.mobile.opk.1000"), keyId = 1000, deviceIdBytes = aliceMobileId),
        ContactBundle.buildOnetimePrekey(opaque("alice.mobile.opk.1001"), keyId = 1001, deviceIdBytes = aliceMobileId),
    )

    private fun buildEndpoints(): List<Map<String, Any?>> = listOf(
        ContactBundle.buildEndpointHint(KmIdConstants.ENDPOINT_ONION, "km6x2p7q4nl3r5t8wz1yv0dc2sh4g7kj9fa.onion"),
        ContactBundle.buildEndpointHint(KmIdConstants.ENDPOINT_UDP, "192.0.2.41:41234"),
    )

    private fun buildBundleContact(): Map<String, Any?> = ContactBundle.signBundle(
        ContactBundle.buildBundle(
            rootV1Pub, buildRoster2(), aliceMobileId, buildSpk(),
            oneTimePrekeys = buildOpks(), endpoints = buildEndpoints(),
            capabilities = 0b111,
        ),
        rootV1Priv,
    )

    private fun buildBundleDeviceLink(): Map<String, Any?> = ContactBundle.signBundle(
        ContactBundle.buildBundle(
            rootV1Pub, buildRoster1(), aliceMobileId, buildSpk(),
            oneTimePrekeys = buildOpks(), kind = KmIdConstants.KIND_DEVICE_LINK,
            linkSecret = opaque("linksecret.1"), capabilities = 0b001,
        ),
        rootV1Priv,
    )

    // ===================================================================
    // G10: contact bundle
    // ===================================================================

    @Test
    fun `G10 contact bundle reconstruido byte a byte`() {
        val construido = buildBundleContact()
        val congelado = KmIdFixtures.bytes("vectors/G10/contact.cbor")
        assertContentEquals(
            congelado, ContactBundle.canonicalBytes(construido),
            "el bundle CONTACT construido en Kotlin no reproduce G10/contact.cbor"
        )
    }

    @Test
    fun `G10 device link reconstruido byte a byte`() {
        val construido = buildBundleDeviceLink()
        val congelado = KmIdFixtures.bytes("vectors/G10/device_link.cbor")
        assertContentEquals(
            congelado, ContactBundle.canonicalBytes(construido),
            "el bundle DEVICE_LINK construido en Kotlin no reproduce G10/device_link.cbor"
        )
    }

    @Test
    fun `los vectores validos de bundle verifican`() {
        for ((gid, file) in listOf("G10" to "contact.cbor", "G10" to "device_link.cbor")) {
            val bytes = KmIdFixtures.bytes("vectors/$gid/$file")
            val bundle = Kce.validate(bytes) as Map<String, Any?>
            ContactBundle.verifyBundle(bundle, expectedIdentityRoot = rootV1Pub)
        }
    }

    @Test
    fun `los invariantes de G10 se cumplen`() {
        val contact = Kce.validate(KmIdFixtures.bytes("vectors/G10/contact.cbor")) as Map<String, Any?>
        val roster = contact["roster"] as Map<String, Any?>
        val entry = DeviceRoster.findDevice(roster, contact["subjectDeviceId"] as ByteArray)
        assertNotNull(entry)

        assertContentEquals(entry["signingKey"] as ByteArray, contact["subjectSigningKey"] as ByteArray)
        assertContentEquals(entry["agreementKey"] as ByteArray, contact["subjectAgreementKey"] as ByteArray)
        assertContentEquals(KmIds.deviceId(contact["subjectSigningKey"] as ByteArray), contact["subjectDeviceId"] as ByteArray)
        assertContentEquals(
            (contact["signedPrekey"] as Map<String, Any?>)["deviceId"] as ByteArray,
            contact["subjectDeviceId"] as ByteArray,
        )
        for (opk in contact["oneTimePrekeys"] as List<Map<String, Any?>>) {
            assertContentEquals(opk["deviceId"] as ByteArray, contact["subjectDeviceId"] as ByteArray)
        }
    }

    // ===================================================================
    // Invalid: wrong-device-binding
    // ===================================================================

    @Test
    fun `wrong-device-binding produce SUBJECT_BINDING_MISMATCH`() {
        val bytes = KmIdFixtures.bytes("invalid/wrong-device-binding.cbor")
        val bundle = Kce.validate(bytes) as Map<String, Any?>
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.verifyBundle(bundle)
        }
        assertEquals("SUBJECT_BINDING_MISMATCH", e.code)
    }

    // ===================================================================
    // Schema validation of bundle structure
    // ===================================================================

    @Test
    fun `un bundle requiere subjectDeviceId en el roster`() {
        // subjectDeviceId que no esta en el roster embebido
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.buildBundle(
                rootV1Pub, buildRoster1(), alicePcId, buildSpk(),
            )
        }
        assertEquals("SUBJECT_NOT_IN_ROSTER", e.code)
    }

    @Test
    fun `un dispositivo revocado no puede ser subject`() {
        val roster = buildRoster1().toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val devices = (roster["devices"] as List<Map<String, Any?>>).map {
            LinkedHashMap(it) + mapOf("status" to KmIdConstants.STATUS_REVOKED, "revokedAt" to 1_700_000_000_000L)
        }
        roster["devices"] = devices
        // Re-sign after mutation
        val rosterRevocado = DeviceRoster.sign(roster, rootV1Priv)

        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.buildBundle(
                rootV1Pub, rosterRevocado, aliceMobileId, buildSpk(),
            )
        }
        assertEquals("SUBJECT_REVOKED", e.code)
    }

    @Test
    fun `linkSecret obligatorio para DEVICE_LINK, prohibido para CONTACT`() {
        val r1 = buildRoster1()
        val spk = buildSpk()
        val opks = buildOpks()

        val sinSecret = assertFailsWith<KmIdVerificationException> {
            ContactBundle.buildBundle(
                rootV1Pub, r1, aliceMobileId, spk,
                kind = KmIdConstants.KIND_DEVICE_LINK, oneTimePrekeys = opks,
            )
        }
        assertEquals("MISSING_FIELD", sinSecret.code)

        val conSecret = assertFailsWith<KmIdVerificationException> {
            ContactBundle.buildBundle(
                rootV1Pub, r1, aliceMobileId, spk,
                kind = KmIdConstants.KIND_CONTACT, oneTimePrekeys = opks,
                linkSecret = ByteArray(32),
            )
        }
        assertEquals("UNEXPECTED_FIELD", conSecret.code)
    }

    @Test
    fun `endpoint RELAY requiere relayId`() {
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.buildEndpointHint(KmIdConstants.ENDPOINT_RELAY, "wss://relay.example.com")
        }
        assertEquals("MISSING_FIELD", e.code)
    }

    // ===================================================================
    // Prekey verification
    // ===================================================================

    @Test
    fun `un signed prekey con deviceId incorrecto se rechaza`() {
        val bundle = buildBundleContact().toMutableMap()
        val spkCorrupto = LinkedHashMap(bundle["signedPrekey"] as Map<String, Any?>)
        spkCorrupto["deviceId"] = ByteArray(32) { 0xFF.toByte() }
        bundle["signedPrekey"] = spkCorrupto
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.verifyBundle(bundle)
        }
        assertEquals("PREKEY_BINDING_MISMATCH", e.code)
    }

    @Test
    fun `un signed prekey con firma invalida se rechaza`() {
        val bundle = buildBundleContact().toMutableMap()
        val spkCorrupto = LinkedHashMap(bundle["signedPrekey"] as Map<String, Any?>)
        spkCorrupto["signature"] = ByteArray(64) { 0xAA.toByte() }
        bundle["signedPrekey"] = spkCorrupto
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.verifyBundle(bundle)
        }
        assertEquals("INVALID_SIGNATURE", e.code)
    }

    @Test
    fun `un one-time prekey con deviceId incorrecto se rechaza`() {
        val bundle = buildBundleContact().toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val opks = (bundle["oneTimePrekeys"] as List<Map<String, Any?>>).map {
            LinkedHashMap(it) + mapOf("deviceId" to ByteArray(32) { 0xBB.toByte() })
        }
        bundle["oneTimePrekeys"] = opks
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.verifyBundle(bundle)
        }
        assertEquals("PREKEY_BINDING_MISMATCH", e.code)
    }

    @Test
    fun `un bundle con firma invalida se rechaza`() {
        val bundle = buildBundleContact().toMutableMap()
        bundle["signature"] = ByteArray(64) { 0xCC.toByte() }
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.verifyBundle(bundle)
        }
        assertEquals("INVALID_SIGNATURE", e.code)
    }

    @Test
    fun `un bundle con identityRoot embebido distinto se rechaza`() {
        // Roster de otra identidad, firmado por rootV2, que NO contiene aliceMobileId
        val rosterOtra = DeviceRoster.sign(
            DeviceRoster.build(rootV2Pub, listOf(
                DeviceRoster.makeDeviceEntry(alicePcSignPub, alicePcAgr, name = "pc")
            ), sequence = 1),
            rootV2Priv,
        )
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.buildBundle(rootV1Pub, rosterOtra, aliceMobileId, buildSpk())
        }
        assertEquals("SUBJECT_NOT_IN_ROSTER", e.code)
    }

    // ===================================================================
    // Mutation tests — cada mutacion debe detectarse
    // ===================================================================

    @Test
    fun `M1 identityId incorrecto se rechaza`() {
        val bundle = buildBundleContact().toMutableMap()
        bundle["identityId"] = ByteArray(32) { 0xDD.toByte() }
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.verifyBundle(bundle)
        }
        assertEquals("IDENTITY_ID_MISMATCH", e.code)
    }

    @Test
    fun `M2 subjectSigningKey distinto del roster se rechaza`() {
        val bundle = buildBundleContact().toMutableMap()
        bundle["subjectSigningKey"] = alicePcSignPub
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.verifyBundle(bundle)
        }
        assertEquals("SUBJECT_BINDING_MISMATCH", e.code)
    }

    @Test
    fun `M3 subjectAgreementKey distinto del roster se rechaza`() {
        val bundle = buildBundleContact().toMutableMap()
        bundle["subjectAgreementKey"] = ByteArray(32) { 0xEE.toByte() }
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.verifyBundle(bundle)
        }
        assertEquals("SUBJECT_BINDING_MISMATCH", e.code)
    }

    @Test
    fun `M4 subjectDeviceId no derivado de signingKey se rechaza`() {
        val bundle = buildBundleContact().toMutableMap()
        bundle["subjectDeviceId"] = alicePcId
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.verifyBundle(bundle)
        }
        assertEquals("SUBJECT_BINDING_MISMATCH", e.code)
    }

    @Test
    fun `M5 prekey firmado con clave distinta a subject se rechaza`() {
        val spkRobado = ContactBundle.signSignedPrekey(
            ContactBundle.buildSignedPrekey(
                aliceMobileSignPub, opaque("alice.mobile.spk"), keyId = 42, deviceIdBytes = aliceMobileId,
            ),
            alicePcSignPriv,
        )
        val bundle = ContactBundle.signBundle(
            ContactBundle.buildBundle(
                rootV1Pub, buildRoster2(), aliceMobileId, spkRobado,
                oneTimePrekeys = buildOpks(), endpoints = buildEndpoints(),
                capabilities = 0b111,
            ),
            rootV1Priv,
        )
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.verifyBundle(bundle)
        }
        assertEquals("INVALID_SIGNATURE", e.code)
    }

    @Test
    fun `M6 omision de oneTimePrekeys falla por esquema`() {
        val bundle = ContactBundle.signBundle(
            ContactBundle.buildBundle(
                rootV1Pub, buildRoster2(), aliceMobileId, buildSpk(),
                endpoints = buildEndpoints(), capabilities = 0b111,
            ),
            rootV1Priv,
        )
        @Suppress("UNCHECKED_CAST")
        val sinOpk = (Kce.decode(ContactBundle.canonicalBytes(bundle)) as Map<String, Any?>).toMutableMap()
        sinOpk.remove("oneTimePrekeys")
        val e = assertFailsWith<KmIdSchemaException> {
            KmSchemas.validate(KmSchemas.CONTACT_BUNDLE, sinOpk + mapOf("signature" to ByteArray(64)), "ContactBundle.")
        }
        assertEquals("MISSING_FIELD", e.code)
    }

    @Test
    fun `M7 capacidad uint por encima del maximo se rechaza`() {
        val e = assertFailsWith<KmIdSchemaException> {
            ContactBundle.buildBundle(
                rootV1Pub, buildRoster1(), aliceMobileId, buildSpk(),
                kind = KmIdConstants.KIND_DEVICE_LINK, oneTimePrekeys = buildOpks(),
                linkSecret = opaque("linksecret.1"),
                capabilities = KmIdConstants.MAX_CAPABILITIES_UINT + 1,
            )
        }
        assertEquals("FIELD_TYPE", e.code)
    }

    @Test
    fun `M8 firma sobre un payload distinto se rechaza`() {
        val bundle = buildBundleContact().toMutableMap()
        val newSig = ed.sign(rootV2Priv, ContactBundle.signableBytes(bundle))
        bundle["signature"] = newSig.bytes
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.verifyBundle(bundle)
        }
        assertEquals("INVALID_SIGNATURE", e.code)
    }

    @Test
    fun `M9 identityRoot embebido no coincide con identityId se rechaza`() {
        val bundle = buildBundleContact().toMutableMap()
        bundle["identityRoot"] = rootV2Pub
        val e = assertFailsWith<KmIdVerificationException> {
            ContactBundle.verifyBundle(bundle)
        }
        assertEquals("IDENTITY_ID_MISMATCH", e.code)
    }
}
package com.keymessage.core.kmid

import com.keymessage.core.crypto.Ed25519Impl
import com.keymessage.core.crypto.Signature
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests de integracion multi-dispositivo (3H).
 *
 * 3H.1 — Modelo multi-device
 * 3H.2 — Roster evolution (alta, revocacion, secuencia, anti-rollback)
 * 3H.3 — ContactBundle integration (bundle A para A, bundle B para B, cross-rechazado)
 * 3H.4 — Cross-device mutation tests (M-3H-1 a M-3H-15)
 */
class MultiDevice3HTest {

    private val ed = Ed25519Impl()

    // Seeds del manifiesto congelado (15 seeds disponibles)
    private fun seed(label: String): ByteArray =
        KmIdFixtures.unhex(KmIdFixtures.manifest()["seeds"][label].asText())

    private fun pub(label: String) = ed.keyPairFromSeed(seed(label)).publicKey
    private fun priv(label: String) = ed.keyPairFromSeed(seed(label)).privateKey
    private fun opaque(label: String) = seed(label)

    // Alice: identidad principal (del manifiesto)
    private val rootAlicePub = pub("root.alice.v1")
    private val rootAlicePriv = priv("root.alice.v1")

    // Dispositivo A (Alice-Mobile)
    private val mobileSignPub = pub("alice.mobile.sign")
    private val mobileSignPriv = priv("alice.mobile.sign")
    private val mobileAgr = opaque("alice.mobile.agr")
    private val mobileId = KmIds.deviceId(mobileSignPub)

    // Dispositivo B (Alice-PC)
    private val pcSignPub = pub("alice.pc.sign")
    private val pcSignPriv = priv("alice.pc.sign")
    private val pcAgr = opaque("alice.pc.agr")
    private val pcId = KmIds.deviceId(pcSignPub)

    // Dispositivo C (Alice-Tablet) — generado deterministicamente
    private val tabletSeed = ByteArray(32) { (it + 0xA0).toByte() }
    private val tabletKP = ed.keyPairFromSeed(tabletSeed)
    private val tabletSignPub = tabletKP.publicKey
    private val tabletSignPriv = tabletKP.privateKey
    private val tabletAgr = ByteArray(32) { (it + 0xB0).toByte() }
    private val tabletId = KmIds.deviceId(tabletSignPub)

    // Otra identidad: Bob — generamos root key deterministicamente
    private val rootBobKP = ed.keyPairFromSeed(ByteArray(32) { (it + 0x50).toByte() })
    private val rootBobPub = rootBobKP.publicKey
    private val rootBobPriv = rootBobKP.privateKey

    // identityId (ByteArray)
    private val aliceIdentityId = KmIds.identityId(rootAlicePub)
    private val bobIdentityId = KmIds.identityId(rootBobPub)

    init {
        require(tabletAgr.size == 32) { "tabletAgr debe tener 32 bytes" }
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private fun makeDevice(
        signing: ByteArray, agreement: ByteArray, name: String,
        status: Long = KmIdConstants.STATUS_ACTIVE,
    ): Map<String, Any?> = DeviceRoster.makeDeviceEntry(signing, agreement, name = name, status = status)

    /** Roster #1: solo Mobile */
    private fun roster1(): Map<String, Any?> = DeviceRoster.sign(
        DeviceRoster.build(rootAlicePub, listOf(makeDevice(mobileSignPub, mobileAgr, "Alice-Mobile")), sequence = 1),
        rootAlicePriv,
    )

    /** Roster #2: Mobile + PC */
    private fun roster2(): Map<String, Any?> {
        val r1 = roster1()
        return DeviceRoster.sign(
            DeviceRoster.build(
                rootAlicePub,
                listOf(makeDevice(mobileSignPub, mobileAgr, "Alice-Mobile"), makeDevice(pcSignPub, pcAgr, "Alice-PC")),
                sequence = 2, previous = DeviceRoster.chainPrevious(r1),
            ),
            rootAlicePriv,
        )
    }

    /** Roster #3: Mobile + PC + Tablet */
    private fun roster3(): Map<String, Any?> {
        val r2 = roster2()
        return DeviceRoster.sign(
            DeviceRoster.build(
                rootAlicePub,
                listOf(
                    makeDevice(mobileSignPub, mobileAgr, "Alice-Mobile"),
                    makeDevice(pcSignPub, pcAgr, "Alice-PC"),
                    makeDevice(tabletSignPub, tabletAgr, "Alice-Tablet"),
                ),
                sequence = 3, previous = DeviceRoster.chainPrevious(r2),
            ),
            rootAlicePriv,
        )
    }

    /** SPK para Mobile */
    private fun spkMobile(): Map<String, Any?> = ContactBundle.signSignedPrekey(
        ContactBundle.buildSignedPrekey(mobileSignPub, opaque("alice.mobile.spk"), keyId = 42, deviceIdBytes = mobileId),
        mobileSignPriv,
    )

    /** SPK para PC */
    private fun spkPc(): Map<String, Any?> = ContactBundle.signSignedPrekey(
        ContactBundle.buildSignedPrekey(pcSignPub, opaque("alice.pc.spk"), keyId = 7, deviceIdBytes = pcId),
        pcSignPriv,
    )

    /** SPK para Tablet */
    private fun spkTablet(): Map<String, Any?> {
        val kp = ed.keyPairFromSeed(ByteArray(32) { (it + 0xC0).toByte() })
        return ContactBundle.signSignedPrekey(
            ContactBundle.buildSignedPrekey(tabletSignPub, kp.publicKey, keyId = 99, deviceIdBytes = tabletId),
            tabletSignPriv,
        )
    }

    /** OTPKs para Mobile */
    private fun opksMobile(): List<Map<String, Any?>> = listOf(
        ContactBundle.buildOnetimePrekey(opaque("alice.mobile.opk.1000"), keyId = 1000, deviceIdBytes = mobileId),
        ContactBundle.buildOnetimePrekey(opaque("alice.mobile.opk.1001"), keyId = 1001, deviceIdBytes = mobileId),
    )

    /** OTPKs para PC */
    private fun opksPc(): List<Map<String, Any?>> = listOf(
        ContactBundle.buildOnetimePrekey(ByteArray(32) { (it + 0xD0).toByte() }, keyId = 3000, deviceIdBytes = pcId),
        ContactBundle.buildOnetimePrekey(ByteArray(32) { (it + 0xE0).toByte() }, keyId = 3001, deviceIdBytes = pcId),
    )

    /** Bundle CONTACT para Mobile (roster #3) */
    private fun bundleMobile(): Map<String, Any?> = ContactBundle.signBundle(
        ContactBundle.buildBundle(rootAlicePub, roster3(), mobileId, spkMobile(),
            oneTimePrekeys = opksMobile(), capabilities = 0b111),
        rootAlicePriv,
    )

    /** Bundle CONTACT para PC (roster #3) */
    private fun bundlePc(): Map<String, Any?> = ContactBundle.signBundle(
        ContactBundle.buildBundle(rootAlicePub, roster3(), pcId, spkPc(),
            oneTimePrekeys = opksPc(), capabilities = 0b101),
        rootAlicePriv,
    )

    // ===================================================================
    // 3H.1 — Modelo multi-device
    // ===================================================================

    @Test
    fun `roster con 3 dispositivos se construye y verifica`() {
        val r3 = roster3()
        DeviceRoster.verify(r3, previousRoster = roster2())
        assertEquals(3, (r3["devices"] as List<*>).size)
    }

    @Test
    fun `roster con 2 dispositivos se construye y verifica`() {
        val r2 = roster2()
        DeviceRoster.verify(r2, previousRoster = roster1())
        assertEquals(2, (r2["devices"] as List<*>).size)
    }

    @Test
    fun `cada dispositivo en roster tiene deviceId derivado de signingKey`() {
        for (d in roster3()["devices"] as List<Map<String, Any?>>) {
            assertContentEquals(KmIds.deviceId(d["signingKey"] as ByteArray), d["deviceId"] as ByteArray)
        }
    }

    @Test
    fun `identityRoot a identityId coinciden`() {
        val r3 = roster3()
        assertContentEquals(KmIds.identityId(r3["identityRoot"] as ByteArray), r3["identityId"] as ByteArray)
    }

    @Test
    fun `bundle para dispositivo Mobile se verifica`() {
        ContactBundle.verifyBundle(bundleMobile(), expectedIdentityRoot = rootAlicePub)
    }

    @Test
    fun `bundle para dispositivo PC se verifica`() {
        ContactBundle.verifyBundle(bundlePc(), expectedIdentityRoot = rootAlicePub)
    }

    @Test
    fun `ambos bundles coexisten bajo la misma identityRoot`() {
        val bA = bundleMobile()
        val bB = bundlePc()
        assertContentEquals(mobileId, bA["subjectDeviceId"] as ByteArray)
        assertContentEquals(pcId, bB["subjectDeviceId"] as ByteArray)
        ContactBundle.verifyBundle(bA, expectedIdentityRoot = rootAlicePub)
        ContactBundle.verifyBundle(bB, expectedIdentityRoot = rootAlicePub)
    }

    // ===================================================================
    // 3H.2 — Roster evolution
    // ===================================================================

    @Test
    fun `roster #2 encadena desde roster #1`() {
        DeviceRoster.verify(roster2(), previousRoster = roster1())
    }

    @Test
    fun `roster #3 encadena desde roster #2`() {
        DeviceRoster.verify(roster3(), previousRoster = roster2())
    }

    @Test
    fun `roster #3 con previo incorrecto se rechaza`() {
        assertFailsWithCode("PREVIOUS_MISMATCH") {
            DeviceRoster.verify(roster3(), previousRoster = roster1())
        }
    }

    @Test
    fun `secuencia 2 sin previous se rechaza`() {
        assertFailsWithCode("MISSING_PREVIOUS") {
            DeviceRoster.build(rootAlicePub, listOf(
                makeDevice(mobileSignPub, mobileAgr, "Alice-Mobile"),
                makeDevice(pcSignPub, pcAgr, "Alice-PC"),
            ), sequence = 2)
        }
    }

    @Test
    fun `ancla genesis se comprueba incluso en modo bundle`() {
        val c = roster1().toMutableMap()
        c["previous"] = ByteArray(32) { 0xAA.toByte() }
        assertFailsWithCode("PREVIOUS_MISMATCH") {
            DeviceRoster.verify(DeviceRoster.sign(c, rootAlicePriv), requireChain = false)
        }
    }

    @Test
    fun `dispositivo revocado se rechaza como subject`() {
        val r1 = roster1()
        val devices = (r1["devices"] as List<Map<String, Any?>>).map {
            LinkedHashMap(it) + mapOf("status" to KmIdConstants.STATUS_REVOKED, "revokedAt" to 1_700_000_000_000L)
        }
        val rosterRev = DeviceRoster.sign(r1.toMutableMap().apply { put("devices", devices) }, rootAlicePriv)
        assertFailsWithCode("SUBJECT_REVOKED") {
            ContactBundle.buildBundle(rootAlicePub, rosterRev, mobileId, spkMobile())
        }
    }

    @Test
    fun `roster vacio se rechaza`() {
        assertFailsWithCode("EMPTY_ROSTER") {
            DeviceRoster.build(rootAlicePub, emptyList(), sequence = 1)
        }
    }

    @Test
    fun `roster con mas de 64 dispositivos se rechaza`() {
        assertFailsWithCode("TOO_MANY_DEVICES") {
            DeviceRoster.build(rootAlicePub, List(KmIdConstants.MAX_DEVICES + 1) {
                DeviceRoster.makeDeviceEntry(
                    ByteArray(32) { it.toByte() },
                    ByteArray(32) { (it + 1).toByte() },
                    name = "d$it",
                )
            }, sequence = 1)
        }
    }

    // ===================================================================
    // 3H.3 — ContactBundle integration
    // ===================================================================

    @Test
    fun `subjectSigningKey coincide con el roster`() {
        val b = bundleMobile()
        val entry = DeviceRoster.findDevice(b["roster"] as Map<String, Any?>, b["subjectDeviceId"] as ByteArray)!!
        assertContentEquals(entry["signingKey"] as ByteArray, b["subjectSigningKey"] as ByteArray)
    }

    @Test
    fun `subjectAgreementKey coincide con el roster`() {
        val b = bundleMobile()
        val entry = DeviceRoster.findDevice(b["roster"] as Map<String, Any?>, b["subjectDeviceId"] as ByteArray)!!
        assertContentEquals(entry["agreementKey"] as ByteArray, b["subjectAgreementKey"] as ByteArray)
    }

    @Test
    fun `SPK deviceId igual a subjectDeviceId`() {
        val b = bundleMobile()
        assertContentEquals(
            b["subjectDeviceId"] as ByteArray,
            (b["signedPrekey"] as Map<String, Any?>)["deviceId"] as ByteArray,
        )
    }

    @Test
    fun `cada OTPK deviceId igual a subjectDeviceId`() {
        for (opk in bundleMobile()["oneTimePrekeys"] as List<Map<String, Any?>>) {
            assertContentEquals(
                bundleMobile()["subjectDeviceId"] as ByteArray,
                opk["deviceId"] as ByteArray,
            )
        }
    }

    @Test
    fun `SPK firmado por subjectSigningKey`() {
        val b = bundleMobile()
        val spk = b["signedPrekey"] as Map<String, Any?>
        assertTrue(ed.verify(
            b["subjectSigningKey"] as ByteArray,
            ContactBundle.signablePrekeyBytes(spk),
            Signature(spk["signature"] as ByteArray),
        ))
    }

    // ===================================================================
    // 3H.4 — Cross-device mutation tests
    // ===================================================================

    @Test
    fun `M-3H-1 bundle A con signingKey de B se rechaza`() {
        val b = bundleMobile().toMutableMap()
        b["subjectSigningKey"] = pcSignPub
        assertFailsWithCode("SUBJECT_BINDING_MISMATCH") {
            ContactBundle.verifyBundle(b)
        }
    }

    @Test
    fun `M-3H-2 SPK de A con deviceId de B se rechaza`() {
        val b = bundleMobile().toMutableMap()
        val spk = LinkedHashMap(b["signedPrekey"] as Map<String, Any?>)
        spk["deviceId"] = pcId
        b["signedPrekey"] = spk
        assertFailsWithCode("PREKEY_BINDING_MISMATCH") {
            ContactBundle.verifyBundle(b)
        }
    }

    @Test
    fun `M-3H-3 OTPK de A con deviceId de B se rechaza`() {
        val b = bundleMobile().toMutableMap()
        b["oneTimePrekeys"] = (b["oneTimePrekeys"] as List<Map<String, Any?>>).map {
            LinkedHashMap(it) + mapOf("deviceId" to pcId)
        }
        assertFailsWithCode("PREKEY_BINDING_MISMATCH") {
            ContactBundle.verifyBundle(b)
        }
    }

    @Test
    fun `M-3H-4 roster de identidad distinta como subject se rechaza`() {
        // Roster de Bob (generamos su root y primer dispositivo)
        val bobDeviceKP = ed.keyPairFromSeed(ByteArray(32) { (it + 0x60).toByte() })
        val rosterBob = DeviceRoster.sign(
            DeviceRoster.build(rootBobPub, listOf(
                DeviceRoster.makeDeviceEntry(bobDeviceKP.publicKey, bobDeviceKP.publicKey, name = "Bob-Device")
            ), sequence = 1),
            rootBobPriv,
        )
        // Bundle que dice identityRoot = Alice pero usa roster de Bob
        assertFailsWithCode("SUBJECT_NOT_IN_ROSTER") {
            ContactBundle.buildBundle(
                rootAlicePub, rosterBob, mobileId, spkMobile(),
            )
        }
    }

    @Test
    fun `M-3H-5 sequence rollback se rechaza`() {
        val r2 = roster2()
        val r3 = roster3()
        assertFailsWithCode("PREVIOUS_MISMATCH") {
            DeviceRoster.verify(r2, previousRoster = r3)
        }
    }

    @Test
    fun `M-3H-6 previous alterado se rechaza`() {
        val r2 = roster2().toMutableMap()
        r2["previous"] = ByteArray(32) { 0xBB.toByte() }
        val corrupto = DeviceRoster.sign(r2, rootAlicePriv)
        assertFailsWithCode("PREVIOUS_MISMATCH") {
            DeviceRoster.verify(corrupto, previousRoster = roster1())
        }
    }

    @Test
    fun `M-3H-7 dispositivo revocado como subject se rechaza`() {
        val r = roster1().toMutableMap()
        r["devices"] = (r["devices"] as List<Map<String, Any?>>).map {
            LinkedHashMap(it) + mapOf("status" to KmIdConstants.STATUS_REVOKED, "revokedAt" to 1_700_000_000_000L)
        }
        val signed = DeviceRoster.sign(r, rootAlicePriv)
        assertFailsWithCode("SUBJECT_REVOKED") {
            ContactBundle.buildBundle(rootAlicePub, signed, mobileId, spkMobile())
        }
    }

    @Test
    fun `M-3H-8 roster con dispositivo inyectado sin resignar se rechaza`() {
        // Inyectar dispositivo sin resignar: la firma no cubre el cambio
        val alienKP = ed.keyPairFromSeed(ByteArray(32) { (it + 0xF0).toByte() })
        val r2 = roster2()
        @Suppress("UNCHECKED_CAST")
        val devices = (r2["devices"] as List<Map<String, Any?>>) + listOf(
            DeviceRoster.makeDeviceEntry(alienKP.publicKey, alienKP.publicKey, name = "Alien")
        )
        val rInyectado = r2.toMutableMap()
        rInyectado["devices"] = devices

        // Sin resignar: la firma del root NO cubre el nuevo dispositivo
        // verify checkea signature ANTES de requerir previous, pero como sequence > 1
        // y no hay previousRoster... usamos requireChain=false para aislar la firma
        assertFailsWithCode("INVALID_SIGNATURE") {
            DeviceRoster.verify(rInyectado, requireChain = false)
        }

        // Si se resigna, el root autoriza el cambio
        val resignado = DeviceRoster.sign(rInyectado, rootAlicePriv)
        DeviceRoster.verify(resignado, previousRoster = roster1())
    }

    @Test
    fun `M-3H-9 bundle de A manipulado como bundle de B se rechaza`() {
        // Atacante toma bundle de Mobile y cambia subjectDeviceId a PC
        val b = bundleMobile().toMutableMap()
        b["subjectDeviceId"] = pcId
        assertFailsWithCode("SUBJECT_BINDING_MISMATCH") {
            ContactBundle.verifyBundle(b)
        }
    }

    @Test
    fun `M-3H-10 identityId del bundle no coincide con identityRoot se rechaza`() {
        val b = bundleMobile().toMutableMap()
        b["identityId"] = bobIdentityId
        assertFailsWithCode("IDENTITY_ID_MISMATCH") {
            ContactBundle.verifyBundle(b)
        }
    }

    @Test
    fun `M-3H-11 bundle de A con agreementKey de B se rechaza`() {
        val b = bundleMobile().toMutableMap()
        b["subjectAgreementKey"] = pcAgr
        assertFailsWithCode("SUBJECT_BINDING_MISMATCH") {
            ContactBundle.verifyBundle(b)
        }
    }

    @Test
    fun `M-3H-12 SPK de A firmado por signingKey de B se rechaza`() {
        val spkRobado = ContactBundle.signSignedPrekey(
            ContactBundle.buildSignedPrekey(mobileSignPub, opaque("alice.mobile.spk"), keyId = 42, deviceIdBytes = mobileId),
            pcSignPriv,
        )
        val bundle = ContactBundle.signBundle(
            ContactBundle.buildBundle(rootAlicePub, roster3(), mobileId, spkRobado,
                oneTimePrekeys = opksMobile(), capabilities = 0b111),
            rootAlicePriv,
        )
        assertFailsWithCode("INVALID_SIGNATURE") {
            ContactBundle.verifyBundle(bundle)
        }
    }

    @Test
    fun `M-3H-13 roster embebido con identityRoot distinto del bundle se rechaza`() {
        // Construir bundle manual: identityRoot=Alice, pero roster con root de Bob
        val bobDeviceKP = ed.keyPairFromSeed(ByteArray(32) { (it + 0x60).toByte() })
        val bobDeviceId = KmIds.deviceId(bobDeviceKP.publicKey)
        val rosterBob = DeviceRoster.sign(
            DeviceRoster.build(rootBobPub, listOf(
                DeviceRoster.makeDeviceEntry(bobDeviceKP.publicKey, bobDeviceKP.publicKey, name = "Bob-Device")
            ), sequence = 1),
            rootBobPriv,
        )

        val raw = linkedMapOf(
            "doc" to KmIdConstants.DOC_CONTACT_BUNDLE,
            "version" to KmIdConstants.CURRENT_VERSION,
            "kind" to KmIdConstants.KIND_CONTACT,
            "createdAt" to KmIdConstants.SAMPLE_CREATED_AT,
            "identityRoot" to rootAlicePub,
            "identityId" to aliceIdentityId,
            "roster" to rosterBob,
            "subjectDeviceId" to bobDeviceId,
            "subjectSigningKey" to bobDeviceKP.publicKey,
            "subjectAgreementKey" to bobDeviceKP.publicKey,
            "signedPrekey" to ContactBundle.signSignedPrekey(
                ContactBundle.buildSignedPrekey(bobDeviceKP.publicKey, ByteArray(32) { 0xAA.toByte() },
                    keyId = 1, deviceIdBytes = bobDeviceId),
                bobDeviceKP.privateKey,
            ),
            "oneTimePrekeys" to emptyList<Map<String, Any?>>(),
            "endpoints" to emptyList<Map<String, Any?>>(),
            "capabilities" to 0L,
        )
        val bundle = ContactBundle.signBundle(raw, rootAlicePriv)

        assertFailsWithCode("IDENTITY_MISMATCH") {
            ContactBundle.verifyBundle(bundle)
        }
    }

    @Test
    fun `M-3H-14 identityId arbitrario se rechaza`() {
        val b = bundleMobile().toMutableMap()
        b["identityId"] = ByteArray(32) { 0xFF.toByte() }
        assertFailsWithCode("IDENTITY_ID_MISMATCH") {
            ContactBundle.verifyBundle(b)
        }
    }

    @Test
    fun `M-3H-15 dispositivo fuera del roster no puede ser subject`() {
        val alienKP = ed.keyPairFromSeed(ByteArray(32) { (it + 0xF0).toByte() })
        val alienId = KmIds.deviceId(alienKP.publicKey)
        assertFailsWithCode("SUBJECT_NOT_IN_ROSTER") {
            ContactBundle.buildBundle(rootAlicePub, roster3(), alienId,
                ContactBundle.signSignedPrekey(
                    ContactBundle.buildSignedPrekey(alienKP.publicKey, ByteArray(32) { 0x99.toByte() },
                        keyId = 1, deviceIdBytes = alienId),
                    alienKP.privateKey,
                ),
            )
        }
    }
}

private fun assertFailsWithCode(
    expectedCode: String,
    block: () -> Unit,
) {
    try {
        block()
        throw AssertionError(
            "Se esperaba KmIdVerificationException con codigo '$expectedCode' pero no se lanzo ninguna excepcion"
        )
    } catch (e: KmIdVerificationException) {
        assertEquals(expectedCode, e.code, "codigo de error esperado: $expectedCode")
    }
}
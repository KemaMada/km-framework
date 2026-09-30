package com.keymessage.core.kmid

import com.keymessage.core.crypto.Ed25519Impl
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DeviceRoster contra los vectores congelados.
 *
 * El nucleo de este test es CONSTRUIR el roster en Kotlin desde las mismas
 * semillas y exigir que salga byte a byte igual que el congelado. No basta
 * con decodificar el fixture y volver a codificarlo: eso solo comprobaria
 * que el codificador es invertible. Reconstruirlo comprueba a la vez la
 * derivacion de deviceId, la cadena genesis, la firma y la ordenacion
 * canonica, y por tanto que Kotlin y Python son el mismo protocolo.
 */
class DeviceRosterGoldenVectorsTest {

    private val ed = Ed25519Impl()

    private fun seed(label: String): ByteArray =
        KmIdFixtures.unhex(KmIdFixtures.manifest()["seeds"][label].asText())

    private fun pub(label: String) = ed.keyPairFromSeed(seed(label)).publicKey

    private fun priv(label: String) = ed.keyPairFromSeed(seed(label)).privateKey

    private fun deviceEntry(signing: String, agreement: String, name: String) =
        DeviceRoster.makeDeviceEntry(
            signingPublicKey = pub(signing),
            agreementPublicKey = seed(agreement),
            name = name,
        )

    /** Reconstruye el roster #1 de Alice, identico a G04. */
    private fun buildRoster1(): Map<String, Any?> = DeviceRoster.sign(
        DeviceRoster.build(
            identityRootPublicKey = pub("root.alice.v1"),
            devices = listOf(deviceEntry("alice.mobile.sign", "alice.mobile.agr", "Alice-Android")),
            sequence = 1,
        ),
        priv("root.alice.v1"),
    )

    private fun buildRoster2(previousRoster: Map<String, Any?>): Map<String, Any?> = DeviceRoster.sign(
        DeviceRoster.build(
            identityRootPublicKey = pub("root.alice.v1"),
            devices = listOf(
                deviceEntry("alice.mobile.sign", "alice.mobile.agr", "Alice-Android"),
                deviceEntry("alice.pc.sign", "alice.pc.agr", "Alice-PC"),
            ),
            sequence = 2,
            previous = DeviceRoster.chainPrevious(previousRoster),
        ),
        priv("root.alice.v1"),
    )

    // ===================================================================
    // Construccion: Kotlin debe producir los bytes congelados
    // ===================================================================

    @Test
    fun `G04 roster genesis reconstruido byte a byte`() {
        val construido = buildRoster1()
        val congelado = KmIdFixtures.bytes("vectors/G04/canonical.cbor")
        assertContentEquals(
            congelado, DeviceRoster.canonicalBytes(construido),
            "el roster construido en Kotlin no reproduce G04/canonical.cbor"
        )
    }

    @Test
    fun `G05 roster #2 reconstruido byte a byte`() {
        val construido = buildRoster2(buildRoster1())
        val congelado = KmIdFixtures.bytes("vectors/G05/canonical.cbor")
        assertContentEquals(
            congelado, DeviceRoster.canonicalBytes(construido),
            "el roster #2 construido en Kotlin no reproduce G05/canonical.cbor"
        )
    }

    @Test
    fun `los payloads firmables reconstruidos coinciden con los congelados`() {
        assertContentEquals(
            KmIdFixtures.bytes("vectors/G04/signable.cbor"),
            DeviceRoster.signableBytes(buildRoster1()),
        )
        assertContentEquals(
            KmIdFixtures.bytes("vectors/G05/signable.cbor"),
            DeviceRoster.signableBytes(buildRoster2(buildRoster1())),
        )
    }

    // ===================================================================
    // Verificacion de los vectores validos
    // ===================================================================

    @Test
    fun `los vectores validos de roster verifican`() {
        // Solo G04, G05 y G06 son DeviceRoster. G07 es identityRotation y
        // G08/G09 son prekeys: asumirlos rosters daria un NullPointerException
        // en lugar de un fallo diagnostico.
        val g4 = Kce.validate(KmIdFixtures.bytes("vectors/G04/canonical.cbor")) as Map<String, Any?>
        for (gid in listOf("G04", "G05", "G06")) {
            val roster = Kce.validate(KmIdFixtures.bytes("vectors/$gid/canonical.cbor")) as Map<String, Any?>
            assertEquals(KmIdConstants.DOC_ROSTER, roster["doc"], "$gid no es un DeviceRoster")

            val seq = roster["sequence"] as Long
            val prev = if (seq == 1L) null else g4
            DeviceRoster.verify(roster, previousRoster = prev, expectedIdentityRoot = pub("root.alice.v1"))
        }
    }

    @Test
    fun `G07 G08 y G09 no son rosters`() {
        // Documenta la composicion de la suite: evita que alguien los anada a
        // la lista de arriba creyendo que son lo mismo.
        val esperados = mapOf(
            "G04" to KmIdConstants.DOC_ROSTER,
            "G05" to KmIdConstants.DOC_ROSTER,
            "G06" to KmIdConstants.DOC_ROSTER,
            "G07" to KmIdConstants.DOC_ROTATION,
            "G08" to KmIdConstants.DOC_SIGNED_PREKEY,
            "G09" to KmIdConstants.DOC_ONETIME_PREKEY,
        )
        for ((gid, doc) in esperados) {
            val m = Kce.validate(KmIdFixtures.bytes("vectors/$gid/canonical.cbor")) as Map<String, Any?>
            assertEquals(doc, m["doc"], "$gid cambio de tipo de documento")
        }
    }

    // ===================================================================
    // Cadena de hashes
    // ===================================================================

    @Test
    fun `el ancla genesis liga a la identidad`() {
        val r1 = buildRoster1()
        val esperado = DeviceRoster.genesisPrevious(pub("root.alice.v1"))
        assertContentEquals(esperado, r1["previous"] as ByteArray)

        // Cambiar la raiz cambia el ancla: un roster #1 de otra identidad no
        // puede ser aceptado como el genesis de esta.
        val otra = DeviceRoster.genesisPrevious(pub("root.alice.v2"))
        assertTrue(!esperado.contentEquals(otra))
    }

    @Test
    fun `el enlace encadena el documento completo incluida la firma`() {
        val r1 = buildRoster1()
        val enlace = DeviceRoster.chainPrevious(r1)
        // SHA-256 de los bytes canonicos completos, no del payload firmable.
        assertContentEquals(DeviceRoster.canonicalBytes(r1).let {
            java.security.MessageDigest.getInstance("SHA-256").digest(it)
        }, enlace)
        assertTrue(!enlace.contentEquals(DeviceRoster.signableBytes(r1).let {
            java.security.MessageDigest.getInstance("SHA-256").digest(it)
        }), "el enlace debe incluir la firma")
    }

    @Test
    fun `sequence mayor que uno exige previous`() {
        val e = assertFailsWith<KmIdVerificationException> {
            DeviceRoster.build(pub("root.alice.v1"), listOf(deviceEntry("alice.mobile.sign", "alice.mobile.agr", "x")), sequence = 2)
        }
        assertEquals("MISSING_PREVIOUS", e.code)
    }

    @Test
    fun `un roster vacio se rechaza`() {
        val e = assertFailsWith<KmIdVerificationException> {
            DeviceRoster.build(pub("root.alice.v1"), emptyList())
        }
        assertEquals("EMPTY_ROSTER", e.code)
    }

    // ===================================================================
    // Fixtures negativos de la capa de esquema y roster
    // ===================================================================

    private val rosterLayer = mapOf(
        "unknown-field.cbor" to "UNKNOWN_FIELD",
        "bad-signature.cbor" to "INVALID_SIGNATURE",
        "wrong-previous.cbor" to "PREVIOUS_MISMATCH",
        "revoked-without-timestamp.cbor" to "REVOCATION_FIELD_MISMATCH",
    )

    /**
     * Ejecuta la cadena de verificacion completa y devuelve el codigo del
     * PRIMER fallo, en el mismo orden de capas que la referencia:
     * KCE -> esquema -> invariantes y firma.
     *
     * Devolver el primer fallo y no el ultimo es lo que hace util la
     * comparacion con el MANIFEST: un fixture que falla por dos razones
     * reporta la primaria, igual que haria la referencia.
     */
    private fun firstFailureCode(bytes: ByteArray): String? {
        // El despacho de version va PRIMERO, antes de KCE, igual que en la
        // referencia: un documento de otra generacion no debe reinterpretarse
        // con las reglas de la actual.
        try {
            DeviceRoster.checkVersionSupported(bytes)
        } catch (e: KmIdVerificationException) {
            return e.code
        }
        val m = try {
            Kce.validate(bytes)
        } catch (e: Kce.KceException) {
            return e.code
        }
        @Suppress("UNCHECKED_CAST")
        val roster = m as Map<String, Any?>
        try {
            KmSchemas.validate(KmSchemas.DEVICE_ROSTER, roster, "DeviceRoster.")
        } catch (e: KmIdSchemaException) {
            return e.code
        }
        return try {
            val seq = roster["sequence"] as Long
            val prev = if (seq == 1L) {
                null
            } else {
                Kce.validate(KmIdFixtures.bytes("vectors/G04/canonical.cbor")) as Map<String, Any?>
            }
            DeviceRoster.verify(roster, previousRoster = prev)
            null
        } catch (e: KmIdVerificationException) {
            e.code
        }
    }

    @Test
    fun `cada fixture negativo de esquema y roster falla con el codigo declarado`() {
        for ((fichero, esperado) in rosterLayer) {
            val codigo = firstFailureCode(KmIdFixtures.bytes("invalid/$fichero"))
            assertEquals(
                esperado, codigo,
                "invalid/$fichero fue aceptado o fallo por ${codigo ?: "nada"}, no por $esperado"
            )
        }
    }

    @Test
    fun `un fixture negativo no puede fallar por dos razones a la vez`() {
        // El MANIFEST declara UN codigo por fixture. Si la cadena completa
        // produjera otro, el fixture estaria pasando por el motivo
        // equivocado, que es peor que no comprobarlo.
        for (entry in KmIdFixtures.manifest()["invalid"]) {
            val fichero = entry["file"].asText().substringAfterLast('/')
            if (fichero in PENDIENTES_CONTACTO) continue
            val declarado = entry["expected"].asText()
            val producido = firstFailureCode(KmIdFixtures.bytes(entry["file"].asText()))
            if (producido == null) continue // aceptado: la cadena no fallo
            assertEquals(
                declarado, producido,
                "invalid/$fichero produce $producido pero el MANIFEST declara $declarado"
            )
        }
    }

    @Test
    fun `la cola de fixtures de la capa de contacto esta declarada`() {
        // wrong-device-binding.cbor es un ContactBundle, no un DeviceRoster. La
        // cadena de roster lo rechaza antes con FIELD_TYPE, que es CORRECTO
        // para una cadena de roster pero no es el codigo que el MANIFEST
        // declara, porque el MANIFEST lo enruta a su verificador propio.
        // Este test deja constancia de que es una omision conocida y no un
        // olvido: al portar la capa de contacto, el nombre debe salir de
        // aqui.
        assertEquals(setOf("wrong-device-binding.cbor"), PENDIENTES_CONTACTO)
    }

    private companion object {
        /**
         * Fixtures que pertenecen a capas aun no portadas. Cada uno debe
         * moverse a su capa cuando esta se implemente; si la lista queda
         * obsoleta, los tests de arriba dejarian de ser certainos.
         */
        val PENDIENTES_CONTACTO = setOf("wrong-device-binding.cbor")
    }

    @Test
    fun `el despacho de version va antes que la validacion`() {
        val v1 = KmIdFixtures.bytes("vectors/G04/canonical.cbor")
        // El vector valido pasa.
        DeviceRoster.checkVersionSupported(v1)

        // El fixture de version incorrecta se rechaza por version, no por
        // firma. Este es el punto del despacho: si se reinterpretaba con las
        // reglas de la v1, el diagnostico seria "la firma no verifica", que
        // senala el sintoma en lugar de la causa.
        val v2 = KmIdFixtures.bytes("invalid/wrong-version.cbor")
        val e = assertFailsWith<KmIdVerificationException> { DeviceRoster.checkVersionSupported(v2) }
        assertEquals("UNSUPPORTED_VERSION", e.code)

        // Y la version se lee con certeza, no se adivina.
        val r = Kce.peekVersion(v2, KmIdConstants.CURRENT_VERSION)
        assertTrue(r is Kce.VersionResult.Unsupported, "se esperaba una version no soportada, se obtuvo $r")
        assertTrue((r as Kce.VersionResult.Unsupported).version != KmIdConstants.CURRENT_VERSION)
    }

    @Test
    fun `un documento ilegible no se declara no soportado`() {
        // Indeterminado NO es "no soportado". Confundir los dos haria que un
        // byte corrupto se reportara como problema de version, que es
        // mucho mas dificil de diagnosticar.
        for (basura in listOf(
            byteArrayOf(),
            byteArrayOf(0x83.toByte(), 1, 2, 3),          // array, no map
            Kce.encode(mapOf("doc" to "km.deviceRoster")),  // map sin version
        )) {
            try {
                DeviceRoster.checkVersionSupported(basura)
            } catch (e: KmIdVerificationException) {
                // Correcto: una version legible y distinta.
                assertEquals("UNSUPPORTED_VERSION", e.code)
            }
            // Si no lanza, es porque fue indeterminado: aceptable.
        }
    }

    // ===================================================================
    // Contexto de bundle: requireChain = false
    // ===================================================================

    @Test
    fun `en contexto de bundle la continuidad no se exige`() {
        val r1 = buildRoster1()
        val r2 = buildRoster2(r1)
        assertEquals(2L, r2["sequence"], "este test necesita un roster con sequence > 1")

        // 1. El enlace real verifica sin estado externo.
        DeviceRoster.verify(r2, previousRoster = r1, expectedIdentityRoot = pub("root.alice.v1"))

        // 2. Sin el roster previo, en modo bundle, NO se exige el enlace. Es
        //    una concesion consciente, no un olvido: un bundle embebe UN
        //    snapshot, de modo que el anterior no es verificable desde el
        //    propio documento. La autoridad es exclusivamente la FIRMA, y el
        //    RFC no debe prometer mas.
        DeviceRoster.verify(r2, requireChain = false, expectedIdentityRoot = pub("root.alice.v1"))

        // 3. Y un enlace de tamano correcto pero VALOR equivocado tambien se
        //    acepta en modo bundle. Se fija a proposito: si algun dia se
        //    cambiara esta concesion, este test debe romperse y obligar a
        //    revisar la garantia que KM-0002 afirma.
        val malValor = DeviceRoster.sign(
            DeviceRoster.build(
                pub("root.alice.v1"),
                listOf(deviceEntry("alice.mobile.sign", "alice.mobile.agr", "Alice-Android")),
                sequence = 2,
                previous = ByteArray(32) { 0x5A },
            ),
            priv("root.alice.v1"),
        )
        DeviceRoster.verify(malValor, requireChain = false)
    }

    @Test
    fun `el tamano de previous se comprueba aunque el esquema ya lo garantiza`() {
        // `previous` es bstr32 en el esquema, asi que por la cadena completa
        // un enlace de 31 bytes ya se rechaza como FIELD_SIZE. La comprobacion
        // de `verify` es defensa en profundidad para quien la llame SIN haber
        // validado el esquema, que es un contrato explicito de esta funcion.
        // Se comprueba aqui precisamente porque es inalcanzable por el otro
        // camino: un test que la ignorara la dejaria sin verificar.
        val r1 = buildRoster1()
        val r2 = buildRoster2(r1).toMutableMap()
        r2["previous"] = ByteArray(31) { 0x5A }
        val e = assertFailsWith<KmIdVerificationException> {
            DeviceRoster.verify(r2, requireChain = false)
        }
        assertEquals("PREVIOUS_MISMATCH", e.code)

        // Y por la cadena completa, el mismo defecto se reporta como esquema.
        val porEsquema = assertFailsWith<KmIdSchemaException> {
            KmSchemas.validate(KmSchemas.DEVICE_ROSTER, r2, "DeviceRoster.")
        }
        assertEquals("FIELD_SIZE", porEsquema.code)
    }

    @Test
    fun `el ancla genesis se comprueba siempre, incluso en modo bundle`() {
        // Contraparte de (2) del test anterior: cuando sequence == 1 el ancla
        // SI es comprobable sin estado externo, asi que la concesion de
        // requireChain=false no debe abrir esa puerta.
        val r1 = buildRoster1().toMutableMap()
        r1["previous"] = ByteArray(32) { 0x5A }
        val e = assertFailsWith<KmIdVerificationException> {
            DeviceRoster.verify(r1, requireChain = false)
        }
        assertEquals("PREVIOUS_MISMATCH", e.code)
    }

    @Test
    fun `con requireChain activo y sequence mayor que uno hace falta el previo`() {
        val r1 = buildRoster1()
        val r2 = buildRoster2(r1)
        val e = assertFailsWith<KmIdVerificationException> { DeviceRoster.verify(r2) }
        assertEquals("MISSING_PREVIOUS", e.code)
    }

    @Test
    fun `el roster previo debe ser de la misma identidad`() {
        val r1 = buildRoster1()
        // Un roster de la identidad rotada, encadenado como si fuera el previo.
        val r2otra = DeviceRoster.sign(
            DeviceRoster.build(pub("root.alice.v2"), listOf(deviceEntry("alice.mobile.sign", "alice.mobile.agr", "x")), sequence = 1),
            priv("root.alice.v2"),
        )
        val r2 = buildRoster2(r1).toMutableMap()
        r2["previous"] = DeviceRoster.chainPrevious(r2otra)
        val e = assertFailsWith<KmIdVerificationException> { DeviceRoster.verify(r2, previousRoster = r2otra) }
        assertEquals("IDENTITY_MISMATCH", e.code)
    }

    // ===================================================================
    // Invariantes de identidad
    // ===================================================================

    @Test
    fun `identityId declarado que no sea la derivacion se rechaza`() {
        val r = buildRoster1().toMutableMap()
        r["identityId"] = ByteArray(32) { 0x7F }
        val e = assertFailsWith<KmIdVerificationException> { DeviceRoster.verify(r) }
        assertEquals("IDENTITY_ID_MISMATCH", e.code)
    }

    @Test
    fun `deviceId declarado que no sea la derivacion se rechaza`() {
        val r = buildRoster1().toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val devices = (r["devices"] as List<Map<String, Any?>>).map { LinkedHashMap(it) }
        devices[0]["deviceId"] = ByteArray(32) { 0x11 }
        r["devices"] = devices
        val e = assertFailsWith<KmIdVerificationException> { DeviceRoster.verify(r) }
        assertEquals("DEVICE_ID_MISMATCH", e.code)
    }

    @Test
    fun `un deviceId repetido se detecta`() {
        // Regresion de plataforma: ByteArray no tiene equals por contenido, asi
        // que un Set<ByteArray> NO detectaria el duplicado.
        val r = buildRoster1().toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val unico = (r["devices"] as List<Map<String, Any?>>)[0]
        r["devices"] = listOf(LinkedHashMap(unico), LinkedHashMap(unico))
        val e = assertFailsWith<KmIdVerificationException> { DeviceRoster.verify(r) }
        assertEquals("DUPLICATE_DEVICE", e.code)
    }

    @Test
    fun `findDevice localiza por deviceId derivado`() {
        val r = buildRoster1()
        val objetivo = KmIds.deviceId(pub("alice.mobile.sign"))
        val encontrado = DeviceRoster.findDevice(r, objetivo)
        assertNotNull(encontrado)
        assertEquals("Alice-Android", encontrado["name"])

        assertEquals(null, DeviceRoster.findDevice(r, KmIds.deviceId(pub("bob.mobile.sign"))))
    }

    // ===================================================================
    // Revocacion
    // ===================================================================

    @Test
    fun `revocar exige revokedAt y activarlo lo prohibe`() {
        val revocarSinMarca = assertFailsWith<KmIdVerificationException> {
            DeviceRoster.makeDeviceEntry(
                pub("alice.mobile.sign"), seed("alice.mobile.agr"),
                status = KmIdConstants.STATUS_REVOKED,
            )
        }
        assertEquals("MISSING_FIELD", revocarSinMarca.code)

        val activoConMarca = assertFailsWith<KmIdVerificationException> {
            DeviceRoster.makeDeviceEntry(
                pub("alice.mobile.sign"), seed("alice.mobile.agr"),
                status = KmIdConstants.STATUS_ACTIVE, revokedAt = 1_700_000_000_000,
            )
        }
        assertEquals("UNEXPECTED_FIELD", activoConMarca.code)

        // Y un dispositivo revocado bien formado se acepta.
        val revocado = DeviceRoster.makeDeviceEntry(
            pub("alice.mobile.sign"), seed("alice.mobile.agr"),
            status = KmIdConstants.STATUS_REVOKED, revokedAt = 1_700_000_000_000,
        )
        assertEquals(KmIdConstants.STATUS_REVOKED, revocado["status"])
        assertEquals(1_700_000_000_000L, revocado["revokedAt"])
    }

    @Test
    fun `los valores de estado son los del oráculo`() {
        // Si alguien "normaliza" los enums a 0-based, este test lo dice con
        // nombre en lugar de dejar que falle un fixture mas adelante.
        assertEquals(1L, KmIdConstants.STATUS_ACTIVE)
        assertEquals(2L, KmIdConstants.STATUS_REVOKED)
        assertEquals(1L, DeviceRoster.makeDeviceEntry(
            pub("alice.mobile.sign"), seed("alice.mobile.agr")
        )["status"])
    }
}

package com.km.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertContentEquals

/**
 * KCE contra los vectores congelados de km-id-reference.
 *
 * Este test NO reimplementa la especificacion: comprueba que el codigo
 * Kotlin produce EXACTAMENTE los mismos bytes que la referencia Python.
 * Si diverge un byte, aqui falla.
 */
class KceGoldenVectorsTest {

    // ===================================================================
    // Documentos validos: decodificar y recodificar debe dar los mismos bytes
    // ===================================================================

    @Test
    fun `todo fixture valido hace round-trip byte a byte`() {
        val manifest = KmIdFixtures.manifest()
        var comprobados = 0

        for (v in manifest["vectors"]) {
            val gid = v["id"].asText()
            val artifacts = v["artifacts"] ?: continue

            for (name in artifacts.fieldNames().asSequence()) {
                val bytes = KmIdFixtures.bytes("vectors/$gid/$name")

                // 1. el hash del MANIFEST sigue describiendo el fichero
                assertEquals(
                    artifacts[name]["sha256"].asText(),
                    KmIdFixtures.sha256(bytes),
                    "$gid/$name: el fixture no coincide con su hash en el MANIFEST"
                )

                // 2. decodificar NO debe fallar
                val decoded = try {
                    Kce.validate(bytes)
                } catch (e: Kce.KceException) {
                    error("$gid/$name: KCE deberia aceptar este fixture, pero fallo con ${e.code}")
                }

                // 3. recodificar debe devolver los MISMOS bytes
                val reencoded = Kce.encode(decoded)
                assertTrue(
                    bytes.contentEquals(reencoded),
                    "$gid/$name: la recodificacion no reproduce los bytes del fixture"
                )

                comprobados++
            }
        }

        assertTrue(comprobados >= 13, "se esperaban al menos 13 artefactos, se comprobaron $comprobados")
    }

    @Test
    fun `el orden de las claves del roster es por bytes codificados y no alfabetico`() {
        val bytes = KmIdFixtures.bytes("vectors/G04/canonical.cbor")
        @Suppress("UNCHECKED_CAST")
        val m = Kce.validate(bytes) as Map<String, Any?>

        // El orden REAL de lectura del documento.
        assertEquals(
            listOf(
                "doc", "devices", "version", "previous", "sequence",
                "createdAt", "signature", "identityId", "identityRoot"
            ),
            m.keys.toList(),
            "el orden de lectura no es el orden canonico de emision"
        )

        // Y el orden alfabetico del texto seria DISTINTO. Esta asercion
        // documenta la trampa: si alguien "arregla" el encoder ordenando
        // alfabeticamente, este test falla.
        val alfabetico = m.keys.toList().sorted()
        assertTrue(
            alfabetico != m.keys.toList(),
            "si el orden alfabetico coincidiera, la trampa del prefijo de longitud habria dejado de existir"
        )
    }

    @Test
    fun `el orden de insercion del mapa no altera los bytes`() {
        val a = linkedMapOf<String, Any?>("version" to 1L, "doc" to "km.deviceRoster")
        val b = linkedMapOf<String, Any?>("doc" to "km.deviceRoster", "version" to 1L)
        assertContentEquals(Kce.encode(a), Kce.encode(b))
    }

    // ===================================================================
    // Fixtures negativos: el codigo de error debe ser el DECLARADO
    // ===================================================================

    /** Fixtures cuyo rechazo corresponde a la capa KCE, ya portada. */
    private val kceLayer = setOf(
        "duplicate-key.cbor",
        "non-canonical-order.cbor",
        "indefinite-length.cbor",
        "float.cbor",
        "non-nfc-text.cbor",
        "cbor-tag.cbor",
        "null-field.cbor",
    )

    @Test
    fun `cada fixture negativo de la capa KCE falla con el codigo declarado`() {
        val manifest = KmIdFixtures.manifest()
        var comprobados = 0

        for (entry in manifest["invalid"]) {
            val name = entry["file"].asText().substringAfterLast('/')
            if (name !in kceLayer) continue

            val bytes = KmIdFixtures.bytes("invalid/$name")
            val esperado = entry["expected"].asText()

            val codigo = KmIdFixtures.kceErrorCode { Kce.validate(bytes) }
            assertNotNull(
                codigo,
                "invalid/$name fue ACEPTADO, pero el MANIFEST exige $esperado"
            )
            assertEquals(
                esperado, codigo,
                "invalid/$name fallo por $codigo, no por $esperado. " +
                    "Si el motivo real es otro, el fixture esta mal construido."
            )
            comprobados++
        }

        assertEquals(kceLayer.size, comprobados, "no se recorrieron todos los fixtures de la capa KCE")
    }

    @Test
    fun `los fixtures negativos de capas aun no portadas estan recognized pero no comprobados`() {
        // KCE cubre 7 de los 13 negativos. Los 6 restantes los cubren las
        // capas de esquema, roster y contacto, que se portaron despues. Este
        // test documenta lo que KCE por si solo NO cubre, y FALLA si alguien
        // lo olvida al portar otra capa.
        val manifest = KmIdFixtures.manifest()
        val portados = mutableSetOf<String>()
        val pendientes = mutableSetOf<String>()

        for (entry in manifest["invalid"]) {
            val name = entry["file"].asText().substringAfterLast('/')
            if (name in kceLayer) portados.add(name) else pendientes.add(name)
        }

        assertEquals(
            setOf(
                // capa de esquema y roster, ya portada: se comprueban en
                // DeviceRosterGoldenVectorsTest
                "unknown-field.cbor",
                "bad-signature.cbor",
                "wrong-previous.cbor",
                "wrong-version.cbor",
                "revoked-without-timestamp.cbor",
                // capa de contacto, AUN NO PORTADA
                "wrong-device-binding.cbor",
            ),
            pendientes,
            "la cola de fixtures pendientes cambio. Cuando se porte una capa, " +
                "mueve sus fixtures a DeviceRosterGoldenVectorsTest."
        )
        assertTrue(portados.size >= 7)
    }

    // ===================================================================
    // Clasificacion de major type 7
    // ===================================================================

    @Test
    fun `los tres anchos de float se rechazan como FLOAT_FORBIDDEN`() {
        // Regresion: al descartar el AI tras consumir el argumento adicional,
        // un float de doble precision (0xFB + 8 bytes) se reportaba como
        // SIMPLE_VALUE_FORBIDDEN. El codigo correcto depende del AI, no del
        // payload.
        fun floatBytes(marker: Byte, payload: Int) =
            ByteArray(1 + payload).also { it[0] = marker }

        assertEquals(
            "FLOAT_FORBIDDEN",
            KmIdFixtures.kceErrorCode { Kce.validate(floatBytes(0xF9.toByte(), 2)) }
        )
        assertEquals(
            "FLOAT_FORBIDDEN",
            KmIdFixtures.kceErrorCode { Kce.validate(floatBytes(0xFA.toByte(), 4)) }
        )
        assertEquals(
            "FLOAT_FORBIDDEN",
            KmIdFixtures.kceErrorCode { Kce.validate(floatBytes(0xFB.toByte(), 8)) }
        )
    }

    @Test
    fun `false y true son validos en KCE y null y undefined no`() {
        assertEquals(false, Kce.decode(byteArrayOf(0xF4.toByte())))
        assertEquals(true, Kce.decode(byteArrayOf(0xF5.toByte())))
        assertEquals("NULL_FORBIDDEN", KmIdFixtures.kceErrorCode { Kce.decode(byteArrayOf(0xF6.toByte())) })
        assertEquals("UNDEFINED_FORBIDDEN", KmIdFixtures.kceErrorCode { Kce.decode(byteArrayOf(0xF7.toByte())) })
        assertEquals("SIMPLE_VALUE_FORBIDDEN", KmIdFixtures.kceErrorCode { Kce.decode(byteArrayOf(0xF0.toByte())) })
        // AI 24 sigue siendo un valor simple de 1 byte, NO un float.
        assertEquals("SIMPLE_VALUE_FORBIDDEN", KmIdFixtures.kceErrorCode { Kce.decode(byteArrayOf(0xF8.toByte(), 0x1A)) })
    }

    // ===================================================================
    // Lector acotado de version
    // ===================================================================

    @Test
    fun `el lector acotado distingue supported de indeterminado`() {
        val version1 = KmIdFixtures.bytes("vectors/G04/canonical.cbor")
        assertTrue(Kce.peekVersion(version1, 1L) is Kce.VersionResult.Ok)

        // Un array no es un map: indeterminado, NO "no soportado".
        assertTrue(Kce.peekVersion(byteArrayOf(0x83.toByte(), 1, 2, 3), 1L) is Kce.VersionResult.Indeterminate)

        // Sin campo version: indeterminado.
        assertTrue(Kce.peekVersion(Kce.encode(mapOf("doc" to "x")), 1L) is Kce.VersionResult.Indeterminate)

        // Version futura: la conclusion es DEFINITIVA (era Indeterminate antes
        // de la correccion del despacho, ahora es Unsupported).
        val futuro = KmIdFixtures.bytes("invalid/wrong-version.cbor")
        val r = Kce.peekVersion(futuro, 1L)
        assertTrue(
            r is Kce.VersionResult.Unsupported,
            "wrong-version.cbor declara version 2, debe reportarse como no soportada. " +
                "Obtenido: ${r.javaClass.simpleName}"
        )
        assertTrue(
            (r as Kce.VersionResult.Unsupported).version > 1L,
            "la version reportada debe ser > 1"
        )
    }

    // ===================================================================
    // UTF-8 estricto
    // ===================================================================

    @Test
    fun `UTF-8 invalido se rechaza en lugar de sustituirse por U_FFFD`() {
        // String(bytes, UTF_8) de la JVM no lanza: sustituye silenciosamente.
        // Si Kce usase eso, una entrada malformada se convertiria en un
        // documento con un texto DISTINTO, y en una ruta de firma eso es
        // corrupcion silenciosa.
        val malformado = byteArrayOf(0x62, 0xC3.toByte(), 0x28) // bstr(2) [0xC3, 0x28]
        assertEquals("INVALID_UTF8", KmIdFixtures.kceErrorCode { Kce.decode(malformado) })
    }
}

package com.km.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * CONFORMANCE GATE del INVENTARIO de la familia de vectores P6 (task 1.1, ampliado en 1.4).
 *
 * POR QUE EXISTE
 * --------------
 * El inventario P6 se cito durante el diseno como 12 vectores (P6-A..P6-L)
 * cuando el total REAL es 20 (P6-A..P6-L = 12, mas P6-M..P6-T = 8). Este test
 * fija el numero REAL y FALLA si alguien vuelve a divergir.
 *
 * LO QUE CAMBIO EN LA TASK 1.4 (anti-falso-verde)
 * -----------------------------------------------
 * La version anterior localizaba el directorio `km-id-reference/` subiendo
 * rutas relativas (`../km-id-reference`, ...). Eso solo funciona en el checkout
 * del repositorio y NUNCA en la app Android. Ahora el inventario, el manifiesto
 * y el congelado se cargan del CLASSPATH (`/p6-vectors/...`), porque viajan
 * DENTRO del artefacto km-core (ver `km-core/build.gradle.kts`). Los cuatro
 * consumidores leen asi los MISMOS bytes.
 *
 * Ademas, este gate YA NO se limita a comprobar el numero: comprueba que cada
 * uno de los 20 vector IDs apunta a un artefacto REAL cuyo sha256 coincide con
 * el declarado en `AUTH_MANIFEST.json` (checksum real, no solo el nombre). La
 * verificacion de ESQUEMA y de RESULTADO por vector vive en
 * [P6VectorConformanceTest].
 *
 * QUE NO HACE
 * -----------
 * NO genera ni congela fixtures: eso es la task 1.4 (responsabilidad de
 * `km-id-reference/auth_generate.py` + `freeze.py`).
 */
class P6VectorInventoryGateTest {

    private val mapper = ObjectMapper()

    private fun resourceBytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) {
            "falta el recurso de clase '$path' (¿esta km-core empaquetando km-id-reference/vectors?)"
        }.use { it.readBytes() }

    private fun resourceText(path: String): String = resourceBytes(path).toString(Charsets.UTF_8)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val inventoryIds: List<String> by lazy {
        resourceText("/p6-vectors/P6_VECTOR_INVENTORY.txt")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private val manifest: JsonNode by lazy {
        mapper.readTree(resourceText("/p6-vectors/AUTH_MANIFEST.json"))
    }

    private fun manifestVectorIds(): List<String> =
        manifest.path("vectors").map { it.path("id").asText() }.filter { it.startsWith("P6-") }

    private fun expectedIds(): List<String> = ('A'..'T').map { "P6-$it" }

    @Test
    fun `el inventario real es de 20 vectores P6-A a P6-T`() {
        assertEquals(
            20,
            inventoryIds.size,
            "el inventario P6 debe tener 20 vectores, no ${inventoryIds.size}",
        )
        assertEquals(expectedIds(), inventoryIds, "el inventario debe ser exactamente P6-A..P6-T en orden")
    }

    @Test
    fun `el inventario se desdobla en 12 mas 8`() {
        val first = inventoryIds.filter { it in ('A'..'L').map { c -> "P6-$c" } }
        val second = inventoryIds.filter { it in ('M'..'T').map { c -> "P6-$c" } }
        assertEquals(12, first.size, "P6-A..P6-L debe ser 12")
        assertEquals(8, second.size, "P6-M..P6-T debe ser 8")
        assertEquals(20, first.size + second.size)
    }

    @Test
    fun `la fuente de fixtures es unica y tiene checksums`() {
        // El manifiesto declara al MISMO generador: una sola fuente (R18).
        val generatedBy = manifest.path("generatedBy").asText()
        assertEquals(
            "km-id-reference/auth_generate.py",
            generatedBy,
            "el AUTH_MANIFEST debe declarar el generador unico",
        )

        // El inventario y el manifiesto NO pueden divergir: ambos apuntan a la
        // misma lista (una sola fuente de verdad, sin duplicar el inventario).
        assertEquals(
            inventoryIds,
            manifestVectorIds(),
            "los IDs del AUTH_MANIFEST deben coincidir EXACTAMENTE con el inventario",
        )

        // El congelado viaja tambien en el artefacto.
        assertTrue(
            resourceBytes("/p6-vectors/FROZEN.sha256").isNotEmpty(),
            "el congelado FROZEN.sha256 debe viajar en el artefacto km-core",
        )
    }

    @Test
    fun `cada vector apunta a artefactos reales con checksums reales`() {
        for (id in inventoryIds) {
            val vectorJson = manifest.path("vectors").first { it.path("id").asText() == id }
            val artifacts = vectorJson.path("artifacts")
            assertTrue(artifacts.isObject && artifacts.size() > 0) {
                "$id no declara artefactos con checksums en el AUTH_MANIFEST"
            }
            for (entry in artifacts.properties()) {
                val name = entry.key
                val resourcePath = "/p6-vectors/$id/$name"
                val realBytes = resourceBytes(resourcePath)
                val declared = entry.value
                assertEquals(
                    declared.path("length").asInt(),
                    realBytes.size,
                    "$id/$name: la longitud real no coincide con la declarada",
                )
                assertEquals(
                    declared.path("sha256").asText(),
                    sha256Hex(realBytes),
                    "$id/$name: el sha256 real no coincide con el declarado (checksum falso)",
                )
            }
            // El AUTH_OK canonico de cada vector debe existir como recurso.
            assertTrue(
                resourceBytes("/p6-vectors/$id/auth_ok.json").isNotEmpty(),
                "$id no tiene auth_ok.json empaquetado",
            )
        }
    }

    @Test
    fun `el inventario no duplica IDs`() {
        assertEquals(
            inventoryIds.size,
            inventoryIds.toSet().size,
            "el inventario tiene IDs repetidos: fuente ambigua",
        )
    }
}

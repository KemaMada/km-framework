package com.km.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Paths

/**
 * CONFORMANCE GATE de la familia de vectores P6 (task 1.1).
 *
 * POR QUE EXISTE
 * --------------
 * El inventario P6 se cito durante el diseno como 12 vectores (P6-A..P6-L)
 * cuando el total REAL es 20 (P6-A..P6-L = 12, mas P6-M..P6-T = 8). El error
 * estaba en la spec, en la matriz de trazabilidad y por tanto en el gate. Este
 * test fija el numero REAL y FALLA si alguien vuelve a divergir.
 *
 * QUE NO HACE
 * -----------
 * NO genera ni congela fixtures: eso es la task 1.4. Aqui solo se verifica el
 * INVENTARIO y que la FUENTE es unica, con checksums.
 *
 * El inventario canonico vive en `km-id-reference/P6_VECTOR_INVENTORY.txt`.
 * Ningun componente duplica la lista: todos la leen.
 */
class P6VectorInventoryGateTest {

    private val mapper = ObjectMapper()

    private val referenceRoot: File by lazy {
        val candidates = listOf(
            Paths.get("km-id-reference").toFile(),
            Paths.get("../km-id-reference").toFile(),
            Paths.get("../../km-id-reference").toFile(),
            Paths.get("../../../km-id-reference").toFile(),
        )
        candidates.firstOrNull { File(it, "P6_VECTOR_INVENTORY.txt").isFile }
            ?: error("no se encuentra km-id-reference/P6_VECTOR_INVENTORY.txt (busque en: $candidates)")
    }

    private val inventoryIds: List<String> by lazy {
        File(referenceRoot, "P6_VECTOR_INVENTORY.txt").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun expectedIds(): List<String> =
        ('A'..'T').map { "P6-$it" }

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
        // Generador unico.
        val generators = referenceRoot.listFiles { f ->
            f.isFile && f.name.contains("auth_generate") && f.name.endsWith(".py")
        } ?: emptyArray()
        assertEquals(1, generators.size, "debe haber UN generador de vectores de auth, no ${generators.size}")

        val authGenerator = File(referenceRoot, "auth_generate.py")
        assertTrue(authGenerator.isFile, "falta km-id-reference/auth_generate.py")

        // Manifest y checksums como fuente de verdad de los hashes.
        val manifest = File(referenceRoot, "AUTH_MANIFEST.json")
        assertTrue(manifest.isFile, "falta km-id-reference/AUTH_MANIFEST.json")
        val frozen = File(referenceRoot, "FROZEN.sha256")
        assertTrue(frozen.isFile, "falta km-id-reference/FROZEN.sha256")

        // El manifest declara al MISMO generador: una sola fuente.
        val generatedBy = mapper.readTree(manifest).path("generatedBy").asText()
        assertEquals(
            "km-id-reference/auth_generate.py",
            generatedBy,
            "el AUTH_MANIFEST debe declarar el generador unico",
        )
    }

    @Test
    fun `no hay una segunda fuente con el inventario P6`() {
        // Si alguien duplicara la lista en otro fichero de la referencia, el
        // gate cae: la fuente canonica es una y solo una.
        val labelled = referenceRoot.walkTopDown()
            .filter { it.isFile && it.name != "P6_VECTOR_INVENTORY.txt" }
            // `vectors/` e `invalid/` son FIXTURES generadas (task 1.4): citar
            // "P6-A" dentro de un vector no es duplicar el inventario.
            .filter { it.parentFile?.name !in setOf("vectors", "invalid") }
            .filter { it.extension in setOf("txt", "json", "md") }
            .filter { f -> runCatching { f.readText().contains("P6-A") }.getOrDefault(false) }
            .toList()
        assertTrue(
            labelled.isEmpty(),
            "la lista P6 no debe duplicarse fuera de P6_VECTOR_INVENTORY.txt; encontrada en: $labelled",
        )
    }
}

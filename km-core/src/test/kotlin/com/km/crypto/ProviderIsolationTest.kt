package com.km.crypto

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * 3O.0.6 — Auditoria de aislamiento del provider criptografico.
 *
 * INVARIANTE ARQUITECTURAL:
 * `org.bouncycastle.*` SOLO puede aparecer en el package
 * `com.km.crypto.provider`. El resto de km-core depende
 * exclusivamente de las interfaces de KeyMessage (X25519, Kdf, Aead).
 *
 * Asi el Double Ratchet y SecureFrame no conocen la libreria de
 * implementacion y pueden probarse contra dobles controlados.
 *
 * Esta auditoria se automatiza para que no pueda degradarse.
 */
class ProviderIsolationTest {

    private fun sourceFiles(): List<File> {
        val root = File("src/main/kotlin")
        assertTrue(root.exists(), "no se encuentra src/main/kotlin desde ${File(".").absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    @DisplayName("ningun archivo fuera de crypto/provider importa org.bouncycastle")
    fun `O4-01 bouncycastle solo aparece en el package provider`() {
        val offenders = sourceFiles().filter { file ->
            val path = file.path.replace(File.separatorChar, '/')
            !path.contains("/crypto/provider/") &&
                file.readText().contains("org.bouncycastle")
        }
        assertTrue(
            offenders.isEmpty(),
            "archivos fuera de crypto/provider que importan bouncycastle:\n" +
                offenders.joinToString("\n") { "  - $it" },
        )
    }

    @Test
    @DisplayName("las interfaces de KeyMessage no dependen de bouncycastle")
    fun `O4-02 las interfaces de primitivas no mencionan bouncycastle`() {
        val interfaces = listOf("X25519.kt", "Kdf.kt", "Aead.kt")
        for (name in interfaces) {
            val file = sourceFiles().first { it.name == name }
            assertFalse(
                file.readText().contains("org.bouncycastle"),
                "$name no debe referenciar bouncycastle: es la API del Double Ratchet",
            )
        }
    }

    @Test
    @DisplayName("las implementaciones BC implementan las interfaces propias")
    fun `O4-03 las implementaciones BC implementan las interfaces de KeyMessage`() {
        val expectations = mapOf(
            "BcX25519.kt" to ": X25519",
            "BcHkdfSha256.kt" to ": Kdf",
            "BcChaCha20Poly1305.kt" to ": Aead",
        )
        for ((fileName, declaration) in expectations) {
            val file = sourceFiles().first { it.name == fileName }
            assertTrue(
                file.readText().contains(declaration),
                "$fileName debe declarar '$declaration'",
            )
        }
    }

    @Test
    @DisplayName("las excepciones de dominio son de KeyMessage, no de la libreria")
    fun `O4-05 las excepciones de dominio no provienen de bouncycastle`() {
        // AllZeroSharedSecretException y AeadAuthenticationException deben ser
        // propias, para que el Double Ratchet no dependa del provider.
        val allZero = sourceFiles().first { it.name == "X25519.kt" }
        assertTrue(allZero.readText().contains("class AllZeroSharedSecretException"))
        val aead = sourceFiles().first { it.name == "Aead.kt" }
        assertTrue(aead.readText().contains("class AeadAuthenticationException"))
    }
}

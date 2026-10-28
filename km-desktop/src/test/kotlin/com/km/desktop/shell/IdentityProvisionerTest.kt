package com.km.desktop.shell

import com.km.crypto.Ed25519Impl
import com.km.desktop.IdentityKeystore
import com.km.desktop.client.RecoveryService
import com.km.mnemonic.Mnemonic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

/**
 * La recuperacion en la PUERTA de arranque (antes de que exista una sesion).
 *
 * ## LOS DOS NEGATIVOS QUE IMPORTAN
 *
 * - Una frase invalida no llama al escritor ni crea el fichero. El orden es
 *   "validar, luego escribir", no al reves.
 * - Sobrescribir un almacen existente sin la confirmacion literal no cambia ni
 *   un byte (se compara el MD5).
 */
class IdentityProvisionerTest {

    private val entropy: ByteArray = ByteArray(32) { (it + 1).toByte() }
    private val frase: String = Mnemonic.fromEntropy(entropy).display()

    private fun md5(path: Path): String =
        MessageDigest.getInstance("MD5").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }

    private fun identidadEsperada(): String =
        IdentityKeystore.deriveIdentityId(Ed25519Impl().keyPairFromSeed(entropy).publicKey)

    @Test
    fun `recuperar deriva la MISMA identidad y el almacen abre con la frase nueva`() {
        val dir = createTempDirectory("km-gate-recovery")
        try {
            val path = dir.resolve("identity.kmkey")

            val resultado = IdentityProvisioner.restore(
                path = path,
                phrase = frase,
                newPassphrase = "pass-nueva".toCharArray(),
                confirmation = "",
                overwriting = false,
            )

            assertTrue(resultado is IdentityProvisioner.Outcome.Restored, "$resultado")
            assertEquals(identidadEsperada(), (resultado as IdentityProvisioner.Outcome.Restored).identityId)

            val contenido = IdentityKeystore.loadContents(path, "pass-nueva".toCharArray())
            assertEquals(identidadEsperada(), contenido.identity.identityId.value)
            assertNotNull(contenido.deviceKeys, "restaurar REGENERA las claves de dispositivo")
            assertTrue(Files.isRegularFile(path))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `la misma frase produce el mismo identityId en dos equipos`() {
        val uno = createTempDirectory("km-gate-recovery")
        val dos = createTempDirectory("km-gate-recovery")
        try {
            val a = IdentityProvisioner.restore(
                uno.resolve("identity.kmkey"), frase, "pass-a".toCharArray(), "", false,
            )
            val b = IdentityProvisioner.restore(
                dos.resolve("identity.kmkey"), frase, "pass-b".toCharArray(), "", false,
            )

            assertTrue(a is IdentityProvisioner.Outcome.Restored)
            assertTrue(b is IdentityProvisioner.Outcome.Restored)
            assertEquals(
                (a as IdentityProvisioner.Outcome.Restored).identityId,
                (b as IdentityProvisioner.Outcome.Restored).identityId,
                "la identidad se deriva de la frase, no del equipo",
            )
        } finally {
            uno.toFile().deleteRecursively()
            dos.toFile().deleteRecursively()
        }
    }

    @Test
    fun `una frase invalida no llama al escritor ni crea el almacen`() {
        val dir = createTempDirectory("km-gate-recovery")
        try {
            val path = dir.resolve("identity.kmkey")
            var escrituras = 0

            val resultado = IdentityProvisioner.restore(
                path = path,
                phrase = "abandon ability able",
                newPassphrase = "pass-nueva".toCharArray(),
                confirmation = "",
                overwriting = false,
            ) { _, _, _ ->
                escrituras++
                error("no se puede escribir con una frase invalida")
            }

            assertEquals(
                IdentityProvisioner.Outcome.Rejected(RecoveryService.CODE_PHRASE_INVALID),
                resultado,
            )
            assertEquals(0, escrituras, "validar va ANTES de escribir")
            assertFalse(Files.exists(path))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `un checksum roto tampoco escribe`() {
        val dir = createTempDirectory("km-gate-recovery")
        try {
            val path = dir.resolve("identity.kmkey")
            val veinticuatroAbandon = (0 until 24).joinToString(" ") { "abandon" }

            val resultado = IdentityProvisioner.restore(
                path, veinticuatroAbandon, "pass".toCharArray(), "", false,
            )

            assertEquals(
                IdentityProvisioner.Outcome.Rejected(RecoveryService.CODE_PHRASE_INVALID),
                resultado,
            )
            assertFalse(Files.exists(path))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sobrescribir sin la confirmacion no cambia ni un byte`() {
        val dir = createTempDirectory("km-gate-recovery")
        try {
            val path = dir.resolve("identity.kmkey")
            IdentityKeystore.create(path, "pass-vieja".toCharArray())
            val antes = md5(path)

            val resultado = IdentityProvisioner.restore(
                path = path,
                phrase = frase,
                newPassphrase = "pass-nueva".toCharArray(),
                confirmation = "si",
                overwriting = true,
            )

            assertEquals(
                IdentityProvisioner.Outcome.Rejected(RecoveryService.CODE_CONFIRMATION_REQUIRED),
                resultado,
            )
            assertEquals(antes, md5(path), "sin confirmacion el almacen no se toca")
            // Y sigue abriendo con la frase de paso vieja.
            IdentityKeystore.load(path, "pass-vieja".toCharArray())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sobrescribir con la confirmacion literal escribe la identidad nueva`() {
        val dir = createTempDirectory("km-gate-recovery")
        try {
            val path = dir.resolve("identity.kmkey")
            val original = IdentityKeystore.create(path, "pass-vieja".toCharArray()).identityId

            val resultado = IdentityProvisioner.restore(
                path = path,
                phrase = frase,
                newPassphrase = "pass-nueva".toCharArray(),
                confirmation = "  " + RecoveryService.CONFIRMATION + "  ",
                overwriting = true,
            )

            assertTrue(resultado is IdentityProvisioner.Outcome.Restored, "$resultado")
            val nueva = IdentityKeystore.loadContents(path, "pass-nueva".toCharArray()).identity
            assertEquals(identidadEsperada(), nueva.identityId.value)
            assertFalse(original == nueva.identityId, "la frase es de otra identidad")
            assertNotNull(
                IdentityKeystore.loadContents(path, "pass-nueva".toCharArray()).deviceKeys,
                "las claves de dispositivo se regeneran",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sin frase de paso nueva no se escribe`() {
        val dir = createTempDirectory("km-gate-recovery")
        try {
            val path = dir.resolve("identity.kmkey")

            val resultado = IdentityProvisioner.restore(
                path, frase, CharArray(0), "", false,
            )

            assertEquals(
                IdentityProvisioner.Outcome.Rejected("PASSPHRASE_EMPTY"),
                resultado,
            )
            assertFalse(Files.exists(path))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `una frase pegada con saltos de linea se acepta`() {
        val dir = createTempDirectory("km-gate-recovery")
        try {
            val path = dir.resolve("identity.kmkey")
            val conSaltos = frase.split(" ").chunked(6).joinToString("\n") { it.joinToString(" ") }

            val resultado = IdentityProvisioner.restore(
                path, conSaltos, "pass".toCharArray(), "", false,
            )

            assertTrue(resultado is IdentityProvisioner.Outcome.Restored, "$resultado")
            assertEquals(
                identidadEsperada(),
                IdentityKeystore.load(path, "pass".toCharArray()).identityId.value,
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

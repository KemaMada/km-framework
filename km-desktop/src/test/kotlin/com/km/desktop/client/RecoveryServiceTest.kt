package com.km.desktop.client

import com.km.desktop.IdentityKeystore
import com.km.mnemonic.Mnemonic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

/**
 * 3.8 — La frase de recuperacion: ver, validar y restaurar SOLO identidad.
 *
 * ## LOS DOS NEGATIVOS QUE IMPORTAN
 *
 * - Sin la confirmacion escrita, el almacen NO se toca (se compara el MD5).
 * - Una frase invalida no escribe NADA. Un flujo que "acepta" y falla al
 *   escribir es el peor sitio para descubrir un checksum roto.
 */
class RecoveryServiceTest {

    private fun keystore(destino: Path): Path {
        val path = destino.resolve("identity.kmkey")
        IdentityKeystore.create(path, "frase-de-paso".toCharArray())
        return path
    }

    private fun service(path: Path): RecoveryService = RecoveryService(
        identity = { IdentityKeystore.load(path, "frase-de-paso".toCharArray()) },
        keystorePath = path,
        passphrase = { "frase-de-paso".toCharArray() },
    )

    private fun md5(path: Path): String =
        MessageDigest.getInstance("MD5").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `la frase tiene 24 palabras y vuelve a la MISMA identidad`() {
        val dir = createTempDirectory("km-recovery")
        try {
            val path = keystore(dir)
            val antes = IdentityKeystore.load(path, "frase-de-paso".toCharArray()).identityId
            val servicio = service(path)

            val palabras = servicio.words()
            assertEquals(Mnemonic.WORD_COUNT, palabras.size)
            assertTrue(palabras.all { it.isNotBlank() })

            val resultado = servicio.restore(palabras.joinToString(" "), RecoveryService.CONFIRMATION)
            assertTrue(resultado is RecoveryService.RestoreOutcome.Restored, "$resultado")
            val nueva = IdentityKeystore.load(path, "frase-de-paso".toCharArray()).identityId
            assertEquals(antes, nueva, "la semilla restaura la MISMA identidad")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sin la confirmacion escrita no se escribe nada`() {
        val dir = createTempDirectory("km-recovery")
        try {
            val path = keystore(dir)
            val servicio = service(path)
            val antes = md5(path)
            val palabras = servicio.words().joinToString(" ")

            val resultado = servicio.restore(palabras, "si")
            assertEquals(
                RecoveryService.RestoreOutcome.Rejected(RecoveryService.CODE_CONFIRMATION_REQUIRED),
                resultado,
            )
            assertEquals(antes, md5(path), "el almacen no cambio ni un byte")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `una frase invalida no se escribe`() {
        val dir = createTempDirectory("km-recovery")
        try {
            val path = keystore(dir)
            val servicio = service(path)
            val antes = md5(path)

            val resultado = servicio.restore("abandon ability able", RecoveryService.CONFIRMATION)
            assertEquals(
                RecoveryService.RestoreOutcome.Rejected(RecoveryService.CODE_PHRASE_INVALID),
                resultado,
            )
            assertEquals(antes, md5(path))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `validate cuenta palabras y checksum sin escribir`() {
        val dir = createTempDirectory("km-recovery")
        try {
            val path = keystore(dir)
            val servicio = service(path)
            val antes = md5(path)

            val valida = servicio.validate(servicio.words().joinToString(" "))
            assertTrue(valida.ok)
            assertEquals(24, valida.wordCount)

            val corta = servicio.validate("abandon ability able")
            assertEquals(3, corta.wordCount)
            assertEquals(false, corta.checksumOk)

            val checksumRoto = servicio.validate(
                (0 until 24).joinToString(" ") { "abandon" },
            )
            assertEquals(false, checksumRoto.checksumOk, "24 palabras con checksum malo no valen")

            assertEquals(antes, md5(path), "validar no escribe")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sin frase de paso en memoria la restauracion se rechaza`() {
        val dir = createTempDirectory("km-recovery")
        try {
            val path = keystore(dir)
            val servicio = RecoveryService(
                identity = { IdentityKeystore.load(path, "frase-de-paso".toCharArray()) },
                keystorePath = path,
                passphrase = { null },
            )
            val antes = md5(path)
            val resultado = servicio.restore(
                servicio.words().joinToString(" "),
                RecoveryService.CONFIRMATION,
            )
            assertEquals(
                RecoveryService.RestoreOutcome.Rejected(RecoveryService.CODE_NO_PASSPHRASE),
                resultado,
            )
            assertEquals(antes, md5(path))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `restaurar otra frase cambia la identidad`() {
        val dir = createTempDirectory("km-recovery")
        try {
            val path = keystore(dir)
            val original = IdentityKeystore.load(path, "frase-de-paso".toCharArray()).identityId
            val otra = Mnemonic.fromEntropy(ByteArray(32) { (it + 1).toByte() }).display()

            val resultado = service(path).restore(otra, RecoveryService.CONFIRMATION)
            assertTrue(resultado is RecoveryService.RestoreOutcome.Restored)
            val nueva = IdentityKeystore.load(path, "frase-de-paso".toCharArray()).identityId
            assertNotEquals(original, nueva)
            assertEquals(
                (resultado as RecoveryService.RestoreOutcome.Restored).identityId,
                nueva.value,
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

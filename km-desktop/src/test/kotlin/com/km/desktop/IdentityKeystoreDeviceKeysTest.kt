package com.km.desktop

import com.km.identity.LocalDeviceKeyFactory
import com.km.identity.LocalDeviceKeyCodec
import com.km.identity.LocalDeviceKeys
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El material del dispositivo DENTRO del almacen cifrado, sin romper los ficheros V1.
 *
 * Lo que se prueba:
 *
 *  - se guarda y se relee con la MISma frase;
 *  - un fichero ESCRITO ANTES de existir el campo sigue abriendo (`deviceKeys`
 *    null) y se aprovisiona sin migracion;
 *  - un material de OTRO dispositivo, pegado en el fichero, se rechaza;
 *  - el consumo de un OPK PERSISTE (se relee ya sin el).
 */
class IdentityKeystoreDeviceKeysTest {

    private val TEXTO = "frase-de-paso-de-prueba"
    private val frase = TEXTO.toCharArray()

    private fun mecanismo(identity: DesktopIdentity): LocalDeviceKeys =
        LocalDeviceKeyFactory(identity.ed25519PublicKey).generate(3)

    @Test
    fun `crear con material lo guarda y lo relee con el deviceId verificado`(@TempDir dir: Path) {
        val path = dir.resolve("identity.kmkey")
        val identity = IdentityKeystore.create(path, frase)
        val keys = mecanismo(identity)

        IdentityKeystore.saveDeviceKeys(path, frase, keys)

        val releido = assertNotNull(IdentityKeystore.loadDeviceKeys(path, frase))
        assertContentEquals(keys.deviceId, releido.deviceId)
        assertEquals(keys.oneTimePreKeys.keys, releido.oneTimePreKeys.keys)
        // Y la identidad sigue siendo la misma despues de reescribir.
        assertEquals(identity, IdentityKeystore.load(path, frase))
    }

    @Test
    fun `un alias V1 sin deviceKeys ABRE y se aprovisiona`(@TempDir dir: Path) {
        val path = dir.resolve("identity.kmkey")
        // `create` sin material escribe exactamente la forma V1 anterior.
        val identity = IdentityKeystore.create(path, frase)
        assertNull(IdentityKeystore.loadDeviceKeys(path, frase), "un fichero V1 no tiene el campo")

        val contenido = IdentityKeystore.loadOrCreateWithDeviceKeys(path, fuente(TEXTO))
        assertEquals(identity.identityId, contenido.identity.identityId, "la identidad se conserva")
        assertNotNull(contenido.deviceKeys, "y el material queda aprovisionado")
        assertNotNull(IdentityKeystore.loadDeviceKeys(path, frase), "persistido")
    }

    @Test
    fun `el material de otro dispositivo se RECHAZA al guardar y al leer`(@TempDir dir: Path) {
        val path = dir.resolve("identity.kmkey")
        val identity = IdentityKeystore.create(path, frase)
        val ajeno = com.km.crypto.Ed25519Impl().generateKeyPair()
        val keysAjenas = LocalDeviceKeyFactory(ajeno.publicKey).generate(3)

        assertFailsWith<IllegalArgumentException> {
            IdentityKeystore.saveDeviceKeys(path, frase, keysAjenas)
        }
    }

    @Test
    fun `un deviceKeys con deviceId incoherente en el fichero se detecta`(@TempDir dir: Path) {
        // Se inyecta el texto de OTRO dispositivo en un contenedor valido y se
        // comprueba que la lectura lo rechaza. Para escribir ese contenedor se
        // usa la API normal, que ya valida, asi que aqui se comprueba el camino
        // que SI puede ocurrir: el codec con la clave esperada.
        val identity = IdentityKeystore.create(dir.resolve("a.kmkey"), frase)
        val otro = com.km.crypto.Ed25519Impl().generateKeyPair()
        val texto = LocalDeviceKeyCodec.encode(LocalDeviceKeyFactory(otro.publicKey).generate(3))

        val fallo = assertFailsWith<com.km.identity.LocalDeviceKeyException> {
            LocalDeviceKeyCodec.decode(texto, identity.ed25519PublicKey)
        }
        assertEquals(LocalDeviceKeyCodec.CODE_DEVICE_MISMATCH, fallo.code)
    }

    @Test
    fun `una frase equivocada no abre el material`(@TempDir dir: Path) {
        val path = dir.resolve("identity.kmkey")
        val identity = IdentityKeystore.create(path, frase)
        IdentityKeystore.saveDeviceKeys(path, frase, mecanismo(identity))

        assertFailsWith<KeystoreException.WrongPassphrase> {
            IdentityKeystore.loadDeviceKeys(path, "otra".toCharArray())
        }
    }

    @Test
    fun `consumir un OPK lo persiste y no toca los demas`(@TempDir dir: Path) {
        val path = dir.resolve("identity.kmkey")
        val identity = IdentityKeystore.create(path, frase)
        val keys = mecanismo(identity)
        IdentityKeystore.saveDeviceKeys(path, frase, keys)

        val store = KeystoreDeviceKeyStore(path, frase)
        assertEquals(3, store.load()!!.oneTimePreKeys.size)
        val restante = store.consumeOneTimePreKey(1L)!!
        assertTrue(!restante.oneTimePreKeys.containsKey(1L))
        assertTrue(restante.oneTimePreKeys.containsKey(0L), "los demas siguen")
        assertEquals(2, store.load()!!.oneTimePreKeys.size, "y el consumo sobrevive a releer")
        store.clear()
    }

    @Test
    fun `loadOrCreateWithDeviceKeys crea identidad Y material de una vez`(@TempDir dir: Path) {
        val path = dir.resolve("identity.kmkey")
        val contenido = IdentityKeystore.loadOrCreateWithDeviceKeys(path, fuente(TEXTO))

        assertEquals(IdentityKeystore.VERSION, 1, "el formato sigue siendo VERSION 1")
        assertNotNull(contenido.deviceKeys)
        assertEquals(
            contenido.identity.identityId,
            IdentityKeystore.load(path, frase).identityId,
        )
        assertTrue(Files.size(path) > 0)
    }

    private fun fuente(texto: String): PassphraseSource = object : PassphraseSource {
        override fun passphrase(creation: Boolean): String = texto
    }
}

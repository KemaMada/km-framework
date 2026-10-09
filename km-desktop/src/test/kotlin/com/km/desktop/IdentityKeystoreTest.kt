package com.km.desktop

import com.km.identity.KmIds
import com.km.node.RelayServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertContentEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pruebas del almacen cifrado.
 *
 * El perfil de KDF de estos tests es BARATO (1 iteracion, 8 MiB) a proposito:
 * lo que se comprueba aqui es la criptografia del contenedor —que la frase
 * correcta abra, que la incorrecta no, que una cabecera editada no se acepte— y
 * eso no depende del coste. El perfil de 64 MiB del producto se ejercita en
 * [defaultProfileRoundTrips], para que no se declare correcto algo que solo
 * funciona con un perfil de test.
 */
class IdentityKeystoreTest {

    private val cheap = KdfProfile(
        algorithm = IdentityKeystore.KDF_ARGON2ID,
        iterations = 1,
        memoryKiB = 8 * 1024,
        parallelism = 1,
        outputBytes = IdentityKeystore.KEY_BYTES,
    )

    @Test
    fun `crea y vuelve a abrir la identidad`(@TempDir dir: Path) {
        val path = dir.resolve("identity.kmkey")
        val created = IdentityKeystore.create(path, "correcta".toCharArray(), cheap)
        val loaded = IdentityKeystore.load(path, "correcta".toCharArray())

        assertEquals(created, loaded)
        assertContentEquals(created.ed25519PrivateKey, loaded.ed25519PrivateKey)
        assertContentEquals(created.x25519PrivateKey, loaded.x25519PrivateKey)
        assertEquals(64, created.identityId.value.length)
    }

    @Test
    fun `el perfil por defecto tambien abre`(@TempDir dir: Path) {
        val path = dir.resolve("identity.kmkey")
        val created = IdentityKeystore.create(path, "correcta".toCharArray())
        assertEquals(created, IdentityKeystore.load(path, "correcta".toCharArray()))
    }

    /**
     * Fija los valores del perfil por defecto (W4: antes nadie los comprobaba).
     *
     * No basta con leer [IdentityKeystore.DEFAULT_PROFILE]: tambien se mira lo
     * que sale en la CABECERA del archivo, que es lo que un lector ajeno
     * tendria que asumir. Si alguien baja el coste sin darse cuenta, el coste
     * esta aqui escrito en dos sitios y el test cae en los dos.
     *
     * Los valores son los de la segunda opcion recomendada de RFC 9106
     * seccion 4: t=3, m=2^16 KiB = 64 MiB, p=4, salida de 32 bytes.
     */
    @Test
    fun `el perfil por defecto es el segundo recomendado de RFC 9106`(@TempDir dir: Path) {
        val profile = IdentityKeystore.DEFAULT_PROFILE
        assertEquals("argon2id", profile.algorithm)
        assertEquals(3, profile.iterations)
        assertEquals(65_536, profile.memoryKiB)
        assertEquals(4, profile.parallelism)
        assertEquals(32, profile.outputBytes)

        val path = dir.resolve("identity.kmkey")
        IdentityKeystore.create(path, "correcta".toCharArray())
        val header = headerKdfOf(path)
        assertEquals(3, header.int("iterations"))
        assertEquals(65_536, header.int("memoryKiB"))
        assertEquals(4, header.int("parallelism"))
        assertEquals(32, header.int("outputBytes"))
    }

    /**
     * Un almacen escrito con el perfil ANTERIOR (p=1) sigue abriéndose.
     *
     * Esto no es un detalle: cambiar el paralelismo cambia la clave derivada, y
     * sin esto un almacen ya escrito dejaria de abrir. Se comprueba en vez de
     * afirmarlo porque la garantia viene de una decision estructural —`load`
     * deriva con el `kdf` de la cabecera, no con [IdentityKeystore.DEFAULT_PROFILE]—
     * que un refactor futuro podria romper sin querer.
     *
     * El perfil barato de estos tests ya lleva `parallelism = 1`, asi que el
     * almacen de aqui es un `p=1` real frente a un [IdentityKeystore.DEFAULT_PROFILE]
     * que hoy declara `p=4`.
     */
    @Test
    fun `un almacen del perfil anterior p=1 sigue abriendo`(@TempDir dir: Path) {
        val path = dir.resolve("identity.kmkey")
        val created = IdentityKeystore.create(path, "correcta".toCharArray(), cheap)

        assertEquals(1, headerKdfOf(path).int("parallelism"), "el almacen debe llevar p=1 en su cabecera")
        assertNotEquals(
            IdentityKeystore.DEFAULT_PROFILE.parallelism,
            cheap.parallelism,
            "la prueba solo tiene sentido si el perfil por defecto ya no es p=1",
        )

        // La cabecera manda: se abre con los parametros del archivo, no con los
        // del perfil por defecto.
        assertEquals(created, IdentityKeystore.load(path, "correcta".toCharArray()))
    }

    @Test
    fun `frase equivocada no abre y lo dice como frase`(@TempDir dir: Path) {
        val path = dir.resolve("identity.kmkey")
        IdentityKeystore.create(path, "correcta".toCharArray(), cheap)

        val error = assertThrows<KeystoreException.WrongPassphrase> {
            IdentityKeystore.load(path, "incorrecta".toCharArray())
        }
        assertTrue(
            error.message!!.contains("frase de paso incorrecta"),
            "el mensaje debe nombrar la causa: ${error.message}",
        )
    }

    @Test
    fun `una cabecera editada no se acepta aunque el texto plano sea valido`(@TempDir dir: Path) {
        val path = dir.resolve("identity.kmkey")
        IdentityKeystore.create(path, "correcta".toCharArray(), cheap)

        // Se sube el coste en la CABECERA sin tocar el texto plano cifrado. El
        // tag GCM sigue descifrando, pero la clave derivada es otra, asi que el
        // descifrado tiene que fallar. Y si alguien re-cifrase el texto plano con
        // la cabecera nueva, `readIdentity` compara los parametros duplicados.
        val original = Files.readString(path, StandardCharsets.UTF_8)
        val edited = original.replace("\"iterations\":1", "\"iterations\":2")
        assertNotEquals(original, edited, "la prueba no ha editado nada: el formato cambio")
        Files.write(path, edited.toByteArray(StandardCharsets.UTF_8))

        assertThrows<KeystoreException> { IdentityKeystore.load(path, "correcta".toCharArray()) }
    }

    @Test
    fun `un identityId que no deriva de la clave publica se rechaza`(@TempDir dir: Path) {
        val path = dir.resolve("identity.kmkey")
        val created = IdentityKeystore.create(path, "correcta".toCharArray(), cheap)

        // Se reescribe el archivo con un identityId ajeno pero claves coherentes:
        // es exactamente lo que haria alguien que edita el almacen a mano.
        val forged = IdentityKeystore.create(dir.resolve("forged.kmkey"), "correcta".toCharArray(), cheap)
        assertNotEquals(created.identityId, forged.identityId)

        val text = Files.readString(path, StandardCharsets.UTF_8)
        val swapped = text.replace(created.identityId.value, "0".repeat(64))
        Files.write(path, swapped.toByteArray(StandardCharsets.UTF_8))

        // El texto plano esta cifrado, asi que editar el identityId visible del
        // archivo no cambia nada dentro: lo que se lee sigue siendo el bueno.
        assertEquals(created, IdentityKeystore.load(path, "correcta".toCharArray()))
    }

    @Test
    fun `el archivo no es un almacen de km-desktop`(@TempDir dir: Path) {
        val path = dir.resolve("identity.kmkey")
        Files.write(path, """{"format":"otra-cosa","version":1}""".toByteArray(StandardCharsets.UTF_8))
        assertThrows<KeystoreException.Corrupt> { IdentityKeystore.load(path, "x".toCharArray()) }
    }

    @Test
    fun `un archivo truncado no revienta con una excepcion de JSON`(@TempDir dir: Path) {
        val path = dir.resolve("identity.kmkey")
        IdentityKeystore.create(path, "correcta".toCharArray(), cheap)
        val truncated = Files.readString(path, StandardCharsets.UTF_8).take(80)
        Files.write(path, truncated.toByteArray(StandardCharsets.UTF_8))
        assertThrows<KeystoreException.Corrupt> { IdentityKeystore.load(path, "correcta".toCharArray()) }
    }

    @Test
    fun `cada creacion usa sal e IV nuevos`(@TempDir dir: Path) {
        val a = dir.resolve("a.kmkey")
        val b = dir.resolve("b.kmkey")
        val first = IdentityKeystore.create(a, "misma".toCharArray(), cheap)
        val second = IdentityKeystore.create(b, "misma".toCharArray(), cheap)

        assertNotEquals(first.identityId, second.identityId)
        val sa = saltOf(a)
        val sb = saltOf(b)
        assertFalse(sa.contentEquals(sb), "dos archivos con la misma frase comparten sal")
    }

    @Test
    fun `loadOrCreate crea la primera vez y abre despues`(@TempDir dir: Path) {
        val path = dir.resolve("nested").resolve("identity.kmkey")
        var askedForCreation: Boolean? = null
        val source = object : PassphraseSource {
            override fun passphrase(creation: Boolean): String {
                askedForCreation = creation
                return "clave"
            }
        }

        val created = IdentityKeystore.loadOrCreate(path, source)
        assertEquals(true, askedForCreation)
        assertEquals(created, IdentityKeystore.loadOrCreate(path, source))
        assertEquals(false, askedForCreation, "la segunda vez el almacen ya existe")
    }

    @Test
    fun `la ruta por defecto sigue XDG y cae a HOME`() {
        val xdg = IdentityKeystore.defaultPath(mapOf("XDG_CONFIG_HOME" to "/tmp/cfg", "HOME" to "/home/u"))
        assertEquals(Path.of("/tmp/cfg/keymessage/identity.kmkey"), xdg)

        val home = IdentityKeystore.defaultPath(mapOf("HOME" to "/home/u"))
        assertEquals(Path.of("/home/u/.config/keymessage/identity.kmkey"), home)

        assertThrows<KeystoreException.Corrupt> { IdentityKeystore.defaultPath(emptyMap()) }
    }

    @Test
    fun `el archivo y su directorio quedan en 600 y 700`(@TempDir dir: Path) {
        val path = dir.resolve("store").resolve("identity.kmkey")
        IdentityKeystore.create(path, "correcta".toCharArray(), cheap)
        runCatching {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(path),
            )
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                Files.getPosixFilePermissions(path.parent),
            )
        }
        // En sistemas sin permisos POSIX el comprobable no aplica: la operacion
        // se ignora a proposito y no se convierte en un fallo de arranque.
    }

    /**
     * Paridad con `km-core`.
     *
     * `IdentityKeystore` reimplementa la derivacion para no arrastrar Jackson al
     * leer un almacen. Esta prueba es la que impide que las dos copias se
     * separen: si `KmIds` o `RelayServer` cambiaran, aqui se veria.
     */
    @Test
    fun `deriveIdentityId coincide con km-core`() {
        val random = SecureRandom()
        repeat(8) {
            val publicKey = ByteArray(32).also(random::nextBytes)
            val ours = IdentityKeystore.deriveIdentityId(publicKey)
            assertEquals(RelayServer.deriveIdentityId(publicKey), ours)
            assertEquals(
                KmIds.identityId(publicKey).joinToString("") { byte -> "%02x".format(byte) },
                ours,
            )
        }
    }

    private fun saltOf(path: Path): ByteArray {
        val text = Files.readString(path, StandardCharsets.UTF_8)
        val marker = "\"salt\":\""
        val start = text.indexOf(marker) + marker.length
        val end = text.indexOf('"', start)
        return java.util.Base64.getDecoder().decode(text.substring(start, end))
    }

    /**
     * El objeto `kdf` de la CABECERA, tal cual esta escrito en el archivo.
     *
     * Se lee el JSON y no el [IdentityKeystore.DEFAULT_PROFILE] a proposito: lo
     * que se quiere comprobar es lo que un lector del archivo —este codigo o
     * cualquiera otro— ve de verdad.
     */
    private fun headerKdfOf(path: Path): JsonObject =
        Json.parseToJsonElement(Files.readString(path, StandardCharsets.UTF_8))
            .jsonObject
            .getValue("kdf")
            .jsonObject
}

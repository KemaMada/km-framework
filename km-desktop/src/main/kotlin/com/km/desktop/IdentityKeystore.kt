package com.km.desktop

import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.X25519
import com.km.crypto.provider.BcX25519
import com.km.model.IdentityId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Identidad local del escritorio: firma (Ed25519) y acuerdo (X25519).
 *
 * Los dos pares se guardan juntos porque un cliente al que le falta uno de los
 * dos es inservible, y porque la `identityId` se deriva del par de FIRMA, no
 * del de acuerdo (KM-ID-0001 seccion 6).
 *
 * `x25519PublicKey` se persiste aunque hoy la TUI no lo use: forma parte de la
 * identidad, y perderlo en una migracion seria irreversible.
 */
class DesktopIdentity(
    val identityId: IdentityId,
    val ed25519PublicKey: ByteArray,
    val ed25519PrivateKey: ByteArray,
    val x25519PublicKey: ByteArray,
    val x25519PrivateKey: ByteArray,
) {
    /** Vista imprimible SIN material secreto. */
    override fun toString(): String =
        "DesktopIdentity(identityId=${identityId.value}, ed25519=${ed25519PublicKey.size}B, " +
            "x25519=${x25519PublicKey.size}B, privado=<oculto>)"

    override fun equals(other: Any?): Boolean = other is DesktopIdentity &&
        identityId == other.identityId &&
        ed25519PublicKey.contentEquals(other.ed25519PublicKey) &&
        ed25519PrivateKey.contentEquals(other.ed25519PrivateKey) &&
        x25519PublicKey.contentEquals(other.x25519PublicKey) &&
        x25519PrivateKey.contentEquals(other.x25519PrivateKey)

    override fun hashCode(): Int {
        var result = identityId.hashCode()
        result = 31 * result + ed25519PublicKey.contentHashCode()
        result = 31 * result + ed25519PrivateKey.contentHashCode()
        result = 31 * result + x25519PublicKey.contentHashCode()
        result = 31 * result + x25519PrivateKey.contentHashCode()
        return result
    }
}

/**
 * Coste de la derivacion, SIN sal.
 *
 * La sal es un valor POR ARCHIVO y nunca se reutiliza, asi que no puede formar
 * parte de un perfil por defecto compartido: por eso son dos tipos y no uno con
 * un `salt` opcional.
 */
class KdfProfile(
    val algorithm: String,
    val iterations: Int,
    val memoryKiB: Int,
    val parallelism: Int,
    val outputBytes: Int,
) {
    init {
        require(iterations >= 1) { "iteraciones >= 1" }
        require(memoryKiB >= 8) { "memoria >= 8 KiB" }
        require(parallelism >= 1) { "paralelismo >= 1" }
    }

    fun withSalt(salt: ByteArray): KdfParams = KdfParams(this, salt)

    override fun toString(): String =
        "KdfProfile($algorithm, iters=$iterations, mem=${memoryKiB}KiB, par=$parallelism, out=${outputBytes}B)"
}

/**
 * Parametros de derivacion tal como viajan en un archivo concreto.
 *
 * Van en la CABECERA, en claro, porque sin ellos no se puede derivar la clave.
 * Por eso [matches] los compara con los que declara el propio texto plano, que
 * si esta autenticado por el tag GCM: si alguien edita la cabecera para subir o
 * bajar el coste, el descifrado falla antes de devolver NADA, en lugar de
 * devolver algo protegido con un KDF distinto del que el archivo declara.
 */
class KdfParams(
    val profile: KdfProfile,
    val salt: ByteArray,
) {
    val algorithm: String get() = profile.algorithm
    val iterations: Int get() = profile.iterations
    val memoryKiB: Int get() = profile.memoryKiB
    val parallelism: Int get() = profile.parallelism
    val outputBytes: Int get() = profile.outputBytes

    fun matches(other: KdfParams): Boolean =
        algorithm == other.algorithm &&
            iterations == other.iterations &&
            memoryKiB == other.memoryKiB &&
            parallelism == other.parallelism &&
            outputBytes == other.outputBytes &&
            salt.contentEquals(other.salt)

    override fun equals(other: Any?): Boolean = other is KdfParams && matches(other)

    override fun hashCode(): Int {
        var result = profile.hashCode()
        result = 31 * result + salt.contentHashCode()
        return result
    }

    override fun toString(): String = "$profile, salt=${salt.size}B"
}

/**
 * Fuente de la frase de paso.
 *
 * Es una interfaz y no una llamada directa a JLine para que [IdentityKeystore]
 * no dependa de la terminal: el almacen se puede probar entero sin TUI. Quien
 * la implementa es `DesktopMain`.
 *
 * Orden de resolucion: variable de entorno [ENV_VAR] y, si no esta, el prompt
 * enmascarado. La variable existe para scripting y contenedores; es comoda y es
 * un riesgo, y su uso se anuncia.
 */
interface PassphraseSource {
    /** @param creation `true` si el almacen aun no existe: cambia el texto del prompt. */
    fun passphrase(creation: Boolean): String?

    companion object {
        /** Variable de entorno leida ANTES de preguntar. */
        const val ENV_VAR: String = "KM_KEYSTORE_PASSPHRASE"

        /** Implementacion por defecto: entorno primero, [prompt] si no hay. */
        fun envThen(prompt: (creation: Boolean) -> String?): PassphraseSource =
            object : PassphraseSource {
                override fun passphrase(creation: Boolean): String? =
                    System.getenv(ENV_VAR)?.takeIf { it.isNotEmpty() } ?: prompt(creation)
            }
    }
}

/**
 * Excepcion del almacen cifrado.
 *
 * Se distingue de una excepcion generica porque la TUI reacciona distinto a
 * cada causa: una frase de paso equivocada invita a reintentar y un almacen
 * corrupto, no.
 */
open class KeystoreException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** La frase de paso no abre la carga, o el archivo esta alterado. */
    class WrongPassphrase(message: String, cause: Throwable? = null) : KeystoreException(message, cause)

    /** El archivo esta corrupto o no es un almacen de km-desktop. */
    class Corrupt(message: String, cause: Throwable? = null) : KeystoreException(message, cause)

    /** La identidad guardada no es coherente con sus propias claves. */
    class Inconsistent(message: String) : KeystoreException(message)
}

/**
 * Almacen cifrado de la identidad local: Argon2id + AES-256-GCM.
 *
 * QUE ES Y QUE NO ES
 *
 * Esto es almacenamiento de secretos, no criptografia de protocolo. El
 * `SecureFrame`, el ratchet, X3DH y los transcripts viven en `km-core`, y este
 * modulo no los reimplementa ni los toca: cifra un `ByteArray` en disco y lo
 * descifra. `Ed25519` y `X25519` se usan a traves de las interfaces de
 * `km-core`.
 *
 * PARAMETROS
 *
 * Argon2id v1.3 con 3 iteraciones, 64 MiB de memoria y 4 carriles: la SEGUNDA
 * opcion recomendada de RFC 9106 seccion 4 (t=3, p=4, m=2^16 = 64 MiB). La salida
 * son los 32 bytes que son la clave de AES-256-GCM. La sal es de 16 bytes y el IV
 * de GCM de 12, ambos de `SecureRandom` y NUNCA reutilizados.
 *
 * El coste va en la cabecera de cada archivo, no en una constante global: es lo
 * que permite subirlo mas adelante sin invalidar lo ya escrito.
 *
 * Y ESO ES TAMBIEN LA COMPATIBILIDAD. `load()` NO usa [DEFAULT_PROFILE]: lee el
 * `kdf` de la cabecera del propio archivo y deriva con esos parametros. Un
 * almacen escrito con el perfil anterior (p=1) sigue abriéndose sin migracion,
 * sin version nueva y sin camino de codigo alterno: leer el coste del archivo es
 * exactamente lo que hace imposible que subir el perfil rompa lo ya escrito.
 * [VERSION] protege la FORMA del contenedor JSON, no el coste; y el coste no lo
 * protege nadie porque lo protege la cabecera, que a su vez esta autenticada por
 * el duplicado del texto plano ([KdfParams.matches]).
 *
 * El unico efecto de cambiar [DEFAULT_PROFILE] es el coste de los archivos que se
 * escriban a partir de ahora.
 *
 * FORMATO
 *
 * Un objeto JSON, con la carga en Base64 estandar (RFC 4648 seccion 4):
 *
 * ```
 * {
 *   "format": "km-desktop-keystore", "version": 1, "createdAt": 1790000000000,
 *   "kdf":   { "algorithm": "argon2id", "salt": "…", "iterations": 3,
 *              "memoryKiB": 65536, "parallelism": 4, "outputBytes": 32 },
 *   "cipher": { "algorithm": "AES/GCM/NoPadding", "iv": "…" },
 *   "ciphertext": "…"
 * }
 * ```
 *
 * El IV no es un AAD: el contenido de la cabecera NO esta autenticado por el
 * GCM. Lo que protege es [KdfParams.matches], que aborta si no cuadra, y el tag
 * GCM sobre el texto plano, que incluye los parametros KDF por duplicado.
 */
object IdentityKeystore {

    /** Formato del archivo. Cualquier otro se rechaza. */
    const val FORMAT: String = "km-desktop-keystore"

    /** Version de formato que este codigo lee y escribe. */
    const val VERSION: Int = 1

    const val KDF_ARGON2ID: String = "argon2id"
    const val CIPHER_AES_GCM: String = "AES/GCM/NoPadding"

    const val SALT_BYTES: Int = 16
    const val IV_BYTES: Int = 12
    const val KEY_BYTES: Int = 32
    private const val TAG_BITS: Int = 128

    /** Nombre del archivo dentro del directorio de configuracion. */
    const val IDENTITY_FILE_NAME: String = "identity.kmkey"

    /**
     * Perfil por defecto: la segunda opcion recomendada de RFC 9106 seccion 4
     * (`t=3`, `m=64 MiB`, `p=4`), que es la que se escribe en los archivos
     * NUEVOS.
     *
     * Solo lo consume [create]. [load] deriva con los parametros que trae la
     * cabecera del archivo, asi que subir este valor no invalida lo ya escrito.
     */
    val DEFAULT_PROFILE: KdfProfile = KdfProfile(
        algorithm = KDF_ARGON2ID,
        iterations = 3,
        memoryKiB = 65_536,
        parallelism = 4,
        outputBytes = KEY_BYTES,
    )

    private val JSON = Json { ignoreUnknownKeys = true }
    private val B64 = Base64.getEncoder()
    private val B64_DECODER = Base64.getDecoder()

    // ------------------------------------------------------------------
    // Rutas
    // ------------------------------------------------------------------

    /**
     * Ruta por defecto: `$XDG_CONFIG_HOME/keymessage/identity.kmkey`, o
     * `~/.config/keymessage/identity.kmkey` si `XDG_CONFIG_HOME` no esta.
     *
     * Es el unico sitio del modulo que decide rutas, para que el resto no tenga
     * que saber de XDG ni de `$HOME`.
     */
    fun defaultPath(env: Map<String, String> = System.getenv()): Path {
        val configHome = env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }
            ?: env["HOME"]?.takeIf { it.isNotBlank() }?.plus("/.config")
            ?: throw KeystoreException.Corrupt(
                "no hay XDG_CONFIG_HOME ni HOME: no se puede localizar el almacen de identidad"
            )
        return Paths.get(configHome, "keymessage", IDENTITY_FILE_NAME)
    }

    fun exists(path: Path): Boolean = Files.isRegularFile(path)

    // ------------------------------------------------------------------
    // Carga y creacion
    // ------------------------------------------------------------------

    /**
     * Descifra el almacen de [path].
     *
     * @throws KeystoreException.WrongPassphrase si la frase no abre el tag.
     * @throws KeystoreException.Corrupt si el archivo no es un almacen valido.
     * @throws KeystoreException.Inconsistent si el `identityId` guardado no
     *   deriva de la clave publica Ed25519 guardada.
     */
    fun load(path: Path, passphrase: CharArray): DesktopIdentity {
        val root = readContainer(path)
        if (root.string("format") != FORMAT) {
            throw KeystoreException.Corrupt("almacen invalido en $path: no es un '$FORMAT'")
        }
        val version = root.requireInt("version")
        if (version != VERSION) {
            throw KeystoreException.Corrupt("almacen invalido en $path: version $version no soportada")
        }

        val where = path.toString()
        val headerKdf = parseKdf(root.obj("kdf") ?: throw KeystoreException.Corrupt("falta 'kdf' en $where"), where)

        val cipher = root.obj("cipher") ?: throw KeystoreException.Corrupt("falta 'cipher' en $where")
        if (cipher.string("algorithm") != CIPHER_AES_GCM) {
            throw KeystoreException.Corrupt(
                "almacen invalido en $where: cifrado '${cipher.string("algorithm")}' no soportado"
            )
        }
        val iv = decodeB64(cipher.string("iv") ?: throw KeystoreException.Corrupt("falta 'cipher.iv' en $where"), "iv")
        if (iv.size != IV_BYTES) throw KeystoreException.Corrupt("'iv' en $where debe tener $IV_BYTES bytes")

        val ciphertext = decodeB64(
            root.string("ciphertext") ?: throw KeystoreException.Corrupt("falta 'ciphertext' en $where"),
            "ciphertext",
        )

        val aesKey = deriveKey(passphrase, headerKdf)
        return readIdentity(decrypt(aesKey, iv, ciphertext, where), headerKdf)
    }

    /**
     * Genera una identidad nueva, la cifra y la deja en [path].
     *
     * El directorio se crea con permisos 700 y el archivo con 600, en POSIX. En
     * sistemas de ficheros sin permisos POSIX la operacion se ignora: es una
     * mejora, y fallar el arranque por no poder fijar un permiso seria peor que
     * no tenerlo.
     */
    fun create(
        path: Path,
        passphrase: CharArray,
        profile: KdfProfile = DEFAULT_PROFILE,
        ed25519: Ed25519 = Ed25519Impl(),
        x25519: X25519 = BcX25519(),
        random: SecureRandom = SecureRandom(),
    ): DesktopIdentity {
        val signing = ed25519.generateKeyPair()
        val agreement = x25519.generateKeyPair()
        val identity = DesktopIdentity(
            identityId = IdentityId(deriveIdentityId(signing.publicKey)),
            ed25519PublicKey = signing.publicKey,
            ed25519PrivateKey = signing.privateKey,
            x25519PublicKey = agreement.publicKey,
            x25519PrivateKey = agreement.privateKey,
        )
        write(path, identity, passphrase, profile, random)
        return identity
    }

    /**
     * Carga [path], o crea una identidad nueva si no existe.
     *
     * La frase se pide UNA vez: si hay que crear, esa misma es la que protege el
     * archivo nuevo.
     */
    fun loadOrCreate(path: Path, source: PassphraseSource): DesktopIdentity {
        val creating = !exists(path)
        val passphrase = source.passphrase(creation = creating)
            ?: throw KeystoreException("sin frase de paso: no se puede ${if (creating) "crear" else "abrir"} $path")
        return if (creating) create(path, passphrase.toCharArray()) else load(path, passphrase.toCharArray())
    }

    // ------------------------------------------------------------------
    // KDF y cifrado
    // ------------------------------------------------------------------

    private fun deriveKey(passphrase: CharArray, kdf: KdfParams): ByteArray {
        if (kdf.algorithm != KDF_ARGON2ID) {
            throw KeystoreException.Corrupt("KDF '${kdf.algorithm}' no soportado: solo se escribe $KDF_ARGON2ID")
        }
        // BouncyCastle implementa Argon2id de RFC 9106 en Java puro. La sal
        // viaja en `Argon2Parameters`, no como argumento: por eso el generador
        // recibe solo la contrasena y el buffer de salida, y la longitud sale de
        // [KEY_BYTES]. Un almacen que declare otra `outputBytes` es de otro
        // formato y se rechaza al leer.
        val password = String(passphrase).toByteArray(StandardCharsets.UTF_8)
        val out = ByteArray(KEY_BYTES)
        return try {
            val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(kdf.salt)
                .withIterations(kdf.iterations)
                .withMemoryAsKB(kdf.memoryKiB)
                .withParallelism(kdf.parallelism)
                .build()
            val generated = Argon2BytesGenerator().apply { init(parameters) }
            generated.generateBytes(password, out, 0, KEY_BYTES)
            out
        } catch (e: Exception) {
            throw KeystoreException(
                "no se pudo derivar la clave con Argon2id: ${e.message}",
                e,
            )
        } finally {
            password.fill(0)
        }
    }

    private fun decrypt(aesKey: ByteArray, iv: ByteArray, ciphertext: ByteArray, where: String): ByteArray =
        try {
            val cipher = Cipher.getInstance(CIPHER_AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            // El tag no cuadra. Las dos causas posibles —frase equivocada y
            // archivo alterado— no se distinguen sin un valor de control que
            // delate el error, asi que no se adivina: se dice lo mismo.
            throw KeystoreException.WrongPassphrase(
                "no se pudo descifrar $where: frase de paso incorrecta o archivo alterado",
                e,
            )
        } catch (e: Exception) {
            throw KeystoreException.Corrupt("no se pudo descifrar $where: ${e.message}", e)
        }

    private fun encrypt(aesKey: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(plaintext)
    }

    // ------------------------------------------------------------------
    // Serializacion
    // ------------------------------------------------------------------

    private fun readContainer(path: Path): JsonObject {
        val text = runCatching { Files.readString(path, StandardCharsets.UTF_8) }
            .getOrElse { throw KeystoreException.Corrupt("almacen ilegible en $path: ${it.message}", it) }
        val node = runCatching { JSON.parseToJsonElement(text) }.getOrNull()
        return node as? JsonObject
            ?: throw KeystoreException.Corrupt("almacen invalido en $path: la raiz no es un objeto JSON")
    }

    private fun write(
        path: Path,
        identity: DesktopIdentity,
        passphrase: CharArray,
        profile: KdfProfile,
        random: SecureRandom,
    ) {
        val kdf = profile.withSalt(ByteArray(SALT_BYTES).also(random::nextBytes))
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val aesKey = deriveKey(passphrase, kdf)
        val plaintext = identityJson(identity, kdf).toString().toByteArray(StandardCharsets.UTF_8)

        val container = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("createdAt", Instant.now().toEpochMilli())
            put("kdf", kdfJson(kdf))
            put("cipher", buildJsonObject {
                put("algorithm", CIPHER_AES_GCM)
                put("iv", B64.encodeToString(iv))
            })
            put("ciphertext", B64.encodeToString(encrypt(aesKey, iv, plaintext)))
        }

        val absolute = path.toAbsolutePath()
        absolute.parent?.let {
            Files.createDirectories(it)
            restrict(it, PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        }
        Files.write(
            absolute,
            container.toString().toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        restrict(absolute, PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    }

    private fun kdfJson(kdf: KdfParams): JsonObject = buildJsonObject {
        put("algorithm", kdf.algorithm)
        put("salt", B64.encodeToString(kdf.salt))
        put("iterations", kdf.iterations)
        put("memoryKiB", kdf.memoryKiB)
        put("parallelism", kdf.parallelism)
        put("outputBytes", kdf.outputBytes)
    }

    private fun identityJson(identity: DesktopIdentity, kdf: KdfParams): JsonObject = buildJsonObject {
        put("identityId", identity.identityId.value)
        put("ed25519", buildJsonObject {
            put("publicKey", B64.encodeToString(identity.ed25519PublicKey))
            put("privateKey", B64.encodeToString(identity.ed25519PrivateKey))
        })
        put("x25519", buildJsonObject {
            put("publicKey", B64.encodeToString(identity.x25519PublicKey))
            put("privateKey", B64.encodeToString(identity.x25519PrivateKey))
        })
        // Duplicados DENTRO del texto plano, que si esta autenticado por el tag
        // GCM. Es lo que detecta una cabecera editada.
        put("kdf", kdfJson(kdf))
    }

    private fun readIdentity(plaintext: ByteArray, headerKdf: KdfParams): DesktopIdentity {
        val node = runCatching { JSON.parseToJsonElement(String(plaintext, StandardCharsets.UTF_8)) }.getOrNull()
        val root = node as? JsonObject
            ?: throw KeystoreException.Corrupt("el texto plano descifrado no es un objeto JSON")

        val identityId = root.string("identityId")
            ?: throw KeystoreException.Corrupt("el texto plano no declara 'identityId'")
        val ed = root.obj("ed25519") ?: throw KeystoreException.Corrupt("el texto plano no declara 'ed25519'")
        val x = root.obj("x25519") ?: throw KeystoreException.Corrupt("el texto plano no declara 'x25519'")

        val edPublic = decodeB64(ed.string("publicKey") ?: throw CorruptField("ed25519.publicKey"), "ed25519.publicKey")
        val edPrivate = decodeB64(ed.string("privateKey") ?: throw CorruptField("ed25519.privateKey"), "ed25519.privateKey")
        val xPublic = decodeB64(x.string("publicKey") ?: throw CorruptField("x25519.publicKey"), "x25519.publicKey")
        val xPrivate = decodeB64(x.string("privateKey") ?: throw CorruptField("x25519.privateKey"), "x25519.privateKey")

        listOf(edPublic to "ed25519", xPublic to "x25519", edPrivate to "ed25519", xPrivate to "x25519")
            .forEach { (bytes, label) ->
                if (bytes.size != KEY_BYTES) {
                    throw KeystoreException.Corrupt("las claves $label deben tener $KEY_BYTES bytes")
                }
            }

        // La coherencia se REHACE, no se copia: un identityId que no sea el
        // derivado de la clave publica es un archivo manipulado o de otro
        // formato, y aceptarlo firmaria un transcript que el rele no podria
        // validar.
        if (!deriveIdentityId(edPublic).equals(identityId, ignoreCase = true)) {
            throw KeystoreException.Inconsistent("identityId incoherente: no deriva de la clave publica Ed25519")
        }

        root.obj("kdf")?.let { inner ->
            val parsed = parseKdf(inner, "texto plano")
            if (!headerKdf.matches(parsed)) {
                throw KeystoreException.Corrupt("la cabecera KDF no coincide con la del texto plano")
            }
        }

        return DesktopIdentity(
            identityId = IdentityId(identityId.lowercase()),
            ed25519PublicKey = edPublic,
            ed25519PrivateKey = edPrivate,
            x25519PublicKey = xPublic,
            x25519PrivateKey = xPrivate,
        )
    }

    private fun parseKdf(node: JsonObject, where: String): KdfParams {
        val salt = decodeB64(node.string("salt") ?: throw CorruptField("kdf.salt"), "kdf.salt")
        if (salt.size < 8) throw KeystoreException.Corrupt("sal demasiado corta en $where")
        val profile = KdfProfile(
            algorithm = node.string("algorithm") ?: throw CorruptField("kdf.algorithm"),
            iterations = node.requireInt("iterations"),
            memoryKiB = node.requireInt("memoryKiB"),
            parallelism = node.requireInt("parallelism"),
            outputBytes = node.requireInt("outputBytes"),
        )
        if (profile.outputBytes != KEY_BYTES) {
            throw KeystoreException.Corrupt("la clave de AES debe tener $KEY_BYTES bytes en $where")
        }
        return profile.withSalt(salt)
    }

    /**
     * `identityId = SHA-256("KM-ID-IDENTITY" || publicKey)` (KM-ID-0001 seccion 6).
     *
     * Se reimplementa aqui, y no con `RelayServer.deriveIdentityId`, por una
     * sola razon: el almacen tiene que poder LEERSE sin arrastrar el runtime
     * completo del rele. `com.km.identity.KmIds` haria el trabajo igual con una
     * dependencia de clase, pero `RelayServer` y `JsonRelayControlCodec`
     * necesitan Jackson en tiempo de ejecucion, y eso si es una dependencia de
     * verdad: `identity` tendria que importar Jackson para poder importar
     * [JvmRelayTransport].
     *
     * `IdentityKeystoreTest` lo comprueba byte a byte contra
     * `KmIds.identityId`, para que las dos copias no se separen.
     */
    fun deriveIdentityId(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("KM-ID-IDENTITY".toByteArray(Charsets.UTF_8))
        digest.update(publicKey)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun restrict(path: Path, vararg permissions: PosixFilePermission) {
        runCatching { Files.setPosixFilePermissions(path, permissions.toSet()) }
    }

    private fun decodeB64(encoded: String, field: String): ByteArray =
        runCatching { B64_DECODER.decode(encoded) }
            .getOrElse { throw KeystoreException.Corrupt("'$field' no es Base64: ${it.message}", it) }

    // ------------------------------------------------------------------
    // Lectores de JSON
    // ------------------------------------------------------------------

    /** Entero obligatorio: si falta o no es entero, el archivo esta corrupto. */
    private fun JsonObject.requireInt(field: String): Int =
        int(field) ?: throw CorruptField(field)

    /** Campo obligatorio ausente. */
    private fun CorruptField(field: String) = KeystoreException.Corrupt("falta '$field'")
}
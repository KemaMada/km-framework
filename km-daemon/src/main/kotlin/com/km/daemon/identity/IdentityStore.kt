package com.km.daemon.identity

import com.km.auth.Base64Url
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.model.IdentityId
import com.km.node.RelayServer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Identidad criptografica del relé.
 *
 * @property identityId 64 hex, derivado de [publicKey] con
 *   `SHA-256("KM-ID-IDENTITY" || publicKey)`. Viaja como `responderIdentityId`
 *   en `AUTH_REQUEST` y entra en el transcript de 152 B.
 * @property publicKey clave de firma Ed25519 (32 B). Publica: se envia en
 *   `AUTH_OK` para que el cliente pueda verificar la firma del relé.
 * @property privateKey semilla Ed25519 (32 B). SECRETA. Nunca se registra.
 */
data class DaemonIdentity(
    val identityId: IdentityId,
    val publicKey: ByteArray,
    val privateKey: ByteArray,
) {
    /** El par que consume `RelayServer` para firmar el transcript de 120 B. */
    fun keyPair(): KeyPair = KeyPair(publicKey.copyOf(), privateKey.copyOf())

    /** Vista imprimible SIN el material secreto. */
    override fun toString(): String =
        "DaemonIdentity(identityId=${identityId.value}, publicKey=${publicKey.size}B, privateKey=<oculta>)"
}

/**
 * Persistencia del par Ed25519 del relé.
 *
 * El daemon necesita una identidad ESTABLE: la `responderIdentityId` es la
 * que el cliente usa para construir el transcript de 152 B, así que un par
 * nuevo en cada arranque haría que todos los clientes rechazaran el challenge.
 *
 * Formato: texto plano `clave=valor`, una linea por clave, para que se pueda
 * inspeccionar a mano. No es un formato secreto ni pretende serlo; la
 * proteccion es el permiso 600 del fichero, no su ofuscacion.
 *
 * ```
 * # km-daemon identity
 * identityId=<64 hex>
 * publicKey=<Base64URL sin padding>
 * privateKeySeed=<Base64URL sin padding, 32 B>
 * ```
 */
object IdentityStore {

    private const val KEY_IDENTITY_ID = "identityId"
    private const val KEY_PUBLIC_KEY = "publicKey"
    private const val KEY_PRIVATE_KEY_SEED = "privateKeySeed"

    private const val SEED_BYTES = 32
    private const val PUBLIC_KEY_BYTES = 32

    private val HEX = "0123456789abcdefABCDEF".toSet()

    /**
     * Carga la identidad de [path]; si no existe, genera un par nuevo y lo
     * persiste.
     *
     * @throws IllegalStateException si el fichero existe pero esta corrupto o
     *   es incoherente. Un daemon NO rearranca con una identidad distinta de
     *   la que tiene en disco sin que nadie lo haya pedido: se para.
     */
    fun loadOrCreate(
        path: Path,
        ed25519: Ed25519 = Ed25519Impl(),
    ): DaemonIdentity =
        if (Files.isRegularFile(path)) read(path) else create(path, ed25519)

    private fun create(path: Path, ed25519: Ed25519): DaemonIdentity {
        val keyPair = ed25519.generateKeyPair()
        val identity = identityOf(keyPair)
        persist(path, identity)
        return identity
    }

    private fun read(path: Path): DaemonIdentity {
        val fields = runCatching {
            Files.readAllLines(path, StandardCharsets.UTF_8)
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
                }
                .toMap()
        }.getOrElse { throw IllegalStateException("identidad ilegible en $path: ${it.message}", it) }

        val identityId = fields[KEY_IDENTITY_ID]
            ?: throw IllegalStateException("identidad incompleta en $path: falta '$KEY_IDENTITY_ID'")
        val publicKey = decode(fields[KEY_PUBLIC_KEY], KEY_PUBLIC_KEY, path)
        val privateKey = decode(fields[KEY_PRIVATE_KEY_SEED], KEY_PRIVATE_KEY_SEED, path)

        if (identityId.length != 64 || !identityId.all { it in HEX }) {
            throw IllegalStateException("identidad invalida en $path: '$KEY_IDENTITY_ID' no son 64 hex")
        }
        if (publicKey.size != PUBLIC_KEY_BYTES) {
            throw IllegalStateException(
                "identidad invalida en $path: '$KEY_PUBLIC_KEY' debe tener $PUBLIC_KEY_BYTES bytes, " +
                    "tiene ${publicKey.size}"
            )
        }
        if (privateKey.size != SEED_BYTES) {
            throw IllegalStateException(
                "identidad invalida en $path: '$KEY_PRIVATE_KEY_SEED' debe tener $SEED_BYTES bytes, " +
                    "tiene ${privateKey.size}"
            )
        }

        // La derivacion se REHACE, no se copia: un identityId que no sea el
        // derivado de la clave publicaria es un fichero manipulado o de otro
        // formato, y aceptarlo emitiria un challenge que nadie podria validar.
        val derived = RelayServer.deriveIdentityId(publicKey)
        if (!derived.equals(identityId, ignoreCase = true)) {
            throw IllegalStateException(
                "identidad incoherente en $path: '$KEY_IDENTITY_ID' no deriva de '$KEY_PUBLIC_KEY'"
            )
        }

        return DaemonIdentity(IdentityId(identityId.lowercase()), publicKey, privateKey)
    }

    private fun decode(encoded: String?, key: String, path: Path): ByteArray {
        if (encoded.isNullOrBlank()) {
            throw IllegalStateException("identidad incompleta en $path: falta '$key'")
        }
        return runCatching { Base64Url.decode(encoded) }
            .getOrElse { throw IllegalStateException("identidad invalida en $path: '$key' no es Base64URL: ${it.message}") }
    }

    private fun identityOf(keyPair: KeyPair): DaemonIdentity = DaemonIdentity(
        identityId = IdentityId(RelayServer.deriveIdentityId(keyPair.publicKey)),
        publicKey = keyPair.publicKey,
        privateKey = keyPair.privateKey,
    )

    private fun persist(path: Path, identity: DaemonIdentity) {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val content = buildString {
            appendLine("# km-daemon identity")
            appendLine("# Generado automaticamente en el primer arranque.")
            appendLine("# Borrarlo cambia la identidad del rele y rompe la autenticacion de los clientes.")
            appendLine("# $KEY_PRIVATE_KEY_SEED es SECRETA. No la copies ni la registres.")
            appendLine("$KEY_IDENTITY_ID=${identity.identityId.value}")
            appendLine("$KEY_PUBLIC_KEY=${Base64Url.encode(identity.publicKey)}")
            appendLine("$KEY_PRIVATE_KEY_SEED=${Base64Url.encode(identity.privateKey)}")
        }
        Files.write(
            path,
            content.toByteArray(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        restrictPermissions(path)
    }

    /**
     * 600 en POSIX. En sistemas de ficheros sin permisos POSIX (Windows,
     * ciertos montajes) la operacion falla y se ignora: es una mejora, no un
     * requisito, y fallar el arranque por ello seria peor que la carencia.
     */
    private fun restrictPermissions(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }
}

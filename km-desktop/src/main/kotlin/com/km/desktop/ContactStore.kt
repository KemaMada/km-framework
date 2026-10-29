package com.km.desktop

import com.km.desktop.contact.ContactImporter
import com.km.desktop.platform.OwnerPermissions
import com.km.identity.ContactRegistry
import com.km.identity.VerifiedContact
import com.km.model.IdentityId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * PERSISTENCIA de los contactos del escritorio, junto al keystore.
 *
 * ## EL FALLO QUE CIERRA
 *
 * El escritorio guardaba contactos y sesiones SOLO en memoria. Si el proceso
 * reiniciaba —un corte de luz, un `km-desktop` que se cierra— los contactos
 * desaparecian y el registro de sesiones tambien. El otro extremo seguia
 * enviando con una sesion que ya no existia de este lado, y el frame se
 * descartaba sin dejar rastro: el sintoma era "re-escanear no recupera el par".
 *
 * Esto guarda lo MINIMO para reconstruir un contacto verificado:
 *
 *  - `identityId`: la RAIZ que firma el bundle y gobierna el ruteo.
 *  - `alias`: el nombre que el usuario le puso. No viaja por el cable.
 *  - `signingKey`: la clave de FIRMA del dispositivo, en Base64. Es de donde
 *    sale el resolver que autentica los `ACK`.
 *  - `deviceId`: el dispositivo descrito por el bundle, en hex.
 *  - `addedAt`: cuando se acepto, en milisegundos.
 *  - `bundle`: el portador PUBLICO comprimido, en Base64.
 *
 * ## POR QUE SE GUARDA EL BUNDLE SI EL MINIMO NO LO PIDE
 *
 * Porque sin el no se puede reconstruir un [VerifiedContact]: abrir una sesion
 * y aceptar un PREKEY releen el portador y lo VUELVEN A VERIFICAR
 * (`CarrierCodec.decode`). Guardar solo `identityId` daria un contacto que
 * aparece en la lista y no puede hablar. El bundle es material PUBLICO —es el
 * mismo que va en el QR— asi que no hay secreto nuevo en disco.
 *
 * ## ESCRITURA ATOMICA
 *
 * Se escribe un temporal EN EL MISMO DIRECTORIO y se renombra. Un corte a mitad
 * deja el `contacts.json` anterior intacto, no un JSON truncado. El temporal y
 * el destino quedan en `600` (POSIX) o su equivalente de propietario.
 *
 * ## UN FICHERO CORRUPTO NO SE BORRA Y NO SE PISA
 *
 * Si el JSON no parsea —o una entrada no verifica— se carga lo que se pueda y
 * se deja `lastError` con un codigo ESTABLE. La escritura queda BLOQUEADA para
 * no destruir el original, que es el unico sitio donde estan esos datos. Un
 * operador puede repararlo o moverlo; el cliente informa, no decide tirarlo.
 */
class ContactStore(
    /** `contacts.json`, junto a `peers.json`. `null` = este cliente no persiste. */
    private val path: Path? = null,
    /** Reloj de `addedAt`, inyectado para que el test no dependa del sistema. */
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * El renombrado, inyectable.
     *
     * Existe como costura de test: permite afirmar que el destino NO se escribe
     * antes del renombrado y que un fallo del renombrado deja el original
     * intacto. En produccion es [moverAtomico].
     */
    private val mover: (Path, Path) -> Unit = { from, to -> moverAtomico(from, to) },
) {
    class Record(
        val identityId: String,
        val alias: String,
        val signingKey: ByteArray,
        val deviceId: String,
        val addedAt: Long,
        val bundleText: String,
    )

    private val registros = LinkedHashMap<String, Record>()
    private val candado = Any()

    /** Ultimo codigo estable de error de carga o escritura, o `null`. */
    @Volatile
    var lastError: String? = null
        private set

    /** Entradas del fichero que no se pudieron cargar. */
    @Volatile
    var skipped: Int = 0
        private set

    /**
     * Si la carga encontro datos que no se pueden reescribir sin perderlos.
     *
     * Mientras sea `true`, [save] devuelve `false` y NO toca el fichero.
     */
    @Volatile
    private var bloqueoEscritura: Boolean = false

    /** Los registros en memoria, ordenados por identidad. */
    fun records(): List<Record> = synchronized(candado) {
        registros.values.sortedBy { it.identityId }
    }

    fun size(): Int = synchronized(candado) { registros.size }

    /**
     * Lee [path] y siembra [registry] con lo que verifique.
     *
     * @return cuantos contactos quedaron registrados.
     */
    fun loadInto(registry: ContactRegistry): Int {
        val file = path ?: return 0
        if (!Files.isRegularFile(file)) return 0
        // Un fichero previo con permisos laxos se corrige al leerlo. No es
        // material secreto, pero vive junto al almacen cifrado.
        OwnerPermissions.DEFAULT.secureFile(file)
        val texto = runCatching { Files.readString(file, StandardCharsets.UTF_8) }.getOrElse {
            lastError = CODE_CORRUPT
            bloqueoEscritura = true
            return 0
        }
        val raiz = runCatching { JSON.parseToJsonElement(texto) }.getOrNull() as? JsonObject
        if (raiz == null) {
            // Bytes ilegibles o JSON truncado: no se borra NADA.
            lastError = CODE_CORRUPT
            bloqueoEscritura = true
            return 0
        }
        val entradas = raiz["contacts"] as? JsonArray ?: run {
            lastError = CODE_CORRUPT
            bloqueoEscritura = true
            return 0
        }

        var cargados = 0
        entradas.forEach { elemento ->
            val entrada = elemento as? JsonObject
            val registro = entrada?.let { parsear(it) }
            if (registro == null) {
                skipped++
                return@forEach
            }
            val contacto = runCatching { ContactImporter.import(registro.bundleText) }.getOrNull()
            // La coherencia se RECALCULA: la raiz tiene que ser la del bundle y
            // la clave guardada tiene que ser la que ese bundle firma. Un
            // fichero manipulado no autentica ACKs de una identidad ajena.
            if (contacto == null ||
                contacto.identityRoot.value != registro.identityId ||
                !contacto.subjectSigningKey.equals(registro.signingKey.toHex(), ignoreCase = true)
            ) {
                skipped++
                return@forEach
            }
            registry.upsert(contacto.copy(displayName = registro.alias))
            synchronized(candado) { registros[registro.identityId] = registro }
            cargados++
        }
        if (skipped > 0) {
            lastError = CODE_INVALID_ENTRY
            bloqueoEscritura = true
        }
        return cargados
    }

    /**
     * Guarda [contact] (upsert) y reescribe el fichero de forma atomica.
     *
     * @return `false` si no hay ruta, si la escritura falla o si la carga previa
     *   dejo el fichero en un estado que no se debe pisar (`lastError`).
     */
    fun save(contact: VerifiedContact): Boolean = saveAll(listOf(contact))

    /** Igual que [save], para varias entradas en UNA sola escritura atomica. */
    fun saveAll(contacts: Collection<VerifiedContact>): Boolean {
        val file = path ?: return false
        synchronized(candado) {
            if (bloqueoEscritura) return false
            contacts.forEach { contacto ->
                val existente = registros[contacto.identityRoot.value]
                registros[contacto.identityRoot.value] = Record(
                    identityId = contacto.identityRoot.value,
                    alias = contacto.displayName,
                    signingKey = contacto.subjectSigningKey.hexToBytes(),
                    deviceId = contacto.subjectDeviceId,
                    addedAt = existente?.addedAt ?: clock(),
                    bundleText = contacto.bundleText,
                )
            }
            return escribir(file)
        }
    }

    /** Si la escritura esta bloqueada por una carga que perdio datos. */
    fun writesBlocked(): Boolean = bloqueoEscritura

    // ------------------------------------------------------------------
    // Escritura atomica
    // ------------------------------------------------------------------

    private fun escribir(file: Path): Boolean {
        val documento = documento()
        return runCatching {
            val directorio = file.toAbsolutePath().parent
                ?: java.nio.file.Paths.get(".").toAbsolutePath()
            Files.createDirectories(directorio)
            OwnerPermissions.DEFAULT.secureDirectory(directorio)
            // El temporal va en el MISMO directorio: un rename entre sistemas
            // de ficheros deja de ser atomico y puede copiar.
            val temporal = Files.createTempFile(
                directorio,
                file.fileName.toString() + ".",
                ".tmp",
            )
            var movido = false
            try {
                Files.writeString(
                    temporal,
                    documento,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE,
                )
                OwnerPermissions.DEFAULT.secureFile(temporal)
                mover(temporal, file)
                movido = true
            } finally {
                if (!movido) runCatching { Files.deleteIfExists(temporal) }
            }
            OwnerPermissions.DEFAULT.secureFile(file.toAbsolutePath())
            lastError = null
            true
        }.getOrElse {
            lastError = CODE_WRITE_FAILED
            false
        }
    }

    private fun documento(): String = buildJsonObject {
        put("format", FORMAT)
        put("version", VERSION)
        putJsonArray("contacts") {
            synchronized(candado) {
                registros.values.sortedBy { it.identityId }.forEach { registro ->
                    add(
                        buildJsonObject {
                            put("identityId", registro.identityId)
                            put("alias", registro.alias)
                            put("signingKey", B64.encodeToString(registro.signingKey))
                            put("deviceId", registro.deviceId)
                            put("addedAt", registro.addedAt)
                            put("bundle", B64.encodeToString(registro.bundleText.toByteArray(Charsets.ISO_8859_1)))
                        },
                    )
                }
            }
        }
    }.toString()

    // ------------------------------------------------------------------
    // Lectura de una entrada
    // ------------------------------------------------------------------

    private fun parsear(entrada: JsonObject): Record? {
        val identityId = entrada.texto("identityId")?.lowercase() ?: return null
        if (identityId.length != RelayIdHex || identityId.any { it !in "0123456789abcdef" }) return null
        val clave = entrada.texto("signingKey")?.let { b64OrNull(it) } ?: return null
        if (clave.size != PUBKEY_BYTES) return null
        val deviceId = entrada.texto("deviceId") ?: return null
        val addedAt = (entrada["addedAt"] as? JsonPrimitive)?.longOrNull ?: return null
        val bundle = entrada.texto("bundle")?.let { b64OrNull(it) } ?: return null
        if (bundle.isEmpty()) return null
        return Record(
            identityId = identityId,
            alias = entrada.texto("alias").orEmpty(),
            signingKey = clave,
            deviceId = deviceId,
            addedAt = addedAt,
            bundleText = String(bundle, Charsets.ISO_8859_1),
        )
    }

    private fun JsonObject.texto(campo: String): String? =
        (this[campo] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        if (length % 2 != 0) return ByteArray(0)
        return ByteArray(length / 2) { i ->
            ((Character.digit(this[i * 2], 16) shl 4) or Character.digit(this[i * 2 + 1], 16)).toByte()
        }
    }

    companion object {
        const val FORMAT: String = "km-desktop-contacts"
        const val VERSION: Int = 1

        /** El JSON completo no parsea. El fichero NO se toca. */
        const val CODE_CORRUPT: String = "CONTACTS_CORRUPT"

        /** Parseo bien, pero una entrada no verifica. La escritura se bloquea. */
        const val CODE_INVALID_ENTRY: String = "CONTACTS_INVALID_ENTRY"

        /** La escritura atomica fallo. El original sigue intacto. */
        const val CODE_WRITE_FAILED: String = "CONTACTS_WRITE_FAILED"

        /** Una clave Ed25519 publica son 32 B (`KmIdConstants.PUBKEY_BYTES`). */
        const val PUBKEY_BYTES: Int = 32

        /** `RelayDataCodec.IDENTITY_ID_HEX`: 64 hex. */
        private const val RelayIdHex: Int = 64

        private val JSON = Json { ignoreUnknownKeys = true }
        private val B64: Base64.Encoder = Base64.getEncoder()
        private val B64_DECODER: Base64.Decoder = Base64.getDecoder()

        private fun b64OrNull(encoded: String): ByteArray? =
            runCatching { B64_DECODER.decode(encoded) }.getOrNull()
    }
}

/** Renombrado atomico con retroceso a reemplazo cuando el FS no lo soporta. */
private fun moverAtomico(desde: Path, hasta: Path) {
    try {
        Files.move(
            desde,
            hasta,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(desde, hasta, StandardCopyOption.REPLACE_EXISTING)
    }
}

package com.km.desktop.client

import com.km.desktop.ContactStore
import com.km.desktop.DesktopIdentity
import com.km.desktop.PeerKeyDirectory
import com.km.desktop.session.SessionCoordinator
import com.km.frame.PrekeyFrame
import com.km.identity.CarrierCodec
import com.km.identity.ContactImportException
import com.km.identity.ContactIntake
import com.km.identity.ContactRegistry
import com.km.identity.LocalDeviceKeys
import com.km.identity.RejectionCodes
import com.km.identity.RegistrationResult
import com.km.identity.SelfBundleFactory
import com.km.identity.VerifiedContact
import com.km.messaging.SendResult
import com.km.messaging.SecureSessionRegistry
import com.km.model.IdentityId
import com.km.transport.RelayClientStack
import com.km.transport.RelaySignalSink
import com.km.transport.SignalDelivery
import com.km.transport.contact.ContactExchangeBootstrap
import com.km.transport.contact.ContactExchangeCoordinator
import com.km.transport.contact.ContactExchangeException
import com.km.transport.contact.ContactLogLevel
import com.km.transport.contact.ContactLogger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Los servicios de CONTACTO del escritorio: importar, exportar, anunciar, abrir
 * sesion y aceptar el PREKEY. Sin terminal y sin protocolo propio.
 *
 * ## QUE HACE Y QUE NO
 *
 * Orquesta piezas que ya existen: [CarrierCodec] lee el portador,
 * [ContactIntake] decide si un bundle entra, [SelfBundleFactory] fabrica el
 * bundle propio, [ContactExchangeBootstrap] impone el orden SIGNAL -> PREKEY y
 * [SessionCoordinator] llama a X3DH. Aqui no se deriva una clave ni se cifra un
 * frame: eso es km-core.
 *
 * ## TODO SE INYECTA COMO FUNCION
 *
 * La pila, el registro de sesiones, la identidad y el material cambian de valor
 * durante la vida del proceso (conectar, reconectar, aprovisionar). Si se
 * capturaran como valores, un `announce` hecho antes de `connect` usaria una
 * pila nula para siempre. Como funciones, cada operacion ve lo que hay AHORA.
 *
 * ## NUNCA IMPRIME
 *
 * Publica [ClientEvent]s. El terminal lo posee el bucle de la TUI.
 */
class ContactService(
    private val events: ClientEvents,
    private val contacts: ContactRegistry,
    private val intake: ContactIntake,
    private val stack: () -> RelayClientStack?,
    private val sessionRegistry: () -> SecureSessionRegistry?,
    private val identity: () -> DesktopIdentity?,
    private val deviceKeys: () -> LocalDeviceKeys?,
    /**
     * Directorio de claves de FIRMA de los peers, o `null` si esta instancia no
     * registra claves.
     *
     * Es una FUNCION por la misma razon que [stack] o [identity]: el directorio
     * que el nucleo recibio como `peerPublicKeyResolver` se consulta EN VIVO, y
     * un import ocurrido despues del arranque tiene que surtir efecto sin
     * reconstruir el nucleo.
     *
     * El registro ocurre SOLO tras [RegistrationResult.Accepted]: el
     * verificador de `km-core` es el unico que puede decir que el portador es
     * autentico, y una clave que no salio de ahi autenticaria `ACK` de una
     * identidad que no los emitio.
     */
    private val peerKeys: () -> PeerKeyDirectory? = { null },
    /**
     * Persistencia de los contactos aceptados (`contacts.json`).
     *
     * Funcion y no valor por la misma razon que [peerKeys]: un import ocurrido
     * despues del arranque tiene que escribir en el almacen vivo. `null`
     * significa "este cliente no persiste contactos" y no es un error.
     */
    private val contactStore: () -> ContactStore? = { null },
    private val sessions: SessionCoordinator = SessionCoordinator(),
    /**
     * Se invoca con el `keyId` del OPK que el framework dice haber consumido.
     *
     * El framework lo DEVUELVE y no lo marca: un OPK que se vuelve a ofrecer es
     * material reutilizado, y quien lo guarda es quien tiene que darlo de baja.
     */
    private val onOneTimePreKeyConsumed: (Long?) -> Unit = {},
) {

    /** La identidad local, o `null` si el almacen aun no la tiene. */
    fun localIdentity(): IdentityId? = identity()?.identityId

    // ------------------------------------------------------------------
    // Importar
    // ------------------------------------------------------------------

    /**
     * Importa el portador [text] (pegado, QR leido como texto o contenido de un `.km`).
     *
     * @throws ContactImportException con codigo estable.
     */
    fun importText(text: String, expectedRoot: String? = null): VerifiedContact {
        val root = expectedRoot?.let { hex ->
            if (hex.length != 64 || hex.any { it !in "0123456789abcdef" }) {
                throw ContactImportException(
                    RejectionCodes.IMPORT_FAILED,
                    "la raiz esperada no son 64 hex",
                )
            }
            hexToBytes(hex)
        }
        val resultado = intake.manual(text, root)
        val accepted = resultado as? RegistrationResult.Accepted
            ?: throw ContactImportException(
                (resultado as RegistrationResult.Rejected).code,
                "el bundle no se registro",
            )
        // Solo despues de Accepted: un rechazo no escribe NADA, ni contacto ni
        // clave de firma. El contacto queda listo para autenticar sus ACK.
        registrarClaveDeFirma(accepted.contact)
        persistirContacto(accepted.contact)
        events.emit(ClientEvent.ContactImported(accepted.contact, accepted.isNew))
        return accepted.contact
    }

    /**
     * Importa el `.km` de [path] con el codec de fichero compartido.
     *
     * @throws ContactImportException con `FILE_TOO_LARGE` / `EMPTY_ENVELOPE` /
     *   `FILE_READ_FAILED` si el fichero no sirve.
     */
    fun importFile(path: Path): VerifiedContact {
        val text = try {
            Files.newInputStream(path).use { com.km.identity.CarrierFileCodec.read(it) }
        } catch (e: com.km.identity.CarrierFileCodec.CarrierFileException) {
            throw ContactImportException(RejectionCodes.of(e), e.message ?: RejectionCodes.of(e))
        } catch (e: java.io.IOException) {
            throw ContactImportException(
                com.km.identity.CarrierFileCodec.CODE_READ_FAILED,
                "no se pudo leer $path: ${e.message}",
            )
        }
        return importText(text)
    }

    // ------------------------------------------------------------------
    // Exportar
    // ------------------------------------------------------------------

    /**
     * Fabrica el bundle propio. Se exporta UNA vez por anuncio.
     *
     * @param maxOneTimePreKeys `0` (por defecto) para el portador de QR.
     * @throws ContactImportException con [RejectionCodes.MISSING_LOCAL_IDENTITY]
     *   si no hay identidad o material de dispositivo.
     */
    fun exportOwn(maxOneTimePreKeys: Int = SelfBundleFactory.QR_ONE_TIME_PREKEYS): SelfBundleFactory.Exported {
        val actual = identity() ?: throw ContactImportException(
            RejectionCodes.MISSING_LOCAL_IDENTITY,
            "no hay identidad local: no se puede fabricar el bundle propio",
        )
        val keys = deviceKeys() ?: throw ContactImportException(
            RejectionCodes.MISSING_LOCAL_IDENTITY,
            "no hay material de dispositivo: no se puede fabricar el bundle propio",
        )
        val exportado = SelfBundleFactory.export(
            signingPublicKey = actual.ed25519PublicKey,
            signingPrivateKey = actual.ed25519PrivateKey,
            localKeys = keys,
            maxOneTimePreKeys = maxOneTimePreKeys,
        )
        events.emit(ClientEvent.OwnBundleExported(exportado))
        return exportado
    }

    /** Escribe el portador propio en [path], ISO-8859-1 y sin normalizar. */
    fun exportToFile(path: Path, maxOneTimePreKeys: Int = SelfBundleFactory.QR_ONE_TIME_PREKEYS): Path {
        val exportado = exportOwn(maxOneTimePreKeys)
        Files.newOutputStream(path).use { com.km.identity.CarrierFileCodec.write(it, exportado.text) }
        events.emit(ClientEvent.Info("bundle escrito en $path (${exportado.byteLength} B)"))
        return path
    }

    // ------------------------------------------------------------------
    // Anunciar / abrir sesion
    // ------------------------------------------------------------------

    /**
     * La secuencia un-solo-scan: portador propio -> SIGNAL -> PREKEY.
     *
     * @throws ContactExchangeException con codigo estable si la SIGNAL no se
     *   entrega; en ese caso NO hay sesion y NO hay PREKEY.
     */
    suspend fun announce(peer: VerifiedContact): Result<Unit> {
        val pila = stack() ?: return Result.failure(
            ContactExchangeException(
                ContactExchangeBootstrap.CODE_EXPORT_FAILED,
                "no hay transporte: conecta antes de anunciar",
            ),
        )
        val bootstrap = ContactExchangeBootstrap(
            exportarPortador = { exportarBytesPropios() },
            enviarPortador = { portador ->
                SignalDelivery(RelaySignalSink(pila.transport, peer.identityRoot)).deliver(portador)
            },
            abrirSesionYEntregar = { openAndDeliver(peer) },
            esperarReauth = { timeoutMs -> pila.transport.awaitOnline(timeoutMs) },
            logger = loggerDeEventos(),
        )
        return try {
            val resultado = bootstrap.ejecutar()
            if (resultado.isSuccess) events.emit(ClientEvent.Announced(peer.identityRoot))
            resultado
        } catch (e: ContactExchangeException) {
            events.emit(ClientEvent.Error("intercambio NO entregado codigo=${e.code}"))
            Result.failure(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            events.emit(ClientEvent.Error("intercambio fallido: ${e.message}"))
            Result.failure(e)
        }
    }

    /** Abre la sesion E2E con [peer] y ENTREGA su frame PREKEY por el canal de par. */
    fun openAndDeliver(peer: VerifiedContact): Result<Unit> = runCatching {
        val pila = stack() ?: throw IllegalStateException("no hay transporte")
        val registro = sessionRegistry() ?: throw IllegalStateException("no hay registro de sesiones")
        val actual = identity() ?: throw ContactImportException(
            RejectionCodes.MISSING_LOCAL_IDENTITY, "no hay identidad local",
        )
        val keys = deviceKeys() ?: throw ContactImportException(
            RejectionCodes.MISSING_LOCAL_IDENTITY, "no hay material de dispositivo",
        )
        val bundle = CarrierCodec.decode(peer.bundleText)
        val contexto = sessions.contextoHacia(bundle)
        val iniciada = sessions.iniciar(
            localIdentity = actual.identityId,
            peerContext = contexto,
            localKeys = keys.initiatorKeys(),
            manager = pila.manager,
            registry = registro,
            // El indice sale del BUNDLE, no de un default: pedir 0 sobre un
            // bundle sin OPK hace LANZAR a X3DH y no habria sesion.
            oneTimePrekeyIndex = bundle.initiatorOneTimePrekeyIndex,
        )
        when (val entrega = iniciada.session.sendTranscript(iniciada.prekeyFrame)) {
            is SendResult.Ok -> Unit
            else -> throw IllegalStateException("el frame PREKEY no salio por el canal de par: $entrega")
        }
        events.emit(ClientEvent.SessionOpened(peer.identityRoot))
    }.onFailure {
        events.emit(ClientEvent.Error("no se pudo abrir sesion: ${it.message}"))
    }

    // ------------------------------------------------------------------
    // Entrada: PREKEY y contact_exchange
    // ------------------------------------------------------------------

    /**
     * Cablea las dos caras de la senalizacion sobre [pila].
     *
     * - `manager.onData`: bytes opacos del canal de par. Un PREKEY de un par
     *   desconocido se RETIENE ([ContactExchangeCoordinator]) hasta que llegue
     *   su bundle; los demas bytes son de otro camino y no se tocan.
     * - `onContactBundle`: el portador del emisor. Se registra por el camino
     *   unico y se drena lo retenido.
     */
    fun attachInbound(pila: RelayClientStack, coordenador: ContactExchangeCoordinator) {
        pila.manager.onData { from, wire ->
            coordenador.onPrekey(from, wire) { emisor, frame -> acceptPrekey(emisor, frame) }
        }
        pila.onContactBundle { from, bytes ->
            // El portador viaja en ISO-8859-1: es la regla con la que se
            // escribio y no se reinterpreta como UTF-8.
            receiveBundle(from, String(bytes, Charsets.ISO_8859_1), coordenador)
        }
    }

    /**
     * Un `contact_exchange`: registra al emisor y drena su PREKEY retenido.
     *
     * No lanza: corre en el hilo de recepcion, y un bundle raro es un rechazo.
     */
    fun receiveBundle(from: IdentityId, portador: String, coordenador: ContactExchangeCoordinator): Boolean {
        val resultado = try {
            intake.inbound(from, portador)
        } catch (e: ContactImportException) {
            events.emit(ClientEvent.Warn("contact_exchange rechazado codigo=${e.code}"))
            return false
        } catch (e: Exception) {
            events.emit(ClientEvent.Warn("contact_exchange rechazado codigo=${RejectionCodes.of(e)}"))
            return false
        }
        val accepted = resultado as? RegistrationResult.Accepted ?: run {
            events.emit(ClientEvent.Warn("contact_exchange rechazado codigo=${(resultado as RegistrationResult.Rejected).code}"))
            return false
        }
        // El bundle lo autentico `ContactIntake` (root == from): recien ahora la
        // clave de firma entra al directorio del que sale el resolver de ACK.
        registrarClaveDeFirma(accepted.contact)
        persistirContacto(accepted.contact)
        events.emit(ClientEvent.ContactImported(accepted.contact, accepted.isNew))
        if (accepted.isNew) {
            coordenador.onContactoRegistrado(from) { emisor, frame -> acceptPrekey(emisor, frame) }
        }
        return true
    }

    /**
     * Acepta un frame PREKEY de un emisor que YA es contacto.
     *
     * El OPK que se usa al responder es el que el EMISOR consumio, identificado
     * por el bundle verificado del emisor y no por uno cualquiera del almacen:
     * si emisor y receptor no usan el mismo `DH4`, el `SK` sale distinto y el
     * frame no abre.
     */
    fun acceptPrekey(from: IdentityId, wire: ByteArray): Result<Unit> = runCatching {
        if (!PrekeyFrame.esPrekey(wire)) return@runCatching
        if (sessionRegistry()?.find(from) != null) {
            // Reenvio de un PREKEY ya aceptado: no se reabre nada.
            return@runCatching
        }
        val pila = stack() ?: throw IllegalStateException("no hay transporte")
        val registro = sessionRegistry() ?: throw IllegalStateException("no hay registro de sesiones")
        val actual = identity() ?: throw ContactImportException(
            RejectionCodes.MISSING_LOCAL_IDENTITY, "no hay identidad local",
        )
        val keys = deviceKeys() ?: throw ContactImportException(
            RejectionCodes.MISSING_LOCAL_IDENTITY, "no hay material de dispositivo",
        )
        val peer = contacts.find(from)
            ?: throw IllegalStateException("PREKEY de un contacto desconocido")
        val bundle = CarrierCodec.decode(peer.bundleText)
        val contexto = sessions.contextoHacia(bundle)
        val opkId = bundle.oneTimePrekeyIdAt(OPK_INDICE)
        val aceptada = sessions.aceptar(
            localIdentity = actual.identityId,
            peerContext = contexto,
            localKeys = keys.responderKeys(opkId),
            prekeyFrameWire = wire,
            manager = pila.manager,
            registry = registro,
        )
        // `consumedOneTimePreKeyId` lo DICE el framework y no lo marca: se da de
        // baja aqui para que el OPK no se vuelva a ofrecer.
        onOneTimePreKeyConsumed(aceptada.consumedOneTimePreKeyId)
        events.emit(ClientEvent.SessionOpened(from))
    }.onFailure {
        events.emit(ClientEvent.Warn("PREKEY no aceptado: ${it.message}"))
    }

    // ------------------------------------------------------------------
    // Consulta
    // ------------------------------------------------------------------

    fun all(): List<VerifiedContact> = contacts.all()

    fun find(identityRoot: IdentityId): VerifiedContact? = contacts.find(identityRoot)

    private fun exportarBytesPropios(): ByteArray =
        exportOwn().text.toByteArray(Charsets.ISO_8859_1)

    /**
     * Registra en [PeerKeyDirectory] la clave de FIRMA del contacto ACEPTADO.
     *
     * ## POR QUE SE RELEE EL PORTADOR
     *
     * Porque `registerFromBundleText` vuelve a pasar por el verificador
     * compartido. Copiar `subjectSigningKey` del registro de contactos seria
     * confiar en un dato guardado; releer el `.km` aceptado es la misma
     * garantia que tuvo el import.
     *
     * ## ES IDEMPOTENTE, Y UN FALLO NO ROMPE EL IMPORT
     *
     * Volver a registrar la misma pareja deja la misma clave. Si la relectura
     * fallara (no deberia: el intake acaba de aceptar el mismo texto), se avisa
     * con un evento y el import sigue: lo que se pierde es la autenticacion de
     * ACK de ese par, no el contacto ya verificado.
     */
    private fun registrarClaveDeFirma(contacto: VerifiedContact) {
        val directorio = peerKeys() ?: return
        val raiz = try {
            directorio.registerFromBundleText(contacto.bundleText)
        } catch (e: Exception) {
            events.emit(
                ClientEvent.Warn("clave de firma NO registrada codigo=${RejectionCodes.of(e)}"),
            )
            return
        }
        if (raiz == null || raiz.value != contacto.identityRoot.value) {
            events.emit(ClientEvent.Warn("clave de firma NO registrada: raiz incoherente"))
            return
        }
        // F2 — la clave de ACK se PERSISTE. Antes solo la guardaba en memoria el
        // modo linea (`PeerKeyDirectory.save()` era exclusivo de `peer`), asi
        // que un reinicio del escritorio dejaba todos los ACK sin autenticar
        // hasta que el usuario re-importara. `save()` reescribe `peers.json`
        // entero, que es atomico por construccion del propio directorio.
        if (directorio.persistent() && !directorio.save()) {
            events.emit(ClientEvent.Warn("clave de firma NO persistida codigo=$CODE_PEERS_SAVE_FAILED"))
        }
    }

    /**
     * Escribe el contacto aceptado en `contacts.json`.
     *
     * Un fallo de disco NO deshace el import: el contacto ya verificado sigue
     * en memoria y puede hablar en esta sesion. Lo que se pierde es su
     * supervivencia al reinicio, y eso se DICE con un codigo estable.
     */
    private fun persistirContacto(contacto: VerifiedContact) {
        val store = contactStore() ?: return
        if (store.save(contacto)) return
        if (store.writesBlocked()) return
        events.emit(
            ClientEvent.Warn(
                "contacto NO persistido codigo=" + (store.lastError ?: ContactStore.CODE_WRITE_FAILED),
            ),
        )
    }

    private fun loggerDeEventos(): ContactLogger = ContactLogger { level, message ->
        events.emit(
            when (level) {
                ContactLogLevel.INFO -> ClientEvent.Info(message)
                ContactLogLevel.WARN -> ClientEvent.Warn(message)
                ContactLogLevel.ERROR -> ClientEvent.Error(message)
            },
        )
    }

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
    }

    companion object {
        /**
         * Indice del OPK en el bundle del EMISOR.
         *
         * `BootstrapPrekeys` lo pide como POSICION en la lista, no como `keyId`.
         * El emisor con QR exporta 0 OPK, asi que el resultado normal es `null`
         * (sesion sin `DH4`) y `oneTimePrekeyIdAt(null)` no se invoca.
         */
        const val OPK_INDICE: Int = 0

        /** La clave de firma no se pudo escribir en `peers.json`. */
        const val CODE_PEERS_SAVE_FAILED: String = "PEERS_SAVE_FAILED"
    }
}

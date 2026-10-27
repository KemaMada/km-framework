package com.km.desktop.ui

import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.MessageState

/**
 * Lo que el reducer PIDE que se haga fuera, como DATO.
 *
 * ## POR QUE UN EFECTO Y NO UNA LLAMADA
 *
 * Porque el reducer es puro: no puede leer un fichero, ni un socket, ni el reloj.
 * Devuelve lo que hay que hacer y el bucle de la TUI (Fase 3) lo ejecuta en su
 * worker. Asi la transicion se prueba sin servicios y el orden de los efectos es
 * parte del estado, no del azar de un hilo.
 *
 * ## QUE NO LLEVA
 *
 * Ni credenciales, ni cierres (`lambda`), ni `Result`. Un efecto con un lambda
 * dentro no se puede comparar en un test, y una transicion que no se puede
 * comparar no se puede afirmar.
 */
sealed interface ShellEffect {

    /** Pide la lista de contactos (INICIO al arrancar o al volver). */
    data class LoadContacts(val reason: String = "inicio") : ShellEffect

    /** Pide el historial conocido de [peer]. */
    data class LoadHistory(val peer: IdentityId) : ShellEffect

    /** Importa el portador pegado. `expectedRoot` es 64 hex o `null`. */
    data class ImportText(val text: String, val expectedRoot: String? = null) : ShellEffect

    /** Importa un fichero `.km` (ISO-8859-1, tope del codec de G1). */
    data class ImportFile(val path: String) : ShellEffect

    /** Fabrica el bundle propio. `0` OPK para el portador de QR. */
    data class ExportOwn(val oneTimePreKeys: Int) : ShellEffect

    /** Guarda el portador propio en el `.km` que decida la plataforma. */
    data class SaveCarrier(val carrier: String) : ShellEffect

    /**
     * Copia [text] al portapapeles.
     *
     * [warn] es `true` cuando el contenido es sensible (la frase): el llamador
     * tiene que avisar de que OSC 52 no es confirmable y de que no hay
     * verificacion posible.
     */
    data class CopyText(
        val text: String,
        val what: String,
        val warn: Boolean = false,
    ) : ShellEffect

    /** Crea y envia un mensaje. */
    data class Send(val peer: IdentityId, val text: String) : ShellEffect

    /** Reintenta un tombstone: MISMO `messageId` y timestamp. */
    data class Resend(val messageId: MessageId) : ShellEffect

    /** Descarta un tombstone. Unica ruta de borrado. */
    data class Discard(val messageId: MessageId) : ShellEffect

    /** Anuncia el portador propio a [peer] (SIGNAL y, tras Delivered, PREKEY). */
    data class Announce(val peer: IdentityId) : ShellEffect

    /** Abre la sesion cifrada con [peer] sin anunciar. */
    data class OpenSession(val peer: IdentityId) : ShellEffect

    /** Pide las 24 palabras de la identidad. Solo tras reveal confirmado. */
    data object LoadPhrase : ShellEffect

    /** Valida forma y checksum de la frase tecleada. */
    data class CheckPhrase(val input: String) : ShellEffect

    /** Restaura la identidad desde la frase. Debe exigir confirmacion escrita. */
    data class RestoreIdentity(val words: String, val confirmation: String) : ShellEffect

    /** Exporta solo lineas de `DiagLog`. */
    data class ExportLogs(val filter: String? = null) : ShellEffect

    /** Copia el log filtrado sin payload. */
    data class CopyLogs(val filter: String? = null) : ShellEffect

    /** Pide el portapapeles y lo entrega como [EffectResult.Pasted]. */
    data object Paste : ShellEffect

    /** Repintado completo (Ctrl-L). */
    data object Redraw : ShellEffect

    /** Terminar el proceso de forma limpia (restaurar terminal). */
    data object Quit : ShellEffect
}

/**
 * El resultado de un [ShellEffect].
 *
 * Los fallos viajan como CODIGO ESTABLE, no como texto de excepcion: el codigo
 * es lo que se pinta y lo que se puede afirmar en un test. El detalle, si existe,
 * es para el log y nunca para la fila de un contacto.
 */
sealed interface EffectResult {

    data class ContactsLoaded(val contacts: List<ContactRow>) : EffectResult

    data class ContactsFailed(val code: String) : EffectResult

    data class HistoryLoaded(
        val peer: IdentityId,
        val messages: List<MessageRow>,
    ) : EffectResult

    data class Imported(val contact: ContactRow) : EffectResult

    data class ImportRejected(val code: String) : EffectResult

    data class Exported(
        val carrier: String,
        val byteLength: Int,
        val oneTimePreKeys: Int,
    ) : EffectResult

    data class Saved(val path: String) : EffectResult

    data class Copied(val what: String) : EffectResult

    /**
     * El nucleo acepto el mensaje.
     *
     * [state] es el estado REAL (`SENT` no es entrega) y [text]/[at] vuelven para
     * que el reducer pueda pintar la fila sin una segunda consulta.
     */
    data class Sent(
        val messageId: MessageId,
        val state: MessageState,
        val text: String,
        val at: Long,
    ) : EffectResult

    data class SendRejected(val code: String) : EffectResult

    data class Resent(val messageId: MessageId) : EffectResult

    data class Discarded(val messageId: MessageId) : EffectResult

    data class PhraseLoaded(val words: List<String>) : EffectResult

    data class PhraseChecked(
        val wordCount: Int,
        val checksumOk: Boolean,
    ) : EffectResult

    data class Restored(val identityId: String) : EffectResult

    data class RestoreRejected(val code: String) : EffectResult

    /** Texto pegado por el usuario en el campo activo. */
    data class Pasted(val text: String) : EffectResult

    data class LogsExported(val path: String) : EffectResult

    /** Lineas del buffer de `DiagLog`, ya redactadas, para la pantalla de logs. */
    data class LogsLoaded(val rows: List<LogRow>) : EffectResult

    /** Fallo generico con codigo estable. */
    data class Failed(val code: String, val detail: String? = null) : EffectResult
}

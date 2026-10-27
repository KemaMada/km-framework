package com.km.desktop.client

import com.km.identity.SelfBundleFactory
import com.km.identity.VerifiedContact
import com.km.model.Ack
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lo que ha pasado en los servicios, como DATO.
 *
 * ## POR QUE UN EVENTO Y NO UN `println`
 *
 * Porque en una TUI el terminal tiene UN solo escritor. Los handlers del core y
 * del transporte llegan desde otros hilos: un `println` ahi compite con el
 * renderer y escribe en medio de una pantalla. Los servicios ENCOLAN eventos; el
 * bucle de la TUI los pinta cuando le toca.
 *
 * ## POR QUE `sealed` Y NO UN `String` CON UN CANAL
 *
 * Porque el consumidor tiene que poder distinguir "un contacto se importo" de
 * "el rele acepto el SIGNAL" sin parsear texto. Lo que se pinta se decide al
 * pintar; lo que paso se decide aqui.
 */
sealed class ClientEvent {

    /** Informacion para el usuario. */
    data class Info(val text: String) : ClientEvent()

    /** Algo salio mal pero el proceso sigue. */
    data class Warn(val text: String) : ClientEvent()

    /** Un fallo del que el usuario tiene que enterarse. */
    data class Error(val text: String) : ClientEvent()

    /** Un contacto quedo registrado. */
    data class ContactImported(val contact: VerifiedContact, val isNew: Boolean) : ClientEvent()

    /** Se exporto el portador propio. */
    data class OwnBundleExported(val exported: SelfBundleFactory.Exported) : ClientEvent()

    /** La senalizacion del bundle salio y la sesion quedo abierta. */
    data class Announced(val peer: IdentityId) : ClientEvent()

    /** Hay una sesion cifrada con [peer]. */
    data class SessionOpened(val peer: IdentityId) : ClientEvent()

    /** Un mensaje cifrado entro. */
    data class MessageReceived(val message: Message) : ClientEvent()

    /** Un `ACK` verificado entro. */
    data class AckReceived(val ack: Ack) : ClientEvent()

    /** Un mensaje cambio de estado (OUT, DELIVERED, EXPIRED, ...). */
    data class MessageStateChanged(val messageId: MessageId, val state: MessageState) : ClientEvent()

    /** El transporte cambio de estado. */
    data class TransportState(val state: String) : ClientEvent()

    /** Un peer aparece o desaparece del rele (presencia DECLARADA, no disponibilidad). */
    data class PeerPresence(val peer: IdentityId, val online: Boolean) : ClientEvent()
}

/**
 * El bus de eventos del cliente.
 *
 * Multi-suscriptor y thread-safe: un suscriptor que se da de alta no puede matar
 * al anterior, y el hilo del motor emite mientras la UI consume.
 *
 * ## NUNCA IMPRIME
 *
 * [emit] no conoce `PrintStream`, ni `PrintWriter`, ni la terminal. Esa es toda
 * la garantia de "un solo escritor": el que posee la terminal es el unico que
 * decide que se pinta.
 */
class ClientEvents {

    private val listeners = CopyOnWriteArrayList<(ClientEvent) -> Unit>()
    private val recientes = ArrayDeque<String>()

    /** Suscribe [listener]. Devuelve la baja. */
    fun subscribe(listener: (ClientEvent) -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    /** Publica [event] a todos los suscriptores. */
    fun emit(event: ClientEvent) {
        // Se recuerda lo ultimo para que un suscriptor que llega tarde (o una
        // pantalla que se repinta) pueda mostrar algo util sin inventarlo.
        synchronized(recientes) {
            recientes.addLast(describe(event))
            while (recientes.size > MAX_RECORDADOS) recientes.removeFirst()
        }
        listeners.forEach { listener ->
            // Un suscriptor roto NO puede tumbar el hilo del motor: el resto de
            // suscriptores siguen, y el evento ya se registro.
            runCatching { listener(event) }
        }
    }

    /** Las ultimas lineas, en orden, para diagnostico. */
    fun tail(max: Int = 10): List<String> = synchronized(recientes) {
        recientes.toList().takeLast(max)
    }

    /** La linea que describe [event], sin pintarla. */
    fun describe(event: ClientEvent): String = when (event) {
        is ClientEvent.Info -> event.text
        is ClientEvent.Warn -> event.text
        is ClientEvent.Error -> event.text
        is ClientEvent.ContactImported ->
            "contacto ${if (event.isNew) "importado" else "ya conocido"} ${event.contact.fingerprint}"
        is ClientEvent.OwnBundleExported ->
            "bundle propio ${event.exported.byteLength} B opk=${event.exported.oneTimePreKeyCount}"
        is ClientEvent.Announced -> "anunciado a ${event.peer.value.take(16)}…"
        is ClientEvent.SessionOpened -> "sesion con ${event.peer.value.take(16)}…"
        is ClientEvent.MessageReceived ->
            "mensaje de ${event.message.from.value.take(8)}… (${event.message.payload.size}B)"
        is ClientEvent.AckReceived ->
            "ACK ${event.ack.status} de ${event.ack.from.value.take(8)}…"
        is ClientEvent.MessageStateChanged ->
            "mensaje ${event.messageId.value.toString().take(8)}… -> ${event.state.name}"
        is ClientEvent.TransportState -> "conexion: ${event.state}"
        is ClientEvent.PeerPresence ->
            "peer ${if (event.online) "presente" else "ausente"}: ${event.peer.value.take(16)}…"
    }

    companion object {
        /** Cuantas lineas se recuerdan para diagnostico. */
        const val MAX_RECORDADOS: Int = 200
    }
}

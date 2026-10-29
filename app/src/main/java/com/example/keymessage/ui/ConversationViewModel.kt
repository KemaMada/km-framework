package com.example.keymessage.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.keymessage.contact.ContactImporter
import com.example.keymessage.contact.VerifiedContact
import com.example.keymessage.contact.rejectionCodeOf
import com.example.keymessage.util.KMLog
import com.example.keymessage.data.ConversationRepository
import com.example.keymessage.model.ChatMessage
import com.km.model.IdentityId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 5.2 — Estado de UNA conversacion.
 *
 * `messages` viene del Room que escribe el `MessageStore` del framework, y
 * `lastRejection` es el texto que se pinta cuando un envio se RECHAZO. Los dos
 * campos existen por separado porque son hechos distintos: un mensaje puede
 * haberse entregado y otro no, y una pantalla con un solo campo `error` para los
 * dos borra informacion.
 */
data class ConversationUiState(
    val messages: List<ChatMessage> = emptyList(),
    val peerId: String = "",
    val peerName: String = "",
    /** Texto del ultimo rechazo. `null` cuando no hubo ninguno. */
    val lastRejection: String? = null,
)

class ConversationViewModel(
    private val repository: ConversationRepository,
    private val peerId: IdentityId,
    peerName: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationUiState(peerId = peerId.value, peerName = peerName))
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeConversation(peerId).collect { mensajes ->
                _uiState.update { it.copy(messages = mensajes) }
            }
        }
    }

    /**
     * Envia [text].
     *
     * Un rechazo por falta de sesion cifrada se pinta como lo que es —"no
     * enviado"— y nunca como un error transitorio que invite a pulsar "reintentar"
     * sin cambiar nada.
     */
    fun send(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val envio = repository.send(peerId, text.toByteArray())
            _uiState.update { it.copy(lastRejection = if (envio.sent) null else envio.motivo) }
        }
    }

    /**
     * R13 — reintento EXPLICITO. Es una accion del usuario sobre un mensaje
     * terminal: la fila se queda con su `messageId` y su `timestamp`, y el
     * receptor la deduplica y re-ACKea.
     */
    fun resend(messageId: String) {
        viewModelScope.launch {
            val envio = repository.resend(messageId)
            _uiState.update { it.copy(lastRejection = envio.motivo) }
        }
    }

    /** R13 — descarte explicito, ya confirmado en la pantalla. */
    fun discard(messageId: String) {
        viewModelScope.launch {
            val envio = repository.discard(messageId)
            _uiState.update { it.copy(lastRejection = envio.motivo) }
        }
    }

    class Factory(
        private val repository: ConversationRepository,
        private val peerId: IdentityId,
        private val peerName: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConversationViewModel(repository, peerId, peerName) as T
    }
}

/**
 * 5.2 / 1.3 — La pantalla de importar un contacto.
 *
 * Es un `ViewModel` y no una funcion suelta porque importar tiene dos finales y
 * uno de ellos es un RECHAZO que hay que poder mostrar: `KmIdVerificationException`
 * con su codigo es informacion util ("la firma no cuadra"), y tragarsela para
 * escribir "error" seria tirar el diagnostico.
 */
data class ImportUiState(
    val contact: VerifiedContact? = null,
    /**
     * Codigo ESTABLE del rechazo. Es `null` solo cuando no hubo rechazo; todo
     * fallo de importacion tiene uno, aunque no venga de
     * `KmIdVerificationException` (sobre vacio, malformado o fuera de esquema).
     */
    val rejectionCode: String? = null,
    /** Detalle legible del rechazo. Se pinta siempre que exista, con o sin codigo. */
    val rejectionDetail: String? = null,
)

class ImportContactViewModel(
    private val onImported: (VerifiedContact) -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun import(text: String, expectedIdentityRoot: String? = null) {
        viewModelScope.launch {
            // Se LIMPIA el resultado anterior antes de intentar. Sin esto, pulsar
            // IMPORTAR dos veces con entradas distintas podia dejar en pantalla el
            // rechazo del primer intento mientras el segundo todavia corre, y el
            // segundo resultado de exito no disparaba la navegacion porque el
            // `contact` no cambiaba de un valor no nulo a otro.
            _state.value = ImportUiState()
            // `decodeAndVerify` es PURO de km-core y no debe pisar el hilo principal:
            // verifica tres firmas Ed25519 y dos derivaciones SHA-256.
            val resultado = withContext(Dispatchers.Default) {
                runCatching {
                    ContactImporter.import(
                        text = text,
                        expectedIdentityRoot = expectedIdentityRoot?.let { it.hexToBytesOrNull() },
                    )
                }
            }
            _state.value = resultado.fold(
                onSuccess = { contact ->
                    val navResult = runCatching { onImported(contact) }
                    navResult.fold(
                        onSuccess = { ImportUiState(contact = contact) },
                        onFailure = { failure ->
                            KMLog.event("[CONTACT]", "rechazado codigo=${rejectionCodeOf(failure)}", KMLog.Level.WARN)
                            ImportUiState(
                                rejectionCode = rejectionCodeOf(failure),
                                rejectionDetail = failure.message,
                            )
                        },
                    )
                },
                onFailure = { failure ->
                    // El codigo sale de `rejectionCodeOf`, que es TOTAL: todo fallo
                    // tiene uno. Antes era `(failure as? KmIdVerificationException)?.code`,
                    // y un sobre vacio o malformado dejaba codigo `null`; como la
                    // pantalla solo pinta el detalle cuando hay codigo, el rechazo
                    // desaparecia y el boton parecia no responder.
                    ImportUiState(
                        rejectionCode = rejectionCodeOf(failure),
                        rejectionDetail = failure.message,
                    )
                },
            )
        }
    }
}

private fun String.hexToBytesOrNull(): ByteArray? {
    if (isEmpty() || length % 2 != 0) return null
    return runCatching {
        ByteArray(length / 2) { i ->
            ((Character.digit(this[i * 2], 16) shl 4) or Character.digit(this[i * 2 + 1], 16)).toByte()
        }
    }.getOrNull()
}
package com.example.keymessage.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.keymessage.data.ConversationRepository
import com.keymessage.core.api.KeyMessageCore
import com.keymessage.core.model.IdentityId
import com.keymessage.core.model.Message
import com.keymessage.core.protocol.ConnectionState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ConversationUiState(
    val messages: List<Message> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val peerId: IdentityId = IdentityId(""),
    val peerName: String = ""
)

class ConversationViewModel(
    private val repository: ConversationRepository,
    private val core: KeyMessageCore,
    private val peerId: IdentityId,
    private val peerName: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ConversationUiState(peerId = peerId, peerName = peerName)
    )
    val uiState: StateFlow<ConversationUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeConversation(peerId).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            core.createMessage(peerId, text.toByteArray())
                .onSuccess { msg -> core.sendMessage(msg) }
        }
    }

    fun retry() {
        viewModelScope.launch {
            core.start()
        }
    }

    class Factory(
        private val repository: ConversationRepository,
        private val core: KeyMessageCore,
        private val peerId: IdentityId,
        private val peerName: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ConversationViewModel(repository, core, peerId, peerName) as T
        }
    }
}

package com.example.keymessage.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.keymessage.ui.ConversationUiState
import com.example.keymessage.ui.ConversationViewModel
import com.keymessage.core.model.MessageState

@Composable
fun NewChatScreen(viewModel: ConversationViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .padding(16.dp)
    ) {
        ConnectionIndicator(uiState.connectionState)

        MessageList(
            messages = uiState.messages,
            peerName = uiState.peerName,
            modifier = Modifier.weight(1f)
        )

        MessageInput(
            text = inputText,
            onTextChange = { inputText = it },
            onSend = {
                viewModel.sendMessage(inputText)
                inputText = ""
            },
            onRetry = { viewModel.retry() }
        )
    }
}

@Composable
private fun ConnectionIndicator(state: com.keymessage.core.protocol.ConnectionState) {
    val (color, label) = when (state) {
        com.keymessage.core.protocol.ConnectionState.ONLINE -> Color(0xFF00FF00) to "Online"
        com.keymessage.core.protocol.ConnectionState.CONNECTING -> Color(0xFFFFFF00) to "Connecting..."
        com.keymessage.core.protocol.ConnectionState.AUTHENTICATING -> Color(0xFFFFFF00) to "Authenticating..."
        com.keymessage.core.protocol.ConnectionState.RECONNECTING -> Color(0xFFFFA500) to "Reconnecting..."
        com.keymessage.core.protocol.ConnectionState.DISCONNECTED -> Color(0xFFFF0000) to "Disconnected"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, shape = MaterialTheme.shapes.small)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MessageList(
    messages: List<com.keymessage.core.model.Message>,
    peerName: String,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(messages, key = { it.messageId.value.toString() }) { msg ->
            val isSent = msg.state == MessageState.SENT || msg.state == MessageState.DELIVERED
            val bgColor = if (isSent) Color(0xFF16213E) else Color(0xFF0F3460)
            val align = if (isSent) Alignment.End else Alignment.Start

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalAlignment = align
            ) {
                Box(
                    modifier = Modifier
                        .background(bgColor, shape = MaterialTheme.shapes.medium)
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = String(msg.payload),
                            color = Color(0xFFE0E0E0),
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = stateLabel(msg.state),
                            color = Color(0xFF888888),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message", fontFamily = FontFamily.Monospace) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color(0xFFE0E0E0),
                unfocusedTextColor = Color(0xFFE0E0E0),
                focusedBorderColor = Color(0xFF0F3460),
                unfocusedBorderColor = Color(0xFF16213E)
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onSend,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F3460))
        ) {
            Text("Send", fontFamily = FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.width(4.dp))
        OutlinedButton(
            onClick = onRetry,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE0E0E0))
        ) {
            Text("Retry", fontFamily = FontFamily.Monospace)
        }
    }
}

private fun stateLabel(state: MessageState): String = when (state) {
    MessageState.CREATED -> "created"
    MessageState.QUEUED -> "queued"
    MessageState.SENDING -> "sending"
    MessageState.SENT -> "sent"
    MessageState.DELIVERED -> "delivered"
    MessageState.FAILED -> "failed"
    MessageState.EXPIRED -> "expired"
}

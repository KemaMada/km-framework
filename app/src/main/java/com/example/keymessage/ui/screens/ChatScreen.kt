package com.example.keymessage.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.keymessage.model.ChatMessage
import com.example.keymessage.model.MessageStatus
import com.example.keymessage.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    contactName: String,
    contactId: String,
    allMessages: Map<String, List<ChatMessage>>,
    onSend: (String) -> Unit,
    onBack: () -> Unit
) {
    val messages = allMessages[contactId] ?: emptyList()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBlack)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalDarkGray)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(
                    text = "<- Back",
                    color = TerminalCyan,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = contactName,
                color = TerminalRed,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider(color = TerminalBorder)

        if (messages.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No messages yet.\nSay hello!",
                    color = TerminalGray,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages) { msg ->
                    MessageBubble(msg)
                }
            }
        }

        HorizontalDivider(color = TerminalBorder)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TerminalDarkGray)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("Type message...", color = TerminalGray, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TerminalWhite,
                    unfocusedTextColor = TerminalWhite,
                    cursorColor = TerminalCyan,
                    focusedBorderColor = TerminalCyan,
                    unfocusedBorderColor = TerminalBorder
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (inputText.isNotBlank()) {
                            onSend(inputText.trim())
                            inputText = ""
                        }
                    }
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSend(inputText.trim())
                        inputText = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = TerminalCyan,
                    contentColor = TerminalBlack
                )
            ) {
                Text("Send", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val align = if (msg.isMine) Alignment.End else Alignment.Start
    val fgColor = if (msg.isMine) TerminalCyan else TerminalWhite
    val bracketOpen = if (msg.isMine) "[" else "{"
    val bracketClose = if (msg.isMine) "]" else "}"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (msg.isMine) Alignment.End else Alignment.Start
    ) {
        Text(
            text = "$bracketOpen ${msg.text} $bracketClose",
            color = fgColor,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatTime(msg.timestamp),
                color = TerminalGray,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            if (msg.isMine) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = statusIcon(msg.status),
                    color = statusColor(msg.status),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(2.dp))
}

private fun statusIcon(status: MessageStatus): String = when (status) {
    MessageStatus.SENDING -> "\u22A1"
    MessageStatus.SENT -> "\u22A2"
    MessageStatus.DELIVERED -> "\u22A3"
    MessageStatus.READ -> "\u22A4"
    MessageStatus.FAILED -> "\u22A5"
}

private fun statusColor(status: MessageStatus): androidx.compose.ui.graphics.Color = when (status) {
    MessageStatus.SENDING -> TerminalGray
    MessageStatus.SENT -> TerminalCyan
    MessageStatus.DELIVERED -> TerminalGreen
    MessageStatus.READ -> TerminalGreen
    MessageStatus.FAILED -> TerminalRed
}

private fun formatTime(ts: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val m = cal.get(java.util.Calendar.MINUTE)
    return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
}

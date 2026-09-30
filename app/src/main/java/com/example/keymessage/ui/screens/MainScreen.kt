package com.example.keymessage.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.keymessage.model.Contact
import com.example.keymessage.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    contacts: List<Contact>,
    myPublicId: String,
    myPublicKey: String,
    myName: String,
    onMenuClick: () -> Unit,
    onContactClick: (Contact) -> Unit,
    connectionStatus: Boolean = false
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBlack)
    ) {
        HeaderRow(
            myPublicId = myPublicId,
            myPublicKey = myPublicKey,
            myName = myName,
            onMenuClick = onMenuClick,
            onCopyKey = { copyToClipboard(context, myPublicKey) }
        )
        ColumnHeader()
        Divider(
            modifier = Modifier.padding(horizontal = 4.dp),
            color = TerminalBorder
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (contacts.isEmpty()) {
                EmptyState()
            } else {
                ContactList(
                    contacts = contacts,
                    onContactClick = onContactClick
                )
            }
        }
        StatusBar(contactCount = contacts.size, isConnected = connectionStatus)
    }
}

@Composable
private fun HeaderRow(
    myPublicId: String,
    myPublicKey: String,
    myName: String,
    onMenuClick: () -> Unit,
    onCopyKey: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalDarkGray)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "[=]",
            color = TerminalWhite,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable(onClick = onMenuClick)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "KeyMessage v1.0",
            color = TerminalRed,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = myPublicId,
            color = TerminalCyan,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onCopyKey)
        )
    }
}

@Composable
private fun ColumnHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .background(TerminalDarkGray)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "#",
            color = TerminalGray,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(24.dp)
        )
        Text(
            text = "CONTACTS",
            color = TerminalRed,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "ID",
            color = TerminalRed,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(130.dp)
        )
    }
}

@Composable
private fun ContactList(
    contacts: List<Contact>,
    onContactClick: (Contact) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth()
    ) {
        items(contacts) { contact ->
            ContactRow(
                contact = contact,
                onClick = { onContactClick(contact) }
            )
            Divider(
                color = TerminalBorder,
                modifier = Modifier.padding(horizontal = 8.dp),
                thickness = 0.5.dp
            )
        }
    }
}

@Composable
private fun ContactRow(
    contact: Contact,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (contact.isOnline) "[X]" else "[ ]",
            color = if (contact.isOnline) TerminalGreen else TerminalRed,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(32.dp)
        )
        Text(
            text = contact.name,
            color = TerminalWhite,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = truncateKey(contact.publicKey, 12),
            color = TerminalCyan,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(130.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No contacts",
                color = TerminalGray,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Use [=] menu > Scan QR to add",
                color = TerminalGray,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun StatusBar(contactCount: Int, isConnected: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalDarkGray)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Contacts:",
            color = TerminalGray,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = " $contactCount  ",
            color = TerminalCyan,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = if (isConnected) "P2P  [*]" else "OFFLINE  [ ]",
            color = if (isConnected) TerminalGreen else TerminalRed,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun truncateKey(key: String, maxLen: Int): String {
    if (key.length <= maxLen) return key
    return key.substring(0, maxLen) + "..."
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Public Key", text))
    Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
}

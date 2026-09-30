package com.example.keymessage.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.keymessage.model.Identity
import com.example.keymessage.ui.theme.*

@Composable
fun Sidebar(
    identity: Identity,
    contactCount: Int,
    hasPassword: Boolean,
    onSetUsername: () -> Unit,
    onSetPassword: () -> Unit,
    onShareQR: () -> Unit,
    onScanQR: () -> Unit,
    onEnterKey: () -> Unit,
    onShowSeed: () -> Unit,
    onShowLog: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight()
            .background(TerminalBlack)
            .padding(16.dp)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "KeyMessage",
            color = TerminalRed,
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "v1.0  P2P",
            color = TerminalGreen,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Contacts: ",
            color = TerminalGray,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "$contactCount",
            color = TerminalCyan,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.height(24.dp))
        Divider(color = TerminalBorder)
        Spacer(modifier = Modifier.height(8.dp))

        SidebarItem(">  Set Username", "Current: ${identity.displayName.ifEmpty { "<none>" }}") {
            onSetUsername()
            onClose()
        }
        if (hasPassword) {
            SidebarItem(">  Change Password", "PIN active") {
                onSetPassword()
                onClose()
            }
        } else {
            SidebarItem(">  Set Password", "Optional PIN lock") {
                onSetPassword()
                onClose()
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Divider(color = TerminalBorder)
        Spacer(modifier = Modifier.height(8.dp))

        SidebarItem(">  Share QR", "Send my public key") {
            onShareQR()
            onClose()
        }
        SidebarItem(">  Scan QR", "Add contact") {
            onScanQR()
            onClose()
        }
        SidebarItem(">  Enter Key", "Paste public key") {
            onEnterKey()
            onClose()
        }

        Spacer(modifier = Modifier.height(16.dp))
        Divider(color = TerminalBorder)
        Spacer(modifier = Modifier.height(8.dp))

        SidebarItem(">  Seed Phrase", "Backup identity") {
            onShowSeed()
            onClose()
        }

        Spacer(modifier = Modifier.height(16.dp))
        Divider(color = TerminalBorder)
        Spacer(modifier = Modifier.height(8.dp))

        SidebarItem(">  Debug Log", "View connection logs") {
            onShowLog()
            onClose()
        }
    }
}

@Composable
private fun SidebarItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Text(
            text = title,
            color = TerminalWhite,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = subtitle,
            color = TerminalGray,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

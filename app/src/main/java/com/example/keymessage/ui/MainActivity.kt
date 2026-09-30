package com.example.keymessage.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.keymessage.KeyMessageApp
import com.example.keymessage.data.ConversationRepository
import com.example.keymessage.model.AppState
import com.example.keymessage.model.Contact
import com.example.keymessage.network.ChatManager
import com.example.keymessage.network.dht.DhtNode
import com.example.keymessage.ui.screens.ChatScreen
import com.example.keymessage.ui.screens.MainScreen
import com.example.keymessage.ui.screens.NewChatScreen
import com.example.keymessage.ui.screens.Sidebar
import com.example.keymessage.ui.theme.*
import com.example.keymessage.util.LogBuffer
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.CaptureActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var appState: AppState
    private lateinit var chatManager: ChatManager

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val contents = result.data?.getStringExtra("SCAN_RESULT")
            if (contents != null) handleScanResult(contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LogBuffer.init(this)
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            LogBuffer.log("CRASH", "${e.message}")
            android.util.Log.e("KeyMessage", "Uncaught on ${t.name}", e)
        }
        appState = AppState(this)
        chatManager = ChatManager(appState, applicationContext)
        chatManager.connect()

        setContent {
            KeyMessageTheme {
                val context = androidx.compose.ui.platform.LocalContext.current
                val showNewChat = remember { mutableStateOf(false) }
                if (showNewChat.value) {
                    val app = context.applicationContext as KeyMessageApp
                    val repo = remember {
                        ConversationRepository(
                            core = app.container.core,
                            messageDao = app.container.database.messageDao(),
                            localIdentity = app.container.localIdentity
                        )
                    }
                    val peerId = remember { com.keymessage.core.model.IdentityId("b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0") }
                    val vm: com.example.keymessage.ui.ConversationViewModel = viewModel(
                        factory = com.example.keymessage.ui.ConversationViewModel.Factory(repo, app.container.core, peerId, "Test Peer")
                    )
                    NewChatScreen(viewModel = vm)
                } else {
                    MainUI(appState, chatManager, onScanQR = { launchScanner() }) { showNewChat.value = true }
                }
            }
        }
    }

    private fun handleScanResult(raw: String) {
        val parts = raw.split("|")
        if (parts.size >= 2) {
            val name = parts[0]
            val pubKey = parts[1]
            val nodeId = DhtNode.hashNodeId(pubKey)
            appState.addContact(Contact(id = nodeId, name = name, publicKey = pubKey))
            val myName = appState.identity.displayName.ifEmpty { "User" }
            chatManager.sendContactExchange(nodeId, myName, appState.identity.publicKey)
            Toast.makeText(this, "Contact added: $name", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Invalid QR", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchScanner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        val intent = Intent(this, CaptureActivity::class.java)
        scanLauncher.launch(intent)
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchScanner()
    }
}

@Composable
fun MainUI(appState: AppState, chatManager: ChatManager, onScanQR: () -> Unit, onNewChat: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var drawerOpen by remember { mutableStateOf(false) }
    var showUsernameDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showSeedDialog by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var showQRDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var activeChat by remember { mutableStateOf<Pair<String, String>?>(null) }

    val identity = remember { appState.identity }
    val contactVersion by appState.contactListVersion.collectAsState()
    var contacts by remember(contactVersion) { mutableStateOf(appState.getContacts()) }
    val onlineUsers by chatManager.onlineUsers.collectAsState()
    val connectionStatus by chatManager.connectionStatus.collectAsState()
    val messages by chatManager.messages.collectAsState()

    val displayContacts = remember(contacts, onlineUsers) {
        contacts.map { it.copy(isOnline = onlineUsers.contains(it.id)) }
    }

    fun refresh() { contacts = appState.getContacts() }

    // --- Sidebar (Drawer) ---
    if (drawerOpen) {
        AlertDialog(
            onDismissRequest = { drawerOpen = false },
            modifier = Modifier.fillMaxHeight().widthIn(max = 300.dp),
            title = { },
            text = {
                Sidebar(
                    identity = identity,
                    contactCount = contacts.size,
                    hasPassword = appState.hasPassword(),
                    onSetUsername = { showUsernameDialog = true },
                    onSetPassword = { showPasswordDialog = true },
                    onShareQR = { showQRDialog = true; drawerOpen = false },
                    onScanQR = { drawerOpen = false; onScanQR() },
                    onEnterKey = { showKeyDialog = true },
                    onShowSeed = { showSeedDialog = true },
                    onShowLog = { showLogDialog = true },
                    onClose = { drawerOpen = false }
                )
            },
            confirmButton = { },
            containerColor = TerminalBlack
        )
    }

        // --- Main content: Chat or Contact list ---
    Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        // New protocol FAB
        FloatingActionButton(
            onClick = onNewChat,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Text("KM", fontFamily = FontFamily.Monospace)
        }
        if (activeChat != null) {
            BackHandler { activeChat = null }
            val (chatId, chatName) = activeChat!!
            ChatScreen(
                contactName = chatName,
                contactId = chatId,
                allMessages = messages,
                onSend = { text -> chatManager.sendMessage(chatId, text) },
                onBack = { activeChat = null }
            )
        } else {
            MainScreen(
                contacts = displayContacts,
                myPublicId = identity.publicId,
                myPublicKey = identity.publicKey,
                myName = identity.displayName.ifEmpty { "User" },
                onMenuClick = { drawerOpen = true },
                onContactClick = { contact ->
                    activeChat = Pair(contact.id, contact.name)
                },
                connectionStatus = connectionStatus
            )
        }
    }

    // Dialogs
    if (showUsernameDialog) {
        var nameText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showUsernameDialog = false },
            containerColor = TerminalBlack,
            title = { Text("Set Username", color = TerminalRed, fontFamily = FontFamily.Monospace) },
            text = {
                OutlinedTextField(value = nameText, onValueChange = { nameText = it },
                    placeholder = { Text("Display name", color = TerminalGray, fontFamily = FontFamily.Monospace) }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TerminalWhite, unfocusedTextColor = TerminalWhite, cursorColor = TerminalCyan, focusedBorderColor = TerminalCyan, unfocusedBorderColor = TerminalBorder),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = {
                        if (nameText.isNotBlank()) { appState.identity = identity.copy(displayName = nameText); refresh(); showUsernameDialog = false }
                    }))
            },
            confirmButton = { TextButton(onClick = {
                if (nameText.isNotBlank()) { appState.identity = identity.copy(displayName = nameText); refresh(); showUsernameDialog = false }
            }) { Text("OK", color = TerminalCyan, fontFamily = FontFamily.Monospace) } }
        )
    }

    if (showPasswordDialog) {
        var pw1 by remember { mutableStateOf("") }
        var pw2 by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            containerColor = TerminalBlack,
            title = { Text(if (appState.hasPassword()) "Change Password" else "Set Password", color = TerminalRed, fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    OutlinedTextField(value = pw1, onValueChange = { pw1 = it }, placeholder = { Text("PIN (4+ chars)", color = TerminalGray, fontFamily = FontFamily.Monospace) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TerminalWhite, unfocusedTextColor = TerminalWhite, cursorColor = TerminalCyan, focusedBorderColor = TerminalCyan, unfocusedBorderColor = TerminalBorder))
                    if (appState.hasPassword()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = pw2, onValueChange = { pw2 = it }, placeholder = { Text("Confirm", color = TerminalGray, fontFamily = FontFamily.Monospace) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TerminalWhite, unfocusedTextColor = TerminalWhite, cursorColor = TerminalCyan, focusedBorderColor = TerminalCyan, unfocusedBorderColor = TerminalBorder))
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                if (pw1.length >= 4 && (!appState.hasPassword() || pw1 == pw2)) { appState.password = pw1; showPasswordDialog = false }
            }) { Text("OK", color = TerminalCyan, fontFamily = FontFamily.Monospace) } }
        )
    }

    if (showSeedDialog) {
        AlertDialog(
            onDismissRequest = { showSeedDialog = false },
            containerColor = TerminalBlack,
            title = { Text("Seed Phrase", color = TerminalRed, fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    Text("Backup these words:", color = TerminalGray, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(identity.seedPhrase, color = TerminalCyan, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Keep secret! Anyone with these words\ncan access your identity.", color = TerminalGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { showSeedDialog = false }) { Text("Done", color = TerminalWhite, fontFamily = FontFamily.Monospace) } }
        )
    }

    if (showKeyDialog) {
        var keyIn by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            containerColor = TerminalBlack,
            title = { Text("Enter Key", color = TerminalRed, fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    Text("Format: Name|PublicKey", color = TerminalGray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = keyIn, onValueChange = { keyIn = it },
                        placeholder = { Text("Paste key", color = TerminalGray, fontFamily = FontFamily.Monospace) }, singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TerminalWhite, unfocusedTextColor = TerminalWhite, cursorColor = TerminalCyan, focusedBorderColor = TerminalCyan, unfocusedBorderColor = TerminalBorder))
                }
            },
            confirmButton = { TextButton(onClick = {
                val parts = keyIn.trim().split("|")
                val name = parts.getOrElse(0) { "Unknown" }
                val pub = parts.getOrElse(1) { keyIn.trim() }
                val id = DhtNode.hashNodeId(pub)
                appState.addContact(Contact(id = id, name = name, publicKey = pub))
                chatManager.sendContactExchange(id, appState.identity.displayName.ifEmpty { "User" }, appState.identity.publicKey)
                refresh()
                showKeyDialog = false
            }) { Text("Add", color = TerminalCyan, fontFamily = FontFamily.Monospace) } }
        )
    }

    if (showQRDialog) {
        val qrData = "${identity.displayName.ifEmpty { "User" }}|${identity.publicKey}"
        val size = 256
        val writer = QRCodeWriter()
        val bitmap = remember {
            try {
                val bm = writer.encode(qrData, BarcodeFormat.QR_CODE, size, size)
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
                for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (bm[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                bmp
            } catch (e: Exception) { null }
        }
        AlertDialog(
            onDismissRequest = { showQRDialog = false },
            containerColor = TerminalBlack,
            title = { Text("My Contact", color = TerminalRed, fontFamily = FontFamily.Monospace) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (bitmap != null) Image(bitmap = bitmap.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(256.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(qrData, color = TerminalCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = { showQRDialog = false }) { Text("Close", color = TerminalWhite, fontFamily = FontFamily.Monospace) } }
        )
    }

    if (showLogDialog) {
        val logs = remember { mutableStateOf(LogBuffer.getLines()) }
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        LaunchedEffect(showLogDialog) {
            while (showLogDialog) {
                logs.value = LogBuffer.getLines()
                delay(1000)
            }
        }
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            containerColor = TerminalBlack,
            title = { Text("Debug Log", color = TerminalRed, fontFamily = FontFamily.Monospace) },
            text = {
                Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    logs.value.forEach { line ->
                        Text(line, color = TerminalCyan, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val full = logs.value.joinToString("\n")
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("logs", full))
                        android.widget.Toast.makeText(context, "Logs copied", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("Copy", color = TerminalCyan, fontFamily = FontFamily.Monospace) }
                    TextButton(onClick = { showLogDialog = false }) { Text("Close", color = TerminalWhite, fontFamily = FontFamily.Monospace) }
                }
            }
        )
    }
}

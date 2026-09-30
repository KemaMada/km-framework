package com.example.keymessage

import android.app.Application
import androidx.room.Room
import com.example.keymessage.network.RelayClient
import com.example.keymessage.storage.room.database.KeyMessageDatabase
import com.example.keymessage.storage.room.impl.RoomDuplicateStore
import com.example.keymessage.storage.room.impl.RoomMessageStore
import com.example.keymessage.storage.room.impl.RoomOfflineQueue
import com.keymessage.core.api.KeyMessageCoreImpl
import com.keymessage.core.crypto.Ed25519Impl
import com.keymessage.core.crypto.KeyPair
import com.keymessage.core.model.IdentityId
import com.keymessage.core.model.MessageState

class AppContainer(application: Application) {

    private val ed25519 = Ed25519Impl()
    private val keyPair: KeyPair = loadOrCreateKeyPair(application)

    val localIdentity = IdentityId(bytesToHex(keyPair.publicKey.take(20).toByteArray()))

    val database: KeyMessageDatabase = Room.databaseBuilder(
        application,
        KeyMessageDatabase::class.java,
        "keymessage.db"
    ).build()

    val messageStore = RoomMessageStore(database.messageDao())
    val offlineQueue = RoomOfflineQueue(database.offlineQueueDao())
    val duplicateStore = RoomDuplicateStore(database.duplicateDao())

    val relayIdentityId = IdentityId("f9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0")

    val transport = RelayClient(
        url = "ws://10.0.2.2:8080/ws",
        localIdentity = localIdentity,
        localPrivateKey = keyPair.privateKey,
        localPublicKey = keyPair.publicKey,
        relayIdentityId = relayIdentityId,
        ed25519 = ed25519
    )

    val core = KeyMessageCoreImpl(
        localIdentity = localIdentity,
        localPrivateKey = keyPair.privateKey,
        transport = transport,
        ed25519 = ed25519,
        messageStore = messageStore,
        offlineQueue = offlineQueue,
        duplicateStore = duplicateStore
    )

    init {
        // NI6: STORED is custody confirmation, never authorization to delete the local copy.
        transport.setStoredHandler { receipt ->
            // Keep the local durable copy; optionally notify the UI via a state callback later.
        }

        // RELAY_EXPIRED: the relay dropped the message; mark it EXPIRED locally (KM-0004 §14.4).
        transport.setRelayExpiredHandler { notice ->
            messageStore.updateState(notice.originalMessageId, MessageState.EXPIRED, null)
        }
    }

    private fun loadOrCreateKeyPair(application: Application): KeyPair {
        val prefs = application.getSharedPreferences("km_keys", android.content.Context.MODE_PRIVATE)
        val pubKey = prefs.getString("public_key", null)
        val privKey = prefs.getString("private_key", null)

        if (pubKey != null && privKey != null) {
            return KeyPair(
                java.util.Base64.getDecoder().decode(pubKey),
                java.util.Base64.getDecoder().decode(privKey)
            )
        }

        val pair = ed25519.generateKeyPair()
        prefs.edit()
            .putString("public_key", java.util.Base64.getEncoder().encodeToString(pair.publicKey))
            .putString("private_key", java.util.Base64.getEncoder().encodeToString(pair.privateKey))
            .apply()
        return pair
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

package com.example.keymessage.model

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

class AppState(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "keymessage_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var identity: Identity
        get() {
            val seed = prefs.getString("seed", null)
            val pub = prefs.getString("pubkey", null)
            val priv = prefs.getString("privkey", null)
            val pid = prefs.getString("public_id", null)
            val name = prefs.getString("display_name", "")
            return if (seed != null && pub != null && priv != null) {
                Identity(seed!!, pub!!, priv!!, pid ?: Identity.hashToId(Base64.decode(pub, Base64.NO_WRAP)), name ?: "")
            } else {
                val id = Identity.generate()
                prefs.edit()
                    .putString("seed", id.seedPhrase)
                    .putString("pubkey", id.publicKey)
                    .putString("privkey", id.privateKey)
                    .putString("public_id", id.publicId)
                    .apply()
                id
            }
        }
        set(value) {
            prefs.edit()
                .putString("display_name", value.displayName)
                .putString("pubkey", value.publicKey)
                .putString("public_id", value.publicId)
                .apply()
        }

    var password: String?
        get() = prefs.getString("password", null)
        set(value) {
            prefs.edit()
                .putString("password", value)
                .apply()
        }

    val privateKey: String get() = identity.privateKey

    fun hasPassword(): Boolean = prefs.contains("password")
    fun verifyPassword(pw: String): Boolean = pw == prefs.getString("password", null)

    fun getContacts(): MutableList<Contact> {
        val json = prefs.getString("contacts", "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<Contact>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                Contact(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    publicKey = obj.getString("publicKey"),
                    isOnline = false
                )
            )
        }
        return list
    }

    private val _contactListVersion = MutableStateFlow(0)
    val contactListVersion: StateFlow<Int> = _contactListVersion

    fun addContact(contact: Contact) {
        val list = getContacts()
        if (list.none { it.id == contact.id }) {
            list.add(contact)
            saveContacts(list)
            _contactListVersion.value++
        }
    }

    fun removeContact(id: String) {
        val list = getContacts()
        list.removeAll { it.id == id }
        saveContacts(list)
    }

    fun saveContacts(contacts: List<Contact>) {
        val arr = JSONArray()
        for (c in contacts) {
            arr.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("publicKey", c.publicKey)
                }
            )
        }
        prefs.edit().putString("contacts", arr.toString()).apply()
        _contactListVersion.value++
    }

    fun getMessages(contactId: String): MutableList<ChatMessage> {
        val json = prefs.getString("msgs_$contactId", "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<ChatMessage>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                ChatMessage(
                    id = obj.getString("id"),
                    senderId = obj.getString("senderId"),
                    receiverId = obj.getString("receiverId"),
                    text = obj.getString("text"),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    isMine = obj.optBoolean("isMine", false),
                    status = try { MessageStatus.valueOf(obj.optString("status", "SENT")) } catch (_: Exception) { MessageStatus.SENT }
                )
            )
        }
        return list
    }

    fun saveMessages(contactId: String, messages: List<ChatMessage>) {
        val arr = JSONArray()
        for (m in messages) {
            arr.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("senderId", m.senderId)
                    put("receiverId", m.receiverId)
                    put("text", m.text)
                    put("timestamp", m.timestamp)
                    put("isMine", m.isMine)
                    put("status", m.status.name)
                }
            )
        }
        prefs.edit().putString("msgs_$contactId", arr.toString()).apply()
    }

    fun getAllContactIds(): List<String> {
        val all = prefs.all ?: return emptyList()
        val ids = mutableListOf<String>()
        for (key in all.keys) {
            if (key.startsWith("msgs_")) {
                ids.add(key.removePrefix("msgs_"))
            }
        }
        return ids
    }
}

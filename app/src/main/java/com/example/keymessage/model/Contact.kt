package com.example.keymessage.model

data class Contact(
    val id: String,
    val name: String,
    val publicKey: String,
    val isOnline: Boolean = false
)

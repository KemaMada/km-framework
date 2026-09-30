package com.keymessage.core.model

data class RelayLimits(
    val maxMessageSize: Long = DEFAULT_MAX_MESSAGE_SIZE,
    val maxStoredMessages: Int = DEFAULT_MAX_STORED_MESSAGES,
    val maxStorageBytes: Long = DEFAULT_MAX_STORAGE_BYTES,
    val maxMessageAgeMs: Long = DEFAULT_MAX_MESSAGE_AGE_MS,
    val maxConnections: Int = DEFAULT_MAX_CONNECTIONS
) {
    companion object {
        const val DEFAULT_MAX_MESSAGE_SIZE = 65536L
        const val DEFAULT_MAX_STORED_MESSAGES = 10000
        const val DEFAULT_MAX_STORAGE_BYTES = 1073741824L
        const val DEFAULT_MAX_MESSAGE_AGE_MS = 604800000L
        const val DEFAULT_MAX_CONNECTIONS = 512
    }
}
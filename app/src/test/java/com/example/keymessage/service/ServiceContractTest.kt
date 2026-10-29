package com.example.keymessage.service

import com.km.protocol.ConnectionState
import org.junit.Test
import kotlin.test.assertTrue

class ServiceContractTest {
    @Test
    fun textCubreTodosLosEstados() {
        ConnectionState.values().forEach { state ->
            val t = ServiceContract.text(state)
            assertTrue(t.isNotBlank(), "texto vacio para $state")
        }
    }

    @Test
    fun textConDetailAnexa() {
        val t = ServiceContract.text(ConnectionState.ONLINE, "relay.example")
        assertTrue(t.contains("relay.example"))
    }

    @Test
    fun constantesBasicos() {
        assertTrue(ServiceContract.CHANNEL_ID.isNotBlank())
        assertTrue(ServiceContract.NOTIFICATION_ID > 0)
        assertTrue(ServiceContract.TITLE.isNotBlank())
    }
}
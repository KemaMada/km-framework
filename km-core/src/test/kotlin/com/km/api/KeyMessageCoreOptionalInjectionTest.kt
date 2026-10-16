package com.km.api

import com.km.codec.JsonEncoder
import com.km.crypto.Ed25519Impl
import com.km.identity.KmTestDevice
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.protocol.ConnectionState
import com.km.protocol.Transport
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tarea 2.1 — la inyeccion nueva es ADITIVA: sin ella, nada cambia.
 *
 * Es la prueba de no-regresion de los 1331 tests, pero por la via que importa
 * aqui: no basta con que la suite siga verde, hace falta que el BYTE que sale
 * sea exactamente el mismo que antes. Por eso la comparacion es byte a byte
 * contra `JsonEncoder().encodeMessage` del MISMO `Message`, y no contra una
 * golden constante que podria haberse movido a la vez que el codigo.
 */
class KeyMessageCoreOptionalInjectionTest {

    private fun mensaje(): Message = Message(
        messageId = MessageId(UUID.fromString("11111111-2222-3333-4444-555555555555")),
        from = IdentityId("ab".repeat(32)),
        to = IdentityId("cd".repeat(32)),
        payload = "carga-util-no-constante-9012".toByteArray(),
        timestamp = 1_700_000_123_456L,
    )

    @Test
    fun `sin los parametros nuevos el envio sale igual que antes`() {
        val device = KmTestDevice("legacy")
        val transporte = Capturador()
        // Argumentos LEGADOS: ni registro ni manager de par.
        val core = KeyMessageCoreImpl(
            localIdentity = device.identityId,
            localPrivateKey = device.signingKeyPair.privateKey,
            transport = transporte,
            ed25519 = Ed25519Impl(),
        )

        val creado = core.createMessage(IdentityId("cd".repeat(32)), mensaje().payload).getOrThrow()
        core.sendMessage(creado).getOrThrow()

        val esperado = JsonEncoder().encodeMessage(
            mensaje().copy(messageId = creado.messageId, from = creado.from, timestamp = creado.timestamp),
        ).getOrThrow()

        assertEquals(1, transporte.sent.size, "un unico envio por el camino legado")
        assertContentEquals(esperado, transporte.sent.single(), "el byte que sale no ha cambiado")
        assertEquals(
            '{'.code.toByte(),
            transporte.sent.single()[0],
            "y sigue siendo el JSON en claro de siempre",
        )
    }

    @Test
    fun `con la inyeccion presente pero sin sesion el nucleo no se rompe al construir`() {
        // La inyeccion nueva no puede cambiar el CONTRATO de construccion: dos
        // parametros opcionales al final, con valor por defecto `null`.
        val device = KmTestDevice("opt")
        val transporte = Capturador()
        val core = KeyMessageCoreImpl(
            localIdentity = device.identityId,
            localPrivateKey = device.signingKeyPair.privateKey,
            transport = transporte,
            secureSessionRegistry = null,
            peerTransportManager = null,
        )
        core.start()
        assertTrue(transporte.online, "arrancar sigue siendo arrancar")
    }

    /** `Transport` minimo que solo registra lo que sale por `send`. */
    private class Capturador : Transport {
        val sent = mutableListOf<ByteArray>()
        var online: Boolean = true

        override fun connect(): Result<Unit> {
            online = true
            return Result.success(Unit)
        }

        override fun disconnect() {
            online = false
        }

        override fun send(data: ByteArray, to: IdentityId): Result<Unit> {
            sent.add(data.copyOf())
            return Result.success(Unit)
        }

        override fun sendAck(ackBytes: ByteArray): Result<Unit> = Result.success(Unit)
        override fun setMessageHandler(handler: (ByteArray) -> Unit) = Unit
        override fun setAckHandler(handler: (ByteArray) -> Unit) = Unit
        override fun isOnline(): Boolean = online
        override fun connectionState(): ConnectionState =
            if (online) ConnectionState.ONLINE else ConnectionState.DISCONNECTED

        override fun onStateChange(handler: (ConnectionState) -> Unit) = Unit
        override fun onPeerOnline(handler: (IdentityId) -> Unit) = Unit
        override fun onPeerOffline(handler: (IdentityId) -> Unit) = Unit
    }
}
package com.example.keymessage.core

import com.km.codec.JsonEncoder
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import com.km.storage.InMemoryDuplicateStore
import com.km.storage.InMemoryMessageStore
import com.km.storage.InMemoryOfflineQueue
import com.example.keymessage.contact.InMemoryContactBook
import com.example.keymessage.identity.IdentityMaterialFactory
import com.example.keymessage.support.RecordingTransport
import com.example.keymessage.support.countingManager
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 2.2 — Lo que llega EN CLARO no entra, y el rechazo se CUENTA.
 *
 * ## QUE AFIRMA
 *
 * 1. Un `MESSAGE` en claro por el camino legado NO invoca el handler de
 *    mensajes de la aplicacion.
 * 2. `legacyInbound.rejectedCleartext` vale 1: el rechazo es un HECHO
 *    observable, no un silencio. "Cerrado" y "nunca llego" tienen que poder
 *    distinguirse, o un corte de cable y un intento de inyeccion se verian
 *    igual en la pantalla de diagnostico.
 * 3. `deliveredCleartext` vale 0: el camino cerrado no se ha abierto "un poco".
 */
class LegacyInboundClosedTest {

    private val material = IdentityMaterialFactory().fromSeed(ByteArray(32) { (it + 11).toByte() })
    private val local = material.identityId

    private fun nucleo(transport: RecordingTransport) = countingManager().let { (manager, _) ->
        CoreFactory().create(
            localIdentity = local,
            localPrivateKey = material.keyPair.privateKey,
            transport = transport,
            manager = manager,
            messageStore = InMemoryMessageStore(),
            offlineQueue = InMemoryOfflineQueue(),
            duplicateStore = InMemoryDuplicateStore(),
            contactBook = InMemoryContactBook(),
        )
    }

    /** Un `Message` de km-core, codificado como lo Travelingia el rele. */
    private fun mensajeEnClaro(emisor: IdentityId): ByteArray {
        val mensaje = Message(
            messageId = MessageId.fromString("3f2b8a1c-5d4e-4f60-9a7b-8c1d2e3f4a5b"),
            from = emisor,
            to = local,
            payload = "inyectado en claro, con contenido propio 3333".toByteArray(),
            timestamp = 1_760_000_000_000L,
            state = MessageState.CREATED,
        )
        return JsonEncoder().encodeMessage(mensaje).getOrThrow()
    }

    @Test
    fun `un MESSAGE en claro NO llega al handler y queda CONTADO como rechazo`() {
        val transport = RecordingTransport(online = true)
        val bundle = nucleo(transport)
        val recibidos = mutableListOf<Message>()
        bundle.core.registerMessageHandler { recibidos += it }

        transport.deliverLegacyCleartext(mensajeEnClaro(IdentityId("a".repeat(64))))

        assertTrue(recibidos.isEmpty(), "el camino en claro esta cerrado: el handler no se invoca")
        assertEquals(1, bundle.legacyInbound.rejectedCleartext.get(), "y el rechazo CUENTA")
        assertEquals(0, bundle.legacyInbound.deliveredCleartext.get(), "nada se entrego en claro")
    }

    @Test
    fun `varios intentos en claro se cuentan todos`() {
        val transport = RecordingTransport(online = true)
        val bundle = nucleo(transport)
        bundle.core.registerMessageHandler { }

        repeat(4) {
            transport.deliverLegacyCleartext(mensajeEnClaro(IdentityId("b".repeat(64))))
        }

        assertEquals(
            4,
            bundle.legacyInbound.rejectedCleartext.get(),
            "cuatro intentos, cuatro rechazos contables: un contador que no cuenta no informa",
        )
    }

    @Test
    fun `los bytes en claro NO se guardan como mensajes`() {
        val transport = RecordingTransport(online = true)
        val bundle = nucleo(transport)
        bundle.core.registerMessageHandler { }

        transport.deliverLegacyCleartext(mensajeEnClaro(IdentityId("c".repeat(64))))

        assertEquals(
            0,
            bundle.core.getConversation(IdentityId("c".repeat(64))).getOrThrow().size,
            "un mensaje rechazado no aparece en ninguna conversacion",
        )
    }
}
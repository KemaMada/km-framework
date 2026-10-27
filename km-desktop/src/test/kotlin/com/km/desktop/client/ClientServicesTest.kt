package com.km.desktop.client

import com.km.identity.ContactImportException
import com.km.identity.ContactIntake
import com.km.identity.InMemoryContactRegistry
import com.km.identity.RejectionCodes
import com.km.model.MessageState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Los servicios del cliente: lo que dicen cuando NO pueden, y lo que etiquetan.
 *
 * ## QUE SE AFIRMA
 *
 * - Sin identidad local, exportar falla con `MISSING_LOCAL_IDENTITY` y no
 *   escribe nada. Es el negativo de la spec.
 * - Un nucleo ausente no tumba el proceso: `ChatService` devuelve un fallo con
 *   codigo estable.
 * - La etiqueta de un mensaje es HONESTA: `SENT` se pinta `OUT` y `EXPIRED` se
 *   pinta EXPIRADO con su motivo. Ninguna etiqueta llama "entregado" a un
 *   mensaje sin ACK.
 */
class ClientServicesTest {

    private fun servicio(identidad: com.km.model.IdentityId?): ContactService {
        val registry = InMemoryContactRegistry()
        return ContactService(
            events = ClientEvents(),
            contacts = registry,
            intake = ContactIntake(registry) { identidad },
            stack = { null },
            sessionRegistry = { null },
            identity = { null },
            deviceKeys = { null },
        )
    }

    @Test
    fun `sin identidad local exportar falla estable y no escribe`() {
        val service = servicio(null)
        val fallo = assertFailsWith<ContactImportException> { service.exportOwn() }
        assertEquals(RejectionCodes.MISSING_LOCAL_IDENTITY, fallo.code)
        assertEquals(0, service.all().size)
    }

    @Test
    fun `sin identidad local importar falla estable y no escribe`() {
        val service = servicio(null)
        val fallo = assertFailsWith<ContactImportException> { service.importText("cualquier cosa") }
        assertEquals(RejectionCodes.MISSING_LOCAL_IDENTITY, fallo.code)
        assertEquals(0, service.all().size)
    }

    @Test
    fun `sin nucleo, enviar devuelve un fallo con codigo, no una excepcion`() {
        val chat = ChatService(ClientEvents()) { null }
        val resultado = chat.send(com.km.model.IdentityId("ab".repeat(32)), "hola")
        assertTrue(resultado is ChatService.SendOutcome.Failed)
        assertEquals("SIN_NUCLEO", (resultado as ChatService.SendOutcome.Failed).code)
    }

    @Test
    fun `las etiquetas NO llaman entregado a lo que no tiene ACK`() {
        val id = com.km.model.MessageId.random()
        assertEquals("OUT", ChatService.SendOutcome.Accepted(id, MessageState.SENT, null).label)
        assertEquals("ENTREGADO", ChatService.SendOutcome.Accepted(id, MessageState.DELIVERED, null).label)
        assertEquals(
            "EXPIRADO",
            ChatService.SendOutcome.Accepted(id, MessageState.EXPIRED, "TIMEOUT").label,
        )
        assertEquals(
            "FALLO NO_SESSION",
            ChatService.SendOutcome.Accepted(id, MessageState.FAILED, "NO_SESSION").label,
        )
        assertTrue(ChatService.SendOutcome.Accepted(id, MessageState.SENT, null).label != "ENTREGADO")
    }

    @Test
    fun `sin nucleo, reintentar y descartar fallan sin crear otra fila`() {
        val chat = ChatService(ClientEvents()) { null }
        val id = com.km.model.MessageId.random()
        assertTrue(chat.resend(id).isFailure)
        assertTrue(chat.discard(id).isFailure)
    }

    @Test
    fun `el bus de eventos no imprime y recuerda lo ultimo`() {
        val events = ClientEvents()
        val recibidos = mutableListOf<ClientEvent>()
        events.subscribe { recibidos += it }
        events.emit(ClientEvent.Info("uno"))
        events.emit(ClientEvent.Warn("dos"))

        assertEquals(2, recibidos.size)
        assertTrue(events.tail(1).single().contains("dos"))
    }
}

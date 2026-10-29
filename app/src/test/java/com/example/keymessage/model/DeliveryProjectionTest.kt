package com.example.keymessage.model

import com.km.model.FailureCode
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R11/R12/R16 — LA PROYECCION HONESTA: siete estados, una sola entrega.
 *
 * ## EL DEFECTO QUE VIGILA
 *
 * `MessageState.SENT` se pintaba como "ENVIADO" y con el mismo color que un
 * mensaje confirmado. Pero `SENT` solo significa que el socket LOCAL acepto los
 * bytes: el rele pudo descartarlos y el par no confirmo nada. El usuario lo leyo
 * —con razon— como "dice enviado y no lo esta".
 *
 * ## QUE AFIRMA
 *
 * Que [MessageStatus] es TOTAL sobre los siete estados, que **solo**
 * `DELIVERED` tiene `confirmed = true`, y que los terminales (`EXPIRED`,
 * `FAILED`) exigen accion del usuario y llevan su motivo en el texto.
 */
class DeliveryProjectionTest {

    private val local = IdentityId("aa".repeat(32))
    private val peer = IdentityId("bb".repeat(32))

    private fun mensaje(
        state: MessageState,
        failureCode: FailureCode? = null,
        id: MessageId = MessageId(UUID.randomUUID()),
    ) = Message(
        messageId = id,
        from = local,
        to = peer,
        payload = "hola".toByteArray(),
        timestamp = 1_700_000_000_000L,
        state = state,
        failureCode = failureCode,
    )

    private fun proyectar(state: MessageState, code: FailureCode? = null): ChatMessage =
        ChatMessage.from(mensaje(state, code), local.value)

    // ===================================================================
    // La matriz de los siete estados
    // ===================================================================

    @Test
    fun `solo DELIVERED tiene confirmacion de entrega`() {
        MessageState.entries.forEach { state ->
            val proyectado = proyectar(state)
            val esperado = state == MessageState.DELIVERED
            assertEquals(
                esperado,
                proyectado.status.confirmed,
                "$state: confirmed solo puede ser true en DELIVERED (ACK firmado), fue ${proyectado.status.confirmed}",
            )
        }
    }

    @Test
    fun `SENT se rotula OUT y NUNCA se presenta como entregado`() {
        val proyectado = proyectar(MessageState.SENT)

        assertEquals("OUT (sin confirmar)", proyectado.status.label, "bytes escritos no son entrega")
        val texto = proyectado.status.display(proyectado.statusDetail)
        assertTrue(texto.contains("OUT"), "la pantalla dice OUT: $texto")
        assertFalse(texto.contains("ENTREGADO"), "y no dice ENTREGADO: $texto")
        assertNull(proyectado.statusDetail, "OUT no tiene motivo: no hay confirmacion que haya fallado")
    }

    @Test
    fun `QUEUED y SENDING tampoco se confunden con OUT`() {
        val encolado = proyectar(MessageState.QUEUED)
        val enviando = proyectar(MessageState.SENDING)

        assertEquals("ENCOLADO", encolado.status.label)
        assertEquals("ENVIANDO", enviando.status.label)
        assertFalse(encolado.status.confirmed)
        assertFalse(enviando.status.confirmed)
        assertTrue(
            setOf(encolado.status.label, enviando.status.label, "OUT (sin confirmar)").size == 3,
            "los tres estados previos a la confirmacion son DISTINTOS entre si",
        )
    }

    @Test
    fun `DELIVERED es el unico con tick y su etiqueta es ENTREGADO`() {
        val proyectado = proyectar(MessageState.DELIVERED)
        assertEquals("ENTREGADO", proyectado.status.label)
        assertTrue(proyectado.status.confirmed)
        assertFalse(proyectado.status.needsAction)
    }

    @Test
    fun `EXPIRED exige accion y muestra que no hubo confirmacion mas su motivo`() {
        val conCodigo = proyectar(MessageState.EXPIRED, FailureCode.TIMEOUT)
        assertTrue(conCodigo.status.needsAction, "EXPIRED se puede reenviar o descartar")
        assertEquals("TIMEOUT", conCodigo.statusDetail, "el motivo real, no inventado")
        assertEquals(
            "EXPIRADO: sin confirmacion (TIMEOUT)",
            conCodigo.status.display(conCodigo.statusDetail),
        )

        val sinCodigo = proyectar(MessageState.EXPIRED)
        assertEquals("sin confirmacion", sinCodigo.statusDetail, "sin codigo se dice lo unico cierto")
        assertEquals("EXPIRADO: sin confirmacion", sinCodigo.status.display(sinCodigo.statusDetail))
    }

    @Test
    fun `FAILED exige accion y lleva su codigo en el texto`() {
        val proyectado = proyectar(MessageState.FAILED, FailureCode.AUTH_FAILED)
        assertTrue(proyectado.status.needsAction)
        assertEquals("AUTH_FAILED", proyectado.statusDetail)
        assertEquals("FALLIDO: AUTH_FAILED", proyectado.status.display(proyectado.statusDetail))
        assertFalse(proyectado.status.confirmed)
    }

    @Test
    fun `CREATED no adelanta nada`() {
        val proyectado = proyectar(MessageState.CREATED)
        assertEquals("CREAD", proyectado.status.label)
        assertFalse(proyectado.status.confirmed)
        assertFalse(proyectado.status.needsAction)
    }
}

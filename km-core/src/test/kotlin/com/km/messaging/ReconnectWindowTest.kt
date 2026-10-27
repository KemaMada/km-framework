package com.km.messaging

import com.km.model.MessageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D8 / D9 — LA VENTANA DE RECONEXION: sesion lista, canal del par todavia no.
 *
 * El nucleo se suscribe a la PRESENCIA del rele antes que el driver del canal
 * (`AppContainer` monta el nucleo y luego conduce el binding a `CONNECTED`).
 * Antes, un envio en esa ventana moria en `TRANSPORT_REJECTED` -> `FAILED`
 * terminal: el mensaje no salia y no habia forma de reintentarlo.
 *
 * Lo que se pinza aqui:
 *
 * 1. `PEER_ONLINE` con el binding SIN `CONNECTED` -> CERO wires en el cable y el
 *    mensaje sigue `QUEUED` (ni `FAILED` ni `SENT`).
 * 2. El binding llega a `CONNECTED` -> UN wire, el MISMO frame que el ratchet ya
 *    habia producido (no se re-cifra), y el mensaje pasa a `SENT`.
 */
class ReconnectWindowTest {

    @Test
    fun `PEER_ONLINE sin CONNECTED no mata el mensaje y CONNECTED lo saca`() {
        // Mundo con sesion pero SIN bindings conducidos: se instalan las
        // sesiones a mano con `driveConnected = false`.
        val mundo = SecureWorld.World(secure = true, establish = false)
        val capturados = mundo.alice.captureOutbound()

        // 1. El mensaje se encola con la red local caida: es el QUEUED que la
        //    ventana de reconexion va a intentar sacar.
        mundo.alice.transport.goOffline()
        val creado = mundo.alice.core
            .createMessage(mundo.bobRoot.identityId, "carga en la ventana 8811".toByteArray())
            .getOrThrow()
        mundo.alice.core.sendMessage(creado).getOrThrow()
        assertEquals(MessageState.QUEUED, estadoAlice(mundo, creado), "encolado y esperando")

        // 2. Hay sesion, pero el binding NO esta CONNECTED.
        mundo.handshake(driveConnected = false)

        // 3. El rele dice que el par esta: el nucleo DESPIERTA la cabeza y el
        //    `sendData` del manager rechaza porque el binding no esta CONNECTED.
        mundo.alice.transport.goOnline(mundo.bobRoot.identityId)

        assertTrue(
            capturados.isEmpty(),
            "cero wires: el transport data no acepta bytes con el binding a medio conducir",
        )
        assertEquals(
            MessageState.QUEUED,
            estadoAlice(mundo, creado),
            "TRANSPORT_REJECTED es REINTENTABLE (D9): el mensaje NO queda FAILED",
        )

        // 4. El driver conduce el binding a CONNECTED. Es la senal del canal
        //    real, la que el nucleo ya observa.
        mundo.alice.driveConnected(requireNotNull(mundo.sessionA))

        assertEquals(1, capturados.size, "un unico wire, en cuanto el canal existe")
        assertEquals(
            MessageState.SENT,
            estadoAlice(mundo, creado),
            "y el mensaje pasa a SENT en el store",
        )
        assertTrue(
            capturados.single().isNotEmpty(),
            "el frame que sale es el MISMO que el ratchet produjo en el rechazo, no uno nuevo",
        )
    }

    private fun estadoAlice(mundo: SecureWorld.World, creado: com.km.model.Message): MessageState =
        mundo.alice.core.getConversation(mundo.bobRoot.identityId).getOrThrow()
            .single { it.messageId == creado.messageId }
            .state
}

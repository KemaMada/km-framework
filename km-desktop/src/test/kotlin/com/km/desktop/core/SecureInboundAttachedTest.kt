package com.km.desktop.core

import com.km.desktop.support.SecureTestIdentities
import com.km.desktop.support.relayProbeManager
import com.km.model.Message
import com.km.model.MessageId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 3.1 — El nucleo del escritorio tiene el puente cifrado PUESTO y el camino
 * legado CERRADO.
 *
 * ## QUE AFIRMA
 *
 * `core.secureInbound` no es `null` — lo que significa que hay las dos
 * inyecciones: registro y manager— y `core.legacyInbound.legacyReceiveEnabled`
 * es `false`.
 *
 * ## POR QUE LAS DOS, Y POR QUE EN UN SOLO TEST
 *
 * Porque son las dos mitades de la misma puerta y basta que caiga una para que
 * el sistema parezca seguro sin serlo:
 *
 *  - Con el puente ausente, nada llega por el camino cifrado y el escritorio
 *    parece "sin contactos todavia".
 *  - Con el camino legado abierto, un `MESSAGE` en claro llega al handler al
 *    lado de un mensaje E2E, sin ninguna marca de origen.
 *
 * ## POR QUE `secureInbound != null` ES UNA AFIRMACION Y NO UNA TAUTOLOGIA
 *
 * Porque km-core lo construye con un `if`:
 * `if (secureSessionRegistry != null && peerTransportManager != null)`. Bastaba
 * con que uno de los dos no llegara para que el puente no existiera, y ese `if`
 * no lanza: devuelve `null` y sigue.
 */
class SecureInboundAttachedTest {

    private class Montado(val bundle: CoreFactory.CoreBundle)

    private fun montar(): Montado {
        val local = SecureTestIdentities.localEnd("alice")
        val (manager, _) = relayProbeManager()
        return Montado(
            CoreFactory().create(
                localIdentity = local.identityId,
                localPrivateKey = local.ed25519PrivateKey,
                transport = com.km.desktop.support.recordingTransport(),
                manager = manager,
                peerPublicKeyResolver = { null },
            ),
        )
    }

    @Test
    fun `3-1 el puente de recepcion cifrada esta PRESENTE`() {
        val montado = montar()

        assertNotNull(
            montado.bundle.secureInbound,
            "con registro y manager inyectados, SecureInbound existe: si fuera null, " +
                "nada de lo que llegue por el rele se descifraria",
        )
    }

    @Test
    fun `3-1 el camino legado de recepcion esta CERRADO`() {
        val montado = montar()

        assertFalse(
            montado.bundle.legacyInbound.legacyReceiveEnabled,
            "con registro presente y sin opt-in, el camino en claro esta CERRADO",
        )
    }

    @Test
    fun `3-1 la puerta se lee por la MISMA fabrica que usa DesktopMain`() {
        // Esta prueba mira lo que hay que mirar para que la anterior no sea
        // tautologica: el nucleo se construye por `CoreFactory.create`, que es
        // la unica via por la que `DesktopMain` lo construye. Un nucleo
        // construido a mano en un test probaria el test.
        val montado = montar()

        assertTrue(
            montado.bundle.core.legacyInbound === montado.bundle.legacyInbound,
            "la observacion que ve el consumidor es la MISMA que expone el nucleo",
        )
    }

    /**
     * CONTROL NEGATIVO: el puente se puede construir `null`, y este arnes lo
     * demuestra.
     *
     * Sin esta prueba, "secureInbound no es null" seria verdad por construccion
     * de la arnes y no mediria nada. Se construye el MISMO nucleo por la via
     * que km-core declara—that NO produce puente— y se comprueba que da `null`.
     */
    @Test
    fun `control negativo sin las dos inyecciones el puente NO existe`() {
        val local = SecureTestIdentities.localEnd("alice")
        val (manager, _) = relayProbeManager()

        // Sin registro: km-core deja el camino legado abierto POR COMPATIBILIDAD
        // y no construye el puente. Es exactamente el estado en el que estaba el
        // escritorio antes de p5.
        val sinRegistro = com.km.api.KeyMessageCoreImpl(
            localIdentity = local.identityId,
            localPrivateKey = local.ed25519PrivateKey,
            transport = com.km.desktop.support.recordingTransport(),
            peerTransportManager = manager,
        )

        assertNull(
            sinRegistro.secureInbound,
            "sin registro el puente no existe: km-core lo dice con un null, no con una excepcion",
        )
        assertTrue(
            sinRegistro.legacyInbound.legacyReceiveEnabled,
            "y el camino legado queda ABIERTO, que es lo que hacia el escritorio parecer seguro",
        )
    }

    /**
     * CONTROL NEGATIVO del opt-in: `allowLegacyCleartextReceive = true` reabre
     * el camino, y `CoreFactory` no ofrece esa puerta.
     *
     * Se construye el nucleo con el opt-in puesto SOLO para comprobar que el
     * flag tiene el efecto que dice. Si `CoreFactory` no lo escribiera
     * explicitamente, ese nucleo seria indistinguible del de `CoreFactory`, y la
     * asercion de arriba no midiria el `false` que cree medir.
     */
    @Test
    fun `control negativo el opt-in en true SI reabre el camino legado`() {
        val local = SecureTestIdentities.localEnd("alice")
        val (manager, _) = relayProbeManager()

        val conOptIn = com.km.api.KeyMessageCoreImpl(
            localIdentity = local.identityId,
            localPrivateKey = local.ed25519PrivateKey,
            transport = com.km.desktop.support.recordingTransport(),
            secureSessionRegistry = com.km.messaging.SecureSessionRegistry(local.identityId),
            allowLegacyCleartextReceive = true,
            peerTransportManager = manager,
        )

        assertTrue(
            conOptIn.legacyInbound.legacyReceiveEnabled,
            "el opt-in abre el camino: por eso CoreFactory tiene que escribir el false a proposito",
        )
    }

    /**
     * CONTROL POSITIVO: con el opt-in puesto, el rechazo en claro SE CUENTA.
     *
     * "Cerrado" tiene que poder distinguirse de "nunca llego". Sin un contador
     * que se mueva, un mensaje inyectado en claro seria indistinguible de uno
     * que no existio.
     */
    @Test
    fun `control positivo un claro entrante se cuenta como RECHAZADO`() {
        val local = SecureTestIdentities.localEnd("alice")
        val (manager, _) = relayProbeManager()
        val transporte = com.km.desktop.support.recordingTransport()
        val bundle = CoreFactory().create(
            localIdentity = local.identityId,
            localPrivateKey = local.ed25519PrivateKey,
            transport = transporte,
            manager = manager,
            peerPublicKeyResolver = { null },
        )

        assertEquals(0, bundle.legacyInbound.rejectedCleartext.get(), "antes de nada, cero rechazos")
        assertEquals(0, bundle.legacyInbound.deliveredCleartext.get(), "y cero entregas en claro")

        val recibido: MutableList<Message> = mutableListOf()
        bundle.core.registerMessageHandler { recibido += it }

        val inyectado = com.km.codec.JsonEncoder().encodeMessage(
            Message(
                messageId = MessageId(java.util.UUID.randomUUID()),
                from = SecureTestIdentities.otroIdentityId(),
                to = local.identityId,
                payload = "inyectado en claro 5521".toByteArray(Charsets.UTF_8),
                timestamp = 0L,
            ),
        ).getOrThrow()

        transporte.deliverLegacyCleartext(inyectado)

        assertEquals(1, bundle.legacyInbound.rejectedCleartext.get(), "el claro se RECHAZA y se cuenta")
        assertEquals(0, bundle.legacyInbound.deliveredCleartext.get(), "y no se entrega a la aplicacion")
        assertEquals(0, recibido.size, "el handler de mensajes no ve nada")
    }
}
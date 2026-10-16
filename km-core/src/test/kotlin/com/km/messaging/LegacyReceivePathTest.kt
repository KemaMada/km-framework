package com.km.messaging

import com.km.codec.JsonEncoder
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C-2 — R1 EN RECEPCION: EL ESPEJO DEL ENVIO, Y LO QUE ESTABA ABIERTO.
 *
 * El envio tiene punto de decision desde el slice anterior: con el registro
 * presente nunca se degrada a `Transport.send`, y el rechazo se ve en el estado
 * `FAILED` del mensaje. La recepcion no lo tenia. Con registro + manager + sesion,
 * un `MESSAGE` EN CLARO del camino legado llegaba al handler de la aplicacion con
 * la misma forma y el mismo `from` que un mensaje E2E, sin ninguna marca de
 * origen: el consumidor no tenia forma de saber que estaba leyendo algo sin
 * cifrar.
 *
 * Estos tres tests son los tres finales de la regla, y estan en el orden en que
 * hay que leerlos: con registro el camino legado esta CERRADO; con el opt-in
 * explicito esta ABIERTO y MARCADO; sin registro esta ABIERTO y sin marcar,
 * porque es el camino de compatibilidad y no hay a quien engañar con el.
 *
 * ## LO QUE NO HACE ESTE TEST
 *
 * No comprueba la REDACCION de un KDoc ni busca un símbolo en el codigo. Todo lo
 * que afirma sale de observar el mismo `RecordingTransport` que usa el resto de
 * la fixture: los bytes que entran por `deliverLegacyMessage` son los que un
 * relé entregaria, y lo que mira el handler es lo que veria la aplicacion.
 */
class LegacyReceivePathTest {

    /** Contenido NO constante y de longitud distinta del `messageId`. */
    private val enClaro = "inyectado por el camino legado, sin cifrar, 9931".toByteArray()

    /** Un `Message` de verdad, codificado por el MISMO `JsonEncoder` del nucleo. */
    private fun jsonEnClaro(mundo: SecureWorld.World): ByteArray {
        val mensaje = Message(
            messageId = MessageId(UUID.randomUUID()),
            from = mundo.alice.device.identityId,
            to = mundo.bob.device.identityId,
            payload = enClaro,
            timestamp = 1_700_000_000_000L,
            state = MessageState.SENT,
        )
        return JsonEncoder().encodeMessage(mensaje).getOrThrow()
    }

    // ===================================================================
    // (a) Con registro, el claro NO llega, y el rechazo se ve
    // ===================================================================

    @Test
    fun `con el registro activo un MESSAGE en claro NO llega al handler`() {
        val mundo = SecureWorld.open()
        val recibidos = mutableListOf<Message>()
        mundo.bob.core.registerMessageHandler { recibidos.add(it) }

        val antes = mundo.bob.core.legacyInbound
        assertTrue(!antes.legacyReceiveEnabled, "con el registro el camino legado esta CERRADO")

        mundo.bob.transport.deliverLegacyMessage(jsonEnClaro(mundo))

        assertTrue(recibidos.isEmpty(), "un MESSAGE en claro no se entrega a la aplicacion")
        assertEquals(
            1,
            mundo.bob.core.legacyInbound.rejectedCleartext.get(),
            "y el rechazo es CONTABLE por API publica, no un silencio",
        )
        assertEquals(
            0,
            mundo.bob.core.legacyInbound.deliveredCleartext.get(),
            "nada se entrego en claro: no hay entrega que alguien pueda confundir con E2E",
        )
        assertEquals(
            0,
            requireNotNull(mundo.bob.core.secureInbound).delivered.get(),
            "tampoco por el camino cifrado: este mensaje no es E2E de ninguna manera",
        )
    }

    // ===================================================================
    // (b) Con el opt-in, el claro LLEGA y queda MARCADO como legado
    // ===================================================================

    @Test
    fun `con el opt-in el claro llega y queda marcado como legado, no como E2E`() {
        val mundo = SecureWorld.open(legacyCleartextReceive = true)
        val recibidos = mutableListOf<Message>()
        mundo.bob.core.registerMessageHandler { recibidos.add(it) }

        assertTrue(
            mundo.bob.core.legacyInbound.legacyReceiveEnabled,
            "el opt-in explicito reabre el camino legado",
        )

        mundo.bob.transport.deliverLegacyMessage(jsonEnClaro(mundo))

        assertEquals(1, recibidos.size, "el opt-in entrega el mensaje en claro")
        assertContentEquals(enClaro, recibidos.single().payload, "con su contenido integro")
        assertEquals(mundo.alice.device.identityId, recibidos.single().from, "y el emisor que dice el JSON")

        // Y AQUI ESTA LA MARCA, que es lo que hace que el opt-in no sea E2E:
        // el camino cifrado no conto esta entrega. Un `Message` que suma en
        // `deliveredCleartext` y NO suma en `delivered` vino por el legado.
        assertEquals(
            1,
            mundo.bob.core.legacyInbound.deliveredCleartext.get(),
            "el opt-in DEJA RASTRO observable, no solo un log",
        )
        assertEquals(
            0,
            mundo.bob.core.legacyInbound.rejectedCleartext.get(),
            "y no hay rechazo, porque el consumidor pidio este camino",
        )
        assertEquals(
            0,
            requireNotNull(mundo.bob.core.secureInbound).delivered.get(),
            "E2E conto cero: lo que llego NO es un mensaje descifrado",
        )
    }

    // ===================================================================
    // (c) Sin registro, el camino legado sigue igual que antes
    // ===================================================================

    @Test
    fun `sin registro el camino legado de recepcion sigue funcionando igual`() {
        val mundo = SecureWorld.open(secure = false)
        val recibidos = mutableListOf<Message>()
        mundo.bob.core.registerMessageHandler { recibidos.add(it) }

        // Lo que decide la regla es la INYECCION, no que el mundo tenga o no
        // sesiones: aqui hay sesion establecida y aun asi el nucleo no la
        // conoce, porque no se le inyecto el registro. Por eso el camino
        // legado esta abierto.
        assertEquals(1, mundo.bob.registry.size(), "el mundo si tiene sesion establecida con A")
        assertNull(
            mundo.bob.core.secureInbound,
            "pero el nucleo no recibio el registro: no descifra nada y su camino legado esta abierto",
        )
        assertTrue(
            mundo.bob.core.legacyInbound.legacyReceiveEnabled,
            "y el camino legado de recepcion esta abierto, como antes de este cambio",
        )

        mundo.bob.transport.deliverLegacyMessage(jsonEnClaro(mundo))

        // No-regresion: esto es EXACTAMENTE lo que hacia el slice 4 y lo que
        // hacia km-core antes de el. Si esta asercion se rompe, el cambio ha
        // roto un consumidor en vez de haber cerrado una puerta.
        assertEquals(1, recibidos.size, "sin registro el JSON llega igual que siempre")
        assertContentEquals(enClaro, recibidos.single().payload)
        assertEquals(mundo.alice.device.identityId, recibidos.single().from)
        assertEquals(
            MessageState.DELIVERED,
            mundo.bob.core.getMessageState(recibidos.single().messageId).getOrThrow(),
            "y queda DELIVERED en su registro",
        )
        assertEquals(0, mundo.bob.core.legacyInbound.rejectedCleartext.get(), "y no se rechaza nada")
        assertEquals(1, mundo.bob.core.legacyInbound.deliveredCleartext.get(), "sino que se cuenta")
    }

    /**
     * El opt-in se DECIDE AL CONSTRUIR, no se apaga en caliente.
     *
     * Sin esto, un valor que se pudiera cambiar despues seria una puerta
     * trasera: bastaria una linea en cualquier sitio para devolver el camino
     * legado a un nucleo que el consumidor cree cifrado. Aqui se fija que los dos
     * nucleos arrancan en estados distintos y que ninguno se ha movido todavia.
     */
    @Test
    fun `el opt-in se decide al construir el nucleo y no se puede cambiar despues`() {
        val mundo = SecureWorld.open()
        val cerrado = mundo.bob.core.legacyInbound
        val otro = SecureWorld.open(legacyCleartextReceive = true)
        val abierto = otro.bob.core.legacyInbound

        assertTrue(!cerrado.legacyReceiveEnabled, "el nucleo con registro arranca cerrado")
        assertTrue(abierto.legacyReceiveEnabled, "y el que pidio el opt-in arranca abierto")
        assertEquals(
            0,
            cerrado.deliveredCleartext.get() + cerrado.rejectedCleartext.get(),
            "y el cerrado no ha contado nada todavia",
        )
    }
}

package com.km.messaging

import com.km.codec.JsonDecoder
import com.km.model.Message
import com.km.model.MessageId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tarea 5.2 — el camino de RECEPCION cifrada, y sus tres finales.
 *
 * Un `SecureFrame` entrante tiene tres destinos posibles y solo uno es bueno:
 * a la aplicacion, a la basura por no autenticar, o a la basura por no ser de
 * nadie. Lo que no puede en ningun caso es caer en el decodificador JSON: ahi un
 * frame cifrado se pierde en silencio, porque `JsonDecoder` devuelve `null` y
 * `ReceiverImpl` traga ese `null` sin decir nada.
 *
 * Por eso las tres comprobaciones son de conteo, no de "llego algo o no": lo que
 * llega y lo que no llega se distinguen solo por el contador.
 */
class EncryptedReceivePathTest {

    private val contenido = "el texto que debe volver intacto desde A hasta B 5150".toByteArray()

    private fun enviadoPorA(mundo: SecureWorld.World): ByteArray {
        val capturados = mundo.alice.captureOutbound()
        val creado = mundo.alice.core
            .createMessage(mundo.bobRoot.identityId, contenido).getOrThrow()
        mundo.alice.core.sendMessage(creado).getOrThrow()
        return capturados.last()
    }

    @Test
    fun `un MESSAGE SecureFrame entrante llega al handler`() {
        val mundo = SecureWorld.open()
        val recibido = mutableListOf<Message>()
        mundo.bob.core.registerMessageHandler { recibido.add(it) }

        val creado = mundo.alice.core
            .createMessage(mundo.bobRoot.identityId, contenido).getOrThrow()
        val capturados = mundo.alice.captureOutbound()
        mundo.alice.core.sendMessage(creado).getOrThrow()

        mundo.bob.deliverInboundAs(mundo.bobToAlice, capturados.last())

        assertEquals(1, recibido.size, "un unico mensaje entregado")
        val m = recibido.single()
        assertContentEquals(contenido, m.payload, "el payload es el del SOBRE, no el del frame")
        assertEquals(creado.messageId, m.messageId, "el messageId es el del sobre")
        assertEquals(mundo.aliceRoot.identityId, m.from, "y el emisor es la RAIZ que el manager notifico")
        assertEquals(mundo.bobRoot.identityId, m.to, "el receptor es la identidad local del nucleo: la RAIZ")

        // Y la entrega es UNA: el puente no descifra otra vez lo que la sesion
        // ya descifro. Un segundo paso por el ratchet seria un replay.
        val puente = requireNotNull(mundo.bob.core.secureInbound)
        assertEquals(1, puente.delivered.get(), "exactamente una entrega, no una por handler")
        assertEquals(0, puente.rejectedFrames.get(), "y ninguna voz de rechazo")
        assertEquals(0, puente.unknownPeerFrames.get(), "ni de un par sin sesion")
    }

    @Test
    fun `un frame con el TAG alterado NO llega al handler`() {
        val mundo = SecureWorld.open()
        val recibido = mutableListOf<Message>()
        mundo.bob.core.registerMessageHandler { recibido.add(it) }

        val wire = enviadoPorA(mundo).copyOf()
        // Se altera UN byte del ciphertext, y es el ULTIMO: el del tag.
        wire[wire.size - 1] = (wire[wire.size - 1] + 1).toByte()

        // Sin excepcion: un rechazo del ratchet es un resultado, no una
        // excepcion. Si aqui saltara algo, el llamante del transporte tendria
        // que decidir que hacer con una excepcion de enrutado.
        mundo.bob.deliverInboundAs(mundo.bobToAlice, wire)

        assertTrue(recibido.isEmpty(), "un frame que no verifica no llega a la aplicacion")
        val puente = requireNotNull(mundo.bob.core.secureInbound)
        assertEquals(1, puente.rejectedFrames.get(), "y queda registrado como rechazo, no como silencio")
        assertEquals(0, puente.delivered.get())
    }

    @Test
    fun `un SecureFrame de un par sin sesion NO llega y NO se trata como JSON`() {
        val mundo = SecureWorld.openWithoutRecipientSession()
        assertEquals(1, mundo.alice.registry.size(), "A tiene sesion y puede emitir")
        assertEquals(0, mundo.bob.registry.size(), "B no tiene sesion con nadie")
        val recibido = mutableListOf<Message>()
        mundo.bob.core.registerMessageHandler { recibido.add(it) }

        val wire = enviadoPorA(mundo)

        // Y el hecho de que no se pueda tratar como JSON no es una suposicion:
        // se comprueba contra el mismo decodificador que usa el camino legado.
        assertNull(
            JsonDecoder().decodeMessage(wire).getOrNull(),
            "un SecureFrame no es un Message de km-core, y el camino legado lo sabe",
        )

        mundo.bob.deliverInboundAs(mundo.bobToAlice, wire)

        assertTrue(recibido.isEmpty(), "no llega nada")
        val puente = requireNotNull(mundo.bob.core.secureInbound)
        assertEquals(
            1,
            puente.unknownPeerFrames.get(),
            "se reconoce como SecureFrame y se descarta por no ser de nadie",
        )
        assertEquals(
            0,
            puente.legacyBytesOnPeerChannel.get(),
            "y no como bytes legacy: la distincion es la que decide no ir al JSON",
        )
        assertEquals(0, puente.delivered.get())
    }

    @Test
    fun `una sesion de REEMPLAZO del mismo par tambien se observa`() {
        // W-2. `SecureInbound` se suscribia UNA vez por par y se quedaba
        // apuntando a la primera sesion instalada. Cuando el par hacia un re-key,
        // los bytes de la sesion nueva llegaban a un handler que seguia
        // drenando la VIEJA: no llegaban al handler, no contaban como rechazo y
        // no disparaban ninguna excepcion. Perdida silenciosa, que es el modo de
        // fallo mas dificil de ver y el unico que este test persigue.
        val mundo = SecureWorld.open()
        val recibido = mutableListOf<Message>()
        mundo.bob.core.registerMessageHandler { recibido.add(it) }
        val puente = requireNotNull(mundo.bob.core.secureInbound)

        // 1. La sesion de B con A esta instalada y observada, y entrega.
        val antesDelRekey = "antes del re-key, por la sesion vieja 3311".toByteArray()
        mundo.bob.deliverInboundAs(
             mundo.bobToAlice,
            framePor(mundo, requireNotNull(mundo.sessionA), antesDelRekey),
        )
        assertEquals(1, recibido.size, "la sesion instalada entrega")
        assertContentEquals(antesDelRekey, recibido.last().payload)

        // 2. Llega una sesion NUEVA para el MISMO par. El registro sustituye.
        val (sesionA2, sesionB2) = mundo.rekey()
        mundo.bob.registry.install(sesionB2)
        assertEquals(1, mundo.bob.registry.size(), "el registro SUSTITUYE, no acumula")
        require(sesionB2 !== mundo.sessionB) { "la sesion de B es de verdad otra" }

        // 3. Y ahora A manda por la sesion nueva. Esto es lo que antes se perdia.
        val despuesDelRekey = "despues del re-key, por la sesion nueva 6644".toByteArray()
        mundo.bob.deliverInboundAs(
             mundo.bobToAlice,
            framePor(mundo, sesionA2, despuesDelRekey),
        )

        assertEquals(2, recibido.size, "la sesion de REEMPLAZO tambien se observa")
        assertContentEquals(despuesDelRekey, recibido.last().payload, "y entrega su carga")
        assertEquals(2, puente.delivered.get(), "dos entregas, una por sesion observada")
        assertEquals(
            0,
            puente.rejectedFrames.get(),
            "y ninguna voz de rechazo: la sesion vieja, ya sustituida, no cuenta como rechazo lo que SI descifro la nueva",
        )
    }

    /** Un frame REAL producido por [sesion], con un sobre nuevo de verdad. */
    private fun framePor(
        mundo: SecureWorld.World,
        sesion: SecureMessagingSession,
        carga: ByteArray,
    ): ByteArray {
        val sobre = MessageEnvelopeCodec.encode(
            MessageEnvelope.userMessage(MessageId(java.util.UUID.randomUUID()), carga),
        )
        return when (val r = sesion.send(sobre)) {
            is SendResult.Ok -> r.wire
            is SendResult.Rejected -> throw AssertionError("la sesion no cifro: ${r.reason}")
        }
    }

    @Test
    fun `el Message entrante lleva timestamp 0 porque el sobre v1 no lleva tiempo`() {
        // R4: `MessageEnvelope` v1 no tiene campo de tiempo. Se reconstruye con
        // 0 y se DECLARA; inventar la hora de ahora seria un dato falso con
        // apariencia de bueno.
        val mundo = SecureWorld.open()
        val recibido = mutableListOf<Message>()
        mundo.bob.core.registerMessageHandler { recibido.add(it) }

        mundo.bob.deliverInboundAs(
             mundo.bobToAlice,
            enviadoPorA(mundo),
        )
        assertEquals(0L, recibido.single().timestamp, "el sobre v1 no lleva tiempo")
    }
}
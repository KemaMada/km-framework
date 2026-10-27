package com.km.messaging

import com.km.codec.JsonEncoder
import com.km.frame.BinarySecureFrameCodec
import com.km.frame.FrameType
import com.km.frame.SecureFrameSpec
import com.km.model.Ack
import com.km.model.AckStatus
import com.km.model.FailureCode
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import com.km.protocol.AckTranscript
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tareas 3.1, 3.2, 3.3 y 4.1 — el camino de envio, en las cuatro ramas que tiene.
 *
 * Todo se mira SOBRE EL CABLE, no sobre el codigo. Un test que comprueba que se
 * llama a `protocol.encrypt` comprobaria una llamada; uno que mira los bytes que
 * salieron comprueba lo que el receptor va a ver.
 */
class SecureSendRoutingTest {

    private val contenido = "la carga util de este mensaje no es constante 8814".toByteArray()

    private fun mensajeDe(mundo: SecureWorld.World): Message =
        mundo.alice.core.createMessage(mundo.bobRoot.identityId, contenido).getOrThrow()

    private fun mensajesDe(mundo: SecureWorld.World, id: MessageId): List<Message> =
        mundo.alice.core.getConversation(mundo.bobRoot.identityId).getOrThrow()
            .filter { it.messageId == id }

    /**
     * Entrega a Alice un `ACK` de Bob, firmado de verdad por el dispositivo de
     * Bob y correlacionado por su id de RAIZ.
     *
     * ## POR QUE HACE FALTA UN `ACK` AUTENTICADO
     *
     * D6 mueve el borrado de la entrada persistida del momento del drenado al
     * momento del `ACK`: hasta que llega, el mensaje ya salio pero sigue
     * pendiente, porque un cierre del proceso antes de la confirmacion lo
     * perderia. Para observar ese cierre no vale cualquier byte: `AckManagerImpl`
     * solo dispara sus handlers tras verificar la firma, y un `ACK` inventado
     * dejaria el `OfflineQueue` lleno y el test en verde por el motivo
     * equivocado.
     */
    private fun entregarAck(
        mundo: SecureWorld.World,
        messageId: MessageId,
        status: AckStatus = AckStatus.DELIVERED,
    ) {
        val from = mundo.bobRoot.identityId
        val to = mundo.aliceRoot.identityId
        val timestamp = 1_790_000_000_000L
        val ack = Ack(
            from = from,
            to = to,
            originalMessageId = messageId,
            status = status,
            timestamp = timestamp,
            signature = SecureWorld.ed25519.sign(
                mundo.bob.device.signingKeyPair.privateKey,
                AckTranscript.build(from, to, messageId, status, timestamp),
            ).bytes,
        )
        mundo.alice.transport.deliverLegacyAck(JsonEncoder().encodeAck(ack).getOrThrow())
    }

    // ===================================================================
    // 3.1 — Con sesion, sale SecureFrame
    // ===================================================================

    @Test
    fun `con sesion el envio sale como SecureFrame`() {
        val mundo = SecureWorld.open()
        val capturados = mundo.alice.captureOutbound()

        val creado = mensajeDe(mundo)
        mundo.alice.core.sendMessage(creado).getOrThrow()

        assertEquals(0, mundo.alice.transport.sendCount, "nada sale por el camino legado")
        assertEquals(1, capturados.size, "un unico frame por el camino cifrado")

        val wire = capturados.single()
        assertEquals(
            SecureFrameSpec.VERSION.toByte(),
            wire[SecureFrameSpec.VERSION_OFFSET],
            "el wire empieza por la version de SecureFrame v1",
        )

        val frame = BinarySecureFrameCodec.decode(wire)
        assertEquals(FrameType.MESSAGE, frame.type, "es un frame de mensaje, no de establecimiento")
        assertEquals(
            SecureFrameSpec.VERSION,
            frame.version,
            "SecureFrame v1 CONGELADO: el layout no ha cambiado",
        )

        // 44 bytes de header + el sobre cifrado + el tag. El sobre es mas grande
        // que el payload porque lleva version, messageId y tipo dentro.
        val sobreEsperado = MessageEnvelopeCodec.encode(
            MessageEnvelope.userMessage(creado.messageId, contenido),
        )
        assertEquals(
            SecureFrameSpec.HEADER_LENGTH + sobreEsperado.size + SecureFrameSpec.TAG_LENGTH,
            wire.size,
            "44 + sobre + tag",
        )

        assertFalse(wire.containsBytesOf(contenido), "el payload NO viaja en claro por el cable")

        // Y el sobre que sale really lleva el messageId que acuno `createMessage`,
        // que es el unico sitio donde se acuña.
        assertEquals(
            creado.messageId,
            envelopeDe(mundo, wire).messageId,
            "el messageId del sobre es el que acuno createMessage",
        )
    }

    /** Entrega [wire] a B y devuelve el `Message` que llego a su handler. */
    private fun entregadoAMano(mundo: SecureWorld.World, wire: ByteArray): Message {
        var recibido: Message? = null
        mundo.bob.core.registerMessageHandler { recibido = it }
        mundo.bob.deliverInboundAs(mundo.bobToAlice, wire)
        return requireNotNull(recibido) { "B no recibio nada por el camino cifrado" }
    }

    /** Descifra el wire con la sesion de B y devuelve su sobre. */
    private fun envelopeDe(mundo: SecureWorld.World, wire: ByteArray): MessageEnvelope {
        val b = requireNotNull(mundo.sessionB)
        // `receive` sin pasar por `drainOutbound`, para no consumir el plaintext
        // que el puente de recepcion necesita.
        return when (val r = b.receive(wire)) {
            is ReceiveResult.Plaintext -> MessageEnvelopeCodec.decode(r.bytes)
            is ReceiveResult.Rejected -> throw AssertionError("B no descifro: ${r.reason}")
        }
    }

    // ===================================================================
    // 3.2 — Registro presente y SIN sesion: rechazo observable
    // ===================================================================

    @Test
    fun `sin sesion el envio se rechaza y no sale nada`() {
        val mundo = SecureWorld.openWithoutSession()
        assertEquals(0, mundo.alice.registry.size(), "el registro existe y esta vacio")

        val capturados = mundo.alice.captureOutbound()
        val creado = mensajeDe(mundo)
        val resultado = mundo.alice.core.sendMessage(creado).getOrThrow()

        // El orden de estas comprobaciones no es decorativo: lo primero que se
        // mira es si ALGO SALIO, y en claro por el camino legado. Un rechazo
        // que se degrada en "pero al menos que salga" es exactamente el defecto
        // que este test existe para cazar.
        assertEquals(0, mundo.alice.transport.sendCount, "NADA sale por Transport.send")
        assertTrue(mundo.alice.transport.sent.isEmpty(), "el camino legado esta vacio, byte a byte")
        assertTrue(capturados.isEmpty(), "tampoco sale un SecureFrame")
        assertTrue(
            mundo.alice.offlineQueue.peekAll().getOrThrow().isEmpty(),
            "un rechazo no se encola: se encolaria para caer en el camino legado al vaciar",
        )

        // Y despues el estado, observable por API PUBLICA, no por inspeccion interna.
        assertEquals(
            MessageState.FAILED,
            resultado.state,
            "sendMessage no puede reportar exito: no salio",
        )
        val guardado = mensajesDe(mundo, creado.messageId).single()
        assertEquals(MessageState.FAILED, guardado.state, "el mensaje queda FAILED")
        assertEquals(FailureCode.AUTH_FAILED, guardado.failureCode, "y con codigo de fallo")
    }

    // ===================================================================
    // 3.3 — El reintento NO re-cifra
    // ===================================================================

    @Test
    fun `el reintento reenvia el MISMO wire`() {
        val mundo = SecureWorld.open()
        val capturados = mundo.alice.captureOutbound()

        val creado = mensajeDe(mundo)
        mundo.alice.core.sendMessage(creado).getOrThrow()

        mundo.alice.core.retryMessage(creado.messageId).getOrThrow()

        assertEquals(2, capturados.size, "el reintento pone un segundo frame en el cable")
        val primero = capturados[0]
        val segundo = capturados[1]
        // Esta comparacion es la que mata el re-cifrado, y mata por BYTES: si el
        // reintento pasara por `protocol.encrypt`, el `N` avanza y la clave de
        // mensaje cambia, luego los dos wires no pueden coincidir. Por eso aqui
        // no hace falta una huella del estado del ratchet —que ademas es opaca—
        // cuando el byte que sale ahi no se ve.
        assertTrue(primero.contentEquals(segundo), "es el MISMO wire, byte a byte")
        assertEquals(
            BinarySecureFrameCodec.decode(primero).ratchetHeader.messageNumber,
            BinarySecureFrameCodec.decode(segundo).ratchetHeader.messageNumber,
            "y por tanto el MISMO N de la cadena del ratchet",
        )

        // Y ESTA es la que mata la fuga EN CLARO ADITIVA, que es un agujero
        // distinto: mandar el frame cifrado Y ADEMAS el JSON por
        // `Transport.send` deja los dos frames del cable IDENTICOS, asi que las
        // aserciones de arriba no lo ven. Solo lo ve el otro cable: el legado.
        assertEquals(
            0,
            mundo.alice.transport.sendCount,
            "NADA sale por Transport.send, ni en el envio ni en el reintento",
        )
        assertTrue(
            mundo.alice.transport.sent.isEmpty(),
            "el camino legado esta vacio, byte a byte",
        )
    }

    @Test
    fun `un mensaje que nunca salio no se re-cifra para el reintento`() {
        // El caso real: el mensaje se encolo porque no habia canal, y cuando
        // vuelve el reintento se dispara antes de que exista wire. La unica
        // alternativa honesta es decir que no; re-cifrar seria fabricar un frame
        // que el receptor no puede distinguir de un mensaje nuevo.
        val mundo = SecureWorld.open()
        mundo.alice.transport.goOffline()
        val creado = mensajeDe(mundo)
        mundo.alice.core.sendMessage(creado).getOrThrow()
        assertEquals(MessageState.QUEUED, mensajesDe(mundo, creado.messageId).single().state)

        val capturados = mundo.alice.captureOutbound()
        val resultado = mundo.alice.core.retryMessage(creado.messageId)

        assertTrue(resultado.isFailure, "sin wire cacheado no hay reintento posible")
        assertTrue(capturados.isEmpty(), "y no sale nada nuevo")

        // C-1, LA OTRA MITAD: la garantia "del reintento no sale nada en claro"
        // esta escrita para las DOS ramas de `retrySecure`, y aqui se esta
        // ejercitando la que NO tiene wire cacheado. Sin esto, las dos
        // aserciones de arriba solo miran el camino CIFRADO, y una fuga ADITIVA
        // por el LEGADO --que es justo la fuga que se cuela cuando no hay frame
        // que comparar-- pasaria inadvertida: `capturados` seguiria vacio
        // porque el JSON en claro sale por el otro cable.
        //
        // Los dos observadores son los REALES del backend falso
        // (`RecordingTransport`), no un contador inventado aqui: si
        // `Transport.send` no se ha llamado, no hay bytes, y eso es lo unico
        // que se puede afirmar sin mirar el codigo.
        assertEquals(
            0,
            mundo.alice.transport.sendCount,
            "y NADA sale por Transport.send en la rama SIN wire tampoco",
        )
        assertTrue(
            mundo.alice.transport.sent.isEmpty(),
            "el camino legado esta vacio, byte a byte, tambien sin wire",
        )
    }

    @Test
    fun `el reintento sin wire tampoco cae al legado con el par en linea`() {
        // El MISMO caso que el test de arriba, pero con el par EN LINEA. No es
        // duplicado: cubre la otra mitad del condicionamiento de una fuga de
        // este tipo, que es la mas natural de escribir. Un "plan B en claro"
        // razonable seria `if (transport.isOnline()) transport.send(json)`, y con
        // el par offline ese `if` ni se ejecuta: un test solo offline pasaría
        // sin ver la fuga, y por eso los dos existen.
        //
        // `connect()` y no `goOnline(peerId)` a proposito: `goOnline` dispara los
        // `onPeerOnline`, que drenan la cola y cachean el wire —con lo cual el
        // reintento pasaria por la rama CON wire y no mediria nada—. Aqui el
        // par vuelve sin evento de cola: el mensaje sigue encolado y sin wire.
        val mundo = SecureWorld.open()
        mundo.alice.transport.goOffline()
        val carga = "carga propia de la rama sin wire, de otra longitud 7741".toByteArray()
        val creado = mundo.alice.core.createMessage(mundo.bobRoot.identityId, carga).getOrThrow()
        mundo.alice.core.sendMessage(creado).getOrThrow()
        assertEquals(
            MessageState.QUEUED,
            mensajesDe(mundo, creado.messageId).single().state,
            "el mensaje quedo encolado y, por tanto, SIN wire cacheado",
        )

        mundo.alice.transport.connect().getOrThrow()
        val capturados = mundo.alice.captureOutbound()
        val resultado = mundo.alice.core.retryMessage(creado.messageId)

        assertTrue(resultado.isFailure, "sin wire cacheado no hay reintento posible")
        assertTrue(capturados.isEmpty(), "nada sale por el camino cifrado")
        assertEquals(
            0,
            mundo.alice.transport.sendCount,
            "y con el par EN LINEA tampoco se degrada a Transport.send",
        )
        assertTrue(
            mundo.alice.transport.sent.isEmpty(),
            "el camino legado sigue vacio, byte a byte: sin wire no hay plan B",
        )
    }

    // ===================================================================
    // 4.1 — onPeerOnline DESPIERTA la cabeza por el camino cifrado
    // ===================================================================

    @Test
    fun `la cola se drena cifrada`() {
        val mundo = SecureWorld.openWithoutSession()
        val capturados = mundo.alice.captureOutbound()

        // 1. Sin canal: el mensaje se encola. No se decide nada de cifrado
        //    porque no se emite nada.
        mundo.alice.transport.goOffline()
        val creado = mensajeDe(mundo)
        mundo.alice.core.sendMessage(creado).getOrThrow()
        assertEquals(
            1,
            mundo.alice.offlineQueue.peekAll().getOrThrow().size,
            "el mensaje quedo esperando, no se perdio",
        )
        assertTrue(capturados.isEmpty(), "y nada salio todavia")

        // 2. Se instala la sesion y vuelve el par.
        mundo.handshake()
        mundo.alice.transport.goOnline(mundo.bobRoot.identityId)

        assertEquals(1, capturados.size, "el vaciado de la cola sale cifrado")
        val wire = capturados.single()
        assertEquals(
            SecureFrameSpec.VERSION.toByte(),
            wire[SecureFrameSpec.VERSION_OFFSET],
            "y por el camino de SecureFrame, no por el JSON legado",
        )
        assertEquals(0, mundo.alice.transport.sendCount, "nada por Transport.send")
        assertEquals(
            creado.messageId,
            envelopeDe(mundo, wire).messageId,
            "el sobre lleva el messageId del mensaje ENCOLADO, no uno nuevo",
        )
        // D6: el drenado NO borra la entrada persistida. El mensaje YA salio,
        // pero mientras no haya `ACK` sigue pendiente: si el proceso muere aqui,
        // tiene que poder rehidratarse. Borrar al drenar era exactamente la
        // perdida silenciosa que este cambio cierra.
        assertEquals(
            1,
            mundo.alice.offlineQueue.peekAll().getOrThrow().size,
            "la cola sigue INTACTA tras drenar: el borrado se difiere al ACK",
        )
        assertEquals(
            MessageState.SENT,
            mensajesDe(mundo, creado.messageId).single().state,
            "y el mensaje consta como SENT, esperando confirmacion",
        )

        // 3. El `ACK` autenticado cierra el ciclo: ahora si se borra.
        entregarAck(mundo, creado.messageId)
        assertTrue(
            mundo.alice.offlineQueue.peekAll().getOrThrow().isEmpty(),
            "y la cola queda vacia tras el ACK, que es la prueba de entrega",
        )
    }

    // ===================================================================
    // W-A — EL DRENADO SIN SESION, QUE ES LA MISMA FUGA POR OTRA RAMA
    // ===================================================================

    /**
     * El vaciado de la cola NO se degrada al camino legado cuando el registro
     * esta presente y no hay sesion con el par.
     *
     * ## POR QUE ESTE TEST Y NO OTRO
     *
     * El de arriba fija la rama feliz: hay sesion, el drenado sale cifrado y el
     * legado queda mudo. Este fija la rama **triste**, y es la unica forma de
     * hacerlo porque son ramas DISTINTAS de la misma llamada. `la cola se drena
     * cifrada` llega al final de `send` con exito; aqui llega al final con
     * `NO_SESSION`. Un drenado en claro como plan B —`if (send falla)
     * transport.send(json)`— solo se ve en la segunda: en la primera nunca llega
     * a la rama del fallo, y la suite entera sigue verde con la fuga puesta.
     * Ese es exactamente el agujero que dejo abierta la mutacion M-NEW2.
     *
     * ## LO QUE AFIRMA, Y EN QUE ORDEN
     *
     * Primero "nada sale", despues "la cola sigue entera", y solo al final el
     * estado del registro. El orden no es estetico: si el drenado se degradara,
     * lo primero que habria que notar es que el JSON del mensaje encolado esta
     * ya en la red, y eso es lo que se afirma primero. El estado del store va al
     * final porque es consecuencia, no causa: describe el efecto de no haber
     * salido, no el hecho de no haber salido.
     *
     * Los dos observadores del camino legado son los REALES del backend falso
     * ([SecureWorld.RecordingTransport]), no un contador inventado aqui. Y el
     * payload NO es constante ni del mismo tamaño que el `messageId`: si algo
     * saliera en claro seria por bytes, y no por una coincidencia de longitud.
     */
    @Test
    fun `el drenado sin sesion no degrada la cola al camino legado`() {
        val mundo = SecureWorld.openWithoutSession()
        assertEquals(
            0,
            mundo.alice.registry.size(),
            "el REGISTRO esta presente y vacio: esta es la rama del rechazo, no la del cifrado",
        )
        val capturados = mundo.alice.captureOutbound()

        // 1. Sin canal: se encola y NO se decide nada de cifrado, porque no se
        //    emite nada. El mensaje queda esperando con su identidad intacta.
        mundo.alice.transport.goOffline()
        val carga = "contenido del drenado sin sesion, de longitud propia 6607".toByteArray()
        val creado = mundo.alice.core.createMessage(mundo.bobRoot.identityId, carga).getOrThrow()
        mundo.alice.core.sendMessage(creado).getOrThrow()
        assertEquals(
            MessageState.QUEUED,
            mensajesDe(mundo, creado.messageId).single().state,
            "el mensaje quedo ENCOLADO, que es lo que el drenado va a encontrar",
        )
        assertEquals(
            1,
            mundo.alice.offlineQueue.peekAll().getOrThrow().size,
            "y la cola lo tiene, no se perdio",
        )

        // 2. Vuelve el par. Aqui es donde el drenado decide, y por eso es aqui
        //    donde puede filtrarse en claro.
        mundo.alice.transport.goOnline(mundo.bobRoot.identityId)

        // (a) LO PRIMERO: NADA sale por el camino legado. Ni una carga, ni una
        //     entrega. Con el registro presente, "no hay sesion" es un RECHAZO y
        //     un rechazo no tiene plan B.
        assertEquals(
            0,
            mundo.alice.transport.sendCount,
            "el drenado NO llama a Transport.send aunque el envio se haya rechazado",
        )
        assertTrue(
            mundo.alice.transport.sent.isEmpty(),
            "el camino legado esta vacio, byte a byte: no hay ni un JSON en la red",
        )
        // Ni por el otro cable tampoco: la fuga de este slice es ADITIVA en el
        // sentido de que "sin frame" y "en claro" son el mismo silencio si solo
        // se mira la pila de par.
        assertTrue(capturados.isEmpty(), "tampoco sale un SecureFrame: no sale nada de ninguna manera")

        // (b) DESPUES: la COLA ya no lo tiene, porque un terminal no espera. La
        //     evidencia durable es la FILA `FAILED`, no la entrada de cola: el
        //     tombstone es lo que el usuario puede ver y reenviar (R13). Antes
        //     esta entrada se conservaba y `expireHead` la borraba: dos caminos
        //     con dos respuestas para el mismo hecho.
        val cola = mundo.alice.offlineQueue.peekAll().getOrThrow()
        assertEquals(0, cola.size, "un rechazo terminal saca el mensaje de la cola de espera")

        // (c) Y POR ULTIMO el estado, que es el rechazo OBSERVABLE por API
        //     publica. Sin esto el drenado fallido seria indistinguible de uno
        //     que no llego a intentarlo.
        val guardado = mensajesDe(mundo, creado.messageId).single()
        assertEquals(
            MessageState.FAILED,
            guardado.state,
            "el drenado fallido deja el mensaje FAILED, no SENT ni QUEUED",
        )
        assertEquals(
            FailureCode.AUTH_FAILED,
            guardado.failureCode,
            "y con codigo de fallo, que es lo que ve el consumidor",
        )
    }

    // ===================================================================
    // El camino cifrado, extremo a extremo
    // ===================================================================

    @Test
    fun `B recibe el mensaje con el texto original`() {
        val mundo = SecureWorld.open()
        val capturados = mundo.alice.captureOutbound()

        val creado = mensajeDe(mundo)
        mundo.alice.core.sendMessage(creado).getOrThrow()

        val recibido = entregadoAMano(mundo, capturados.single())
        assertContentEquals(contenido, recibido.payload, "el payload llega entero")
        assertEquals(creado.messageId, recibido.messageId, "con el messageId del sobre")
        assertEquals(mundo.aliceRoot.identityId, recibido.from, "y desde quien lo envio: la RAIZ, no el dispositivo")
        assertEquals(mundo.bobRoot.identityId, recibido.to, "y el receptor es la RAIZ, la que la sesion indexa")
    }
}

package com.km.daemon

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.km.auth.Base64Url
import com.km.auth.TranscriptBuilder
import com.km.codec.JsonDecoder
import com.km.codec.JsonEncoder
import com.km.codec.ValidatorImpl
import com.km.crypto.Ed25519
import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.daemon.config.DaemonConfig
import com.km.daemon.server.AckRoute
import com.km.daemon.server.AuthAdapter
import com.km.daemon.server.JsonWireCodec
import com.km.daemon.server.MessageRouter
import com.km.daemon.server.RelayWebSocket
import com.km.daemon.util.Base64Standard
import com.km.identity.KmIds
import com.km.model.Ack
import com.km.model.AckStatus
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.node.RelayServer
import com.km.node.RelayServiceImpl
import com.km.protocol.AckManagerImpl
import com.km.protocol.ConnectionState
import com.km.protocol.OrderingManagerImpl
import com.km.protocol.ReceiverImpl
import com.km.protocol.Transport
import com.km.storage.InMemoryDuplicateStore
import com.km.storage.InMemoryMessageStore
import com.km.storage.InMemoryRelayStore
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.enums.Opcode
import org.java_websocket.enums.ReadyState
import org.java_websocket.framing.Framedata
import org.java_websocket.protocols.IProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.Marker
import org.slf4j.event.Level
import org.slf4j.helpers.AbstractLogger
import org.slf4j.helpers.MessageFormatter
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import javax.net.ssl.SSLSession

/**
 * Checkpoint B.1 — el relé mueve `ACK` sin entenderlo (§11.1 ya enmendada).
 *
 * Lo que estos tests miden NO es que el relé reenvíe un `ACK`, sino que lo
 * reenvíe **sin tocarlo**. La diferencia importa: un relé que reenvía el `ACK`
 * pero le reescribe `timestamp`, le normaliza el orden de las claves o le
 * corrige el `from` sigue "moviendo ACKs" y está roto, porque la firma del `ACK`
 * cubre justo esos campos. Por eso el listón es la igualdad de `String`, y por eso
 * el canario usa un JSON que ninguna serialización canónica reproduciría.
 *
 * EL ENTRAMADO ES REAL, NO UN DOBLE DE LA ESTRUCTURA QUE SE PRUEBA
 *
 * Las dos conexiones se autentican pasando por el camino de verdad del daemon:
 * `AUTH_REQUEST` -> `AUTH_CHALLENGE` -> `AUTH_RESPONSE` -> `AUTH_OK`, con la firma
 * de Ed25519 sobre el transcript de 152 B construida con `TranscriptBuilder` y
 * verificada por `AuthVerifier` a través de `RelayServer.authenticate`. Eso
 * importa por dos razones: `establishSession` registra el handler de datos en
 * `RelayServer` —sin él no hay camino de `MESSAGE` que probar— y `peerId` queda
 * ligado a una identidad verificada, que es contra la que se mide el
 * anti-spoofing. Un `peerId` que el test se inventara probaría el test, no el
 * código.
 *
 * Lo único doble es el socket: `WebSocket` es una INTERFAZ en Java-WebSocket
 * 1.5.6, así que `RecordingSocket` la implementa y registra lo que se le envía.
 * Solo dos métodos están vivos (`send(String)` e `isOpen()`); el resto lanza, a
 * propósito: un método que el daemon no usa y que de pronto empiece a usar es un
 * cambio de comportamiento que este doble debe hacer fallar, no absorber.
 *
 * LO QUE ESTOS TESTS NO DICEN
 *
 * Que el `ACK` llegue al destinatario no basta para que el mensaje quede
 * confirmado: hace falta que su `originalMessageId` corresponda a un mensaje que
 * el emisor tenga guardado. Esa correlación es un asunto del camino de `MESSAGE`
 * (§6.2: el relé acuña un `messageId` nuevo en cada lado, así que el id que el
 * receptor devuelve en el `ACK` no es el que el emisor guardó), no de este camino
 * de reenvío. Aquí se comprueba lo que sí es de este camino: que el `ACK` viaja
 * entero, sin reescribir y sin amplificarse.
 */
class AckForwardingTest {

    private val mapper = ObjectMapper()
    private val ed25519: Ed25519 = Ed25519Impl()

    private val relayKp = ed25519.generateKeyPair()
    private val aliceKp = ed25519.generateKeyPair()
    private val bobKp = ed25519.generateKeyPair()
    private val malloryKp = ed25519.generateKeyPair()

    private val relayId = identityOf(relayKp)
    private val aliceId = identityOf(aliceKp)
    private val bobId = identityOf(bobKp)
    private val malloryId = identityOf(malloryKp)

    /** Logger del router: este doble, no el global, para poder evidenciar el LOG. */
    private val log = RecordingLogger()

    private val relayServer = RelayServer(
        relayKeyPair = relayKp,
        ed25519 = ed25519,
        relayService = RelayServiceImpl(relayStore = InMemoryRelayStore(now = { NOW }), now = { NOW }),
        relayIdentityId = relayId,
        now = { NOW },
    )

    private val codec = JsonWireCodec()
    private val router = MessageRouter(
        relayServer = relayServer,
        codec = codec,
        authAdapter = AuthAdapter(relayServer, relayId, codec),
        relayIdentityId = relayId,
        config = DaemonConfig(),
        logger = log,
        now = { NOW },
    )

    // =====================================================================
    // T1 — identidad de bytes
    // =====================================================================

    /**
     * T1: lo que entra es la MISMA `String` que sale.
     *
     * `assertEquals` sobre `String` es comparación de caracteres: si el relé
     * re-serializara, cambiara el orden de las claves o normalizara un escape,
     * esta aserción cae. No se admite "equivalencia": un `ACK` firmado no tiene
     * dos formas de escribir lo mismo.
     *
     * Los tres casos no son el mismo caso tres veces, y los tres son legales en
     * este camino: un `ACK` con un UUID de verdad; uno cuyo `originalMessageId` no
     * lo acepta ningún `UUID.fromString`; y uno cuya `signature` no son los 64
     * bytes que exigiría `ValidatorImpl.validateAck`. Los tres deben salir
     * idénticos, porque ninguno de esos tres hechos es asunto del relé: si los
     * mirara, el tercero no habría llegado nunca, y el primero tampoco.
     */
    @Test
    fun `byteIdentity - el ACK sale exactamente la String que entro`() {
        val bob = connectAs(bobKp)
        val alice = connectAs(aliceKp)
        bob.socket.sent.clear()

        val frames = listOf(
            ackWireFor(bobKp, to = aliceId, original = UUID.randomUUID()),
            ackWireFor(bobKp, to = aliceId, original = UUID.randomUUID(), originalLiteral = "no-soy-un-uuid"),
            ackWireFor(bobKp, to = aliceId, original = UUID.randomUUID(), signatureOverride = ""),
        )

        for (frame in frames) bob.send(frame)

        assertEquals(frames, alice.socket.sent, "cada ACK debe salir byte a byte como entró (§11.1)")
        assertTrue(alice.socket.sent.all { typeOf(it) == "ACK" }, "del lado de alice solo puede salir 'ACK'")
        assertTrue(bob.socket.sent.isEmpty(), "el emisor no recibe eco de su propio ACK")
    }

    // =====================================================================
    // T2 — integridad de la firma
    // =====================================================================

    /**
     * T2: mutar un campo firmado no lo salva ni el relé ni el receptor.
     *
     * Son dos mitades, y son responsabilidades distintas: con una sola, el test
     * no probaría lo que dice probar.
     *
     * 1. **El relé no repara.** La mutación llega al destinatario tal cual, con
     *    el `timestamp` / `originalMessageId` / `signature` cambiados. Si el relé
     *    normalizara, el receptor vería un `ACK` íntegro y no sabría que alguien
     *    lo tocó por el camino. El `from` es la excepción y lo dice por qué: un
     *    `from` que no es el peer autenticado lo descarta el propio relé (§11.1,
     *    anti-spoofing de transporte), porque corregirlo fabricaría un `ACK`
     *    distinto del firmado.
     * 2. **El receptor rechaza.** `AckManagerImpl` —el verificador real de
     *    km-core, el mismo que corre en Android y en el desktop— acepta el `ACK`
     *    intacto y no acepta ninguna de las cuatro mutaciones.
     *
     * El `from` mutado tiene dos capas de defensa en el receptor, y el test las
     * separa: con el mensaje guardado dirigido a Bob salta la comparación
     * `ack.from == message.to`; para demostrar que `from` está DENTRO del
     * transcript firmado se repite con el mensaje dirigido a Mallory, de modo que
     * la única defensa posible sea la firma.
     */
    @Test
    fun `signatureIntegrity - mutar un campo firmado no lo salva ni el rele ni el receptor`() {
        val bob = connectAs(bobKp)
        val alice = connectAs(aliceKp)
        bob.socket.sent.clear()

        val original = signedAck(bobKp, to = aliceId, original = UUID.randomUUID())
        val wire = ackWire(original)

        // CONTROL NEGATIVO DEL VERIFICADOR. Un detector que rechaza todo pasa los
        // cuatro casos sin distinguir nada. Este demuestra que el verificador
        // acepta lo que tiene que aceptar: sin esta línea, "no entregado" no
        // significaría nada.
        val store = InMemoryMessageStore()
        store.insert(storedMessage(original.originalMessageId, to = bobId)).getOrThrow()
        val accepted = mutableListOf<Ack>()
        val ackManager = AckManagerImpl(store, ed25519) { id -> publicKeyOf(id) }
        ackManager.registerAckHandler { accepted += it }

        bob.send(wire)
        assertEquals(listOf(wire), alice.socket.sent, "control negativo: el ACK intacto también debe pasar por el relé")
        ackManager.onAckReceived(original).getOrThrow()
        assertEquals(1, accepted.size, "el ACK intacto DEBE aceptarse; si no, el detector no detecta nada")

        val mutaciones = listOf(
            "timestamp" to ackWire(original.copy(timestamp = original.timestamp + 1L)),
            "originalMessageId" to ackWire(original.copy(originalMessageId = MessageId(UUID.randomUUID()))),
            "signature" to ackWire(
                original.copy(signature = original.signature.copyOf().also { it[0] = (it[0] + 1).toByte() }),
            ),
        )
        val suplantado = ackWire(original.copy(from = malloryId))

        for ((campo, frame) in mutaciones) {
            val antes = accepted.size
            ackManager.onAckReceived(decodeAck(frame)).getOrThrow()
            assertEquals(antes, accepted.size, "mutar '$campo' no puede dejar pasar el ACK")
        }
        ackManager.onAckReceived(decodeAck(suplantado)).getOrThrow()
        assertEquals(1, accepted.size, "un 'from' que no es el destinatario del mensaje tampoco")

        // Subcaso `from`: el mensaje guardado va a MALLORY, así que
        // `ack.from == message.to` se cumple y la única defensa posible es la
        // firma. Si aquí pasara, significaría que `from` no está firmado.
        val spoofStore = InMemoryMessageStore()
        spoofStore.insert(storedMessage(original.originalMessageId, to = malloryId)).getOrThrow()
        val spoofAccepted = mutableListOf<Ack>()
        val spoofManager = AckManagerImpl(spoofStore, ed25519) { id -> publicKeyOf(id) }
        spoofManager.registerAckHandler { spoofAccepted += it }
        spoofManager.onAckReceived(decodeAck(suplantado)).getOrThrow()
        assertEquals(0, spoofAccepted.size, "'from' está firmado: con el mensaje dirigido a la identidad nueva solo la firma puede rechazarlo")

        // Y la mitad del relé: cada mutación de APLICACIÓN viaja tal cual. La
        // de `from` no viaja, y el motivo es el control de transporte, no la
        // semántica del `ACK`.
        for ((campo, frame) in mutaciones) {
            alice.socket.sent.clear()
            bob.send(frame)
            assertEquals(listOf(frame), alice.socket.sent, "el relé debe reenviar '$campo' mutado tal cual")
        }
        alice.socket.sent.clear()
        bob.send(suplantado)
        assertTrue(alice.socket.sent.isEmpty(), "un 'from' suplantado lo para el relé, no el receptor (§11.1)")
    }

    // =====================================================================
    // T3 — anti-spoofing
    // =====================================================================

    /**
     * T3: `from` distinto del peer autenticado -> DESCARTE y LOG, sin corregir.
     *
     * "Sin corregir" es la parte que se suele olvidar: un relé que reescribe
     * `from` con el valor que él cree verdad produce un `ACK` DISTINTO del que
     * se firmó, y el receptor no tiene forma de notarlo. Descartar es lo único
     * honesto que se puede hacer con algo que no se sabe si es auténtico.
     *
     * El control negativo está dentro del propio test: el mismo frame con el
     * `from` correcto SÍ llega. Sin esa mitad, el test pasaría igual con un relé
     * que tirase todos los `ACK` —que es exactamente el comportamiento que había
     * antes de esta enmienda.
     */
    @Test
    fun `antiSpoof - un from que no es el peer autenticado se descarta y se registra`() {
        val bob = connectAs(bobKp)
        val alice = connectAs(aliceKp)
        bob.socket.sent.clear()

        val suplantado = ackWireFor(bobKp, to = aliceId, original = UUID.randomUUID(), fromOverride = malloryId)
        bob.send(suplantado)

        assertTrue(alice.socket.sent.isEmpty(), "un ACK suplantado no puede llegar al destinatario")
        assertTrue(log.contains("anti-spoofing"), "el descarte tiene que quedar registrado")
        assertTrue(
            log.entriesAt(Level.WARN).any { it.contains(malloryId.value.take(8)) },
            "el log dice QUIEN se suplantó, no solo que hubo un descarte",
        )

        // Control negativo: el mismo frame con `from` correcto sí pasa.
        val honesto = ackWireFor(bobKp, to = aliceId, original = UUID.randomUUID())
        bob.send(honesto)
        assertEquals(listOf(honesto), alice.socket.sent)
    }

    // =====================================================================
    // S3 / R1.5 — destino ausente
    // =====================================================================

    /**
     * S3: `to` sin conexión -> se descarta, y SIN `RELAY_ERROR`.
     *
     * El `RELAY_ERROR` se importa porque es tentador: el camino de `MESSAGE` lo
     * emite cuando el destinatario no está (`deliverData` -> `PEER_NOT_ONLINE`).
     * En `ACK` sería una MENTIRA: el relé no sabe si ese `ACK` debía llegar, así
     * que no puede afirmar que se perdió. El emisor vería un error donde no lo
     * hay, y eso es peor que un silencio.
     */
    @Test
    fun `destino ausente - se descarta y se registra, sin RELAY_ERROR`() {
        val bob = connectAs(bobKp)

        bob.send(ackWireFor(bobKp, to = aliceId, original = UUID.randomUUID()))

        assertTrue(bob.socket.sent.isEmpty(), "sin RELAY_ERROR y sin eco: el emisor no se entera de nada")
        assertTrue(log.contains("no conectado"), "el descarte por destino ausente tiene que quedar registrado")
    }

    /**
     * Un `ACK` de una conexión sin `AUTH_OK` se descarta, y la conexión
     * sobrevive (§7.1): `peerId` es `null`, así que no hay contra quién medir el
     * anti-spoofing ni a quién atribuir el frame.
     */
    @Test
    fun `ACK antes de AUTH_OK se descarta y no tumba la conexion`() {
        val socket = RecordingSocket()
        val anonimo = RelayWebSocket(socket, log)
        router.register(socket, anonimo)

        router.onText(anonimo, ackWireFor(bobKp, to = aliceId, original = UUID.randomUUID()))

        assertTrue(socket.sent.isEmpty())
        assertTrue(log.contains("no autenticada"))
    }

    /**
     * Un `ACK` sin `to`, o con un `to` que no son 64 hex, se descarta. Es el
     * mismo criterio de forma que aplica el codec al resto del dialecto (§7.1),
     * no un caso especial del `ACK`: sin `to` no hay a dónde mandarlo, y
     * adivinarlo sería inventar semántica.
     */
    @Test
    fun `ACK sin to utilizable se descarta`() {
        val bob = connectAs(bobKp)
        val alice = connectAs(aliceKp)
        bob.socket.sent.clear()

        val sinTo = """{"type":"ACK","from":"${bobId.value}","originalMessageId":"x","status":"DELIVERED","timestamp":1,"signature":"AA=="}"""
        val toMalo = """{"type":"ACK","from":"${bobId.value}","to":"no-es-hex","originalMessageId":"x","status":"DELIVERED","timestamp":1,"signature":"AA=="}"""

        bob.send(sinTo)
        bob.send(toMalo)

        assertTrue(alice.socket.sent.isEmpty(), "sin destino no hay entrega, y el frame no se repara")
        assertTrue(log.contains("'from' o 'to'"))
    }

    // =====================================================================
    // T4 — no amplificación
    // =====================================================================

    /**
     * T4: `MESSAGE` -> `ACK` -> relé -> `ACK`, y de ahí nada.
     *
     * El ciclo de amplificación es el motivo por el que `ACK` existe como tipo
     * propio: un `ACK` que saliera por `MESSAGE` llegaría al otro extremo como un
     * mensaje entrante más, y ese mensaje entrante generaría OTRO `ACK`. Aquí se
     * recorre el ciclo entero con piezas de verdad —`ReceiverImpl` de km-core
     * firma el `ACK`, el router lo mueve— y se comprueba que al otro lado no
     * aparece ningún `MESSAGE`, no se emite un segundo `ACK` y el emisor del
     * `MESSAGE` no recibe ningún eco.
     */
    @Test
    fun `noAmplification - MESSAGE, ACK, rele, ACK, y nada mas`() {
        val alice = connectAs(aliceKp)
        val bob = connectAs(bobKp)
        alice.socket.sent.clear()
        bob.socket.sent.clear()

        // Los 256 valores de byte y una longitud que no sea múltiplo de 3: una
        // carga de ceros serenity idempotente en casi cualquier codificacion.
        val carga = ByteArray(96) { (it - 1).toByte() }
        val secureFrame = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8) + carga

        // 1. alice manda el MESSAGE. El rele lo mueve: Base64 -> bytes -> Base64.
        alice.send(messageWire(aliceId, bobId, secureFrame))
        assertEquals(1, bob.socket.sent.size, "el MESSAGE debe llegar una vez")
        val entrante = mapper.readTree(bob.socket.sent.single())
        assertEquals("MESSAGE", entrante["type"].asText())
        val payload = Base64Standard.decode(entrante["data"].asText()).getOrThrow()
        assertTrue(secureFrame.contentEquals(payload), "§6.2: la carga viaja byte a byte")

        // 2. El receptor de km-core procesa el mensaje y EMITE un ACK firmado.
        var emitido: Ack? = null
        val receptor = ReceiverImpl(
            messageStore = InMemoryMessageStore(),
            duplicateStore = InMemoryDuplicateStore(),
            decoder = JsonDecoder(),
            encoder = JsonEncoder(),
            validator = ValidatorImpl(),
            ed25519 = ed25519,
            transport = UnusedTransport,
            orderingManager = OrderingManagerImpl(),
            localIdentity = bobId,
            localPrivateKey = bobKp.privateKey,
            ackEmitter = { emitido = it; Result.success(Unit) },
        )
        receptor.onMessageReceived(
            Message(
                messageId = MessageId(UUID.fromString(entrante["messageId"].asText())),
                from = aliceId,
                to = bobId,
                payload = payload,
                timestamp = entrante["timestamp"].asLong(),
            ),
        ).getOrThrow()
        val ack = requireNotNull(emitido) { "el receptor debe emitir un ACK" }
        assertEquals(bobId, ack.from)
        assertEquals(aliceId, ack.to)

        // 3. El ACK viaja por su propio frame y el rele lo mueve verbatim.
        val frame = ackWire(ack)
        bob.send(frame)

        assertEquals(listOf(frame), alice.socket.sent, "el ACK debe llegar una vez, intacto")
        assertEquals("ACK", typeOf(alice.socket.sent.single()))

        // 4. Y no hay segunda vuelta: ni un MESSAGE, ni otro ACK, ni nada.
        assertEquals(0, alice.socket.sent.count { typeOf(it) == "MESSAGE" }, "un ACK no genera un MESSAGE")
        assertEquals(1, alice.socket.sent.size, "un ACK no genera un eco de si mismo")
        assertTrue(bob.socket.sent.none { typeOf(it) == "ACK" }, "el rele no devuelve el ACK a su emisor")
    }

    // =====================================================================
    // T5 — sin semántica
    // =====================================================================

    /**
     * T5: el relé no usa `originalMessageId`, y eso es observable.
     *
     * Un `originalMessageId` que no es un UUID no lo puede entender nadie con
     * semántica: `km-desktop`'s `decodeAck` hace `UUID.fromString` y devuelve
     * `null`. Un relé con semántica los rechazaría; uno opaco los reenvía. Lo
     * mismo con un `status` que no es un `AckStatus`.
     *
     * El control negativo está al final: un `ACK` con un UUID de verdad llega
     * igual. Sin él, el test pasaría con un relé que tirase todos los `ACK`.
     */
    @Test
    fun `noSemantics - un originalMessageId imposible no cambia lo que hace el rele`() {
        val bob = connectAs(bobKp)
        val alice = connectAs(aliceKp)
        bob.socket.sent.clear()

        val imposibles = listOf(
            "" to "DELIVERED",
            "no-soy-un-uuid" to "DELIVERED",
            "00000000-0000-0000-0000-000000000000" to "ENTREGADO_PERO_QUIZÁ",
            "x".repeat(4096) to "DELIVERED",
        )

        val frames = imposibles.map { (original, status) ->
            ackWireFor(bobKp, to = aliceId, original = UUID.randomUUID(), originalLiteral = original, status = status)
        }
        for (frame in frames) bob.send(frame)

        assertEquals(frames, alice.socket.sent, "el relé no sabe qué es un originalMessageId: reenvía lo que le den")

        // Control negativo: con los mismos campos pero valores que sí existen en
        // km-core, el resultado es idéntico. Si el reenvío dependiera de ellos,
        // estas dos filas se separarían.
        val conSignificado = ackWireFor(bobKp, to = aliceId, original = UUID.randomUUID())
        alice.socket.sent.clear()
        bob.send(conSignificado)
        assertEquals(listOf(conSignificado), alice.socket.sent)
    }

    /**
     * `parseAckRoute` no tiene por dónde devolver `originalMessageId`, `status`,
     * `timestamp` ni `signature`: no están en el tipo.
     *
     * Esto no es una prueba de comportamiento sino de forma, pero es la que ata
     * el código a la regla sin depender de que alguien añada un campo a `ACK` y
     * se acuerde de actualizar el test. Si alguien necesitara el
     * `originalMessageId` para reenviar el `ACK`, tendría que añadirlo a
     * [AckRoute], y el primer sitio donde se vería es la lista de prohibiciones
     * del spec.
     */
    @Test
    fun `noSemantics - el codec solo devuelve from y to`() {
        val node = mapper.readTree(ackWire(signedAck(bobKp, to = aliceId, original = UUID.randomUUID())))
        val route = requireNotNull(codec.parseAckRoute(node)) { "un ACK bien formado se puede rutear" }

        assertEquals(bobId, route.from)
        assertEquals(aliceId, route.to)

        val propiedades = AckRoute::class.java.declaredFields.map { it.name }.sorted()
        assertEquals(listOf("from", "to"), propiedades, "§11.1: los campos de aplicación no entran en el tipo de ruta")
    }

    // =====================================================================
    // T6 — canario
    // =====================================================================

    /**
     * T6: un `ACK` con whitespace y orden de claves imposibles sale igual.
     *
     * El caso normal (`byteIdentity`) lo cubriría un parse->serialize que
     * respetase el orden de inserción, así que este canario está construido para
     * que CUALQUIER normalización lo rompa: claves en orden inverso al de
     * `JsonEncoder`, saltos de línea, tabuladores y un `\r\n` donde un
     * serializador pone nada, y un campo desconocido que no está en §11.2.
     *
     * La aserción de la mitad de abajo es la que le da dientes: muestra qué
     * destruiría exactamente un parse->serialize, y comprueba que el canario se
     * distingue de esa forma. Sin ella, el test no separaría un reenvío verbatim
     * de un round-trip y pasaría sin estar probando nada.
     *
     * MEDIDO, NO SUPUESTO: este canario es el ÚNICO detector de la
     * normalización de FORMA. Con la mutación `it.send(frame.toString())` —que
     * es un parse->serialize de manual, con el mismo orden de claves y sin
     * tocar ningún valor— el resto de la suite pasa entero, `byteIdentity`
     * incluido, porque las tramas que usa son compactas y ya están
     * normalizadas. Solo este test cae. Por eso existe y por eso no es
     * redundante.
     */
    @Test
    fun `canary - whitespace, orden y escapes sobreviven exactamente`() {
        val bob = connectAs(bobKp)
        val alice = connectAs(aliceKp)
        bob.socket.sent.clear()

        val firma = Base64Standard.encode(malloryKp.publicKey)
        val acentos = "áéíóú"
        val canario = buildString {
            append("{\n")
            append("\t\"signature\" : \"$firma\" ,\n")
            append("  \"timestamp\": $NOW,\n")
            append("\"status\":\t\"DELIVERED\",\r\n")
            append("  \"originalMessageId\"  :  \"${UUID.randomUUID()}\",\n")
            append("\"to\"   :  \"${aliceId.value}\",\n")
            append("\"from\":\"${bobId.value}\",\n")
            append("  \"nota\" :  \"$acentos \\\\ \\\"\" ,\n")
            append("  \"type\":\"ACK\"\n}")
        }

        // CONTROL NEGATIVO DEL DETECTOR: la forma que produciría un
        // parse->serialize. Tiene que ser DISTINTA del canario, y las diferencias
        // tienen que ser justo las que este test afirma conservar.
        val normalizado = mapper.writeValueAsString(mapper.readTree(canario))
        assertNotEquals(canario, normalizado, "el canario tiene que distinguir verbatim de round-trip")
        assertTrue(!normalizado.contains("\t"), "el round-trip se come los tabuladores")
        assertTrue(!normalizado.contains("\r"), "el round-trip se come el CRLF")
        assertTrue(!normalizado.contains("  "), "el round-trip se come el padding de espacios")
        assertTrue(normalizado.contains("\"type\":\"ACK\""), "y aun así sigue siendo un ACK: la diferencia es de forma, no de sentido")

        bob.send(canario)

        val recibido = requireNotNull(alice.socket.sent.singleOrNull()) { "el canario debe llegar una vez" }
        assertEquals(canario, recibido, "el canario debe salir carácter a carácter idéntico")
        assertEquals(canario.length, recibido.length, "ni un carácter de más ni de menos")
        assertTrue(recibido.contains("\t\"signature\" :"), "los tabuladores sobreviven")
        assertTrue(recibido.contains("\r\n"), "el CRLF sobrevive")
        assertTrue(recibido.contains(acentos), "el texto con acentos llega sin decodificar y re-codificar")
        assertTrue(recibido.contains("\"nota\""), "un campo desconocido no se pierde (§7.1.3)")
        assertEquals("ACK", typeOf(recibido), "y el relé lo sigue leyendo como ACK para rutearlo")
    }

    // =====================================================================
    // Utilidades de entramado
    // =====================================================================

    /**
     * Conecta una identidad pasando por el handshake REAL del daemon.
     *
     * Lo que se hace aquí es lo que haría `RelayClient`: `AUTH_REQUEST` con
     * `protocolVersion` "2.0", firma del transcript de 152 B, `AUTH_RESPONSE`.
     * Que funcione es lo que hace que `peerId` sea una identidad verificada y no
     * una cadena que el test se inventó.
     */
    private fun connectAs(kp: KeyPair): Peer {
        val socket = RecordingSocket()
        val connection = RelayWebSocket(socket, log)
        router.register(socket, connection)

        val identity = identityOf(kp)
        router.onText(
            connection,
            mapper.writeValueAsString(
                objectNode {
                    put("type", "AUTH_REQUEST")
                    put("messageId", UUID.randomUUID().toString())
                    put("timestamp", NOW)
                    put("protocolVersion", JsonWireCodec.DIALECT_VERSION)
                    put("identityId", identity.value)
                    put("publicKey", Base64Url.encode(kp.publicKey))
                    put("responderIdentityId", relayId.value)
                },
            ),
        )

        val challenge = socket.sent.map { mapper.readTree(it) }
            .single { it["type"].asText() == "AUTH_CHALLENGE" }
        val transcript = TranscriptBuilder.authTranscript(
            nonce = Base64Url.decode(challenge["nonce"].asText()),
            timestampMillis = challenge["timestamp"].asLong(),
            responderIdentityId = challenge["responderIdentityId"].asText(),
            identityId = identity.value,
        )
        val signature = ed25519.sign(kp.privateKey, transcript).bytes
        router.onText(
            connection,
            mapper.writeValueAsString(
                objectNode {
                    put("type", "AUTH_RESPONSE")
                    put("messageId", UUID.randomUUID().toString())
                    put("timestamp", NOW)
                    put("signature", Base64Url.encode(signature))
                },
            ),
        )

        assertTrue(connection.isAuthenticated, "el handshake debe completar para ${identity.value.take(8)}")
        // El handshake dejó `AUTH_CHALLENGE` y `AUTH_OK` en este socket, y la
        // presencia que dispara el `connectAs` siguiente llega al que ya estaba.
        // Se vacían para que cada aserción mire solo lo que pasó por su acción.
        socket.sent.clear()
        return Peer(connection, socket)
    }

    /** `MESSAGE` saliente tal como lo escribe un cliente (§10). */
    private fun messageWire(from: IdentityId, to: IdentityId, payload: ByteArray): String =
        mapper.writeValueAsString(
            objectNode {
                put("type", "MESSAGE")
                put("messageId", UUID.randomUUID().toString())
                put("timestamp", NOW)
                put("from", from.value)
                put("to", to.value)
                put("data", Base64Standard.encode(payload))
            },
        )

    /**
     * Un `ACK` REALMENTE firmado por la identidad de [kp].
     *
     * Se construye con `ReceiverImpl` de km-core, no reimplementando el
     * transcript. `AckTranscript` es `internal`, y copiar su formato en el test
     * haría que este test pasara con una firma que el core no aceptaría: es la
     * forma más fácil de falsear una prueba de integridad.
     */
    private fun signedAck(kp: KeyPair, to: IdentityId, original: UUID): Ack {
        val local = identityOf(kp)
        var emitted: Ack? = null
        ReceiverImpl(
            messageStore = InMemoryMessageStore(),
            duplicateStore = InMemoryDuplicateStore(),
            decoder = JsonDecoder(),
            encoder = JsonEncoder(),
            validator = ValidatorImpl(),
            ed25519 = ed25519,
            transport = UnusedTransport,
            orderingManager = OrderingManagerImpl(),
            localIdentity = local,
            localPrivateKey = kp.privateKey,
            ackEmitter = { emitted = it; Result.success(Unit) },
        ).onMessageReceived(
            Message(
                messageId = MessageId(original),
                from = to,
                to = local,
                payload = byteArrayOf(1, 2, 3),
                timestamp = NOW,
            ),
        ).getOrThrow()
        return requireNotNull(emitted) { "ReceiverImpl debe emitir un ACK firmado" }
    }

    /**
     * El `ACK` en el cable: `JsonEncoder` de km-core pone los seis campos de
     * §11.2 y el cliente añade el discriminante `type`, que es lo que hacen
     * `km-desktop`'s `RelayDataCodec.encodeAck` y `RelayClient`. El relé no
     * añade nada, y por eso la trama no pasa por él.
     */
    private fun ackWire(ack: Ack): String {
        val node = mapper.readTree(JsonEncoder().encodeAck(ack).getOrThrow()) as ObjectNode
        node.put("type", "ACK")
        return mapper.writeValueAsString(node)
    }

    /**
     * Atajo para el caso frecuente: `ACK` firmado por [kp] con
     * `originalMessageId` y `status` sustituibles, para los casos de "no
     * semántica".
     */
    private fun ackWireFor(
        kp: KeyPair,
        to: IdentityId,
        original: UUID,
        originalLiteral: String = original.toString(),
        status: String = AckStatus.DELIVERED.name,
        signatureOverride: String? = null,
        fromOverride: IdentityId? = null,
    ): String {
        val node = mapper.readTree(ackWire(signedAck(kp, to = to, original = original))) as ObjectNode
        node.put("originalMessageId", originalLiteral)
        node.put("status", status)
        signatureOverride?.let { node.put("signature", it) }
        fromOverride?.let { node.put("from", it.value) }
        return mapper.writeValueAsString(node)
    }

    private fun decodeAck(frame: String): Ack {
        val node = mapper.readTree(frame)
        return Ack(
            from = IdentityId(node["from"].asText()),
            to = IdentityId(node["to"].asText()),
            originalMessageId = MessageId(UUID.fromString(node["originalMessageId"].asText())),
            status = AckStatus.valueOf(node["status"].asText()),
            timestamp = node["timestamp"].asLong(),
            signature = Base64Standard.decode(node["signature"].asText()).getOrThrow(),
        )
    }

    private fun storedMessage(id: MessageId, to: IdentityId) = Message(
        messageId = id,
        from = aliceId,
        to = to,
        payload = byteArrayOf(1, 2, 3),
        timestamp = NOW,
    )

    private fun publicKeyOf(id: IdentityId): ByteArray? = when (id) {
        aliceId -> aliceKp.publicKey
        bobId -> bobKp.publicKey
        malloryId -> malloryKp.publicKey
        else -> null
    }

    private fun identityOf(kp: KeyPair): IdentityId =
        IdentityId(kp.publicKey.let { KmIds.identityId(it) }.joinToString("") { "%02x".format(it) })

    private fun typeOf(frame: String): String? =
        mapper.readTree(frame)["type"]?.takeIf { it.isTextual }?.asText()

    private fun objectNode(block: ObjectNode.() -> Unit): ObjectNode =
        mapper.createObjectNode().apply(block)

    private companion object {
        /** Reloj fijo: el transcript de 152 B y la ventana de timestamp son comprobables. */
        const val NOW: Long = 1_700_000_000_000L
    }

    /**
     * Conexión de test: el `RelayWebSocket` y el socket que hay detrás.
     *
     * [send] entra por el ROUTER, no por la conexión. Es la única forma de que
     * un test ejercite el despacho por `type`: llamar a la conexión
     * directamente saltaría justo el código que se quiere probar.
     */
    private inner class Peer(val connection: RelayWebSocket, val socket: RecordingSocket) {
        fun send(frame: String) = router.onText(connection, frame)
    }
}

// =====================================================================
// Dobles
// =====================================================================

/**
 * `WebSocket` de Java-WebSocket 1.5.6 es una INTERFAZ, así que el doble es
 * trivial de escribir y no necesita servidor, hilo ni puerto.
 *
 * Solo dos métodos están vivos: `send(String)`, por donde sale todo el tráfico
 * de este dialecto, e `isOpen()`, que decide si una conexión cuenta como
 * destinataria. El resto lanza `UnsupportedOperationException` a propósito: si el
 * daemon empieza a usar `send(byte[])` —el camino binario que §6.4 deja sin
 * cerrar— este doble tiene que hacerlo fallar, no dejarlo pasar en verde.
 */
private class RecordingSocket : WebSocket {
    val sent = mutableListOf<String>()
    var open = true

    override fun send(text: String) {
        sent += text
    }

    override fun isOpen(): Boolean = open

    private fun unused(): Nothing =
        throw UnsupportedOperationException("metodo de WebSocket no usado por km-daemon")

    override fun close(code: Int, reason: String?) { open = false }
    override fun close(code: Int) { open = false }
    override fun close() { open = false }
    override fun closeConnection(code: Int, reason: String?) { open = false }
    override fun send(byteBuffer: ByteBuffer?) = unused()
    override fun send(data: ByteArray?) = unused()
    override fun sendFrame(framedata: Framedata?) = unused()
    override fun sendFrame(frames: MutableCollection<Framedata>?) = unused()
    override fun sendPing() = unused()
    override fun sendFragmentedFrame(opcode: Opcode?, buffer: ByteBuffer?, fin: Boolean) = unused()
    override fun hasBufferedData(): Boolean = false
    override fun getRemoteSocketAddress(): InetSocketAddress? = null
    override fun getLocalSocketAddress(): InetSocketAddress? = null
    override fun isClosing(): Boolean = !open
    override fun isFlushAndClose(): Boolean = false
    override fun isClosed(): Boolean = !open
    override fun getDraft(): Draft = unused()
    override fun getReadyState(): ReadyState = if (open) ReadyState.OPEN else ReadyState.CLOSED
    override fun getResourceDescriptor(): String = "ws://doble/relay"
    override fun <T : Any?> setAttachment(attachment: T?) = unused()
    override fun <T : Any?> getAttachment(): T = unused()
    override fun hasSSLSupport(): Boolean = false
    override fun getSSLSession(): SSLSession? = null
    override fun getProtocol(): IProtocol = unused()
}

/**
 * `Logger` del router que se guarda lo que se registra.
 *
 * El punto de este doble es poder afirmar el "DESCARTE **y LOG**" de T3 sin
 * mirar una consola: el log es parte del contrato de la enmienda (§11.1 exige
 * registrar los dos descartes), y un contrato que no se puede observar desde un
 * test es una intención.
 *
 * Se extiende `AbstractLogger` de slf4j en lugar de implementar `Logger` a mano
 * porque son cinco métodos de nivel y uno de normalización, no setenta.
 */
private class RecordingLogger : AbstractLogger() {
    val entries = mutableListOf<Pair<Level, String>>()

    init {
        name = "AckForwardingTest"
    }

    fun contains(fragment: String): Boolean = entries.any { it.second.contains(fragment) }

    fun entriesAt(level: Level): List<String> =
        entries.filter { it.first == level }.map { it.second }

    override fun getFullyQualifiedCallerName(): String? = RecordingLogger::class.java.name

    override fun handleNormalizedLoggingCall(
        level: Level,
        marker: Marker?,
        messagePattern: String?,
        arguments: Array<out Any>?,
        throwable: Throwable?,
    ) {
        entries += level to MessageFormatter.basicArrayFormat(messagePattern ?: "", arguments ?: emptyArray())
    }

    override fun isTraceEnabled(): Boolean = true
    override fun isTraceEnabled(marker: Marker?): Boolean = true
    override fun isDebugEnabled(): Boolean = true
    override fun isDebugEnabled(marker: Marker?): Boolean = true
    override fun isInfoEnabled(): Boolean = true
    override fun isInfoEnabled(marker: Marker?): Boolean = true
    override fun isWarnEnabled(): Boolean = true
    override fun isWarnEnabled(marker: Marker?): Boolean = true
    override fun isErrorEnabled(): Boolean = true
    override fun isErrorEnabled(marker: Marker?): Boolean = true
}

/**
 * `Transport` que no se usa.
 *
 * `ReceiverImpl` solo lo necesita porque su `ackEmitter` por defecto delega en
 * `transport.sendAck`. Este test inyecta ese emitter, así que el `Transport` no
 * llega a usarse; está aquí para que la construcción sea explícita y no un
 * `TODO()`. Si alguna vez se invoca, es un fallo del test, no un no-op.
 */
private object UnusedTransport : Transport {
    private fun unused(): Nothing =
        throw UnsupportedOperationException("el ackEmitter inyectado no usa el Transport")

    override fun connect(): Result<Unit> = unused()
    override fun disconnect() = unused()
    override fun send(data: ByteArray, to: IdentityId): Result<Unit> = unused()
    override fun sendAck(ackBytes: ByteArray): Result<Unit> = unused()
    override fun setMessageHandler(handler: (ByteArray) -> Unit) = unused()
    override fun setAckHandler(handler: (ByteArray) -> Unit) = unused()
    override fun isOnline(): Boolean = unused()
    override fun connectionState(): ConnectionState = unused()
    override fun onStateChange(handler: (ConnectionState) -> Unit) = unused()
    override fun onPeerOnline(handler: (IdentityId) -> Unit) = unused()
    override fun onPeerOffline(handler: (IdentityId) -> Unit) = unused()
}
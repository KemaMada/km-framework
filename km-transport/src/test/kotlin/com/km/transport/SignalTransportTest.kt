package com.km.transport

import com.km.model.IdentityId
import com.km.protocol.ConnectionState
import com.km.transport.SocketSecureWorld.awaitOrFail
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * El despacho de `SIGNAL` en el motor de transporte.
 *
 * ## POR QUE UN TEST PROPIO Y NO DENTRO DEL CODEC
 *
 * Porque las dos reglas que importan aqui NO son del codec:
 *
 *  1. Una senal dirigida a OTRO par no se entrega aqui. El codec la entiende
 *     sin problema —es un frame bien formado—; lo que la descarta es que su
 *     `to` no sea esta identidad.
 *  2. Un `kind` desconocido, o un frame roto, NO matan la conexion. Eso es
 *     del bucle de recepcion, y la prueba de que sigue viva es que la senal
 *     SIGUIENTE llega.
 *
 * Casi todos los casos construyen el motor SIN abrir socket: `deliverSignal` es
 * el punto de despacho, y probarlo ahi no necesita red, ni rele, ni esperas.
 *
 * ## LA EXCEPCION: `SIGNAL_TO_SELF` ESTANDO ONLINE (D1 / TS-1)
 *
 * El rechazo del envio a la propia identidad tiene DOS guardas en
 * [JvmRelayTransport.sendSignal]: primero el estado, despues `to == identity`.
 * Un test que corra con el motor OFFLINE solo ejercita la primera, y por eso la
 * mutacion que borra la guarda de `SIGNAL_TO_SELF` sobrevivia (verify #331,
 * MC): el motor seguia fallando —por estar desconectado— y el test seguia
 * verde. El caso ONLINE de abajo levanta un rele REAL ([LocalRelay]), conecta el
 * motor de verdad y comprueba el rechazo **atravesando** la primera guarda.
 */
class SignalTransportTest {

    private val local = IdentityId("11".repeat(32))
    private val remoto = IdentityId("22".repeat(32))
    private val otro = IdentityId("33".repeat(32))

    private fun motor(): JvmRelayTransport = JvmRelayTransport(
        identity = local,
        ed25519PublicKey = ByteArray(32) { it.toByte() },
        ed25519PrivateKey = ByteArray(64) { it.toByte() },
    )

    private fun frameDeContacto(to: IdentityId, portador: ByteArray = byteArrayOf(1, 2, 3)): String {
        val codec = RelayDataCodec()
        return codec.encodeSignal(local, to, RelayDataCodec.KIND_CONTACT_EXCHANGE, portador)
    }

    /**
     * El rele se arranca SOLO en el caso que lo necesita: los otros ocho casos
     * de esta clase no deben pagar el montaje.
     */
    private var relay: LocalRelay? = null

    private fun rele(): LocalRelay = relay ?: LocalRelay.start("self-signal").also { relay = it }

    @AfterEach
    fun pararRele() {
        relay?.shutdown()
        relay = null
    }

    @Test
    fun `una senal dirigida a esta identidad LLEGA al suscriptor`() {
        val motor = motor()
        val recibidas = mutableListOf<DecodedSignal.Known>()
        motor.onSignal { if (it is DecodedSignal.Known) recibidas += it }

        motor.deliverSignal(motor.relayCodec().parseObject(frameDeContacto(local))!!)

        assertEquals(1, recibidas.size, "la senal que si es para nosotros se entrega")
        assertEquals(remoto.let { local }, recibidas.single().from)
        assertEquals(RelayDataCodec.KIND_CONTACT_EXCHANGE, recibidas.single().kind)
    }

    @Test
    fun `una senal con to DISTINTO de esta identidad se descarta`() {
        // La regla que se pedia por el nombre: una senal de otro par no se
        // entrega aqui, aunque su frame sea perfecto.
        val motor = motor()
        var entregadas = 0
        motor.onSignal { entregadas++ }

        motor.deliverSignal(motor.relayCodec().parseObject(frameDeContacto(otro))!!)

        assertEquals(0, entregadas, "una senal dirigida a otro par no se entrega aqui")
    }

    @Test
    fun `enviar una SIGNAL a la PROPIA identidad se descarta`() {
        // El motor esta OFFLINE: este caso prueba la guarda de ESTADO, no la
        // de `SIGNAL_TO_SELF` (el caso ONLINE es el de abajo). Se conserva
        // porque el orden de las guardas es contrato: sin conexion no se envia
        // nada, ni siquiera a un par valido.
        val motor = motor()
        val resultado = motor.sendSignal(local, RelayDataCodec.KIND_CONTACT_EXCHANGE, byteArrayOf(1))
        assertTrue(resultado.isFailure, "una SIGNAL a si mismo no sale: $resultado")
    }

    /**
     * D1 / TS-1: la ruta de RECHAZO de `SIGNAL_TO_SELF`, con el motor realmente
     * `ONLINE` contra un rele in-process.
     *
     * ## QUE SE AFIRMA Y POR QUE CADA COSA
     *
     *  1. `sendSignal(to = propia)` FALLA con la excepcion del rechazo y la
     *     traza dice `SIGNAL_TO_SELF`. Este es el aserto que mata la mutacion
     *     MC: si se borra la guarda, el envio devuelve exito y esto se pone
     *     rojo (antes no, porque el motor estaba OFFLINE y fallaba por estado).
     *  2. La conexion sigue `ONLINE`. Un rechazo del emisor no es un fallo de
     *     la sesion; la UI no debe perder el estado por un envio mal formado.
     *  3. El rele NO recibe la senal. Se comprueba por el camino REAL: el
     *     `RelayServer` rutea una senal a su destinatario por la sesion de
     *     `to`, y con `to == from` el destinatario es esta misma sesion, asi
     *     que si el frame hubiera cruzado el socket volveria y se entregaria
     *     al suscriptor. Suscriptor vacio = no salio nada.
     *  4. Y el socket sigue USABLE: una SIGNAL a un par AUSENTE si viaja y el
     *     rele contesta `RELAY_ERROR PEER_NOT_FOUND`. Sin esto, "sigue ONLINE"
     *     podria ser una etiqueta mentirosa, que es justo el fallo que persigue
     *     el change single-scan-hardening.
     */
    @Test
    @Timeout(120)
    fun `ONLINE, una SIGNAL a la PROPIA identidad falla con SIGNAL_TO_SELF y no cruza el socket`() {
        val relay = rele()
        val keyPair = SocketSecureWorld.ed25519.generateKeyPair()
        val yo = SocketSecureWorld.identityOf(keyPair.publicKey)
        val logs = CopyOnWriteArrayList<String>()
        val recibidas = CopyOnWriteArrayList<DecodedSignal>()

        val transport = JvmRelayTransport(
            identity = yo,
            ed25519PublicKey = keyPair.publicKey,
            ed25519PrivateKey = keyPair.privateKey,
            endpoint = relay.endpoint(),
            relayIdentity = relay.relayIdentityId,
            ed25519 = SocketSecureWorld.ed25519,
            handshakeTimeoutMs = 5_000,
            log = { logs.add(it) },
        )
        transport.onSignal { recibidas += it }

        try {
            transport.connect().getOrThrow()
            assertTrue(transport.isOnline(), "el caso exige un transporte ONLINE de verdad")
            assertEquals(ConnectionState.ONLINE, transport.connectionState())

            val resultado = transport.sendSignal(yo, RelayDataCodec.KIND_CONTACT_EXCHANGE, byteArrayOf(1, 2, 3))

            // 1. El rechazo es el de SIGNAL_TO_SELF, no el de "no conectado".
            val fallo = assertIs<IllegalStateException>(
                resultado.exceptionOrNull(),
                "una SIGNAL a la propia identidad se rechaza: $resultado",
            )
            assertEquals("una SIGNAL no se envia a la identidad local", fallo.message)
            assertTrue(
                logs.any { it.contains("SIGNAL_TO_SELF") },
                "la traza nombra el codigo del rechazo: $logs",
            )

            // 2. El rechazo NO tumba la sesion.
            assertEquals(
                ConnectionState.ONLINE,
                transport.connectionState(),
                "un envio rechazado no cambia el estado de la conexion",
            )
            assertTrue(transport.isOnline())

            // 3. Nada cruzo el socket: si hubiera salido, el rele lo devolveria
            //    a esta misma sesion (to == from) y llegaria al suscriptor.
            Thread.sleep(1_000)
            assertTrue(recibidas.isEmpty(), "el rele no recibio ninguna SIGNAL: $recibidas")

            // 4. El socket sigue vivo y usable: una SIGNAL a un par AUSENTE sale
            //    y el rele la contesta.
            val ajeno = IdentityId("22".repeat(32))
            val aOtro = transport.sendSignal(ajeno, RelayDataCodec.KIND_CONTACT_EXCHANGE, byteArrayOf(1))
            assertTrue(aOtro.isSuccess, "con el motor ONLINE una SIGNAL a otro par se escribe: $aOtro")
            awaitOrFail("el rele contesta a la SIGNAL dirigida a un par ausente") {
                logs.any { it.contains("RELAY_ERROR PEER_NOT_FOUND") }
            }
            assertTrue(recibidas.isEmpty(), "la respuesta del rele es un aviso, no una SIGNAL: $recibidas")
        } finally {
            transport.close()
        }
    }

    @Test
    fun `un kind desconocido NO corta la conexion`() {
        // R1: "se ignora con log; la conexion sigue viva". La prueba de que
        // sigue viva es que la senal SIGUIENTE, valida, se entrega.
        val motor = motor()
        val recibidas = mutableListOf<DecodedSignal.Known>()
        motor.onSignal { if (it is DecodedSignal.Known) recibidas += it }

        val codec = motor.relayCodec()
        val raro = codec.parseObject(
            """{"type":"SIGNAL","from":"${local.value}","to":"${local.value}","kind":"inventado"}""",
        )!!
        motor.deliverSignal(raro)

        assertTrue(recibidas.isEmpty(), "un kind desconocido no se entrega: $recibidas")

        motor.deliverSignal(codec.parseObject(frameDeContacto(local))!!)
        assertEquals(1, recibidas.size, "despues del kind raro, la conexion sigue viva")
    }

    @Test
    fun `un frame de senal ROTO no corta la conexion`() {
        val motor = motor()
        val recibidas = mutableListOf<DecodedSignal.Known>()
        motor.onSignal { if (it is DecodedSignal.Known) recibidas += it }

        val codec = motor.relayCodec()
        motor.deliverSignal(
            codec.parseObject(
                """{"type":"SIGNAL","from":"${local.value}","to":"${local.value}",
                    "kind":"contact_exchange","contactBundle":"no-es-base64!!"}""",
            )!!,
        )

        assertTrue(recibidas.isEmpty(), "una carga ilegible no se entrega")
        motor.deliverSignal(codec.parseObject(frameDeContacto(local))!!)
        assertEquals(1, recibidas.size, "despues de un frame roto, la conexion sigue viva")
    }

    @Test
    fun `hay mas de un suscriptor y uno no pisa al otro`() {
        // El mismo criterio que `onPeerOnline`: multi-suscriptor.
        val motor = motor()
        var primero = 0
        var segundo = 0
        motor.onSignal { primero++ }
        motor.onSignal { segundo++ }

        motor.deliverSignal(motor.relayCodec().parseObject(frameDeContacto(local))!!)

        assertEquals(1, primero)
        assertEquals(1, segundo, "el segundo suscriptor no pisa al primero")
    }

    @Test
    fun `un suscriptor que FALLA no tumba la recepcion ni a los demas`() {
        val motor = motor()
        var despues = 0
        motor.onSignal { throw IllegalStateException("este consumidor esta roto") }
        motor.onSignal { despues++ }

        motor.deliverSignal(motor.relayCodec().parseObject(frameDeContacto(local))!!)

        assertEquals(1, despues, "un consumidor roto no se lleva por delante al otro")
    }

    @Test
    fun `sin conexion no se puede enviar una SIGNAL`() {
        val motor = motor()
        assertFalse(motor.isOnline())
        val resultado = motor.sendSignal(remoto, RelayDataCodec.KIND_CONTACT_EXCHANGE, byteArrayOf(1))
        assertTrue(resultado.isFailure, "sin socket no hay senal: $resultado")
    }

    @Test
    fun `el from de una SIGNAL saliente es SIEMPRE la identidad local`() {
        // El rele autentica el `from` contra la sesion: un `from` escribible
        // por el llamante seria una firma falsificable.
        val codec = RelayDataCodec()
        val frame = codec.parseObject(
            codec.encodeSignal(local, remoto, RelayDataCodec.KIND_CONTACT_EXCHANGE, byteArrayOf(1)),
        )!!
        assertEquals(local.value, frame.string("from"))
        assertEquals(remoto.value, frame.string("to"))
        assertIs<DecodedSignal.Known>(codec.decodeSignal(frame))
    }
}

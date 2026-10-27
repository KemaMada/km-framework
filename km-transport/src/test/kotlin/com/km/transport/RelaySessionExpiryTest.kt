package com.km.transport

import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.identity.KmIds
import com.km.model.IdentityId
import com.km.protocol.ConnectionState
import com.km.transport.SocketSecureWorld.awaitOrFail
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D2 / TS-2 / TS-4: la sesion del rele expira y el motor la suelta.
 *
 * ## EL CASO REAL QUE ESTE TEST REPRODUCE
 *
 * HONOR/S8+, `sessionExpiryMs` de 300 s (5 min; el log del usuario muestra
 * ~10 min de inactividad): la app seguia `[RELAY] ... ONLINE`, el rele habia
 * expirado la sesion y no habia cerrado el socket, y la primera `SIGNAL`
 * posterior volvia como
 *
 * ```
 * [RELAY] RELAY_ERROR RELAY_NOT_AUTHORIZED: sesion de 1e9a73f... expirada
 * [SIGNAL] intercambio de contactos NO entregado codigo=CONTACT_EXCHANGE_REFUSED
 * ```
 *
 * Es decir: un estado `ONLINE` que mentia y un "rechazo del intercambio" que
 * nunca existio. Este test levanta el `km-daemon` REAL ([LocalRelay]) con
 * `sessionExpiryMs` corto y comprueba el contrato entero:
 *
 *  1. el motor sale de `ONLINE`, limpia el `sessionId` muerto y renegocia con
 *     un `AUTH` nuevo por el ciclo existente (backoff, no redial inmediato);
 *  2. el veredicto que ve `SignalDelivery` es `CONTACT_EXCHANGE_NOT_CONNECTED`
 *     y NUNCA `CONTACT_EXCHANGE_REFUSED`;
 *  3. dos expiraciones seguidas producen dos renegociaciones, y el estado no
 *     se queda `ONLINE` a traves de ninguna de ellas;
 *  4. no hay segundo `AUTH`: un aviso, un ciclo, un `sessionId` nuevo.
 *
 * ## POR QUE CONTRA EL RELE REAL Y NO CON UN DOBLE
 *
 * Porque la causa es una decision del `RelayServer` (comparar `now()` con
 * `authenticatedAt`) y la respuesta es un frame del `MessageRouter`. Un doble
 * del transporte podria "simular" la expiracion, pero tambien podria simular
 * una que el rele no produce. Aqui el frame cruza un socket de verdad.
 */
class RelaySessionExpiryTest {

    private val ed25519 = Ed25519Impl()
    private val logs = CopyOnWriteArrayList<String>()

    /**
     * Vida de sesion corta. 400 ms dejan margen de sobra para conectar y
     * escribir un frame, y convierten los 300 s de produccion en un test que
     * dura segundos. El valor NO se inyecta en el transporte: es el rele quien
     * decide, como en el despliegue.
     */
    private val sessionExpiryMs: Long = 400L

    private lateinit var relay: LocalRelay

    /**
     * Las lineas que [JvmRelayTransport.scheduleReconnect] escribe al PROGRAMAR
     * un reintento (`reconexion N en Xms`). Se excluye a proposito la linea de
     * exito (`reconexion N correcta`): lo que se cuenta son ciclos abiertos, y
     * dos lineas de programacion serian dos `AUTH` en camino.
     */
    private fun scheduleLogs(): List<String> =
        logs.filter { it.matches(Regex("reconexion \\d+ en \\d+ms")) }

    @BeforeEach
    fun startRelay() {
        relay = LocalRelay.start("expiry", sessionExpiryMs = sessionExpiryMs)
    }

    @AfterEach
    fun stopRelay() {
        if (::relay.isInitialized) relay.shutdown()
    }

    private fun identityOf(keyPair: KeyPair): IdentityId =
        IdentityId(KmIds.identityId(keyPair.publicKey).joinToString("") { "%02x".format(it) })

    private fun engine(keyPair: KeyPair, onState: (ConnectionState) -> Unit): JvmRelayTransport {
        val identity = identityOf(keyPair)
        return JvmRelayTransport(
            identity = identity,
            ed25519PublicKey = keyPair.publicKey,
            ed25519PrivateKey = keyPair.privateKey,
            endpoint = relay.endpoint(),
            relayIdentity = relay.relayIdentityId,
            ed25519 = ed25519,
            handshakeTimeoutMs = 5_000,
            log = { message -> logs.add(message) },
        ).also { it.onStateChange(onState) }
    }

    @Test
    @Timeout(120)
    fun `RELAY_NOT_AUTHORIZED estando ONLINE cierra la sesion y renegocia sin segundo AUTH`() {
        val keyPair = ed25519.generateKeyPair()
        // El destinatario de la SIGNAL es irrelevante: el rele rechaza por
        // sesion expirada ANTES de mirar el ruteo. Lo que importa es que no sea
        // la propia identidad, porque eso lo corta el motor (SIGNAL_TO_SELF).
        val peer = IdentityId("22".repeat(32))

        val estados = CopyOnWriteArrayList<Pair<ConnectionState, String?>>()
        lateinit var transport: JvmRelayTransport
        transport = engine(keyPair) { estado -> estados.add(estado to transport.status().sessionId) }

        try {
            transport.connect().getOrThrow()
            val session1 = transport.status().sessionId
            assertNotNull(session1, "la primera sesion tiene sessionId")
            assertEquals(ConnectionState.ONLINE, transport.connectionState())

            // La sesion del rele cumple su plazo MIENTRAS el socket sigue
            // abierto: es exactamente el estado mentiroso del caso HONOR.
            Thread.sleep(sessionExpiryMs + 200)
            assertEquals(
                ConnectionState.ONLINE,
                transport.connectionState(),
                "sin trafico, el motor no puede enterarse de que el rele expiro la sesion",
            )

            // Camino del usuario: la SIGNAL sale por el sink real y el
            // veredicto lo lee el SignalDelivery real.
            val resultado = SignalDelivery(RelaySignalSink(transport, peer)).deliver(byteArrayOf(1, 2, 3))

            val fallo = assertIs<SignalOutcome.Failed>(resultado)
            assertEquals(
                SignalDelivery.CODE_NOT_CONNECTED,
                fallo.code,
                "una sesion expirada es NO_CONNECTED, no un rechazo del intercambio",
            )
            assertNotEquals(SignalDelivery.CODE_REFUSED, fallo.code)
            assertTrue(
                logs.any { it.contains("RELAY_NOT_AUTHORIZED") },
                "el aviso del rele tiene que aparecer en la traza: $logs",
            )

            // 1. El motor suelta la sesion muerta: sale de ONLINE y olvida el
            //    sessionId (la UI deja de decir ONLINE).
            awaitOrFail("sale de ONLINE y limpia la sesion muerta") {
                transport.connectionState() != ConnectionState.ONLINE &&
                    transport.status().sessionId == null
            }

            // 2. Renegocia por el ciclo existente: AUTH nuevo, sessionId nuevo.
            awaitOrFail("vuelve ONLINE con un AUTH nuevo") {
                transport.isOnline() && transport.status().sessionId != session1
            }
            val session2 = transport.status().sessionId
            assertNotNull(session2, "la sesion renegociada tiene sessionId")
            assertNotEquals(session1, session2, "el AUTH nuevo no puede reutilizar la sesion muerta")

            // 3. Un aviso, UN ciclo: no hay redial inmediato ni un segundo
            //    handshake concurrente. Se espera mas que el backoff entero para
            //    que cualquier reintento doble tuviera tiempo de aparecer.
            Thread.sleep(2_500)
            val reconexiones = scheduleLogs()
            assertEquals(
                1,
                reconexiones.size,
                "un solo ciclo de reconexion por aviso; traza=$reconexiones\n$logs",
            )
            assertTrue(
                logs.any { it.matches(Regex("reconexion \\d+ correcta")) },
                "el ciclo programado termino: $logs",
            )
            assertTrue(
                logs.none { it.contains("redial inmediato") },
                "la sesion expirada usa el BACKOFF, no el redial inmediato de cambio de red: $logs",
            )
            assertEquals(
                2,
                estados.count { it.first == ConnectionState.ONLINE },
                "un ONLINE inicial y un AUTH de renegociacion: ninguno de mas. estados=$estados",
            )
            assertEquals(session2, transport.status().sessionId, "ningun reintento viejo piso la sesion nueva")

            // 4. Segunda expiracion: el ciclo se repite, y el estado no se queda
            //    ONLINE a traves de la primera (TS-2, escenario x2).
            Thread.sleep(sessionExpiryMs + 200)
            val segundo = SignalDelivery(RelaySignalSink(transport, peer)).deliver(byteArrayOf(4))
            assertEquals(
                SignalDelivery.CODE_NOT_CONNECTED,
                (assertIs<SignalOutcome.Failed>(segundo)).code,
            )
            awaitOrFail("segunda renegociacion ONLINE") {
                transport.isOnline() && transport.status().sessionId !in listOf(session1, session2)
            }
            val session3 = transport.status().sessionId
            assertNotNull(session3)
            Thread.sleep(1_500)
            assertEquals(
                3,
                estados.count { it.first == ConnectionState.ONLINE },
                "tres ONLINE: inicial + dos renegociaciones. estados=$estados",
            )
            assertEquals(
                2,
                scheduleLogs().size,
                "dos avisos, dos ciclos: $logs",
            )
            assertEquals(session3, transport.status().sessionId)
        } finally {
            transport.close()
        }
    }

    @Test
    @Timeout(120)
    fun `tras el aviso, awaitOnline espera la renegociacion y el reenvio del MISMO bundle ya sale`() =
        runBlocking {
            val keyPair = ed25519.generateKeyPair()
            val peerKeyPair = ed25519.generateKeyPair()
            val peer = identityOf(peerKeyPair)
            val transport = engine(keyPair) {}
            val peerTransport = engine(peerKeyPair) {}
            try {
                transport.connect().getOrThrow()
                val session1 = transport.status().sessionId
                assertNotNull(session1)
                Thread.sleep(sessionExpiryMs + 200)

                // 1. La sesion esta muerta aunque el motor diga ONLINE: la
                //    primera senal es el veredicto estable, NO un rechazo.
                val primero = SignalDelivery(RelaySignalSink(transport, peer)).deliver(byteArrayOf(9, 9, 9))
                assertEquals(
                    SignalDelivery.CODE_NOT_CONNECTED,
                    (assertIs<SignalOutcome.Failed>(primero)).code,
                    "el primer envio cae por sesion expirada: $primero",
                )

                // 2. LA COSTURA NUEVA: se espera a que la renegociacion del
                //    motor llegue a ONLINE. Sin forzar redial: el ciclo ya esta
                //    programado por `abandonExpiredSession`.
                assertTrue(
                    transport.awaitOnline(10_000),
                    "la renegociacion llega dentro del plazo. traza=$logs",
                )
                val session2 = transport.status().sessionId
                assertNotNull(session2)
                assertNotEquals(session1, session2, "hay AUTH nuevo, no la sesion muerta")

                // 3. El par tiene que estar presente en el rele para que el
                //    reenvio sea entregable: se conecta un segundo motor real.
                peerTransport.connect().getOrThrow()

                // 4. El MISMO bundle, reenviado una vez, ya no vuelve con
                //    NOT_CONNECTED: es `Delivered`.
                val segundo = SignalDelivery(RelaySignalSink(transport, peer)).deliver(byteArrayOf(9, 9, 9))
                assertIs<SignalOutcome.Delivered>(
                    segundo,
                    "con sesion nueva el reenvio se entrega: $segundo. traza=$logs",
                )

                // Un aviso, UN ciclo: la espera no abrio otro AUTH.
                assertEquals(
                    1,
                    scheduleLogs().size,
                    "un solo ciclo de renegociacion por aviso: ${scheduleLogs()}\n$logs",
                )
                assertTrue(
                    logs.any { it.matches(Regex("reconexion \\d+ correcta")) },
                    "el ciclo que desperto la espera termino: $logs",
                )
            } finally {
                peerTransport.close()
                transport.close()
            }
        }
}

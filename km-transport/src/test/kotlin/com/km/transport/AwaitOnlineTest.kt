package com.km.transport

import com.km.crypto.Ed25519Impl
import com.km.crypto.KeyPair
import com.km.identity.KmIds
import com.km.model.IdentityId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TS-R1: `awaitOnline(timeoutMs)` — la costura de ESPERA del cambio de
 * `contact-exchange-retry-after-reauth`.
 *
 * ## LO QUE ESTE TEST FIJA, Y POR QUE CADA COSA
 *
 *  - **ONLINE tardio** -> `true`: la renegociacion del motor es lo que el
 *    llamante estaba esperando.
 *  - **timeout** -> `false` ACOTADO: una espera sin cota retendria el hilo IO y
 *    el usuario no veria nunca el codigo estable.
 *  - **disconnect durante la espera** -> `false` RAPIDO: el motor ya sabe que no
 *    va a haber sesion; esperar el plazo entero seria mentirle al reloj.
 *  - **cancelacion** -> `CancellationException` y CERO efectos: ni redial, ni
 *    `AUTH` extra. La cancelacion desuscribe el flujo sola.
 *  - **ningun redial forzado**: `awaitOnline` es una espera PASIVA. Si forzara
 *    un redial, se abriria un segundo `AUTH` contra el que ya esta en camino y
 *    el rele cerraria la sesion del primero (`AUTH_FAILED INVALID_SIGNATURE`).
 *
 * ## POR QUE CONTRA EL RELE REAL
 *
 * Porque el unico que pone el motor en `ONLINE` es un `AUTH_OK` de verdad. Un
 * doble del transporte probaria que la espera espera, no que despierta con el
 * estado que el motor produce.
 */
class AwaitOnlineTest {

    private val ed25519 = Ed25519Impl()
    private val logs = CopyOnWriteArrayList<String>()

    private lateinit var relay: LocalRelay

    @BeforeEach
    fun startRelay() {
        relay = LocalRelay.start("await-online")
    }

    @AfterEach
    fun stopRelay() {
        if (::relay.isInitialized) relay.shutdown()
    }

    private fun identityOf(keyPair: KeyPair): IdentityId =
        IdentityId(KmIds.identityId(keyPair.publicKey).joinToString("") { "%02x".format(it) })

    private fun engine(): JvmRelayTransport {
        val keyPair = ed25519.generateKeyPair()
        return JvmRelayTransport(
            identity = identityOf(keyPair),
            ed25519PublicKey = keyPair.publicKey,
            ed25519PrivateKey = keyPair.privateKey,
            endpoint = relay.endpoint(),
            relayIdentity = relay.relayIdentityId,
            ed25519 = ed25519,
            handshakeTimeoutMs = 5_000,
            log = { message -> logs.add(message) },
        )
    }

    /** Reintentos PROGRAMADOS por el motor (`reconexion N en Xms`). */
    private fun scheduleLogs(): List<String> =
        logs.filter { it.matches(Regex("reconexion \\d+ en \\d+ms|redial inmediato.*")) }

    @Test
    @Timeout(60)
    fun `ya ONLINE devuelve true sin esperar`() = runBlocking {
        val transport = engine()
        try {
            transport.connect().getOrThrow()

            val transcurrido = measureTimeMillis {
                assertTrue(transport.awaitOnline(10_000), "ONLINE al entrar es true")
            }
            assertTrue(transcurrido < 1_000, "no espera lo que ya es cierto: ${transcurrido}ms")
            assertEquals(1, logs.count { it.contains("sesion establecida") }, "un solo AUTH: $logs")
        } finally {
            transport.close()
        }
    }

    @Test
    @Timeout(60)
    fun `ONLINE tardio devuelve true y no fuerza redial`() = runBlocking {
        val transport = engine()
        try {
            val espera = async(Dispatchers.Default) { transport.awaitOnline(15_000) }
            delay(250)

            // Antes del connect no hay ni un ciclo programado: la espera no toca
            // la politica de reconexion.
            assertTrue(
                scheduleLogs().isEmpty(),
                "awaitOnline NO programa reintentos: ${scheduleLogs()}\n$logs",
            )
            assertFalse(espera.isCompleted, "sin sesion todavia, la espera sigue viva")

            transport.connect().getOrThrow()

            assertTrue(espera.await(), "el ONLINE del AUTH despierta a quien esperaba")
            assertTrue(
                logs.any { it.contains("sesion establecida") },
                "lo que desperto fue un AUTH real: $logs",
            )
            assertTrue(
                logs.none { it.contains("redial") },
                "ningun redial forzado por la espera: $logs",
            )
            assertTrue(
                scheduleLogs().isEmpty(),
                "la espera pasiva no programa reintentos: ${scheduleLogs()}\n$logs",
            )
        } finally {
            transport.close()
        }
    }

    @Test
    @Timeout(60)
    fun `sin sesion y sin plazo suficiente devuelve false ACOTADO`() = runBlocking {
        val transport = engine()
        try {
            var resultado = false
            val transcurrido = measureTimeMillis {
                resultado = transport.awaitOnline(300)
            }

            assertFalse(resultado, "un RECONNECTING/DISCONNECTED al vencer el plazo no es true")
            assertTrue(transcurrido >= 250, "espero el plazo: ${transcurrido}ms")
            assertTrue(transcurrido < 5_000, "y no mas que el plazo: ${transcurrido}ms")
            assertTrue(
                scheduleLogs().isEmpty(),
                "el timeout de la espera no encola reintentos: ${scheduleLogs()}\n$logs",
            )
        } finally {
            transport.close()
        }
    }

    @Test
    @Timeout(60)
    fun `disconnect durante la espera devuelve false sin agotar el plazo`() = runBlocking {
        val transport = engine()
        try {
            val espera = async(Dispatchers.Default) { transport.awaitOnline(20_000) }
            delay(200)
            assertFalse(espera.isCompleted, "todavia no hay sesion")

            var resultado = true
            val transcurrido = measureTimeMillis {
                transport.disconnect()
                resultado = espera.await()
            }

            assertFalse(resultado, "el motor parado no puede dar true")
            assertTrue(
                transcurrido < 5_000,
                "el cierre despierta la espera; no se agota el plazo de 20 s: ${transcurrido}ms",
            )
        } finally {
            transport.close()
        }
    }

    @Test
    @Timeout(60)
    fun `un transporte YA parado devuelve false de inmediato`() = runBlocking {
        val transport = engine()
        transport.close()

        val transcurrido = measureTimeMillis {
            assertFalse(transport.awaitOnline(20_000), "parado no hay nada que esperar")
        }
        assertTrue(transcurrido < 1_000, "sin agotar el plazo: ${transcurrido}ms")
    }

    @Test
    @Timeout(60)
    fun `cancelar la espera desuscribe y no deja AUTH ni reintentos`() = runBlocking {
        val transport = engine()
        try {
            var cancelada = false
            val espera = launch(Dispatchers.Default) {
                try {
                    transport.awaitOnline(20_000)
                } catch (e: CancellationException) {
                    cancelada = true
                    throw e
                }
            }
            delay(200)
            espera.cancelAndJoin()

            assertTrue(cancelada, "la cancelacion llega al que espera como CancellationException")
            assertTrue(espera.isCancelled, "el job quedo cancelado")

            // La espera cancelada no puede haber tocado el motor: el AUTH que
            // haga el connect de abajo es el PRIMERO.
            transport.connect().getOrThrow()
            assertEquals(1, logs.count { it.contains("sesion establecida") }, "un AUTH, el del connect: $logs")
            assertTrue(scheduleLogs().isEmpty(), "cero reintentos: ${scheduleLogs()}\n$logs")
        } finally {
            transport.close()
        }
    }
}

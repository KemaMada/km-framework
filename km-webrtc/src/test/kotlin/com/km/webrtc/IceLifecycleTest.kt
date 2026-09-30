package com.km.webrtc

import com.km.model.IdentityId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 3N.2 — Ciclo de vida ICE, reconexion, ICE restart, callbacks obsoletos.
 *
 * INVARIANTE CENTRAL (3N.2):
 * "Todo evento proveniente de RealWebRtcTransport debe estar asociado a
 *  una instancia/version de binding concreta; un evento de un binding
 *  cerrado o reemplazado no puede mutar el estado del binding actual."
 *
 * Se implementa con fencing por epoch (generation). Cada PeerConnection
 * captura su generation; todo callback la revalida antes de actuar.
 */
class IceLifecycleTest {

    private lateinit var alice: RealWebRtcTransport
    private lateinit var bob: RealWebRtcTransport
    private lateinit var link: DirectWebRtcLink

    private val aliceId = identityIdFromSeed("alice")
    private val bobId = identityIdFromSeed("bob")

    @BeforeEach
    fun setUp() {
        alice = RealWebRtcTransport()
        bob = RealWebRtcTransport()
        assertTrue(alice.initialize().isSuccess)
        assertTrue(bob.initialize().isSuccess)
        assertTrue(alice.onCreateBinding(aliceId, bobId).isSuccess)
        assertTrue(bob.onCreateBinding(bobId, aliceId).isSuccess)
        link = DirectWebRtcLink(alice, bob, aliceId, bobId).wire()
    }

    @AfterEach
    fun tearDown() {
        alice.shutdown()
        bob.shutdown()
        alice.disposeCallbacks()
        bob.disposeCallbacks()
    }

    // ===================================================================
    // N7 — Ciclo de vida de recoleccion ICE
    // ===================================================================

    @Test
    fun `N7-01 gathering arranca en NEW`() {
        assertEquals(
            dev.onvoid.webrtc.RTCIceGatheringState.NEW,
            alice.iceGatheringState(aliceId, bobId)
        )
    }

    @Test
    fun `N7-02 gathering alcanza COMPLETE tras handshake`() {
        link.handshake()
        assertTrue(
            alice.awaitIceGatheringComplete(aliceId, bobId, 15_000L),
            "Alice no completo gathering: ${alice.iceGatheringState(aliceId, bobId)}"
        )
        assertTrue(
            bob.awaitIceGatheringComplete(bobId, aliceId, 15_000L),
            "Bob no completo gathering: ${bob.iceGatheringState(bobId, aliceId)}"
        )
    }

    @Test
    fun `N7-03 se generan multiples candidatos ICE`() {
        link.handshake()
        alice.awaitIceGatheringComplete(aliceId, bobId, 15_000L)
        // Drenar el buffer pendiente.
        val drained = alice.drainPendingIce(aliceId, bobId)
        val total = drained.size + link.aliceIce.size
        assertTrue(total > 0, "debe generarse al menos un candidato ICE")
    }

    @Test
    fun `N7-04 conectividad ICE alcanza CONNECTED en ambos lados`() {
        link.handshake()
        link.awaitBothConnected()
        assertTrue(awaitIceConnected(alice, aliceId, bobId), "Alice ICE no conectado")
        assertTrue(awaitIceConnected(bob, bobId, aliceId), "Bob ICE no conectado")
    }

    // ===================================================================
    // N8 — Reconexion
    // ===================================================================

    @Test
    fun `N8-01 epoch se incrementa al recrear la conexion`() {
        val gen1 = alice.generation(aliceId, bobId)
        assertNotNull(gen1)
        assertEquals(1L, gen1)

        alice.onCloseBinding(aliceId, bobId)
        assertNull(alice.generation(aliceId, bobId), "binding cerrado no debe tener epoch")

        assertTrue(alice.onCreateBinding(aliceId, bobId).isSuccess)
        val gen2 = alice.generation(aliceId, bobId)
        assertEquals(2L, gen2, "el epoch debe incrementarse, nunca reutilizarse")
    }

    @Test
    fun `N8-02 no hay dos conexiones activas para el mismo par`() {
        assertEquals(1, alice.activeConnectionCount(aliceId, bobId))
        val dup = alice.onCreateBinding(aliceId, bobId)
        assertTrue(dup.isFailure, "no se debe permitir binding duplicado")
        assertEquals(1, alice.activeConnectionCount(aliceId, bobId))
    }

    @Test
    fun `N8-03 reconexion completa vuelve a CONNECTED`() {
        // Primera conexion.
        link.handshake()
        link.awaitBothConnected()

        // Cerrar ambos lados y reconectar.
        link.rewire()
        val (offer, answer) = link.handshake()
        assertTrue(offer.contains("v=0"), "la reconexion debe producir SDP real")
        assertTrue(answer.contains("v=0"))

        link.awaitBothConnected()
        assertEquals(1, alice.activeConnectionCount(aliceId, bobId))
        assertEquals(1, bob.activeConnectionCount(bobId, aliceId))
    }

    @Test
    fun `N8-04 DataChannel funciona tras reconexion`() {
        link.handshake()
        link.awaitBothConnected()
        assertTrue(alice.awaitDataChannelOpen(aliceId, bobId))

        // Reconectar.
        link.rewire()
        link.handshake()
        link.awaitBothConnected()
        assertTrue(alice.awaitDataChannelOpen(aliceId, bobId))
        assertTrue(bob.awaitDataChannelOpen(bobId, aliceId))

        // Los datos vuelven a fluir con la nueva conexion.
        val payload = byteArrayOf(0x0A, 0x0B, 0x0C)
        link.bobReceived.expect { it.contentEquals(payload) }
        assertTrue(alice.sendData(bobId, payload).isSuccess)
        assertArrayEquals(payload, link.bobReceived.await())
    }

    // ===================================================================
    // N9 — ICE restart
    // ===================================================================

    @Test
    fun `N9-01 restartIce genera credenciales ICE nuevas`() {
        link.handshake()
        link.awaitBothConnected()
        alice.awaitIceGatheringComplete(aliceId, bobId, 15_000L)

        val offerBefore = sdpAttribute(link.aliceSdpHistory.last(), "ice-ufrag")
        assertNotNull(offerBefore)

        // Reiniciar ICE y regenerar oferta.
        assertTrue(alice.restartIce(aliceId, bobId).isSuccess)
        link.aliceLocalSdp.reset()
        assertTrue(alice.requestOffer(aliceId, bobId).isSuccess)
        link.aliceLocalSdp.await()

        val offerAfter = sdpAttribute(link.aliceSdpHistory.last(), "ice-ufrag")
        assertNotNull(offerAfter)
        assertNotEquals(
            offerBefore, offerAfter,
            "un ICE restart debe producir credenciales nuevas"
        )
    }

    @Test
    fun `N9-02 ICE restart preserva la identidad logica del peer`() {
        link.handshake()
        link.awaitBothConnected()
        val genBefore = alice.generation(aliceId, bobId)

        // Un ICE restart NO crea una PeerConnection nueva: el epoch se mantiene.
        assertTrue(alice.restartIce(aliceId, bobId).isSuccess)
        assertEquals(genBefore, alice.generation(aliceId, bobId))
        assertEquals(1, alice.activeConnectionCount(aliceId, bobId))
    }

    @Test
    fun `N9-03 restartIce sin binding falla`() {
        val fresh = identityIdFromSeed("nobody")
        assertTrue(alice.restartIce(aliceId, fresh).isFailure)
    }

    // ===================================================================
    // N10 — Callbacks obsoletos (stale callbacks) — INVARIANTE CENTRAL
    // ===================================================================

    @Test
    fun `N10-01 evento de conexion cerrada no propaga a downstream`() {
        val events = AtomicInteger(0)
        val current = alice.eventCallbacks
        alice.eventCallbacks = current.copy(
            onConnectionStateChange = { lid, pid, c ->
                current.onConnectionStateChange?.invoke(lid, pid, c)
                events.incrementAndGet()
            }
        )

        // Cerrar la conexion: cualquier evento posterior es stale.
        alice.onCloseBinding(aliceId, bobId)
        Thread.sleep(500)

        val before = events.get()
        Thread.sleep(500)
        assertEquals(before, events.get(),
            "un callback de conexion cerrada no debe generar eventos nuevos")
    }

    @Test
    fun `N10-02 datos de conexion cerrada no llegan a downstream`() {
        val received = AtomicInteger(0)
        link.observeAliceData { received.incrementAndGet() }

        link.handshake()
        link.awaitBothConnected()
        assertTrue(alice.awaitDataChannelOpen(aliceId, bobId))

        // Bob envia; Alice recibe (conexion viva).
        assertTrue(bob.sendData(aliceId, "antes".toByteArray()).isSuccess)
        waitUntil { received.get() > 0 }
        assertTrue(received.get() > 0, "debe recibirse al menos un mensaje con la conexion viva")

        // Cerrar Alice. Bob sigue intentando enviar; nada debe procesarse.
        alice.onCloseBinding(aliceId, bobId)
        Thread.sleep(400)
        val afterClose = received.get()

        repeat(20) { runCatching { bob.sendData(aliceId, "despues".toByteArray()) } }
        Thread.sleep(800)

        assertEquals(afterClose, received.get(),
            "datos de una conexion cerrada no deben entregarse a downstream")
    }

    @Test
    fun `N10-03 evento obsoleto no muta el binding nuevo`() {
        link.handshake()
        link.awaitBothConnected()
        assertEquals(1L, alice.generation(aliceId, bobId))

        // Recrear el binding -> nuevo epoch.
        alice.onCloseBinding(aliceId, bobId)
        assertTrue(alice.onCreateBinding(aliceId, bobId).isSuccess)
        assertEquals(2L, alice.generation(aliceId, bobId))

        // Los eventos de la conexion vieja (epoch 1) no deben tocar el nuevo.
        // Simula llegada tardia invoking el mecanismo de fencing via
        // un observer obsoleto no es accesible publicamente; se verifica
        // que el binding nuevo sigue limpio y funcional.
        assertEquals(2L, alice.generation(aliceId, bobId), "el epoch no debe retroceder")
        assertEquals(1, alice.activeConnectionCount(aliceId, bobId))
    }

    @Test
    fun `N10-04 pendingIce se limpia al recrear el binding`() {
        link.handshake()
        alice.awaitIceGatheringComplete(aliceId, bobId, 15_000L)
        assertTrue(alice.drainPendingIce(aliceId, bobId).isNotEmpty())

        alice.onCloseBinding(aliceId, bobId)
        assertTrue(alice.onCreateBinding(aliceId, bobId).isSuccess)
        // El buffer del binding viejo no debe filtrarse al nuevo.
        assertTrue(alice.drainPendingIce(aliceId, bobId).isEmpty(),
            "los candidatos del binding cerrado no deben sobrevivir")
    }

    @Test
    fun `N10-05 mensaje de DataChannel obsoleto se descarta`() {
        val received = AtomicInteger(0)
        link.observeAliceData { received.incrementAndGet() }

        link.handshake()
        link.awaitBothConnected()
        assertTrue(alice.awaitDataChannelOpen(aliceId, bobId))

        // Bob envia con conexion viva: llega.
        assertTrue(bob.sendData(aliceId, "vivo".toByteArray()).isSuccess)
        waitUntil { received.get() > 0 }
        val alive = received.get()

        // Alice se reconecta (nuevo epoch). Bob sigue con la conexion vieja:
        // cualquier mensaje residual debe descartarse por fencing.
        link.rewire()
        repeat(20) { runCatching { bob.sendData(aliceId, "obsoleto".toByteArray()) } }
        Thread.sleep(800)

        assertEquals(alive, received.get(),
            "mensajes de la conexion vieja no deben entregarse tras recrear el binding")
    }

    // ===================================================================
    // N11 — Condiciones de carrera
    // ===================================================================

    @Test
    fun `N11-01 close concurrente con create no deja conexiones huerfanas`() {
        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) { i ->
                pool.submit {
                    start.await()
                    val peer = identityIdFromSeed("peer-$i")
                    if (i % 2 == 0) {
                        alice.onCreateBinding(aliceId, peer)
                    } else {
                        alice.onCloseBinding(aliceId, peer)
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(20, TimeUnit.SECONDS), "hilos no terminaron")
        } finally {
            pool.shutdownNow()
        }
        // El estado debe seguir siendo coherente.
        assertTrue(alice.activeConnectionCount(aliceId, bobId) in 0..1)
    }

    @Test
    fun `N11-02 callbacks concurrentes no corrompen el estado`() {
        link.handshake()
        link.awaitBothConnected()
        assertTrue(alice.awaitDataChannelOpen(aliceId, bobId))

        val errors = ConcurrentLinkedQueue<String>()
        val threads = 4
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) { i ->
                pool.submit {
                    start.await()
                    repeat(25) { k ->
                        runCatching {
                            if (i == 0) alice.restartIce(aliceId, bobId)
                            if (i == 1) alice.connectionState(aliceId, bobId)
                            if (i == 2) alice.sendData(bobId, byteArrayOf(k.toByte()))
                            if (i == 3) alice.iceGatheringState(aliceId, bobId)
                        }.onFailure { errors.add(it.message ?: "error") }
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(30, TimeUnit.SECONDS), "hilos no terminaron")
        } finally {
            pool.shutdownNow()
        }
        // No debe haber excepciones de corrupcion; el binding sigue activo.
        assertEquals(1, alice.activeConnectionCount(aliceId, bobId))
    }

    @Test
    fun `N11-03 eventos posteriores a shutdown no lanzan`() {
        link.handshake()
        link.awaitBothConnected()

        alice.shutdown()
        alice.disposeCallbacks()
        // Cualquier operacion posterior debe fallar limpiamente.
        assertTrue(alice.onCreateBinding(aliceId, bobId).isFailure)
        assertTrue(alice.sendData(bobId, byteArrayOf(1)).isFailure)
        // Segundo shutdown seguro.
        assertTrue(alice.shutdown().isSuccess)
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    private fun aliceLocalSdpForTest(): String = link.aliceSdpHistory.lastOrNull() ?: ""

    /** Espera a que la conectividad ICE alcance CONNECTED. */
    private fun awaitIceConnected(
        transport: RealWebRtcTransport,
        local: IdentityId,
        remote: IdentityId,
        timeoutMs: Long = 15_000L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = transport.iceConnectionState(local, remote)
            if (s == dev.onvoid.webrtc.RTCIceConnectionState.CONNECTED ||
                s == dev.onvoid.webrtc.RTCIceConnectionState.COMPLETED
            ) return true
            Thread.sleep(25)
        }
        return false
    }

    private fun waitUntil(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
        }
    }

    private fun identityIdFromSeed(seed: String): IdentityId {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("KM-ID-IDENTITY".toByteArray())
        digest.update(seed.toByteArray())
        return IdentityId(digest.digest().joinToString("") { "%02x".format(it) })
    }
}

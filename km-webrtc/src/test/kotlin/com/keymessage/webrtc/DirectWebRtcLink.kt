package com.keymessage.webrtc

import com.keymessage.core.model.IdentityId
import com.keymessage.core.node.TransportEventCallbacks
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Infraestructura de sincronizacion para tests WebRTC reales.
 *
 * Los callbacks de WebRTC son asincronos y llegan desde hilos nativos.
 * En lugar de Thread.sleep() arbitrarios, usamos latches con timeout:
 * el test espera un evento concreto o falla con diagnostico.
 *
 * SEMANTICA:
 * - `expect(predicate)`: declara que esperamos un valor que cumpla la
 *   condicion. Si ya llego, se satisface de inmediato.
 * - `await()`: bloquea hasta que la condicion declarada se cumple.
 * - Si no se declaro condicion, `await()` retorna en cuanto llega CUALQUIER
 *   valor (util para SDP, que solo se emite una vez).
 *
 * Esto permite esperar "true" en eventos booleanos repetidos (conexion)
 * sin que un "false" transitorio (CONNECTING) los satisfaga por error.
 */
class Awaitable<T>(private val description: String) {

    private val ref = AtomicReference<T?>(null)
    private val condition = AtomicReference<((T) -> Boolean)?>(null)
    private val satisfied = AtomicReference(false)
    private val latch = AtomicReference(CountDownLatch(1))

    /**
     * Declara la condicion de espera. Idempotente.
     * Si ya hay un valor que la cumple, se marca satisfecha.
     */
    fun expect(predicate: (T) -> Boolean) {
        condition.set(predicate)
        val current = ref.get()
        if (current != null && predicate(current)) {
            satisfied.set(true)
            latch.get().countDown()
        }
    }

    /** Publica un valor; despierta al waiter si la condicion se cumple. */
    fun set(value: T) {
        ref.set(value)
        val predicate = condition.get()
        val ok = predicate?.invoke(value) ?: true
        if (ok) {
            satisfied.set(true)
            latch.get().countDown()
        }
    }

    /** Espera a que la condicion declarada se cumpla (o llegue un valor). */
    fun await(timeoutMs: Long = 10_000L): T {
        if (!latch.get().await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw AssertionError("timeout esperando: $description (ultimo valor: ${ref.get()})")
        }
        @Suppress("UNCHECKED_CAST")
        return ref.get() as T
    }

    /** Valor actual sin esperar. */
    fun current(): T? = ref.get()

    /** Verdadero si la condicion ya se cumplio. */
    fun isSatisfied(): Boolean = satisfied.get()

    /** Reinicia el awaitable para reutilizarlo tras una reconexion. */
    fun reset() {
        ref.set(null)
        condition.set(null)
        satisfied.set(false)
        latch.set(CountDownLatch(1))
    }
}

/**
 * Enlaza dos [RealWebRtcTransport] directamente, sin relay.
 *
 * Topologia (3N.1):
 * <pre>
 * Alice transport  <-->  Bob transport
 *     onLocalSdpGenerated -> peer.setRemoteDescription
 *     onIceCandidateGenerated -> peer.addIceCandidate
 *     onDataReceived -> verify
 *     onConnectionStateChange -> verify
 * </pre>
 *
 * La identidad (identityId) NO participa en la negociacion WebRTC;
 * solo se usa como clave de enrutamiento local, igual que hace el manager.
 */
class DirectWebRtcLink(
    val aliceTransport: RealWebRtcTransport,
    val bobTransport: RealWebRtcTransport,
    val aliceId: IdentityId,
    val bobId: IdentityId,
) {
    /** SDP generadas por cada lado. */
    val aliceLocalSdp = Awaitable<String>("Alice onLocalSdpGenerated")
    val bobLocalSdp = Awaitable<String>("Bob onLocalSdpGenerated")

    /**
     * Historial de SDP generadas por Alice, para comparar credenciales ICE
     * antes/despues de un ICE restart.
     */
    val aliceSdpHistory = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** ICE candidates generados por cada lado. */
    val aliceIce = ConcurrentLinkedQueue<String>()
    val bobIce = ConcurrentLinkedQueue<String>()

    /** Datos recibidos por cada lado. */
    val bobReceived = Awaitable<ByteArray>("Bob onDataReceived")
    val aliceReceived = Awaitable<ByteArray>("Alice onDataReceived")

    /** Estado de conexion observado por cada lado. */
    val aliceConnected = Awaitable<Boolean>("Alice onConnectionStateChange")
    val bobConnected = Awaitable<Boolean>("Bob onConnectionStateChange")

    /** Errores de transporte. */
    val aliceErrors = ConcurrentLinkedQueue<String>()
    val bobErrors = ConcurrentLinkedQueue<String>()

    fun wire(): DirectWebRtcLink {
        // Alice -> Bob
        aliceTransport.eventCallbacks = TransportEventCallbacks(
            onLocalSdpGenerated = { _, _, sdp ->
                aliceSdpHistory.add(sdp)
                aliceLocalSdp.set(sdp)
            },
            onIceCandidateGenerated = { _, _, candidate ->
                aliceIce.add(candidate)
                bobTransport.onIceCandidate(bobId, aliceId, candidate)
            },
            onDataReceived = { _, data -> aliceReceived.set(data) },
            onConnectionStateChange = { _, _, connected -> aliceConnected.set(connected) },
            onTransportError = { _, _, reason -> aliceErrors.add(reason) },
        )

        // Bob -> Alice
        bobTransport.eventCallbacks = TransportEventCallbacks(
            onLocalSdpGenerated = { _, _, sdp -> bobLocalSdp.set(sdp) },
            onIceCandidateGenerated = { _, _, candidate ->
                bobIce.add(candidate)
                aliceTransport.onIceCandidate(aliceId, bobId, candidate)
            },
            onDataReceived = { _, data -> bobReceived.set(data) },
            onConnectionStateChange = { _, _, connected -> bobConnected.set(connected) },
            onTransportError = { _, _, reason -> bobErrors.add(reason) },
        )
        return this
    }

    /**
     * Ejecuta el handshake completo:
     * Alice(createDataChannel + offer) -> Bob(accept + answer) -> Alice(applies).
     */
    fun handshake(): Pair<String, String> {
        // 1. Alice crea DataChannel + offer
        aliceTransport.createDataChannel(aliceId, bobId, "km")
        aliceTransport.requestOffer(aliceId, bobId)
        val offer = aliceLocalSdp.await()

        // 2. Bob aplica offer y genera answer
        bobTransport.acceptOfferAndAnswer(bobId, aliceId, offer)
        val answer = bobLocalSdp.await()

        // 3. Alice aplica answer
        aliceTransport.applyRemoteAnswer(aliceId, bobId, answer)

        return offer to answer
    }

    /** Espera a que ambos lados reporten CONNECTED. */
    fun awaitBothConnected(timeoutMs: Long = 15_000L) {
        aliceConnected.expect { it }
        bobConnected.expect { it }
        aliceConnected.await(timeoutMs)
        bobConnected.await(timeoutMs)
    }

    /**
     * Reconstruye el enlace sobre las conexiones actuales.
     *
     * Necesario tras cerrar y recrear bindings (reconexion / ICE restart),
     * porque los transports eliminan sus recursos en [onCloseBinding].
     */
    fun rewire() {
        // Cerrar antes de recrear: onCreateBinding rechaza duplicados.
        aliceTransport.onCloseBinding(aliceId, bobId)
        bobTransport.onCloseBinding(bobId, aliceId)
        resetAwaitables()
        assertTrue(aliceTransport.onCreateBinding(aliceId, bobId).isSuccess, "recrear binding Alice")
        assertTrue(bobTransport.onCreateBinding(bobId, aliceId).isSuccess, "recrear binding Bob")
        wire()
    }

    private fun resetAwaitables() {
        aliceLocalSdp.reset()
        bobLocalSdp.reset()
        bobReceived.reset()
        aliceReceived.reset()
        aliceConnected.reset()
        bobConnected.reset()
        aliceIce.clear()
        bobIce.clear()
    }

    /**
     * Engancha un observador adicional a los callbacks de Alice sin
     * romper el cableado existente (SDP, ICE, datos, estado).
     */
    fun observeAliceData(onData: (ByteArray) -> Unit) {
        val current = aliceTransport.eventCallbacks
        aliceTransport.eventCallbacks = current.copy(
            onDataReceived = { peerId, data ->
                current.onDataReceived?.invoke(peerId, data)
                onData(data)
            }
        )
    }

    /** Engancha un observador de datos para Bob, preservando el cableado. */
    fun observeBobData(onData: (ByteArray) -> Unit) {
        val current = bobTransport.eventCallbacks
        bobTransport.eventCallbacks = current.copy(
            onDataReceived = { peerId, data ->
                current.onDataReceived?.invoke(peerId, data)
                onData(data)
            }
        )
    }
}

/** Extrae el valor de una linea `a=<clave>:<valor>` de una SDP real. */
fun sdpAttribute(sdp: String, key: String): String? =
    sdp.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("a=$key:") }
        ?.substringAfter("$key:")
        ?.trim()
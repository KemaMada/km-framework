package com.km.desktop.net

import com.km.crypto.Ed25519Impl
import com.km.desktop.support.SecureTestIdentities
import com.km.desktop.support.inertBackend
import com.km.model.IdentityId
import com.km.node.PeerTransportManager
import com.km.transport.JvmRelayTransport
import com.km.transport.RelayClientStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 1.1 — El motor del rele se USA, no se copia.
 *
 * ## QUE AFIRMA
 *
 * `RelayStackFactory.stackFor(motor)` devuelve una pila cuyo `backend` es el
 * MISMO objeto que su `transport`, y cuyo `manager` tiene ese motor por backend.
 * `===` por identidad, no por igualdad: dos motores distintos con el mismo
 * estado son dos motores, y la divergencia entre ellos es silenciosa.
 *
 * ## POR QUE ESTO NO ES COSMETICO
 *
 * Si el manager tuviera otro backend por debajo, los bytes de SALIDA irian por
 * un sitio y la CONEXION viviria en otro. El envio pareceria funcionar —la
 * conexion esta viva— y no llegaria. Al reves tampoco: la conexion pareceria
 * caida con el cable sano. Ninguno de los dos sintomas dice "dos motores".
 *
 * ## EL RECHAZO, Y POR QUE ES UNA EXCEPCION
 *
 * Un manager con el backend equivocado es un fallo de ENSAMBLADO, no de
 * transporte: no ocurre si la pila se compone por aqui, y si ocurre significa
 * que alguien monto las piezas a mano. Por eso [RelayClientStack.compose]
 * lanza en vez de componer en silencio, y este test comprueba que el rechazo
 * SIGUE viniendo —que la factoria no lo envuelva ni lo trague.
 */
class RelayStackCompositionTest {

    /** Un motor real, construido pero NUNCA conectado: no hace falta socket. */
    private fun motor(identidad: IdentityId): JvmRelayTransport {
        val ed25519 = Ed25519Impl()
        val par = ed25519.generateKeyPair()
        return JvmRelayTransport(
            identity = identidad,
            ed25519PublicKey = par.publicKey,
            ed25519PrivateKey = par.privateKey,
            // Endpoint de configuracion, sin resolver: el motor no abre nada
            // hasta que se llama a `connect()`, y este test no lo llama.
            endpoint = "wss://ejemplo.invalid/km",
            ed25519 = ed25519,
        )
    }

    private val localId: IdentityId = SecureTestIdentities.localEnd("alice").identityId

    @Test
    fun `el backend de la pila ES su propio motor`() {
        val transporte = motor(localId)

        val pila = RelayStackFactory.stackFor(transporte)

        assertSame(
            transporte,
            pila.backend,
            "el backend de la pila es el MISMO objeto que el motor, no una segunda fachada",
        )
        assertSame(
            transporte,
            pila.transport,
            "y el motor de la pila es el que se le dio",
        )
    }

    @Test
    fun `el manager de la pila tiene ESE motor por backend`() {
        val transporte = motor(localId)

        val pila = RelayStackFactory.stackFor(transporte)

        assertSame(
            transporte,
            pila.manager.backend(),
            "el cable de recepcion del manager esta en el motor por el que salen los bytes",
        )
    }

    @Test
    fun `la pila conserva la identidad con la que se compone`() {
        val pila = RelayStackFactory.stackFor(motor(localId))

        assertEquals(localId, pila.identity, "la pila no fabrica identidades: propaga la del motor")
    }

    /**
     * La otra mitad del invariante: el rechazo de un backend ajeno SIGUE
     * llegando.
     *
     * Si la factoria construyera su propio manager sin backend, o envolviera el
     * `compose` tragandose la excepcion, esta prueba no tendria nada que mirar.
     */
    @Test
    fun `un manager cuyo backend es OTRO motor se RECHAZA, no se compone`() {
        val transporte = motor(localId)
        // Un manager con un backend que NO es el motor: el error exacto que
        // `compose` existe para no dejar pasar.
        val managerEquivocado = PeerTransportManager(inertBackend(), now = { 0L })

        val fallo = assertThrows(IllegalArgumentException::class.java) {
            RelayClientStack.compose(transporte, managerEquivocado)
        }

        assertTrue(
            fallo.message?.contains("backend") == true,
            "el mensaje dice POR QUE falla —el backend— y no solo que fallo: ${fallo.message}",
        )
    }

    /** Control positivo: el rechazo no es "todo lo que se compone falla". */
    @Test
    fun `control positivo un manager con el motor correcto SI se compone`() {
        val transporte = motor(localId)

        val pila = RelayStackFactory.stackFor(transporte)

        assertNotNull(pila.backend)
        assertTrue(pila.backend === transporte)
    }

    /**
     * Control NEGATIVO del detector de backend: `===` sobre un objeto DISTINTO
     * con el mismo comportamiento.
     *
     * Sin esto, "el backend es el motor" podria estar mirandose con `==` sobre
     * una clase que no tiene `equals`, y entonces cualquier motor pasaria.
     */
    @Test
    fun `control negativo dos motores con el MISMO endpoint NO son el mismo objeto`() {
        val uno = motor(localId)
        val otro = motor(localId)

        assertEquals(uno.status().endpoint, otro.status().endpoint, "los dos apuntan al mismo endpoint")
        assertTrue(uno !== otro, "pero son motores DISTINTOS: por eso la comparacion es por identidad")
    }
}
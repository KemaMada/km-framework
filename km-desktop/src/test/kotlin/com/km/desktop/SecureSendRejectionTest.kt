package com.km.desktop

import com.km.desktop.core.CoreFactory
import com.km.desktop.support.RelayProbe
import com.km.desktop.support.SecureTestIdentities
import com.km.desktop.support.recordingTransport
import com.km.desktop.support.relayProbeManager
import com.km.model.MessageState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 0.1 — La puerta anti-texto-plano: sin sesion, NO sale.
 *
 * ## QUE AFIRMA
 *
 * Un `sendMessage` a un par con el que no hay sesion queda en `FAILED`, con
 * codigo de fallo, y **cero bytes** salen por los DOS caminos que existen: el
 * cifrado ([RelayProbe]) y el legado ([recordingTransport]).
 *
 * ## POR QUE LA AFIRMACION ESTA EN "CERO BYTES" Y NO EN "DEVUELVE FALLO"
 *
 * Porque `SecureSendRejectedException` es un `Result.failure`, y un
 * `Result.failure` lo puede devolver cualquier camino que decida no enviar —o
 * fallar por un motivo que nada tiene que ver. Lo que distingue "rechazo
 * correcto" de "fallo que dejo salir el mensaje" es lo que hay FUERA del
 * resultado: los bytes. Un nucleo que rechaza y ademas entrega seria un nucleo
 * que miente dos veces, y el `Result` no lo delata.
 *
 * ## POR QUE SE COMPRUEBAN LOS DOS CAMINOS
 *
 * Un byte puede salir de este proceso por `TransportBackend.sendData` (cifrado)
 * o por `Transport.send` (legado, en claro). Mirar solo el primero dejaria el
 * segundo sin vigilar, y el segundo es justo el que esta puerta cierra.
 */
class SecureSendRejectionTest {

    private class Montado(
        val bundle: CoreFactory.CoreBundle,
        val probe: RelayProbe,
        val legado: recordingTransport,
    )

    /** El nucleo TAL COMO lo construye `DesktopMain`, con las dos inyecciones. */
    private fun montar(local: SecureTestIdentities.LocalEnd): Montado {
        val (manager, probe) = relayProbeManager()
        val legado = recordingTransport()
        val bundle = CoreFactory().create(
            localIdentity = local.identityId,
            localPrivateKey = local.ed25519PrivateKey,
            transport = legado,
            manager = manager,
            peerPublicKeyResolver = { null },
        )
        return Montado(bundle, probe, legado)
    }

    @Test
    fun `sin sesion, enviar a un par NO sale por ningun camino`() {
        val local = SecureTestIdentities.localEnd("alice")
        val montado = montar(local)
        // Par que no es esta identidad y con el que no hay sesion establecida.
        val destino = SecureTestIdentities.otroIdentityId()

        val creado = montado.bundle.core.createMessage(destino, CONTENIDO).getOrThrow()
        val resultado = montado.bundle.core.sendMessage(creado)

        // 1. Lo observable por el API publico es un SendResult en FAILED.
        //
        //    NOTA HONESTA: `KeyMessageCoreImpl.sendMessage` envuelve el fallo de
        //    `sender.send` y devuelve `Result.success(SendResult(FAILED, ...))`:
        //    la `SecureSendRejectedException` que produce el camino cifrado NO
        //    sale por aqui. Lo que sale —y lo que la TUI puede mirar— es el
        //    estado. Por eso la asercion 1 es sobre el ESTADO y la 3 es sobre
        //    los BYTES, que es donde un defecto se delata de verdad.
        assertTrue(resultado.isSuccess, "sendMessage devuelve un SendResult, no una excepcion: $resultado")
        val enviado = resultado.getOrThrow()
        assertEquals(
            MessageState.FAILED,
            enviado.state,
            "sin sesion el envio queda FAILED: no se documento como enviado",
        )
        assertNotNull(enviado.failureCode, "y con codigo de fallo, que es lo que ve el usuario")

        // 2. Y el estado PERSISTIDO dice lo mismo. El store es la verdad
        //    observable: un SendResult que dijera FAILED con un store en SENT
        //    seria un nucleo que miente.
        assertEquals(
            MessageState.FAILED,
            montado.bundle.core.getMessageState(creado.messageId).getOrThrow(),
            "un rechazo no es un SENT: no se envio",
        )
        val guardado = montado.bundle.core.getConversation(destino).getOrThrow().single()
        assertEquals(creado.messageId, guardado.messageId, "y es el MISMO mensaje el que quedo en FAILED")
        assertNotNull(guardado.failureCode, "con su codigo de fallo escrito en el almacen")

        // 3. CERO BYTES por los DOS caminos. Esta es la asercion que da nombre
        //    a la fase.
        assertEquals(0, montado.probe.bytesSalientes, "cero bytes por el camino cifrado")
        assertEquals(
            0,
            montado.legado.bytesEnClaro,
            "cero bytes por el camino LEGADO: el nucleo no degrada a texto plano",
        )
        assertEquals(0, montado.legado.enviados.size, "ni siquiera una llamada a Transport.send")
    }

    @Test
    fun `control el registro de sesiones existe y esta VACIO antes de enviar`() {
        val montado = montar(SecureTestIdentities.localEnd("alice"))

        assertEquals(0, montado.bundle.registry.size(), "no hay ninguna sesion todavia")
        assertEquals(
            emptySet<Any>(),
            montado.bundle.registry.peers().toSet<Any>(),
            "y el registro no inventa pares",
        )
    }

    @Test
    fun `control el camino legado de RECEPCION esta cerrado por el mismo nucleo`() {
        val montado = montar(SecureTestIdentities.localEnd("alice"))

        assertTrue(
            !montado.bundle.legacyInbound.legacyReceiveEnabled,
            "con registro presente y sin opt-in, el camino legado de recepcion esta CERRADO",
        )
    }

    /**
     * CONTROL NEGATIVO: el doble de transporte NO cuenta por defecto.
     *
     * Si [recordingTransport] contara "todo lo que pasa" en vez de "lo que sale
     * por `send`", la asercion de cero bytes de arriba seria verde con un
     * defecto puesto. Se mide ANTES de enviar nada.
     */
    @Test
    fun `control el doble de transporte no cuenta lo que no sale`() {
        val legado = recordingTransport()

        assertEquals(0, legado.bytesEnClaro, "un transporte recien construido no ha enviado nada")
        assertEquals(0, legado.enviados.size)
        assertEquals(0, legado.acks.size, "ni un ACK sin pedirlo")
    }

    /**
     * CONTROL NEGATIVO del contenido: lo que se cuenta NO es una cadena vacia.
     *
     * Un `sendData` con cero bytes y ningun `sendData` darian el mismo total, y
     * "cero bytes" seria verdad por la forma del numero. Se comprueba que con
     * un envio real el total es DISTINTO de cero.
     */
    @Test
    fun `control los dobles SI cuentan cuando hay algo que contar`() {
        val (_, probe) = relayProbeManager()
        val legado = recordingTransport()

        probe.sendData(SecureTestIdentities.otroIdentityId(), CONTENIDO)
        legado.send(CONTENIDO, SecureTestIdentities.otroIdentityId())

        assertEquals(
            CONTENIDO.size,
            probe.bytesSalientes,
            "el backend cuenta bytes cuando hay un SecureFrame",
        )
        assertEquals(
            CONTENIDO.size,
            legado.bytesEnClaro,
            "y el transporte cuenta bytes cuando hay un send",
        )
    }

    private companion object {
        /** Contenido NO constante y de tamano PROPIO. */
        val CONTENIDO: ByteArray =
            "esto es lo que el escritorio NO debe dejar salir en claro 4711".toByteArray(Charsets.UTF_8)
    }
}
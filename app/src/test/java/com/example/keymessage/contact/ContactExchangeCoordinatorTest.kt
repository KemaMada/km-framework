package com.example.keymessage.contact

import com.km.frame.FrameType
import com.km.frame.SecureFrameSpec
import com.km.model.IdentityId
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * La retencion ACOTADA del PREKEY de un par desconocido (R8).
 *
 * ## POR QUE EL RELOJ ESTA INYECTADO
 *
 * Porque el caso (c) de la spec —"nunca llega el SIGNAL, el TTL expira, no
 * queda sesion ni contacto"— es el que mas importa, y con el reloj real seria
 * un test de 60 s que nadie ejecuta. Con el reloj inyectado se prueba entero y
 * en milisegundos, y el NUMERO del TTL queda escrito en el codigo.
 */
class ContactExchangeCoordinatorTest {

    private var reloj = 0L
    private val ahora: () -> Long = { reloj }

    private val conocidos = mutableSetOf<IdentityId>()

    private fun par(n: Int): IdentityId = IdentityId(n.toString(16).padStart(64, '0'))

    /**
     * Un frame PREKEY de mentira: solo la cabecera, que es lo que el
     * coordinador mira ([PrekeyFrame.esPrekey] lee version y tipo).
     */
    private fun prekey(relleno: Int = 7): ByteArray = ByteArray(SecureFrameSpec.HEADER_LENGTH + 16) {
        when (it) {
            SecureFrameSpec.VERSION_OFFSET -> SecureFrameSpec.VERSION.toInt().toByte()
            SecureFrameSpec.TYPE_OFFSET -> FrameType.PREKEY.code.toInt().toByte()
            else -> relleno.toByte()
        }
    }

    private fun coordinador(
        maxRetenidos: Int = ContactExchangeCoordinator.MAX_RETENIDOS,
        ttlMs: Long = ContactExchangeCoordinator.TTL_MS,
    ) = ContactExchangeCoordinator(
        conocido = { conocidos.contains(it) },
        maxRetenidos = maxRetenidos,
        ttlMs = ttlMs,
        now = ahora,
    )

    // ==================================================================
    // R8 (a) — el SIGNAL llego antes que el PREKEY
    // ==================================================================

    @Test
    fun `si el par ya es contacto el PREKEY se entrega en el acto`() {
        val coord = coordinador()
        val par = par(1)
        conocidos += par

        var entregado: ByteArray? = null
        val entregadoAhora = coord.onPrekey(par, prekey()) { _, frame -> entregado = frame }

        assertTrue(entregadoAhora, "con el contacto ya registrado, el PREKEY no se retiene")
        assertContentEquals(prekey(), entregado, "y el frame es el de verdad")
        assertEquals(0, coord.retenidos(), "no queda nada retenido")
    }

    // ==================================================================
    // R8 (b) — el PREKEY llego antes y se entrega al registrar el contacto
    // ==================================================================

    @Test
    fun `un PREKEY de un desconocido se retiene y se entrega al registrar el contacto`() {
        val coord = coordinador()
        val par = par(2)
        val frame = prekey(9)

        var entregado: ByteArray? = null
        val entregadoAhora = coord.onPrekey(par, frame) { _, f -> entregado = f }

        assertFalse(entregadoAhora, "sin contacto no se puede aceptar: se retiene")
        assertEquals(null, entregado, "y no se entrega nada todavia")
        assertEquals(1, coord.retenidos())

        // Llega el `contact_exchange`: el par pasa a ser contacto.
        conocidos += par
        val cuantos = coord.onContactoRegistrado(par) { _, f -> entregado = f }

        assertEquals(1, cuantos, "el retenido se entrega al registrar el contacto")
        assertContentEquals(frame, entregado, "y es EXACTAMENTE el frame que llego")
        assertEquals(0, coord.retenidos(), "y ya no queda retenido")
    }

    @Test
    fun `drenar a un par que no tenia nada retenido no entrega nada`() {
        val coord = coordinador()
        var entregas = 0
        val cuantos = coord.onContactoRegistrado(par(3)) { _, _ -> entregas++ }
        assertEquals(0, cuantos)
        assertEquals(0, entregas)
    }

    // ==================================================================
    // R8 (c) — nunca llega el SIGNAL: el TTL expira
    // ==================================================================

    @Test
    fun `un PREKEY retenido EXPIRADO no se entrega nunca`() {
        val coord = coordinador()
        val par = par(4)
        val frame = prekey(3)

        coord.onPrekey(par, frame) { _, _ -> }
        assertEquals(1, coord.retenidos())

        // Pasa el TTL: el emisor ya dio el intercambio por perdido.
        reloj += ContactExchangeCoordinator.TTL_MS + 1

        var entregas = 0
        val cuantos = coord.onContactoRegistrado(par) { _, _ -> entregas++ }

        assertEquals(0, cuantos, "un frame caducado no se entrega")
        assertEquals(0, entregas, "ni se entrega por el otro lado")
        assertEquals(0, coord.retenidos(), "y no queda nada: ni sesion ni contacto")
    }

    @Test
    fun `justo ANTES del TTL el frame SIGUE vivo`() {
        // El borde: un frame que caducaria en el segundo siguiente no puede
        // perder su ventana porque el reloj haya avanzado un milisegundo de mas.
        val coord = coordinador()
        val par = par(5)
        val frame = prekey(5)

        coord.onPrekey(par, frame) { _, _ -> }
        reloj += ContactExchangeCoordinator.TTL_MS - 1

        var entregas = 0
        val cuantos = coord.onContactoRegistrado(par) { _, _ -> entregas++ }
        assertEquals(1, cuantos, "a un milisegundo de caducar, el frame sigue vivo")
    }

    // ==================================================================
    // El TOPE
    // ==================================================================

    @Test
    fun `el tope de 16 expulsa al mas ANTIGUO`() {
        val coord = coordinador()
        val frames = (1..20).map { par(it) to prekey(it) }

        frames.forEach { (emisor, frame) -> coord.onPrekey(emisor, frame) { _, _ -> } }

        assertEquals(
            ContactExchangeCoordinator.MAX_RETENIDOS,
            coord.retenidos(),
            "el tope acota lo retenido: 20 emisores no pueden ser 20 entradas",
        )
        // El mas antiguo (1) se fue; el mas reciente (20) sigue.
        assertEquals(0, coord.onContactoRegistrado(par(1)) { _, _ -> }, "el mas antiguo se expulsa")
        assertEquals(1, coord.onContactoRegistrado(par(20)) { _, _ -> }, "el mas reciente se queda")
    }

    @Test
    fun `hay UNA entrada por emisor y la nueva reemplaza a la vieja`() {
        val coord = coordinador()
        val par = par(7)
        val primero = prekey(1)
        val segundo = prekey(2)

        coord.onPrekey(par, primero) { _, _ -> }
        coord.onPrekey(par, segundo) { _, _ -> }

        assertEquals(1, coord.retenidos(), "un emisor no ocupa dos huecos")
        var entregado: ByteArray? = null
        coord.onContactoRegistrado(par) { _, f -> entregado = f }
        assertContentEquals(segundo, entregado, "y se entrega el MAS RECIENTE")
    }

    // ==================================================================
    // Lo que NO se retiene
    // ==================================================================

    @Test
    fun `un frame que NO es PREKEY no se retiene`() {
        val coord = coordinador()
        val par = par(8)
        // Version y tipo de un frame de MENSAJE: es de otro camino.
        val mensaje = ByteArray(64) {
            when (it) {
                SecureFrameSpec.VERSION_OFFSET -> SecureFrameSpec.VERSION.toInt().toByte()
                SecureFrameSpec.TYPE_OFFSET -> FrameType.MESSAGE.code.toInt().toByte()
                else -> 0
            }
        }

        val entregadoAhora = coord.onPrekey(par, mensaje) { _, _ -> }
        assertFalse(entregadoAhora)
        assertEquals(0, coord.retenidos(), "un mensaje de sesion no es un PREKEY pendiente")
    }

    @Test
    fun `un PREKEY demasiado grande no se retiene`() {
        val coord = coordinador()
        val par = par(9)
        val enorme = ByteArray(ContactExchangeCoordinator.MAX_FRAME_BYTES + 1) {
            when (it) {
                SecureFrameSpec.VERSION_OFFSET -> SecureFrameSpec.VERSION.toInt().toByte()
                SecureFrameSpec.TYPE_OFFSET -> FrameType.PREKEY.code.toInt().toByte()
                else -> 0
            }
        }

        val entregadoAhora = coord.onPrekey(par, enorme) { _, _ -> }
        assertFalse(entregadoAhora)
        assertEquals(0, coord.retenidos(), "un frame por encima del limite no se retiene")
    }

    // ==================================================================
    // La limpieza
    // ==================================================================

    @Test
    fun `parar() suelta lo retenido`() {
        val coord = coordinador()
        val par = par(10)
        coord.onPrekey(par, prekey()) { _, _ -> }
        assertEquals(1, coord.retenidos())

        coord.parar()
        assertEquals(0, coord.retenidos(), "tras parar, no queda nada retenido")

        var entregas = 0
        coord.onContactoRegistrado(par) { _, _ -> entregas++ }
        assertEquals(0, entregas, "y lo que se solto no se entrega")
    }
}

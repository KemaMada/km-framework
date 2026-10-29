package com.example.keymessage.contact

import com.km.transport.SignalOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * La SECUENCIA del emisor: portador propio -> `SIGNAL` -> sesion y PREKEY.
 *
 * ## POR QUE EL ORDEN ES LA GARANTIA
 *
 * El frame PREKEY no transporta el bundle del emisor (cabecera fija de 44 B) y
 * `km-core` exige el bundle VERIFICADO del emisor para aceptarlo (KM-0006
 * §7.3). Si el emisor abre la sesion y entrega el PREKEY antes de publicar su
 * bundle, el receptor recibe un PREKEY de un par que no conoce y no puede
 * aceptarlo.
 *
 * Este test dice el orden de forma EJECUTABLE, que es lo unico que lo protege:
 * un comentario no lo protege de nada.
 *
 * ## Y EL REENVIO ACOTADO (#398)
 *
 * El rele puede declarar expirada la sesion justo antes de la `SIGNAL`. El
 * transporte renegocia solo; lo que faltaba era esperar y reenviar el MISMO
 * bundle UNA vez. Aqui se fija: mismo `ByteArray`, un unico reenvio, PREKEY
 * solo tras `Delivered`, y cero reintento para los codigos que no arregla
 * nuestra re-auth. El control negativo es el timeout: NO puede salir PREKEY.
 */
class ContactBootstrapTest {

    /**
     * El arnes: cuenta las llamadas y guarda LOS BYTES que viajaron.
     *
     * [veredictos] es el guion del rele, uno por envio; si se acaba, se repite
     * el ultimo para que un bucle defectuoso se VEA (mas envios de los que el
     * test permite) en vez de caerse por indice.
     */
    private class Fixture(
        val portador: ByteArray = ByteArray(64) { (it + 1).toByte() },
        val veredictos: List<SignalOutcome>,
        private val esperaDevuelve: Boolean = true,
        private val esperaLanza: Exception? = null,
        private val falloExportacion: Exception? = null,
    ) {
        val orden = mutableListOf<String>()
        val esperas = mutableListOf<Long>()
        val enviados = mutableListOf<ByteArray>()
        var exportaciones = 0
        var sesiones = 0

        val bootstrap = ContactBootstrap(
            exportarPortador = {
                exportaciones++
                orden += "exportar"
                if (falloExportacion != null) throw falloExportacion
                portador
            },
            enviarPortador = { payload ->
                orden += "signal"
                enviados += payload
                veredictos[minOf(enviados.size - 1, veredictos.size - 1)]
            },
            abrirSesionYEntregar = {
                orden += "abrir-sesion"
                sesiones++
                Result.success(Unit)
            },
            esperarReauth = { timeoutMs ->
                orden += "esperar"
                esperas += timeoutMs
                if (esperaLanza != null) throw esperaLanza
                esperaDevuelve
            },
        )
    }

    private fun falloNoConectado(intentos: Int = 1) =
        SignalOutcome.Failed(SignalDeliveryCodes.NOT_CONNECTED, intentos)

    private fun entregado(intentos: Int = 1) = SignalOutcome.Delivered(intentos)

    @Test
    fun `el SIGNAL sale ANTES de abrir la sesion`() {
        val fixture = Fixture(veredictos = listOf(entregado()))

        val resultado = runBlocking { fixture.bootstrap.ejecutar() }

        assertTrue(resultado.isSuccess, "la secuencia completa va bien: $resultado")
        assertEquals(
            listOf("exportar", "signal", "abrir-sesion"),
            fixture.orden,
            "el bundle se publica ANTES del PREKEY: si no, el receptor no puede aceptarlo",
        )
        assertEquals(0, fixture.esperas.size, "sin expiry no se espera nada")
    }

    @Test
    fun `el portador que viaja es el que se exporta`() {
        val portador = ByteArray(300) { (it * 3).toByte() }
        val fixture = Fixture(portador = portador, veredictos = listOf(entregado()))

        runBlocking { fixture.bootstrap.ejecutar() }

        assertEquals(1, fixture.enviados.size)
        assertSame(portador, fixture.enviados.single(), "el bundle viaja tal cual, la MISMA instancia")
    }

    @Test
    fun `si el intercambio NO se entrega NO se abre sesion`() {
        // R5: el emisor NO da el alta por hecha. Sin intercambio no hay bundle
        // en el receptor, asi que sin sesion: un PREKEY que nadie puede
        // aceptar es peor que no enviar nada.
        val fixture = Fixture(veredictos = listOf(SignalOutcome.Failed(SignalDeliveryCodes.PEER_OFFLINE, 4)))

        val fallo = assertFailsWith<ContactExchangeException> {
            runBlocking { fixture.bootstrap.ejecutar() }
        }

        assertEquals(SignalDeliveryCodes.PEER_OFFLINE, fallo.code, "el codigo es ESTABLE")
        assertEquals(0, fixture.sesiones, "y la sesion NO se abre")
        assertEquals(listOf("exportar", "signal"), fixture.orden)
        assertEquals(0, fixture.esperas.size, "PEER_OFFLINE no consume la espera de re-auth (AB-R4)")
    }

    @Test
    fun `cada codigo de fallo del rele se propaga tal cual`() {
        listOf(
            SignalDeliveryCodes.PEER_OFFLINE,
            SignalDeliveryCodes.REFUSED,
            SignalDeliveryCodes.FAILED,
        ).forEach { codigo ->
            val fixture = Fixture(veredictos = listOf(SignalOutcome.Failed(codigo, 1)))

            val fallo = assertFailsWith<ContactExchangeException> {
                runBlocking { fixture.bootstrap.ejecutar() }
            }
            assertEquals(codigo, fallo.code, "el codigo $codigo viaja intacto")
            assertEquals(1, fixture.enviados.size, "$codigo no se reintenta: no lo arregla nuestra re-auth")
            assertEquals(0, fixture.esperas.size, "$codigo no espera re-auth")
            assertEquals(0, fixture.sesiones, "$codigo no abre sesion")
        }
    }

    @Test
    fun `expiry tras re-auth reenvia el MISMO bundle UNA vez y PREKEY solo tras Delivered`() {
        val fixture = Fixture(
            veredictos = listOf(falloNoConectado(), entregado()),
        )

        val resultado = runBlocking { fixture.bootstrap.ejecutar() }

        assertTrue(resultado.isSuccess, "el reenvio tras la re-auth cierra la secuencia: $resultado")
        assertEquals(
            listOf("exportar", "signal", "esperar", "signal", "abrir-sesion"),
            fixture.orden,
            "un unico reenvio, y el PREKEY despues del Delivered",
        )
        assertEquals(1, fixture.exportaciones, "el portador se exporta UNA vez: otro material seria otro bundle")
        assertEquals(2, fixture.enviados.size, "un envio y UN reenvio")
        assertSame(
            fixture.enviados[0],
            fixture.enviados[1],
            "el reenvio lleva el MISMO ByteArray, no una copia re-exportada (AB-R5)",
        )
        assertEquals(listOf(25_000L), fixture.esperas, "el plazo total es el de la spec: 25 s")
        assertEquals(1, fixture.sesiones, "la sesion/PREKEY se abre EXACTAMENTE una vez")
    }

    @Test
    fun `si la re-auth no llega a tiempo NO se reenvia y NO hay PREKEY`() {
        val fixture = Fixture(
            veredictos = listOf(falloNoConectado()),
            esperaDevuelve = false,
        )

        val fallo = assertFailsWith<ContactExchangeException> {
            runBlocking { fixture.bootstrap.ejecutar() }
        }

        assertEquals(SignalDeliveryCodes.NOT_CONNECTED, fallo.code, "el codigo estable se conserva (AB-R6)")
        assertEquals(1, fixture.enviados.size, "timeout: no se reenvia")
        assertEquals(listOf(25_000L), fixture.esperas, "la espera se hizo con el plazo de la spec")
        assertEquals(0, fixture.sesiones, "y NO se abre sesion: sin Delivered no hay PREKEY (AB-R2)")
        assertFalse(fixture.orden.contains("abrir-sesion"))
        assertEquals(
            listOf("exportar", "signal", "esperar"),
            fixture.orden,
            "ni un segundo envio ni el paso 2",
        )
    }

    @Test
    fun `un segundo fallo tras el reenvio es TERMINAL y no hay tercer intento`() {
        val fixture = Fixture(
            veredictos = listOf(falloNoConectado(), falloNoConectado(1)),
            esperaDevuelve = true,
        )

        val fallo = assertFailsWith<ContactExchangeException> {
            runBlocking { fixture.bootstrap.ejecutar() }
        }

        assertEquals(SignalDeliveryCodes.NOT_CONNECTED, fallo.code, "mismo codigo estable, nunca REFUSED")
        assertEquals(2, fixture.enviados.size, "UN solo reenvio: nada de tercer intento")
        assertEquals(1, fixture.esperas.size, "una espera, no dos")
        assertEquals(0, fixture.sesiones, "sin Delivered no hay PREKEY")
    }

    @Test
    fun `un ONLINE duplicado o tardio no dispara un reenvio extra`() {
        // La espera puede despertar con un ONLINE repetido; lo que no puede es
        // habilitar un SEGUNDO reenvio. El tope es por el contador del
        // bootstrap, no por el numero de avisos del transporte (AB-R8).
        val fixture = Fixture(
            veredictos = listOf(falloNoConectado(), falloNoConectado(), entregado()),
            esperaDevuelve = true,
        )

        assertFailsWith<ContactExchangeException> {
            runBlocking { fixture.bootstrap.ejecutar() }
        }

        assertEquals(2, fixture.enviados.size, "segundo fallo = terminal: no se llega al tercer guion")
        assertEquals(1, fixture.esperas.size, "un unico ciclo de espera")
    }

    @Test
    fun `cancelar durante la espera de re-auth NO abre sesion`() {
        val fixture = Fixture(
            veredictos = listOf(falloNoConectado()),
            esperaLanza = CancellationException("trabajo cancelado"),
        )

        assertFailsWith<CancellationException> {
            runBlocking { fixture.bootstrap.ejecutar() }
        }

        assertEquals(1, fixture.enviados.size, "no se reenvia tras cancelar")
        assertEquals(0, fixture.sesiones, "ni se abre la sesion: CancellationException atraviesa la secuencia")
    }

    @Test
    fun `si el portador propio no se puede exportar, falla con SU codigo`() {
        val fixture = Fixture(
            veredictos = listOf(entregado()),
            falloExportacion = IllegalStateException("material incoherente"),
        )

        val fallo = assertFailsWith<ContactExchangeException> {
            runBlocking { fixture.bootstrap.ejecutar() }
        }
        assertEquals(ContactBootstrap.CODE_EXPORT_FAILED, fallo.code)
        assertEquals(0, fixture.enviados.size, "ni se intenta enviar nada")
    }

    @Test
    fun `un portador vacio no se envia`() {
        val fixture = Fixture(portador = ByteArray(0), veredictos = listOf(entregado()))

        val fallo = assertFailsWith<ContactExchangeException> {
            runBlocking { fixture.bootstrap.ejecutar() }
        }
        assertEquals(ContactBootstrap.CODE_EXPORT_FAILED, fallo.code)
        assertEquals(0, fixture.enviados.size, "un portador vacio no se manda al rele")
    }
}

/**
 * Los codigos del rele, escritos aqui para no atar el test de la app al
 * artefacto de `km-transport`.
 *
 * Son los MISMOS strings que usa `com.km.transport.SignalDelivery`: si los dos
 * se movieran, el test pasaria con un codigo que la app nunca emite. Por eso
 * se escriben LITERALMENTE aqui, y no se importan: el valor del test es
 * exactamente que el codigo de la app es este texto.
 */
object SignalDeliveryCodes {
    const val PEER_OFFLINE: String = "CONTACT_EXCHANGE_PEER_OFFLINE"
    const val NOT_CONNECTED: String = "CONTACT_EXCHANGE_NOT_CONNECTED"
    const val REFUSED: String = "CONTACT_EXCHANGE_REFUSED"
    const val FAILED: String = "CONTACT_EXCHANGE_FAILED"
}

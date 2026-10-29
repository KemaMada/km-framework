package com.example.keymessage.ui

import com.example.keymessage.contact.VerifiedContact
import com.example.keymessage.ui.contact.SNIPPET_MAX
import com.example.keymessage.ui.contact.UltimoMensaje
import com.example.keymessage.ui.contact.horaResumen
import com.example.keymessage.ui.contact.lineaIdentidad
import com.example.keymessage.ui.contact.mapearResumenes
import com.example.keymessage.ui.contact.vistaPrevia
import com.km.model.IdentityId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F2 — La fila de dos lineas: alias UNA vez + snippet/hora del ultimo mensaje.
 *
 * ## QUE AFIRMA
 *
 *  - el alias aparece UNA SOLA VEZ (nunca huella + id juntos: el roce que dejo
 *    verify #368/#381);
 *  - sin alias se recurre a un ID CORTO de la raiz, no a la huella duplicada;
 *  - sin ultimo mensaje la fila dice "sin mensajes" (snippet/hora `null`), nunca
 *    una hora inventada;
 *  - el snippet se acota y colapsa saltos de linea;
 *  - la hora es `HH:mm` si es de HOY y `dd/MM` si es de antes, con reloj
 *    inyectado (sin esto no se podria probar en JVM).
 *
 * ## POR QUE FUNCIONES PURAS
 *
 * Este modulo no tiene `ComposeTestRule`: la fila no se puede pintar en un test
 * de JVM. El mapeo es una funcion, y una funcion se prueba.
 */
class ResumenContactoTest {

    private fun contacto(raiz: String, alias: String = ""): VerifiedContact =
        VerifiedContact(
            identityRoot = IdentityId(raiz),
            subjectDeviceId = "dispositivo",
            subjectSigningKey = "clave",
            bundleText = "portador",
            displayName = alias,
        )

    private fun ultimo(peer: String, texto: String, ts: Long, mio: Boolean = false): UltimoMensaje =
        UltimoMensaje(peerId = peer, texto = texto, timestamp = ts, esMio = mio)

    // ===================================================================
    // 1. El alias aparece UNA sola vez
    // ===================================================================

    @Test
    fun `la linea de identidad es el alias cuando existe`() {
        assertEquals("Ana", lineaIdentidad(contacto("a".repeat(64), alias = "Ana")))
    }

    @Test
    fun `sin alias la linea es un ID corto de la raiz, NO la huella`() {
        val raiz = "0123456789abcdef".repeat(4)
        val linea = lineaIdentidad(contacto(raiz))

        assertEquals(raiz.take(12), linea, "sin alias se usa un ID corto de la raiz")
        assertTrue(linea.length <= 12, "y es CORTO: la identidad completa no cabe en la fila")
    }

    @Test
    fun `control negativo el alias NO se duplica con la huella`() {
        // El defecto del roce: fila con la huella repetida. La linea de identidad
        // es UNA cosa: el alias O el ID corto, nunca los dos.
        val raiz = "0123456789abcdef".repeat(4)
        val linea = lineaIdentidad(contacto(raiz, alias = "Ana"))

        assertEquals("Ana", linea)
        assertTrue(!linea.contains(raiz.take(12)), "el alias no lleva la raiz pegada")
        assertTrue(!linea.contains(raiz.take(16)), "ni la huella")
    }

    // ===================================================================
    // 2. La fila sin mensajes NO inventa hora
    // ===================================================================

    @Test
    fun `un contacto sin ultimo mensaje deja snippet y hora en null`() {
        val raiz = "c".repeat(64)
        val fila = mapearResumenes(listOf(contacto(raiz, alias = "Beto")), emptyMap()).single()

        assertEquals("Beto", fila.alias)
        assertNull(fila.snippet, "sin mensajes no hay snippet")
        assertNull(fila.hora, "y NO hay hora inventada")
    }

    @Test
    fun `con ultimo mensaje la fila lleva su snippet y su hora`() {
        val raiz = "d".repeat(64)
        val ahora = 1_700_000_000_000L
        val fila = mapearResumenes(
            contactos = listOf(contacto(raiz, alias = "Cira")),
            ultimos = mapOf(raiz to ultimo(raiz, "hola que tal", ahora - 1000)),
            ahora = ahora,
        ).single()

        assertEquals("Cira", fila.alias)
        assertEquals("hola que tal", fila.snippet)
        assertTrue(fila.hora != null && fila.hora.contains(":"), "la hora de hoy es HH:mm")
    }

    @Test
    fun `el ultimo mensaje se resuelve por la RAIZ del par`() {
        // Dos contactos con mensajes distintos: cada fila coge el suyo por raiz.
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val ahora = 1_700_000_000_000L
        val filas = mapearResumenes(
            contactos = listOf(contacto(a, "Ana"), contacto(b, "Beto")),
            ultimos = mapOf(
                a to ultimo(a, "para Ana", ahora),
                b to ultimo(b, "para Beto", ahora),
            ),
            ahora = ahora,
        )

        assertEquals("para Ana", filas[0].snippet)
        assertEquals("para Beto", filas[1].snippet)
    }

    // ===================================================================
    // 3. El snippet
    // ===================================================================

    @Test
    fun `el snippet colapsa saltos de linea y no es un parrafo`() {
        assertEquals("primera segunda", vistaPrevia("primera\nsegunda"))
        assertEquals("a  b", vistaPrevia("  a\r\nb  "), "recorta los bordes; los saltos dejan un hueco")
    }

    @Test
    fun `el snippet se acota con elipsis`() {
        val largo = "x".repeat(SNIPPET_MAX + 50)
        val corto = vistaPrevia(largo)

        assertEquals(SNIPPET_MAX + 1, corto.length, "tope mas el caracter de elipsis")
        assertTrue(corto.endsWith("…"), "y termina en elipsis")
    }

    @Test
    fun `un mensaje vacio no produce snippet`() {
        assertEquals("", vistaPrevia(""), "no se inventa texto")
    }

    // ===================================================================
    // 4. La hora
    // ===================================================================

    @Test
    fun `una hora de HOY sale como HH-mm`() {
        val ahora = 1_700_000_000_000L
        val hoy = ahora - 60_000L

        val hora = horaResumen(hoy, ahora)

        assertTrue(Regex("^\\d{2}:\\d{2}$").matches(hora), "HH:mm, no una fecha: fue $hora")
    }

    @Test
    fun `una hora de ANTES sale como dd-MM`() {
        val ahora = 1_700_000_000_000L
        val antes = ahora - 3L * 24 * 60 * 60 * 1000

        val hora = horaResumen(antes, ahora)

        assertTrue(Regex("^\\d{2}/\\d{2}$").matches(hora), "dd/MM para otro dia: fue $hora")
    }

    @Test
    fun `control negativo un timestamp no positivo NO produce hora inventada`() {
        assertEquals("", horaResumen(0L, 1_700_000_000_000L))
        assertEquals("", horaResumen(-5L, 1_700_000_000_000L))
    }

    // ===================================================================
    // 5. Pureza y orden
    // ===================================================================

    @Test
    fun `el mapeo conserva el orden del libro y el numero`() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val filas = mapearResumenes(listOf(contacto(b, "Beto"), contacto(a, "Ana")), emptyMap())

        assertEquals(listOf("Beto", "Ana"), filas.map { it.alias }, "el orden es el del libro")
        assertEquals(listOf(b, a), filas.map { it.id }, "id = raiz")
    }

    @Test
    fun `un libro vacio mapea a una lista vacia`() {
        assertEquals(emptyList(), mapearResumenes(emptyList(), emptyMap()))
    }
}

package com.example.keymessage.boot

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F5 — Las FASES del arranque son REALES: avanzan con un EVENTO, no con tiempo.
 *
 * ## QUE AFIRMA
 *
 *  - una fase empieza PENDIENTE y NO llega a HECHA sin un evento;
 *  - HECHA mide una duracion con reloj INYECTADO (sin dispositivo);
 *  - un FALLO lleva su codigo real en la nota;
 *  - la fila/la franja NO llevan `%` ni barra: nada de un 100% inventado;
 *  - ni la nota ni la franja llevan secretos (nemotecnico/frase).
 *
 * ## POR QUE RELOJ INYECTADO
 *
 * [ArranqueTraza] recibe `ahora: () -> Long`. En el test se inyecta un contador
 * que NO depende del `SystemClock`: asi la duracion es una afirmacion sobre la
 * logica, no sobre el dispositivo.
 */
class ArranqueFasesTest {

    private fun traza(reloj: () -> Long): ArranqueTraza = ArranqueTraza(ahora = reloj)

    private fun ArranqueTraza.hito(fase: FaseArranque): HitoFase =
        hitos.value.single { it.fase == fase }

    @Test
    fun `todas las fases empiezan PENDIENTES y sin tiempo medido`() {
        val t = traza { 0L }

        assertEquals(FaseArranque.entries.toList(), t.hitos.value.map { it.fase }, "el orden es el del enum")
        t.hitos.value.forEach { hito ->
            assertEquals(EstadoFase.PENDIENTE, hito.estado, "${hito.fase} arranca pendiente")
            assertNull(hito.inicioMs, "${hito.fase} no tiene inicio medido")
            assertNull(hito.finMs, "${hito.fase} no tiene fin medido")
        }
    }

    @Test
    fun `control negativo NINGUNA fase llega a HECHA sin un evento`() {
        val t = traza { 0L }

        // Sin llamar a `completar`, ninguna fase puede estar HECHA por el mero
        // paso del tiempo: esto es lo que cierra el "100% falso".
        assertTrue(
            t.hitos.value.none { it.estado == EstadoFase.HECHA },
            "sin evento no hay HECHA",
        )
    }

    @Test
    fun `completar mide una duracion con el reloj inyectado`() {
        var ahora = 100L
        val t = traza { ahora }

        t.iniciar(FaseArranque.CONFIG)
        ahora = 175L
        t.completar(FaseArranque.CONFIG, nota = "origen=build")

        val hito = t.hito(FaseArranque.CONFIG)
        assertEquals(EstadoFase.HECHA, hito.estado)
        assertEquals(75L, duracionMs(hito), "175 - 100")
        assertEquals("origen=build", hito.nota)
    }

    @Test
    fun `iniciar es idempotente y no reinicia el tiempo`() {
        var ahora = 100L
        val t = traza { ahora }

        t.iniciar(FaseArranque.RELAY)
        ahora = 150L
        t.iniciar(FaseArranque.RELAY) // segundo intento: no reinicia

        assertEquals(100L, t.hito(FaseArranque.RELAY).inicioMs, "el segundo `iniciar` no mueve el inicio")
    }

    @Test
    fun `un fallo lleva su codigo real en la nota`() {
        var ahora = 10L
        val t = traza { ahora }

        t.iniciar(FaseArranque.RELAY)
        ahora = 40L
        t.fallar(FaseArranque.RELAY, "sin conexion a ningun rele: 2")

        val hito = t.hito(FaseArranque.RELAY)
        assertEquals(EstadoFase.FALLIDA, hito.estado)
        assertEquals("sin conexion a ningun rele: 2", hito.nota, "el codigo real, no un 'fallo' generico")
        assertEquals(30L, duracionMs(hito))
    }

    @Test
    fun `duracionMs es null cuando NO se midio y nunca negativa`() {
        assertNull(duracionMs(HitoFase(FaseArranque.AUTH, EstadoFase.PENDIENTE)), "sin inicio no hay duracion")
        assertNull(
            duracionMs(HitoFase(FaseArranque.AUTH, EstadoFase.EN_CURSO, inicioMs = 5L)),
            "sin fin tampoco",
        )
        assertEquals(
            0L,
            duracionMs(HitoFase(FaseArranque.AUTH, EstadoFase.HECHA, inicioMs = 50L, finMs = 10L)),
            "un reloj que retrocede no produce una duracion negativa",
        )
    }

    // ===================================================================
    // La forma: SIN porcentajes, SIN secretos
    // ===================================================================

    @Test
    fun `ni la fila ni la franja llevan porcentaje o barra`() {
        val hitos = listOf(
            HitoFase(FaseArranque.CONFIG, EstadoFase.HECHA, 0L, 12L, "origen=build"),
            HitoFase(FaseArranque.RELAY, EstadoFase.EN_CURSO, 12L, null),
            HitoFase(FaseArranque.AUTH, EstadoFase.PENDIENTE),
        )

        val filas = hitos.joinToString("\n") { filaFase(it) }
        val franja = ArranqueTraza.franja(hitos)

        assertTrue(!filas.contains("%"), "una fase no se pinta en porcentaje: $filas")
        assertTrue(!franja.contains("%"), "la franja tampoco: $franja")
        assertTrue(!filas.contains("100"), "ni se escribe el 100% falso")
    }

    @Test
    fun `la franja marca el estado de cada fase sin inventar progreso`() {
        val hitos = listOf(
            HitoFase(FaseArranque.CONFIG, EstadoFase.HECHA),
            HitoFase(FaseArranque.IDENTIDAD, EstadoFase.EN_CURSO),
            HitoFase(FaseArranque.NUCLEO, EstadoFase.PENDIENTE),
            HitoFase(FaseArranque.RELAY, EstadoFase.FALLIDA),
        )

        val franja = ArranqueTraza.franja(hitos)

        assertTrue(franja.contains("CONFIG+"), "HECHA se marca con `+`: $franja")
        assertTrue(franja.contains("IDENTIDAD>"), "EN_CURSO con `>`")
        assertTrue(franja.contains("NUCLEO·"), "PENDIENTE con `·`")
        assertTrue(franja.contains("RELAY!"), "FALLIDA con `!`")
    }

    @Test
    fun `ni la nota ni la franja llevan secretos (nemotecnico o frase)`() {
        // El contrato: la traza NUNCA pinta material de la frase de recuperacion.
        // Se inyectan notas legitimas y se afirma que no hay tokens de secreto.
        val t = traza { 0L }
        t.iniciar(FaseArranque.IDENTIDAD, nota = "cargada")
        t.completar(FaseArranque.IDENTIDAD, nota = "cargada")

        val texto = t.hitos.value.joinToString(" ") { "${it.fase} ${it.nota}" } +
            " " + ArranqueTraza.franja(t.hitos.value)

        listOf("frase", "mnemonic", "seed", "secret", "nemotecnico").forEach { token ->
            assertTrue(
                !texto.lowercase().contains(token),
                "la traza no puede contener '$token': fue $texto",
            )
        }
    }

    @Test
    fun `completar sin iniciar mide desde el momento del completado`() {
        // Un `completar` sin `iniciar` no deja la duracion en null: usa el mismo
        // instante como inicio y fin, que es una duracion REAL (0ms), no una
        // ausencia. La UI lo pinta como "0ms", no como "sin medicion".
        var ahora = 42L
        val t = traza { ahora }
        ahora = 99L
        t.completar(FaseArranque.CONTACTOS, nota = "0 en memoria")

        assertEquals(0L, duracionMs(t.hito(FaseArranque.CONTACTOS)), "inicio = fin = 99")
    }

    // ===================================================================
    // R4 — una fase NO medida se dice "sin medicion"
    // ===================================================================

    @Test
    fun `la tabla de una fase no medida dice sin medicion`() {
        val pendiente = filaFase(HitoFase(FaseArranque.AUTH, EstadoFase.PENDIENTE))
        val enCurso = filaFase(HitoFase(FaseArranque.RELAY, EstadoFase.EN_CURSO, inicioMs = 5L))

        listOf(pendiente, enCurso).forEach { fila ->
            assertTrue(
                fila.contains("sin medicion"),
                "R4 exige el literal 'sin medicion' cuando no hay metrica: $fila",
            )
            assertTrue(!fila.contains("--"), "el guion del diseno NO es una metrica: $fila")
            assertTrue(!fila.contains("%"), "ni un porcentaje: $fila")
        }
    }
}

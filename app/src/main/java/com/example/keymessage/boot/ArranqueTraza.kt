package com.example.keymessage.boot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * F5 — Las FASES REALES del arranque.
 *
 * ## QUE MIDE Y QUE NO
 *
 * Mide hitos que de verdad ocurren: configuracion, identidad, nucleo (crear y
 * arrancar), enlace al relay, autenticacion y contactos. NO mide latencias
 * inventadas ni pinta porcentajes: una barra de progreso que no corresponde a un
 * evento es una mentira amable, y aqui se evita por diseno.
 */
enum class FaseArranque(val etiqueta: String) {
    CONFIG("CONFIG"),
    IDENTIDAD("IDENTIDAD"),
    NUCLEO("NUCLEO"),
    RELAY("RELAY"),
    AUTH("AUTH"),
    CONTACTOS("CONTACTOS"),
}

/** El estado de UNA fase. */
enum class EstadoFase(val etiqueta: String) {
    PENDIENTE("PENDIENTE"),
    EN_CURSO("EN CURSO"),
    HECHA("HECHA"),
    FALLIDA("FALLO"),
}

/**
 * F5 — Un HITO de la traza: una fase, su estado, y los tiempos MEDIDOS.
 *
 * [inicioMs] y [finMs] son `null` cuando no se han medido todavia: un `0` seria
 * un tiempo real (el arranque) y confundirlo con "no medido" seria una
 * afirmacion falsa.
 */
data class HitoFase(
    val fase: FaseArranque,
    val estado: EstadoFase,
    /** `elapsedRealtime` cuando empezo, o `null` si no se midio. */
    val inicioMs: Long? = null,
    /** `elapsedRealtime` cuando termino, o `null` si aun corre. */
    val finMs: Long? = null,
    /** Nota legible (conteos, codigos). Nunca secretos. */
    val nota: String? = null,
)

/**
 * F5 / R4 — La duracion de un hito, o `null` si NO se midio.
 *
 * Sin ambos extremos no hay duracion: devolver `0` seria "instantaneo", que es
 * una afirmacion que no se puede sostener. La UI pinta `sin medicion` en ese
 * caso (R4: el literal es "sin medicion", no un `-- ms`).
 */
fun duracionMs(hito: HitoFase): Long? {
    val inicio = hito.inicioMs ?: return null
    val fin = hito.finMs ?: return null
    // Monotona y no negativa: `elapsedRealtime` no deberia retroceder, pero si
    // lo hiciera una duracion negativa seria una metrica imposible de mostrar.
    return (fin - inicio).coerceAtLeast(0L)
}

/**
 * F5 / R4 — La linea de una fase para la tabla de DIAGNOSTICO.
 *
 * NO lleva `%` ni barra: dice el estado, la duracion SI fue medida
 * ([LineasBoot.SIN_MEDICION] si no), y la nota. Es una funcion pura para poder
 * afirmar "no hay porcentajes".
 */
fun filaFase(hito: HitoFase): String {
    val duracion = duracionMs(hito)?.let { "${it}ms" } ?: LineasBoot.SIN_MEDICION
    val nota = hito.nota?.takeIf { it.isNotBlank() }?.let { "  ($it)" } ?: ""
    return "${hito.fase.etiqueta.padEnd(10)} ${hito.estado.etiqueta.padEnd(9)} $duracion$nota"
}

/**
 * F5 — La traza de arranque, como `StateFlow`.
 *
 * ## RELOJ INYECTADO
 *
 * [ahora] es una lambda (por defecto `SystemClock.elapsedRealtime`, inyectable
 * en tests) para que las duraciones se puedan afirmar sin dispositivo. NUNCA se
 * usa `currentTimeMillis` para medir intervalos: el reloj de pared puede saltar.
 *
 * ## LAS FASES SOLO AVANZAN CON UN EVENTO
 *
 * No hay `marcarHecha` sin inicio, ni una fase que salte a HECHA porque haya
 * pasado el tiempo: cada transicion la dispara el codigo que HACE la cosa.
 * `CONTACTOS` en frio sin persistencia cuenta 0 y lo DICE en la nota.
 */
class ArranqueTraza(
    private val ahora: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {

    private val orden: List<FaseArranque> = FaseArranque.values().toList()

    private val _hitos = MutableStateFlow(
        orden.map { HitoFase(it, EstadoFase.PENDIENTE) },
    )
    val hitos: StateFlow<List<HitoFase>> = _hitos.asStateFlow()

    /** Marca el INICIO de [fase]. Idempotente: repetir no reinicia el tiempo. */
    fun iniciar(fase: FaseArranque, nota: String? = null) {
        _hitos.update { lista ->
            lista.map { hito ->
                if (hito.fase == fase && hito.estado == EstadoFase.PENDIENTE) {
                    hito.copy(estado = EstadoFase.EN_CURSO, inicioMs = ahora(), nota = nota ?: hito.nota)
                } else {
                    hito
                }
            }
        }
    }

    /** Marca el FIN de [fase]: HECHA con duracion medida. */
    fun completar(fase: FaseArranque, nota: String? = null) {
        _hitos.update { lista ->
            lista.map { hito ->
                if (hito.fase == fase) {
                    hito.copy(
                        estado = EstadoFase.HECHA,
                        inicioMs = hito.inicioMs ?: ahora(),
                        finMs = ahora(),
                        nota = nota ?: hito.nota,
                    )
                } else {
                    hito
                }
            }
        }
    }

    /** Marca [fase] como FALLIDA con su nota (un codigo real, nunca secretos). */
    fun fallar(fase: FaseArranque, nota: String) {
        _hitos.update { lista ->
            lista.map { hito ->
                if (hito.fase == fase) {
                    hito.copy(
                        estado = EstadoFase.FALLIDA,
                        inicioMs = hito.inicioMs ?: ahora(),
                        finMs = ahora(),
                        nota = nota,
                    )
                } else {
                    hito
                }
            }
        }
    }

    /**
     * F5 — La franja compacta que se pinta en INICIO.
     *
     * Es una funcion pura de la lista de hitos: la fase EN_CURSO se marca con un
     * cursor. Sin porcentajes, sin barras: solo el estado real de cada fase.
     */
    companion object {
        fun franja(hitos: List<HitoFase>): String = hitos.joinToString(" ") { hito ->
            when (hito.estado) {
                EstadoFase.PENDIENTE -> "${hito.fase.etiqueta}·"
                EstadoFase.EN_CURSO -> "${hito.fase.etiqueta}>"
                EstadoFase.HECHA -> "${hito.fase.etiqueta}+"
                EstadoFase.FALLIDA -> "${hito.fase.etiqueta}!"
            }
        }
    }
}

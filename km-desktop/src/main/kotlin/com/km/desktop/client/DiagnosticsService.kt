package com.km.desktop.client

import com.km.desktop.ui.LogLevel
import com.km.desktop.ui.LogRow
import com.km.desktop.util.DiagLog

/**
 * DIAGNOSTICO y LOGS para la pantalla, con la misma regla de no-fuga de G1.
 *
 * ## EL EXPORT SON SOLO LINEAS DE [DiagLog]
 *
 * No se compone un informe nuevo con datos del estado vivo: si el export
 * pudiera leer `Session`, alguien acabaria metiendo el endpoint, el alias o algo
 * peor. Lo que se exporta es EXACTAMENTE el buffer de `DiagLog`, que ya paso por
 * `redact()` y cuya API de dominio no acepta payload.
 *
 * ## LAS HORAS VIENEN DEL LOG, NO DEL REDUCER
 *
 * `DiagLog` estampa `yyyy-MM-dd HH:mm:ss` en cada linea. El reducer no puede
 * leer el reloj (es puro), asi que la hora entra por aqui como parte del texto.
 */
class DiagnosticsService(
    /** Las lineas del buffer, inyectable para el test del centinela. */
    private val source: () -> List<String> = { DiagLog.lines() },
) {

    /** El canal `[X]` del principio de una linea, si lo tiene. */
    private val canal = Regex("^\\[([A-Z]+)\\]")

    /**
     * Las lineas como filas de pantalla.
     *
     * `level` es INFO para todas: el buffer no guarda el nivel, y adivinarlo por
     * el texto seria inventar. La severidad visible esta en el propio mensaje
     * cuando importa; el nivel se conserva en el sumidero real.
     */
    fun rows(): List<LogRow> = source().map { linea ->
        LogRow(
            level = LogLevel.INFO,
            channel = canal.find(linea)?.groupValues?.get(1)?.let { "[$it]" },
            text = linea,
        )
    }

    /** El texto del export, filtrado por canal. `null` = todos. */
    fun exportText(filter: String? = null): String {
        val lineas = source().filter { filter == null || it.startsWith(filter) }
        // LF y no System.lineSeparator: el fichero de log es el mismo en Linux y
        // en Windows, y una exportacion que cambia de bytes por el SO complica
        // comparar dos trazas.
        return lineas.joinToString("\n") + "\n"
    }

    /** Las lineas del export filtrado. Para copiar por OSC 52. */
    fun exportLines(filter: String? = null): List<String> =
        source().filter { filter == null || it.startsWith(filter) }
}

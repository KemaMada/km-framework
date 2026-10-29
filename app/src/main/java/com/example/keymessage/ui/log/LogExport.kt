package com.example.keymessage.ui.log

/**
 * F5 — El filtro y el volcado del LogPanel. Funciones PURAS.
 *
 * ## POR QUE ESTAN FUERA DEL COMPOSABLE
 *
 * Porque no hay `ComposeTestRule` en este modulo: filtrar y serializar son la
 * logica que importa, y una funcion pura se prueba en JVM
 * ([com.example.keymessage.ui.LogExportTest]). El composable solo las llama.
 *
 * ## LA FUENTE ES `KMLog.lines()` Y SOLO `KMLog.lines()`
 *
 * El export NO re-captura de fuentes crudas. `KMLog` es no-fuga POR API
 * (`messageSent`/`messageReceived` no reciben payload, `truncar` acorta ids), y
 * `LogNoLeakTest` lo guarda. Re-leer de otro sitio abriria un camino de fuga
 * nuevo (spec R6.3). Aqui entran lineas ya formateadas y salen lineas.
 *
 * ## EL FILTRO NO ALTERA EL FORMATO
 *
 * Filtrar SELECCIONA lineas; no reescribe ni trunca la linea elegida. Un filtro
 * que "limpiara" la linea seria un segundo formato, y el registro de una
 * incidencia se volveria irreproducible.
 */
object LogExport {

    /** El canal "sin filtro": todo el buffer. */
    const val TODOS: String = "TODOS"

    /**
     * Las lineas de [lineas] cuyo canal es [canal].
     *
     * @param canal el token de canal (`"[RELAY]"`) o [TODOS]. Un canal
     *   INEXISTENTE —o vacio— no lanza: devuelve la lista vacia, porque "no hay
     *   lineas de ese canal" es un resultado valido y no un error de programa.
     */
    fun filtrarPorCanal(lineas: List<String>, canal: String): List<String> =
        if (canal == TODOS || canal.isBlank()) lineas
        else lineas.filter { it.contains(canal) }

    /**
     * El texto exportable de [lineas]: las lineas unidas por salto de linea.
     *
     * `joinToString("\n")` de una lista vacia da `""`: el export de un buffer
     * vacio es una cadena vacia, que es exactamente lo que hay.
     */
    fun textoExport(lineas: List<String>): String = lineas.joinToString("\n")
}

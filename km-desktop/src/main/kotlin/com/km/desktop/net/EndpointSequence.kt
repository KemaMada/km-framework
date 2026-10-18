package com.km.desktop.net

/**
 * El recorrido de bootstraps: en ORDEN, con failover, y sin bucles.
 *
 * ## QUE RESUELVE
 *
 * Que "el rele no responde" sea un hecho que el cliente puede seguir y no una
 * razon para rendirse. Los endpoints se prueban uno detras de otro en el orden
 * dado, y si todos fallan se lanza [BootstrapAllFailed] con la lista de los
 * PROBADOS —que es lo que el operador necesita para diagnosticar, y lo que un
 * mensaje generico no le da.
 *
 * ## POR QUE NO HAY REINTENTOS EN UN MISMO ENDPOINT
 *
 * Porque reintentar un endpoint caido es un bucle disfrazado: con una latencia de
 * conexion alta, dos endpoints caidos convierten un arranque en un minuto de
 * espera sin decir nada. Un endpoint que fallo ya fallo; el siguiente es el que
 * puede funcionar. El numero de intentos por endpoint es de quien lo maneja, no
 * de aqui.
 *
 * ## POR QUE LA EXCEPCION DICE QUE SE PROBARON
 *
 * Porque "no se pudo conectar" sin decir "a que" es la mitad del diagnostico. Con
 * la lista, el operador distingue "el DNS de `keymessage.xyz` no resuelve" de
 * "los tres estan caidos", que son fallos distintos con arreglo distinto.
 */
class EndpointSequence(
    endpoints: List<String>,
    /** Un intento sobre [endpoint]. Devuelve excepcion si fallo. */
    private val intentar: (String) -> Unit,
) {
    /** Los endpoints, en orden y sin repetidos. Nunca vacio. */
    val endpoints: List<String> = endpoints.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    init {
        require(this.endpoints.isNotEmpty()) {
            "EndpointSequence necesita al menos un endpoint: una lista vacia es una invariante rota, no un caso"
        }
    }

    /** Cuantos endpoints se han intentado. Para trazas. */
    var intentos: Int = 0
        private set

    /**
     * Recorre la lista y devuelve en cuanto uno funciona.
     *
     * @throws BootstrapAllFailed si TODOS fallan. La excepcion lleva la lista
     *   de los probados, en orden.
     */
    fun recorrer() {
        val probados = mutableListOf<String>()
        for (endpoint in endpoints) {
            intentos++
            probados += endpoint
            // Si NO lanza, este endpoint funciono y se devuelve aqui. El fallo
            // NO se acumula como excepcion suppressor: lo que importa al final
            // es QUE se probaron todos, no por que fallo el ultimo.
            val fallo = runCatching { intentar(endpoint) }.exceptionOrNull()
            if (fallo == null) return
        }
        throw BootstrapAllFailed(probados)
    }
}

/**
 * Ningun bootstrap de la lista respondio.
 *
 * @property tried los endpoints intentados, EN ORDEN y sin deduplicar de mas.
 */
class BootstrapAllFailed(
    val tried: List<String>,
) : RuntimeException(
    "ningun bootstrap respondio; se probaron ${tried.size}: ${tried.joinToString()}",
)

/** El mensaje de una [BootstrapAllFailed], para la pantalla y para el log. */
fun BootstrapAllFailed.resumen(): String =
    "BOOTSTRAP_ALL_FAILED probados=${tried.size} (${tried.joinToString()})"
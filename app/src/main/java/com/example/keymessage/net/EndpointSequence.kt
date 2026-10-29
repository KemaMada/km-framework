package com.example.keymessage.net

import com.example.keymessage.util.KMLog

/**
 * Todos los endpoints probados y ninguno funciono.
 *
 * ## POR QUE LLEVA LA LISTA
 *
 * Porque "no hay conexion a ningun rele" sin decir CUALES fallaron es el fallo
 * mas dificil de diagnosticar que existe en una app de reloj: el usuario ve un
 * mensaje, el operador ve un log vacio. Los endpoints probados van en la propia
 * excepcion y en el canal `[BOOTSTRAP_ALL_FAILED]`.
 */
class RelayEndpointsExhausted(val tried: List<String>) :
    IllegalStateException("se agotaron los endpoints de arranque: $tried")

/**
 * 3.2 — Failover ORDENADO sobre la lista de endpoints.
 *
 * ## QUE GARANTIZA
 *
 * Que el primer endpoint que falla NO se repite, que el siguiente SI se intenta,
 * y que agotarlos produce una excepcion que los nombra. Con un unico endpoint
 * escrito a mano no habia nada que ordenar, y con "reintentar el mismo" el
 * usuario esperaba un minuto para oír exactamente lo mismo.
 *
 * ## QUE NO HACE
 *
 * No decide cuando reintentar ni cada cuanto: el motor de p4 ya tiene su
 * `ReconnectPolicy`, y un segundo backoff aqui seria una segunda politica
 * peleandose con la primera.
 */
class EndpointSequence(private val endpoints: List<String>) {

    init {
        if (endpoints.isEmpty()) {
            throw IllegalArgumentException("una secuencia de endpoints vacia no tiene a donde ir")
        }
        endpoints.forEachIndexed { i, e ->
            if (e.isBlank()) {
                throw IllegalArgumentException(
                    "el endpoint en la posicion $i de $endpoints esta vacio: " +
                        "relaja la precedencia en vez de inventar un destino",
                )
            }
        }
    }

    /** Los endpoints, en orden de intento. */
    fun ordered(): List<String> = endpoints.toList()

    /**
     * Intenta [connect] con cada endpoint, en orden, hasta que uno funcione.
     *
     * @param connect devuelve exito o fallo POR ENDPOINT. El fallo es un dato
     *   (`Result`), no una excepcion: un rele que no responde es un resultado
     *   del intento, y el siguiente tiene que poder intentarlo. Una excepcion
     *   que se escape se trata como fallo de ESE endpoint, con su tipo en el
     *   log.
     * @return el valor del primer endpoint que tuvo exito.
     * @throws RelayEndpointsExhausted si TODOS fallan.
     */
    fun <T> connect(connect: (String) -> Result<T>): T {
        val probados = mutableListOf<String>()
        for (endpoint in endpoints) {
            KMLog.event("[RELAY]", "intentando $endpoint")
            // Una EXCEPCION tambien es un fallo de este endpoint, no de la
            // secuencia: si un motor lanzara al conectar, la app se quedaria sin
            // probar el rele siguiente, que es justo el rele de repuesto. Por eso
            // se envuelve, y por eso el fallo queda registrado con su tipo: tragarse
            // una excepcion sin dejarla escrita seria cambiar un crash visible por
            // un silencio.
            val outcome = runCatching { connect(endpoint) }
                .onFailure { KMLog.event("[RELAY]", "excepcion en $endpoint: ${it::class.simpleName}", KMLog.Level.WARN) }
                .getOrElse { Result.failure(it) }
            if (outcome.isSuccess) {
                KMLog.event("[RELAY]", "conectado a $endpoint")
                return outcome.getOrThrow()
            }
            probados += endpoint
            KMLog.event("[RELAY]", "fallo $endpoint: ${outcome.exceptionOrNull()?.message}")
        }
        KMLog.event("[BOOTSTRAP_ALL_FAILED]", "probados=$probados", KMLog.Level.ERROR)
        throw RelayEndpointsExhausted(probados.toList())
    }
}
package com.km.desktop.config

import com.km.transport.JvmRelayTransport

/**
 * Los bootstraps del cliente, en ORDEN, y de donde salen.
 *
 * ## POR QUE UNA LISTA Y NO UN ENDPOINT
 *
 * Porque un unico endpoint incrustado es un punto unico de fallo y, peor, es un
 * punto de control: quien puede cambiar el destino del cliente decide de whom ve
 * sus mensajes. Con una lista ORDENADA el cliente puede seguir operando cuando
 * el primero no responde, y el operador puede cambiar el destino sin tocar el
 * binario.
 *
 * ## LA PRECEDENCIA, Y POR QUE ESTA ES
 *
 *     1. linea de comandos (`--bootstrap`, repetible)
 *     2. entorno (`KM_BOOTSTRAPS`, plural; `KM_BOOTSTRAP_URL`, singular)
 *     3. el default DEL MOTOR (`JvmRelayTransport.DEFAULT_ENDPOINT`)
 *
 * La de arriba gana porque es lo que el operador ha escrito en ESTA invocacion.
 * El default de km-transport se LEE, no se copia: escribirlo aqui seria una
 * segunda fuente de verdad que divergiria en cuanto el motor cambiase el suyo.
 *
 * ## POR QUE UNA ENTRADA EN BLANCO ES UN ERROR
 *
 * Porque `--bootstrap ""` es casi siempre un error de tecleo o un `$(...)` que
 * se quedo vacio, y "continuar con los demas" es un comportamiento de adivinar
 * en el que el usuario no ha pensado. Devolver una lista vacia seria peor:
 * el cliente no conectaria con nadie y no diria por que. Aqui falla al
 * arrancar, nombrando la fuente, que es justo cuando el operador puede verlo.
 */
object BootstrapConfig {

    /** Variable de entorno con UN endpoint. Se conserva por compatibilidad. */
    const val ENV_UNO: String = "KM_BOOTSTRAP_URL"

    /** Variable de entorno con VARIOS endpoints, separados por coma. */
    const val ENV_MUCHOS: String = "KM_BOOTSTRAPS"

    /** Separa endpoints en las variables de entorno. */
    const val SEPARADOR: String = ","

    /**
     * El valor por defecto, LEIDO del motor y no escrito aqui.
     *
     * Es una `val` y no un `const val` a proposito: `JvmRelayTransport.DEFAULT_ENDPOINT`
     * es un `const` de Kotlin y se puede leer en tiempo de compilacion, pero
     * pasarlo por aqui mantiene la dependencia en un solo punto visible.
     */
    val DEFAULT: List<String> get() = listOf(JvmRelayTransport.DEFAULT_ENDPOINT)

    /**
     * Resuelve la lista de bootstraps.
     *
     * @param cli lo que llego de `--bootstrap`, en orden.
     * @param entorno el entorno del proceso, inyectable para poder probarlo sin
     *   tocar el entorno real de quien lanza la suite.
     * @param porDefecto la lista de fallback. Por defecto, la del motor.
     * @return una lista NO VACIA, en orden y sin repetidos.
     * @throws IllegalArgumentException si la fuente que gana no deja ninguna
     *   entrada utilizable. El mensaje nombra la fuente, porque hay tres.
     */
    fun resolve(
        cli: List<String> = emptyList(),
        entorno: Map<String, String> = System.getenv(),
        porDefecto: List<String> = DEFAULT,
    ): List<String> {
        if (cli.isNotEmpty()) return depurar(cli, "--bootstrap")

        val muchos = entorno[ENV_MUCHOS]
        if (!muchos.isNullOrBlank()) {
            return depurar(muchos.split(SEPARADOR), ENV_MUCHOS)
        }

        val uno = entorno[ENV_UNO]
        if (!uno.isNullOrBlank()) {
            return depurar(listOf(uno), ENV_UNO)
        }

        require(porDefecto.isNotEmpty()) {
            "no hay ningun bootstrap: ni --bootstrap, ni $ENV_MUCHOS, ni $ENV_UNO, " +
                "y la lista de default esta vacia"
        }
        return porDefecto.toList()
    }

    /**
     * Recorta, rechaza entradas EN BLANCO y quita repetidos conservando el orden.
     *
     * @throws IllegalArgumentException si alguna entrada queda vacia tras
     *   recortar. Un endpoint en blanco no es "ninguno": es un error de
     *   invocacion, y ocultarlo deja al cliente sin destino sin decirlo.
     */
    private fun depurar(entradas: List<String>, fuente: String): List<String> {
        val limpias = entradas.map { it.trim() }
        val vacias = limpias.withIndex().filter { it.value.isEmpty() }.map { it.index }
        require(vacias.isEmpty()) {
            "$fuente tiene una entrada EN BLANCO en la posicion ${vacias.joinToString(", ")}: " +
                "escribe el endpoint entero, o quita el argumento"
        }
        return limpias.distinct()
    }
}
package com.example.keymessage.boot

import com.example.keymessage.ui.components.etiquetaConexion
import com.km.protocol.ConnectionState

/**
 * R3 / R4 — El TEXTO del overlay de arranque, como funcion PURA.
 *
 * ## POR QUE PURA Y FUERA DEL COMPOSABLE
 *
 * Porque este modulo no tiene `ComposeTestRule`: lo que se pinta dentro de un
 * `@Composable` no se puede afirmar en JVM. El contrato del overlay —entorno
 * real, fases desde eventos, "sin medicion" cuando no hay metrica, ni un `%`—
 * es una transformacion de datos, y una transformacion se prueba.
 *
 * ## QUE DICE CADA LINEA
 *
 *  1. tres lineas de ENTORNO (`SISTEMA` / `APP` / `RELAY`), todas con datos
 *     reales: API/release/ABI del dispositivo, version de `PackageManager`, y el
 *     endpoint configurado con su origen;
 *  2. una linea por FASE, con su estado y su duracion MEDIDA (o [SIN_MEDICION]);
 *  3. una linea de ENLACE con [etiquetaConexion], la MISMA que usa DIAGNOSTICO
 *     y que HOME al cerrar: `RECONECTANDO` / `SIN CONEXION <codigo>` reales.
 *
 * ## LO QUE NO APARECE NUNCA
 *
 *  - `%` ni `100%`: no hay barra de progreso porque no hay un total medido;
 *  - la semilla, el mnemonic, sus palabras, ni claves/tokens;
 *  - un endpoint inventado: sin configuracion se dice [BootEnvironment.SIN_CONFIGURAR].
 */
object LineasBoot {

    /** Lo que se pinta cuando una fase NO tiene metrica. Literal de R4. */
    const val SIN_MEDICION: String = "sin medicion"

    /** El numero de lineas de entorno previas a las fases. */
    const val LINEAS_ENTORNO: Int = 3

    /**
     * Las tres lineas de entorno real.
     *
     * El endpoint nulo NO se sustituye por `127.0.0.1` ni por una URL de
     * ejemplo: se dice "sin configurar", que es lo que la app sabe.
     */
    fun lineaEntorno(env: BootEnvironment): List<String> = listOf(
        "SISTEMA  API ${env.api}  ${env.release}  ${env.abi}",
        "APP      ${env.appVersion} (${env.versionCode})",
        "RELAY    ${env.endpoint ?: "sin configurar"}  origen=${env.endpointSource}",
    )

    /**
     * Una fase: etiqueta, estado, duracion medida o [SIN_MEDICION], y su nota.
     *
     * Sin `%` y sin barra: la duracion es un numero de `elapsedRealtime`, no un
     * porcentaje de un total que nadie midio.
     */
    fun lineaFase(hito: HitoFase): String {
        val duracion = duracionMs(hito)?.let { "${it}ms" } ?: SIN_MEDICION
        val nota = hito.nota?.takeIf { it.isNotBlank() }?.let { "  ($it)" } ?: ""
        return "${hito.fase.etiqueta}  ${hito.estado.etiqueta}  $duracion$nota"
    }

    /**
     * El texto completo del overlay.
     *
     * No espera a nadie: recibe el estado que hay AHORA. Si el relay esta
     * offline, la linea de enlace lo dice y las fases locales siguen su curso;
     * el overlay se cierra por tiempo (2 s) o por tap/OMITIR, nunca por relay.
     */
}

/**
 * R3/R4 — El texto completo del overlay: entorno + fases + enlace real.
 *
 * Top-level (y no solo miembro del objeto) porque es la INTERFAZ que fija el
 * diseno y la que llama el host del overlay; los helpers viven en [LineasBoot].
 */
fun lineasBoot(
    env: BootEnvironment,
    hitos: List<HitoFase>,
    estado: ConnectionState,
    error: String?,
): List<String> = buildList {
    addAll(LineasBoot.lineaEntorno(env))
    hitos.forEach { add(LineasBoot.lineaFase(it)) }
    add("ENLACE   ${etiquetaConexion(estado, env.endpoint, error)}")
}

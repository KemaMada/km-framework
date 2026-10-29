package com.example.keymessage.boot

import android.content.Context
import android.os.Build
import com.example.keymessage.config.ResolvedEndpoints

/**
 * R3 — El snapshot de entorno REAL que pinta el overlay de arranque.
 *
 * ## DE DONDE SALE CADA CAMPO
 *
 *  - [api] / [release] / [abi]: de `Build`, que es el dispositivo de verdad.
 *  - [appVersion] / [versionCode]: de `PackageManager`, porque este modulo NO
 *    activa `buildConfig` (solo `compose`): no hay `BuildConfig.VERSION_NAME`
 *    que leer, y leerlo de un `BuildConfig` generado seria otra fuente de
 *    verdad que puede faltar.
 *  - [endpoint] / [endpointSource]: del [ResolvedEndpoints] que la app YA
 *    resolvio para conectar (runtime > BuildConfig > default del motor). No se
 *    inventa una URL: si no hay lista, [endpoint] es `null` y el formateador
 *    dice "sin configurar".
 *
 * ## LO QUE NUNCA LLEVA
 *
 * Ni `127.0.0.1` (no es un endpoint configurado, es un placeholder de
 * desarrollo), ni API keys, ni montajes de kernel, ni la semilla. Esta clase
 * solo copia lo que el sistema y la configuracion dicen.
 *
 * ## POR QUE ES UN `data class` CON `capture()` APARTE
 *
 * Para que los tests de JVM puedan CONSTRUIR un entorno sin `Context` y
 * afirmar el formato. `capture()` es el unico punto que toca Android, y por eso
 * es el unico que no se prueba en JVM (se prueba en dispositivo).
 */
data class BootEnvironment(
    /** `Build.VERSION.SDK_INT`. */
    val api: Int,
    /** `Build.VERSION.RELEASE`, tal cual lo dice el sistema. */
    val release: String,
    /** La ABI primaria del dispositivo (`Build.SUPPORTED_ABIS[0]`). */
    val abi: String,
    /** `versionName` de la app segun `PackageManager`. */
    val appVersion: String,
    /** `versionCode` (long, API 28+) de la app. */
    val versionCode: Long,
    /** El endpoint REALMENTE configurado, o `null` si no hay ninguno. */
    val endpoint: String?,
    /** De donde salio [endpoint]: `RUNTIME_OVERRIDE`, `BUILD_CONFIG`, `DEFAULT`. */
    val endpointSource: String,
) {
    companion object {

        /**
         * El snapshot del proceso, con el dispositivo y la configuracion reales.
         *
         * `runCatching` alrededor de `PackageManager`: un `NameNotFoundException`
         * aqui no puede tumbar el arranque —significaria que la app no esta
         * instalada, y entonces no habria overlay—. Se dice "desconocida" en vez
         * de inventar una version.
         */
        fun capture(context: Context, endpoints: ResolvedEndpoints?): BootEnvironment {
            val info = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()

            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info?.longVersionCode ?: 0L
            } else {
                @Suppress("DEPRECATION")
                (info?.versionCode ?: 0).toLong()
            }

            return BootEnvironment(
                api = Build.VERSION.SDK_INT,
                release = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: "?",
                abi = Build.SUPPORTED_ABIS?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "?",
                appVersion = info?.versionName?.takeIf { it.isNotBlank() } ?: "desconocida",
                versionCode = versionCode,
                // `preferred` es el PRIMERO de la lista resuelta: el que el
                // arranque intenta antes que ninguno. No se sintetiza.
                endpoint = endpoints?.endpoints?.firstOrNull(),
                endpointSource = endpoints?.source?.name ?: SIN_CONFIGURAR,
            )
        }

        /** Lo que se dice cuando no hay endpoint: ni una URL inventada. */
        const val SIN_CONFIGURAR: String = "SIN_CONFIGURAR"
    }
}

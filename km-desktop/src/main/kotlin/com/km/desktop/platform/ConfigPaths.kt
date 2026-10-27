package com.km.desktop.platform

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Rutas de configuracion del escritorio, resueltas por PLATAFORMA.
 *
 * ## POR QUE AQUI Y NO EN `IdentityKeystore`
 *
 * Porque la ruta por defecto no es un detalle del almacen: la usan el almacen,
 * el directorio de claves de peers y, en G2/G3, el export de logs. Si cada uno
 * la resolviera por su cuenta, Linux seguiria funcionando y Windows guardaria
 * el almacen en un sitio distinto del que el diagnostico dice. Un unico punto
 * de resolucion evita esa deriva.
 *
 * ## LAS TRES FAMILIAS
 *
 * | Familia | Variable | Ruta |
 * |---|---|---|
 * | Linux/Unix | `XDG_CONFIG_HOME` | `$XDG_CONFIG_HOME/keymessage` |
 * | Linux/Unix (sin XDG) | `HOME` | `$HOME/.config/keymessage` |
 * | Windows | `APPDATA` | `%APPDATA%\keymessage` |
 * | Windows (sin APPDATA) | `USERPROFILE` | `%USERPROFILE%\AppData\Roaming\keymessage` |
 * | macOS | `HOME` | `~/Library/Application Support/keymessage` |
 *
 * No hay rutas absolutas de un SO escritas a mano: todo sale de variables de
 * entorno y se compone con [Path], que es lo que hace que el mismo artefacto
 * funcione en Linux y en Windows sin separadores fijos.
 *
 * ## POR QUE `osName` ES UN PARAMETRO
 *
 * Porque un test de JVM en Linux no puede cambiar el SO del proceso. Recibir el
 * nombre del SO (y el entorno) como parametros permite afirmar las tres ramas en
 * la misma maquina, que es la unica forma de que la rama de Windows no se
 * publique sin haber ejecutado nunca.
 */
object ConfigPaths {

    /** Carpeta de la aplicacion dentro del directorio de configuracion. */
    const val APP_DIRECTORY: String = "keymessage"

    /** Nombre del almacen de identidad dentro de [APP_DIRECTORY]. */
    const val IDENTITY_FILE_NAME: String = "identity.kmkey"

    /** Nombre del directorio de claves de firma de peers. */
    const val PEERS_FILE_NAME: String = "peers.json"

    /** El `os.name` del proceso, tolerante a que la propiedad no exista. */
    fun currentOsName(): String = System.getProperty("os.name").orEmpty()

    /**
     * Directorio de configuracion de KeyMessage para [osName].
     *
     * @throws IllegalStateException si no hay ninguna variable que permita
     *   resolver una ruta. No se inventa `~` ni un directorio temporal: guardar
     *   una identidad en un sitio que el usuario no controla es peor que fallar
     *   al arrancar y decirlo.
     */
    fun resolve(
        env: Map<String, String> = System.getenv(),
        osName: String = currentOsName(),
    ): Path = when (family(osName)) {
        Family.WINDOWS -> windows(env)
        Family.MAC -> mac(env)
        Family.POSIX -> posix(env)
    }.resolve(APP_DIRECTORY)

    /** Ruta del almacen de identidad. */
    fun identityPath(
        env: Map<String, String> = System.getenv(),
        osName: String = currentOsName(),
    ): Path = resolve(env, osName).resolve(IDENTITY_FILE_NAME)

    /** Ruta del directorio de claves de peers. */
    fun peersPath(
        env: Map<String, String> = System.getenv(),
        osName: String = currentOsName(),
    ): Path = resolve(env, osName).resolve(PEERS_FILE_NAME)

    /** Familia de sistema operativo, por NOMBRE y sin ejecutar nada. */
    internal fun family(osName: String): Family {
        val normalized = osName.lowercase()
        return when {
            normalized.contains("windows") -> Family.WINDOWS
            normalized.contains("mac") || normalized.contains("darwin") -> Family.MAC
            else -> Family.POSIX
        }
    }

    private fun windows(env: Map<String, String>): Path {
        val appData = env["APPDATA"]?.takeIf { it.isNotBlank() }
        if (appData != null) return Paths.get(appData)
        val profile = env["USERPROFILE"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "no hay APPDATA ni USERPROFILE: no se puede localizar el directorio de configuracion en Windows"
            )
        return Paths.get(profile, "AppData", "Roaming")
    }

    private fun mac(env: Map<String, String>): Path {
        val home = env["HOME"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "no hay HOME: no se puede localizar el directorio de configuracion en macOS"
            )
        return Paths.get(home, "Library", "Application Support")
    }

    private fun posix(env: Map<String, String>): Path {
        val xdg = env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() }
        if (xdg != null) return Paths.get(xdg)
        val home = env["HOME"]?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "no hay XDG_CONFIG_HOME ni HOME: no se puede localizar el directorio de configuracion"
            )
        return Paths.get(home, ".config")
    }

    internal enum class Family { WINDOWS, MAC, POSIX }
}

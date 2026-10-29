package com.example.keymessage.service

import com.km.protocol.ConnectionState

/**
 * 5.4 — El CONTRATO del servicio en segundo plano: identificadores y textos.
 *
 * ## POR QUE ESTOS TEXTOS ESTAN EN UNA CLASE Y NO EN EL SERVICIO
 *
 * Son lo que el usuario lee en la barra de notificaciones, que es la unica parte
 * de la app que ve cuando la pantalla esta cerrada. Si estuvieran dentro de
 * `KeyMessageService`, su unica forma de comprobarse seria desinstalar y mirar el
 * movil. Aqui son una FUNCION PURA: lo que dice "reconectando" y lo que dice
 * "conectado" se afirma en un test de JVM, sin `NotificationManager` y sin
 * emulador.
 *
 * ## POR QUE EL ESTADO DICE "RECONECTANDO" Y NO "CONECTADO"
 *
 * `arrancar()` es sincrono y espera al `AUTH_OK` con limite de 20 s. Durante
 * esos 20 s hay una notificacion visible y la app esta viva, pero no hay
 * sesion. Un texto que dijera "conectado" durante esa ventana seria una
 * afirmacion que la pantalla de estado todavia no puede sostener.
 */
object ServiceContract {

    /** Id del canal. Un canal y solo uno: el servicio es una sola cosa. */
    const val CHANNEL_ID: String = "keymessage.background"

    /** Id de la notificacion persistente. */
    const val NOTIFICATION_ID: Int = 1001

    /** Accion explicita del boton "Detener". */
    const val ACTION_STOP: String = "com.example.keymessage.action.STOP_BACKGROUND"

    /** Tipo de servicio en primer plano. */
    const val FOREGROUND_TYPE: Int = 1 // FOREGROUND_SERVICE_TYPE_DATA_SYNC

    /** La conexion se mantiene para recibir mensajes del relay: eso es `dataSync`. */
    const val FOREGROUND_TYPE_NAME: String = "dataSync"

    /** Lo que el usuario ve arriba del todo. */
    const val TITLE: String = "KeyMessage activo"

    /**
     * Texto del aviso, en funcion del estado REAL de la conexion.
     *
     * No hay ningun caso "no ha pasado nada": el servicio se arranca explicitamente
     * y mientras vive hay una conexion intentandose, asi que los tres estados de
     * [ConnectionState] tienen su propio texto.
     */
    fun text(state: ConnectionState, detail: String? = null): String {
        val base = when (state) {
            ConnectionState.ONLINE -> "Recibiendo mensajes del relay"
            ConnectionState.CONNECTING -> "Conectando con el relay"
            ConnectionState.AUTHENTICATING -> "Autenticando con el relay"
            ConnectionState.RECONNECTING -> "Reconectando con el relay"
            ConnectionState.DISCONNECTED -> "Sin conexion: reintentando"
        }
        return if (detail.isNullOrBlank()) base else "$base · $detail"
    }

    /** Texto que se pinta cuando el usuario pulsa "Detener". */
    const val STOPPED: String = "KeyMessage en segundo plano detenido"
}
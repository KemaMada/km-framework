package com.example.keymessage.ui.permisos

/**
 * 7.2 — POST_NOTIFICATIONS: cuando se pide y que se dice (API 33+).
 *
 * ## POR QUE ES UNA FUNCION PURA
 *
 * Porque la decision depende de DOS datos —nivel de API y si el permiso esta
 * concedido— y no de un dispositivo: en JVM se puede afirmar el estado completo
 * ([com.example.keymessage.ui.PermisosNotificacionesTest]) sin emulador. Lo que
 * NO se puede probar en JVM es el dialogo del sistema; eso queda como plan ADB.
 *
 * ## LA REGLA
 *
 *  - API < 33: el permiso NO existe como runtime. No se pide y no se avisa
 *    (pedirlo seria pedir un permiso que la app no necesita en ese nivel).
 *  - API >= 33 y concedido: nada que pedir ni que avisar.
 *  - API >= 33 y NO concedido: se explica, no se bloquea. El servicio
 *    foreground sigue siendo el dueno de la conexion; lo unico que falta es el
 *    aviso visible, y la UI lo DICE en vez de fingir que todo esta bien.
 */
object PermisosNotificaciones {

    /** Primer nivel de API con POST_NOTIFICATIONS como permiso runtime. */
    const val API_RUNTIME: Int = 33

    /** Hay que pedir el permiso (API 33+ y aun no concedido). */
    fun requierePermiso(api: Int, concedido: Boolean): Boolean =
        api >= API_RUNTIME && !concedido

    /**
     * El aviso estable que la UI pinta, o `null` si no hay nada que decir.
     *
     * El texto NO promete que la app funciona igual: dice lo unico cierto —que
     * el servicio sigue activo y que no habra aviso visible—.
     */
    fun mensaje(api: Int, concedido: Boolean): String? = when {
        !requierePermiso(api, concedido) -> null
        else -> "NOTIFICACIONES NO CONCEDIDAS - el servicio sigue activo sin avisos"
    }

    /** La etiqueta de la accion para pedirlo, o `null` si no aplica. */
    fun accion(api: Int, concedido: Boolean): String? =
        if (requierePermiso(api, concedido)) "[ PERMITIR NOTIFICACIONES ]" else null
}

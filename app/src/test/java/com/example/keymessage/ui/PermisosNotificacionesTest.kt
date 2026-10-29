package com.example.keymessage.ui

import com.example.keymessage.ui.permisos.PermisosNotificaciones
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 7.2 — POST_NOTIFICATIONS (API 33+), en estado PURO (verify #381 W3).
 *
 * ## EL DEFECTO QUE VIGILA
 *
 * La app declaraba POST_NOTIFICATIONS en el manifest y NUNCA lo pedia ni
 * explicaba una denegacion: en API 33+ el usuario no veia el dialogo y no habia
 * feedback. R7.2 exige manejo ESTABLE y aviso visible, sin romper el flujo.
 *
 * ## QUE SE PUEDE PROBAR EN JVM
 *
 * La REGLA —cuando se pide y que se dice— depende de `api` y `concedido`, no
 * del dispositivo. Eso es una funcion pura y se afirma aqui. El dialogo del
 * sistema y la degradacion real del servicio son plan ADB (API 33+ no
 * disponible en este entorno), documentado en `apply-progress`.
 *
 * ## REGLA QUE FIJA EL TEST
 *
 *  - API < 33: NO se pide (el permiso no existe como runtime) y NO se avisa.
 *  - API 33+ concedido: nada que hacer.
 *  - API 33+ no concedido: se explica y se ofrece pedirlo; NUNCA se bloquea.
 */
class PermisosNotificacionesTest {

    @Test
    fun `API 28 no pide ni avisa aunque no este concedido`() {
        val api = 28
        assertFalse(
            PermisosNotificaciones.requierePermiso(api, concedido = false),
            "en API < 33 POST_NOTIFICATIONS no es runtime: pedirlo seria pedir un permiso que no aplica",
        )
        assertNull(PermisosNotificaciones.mensaje(api, concedido = false))
        assertNull(PermisosNotificaciones.accion(api, concedido = false))
    }

    @Test
    fun `API 33 no concedido pide, explica y ofrece la accion`() {
        val api = 33
        assertTrue(PermisosNotificaciones.requierePermiso(api, concedido = false))
        assertEquals(
            "NOTIFICACIONES NO CONCEDIDAS - el servicio sigue activo sin avisos",
            PermisosNotificaciones.mensaje(api, concedido = false),
        )
        assertEquals(
            "[ PERMITIR NOTIFICACIONES ]",
            PermisosNotificaciones.accion(api, concedido = false),
        )
    }

    @Test
    fun `API 36 concedido no pide ni avisa`() {
        val api = 36
        assertFalse(PermisosNotificaciones.requierePermiso(api, concedido = true))
        assertNull(PermisosNotificaciones.mensaje(api, concedido = true))
        assertNull(PermisosNotificaciones.accion(api, concedido = true))
    }

    @Test
    fun `control negativo el mensaje NO promete que todo funciona igual`() {
        val mensaje = PermisosNotificaciones.mensaje(33, concedido = false)!!

        assertTrue(
            mensaje.contains("sin avisos"),
            "el aviso tiene que decir la consecuencia real, no un 'OK' generico: $mensaje",
        )
        assertTrue(
            mensaje.startsWith("NOTIFICACIONES NO CONCEDIDAS"),
            "el aviso tiene que declarar la DENEGACION, no esconderla: $mensaje",
        )
        assertFalse(
            mensaje.contains("TODO OK") || mensaje.contains("FUNCIONA IGUAL"),
            "un aviso de denegacion no puede afirmar que no pasa nada: $mensaje",
        )
    }

    @Test
    fun `control negativo la decision cambia con el nivel de API, no solo con el flag`() {
        // Misma entrada `concedido=false`: 32 no pide, 33 si. Si la funcion
        // ignorara el nivel de API (mutacion plausible), este test falla.
        assertFalse(PermisosNotificaciones.requierePermiso(32, concedido = false))
        assertTrue(PermisosNotificaciones.requierePermiso(33, concedido = false))
    }
}

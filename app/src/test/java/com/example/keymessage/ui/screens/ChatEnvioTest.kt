package com.example.keymessage.ui.screens

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C2 — El envio de la conversacion: BOTON e IME comparten UN camino.
 *
 * ## EL DEFECTO QUE VIGILA
 *
 * `TuiField` no exponia `keyboardActions`, asi que el IME Send se habia perdido
 * (verify #381 C2): el teclado no ofrecia "Enviar" y los imports de
 * `KeyboardOptions`/`KeyboardActions`/`ImeAction` en `ChatScreen` estaban
 * muertos. Ademas, el envio por boton aceptaba el toque con el campo vacio (no
 * hacia nada) en vez de mostrarse deshabilitado.
 *
 * ## QUE SE PUEDE PROBAR EN JVM
 *
 * [textoTrasEnviar] es la funcion que usan LOS DOS caminos. Se afirma:
 *  - vacio/blancos: NO se envia y el campo no cambia;
 *  - texto valido: se envia UNA vez, sin espacios de borde, y el campo se vacia;
 *  - el envio no se duplica si el IME y el boton disparasen (una llamada, un
 *    `onSend`).
 */
class ChatEnvioTest {

    @Test
    fun `un campo vacio no envia nada ni cambia el borrador`() {
        var enviados = 0
        val restante = textoTrasEnviar("", { enviados++ })

        assertEquals(0, enviados, "sin texto no hay envio")
        assertEquals("", restante)
    }

    @Test
    fun `solo espacios tampoco envia`() {
        var enviados = 0
        val restante = textoTrasEnviar("   \n  ", { enviados++ })

        assertEquals(0, enviados, "un borrador en blanco no es un mensaje")
        assertEquals("   \n  ", restante, "no se borra lo que el usuario escribio si no se envio")
    }

    @Test
    fun `un texto valido se envia una vez sin espacios de borde y vacia el campo`() {
        val enviados = mutableListOf<String>()
        val restante = textoTrasEnviar("  hola mundo  ", { enviados.add(it) })

        assertEquals(listOf("hola mundo"), enviados, "un solo envio, con el texto recortado")
        assertEquals("", restante, "el campo queda vacio en la MISMA llamada que envia")
    }

    @Test
    fun `control negativo reenviar la misma llamada no duplica el mensaje`() {
        // Simula "boton + IME disparando el mismo borrador": tras el primer
        // envio el campo quedo vacio, asi que el segundo intento no envia.
        val enviados = mutableListOf<String>()
        var campo = "hola"
        campo = textoTrasEnviar(campo, { enviados.add(it) })
        campo = textoTrasEnviar(campo, { enviados.add(it) })

        assertEquals(listOf("hola"), enviados, "el estado vacio corta el doble submit")
        assertTrue(campo.isEmpty())
    }
}

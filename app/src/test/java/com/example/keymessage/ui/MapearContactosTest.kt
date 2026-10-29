package com.example.keymessage.ui

import com.example.keymessage.contact.VerifiedContact
import com.km.model.IdentityId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El mapeo del libro al modelo que la lista pinta.
 *
 * ## QUE AFIRMA
 *
 * Que [mapearContactos] es una FUNCION PURA de nivel superior y que el modelo
 * que sale es el que la conversacion espera:
 *
 *  - `id` es la RAIZ (`identityRoot`), que es la clave de ruteo. NO es el
 *    `deviceId`: un contacto que rota de dispositivo sigue siendo la misma
 *    conversacion, y con el `deviceId` se partiria en dos.
 *  - `name` es la HUELLA corta, que es lo unico que se puede mostrar sin volcar
 *    la identidad entera en pantalla.
 *
 * ## POR QUE ESTA FUERA DEL COMPOSABLE
 *
 * Porque no hay `ComposeTestRule` en este modulo y no se va a meter uno. El
 * mapeo es una FUNCION, y una funcion se prueba. El guard estructural
 * `ContactListReactiveGuardTest` es el que ata que la UI la USE.
 */
class MapearContactosTest {

    /** Un contacto con la raiz que se le da, sin material criptografico. */
    private fun contacto(raiz: String, nombre: String = ""): VerifiedContact =
        VerifiedContact(
            identityRoot = IdentityId(raiz),
            subjectDeviceId = "dispositivo",
            subjectSigningKey = "clave",
            bundleText = "portador",
            displayName = nombre,
        )

    @Test
    fun `el id del contacto es su RAIZ y el nombre su huella`() {
        // Una identidad real tiene 64 hex: el pretesto de que la huella es un
        // TRUNCADO y no la identidad entera.
        val raiz = "a1b2c3d4".repeat(8)
        assertEquals(64, raiz.length, "el pretesto: una identidad completa son 64 hex")

        val mapeado = mapearContactos(listOf(contacto(raiz))).single()

        assertEquals(raiz, mapeado.id, "el id es la RAIZ, que es la clave de ruteo")
        assertEquals(
            raiz.take(16) + "...",
            mapeado.name,
            "el nombre es la huella corta, no la identidad entera",
        )
    }

    @Test
    fun `el nombre mostrado es la huella del contacto`() {
        val raiz = "0123456789abcdef".repeat(4)
        val original = contacto(raiz)

        val mapeado = mapearContactos(listOf(original)).single()

        assertEquals(original.fingerprint, mapeado.name, "el nombre ES la huella del contacto")
        assertTrue(mapeado.name.length < original.identityRoot.value.length, "y es mas corta que la identidad")
    }

    @Test
    fun `el mapeo conserva el ORDEN y el numero`() {
        val a = contacto("a".repeat(64))
        val b = contacto("b".repeat(64))
        val c = contacto("c".repeat(64))

        val mapeados = mapearContactos(listOf(b, a, c))

        assertEquals(3, mapeados.size, "no se pierde ningun contacto")
        assertEquals(
            listOf(b.identityRoot.value, a.identityRoot.value, c.identityRoot.value),
            mapeados.map { it.id },
            "y el orden es el del libro",
        )
    }

    @Test
    fun `un libro vacio mapea a una lista vacia`() {
        assertEquals(emptyList(), mapearContactos(emptyList()))
    }

    @Test
    fun `el mapeo es PURO y dos llamadas dan lo mismo`() {
        val raiz = "f".repeat(64)
        val entrada = listOf(contacto(raiz))

        assertEquals(mapearContactos(entrada), mapearContactos(entrada), "la misma entrada, la misma salida")
    }
}

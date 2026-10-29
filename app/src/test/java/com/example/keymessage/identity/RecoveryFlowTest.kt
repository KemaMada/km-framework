package com.example.keymessage.identity

import com.example.keymessage.recovery.RecoveryFlow
import com.example.keymessage.recovery.ResultadoRestauracion
import com.example.keymessage.recovery.Validacion
import com.km.mnemonic.MnemonicMotivo
import com.km.model.IdentityId
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 6.4 — El coordinador de recuperacion: lo que decide ANTES de escribir.
 *
 * ## LO QUE AFIRMA, Y POR QUE ESTOS CASOS Y NO OTROS
 *
 * Que [RecoveryFlow.restaurar] con una identidad local y sin confirmacion NO
 * escribe nada, no toca el ciclo de vida y deja la identidad intacta. Esa es la
 * unica garantia de la recuperacion que se puede perder de la peor manera: un
 * doble toque, un boton pegado donde el dedo ya iba a cancelar, y el usuario se
 * queda sin su identidad sin haber visto un aviso.
 *
 * Por eso el ciclo de vida es un doble que GRABA el orden de las llamadas y no
 * un mock que solo cuenta: "no escribir" tambien quiere decir "no parar el
 * socket", porque parar el motor sin guardar deja la app desconectada.
 */
class RecoveryFlowTest {

    private val factory = IdentityMaterialFactory()

    /** El doble del ciclo: graba el ORDEN, que es parte de la garantia. */
    private class CicloGrabador : RecoveryFlow.Ciclo {
        val llamadas = mutableListOf<String>()
        override fun parar() { llamadas += "parar" }
        override fun arrancar() { llamadas += "arrancar" }
        override fun regenerarClavesLocales() { llamadas += "regenerar" }
    }

    private fun semilla(distintiva: Int): ByteArray =
        ByteArray(32) { ((it * 13 + distintiva * 41) % 241 + 5).toByte() }

    private fun fraseDe(semilla: ByteArray): String =
        fraseDeRecuperacion(factory.fromSeed(semilla)).display()

    @Test
    fun `sin confirmar NO escribe y deja la identidad intacta`() {
        val original = factory.fromSeed(semilla(1))
        val store = InMemoryIdentityStore()
        store.save(original)
        store.setPhraseAcknowledged(true)
        val ciclo = CicloGrabador()
        val flow = RecoveryFlow(store, factory, ciclo)

        val validacion = flow.validar(fraseDe(semilla(2)))
        assertIs<Validacion.Aceptada>(validacion)

        val resultado = flow.restaurar(validacion, original.identityId, confirmado = false)

        assertIs<ResultadoRestauracion.NecesitaConfirmacion>(resultado)
        assertEquals(store.text, assertNotNull(store.load()).let { IdentityCodec.encode(it) })
        assertEquals(original.identityId.value, assertNotNull(store.load()).identityId.value)
        assertTrue(ciclo.llamadas.isEmpty(), "ni parar, ni regenerar, ni arrancar: ${ciclo.llamadas}")
        assertTrue(store.phraseAcknowledged(), "el aviso tampoco se toca si no se ha escrito nada")
    }

    @Test
    fun `confirmar sobrescribe en el orden parar, guardar, regenerar, arrancar`() {
        val original = factory.fromSeed(semilla(3))
        val nueva = factory.fromSeed(semilla(4))
        val store = InMemoryIdentityStore()
        store.save(original)
        val ciclo = CicloGrabador()
        val flow = RecoveryFlow(store, factory, ciclo)

        val validacion = flow.validar(fraseDe(semilla(4)))
        assertIs<Validacion.Aceptada>(validacion)
        assertEquals(nueva.identityId.value, validacion.identityId.value)

        val resultado = flow.restaurar(validacion, original.identityId, confirmado = true)

        assertIs<ResultadoRestauracion.Restaurada>(resultado)
        assertEquals(nueva.identityId.value, assertNotNull(store.load()).identityId.value)
        assertEquals(
            listOf("parar", "regenerar", "arrancar"),
            ciclo.llamadas,
            "parar ANTES de escribir y arrancar AL FINAL: si no, el rele firma con la identidad vieja",
        )
    }

    @Test
    fun `sin identidad local no hace falta confirmar`() {
        val store = InMemoryIdentityStore()
        val ciclo = CicloGrabador()
        val flow = RecoveryFlow(store, factory, ciclo)

        assertFalse(flow.hayIdentidadLocal(), "no hay nada que perder: no hay dialogo")
        val validacion = flow.validar(fraseDe(semilla(5)))

        val resultado = flow.restaurar(validacion, idActual = null, confirmado = false)

        assertIs<ResultadoRestauracion.Restaurada>(resultado)
        assertNotNull(store.load())
        assertEquals(listOf("parar", "regenerar", "arrancar"), ciclo.llamadas)
    }

    @Test
    fun `una frase invalida no escribe y dice por que`() {
        val original = factory.fromSeed(semilla(6))
        val store = InMemoryIdentityStore()
        store.save(original)
        val ciclo = CicloGrabador()
        val flow = RecoveryFlow(store, factory, ciclo)

        val palabras = fraseDe(semilla(7)).split(" ")

        val corta = flow.validar(palabras.dropLast(1).joinToString(" "))
        assertIs<Validacion.Rechazada>(corta)
        assertEquals(MnemonicMotivo.LONGITUD, corta.motivo)

        val conIntrusa = flow.validar(palabras.toMutableList().also { it[4] = "zzzqqq" }.joinToString(" "))
        assertIs<Validacion.Rechazada>(conIntrusa)
        assertEquals(MnemonicMotivo.PALABRA_DESCONOCIDA, conIntrusa.motivo)
        assertEquals(5, conIntrusa.indice)

        val resultado = flow.restaurar(corta, original.identityId, confirmado = true)

        assertIs<ResultadoRestauracion.Rechazada>(resultado)
        assertEquals(original.identityId.value, assertNotNull(store.load()).identityId.value)
        assertTrue(ciclo.llamadas.isEmpty(), "una frase mala no para ni arranca nada: ${ciclo.llamadas}")
    }

    @Test
    fun `save reinicia el aviso de frase`() {
        // El invariante del arranque: cambiar de identidad cambia la frase, asi
        // que la nueva tiene que volver a pedir el aviso de exposicion.
        val store = InMemoryIdentityStore()
        val ciclo = CicloGrabador()
        val flow = RecoveryFlow(store, factory, ciclo)
        store.setPhraseAcknowledged(true)
        assertTrue(store.phraseAcknowledged(), "pretesto: el aviso estaba puesto")

        val validacion = flow.validar(fraseDe(semilla(8)))
        flow.restaurar(validacion, idActual = null, confirmado = false)

        assertFalse(store.phraseAcknowledged(), "tras restaurar hay que volver a ver la frase nueva")
    }

    @Test
    fun `save() por si solo tambien reinicia el aviso`() {
        val store = InMemoryIdentityStore()
        store.save(factory.fromSeed(semilla(9)))
        store.setPhraseAcknowledged(true)

        store.save(factory.fromSeed(semilla(10)))

        assertFalse(store.phraseAcknowledged(), "el aviso pertenece a una identidad, no al fichero")
    }

    @Test
    fun `el aviso de sobrescritura lleva los dos identificadores`() {
        val store = InMemoryIdentityStore()
        val flow = RecoveryFlow(store, factory, CicloGrabador())
        val idActual = factory.fromSeed(semilla(11)).identityId
        val idRestaurado = factory.fromSeed(semilla(12)).identityId

        val aviso = assertNotNull(flow.confirmarSobrescritura(idActual, idRestaurado))

        assertTrue(aviso.texto.contains(idActual.value), "el aviso lleva la identidad actual")
        assertTrue(aviso.texto.contains(idRestaurado.value), "y la que se va a restaurar")
        assertTrue(aviso.texto.contains("MENSAJES PASADOS NO SE RECUPERAN"))
        assertNull(flow.confirmarSobrescritura(null, idRestaurado), "sin identidad no hay aviso")
    }

    @Test
    fun `el seed de la frase restaurada es exactamente el de la identidad restaurada`() {
        val nueva = factory.fromSeed(semilla(13))
        val store = InMemoryIdentityStore()
        val flow = RecoveryFlow(store, factory, CicloGrabador())

        val validacion = flow.validar(fraseDe(semilla(13)))
        assertIs<Validacion.Aceptada>(validacion)
        assertContentEquals(nueva.keyPair.privateKey, validacion.frase.let { semillaDe(validacion) })

        flow.restaurar(validacion, null, false)

        val guardada = assertNotNull(store.load())
        assertEquals(nueva.identityId.value, guardada.identityId.value)
        assertContentEquals(nueva.keyPair.privateKey, guardada.keyPair.privateKey)
    }

    /** La frase es opaca a proposito: los bytes se piden al framework, no a ella. */
    private fun semillaDe(validacion: Validacion.Aceptada): ByteArray =
        com.km.mnemonic.Mnemonic.toEntropy(validacion.frase)
}
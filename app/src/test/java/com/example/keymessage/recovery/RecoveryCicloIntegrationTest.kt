package com.example.keymessage.recovery

import com.example.keymessage.identity.IdentityMaterial
import com.example.keymessage.identity.IdentityMaterialFactory
import com.example.keymessage.identity.IdentityStore
import com.example.keymessage.identity.InMemoryIdentityStore
import com.example.keymessage.identity.fraseDeRecuperacion
import com.km.model.IdentityId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 6.2 — El ciclo de recuperacion REAL, sin dobles de `RecoveryFlow.Ciclo`.
 *
 * ## POR QUE ESTE TEST EXISTE
 *
 * ADB v5 (obs #285) dio un `StackOverflowError` restaurando identidad:
 * `AppContainer` construia el ciclo con un `object : RecoveryFlow.Ciclo` cuyos
 * overrides eran `override fun parar() { parar() }` y
 * `override fun arrancar() { arrancar() }`. El `parar()` de dentro resolvia al
 * OVERRIDE —el miembro de la clase anonima gana al receptor externo— asi que se
 * llamaba a si mismo, y como [RecoveryFlow] llama `ciclo.parar()` en toda
 * restauracion, la app moria SIEMPRE.
 *
 * Ese bug sobrevivio a un verify entero porque los tests anteriores usaban un
 * doble de `RecoveryFlow.Ciclo` —el `CicloGrabador` de `RecoveryFlowTest`— que,
 * por estar bien escrito, SI llamaba a su propio registro. Un doble no puede
 * afirmar que el ciclo real llame a nadie: solo puede afirmar que el flujo lo
 * invoca. Aqui se ejercita la clase de produccion, [RecoveryCiclo], que es la
 * que monta `AppContainer`.
 *
 * ## LO QUE NO SE AFIRMA
 *
 * Que `AppContainer.parar()` cierre de verdad el socket: eso necesita
 * `Application`, `Room` y `EncryptedSharedPreferences`. Lo que si se afirma es el
 * contrato de los tres pasos —parar, guardar, arrancar— y que la llamada
 * TERMINA. El Contexto de Android es lo unico que queda fuera, y se declara.
 *
 * ## POR QUE `runCatching`
 *
 * Para que el fallo del bug sea un aserto legible ("restaurar tiene que
 * TERMINAR: java.lang.StackOverflowError") y no un hilo muerto que se lleva por
 * delante al resto de la suite. [StackOverflowError] es un `Error`, y al
 * desenrollarse la pila el hilo sigue vivo para comprobarlo.
 */
class RecoveryCicloIntegrationTest {

    /**
     * La pila de rele del contenedor, en memoria, con el CONTRATO de
     * `AppContainer.parar()` / `AppContainer.arrancar()`.
     *
     * Graba la orden porque "se detuvo" y "se(arrancó de nuevo" son afirmaciones
     * sobre el estado de este objeto, no sobre el numero de llamadas.
     */
    private class PilaDeRele(private val registro: MutableList<String>) {
        var socketAbierto: Boolean = true
            private set

        fun detener() {
            socketAbierto = false
            registro += "parar"
        }

        fun levantar() {
            socketAbierto = true
            registro += "arrancar"
        }
    }

    /**
     * El almacen del contenedor, con grabador.
     *
     * [InMemoryIdentityStore] no se puede heredar sin pelea (`text` y
     * `acknowledged` son finales con setter privado), asi que se DECLARA
     * [IdentityStore] y se delega: lo que se prueba es el ORDEN en el que el
     * flujo escribe, y que escribe con el socket ya cerrado.
     */
    private class AlmacenGrabador(
        private val registro: MutableList<String>,
        private val pila: PilaDeRele,
    ) : IdentityStore {
        private val dentro = InMemoryIdentityStore()

        /** Siembra identidad previa sin ensuciar el orden que se afirma. */
        fun sembrar(material: IdentityMaterial) {
            dentro.save(material)
        }

        override fun load(): IdentityMaterial? = dentro.load()

        override fun save(material: IdentityMaterial) {
            registro += "guardar(pilaAbierta=${pila.socketAbierto})"
            dentro.save(material)
        }

        override fun clear() {
            dentro.clear()
        }

        override fun phraseAcknowledged(): Boolean = dentro.phraseAcknowledged()

        override fun setPhraseAcknowledged(acknowledged: Boolean) {
            registro += "ack=$acknowledged"
            dentro.setPhraseAcknowledged(acknowledged)
        }
    }

    private val factory = IdentityMaterialFactory()

    private fun semilla(distintiva: Int): ByteArray =
        ByteArray(32) { ((it * 29 + distintiva * 37) % 239 + 7).toByte() }

    private fun mundo(): Triple<MutableList<String>, PilaDeRele, AlmacenGrabador> {
        val registro = mutableListOf<String>()
        val pila = PilaDeRele(registro)
        return Triple(registro, pila, AlmacenGrabador(registro, pila))
    }

    private fun cicloDe(pila: PilaDeRele, registro: MutableList<String>) = RecoveryCiclo(
        detenerNucleo = { pila.detener() },
        levantarNucleo = { pila.levantar() },
        regenerarClaves = { registro += "regenerar" },
    )

    @Test
    fun `parar y arrancar del ciclo REAL mueven la pila y NO se llaman a si mismos`() {
        val (registro, pila, _) = mundo()
        val ciclo = cicloDe(pila, registro)
        assertTrue(pila.socketAbierto, "la pila arranca viva")

        val intento = runCatching {
            ciclo.parar()
            assertTrue(!pila.socketAbierto, "parar() tiene que cerrar el socket de verdad")
            ciclo.arrancar()
        }

        assertNotNull(
            intento.getOrNull(),
            "el ciclo no puede reventar la pila al delegar: ${intento.exceptionOrNull()}",
        )
        assertTrue(pila.socketAbierto, "arrancar() tiene que volver a levantar el nucleo")
        assertEquals(
            listOf("parar", "arrancar"),
            registro,
            "una llamada por delegacion, y en orden",
        )
    }

    @Test
    fun `regenerarClavesLocales delega una vez, sin tocar el socket`() {
        val (registro, pila, _) = mundo()
        val ciclo = cicloDe(pila, registro)

        val intento = runCatching { ciclo.regenerarClavesLocales() }

        assertNotNull(intento.getOrNull(), "no puede reventar la pila: ${intento.exceptionOrNull()}")
        assertEquals(listOf("regenerar"), registro)
        assertTrue(pila.socketAbierto, "regenerar claves no abre ni cierra el socket")
    }

    @Test
    fun `restaurar con el ciclo REAL termina, escribe parado y deja el nucleo arriba`() {
        val (registro, pila, almacen) = mundo()
        val actual = factory.fromSeed(semilla(1))
        val fraseRestaurada = fraseDeRecuperacion(factory.fromSeed(semilla(2))).display()
        almacen.sembrar(actual)

        val flow = RecoveryFlow(almacen, factory, cicloDe(pila, registro))
        val validacion = flow.validar(fraseRestaurada)
        assertIs<Validacion.Aceptada>(validacion)

        val intento = runCatching {
            flow.restaurar(validacion, actual.identityId, confirmado = true)
        }

        // El aserto del CRITICAL: la llamada tiene que VOLVER. Con el override
        // ensombrecido, `ciclo.parar()` se llama a si mismo hasta llenar la pila.
        assertNotNull(
            intento.getOrNull(),
            "restaurar tiene que TERMINAR, no reventar la pila: ${intento.exceptionOrNull()}",
        )
        assertIs<ResultadoRestauracion.Restaurada>(intento.getOrNull())

        assertEquals(
            listOf("parar", "guardar(pilaAbierta=false)", "ack=false", "regenerar", "arrancar"),
            registro,
            "parar ANTES de guardar, arrancar DESPUES: si se guardase con el socket vivo, " +
                "el rele seguiria firmando con la identidad que se va a sustituir",
        )

        val escrita = assertNotNull(almacen.load())
        assertEquals(
            assertIs<Validacion.Aceptada>(validacion).identityId.value,
            escrita.identityId.value,
            "lo guardado es la identidad de la frase, no la de antes",
        )
        assertTrue(pila.socketAbierto, "al terminar, el nucleo queda levantado")
        assertTrue(!almacen.phraseAcknowledged(), "una identidad nueva es una frase nueva")
    }

    @Test
    fun `restaurar dos veces seguidas no acumula llamadas ni deja el socket caido`() {
        // El caso que el bug de la recursion hacia PALPABLE: la segunda
        // restauracion vuelve a pasar por `ciclo.parar()` y `ciclo.arrancar()`.
        val (registro, pila, almacen) = mundo()
        almacen.sembrar(factory.fromSeed(semilla(3)))
        val flow = RecoveryFlow(almacen, factory, cicloDe(pila, registro))

        repeat(2) { intentoNumero ->
            val frase = fraseDeRecuperacion(factory.fromSeed(semilla(10 + intentoNumero))).display()
            val validacion = flow.validar(frase)
            assertIs<Validacion.Aceptada>(validacion)

            val intento = runCatching { flow.restaurar(validacion, null, confirmado = false) }

            assertNotNull(
                intento.getOrNull(),
                "la restauracion $intentoNumero tiene que TERMINAR: ${intento.exceptionOrNull()}",
            )
        }

        assertEquals(
            listOf(
                "parar", "guardar(pilaAbierta=false)", "ack=false", "regenerar", "arrancar",
                "parar", "guardar(pilaAbierta=false)", "ack=false", "regenerar", "arrancar",
            ),
            registro,
            "cada restauracion hace el ciclo entero, y solo uno",
        )
        assertTrue(pila.socketAbierto)
    }

    @Test
    fun `la identidad que sale del ciclo es la de la frase y no la sembrada`() {
        val (registro, pila, almacen) = mundo()
        val sembrada = factory.fromSeed(semilla(5))
        val esperada = factory.fromSeed(semilla(6))
        almacen.sembrar(sembrada)
        val flow = RecoveryFlow(almacen, factory, cicloDe(pila, registro))

        val resultado = flow.restaurar(
            flow.validar(fraseDeRecuperacion(esperada).display()),
            sembrada.identityId,
            confirmado = true,
        )

        val idEsperado: IdentityId = assertIs<ResultadoRestauracion.Restaurada>(resultado).identityId
        assertEquals(esperada.identityId.value, idEsperado.value)
        assertEquals(esperada.identityId.value, assertNotNull(almacen.load()).identityId.value)
    }
}
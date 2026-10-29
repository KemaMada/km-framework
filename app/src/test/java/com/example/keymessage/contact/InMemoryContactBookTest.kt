package com.example.keymessage.contact

import com.example.keymessage.support.TestWorld
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 1.3 — El registro de contactos es OBSERVABLE, y solo cuando toca.
 *
 * ## QUE AFIRMA
 *
 * - **R1**: el flujo entrega el estado PRESENTE al suscribirse, y el orden de
 *   insercion se conserva.
 * - **R2**: cada mutacion emite; un contacto nuevo va AL FINAL.
 * - **R3**: un `upsert` que NO verifica LANZA, NO emite y NO altera el estado.
 * - **R4**: las escrituras del hilo del motor y del principal son ATOMICAS:
 *   la ultima emision es la union de las raices y no hay estado a medias.
 *
 * ## POR QUE ESTO NO SE PROBABA EN LA UI
 *
 * Porque la UI no tiene `ComposeTestRule` en este modulo y no se va a meter uno.
 * El defecto D1 (obs #326) no era de Compose: era que la FUENTE no emitia. Por
 * eso lo que se afirma aqui es el contrato del libro, y lo que la UI consume
 * (`collectAsStateWithLifecycle`) lo ata el guard estructural
 * `ContactListReactiveGuardTest`.
 *
 * ## POR QUE UN VIGILANTE Y NO UN `first()`
 *
 * Porque `StateFlow` CONFLYE: dos mutaciones con el mismo valor no son dos
 * emisiones, y un test que espera "la siguiente" puede colgarse o pasar por
 * casualidad. El vigilante se suscribe ANTES de mutar, espera a haber recibido
 * el valor inicial, y despues cuenta lo que llega: sin carreras y sin suposiciones.
 */
class InMemoryContactBookTest {

    /** Un contacto de VERDAD, con su bundle firmado por su propia raiz. */
    private fun contactoDe(nombre: String): VerifiedContact {
        val par = TestWorld.deviceWithRoot(nombre)
        val texto = par.root.bundleTextFor(listOf(par.device), par.device, emptyList())
        return ContactImporter.import(texto)
    }

    /**
     * Un recolector VIVO del flujo: cuenta cada valor que llega.
     *
     * Espera a que el recolector haya recibido el valor INICIAL antes de
     * devolverlo, para que la prueba no dependa de un `yield` afortunado.
     */
    private class Vigilante<T>(flujo: StateFlow<T>) {
        val valores: MutableList<T> = mutableListOf()
        private val inicial = CompletableDeferred<Unit>()
        val job: Job = CoroutineScope(Dispatchers.Default).launch {
            flujo.collect { valor ->
                synchronized(valores) { valores += valor }
                inicial.complete(Unit)
            }
        }

        /** Espera a que el valor inicial haya llegado. */
        suspend fun esperarInicial() = inicial.await()

        /** Espera a que hayan llegado al menos [minimo] valores. */
        suspend fun esperar(minimo: Int, ms: Long = 5000) {
            withTimeout(ms) {
                while (true) {
                    val n = synchronized(valores) { valores.size }
                    if (n >= minimo) return@withTimeout
                    delay(2)
                }
            }
        }

        /** Afirma que NO llega ninguna emision nueva en [ms] milisegundos. */
        suspend fun noEmiteEn(ms: Long = 400) {
            val antes = synchronized(valores) { valores.size }
            delay(ms)
            val despues = synchronized(valores) { valores.size }
            assertEquals(antes, despues, "no tenia que llegar ninguna emision nueva")
        }

        fun cerrar() = job.cancel()
    }

    // ==================================================================
    // R1 — el flujo entrega el estado PRESENTE al suscribirse
    // ==================================================================

    @Test
    fun `el flujo entrega el estado presente al suscribirse`() {
        val libro = InMemoryContactBook()

        // El pretesto: un `StateFlow` SIEMPRE tiene el estado actual, y vacio
        // es un estado valido (una app que todavia no ha importado nada).
        assertEquals(emptyList(), libro.contacts.value)
        assertEquals(emptyList(), libro.all())

        val a = contactoDe("alice")
        val b = contactoDe("bob")
        libro.upsert(a)
        libro.upsert(b)

        assertEquals(
            listOf(a.identityRoot, b.identityRoot),
            libro.contacts.value.map { it.identityRoot },
            "el flujo tiene el estado ACTUAL, no una fotografia vacia",
        )
    }

    @Test
    fun `los contactos se ven en ORDEN DE INSERCION`() {
        val libro = InMemoryContactBook()
        val a = contactoDe("alice")
        val b = contactoDe("bob")
        val c = contactoDe("carol")

        libro.upsert(b)
        libro.upsert(a)
        libro.upsert(c)

        assertEquals(
            listOf(b.identityRoot, a.identityRoot, c.identityRoot),
            libro.contacts.value.map { it.identityRoot },
            "el orden es el de importacion, no el alfabetico ni el de la raiz",
        )
    }

    // ==================================================================
    // R2 — toda mutacion emite
    // ==================================================================

    @Test
    fun `un upsert verificado EMITE y el nuevo va al final`() {
        runBlocking {
            val libro = InMemoryContactBook()
            val a = contactoDe("alice")
            val b = contactoDe("bob")

            libro.upsert(a)
            val vigilante = Vigilante(libro.contacts)
            vigilante.esperarInicial()

            libro.upsert(b)
            vigilante.esperar(2)

            val emissions = vigilante.valores.toList()
            assertEquals(2, emissions.size, "un upsert es UNA emision: $emissions")
            assertEquals(
                listOf(a.identityRoot, b.identityRoot),
                emissions.last().map { it.identityRoot },
                "y el contacto nuevo va AL FINAL",
            )
            vigilante.cerrar()
        }
    }

    @Test
    fun `un remove EMITE la lista sin el contacto`() {
        runBlocking {
            val libro = InMemoryContactBook()
            val a = contactoDe("alice")
            val b = contactoDe("bob")
            libro.upsert(a)
            libro.upsert(b)

            val vigilante = Vigilante(libro.contacts)
            vigilante.esperarInicial()

            libro.remove(a.identityRoot)
            vigilante.esperar(2)

            val ultima = vigilante.valores.last()
            assertEquals(
                listOf(b.identityRoot),
                ultima.map { it.identityRoot },
                "tras el remove, el flujo lleva la lista SIN el contacto borrado",
            )
            assertEquals(null, libro.find(a.identityRoot), "y el contacto ya no se encuentra")
            vigilante.cerrar()
        }
    }

    // ==================================================================
    // R3 — un upsert RECHAZADO no emite y no altera el estado
    // ==================================================================

    @Test
    fun `un sobre roto LANZA, NO emite y NO altera el estado`() {
        runBlocking {
            val libro = InMemoryContactBook()
            val a = contactoDe("alice")
            libro.upsert(a)

            val vigilante = Vigilante(libro.contacts)
            vigilante.esperarInicial()

            // El mismo contacto con el sobre ROTO: el registro reverifica y no
            // se fia del objeto que le llega.
            val fallo = assertFailsWith<Exception> {
                libro.upsert(a.copy(bundleText = "no-es-un-sobre"))
            }
            assertTrue(
                rejectionCodeOf(fallo).isNotBlank(),
                "el rechazo tiene un codigo estable: ${rejectionCodeOf(fallo)}",
            )

            vigilante.noEmiteEn()
            assertEquals(1, libro.all().size, "all() sigue intacto")
            assertEquals(
                listOf(a.identityRoot),
                libro.contacts.value.map { it.identityRoot },
                "y el flujo retiene el valor ANTERIOR",
            )
            vigilante.cerrar()
        }
    }

    @Test
    fun `un contacto con el sobre roto NO entra en el libro`() {
        val libro = InMemoryContactBook()
        val a = contactoDe("alice")

        assertFailsWith<Exception> { libro.upsert(a.copy(bundleText = "no-es-un-sobre")) }

        assertEquals(0, libro.all().size, "un rechazo no deja nada guardado")
        assertEquals(0, libro.contacts.value.size, "ni en el flujo")
    }

    // ==================================================================
    // R4 — las escrituras concurrentes son atomicas
    // ==================================================================

    @Test
    fun `las escrituras concurrentes NO dejan estado a medias`() {
        runBlocking {
            val libro = InMemoryContactBook()
            val hilos = 4
            val porHilo = 5
            // Los contactos se crean ANTES: la concurrencia que se quiere medir
            // es la del REGISTRO, no la de la arnes de pruebas.
            val contactos = (0 until (hilos * porHilo)).map { contactoDe("par$it") }
            val grupos = contactos.chunked(porHilo)

            val vigilante = Vigilante(libro.contacts)
            vigilante.esperarInicial()

            val trabajos = grupos.map { grupo ->
                CoroutineScope(Dispatchers.Default).launch {
                    grupo.forEach { libro.upsert(it) }
                }
            }
            trabajos.forEach { it.join() }
            vigilante.esperar(2)

            val raicesEsperadas = contactos.map { it.identityRoot }.toSet()
            val raicesVivas = libro.all().map { it.identityRoot }.toSet()

            assertEquals(
                contactos.size,
                raicesVivas.size,
                "tienen que estar TODOS: ${raicesVivas.size} de ${contactos.size}",
            )
            assertEquals(raicesEsperadas, raicesVivas, "y son exactamente los que se escribieron")
            assertEquals(
                raicesEsperadas,
                libro.contacts.value.map { it.identityRoot }.toSet(),
                "la ultima emision es la union de las raices",
            )
            vigilante.cerrar()
        }
    }

    @Test
    fun `un remove concurrente con un upsert deja el libro CONSISTENTE`() {
        runBlocking {
            val libro = InMemoryContactBook()
            val a = contactoDe("alice")
            val b = contactoDe("bob")
            libro.upsert(a)
            libro.upsert(b)

            val vigilante = Vigilante(libro.contacts)
            vigilante.esperarInicial()

            // Dos escrituras a la vez sobre el mismo libro: una quita, otra
            // anade. Lo que se afirma no es el orden —que no lo tiene— sino que
            // el estado final NO esta a medias.
            val quita = CoroutineScope(Dispatchers.Default).launch { libro.remove(a.identityRoot) }
            val anade = CoroutineScope(Dispatchers.Default).launch { libro.upsert(b) }
            quita.join()
            anade.join()
            vigilante.esperar(2)

            val estado = libro.all().map { it.identityRoot }
            assertTrue(
                estado == listOf(b.identityRoot) || estado == listOf(a.identityRoot, b.identityRoot),
                "el estado final es UNO de los dos enteros posibles, no una mezcla: $estado",
            )
            assertNotNull(libro.find(b.identityRoot), "y el contacto que se anadio esta")
            vigilante.cerrar()
        }
    }
}

package com.example.keymessage.ui

import com.example.keymessage.support.TestWorld
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * El evento "importado" tiene UN SOLO dueno, y ese dueno lo dispara UNA vez.
 *
 * ## EL DEFECTO
 *
 * El evento "importado con exito" lo disparaban DOS: el
 * [ImportContactViewModel] al importar (`ConversationViewModel.kt:128`) y el
 * `LaunchedEffect(state.contact)` del composable (`MainActivity.kt:385`). Con el
 * `ViewModel` retenido por el `Activity`, el segundo se re-disparaba al reabrir
 * el dialogo y RELANZABA el intercambio de contactos con el contacto ANTERIOR
 * (obs #326, D2). En logcat eso era mas de una linea `[SIGNAL] intercambio de
 * contactos entregado` por par.
 *
 * ## QUE AFIRMA
 *
 *  - Un import EXITOSO llama a `onImported` EXACTAMENTE una vez.
 *  - Un import RECHAZADO no lo llama NUNCA, y deja un codigo de rechazo ESTABLE
 *    (nunca `null`).
 *  - Si el `onImported` falla, el fallo SUBE al estado: el dialogo pinta el
 *    rechazo con su codigo y el contacto NO se da por bueno.
 *
 * ## POR QUE `Dispatchers.setMain`
 *
 * Porque `viewModelScope` vive en `Dispatchers.Main.immediate`, y en un test de
 * JVM no hay hilo principal. Sin esto, el `ViewModel` del import —que es
 * justamente donde vive el dueno unico del evento— no se puede ni arrancar.
 *
 * ## EL FLAKE DEL PROPIO TEST (fase `contact-list-refresh`, obs #346)
 *
 * `importar DOS VECES son DOS eventos, no cuatro` era `1/10` inestable —y de
 * hecho 8/20 medido— NO por un defecto de produccion, sino por dos defectos del
 * test: un `var llamadas` compartido entre los hilos `DefaultDispatcher` donde
 * corre `onImported`, y un handshake `CompletableDeferred` que colgaba (`runTest`
 * aborta a los 60s con `UncompletedCoroutinesError`) cuando la carrera perdia la
 * senal de completado. El detalle y la reparacion estan en el KDoc de ese test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportContactViewModelTest {

    /** Un bundle de verdad, firmado por su propia raiz. */
    private fun bundleDe(nombre: String): String {
        val par = TestWorld.deviceWithRoot(nombre)
        return par.root.bundleTextFor(listOf(par.device), par.device, emptyList())
    }

    @Test
    fun `un import EXITOSO llama a onImported EXACTAMENTE una vez`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            var llamadas = 0
            val hecho = CompletableDeferred<Unit>()
            val vm = ImportContactViewModel {
                llamadas++
                hecho.complete(Unit)
            }

            vm.import(bundleDe("alice"))
            hecho.await()

            // El estado se asienta DESPUES del evento: `onImported` va primero
            // y el `state` se escribe despues. Por eso se espera al estado y
            // no se da por hecho.
            val estado = vm.state.first { it.contact != null || it.rejectionCode != null }
            assertNotNull(estado.contact, "un bundle valido da un contacto: $estado")
            assertNull(estado.rejectionCode, "y ningun rechazo")
            assertEquals(1, llamadas, "onImported se dispara EXACTAMENTE una vez, no dos")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `un import RECHAZADO no llama a onImported y deja un codigo ESTABLE`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            var llamadas = 0
            val vm = ImportContactViewModel { llamadas++ }

            vm.import("no-es-un-sobre")

            val estado = vm.state.first { it.contact != null || it.rejectionCode != null }
            assertEquals(0, llamadas, "un rechazo NO dispara el evento de importado")
            assertNull(estado.contact, "y no hay contacto")
            val codigo = assertNotNull(estado.rejectionCode, "el rechazo tiene un codigo: $estado")
            assertTrue(codigo.isNotBlank(), "y el codigo no esta vacio")
            assertTrue(
                codigo != "IMPORT_FAILED",
                "el codigo viene del verificador, no del cajon de sastre: '$codigo'",
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `si onImported FALLA, el fallo sube al estado con su codigo`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val vm = ImportContactViewModel { error("el registro ha fallado") }

            vm.import(bundleDe("alice"))

            val estado = vm.state.first { it.contact != null || it.rejectionCode != null }
            assertNull(estado.contact, "un evento que fallo NO da el contacto por bueno")
            val codigo = assertNotNull(estado.rejectionCode, "el fallo tiene codigo: $estado")
            assertEquals("IMPORT_FAILED", codigo, "y sale de `rejectionCodeOf`, sin inventar")
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `importar DOS VECES son DOS eventos, no cuatro`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            // EL DEFECTO DE ESTE TEST (fase `contact-list-refresh`, obs #346).
            //
            // `onImported` NO se ejecuta en el hilo del test: la produccion hace
            // `withContext(Dispatchers.Default)` dentro de `viewModelScope`, asi
            // que cada callback corre en un `DefaultDispatcher-worker-*`. Dos
            // pulsaciones de IMPORTAR son dos coroutines CONCURRENTES, y sus
            // callbacks caen en DOS hilos distintos a la vez.
            //
            //   - `var llamadas++` era un read-modify-write NO atomico sobre esa
            //     variable compartida: los dos hilos leian 0 y el contador perdia
            //     una escritura.
            //   - Peor: el handshake `if (llamadas++ >= 1) dos.complete(Unit)`
            //     dependia de esa lectura. Con los dos hilos viendo 0,`complete`
            //     NO se llamaba NUNCA y `dos.await()` colgaba hasta el timeout de
            //     `runTest`: `UncompletedCoroutinesError ... the test body did not
            //     run to completion`. No era un `assertEquals` que fallara: era el
            //     test ENTERO sin terminar. Medido: 8/20 HANG en aislamiento.
            //
            // LA REPARACION: acumulacion DETERMINISTA y SIN CARRERA.
            //
            // `AtomicInteger` cierra el read-modify-write (invariante: cuenta
            // exacta de eventos, venga de un hilo o de dos). `Channel(UNLIMITED)`
            // da el rendezvous: cada callback ENTREGA un token, y el test DRENA
            // recibiendo exactamente dos. `receive()` suspende hasta que el
            // trabajo REAL de `Dispatchers.Default` ha terminado de verdad — no
            // sondea, no depende del orden de ejecucion, y no puede perder una
            // senal como el `CompletableDeferred` condicional.
            val llamadas = AtomicInteger(0)
            val eventos = Channel<Unit>(Channel.UNLIMITED)
            val vm = ImportContactViewModel {
                llamadas.incrementAndGet()
                eventos.trySend(Unit)
            }
            val texto = bundleDe("alice")

            vm.import(texto)
            vm.import(texto)

            // Drena DOS eventos: uno por importacion. Esperar al ESTADO no sirve
            // aqui, porque las dos importaciones producen el MISMO contacto y un
            // `StateFlow` no vuelve a emitir un valor igual.
            eventos.receive()
            eventos.receive()

            // La invariante, SIN carrera: dos importaciones, dos eventos.
            // `AtomicInteger` cuenta aunque los callbacks caigan en dos hilos
            // distintos, y el `receive()` de arriba ya garantiza que AMBOS
            // llegaron de verdad (no se sondea: se espera).
            assertEquals(2, llamadas.get(), "dos importaciones, dos eventos: ni uno mas")
        } finally {
            Dispatchers.resetMain()
        }
    }
}

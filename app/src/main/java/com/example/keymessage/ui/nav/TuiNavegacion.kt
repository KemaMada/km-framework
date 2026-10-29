package com.example.keymessage.ui.nav

/**
 * 8.3 — Las rutas de la app.
 *
 * [FRASE] y [RESTAURAR] son las dos de la recuperacion. `DIAGNOSTICO` es la que
 * hace de CONFIG: no hay una pantalla de ajustes aparte.
 *
 * Se declaran aqui —y no `private` dentro de `MainActivity`— para que la PILA de
 * navegacion sea una funcion pura y se pueda probar en JVM (no hay
 * `ComposeTestRule` en este modulo). La Activity solo pinta `pila.last()`.
 */
enum class Pantalla { INICIO, IMPORTAR, CONVERSACION, DIAGNOSTICO, COMPARTIR, FRASE, RESTAURAR }

/**
 * 8.3 — La pila de navegacion TUI: funciones PURAS sobre `List<Pantalla>`.
 *
 * ## POR QUE UNA PILA Y NO `pantalla = ...` SUELTO
 *
 * Porque sin pila el Back del sistema cerraba la Activity y no habia forma de
 * "volver a la pantalla anterior" (verify #381 C3: no existia `BackHandler`).
 * Con la pila, el back tiene un nivel por pulsacion:
 *
 *  1. una pantalla secundaria (dialogo IMPORTAR, CONVERSACION, DIAGNOSTICO...) -> vuelve a la anterior;
 *  2. en la raiz [Pantalla.INICIO] la pila NO puede retroceder -> el sistema
 *     recibe el back y sale de la app, que es lo honesto: no hay nada detras.
 *
 * ## POR QUE `sustituir` EXISTE
 *
 * Porque "importar con exito" CIERRA el dialogo y navega al chat: si se
 * apilara, el back desde el chat reabriria el dialogo de importar —con su
 * `OwnerDeApertura` recreado y el estado de import perdido—, que no es "la
 * pantalla anterior" sino un callejon. `sustituir` reemplaza la cima, de modo
 * que el back desde el chat vuelve a INICIO.
 *
 * Las funciones NO mutan la lista de entrada; devuelven una nueva.
 */
object TuiNavegacion {

    /** La pila inicial: solo INICIO. */
    fun inicial(): List<Pantalla> = listOf(Pantalla.INICIO)

    /**
     * Apila [destino]. Repetir la pantalla actual no apila una copia: dos
     * entradas iguales harian que un back pareciera "no hacer nada".
     */
    fun avanzar(pila: List<Pantalla>, destino: Pantalla): List<Pantalla> =
        if (pila.lastOrNull() == destino) pila else pila + destino

    /**
     * Reemplaza la cima por [destino] (cierre de dialogo + navegacion).
     * Con una pila vacia se comporta como [inicial] con [destino].
     */
    fun sustituir(pila: List<Pantalla>, destino: Pantalla): List<Pantalla> =
        if (pila.isEmpty()) listOf(destino) else pila.dropLast(1) + destino

    /**
     * Desapila la cima.
     *
     * @return la pila nueva, o `null` si ya estabamos en la raiz: `null` es la
     *   senal de "no hay pantalla anterior, que el back lo trate el sistema".
     */
    fun volver(pila: List<Pantalla>): List<Pantalla>? =
        if (pila.size <= 1) null else pila.dropLast(1)

    /** Hay una pantalla anterior a la que volver. */
    fun puedeVolver(pila: List<Pantalla>): Boolean = pila.size > 1

    /** Deja la navegacion en [destino] como unica entrada (onboarding forzado). */
    fun reiniciar(destino: Pantalla = Pantalla.INICIO): List<Pantalla> = listOf(destino)
}

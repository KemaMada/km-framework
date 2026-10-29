package com.example.keymessage.ui.screens

/**
 * R17 / F4 — cuando la conversacion SEGUIR el ultimo mensaje, cuando abrir al
 * fondo, y cuando re-anclar tras el IME.
 *
 * ## EL DEFECTO QUE CIERRA
 *
 * Antes habia un `LaunchedEffect(messages.size) { animateScrollToItem(size - 1) }`
 * incondicional: cada mensaje nuevo arrastraba la vista al final. Si el usuario
 * estaba leyendo arriba, la lectura se interrumpia; y al reves, un mensaje que
 * no se veia en la ventana se interpretaba como un mensaje PERDIDO cuando solo
 * estaba fuera de pantalla. La ausencia visible no es evidencia de perdida.
 *
 * ## LO QUE ANADE F4
 *
 *  - [indiceInicial]: al ABRIR la conversacion la lista se coloca al fondo, para
 *    que el hilo empiece por lo mas reciente.
 *  - [debeReanclarTrasIme]: cuando el teclado sube o baja, la vista solo se
 *    re-ancla si el usuario YA estaba al final. Si estaba leyendo arriba, el IME
 *    no le mueve la vista.
 *
 * La decision vive en una funcion PURA porque no hay `ComposeTestRule` en esta
 * app (no es dependencia) y porque "seguir o no seguir" es una regla de
 * producto, no un detalle del `LazyListState`.
 */
object ChatScrollPolicy {

    /**
     * El indice al que abrir la lista: el ultimo, o `-1` si esta vacia.
     *
     * Un `LazyListState` no se puede anclar a un indice que no existe: con la
     * lista vacia se devuelve `-1` y el llamante NO llama a `scrollToItem`.
     */
    fun indiceInicial(total: Int): Int = if (total <= 0) -1 else total - 1

    /**
     * @param wasAtBottom el usuario ya estaba al final ANTES de que llegara lo
     *   nuevo. Es la unica razon por la que se le puede mover la vista.
     * @param previousCount filas antes de la actualizacion.
     * @param newCount filas ahora.
     * @return `true` solo si habia mensajes nuevos y el usuario estaba abajo.
     *   Leer arriba NUNCA hace scroll, y un conteo igual o menor tampoco.
     */
    fun shouldFollow(wasAtBottom: Boolean, previousCount: Int, newCount: Int): Boolean =
        wasAtBottom && newCount > previousCount

    /**
     * F4 — si el IME (al subir o bajar) debe re-anclar la vista al fondo.
     *
     * Solo cuando el usuario YA estaba al final y hay mensajes: re-anclar a un
     * lector de historial seria interrumpirle la lectura —el mismo defecto que
     * [shouldFollow] cierra para los mensajes nuevos—. Sin mensajes no hay nada
     * a lo que anclar.
     */
    fun debeReanclarTrasIme(wasAtBottom: Boolean, hayMensajes: Boolean): Boolean =
        wasAtBottom && hayMensajes

    /**
     * Cuantos mensajes llegaron desde [previousCount] para el aviso "N nuevos".
     *
     * Nunca negativo: el conteo puede BAJAR (un descarte explicito), y eso no
     * es "menos N nuevos".
     */
    fun newCount(previousCount: Int, newCount: Int): Int = (newCount - previousCount).coerceAtLeast(0)
}

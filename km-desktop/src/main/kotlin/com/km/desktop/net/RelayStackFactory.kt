package com.km.desktop.net

import com.km.node.PeerTransportManager
import com.km.transport.JvmRelayTransport
import com.km.transport.RelayClientStack

/**
 * La pila del rele, compuesta desde el motor que YA existe.
 *
 * ## QUE RESUELVE
 *
 * Que el escritorio no tenga que recordar el orden de las piezas: motor ->
 * manager con ESE motor por backend -> drivers. Lo unico que este metodo hace es
 * quitar esa obligation; el trabajo de verdad lo hace [RelayClientStack], que
 * ya vive en `:km-transport` y que RECHAZA un manager cuyo backend no sea el
 * motor. Aqui no se reimplementa nada de eso: se llama.
 *
 * ## POR QUE NO SE ESCRIBE UN CLIENTE DE RELE AQUI
 *
 * Porque el motor del rele (cliente WebSocket + codec del dialecto
 * KM-WIRE-RELAY) salio de este modulo a `:km-transport` en p4. Dos clientes de
 * wire son dos protocolos, dos `MAX_RESEND` y dos vectores de ratchet
 * duplicados, y el que se queda sin sincronizar es el que nadie mira. Esta clase
 * no puede crecer hacia ahi: solo compone.
 *
 * ## POR QUE SE COMPOSE SOBRE UN MOTOR YA CONSTRUIDO
 *
 * `JvmRelayTransport` admite un `clientFactory` inyectable en su constructor, y
 * por ahi es por donde un test puede abrir la pila sin red: la composicion se
 * comprueba en una JVM sin socket, y lo unico que necesita red de verdad es el
 * handshake `AUTH_*`, que se prueba en la etapa ADB. Esta clase no vuelve a
 * declarar ese parametro porque no construye el motor: lo recibe.
 */
object RelayStackFactory {

    /**
     * Compone la pila sobre un motor YA CONSTRUIDO.
     *
     * @param managerFactory como se construye el manager. Por defecto es el
     *   manager de km-core con el motor por backend, que es lo unico que
     *   [RelayClientStack.compose] acepta. Se inyecta para que un test pueda
     *   observar el rechazo con un backend distinto, no para meter un motor
     *   propio en produccion: eso lo rechazaria `compose`.
     * @throws IllegalArgumentException si el manager resultante no tiene este
     *   motor por backend. Lo propaga [RelayClientStack.compose], que es donde
     *   vive el criterio.
     */
    fun stackFor(
        transport: JvmRelayTransport,
        managerFactory: (JvmRelayTransport) -> PeerTransportManager = { PeerTransportManager(it) },
    ): RelayClientStack = RelayClientStack.compose(transport, managerFactory(transport))
}
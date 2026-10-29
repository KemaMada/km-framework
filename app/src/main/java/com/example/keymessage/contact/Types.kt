package com.example.keymessage.contact

/**
 * El contacto verificado es el tipo COMPARTIDO de km-core.
 *
 * El `data class` que vivia en este paquete se sustituye por
 * [com.km.identity.VerifiedContact]: el escritorio y la app describen igual un
 * contacto importado —raiz, `deviceId`, clave de firma, portador y nombre— y no
 * pueden divergir en que campos guardan.
 *
 * El alias conserva el nombre que ya usan la app y sus tests, sin una segunda
 * definicion que se pueda quedar atras.
 */
typealias VerifiedContact = com.km.identity.VerifiedContact

/** El fallo del intercambio, con codigo estable: el tipo COMPARTIDO. */
typealias ContactExchangeException = com.km.transport.contact.ContactExchangeException

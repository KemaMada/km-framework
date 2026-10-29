package com.example.keymessage.model

/**
 * 5.2 — Un contacto en la lista.
 *
 * `id` es el `identityId` de la RAIZ, en hex: la MISMA clave con la que el
 * framework enruta la conversacion (`PeerContext.forContact`). Guardar aqui el
 * `subjectDeviceId` del bundle seria el error que hace que un contacto/device se
 * cuelen en el mismo registro que una conversacion.
 */
data class Contact(
    val id: String,
    val name: String,
    val isOnline: Boolean = false,
)
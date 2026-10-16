package com.km.messaging

import com.km.frame.SecureFrameSpec

/**
 * D-1 — COMO SE DISTINGUE UN `SecureFrame` DE UN `Message` EN CLARO.
 *
 * Las dos rutas de entrada conviven en el mismo producto: por un
 * `PeerTransportManager` pueden llegar bytes de un `SecureFrame` v1, y por el
 * `Transport` legado llega el JSON que emite `JsonEncoder`. Como quien recibe
 * tiene que elegir una de las dos SIN adivinar, hace falta un discriminante, y
 * la decision tiene que ser una SOLA COMPARACION sobre bytes reales.
 *
 * ## POR QUE EL PRIMER BYTE
 *
 * El wire de `SecureFrame` v1 empieza obligatoriamente por su campo `version`
 * (`SecureFrameSpec.VERSION_OFFSET == 0`), y v1 vale `0x01`: **todo** frame v1
 * empieza por `0x01`. El objeto que emite `JsonEncoder` es un objeto Jackson y
 * empieza por `0x7B` (`{`); un array empezaria por `0x5B`.
 *
 * `0x01` no es un caracter JSON valido al principio de un valor, asi que la
 * colision no es improbable: es imposible para cualquier valor JSON valido.
 * Un array vacio tampoco empieza por `0x01`.
 *
 * ## LO QUE ESTO NO ES
 *
 * No es una validacion. `isSecureFrameWire` no comprueba que el frame tenga
 * 44 bytes de header ni que su `type` sea conocido: solo responde "este wire
 * PASA por la ruta de `SecureFrame`". Si despues no descifra, el descifrado lo
 * rechaza —con su propio tipo de fallo— y nada llega a la aplicacion. Prefijar
 * con una comprobacion mas solo daria una segunda puerta al mismo sitio.
 */
fun isSecureFrameWire(data: ByteArray): Boolean =
    data.isNotEmpty() &&
        data[SecureFrameSpec.VERSION_OFFSET].toUByte() == SecureFrameSpec.VERSION
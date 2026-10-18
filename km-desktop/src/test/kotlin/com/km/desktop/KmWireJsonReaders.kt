package com.km.desktop

import com.km.transport.RelayDataCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lectores de JSON de los frames del dialecto KM-WIRE-RELAY, para los tests de
 * escritorio.
 *
 * ## POR QUE EXISTEN AQUI
 *
 * p4/R-8.a movio el codec del dialecto (`RelayDataCodec.kt`) de `km-desktop` a
 * `:km-transport`, y los lectores de campo del codec son `internal` a ESE
 * modulo. Los tests de escritorio leen frames del MISMO dialecto (handshake,
 * `ACK`, cabeceras del almacen) y necesitan esos lectores, pero `internal` no
 * cruza el limite del modulo.
 *
 * ## POR QUE NO SE HACE PUBLICA LA SUPERFICIE DEL CODEC
 *
 * Los lectores del codec se llaman `string`, `int` y `responderIdentity`:
 * nombres genericos que en un paquete publico son un peligro de colision, y el
 * diseno del codec los dejo `internal` a proposito. Exponerlos obligaria a
 * cualquier consumidor a importar `com.km.transport.string`, que choca con
 * cualquier otro `string` de su propio codigo.
 *
 * ## ESTO NO ES UN SEGUNDO MOTOR DE WIRE
 *
 * R-8.a habla del CLIENTE DE RED (socket, codec, `MAX_RESEND`): eso vive
 * entero en `com.km.transport.JvmRelayTransport`. Aqui solo se repiten tres
 * lectores de JSON de uso local en las pruebas. Son `internal` del modulo de
 * TEST, no entran en el POM ni en ningun binario, y no pueden divergir del
 * protocolo porque no hablan con nadie.
 */
internal fun JsonObject.string(field: String): String? = this[field]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.int(field: String): Int? = this[field]?.jsonPrimitive?.int

/**
 * `responderIdentityId` de un frame, normalizado a minusculas, o `null`.
 *
 * Es la MISMA regla que el lector de `RelayDataCodec`: 64 hex. Se repite aqui
 * por el motivo de arriba; un test que crafteara una identidad de otra
 * longitud no estaria probando la validacion del rele, estaria probando su
 * propia copia.
 */
internal fun JsonObject.responderIdentity(): String? {
    val raw = string("responderIdentityId") ?: return null
    if (raw.length != RelayDataCodec.IDENTITY_ID_HEX) return null
    if (raw.any { it !in "0123456789abcdefABCDEF" }) return null
    return raw.lowercase()
}

package com.km.node

import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId

/**
 * Frame LEGADO que entra por el relé y que NO es un `SecureFrame`.
 *
 * ## QUE ES Y QUE NO ES
 *
 * Es el `Message` de km-core que el motor del relé (`com.km.transport`) ya
 * sabe decodificar del payload de un `MESSAGE` del dialecto KM-WIRE-RELAY. Con
 * el motor de hoy ese `Message` llegaba por `Transport.setMessageHandler`, un
 * camino que **no sabe distinguir** un mensaje de aplicación de un `SecureFrame`
 * opaco: los dos llegan al mismo handler y sólo el envoltorio JSON los separa.
 *
 * Esta clase da a ese camino un NOMBRE y una FRONTERA sin quitarle nada: el
 * mismo `Message`, los mismos bytes, la misma entrega. Lo único que cambia es
 * que ahora se puede dejar de entregar en el camino de par y dejarlo en el suyo.
 *
 * ## POR QUE UN TIPO NUEVO Y NO REUSAR `onDataReceived` FILTRANDO EN KM-CORE
 *
 * Porque el byte de un `Message` legado y el byte de un `SecureFrame` se
 * distinguen SÓLO parseando el payload, y esa pregunta es del DIALECTO del
 * medio, no del framework. Si se reutilizara `onDataReceived` filtrando en
 * km-core, TODO `TransportBackend` tendría que contestar "esto es legado o es de
 * par" —incluido WebRTC, que no tiene dialecto— y el filtro quedaría en la
 * frontera equivocada, con el codec de un medio decidiendo el camino de otro.
 *
 * Con un slot propio, un backend sin dialecto no tiene nada que activar, y el
 * que lo tiene resuelve la clasificación una vez, donde vive su codec.
 */
data class LegacyInboundFrame(
    /** Identidad AUTENTICADA por el relé que entregó el sobre. */
    val from: IdentityId,
    /** El mensaje de km-core ya decodificado del payload. */
    val encoded: Message,
) {
    /**
     * Identificador del mensaje, para correlacionar sin desempaquetar.
     *
     * Es el `messageId` DEL MENSAJE —el que viaja dentro del payload—, no el
     * `messageId` del envelope del cable. Son dos cosas distintas y confundirlas
     * rompe la correspondencia del `ACK`: `RelayDataCodec` lo separa a proposito
     * (el id de la aplicacion es el que el `ACK` referencia; el del envelope es
     * opcional y puede faltar).
     *
     * ## POR QUE NO ES NULABLE (desviacion declarada de la tarea 1.1)
     *
     * La tarea lo pedia como `messageId?`. Es Nullable en el WIRE y NO nullable
     * aqui, y decir lo contrario seria mentir: `encoded` no lo es, y
     * `Message.messageId` tampoco. Un campo que no puede ser `null` declarado
     * como tal solo añade una rama que el compilador sabe muerta, y en un tipo
     * cuya unica mision es ser honesto sobre lo que lleva, eso no se sostiene.
     * Lo que el codec hace con un envelope sin id —fabricar uno, §R1.1/R1.2 de
     * `RelayDataCodec`— ocurre ANTES de construir este marco, y por eso aqui
     * siempre hay uno.
     */
    val messageId: MessageId
        get() = encoded.messageId

    /**
     * Igualdad por CONTENIDO, y no la generada por el compilador.
     *
     * La generada compararía también [messageId], que es un campo DERIVADO de
     * [encoded]; eso haría que dos instancias con el mismo contenido y distinta
     * caché no fueran iguales. Aquí el sobre se compara con `Message.equals`,
     * que ya usa `contentEquals` sobre el payload.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LegacyInboundFrame) return false
        return from == other.from && encoded == other.encoded
    }

    /** Hash coherente con [equals]: el de `from` y el de [encoded]. */
    override fun hashCode(): Int = 31 * from.hashCode() + encoded.hashCode()
}
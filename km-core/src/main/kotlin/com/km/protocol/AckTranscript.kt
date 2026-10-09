package com.km.protocol

import com.km.model.Ack
import com.km.model.AckStatus
import com.km.model.IdentityId
import com.km.model.MessageId

/**
 * Los bytes que se FIRMAN y se VERIFICAN en un `ACK`.
 *
 * ESTA ES LA FUENTE UNICA DEL FORMATO, y es una decision deliberada.
 *
 * `ReceiverImpl` firmaba con una concatenacion y `AckManagerImpl` verificaba
 * con otra identica escrita aparte. Que coincidieran era una coincidencia
 * editorial, no una invariante: cambiar una y no la otra no rompe ningun test
 * de forma visible -- rompe la entrega, que falla en silencio porque
 * `onAckReceived` devuelve `success` tanto si la firma cuadra como si no.
 *
 * Un `ACK` mal firmado que se acepta es peor que uno que se rechaza: el emisor
 * cree que el mensaje llego y no reintenta.
 */
internal object AckTranscript {

    /**
     * `from || to || originalMessageId || status || timestamp`, en UTF-8.
     *
     * Los campos van en orden fijo y sin separadores porque es lo que ya
     * firmaban las dos copias. Los identificadores son de longitud fija (64 hex)
     * y `status` es un enumerado, de modo que la concatenacion no es ambigua.
     */
    fun build(
        from: IdentityId,
        to: IdentityId,
        originalMessageId: MessageId,
        status: AckStatus,
        timestamp: Long,
    ): ByteArray = (
        from.value +
            to.value +
            originalMessageId.value.toString() +
            status.name +
            timestamp.toString()
        ).toByteArray()

    /** El mismo transcript a partir de un `ACK` ya construido. */
    fun build(ack: Ack): ByteArray =
        build(ack.from, ack.to, ack.originalMessageId, ack.status, ack.timestamp)
}
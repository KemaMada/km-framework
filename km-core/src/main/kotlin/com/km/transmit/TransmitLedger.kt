package com.km.transmit

import com.km.unit.OutboundDeliveryState
import com.km.unit.OutboundRecord
import com.km.model.MessageId

/**
 * 3Q.5.2b-B — El ESTADO LOGICO DE TRANSMISION: que envios son durables.
 *
 * ## QUE ES Y POR QUE ESTA FUERA DEL RATCHET Y FUERA DE LA UNIDAD
 *
 * Es la respuesta a una sola pregunta —"este envio, ¿ya se puede
 * reconstruir?"— y su unico consumidor es la capa de transporte, que decide si
 * reenvia. NO la guarda el ratchet, porque un ratchet que supiera si un envio
 * salio por la red habria roto la frontera de §1; y NO la guarda la unidad,
 * porque la unidad es lo que se RECONSTRUYE, no el estado que la reconstruccion
 * produce.
 *
 * ## LA REGLA QUE SOSTIENE ESTA CLASE
 *
 * Un registro entra aqui SOLO despues de que la unidad este confirmada Y
 * verificada. Nunca antes. Un registro en este libro afirma que existe una
 * unidad en disco que se ha releido y comparado byte a byte; meterlo antes
 * afirmaria algo que todavia no se sabe.
 *
 * ## POR QUE SE ORDENA POR `createdOrdinal` Y NO POR EL RECORRIDO DEL MAPA
 *
 * INV-07. `LinkedHashMap` da orden de insercion, y el recorrido de un mapa
 * hash no es un orden que se pueda comparar entre dos ejecuciones. La capa de
 * transporte necesita una lista estable —para victimar, para informar, para
 * comparar dos estados— y el unico campo que §6.9.4 garantiza ordenable es el
 * ordinal de creacion. Por eso [registros] ordena por el.
 */
class TransmitLedger {

    /**
     * `messageId -> registro`. La clave primaria es el mensaje de APLICACION y
     * no la identidad de frame: un mismo `messageId` no puede tener dos
     * unidades, y el valor lo dice si se busca por identidad de frame.
     */
    private val porMensaje = LinkedHashMap<MessageId, OutboundRecord>()

    /**
     * Registra un envio YA durable.
     *
     * El nombre dice lo que la regla exige: quien llama tiene que haber
     * confirmado y verificado antes de llegar aqui. Es el unico metodo que
     * escribe, a proposito, para que no haya dos puertas.
     */
    @Synchronized
    fun registrarDurable(registro: OutboundRecord) {
        porMensaje[registro.messageId] = registro
    }

    /**
     * Reconstruye el libro desde un registro ledido de una unidad.
     *
     * [registrarDurable] y [reconstruir] hacen lo mismo y por eso estan
     * separados: uno dice "lo acabo de hacer durable yo" y el otro "lo estoy
     * leyendo de disco". La diferencia es de CONFIANZA —el primero se apoya en
     * una verificacion que acaba de ocurrir en este proceso, el segundo en una
     * unidad que paso por la misma verificacion en el proceso anterior— y esa
     * diferencia no se puede comprobar dentro de la clase.
     */
    @Synchronized
    fun reconstruir(registro: OutboundRecord) {
        porMensaje[registro.messageId] = registro
    }

    /** Estado de entrega de un envio, o `null` si no se conoce ninguno. */
    @Synchronized
    fun estadoDe(messageId: MessageId): OutboundDeliveryState? = porMensaje[messageId]?.deliveryState

    /** `true` si el envio esta en el libro, y por tanto es durable. */
    @Synchronized
    fun esDurable(messageId: MessageId): Boolean = porMensaje.containsKey(messageId)

    /** Numero de envios durables conocidos. */
    @Synchronized
    fun tamanho(): Int = porMensaje.size

    /** Vacia el libro. */
    @Synchronized
    fun vaciar() = porMensaje.clear()

    /**
     * Todos los registros, en orden de `createdOrdinal` ASC.
     *
     * Ese es el orden de victimizacion de §6.9.1 para el log de salida, asi
     * que el mismo recorrido sirve para informar y para decidir a quien
     * victimizar: si dos listas de este tipo no coincidieran, el emisor
     * contaria una historia y victimaria otra.
     *
     * El desempate es el `messageId`, porque dos registros podem compartir
     * ordinal (el ordinal lo emite quien compone la unidad, y dos envios del
     * mismo tick lo comparten) y sin desempate el orden seria el del mapa.
     */
    @Synchronized
    fun registros(): List<OutboundRecord> = porMensaje.values.sortedWith(
        compareBy({ it.createdOrdinal }, { it.messageId.toString() }),
    )
}

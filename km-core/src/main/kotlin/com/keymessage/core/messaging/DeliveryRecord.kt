package com.keymessage.core.messaging

import com.keymessage.core.model.MessageId
import com.keymessage.core.sf.SecureFrameSpec

/**
 * Identidad de un SecureFrame, tal y como aparece en el wire (3Q.5.1b).
 *
 * ## Que es y que no es
 *
 * Es la posicion del frame en la cadena del ratchet, escrita en el header:
 *
 * ```
 * DH (32 B) || PN (4 B) || N (4 B)  =  40 bytes
 * ```
 *
 * NO es la identidad del mensaje de aplicacion (esa es `messageId`, y viaja
 * cifrada dentro). Son identidades DISTINTAS y ninguna absorbe a la otra.
 *
 * ## Por que se puede leer sin el ratchet
 *
 * Esos tres campos son el AAD: van en el header, en claro y **autenticados**.
 * No hacen falta ninguna clave ni ninguna operacion criptografica para
 * extraerlos, solo leer los bytes. Por eso `fromWire` funciona incluso sobre
 * un frame cuyo ciphertext este corrupto: la identidad de un frame es
 * legible por cualquiera que lo posea.
 *
 * ## Ambito
 *
 * `(DH, PN, N)` es unico dentro de una sesion. Un registro de entrega
 * pertenece a UNA sesion con UN peer, y en ese ambito la identidad no colisiona.
 * Usar un registro entre sesiones mezclaria identidades: por eso el registro
 * se crea por sesion y se destruye con ella.
 */
class FrameIdentity private constructor(val bytes: ByteArray) {

    /** Clave publica DH de la epoca del ratchet. */
    val dhPublicKey: ByteArray get() = bytes.copyOfRange(0, DH_LENGTH)

    /** Longitud de la cadena de envio anterior (`PN`). */
    val previousChainLength: UInt get() = readUint32(bytes, DH_LENGTH)

    /** Numero de mensaje dentro de la cadena (`N`). */
    val messageNumber: UInt get() = readUint32(bytes, DH_LENGTH + 4)

    /**
     * Igualdad por CONTENIDO.
     *
     * Un `data class` con `ByteArray` compararia por referencia, y dos
     * identidades con los mismos bytes serian distintas: el registro nunca
     * encontraria un duplicado, y el fallo seria silencioso.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameIdentity) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String =
        "FrameIdentity(dh=${dhPublicKey.size}B, pn=$previousChainLength, n=$messageNumber)"

    companion object {
        const val DH_LENGTH: Int = SecureFrameSpec.DH_PUBLIC_KEY_LENGTH  // 32
        const val LENGTH: Int = DH_LENGTH + 4 + 4                        // 40

        /** Construye la identidad desde los tres campos del ratchet. */
        fun of(dhPublicKey: ByteArray, previousChainLength: UInt, messageNumber: UInt): FrameIdentity {
            require(dhPublicKey.size == DH_LENGTH) { "dhPublicKey debe tener $DH_LENGTH bytes" }
            val out = ByteArray(LENGTH)
            System.arraycopy(dhPublicKey, 0, out, 0, DH_LENGTH)
            writeUint32(out, DH_LENGTH, previousChainLength)
            writeUint32(out, DH_LENGTH + 4, messageNumber)
            return FrameIdentity(out)
        }

        /**
         * Extrae la identidad de un frame EN CRUDO, sin descifrar y sin el
         * ratchet.
         *
         * Lee solo los 40 bytes del header. No valida el ciphertext ni exige
         * que el frame completo sea coherente, porque la identidad es una
         * propiedad del header, no del contenido.
         *
         * @throws IllegalArgumentException si los bytes no bastan.
         */
        fun fromWire(wire: ByteArray): FrameIdentity {
            require(wire.size >= SecureFrameSpec.CIPHERTEXT_OFFSET) {
                "un frame de ${wire.size}B no contiene header completo"
            }
            val out = ByteArray(LENGTH)
            System.arraycopy(
                wire, SecureFrameSpec.DH_PUBLIC_KEY_OFFSET,
                out, 0, DH_LENGTH,
            )
            System.arraycopy(
                wire, SecureFrameSpec.PREVIOUS_CHAIN_LENGTH_OFFSET,
                out, DH_LENGTH, 4,
            )
            System.arraycopy(
                wire, SecureFrameSpec.MESSAGE_NUMBER_OFFSET,
                out, DH_LENGTH + 4, 4,
            )
            return FrameIdentity(out)
        }

        private fun writeUint32(out: ByteArray, offset: Int, value: UInt) {
            out[offset] = (value shr 24).toByte()
            out[offset + 1] = (value shr 16).toByte()
            out[offset + 2] = (value shr 8).toByte()
            out[offset + 3] = value.toByte()
        }

        private fun readUint32(bytes: ByteArray, offset: Int): UInt {
            val b0 = (bytes[offset].toLong() and 0xFF)
            val b1 = (bytes[offset + 1].toLong() and 0xFF)
            val b2 = (bytes[offset + 2].toLong() and 0xFF)
            val b3 = (bytes[offset + 3].toLong() and 0xFF)
            return (((b0 shl 24) or (b1 shl 16)) or (b2 shl 8) or b3).toUInt()
        }
    }
}

/**
 * Una entrada del registro de entrega, ya fuera de el (`C-01`).
 *
 * ## POR QUE ES UN TIPO Y NO UN `Pair`
 *
 * Porque un `Pair<FrameIdentity, MessageId>` no dice cual es cual, y esta
 * lista acaba en una capa donde la DIRECCION importa: el receptor busca POR
 * IDENTIDAD para saber que mensaje de aplicacion era. Un `Pair` con los dos
 * campos intercambiados compila, y el fallo —un frame legitimo reconocido como
 * duplicado de otro mensaje— no aparece hasta que hay trafico de verdad.
 *
 * Inmutable y con copia defensiva: lo que sale de
 * [DeliveryRecordTable.export] no puede seguir cambiando por debajo de quien
 * lo recibio.
 */
class DeliveryRecordEntry(
    frameIdentity: FrameIdentity,
    /** El mensaje de aplicacion que llego con ese frame. */
    val messageId: MessageId,
) {
    /** Identidad del frame. Copia defensiva. */
    val frameIdentity: FrameIdentity = frameIdentity

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeliveryRecordEntry) return false
        return messageId == other.messageId && frameIdentity == other.frameIdentity
    }

    override fun hashCode(): Int = 31 * frameIdentity.hashCode() + messageId.hashCode()

    override fun toString(): String = "DeliveryRecordEntry($frameIdentity -> $messageId)"
}

/**
 * Registro de entrega: `identidad de frame -> messageId` (3Q.5.1b).
 *
 * ## Direccion
 *
 * La clave primaria es la identidad del FRAME, y el valor es el `messageId`.
 * Nunca al reves. El receptor reconoce un frame repetido y recuerda que
 * mensaje de aplicacion era, que es exactamente lo que necesita para
 * reconstruir el ACK que exige KM-0004 §9.4.
 *
 * ## Que NO guarda
 *
 * Deliberadamente: `applicationDelivered`, `ackSent`, `timestamp`, `transport`
 * y `retryCount`. Cada uno pertenece a otra capa —la de aplicacion, la de
 * senalizacion o la de entrega saliente— y meterlos aqui convertiria un
 * registro de deduplicacion en un repositorio de estado con una sola razon de
 * ser.
 *
 * ## Por que puede ser ACOTADO sin volverse una-condition de seguridad
 *
 * El registro NO es la defensa contra replay. La defensa criptografica es el
 * ratchet, que rechaza `N` ya consumido. Perder o envejecer una entrada solo
 * hace que un frame repetido llegue al ratchet, que lo rechaza igual. El
 * registro es una optimizacion que DEGRADA con seguridad.
 *
 * ## Cuando se escribe
 *
 * Solo despues de que el ratchet haya aceptado el frame y se haya obtenido un
 * `messageId` valido. Nunca de forma provisional: un frame malformado, con
 * AEAD invalida o con un sobre ilegible, no debe crear una entrada que luego
 * parezca un mensaje legitimo.
 */
class DeliveryRecordTable(
    /** Entradas maximas. Al superar, se descarta la menos usada. */
    val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity debe ser positiva, no $capacity" }
    }

    // `accessOrder = true` convierte el mapa en LRU: la lectura de una
    // identidad la refresca, de modo que se conserva lo que esta en uso
    // activo y se descarta lo que lleva mucho sin tocarse.
    private val records = object : LinkedHashMap<FrameIdentity, MessageId>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FrameIdentity, MessageId>?): Boolean =
            size > capacity
    }

    /** `messageId` que se recibio para esa identidad de frame, si se conoce. */
    @Synchronized
    fun lookup(frameIdentity: FrameIdentity): MessageId? = records[frameIdentity]

    /** Registra la asociacion. Se llama SOLO tras una entrega aceptada. */
    @Synchronized
    fun record(frameIdentity: FrameIdentity, messageId: MessageId) {
        records[frameIdentity] = messageId
    }

    /** `true` si el frame ya fue procesado. No cuenta como lectura. */
    @Synchronized
    fun contains(frameIdentity: FrameIdentity): Boolean = records.containsKey(frameIdentity)

    @Synchronized
    fun size(): Int = records.size

    @Synchronized
    fun clear() = records.clear()

    /**
     * Todas las entradas, para poder persistirlas (3Q.5.2b-B, `C-01`).
     *
     * ## POR QUE ESTE METODO EXISTE Y NO UNO DE "RECONSTRUIR DESDE BYTES"
     *
     * Sin el, el registro de entrega es un callejon sin salida: se puede
     * consultar y se puede llenar, pero su contenido no sale del proceso. La
     * tercera pieza de la unidad atomica —el estado de entrega de un envio—
     * no se puede escribir si no hay forma de leer de aqui lo que hay, y
     * `clear()` + `record()`, que ya existen, son la reimportacion.
     *
     * ## POR QUE EL ORDEN ES POR CONTENIDO Y NO EL DEL MAPA
     *
     * `records` es un `LinkedHashMap` con `accessOrder = true`: su recorrido
     * es el orden LRU, que depende de QUE SE HA CONSULTADO, no de QUE HAY. Dos
     * procesos con el mismo contenido en el registro pueden recorrerlo de
     * forma distinta, y una lista que se persiste y se compara tiene que ser
     * la misma. Por eso se ordena por `FrameIdentity` —que compara por
     * contenido— y por el `messageId` como desempate. Es la misma exigencia
     * de INV-07 que gobierna la victimizacion: el orden de una lista que se
     * persiste no puede depender del recorrido de un mapa.
     *
     * @return una copia. Las identidades devuelven copias de sus bytes, asi
     *   que quien reciba la lista no puede modificar el registro por su cuenta.
     */
    @Synchronized
    fun export(): List<DeliveryRecordEntry> = records.entries
        .map { DeliveryRecordEntry(it.key, it.value) }
        .sortedWith(compareBy({ it.frameIdentity.bytes.joinToString("") { b -> "%02x".format(b) } }, { it.messageId.toString() }))

    companion object {
        /**
         * Capacidad por defecto.
         *
         * Suficiente para cubrir la ventana de retransmision de KM-0004 con
         * holgura, sin permitir crecimiento indefinido en una sesion larga.
         */
        const val DEFAULT_CAPACITY: Int = 512
    }
}

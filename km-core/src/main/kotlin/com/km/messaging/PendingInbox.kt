package com.km.messaging

/**
 * 3Q.5.3 — La BANDEJA de frames entrantes sin usar.
 *
 * ## QUE ES Y POR QUE NO ES UNA LISTA
 *
 * `SecureMessagingSession` tenia un `ArrayDeque<ByteArray>` de bytes crudos que
 * se llenaba en [SecureMessagingSession.onTransportBytes] y que nadie vaciaba
 * nunca: `pendingInboundCount()` podia crecer sin limite y el contenido no
 * salia del proceso. Aqui la bandeja sustituye a esa estructura con TRES
 * reglas que un `ArrayDeque` no puede tener, y que son el contrato de D-5:
 *
 *  1. **DEDUPLICACION por [FrameIdentity].** Un frame repetido no se guarda dos
 *     veces. La identidad se lee del header, sin descifrar, y por eso funciona
 *     con un frame cuyo payload no se puede abrir.
 *  2. **FIFO estricto.** La salida es el ORDEN DE LLEGADA, nunca el de un mapa.
 *     INV-07: dos ejecuciones con el mismo contenido tienen que producir los
 *     mismos bytes, y el recorrido de un mapa hash no es un orden comparable.
 *  3. **COTA L2.** Cuando el conjunto no cabe, se descarta POR EL FRENTE (lo
 *     mas antiguo), que es lo que dice "el mas antiguo es el que se va". No es
 *     una eleccion de entrega —cual mensaje se sacrifica es de 3Q.5.4—: es el
 *     overflow de una bandeja acotada, y lo unico que decide es que la bandeja
 *     tiene techo.
 *
 * ## POR QUE NO SABE NADA DEL RATCHET
 *
 * La bandeja no descifra, no mira el estado de la sesion y no sabe si un frame
 * se puede abrir. Solo ordena y acota. Quien descifra es la sesion; quien
 * criptografia tiene es el ratchet. Esta clase no importa ninguno de los dos.
 *
 * ## LA FRONTERA QUE ESTA CLASE SOSTIENE
 *
 * `[FrameIdentity]` y `messageId` son identidades de CAPAS DISTINTAS: la
 * primera es la posicion en la cadena del ratchet y la segunda es el mensaje de
 * aplicacion. Esta clase deduplica por la PRIMERA, y por eso la deduplicacion
 * no puede convertirse en "he visto este mensaje": un mensaje distinto con el
 * mismo texto viaja con distinta identidad, y un mismo mensaje reenviado con
 * el mismo frame llega con la misma.
 */
class PendingInbox(
    /** Techo de bytes del conjunto, medido sobre los wire-frame completos. */
    private val budget: Int = DEFAULT_BUDGET,
) {
    init {
        require(budget > 0) { "el presupuesto de la bandeja tiene que ser positivo, es $budget" }
    }

    /**
     * Los frames, EN ORDEN DE LLEGADA.
     *
     * `LinkedHashMap` con clave [FrameIdentity]: `FrameIdentity` compara por
     * CONTENIDO, de modo que el mapa no puede tener dos claves que sean el
     * mismo frame, y `values` recorre en orden de insercion. Es la misma
     * tecnica que `DeliveryRecordTable`, y por el mismo motivo.
     */
    private val porIdentidad = LinkedHashMap<FrameIdentity, PendingInboundEntry>()

    /** Bytes que ocupan las entradas ahora mismo. */
    private var bytes = 0

    /** Cuantas entradas se han descartado por el frente al superar el techo. */
    private var descartadasPorElFrente = 0uL

    /**
     * Añade un frame. Devuelve `false` si ya estaba: el frame repetido se
     * RECHAZA y no sustituye al que ya estaba.
     *
     * Sustituir en vez de rechazar seria cambiar los bytes conservados por una
     * identidad que el ratchet ya consumio, y eso es exactamente la trampa que
     * [PreparedSend.ciphertext] documenta para el lado de envio.
     */
    fun encolar(wireFrame: ByteArray): Boolean {
        val entrada = PendingInboundEntry(FrameIdentity.fromWire(wireFrame), wireFrame)
        if (porIdentidad.containsKey(entrada.frameIdentity)) return false
        porIdentidad[entrada.frameIdentity] = entrada
        bytes += entrada.wireFrame.size
        purgarPorElFrente()
        return true
    }

    /**
     * Descarta POR EL FRENTE hasta que el conjunto cabe.
     *
     * Se descarta la entrada mas antigua, que es la unica que puede salirse sin
     * cambiar el orden de las que quedan. Si una sola entrada no cabe, no cabe
     * ninguna: se vacia y se dice por que, en vez de dejar una bandeja que
     * rebasa su propio presupuesto.
     */
    private fun purgarPorElFrente() {
        while (bytes > budget) {
            val primero = porIdentidad.entries.firstOrNull() ?: return
            bytes -= primero.value.wireFrame.size
            porIdentidad.remove(primero.key)
            descartadasPorElFrente++
        }
    }

    /** Las entradas, en orden de llegada. Copia: la bandeja sigue viva. */
    fun entradas(): List<PendingInboundEntry> = porIdentidad.values.toList()

    /** Las entradas, en orden de llegada, serializables a la unidad. */
    fun bloque(): List<PendingInboundEntry> = entradas()

    /** `true` si esta identidad ya esta en la bandeja. */
    fun contiene(frameIdentity: FrameIdentity): Boolean = porIdentidad.containsKey(frameIdentity)

    /** Numero de frames en la bandeja. */
    fun tamano(): Int = porIdentidad.size

    /** Bytes que ocupan las entradas ahora mismo. */
    fun bytesOcupados(): Int = bytes

    /** Cuantas entradas se han descartado por el frente en toda la vida del libro. */
    fun descartadas(): ULong = descartadasPorElFrente

    /** `true` si la identidad esta pendiente O ya se descarto por el frente. */
    fun estaVacia(): Boolean = porIdentidad.isEmpty()

    companion object {
        /**
         * Techo por defecto de la bandeja: 256 KiB.
         *
         * Es la cota L2 de `KM52` v2, y aqui y alla es el MISMO numero
         * ([com.km.unit.Km52PendingLimits.MAX_PENDING_INBOUND_BUDGET]).
         * La bandeja acota para que la unidad sea escribible; el codec acota
         * para que una unidad en disco que no lo cumple se rechace entera.
         */
        const val DEFAULT_BUDGET: Int = 256 * 1024

        /** Libro recien hidratado desde las entradas que traia una unidad. */
        fun rehidratada(entradas: List<PendingInboundEntry>): PendingInbox {
            val libro = PendingInbox()
            for (e in entradas) {
                if (libro.porIdentidad.containsKey(e.frameIdentity)) continue
                libro.porIdentidad[e.frameIdentity] = e
                libro.bytes += e.wireFrame.size
            }
            return libro
        }
    }
}
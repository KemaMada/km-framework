package com.km.transmit

import com.km.unit.ChainUseOrdinal
import com.km.unit.ReceiveChainRetention
import com.km.ratchet.ChainIdentifier
import com.km.ratchet.DoubleRatchetSnapshot

/**
 * 3Q.5.2b-B — El libro de ORDEN DE RETENCION de las cadenas de recepcion.
 *
 * ## QUE ES Y POR QUE ESTA AQUI Y NO EN EL RATCHET
 *
 * §6.9.4 de la spec exige un `lastUseOrdinal` por cadena de recepcion, y lo
 * sitúa en la UNIDAD, no en el estado del ratchet. La razon ya la escribio
 * `Km52Types`: `DoubleRatchetSnapshot` esta congelado y auditado campo a campo,
 * y un contador cuya unica funcion es ordenar una politica de eviction no es
 * material criptografico. Meterlo dentro obligaria al ratchet a saber de
 * retencion, que es exactamente la frontera que §1 prohibe.
 *
 * Asi que el ordinal vive en la capa de TRANSMISION, y este libro es quien lo
 * lleva. El ratchet no sabe que existe.
 *
 * ## QUIEN LO MUEVE
 *
 * [anotarUso]. Lo llama la capa que sabe que se ha servido un frame de una
 * cadena —que es la capa de transporte de mensaje, por encima de esta— porque
 * solo ella puede observar eso. Aqui no hay ningun `previewReceive` que lo
 * detecte: mirar las claves retenidas de un ratchet para saber si acaba de
 * servir un frame seria inventar observabilidad que el ratchet no ofrece.
 *
 * ## POR QUE UN CONTADOR Y NO UNA FECHA
 *
 * INV-07: el resultado de aplicar las cotas tiene que ser DETERMINISTA, y
 * prohibido depender del reloj. Un ordinal monotonico de sesion cumple las dos
 * cosas: dos ejecuciones con el mismo estado eligen la MISMA victima, porque
 * el orden no depende de cuando se llego aqui sino de cuantas veces se ha
 * usado cada cadena, en un orden que el propio estado determina.
 *
 * ## POR QUE HAY QUE PODER REHIDRATARLO
 *
 * Porque el ordinal se PERSISTE en la unidad, y ese es el unico motivo de que
 * exista como campo. Si al arrancar un proceso el libro volviera a cero, la
 * unidad leida traeria una tabla de retencion que el libro no conocia, todas las
 * cadenas se reordenarian y la proxima escritura podaria victimas distintas de
 * las que la unidad anterior daba por mas antiguas. La victimizacion
 * determinista solo es determinista si sobrevive al reinicio: por eso
 * [rehidratada] existe, y por eso `restaurar()` la usa.
 */
class ChainRetentionBook {

    /**
     * Clave: identificador de cadena. `ChainIdentifier` compara por CONTENIDO;
     * un `ByteArray` compararia por referencia y el mismo `chainId` en dos
     * fotos distintas seria dos claves distintas del mapa.
     */
    private val ordinales = HashMap<ChainIdentifier, ULong>()

    /**
     * Siguiente ordinal a emitir.
     *
     * Solo crece. El valor maximo se toma ANTES de escribir para que un
     * rehidratado con ordinales altos no se pise a si mismo al primer
     * [anotarUso].
     */
    private var siguiente: ULong = 0uL

    /**
     * Registra que se acaba de servir un frame de esta cadena.
     *
     * El ordinal es "el siguiente que me toca", no "el mayor que veo mas uno":
     * asi dos cadenas usadas en el mismo instante siguen teniendo ordinales
     * distintos y el orden entre ellas es el del contador, que es lo que
     * hace la victimizacion reproducible.
     */
    fun anotarUso(chainId: ByteArray) {
        val id = ChainIdentifier(chainId.copyOf())
        if (siguiente == ULong.MAX_VALUE) {
            // Saturar en silencio haria que dos cadenas distintas compartieran
            // ordinal y el desempate pasara a depender del `chainId` solo. Es
            // determinista, pero deja de reflejar el uso, que es lo que el
            // ordinal existe para expresar. Un error explicito es mejor.
            throw IllegalStateException("el contador de retencion se ha agotado: no cabe mas orden")
        }
        val v = siguiente
        siguiente = v + 1uL
        ordinales[id] = v
    }

    /**
     * La tabla de retencion que cubre EXACTAMENTE las cadenas de [foto].
     *
     * Las cadenas que el libro todavia no conoce se ordenan con ordinales
     * NUEVOS y consecutivos, y en orden canonico de `chainId`. Es lo unico
     * determinista que se puede hacer con una cadena que este libro no ha
     * visto nunca: no tiene historial, y "sin historial" se expresa como "la
     * acabamos de descubrir", no como "la mas antigua de todas". Darle el
     * ordinal mas bajo seria inventar un uso que nadie ha registrado, y la
     * victimizacion tiene que elegir la victima por el uso REAL.
     *
     * El orden canonico importa: si dos cadenas recien descubcidas recibieran
     * ordinales segun el recorrido de un mapa, dos ejecuciones con el mismo
     * estado podrian elegir victimas distintas (INV-07).
     *
     * Una cadena duplicada en la foto se cuenta una sola vez: el mapa del
     * ratchet no puede tener dos entradas con la misma clave publica DH, y una
     * tabla de retencion con la cadena repetida la rechazaria el propio
     * constructor de `ReceiveChainRetention`.
     */
    fun tablaPara(foto: DoubleRatchetSnapshot): ReceiveChainRetention {
        val entradas = foto.receiveChains
            .map { ChainIdentifier(it.chainId.copyOf()) to it.chainId }
            .distinctBy { it.first }
            .sortedBy { hex(it.first.bytes) }
            .map { (id, bytes) ->
                val ordinal = ordinales[id] ?: siguiente.also {
                    siguiente = siguiente + 1uL
                    ordinales[id] = it
                }
                ChainUseOrdinal(bytes, ordinal)
            }
        return ReceiveChainRetention(entradas)
    }

    /**
     * Ordinal conocido de una cadena, o `null` si el libro no la ha visto.
     * Solo para diagnostico y para las pruebas de orden.
     */
    fun ordinalDe(chainId: ByteArray): ULong? = ordinales[ChainIdentifier(chainId)]

    companion object {
        /**
         * Libro recien hidratado desde una tabla que venia en una unidad.
         *
         * El contador sigue al mayor ordinal leido, de modo que un uso
         * posterior no colisiona con un ordinal ya persistido. Sin esto, una
         * cadena usada despues del reinicio podria recibir un ordinal que ya
         * tenia otra, y dos cadenas distintas se ordenarian como si fueran la
         * misma: la victimizacion volveria a depender de otra cosa.
         */
        fun rehidratada(retencion: ReceiveChainRetention): ChainRetentionBook {
            val libro = ChainRetentionBook()
            var maximo = 0uL
            var hay = false
            for (e in retencion.entries) {
                libro.ordinales[ChainIdentifier(e.chainId.copyOf())] = e.lastUseOrdinal
                if (!hay || e.lastUseOrdinal > maximo) {
                    maximo = e.lastUseOrdinal
                    hay = true
                }
            }
            libro.siguiente = if (hay) maximo + 1uL else 0uL
            return libro
        }

        private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    }
}

package com.km.transmit

import com.km.unit.Km52Spec
import com.km.unit.Km52Unit
import com.km.unit.Km52UnitCodec
import com.km.unit.OutboundDeliveryState
import com.km.unit.OutboundRecord
import com.km.messaging.FrameIdentity
import com.km.messaging.PendingInbox
import com.km.model.MessageId
import com.km.ratchet.DoubleRatchetSession
import com.km.ratchet.DoubleRatchetSnapshot
import com.km.ratchet.SymmetricRatchetSnapshot
import com.km.frame.SecureFrameSpec

/**
 * 3Q.5.2b-B — La COSTURA de persistencia write-ahead de un envio.
 *
 * ## LA PROPIEDAD FUNDAMENTAL DE ESTE CHECKPOINT
 *
 * > Nunca avanzar o confirmar el estado logico de transmision basandose en una
 * > unidad `KM52` que todavia no esta completa y valida.
 *
 * Todo el diseno sale de ahi, y en particular del ULTIMO paso del diagrama:
 *
 * ```
 * estado criptografico actual
 *     |- producir snapshot
 *     |- conservar ciphertext ORIGINAL
 *     |- conservar outbound state
 *     v
 *   construir KM52
 *     v
 *   serializar completa
 *     v
 *   checksum                        <- lo pone el codec, no esta clase
 *     v
 *   write-ahead                     <- el medio TIENE la unidad
 *     v
 *   VERIFICAR la unidad persistida  <- releer, comparar, parsear
 *     v
 *   COMMIT                          <- y SOLO aqui avanza el estado logico
 * ```
 *
 * ## POR QUE EL COMMIT VA DESPUES DE VERIFICAR, Y NO DESPUES DE ESCRIBIR
 *
 * Porque **escribir no es comprobar**. [TransmitUnitStore.writeAhead] devuelve
 * cuando el medio ha aceptado los bytes, y hay al menos cuatro maneras
 * normales de que eso no signifique que la unidad esta ahi integra:
 *
 *  1. el medio escribio solo un prefijo y devolvio exito;
 *  2. un sector se perdio y el checksum del sector contiguo no se aviso;
 *  3. el buffer del sistema de ficheros acepto la escritura y el proceso murio
 *     antes de que bajara a disco;
 *  4. la capa intermedia sustituyo la unidad por otra de la misma longitud.
 *
 * En los cuatro casos, confirmar sin releer deja la sesion LOGICAMENTE
 * avanzada sobre una unidad que no existe: el ratchet ya consumio su clave de
 * mensaje, el envio ya figura como durable en el libro, y la unidad que
 * permitiria reconstruirlo no esta. La sesion queda con estado de dos
 * historias, que es lo unico que el contrato entero prohibe (INV-02).
 *
 * Por eso la verificacion no es un extra: es la CONDICION de la confirmacion.
 * [verificar] relee lo que HAY ([TransmitUnitStore.readBack], no lo que se
 * queria escribir), lo compara byte a byte, y ademas lo PARSEA con el mismo
 * codec, porque una unidad que relee correcta y describe otro estado tampoco
 * es la unidad que se queria persistir.
 *
 * ## LA FRONTERA, Y POR QUE VA POR ARRIBA
 *
 * ```
 * persistencia -> ratchet -> delivery       (esto)
 * ratchet -> conoce persistencia -> conoce delivery -> conoce retransmision
 *                                          (PROHIBIDO: es el nucleo de 3Q.5.2b)
 * ```
 *
 * [session] entra aqui como `DoubleRatchetSession` y se usa de DOS formas, y
 * solo dos: [DoubleRatchetSession.snapshot] para leer su estado criptografico y
 * [DoubleRatchetSession.restore] para devolvérselo. Ni una sola operacion de
 * este paquete le pasa otra cosa, ni le devuelve otra cosa, ni le pide una
 * opinion sobre entregas, ACKs, reintentos o transporte. El ratchet aporta su
 * snapshot y se calla.
 *
 * La otra direccion —la que tambien esta prohibida y que es mas facil de
 * colar— este paquete no le DA nada al ratchet: no le pasa el registro de
 * salida, ni el `messageId`, ni la unidad. La composicion de las tres cosas es
 * de aqui, y por eso existe esta clase y no un metodo mas en el ratchet.
 *
 * ## LO QUE ESTA CLASE NO HACE
 *
 * No retransmite (3Q.5.4), no elige transporte, no lleva la cuenta de
 * reintentos ([OutboundRecord] no tiene `resendCount` desde §8.3) y no
 * implementa `MAX_OUTBOUND_RECORDS` (§8.2-J sigue pendiente de decision).
 */
class SecureTransmitJournal(
    private val session: DoubleRatchetSession,
    private val store: TransmitUnitStore,
    private val codec: Km52UnitCodec,
    /** Estado logico de transmision. Solo avanza tras un commit verificado. */
    val ledger: TransmitLedger = TransmitLedger(),
    /**
     * Orden de retencion de las cadenas. Solo esta capa lo conoce.
     *
     * Es `var` y no `val` por una razon concreta: [restaurar] lo REHIDRATA con
     * los ordinales que venian en la unidad, porque esos ordinales son la
     * unica memoria de que cadena se uso antes. Un libro que volviera a cero al
     * arrancar haria que la proxima escritura podara victimas distintas de las
     * que la unidad anterior daba por mas antiguas, y la victimizacion
     * determinista dejaria de serlo entre dos ejecuciones del mismo estado.
     */
    var retention: ChainRetentionBook = ChainRetentionBook(),
    /**
     * La BANDEJA de frames entrantes sin usar (D-5).
     *
     * Es `var` por la misma razon que [retention]: [restaurar] la REHIDRATA con
     * las entradas que venian en la unidad. Un libro que volviera a vacio al
     * arrancar perderia los frames que el otro proceso recibio y todavia no
     * habia usado, y una sesion restaurada sin ellos no continuaria donde
     * estaba.
     *
     * La sesion de mensajeria y este journal comparten la MISMA instancia: es
     * lo que hace que lo que la sesion encola llegue a la unidad sin que nadie
     * tenga que copiar la bandeja de un sitio a otro.
     */
    var inbox: PendingInbox = PendingInbox(),
) {

    /**
     * Write-ahead completo de un envio ya emitido.
     *
     * El envio entra como el frame que el emisor YA produjo, con sus bytes. El
     * journal no lo vuelve a cifrar ni lo vuelve a leer del ratchet: lo
     * conserva. La razon esta en [PreparedSend].
     *
     * @return el registro durable.
     * @throws com.km.unit.RetentionBudgetExceeded /
     *   [com.km.unit.Km52FormatException] si la unidad es
     *   irrepresentable. Se propagan SIN envolver: son errores de FORMATO, y
     *   quien los recibe tiene que poder distinguir "no cabe" de "el medio
     *   fallo" para decidir si reintentar tiene sentido.
     * @throws TransmitStoreFailure si el medio falla.
     * @throws PersistedUnitMismatch si lo persistido no es la unidad escrita.
     */
    fun persistir(envio: PreparedSend): OutboundRecord {
        // 1. ESTADO CRIPTOGRAFICO ACTUAL. `snapshot()` es de solo lectura: no
        //    abre rama especulativa ni avanza nada. Lo que se persiste es el
        //    estado de DESPUES del envio, porque el frame ya salio y el
        //    receptor puede haberlo tenido ya: una unidad con el `N` anterior
        //    dejaria al receptor viendo un `N` que ya consumio.
        val foto: DoubleRatchetSnapshot = session.snapshot()

        // 2. CIPHERTEXT ORIGINAL + IDENTIDAD + ESTADO DE ENTREGA. Se conservan
        //    los bytes del frame; no se recalcula nada.
        val outbound = envio.aRegistro()

        // 3. ORDEN DE RETENCION. Vive aqui y no en la foto: ver
        //    [ChainRetentionBook].
        val tabla = retention.tablaPara(foto)

        // 4. LA UNIDAD. Compuesta aqui, y no en el ratchet, porque es la
        //    UNICA operacion donde los hechos de capas distintas tienen que
        //    quedar en un solo byte-string atomico. En `v2` son cuatro, y el
        //    cuarto es la BANDEJA de frames entrantes sin usar: entra en la misma
        //    unidad que el estado, de modo que una sesion recuperada sabe
        //    tambien que habia recibido algo que todavia no habia usado.
        val unidad = Km52Unit(foto, tabla, outbound, inbox.bloque())

        // 5. SERIALIZAR. El codec cierra la unidad con su checksum y, al
        //    escribir, aplica la eviction determinista de §6.9.2 si el estado
        //    retenido no cabe. Los bytes que salen de aqui son los
        //    definitivos.
        val bytes = codec.serialize(unidad)

        // 6. WRITE-AHEAD. A partir de este punto el medio tiene la unidad y
        //    todavia nadie la da por buena.
        try {
            store.writeAhead(bytes)
        } catch (e: Exception) {
            retirar()
            throw e.asFalloDeMedio(TransmitStage.WRITE_AHEAD, "no se pudo escribir la unidad")
        }

        // 7. VERIFICAR LO PERSISTIDO. Releer, comparar y parsear. Sin esto no
        //    hay commit, y el motivo esta en la cabecera de esta clase.
        try {
            verificar(bytes, store.readBack())
        } catch (e: Exception) {
            retirar()
            throw e
        }

        // 8. COMMIT. El medio deja de tener una unidad pendiente.
        try {
            store.commit()
        } catch (e: Exception) {
            retirar()
            throw e.asFalloDeMedio(TransmitStage.COMMIT, "no se pudo confirmar la unidad")
        }

        // 9. EL ESTADO LOGICO AVANZA. Aqui y no antes: hasta este punto la
        //    unidad existia en el medio pero no se habia comprobado que fuera
        //    la que se queria, y afirmar que el envio es durable sin esa
        //    comprobacion es exactamente lo que la propiedad fundamental
        //    prohibe.
        ledger.registrarDurable(outbound)
        return outbound
    }

    /**
     * Recupera el estado de transmision tras un corte de proceso.
     *
     * ## POR QUE SOLO LEE LO CONFIRMADO
     *
     * Una unidad pendiente de confirmacion es una unidad en la que nadie confio.
     * Tras un corte puede ser un prefijo, puede no ser la que se escribio, o
     * puede no existir. Restaurarla seria FABRICAR estado: una sesion con
     * material que nadie ha verificado puede tener claves que no son de nadie.
     * Por eso [TransmitUnitStore.readCommitted] esta separado de
     * [TransmitUnitStore.readBack] y por eso aqui no se consulta el segundo.
     *
     * ## POR QUE UN FALLO DE LECTURA NO DEJA LA SESION A MEDIAS
     *
     * El codec se ejecuta entero y devuelve una unidad validada, o lanza. La
     * sesion solo se toca despues, con una unidad en la mano. Un
     * [com.km.unit.RetentionBudgetExceeded] (§6.9.5) o un checksum
     * mal no dejan la sesion a medias, y no por un `try`/`catch`: por el ORDEN.
     *
     * @return `null` si no hay nada confirmado. No es un fallo.
     * @throws com.km.unit.Km52FormatException /
     *   [com.km.unit.RetentionBudgetExceeded] si lo confirmado no
     *   es una unidad valida. La sesion queda intacta.
     */
    fun restaurar(): OutboundRecord? {
        val bytes = try {
            store.readCommitted()
        } catch (e: Exception) {
            throw e.asFalloDeMedio(TransmitStage.READ_BACK, "no se pudo leer la unidad confirmada")
        } ?: return null

        // TODO lo que puede fallar, falla aqui, con la sesion intacta.
        val unidad = codec.deserialize(bytes)

        // A partir de aqui ya no puede fallar por el FORMATO: la foto esta
        // construida, sus cadenas son validas y el registro existe.
        session.restore(unidad.snapshot)
        // El registro es OPCIONAL en `v2`: una unidad puede traer estado y
        // bandeja sin ningun envio propio pendiente, y en ese caso el libro de
        // entrega se queda como estaba en vez de recibir un `null`.
        unidad.outbound?.let { ledger.reconstruir(it) }
        retention = ChainRetentionBook.rehidratada(unidad.retention)
        // Y la bandeja se REHIDRATA, no se VACIA: los frames que la unidad
        // dice que habian llegado vuelven a la sesion, en el mismo orden.
        inbox = PendingInbox.rehidratada(unidad.pendingInbound)
        return unidad.outbound
    }

    /** `true` si el medio tiene una unidad pendiente de confirmar. */
    fun hayPendienteSinConfirmar(): Boolean = store.hasPending()

    /** Lo que hay confirmado ahora mismo, o `null`. Diagnostico. */
    fun unidadConfirmada(): ByteArray? = store.readCommitted()

    /**
     * Descarta lo pendiente sin confirmar.
     *
     * Es la contraparte de [retirar] para cuando el proceso sigue vivo y
     * quiere dejar el medio limpio antes de intentar otra vez.
     */
    fun descartarPendiente() = retirar()

    // ==================================================================
    // VERIFICACION
    // ==================================================================

    /**
     * Comprueba que lo que esta en el medio es la unidad que se escribio.
     *
     * Las comprobaciones van en este orden y el orden NO es arbitrario:
     *
     *  1. **ESTA.** Un medio que devuelve `null` no ha escrito nada.
     *  2. **ES UNA UNIDAD.** Se parsea. Aqui es donde un medio que entrego
     *     bytes corruptos recibe su error TIPADO —`Km52ChecksumMismatch`,
     *     `Km52Truncated`, `Km52FormatException`— y no un "no coincide": el
     *     diagnostico de POR QUE fallo la escritura esta en la unidad, y se
     *     pierde si lo sustituyo por una comparacion de bytes.
     *  3. **ES LA MISMA.** Se comparan las dos unidades por CONTENIDO y
     *     ademas byte a byte. El contenido es lo que dice que el estado
     *     persistido es el que se queria persistir; los bytes son lo que
     *     descarta a un medio que guardo otra cosa de la misma longitud, o que
     *     reordeno el cuerpo sin cambiarle el sentido.
     *
     * La comparacion de contenido no puede hacerse contra la unidad que el
     * codigo COMPUSO, porque al escribir el codec puede haber podado claves
     * retenidas (§6.9.2) y entonces la compuesta no seria la escrita. Las dos
     * unidades pasan por el MISMO lector, y eso es lo que hace la comparacion
     * legitima.
     */
    private fun verificar(expected: ByteArray, leido: ByteArray?) {
        if (leido == null) {
            throw PersistedUnitMismatch("el medio no tiene nada escrito")
        }
        val releida = codec.deserialize(leido)
        val referencia = codec.deserialize(expected)
        val diferencia = comparar(referencia, releida)
        if (diferencia != null) {
            throw PersistedUnitMismatch("la unidad releida no es la escrita: $diferencia")
        }
        if (!leido.contentEquals(expected)) {
            throw PersistedUnitMismatch(
                "el medio devolvio ${leido.size} bytes que no son los ${expected.size} escritos, " +
                    "aunque describan el mismo estado",
            )
        }
    }

    /**
     * Compara dos unidades leidas por el mismo codec.
     *
     * @return `null` si son la misma, o la descripcion del primer campo que no
     *   lo es.
     */
    private fun comparar(a: Km52Unit, b: Km52Unit): String? {
        compararFoto(a.snapshot, b.snapshot)?.let { return it }
        if (a.retention.size != b.retention.size) {
            return "tabla de retencion de ${a.retention.size} entradas contra ${b.retention.size}"
        }
        for (e in a.retention.entries) {
            val otro = b.retention.lastUseOrdinal(e.chainId)
            if (otro == null) return "la tabla releida no cubre la cadena ${hex(e.chainId)}"
            if (otro != e.lastUseOrdinal) {
                return "ordinal ${e.lastUseOrdinal} contra $otro en la cadena ${hex(e.chainId)}"
            }
        }
        // La BANDEJA se compara por CONTENIDO y por ORDEN. El orden importa
        // tanto como el contenido: la sesion consume la bandeja en FIFO, asi que
        // un medio que devolviese las mismas entradas en otro orden devolveria
        // un estado distinto, y byte a byte los dos byte-strings serian
        // diferentes pero la comparacion de unidades pasaria.
        if (a.pendingInbound.size != b.pendingInbound.size) {
            return "bandeja de ${a.pendingInbound.size} entradas contra ${b.pendingInbound.size}"
        }
        for (i in a.pendingInbound.indices) {
            val ea = a.pendingInbound[i]
            val eb = b.pendingInbound.getOrNull(i)
                ?: return "la bandeja releida no tiene la entrada $i"
            if (ea.frameIdentity != eb.frameIdentity) {
                return "la entrada $i de la bandeja cambia de identidad: ${ea.frameIdentity} contra ${eb.frameIdentity}"
            }
            if (!ea.wireFrame.contentEquals(eb.wireFrame)) {
                return "la entrada $i de la bandeja tiene otros bytes"
            }
        }
        val oa = a.outbound
        val ob = b.outbound
        if (oa == null || ob == null) {
            if (oa != ob) return "una unidad tiene envio y la otra no"
            return null
        }
        if (oa.recordVersion != ob.recordVersion) return "recVersion ${oa.recordVersion} contra ${ob.recordVersion}"
        if (oa.deliveryState != ob.deliveryState) return "deliveryState ${oa.deliveryState} contra ${ob.deliveryState}"
        if (oa.messageId != ob.messageId) return "messageId"
        if (oa.frameIdentity != ob.frameIdentity) {
            return "frameIdentity ${oa.frameIdentity} contra ${ob.frameIdentity}"
        }
        if (oa.createdOrdinal != ob.createdOrdinal) {
            return "createdOrdinal ${oa.createdOrdinal} contra ${ob.createdOrdinal}"
        }
        if (!oa.ciphertext.contentEquals(ob.ciphertext)) {
            return "el ciphertext persistido y el releido no son los mismos bytes"
        }
        return null
    }

    /**
     * Compara dos fotos del ratchet por CONTENIDO.
     *
     * ## POR QUE NO `stateFingerprint()`
     *
     * Porque la huella de `DoubleRatchetSession` se construye con
     * `dhSelf.publicKey` y no con el escalar privado. Dos fotos con la misma
     * huella pueden tener mitades privadas distintas y no poder hablar nunca.
     * Una verificacion de escritura apoyada en ella pasaria por la razon
     * equivocada: confirmaria una unidad cuyo escalar DH se ha roto, que es
     * justo la unidad que no se debe confirmar. Aqui se compara el escalar
     * privado, que es lo que decide si la sesion restaurada puede hablar con
     * la otra mitad.
     *
     * ## LA UNICA ASIMETRIA QUE SE ACEPTA, Y POR QUE
     *
     * La `receiveChainKey` de la CADENA DE ENVIO no se compara. El layout de
     * §3.1 no la guarda y al leer se deriva como copia de la `sendChainKey`, y
     * en una sesion viva las dos mitades de la cadena de envio dejan de
     * coincidir en cuanto envia su primer mensaje. Es la perdida INERTE que
     * documenta `BinaryKm52UnitCodec`: ninguna operacion del ratchet lee jamas
     * esa mitad. Lo que si se comprueba es que la releida cumple la
     * invariante que la hace inerte: indice de recepcion cero y sin retenidas.
     */
    private fun compararFoto(a: DoubleRatchetSnapshot, b: DoubleRatchetSnapshot): String? {
        if (!a.rootKey.contentEquals(b.rootKey)) return "rootKey"
        if (!a.dhSelf.privateKeyBytes().contentEquals(b.dhSelf.privateKeyBytes())) {
            return "el escalar DH propio, que la huella de la sesion NO cubre"
        }
        if (!a.dhSelf.publicKeyBytes().contentEquals(b.dhSelf.publicKeyBytes())) return "la clave publica DH propia"
        if (!contenidoIgual(a.dhRemote, b.dhRemote)) return "dhRemote"
        if (a.sendMessageNumber != b.sendMessageNumber) {
            return "Ns ${a.sendMessageNumber} contra ${b.sendMessageNumber}"
        }
        if (a.previousChainLength != b.previousChainLength) {
            return "PN ${a.previousChainLength} contra ${b.previousChainLength}"
        }
        val sc = b.sendChain
        if (sc.receiveMessageNumber != 0u) {
            return "la cadena de envio leida tiene Nr=${sc.receiveMessageNumber}: una cadena de envio nunca recibe"
        }
        if (sc.skipped.isNotEmpty()) {
            return "la cadena de envio leida lleva ${sc.skipped.size} claves retenidas"
        }
        if (!a.sendChain.sendChainKey.contentEquals(b.sendChain.sendChainKey)) return "la CKs de la cadena de envio"
        if (a.sendChain.sendMessageNumber != b.sendChain.sendMessageNumber) return "el Ns de la cadena de envio"
        if (a.sendChain.receiveMessageNumber != b.sendChain.receiveMessageNumber) {
            return "el Nr de la cadena de envio"
        }
        if (a.sendChain.skipped.keys != b.sendChain.skipped.keys) return "las retenidas de la cadena de envio"
        if (a.receiveChains.size != b.receiveChains.size) {
            return "${a.receiveChains.size} cadenas de recepcion contra ${b.receiveChains.size}"
        }
        // Orden canonico por `chainId`: el codec escribe las cadenas ordenadas
        // (INV-07) y el recorrido de la lista de la sesion no tiene por que
        // coincidir con el del medio.
        val ordenadas = a.receiveChains.sortedBy { hex(it.chainId) }
        val leidas = b.receiveChains.sortedBy { hex(it.chainId) }
        for (i in ordenadas.indices) {
            val x = ordenadas[i]
            val y = leidas[i]
            if (!x.chainId.contentEquals(y.chainId)) return "la cadena $i no es la misma"
            compararCadena(i, x.ratchet, y.ratchet)?.let { return it }
        }
        return null
    }

    private fun compararCadena(
        indice: Int,
        a: SymmetricRatchetSnapshot,
        b: SymmetricRatchetSnapshot,
    ): String? {
        val p = "cadena $indice"
        if (!a.sendChainKey.contentEquals(b.sendChainKey)) return "$p: CKs"
        if (a.sendMessageNumber != b.sendMessageNumber) return "$p: Ns"
        if (!a.receiveChainKey.contentEquals(b.receiveChainKey)) return "$p: CKr"
        if (a.receiveMessageNumber != b.receiveMessageNumber) return "$p: Nr"
        if (a.skipped.keys != b.skipped.keys) {
            return "$p: ${a.skipped.size} retenidas contra ${b.skipped.size}"
        }
        for ((k, v) in a.skipped) {
            if (!v.contentEquals(b.skipped[k])) return "$p: la retenida N=${k.second} tiene otros bytes"
        }
        return null
    }

    // ==================================================================
    // RETIRADA
    // ==================================================================

    /**
     * Retira lo pendiente de confirmar.
     *
     * Un fallo en [TransmitUnitStore.discardPending] NO enmascara el fallo
     * original: se anade como suprimida. El error que hay que propagar es el
     * del medio al escribir, no el del medio al limpiar, y perder el primero
     * para publicar el segundo seria perder el diagnostico que si sirve.
     */
    private fun retirar() {
        try {
            store.discardPending()
        } catch (e: Exception) {
            e.addSuppressed(
                TransmitStoreFailure(TransmitStage.DISCARD, "no se pudo retirar la unidad pendiente", e),
            )
        }
    }

    private fun Exception.asFalloDeMedio(stage: TransmitStage, mensaje: String): Exception =
        if (this is TransmitStoreFailure) this
        else TransmitStoreFailure(stage, "$mensaje: $this", this)

    private companion object {
        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

        fun contenidoIgual(a: ByteArray?, b: ByteArray?): Boolean = when {
            a == null && b == null -> true
            a == null || b == null -> false
            else -> a.contentEquals(b)
        }
    }
}

/**
 * Un envio que el emisor YA PRODUJO, y que todavia no es durable.
 *
 * ## POR QUE ENTRA EL FRAME COMPLETO Y NO SUS PARTES
 *
 * Porque lo unico que se conserva es lo que salio. Si esta clase tomara el
 * `messageId` por un lado y el ciphertext por otro, la posibilidad de que
 * dejaran de corresponder seria permanente: un `messageId` con el ciphertext de
 * otro envio es una unidad coherente en apariencia y falsa de hecho. Tomando
 * el frame entero, la identidad se LEE de los mismos bytes que el ciphertext,
 * y la correspondencia no se puede romper por accidente.
 */
class PreparedSend(
    /** Identidad del mensaje de APLICACION. Va en la unidad; no va al ratchet. */
    val messageId: MessageId,
    /** El SecureFrame completo, tal y como salio por el cable. */
    wireFrame: ByteArray,
    /** Estado de entrega con el que se persiste. */
    val deliveryState: OutboundDeliveryState = OutboundDeliveryState.PENDIENTE,
    /** Ordinal de creacion (§6.9.4): ordena el log de salida. */
    val createdOrdinal: ULong,
) {
    /** El frame, intacto. Copia defensiva. */
    val wireFrame: ByteArray = wireFrame.copyOf()

    /**
     * Los tres campos de la identidad del frame, LEIDOS de su header.
     *
     * [FrameIdentity.fromWire] es el lector que existe para esto: lee los 40
     * bytes de la identidad SIN descifrar y SIN el ratchet, y por eso funciona
     * sobre un frame cuyo payload no se puede abrir.
     */
    val frameIdentity: FrameIdentity = FrameIdentity.fromWire(this.wireFrame)

    /**
     * El ciphertext del frame, con su tag: los bytes DESPUES del header.
     *
     * ## POR QUE SE CONSERVA Y NO SE REGENERA
     *
     * Un SecureFrame esta autenticado con un AAD que incluye su identidad
     * (`DH || PN || N`) y con una `messageKey` que el ratchet ya consumio al
     * derivarla. Re-cifrar el mismo texto con el estado actual daria un frame
     * con `N+1` y una clave distinta: el receptor tendria una posicion mas en
     * la cadena, veria un salto que nadie marco, y su indice de recepcion
     * avanzaria una de mas. Aqui, ese frame cae en `REPLAY_OR_UNKNOWN` o, peor,
     * se acepta como si fuera el siguiente y el mensaje anterior queda
     * irrecuperable. Un envio "retransmisible" con OTRO frame no es un
     * reenvio: es corrupcion.
     */
    val ciphertext: ByteArray =
        this.wireFrame.copyOfRange(SecureFrameSpec.CIPHERTEXT_OFFSET, this.wireFrame.size)

    init {
        // El `length` del header tiene que describir el frame que realmente
        // se conserva. Sin esta comprobacion, un frame con el `length`
        // desfasado produciria una unidad cuyo ciphertext no es el que el
        // receptor veria al recibir el frame entero.
        val declarado = readUint16BE(this.wireFrame, SecureFrameSpec.LENGTH_OFFSET)
        val real = this.wireFrame.size - SecureFrameSpec.CIPHERTEXT_OFFSET
        require(declarado == real) {
            "el header del frame declara length=$declarado pero el frame lleva $real bytes de ciphertext"
        }
        require(declarado <= Km52Spec.MAX_CIPHERTEXT_LENGTH) {
            "el ciphertext de $declarado bytes excede el maximo ${Km52Spec.MAX_CIPHERTEXT_LENGTH}"
        }
    }

    /**
     * El registro de salida de la unidad.
     *
     * `recordVersion = 1` esta ratificado en §8.1-E. La constante vive aqui y
     * no en el codec porque 3Q.5.2b-A congelo el codec; el valor se contrasta
     * en las pruebas, porque el propio `deserialize` rechaza cualquier otro y
     * una unidad con otra version no llega a existir.
     */
    fun aRegistro(): OutboundRecord = OutboundRecord(
        recordVersion = OUTBOUND_RECORD_VERSION,
        deliveryState = deliveryState,
        messageId = messageId,
        frameIdentity = frameIdentity,
        createdOrdinal = createdOrdinal,
        ciphertext = ciphertext,
    )

    companion object {
        /** §8.1-E, ratificado. Distinto de la version de la UNIDAD (§8.1-D). */
        const val OUTBOUND_RECORD_VERSION: UByte = 1u

        private fun readUint16BE(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }
}

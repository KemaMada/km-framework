package com.km.unit

import com.km.crypto.AgreementKeyGuard
import com.km.crypto.DerivedX25519KeyPair
import com.km.crypto.Hash
import com.km.crypto.HashImpl
import com.km.crypto.X25519
import com.km.messaging.FrameIdentity
import com.km.messaging.PendingInboundEntry
import com.km.model.MessageId
import com.km.ratchet.ChainIdentifier
import com.km.ratchet.DoubleRatchetSnapshot
import com.km.ratchet.ReceiveChainSnapshot
import com.km.ratchet.SymmetricRatchetSnapshot
import com.km.frame.SecureFrameSpec
import java.util.UUID

/**
 * 3Q.5.2b-A — Codec binario de la unidad `KM52`.
 *
 * ## v2 (3Q.5.3): QUE AÑADE
 *
 * ```
 * v1:  [Header 32][SNAPSHOT][OUTBOUND][Checksum 32]
 * v2:  [Header 32][SNAPSHOT][OUTBOUND][PENDING_INBOUND][Checksum 32]
 * ```
 *
 * El bloque nuevo se serializa EN ORDEN DE LLEGADA, nunca en el de un mapa:
 * INV-07 exige que el mismo estado produzca SIEMPRE los mismos bytes, y el
 * recorrido de un `HashMap` no es un orden comparable entre dos ejecuciones.
 *
 * ## QUE HACE Y QUE NO
 *
 * Traduce [Km52Unit] a bytes y de vuelta. NO escribe en ningun almacen, NO
 * decide cuando hacer commit y NO sabe nada de transporte, reintento ni
 * confirmaciones. Es el unico sitio donde vive la validacion de la unidad, y por
 * eso no hay una segunda copia de las reglas en ningun otro tipo.
 *
 * ## LAS DOS ASIMETRIAS DE §6.9.2, Y POR QUE NO SON INCONSISTENCIA
 *
 *  - AL ESCRIBIR el presupuesto es un limite de DATOS: si el estado retenido no
 *    cabe, se PODA de forma determinista y se escribe la unidad ya conforme.
 *  - AL LEER es un limite de VALIDACION: una unidad en disco por encima del
 *    presupuesto es DATO INVALIDO y se RECHAZA entera, sin podar.
 *
 * La razon es que al escribir el estado es VIVO (se puede podar sin ambiguedad:
 * la sesion sigue siendo coherente) y al leer es DETERMINADO (podar en silencio
 * enmascararia corrupcion y elegiria por el usuario que mensajes se pierden, que
 * no es una decision del restaurador). Un `restore` a medias deja una sesion con
 * estado de dos historias, que es justo lo que INV-02 prohibe.
 *
 * En `v2` la misma asimetria rige L2, y con el mismo argumento: al escribir se
 * descarta POR EL FRENTE —lo mas antiguo de la bandeja—, que es una decision
 * que la sesion puede tomar sin ambiguedad porque lo mas antiguo es lo que nadie
 * ha pedido todavia; al leer se rechaza la unidad entera. Lo que NO se hace en
 * ninguno de los dos sentidos es *sustituir*, *podar* o *regenerar* el bloque
 * outbound: 3Q.5.3 no decide que envio se sacrifica, y esa decision no se toma
 * aqui ni por el hecho de que un byte sobre.
 *
 * ## NINGUN ESTADO A MEDIAS
 *
 * [deserialize] es una funcion PURA: construye la unidad entera en variables
 * locales y la devuelve solo al final. Cualquier fallo lanza antes de devolver
 * nada, y el llamante no tiene un objeto a medias que restaurar. Lo unico que
 * muta una sesion viva es `DoubleRatchetSession.restore`, que solo se llama con
 * una unidad ya validada.
 *
 * ## DETERMINISMO (INV-07)
 *
 * Nada aqui depende del orden de iteracion de un mapa ni del reloj. Las cadenas
 * de recepcion se escriben ordenadas por `chainId` y las claves retenidas por
 * `(chainId, N)`, y la victimizacion ordena por campos del propio layout
 * (`lastUseOrdinal`, `N`, `chainId`). El mismo estado produce SIEMPRE los
 * mismos bytes.
 */
interface Km52UnitCodec {
    /**
     * Escribe la unidad.
     *
     * @throws RetentionBudgetExceeded si el estado no cabe en las cotas y no se
     *   puede arreglar podando (demasiadas cadenas).
     * @throws Km52FormatException si el estado es irrepresentable.
     */
    fun serialize(unit: Km52Unit): ByteArray

    /**
     * Lee la unidad.
     *
     * @throws Km52Truncated / Km52BadMagic / UnsupportedUnitVersion /
     *   Km52ChecksumMismatch / Km52FormatException / ForeignSessionMaterial /
     *   RetentionBudgetExceeded. Nunca devuelve una unidad parcial.
     */
    fun deserialize(bytes: ByteArray): Km52Unit
}

/**
 * Implementacion binaria del formato `KM52` v1.
 *
 * Las primitivas entran por inyeccion ([X25519], [Hash]) por la misma razon que
 * en el resto del proyecto: el codec no puede crear material criptografico ni
 * decidir con que algoritmo se resume. `X25519` es imprescindible al LEER: la
 * mitad publica de la clave DH propia no viaja, se RECALCULA del escalar
 * (ver [DerivedX25519KeyPair.derive]).
 */
class BinaryKm52UnitCodec(
    private val x25519: X25519,
    private val hash: Hash = HashImpl(),
) : Km52UnitCodec {

    // ==================================================================
    // ESCRITURA
    // ==================================================================

    override fun serialize(unit: Km52Unit): ByteArray {
        val retencion = exigirTablaDeRetencion(unit.snapshot, unit.retention)
        val original = unit.snapshot

        // 1. La cadena de envio NUNCA recibe, y eso se comprueba antes de
        //    escribir en vez de asumirlo. `commitReceive` solo se llama sobre
        //    `receiveChains` (`DoubleRatchetSession`), asi que su indice de
        //    recepcion es 0 y su almacen de retenidas esta vacio SIEMPRE.
        //
        //    Su `receiveChainKey` SI se pierde al serializar: el layout de §3.1
        //    no la guarda. Es una pérdida INERTE —ninguna operacion del ratchet
        //    lee jamas la mitad de recepcion de la cadena de envio, y solo se
        //    vuelve a escribir al restaurar— y no se puede escribir con una
        //    invariante mas fuerte, porque `commitSend` avanza `sendChainKey` y
        //    deja `receiveChainKey` atras: en una sesion viva, dos mitades de la
        //    cadena de envio dejan de coincidir en cuanto envia su primer
        //    mensaje. Lo que NO es aceptable es un almacen de retenidas ahi:
        //    seria material que el ratchet no puede encontrar y que el orden de
        //    victimizacion no sabe ordenar, porque la ordenacion es por cadena.
        val sc = original.sendChain
        if (sc.receiveMessageNumber != 0u) {
            throw Km52FormatException(
                "la cadena de envio lleva Nr=${sc.receiveMessageNumber}: una cadena de envio nunca " +
                    "recibe, asi que ese indice no describe ningun estado alcanzable",
            )
        }
        if (sc.skipped.isNotEmpty()) {
            throw Km52FormatException(
                "la cadena de envio no puede llevar claves retenidas: una cadena de envio nunca " +
                    "recibe, y sus claves retenidas serian material que el ratchet no puede " +
                    "volver a encontrar (tiene ${sc.skipped.size})",
            )
        }

        // 2. Cotas que la poda NO puede arreglar. Podar es descartar material
        //    RETENIDO; descartar una CADENA entera es perder estado
        //    no-retenido, que §6.9.1 prohibe.
        if (original.receiveChains.size > Km52RetentionLimits.MAX_RECEIVE_CHAINS) {
            throw RetentionBudgetExceeded(
                RetentionRule.RECEIVE_CHAINS,
                original.receiveChains.size.toLong(),
                Km52RetentionLimits.MAX_RECEIVE_CHAINS.toLong(),
            )
        }
        for (cadena in original.receiveChains) {
            if (cadena.ratchet.skipped.size > Km52RetentionLimits.MAX_RETAINED_PER_CHAIN) {
                throw RetentionBudgetExceeded(
                    RetentionRule.PER_CHAIN,
                    cadena.ratchet.skipped.size.toLong(),
                    Km52RetentionLimits.MAX_RETAINED_PER_CHAIN.toLong(),
                )
            }
        }

        // 3. Presupuesto global: se poda de forma determinista hasta entrar.
        val dentro = podarAlPresupuesto(original, retencion)

        val ciphertext = unit.outbound?.ciphertext
        if (ciphertext != null && ciphertext.size > Km52Spec.MAX_CIPHERTEXT_LENGTH) {
            // E-OUT-01 con el disparador de 3Q.5.3: se rechaza porque excede el
            // MAXIMO ESTRUCTURAL del campo `length` del SecureFrame v1. El
            // registro NO se sustituye, NO se poda y NO se regenera para que
            // quepa: 3Q.5.3 no decide que envio se sacrifica, y el que lo
            // decidiera serian las bytes de un envio que ya salio.
            throw Km52FormatException(
                "el ciphertext de ${ciphertext.size} bytes excede el maximo estructural " +
                    "${Km52Spec.MAX_CIPHERTEXT_LENGTH} del campo length; el registro se rechaza entero, " +
                    "no se sustituye ni se poda",
            )
        }

        // L2 AL ESCRIBIR: limite de DATOS. Se descarta POR EL FRENTE, en orden
        // inverso al de llegada, hasta que el bloque cabe. No se poda el FINAL:
        // lo que se descarta es lo mas antiguo, y lo mas reciente es lo que el
        // emisor acaba de enviar.
        val pendiente = podarInboxPorElFrente(unit.pendingInbound)

        val snap = encodeSnapshot(dentro, retencion)
        val out = unit.outbound?.let { encodeOutbound(it) } ?: ByteArray(0)
        val inb = encodePendingInbound(pendiente)
        // `out` YA lleva el ciphertext al final: es el ultimo campo del bloque
        // OUTBOUNDRECORD. Escribirlo otra vez aqui duplicaria los bytes y dejaria
        // la unidad con 20 bytes de mas que su propio `totalLen`.
        val total = Km52Spec.HEADER_LENGTH.toLong() + snap.size + out.size + inb.size +
            Km52Spec.CHECKSUM_LENGTH
        // L4. Es DEFENSIVO: una unidad legal no llega aqui (maximo 601 593 B), asi
        // que dispararse significa que hay un defecto o que la entrada es
        // corrupta. Se comprueba ANTES de reservar el buffer, no despues.
        if (total > Km52PendingLimits.DEFENSIVE_UNIT_BOUND) {
            throw Km52UnitTooLarge(
                declared = total,
                limit = Km52PendingLimits.DEFENSIVE_UNIT_BOUND.toLong(),
                bloque = "escrito",
            )
        }
        val outBytes = java.io.ByteArrayOutputStream(total.toInt())
        val cabecera = encodeHeader(
            snap.size.toLong(), out.size.toLong(), (ciphertext?.size ?: 0).toLong(), inb.size.toLong(), total,
        )
        outBytes.write(cabecera, 0, cabecera.size)
        outBytes.write(snap, 0, snap.size)
        outBytes.write(out, 0, out.size)
        outBytes.write(inb, 0, inb.size)
        val cuerpo = outBytes.toByteArray()
        val unidad = cuerpo + hash.sha256(cuerpo)
        return unidad
    }

    /**
     * Devuelve las entradas que CABEN en L2, descartando las MAS ANTIGUAS.
     *
     * El recorte es determinista porque el orden de entrada ya lo es (FIFO de
     * la bandeja, INV-07) y el recorte es siempre por el frente: dos
     * ejecuciones del mismo conjunto dejan el mismo conjunto.
     *
     * ## POR QUE EL FINAL NO SE TOCA NUNCA
     *
     * Porque lo mas reciente es lo que el emisor acaba de poner en el cable. Si
     * al superarse el presupuesto se descartara el final, el mensaje acabaria de
     * salir y se perderia sin que nadie lo supiera, y el que se perdiera seria
     * siempre el ultimo: la bandeja se convertiria en una cuenta atrasada que
     * solo puede pagar el mensaje mas nuevo.
     */
    private fun podarInboxPorElFrente(entradas: List<PendingInboundEntry>): List<PendingInboundEntry> {
        val tamanoDe = { e: PendingInboundEntry -> Km52Spec.PENDING_ENTRY_FIXED_LENGTH + e.wireFrame.size }
        var total = entradas.sumOf { tamanoDe(it).toLong() }
        if (total <= Km52PendingLimits.MAX_PENDING_INBOUND_BUDGET) return entradas
        var primero = 0
        while (primero < entradas.size && total > Km52PendingLimits.MAX_PENDING_INBOUND_BUDGET) {
            total -= tamanoDe(entradas[primero])
            primero++
        }
        return entradas.subList(primero, entradas.size)
    }

    // ==================================================================
    // LECTURA
    // ==================================================================

    override fun deserialize(bytes: ByteArray): Km52Unit {
        // --- 0. La cabecera tiene que estar ENTERA antes de mirarla. --------
        if (bytes.size < Km52Spec.HEADER_LENGTH) {
            throw Km52Truncated(Km52Spec.HEADER_LENGTH.toLong(), bytes.size)
        }

        // --- 1. Identidad y version. Cortan ANTES de cualquier longitud. ----
        val magic = bytes.copyOfRange(Km52Spec.MAGIC_OFFSET, Km52Spec.MAGIC_OFFSET + 4)
        if (!magic.contentEquals(Km52Spec.MAGIC)) {
            throw Km52BadMagic(magic.joinToString("") { "%02x".format(it) })
        }
        val version = bytes[Km52Spec.UNIT_VERSION_OFFSET].toUByte()
        if (version != Km52Spec.UNIT_VERSION) {
            throw UnsupportedUnitVersion(version)
        }

        // --- 2. Campos reservados: a cero o el byte-string no es v2. -------
        if (bytes[Km52Spec.FLAGS_OFFSET] != 0.toByte()) {
            throw Km52FormatException("flags a distinto de cero en v2: ${bytes[Km52Spec.FLAGS_OFFSET]}")
        }
        if (readUint16(bytes, Km52Spec.RESERVED16_OFFSET) != 0u) {
            throw Km52FormatException("reserved a distinto de cero en v2")
        }
        for (i in Km52Spec.RESERVED2_OFFSET until Km52Spec.HEADER_LENGTH) {
            if (bytes[i] != 0.toByte()) {
                throw Km52FormatException("reserved2 a distinto de cero en v2 (byte $i)")
            }
        }

        // --- 3. totalLen EXACTO, antes de leer un solo byte de payload. ----
        val snapshotLen = readUint32(bytes, Km52Spec.SNAPSHOT_LEN_OFFSET)
        val outboundLen = readUint32(bytes, Km52Spec.OUTBOUND_LEN_OFFSET)
        val ciphertextLen = readUint32(bytes, Km52Spec.CIPHERTEXT_LEN_OFFSET)
        val inboundLen = readUint32(bytes, Km52Spec.PENDING_INBOUND_LEN_OFFSET)
        val totalLen = readUint32(bytes, Km52Spec.TOTAL_LEN_OFFSET)
        // En `Long` porque los campos son u32 y su suma no cabe en un Int con
        // signo: un total de 2^32 bytes daria negativo y pasaria la
        // comprobacion de truncamiento.
        //
        // `ciphertextLen` NO suma aqui: el ciphertext es el ULTIMO campo del
        // bloque OUTBOUNDRECORD, asi que ya esta dentro de `outboundLen`.
        // Ver la nota sobre la aritmetica de §3.1 en [Km52Spec.OUTBOUND_FIXED_LENGTH].
        val esperado = Km52Spec.HEADER_LENGTH.toLong() + snapshotLen.toLong() + outboundLen.toLong() +
            inboundLen.toLong() + Km52Spec.CHECKSUM_LENGTH
        if (totalLen.toLong() != esperado) {
            throw Km52FormatException(
                "totalLen incoherente: el header declara $totalLen pero sus bloques suman $esperado",
            )
        }
        // `ciphertextLen` es un campo REDUNDANTE, y por eso se contrasta en vez
        // de confiar en el: si no cuadra con el tamano del bloque, la unidad
        // describe dos longitudes distintas para el mismo campo.
        //
        // El caso "no hay outbound" tiene que quedar FUERA de la resta: sin
        // registro, `outboundLen` vale cero, y `0 - 72` daria MENOS setenta y
        // dos, que no es el tamano de ningun campo. Por eso se comprueba la
        // AUSENCIA antes de contrastar, y no despues.
        if (outboundLen.toLong() == 0L) {
            if (ciphertextLen.toLong() != 0L) {
                throw Km52FormatException(
                    "la unidad no tiene bloque OUTBOUNDRECORD pero declara $ciphertextLen de ciphertext",
                )
            }
        } else {
            if (outboundLen.toLong() < Km52Spec.OUTBOUND_FIXED_LENGTH.toLong()) {
                throw Km52FormatException(
                    "el bloque OUTBOUNDRECORD declara $outboundLen bytes, menos que sus " +
                        "${Km52Spec.OUTBOUND_FIXED_LENGTH} fijos",
                )
            }
            if (outboundLen.toLong() - Km52Spec.OUTBOUND_FIXED_LENGTH != ciphertextLen.toLong()) {
                throw Km52FormatException(
                    "ciphertextLen vale $ciphertextLen pero el bloque OUTBOUNDRECORD lleva " +
                        "${outboundLen.toLong() - Km52Spec.OUTBOUND_FIXED_LENGTH}: el mismo campo con dos longitudes",
                )
            }
        }
        // El bloque inbound tiene forma `count(4) || entrada*`, y cada entrada
        // es `48 + wire` con `wire >= 44` (los 44 B del header de un SecureFrame
        // v1). Su longitud SOLO es coherente si se puede descomponer en eso.
        //
        // ## EL CASO `count = 0` ES LEGAL, Y SON 4 BYTES
        //
        // Una sesion sin frames entrantes pendientes tiene que poder
        // persistirse, y su bloque no desaparece: [encodePendingInbound] escribe
        // siempre el `count`, aunque valga cero. Comparar la longitud con la de
        // UNA ENTRADA COMPLETA rechazaba ese bloque de 4 bytes con
        // "declara 4 bytes, menos que una entrada completa de 48" —una unidad
        // perfectamente legal— porque el minimo se TOMABA como el de un bloque
        // con al menos una entrada, que no es lo mismo que el minimo del bloque.
        //
        // Lo que no es legal es una longitud indecomposable: de 1 a 3 bytes no
        // hay ni `count`, y de 5 a 95 no hay ni una entrada con su wire minimo.
        val minimoBloqueInbound =
            (Km52Spec.PENDING_INBOUND_COUNT_LENGTH + Km52Spec.PENDING_ENTRY_FIXED_LENGTH +
                SecureFrameSpec.CIPHERTEXT_OFFSET).toLong()
        if (inboundLen.toLong() != 0L &&
            inboundLen.toLong() != Km52Spec.PENDING_INBOUND_COUNT_LENGTH.toLong() &&
            inboundLen.toLong() < minimoBloqueInbound
        ) {
            throw Km52FormatException(
                "el bloque PENDING_INBOUND declara $inboundLen bytes, una longitud que no se puede " +
                    "descomponer en count(4) mas entradas de ${Km52Spec.PENDING_ENTRY_FIXED_LENGTH} " +
                    "mas un wire-frame de al menos ${SecureFrameSpec.CIPHERTEXT_OFFSET}",
            )
        }
        // L4 ANTES de reservar nada. Es DEFENSIVO: el maximo legal es 601 593 B,
        // asi que un total de mas de 1 MiB es un byte-string invalido o un
        // defecto, y en los dos casos la respuesta es el rechazo COMPLETO.
        if (totalLen.toLong() > Km52PendingLimits.DEFENSIVE_UNIT_BOUND) {
            throw Km52UnitTooLarge(
                declared = totalLen.toLong(),
                limit = Km52PendingLimits.DEFENSIVE_UNIT_BOUND.toLong(),
                bloque = "declarado en el header",
            )
        }
        if (totalLen.toLong() < Km52Spec.MIN_UNIT_LENGTH) {
            throw Km52FormatException("totalLen $totalLen es menor que el minimo ${Km52Spec.MIN_UNIT_LENGTH}")
        }
        if (bytes.size.toLong() < totalLen.toLong()) {
            throw Km52Truncated(totalLen.toLong(), bytes.size)
        }
        if (bytes.size.toLong() > totalLen.toLong()) {
            throw Km52FormatException(
                "sobran ${bytes.size - totalLen.toInt()} bytes: la unidad es canonica y no admite cola",
            )
        }

        // --- 4. Integridad, antes de interpretar NADA del cuerpo. -----------
        val cuerpo = bytes.copyOf(totalLen.toInt() - Km52Spec.CHECKSUM_LENGTH)
        val esperadoHash = hash.sha256(cuerpo)
        val obtenidoHash = bytes.copyOfRange(
            totalLen.toInt() - Km52Spec.CHECKSUM_LENGTH, totalLen.toInt(),
        )
        if (!esperadoHash.contentEquals(obtenidoHash)) {
            throw Km52ChecksumMismatch(hex(esperadoHash), hex(obtenidoHash))
        }

        // --- 5. Bloques. Cada uno tiene que consumir EXACTAMENTE su longitud.
        val cursor = Km52Spec.SNAPSHOT_OFFSET
        val finSnapshot = cursor + snapshotLen.toInt()
        val finOutbound = finSnapshot + outboundLen.toInt()
        val finInbound = finOutbound + inboundLen.toInt()
        val finChecksum = totalLen.toInt() - Km52Spec.CHECKSUM_LENGTH
        if (finInbound != finChecksum) {
            throw Km52FormatException(
                "los bloques del header suman ${finInbound - cursor} y el cuerpo tiene " +
                    "${finChecksum - cursor} antes del checksum",
            )
        }
        val (snapshot, retencion) = decodeSnapshot(cuerpo, cursor, finSnapshot)
        val outbound = decodeOutbound(cuerpo, finSnapshot, finOutbound, ciphertextLen.toInt())
        val pendiente = decodePendingInbound(cuerpo, finOutbound, finInbound)
        return Km52Unit(snapshot, retencion, outbound, pendiente)
    }

    // ==================================================================
    // BLOQUE SNAPSHOT
    // ==================================================================

    /**
     * Lee el bloque SNAPSHOT con VALIDACION DE COTAS DURANTE LA LECTURA.
     *
     * Las cotas se comprueban en DOS PASADAS y no al final, y el motivo es de
     * coste: los `count(2)` de las cadenas se leen TODOS antes de leer ni una
     * sola clave retenida. Una unidad manipulada que anuncie cuatro millones de
     * claves se rechaza habiendo leido 8 cabeceras de 114 B, y no habiendose
     * reservado 272 MB para una sesion que no existe.
     *
     * El `chainId` de cada clave retenida se compara con el de su cadena ANTES
     * de aceptarla: una clave archivada bajo otra cadena es material que esta
     * sesion no puede volver a encontrar, y admitiria dejaria un frame
     * irrecuperable sin que nada lo indicase.
     */
    private fun decodeSnapshot(
        b: ByteArray,
        from: Int,
        to: Int,
    ): Pair<DoubleRatchetSnapshot, ReceiveChainRetention> {
        val rootKey = b.copyOfRange(from, from + 32)
        val dhSelfScalar = b.copyOfRange(from + 32, from + 64)
        val present = b[from + 64].toInt()
        if (present != 0 && present != 1) {
            throw Km52FormatException("dhRemotePresent vale $present, y solo puede ser 0 o 1")
        }
        var p = from + 65
        val dhRemote = if (present == 1) {
            val r = b.copyOfRange(p, p + 32)
            p += 32
            // La frontera criptografica del motor, reutilizada tal cual: con 32
            // bytes, la longitud no dice nada, y una clave de orden pequeno
            // abortaria el ratchet en el primer `agree` con un error que
            // pareceria de red.
            if (AgreementKeyGuard.isLowOrder(r)) {
                throw ForeignSessionMaterial(
                    "la DH remota de la unidad es una clave publica de orden pequeno: no puede " +
                        "servir a esta sesion, el secreto compartido seria todo cero (RFC 7748 6.1)",
                )
            }
            r
        } else {
            null
        }
        val ns = readUint32(b, p)
        val pn = readUint32(b, p + 4)
        p += 8

        // Cadena de envio: 42 B + sus retenidas (que no puede haber).
        val sendChainKey = b.copyOfRange(p, p + 32)
        val sendNs = readUint32(b, p + 32)
        val sendNr = readUint32(b, p + 36)
        val sendCount = readUint16(b, p + 40).toInt()
        p += Km52Spec.SEND_CHAIN_LENGTH
        if (sendCount != 0) {
            throw Km52FormatException(
                "la cadena de envio anuncia $sendCount claves retenidas: una cadena de envio nunca " +
                    "recibe, asi que ese material no seria recuperable",
            )
        }

        val numCadenas = readUint16(b, p).toInt()
        p += 2
        if (numCadenas > Km52RetentionLimits.MAX_RECEIVE_CHAINS) {
            throw RetentionBudgetExceeded(
                RetentionRule.RECEIVE_CHAINS,
                numCadenas.toLong(),
                Km52RetentionLimits.MAX_RECEIVE_CHAINS.toLong(),
            )
        }

        // --- Pasada 1: solo las cabeceras y los `count`. --------------------
        class Cabecera(
            val chainId: ByteArray,
            val lastUseOrdinal: ULong,
            val sendChainKey: ByteArray,
            val sendMessageNumber: UInt,
            val receiveChainKey: ByteArray,
            val receiveMessageNumber: UInt,
            val skippedCount: Int,
            /**
             * Donde empiezan SUS claves retenidas.
             *
             * El layout INTERCALA: cabecera de la cadena, sus retenidas,
             * cabecera de la siguiente. Por eso la primera pasada no puede
             * leer todas las cabeceras seguidas —la cabecera siguiente esta
             * detras de las retenidas de la anterior— y tiene que saltar por
             * encima de ellas guardando el punto de vuelta.
             */
            val entriesOffset: Int,
        )

        val cabeceras = ArrayList<Cabecera>(numCadenas)
        var totalRetenidas = 0L
        val vistas = ArrayList<ChainIdentifier>(numCadenas)
        for (i in 0 until numCadenas) {
            requireEnLosLimites(b, p, Km52Spec.RECEIVE_CHAIN_LENGTH, to, "cadena de recepcion $i")
            val chainId = b.copyOfRange(p, p + 32)
            val ordinal = readUint64(b, p + 32)
            val cks = b.copyOfRange(p + 40, p + 72)
            val cns = readUint32(b, p + 72)
            val ckr = b.copyOfRange(p + 76, p + 108)
            val cnr = readUint32(b, p + 108)
            val count = readUint16(b, p + 112).toInt()
            // salto por encima de las retenidas de ESTA cadena, sin leerlas
            requireEnLosLimites(b, p + Km52Spec.RECEIVE_CHAIN_LENGTH, count * Km52Spec.SKIPPED_ENTRY_LENGTH, to, "las claves retenidas de la cadena $i")
            val entries = p + Km52Spec.RECEIVE_CHAIN_LENGTH
            p = entries + count * Km52Spec.SKIPPED_ENTRY_LENGTH

            if (count > Km52RetentionLimits.MAX_RETAINED_PER_CHAIN) {
                throw RetentionBudgetExceeded(
                    RetentionRule.PER_CHAIN,
                    count.toLong(),
                    Km52RetentionLimits.MAX_RETAINED_PER_CHAIN.toLong(),
                )
            }
            val id = ChainIdentifier(chainId)
            if (vistas.any { it == id }) {
                throw Km52FormatException("hay dos cadenas de recepcion con la misma clave publica DH")
            }
            vistas.add(id)
            totalRetenidas += count
            cabeceras.add(Cabecera(chainId, ordinal, cks, cns, ckr, cnr, count, entries))
        }

        // El presupuesto se comprueba con los `count` ya leidos y ANTES de leer
        // las claves. Es la validacion de §6.9.2 al leer: LÍMITE DE VALIDACIÓN,
        // no de trabajo. No se poda: una unidad sobredimensionada es un
        // byte-string que no cumple el contrato de la version.
        val bytesRetenidos = totalRetenidas * Km52RetentionLimits.SKIPPED_ENTRY_LENGTH
        if (bytesRetenidos > Km52RetentionLimits.MAX_TOTAL_RETAINED_BUDGET) {
            throw RetentionBudgetExceeded(
                RetentionRule.TOTAL_BYTES,
                bytesRetenidos,
                Km52RetentionLimits.MAX_TOTAL_RETAINED_BUDGET.toLong(),
            )
        }

        // --- Pasada 2: las claves retenidas, en el MISMO orden. -------------
        val cadenas = cabeceras.map { cab ->
            val skipped = LinkedHashMap<Pair<ChainIdentifier, UInt>, ByteArray>(cab.skippedCount.coerceAtLeast(1))
            p = cab.entriesOffset
            for (k in 0 until cab.skippedCount) {
                requireEnLosLimites(b, p, Km52Spec.SKIPPED_ENTRY_LENGTH, to, "clave retenida $k")
                val idBytes = b.copyOfRange(p, p + 32)
                val n = readUint32(b, p + 32)
                val clave = b.copyOfRange(p + 36, p + 68)
                p += Km52Spec.SKIPPED_ENTRY_LENGTH
                if (!idBytes.contentEquals(cab.chainId)) {
                    throw ForeignSessionMaterial(
                        "una clave retenida de la cadena ${hex(cab.chainId)} esta archivada bajo " +
                            "otra cadena (${hex(idBytes)}): esta sesion no podria volver a encontrarla",
                    )
                }
                if (n >= cab.receiveMessageNumber) {
                    throw Km52FormatException(
                        "clave retenida con N=$n en una cadena cuyo Nr es ${cab.receiveMessageNumber}: " +
                            "una clave retenida siempre es anterior al indice de recepcion",
                    )
                }
                val id = ChainIdentifier(idBytes)
                if (skipped.containsKey(id to n)) {
                    throw Km52FormatException("dos claves retenidas con el mismo (chainId, N)")
                }
                skipped[id to n] = clave
            }
            ReceiveChainSnapshot(
                cab.chainId,
                SymmetricRatchetSnapshot(
                    sendChainKey = cab.sendChainKey,
                    sendMessageNumber = cab.sendMessageNumber,
                    receiveChainKey = cab.receiveChainKey,
                    receiveMessageNumber = cab.receiveMessageNumber,
                    skipped = skipped,
                ),
            )
        }

        if (p != to) {
            throw Km52FormatException(
                "el bloque SNAPSHOT consume ${p - from} bytes de los ${to - from} que declara",
            )
        }
        if (ns != sendNs) {
            throw Km52FormatException(
                "la unidad lleva Ns=$ns pero su cadena de envio lleva Ns=$sendNs: una foto que se " +
                    "contradiga a si misma no describe ningun estado alcanzable",
            )
        }
        val retencion = ReceiveChainRetention(cabeceras.map { ChainUseOrdinal(it.chainId, it.lastUseOrdinal) })
        val snapshot = construirSnapshot(rootKey, dhSelfScalar, dhRemote, ns, pn, sendChainKey, sendNs, cadenas)
        return snapshot to retencion
    }

    private fun construirSnapshot(
        rootKey: ByteArray,
        dhSelfScalar: ByteArray,
        dhRemote: ByteArray?,
        ns: UInt,
        pn: UInt,
        sendChainKey: ByteArray,
        sendNs: UInt,
        cadenas: List<ReceiveChainSnapshot>,
    ): DoubleRatchetSnapshot {
        val dhSelf = try {
            DerivedX25519KeyPair.derive(dhSelfScalar, x25519)
        } catch (e: IllegalArgumentException) {
            throw ForeignSessionMaterial(
                "el escalar DH propio de la unidad no sirve como clave: ${e.message}",
            )
        }
        return try {
            DoubleRatchetSnapshot(
                rootKey = rootKey,
                dhSelf = dhSelf,
                dhRemote = dhRemote,
                sendMessageNumber = ns,
                previousChainLength = pn,
                sendChain = SymmetricRatchetSnapshot(
                    sendChainKey = sendChainKey,
                    sendMessageNumber = sendNs,
                    // Derivada, no guardada: una cadena de envio nunca recibe
                    // (comprobado al escribir y al leer).
                    receiveChainKey = sendChainKey.copyOf(),
                    receiveMessageNumber = 0u,
                    skipped = emptyMap(),
                ),
                receiveChains = cadenas,
            )
        } catch (e: IllegalArgumentException) {
            // Los `require` del tipo (longitudes, `chainId` repetidos, `Ns`
            // incoherente) son el ultimo RED de la lectura: si uno salta, la
            // unidad describiria un estado que ninguna sesion puede tener, y
            // hay que decirlo como lo que es en vez de dejar que salga un
            // `IllegalArgumentException` sin contexto.
            throw Km52FormatException("el estado de la unidad no es un estado de sesion posible: ${e.message}")
        }
    }

    // ==================================================================
    // BLOQUE OUTBOUNDRECORD
    // ==================================================================

    private fun decodeOutbound(
        b: ByteArray,
        from: Int,
        to: Int,
        ciphertextLen: Int,
    ): OutboundRecord? {
        // Un bloque de longitud CERO es "no hay envio pendiente", no "un envio de
        // tamano cero": la longitud se contrasta arriba y una unidad sin envio
        // legitima tiene `outboundLen = 0` y `ciphertextLen = 0`.
        if (to == from) return null
        val recordVersion = b[from].toUByte()
        if (recordVersion != OutboundRecordVersion) {
            throw Km52FormatException("recVersion $recordVersion no soportada: solo 1")
        }
        val stateCode = b[from + 1].toUByte()
        val state = OutboundDeliveryState.fromCode(stateCode)
            ?: throw Km52FormatException("deliveryState $stateCode no valido: el rango es 1..4")
        if (readUint16(b, from + 2) != 0u) {
            throw Km52FormatException("reserved de OUTBOUNDRECORD a distinto de cero en v1")
        }
        var msb = 0L
        var lsb = 0L
        for (i in 0 until 8) {
            msb = (msb shl 8) or (b[from + 4 + i].toLong() and 0xFFL)
            lsb = (lsb shl 8) or (b[from + 12 + i].toLong() and 0xFFL)
        }
        val messageId = MessageId.from(UUID(msb, lsb))
        val frameIdentityLen = readUint32(b, from + 20)
        if (frameIdentityLen.toLong() != Km52Spec.FRAME_IDENTITY_LENGTH.toLong()) {
            throw Km52FormatException(
                "frameIdentityLen vale $frameIdentityLen y el formato exige " +
                    "${Km52Spec.FRAME_IDENTITY_LENGTH}",
            )
        }
        val identidad = b.copyOfRange(from + 24, from + 64)
        val createdOrdinal = readUint64(b, from + 64)
        val ciphertext = b.copyOfRange(from + 72, to)
        if (ciphertext.size != ciphertextLen) {
            throw Km52FormatException(
                "el bloque OUTBOUNDRECORD trae ${ciphertext.size} bytes de ciphertext y el header " +
                    "declara $ciphertextLen",
            )
        }
        if (ciphertextLen > Km52Spec.MAX_CIPHERTEXT_LENGTH) {
            throw Km52FormatException(
                "ciphertextLen $ciphertextLen excede el maximo ${Km52Spec.MAX_CIPHERTEXT_LENGTH}",
            )
        }
        return OutboundRecord(
            recordVersion = recordVersion,
            deliveryState = state,
            messageId = messageId,
            // `FrameIdentity.fromWire` NO sirve aqui: lee esos 40 bytes en las
            // posiciones que ocupan DENTRO de un SecureFrame (offsets 4..43), y
            // le dariamos solo 40 bytes, menos que su propio header de 44.
            // Se reconstruye desde los tres campos, que es lo que la identidad
            // ES.
            //
            // Y los dos contadores se leen BIG-ENDIAN a proposito: la
            // identidad de frame son 40 bytes DEL CABLE, y en el cable
            // `SecureFrame` es big-endian. La unidad es little-endian en sus
            // PROPIOS enteros (longitudes, `Ns`, `PN`, ordinales); el material
            // criptografico y las identidades viajan como bytes opacos en su
            // orden canonico. Es la divergencia ratificada de R-FMT-ENDIAN-01,
            // y leerlos en little-endian devolveria un `PN` multiplicado por
            // 2^24 que no corresponde a ningun frame emitido nunca.
            frameIdentity = FrameIdentity.of(
                identidad.copyOfRange(0, 32),
                readUint32BE(identidad, 32),
                readUint32BE(identidad, 36),
            ),
            createdOrdinal = createdOrdinal,
            ciphertext = ciphertext,
        )
    }

    // ==================================================================
    // ESCRITURA DE LOS BLOQUES
    // ==================================================================

    private fun encodeHeader(
        snapshotLen: Long,
        outboundLen: Long,
        ciphertextLen: Long,
        inboundLen: Long,
        total: Long,
    ): ByteArray {
        val out = ByteArray(Km52Spec.HEADER_LENGTH)
        System.arraycopy(Km52Spec.MAGIC, 0, out, Km52Spec.MAGIC_OFFSET, 4)
        out[Km52Spec.UNIT_VERSION_OFFSET] = Km52Spec.UNIT_VERSION.toByte()
        out[Km52Spec.FLAGS_OFFSET] = 0
        out.putU16(Km52Spec.RESERVED16_OFFSET, 0u)
        out.putU32(Km52Spec.SNAPSHOT_LEN_OFFSET, snapshotLen)
        out.putU32(Km52Spec.OUTBOUND_LEN_OFFSET, outboundLen)
        out.putU32(Km52Spec.CIPHERTEXT_LEN_OFFSET, ciphertextLen)
        out.putU32(Km52Spec.TOTAL_LEN_OFFSET, total)
        out.putU32(Km52Spec.PENDING_INBOUND_LEN_OFFSET, inboundLen)
        // reserved2 (los ultimos 4 B) ya esta a cero.
        return out
    }

    private fun encodeSnapshot(s: DoubleRatchetSnapshot, retencion: ReceiveChainRetention): ByteArray {
        val sc = s.sendChain
        val cadenas = s.receiveChains
            .map { it to (retencion.lastUseOrdinal(it.chainId)
                ?: throw Km52FormatException("la tabla de retencion no cubre una cadena del snapshot")) }
            // Orden canonico por `chainId`. Sin esto, el orden dependeria del
            // mapa de la sesion y los bytes no serian reproducibles (INV-07).
            .sortedBy { it.first.chainId.copyOf().let(::hex) }
        val retenidas = cadenas.sumOf { it.first.ratchet.skipped.size }
        val tamano = Km52Spec.SNAPSHOT_FIXED_LENGTH + Km52Spec.SEND_CHAIN_LENGTH +
            cadenas.size * (Km52Spec.RECEIVE_CHAIN_LENGTH + 2) +
            retenidas * Km52Spec.SKIPPED_ENTRY_LENGTH
        val out = java.io.ByteArrayOutputStream(tamano)

        out.write(s.rootKey)
        out.write(s.dhSelf.privateKeyBytes())
        out.write(if (s.dhRemote != null) 1 else 0)
        s.dhRemote?.let { out.write(it) }
        out.putU32(s.sendMessageNumber.toLong())
        out.putU32(s.previousChainLength.toLong())

        out.write(sc.sendChainKey)
        out.putU32(sc.sendMessageNumber.toLong())
        out.putU32(sc.receiveMessageNumber.toLong())
        out.putU16(0u)                            // count de retenidas: cero

        out.putU16(cadenas.size.toUInt())
        for ((cadena, ordinal) in cadenas) {
            out.write(cadena.chainId)
            out.putU64(ordinal)
            out.write(cadena.ratchet.sendChainKey)
            out.putU32(cadena.ratchet.sendMessageNumber.toLong())
            out.write(cadena.ratchet.receiveChainKey)
            out.putU32(cadena.ratchet.receiveMessageNumber.toLong())
            val claves = cadena.ratchet.skipped.entries
                .sortedWith(compareBy({ hex(it.key.first.bytes) }, { it.key.second }))
            out.putU16(claves.size.toUInt())
            for (entrada in claves) {
                out.write(entrada.key.first.bytes)
                out.putU32(entrada.key.second.toLong())
                out.write(entrada.value)
            }
        }
        return out.toByteArray()
    }

    private fun encodeOutbound(r: OutboundRecord): ByteArray {
        val out = ByteArray(Km52Spec.OUTBOUND_FIXED_LENGTH + r.ciphertext.size)
        out[0] = r.recordVersion.toByte()
        out[1] = r.deliveryState.code.toByte()
        out.putU16(2, 0u)
        val msb = r.messageId.value.mostSignificantBits
        val lsb = r.messageId.value.leastSignificantBits
        for (i in 0 until 8) {
            out[4 + i] = ((msb shr (8 * (7 - i))) and 0xFFL).toByte()
            out[12 + i] = ((lsb shr (8 * (7 - i))) and 0xFFL).toByte()
        }
        out.putU32(20, Km52Spec.FRAME_IDENTITY_LENGTH.toLong())
        System.arraycopy(r.frameIdentity.bytes, 0, out, 24, Km52Spec.FRAME_IDENTITY_LENGTH)
        out.putU64(64, r.createdOrdinal)
        System.arraycopy(r.ciphertext, 0, out, 72, r.ciphertext.size)
        return out
    }

    // ==================================================================
    // BLOQUE PENDING_INBOUND  (v2)
    //
    //   count(4) || entrada*
    //   entrada := frameIdentityLen(4) + frameIdentity(40) + wireLen(4) + wire
    //
    // `count` NO tiene cota propia: la limita L2, que se mide en BYTES del
    // bloque entero. Un `u32` da margen de sobra para el presupuesto mas
    // pequeno de una entrada (48 + 44 = 92 B), que es 2850 entradas.
    // ==================================================================

    private fun encodePendingInbound(entradas: List<PendingInboundEntry>): ByteArray {
        if (entradas.isEmpty()) {
            // El bloque NO desaparece cuando esta vacio: vale `count = 0`, que
            // son 4 bytes. Un bloque de longitud cero seria ambiguo con "no hay
            // bloque" y obligaria al lector a distinguir dos cosas que en v2 son
            // la misma.
            return ByteArray(Km52Spec.PENDING_INBOUND_COUNT_LENGTH)
        }
        val tamano = Km52Spec.PENDING_INBOUND_COUNT_LENGTH +
            entradas.sumOf { Km52Spec.PENDING_ENTRY_FIXED_LENGTH + it.wireFrame.size }
        val out = java.io.ByteArrayOutputStream(tamano)
        out.putU32(entradas.size.toLong())
        for (e in entradas) {
            out.putU32(Km52Spec.FRAME_IDENTITY_LENGTH.toLong())
            out.write(e.frameIdentity.bytes)
            out.putU32(e.wireFrame.size.toLong())
            out.write(e.wireFrame)
        }
        return out.toByteArray()
    }

    /**
     * Lee el bloque `PENDING_INBOUND` con VALIDACION DE COTAS EN DOS PASADAS.
     *
     * ## POR QUE EN DOS PASADAS, Y NO UNA
     *
     * Por el mismo motivo que el bloque SNAPSHOT ([decodeSnapshot]): el `count(4)`
     * se lee ANTES de leer ni una sola entrada, y las longitudes de todas las
     * entradas se verifican ANTES de reservar nada. Una unidad manipulada que
     * anuncie cuatro millones de entradas se rechaza habiendo leido cuatro
     * bytes, y no habiendose reservado 400 MB para una sesion que no existe.
     *
     * ## LA ASIMETRIA DE §6.9, AQUI IGUAL
     *
     * Al leer, L2 es un limite de VALIDACION: se rechaza la unidad ENTERA. No se
     * poda por el frente ni se descartan las entradas que "sobran". Podar al
     * leer enmascararia corrupcion y elegiria por el usuario que mensajes se
     * pierden, que no es una decision del restaurador.
     */
    private fun decodePendingInbound(
        b: ByteArray,
        from: Int,
        to: Int,
    ): List<PendingInboundEntry> {
        if (to == from) return emptyList()
        requireEnLosLimites(b, from, 4, to, "el count del bloque PENDING_INBOUND")
        val count = readUint32(b, from).toLong()

        // --- Pasada 1: el budget, sin leer entradas. --------------------------
        // El minimo de una entrada es `48 + 44` (los 44 B del header v1 mas el
        // tag minimo), y con el `count(4)` delante. Si el count por si solo no
        // cabe en L2, la unidad es invalida sin haber tocado una entrada.
        val minimoPorEntrada = (Km52Spec.PENDING_ENTRY_FIXED_LENGTH + 44).toLong()
        val minimo = 4 + count * minimoPorEntrada
        if (minimo > Km52PendingLimits.MAX_PENDING_INBOUND_BUDGET) {
            throw PendingInboundBudgetExceeded(
                actual = minimo,
                limit = Km52PendingLimits.MAX_PENDING_INBOUND_BUDGET.toLong(),
            )
        }

        // --- Pasada 2: las entradas, en el MISMO orden. ----------------------
        val entradas = ArrayList<PendingInboundEntry>(count.coerceAtMost(1024L).toInt())
        var p = from + 4
        for (i in 0 until count) {
            requireEnLosLimites(
                b, p, Km52Spec.PENDING_ENTRY_FIXED_LENGTH, to,
                "la entrada PENDING_INBOUND $i",
            )
            val identityLen = readUint32(b, p)
            if (identityLen.toLong() != Km52Spec.FRAME_IDENTITY_LENGTH.toLong()) {
                throw Km52FormatException(
                    "frameIdentityLen de la entrada $i vale $identityLen y el formato exige " +
                        "${Km52Spec.FRAME_IDENTITY_LENGTH}",
                )
            }
            val identidad = b.copyOfRange(p + 4, p + 4 + Km52Spec.FRAME_IDENTITY_LENGTH)
            val wireLen = readUint32(b, p + 4 + Km52Spec.FRAME_IDENTITY_LENGTH).toLong()
            val inicioWire = p + Km52Spec.PENDING_ENTRY_FIXED_LENGTH
            requireEnLosLimites(b, inicioWire.toInt(), wireLen.toInt(), to, "el wire-frame de la entrada $i")
            val wire = b.copyOfRange(inicioWire.toInt(), inicioWire.toInt() + wireLen.toInt())
            p = inicioWire.toInt() + wireLen.toInt()

            // La identidad guardada tiene que ser la que LLEVA el header del
            // frame. Si no lo son, la unidad describe dos cosas distintas sobre
            // el mismo frame, y la bandeja que se restaurase de ella
            // deduplicaria por una identidad que no es la del frame.
            //
            // El `require` de [PendingInboundEntry] —que exige que el frame tenga
            // header completo— NO puede ser el que hable aqui: un byte-string
            // manipurado tiene que salir del codec como [Km52FormatException],
            // y un `IllegalArgumentException` sin contexto seria el mismo
            // "fallo de programacion" que el propio codec pretende no generar.
            if (wire.size < SecureFrameSpec.CIPHERTEXT_OFFSET) {
                throw Km52FormatException(
                    "la entrada PENDING_INBOUND $i lleva un wire-frame de ${wire.size} bytes, menos " +
                        "que los ${SecureFrameSpec.CIPHERTEXT_OFFSET} del header de un SecureFrame v1",
                )
            }
            val delHeader = FrameIdentity.fromWire(wire)
            val declarada = FrameIdentity.of(
                identidad.copyOfRange(0, 32),
                readUint32BE(identidad, 32),
                readUint32BE(identidad, 36),
            )
            if (delHeader != declarada) {
                throw ForeignSessionMaterial(
                    "la entrada PENDING_INBOUND $i declara la identidad $declarada pero su header " +
                        "lleva $delHeader: la unidad describe dos identidades para el mismo frame",
                )
            }
            entradas += PendingInboundEntry(declarada, wire)
        }

        if (p != to) {
            throw Km52FormatException(
                "el bloque PENDING_INBOUND consume ${p - from} bytes de los ${to - from} que declara",
            )
        }

        // L2 exacto, ya con las entradas leidas. La pasada 1 dio la cota INFERIOR;
        // esta es la REAL, y las dos hacen falta: la primera evita reservar, la
        // segunda comprueba lo que de verdad ocupa.
        val real = (to - from).toLong()
        if (real > Km52PendingLimits.MAX_PENDING_INBOUND_BUDGET) {
            throw PendingInboundBudgetExceeded(
                actual = real,
                limit = Km52PendingLimits.MAX_PENDING_INBOUND_BUDGET.toLong(),
            )
        }

        // Dos entradas con la MISMA identidad no pueden existir: la identidad es
        // unica por frame. Se comprueba por PARES porque el numero de entradas
        // esta acotado por L2 y la comparacion por pares es gratis; un
        // `contentHashCode` podria dar dos identidades iguales como distintas y
        // dejaria pasar justo la que esta prohibida.
        for (i in entradas.indices) {
            for (j in i + 1 until entradas.size) {
                if (entradas[i].frameIdentity == entradas[j].frameIdentity) {
                    throw Km52FormatException(
                        "hay dos entradas PENDING_INBOUND con la misma FrameIdentity " +
                            "${entradas[i].frameIdentity}",
                    )
                }
            }
        }
        return entradas
    }

    // ==================================================================
    // PODA DETERMINISTA AL ESCRIBIR
    // ==================================================================

    private class Victima(val chainId: ByteArray, val n: UInt, val ordinal: ULong)

    /**
     * Devuelve un snapshot cuyas claves retenidas caben en el presupuesto, o
     * el mismo si ya cabian.
     *
     * El victimario lo fija §6.9.1 con campos DEL PROPIO LAYOUT, y en este orden:
     * `lastUseOrdinal` ASC entre cadenas, `N` ASC dentro de una cadena, y
     * `chainId` ASC como desempate. El desempate no es decorativo: dos cadenas
     * con el mismo ordinal NO tienen un orden natural, y sin el la eleccion
     * dependeria del recorrido del mapa — es decir, del reloj y del azar.
     */
    private fun podarAlPresupuesto(
        s: DoubleRatchetSnapshot,
        retencion: ReceiveChainRetention,
    ): DoubleRatchetSnapshot {
        val total = s.receiveChains.sumOf { it.ratchet.skipped.size }
        if (total <= Km52RetentionLimits.MAX_TOTAL_RETAINED_KEYS) return s
        val sobran = total - Km52RetentionLimits.MAX_TOTAL_RETAINED_KEYS
        val candidatos = s.receiveChains.flatMap { cadena ->
            val ordinal = retencion.lastUseOrdinal(cadena.chainId)
                ?: throw Km52FormatException("la tabla de retencion no cubre una cadena del snapshot")
            cadena.ratchet.skipped.keys.map { Victima(cadena.chainId, it.second, ordinal) }
        }
        val eliminadas = candidatos
            .sortedWith(compareBy({ it.ordinal }, { it.n }, { hex(it.chainId) }))
            .take(sobran)
            .map { it.chainId to it.n }
            .toSet()
        if (eliminadas.isEmpty()) {
            throw RetentionBudgetExceeded(
                RetentionRule.TOTAL_BYTES,
                total.toLong() * Km52RetentionLimits.SKIPPED_ENTRY_LENGTH,
                Km52RetentionLimits.MAX_TOTAL_RETAINED_BUDGET.toLong(),
            )
        }
        val nuevas = s.receiveChains.map { cadena ->
            val supervivientes = cadena.ratchet.skipped
                .filterKeys { !eliminadas.contains(cadena.chainId to it.second) }
            ReceiveChainSnapshot(
                cadena.chainId,
                SymmetricRatchetSnapshot(
                    sendChainKey = cadena.ratchet.sendChainKey,
                    sendMessageNumber = cadena.ratchet.sendMessageNumber,
                    receiveChainKey = cadena.ratchet.receiveChainKey,
                    receiveMessageNumber = cadena.ratchet.receiveMessageNumber,
                    skipped = supervivientes,
                ),
            )
        }
        val podada = DoubleRatchetSnapshot(
            rootKey = s.rootKey,
            dhSelf = s.dhSelf,
            dhRemote = s.dhRemote,
            sendMessageNumber = s.sendMessageNumber,
            previousChainLength = s.previousChainLength,
            sendChain = s.sendChain,
            receiveChains = nuevas,
        )
        if (podada.receiveChains.sumOf { it.ratchet.skipped.size } > Km52RetentionLimits.MAX_TOTAL_RETAINED_KEYS) {
            throw RetentionBudgetExceeded(
                RetentionRule.TOTAL_BYTES,
                total.toLong() * Km52RetentionLimits.SKIPPED_ENTRY_LENGTH,
                Km52RetentionLimits.MAX_TOTAL_RETAINED_BUDGET.toLong(),
            )
        }
        return podada
    }

    // ==================================================================
    // COMPLETITUD DE LA TABLA DE RETENCION
    // ==================================================================

    private fun exigirTablaDeRetencion(
        s: DoubleRatchetSnapshot,
        retencion: ReceiveChainRetention,
    ): ReceiveChainRetention {
        for (cadena in s.receiveChains) {
            if (retencion.lastUseOrdinal(cadena.chainId) == null) {
                throw Km52FormatException(
                    "la tabla de retencion (ordinal de retencion) no cubre la cadena " +
                        "${hex(cadena.chainId)}: sin ordinal no hay victimizable determinable",
                )
            }
        }
        if (retencion.size != s.receiveChains.size) {
            throw Km52FormatException(
                "la tabla de retencion tiene ${retencion.size} entradas y el snapshot tiene " +
                    "${s.receiveChains.size} cadenas: la tabla tiene que cubrirlas exactamente",
            )
        }
        return retencion
    }

    // ==================================================================
    // ENTEROS LITTLE-ENDIAN
    //
    // Nota de precedencia, la misma que en `BinarySecureFrameCodec`: en Kotlin
    // `shl` y `or` son del MISMO nivel y se asocian por la IZQUIERDA, asi que
    // cada desplazamiento va parentizado. Encadenarlos sin parentesis produce
    // `(a shl 24 or b) shl 16` en vez de `(a shl 24) or (b shl 16)`.
    // ==================================================================

    private fun ByteArray.putU16(offset: Int, value: UInt) {
        this[offset] = (value and 0xFFu).toByte()
        this[offset + 1] = ((value shr 8) and 0xFFu).toByte()
    }

    private fun ByteArray.putU32(offset: Int, value: Long) {
        this[offset] = (value and 0xFFL).toByte()
        this[offset + 1] = ((value shr 8) and 0xFFL).toByte()
        this[offset + 2] = ((value shr 16) and 0xFFL).toByte()
        this[offset + 3] = ((value shr 24) and 0xFFL).toByte()
    }

    private fun ByteArray.putU64(offset: Int, value: ULong) {
        for (i in 0 until 8) this[offset + i] = ((value shr (8 * i)) and 0xFFuL).toByte()
    }

    private fun java.io.ByteArrayOutputStream.putU16(value: UInt) {
        write((value and 0xFFu).toInt())
        write(((value shr 8) and 0xFFu).toInt())
    }

    private fun java.io.ByteArrayOutputStream.putU32(value: Long) {
        for (i in 0 until 4) write(((value shr (8 * i)) and 0xFFL).toInt())
    }

    private fun java.io.ByteArrayOutputStream.putU64(value: ULong) {
        for (i in 0 until 8) write(((value shr (8 * i)) and 0xFFuL).toInt())
    }

    private fun readUint16(bytes: ByteArray, offset: Int): UInt =
        (((bytes[offset].toLong() and 0xFFL) or ((bytes[offset + 1].toLong() and 0xFFL) shl 8))).toUInt()

    private fun readUint32(bytes: ByteArray, offset: Int): UInt {
        val b0 = (bytes[offset].toLong() and 0xFFL)
        val b1 = (bytes[offset + 1].toLong() and 0xFFL)
        val b2 = (bytes[offset + 2].toLong() and 0xFFL)
        val b3 = (bytes[offset + 3].toLong() and 0xFFL)
        val v = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        return v.toUInt()
    }

    /**
     * Enteros del CABLE, en big-endian.
     *
     * Solo para la identidad de frame, que son bytes del SecureFrame. Ver
     * R-FMT-ENDIAN-01 y la nota de [decodeOutbound].
     */
    private fun readUint32BE(bytes: ByteArray, offset: Int): UInt =
        (((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)).toUInt()

    private fun readUint64(bytes: ByteArray, offset: Int): ULong {
        var v = 0uL
        for (i in 0 until 8) v = v or (((bytes[offset + i].toLong() and 0xFFL).toULong()) shl (8 * i))
        return v
    }

    private fun requireEnLosLimites(b: ByteArray, offset: Int, need: Int, limite: Int, que: String) {
        if (offset < 0 || offset + need > limite || limite > b.size) {
            throw Km52FormatException("el bloque se acaba antes de $que (offset $offset, limite $limite)")
        }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private companion object {
        /**
         * Version del REGISTRO, independiente de la version de la unidad
         * (§8.1-E). Se escriben en campos distintos y se comprueban por
         * separado: cambiar uno no obliga a cambiar el otro.
         */
        const val OutboundRecordVersion: UByte = 1u
    }
}

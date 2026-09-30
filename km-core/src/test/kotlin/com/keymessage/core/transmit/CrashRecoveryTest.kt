package com.keymessage.core.transmit

import com.keymessage.core.km52.Km52ChecksumMismatch
import com.keymessage.core.km52.Km52FormatException
import com.keymessage.core.km52.Km52Spec
import com.keymessage.core.km52.Km52Truncated
import com.keymessage.core.km52.Km52Unit
import com.keymessage.core.km52.OutboundDeliveryState
import com.keymessage.core.km52.OutboundRecord
import com.keymessage.core.ratchet.DoubleRatchetSession
import com.keymessage.core.sf.SecureFrameSpec
import com.keymessage.core.sf.SecureRatchetProtocol
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 3Q.5.2c — CRASH / RECOVERY: las invariantes de `KM52` frente a una
 * interrupcion, en el punto exacto.
 *
 * ## QUE DEMUESTRA ESTE ARCHIVO, Y QUE NO
 *
 * Que la recuperacion es una FUNCION PURA DEL MEDIO: dada la unidad
 * confirmada que hay, devuelve exactamente el estado criptografico y el
 * registro de salida que la dejaron, y dada la ausencia de esa unidad no
 * inventa nada. Ni una linea de produccion ha cambiado: este checkpoint no
 * anade ningun mecanismo de persistencia, solo demuestra que los que ya
 * estan se sostienen cuando el proceso deja de existir en mitad de ellos.
 *
 * ## LA REGLA QUE HACE QUE ESTAS PRUEBAS MIDAN ALGO: RECUPERAR SOBRE UNA
 * CENTINELA
 *
 * Si el proceso que recovering arranco YA en el estado que la unidad
 * describe, `restaurar()` no tiene nada que hacer: el estado esta ahi
 * igual, y una Implementacion que se borrara a si misma —un
 * `session.restore()` eliminado, un `receiveChains.clear()` olvidado— pasaria
 * estas pruebas sin que nadie se entere. Se_mutaciono y se midio: las dos
 * cosas pasaban.
 *
 * Por eso toda recuperacion de este archivo va SOBRE una
 * [GuionDeterminista.sesionSentinel], que esta en otro estado, y por eso
 * [afirmarQueLaCentinelaNoSabia] se ejecuta ANTES de recuperar. Sin la
 * centinela, "el estado recuperado es el correcto" y "el estado nunca se ha
 * movido" son la misma comprobacion.
 *
 * ## DOS GUARDIANES DISTINTOS PARA LA MISMA MUTACION
 *
 * `receiveChains.clear()` borrado de [DoubleRatchetSession.restore] es el
 * fallo mas silencioso del archivo, y lo cubren DOS pruebas que miran cosas
 * distintas, a proposito:
 *
 *  - `CRASH-07` mira el TAMAÑO del juego de reception: dos unidades de una
 *    MISMA historia, la nueva y luego la vieja, y cuenta.
 *  - `CRASH-07b` mira un DESCIFRADO: dos unidades de historias DISTINTAS, y
 *    exige que un frame que solo descifra con la primera deje de descifrar
 *    cuando la segunda se restaura encima. Byte a byte, con el escalar DH
 *    privado implicito en la operacion, nunca con `stateFingerprint()`.
 *
 * La razon de que sean dos esta escrita en `CRASH-07b`: dentro de una sola
 * historia el juego de cadenas solo crece, y una restauracion que se sumara
 * en vez de sobreescribir NO deja nada que observar cuando la segunda unidad
 * es la nueva. Los dos guardianes no se sustituyen: cada uno se muere por
 * una mutacion que el otro no ve.
 *
 * ## LO QUE ESTA FUERA, Y POR QUE NO SE CRUZA
 *
 * La recuperacion NO es una politica de entrega. Devuelve el
 * `OutboundRecord` como DATO —forma parte de la unidad atomica— y no decide
 * a quien reintentar, cuando, cuantas veces, ni que hacer con el estado de
 * entrega. `CRASH-11` lo comprueba: el estado de entrega se RECUPERA tal
 * cual y la recuperacion no lo avanza, y el paquete de transmision no
 * contiene reloj, azar, contadores ni vocabulario de reintento.
 */
class CrashRecoveryTest {

    /**
     * Punto del generador determinista en el que se hace TODO comparaison.
     *
     * El guion de este archivo genera unas pocas claves DH; 1000 esta muy
     * por encima, asi que ningun envio del guion lo alcanza y dos sesiones
     * comparadas aqui coinciden SI Y SOLO SI su estado coincide.
     */
    private val PUNTO_DE_COMPARACION: Int = 1000

    @TempDir
    lateinit var tmp: File

    // ===================================================================
    // UTILIDADES
    // ===================================================================

    private fun journal(
        sesion: DoubleRatchetSession,
        medio: TransmitUnitStore,
        g: GuionDeterminista,
    ) = SecureTransmitJournal(sesion, medio, g.codec(), TransmitLedger(), ChainRetentionBook())

    /** Una sesion en la que recuperar, con un clon ANTERIOR a la recuperacion. */
    private class Recuperacion(
        val j: SecureTransmitJournal,
        val sesion: DoubleRatchetSession,
        /** Clone de la centinela ANTES de recuperar. */
        val antes: DoubleRatchetSession,
    )

    /**
     * Monta una recuperacion SOBRE UNA CENTINELA.
     *
     * La centinela se construye con otras claves ([sesionSentinel]) y por
     * tanto en un estado que no es el que la unidad describe. Recuperar ahi
     * es lo que hace OBSERVABLE el `session.restore()`: sin esto, la prueba
     * mediria que el estado no se ha movido, que es otra cosa.
     */
    private fun recuperarSobre(medio: TransmitUnitStore, g: GuionDeterminista): Recuperacion {
        val sesion = g.sesionSentinel()
        val antes = clonDe(sesion, g)
        val j = journal(sesion, medio, g)
        afirmarQueLaCentinelaNoSabia(antes, g, "antes de recuperar")
        return Recuperacion(j, sesion, antes)
    }

    /**
     * Cifra de control en un PUNTO FIJO del generador determinista.
     *
     * ## POR QUE HACE FALTA EL PUNTO
     *
     * La primera clave DH de una sesion la genera el ratchet con
     * `generateKeyPair()`, y en este guion el generador es un CONTADOR. Si
     * dos sesiones han recorrido guiones distintos, sus contadores estan en
     * sitios distintos y su "frame siguiente" sale distinto —no por su
     * estado, sino por cuanto han generado antes—. Comparar sin fijar el
     * punto mide el historial y no el estado, y es la clase de fallo que
     * hace pasar una prueba por la razon equivocada.
     *
     * Por eso toda comparacion de este archivo se hace en
     * [PUNTO_DE_COMPARACION], un punto que el guion nunca alcanza. Fijarlo
     * hace que el criterio dependa SOLO del estado, que es lo que se quiere
     * medir.
     *
     * ## POR QUE UN CLON Y NO LA SESION VIVA
     *
     * Porque `encrypt()` CONSUME la cadena de envio: cifrar con la sesion
     * viva para compararla cambiaria el estado que se iba a comparar.
     *
     * ## POR QUE NUNCA `stateFingerprint()`
     *
     * Porque la huella se construye con `dhSelf.publicKey` y NO cubre el
     * escalar DH privado: dos sesiones con la misma huella pueden tener
     * mitades privadas distintas y no poder hablar nunca. Una prueba que la
     * usara pasaria por la razon equivocada —daria por buena una sesion con
     * el escalar roto, que es justo la unidad que no se debe confirmar—.
     * Precedentes: `KM52-01b`, `KM52-01c`, `KM52B-10b`, `KM52B-04b`.
     */
    private fun frameEnPunto(
        s: DoubleRatchetSession,
        g: GuionDeterminista,
        punto: Int = PUNTO_DE_COMPARACION,
    ): ByteArray {
        g.x25519.contador = punto
        return frameDeControl(clonDe(s, g), g)
    }

    /** El frame de control de la sesion del guion, que es la de referencia. */
    private fun frameEsperado(g: GuionDeterminista): ByteArray = frameEnPunto(g.bobSession, g)

    /**
     * La centinela NO puede estar ya en el estado que se va a recuperar.
     *
     * Es la comprobacion que impide que estas pruebas pasen por la razon
     * equivocada, y va ANTES de recuperar a proposito. Si la centinela
     * salariera con el frame esperado, todo lo que se comprueba despues
     * seria cierto aunque la recuperacion no hiciera NADA: un
     * `session.restore()` suprimido pasaria el archivo entero.
     */
    private fun afirmarQueLaCentinelaNoSabia(
        centinela: DoubleRatchetSession,
        g: GuionDeterminista,
        donde: String,
    ) {
        // El frame sale de un CLON DEL CLON, y no de la centinela: `encrypt()`
        // consume la clave siguiente del generador determinista, y si esta
        // comprobacion la consumiera, la centinela y su clon comparado
        // despus partirian de claves DISTINTAS y dejarian de ser comparables
        // —que es un fallo silencioso que mide el generador y no el estado.
        assertFalse(
            frameEsperado(g).contentEquals(frameEnPunto(centinela, g)),
            "$donde: la centinela TIENE que estar en un estado distinto del que se va a recuperar. " +
                "Si ya estuviera en el estado correcto, una recuperacion que no hiciera nada pasaria " +
                "esta prueba, y con ella el resto",
        )
    }

    /** La unidad correcta para este estado previo, producida SIN corte. */
    private fun referencia(dir: File, g: GuionDeterminista): ByteArray {
        val medio = MedioDeChoque(dir)
        journal(g.bobSession, medio, g).persistir(g.envio(1, ordinal = 1uL, n = 1))
        return assertNotNull(medio.confirmadoBruto(), "la referencia tiene que confirmarse")
    }

    /**
     * El ciphertext que la unidad lleva DENTRO del bloque OUTBOUNDRECORD.
     *
     * ## POR QUE NO SE SACA RESTANDO AL FINAL
     *
     * En `v1` el bloque OUTBOUND era el ULTIMO antes del checksum, asi que el
     * ciphertext se sacaba restando su longitud al final del byte-string. En
     * `v2` ese bloque ya no es el ultimo: detras viene el PENDING_INBOUND. Una
     * cuenta desde el final se come el `count(4)` de la bandeja y ademas entra
     * cuatro bytes dentro del ciphertext, de modo que devolvia los bytes
     * DESPLAZADOS —una clase de fallo que hace pasar una prueba por la razon
     * equivocada— y lo hace sin que nada parezca roto.
     *
     * Por eso la posicion sale del HEADER —`snapshotLen` mas el ancho fijo del
     * OUTBOUNDRECORD— y el final descuenta la longitud que el propio header
     * declara para la bandeja, en vez de suponer que el outbound es el ultimo.
     */
    private fun ciphertextDe(unidad: ByteArray): ByteArray {
        fun le32(off: Int): Int {
            var v = 0L
            for (i in 3 downTo 0) v = (v shl 8) or (unidad[off + i].toLong() and 0xFF)
            return v.toInt()
        }
        val inicio = Km52Spec.HEADER_LENGTH +
            le32(Km52Spec.SNAPSHOT_LEN_OFFSET) +
            Km52Spec.OUTBOUND_FIXED_LENGTH
        val fin = unidad.size - Km52Spec.CHECKSUM_LENGTH -
            le32(Km52Spec.PENDING_INBOUND_LEN_OFFSET)
        require(fin > inicio) { "la unidad no lleva ciphertext: $inicio..$fin de ${unidad.size}" }
        return unidad.copyOfRange(inicio, fin)
    }

    /** Exige que [sesion] este en el estado exacto que hay que recuperar. */
    private fun afirmarEstadoRecuperado(sesion: DoubleRatchetSession, g: GuionDeterminista, donde: String) {
        assertEquals(2, sesion.receiveChainCount(), "$donde: las dos cadenas de recepcion")
        assertEquals(3u, sesion.currentPreviousChainLength(), "$donde: PN = 3")
        assertEquals(2, retenidasDe(sesion).size, "$donde: las dos claves retenidas de la epoca vieja")
        assertContentEquals(
            frameEsperado(g), frameEnPunto(sesion, g),
            "$donde: el frame siguiente sale byte a byte IDENTICO al de la sesion que se murio. " +
                "La huella de la sesion no se usa: no cubre el escalar DH privado",
        )
    }

    /** La huella de un `OutboundRecord` recuperado, con TODO su detalle. */
    private fun huellaDe(r: OutboundRecord?): String =
        r?.let { "${it.messageId}/${it.deliveryState}/${it.createdOrdinal}/${hexBytes(it.ciphertext)}/${it.frameIdentity}" }
            ?: "SIN_REGISTRO"

    /**
     * Todo lo que se comprueba del estado VIVO de una sesion, en comparable.
     *
     * El frame de control sale de un CLON y no de la sesion viva, porque
     * `encrypt()` CONSUME la cadena de envio: pedir la firma no puede
     * cambiar el estado que despues se quiere comparar.
     */
    private fun firmaDeSesion(s: DoubleRatchetSession, g: GuionDeterminista): String = buildString {
        append("ns=").append(s.currentSendMessageNumber())
        append(" pn=").append(s.currentPreviousChainLength())
        append(" cadenas=").append(s.receiveChainCount())
        append(" retenidas=").append(retenidasDe(s))
        append(" frame=").append(hexBytes(frameEnPunto(s, g)))
    }

    /**
     * Ejecuta la RECUPERACION sobre un medio y devuelve una firma de TODO lo
     * que queda: el registro devuelto, el libro, la tabla de retencion, el
     * estado criptografico y si la propia recuperacion limpio algo.
     *
     * Es lo que se compara entre dos ejecuciones para demostrar
     * determinismo: si dos runs del mismo estado previo y del mismo punto de
     * corte dieran firmas distintas, aqui se veria.
     */
    private fun firmaDeRecuperacion(dir: File, g: GuionDeterminista): String {
        val medio = MedioDeChoque(dir)
        val r = recuperarSobre(medio, g)
        val devuelto = try {
            r.j.restaurar()
        } catch (e: Throwable) {
            return "EXCEPCION=${e::class.java.simpleName} retiros=${medio.retiros}"
        }
        return buildString {
            append("registro=").append(huellaDe(devuelto))
            append(" libro=").append(r.j.ledger.registros().joinToString(",") { it.messageId.toString() })
            append(" ret=").append(
                r.sesion.snapshot().receiveChains
                    .map { hexBytes(it.chainId) to r.j.retention.ordinalDe(it.chainId) }
                    .sortedBy { it.first }
                    .joinToString(",") { "${it.first}:${it.second}" },
            )
            append(" ").append(firmaDeSesion(r.sesion, g))
            append(" retiros=").append(medio.retiros)
        }
    }

    /**
     * El corte fue REAL, y el estado logico no se habia adelantado.
     *
     * Las tres cosas que un corte de proceso no ejecutaria y una excepcion
     * si, comprobadas una por una.
     */
    private fun afirmarQueElCorteFueReal(ch: InformeDelChoque, dir: File) {
        assertTrue(
            ch.muertoPorSenal,
            "el proceso tiene que morir por SEÑAL (SIGKILL), no terminar por su cuenta.\n${ch.informe}",
        )
        assertFalse(
            File(dir, MedioDeChoque.SIN_MORIR).isFile,
            "`persistir` ha vuelto: el guion no ha muerto en el punto ${ch.punto}.\n${ch.informe}",
        )
        assertEquals(
            0, ch.retiros,
            "el proceso murio SIN ejecutar ninguna limpieza: un `finally` o un gancho de cierre " +
                "habrian retirado lo pendiente. Si esto falla, el arnes ha medido un desenrollado de " +
                "pila y no una interrupcion.\n${ch.informe}",
        )
        // --- Y EL ESTADO LOGICO, EN EL PUNTO EXACTO DEL CORTE ----------------
        //
        // Esta es la unica evidencia de un retraso del `COMMIT` que un corte
        // no destruye por accidente: si el libro de transmision se adelantara
        // al commit, al morir el proceso se llevaria la prueba consigo y NADIE
        // la veria jamas. El testigo lo escribe el punto de corte, un instante
        // antes de que la senal llegue, y por eso mide el estado EN EL CORTE.
        assertEquals(
            0, ch.libroEnElCorte,
            "en el instante del corte el libro de transmision tiene que estar VACIO: el estado logico " +
                "solo avanza tras un commit verificado, y hasta el corte no lo hay. Si esto falla, " +
                "alguien confirma estado antes de que el medio tenga la unidad.\n${ch.informe}",
        )
    }

    // ===================================================================
    // CRASH-01 — PUNTO 1: ANTES DE ESCRIBIR
    // ===================================================================

    @Test
    @DisplayName("CRASH-01 antes de escribir: el estado ni se confirma ni se pierde")
    fun `CRASH-01 antes de escribir`() {
        val dir = tmp.resolve("antes-de-escribir")
        val ch = ejecutarHastaElChoque(dir, PuntoDeCorte.ANTES_DE_ESCRIBIR)
        afirmarQueElCorteFueReal(ch, dir)

        // --- 1. EL CORTE OCURRIO ANTES DE TOCAR EL MEDIO --------------------
        assertEquals(0, ch.escrituras, "no se dio de escribir ninguna unidad")
        assertEquals(0, ch.lecturas, "y no se leyo ninguna")
        assertEquals(0, ch.commits, "y no se confirmo ninguna")
        val medio = MedioDeChoque(dir)
        assertNull(medio.pendienteBruto(), "el medio no tiene nada pendiente")
        assertNull(medio.confirmadoBruto(), "ni nada confirmado")
        assertFalse(medio.hasPending(), "ni siquiera un resto")

        // --- 2. LA RECUPERACION NO INVENTA NADA ----------------------------
        val g = GuionDeterminista()
        g.dosEpochs()
        val r = recuperarSobre(medio, g)
        assertNull(r.j.restaurar(), "no hay nada confirmado: no hay que recuperar")
        assertEquals(0, r.j.ledger.tamanho(), "y el libro de transmision sigue vacio")
        assertFalse(r.j.ledger.esDurable(mensajeId(1)), "el envio no aparece")
        assertEquals(0, medio.retiros, "y la propia recuperacion no limpia: no depende de cerrar nada")

        // --- 3. EL ESTADO LOGICO NO SE HA MOVIDO, CON CRIPTOGRAFIA ---------
        //
        // La centinela, intacta: `encrypt()` byte a byte contra su propio
        // clon. NO con `stateFingerprint()`, que no cubre el escalar DH
        // privado y daria por buena una sesion con el escalar roto.
        assertContentEquals(
            frameEnPunto(r.antes, g), frameEnPunto(r.sesion, g),
            "una recuperacion que no encuentra nada no puede haber tocado la sesion, ni un byte",
        )

        // --- 4. NI SE PIERDE: el envio sigue disponible y es el MISMO -------
        //
        // "No se pierde" no es "no ha pasado nada": es que el envio sigue
        // siendo persistible desde el MISMO estado previo, con el MISMO
        // ciphertext. Se comprueba reintentando con el proceso vivo y
        // comparando la unidad con la referencia del mismo guion.
        val g2 = GuionDeterminista()
        g2.dosEpochs()
        val medio2 = MedioDeChoque(tmp.resolve("antes-reintento"))
        val j2 = journal(g2.bobSession, medio2, g2)
        val registro = j2.persistir(g2.envio(1, ordinal = 1uL, n = 1))
        assertTrue(j2.ledger.esDurable(mensajeId(1)), "reintentado con el proceso vivo, el envio es durable")
        assertEquals(OutboundDeliveryState.PENDIENTE, j2.ledger.estadoDe(mensajeId(1)), "y sigue PENDIENTE")

        val g3 = GuionDeterminista()
        g3.dosEpochs()
        assertContentEquals(
            referencia(tmp.resolve("antes-referencia"), g3), medio2.confirmadoBruto(),
            "el envio no se ha perdido ni se ha regenerado: es el mismo byte-string",
        )
        assertContentEquals(
            registro.ciphertext,
            g2.frames[1].copyOfRange(SecureFrameSpec.CIPHERTEXT_OFFSET, g2.frames[1].size),
            "y con el ciphertext ORIGINAL del frame, no uno re-cifrado",
        )
    }

    // ===================================================================
    // CRASH-02 — PUNTO 2: DURANTE LA ESCRITURA
    // ===================================================================

    @Test
    @DisplayName("CRASH-02 a mitad de escribir: una unidad parcial no puede llegar a ser estado")
    fun `CRASH-02 durante la escritura`() {
        val dir = tmp.resolve("durante-la-escritura")
        val ch = ejecutarHastaElChoque(dir, PuntoDeCorte.DURANTE_LA_ESCRITURA)
        afirmarQueElCorteFueReal(ch, dir)

        // --- 1. EL MEDIO TIENE UN PREFIJO, Y ES UN PREFIJO ----------------
        assertEquals(1, ch.escrituras, "la escritura empezo")
        assertEquals(0, ch.commits, "y no hay confirmacion")
        val medio = MedioDeChoque(dir)
        val prefijo = assertNotNull(medio.pendienteBruto(), "el medio dejo bytes a medias")
        assertNull(medio.confirmadoBruto(), "y nada confirmado")

        val gRef = GuionDeterminista()
        gRef.dosEpochs()
        val buena = referencia(tmp.resolve("durante-referencia"), gRef)
        assertTrue(
            prefijo.size < buena.size,
            "lo que hay es un PREFIJO (${prefijo.size} de ${buena.size}), no una unidad: sin esto el caso " +
                "no probaria nada",
        )
        assertContentEquals(
            buena.copyOf(prefijo.size), prefijo,
            "y es el principio de la unidad buena, byte a byte: el corte fue de verdad a mitad",
        )

        // --- 2. UNA UNIDAD PARCIAL NO ES UNA UNIDAD -------------------------
        assertTrue(
            runCatching { gRef.codec().deserialize(prefijo) }.isFailure,
            "un prefijo no se puede leer como unidad: por eso confirmar sin verificar seria confirmar basura",
        )

        // --- 3. Y TAMPOCO PUEDE CONVERTIRSE EN ESTADO AL RECUPERAR --------
        //
        // Esta es la parte de ESTE checkpoint, y no la de 3Q.5.2b: aqui el
        // prefijo esta CONFIRMADO en el medio, que es el peor caso posible.
        // Si la recuperacion lo adoptara, tendriamos una sesion con claves
        // que no son de nadie. Se exige que lo RECHACE y que no toque nada.
        medio.dejarConfirmado(prefijo)
        val g = GuionDeterminista()
        g.dosEpochs()
        val r = recuperarSobre(medio, g)
        assertThrows<Km52Truncated> { r.j.restaurar() }
        assertEquals(0, r.j.ledger.tamanho(), "una unidad parcial no registra nada")
        assertContentEquals(
            frameEnPunto(r.antes, g), frameEnPunto(r.sesion, g),
            "y la sesion sigue intacta: byte a byte, no con la huella",
        )

        // --- 4. EL CONTROL: LA MISMA UNIDAD, COMPLETA ----------------------
        //
        // Sin esto, "nunca se restaura" podria ser cierto porque el codigo no
        // restaura NUNCA, y la prueba pasaria por la razon equivocada.
        medio.dejarConfirmado(buena)
        val g4 = GuionDeterminista()
        g4.dosEpochs()
        val r4 = recuperarSobre(medio, g4)
        assertNotNull(r4.j.restaurar(), "con la unidad completa, la recuperacion SI funciona")
        afirmarEstadoRecuperado(r4.sesion, g4, "control")
    }

    // ===================================================================
    // CRASH-03 — PUNTO 3: DESPUES DE ESCRIBIR, ANTES DE VERIFICAR
    // ===================================================================

    @Test
    @DisplayName("CRASH-03 escrito y NO verificado: la unidad no llega a estado")
    fun `CRASH-03 despues de escribir antes de verificar`() {
        val dir = tmp.resolve("escrito-no-verificado")
        val ch = ejecutarHastaElChoque(dir, PuntoDeCorte.DESPUES_DE_ESCRIBIR)
        afirmarQueElCorteFueReal(ch, dir)

        // --- 1. LA SEPARACION ENTRE ESCRIBIR Y VERIFICAR, DELIBERADA -------
        //
        // El corte esta DESPUES de que el medio acepta la unidad y ANTES de
        // que nadie la mire. Se comprueba en los dos contadores, y en que el
        // segundo es CERO: si la leyese, la unidad habria sido verificada y
        // este punto seria otro.
        assertEquals(1, ch.escrituras, "la unidad esta escrita")
        assertEquals(0, ch.lecturas, "y NADIE la ha releido: no esta verificada")
        assertEquals(0, ch.commits, "y no hay confirmacion")
        val medio = MedioDeChoque(dir)
        assertNull(medio.confirmadoBruto(), "el medio no tiene nada confirmado")

        // --- 2. Y LA UNIDAD DEL MEDIO ES PERFECTA ---------------------------
        //
        // Esto va DESPUES a proposito, y es la garantia de que la prueba no
        // se ha pasado por el camino corto. La unidad que hay en el medio
        // esta BIEN: mismo checksum, misma forma, mismo envio. Un caso que
        // se apoyara en que la escritura esta rota probaria una defectividad
        // de la escritura, no la de la recuperacion.
        val gRef = GuionDeterminista()
        gRef.dosEpochs()
        val pendiente = assertNotNull(medio.pendienteBruto(), "el medio tiene la unidad")
        val leida = gRef.codec().deserialize(pendiente)
        val outPendiente = assertNotNull(leida.outbound, "la unidad pendiente describe el envio")
        assertEquals(mensajeId(1), outPendiente.messageId, "la unidad pendiente describe el envio")
        assertContentEquals(
            outPendiente.ciphertext,
            gRef.frames[1].copyOfRange(SecureFrameSpec.CIPHERTEXT_OFFSET, gRef.frames[1].size),
            "con el ciphertext original, byte a byte",
        )
        assertEquals(2, leida.snapshot.receiveChains.size, "y con el estado criptografico entero")
        val buena = referencia(tmp.resolve("escrito-no-verificado-referencia"), gRef)
        assertContentEquals(buena, pendiente, "de hecho es byte a byte la unidad que se iba a confirmar")

        // --- 3. LA RECUPERACION NO LA ADOPTA -------------------------------
        //
        // ESTE es el nucleo del checkpoint. Una unidad correcta, con su
        // checksum correcto, que describa el envio correcto y el estado
        // correcto, y que aun asi NO es estado: porque nadie la ha
        // verificado en este proceso, y "escribir" no es "comprobar".
        //
        // Si alguien hiciera que la recuperacion leyera lo pendiente, este
        // bloque entero pasaria: la sesion tendria material criptografico
        // que nadie ha confirmado, y el envio figuraria durable sin que
        // ningun commit lo respaldara.
        val g = GuionDeterminista()
        g.dosEpochs()
        val r = recuperarSobre(medio, g)
        assertNull(r.j.restaurar(), "lo no verificado no se recupera: FABRICAR estado seria inventar claves")
        assertEquals(0, r.j.ledger.tamanho(), "y el libro sigue vacio")
        assertFalse(r.j.ledger.esDurable(mensajeId(1)), "el envio no aparece")
        assertTrue(medio.hasPending(), "el pendiente sigue en el medio: nadie lo ha retirado")
        assertEquals(0, medio.retiros, "y la recuperacion no lo retira: no depende de cerrar nada")
        assertContentEquals(
            frameEnPunto(r.antes, g), frameEnPunto(r.sesion, g),
            "la sesion intacta, con criptografia y no con la huella",
        )

        // --- 4. EL MISMO BYTE-STRING, CONFIRMADO, SI SE RECUPERA ------------
        //
        // El control que separa "no se adopta lo no confirmado" de "esta
        // sesion no se restaura nunca". Son los MISMOS bytes: lo unico que
        // cambia es que estan en el hueco de confirmado, que es donde el
        // medio garantiza que hubo un commit.
        medio.dejarConfirmado(pendiente)
        val g2 = GuionDeterminista()
        g2.dosEpochs()
        val r2 = recuperarSobre(medio, g2)
        val registro = assertNotNull(r2.j.restaurar(), "los mismos bytes, confirmados, SI se recuperan")
        assertEquals(mensajeId(1), registro.messageId, "con el envio")
        assertTrue(r2.j.ledger.esDurable(mensajeId(1)), "y el envio vuelve a ser durable")
        afirmarEstadoRecuperado(r2.sesion, g2, "control")
    }

    // ===================================================================
    // CRASH-04 — LA LECTURA MUERE A MEDIAS DE LA VERIFICACION
    // ===================================================================

    @Test
    @DisplayName("CRASH-04 muere al releer: la verificacion empezada no completa nada")
    fun `CRASH-04 durante la verificacion`() {
        val dir = tmp.resolve("durante-la-verificacion")
        val ch = ejecutarHastaElChoque(dir, PuntoDeCorte.DURANTE_LA_VERIFICACION)
        afirmarQueElCorteFueReal(ch, dir)

        // La UNICA diferencia con el punto 3 es que la lectura empezo. Sin
        // este contraste, "escrito y no verificado" y "verificacion a medias"
        // serian el mismo scenario con dos nombres.
        assertEquals(1, ch.escrituras, "la unidad esta escrita")
        assertEquals(1, ch.lecturas, "y la lectura EMPEZO")
        assertEquals(0, ch.commits, "pero no hay confirmacion")

        val medio = MedioDeChoque(dir)
        val pendiente = assertNotNull(medio.pendienteBruto(), "el medio tiene la unidad")
        assertNull(medio.confirmadoBruto(), "y nada confirmado")
        val gRef = GuionDeterminista()
        gRef.dosEpochs()
        assertContentEquals(
            referencia(tmp.resolve("verificacion-referencia"), gRef), pendiente,
            "la unidad del medio es la buena: lo que fallo fue la COMPROBACION, no la escritura",
        )

        val g = GuionDeterminista()
        g.dosEpochs()
        val r = recuperarSobre(medio, g)
        assertNull(r.j.restaurar(), "una verificacion que no termino no ha confirmado nada")
        assertEquals(0, r.j.ledger.tamanho(), "el libro sigue vacio")
        assertContentEquals(
            frameEnPunto(r.antes, g), frameEnPunto(r.sesion, g),
            "y la sesion intacta, con criptografia",
        )
        assertEquals(0, medio.retiros, "la recuperacion no retira lo pendiente: no depende del cierre")
    }

    // ===================================================================
    // CRASH-05 — PUNTO 4: DESPUES DE VERIFICAR
    // ===================================================================

    @Test
    @DisplayName("CRASH-05 verificado y sin confirmar: la unidad completa da el estado exacto")
    fun `CRASH-05 despues de verificar`() {
        val dir = tmp.resolve("verificado-sin-confirmar")
        val ch = ejecutarHastaElChoque(dir, PuntoDeCorte.DESPUES_DE_VERIFICAR)
        afirmarQueElCorteFueReal(ch, dir)

        // --- 1. LA VERIFICACION SI LLEGO A HACERSE -------------------------
        assertEquals(1, ch.escrituras, "escrita")
        assertEquals(1, ch.lecturas, "releida")
        assertEquals(0, ch.commits, "y NO confirmada: el proceso murio antes de promoverla")
        val medio = MedioDeChoque(dir)
        val pendiente = assertNotNull(medio.pendienteBruto(), "la unidad esta en el medio")
        assertNull(medio.confirmadoBruto(), "pero no en el hueco de confirmado")

        // La lectura devuelve EXACTAMENTE lo escrito: es lo que permite decir
        // que la verificacion se completo. Sin esta comprobacion, "1
        // lectura" podria ser una lectura a medias.
        val gRef = GuionDeterminista()
        gRef.dosEpochs()
        val buena = referencia(tmp.resolve("verificado-referencia"), gRef)
        assertContentEquals(buena, pendiente, "el medio tiene la unidad completa y correcta, byte a byte")

        // --- 2. PERO VERIFICADA NO ES CONFIRMADA ---------------------------
        //
        // El punto mas incomodo de los seis: el medio tiene exactamente lo
        // correcto y el proceso lo habia comprobado, y aun asi no es estado.
        // La razon no es que falte el dato —falta la PROMOCION, que es del
        // medio— sino que la recuperacion solo lee lo que el medio
        // garantiza por contrato: lo confirmado.
        val g = GuionDeterminista()
        g.dosEpochs()
        val r = recuperarSobre(medio, g)
        assertNull(r.j.restaurar(), "una unidad verificada y no confirmada no es estado todavia")
        assertEquals(0, r.j.ledger.tamanho(), "el libro sigue vacio")
        assertContentEquals(
            frameEnPunto(r.antes, g), frameEnPunto(r.sesion, g),
            "y la sesion intacta, con criptografia",
        )

        // --- 3. LA MITAD POSITIVA: LA UNIDAD COMPLETA PERMITE RECUPERAR ----
        //
        // Lo que el punto 4 tiene que demostrar es que la unidad que hay es
        // la que hace falta, ni una mas ni una menos. Se demuestra Promoting
        // ESE MISMO byte-string —el que estaba verificado, sin reescribirlo
        // ni re-cifrarlo— y exigiendo el estado exacto sobre una CENTINELA,
        // de modo que el estado RECUPERADO aparezca y no este ahi de antes.
        //
        // No se prueba con una unidad nueva: si el guion construyera una
        // segunda unidad, la prueba no diria nada de la que murio.
        medio.dejarConfirmado(buena)
        val g2 = GuionDeterminista()
        g2.dosEpochs()
        val r2 = recuperarSobre(medio, g2)
        val registro = assertNotNull(r2.j.restaurar(), "esa unidad completa da el estado")
        assertEquals(mensajeId(1), registro.messageId, "con el envio que la unidad lleva")
        assertContentEquals(
            registro.ciphertext, ciphertextDe(buena),
            "y con el ciphertext ORIGINAL que salio por el cable, byte a byte",
        )

        // El estado EXACTO, con criptografia: el frame siguiente sale byte a
        // byte igual que en la sesion que se murio. Va antes del descifrado de
        // abajo porque `encrypt()` consume la cadena de ENVIO y el descifrado
        // de una clave retenida consume la de RECEPCION: son independientes,
        // pero el recuento de retenidas solo es dos si nadie ha descifrado
        // todavia.
        afirmarEstadoRecuperado(r2.sesion, g2, "punto 4")

        // Y la parte del estado que NO viaja en el frame: la clave retenida
        // que `a1` necesita sigue ahi y descifra desde ella. Una sesion
        // restaurada con la foto podada daria REPLAY_OR_UNKNOWN aqui, y este
        // es el unico sitio donde se nota.
        val d = SecureRatchetProtocol(r2.sesion, g2.protector).decrypt(g2.frames[1])
        assertTrue(d is SecureRatchetProtocol.DecryptResult.Ok, "el frame retenido tiene que entrar: $d")
        assertTrue(
            (d as SecureRatchetProtocol.DecryptResult.Ok).fromSkipped,
            "y desde la clave RETENIDA: recuperar el estado significa poder seguir descifrando",
        )
        assertContentEquals("a1".toByteArray(), d.plaintext, "con el contenido correcto")
    }

    // ===================================================================
    // CRASH-06 — PUNTO 5: DESPUES DEL COMMIT
    // ===================================================================

    @Test
    @DisplayName("CRASH-06 despues del commit: la recuperacion devuelve el estado exacto")
    fun `CRASH-06 despues del commit`() {
        val dir = tmp.resolve("despues-del-commit")
        val ch = ejecutarHastaElChoque(dir, PuntoDeCorte.DESPUES_DEL_COMMIT)
        afirmarQueElCorteFueReal(ch, dir)

        // --- 1. LA UNIDAD ESTA CONFIRMADA -----------------------------------
        assertEquals(1, ch.escrituras, "escrita")
        assertEquals(1, ch.lecturas, "verificada")
        assertEquals(1, ch.commits, "y confirmada")
        val medio = MedioDeChoque(dir)
        val confirmado = assertNotNull(medio.confirmadoBruto(), "el commit llego al medio")
        assertNull(medio.pendienteBruto(), "y no queda nada pendiente")
        assertFalse(medio.hasPending(), "el medio queda limpio")

        // --- 2. LA RECUPERACION ES EXACTA ----------------------------------
        val g = GuionDeterminista()
        g.dosEpochs()
        val r = recuperarSobre(medio, g)
        val registro = assertNotNull(r.j.restaurar(), "hay unidad confirmada: se restaura")
        assertEquals(mensajeId(1), registro.messageId, "el envio es el que se confirmo")
        assertEquals(OutboundDeliveryState.PENDIENTE, registro.deliveryState, "con su estado de entrega")
        assertEquals(1uL, registro.createdOrdinal, "y su ordinal de creacion")
        assertTrue(r.j.ledger.esDurable(mensajeId(1)), "el envio vuelve a ser durable")
        assertEquals(1, r.j.ledger.tamanho(), "y hay UN registro, no dos")

        // El estado EXACTO, con criptografia y no con la huella, y SOBRE UNA
        // CENTINELA: este es el unico sitio donde se ve que el estado
        // RECUPERADO aparece, y no que simplemente sigue ahi.
        afirmarEstadoRecuperado(r.sesion, g, "punto 5")

        // Y lo que el estado VIVO no puede ensenar: la clave retenida.
        val d = SecureRatchetProtocol(r.sesion, g.protector).decrypt(g.frames[1])
        assertTrue(d is SecureRatchetProtocol.DecryptResult.Ok, "el frame retenido tiene que entrar: $d")
        assertTrue((d as SecureRatchetProtocol.DecryptResult.Ok).fromSkipped, "desde la clave retenida")

        // --- 3. Y LA TABLA DE RETENCION SOBREVIVIO AL CORTE -----------------
        //
        // Sin esto, la proxima escritura podaria victimas distintas de las
        // que la unidad anterior daba por mas antiguas, y la victimizacion
        // determinista dejaria de serlo entre dos ejecuciones del mismo
        // estado (INV-07).
        val tabla = g.codec().deserialize(confirmado).retention
        assertTrue(tabla.size > 0, "el guion tiene que tener cadenas que ordenar")
        for (e in tabla.entries) {
            assertEquals(
                e.lastUseOrdinal, r.j.retention.ordinalDe(e.chainId),
                "el ordinal de la cadena ${hexBytes(e.chainId)} ha sobrevivido al corte",
            )
        }
    }

    // ===================================================================
    // CRASH-07 — IDEMPOTENCIA: RECUPERAR DOS VECES, Y NO VOLVER ATRAS
    // ===================================================================

    @Test
    @DisplayName("CRASH-07 recuperar dos veces es lo mismo, y una unidad vieja no hace retroceder")
    fun `CRASH-07 idempotencia y no retroceso`() {
        // --- 1. DOS UNIDADES CONFIRMADAS, Y CON JUEGO DE CADENAS DISTINTO ---
        //
        // El medio se lleva dos commits reales del mismo guion. Entre ellos el
        // ratchet de BOB ABRE UNA CADENA MAS de verdad —con dos entregas
        // cruzadas, que es como un ratchet reactivo abre una epoca—, asi que
        // las dos unidades no se distinguen solo por el registro de salida
        // sino por el JUEGO COMPLETO DE CADENAS. Si las dos tuvieran el
        // mismo, una prueba de retroceso o de mezcla pasaria por la razon
        // equivocada: no habria nada que confundir.
        //
        // Todo el test usa UN SOLO guion: las claves del generador
        // determinista salen de un contador, y dos guiones que han recorrido
        // guiones distintos tendrian contadores distintos y producirian frames
        // distintos por el contador y no por el estado.
        val dir = tmp.resolve("idempotencia")
        val g = GuionDeterminista()
        g.dosEpochs()
        val medio = MedioDeChoque(dir)
        val j = journal(g.bobSession, medio, g)

        j.persistir(g.envio(1, ordinal = 1uL, n = 1))
        val bytesVieja = assertNotNull(medio.confirmadoBruto(), "la unidad vieja se confirmo")
        // La sesion tal y como quedo tras la unidad VIEJA. Se conserva como
        // referencia y NO se usa para nada mas: si el guion siguiera con
        // ella, la referencia seria la misma que la del estado final.
        val referenciaVieja = clonDe(g.bobSession, g)
        val estadoViejo = firmaDeSesion(g.bobSession, g)
        val nsViejo = g.bobSession.currentSendMessageNumber()
        assertEquals(2, g.bobSession.receiveChainCount(), "la unidad vieja tiene dos cadenas")

        g.bobEnviaYAliceRecibe("b3")   // ALICE ve una DH nueva de BOB y rota
        g.aliceEnviaYBobRecibe("a5")   // BOB ve una DH nueva de ALICE y rota: 3a cadena
        val cadenasNuevas = g.bobSession.receiveChainCount()
        assertEquals(3, cadenasNuevas, "el guion tiene que abrir una cadena mas o no mide nada")

        j.persistir(g.envio(4, ordinal = 2uL, n = 2))
        val bytesNueva = assertNotNull(medio.confirmadoBruto(), "la unidad nueva se confirmo")
        val estadoNuevo = firmaDeSesion(g.bobSession, g)
        val nsNuevo = g.bobSession.currentSendMessageNumber()

        // Los estados EXACTOS que describen las dos unidades: un clon de la
        // sesion viva en el momento de cada commit. Son las referencias
        // contra las que se mide cada recuperacion de este test.
        val esperadoViejo = frameEnPunto(referenciaVieja, g)
        val esperadoNuevo = frameEnPunto(g.bobSession, g)

        assertFalse(bytesVieja.contentEquals(bytesNueva), "las dos unidades tienen que ser distintas")
        assertFalse(
            estadoViejo == estadoNuevo,
            "y con ESTADO CRIPTOGRAFICO distinto (Ns $nsViejo contra $nsNuevo, y dos cadenas contra tres)",
        )

        // --- 2. RECUPERAR DOS VECES LA MISMA UNIDAD CONFIRMADA --------------
        //
        // Dos centinelas distintas leyendo el MISMO medio. Si la
        // recuperacion no fuera idempotente, darian estados distintos.
        val r1 = recuperarSobre(MedioDeChoque(dir), g)
        val r2 = recuperarSobre(MedioDeChoque(dir), g)

        val rec1 = assertNotNull(r1.j.restaurar(), "primera recuperacion")
        val rec2 = assertNotNull(r2.j.restaurar(), "segunda recuperacion")
        assertEquals(huellaDe(rec1), huellaDe(rec2), "el registro devuelto es el mismo")
        assertEquals(
            firmaDeSesion(r1.sesion, g), firmaDeSesion(r2.sesion, g),
            "y el estado criptografico recuperado es IDENTICO: recuperar dos veces es lo mismo que una",
        )
        assertEquals(1, r1.j.ledger.tamanho(), "un solo registro: recuperar no duplica")
        assertEquals(1, r2.j.ledger.tamanho(), "y en la segunda recuperacion tambien uno")
        assertEquals(1, r1.j.ledger.registros().size, "ni el libro se desdobla")
        assertEquals(cadenasNuevas, r1.sesion.receiveChainCount(), "ni la sesion acumula cadenas")
        assertContentEquals(
            esperadoNuevo, frameEnPunto(r1.sesion, g),
            "y el estado recuperado es el de la sesion que se murio, byte a byte",
        )

        // Y sobre la MISMA sesion, dos veces seguidas: el estado vivo no puede
        // acumular, ni de cadenas ni de retenidas.
        val r3 = recuperarSobre(MedioDeChoque(dir), g)
        r3.j.restaurar()
        // Un clon DESPUES de la primera, para comparar el estado de la sesion
        // con el de la segunda. Comparar con `antes` seria comparar la
        // centinela con lo recuperado, que tienen que ser distintos.
        val entreMedias = clonDe(r3.sesion, g)
        val trasUna = retenidasDe(r3.sesion)
        r3.j.restaurar()
        assertEquals(trasUna, retenidasDe(r3.sesion), "recuperar dos veces no duplica claves retenidas")
        assertEquals(cadenasNuevas, r3.sesion.receiveChainCount(), "ni cadenas de recepcion")
        assertEquals(1, r3.j.ledger.tamanho(), "ni registros en el libro")
        assertContentEquals(
            frameEnPunto(entreMedias, g), frameEnPunto(r3.sesion, g),
            "y la sesion sigue en el estado recuperado, byte a byte, no en un estado acumulado",
        )
        assertContentEquals(
            esperadoNuevo, frameEnPunto(r3.sesion, g),
            "y ese estado es el de la sesion que se murio, no el de la centinela ni una mezcla",
        )

        // --- 3. RECUPERAR UNA UNIDAD MAS NUEVA SOBRE UNA VIEJA ---------------
        //
        // UNA MISMA sesion, un MISMO journal: primero se restaura A, luego
        // se restaura B encima. No basta con que B sea correcta: tiene que
        // DESPLAZAR a A. Si la restauracion se sumara en vez de
        // sobreescribir, la sesion se quedaria con las dos historias —tres
        // cadenas, con las de la epoca vieja otra vez vivas—, que es
        // exactamente lo que INV-02 prohibe.
        val r5 = recuperarSobre(MedioDeChoque(dir), g)
        medio.dejarConfirmado(bytesVieja)
        val a = assertNotNull(r5.j.restaurar(), "primero A")
        assertEquals(mensajeId(1), a.messageId, "primero A")
        assertEquals(2, r5.sesion.receiveChainCount(), "A tiene DOS cadenas")
        val controlEnA = frameEnPunto(r5.sesion, g)
        assertContentEquals(
            esperadoViejo, controlEnA,
            "A se recupera como el estado de A, byte a byte",
        )
        assertFalse(
            esperadoNuevo.contentEquals(controlEnA),
            "y A NO es el estado final: si lo fuera, la prueba de retroceso no distinguiria una unidad de otra",
        )

        medio.dejarConfirmado(bytesNueva)
        val b = assertNotNull(r5.j.restaurar(), "despues B, se recupera")
        assertEquals(mensajeId(2), b.messageId, "y lo que se recupera es B, no A")
        assertEquals(
            cadenasNuevas, r5.sesion.receiveChainCount(),
            "la sesion queda con las $cadenasNuevas cadenas de B y NINGUNA de A: restaurar SOBREESCRIBE",
        )
        assertContentEquals(
            esperadoNuevo, frameEnPunto(r5.sesion, g),
            "y el estado es el de B, byte a byte, no una mezcla de las dos historias",
        )
        // El LIBRO, en cambio, si acumula, y es lo correcto: A y B se
        // confirmaron de verdad en el medio, y un log de envios durables que
        // se olvidara de uno seria otro tipo de perdida. Lo que no puede
        // pasar es que el ULTIMO deje de ser el vigente, y el orden es el de
        // `createdOrdinal` (INV-07).
        assertEquals(2, r5.j.ledger.tamanho(), "el libro conserva los dos envios que se confirmaron")
        assertEquals(
            listOf(mensajeId(1), mensajeId(2)), r5.j.ledger.registros().map { it.messageId },
            "y en orden de createdOrdinal, que es determinista (INV-07)",
        )
        assertEquals(
            mensajeId(2), r5.j.ledger.registros().last().messageId,
            "el ULTIMO es el de B: es el que describe el estado actual de la sesion",
        )

        // --- 3b. Y EN EL SENTIDO INVERSO: A ENCIMA DE B ----------------------
        //
        // Si el medio devolviera una unidad ANTIGUA, la sesion tiene que
        // quedar en el estado de ESA unidad. Sin `clear()` en la restauracion
        // —sin sobrescribir, sin fusionar— la sesion conservaria la tercera
        // cadena de B junto a las dos de A: tres cadenas que no conviven en
        // ninguna sesion alcanzable, y una sesion capaz de descifrar con
        // material de dos historias. Es la misma mezcla del punto 3, vista
        // desde el otro lado, y es la unica forma de que un acumulador
        // sobreviva a dos restauraciones.
        medio.dejarConfirmado(bytesVieja)
        val sobreB = assertNotNull(r5.j.restaurar(), "y con la unidad vieja, tambien")
        assertEquals(mensajeId(1), sobreB.messageId, "se recupera A")
        assertEquals(
            2, r5.sesion.receiveChainCount(),
            "la sesion VUELVE a las dos cadenas de A: la tercera cadena de B no puede sobrevivir",
        )
        assertContentEquals(
            esperadoViejo, frameEnPunto(r5.sesion, g),
            "y el estado es el de A, byte a byte: restaurar SOBREESCRIBE en las dos direcciones",
        )

        // --- 4. Y RECUPERAR UNA UNIDAD ANTIGUA NO HACE RETROCEDER -----------
        //
        // El escenario: el medio confirmo A, luego B, y el proceso murio con
        // B confirmado. La recuperacion tiene que devolver B. Devolver A
        // seria un retroceso: la sesion perderia el envio mas reciente y las
        // claves que el ratchet avanzo desde entonces.
        //
        // El medio se devuelve a B, que es como lo dejo el proceso que
        // murio: el paso 3b lo dejo en A a proposito.
        medio.dejarConfirmado(bytesNueva)
        val r4 = recuperarSobre(MedioDeChoque(dir), g)
        val recuperado = assertNotNull(r4.j.restaurar())

        assertEquals(mensajeId(2), recuperado.messageId, "la recuperacion devuelve la UNIDAD MAS NUEVA, no la vieja")
        assertEquals(2uL, recuperado.createdOrdinal, "con su ordinal mas alto")
        assertFalse(r4.j.ledger.esDurable(mensajeId(1)), "el envio de la unidad VIEJA no reaparece: eso seria retroceder")
        assertEquals(listOf(mensajeId(2)), r4.j.ledger.registros().map { it.messageId }, "y el libro solo tiene la nueva")
        assertEquals(nsNuevo, r4.sesion.currentSendMessageNumber(), "el indice de envio es el NUEVO")
        assertEquals(cadenasNuevas, r4.sesion.receiveChainCount(), "y el juego de cadenas es el nuevo")
        assertContentEquals(
            esperadoNuevo, frameEnPunto(r4.sesion, g),
            "y el frame es el de B, byte a byte",
        )
    }

    // ===================================================================
    // CRASH-07b — EL SEGUNDO GUARDIAN: RESTAURAR ENCIMA REEMPLAZA
    // ===================================================================

    /**
     * Una sesion montada desde UNA UNIDAD, y nada mas: la referencia "fresca
     * y limpia".
     *
     * Se construye con [DoubleRatchetSession.restore] sobre la foto LEIDA del
     * medio, no sobre el objeto que la escribio, para que la comparacion no
     * pueda pasar por una copia del mismo objeto en memoria.
     */
    private fun sesionLimpiaDe(unidad: ByteArray, g: GuionDeterminista): DoubleRatchetSession =
        DoubleRatchetSession.restore(
            g.codec().deserialize(unidad).snapshot,
            g.x25519.copiaEnElMismoPunto(),
            g.kdf,
        )

    /**
     * Descifra un frame con un CLON de [s], sin tocar [s].
     *
     * ## POR QUE UN CLON Y NO LA SESION VIVA
     *
     * Porque `decrypt()` CONSUME la clave que sirve: la clave saltada que
     * acaba de entregar desaparece de la cadena. Preguntar sobre la sesion
     * viva se comeria la evidencia que esta prueba viene a medir, y la
     * segunda pregunta —"esta misma instancia, ya restaurada B, ¿descifra
     * lo que descifraba con A?"— mediria un estado que el primer descifrado
     * gasto. Con un clon las dos preguntas son sobre el MISMO estado, leido
     * dos veces.
     *
     * El punto del generador tambien se fija, por el mismo motivo que en
     * [frameEnPunto]: lo que se compara tiene que ser el estado, no cuanto
     * ha generado cada clon.
     */
    private fun descifraConClon(
        s: DoubleRatchetSession,
        g: GuionDeterminista,
        frame: ByteArray,
    ): SecureRatchetProtocol.DecryptResult {
        g.x25519.contador = PUNTO_DE_COMPARACION
        return SecureRatchetProtocol(clonDe(s, g), g.protector).decrypt(frame)
    }

    /** Las claves publicas DH que tienen cadena de recepcion en [s]. */
    private fun cadenasDe(s: DoubleRatchetSession): Set<String> =
        s.snapshot().receiveChains.map { hexBytes(it.chainId) }.toSet()

    /**
     * MONTA LA HISTORIA B: otra conversacion, con RAIZ PROPIA.
     *
     * ## POR QUE UNA SEGUNDA HISTORIA Y NO OTRO PUNTO DE LA MISMA
     *
     * Porque dentro de una sola historia el juego de cadenas SOLO CRECE:
     * `receiveChains` no tiene politica de retirada —esta escrito en la
     * propia clase— de modo que las claves de un estado viejo son siempre
     * un subconjunto de las del estado nuevo. Restaurar el VIEJO y luego el
     * NUEVO deja, sin una sola falta, el mismo mapa que restaura solo el
     * nuevo: cada clave vieja se sobreescribe con la suya. **En ese orden,
     * borrar el `receiveChains.clear()` no deja NADA que observar.** Medido:
     * con el `clear()` eliminado, `CRASH-07` sigue verde en su bloque 3.
     *
     * Por eso el estado que tiene que sobrevivir a la segunda restauracion
     * es el PRIMERO, y para eso los dos juegos de cadenas tienen que ser
     * disjuntos: si compartieran una clave, la segunda la sobreescribiria y
     * el resto de la primera desapareceria igual. Dos historias con raices
     * distintas cumplen eso por construccion, y de paso hacen que B NO
     * pueda derivar ninguna clave de A: si compartieran raiz, el descifrado
     * que se espera que falle dependeria de hasta donde hubieran coincidido
     * las dos, que es medir el azar de la criptografia en vez del estado.
     *
     * @return la sesion de BOB en B, y ALICE para poder seguir moviendola.
     */
    private fun historiaB(g: GuionDeterminista): Pair<DoubleRatchetSession, SecureRatchetProtocol> {
        val raiz = ByteArray(32) { (it + 0x5A).toByte() }

        fun sesion(quien: String, remoto: String?) = DoubleRatchetSession(
            rootKey = raiz,
            dhSelf = g.par("B/$quien"),
            dhRemote = remoto?.let { g.par("B/$remoto").publicKey },
            sendChainKey = ByteArray(32) { (it * 7 + 1).toByte() },
            receiveChainKey = ByteArray(32) { (it * 11 + 3).toByte() },
            x25519 = g.x25519,
            kdf = g.kdf,
        )

        val bob = sesion("bob", null)
        val protocoloB = SecureRatchetProtocol(bob, g.protector)
        val alice = SecureRatchetProtocol(sesion("alice", "bob"), g.protector)

        /** Entrega un frame de verdad: si el guion se rompe, que lo diga aqui. */
        fun entregar(frame: ByteArray, enQuien: SecureRatchetProtocol, quien: String) {
            check(enQuien.decrypt(frame) is SecureRatchetProtocol.DecryptResult.Ok) {
                "el intercambio de la historia B tiene que funcionar: eso es el guion, no la prueba ('$quien')"
            }
        }

        alice.initiateEpoch()
        entregar(alice.encrypt("b0".toByteArray()), protocoloB, "b0")
        // BOB responde, ALICE ve una DH nueva y rota la suya: a partir de aqui
        // se puede dejar mensajes en el aire sin entregar.
        entregar(protocoloB.encrypt("a0".toByteArray()), alice, "a0")
        // Cuatro mensajes de esa epoca que NO se entregan, y uno que si: el
        // que si hace que BOB tambien rote, y el siguiente hace que ALICE
        // rote con un `PN` que deja los cuatro en el aire.
        repeat(4) { i -> alice.encrypt("fuera$i".toByteArray()) }
        entregar(alice.encrypt("dentro".toByteArray()), protocoloB, "dentro")
        entregar(protocoloB.encrypt("a1".toByteArray()), alice, "a1")
        // Este ultimo es el que obliga a BOB a saltar su cadena anterior: sus
        // cuatro claves retenidas aparecen aqui, de verdad.
        entregar(alice.encrypt("b1".toByteArray()), protocoloB, "b1")

        return bob to protocoloB
    }

    /**
     * CRASH-07b — RESTAURAR DOS VECES REEMPLAZA, Y SE COMPRUEBA DESCIFRANDO.
     *
     * ```
     * historia A  ->  unidad A   (cadena e3 con un SALTADO REAL)
     * historia B  ->  unidad B   (otra conversacion, raiz propia)
     *
     *   restore(A) sobre una instancia      -> el frame retenido de A DESCIFRA
     *   restore(B) sobre LA MISMA instancia  -> ese frame ya NO descifra
     *                                          y el estado es el de B, byte a byte
     * ```
     *
     * ## QUE OBSERVA ESTA PRUEBA Y QUE NO OBSERVA `CRASH-07`
     *
     * `CRASH-07` mira el NUMERO de cadenas de recepcion. Esta prueba no mira
     * ningun numero: mira si una operacion criptografica de verdad sigue
     * saliendo, y la elige con una propiedad que la cuenta no puede tener.
     *
     * El frame [retenida] sale de la TERCERA cadena de la historia A con
     * `N = 1`, y se emitio DESPUES de que BOB rotara mas alla de esa cadena.
     * En ese momento la clave de ese mensaje ya no se puede volver a
     * derivar —el ratchet no tiene ni la raiz ni el par DH de aquella
     * epoca—: solo existe como clave SALTADA dentro de la tercera cadena.
     * Es decir, hay un SEGUNDO CAMINO a esa clave, pero el segundo camino es
     * exactamente `receiveChains`. Si al restaurar B sobrevive CUALQUIER
     * resto de A que contenga material retenido, ese frame vuelve a
     * descifrar: no porque se haya derivado mal, sino porque el ratchet ha
     * encontrado la clave que A dejaba guardada. Por eso el fallo salta
     * con una operacion criptografica y no con una cuenta, y por eso
     * detecta tambien las variantes en las que se conserva "casi todo" de A.
     *
     * Y el otro orden —restaurar el estado NUEVO y luego el VIEJO— es el
     * unico dentro de una sola historia en el que la acumulacion deja
     * resto, y ese lo mide `CRASH-07`. Ver la cabecera de [historiaB].
     *
     * ## POR QUE NO CON `stateFingerprint()`
     *
     * Porque la huella se construye con `dhSelf.publicKey` y NO cubre el
     * escalar DH privado: dos sesiones con la misma huella pueden tener
     * mitades privadas distintas y no poder hablar nunca. Una prueba que la
     * usara pasaria por la razon equivocada. Aqui la evidencia es un
     * descifrado con clave simetrica Y un agreement DH por debajo —
     * `dhSelf` participa en el ratchet del frame que se descifra—, y el
     * frame de control se compara byte a byte. Precedentes: `KM52-01b`,
     * `KM52-01c`, `KM52B-10b`, `KM52B-04b`.
     *
     * ## LAS DOS PALANCAS SON INDEPENDIENTES, Y HAY QUE MEDIRLAS POR SEPARADO
     *
     * Todo lo que se compara sobre un CLON se compara sobre una sesion
     * construida con [DoubleRatchetSession.restore] —el metodo bajo
     * prueba—, asi que una mutacion de ESE metodo se propaga a las dos
     * referencias y las dos quedan de acuerdo: comparan mal, pero
     * igualmente. Medido: borrando solo el `sendChain` de `restore`, las
     * comparaciones sobre clon PASABAN y el frame de la sesion VIVA no
     * cuadraba. Por eso el frame de control final sale de la sesion viva, y
     * por eso el descifrado —que no depende de la cadena de envio— se
     * compara contra un clon. Medido en las dos direcciones: la palanca del
     * descifrado es la unica que ve el `receiveChains.clear()` borrado, y la
     * del frame vivo es la unica que ve el `sendChain` sin restaurar.
     *
     * ## LO QUE ESTA FUERA
     *
     * El `OutboundRecord` se mira como DATO —su `messageId` y su
     * `deliveryState`, tal como venian—: no hay ninguna decision de a quien
     * reintentar, cuando ni cuantas veces, ni vocabulario de reintento. Ni
     * `MAX_RESEND` ni el medio fisico aparecen por ningun lado.
     */
    @Test
    @DisplayName("CRASH-07b el segundo restore desplaza al primero: un frame de A deja de descifrar y el estado es el de B")
    fun `CRASH-07b el segundo restore desplaza al primero`() {
        val dir = tmp.resolve("segundo-restaurador")
        val g = GuionDeterminista()
        g.dosEpochs()

        // --- 1. HISTORIA A: UNA CADENA CON UN SALTADO REAL DE VERDAD --------
        //
        // El salto no se simula: lo provoca el propio ratchet cuando ALICE
        // rota su DH y su `PN` deja cuatro mensajes sin entregar de la
        // cadena anterior. BOB los retiene de verdad —cuatro claves de
        // mensaje, no un numero— y la cuarta cadena se abre encima.
        val medioA = MedioDeChoque(dir.resolve("A"))
        val jA = journal(g.bobSession, medioA, g)

        g.bobEnviaYAliceRecibe("b3")      // ALICE ve una DH nueva de BOB y rota
        g.aliceEnviaYBobRecibe("a5")      // BOB abre la TERCERA cadena y recibe su N=0
        val retenida = g.aliceCifra("r1") // N=1 de la tercera, SIN entregar
        g.aliceCifra("r2")                // N=2
        g.aliceCifra("r3")                // N=3
        g.aliceCifra("r4")                // N=4
        g.bobEnviaYAliceRecibe("b4")      // ALICE ve OTRA DH nueva y rota: PN = 5
        val repetible = g.aliceEnviaYBobRecibe("a7")  // BOB salta la tercera y abre la CUARTA

        assertEquals(4, g.bobSession.receiveChainCount(), "A tiene CUATRO cadenas, o el guion no mide nada")
        assertEquals(
            7, retenidasDe(g.bobSession).size,
            "y SIETE claves retenidas: dos de la primera epoca (nunca entregadas), una de la segunda " +
                "(el propio `a5` llego con PN = 2 y se salto su cadena) y las cuatro del salto grande",
        )
        assertTrue(
            cadenasDe(g.bobSession).isNotEmpty(),
            "y la tercera cadena tiene que estar entre ellas, que es la que guarda las claves saltadas",
        )

        jA.persistir(g.envio(4, ordinal = 2uL, n = 2))
        val bytesA = assertNotNull(medioA.confirmadoBruto(), "la unidad de A se confirmo")

        // --- 2. HISTORIA B: OTRA CONVERSACION, CON RAIZ PROPIA ---------------
        val medioB = MedioDeChoque(dir.resolve("B"))
        val (bobB, _) = historiaB(g)
        journal(bobB, medioB, g).persistir(g.envio(1, ordinal = 1uL, n = 7))
        val bytesB = assertNotNull(medioB.confirmadoBruto(), "la unidad de B se confirmo")
        // Lo que produce la sesion VIVA de B con su cadena de envio, en el
        // mismo estado en que la unidad la fotografio.
        val esperadoB = frameDeControl(bobB, g)

        // --- 3. LA PALANCA, Y SUS DOS CONTROLES ANTES DE NADA ---------------
        val fotoA = sesionLimpiaDe(bytesA, g).snapshot()
        val fotoB = sesionLimpiaDe(bytesB, g).snapshot()

        // A tiene claves que B no tiene, y ninguna que B tenga: sin esto no
        // queda NADA que pueda sobrevivir a una restauracion de B, y la
        // prueba pasaria por la razon equivocada.
        val idsA = cadenasDe(sesionLimpiaDe(bytesA, g))
        val idsB = cadenasDe(sesionLimpiaDe(bytesB, g))
        assertTrue(idsA.any { it !in idsB }, "A tiene cadenas que B no tiene: es el resto que se busca")
        assertTrue(idsB.all { it !in idsA }, "y B no tiene ninguna de las de A: si las compartieran, B las pisaria todas")
        assertTrue(fotoB.receiveChains.any { it.ratchet.skipped.isNotEmpty() }, "B tiene claves retenidas propias: no es un estado vacio")

        // Y el frame retenido: A lo descifra, desde la clave saltada, y B no
        // puede. Sin estas dos controles, "despues de restaurar B no
        // descifra" podria ser cierto porque el frame no descifra NUNCA.
        val limpioA = descifraConClon(sesionLimpiaDe(bytesA, g), g, retenida)
        assertTrue(
            limpioA is SecureRatchetProtocol.DecryptResult.Ok,
            "una sesion A limpia tiene que descifrar el frame retenido; si no, la prueba no mediria nada: $limpioA",
        )
        assertTrue(
            (limpioA as SecureRatchetProtocol.DecryptResult.Ok).fromSkipped,
            "y lo descifra DESDE LA CLAVE SALTADA de la tercera cadena, que es el unico sitio donde esta",
        )
        val limpioB = descifraConClon(sesionLimpiaDe(bytesB, g), g, retenida)
        assertTrue(
            limpioB !is SecureRatchetProtocol.DecryptResult.Ok,
            "una sesion B limpia NO lo descifra —son dos historias distintas—; si lo descifrara, la prueba " +
                "no distinguiria el estado de A del estado de B: $limpioB",
        )

        // --- 4. RESTAURAR A, Y DESPUES B, SOBRE LA MISMA INSTANCIA -----------
        //
        // `recuperarSobre` monta una CENTINELA y exige, ANTES de recuperar,
        // que no este ya en el estado que va a llegar. Sin eso, un
        // `session.restore()` que no hiciera nada pasaria esta prueba.
        val medio = MedioDeChoque(dir.resolve("instancia"))
        val r = recuperarSobre(medio, g)

        medio.dejarConfirmado(bytesA)
        val a = assertNotNull(r.j.restaurar(), "primera restauracion: A")
        assertEquals(mensajeId(2), a.messageId, "y se recupera A")
        // El registro de salida se mira como DATO. No hay aqui ninguna
        // decision de entrega: el registro vuelve con el estado que traia.
        assertEquals(OutboundDeliveryState.PENDIENTE, a.deliveryState, "el registro vuelve como estaba, sin que la recuperacion lo mueva")

        val trasA = descifraConClon(r.sesion, g, retenida)
        assertTrue(
            trasA is SecureRatchetProtocol.DecryptResult.Ok,
            "la instancia restaurada en A DESCIFRA el frame retenido de A: es lo que hace que la " +
                "comprobacion de mas abajo no sea una asercion sobre el vacio: $trasA",
        )

        // --- 5. LA EVIDENCIA: LA ESTRUCTURA DE A NO SOBREVIVE ---------------
        //
        // Aqui esta el guardian. Sin `receiveChains.clear()`, la tercera y la
        // cuarta cadena de A siguen en el mapa despues de que B se haya
        // escrito encima, y el frame retenido vuelve a descifrar.
        medio.dejarConfirmado(bytesB)
        val b = assertNotNull(r.j.restaurar(), "segunda restauracion: B")
        assertEquals(mensajeId(7), b.messageId, "y lo que se recupera es B, no A")
        assertEquals(OutboundDeliveryState.PENDIENTE, b.deliveryState, "tampoco su estado de entrega se mueve")

        val trasB = descifraConClon(r.sesion, g, retenida)
        assertTrue(
            trasB !is SecureRatchetProtocol.DecryptResult.Ok,
            "tras restaurar B, el frame retenido de A YA NO PUEDE descifrar. Si descifra ($trasB), es que " +
                "una cadena de A —con material retenido— ha sobrevivido a la restauracion, y el estado " +
                "resultante no es el de B: es el de B mas los restos de A, que es el estado de dos historias " +
                "que INV-02 prohibe",
        )

        // Un resto de A que NO lleve claves retenidas tampoco es aceptable, y
        // para verlo hay que mirar el otro lado del descifrado: un frame de la
        // cuarta cadena de A que BOB ya consumio. En el estado de B esa
        // epoca no existe, el ratchet deriva una clave que no es la de A y
        // el AEAD la rechaza. Si la cuarta cadena de A hubiera sobrevivido,
        // el ratchet la encontraria, veria el `N` ya consumido y responderia
        // `REPLAY_OR_UNKNOWN`: un rechazo distinto, y delator.
        val repetido = descifraConClon(r.sesion, g, repetible)
        assertTrue(
            repetido is SecureRatchetProtocol.DecryptResult.Unauthenticated,
            "un frame de una epoca que B no conoce tiene que morir en el AEAD, no en el ratchet ($repetido). " +
                "Si muere antes, es que el ratchet ha encontrado una cadena de la historia anterior",
        )

        // --- 6. Y QUE EL ESTADO ES EL DE B, Y SOLO B, BYTE A BYTE ------------
        //
        // Ni la huella ni el numero de cadenas: el `encrypt()` siguiente, con
        // el agreement DH de su par propio y con el escalar que la unidad
        // traia. Byte a byte contra la sesion que PRODUCIO la unidad, y
        // byte a byte contra una sesion B fresca y limpia construida desde la
        // unidad —no contra "algo que se parece a B".
        val limpiaB = sesionLimpiaDe(bytesB, g)
        assertContentEquals(
            frameEnPunto(limpiaB, g), frameEnPunto(r.sesion, g),
            "el estado es el de B: una sesion FRESCA Y LIMPIA construida desde la unidad produce el mismo frame, no un estado 'parecido a B'",
        )

        // --- 7. Y EL MISMO FRAME, PERO SOBRE LA SESION VIVA ------------------
        //
        // ESTA comprobacion no puede hacerse como las de arriba, y el motivo
        // importa mas que la comprobacion: [clonDe] construye el clon con
        // [DoubleRatchetSession.restore] —el MISMO metodo al que esta
        // mutacion borra una linea—, asi que preguntar sobre un clon es
        // preguntar a la IMPLEMENTACION MUTADA y no al estado. Con `sendChain`
        // sin restaurar, los clones salen de una cadena de envio vacia y
        // COINCIDEN entre si: la comparacion byte a byte de arriba pasaria
        // por la razon equivocada. Medido: V2 (borrar solo el `sendChain` de
        // `restore`) era invisible para todo lo que va sobre un clon.
        //
        // Por eso este frame se saca de la sesion VIVA —la que va a seguir
        // usandose—, igual que la referencia [esperadoB]. Va al FINAL porque
        // `encrypt()` consume la cadena de envio; no genera claves DH
        // (`previewSend` no rota el ratchet), asi que tampoco mueve el punto
        // del generador determinista.
        assertContentEquals(
            esperadoB, frameDeControl(r.sesion, g),
            "el siguiente frame de la sesion VIVA sale byte a byte igual al de B: la cadena de envio tambien se " +
                "ha sustituido, no solo las de recepcion",
        )
    }

    // ===================================================================
    // CRASH-08 — DETERMINISMO DE LA RECUPERACION
    // ===================================================================

    @Test
    @DisplayName("CRASH-08 el mismo estado previo y el mismo corte dan el mismo resultado")
    fun `CRASH-08 determinismo de la recuperacion`() {
        // Los SEIS puntos, DOS veces cada uno, en dos procesos distintos cada
        // vez. El estado previo es identico porque el guion es determinista:
        // las claves se derivan de una etiqueta con SHA-256 y el nonce del
        // SecureFrame sale del `messageKey`, de modo que no hay ni un byte de
        // azar en el camino.
        for (punto in listOf(
            PuntoDeCorte.ANTES_DE_ESCRIBIR,
            PuntoDeCorte.DURANTE_LA_ESCRITURA,
            PuntoDeCorte.DESPUES_DE_ESCRIBIR,
            PuntoDeCorte.DURANTE_LA_VERIFICACION,
            PuntoDeCorte.DESPUES_DE_VERIFICAR,
            PuntoDeCorte.DESPUES_DEL_COMMIT,
        )) {
            val dirA = tmp.resolve("det-A-${punto.name}")
            val dirB = tmp.resolve("det-B-${punto.name}")
            val chA = ejecutarHastaElChoque(dirA, punto)
            val chB = ejecutarHastaElChoque(dirB, punto)
            afirmarQueElCorteFueReal(chA, dirA)
            afirmarQueElCorteFueReal(chB, dirB)

            // --- LO QUE EL MEDIO DEJO, BYTE A BYTE -------------------------
            val medioA = MedioDeChoque(dirA)
            val medioB = MedioDeChoque(dirB)
            assertEquals(
                medioA.pendienteBruto()?.toList(), medioB.pendienteBruto()?.toList(),
                "$punto: el medio tiene el MISMO pendiente",
            )
            assertEquals(
                medioA.confirmadoBruto()?.toList(), medioB.confirmadoBruto()?.toList(),
                "$punto: y el MISMO confirmado",
            )
            assertEquals(chA.escrituras, chB.escrituras, "$punto: mismas escrituras")
            assertEquals(chA.lecturas, chB.lecturas, "$punto: mismas lecturas")
            assertEquals(chA.commits, chB.commits, "$punto: mismos commits")
            assertEquals(chA.libroEnElCorte, chB.libroEnElCorte, "$punto: y el mismo estado logico en el corte")

            // --- Y LO QUE SE RECUPERA DE EL, IDENTICO ----------------------
            val gA = GuionDeterminista().also { it.dosEpochs() }
            val gB = GuionDeterminista().also { it.dosEpochs() }
            assertEquals(
                firmaDeRecuperacion(dirA, gA), firmaDeRecuperacion(dirB, gB),
                "$punto: dos ejecuciones con el mismo estado previo y el mismo corte dan el mismo resultado",
            )
        }
    }

    // ===================================================================
    // CRASH-09 — LA RECUPERACION NO DEPENDE DEL CIERRE ORDENADO
    // ===================================================================

    @Test
    @DisplayName("CRASH-09 la recuperacion es correcta SIN que se haya ejecutado ninguna limpieza")
    fun `CRASH-09 sin cierre ordenado`() {
        // ESTE es el test que responde a la pregunta de si un corte de proceso
        // se simula con excepciones o de verdad.
        //
        // Se simula DE VERDAD: el hijo muere con `SIGKILL`, que no ejecuta
        // `catch` ni `finally` ni los ganchos de cierre de la JVM. Y la
        // recuperacion, sin embargo, acierta en los seis puntos.
        //
        // En los cinco primeros NO hay nada confirmado, y se exige que no se
        // fabrique estado. En el ultimo la unidad esta en el medio, y se
        // exige el estado exacto.
        val puntos = listOf(
            Triple(PuntoDeCorte.ANTES_DE_ESCRIBIR, false, 0),
            Triple(PuntoDeCorte.DURANTE_LA_ESCRITURA, false, 0),
            Triple(PuntoDeCorte.DESPUES_DE_ESCRIBIR, false, 0),
            Triple(PuntoDeCorte.DURANTE_LA_VERIFICACION, false, 0),
            Triple(PuntoDeCorte.DESPUES_DE_VERIFICAR, false, 0),
            Triple(PuntoDeCorte.DESPUES_DEL_COMMIT, true, 1),
        )
        for ((punto, hayConfirmado, commitsEsperados) in puntos) {
            val dir = tmp.resolve("sin-cierre-${punto.name}")
            val ch = ejecutarHastaElChoque(dir, punto)
            afirmarQueElCorteFueReal(ch, dir)
            assertEquals(commitsEsperados, ch.commits, "$punto: los commits que tocaban")

            val g = GuionDeterminista()
            g.dosEpochs()
            val medio = MedioDeChoque(dir)
            val r = recuperarSobre(medio, g)
            val recuperado = r.j.restaurar()

            if (hayConfirmado) {
                assertNotNull(recuperado, "$punto: lo confirmado se recupera sin haber cerrado nada")
                afirmarEstadoRecuperado(r.sesion, g, "$punto")
                assertTrue(r.j.ledger.esDurable(mensajeId(1)), "$punto: y el envio es durable")
            } else {
                assertNull(recuperado, "$punto: lo no confirmado no se recupera")
                assertEquals(0, r.j.ledger.tamanho(), "$punto: y no se registra nada")
                assertContentEquals(
                    frameEnPunto(r.antes, g), frameEnPunto(r.sesion, g),
                    "$punto: la sesion intacta, con criptografia",
                )
            }

            // --- Y LA RECUPERACION NO NECESITA NI LIMPIAR NI SER LIMPIADA ---
            //
            // `retiros == 0` aqui significa dos cosas a la vez: que la
            // recuperacion no retira lo pendiente —no depende de que nadie lo
            // haga— y que puede correr con el medio sucio delante.
            assertEquals(0, medio.retiros, "$punto: la recuperacion no toca lo pendiente")
            assertEquals(0, medio.commits, "$punto: y no confirma nada por su cuenta")
            assertEquals(0, medio.escrituras, "$punto: y no escribe nada por su cuenta")
        }
    }

    // ===================================================================
    // CRASH-10 — LA RECUPERACION VERIFICA LO QUE LEE
    // ===================================================================

    @Test
    @DisplayName("CRASH-10 lo confirmado y danado se rechaza, y la sesion no se toca")
    fun `CRASH-10 la recuperacion verifica`() {
        // Un corte post-commit deja una unidad confirmada y buena. Se dana
        // despues, que es lo que pasa cuando un sector se pierde o cuando el
        // medio era otra cosa. La recuperacion tiene que VERIFICAR: leer no
        // es confiar.
        val dir = tmp.resolve("verificacion-de-la-recuperacion")
        ejecutarHastaElChoque(dir, PuntoDeCorte.DESPUES_DEL_COMMIT)
        val medio = MedioDeChoque(dir)
        val buena = assertNotNull(medio.confirmadoBruto())
        assertEquals(
            mensajeId(1), assertNotNull(gRef().codec().deserialize(buena).outbound, "el control trae envio").messageId,
            "el control: la unidad buena se lee y describe el envio",
        )

        val danadas = listOf(
            Triple(
                "checksum roto",
                buena.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() },
                Km52ChecksumMismatch::class.java,
            ),
            Triple(
                "cuerpo tocado",
                buena.copyOf().also { it[Km52Spec.SNAPSHOT_OFFSET + 3] = (it[Km52Spec.SNAPSHOT_OFFSET + 3] + 1).toByte() },
                Km52ChecksumMismatch::class.java,
            ),
            Triple("cola de basura", buena.copyOf(buena.size + 1).also { it[buena.size] = 0x7A }, Km52FormatException::class.java),
            Triple("truncada", buena.copyOf(buena.size / 2), Km52Truncated::class.java),
        )
        assertEquals(4, danadas.size, "el guion tiene que cubrir cuatro formas de unidad danada")

        for ((nombre, bytes, tipo) in danadas) {
            medio.dejarConfirmado(bytes)
            val g = GuionDeterminista()
            g.dosEpochs()
            val r = recuperarSobre(medio, g)
            val e = org.junit.jupiter.api.Assertions.assertThrows(tipo) { r.j.restaurar() }
            assertTrue(e.message!!.isNotEmpty(), "$nombre: el error dice por que")
            assertEquals(0, r.j.ledger.tamanho(), "$nombre: no se registra nada a medias")
            assertFalse(r.j.ledger.esDurable(mensajeId(1)), "$nombre: y el envio no aparece")
            assertContentEquals(
                frameEnPunto(r.antes, g), frameEnPunto(r.sesion, g),
                "$nombre: la sesion sigue intacta, con criptografia y no con la huella",
            )
        }

        // Y el CONTROL, al final: la misma sesion, con la unidad buena, se
        // restaura. Sin esto, "nunca restaura" pasaria aunque lo que se
        // quisiera probar fuese que el guion esta roto.
        medio.dejarConfirmado(buena)
        val g5 = GuionDeterminista()
        g5.dosEpochs()
        val r5 = recuperarSobre(medio, g5)
        assertNotNull(r5.j.restaurar(), "con la unidad intacta, la recuperacion funciona")
        afirmarEstadoRecuperado(r5.sesion, g5, "control")
    }

    // ===================================================================
    // CRASH-11 — LA RECUPERACION NO ES ENTREGA
    // ===================================================================

    @Test
    @DisplayName("CRASH-11 la recuperacion devuelve el estado de entrega, no lo decide")
    fun `CRASH-11 la recuperacion no es entrega`() {
        // --- 1. COMPORTAMIENTO: EL ESTADO DE ENTREGA SE RECUPERA, NO SE AVANZA
        //
        // Si la recuperacion moviera `PENDIENTE` a `IN_FLIGHT`, o a
        // `DELIVERED`, estaria DECIDIENDO una entrega: y decidir a quien se
        // reintenta, cuando y cuantas veces es de 3Q.5.4, no de aqui. Lo que
        // se exige es lo contrario de lo que haria una politica de entrega:
        // que el estado de entrega es el que estaba PERSISTIDO.
        val dir = tmp.resolve("sin-politica-de-entrega")
        ejecutarHastaElChoque(dir, PuntoDeCorte.DESPUES_DEL_COMMIT)
        val medio = MedioDeChoque(dir)
        val buena = assertNotNull(medio.confirmadoBruto())

        for (estado in OutboundDeliveryState.entries) {
            val g = GuionDeterminista()
            g.dosEpochs()
            val base = g.envio(1, ordinal = 1uL, n = 1)
            val registro = PreparedSend(base.messageId, base.wireFrame, estado, base.createdOrdinal)
            val bytes = g.codec().serialize(
                Km52Unit(
                    g.bobSession.snapshot(),
                    ChainRetentionBook().tablaPara(g.bobSession.snapshot()),
                    registro.aRegistro(),
                ),
            )
            medio.dejarConfirmado(bytes)
            val g2 = GuionDeterminista()
            g2.dosEpochs()
            val r = recuperarSobre(medio, g2)
            val recuperado = assertNotNull(r.j.restaurar(), "se recupera la unidad de $estado")
            assertEquals(
                estado, recuperado.deliveryState,
                "$estado: la recuperacion TRANSPORTA el estado de entrega, no lo avanza ni lo decide",
            )
            assertEquals(estado, r.j.ledger.estadoDe(registro.messageId), "$estado: y el libro lo refleja igual")
            assertEquals(1, r.j.ledger.tamanho(), "$estado: sin crear registros de mas")
        }
        // Y el hueco: la unidad buena de referencia, que es PENDIENTE.
        medio.dejarConfirmado(buena)
        assertEquals(
            OutboundDeliveryState.PENDIENTE,
            assertNotNull(gRef().codec().deserialize(buena).outbound, "el control trae envio").deliveryState,
            "el guion de referencia es PENDIENTE: persistir no entrega",
        )

        // --- 2. AUDITORIA: LA RECUPERACION NO TIRE DE RELOJ NI DE AZAR -------
        //
        // Es la parte mecanica de INV-07. Se audita el CODIGO, no la prosa:
        // un KDoc puede decir "reintento" para explicar por que no lo hay, y
        // eso es lo correcto; lo que no puede aparecer es un
        // `System.currentTimeMillis` o un `Random` en una ruta que se supone
        // reproducible.
        val base = File("src/main/kotlin/com/keymessage/core/transmit")
        assertTrue(base.exists(), "no se encuentra $base desde ${File(".").absolutePath}")
        val prohibidos = listOf(
            "currentTimeMillis", "nanoTime", "Math.random", "Random(", "LocalDate", "Instant.now",
            "Clock.", "System.identityHashCode", "MAX_RESEND", "resendCount", "backoff", "reintentosRestantes",
        )
        val ofensas = mutableListOf<String>()
        for (archivo in base.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            for ((n, linea) in codigoDe(archivo)) {
                for (token in prohibidos) {
                    if (linea.contains(token)) ofensas += "${archivo.name}:$n usa '$token' -> ${linea.trim()}"
                }
            }
        }
        assertTrue(
            ofensas.isEmpty(),
            "la recuperacion tiene que ser una funcion pura del medio: sin reloj, sin azar y sin " +
                "vocabulario de reintento.\n" + ofensas.joinToString("\n") { "  - $it" },
        )
    }

    // ===================================================================
    // UTILIDADES DE APOYO
    // ===================================================================

    private fun gRef() = GuionDeterminista().also { it.dosEpochs() }

    /**
     * Las lineas de CODIGO de un archivo, con su numero.
     *
     * Se salta la prosa de los comentarios porque un KDoc que dice "no
     * reintentamos" es la EXPLICACION de una invariante, y no su
     * infraccion. Lo que se busca es el mecanismo, y el mecanismo es codigo.
     */
    private fun codigoDe(archivo: File): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var enBloque = false
        archivo.readLines().forEachIndexed { i, linea ->
            val t = linea.trim()
            if (enBloque) {
                if (t.contains("*/")) enBloque = false
                return@forEachIndexed
            }
            when {
                t.startsWith("/*") -> if (!t.contains("*/")) enBloque = true
                t.startsWith("*") || t.startsWith("//") -> Unit
                else -> out += (i + 1) to linea
            }
        }
        return out
    }
}

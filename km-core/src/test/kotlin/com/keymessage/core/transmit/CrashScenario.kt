package com.keymessage.core.transmit

import com.keymessage.core.crypto.Hash
import com.keymessage.core.crypto.HashImpl
import com.keymessage.core.crypto.Kdf
import com.keymessage.core.crypto.X25519
import com.keymessage.core.crypto.X25519KeyPair
import com.keymessage.core.crypto.provider.BcChaCha20Poly1305
import com.keymessage.core.crypto.provider.BcHkdfSha256
import com.keymessage.core.crypto.provider.BcX25519
import com.keymessage.core.km52.BinaryKm52UnitCodec
import com.keymessage.core.km52.Km52UnitCodec
import com.keymessage.core.km52.OutboundDeliveryState
import com.keymessage.core.model.MessageId
import com.keymessage.core.ratchet.DoubleRatchetSession
import com.keymessage.core.sf.SecureFrameProtector
import com.keymessage.core.sf.SecureRatchetProtocol
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/* ==================================================================== *
 * 3Q.5.2c — EL ARNES DE CHOQUE.
 *
 * Este archivo NO contiene ninguna prueba: contiene el `guion` que las
 * pruebas de `CrashRecoveryTest` ejecutan, el MEDIO con el que se ejecuta,
 * y el PROCESO QUE SE MATA.
 *
 * ==================================================================== *
 * POR QUE EL CORTE SE PRODUCE MATANDO UN PROCESO Y NO TIRANDO UNA
 * EXCEPCION
 *
 * Un excepcion es una CAUSA, no una INTERRUPCION. Cuando un metodo lanza,
 * la pila se desenrolla: se ejecutan los `catch` que encajen y se ejecuta
 * `finally` SIEMPRE, aunque la excepcion no se capture. Un proceso que
 * muere no ejecuta nada de eso: no hay pila que desenrollar, no hay
 * `catch`, no hay `finally`, no hay gancho de cierre de la JVM.
 *
 * La diferencia decide si una prueba significa algo. Si la recuperacion
 * dependiera de que un bloque de limpieza se ejecutara —porque `persistir`
 * dejara el medio sucio y un `finally` lo retirara, o porque el medio
 *se cerrara al recibir `SIGTERM`— una prueba con excepciones PASSARIA:
 * la excepcion desenrollaria la pila, el `finally` correria, el medio
 * quedaria limpio y la recuperacion tendria el final feliz. La prueba
 * verde estaria midiendo el `finally`, que es justo lo que un corte real
 * no ejecuta.
 *
 * Por eso el punto de corte se alcanza MATANDO el proceso con
 * `Process.destroyForcibly()`, que en POSIX es `SIGKILL`: la senal la
 * entrega el nucleo, el hilo muere donde este y no se ejecuta nada de lo
 * que el usuario escribio. El hijo no "termina mal": deja de existir.
 *
 * Y para que la afirmacion sea COMPROBABLE y no una promesa, el hijo
 * escribe, en el instante exacto del corte, cuantos `discardPending`
 * llevo —cero— y el padre lo lee despues del `SIGKILL`. Si algun dia
 * hubiera un `finally` que limpiara, ese contador dejaria de ser cero y
 * estas pruebas lo dirian.
 *
 * ==================================================================== *
 * POR QUE UN MEDIO DE FICHEROS Y NO EL DOBLE EN MEMORIA
 *
 * Porque el medio tiene que SOBREVIVIR al proceso. Un doble en memoria
 * muere con el hilo que lo escribio, y entonces no habria nada que
 * recuperar: la prueba estaria midiendo un objeto de Kotlin, no una
 * interrupcion.
 *
 * Y NO es un medio de 3Q.5.3: §8.1-G deja el medio fisico fuera de
 * 3Q.5.2b y este checkpoint no lo implementa. Lo que hay aqui es un DOBLE
 * —no tiene fsync, ni journal de sistema de ficheros, ni desgaste, ni
 * cifrado— que cumple el CONTRATO de `TransmitUnitStore` y ademas deja
 * el medio en un estado REAL al que se le pueda matar el dueño.
 * ==================================================================== */

/** Donde corta el proceso, dentro de la costura de persistencia. */
enum class PuntoDeCorte(val orden: Int) {
    /** Sin corte: la operacion se completa. Es el CONTROL. */
    NINGUNO(-1),

    /** 1. Antes de escribir: el medio no recibe nada. */
    ANTES_DE_ESCRIBIR(1),

    /** 2. Durante la escritura: el medio recibe un PREFIJO y se corta. */
    DURANTE_LA_ESCRITURA(2),

    /** 3. Despues de escribir, antes de verificar: la unidad esta ahi y nadie la ha mirado. */
    DESPUES_DE_ESCRIBIR(3),

    /** 4. Durante la verificacion: la lectura empieza y el proceso muere a medias. */
    DURANTE_LA_VERIFICACION(4),

    /** 5. Despues de verificar, antes de confirmar: la unidad esta COMPROBADA y no confirmada. */
    DESPUES_DE_VERIFICAR(5),

    /** 6. Despues de confirmar: la unidad esta confirmada y el proceso muere antes de volver. */
    DESPUES_DEL_COMMIT(6),
    ;

    val esCorte: Boolean get() = this != NINGUNO
}

/**
 * El MEDIO con el que se corta el proceso.
 *
 * ## POR QUE UN FICHERO Y POR QUE SIN `fsync`
 *
 * Dos decisiones, y las dos importan.
 *
 * `fsync` NO se llama a proposito. Un corte de PROCESO no es un corte de
 * corriente: lo que hay en el buffer del sistema de ficheros sobrevive a
 * que muera el proceso, porque el buffer es del nucleo. Omitir el
 * `fsync` modela ademas el caso que de verdad importa —el proceso murio
 * antes de que los bytes bajaran a disco—, que es uno de los cuatro por
 * los que 3Q.5.2b pone el `COMMIT` despues del `VERIFICAR`. Si el doble
 * hiciese `fsync`, estariamos probando un medio mas fuerte que todos los
 * que el contrato tiene que soportar.
 *
 * ## POR QUE `morir()` NO ES UNA EXCEPCION
 *
 * Ver la cabecera del archivo. `morir()` no lanza: escribe su informe,
 * avisa de que el punto esta alcanzado y se queda esperando. Lo unico que
 * lo saca de ahi es la senal del padre. Si lanzara, el `catch (e:
 * Exception)` de `persistir` —que existe, y es correcto— lo interceptaria
 * y la prueba estaria midiendo ese `catch`.
 */
class MedioDeChoque(private val dir: File) : TransmitUnitStore {

    /** Donde muere el proceso. [PuntoDeCorte.NINGUNO] = no muere. */
    var punto: PuntoDeCorte = PuntoDeCorte.NINGUNO

    /**
     * Lo que el proceso va a escribir en su informe, en el instante del corte.
     *
     * Es un TESTIGO, no unacleanup: lo escribe el doble justo antes de
     * quedarse esperando, y por eso mide el estado LOGICO en el punto exacto
     * de corte —que es lo que un corte de proceso destruye y una excepcion no—
     * sin ejecutar nada que una interrupcion real no ejecutaria.
     */
    var alMorir: (() -> String)? = null

    var escrituras: Int = 0
        private set

    var lecturas: Int = 0
        private set

    var commits: Int = 0
        private set

    var retiros: Int = 0
        private set

    private fun f(nombre: String) = File(dir, nombre)

    // --- LECTURA DEL MEDIO, desde CUALQUIER proceso ------------------------

    /** Lo pendiente sin confirmar, o `null`. Lo que HAY, no lo que se quiere. */
    fun pendienteBruto(): ByteArray? = f(MedioDeChoque.PENDIENTE).takeIf { it.isFile }?.readBytes()

    /** Lo confirmado, o `null`. */
    fun confirmadoBruto(): ByteArray? = f(MedioDeChoque.CONFIRMADO).takeIf { it.isFile }?.readBytes()

    /** Deja el medio como si otro proceso hubiera escrito y confirmado. */
    fun sembrar(pendiente: ByteArray?, confirmado: ByteArray?) {
        f(MedioDeChoque.PENDIENTE).delete()
        f(MedioDeChoque.CONFIRMADO).delete()
        pendiente?.let { escribir(PENDIENTE, it) }
        confirmado?.let { escribir(CONFIRMADO, it) }
    }

    /** Pone lo pendiente en el medio SIN pasar por [writeAhead]. Sonda de test. */
    fun dejarPendiente(bytes: ByteArray) = escribir(PENDIENTE, bytes)

    /** Pone lo confirmado en el medio SIN pasar por [commit]. Sonda de test. */
    fun dejarConfirmado(bytes: ByteArray) = escribir(CONFIRMADO, bytes)

    // --- EL CONTRATO DE `TransmitUnitStore`, CON LOS CORTES DENTRO ---------

    override fun writeAhead(bytes: ByteArray) {
        if (punto == PuntoDeCorte.ANTES_DE_ESCRIBIR) {
            // El corte ocurre ANTES de tocar el medio. No hay nada escrito,
            // y ese es el escenario: la sesion no puede tener confirmado ni
            // haber perdido nada, porque no llego a intentarlo en el medio.
            morir()
        }
        escrituras++
        if (punto == PuntoDeCorte.DURANTE_LA_ESCRITURA) {
            // Un FLUJO DE BYTES A MEDIAS: el medio acepta el prefijo y el
            // proceso muere. Lo que queda no es una unidad y no puede
            // llegar a ser estado, y eso hay que demostrarlo, no suponerlo.
            escribir(PENDIENTE, bytes.copyOf(bytes.size / 2))
            morir()
        }
        escribir(PENDIENTE, bytes)
        if (punto == PuntoDeCorte.DESPUES_DE_ESCRIBIR) {
            // La unidad esta ESCRITA y no verificada. Este es el punto que
            // mas veces se escribe mal: el medio tiene una unidad PERFECTA
            // —mismo checksum, misma forma, mismo envio— y lo unico que le
            // falta es que nadie la haya comprobado. Si la recuperacion la
            // adopta, se ha fabricado estado.
            morir()
        }
    }

    override fun readBack(): ByteArray? {
        lecturas++
        if (punto == PuntoDeCorte.DURANTE_LA_VERIFICACION) {
            // La lectura EMPIEZO y el proceso muere antes de devolver. Se
            // distingue del punto 3 en un unico contador —`lecturas`— y en
            // que aqui la verificacion ya estaba en curso.
            morir()
        }
        return pendienteBruto()
    }

    override fun readCommitted(): ByteArray? = confirmadoBruto()

    override fun commit() {
        if (punto == PuntoDeCorte.DESPUES_DE_VERIFICAR) {
            // La unidad esta escrita Y verificada —[readBack] ya ha vuelto y
            // `persistir` ya ha comparado— y el proceso muere antes de
            // promoverla. Es el punto mas incomodo de los seis: el medio
            // tiene lo correcto, y aun asi no es estado.
            morir()
        }
        commits++
        pendienteBruto()?.let { escribir(CONFIRMADO, it) }
        f(MedioDeChoque.PENDIENTE).delete()
        if (punto == PuntoDeCorte.DESPUES_DEL_COMMIT) {
            // La unidad esta CONFIRMADA. El proceso muere antes de que
            // `persistir` reciba la respuesta: no llega a tocar el estado
            // logico de transmision, y el estado logico no tendra que
            // saber nada de eso.
            morir()
        }
    }

    override fun discardPending() {
        retiros++
        f(MedioDeChoque.PENDIENTE).delete()
    }

    override fun hasPending(): Boolean = f(MedioDeChoque.PENDIENTE).isFile

    /**
     * El proceso deja de existir aqui.
     *
     * No es una excepcion y no es un retorno: es la ausencia de las dos
     * cosas. Escribe el informe —que el padre lee despues del `SIGKILL` y
     * que por eso tiene que estar escrito ANTES de morirse—, avisa, y
     * espera a que la senal llegue.
     */
    private fun morir() {
        f(ESTADO).writeText(
            buildString {
                append("punto=").append(punto.name).append('\n')
                append("escrituras=").append(escrituras).append('\n')
                append("lecturas=").append(lecturas).append('\n')
                append("commits=").append(commits).append('\n')
                append("retiros=").append(retiros).append('\n')
                append("testigo=").append(alMorir?.invoke() ?: "sin-testigo").append('\n')
            },
        )
        f(LISTO).writeText(punto.name)
        Thread.sleep(Long.MAX_VALUE)
        // Alcanzable solo si la senal no llego. Hacerlo ruidoso evita que un
        // fallo del arnes se confunda con un corte.
        throw IllegalStateException("el proceso deberia haber sido terminado en el punto ${punto.name}")
    }

    private fun escribir(nombre: String, bytes: ByteArray) {
        dir.mkdirs()
        f(nombre).writeBytes(bytes)
    }

    companion object {
        const val PENDIENTE = "pendiente.bin"
        const val CONFIRMADO = "confirmado.bin"
        const val LISTO = "listo"
        const val ESTADO = "estado.txt"
        const val SIN_MORIR = "sin-morir.txt"
        const val ERROR = "error.txt"
        const val SALIDA = "salida.txt"
    }
}

/** Lo que el proceso deja escrito en el instante en que se le mata. */
class InformeDelChoque(
    val punto: PuntoDeCorte,
    private val lineas: Map<String, String>,
    private val salida: String,
    private val codigo: Int,
) {
    fun n(clave: String): Int = lineas[clave]?.toIntOrNull()
        ?: error("el hijo no informo de '$clave' (informe completo: $lineas)")

    val escrituras: Int get() = n("escrituras")
    val lecturas: Int get() = n("lecturas")
    val commits: Int get() = n("commits")
    val retiros: Int get() = n("retiros")

    /** `true` si el proceso ha muerto por SEÑAL y no ha salido por su cuenta. */
    val muertoPorSenal: Boolean get() = codigo == 137 || codigo == -9

    /**
     * El estado LOGICO del proceso en el instante del corte.
     *
     * `null` si el hijo no instalo testigo. Vive en el informe porque es lo
     * unico que un corte destruye: un libro de transmision que se hubiera
     * adelantado al `COMMIT` no deja rastro en el medio, y su unica evidencia
     * es esta linea.
     */
    val testigo: String?
        get() = lineas["testigo"]?.takeIf { it != "sin-testigo" }

    /** El numero de envios que el libro declara durables en el corte. */
    val libroEnElCorte: Int?
        get() = testigo?.split(';')?.firstOrNull { it.startsWith("libro-") }
            ?.removePrefix("libro-")?.toIntOrNull()

    val informe: String = buildString {
        append("punto: ").append(punto.name).append('\n')
        append("codigo de salida: ").append(codigo).append('\n')
        append(lineas.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }).append('\n')
        append("salida del hijo:\n").append(salida.ifBlank { "(vacia)" })
    }
}

/**
 * Lanza el proceso, espera a que alcance el punto y lo mata.
 *
 * ## POR QUE UN FICHERO DE SEÑAL Y NO LA SALIDA ESTANDAR
 *
 * Porque el padre tiene que poder ESPERAR sin bloquearse leyendo un flujo
 * que el hijo puede no cerrar nunca —que es justo lo que pasa si el punto
 * de corte no se alcanza—. Un fichero de senal se puede sondear con un
 * plazo, y si el proceso se acaba antes, el sondeo lo ve y el padre puede
 * decir POR QUE en vez de colgarse.
 *
 * El reloj se usa AQUI, en el arnes, y no en el codigo de produccion:
 * INV-07 prohibe el reloj donde hay una DECISION que se pueda reproducir, y
 * esto no decide nada sobre el estado.
 */
fun ejecutarHastaElChoque(
    dir: File,
    punto: PuntoDeCorte,
    limiteMs: Long = 120_000,
): InformeDelChoque {
    dir.mkdirs()
    listOf(
        MedioDeChoque.LISTO, MedioDeChoque.ESTADO,
        MedioDeChoque.SIN_MORIR, MedioDeChoque.ERROR,
    ).forEach { File(dir, it).delete() }
    File(dir, MedioDeChoque.SALIDA).delete()

    val java = File(System.getProperty("java.home"), "bin/java").absolutePath
    val proceso = ProcessBuilder(
        java,
        "-cp", System.getProperty("java.class.path"),
        "com.keymessage.core.transmit.CrashChild",
        dir.absolutePath,
        punto.name,
    )
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(File(dir, MedioDeChoque.SALIDA)))
        .start()

    val limite = System.currentTimeMillis() + limiteMs
    var alcanzado: File? = null
    while (System.currentTimeMillis() < limite) {
        alcanzado = File(dir, MedioDeChoque.LISTO).takeIf { it.isFile }
        if (alcanzado != null) break
        if (!proceso.isAlive) break
        Thread.sleep(20)
    }

    if (alcanzado == null) {
        proceso.destroyForcibly()
        proceso.waitFor(30, TimeUnit.SECONDS)
        error(
            "el hijo no alcanzó el punto ${punto.name}.\n" +
                "  sin morir: ${File(dir, MedioDeChoque.SIN_MORIR).isFile}\n" +
                "  error: ${File(dir, MedioDeChoque.ERROR).takeIf { it.isFile }?.readText() ?: "(ninguno)"}\n" +
                "  salida: ${File(dir, MedioDeChoque.SALIDA).takeIf { it.isFile }?.readText() ?: "(vacia)"}",
        )
    }

    // AQUI ESTA EL CORTE. `destroyForcibly()` es `SIGKILL` en POSIX: el
    // nucleo mata el hilo donde esta, sin desenrollar la pila, sin
    // ejecutar `finally` y sin lanzar los ganchos de cierre de la JVM.
    proceso.destroyForcibly()
    check(proceso.waitFor(60, TimeUnit.SECONDS)) { "el hijo no muere ni con SIGKILL" }

    val lineas = File(dir, MedioDeChoque.ESTADO)
        .takeIf { it.isFile }
        ?.readLines()
        ?.filter { it.contains('=') }
        ?.associate { it.substringBefore('=') to it.substringAfter('=') }
        .orEmpty()
    val salida = File(dir, MedioDeChoque.SALIDA).takeIf { it.isFile }?.readText().orEmpty()

    return InformeDelChoque(punto, lineas, salida, proceso.exitValue())
}

/* ==================================================================== *
 * EL GUION DETERMINISTA.
 *
 * ## POR QUE UN GUION DISTINTO DEL DE 3Q.5.2b, Y POR QUE NECESITA UN
 * `X25519` PROPIO
 *
 * Porque este checkpoint necesita que el proceso que se mata y el proceso
 * que recupera PARTAN DEL MISMO ESTADO. `SecureTransmitJournalTest` genera
 * sus claves con `generateKeyPair()`, y dos claves aleatorias no producen
 * el mismo estado en dos ejecuciones.
 *
 * La fuente de azar no es solo el arranque del guion: `initiateEpoch` —que
 * este guion necesita para abrir la primera epoca— y el giro reactivo del
 * ratchet DH generan un par NUEVO con `x25519.generateKeyPair()`. Un
 * `X25519` que cumple el contrato pero fabrica las claves por contador
 * quita el azar de ahi sin tocar ni una linea de produccion: es un DOBLE
 * de la PRIMITIVA, y las dos mitades siguen hablando con criptografia real
 * —el secreto compartido sale del mismo X25519 de BouncyCastle—.
 *
 * ## POR QUE EL CONTADOR SE COPIA AL CLONAR
 *
 * El control criptografico de estas pruebas compara el `encrypt()` de la
 * sesion con el de un CLON suyo. Si el clon generase la clave #0 y la
 * sesion viva la #2, los frames serian distintos y el test mediria el
 * contador en vez del estado. [X25519Determinista.copiaEnElMismoPunto] da al
 * clon un generador QUE SIGUE DONDE ESTA el suyo: dos sesiones en el mismo
 * estado piden la misma clave siguiente, que es lo que tiene que ser cierto.
 *
 * ## POR QUE ESO CONVIERTE LA RECUPERACION EN DETERMINISTA
 *
 * El `encrypt()` de `SecureRatchetProtocol` deriva su nonce por HKDF del
 * `messageKey`, y el `messageKey` sale de la cadena: con las claves
 * deterministas no hay ni un byte de azar en el camino del estado. Un guion
 * determinista convierte "dos ejecuciones" en "dos ejecuciones del MISMO
 * estado previo", que es la precondicion de INV-07 y la que permite
 * comparar resultados.
 * ==================================================================== */

/**
 * `X25519` real con la GENERACION de claves por contador.
 *
 * `publicKey` y `agree` NO se tocan: son X25519 de verdad, y el secreto
 * compartido de cada par es el que corresponde. Lo unico que cambia es de
 * donde sale el escalar cuando alguien pide un par NUEVO, y pasa a salir de
 * una etiqueta y un contador: el mismo numero, los mismos bytes.
 */
class X25519Determinista(
    private val real: X25519,
    private val hash: Hash,
    private val etiqueta: String,
) : X25519 by real {

    /** Pares generados por ESTA instancia. */
    var contador: Int = 0

    override fun generateKeyPair(): X25519KeyPair {
        val sk = hash.sha256("$etiqueta/$contador".toByteArray())
        contador++
        return X25519KeyPair(sk, real.publicKey(sk))
    }

    /**
     * Un generador identico, parado en el MISMO punto.
     *
     * Es lo que hace que un clon de una sesion y la sesion produzcan el
     * mismo frame siguiente. Sin esto, el control criptografico compararia
     * el estado con el CONTADOR y pasaria por la razon equivocada.
     */
    fun copiaEnElMismoPunto(): X25519Determinista =
        X25519Determinista(real, hash, etiqueta).also { it.contador = contador }
}

class GuionDeterminista {
    private val hashReal: Hash = HashImpl()

    /** Primitiva con generacion determinista. Ver la cabecera de este bloque. */
    val x25519: X25519Determinista = X25519Determinista(BcX25519(), hashReal, "KM52C/DH")

    val kdf: Kdf = BcHkdfSha256()
    val protector: SecureFrameProtector = SecureFrameProtector(BcChaCha20Poly1305(), kdf)
    val hash: Hash = hashReal

    private val rootKey = ByteArray(32) { (it + 1).toByte() }

    /** Clave DH derivada de una etiqueta. Misma etiqueta, mismos bytes. */
    fun par(etiqueta: String): X25519KeyPair {
        val sk = hash.sha256("KM52C/$etiqueta".toByteArray())
        return X25519KeyPair(sk, x25519.publicKey(sk))
    }

    fun sesion(dhSelf: X25519KeyPair, dhRemote: ByteArray?): DoubleRatchetSession =
        DoubleRatchetSession(
            rootKey = rootKey,
            dhSelf = dhSelf,
            dhRemote = dhRemote,
            sendChainKey = ByteArray(32) { (it * 3).toByte() },
            receiveChainKey = ByteArray(32) { (it * 5).toByte() },
            x25519 = x25519,
            kdf = kdf,
        )

    val bobSession: DoubleRatchetSession = sesion(par("bob"), null)
    private val aliceSession: DoubleRatchetSession = sesion(par("alice"), par("bob").publicKey)
    private val alice = SecureRatchetProtocol(aliceSession, protector)
    val bob = SecureRatchetProtocol(bobSession, protector)

    /** Los frames de Alice, en orden de emision. Se rellena en [dosEpochs]. */
    var frames: List<ByteArray> = emptyList()
        private set

    init {
        aliceSession.initiateEpoch()
    }

    fun codec(): Km52UnitCodec = BinaryKm52UnitCodec(x25519, hash)

    /**
     * Deja a BOB con DOS cadenas de recepcion y dos frames de la epoca VIEJA
     * sin entregar: el estado que hay que recuperar tiene claves retenidas
     * de verdad, y recuperarlas es parte del contrato.
     */
    fun dosEpochs() {
        val a0 = enviar("a0")
        val a1 = alice.encrypt("a1".toByteArray())   // N=1, epoca 1: RETENIDA
        val a2 = alice.encrypt("a2".toByteArray())   // N=2, epoca 1: RETENIDA
        enviarB("b0")
        enviarB("b1")
        enviarB("b2")
        val a3 = enviar("a3")
        val a4 = alice.encrypt("a4".toByteArray())   // epoca 2, N = 1: PENDIENTE
        frames = listOf(a0, a1, a2, a3, a4)
    }

    /**
     * Una sesion que NO es la del guion.
     *
     * Es la que hace OBSERVABLE la recuperacion. Si el proceso que recupera
     * arranca ya en el estado que la unidad describe, entonces `restaurar()`
     * no tiene nada que hacer y un `session.restore()` que se borrara a si
     * mismo pasaria estas pruebas sin que nadie se entere: el estado
     * estaria ahi igual. Recuperar SOBRE una centinela obliga a que el
     * estado RECUPERADO aparezca, y no solo a que no desaparezca.
     */
    fun sesionSentinel(): DoubleRatchetSession = sesion(par("centinela"), null)

    /** ALICE cifra y BOB recibe. Es la unica via de abrir una cadena nueva. */
    fun aliceEnviaYBobRecibe(texto: String): ByteArray = enviar(texto)

    /** BOB cifra y ALICE recibe. Abre el giro de la mitad de ALICE. */
    fun bobEnviaYAliceRecibe(texto: String): ByteArray = enviarB(texto)

    private fun enviar(texto: String): ByteArray {
        val w = alice.encrypt(texto.toByteArray())
        val r = bob.decrypt(w)
        check(r is SecureRatchetProtocol.DecryptResult.Ok) { "esperaba Ok en '$texto', fue $r" }
        return w
    }

    private fun enviarB(texto: String): ByteArray {
        val w = bob.encrypt(texto.toByteArray())
        val r = alice.decrypt(w)
        check(r is SecureRatchetProtocol.DecryptResult.Ok) { "esperaba Ok en '$texto', fue $r" }
        return w
    }

    /**
     * Cifra con ALICE sin entregar.
     *
     * Hace falta para mover el ratchet de BOB entre dos persistencias: sin
     * esto, dos unidades seguidas serian indistinguibles por su estado
     * criptografico y una prueba de retroceso pasaria por la razon
     * equivocada.
     */
    fun aliceCifra(texto: String): ByteArray = alice.encrypt(texto.toByteArray())

    /** Un envio sobre el frame `indice` de [frames]. */
    fun envio(indice: Int, ordinal: ULong, n: Int) = PreparedSend(
        messageId = mensajeId(n),
        wireFrame = frames[indice],
        deliveryState = OutboundDeliveryState.PENDIENTE,
        createdOrdinal = ordinal,
    )
}

/** `messageId` reproducible: mismo numero, mismos 16 bytes. */
fun mensajeId(n: Int) = MessageId.from(UUID(0x0102030405060700L + n, 0x090a0b0c0d0e0f00L + n))

/**
 * Una sesion CLONADA, con el mismo estado y las mismas primitivas.
 *
 * El estado intacto se demuestra con el CIPHERTEXT de un `encrypt()`
 * posterior contra este clon, NUNCA con `stateFingerprint()`: la huella se
 * construye con `dhSelf.publicKey` y no cubre el escalar DH privado, asi
 * que dos sesiones con la misma huella pueden tener mitades privadas
 * distintas y no poder hablar nunca. Precedente: `KM52-01b`, `KM52B-04b`,
 * `KM52B-10b`.
 */
fun clonDe(s: DoubleRatchetSession, g: GuionDeterminista) =
    DoubleRatchetSession.restore(s.snapshot(), g.x25519.copiaEnElMismoPunto(), g.kdf)

/** Cifrado de un frame de control con una sesion dada. */
fun frameDeControl(s: DoubleRatchetSession, g: GuionDeterminista) =
    SecureRatchetProtocol(s, g.protector).encrypt("ctrl".toByteArray())

/** Las claves retenidas de una sesion, como `(chainId en hex, N)`. */
fun retenidasDe(s: DoubleRatchetSession): List<Pair<String, UInt>> =
    s.snapshot().receiveChains.flatMap { c -> c.ratchet.skipped.keys.map { hexBytes(c.chainId) to it.second } }

fun hexBytes(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

/* ==================================================================== *
 * EL PROCESO QUE SE MATA.
 *
 * ## POR QUE ES UNA CLASE CON `main` Y NO UN METODO DEL TEST
 *
 * Porque tiene que ser OTRO PROCESO. Un `SIGKILL` al hilo del test mataria
 * tambien al arnes, y una excepcion en un hilo —por mucho `Error` que sea—
 * seguiria ejecutando los `finally` de ese hilo.
 *
 * ## POR QUE ESCRIBIR `sin-morir.txt` ES LA PRUEBA DE QUE EL CORTE OCURRIO
 *
 * Si `persistir` vuelve, el guion no ha muerto por el punto que decia.
 * El fichero se escribe al FINAL, y solo se llega al final si el proceso
 * sigue vivo. El padre lo comprueba: la ausencia de ese fichero es parte del
 * resultado, no un detalle.
 * ==================================================================== */
object CrashChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args[0])
        val punto = PuntoDeCorte.valueOf(args[1])

        val g = GuionDeterminista()
        g.dosEpochs()
        val medio = MedioDeChoque(dir).apply { this.punto = punto }
        val journal = SecureTransmitJournal(
            g.bobSession, medio, g.codec(), TransmitLedger(), ChainRetentionBook(),
        )

        // El testigo del estado LOGICO en el instante del corte. Es lo unico
        // que un corte destruye y que ninguna lectura del medio puede
        // reconstruir despues, asi que hay que leerlo mientras el proceso
        // todavia esta: por eso lo escribe el punto de corte, no el test.
        medio.alMorir = { "libro-${journal.ledger.tamanho()}" }

        try {
            // El frame `a1` queda RETENIDO: el estado que hay que proteger
            // tiene claves retenidas de verdad. Un envio sin retenidas seria
            // un estado mas pobre del que la propiedad protege.
            journal.persistir(g.envio(1, ordinal = 1uL, n = 1))
        } catch (e: Throwable) {
            File(dir, MedioDeChoque.ERROR).writeText("${e::class.java.name}: ${e.message}\n${e.stackTraceToString()}")
        }

        File(dir, MedioDeChoque.SIN_MORIR).writeText("`persistir` ha terminado con exito")
    }
}

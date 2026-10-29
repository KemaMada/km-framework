package com.km.transport.contact

import com.km.model.IdentityId
import java.util.concurrent.locks.ReentrantLock

/**
 * La retencion ACOTADA de un frame PREKEY de un par que todavia no es contacto.
 *
 * ## EL HUECO QUE TAPA
 *
 * El frame PREKEY NO transporta el bundle del emisor: su cabecera es FIJA (44 B)
 * y no cabe un `ContactBundle` ahi. km-core exige el bundle VERIFICADO del
 * emisor para aceptar un PREKEY (KM-0006 §7.3). De modo que hay una carrera:
 *
 * - Si el `contact_exchange` llega ANTES que el PREKEY, el receptor registra al
 *   emisor y el PREKEY se acepta por el camino normal.
 * - Si el PREKEY llega ANTES, el receptor no tiene de quien es el bundle: no
 *   puede aceptarlo (no fabricaria un contexto sin autoridad) y no puede tirarlo
 *   (el emisor ya esta intentando establecer).
 *
 * Esta clase es el segundo caso: el PREKEY se RETIENE un rato, y cuando llega el
 * `contact_exchange` se entrega al camino de aceptacion de siempre.
 *
 * ## LOS LIMITES SON CORTOS Y ACOTADOS
 *
 * - TTL de 60 s: es la ventana en la que el emisor sigue intentando.
 * - Tope de 16: un retenedor de PREKEYs de pares desconocidos crece con lo que
 *   le manden. Con tope, el peor caso esta acotado; al llenarse sale el MAS
 *   ANTIGUO, que es el que mas probablemente ya no valga.
 *
 * ## EL RELOJ ES INYECTADO
 *
 * Un TTL probado con el reloj real es un test que tarda 60 s o que no existe.
 */
class ContactExchangeCoordinator(
    /**
     * Si el par YA es un contacto conocido.
     *
     * Funcion y no registro entero: lo que esta clase necesita es una pregunta,
     * y el "conocido" de verdad es "hay contacto Y hay sesion instalada", que lo
     * decide el consumidor.
     */
    private val conocido: (IdentityId) -> Boolean,
    /** Ventana de retencion, en milisegundos. */
    private val ttlMs: Long = TTL_MS,
    /** Cuantos emisores distintos se retienen a la vez. */
    private val maxRetenidos: Int = MAX_RETENIDOS,
    /** El frame retenido no puede pasar de aqui. */
    private val maxFrameBytes: Int = MAX_FRAME_BYTES,
    /**
     * Clasificador del wire: "¿esto es un PREKEY?".
     *
     * Se INYECTA y no se importa de `com.km.frame`: km-transport no depende del
     * paquete de frames (su guard de separacion lo prohibe), y reconocer un
     * frame es informacion que ya tiene quien compone el cliente. El que llama
     * pasa `PrekeyFrame::esPrekey` de km-core; aqui no se duplica el parser.
     */
    private val esPrekey: (ByteArray) -> Boolean,
    /** El reloj, inyectado. */
    private val now: () -> Long = System::currentTimeMillis,
    /** Traza inyectada. */
    private val logger: ContactLogger = ContactLogger.NONE,
) {

    private class Retenido(val frame: ByteArray, val expiresAt: Long)

    /**
     * Los PREKEYs retenidos, por emisor.
     *
     * `LinkedHashMap` porque el ORDEN DE INSERCION es lo que hace falta para
     * expulsar al mas antiguo cuando el mapa se llena.
     */
    private val retenidos = LinkedHashMap<IdentityId, Retenido>()

    /**
     * Frames de un par DESCONOCIDO que se descartaron sin retener.
     *
     * ## POR QUE ES UN CONTADOR Y NO SOLO UNA LINEA DE LOG
     *
     * Porque el fallo que este contador mide es INVISIBLE: un mensaje cifrado
     * que llega de un par que el receptor no tiene registrado no es un PREKEY,
     * asi que no se puede retener, y se tira. Sin un numero en la pantalla, el
     * sintoma es "el otro no recibe" y no hay nada que mirar. Con el numero, un
     * operador ve que el escritorio esta descartando frames y sabe que el
     * problema es de identidad/contactos, no de cifrado.
     *
     * Se incrementa bajo [candado], asi que no necesita atomico propio.
     */
    private var descartesDesconocidos: Int = 0

    /**
     * EL candado UNICO de la senalizacion: uno para el SIGNAL y para el PREKEY,
     * y la entrega ocurre DENTRO. Con dos candados, un PREKEY y un
     * `contact_exchange` que llegan a la vez podrian cruzarse.
     */
    private val candado = ReentrantLock()

    /**
     * Un PREKEY entrante.
     *
     * @param entregar el camino de aceptacion de siempre, invocado DENTRO del
     *   candado con el frame intacto.
     * @return `true` si se entrego en el acto, `false` si quedo retenido.
     */
    fun onPrekey(
        from: IdentityId,
        wire: ByteArray,
        entregar: (IdentityId, ByteArray) -> Unit,
    ): Boolean {
        candado.lock()
        try {
            if (conocido(from)) {
                entregar(from, wire)
                return true
            }
            if (!esPrekey(wire)) {
                descartesDesconocidos++
                logger.log(
                    ContactLogLevel.WARN,
                    "[SESSION] $CODE_UNKNOWN_PEER_FRAME peer=${from.value.take(16)} " +
                        "bytes=${wire.size} motivo=no-es-prekey",
                )
                return false
            }
            if (wire.size > maxFrameBytes) {
                descartesDesconocidos++
                logger.log(
                    ContactLogLevel.WARN,
                    "[SESSION] $CODE_UNKNOWN_PEER_FRAME peer=${from.value.take(16)} " +
                        "bytes=${wire.size} motivo=prekey-demasiado-grande limite=$maxFrameBytes",
                )
                return false
            }
            barrer()
            // Una por emisor: el PREKEY mas reciente REEMPLAZA al anterior y
            // pasa a ser el mas reciente de la cola de expulsion.
            retenidos.remove(from)
            retenidos[from] = Retenido(wire.copyOf(), now() + ttlMs)
            while (retenidos.size > maxRetenidos) {
                val masAntiguo = retenidos.keys.first()
                retenidos.remove(masAntiguo)
                logger.log(
                    ContactLogLevel.WARN,
                    "[SESSION] PREKEY retenido expulsado por tope peer=${masAntiguo.value.take(16)}",
                )
            }
            logger.log(
                ContactLogLevel.INFO,
                "[SESSION] PREKEY de contacto desconocido RETENIDO peer=${from.value.take(16)} " +
                    "bytes=${wire.size} retenidos=${retenidos.size}",
            )
            return false
        } finally {
            candado.unlock()
        }
    }

    /**
     * El emisor ya es contacto: se drena lo retenido de el.
     *
     * Un frame caducado NO se entrega nunca: abriria una sesion con material
     * viejo, que es exactamente lo que el TTL evita.
     *
     * @return cuantos frames se entregaron.
     */
    fun onContactoRegistrado(
        from: IdentityId,
        entregar: (IdentityId, ByteArray) -> Unit,
    ): Int {
        candado.lock()
        try {
            barrer()
            val retenido = retenidos.remove(from) ?: return 0
            logger.log(
                ContactLogLevel.INFO,
                "[SESSION] PREKEY retenido ENTREGADO al registrar el contacto peer=${from.value.take(16)}",
            )
            entregar(from, retenido.frame)
            return 1
        } finally {
            candado.unlock()
        }
    }

    /** Descarta lo retenido. El coordinador deja de servir. */
    fun parar() {
        candado.lock()
        try {
            retenidos.clear()
        } finally {
            candado.unlock()
        }
    }

    /** Cuantos emisores tienen un PREKEY retenido ahora mismo. */
    fun retenidos(): Int {
        candado.lock()
        try {
            barrer()
            return retenidos.size
        } finally {
            candado.unlock()
        }
    }

    /**
     * Frames de pares DESCONOCIDOS descartados desde el arranque.
     *
     * Es el contador que se pinta en la barra y en DIAGNOSTICO. No cuenta los
     * RETENIDOS: retener es el camino bueno de la carrera SIGNAL/PREKEY.
     */
    fun unknownDropped(): Int {
        candado.lock()
        try {
            return descartesDesconocidos
        } finally {
            candado.unlock()
        }
    }

    /**
     * Barrido PEREZOSO: no hay temporizador; lo caducado se va en la siguiente
     * operacion. Basta porque lo unico que importa es que un frame caducado no
     * se entregue, y eso lo garantiza el `barrer()` que precede a toda entrega.
     */
    private fun barrer() {
        val instante = now()
        retenidos.filterValues { it.expiresAt <= instante }.keys.toList().forEach { retenidos.remove(it) }
    }

    companion object {
        /** La ventana de retencion. */
        const val TTL_MS: Long = 60_000L

        /** Cuantos emisores distintos se retienen a la vez. */
        const val MAX_RETENIDOS: Int = 16

        /** El frame retenido no pasa de 8 KiB. */
        const val MAX_FRAME_BYTES: Int = 8 * 1024

        /**
         * Codigo ESTABLE del frame de un par desconocido que se descarta.
         *
         * Es lo que permite `grep UNKNOWN_PEER_FRAME` en una traza real. Va en
         * el mensaje y no en un campo aparte porque el sumidero es texto.
         */
        const val CODE_UNKNOWN_PEER_FRAME: String = "UNKNOWN_PEER_FRAME"
    }
}

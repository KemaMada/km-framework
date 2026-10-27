package com.km.transport.contact

import com.km.transport.SignalDelivery
import com.km.transport.SignalOutcome

/**
 * El fallo del intercambio de contactos, con su codigo ESTABLE.
 *
 * Una excepcion propia y no un `IllegalStateException`: el codigo es lo que se
 * pinta en pantalla y lo que se escribe en el log, y `IllegalStateException` no
 * lo tiene. Con esta clase el codigo viaja DENTRO del fallo y no se puede perder
 * en el mensaje, que es texto libre.
 */
class ContactExchangeException(
    /** El codigo estable: `CONTACT_EXCHANGE_*`, o el que devuelva el transporte. */
    val code: String,
    message: String,
) : Exception(message)

/**
 * La SECUENCIA del emisor: portador propio -> `SIGNAL` -> sesion y PREKEY.
 *
 * ## POR QUE ES UNA CLASE Y NO UNOS IFS EN EL CONTENEDOR
 *
 * Porque el ORDEN es la garantia, y una garantia que vive en un `if` de un
 * metodo de 200 lineas no se puede probar. Aqui las tres piezas son funciones
 * inyectadas y un test dice "el SIGNAL sale ANTES de que se abra la sesion" sin
 * necesitar rele, ni terminal, ni dispositivo.
 *
 * ## EL ORDEN Y POR QUE IMPORTA
 *
 * El emisor de un `contact_exchange` es el que ya tiene el bundle del otro: el
 * que escaneo, el que abrio el `.km`. El otro no lo tiene. Si el emisor abre la
 * sesion y entrega el PREKEY ANTES de publicar su bundle, el receptor recibe un
 * PREKEY de un par desconocido y no puede aceptarlo: km-core exige el bundle
 * verificado del emisor (KM-0006 §7.3) y el frame PREKEY no lo transporta.
 *
 * Publicar el bundle PRIMERO le da al receptor la oportunidad de registrarlo, y
 * el caso inverso (PREKEY antes) lo cubre la retencion acotada del receptor
 * ([ContactExchangeCoordinator]).
 *
 * ## SI EL INTERCAMBIO FALLA, NO HAY SESION
 *
 * Abrir la sesion sin haber publicado el bundle deja al receptor con un PREKEY
 * que no puede aceptar y al emisor con una sesion que parece viva y no envia
 * nada. El fallo se reporta con codigo y el contacto importado NO se retira: el
 * usuario lo importo, y eso sigue siendo cierto.
 *
 * ## UN REENVIO ACOTADO TRAS LA RE-AUTENTICACION
 *
 * Caso real (#398): el rele declara EXPIRADA la sesion justo antes de que salga
 * la SIGNAL. El transporte ya renegocia solo, pero el bundle en vuelo se
 * abandonaba porque esto LANZABA en el primer fallo, y el contacto nunca se
 * auto-registraba aunque la sesion nueva estuviera viva medio segundo despues.
 *
 * Por eso, SOLO ante `CONTACT_EXCHANGE_NOT_CONNECTED`, se espera la
 * re-autenticacion ([esperarReauth]) y se reenvia el MISMO [ByteArray] UNA vez.
 * Lo que NO se hace:
 *
 *  - no se re-exporta el portador: otro material seria otro bundle (AB-R5);
 *  - no se reabre X3DH ni se re-emite PREKEY tras la re-auth (AB-R3);
 *  - no se reintenta PEER_NOT_FOUND/REFUSED/FAILED: ahi el problema es el par o
 *    la red del receptor, y esperar nuestra re-auth no lo arregla (AB-R4);
 *  - el PREKEY sigue saliendo SOLO tras un Delivered (AB-R2).
 */
class ContactExchangeBootstrap(
    /**
     * El portador PROPIO, ya en los bytes que viajan: ISO-8859-1 del texto
     * comprimido, los MISMOS que van en el QR.
     */
    private val exportarPortador: () -> ByteArray,
    /** Envia el portador por `SIGNAL/contact_exchange`. */
    private val enviarPortador: (ByteArray) -> SignalOutcome,
    /** Abre la sesion E2E y ENTREGA el frame PREKEY. */
    private val abrirSesionYEntregar: () -> Result<Unit>,
    /**
     * Espera a que el transporte vuelva a tener sesion util.
     *
     * `suspend` porque la espera real (`JvmRelayTransport.awaitOnline`) es
     * cancelable y no bloquea un hilo. El default `false` mantiene el
     * comportamiento de quien no cablee la costura: un unico envio, sin espera.
     */
    private val esperarReauth: suspend (timeoutMs: Long) -> Boolean = { false },
    /**
     * Plazo TOTAL de la espera de re-autenticacion: 25 s.
     *
     * Por encima del backoff maximo del motor (30 s implicaria rele
     * inalcanzable) y por debajo de la paciencia de quien acaba de importar un
     * contacto. Si tarda mas, el resultado honesto es el codigo estable.
     */
    private val timeoutReauthMs: Long = DEFAULT_TIMEOUT_REAUTH_MS,
    /** Reenvios extra permitidos: UNO. Mas serian una tormenta contra el rele. */
    private val maxReenvios: Int = DEFAULT_MAX_REENVIOS,
    /** Traza inyectada. */
    private val logger: ContactLogger = ContactLogger.NONE,
) {

    /**
     * Ejecuta la secuencia.
     *
     * @return `Result.success` solo si la SIGNAL salio Y la sesion se abrio con
     *   su PREKEY entregado. Cualquier otro final es un fallo con codigo.
     * @throws ContactExchangeException con el codigo estable del motivo.
     * @throws kotlinx.coroutines.CancellationException si se cancela la espera:
     *   la cancelacion no es un fallo del intercambio y se propaga tal cual.
     */
    suspend fun ejecutar(): Result<Unit> {
        val portador = try {
            exportarPortador()
        } catch (e: Exception) {
            throw ContactExchangeException(
                CODE_EXPORT_FAILED,
                "no se pudo exportar el portador propio: ${e.message}",
            )
        }
        if (portador.isEmpty()) {
            throw ContactExchangeException(CODE_EXPORT_FAILED, "el portador propio salio vacio")
        }

        var reenvios = 0
        while (true) {
            when (val veredicto = enviarPortador(portador)) {
                is SignalOutcome.Failed -> {
                    val reintentable =
                        veredicto.code == SignalDelivery.CODE_NOT_CONNECTED && reenvios < maxReenvios
                    if (reintentable) {
                        logger.log(
                            ContactLogLevel.WARN,
                            "[SIGNAL] intercambio sin sesion util codigo=${veredicto.code}: " +
                                "esperando re-auth hasta ${timeoutReauthMs}ms",
                        )
                        if (esperarReauth(timeoutReauthMs)) {
                            reenvios++
                            continue
                        }
                    }
                    logger.log(
                        ContactLogLevel.ERROR,
                        "[SIGNAL] intercambio NO entregado codigo=${veredicto.code} " +
                            "intentos=${veredicto.attempts} reenvios=$reenvios",
                    )
                    throw ContactExchangeException(
                        veredicto.code,
                        "el rele no acepto el intercambio de contactos: " +
                            (veredicto.detail ?: veredicto.code),
                    )
                }
                is SignalOutcome.Delivered -> {
                    logger.log(
                        ContactLogLevel.INFO,
                        "[SIGNAL] intercambio entregado intentos=${veredicto.attempts} " +
                            "bytes=${portador.size} reenvios=$reenvios",
                    )
                }
            }
            // El PREKEY SOLO aqui: la SIGNAL quedo Delivered. No hay otra salida
            // del bucle que llegue a esta linea.
            return abrirSesionYEntregar()
        }
    }

    companion object {
        /** El portador propio no se pudo exportar. */
        const val CODE_EXPORT_FAILED: String = "CONTACT_EXCHANGE_EXPORT_FAILED"

        /** Plazo total de la espera de re-auth, en milisegundos. */
        const val DEFAULT_TIMEOUT_REAUTH_MS: Long = 25_000L

        /** Reenvios extra: uno cubre la ventana de re-auth, dos son tormenta. */
        const val DEFAULT_MAX_REENVIOS: Int = 1
    }
}

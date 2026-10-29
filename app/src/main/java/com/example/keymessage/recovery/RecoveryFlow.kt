package com.example.keymessage.recovery

import com.km.mnemonic.Mnemonic
import com.km.mnemonic.MnemonicException
import com.km.mnemonic.MnemonicMotivo
import com.km.mnemonic.MnemonicPhrase
import com.km.model.IdentityId
import com.example.keymessage.identity.IdentityMaterialFactory
import com.example.keymessage.identity.IdentityStore

/**
 * 6.2 — Lo que devuelve [RecoveryFlow.validar].
 *
 * NO es una excepcion porque validar es la operacion que mas se hace —el usuario
 * teclea y teclea— y el fallo es un RESULTADO de la pantalla, no un fallo del
 * programa. Lo que si es un fallo del programa, que el recurso no este en el
 * classpath, sale como [MnemonicException].
 */
sealed interface Validacion {

    /** La frase es valida. [frase] queda en memoria de quien la pidio. */
    data class Aceptada(
        val frase: MnemonicPhrase,
        val identityId: IdentityId,
    ) : Validacion

    /** La frase no vale. [detalle] no lleva ninguna palabra de lo tecleado. */
    data class Rechazada(
        val motivo: MnemonicMotivo,
        val indice: Int?,
        val detalle: String,
    ) : Validacion
}

/**
 * 6.2 — El aviso que hay que pintar ANTES de pisar una identidad.
 *
 * [texto] lleva los dos identificadores a proposito: "tu identidad es esta,
 * la frase pegada es ESTA OTRA" es informacion que el usuario necesita para
 * decidir, y esconderla tras un "se va a perder" generico hace que confirme sin
 * saber que.
 */
data class AvisoSobrescritura(
    val idActual: IdentityId,
    val idRestaurado: IdentityId,
    val texto: String,
)

/** Lo que devuelve [RecoveryFlow.restaurar]. */
sealed interface ResultadoRestauracion {

    /** Escrito. */
    data class Restaurada(val identityId: IdentityId) : ResultadoRestauracion

    /** No se escribio nada: habia identidad local y nadie ha confirmado. */
    data class NecesitaConfirmacion(val aviso: AvisoSobrescritura) : ResultadoRestauracion

    /** No se escribio nada: la frase no valia. */
    data class Rechazada(val validacion: Validacion.Rechazada) : ResultadoRestauracion
}

/**
 * 6.2 — Restaurar una identidad desde la frase. Un coordinador, no una pantalla.
 *
 * ## QUE HACE ESTA CLASE Y QUE NO
 *
 * Decide CUANDO se escribe y que pasa despues. No sabe de Compose, ni de
 * `SharedPreferences`, ni de `ActivityResult`: por eso se prueba entero en un
 * test de JVM, que es donde se comprueba lo que de verdad da miedo —que cancelar
 * no escriba nada y que `save()` este DETRAS de la confirmacion.
 *
 * ## LA REGLA UNICA
 *
 * [IdentityStore.save] se llama desde UN sitio, [aplicar], y ese sitio esta
 * detras de la comprobacion de confirmacion. No hay un segundo camino: por eso
 * la mutacion de mover el `save()` por encima del `if` la detecta el test.
 *
 * ## LO QUE SE PIERDE Y LO QUE NO
 *
 * Al restaurar se pierde la identidad local y las sesiones con sus peers, que se
 * reabren con un re-handshake. NO se borran los contactos: son datos de otra
 * persona y la sesion se puede reabrir sin volver a escanear un QR.
 */
class RecoveryFlow(
    private val store: IdentityStore,
    private val factory: IdentityMaterialFactory = IdentityMaterialFactory(),
    private val ciclo: Ciclo,
) {

    /**
     * 6.2 — Lo que hay que hacer cuando cambia la identidad DE VERDAD.
     *
     * Las tres operaciones van en este orden y el orden importa:
     * `parar()` antes de escribir (si no, el socket sigue Firmando con la
     * identidad que se va a sustituir y el rele devuelve `AUTH_FAILED`),
     * regenerar las claves locales despues (el `deviceId` deriva de la clave de
     * firma de la identidad, asi que con la identidad nueva el `deviceId` viejo
     * seria la identidad de otro dispositivo) y `arrancar()` al final.
     */
    interface Ciclo {
        /** Cierra el nucleo y la pila de rele. */
        fun parar()

        /** Vuelve a levantar el nucleo con la identidad que hay ahora. */
        fun arrancar()

        /** Genera y guarda material local NUEVO para la identidad actual. */
        fun regenerarClavesLocales()
    }

    /** Hay identidad local que una restauracion pudiera pisar. */
    fun hayIdentidadLocal(): Boolean = store.load() != null

    /**
     * Valida el texto pegado. NO escribe nada, ni toca el almacen ni el ciclo.
     *
     * El checksum se comprueba aqui y no al escribir, para que el usuario vea el
     * error antes del aviso de sobrescritura: pedir "SOBRESCRIBIR" y que despues
     * falle el checksum seria la forma mas larga de perder la identidad.
     */
    fun validar(texto: String): Validacion {
        val frase = try {
            Mnemonic.parse(texto)
        } catch (e: MnemonicException) {
            return Validacion.Rechazada(e.motivo, e.indice, e.message ?: "frase no valida")
        }
        val identidad = factory.fromSeedEfimera(Mnemonic.toEntropy(frase))
        return Validacion.Aceptada(frase, identidad.identityId)
    }

    /**
     * El aviso de sobrescritura, o `null` si NO hay identidad local.
     *
     * Sin identidad local no hay nada que perder, y por eso el dialogo no se
     * pide: un boton que siempre dice "va a perder algo" acaba pulsandose sin
     * leerse, y ese es el aviso que de verdad evita que alguien pulse.
     */
    fun confirmarSobrescritura(
        idActual: IdentityId?,
        idRestaurado: IdentityId,
    ): AvisoSobrescritura? {
        val actual = idActual ?: return null
        return AvisoSobrescritura(
            idActual = actual,
            idRestaurado = idRestaurado,
            texto = buildString {
                append("TU IDENTIDAD AHORA ES\n")
                append("${actual.value}\n\n")
                append("LA FRASE PEGADA ES OTRA:\n")
                append("${idRestaurado.value}\n\n")
                append("QUIEN LA TENGA ACTUARA COMO TU Y ESTA IDENTIDAD ")
                append("DEJARA DE RESPONDER.\n")
                append("TUS CONTACTOS SE GUARDAN; LAS CONVERSACIONES SE ")
                append("REABREN A MANO.\n")
                append("LOS MENSAJES PASADOS NO SE RECUPERAN.")
            },
        )
    }

    /**
     * Escribe la identidad restaurada. O no escribe nada.
     *
     * @param validacion lo que salio de [validar].
     * @param confirmado `true` solo si el usuario ha pulsado SOBRESCRIBIR. Sin
     *   identidad local el parametro se ignora a proposito: no hay nada que
     *   confirmar porque no hay nada que perder.
     * @param idActual el `identityId` que hay ahora, o `null` si no hay
     *   identidad local. Lo recibe el llamante y no se lee del almacen porque
     *   [store] ya se ha consultado en [hayIdentidadLocal] y en la pantalla, y
     *   releerlo aqui abriria la puerta a que la pantalla y el store discrepen.
     */
    fun restaurar(
        validacion: Validacion,
        idActual: IdentityId?,
        confirmado: Boolean,
    ): ResultadoRestauracion {
        val aceptada = validacion as? Validacion.Aceptada
            ?: return ResultadoRestauracion.Rechazada(
                validacion as? Validacion.Rechazada
                    ?: Validacion.Rechazada(
                        motivo = MnemonicMotivo.WORDLIST,
                        indice = null,
                        detalle = "validacion que no es ni aceptada ni rechazada",
                    ),
            )
        if (idActual != null && !confirmado) {
            val aviso = confirmarSobrescritura(idActual, aceptada.identityId)
                ?: return ResultadoRestauracion.Rechazada(
                    Validacion.Rechazada(
                        motivo = MnemonicMotivo.CHECKSUM,
                        indice = null,
                        detalle = "no se puede construir el aviso de sobrescritura",
                    ),
                )
            return ResultadoRestauracion.NecesitaConfirmacion(aviso)
        }
        aplicar(aceptada.frase)
        return ResultadoRestauracion.Restaurada(aceptada.identityId)
    }

    /**
     * EL UNICO sitio del flujo que escribe.
     *
     * Vive en su propio metodo a proposito: [restaurar] decide, esta linea
     * ejecuta, y la separacion hace que "no se escribe sin confirmar" sea una
     * propiedad del metodo que decide y no una costumbre de quien programa
     * dentro.
     */
    private fun aplicar(frase: MnemonicPhrase) {
        // Los bytes de la semilla salen del framework, se derivan y se destruyen
        // dentro de `fromSeedEfimera`. `save()` escribe con `commit()` sincrono.
        val identidad = factory.fromSeedEfimera(Mnemonic.toEntropy(frase))
        ciclo.parar()
        store.save(identidad)
        store.setPhraseAcknowledged(false)
        ciclo.regenerarClavesLocales()
        ciclo.arrancar()
    }
}
package com.km.transmit

/**
 * 3Q.5.2b-B — El ALMACEN de la unidad de transmision, como INTERFAZ.
 *
 * ## POR QUE UNA INTERFAZ Y NO UN MEDIO
 *
 * §8.1-G de la spec deja el medio fisico FUERA de 3Q.5.2b: lo pone en
 * 3Q.5.3+. Lo que este checkpoint fija es el CONTRATO que cualquier medio
 * tendra que cumplir, y ese contrato no es "escribir bytes": es la separacion
 * entre **escribir**, **verificar** y **confirmar**.
 *
 * Por eso la interfaz tiene TRES operaciones de escritura y no una, y por eso
 * ninguna de ellas devuelve el estado logico de transmision. Un medio que
 * marcase la unidad al terminarla —que es lo que haria un `write()` normal de
 * fichero— no podria cumplir este contrato: entre "escrito" y "confirmado"
 * hay una ventana en la que los bytes estan en el medio pero todavia no se ha
 * comprobado que sean integros, y en esa ventana el estado logico de la
 * sesion ya no debe haber avanzado.
 *
 * ## LA SECUENCIA QUE ESTA INTERFAZ HACE POSIBLE, Y POR QUE ES ESTA
 *
 * ```
 *  writeAhead(bytes)     el medio tiene la unidad, todavia nadie lo cree
 *  readBack()            se relee lo que HAY, no lo que se queria escribir
 *  commit()              solo ahora la unidad es verdad
 *  discardPending()      el fallo se retira
 * ```
 *
 * `readBack()` existe, y es la mas importante, porque **escribir no es
 * comprobar**. Un medio puede truncar, un sistema de ficheros puede escribir
 * solo el primer sector, y un almacenamiento remoto puede devolver exito y
 * guardar otra cosa. Si el estado logico avanza con el exito de la escritura,
 * un byte a medias deja la sesion con estado de dos historias: el ratchet ya
 * avanzo y el envio ya no se puede reconstruir. Por eso
 * [SecureTransmitJournal] relee y compara ANTES de confirmar, y por eso
 * [readCommitted] esta separada de [readBack]: confirmar no es "dejar de
 * escribir", es una segunda operacion sobre el medio.
 *
 * ## POR QUE NO HAY `flush()` NI `fsync()`
 *
 * Porque el medio no es de este checkpoint. Un medio durable dira "cuando
 * [commit] vuelva, los bytes sobreviven a un corte"; uno que no, dira otra
 * cosa. Fijar aqui la semantica de durabilidad seria inventar el contrato del
 * 3Q.5.3+ sin su medio, y esta interfaz solo promete lo que TODOS los medios
 * pueden cumplir.
 */
interface TransmitUnitStore {

    /**
     * WRITE-AHEAD. Escribe la unidad COMPLETA y la deja pendiente de
     * confirmar.
     *
     * Se llama con los bytes ya cerrados por el codec: la unidad llega
     * terminada, con su checksum, y este metodo no la interpreta. Un medio que
     * escribiera solo una parte y aun asi devolviera con exito habria roto el
     * contrato, y por eso existe [readBack].
     *
     * @throws TransmitStoreFailure si el medio no puede aceptar la escritura.
     */
    fun writeAhead(bytes: ByteArray)

    /**
     * Lo que hay en el medio AHORA MISMO, pendiente o confirmado.
     *
     * Es la lectura que usa la verificacion, y por eso devuelve tambien lo no
     * confirmado: la verificacion tiene que poder comprobar una escritura que
     * todavia no se ha promovido.
     *
     * @return `null` si no hay nada escrito.
     * @throws TransmitStoreFailure si el medio no puede leer.
     */
    fun readBack(): ByteArray?

    /**
     * Lo CONFIRMADO, y nada mas.
     *
     * Es la unica lectura que usa la recuperacion tras un corte. Una unidad
     * pendiente sin confirmar es, por definicion, una unidad en la que nadie
     * confiaba todavia: leerla seria fabricar estado.
     *
     * @return `null` si no hay nada confirmado.
     */
    fun readCommitted(): ByteArray?

    /**
     * PROMUEVE lo pendiente a confirmado.
     *
     * @throws TransmitStoreFailure si el medio no puede confirmar.
     */
    fun commit()

    /** RETIRA lo pendiente sin confirmar. Nunca toca lo confirmado. */
    fun discardPending()

    /** `true` si hay una unidad pendiente de confirmar. */
    fun hasPending(): Boolean
}

/**
 * En que operacion fallo el medio.
 *
 * No es decorativo: quien reintenta un envio necesita saber si puede
 * reintentarlo entero (fallo de lectura, la unidad sigue ahi) o si tiene que
 * volver a construirlo (fallo de escritura).
 */
enum class TransmitStage {
    /** [TransmitUnitStore.writeAhead]. */
    WRITE_AHEAD,

    /** [TransmitUnitStore.readBack]. */
    READ_BACK,

    /** [TransmitUnitStore.commit]. */
    COMMIT,

    /** [TransmitUnitStore.discardPending]. */
    DISCARD,
}

/**
 * El medio fallo.
 *
 * Envuelve la excepcion del medio para que quien llama no tenga que conocer
 * ni la libreria ni el sistema de ficheros de 3Q.5.3+. El error tipado del
 * FORMATO ([com.km.unit.RetentionBudgetExceeded],
 * [com.km.unit.Km52FormatException]) NO pasa por aqui: son dos
 * naturalezas distintas y una transicion de estado, la otra una unidad
 * irrepresentable.
 */
class TransmitStoreFailure(
    val stage: TransmitStage,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Lo persistido NO es la unidad que se iba a persistir.
 *
 * ## POR QUE ES UN ERROR PROPIO Y NO UN `IllegalStateException`
 *
 * Es la condicion que hace existir la etapa de verificacion, y su respuesta
 * es una DECISION —confirmar o no confirmar— que la capa de arriba ejecuta.
 * Si se manifestara como una excepcion generica, un `catch (e: Exception)`
 * en el camino de envio la trataria como un fallo mas y confirmaria. Con un
 * tipo propio, "no se pudo verificar lo que se escribio" no se confunde con
 * "el medio no esta disponible".
 */
class PersistedUnitMismatch(val motivo: String) :
    RuntimeException("lo persistido no es la unidad escrita: $motivo")

/**
 * No hay ninguna unidad confirmada que recuperar.
 *
 * No es un fallo: es el estado normal de un dispositivo que todavia no ha
 * escrito nada, o que escribio y perdio antes de confirmar. Por eso es un
 * tipo y no una excepcion con mensaje: quien recupera tiene que poder
 * distinguir "no hay nada" de "hay algo y esta roto".
 */
class NothingPersisted :
    RuntimeException("no hay ninguna unidad de transmision confirmada en el almacen")

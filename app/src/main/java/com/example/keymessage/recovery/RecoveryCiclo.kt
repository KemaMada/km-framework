package com.example.keymessage.recovery

/**
 * El `Ciclo` REAL de la app, fuera del contenedor de Android.
 *
 * ## POR QUE ESTA CLASE EXISTE
 *
 * Antes de esto, `AppContainer` construia el ciclo con un
 * `object : RecoveryFlow.Ciclo { override fun parar() { parar() } ... }`, y el
 * `parar()` de DENTRO resolvia al `override` —no al `AppContainer.parar()` de
 * fuera— porque el miembro de la clase anonima tiene prioridad sobre el receptor
 * externo. [RecoveryFlow] llama `ciclo.parar()` SIEMPRE antes de escribir, de
 * modo que restaurar una identidad llenaba la pila de recusion y mataba el
 * proceso con `StackOverflowError`. No corrompia datos: `parar()` va antes de
 * `save()`, asi que el almacen quedaba entero, pero la app se caia de todos
 * modos. Evidencia: ADB v5, obs #285.
 *
 * ## POR QUE AQUI YA NO PUEDE PASAR
 *
 * Los tres delegados se llaman `detenerNucleo`, `levantarNucleo` y
 * `regenerarClaves`: NINGUNO coincide con el nombre de un metodo de
 * [RecoveryFlow.Ciclo], asi que un override no puede ensombrecerlos por
 * construccion. La correccion no depende de que alguien se acuerde de escribir
 * `this@Contenedor.parar()`, que es exactamente el detalle que se perdio la vez
 * que se escribio el buggy.
 *
 * ## LO QUE ESTA FUERA Y POR QUE
 *
 * `Application`, `Room` y `EncryptedSharedPreferences` viven en
 * `AppContainer`, y por eso esta clase solo sabe de tres acciones. La ventaja es
 * que el ciclo de produccion se ejercita ENTERO en un test de JVM, con la clase
 * de verdad y no con un doble que se le parezca: un doble que implementa
 * [RecoveryFlow.Ciclo] pasa por alto justo el fallo que la clase anonymous
 * escondia, que es que el override no llamase a nadie.
 */
class RecoveryCiclo(
    /** Cierra el nucleo y suelta la pila de rele. */
    private val detenerNucleo: () -> Unit,
    /** Vuelve a levantar el nucleo con la identidad que hay ahora. */
    private val levantarNucleo: () -> Unit,
    /** Genera y guarda material local NUEVO para la identidad actual. */
    private val regenerarClaves: () -> Unit,
) : RecoveryFlow.Ciclo {

    override fun parar() {
        detenerNucleo()
    }

    override fun arrancar() {
        levantarNucleo()
    }

    override fun regenerarClavesLocales() {
        regenerarClaves()
    }
}
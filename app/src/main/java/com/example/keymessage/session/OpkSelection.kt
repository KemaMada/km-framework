package com.example.keymessage.session

import com.km.identity.VerifiedContactBundle

/**
 * Elige el indice de one-time prekey con el que INICIAR una sesion.
 *
 * ## DONDE VIVE AHORA ESTA DECISION
 *
 * En [VerifiedContactBundle.initiatorOneTimePrekeyIndex]. El dato del que
 * depende —cuantos OPK trae el bundle— lo conoce el propio bundle, y tenerlo en
 * una clase de composicion aparte era una segunda copia de la misma regla. Aqui
 * queda la API que ya usa [com.example.keymessage.AppContainer].
 *
 * ## LA CONTRADICCION QUE RESUELVE
 *
 * `BootstrapPrekeys.fromVerified` de km-core LANZA si se le pide un OPK que el
 * bundle no lleva (`0` sobre un bundle con 0 OPK) y exige `null` para una sesion
 * sin `DH4`. El QR de la app exporta con 0 OPK, asi que todo bundle importado
 * por QR lleva 0: pedir `0` por defecto hacia la sesion E2E IMPOSIBLE.
 */
object OpkSelection {

    /**
     * Indice de OPK a pedir para [bundle]: `0` si lleva alguno, `null` si no.
     *
     * `null` no es "niego el OPK": es la peticion explicita de una sesion sin
     * `DH4`, que es lo unico que km-core acepta para un bundle sin OPK.
     */
    fun paraIniciar(bundle: VerifiedContactBundle): Int? = bundle.initiatorOneTimePrekeyIndex
}

package com.example.keymessage.contact

import com.km.model.IdentityId
import com.km.transport.SignalOutcome

/**
 * La decision de RE-ANUNCIAR, separada del contenedor para poder probarla.
 *
 * ## EL FALLO QUE ARREGLA
 *
 * `AppContainer.abrirSesionYEntregar` hacia `return` temprano cuando ya habia
 * una sesion con el par. Eso es correcto en el camino NORMAL (pulsar un
 * contacto) pero es EXACTAMENTE el fallo cuando el usuario vuelve a escanear un
 * QR o a importar un `.km`: el escritorio reinicio, perdio contactos y sesiones,
 * y la app —que conserva su sesion vieja en memoria— no publicaba su bundle, no
 * mandaba PREKEY y el mensaje salia con un ratchet que el otro ya no tiene. El
 * escritorio descartaba el frame sin log y no habia ACK.
 *
 * ## LA REGLA
 *
 * - **Import/escaneo EXPLICITO**: siempre bundle + PREKEY. Re-keying es la
 *   intencion del usuario; que la sesion exista de este lado no prueba que el
 *   otro la conserve.
 * - **Camino normal**: si hay sesion, no se toca. Reiniciar sin motivo crearia
 *   una sesion nueva aqui mientras el otro conserva la anterior, y los ratchets
 *   divergen.
 *
 * ## POR QUE UNA CLASE Y NO UN `if` EN EL CONTENEDOR
 *
 * `AppContainer` necesita un `Application` y no se instancia en un test de JVM
 * (`Settings.Global`, Room, `EncryptedSharedPreferences`). La secuencia
 * (`ContactBootstrap`) y la decision viven aqui, con las tres operaciones
 * inyectadas, y el test mide lo que importa: que un import explicito EMITE y
 * que un arranque normal NO.
 */
class EntregaDePar(
    /** Si este lado ya tiene una sesion E2E con el par. */
    private val sesionInstalada: (IdentityId) -> Boolean,
    /** El portador propio, en los bytes que viajan. */
    private val exportarPortador: () -> ByteArray,
    /** `SIGNAL/contact_exchange` del portador. */
    private val enviarPortador: (IdentityId, ByteArray) -> SignalOutcome,
    /** Abrir la sesion Y entregar su frame PREKEY por el canal de par. */
    private val abrirSesionYEntregar: () -> Result<Unit>,
    /** Espera la re-auth antes del UNICO reenvio del bootstrap. */
    private val esperarReauth: suspend (timeoutMs: Long) -> Boolean = { false },
    private val timeoutReauthMs: Long = ContactBootstrap.DEFAULT_TIMEOUT_REAUTH_MS,
    private val maxReenvios: Int = ContactBootstrap.DEFAULT_MAX_REENVIOS,
) {

    /**
     * Ejecuta la secuencia contra [peer].
     *
     * @param explicito `true` solo cuando el usuario acaba de escanear/importar.
     *   Un arranque normal, abrir una conversacion o pulsar un contacto pasan
     *   `false`.
     * @return exito tambien cuando se OMITE por sesion ya instalada: no hacer
     *   nada es el resultado correcto de ese caso.
     */
    suspend fun ejecutar(peer: IdentityId, explicito: Boolean): Result<Unit> {
        if (!explicito && sesionInstalada(peer)) return Result.success(Unit)
        val bootstrap = ContactBootstrap(
            exportarPortador = exportarPortador,
            enviarPortador = { portador -> enviarPortador(peer, portador) },
            abrirSesionYEntregar = abrirSesionYEntregar,
            esperarReauth = esperarReauth,
            timeoutReauthMs = timeoutReauthMs,
            maxReenvios = maxReenvios,
        )
        return bootstrap.ejecutar()
    }
}

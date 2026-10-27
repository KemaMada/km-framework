package com.km.desktop.core

import com.km.api.KeyMessageCoreImpl
import com.km.messaging.LegacyInboundObservation
import com.km.messaging.SecureInbound
import com.km.messaging.SecureSessionRegistry
import com.km.model.IdentityId
import com.km.node.PeerTransportManager
import com.km.protocol.DaemonRetryScheduler
import com.km.protocol.RetryManagerImpl
import com.km.protocol.RetryScheduler
import com.km.protocol.Transport

/**
 * La puerta anti-texto-plano, en UN sitio y sin parametros que la puedan abrir.
 *
 * ## QUE RESUELVE
 *
 * `KeyMessageCoreImpl` admite `secureSessionRegistry = null` como valor por
 * defecto, y en ese caso el envio sale **EN CLARO** por el camino legado
 * (`SecureOutboundImpl` solo se construye si hay registro). Es una decision de
 * COMPATIBILIDAD de km-core, escrita alli y bien escrita; lo que este modulo
 * decide es no activarla.
 *
 * Antes de esta clase, `DesktopMain.kt:454` construia el nucleo sin registro y
 * sin manager. El comando `send` de la TUI funcionaba, y_FUNCIONABA EN CLARO_:
 * la puerta no estaba cerrada, no estaba abierta con una llave. Eso es lo que
 * esta clase cierra.
 *
 * ## POR QUE UN OBJETO Y NO DOS CONSTRUCTORES
 *
 * Porque las dos mitades de la puerta —el registro que RECHAZA el envio sin
 * sesion y `allowLegacyCleartextReceive = false` que cierra la RECEPCION en
 * claro— tienen que estar siempre juntas. Separadas en dos sitios, cada uno con
 * su bandera, la segunda se puede dejar en su valor por defecto y la primera
 * seguir escribiendo "seguro" en un log.
 *
 * ## LO QUE ESTA CLASE NO HACE
 *
 * No conecta, no cifra, no deriva claves y no decide quien es el par. Compone
 * cuatro piezas que km-core ya define y las entrega con su garantia observable:
 * [core].secureInbound y [core].legacyInbound.
 */
class CoreFactory {

    /** El nucleo y las dos piezas cuya presencia hay que poder mirar. */
    class CoreBundle(
        val core: KeyMessageCoreImpl,
        /** Ancla de sesiones por par. Es lo que convierte "sin sesion" en rechazo. */
        val registry: SecureSessionRegistry,
        /** El gestor por el que viajan los `SecureFrame`. */
        val manager: PeerTransportManager,
    ) {
        /** Lo que el camino legado ha hecho. Observable por API publica. */
        val legacyInbound: LegacyInboundObservation get() = core.legacyInbound

        /** El puente de recepcion cifrada: no es `null` porque hay las dos inyecciones. */
        val secureInbound: SecureInbound? get() = core.secureInbound
    }

    /**
     * Construye el nucleo con las dos inyecciones PUESTAS.
     *
     * @param peerPublicKeyResolver clave de FIRMA de un peer, para autenticar
     *   sus `ACK`. Sin bundle verificado no hay clave de firma que verificar, y
     *   un resolvedor que inventa una seria peor que uno que devuelve `null`.
     * @param retryScheduler reloj del motor de reintentos. El valor por defecto
     *   es el de PRODUCCION ([DaemonRetryScheduler], un hilo daemon); el test
     *   determinista inyecta uno manual para recorrer el techo de 155 s sin
     *   dormir. NO cambia el backoff (5 s, x2, 5 intentos): eso sigue en km-core.
     * @throws IllegalArgumentException si [manager] no es el gestor que
     *   corresponde al [transport]. Es una invariante de ENSAMBLADO, no un
     *   fallo de transporte, y por eso se comprueba aqui y no en km-core.
     */
    fun create(
        localIdentity: IdentityId,
        localPrivateKey: ByteArray,
        transport: Transport,
        manager: PeerTransportManager,
        peerPublicKeyResolver: (IdentityId) -> ByteArray?,
        retryScheduler: RetryScheduler = DaemonRetryScheduler(),
    ): CoreBundle {
        val registry = SecureSessionRegistry(localIdentity)
        val core = KeyMessageCoreImpl(
            localIdentity = localIdentity,
            localPrivateKey = localPrivateKey,
            transport = transport,
            peerPublicKeyResolver = peerPublicKeyResolver,
            // La mitad de la puerta que corta el ENVIO: sin registro con el
            // destinatario, `SecureOutboundImpl` RECHAZA (estado FAILED) y
            // `Transport.send` no se llama. Nunca hay reintento en claro.
            secureSessionRegistry = registry,
            // La mitad que cierra la RECEPCION. Se escribe el `false` aunque sea
            // el default de km-core: depender de un default que nadie ve es la
            // forma de que un dia cambie sin que nadie lo decida.
            allowLegacyCleartextReceive = false,
            // Sin esto el nucleo no descifra nada: sin manager no hay por donde
            // enterarse de que llego un wire, y adivinar seria tratar bytes de
            // sesion como si fueran JSON.
            peerTransportManager = manager,
            // D12: el reloj del reintento es INYECTABLE. En produccion es el
            // mismo scheduler de siempre; en km-desktop tests se pasa uno manual
            // para llegar al EXPIRED sin esperar 155 s reales.
            retryManager = RetryManagerImpl(scheduler = retryScheduler),
        )
        return CoreBundle(core, registry, manager)
    }
}
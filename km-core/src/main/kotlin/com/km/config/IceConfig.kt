package com.km.config

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Servidores ICE (STUN/TURN) del tier P2P, cargados SOLO del entorno.
 *
 * ## Por que NO es una `data class`
 *
 * [toString] es la via mas comun por la que una credencial acaba en un log, en
 * un informe de diagnostico o en un export. El `toString` sintetico de una
 * `data class` imprime TODOS los campos, incluido el password de TURN, y basta
 * con que alguien interpole el objeto en un mensaje para filtrarlo. Aqui
 * [toString] se escribe a mano y redacta las credenciales; una `data class` no
 * permitiria garantizarlo: `copy`/`equals`/`componentN` se regeneran y el
 * contrato de redaccion se rompe en cuanto alguien cambia un campo.
 *
 * ## De donde salen los valores
 *
 * Solo de variables de entorno ([fromEnv]). No hay fichero de secretos, ni
 * valores por defecto, ni URLs literales en el codigo. La rotacion de una
 * credencial es cambiar el entorno y reiniciar, sin recompilar.
 *
 * ## Que se valida y que se rechaza
 *
 * - Esquemas admitidos: `stun:`, `stuns:`, `turn:`, `turns:`.
 * - Cualquier otra cosa —incluido `http(s)://`— es un error de configuracion y
 *   [of]/[fromEnv] lanzan [IllegalArgumentException]. Un `http:` aqui no es un
 *   typo inofensivo: seria mandar credenciales de TURN en claro.
 * - `turn` sin credenciales (o al reves) se rechaza: media configuracion de
 *   autenticacion produce un fallo de ICE que parece un problema de NAT.
 *
 * La clase NO conoce WebRTC: expone datos, y `km-webrtc` los traduce a
 * `RTCIceServer`. Asi `km-core` sigue sin dependencias del medio.
 *
 * ## Credenciales efimeras (TURN REST)
 *
 * Con `KM_ICE_TURN_SECRET`, [of]/[fromEnv] NO usan el par usuario/credencial
 * estatico: derivan la credencial del secreto compartido con el servidor, como
 * el mecanismo REST de coturn:
 *
 * ```
 * username   = "<expiryUnix>:<etiqueta>"
 * credential = base64(HMAC-SHA1(secret, username))
 * ```
 *
 * El secreto NUNCA se guarda en la instancia ni aparece en [toString]: solo se
 * usa para calcular la credencial. La ventana ([DEFAULT_TTL_SECONDS]) acota lo
 * que vale una credencial filtrada, y rotar el secreto invalida todas las
 * anteriores. `javax.crypto` es local: no hay red ni dependencia nueva.
 */
class IceConfig private constructor(
    /** URLs `stun:`/`stuns:`, en el orden declarado y sin duplicados. */
    val stunUrls: List<String>,
    /** URLs `turn:`/`turns:`, en el orden declarado y sin duplicados. */
    val turnUrls: List<String>,
    /** Usuario de TURN, o `null` si no hay credenciales configuradas. */
    val turnUsername: String?,
    /** Credencial de TURN, o `null`. NUNCA aparece en [toString]. */
    val turnCredential: String?,
) {

    /** Todas las URLs ICE, STUN primero y TURN despues. */
    val urls: List<String> get() = stunUrls + turnUrls

    /** Si hay al menos una URL ICE: la condicion para intentar P2P en `auto`. */
    val isConfigured: Boolean get() = urls.isNotEmpty()

    /** Si hay un relay (TURN) configurado y UTILIZABLE. Sin el, `auto` no intenta P2P. */
    val hasRelayServer: Boolean get() = turnUrls.isNotEmpty() && turnCredential != null

    /**
     * Si la lista esta vacia y la conexion sera host-only.
     *
     * Host-only NO significa "sin red": significa que libwebrtc solo usara
     * candidatos locales del host. Se declara para que un operador no confunda
     * "no hay ICE configurado" con "el NAT no dejo pasar".
     */
    val isHostOnly: Boolean get() = urls.isEmpty()

    /** URLs sin credenciales ni userinfo, aptas para diagnostico y badge. */
    fun redactedUrls(): List<String> = urls.map(::redact)

    /**
     * Representacion SEGURA: esquema y autoridad, cero credenciales.
     *
     * El usuario de TURN tambien se redacta. No es un secreto por si mismo,
     * pero identifica la cuenta y suele ir apareado con la credencial; un log
     * que lo publique permite correlacionar intentos de abuso.
     */
    override fun toString(): String =
        "IceConfig(urls=${redactedUrls()}, turnUsername=${if (turnUsername == null) "<none>" else "<redacted>"}, " +
            "turnCredential=${if (turnCredential == null) "<none>" else "<redacted>"}, hostOnly=$isHostOnly)"

    override fun equals(other: Any?): Boolean =
        other is IceConfig &&
            stunUrls == other.stunUrls &&
            turnUrls == other.turnUrls &&
            turnUsername == other.turnUsername &&
            turnCredential == other.turnCredential

    override fun hashCode(): Int {
        var result = stunUrls.hashCode()
        result = 31 * result + turnUrls.hashCode()
        result = 31 * result + (turnUsername?.hashCode() ?: 0)
        result = 31 * result + (turnCredential?.hashCode() ?: 0)
        return result
    }

    companion object {
        const val ENV_STUN_URLS: String = "KM_ICE_STUN_URLS"
        const val ENV_TURN_URLS: String = "KM_ICE_TURN_URLS"
        const val ENV_TURN_USERNAME: String = "KM_ICE_TURN_USERNAME"
        const val ENV_TURN_CREDENTIAL: String = "KM_ICE_TURN_CREDENTIAL"

        /**
         * Secreto compartido con coturn (`static-auth-secret`).
         *
         * Cuando esta, se ignora la credencial estatica y se deriva una
         * efimera. Es SECRETO: no se registra ni se guarda en la instancia.
         */
        const val ENV_TURN_SECRET: String = "KM_ICE_TURN_SECRET"

        /** Vida de la credencial efimera, en segundos. Default: 24 h. */
        const val ENV_TURN_TTL_SECONDS: String = "KM_ICE_TURN_TTL_SECONDS"

        /** 24 h: suficiente para una jornada, corto para una credencial filtrada. */
        const val DEFAULT_TTL_SECONDS: Long = 86_400L

        private val STUN_SCHEMES = setOf("stun", "stuns")
        private val TURN_SCHEMES = setOf("turn", "turns")

        /** Configuracion sin ICE: host-only, el default seguro. */
        fun hostOnly(): IceConfig = IceConfig(emptyList(), emptyList(), null, null)

        /**
         * Carga la configuracion del entorno.
         *
         * @param env fuente de variables de entorno. Inyectable para tests.
         * @throws IllegalArgumentException si alguna URL no es valida.
         */
        fun fromEnv(env: (String) -> String? = { name -> System.getenv(name) }): IceConfig =
            fromEnv(env) { System.currentTimeMillis() }

        /**
         * Igual, con reloj INYECTADO, para fijar el vencimiento de una
         * credencial efimera sin depender de la hora real.
         *
         * `internal` a proposito: la API publica no necesita un reloj, y un
         * parametro mas en [fromEnv] haria que una trailing lambda se ligara al
         * reloj en vez de al entorno.
         */
        internal fun fromEnv(env: (String) -> String?, now: () -> Long): IceConfig {
            val stun = parseUrls(env(ENV_STUN_URLS))
            val turn = parseUrls(env(ENV_TURN_URLS))
            val username = env(ENV_TURN_USERNAME)?.trim()?.takeIf { it.isNotEmpty() }
            val credential = env(ENV_TURN_CREDENTIAL)?.trim()?.takeIf { it.isNotEmpty() }
            val secret = env(ENV_TURN_SECRET)?.trim()?.takeIf { it.isNotEmpty() }
            val ttl = env(ENV_TURN_TTL_SECONDS)?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()
                ?: DEFAULT_TTL_SECONDS
            return of(stun, turn, username, credential, secret, ttl, now)
        }

        /**
         * Construye y valida una configuracion explicita.
         *
         * Es la unica fabrica ademas de [fromEnv]; el constructor es privado
         * para que ninguna ruta pueda saltarse la validacion.
         *
         * @param turnSecret si no es `null`, la credencial se DERIVA y
         *   [turnCredential] no debe venir: dos fuentes de autenticacion
         *   distintas en el mismo cliente es una configuracion ambigua.
         */
        fun of(
            stunUrls: List<String> = emptyList(),
            turnUrls: List<String> = emptyList(),
            turnUsername: String? = null,
            turnCredential: String? = null,
            turnSecret: String? = null,
            ttlSeconds: Long = DEFAULT_TTL_SECONDS,
            now: () -> Long = System::currentTimeMillis,
        ): IceConfig {
            val stun = normalize(stunUrls, turnOnly = false)
            val turn = normalize(turnUrls, turnOnly = true)
            require((turnUsername == null) == (turnCredential == null) || turnSecret != null) {
                "TURN necesita usuario y credencial JUNTOS; llego solo uno de los dos"
            }
            val (usuario, credencial) = if (turnSecret != null) {
                require(turnUsername != null) {
                    "KM_ICE_TURN_SECRET necesita KM_ICE_TURN_USERNAME como etiqueta del usuario efimero"
                }
                require(turnCredential == null) {
                    "KM_ICE_TURN_SECRET y KM_ICE_TURN_CREDENTIAL son excluyentes: elige una"
                }
                require(ttlSeconds > 0) { "la vida de la credencial TURN debe ser positiva: $ttlSeconds" }
                val name = ephemeralUsername(turnUsername, now(), ttlSeconds)
                name to restCredential(turnSecret, name)
            } else {
                turnUsername to turnCredential
            }
            return IceConfig(stun, turn, usuario, credencial)
        }

        /**
         * `"<expiryUnix>:<label>"`, el formato que espera coturn en modo REST.
         *
         * `internal` para poder fijarlo en tests con un reloj; no forma parte
         * de la API publica.
         */
        internal fun ephemeralUsername(label: String, nowMillis: Long, ttlSeconds: Long): String =
            "${nowMillis / 1_000L + ttlSeconds}:$label"

        /**
         * `base64(HMAC-SHA1(secret, username))`, sin relleno URL-safe.
         *
         * coturn compara la credencial tal cual llega en el mensaje STUN: es
         * base64 ESTANDAR (con `+`, `/` y `=`), no base64url.
         */
        internal fun restCredential(secret: String, username: String): String {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA1"))
            return Base64.getEncoder().encodeToString(mac.doFinal(username.toByteArray(StandardCharsets.UTF_8)))
        }

        /** Lista separada por comas -> URLs sin vacios. La validacion la hace [normalize]. */
        private fun parseUrls(raw: String?): List<String> =
            raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

        private fun normalize(values: List<String>, turnOnly: Boolean): List<String> =
            values.map { it.trim() }.filter { it.isNotEmpty() }
                .also { it.forEach { url -> requireScheme(url, turnOnly) } }
                .distinct()

        /**
         * Comprueba el esquema y la autoridad.
         *
         * Se escribe aqui y no con `java.net.URI` porque `URI` acepta cosas que
         * aqui no valen (`turn:host` sin `//`, userinfo en claro) y porque el
         * mensaje de rechazo tiene que decir el esquema observado.
         */
        private fun requireScheme(url: String, turnOnly: Boolean) {
            val parts = split(url)
            require(parts != null) { "URL ICE sin esquema: '$url'" }
            val (scheme, authority) = parts
            val allowed = if (turnOnly) TURN_SCHEMES else STUN_SCHEMES
            require(scheme in allowed) {
                "esquema ICE no admitido en '$url': se esperaba ${allowed.joinToString("/")}"
            }
            val host = authority.substringAfterLast('@').substringBefore(':')
            require(host.isNotEmpty()) { "URL ICE sin host: '$url'" }
        }

        /**
         * `scheme` + autoridad de una URL ICE.
         *
         * Se aceptan las dos formas que circulan en el ecosistema —`stun:host`
         * (RFC 7064/7065, la que usa libwebrtc) y `stun://host`— y se
         * normaliza la autoridad igual en ambos casos. Un parser estricto de
         * `://` rechazaria las URLs que el propio medio acepta.
         */
        private fun split(url: String): Pair<String, String>? {
            val colon = url.indexOf(':')
            if (colon <= 0) return null
            val scheme = url.substring(0, colon).lowercase()
            var rest = url.substring(colon + 1)
            if (rest.startsWith("//")) rest = rest.substring(2)
            val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
            if (authority.isEmpty()) return null
            return scheme to authority
        }

        /**
         * `scheme:host[:port]`, sin userinfo, path, query ni fragmento.
         *
         * Una URL TURN puede llevar `usuario:clave@host`; el `@` y lo que le
         * precede se descarta. Es la unica forma segura de enseñar la lista.
         */
        internal fun redact(url: String): String {
            val parts = split(url) ?: return "<invalid>"
            val (scheme, authority) = parts
            return "$scheme:${authority.substringAfterLast('@')}"
        }
    }
}

package com.example.keymessage.boot

import com.km.protocol.ConnectionState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R3 / R4 / R6 — El TEXTO del overlay de arranque: entorno real, fases reales,
 * "sin medicion" cuando no hay metrica, y ningun secreto ni porcentaje.
 *
 * ## POR QUE ESTAS AFIRMACIONES SON DE JVM
 *
 * `lineasBoot` es pura: recibe un [BootEnvironment] ya capturado, los hitos y el
 * estado del enlace. No hay `ComposeTestRule` en este modulo, asi que lo que se
 * pinta se cierra aqui; el comportamiento en dispositivo (2 s, tap, OMITIR) se
 * confirma por ADB y se reporta en `apply-progress`.
 *
 * ## CONTROLES NEGATIVOS
 *
 * Los detectores ([porcentajesFalsos], [endpointsInventados], [secretosEn]) se
 * alimentan con muestras MALAS: si no las ven, el guard pasaria por vacio.
 */
class LineasBootTest {

    private val env = BootEnvironment(
        api = 28,
        release = "9",
        abi = "arm64-v8a",
        appVersion = "1.0",
        versionCode = 1L,
        endpoint = "wss://relay.real.example/ws",
        endpointSource = "RUNTIME_OVERRIDE",
    )

    private val hitos = listOf(
        HitoFase(FaseArranque.CONFIG, EstadoFase.HECHA, 0L, 12L, "origen=RUNTIME_OVERRIDE"),
        HitoFase(FaseArranque.IDENTIDAD, EstadoFase.HECHA, 0L, 30L, "cargada"),
        HitoFase(FaseArranque.NUCLEO, EstadoFase.HECHA, 30L, 80L),
        HitoFase(FaseArranque.RELAY, EstadoFase.FALLIDA, 80L, 190L, "sin conexion a ningun rele: 1"),
        HitoFase(FaseArranque.AUTH, EstadoFase.PENDIENTE),
        HitoFase(FaseArranque.CONTACTOS, EstadoFase.HECHA, 0L, 0L, "0 en memoria (no persistido)"),
    )

    private fun lineas(
        entorno: BootEnvironment = env,
        estado: ConnectionState = ConnectionState.DISCONNECTED,
        error: String? = "BOOTSTRAP_ALL_FAILED",
    ): List<String> = lineasBoot(entorno, hitos, estado, error)

    private fun List<String>.texto(): String = joinToString("\n")

    // ===================================================================
    // Control negativo primero: los detectores VEN lo malo
    // ===================================================================

    @Test
    fun `control negativo el detector VE un porcentaje y un endpoint inventado`() {
        val falso = listOf(
            "ARRANQUE 100%",
            "RELAY    ws://127.0.0.1:8080  origen=DEFAULT",
        ).texto()

        assertEquals(1, porcentajesFalsos(falso), "el detector tiene que ver el `%`: $falso")
        assertEquals(1, endpointsInventados(falso), "y el 127.0.0.1: $falso")
    }

    @Test
    fun `control negativo el detector VE un secreto en la traza`() {
        val falso = "IDENTIDAD  frase=abandon ability able about"

        assertTrue(
            secretosEn(falso).isNotEmpty(),
            "el detector tiene que ver el mnemonic: $falso",
        )
    }

    @Test
    fun `control negativo una linea bien formada NO dispara el detector`() {
        val bueno = "CONFIG  HECHA  12ms  (origen=RUNTIME_OVERRIDE)"

        assertEquals(0, porcentajesFalsos(bueno))
        assertEquals(0, endpointsInventados(bueno))
        assertEquals(emptyList<String>(), secretosEn(bueno))
    }

    // ===================================================================
    // R3 — entorno real
    // ===================================================================

    @Test
    fun `el entorno sale del dispositivo, de PackageManager y del endpoint configurado`() {
        val texto = lineas().texto()

        assertTrue(texto.contains("API 28"), "la API real del dispositivo: $texto")
        assertTrue(texto.contains("9"), "el release real: $texto")
        assertTrue(texto.contains("arm64-v8a"), "la ABI real: $texto")
        assertTrue(texto.contains("1.0 (1)"), "versionName y versionCode de PackageManager: $texto")
        assertTrue(
            texto.contains("wss://relay.real.example/ws"),
            "el endpoint REALMENTE configurado: $texto",
        )
        assertTrue(texto.contains("origen=RUNTIME_OVERRIDE"), "y de donde salio: $texto")
    }

    @Test
    fun `sin endpoint configurado se dice sin configurar y NO se inventa una URL`() {
        val sinEndpoint = env.copy(
            endpoint = null,
            endpointSource = BootEnvironment.SIN_CONFIGURAR,
        )
        val texto = lineas(entorno = sinEndpoint).texto()

        assertTrue(texto.contains("RELAY    sin configurar"), "se dice tal cual: $texto")
        assertFalse(texto.contains("127.0.0.1"), "ni un loopback de desarrollo: $texto")
        assertFalse(texto.contains("wss://"), "ni una URL que nadie configuro: $texto")
        assertFalse(texto.contains("ws://"), "ni cleartext inventado: $texto")
    }

    // ===================================================================
    // R4 — fases desde eventos, "sin medicion", sin 100%
    // ===================================================================

    @Test
    fun `una fase sin metrica dice sin medicion y nunca un porcentaje`() {
        val texto = lineas().texto()
        val auth = lineas().single { it.startsWith(FaseArranque.AUTH.etiqueta) }

        // El LITERAL, no la constante: si alguien cambia la constante, R4 sigue
        // exigiendo esta cadena ("sin medicion", no el `-- ms` del diseno).
        assertEquals("sin medicion", LineasBoot.SIN_MEDICION, "el literal de R4")
        assertTrue(
            auth.contains("sin medicion"),
            "R4 exige el literal 'sin medicion': $auth",
        )
        assertEquals(0, porcentajesFalsos(texto), "ni un `%` en toda la traza: $texto")
        assertFalse(texto.contains("100%"), "ni el 100% fabricado: $texto")
    }

    @Test
    fun `una fase medida muestra su duracion real`() {
        val fases = lineas().drop(LineasBoot.LINEAS_ENTORNO)
        val config = fases.single { it.startsWith(FaseArranque.CONFIG.etiqueta) }
        val relay = fases.single { it.startsWith(FaseArranque.RELAY.etiqueta) }

        assertTrue(config.contains("12ms"), "0 -> 12 medido: $config")
        assertTrue(relay.contains("110ms"), "80 -> 190 medido: $relay")
        assertTrue(relay.contains("FALLO"), "y su estado real: $relay")
    }

    @Test
    fun `CONTACTOS es una medicion LOCAL y no espera al relay`() {
        val contactos = lineas().single { it.startsWith(FaseArranque.CONTACTOS.etiqueta) }

        assertTrue(contactos.contains("HECHA"), "la carga local ya termino: $contactos")
        assertTrue(
            contactos.contains("0 en memoria"),
            "0 es una medicion real, no una espera: $contactos",
        )
        // El relay esta caido y aun asi CONTACTOS esta HECHA: no depende de el.
        assertTrue(contactos.contains("no persistido"), "y se dice el alcance: $contactos")
    }

    @Test
    fun `el estado del enlace es el real y no una promesa`() {
        val caido = lineas(estado = ConnectionState.RECONNECTING, error = "BACKOFF").texto()
        val online = lineas(estado = ConnectionState.ONLINE, error = null).texto()

        assertTrue(caido.contains("ENLACE   RECONECTANDO"), "el estado real: $caido")
        assertTrue(caido.contains("BACKOFF"), "con su codigo real: $caido")
        assertTrue(online.contains("ENLACE   ONLINE"), "y ONLINE solo con ONLINE: $online")
    }

    // ===================================================================
    // R6 — sin secretos, forma del overlay
    // ===================================================================

    @Test
    fun `la traza no lleva secretos`() {
        val texto = lineas().texto()

        assertEquals(emptyList<String>(), secretosEn(texto), "ningun material de frase ni token: $texto")
    }

    @Test
    fun `la traza tiene entorno, una linea por fase y el enlace`() {
        val lineas = lineas()

        assertEquals(
            LineasBoot.LINEAS_ENTORNO + hitos.size + 1,
            lineas.size,
            "3 de entorno + ${hitos.size} fases + 1 de enlace: $lineas",
        )
    }

    // -------------------------------------------------------------------
    // Detectores (los mismos criterios que usan los tests de arriba)
    // -------------------------------------------------------------------

    private fun porcentajesFalsos(texto: String): Int =
        Regex("%").findAll(texto).count()

    private fun endpointsInventados(texto: String): Int =
        Regex("127\\.0\\.0\\.1|localhost").findAll(texto).count()

    private fun secretosEn(texto: String): List<String> =
        listOf("abandon", "ability", "mnemonic", "nemotecnico", "seed", "private key", "api_key", "api key")
            .filter { texto.lowercase().contains(it) }
}

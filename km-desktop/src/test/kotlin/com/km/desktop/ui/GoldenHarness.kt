package com.km.desktop.ui

import com.km.model.IdentityId
import com.km.model.MessageId
import com.km.model.MessageState
import java.io.File
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse

/**
 * El ARNES de los goldens de TEXTO de la TUI.
 *
 * ## BASELINE F2, NO PARIDAD F1
 *
 * Estos 21 ficheros son la BASELINE del rediseno F2 (bandas, pestanas, barra de
 * estado y pie): se capturaron DESPUES de M5/M6 y describen el aspecto actual,
 * no el anterior. La paridad con la pintura F1 se demostro en M2 con los md5
 * que dejo `apply-progress` (#463); el oraculo F1 (`ShellRendererLegacy` y su
 * test) se retiro en M6 por decision del design (#460, M6). Por eso R20 no se
 * puede reverificar hoy y estos goldens NO prueban F1: prueban "que no cambie
 * lo que ya hay en F2".
 *
 * ## POR QUE TEXTO PLANO Y NO BYTES DEL TERMINAL
 *
 * Porque lo que cambia en el rediseno es el LAYOUT, no la codificacion ANSI. Un
 * golden de texto plano dice, sin ruido: "esta fila mide estas celdas y dice
 * esto". El estilo (color, reverse) se prueba aparte con aserciones semanticas;
 * aqui no entra ni un `ESC` y el test lo comprueba.
 *
 * ## POR QUE TODO ES FIJO
 *
 * Un golden que depende de la maquina no es un golden. Los fixtures usan el
 * MISMO instante en milisegundos y [FIXED_CLOCK] formatea en UTC, de modo que
 * una fila con hora sale igual en Madrid y en Tokio. Un `System.currentTimeMillis`
 * aqui seria el bug que M0 cierra.
 *
 * ## REGENERAR ES EXPLICITO
 *
 * `-Dkm.golden.update=true` escribe los ficheros; sin el flag el test COMPARA y
 * falla. Un test que se reescribe solo no protege nada: convierte cualquier
 * regresion en "el golden nuevo".
 */
object GoldenHarness {

    /** Instante fijo de todos los fixtures: 2023-11-14T22:13:20Z. */
    const val FIXED_MS: Long = 1_700_000_000_000L

    /** UTC y el instante fijo: nada que ver con la zona del que ejecuta. */
    val FIXED_CLOCK: RenderClock = RenderClock(ZoneOffset.UTC, FIXED_MS)

    /** Los tres tamanos de la baseline F2 (antes: gate de paridad de R20). */
    val SIZES: List<Size> = listOf(Size(60, 20), Size(80, 24), Size(120, 40))

    private val yo = IdentityId("f".repeat(64))
    private val ana = IdentityId("a".repeat(64))
    private val pedro = IdentityId("b".repeat(64))
    private val luis = IdentityId("c".repeat(64))

    /** Nombre de fichero por pantalla: prefijo F2, minusculas, estable. */
    fun slug(screen: Screen): String = screen.name.lowercase()

    fun fileName(screen: Screen, size: Size): String =
        "f2-${slug(screen)}-${size.columns}x${size.rows}.txt"

    /**
     * Un caso del golden: pantalla, tamano y el estado EXACTO que se pinta.
     *
     * El estado sale de [stateFor], que es la unica fuente de los fixtures: si
     * un golden y un test de paridad usaran estados distintos, estarian
     * hablando de pantallas distintas con el mismo nombre.
     */
    data class Case(val screen: Screen, val size: Size) {
        val name: String get() = fileName(screen, size)
        fun state(): ShellState = stateFor(screen, size)
    }

    /** Los 21 casos: 7 pantallas x 3 tamanos. */
    fun cases(): List<Case> = Screen.entries.flatMap { screen ->
        SIZES.map { size -> Case(screen, size) }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun contacto(
        id: IdentityId,
        nombre: String,
        sesion: Boolean = true,
        clave: Boolean = true,
        presencia: Presence = Presence.RELAY_PRESENT,
        ultimo: String? = null,
        at: Long? = null,
    ) = ContactRow(
        identityId = id,
        fingerprint = id.value.take(16) + "\u2026",
        displayName = nombre,
        hasSession = sesion,
        hasAckKey = clave,
        presence = presencia,
        lastMessage = ultimo,
        lastAt = at,
    )

    private fun mensajes(): List<MessageRow> = listOf(
        MessageRow(
            messageId = MessageId.random(),
            direction = MessageDirection.IN,
            at = FIXED_MS - 300_000,
            text = "hola, \u00bfque tal?",
            state = MessageState.DELIVERED,
        ),
        MessageRow(
            messageId = MessageId.random(),
            direction = MessageDirection.OUT,
            at = FIXED_MS - 180_000,
            text = "nos vemos a las 8",
            state = MessageState.SENT,
        ),
        MessageRow(
            messageId = MessageId.random(),
            direction = MessageDirection.OUT,
            at = FIXED_MS - 120_000,
            text = "vale",
            state = MessageState.EXPIRED,
            failureCode = "ACK_TIMEOUT",
        ),
    )

    /**
     * Estado base del fixture.
     *
     * [size] entra en el viewport y nada mas: el estado NO guarda geometria, el
     * layout se recalcula por frame.
     */
    fun stateFor(screen: Screen, size: Size): ShellState {
        val base = ShellState(
            tabs = TabStacks.of(Screen.INICIO, screen),
            viewport = size,
            contacts = listOf(
                contacto(ana, "ana", ultimo = "hasta luego", at = FIXED_MS - 60_000),
                contacto(
                    pedro,
                    "pedro",
                    sesion = false,
                    clave = false,
                    presencia = Presence.UNKNOWN,
                ),
                contacto(
                    luis,
                    "luis",
                    clave = false,
                    presencia = Presence.RELAY_ABSENT,
                    ultimo = "ok",
                    at = FIXED_MS - 3_600_000,
                ),
            ),
            contactsLoaded = true,
            focus = Focus(FocusField.LIST, 0),
            status = StatusLine(
                link = LinkState.ONLINE,
                endpoint = "wss://relay.example/km",
                relay = "9".repeat(16) + "\u2026",
                localFingerprint = yo.value.take(16) + "\u2026",
                localIdentityId = yo.value,
                sessions = 1,
                ackKeys = 2,
            ),
        )
        return when (screen) {
            Screen.INICIO -> base

            Screen.IMPORTAR -> base.copy(
                importDraft = ImportDraft(
                    source = ImportSource.TEXT,
                    text = "KM1.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ),
            )

            // Portador de 469 B: el QR real mide 85x43 y NO cabe en ninguno de
            // los tres tamanos de la baseline. Se fija asi a proposito: el
            // golden comprueba el aviso honesto del minimo. El QR completo se
            // afirma aparte en ShellRendererTest/ShellViewTest (85x43 sin
            // marco, 89x47 con marco compacto), no en un golden de texto.
            Screen.COMPARTIR -> base.copy(
                share = ShareView(
                    carrier = buildString { while (length < 469) append("aB3-_") }.take(469),
                    byteLength = 469,
                    oneTimePreKeys = 0,
                ),
            )

            Screen.CONVERSACION -> base.copy(
                conversation = ConversationView(
                    peer = ana,
                    title = "ana",
                    messages = mensajes(),
                    composer = "hola",
                    peerPresence = Presence.RELAY_PRESENT,
                    hasSession = true,
                ),
                focus = Focus(FocusField.LIST, 2),
            )

            Screen.FRASE -> base.copy(
                phrase = PhraseView(
                    words = List(24) { "palabra${it + 1}" },
                    revealed = true,
                ),
            )

            Screen.RESTAURAR -> base.copy(
                phrase = PhraseView(
                    restoreInput = "palabra1 palabra2 palabra3",
                    confirmation = "SOBRESCRIBIR",
                    validation = PhraseValidation(wordCount = 24, checksumOk = true),
                    identityNow = "a" + yo.value.take(15) + "\u2026",
                ),
                focus = Focus(FocusField.CONFIRM, 0),
            )

            Screen.DIAGNOSTICO -> base.copy(
                logs = listOf(
                    LogRow(LogLevel.INFO, "[AUTH]", "[AUTH] identidad cargada desde el almacen"),
                    LogRow(LogLevel.INFO, "[CONTACT]", "[CONTACT] directorio de peers leido: 2"),
                    LogRow(LogLevel.INFO, "[SESSION]", "[SESSION] instalada peer=aaaa"),
                    LogRow(LogLevel.WARN, "[SEND]", "[SEND] EXPIRADO msg=abc"),
                    LogRow(LogLevel.ERROR, "[RELAY]", "[RELAY] RELAY_NOT_AUTHORIZED"),
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // Render y comparacion
    // ------------------------------------------------------------------

    /** Las lineas del caso, por la fachada: lo que el usuario ve hoy. */
    fun render(case: Case): List<RenderedLine> =
        ShellRenderer.render(case.state(), ascii = false, color = true, clock = FIXED_CLOCK)

    /**
     * El directorio de los goldens en el ARBOL DE FUENTES.
     *
     * Gradle ejecuta los tests con el directorio del modulo como `workingDir`, y
     * `-Dkm.golden.dir` permite moverlo. Existe para `-Dkm.golden.update=true`:
     * la comparacion normal lee del classpath, que es lo que viaja en el jar de
     * tests y no depende de donde se ejecute.
     */
    fun sourceDir(): File {
        System.getProperty("km.golden.dir")?.let { return File(it) }
        val modulo = File(".").absoluteFile
        return File(modulo, "src/test/resources/golden/tui")
    }

    private fun resourcePath(name: String): String = "golden/tui/$name"

    /** Las lineas guardadas, o `null` si el fichero no existe todavia. */
    fun expected(name: String): List<String>? {
        val stream = GoldenHarness::class.java.classLoader.getResourceAsStream(resourcePath(name))
            ?: return null
        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText().removeSuffix("\n").split("\n")
        }
    }

    /**
     * Compara (o escribe) el golden y afirma el invariante de forma.
     *
     * Tres cosas a la vez, y las tres importan:
     *  1. el texto es identico al guardado (o se escribe si `km.golden.update`);
     *  2. cada linea mide EXACTAMENTE `columns` celdas;
     *  3. no hay ni un `ESC` en el texto, porque el estilo no va aqui.
     */
    fun assertGolden(case: Case, lines: List<RenderedLine> = render(case)) {
        lines.forEachIndexed { i, linea ->
            assertEquals(
                case.size.columns,
                Sanitizer.width(linea.text),
                "${case.name} linea $i no mide ${case.size.columns}: '${linea.text}'",
            )
            assertFalse(
                linea.text.contains('\u001b'),
                "${case.name} linea $i lleva ESC: el golden es texto plano",
            )
        }
        assertEquals(
            case.size.rows,
            lines.size,
            "${case.name}: se esperaban ${case.size.rows} filas, hay ${lines.size}",
        )

        val actual = lines.map { it.text }
        if (System.getProperty("km.golden.update") == "true") {
            val destino = File(sourceDir(), case.name)
            destino.parentFile.mkdirs()
            destino.writeText(actual.joinToString("\n") + "\n", Charsets.UTF_8)
            return
        }

        val esperado = expected(case.name)
            ?: throw AssertionError(
                "falta el golden ${case.name}: genera con -Dkm.golden.update=true en " +
                    sourceDir().absolutePath,
            )
        assertEquals(esperado.size, actual.size, "${case.name}: numero de filas")
        esperado.indices.forEach { i ->
            assertEquals(
                esperado[i],
                actual[i],
                "${case.name} linea $i\n  esperado: '${esperado[i]}'\n  actual:   '${actual[i]}'",
            )
        }
    }
}

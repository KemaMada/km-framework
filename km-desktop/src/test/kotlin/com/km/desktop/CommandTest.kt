package com.km.desktop

import com.km.model.IdentityId
// p4/R-8.a: el motor del rele vive en `:km-transport`; `Command.DEFAULT` cita su
// `DEFAULT_ENDPOINT`, no una copia local.
import com.km.transport.JvmRelayTransport
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pruebas del bucle de la TUI.
 *
 * El parser se prueba aparte del `LineReader` a proposito: es logica pura, y
 * probarla a traves de una terminal real solo anadiria flakes.
 */
class CommandTest {

    private val hex = "ab".repeat(32)

    @Test
    fun `reconoce los comandos del contrato`() {
        assertIs<Command.Help>(Command.parse("help"))
        assertIs<Command.Identity>(Command.parse("identity"))
        assertIs<Command.Status>(Command.parse("status"))
        assertIs<Command.Quit>(Command.parse("quit"))
    }

    @Test
    fun `los alias y las variantes de caja funcionan`() {
        assertIs<Command.Quit>(Command.parse("q"))
        assertIs<Command.Quit>(Command.parse("EXIT"))
        assertIs<Command.Help>(Command.parse("?"))
        assertIs<Command.Connect>(Command.parse("conectar"))
    }

    @Test
    fun `connect acepta endpoint opcional`() {
        val sinArgumento = Command.parse("connect")
        assertIs<Command.Connect>(sinArgumento)
        assertEquals(null, sinArgumento.endpoint)

        val conArgumento = Command.parse("connect wss://ejemplo/km")
        assertIs<Command.Connect>(conArgumento)
        assertEquals("wss://ejemplo/km", conArgumento.endpoint)
    }

    @Test
    fun `send exige un identityId de forma valida`() {
        val ok = Command.parse("send $hex hola mundo")
        assertIs<Command.Send>(ok)
        assertEquals(IdentityId(hex), ok.to)
        assertEquals("hola mundo", ok.text, "el texto conserva los espacios intermedios")
    }

    @Test
    fun `send con identityId corto no se lanza y se informa`() {
        assertIs<Command.Unknown>(Command.parse("send abc hola"))
        assertIs<Command.Unknown>(Command.parse("send ${"zz".repeat(32)} hola"))
    }

    @Test
    fun `send sin texto no se acepta`() {
        assertIs<Command.Unknown>(Command.parse("send $hex"))
    }

    @Test
    fun `el camino de contacto y mensajeria se parsea sin inventar`() {
        assertIs<Command.Import>(Command.parse("import @/tmp/ana.km"))
        assertEquals("@/tmp/ana.km", (Command.parse("import @/tmp/ana.km") as Command.Import).arg)

        assertIs<Command.Export>(Command.parse("export"))
        assertEquals("/tmp/mio.km", (Command.parse("export /tmp/mio.km") as Command.Export).path)

        assertIs<Command.Announce>(Command.parse("announce $hex"))
        assertEquals(IdentityId(hex), (Command.parse("announce $hex") as Command.Announce).to)
        assertIs<Command.SessionCmd>(Command.parse("session $hex"))
        assertIs<Command.Contacts>(Command.parse("contacts"))
        assertIs<Command.History>(Command.parse("history $hex"))

        val uuid = java.util.UUID.randomUUID()
        assertEquals(
            com.km.model.MessageId.fromString(uuid.toString()),
            (Command.parse("resend $uuid") as Command.Resend).messageId,
        )
        assertIs<Command.Discard>(Command.parse("discard $uuid"))

        // Un id que no es UUID no se acepta: el comando no adivina.
        assertIs<Command.Unknown>(Command.parse("resend abc"))
        assertIs<Command.Unknown>(Command.parse("announce abc"))
    }

    @Test
    fun `una linea vacia no es un error`() {
        assertIs<Command.Unknown>(Command.parse("   "))
    }

    @Test
    fun `el usage CLI menciona los comandos nuevos del REPL`() {
        // F7.6: `--help` es la unica fuente de verdad del CLI, y listaba solo los
        // siete historicos. Quien arranca con `-h` no se enteraba de que existe
        // el camino de contacto.
        val usage = DesktopOptions.usage()
        listOf("import", "export", "announce", "session", "contacts", "history", "resend", "discard")
            .forEach { comando ->
                assertTrue(usage.contains(comando), "usage() debe mencionar '$comando'")
            }
    }

    @Test
    fun `la tabla de ayuda cubre todos los comandos despachados`() {
        val documented = Command.TABLE.map { it.first.substringBefore(" ") }.toSet()
        // `peer` entra aqui con la correccion del resolver de ACK: registrar la
        // clave de firma de un peer es lo que hace que sus `ACK` se autentiquen,
        // asi que es un comando de primera clase y no un detalle.
        // G1: el REPL conserva los siete historicos y anade el camino de
        // contacto (import/export/announce/session) y el de mensajeria
        // (contacts/history/resend/discard). La tabla tiene que cubrirlos TODOS.
        val dispatched = setOf(
            "help", "identity", "status", "quit", "connect", "send", "peer",
            "import", "export", "announce", "session", "contacts", "history", "resend", "discard",
        )
        assertEquals(dispatched, documented, "help debe listar exactamente lo que el bucle despacha")
    }

    @Test
    fun `peer exige identidad y clave, y no las inventa`() {
        val id = "ab".repeat(32)
        val key = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })

        val parsed = Command.parse("peer $id $key")
        assertIs<Command.Peer>(parsed)
        assertEquals(id, parsed.identityIdHex)
        assertEquals(key, parsed.publicKeyBase64)

        // Ni uno, ni dos, ni de mas: el comando con la aridad equivocada es un
        // comando desconocido, no un comando a medio construir.
        assertIs<Command.Unknown>(Command.parse("peer"))
        assertIs<Command.Unknown>(Command.parse("peer $id"))
        assertIs<Command.Unknown>(Command.parse("peer $id $key extra"))
        // Y la coherencia NO se comprueba aqui: es trabajo del directorio, que
        // tiene la derivacion, y por eso se prueba en `DesktopAckAuthenticationTest`.
        assertIs<Command.Peer>(Command.parse("peer ${"zz".repeat(32)} $key"))
    }

    @Test
    fun `los argumentos de linea de comandos se parsean`() {
        val porDefecto = DesktopOptions.parse(emptyArray())
        assertEquals(JvmRelayTransport.DEFAULT_ENDPOINT, porDefecto.endpoint)

        val conRelay = DesktopOptions.parse(arrayOf("--relay", "wss://otro/km", "--verbose"))
        assertEquals("wss://otro/km", conRelay.endpoint)
        assertTrue(conRelay.verbose)

        val conAlmacen = DesktopOptions.parse(arrayOf("--keystore", "/tmp/x.kmkey", "--reset"))
        assertEquals("/tmp/x.kmkey", conAlmacen.keystorePath.toString())
        assertTrue(conAlmacen.reset)
    }

    @Test
    fun `una opcion desconocida se rechaza con mensaje`() {
        var thrown: IllegalArgumentException? = null
        try {
            DesktopOptions.parse(arrayOf("--inventada"))
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assertTrue(thrown != null, "una opcion desconocida no puede pasarse en silencio")
    }
}

package com.example.keymessage.util

import com.example.keymessage.config.RelayEndpointConfig
import com.example.keymessage.build.CarrierSingleDecodePointTest
import com.example.keymessage.build.NoFrameworkReimplementationTest
import com.example.keymessage.core.CoreFactory
import com.example.keymessage.core.SendOutcome
import com.example.keymessage.contact.ContactImporter
import com.example.keymessage.contact.ContactBootstrap
import com.km.transport.SignalDelivery
import com.km.transport.SignalOutcome
import com.example.keymessage.data.ConversationRepository
import com.example.keymessage.identity.CorruptIdentityException
import com.example.keymessage.identity.IdentityMaterialFactory
import com.example.keymessage.identity.InMemoryIdentityStore
import com.example.keymessage.model.ChatMessage
import com.example.keymessage.net.EndpointSequence
import com.example.keymessage.net.RelayEndpointsExhausted
import com.example.keymessage.session.SessionCoordinator
import com.example.keymessage.support.RecordingTransport
import com.example.keymessage.support.TestWorld
import com.example.keymessage.support.InMemoryMessageDao
import com.example.keymessage.support.countingManager
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import com.km.storage.InMemoryDuplicateStore
import com.km.storage.InMemoryMessageStore
import com.km.storage.InMemoryOfflineQueue
import com.example.keymessage.contact.InMemoryContactBook
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 4.2 — Los diez canales se EMITEN desde el codigo de la app, y el sumidero recibe
 * exactamente ese token.
 *
 * ## POR QUE NO BASTA CON "estan en KMLog.CHANNELS"
 *
 * Porque declarar una constante y no usarla es la forma mas facil de tener un
 * canal muerto: la lista se ve llena y `adb logcat -s KeyMessage` no muestra
 * nunca `[BOOTSTRAP_ALL_FAILED]`. Este test EJERTA los caminos de la app que
 * emiten —configuracion, failover, importacion, rechazo de envio— y comprueba que
 * el token que llega al sumidero es el declarado.
 *
 * ## LO QUE NO SE EJERTA AQUI
 *
 * Los canales que dependen de un cable real —`[AUTH]`, `[SESSION]`, `[RELAY]` en su
 * exito— salen de codigo del motor de p4 y de un handshake contra un rele de
 * verdad. Aqui se mide que el token EXISTE y se emite desde la app; que el rele
 * autentique y que la sesion cruce dos dispositivos es la etapa ADB, y se declara
 * como tal en vez de simularse.
 */
class LogChannelCoverageTest {

    private val emitidas = mutableListOf<String>()

    @Before
    fun setUp() {
        emitidas.clear()
        KMLog.clear()
        KMLog.install(object : LogSink {
            override fun log(level: KMLog.Level, tag: String, message: String) {
                emitidas += message
            }
        })
    }

    private fun lineasDe(canal: String): List<String> = emitidas.filter { it.contains(canal) }

    @Test
    fun `CONFIG se emite al resolver los endpoints`() {
        RelayEndpointConfig.resolve(runtimeOverride = "wss://uno.invalid/km,wss://dos.invalid/km")

        assertTrue(lineasDe("[CONFIG]").isNotEmpty(), "resolver la configuracion emite [CONFIG]")
        assertTrue(
            lineasDe("[CONFIG]").any { it.contains("wss://uno.invalid/km") && it.contains("origen=RUNTIME_OVERRIDE") },
            "y la linea dice QUE endpoints y de DONDE: ${lineasDe("[CONFIG]")}",
        )
    }

    @Test
    fun `RELAY y BOOTSTRAP_ALL_FAILED se emiten al agotar los endpoints`() {
        val secuencia = EndpointSequence(listOf("wss://uno.invalid/km", "wss://dos.invalid/km"))

        assertFailsWith<RelayEndpointsExhausted> {
            secuencia.connect { Result.failure(IllegalStateException("sin ruta")) }
        }

        assertEquals(2, lineasDe("[RELAY]").count { it.contains("intentando") }, "un intento por endpoint")
        assertEquals(2, lineasDe("[RELAY]").count { it.contains("fallo") }, "y un fallo por endpoint")
        val agotados = lineasDe("[BOOTSTRAP_ALL_FAILED]")
        assertEquals(1, agotados.size, "el agotamiento se registra UNA vez")
        assertTrue(
            agotados.single().contains("wss://dos.invalid/km"),
            "con la lista de los probados: ${agotados.single()}",
        )
    }

    @Test
    fun `CONTACT se emite al importar y al rechazar un bundle`() {
        val parBob = TestWorld.deviceWithRoot("bob")
        parBob.device.issueOneTimePreKey(TestWorld.BOB_ONE_TIME_PREKEY_ID)
        val sobre = parBob.root.bundleTextFor(
            listOf(parBob.device),
            parBob.device,
            listOf(TestWorld.BOB_ONE_TIME_PREKEY_ID),
        )

        ContactImporter.import(sobre)
        assertTrue(lineasDe("[CONTACT]").any { it.contains("verificado") }, "una importacion valida se registra")

        val alterado = sobre.dropLast(3) + "AAA"
        runCatching { ContactImporter.import(alterado) }
        assertTrue(
            lineasDe("[CONTACT]").any { it.contains("rechazado") },
            "y un rechazo tambn: sin esto, 'nunca llego' y 'fue rechazado' se parecen",
        )
    }

    @Test
    fun `SEND se emite al rechazar un envio`() {
        val material = IdentityMaterialFactory().fromSeed(ByteArray(32) { (it + 5).toByte() })
        val (manager, _) = countingManager()
        val transport = RecordingTransport(online = true)
        val bundle = CoreFactory().create(
            localIdentity = material.identityId,
            localPrivateKey = material.keyPair.privateKey,
            transport = transport,
            manager = manager,
            messageStore = InMemoryMessageStore(),
            offlineQueue = InMemoryOfflineQueue(),
            duplicateStore = InMemoryDuplicateStore(),
            contactBook = InMemoryContactBook(),
        )
        val parContacto = TestWorld.deviceWithRoot("bob")
        val dao = InMemoryMessageDao()
        val repositorio = ConversationRepository({ bundle.core }, dao, material.identityId)

        val envio = repositorio.send(parContacto.root.identityId, "sin sesion cifrada".toByteArray())

        assertTrue(!envio.sent, "sin sesion el envio no sale (pretesto)")
        val envios = lineasDe("[SEND]")
        assertTrue(envios.isNotEmpty(), "el camino de envio de la app registra en [SEND]")
        assertTrue(
            envios.any { it.contains("NOT SENT") && !it.contains("-> SENT") },
            "como NO enviado, nunca como enviado: $envios",
        )
        assertTrue(
            envios.none { it.contains("sin sesion cifrada") },
            "y el texto del mensaje no se filtra al log: $envios",
        )
    }

    @Test
    fun `RECV se emite al registrar una recepcion`() {
        KMLog.messageReceived(MessageId.random().value.toString(), "ab".repeat(32))

        assertTrue(lineasDe("[RECV]").isNotEmpty(), "una recepcion se registra")
    }

    @Test
    fun `SIGNAL se emite al fallar el intercambio de contactos`() {
        // R5: el emisor NO da el alta por hecha, y el fallo del intercambio se
        // dice con su codigo. El canal `[SIGNAL]` es el que lo dice.
        runBlocking {
            runCatching {
                ContactBootstrap(
                    exportarPortador = { byteArrayOf(1, 2, 3) },
                    enviarPortador = { SignalOutcome.Failed(SignalDelivery.CODE_PEER_OFFLINE, 4) },
                    abrirSesionYEntregar = { Result.success(Unit) },
                ).ejecutar()
            }
        }

        val senal = lineasDe("[SIGNAL]")
        assertTrue(senal.isNotEmpty(), "el fallo del intercambio se registra")
        assertTrue(
            senal.any { it.contains(SignalDelivery.CODE_PEER_OFFLINE) },
            "y dice el codigo estable, que es lo que la UI puede pintar: $senal",
        )
    }

    @Test
    fun `los diez canales estan DECLARADOS y son los de la especificacion`() {
        assertEquals(
            listOf(
                "[CONFIG]",
                "[RELAY]",
                "[AUTH]",
                "[CONTACT]",
                "[FILE]",
                "[SIGNAL]",
                "[SESSION]",
                "[SEND]",
                "[RECV]",
                "[BOOTSTRAP_ALL_FAILED]",
            ),
            KMLog.CHANNELS,
            "la lista es la de la especificacion, en orden: cambiarla es cambiar la especificacion",
        )
    }

    @Test
    fun `todo canal que USA la app esta DECLARADO`() {
        // Un canal USADO y no DECLARADO hace que `KMLog.event` LANZE, y el
        // `require` revienta el proceso EN MEDIO de un rechazo: un `.km` que no
        // se puede leer tumbaba la app en vez de pintar "RECHAZADO
        // FILE_READ_FAILED". Es un fallo silencioso con forma de crash, y solo
        // se ve mirando el CODIGO: la lista de canales decia ocho y la app
        // usaba un noveno.
        //
        // Por eso este guard mira las DOS caras: lo declarado y lo usado.
        val usados = mutableSetOf<String>()
        CarrierSingleDecodePointTest.fuentes().forEach { f ->
            usados += canalesUsados(f.readText())
        }
        val noDeclarados = usados - KMLog.CHANNELS.toSet()
        assertEquals(
            emptyList<String>(),
            noDeclarados.toList(),
            "canales USADOS y NO DECLARADOS: KMLog.event los rechaza con una excepcion y " +
                "tumba la app en medio de un rechazo: ${noDeclarados.joinToString(", ")}",
        )
    }

    /**
     * Los canales que una fuente EMITE.
     *
     * Se quitan los comentarios antes de buscar: un KDoc que nombra un canal
     * no lo emite.
     */
    private fun canalesUsados(fuente: String): Set<String> {
        val sinComentarios = NoFrameworkReimplementationTest.sinComentarios(fuente)
        return Regex("KMLog\\.event\\(\"(\\[[A-Z_]+\\])\"").findAll(sinComentarios)
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun `AUTH y SESSION tienen un emisor propio y con nombre`() {
        // Estos dos los emite codigo del motor de p4 y de un handshake contra un
        // rele real; lo que se comprueba aqui es que la app tiene una via
        // LLAMADA para emitirlos, y que esa via exige el token exacto. Un canal
        // que solo existe en una lista es un canal muerto.
        val escribioAuth = runCatching { KMLog.event("[AUTH]", "AUTH_OK dialecto=KM-WIRE-RELAY") }.isSuccess
        val escribioSession = runCatching { KMLog.event("[SESSION]", "iniciada peer=ab12cd34") }.isSuccess

        assertTrue(escribioAuth && escribioSession, "los dos canales se pueden emitir desde codigo de la app")
        assertTrue(lineasDe("[AUTH]").isNotEmpty() && lineasDe("[SESSION]").isNotEmpty(), "y llegan al sumidero")
        assertTrue(
            runCatching { KMLog.event("[LOGIN]", "canal que no existe") }.isFailure,
            "control negativo: renombrar un canal no se cuela, el token tiene que ser el declarado",
        )
    }

    @Test
    fun `control el detector de canales distingue dos tokens parecidos`() {
        KMLog.event("[SEND]", "esto es un envio")
        KMLog.event("[RECV]", "esto es una recepcion")

        assertEquals(1, lineasDe("[SEND]").size, "un token de cuatro letras no captura al de cinco")
        assertEquals(1, lineasDe("[RECV]").size, "ni al reves")
        assertEquals(2, emitidas.size, "y las dos lineas se emiten por separado")
    }
}
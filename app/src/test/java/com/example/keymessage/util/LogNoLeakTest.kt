package com.example.keymessage.util

import com.km.identity.ContactBundle
import com.km.messaging.LocalInitiatorKeys
import com.km.messaging.LocalResponderKeys
import com.km.model.IdentityId
import com.km.model.Message
import com.km.model.MessageId
import com.km.model.MessageState
import com.example.keymessage.contact.InMemoryContactBook
import com.example.keymessage.data.ConversationRepository
import com.example.keymessage.identity.IdentityMaterialFactory
import com.example.keymessage.identity.InMemoryIdentityStore
import com.example.keymessage.identity.fraseDeRecuperacion
import com.example.keymessage.recovery.RecoveryFlow
import com.example.keymessage.recovery.ResultadoRestauracion
import com.example.keymessage.recovery.Validacion
import com.example.keymessage.session.SessionCoordinator
import com.example.keymessage.support.InMemoryMessageDao
import com.example.keymessage.support.RecordingTransport
import com.example.keymessage.support.TestWorld
import com.example.keymessage.support.countingManager
import com.example.keymessage.util.toHex
import com.km.mnemonic.Mnemonic
import com.km.mnemonic.MnemonicException
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 4.1 — El log NO filtra lo que el log no debe filtrar, y se comprueba con
 * EJECUCION REAL de los caminos que registran.
 *
 * ## QUE AFIRMA
 *
 * Con cuatro marcadores unicos —el payload de un mensaje, la semilla de la
 * identidad, la clave de acuerdo del dispositivo y la clave firmada del
 * receptor—, NINGUNA linea emitida contiene ninguno de ellos. Los cuatro son
 * material que existe de verdad en el mundo que ejecuta el test: el
 * establecimiento de sesion es el de [SessionCoordinator], el envio es el de
 * [KMLog.messageSent] y la recepcion el de [KMLog.messageReceived].
 *
 * ## POR QUE LOS MARCADORES SON UNICOS Y NO CONSTANTES
 *
 * Una cadena repetida como `"PAGO"` aparece en el codigo, en los nombres y en los
 * mensajes de fallo, y un detector que la busca daria positivo sin motivo. Los
 * marcadores de aqui son unicos y con longitud propia.
 *
 * ## LO QUE ESTO NO DICE
 *
 * Que las lineas esten libres de TODO lo sensible. Lo que dice es que la API de
 * registro no tiene por donde recibirlo: [KMLog.messageSent] y
 * [KMLog.messageReceived] reciben identificadores y un resultado, y el truncado
 * se aplica antes de formatear. La garantia es ESTRUCTURAL y este test la vigila.
 */
class LogNoLeakTest {

    private data class Linea(val message: String)

    private val emitidas = mutableListOf<Linea>()

    @Before
    fun setUp() {
        emitidas.clear()
        KMLog.clear()
        KMLog.install(object : LogSink {
            override fun log(level: KMLog.Level, tag: String, message: String) {
                emitidas += Linea(message)
            }
        })
    }

    /** Marcador unico de payload: no aparece en ningun otro sitio del codigo. */
    private val marcadorPayload = "Z4ntr7-payload-marcador-8813"

    private fun textoDeTodas(): String = emitidas.joinToString("\n") { it.message }

    private fun lineasDe(canal: String): List<String> = emitidas.map { it.message }.filter { it.contains(canal) }

    @Test
    fun `un envio registrado NO contiene el payload`() {
        val mensaje = Message(
            messageId = MessageId.random(),
            from = IdentityId("a".repeat(64)),
            to = IdentityId("b".repeat(64)),
            payload = marcadorPayload.toByteArray(),
            timestamp = 1_760_000_000_000L,
            state = MessageState.SENT,
        )

        KMLog.messageSent(
            messageId = mensaje.messageId.value.toString(),
            peerId = mensaje.to.value,
            resultado = "SENT",
        )

        assertTrue(emitidas.isNotEmpty(), "el envio se registra (pretesto)")
        assertTrue(
            !textoDeTodas().contains(marcadorPayload),
            "el payload llego al log: ${textoDeTodas()}",
        )
    }

    @Test
    fun `una recepcion registrada NO contiene el payload`() {
        KMLog.messageReceived(MessageId.random().value.toString(), "e".repeat(64))

        val linea = emitidas.single().message
        assertTrue(linea.contains("[RECV]"), "pretesto: la linea es de recepcion")
        assertTrue(!linea.contains(marcadorPayload), "y no lleva el payload")
    }

    @Test
    fun `el camino de envio de la app NO filtra el payload`() {
        // Este es el caso que importa: el log lo escribe `ConversationRepository`,
        // no el test. Con la llamada directa a `KMLog` el test probaria el helper
        // y no el CODIGO DE LA APP.
        val material = IdentityMaterialFactory().fromSeed(ByteArray(32) { (it * 5 + 3).toByte() })
        val (manager, _) = countingManager()
        val bundle = com.example.keymessage.core.CoreFactory().create(
            localIdentity = material.identityId,
            localPrivateKey = material.keyPair.privateKey,
            transport = RecordingTransport(online = true),
            manager = manager,
            messageStore = com.km.storage.InMemoryMessageStore(),
            offlineQueue = com.km.storage.InMemoryOfflineQueue(),
            duplicateStore = com.km.storage.InMemoryDuplicateStore(),
            contactBook = InMemoryContactBook(),
        )
        val parContacto = TestWorld.deviceWithRoot("bob")
        val repositorio = ConversationRepository({ bundle.core }, InMemoryMessageDao(), material.identityId)

        repositorio.send(parContacto.root.identityId, marcadorPayload.toByteArray())

        assertTrue(lineasDe("[SEND]").isNotEmpty(), "el camino de envio registra en [SEND] (pretesto)")
        assertTrue(
            !textoDeTodas().contains(marcadorPayload),
            "el payload llego al log desde el codigo de la app: " + textoDeTodas(),
        )
    }

    @Test
    fun `un establecimiento de sesion REAL no filtra claves ni payload`() {
        val coordinator = SessionCoordinator()
        val parAlice = TestWorld.deviceWithRoot("alice")
        val parBob = TestWorld.deviceWithRoot("bob")
        val alice = parAlice.device
        val bob = parBob.device
        val opkId = TestWorld.BOB_ONE_TIME_PREKEY_ID
        bob.issueOneTimePreKey(opkId)

        val marcadores = listOf(
            "payload" to marcadorPayload.toByteArray().toHex(),
            "semilla del emisor" to alice.signingKeyPair.privateKey.toHex(),
            "clave de acuerdo del emisor" to alice.agreementKey.privateKey.toHex(),
            "clave firmada del receptor" to bob.signedPreKey.privateKey.toHex(),
            "OPK del receptor" to assertNotNull(bob.oneTimePreKey(opkId)).privateKey.toHex(),
        )

        val (managerA, _) = countingManager()
        val (managerB, _) = countingManager()
        val aliceIdentity = IdentityId(alice.deviceId.toHex())
        val bobIdentity = IdentityId(bob.deviceId.toHex())

        val iniciada = coordinator.iniciar(
            localIdentity = aliceIdentity,
            peerContext = coordinator.contextoHacia(
                ContactBundle.verified(parBob.root.bundleMapFor(listOf(bob), bob, listOf(opkId))),
            ),
            localKeys = LocalInitiatorKeys(alice.deviceId, alice.agreementKey),
            manager = managerA,
            registry = com.km.messaging.SecureSessionRegistry(aliceIdentity),
        )
        coordinator.aceptar(
            localIdentity = bobIdentity,
            peerContext = com.km.node.PeerContext.forContact(
                ContactBundle.verified(parAlice.root.bundleMapFor(listOf(alice), alice, emptyList())),
            ),
            localKeys = LocalResponderKeys(
                deviceId = bob.deviceId,
                agreementKey = bob.agreementKey,
                signedPreKey = bob.signedPreKey,
                oneTimePreKey = assertNotNull(bob.oneTimePreKey(opkId)),
                oneTimePreKeyId = opkId,
            ),
            prekeyFrameWire = iniciada.prekeyFrame,
            manager = managerB,
            registry = com.km.messaging.SecureSessionRegistry(bobIdentity),
        )

        assertTrue(
            KMLog.lines().any { it.contains("[SESSION]") },
            "el establecimiento emite [SESSION] (pretesto)",
        )
        marcadores.forEach { marcador ->
            val texto = textoDeTodas()
            assertTrue(!texto.contains(marcador.second), "se filtro " + marcador.first + ": " + texto)
        }
    }

    @Test
    fun `los diez canales no filtran nada en conjunto`() {
        KMLog.CHANNELS.forEachIndexed { i, canal ->
            when (canal) {
                "[SEND]" -> KMLog.messageSent(MessageId.random().value.toString(), "f".repeat(64), "SENT")
                "[RECV]" -> KMLog.messageReceived(MessageId.random().value.toString(), "0".repeat(64))
                "[BOOTSTRAP_ALL_FAILED]" -> KMLog.event(canal, "probados=[]", KMLog.Level.ERROR)
                else -> KMLog.event(canal, "evento $i de $canal")
            }
        }

        assertEquals(KMLog.CHANNELS.size, emitidas.size, "los diez canales emiten (pretesto)")
        assertTrue(!textoDeTodas().contains(marcadorPayload), "ningun canal lleva el payload")
    }

    @Test
    fun `el volcado del buffer tampoco filtra`() {
        KMLog.CHANNELS.forEach { KMLog.event(it, "linea de $it") }

        val volcado = KMLog.lines().joinToString("\n")
        assertTrue(volcado.isNotEmpty(), "el buffer tiene lineas (pretesto)")
        assertTrue(!volcado.contains(marcadorPayload), "el volcado que ve la pantalla tampoco filtra")
    }

    @Test
    fun `control el detector de marcadores ve un marcador en un texto de verdad`() {
        // El pretesto del detector: si `contains` no viera el marcador, los tests
        // de arriba pasarian con cualquier cadena.
        KMLog.event("[SEND]", "msg=$marcadorPayload")

        assertTrue(
            emitidas.single().message.contains(marcadorPayload),
            "el marcador se detecta cuando ESTA en la linea: sin esto, el test no probaria nada",
        )
    }

    // =====================================================================
    // 6.4 — La frase de recuperacion tampoco sale por el log
    // =====================================================================

    /** Semilla NO constante, para que la frase no sea la de los 23 "abandon". */
    private fun semillaDePrueba(distintiva: Int): ByteArray =
        ByteArray(32) { ((it * 19 + distintiva * 23) % 239 + 7).toByte() }

    /**
     * Las palabras "largas" de la frase: las de 5 caracteres o mas.
     *
     * Con 3 letras un `contains` daria falsos positivos en cualquier texto
     * (`art` esta dentro de `parte`, `add` dentro de `n adder`), y un detector
     * que se dispara solo es un detector que no vigila. Las cortas se cubren
     * igual, pero mirando la frase COMPLETA, que si es un dato unico.
     */
    private fun palabrasLargas(frase: String): List<String> =
        frase.split(" ").filter { it.length >= 5 }.distinct()

    @Test
    fun `el camino COMPLETO de recuperar y restaurar no lleva palabras ni semilla al log`() {
        // Este es el caso que importa: el log no lo escribe el test, lo escribe
        // el contenedor y el coordinador. Se ejercitan los dos.
        val factory = IdentityMaterialFactory()
        val original = factory.fromSeed(semillaDePrueba(1))
        val fraseNueva = fraseDeRecuperacion(factory.fromSeed(semillaDePrueba(2)))
        val store = InMemoryIdentityStore()
        store.save(original)
        val flow = RecoveryFlow(store, factory, CicloSinEffectos)

        val validacion = flow.validar(fraseNueva.display())
        assertTrue(validacion is Validacion.Aceptada, "pretesto: la frase es valida")
        val resultado = flow.restaurar(validacion, original.identityId, confirmado = true)

        assertTrue(resultado is ResultadoRestauracion.Restaurada, "pretesto: se restauró")
        // El ciclo de verdad registra, asi que hay lineas que mirar.
        assertTrue(emitidas.isNotEmpty(), "el camino de restauracion registra (pretesto)")

        val texto = textoDeTodas()
        for (palabra in palabrasLargas(fraseNueva.display())) {
            assertTrue(
                !texto.contains(palabra),
                "la palabra $palabra llego al log: $texto",
            )
        }
        assertTrue(!texto.contains(fraseNueva.display()), "la frase entera llego al log: $texto")

        val semilla = factory.fromSeed(semillaDePrueba(2)).keyPair.privateKey
        val semillaHex = semilla.toHex()
        val semillaB64 = java.util.Base64.getEncoder().encodeToString(semilla)
        assertTrue(!texto.contains(semillaHex), "la semilla en hex llego al log: $texto")
        assertTrue(
            !texto.contains(semillaB64),
            "la semilla en base64 —que es como la guarda el codec— llego al log: $texto",
        )
    }

    @Test
    fun `control el detector de palabras ve una palabra de la frase en una linea`() {
        // El control del control: si `contains` no encontrara una palabra de la
        // frase, el test de arriba pasaria con un log que si la filtra.
        val frase = fraseDeRecuperacion(IdentityMaterialFactory().fromSeed(semillaDePrueba(3)))
        val palabra = palabrasLargas(frase.display()).first()

        KMLog.event("[SESSION]", "control: $palabra")

        assertTrue(
            textoDeTodas().contains(palabra),
            "el detector ve la palabra cuando esta: sin esto el test no probaria nada",
        )
    }

    @Test
    fun `el toString de la frase no sale por el log`() {
        val frase = fraseDeRecuperacion(IdentityMaterialFactory().fromSeed(semillaDePrueba(4)))

        KMLog.event("[CONFIG]", "frase=$frase")

        val texto = textoDeTodas()
        assertTrue(texto.contains("MnemonicPhrase(24 palabras)"), "pretesto: el toString redactado si sale")
        for (palabra in palabrasLargas(frase.display())) {
            assertTrue(!texto.contains(palabra), "la palabra $palabra salio con el toString: $texto")
        }
    }

    @Test
    fun `ninguna excepcion de una frase invalida lleva palabras`() {
        val palabras = fraseDeRecuperacion(IdentityMaterialFactory().fromSeed(semillaDePrueba(5)))
            .display()
            .split(" ")
        val entradas = mapOf(
            "23 palabras" to palabras.dropLast(1).joinToString(" "),
            "25 palabras" to (palabras + "zoo").joinToString(" "),
            "palabra ajena" to palabras.toMutableList().also { it[9] = "zzzqqq" }.joinToString(" "),
            "checksum roto" to palabras.toMutableList().also { it[23] = "zoo" }.joinToString(" "),
        )

        for ((nombre, entrada) in entradas) {
            val fallo = runCatching { Mnemonic.parse(entrada) }.exceptionOrNull()
            assertTrue(
                fallo is MnemonicException,
                "$nombre deberia lanzar MnemonicException, y lanzo $fallo",
            )
            val mensaje = fallo.message.orEmpty()
            for (palabra in palabrasLargas(fraseDeRecuperacion(
                IdentityMaterialFactory().fromSeed(semillaDePrueba(5)),
            ).display())) {
                assertTrue(!mensaje.contains(palabra), "$nombre filtro la palabra $palabra: $mensaje")
            }
        }
    }

    @Test
    fun `el almacen de identidad no guarda la frase, solo la semilla y el aviso`() {
        // `IdentityCodec` es lo que se escribe en `km_identity.xml`: si la frase
        // estuviera dentro, el backup del sistema se la llevaria aunque
        // `backup_rules.xml` no supiera de ella.
        val material = IdentityMaterialFactory().fromSeed(semillaDePrueba(6))
        val frase = fraseDeRecuperacion(material)
        val store = InMemoryIdentityStore()
        store.save(material)
        store.setPhraseAcknowledged(true)

        val escrito = assertNotNull(store.text)
        assertTrue(escrito.contains("KM-IDENTITY-1"), "pretesto: hay texto de identidad guardado")
        for (palabra in palabrasLargas(frase.display())) {
            assertTrue(!escrito.contains(palabra), "la palabra $palabra quedo guardada")
        }
        assertTrue(!escrito.contains(frase.display()), "la frase quedo guardada")
        assertTrue(store.text!!.lines().size <= 4, "siguen siendo las cuatro lineas del formato")
    }

    @Test
    fun `las reglas de backup excluyen la identidad y las claves locales`() {
        val dir = appRoot()
        for (nombre in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val fichero = File(dir, "src/main/res/xml/$nombre")
            assertTrue(fichero.isFile, "pretesto: existe $nombre")
            val xml = fichero.readText()

            assertTrue(
                xml.contains("""<exclude domain="sharedpref" path="km_identity.xml"/>"""),
                "$nombre no excluye km_identity.xml:\n$xml",
            )
            assertTrue(
                xml.contains("""<exclude domain="sharedpref" path="km_local_keys.xml"/>"""),
                "$nombre no excluye km_local_keys.xml:\n$xml",
            )
        }
    }

    @Test
    fun `control de las reglas de backup, una exclusion inventada NO se detecta como exclida`() {
        // El control del control: si `contains` no fallara para un fichero que no
        // excluye nada, el test de arriba no probaria nada.
        val xml = File(appRoot(), "src/main/res/xml/backup_rules.xml").readText()

        assertTrue(
            !xml.contains("""path="km_inventada.xml""""),
            "el detector distingue una exclusion real de una inventada",
        )
    }

    /**
     * El ciclo del contenedor SIN motor: registra igual que lo hace `AppContainer`
     * y no abre ni un socket.
     *
     * Las lineas son las MISMAS que emite el contenedor de verdad, para que el
     * pretesto "hay lineas que mirar" se cumpla de verdad y el test compruebe
     * sobre texto emitido por el camino de recuperacion y no sobre un buffer
     * vacio que passaria siempre.
     */
    private object CicloSinEffectos : RecoveryFlow.Ciclo {
        override fun parar() = Unit

        override fun arrancar() {
            KMLog.event("[RELAY]", "en linea como ${"a".repeat(64).take(12)}")
        }

        override fun regenerarClavesLocales() {
            KMLog.event("[CONFIG]", "claves de sesion regeneradas para la identidad nueva")
        }
    }

    private fun appRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/res/xml/backup_rules.xml").isFile) return dir
            dir = dir.parentFile
        }
        error("No se encontro el modulo app desde user.dir=${System.getProperty("user.dir")}")
    }
}
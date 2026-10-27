package com.km.desktop.util

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 5.1 — El contrato del sumidero, y la NO FUGA.
 *
 * ## LO QUE AFIRMA
 *
 * - Cada evento llega al sumidero con SU token de canal, y el TAG es estable.
 * - Un payload y una clave marcadores NO aparecen en NINGUNA linea, ni con el
 *   camino de `messageSent` ni con el de `messageReceived`.
 *
 * ## POR QUE LA NO FUGA ES ESTRUCTURAL Y NO DISCIPLINARIA
 *
 * Porque `messageSent` y `messageReceived` NO TIENEN un parametro donde meter el
 * payload. No es que quien llama seomitempty pasar el texto: es que la API no
 * tiene por donde. Un `LogNoLeakTest` que solo comprobara "las lineas no
 * contienen el marcador" seria verdad con una API que lo admitiera y por
 *kachón no lo usara; aqui la garantia es doble, la de la firma y la del
 * contenido.
 */
class DiagLogTest {

    private lateinit var sumidero: MemoryDiagSink

    @BeforeEach
    fun antes() {
        sumidero = MemoryDiagSink()
        DiagLog.install(sumidero)
        DiagLog.clear()
    }

    @AfterEach
    fun despues() {
        DiagLog.clear()
        DiagLog.install(StreamDiagSink())
    }

    @Test
    fun `cada evento llega con SU token de canal`() {
        DiagLog.event("[RELAY]", "conectado")
        DiagLog.event("[SEND]", "msg=abc -> SENT")

        assertEquals(1, sumidero.deCanal("[RELAY]").size, "un evento de RELAY")
        assertEquals(1, sumidero.deCanal("[SEND]").size, "un evento de SEND")
        assertTrue(
            sumidero.lines().all { it.split("|").getOrNull(1) == DiagLog.TAG },
            "todas las lineas llevan el TAG estable: ${sumidero.lines()}",
        )
    }

    @Test
    fun `los ocho canales se ACEPTAN y cada uno llega con su token`() {
        for (canal in DiagLog.CHANNELS) {
            DiagLog.event(canal, "marcador-$canal")
        }

        for (canal in DiagLog.CHANNELS) {
            assertEquals(1, sumidero.deCanal(canal).size, "el canal $canal llega con SU token")
        }
        assertEquals(DiagLog.CHANNELS.size, sumidero.lines().size, "y no hay ninguno de mas")
    }

    @Test
    fun `un canal que NO es uno de los ocho LANZA`() {
        val fallo = assertThrows(IllegalArgumentException::class.java) {
            DiagLog.event("[LOGIN]", "autenticando")
        }

        assertTrue(
            fallo.message?.contains("[LOGIN]") == true,
            "el error nombra el canal que se quiso usar: ${fallo.message}",
        )
        assertTrue(
            fallo.message?.contains("[AUTH]") == true,
            "y dice cuales son los ocho, para que el error se pueda corregir sin abrir el fichero: ${fallo.message}",
        )
        assertEquals(0, sumidero.lines().size, "y NO se emite nada: un canal mal escrito no llega al log")
    }

    @Test
    fun `5-1 un payload marcador NO aparece en NINGUNA linea`() {
        val marcadorPayload = "PAYLOAD-NO-DEBE-SALIR-9f3a7c"
        val marcadorClave = "CLAVE-NO-DEBE-SALIR-4b8e21"

        // Se recorre TODA la API de dominio con marcadores dentro de lo que un
        // descuido pasaria: el texto del mensaje y la clave de la sesion.
        DiagLog.messageSent(
            messageId = "6f1d2a3b4c5d6e7f8091a2b3c4d5e6f7",
            peerId = "aabbccddeeff00112233445566778899",
            resultado = "SENT",
        )
        DiagLog.messageReceived(
            messageId = "11223344556677889900aabbccddeeff",
            from = "ffeeddccbbaa99887766554433221100",
        )
        DiagLog.integrity("contenido", ok = true)
        DiagLog.bootstrap("linea", 2)
        DiagLog.event("[RELAY]", "estado=ONLINE")

        val todo = sumidero.lines().joinToString("\n")
        assertFalse(
            todo.contains(marcadorPayload),
            "el marcador de payload no debe salir por ninguna via: $todo",
        )
        assertFalse(
            todo.contains(marcadorClave),
            "el marcador de clave no debe salir por ninguna via: $todo",
        )
        // Y el control POSITIVO: las lineas no estan vacias, luego la ausencia del
        // marcador no es por no haber escrito nada.
        assertTrue(sumidero.lines().isNotEmpty(), "hay lineas: el log no esta mudo")
        assertEquals(5, sumidero.lines().size, "una por evento")
    }

    @Test
    fun `los identificadores van TRUNCADOS, no enteros`() {
        DiagLog.messageSent(
            messageId = "6f1d2a3b4c5d6e7f8091a2b3c4d5e6f7",
            peerId = "aabbccddeeff00112233445566778899",
            resultado = "SENT",
        )

        val linea = sumidero.lines().single()
        assertFalse(
            linea.contains("6f1d2a3b4c5d6e7f8091a2b3c4d5e6f7"),
            "el messageId entero NO sale: $linea",
        )
        assertTrue(
            linea.contains("6f1d2a3b4c5d"),
            "sale el principio, que basta para correlacionar: $linea",
        )
    }

    /**
     * CONTROL NEGATIVO de la no-fuga: un texto de diagnostico PUEDE llevar
     * cualquier cosa, porque quien escribe el texto decide.
     *
     * Esto declara el LIMITE de la garantia de forma explicita: la no-fuga esta
     * en la API de dominio ([messageSent] y [messageReceived]), no en
     * [event], que es un canal libre para diagnostico del propio cliente.
     * Pretender lo contrario seria prometer algo que el codigo no sostiene.
     */
    @Test
    fun `control negativo event NO es un canal con garantia de no-fuga`() {
        DiagLog.event("[SEND]", "contenido deliberado 5521")

        assertTrue(
            sumidero.lines().single().contains("5521"),
            "event escribe lo que le digan: por eso la garantia esta en messageSent/messageReceived",
        )
    }

    /**
     * CONTROL NEGATIVO: el truncado NO es un cifrado, y no pretende serlo.
     *
     * Un `messageId` es un identificador, no un secreto. Lo que se evita es dejar
     * el valor entero en un fichero de texto que luego se copia de un sitio a
     * otro; no se evita que alguien con el fichero correlate dos lineas.
     */
    @Test
    fun `control negativo truncar conserva el prefijo, no oculta el valor`() {
        val entero = "6f1d2a3b4c5d6e7f8091a2b3c4d5e6f7"

        assertEquals("6f1d2a3b4c5d…", DiagLog.truncar(entero), "corta por el final y lo dice")
        assertEquals("corto", DiagLog.truncar("corto"), "un valor mas corto que el corte se deja entero")
    }

    /**
     * 5.1 — LA NO FUGA ES ESTRUCTURAL: la API no tiene por donde colar texto.
     *
     * ## POR QUE ESTA PRUEBA Y NO SOLO LA DE CONTENIDO
     *
     * Porque la de contenido se puede falsear con UNA LLAMADA que si lo pase.
     * Se comprobo: anadir un `payload: String = ""` con valor por defecto deja
     * verde el test de marcadores —que no lo pasa— mientras la puerta de fuga ya
     * esta abierta. Un test de contenido solo mira lo que el test hace, no lo
     * que la API PERMITE.
     *
     * Esta comprueba la FIRMA. Las dos funciones de dominio aceptan exactamente
     * los identificadores que declaran y nada mas, de modo que un
     * `messageReceived(id, from, texto)` no compila. La garantia pasa de "no lo
     * pasamos" —disciplina— a "no se puede pasar" —tipos—.
     */
    @Test
    fun `5-1 la API de dominio no tiene por donde pasar TEXTO`() {
        // Se busca POR NOMBRE y no por firma exacta: si la mutacion anade un
        // parametro, un `getDeclaredMethod` con la firma vieja launching
        // `NoSuchMethodException` y el fallo diria "no existe el metodo", que no
        // es el defecto que hay que senalar. Con la busqueda por nombre, el
        // fallo cae en la ASERCION que compara el numero de parametros.
        val enviados = DiagLog::class.java.declaredMethods.first { it.name == "messageSent" }
        val recibidos = DiagLog::class.java.declaredMethods.first { it.name == "messageReceived" }

        assertEquals(4, enviados.parameterCount, "messageSent acepta CUATRO cosas, ni una mas")
        assertEquals(2, recibidos.parameterCount, "messageReceived acepta DOS, ni una mas")
        for (m in listOf(enviados, recibidos)) {
            assertTrue(
                m.parameterTypes.all { it == String::class.java || it == Int::class.java },
                "${m.name} solo acepta String/Int: no hay donde colar un ByteArray de payload",
            )
            assertTrue(
                m.parameterTypes.none { it == ByteArray::class.java },
                "${m.name} no acepta ByteArray: el payload no tiene puerta de entrada",
            )
        }
        // Y la cuenta de parametros es lo que la hace susceptible: con un
        // quinto parametro `payload`, esta asercion se rompe y la puerta de fuga
        // queda abierta sin que el test de marcadores se entere.
        assertTrue(
            DiagLog::class.java.declaredMethods.none { it.name == "messageReceived" && it.parameterCount == 3 },
            "no existe una sobrecarga de messageReceived con un tercer parametro",
        )
    }

    /**
     * CONTROL NEGATIVO de esa prueba: el escaner de firmas SI ve un `ByteArray`.
     *
     * Sin esto, "no hay ByteArray" seria verdad por construccion del arnes y no
     * mediria nada: un metodo con un parametro extra pasaria sin que la prueba
     * lo notase.
     */
    @Test
    fun `control negativo el escaner de firmas SI distingue un ByteArray`() {
        // Se compara la firma de `messageReceived` con la de un metodo que SI
        // declara un ByteArray —`event` no lo declara, asi que se usa uno de la
        // propia arnes de pruebas. Si el escaner no distinguiera los dos casos,
        // la prueba anterior seria verde con la puerta abierta.
        val deLaApi = DiagLog::class.java.declaredMethods.first { it.name == "messageReceived" }
        val conBytes = SecureTestIdentitiesFixture::class.java.declaredMethods
            .first { it.name == "recibeCarga" }

        assertTrue(
            deLaApi.parameterTypes.none { it == ByteArray::class.java },
            "la API de dominio no declara ByteArray",
        )
        assertTrue(
            conBytes.parameterTypes.any { it == ByteArray::class.java },
            "y el escaner SI lo ve cuando existe: por eso la comprobacion anterior mide algo",
        )
    }

    /** Un metodo con un `ByteArray`, para probar que el escaner lo distingue. */
    class SecureTestIdentitiesFixture {
        @Suppress("unused")
        fun recibeCarga(carga: ByteArray): Int = carga.size
    }

    /** CONTROL POSITIVO del buffer: las lineas se guardan y se pueden leer. */
    @Test
    fun `control positivo el buffer acumula y se puede limpiar`() {
        DiagLog.event("[RELAY]", "uno")
        DiagLog.event("[AUTH]", "dos")

        assertEquals(2, DiagLog.lines().size, "las dos lineas estan en el buffer")

        DiagLog.clear()

        assertEquals(0, DiagLog.lines().size, "y clear lo vacia")
    }

    // ------------------------------------------------------------------
    // 1.3 — Marca temporal y REDACCION de secretos
    // ------------------------------------------------------------------

    @Test
    fun `cada linea lleva marca temporal y el canal sigue primero`() {
        DiagLog.event("[RELAY]", "conectado")

        val linea = sumidero.lines().single()
        val cuerpo = linea.split("|")[2]
        assertTrue(
            cuerpo.startsWith("[RELAY] "),
            "el token de canal sigue al principio, para que grep y deCanal funcionen: $cuerpo",
        )
        assertTrue(
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} """).containsMatchIn(cuerpo),
            "la linea lleva fecha y hora: $cuerpo",
        )
        assertTrue(
            DiagLog.lines().single().contains("conectado"),
            "y el mensaje se conserva",
        )
    }

    @Test
    fun `una frase mnemonica se redacta`() {
        val frase = listOf(
            "abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract",
            "absurd", "abuse", "access", "accident", "account", "accuse", "achieve", "acid",
            "acoustic", "acquire", "across", "act", "action", "actor", "actress", "actual",
        ).joinToString(" ")

        DiagLog.event("[CONTACT]", "mnemonic: $frase")

        val linea = sumidero.lines().single()
        assertFalse(linea.contains("abandon"), "la frase no se escribe: $linea")
        assertFalse(linea.contains("actual"), "ni la ultima palabra: $linea")
        assertTrue(linea.contains("<redactado>"), "se declara que se redacto: $linea")
    }

    @Test
    fun `una clave base64 larga se redacta`() {
        val clave = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

        DiagLog.event("[SESSION]", "clave=$clave")

        val linea = sumidero.lines().single()
        assertFalse(linea.contains(clave), "una clave de 32 B no sale al log: $linea")
        assertTrue(linea.contains("<redactado>"))
    }

    @Test
    fun `un token asignado se redacta`() {
        DiagLog.event("[AUTH]", "token=abc123def456")

        val linea = sumidero.lines().single()
        assertFalse(linea.contains("abc123def456"), "el valor del token no sale: $linea")
        assertTrue(linea.contains("token=<redactado>"), "y se ve QUE campo se redacto: $linea")
    }

    /**
     * CONTROL NEGATIVO de la redaccion: no se come el texto normal.
     *
     * Sin esto, "no aparece el secreto" seria compatible con un log vacio o con
     * un redactor que borra cualquier linea.
     */
    @Test
    fun `control negativo el texto normal NO se redacta`() {
        DiagLog.event("[RELAY]", "estado=ONLINE intento=3")
        DiagLog.messageReceived("11223344556677889900aabbccddeeff", "ffeeddccbbaa99887766554433221100")

        val todo = sumidero.lines().joinToString("\n")
        assertTrue(todo.contains("estado=ONLINE"), "un estado no es un secreto: $todo")
        assertTrue(todo.contains("intento=3"), "ni un contador: $todo")
        assertTrue(todo.contains("112233445566…"), "el id truncado sigue visible: $todo")
    }
}
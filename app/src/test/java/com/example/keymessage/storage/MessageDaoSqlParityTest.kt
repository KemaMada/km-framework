package com.example.keymessage.storage

import com.example.keymessage.build.NoFrameworkReimplementationTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T2 — EL `ORDER BY` DE ROOM, PROBADO CONTRA SQLITE DE VERDAD.
 *
 * ## POR QUE DOS PRUEBAS Y NO UNA
 *
 * - La del TEXTO fija que las cuatro consultas (`listConversation`,
 *   `observeConversation`, `dequeue`, `peekAll`) llevan el desempate. Es una
 *   guarda de forma: si alguien lo quita, se ve en el diff.
 * - La de SQLITE ejecuta la MISMA clausula contra el motor real, con dos filas
 *   que empatan en `timestamp` y se insertaron al reves. Sin esta, "ordena por
 *   messageId" seria una afirmacion sobre un string, no sobre una base de datos.
 *
 * ## QUE NO PRUEBA
 *
 * El wiring de la base GENERADA por Room (DAO real sobre `KeyMessageDatabase`):
 * eso necesita Android y queda declarado como follow-up (androidTest). La
 * reflexion sobre `@Query` tampoco valdria: la anotacion no se retiene en
 * runtime, y aunque se retuviera, leer un string no es ejecutar SQL.
 */
class MessageDaoSqlParityTest {

    private val desempate = "ORDER BY timestamp ASC, message_id ASC"

    companion object {
        fun appRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                if (File(dir, "src/main").isDirectory && File(dir, "build.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            error("No se encontro el modulo app desde user.dir=${System.getProperty("user.dir")}")
        }

        fun fuente(relativa: String): String {
            val fichero = File(appRoot(), relativa)
            assertTrue(fichero.isFile, "no se encontro $relativa")
            return NoFrameworkReimplementationTest.sinComentarios(fichero.readText())
        }

        /** Ejecuta [sql] contra el motor `sqlite3` real y devuelve la salida. */
        fun sqlite(sql: String): String {
            val proceso = ProcessBuilder("sqlite3", ":memory:", sql)
                .redirectErrorStream(true)
                .start()
            val salida = proceso.inputStream.bufferedReader().readText().trim()
            val codigo = proceso.waitFor()
            assertEquals(0, codigo, "sqlite3 fallo (exit=$codigo): $salida")
            return salida
        }
    }

    // ===================================================================
    // 1. Las cuatro consultas declaran el desempate
    // ===================================================================

    @Test
    fun `MessageDao ordena por timestamp y messageId en las dos consultas de conversacion`() {
        val fuente = fuente("src/main/java/com/example/keymessage/storage/room/dao/MessageDao.kt")
        val ocurrencias = Regex(Regex.escape(desempate)).findAll(fuente).count()

        assertEquals(2, ocurrencias, "listConversation y observeConversation deben desempatar por message_id")
        assertTrue(
            !Regex("ORDER BY timestamp ASC(?!,)").containsMatchIn(fuente),
            "no queda ninguna consulta que ordene SOLO por timestamp",
        )
    }

    @Test
    fun `OfflineQueueDao ordena por timestamp y messageId en dequeue y peekAll`() {
        val fuente = fuente("src/main/java/com/example/keymessage/storage/room/dao/OfflineQueueDao.kt")
        val ocurrencias = Regex(Regex.escape(desempate)).findAll(fuente).count()

        assertEquals(2, ocurrencias, "dequeue y peekAll deben desempatar por message_id")
        assertTrue(
            !Regex("ORDER BY timestamp ASC(?!,)").containsMatchIn(fuente),
            "no queda ninguna consulta de cola que ordene SOLO por timestamp",
        )
    }

    // ===================================================================
    // 2. La clausula, contra SQLite real
    // ===================================================================

    @Test
    fun `SQLite real ordena la conversacion por timestamp y desempata por messageId`() {
        // Insertadas b, a, c y todas con el MISMO timestamp. Sin desempate, el
        // motor devuelve orden de rowid (insercion); con el desempate, por id.
        val sql = """
            CREATE TABLE messages(
                message_id TEXT PRIMARY KEY,
                from_identity TEXT NOT NULL,
                to_identity TEXT NOT NULL,
                payload BLOB NOT NULL,
                timestamp INTEGER NOT NULL,
                state TEXT NOT NULL,
                failure_code TEXT
            );
            INSERT INTO messages VALUES('00000000-0000-0000-0000-0000000000bb','bb','aa',x'00',7000,'QUEUED',NULL);
            INSERT INTO messages VALUES('00000000-0000-0000-0000-0000000000aa','bb','aa',x'00',7000,'QUEUED',NULL);
            INSERT INTO messages VALUES('00000000-0000-0000-0000-0000000000cc','bb','aa',x'00',7000,'QUEUED',NULL);
            SELECT message_id FROM messages ORDER BY rowid;
            SELECT message_id FROM messages
                WHERE (from_identity = 'bb' AND to_identity = 'aa')
                   OR (from_identity = 'aa' AND to_identity = 'bb')
                ORDER BY timestamp ASC, message_id ASC;
        """.trimIndent()

        val lineas = sqlite(sql).lines()
        assertEquals(
            listOf(
                "00000000-0000-0000-0000-0000000000bb",
                "00000000-0000-0000-0000-0000000000aa",
                "00000000-0000-0000-0000-0000000000cc",
            ),
            lineas.take(3),
            "control: la insercion fue b, a, c (rowid)",
        )
        assertEquals(
            listOf(
                "00000000-0000-0000-0000-0000000000aa",
                "00000000-0000-0000-0000-0000000000bb",
                "00000000-0000-0000-0000-0000000000cc",
            ),
            lineas.drop(3),
            "con ORDER BY timestamp, message_id ASC el empate lo decide el ID, no el rowid",
        )
    }

    @Test
    fun `SQLite real ordena la cola offline por timestamp y desempata por messageId`() {
        val sql = """
            CREATE TABLE offline_queue(
                message_id TEXT PRIMARY KEY,
                to_identity TEXT NOT NULL,
                payload BLOB NOT NULL,
                timestamp INTEGER NOT NULL
            );
            INSERT INTO offline_queue VALUES('00000000-0000-0000-0000-0000000000bb','aa',x'00',7000);
            INSERT INTO offline_queue VALUES('00000000-0000-0000-0000-0000000000aa','aa',x'00',7000);
            INSERT INTO offline_queue VALUES('00000000-0000-0000-0000-000000000099','aa',x'00',6000);
            SELECT message_id FROM offline_queue ORDER BY timestamp ASC, message_id ASC;
        """.trimIndent()

        assertEquals(
            listOf(
                "00000000-0000-0000-0000-000000000099",
                "00000000-0000-0000-0000-0000000000aa",
                "00000000-0000-0000-0000-0000000000bb",
            ),
            sqlite(sql).lines(),
            "el mas antiguo primero y, en el empate, el messageId menor",
        )
    }

    @Test
    fun `control negativo SQLite sin desempate SI devuelve el orden de insercion`() {
        val sql = """
            CREATE TABLE mensajes(message_id TEXT PRIMARY KEY, timestamp INTEGER NOT NULL);
            INSERT INTO mensajes VALUES('b', 7000);
            INSERT INTO mensajes VALUES('a', 7000);
            SELECT message_id FROM mensajes ORDER BY timestamp ASC;
        """.trimIndent()

        assertEquals(
            listOf("b", "a"),
            sqlite(sql).lines(),
            "sin message_id ASC decide el rowid: es el defecto que el desempate cierra",
        )
    }

    // ===================================================================
    // 3. F2 — observeUltimos: el ULTIMO mensaje por par, en UNA consulta
    // ===================================================================

    @Test
    fun `MessageDao declara la subconsulta agregada de observeUltimos`() {
        val fuente = fuente("src/main/java/com/example/keymessage/storage/room/dao/MessageDao.kt")

        assertTrue(
            fuente.contains("observeUltimos"),
            "el DAO expone observeUltimos (el resumen de contacto depende de el)",
        )
        assertTrue(
            fuente.contains("ORDER BY s.timestamp DESC, s.message_id DESC LIMIT 1"),
            "y desempata por message_id para que dos consultas identicas den lo mismo",
        )
        assertTrue(
            !fuente.contains("ROW_NUMBER") && !fuente.contains("OVER ("),
            "NO se usan funciones de ventana: minSdk 28 (el motor de Room no las tiene garantizadas)",
        )
    }

    @Test
    fun `SQLite real devuelve el ultimo mensaje de CADA par en una sola consulta`() {
        // Tres pares con mensajes entrelazados. Se espera el de mayor timestamp
        // por par; y un par con empate exacto lo decide el message_id DESC.
        val sql = """
            CREATE TABLE messages(
                message_id TEXT PRIMARY KEY,
                from_identity TEXT NOT NULL,
                to_identity TEXT NOT NULL,
                payload BLOB NOT NULL,
                timestamp INTEGER NOT NULL,
                state TEXT NOT NULL,
                failure_code TEXT
            );
            -- par local/ana
            INSERT INTO messages VALUES('m1','yo','ana',x'00',1000,'SENT',NULL);
            INSERT INTO messages VALUES('m2','ana','yo',x'00',2000,'DELIVERED',NULL);
            -- par local/beto
            INSERT INTO messages VALUES('m3','yo','beto',x'00',1500,'SENT',NULL);
            -- par local/carla: EMPATE exacto en 5000, decide el id mayor
            INSERT INTO messages VALUES('m4','carla','yo',x'00',5000,'DELIVERED',NULL);
            INSERT INTO messages VALUES('m5','yo','carla',x'00',5000,'DELIVERED',NULL);
            SELECT m.message_id FROM messages AS m WHERE m.message_id = (
                SELECT s.message_id FROM messages AS s
                WHERE (s.from_identity = 'yo' AND s.to_identity = (CASE WHEN m.from_identity = 'yo'
                       THEN m.to_identity ELSE m.from_identity END))
                   OR (s.to_identity = 'yo' AND s.from_identity = (CASE WHEN m.from_identity = 'yo'
                       THEN m.to_identity ELSE m.from_identity END))
                ORDER BY s.timestamp DESC, s.message_id DESC LIMIT 1)
            ORDER BY m.message_id ASC;
        """.trimIndent()

        assertEquals(
            listOf("m2", "m3", "m5"),
            sqlite(sql).lines(),
            "el ultimo de cada par (ana=m2, beto=m3, carla=m5 por desempate) y NINGUNO de mas",
        )
    }

    @Test
    fun `control negativo el CASE del par no devuelve el mensaje de OTRO par`() {
        // Sin el `CASE` que normaliza el par, un mensaje entrante de otro par
        // colaria. Se usa una consulta INGENUA (solo `from_identity = local`) y se
        // ve que devuelve el mensaje equivocado para el par carla.
        val sql = """
            CREATE TABLE messages(
                message_id TEXT PRIMARY KEY,
                from_identity TEXT NOT NULL,
                to_identity TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            );
            INSERT INTO messages VALUES('m3','yo','beto',1500);
            INSERT INTO messages VALUES('m4','carla','yo',5000);
            SELECT s.message_id FROM messages AS s
                WHERE s.from_identity = 'yo'
                ORDER BY s.timestamp DESC, s.message_id DESC LIMIT 1;
        """.trimIndent()

        assertEquals(
            listOf("m3"),
            sqlite(sql).lines(),
            "la consulta ingenua ignora los ENTRANTES: aqui aflora el defecto que el CASE cierra",
        )
    }
}

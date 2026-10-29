package com.example.keymessage.build

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * obs #308 — Un solo sitio decodifica el PORTADOR de un contacto.
 *
 * ## QUE AFIRMA
 *
 * En `app/src/main` no hay NINGUNA llamada a `ContactBundle.decodeAndVerify`:
 * el portador que la app maneja es el `.kmc` COMPRIMIDO que produce
 * [com.example.keymessage.contact.SelfBundleExporter], y ese texto no es
 * base64url. Decodificarlo en crudo no es una variante equivalente, es leer otro
 * formato: `ENVELOPE_MALFORMED: InvalidChar`.
 *
 * La lectura correcta vive en
 * [com.example.keymessage.contact.ContactImporter.verifiedBundle] —descomprimir,
 * validar KCE, verificar— y por ella pasan la importacion Y la apertura de
 * sesion.
 *
 * ## POR QUE ES ESTRUCTURAL Y NO CONDUCTUAL
 *
 * Porque los dos llamantes que tenian el bug eran `AppContainer.abrirSesion` y
 * `AppContainer.aceptarPrekeyEntrante`, y el contenedor necesita un `Application`:
 * no hay test de JVM que ejecute esas dos lineas. El de comportamiento
 * (`SessionCarrierDecodeTest`) cubre el camino que SI se puede ejecutar; este
 * guard cierra el que no, mirando CODIGO.
 *
 * Se declara el limite: mira el texto de las fuentes. Una decodificacion cruda
 * escondida detras de otro nombre no la veria, y por eso el comportamiento se
 * prueba ademas por su camino real.
 */
class CarrierSingleDecodePointTest {

    companion object {
        /** El atajo que decodifica base64url y que esta app NO puede usar. */
        const val DECODIFICACION_CRUDA: String = "ContactBundle.decodeAndVerify("

        /** Raiz del modulo `app/`. */
        fun appRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                if (File(dir, "src/main").isDirectory && File(dir, "build.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            error("No se encontro el modulo app desde user.dir=${System.getProperty("user.dir")}")
        }

        /** Fuentes de `src/main` de la app. */
        fun fuentes(): List<File> =
            File(appRoot(), "src/main")
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()

        /**
         * Lineas de [fuente] que decodifican el portador en crudo.
         *
         * Se reusa el stripper de comentarios de [NoFrameworkReimplementationTest]
         * —entiende cadenas y bloques— porque explicar el bug en un KDoc NO es
         * cometerlo: solo cuenta la llamada en CODIGO.
         */
        fun llamadasCrudas(fuente: String): List<String> {
            val sinComentarios = NoFrameworkReimplementationTest.sinComentarios(fuente).split("\n")
            val original = fuente.split("\n")
            return sinComentarios.mapIndexedNotNull { idx, linea ->
                if (linea.contains(DECODIFICACION_CRUDA)) "${idx + 1}: ${original.getOrElse(idx) { linea }.trim()}" else null
            }
        }

        /**
         * Las tres formas de VERIFICAR un bundle.
         *
         * El orden no importa: lo que se busca es que ALGUIEN las tres fuera
         * del punto unico.
         */
        val VERIFICACIONES: List<String> = listOf(
            "Kce.validate(",
            "ContactBundle.verified(",
            "decodeAndVerify(",
        )

        /**
         * Lineas de [fuente] que verifican un bundle por su cuenta.
         *
         * Los comentarios se quitan ANTES de buscar: un KDoc que explica el
         * bug no es el bug.
         */
        fun verificacionesPropias(fuente: String): List<String> {
            val sinComentarios = NoFrameworkReimplementationTest.sinComentarios(fuente).split("\n")
            val original = fuente.split("\n")
            return sinComentarios.mapIndexedNotNull { idx, linea ->
                if (VERIFICACIONES.any { linea.contains(it) }) {
                    "${idx + 1}: ${original.getOrElse(idx) { linea }.trim()}"
                } else {
                    null
                }
            }
        }

        /** El UNICO fichero de la app que puede verificar un bundle. */
        val PUNTO_UNICO: String = "src/main/java/com/example/keymessage/contact/ContactImporter.kt"

        /** Si [fichero] es el punto unico de verificacion. */
        fun esElPuntoUnico(fichero: File): Boolean =
            fichero.relativeTo(appRoot()).path.replace(File.separatorChar, '/') == PUNTO_UNICO
    }

    @Test
    fun `ninguna fuente de la app decodifica el portador en crudo`() {
        val ficheros = fuentes()
        assertTrue(ficheros.isNotEmpty(), "no se encontro ninguna fuente en src/main de la app")

        val culpables = ficheros.flatMap { f ->
            llamadasCrudas(f.readText()).map { "${f.relativeTo(appRoot()).path}:$it" }
        }
        assertEquals(
            emptyList<String>(),
            culpables,
            "PORTADOR DECODIFICADO EN CRUDO: el texto es un .kmc COMPRIMIDO y eso da " +
                "ENVELOPE_MALFORMED. Usa ContactImporter.verifiedBundle: ${culpables.joinToString(" | ")}",
        )
    }

    @Test
    fun `la app SI tiene el punto unico que lee el portador`() {
        // El otro lado del guard: que el test anterior no pase por VACIO, es
        // decir, que exista una via real de lectura del portador.
        val conPuntoUnico = fuentes().filter { it.readText().contains("fun verifiedBundle(") }
        assertTrue(
            conPuntoUnico.isNotEmpty(),
            "tiene que existir ContactImporter.verifiedBundle: sin el, esta app no sabe leer su propio portador",
        )
    }

    @Test
    fun `control negativo el guard ve la llamada y no la confunde con la prosa`() {
        val fuga = """
            class AppContainer {
                fun abrir(contacto: VerifiedContact) {
                    val bundle = ContactBundle.decodeAndVerify(contacto.bundleText)
                }
            }
        """.trimIndent()
        assertEquals(1, llamadasCrudas(fuga).size, "el guard VE la decodificacion cruda")

        val limpio = """
            class AppContainer {
                /**
                 * Antes: ContactBundle.decodeAndVerify(bundleText), que era el bug.
                 */
                fun abrir(contacto: VerifiedContact) {
                    val bundle = ContactImporter.verifiedBundle(contacto)
                }
            }
        """.trimIndent()
        assertEquals(
            emptyList<String>(),
            llamadasCrudas(limpio),
            "una mencion en un KDoc no es una llamada, y verifiedBundle no es el atajo crudo",
        )
    }

    // ==================================================================
    // El guard AMPLIADO: la VERIFICACION, no solo la decodificacion
    // ==================================================================

    @Test
    fun `ninguna fuente de la app VERIFICA el bundle por su cuenta`() {
        // El guard de arriba miraba una sola llamada: la decodificacion CRUDA.
        // Este mira las TRES formas de verificar un bundle, que es lo que hace
        // que dos caminos puedan divergir:
        //
        //   - `Kce.validate(...)`      : el sobre KCE, sin comprobar la firma.
        //   - `ContactBundle.verified` : la firma de la raiz.
        //   - `decodeAndVerify(...)`   : la decodificacion cruda de antes.
        //
        // Solo `ContactImporter` puede hacerlas. El por que es el mismo de
        // siempre: el portador COMPRIMIDO se lee de una sola manera, y un
        // segundo lector es un segundo formato (obs #308).
        val culpables = fuentes()
            .filterNot { esElPuntoUnico(it) }
            .flatMap { f ->
                verificacionesPropias(f.readText()).map { "${f.relativeTo(appRoot()).path}:$it" }
            }
        assertEquals(
            emptyList<String>(),
            culpables,
            "VERIFICACION FUERA DEL PUNTO UNICO: solo ContactImporter puede validar, " +
                "descomprimir y verificar un portador: ${culpables.joinToString(" | ")}",
        )
    }

    @Test
    fun `la app DELEGA en el codec compartido y no verifica por su cuenta`() {
        // G1 movio el orden decompress -> Kce.validate -> verified a
        // `com.km.identity.CarrierCodec`. Desde entonces la app no debe tener
        // NINGUNA verificacion propia: se comprueba que no queda ninguna y que
        // el punto de entrada delega de verdad (si no, el guard pasaria por
        // vacio).
        val queVerifican = fuentes().filter { verificacionesPropias(it.readText()).isNotEmpty() }
        assertEquals(
            emptyList<String>(),
            queVerifican.map { it.relativeTo(appRoot()).path.replace(File.separatorChar, '/') },
            "el portador se verifica SOLO en km-core (CarrierCodec); la app no repite el orden",
        )

        val queDelegan = fuentes().filter { it.readText().contains("CarrierCodec.decode(") }
        assertEquals(
            listOf(PUNTO_UNICO),
            queDelegan.map { it.relativeTo(appRoot()).path.replace(File.separatorChar, '/') }.sorted(),
            "y ContactImporter es el unico sitio que llama al codec compartido",
        )
    }

    @Test
    fun `control negativo el guard ampliado ve las TRES formas de verificar`() {
        val fuga = """
            class AppContainer {
                fun abrir(bytes: ByteArray, texto: String) {
                    val mapa = com.km.identity.Kce.validate(bytes) as Map<String, Any?>
                    val verificado = ContactBundle.verified(mapa, null)
                    val crudo = ContactBundle.decodeAndVerify(texto)
                }
            }
        """.trimIndent()
        assertEquals(
            3,
            verificacionesPropias(fuga).size,
            "el guard ve Kce.validate, ContactBundle.verified y decodeAndVerify",
        )

        val limpio = """
            class AppContainer {
                /**
                 * Antes: Kce.validate, ContactBundle.verified y decodeAndVerify.
                 */
                fun abrir(contacto: VerifiedContact) {
                    val bundle = ContactImporter.verifiedBundle(contacto)
                }
            }
        """.trimIndent()
        assertEquals(
            emptyList<String>(),
            verificacionesPropias(limpio),
            "una mencion en un KDoc no es una llamada",
        )
    }
}

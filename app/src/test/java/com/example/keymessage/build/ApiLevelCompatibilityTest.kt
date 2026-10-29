package com.example.keymessage.build

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 4.1 — Compatibilidad con el nivel de API que la app PROMETE.
 *
 * ## QUE AFIRMA
 *
 * Que nada de lo que viaja en la APK llama a una API de Android posterior al
 * `minSdk` declarado. Concretamente, que ninguna clase de `com.km` (el
 * framework), ni de `com.example.keymessage` (la app), referencia un miembro de
 * `java.*` o `android.*` que el runtime de Android no tiene todavia.
 *
 * ## POR QUE ESTE GUARDA EXISTE
 *
 * Porque el fallo es INVISIBLE hasta que se ejecuta en un dispositivo viejo.
 *
 * El caso que la motivo: `AgreementKeyGuard` (km-core) calculaba el primo de
 * Curve25519 con `BigInteger.TWO`. `BigInteger.TWO` es un campo estatico que
 * Java lo define desde Java 9 y que **Android solo expone desde API 33**. La app
 * declara `minSdk = 28`, de modo que en un Galaxy S8+ (Android 9 / API 28) la
 * inicializacion del `object` reventaba con
 * `NoSuchFieldError: No static field TWO of type Ljava/math/BigInteger`, la
 * sesion E2E no se abria y el envio terminaba en `AUTH_FAILED`. En el
 * emulador (API 37) no se reproducia: el fallo solo aparecia en hardware
 * antiguo.
 *
 * El CODIGO compila sin errores porque el JDK de compilacion (11) si tiene
 * `BigInteger.TWO`. El fallo es de ENLACE, y el enlace solo ocurre en el
 * dispositivo.
 *
 * ## COMO SE COMPRUEBA
 *
 * Con la TABLA DE NIVELES DE API DEL PROPIO SDK, no con una lista escrita a
 * mano: el `api-versions.xml` de cada plataforma (`platforms/android-NN/data/`)
 * dice, para cada clase y cada miembro, desde que nivel de API existe. Se lee
 * esa tabla, se leen los `.class` (el constant pool, donde vive cada
 * referencia) y se cruzan.
 *
 * Se mira el BYTECODE y no el fuente porque el bytecode es lo que viaja: un
 * fuente puede estar limpio y el `.class` guardar una referencia heredada de
 * otro sitio.
 *
 * ## EL MINSDK NO SE SUBA
 *
 * Subir `minSdk` a 33 haria desaparecer el bug sin tocar una linea de
 * criptografia, y perderia a los dispositivos con API 28 a 32 a cambio. La
 * politica del proyecto es SOPORTAR API 28, asi que este test afirma que el
 * `minSdk` declarado no sube por encima de 28.
 *
 * ## CONTROL
 *
 * [control el detector ve la API prohibida y no inventa] ejercita el detector
 * con un `.class` sintetico que SI referencia `BigInteger.TWO` (tiene que
 * verlo) y con uno que referencia `BigInteger.ZERO` (no tiene que inventar
 * nada). Un detector que nunca dispara daria verde para siempre.
 */
class ApiLevelCompatibilityTest {

    companion object {

        /** El nivel de API mas ALTO que la app promete soportar. */
        const val MIN_SDK_ESPERADO = 28

        /** Clases de las que se toma el artefacto a escanear. */
        const val CLASE_APP = "com.example.keymessage.AppContainer"
        const val CLASE_KM_CORE = "com.km.crypto.AgreementKeyGuard"
        const val CLASE_KM_TRANSPORT = "com.km.transport.JvmRelayTransport"

        /**
         * Una referencia a un miembro, tal y como queda en el constant pool.
         */
        data class Referencia(val tipo: String, val clase: String, val nombre: String, val descriptor: String) {
            override fun toString(): String = "$clase#$nombre$descriptor"
        }

        // =================================================================
        // 1. Dónde está el SDK y dónde está la tabla de niveles de API
        // =================================================================

        /** La raiz del repositorio (misma regla que [RootBuildIsolationTest]). */
        fun repoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile && File(dir, "gradle/libs.versions.toml").isFile) {
                    return dir
                }
                dir = dir.parentFile
            }
            error("No se encontro la raiz del repositorio desde user.dir=${System.getProperty("user.dir")}")
        }

        /** El modulo `app/`. */
        fun appRoot(): File {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            while (dir != null) {
                if (File(dir, "src/main").isDirectory && File(dir, "build.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            error("No se encontro el modulo app desde user.dir=${System.getProperty("user.dir")}")
        }

        /** El SDK de Android: entorno, `local.properties` o el sitio de siempre. */
        fun sdkRoot(): File? {
            val candidatos = mutableListOf<File>()
            System.getenv("ANDROID_HOME")?.let { candidatos += File(it) }
            System.getenv("ANDROID_SDK_ROOT")?.let { candidatos += File(it) }
            listOf(File(appRoot(), "local.properties"), File(repoRoot(), "local.properties")).forEach { props ->
                if (props.isFile) {
                    val dir = props.readLines()
                        .firstOrNull { it.trim().startsWith("sdk.dir=") }
                        ?.substringAfter("=", "")
                        ?.trim()
                    if (!dir.isNullOrEmpty()) candidatos += File(dir)
                }
            }
            candidatos += File(System.getProperty("user.home"), "Android/Sdk")
            return candidatos.map { it.absoluteFile }.firstOrNull { it.isDirectory }
        }

        /**
         * La tabla de niveles de API del SDK: el `api-versions.xml` de la
         * plataforma mas NUEVA que se tenga instalada.
         */
        fun apiVersions(): File? {
            val sdk = sdkRoot() ?: return null
            val plataformas = File(sdk, "platforms")
            if (!plataformas.isDirectory) return null
            return plataformas.listFiles { f -> f.isDirectory }
                ?.map { File(it, "data/api-versions.xml") }
                ?.filter { it.isFile }
                ?.maxByOrNull { f ->
                    f.parentFile?.parentFile?.name?.filter(Char::isDigit)?.toIntOrNull() ?: 0
                }
        }

        /**
         * La tabla de niveles de API: clase -> nivel, y (clase, miembro) -> nivel.
         *
         * Un miembro SIN atributo `since` existe desde API 1. Un miembro CON
         * `since="33"` solo existe a partir de API 33.
         */
        fun nivelesDeApi(fichero: File): Pair<Map<String, Int>, Map<String, Int>> {
            val desdeClase = HashMap<String, Int>()
            val desdeMiembro = HashMap<String, Int>()
            var claseActual: String? = null
            var miembroActual: Pair<String, Int>? = null
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newSAXParser()
            parser.parse(
                fichero,
                object : org.xml.sax.helpers.DefaultHandler() {
                    override fun startElement(
                        uri: String?,
                        localName: String?,
                        qName: String?,
                        attributes: org.xml.sax.Attributes?,
                    ) {
                        when (qName) {
                            "class" -> {
                                claseActual = attributes?.getValue("name")
                                val since = attributes?.getValue("since")?.toIntOrNull()
                                val clase = claseActual
                                if (clase != null && since != null) desdeClase[clase] = since
                            }
                            "field", "method" -> {
                                val nombre = attributes?.getValue("name")
                                val since = attributes?.getValue("since")?.toIntOrNull()
                                miembroActual = if (nombre != null && since != null) nombre to since else null
                            }
                        }
                    }

                    override fun endElement(uri: String?, localName: String?, qName: String?) {
                        when (qName) {
                            "field", "method" -> {
                                val miembro = miembroActual
                                val clase = claseActual
                                if (miembro != null && clase != null) {
                                    desdeMiembro["$clase#${miembro.first}"] = miembro.second
                                }
                                miembroActual = null
                            }
                            "class" -> claseActual = null
                        }
                    }
                },
            )
            return desdeClase to desdeMiembro
        }

        // =================================================================
        // 2. Lectura del constant pool
        // =================================================================

        /**
         * Las referencias a CAMPOS y METODOS de otras clases que hay en un
         * `.class`.
         *
         * Se leen a mano porque una referencia a un miembro que no existe en el
         * runtime no se puede resolver por reflexion: la excepcion se produce
         * al ENLAZAR, no al preguntar. El constant pool, en cambio, es un
         * fichero con indices y se puede leer entero.
         */
        fun referenciasDe(bytes: ByteArray): List<Referencia> {
            val d = DataInputStream(ByteArrayInputStream(bytes))
            require(d.readInt() == 0xCAFEBABE.toInt()) { "no es un fichero de clase" }
            d.readUnsignedShort() // minor
            d.readUnsignedShort() // major
            val count = d.readUnsignedShort()

            val utf8 = arrayOfNulls<String>(count)
            val clases = arrayOfNulls<Int>(count)
            val nameAndType = arrayOfNulls<Pair<Int, Int>>(count)
            val refs = arrayOfNulls<Triple<String, Int, Int>>(count)

            var i = 1
            while (i < count) {
                when (val tag = d.readUnsignedByte()) {
                    1 -> { val len = d.readUnsignedShort(); utf8[i] = String(d.readNBytes(len), Charsets.UTF_8) }
                    3 -> d.readInt()
                    4 -> d.readInt()
                    5 -> { d.readLong(); i++ }
                    6 -> { d.readDouble(); i++ }
                    7 -> clases[i] = d.readUnsignedShort()
                    8 -> d.readUnsignedShort()
                    9 -> refs[i] = Triple("campo", d.readUnsignedShort(), d.readUnsignedShort())
                    10, 11 -> refs[i] = Triple("metodo", d.readUnsignedShort(), d.readUnsignedShort())
                    12 -> nameAndType[i] = d.readUnsignedShort() to d.readUnsignedShort()
                    15 -> { d.readUnsignedByte(); d.readUnsignedShort() }
                    16 -> d.readUnsignedShort()
                    17, 18 -> { d.readUnsignedShort(); d.readUnsignedShort() }
                    19, 20 -> d.readUnsignedShort()
                    else -> error("etiqueta de constant pool desconocida: $tag")
                }
                i++
            }

            val salida = mutableListOf<Referencia>()
            for (indice in 1 until count) {
                val ref = refs[indice] ?: continue
                val nombreClase = utf8[clases[ref.second] ?: continue] ?: continue
                val nat = nameAndType[ref.third] ?: continue
                val nombre = utf8[nat.first] ?: continue
                val descriptor = utf8[nat.second] ?: ""
                salida += Referencia(ref.first, nombreClase, nombre, descriptor)
            }
            return salida
        }

        /**
         * Un `.class` sintetico que referencia el campo indicado de
         * `java.math.BigInteger`.
         *
         * Se construye a mano para que el control no dependa de un compilador
         * en tiempo de test.
         */
        fun classFileQueReferencia(campo: String): ByteArray {
            val out = ByteArrayOutputStream()
            val d = DataOutputStream(out)
            d.writeInt(0xCAFEBABE.toInt())
            d.writeShort(0)
            d.writeShort(52)
            d.writeShort(9)
            d.writeByte(1); d.writeUTF("java/math/BigInteger")
            d.writeByte(1); d.writeUTF(campo)
            d.writeByte(1); d.writeUTF("Ljava/math/BigInteger;")
            d.writeByte(7); d.writeShort(1)
            d.writeByte(12); d.writeShort(2); d.writeShort(3)
            d.writeByte(9); d.writeShort(4); d.writeShort(5)
            d.writeByte(1); d.writeUTF("Sintetico")
            d.writeByte(7); d.writeShort(7)
            d.writeShort(0x0021)
            d.writeShort(8)
            d.writeShort(0)
            d.writeShort(0)
            d.writeShort(0)
            d.writeShort(0)
            d.writeShort(0)
            d.flush()
            return out.toByteArray()
        }
    }

    // ===================================================================
    // REAL 1: el minSdk no sube
    // ===================================================================

    /** El `minSdk` declarado en el build de la app. */
    private fun minSdkDeclarado(): Int {
        val build = File(appRoot(), "build.gradle.kts")
        assertTrue(build.isFile, "no existe app/build.gradle.kts")
        val texto = build.readText()
        val m = Regex("""minSdk\s*=\s*(\d+)""").find(texto)
        assertTrue(m != null, "no se encuentra minSdk en app/build.gradle.kts")
        return m!!.groupValues[1].toInt()
    }

    @Test
    fun `la app no sube su minSdk por encima de 28`() {
        val minSdk = minSdkDeclarado()
        assertTrue(
            minSdk <= MIN_SDK_ESPERADO,
            "la app declara minSdk = $minSdk y la politica es soportar API 28: " +
                "subir el minSdk haria desaparecer los fallos de nivel de API " +
                "a costa de perder los dispositivos con API 28 a 32",
        )
    }

    // ===================================================================
    // REAL 2: el bytecode que viaja no llama a APIs posteriores al minSdk
    // ===================================================================

    /** El artefacto (directorio o JAR) del que sale una clase. */
    private fun codeSourceDe(clase: String): File {
        val c = Class.forName(clase)
        val dominio = c.protectionDomain ?: error("$clase no tiene protection domain")
        val codigo = dominio.codeSource ?: error("$clase no tiene code source")
        return File(codigo.location.toURI())
    }

    /** Todos los `.class` de un artefacto, con su nombre relativo. */
    private fun clasesDe(artefacto: File): List<Pair<String, ByteArray>> {
        val salida = mutableListOf<Pair<String, ByteArray>>()
        if (artefacto.isDirectory) {
            artefacto.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { salida += it.relativeTo(artefacto).path to it.readBytes() }
        } else {
            ZipFile(artefacto).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .forEach { e -> salida += e.name to zip.getInputStream(e).use { it.readBytes() } }
            }
        }
        return salida
    }

    @Test
    fun `el bytecode que viaja no llama a ninguna API posterior al minSdk`() {
        val tabla = apiVersions()
        assertTrue(
            tabla != null,
            "no se encuentra api-versions.xml del SDK: sin esa tabla no se puede " +
                "afirmar nada sobre el nivel de API. SDK buscado en ${sdkRoot()}",
        )
        val (desdeClase, desdeMiembro) = nivelesDeApi(tabla!!)
        assertTrue(desdeClase.isNotEmpty(), "la tabla de niveles de API esta vacia: $tabla")

        val minSdk = minSdkDeclarado()

        val artefactos = mapOf(
            "app" to codeSourceDe(CLASE_APP),
            "km-core" to codeSourceDe(CLASE_KM_CORE),
            "km-transport" to codeSourceDe(CLASE_KM_TRANSPORT),
        )

        val infracciones = mutableListOf<String>()
        val desconocidos = mutableListOf<String>()
        var escaneadas = 0

        for ((nombre, artefacto) in artefactos) {
            val clases = clasesDe(artefacto)
            escaneadas += clases.size
            for ((clase, bytes) in clases) {
                for (ref in referenciasDe(bytes)) {
                    val claseAndroid = ref.clase
                    if (!claseAndroid.startsWith("java/") && !claseAndroid.startsWith("android/")) continue
                    val nivelClase = desdeClase[claseAndroid]
                    val clave = if (ref.tipo == "campo") {
                        "${claseAndroid}#${ref.nombre}"
                    } else {
                        "${claseAndroid}#${ref.nombre}${ref.descriptor}"
                    }
                    val nivelMiembro = desdeMiembro[clave]
                    val nivel = nivelMiembro ?: nivelClase
                    when {
                        nivel != null && nivel > minSdk -> infracciones +=
                            "$nombre: $clase -> $clave (desde API $nivel)"
                        nivel == null -> desconocidos += "$nombre: $clase -> $clave"
                    }
                }
            }
        }

        assertTrue(
            escaneadas > 500,
            "el barrido solo ha mirado $escaneadas clases: no esta barriendo el " +
                "artefacto entero y no puede afirmar nada",
        )
        assertTrue(
            infracciones.isEmpty(),
            "el bytecode que viaja llama a APIs posteriores a minSdk = $minSdk:\n" +
                infracciones.joinToString("\n") { "  - $it" },
        )
        // Los desconocidos se INFORMAN: son clases que el SDK de Android no
        // documenta (por ejemplo `java.lang.invoke`, que D8 desugara). No son
        // una infraccion por si mismos, pero no pueden quedarse mudos.
        if (desconocidos.isNotEmpty()) {
            println("[API] ${desconocidos.size} referencias a miembros que no estan en api-versions.xml")
        }
    }

    // ===================================================================
    // REAL 3: control del detector
    // ===================================================================

    @Test
    fun `control el detector ve la API prohibida y no inventa`() {
        val tabla = apiVersions()
        assertTrue(tabla != null, "no se encuentra api-versions.xml del SDK")
        val (desdeClase, desdeMiembro) = nivelesDeApi(tabla!!)
        val minSdk = minSdkDeclarado()

        fun nivelDe(campo: String): Int? {
            val nivelMiembro = desdeMiembro["java/math/BigInteger#$campo"]
            return nivelMiembro ?: desdeClase["java/math/BigInteger"]
        }

        // NEGATIVO: `TWO` es API 33 y tiene que estar en la tabla como tal.
        assertEquals(33, nivelDe("TWO"), "BigInteger.TWO es API 33: tiene que estar en la tabla")  // API-LEVEL-FIXTURE
        assertTrue(
            nivelDe("TWO")!! > minSdk,
            "BigInteger.TWO tiene que ser una infraccion con minSdk = $minSdk",  // API-LEVEL-FIXTURE
        )

        // Y `TEN` NO es una infraccion: la `libcore` de Android lo tiene desde
        // API 1 aunque el JDK lo anadio en Java 9. Una sonda en el Galaxy S8+
        // real (API 28) responde `BigInteger.TEN = 10`. Si la tabla del SDK
        // cambiara, este test lo diria.
        assertTrue(
            (nivelDe("TEN") ?: 1) <= minSdk,
            "BigInteger.TEN tiene que estar disponible en API 28: la sonda del " +
                "Galaxy S8+ lo encuentra y la tabla del SDK no le pone nivel",
        )

        // Y el detector tiene que VERLA en un `.class` sintetico.
        val detectada = referenciasDe(classFileQueReferencia("TWO"))
            .filter { it.tipo == "campo" && it.clase == "java/math/BigInteger" }
            .map { it.nombre }
        assertEquals(listOf("TWO"), detectada, "el detector tiene que ver la referencia prohibida")

        // POSITIVO: `ZERO` es API 1 y no puede ser una infraccion.
        val noDetectada = referenciasDe(classFileQueReferencia("ZERO"))
            .filter { it.tipo == "campo" && it.clase == "java/math/BigInteger" }
            .filter { (desdeMiembro["java/math/BigInteger#${it.nombre}"] ?: desdeClase["java/math/BigInteger"] ?: 1) > minSdk }
        assertTrue(noDetectada.isEmpty(), "el detector no puede inventar una infraccion: $noDetectada")
    }

    // ===================================================================
    // REAL 4: barrido de FUENTE (main y test) en todo el repositorio
    // ===================================================================

    /**
     * Los campos estaticos de `java.math.BigInteger` que NO existen en API 28.
     *
     * Solo `TWO`: `ZERO` y `ONE` existen desde API 1, y `TEN` tambien (la
     * `libcore` de Android lo tiene desde API 1 aunque el JDK lo anadio en
     * Java 9). Una sonda ejecutada en el Galaxy S8+ real (API 28) responde
     * `BigInteger.TEN = 10` y `BigInteger.TWO AUSENTE (NoSuchFieldException)`.
     *
     * `TWO` es el que rompia la sesion E2E en el S8+, y la tabla del propio
     * SDK lo confirma: `java/math/BigInteger` declara `TWO` con
     * `since="33"` y `TEN` sin atributo `since`.
     */
    private val CAMPOS_DE_BIGINTEGER_PROHIBIDOS = listOf("TWO")

    /**
     * Las fuentes que se barren: el `main` y el `test` de los tres modulos
     * que acaban en la APK.
     */
    private fun fuentes(): List<File> {
        val raiz = repoRoot()
        val carpetas = listOf(
            "app/src/main",
            "app/src/test",
            "km-core/src/main",
            "km-core/src/test",
            "km-transport/src/main",
            "km-transport/src/test",
        )
        return carpetas.map { File(raiz, it) }
            .filter { it.isDirectory }
            .flatMap { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                    .toList()
            }
    }

    /**
     * El codigo de un fichero Kotlin/Java, SIN comentarios y conservando el
     * numero de linea (los comentarios de bloque se sustituyen por ESPACIOS,
     * no se borran).
     *
     * Conservar las lineas es lo que permite que la excepcion se pueda
     * justifiable POR LINEA: ver [MARCADOR_DE_FICHA].
     */
    private fun sinComentarios(texto: String): String =
        texto.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { m ->
            m.value.replace(Regex("[^\n]"), " ")
        }.lines().joinToString("\n") { it.substringBefore("//") }

    /**
     * Marca una linea que USA el token prohibido a proposito.
     *
     * Solo la usan las MUESTRAS del propio detector (que tienen que contener
     * el uso prohibido para poder afirmar que lo detectan). Sin esta marca,
     * el detector se detectaria a si mismo y el barrido de fuentes no podria
     * afirmar nada.
     */
    private val MARCADOR_DE_FICHA = "API-LEVEL-FIXTURE"

    @Test
    fun `las fuentes de main y test no usan campos de BigInteger posteriores a API 1`() {
        val ofensas = mutableListOf<String>()
        val justificadas = mutableListOf<String>()
        for (fichero in fuentes()) {
            val original = fichero.readText()
            val lineasOriginal = original.lines()
            val lineasCodigo = sinComentarios(original).lines()
            for (indice in lineasCodigo.indices) {
                val codigo = lineasCodigo[indice]
                for (campo in CAMPOS_DE_BIGINTEGER_PROHIBIDOS) {
                    if (!Regex("""BigInteger\s*\.\s*$campo\b""").containsMatchIn(codigo)) continue
                    val lineaOriginal = lineasOriginal.getOrNull(indice).orEmpty()
                    if (lineaOriginal.contains(MARCADOR_DE_FICHA)) {
                        justificadas += "${fichero.path}:${indice + 1} ($campo, ficha del detector)"
                    } else {
                        ofensas += "${fichero.path}:${indice + 1}: BigInteger.$campo"
                    }
                }
            }
        }
        assertTrue(
            ofensas.isEmpty(),
            "fuentes que usan un campo de java.math.BigInteger posterior a API 1:\n" +
                ofensas.joinToString("\n") { "  - $it" },
        )
        assertTrue(
            justificadas.size <= 8,
            "fichas justificadas de mas: el detector no puede crecer sin control. $justificadas",
        )
    }

    @Test
    fun `control el detector de fuente ve el campo prohibido y no inventa`() {
        val prohibido = "val p = BigInteger.TWO.pow(255)"  // API-LEVEL-FIXTURE
        val limpio = "val p = BigInteger.valueOf(2).pow(255)"
        val enComentario = "// BigInteger.TWO se evita a proposito"

        val detecta = { texto: String ->
            sinComentarios(texto).let { codigo ->
                CAMPOS_DE_BIGINTEGER_PROHIBIDOS.any { campo ->
                    Regex("""BigInteger\s*\.\s*$campo\b""").containsMatchIn(codigo)
                }
            }
        }

        assertTrue(detecta(prohibido), "el detector tiene que ver el uso prohibido")
        assertTrue(!detecta(limpio), "el detector no puede inventar un uso prohibido")
        assertTrue(!detecta(enComentario), "un comentario no es un uso")
    }
}

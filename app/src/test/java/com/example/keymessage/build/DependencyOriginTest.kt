package com.example.keymessage.build

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry

/**
 * 0.3 — De donde sale cada clase, medido sobre el CLASSPATH REAL de la suite.
 *
 * ## QUE AFIRMA
 *
 * Que el framework que consume la app es el de los artefactos, y que no ha
 * vuelto a colarse la pila de escritorio:
 *
 *  - `com/km/transport/RelayClientStack.class` existe: el motor de rele de p4 es
 *    el que hay en el grafo.
 *  - `com/km/api/KeyMessageCoreImpl.class` existe: km-core es el nucleo.
 *  - CERO entradas `dev/onvoid/webrtc/` (webrtc-java, la libreria de ESCRITORIO
 *    con JNI, que es la que usa `km-webrtc`) y cero `org/webrtc/` (el runtime de
 *    WebRTC). La version P3 de la app es RELE-ONLY.
 *
 * ## POR QUE SOBRE EL CLASSPATH Y NO SOBRE EL `build.gradle.kts`
 *
 * Leer el build script prueba lo que alguien ESCRIBIO; el classpath prueba lo que
 * esta RESUELTO, que es lo unico que llega al proceso. Una dependencia transitiva
 * que nadie declaro —`webrtc-sdk` entrando por una sola linea, o `km-webrtc`
 * colado por un `api(...)`— no aparece en el build script y si en la classpath.
 * Por eso el escaneo es de entradas REALES de jar, con nombres de entrada, y no
 * del texto del build.
 *
 * ## CONTROL NEGATIVO
 *
 * [entradasProhibidas] es una funcion PURA: los tests `control*` la alimentan con
 * nombres sinteticos y afirman que los ve, y con nombres limpios y afirman que
 * no inventa. Y hay un control POSITIVO de verdad: se construye un JAR con una
 * entrada prohibida dentro, en memoria, y se comprueba que el escaner la ve —
 * porque un escaner que solo mirase la lista de ficheros de la classpath pasaria
 * estos tests sin mirar un solo byte de contenido.
 */
class DependencyOriginTest {

    companion object {
        /** Paquetes jamas permitidos en el grafo de la app. */
        val PAQUETES_PROHIBIDOS_SIEMPRE: List<String> = listOf("dev/onvoid/webrtc/")

        /**
         * Runtime de WebRTC: `org/webrtc`.
         *
         * Ya no esta prohibido en si mismo: la app tiene P2P en Android. Lo que
         * sigue prohibido es que entre por ARRASTRE sin el artefacto que lo
         * justifica. La puerta es [ARTEFACTO_P2P_ANDROID].
         */
        val PAQUETE_WEBRTC: String = "org/webrtc/"

        /** Clase que solo puede venir de `com.km:km-webrtc-android`. */
        val ARTEFACTO_P2P_ANDROID: String = "com/km/webrtc/android/AndroidP2pProvider.class"

        /** Clases del framework que tienen que estar presentes. */
        val CLASES_EXIGIDAS: List<String> = listOf(
            "com/km/transport/RelayClientStack.class",
            "com/km/api/KeyMessageCoreImpl.class",
        )

        /**
         * Entradas de [entries] con nombre de clase dentro de un paquete
         * prohibido.
         *
         * Es PURA a proposito: recibe nombres de entrada ya extraidos y decide. El
         * recorrido real de jar y directorios vive en [escanearEntrada], que la
         * usa.
         */
        fun entradasProhibidas(entries: List<String>, p2pAndroidPresente: Boolean = false): List<String> =
            entries.filter { nombre ->
                PAQUETES_PROHIBIDOS_SIEMPRE.any { nombre.contains(it) } ||
                    (!p2pAndroidPresente && nombre.contains(PAQUETE_WEBRTC))
            }.sorted()

        /** Todas las entradas de la classpath real de la suite. */
        fun classpathReal(): List<File> =
            System.getProperty("java.class.path")
                ?.split(File.pathSeparator)
                ?.filter { it.isNotBlank() }
                ?.map { File(it) }
                .orEmpty()

        /** Nombres de entrada (ficheros .class) que hay dentro de [root]. */
        fun escanearEntrada(root: File): List<String> = when {
            root.isDirectory ->
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .map { it.relativeTo(root).invariantSeparatorsPath }
                    .toList()

            root.isFile && root.name.endsWith(".jar") ->
                java.util.zip.ZipFile(root).use { zip ->
                    zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .map { it.name }
                        .toList()
                }

            else -> emptyList()
        }
    }

    // --- Real 1: las clases del framework estan ---

    @Test
    fun `las clases del framework estan en el classpath`() {
        val entradas = classpathReal()
        assertTrue(entradas.isNotEmpty(), "java.class.path vacia: no se puede medir nada")

        val presentes = CLASES_EXIGIDAS.filter { recurso ->
            DependencyOriginTest::class.java.classLoader.getResource(recurso) != null
        }
        assertEquals(
            CLASES_EXIGIDAS.toSet(),
            presentes.toSet(),
            "faltan clases del framework en el classpath real de la suite",
        )
    }

    // --- Real 2: no hay WebRTC de escritorio ni su runtime ---

    @Test
    fun `la classpath no lleva WebRTC de escritorio ni su runtime`() {
        val entradas = classpathReal()
        assertTrue(entradas.isNotEmpty(), "java.class.path vacia: no se puede verificar la ausencia")

        val p2pAndroid = DependencyOriginTest::class.java.classLoader
            .getResource(ARTEFACTO_P2P_ANDROID) != null
        assertTrue(
            p2pAndroid,
            "la app debe declarar com.km:km-webrtc-android: es el unico artefacto que " +
                "justifica org/webrtc en su classpath",
        )

        val offenders = mutableListOf<String>()
        for (root in entradas) {
            for (nombre in escanearEntrada(root)) {
                val prohibido = PAQUETES_PROHIBIDOS_SIEMPRE.any { nombre.contains(it) } ||
                    (!p2pAndroid && nombre.contains(PAQUETE_WEBRTC))
                if (prohibido) offenders += "${root.name}!/$nombre"
            }
        }
        assertEquals(
            emptyList<String>(),
            offenders,
            "FRONTERA VIOLADA: la app arrastra WebRTC de escritorio (o el runtime sin " +
                "el artefacto que lo justifica): " + offenders.joinToString(" | "),
        )
    }

    // --- Control negativo 1: el filtro de nombres ve la fuga ---

    @Test
    fun `control negativo el detector de paquetes prohibidos dispara`() {
        val sucia = listOf(
            "com/km/api/KeyMessageCoreImpl.class",
            "dev/onvoid/webrtc/PeerConnectionFactory.class",
            "org/webrtc/PeerConnection.class",
            "com/km/transport/JvmRelayTransport.class",
        )
        val vistas = entradasProhibidas(sucia)
        assertEquals(2, vistas.size, "debio ver las dos entradas prohibidas: $vistas")
        assertTrue(vistas.any { it.startsWith("dev/onvoid/webrtc/") })
        assertTrue(vistas.any { it.startsWith("org/webrtc/") })
    }

    // --- Control negativo 3: org/webrtc sin el artefacto sigue siendo fuga ---

    @Test
    fun `control negativo org webrtc sin el artefacto sigue siendo una fuga`() {
        val entradas = listOf(
            "org/webrtc/PeerConnection.class",
            ARTEFACTO_P2P_ANDROID,
        )
        assertEquals(
            listOf("org/webrtc/PeerConnection.class"),
            entradasProhibidas(entradas, p2pAndroidPresente = false),
            "el runtime de WebRTC no puede entrar por arrastre",
        )
        assertEquals(
            emptyList<String>(),
            entradasProhibidas(entradas, p2pAndroidPresente = true),
            "con el artefacto declarado, org/webrtc es el backend deliberado",
        )
    }

    // --- Control negativo 2: el escaner ABRE un jar de verdad ---

    @Test
    fun `control negativo el escaner ve la fuga dentro de un jar de verdad`() {
        val jar = File.createTempFile("km-fuga-control-", ".jar")
        try {
            val manifest = Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") }
            JarOutputStream(BufferedOutputStream(FileOutputStream(jar)), manifest).use { out ->
                out.putNextEntry(ZipEntry("dev/onvoid/webrtc/WebRtcPeer.class"))
                out.write(byteArrayOf(1, 2, 3, 4, 5))
                out.closeEntry()
                out.putNextEntry(ZipEntry("com/km/transport/RelayClientStack.class"))
                out.write(byteArrayOf(6, 7, 8))
                out.closeEntry()
            }
            val vistas = entradasProhibidas(escanearEntrada(jar))
            assertEquals(
                listOf("dev/onvoid/webrtc/WebRtcPeer.class"),
                vistas,
                "el escaner NO ABRIO el jar: por eso el test de arriba no probaria nada",
            )
        } finally {
            jar.delete()
        }
    }

    // --- Control positivo: no inventa ---

    @Test
    fun `control positivo el detector no inventa fugas`() {
        val limpia = listOf(
            "com/km/api/KeyMessageCoreImpl.class",
            "com/km/transport/RelayClientStack.class",
            "com/km/transport/JvmRelayTransport.class",
            "kotlinx/coroutines/BuildersKt.class",
        )
        assertEquals(
            emptyList<String>(),
            entradasProhibidas(limpia),
            "una lista limpia no produce fugas",
        )
    }
}
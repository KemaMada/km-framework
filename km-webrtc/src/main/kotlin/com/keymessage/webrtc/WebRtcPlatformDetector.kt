package com.keymessage.webrtc

/**
 * Plataformas nativas soportadas por webrtc-java.
 *
 * webrtc-java publica el codigo nativo (libwebrtc) como artefactos
 * separados por clasificador. La deteccion en runtime complementa la
 * resolucion en build time: si el entorno de ejecucion no coincide con
 * el artefacto resuelto, fallamos con un diagnostico explicito en lugar
 * de un UnsatisfiedLinkError opaco.
 */
enum class WebRtcPlatform(val classifier: String) {
    LINUX_X86_64("linux-x86_64"),
    LINUX_AARCH64("linux-aarch64"),
    LINUX_AARCH32("linux-aarch32"),
    MACOS_X86_64("macos-x86_64"),
    MACOS_AARCH64("macos-aarch64"),
    WINDOWS_X86_64("windows-x86_64"),
    WINDOWS_AARCH64("windows-aarch64"),
}

/**
 * Detector de plataforma en runtime.
 */
object WebRtcPlatformDetector {

    /**
     * Detecta la plataforma del entorno de ejecucion actual.
     *
     * @throws WebRtcPlatformException si la combinacion OS/arch no esta soportada.
     */
    fun detect(
        osName: String = System.getProperty("os.name").lowercase(),
        osArch: String = System.getProperty("os.arch").lowercase(),
    ): WebRtcPlatform = when {
        osName.contains("linux") && osArch in setOf("amd64", "x86_64") -> WebRtcPlatform.LINUX_X86_64
        osName.contains("linux") && osArch in setOf("aarch64", "arm64") -> WebRtcPlatform.LINUX_AARCH64
        osName.contains("linux") && osArch == "arm" -> WebRtcPlatform.LINUX_AARCH32
        isMac(osName) && osArch in setOf("aarch64", "arm64") -> WebRtcPlatform.MACOS_AARCH64
        isMac(osName) && osArch in setOf("amd64", "x86_64") -> WebRtcPlatform.MACOS_X86_64
        osName.contains("windows") && osArch in setOf("amd64", "x86_64") -> WebRtcPlatform.WINDOWS_X86_64
        osName.contains("windows") && osArch in setOf("aarch64", "arm64") -> WebRtcPlatform.WINDOWS_AARCH64
        else -> throw WebRtcPlatformException(osName, osArch)
    }

    private fun isMac(osName: String): Boolean =
        osName.contains("mac") || osName.contains("darwin")

    /**
     * Verifica que el entorno de ejecucion es compatible con la plataforma
     * esperada por el build. Llamado antes de inicializar JNI.
     */
    fun requireSupported(
        expected: WebRtcPlatform,
        osName: String = System.getProperty("os.name").lowercase(),
        osArch: String = System.getProperty("os.arch").lowercase(),
    ) {
        val actual = try {
            detect(osName, osArch)
        } catch (e: WebRtcPlatformException) {
            throw WebRtcPlatformException(
                osName, osArch,
                "el build resolvio natives para '${expected.classifier}'",
            )
        }
        if (actual != expected) {
            throw WebRtcPlatformException(
                osName, osArch,
                "el build resolvio natives para '${expected.classifier}' pero el runtime " +
                    "detecto '${actual.classifier}'",
            )
        }
    }
}

/**
 * Plataforma WebRTC no soportada o inconsistente entre build y runtime.
 */
class WebRtcPlatformException(
    osName: String,
    osArch: String,
    detail: String = "combinacion OS/arch no soportada por webrtc-java",
) : RuntimeException(
    "Plataforma WebRTC no soportada: OS=$osName arch=$osArch ($detail). " +
        "Clasificadores disponibles: ${WebRtcPlatform.values().joinToString { it.classifier }}"
)
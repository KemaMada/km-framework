package com.keymessage.webrtc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 3N.1 — Detector de plataforma.
 *
 * Tests deterministas, sin cargar JNI. Verifican que la deteccion de
 * plataforma es explicita y falla de forma auditable.
 */
class WebRtcPlatformDetectorTest {

    @Test
    fun `N1-01 detecta linux x86_64`() {
        assertEquals(
            WebRtcPlatform.LINUX_X86_64,
            WebRtcPlatformDetector.detect("linux", "amd64")
        )
        assertEquals(
            WebRtcPlatform.LINUX_X86_64,
            WebRtcPlatformDetector.detect("linux", "x86_64")
        )
    }

    @Test
    fun `N1-02 detecta linux aarch64`() {
        assertEquals(
            WebRtcPlatform.LINUX_AARCH64,
            WebRtcPlatformDetector.detect("linux", "aarch64")
        )
        assertEquals(
            WebRtcPlatform.LINUX_AARCH64,
            WebRtcPlatformDetector.detect("linux", "arm64")
        )
    }

    @Test
    fun `N1-03 detecta macOS`() {
        assertEquals(
            WebRtcPlatform.MACOS_AARCH64,
            WebRtcPlatformDetector.detect("mac os x", "aarch64")
        )
        assertEquals(
            WebRtcPlatform.MACOS_X86_64,
            WebRtcPlatformDetector.detect("mac os x", "x86_64")
        )
        assertEquals(
            WebRtcPlatform.MACOS_AARCH64,
            WebRtcPlatformDetector.detect("darwin", "arm64")
        )
    }

    @Test
    fun `N1-04 detecta windows`() {
        assertEquals(
            WebRtcPlatform.WINDOWS_X86_64,
            WebRtcPlatformDetector.detect("windows 11", "amd64")
        )
        assertEquals(
            WebRtcPlatform.WINDOWS_AARCH64,
            WebRtcPlatformDetector.detect("windows 11", "aarch64")
        )
    }

    @Test
    fun `N1-05 plataforma no soportada falla explicitamente`() {
        val ex = assertThrows<WebRtcPlatformException> {
            WebRtcPlatformDetector.detect("plan9", "mips")
        }
        assertTrue(ex.message!!.contains("OS=plan9"))
        assertTrue(ex.message!!.contains("arch=mips"))
        // El mensaje lista los clasificadores disponibles.
        assertTrue(ex.message!!.contains("linux-x86_64"))
    }

    @Test
    fun `N1-06 requireSupported acepta runtime coincidente`() {
        assertDoesNotThrow {
            WebRtcPlatformDetector.requireSupported(WebRtcPlatform.LINUX_X86_64, "linux", "amd64")
        }
    }

    @Test
    fun `N1-07 requireSupported falla si runtime difiere del build`() {
        val ex = assertThrows<WebRtcPlatformException> {
            // build resolvio macos-aarch64, runtime es linux x86_64
            WebRtcPlatformDetector.requireSupported(WebRtcPlatform.MACOS_AARCH64, "linux", "amd64")
        }
        assertTrue(ex.message!!.contains("macos-aarch64"))
        assertTrue(ex.message!!.contains("linux-x86_64"))
    }

    @Test
    fun `N1-08 requireSupported falla si runtime es plataforma desconocida`() {
        val ex = assertThrows<WebRtcPlatformException> {
            WebRtcPlatformDetector.requireSupported(WebRtcPlatform.LINUX_X86_64, "haiku", "sparc")
        }
        assertTrue(ex.message!!.contains("haiku"))
    }

    @Test
    fun `N1-09 clasificadores coinciden con los publicados por webrtc-java`() {
        val classifiers = WebRtcPlatform.values().map { it.classifier }.toSet()
        assertEquals(
            setOf(
                "linux-x86_64", "linux-aarch64", "linux-aarch32",
                "macos-x86_64", "macos-aarch64",
                "windows-x86_64", "windows-aarch64",
            ),
            classifiers
        )
    }
}
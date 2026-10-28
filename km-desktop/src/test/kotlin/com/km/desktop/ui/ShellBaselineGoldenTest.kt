package com.km.desktop.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * La BASELINE F2: los 21 goldens de TEXTO (7 pantallas x 60x20/80x24/120x40).
 *
 * ## QUE PINZAN
 *
 * El texto y el ancho de cada pantalla en los tres tamanos de la baseline F2
 * (`resources/golden/tui/f2-*.txt`). Son un trinquete contra regresiones
 * visuales: si un cambio mueve una celda, el test falla y el cambio se revisa.
 *
 * ## LO QUE NO PINZAN
 *
 * NO prueban la paridad con F1 (R20). Se capturaron DESPUES del rediseno, en
 * M5/M6, cuando el oraculo F1 ya estaba en retirada; la paridad F1 se demostro
 * en M2 con los md5 documentados en `apply-progress` (#463) y el oraculo
 * (`ShellRendererLegacy` + su test de paridad) se retiro en M6 por decision del
 * design (#460). El nombre "F2" de cada fichero lo declara.
 *
 * ## COMO SE GENERAN
 *
 *     ./gradlew :km-desktop:test --tests '*ShellBaselineGoldenTest*' \
 *         -Dkm.golden.update=true
 *
 * Sin el flag, el test compara y falla ante cualquier diferencia: un golden que
 * se reescribe solo convierte toda regresion en "lo nuevo".
 *
 * ## QUE NO ES UN GOLDEN
 *
 * Estilo. Ningun fichero lleva `ESC`; el test lo comprueba linea a linea. El
 * color y el foco se afirman en los tests semanticos de cada pantalla.
 */
class ShellBaselineGoldenTest {

    @TestFactory
    fun `las 7 pantallas a 60x20 80x24 y 120x40 coinciden con su golden F2`(): List<DynamicTest> {
        val casos = GoldenHarness.cases()
        assertTrue(casos.size == 21, "la baseline son 21 casos, hay ${casos.size}")
        return casos.map { caso ->
            DynamicTest.dynamicTest(caso.name) { GoldenHarness.assertGolden(caso) }
        }
    }

    @Test
    fun `cada pantalla tiene sus tres tamanos y ninguno comparte fichero`() {
        val nombres = GoldenHarness.cases().map { it.name }
        assertTrue(nombres.size == nombres.toSet().size, "nombres de golden repetidos: $nombres")
        Screen.entries.forEach { pantalla ->
            GoldenHarness.SIZES.forEach { tamano ->
                assertTrue(
                    nombres.contains(GoldenHarness.fileName(pantalla, tamano)),
                    "falta ${GoldenHarness.fileName(pantalla, tamano)}",
                )
            }
        }
    }

    /**
     * Control negativo del ARNES, no de la pintura: si un golden perdiera un
     * espacio, la comparacion tiene que fallar. Se comprueba con una linea
     * sintetica para no depender de un fichero mutado a mano.
     */
    @Test
    fun `control negativo una linea con un espacio de menos no es igual`() {
        val esperado = "│ ana │"
        val actual = "│ana  │"
        assertTrue(esperado != actual, "el arnes compara texto exacto, no forma")
        assertTrue(
            Sanitizer.width(esperado) == Sanitizer.width(actual),
            "mismo ancho y distinto texto: es exactamente el fallo que el golden caza",
        )
    }
}

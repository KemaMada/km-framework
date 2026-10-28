package com.km.desktop.ui.layout

/**
 * Como se reparte el viewport en BANDAS verticales.
 *
 * [BOX] es la caja unica de hoy: un borde arriba, el cuerpo y un borde abajo.
 * Existe para que M1/M2 puedan reproducir la pintura actual celda a celda; el
 * rediseño de F2 llega con [BANDS], no reescribiendo la geometria.
 *
 * [BANDS] es la pantalla por bandas: cabecera, cuerpo, barra de estado y pie.
 *
 * [NONE] es una sola region: el QR a 85x43, donde no cabe ni un borde y lo que
 * se sacrifica es el marco, nunca el codigo.
 */
enum class Chrome {
    BOX,
    BANDS,
    NONE,
}

/**
 * Las bandas de un frame, ya medidas en celdas.
 *
 * ## POR QUE CUATRO CAMPOS Y NO UNA LISTA
 *
 * Porque son cuatro papeles con significado propio: `header` es el borde (o la
 * banda de marca) de arriba, `body` lo que scrollea, `status` el estado y
 * `footer` las teclas. Un `List<Rect>` obligaria a recordar el orden en cada
 * lectura y a llamar "rect 2" a la barra de estado.
 *
 * Un campo puede medir 0 (`isEmpty`): [Chrome.BOX] no tiene footer y
 * [Chrome.NONE] no tiene ninguna de las otras tres. Quien pinta comprueba la
 * altura; nadie escribe fuera de su banda.
 */
data class Frame(
    val header: Rect,
    val body: Rect,
    val status: Rect,
    val footer: Rect,
) {
    /** Todas las bandas, en orden de lectura. */
    val bands: List<Rect> get() = listOf(header, body, status, footer)
}

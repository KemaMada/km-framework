package com.km.desktop.ui

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * El RELOJ que el renderer usa para formatear horas. Se INYECTA, no se lee.
 *
 * ## POR QUE ESTO NO ES `System.currentTimeMillis()` DENTRO DEL RENDERER
 *
 * Porque el renderer es puro y sus tests son de texto exacto. `hora()` usaba
 * `ZoneId.systemDefault()`, asi que la misma fila con el mismo instante se
 * pintaba `14:00` en Madrid y `08:00` en Nueva York: un golden con horas dejaba
 * de ser un golden y pasaba a depender de la maquina que lo ejecutara. Con el
 * reloj inyectado el renderer sigue sin ver el reloj del sistema —`ui/` no lo
 * lee— y los tests pasan UTC con un instante fijo.
 *
 * ## QUIEN LO CONSTRUYE
 *
 * Solo `shell/` y `DesktopMain`: la zona del sistema y el instante son una
 * decision de la capa que ya habla con el terminal. `ui/` lo recibe hecho.
 *
 * [nowMs] todavia no lo usa la pintura de M0/M2 (solo las horas de fila, que
 * dependen de [zone]); existe ya porque es el mismo contrato que usaran los
 * toasts y el spinner: el reducer compara `nowMs` y el renderer no lee nada.
 */
data class RenderClock(
    /** Zona con la que se formatean las horas de fila. */
    val zone: ZoneId,
    /** Instante del frame, en milisegundos desde la epoch. Lo fija el bucle. */
    val nowMs: Long,
) {

    /** `HH:mm` de [ms] en [zone]. Deterministico: no consulta el reloj. */
    fun hora(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).format(HORA)

    companion object {
        private val HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /**
         * Reloj FIJO en UTC epoch 0: el valor por defecto del renderer.
         *
         * No es "el reloj del sistema disfrazado": es una constante, de modo que
         * llamar a `render` sin reloj nunca puede depender de la maquina. El
         * bucle real pasa el suyo.
         */
        val UTC_EPOCH: RenderClock = RenderClock(ZoneOffset.UTC, 0L)
    }
}

# Re-medición de la línea base tras `include(":km-transport")` (tarea 1.4)

El orquestador dejó escrito, como AVISO VINCULANTE, que añadir
`include(":km-transport")` a `settings.gradle.kts` puede **inutilizar**
`-Dorg.gradle.java.home` y que, si eso ocurre, hay que **re-medir TODO** con la
configuración que realmente se aplique y **declararlo**, sin dar por buena una
cifra medida con otra.

## Lo que se midió, y lo que dio

### 1. ¿Sigue mandando `-Dorg.gradle.java.home`?

**Sí.** Con `:km-transport` ya en `settings.gradle.kts` y con el build entero
(`km-core`, `km-daemon`, `km-transport`, `km-desktop`, `km-webrtc`, `app`):

| Proceso | JDK detectado | Por qué |
|---|---|---|
| Daemon de Gradle | `/usr/lib/jvm/java-27-openjdk/bin/java` | lo fija `-Dorg.gradle.java.home`, que pisa `~/.gradle/gradle.properties` (`/home/kemamada/jdk-17.0.2`) |
| Worker de `test` de km-core | `/usr/lib/jvm/java-11-openjdk/bin/java` | lo fija `jvmToolchain(11)`, y es ORTOGONAL al anterior |

Los dos son correctos y no se contradicen: el primero **ejecuta** Gradle, el
segundo **ejecuta** los tests. Medido durante una corrida real de `:km-core:test`
inspeccionando la linea de ordenes del proceso `GradleWorkerMain`.

### 2. ¿El byteCode emitido cambió?

No. Clases del motor movido:

```
km-transport/build/classes/kotlin/main/com/km/transport/JvmRelayTransport$1.class        major = 55
km-transport/build/classes/kotlin/main/com/km/transport/JvmRelayTransport$3.class        major = 55
km-transport/build/classes/kotlin/main/com/km/transport/JvmRelayTransport$Companion.class major = 55
```

`major = 55` es Java 11. `km-transport` **no** es el problema: el toolchain 11
declarado en su `build.gradle.kts` es el que emite, y eso es exactamente lo que
exige el Gate 0.4.

### 3. Cifras de la suite, antes y después de añadir el módulo

| Corrida | km-core | km-transport | km-desktop |
|---|---|---|---|
| **Baseline** (antes de `:km-transport`) | **1376 / 0 fallos**, exit 0 | — | — |
| **Re-medida** (con `:km-transport`) | **1381 / 0 fallos**, exit 0 | **41 / 0 fallos**, exit 0 | **42 / 0 fallos**, exit 0 |

Las 5 pruebas de más en km-core son las de `LegacyInboundFrameContractTest`
(tarea 1.1). Las 41 + 42 = **83** de `km-transport` + `km-desktop` son
exactamente las mismas que tenia `km-desktop` antes del movimiento: se movieron
41 con su clase y **una** se quedo en el escritorio (el caso de
`DesktopOptions.parse`, que probaba la CLI y no el wire). **Cero pruebas
perdidas**, cuentas comprobadas una a una con `grep -c '@Test'`.

## Por qué esto NO es un "no cambió nada"

Si el `-Dorg.gradle.java.home=` hubiera dejado de mandar, el daemon habria
vuelto a `/home/kemamada/jdk-17.0.2` y las tres cifras habrian sido inválidas.
Se comprobó el proceso REAL, no la ausencia de un error, y se volvería a
comprobar en la suite final (tarea 5.1). La cifra que se declare al final es,
por tanto, la medida con esta configuración concreta: daemon 27, toolchain 11.
# Gate 0.4 — toolchain de `km-transport` (decision escrita ANTES del build script)

**Decisión: `jvmToolchain(11)`. NO 27.**

## Por qué no 27

`jvmToolchain(N)` fija el JDK que **emite** bytecode. `-Dorg.gradle.java.home=X`
fija el JDK que **ejecuta** el daemon de Gradle. Son dos cosas ortogonales y
confundirlas es exactamente el error que esta decisión evita:

- `~/.gradle/gradle.properties` fija `org.gradle.java.home=/home/kemamada/jdk-17.0.2`.
- El proyecto pasa `-Dorg.gradle.java.home=/usr/lib/jvm/java-27-openjdk`, que lo pisa.
  Eso cambia quién **corre** Gradle. No cambia qué bytecode sale de `kotlinc`.
- Lo que sale de `kotlinc` lo decide `jvmToolchain`, y en los cuatro módulos
  existentes es 11:
  - `km-core/build.gradle.kts:17-23` → `sourceCompatibility/targetCompatibility = 11`, `jvmToolchain(11)`
  - `km-desktop/build.gradle.kts:23-30` → ídem
  - `km-webrtc/build.gradle.kts:5-12` → ídem
  - `app/build.gradle.kts:9` → `VERSION_11`
- `km-core` se consume **por artefacto** (`km-desktop/build.gradle.kts:41`,
  `implementation("com.km:km-core:0.1.0-SNAPSHOT")`). Un consumidor compilado en
  toolchain 11 **no lee bytecode de clase 27**: falla con
  `UnsupportedClassVersionError` / `class file version 67.0, only up to 55`.

Subir `km-transport` a 27 sin subir km-core no rompería el build de km-transport
contra un km-core 11, pero produciría un artefacto que **ningún consumidor de este
repositorio puede cargar**, y suGem ≠ su Maven. Es un módulo muerto.

Subir también km-core/km-desktop/km-webrtc a 27 queda **fuera de alcance**:
toca tres módulos que no son de esta fase, altera el artefacto publicado por
km-core yoidal el requirement que km-core siga siendo consumible por la app
Android (que fija 11). Decisión del orquestador: **no se sube el toolchain de
ningún módulo existente**.

## Por qué 11 es también lo correcto por toolchain de Android

La app (`app/build.gradle.kts:9`, `VERSION_11`) consume `com.km:km-core` como
dependencia. Si `km-transport` publicara a 11, una futura fase de app que lo
consuma no necesita subir de toolchain. Publicar a 11 no cuesta nada hoy y
evita el salto en su momento.

## Aviso vinculante del orquestador (medido, no supuesto)

Añadir `include(":km-transport")` a `settings.gradle.kts` puede **inutilizar**
`-Dorg.gradle.java.home` y con ello toda la línea base medida antes de 1.4. Si
eso ocurre, **se re-mide TODO** (baseline y final) con la configuración que
realmente se aplique y **se declara**; no se da por buena una cifra medida con
otra configuración.

Medición hecha en la fase APPLY, con la configuración final:
ver `tools/mutaciones_p4/REINMEDICION.md`.
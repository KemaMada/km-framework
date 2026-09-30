# Dependencias de terceros

Este directorio documenta el software de terceros que `km-core` y `km-webrtc`
enlazan o embeben, y su licencia.

`km-framework` está bajo MIT (ver [`LICENSE`](../LICENSE)). Ninguna dependencia
de terceros se relicensifica ni se modifica: cada componente conserva su propia
licencia, y la obligación de km-framework es la de atribución que esas licencias
imponen.

## Cómo se ha verificado cada licencia

Este documento **no** se ha escrito de memoria. Cada fila dice de dónde sale su
licencia, y hay tres niveles de evidencia:

| Nivel | Significado |
|-------|-------------|
| **Verificada en el artefacto** | El `.jar` o su `.pom` declara la licencia o trae el texto dentro. Se puede comprobar sin conexión. |
| **Verificada en el POM** | El `.pom` del artefacto (o de su padre) declara la licencia. |
| **No declarada** | Ni el artefacto ni su POM declaran licencia, y no hay copia local del POM que consultar. Se indica la licencia conocida aguas arriba, marcada como **no verificada aquí**. |

**Fecha de verificación: 2026-09-30**, contra la caché local de Gradle
(`~/.gradle/caches/modules-2/files-2.1`).

**Antes de redistribuir**, regenera esta tabla con `./gradlew dependencies` y
comprueba los POMs en Maven Central. Y en particular: **ninguna licencia de esta
tabla está declarada por el propio proyecto km-framework** en los ficheros de
build. Las versiones que se verificaron son las que se resolvieron localmente; si
cambias una versión, esta tabla caduca.

## Dependencias de `km-core`

`km-core` no tiene dependencia alguna de Android ni de `dev.onvoid`. Compila para
un toolchain JVM 11.

| Dependencia | Versión declarada | Dónde se declara | Licencia | Evidencia | Para qué se usa |
|-------------|-------------------|------------------|----------|-----------|-----------------|
| `org.jetbrains.kotlin:kotlin-stdlib` | resuelta por el plugin Kotlin a **2.2.10** | `implementation(kotlin("stdlib"))` en `km-core/build.gradle.kts` | **Apache-2.0** (no verificada aquí) | **No declarada.** El manifiesto solo dice `Implementation-Vendor: JetBrains`. No hay POM en la caché ni texto incrustado. | Runtime de Kotlin. |
| `com.fasterxml.jackson.module:jackson-module-kotlin` | **2.15.2** | Cadena literal en `km-core/build.gradle.kts` (no está en el catálogo) | **Apache-2.0** | **Verificada en el artefacto.** Manifiesto: `Bundle-License: https://www.apache.org/licenses/LICENSE-2.0.txt`. Incrustados `META-INF/LICENSE` y `META-INF/NOTICE`. | Serialización JSON de `Message`, `Ack` y control de relé. |
| `com.fasterxml.jackson.core:jackson-databind` | **2.15.2** (transitiva) | Arrastrada por `jackson-module-kotlin` | **Apache-2.0** | **Verificada en el artefacto.** `Bundle-License` en el manifiesto; 2 ficheros de licencia. | Motor de binding de Jackson. |
| `com.fasterxml.jackson.core:jackson-core` | **2.15.2** (transitiva) | Arrastrada por `jackson-module-kotlin` | **Apache-2.0** | **Verificada en el artefacto.** `Bundle-License` en el manifiesto. | Lectura/escritura del árbol JSON. |
| `com.fasterxml.jackson.core:jackson-annotations` | **2.15.2** (transitiva) | Arrastrada por `jackson-module-kotlin` | **Apache-2.0** | **Verificada en el artefacto.** `Bundle-License` en el manifiesto. | Anotaciones de jackson-annotations. |
| `net.i2p.crypto:eddsa` | **0.3.0** | Cadena literal en `km-core/build.gradle.kts`. `gradle/libs.versions.toml` declara la versión `eddsa = "0.3.0"` en `[versions]`, pero **no** define un alias `eddsa` en `[libraries]`: el alias no existe | **CC0 1.0 Universal** (dedicación al dominio público) | **Verificada en el artefacto.** Manifiesto: `Bundle-License: https://creativecommons.org/publicdomain/zero/1.0/`. **El artefacto no declara ningún titular de copyright**, y no hay POM en la caché. | **Ed25519** (firmar y verificar) en `com.km.crypto.Ed25519Impl`. |
| `org.bouncycastle:bcprov-jdk18on` | **1.86** (alias `libs.bcprov`, `bouncycastle = "1.86"`) | `implementation(libs.bcprov)` en `km-core/build.gradle.kts` | **MIT** | **Verificada en el artefacto.** POM: `Bouncy Castle Licence` → `https://www.bouncycastle.org/licence.html`. El jar trae `META-INF/LICENSE.md`, que es el texto MIT con `Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc.` | X25519, HKDF-SHA256 y ChaCha20-Poly1305, **solo** en `com.km.crypto.provider`. |
| `org.jetbrains.kotlin:kotlin-test` | resuelta por el plugin Kotlin | `testImplementation` | **Apache-2.0** (no verificada aquí) | **No declarada**, igual que `kotlin-stdlib`. | Test scope. |
| `org.junit.jupiter:junit-jupiter` | **5.10.0** | Cadena literal en ambos `build.gradle.kts` | **EPL-2.0** (no verificada aquí) | **No declarada.** La caché local contiene los artefactos **5.10.1**, cuyo manifiesto solo dice `Implementation-Vendor: junit.org`. Sin POM, sin texto incrustado. | Test scope. Motor de tests. |

### Nota sobre `net.i2p.crypto:eddsa`

Es una dependencia **crítica**: es la implementación de Ed25519 que usa
`com.km.crypto.Ed25519Impl` para firmar y verificar. `Bundle-License` declara
CC0 1.0, es decir, **`public domain` con una renuncia explícita de derechos**, no
una licencia copyleft. El artefacto no indica quién es el titular del copyright,
así que la atribución se hace por proyecto aguas arriba, no por nombre propio.

Que el titular no esté declarado no exime de atribuir. El CC0 1.0 es una
renuncia a los derechos, no una licencia copyleft, y no obliga a citar. Este
proyecto cita por cortesía y porque es lo correcto: la atribución se hace por
proyecto y por URL, no por titular nominal.

### Nota sobre Bouncy Castle y su frontera

`bcprov-jdk18on` está **confinado a `com.km.crypto.provider`**
(`BcX25519`, `BcHkdfSha256`, `BcChaCha20Poly1305`). El resto de `km-core` depende
solo de las interfaces propias `X25519`, `Kdf`, `Aead` y `Hash`. Está
deliberado: permite cambiar de proveedor de primitivas sin tocar nada más, y
reduce la superficie de la biblioteca de terceros.

## Dependencias de `km-webrtc`

`km-webrtc` depende de `km-core` y arrastra **código nativo**.

| Dependencia | Versión declarada | Dónde se declara | Licencia | Evidencia | Para qué se usa |
|-------------|-------------------|------------------|----------|-----------|-----------------|
| `dev.onvoid.webrtc:webrtc-java` | **0.19.0** | `implementation(libs.webrtc.java)`; `webrtc-java = { group = "dev.onvoid.webrtc", name = "webrtc-java", version = "0.19.0" }` | **Apache-2.0** | **Verificada en el POM padre.** El POM del propio `webrtc-java-0.19.0` **no** tiene bloque `<licenses>`; lo declara `dev.onvoid.webrtc:webrtc-java-parent:0.19.0` como *The Apache Software License, Version 2.0*. | API Java y bindings JNI de WebRTC. |
| `dev.onvoid.webrtc:webrtc-java` (con clasificador nativo) | **0.19.0**, clasificador según plataforma | `runtimeOnly(variantOf(libs.webrtc.java) { classifier(nativePlatform.classifier) })` | **BSD 3-Clause** (el código nativo) | **Verificada en el artefacto.** El jar nativo incluye `META-INF/licenses/webrtc/LICENSE.md` y `META-INF/licenses/webrtc/PATENTS`. El LICENSE es el BSD de 3 cláusulas con `Copyright (c) 2011, The WebRTC project authors. All rights reserved.` | **libwebrtc**: `libwebrtc-java-<plataforma>.so` / `.dll` / `.dylib`, ~25 MB por plataforma. |
| `org.jetbrains.kotlin:kotlin-stdlib` | resuelta a **2.2.10** | `implementation(kotlin("stdlib"))` | **Apache-2.0** (no verificada aquí) | **No declarada**, igual que en `km-core`. | Runtime de Kotlin. |
| `org.junit.jupiter:junit-jupiter` | **5.10.0** | `testImplementation` | **EPL-2.0** (no verificada aquí) | **No declarada**, igual que en `km-core`. | Test scope. |

### Sobre el clasificador nativo

`webrtc-java` publica el código nativo en artefactos separados por clasificador.
El build de `km-webrtc` detecta la plataforma a partir de `os.name` y `os.arch`
y elige entre siete:

`linux-x86_64`, `linux-aarch64`, `linux-aarch32`, `macos-x86_64`,
`macos-aarch64`, `windows-x86_64`, `windows-aarch64`.

Fuera de esa lista el build **falla a propósito**, con un error que los enumera.
Es una consecuencia práctica de la licencia: si `km-webrtc` se distribuyera como
un único artefacto, tendría que embeberse el `.so` de todas las plataformas, con
lo que eso implica de cadena de atribución.

### La concesión de patentes de libwebrtc — y lo que km-framework no tiene

El fichero `META-INF/licenses/webrtc/PATENTS` que viene dentro del artefacto
nativo es una **Additional IP Rights Grant** de Google: una concesión de patentes
expresa, mundial, irrevocable y sin coste, que se extingue si se inicia un litigio
por infracción contra Google.

Conviene señalarlo porque es exactamente la protección que **km-framework no
ofrece**: su licencia es MIT, que no incluye concesión de patentes. Esa es una
decisión consciente del proyecto, no un descuido. Ver el
[`CHANGELOG.md`](../CHANGELOG.md) y el `NOTICE`.

## Ficheros de licencia en este directorio

| Fichero | De dónde sale |
|---------|--------------|
| `Apache-2.0.txt` | Texto canónico de Apache-2.0, extraído del `META-INF/LICENSE` de `org.apache.httpcomponents:httpcore:4.4.16` de la caché local (11358 bytes, el texto estándar íntegro, con su apéndice). |
| `bouncy-castle-1.86-LICENSE.md` | **Copia literal** de `META-INF/LICENSE.md` dentro de `bcprov-jdk18on-1.86.jar`. |
| `webrtc-native-LICENSE.md` | **Copia literal** de `META-INF/licenses/webrtc/LICENSE.md` dentro de `webrtc-java-0.19.0-linux-x86_64.jar`. 130 KB: es el fichero de licencias tal cual lo publica el proyecto WebRTC, que incluye las licencias de sus propias dependencias. |
| `webrtc-native-PATENTS` | **Copia literal** de `META-INF/licenses/webrtc/PATENTS` del mismo jar. |
| `jackson-LICENSE` | **Copia literal** de `META-INF/LICENSE` de `jackson-module-kotlin-2.15.2.jar`. |
| `jackson-NOTICE` | **Copia literal** de `META-INF/NOTICE` del mismo jar. Obligatoria: Apache-2.0 §4(d) exige reemitir los avisos. |

**No se incluye el texto de CC0-1.0** (eddsa) ni el de EPL-2.0 (JUnit): ninguno
de esos dos artefactos lo incrusta en su `.jar`, y no hay copia local. Se
enlazan a su fuente oficial en su lugar:

- CC0 1.0: <https://creativecommons.org/publicdomain/zero/1.0/legalcode>
- EPL-2.0: <https://www.eclipse.org/legal/epl-2.0/>

## En el catálogo pero no en los módulos publicables

`gradle/libs.versions.toml` declara bastante más de lo que `km-core` y
`km-webrtc` usan. Lo que sigue pertenece al cliente Android, que se ha extraído a
`km-android`, y **no forma parte de km-framework**:

`io.github.webrtc-sdk:android:125.6422.07`, `androidx.core:core-ktx`,
`androidx.room:*`, `androidx.security:security-crypto`,
`androidx.navigation:navigation-compose`, `androidx.compose.*`,
`com.google.android.material:material`, `com.google.zxing:core`,
`com.journeyapps:zxing-android-embedded`, `com.squareup.okhttp3:okhttp`.

`io.github.webrtc-sdk:android` merece un comentario: es el SDK de WebRTC para
Android, distinto de `dev.onvoid.webrtc:webrtc-java`, que es el de escritorio.
Los dos no deben coexistir en un mismo classpath. `km-webrtc` usa solo el
segundo.

## Procedimiento para actualizar esta tabla

1. `./gradlew :km-core:dependencies :km-webrtc:dependencies` para ver el grafo
   resuelto de verdad.
2. Para cada artefacto nuevo, mira su `.pom` en Maven Central y su manifiesto.
3. Extrae la licencia del artefacto con
   `unzip -p <jar> META-INF/LICENSE` y guárdala **literal** si existe.
4. Actualiza `NOTICE`, este fichero y la fila correspondiente del `README.md`.
5. Si la licencia no aparece en ningún sitio, **no la supongas**: pon
   "No declarada" y enlaza a la fuente.

## Licencia de este directorio

Los ficheros de licencia de terceros que aquí se reproducen siguen siendo
propiedad de sus titulares y se replican **sin modificación**, tal como aparecen
en los artefactos. Este directorio se distribuye bajo la misma MIT de
km-framework, pero eso **no** reescribe las licencias de los textos que contiene.

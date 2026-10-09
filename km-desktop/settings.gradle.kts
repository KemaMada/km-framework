/*
 * Build STANDALONE de km-desktop.
 *
 * El modulo vive dentro del mono-repo (`include(":km-desktop")` en el
 * `settings.gradle.kts` raiz), asi que normalmente se compila con el Gradle de
 * la raiz: `./gradlew :km-desktop:shadowJar`.
 *
 * Este fichero existe para que el modulo tambien se pueda compilar por su
 * cuenta, sin el resto del mono-repo, que es justo lo que hace util depender
 * de `km-core` por `mavenLocal()` en vez de por `project(":km-core")`:
 *
 *     (cd km-desktop && gradle shadowJar)      # build aislado
 *     ./gradlew :km-desktop:shadowJar           # build integrado
 *
 * `build.gradle.kts` pide el plugin Kotlin como `alias(libs.plugins.kotlin.jvm)`,
 * no con una version local. En el build integrado el accessor `libs` lo da el
 * catalogo de la raiz; aqui, al no existir build raiz que lo provea, se importa
 * EL MISMO fichero. No es una copia: es el catalogo de la raiz, leido desde
 * fuera, de modo que no puede divergir de ella.
 *
 * En el build integrado este fichero se IGNORA por completo: Gradle solo lee el
 * `settings.gradle.kts` de la raiz del build.
 */
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // `km-core` se consume como artefacto publicado (`com.km:km-core`),
        // no como proyecto del build: este modulo es un CONSUMIDOR del
        // framework, nunca una segunda fuente de la logica de protocolo.
        mavenLocal()
        mavenCentral()
    }

    // El accessor `libs` de `build.gradle.kts` sale del catalogo de la raiz.
    // Va DENTRO de `dependencyResolutionManagement` porque `versionCatalogs` no
    // es un bloque suelto del script de settings: cuelga de ahi.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "km-desktop"
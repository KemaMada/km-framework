pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // `km-desktop` consume `com.km:km-core` como artefacto publicado, no
        // como `project(":km-core")`: es un CONSUMIDOR del framework y aloja
        // ninguna logica de protocolo. Por eso necesita mavenLocal, que el
        // POM de km-core publica via `publishToMavenLocal`.
        //
        // Lo mismo vale para `km-transport` desde p4: el motor de red vive en
        // un HERMANO de km-core, nunca dentro de el. km-core no gana ninguna
        // dependencia de red; el que las gana es este modulo, que las declara
        // todas aqui.
        mavenLocal()
    }
}

rootProject.name = "KeyMessage"
include(":km-core")
include(":km-daemon")
// p4: el transporte de RED del rele. Vive FUERA de km-core para que el
// framework siga sin dependencias de red; lo consumen km-desktop y (en p3) la
// app. Se construye POR ARTEFACTO (`com.km:km-core`), nunca por proyecto:
// mezclar las dos formas mete km-core dos veces en el grafo.
include(":km-transport")
include(":km-desktop")
include(":km-webrtc")

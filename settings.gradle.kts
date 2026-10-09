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
        mavenLocal()
    }
}

rootProject.name = "KeyMessage"
include(":km-core")
include(":km-daemon")
include(":km-desktop")
include(":km-webrtc")

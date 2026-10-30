// =====================================================================
// Build AUTONOMO de la app movil (p3-app-movil-cliente-delgado).
//
// Este fichero es la RAIZ de un build propio: la app NO esta incluida en el
// `settings.gradle.kts` del repositorio y no se toca. `rootProject` ES la app,
// de modo que las tareas son `compileDebugKotlin`, `testDebugUnitTest`, ... sin
// el prefijo `:app:`.
//
// Que sea autonomo no es una preferencia estetica: el `settings.gradle.kts` raiz
// construye `km-core`, `km-transport` y demas como MODULOS (`project(":km-core")`),
// y la app los consume POR ARTEFACTO (`com.km:km-core`, `com.km:km-transport`)
// desde mavenLocal, que es la frontera de consumidor que sostiene la regla
// "la app no aloja logica de framework". Un build unico con `project(...)`
// permitiria que la app compiles contra un `km-core` distinto del publicado y
// que un fallo de artefacto pasara inadvertido.
//
// Invocacion (desde la raiz del repositorio):
//
//     ./gradlew -p app -Dorg.gradle.java.home=/usr/lib/jvm/java-27-openjdk \
//         :testDebugUnitTest
//
// `RootBuildIsolationTest` ata las dos mitades de esta decision: que el settings
// raiz NO incluya la app, y que este exista y la incluya de verdad.
// =====================================================================

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

// El catalogo es el MISMO del repositorio, no una copia: los plugins de la app
// y los de los modulos tienen que moverse juntos, y dos catalogues se separan
// en silencio. La ruta es relativa a este fichero, que es la raiz de ESTE build.
//
// Vive DENTRO de `dependencyResolutionManagement` porque es ahi —y no en el
// `pluginManagement`— donde Gradle expone el catálogo a los scripts de proyecto:
// un `versionCatalogs` suelto en el cuerpo del settings no resuelve.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }

    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // `mavenLocal` es de donde salen `com.km:km-core` y `com.km:km-transport`.
        // Sin el, el gate "deps por artefacto" no tiene de donde resolver.
        mavenLocal()
    }
}

rootProject.name = "KeyMessageApp"

// Sin `include(...)`: la raiz ES la app.
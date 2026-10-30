plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.keymessage"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.keymessage"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        create("release") {
            storeFile = file("keymessage-release.jks")
            storePassword = "keymessage"
            keyAlias = "keymessage"
            keyPassword = "keymessage"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Los tests de p3 son PUROS (logica de config, identidad, sesion y
            // mapeo). `returnDefaultValues` evita que una llamada a una API de
            // `android.jar` sin stub LANZE `RuntimeException("Stub!")`: lo que
            // se prueba aqui es logica, no el framework de Android.
            isReturnDefaultValues = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ---------------------------------------------------------------------
    // Framework: POR ARTEFACTO, nunca `project(":km-core")`
    // ---------------------------------------------------------------------
    //
    // `app/` es un build AUTONOMO (su propio `settings.gradle.kts`) y no esta
    // incluido en el del repositorio, de modo que `project(":km-core")` no
    // resolveria nada. Y aunque se incluyera, mezclar las dos formas deja el
    // grafo con dos ficheros de clase distintos para el mismo
    // group/artifactId/version, que Gradle reporta como `Duplicate class`.
    //
    // Las dos mitades de esta frontera las ata `RootBuildIsolationTest` y
    // `DependencyOriginTest`.
    //
    // Publicar antes de compilar:
    //
    //     ./gradlew :km-core:publishToMavenLocal :km-transport:publishToMavenLocal
    implementation("com.km:km-core:0.1.0-SNAPSHOT")
    implementation("com.km:km-transport:0.1.0-SNAPSHOT")

    // km-core y km-transport declaran Jackson como `implementation`, asi que su
    // API NO se filtra al classpath de compilacion de la app. `JsonRelayControlCodec`
    // la usa en ejecucion (STORED, RELAY_EXPIRED), asi que se declara aqui.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

    // ---------------------------------------------------------------------
    // Transporte: el MISMO motor de p4, con motor Ktor OkHttp
    // ---------------------------------------------------------------------
    //
    // `JvmRelayTransport` es de km-transport y NO se reimplementa aqui: lo que
    // hace la app es INYECTARLE el `clientFactory` (`RelayStackFactory`). El
    // motor por defecto de km-transport es CIO, pensado para escritorio; en
    // Android el motor es OkHttp, y por eso la app lo fija en lugar de heredar
    // el de otro.
    implementation("io.ktor:ktor-client-core:3.1.3")
    implementation("io.ktor:ktor-client-websockets:3.1.3")
    implementation("io.ktor:ktor-client-okhttp:3.1.3")

    // `Dispatcher.Main` en Android: `runBlocking` y los flujos de la UI.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // ---------------------------------------------------------------------
    // Android
    // ---------------------------------------------------------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)

    // `ConversationViewModel` y `viewModel()` los necesitan, y antes llegaban
    // por `activity-compose` de forma transitiva: eso es una dependencia
    // implicita que se rompe en cuanto otra la sube o la baja. Se declaran.
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

    // `EncryptedSharedPreferences`, para `AndroidIdentityStore`: la clave de
    // FIRMA en reposo no puede vivir en preferencias en claro.
    implementation(libs.androidx.security.crypto)

    // ---------------------------------------------------------------------
    // QR: el PORTADOR, no el contenido
    // ---------------------------------------------------------------------
    //
    // El contenido del QR es el MISMO sobre KCE que viaja en un `.kmc`
    // (`KceEnvelope`): aqui no hay "protocolo QR". Lo que se anade es el
    // portador —codificar a una matriz y leerla de la camara—, que `KceEnvelope`
    // deja deliberadamente fuera de su alcance.
    //
    // `zxing-core` es Java puro y por eso se puede EJERCITAR en un test de JVM
    // (codificar y volver a decodificar sin dispositivo). `zxing-android-embedded`
    // es la Activity de escaneo y el permiso de camara, ya resueltos.
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    // Version explicita y no `kotlin("test")`: en un modulo Android el helper
    // del plugin Kotlin JVM no esta disponible, y la version tiene que coincidir
    // con la del plugin Kotlin que usa el modulo.
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.2.10")
    // `runTest` y `Dispatchers.setMain`: el `ImportContactViewModel` corre su
    // import en `viewModelScope`, que vive en `Dispatchers.Main.immediate`, y
    // en un test de JVM no hay hilo principal. Sin esto, ese `ViewModel` —que
    // es donde vive el UNICO dueno del evento "importado"— no se puede
    // comprobar. La version coincide con la de `kotlinx-coroutines-android`.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// La app no depende de `webrtc-sdk`: el transporte de esta version es
// RELE-ONLY. `DependencyOriginTest` lo comprueba sobre el classpath REAL de la
// suite, no leyendo este comentario.
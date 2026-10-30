plugins {
    id("com.android.library")
    `maven-publish`
}

// Backend P2P de Android. Espeja a :km-webrtc (escritorio): el medio vive en su
// modulo y km-core/km-transport siguen sin conocer WebRTC.
android {
    namespace = "com.km.webrtc.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release")
    }
}

dependencies {
    // El contrato (P2pProvider/TransportBackend) es de km-core. Se consume por
    // artefacto, igual que la app: mezclar project() y coordenadas duplica
    // clases.
    api("com.km:km-core:0.1.0-SNAPSHOT")
    implementation(libs.webrtc.sdk)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.km"
                artifactId = "km-webrtc-android"
                version = "0.1.0-SNAPSHOT"
            }
        }
    }
}

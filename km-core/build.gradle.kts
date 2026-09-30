plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Coordenadas mínima para que km-core pueda consumirse como dependencia.
//
// `group` y `version` NO implican que exista un artefacto publicado: no hay
// plugin `maven-publish` aplicado y km-core sigue sin estar en Maven Central ni
// en ningún registro. Lo único que hacen es darle una identidad, que es lo que
// Gradle necesita para resolver una dependencia por coordenadas
// (`com.km:km-core:0.1.0-SNAPSHOT`) y para poder sustituirla por este proyecto
// cuando alguien lo incluye con `includeBuild` (composite build). Ver el
// README de km-android para el consumo provisional actual.
//
// group = "com.km"     -> coherente con los paquetes `com.km.*` y con el grupo
//                         que el README de km-framework declara como previsto.
// version = "0.1.0-SNAPSHOT"
//                      -> el proyecto está por debajo de 1.0 (ver CHANGELOG.md),
//                         y el sufijo -SNAPSHOT deja explícito que no hay
//                         ninguna versión publicada. Cuando se publique la
//                         primera, estas dos lineas cambiaran.
group = "com.km"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
    implementation("net.i2p.crypto:eddsa:0.3.0")
    // Primitivas criptograficas (X25519, HKDF, ChaCha20-Poly1305).
    // BC queda CONFINADO en el package crypto.provider: el resto de
    // km-core depende solo de las interfaces propias.
    implementation(libs.bcprov)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

tasks.test {
    useJUnitPlatform()
}

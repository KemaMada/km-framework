plugins {
    alias(libs.plugins.kotlin.jvm)
}

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

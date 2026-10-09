plugins {
    alias(libs.plugins.kotlin.jvm)
    id("maven-publish")
}

// Coordenadas para consumo desde mavenLocal
group = "com.km"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(11)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.km"
            artifactId = "km-core"
            version = "0.1.0-SNAPSHOT"
            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
    }
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

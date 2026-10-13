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

// ---------------------------------------------------------------------------
// Vectores P6 como RECURSO del artefacto (task 1.4).
//
// La fuente canonica vive en `km-id-reference/`. Para que los CUATRO
// consumidores (km-core, km-daemon, km-desktop y la app Android) lean los
// MISMOS bytes, se copian al classpath del jar de km-core bajo `p6-vectors/`.
// Android consume `com.km:km-core` como dependencia, asi que los vectores
// llegan de forma TRANSITIVA por el artefacto: ningun consumidor sube por
// rutas relativas (`../km-id-reference`), que en la app Android no existen.
//
// `Sync` (no `Copy`) es deliberado: el destino se reconstruye desde cero, de
// modo que un vector retirado de la fuente no sobrevive en el jar.
// ---------------------------------------------------------------------------
val p6ResourceDir = layout.buildDirectory.dir("generated/p6-vectors")
val referenceDir = rootProject.layout.projectDirectory.dir("km-id-reference")

val syncP6Vectors by tasks.registering(Sync::class) {
    from(referenceDir.dir("vectors")) {
        include("P6-*/**")
        into("p6-vectors")
    }
    from(referenceDir.file("P6_VECTOR_INVENTORY.txt")) { into("p6-vectors") }
    from(referenceDir.file("AUTH_MANIFEST.json")) { into("p6-vectors") }
    from(referenceDir.file("FROZEN.sha256")) { into("p6-vectors") }
    into(p6ResourceDir)
}

sourceSets {
    main {
        resources.srcDir(p6ResourceDir)
    }
}

tasks.named("processResources") {
    dependsOn(syncP6Vectors)
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

plugins {
    alias(libs.plugins.kotlin.jvm)
    // `km-webrtc` se publica para que `km-desktop` lo consuma POR ARTEFACTO.
    // Declararlo como `project(":km-webrtc")` desde el escritorio y, a la vez,
    // enganchar `km-core` por artefacto mete el mismo `group:artifact:version`
    // dos veces con distinto fichero de clase (`Duplicate class`, M8-b).
    id("maven-publish")
}

group = "com.km"
version = "0.1.0-SNAPSHOT"

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.km"
            artifactId = "km-webrtc"
            version = "0.1.0-SNAPSHOT"
            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(11)
}

/** Version mayor de la toolchain, para condicionar flags de JVM. */
val toolchainMajorVersion: Int = 11

/**
 * Plataformas nativas soportadas por webrtc-java.
 *
 * webrtc-java publica el codigo nativo (libwebrtc) como artefactos
 * separados por clasificador. La JVM principal solo contiene el codigo
 * Java; el .so/.dll/.dylib correspondiente debe estar en el classpath.
 *
 * Ref: https://central.sonatype.com/artifact/dev.onvoid.webrtc/webrtc-java
 */
data class NativePlatform(
    val classifier: String,
    val osName: String,
    val osArch: String,
)

fun detectNativePlatform(): NativePlatform {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("linux") && (arch == "amd64" || arch == "x86_64") ->
            NativePlatform("linux-x86_64", os, arch)
        os.contains("linux") && (arch == "aarch64" || arch == "arm64") ->
            NativePlatform("linux-aarch64", os, arch)
        os.contains("linux") && (arch == "arm") ->
            NativePlatform("linux-aarch32", os, arch)
        (os.contains("mac") || os.contains("darwin")) && (arch == "aarch64" || arch == "arm64") ->
            NativePlatform("macos-aarch64", os, arch)
        (os.contains("mac") || os.contains("darwin")) && (arch == "amd64" || arch == "x86_64") ->
            NativePlatform("macos-x86_64", os, arch)
        os.contains("windows") && (arch == "amd64" || arch == "x86_64") ->
            NativePlatform("windows-x86_64", os, arch)
        os.contains("windows") && (arch == "aarch64" || arch == "arm64") ->
            NativePlatform("windows-aarch64", os, arch)
        else -> throw GradleException(
            "Plataforma WebRTC no soportada: OS=$os arch=$arch. " +
                "webrtc-java publica clasificadores para linux-x86_64, linux-aarch64, " +
                "linux-aarch32, macos-x86_64, macos-aarch64, windows-x86_64, windows-aarch64."
        )
    }
}

val nativePlatform = detectNativePlatform()
logger.lifecycle("km-webrtc: plataforma nativa detectada -> ${nativePlatform.classifier} " +
    "(${nativePlatform.osName}/${nativePlatform.osArch})")

dependencies {
    // POR ARTEFACTO y `api`: la superficie de este modulo expone tipos de
    // km-core (`TransportBackend`, `P2pProvider`, `IceConfig`), asi que quien
    // declare solo `com.km:km-webrtc` tiene que poder nombrarlos. Publicar
    // km-core antes:
    //
    //     ./gradlew :km-core:publishToMavenLocal :km-webrtc:publishToMavenLocal
    api("com.km:km-core:0.1.0-SNAPSHOT")

    // Codigo Java puro de webrtc-java (API + JNI bindings).
    implementation(libs.webrtc.java)

    // Codigo nativo (libwebrtc) para la plataforma de este entorno.
    // IMPORTANTE: el clasificador debe pasarse como coordinate con 'classifier',
    // NO como "group:name:classifier" (eso se interpretaria como version y
    // la resolucion de conflicto descartaria el artefacto nativo).
    runtimeOnly(
        variantOf(libs.webrtc.java) {
            classifier(nativePlatform.classifier)
        }
    )

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

tasks.test {
    useJUnitPlatform()
    // Los tests de integracion real levantan PeerConnections nativas.
    // Se ejecutan solo cuando se invoca explicitamente km-webrtc:test.
    testLogging {
        events("passed", "failed", "skipped")
    }
    // --enable-native-access es Java 21+; en toolchains antiguos (11) no existe.
    // webrtc-java funciona sin el flag en Java 11; solo emite un warning en 21+.
    if (toolchainMajorVersion >= 21) {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
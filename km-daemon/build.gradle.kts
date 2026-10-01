plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Mismas coordenadas que km-core: el daemon es una aplicacion que consume
// km-core como biblioteca, no un artefacto nuevo que km-core conozca.
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

    // FRONTERA ARQUITECTONICA: km-daemon depende SOLO de km-core.
    // Nunca de km-webrtc (arrastra ~25 MB de libwebrtc nativo que el relé no
    // usa) ni del cliente Android. km-core, a su vez, no depende de este
    // modulo: la dependencia va en un solo sentido.
    implementation(project(":km-core"))

    // Transporte WebSocket (RFC 6455) del lado SERVIDOR.
    //
    // La licencia real de esta version es MIT, no LGPL: verificado en el
    // `<licenses>` de su POM y en el LICENSE del tag v1.5.6. Copia literal en
    // THIRD-PARTY-LICENSES/java-websocket-LICENSE.
    implementation("org.java-websocket:Java-WebSocket:1.5.6")

    // Jackson 2.15.2, la MISMA version que declara km-core.
    //
    // Se declara aqui aunque el codec de este modulo use solo la API de arbol
    // de jackson-core: km-core declara jackson-module-kotlin como
    // `implementation`, y eso NO se filtra al classpath de compilacion de quien
    // lo consume. Sin esta linea, `run` fallaria en tiempo de ejecucion al
    // pedir Jackson por el classpath de km-core.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.2")

    // Logback: implementacion de slf4j, la que usa Java-WebSocket por debajo.
    implementation("ch.qos.logback:logback-classic:1.4.14")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

application {
    mainClass.set("com.km.daemon.KmDaemonKt")
}

tasks.test {
    useJUnitPlatform()
}

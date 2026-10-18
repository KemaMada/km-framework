plugins {
    // El plugin Kotlin NO se declara con `version` aqui: sale del catalogo
    // compartido, igual que en km-core, km-daemon, km-desktop y km-webrtc.
    alias(libs.plugins.kotlin.jvm)
    id("maven-publish")
}

// Coordenadas para consumo desde mavenLocal, IGUALES que km-core: quien
// consume km-transport acaba transitivamente en km-core, y dos `group`/`version`
// distintos obligarian a declarar la version de km-core en cada consumidor.
group = "com.km"
version = "0.1.0-SNAPSHOT"

// --------------------------------------------------------------------- toolchain
//
// Gate 0.4: `jvmToolchain(11)`, NO 27. Decidido y escrito antes de escribir este
// fichero, en `tools/mutaciones_p4/gate_0_4_toolchain.md`. El resumen:
//
//  - `jvmToolchain` fija el JDK que EMITE; `-Dorg.gradle.java.home` fija el que
//    EJECUTA el daemon. Son ortogonales, y confundirlos es el error que este
//    gate evita.
//  - km-core, km-desktop, km-webrtc y la app Android estan en 11. km-core se
//    consume POR ARTEFACTO, y un consumidor en toolchain 11 no lee bytecode 27.
//  - Publicar a 27 haria de este modulo un artefacto que NADIE de este
//    repositorio puede cargar, sin ganar nada.
//
// NO se sube el toolchain de ningun modulo existente: eso es de otra fase y
// tocaria el artefacto publicado por km-core.
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
            artifactId = "km-transport"
            version = "0.1.0-SNAPSHOT"
            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
    }
}

dependencies {
    // ---------------------------------------------------------------------
    // Framework
    // ---------------------------------------------------------------------
    //
    // POR ARTEFACTO y no como `project(":km-core")`. Es la frontera que
    // decide este cambio: km-core no gana dependencias de red, asi que el
    // transporte de red vive en un HERMANO que depende de el, nunca al reves.
    //
    // `api` y no `implementation`: km-transport EXPONE tipos de km-core en su
    // propia superficie —`TransportBackend`, `PeerTransportManager`,
    // `TransportResult`—, y un consumidor que solo declare km-transport tiene
    // que poder nombrarlos. Con `implementation` compilaria el consumidor y
    // fallaria en ejecucion, que es peor.
    //
    // Y por ARTEFACTO, y no `project(...)`, porque km-desktop ya consume km-core
    // por artefacto: mezclar las dos formas mete km-core DOS veces en el mismo
    // grafo, con el mismo `group/artifactId/version` pero distinto fichero de
    // clase, y Gradle produce `Duplicate class`. Esa es la mutacion M8-b.
    //
    // Publicar antes de compilar un consumidor aislado:
    //
    //     ./gradlew :km-core:publishToMavenLocal :km-transport:publishToMavenLocal
    //
    api("com.km:km-core:0.1.0-SNAPSHOT")

    // `JsonRelayControlCodec` (STORED, RELAY_EXPIRED) corre Jackson. km-core lo
    // declara como `implementation` y eso NO se filtra al classpath de
    // compilacion de quien la consume: sin esta linea el fallo aparece en
    // EJECUCION, no al compilar. Misma version y mismo motivo que km-desktop.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

    // ---------------------------------------------------------------------
    // Transporte: Ktor Client WebSocket (RFC 6455), dialecto KM-WIRE-RELAY
    // ---------------------------------------------------------------------
    //
    // Es el motor movido desde km-desktop. Las versiones son las que tenia
    // km-desktop, no inventadas: mover sin cambiar de version es parte de que
    // el movimiento sea puro.
    implementation("io.ktor:ktor-client-core:3.1.3")
    implementation("io.ktor:ktor-client-cio:3.1.3")
    implementation("io.ktor:ktor-client-websockets:3.1.3")

    // ---------------------------------------------------------------------
    // Serializacion del cable
    // ---------------------------------------------------------------------
    //
    // Solo su API de arbol (`buildJsonObject` / `Json.parseToJsonElement`), que
    // no necesita el plugin del compilador `kotlinx-serialization`: asi el
    // modulo no arrastra un cuarto plugin ni su conflicto de version.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")

    // ---------------------------------------------------------------------
    // SOLO PARA PRUEBAS: el rele de verdad
    // ---------------------------------------------------------------------
    //
    // `RelayAckEndToEndTest` levanta el `km-daemon` REAL —su `MessageRouter`,
    // su `AuthAdapter` y su servidor WebSocket— y conecta dos motores de verdad
    // contra el. Es el UNICO nivel que demuestra que un byte sale por un socket
    // y no solo por un doble de test.
    //
    // Es `testImplementation` a proposito: no entra en el POM publicado ni en
    // ningun binario de producto. Un producto no usa un rele en proceso.
    testImplementation(project(":km-daemon"))

    // `RelayWebSocketServer` extiende `WebSocketServer` de Java-WebSocket y
    // km-daemon lo declara como `implementation`: sin esta linea el supertipo
    // no es visible desde aqui y el test ni siquiera compila.
    testImplementation("org.java-websocket:Java-WebSocket:1.5.6")

    // El server logger de `MessageRouter` es `org.slf4j.Logger`, y tambien esta
    // declarado como `implementation` en km-daemon. Va en stderr, nunca en
    // stdout.
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

tasks.test {
    useJUnitPlatform()
}
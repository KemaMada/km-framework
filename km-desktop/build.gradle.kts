plugins {
    // El plugin Kotlin NO se declara con `version` aqui: sale del catalogo
    // compartido, igual que en km-core, km-daemon y km-webrtc. Pinear una
    // version local obliga a que el catalogo de la raiz baje para que el modulo
    // entre, y eso degrada a TODOS los modulos a la vez. Ver
    // `km-desktop/settings.gradle.kts` para el build aislado.
    alias(libs.plugins.kotlin.jvm)
    application
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.km"
version = "0.1.0-SNAPSHOT"

// Mismo toolchain que km-core: el framework publica bytecode Java 11 y este
// modulo se apoya en el, asi que bajar de 11 romperia el consumo.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    jvmToolchain(11)
}

dependencies {
    // ---------------------------------------------------------------------
    // Framework
    // ---------------------------------------------------------------------
    //
    // Se consume POR ARTEFACTO, no como `project(":km-core")`. Es la frontera
    // arquitectural del registro de skills: km-desktop es un consumidor de
    // km-core y no aloja logica de protocolo. Publicar primero:
    //
    //     ./gradlew :km-core:publishToMavenLocal
    //
    implementation("com.km:km-core:0.1.0-SNAPSHOT")

    // p4: el motor del rele (cliente WebSocket + codec del dialecto
    // KM-WIRE-RELAY) salio de aqui a `:km-transport`, su modulo propio, para
    // que km-core siga sin dependencias de red y para que un cliente que solo
    // necesite el rele no arrastre el escritorio entero.
    //
    // Tambien POR ARTEFACTO, por el mismo motivo y por el mismo riesgo: si
    // km-core se declarase aqui como `project(":km-core")` en vez de como
    // artefacto, entraria dos veces en el grafo —una desde el POM de
    // km-transport y otra desde aqui— con el mismo
    // `group/artifactId/version` y distinto fichero de clase, y Gradle
    // aborta con `Duplicate class`. Es la mutacion M8-b.
    //
    // Antes de un build AISLADO de este modulo (el `settings.gradle.kts` local
    // de km-desktop lo permite), publicar los dos:
    //
    //     ./gradlew :km-core:publishToMavenLocal :km-transport:publishToMavenLocal
    //
    implementation("com.km:km-transport:0.1.0-SNAPSHOT")

    // Jackson 2.15.2, la MISMA version que declara km-core.
    //
    // km-core la declara como `implementation`, y eso NO se filtra al
    // classpath de COMPILACION de quien la consume. Este modulo no usa Jackson
    // (el cable se serializa con kotlinx-serialization), pero SI lo ejecuta
    // `JsonRelayControlCodec`, que se invoca al recibir STORED y
    // RELAY_EXPIRED. Sin esta linea, el fallo aparece en ejecucion.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")

    // ---------------------------------------------------------------------
    // Transporte: Ktor Client WebSocket (RFC 6455), dialecto KM-WIRE-RELAY
    // ---------------------------------------------------------------------
    implementation("io.ktor:ktor-client-core:3.1.3")
    implementation("io.ktor:ktor-client-cio:3.1.3")
    implementation("io.ktor:ktor-client-websockets:3.1.3")

    // ---------------------------------------------------------------------
    // Terminal
    // ---------------------------------------------------------------------
    implementation("org.jline:jline:3.30.17")

    // `kansi` queda FUERA a proposito.
    //
    // El diseno (sdd/km-desktop-initial/design) lo pedia para el color de la
    // TUI, pero NO esta publicado en ningun repositorio accesible: ni
    // `com.github.ajalt.kansi:kansi-jvm` en Maven Central (404), ni el grupo
    // `com.github.ajalt` de Central (solo clikt, colormath, mordant,
    // timberkt), ni el repo GitHub `ajalt/kansi` (404), ni JitPack. Anadirlo
    // haria que `shadowJar` no se pudiera compilar.
    //
    // El color lo pone `com.km.desktop.Ansi`, 40 lineas de secuencias SGR, y
    // se desactiva solo si la salida no es una terminal. El sitio donde
    //Originally se declaraba la dependencia se conserva aqui como recordatorio
    // para cuando la libreria aparezca.
    // implementation("com.github.ajalt.kansi:kansi-jvm:0.1.3")

    // ---------------------------------------------------------------------
    // Serializacion del cable
    // ---------------------------------------------------------------------
    //
    // Se usa SOLO su API de arbol (`buildJsonObject` / `Json.parseToJsonElement`),
    // que no necesita el plugin del compilador `kotlinx-serialization`: asi el
    // modulo no arrastra un cuarto plugin ni su conflicto de version.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // ---------------------------------------------------------------------
    // Almacen cifrado de identidad
    // ---------------------------------------------------------------------
    //
    // Argon2id sale de BouncyCastle, y NO de `de.mkammerer:argon2-jvm`.
    //
    // Motivo, verificado EN EL ARTEFACTO: el POM de `argon2-jvm:2.11` declara
    //
    //     <license><name>GNU Lesser General Public License, Version 3</name>
    //
    // Es decir, LGPL-3.0, dentro de un proyecto MIT que verifica la licencia de
    // cada dependencia en su POM y copia el texto a THIRD-PARTY-LICENSES. Anadir
    // una LGPL con JNI nativo y bibliotecas para ocho plataformas seria una
    // obligacion de distribucion que este repositorio no tiene. Se registra
    // aqui como desviacion consciente del diseno, que pedia `argon2-jvm`.
    //
    // BouncyCastle es MIT, es YA dependencia del proyecto (`libs.bcprov`, misma
    // version que km-core) e implementa Argon2id de RFC 9106 en Java puro: sin
    // nativa, sin extraccion a temporal, y el mismo algoritmo.
    //
    // Se declara aqui y no solo por el `runtime` de km-core porque
    // `IdentityKeystore` lo llama al COMPILAR, y `implementation` no se filtra
    // al classpath de compilacion de quien consume.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")

    // Binding de SLF4J. Escribe en stderr, nunca en stdout: stdout es el
    // prompt de la TUI. El nivel se fija en `resources/simplelogger.properties`.
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")

    // ---------------------------------------------------------------------
    // SOLO PARA PRUEBAS: el rele de verdad
    // ---------------------------------------------------------------------
    //
    // `RelayAckEndToEndTest` levanta el `km-daemon` REAL —su `MessageRouter`,
    // su `AuthAdapter` y su servidor WebSocket— y conecta dos `JvmRelayTransport`
    // de verdad contra el. Es lo unico que puede cerrar el criterio de aceptacion
    // "MESSAGE(id=M) -> ACK(originalMessageId=M) -> el emisor correlaciona M" a
    // traves de un socket, en lugar de dentro de un proceso.
    //
    // Es `testImplementation` a proposito, por dos razones:
    //
    //  1. No entra en el POM publicado ni en el `shadowJar`: `km-desktop` sigue
    //     siendo un CONSUMIDOR de `km-core`, sin logica de protocolo alojada.
    //  2. La dependencia va en un sentido y este test es la UNICA excepcion
    //     declarada. Un producto no la usa.
    //
    // Lo que NO hace este test es fingir que el otro extremo es el cliente
    // Android: eso no cabe en este repositorio. La pata Android se cubre aparte,
    // sobre su propio WebSocket, con el `RelayClient` real.
    testImplementation(project(":km-daemon"))

    // `RelayWebSocketServer` extiende `WebSocketServer` de Java-WebSocket, y
    // `km-daemon` lo declara como `implementation`: sin esta linea el
    // supertipo no es visible desde aqui y el test ni siquiera compila.
    testImplementation("org.java-websocket:Java-WebSocket:1.5.6")

    // El server logger de `MessageRouter` es `org.slf4j.Logger`, y tambien esta
    // declarado como `implementation` en `km-daemon`.
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

application {
    mainClass.set("com.km.desktop.DesktopMainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    // Un binario unico con nombre estable: sin version ni classifier, para que
    // el README pueda decir `java -jar km-desktop/build/libs/km-desktop.jar` y
    // ese camino no cambie en cada release.
    archiveBaseName.set("km-desktop")
    archiveVersion.set("")
    archiveClassifier.set("")
    // `argon2-jvm` y los providers TLS de Ktor llegan como artefactos
    // multifichero; `mergeServiceFiles` es lo que mantiene el ServiceLoader de
    // Ktor apuntando al motor CIO.
    mergeServiceFiles()
    // Sin esto, shadow EXCLUYE los duplicados de `META-INF/services/*` ANTES de
    // que el `ServiceFileTransformer` los vea, y el transformer avisa de ello.
    // El resultado es un jar que arranca y luego no encuentra ni el binding de
    // SLF4J ni los proveedores de terminal de JLine. `INCLUDE` deja que el
    // transformer los fusione, que es justo lo que se quiere.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    // Sin esto, los LICENSE y NOTICE de las dependencias se pisan entre si y el
    // jar se lleva UN texto de licencia, no todos. Un binario que se distribuye
    // tiene que llevar los textos de lo que contiene, asi que se concatenan en
    // vez de descartarse. El aviso de "duplicate entries" que deja shadow es
    // justo este, y aqui se resuelve en lugar de aceptarse.
    append("META-INF/LICENSE")
    append("META-INF/LICENSE.txt")
    append("META-INF/NOTICE")
    manifest {
        attributes("Main-Class" to "com.km.desktop.DesktopMainKt")
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
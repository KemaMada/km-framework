@echo off
rem ---------------------------------------------------------------------------
rem km.cmd - lanzador de km-desktop para Windows 10+.
rem
rem Uso, tras clonar el repositorio:
rem
rem     git clone https://github.com/KemaMada/km-framework.git
rem     cd km-framework
rem     km.cmd
rem
rem La primera vez NO hay jar: este script publica km-core y km-transport en
rem mavenLocal y construye el fat jar (:km-desktop:shadowJar). Despues arranca
rem SIEMPRE con "java -jar", no con "gradlew run".
rem
rem Variables de entorno:
rem
rem   KM_REBUILD=1                 fuerza el paso de build aunque el jar exista
rem                                (Gradle decide que recompilar).
rem   KM_NO_REBUILD=1              arranca el jar existente SIN comprobar fuentes.
rem   KM_JAVA=<ruta>               JVM que ejecuta el jar (por defecto java del PATH).
rem   KM_GRADLE_JAVA_HOME=<ruta>   JDK que ejecuta Gradle. Si no se define, se usa
rem                                JAVA_HOME. El build exige JDK 27; el jar corre
rem                                en JRE 11 o superior.
rem
rem DIFERENCIA CON EL LANZADOR POSIX (`km`): en Windows no se compara la fecha de
rem cada .kt con la del jar (forfiles /D no es fiable en todas las versiones).
rem Por eso, si el jar YA existe, se reconstruye por defecto: Gradle decide que
rem recompilar. Eso exige JDK 27 en la maquina; quien solo quiera arrancar el
rem jar ya construido, sin JDK, debe definir KM_NO_REBUILD=1. Asi nunca se
rem ejecuta en silencio un jar obsoleto, que es el fallo que se esta cerrando.
rem
rem stdout pertenece al cliente: todo lo que imprime el build va a stderr.
rem ---------------------------------------------------------------------------
setlocal

set "KM_ROOT=%~dp0"
set "KM_JAR=%KM_ROOT%km-desktop\build\libs\km-desktop.jar"

if "%KM_REBUILD%"=="1" goto build
if not exist "%KM_JAR%" goto build
if "%KM_NO_REBUILD%"=="1" goto run
rem Hay jar, pero no se puede saber de forma fiable si las fuentes son mas
rem nuevas. Se reconstruye por defecto (Gradle incrementa); KM_NO_REBUILD=1
rem evita el build cuando solo se quiere arrancar el jar existente.
echo km: jar existente; se recompila por defecto en Windows (KM_NO_REBUILD=1 lo evita)... 1>&2
goto build

rem DOS invocaciones, y en este orden: primero publicar, despues compilar.
rem publishToMavenLocal NO es dependencia de :km-desktop:compileKotlin, asi que
rem pedir las tres tareas en la MISMA invocacion deja que Gradle compile
rem km-desktop mientras reescribe los jars de km-core/km-transport en
rem mavenLocal, y el compilador Kotlin muere con un error interno
rem (newPosition > limit) al leer un jar a medio escribir.
:build
echo km: construyendo km-desktop (publishToMavenLocal de km-core/km-transport + shadowJar)... 1>&2
if defined KM_GRADLE_JAVA_HOME goto build_with_java
call "%KM_ROOT%gradlew.bat" --quiet :km-core:publishToMavenLocal :km-transport:publishToMavenLocal 1>&2
if errorlevel 1 goto build_failed
call "%KM_ROOT%gradlew.bat" --quiet :km-desktop:shadowJar 1>&2
if errorlevel 1 goto build_failed
goto check_jar

:build_with_java
call "%KM_ROOT%gradlew.bat" "-Dorg.gradle.java.home=%KM_GRADLE_JAVA_HOME%" --quiet :km-core:publishToMavenLocal :km-transport:publishToMavenLocal 1>&2
if errorlevel 1 goto build_failed
call "%KM_ROOT%gradlew.bat" "-Dorg.gradle.java.home=%KM_GRADLE_JAVA_HOME%" --quiet :km-desktop:shadowJar 1>&2
if errorlevel 1 goto build_failed
goto check_jar

:check_jar
if not exist "%KM_JAR%" (
    echo km: el build termino pero no produjo "%KM_JAR%" 1>&2
    exit /b 1
)
goto run

:build_failed
echo km: el build fallo. Gradle necesita JDK 27 (KM_GRADLE_JAVA_HOME o JAVA_HOME); 1>&2
echo     el jar resultante corre en JRE 11 o superior. 1>&2
exit /b 1

:run
if defined KM_JAVA (
    "%KM_JAVA%" -jar "%KM_JAR%" %*
    exit /b %ERRORLEVEL%
)
java -jar "%KM_JAR%" %*
exit /b %ERRORLEVEL%

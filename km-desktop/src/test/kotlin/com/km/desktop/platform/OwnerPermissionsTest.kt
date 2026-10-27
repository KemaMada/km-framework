package com.km.desktop.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * 1.2 — Permisos de propietario con degradacion declarada.
 *
 * ## QUE SE AFIRMA
 *
 * - POSIX: directorio `700`, fichero `600`, exactos.
 * - Windows (ACL) simulado: se intenta POSIX, falla, se aplica ACL y NO se
 *   avisa.
 * - Sin POSIX ni ACL: se avisa UNA vez y se continua (no se lanza).
 *
 * ## POR QUE UN FONDO INYECTADO
 *
 * Porque en un JVM Linux el sistema de ficheros siempre soporta POSIX, asi que
 * la rama de Windows no se ejecutaria nunca y se publicaria sin medir. El fondo
 * falso permite ejecutar la degradacion aqui.
 */
class OwnerPermissionsTest {

    private class Fondo(
        private val posix: Boolean,
        private val acl: Boolean,
    ) : PermissionBackend {
        var posixCalls = 0
        var aclCalls = 0
        var ultimosPermisos: Set<PosixFilePermission>? = null

        override fun setPosix(path: Path, permissions: Set<PosixFilePermission>): Boolean {
            posixCalls++
            ultimosPermisos = permissions
            return posix
        }

        override fun setOwnerOnlyAcl(path: Path): Boolean {
            aclCalls++
            return acl
        }
    }

    @Test
    fun `POSIX deja directorio 700 y fichero 600`(@TempDir dir: Path) {
        val sub = Files.createDirectory(dir.resolve("config"))
        val file = Files.writeString(dir.resolve("identity.kmkey"), "x")
        val permisos = OwnerPermissions.DEFAULT

        permisos.secureDirectory(sub)
        permisos.secureFile(file)

        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            Files.getPosixFilePermissions(sub),
            "un directorio con secretos no puede ser 755",
        )
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file),
            "un fichero con material secreto no puede ser 644",
        )
    }

    @Test
    fun `si POSIX no esta se usa la ACL del propietario`() {
        val fondo = Fondo(posix = false, acl = true)
        val avisos = mutableListOf<String>()
        val permisos = OwnerPermissions(fondo) { avisos += it }

        permisos.secureFile(Path.of("peers.json"))
        permisos.secureDirectory(Path.of("keymessage"))

        assertEquals(2, fondo.aclCalls, "las dos llamadas caen a la ACL")
        assertEquals(0, avisos.size, "si la ACL funciona no hay nada que avisar")
    }

    @Test
    fun `sin POSIX ni ACL se avisa UNA vez y se continua`() {
        val fondo = Fondo(posix = false, acl = false)
        val avisos = mutableListOf<String>()
        val permisos = OwnerPermissions(fondo) { avisos += it }

        // Se llama tres veces, como en un arranque que consume varios OPK.
        permisos.secureFile(Path.of("a"))
        permisos.secureFile(Path.of("b"))
        permisos.secureDirectory(Path.of("c"))

        assertEquals(1, avisos.size, "la misma degradacion no se repite en cada guardado: $avisos")
        assertTrue(avisos.single().contains("no soporta"), "el aviso dice que no se pudo restringir")
    }

    /**
     * CONTROL NEGATIVO: los permisos que se piden son los de PROPIETARIO.
     *
     * Sin esta asercion, un cambio a `OWNER_READ` para todos o a `0777` seguiria
     * pasando las pruebas de "se llamo al fondo".
     */
    @Test
    fun `control negativo se piden exactamente 600 y 700`() {
        val fondo = Fondo(posix = true, acl = false)
        val permisos = OwnerPermissions(fondo) { }

        permisos.secureFile(Path.of("f"))
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            fondo.ultimosPermisos,
        )

        permisos.secureDirectory(Path.of("d"))
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            fondo.ultimosPermisos,
        )
    }
}

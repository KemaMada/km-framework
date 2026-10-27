package com.km.desktop.platform

import com.km.desktop.util.DiagLevel
import com.km.desktop.util.DiagLog
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Restringe un fichero o directorio al PROPIETARIO.
 *
 * ## POSIX
 *
 * `700` para directorios y `600` para ficheros. Es lo mismo que ya hacia
 * `IdentityKeystore` en linea y que el test de V1 pinza: el almacen no puede
 * quedar legible por el resto del sistema.
 *
 * ## WINDOWS
 *
 * Windows no tiene `chmod`. La traduccion es una ACL con UNA entrada `ALLOW`
 * para el propietario y sin herencia: el resto de usuarios no recibe permiso.
 * La API es [AclFileAttributeView], que forma parte de `java.nio.file` y no
 * necesita `shell-out` ni `icacls`.
 *
 * Si el sistema de ficheros no soporta POSIX ni ACL (un FAT32 montado, una
 * imagen sin atributos), se avisa UNA vez y se continua. Abortar el arranque
 * por eso dejaria al usuario sin cliente en un entorno donde no puede hacer
 * nada; seguir en silencio seria peor, porque ocultaria que el material secreto
 * queda con los permisos que traiga el sistema.
 *
 * ## POR QUE LA ADVERTENCIA ES UNA SOLA
 *
 * Porque se llama por cada escritura (cada OPK consumido reescribe el almacen)
 * y repetir la misma linea en cada guardado convertiria el log en ruido.
 */
class OwnerPermissions internal constructor(
    private val backend: PermissionBackend = SystemPermissionBackend,
    private val warn: (String) -> Unit = { message ->
        DiagLog.event("[INTEGRITY]", "permisos: $message", DiagLevel.WARN)
    },
) {

    private val warned = AtomicBoolean(false)

    /** Deja [path] en `700` (POSIX) o su equivalente de propietario en Windows. */
    fun secureDirectory(path: Path) {
        apply(path, DIRECTORY_PERMISSIONS, "directorio")
    }

    /** Deja [path] en `600` (POSIX) o su equivalente de propietario en Windows. */
    fun secureFile(path: Path) {
        apply(path, FILE_PERMISSIONS, "fichero")
    }

    private fun apply(path: Path, permissions: Set<PosixFilePermission>, kind: String) {
        if (backend.setPosix(path, permissions)) return
        if (backend.setOwnerOnlyAcl(path)) return
        if (warned.compareAndSet(false, true)) {
            warn(
                "este sistema no soporta permisos POSIX ni ACL de propietario: " +
                    "el $kind $path conserva los permisos del sistema"
            )
        }
    }

    companion object {
        /** Instancia de produccion. */
        val DEFAULT: OwnerPermissions = OwnerPermissions()

        /** `rwx------`: solo el propietario entra en el directorio. */
        internal val DIRECTORY_PERMISSIONS: Set<PosixFilePermission> = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )

        /** `rw-------`: solo el propietario lee y escribe el fichero. */
        internal val FILE_PERMISSIONS: Set<PosixFilePermission> = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}

/**
 * Las dos operaciones que [OwnerPermissions] necesita del sistema.
 *
 * Es una interfaz y no llamadas directas para poder probar la degradacion de
 * Windows en un JVM Linux: si estuviera cableada, la rama de ACL no se
 * ejecutaria nunca en CI y se publicaria sin medir.
 */
internal interface PermissionBackend {
    /** Aplica permisos POSIX. Devuelve `false` si el sistema no los soporta. */
    fun setPosix(path: Path, permissions: Set<PosixFilePermission>): Boolean

    /** Aplica una ACL de solo propietario. Devuelve `false` si no se puede. */
    fun setOwnerOnlyAcl(path: Path): Boolean
}

/** Implementacion real sobre `java.nio.file`. */
internal object SystemPermissionBackend : PermissionBackend {

    override fun setPosix(path: Path, permissions: Set<PosixFilePermission>): Boolean {
        val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
            ?: return false
        return runCatching { view.setPermissions(permissions) }.isSuccess
    }

    override fun setOwnerOnlyAcl(path: Path): Boolean {
        val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            ?: return false
        return runCatching {
            val owner = view.owner ?: Files.getOwner(path)
            val ownerOnly = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(AclEntryPermission.values().toSet())
                .build()
            // Una ACL con una sola entrada y sin herencia: el propietario
            // conserva el control y ningun otro principal recibe permiso.
            view.acl = listOf(ownerOnly)
        }.isSuccess
    }
}

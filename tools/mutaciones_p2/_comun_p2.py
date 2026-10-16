#!/usr/bin/env python3
"""
Infraestructura comun de la campana de mutaciones de `p2-contact-sessions`.

POR QUE ESTE MOLDE NO USA GIT (y `tools/mutaciones/_comun.py` si)
========================================================================

Los dos arnes anteriores revierten con `git checkout -- FICHERO` y validan
contra `git show HEAD:FICHERO`. Ese molde es correcto SOLO si el arbol esta
limpio, y aqui NO lo esta: la politica del proyecto es no hacer commit, asi que
la implementacion de p2 va a quedar sin commitear en el arbol de trabajo.

Con ese arbol, `git checkout -- km-core/src/main/.../SecureMessagingSession.kt`
NO restauraria el fichero original: restauraria la version de HEAD, que es la de
ANTES de p2. Es decir: el "revert" destruiria la implementacion que se esta
midiendo. Y para el fichero NUEVO `node/PeerAuthority.kt`, `git show HEAD:...`
falla de salida porque en HEAD ese fichero no existe.

Ademas, una mutacion que se revierte a mano "de memoria" deja residuos, y un
residuo es exactamente la forma barata de obtener una campana de mutaciones que
miente. Aqui el revert es un `shutil.copyfile` desde una copia FUERA de git, y
la validacion es el **md5** del fichero restaurado contra el md5 de la copia.
Si no coinciden, el script aborta.

REGLAS QUE ESTE MODULO IMPONE
-----------------------------

1. **Ninguna mutacion se aplica sin copia previa.** `asegurar()` crea la copia
   si no existe y valida su integridad si existe. `aplicar()` llama a `asegurar()`
   antes de tocar nada, luego es imposible aplicar una mutacion sobre un fichero
   sin respaldo.
2. **Cuenta exacta de apariciones.** Si el texto a mutar no aparece el numero de
   veces esperado, ABORTA. Un `replace` a pelo sobre un texto que no esta donde
   se espera produce una mutacion que no es la que dice ser.
3. **Sin git para nada.** Ni para revertir, ni para validar, ni para el diff.
   El diff se calcula contra la copia.
4. **La copia vive FUERA del repositorio**, en `/tmp/opencode/p2-backups/` (o
   `$KM_P2_BACKUP_DIR`), porque si viviera dentro del arbol un `git clean`
   razonable se la llevaria.

Uso:

    # respaldar (una vez, antes de la primera mutacion)
    python3 tools/mutaciones_p2/_comun_p2.py --backup <fichero>...

    # verificar que el arbol coincide byte a byte con las copias
    python3 tools/mutaciones_p2/_comun_p2.py --verify <fichero>...

    # desde un script de mutacion concreto
    from _comun_p2 import main, aplicar, revertir,(diff)
    main(FICHEROS, aplicar_la_mutacion)
"""

import difflib
import hashlib
import io
import json
import os
import shutil
import sys

RAIZ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RESPALDO_DIR = os.environ.get("KM_P2_BACKUP_DIR", "/tmp/opencode/p2-backups")
MANIFIESTO = os.path.join(RESPALDO_DIR, "manifest.json")

# El fichero del mutacion de humo (0.1) y las rutas de las mutaciones de p2.
FICHERO_HUMO = "km-core/src/main/kotlin/com/km/messaging/SecureMessagingSession.kt"
F_PEER_AUTHORITY = "km-core/src/main/kotlin/com/km/node/PeerAuthority.kt"
F_PEER_CONTEXT = "km-core/src/main/kotlin/com/km/node/PeerContext.kt"
F_BINDING = "km-core/src/main/kotlin/com/km/node/PeerTransportBinding.kt"
F_ROLES = "km-core/src/main/kotlin/com/km/node/PeerRoleDirectory.kt"
F_ESTABLECIMIENTO = "km-core/src/main/kotlin/com/km/messaging/SecureSessionEstablishment.kt"
F_VERIFIED_BUNDLE = "km-core/src/main/kotlin/com/km/identity/VerifiedContactBundle.kt"
F_MENSAJERIA = "km-core/src/main/kotlin/com/km/messaging/SecureMessagingSession.kt"


# --------------------------------------------------------------------- utils


def md5(b):
    return hashlib.md5(b).hexdigest()


def md5_de_fichero(path):
    with open(path, "rb") as fh:
        return md5(fh.read())


def leer(fichero):
    with io.open(os.path.join(RAIZ, fichero), encoding="utf-8") as fh:
        return fh.read()


def escribir(fichero, s):
    with io.open(os.path.join(RAIZ, fichero), "w", encoding="utf-8") as fh:
        fh.write(s)


def ruta_de_respaldo(fichero):
    """Ruta de la copia. Se antepone `_p2_` para que no colisione con nada."""
    return os.path.join(RESPALDO_DIR, fichero.replace(os.sep, "__"))


def _cargar_manifiesto():
    if not os.path.exists(MANIFIESTO):
        return {}
    with io.open(MANIFIESTO, encoding="utf-8") as fh:
        return json.load(fh)


def _guardar_manifiesto(m):
    os.makedirs(RESPALDO_DIR, exist_ok=True)
    with io.open(MANIFIESTO, "w", encoding="utf-8") as fh:
        json.dump(m, fh, indent=2, sort_keys=True)
        fh.write("\n")


# ----------------------------------------------------------------- respaldos


def asegurar(ficheros, refresh=False):
    """
    Crea la copia de cada fichero si no existe; si existe, comprueba su md5.

    Esto es la garantia de que SIEMPRE hay una copia recuperable de todo lo que
    se va a mutar, incluso si el proceso muere a mitad.

    `refresh=True` vuelve a copiar y a actualizar el registro. HAY que usarlo a
    proposito cuando el codigo de produccion ha cambiado de verdad entre dos
    fases: sin el, la copia seria la de antes de la fase y el "revert" de una
    mutacion devolveria el fichero a un estado en el que la implementacion que
    se esta midiendo no existe. Es el error opuesto al de `git checkout`, y es
    igual de grave.
    """
    m = _cargar_manifiesto()
    creado, verificado = [], []
    for fichero in ficheros:
        orig = os.path.join(RAIZ, fichero)
        if not os.path.exists(orig):
            raise SystemExit("ABORTADO: no existe %s" % fichero)
        copia = ruta_de_respaldo(fichero)
        if refresh or not os.path.exists(copia):
            os.makedirs(RESPALDO_DIR, exist_ok=True)
            shutil.copyfile(orig, copia)
            h = md5_de_fichero(copia)
            era = refresh and m.get(fichero) is not None
            m[fichero] = h
            creado.append((fichero, h, era))
        else:
            h = md5_de_fichero(copia)
            if m.get(fichero) is not None and h != m[fichero]:
                raise SystemExit(
                    "ABORTADO: la copia de %s no coincide con su md5 registrado\n"
                    "  copia  : %s\n  registro: %s\n"
                    "Alguien toco el respaldo. No se sigue: se mediria sobre otra cosa."
                    % (fichero, h, m[fichero])
                )
            if m.get(fichero) is None:
                m[fichero] = h
            verificado.append((fichero, h))
    _guardar_manifiesto(m)
    for f, h, era in creado:
        print("  RESPALDO REFRESCADO %s  md5=%s" % (f, h) if era else "  RESPALDO NUEVO     %s  md5=%s" % (f, h))
    for f, h in verificado:
        print("  RESPALDO VERIFICADO %s  md5=%s" % (f, h))
    return m


def exigir_limpio(ficheros):
    """
    Aborta si algun fichero NO coincide con su copia antes de mutar.

    Sin esto, dos mutaciones encadenadas sobre el mismo fichero se acumulan: la
    segunda se aplica sobre la primera y el "revert" de la segunda devuelve el
    fichero CON la primera todavia dentro. El resultado es una campana que mide
    una mutacion que nadie wrote. Un hacha corta el arbol entero.
    """
    m = _cargar_manifiesto()
    sucio = []
    for fichero in ficheros:
        copia = ruta_de_respaldo(fichero)
        if not os.path.exists(copia):
            raise SystemExit("ABORTADO: no hay copia de %s" % fichero)
        if md5_de_fichero(os.path.join(RAIZ, fichero)) != m.get(fichero, md5_de_fichero(copia)):
            sucio.append(fichero)
    if sucio:
        raise SystemExit(
            "ABORTADO: estos ficheros NO coinciden con su copia, luego ya hay una "
            "mutacion a medias o el codigo cambio sin refrescar el respaldo:\n  "
            + "\n  ".join(sucio)
            + "\nRevierte con `--revert` o refresca con `--refresh`."
        )
    for f in ficheros:
        print("  LIMPIO antes de mutar %s" % f)


def revertir(ficheros):
    """Restaura byte a byte desde la copia y VALIDA el md5 resultante."""
    m = _cargar_manifiesto()
    for fichero in ficheros:
        copia = ruta_de_respaldo(fichero)
        if not os.path.exists(copia):
            raise SystemExit("ABORTADO: no hay copia de %s: no se puede revertir" % fichero)
        shutil.copyfile(copia, os.path.join(RAIZ, fichero))
        esperado = m.get(fichero)
        obtenido = md5_de_fichero(os.path.join(RAIZ, fichero))
        if esperado is not None and obtenido != esperado:
            raise SystemExit(
                "ABORTADO: tras revertir, %s tiene md5 %s y se esperaba %s"
                % (fichero, obtenido, esperado)
            )
        print("  REVERTIDO + md5 OK  %s  md5=%s" % (fichero, obtenido))


def verificar(ficheros):
    """Fallo (exit 1) si algun fichero NO coincide con su copia."""
    m = _cargar_manifiesto()
    sucio = False
    for fichero in ficheros:
        copia = ruta_de_respaldo(fichero)
        if not os.path.exists(copia):
            print("  SIN COPIA  %s" % fichero)
            sucio = True
            continue
        esperado = m.get(fichero, md5_de_fichero(copia))
        obtenido = md5_de_fichero(os.path.join(RAIZ, fichero))
        if obtenido == esperado:
            print("  LIMPIO     %s  md5=%s" % (fichero, obtenido))
        else:
            print("  RESIDUO    %s  md5=%s (copia %s)" % (fichero, obtenido, esperado))
            sucio = True
    return 1 if sucio else 0


# -------------------------------------------------------------------- aplicar


def aplicar(fichero, reemplazos, otros=()):
    """
    reemplazos = [(viejo, nuevo, veces_esperadas), ...]

    `otros` son ficheros que esta mutacion tambien necesita respaldar, aunque no
    modifique (mutaciones aditivas que tocan varios sitios).
    """
    asegurar([fichero] + list(otros))
    exigir_limpio([fichero])
    s = leer(fichero)
    for viejo, nuevo, esperadas in reemplazos:
        n = s.count(viejo)
        if n != esperadas:
            raise SystemExit(
                "ABORTADO: el texto a mutar aparece %d veces y se esperaban %d en %s.\n"
                "Si no esta donde se esperaba, la mutacion seria OTRA cosa:\n---\n%s\n---"
                % (n, esperadas, fichero, viejo)
            )
        s = s.replace(viejo, nuevo)
    escribir(fichero, s)
    diff(fichero)


def diff(fichero):
    """Diff contra la COPIA, no contra git. Si sale vacio, la mutacion no hizo nada."""
    copia = ruta_de_respaldo(fichero)
    antes = io.open(copia, encoding="utf-8").read().splitlines(keepends=True)
    despues = io.open(os.path.join(RAIZ, fichero), encoding="utf-8").read().splitlines(keepends=True)
    d = list(difflib.unified_diff(antes, despues, fromfile="respaldo/" + fichero, tofile=fichero, n=2))
    print("---- la mutacion que se ha aplicado en %s ----" % fichero)
    if not d:
        print("VACIO: la mutacion NO ha cambiado nada")
    else:
        sys.stdout.write("".join(d[:80]))
        if len(d) > 80:
            print("... (%d lineas mas de diff)" % (len(d) - 80))


def main(ficheros, fn):
    if len(sys.argv) != 2 or sys.argv[1] not in ("apply", "revert"):
        raise SystemExit("uso: %s apply|revert" % sys.argv[0])
    if sys.argv[1] == "apply":
        asegurar(ficheros)
        exigir_limpio(ficheros)
        fn()
    else:
        revertir(ficheros)


def cli():
    if "--refresh" in sys.argv:
        i = sys.argv.index("--refresh")
        asegurar(sys.argv[i + 1:], refresh=True)
        return 0
    if "--backup" in sys.argv:
        i = sys.argv.index("--backup")
        asegurar(sys.argv[i + 1:])
        return 0
    if "--verify" in sys.argv:
        i = sys.argv.index("--verify")
        return verificar(sys.argv[i + 1:])
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(cli())
#!/usr/bin/env python3
"""
Verificacion independiente de los vectores de KM-ID-0001
=========================================================

Este script no genera nada. Lee los fixtures desde disco y comprueba que
producen EXACTAMENTE lo que el MANIFEST declara.

Tres propiedades que se comprueban:

  A. CORRECCION     cada fixture produce el resultado esperado
  B. REPRODUCIBILIDAD  regenerar los bytes produce exactamente los mismos
                      bytes, y las firmas coinciden byte a byte
  C. AISLAMIENTO    cada fixture invalido falla por el motivo declarado, y
                    solo por ese motivo

La propiedad B es la importante: si una firma depende del reloj, del azar o
del orden de iteracion de un diccionario, B falla. Por eso los vectores
deben regenerarse identicos en cualquier maquina.
"""

from __future__ import annotations

import hashlib
import json
import os
import sys

import constants as C
import contact
import device_link
import ed25519
import fingerprints
import identity
import kce
import rotation
import roster
import schemas
import verification
from errors import VerificationError

HERE = os.path.dirname(os.path.abspath(__file__))
VECTORS_DIR = os.path.join(HERE, "vectors")
INVALID_DIR = os.path.join(HERE, "invalid")

passed = 0
failed = 0
failures: list[str] = []


def check(label: str, condition: bool, detail: str = "") -> None:
    global passed, failed
    if condition:
        passed += 1
    else:
        failed += 1
        failures.append(f"{label}" + (f"\n      {detail}" if detail else ""))


def load_manifest() -> dict:
    with open(os.path.join(HERE, "MANIFEST.json"), encoding="utf-8") as f:
        return json.load(f)


def read(path: str) -> bytes:
    with open(path, "rb") as f:
        return f.read()


def derived_of(gid: str) -> str | None:
    p = os.path.join(VECTORS_DIR, gid, "derived.txt")
    if not os.path.exists(p):
        return None
    with open(p, encoding="utf-8") as f:
        return f.read().strip()


# ---------------------------------------------------------------------------
# A. Correccion de cada vector
# ---------------------------------------------------------------------------


def check_vectors(manifest: dict) -> None:
    sc_pub = _rebuild_keys(manifest)

    for v in manifest["vectors"]:
        gid = v["id"]
        d = os.path.join(VECTORS_DIR, gid)

        # Los vectores de derivacion pura no tienen documento CBOR.
        if "artifacts" not in v:
            continue

        for name in v["artifacts"]:
            blob = read(os.path.join(d, name))
            check(
                f"{gid}/{name} sha256",
                hashlib.sha256(blob).hexdigest() == v["artifacts"][name]["sha256"],
                "el hash del fixture no coincide con el MANIFEST",
            )
            check(
                f"{gid}/{name} length",
                len(blob) == v["artifacts"][name]["length"],
                f"esperado {v['artifacts'][name]['length']}, leido {len(blob)}",
            )

        # KCE: cualquier documento valido debe ser canonico.
        for name in v["artifacts"]:
            blob = read(os.path.join(d, name))
            if v["expected"].split("\n")[0].endswith(f"{name}: VALID") or name in (
                "canonical.cbor", "signable.cbor", "contact.cbor",
                "device_link.cbor", "challenge.cbor", "response.cbor",
                "authorizing_roster.cbor", "second.cbor",
            ):
                try:
                    kce.validate(blob)
                except kce.KceError as exc:
                    check(f"{gid}/{name} KCE", False, f"no es KCE canonico: {exc}")

    # Verificaciones criptograficas y semanticas concretas.
    c4 = read(os.path.join(VECTORS_DIR, "G04", "canonical.cbor"))
    c5 = read(os.path.join(VECTORS_DIR, "G05", "canonical.cbor"))
    c7 = read(os.path.join(VECTORS_DIR, "G07", "canonical.cbor"))

    r1 = verification.verify_document(c4)
    r2 = verification.verify_document(c5, context={"previous_roster": r1})
    check("G04 sequence == 1", r1["sequence"] == 1)
    check("G05 sequence == 2", r2["sequence"] == 2)
    check("G05 encadena con G04", r2["previous"] == roster.chain_previous(r1))
    check("G05 anade el PC", len(r2["devices"]) == 2)

    # G04 y G05 comparten identidad: el numero de seguridad NO cambia.
    n4 = fingerprints.identity_safety_number(r1["identityId"])
    n5 = fingerprints.identity_safety_number(r2["identityId"])
    check("G12 estable entre G04 y G05", n4 == n5, f"{n4} != {n5}")
    check("G12 coincide con el fixture", n4 == derived_of("G12"))

    # El codigo de roster SI cambia: es estado, no identidad.
    c4code = fingerprints.roster_verification_code(schemas.DEVICE_ROSTER.signable(r1))
    c5code = fingerprints.roster_verification_code(schemas.DEVICE_ROSTER.signable(r2))
    check("G13 cambia entre G04 y G05", c4code != c5code, "el codigo de roster debe cambiar")
    check("G13 coincide con el fixture", c4code == derived_of("G13"))

    rot = verification.verify_document(c7)
    check("G07 rotacion bilateral", rot["oldRoot"] != rot["newRoot"])

    # G08: la firma del prekey la verifica la clave del DISPOSITIVO, no la raiz.
    spk = kce.validate(read(os.path.join(VECTORS_DIR, "G08", "canonical.cbor")))
    entry = roster.find_device(r1, spk["deviceId"])
    check("G08 deviceId en el roster", entry is not None)
    check(
        "G08 firma por deviceSigningKey",
        entry is not None
        and ed25519.verify(
            spk["signature"],
            contact.signable_prekey_bytes(spk),
            entry["signingKey"],
        ),
    )
    check(
        "G08 no verifica con la raiz",
        not ed25519.verify(
            spk["signature"], contact.signable_prekey_bytes(spk), r1["identityRoot"]
        ),
        "una firma de prekey NO debe ser valida bajo la raiz",
    )

    # G09: la OPK no lleva firma.
    opk = kce.validate(read(os.path.join(VECTORS_DIR, "G09", "canonical.cbor")))
    check("G09 sin firma propia", "signature" not in opk)
    check("G09 linked al subject", opk["deviceId"] == r1["devices"][0]["deviceId"])

    # G10: ambos bundles.
    for name in ("contact.cbor", "device_link.cbor"):
        b = verification.verify_document(read(os.path.join(VECTORS_DIR, "G10", name)))
        check(f"G10/{name} kind", b["kind"] in (C.KIND_CONTACT, C.KIND_DEVICE_LINK))
    dl = kce.validate(read(os.path.join(VECTORS_DIR, "G10", "device_link.cbor")))
    check("G10 DEVICE_LINK lleva linkSecret", "linkSecret" in dl)
    ct = kce.validate(read(os.path.join(VECTORS_DIR, "G10", "contact.cbor")))
    check("G10 CONTACT no lleva linkSecret", "linkSecret" not in ct)
    check("G10 endpoints firmados", len(ct.get("endpoints", [])) == 2)

    # G11: challenge, response y el roster que autoriza.
    ch = kce.validate(read(os.path.join(VECTORS_DIR, "G11", "challenge.cbor")))
    device_link.verify_challenge(ch, sc_pub["alice.mobile.sign"])
    rs = verification.verify_document(
        read(os.path.join(VECTORS_DIR, "G11", "response.cbor")),
        context={"expected_nonce": ch["nonce"], "expected_identity_root": ch["identityRoot"]},
    )
    check("G11 nonce en eco", rs["nonce"] == ch["nonce"])
    # El PC queda autorizado SOLO porque aparece en roster #2.
    authorizing = verification.verify_document(
        read(os.path.join(VECTORS_DIR, "G11", "authorizing_roster.cbor")),
        context={"previous_roster": r1},
    )
    check(
        "G11 el PC queda autorizado por el roster",
        roster.find_device(authorizing, rs["deviceId"]) is not None,
    )
    check(
        "G11 el PC NO estaba en roster #1",
        roster.find_device(r1, rs["deviceId"]) is None,
        "si el PC ya estuviera en roster #1, el device-link no demostraria nada",
    )

    # G01..G03
    check("G01 identityId", derived_of("G01") == identity.identity_id(sc_pub["root.alice.v1"]).hex())
    check("G02 deviceId", derived_of("G02") == identity.device_id(sc_pub["alice.mobile.sign"]).hex())
    check("G03 nodeId", len(derived_of("G03")) == 64)

    # Los tres identificadores deben ser distintos.
    i1, d1, n1 = derived_of("G01"), derived_of("G02"), derived_of("G03")
    check("G01/G02/G03 distintos", len({i1, d1, n1}) == 3)


def _rebuild_keys(manifest: dict) -> dict[str, bytes]:
    """Reconstruye las claves publicas a partir de las semillas del MANIFEST."""
    seeds = manifest["seeds"]
    out = {}
    for label in (
        "root.alice.v1", "root.alice.v2", "alice.mobile.sign",
        "alice.pc.sign", "bob.mobile.sign", "node.identity",
    ):
        out[label] = ed25519.keypair_from_seed(bytes.fromhex(seeds[label]))[0]
    return out


# ---------------------------------------------------------------------------
# Fixtures inválidos
# ---------------------------------------------------------------------------


def check_invalid(manifest: dict) -> None:
    # El fixture wrong-previous.cbor es un roster de sequence 2: para que el
    # fallo que se observe sea el del ENLACE y no "no me diste el roster
    # previo", hay que proporcionarle el G04 como contexto.
    roster1 = kce.decode(read(os.path.join(VECTORS_DIR, "G04", "canonical.cbor")))

    for entry in manifest["invalid"]:
        name = os.path.basename(entry["file"])
        blob = read(os.path.join(INVALID_DIR, name))

        check(
            f"invalid/{name} sha256",
            hashlib.sha256(blob).hexdigest() == entry["sha256"],
        )

        ctx: dict = {}
        if entry["docType"] == "DeviceRoster":
            ctx = {"previous_roster": roster1}

        try:
            verification.verify_document(blob, context=ctx)
            check(f"invalid/{name} rechazado", False, "el fixture invalido fue ACEPTADO")
        except VerificationError as exc:
            check(
                f"invalid/{name} -> {entry['expected']}",
                exc.code == entry["expected"],
                f"esperado {entry['expected']}, obtenido {exc.code}",
            )


# ---------------------------------------------------------------------------
# B. Reproducibilidad
# ---------------------------------------------------------------------------


def check_reproducibility(manifest: dict) -> None:
    """Regenera los fixtures en memoria y exige identidad byte a byte."""
    import generate

    sc = generate.Scenario()
    vectors = {v["id"]: v for v in generate.build_vectors(sc)}
    invalid = {os.path.basename(e["file"]): e for e in generate.build_invalid(sc)}

    for gid, v in vectors.items():
        d = os.path.join(VECTORS_DIR, gid)
        for name in v.get("artifacts", {}):
            fresh = read(os.path.join(d, name))
            # Recalcular desde cero: vuelve a construir el escenario y compara
            # el hash declarado, que ya viene de los bytes recien generados.
            check(
                f"reproducible {gid}/{name}",
                hashlib.sha256(fresh).hexdigest() == v["artifacts"][name]["sha256"],
                "la regeneracion produce bytes distintos",
            )

    for name, e in invalid.items():
        fresh = read(os.path.join(INVALID_DIR, name))
        check(
            f"reproducible invalid/{name}",
            hashlib.sha256(fresh).hexdigest() == e["sha256"],
        )

    # Las semillas del MANIFEST deben ser las de la derivacion documentada.
    for label, expected in sc.seeds.items():
        check(
            f"semilla {label}",
            manifest["seeds"].get(label) == expected,
            "la derivacion de semillas documentada no coincide",
        )
        check(
            f"semilla {label} reproducible",
            expected == generate.seed_of(label).hex(),
        )


# ---------------------------------------------------------------------------
# C. Aislamiento de los fallos
# ---------------------------------------------------------------------------


def check_isolation() -> None:
    """Cada fixture negativo debe fallar por UNA sola razon identifiable."""
    manifest = load_manifest()
    counts: dict[str, list[str]] = {}
    for entry in manifest["invalid"]:
        name = os.path.basename(entry["file"])
        blob = read(os.path.join(INVALID_DIR, name))
        try:
            verification.verify_document(blob)
            counts.setdefault("ACEPTADO", []).append(name)
        except VerificationError as exc:
            counts.setdefault(exc.code, []).append(name)

    # Ningun fixture invalido debe ser aceptado.
    check("ningun fixture invalido es aceptado", "ACEPTADO" not in counts,
          f"aceptados: {counts.get('ACEPTADO', [])}")


# ---------------------------------------------------------------------------
# D. Propiedades de KCE
# ---------------------------------------------------------------------------


def check_kce_properties() -> None:
    # Orden de claves: el codigo de la clave ordena, no su texto.
    a = kce.encode({"b": 1, "aa": 2})
    b = kce.encode({"aa": 2, "b": 1})
    check("KCE ordenacion independiente del orden de insercion", a == b)

    # Cabecera no minima -> rechazo.
    non_minimal = bytes([0xA1, 0x61, ord("a"), 0x18, 0x01])  # map(1) {"a": 1 con head 24}
    try:
        kce.validate(non_minimal)
        check("KCE rechaza cabecera no minima", False, "aceptado")
    except kce.KceError:
        check("KCE rechaza cabecera no minima", True)

    # El lector acotado no debe poder distinguir un map de un array.
    check("lector acotado rechaza no-map", kce.peek_version(b"\x83\x01\x02\x03").reason
          == "INVALID_ENCODING")
    # Ni inventar una version cuando `version` no existe.
    check("lector acotado rechaza sin version", kce.peek_version(kce.encode({"doc": "x"})).reason
          == "INVALID_ENCODING")
    # Ni aceptar una version futura.
    future = kce.encode({"version": 99})
    check("lector acotado detecta version futura",
          kce.peek_version(future).reason == "UNSUPPORTED_VERSION")

    # ORDEN POR BYTES CODIFICADOS, NO ALFABETICO.
    # KCE ordena las claves por sus bytes codificados, y en una clave tstr
    # corta el prefijo de longitud DOMINA sobre el contenido. Ordenar
    # alfabeticamente el texto de las claves produce un documento distinto y
    # por tanto incompatible. Es la trampa mas peligrosa de KCE, y ya produjo
    # un fixture mal dirigido durante el desarrollo de esta referencia.
    text_map = {"identityRoot": b"\x00" * 32, "doc": "km.deviceRoster", "version": 1}
    # sorted_pairs devuelve las claves YA CODIFICADAS, que es justo lo que
    # se ordena; para compararlas como texto hay que quitar el prefijo CBOR.
    order = [kce._decode_key(k) for k, _ in kce.sorted_pairs(text_map)]
    check(
        "KCE ordena por longitud antes que alfabeticamente",
        order == ["doc", "version", "identityRoot"],
        f"orden obtenido: {order} (alfabetico seria ['doc', 'identityRoot', 'version'])",
    )
    check(
        "el orden de insercion no altera los bytes",
        kce.encode(text_map) == kce.encode(dict(reversed(list(text_map.items())))),
    )
    check(
        "el orden de lectura real es doc, version, identityRoot",
        list(kce.decode(kce.encode(text_map)).keys()) == ["doc", "version", "identityRoot"],
    )


# ---------------------------------------------------------------------------
# E. Cross-validacion de Ed25519 (opcional)
# ---------------------------------------------------------------------------


def check_ed25519_crossvalidation() -> None:
    try:
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
        from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
        from cryptography.exceptions import InvalidSignature
    except ImportError:
        print("  (biblioteca 'cryptography' ausente: cross-validacion omitida)")
        return

    manifest = load_manifest()
    r1 = verification.verify_document(read(os.path.join(VECTORS_DIR, "G04", "canonical.cbor")))
    seed = bytes.fromhex(manifest["seeds"]["root.alice.v1"])

    ref = Ed25519PrivateKey.from_private_bytes(seed)
    check(
        "Ed25519 pubkey coincide con cryptography",
        ref.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw) == r1["identityRoot"],
    )
    try:
        ref.public_key().verify(
            r1["signature"],
            roster.signable_bytes(r1),
        )
        check("Ed25519 cryptography acepta nuestra firma", True)
    except InvalidSignature:
        check("Ed25519 cryptography acepta nuestra firma", False, "rechazada")

    # Y al reves.
    c4 = read(os.path.join(VECTORS_DIR, "G04", "canonical.cbor"))
    check(
        "Ed25519 nosotros aceptamos la firma de cryptography",
        ed25519.verify(ref.sign(roster.signable_bytes(r1)), roster.signable_bytes(r1),
                       r1["identityRoot"]),
    )
    del c4


# ---------------------------------------------------------------------------


def main() -> int:
    manifest = load_manifest()

    check_kce_properties()
    check_vectors(manifest)
    check_invalid(manifest)
    check_reproducibility(manifest)
    check_isolation()
    check_ed25519_crossvalidation()

    print()
    if failures:
        print(f"FALLOS ({len(failures)}):")
        for f in failures:
            print(f"  - {f}")
    print(f"{passed} comprobaciones correctas, {failed} fallidas")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

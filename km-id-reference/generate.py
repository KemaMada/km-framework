#!/usr/bin/env python3
"""
Generador de vectores de prueba de KM-ID-0001
==============================================

Produce los paquetes G01..G13 y el directorio `invalid/`, y escribe un
MANIFEST con la procedencia de cada artefacto.

Los vectores NO se escriben a mano. Se calculan ejecutando la
implementacion de referencia, de modo que si un valor fuera incorrecto,
el error queda en el codigo y no disfrazado de constante normativa en el
RFC.

Cada paquete incluye `keys.json` con las semillas deterministas utilizadas,
para que otra implementacion (Kotlin, Rust, Go) pueda reproducir
exactamente las mismas firmas.
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
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

HERE = os.path.dirname(os.path.abspath(__file__))
VECTORS_DIR = os.path.join(HERE, "vectors")
INVALID_DIR = os.path.join(HERE, "invalid")

SEED_PREFIX = "KM-ID-0001/"

# Etiquetas de material de clave. Cambiar cualquiera de estas invalida
# todos los vectores: las semillas son parte del contrato de los fixtures.
ED25519_LABELS = [
    "root.alice.v1",
    "root.alice.v2",
    "alice.mobile.sign",
    "alice.pc.sign",
    "bob.mobile.sign",
]
OPAQUE_LABELS = [
    "alice.mobile.agr",
    "alice.pc.agr",
    "bob.mobile.agr",
    "alice.mobile.spk",
    "alice.mobile.opk.1000",
    "alice.mobile.opk.1001",
    "alice.pc.spk",
    "nonce.link.1",
    "linksecret.1",
]


def seed_of(label: str) -> bytes:
    return hashlib.sha256((SEED_PREFIX + label).encode("utf-8")).digest()


# ---------------------------------------------------------------------------
# Escenario determinista
# ---------------------------------------------------------------------------


class Scenario:
    def __init__(self) -> None:
        self.seeds: dict[str, str] = {}
        self.pub: dict[str, bytes] = {}
        self.priv: dict[str, bytes] = {}
        self.opaque: dict[str, bytes] = {}

        for label in ED25519_LABELS:
            s = seed_of(label)
            pk, sk = ed25519.keypair_from_seed(s)
            self.seeds[label] = s.hex()
            self.pub[label] = pk
            self.priv[label] = sk

        for label in OPAQUE_LABELS:
            blob = seed_of(label)
            self.seeds[label] = blob.hex()
            self.opaque[label] = blob

        # Material derivado
        self.identity_id_v1 = identity.identity_id(self.pub["root.alice.v1"])
        self.identity_id_v2 = identity.identity_id(self.pub["root.alice.v2"])
        self.alice_mobile_id = identity.device_id(self.pub["alice.mobile.sign"])
        self.alice_pc_id = identity.device_id(self.pub["alice.pc.sign"])
        self.bob_mobile_id = identity.device_id(self.pub["bob.mobile.sign"])
        self.node_identity = ed25519.keypair_from_seed(seed_of("node.identity"))
        self.seeds["node.identity"] = seed_of("node.identity").hex()

        # Dispositivos
        self.device_alice_mobile = roster.make_device_entry(
            self.pub["alice.mobile.sign"],
            self.opaque["alice.mobile.agr"],
            name="Alice-Android",
        )
        self.device_alice_pc = roster.make_device_entry(
            self.pub["alice.pc.sign"],
            self.opaque["alice.pc.agr"],
            name="Alice-PC",
        )
        self.device_bob_mobile = roster.make_device_entry(
            self.pub["bob.mobile.sign"],
            self.opaque["bob.mobile.agr"],
            name="Bob-Phone",
        )

        # Rosters
        r1 = roster.build_roster(
            self.pub["root.alice.v1"],
            [self.device_alice_mobile],
            sequence=1,
        )
        self.roster1 = roster.sign_roster(r1, self.priv["root.alice.v1"])

        r2 = roster.build_roster(
            self.pub["root.alice.v1"],
            [self.device_alice_mobile, self.device_alice_pc],
            sequence=2,
            previous=roster.chain_previous(self.roster1),
        )
        self.roster2 = roster.sign_roster(r2, self.priv["root.alice.v1"])

        # Prekeys de Alice (movil)
        self.spk = contact.sign_signed_prekey(
            contact.build_signed_prekey(
                self.pub["alice.mobile.sign"],
                self.opaque["alice.mobile.spk"],
                key_id=42,
                device_id_bytes=self.alice_mobile_id,
            ),
            self.priv["alice.mobile.sign"],
        )
        self.opks = [
            contact.build_onetime_prekey(
                self.opaque[f"alice.mobile.opk.{1000 + i}"],
                key_id=1000 + i,
                device_id_bytes=self.alice_mobile_id,
            )
            for i in (0, 1)
        ]

        # Prekeys de Alice (PC)
        self.spk_pc = contact.sign_signed_prekey(
            contact.build_signed_prekey(
                self.pub["alice.pc.sign"],
                self.opaque["alice.pc.spk"],
                key_id=7,
                device_id_bytes=self.alice_pc_id,
            ),
            self.priv["alice.pc.sign"],
        )

        # Bundles
        self.bundle_contact = contact.sign_bundle(
            contact.build_bundle(
                self.pub["root.alice.v1"],
                self.roster2,
                self.alice_mobile_id,
                self.spk,
                one_time_prekeys=self.opks,
                endpoints=[
                    contact.build_endpoint_hint(
                        C.ENDPOINT_ONION, "km6x2p7q4nl3r5t8wz1yv0dc2sh4g7kj9fa.onion"
                    ),
                    contact.build_endpoint_hint(C.ENDPOINT_UDP, "192.0.2.41:41234"),
                ],
                capabilities=0b0000_0000_0000_0111,
            ),
            self.priv["root.alice.v1"],
        )

        # El bundle DEVICE_LINK embebe el roster ANTERIOR a dar de alta al PC:
        # es el movil quien emite despues el roster nuevo.
        self.bundle_device_link = contact.sign_bundle(
            contact.build_bundle(
                self.pub["root.alice.v1"],
                self.roster1,
                self.alice_mobile_id,
                self.spk,
                one_time_prekeys=self.opks,
                kind=C.KIND_DEVICE_LINK,
                link_secret=self.opaque["linksecret.1"],
                capabilities=0b0000_0000_0000_0001,
            ),
            self.priv["root.alice.v1"],
        )

        # Rotacion bilateral
        self.rotation = rotation.sign_rotation(
            rotation.build_rotation(self.pub["root.alice.v1"], self.pub["root.alice.v2"], 2),
            old_root_private_key=self.priv["root.alice.v1"],
            new_root_private_key=self.priv["root.alice.v2"],
        )

        # Device-link
        self.challenge = device_link.sign_challenge(
            device_link.build_challenge(
                self.pub["root.alice.v1"],
                self.alice_mobile_id,
                self.opaque["nonce.link.1"],
                self.opaque["linksecret.1"],
            ),
            self.priv["alice.mobile.sign"],
        )
        self.response = device_link.sign_response(
            device_link.build_response(
                self.pub["root.alice.v1"],
                self.pub["alice.pc.sign"],
                self.opaque["alice.pc.agr"],
                self.opaque["nonce.link.1"],
                name="Alice-PC",
            ),
            self.priv["alice.pc.sign"],
        )


# ---------------------------------------------------------------------------
# Emision de paquetes
# ---------------------------------------------------------------------------


def emit(
    gid: str,
    title: str,
    description: str,
    *,
    artifacts: list[tuple[str, bytes]] | None = None,
    expected: str = "VALID",
    derived: str | None = None,
    inputs: dict[str, str] | None = None,
    meta_extra: dict | None = None,
) -> dict:
    d = os.path.join(VECTORS_DIR, gid)
    os.makedirs(d, exist_ok=True)

    meta = {"id": gid, "title": title, "description": description, "expected": expected}

    for name, blob in artifacts or []:
        with open(os.path.join(d, name), "wb") as f:
            f.write(blob)
        if name.endswith(".cbor"):
            meta.setdefault("artifacts", {})[name] = {
                "length": len(blob),
                "sha256": hashlib.sha256(blob).hexdigest(),
            }

    with open(os.path.join(d, "expected.txt"), "w", encoding="utf-8") as f:
        f.write(expected.rstrip() + "\n")

    if derived is not None:
        with open(os.path.join(d, "derived.txt"), "w", encoding="utf-8") as f:
            f.write(derived + "\n")
        meta["derived"] = derived

    if inputs:
        with open(os.path.join(d, "input.txt"), "w", encoding="utf-8") as f:
            for k in sorted(inputs):
                f.write(f"{k} = {inputs[k]}\n")
        meta["inputs"] = inputs

    if meta_extra:
        meta.update(meta_extra)

    with open(os.path.join(d, "vector.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2, sort_keys=True, ensure_ascii=False)
        f.write("\n")
    return meta


def emit_invalid(name: str, blob: bytes, expected: str, note: str) -> dict:
    with open(os.path.join(INVALID_DIR, name), "wb") as f:
        f.write(blob)
    entry = {
        "file": f"invalid/{name}",
        "length": len(blob),
        "sha256": hashlib.sha256(blob).hexdigest(),
        "expected": expected,
        "note": note,
    }
    return entry


# ---------------------------------------------------------------------------
# Codificador "crudo" para construir documentos deliberadamente invalidos
# ---------------------------------------------------------------------------

MT_MAP = 5
MT_TSTR = 3
MT_UINT = 0
MT_ARRAY = 4
MT_SIMPLE = 7


def raw_head(major: int, arg: int) -> bytes:
    """Cabecera CBOR cruda, para construir fixtures deliberadamente invalidos.

    OJO: para `major == 7` los AI 24..27 son las anchuras de FLOAT, no longitudes
    de argumento. Un float de doble precision se emite como el byte suelto 0xFB
    seguido de 8 bytes de payload; NO como `0xF8 0x1A`, que seria un float de
    media precision con el payload recortado.
    """
    if major == 7:
        if arg in (24, 25, 26, 27):
            return bytes([(major << 5) | arg])
        if arg < 24:
            return bytes([(major << 5) | arg])
        raise ValueError(f"AI {arg} invalido para major 7")
    if arg < 24:
        return bytes([(major << 5) | arg])
    if arg < 0x100:
        return bytes([(major << 5) | 24, arg])
    if arg < 0x10000:
        return bytes([(major << 5) | 25]) + arg.to_bytes(2, "big")
    if arg < 0x100000000:
        return bytes([(major << 5) | 26]) + arg.to_bytes(4, "big")
    return bytes([(major << 5) | 27]) + arg.to_bytes(8, "big")


def raw_double(value: float) -> bytes:
    """Float de doble precision REAL: 0xFB + 8 bytes big-endian (IEEE 754)."""
    return b"\xfb" + _f64(value)


def raw_tstr(text: str) -> bytes:
    """Text string SIN comprobacion de NFC. Solo para fixtures invalidos."""
    b = text.encode("utf-8")
    return raw_head(MT_TSTR, len(b)) + b


def raw_bstr(blob: bytes) -> bytes:
    return raw_head(2, len(blob)) + blob


def raw_uint(n: int) -> bytes:
    return raw_head(MT_UINT, n)


def raw_map_from_parts(pairs: list[tuple[bytes, bytes]]) -> bytes:
    """Mapa construido de piezas ya codificadas, sin orden imposed."""
    out = raw_head(MT_MAP, len(pairs))
    for kb, vb in pairs:
        out += kb + vb
    return out


def sorted_pairs(mapping: dict) -> list[tuple[bytes, bytes]]:
    """Pares (clave, valor) en el orden canonico que impone KCE."""
    items = []
    for k, v in mapping.items():
        kb = kce.encode(k)
        items.append((kb, kce.encode(v)))
    items.sort(key=lambda kv: kv[0])
    return items


# ---------------------------------------------------------------------------
# Vectores
# ---------------------------------------------------------------------------


def build_vectors(sc: Scenario) -> list[dict]:
    out: list[dict] = []

    # --- G01 identityId -------------------------------------------------
    out.append(
        emit(
            "G01",
            "identityId derivation",
            "SHA-256(\"KM-ID-IDENTITY\" || identityRootPublicKey). Entrada: clave "
            "publica Ed25519 de la raiz de identidad. Salida: 32 bytes.",
            derived=sc.identity_id_v1.hex(),
            inputs={"identityRootPublicKey": sc.pub["root.alice.v1"].hex()},
            meta_extra={"rule": 'SHA-256(UTF8("KM-ID-IDENTITY") || key)', "seedLabel": "root.alice.v1"},
        )
    )

    # --- G02 deviceId ----------------------------------------------------
    out.append(
        emit(
            "G02",
            "deviceId derivation",
            "SHA-256(\"KM-ID-DEVICE\" || deviceSigningPublicKey). La clave de ACUERDO "
            "X25519 no participa: deviceId se ancla a la clave de FIRMA, que es la "
            "que autoriza al dispositivo.",
            derived=sc.alice_mobile_id.hex(),
            inputs={"deviceSigningPublicKey": sc.pub["alice.mobile.sign"].hex()},
            meta_extra={"rule": 'SHA-256(UTF8("KM-ID-DEVICE") || key)', "seedLabel": "alice.mobile.sign"},
        )
    )

    # --- G03 nodeId ------------------------------------------------------
    out.append(
        emit(
            "G03",
            "nodeId derivation",
            "SHA-256(\"KM-NODE-NODE\" || nodeIdentityPublicKey). El nodo es un objeto "
            "distinto de la identidad y del dispositivo; el prefijo de dominio "
            "impide que los tres identificadores puedan colisionar.",
            derived=identity.node_id(sc.node_identity[0]).hex(),
            inputs={"nodeIdentityPublicKey": sc.node_identity[0].hex()},
            meta_extra={"rule": 'SHA-256(UTF8("KM-NODE-NODE") || key)', "seedLabel": "node.identity"},
        )
    )

    # --- G04 roster genesis ----------------------------------------------
    out.append(
        emit(
            "G04",
            "DeviceRoster genesis (sequence = 1)",
            "Roster inicial de una identidad, con un unico dispositivo. previous esta "
            "anclado a la identidad: SHA-256(\"KM-ID-ROSTER-GENESIS\" || identityRoot). "
            "Firmado con la raiz Ed25519.",
            artifacts=[
                ("canonical.cbor", roster.canonical_bytes(sc.roster1)),
                ("signable.cbor", roster.signable_bytes(sc.roster1)),
            ],
            derived=roster.canonical_bytes(sc.roster1).hex(),
            inputs={
                "identityRoot": sc.pub["root.alice.v1"].hex(),
                "deviceSigningKey": sc.pub["alice.mobile.sign"].hex(),
                "seed(root)": sc.seeds["root.alice.v1"],
                "seed(device)": sc.seeds["alice.mobile.sign"],
            },
            meta_extra={
                "signature": sc.roster1["signature"].hex(),
                "signingKey": "seed root.alice.v1",
                "verify": "Ed25519.Verify(identityRoot, signable.cbor, signature)",
            },
        )
    )

    # --- G05 roster #2 ---------------------------------------------------
    out.append(
        emit(
            "G05",
            "DeviceRoster sequence = 2 (cadena)",
            "Anade el dispositivo PC. previous = SHA-256(KCE(roster#1 COMPLETO, "
            "incluida su firma)). Verificarlo exige el roster #1, que se incluye como "
            "dependencia declarada.",
            artifacts=[
                ("canonical.cbor", roster.canonical_bytes(sc.roster2)),
                ("signable.cbor", roster.signable_bytes(sc.roster2)),
            ],
            derived=roster.canonical_bytes(sc.roster2).hex(),
            meta_extra={
                "signature": sc.roster2["signature"].hex(),
                "dependsOn": "G04",
                "chain": "previous = SHA-256(G04/canonical.cbor)",
                "invariant": "deviceId == SHA-256(\"KM-ID-DEVICE\" || signingKey) en cada entrada",
            },
        )
    )

    # --- G06 roster signature, valida e invalida --------------------------
    tampered = flip_in_value(roster.canonical_bytes(sc.roster1), sc.roster1["signature"])
    out.append(
        emit(
            "G06",
            "Firma de roster: valida y manipulada",
            "El mismo documento con la firma intacta debe verificar, y el mismo "
            "documento con el ultimo byte de la firma alterado debe rechazarse. "
            "Aisla el error criptografico de todos los demas.",
            artifacts=[
                ("canonical.cbor", roster.canonical_bytes(sc.roster1)),
                ("signable.cbor", roster.signable_bytes(sc.roster1)),
                ("tampered.cbor", bytes(tampered)),
            ],
            expected="canonical.cbor: VALID\ntampered.cbor: INVALID_SIGNATURE",
            meta_extra={
                "signature": sc.roster1["signature"].hex(),
                "mutation": "se invierte el bit 0 del ultimo byte (dentro de la firma)",
                "note": "KCE y esquema siguen siendo validos: el fallo es puramente criptografico",
            },
        )
    )

    # --- G07 rotacion bilateral -------------------------------------------
    forged = dict(sc.rotation)
    forged.pop("newRootSign")
    out.append(
        emit(
            "G07",
            "IdentityRotation bilateral",
            "Certificado de transicion entre dos raices. Ambas firmas cubren el mismo "
            "Signable. El fixture `forged.cbor` demuestra el caso que la firma bilateral "
            "existe para detectar: un atacante con la raiz antigua robada puede firmar, "
            "pero NO puede producir la contrafirma de una raiz nueva legitima.",
            artifacts=[
                ("canonical.cbor", rotation.canonical_bytes(sc.rotation)),
                ("signable.cbor", rotation.signable_bytes(sc.rotation)),
                ("forged.cbor", kce.encode(forged)),
            ],
            expected="canonical.cbor: VALID\nforged.cbor: INVALID_NEW_ROOT_SIGNATURE",
            inputs={
                "oldRoot": sc.pub["root.alice.v1"].hex(),
                "newRoot": sc.pub["root.alice.v2"].hex(),
            },
            meta_extra={
                "oldRootSign": sc.rotation["oldRootSign"].hex(),
                "newRootSign": sc.rotation["newRootSign"].hex(),
                "consequence": "sequence se reinicia en 1 para la identidad nueva; "
                "el reinicio solo es aceptable porque la rotacion es bilateral",
            },
        )
    )

    # --- G08 signed prekey ------------------------------------------------
    out.append(
        emit(
            "G08",
            "SignedPrekey",
            "Firmado por la clave de FIRMA DEL DISPOSITIVO, no por la raiz. La cadena "
            "de autoridad es IdentityRoot -> DeviceRoster -> DeviceSigningKey -> "
            "SignedPrekey. deviceId DEBE coincidir con el subjectDeviceId del bundle.",
            artifacts=[
                ("canonical.cbor", kce.encode(sc.spk)),
                ("signable.cbor", contact.signable_prekey_bytes(sc.spk)),
            ],
            derived=kce.encode(sc.spk).hex(),
            meta_extra={
                "signature": sc.spk["signature"].hex(),
                "signingKey": "seed alice.mobile.sign",
                "verify": "Ed25519.Verify(roster[subject].signingKey, signable.cbor, signature)",
            },
        )
    )

    # --- G09 one-time prekey ----------------------------------------------
    out.append(
        emit(
            "G09",
            "OneTimePrekey (sin firma propia)",
            "La OPK NO lleva firma propia, deliberadamente: hereda la autenticidad de la "
            "firma de la raiz que envuelve el ContactBundle. Así el verificador no "
            "verifica N firmas adicionales y la garantia es \"o el bundle entero es "
            "valido, o no sirve\".",
            artifacts=[("canonical.cbor", kce.encode(sc.opks[0])), ("second.cbor", kce.encode(sc.opks[1]))],
            derived=kce.encode(sc.opks[0]).hex(),
            meta_extra={
                "signature": None,
                "consumption": "AVAILABLE -> RESERVED -> CONSUMED, irreversible; "
                "el consumo atomico por opkId se especifica en KM-DISC-0001",
            },
        )
    )

    # --- G10 contact bundle ------------------------------------------------
    out.append(
        emit(
            "G10",
            "ContactBundle: CONTACT y DEVICE_LINK",
            "El bundle es el MISMO objeto en `.kmc` y en QR: no existen dos protocolos. "
            "`kind` distingue CONTACT de DEVICE_LINK sin cambiar el modelo criptografico. "
            "El bundle DEVICE_LINK embibe el roster ANTERIOR a dar de alta al nuevo "
            "dispositivo, e incluye linkSecret.",
            artifacts=[
                ("contact.cbor", contact.canonical_bytes(sc.bundle_contact)),
                ("device_link.cbor", contact.canonical_bytes(sc.bundle_device_link)),
            ],
            expected="contact.cbor: VALID\ndevice_link.cbor: VALID",
            meta_extra={
                "contactSignature": sc.bundle_contact["signature"].hex(),
                "deviceLinkSignature": sc.bundle_device_link["signature"].hex(),
                "invariants": [
                    "subjectDeviceId == roster entry deviceId",
                    "subjectSigningKey == roster entry signingKey",
                    "subjectAgreementKey == roster entry agreementKey",
                    "deviceId(subjectSigningKey) == subjectDeviceId",
                    "signedPrekey.deviceId == subjectDeviceId",
                    "oneTimePrekeys[i].deviceId == subjectDeviceId",
                ],
                "endpointsAreSigned": "un atacante que modifique un endpoint invalida la firma",
                "chainNotVerifiable": "el bundle embebe un unico snapshot: si su sequence > 1 "
                "el enlace al anterior NO es verificable aqui; la autoridad es la firma",
            },
        )
    )

    # --- G11 device link ---------------------------------------------------
    out.append(
        emit(
            "G11",
            "Device-link: challenge y response",
            "NIVEL 1 canal fisico: el QR transporta el challenge. NIVEL 2 prueba de "
            "posesion: el PC firma el nonce. NIVEL 3 autorizacion: el movil firma un "
            "roster nuevo que incluye al PC, y el PC lo verifica. El challenge NO "
            "autoriza a nadie por si solo.",
            artifacts=[
                ("challenge.cbor", kce.encode(sc.challenge)),
                ("response.cbor", kce.encode(sc.response)),
                ("authorizing_roster.cbor", roster.canonical_bytes(sc.roster2)),
            ],
            expected="challenge.cbor: VALID\nresponse.cbor: VALID\nauthorizing_roster.cbor: VALID",
            inputs={
                "nonce": sc.opaque["nonce.link.1"].hex(),
                "linkSecret": sc.opaque["linksecret.1"].hex(),
            },
            meta_extra={
                "linkSecretRole": "liga este intercambio a ESTA instancia de QR; NO es un "
                "sustituto de autorizacion criptografica",
                "authorization": "roster #2 (G05) contiene al PC y esta firmado por la raiz",
            },
        )
    )

    # --- G12 identity safety number -----------------------------------------
    out.append(
        emit(
            "G12",
            "identitySafetyNumber",
            "60 digitos decimales en 12 bloques de 5. 10^60 ~ 2^199.3157, de modo que "
            "25 bytes (200 bits) cubren el espacio. Es un CODIGO DE COMPARACION, no un "
            "secreto ni una credencial, y no autentica a una persona.",
            derived=fingerprints.identity_safety_number(sc.identity_id_v1),
            inputs={"identityId": sc.identity_id_v1.hex()},
            meta_extra={
                "rule": 'render60(SHA-256(UTF8("KM-ID-SAFETY-NUMBER") || identityId)[0:25])',
                "stable": "mientras IdentityRoot no rote; anadir un dispositivo NO lo invalida",
            },
        )
    )

    # --- G13 roster verification code ---------------------------------------
    out.append(
        emit(
            "G13",
            "rosterVerificationCode",
            "Representa el ESTADO ACTUAL del roster, separado de la identidad. Cambia "
            "al anadir, retirar o revocar un dispositivo, mientras G12 permanece igual. "
            "Permite expresar \"la identidad es la misma, pero el conjunto de dispositivos "
            "cambio\".",
            derived=fingerprints.roster_verification_code(schemas.DEVICE_ROSTER.signable(sc.roster1)),
            inputs={"roster": "G04/canonical.cbor"},
            meta_extra={
                "rule": 'render60(SHA-256(UTF8("KM-ID-ROSTER-CODE") || KCE(signableRoster))[0:25])',
                "sameAs": "G12 permanece identico entre G04 y G05; este codigo NO",
            },
        )
    )

    return out


# ---------------------------------------------------------------------------
# Fixtures inválidos
# ---------------------------------------------------------------------------


def build_invalid(sc: Scenario) -> list[dict]:
    out: list[dict] = []

    # 1. Clave de map duplicada
    base = roster.canonical_bytes(sc.roster1)
    reader = kce._Reader(base)
    major, count, _ai = reader.read_head()
    assert major == 5 and count == 9, (major, count)
    dup = raw_head(5, count + 1) + base[1:] + kce.encode("doc") + kce.encode(C.DOC_ROSTER)
    out.append(
        emit_invalid(
            "duplicate-key.cbor",
            dup,
            "DUPLICATE_MAP_KEY",
            "La clave `doc` aparece dos veces. Un mapa CBOR con claves duplicadas tiene "
            "interpretacion ambigua, asi que KCE lo trata como error, no como algo a resolver.",
        )
    )

    # 2. Campo desconocido (KCE valido, esquema rechazado)
    unknown = dict(schemas.DEVICE_ROSTER.signable(sc.roster1))
    unknown["xNoSuchField"] = 1
    unknown["signature"] = sc.roster1["signature"]
    out.append(
        emit_invalid(
            "unknown-field.cbor",
            kce.encode(unknown),
            "UNKNOWN_FIELD",
            "Documento KCE-perfecto con un campo que el esquema no define. Como la firma "
            "cubre la estructura ENTERA, no es posible 'ignorar campos desconocidos': se "
            "rechaza. Anadir un campo obliga a subir `version`.",
        )
    )

    # 3. Firma manipulada
    bad = flip_in_value(roster.canonical_bytes(sc.roster1), sc.roster1["signature"])
    out.append(
        emit_invalid(
            "bad-signature.cbor",
            bad,
            "INVALID_SIGNATURE",
            "KCE y esquema validos; solo un byte de la firma esta alterado. Se localiza "
            "el bloque de la firma explicitamente, porque el ultimo byte del documento no "
            "pertenece necesariamente a ella: KCE ordena por bytes codificados y el "
            "prefijo de longitud domina.",
        )
    )

    # 4. Binding de subject incorrecto
    broken = dict(sc.bundle_contact)
    broken["subjectSigningKey"] = sc.pub["alice.pc.sign"]
    out.append(
        emit_invalid(
            "wrong-device-binding.cbor",
            contact.canonical_bytes(broken),
            "SUBJECT_BINDING_MISMATCH",
            "El bundle dice que pertenece al movil (subjectDeviceId) pero lleva la clave "
            "de firma del PC. Se comprueba el binding ANTES que la firma del bundle, que "
            "tambien fallaria; el error primario es el binding.",
        )
    )

    # 5. previous incorrecto, con firma recalculada
    bad_prev = dict(schemas.DEVICE_ROSTER.signable(sc.roster2))
    bad_prev["previous"] = roster.genesis_previous(sc.pub["bob.mobile.sign"])
    bad_prev["signature"] = ed25519.sign(
        roster.signable_bytes(bad_prev), sc.priv["root.alice.v1"]
    )
    out.append(
        emit_invalid(
            "wrong-previous.cbor",
            roster.canonical_bytes(bad_prev),
            "PREVIOUS_MISMATCH",
            "Roster #2 con el enlace calculado sobre OTRA identidad, y RE-FIRMADO con la "
            "raiz correcta. Asi el unico defecto es la cadena: la firma es valida.",
        )
    )

    # 6. Version no soportada, correctamente firmado
    future = dict(schemas.DEVICE_ROSTER.signable(sc.roster1))
    future["version"] = 2
    future["signature"] = ed25519.sign(roster.signable_bytes(future), sc.priv["root.alice.v1"])
    out.append(
        emit_invalid(
            "wrong-version.cbor",
            roster.canonical_bytes(future),
            "UNSUPPORTED_VERSION",
            "Documento bien formado y bien firmado que declara version 2. Debe reportarse "
            "como version no soportada, NO como encoding invalido: por eso el despacho de "
            "version precede a la validacion KCE.",
        )
    )

    # 7. Orden de claves no canonico
    pairs = sorted_pairs(dict(sc.roster1))
    pairs.reverse()
    out.append(
        emit_invalid(
            "non-canonical-order.cbor",
            raw_map_from_parts(pairs),
            "KCE_ROUNDTRIP_MISMATCH",
            "Mapa con las mismas claves y valores en orden inverso al canonico. Es CBOR "
            "valido y decodifica bien, pero NO es KCE: por eso se valida el round-trip "
            "byte a byte en lugar de confiar en la decodificacion.",
        )
    )

    # 8. Longitud indefinida
    indef = b"\xbf" + base[1:] + b"\xff"
    out.append(
        emit_invalid(
            "indefinite-length.cbor",
            indef,
            "INDEFINITE_LENGTH",
            "Mapa de longitud indefinida. KCE exige longitudes definitivas: un receptor no "
            "debe poder aceptar una representacion cuyos limites no conoce.",
        )
    )

    # 9. Flotante
    float_pairs = sorted_pairs(
        {k: v for k, v in sc.roster1.items() if k != "createdAt"}
    )
    float_pairs.append((kce.encode("createdAt"), raw_double(1760000000.0)))
    float_pairs.sort(key=lambda kv: kv[0])
    out.append(
        emit_invalid(
            "float.cbor",
            raw_map_from_parts(float_pairs),
            "FLOAT_FORBIDDEN",
            "`createdAt` codificado como float de doble precision en lugar de uint. Los "
            "flotantes estan prohibidos en KCE porque su representacion no es canonica de "
            "forma fiable entre lenguajes.",
        )
    )

    # 10. Texto no NFC
    # El nombre se inyecta en forma de DESCOMPOSICION de un caracter que SI
    # tiene forma precompuesta ("A" + acentoCombinante -> "Á"). Anadir un
    # acento combinante a un caracter sin forma precompuesta NO serviria:
    # NFC de esa secuencia es la propia secuencia, y no se detectaria nada.
    nfc_pairs = []
    for kb, vb in sorted_pairs(sc.roster1):
        if b"Alice-Android" in vb:
            vb = raw_tstr("A\u0301lice-Android")
        nfc_pairs.append((kb, vb))
    out.append(
        emit_invalid(
            "non-nfc-text.cbor",
            raw_map_from_parts(nfc_pairs),
            "TEXT_NOT_NFC",
            "El nombre del dispositivo usa forma de descomposicion Unicode. Sin NFC "
            "obligatorio, dos implementaciones firman bytes distintos para el mismo texto.",
        )
    )

    # 11. Etiqueta CBOR
    tagged = b"\xd8\x20" + roster.canonical_bytes(sc.roster1)
    out.append(
        emit_invalid(
            "cbor-tag.cbor",
            tagged,
            "TAGS_FORBIDDEN",
            "Documento con etiqueta CBOR 32. KCE las prohibe salvo las definidas "
            "explicitamente por KM, para evitar dos representaciones semanticamente "
            "equivalentes.",
        )
    )

    # 12. Null
    null_pairs = sorted_pairs({k: v for k, v in sc.roster1.items() if k != "name"})
    null_pairs.append((kce.encode("name"), bytes([0xF6])))
    null_pairs.sort(key=lambda kv: kv[0])
    out.append(
        emit_invalid(
            "null-field.cbor",
            raw_map_from_parts(null_pairs),
            "NULL_FORBIDDEN",
            "`name` presente pero con valor null. Los campos opcionales se OMITEN, nunca "
            "se nulan: asi ausente y ausente significan lo mismo en cualquier "
            "implementacion.",
        )
    )

    # 13. revokedAt incoherente
    # make_device_entry IMPIDE construir esta incoherencia (revokedAt es
    # obligatorio con status REVOKED), asi que se inyecta deliberadamente
    # para producir un fixture negativo valido.
    revoked_entry = {
        "deviceId": sc.alice_mobile_id,
        "signingKey": sc.pub["alice.mobile.sign"],
        "agreementKey": sc.opaque["alice.mobile.agr"],
        "status": C.STATUS_REVOKED,
        "addedAt": C.SAMPLE_CREATED_AT,
        "name": "Alice-Android",
    }
    incoherent = roster.build_roster(
        sc.pub["root.alice.v1"], [revoked_entry], sequence=1
    )
    incoherent = roster.sign_roster(incoherent, sc.priv["root.alice.v1"])
    out.append(
        emit_invalid(
            "revoked-without-timestamp.cbor",
            roster.canonical_bytes(incoherent),
            "REVOCATION_FIELD_MISMATCH",
            "Dispositivo marcado REVOKED sin revokedAt, y correctamente firmado por la raiz. "
            "La dependencia es normativa: revokedAt esta presente si y solo si "
            "status == REVOKED.",
        )
    )

    return out


def _f64(value: float) -> bytes:
    import struct

    return struct.pack(">d", value)


def flip_in_value(blob: bytes, value: bytes) -> bytes:
    """Invierte el ultimo byte de una OCURRENCIA de `value` dentro de `blob`.

    Necesario porque NO se puede suponer que el ultimo byte del documento
    pertenezca a la firma: KCE ordena las claves por sus bytes codificados,
    y en una clave corta el prefijo de longitud manda sobre el contenido, de
    modo que el ultimo campo del mapa suele ser el de la clave MAS LARGA
    (p.ej. `identityRoot`), no el de la firma.

    Localizar los bytes de la firma es explicito y no fragil.
    """
    idx = blob.find(value)
    if idx < 0:
        raise ValueError("no se localizo el valor a manipular dentro del documento")
    if blob.find(value, idx + 1) >= 0:
        raise ValueError("el valor a manipular aparece mas de una vez: fixture ambiguo")
    pos = idx + len(value) - 1
    out = bytearray(blob)
    out[pos] ^= 0xFF
    return bytes(out)


# ---------------------------------------------------------------------------
# MANIFEST
# ---------------------------------------------------------------------------


def normative_constants() -> dict:
    """Constantes normativas de KM-ID-0001, expuestas de forma verificable.

    Se exportan al MANIFEST para que un puerto en otro lenguaje pueda
    comprobar sus propias constantes contra el congelado, en vez de
    transcribirlas del RFC y arrastrar un error silencioso.
    """
    return {
        # separacion de dominio
        "DS_IDENTITY": C.DS_IDENTITY,
        "DS_DEVICE": C.DS_DEVICE,
        "DS_NODE": C.DS_NODE,
        "DS_ROSTER_GENESIS": C.DS_ROSTER_GENESIS,
        "DS_ROSTER": C.DS_ROSTER,
        "DS_ROSTER_CODE": C.DS_ROSTER_CODE,
        "DS_SAFETY_NUMBER": C.DS_SAFETY_NUMBER,
        "DS_ROTATION": C.DS_ROTATION,
        "DS_CONTACT_BUNDLE": C.DS_CONTACT_BUNDLE,
        "DS_DEVICE_LINK": C.DS_DEVICE_LINK,
        "DS_PREKEY_SIGNED": C.DS_PREKEY_SIGNED,
        "DS_PREKEY_ONETIME": C.DS_PREKEY_ONETIME,
        # tipos de documento
        "DOC_ROSTER": C.DOC_ROSTER,
        "DOC_ROTATION": C.DOC_ROTATION,
        "DOC_CONTACT_BUNDLE": C.DOC_CONTACT_BUNDLE,
        "DOC_SIGNED_PREKEY": C.DOC_SIGNED_PREKEY,
        "DOC_ONETIME_PREKEY": C.DOC_ONETIME_PREKEY,
        "DOC_LINK_CHALLENGE": C.DOC_LINK_CHALLENGE,
        "DOC_LINK_RESPONSE": C.DOC_LINK_RESPONSE,
        "CURRENT_VERSION": C.CURRENT_VERSION,
        # tamanos
        "PUBKEY_BYTES": C.PUBKEY_BYTES,
        "SIG_BYTES": C.SIG_BYTES,
        "HASH_BYTES": C.HASH_BYTES,
        # limites
        "MAX_DEVICES": C.MAX_DEVICES,
        "MAX_ONE_TIME_PREKEYS": C.MAX_ONE_TIME_PREKEYS,
        "MAX_ENDPOINTS": C.MAX_ENDPOINTS,
        "MAX_NAME_BYTES": C.MAX_NAME_BYTES,
        "MAX_ENDPOINT_VALUE_BYTES": C.MAX_ENDPOINT_VALUE_BYTES,
        "MAX_CAPABILITIES_UINT": C.MAX_CAPABILITIES_UINT,
        # numeros de verificacion
        "SAFETY_NUMBER_DIGITS": C.SAFETY_NUMBER_DIGITS,
        "SAFETY_NUMBER_GROUPS": C.SAFETY_NUMBER_GROUPS,
        "SAFETY_NUMBER_GROUP_SIZE": C.SAFETY_NUMBER_GROUP_SIZE,
        "SAFETY_NUMBER_SOURCE_BYTES": C.SAFETY_NUMBER_SOURCE_BYTES,
        # estados y tipos: CONVENCION 1-BASED
        "STATUS_ACTIVE": C.STATUS_ACTIVE,
        "STATUS_REVOKED": C.STATUS_REVOKED,
        "KIND_CONTACT": C.KIND_CONTACT,
        "KIND_DEVICE_LINK": C.KIND_DEVICE_LINK,
        "ENDPOINT_ONION": C.ENDPOINT_ONION,
        "ENDPOINT_UDP": C.ENDPOINT_UDP,
        "ENDPOINT_RELAY": C.ENDPOINT_RELAY,
        # limites de encoding de KCE, que un puerto no debe transcribir a mano
        "KCE_MAX_DOCUMENT_BYTES": kce.MAX_DOCUMENT_BYTES,
        "KCE_MAX_TEXT_BYTES": kce.MAX_TEXT_BYTES,
        "KCE_MAX_MAP_ITEMS": kce.MAX_MAP_ITEMS,
        "KCE_MAX_ARRAY_ITEMS": kce.MAX_ARRAY_ITEMS,
        "KCE_MAX_DEPTH": kce.MAX_DEPTH,
        "KCE_FRAMER_MAX_DEPTH": kce.FRAMER_MAX_DEPTH,
        # muestras deterministas
        "SAMPLE_CREATED_AT": C.SAMPLE_CREATED_AT,
        "SAMPLE_LINK_EXPIRES_AT": C.SAMPLE_LINK_EXPIRES_AT,
        "SAMPLE_SPK_VALIDITY_MS": C.SAMPLE_SPK_VALIDITY_MS,
    }


def write_manifest(vectors: list[dict], invalid: list[dict], sc: Scenario) -> None:
    # Tipo de documento de cada fixture invalido, para que selfcheck.py
    # pueda enrutar cada uno al verificador correcto.
    routing = {
        "duplicate-key.cbor": "DeviceRoster",
        "unknown-field.cbor": "DeviceRoster",
        "bad-signature.cbor": "DeviceRoster",
        "wrong-previous.cbor": "DeviceRoster",
        "wrong-version.cbor": "DeviceRoster",
        "non-canonical-order.cbor": "DeviceRoster",
        "indefinite-length.cbor": "DeviceRoster",
        "float.cbor": "DeviceRoster",
        "non-nfc-text.cbor": "DeviceRoster",
        "cbor-tag.cbor": "DeviceRoster",
        "null-field.cbor": "DeviceRoster",
        "revoked-without-timestamp.cbor": "DeviceRoster",
        "wrong-device-binding.cbor": "ContactBundle",
    }
    for entry in invalid:
        name = os.path.basename(entry["file"])
        if name not in routing:
            raise KeyError(f"fixture invalido sin enrutar: {name}")
        entry["docType"] = routing[name]

    # Constantes normativas expuestas para que OTRA implementacion pueda
    # verificarlas contra el congelado, no solo leerlas del RFC.
    #
    # Sin esto, un valor mal transcrito en un puerto (un enum 0-based donde la
    # referencia usa 1-based, por ejemplo) es invisible: los vectores de
    # derivacion de ID no lo ejercitan y el fallo aparece meses despues, en
    # interoperabilidad, como un rechazo inexplicable.
    manifest = {
        "spec": "KM-ID-0001",
        "generatedBy": "km-id-reference/generate.py",
        "seedDerivation": 'SHA-256(UTF8("KM-ID-0001/" || label)',
        "note": "Los vectores se CALCULAN ejecutando la implementacion de referencia. "
        "Si un valor fuese incorrecto, el error estaria en el codigo y no "
        "disfrazado de constante normativa en el RFC.",
        "seeds": dict(sorted(sc.seeds.items())),
        "constants": normative_constants(),
        "vectors": sorted(vectors, key=lambda v: v["id"]),
        "invalid": invalid,
    }
    with open(os.path.join(HERE, "MANIFEST.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, sort_keys=True, ensure_ascii=False)
        f.write("\n")

    with open(os.path.join(VECTORS_DIR, "keys.json"), "w", encoding="utf-8") as f:
        json.dump(
            {
                "note": "Semillas deterministas. Necesarias para que otra implementacion "
                "reproduzca EXACTAMENTE las mismas firmas.",
                "seedDerivation": 'SHA-256(UTF8("KM-ID-0001/" || label)',
                "seeds": dict(sorted(sc.seeds.items())),
            },
            f,
            indent=2,
            sort_keys=True,
            ensure_ascii=False,
        )
        f.write("\n")


def main() -> int:
    for d in (VECTORS_DIR, INVALID_DIR):
        if os.path.isdir(d):
            shutil.rmtree(d)
        os.makedirs(d)

    sc = Scenario()
    vectors = build_vectors(sc)
    invalid = build_invalid(sc)
    write_manifest(vectors, invalid, sc)

    print(f"Generados {len(vectors)} vectores en vectors/")
    print(f"Generados {len(invalid)} fixtures invalidos en invalid/")
    for v in vectors:
        print(f"  {v['id']}  {v['title']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

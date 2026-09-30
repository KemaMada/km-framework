#!/usr/bin/env python3
"""
Generador de vectores de prueba de KM-0002 (autenticacion)
==========================================================

Produce los paquetes G14..G19 en vectors/ y los fixtures invalidos I1..I5
en invalid/ para la autenticacion wire.

Filosofia identica a generate.py:
  - Los vectores NO se escriben a mano: cada artefacto se calcula.
  - Las semillas son deterministicas.
  - La referencia Python es la autoridad generadora.
  - Kotlin verifica byte-for-byte.

Diferencia con KM-ID-0001: los mensajes wire son JSON, no CBOR.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
VECTORS_DIR = os.path.join(HERE, "vectors")
INVALID_DIR = os.path.join(HERE, "invalid")

# Semillas deterministas (mismo esquema que KM-ID-0001)
SEED_PREFIX = "KM-AUTH-0002/"
ED25519_LABELS = [
    "initiator.sign.v1",   # Alice (initiator)
    "responder.sign.v1",   # Bob (responder)
]

import sys
sys.path.insert(0, HERE)
import ed25519 as ed


# ---------------------------------------------------------------------------
# Base64URL (RFC 4648 seccion 5) - estricto, sin padding
# ---------------------------------------------------------------------------

def base64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")

def base64url_decode(text: str) -> bytes:
    if "=" in text:
        raise ValueError(f"padding '=' no permitido en wire: {text!r}")
    # Anadir padding para que base64.b64decode funcione (se descarta despues)
    m = len(text) % 4
    if m == 1:
        raise ValueError(f"longitud imposible: len % 4 == 1 en Base64URL ({len(text)})")
    if m:
        text += "=" * (4 - m)
    return base64.urlsafe_b64decode(text)


# ---------------------------------------------------------------------------
# Derivatacion de identificadores  (KM-ID-0001 seccion 6)
# ---------------------------------------------------------------------------

def identity_id(public_key: bytes) -> str:
    if len(public_key) != 32:
        raise ValueError(f"publicKey debe tener 32 bytes, se recibieron {len(public_key)}")
    h = hashlib.sha256("KM-ID-IDENTITY".encode("utf-8"))
    h.update(public_key)
    return h.digest().hex()


# ---------------------------------------------------------------------------
# Transcript builders (KM-0002 secciones 9.2, 10.4)
# ---------------------------------------------------------------------------

def auth_transcript(
    nonce: bytes,
    timestamp_millis: int,
    responder_identity_id: str,
    identity_id_str: str,
) -> bytes:
    assert len(nonce) == 16, f"nonce debe tener 16 bytes, tiene {len(nonce)}"
    assert timestamp_millis >= 0
    assert len(responder_identity_id) == 64, f"responderIdentityId debe tener 64 hex, tiene {len(responder_identity_id)}"
    assert len(identity_id_str) == 64, f"identityId debe tener 64 hex, tiene {len(identity_id_str)}"
    assert all(c in "0123456789abcdef" for c in responder_identity_id), "responderIdentityId solo hex minusculas"
    assert all(c in "0123456789abcdef" for c in identity_id_str), "identityId solo hex minusculas"
    
    t_bytes = timestamp_millis.to_bytes(8, "big")  # uint64 big-endian
    return (
        nonce +
        t_bytes +
        responder_identity_id.encode("ascii") +
        identity_id_str.encode("ascii")
    )


def server_auth_transcript(
    session_id: str,
    server_nonce: bytes,
    initiator_identity_id: str,
) -> bytes:
    assert len(session_id) == 40, f"sessionId debe tener 40 hex, tiene {len(session_id)}"
    assert len(server_nonce) == 16, f"serverNonce debe tener 16 bytes, tiene {len(server_nonce)}"
    assert len(initiator_identity_id) == 64, f"initiatorIdentityId debe tener 64 hex, tiene {len(initiator_identity_id)}"
    assert all(c in "0123456789abcdef" for c in session_id), "sessionId solo hex minusculas"
    assert all(c in "0123456789abcdef" for c in initiator_identity_id), "initiatorIdentityId solo hex minusculas"
    
    return (
        session_id.encode("ascii") +
        server_nonce +
        initiator_identity_id.encode("ascii")
    )


# ---------------------------------------------------------------------------
# Construccion de escenario deterministico
# ---------------------------------------------------------------------------

def seed_of(label: str) -> bytes:
    return hashlib.sha256((SEED_PREFIX + label).encode("utf-8")).digest()


class Scenario:
    def __init__(self) -> None:
        self.seeds: dict[str, str] = {}
        self.pub: dict[str, bytes] = {}
        self.priv: dict[str, bytes] = {}

        for label in ED25519_LABELS:
            s = seed_of(label)
            pk, sk = ed.keypair_from_seed(s)
            self.seeds[label] = s.hex()
            self.pub[label] = pk
            self.priv[label] = sk

        # Identificadores
        self.initiator_id = identity_id(self.pub["initiator.sign.v1"])
        self.responder_id = identity_id(self.pub["responder.sign.v1"])

        # Valores fijos deterministas
        self.nonce = hashlib.sha256(b"KM-AUTH-NONCE-1").digest()[:16]
        self.server_nonce = hashlib.sha256(b"KM-AUTH-SERVER-NONCE-1").digest()[:16]
        self.timestamp = 1_721_827_200_000  # 2024-07-29T00:00:00.000Z
        self.session_id = hashlib.sha256(b"KM-AUTH-SESSION-1").digest()[:20].hex()  # 40 hex


# ---------------------------------------------------------------------------
# Emision de paquetes
# ---------------------------------------------------------------------------

def emit_auth(
    gid: str,
    title: str,
    description: str,
    *,
    artifacts: dict[str, str | bytes | dict] | None = None,
    expected: str = "VALID",
    inputs: dict[str, str] | None = None,
    meta_extra: dict | None = None,
) -> dict:
    d = os.path.join(VECTORS_DIR, gid)
    os.makedirs(d, exist_ok=True)

    meta = {"id": gid, "title": title, "description": description, "expected": expected}

    for name, value in (artifacts or {}).items():
        path = os.path.join(d, name)
        if isinstance(value, dict):
            with open(path, "w", encoding="utf-8") as f:
                json.dump(value, f, indent=2, sort_keys=True, ensure_ascii=False)
                f.write("\n")
        elif isinstance(value, str):
            with open(path, "w", encoding="utf-8") as f:
                f.write(value)
            if name.endswith(".txt") or name.endswith(".json"):
                pass  # texto
            elif name.endswith(".bin"):
                with open(path, "wb") as f:
                    f.write(value.encode("utf-8"))
                raise ValueError("no usar .bin para texto")
        elif isinstance(value, bytes):
            with open(path, "wb") as f:
                f.write(value)
        else:
            raise TypeError(f"tipo no soportado: {type(value)} para {name}")

        if name.endswith(".bin"):
            meta.setdefault("artifacts", {})[name] = {
                "length": len(value),
                "sha256": hashlib.sha256(value if isinstance(value, bytes) else value.encode("utf-8")).hexdigest(),
            }
        elif name.endswith(".json"):
            blob = json.dumps(value).encode("utf-8") if isinstance(value, dict) else value.encode("utf-8")
            meta.setdefault("artifacts", {})[name] = {
                "length": len(blob),
                "sha256": hashlib.sha256(blob).hexdigest(),
            }

    with open(os.path.join(d, "expected.txt"), "w", encoding="utf-8") as f:
        f.write(expected.rstrip() + "\n")

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


def emit_invalid(name: str, blob: dict, expected: str, note: str) -> dict:
    with open(os.path.join(INVALID_DIR, name), "w", encoding="utf-8") as f:
        json.dump(blob, f, indent=2, sort_keys=True, ensure_ascii=False)
        f.write("\n")
    entry = {
        "file": f"invalid/{name}",
        "sha256": hashlib.sha256(
            json.dumps(blob, sort_keys=True, ensure_ascii=False).encode("utf-8")
        ).hexdigest(),
        "expected": expected,
        "note": note,
    }
    return entry


# ---------------------------------------------------------------------------
# Vectores G14..G19
# ---------------------------------------------------------------------------

def build_auth_vectors(sc: Scenario) -> list[dict]:
    out: list[dict] = []

    # --- G14: AUTH_CHALLENGE + 152-byte transcript --------------------------
    ch = {
        "nonce": base64url_encode(sc.nonce),
        "timestamp": sc.timestamp,
        "version": 3,
        "responderIdentityId": sc.responder_id,
    }
    transcript = auth_transcript(sc.nonce, sc.timestamp, sc.responder_id, sc.initiator_id)

    out.append(emit_auth(
        "G14",
        "AUTH_CHALLENGE + 152-byte transcript",
        f"Challenge emitido por el Responder ({sc.responder_id[:16]}...) para el Initiator "
        f"({sc.initiator_id[:16]}...). El transcript mide exactamente 152 bytes: "
        f"16 (nonce) + 8 (uint64 timestamp) + 64 (responderIdentityId) + 64 (identityId).",
        artifacts={
            "challenge.json": ch,
            "transcript.bin": transcript,
        },
        inputs={
            "nonce": sc.nonce.hex(),
            "timestamp": str(sc.timestamp),
            "responderIdentityId": sc.responder_id,
            "identityId": sc.initiator_id,
        },
        meta_extra={
            "transcriptLength": len(transcript),
            "transcriptSha256": hashlib.sha256(transcript).hexdigest(),
        },
    ))

    # --- G15: AUTH_RESPONSE + identidad + firma -----------------------------
    resp = {
        "identityId": sc.initiator_id,
        "publicKey": base64url_encode(sc.pub["initiator.sign.v1"]),
        "signature": base64url_encode(
            ed.sign(transcript, sc.priv["initiator.sign.v1"])
        ),
        "protocolVersion": 3,
    }

    out.append(emit_auth(
        "G15",
        "AUTH_RESPONSE + identity + device + signature",
        "Respuesta del Initiator al challenge del Responder. Contiene identityId, "
        "la clave publica Ed25519 del dispositivo, y la firma sobre el transcript "
        "de 152 bytes. La verificacion debe pasar V1 (identity), V2 (device) y "
        "V3 (signature).",
        artifacts={
            "response.json": resp,
            "transcript.bin": transcript,
        },
        inputs={
            "nonce": sc.nonce.hex(),
            "timestamp": str(sc.timestamp),
            "responderIdentityId": sc.responder_id,
            "identityId": sc.initiator_id,
            "publicKey": sc.pub["initiator.sign.v1"].hex(),
        },
        meta_extra={
            "transcriptLength": len(transcript),
            "transcriptSha256": hashlib.sha256(transcript).hexdigest(),
            "signature": base64url_encode(ed.sign(transcript, sc.priv["initiator.sign.v1"])),
            "publicKeyHex": sc.pub["initiator.sign.v1"].hex(),
        },
    ))

    # --- G16: AUTH_OK + 120-byte server transcript --------------------------
    server_transcript = server_auth_transcript(sc.session_id, sc.server_nonce, sc.initiator_id)
    ok = {
        "sessionId": sc.session_id,
        "serverNonce": base64url_encode(sc.server_nonce),
        "responderPublicKey": base64url_encode(sc.pub["responder.sign.v1"]),
        "signature": base64url_encode(
            ed.sign(server_transcript, sc.priv["responder.sign.v1"])
        ),
    }

    out.append(emit_auth(
        "G16",
        "AUTH_OK + 120-byte server transcript",
        f"Confirmacion del Responder. El transcript mide exactamente 120 bytes: "
        f"40 (sessionId) + 16 (serverNonce) + 64 (initiatorIdentityId). "
        f"La firma usa la clave del Responder.",
        artifacts={
            "auth_ok.json": ok,
            "transcript.bin": server_transcript,
        },
        inputs={
            "sessionId": sc.session_id,
            "serverNonce": sc.server_nonce.hex(),
            "initiatorIdentityId": sc.initiator_id,
            "responderPublicKey": sc.pub["responder.sign.v1"].hex(),
        },
        meta_extra={
            "transcriptLength": len(server_transcript),
            "transcriptSha256": hashlib.sha256(server_transcript).hexdigest(),
            "signature": base64url_encode(ed.sign(server_transcript, sc.priv["responder.sign.v1"])),
            "responderKeyHex": sc.pub["responder.sign.v1"].hex(),
        },
    ))

    # --- G17: Flujo completo Challenge -> Response --------------------------
    out.append(emit_auth(
        "G17",
        "Flujo completo: Challenge -> Response",
        "Escenario completo del intercambio de autenticacion: el challenge se "
        "emite, el initiator construye el response, y el transcript de 152 bytes "
        "es el mismo en ambos extremos.",
        artifacts={
            "challenge.json": ch,
            "response.json": resp,
            "transcript.bin": transcript,
        },
        inputs={
            "nonce": sc.nonce.hex(),
            "timestamp": str(sc.timestamp),
            "responderIdentityId": sc.responder_id,
            "responderPublicKey": sc.pub["responder.sign.v1"].hex(),
            "initiatorIdentityId": sc.initiator_id,
            "initiatorPublicKey": sc.pub["initiator.sign.v1"].hex(),
        },
        meta_extra={
            "challengeTranscriptSha256": hashlib.sha256(transcript).hexdigest(),
            "responseSignature": base64url_encode(ed.sign(transcript, sc.priv["initiator.sign.v1"])),
        },
    ))

    # --- G18: Flujo completo incluyendo AUTH_OK ------------------------------
    ok = {
        "sessionId": sc.session_id,
        "serverNonce": base64url_encode(sc.server_nonce),
        "responderPublicKey": base64url_encode(sc.pub["responder.sign.v1"]),
        "signature": base64url_encode(
            ed.sign(server_transcript, sc.priv["responder.sign.v1"])
        ),
    }
    out.append(emit_auth(
        "G18",
        "Flujo completo: Challenge -> Response -> AuthOk",
        "Escenario completo con los tres mensajes wire: challenge del Responder, "
        "response del Initiator, y auth_ok del Responder. El server transcript "
        "de 120 bytes usa el mismo initiatorIdentityId del response.",
        artifacts={
            "challenge.json": ch,
            "response.json": resp,
            "auth_ok.json": ok,
        },
        inputs={
            "nonce": sc.nonce.hex(),
            "timestamp": str(sc.timestamp),
            "sessionId": sc.session_id,
            "responderIdentityId": sc.responder_id,
            "initiatorIdentityId": sc.initiator_id,
        },
        meta_extra={
            "authTranscriptSha256": hashlib.sha256(transcript).hexdigest(),
            "serverTranscriptSha256": hashlib.sha256(server_transcript).hexdigest(),
        },
    ))

    # --- G19: JSON wire + Base64URL + verificación criptográfica -------------
    # Generar un challenge con todos los campos completos
    ch_full = {
        "nonce": base64url_encode(sc.nonce),
        "timestamp": sc.timestamp,
        "version": 3,
        "responderIdentityId": sc.responder_id,
    }
    resp_full = {
        "identityId": sc.initiator_id,
        "publicKey": base64url_encode(sc.pub["initiator.sign.v1"]),
        "signature": base64url_encode(
            ed.sign(transcript, sc.priv["initiator.sign.v1"])
        ),
        "protocolVersion": 3,
    }
    ok_full = {
        "sessionId": sc.session_id,
        "serverNonce": base64url_encode(sc.server_nonce),
        "responderPublicKey": base64url_encode(sc.pub["responder.sign.v1"]),
        "signature": base64url_encode(
            ed.sign(server_transcript, sc.priv["responder.sign.v1"])
        ),
    }

    verification = {
        "initiatorIdentityId": sc.initiator_id,
        "responderIdentityId": sc.responder_id,
        "authTranscriptHex": transcript.hex(),
        "authTranscriptBytes": len(transcript),
        "authTranscriptSha256": hashlib.sha256(transcript).hexdigest(),
        "serverTranscriptHex": server_transcript.hex(),
        "serverTranscriptBytes": len(server_transcript),
        "serverTranscriptSha256": hashlib.sha256(server_transcript).hexdigest(),
        "initiatorSignature": base64url_encode(
            ed.sign(transcript, sc.priv["initiator.sign.v1"])
        ),
        "responderSignature": base64url_encode(
            ed.sign(server_transcript, sc.priv["responder.sign.v1"])
        ),
        "base64urlNonce": base64url_encode(sc.nonce),
        "base64urlServerNonce": base64url_encode(sc.server_nonce),
        "base64urlInitiatorKey": base64url_encode(sc.pub["initiator.sign.v1"]),
        "base64urlResponderKey": base64url_encode(sc.pub["responder.sign.v1"]),
    }

    out.append(emit_auth(
        "G19",
        "JSON wire + Base64URL + verificacion criptografica",
        "Conjunto completo de artefactos wire con todos los campos JSON y "
        "sus verificaciones criptograficas. Los campos Base64URL cumplen "
        "RFC 4648 seccion 5: sin padding, URL-safe.",
        artifacts={
            "challenge.json": ch_full,
            "response.json": resp_full,
            "auth_ok.json": ok_full,
            "verification.json": verification,
        },
        inputs={
            "nonce": sc.nonce.hex(),
            "serverNonce": sc.server_nonce.hex(),
            "timestamp": str(sc.timestamp),
            "sessionId": sc.session_id,
            "responderPublicKey": sc.pub["responder.sign.v1"].hex(),
            "initiatorPublicKey": sc.pub["initiator.sign.v1"].hex(),
        },
        meta_extra={
            "base64urlInitiatorKey": base64url_encode(sc.pub["initiator.sign.v1"]),
            "base64urlResponderKey": base64url_encode(sc.pub["responder.sign.v1"]),
        },
    ))

    return out


# ---------------------------------------------------------------------------
# Fixtures invalidos I1..I5
# ---------------------------------------------------------------------------

def build_invalid_fixtures(sc: Scenario) -> list[dict]:
    out: list[dict] = []

    # I1: identityId incorrecto en AuthResponse
    wrong_id = hashlib.sha256(b"bogus-key").digest()[:20].hex() * 3 + "abcd"
    wrong_id = wrong_id[:64]
    resp_wrong_id = {
        "identityId": wrong_id,
        "publicKey": base64url_encode(sc.pub["initiator.sign.v1"]),
        "signature": base64url_encode(ByteArray(64)),
        "protocolVersion": 3,
    }
    out.append(emit_invalid(
        "I1-wrong-identity.json",
        resp_wrong_id,
        "INVALID_IDENTITY",
        "identityId no deriva de publicKey. El verificador debe detectar "
        "V1 antes de comprobar la firma.",
    ))

    # I2: deviceId incorrecto en AuthResponse (signingKey no corresponde)
    # Nota: KM-0002 V2 verifica que deviceId == SHA-256(KM-ID-DEVICE || signingKey)
    # Como en 3F no implementamos V2, el error primario es INVALID_IDENTITY
    # si identityId no coincide.
    resp_wrong_device = {
        "identityId": sc.initiator_id,
        "publicKey": base64url_encode(sc.pub["responder.sign.v1"]),  # clave del Responder, no del Initiator
        "signature": base64url_encode(ByteArray(64)),
        "protocolVersion": 3,
    }
    out.append(emit_invalid(
        "I2-wrong-device.json",
        resp_wrong_device,
        "INVALID_IDENTITY",
        "La publicKey no corresponde al identityId declarado. Aunque deviceId "
        "no se verifica en 3F, identityId != SHA-256(KM-ID-IDENTITY || publicKey) "
        "y debe fallar en V1.",
    ))

    # I3: firma invalida sobre el transcript
    # Firmamos con la clave del Responder y declaramos identityId del Initiator
    transcript = auth_transcript(sc.nonce, sc.timestamp, sc.responder_id, sc.initiator_id)
    bad_sig = ed.sign(transcript, sc.priv["responder.sign.v1"])  # firma del Responder
    resp_bad_sig = {
        "identityId": sc.initiator_id,
        "publicKey": base64url_encode(sc.pub["initiator.sign.v1"]),
        "signature": base64url_encode(bad_sig),
        "protocolVersion": 3,
    }
    out.append(emit_invalid(
        "I3-bad-signature.json",
        resp_bad_sig,
        "INVALID_SIGNATURE",
        "La firma esta calculada con la clave privada del Responder, no del "
        "Initiator. identityId y publicKey son correctos, pero la firma no "
        "verifica. Debe fallar en V3.",
    ))

    # I4: timestamp fuera de ventana
    ch_bad_ts = {
        "nonce": base64url_encode(sc.nonce),
        "timestamp": sc.timestamp - 600_000,  # 10 minutos en el pasado (fuera de la ventana de 300s)
        "version": 3,
        "responderIdentityId": sc.responder_id,
    }
    out.append(emit_invalid(
        "I4-bad-timestamp.json",
        ch_bad_ts,
        "INVALID_TIMESTAMP",
        "Timestamp 10 minutos por debajo del challenge nominal. Con clock "
        "fijo en el timestamp nominal, este valor esta fuera de la ventana "
        "de tolerancia (default 300s). Debe fallar en V5.",
    ))

    # I5: nonce reutilizado (replay)
    # Se necesita un challenge identico al G14 pero el verificador debe tener
    # un NonceReplayGuard que ya vio el nonce. El fixture es identico a G14
    # y el test debe configurar el guard para rechazarlo.
    # Emitimos el challenge de G14 como fixture invalido con nota de replay.
    ch_replay = {
        "nonce": base64url_encode(sc.nonce),
        "timestamp": sc.timestamp,
        "version": 3,
        "responderIdentityId": sc.responder_id,
    }
    out.append(emit_invalid(
        "I5-replay.json",
        ch_replay,
        "REPLAY_DETECTED",
        "Challenge identico a G14 pero con un NonceReplayGuard que ya vio "
        "el nonce. Debe fallar en V6.",
    ))

    return out


# ---------------------------------------------------------------------------
# ByteArray helper
# ---------------------------------------------------------------------------

class ByteArray:
    """Emula ByteArray(64) -> 64 bytes de zeros."""
    def __init__(self, size: int):
        self.size = size
    def __repr__(self) -> str:
        return "00" * self.size

    def __len__(self) -> int:
        return self.size


# Hay que parchear base64url_encode para que convierta ByteArray a bytes reales
_orig_encode = base64url_encode
def _patched_encode(data: bytes) -> str:
    if isinstance(data, ByteArray):
        data = bytes(data.size)
    return _orig_encode(data)
base64url_encode = _patched_encode


# ---------------------------------------------------------------------------
# MANIFEST
# ---------------------------------------------------------------------------

def write_manifest(vectors: list[dict], invalid: list[dict], sc: Scenario) -> None:
    manifest = {
        "spec": "KM-0002",
        "generatedBy": "km-id-reference/auth_generate.py",
        "seedDerivation": 'SHA-256(UTF8("KM-AUTH-0002/" || label)',
        "note": "Vectores de autenticacion KM-0002. Los vectores se CALCULAN "
        "ejecutando la implementacion de referencia.",
        "seeds": dict(sorted(sc.seeds.items())),
        "vectors": sorted(vectors, key=lambda v: v["id"]),
        "invalid": invalid,
    }
    with open(os.path.join(HERE, "AUTH_MANIFEST.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, sort_keys=True, ensure_ascii=False)
        f.write("\n")


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main() -> int:
    os.makedirs(VECTORS_DIR, exist_ok=True)
    os.makedirs(INVALID_DIR, exist_ok=True)

    sc = Scenario()
    vectors = build_auth_vectors(sc)
    invalid = build_invalid_fixtures(sc)
    write_manifest(vectors, invalid, sc)

    print(f"Generados {len(vectors)} vectores de autenticacion en vectors/")
    for v in vectors:
        print(f"  {v['id']}  {v['title']}")
    print(f"Generados {len(invalid)} fixtures invalidos en invalid/")
    for inv in invalid:
        print(f"  {inv['file']}  {inv['expected']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
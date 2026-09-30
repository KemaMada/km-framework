"""
KM Canonical Encoding (KCE)
===========================

Implementacion de referencia de KCE segun KM-ID-0001 seccion 6.

KCE es un perfil determinista de CBOR basado en RFC 8949 seccion 4.2.1
(Core Deterministic Encoding Requirements) mas las restricciones
normativas propias de KM.

KCE no es "usar un encoder CBOR": es una funcion de protocolo cuyo
resultado byte-a-byte forma parte de la semantica criptografica. Un
documento firmado que no cumpla KCE debe rechazarse aunque sea CBOR
valido.

Este modulo no implementa ninguna otra parte del protocolo.
"""

from __future__ import annotations

import unicodedata
from typing import Any

# ---------------------------------------------------------------------------
# Limites de encoding (nivel KCE, no nivel de esquema)
# ---------------------------------------------------------------------------

MAX_UINT64 = (1 << 64) - 1
MAX_DOCUMENT_BYTES = 65536
MAX_TEXT_BYTES = 1024
MAX_MAP_ITEMS = 64
MAX_ARRAY_ITEMS = 256
MAX_DEPTH = 8

# ---------------------------------------------------------------------------
# Major types
# ---------------------------------------------------------------------------

_MT_UINT = 0
_MT_NINT = 1
_MT_BSTR = 2
_MT_TSTR = 3
_MT_ARRAY = 4
_MT_MAP = 5
_MT_TAG = 6
_MT_SIMPLE = 7

# Limite de anidamiento que el lector de version (seccion 7.1) esta
# autorizado a recorrer al saltar valores. Deliberadamente mas bajo que
# MAX_DEPTH: el lector acotado no debe poder recorrer en profundidad.
FRAMER_MAX_DEPTH = 4

RESERVED_AI = {28, 29, 30}
_INDEFINITE = 31

# Para major type 7, los AI 25..27 son las tres anchuras de float de RFC
# 8949. El AI 24 NO es un float: sigue siendo un valor simple de 1 byte.
_FLOAT_AIS = (25, 26, 27)
_FLOAT_WIDTHS = {25: "half precision", 26: "precision simple", 27: "precision doble"}


class KceError(Exception):
    """Error de KCE con codigo estable, citable desde los fixtures."""

    def __init__(self, code: str, message: str = "") -> None:
        super().__init__(f"{code}: {message}" if message else code)
        self.code = code
        self.message = message


# ---------------------------------------------------------------------------
# Encoder
# ---------------------------------------------------------------------------


def _head(major: int, arg: int) -> bytes:
    """Codifica una cabecera CBOR con serializacion preferida (RFC 8949 4.1)."""
    if arg < 0:
        raise KceError("NEGATIVE_INTEGER", "los enteros negativos estan prohibidos por KCE")
    if arg < 24:
        return bytes([(major << 5) | arg])
    if arg <= 0xFF:
        return bytes([(major << 5) | 24, arg])
    if arg <= 0xFFFF:
        return bytes([(major << 5) | 25]) + arg.to_bytes(2, "big")
    if arg <= 0xFFFFFFFF:
        return bytes([(major << 5) | 26]) + arg.to_bytes(4, "big")
    if arg <= MAX_UINT64:
        return bytes([(major << 5) | 27]) + arg.to_bytes(8, "big")
    raise KceError("UINT_OUT_OF_RANGE", f"{arg} excede uint64")


def _encode(value: Any, depth: int) -> bytes:
    if depth > MAX_DEPTH:
        raise KceError("MAX_DEPTH_EXCEEDED", f"profundidad {depth}")

    # bool es subclase de int en Python: comprobar primero.
    if isinstance(value, bool):
        return bytes([(_MT_SIMPLE << 5) | (20 if value else 21)])
    if isinstance(value, int):
        if value > MAX_UINT64:
            raise KceError("UINT_OUT_OF_RANGE", f"{value} excede uint64")
        return _head(_MT_UINT, value)
    if isinstance(value, (bytes, bytearray)):
        raw = bytes(value)
        return _head(_MT_BSTR, len(raw)) + raw
    if isinstance(value, str):
        raw = value.encode("utf-8")
        if len(raw) > MAX_TEXT_BYTES:
            raise KceError("TEXT_TOO_LONG", f"{len(raw)} > {MAX_TEXT_BYTES}")
        if unicodedata.normalize("NFC", value) != value:
            raise KceError("TEXT_NOT_NFC", "el texto debe estar en NFC antes de codificar")
        return _head(_MT_TSTR, len(raw)) + raw
    if isinstance(value, (list, tuple)):
        if len(value) > MAX_ARRAY_ITEMS:
            raise KceError("ARRAY_TOO_LONG", f"{len(value)} > {MAX_ARRAY_ITEMS}")
        return _head(_MT_ARRAY, len(value)) + b"".join(_encode(v, depth + 1) for v in value)
    if isinstance(value, dict):
        return _encode_map(value, depth)
    raise KceError("UNSUPPORTED_TYPE", type(value).__name__)


def sorted_pairs(mapping: dict) -> list[tuple[bytes, Any]]:
    """Pares (clave_codificada, valor) de un mapa, en el orden canonico KCE.

    ORDEN POR BYTES CODIFICADOS, NO ALFABETICO. Esta es la propiedad que
    mas errores produce al implementar KCE.

    RFC 8949 4.2.1 manda ordenar por el orden lexicografico bytewise de las
    CODIFICACIONES DETERMINISTAS de las claves, y la codificacion de una
    clave tstr corta empieza por su PREFIXIO DE LONGITUD. Por tanto el
    prefijo domina al contenido y, en la practica, las claves se ordenan
    primero por LONGITUD y despues alfabeticamente entre las de igual
    longitud.

    Ejemplo real, con las claves de un DeviceRoster:

        doc (3)  devices (7)  version (7)  previous (8)  sequence (8)
        createdAt (9)  signature (9)  identityId (10)  identityRoot (12)

    El orden alfabetico del texto seria identityId, identityRoot, previous,
    signature... y produce un documento DISTINTO e incompatible.

    Consecuencia practica: no se puede alterar el ultimo byte de un
    documento suponiendo que pertenece a la firma. El ultimo campo del mapa
    es el de la clave mas larga, no el de `signature`.
    """
    entries = []
    for key, val in mapping.items():
        if not isinstance(key, str):
            raise KceError("MAP_KEY_NOT_TEXT", type(key).__name__)
        raw = key.encode("utf-8")
        if len(raw) > MAX_TEXT_BYTES:
            raise KceError("TEXT_TOO_LONG", f"clave de {len(raw)} bytes")
        if unicodedata.normalize("NFC", key) != key:
            raise KceError("TEXT_NOT_NFC", "la clave debe estar en NFC")
        entries.append((_head(_MT_TSTR, len(raw)) + raw, val))
    entries.sort(key=lambda kv: kv[0])
    return entries


def _decode_key(key_bytes: bytes) -> str:
    """Inverso de la codificacion de clave que hace `sorted_pairs`.

    Util para diagnostico y para pruebas. No es un decodificador general: solo
    admite claves tstr, que es el unico tipo de clave que KCE permite.
    """
    reader = _Reader(key_bytes)
    major, arg, _ai = reader.read_head()
    if major != _MT_TSTR:
        raise KceError("MAP_KEY_NOT_TEXT", f"major type {major}")
    return key_bytes[1:].decode("utf-8")


def _encode_map(mapping: dict, depth: int) -> bytes:
    if len(mapping) > MAX_MAP_ITEMS:
        raise KceError("MAP_TOO_LONG", f"{len(mapping)} > {MAX_MAP_ITEMS}")

    entries = sorted_pairs(mapping)
    out = _head(_MT_MAP, len(entries))
    for key_bytes, val in entries:
        out += key_bytes + _encode(val, depth + 1)
    return out


def encode(value: Any) -> bytes:
    """Codifica un valor KM a bytes KCE."""
    out = _encode(value, 0)
    if len(out) > MAX_DOCUMENT_BYTES:
        raise KceError("DOCUMENT_TOO_LARGE", f"{len(out)} > {MAX_DOCUMENT_BYTES}")
    return out


# ---------------------------------------------------------------------------
# Decoder
# ---------------------------------------------------------------------------


class _Reader:
    __slots__ = ("data", "pos", "n")

    def __init__(self, data: bytes) -> None:
        self.data = data
        self.pos = 0
        self.n = len(data)

    def take(self, count: int) -> bytes:
        if count < 0 or self.pos + count > self.n:
            raise KceError("TRUNCATED_INPUT")
        chunk = self.data[self.pos : self.pos + count]
        self.pos += count
        return chunk

    def read_head(self) -> tuple[int, int, int]:
        """Devuelve (major, arg, ai).

        El AI se conserva porque para `major == 7` es el UNICO dato que
        permite distinguir un float de 25/26/27 bits de un valor simple
        ordinario: tras consumir el argumento adicional, `arg` contiene el
        PAYLOAD del float, no su anchura. Descartar el ai hacia que todo
        float se reportase como SIMPLE_VALUE_FORBIDDEN en lugar de
        FLOAT_FORBIDDEN, y que los chequeos de false/true/null fueran
        coincidencias afortunadas.
        """
        if self.pos >= self.n:
            raise KceError("TRUNCATED_INPUT")
        initial = self.data[self.pos]
        self.pos += 1
        major = initial >> 5
        ai = initial & 0x1F
        if ai < 24:
            return major, ai, ai
        if ai in RESERVED_AI:
            raise KceError("RESERVED_ADDITIONAL_INFO", str(ai))
        if ai == _INDEFINITE:
            raise KceError("INDEFINITE_LENGTH", "las longitudes indefinidas estan prohibidas")
        # El ancho del argumento NO depende del major type: ai 24 siempre son
        # 1 byte, 25 -> 2, 26 -> 4, 27 -> 8. Para major 7, los AI 25..27 son
        # las anchuras de float de RFC 8949 (media, simple, doble); el AI 24
        # sigue siendo un valor simple de 1 byte.
        width = {24: 1, 25: 2, 26: 4, 27: 8}[ai]
        raw = self.take(width)
        return major, int.from_bytes(raw, "big"), ai

    def skip(self, depth: int = 0) -> None:
        """Salta un valor completo con limite de profundidad."""
        if depth > MAX_DEPTH:
            raise KceError("MAX_DEPTH_EXCEEDED")
        major, arg, ai = self.read_head()
        if major == _MT_UINT or major == _MT_NINT:
            if major == _MT_NINT:
                raise KceError("NEGATIVE_INTEGER")
            return
        if major in (_MT_BSTR, _MT_TSTR):
            if arg > MAX_TEXT_BYTES:
                raise KceError("TEXT_TOO_LONG", f"{arg} > {MAX_TEXT_BYTES}")
            self.take(arg)
            return
        if major == _MT_ARRAY:
            for _ in range(arg):
                self.skip(depth + 1)
            return
        if major == _MT_MAP:
            for _ in range(arg):
                self.skip(depth + 1)
                self.skip(depth + 1)
            return
        if major == _MT_SIMPLE and ai in _FLOAT_AIS:
            raise KceError("FLOAT_FORBIDDEN", _FLOAT_WIDTHS[ai])
        raise KceError("FORBIDDEN_MAJOR_TYPE", str(major))

    def read_value(self, depth: int = 0) -> Any:
        if depth > MAX_DEPTH:
            raise KceError("MAX_DEPTH_EXCEEDED")
        major, arg, ai = self.read_head()

        if major == _MT_UINT:
            if arg > MAX_UINT64:
                raise KceError("UINT_OUT_OF_RANGE")
            return arg
        if major == _MT_NINT:
            raise KceError("NEGATIVE_INTEGER")
        if major == _MT_BSTR:
            if arg > MAX_TEXT_BYTES:
                raise KceError("TEXT_TOO_LONG", f"{arg} > {MAX_TEXT_BYTES}")
            return self.take(arg)
        if major == _MT_TSTR:
            if arg > MAX_TEXT_BYTES:
                raise KceError("TEXT_TOO_LONG", f"{arg} > {MAX_TEXT_BYTES}")
            raw = self.take(arg)
            try:
                return raw.decode("utf-8")
            except UnicodeDecodeError as exc:
                raise KceError("INVALID_UTF8") from exc
        if major == _MT_ARRAY:
            if arg > MAX_ARRAY_ITEMS:
                raise KceError("ARRAY_TOO_LONG", f"{arg} > {MAX_ARRAY_ITEMS}")
            return [self.read_value(depth + 1) for _ in range(arg)]
        if major == _MT_MAP:
            if arg > MAX_MAP_ITEMS:
                raise KceError("MAP_TOO_LONG", f"{arg} > {MAX_MAP_ITEMS}")
            result: dict[str, Any] = {}
            for _ in range(arg):
                key = self.read_value(depth + 1)
                if not isinstance(key, str):
                    raise KceError("MAP_KEY_NOT_TEXT", type(key).__name__)
                if key in result:
                    raise KceError("DUPLICATE_MAP_KEY", key)
                result[key] = self.read_value(depth + 1)
            return result

        if major == _MT_TAG:
            raise KceError("TAGS_FORBIDDEN", "las etiquetas CBOR estan prohibidas salvo definicion KM")
        # _MT_SIMPLE: la discriminacion es por AI, nunca por arg.
        if arg == 20 and ai == 20:
            return False
        if arg == 21 and ai == 21:
            return True
        if ai == 22:
            raise KceError("NULL_FORBIDDEN")
        if ai == 23:
            raise KceError("UNDEFINED_FORBIDDEN")
        if ai in _FLOAT_AIS:
            raise KceError("FLOAT_FORBIDDEN", _FLOAT_WIDTHS[ai])
        raise KceError("SIMPLE_VALUE_FORBIDDEN", str(ai))


def decode(data: bytes) -> Any:
    """Decodifica bytes CBOR aplicando las prohibiciones semanticas de KCE.

    No comprueba el orden canonico de las claves ni la serializacion
    preferida: eso lo comprueba `validate` mediante el round-trip.
    """
    if len(data) > MAX_DOCUMENT_BYTES:
        raise KceError("DOCUMENT_TOO_LARGE", f"{len(data)} > {MAX_DOCUMENT_BYTES}")
    reader = _Reader(data)
    value = reader.read_value()
    if reader.pos != reader.n:
        raise KceError("TRAILING_BYTES", f"{reader.n - reader.pos} bytes sobrantes")
    return value


# ---------------------------------------------------------------------------
# Validacion KCE
# ---------------------------------------------------------------------------


def validate(data: bytes) -> Any:
    """Valida que `data` cumple KCE y devuelve el valor decodificado.

    Aplica, en orden:
      1. limite de tamano de documento
      2. prohibiciones semanticas (tags, null, floats, negativos,
         longitudes indefinidas, claves duplicadas, UTF-8, tamano)
      3. round-trip byte a byte contra `encode`

    El paso 3 es lo que detecta el orden no canonico de las claves y las
    cabeceras no minimas: una representacion no determinista decodifica a
    un valor que se re-codifica distinto.
    """
    if len(data) > MAX_DOCUMENT_BYTES:
        raise KceError("DOCUMENT_TOO_LARGE", f"{len(data)} > {MAX_DOCUMENT_BYTES}")

    value = decode(data)
    re_encoded = encode(value)
    if re_encoded != data:
        # Localiza la primera diferencia para que el fixture sea util.
        for i, (a, b) in enumerate(zip(data, re_encoded)):
            if a != b:
                raise KceError(
                    "KCE_ROUNDTRIP_MISMATCH",
                    f"primera diferencia en offset {i}: recibido 0x{a:02x}, canonico 0x{b:02x}",
                )
        raise KceError("KCE_ROUNDTRIP_MISMATCH", "longitudes distintas")
    return value


# ---------------------------------------------------------------------------
# Lector de version acotado (KM-ID-0001 seccion 7.1)
# ---------------------------------------------------------------------------


class VersionResult:
    __slots__ = ("version", "reason")

    def __init__(self, version: int | None, reason: str | None) -> None:
        self.version = version
        self.reason = reason

    def __repr__(self) -> str:  # pragma: no cover - diagnostico
        return f"VersionResult(version={self.version}, reason={self.reason})"


def peek_version(data: bytes, *, max_version: int = 1) -> VersionResult:
    """Extrae el campo `version` de nivel superior sin parsear el documento.

    Lector de framing deliberadamente restringido: NO es un segundo parser
    CBOR general. Solo sabe distinguir un map de nivel superior con
    longitudes definitivas, localizar claves de texto y saltar valores
    dentro de un limite de profundidad. Si no puede determinar la version
    de forma inequivoca devuelve `INVALID_ENCODING` en lugar de adivinar.

    Devolveria:
      - (version, None)              si se extrajo una version soportable
      - (None, "UNSUPPORTED_VERSION") si version > max_version
      - (None, "INVALID_ENCODING")   si no puede determinar inequivocamente
    """
    if len(data) > MAX_DOCUMENT_BYTES:
        return VersionResult(None, "DOCUMENT_TOO_LARGE")

    reader = _Reader(data)
    try:
        major, count, _ai = reader.read_head()
        if major != _MT_MAP:
            return VersionResult(None, "INVALID_ENCODING")
        if count > MAX_MAP_ITEMS:
            return VersionResult(None, "INVALID_ENCODING")

        found: int | None = None
        for _ in range(count):
            kmajor, klen, _kai = reader.read_head()
            if kmajor != _MT_TSTR or klen > MAX_TEXT_BYTES:
                return VersionResult(None, "INVALID_ENCODING")
            try:
                key = reader.take(klen).decode("utf-8")
            except UnicodeDecodeError:
                return VersionResult(None, "INVALID_ENCODING")

            if key == "version":
                vmajor, vlen, _vai = reader.read_head()
                if vmajor != _MT_UINT:
                    return VersionResult(None, "INVALID_ENCODING")
                found = vlen
            else:
                _skip_framed(reader, 1)

        if found is None:
            return VersionResult(None, "INVALID_ENCODING")
        if found > max_version:
            return VersionResult(None, "UNSUPPORTED_VERSION")
        return VersionResult(found, None)
    except KceError:
        return VersionResult(None, "INVALID_ENCODING")


def _skip_framed(reader: _Reader, depth: int) -> None:
    """Salta un valor con limite de profundidad FRAMER_MAX_DEPTH."""
    if depth > FRAMER_MAX_DEPTH:
        raise KceError("FRAMER_DEPTH_EXCEEDED")
    major, arg, _ai = reader.read_head()
    if major == _MT_UINT:
        return
    if major == _MT_NINT:
        raise KceError("NEGATIVE_INTEGER")
    if major in (_MT_BSTR, _MT_TSTR):
        if arg > MAX_TEXT_BYTES:
            raise KceError("TEXT_TOO_LONG")
        reader.take(arg)
        return
    if major == _MT_ARRAY:
        for _ in range(arg):
            _skip_framed(reader, depth + 1)
        return
    if major == _MT_MAP:
        for _ in range(arg):
            _skip_framed(reader, depth + 1)
            _skip_framed(reader, depth + 1)
        return
    raise KceError("FORBIDDEN_MAJOR_TYPE", str(major))

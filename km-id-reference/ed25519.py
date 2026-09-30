"""
Ed25519 (RFC 8032) - implementacion de referencia, sin dependencias
====================================================================

Porta directa de la implementacion de referencia de RFC 8032
(seccion 5, "Reference implementations"), con la API reorganizada para
emitir claves a partir de semillas deterministas.

Se usa implementacion propia y no una biblioteca de terceros por dos
motivos:

  1. La implementacion de referencia de KM debe ser auditable de punta a
     punta, sin depender de la version de una dependencia.
  2. Los fixtures de KM-ID-0001 se generan a partir de semillas fijas, y
     las firmas deben ser reproducibles byte a byte por cualquier otra
     implementacion.

`selfcheck.py` cross-valida estas firmas contra la biblioteca
`cryptography`, de modo que un error aqui no puede pasar inadvertido.

Este modulo NO implementa X25519. Las claves de acuerdo son material opaco
de 32 bytes en la capa de identidad; el acuerdo de claves pertenece a
KM-SESSION-0001.
"""

from __future__ import annotations

import hashlib

B = 256
Q = 2**255 - 19
L = 2**252 + 27742317777372353535851937790883648493

PUBLIC_KEY_BYTES = 32
PRIVATE_KEY_BYTES = 32
SIGNATURE_BYTES = 64

_D = -121665 * pow(121666, Q - 2, Q) % Q
_I = pow(2, (Q - 1) // 4, Q)

# Puntos en coordenadas extendidas: (X, Y, Z, T) con x = X/Z, y = Y/Z
_Point = tuple[int, int, int, int]


def _sha512(data: bytes) -> bytes:
    return hashlib.sha512(data).digest()


def _x_recover(y: int) -> int:
    xx = (y * y - 1) * pow(_D * y * y + 1, Q - 2, Q) % Q
    x = pow(xx, (Q + 3) // 8, Q)
    if (x * x - xx) % Q != 0:
        x = x * _I % Q
    if x % 2 != 0:
        x = Q - x
    return x


_BY = 4 * pow(5, Q - 2, Q) % Q
_BX = _x_recover(_BY)
_B: _Point = (_BX % Q, _BY % Q, 1, _BX * _BY % Q)


def _point_add(p: _Point, q: _Point) -> _Point:
    x1, y1, z1, t1 = p
    x2, y2, z2, t2 = q
    a = (y1 - x1) * (y2 - x2) % Q
    b = (y1 + x1) * (y2 + x2) % Q
    c = t1 * 2 * _D * t2 % Q
    dd = z1 * 2 * z2 % Q
    e = b - a
    f = dd - c
    g = dd + c
    h = b + a
    return (e * f % Q, g * h % Q, f * g % Q, e * h % Q)


def _point_double(p: _Point) -> _Point:
    # Formula 'dbl-2008-hwcd' de Hyperelliptic.org, en coordenadas extendidas.
    x1, y1, z1, _t1 = p

    a = x1 * x1 % Q
    b = y1 * y1 % Q
    c = 2 * z1 * z1 % Q
    e = ((x1 + y1) * (x1 + y1) - a - b) % Q
    g = -a + b
    f = g - c
    h = -a - b
    return (e * f % Q, g * h % Q, f * g % Q, e * h % Q)


def _scalar_mult(p: _Point, e: int) -> _Point:
    if e == 0:
        return (0, 1, 1, 0)
    result = _scalar_mult(p, e // 2)
    result = _point_double(result)
    if e & 1:
        result = _point_add(result, p)
    return result


def _point_compress(p: _Point) -> bytes:
    x, y, z, _t = p
    zi = pow(z, Q - 2, Q)
    x = x * zi % Q
    y = y * zi % Q
    return (y | ((x & 1) << 255)).to_bytes(32, "little")


def _point_decompress(data: bytes) -> _Point:
    if len(data) != 32:
        raise ValueError("punto invalido: se esperaban 32 bytes")
    y = int.from_bytes(data, "little")
    sign = y >> 255
    y &= (1 << 255) - 1
    if y >= Q:
        raise ValueError("punto invalido: y fuera de rango")
    x = _x_recover(y)
    if x & 1 != sign:
        x = Q - x
    if (-x * x + y * y - 1 - _D * x * x * y * y) % Q != 0:
        raise ValueError("punto invalido: no esta sobre la curva")
    return (x, y, 1, x * y % Q)


def _bit(h: bytes, i: int) -> int:
    return (h[i // 8] >> (i % 8)) & 1


def _clamped_scalar(h: bytes) -> int:
    a = 2 ** (B - 2) + sum(2**i * _bit(h, i) for i in range(3, B - 2))
    return a


def keypair_from_seed(seed: bytes) -> tuple[bytes, bytes]:
    """Deriva (publicKey, privateKey) de forma determinista desde una semilla.

    CONVENCION: en Ed25519 la "clave privada" ES la semilla de 32 bytes.
    El escalar y el prefijo de nonce se derivan como h = SHA-512(semilla)
    dentro de cada operacion. Guardar h[:32] como clave privada seria
    incorrecto, porque firmacion volveria a aplicar SHA-512 y romperia la
    cadena de derivacion.

    La semilla DEBE tener exactamente 32 bytes. La misma semilla produce
    siempre el mismo par, en cualquier implementacion conforme a RFC 8032.
    """
    if len(seed) != 32:
        raise ValueError(f"la semilla debe tener 32 bytes, se recibieron {len(seed)}")
    h = _sha512(seed)
    a = _clamped_scalar(h)
    public_key = _point_compress(_scalar_mult(_B, a))
    return public_key, seed


def sign(message: bytes, private_key: bytes) -> bytes:
    if len(private_key) != PRIVATE_KEY_BYTES:
        raise ValueError(f"la clave privada debe tener 32 bytes, se recibieron {len(private_key)}")
    # private_key es la semilla; h = SHA-512(semilla) segun RFC 8032.
    h = _sha512(private_key)
    a = _clamped_scalar(h)
    # RFC 8032 usa little-endian en las tres conversiones hash->entero y en
    # la codificacion del escalar S. Verificado contra los vectores oficiales
    # de la seccion 7.1.
    r = int.from_bytes(_sha512(h[32:64] + message), "little") % L
    big_r = _point_compress(_scalar_mult(_B, r))
    challenge = int.from_bytes(_sha512(big_r + _derive_public(private_key) + message), "little")
    s = (r + challenge * a) % L
    return big_r + s.to_bytes(32, "little")


def verify(signature: bytes, message: bytes, public_key: bytes) -> bool:
    if len(signature) != SIGNATURE_BYTES:
        return False
    if len(public_key) != PUBLIC_KEY_BYTES:
        return False
    try:
        big_r = _point_decompress(signature[:32])
        point_a = _point_decompress(public_key)
    except ValueError:
        return False
    s = int.from_bytes(signature[32:], "little")
    if s >= L:
        return False
    h = int.from_bytes(_sha512(signature[:32] + public_key + message), "little")
    lhs = _scalar_mult(_B, s)
    rhs = _point_add(big_r, _scalar_mult(point_a, h))
    return (lhs[0] * rhs[2] - rhs[0] * lhs[2]) % Q == 0 and (
        lhs[1] * rhs[2] - rhs[1] * lhs[2]
    ) % Q == 0


def _derive_public(private_key: bytes) -> bytes:
    return _point_compress(_scalar_mult(_B, _clamped_scalar(_sha512(private_key))))

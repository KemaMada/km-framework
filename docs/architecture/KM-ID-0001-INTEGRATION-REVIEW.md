# Integration Review: KM-ID-0001 ↔ Existing Identity Model

**Date:** 2026-09-27
**Specs compared:** KM-ID-0001 (identity chain) vs KM-0002 (authentication) vs KM-0005 (relay)
**Status:** Identity chain frozen, all layers ported
**Tests:** 141, 0 failures (93 pre-existing + 48 new across Increments 3A–3B)

## 0. Increment Status

| Increment | Scope | Status | Detail |
|-----------|-------|--------|--------|
| **3A** | KM-ID-0001 identity/device derivation, KCE, schemas, DeviceRoster | **COMPLETE** | 118 tests, byte-identical G1–G6, all 13 negative fixtures verified |
| **3B** | ContactBundle, SignedPrekey, OneTimePrekey | **COMPLETE** | 23 new tests (141 total), G10 contact.cbor/device_link.cbor byte-identical, wrong-device-binding.cbor → SUBJECT_BINDING_MISMATCH, M1–M9 mutation tests |
| **3C** | NodeIdentity/NodeAnnouncement KM-ID-0001 integration | **COMPLETE** | 11 new tests (152 total), `fromKeyPair()` factory, `verifyWireBytes()` against KM-0005 §9.4 |
| **3E** | identityId ≠ publicKey (SignatureVerifierImpl, AckManagerImpl, Ed25519Impl) | **COMPLETE** | 15 regression tests (167 total). `SignedMessage`/`SignedAck` now carry `publicKey`. `Ed25519Impl.verify()` rejects wrong-size keys. `AckManagerImpl` injects public key resolver. `NodeRuntimeImpl.fromKeyPair()` factory. |
| **3F** | KM-0002 wire integration: Base64URL + TranscriptBuilder + AuthVerifier + codecs + golden vectors + mutation tests + KM-0003 binding | **DONE** | 315 tests (0 fail). 3F cerrado. Pendiente: 3G (AuthSession), 3H (ContactBundle multi-device). |

### Known Follow-up (post-3D)

| Item | Scope | Reason |
|------|-------|--------|
| `SignatureVerifierImpl` uses `identityId.value.toByteArray()` as public key | `km-core/.../crypto/SignatureVerifierImpl.kt` | **Bug**: identityId ≠ publicKey; derived hash used as signing material |
| `NodeRuntimeImpl` constructs `NodeIdentity(nodeId, pubKey)` directly | `km-core/.../node/NodeRuntimeImpl.kt` | Bypasses `fromKeyPair()` factory; no derivation enforcement at runtime boundary |
| `NodeAnnouncement` JSON codec | Wire layer | No deterministic JSON serializer exists yet; verification depends on raw bytes from transport |

## 1. Purpose

Determine whether the existing KM-0002/KM-0005 identity model is compatible with
KM-ID-0001, and what must change for a unified identity model.

This review covers every identity-related type, wire field, and derivation rule
found in code and RFCs. It does **not** modify `NodeIdentity`,
`NodeAnnouncement`, `Message`, or any wire protocol yet.

---

## 2. Core Conflict: Three Different `identityId` Derivations

Currently the project has THREE incompatible definitions of `identityId`. They
produce **different byte sequences** for the same Ed25519 public key.

| Source | Derivation | Output | Wire size |
|--------|-----------|--------|-----------|
| **KM-0005 §9.2** | `first160bits(SHA-256(publicKey))` | 20 bytes | 40 hex chars |
| **KM-ID-0001 §6** | `SHA-256("KM-ID-IDENTITY" \|\| publicKey)` | 32 bytes | 64 hex chars or `bstr32` in KCE |
| **App (legacy RSA)** | `SHA-256(publicKey)`, first 12 bytes | 12 bytes | 24 hex chars |

These are not representations of the same value truncated differently. They use
different hash inputs, so the 20-byte truncation of KM-ID-0001 is **not equal**
to the 20 bytes of KM-0005.

---

## 3. Compatibility Matrix

### 3.1 Identity Types

| Concept | KM-0002 / KM-0005 (current) | KM-ID-0001 (new) | Conflict? | Action |
|---------|----------------------------|-------------------|-----------|--------|
| `identityId` derivation | `first160bits(SHA-256(pubkey))`, 20 bytes | `SHA-256("KM-ID-IDENTITY" \|\| pubkey)`, 32 bytes | **FULL — different hash inputs** | Adopt KM-ID-0001 derivation. KM-0005 must update §9.2 |
| `identityId` wire format | 40 hex chars in JSON | 64 hex chars or `bstr32` in KCE | **Partial — length differs** | Wire protocol must accept 64 hex chars |
| `deviceId` semantics | Opaque, ≤64 ASCII bytes, not in auth transcript | Cryptographically derived: `SHA-256("KM-ID-DEVICE" \|\| signingKey)`, 32 bytes | **FULL — semantic incompatibility** | Adopt KM-ID-0001 deviceId. Remove opaque deviceId from auth response |
| `deviceId` wire format | ASCII string, up to 64 bytes in AUTH_RESPONSE | 64 hex chars or `bstr32` in KCE | **Format change** | Wire format must change to match |
| `nodeId` | `identityId` of the node (KM-0005 §6.3) | Derivation exists: `SHA-256("KM-NODE-NODE" \|\| pubkey)`, 32 bytes | **Relationship undefined** | See §5.1 below |
| `sessionId` | 160 bits (20 bytes), 40 hex chars | Not defined in KM-ID-0001 | **No conflict** | Unchanged; orthogonal to identity chain |
| Identity binding to key | Implicit (identityId derived from key) | Explicit (identityRoot in roster, signed) | **Architectural difference** | KM-ID-0001 adds signatures and chain of trust |

### 3.2 Authentication (KM-0002)

| Component | KM-0002 uses | KM-ID-0001 implications | Action |
|-----------|-------------|------------------------|--------|
| `identityId` in AUTH_CHALLENGE | 40 hex chars | Would become 64 hex chars | Update field size |
| `responderIdentityId` in AUTH_CHALLENGE | 40 hex chars | Same | Update field size |
| `identityId` in AUTH_RESPONSE | 40 hex chars | Same | Update field size |
| `identityId` in AUTH_OK | 40 hex chars | Same | Update field size |
| `deviceId` in AUTH_RESPONSE | Opaque, ≤64 ASCII | 64-hex derivation from signing key | **Must change**: opaque deviceId no longer exists |
| Auth transcript | `nonce(16) \|\| timestamp(8) \|\| responderIdentityId(40) \|\| identityId(40)` = 104 bytes | Would become `nonce(16) \|\| timestamp(8) \|\| responderIdentityId(64) \|\| identityId(64)` = 152 bytes | **Transcript changes size** — breaks all existing signatures |
| `publicKey` field | Base64URL | Same (32 bytes → ~44 chars Base64URL) | Unchanged |
| `signature` field | Base64URL | Same (64 bytes → ~88 chars Base64URL) | Unchanged |
| Key type | Ed25519 | Ed25519 | No change |

### 3.3 Relay Protocol (KM-0005)

| Component | KM-0005 uses | KM-ID-0001 implications | Action |
|-----------|-------------|------------------------|--------|
| `nodeId` in NODE_ANNOUNCEMENT | 40 hex chars | KM-ID-0001 derives it as 32 bytes | See §5.1 |
| `publicKey` in NODE_ANNOUNCEMENT | Base64URL | Same key derivation | Unchanged |
| `NodeIdentity` model | `nodeId: IdentityId`, `publicKey`, `nodeName` | KM-ID-0001 adds identityRoot + roster | **Pending decision**: does NodeIdentity embed a DeviceRoster? |
| Node verification | `identityId == first160bits(SHA-256(pubkey))` | Must use KM-ID-0001 derivation | Update verification rule |
| `Message.from` / `Message.to` | `IdentityId` (value class over String) | Same type, different field size | No model change; wire format changes |

### 3.4 Storage and Routing

| Component | Current | KM-ID-0001 impact | Action |
|-----------|---------|-------------------|--------|
| `StoredMessage.senderId` | `IdentityId` | Field size changes | Wire format update |
| `StoredMessage.recipientId` | `IdentityId` | Same | Wire format update |
| `StoredReceipt.to` | `IdentityId` | Same | Wire format update |
| `StoredReceipt.relayNodeId` | `IdentityId` | Same | Wire format update |
| Peer routing table | Keyed by `IdentityId` | `IdentityId` value class unchanged | No model change |

### 3.5 Device Model

| Concept | KM-0002/KM-0005 | KM-ID-0001 | Conflict? | Action |
|---------|----------------|------------|-----------|--------|
| Multi-device | Implicit: each device has separate identity | Explicit: DeviceRoster with identity-owned devices | **Architectural difference** | KM-ID-0001 model supersedes implicit device identities |
| Device identity | Each device is its own identity | Device is not an identity; devices are authorized by identity | **Must adopt new model** | Devices sign as the identity, not as themselves |
| Device key | No separate key model | Per-device signing + agreement keypair | **New concept** | Must be added to auth protocol |
| Device discovery | Not defined | Via DeviceRoster in ContactBundle | **New concept** | Must be added to relay protocol |

### 3.6 KCE-Encoded Documents (KM-ID-0001)

| Document | Identity fields | Size | Wire |
|----------|----------------|------|------|
| DeviceRoster | `identityRoot` (`bstr32`), `identityId` (`bstr32`) | 32 bytes each | KCE (binary) |
| DeviceEntry | `deviceId` (`bstr32`), `signingKey` (`bstr32`), `agreementKey` (`bstr32`) | 32 bytes each | KCE (binary) |
| IdentityRotation | `oldRoot` (`bstr32`), `newRoot` (`bstr32`) | 32 bytes each | KCE (binary) |

These are already implemented and tested against frozen vectors. No change.

---

## 4. Existing Code Threat Assessment

### 4.1 Files That MUST change when identityId size changes

| File | Reason |
|------|--------|
| `km-core/.../model/IdentityId.kt` | The value class wraps `String`; no size enforcement, so no code change, but documentation must change |
| `km-core/.../model/NodeIdentity.kt` | `nodeId` type is `IdentityId` — no code change needed |
| `km-core/.../model/NodeAnnouncement.kt` | Uses `NodeIdentity.identity.nodeId` — no code change needed if IdentityId is the same type |
| `km-core/.../node/RelayClient.kt` | Parses identityId from JSON wire strings — must accept longer strings |
| `km-core/.../codec/JsonRelayControlCodec.kt` | Serializes/deserializes IdentityId fields — no code change if no length check |
| `km-core/.../crypto/SignatureVerifier.kt` | Uses `identityId` in SignedMessage/SignedAck — size change is transparent |
| `app/.../model/Identity.kt` | App-level identity model (RSA, not Ed25519) — **full rewrite pending anyway** |

### 4.2 Files that MUST change due to identity-id derivation change

| File | Reason |
|------|--------|
| `km-core/.../node/NodeRuntimeImpl.kt` | May verify identityId derivation — must use KM-ID-0001 formula |
| `km-core/.../node/RelayClient.kt` | Same |
| `km-id-reference/constants.py` | Already uses KM-ID-0001 — no change needed |
| DHT code in app | Uses `first20bytes(SHA-256(pubkey))` — must update to domain-separated derivation |

### 4.3 Files that pose NO risk

| File | Reason |
|------|--------|
| All KM-ID-0001 layer (KmIds, Kce, DeviceRoster, KmSchemas, KmIdConstants) | Already uses KM-ID-0001 derivation |
| `KmIdsGoldenVectorsTest`, `DeviceRosterGoldenVectorsTest` | Test KM-ID-0001 behavior against frozen manifest |
| `Ed25519Impl` | Only implements crypto, not identity model |
| `NodeCapability`, `NodeEndpoint`, `RelayLimits` | Not identity-related |

---

## 5. Identity Relationship Decisions

### 5.1 `identityId` ↔ `nodeId`

**Question:** Is a Node the same entity as an Identity, or a different abstraction?

**Analysis:**

KM-0005 says a Node IS an identity: its `nodeId` is its `identityId`. The relay
announcement is signed by the node's identity key.

KM-ID-0001 defines `nodeId` as a DISTINCT domain: `SHA-256("KM-NODE-NODE" ||
pubkey)`, which is different from `identityId` even for the same key.

**Options:**

| Option | Description | Pros | Cons |
|--------|------------|------|------|
| A. Node == Identity | `nodeId == identityId` (KM-0005 model) | Simpler; matches current wire protocol | Loses domain separation; node and identity are different concepts |
| B. Node ≠ Identity | Use KM-ID-0001 `nodeId` derivation | Domain separation; node is a transport role, not an identity | Changes wire protocol; current code uses IdentityId for nodeId |

**Recommendation (Option A):** A Node is an incarnation of an Identity in a
transport role. The `nodeId` in the wire protocol (NODE_ANNOUNCEMENT) IS the
identity's `identityId`. The KM-ID-0001 `nodeId` derivation (`"KM-NODE-NODE"`)
is reserved for internal routing tables and DHT, not for wire identity.

**Rationale:** The relay protocol already uses `identityId` as the node's
identity. Changing this to a different derivation would require all existing
nodes to re-announce and peers to re-learn identifiers. The "KM-NODE-NODE"
domain separator exists as a safety measure to prevent identityId/nodeId
collisions in internal data structures, not as a wire requirement.

**Wire implication:** NODE_ANNOUNCEMENT.identity.nodeId remains IdentityId (just
changes size from 40 hex to 64 hex).

### 5.2 `identityId` ↔ `deviceId`

**Question:** Is a device an identity with its own keys, or is it authorized by an identity?

**Analysis:**

This is the cleanest separation in KM-ID-0001. A Device is NOT an identity:
- Devices have their own keypairs (signing + agreement)
- Device identity is derived from the device's signing key as `deviceId`
- Devices are authorized by inclusion in the identity's DeviceRoster
- Devices SIGN as the identity (using the identity root key)

KM-0002 treats deviceId as opaque and not involved in signatures — this model
must be retired.

**Recommendation:** The KM-ID-0001 model is authoritative. `deviceId` is always
cryptographically derived. Opaque device identifiers are removed.

**Wire implication:** AUTH_RESPONSE.deviceId changes from opaque string to
64-hex derivation.

### 5.3 `identityId` Representation in Wire Protocol

**Question:** Should the wire protocol (JSON) use 64-hex or 40-hex identityId?

**Options:**

| Option | Description | Pros | Cons |
|--------|------------|------|------|
| A. Full 32 bytes (64 hex) | Use the full SHA-256 output | No information loss; matches KCE encoding | Changes all existing transcripts; increases message size by ~34% for identity fields |
| B. Truncated 20 bytes (40 hex) | Use first 160 bits | Smaller; backward-compatible with KM-0002 wire sizes | Information loss; two 256-bit security infrastructure using 160-bit identifiers is conceptually inconsistent |

**Recommendation (Option A):** Use the full 32-byte identityId (64 hex) in the
wire protocol.

**Rationale:**
1. KM-ID-0001 already defines identityId as 32 bytes.
2. KCE-encoded documents use the full 32 bytes.
3. Truncation gains only ~48 bytes per message — negligible for modern networks.
4. The KM-0005 `first160bits` rule was a design choice, not a technical limitation.

**Impact:** Authentication transcript changes from 104 bytes to 152 bytes.
Every existing signature over a transcript is invalidated. This is unavoidable.

---

## 6. Migration Path

### 6.1 Steps (in order)

1. **Adopt KM-ID-0001 as the single identity specification.**
   - Mark KM-0002's identity definition as superseded.
   - KM-0005's identity definition as superseded.
   - Both documents must reference KM-ID-0001 for identity types and derivation.

2. **Update `identityId` derivation in KM-0005 §9.2.**
   - Change from `first160bits(SHA-256(pubkey))` to `SHA-256("KM-ID-IDENTITY" ||
     pubkey)`.
   - Update wire size from 40 hex to 64 hex.

3. **Update authentication transcript size in KM-0002 §9.1.**
   - Both `responderIdentityId` and `identityId` change from 40 hex to 64 hex.
   - Transcript changes from 104 bytes to 152 bytes.
   - All existing authentication signatures become invalid — this is a
     version-bump boundary.

4. **Remove opaque `deviceId` from KM-0002 AUTH_RESPONSE.**
   - Replace with KM-ID-0001 deviceId (64 hex derivation from device signing key).

5. **Update `NodeIdentity` to reference KM-ID-0001 identity types.**
   - `nodeId` remains `IdentityId` (value class over String).
   - Add optional `identityRoot` field for roster-verified identity.

6. **Define `identityId ↔ deviceId` binding in auth protocol.**
   - AUTH_RESPONSE must include the device's signing key or its derivation
     proof.

### 6.2 What does NOT change

- `IdentityId` value class (remains `String` wrapper — size is unenforced).
- `NodeAnnouncement` JSON structure (fields remain the same, only some field
  sizes change).
- `Message`, `Ack`, `StoredReceipt` model classes.
- `NodeCapability`, `NodeEndpoint`, `RelayLimits`.
- Ed25519 key format.
- KCE encoding and all KM-ID-0001 documents (already correct).

### 6.3 What requires a wire protocol version bump

| Change | Scope |
|--------|-------|
| identityId in auth messages: 40→64 hex | KM-0002 wire format change |
| identityId in Message.from/to: 40→64 hex | KM-0003 wire format change |
| deviceId format change | AUTH_RESPONSE wire format change |
| Authentication transcript size change | Breaks all existing AUTH_RESPONSE signatures |
| nodeId in NODE_ANNOUNCEMENT: 40→64 hex | KM-0005 wire format change |
| DHT nodeId derivation change | DHT protocol change |

---

## 7. Pending Questions (Deferred)

These are outside the scope of this review and should be resolved in a future
RFC:

1. **Recovery authority**: How does a rotated identity prove ownership of the
   previous identity? (KM-ID-0001 defers this.)

2. **NodeIdentity embedding**: Should `NodeAnnouncement` embed a `DeviceRoster`
   to prove the node's authority? (KM-0005 does not define this.)

3. **Capability bit definition**: How do capabilities interact with device roles?
   (KM-0005 v0.1 marks this as pending.)

4. **DHT migration**: The app-level DHT uses its own `nodeId` derivation. Must
   it use the KM-ID-0001 nodeId? This affects the Android app's DHT protocol.

---

## 8. Implementation Status

### 8.1 KM-ID-0001 Reference (Python → Kotlin)

| Layer | Python | Kotlin | Status |
|-------|--------|--------|--------|
| IdentityId/DeviceId derivation | `identity.py` | `KmIds.kt` | **FROZEN** — G1–G6 byte-identical |
| KCE encoder/decoder | `kce.py` | `Kce.kt` | **FROZEN** — all KCE vectors match |
| Schemas | `schemas.py` | `KmSchemas.kt` | **FROZEN** — closed-world validation |
| DeviceRoster | `roster.py` | `DeviceRoster.kt` | **FROZEN** — G4–G9, 12/13 negative fixtures |
| ContactBundle | `contact.py` | `ContactBundle.kt` | **FROZEN** — G10 byte-identical, wrong-device-binding.cbor |
| IdentityRotation | `rotation.py` | — | **Pending** — no golden vectors exist yet |

### 8.2 Wire Protocol Integration

| Component | KM-0005 §9.2 rule | NodeIdentity.kt | Status |
|-----------|--------------------|-----------------|--------|
| `nodeId == identityId` | §6.3 | `nodeId: IdentityId` (string wrapper) | **No enforcement** — value class accepts any string |
| `identityId == SHA-256("KM-ID-IDENTITY" \|\| publicKey)` | §9.4 rule 1 | Not implemented | **Missing** — no derivation check |
| `signature` verified against `publicKey` | §9.4 rule 2 | Not implemented | **Missing** — no verify() method |
| `publicKey` type | Ed25519, 32 bytes | `ByteArray` | Correct size but unenforced |
| `nodeName` | Optional string | `String?` | Correct |

### 8.3 Required Changes (Updated)

| Priority | Change | Affected RFCs | Ready? |
|----------|--------|---------------|--------|
| **P0** | Adopt KM-ID-0001 identityId derivation | KM-0002 §9.1, KM-0005 §9.2 | **Done** — KM-ID-0001 frozen, KM-0005 v0.3 updated |
| **P0** | Update wire identityId from 40→64 hex | KM-0002, KM-0005, wire codecs | **RFCs updated** — KM-0005 v0.3, KM-0002 v0.5 |
| **P0** | Remove opaque deviceId from auth | KM-0002 §7 | **RFC updated** — KM-0002 v0.5 |
| **P0** | Enforce `identityId` derivation in NodeIdentity | NodeIdentity.kt, KM-0005 §9.4 | **Pending** — §8.2 above |
| **P1** | Update auth transcript size | KM-0002 §9.1 | **RFC updated** — 152 bytes (was 104) |
| **P1** | Define identityId ↔ deviceId binding | KM-0002 (new section) | **Done** — ContactBundle enforces it |
| **P2** | Define nodeId ↔ identityId relation | KM-0005 §6.3 | **Resolved** — nodeId == identityId |
| **P3** | Embed DeviceRoster in NodeAnnouncement | KM-0005 (new section) | **Deferred** — after wire protocol version bump |

---

## 9. Increment 3D: Cross-cutting audit — legacy identity paths

### 9.1 Purpose

With all KM-ID-0001 layers ported and 152 tests passing, find and remove every
code path that constructs or interprets identity using the pre-KM-ID-0001 model:

- `first160bits(SHA-256(pubkey))` — KM-0005 v0.1 derivation
- `first20bytes(SHA-256(pubkey))` — app-level DHT derivation
- `identityId.value.toByteArray()` used as Ed25519 public key
- Direct `NodeIdentity(nodeId, pubKey)` constructor without derivation check
- Any hex string comparison that assumes 40-char identityId
- Any wire deserializer that assumes 40-char identityId on input

### 9.2 Audit checklist

| Category | Search pattern | Files to examine |
|----------|---------------|------------------|
| Old derivation formulas | `first160bits`, `first20bytes`, `toByteArray` on IdentityId | `SignatureVerifierImpl.kt`, app DHT code |
| Direct identity construction | `NodeIdentity(` without `fromKeyPair` | `NodeRuntimeImpl.kt`, tests |
| Wire assumptions | 40-char, 20-byte, `identityId.length` checks | `RelayClient.kt`, `JsonRelayControlCodec.kt` |
| Legacy RSA identity | RSA key types, `SHA-256(pubkey)` without domain separator | App-layer Identity.kt |
| IdentityId as crypto material | `identityId.value.toByteArray()` passed to `ed25519.verify()` | `SignatureVerifierImpl.kt` |
| IdentityId in SignatureVerifier | `identityId` used where `publicKey` is expected | `SignedMessage.identityId`, `SignedAck.identityId` |

### 9.3 Scope boundaries

**IN scope for 3D:**
- Source code audit of `km-core` and `app` (except KM-ID-0001 reference)
- Fix or report every legacy identity path found
- Keep tests passing (152 → should remain ≥152)

**NOT in scope for 3D:**
- New crypto constructions (X3DH, Double Ratchet)
- Wire protocol version bump
- `NodeAnnouncement` JSON codec implementation
- ContactBundle integration with relay signalling

### 9.4 Audit findings

#### 9.4.1 CRITICAL: `SignatureVerifierImpl` — identityId passed as Ed25519 public key

| File | Line | Code |
|------|------|------|
| `km-core/.../crypto/SignatureVerifierImpl.kt` | 5 | `ed25519.verify(signedMessage.identityId.value.toByteArray(), ...)` |
| `km-core/.../crypto/SignatureVerifierImpl.kt` | 9 | `ed25519.verify(signedAck.identityId.value.toByteArray(), ...)` |
| `km-core/.../protocol/AckManagerImpl.kt` | 25 | `ed25519.verify(ack.from.value.toByteArray(), ...)` |

**Root cause:** `SignedMessage.identityId` is a hex string (64 chars), NOT an Ed25519
public key (32 bytes). `.value.toByteArray()` produces 64 ASCII bytes, which is
neither the correct length (32) nor the correct value for a public key.

**Impact:** `Ed25519Impl.verify()` (line 50) calls
`GroupElement(curve, wrongBytes)` which throws an `IllegalArgumentException`;
the `catch (e: Exception) { false }` handler makes verification **silently
return false for every call**. No signed message or ACK is ever verified.

**Fix needed:** `SignedMessage` and `SignedAck` must carry the sender's Ed25519
`publicKey` in addition to (or instead of) `identityId`. The verifier must pass
the actual public key, not the derived identifier.

#### 9.4.2 MEDIUM: `NodeRuntimeImpl` direct `NodeIdentity` construction

| File | Line |
|------|------|
| `km-core/.../model/NodeRuntimeImpl.kt` | 24 |

`NodeRuntimeImpl(override val identity: NodeIdentity)` is constructed externally.
No callers yet use `NodeIdentity.fromKeyPair()`. The runtime never verifies that
`nodeId == SHA-256("KM-ID-IDENTITY" \|\| publicKey)`.

**Fix needed:** `NodeRuntime` interface or factory should accept a `KeyPair` and
derive `NodeIdentity` via `fromKeyPair()`.

#### 9.4.3 STALE: `KM-0000-terminology.md` identityId derivation

| File | Change |
|------|--------|
| `docs/rfc/KM-0000-terminology.md` | **FIXED** — updated from `first160bits(SHA-256(pubkey))` to KM-ID-0001 §6 |

#### 9.4.4 No legacy 40-hex code paths found

After auditing:
- `first160bits` / `first20bytes` — only in documentation (now all updated)
- 40-char hex wire parsing — none found (RelayClient uses ASCII transcript, not hex-length parsing)
- RSA identity import path — exists in `app/.../model/Identity.kt` but is a separate legacy model pending rewrite (not part of this audit)

#### 9.4.5 `RelayClient.computeAuthSignature` — verified correct

This function uses `identityId.value.toByteArray(Charsets.US_ASCII)` to build an
authentication **transcript**, not as a public key. The transcript format defined
in KM-0002 concatenates `nonce + timestamp + responderIdentityId + identityId`
as raw byte sequences, where identityId travels as hex ASCII. This usage is
correct.

### 9.5 3D Summary

| Category | Status |
|----------|--------|
| Source code audit | **COMPLETE** — 3 findings in `km-core`, 0 in `app` (besides legacy RSA model) |
| Critical bugs found | 2 (`SignatureVerifierImpl`, `AckManagerImpl`) — both `identityId` used as `publicKey` |
| Medium issues found | 1 (`NodeRuntimeImpl` direct construction) |
| Stale docs updated | 1 (`KM-0000-terminology.md`) |
| Tests preserved | ✅ 152/152 passing |
| `NodeIdentity.fromKeyPair()` path | Exists but not connected to runtime |

---

## 10. Increment 3F: KM-0002 wire integration planning

### 10.1 Current state

The existing codec layer (`JsonEncoder.kt`, `JsonDecoder.kt`) handles only
`Message` and `Ack`. There is **no authentication codec** — no
`AUTH_CHALLENGE`, `AUTH_RESPONSE`, `AUTH_OK`, or `AUTH_FAIL` serialization.

### 10.2 Wire message inventory (KM-0002 v0.5)

#### AUTH_CHALLENGE (Responder → Initiator)

| Field | KM-0002 type | KM-ID-0001 type | Notes |
|-------|-------------|-----------------|-------|
| `nonce` | Base64URL (128 bits) | Opaque | Unchanged; random value, not a key. Decoded: 16 bytes. |
| `timestamp` | uint64 big-endian | uint64 | Unchanged; used in Auth Transcript. |
| `version` | String | String | Unchanged; protocol version. |
| `responderIdentityId` | 64 hex chars | `bstr32` (32 bytes raw) | Changed from 40 hex; must match `SHA-256("KM-ID-IDENTITY" \|\| responderPubKey)`. In transcript as 64 ASCII bytes. |
| `identityId` in common header | 64 hex chars | `bstr32` | Same format. Header field not used cryptographically. |

#### AUTH_RESPONSE (Initiator → Responder)

| Field | KM-0002 type | KM-ID-0001 type | Notes |
|-------|-------------|-----------------|-------|
| `identityId` | 64 hex chars | `bstr32` | Must match `SHA-256("KM-ID-IDENTITY" \|\| publicKey)`. In transcript as 64 ASCII bytes. |
| `publicKey` | Base64URL (32 bytes) | `bstr32` Ed25519 | Ed25519 public key, 32 bytes raw. |
| `signature` | Base64URL (64 bytes) | `bstr64` | Ed25519 signature over Auth Transcript (152 bytes). |
| `deviceId` | 64 hex chars | `bstr32` | Derived: `SHA-256("KM-ID-DEVICE" \|\| deviceSigningKey)`. Not in transcript. |
| `capabilities` | JSON array | — | Unchanged; feature negotiation. |
| `serialization` | JSON array | — | KM-0007, not KM-ID-0001. |
| `compression` | JSON array | — | KM-0007, not KM-ID-0001. |
| `extensions` | JSON object | — | Optional extensions. |
| `protocolVersion` in header | String | String | Unchanged. |

#### AUTH_OK (Responder → Initiator)

| Field | KM-0002 type | KM-ID-0001 type | Notes |
|-------|-------------|-----------------|-------|
| `identityId` | 64 hex chars | `bstr32` | Responder's identityId in header. |
| `sessionId` | 40 hex chars | Opaque (160 bits) | **NOT** 64 hex. Opaque random session identifier. Unchanged. |
| `serverNonce` | Base64URL (128 bits) | 16 bytes raw | Opaque random value. Unchanged. |
| `signature` | Base64URL (64 bytes) | `bstr64` | Ed25519 over Server Auth Transcript (120 bytes). |
| `errorCode` | String enum | — | Only in AUTH_FAIL. |

#### AUTH_FAIL (Responder → Initiator)

| Field | KM-0002 type | KM-ID-0001 type | Notes |
|-------|-------------|-----------------|-------|
| `errorCode` | String enum | — | From §13 failure codes. |
| `errorMessage` | String | — | Human-readable. |

### 10.3 Transcript definitions

#### Authentication Transcript (152 bytes)

```
nonce(16) || uint64_be(timestamp)(8) || responderIdentityId_ascii(64) || identityId_ascii(64)
```

| Component | Source | KM-ID-0001 derivation check |
|-----------|--------|------------------------------|
| `nonce` | From AUTH_CHALLENGE | None (opaque). |
| `timestamp` | From AUTH_CHALLENGE | None (validated against clock skew). |
| `responderIdentityId` | From AUTH_CHALLENGE | Verified in Server Auth Transcript (step ⑥). |
| `identityId` | From AUTH_RESPONSE | **MUST** equal `SHA-256("KM-ID-IDENTITY" \|\| publicKey)` from AUTH_RESPONSE. |

#### Server Authentication Transcript (120 bytes)

```
sessionId_ascii(40) || serverNonce_bytes(16) || initiatorIdentityId_ascii(64)
```

| Component | Source | KM-ID-0001 derivation check |
|-----------|--------|------------------------------|
| `sessionId` | From AUTH_OK | None (opaque). |
| `serverNonce` | From AUTH_OK | None (opaque). |
| `initiatorIdentityId` | From AUTH_RESPONSE | Already verified; binds OK to initiator. |

### 10.4 Verification rules (from KM-ID-0001 perspective)

For each authentication message, the verifier **MUST** check:

| # | Check | Inputs | Failure code |
|---|-------|--------|-------------|
| V1 | `identityId == SHA-256("KM-ID-IDENTITY" \|\| publicKey)` | `identityId`, `publicKey` (both from AUTH_RESPONSE) | `INVALID_IDENTITY` |
| V2 | `deviceId == SHA-256("KM-ID-DEVICE" \|\| deviceSigningKey)` | `deviceId`, `deviceSigningKey` (from AUTH_RESPONSE or previously known) | `INVALID_IDENTITY` |
| V3 | Auth Transcript signature valid against `publicKey` | Transcript bytes, `signature`, `publicKey` | `INVALID_SIGNATURE` |
| V4 | Server Auth Transcript signature valid against responder `publicKey` | Server transcript bytes, `signature`, responder `publicKey` | `INVALID_AUTH_OK_SIGNATURE` |
| V5 | Timestamp within clock skew window (±30s) | AUTH_CHALLENGE timestamp | `INVALID_TIMESTAMP` |
| V6 | Nonce not reused | AUTH_CHALLENGE nonce | `REPLAY_DETECTED` |

### 10.5 API design constraints (from 3E lessons)

After the identityId/publicKey separation fix, all new authentication APIs
**MUST** respect:

```kotlin
// ✅ CORRECTO
fun verifyTranscript(transcript: ByteArray, signature: Signature, publicKey: ByteArray): Boolean

// ❌ PROHIBIDO — identityId no es clave criptografica
fun verifyTranscript(transcript: ByteArray, signature: Signature, identityId: IdentityId): Boolean
```

| Concept | Use in API |
|---------|-----------|
| `identityId` | Lookup, identification, transcript construction |
| `publicKey` | Ed25519 signature verification |
| `deviceId` | Device binding, roster lookup |
| `deviceSigningKey` | Ed25519 SPK signature verification |
| `nodeId` | Routing (== `identityId`) |

### 10.6 Nonce replay guard (V6)

`REPLAY_DETECTED` (V6) requires storage — the verifier must remember seen nonces
within the clock skew window. To keep `AuthVerifier` stateless and testable:

```kotlin
interface NonceReplayGuard {
    /** Returns true if nonce is fresh (not seen before). */
    fun checkAndRemember(nonce: ByteArray): Boolean
}
```

`AuthVerifier` takes `NonceReplayGuard` and `Clock` as constructor dependencies:

```kotlin
class AuthVerifier(
    private val ed25519: Ed25519,
    private val replayGuard: NonceReplayGuard,
    private val clock: () -> Long = System::currentTimeMillis,
)
```

This keeps V1–V5 as pure functions while V6 delegates to an injected guard.
The actual session state (`AuthSession`) remains deferred to a later increment.

### 10.7 Base64URL (RFC 4648 §5)

**Must be implemented before any codec.** Current `JsonEncoder` uses standard
Base64 with padding. KM-0002 §16.1 requires Base64URL **without padding**:

| Rule | Standard Base64 | Base64URL (RFC 4648 §5) |
|------|----------------|-------------------------|
| Alphabet | `A-Za-z0-9+/` | `A-Za-z0-9-_` |
| Padding | `=` required | **MUST NOT** appear on wire |
| Decoder | Accepts padding | **MUST reject** padding |

### 10.8 Codec plan

| Component | Status | Action |
|-----------|--------|--------|
| `AuthChallengeCodec` | **Missing** | Serialize/deserialize AUTH_CHALLENGE JSON. |
| `AuthResponseCodec` | **Missing** | Serialize/deserialize AUTH_RESPONSE JSON. Verify V1, V2, V3. |
| `AuthOkCodec` | **Missing** | Serialize/deserialize AUTH_OK JSON. Verify V4. |
| `AuthFailCodec` | **Missing** | Serialize/deserialize AUTH_FAIL JSON. |
| `AuthSession` | **Missing** | Holds challenge state, nonce reuse tracking, clock skew. |
| `TranscriptBuilder` | **Missing** | Builds 152-byte and 120-byte transcripts deterministically. |
| `Base64URL` codec | **Missing** | KM-0002 §16.1 requires Base64URL **without padding**. Current encoder uses standard Base64 **with** padding. **Must fix** before wire. |

### 10.9 Implementation order for 3F

```
3F.1  Base64URL encoder/decoder (RFC 4648 §5, strict)
      ↓
3F.2  TranscriptBuilder (152 + 120 bytes, pure)
      ↓
3F.3  AuthVerifier V1–V6 (stateless, Clock + NonceReplayGuard injected)
      ↓
3F.4  JSON codecs (AUTH_CHALLENGE, RESPONSE, OK, FAIL)
      ↓
3F.5  Golden vectors G14–G19 (valid, deterministic)
      ↓
3F.6  Negative vectors I1–I5 (one cause per fixture)
      ↓
3F.7  Mutation testing
      ↓
3F.8  KM-0003 integration (stateless binding)
```

### 10.10 Architectural invariants (post-3E)

The following rule is now an **architectural invariant** inherited from the 3E
identityId/publicKey fix:

> `publicKey` is the only value permitted as the Ed25519 verification key.
> `identityId` and `deviceId` are identifiers and MUST NOT be interpreted as
> public keys.

This invariant covers:

| Rule | Enforced by |
|------|-------------|
| `Ed25519Impl.verify()` requires `publicKey.size == 32` | `require()` guard in `Ed25519Impl.kt` |
| `SignedMessage`/`SignedAck` carry `publicKey` separately from `identityId` | Model definition in `SignatureVerifier.kt` |
| `AckManagerImpl` uses injected `senderPublicKey` resolver | DI pattern in `AckManagerImpl.kt` |
| AuthVerifier receives `publicKey` for signature checks | API design (V3, V4) |
| `identityId` appears in transcripts as 64 ASCII bytes, NOT as a key | Transcript format (§10.3) |
| `deviceId` is a roster lookup key, NOT a public key | Device roster verification path |

### 10.11 Golden vector plan

| ID | Message | Variation | Expected |
|----|---------|-----------|----------|
| G14 | AUTH_CHALLENGE | Valid | Parses, transcripts build |
| G15 | AUTH_RESPONSE | Valid | Verifies V1–V3 |
| G16 | AUTH_OK | Valid | Verifies V4 |
| G17 | AUTH_FAIL | Valid | Parses error codes |
| G18 | Auth Transcript | Valid | 152 bytes, deterministic |
| G19 | Server Auth Transcript | Valid | 120 bytes, deterministic |
| I1 | AUTH_RESPONSE | Wrong identityId | `INVALID_IDENTITY` |
| I2 | AUTH_RESPONSE | Wrong publicKey on transcript | `INVALID_SIGNATURE` |
| I3 | AUTH_RESPONSE | Wrong deviceId | `INVALID_IDENTITY` |
| I4 | AUTH_OK | Wrong server signature | `INVALID_AUTH_OK_SIGNATURE` |
| I5 | AUTH_CHALLENGE | Stale timestamp | `INVALID_TIMESTAMP` |

These vectors will live alongside the existing G1–G10 in `km-id-reference/`.
# Exploration: framework-e2e-crypto-wiring — Checkpoint A (Build/Identity)

## Current State

Two hard blockers prevent Android from participating in E2E crypto wiring:

### F6: Android Room/Kotlin 2.2.10 compatibility
- **km-core** compiles with **Kotlin 2.2.10** → emits metadata version **2.2.0**
- **km-android** uses **Kotlin 2.1.20** + **Room 2.7.0-alpha02** (shaded `kotlinx-metadata-jvm` capped at 2.1.0)
- `:app:kaptDebugKotlin` fails with: `Provided Metadata instance has version 2.2.0, while maximum supported version is 2.1.0`

### F5: IdentityId 40-hex → 64-hex mismatch
- **Android** derives: `IdentityId(bytesToHex(keyPair.publicKey.take(20).toByteArray()))` → **40 hex** (20 bytes)
- **Daemon** derives (KM-ID-0001 §6): `SHA-256("KM-ID-IDENTITY" || publicKey)` → **64 hex** (32 bytes)
- **Auth transcript** (152 bytes) layout: `16 nonce + 8 ts + 64 responderId + 64 identityId`
- **40-hex identityId** is rejected at V1 identity layer (`INVALID_IDENTITY`) and cannot even form a transcript

## Affected Areas

| File | Why Affected |
|------|--------------|
| `KemaMada-km-android/gradle/libs.versions.toml` | Room version pin (F6) |
| `KemaMada-km-android/app/build.gradle.kts` | packaging excludes for Room 2.7+ (F6) |
| `KemaMada-km-android/app/src/main/java/com/example/keymessage/AppContainer.kt` | `localIdentity` derivation (F5) |
| `KemaMada-km-android/app/src/main/java/com/example/keymessage/ui/MainActivity.kt` | hardcoded 40-hex placeholder (F5) |

## Approaches

### F6: Room/Kotlin metadata skew

| Approach | Pros | Cons | Complexity |
|----------|------|------|------------|
| **1. Upgrade Room to ≥ 2.7.0** (proven) | Fixes metadata cap, keeps km-core at 2.2.10, zero km-core changes | Room 2.7+ exposes a second blocker: duplicate `META-INF/versions/9/OSGI-INF/MANIFEST.MF` from BouncyCastle + jspecify (fixed with packaging exclude) | **Low** — 2-line version bump + 1-line packaging exclude |
| 2. Downgrade km-core Kotlin to 2.1.x | Keeps Room 2.6.1 | Breaks km-core baseline (1188 tests); km-core uses 2.2.10 features; upstream consumers expect 2.2.10 | **High** — regression cascade |
| 3. Switch to KSP instead of KAPT | Better metadata support long-term | Requires KSP setup, KSP version locked to Kotlin version, migration effort | **Medium** — architectural shift |
| 4. Compile km-core with `-Xjvm-target=11 -language-version 2.1` for Android | Keeps Room 2.6.1 | Requires composite build or separate publication; fragments the artifact | **Medium** — build complexity |

**Room 2.7.0 is the exact floor**: 2.6.1 fails (caps at 2.1.0), 2.7.0-alpha02 fails (same shaded lib), 2.7.0 succeeds (drops shaded lib, uses KSP2).

### F5: IdentityId derivation alignment

| Approach | Pros | Cons | Complexity |
|----------|------|------|------------|
| **1. Use `KmIds.identityId(publicKey)` in Android** (proven) | Aligns with daemon, spec-compliant, 64-hex, transcript passes, zero behavior change elsewhere | Requires `KmIds` import; transitive dep (km-core already published) | **Low** — 1-line derivation swap + relayIdentityId literal update |
| 2. Make daemon accept 40-hex | Zero Android change | Violates KM-ID-0001 §6; breaks transcript length invariant; requires daemon code change | **High** — protocol divergence |
| 3. Add compat shim in km-core (accept both) | Backward compatible | Perpetuates bug; transcript would still be length-mismatched; two code paths | **Medium** — technical debt |

**`KmIds.identityId` is the canonical derivation** — already used by daemon (`RelayServer.deriveIdentityId`), desktop (`IdentityKeystore.deriveIdentityId`), and spec reference (`km-id-reference/identity.py`). Android's `take(20)` was a local invention.

## Recommendation

### F6: Upgrade Room to 2.7.2 (current stable) + packaging exclude
```toml
# gradle/libs.versions.toml
room = "2.7.2"
```
```kotlin
// app/build.gradle.kts (packaging block)
excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
```
- **Result**: `:app:assembleDebug` **SUCCESS**, all 45 Android unit tests **PASS**
- **No km-core changes**, no Kotlin downgrade, no KSP migration needed

### F5: Replace `take(20)` with `KmIds.identityId`
```kotlin
// AppContainer.kt
import com.km.identity.KmIds

val localIdentity = IdentityId(bytesToHex(KmIds.identityId(keyPair.publicKey)))
val relayIdentityId = IdentityId("cadd3afc301de94d9161ce7834b8c6b6396d49dd529a830269dfcc09c4cda590")
```
```kotlin
// MainActivity.kt (UI placeholder only)
val peerId = remember { IdentityId("b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0") }
```
- **Result**: IdentityId is **64 hex**, auth transcript constructs correctly, `AuthVerifier.verifyChallengeResponse` returns **null (OK)** vs `INVALID_IDENTITY` for 40-hex
- **No daemon changes**, no spec changes, DHT `nodeId` (separate 20-byte concept) untouched

## Risks

- **Room 2.7.2 packaging exclude**: The `OSGI-INF/MANIFEST.MF` conflict is a known Room 2.7+ artifact issue (bcprov + jspecify). The exclude is surgical and does not affect runtime.
- **KmIds transitive dependency**: km-android already depends on `com.km:km-core`; `KmIds` is public API. No new dependency.
- **Relay identity literal**: Updated to the actual daemon identity (`cadd3afc...`). This is correct — the placeholder was never real.
- **DHT `nodeId` unchanged**: It is a **different identifier space** (20-byte SHA-256 of stringified public key for Kademlia routing). Not the protocol `IdentityId`. No conflict.

## Ready for Proposal

**Yes** — Both blockers have empirically proven fixes with:
- Full Android build success (`assembleDebug`)
- All 45 Android unit tests passing
- km-core 1096 tests intact
- km-daemon 2 tests intact
- km-webrtc 92 tests intact
- AuthVerifier behavioural proof: 64-hex → `null` (OK), 40-hex → `INVALID_IDENTITY` + transcript rejection

Next phase: `sdd-propose` for Checkpoint A.
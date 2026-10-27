# Archive Report: contact-km

## Status
Completed

## Executive Summary
Archived the `contact-km` change that introduced the third contact carrier (`.km` file format) alongside QR code and text-sharing capabilities. All three channels (QR, `.km` file share, text paste) are now equivalent options. The change was implemented entirely in `app/` with no modifications to `km-core`.

## Artifacts (topic_keys written)
- `sdd/contact-km/archive-report`

## Specs Synced
| Domain | Action | Details |
|--------|--------|---------|
| `app/` | New carrier `.km` added | Third contact carrier alongside QR and text sharing |

## Archive Contents
- proposal.md ✅ (traceable to issue #297)
- specs/ ✅ (traceable to issue #298)
- design.md ✅ (traceable to issue #299)
- tasks.md ✅ (traceable to issue #300)
- verify-report ✅ (traceable to issue #302)
- archive-report ✅ (this document, traceable to issue #303)

## Verification Results
- **PASS**: 11/11 codec compliance tests
- **Two explicit caveats registered:**
  - **Mutaciones independientes del verificador: NO evidenciadas** — el verificador afirmó haberlas aplicado/revertido pero sin recuento de tests ni `exit` observados (describió fallos "esperados"). La evidencia de sensibilidad válida son las **3 mutaciones del implementador** (obs #301, con tests que caen y md5 idéntico). Es una carencia de la fase verify, no del código.
  - **ADB 6.2–6.4 (export/import real por SAF): NO ejecutadas** — SAF no es automatizable por `input` de ADB; queda como **prueba manual del usuario**.

## Manual User Pendings
1. **QR**: escaneo con cámara entre dispositivos (`pm clear` del emulador previo para identidades distintas).
2. **.km**: COMPARTIR → `GUARDAR .KM` (elegir destino en SAF) y en el otro dispositivo IMPORTAR → `IMPORTAR FICHERO` (elegir el `.km`).

## Risks Aceptados
- SAF sin cobertura JVM (capa Android mínima)
- `app/` fuera de git (rollback md5 manual)
- flake preexistente zxing ~4.5% (obs #293) ajeno
- el `.km` guarda el bundle de 0 OPK (coherente con el QR)

## Estado Git
`0eaa079`, sin commits, `app/` sin versionar.

## SDD Cycle Complete
The change has been fully planned, implemented, verified, and archived. Ready for the next change.
# KM-TEST-0001 — Protocol Conformance & Integration Tests

| Metadata | Valor |
|----------|-------|
| **Estado** | Draft (Frozen) |
| **Versión** | 0.3 |
| **Nota** | Congelado. Nuevos tests se añadirán en versiones posteriores. |
| **Fecha** | 2026-07-25 |
| **Dependencias** | KM-0002, KM-0003, KM-0004 |

---

## 1. Introduction

Este documento define dos suites de pruebas para el protocolo KeyMessage:

- **Conformance Tests**: verifican requisitos normativos (MUST/SHOULD/MAY). Se ejecutan contra la implementación del protocolo en aislamiento, con mocks de relay y almacenamiento.
- **Integration Tests**: verifican el comportamiento del sistema completo con clientes reales, relay real y condiciones de red adversas.

---

## 2. Test Environment

### 2.1 Conformance Tests

| Requisito | Especificación |
|-----------|----------------|
| **Transport** | Mock (in-process) |
| **Relay** | Mock |
| **Client A** | Implementación del protocolo bajo prueba |
| **Client B** | Implementación del protocolo bajo prueba |
| **Storage** | In-memory (con fallo simulado donde se indique) |
| **Claves** | Ed25519 generadas para cada test |

### 2.2 Integration Tests

| Requisito | Especificación |
|-----------|----------------|
| **Transport** | WebSocket local (ws://127.0.0.1:PORT) |
| **Relay** | Implementación conforme a KM-0003 |
| **Client A** | Implementación bajo prueba (emisor) |
| **Client B** | Implementación bajo prueba (receptor) |
| **Claves** | Ed25519 generadas para cada test |
| **Message ID** | UUIDv7 conforme a RFC 9562 |

### 2.3 Field Definitions

Cada test incluye los campos:

| Campo | Descripción |
|-------|-------------|
| **Priority** | `MUST` — requisito obligatorio. `SHOULD` — recomendado. `MAY` — opcional. |
| **Automation** | `YES` — automatizable en CI. `MANUAL` — requiere intervención humana. |
| **Reference** | Sección normativa del RFC correspondiente. |
| **Suite** | `CONFORMANCE` o `INTEGRATION` |
| **Preconditions** | Estado requerido antes de ejecutar el test. |
| **Procedure** | Pasos a seguir. |
| **Expected result** | Comportamiento observable. |
| **Pass criteria** | Condición que determina si el test pasa. |

---

## Part I — Conformance Tests

---

## 3. Message Lifecycle

### TEST-LIFE-001 — CREATED → SENT

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §8 |
| **Preconditions** | Client A autenticado, Client B ONLINE. |
| **Procedure** | Client A crea un MESSAGE y lo envía a Client B. |
| **Expected result** | El mensaje pasa por CREATED → SENDING → SENT. |
| **Pass criteria** | El estado observable en Client A es SENT. |

### TEST-LIFE-002 — SENT → DELIVERED

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §8, §9.2 |
| **Preconditions** | Client A y B autenticados, B ONLINE. |
| **Procedure** | Client A envía MESSAGE. Client B recibe, persiste y envía ACK. |
| **Expected result** | Client A recibe ACK y marca mensaje como DELIVERED. |
| **Pass criteria** | Estado en Client A: DELIVERED. |

### TEST-LIFE-003 — SENT → EXPIRED (receptor nunca online)

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §8, §11.6 |
| **Preconditions** | Client A autenticado. Client B nunca se conecta. |
| **Procedure** | Client A envía MESSAGE. El mensaje entra en Offline Queue. La política de expiración de la implementación se alcanza. |
| **Expected result** | El mensaje pasa a EXPIRED. |
| **Pass criteria** | Estado EXPIRED. No se recibió ACK. |

### TEST-LIFE-004 — FAILED por MESSAGE_TOO_LARGE

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §8, §16 |
| **Preconditions** | Client A autenticado. |
| **Procedure** | Client A crea un MESSAGE cuyo JSON serializado excede 64 KiB. |
| **Expected result** | El mensaje pasa a FAILED. El receptor responde MESSAGE_TOO_LARGE. |
| **Pass criteria** | Estado FAILED. No se intentó retransmisión. |

### TEST-LIFE-005 — FAILED por INVALID_MESSAGE

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §8, §16 |
| **Preconditions** | Client B autenticado. |
| **Procedure** | Enviar un MESSAGE sin campo `payload`. |
| **Expected result** | Client B responde INVALID_MESSAGE. Emisor marca FAILED. |
| **Pass criteria** | Estado FAILED en el emisor. |

---

## 4. ACK Protocol

### TEST-ACK-001 — ACK después de persistencia

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §9.2 |
| **Preconditions** | Client A y B autenticados, B ONLINE. Almacenamiento disponible. |
| **Procedure** | Client A envía MESSAGE. Client B persiste en almacenamiento durable antes de emitir ACK. |
| **Expected result** | ACK emitido después de la persistencia. Mensaje en almacén de B. |
| **Pass criteria** | ACK recibido. Mensaje recuperable desde B tras reinicio simulado. |

### TEST-ACK-002 — Sin ACK si falla la persistencia

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §9.2 |
| **Preconditions** | Client A y B autenticados. Almacenamiento de B simulado como no disponible. |
| **Procedure** | Client A envía MESSAGE. B intenta persistir y falla. |
| **Expected result** | Client B NO envía ACK. Client A retransmite al expirar el temporizador. |
| **Pass criteria** | Sin ACK. Retransmisión observada. |

### TEST-ACK-003 — ACK firmado

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §11.5, §18.4 |
| **Preconditions** | Client A y B autenticados. |
| **Procedure** | Client A envía MESSAGE. B responde con ACK. |
| **Expected result** | ACK contiene `signature` verificable con la clave pública de B. |
| **Pass criteria** | `Ed25519.verify(B.publicKey, signature, from || to || originalMessageId || "DELIVERED" || timestamp)` retorna verdadero. |

### TEST-ACK-004 — ACK perdido

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §11.6 |
| **Preconditions** | Client A y B autenticados. El ACK de B se descarta en la red (simulado). |
| **Procedure** | Client A envía MESSAGE. B persiste y envía ACK (descartado). Timeout de A expira. |
| **Expected result** | A retransmite MESSAGE. B detecta duplicado, reenvía ACK. A marca DELIVERED. |
| **Pass criteria** | Una sola persistencia en B. Estado DELIVERED en A. |

### TEST-ACK-005 — ACK duplicado ignorado

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §11.4 |
| **Preconditions** | Mensaje ya en estado DELIVERED en A. |
| **Procedure** | Inyectar un ACK duplicado (mismo originalMessageId) en A. |
| **Expected result** | Client A ignora el ACK duplicado silenciosamente. Estado permanece DELIVERED. |
| **Pass criteria** | Sin cambio de estado. Sin error. |

### TEST-ACK-006 — ACK firmado por otro Peer

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §11.5, §18.4 |
| **Preconditions** | Client A, B, C autenticados. C conoce originalMessageId de un mensaje entre A y B. |
| **Procedure** | C genera un ACK firmado con su propia clave (from: C, to: A) para originalMessageId de A↔B. |
| **Expected result** | A rechaza el ACK porque `from` en la firma no coincide con el receptor esperado. |
| **Pass criteria** | Estado del mensaje no cambia. |

### TEST-ACK-007 — ACK con firma truncada

| Campo | Valor |
|-------|-------|
| **Priority** | SHOULD |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §18.4 |
| **Preconditions** | Client A autenticado. |
| **Procedure** | Inyectar un ACK con campo `signature` truncado (menos de 64 bytes). |
| **Expected result** | A rechaza el ACK por firma inválida. |
| **Pass criteria** | ACK ignorado. Estado no cambia. |

### TEST-ACK-008 — ACK con firma modificada

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §18.4 |
| **Preconditions** | Client A autenticado. |
| **Procedure** | Capturar un ACK válido, modificar un byte de la firma, reenviarlo. |
| **Expected result** | A rechaza el ACK por firma inválida. |
| **Pass criteria** | ACK ignorado. Estado no cambia. |

---

## 5. Duplicate Detection

### TEST-DUP-001 — Mismo mensaje dos veces

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §13.1 |
| **Preconditions** | Client A y B autenticados. |
| **Procedure** | Client A envía el mismo MESSAGE (mismo messageId, mismo payload) dos veces. |
| **Expected result** | Client B almacena una sola vez. Envía dos ACK. Un solo mensaje visible. |
| **Pass criteria** | Una entrada en almacén de B. Dos ACK emitidos. |

### TEST-DUP-002 — Retransmisión con payload diferente

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §13.1 |
| **Preconditions** | Client A y B autenticados. |
| **Procedure** | A envía MESSAGE (id: M1, payload: P1). Retransmite M1 con payload P2. |
| **Expected result** | B ignora el payload duplicado. Almacena P1. Reenvía ACK. |
| **Pass criteria** | Payload almacenado es P1, no P2. |

### TEST-DUP-003 — MESSAGE con firma de clave incorrecta

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §18.3 |
| **Preconditions** | Client A y B autenticados. Atacante conoce clave pública de A. |
| **Procedure** | Atacante firma un MESSAGE con su propia clave privada, usando identityId de A. |
| **Expected result** | B rechaza el mensaje porque la firma no corresponde a la clave pública de A. |
| **Pass criteria** | Mensaje rechazado. No se persiste. |

### TEST-DUP-004 — MESSAGE con firma modificada

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §18.3 |
| **Preconditions** | Client A y B autenticados. |
| **Procedure** | Capturar un MESSAGE válido de A, modificar un byte de la firma, reenviarlo a B. |
| **Expected result** | B rechaza el mensaje por firma inválida. |
| **Pass criteria** | Mensaje rechazado. No se persiste. |

---

## 6. Ordering

### TEST-ORD-001 — Mensajes en orden

| Campo | Valor |
|-------|-------|
| **Priority** | SHOULD |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §12.1 |
| **Preconditions** | Client A y B autenticados. |
| **Procedure** | A envía M1, luego M2, luego M3. Todos llegan en orden. |
| **Expected result** | B presenta M1, M2, M3 en ese orden. |
| **Pass criteria** | Orden de presentación coincide con orden de envío. |

### TEST-ORD-002 — Mensajes fuera de orden

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §12.3 |
| **Preconditions** | Client A y B autenticados. |
| **Procedure** | A envía M1, M2, M3. La red los entrega como M2, M3, M1. |
| **Expected result** | B persiste y envía ACK por cada uno al recibirlo. Presentación final muestra M1, M2, M3. |
| **Pass criteria** | Todos en almacén persistente. Todos los ACK emitidos. UI ordenada. |

### TEST-ORD-003 — Mensaje perdido no bloquea

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §12.3 |
| **Preconditions** | Client A y B autenticados. |
| **Procedure** | A envía M1, M2, M3, M4. M1 se pierde en la red. Llegan M2, M3, M4. |
| **Expected result** | B persiste y envía ACK por M2, M3, M4 inmediatamente. UI muestra M2, M3, M4 con marcador de hueco para M1. |
| **Pass criteria** | Sin bloqueo. ACK emitidos. Hueco visual para M1. |

---

## 7. Offline Queue

### TEST-OFF-001 — Receptor offline

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §14.2 |
| **Preconditions** | Client A autenticado. Client B desconectado. |
| **Procedure** | Client A envía MESSAGE. Relay responde PEER_NOT_FOUND. |
| **Expected result** | Mensaje entra en Offline Queue de A con estado QUEUED. |
| **Pass criteria** | Estado QUEUED. Mensaje en cola. |

### TEST-OFF-002 — Entrega al reconectar

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §14.5 |
| **Preconditions** | Client A tiene mensaje en Offline Queue. |
| **Procedure** | Client B se conecta. Relay emite PEER_ONLINE. |
| **Expected result** | Client A intenta entregar el mensaje pendiente. |
| **Pass criteria** | Mensaje enviado. Si B responde ACK, pasa a DELIVERED. |

### TEST-OFF-003 — Expiración en cola

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §14.4 |
| **Preconditions** | Client A tiene mensaje en Offline Queue. La política de expiración de la implementación se cumple. |
| **Procedure** | Esperar a que la implementación expire el mensaje. |
| **Expected result** | Mensaje pasa a EXPIRED. Se descarta de la cola. |
| **Pass criteria** | Estado EXPIRED. Cola vacía para ese destino. |

---

## 8. Failure Modes

### TEST-FAIL-001 — MESSAGE_TOO_LARGE

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §16 |
| **Preconditions** | Client A autenticado. |
| **Procedure** | Enviar MESSAGE serializado > 64 KiB. |
| **Expected result** | Relay o receptor responde MESSAGE_TOO_LARGE. |
| **Pass criteria** | Código de error correcto. No se persiste nada. |

### TEST-FAIL-002 — PEER_NOT_FOUND

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0003 §15 |
| **Preconditions** | Client A autenticado. Client B no está ONLINE. |
| **Procedure** | A envía MESSAGE a B. |
| **Expected result** | Relay responde PEER_NOT_FOUND. |
| **Pass criteria** | Código de error correcto. |

### TEST-FAIL-003 — INVALID_MESSAGE

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §16 |
| **Preconditions** | Client B autenticado. |
| **Procedure** | Enviar MESSAGE sin campo `payload`. |
| **Expected result** | B responde INVALID_MESSAGE. |
| **Pass criteria** | Código de error correcto. |

### TEST-FAIL-004 — AUTH_FAILED

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0002 §13, KM-0003 §15 |
| **Preconditions** | Cliente con identityId inválido o clave incorrecta. |
| **Procedure** | Intentar autenticación contra el Relay. |
| **Expected result** | Relay responde AUTH_FAILED. |
| **Pass criteria** | Conexión rechazada. |

---

## 9. Security

### TEST-SEC-001 — Replay de MESSAGE

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §17.1 |
| **Preconditions** | Client A y B autenticados. |
| **Procedure** | Capturar un MESSAGE válido y reenviarlo más tarde (mismo messageId, mismo payload). |
| **Expected result** | B detecta duplicado, ignora payload, reenvía ACK. |
| **Pass criteria** | Una sola entrada en almacén. |

### TEST-SEC-002 — ACK falsificado sin firma

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §11.5, §18.4 |
| **Preconditions** | Client A espera ACK para M1. |
| **Procedure** | Inyectar un ACK con firma inválida o ausente. |
| **Expected result** | A rechaza el ACK. Mensaje no pasa a DELIVERED. |
| **Pass criteria** | Estado no cambia. |

### TEST-SEC-003 — Flooding de mensajes

| Campo | Valor |
|-------|-------|
| **Priority** | SHOULD |
| **Automation** | YES |
| **Suite** | CONFORMANCE |
| **Reference** | KM-0004 §17.3 |
| **Preconditions** | Client B autenticado. |
| **Procedure** | A envía 100 mensajes en 10 segundos. |
| **Expected result** | B aplica límite de tasa (>60/min). Los adicionales son rechazados con RATE_LIMITED. |
| **Pass criteria** | Código de error correcto. No se persisten los rechazados. |

---

## Part II — Integration Tests

---

## 10. Persistence & Recovery

### TEST-PERSIST-001 — Persistencia tras reinicio del receptor

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | MANUAL |
| **Suite** | INTEGRATION |
| **Reference** | KM-0004 §9.2 |
| **Preconditions** | Client A y B autenticados. B ONLINE. |
| **Procedure** | A envía MESSAGE. B lo recibe, persiste y envía ACK. Se cierra la app de B inmediatamente. Se vuelve a abrir. |
| **Expected result** | El mensaje persiste en B tras el reinicio. No aparece duplicado. B no necesita re-ACK. |
| **Pass criteria** | Mensaje único visible en B. Estado DELIVERED en A. |

### TEST-PERSIST-002 — Offline Queue tras reinicio del emisor

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | MANUAL |
| **Suite** | INTEGRATION |
| **Reference** | KM-0004 §14.4 |
| **Preconditions** | Client A autenticado. B desconectado. A tiene un mensaje en Offline Queue. |
| **Procedure** | A envía MESSAGE. Entra en QUEUED. Se cierra la app de A. Se vuelve a abrir. |
| **Expected result** | La Offline Queue se recupera. El mensaje permanece en QUEUED. Cuando B se conecta, A reintenta la entrega. |
| **Pass criteria** | Estado QUEUED tras reinicio. Entrega exitosa cuando B se conecta. |

---

## 11. Interoperability

### TEST-INTEROP-001 — Misma implementación, misma versión

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | INTEGRATION |
| **Reference** | KM-0004 |
| **Preconditions** | Dos instancias de la misma implementación (v1.0). |
| **Procedure** | Intercambian 10 mensajes en secuencia. |
| **Expected result** | Todos llegan en orden. Todos los ACK se reciben. Sin duplicados. |
| **Pass criteria** | 10/10 DELIVERED. |

### TEST-INTEROP-002 — Misma implementación, versiones distintas

| Campo | Valor |
|-------|-------|
| **Priority** | SHOULD |
| **Automation** | YES |
| **Suite** | INTEGRATION |
| **Reference** | KM-0004 |
| **Preconditions** | Implementación A v1.0 ↔ Implementación A v1.1. |
| **Procedure** | Intercambian 10 mensajes en secuencia. |
| **Expected result** | Todos llegan. Todos los ACK se reciben. |
| **Pass criteria** | 10/10 DELIVERED. Sin errores de protocolo. |

### TEST-INTEROP-003 — Implementaciones distintas

| Campo | Valor |
|-------|-------|
| **Priority** | MUST |
| **Automation** | YES |
| **Suite** | INTEGRATION |
| **Reference** | KM-0004 |
| **Preconditions** | Dos implementaciones independientes y conformes. |
| **Procedure** | Intercambian 10 mensajes en secuencia. |
| **Expected result** | Todos llegan. Todos los ACK se reciben. Wire protocol compatible. |
| **Pass criteria** | 10/10 DELIVERED. Sin errores de parsing. |

---

## Appendix A — Coverage Matrix

| RFC § | Requisito | Test | Suite |
|-------|-----------|------|-------|
| KM-0004 §8 | Lifecycle CREATED→SENT | TEST-LIFE-001 | CONFORMANCE |
| KM-0004 §8 | Lifecycle SENT→DELIVERED | TEST-LIFE-002 | CONFORMANCE |
| KM-0004 §8 | Lifecycle →EXPIRED | TEST-LIFE-003 | CONFORMANCE |
| KM-0004 §8 | Lifecycle →FAILED | TEST-LIFE-004, TEST-LIFE-005 | CONFORMANCE |
| KM-0004 §9.2 | ACK tras persistencia | TEST-ACK-001 | CONFORMANCE |
| KM-0004 §9.2 | No ACK si falla persistencia | TEST-ACK-002 | CONFORMANCE |
| KM-0004 §9.2 | Persistencia tras reinicio | TEST-PERSIST-001 | INTEGRATION |
| KM-0004 §11.4 | ACK duplicado ignorado | TEST-ACK-005 | CONFORMANCE |
| KM-0004 §11.5 | ACK firmado | TEST-ACK-003, TEST-ACK-006 | CONFORMANCE |
| KM-0004 §11.6 | ACK perdido → retransmisión | TEST-ACK-004 | CONFORMANCE |
| KM-0004 §12.1 | Orden por Message ID | TEST-ORD-001 | CONFORMANCE |
| KM-0004 §12.3 | Fuera de orden | TEST-ORD-002 | CONFORMANCE |
| KM-0004 §12.3 | Sin HoL blocking | TEST-ORD-003 | CONFORMANCE |
| KM-0004 §13.1 | Duplicado → ignorar payload | TEST-DUP-001, TEST-DUP-002 | CONFORMANCE |
| KM-0004 §14.2 | Offline Queue entrada | TEST-OFF-001 | CONFORMANCE |
| KM-0004 §14.4 | Offline Queue expiración | TEST-OFF-003 | CONFORMANCE |
| KM-0004 §14.4 | Offline Queue tras reinicio | TEST-PERSIST-002 | INTEGRATION |
| KM-0004 §14.5 | Offline Queue salida | TEST-OFF-002 | CONFORMANCE |
| KM-0004 §16 | MESSAGE_TOO_LARGE | TEST-FAIL-001 | CONFORMANCE |
| KM-0004 §16 | INVALID_MESSAGE | TEST-FAIL-003 | CONFORMANCE |
| KM-0004 §18.3 | Firma MESSAGE | TEST-DUP-003, TEST-DUP-004 | CONFORMANCE |
| KM-0004 §18.4 | Firma ACK | TEST-ACK-003, TEST-ACK-007, TEST-ACK-008 | CONFORMANCE |
| KM-0003 §15 | PEER_NOT_FOUND | TEST-FAIL-002 | CONFORMANCE |
| KM-0002 §13 | AUTH_FAILED | TEST-FAIL-004 | CONFORMANCE |

---

## Appendix B — Test Template

```
TEST-XXX-NNN

Priority: MUST | SHOULD | MAY
Automation: YES | MANUAL
Suite: CONFORMANCE | INTEGRATION
Reference:
KM-0004 §X.Y

Preconditions:

Procedure:

Expected result:

Pass criteria:
```

---

## Appendix C — Version History

| Versión | Fecha | Cambios |
|---------|-------|---------|
| 0.1 | 2026-07-25 | Documento inicial — 25 casos de prueba. |
| 0.2 | 2026-07-25 | Añadidos Priority, Automation, tests negativos de firma, interop entre versiones, coverage matrix. |
| 0.3 | 2026-07-25 | Separación Conformance/Integration. Añadidos TEST-PERSIST-001, TEST-PERSIST-002. 29 tests total (25 conformance, 4 integration). |

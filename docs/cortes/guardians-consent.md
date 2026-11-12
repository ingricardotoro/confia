# Corte: consentimiento de comunicaciones (`guardians`)

Fase F1, entregable 5. Rama `feat/guardians-consent`, apilada sobre `feat/guardians-registry` (PR #307).
Política: `docs/08-datos-privacidad-y-retencion.md` §8.

## Objetivo

El personal registra y revoca, a nombre de un encargado, su consentimiento para comunicaciones, por canal y por
finalidad, con evidencia. Es el dato que el motor de notificaciones consultará en el momento de enviar (F-posterior).

## Fuera de alcance

Casillas y baja por enlace en el portal, motor de notificaciones y lista de supresión (otras fases). Aquí solo el
registro administrativo y su historial.

## Modelo y reglas

Tabla `guardians_consent_event`, **solo `INSERT` y `SELECT`** (inmutable), `institution_id`, seguridad por fila
forzada, claves compuestas al encargado. El estado vigente de cada combinación se **deriva del último evento**; no se
guarda un campo mutable.

| Campo | Regla |
|---|---|
| `guardianId` | Encargado de la institución |
| `channel` | `EMAIL`, `SMS`, `WHATSAPP` |
| `purpose` | `INSTITUTIONAL`, `MARKETING`. La finalidad `transactional` (cobro, mora, factura, seguridad) **no es consentible**: se basa en contrato o interés legítimo y no se registra ni se revoca aquí |
| `action` | `GRANT` o `WITHDRAW` |
| `evidenceSource` | `SIGNED_PAPER_FORM` o `ADMIN_REGISTERED` (`PORTAL_FORM` queda para el portal; la API administrativa lo rechaza) |
| `policyVersion` | Obligatoria en `GRANT` (patrón `^[A-Za-z0-9._-]{1,32}$`, p. ej. `2026-01`); en `WITHDRAW` se omite |
| `occurredAt` | Hora del servidor; el cliente no la envía |
| actor | Cuenta del personal, del token |

- Estado vigente: `GRANTED`, `WITHDRAWN` o `NEVER_GRANTED` (sin eventos). Nunca viene premarcado.
- `GRANT` exige que el encargado tenga el dato del canal (correo para `EMAIL`; teléfono para `SMS` y `WHATSAPP`) y esté
  `ACTIVE` (`409 guardian-not-active`). `WITHDRAW` siempre se permite, incluso con el encargado inactivo, y es inmediato.
- Repetir la acción que ya es el estado vigente no escribe evento y responde el estado actual (idempotente).
- Cada finalidad y canal es independiente: no existe consentimiento agrupado.
- Se serializa por encargado (bloqueo de su fila) para que dos cambios simultáneos dejen un orden coherente.

## API

| Operación | Permiso |
|---|---|
| `GET /api/v1/guardians/{id}/consents` → estados vigentes de las 6 combinaciones (canal × finalidad) con `state`, `since`, `policyVersion`, `evidenceSource`, más el historial de eventos (el más reciente primero, máx. 100) | `GUARDIANS_READ` |
| `POST /api/v1/guardians/{id}/consents` → 200: `channel`, `purpose`, `action`, `evidenceSource`, `policyVersion` | `GUARDIANS_UPDATE` |

- Auditoría `guardians.consent.granted|withdrawn` con guardián, canal, finalidad, versión y evidencia; **sin correo, teléfono
  ni nombres**. Límites de tasa existentes, `Cache-Control: no-store`, sin `Idempotency-Key` (no mueve dinero).
- Errores: `validation-failed` con campo (`purpose` no consentible, `evidenceSource`, `policyVersion`), `guardian-not-active`
  (409), `consent-channel-unavailable` (409, el encargado no tiene el dato del canal), `resource-not-found`.

## Pantalla (`admin-web`)

En el diálogo de detalle de «Encargados» una sección «Consentimientos»: matriz canal × finalidad con el estado vigente
(**ninguna casilla premarcada**), acciones «Registrar consentimiento» (versión de política y evidencia) y «Registrar
revocación» por celda, e historial. Estados de carga, vacío, error y éxito; el servidor decide los permisos (403).

## Casos límite a probar

Combinación sin eventos (`NEVER_GRANTED`), conceder, revocar y volver a conceder (historial de 3 eventos), repetir la acción
vigente (sin evento nuevo), `transactional` rechazado, `PORTAL_FORM` rechazado, versión de política ausente o con caracteres
inválidos, canal sin dato, encargado inactivo (conceder 409, revocar permitido), dos cambios simultáneos, tabla sin
`UPDATE`/`DELETE`, aislamiento entre instituciones, 403 sin efecto, límites de tasa, ausencia de datos de contacto en
auditoría y logs.

## Criterio de aceptación

Un administrador registra el consentimiento institucional por correo de un encargado con su versión de política y evidencia
en papel firmado, lo revoca, y el historial conserva ambos eventos; el estado vigente queda revocado y nada viene premarcado.

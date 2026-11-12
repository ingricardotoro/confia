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
- Repetir de forma idéntica la acción que ya es el estado vigente (revocar lo revocado; conceder lo concedido con la misma versión y la misma evidencia) no escribe evento y responde el estado actual (idempotente). Conceder lo ya concedido con otra versión de política u otra evidencia es un cambio real: escribe evento y auditoría.
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
vigente (sin evento nuevo; conceder con otra versión o evidencia sí lo escribe), `transactional` rechazado, `PORTAL_FORM` rechazado, versión de política ausente o con caracteres
inválidos, canal sin dato, encargado inactivo (conceder 409, revocar permitido), dos cambios simultáneos, tabla sin
`UPDATE`/`DELETE`, aislamiento entre instituciones, 403 sin efecto, límites de tasa, ausencia de datos de contacto en
auditoría y logs.

## Criterio de aceptación

Un administrador registra el consentimiento institucional por correo de un encargado con su versión de política y evidencia
en papel firmado, lo revoca, y el historial conserva ambos eventos; el estado vigente queda revocado y nada viene premarcado.

## Construido en el backend

Módulo `com.confia.guardians` (cuatro capas, clases nuevas), migración `V20__create_guardians_guardian_consent_event.sql` y dos
rutas con `@RateLimited` por dirección y por cuenta (lectura `admin-read` y `admin-read-account`; escritura `admin-write` y
`admin-write-account`; `429` con `Retry-After`). Permisos en `PermissionedEndpoints.forAdmin()` y `x-required-permission` en el
OpenAPI: `GET` con `guardians:read`, `POST` con `guardians:update` (no hay permiso nuevo; `StaffRole` no cambia: administrador y
super administrador registran, los demás roles con lectura solo consultan). Auditoría `guardians.consent.granted|withdrawn`.
Instantánea del OpenAPI regenerada (38 operaciones, 31 con permiso).

### Base de datos

Tabla `guardians_guardian_consent_event` (nombre fijado al construir; la nota decía `guardians_consent_event`): `institution_id`,
`id`, `seq` (identidad `GENERATED ALWAYS`, es el orden de los eventos), `guardian_id`, `channel`, `purpose`, `action`,
`evidence_source`, `policy_version`, `occurred_at` (por defecto `clock_timestamp()`), `recorded_by` y `request_id` (`UUID NOT NULL`, el mismo identificador de petición que lleva la entrada de auditoría del evento; no se expone en la API). Clave compuesta
`(institution_id, guardian_id)` hacia `guardians_guardian`; `CHECK` de cada conjunto cerrado (la finalidad solo admite
`INSTITUTIONAL` y `MARKETING`; la evidencia admite también `PORTAL_FORM` para el portal futuro, que la API administrativa
rechaza), versión de política presente si y solo si la acción es `GRANT` y con la forma `^[A-Za-z0-9._-]{1,32}$`. Seguridad por
fila forzada, `REVOKE ALL FROM PUBLIC`, solo `SELECT` e `INSERT` para la aplicación (`SELECT` para el rol de solo lectura) y dos
disparadores (`BEFORE UPDATE OR DELETE` por fila y `BEFORE TRUNCATE` por sentencia, como `shared_audit_log` en V2) que rechazan todo cambio a cualquier rol, el dueño incluido; también el `TRUNCATE ... CASCADE` desde `guardians_guardian`. No guarda correo, teléfono ni
nombre.

### Forma de la API que necesita el frontend

- **Leer** `GET /api/v1/guardians/{id}/consents` → `200`: `{ guardianId, states, history }`. `states` son siempre las seis
  combinaciones, canal por canal (`EMAIL`, `SMS`, `WHATSAPP`) y dentro de cada uno `INSTITUTIONAL` antes de `MARKETING`; cada
  elemento: `channel`, `purpose`, `state` (`GRANTED`, `WITHDRAWN`, `NEVER_GRANTED`), `since`, `policyVersion`, `evidenceSource`
  (los tres últimos son `null` si nunca se concedió; `policyVersion` es `null` si está revocado). `history`: el más reciente
  primero, máximo 100; cada elemento: `id`, `channel`, `purpose`, `action` (`GRANT`, `WITHDRAW`), `evidenceSource`,
  `policyVersion` (`null` en una revocación), `occurredAt`. Nunca trae correo, teléfono, nombres ni la cuenta que registró.
- **Registrar** `POST /api/v1/guardians/{id}/consents` → `200`. Cuerpo: `channel` (`EMAIL|SMS|WHATSAPP`), `purpose`
  (`INSTITUTIONAL|MARKETING`), `action` (`GRANT|WITHDRAW`), `evidenceSource` (`SIGNED_PAPER_FORM|ADMIN_REGISTERED`) y
  `policyVersion` (obligatoria en `GRANT`, **se omite** en `WITHDRAW`). Todos obligatorios salvo la versión; un miembro desconocido
  (por ejemplo `occurredAt` o `recordedBy`) es `400`. Respuesta: `{ channel, purpose, state, since, policyVersion,
  evidenceSource, changed }` con el estado vigente de esa combinación; `changed` es `true` si se escribió un evento y `false` si
  la petición repetía el estado vigente (el cliente puede refrescar la matriz con el `GET`).
- **Errores**: `400 validation-failed` con `errors: [{ field, reason }]`: `channel` y `action` con `pattern` o `not-null`;
  `purpose` con `not-consentable` (`TRANSACTIONAL`, en cualquier capitalización), `invalid` o `not-null`; `evidenceSource` con
  `not-allowed` (`PORTAL_FORM`), `invalid` o `not-null`; `policyVersion` con `pattern` (forma o más de 32 caracteres), `required`
  (`GRANT` sin versión) o `not-allowed` (`WITHDRAW` con versión). `404 resource-not-found` (encargado inexistente o de otra
  institución). `409 guardian-not-active` (conceder a un encargado inactivo) y `409 consent-channel-unavailable` (conceder por un
  canal sin dato: correo para `EMAIL`, teléfono para `SMS` y `WHATSAPP`). `403 forbidden` sin permiso, `401` sin sesión. Las
  respuestas llevan `Cache-Control: no-store`; no hay `Idempotency-Key`.
- `ProblemCode` gana `consent-channel-unavailable` (409); el catálogo `problems.properties` lo dice, y el detalle de
  `guardian-not-active` ahora cubre también el consentimiento y aclara que revocar siempre es posible. El frontend debe sumar
  ambos a `KNOWN_PROBLEM_CODES` (29 a 30) y a sus textos.

### Decisiones tomadas al construir

- **Orden y comprobaciones**: forma de la petición (`400`, antes de abrir la transacción), encargado (`404`, con
  `SELECT ... FOR UPDATE` de su fila, que serializa los cambios del mismo encargado), conceder exige encargado activo y luego dato
  del canal, y por último la idempotencia. Conceder a un inactivo es `409` aunque el estado ya fuera `GRANTED`.
- **Idempotencia**: solo una repetición idéntica no escribe evento ni auditoría y responde `changed: false` con el estado vigente:
  revocar lo `WITHDRAWN`, o conceder lo `GRANTED` con la misma versión **y** la misma evidencia. Conceder lo `GRANTED` con otra
  versión de política u otra evidencia es un hecho nuevo: escribe evento, audita (`guardians.consent.granted`, antes y después
  `GRANTED`) y responde `changed: true`. **Revocar una combinación `NEVER_GRANTED` sí escribe un evento** (la persona declinó por
  adelantado; es evidencia útil para la futura lista de supresión).
- **Estado vigente** = último evento de cada combinación por `seq`, no por `occurred_at`; `occurred_at` la fija el servidor
  (reloj de la aplicación truncado a microsegundos, igual que el resto del módulo).
- **Auditoría**: entidad `guardians.consent` con el identificador del encargado; antes `{guardianCode, channel, purpose,
  state}` y después ese mismo más `evidenceSource` y, solo en una concesión, `policyVersion`. Nunca correo, teléfono ni nombres.
- **Disparador `BEFORE TRUNCATE`**: la tabla lo tiene (misma función que el de `UPDATE`/`DELETE`). La limpieza de
  `CommittingPostgresIntegrationTest` sigue derivando las tablas del catálogo y, además, trunca las que tienen un disparador de
  `TRUNCATE` **y** una clave foránea hacia una tabla truncada, en la misma transacción del dueño y envolviendo el truncado con
  `ALTER TABLE ... DISABLE TRIGGER` y `ENABLE TRIGGER`. `shared_audit_log` y `shared_audit_chain_head` no tienen claves foráneas y
  siguen excluidas.
- **`request_id`**: se pasa por el puerto (`GuardianConsentRepository.append(institución, evento, requestId)`) con el
  `auditRequestId` que el caso de uso ya calcula; `ConsentEvent` y las respuestas de la API no cambian.
- **`policyVersion`**: `@Size(max = 32)` en `RecordConsentRequest`, igual que el límite real; el `maxLength` de
  `admin.openapi.json` pasa de 64 a 32 (solo el de esa propiedad).
- `RecordConsentRequest` redacta su `toString` (Spring lo registra al leer el cuerpo en TRACE). Observación ajena al corte: con el
  nivel de registro en TRACE o DEBUG, el mensaje de validación del propio Spring ("rejected value [...]") repite el valor rechazado
  de cualquier campo de cualquier DTO; en producción (INFO) no se registra.

### Pruebas

`GuardianConsentsTest` (39; máquina de estados: derivado del último evento, nada premarcado, conceder-revocar-conceder, repetición,
revocar lo nunca concedido, canal sin dato, encargado inactivo conceder/revocar, `TRANSACTIONAL` y `PORTAL_FORM` rechazados, forma
de la versión con casos válidos e inválidos), `ManageGuardianConsentsTest` (19; casos de uso con puertos en memoria: flujo de
aceptación, historial de 100, orden de bloqueos —se bloquea la fila del encargado antes de leer y de escribir—, lectura de solo
lectura, aislamiento, auditoría sin datos personales, nada se abre si la petición es inválida), `GuardiansGuardianConsentEventSchemaIT`
(11; `CHECK` de conjuntos y de versión, clave compuesta entre instituciones, orden por `seq`, `UPDATE` y `DELETE` rechazados al dueño y
sin privilegio a la aplicación, seguridad por fila, roles de solo lectura y portal) y `GuardianConsentEndpointsIT` (23; flujo de
aceptación con dos eventos, nunca premarcado, tres eventos, forma exacta de las respuestas, tiempo del servidor, repetición,
cada regla con su campo, canal sin dato, inactivo, 404/400, ocho concesiones simultáneas = un evento, doce cambios simultáneos sin
dos acciones iguales seguidas, seis combinaciones simultáneas, concesión contra inactivación, aislamiento, `403` sin efecto, `401`,
sesión cerrada, origen ausente, `429` por dirección y por cuenta, y barrido de auditoría y registros contra correo, teléfono,
nombres y dirección). Actualizadas: `ProblemCodeTest`, `PermissionedEndpointsTest`, `OpenApiContractSnapshotTest`,
`CurrentSessionEndpointIT` y `PublicRouteAllowListTest` (listas de rutas). Pendiente fuera de este paso: regenerar
`packages/contracts` con orval y la pantalla.

## Seguimientos

- **Retención y supresión frente a inmutabilidad**: la tabla no admite `DELETE` ni `UPDATE`. Antes del corte de retención
  (`docs/08-datos-privacidad-y-retencion.md`) hace falta una función `SECURITY DEFINER` de purga o un mecanismo de
  seudonimización que respete el registro como evidencia.
- **Escritor del portal**: `FOR UPDATE` sobre la fila del encargado exige privilegio `UPDATE`, que el rol del portal no tiene; el
  escritor del portal necesitará un bloqueo asesor compartido (`pg_advisory_xact_lock`) con el mismo orden que usa el
  administrativo.
- **Intentos rechazados sin auditoría**: los intentos de conceder o revocar rechazados no dejan entrada de
  auditoría; evaluar registrarlos como `denied`.
- **Revocar requiere un administrador**: hasta que exista la baja desde el portal, un encargado que pide dejar de recibir
  comunicaciones depende de que un miembro del personal con permiso lo registre.

## Construido en el frontend

Sección «Consentimientos» dentro del diálogo de detalle de «Encargados» (`/guardians`, botón «Ver detalle»). Código en
`apps/admin-web/src/features/guardians/` (`GuardianConsentsContainer`, `components/GuardianConsents`, `use-consent-form`,
`consent-model`), operaciones `getGuardianConsents` y `recordGuardianConsent` en `shared/api/guardian-operations.ts`. El cliente se
regeneró con `pnpm --filter @confia/contracts generate`.

**Decisiones**

- **Matriz 3 × 2** (canal × finalidad) en una tabla con encabezados de fila y de columna. Cada celda muestra el estado que dice el
  servidor («Concedido», «Revocado» o «Sin consentimiento registrado»), desde cuándo, la versión de política y la evidencia. No hay
  casillas, botones de opción ni valor premarcado: la evidencia del formulario empieza en «Elija la evidencia» y la versión, vacía.
- **Acciones por celda**: «Registrar consentimiento» y «Registrar revocación», con nombre accesible que incluye canal y finalidad.
  Abren un formulario en línea (no un diálogo anidado): conceder pide versión de política y evidencia (`SIGNED_PAPER_FORM` o
  `ADMIN_REGISTERED`); revocar solo evidencia y **no envía** `policyVersion`. Sin `Idempotency-Key` ni actualización optimista: el
  botón queda en estado de envío y un segundo envío no hace nada; tras la respuesta se vuelve a leer `GET .../consents`.
- **`changed`**: `true` produce «Se registró el consentimiento/la revocación…»; `false`, «Sin cambios: … ya estaba concedido/revocado.
  No se agregó ningún evento al historial.». El mensaje vive en una región `aria-live="polite"` siempre montada.
- **Foco**: al abrir va al primer campo (versión o evidencia); al cancelar o tras guardar vuelve al botón de la celda; un envío con
  errores locales o de campo mueve el foco al resumen de errores (con enlaces a los campos); un fallo del servidor, al aviso
  (`role="alert"`), conservando lo escrito.
- **Errores**: `consent-channel-unavailable` y `guardian-not-active` con texto propio (el segundo, en esta pantalla, aclara que revocar
  siempre es posible; `KNOWN_PROBLEM_CODES` pasa de 29 a 30 y `PROBLEM_KEYS` lo espeja), `forbidden`, `resource-not-found`, 429 con
  cuenta regresiva, error inesperado con identificador de traza. Campos: `policyVersion` (`pattern`, `required`), `evidenceSource`
  (`not-allowed`, `required`); `purpose`/`channel`/`action` (`not-consentable`, etc.) no tienen control y se dicen como «no se pudieron
  procesar los datos». Un encargado inactivo muestra un aviso previo; el servidor sigue decidiendo.
- **Historial**: tabla del más reciente al más antiguo (fecha y hora en la zona de la institución con `Intl.DateTimeFormat`, canal,
  finalidad, acción, política, evidencia), vacío con texto propio y nota cuando el servidor corta en 100. Estados: esqueleto con la
  forma de la matriz, error con reintento y traza, vacío, éxito.
- **Sin datos de contacto** en la URL, el almacenamiento, la consola ni la sección: la API no los envía y el formulario no los toca.
- Cambios compartidos mínimos: `formatInstant` en `shared/format/format-date.ts`, tres motivos nuevos en `fieldErrorKey`, `GuardianDetail`
  gana la ranura opcional `consents`, y `classifyOrganizationFailure` reconoce `policyVersion` + `pattern`.

**Simulador (e2e).** `mock-api/guardians-model.ts` atiende `GET` y `POST /guardians/{id}/consents` con un registro de eventos en
memoria: estado derivado del último evento, `TRANSACTIONAL` y `PORTAL_FORM` rechazados con su campo, versión requerida, mal formada o
sobrante, conceder exige encargado activo y dato del canal (correo para `EMAIL`, teléfono para `SMS` y `WHATSAPP`), revocar siempre se
permite (también sobre `NEVER_GRANTED`), y la repetición del estado vigente no escribe (`changed: false`). **Una concesión sobre una
concesión con otra versión o evidencia sí es un cambio** (`changed: true`), como el backend actual; la frase del apartado «Decisiones
tomadas al construir» sobre «otra versión o evidencia en la repetición no cambia nada» quedó superada por ese comportamiento.

**Pruebas del frontend.** `GuardianConsentsContainer.test.tsx` (21: matriz sin nada premarcado, estado y historial del servidor, límite de
100, encargado inactivo, error con reintento, cuerpos exactos de conceder y revocar, un solo envío, `changed`, resumen de errores y foco,
versión inválida, cancelar, cada código de problema, error de campo, 429, sin rastro de contacto), `format-instant.test.ts`,
`guardians-model.test.ts` (7 nuevas) y las listas de códigos en `problem.test.ts` y `problem-keys.test.ts`. Recorridos:
`e2e/specs/journeys/guardians-consents.e2e.ts` (7: el criterio de aceptación —conceder por correo con versión y papel firmado, revocar, el
historial conserva ambos—, repetición, formulario vacío, canal sin dato, inactivo, teclado). Matriz de axe: `dialog-consents`,
`dialog-consents-form-errors` y `dialog-consents-channel-error` en `/guardians`, en tema claro y oscuro.

**Resultado de la verificación (frontend).** `tsc --noEmit` y `eslint` (src y e2e) limpios. Vitest dirigido (`features/guardians`, `shared`,
`i18n`, `organization-failure`, `e2e/mock-api`, `e2e/support`): 49 archivos, 696 pruebas, todas pasan. Playwright, solo lo del corte: los
cinco recorridos previos de encargados y el nuevo (33 pruebas) y la puerta de axe filtrada a encargados (28 evaluaciones, 14 estados en
dos temas) pasan sin violaciones bloqueantes. No se ejecutó `test:e2e` completo.

# Corte: sesiones de caja (cashbox, corte 1)

Fase F4, corte 1. Es previo al registro de pagos (corte 2, `payments-register`): el efectivo exige una sesión abierta (INV-20), así
que la sesión de caja debe existir antes que el cobro. Este corte no registra pagos ni toca el libro mayor.

## Objetivo

Que un cajero abra su caja con un fondo inicial, registre retiros y depósitos de efectivo, y la cierre declarando el conteo físico; que
el sistema calcule lo esperado y la diferencia, y que una diferencia distinta de cero exija motivo y la aprobación de otra persona.
Que Contabilidad pueda consultar las sesiones y aprobar los cierres con diferencia.

## Reglas e invariantes

- **INV-22**: un usuario no puede tener más de una sesión `open` a la vez (índice único parcial por institución y cajero).
- **INV-21**: una sesión `closed` o `reconciled` no acepta movimientos (aplicación y disparador de base de datos).
- **INV-23**: `differenceAmount = declaredAmount − expectedAmount` (columna generada). Si es distinta de cero, `differenceReason` es
  obligatorio (20 a 500 caracteres) y el cierre queda `pending_approval` hasta que un aprobador **distinto del cajero** con
  `cashbox:approve` lo apruebe (segregación de funciones, `docs/03-seguridad.md` §5.3). Con diferencia cero el cierre queda `closed`.
- **Esperado** = fondo inicial + depósitos − retiros ± ajustes de los movimientos de la sesión. Se calcula en el servidor sobre
  `Money`; el navegador solo lo muestra. En este corte no hay `payment_in` (llega con el corte 2, sin cambiar la fórmula).
- Nada se borra ni se edita: movimientos y cierre son filas de solo inserción; el estado de la sesión se deriva o avanza por
  transiciones registradas (`open → closed | pending_approval → closed`). Una corrección es un movimiento `adjustment` con motivo.
- Una sesión es de una moneda (la de la institución, HNL). El fondo inicial es mayor o igual a cero; retiros y depósitos, mayores a cero.
- Idempotencia: `Idempotency-Key` obligatoria en abrir, mover, cerrar y aprobar. Toda acción escribe en la bitácora.

## Permisos (ya existen en `Permission`; sin cambio de matriz)

`cashbox:create` (Cajero y Super Administrador: abrir, mover, cerrar), `cashbox:read` (Cajero solo su sesión; Administrador,
Contabilidad, Auditor y Super Administrador, todas), `cashbox:approve` (**Administrador y Super Administrador**, según la matriz
efectiva de `docs/03-seguridad.md` §5.2 y la fila de §5.3 «Cierre de caja con diferencia»; Contabilidad solo lee y exporta). El
Administrador aprueba pero no maneja efectivo (no tiene `cashbox:create`).

## Endpoints (`/api/v1/cashbox`)

| Ruta | Permiso | Resultado |
|---|---|---|
| `POST /sessions` `{openingFloat}` | `cashbox:create` | 201 sesión abierta. 409 `cash-session-already-open` |
| `GET /sessions/current` | `cashbox:create` | Mi sesión abierta con esperado y movimientos, o 404 |
| `POST /sessions/{id}/movements` `{type, amount, notes}` | `cashbox:create` | 201 movimiento (`deposit`, `withdrawal`, `adjustment`) |
| `POST /sessions/{id}/close` `{declaredAmount, differenceReason?}` | `cashbox:create` | 200 cierre; `pending_approval` si hay diferencia |
| `POST /sessions/{id}/approve` `{note}` | `cashbox:approve` | 200 aprobada. 403 si el aprobador es el cajero |
| `GET /sessions` (`status`, `cashier`, `from`, `to`, `sort`, `page`, `size`) | `cashbox:read` | Página de sesiones |
| `GET /sessions/{id}` | `cashbox:read` | Detalle con movimientos y cierre (base del reporte imprimible) |

Otra institución e identificador desconocido responden igual: 404 `resource-not-found`.

## Pantallas (admin-web)

- **Mi caja** (`/cashbox`): sin sesión, formulario de apertura; con sesión, esperado, movimientos, registrar retiro o depósito y cerrar
  caja (conteo declarado, diferencia en vivo informativa y motivo cuando aplique).
- **Sesiones de caja** (`/cashbox/sessions`): listado filtrable y detalle; aprobar cierres pendientes para quien tiene `cashbox:approve`.
- El reporte imprimible del cierre es el detalle con estilos de impresión (firma del cajero y del aprobador).
- Estados de carga, vacío, error y éxito; bloqueo del reenvío mientras la petición está en curso.

## Casos límite

Segunda apertura (misma y distinta clave), apertura simultánea desde dos peticiones, fondo cero y negativo, movimiento sobre sesión
cerrada, retiro mayor al esperado (se permite pero el esperado no puede quedar negativo: se rechaza), cierre con diferencia cero,
positiva y negativa, cierre con diferencia sin motivo, aprobador igual al cajero, aprobar una sesión sin diferencia, doble
aprobación, cajero que consulta la sesión de otro, sesión de otra institución (aislamiento por fila, prueba como
`confia_admin_app`), reenvío con la misma clave.

## Criterio de aceptación

Un cajero abre con 500.00, deposita 100.00, retira 50.00 y cierra declarando 540.00: esperado 550.00, diferencia −10.00, queda
`pending_approval`; el mismo cajero no puede aprobarla, el Administrador (o el Super Administrador) sí, y la sesión queda `closed` con todo en la bitácora. Una
segunda apertura mientras hay una abierta responde 409. Con la sesión cerrada, ningún movimiento nuevo entra, ni por la API ni por SQL.

## Fuera de alcance

Pagos y movimiento `payment_in` (corte 2), reembolsos (`refund_out`), conteo por denominaciones (`CashCount`), cajones físicos
(`CashDrawer`) y asientos contables del efectivo.

## Lo construido (backend, 2026-11-19)

Esta sección describe el backend y el contrato OpenAPI. Las pantallas están en «Lo construido (frontend)», más abajo.

- **Migración `V34__create_cashbox_sessions.sql`**: `cashbox_session`, `cashbox_movement`, `cashbox_session_close` (con la columna
  generada `difference_amount = declared_amount - expected_amount`) y `cashbox_session_approval`. Índice único parcial
  `(institution_id, cashier_id) WHERE status = 'open'` (INV-22). Disparadores: un movimiento solo entra en una sesión `open` y en su
  moneda (INV-21, con `FOR SHARE` para que un movimiento que corre contra un cierre espere); el cierre recalcula el esperado desde
  los movimientos, exige que cierre el cajero y que la diferencia tenga motivo de 20 a 500 caracteres (INV-23); la aprobación
  rechaza al cajero de la sesión y a una sesión que no esté `pending_approval`. Movimientos, cierres y aprobaciones son de solo
  inserción (UPDATE, DELETE y TRUNCATE rechazados a todo rol). La única columna mutable es `status` de la sesión: solo avanza
  (`open → closed | pending_approval`, `pending_approval → closed`) y solo con el registro que lo justifica; `confia_admin_app` tiene
  `UPDATE (status)` y nada más. RLS forzada por institución.
- **Paquete `com.confia.cashbox`**: dominio puro con `Money` (`CashSession`, `CashMovement`, `CashClose`, `CashApproval`; el esperado
  y la diferencia se calculan en `CashSession`/`CashClose`), casos de uso `OpenSession`, `RecordMovement`, `CloseSession`,
  `ApproveClose`, `ListSessions`, `GetSession`, `GetCurrentSession`, adaptadores jOOQ, `CashboxController` con `Idempotency-Key`
  permanente por operación (clave + hash que incluye al actor; misma clave y mismo cuerpo devuelve lo mismo, otro cuerpo u otro
  actor 422 `idempotency-payload-mismatch`), bloqueo `FOR UPDATE` de la sesión en mover, cerrar y aprobar, y bitácora dentro de la
  misma transacción (`cashbox.session.opened|closed|approved`, `cashbox.movement.recorded`; solo longitudes de los textos libres).
- **Errores** (`ProblemCode`): `cash-session-already-open` 409, `cash-session-not-open` 409, `cash-session-not-awaiting-approval`
  409, `cash-expected-negative` 409, `cash-difference-reason-required` 400, `cash-approval-same-actor` 403.
- **Importes**: entran y salen como `{ amount, currency }` (HNL, a lo más dos decimales); el movimiento devuelve el esperado
  posterior y el detalle el esperado, el cierre y la diferencia ya redondeados por el servidor.
- **Pruebas**: `CashSessionTest` (dominio y casos límite de la nota), `CashboxUseCasesTest` (casos de uso con dobles),
  `CashboxSessionsIT` (PostgreSQL real: flujo de aceptación, apertura simultánea, idempotencia, rechazos de la base por SQL, RLS
  como `confia_admin_app` con conteos sin filtro), `CashboxEndpointsIT` (proceso administrativo real: flujo de aceptación por HTTP,
  permiso de cada rol, códigos de error, aislamiento entre instituciones y el alcance del cajero sobre su propia sesión), más las listas fijadas de rutas, permisos, OpenAPI y claves de idempotencia.

### Pendientes y decisiones abiertas

1. **Alcance «el cajero solo ve su sesión» en la lectura (`docs/03` §5.4): resuelto (2026-11-19).** `StaffRole.ownScoped()`
   declara el alcance (solo el cajero, solo `cashbox:read`); `identity` lo publica con `OwnRecordPolicy` (interfaz nombrada
   `identity::directory`, adaptador `AccountOwnRecordPolicy`, sin transacción propia y sin tocar `PermissionResolver`) y
   `RoleDirectorySessionScope` lo traduce al puerto `SessionScope` de `cashbox`. Un cajero ve solo sus sesiones: el listado se
   limita aunque pida el id de otro cajero, el detalle ajeno es 404 y la sesión actual es la suya; Contabilidad, Auditor,
   Administrador y Super Administrador ven todas. Falla cerrado: sin rol o con código desconocido, solo lo propio.
   Pendiente aparte: `docs/03` §6.3 (tabla de RLS, línea ~1056) dice que la fila de `cashbox_session` se limita al propio cajero salvo
   supervisor, pero la migración V34 solo aísla por institución; el alcance por cajero lo hace la aplicación (se reporta, no se
   edita la migración).
   **Actualización (2026-11-19, auditoría de seguridad M2):** `docs/03` §6.3 lleva ahora una nota fechada que lo declara
   (límite por cajero en la aplicación, fallo cerrado; el límite en base de datos exige una variable de alcance del actor en
   `TransactionRunner`, decisión de `confia-architect` antes del módulo `reporting`). Una prueba de arquitectura
   (`AuthorizationBoundaryRulesTest`, con dos fixtures que debe rechazar) exige que la capa `web` no lea `CashSessionRepository` y
   que todo caso de uso de lectura (`Get*`, `List*`, salvo `GetCurrentSession`, que lee solo la sesión del propio actor) aplique
   `SessionScope`.
2. **Moneda**: la sesión usa HNL fijo (`CashAmounts.CURRENCY`), la de la institución; leer `organization_institution.default_currency`
   exigiría un directorio publicado por `organization`.
3. La nota original atribuía `cashbox:approve` a Contabilidad; la matriz efectiva aprobada por el propietario lo da al
   Administrador y al Super Administrador, y así quedó.

4. **Ronda de revisión y auditoría (2026-11-19), corregida en V34 (no estaba fusionada ni aplicada, se enmendó en sitio):**
   - Disparador de apertura: exige `status = 'open'`, `cashier_id = app.actor_id` (rechaza si es nulo) y `opened_at` dentro de un
     minuto de `clock_timestamp()` (la aplicación envía su reloj; las pruebas de integración usan el reloj real). Los disparadores
     de movimiento, cierre y aprobación exigen `created_by`, `closed_by` y `approved_by` igual a `app.actor_id`; el movimiento
     además exige que sea el cajero de la sesión.
   - El disparador de movimiento toma `FOR UPDATE` (antes `FOR SHARE`) y recalcula el esperado acumulado: rechaza un movimiento que
     lo dejaría negativo aunque se escriba por SQL sin el bloqueo de la aplicación, y guarda el resultante en
     `cashbox_movement.expected_after` (lo calcula el disparador, no se envía).
   - La repetición idempotente de un movimiento devuelve el esperado de la llamada original (`expected_after`), no el de hoy.
   - Pruebas nuevas en `CashboxSessionsIT`: retiros simultáneos que suman más que lo esperado (solo caben los que caben, el esperado
     final es 0), la misma carrera por SQL directo sin el bloqueo de la aplicación, un movimiento contra el cierre (queda dentro
     del esperado o se rechaza con `cash-session-not-open`, nunca después), repetición de A después de B, y los rechazos por SQL
     como `confia_admin_app` de cada regla de actor.
5. **Abiertos, fuera de esta ronda:** (B1) auditar los intentos denegados (cierre ajeno, aprobación del propio cajero, etc.);
   (B2) la clave de idempotencia por actor: hoy la clave es única por institución y otro actor con la misma clave recibe
   `idempotency-payload-mismatch` (422) en vez de una clave propia.

## Lo construido (frontend, 2026-11-19)

Rutas de `apps/admin-web` bajo el área `/cashbox` (componente `CashboxContainer`, `h1` «Caja»; cada vista pinta su `PageHeader` de nivel 2):

- **Mi caja** (`/cashbox`, entrada «Mi caja» del grupo Finanzas, exacta para no encenderse en las rutas hijas). Sin sesión (el servidor responde 404 a
  `GET /sessions/current`), formulario de apertura con el fondo inicial. Con sesión, el esperado que entrega el servidor, la tabla de movimientos y las
  acciones: registrar depósito, retiro y ajuste (diálogo; el ajuste exige nota de 20 a 500 caracteres) y cerrar caja (diálogo con el esperado y el conteo
  lado a lado, y el motivo de la diferencia opcional). Al cerrar, el resultado que devolvió el servidor (conteo, diferencia con su signo, estado) se muestra
  sobre el formulario de apertura, con el acceso al reporte.
- **Sesiones de caja** (`/cashbox/sessions`, entrada «Sesiones de caja»): tabla con paginación, orden (`openedAt`, `status`) y filtros (estado, apertura desde y
  hasta, cajero por URL) en el servidor, con el estado de la tabla en los parámetros de búsqueda validados con Zod.
- **Detalle y reporte de cierre** (`/cashbox/sessions/$sessionId`): resumen, movimientos, cierre y aprobación; botón «Aprobar cierre» (diálogo con el cierre
  restituido y nota obligatoria) para una sesión `pending_approval`; botón «Imprimir reporte». Estilos de impresión: el armazón (barra lateral, barra superior,
  enlace de salto) y las acciones llevan `print:hidden`, y dos líneas de firma (cajero y quien aprueba) solo aparecen en papel.
- **Contrato y errores**: `shared/api/cashbox-operations.ts` (siete operaciones, esquemas generados, `Idempotency-Key` en las cuatro escrituras). Los seis códigos
  `cash-*` están en `KNOWN_PROBLEM_CODES`, `PROBLEM_KEYS` y el catálogo es-HN. `cash-difference-reason-required` se muestra bajo el campo del motivo (error de
  campo, con el resumen de errores y el foco), no como alerta; los demás van en la alerta del diálogo con el identificador de traza.
- **Dinero**: el navegador no calcula. Importes con `MoneyAmount`; los importes escritos viajan como el texto tecleado (solo se valida el formato con una
  expresión regular, nunca como `number`). La **diferencia en vivo informativa de la sección «Pantallas» no se construyó**: sería aritmética monetaria en el
  navegador. Antes de cerrar se muestran el esperado y el campo del conteo lado a lado; la diferencia aparece después, tal como la devolvió el servidor.
- **Idempotencia y reenvío**: `cashbox-model.ts` (`intentFor`) genera la clave una vez por intento y la reutiliza solo si el cuerpo es el mismo y el resultado
  fue desconocido (red, respuesta ilegible, 5xx); una respuesta definitiva del servidor (4xx) cierra el intento y el siguiente envío lleva clave nueva. El botón
  queda en espera y la segunda pulsación no envía mientras la petición está en vuelo (`useAccessForm`). Ninguna actualización optimista: tras cada escritura se
  invalida el prefijo `cashboxKeys.all` (sesión actual, listados y detalles) y el diálogo se cierra cuando la lectura nueva llegó.
- **Estados**: carga con esqueleto, vacío con acción, error con reintento e identificador de traza, 403 y 404 como aviso sin reintento, éxito en región `aria-live`.
- **Pruebas**: 15 archivos nuevos de `features/cashbox` (contenedores con MSW sobre la ruta real, presentaciones con el comentario `States.`, modelo y fallos),
  `cashbox-operations.test.ts`, el modelo simulado `e2e/mock-api/cashbox-model.ts` con su prueba y cuatro presets (`cashbox-empty`, `cashbox-open`,
  `cashbox-forbidden`, `cashbox-server-error`), el recorrido `cashbox-session.e2e.ts` (abrir, depositar, retirar, cerrar con diferencia, reporte, rechazo de la
  aprobación por el propio cajero, y aprobación de un cierre ajeno desde la lista, con axe en cada paso) y nueve estados nuevos de la matriz de la puerta de axe.
  Actualizadas las pruebas fijadas: `journeys-inventory`, `routes` (AW59), `structure`, `AppShellContainer`, `page-header-standard` y la de entradas de navegación.

### Pendientes y decisiones abiertas del frontend

1. **Permisos en la interfaz**: la sesión (`CurrentSessionResponse`) no entrega permisos ni rol, así que las entradas del menú y el botón «Aprobar cierre» se ofrecen a
   toda persona con sesión y el servidor decide (403 `forbidden` o `cash-approval-same-actor`, que cada diálogo explica). Ocultarlos según `cashbox:create` y
   `cashbox:approve` exige que el contrato de sesión los exponga: decisión de `confia-architect` y `confia-backend-dev`. El bloqueo por «quien cerró no aprueba» tampoco se
   puede anticipar en pantalla sin el identificador de la cuenta en la sesión del cliente (`accountId` existe en el contrato, la app no lo usa todavía).
2. **Cajero por identificador**: el contrato da `cashierId` y no el nombre; la lista y el detalle muestran el identificador (corto en la tabla, completo en el detalle). Un
   nombre legible exige un directorio de cuentas publicado.
3. **Recorrido de Playwright sin ejecutar** en esta rama: los puertos 5173 y 8099 estaban ocupados por los servidores de otro árbol de trabajo, y la configuración los fija
   (`strictPort`, `MOCK_API_URL`, origen del simulador). Corre con `pnpm --filter @confia/admin-web exec playwright test e2e/specs/journeys/cashbox-session.e2e.ts`
   cuando los puertos estén libres.

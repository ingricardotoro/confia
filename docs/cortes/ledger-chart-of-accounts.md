# Corte: carga del plan de cuentas y del mapa de contabilización (ledger y charges)

Fase F3, entre los cortes 3a-1 y 3a-2. Decisiones abiertas 1 de `docs/cortes/ledger-core.md` y de `docs/cortes/charges-accrual.md`.
Estado: **backend construido** (ver «Construido (backend)» al final); sin pantalla. Cierra el pendiente «`GET`/`PUT /charges/posting-accounts` y
`GET`/`POST /ledger/accounts` con `ledger:create`» de `docs/cortes/charges-accrual.md`.

## Objetivo

Que el Super Administrador cargue por la aplicación, con permiso, el plan de cuentas de la institución y el mapa que dice a qué cuenta
contabiliza el motor de devengo (por cobrar, ingreso por categoría de concepto e impuesto por pagar). Hasta hoy el motor solo **lee** ambos y
solo se podían sembrar como dueño del esquema en pruebas. El plan de cuentas es **dato**: ningún código de cuenta, nombre ni cifra fiscal
está en el código ni en una migración.

## Fuera de alcance

Pantalla de plan de cuentas y del mapa; edición del código, el tipo o la bandera de estudiantes de una cuenta (el disparador de V30 lo
prohíbe siempre); borrado de cuentas o de filas del mapa; ajustes manuales, cierre de período y reportes (cortes 4 y 5); importación masiva del
plan; aprobación del plan por el contador (queda como validación posterior, ver «Plan mínimo sugerido»); moneda por cuenta (no existe, ver reglas).

## Decisiones del propietario

1. El plan es **dato** que carga **solo el Super Administrador**. Nada se siembra en migraciones ni en código.
2. Permiso nuevo **`ledger:create`**, solo Super Administrador; el Administrador **no** lo recibe.
3. Las cuentas **nunca se borran**: se desactivan. Una cuenta con asientos no cambia código, tipo ni bandera (nadie los cambia nunca).
4. El plan de seis cuentas es una **sugerencia** documentada aquí, a validar con el contador; no es código.

## Reglas

- **Cuenta** (`ledger_account`, V30): `code` (1 a 30 caracteres `[A-Za-z0-9_.-]`, único por institución **sin distinguir mayúsculas**), `name` (1 a
  120, sin caracteres de control), `type` (`ASSET`, `LIABILITY`, `EQUITY`, `INCOME`, `EXPENSE`), `holdsStudentBalances` (solo `ASSET` o
  `LIABILITY`), activa, `version`. Nace activa y en versión 0. `PUT` cambia **solo** `name` y `active`, con la `version` leída (otra: `409
  stale-version`) y mueve la versión en uno. Un cuerpo con `code`, `type` u otro miembro se rechaza `400`.
- **Moneda.** La cuenta contable **no tiene moneda**: la moneda de un asiento la fija la subcuenta del estudiante y una transacción es de una sola
  moneda (INV-06). Por eso el mapa valida existencia, institución, estado y tipo, y no moneda.
- **Desactivar** una cuenta solo rechaza **hechos nuevos** (INV-39); sus asientos siguen legibles y su reverso sigue permitido. Si el mapa de
  contabilización la usa, se **rechaza** (`409 account-in-use`): primero se apunta el mapa a otra cuenta. Se decidió rechazar y no advertir
  porque un mapa que apunta a una cuenta apagada rechaza todo el devengo. La verificación es atómica: el `PUT` del mapa toma las cuentas con
  `FOR SHARE` y la desactivación la cuenta con `FOR UPDATE` y luego pregunta al módulo que la usa (puerto `LedgerAccountUsage`, que
  implementa `charges`; `ledger` no conoce a `charges`, sin ciclo de módulos).
- **Mapa** (`charges_posting_account`, V32): `purpose` (`RECEIVABLE`, `REVENUE`, `TAX_PAYABLE`), `concept_category` (solo `REVENUE`; ninguna = ingreso por
  defecto; `TUITION`, `ENROLLMENT`, `TRANSPORT`, `MATERIAL`, `FINE`, `OTHER`). Cuenta exigida: por cobrar = `ASSET` con `holdsStudentBalances`;
  ingreso = `INCOME` sin estudiantes; impuesto por pagar = `LIABILITY` sin estudiantes (la misma regla que aplica el motor al devengar). La
  cuenta debe existir **en la institución** (de otra: `404`), estar **activa** (`409 inactive-reference`) y ser del tipo (`409
  posting-account-invalid`).
- **`PUT` del mapa es un upsert parcial**: solo se escriben las entradas enviadas, así el mapa se carga por pasos y un mapa incompleto sigue
  siendo reportable (la vista previa responde `posting-accounts-incomplete`). Nada se borra. Cada entrada lleva la `version` de su fila, o
  ninguna si nunca se fijó; otra versión es `409 stale-version` y **nada** de la petición se guarda (una sola transacción). La misma cuenta
  reenviada no cambia nada ni audita. Hasta 20 entradas, ninguna repetida.
- **Permisos.** `GET` de cuentas `ledger:read`; `POST` y `PUT` de cuentas `ledger:create`; `GET` del mapa `charges:read`; `PUT` del mapa
  **`ledger:create`** y no `charges:update` (que tiene el Administrador): el mapa decide en qué cuenta se registra cada cargo, así que es la misma
  autoridad que carga el plan. `PUT` de una cuenta usa `ledger:create` porque no existe `ledger:update` (regla 1 de §5.2: nadie edita el libro) y
  editar el nombre o desactivar es configuración del plan. No son rutas de dinero: sin `Idempotency-Key`; la concurrencia la cubren la versión y
  los bloqueos.
- **Auditoría** en la misma transacción: `ledger.account.created|updated` y `charges.posting_account.updated` (con antes y después, sin datos
  personales). Una petición rechazada no deja entrada.

## API (`/api/v1`)

| Ruta | Permiso | Notas |
|---|---|---|
| `GET /ledger/accounts?type=&active=&sort=&page=&size=` | `ledger:read` | Página (tope 200); orden `code` o `name` |
| `POST /ledger/accounts` | `ledger:create` | `{code, name, type, holdsStudentBalances}`; `201`; `409 duplicate-account-code` |
| `PUT /ledger/accounts/{id}` | `ledger:create` | `{version, name, active}`; `404`, `409 stale-version`, `409 account-in-use` |
| `GET /charges/posting-accounts` | `charges:read` | `{items: [{id, purpose, conceptCategory?, ledgerAccountId, version, updatedAt}]}` |
| `PUT /charges/posting-accounts` | `ledger:create` | `{entries: [{purpose, conceptCategory?, ledgerAccountId, version?}]}`; devuelve el mapa completo |

Códigos nuevos: `duplicate-account-code`, `account-in-use`, `posting-account-invalid` (todos 409); se reutilizan `validation-failed`,
`resource-not-found`, `inactive-reference` y `stale-version`. Sin migración: V30 y V32 ya tienen las columnas, las versiones, las llaves
compuestas con la institución, RLS y los permisos de columna (`UPDATE (name, is_active, version, updated_at)` y `UPDATE (ledger_account_id,
version, updated_at)`).

## Plan mínimo sugerido (no es código; validar con el contador)

Seis cuentas con códigos y nombres **ficticios** que el contador reemplazará: cuentas por cobrar a estudiantes (`ASSET`, con estudiantes);
caja y bancos (`ASSET`); impuesto por pagar (`LIABILITY`); ingresos por colegiatura, por matrícula y por otros conceptos (`INCOME`). Mapa:
por cobrar → cuentas por cobrar; impuesto por pagar → impuesto por pagar; ingreso por defecto → otros ingresos, y por categoría `TUITION` y
`ENROLLMENT` → su ingreso. Si el contador exige el saldo a favor como pasivo, es una cuenta `LIABILITY` con estudiantes (F4).

## Casos límite

Código duplicado cambiando mayúsculas; código vacío, con espacio, de 31 caracteres; tipo desconocido o en minúsculas; bandera de estudiantes en
`INCOME`; nombre en blanco o de 121; cuerpo con `institutionId`, `code` o `type`; versión vieja y versión ausente; cuenta de otra institución
(`404`); desactivar la cuenta del mapa; reactivar; mapa con cuenta de tipo equivocado, inactiva, inexistente o ajena; categoría en una
finalidad que no la admite o fuera del conjunto; la misma clave dos veces; petición con una entrada buena y una mala (no se guarda ninguna);
`403` para Administrador, Cajero, Contabilidad, Auditor y Coordinador en las escrituras; aislamiento entre instituciones; bitácora.

## Criterio de aceptación

Con el Super Administrador: se cargan seis cuentas, se fija el mapa en dos pasos y la lectura devuelve lo fijado; el Administrador recibe `403` al
intentarlo; cada rechazo de la lista de casos límite responde su código sin dejar filas ni bitácora; una cuenta del mapa no se desactiva hasta
apuntar el mapa a otra; otra institución no ve ni toca nada; cada escritura aceptada deja su entrada de auditoría.

## Construido (backend)

- **Permiso `ledger:create`**: catálogo `Permission` de 89 a 90 (89 de módulo y verbo más `invoicing:request-void`); `StaffRole`: solo el Super
  Administrador (89 permisos; los otros cinco roles no cambian: 62, 18, 31, 35, 23); matriz efectiva y nota fechada 2026-11-17 en `docs/03-seguridad.md`
  §5.2; `PermissionMatrixParityTest` en verde; conteos fijados en `PermissionTest`, `StaffRoleTest`, `AccountPermissionsTest` y `AccountPermissionsIT`.
- **ledger**: `LedgerAccount` (dominio puro), `ManageLedgerAccounts`, puerto `LedgerAccountRepository` con `JooqLedgerAccountRepository` (solo inserta,
  lee y escribe `name`, `is_active`, `version` y `updated_at`), `LedgerAccountController` con sus DTO y `LedgerWebConfiguration`. API pública
  ampliada de forma mínima: `LedgerAccountDirectory.lockShare` (lectura `FOR SHARE` en orden de identificador) y el puerto `LedgerAccountUsage`.
- **charges**: `ManagePostingAccounts` (upsert parcial con versión por fila), `PostingAccountKey`, `PostingPurpose` con el tipo que exige cada finalidad,
  `PostingAccountController` con sus DTO, `PostingAccountUsage` (implementa `LedgerAccountUsage` sobre `charges_posting_account`) y la entrada
  `charges.posting_account.updated` en `ChargesAudit`. `charges` lee las cuentas solo por `ledger.application.api`.
- **Sin migración.** V30 y V32 ya traen versión, llaves compuestas con la institución, RLS forzada, disparadores de inmutabilidad y permisos de
  columna; el rol de la aplicación puede `INSERT` y `UPDATE` exactamente lo que estas rutas escriben y nada más (un `UPDATE` del código lo rechaza el
  disparador; ninguna fila se borra).
- **Contrato:** OpenAPI de administración con 5 operaciones nuevas (74 a 79 con permiso) y 3 rutas; aditivo, ninguna ruta existente cambia;
  `packages/contracts` regenerado con `pnpm generate`. Problemas nuevos: `duplicate-account-code`, `account-in-use`, `posting-account-invalid`
  (+ textos en `problems.properties`).
- **Pruebas:** `LedgerAccountTest`, `PostingAccountKeyTest` (dominio), `ManageLedgerAccountsTest` y `ManagePostingAccountsTest` (casos de uso con
  puertos en memoria) y `LedgerChartEndpointsIT` (proceso administrativo real contra PostgreSQL: 403 de los demás roles, código duplicado sin
  distinguir mayúsculas, tipo y bandera inválidos, versión vieja, mapa por pasos, cuenta de tipo equivocado, inactiva, inexistente y ajena, cuenta del
  mapa que no se desactiva, rechazo atómico, bitácora con el actor, aislamiento entre instituciones).
- **Decisiones tomadas en la construcción:** (1) el `PUT` del mapa exige `ledger:create` y no `charges:update`, por la intención de Super
  Administrador; (2) el `PUT` de una cuenta exige `ledger:create` porque `ledger:update` no existe; (3) desactivar una cuenta del mapa se rechaza, no
  se advierte; (4) la cuenta contable no tiene moneda, así que el mapa no valida moneda; (5) el `GET` del mapa devuelve las filas tal como están
  (incluso incompletas) y la integridad la sigue informando la vista previa del devengo; (6) estas escrituras de configuración no usan
  `Idempotency-Key`.
  (7) la guarda AZ74 (`AuthorizationAbsences`) trataba toda ruta `.../accounts` como gestión de cuentas de personal; se le agregó una lista exacta de
  las tres rutas del plan de cuentas (`LEDGER_CHART_ROUTES`), sin tocar el patrón, con una prueba de control de que otras rutas siguen siendo violación.
- **Pendiente:** pantalla del plan de cuentas y del mapa; validación del plan con el contador; decidir en el corte 4 si el ajuste manual reutiliza
  `ledger:create`.

## Construido (frontend)

Feature nueva `apps/admin-web/src/features/ledger` (una feature no importa a otra: el mapa pertenece a `charges` en el backend, pero su pantalla
necesita las cuentas del plan, así que vive aquí). En la navegación, grupo «Configuración», entrada «Plan de cuentas»; el área `/ledger` lleva a
`/ledger/accounts` y tiene una segunda vista, `/ledger/posting-accounts` («Mapa de cuentas de cargos»). Las rutas se comparten por
`shared/navigation/ledger-paths.ts`; el cliente está en `shared/api/ledger-operations.ts`.

- **Plan de cuentas**: lista paginada, ordenada (código, nombre) y filtrada (tipo, estado) en el servidor, con el estado en la URL. Columnas: código,
  nombre, tipo, saldos de estudiantes, estado. Diálogo de alta (código, nombre, tipo, marca de saldos de estudiantes, que solo se ofrece a `ASSET` o
  `LIABILITY`) y de edición (solo nombre y estado; código, tipo y marca de solo lectura; se envía la `version`). Activar y desactivar desde la fila
  con el color de su acción. `duplicate-account-code` se muestra bajo el campo código; `account-in-use` explica que el mapa aún apunta a la cuenta;
  `stale-version` recarga y explica.
- **Mapa de cuentas de cargos**: formulario con nueve renglones (por cobrar, impuesto por pagar, ingreso por defecto y un ingreso por cada una de las seis
  categorías), cada uno con las cuentas **activas** del tipo que exige la regla (el servidor decide). Estado «Configurada»/«Sin configurar» en texto y
  aviso de mapa incompleto que dice que la generación de cargos queda bloqueada. Guarda solo los renglones cambiados, con la `version` de cada fila; sin
  actualización optimista; relee el mapa tras guardar. `stale-version` descarta lo elegido y recarga; `posting-account-invalid`, `inactive-reference` y 404
  tienen texto propio; 403 vuelve el formulario de solo lectura con una nota. Estados de carga (esqueleto), error con reintento, vacío (sin cuentas
  activas) y éxito.
- **Generación de cargos**: el bloqueo `posting-accounts-incomplete` enlaza ahora al mapa (`href` por la constante compartida).
- **Contrato**: tres códigos de problema nuevos en el cliente (`duplicate-account-code`, `account-in-use`, `posting-account-invalid`).
- **Simulador** (`e2e/mock-api`): `ledger-seed`, `ledger-model` (con sus pruebas) y presets `ledger-map-empty`, `ledger-map-complete`, `ledger-empty`,
  `ledger-stale-version`, `ledger-forbidden`, `ledger-server-error`, `ledger-map-server-error`. Los códigos son ficticios y solo existen en el simulador.
- **Límite del contrato**: la sesión no entrega permisos a la interfaz, así que la pantalla no puede ocultar de antemano las acciones de escritura; se
  apoya en el 403 del servidor. Los selectores del mapa leen hasta 200 cuentas activas por tipo (tope de la API).
- **Pendiente**: recorridos de Playwright `ledger-chart-*`, estados de la matriz de accesibilidad (axe) de las dos vistas, y ejecutar Playwright (no se
  ejecutó); el simulador debe reiniciarse para tomar los presets nuevos. La preview de devengo del simulador sigue bloqueándose por su propio preset, no
  por el mapa del simulador.

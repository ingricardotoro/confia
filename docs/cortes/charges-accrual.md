# Corte: motor de devengo (charges)

Fase F3, corte 3 (`charges-accrual` de la propuesta de `docs/cortes/ledger-core.md`), entregable 7 del roadmap («Motor de
devengo idempotente», brecha B2). Es el corte de mayor riesgo de F3: es la primera escritura real en el libro mayor. Estado:
**diseño, pendiente de aprobación del propietario**. La revisión adversarial está en el apéndice A, las discrepancias con
documentos y código en el apéndice B y la propuesta de división en el apéndice C.

## Objetivo

Que un administrador genere, para un plan de cobro activo y un período, un cargo por estudiante y concepto con el precio y
el impuesto vigentes a la fecha, asentado en el libro mayor **en la misma transacción de base de datos**, de modo que
ejecutar el motor dos veces sobre el mismo período produzca el mismo conteo y cero duplicados (INV-14), y que el saldo del
estudiante se derive del libro.

## Fuera de alcance (decisiones del propietario ya tomadas y división delgada)

Becas y descuentos (diferidos: solo queda el punto de extensión); reverso de un cargo con `charges:void` y trabajo nocturno
de integridad (corte 3b, apéndice C); cargos manuales y ajustes (corte 4); estado de cuenta, cierre de período y caché
(corte 5); programación mensual automática (tarea recurrente); prorrateo; conversión de moneda; publicación de eventos.

## Modelo (migración `V32__create_charges_accrual.sql`)

Todas las tablas con `institution_id`, clave primaria `(institution_id, id)`, UUIDv7 de la aplicación, llaves compuestas
con la institución, RLS forzada como V4 a V31, códigos en inglés y mayúsculas.

- **`charges_charge`** (solo inserción): `student_id`, `enrollment_id`, `payment_concept_id`, `concept_category`
  (instantánea), `plan_id`, `plan_item_id`, `accrual_run_id`, `origin` (`AUTOMATIC`, `MANUAL`; este corte solo inserta
  `AUTOMATIC`), `period_start`, `period_end`, `period_label` (`2027-02`), `pricing_date` (= `period_start`), `due_on`,
  `price_list_item_id`, cuatro importes `NUMERIC(14,4)` cada uno con su moneda (`base_*`, `discount_*`, `tax_*`, `total_*`),
  `is_taxable`, `tax_rate_id`, `tax_rate_period_id`, `tax_rate_percent NUMERIC(7,4)` (instantáneas), `ledger_transaction_id`
  (no nulo, único, llave a `ledger_transaction`), `created_by`, `created_at`. **Sin columna de estado**: «pagado» y «vencido»
  se derivarán de las imputaciones (F4) y «anulado» de una tabla de anulaciones de solo inserción (corte 3b).
- **`charges_accrual_run`**: `plan_id`, `period_start`, `status`, `request_key` (único por institución, permanente),
  `request_hash`, `preview_digest` (opcional), `requested_by`, `queued_at`, `started_at`, `finished_at`, contadores
  (`students_evaluated`, `charges_created`, `charges_existing`, `charges_failed`) y `failure_code`. Solo cambian `status`,
  fechas, contadores y `failure_code` (permiso por columna y disparador de transiciones).
- **`charges_accrual_run_item`** (solo inserción): `run_id`, `student_id`, `payment_concept_id`, `outcome` (`CREATED`,
  `ALREADY_ACCRUED`, `ALREADY_ACCRUED_OTHER_PLAN`, `ERROR`), `error_code`, `charge_id`, `existing_charge_id`. Único
  `(run_id, student_id, payment_concept_id)`.
- **`charges_posting_account`**: el mapa de contabilización como **dato**: `purpose` (`RECEIVABLE`, `REVENUE`,
  `TAX_PAYABLE`), `concept_category` (solo en `REVENUE`; nulo = cuenta por defecto), `ledger_account_id`, `version`. Único
  por `(purpose, categoría)`. Nada del plan de cuentas está en código.

**Máquina de estados de la ejecución.** `QUEUED → RUNNING` (el trabajador la toma); `RUNNING → COMPLETED` (sin errores),
`COMPLETED_WITH_ERRORS` (al menos un renglón `ERROR`), `REJECTED` (precondición: plan no activo, mapa incompleto, período
futuro, vista previa desactualizada; **nada escrito**) o `FAILED` (reintentos agotados); `QUEUED → REJECTED`. **Prohibidas:**
toda salida de un estado terminal, `RUNNING → QUEUED`, `QUEUED → COMPLETED*`. Índice único parcial: una sola ejecución no
terminal por `(plan_id, period_start)`.

## Invariantes

| ID | Enunciado | Base de datos | Dominio o aplicación | Prueba |
|---|---|---|---|---|
| INV-08, 09, 10 | Importes no negativos; `total = base − descuento + impuesto`; descuento ≤ base. **En este corte descuento = 0** | `CHECK` sobre las columnas; las cuatro monedas iguales; todo importe en unidad menor | `ChargeAmounts` | `rejectsInconsistentTotals` |
| INV-13 | Un cargo automático referencia el precio que lo determinó, cuya vigencia cubre `pricing_date` y cuyo importe es la base | `CHECK (price_list_item_id IS NOT NULL OR origin = 'MANUAL')` y disparador `BEFORE INSERT` | Resolución de precio | `rejectsChargeWithPriceOutsideValidity` |
| INV-14 (reforzada, **INV-43**) | No existen dos cargos automáticos del mismo estudiante y concepto con períodos que se **solapan**, aunque vengan de planes o periodicidades distintas | `EXCLUDE USING gist (institution_id =, student_id =, payment_concept_id =, daterange(period_start, period_end, '[]') &&) WHERE (origin = 'AUTOMATIC')` | Comprobación previa bajo bloqueo | `rerunCreatesNothing`, `rejectsOverlapFromOtherPlan` |
| INV-44 (nueva) | Todo cargo tiene exactamente una transacción `CHARGE` de origen `charges` con `source_ref_id` = el cargo, cuyo débito a la subcuenta del estudiante es el total en la misma moneda | Disparador `BEFORE INSERT` (la transacción ya existe en la misma transacción de base de datos); `ledger_transaction_id` único | Orden: asiento, luego cargo | `rejectsChargeWithoutMatchingPosting` |
| INV-45 (nueva) | Concepto gravado: hay vigencia de tasa y `tax = round(base × pct / 100, 2)` con **un solo redondeo**; no gravado: impuesto cero y sin tasa. Nunca 0 % por falta de tasa | `CHECK` con `round(numeric, 2)` (mitad alejada de cero = `HALF_UP` para positivos) | `TaxCalculation` | `taxMatchesSingleRounding`, `taxableWithoutRateIsError` |
| INV-46 (nueva, extiende INV-12) | Una vigencia de precio o de tasa referenciada por un cargo no se cierra antes del `pricing_date` de ese cargo | Disparadores de V32 sobre `catalog_price_list_item` y `catalog_tax_rate_period` (`BEFORE UPDATE` de `valid_to`) | La ejecución lee esas filas `FOR SHARE` | `rejectsBackdatedClosureOfReferencedPrice` |
| INV-47 (nueva) | Un cargo no se actualiza, no se borra ni se vacía, tampoco por el dueño | Sin `UPDATE`/`DELETE` concedido y disparadores `BEFORE UPDATE OR DELETE` y `BEFORE TRUNCATE` | El repositorio solo inserta | `rejectsUpdateDeleteTruncateOfCharge` |
| INV-48 (nueva) | Una sola ejecución no terminal por plan y período; un estado terminal no cambia | Índice único parcial y disparador de transiciones | `AccrualRun` | `rejectsConcurrentRunForSamePeriod` |

Funciones de disparador con `SET search_path = pg_catalog, public`, `SECURITY INVOKER` y `REVOKE ALL ... FROM PUBLIC`.
Permisos: `confia_admin_app` con `SELECT, INSERT` en cargos y renglones, `SELECT, INSERT, UPDATE (columnas de estado)` en
ejecuciones y `SELECT, INSERT, UPDATE (ledger_account_id, version, updated_at)` en el mapa; `confia_readonly` con `SELECT`.

## El motor (dominio puro en `com.confia.charges.domain`; aplicación en `AccrueChargePlanPeriod`)

1. **Períodos del renglón.** Para `i = 0 … periods_count − 1`: inicio = `first_period_on + i × L` meses, fin = inicio + `L`
   meses − 1 día, con `L` = 1 (`MONTHLY`), 2 (`BIMONTHLY`), 12 (`ANNUAL`); `ONE_TIME` es un solo período de un mes y exige
   `periods_count = 1` (si no, `PLAN_ITEM_INVALID`). `due_on` = día `due_day_of_month` del mes de inicio (1 a 28, existe
   siempre). Una ejecución para el período `P` procesa los renglones con un período que empieza en `P`; el navegador nunca
   calcula períodos: los lista el servidor.
2. **Población.** Matrículas del año lectivo del plan (cualquier estado) que **se solapan con el período**
   (`enrolled_on <= period_end` y `withdrawn_on` nulo o `>= period_start`), cuya sección en la fecha de referencia
   `max(period_start, enrolled_on)` (reconstruida con `students_enrollment_transfer`) cae en el objetivo del plan (sección;
   grado de la sección; modalidad del grado). Dos matrículas del mismo estudiante en el período cuentan una vez (la más
   reciente). Cargo completo, sin prorrateo (decisión abierta 2). Un objetivo de sección de otro año lectivo rechaza la
   ejecución (`plan-target-outside-year`).
3. **Precio por fecha** (`pricing_date = period_start`): listas `ACTIVE` del año lectivo del plan en tres niveles, `GRADE`
   (grado del estudiante) sobre `MODALITY` sobre `INSTITUTION`. **Decide el nivel más específico que tiene algún precio del
   concepto** (en cualquier vigencia); si ese nivel no tiene vigencia que cubra la fecha, `NO_PRICE` (nunca se cae en
   silencio al nivel general); si dos listas del mismo nivel la cubren, `AMBIGUOUS_PRICE` (nunca se elige). La moneda del
   cargo es la del precio.
4. **Punto de extensión de beneficios.** `PricingPipeline`: `BasePrice → BenefitsStep → TaxableBase → Tax → Total`. En este
   corte `BenefitsStep` es la identidad (descuento cero). Los beneficios futuros se asentarán como una transacción `DISCOUNT`
   aparte (clave `charges:benefit:<chargeId>`) en la misma transacción de base de datos: ni la clave del cargo ni la forma
   de su asiento cambian.
5. **Impuesto por fecha.** No gravado: cero. Gravado: la tasa del concepto debe existir (`TAX_RATE_MISSING`), estar activa
   (`TAX_RATE_INACTIVE`) y tener una vigencia que cubra `pricing_date` (`TAX_RATE_NOT_IN_FORCE`). Cada caso es **error del
   cargo, registrado en la ejecución, nunca 0 %**.
6. **Redondeo.** La base ya está en dos decimales (`CHECK` de V28). Impuesto = `base × pct / 100` redondeado **una sola
   vez** a la unidad menor con `HALF_UP`; total = base + impuesto, exacto. Ningún redondeo intermedio ni acumulado: los
   totales de una ejecución son sumas exactas. Requiere una operación de `Money` que aplique el porcentaje y redondee
   directamente a la unidad menor (apéndice B: `percentage().roundToMinorUnit()` redondea dos veces).
7. **Otros errores del cargo:** `CONCEPT_INACTIVE`, `STUDENT_INACTIVE`, `ACCOUNT_UNDER_REVIEW`, `OVERLAPPING_CHARGE` (otro
   cargo automático con rango distinto que se solapa), `POSTING_FAILED`.

## Atomicidad, concurrencia e idempotencia

- **Unidad atómica: el estudiante; unidad de error: el cargo.** Por estudiante, una transacción del componente
  transaccional: (1) calcula sus cargos leyendo precio y vigencia de tasa `FOR SHARE`; (2) bloquea sus subcuentas
  `FOR UPDATE` en orden `(student_id, currency)` por el puerto del libro; (3) relee bajo bloqueo los cargos existentes
  (`ALREADY_ACCRUED*` u `OVERLAPPING_CHARGE`); (4) por cada cargo válido asienta y luego inserta el cargo; (5) inserta los
  renglones de la ejecución. Un cargo con error de cálculo se excluye antes de escribir; un error de base de datos revierte
  al estudiante entero y queda como `POSTING_FAILED`. Una ejecución con errores no es un fracaso: se corrige la
  configuración y se vuelve a ejecutar, y solo se crea lo que falta.
- **Puerto del libro en la transacción del llamador.** `LedgerPosting.post` abre hoy su propia transacción y reintenta por
  dentro; anidado, su reintento correría sobre una transacción ya abortada (apéndice B). Se agrega
  `LedgerPosting.postWithinCurrentTransaction` y `lockStudentAccounts`, que exigen una transacción activa y no reintentan;
  el reintento ante interbloqueo lo hace el `TransactionRunner` exterior, repitiendo al estudiante completo.
- **Claves.** Natural: `(student, concept, período)` con INV-43. Del libro: `charges:accrual:<chargeId>` (permanente).
  De la ejecución: cabecera `Idempotency-Key` guardada en `request_key` (permanente, con `request_hash`): misma clave y
  mismo cuerpo devuelven la ejecución existente; otro cuerpo, 422; otra clave con una ejecución no terminal del mismo plan y
  período, 409 `accrual-run-in-progress`. La tarea usa como instancia el identificador de la ejecución.
- **Reanudación.** Si el trabajador cae, db-scheduler retoma la ejecución, que salta a los estudiantes que ya tienen
  renglones en ella. Dos ejecuciones de planes distintos sobre el mismo estudiante se serializan por el bloqueo de su
  subcuenta; la segunda ve el cargo y registra `ALREADY_ACCRUED_OTHER_PLAN`.

## Asiento por cargo (tipo `CHARGE`, `effective_on = period_start`, origen `charges`)

**Debe** Cuentas por cobrar a estudiantes [estudiante, moneda] = total / **Haber** Ingresos de la categoría del concepto
= base / **Haber** Impuesto por pagar = impuesto (renglón omitido si es cero). Ejemplo ficticio con tasa ficticia de
10 %: Debe 1105 [E-000001, HNL] 1 650,00 / Haber 4105 1 500,00 / Haber 2105 150,00. Cuadra porque total = base + impuesto.
Reverso (corte 3b): espejo exacto por `LedgerPosting.reverse`. **Antes de producción, con el contador:** cuentas y
categorías, la cuenta del impuesto (el plan mínimo del corte 1 no la tiene), si el impuesto se reconoce al devengar o al
facturar (`confia-fiscal-compliance`), y si un cargo anual o bimestral exige ingreso diferido. El mapa es dato y absorbe
cualquiera de esas respuestas sin cambiar código.

## Disparo, API, permisos y pantalla

**Recomendación: endpoint que programa una tarea única de db-scheduler** en la misma transacción que crea la ejecución
`QUEUED`; solo `confia-worker` la ejecuta (ADR-0016 y la tabla de procesos de `docs/01-arquitectura.md`). Una ejecución síncrona en el proceso administrativo
sería más delgada pero contradice ADR-0016 y no se recupera de una caída. La tarea recurrente mensual queda para después.

| Ruta | Permiso | Resultado |
|---|---|---|
| `GET /api/v1/charges/plans/{id}/accrual-periods` | `charges:read` | Períodos del plan con su última ejecución |
| `POST /api/v1/charges/plans/{id}/accrual-preview` | `charges:create` | Vista previa sin escribir: conteos, totales por moneda, errores y hasta 500 renglones, más `previewDigest` |
| `POST /api/v1/charges/plans/{id}/accrual-runs` con `Idempotency-Key` | `charges:create` | 202 con la ejecución; `previewDigest` opcional: si el cálculo cambió, `REJECTED` con `preview-outdated` |
| `GET /api/v1/charges/accrual-runs` y `/{runId}` (renglones paginados) | `charges:read` | Estado, contadores y renglones |
| `GET`/`PUT /api/v1/charges/posting-accounts` | `charges:read` / `charges:update` | Mapa de contabilización con `version` |
| `GET`/`POST /api/v1/ledger/accounts` | `ledger:read` / `ledger:create` (**nuevo**, decisión 1) | Plan de cuentas; sin edición en este corte |

`charges:create`, `charges:read`, `charges:update` y `charges:void` ya existen en `Permission` y `StaffRole`. Solo cambia el
catálogo si se aprueba `ledger:create` (89 a 90, `StaffRole`, §5.2 de `docs/03-seguridad.md`, `PermissionMatrixParityTest`).
`ProcessBeanPolicy` debe permitir en el trabajador la configuración de `charges` y `ledger` y los directorios de `students`
y `catalog` que usa la tarea (hoy prohibidas). Puertos publicados nuevos (ADR-0022): `students` `EnrollmentRoster` y
`catalog` `PricingDirectory`.

**Pantalla:** en el detalle del plan, «Generar cargos»: elegir período, ver la vista previa, confirmar, y seguir la ejecución
(consulta periódica) hasta su resultado con sus renglones y errores. El mapa contable y el plan de cuentas quedan solo como
rutas en este corte.

**Auditoría** en la misma transacción: `charges.accrual_run.requested|started|finished` (plan, período, contadores, totales
por moneda, sin datos personales), `charges.posting_account.updated` y `ledger.account.created`; cada asiento ya escribe
`ledger.transaction.posted` con el cargo como origen. Eventos `ChargeAccrued` y `AccrualRunFinished` definidos y no
publicados (sin consumidor).

## Casos límite a probar (selectivos: dominio con jqwik y Testcontainers solo de `charges`)

Precio cero (la base lo impide), sin lista, solo lista institucional, de grado sobre institucional, dos listas del mismo
nivel, lista archivada, precio cambiado a mitad de año (cada período toma su vigencia), vigencia que termina el día anterior
al período; gravado sin tasa, con tasa inactiva, sin vigencia, y con vigencia que empieza o termina exactamente en
`pricing_date`; impuesto con punto medio (base 0,05 a 10 % = 0,005 → 0,01; base 1,00 a 0,4995 % = 0,004995 → 0,00, no
0,01); impuesto que redondea a cero (sin renglón); `due_day` 28 en febrero; `ONE_TIME` con más de un período; matrícula a
mitad de período, retiro el primer día, retiro y nueva matrícula en el mismo período, traslado de sección; dos planes con el
mismo concepto, igual y distinta periodicidad; concepto inactivo; plan borrador o archivado; período futuro; dos monedas en
un estudiante; subcuenta `UNDER_REVIEW`; dos ejecuciones simultáneas (misma y distinta clave), dos planes simultáneos sobre
un estudiante; caída a mitad y reanudación; repetición de la clave con otro cuerpo; cierre retroactivo de una vigencia
referenciada; `UPDATE`, `DELETE` y `TRUNCATE` de cargos por la aplicación y el dueño; RLS entre instituciones; rol sin
`charges:create` (403).

## Criterio de aceptación

En una base real con cuentas y tasa ficticias, el plan «Primaria 2027» con 30 estudiantes se ejecuta para febrero: 30
cargos de colegiatura con su impuesto y 30 transacciones cuadradas; ejecutarlo de nuevo crea 0 y registra 30
`ALREADY_ACCRUED`; un estudiante matriculado después se cobra en la tercera ejecución sin tocar a los demás; el saldo de
cada estudiante, derivado del libro, es igual a la suma de sus totales; la base rechaza editar o borrar un cargo; otra
institución no ve nada.

## Decisiones abiertas para el propietario

1. **Plan de cuentas y quién lo carga.** Recomendación: seis cuentas (las cinco del corte 1 más «Impuesto por pagar»),
   validadas con el contador antes de usar el corte con datos reales; las crea el Super Administrador con el permiso nuevo
   `ledger:create` (el corte 4 decide si el Administrador lo recibe para iniciar ajustes).
2. **Cobertura de la matrícula.** Recomendación: un estudiante con cualquier día matriculado en el período paga el período
   completo, sin prorrateo; el ajuste caso por caso llega con el reverso (3b) y los cargos manuales (4).
3. **¿Se puede volver a devengar un cargo anulado?** Recomendación: no; INV-43 incluye a los anulados y la corrección es
   anular y emitir un cargo manual (corte 4). Relajar la restricción después es compatible con los datos; endurecerla no.
4. **Reverso de cargo y trabajo nocturno de integridad en un corte 3b.** Recomendación: aceptarlo; el libro ya rechaza en
   la base descuadres, ediciones y borrados, y este corte ya es el más grande de F3.
5. **¿Desde cuándo se confirma un período?** Recomendación: desde su primer día en la zona horaria de la institución; la
   vista previa, cualquier día. Si se factura por adelantado, fijar cuántos días antes.

---

## Apéndice A. Revisión adversarial

| Ataque o falla | Respuesta del diseño |
|---|---|
| Cargo duplicado por doble clic, reintento o dos ejecuciones | Clave permanente en la ejecución, una ejecución no terminal por plan y período, bloqueo de la subcuenta, relectura bajo bloqueo e INV-43 en la base |
| Duplicado por dos planes o periodicidades distintas | INV-43 compara rangos de fechas, no etiquetas: mensual y bimestral que se solapan chocan |
| Cargo faltante | Toda exclusión queda como renglón `ERROR` con código; volver a ejecutar crea solo lo que falta; la vista previa lo muestra antes |
| Precio cambiado entre vista previa y confirmación | `previewDigest`: si el cálculo cambió, `REJECTED` sin escribir |
| Precio cambiado entre dos ejecuciones | Cada cargo guarda su precio; una ejecución nueva no toca lo emitido |
| Vigencia retroactiva | INV-46 impide cerrar una vigencia referenciada antes de la fecha del cargo; la fila se lee `FOR SHARE`, así el cierre espera y luego falla |
| Cambio de matrícula después del devengo | El cargo queda; se corrige con reverso (3b). El motor nunca borra ni ajusta solo |
| Deriva por redondeo | Base en dos decimales, un solo redondeo del impuesto verificado por `CHECK`, total exacto, sumas sin redondeo |
| Falla a medias | Estudiante atómico; cargo y asiento en la misma transacción (INV-44); reanudación idempotente |
| Repetición con otra carga | 422 por `request_hash`; en el libro, `ledger-idempotency-conflict` |
| Escalada de privilegios | Permisos en el filtro por ruta; institución tomada de la sesión y de la ejecución, nunca del cuerpo; el trabajador verifica que la institución de la tarea es la de la ejecución; RLS forzada |
| Precedencia equivocada de listas | Nivel más específico con el concepto; ambigüedad y hueco son errores, nunca elección ni caída silenciosa |
| Impuesto a la fecha equivocada | Una sola fecha (`pricing_date`) para precio y tasa, guardada en el cargo con la vigencia usada |
| Concurrencia con ediciones de precios | `FOR SHARE` sobre precio y vigencia de tasa dentro de la transacción del estudiante |
| Reloj y zona horaria | Períodos y vencimientos son `DATE`; «hoy» se calcula en la zona de la institución; `posted_at` lo pone la base |
| Bloqueos largos o tiempo agotado | Transacción corta por estudiante, `lock_timeout` del componente, nunca dos estudiantes en una transacción, ejecución fuera del proceso que atiende peticiones |
| Plan de cuentas mal configurado | Precondición de la ejecución: cuenta por cobrar con `holds_student_balances`, ingreso `INCOME`, impuesto `LIABILITY`, activas; si no, `REJECTED` |
| Alteración directa de la tabla | Sin permisos y disparadores también para el dueño; INV-44 liga cargo y asiento |

## Apéndice B. Discrepancias encontradas (a alinear al aprobar este corte)

- `LedgerPosting.post` y `reverse` abren su propia transacción con `TransactionRunner` (propagación por defecto `REQUIRED`) y
  reintentan por dentro (interbloqueo y carrera de clave). Llamados dentro de otra transacción, se unen a ella y el
  reintento correría sobre una transacción de PostgreSQL ya abortada. Hace falta la variante en la transacción del llamador.
- `confia-money-rules` §4 calcula el impuesto con `percentage(...)` (redondea a escala 4) y luego `roundToMinorUnit` (a 2):
  doble redondeo, alcanzable con tasas de cuatro decimales (0,004995 da 0,01 en lugar de 0,00). Hace falta una operación de
  `Money` con un solo redondeo a la unidad menor (cambio del módulo `kernel`).
- `docs/02` §7: `ChargeGenerated` lo consume `Ledger` para asentar; aquí se asienta en la misma transacción (corte 1).
- `docs/02` INV-14: índice parcial que excluye cargos `reversed` y etiqueta de período; aquí rangos de fechas e incluye a los
  anulados (decisión 3). `docs/02` §3.4 y §6.1 guardan `status` (`pending`, `paid`, `overdue`); aquí no hay columna de estado.
- `docs/02` §3.3 pone `accountMappingId` en el concepto (no construido); aquí el mapa va por categoría en `charges`.
- El plan de cuentas mínimo del corte 1 no tiene cuenta para el impuesto.
- V31 y `ChargePlanItem` admiten `ONE_TIME` con hasta 36 períodos; el motor lo trata como error. Conviene exigir 1 al
  agregar el renglón.
- `ProcessBeanPolicy` prohíbe `charges` y `ledger` en el trabajador, y la arquitectura pone la generación de cargos en él.
- Invariantes nuevas INV-43 a INV-48 para `docs/02` §5 (después de las INV-37 a 42 del corte 1, aún sin pasar a ese documento).

## Apéndice C. Ajuste de los cortes de F3

3a. **charges-accrual** (este): motor, ejecución en el trabajador, asiento, vista previa, mapa y plan de cuentas como rutas,
pantalla de generación. Si resulta grande, se parte en **3a-1** (backend, contrato y pruebas, demostrado con la batería
contra PostgreSQL) y **3a-2** (pantalla). 3b. **charges-void-and-integrity**: `charges:void` con motivo, tabla de
anulaciones de solo inserción, reverso por el puerto, lista de cargos del estudiante y trabajo nocturno de integridad.
4 y 5 sin cambios.

---

## Decisiones del propietario

Tomadas antes de construir el corte 3a-1 (solo backend, sin pantallas); sustituyen la recomendación de la sección «Decisiones
abiertas» donde difieren.

1. **Matrícula a mitad de período.** Un estudiante matriculado **cualquier día** del período paga el período **completo**. No hay
   prorrateo (decisión abierta 2 aceptada).
2. **Becas y descuentos.** Diferidos. Solo queda el punto de extensión `BenefitsStep` (identidad: descuento cero); las cuatro
   columnas de importe del cargo existen, con descuento cero, para que su forma no cambie después.
3. **Plan de cuentas (decisión abierta 1).** Seis cuentas: las cinco del corte 1 más «Impuesto por pagar». Se carga como **dato**
   (`ledger_account` y el mapa `charges_posting_account`), nunca como código. El permiso nuevo `ledger:create` y su ruta de carga
   (`GET`/`POST /api/v1/ledger/accounts` y `GET`/`PUT /api/v1/charges/posting-accounts`) **no se construyen en este paso**: el
   motor solo lee el mapa. Mientras tanto la carga es una ruta documentada de siembra por administración (SQL como propietario de
   la base, con los mismos `CHECK` y disparadores), que usan las pruebas. Pendiente para el siguiente paso del corte 3a: el
   permiso `ledger:create` (catálogo del núcleo, solo Super Administrador, §5.2 de `docs/03-seguridad.md`, paridad de la matriz) y
   las rutas.
4. **Cargo anulado (decisión abierta 3).** No se vuelve a devengar: INV-43 lo incluye aunque esté anulado. La corrección es
   anular y emitir un cargo manual (corte 4).
5. **Reverso y trabajo nocturno de integridad (decisión abierta 4).** Corte 3b. Este paso no construye `charges:void`.
6. **Desde cuándo se confirma un período (decisión abierta 5).** Solo desde su primer día, en la zona horaria de la institución.
   La vista previa se puede pedir cualquier día. Lo comprueba el trabajador al tomar la ejecución (`period-in-future`).
7. **Pregunta fiscal: PENDIENTE, no decidida.** Si el impuesto se reconoce al devengar o al facturar, y si un cargo anual o
   bimestral exige ingreso diferido, lo responden el contador y `confia-fiscal-compliance`. El mapa de contabilización es dato y
   absorbe cualquier respuesta sin cambiar código. **Antes de producción** deben validarse con el contador las cuentas, las
   categorías, la cuenta del impuesto y esta pregunta.

## Construido (backend, corte 3a-1)

- **`Money.percentageToMinorUnit(Percentage, RoundingMode)`** (módulo `kernel`): aplica el porcentaje y redondea **una sola vez**
  a la unidad menor. `percentage().roundToMinorUnit()` redondea dos veces (1,00 al 0,4995 % daba 0,01 en lugar de 0,00). No cambia
  ninguna operación existente. Prueba: `MoneyPercentageToMinorUnitTest` (cero, negativo, punto medio 0,005, escala, moneda, rango).
- **Libro en la transacción del llamador.** `LedgerPosting.postWithinCurrentTransaction`, `reverseWithinCurrentTransaction` y
  `lockStudentAccounts`: exigen una transacción activa (`TransactionRunner.isTransactionActive`), no abren ninguna, no aplican
  contexto de seguridad propio y **no reintentan**; el reintento ante interbloqueo lo hace el `TransactionRunner` exterior,
  repitiendo al estudiante completo. `post` y `reverse` no cambian. Interfaz pública nueva `ledger.application.api`
  (`LedgerPostingApi`, `LedgerAccountDirectory`), con tipos planos: el dominio del libro no cruza el módulo (ADR-0022, consumidor
  `charges`).
- **Migración `V32__create_charges_accrual.sql`:** `charges_charge` (solo inserción: cuatro importes con su moneda, copias de
  categoría, tasa y porcentaje, `ledger_transaction_id` obligatorio y único, `EXCLUDE` por rangos de fechas para INV-14/43,
  `CHECK` del impuesto con `round(numeric, 2)`, disparador `BEFORE INSERT` que exige precio vigente y la transacción `CHARGE`
  del libro creada en la misma transacción con **un** débito igual al total; disparadores de solo inserción con `TRUNCATE`,
  también para el dueño), `charges_accrual_run` (máquina de estados por disparador, un solo no terminal por plan y período,
  clave de la petición permanente), `charges_accrual_run_item` (solo inserción, único por ejecución, estudiante y concepto),
  `charges_posting_account` (mapa como dato), disparadores de INV-46 sobre las vigencias de precio y de tasa, RLS forzada y
  permisos mínimos.
- **Dominio puro** (`charges.domain`): `AccrualPeriods` (calendario), `PriceResolver` (nivel más específico con el concepto;
  hueco = `NO_PRICE`, empate = `AMBIGUOUS_PRICE`), `ChargeTax` (un solo redondeo; sin tasa, inactiva o fuera de vigencia es error,
  nunca 0 %), `ChargeAmounts`, `ChargeEvaluator` con `BenefitsStep`, `AccrualRun` y `RunStatus`, `PostingAccountMap`.
- **Aplicación:** `ManageAccrual` (periodos, vista previa con `previewDigest`, petición idempotente, lectura), `ExecuteAccrualRun`
  (inicio con precondiciones, una transacción por estudiante, cierre) y `AccrualRunJobHandler`. Por estudiante: precio y tasa
  `FOR SHARE`, subcuentas `FOR UPDATE` en orden `(estudiante, moneda)`, relectura de cargos bajo el bloqueo, asiento y luego cargo.
  Un error de base de datos revierte al estudiante entero y queda `POSTING_FAILED`.
- **Población y matrícula.** `students_enrollment.section_id` guarda solo la sección **actual**: `EnrollmentRoster` reconstruye la
  sección a la fecha `max(inicio del período, matrícula)` con `students_enrollment_transfer` (la sección en esa fecha es el
  `from_section_id` de la primera transferencia posterior). Dos matrículas del estudiante en el período cuentan una (la más
  reciente). Publicaciones nuevas (ADR-0022): `students.application.directory.EnrollmentRoster`,
  `catalog.application.directory.PricingDirectory` y `organization.application.directory.PlacementDirectory`.
- **Trabajo en segundo plano (ADR-0016).** La petición crea la ejecución `QUEUED` y programa la tarea `charges.accrual-run` (solo
  el identificador de la ejecución) en la misma transacción; solo `confia-worker` la ejecuta. Cinco intentos, espera
  exponencial; al agotarse, la ejecución queda `FAILED` (`retries-exhausted`) y la tarea parqueada. `ProcessBeanPolicy` se
  cambió **a propósito**: el trabajador ahora admite los paquetes de aplicación e infraestructura de `charges` y `ledger` y la
  infraestructura de `organization`, `students` y `catalog` (solo los directorios de lectura y el libro); siguen prohibidos sus
  controladores, sus casos de uso de registro y los adaptadores con llaves o con el token del personal.
- **API** bajo `/api/v1/charges`: `GET plans/{id}/accrual-periods` (`charges:read`), `POST plans/{id}/accrual-preview` y `POST
  plans/{id}/accrual-runs` con `Idempotency-Key` (`charges:create`, 202), `GET accrual-runs` y `GET accrual-runs/{runId}`
  (`charges:read`). Códigos nuevos: `accrual-run-in-progress` (409) y `plan-item-invalid` (409). La clave no usa `@IdempotentWrite`
  (la regla de arquitectura lo reserva al borde web): se valida en el caso de uso, falta = `idempotency-key-missing`, y se guarda
  permanente en la ejecución, no en la tabla de 24 horas del borde.
- **Pruebas:** `AccrualPeriodsTest`, `PriceResolverTest`, `ChargeEvaluatorTest` (incluye propiedad jqwik del impuesto),
  `AccrualRunTest`, `AccrualEngineIT` (base real: criterio de aceptación con 30 estudiantes, repetición sin duplicados, matrícula
  tardía, errores del cargo, precondiciones rechazadas, falla parcial y reanudación, dos peticiones simultáneas, dos planes, rechazos de
  la base a `UPDATE`/`DELETE`/`TRUNCATE` también del dueño, exclusión de solapes, redondeo del impuesto, asiento que no cuadra,
  vigencias referenciadas, máquina de estados y RLS) y `AccrualEndpointsIT` (las cinco rutas en el proceso administrativo real, la
  clave faltante, el rol sin permiso y el aislamiento).
- **Pendiente:** pantalla de generación (corte 3a-2); `GET`/`PUT /charges/posting-accounts` y `GET`/`POST /ledger/accounts` con
  `ledger:create`; reverso de cargo y trabajo nocturno de integridad (corte 3b); decisión fiscal del contador.

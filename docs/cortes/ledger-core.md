# Corte: núcleo del libro mayor (ledger)

Fase F3, entregables 1 a 4 («Libro mayor de doble partida», «Asientos inmutables», «Reversos», «Derivación del saldo»).
Primer corte de F3 y del módulo `ledger`. El roadmap pide revisión adversarial del diseño **antes** de escribir código: está
en el apéndice A, y las discrepancias entre documentos de referencia, en el apéndice B. Estado: **backend construido** (ver «Construido (backend)» al final); sin rutas ni pantalla.

## Objetivo

Dejar construido y probado contra PostgreSQL real el núcleo que todo F3 a F5 usará: cuentas contables, cuenta del
estudiante por moneda, transacciones de doble partida que la base de datos se niega a guardar descuadradas, editadas o
borradas, reverso único y saldo derivado. Ningún módulo escribe aún en el libro: el corte entrega el puerto de aplicación
que usarán `charges` (corte 3) y `payments` (F4).

## Fuera de alcance

Planes de cobro, devengo, cargos, pagos, imputación, documentos fiscales, ajustes manuales con aprobación, estado de
cuenta y pantallas; caché de saldo; trabajo nocturno de integridad (corte 3); cierre de período; conversión de moneda (no
hay fuente de tasa registrable, así que una transacción con dos monedas se rechaza siempre); `charge_id` en el asiento
(lo agrega el corte 3 como columna anulable).

## Decisiones de modelo

1. **Plan de cuentas más subcuenta del estudiante, no una cuenta contable por estudiante.** `ledger_account` es el plan
   de cuentas de la institución (pocas filas, estables, exportables a contabilidad). El estudiante es una **dimensión del
   asiento**: `ledger_student_account`, una por estudiante y moneda (INV-33), que solo pueden llevar los asientos de
   cuentas marcadas `holds_student_balances` (cuentas por cobrar). Una cuenta contable por estudiante inflaría el plan
   con datos maestros y rompería la exportación contable.
2. **Por estudiante y moneda, creada de forma perezosa** en la misma transacción del primer asiento
   (`INSERT ... ON CONFLICT DO NOTHING` y lectura con bloqueo). No depende de un evento de matrícula que podría perderse
   o llegar tarde, y la moneda de la subcuenta fija la de sus asientos.
3. **La subcuenta va en el asiento, no en la cabecera** (como ADR-0007). Una transferencia de saldo a favor entre
   hermanos (F4) toca dos subcuentas en una sola transacción cuadrada.
4. **El libro no redondea: rechaza.** Un importe con más de dos decimales se rechaza; redondea quien calcula (cargo,
   factura) una sola vez, en su punto de emisión. Así el libro nunca crea ni pierde un centavo.
5. **Cuadre verificado por cabecera, no por asiento.** Un disparador de restricción diferido sobre `ledger_transaction`
   comprueba al `COMMIT` cuadre, mínimo de dos asientos, una sola moneda y, en un reverso, el espejo exacto. Es correcto
   porque INV-37 impide agregar asientos en otra transacción de base de datos; detecta además la cabecera sin asientos,
   que el disparador por asiento de ADR-0007 no ve, y evita su costo cuadrático.

## Modelo y tablas (migración `V30__create_ledger_core.sql`)

Todas con `institution_id`, clave primaria `(institution_id, id)`, UUIDv7 generado por la aplicación, llaves foráneas
compuestas con la institución, RLS forzada con la política de institución de V4 a V29 y códigos en inglés y mayúsculas.

- `ledger_account`: `code` (único por institución), `name`, `account_type` (`ASSET`, `LIABILITY`, `EQUITY`, `INCOME`,
  `EXPENSE`), `holds_student_balances` (solo `ASSET` o `LIABILITY`), `is_active`, `version`, `created_at`, `updated_at`.
  Código, tipo y bandera no cambian nunca (disparador).
- `ledger_student_account`: `student_id` (llave a `students_student`), `currency` (`HNL`, `USD`), `status` (`OPEN`,
  `UNDER_REVIEW`), `opened_at`. Únicos `(institution_id, student_id, currency)` y `(institution_id, id, currency)`.
  Sin columna de saldo de ningún tipo.
- `ledger_transaction`: `kind` (`CHARGE`, `DISCOUNT`, `PAYMENT`, `LATE_FEE`, `CREDIT_NOTE`, `REVERSAL`, `ADJUSTMENT`,
  `WRITE_OFF`), `effective_on DATE`, `posted_at TIMESTAMPTZ`, `posting_xid XID8`, `source_module`, `source_ref_id`,
  `reverses_transaction_id`, `reason` (20 a 500 caracteres, obligatorio en `REVERSAL` y `ADJUSTMENT`),
  `idempotency_key`, `payload_hash` (SHA-256 hexadecimal), `created_by`. Sin descripción libre: la etiqueta del estado
  de cuenta se deriva del origen.
- `ledger_entry`: `transaction_id`, `line_number`, `account_id`, `student_account_id` (anulable), `side` (`DEBIT`,
  `CREDIT`), `amount NUMERIC(14,4)`, `currency`. Llave `(institution_id, student_account_id, currency)` hacia la subcuenta.

Ejemplo con cuentas ficticias de prueba. Devengo: **Debe** 1105 Cuentas por cobrar [E-000001, HNL] 1 500,00 / **Haber**
4105 Ingresos por colegiatura 1 500,00. Reverso: **Debe** 4105 1 500,00 / **Haber** 1105 [E-000001, HNL] 1 500,00.

## Invariantes y punto de aplicación

| ID | Enunciado | Base de datos | Dominio o aplicación | Prueba |
|---|---|---|---|---|
| INV-01, 02 | Debe igual a haber, al menos dos asientos | Disparador diferido por cabecera (suma en `numeric` sin límite) | Fábrica de `LedgerTransaction` | `rejectsUnbalancedTransactionAtCommit`, `rejectsHeaderWithoutEntries` |
| INV-03 | Importe mayor que cero; el signo lo da `side` | `CHECK (amount > 0)` | `Money.isPositive` | `rejectsZeroAndNegativeEntry` |
| INV-04 | Nada se actualiza ni se borra | Sin `UPDATE`/`DELETE` concedido y disparadores `BEFORE UPDATE OR DELETE` y `BEFORE TRUNCATE` que rechazan **a todo rol, incluido el dueño** | El repositorio solo inserta y lee | `rejectsUpdateDeleteTruncateForAppAndOwner` |
| INV-05 | Un original se reversa una vez | Índice único parcial sobre `reverses_transaction_id`; `CHECK ((kind = 'REVERSAL') = (reverses_transaction_id IS NOT NULL))` | `TransactionAlreadyReversed` (409) | `rejectsSecondReversalIncludingConcurrent` |
| INV-06 | Una moneda por transacción, igual a la de la subcuenta | Disparador diferido más llave compuesta con `currency` | `Money` lanza ante monedas distintas | `rejectsMixedCurrencies`, `rejectsEntryInOtherCurrencyThanStudentAccount` |
| INV-07 | El saldo es función de los asientos | Ninguna columna de saldo; prueba de catálogo | `LedgerQueries.balanceOf` | `noBalanceColumnExists` |
| INV-24 | Una clave ya vista no produce otro efecto | `UNIQUE (institution_id, idempotency_key)`, **permanente** | Misma huella: devuelve el original; otra: `ledger-idempotency-conflict` | `replaysSamePayload`, `rejectsChangedPayload` |
| INV-33 | Una subcuenta por estudiante y moneda | Índice único | Creación perezosa idempotente | `opensOneAccountUnderConcurrency` |
| INV-37 (nueva) | Los asientos se insertan solo en la transacción de base de datos que creó la cabecera | `posting_xid` forzado a `pg_current_xact_id()`; el disparador del asiento lo compara | | `rejectsEntryAppendedToPostedTransaction` |
| INV-38 (nueva) | Un reverso es espejo exacto del original (mismas cuentas, subcuentas, importes y moneda, lado contrario), no reversa a otro reverso y su fecha efectiva no es anterior a la del original | Disparadores de cabecera (inmediato) y diferido | `LedgerTransaction.mirror()` | `rejectsNonMirrorReversal`, `rejectsReversalOfReversal` |
| INV-39 (nueva) | Un asiento lleva subcuenta si y solo si su cuenta tiene `holds_student_balances`; la cuenta está activa | Disparador del asiento | Validación del dominio | `rejectsReceivableWithoutStudent` |
| INV-40 (nueva) | Todo importe del libro está en la unidad menor de su moneda | `CHECK (amount = round(amount, 2))` | Rechazo, nunca redondeo | `rejectsThirdDecimal` |
| INV-41 (nueva) | Solo el módulo de origen reversa su transacción | | `reverse()` compara `source_module` | `rejectsReversalFromForeignModule` |
| INV-42 (nueva) | `posted_at` y `posting_xid` los fija la base, nunca quien llama | Disparador `BEFORE INSERT` que los sobrescribe con `clock_timestamp()` | | `ignoresCallerPostedAt` |

Cada función de disparador lleva `SET search_path = pg_catalog, public`, es `SECURITY INVOKER` y tiene `REVOKE ALL ... FROM
PUBLIC`. Permisos: `confia_admin_app` con `SELECT, INSERT` sobre transacción, asiento y subcuenta, `UPDATE (status)` sobre
la subcuenta (lo exige `FOR UPDATE`; un disparador impide cualquier otro cambio) y `UPDATE (name, is_active, version,
updated_at)` sobre la cuenta contable; `confia_readonly` con `SELECT`; `confia_portal_app` sin permisos (F8).

## Saldo

`saldo(subcuenta, al) = Σ debe − Σ haber` de los asientos de esa subcuenta con `effective_on <= al`: positivo es deuda,
negativo es saldo a favor. Opcionalmente «tal como se conocía» (`posted_at <= instante`). Consulta en jOOQ con
parámetros, índice `(institution_id, student_account_id)` que incluye `side`, `amount` y `transaction_id`. Orden canónico
de un historial: `(effective_on, posted_at, id)`. La caché de saldo queda fuera; su punto de extensión es una tabla aparte
`ledger_balance_cache`, actualizada en la misma transacción, reconstruida cada noche y **nunca corregida sola**.

## Concurrencia e idempotencia

Puerto público `LedgerPosting.post(...)` y `reverse(...)`, siempre dentro del componente transaccional de `shared/security`
(con `lock_timeout` y reintento ante interbloqueo): (1) busca la clave de idempotencia y compara `payload_hash`; (2) crea
las subcuentas que falten; (3) bloquea con `FOR UPDATE` las subcuentas afectadas en orden `(student_id, currency)`;
(4) rechaza la operación si alguna está `UNDER_REVIEW`; (5) inserta cabecera y asientos y escribe la auditoría; (6) en el
`COMMIT` se verifican los disparadores diferidos. Una clave igual en curso espera en el índice único y luego devuelve el
original. La huella es SHA-256 de la forma canónica (tipo, fecha, origen, asientos ordenados). La clave es permanente y
la eligen los módulos de origen (`charges:accrual:<chargeId>`); en los endpoints futuros se combina con la cabecera
`Idempotency-Key` del `IdempotentExecutor` existente (veinticuatro horas, 422 ante otra carga).

## API y permisos

**Recomendación: el corte 1 es backend y pruebas, sin rutas HTTP ni pantalla.** La primera escritura real llega con el
corte 3 y la lectura con su pantalla en el corte 5. Rutas previstas, para fijar permisos desde ahora:

| Ruta | Permiso | Corte |
|---|---|---|
| `GET /api/v1/ledger/students/{studentId}/balances?asOf=` | `ledger:read` | 5 |
| `GET /api/v1/ledger/students/{studentId}/transactions` (página, moneda, rango) | `ledger:read` | 5 |
| `POST /api/v1/ledger/adjustments` con `Idempotency-Key` | `ledger:create` (**no existe**) y aprobación con `ledger:approve` | 4 |
| `POST /api/v1/ledger/transactions/{id}/reversal` con `Idempotency-Key`, solo `source_module = ledger` | `ledger:void` | 4 |

El catálogo `Permission` ya tiene `ledger:read|void|approve|export`. Falta `ledger:create`: agregarlo cambia el catálogo
(89 a 90), `StaffRole`, la matriz de `docs/03-seguridad.md` §5.2 y `PermissionMatrixParityTest` (pendiente Q4 de §5.3).
El reverso de un cargo nunca pasa por la ruta del libro: lo pide `charges` con `charges:void` (INV-41).

## Auditoría y eventos

Auditoría en la misma transacción: `ledger.transaction.posted` (identificador, tipo, origen, moneda, total, número de
asientos y `contentHash`), `ledger.transaction.reversed` (reverso, original, motivo) y `ledger.student_account.opened`.
Sin datos personales. Eventos definidos y **no publicados en este corte** porque aún no tienen consumidor:
`LedgerTransactionPosted` y `LedgerTransactionReversed` (identificadores, tipo, origen, fecha efectiva; el consumidor
recalcula importes; idempotentes por `transactionId`).

## Casos límite a probar (Testcontainers con PostgreSQL 18 y pruebas de dominio con jqwik)

Importe cero, negativo, con tres decimales y en el máximo de `NUMERIC(14,4)`; descuadre de un centavo; cabecera sin
asientos y con uno; dos monedas; subcuenta en otra moneda; cuenta por cobrar sin estudiante e ingreso con estudiante;
cuenta inactiva; asiento agregado a una transacción ya registrada; reverso exacto (saldo vuelve a cero), no espejo,
segundo reverso (secuencial y concurrente), reverso de reverso, reverso con fecha anterior, de otra institución y de otro
módulo; `UPDATE`, `DELETE` y `TRUNCATE` por la aplicación y por el dueño; repetición con la misma carga (un solo
efecto) y con otra (conflicto); dos claves iguales concurrentes; dos asientos concurrentes sobre la misma subcuenta
(saldo igual a la suma); dos transacciones sobre las subcuentas A y B en orden opuesto (sin interbloqueo); primera
apertura concurrente de la subcuenta; RLS: otra institución no lee ni inserta; saldo `asOf` que ignora lo posterior;
saldo a favor negativo; estudiante inactivo (el libro lo acepta: valida el módulo de origen).

## Criterio de aceptación

En una base real, con cuentas ficticias: se registra el devengo de L 1 500,00 a E-000001 y el saldo es 1 500,00; repetir
la misma clave no crea nada; se reversa y el saldo es 0,00 con original y reverso visibles; un segundo reverso, un
asiento descuadrado, uno agregado después y cualquier `UPDATE`, `DELETE` o `TRUNCATE`, incluso del dueño, los rechaza la
base de datos; otra institución no ve nada.

## Decisiones abiertas para el propietario

1. **Plan de cuentas mínimo, validado con el contador antes del corte 3** (no bloquea este corte). Recomendación: cinco
   cuentas (cuentas por cobrar a estudiantes, ingresos por colegiatura, por matrícula, por otros conceptos y caja o
   bancos), con el saldo a favor como saldo negativo de la misma cuenta por cobrar; si el contador lo exige como pasivo,
   se reclasifica en F4.
2. **Corte 1 sin rutas ni pantalla.** Recomendación: aceptarlo; la demo es la batería de pruebas contra PostgreSQL real.
3. **Permiso `ledger:create` para el ajuste manual**: lo inicia el Administrador y lo aprueba el Super Administrador, con
   aprobador distinto. Recomendación: agregarlo en el corte 4.
4. **Fechas retroactivas y cierre de período.** Recomendación: nunca fecha futura; retroactivo permitido mientras no
   exista cierre, y un cierre mensual por institución en el corte 5, antes de producción.
5. **Saldos iniciales al salir a producción.** Recomendación: un noveno tipo `OPENING_BALANCE` contra una cuenta de
   patrimonio, cargado solo por el módulo `imports` por el mismo puerto, con clave `imports:opening:<studentId>:<moneda>`;
   nunca por migración. Agregarlo ahora cuesta una línea del `CHECK`.

## Propuesta de cortes de F3

1. **ledger-core** (este): núcleo, invariantes y puerto, sin rutas.
2. **charges-plans**: planes de cobro con alcance, periodicidad y día de vencimiento, con pantalla; sin libro.
3. **charges-accrual**: motor de devengo idempotente (INV-14) que escribe en el libro por el puerto **en la misma
   transacción**, reverso de un cargo con `charges:void`, trabajo nocturno de integridad y plan de cuentas aprobado.
4. **charges-manual-and-adjustments**: cargos manuales y ajustes del libro con motivo, aprobación y `ledger:create`.
5. **ledger-account-statement**: estado de cuenta con pantalla, saldo a fecha, reconstrucción de doce meses, cierre de
   período y la decisión sobre la caché.

---

## Apéndice A. Revisión adversarial

| Ataque o falla | Respuesta del diseño |
|---|---|
| Falsificar un asiento agregándolo a una transacción antigua (un par cuadrado no altera el cuadre) | INV-37: el asiento solo entra en la transacción de base de datos que creó la cabecera |
| Editar o borrar con el rol de la aplicación | Sin permiso y con disparadores de rechazo |
| Editar, borrar o vaciar como dueño (`confia_owner`) | Disparadores de fila y `BEFORE TRUNCATE` aplican también al dueño |
| El dueño deshabilita o elimina un disparador | Riesgo residual: el dueño puede alterar el esquema. Mitigación: la aplicación nunca se conecta como dueño; prueba de catálogo que exige cada disparador presente y habilitado; revisión estática de migraciones que rechaza `DISABLE TRIGGER`, `DROP TRIGGER` y `session_replication_role` sobre tablas `ledger_`; el `contentHash` en la bitácora encadenada permite detectar después una alteración; el superusuario del proveedor queda fuera del modelo |
| Escalar por una función `SECURITY DEFINER` o secuestrar `search_path` | El módulo no tiene funciones definer; los disparadores fijan `search_path` y no son ejecutables por `PUBLIC` |
| Cabecera sin asientos | Disparador diferido por cabecera (INV-02) |
| Desviación del saldo | No existe columna de saldo; la caché futura se reconstruye y nunca se corrige sola |
| Desviación por fecha retroactiva | Doble fecha (`effective_on`, `posted_at`), saldo «tal como se conocía» y cierre de período (decisión 4) |
| Falla a medias o caída antes del `COMMIT` | Cabecera, asientos y auditoría en una sola transacción; el cuadre se verifica al confirmar. Si la respuesta se pierde tras confirmar, el reintento con la misma clave devuelve el original |
| Reloj y orden | `posted_at` lo pone la base; el orden desempata con el UUIDv7 y ninguna invariante depende de un reloj monótono. `effective_on` se calcula en la zona horaria de la institución |
| Mezcla de monedas | Disparador de una moneda, llave compuesta con la subcuenta y `Money` que lanza; sin tasa registrable no hay conversión |
| Deriva por redondeo | El libro rechaza más de dos decimales y nunca redondea; las sumas son exactas |
| Cadenas de reverso | Uno por original, ninguno sobre un reverso, espejo verificado, fecha no anterior y solo del módulo dueño. Un reverso erróneo se corrige registrando de nuevo el hecho original con otra clave |
| Reverso desde el libro que desincroniza el cargo | INV-41: solo el módulo de origen reversa |
| Repetición con otra carga | Huella distinta, conflicto y ningún efecto; la clave del libro no caduca, a diferencia de la de veinticuatro horas del HTTP |
| Bloqueo imposible por falta de privilegio | `FOR UPDATE` exige `UPDATE` en alguna columna: se concede solo `UPDATE (status)` |
| Interbloqueo | Orden fijo `(student_id, currency)`, idempotencia antes de bloquear y espera acotada |
| Fuga entre instituciones | RLS forzada, institución tomada de la sesión y llaves foráneas compuestas, también para el dueño |
| Desborde | Asiento hasta 9 999 999 999,99; los disparadores suman en `numeric` sin límite. Los totales institucionales de varios años podrían superar el máximo de `Money`: los reportes agregan en la base |
| Transacción gigante | Tope de cien asientos en el dominio |
| Carga histórica por migración | Prohibida. Solo por el puerto (decisión 5) |
| Datos personales en el libro | Sin texto libre salvo el motivo, que no se expone al portal; eventos sin datos personales; la anonimización toca al estudiante, no al libro |

## Apéndice B. Discrepancias en documentos de referencia (a alinear al aprobar este corte)

- `docs/02` §3.5 pone `studentAccountId` en la cabecera y `cachedBalance` en `StudentAccount`; la skill
  `confia-ledger-invariants` pone `account_id` en la cabecera. ADR-0007 (aceptado) pone el estudiante en el asiento y la
  caché en tabla aparte: se sigue ADR-0007.
- ADR-0007 verifica el cuadre por asiento; aquí se verifica por cabecera, con INV-37 (decisión 5).
- INV-04 exige solo ausencia de permiso para la aplicación; aquí también hay disparadores contra el dueño.
- `openspec/specs/ledger/spec.md` (histórico) dice que la verificación nocturna «corrige el valor cacheado», en contra de
  ADR-0007 y de la skill.
- `docs/14-glosario.md` describe `LedgerEntry` con campos debe y haber y el reverso como `ReversalEntry`; el modelo usa
  `side` y `kind = REVERSAL`. Términos nuevos: cuenta contable (`LedgerAccount`), subcuenta del estudiante
  (`StudentAccount`, no es un saldo), lado (`EntrySide`), fecha efectiva (`effectiveOn`, no es la fecha de registro),
  fecha de registro (`postedAt`) y huella de contenido (`payloadHash`).
- `docs/02` §5 recibe INV-37 a INV-42.


---

## Decisiones del propietario (corte 1)

1. **Corte 1 solo backend y pruebas.** Sin rutas HTTP ni pantalla: no cambia el OpenAPI ni `packages/contracts`.
2. **Plan de cuentas mínimo** (decisión abierta 1): aceptado; se valida con el contador antes del corte 3. El corte no siembra ninguna cuenta.
3. **Permiso `ledger:create` (decisión abierta 3): no se agrega en este corte.** Ninguna ruta lo usa en el corte 1; se agrega en el corte 4 junto con `StaffRole`, la matriz de `docs/03-seguridad.md` §5.2 y `PermissionMatrixParityTest`.
4. **Fechas retroactivas y cierre de período** (decisión abierta 4): diferidos. El corte 1 no valida fecha futura ni período cerrado.
5. **`OPENING_BALANCE`** (decisión abierta 5): diferido. No se agrega el noveno tipo.

## Construido (backend)

- **Migración `V30__create_ledger_core.sql`**: `ledger_account`, `ledger_student_account`, `ledger_transaction` y `ledger_entry` con
  RLS forzada y llaves compuestas con la institución. Disparadores: rechazo de `UPDATE`/`DELETE` por fila y de `TRUNCATE` por
  sentencia en transacción y asiento (también al dueño); guardas de cuenta contable y de subcuenta; `BEFORE INSERT` de cabecera (fija
  `posted_at` y `posting_xid`, reglas de reverso) y de asiento (INV-37 y INV-39); disparador de restricción diferido por cabecera
  (mínimo dos asientos, una moneda, cuadre y espejo exacto). `posting_xid` es `BIGINT` (el valor de `pg_current_xact_id()`), no `xid8`,
  para no requerir un enlace propio en jOOQ. Permisos: `SELECT, INSERT` a `confia_admin_app`, `UPDATE (status)` solo en la subcuenta
  (lo exige `FOR UPDATE`) y `UPDATE (name, is_active, version, updated_at)` en la cuenta contable; `SELECT` a `confia_readonly`; nada al portal.
- **Dominio puro** (`com.confia.ledger.domain`): `LedgerTransaction` (fábrica `post`, `mirror` y `restore`; cuadre con `Money`, 2 a 100
  asientos, una moneda, rechazo de más de dos decimales, forma canónica para la huella), `LedgerEntry`, `AccountRef`, tipos y errores.
- **Aplicación**: `LedgerPosting.post` y `reverse` (idempotencia por clave y huella SHA-256 con `Digests`; apertura perezosa y bloqueo
  `FOR UPDATE` en orden `(student_id, currency)`; subcuenta `UNDER_REVIEW` rechaza; auditoría `ledger.transaction.posted|reversed` y
  `ledger.student_account.opened` en la misma transacción; reintento una vez ante carrera de la misma clave) y `LedgerQueries`
  (`balanceOf` derivado de los asientos, `asOf` y «tal como se conocía»; `entries` paginado, tope 100).
- **Infraestructura**: `JooqLedgerRepository` (solo inserta y lee; `Money` por `MoneyColumns`) y `LedgerConfiguration`, importada solo
  por `AdminApplication` (y declarada en `ProcessBeanPolicy`; el portal la tiene prohibida).
- **Soporte de pruebas**: `CommittingPostgresIntegrationTest` calcula ahora el conjunto protegido de forma transitiva (la subcuenta apunta
  a `students_student` y el asiento apunta a la subcuenta).
- **Pruebas**: `LedgerTransactionTest` (dominio) y `LedgerCoreIT` (PostgreSQL real: criterio de aceptación, repetición con la misma y otra
  carga, reverso único y concurrente, reverso de reverso, otro módulo, otra institución y fecha anterior, descuadre, sin asientos, cero,
  negativo, tres decimales, monedas, dimensión del estudiante, asiento tardío, `UPDATE`/`DELETE`/`TRUNCATE` del dueño y la aplicación,
  RLS, concurrencia sobre la misma cuenta y orden opuesto sin interbloqueo).
- **Pendiente para otros cortes**: eventos de dominio (sin consumidor), caché de saldo, plan de cuentas aprobado, rutas, `ledger:create`. (`ledger:create` y las rutas de carga del plan de cuentas: `docs/cortes/ledger-chart-of-accounts.md`)

# Corte: descuentos de campaña y de convenio, y la regla de acumulación (scholarships, corte 2)

Fase F2, entregable 6, que completa la fase. Agrega los descuentos al módulo `scholarships` y lleva al devengo de F3 la suma de
beneficios. El descuento por pronto pago llega después, junto a pagos. Se apoya en el corte 1 (`scholarships-awards`, PR #354 al #361).

## Objetivo

- Que la administración defina descuentos de campaña y de convenio, con vigencia y conceptos cubiertos.
- Que un descuento de convenio se asigne a un estudiante y que otra persona apruebe la asignación.
- Que el motor de devengo sume la beca y los descuentos de un cargo según la regla de acumulación, y que el simulador lo muestre antes.

## Reglas e invariantes

- **Descuento** (`Discount`, docs/02 §3.3):
  - Campos: código único, nombre, clase (`campaign` o `agreement`), tipo de beneficio y valor (porcentaje mayor que 0 y hasta 100, o
    monto fijo mayor que 0, ambos con hasta dos decimales), conceptos que cubre (uno o más), vigencia (`validFrom`, `validTo`) y la marca
    `stackable` (acumulable).
  - Se desactiva y se reactiva; no se borra. Desactivarlo no cambia los cargos emitidos (docs/ui-ux/04, mensajes).
  - Con cargos emitidos o asignaciones aprobadas, el tipo, el valor y la marca acumulable no cambian, y el alcance solo se reduce (conceptos que se quitan,
    vigencia que se acorta), igual que una beca: ampliar significa crear otro descuento.
- **Clases de descuento:**
  - **Campaña:** se aplica solo, a todo cargo de un concepto cubierto cuya fecha de precio (la fecha con que se fija el precio del cargo, no la de emisión) cae en la
    vigencia, con ambos extremos incluidos. No se asigna a nadie. Aplica solo una vez aprobada por otra persona (ver «Aprobación de un descuento de
    campaña»).
  - **Convenio:** se aplica solo a los estudiantes con una asignación aprobada y vigente (decisión del propietario, 2026-11-26, «Con
    aprobación»). La asignación (`DiscountAssignment`) sigue el mismo ciclo que el otorgamiento de beca: nace `pending`, pasa a
    `approved` o `rejected`, y una aprobada puede pasar a `revoked`. Quien la solicita no puede decidirla (`403 self-approval-forbidden`).
    Rechazo y revocación exigen un motivo de 20 a 500 caracteres; la bitácora guarda su huella SHA-256 y su longitud. Su vigencia cae
    dentro de la del descuento, y un estudiante no tiene dos asignaciones aprobadas del mismo descuento que se solapen.
- **Aprobación de un descuento de campaña** (decisión del propietario, 2026-11-26, «Aprobación por otra persona», cierra el hallazgo H1 de
  la revisión de seguridad): un descuento de campaña se aplica solo a todo cargo, así que ninguna persona lo deja vigente sola.
  - Crear un descuento de campaña lo deja `pending` (sin aplicarse) hasta que otra persona con `scholarships:approve` lo aprueba; si lo
    rechaza (motivo de 20 a 500 caracteres) queda `rejected` y nunca se aplica ni se edita.
  - **Ampliarlo** (otro tipo de beneficio o un valor mayor, una vigencia que empieza antes o termina después, un concepto más, la marca
    acumulable activada) y **reactivarlo** no cambian los términos vigentes: dejan una **propuesta** (`pendingChange`) que otra persona
    aprueba (pasa a ser los términos vigentes) o rechaza (se descarta). Una propuesta a la vez; mientras espera, el descuento no se
    edita ni se reactiva, y solo se puede desactivar. **Reducirlo** (valor menor, vigencia más corta, menos conceptos, acumulable
    apagado) y **desactivarlo** siguen siendo inmediatos. Ampliar uno ya en uso sigue siendo 409 (se crea otro descuento).
  - Solo los términos aprobados se aplican en el devengo (`DiscountBenefits.inForce` filtra `approval_status = approved`) y la base
    rechaza la línea de un cargo para un descuento que no está aprobado.
  - **Modelo mínimo.** La propuesta vive en las columnas `pending_*` del mismo renglón del descuento (los términos vigentes son las columnas
    de siempre); la decisión es un registro de solo inserción, `scholarships_discount_change_decision`, con la versión del descuento que el
    aprobador vio, la `Idempotency-Key` permanente con la huella de lo pedido y, al rechazar, el motivo (en la bitácora solo su SHA-256 y su
    longitud). Cada cambio de estado sube la versión del descuento; la decisión nombra la versión (`409 stale-version` si cambió). La
    base impide, también al dueño: nacer un descuento de campaña aprobado, ampliarlo o reactivarlo sin una decisión de otra persona
    tomada en la misma transacción y con exactamente los términos propuestos, una propuesta a nombre de otra cuenta, cambiar uno
    pendiente o rechazado, y agregar un concepto fuera de su nacimiento o de una aprobación. Quien creó el descuento o propuso el
    cambio no lo decide (`403 self-approval-forbidden`, en el servidor y en la base).
  - **Descuento de convenio:** crearlo, editarlo y reactivarlo sigue siendo inmediato, porque no se aplica a nadie sin una asignación
    aprobada, que ya lleva su segunda persona (ver abajo).
- **La aprobación de una asignación queda ligada a lo solicitado** (hallazgo H2): la asignación guarda la versión del descuento y su
  beneficio (tipo y valor) al solicitarla, y el detalle los muestra (`discountVersion`, `benefitType`, `percent`, `fixedAmount`). Aprobarla
  exige que el descuento tenga todavía esa versión: si cambió después de la solicitud (cualquier edición, incluso el nombre, una
  desactivación o una reactivación) responde `409 discount-terms-changed` y no se escribe nada; rechazar no exige términos. La base lo
  exige igual y que quien solicita sea la cuenta de la sesión. Una asignación de antes de esta regla no guarda términos y ya no se puede
  aprobar: se solicita otra vez. La bitácora de la solicitud y de la aprobación lleva la versión y el beneficio.
- **La marca `stackable` queda bloqueada en uso** (decisión del propietario, 2026-11-26, «Bloquearla en uso»): como el tipo y el valor,
  no cambia cuando el descuento tiene una asignación aprobada o un cargo emitido (`409 discount-benefit-locked`; también la base).
- **Regla de acumulación (INV-15)** (decisión del propietario, 2026-11-26, «En cascada»):
  - Si todos los beneficios que aplican a un cargo son acumulables, se aplican **en cascada**: primero la beca sobre la base bruta,
    después cada descuento sobre lo que queda. Ejemplo: base 1,000.00, beca 20 % y descuento 10 %: 1,000.00 → 800.00 → 720.00.
  - Si alguno no es acumulable, se aplica solo el de mayor beneficio, como en el corte 1. Con empate, la beca antes que el descuento, y
    entre descuentos el de código menor.
  - Confirmado por el propietario (2026-11-26): la beca cuenta como acumulable; lo que decide es la marca de cada descuento. Entre becas sigue aplicándose solo la mayor.
  - Confirmado por el propietario (2026-11-26): en la cascada, los descuentos de porcentaje van antes que los de monto fijo, y a igual tipo, por código.
  - El beneficio total nunca deja la base por debajo de cero. El tope por período de la beca se calcula igual que en el corte 1.
- **Cálculo** (servidor, `Money`, `HALF_UP` a dos decimales en cada paso de la cascada). El impuesto se calcula sobre la base neta, con el
  mismo supuesto pendiente del contador que el corte 1.
- **El cargo guarda el detalle de cada beneficio** (origen, beca u otorgamiento o descuento, y su importe), además de la base bruta, el
  beneficio total, la base neta, el impuesto y el total. Nada se recalcula después; los cargos ya emitidos no cambian (INV-14).
- **Libro mayor.** El asiento sigue siendo una sola transacción: el débito a «Becas y descuentos» es el beneficio total.
- **Solo hacia adelante e idempotencia:** como en el corte 1. Si un descuento o una asignación cambia entre la vista previa y la
  confirmación, no se genera nada.
- **Auditoría:** creación, cambio, desactivación y reactivación de un descuento; solicitud, aprobación, rechazo y revocación de una
  asignación.

## Permisos

Confirmado por el propietario (2026-11-26): los descuentos usan los permisos de becas, porque viven en el mismo módulo y los deciden los mismos roles.
`scholarships:create` define descuentos y solicita asignaciones, `scholarships:read` los consulta, `scholarships:approve` aprueba y
rechaza, y `scholarships:void` revoca.

## Endpoints (`/api/v1/scholarships`)

- Descuentos: `GET` y `POST /discounts`, `GET` y `PUT /discounts/{id}`, y `POST /discounts/{id}/deactivate|reactivate`.
  Decisiones sobre un descuento de campaña: `POST /discounts/{id}/approve` (cuerpo `{ version }`) y `POST /discounts/{id}/reject` (cuerpo
  `{ version, reason }`), con `Idempotency-Key` y el permiso `scholarships:approve`.
- Asignaciones: `GET /discount-assignments?studentId&status&discountId&page&size&sort`, `POST /discount-assignments`,
  `GET /discount-assignments/{id}` y `POST /discount-assignments/{id}/approve|reject|revoke`, con `Idempotency-Key`.
- La vista previa, los cargos del estudiante y el simulador agregan la lista de beneficios aplicados por cargo.

## Pantallas (admin-web)

- **Descuentos** (Finanzas › Descuentos, ruta ya prevista en docs/ui-ux/04): lista con filtros y alta o edición.
- **Asignaciones de convenio:** lista, solicitud y detalle con «Aprobar», «Rechazar» y «Revocar», con el mismo patrón que otorgamientos.
- El simulador, la generación de cargos y los cargos del estudiante muestran cada beneficio aplicado y el total.

## Casos límite

- Beca y descuento acumulables (cascada), uno no acumulable (solo el mayor) y empate.
- Dos descuentos acumulables, uno de porcentaje y uno de monto fijo (orden de la cascada).
- Beneficios que sumarían más que la base (queda en cero).
- Descuento de campaña que empieza o termina el día de la fecha de precio (incluido); concepto no cubierto.
- Convenio sin asignación, pendiente, rechazada o revocada (no se aplica); aprobación de quien la solicitó.
- Ampliar el alcance o cambiar el valor de un descuento ya usado (409); reducirlo (permitido).
- Descuento de campaña pendiente, rechazado o con una propuesta esperando (no se aplica o se aplica con los términos vigentes); decisión de
  quien lo creó o lo propuso (403); versión desactualizada (409); editar o reactivar mientras espera (409 `discount-pending-approval`).
- Cambiar el descuento entre la solicitud y la aprobación de una asignación (409 `discount-terms-changed`).
- Redondeo `HALF_UP` en cada paso; concepto gravado (impuesto sobre la base neta).
- Otra institución (404) y RLS.

## Criterio de aceptación

- Un estudiante con beca aprobada del 20 % y un descuento de campaña acumulable del 10 % sobre la mensualidad, con una base de
  L 1,000.00 y un impuesto del 15 %, recibe un cargo con base neta L 720.00, impuesto L 108.00 y total L 828.00. El libro mayor registra
  L 1,000.00 de ingreso y L 280.00 en becas y descuentos.
- Si el descuento no es acumulable y es del 25 %, se aplica solo el descuento: base neta L 750.00.
- El simulador muestra lo mismo antes de generar.

## Fuera de alcance

- Pronto pago (con pagos).
- Descuentos por modalidad, grado o sección (este corte cubre la institución completa y los convenios por estudiante).
- Aplicar descuentos a cargos ya emitidos; reportes de costo de descuentos.

## Lo construido (backend T1, 2026-11-26)

- **Esquema (`V39__create_scholarships_discounts.sql`).** Cuatro tablas con RLS forzada por institución: `scholarships_discount`,
  `scholarships_discount_concept`, `scholarships_discount_assignment` y `scholarships_discount_assignment_decision` (solo inserción, con la
  `Idempotency-Key` guardada de forma permanente y la huella de lo pedido). La base rechaza, también al dueño: beneficio que no sea un porcentaje
  mayor que 0 y hasta 100, o un monto fijo mayor que 0, ambos con dos decimales y en HNL; vigencia invertida o fuera de 2000-2100; descuento sin
  concepto; cambio de código, clase o creación; cualquier `DELETE`; asignación de un descuento de campaña, de uno inactivo o fuera de su vigencia;
  dos asignaciones aprobadas del mismo descuento y estudiante que se solapen (restricción de exclusión sobre el rango cerrado de fechas);
  decisión fuera del estado que decide, de otra cuenta, o de quien solicitó (autoaprobación); motivo fuera de 20 a 500 caracteres; estado sin su
  decisión; y cualquier `UPDATE` o `DELETE` de una decisión.
- **Descuento en uso.** Con una asignación aprobada (o revocada después) el tipo y el valor no cambian, la vigencia solo se acorta y los
  conceptos solo se quitan; la base lo exige con `restrict_violation` y la API responde `409 discount-benefit-locked` o
  `409 discount-scope-locked`. Gancho para T2: «en uso» hoy significa «tiene una asignación aprobada o revocada»; la tarea del devengo debe
  agregar «o un cargo emitido con el descuento» con `CREATE OR REPLACE` de `scholarships_discount_guard` y de
  `scholarships_discount_concept_before_insert`, y en `DiscountAssignmentRepository.inUse`.
- **Módulo `scholarships`.** Dominio (`Discount`, `DiscountKind`, `DiscountAssignment`, `AssignmentDecision` y cinco errores), casos de uso
  (`ManageDiscounts`, `RequestDiscountAssignment`, `DecideDiscountAssignment`, `ViewDiscountAssignments`), puertos y adaptadores jOOQ,
  bitácora (`scholarships.discount.created|updated|deactivated|reactivated` y `scholarships.discount_assignment.requested|approved|rejected|revoked`,
  con la huella SHA-256 y la longitud del motivo, nunca su texto) y los controladores `DiscountController` y `DiscountAssignmentController`. El
  ciclo de la asignación reutiliza `AwardStatus` y `DecisionKind`, porque es el mismo ciclo que el del otorgamiento.
- **Rutas** (según la nota, bajo `/api/v1/scholarships`): `GET|POST /discounts`, `GET|PUT /discounts/{id}`, `POST /discounts/{id}/deactivate|reactivate`,
  `GET|POST /discount-assignments`, `GET /discount-assignments/{id}` y `POST /discount-assignments/{id}/approve|reject|revoke` (con
  `Idempotency-Key`). Permisos: `scholarships:read` (listar y leer), `create` (definir descuentos y solicitar), `approve` (aprobar y rechazar) y
  `void` (revocar). Doce operaciones nuevas en `admin.openapi.json` (124 en total); `packages/contracts` no se regeneró (corresponde a T3).
- **Puerto de lectura para el devengo (T2).** `scholarships.application.api.DiscountBenefits.inForce(...)` devuelve los descuentos vigentes de un
  estudiante para un concepto y una fecha (campaña: todos; convenio: solo con asignación aprobada y vigente; activos, con ambos extremos
  incluidos, solo en HNL), ya en el orden de la cascada: porcentaje antes que monto fijo y, dentro de cada grupo, por código. Cada elemento trae
  `stackable`, `percentage` y la función `on(base)` que da su beneficio sobre la base que se le pase (`HALF_UP`, nunca mayor que la base), de modo
  que la cascada y la elección del mayor las decide el módulo `charges` sin tipos del dominio de becas. También `anyApplicable(...)` y la
  identidad `NONE`. Registrado en el proceso administrativo y en el trabajador; `charges` no se tocó.
- **Pruebas nuevas (128):** `DiscountTest` (41) y `DiscountAssignmentTest` (14) de dominio; `DiscountBenefitsServiceTest` (10);
  `DiscountsIT` (39, base real: restricciones, RLS, llaves, carrera de dos decisiones, auditoría); `DiscountBenefitsIT` (9);
  `DiscountsEndpointsIT` (15, proceso administrativo real: permisos por rol, autoaprobación 403, sesión revocada, validación, 404 entre
  instituciones). Inventarios actualizados: `PermissionedEndpoints` y su prueba, `PublicRouteAllowListTest`, `CurrentSessionEndpointIT`,
  `OpenApiContractSnapshotTest`, `ProblemCodeTest` (cinco códigos nuevos) e `IdempotencyScopeExclusionInventoryTest`.

### Desviaciones y pendientes

- **Regla añadida:** acortar la vigencia de un descuento que dejaría fuera una asignación pendiente o aprobada se rechaza (`400 validation-failed`,
  campo `validTo`, motivo `assignments-outside-validity`); así la vigencia de la asignación siempre queda dentro de la del descuento. Aprobar
  vuelve a comprobar esa inclusión. La nota no lo decía; para acortar, primero se revoca o rechaza la asignación.
- ~~Cambiar la marca `stackable` se permite aunque el descuento esté en uso~~: corregido en T4 (S8): la marca queda bloqueada en uso, como el
  tipo y el valor (ver «Lo construido (backend T4)»).
- Cinco códigos de problema nuevos: `duplicate-discount-code`, `discount-benefit-locked`, `discount-scope-locked`,
  `discount-assignment-overlap` e `invalid-discount-assignment-state`. La autoaprobación reutiliza `self-approval-forbidden` (403).
- Pendiente de T2 (hecho en T2, ver «Lo construido (backend T2)»): cascada, detalle por beneficio en el cargo, asiento, vista previa, cargos del estudiante y simulador; ampliar «en uso» con los
  cargos emitidos. Pendiente de T3: `packages/contracts` y pantallas. `docs/03-seguridad.md` §5.3 se actualizó en T4 con la segregación de las
  asignaciones de convenio y de los descuentos de campaña.

## Lo construido (backend T2, 2026-11-26)

El motor de devengo aplica la beca y los descuentos de un cargo con la regla de acumulación; la vista previa, el simulador y los cargos del
estudiante muestran la lista de beneficios aplicados. Queda T3 (contrato en `packages/contracts` y pantallas).

- **Dominio de `charges` (puro, con `Money` y `HALF_UP`).** `BenefitStacking` decide qué beneficios aplican y cuánto. Si todos los que aplican
  son acumulables, los aplica en cascada: la beca sobre la base bruta (con su tope por período como en el corte 1), y después cada descuento
  sobre lo que queda, en el orden que entrega `scholarships` (porcentaje antes que monto fijo y por código); cada paso se redondea `HALF_UP` a
  dos decimales, nunca supera lo que queda y un paso que no da nada no deja línea. Si algún descuento que aplica no es acumulable, aplica solo
  el de mayor beneficio calculado sobre la base bruta; con empate gana la beca y, entre descuentos, el código menor (después el identificador).
  `AppliedBenefit` es cada beneficio (origen, código copiado y monto), `DiscountCandidate` y `DiscountsStep` son la entrada de los descuentos,
  y `ChargeEvaluator` calcula el impuesto sobre la base neta. `ComputedCharge` guarda la lista de beneficios; `benefit()` sigue devolviendo la beca.
- **`scholarships`.** `DiscountBenefits.Applicable` y `DiscountsInForceRepository.InForce` agregan la asignación aprobada que hace aplicar un
  convenio (`assignmentId`, vacía en una campaña); se conserva el constructor anterior. `DiscountAssignmentRepository.inUse` lee la vista
  `scholarships_discount_in_use`, porque «tiene un cargo emitido» vive en las tablas de `charges`, que este módulo no lee.
- **Migración `V40__add_discount_benefits_to_charges.sql`** (solo agrega; ninguna fila existente se actualiza ni se borra):
  - Tabla `charges_charge_benefit`, solo inserción (`UPDATE`, `DELETE` y `TRUNCATE` rechazados), con RLS forzada: una línea por beneficio, en
    orden (`position`), con el origen (`SCHOLARSHIP` o `DISCOUNT`), el código copiado, la beca o el descuento, la asignación en un convenio, el monto
    y la moneda. A lo más una línea de beca por cargo y una línea por descuento.
  - La base rechaza, también al dueño: una línea que no es la beca del cargo o cuyo monto no es el beneficio de su beca limitado al resto del
    tope del período y a la base (regla de V38, ahora sobre las líneas de beca: el beneficio de un descuento no gasta el tope); una línea de un
    descuento inactivo, fuera de su vigencia en la fecha de precio (ambos extremos), que no cubre el concepto, con otro código, o con un monto mayor
    que el beneficio del descuento sobre la base; un convenio sin una asignación aprobada del estudiante, del descuento y vigente en la fecha, y una
    campaña con asignación; líneas que suman más que el beneficio del cargo; y, al terminar la transacción (disparador diferido), un cargo cuyo
    beneficio no es la suma de sus líneas, con beca sin línea de beca (o al revés), o con líneas que se acumulan cuando algún descuento no es acumulable.
  - `charges_charge_benefit_chk` se relaja: la beca y su código van juntos y una beca implica beneficio, pero un cargo puede llevar solo
    descuentos. Todo cargo existente la cumple. El disparador del cargo ya no compara el monto del beneficio con una sola beca; el asiento sigue
    exigiendo UN débito por el beneficio total. `charges_award_benefit_given` suma las líneas de beca de los cargos no anulados del período y, para
    un cargo anterior a las líneas (sin líneas), su beneficio completo.
  - «En uso» incluye ahora «un cargo emitido con el descuento» (`CREATE OR REPLACE` de `scholarships_discount_guard` y de
    `scholarships_discount_concept_before_insert`, y la vista `scholarships_discount_in_use`).
- **Motor, asiento y lectura.** Una sola transacción por cargo; el débito a `BENEFITS` es el beneficio total (S2). La vista previa, el trabajador y el
  simulador pasan por el mismo `AccrualEngine`. El resumen de la vista previa pasó a la versión `v3` e incluye cada beneficio (origen, asignación y
  monto): si un descuento o una asignación cambia entre la vista previa y la confirmación, la ejecución se rechaza con `preview-outdated` y no se
  genera nada. Con descuentos en vigor para la población, el período exige la cuenta `BENEFITS`, igual que con becas. La vista previa, los cargos del
  estudiante y el simulador agregan `benefits` (origen, código, identificadores y monto), sin quitar ni renombrar nada: un cargo anterior a las
  líneas muestra su beca como único beneficio. `admin.openapi.json` regenerado (un esquema nuevo, `AppliedBenefit`, y el campo `benefits`; sin
  operaciones nuevas); `packages/contracts` no se regeneró (T3).
- **Pruebas nuevas.** Dominio: `BenefitStackingTest` (15) y `ChargeEvaluatorDiscountsTest` (5). Base real: `AccrualDiscountsIT` (38: el ejemplo de
  aceptación con su asiento, el caso no acumulable, orden de la cascada, suma mayor que la base, empates, campaña que empieza o termina el día de la fecha de
  precio, concepto no cubierto, convenio sin asignación, pendiente, rechazada, revocada y aprobada, `HALF_UP` por paso, tope con descuento,
  cambio entre vista previa y confirmación, repetir el período, simulador igual al motor, «en uso» y lo que rechaza la base) y
  `ChargeBenefitsMigrationIT` (2: una base migrada de V39 a V40 con cargos del corte 1 los deja intactos y se leen igual).

### Desviaciones y pendientes

- **Compatibilidad probada con una base desechable**, no con la base compartida: los cargos del corte 1 ya no se pueden escribir (la base exige las
  líneas), así que la prueba migra una base nueva hasta V39, escribe cargos como los de V37 y V38 con el superusuario y los disparadores apagados, migra
  a V40 y comprueba que siguen iguales, que `benefitGiven` y `charges_award_benefit_given` los cuentan contra el tope y que la lista de cargos muestra
  su beca como único beneficio.
- **El disparador diferido (suma de líneas igual al beneficio) se ejercita en cada devengo**, que lo cumple, y el exceso lo rechaza una prueba directa;
  no hay una prueba negativa del caso «las líneas suman menos», porque ningún camino de la aplicación puede producirlo y escribir un cargo a mano exige
  un asiento del libro mayor.
- Una línea solo puede agregarse mientras la suma no supere el beneficio del cargo; un cargo anterior a las líneas (sin líneas) aceptaría una línea que
  coincida con su beneficio. No es un camino de la aplicación; queda anotado para la revisión de seguridad.
- La función `charges_award_benefit_given` se concede a `confia_admin_app` porque el disparador de la línea la llama con los derechos de quien inserta.
- `IntegrityCheck` no compara las líneas; el asiento sigue comparándose contra total más beneficio.
- Los constructores de `ManageAccrual`, `ExecuteAccrualRun` y `SimulateCharges` sin descuentos se conservan (`PaymentsFixture` los usa); los procesos reales
  usan los nuevos, que reciben `DiscountBenefits`.

## Lo construido (frontend T3, 2026-11-26)

`packages/contracts` se regeneró con `pnpm --filter @confia/contracts generate` (el directorio generado está fuera de Git, así que no hay archivo que
confirmar). Las pantallas viven en `apps/admin-web/src/features/scholarships/`, bajo el área «Becas», y reutilizan los patrones del corte 1.

- **Beneficios aplicados en los cargos.** El componente de presentación `BenefitList` (`shared/benefits/components/`) lista cada beneficio de un cargo en el
  orden que lo aplicó el servidor (origen «Beca» o «Descuento», código copiado e importe tal como llegó, con `MoneyAmount`), o dice «Sin beneficios». Sustituye
  la columna «Beca» en la vista previa de la generación de cargos, en el simulador y en los cargos del estudiante. El navegador no suma, no redondea ni compara
  importes: el beneficio total, la base neta, el impuesto y el total siguen siendo los del servidor. El tipo viene de `@confia/contracts/admin`, no de
  `shared/api` (regla `admin-presentation-is-pure`).
- **Descuentos** (`/scholarships/discounts`, entrada «Descuentos»). Lista paginada, filtrada (clase, tipo de beneficio, estado) y ordenada en el servidor, con todo
  en la dirección. Alta y edición en un diálogo: código y clase (fijos al crear), nombre, tipo y valor (porcentaje o monto fijo), vigencia con ambos días incluidos,
  marca «Acumulable» (empieza sin marcar) y conceptos. Con `inUse` el tipo y el valor se muestran de solo lectura con la explicación; los rechazos del servidor
  (`discount-benefit-locked`, `discount-scope-locked`, `duplicate-discount-code` bajo el código y `assignments-outside-validity` bajo el fin de la vigencia) salen
  del catálogo i18n. Desactivar y reactivar desde la fila, sin borrar.
- **Asignaciones de convenio** (`/scholarships/discount-assignments` y `/scholarships/discount-assignments/$assignmentId`, entrada «Asignaciones de convenio»).
  Lista con filtros de estado, descuento y estudiante; solicitud en un diálogo (solo descuentos de convenio activos); detalle con «Aprobar», «Rechazar» y
  «Revocar», cada una un diálogo explícito. Rechazar y revocar exigen un motivo de 20 a 500 caracteres. Las tres decisiones llevan `Idempotency-Key`, que se
  conserva mientras el resultado es desconocido (red caída, 5xx) y se renueva tras un rechazo definitivo. `self-approval-forbidden` tiene su propio texto («Quien
  solicitó la asignación…») y los demás códigos nuevos (`discount-assignment-overlap`, `invalid-discount-assignment-state`) pasan por el clasificador compartido.
  Nada es optimista: tras confirmar se vuelven a leer las asignaciones y los descuentos (una aprobada pone el descuento «en uso»).
- **Inventarios.** Rutas de AW59, entradas de la navegación lateral, matriz de accesibilidad (descuentos: listado, vacío, error de carga, alta y edición de uno en
  uso; asignaciones: listado, vacío, solicitud, detalle pendiente y su diálogo de rechazo) e inventario de recorridos (`scholarships-discounts.e2e.ts` y
  `scholarships-assignments.e2e.ts`). La API simulada (`e2e/mock-api/discounts-model.ts`) repite solo las reglas que esos recorridos necesitan.
- **Pruebas.** Vitest con Testing Library y MSW: modelo del formulario, vistas, diálogos y contenedores de descuentos y asignaciones (incluidos el vacío, el error con
  reintento, los campos bloqueados, el rechazo de la autoaprobación, la longitud del motivo y la reutilización de la `Idempotency-Key`); Playwright con axe para los
  tres recorridos de becas y las combinaciones nuevas de la matriz.

### Desviaciones y pendientes

- El simulador de la API simulada (solo para Playwright) no aplica descuentos: la cascada se prueba en el backend. Los recorridos de Playwright no muestran un
  cargo con descuento.
- No hay todavía una pantalla de reportes de costo de descuentos (fuera de alcance de este corte).

## Lo construido (backend T4, correcciones de las revisiones, 2026-11-26)

Cierra los hallazgos de la revisión de seguridad (H1, H2, M1, L1, L3) y las observaciones de la revisión de código (F1 a F7). Migración
`V41__require_approval_of_campaign_discounts_and_bind_assignments_to_terms.sql` (agrega y reemplaza funciones con `CREATE OR REPLACE`; no
actualiza ni borra ninguna fila; V39 y V40 no se tocan porque las bases locales ya las aplicaron).

- **S7 / H1, aprobación de un descuento de campaña.** Ver «Aprobación de un descuento de campaña» arriba. Código: `Discount` (`approval`,
  `proposal`), `DiscountProposal`, `DiscountDecision`, `DecideDiscountChange` (aprobar y rechazar, con `Idempotency-Key` y versión),
  `ManageDiscounts` (una ampliación o una reactivación de campaña deja la propuesta), `JooqDiscountDecisionRepository`. La bitácora agrega
  `scholarships.discount.change_requested`, `scholarships.discount.approved` (con el aprobador) y `scholarships.discount.rejected` (con la
  huella del motivo); `created` lleva `approvalStatus`.
- **H2, aprobación ligada a los términos.** `scholarships_discount_assignment` agrega `discount_version`, `benefit_type`, `percent` y
  `fixed_amount`; `DiscountAssignment.requireTermsOf` y `DecideDiscountAssignment` rechazan con `409 discount-terms-changed`; el disparador de la
  asignación lo exige también (al nacer con los términos de hoy, al aprobar con la versión solicitada, y los términos nunca cambian). El
  detalle y la bitácora de la solicitud y de la aprobación llevan la versión y el beneficio.
- **S8 / F3, `stackable` bloqueada en uso**: dominio (`Discount.edit`), caso de uso y disparador; reutiliza `discount-benefit-locked`.
- **M1 / F1**: `charges_charge_benefit_before_insert` rechaza una línea cuyo cargo no se escribió en la misma transacción (la del asiento
  `ledger_transaction.posting_xid`, la técnica de `charges_charge_before_insert`); cierra el caso de un cargo de antes de las líneas.
- **L1**: el disparador de la asignación de descuento y el del otorgamiento de beca exigen `requested_by = app.actor_id` al nacer.
- **Pruebas nuevas o cambiadas.** `CampaignApprovalIT` (nuevo), `DiscountTest`, `DiscountAssignmentTest`, `DiscountsIT` (H2, L1, S8 en la base,
  L3), `ScholarshipsIT` (L1 del otorgamiento), `DiscountsEndpointsIT` (rutas de decisión, 403, 409, cuerpo y clave), `AccrualDiscountsIT`
  (descuento pendiente o rechazado, propuesta que no cambia lo que aplica, F4 con el rol de la aplicación, F5 con un disparador de prueba
  que pierde una línea y el rechazo al confirmar), `ChargeBenefitsMigrationIT` (M1). Los ITs que crean campañas las aprueban con otra
  persona (`approvedByAnother`). Pruebas de dominio para cubrir las ramas que ya existían sin prueba directa: `DiscountInvariantsTest`,
  `AppliedBenefitTest` y `DiscountCandidateTest`.
- **Verificación.** `./mvnw verify` completo en `apps/api`: núcleo 205, unitarias 4841 (3 omitidas que ya lo estaban), integración 1870, sin
  fallos (incluidas `DecidePaymentIT`, `RegisterPaymentIT` y `PaymentEndpointsIT`), y la compuerta de JaCoCo cumplida (ramas de
  `scholarships.domain` 98 %, de `charges.domain` 96 %; una primera corrida solo falló esa compuerta, con 91 % y 94 %, y se corrigió con
  pruebas y no con un umbral menor).

### Lo que debe cambiar el frontend (T4, segunda parte)

- Respuesta de descuento (`DiscountResponse`, también en la lista): campos nuevos `approvalStatus` (`pending`|`approved`|`rejected`),
  `createdBy` y `pendingChange` (ausente si no espera nada: `name`, `benefitType`, `percent`, `fixedAmount`, `validFrom`, `validTo`,
  `stackable`, `reactivate`, `concepts`, `requestedBy`, `requestedAt`). Los campos de siempre son los términos vigentes.
- Rutas nuevas: `POST /api/v1/scholarships/discounts/{id}/approve` (`{ version }`) y `.../reject` (`{ version, reason }`, 20 a 500
  caracteres), con `Idempotency-Key` (conservarla mientras el resultado es desconocido) y permiso `scholarships:approve`.
- Estados que mostrar: «Pendiente de aprobación» (crear una campaña responde 201 con `approvalStatus: pending`), «Propuesta pendiente»
  (editar una ampliación o reactivar una campaña responde 200 con `pendingChange`, y el formulario muestra los términos vigentes y los
  propuestos), «Rechazado». Con `approvalStatus != approved` o `pendingChange` presente no se puede editar ni reactivar (`409
  discount-pending-approval`); sí se puede desactivar. El botón «Aprobar» y «Rechazar» se oculta o se deshabilita para `createdBy` (si está
  pendiente de creación) o `pendingChange.requestedBy`; el servidor responde `403 self-approval-forbidden`.
- Códigos de problema nuevos (`ProblemCode` y `problems.properties`): `discount-pending-approval`, `invalid-discount-change-state`,
  `discount-terms-changed` (409 los tres). `discount-benefit-locked` ahora también cubre la marca acumulable: el campo «Acumulable» queda de solo
  lectura cuando `inUse`.
- Detalle de asignación (`AssignmentResponse`): campos nuevos `discountVersion`, `benefitType`, `percent`, `fixedAmount` (lo solicitado);
  aprobar puede responder `409 discount-terms-changed` (mensaje: solicitar la asignación otra vez).

### Desviaciones y pendientes

- Una asignación creada antes de V41 no guarda términos y no se puede aprobar (`discount-terms-changed`); se solicita otra vez. Una campaña que
  ya existía en una base local queda `approved` (V41 no la reabre).
- Un descuento de campaña rechazado conserva su código (no se reutiliza) y nunca se edita; se crea otro.
- Reactivar un descuento de convenio y desactivar y reactivar un descuento con asignaciones pendientes sube su versión: esas asignaciones se
  rechazan o se solicitan otra vez antes de aprobarse.

## Lo construido (frontend T4, segunda parte, 2026-11-27)

`packages/contracts` se regeneró con `pnpm --filter @confia/contracts generate` (fuera de Git). Cambios en `apps/admin-web`:

- **Descuentos, estados de aprobación (S7).** La columna «Estado» de la lista dice, además de activo o inactivo, lo que el descuento espera: «Pendiente de
  aprobación» (una campaña recién creada, que no se aplica a nada), «Propuesta pendiente» (con los términos propuestos junto a los vigentes: beneficio,
  vigencia y, si la propuesta reactiva, que lo hace) y «Rechazado». No se agregó una columna: la tabla ya era ancha y una más desplaza las primeras columnas
  fuera de la vista (lo vio la compuerta de axe como contraste «parcialmente cubierto»). Mientras espera o está rechazado no se ofrece Editar ni Activar
  (`409 discount-pending-approval` si el servidor lo recibe igual); Desactivar sí. Tras crear una campaña, tras ampliarla o tras pedir su reactivación, el aviso
  dice que quedó pendiente o que se envió la propuesta y que los términos vigentes siguen aplicándose, no «se creó» ni «se activó». El formulario de una
  campaña lo advierte.
- **Aprobar y rechazar un descuento.** Dos botones por fila (`Aprobar {nombre}`, `Rechazar {nombre}`) con permiso `scholarships:approve`, y dos diálogos
  explícitos que repiten lo que se decide (los términos de la creación, o los vigentes junto a los propuestos). Mandan `{ version }` y `{ version, reason }`
  (20 a 500 caracteres) con la `Idempotency-Key` del intento: se conserva mientras el resultado es desconocido y se renueva tras un rechazo definitivo. Es el
  mismo ayudante que las asignaciones (`useDecisionIntent`, que ahora comparten ambos contenedores). Los botones se ocultan a quien creó el descuento o
  propuso el cambio (`createdBy` o `pendingChange.requestedBy` contra la cuenta de la sesión, que ahora viaja en `usePermissions().accountId`); es solo
  presentación y el servidor sigue respondiendo `403 self-approval-forbidden`, con su propio texto. Nada es optimista: tras confirmar se vuelven a leer los
  descuentos y las asignaciones.
- **«Acumulable» de solo lectura (S8).** Con `inUse` el campo se muestra como texto («Acumulable» o «No acumulable») igual que el tipo y el valor, y el
  `discount-benefit-locked` dice ahora que cubre también esa marca.
- **Asignaciones, términos solicitados (H2).** El detalle y los tres diálogos de decisión muestran «Beneficio solicitado» y «Versión del descuento» (de
  `benefitType`, `percent`, `fixedAmount` y `discountVersion`). Una asignación sin términos (de antes de V41) lo dice y avisa que no se puede aprobar. Aprobar
  puede responder `409 discount-terms-changed`: el diálogo dice que se rechace y se solicite de nuevo, y la asignación se vuelve a leer.
- **Códigos de problema** `discount-pending-approval`, `invalid-discount-change-state` y `discount-terms-changed`, con su texto en el catálogo.
- **Inventarios y API simulada.** La matriz de accesibilidad agrega los diálogos de aprobar y rechazar un descuento y la propuesta pendiente. La API simulada
  de Playwright repite las reglas nuevas (campaña pendiente, propuesta, decisión con versión y clave, términos de la asignación, `stackable` bloqueada) y la
  cuenta de la sesión simulada es ahora la persona que usan los demás modelos (`ME`). Recorridos: `scholarships-discounts.e2e.ts` (crear una campaña que queda
  pendiente, aprobar la de otra persona, rechazar con motivo, proponer una ampliación) y `scholarships-assignments.e2e.ts` (términos solicitados y
  `discount-terms-changed`). Sin archivos `*.e2e.ts` nuevos, así que el inventario de recorridos no cambia.

### Desviaciones y pendientes

- La cuenta de la sesión (`accountId`) se compara con `createdBy` y `requestedBy` suponiendo que son el mismo identificador de la cuenta, como en las
  asignaciones del servidor (`requested_by = app.actor_id`). Si no coincidieran, el botón se vería y el servidor respondería 403 con su texto.
- Los recorridos de Playwright no analizan con axe los diálogos recién abiertos ni la lista justo después de desactivar (un análisis en esos instantes ve
  la capa del diálogo o el desplazamiento del aviso como contraste «parcialmente cubierto»): lo cubren las combinaciones de la matriz de la compuerta, que
  aísla el diálogo y espera a que cierre.
- Las pruebas de componente se escribieron después del código; el rojo se observó por mutación (ver el registro de la tarea).

## Lo construido (avisos de la segunda revisión de seguridad, 2026-11-27)

La migración V42 cierra los dos avisos que dejó la segunda revisión de seguridad, sin cambiar ninguna fila guardada:

- **A1.** La base de datos exige que quien crea un descuento sea la persona de la sesión (`created_by` igual a `app.actor_id`), con
  la misma regla que ya tenían las asignaciones y las becas. Así, la comprobación de que nadie aprueba su propia campaña no depende
  de lo que escriba quien inserta la fila.
- **A2.** Todo cambio de un descuento lleva la versión siguiente (exactamente una más). La aprobación de una asignación y la decisión
  sobre una campaña se atan a la versión, y ahora la base de datos garantiza que ningún cambio la deja igual.

Las dos comprobaciones corren después de escribir la fila, de modo que las reglas de V39 a V41, las restricciones y la seguridad por
fila siguen respondiendo primero con su propio motivo. La aplicación ya cumplía ambas reglas, así que no cambia su comportamiento.


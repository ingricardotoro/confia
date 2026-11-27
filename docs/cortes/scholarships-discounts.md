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
  - Con cargos emitidos o asignaciones aprobadas, el tipo y el valor no cambian, y el alcance solo se reduce (conceptos que se quitan,
    vigencia que se acorta), igual que una beca: ampliar significa crear otro descuento.
- **Clases de descuento:**
  - **Campaña:** se aplica solo, a todo cargo de un concepto cubierto cuya fecha de emisión cae en la vigencia. No se asigna a nadie.
  - **Convenio:** se aplica solo a los estudiantes con una asignación aprobada y vigente (decisión del propietario, 2026-11-26, «Con
    aprobación»). La asignación (`DiscountAssignment`) sigue el mismo ciclo que el otorgamiento de beca: nace `pending`, pasa a
    `approved` o `rejected`, y una aprobada puede pasar a `revoked`. Quien la solicita no puede decidirla (`403 self-approval-forbidden`).
    Rechazo y revocación exigen un motivo de 20 a 500 caracteres; la bitácora guarda su huella SHA-256 y su longitud. Su vigencia cae
    dentro de la del descuento, y un estudiante no tiene dos asignaciones aprobadas del mismo descuento que se solapen.
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
- Descuento de campaña que empieza o termina el día de emisión (incluido); concepto no cubierto.
- Convenio sin asignación, pendiente, rechazada o revocada (no se aplica); aprobación de quien la solicitó.
- Ampliar el alcance o cambiar el valor de un descuento ya usado (409); reducirlo (permitido).
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
- Cambiar la marca `stackable` se permite aunque el descuento esté en uso (la nota solo bloquea tipo, valor y alcance); solo afecta a los cargos
  que se emitan después.
- Cinco códigos de problema nuevos: `duplicate-discount-code`, `discount-benefit-locked`, `discount-scope-locked`,
  `discount-assignment-overlap` e `invalid-discount-assignment-state`. La autoaprobación reutiliza `self-approval-forbidden` (403).
- Pendiente de T2: cascada, detalle por beneficio en el cargo, asiento, vista previa, cargos del estudiante y simulador; ampliar «en uso» con los
  cargos emitidos. Pendiente de T3: `packages/contracts` y pantallas. `docs/03-seguridad.md` §5.3 no se actualizó con la segregación de las
  asignaciones de convenio.

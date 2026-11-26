# Corte: becas, otorgamiento con aprobación y aplicación en el devengo (scholarships, corte 1)

Fase F2, entregables 4, 5 y 7, que el propietario retomó el 2026-11-20 tras haberlos diferido. Crea el módulo `scholarships`.
Necesita el devengo de F3 (`charges-accrual`), porque la beca se aplica al generar el cargo. Los descuentos de campaña y de convenio
y la regla de acumulación entre beneficios van en el corte 2 (`scholarships-discounts`). El descuento por pronto pago llega después,
junto a pagos.

## Objetivo

- Que la administración defina las becas de la institución.
- Que se solicite el otorgamiento de una beca a un estudiante y que otra persona lo apruebe.
- Que el motor de devengo aplique la beca aprobada y vigente a los cargos que genere desde ese momento.
- Que un simulador muestre, para un estudiante y un período, el monto que resultará antes de generar nada.

## Reglas e invariantes

- **Beca** (`Scholarship`, docs/02 §3.3):
  - Campos: código único, nombre, tipo de beneficio y conceptos que cubre (uno o más), y un tope opcional por período en HNL.
  - Tipo de beneficio: porcentaje (mayor que 0 y hasta 100, con hasta dos decimales) o monto fijo (mayor que 0, con hasta dos decimales).
  - Se desactiva y se reactiva; no se borra.
  - Cambiar el porcentaje o el monto de una beca con otorgamientos aprobados no está permitido: se crea otra beca. Así un cargo
    emitido siempre se explica con la beca que tenía.
- **Otorgamiento** (`ScholarshipAward`):
  - Liga una beca a un estudiante, para un año lectivo, con una vigencia (`validFrom`, `validTo`) dentro de ese año.
  - Nace `pending`. Pasa a `approved` o `rejected`; un otorgamiento aprobado puede pasar a `revoked`.
  - Quien lo solicita no puede aprobarlo ni rechazarlo (segregación de funciones; `403 self-approval-forbidden`, igual que caja y
    pagos desde la revisión de pagos; la nota decía `409` y se corrige aquí).
  - El rechazo y la revocación exigen un motivo de 20 a 500 caracteres, que no se copia a la bitácora.
  - Un estudiante no puede tener dos otorgamientos aprobados de la misma beca con vigencias que se solapen (restricción en la base de datos).
- **Solo hacia adelante.**
  - La beca solo afecta los cargos que se generen después de aprobarla y dentro de su vigencia. Los cargos ya emitidos no cambian
    (nada financiero se edita).
  - Revocar un otorgamiento solo afecta los cargos futuros.
- **INV-16.** El motor aplica un otorgamiento solo si está `approved` y vigente en la fecha de emisión del cargo, y solo a los conceptos
  que la beca cubre.
- **Cálculo** (servidor, `Money`, `HALF_UP` a dos decimales):
  - Beneficio de porcentaje: base del cargo × porcentaje.
  - Beneficio de monto fijo: el monto.
  - En ambos casos se limita al tope por período, si existe, y a la base del cargo. El beneficio nunca deja la base por debajo de
    cero (INV-15).
- **Impuesto sobre la base reducida.**
  - En un concepto gravado, el impuesto se calcula sobre la base menos la beca. Ejemplo: una base de 1,000.00 con 20 % de beca deja
    una base de 800.00, y el impuesto se calcula sobre 800.00.
  - Hay que validarlo con el contador antes de producción. Queda en la lista de validaciones externas.
- **Más de una beca sobre el mismo cargo.** En este corte se aplica solo la de mayor beneficio. La suma de beneficios llega con la
  regla de acumulación del corte 2.
- **El cargo guarda el detalle:** base bruta, beca aplicada (otorgamiento e importe), base neta, impuesto y total. Los cargos sin beca
  muestran beneficio cero. Nada se recalcula después.
- **Libro mayor: ingreso bruto y cuenta de becas.** El asiento del cargo con beca queda así:
  - débito a la cuenta por cobrar por el total neto;
  - crédito al ingreso por la base bruta;
  - débito a «Becas y descuentos otorgados» por el beneficio;
  - crédito al impuesto por pagar por el impuesto.

  El mapa de cuentas de cargos agrega el propósito `BENEFITS`, que exige una cuenta de tipo resta del ingreso o gasto, según lo que
  valide el contador. Sin esa cuenta en el mapa, un período con becas aplicables bloquea el devengo con `posting-accounts-incomplete`,
  igual que hoy.
- **Idempotencia.** Generar cargos dos veces sigue sin duplicar nada (INV-14). La vista previa y la confirmación usan el mismo cálculo
  con beca. Si un otorgamiento cambia entre la vista previa y la confirmación, no se genera nada (regla vigente del devengo).
- **Simulador.**
  - Recibe un estudiante, un plan y un período, y devuelve por concepto la base, la beca aplicada, la base neta, el impuesto y el total.
  - Usa exactamente el código del motor de devengo y no escribe nada.
- **Auditoría.**
  - Se auditan la creación y el cambio de una beca, y la solicitud, aprobación, rechazo y revocación de un otorgamiento.
  - La consulta del simulador se audita porque son datos de un menor.

## Permisos (ya existen en `Permission`; sin cambio de matriz, `docs/03` §5.2)

- `scholarships:create`: Super Administrador, Administrador y Coordinador Académico (definir becas y solicitar otorgamientos).
- `scholarships:read`: además Cajero, Contabilidad y Auditor.
- `scholarships:approve`: Super Administrador y Administrador (aprobar y rechazar otorgamientos).
- `scholarships:void`: revocar un otorgamiento; también lo tienen solo esos dos roles.

## Endpoints (`/api/v1/scholarships`)

- Becas: `GET` y `POST /scholarships`, `GET` y `PUT /scholarships/{id}`, y `POST /scholarships/{id}/deactivate|reactivate`.
- Otorgamientos:
  - `GET /scholarship-awards?studentId&status&scholarshipId&page&size&sort`;
  - `POST /scholarship-awards` (nace `pending`);
  - `GET /scholarship-awards/{id}`;
  - `POST /scholarship-awards/{id}/approve|reject|revoke`, con `Idempotency-Key`.
- Simulador: `GET /charges/plans/{planId}/simulation?studentId&period` (`charges:read`).
- La vista previa del devengo y los cargos del estudiante agregan `benefitAmount` y `netBase` por cargo.

## Pantallas (admin-web)

- **Becas** (Finanzas › Becas): lista con filtros y alta o edición de una beca (tipo, valor, conceptos y tope).
- **Otorgamientos** (Finanzas › Becas › Otorgamientos):
  - lista con filtros por estado, beca y estudiante;
  - solicitud con selector de estudiante, beca, año y vigencia;
  - detalle con «Aprobar», «Rechazar» y «Revocar».
- **Simulador** (Finanzas › Simulador de cargos): estudiante, plan y período, y la tabla que devuelve el servidor.
- **Cambios en pantallas existentes:**
  - «Generación de cargos» y «Cargos del estudiante» muestran la beca aplicada.
  - El mapa de cuentas agrega «Becas y descuentos».
- Desde la ficha del estudiante se llega a sus becas.

## Casos límite

**Cálculo del beneficio:**
- beca del 100 %;
- monto fijo mayor que la base (queda en cero);
- tope por período menor que el beneficio;
- redondeo `HALF_UP` en porcentajes con fracciones;
- concepto no cubierto;
- concepto gravado con beca (impuesto sobre la base reducida);
- dos becas aprobadas sobre el mismo cargo (se aplica la mayor; con empate, la de código menor).

**Vigencia:**
- otorgamiento vigente que termina a mitad del año;
- vigencia que empieza el mismo día de emisión del cargo (incluida).

**Ciclo del otorgamiento:**
- revocar y volver a generar un período ya generado (no cambia, INV-14);
- otorgamiento pendiente o rechazado (no se aplica);
- aprobación de quien lo solicitó;
- otorgamientos con vigencias que se solapan;
- cambio del otorgamiento entre la vista previa y la confirmación;
- beca desactivada con otorgamientos aprobados (deja de aplicarse a cargos futuros).

**Otros:**
- mapa sin `BENEFITS`;
- otra institución (404);
- RLS.

## Criterio de aceptación

- Un estudiante con beca aprobada del 25 % sobre la mensualidad, con una base de L 1,100.00 y un impuesto del 15 %, recibe un cargo con
  base neta L 825.00 e impuesto L 123.75. El libro mayor registra L 1,100.00 de ingreso y L 275.00 en becas.
- El simulador muestra lo mismo antes de generar.
- Un otorgamiento pendiente o solicitado y aprobado por la misma persona no cambia nada.
- Los cargos emitidos antes de aprobar la beca siguen iguales.

## Fuera de alcance

- Descuentos de campaña y convenio, y la regla de acumulación (corte 2).
- Pronto pago (con pagos).
- Aplicar becas a cargos ya emitidos.
- Becas de otras monedas.
- Notificación al encargado (`ScholarshipAwarded` → Notifications, cuando exista la pantalla de notificaciones).
- Reportes de costo de becas.

## Lo construido (backend T1, 2026-11-24)

Parcial: migración, dominio, casos de uso y adaptadores están construidos y probados; la capa web está escrita pero sin registrar en una
construcción verde (ver "Pendiente").

- **Migración `V36__create_scholarships_core.sql`.** Tablas `scholarships_scholarship`, `scholarships_scholarship_concept`,
  `scholarships_award` y `scholarships_award_decision`, con RLS forzada por institución, `NUMERIC(14,4)` más moneda y sin borrado.
  La base rechaza: beneficio fuera de regla, beca sin conceptos, cambio de tipo o valor de una beca con otorgamientos aprobados o
  revocados, validez fuera del año lectivo, otorgamiento nuevo o aprobación sobre beca inactiva, transiciones de estado distintas de
  pendiente → aprobado|rechazado y aprobado → revocado, decisión de quien solicitó el otorgamiento, y dos otorgamientos aprobados de la
  misma beca y estudiante con vigencias solapadas (restricción de exclusión sobre `daterange`).
- **Dominio puro** (`Benefit`, `Scholarship`, `ScholarshipAward`, `AwardDecision`, `DecisionReason`) con `Money` y `HALF_UP` una sola vez.
- **Casos de uso:** `ManageScholarships`, `RequestAward`, `DecideAward` (aprobar, rechazar, revocar con `Idempotency-Key` permanente en la
  decisión) y `ViewAwards`. Auditoría de todas las acciones de la nota; el motivo solo se audita por su longitud.
- **Pruebas:** 81 unitarias de dominio y 28 de integración contra PostgreSQL real (`ScholarshipsIT`).

### Desviaciones

- La autoaprobación responde `403 self-approval-forbidden` (convención de caja y pagos), no `409`.
- Permisos usados: `scholarships:create` para definir, editar, desactivar, reactivar y solicitar; `approve` para aprobar y rechazar;
  `void` para revocar. En `StaffRole`, el Administrador no tiene `scholarships:void` (solo Super Administrador); la nota decía que
  ambos. No se cambió la matriz: decisión pendiente del propietario.
- El valor del beneficio queda bloqueado si existe un otorgamiento aprobado o revocado alguna vez; nombre, tope y conceptos siguen editables.
- Crear y solicitar no llevan `Idempotency-Key` (convención del catálogo); desactivar y reactivar son idempotentes por estado.
- Que la vigencia esté dentro del año lo valida el trigger de la base, porque `organization` no publica las fechas del año.

### Pendiente

Requiere editar archivos fuera de la superficie autorizada: `PermissionedEndpoints.java` (12 rutas), `ProblemCode.java` y
`i18n/problems.properties` (códigos `duplicate-scholarship-code`, `scholarship-benefit-locked`, `scholarship-award-overlap`,
`invalid-award-state`) y `IdempotencyScopeExclusionInventoryTest` (`ScholarshipAwardController`). Después: registrar la capa web, regenerar
`admin.openapi.json` y `packages/contracts`.

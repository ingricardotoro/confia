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
  - **Solo se puede reducir el alcance** (decisión del propietario, 2026-11-24, «Solo reducir»). Con otorgamientos aprobados o revocados
    alguna vez, el tope por período solo baja (no se sube ni se quita; poner un tope donde no había es bajarlo) y los conceptos que cubre
    solo se quitan (no se agregan). Ampliar significa crear otra beca. Lo exigen el dominio (`409 scholarship-scope-locked`) y la base de
    datos. Sin otorgamientos aprobados, el tope y los conceptos se editan sin restricción.
- **Otorgamiento** (`ScholarshipAward`):
  - Liga una beca a un estudiante, para un año lectivo, con una vigencia (`validFrom`, `validTo`) dentro de ese año.
  - Nace `pending`. Pasa a `approved` o `rejected`; un otorgamiento aprobado puede pasar a `revoked`.
  - Quien lo solicita no puede aprobarlo ni rechazarlo (segregación de funciones; `403 self-approval-forbidden`, igual que caja y
    pagos desde la revisión de pagos; la nota decía `409` y se corrige aquí).
  - El rechazo y la revocación exigen un motivo de 20 a 500 caracteres. El texto vive solo en la decisión del otorgamiento
    (`scholarships_award_decision`); la bitácora guarda en `reason` su huella SHA-256 en hexadecimal (decisión del propietario,
    2026-11-24, «Hash en bitácora») y su longitud, porque el texto libre puede nombrar a una persona.
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
  - **El tope es por otorgamiento y por período** (decisión del propietario, 2026-11-24, «Tope por período»): el beneficio de todos los
    cargos de un período para un otorgamiento nunca supera el tope. La oferta de un cargo es el menor entre el beneficio y lo que queda
    del tope, que es el tope menos el beneficio que ese otorgamiento ya dio en el mismo período (cargos emitidos no anulados, de
    cualquier corrida, y cargos anteriores de la misma evaluación). Un período es el de `period_start` del cargo. Los cargos de un
    estudiante se evalúan en orden de identificador de concepto, y la base de datos verifica la misma suma al insertar cada cargo.
    **Anular un cargo libera su beneficio**: deja de contar para el tope, de modo que un cargo emitido después en ese período puede
    tomarlo. Los cargos ya emitidos nunca cambian (INV-14).
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

## Permisos (ya existen en `Permission`; el Administrador gana `scholarships:void`, `docs/03` §5.2, nota del 2026-11-24)

- `scholarships:create`: Super Administrador, Administrador y Coordinador Académico (definir becas y solicitar otorgamientos).
- `scholarships:read`: además Cajero, Contabilidad y Auditor.
- `scholarships:approve`: Super Administrador y Administrador (aprobar y rechazar otorgamientos).
- `scholarships:void`: revocar un otorgamiento; también lo tienen solo esos dos roles. Decisión del propietario, 2026-11-24: «si, si puede el
  adminsitrador revocar una beca, con justificacion». La justificación es el motivo obligatorio de 20 a 500 caracteres.

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
- beca de dos conceptos con un tope menor que la suma de sus beneficios, en una corrida y en dos corridas del mismo período (el tope se
  comparte);
- anular un cargo y generar otro del mismo período (el beneficio anulado se libera);
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
- subir o quitar el tope, o agregar un concepto, de una beca con otorgamientos aprobados (409); bajar el tope o quitar un concepto (permitido);
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

Backend de T1 completo: migración, dominio, casos de uso, adaptadores, capa web y contrato. Quedan T2 (devengo y simulador) y T3.

- **Migración `V36__create_scholarships_core.sql`.** Tablas `scholarships_scholarship`, `scholarships_scholarship_concept`,
  `scholarships_award` y `scholarships_award_decision`, con RLS forzada por institución, `NUMERIC(14,4)` más moneda y sin borrado.
  La base rechaza: beneficio fuera de regla, beca sin conceptos, cambio de tipo o valor de una beca con otorgamientos aprobados o
  revocados, validez fuera del año lectivo, otorgamiento nuevo o aprobación sobre beca inactiva, transiciones de estado distintas de
  pendiente → aprobado|rechazado y aprobado → revocado, decisión de quien solicitó el otorgamiento, y dos otorgamientos aprobados de la
  misma beca y estudiante con vigencias solapadas (restricción de exclusión sobre `daterange`).
- **Dominio puro** (`Benefit`, `Scholarship`, `ScholarshipAward`, `AwardDecision`, `DecisionReason`) con `Money` y `HALF_UP` una sola vez.
- **Casos de uso:** `ManageScholarships`, `RequestAward`, `DecideAward` (aprobar, rechazar, revocar con `Idempotency-Key` permanente en la
  decisión) y `ViewAwards`. Auditoría de todas las acciones de la nota; el motivo se audita por su huella SHA-256 y su longitud (corregido en T4; antes solo la longitud).
- **Pruebas:** 81 unitarias de dominio y 28 de integración contra PostgreSQL real (`ScholarshipsIT`).

### Desviaciones

- La autoaprobación responde `403 self-approval-forbidden` (convención de caja y pagos), no `409`.
- Permisos usados: `scholarships:create` para definir, editar, desactivar, reactivar y solicitar; `approve` para aprobar y rechazar;
  `void` para revocar. El worker anterior encontró que `StaffRole` no daba `scholarships:void` al Administrador; el propietario decidió el
  2026-11-24 que sí puede revocar, con justificación. Se agregó `SCHOLARSHIPS_VOID` a `ADMINISTRATOR`, la fila `scholarships` de la matriz
  efectiva de `docs/03` §5.2 pasó a `C L A N P X` y hay una nota fechada 2026-11-24. Pruebas: `StaffRoleTest`, `PermissionMatrixParityTest` y
  `ScholarshipsIT` (el Administrador revoca con motivo válido y se le rechaza con uno corto).
- El valor del beneficio queda bloqueado si existe un otorgamiento aprobado o revocado alguna vez; el nombre sigue editable. El tope y los
  conceptos solo se reducen (decisión del propietario del 2026-11-24, ver «Reglas e invariantes»; en T1 seguían editables sin límite).
- Crear y solicitar no llevan `Idempotency-Key` (convención del catálogo); desactivar y reactivar son idempotentes por estado.
- Que la vigencia esté dentro del año lo valida el trigger de la base, porque `organization` no publica las fechas del año.

### Capa web y contrato

- Controladores `ScholarshipController` (`/api/v1/scholarships`) y `ScholarshipAwardController` (`/api/v1/scholarship-awards`) con DTO
  explícitos, Bean Validation, importes `{ amount, currency }` y paginación por servidor (máximo 200). Registrados solo en el punto de
  entrada administrativo. Las 12 rutas están en `PermissionedEndpoints`; hay cuatro códigos nuevos (`duplicate-scholarship-code`,
  `scholarship-benefit-locked`, `scholarship-award-overlap`, `invalid-award-state`, todos 409).
- `admin.openapi.json` regenerado (12 operaciones nuevas; el portal no cambia) y `packages/contracts` regenerado con `pnpm generate`.

### Preguntas abiertas

- ~~El mensaje de `self-approval-forbidden` habla de pagos.~~ Resuelto en T4: el texto es neutro y vale para pagos y otorgamientos.

## Lo construido (backend T2, 2026-11-24)

Integración con el devengo completa en el backend: la beca aprobada y vigente se aplica al cargo, el asiento registra el ingreso bruto y la
beca, la vista previa, los cargos del estudiante y el simulador muestran el detalle. Queda T3 (contrato y pantallas).

- **Migración `V37__add_scholarship_benefit_to_charges.sql`** (solo agrega; ningún cargo existente se toca):
  - El cargo guarda el otorgamiento aplicado (`scholarship_award_id`, con clave foránea) y el código de la beca (`scholarship_code`). La base
    neta (`net_base_amount`, `net_base_currency`) es una columna generada: base menos beneficio. El beneficio es la columna `discount_amount`
    que ya existía desde V32, así que base bruta, beneficio, base neta, impuesto y total quedan en `NUMERIC(14,4)` con su moneda.
  - La base de datos rechaza un beneficio sin otorgamiento (y al revés) y un otorgamiento que no esté aprobado, que sea de otro estudiante,
    que no cubra la fecha del cargo (ambos extremos incluidos), que sea de una beca inactiva o que no cubra el concepto. También rechaza un
    beneficio distinto del que da la beca (porcentaje o monto fijo, limitado al tope y a la base) y un asiento cuyo único otro débito no sea
    el beneficio.
  - El propósito `BENEFITS` entra al mapa de cuentas.
- **Dominio de `charges` (puro, con `Money` y `HALF_UP`).** `BenefitOffer` y `BenefitSelection` eligen la oferta de mayor beneficio y, con
  empate, la beca de código menor; `ChargeEvaluator` calcula el impuesto sobre la base reducida con un solo redondeo y el beneficio nunca deja
  la base neta por debajo de cero. `IntegrityCheck` compara cada lado del asiento contra total más beneficio.
- **`scholarships` publica `AwardBenefits`** (`application/api`, interfaz nombrada `api`): para un estudiante, un concepto, una fecha y una
  base, qué ofrece cada otorgamiento aprobado y vigente. El beneficio lo calcula `Benefit.on` de la beca, la única fórmula; `charges` no importa
  el dominio de `scholarships`. El proceso trabajador carga solo el adaptador de lectura (`ScholarshipsWorkerConfiguration`).
- **Motor.** La vista previa, la ejecución del trabajador y el simulador pasan por el mismo `AccrualEngine`. La fecha de vigencia es el primer
  día del período, que es también la fecha de precio y la del asiento. Los cargos ya emitidos nunca cambian: generar de nuevo los encuentra y no
  crea nada (INV-14), aunque el otorgamiento se apruebe o se revoque después.
- **Libro mayor.** Una sola transacción por cargo: débito a la cuenta por cobrar por el total, crédito al ingreso por la base bruta, débito a
  `BENEFITS` por el beneficio y crédito al impuesto por pagar. El ejemplo del criterio de aceptación (base 1,100.00, beca 25 %, impuesto 15 %)
  da base neta 825.00, impuesto 123.75, total 948.75, ingreso 1,100.00 y becas 275.00; el asiento cuadra.
- **Lectura.** La vista previa agrega `benefitAmount` y `netBase` por fila y por total, y `scholarshipAwardId` y `scholarshipCode` por fila.
  Los cargos del estudiante agregan los cuatro campos. El simulador es `GET /api/v1/charges/plans/{planId}/simulation?studentId&period`
  (`charges:read`, `period` es el primer día del período, `yyyy-MM-dd`) y responde por concepto: resultado, base, `benefitAmount`, `netBase`,
  beca (otorgamiento y código), impuesto y total. Se audita cada consulta (`charges.simulation.viewed`, sin importes ni nombres).
- **Pruebas.** Unitarias de dominio (selección, evaluador, mapa, verificación de integridad, servicio de ofertas), `AwardBenefitsIT` (9) y
  `AccrualScholarshipsIT` (25) contra PostgreSQL real, y dos de ruta en `AccrualEndpointsIT`.

### Supuestos pendientes del contador (S4)

Siguen en la lista de validaciones externas y están implementados así hasta que el contador responda:

- El impuesto se calcula sobre la base reducida.
- `BENEFITS` acepta una cuenta de ingreso (resta del ingreso) o de gasto que no lleve saldos de estudiantes. Una cuenta de otro tipo deja el
  mapa incompleto para un período con becas aplicables.

### Desviaciones

- **Un cargo puede quedar en cero.** Una beca del 100 % (o un monto fijo que alcanza la base) deja el total en cero, y el libro mayor no admite
  una línea de monto cero. Por eso la migración relaja `total_amount > 0` a `>= 0`, el asiento no lleva línea a la cuenta del estudiante y el
  trigger del cargo y el de la anulación esperan cero líneas en ese caso. Ese cargo no aparece en el estado de cuenta (que sale de las
  líneas del libro), no se ofrece a un pago (`findActiveByStudent` lo excluye) y se puede anular; la verificación nocturna lo revisa.
- **El asiento es una sola transacción**, no una transacción `DISCOUNT` aparte como anticipaba el comentario de `BenefitsStep`; el trigger de
  V32 que exigía un único débito se reemplazó.
- **La fórmula del beneficio no se repite en `charges`.** `scholarships` calcula el monto de cada oferta (`Benefit.on`) y `charges` solo elige
  y aplica; la base de datos repite la regla en un `CHECK`/trigger, como ya hacía con el impuesto.
- **Bloqueo bajo `posting-accounts-incomplete`.** Solo bloquea (vista previa y ejecución) cuando algún estudiante de la población tiene un
  otorgamiento aplicable a un concepto del período; sin becas aplicables no se pide la cuenta `BENEFITS`.
- **Resumen de la vista previa:** el digest pasó a la versión `v2` e incluye el beneficio y el otorgamiento, de modo que revocar entre la vista
  previa y la confirmación rechaza la ejecución con `preview-outdated`.
- **Simulador:** un estudiante que no está matriculado en el destino del plan en ese período responde `404 resource-not-found`; una fecha que
  no es el inicio de un período responde `400 validation-failed`.
- **Compatibilidad:** `ManageAccrual` y `ExecuteAccrualRun` conservan el constructor sin `AwardBenefits` (sin becas) porque `PaymentsFixture`,
  fuera de esta superficie, lo usa; los procesos reales usan el constructor nuevo.
- La auditoría de la ejecución (`CurrencyTotals`) sigue sumando base, impuesto y total; no suma el beneficio.

### Preguntas abiertas

- ¿El contador acepta el tratamiento de S4 (impuesto sobre la base reducida y cuenta `BENEFITS` de ingreso o gasto)?
- ¿Debe el estado de cuenta mostrar el cargo de total cero de una beca del 100 %? Hoy no aparece porque no mueve el auxiliar del estudiante.
- No hay prueba de concurrencia del bloqueo `FOR SHARE` de las becas (revocar mientras el trabajador evalúa un estudiante); la lectura bajo
  bloqueo se prueba, la espera no.

## Lo construido (T4, correcciones de la revisión, 2026-11-24)

Corrige los hallazgos de la revisión (confia-code-reviewer y confia-security-auditor) con las decisiones del propietario L8 a L10.

- **Tope por período compartido (B1).** `Benefit.on(base, tope, consumido)` resta lo que el otorgamiento ya dio en el período;
  `AwardBenefits.offers` recibe la función `alreadyGiven` (los cargos son de `charges`, no de `scholarships`) y `AccrualEngine` suma los
  cargos emitidos no anulados (`ChargeRepository.benefitGiven`) y los de la misma evaluación, en orden de concepto. La migración
  `V38__share_scholarship_cap_per_period_and_narrow_scope.sql` hace que el trigger del cargo verifique la misma suma (V37 no se edita).
  Pruebas: `BenefitTest`, `AwardBenefitsServiceTest`, y en `AccrualScholarshipsIT` una beca de dos conceptos en una corrida, en dos
  corridas, con la vista previa, con un cargo anulado y con un motor que olvida lo consumido (la base lo rechaza con `POSTING_FAILED`).
  El cambio de período (marzo, con el tope completo otra vez) no tiene prueba de integración porque el reloj fijo del fixture impide
  generar un período futuro; lo cubre la condición `period_start` del trigger y de la consulta.
- **Alcance reducible (A1).** `Scholarship.edit` lanza `ScholarshipScopeLockedException` (`409 scholarship-scope-locked`); el trigger de la
  beca (V38) rechaza subir o quitar el tope y un trigger `BEFORE INSERT` sobre los conceptos rechaza agregar uno. Pruebas de dominio, de
  integración (`ScholarshipsIT`, incluidas las sentencias directas a la base) y de ruta (`ScholarshipsEndpointsIT`).
- **Hash del motivo (M2).** El `reason` de la bitácora del rechazo y de la revocación es el SHA-256 del motivo de la decisión.
  `docs/03-seguridad.md` §5.2 (nota del 2026-11-24) quedó corregido.
- **Pruebas HTTP (M1).** `ScholarshipsEndpointsIT` (403 de Cajero en aprobar y rechazar, 403 de Coordinador en revocar, 401 con sesión
  revocada, 400 con motivo corto y con propiedad no declarada, 400 `idempotency-key-missing`, 404 de otra institución,
  403 `self-approval-forbidden`) y una prueba del simulador en `AccrualEndpointsIT`. Justifican la exclusión de `/api/v1/scholarship` en
  `AuthorizationEdgeIT`.
- **Otros.** Mensaje neutro de `self-approval-forbidden`; pruebas de RLS con una decisión existente y con escrituras de otra
  institución; `requestedBy` en el `before_value` de la bitácora de aprobación; import duplicado de `ChargeEvaluator` eliminado.
- **Documentación de seguridad.** `docs/03-seguridad.md` §5.3: excepción de la revocación sin segundo aprobador (L3) y brecha de control
  registrada: las aprobaciones pendientes no caducan a las 72 horas (becas y pagos); no se implementó el vencimiento.

## Lo construido (pantallas T3 y correcciones T4 del frontend, 2026-11-24)

- **Pantallas nuevas** en `apps/admin-web/src/features/scholarships`, dentro del grupo Finanzas:
  - Becas: lista con filtros, alta y edición, desactivar y reactivar. Con otorgamientos aprobados, el tipo y el valor quedan de solo
    lectura y el formulario explica que el tope solo puede bajar y los conceptos solo pueden quitarse.
  - Otorgamientos: lista con filtros por estado, beca y estudiante; solicitud; detalle con «Aprobar», «Rechazar» y «Revocar». Cada
    decisión envía una `Idempotency-Key` que se reutiliza al reintentar; rechazar y revocar exigen un motivo de 20 a 500 caracteres.
  - Simulador de cargos en `/scholarships/simulator`, con el permiso `charges:read`: muestra la tabla que devuelve el servidor.
- **Pantallas existentes:** «Generación de cargos» y «Cargos del estudiante» muestran la beca, el beneficio y la base neta; el mapa de
  cuentas agrega «Becas y descuentos» (cuenta de ingreso o de gasto, S4); la lista de estudiantes enlaza a los otorgamientos de cada uno.
- **Errores:** `outside-academic-year` se muestra bajo el inicio de la vigencia y `scholarship-scope-locked` dentro del diálogo de edición.
- **Pruebas:** componente con Vitest, Testing Library y MSW (estados, decisiones, validación del motivo y las cifras del criterio de
  aceptación en el simulador: L 1,100.00, L 275.00, L 825.00, L 123.75 y L 948.75), recorrido de Playwright solicitud → aprobación →
  simulación con el rechazo de la autoaprobación, y la puerta de axe sobre los once estados nuevos en tema claro y oscuro.

### Desviaciones y pendientes

- Los otorgamientos muestran solo el código del estudiante: el contrato no trae su nombre.
- La fila «Becas y descuentos» no entra en el aviso de mapa incompleto; la vista previa del devengo ya bloquea con
  `posting-accounts-incomplete` cuando una beca se aplica.
- La API simulada aplica becas solo en el simulador, no en la vista previa ni en la corrida.
- `dependency-cruiser` no se ejecutó en local (no está instalado); se revisó a mano que ninguna feature importe a otra.
- Las aprobaciones pendientes no caducan a las 72 horas (brecha registrada en `docs/03-seguridad.md` §5.3).

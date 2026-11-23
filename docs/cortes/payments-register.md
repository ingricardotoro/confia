# Corte: registro de pagos en efectivo y transferencia declarada (payments, corte 2)

Fase F4, corte 2. Requiere las sesiones de caja del corte 1 (`cashbox-sessions`): el efectivo solo se cobra con una sesión abierta
(INV-20). Este corte crea el módulo `payments`. Es el primero que acredita la cuenta por cobrar del estudiante con dinero recibido.

## Objetivo

Que un cajero registre en ventanilla el pago de un estudiante, en efectivo o como transferencia declarada.

- **Efectivo.** Se asienta en el libro mayor en ese momento, se aplica a los cargos pendientes del más antiguo al más reciente y
  suma a lo esperado de la caja.
- **Transferencia declarada.** Queda registrada sin tocar el saldo. Solo cuando una persona autorizada la confirma se asienta y se
  aplica. Si se rechaza, no deja ningún asiento.

## Reglas e invariantes

- **Estados (docs/02 §6.2).**
  - El efectivo nace `confirmed`.
  - La transferencia nace `declared` y pasa a `confirmed` o a `rejected` (rechazada). `reversed` queda reservado al reverso de un
    pago confirmado (corte 4).
  - Solo `confirmed` produce asiento. Un pago `declared` no cambia el saldo, no se aplica a cargos y no aparece en el estado de cuenta.
  - Este corte no usa `draft`, ni el reverso de un pago confirmado (corte 4).
- **INV-19.** El importe es mayor que cero, con hasta dos decimales y en la moneda de la institución (HNL).
- **INV-20.** El efectivo exige una sesión de caja `open` del usuario que registra. El pago y su movimiento `payment_in` en esa sesión se
  escriben en la misma transacción.
  - **Fecha del efectivo (decisión del propietario, 2026-11-21: «Solo el día de la sesión»).** `receivedOn` de un pago en efectivo es
    el día de negocio de la sesión abierta, es decir, hoy en la zona horaria de la institución mientras la sesión está abierta. Otro
    día se rechaza con `400 validation-failed` y el campo `receivedOn`. La base lo repite por disparador. La transferencia conserva su
    regla: ni futura ni anterior al año 2000.
  - Lo esperado de la caja suma los `payment_in` sin cambiar la fórmula del corte 1.
  - La transferencia no toca la caja.
- **Asiento al confirmar:**
  - débito a la cuenta de caja (efectivo) o de banco (transferencia);
  - crédito a la cuenta por cobrar del estudiante;
  - una sola transacción del libro mayor, con bloqueo sobre la subcuenta del estudiante.
- **Mapa de cuentas de pagos.** Las cuentas se toman de un mapa de pagos con dos propósitos, `CASH` y `BANK`. Cada uno exige una
  cuenta `ASSET` activa que no lleve saldos de estudiantes. Sin mapa completo, registrar efectivo o confirmar una transferencia
  responde `409 posting-accounts-incomplete`.
- **Aplicación automática a cargos (INV-17).**
  - Al confirmar, el servidor reparte el importe entre los cargos vigentes con saldo pendiente, ordenados por vencimiento, período y
    código de concepto.
  - Nunca excede el pendiente de un cargo. La serialización es el bloqueo de la subcuenta del estudiante en el libro mayor: la tabla
    de cargos no concede `UPDATE` (INV-47), así que no se bloquean las filas de los cargos. Quien escribe cargos, pagos o anulaciones
    de ese estudiante toma primero ese bloqueo, y los disparadores de la base lo repiten.
  - Se admiten pagos parciales: un cargo puede quedar con pendiente.
- **Remanente (INV-18).** Lo que sobra después de cubrir todo lo pendiente queda acreditado en la misma cuenta por cobrar. El saldo
  del estudiante queda negativo y el estado de cuenta ya lo rotula «Saldo a favor». La suma de aplicaciones nunca excede el pago.
- **Pendiente de un cargo.** Es el total del cargo menos sus aplicaciones. Se deriva siempre; no se guarda como campo mutable (regla 4).
  - Un cargo con aplicaciones no se puede anular: `409 charge-has-payments`, que se agrega a `VoidCharge`.
  - Para anularlo, primero se reversa el pago (corte 4).
- **Confirmación y rechazo de una transferencia.**
  - Requieren `payments:approve` (Super Administrador y Administrador, `docs/03` §5.2).
  - Quien confirma o rechaza no puede ser quien registró el pago (segregación de funciones; `403 self-approval-forbidden`, como
    `cash-approval-same-actor` en la caja, `docs/03` §5.3).
  - El rechazo exige un motivo de 20 a 500 caracteres, que no se copia al libro mayor ni a la bitácora.
- **Transferencia.** Exige una referencia bancaria de 4 a 40 caracteres. La pareja referencia y banco no se repite entre los pagos no
  rechazados de la institución (`409 duplicate-transfer-reference`).
- **Nada se borra ni se edita.** Pagos, aplicaciones y movimientos son de solo inserción. Los cambios de estado son transiciones
  registradas.
- **Idempotencia (INV-24).** `Idempotency-Key` es obligatoria en registrar, confirmar y rechazar, y va ligada al actor, como en
  `charges-void`. Un doble envío produce un solo pago.
- **Bitácora.** Toda escritura se audita: `payments.payment.registered`, `payments.payment.confirmed` y `payments.payment.rejected`.
  La bitácora no guarda nombres ni la referencia completa; la referencia se enmascara.

## Permisos (ya existen en `Permission`; sin cambio de matriz)

- `payments:create`: Cajero y Super Administrador. El Administrador no cobra (`docs/03` §5.2, regla 5).
- `payments:read`:
  - el Cajero, solo los pagos que él registró;
  - Administrador, Contabilidad, Auditor y Super Administrador, todos.
- `payments:approve`: Administrador y Super Administrador; sirve para confirmar y rechazar transferencias.

## Endpoints (`/api/v1/payments`)

- `POST /payments` (`payments:create`): registra un pago.
  - Cuerpo: `{studentId, method: CASH|BANK_TRANSFER, amount: {amount, currency}, receivedOn, reference?, bankName?, notes?}`.
  - Responde `201` con el pago, su estado y, si quedó confirmado, sus aplicaciones y el remanente.
- `GET /payments?studentId&status&method&from&to&page&size&sort` (`payments:read`): lista paginada en el servidor.
- `GET /payments/{id}` (`payments:read`): detalle con sus aplicaciones (cargo, concepto, período e importe aplicado).
- `POST /payments/{id}/confirm` (`payments:approve`): asienta y aplica una transferencia `declared`.
- `POST /payments/{id}/reject` (`payments:approve`): rechaza una transferencia `declared`. Cuerpo: `{reason}`.
- `GET` y `PUT /payments/posting-accounts` (`ledger:read` y `ledger:create`): el mapa `CASH` y `BANK`, con un upsert parcial
  versionado como el mapa de cargos.
- `GET /charges/students/{studentId}/charges`: agrega a cada cargo `outstanding` (pendiente derivado), `paid` y `settled`.

## Pantallas (admin-web)

- **Registrar pago** (Finanzas › Cobrar, `payments:create`):
  - Se elige al estudiante y se ven sus cargos pendientes y el total pendiente, tal como los entrega el servidor.
  - Se eligen el método, el importe y la fecha; para una transferencia, también la referencia y el banco.
  - Con efectivo y sin caja abierta, la pantalla lo dice y ofrece «Ir a Mi caja».
  - Al guardar se muestran las aplicaciones y el remanente que calculó el servidor. El navegador no reparte ni suma importes.
- **Pagos** (Finanzas › Pagos, `payments:read`):
  - Lista con filtros por estado, método y fechas.
  - El detalle de un pago muestra sus aplicaciones. Una transferencia declarada ofrece «Confirmar» y «Rechazar», si se tiene
    `payments:approve`.
- **Plan de cuentas.** El mapa agrega «Caja» y «Banco».
- **Estado de cuenta y cargos del estudiante.** Los pagos confirmados aparecen como abonos. Cada cargo muestra lo pagado y lo pendiente.
- Los textos van en el catálogo es-HN. Cada pantalla tiene sus estados de carga, vacío, error y éxito, y pasa la puerta de axe.

## Casos límite

**Importe:**
- cero, negativo, tres decimales u otra moneda;
- exacto al pendiente;
- mayor que todo lo pendiente (remanente a favor);
- estudiante sin cargos pendientes (todo a favor);
- dos cargos con el mismo vencimiento (desempate estable).

**Efectivo:**
- sin sesión abierta;
- con la sesión cerrada entre la vista y el envío;
- con una sesión de otro cajero.

**Transferencia:**
- confirmada dos veces;
- confirmada y rechazada en paralelo (solo una gana);
- confirmada o rechazada por quien la registró;
- con referencia repetida;
- confirmada después de anular uno de los cargos pendientes (se aplica a lo que esté vigente en ese momento).

**Concurrencia, idempotencia y otros:**
- dos pagos simultáneos del mismo estudiante (el bloqueo impide exceder el pendiente);
- doble envío con la misma clave;
- clave repetida por otro actor;
- mapa de cuentas incompleto;
- anular un cargo que tiene pagos;
- otra institución (404);
- cajero que lee un pago ajeno (404).

## Criterio de aceptación

- Un cajero con la caja abierta cobra L 1,500.00 en efectivo a un estudiante que debe dos mensualidades de L 1,265.00. Se cubre por
  completo la más antigua y se abonan L 235.00 a la siguiente.
- El estado de cuenta muestra el abono y el saldo nuevo. Lo esperado de la caja sube L 1,500.00.
- Sin caja abierta, el mismo cobro se rechaza.
- Una transferencia declarada no cambia el saldo. El administrador la confirma y entonces se aplica. Si la rechaza, el saldo no cambia
  nunca.
- Un doble clic en «Registrar pago» produce un solo pago.

## Fuera de alcance

- Tarjeta y pasarela de pago.
- Aplicación manual por selección de cargos (corte 3).
- Comprobante interno imprimible (corte 3).
- Reverso de un pago confirmado con aprobación (corte 4).
- Saldos a favor aplicados a deuda futura, transferidos a un hermano o reembolsados (corte 4).
- Conciliación bancaria automática (F7) y factura fiscal (F5).
- Elegir al encargado que paga: se guarda el `guardianId` si viene, pero la pantalla no lo pide en este corte.

## Lo construido (backend, 2026-11-20)

Esta sección describe el backend y el contrato OpenAPI. Las pantallas y los contratos del frontend quedan para la tarea siguiente.

- **Migración `V35__create_payments_core.sql`**: `payments_posting_account` (mapa `CASH` y `BANK`, con versión), `payments_payment`,
  `payments_payment_decision` y `payments_payment_allocation`. Un pago de efectivo nace `confirmed` con su sesión de caja, su
  movimiento `payment_in` y su asiento; una transferencia nace `declared` sin sesión, sin movimiento y sin asiento. La decisión
  (confirmar o rechazar) es una fila de solo inserción, una por pago; el estado del pago es la única columna mutable, solo avanza
  `declared → confirmed | rejected` y solo con la decisión que lo justifica. Pagos, decisiones y aplicaciones rechazan UPDATE (salvo
  ese avance), DELETE y TRUNCATE a todo rol. Reglas que la base repite por disparador: importe mayor que cero y en la unidad menor
  (INV-19); el efectivo exige la sesión abierta del cajero que registra y un movimiento `payment_in` del mismo importe escrito por
  él (INV-20); un asiento `PAYMENT` del módulo `payments` que nombra al pago, con la fecha de recepción y débitos iguales al importe;
  quien registra no decide (segregación); la pareja banco y referencia no se repite entre los no rechazados (índice único parcial
  sin distinguir mayúsculas); una aplicación nunca excede el total del cargo ni el importe del pago, solo a cargos del mismo
  estudiante, en la misma moneda y no anulados (INV-17, INV-18; el disparador toma primero el bloqueo de la subcuenta del estudiante
  en el libro mayor); un `payment_in` que ningún pago reclama al confirmar la transacción (disparador diferido); y la anulación de
  un cargo con aplicaciones. En `cashbox_movement` el tipo `payment_in` es nuevo; la fórmula de lo esperado no cambia. RLS forzada
  por institución; `confia_admin_app` solo tiene `SELECT`, `INSERT` y `UPDATE (status)` (y las columnas del mapa).
- **Paquete `com.confia.payments`**: dominio puro con `Money` (`Payment`, `PaymentRequest`, `AllocationPlan`, `OpenCharge`,
  `PaymentCoverage`, `PaymentDecision`, `TransferReference`, `RejectionReason`, `PostingAccounts`); el reparto es
  `AllocationPlan` (vencimiento, período, código de concepto e identificador como desempate estable) y el remanente queda
  acreditado en la misma cuenta por cobrar. Casos de uso `RegisterPayment`, `ConfirmPayment`, `RejectPayment`, `ListPayments`,
  `GetPayment`, `ManagePostingAccounts`; adaptadores jOOQ; `PaymentController` y `PaymentPostingAccountController`.
- **Integración sin ciclos entre módulos** (todo por API publicada, sin importar dominios): `ledger` publica `postPayment`;
  `cashbox` publica `CashPayments` (la sesión abierta del cajero se bloquea `FOR UPDATE` y el movimiento se escribe en la
  transacción del pago); `charges` publica `PayableCharges` (cuenta por cobrar, cargos vigentes y rótulos de cargo) y el puerto
  `ChargePayments`, que `payments` implementa (lo pagado por cargo y si un cargo tiene aplicaciones). Así `payments` depende de
  `charges` y nunca al revés. `VoidCharge` responde `409 charge-has-payments` bajo el mismo bloqueo de la subcuenta;
  `GET /charges/students/{studentId}/charges` agrega `paid` y `outstanding`, derivados.
- **Orden de bloqueo** (efectivo y confirmación): subcuenta del estudiante en el libro mayor, luego la sesión de caja (efectivo) o la
  fila del pago (confirmación). El rechazo solo bloquea la fila del pago y no abre la subcuenta. Confirmar y rechazar a la vez
  dejan un solo ganador. La tabla de cargos no concede `UPDATE` (INV-47), así que la fila del cargo no se bloquea: la serialización
  es la subcuenta del estudiante, que toman todos los que escriben cargos o pagos de ese estudiante, y el disparador de aplicaciones
  lo repite en la base.
- **Idempotencia**: `Idempotency-Key` permanente en el pago (registrar) y en la decisión (confirmar y rechazar), con el hash de lo
  pedido incluido el actor; misma clave y mismo cuerpo devuelve lo mismo, otro cuerpo u otro actor 422
  `idempotency-payload-mismatch`. Un doble envío simultáneo produce un solo pago.
- **Errores** (`ProblemCode`): `posting-accounts-incomplete`, `duplicate-transfer-reference`, `self-approval-forbidden`,
  `payment-not-declared` y `charge-has-payments`, todos 409 salvo `self-approval-forbidden`, que responde 403; el efectivo sin sesión abierta responde `cash-session-not-open` (409,
  código existente). `posting-account-invalid`, `inactive-reference`, `stale-version` y `resource-not-found` se reutilizan en el mapa.
- **Decisiones del corte que la nota no fijaba**: (1) el mapa se exige completo (`CASH` y `BANK`, más la cuenta por cobrar de los
  cargos) para registrar efectivo o confirmar; declarar una transferencia no lo necesita; (2) la fecha de recepción no puede ser
  futura ni anterior al año 2000; (3) el efectivo no admite referencia ni banco (se rechaza, no se ignora); (4) `guardianId` se guarda
  si viene y un encargado que no es de la institución responde 400; (5) la referencia se guarda completa en el pago y se enmascara
  (últimos cuatro caracteres) en la bitácora y en `toString`; el motivo del rechazo solo vive en la decisión; (6) el Cajero queda
  limitado a sus pagos con `StaffRole.ownScoped` (`payments:read`), como en la caja; las lecturas no se auditan (la nota audita solo
  las escrituras).
- **Pruebas**: `AllocationPlanTest` (con jqwik), `PaymentRequestTest`, `PaymentTest`, `ChargeBalanceTest`, `VoidChargeTest`,
  `CashboxUseCasesTest` (dominio y casos de uso con dobles); `RegisterPaymentIT` y `DecidePaymentIT` (PostgreSQL real: ejemplo de
  aceptación, exacto, remanente a favor, sin cargos, parcial, sin sesión, sesión cerrada, sesión de otro cajero, mapa incompleto,
  doble envío simultáneo, dos pagos simultáneos del mismo estudiante, anulación de un cargo con pagos, RLS, alcance del cajero,
  paginación, bitácora, rechazos de la base por SQL, confirmar y rechazar a la vez, autoaprobación, referencia repetida, confirmar
  después de anular un cargo); `PaymentEndpointsIT` (proceso administrativo real: flujo por HTTP, permiso de cada rol, validación
  del borde, códigos de error, mapa de cuentas, aislamiento entre instituciones); más las listas fijadas de rutas, permisos, OpenAPI,
  claves de idempotencia, aislamiento de beans por proceso y las reglas de ArchUnit (lectura de pagos solo por casos de uso con
  alcance, con sus dos fixtures inválidos permanentes).

## Lo construido (frontend, 2026-11-20)

Esta sección describe las pantallas de `apps/admin-web`, su API simulada y las pruebas. El contrato (`packages/contracts`) se regeneró con orval y no se versiona.

- **Registrar pago** (`/payments/register`, Finanzas › Cobrar).
  - Se busca al estudiante en el servidor y se muestran sus cargos vigentes, del vencimiento más antiguo al más reciente, con lo pagado y lo pendiente tal como los entrega el servidor. El saldo del estudiante es el cierre de su estado de cuenta; el navegador no suma importes. Un cargo cuyo pendiente es cero no se ofrece como pendiente: se juzga el texto, no un número.
  - Método, importe, fecha y, en transferencia, referencia (4 a 40 caracteres) y banco. La validación del cliente solo mira caracteres; el servidor decide el resto y sus errores se muestran bajo su campo (importe, referencia repetida, fecha).
  - Con efectivo y sin caja abierta la pantalla lo dice, deshabilita el botón y ofrece «Ir a Mi caja». Si la sesión se cierra entre la vista y el envío, el rechazo `cash-session-not-open` se cuenta en el formulario y la caja se vuelve a leer. Una transferencia no pide caja.
  - La clave `Idempotency-Key` se crea una vez por intención (la misma si la respuesta no llegó, otra si cambió el cuerpo) y un doble clic envía una sola petición. El resultado reemplaza al formulario y muestra las aplicaciones y el remanente que calculó el servidor; una transferencia declarada dice que no cambia el saldo.
- **Pagos** (`/payments`, Finanzas › Pagos) y detalle (`/payments/:id`). La lista pagina, ordena y filtra en el servidor (estado, método, fechas) y todo vive en la dirección. El detalle muestra los datos, las aplicaciones de un pago confirmado y la decisión. Una transferencia declarada ofrece «Confirmar» y «Rechazar» en diálogos explícitos; el rechazo exige un motivo de 20 a 500 caracteres. Los rechazos del servidor (`self-approval-forbidden`, `payment-not-declared`, `posting-accounts-incomplete`, 403) se cuentan dentro del diálogo.
- **Plan de cuentas**: el mapa agrega el grupo «Cuentas de pagos» con «Caja» y «Banco» (activos que no llevan saldos de estudiantes). Son otro endpoint, así que el guardado hace dos peticiones, primero cargos y luego pagos, cada una solo con lo que cambió y con su versión; si la segunda falla tras guardar la primera, el aviso lo dice y queda pendiente solo lo de pagos.
- **Cargos del estudiante**: columnas «Pagado» y «Pendiente». El servidor responde `charge-has-payments` al anular un cargo con pagos y la pantalla lo dice.
- **Estado de cuenta**: sin cambios; ya rotulaba el tipo `PAYMENT` y el abono llega tal como lo devuelve el servidor. **Caja**: el movimiento `payment_in` aparece en la tabla de movimientos como «Cobro en efectivo».
- **Invalidación tras una escritura**: registrar, confirmar y rechazar invalidan los pagos, los estados de cuenta, los cargos del estudiante y la caja. Los indicadores del tablero no tienen consulta todavía. No hay actualización optimista.
- **Piezas compartidas movidas a `src/shared`** (los features no se importan entre sí): las claves de consulta de la caja, de los estados de cuenta y de los cargos del estudiante, el selector de estudiante, la clave de idempotencia por intención y las reglas de texto de importes y notas. Las rutas y las claves anteriores se reexportan, así que la caja y los cargos no cambiaron de comportamiento.
- **API simulada** (`e2e/mock-api/payments-model.ts`): aplica las reglas que necesitan los recorridos (efectivo con caja abierta, reparto del más antiguo al más reciente con remanente, transferencia declarada, autoaprobación, referencia repetida, mapa incompleto, clave de idempotencia). Se acopla a los cargos (pagado y pendiente, `charge-has-payments`), al estado de cuenta (línea de abono) y a la caja (movimiento `payment_in`). Presets: `payments-empty`, `payments-forbidden`, `payments-server-error`, `payments-accounts-incomplete`.
- **Pruebas** (comandos desde `apps/admin-web` con `CONFIA_VITEST_API_PORT=45731 pnpm exec vitest run ... --maxWorkers=2`):
  - suite completa de Vitest, una sola vez: 271 archivos, 3270 pruebas aprobadas, 2 pendientes que ya existían;
  - `tsc -b --noEmit` y eslint sobre lo tocado sin hallazgos, y sin `eslint-disable`;
  - pruebas de contenedor con MSW: registro (cargos y saldo, efectivo sin caja, doble clic, errores del servidor bajo su campo, misma clave tras un fallo de red, transferencia, referencia repetida, resultado verbatim), lista (filtros en la dirección y en el servidor, vacío, error con reintento), detalle y decisiones (clave de idempotencia, autoaprobación, motivo corto);
  - pruebas de presentación con sus estados, del cliente de la API, del mapa de cuentas y del modelo simulado, y la ruta del servidor simulado.
- **Recorrido de Playwright** (`e2e/specs/journeys/payments-register.e2e.ts`) y puerta de accesibilidad (diez estados nuevos en `e2e/support/a11y-matrix.ts`): ejecutados el 2026-11-21 con los puertos libres. Pasan las 4 pruebas del recorrido (`pnpm exec playwright test specs/journeys/payments-register.e2e.ts --workers=1`) y los 20 casos de la puerta, diez estados en tema claro y oscuro (`pnpm exec playwright test specs/a11y/gate.e2e.ts -g "payment|Pago|pago"`).
- **Fuera de este corte**: no se agregó código de ocultación por permisos (`usePermissions` todavía no está en `main`); el servidor responde 403 y cada pantalla lo cuenta. El selector de encargado que paga no se pide, como dice la nota.
- **Correcciones del frontend (2026-11-21).** La pantalla Registrar pago ya no decide en el navegador qué cargo está pendiente: pide al servidor `outstanding=true` (sin `sort`: el orden es el de la asignación), pagina de a 10 en el servidor y muestra las filas tal como llegan. Estado `rejected` en etiquetas, filtro, detalle y servidor simulado; `self-approval-forbidden` llega como 403; el efectivo arranca con la fecha de hoy (zona de la institución) y vuelve a hoy al volver al método efectivo, y `cash-received-today` se muestra bajo la fecha. El servidor simulado soporta `outstanding`, `settled`, el orden de asignación, `rejected`, el 403 y la regla de la fecha del efectivo. Los `as` del mapa de cuentas se sustituyeron por una unión discriminada (m6). Vitest completo: 271 archivos, 3275 pruebas aprobadas, 2 pendientes que ya existían; recorrido (4) y puerta de accesibilidad (20) de Playwright aprobados.

## Correcciones de la revisión (2026-11-21)

Revisión de código y auditoría de seguridad del backend. Se corrigió `V35` en su lugar (nunca se aplicó en producción y no está
integrada, así que no hay `V36`).

- **Estado `rejected`.** Una transferencia rechazada pasa a `rejected`, distinto de `reversed` (corte 4). Cambian el estado, el tipo de
  decisión, el índice único parcial de la referencia (solo excluye `rejected`), el enum de la API y los filtros.
- **Anulación y asignación.** El disparador `payments_charge_void_guard` bloquea la subcuenta del estudiante (`FOR UPDATE`, mismo
  orden que el disparador de asignaciones) antes de ver si el cargo tiene aplicaciones: una anulación directa y una asignación a la
  vez dejan un solo ganador.
- **Decisión y estado.** Un disparador diferido sobre `payments_payment_decision` exige al confirmar la transacción que una decisión
  `confirmed` tenga el pago `confirmed` y una `rejected` lo tenga `rejected`: una decisión no se confirma sin el avance del estado.
- **Fecha del efectivo.** Solo el día de la sesión (aplicación y disparador).
- **Autoaprobación.** Responde `403 self-approval-forbidden` (antes 409).
- **Asiento.** `payments_require_posting` exige además que el crédito vaya a la subcuenta del estudiante del pago; la comparación de
  fecha de la confirmación, que se comparaba con sí misma, ahora compara con la fecha de recepción del pago.
- **Referencia enmascarada.** Nunca se muestra completa: cuatro caracteres, o dos si la referencia tiene menos de ocho.
- **Huella de la solicitud.** `PaymentRequest.canonical()` antepone la longitud a cada campo; mover un separador entre la referencia y
  el banco ya no da la misma huella.
- **Avance de estado.** `advanceStatus` exige exactamente una fila actualizada.
- **Sin pagos en el proceso.** `charges` ya no tiene un `ChargePayments.NONE` de producción: un proceso que carga los casos de uso de
  cargos sin `payments` falla al arrancar. El trabajador no los carga.
- **Regla de arquitectura.** Toda clase de `application` que llame a `PaymentRepository.findById` o `list` depende de `PaymentScope`,
  sea cual sea su nombre. Anular o decidir un pago lee por `ScopedPayments`.
- **Cargos del estudiante.** `GET /charges/students/{studentId}/charges` agrega `settled` (derivado: cargo vigente sin pendiente) y
  el filtro `outstanding=true`. Con ese filtro y sin `sort`, el orden es el de la asignación (vencimiento, período, código de
  concepto, identificador). Lo pendiente se deriva de las asignaciones, que `charges` no puede consultar en SQL, así que el caso de
  uso lee los cargos del estudiante, filtra, ordena y corta la página.
- **Pruebas.** Sesión revocada (escritura y lectura) en `PaymentEndpointsIT`; la nota de `docs/03-seguridad.md` §6.3 deja dicho que el
  límite del cajero sobre los pagos vive en la aplicación.

## Integración en `main` (2026-11-23)

El corte superó las ochocientas líneas y se dividió en nueve PR encadenados, integrados uno a uno con integración continua en verde:

| PR | Contenido |
|---|---|
| #341 | Migración y dominio puro con las reglas de asignación |
| #342 | API de `ledger`, `cashbox` y `charges` que usa `payments` |
| #343 | Cobro en efectivo con su asiento, su asignación y el alcance del cajero |
| #344 | Declarar, confirmar y rechazar transferencias |
| #345 | Lecturas, mapa de cuentas, guardas e instantánea del OpenAPI |
| #346 | Cliente de la API y columnas de pagado y pendiente de los cargos del estudiante |
| #347 | Pantallas de registro, lista y detalle |
| #348 | Cuentas de caja y banco en el mapa de asientos |
| #349 | API simulada, recorridos y estados de la puerta de accesibilidad |

Ajustes que exigió la división (cada parte debía pasar sola):

- **#342.** El tipo de movimiento `payment_in` de la caja y su etiqueta pasaron a esta parte, porque el contrato de `cashbox` ya lo
  listaba.
- **#343.** `AuthorizationEdgeIT` carga `PaymentsConfiguration`. Las respuestas de prueba de los cargos del estudiante y el modelo de
  anulación de la API simulada incluyen `paid`, `outstanding` y `settled`.
- **#347.** Los permisos de sesión (PR #336 a #339) ya estaban en `main`. Pagos declara `payments:read`, `payments:create` y
  `payments:approve` en la navegación y en las rutas, oculta «Registrar un pago» sin `payments:create` y oculta «Confirmar» y
  «Rechazar» sin `payments:approve`. El servidor sigue decidiendo cada solicitud.
- **#349.** La línea de avance del roadmap obligó a fijar de nuevo el texto de `docs/09` en `docs-notes.test.ts`.

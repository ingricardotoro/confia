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
  - La transferencia nace `declared` y pasa a `confirmed` o a `reversed` (rechazada).
  - Solo `confirmed` produce asiento. Un pago `declared` no cambia el saldo, no se aplica a cargos y no aparece en el estado de cuenta.
  - Este corte no usa `draft`, ni el reverso de un pago confirmado (corte 4).
- **INV-19.** El importe es mayor que cero, con hasta dos decimales y en la moneda de la institución (HNL).
- **INV-20.** El efectivo exige una sesión de caja `open` del usuario que registra. El pago y su movimiento `payment_in` en esa sesión se
  escriben en la misma transacción.
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
  - Nunca excede el pendiente de un cargo. Bloquea las filas de esos cargos.
  - Se admiten pagos parciales: un cargo puede quedar con pendiente.
- **Remanente (INV-18).** Lo que sobra después de cubrir todo lo pendiente queda acreditado en la misma cuenta por cobrar. El saldo
  del estudiante queda negativo y el estado de cuenta ya lo rotula «Saldo a favor». La suma de aplicaciones nunca excede el pago.
- **Pendiente de un cargo.** Es el total del cargo menos sus aplicaciones. Se deriva siempre; no se guarda como campo mutable (regla 4).
  - Un cargo con aplicaciones no se puede anular: `409 charge-has-payments`, que se agrega a `VoidCharge`.
  - Para anularlo, primero se reversa el pago (corte 4).
- **Confirmación y rechazo de una transferencia.**
  - Requieren `payments:approve` (Super Administrador y Administrador, `docs/03` §5.2).
  - Quien confirma o rechaza no puede ser quien registró el pago (segregación de funciones; `409 self-approval-forbidden`).
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
- `GET /charges/students/{studentId}/charges`: agrega a cada cargo `outstanding` (pendiente derivado) y `paid`.

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
  `declared → confirmed | reversed` y solo con la decisión que lo justifica. Pagos, decisiones y aplicaciones rechazan UPDATE (salvo
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
  `payment-not-declared` y `charge-has-payments`, todos 409; el efectivo sin sesión abierta responde `cash-session-not-open` (409,
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

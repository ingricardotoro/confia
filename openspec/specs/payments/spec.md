# Capacidad: Pagos

- **Identificador:** payments
- **Estado:** Borrador
- **Fase:** F3

## Propósito

Registrar el dinero que un estudiante o su encargado entrega a la institución y aplicarlo a los
cargos pendientes según la política de imputación configurada. Le corresponde: el registro
idempotente del pago, su aplicación a uno o varios cargos, los pagos parciales y los abonos, el
saldo a favor resultante de un pago que excede la deuda, el reverso de un pago y el estado
declarado o confirmado propio de una transferencia bancaria. NO le corresponde: el control físico
del efectivo en ventanilla (capacidad `cashbox`), la emisión del documento fiscal asociado
(capacidad `invoicing`) ni la conciliación contra el estado de cuenta bancario (capacidad
`reconciliation`); esta capacidad solo produce el estado declarado que esas otras consumen.

## Requisitos

### Requisito: Registro de pago con clave de idempotencia

El sistema DEBE aceptar una clave de idempotencia en todo registro de pago y, ante una clave ya
usada, DEBE devolver el resultado del pago original sin crear un segundo pago.

#### Escenario: Primer registro con una clave nueva

- **DADO** que la clave de idempotencia `IDEMP-a1b2c3` nunca se ha usado
- **CUANDO** se registra un pago de 1,800.00 HNL para `EST-00321` con esa clave
- **ENTONCES** el sistema crea el pago y lo asocia a la clave `IDEMP-a1b2c3`
- **Y** devuelve el identificador del pago creado, por ejemplo `PAY-55010`

#### Escenario: Reintento con la misma clave de idempotencia

- **DADO** que la clave `IDEMP-a1b2c3` ya creó el pago `PAY-55010` de 1,800.00 HNL, como en el
  escenario anterior
- **CUANDO** una solicitud posterior de reintento de red vuelve a registrar un pago con la misma
  clave `IDEMP-a1b2c3` y el mismo importe
- **ENTONCES** el sistema NO crea un segundo pago
- **Y** devuelve exactamente el mismo identificador `PAY-55010` y los mismos datos del pago
  original

### Requisito: Aplicación del pago según la política de imputación configurada

El sistema DEBE aplicar un pago a los cargos pendientes del estudiante siguiendo la política de
imputación configurada, y NO DEBE aplicar a un cargo un monto mayor a su saldo pendiente.

#### Escenario: Pago que cubre exactamente dos cargos con política "más antiguo primero"

- **DADO** un estudiante con dos cargos pendientes: 1,500.00 HNL con vencimiento 2026-02-05 y
  2,000.00 HNL con vencimiento 2026-03-05
- **Y** la política de imputación configurada como "más antiguo primero"
- **CUANDO** se registra un pago de 3,500.00 HNL
- **ENTONCES** ambos cargos quedan en estado "pagado"
- **Y** el cargo con vencimiento 2026-02-05 recibe la aplicación antes que el de 2026-03-05
- **Y** el saldo del estudiante queda en 0.00 HNL

#### Escenario: Aplicación no excede el saldo pendiente de un cargo

- **DADO** un cargo con saldo pendiente de 900.00 HNL, tras un abono previo de 300.00 HNL sobre
  un cargo original de 1,200.00 HNL
- **CUANDO** se registra un pago de 900.00 HNL dirigido a ese cargo
- **ENTONCES** la aplicación al cargo es de exactamente 900.00 HNL
- **Y** el sistema NO permite que la aplicación registrada supere el saldo pendiente de 900.00 HNL
  del cargo, aunque el pago recibido fuera mayor

### Requisito: Pagos parciales y abonos

El sistema DEBE permitir registrar un pago por un monto menor al saldo pendiente de un cargo,
dejando el cargo en estado "parcial" con su saldo restante correctamente calculado.

#### Escenario: Abono que deja un cargo parcialmente pagado

- **DADO** un cargo de colegiatura de 2,200.00 HNL, sin pagos previos
- **CUANDO** se registra un abono de 700.00 HNL
- **ENTONCES** el cargo queda en estado "parcial"
- **Y** su saldo pendiente queda en 1,500.00 HNL

#### Escenario: Segundo abono que completa el cargo

- **DADO** el cargo anterior en estado "parcial" con saldo pendiente de 1,500.00 HNL
- **CUANDO** se registra un segundo abono de 1,500.00 HNL
- **ENTONCES** el cargo pasa a estado "pagado"
- **Y** el saldo pendiente del cargo queda en 0.00 HNL

### Requisito: Saldo a favor por pago que excede la deuda

El sistema DEBE generar un saldo a favor cuando un pago excede el total de la deuda a la que se
aplica, y ese saldo a favor DEBE quedar disponible para aplicarse a cargos futuros del mismo
estudiante.

#### Escenario: Pago que excede la deuda total

- **DADO** un estudiante con un único cargo pendiente de 1,000.00 HNL
- **CUANDO** se registra un pago de 1,200.00 HNL
- **ENTONCES** el cargo queda en estado "pagado"
- **Y** se genera un saldo a favor de 200.00 HNL
- **Y** el saldo a favor queda disponible para cargos futuros

#### Escenario: Saldo a favor aplicado a un cargo posterior

- **DADO** el saldo a favor de 200.00 HNL del escenario anterior
- **Y** un nuevo cargo de transporte de 200.00 HNL generado el 2026-04-01
- **CUANDO** corre la aplicación automática de saldo a favor disponible
- **ENTONCES** el cargo de 200.00 HNL queda en estado "pagado" sin que el estudiante registre un
  pago adicional
- **Y** el saldo a favor disponible queda en 0.00 HNL

### Requisito: Reverso de pago

El sistema DEBE permitir reversar un pago ya registrado, deshaciendo su efecto sobre los cargos a
los que fue aplicado, y NO DEBE eliminar el pago original: el reverso queda registrado como una
operación nueva vinculada al pago que anula.

#### Escenario: Reverso de un pago aplicado a un solo cargo

- **DADO** el pago `PAY-55010` de 1,800.00 HNL, aplicado por completo al cargo `CHG-9001`, que
  quedó en estado "pagado"
- **CUANDO** el personal autorizado reversa `PAY-55010` con el motivo "cheque rechazado por el
  banco"
- **ENTONCES** el cargo `CHG-9001` vuelve al estado "pendiente" por 1,800.00 HNL
- **Y** el pago original `PAY-55010` permanece en el historial, marcado como reversado, sin ser
  borrado

#### Escenario: Reverso de un pago que había generado saldo a favor ya consumido

- **DADO** el pago del escenario "Pago que excede la deuda total", que generó un saldo a favor de
  200.00 HNL ya aplicado a un cargo posterior
- **CUANDO** se reversa ese pago original
- **ENTONCES** el sistema rechaza el reverso directo y exige primero reversar la aplicación del
  saldo a favor que lo consumió
- **Y** NO deja el sistema en un estado donde exista un saldo a favor negativo

### Requisito: Estados declarado y confirmado para transferencias bancarias

El sistema DEBE registrar un pago por transferencia bancaria en estado "declarado" al momento de
su notificación por el encargado, y NO DEBE marcarlo como "confirmado" hasta que la conciliación
bancaria valide su ingreso efectivo a la cuenta de la institución.

#### Escenario: Transferencia declarada por el encargado

- **DADO** un encargado que notifica una transferencia de 4,500.00 HNL con número de referencia
  `TRF-330912` el 2026-05-02
- **CUANDO** el sistema registra la notificación
- **ENTONCES** el pago queda en estado "declarado"
- **Y** el cargo asociado queda marcado como cubierto de forma provisional, sujeto a la
  configuración de alerta por pago no confirmado

#### Escenario: Confirmación tras conciliación bancaria

- **DADO** el pago declarado `TRF-330912` de 4,500.00 HNL del escenario anterior
- **CUANDO** la conciliación bancaria del 2026-05-04 encuentra en el estado de cuenta del banco un
  movimiento por 4,500.00 HNL con la misma referencia
- **ENTONCES** el sistema actualiza el pago a estado "confirmado"
- **Y** el cargo asociado deja de depender de la marca provisional y queda definitivamente pagado

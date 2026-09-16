# Capacidad: Libro mayor

- **Identificador:** ledger
- **Estado:** Borrador
- **Fase:** F3

## Propósito

Ser la fuente única de verdad del dinero que un estudiante debe, pagó o tiene a favor, mediante un
libro mayor de doble partida inmutable. Le corresponde: registrar asientos balanceados por cada
transacción financiera, derivar el saldo a partir de esos asientos, garantizar que ninguna
transacción quede descuadrada y verificar la integridad contable de forma continua. NO le
corresponde: decidir cuándo se genera un cargo (capacidad `charges`), cómo se aplica un pago a un
cargo específico (capacidad `payments`) ni la emisión de documentos fiscales (capacidad
`invoicing`); esas capacidades producen las transacciones que el libro mayor registra.

## Requisitos

### Requisito: Toda transacción cuadra

El sistema DEBE registrar cada transacción de modo que la suma de los importes al debe sea
exactamente igual a la suma de los importes al haber, y NO DEBE persistir una transacción cuya
suma de debe y haber difiera, ni siquiera en un centavo.

#### Escenario: Transacción de pago balanceada

- **DADO** un cargo pendiente de colegiatura por 3,200.00 HNL en la cuenta del estudiante
  `EST-00457`
- **CUANDO** se registra un pago de 3,200.00 HNL aplicado a ese cargo
- **ENTONCES** la transacción resultante tiene un asiento al debe de 3,200.00 HNL contra la
  cuenta de caja o banco y un asiento al haber de 3,200.00 HNL contra la cuenta por cobrar del
  estudiante
- **Y** la diferencia entre el total debe y el total haber de esa transacción es 0.00 HNL

#### Escenario: Intento de transacción descuadrada rechazado

- **DADO** un intento de registrar una transacción con un asiento al debe de 1,500.00 HNL y un
  asiento al haber de 1,450.00 HNL
- **CUANDO** el sistema procesa esa transacción
- **ENTONCES** la rechaza sin persistir ningún asiento
- **Y** devuelve un error que identifica la transacción como descuadrada, sin crear un registro
  parcial

### Requisito: El saldo se deriva de los asientos

El sistema DEBE calcular el saldo de la cuenta de un estudiante como el resultado de sumar todos
sus asientos de libro mayor vigentes, y NO DEBE almacenar el saldo como un campo que se actualiza
de forma independiente a esos asientos.

#### Escenario: Saldo después de un cargo y un pago parcial

- **DADO** un estudiante `EST-00457` sin movimientos previos
- **Y** un cargo de matrícula de 2,500.00 HNL registrado el 2026-01-15
- **CUANDO** se registra un pago de 1,000.00 HNL el 2026-01-20
- **ENTONCES** el saldo consultado el 2026-01-21 es 1,500.00 HNL, calculado como la suma de los
  asientos de esa cuenta, no leído de un campo `balance`

#### Escenario: Saldo cacheado reconstruido en la verificación nocturna

- **DADO** un saldo cacheado de 1,500.00 HNL para `EST-00457` usado solo como optimización de
  lectura
- **Y** los asientos reales de esa cuenta suman 1,480.00 HNL debido a un ajuste registrado
  minutos antes de la última actualización del caché
- **CUANDO** corre la verificación nocturna de integridad
- **ENTONCES** el sistema recalcula el saldo desde los asientos y corrige el valor cacheado a
  1,480.00 HNL
- **Y** el saldo cacheado nunca es la fuente de verdad usada para decidir si un cargo está pagado

### Requisito: Los asientos son inmutables

El sistema NO DEBE permitir la edición ni el borrado de un asiento del libro mayor una vez
persistido, sin importar el rol de quien lo solicite.

#### Escenario: Intento de editar un asiento ya registrado

- **DADO** un asiento de pago de 800.00 HNL registrado el 2026-02-03 para `EST-00512`
- **CUANDO** un usuario con rol `administrator` intenta modificar el importe de ese asiento a
  700.00 HNL
- **ENTONCES** el sistema rechaza la operación
- **Y** el asiento original permanece sin cambios, con su importe de 800.00 HNL

#### Escenario: Intento de borrar un asiento ya registrado

- **DADO** el mismo asiento de 800.00 HNL
- **CUANDO** cualquier usuario intenta eliminarlo
- **ENTONCES** el sistema rechaza la operación con un error que indica que los asientos del libro
  mayor no admiten borrado
- **Y** el asiento sigue siendo consultable en el historial de la cuenta

### Requisito: Toda corrección se hace por reverso

El sistema DEBE corregir un asiento erróneo únicamente mediante una nueva transacción de tipo
Reverso que anule su efecto, seguida del asiento correcto cuando corresponda, y NO DEBE ofrecer
ningún mecanismo que sustituya el asiento original en su lugar.

#### Escenario: Reverso de un pago registrado por error

- **DADO** un pago de 3,200.00 HNL registrado por error a nombre de `EST-00457` en lugar de
  `EST-00458`, el 2026-03-05
- **CUANDO** el personal autorizado ejecuta la corrección
- **ENTONCES** el sistema registra una transacción de tipo Reverso el 2026-03-05 que anula
  exactamente los 3,200.00 HNL originales en la cuenta de `EST-00457`
- **Y** registra por separado el pago correcto de 3,200.00 HNL en la cuenta de `EST-00458`
- **Y** el historial de `EST-00457` conserva el asiento original y su reverso, ambos visibles

#### Escenario: Reverso parcial no existe como operación distinta

- **DADO** un cargo de recargo por mora de 150.00 HNL aplicado por error, donde solo 100.00 HNL
  correspondían
- **CUANDO** se corrige el error
- **ENTONCES** el sistema exige un reverso completo de los 150.00 HNL seguido de un nuevo cargo
  de 100.00 HNL
- **Y** NO ofrece una operación que edite el recargo original a 100.00 HNL

### Requisito: Tipos de transacción permitidos

El sistema DEBE restringir cada transacción del libro mayor a uno de los tipos definidos (Cargo,
Descuento o beca, Pago, Recargo por mora, Nota de crédito, Reverso, Ajuste, Incobrable), y NO
DEBE persistir una transacción con un tipo fuera de ese conjunto.

#### Escenario: Transacción de tipo Ajuste con motivo registrado

- **DADO** una diferencia detectada manualmente en la cuenta de `EST-00600` por 50.00 HNL
- **CUANDO** el personal autorizado registra una transacción de tipo Ajuste por 50.00 HNL con el
  motivo "corrección de traslado de matrícula entre secciones"
- **ENTONCES** el sistema acepta la transacción y almacena el motivo como parte obligatoria del
  registro

#### Escenario: Tipo de transacción inexistente rechazado

- **DADO** una solicitud de transacción con el tipo `"Descuento manual no auditado"`, que no
  pertenece al conjunto permitido
- **CUANDO** el sistema procesa la solicitud
- **ENTONCES** la rechaza antes de persistir ningún asiento
- **Y** devuelve un error que enumera los tipos de transacción válidos

### Requisito: Verificación nocturna de integridad

El sistema DEBE ejecutar diariamente un trabajo de verificación que recalcule el cuadre de cada
transacción y el saldo de cada cuenta, y NO DEBE corregir automáticamente un descuadre detectado:
DEBE alertar para revisión humana.

#### Escenario: Verificación nocturna sin hallazgos

- **DADO** que todas las transacciones registradas hasta el 2026-04-10 están balanceadas
- **CUANDO** corre la verificación nocturna programada a las 02:00:00 del 2026-04-11
- **ENTONCES** el trabajo finaliza sin generar alertas
- **Y** registra en el reporte de integridad que 0 transacciones presentaron descuadre

#### Escenario: Verificación nocturna detecta un descuadre

- **DADO** una transacción `TXN-77219` con un debe de 900.00 HNL y un haber de 890.00 HNL,
  producida por un defecto de un proceso anterior
- **CUANDO** corre la verificación nocturna del 2026-04-11
- **ENTONCES** el sistema identifica `TXN-77219` como descuadrada por 10.00 HNL
- **Y** genera una alerta accionable para el equipo responsable
- **Y** NO modifica ni reversa `TXN-77219` de forma automática

### Requisito: Consistencia del saldo bajo escrituras concurrentes

El sistema NO DEBE producir un saldo incorrecto cuando dos transacciones que afectan la misma
cuenta se procesan de forma simultánea; el resultado observable DEBE ser equivalente a procesar
ambas transacciones en algún orden secuencial.

#### Escenario: Dos pagos simultáneos sobre la misma cuenta

- **DADO** un estudiante `EST-00457` con un cargo pendiente de 2,000.00 HNL
- **CUANDO** dos cajeros distintos registran, en el mismo instante, un pago de 1,200.00 HNL y un
  pago de 800.00 HNL sobre esa cuenta
- **ENTONCES** el sistema procesa ambos pagos sin pérdida de ninguno de los dos
- **Y** el saldo final de la cuenta es 0.00 HNL, equivalente a haber aplicado los dos pagos en
  cualquier orden secuencial

#### Escenario: Pago simultáneo con un reverso sobre la misma cuenta

- **DADO** el mismo estudiante con un pago previo de 2,000.00 HNL ya aplicado y saldo en 0.00 HNL
- **CUANDO** se solicita, en el mismo instante, un reverso de ese pago y un nuevo pago de 500.00
  HNL sobre la misma cuenta
- **ENTONCES** el sistema procesa ambas operaciones sin que una sobrescriba el efecto de la otra
- **Y** el saldo final refleja exactamente ambos movimientos: 2,000.00 HNL de cargo repuesto por
  el reverso, menos 500.00 HNL del nuevo pago, es decir 1,500.00 HNL

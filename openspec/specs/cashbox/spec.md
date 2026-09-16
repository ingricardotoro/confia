# Capacidad: Caja

- **Identificador:** cashbox
- **Estado:** Borrador
- **Fase:** F4

## Propósito

Controlar el efectivo físico que un cajero recibe en ventanilla, desde la apertura de la sesión
hasta su arqueo y cierre. Le corresponde: abrir y cerrar sesiones de caja, registrar todos los
movimientos de efectivo de una sesión, comparar el conteo declarado contra el esperado y exigir
justificación de cualquier diferencia. NO le corresponde: decidir cómo se aplica un pago a un
cargo (capacidad `payments`) ni emitir el documento fiscal del cobro (capacidad `invoicing`); esta
capacidad solo controla el efectivo que respalda esos movimientos.

## Requisitos

### Requisito: Apertura de sesión con fondo inicial declarado

El sistema DEBE exigir que la apertura de una sesión de caja incluya un fondo inicial declarado
en efectivo, y NO DEBE abrir una sesión sin ese valor registrado.

#### Escenario: Apertura con fondo inicial declarado

- **DADO** el cajero `USR-2201` sin sesión de caja abierta
- **CUANDO** abre una sesión el 2026-06-01 a las 07:30:00 declarando un fondo inicial de 500.00
  HNL
- **ENTONCES** el sistema crea la sesión `CBX-4501` en estado "abierta" con fondo inicial de
  500.00 HNL

#### Escenario: Intento de apertura sin fondo inicial

- **DADO** el mismo cajero `USR-2201` sin sesión abierta
- **CUANDO** intenta abrir una sesión sin declarar ningún fondo inicial
- **ENTONCES** el sistema rechaza la apertura
- **Y** NO existe ninguna sesión en estado "abierta" para ese cajero

### Requisito: Bloqueo de pagos en efectivo sin sesión de caja abierta

El sistema NO DEBE permitir registrar un pago en efectivo si el cajero que lo registra no tiene
una sesión de caja abierta en ese momento.

#### Escenario: Pago en efectivo con sesión abierta

- **DADO** el cajero `USR-2201` con la sesión `CBX-4501` abierta desde las 07:30:00 del 2026-06-01
- **CUANDO** registra un pago en efectivo de 1,200.00 HNL a las 09:00:00 del mismo día
- **ENTONCES** el sistema acepta el pago
- **Y** lo asocia a la sesión `CBX-4501`

#### Escenario: Pago en efectivo sin sesión abierta

- **DADO** el cajero `USR-2305` sin ninguna sesión de caja abierta
- **CUANDO** intenta registrar un pago en efectivo de 600.00 HNL
- **ENTONCES** el sistema rechaza el registro del pago
- **Y** el error indica que se requiere una sesión de caja abierta para ese cajero

### Requisito: Registro de todos los movimientos de la sesión

El sistema DEBE registrar cada movimiento de efectivo ocurrido durante una sesión de caja abierta,
asociado a esa sesión y con marca de tiempo, de modo que el total de la sesión sea la suma
verificable de sus movimientos.

#### Escenario: Varios pagos dentro de la misma sesión

- **DADO** la sesión `CBX-4501` abierta con fondo inicial de 500.00 HNL
- **CUANDO** se registran tres pagos en efectivo de 1,200.00 HNL, 800.00 HNL y 350.00 HNL durante
  la jornada
- **ENTONCES** la sesión `CBX-4501` lista los tres movimientos de forma individual
- **Y** el total esperado en caja es 500.00 HNL de fondo inicial más 2,350.00 HNL de pagos, es
  decir 2,850.00 HNL

#### Escenario: Retiro parcial de efectivo durante la sesión

- **DADO** la sesión `CBX-4501` con un total esperado de 2,850.00 HNL a las 12:00:00
- **CUANDO** el supervisor registra un retiro de efectivo de 2,000.00 HNL a las 12:05:00 para
  depósito bancario
- **ENTONCES** el retiro queda registrado como movimiento de la sesión con su propia marca de
  tiempo
- **Y** el total esperado en caja se reduce a 850.00 HNL

### Requisito: Cierre con conteo declarado frente a conteo esperado

El sistema DEBE exigir, al cerrar una sesión de caja, un conteo físico declarado por el cajero, y
DEBE compararlo contra el conteo esperado calculado a partir del fondo inicial y los movimientos
registrados.

#### Escenario: Cierre con conteo exacto

- **DADO** la sesión `CBX-4501` con un conteo esperado de 850.00 HNL a las 16:00:00 del 2026-06-01
- **CUANDO** el cajero declara un conteo físico de 850.00 HNL al cerrar
- **ENTONCES** el sistema cierra la sesión con diferencia de 0.00 HNL
- **Y** marca el cierre como "cuadrado"

#### Escenario: Cierre con conteo distinto al esperado

- **DADO** la misma sesión con un conteo esperado de 850.00 HNL
- **CUANDO** el cajero declara un conteo físico de 830.00 HNL
- **ENTONCES** el sistema calcula una diferencia de -20.00 HNL
- **Y** exige una justificación antes de completar el cierre

### Requisito: Registro obligatorio de la diferencia con justificación

El sistema DEBE exigir un motivo de justificación registrado cuando el cierre de una sesión
presenta una diferencia distinta de 0.00 HNL entre el conteo declarado y el conteo esperado, y NO
DEBE completar el cierre de esa sesión sin esa justificación.

#### Escenario: Cierre bloqueado hasta ingresar justificación

- **DADO** una diferencia de -20.00 HNL detectada al cerrar la sesión `CBX-4501`
- **CUANDO** el cajero intenta finalizar el cierre sin ingresar ningún motivo
- **ENTONCES** el sistema rechaza la finalización del cierre
- **Y** la sesión permanece en estado "en cierre" hasta que se registre una justificación

#### Escenario: Cierre completado con justificación registrada

- **DADO** la misma diferencia de -20.00 HNL
- **CUANDO** el cajero registra el motivo "vuelto entregado de más a un padre de familia" y
  confirma el cierre
- **ENTONCES** el sistema completa el cierre de la sesión con la diferencia y el motivo asociados
  de forma permanente al registro de la sesión

### Requisito: Una sesión cerrada no acepta movimientos nuevos

El sistema NO DEBE aceptar ningún movimiento de efectivo, incluidos pagos, retiros o ajustes,
asociado a una sesión de caja que ya se cerró.

#### Escenario: Intento de registrar un pago tras el cierre

- **DADO** la sesión `CBX-4501` cerrada a las 16:10:00 del 2026-06-01
- **CUANDO** se intenta registrar un pago en efectivo de 300.00 HNL a las 16:15:00 asociado a esa
  sesión
- **ENTONCES** el sistema rechaza el movimiento
- **Y** exige que el cajero abra una nueva sesión de caja para continuar recibiendo efectivo

#### Escenario: Corrección posterior al cierre requiere una sesión nueva

- **DADO** que se descubre, después del cierre de `CBX-4501`, que un pago de esa jornada quedó
  registrado con el monto incorrecto
- **CUANDO** el personal autorizado corrige el error
- **ENTONCES** la corrección se realiza mediante un reverso en el libro mayor y, si corresponde
  efectivo físico, un movimiento en una sesión de caja nueva, nunca reabriendo `CBX-4501`

### Requisito: Un cajero no tiene dos sesiones abiertas simultáneas

El sistema NO DEBE permitir que un mismo cajero abra una segunda sesión de caja mientras ya tiene
una sesión en estado "abierta".

#### Escenario: Intento de doble apertura por el mismo cajero

- **DADO** el cajero `USR-2201` con la sesión `CBX-4501` ya abierta desde las 07:30:00
- **CUANDO** intenta abrir una segunda sesión a las 10:00:00 del mismo día
- **ENTONCES** el sistema rechaza la apertura
- **Y** indica que la sesión `CBX-4501` sigue abierta

#### Escenario: Apertura permitida tras cerrar la sesión previa

- **DADO** el mismo cajero, con `CBX-4501` ya cerrada a las 16:10:00
- **CUANDO** abre una nueva sesión a las 16:20:00 con fondo inicial de 500.00 HNL
- **ENTONCES** el sistema crea la sesión nueva `CBX-4502` en estado "abierta"
- **Y** ambas sesiones, `CBX-4501` y `CBX-4502`, quedan visibles por separado en el historial del
  cajero

# Capacidad: Trabajos en segundo plano

- **Identificador:** background-jobs
- **Estado:** pendiente de aprobación del propietario del producto
- **Fase:** F0
- **Cambio:** `background-jobs-with-db-scheduler` (F0, cambio 9)
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-10-24, con P1 a P9 vinculantes

**Convenciones de esta especificación.**

- Es una especificación nueva completa. Sus escenarios se numeran `BJ01` en adelante.
- Describe comportamiento observable; los nombres de clases, de paquetes y de asientos de auditoría los fija el
  diseño. Los nombres de pruebas aparecen solo cuando una especificación vigente ya los usa.
- «Tarea» es una fila de `scheduled_tasks`. «Tarea única» es la que se ejecuta una vez; «tarea recurrente» es la
  que se reprograma sola según su cadencia. «Trabajador» es el proceso `confia-worker`.
- «Agotada» significa que una tarea única consumió el máximo de intentos de su tipo sin éxito. Una tarea agotada
  queda «estacionada»: su ejecución se reprograma al instante `9999-01-01T00:00:00Z`, conserva `consecutive_failures`
  y `last_failure`, ningún sondeo la toma y es visible para las métricas. **Estado fallido = ejecución estacionada**
  (tarea única) o `consecutive_failures` mayor que cero hasta el siguiente éxito (tarea recurrente).
- Los valores de borde (un segundo antes, en el borde exacto y un segundo después) se declaran en cada
  escenario; allí donde la propuesta no fija si el borde exacto incluye o excluye, la especificación fija el
  lado conservador (no actuar en el borde). El diseño (decisión 16) lo confirmó: una fila es purgable solo si su
  referencia es estrictamente anterior al corte.
- Los escenarios `BJ01` a `BJ42` se conservan con su número; los escenarios añadidos al conciliar la especificación
  con el diseño y con las decisiones del propietario del 2026-10-24 (D-N1, D-N2, D-N3) se numeran `BJ43` en adelante.
- La zona horaria de las cadencias es siempre `America/Tegucigalpa`; ningún módulo puede elegir otra.

## Propósito

ADR-0016 eligió db-scheduler sobre PostgreSQL para todo el trabajo en segundo plano. Esta capacidad define lo
observable de ese trabajo: una tarea se programa dentro de la transacción del negocio y nace o muere con ella;
solo el proceso trabajador ejecuta; los datos de tarea son mínimos, tipados y sin datos personales; cada
ejecución corre con el contexto de seguridad de una institución; el fallo de una tarea única se reintenta de forma acotada y, al
agotarse, la tarea queda estacionada y visible, y el de una tarea recurrente no se reintenta: se reprograma a su próxima ocurrencia; las tareas recurrentes tienen una zona horaria explícita; y el estado de la cola es
observable sin leer los datos de tarea.

Cierra el criterio de salida 5 de F0 («una tarea programada dentro de una transacción revertida no existe, y
solo el proceso `confia-worker` ejecuta tareas») y aporta el ejecutor que los cambios `password-recovery-endpoints`
(S3) y `transactional-email-adapter` (cambio 14) necesitan.

Los manejadores concretos tienen su comportamiento en la capacidad que los posee: la emisión de recuperación y
las purgas de identidad en `identity`, las purgas de idempotencia y los privilegios en `build-integrity`, y las
cadencias del verificador de cadena y los asientos de las purgas en `audit-trail`.

## Requisitos

### Requisito: Una tarea se programa dentro de la transacción del negocio y nace o muere con ella

El sistema DEBE programar toda tarea dentro de la transacción de base de datos del `TransactionRunner`, de modo
que la tarea exista si y solo si esa transacción se confirma. Una transacción revertida NO DEBE dejar ninguna
tarea, y una tarea nunca DEBE ser visible para el ejecutor antes de la confirmación. La programación DEBE
participar de la misma conexión y de la misma transacción que las escrituras del negocio, incluido el asiento de
auditoría de esa operación. El proceso administrativo y el del portal DEBEN programar mediante un cliente
liviano que NO ejecuta nada. Si el `TransactionRunner` reintenta la transacción (por ejemplo ante `40001` o
`40P01`), la tarea DEBE existir una sola vez.

La programación DEBE negarse con un error cuando no hay una transacción activa abierta por el
`TransactionRunner`: nunca programa fuera de una transacción. La institución de la envoltura de la tarea DEBE ser
la de la transacción en curso. Programar una instancia que ya existe NO es un error: el resultado es «ya
programada» y NO DEBE abortar la transacción del negocio. Cada proceso que programa DEBE tener exactamente un
proxy de conexión que participa de la transacción, compartido por el acceso a datos y por el cliente de
programación, de modo que la invariante «misma conexión y misma transacción» sea revisable.

#### Escenario: [BJ01] Una transacción confirmada deja la tarea

- **DADO** un caso de uso del proceso administrativo que, dentro de `runner.execute(...)`, programa una tarea
  única y escribe un asiento de auditoría
- **CUANDO** la transacción se confirma
- **ENTONCES** existe exactamente una fila en `scheduled_tasks` para esa instancia
- **Y** existe el asiento de auditoría

#### Escenario: [BJ02] Una transacción revertida no deja ninguna tarea

- **DADO** el mismo caso de uso, que lanza una excepción después de programar la tarea
- **CUANDO** la transacción se revierte
- **ENTONCES** no existe ninguna fila en `scheduled_tasks` para esa instancia
- **Y** no existe el asiento de auditoría
- **Y** el trabajador nunca llega a ejecutar nada para esa instancia, aunque se espere más que su instante de
  ejecución

#### Escenario: [BJ03] La tarea no es visible antes de la confirmación

- **DADO** una transacción abierta que ya programó una tarea y todavía no se confirma
- **CUANDO** otra conexión consulta `scheduled_tasks`
- **ENTONCES** no ve la fila
- **Y** la ve después de la confirmación

#### Escenario: [BJ04] Un reintento del `TransactionRunner` programa una sola vez

- **DADO** un caso de uso que programa una tarea y cuyo primer intento falla con `SQLState` `40001`
- **CUANDO** el `TransactionRunner` reintenta y el segundo intento se confirma
- **ENTONCES** existe exactamente una fila en `scheduled_tasks` para esa instancia

#### Escenario: [BJ05] Un cliente construido sobre una conexión sin proxy transaccional hace fallar la prueba (control negativo)

- **DADO** un fixture permanente con un cliente de programación construido sobre el `DataSource` sin el proxy
  que participa de la transacción
- **CUANDO** se ejecuta la prueba de atomicidad con una transacción revertida
- **ENTONCES** la prueba falla, porque la tarea quedó confirmada fuera de la transacción del negocio
- **Y** con el cliente real la misma prueba pasa, de modo que la prueba no pasa por vacío

#### Escenario: [BJ43] Programar fuera de una transacción se rechaza

- **DADO** el cliente de programación y ninguna transacción activa del `TransactionRunner`
- **CUANDO** se intenta programar una tarea única
- **ENTONCES** la operación lanza un error de estado ilegal y no existe ninguna fila en `scheduled_tasks`

#### Escenario: [BJ44] Programar una instancia ya programada no aborta la transacción del negocio

- **DADO** una transacción que ya programó la instancia `X` y que después escribe un asiento de auditoría
- **CUANDO** el mismo caso de uso programa de nuevo la instancia `X` dentro de la misma transacción y la
  transacción se confirma
- **ENTONCES** la segunda programación devuelve «ya programada» sin error
- **Y** la transacción se confirma con el asiento de auditoría y exactamente una fila para la instancia `X`

#### Escenario: [BJ45] La institución de la envoltura debe ser la de la transacción

- **DADO** una transacción con el contexto de la institución `INS-1`
- **CUANDO** se intenta programar una tarea cuya envoltura declara la institución `INS-2`
- **ENTONCES** la programación se rechaza con un error y no existe ninguna fila en `scheduled_tasks`

#### Escenario: [BJ46] Hay un solo proxy transaccional por proceso

- **DADO** los procesos administrativo, del portal y del trabajador arrancados por su camino de producción
- **CUANDO** se inspecciona qué proxy de conexión usan el `DSLContext` (donde existe) y el cliente de programación
- **ENTONCES** en cada proceso hay exactamente un proxy y ambos usan ese mismo objeto
- **Y** un fixture que construye un segundo proxy hace fallar la verificación

### Requisito: Solo el proceso trabajador ejecuta tareas, y administración y portal nunca tienen un ejecutor

El sistema DEBE ejecutar tareas únicamente en el contexto del proceso `confia-worker`, que DEBE contener
exactamente un ejecutor (`Scheduler`). Los contextos del proceso administrativo y del portal NO DEBEN contener
ningún `Scheduler` ni ningún otro bean de `com.github.kagkarlsson`: su cliente de programación se construye
dentro del adaptador y no se expone como bean (ADR-0016, regla 1). Ninguna tarea DEBE ejecutarse mientras solo
estén en marcha el proceso administrativo y el del portal.

El ejecutor del trabajador DEBE ser un único `Scheduler` propio del sistema: el que la autoconfiguración del
iniciador de la biblioteca declararía por omisión se retira en su favor. El proceso administrativo y el del portal
DEBEN excluir, en el código de su punto de entrada (no por una propiedad que un entorno pueda cambiar), **todas**
las clases de autoconfiguración que declara el iniciador. El ejecutor DEBE arrancar al estar el contexto listo, y
ninguna configuración versionada DEBE desactivar su arranque; el único modo de desactivarlo es una propiedad de
prueba que deja una advertencia al arrancar. El trabajador no tiene servidor web y DEBE seguir vivo mientras su
contexto esté abierto.

El portal DEBE programar con su rol real de base de datos (decisión D-N1 del propietario del 2026-10-24, que
revierte la decisión D5 de `web-edge-foundations`): el portal recibe su primer `DataSource`, con el rol
`confia_portal_app`, y su cableado se limita al `TransactionRunner`, el proxy transaccional y el puerto de
programación. Esta capacidad no concede al portal ninguna lectura ni escritura de datos de negocio.

#### Escenario: [BJ06] Cero ejecutores en administración y portal, uno en el trabajador

- **DADO** los tres procesos arrancados por su camino de producción
- **CUANDO** se cuentan los beans de tipo `Scheduler` y los beans de `com.github.kagkarlsson` de cada contexto
- **ENTONCES** el administrativo y el portal tienen cero de cada tipo y el trabajador tiene exactamente un
  `Scheduler`
- **Y** la verificación evalúa beans reales de cada contexto, no un conjunto vacío

#### Escenario: [BJ07] Una tarea confirmada solo la ejecuta el trabajador

- **DADO** PostgreSQL real, una tarea programada desde un contexto administrativo con la transacción confirmada
  y un instante de ejecución ya vencido, y solo los contextos administrativo y del portal en marcha
- **CUANDO** transcurre un tiempo mayor que el intervalo de sondeo del ejecutor
- **ENTONCES** la tarea no se ejecutó: su efecto no existe y la fila sigue sin tomar
- **Y** al arrancar el contexto del trabajador, la tarea se ejecuta exactamente una vez

#### Escenario: [BJ08] Un contexto administrativo con la autoconfiguración de db-scheduler hace fallar la prueba (control negativo)

- **DADO** un fixture permanente que arranca el contexto administrativo (y otro que arranca el del portal) sin la
  exclusión de la autoconfiguración de db-scheduler
- **CUANDO** se ejecuta la prueba de arranque por perfil
- **ENTONCES** la prueba falla señalando el bean `Scheduler` y el proceso
- **Y** con los contextos reales pasa

#### Escenario: [BJ47] El ejecutor del iniciador se retira y queda uno solo

- **DADO** el proceso trabajador arrancado por su camino de producción con el iniciador de la biblioteca en el
  camino de clases
- **CUANDO** se cuentan los beans `Scheduler` de su contexto
- **ENTONCES** hay exactamente uno, el propio del sistema, y no el que declararía la autoconfiguración del
  iniciador
- **Y** no hay ningún hilo del ejecutor en administración ni en el portal

#### Escenario: [BJ48] Toda autoconfiguración del iniciador está excluida en administración y portal

- **DADO** la lista de clases de autoconfiguración que declara el iniciador de la versión fijada, leída del camino
  de clases
- **CUANDO** se comprueban los puntos de entrada administrativo y del portal
- **ENTONCES** cada clase de la lista está excluida en ambos
- **Y** si una versión nueva de la biblioteca agrega una autoconfiguración, la construcción falla señalando la clase
  no excluida

#### Escenario: [BJ49] Ninguna configuración versionada desactiva el arranque del ejecutor

- **DADO** todos los archivos de configuración versionados del repositorio
- **CUANDO** se busca la propiedad que desactiva el arranque del ejecutor
- **ENTONCES** ninguno la fija en falso, y un fixture que la fija en falso hace fallar la prueba
- **Y** un trabajador arrancado con la propiedad de prueba en falso deja la advertencia `jobs_executor_not_started`
  y no arranca ningún hilo del ejecutor

#### Escenario: [BJ50] El trabajador sin servidor web sigue vivo

- **DADO** el proceso trabajador arrancado por su camino de producción, sin servidor web
- **CUANDO** transcurre un tiempo mayor que varios intervalos de sondeo
- **ENTONCES** el proceso sigue en marcha y el ejecutor sigue sondeando

#### Escenario: [BJ51] El contexto del portal contiene solo el cableado de programación

- **DADO** el proceso del portal arrancado por su camino de producción
- **CUANDO** se inspecciona su contexto
- **ENTONCES** contiene un `DataSource`, el `TransactionRunner`, el proxy transaccional y el puerto de programación
- **Y** no contiene `DSLContext`, repositorios, casos de uso, nada de `identity` ni de `organization`, ni ningún
  `Scheduler`

#### Escenario: [BJ09] El portal puede programar con su rol real

- **DADO** el contexto real del portal, conectado con el rol `confia_portal_app` (D-N1), y una transacción abierta
  por su `TransactionRunner` que usa el puerto de programación del portal
- **CUANDO** programa una tarea única y confirma
- **ENTONCES** la fila existe, con exactamente los privilegios concedidos a ese rol sobre `scheduled_tasks`
- **Y** el mismo rol no puede actualizar ni borrar filas de `scheduled_tasks`
- **Y** el contexto del portal no contiene ningún `Scheduler`, de modo que la fila solo la ejecuta el trabajador

### Requisito: La envoltura de tarea lleva identificadores y contexto, y los datos de tarea son JSON mínimo, tipado y sin datos personales

El sistema DEBE envolver los datos de toda tarea en una envoltura común (`TracedJob`) con dos partes, `payload` y
`meta`, serializada como el documento JSON `{"payload":{…},"meta":{…}}` (`docs/07` §3.3). `meta` DEBE llevar el
identificador de correlación, el identificador de solicitud, la clase de actor (`STAFF`, `GUARDIAN` o `SYSTEM`) y
la institución a la que pertenece la tarea, y PUEDE llevar sin valor el identificador del actor (cuando quien pide
es anónimo) y el `traceparent` (hasta que exista OpenTelemetry). Los datos de tarea DEBEN serializarse como JSON,
con un tipo de datos propio por tipo de tarea, y NO DEBEN serializarse jamás como objetos de Java. Al leer, el
sistema DEBE rechazar los campos desconocidos. El documento NO DEBE superar 4 KiB.

Los datos de tarea NO DEBEN contener datos personales (correo, nombre, documento de identidad, IP, datos de
menores) ni importes ni secretos: solo identificadores internos. Para que esto no dependa de nombres de campos, la
carga de todo tipo de tarea DEBE ser un `record` cuyos componentes sean solo `UUID`, `Instant`, `LocalDate`,
`boolean`, `int`, `long` o un `enum` del mismo módulo, y NUNCA `String`, `BigDecimal`, `Money`, colecciones ni
otros `record`. Cada tipo de tarea DEBE tener una lista aprobada de campos, comparada contra el conjunto exacto de
claves JSON a todo nivel (incluida `meta`), y una prueba DEBE fallar si el tipo declara un campo fuera de ella.
Toda excepción de serialización o deserialización DEBE traducirse a un error cuyo mensaje nombre la tarea y la
clase de la excepción original, sin la causa encadenada ni fragmentos de los datos.

#### Escenario: [BJ10] La envoltura lleva el contexto y no datos personales

- **DADO** una tarea de cada tipo registrado, programada desde un caso de uso con un actor y una institución
- **CUANDO** se lee el `task_data` de la fila
- **ENTONCES** es un documento JSON con `payload` y `meta`, y `meta` contiene el identificador de correlación, el
  de solicitud, la clase del actor y el de la institución, además del identificador del actor y del `traceparent`
  cuando existen (sin valor cuando quien pide es anónimo o no hay traza)
- **Y** no contiene correo, nombre, documento de identidad, IP, contraseña, token ni importe alguno

#### Escenario: [BJ11] Los datos de tarea nunca son un objeto de Java serializado

- **DADO** una fila de `scheduled_tasks` cuyo `task_data` es un objeto de Java serializado
- **CUANDO** el trabajador intenta leerla para ejecutarla
- **ENTONCES** la rechaza sin deserializarla y la ejecución falla visiblemente
- **Y** una prueba que intenta programar con el serializador de objetos de Java falla

#### Escenario: [BJ12] La lista aprobada de campos rechaza un campo nuevo (control negativo)

- **DADO** un fixture permanente con un tipo de tarea que declara el campo `email` y otro que declara un
  campo de importe, y la lista aprobada de campos de cada tipo real
- **CUANDO** se ejecuta la prueba de lista aprobada
- **ENTONCES** falla en los dos fixtures señalando el tipo y el campo
- **Y** pasa con cada tipo real, y la prueba evalúa al menos un tipo registrado, no un conjunto vacío

#### Escenario: [BJ13] Todo tipo de tarea registrado tiene su entrada en la lista aprobada

- **DADO** el registro de tipos de tarea del trabajador
- **CUANDO** se compara con las entradas de la lista aprobada de campos
- **ENTONCES** cada tipo registrado tiene exactamente una entrada, y un tipo registrado sin entrada hace fallar
  la prueba

#### Escenario: [BJ52] La carga solo admite tipos que no pueden llevar texto libre

- **DADO** una carga con un componente `String`, otra con `BigDecimal`, otra con una lista y otra con un `record`
  anidado, y una carga con `UUID`, `Instant`, `LocalDate`, `boolean`, `int`, `long` y un `enum`
- **CUANDO** se ejecuta la regla de tipos de carga sobre cada una
- **ENTONCES** las cuatro primeras se rechazan señalando el tipo y el componente, y la última se admite
- **Y** la regla evalúa al menos un tipo de tarea real registrado

#### Escenario: [BJ53] Un documento de más de 4 KiB se rechaza al programar

- **DADO** una carga que, serializada con su `meta`, excede los 4 KiB, y otra de exactamente 4 KiB
- **CUANDO** se intenta programar cada una dentro de una transacción
- **ENTONCES** la primera se rechaza con un error y no deja fila, y la segunda se programa

#### Escenario: [BJ54] Un campo desconocido en una fila se rechaza al leerla

- **DADO** una fila cuyo `task_data` es JSON válido con un campo adicional en `payload` o en `meta`
- **CUANDO** el trabajador la toma
- **ENTONCES** la rechaza antes de llamar al manejador y la ejecución falla visiblemente

#### Escenario: [BJ55] El error de serialización no revela los datos

- **DADO** una fila cuyo `task_data` contiene un valor distintivo y es inválido para el tipo de la tarea
- **CUANDO** el trabajador intenta leerla
- **ENTONCES** el mensaje del error nombra la tarea y la clase de la excepción original
- **Y** ni el mensaje, ni su causa encadenada ni ningún evento de registro contienen el valor distintivo

### Requisito: Las tareas únicas llevan un identificador de instancia determinista derivado de datos no personales

El sistema DEBE identificar cada tarea única por un identificador de instancia determinista, derivado solo de
datos no personales (por ejemplo institución, cuenta interna e identificador de solicitud), por lo que el puerto
que programa DEBE recibir esos datos, incluido el identificador de solicitud. El identificador es una cadena de
exactamente 32 caracteres hexadecimales en minúscula, opaca. Programar dos veces la misma instancia NO DEBE crear
una segunda fila ni abortar la transacción. Dos instancias con datos distintos, o con el mismo conjunto de datos en
otro tipo de tarea, DEBEN tener identificadores distintos. El identificador NO DEBE contener correo, nombre ni
ningún dato personal.

#### Escenario: [BJ14] Programar la misma instancia dos veces deja una sola fila

- **DADO** una tarea única con la institución `INS-1`, la cuenta interna `ACC-7` y la solicitud `REQ-100`
- **CUANDO** se programa dos veces en transacciones confirmadas
- **ENTONCES** existe exactamente una fila para esa instancia

#### Escenario: [BJ15] Instancias distintas tienen identificadores distintos y sin datos personales

- **DADO** dos solicitudes distintas (`REQ-100` y `REQ-101`) para la misma cuenta interna `ACC-7`
- **CUANDO** se programan
- **ENTONCES** existen dos filas con identificadores de instancia distintos
- **Y** ninguno de los identificadores contiene un correo ni un nombre

#### Escenario: [BJ56] El identificador de instancia es determinista, opaco y sensible a cada dato

- **DADO** la institución `INS-1`, la cuenta `ACC-7` y la solicitud `REQ-100` en el tipo de tarea de emisión
- **CUANDO** se deriva el identificador de instancia dos veces, y después cambiando, por separado, la institución,
  la cuenta, la solicitud y el tipo de tarea
- **ENTONCES** las dos primeras derivaciones son idénticas y cada una de las cuatro variantes produce un
  identificador distinto
- **Y** todos tienen exactamente 32 caracteres hexadecimales en minúscula

### Requisito: Cada ejecución corre con el contexto de seguridad de una institución y solo ve los datos de esa institución

El sistema DEBE ejecutar todo manejador dentro del `TransactionRunner`, con la institución de su tarea y el
actor `system`. Un manejador solo DEBE ver y modificar datos de la institución de su tarea, y DEBE revalidar
contra la base de datos todo lo que la tarea afirma (los datos de tarea no son una fuente de verdad). Una tarea
cuya envoltura no trae institución (o está incompleta) NO DEBE producir ningún efecto de negocio y DEBE fallar
visiblemente. Una tarea que trae una institución inexistente, o una cuenta de otra institución, NO DEBE producir
ningún efecto de negocio: bajo la seguridad de fila el manejador no ve ninguna fila, revalida y termina sin
efecto. Durante la ejecución, el registro lleva el nombre de la tarea, la instancia, la correlación, la solicitud,
la institución y la clase de actor `system`.

#### Escenario: [BJ16] El manejador corre como `system` en la institución de la tarea

- **DADO** una tarea de la institución `INS-1`
- **CUANDO** el trabajador la ejecuta y el manejador escribe un asiento de auditoría
- **ENTONCES** el asiento pertenece a `INS-1` y su actor es de clase `system`

#### Escenario: [BJ17] Un manejador no ve los datos de otra institución

- **DADO** dos instituciones con datos propios y una tarea de la primera cuyo manejador consulta la tabla que
  maneja
- **CUANDO** el trabajador la ejecuta
- **ENTONCES** el manejador solo ve y modifica filas de la primera institución
- **Y** las filas de la segunda quedan intactas, y la primera institución sí ve las suyas, de modo que una
  política que ocultara todo a todos no satisface el escenario

#### Escenario: [BJ18] Una tarea sin institución no produce ningún efecto

- **DADO** una fila de `scheduled_tasks` cuya envoltura no trae institución
- **CUANDO** el trabajador la toma
- **ENTONCES** el manejador no ejecuta ningún efecto de negocio
- **Y** la ejecución se registra como fallida y visible

#### Escenario: [BJ57] Una tarea de una institución inexistente no produce efecto

- **DADO** una tarea con una envoltura completa cuya institución no existe en la base de datos, y datos de
  negocio de otras instituciones
- **CUANDO** el trabajador la ejecuta
- **ENTONCES** el manejador no ve ni modifica ninguna fila y no se escribe ningún efecto de negocio
- **Y** los datos de las demás instituciones quedan intactos

### Requisito: Todo tipo de tarea produce un solo efecto aunque se ejecute dos veces

El sistema DEBE garantizar que la ejecución de una misma tarea dos veces, como ocurre con la entrega «al menos
una vez» de db-scheduler, produzca el mismo efecto de negocio que una sola ejecución (ADR-0016, cumplimiento 5).
Cada tipo de tarea registrado DEBE tener una prueba de doble ejecución con PostgreSQL real, y la prueba DEBE
evaluar todos los tipos registrados.

#### Escenario: [BJ19] La doble ejecución de cada tipo registrado deja un solo efecto

- **DADO** cada tipo de tarea registrado y el estado de la base de datos tras su primera ejecución
- **CUANDO** el mismo manejador se ejecuta una segunda vez con los mismos datos
- **ENTONCES** el estado de negocio resultante equivale al de una sola ejecución (los efectos específicos de
  cada tipo se declaran en `identity`, `build-integrity` y `audit-trail`)
- **Y** la segunda ejecución no falla

#### Escenario: [BJ20] Un manejador no idempotente hace fallar la prueba (control negativo)

- **DADO** un fixture permanente con un manejador cuya segunda ejecución duplica su efecto
- **CUANDO** se ejecuta la prueba de doble ejecución sobre el fixture
- **ENTONCES** falla señalando el tipo, y la prueba sobre los tipos reales evalúa al menos uno

### Requisito: Los reintentos son acotados con espera exponencial, y una tarea agotada se conserva visible como fallida

El sistema DEBE reintentar una tarea única fallida con espera exponencial, con tope, y un máximo de intentos por
tipo de tarea (la emisión de recuperación: 5 intentos, primera espera de 30 segundos, factor 2, tope de 5
minutos). Mientras reintenta, DEBE llevar la cuenta en `consecutive_failures` y el instante del último fallo en
`last_failure`. Una tarea agotada NO DEBE borrarse ni descartarse en silencio (la política por omisión de la
biblioteca borra la ejecución al agotarse y NO se usa): DEBE **estacionarse**, es decir, reprogramarse a
`9999-01-01T00:00:00Z` conservando `consecutive_failures` y `last_failure`, de modo que ningún sondeo la tome,
siga en `scheduled_tasks` como fallida y sea visible para las métricas y la alerta. Cada fallo DEBE escribir una
advertencia `job_failed` con la tarea, el número de intento y el desenlace (`retry` o `parked`). Una tarea recurrente
fallida NO se reintenta: se reprograma a su próxima ocurrencia y cuenta como fallida mientras
`consecutive_failures` sea mayor que cero. Todo fallo de un manejador DEBE sanearse antes de salir al registro:
solo se conservan el nombre de la tarea, la clase de la excepción original y, si la cadena de causas trae un error
de base de datos, su `SQLState`; nunca el mensaje ni la causa encadenada. Un éxito DEBE completar la tarea única (se
retira) o reprogramar la recurrente, y reiniciar su cuenta de fallos. Reprogramar a mano una tarea estacionada es
un `UPDATE` documentado de `execution_time` y `consecutive_failures`; no se entrega ninguna herramienta.

#### Escenario: [BJ21] Un fallo transitorio se reintenta y termina en éxito

- **DADO** una tarea cuyo manejador falla en el primer intento y tiene éxito en el segundo
- **CUANDO** el trabajador la ejecuta con reintentos
- **ENTONCES** el efecto de negocio existe una sola vez
- **Y** la tarea única deja de existir en `scheduled_tasks`

#### Escenario: [BJ22] La espera crece y el número de intentos está acotado

- **DADO** una tarea cuyo manejador siempre falla y un tipo con máximo de `N` intentos
- **CUANDO** el trabajador la ejecuta hasta agotarla
- **ENTONCES** hubo exactamente `N` intentos
- **Y** la espera entre intentos consecutivos crece de forma exponencial, y estrictamente creciente mientras no
  alcance el tope del tipo, y desde entonces es igual al tope

#### Escenario: [BJ23] Una tarea agotada se conserva como fallida

- **DADO** la tarea del escenario anterior tras su último intento
- **CUANDO** se consulta `scheduled_tasks`, incluso tras reiniciar el trabajador y esperar más que la espera
  máxima
- **ENTONCES** la fila sigue existiendo, con `consecutive_failures` igual a `N`, `last_failure` con el instante
  del último fallo y `execution_time` igual a `9999-01-01T00:00:00Z` (estacionada)
- **Y** el trabajador no la vuelve a tomar
- **Y** la métrica de tareas fallidas la cuenta

#### Escenario: [BJ24] Un éxito reinicia la cuenta de fallos de una tarea recurrente

- **DADO** una tarea recurrente con `consecutive_failures` igual a 2
- **CUANDO** su siguiente ejecución tiene éxito
- **ENTONCES** `consecutive_failures` vuelve a 0 y la tarea queda programada para su siguiente ciclo

#### Escenario: [BJ58] Cada fallo deja una advertencia con el intento y el desenlace

- **DADO** una tarea única de un tipo con 3 intentos cuyo manejador siempre falla
- **CUANDO** el trabajador la ejecuta hasta agotarla
- **ENTONCES** hay tres advertencias `job_failed`, con el nombre de la tarea y el número de intento, las dos
  primeras con desenlace `retry` y la tercera con `parked`
- **Y** ninguna contiene datos de la tarea

#### Escenario: [BJ59] Una tarea agotada no se borra: control negativo contra la política por omisión de la biblioteca

- **DADO** un fixture permanente que usa la política de la biblioteca que borra la ejecución al agotarse, y el
  tipo real con la política propia
- **CUANDO** ambos agotan sus intentos y se consulta `scheduled_tasks`
- **ENTONCES** con el fixture la fila ya no existe y la prueba falla señalando el tipo
- **Y** con el tipo real la fila existe, estacionada en `9999-01-01T00:00:00Z`, con `consecutive_failures` y
  `last_failure` conservados

#### Escenario: [BJ60] El fallo de un manejador se sanea antes de llegar al registro

- **DADO** un manejador que lanza una excepción cuyo mensaje contiene un valor distintivo y cuya causa es un
  error de base de datos con un `SQLState`
- **CUANDO** el trabajador lo ejecuta, incluido el evento de nivel `ERROR` de la propia biblioteca
- **ENTONCES** ningún evento contiene el valor distintivo ni el mensaje original ni la causa encadenada
- **Y** los eventos conservan el nombre de la tarea, la clase de la excepción original y el `SQLState`

#### Escenario: [BJ61] Una tarea recurrente fallida no se reintenta y queda visible hasta su próximo éxito

- **DADO** una tarea recurrente diaria cuya ocurrencia de hoy falla
- **CUANDO** el trabajador registra el fallo
- **ENTONCES** la ocurrencia no se reintenta, la tarea queda programada para su próxima ocurrencia y
  `consecutive_failures` es 1, por lo que la métrica de tareas fallidas la cuenta
- **Y** la próxima ocurrencia exitosa vuelve la cuenta a 0 y la deja de contar

### Requisito: Una ejecución muerta se recupera dentro del plazo de la alerta `QueueStalled`

El sistema DEBE detectar una ejecución muerta (el trabajador cae a mitad de una tarea) y devolverla a la cola en
un tiempo estrictamente menor que 900 segundos, la meta de la alerta `QueueStalled` (latido cada 60 segundos y 6 latidos perdidos: unos 6 minutos). El latido de una ejecución
sana NO DEBE hacerla pasar por muerta, y una tarea viva NO DEBE ejecutarse a la vez en dos trabajadores.

#### Escenario: [BJ25] Una ejecución muerta se revive antes de 900 segundos

- **DADO** una tarea tomada por un trabajador que se detiene a mitad de la ejecución
- **CUANDO** transcurre el plazo de detección configurado y otro trabajador está en marcha
- **ENTONCES** la tarea se vuelve a ejecutar, y el tiempo transcurrido entre la caída y la nueva toma es menor
  que 900 segundos
- **Y** como el manejador es idempotente, el efecto de negocio existe una sola vez

#### Escenario: [BJ26] Una ejecución larga y sana no se revive

- **DADO** una tarea cuyo manejador tarda más que el intervalo del latido, con el trabajador vivo
- **CUANDO** transcurre ese tiempo
- **ENTONCES** ningún otro trabajador la toma y el manejador se ejecuta una sola vez

### Requisito: Las tareas recurrentes se declaran en código con la zona horaria `America/Tegucigalpa` explícita y corren una vez por ciclo

El sistema DEBE declarar toda tarea recurrente en código, con su cadencia calculada en la zona
`America/Tegucigalpa` fijada de forma explícita, sin depender de la zona por defecto de la JVM ni del servidor.
Con varios trabajadores en marcha, cada ciclo DEBE ejecutarse una sola vez. Reiniciar el trabajador NO DEBE
duplicar ni perder el ciclo en curso. Los nombres de las tareas DEBEN ser únicos: un nombre repetido impide el
arranque. Las tareas recurrentes de este cambio, con su cadencia en `America/Tegucigalpa` (Honduras no aplica
horario de verano), son:

| Nombre | Cadencia |
|---|---|
| `shared.audit-chain-verification-daily` | Diaria, 03:00 |
| `shared.audit-chain-verification-weekly` | Domingo, 03:30 |
| `identity.purge-password-reset-tokens` | Diaria, 04:30 |
| `identity.purge-refresh-tokens` | Diaria, 04:35 |
| `shared.purge-idempotency-keys` | Diaria, 04:40 |
| `identity.purge-login-backoff` | Diaria, 04:45 |
| `identity.purge-mfa-totp-backoff` | Diaria, 04:50 |
| `shared.queue-observation` | Cada 60 segundos |

#### Escenario: [BJ27] La cadencia no depende de la zona por defecto de la JVM

- **DADO** una tarea recurrente declarada para las 02:00 y dos arranques de prueba, uno con la zona por defecto
  de la JVM en UTC y otro en `America/New_York`
- **CUANDO** se calcula el siguiente instante de ejecución a partir del mismo instante de referencia
- **ENTONCES** ambos resultados son el mismo instante, las 02:00 de Tegucigalpa (08:00 UTC, sin horario de
  verano)

#### Escenario: [BJ28] Dos trabajadores ejecutan cada ciclo una sola vez

- **DADO** dos contextos de trabajador contra la misma base de datos y una tarea recurrente cuyo ciclo vence
- **CUANDO** transcurre ese ciclo
- **ENTONCES** el manejador se ejecutó exactamente una vez

#### Escenario: [BJ29] Reiniciar el trabajador no duplica el ciclo

- **DADO** una tarea recurrente ya ejecutada en el ciclo de hoy
- **CUANDO** el trabajador se reinicia antes del ciclo de mañana
- **ENTONCES** existe una sola fila de esa tarea recurrente y no se ejecuta de nuevo hoy

#### Escenario: [BJ62] Cada tarea recurrente del catálogo tiene su próxima ejecución exacta

- **DADO** el instante fijo de referencia 2026-10-25 00:00 en Tegucigalpa (domingo) y las ocho tareas recurrentes
  del catálogo
- **CUANDO** se calcula la próxima ejecución de cada una
- **ENTONCES** son, el mismo día, 03:00 (verificación diaria), 03:30 (semanal), 04:30, 04:35, 04:40, 04:45 y 04:50
  (las cinco purgas) y 00:01 (observación de la cola, 60 segundos después)
- **Y** ninguna está más lejos que un año del instante de referencia, de modo que ninguna alcanza el instante de
  estacionamiento `9999-01-01`

#### Escenario: [BJ63] Dos tareas con el mismo nombre impiden el arranque

- **DADO** el trabajador con dos definiciones de tarea que comparten el nombre
- **CUANDO** se construye su contexto
- **ENTONCES** el arranque falla nombrando el nombre repetido

### Requisito: Las tareas recurrentes corren por cada institución configurada del trabajador, y por ninguna otra

El sistema DEBE obtener por configuración del proceso (`confia.jobs.institutions`, una lista de UUID separados por
comas; no es un secreto) las instituciones sobre las que corren las tareas recurrentes (en F0 hay una sola) y DEBE
ejecutar cada ciclo una vez por institución configurada, con el actor `system` y el contexto de esa institución. La
lista es obligatoria y no puede ser vacía, ni traer entradas inválidas ni repetidas: el proceso trabajador NO DEBE
arrancar en esos casos, y el mensaje nombra la propiedad y la posición de la entrada inválida sin repetir la lista.
El trabajador NO DEBE descubrir instituciones consultando datos de otro módulo. Una institución no configurada NO
DEBE recibir ninguna ejecución. Cada ocurrencia recibe un identificador de ejecución (`runId`) que sirve de
correlación y de solicitud de todo lo que haga. El fallo de una institución NO DEBE impedir la ejecución de las
demás: se cuenta, se registra una advertencia `job_institution_failed` con la tarea, la institución y la clase de la
excepción, y al final, si alguna falló, la ocurrencia completa se registra como fallida.

#### Escenario: [BJ30] Cada ciclo corre una vez por institución configurada

- **DADO** el trabajador configurado con las instituciones `INS-1` e `INS-2`, y una tercera `INS-3` existente en
  la base de datos pero no configurada
- **CUANDO** vence el ciclo de una tarea recurrente
- **ENTONCES** hay una ejecución con el contexto de `INS-1` y otra con el de `INS-2`, ambas con el actor `system`
- **Y** ninguna con el de `INS-3`, y los datos de `INS-3` quedan intactos

#### Escenario: [BJ31] Sin instituciones configuradas el trabajador no arranca y nada actúa sobre datos

- **DADO** el trabajador sin la propiedad `confia.jobs.institutions` o con la lista vacía, y filas vencidas en una
  institución existente
- **CUANDO** se intenta arrancar el proceso
- **ENTONCES** el arranque falla con un mensaje que nombra la propiedad
- **Y** no se ejecuta ninguna tarea recurrente, por lo que no se borra ni se modifica ninguna fila

#### Escenario: [BJ64] Una lista con una entrada inválida o repetida impide el arranque

- **DADO** el trabajador con la lista `UUID-válido,no-es-uuid`, otro con el mismo UUID dos veces y otro con una
  lista válida
- **CUANDO** se construye el contexto de cada uno
- **ENTONCES** los dos primeros fallan con un mensaje que nombra la propiedad y la posición de la entrada
  inválida o repetida, sin repetir la lista, y el tercero arranca

#### Escenario: [BJ65] Una institución que falla no impide a las demás y la ocurrencia queda fallida

- **DADO** el trabajador configurado con `INS-1` e `INS-2`, y una tarea recurrente cuyo manejador falla para
  `INS-1`
- **CUANDO** vence el ciclo
- **ENTONCES** el manejador se ejecuta también para `INS-2` y termina con éxito
- **Y** se escribe una advertencia `job_institution_failed` con la tarea, `INS-1` y la clase de la excepción
- **Y** la ocurrencia queda registrada como fallida (`consecutive_failures` mayor que cero)

#### Escenario: [BJ66] La ocurrencia usa su identificador de ejecución como correlación y solicitud

- **DADO** una ocurrencia de una tarea recurrente configurada con `INS-1`
- **CUANDO** el manejador escribe un asiento de auditoría
- **ENTONCES** el asiento y los eventos de registro de esa ocurrencia llevan el mismo identificador de ejecución
  como correlación y como solicitud, el actor `system` y la institución `INS-1`

### Requisito: Las métricas de cola se leen sin tocar los datos de tarea y la preparación se degrada por encima de los 900 segundos

El sistema DEBE exponer, mediante un puerto de métricas de cola, la profundidad de la cola (`confia_queue_depth`)
por tarea y por estado (programada, vencida, en ejecución y fallida), la antigüedad de la tarea vencida más antigua
no tomada (`confia_queue_oldest_job_age_seconds`) y el número de tareas fallidas, calculadas con consultas de solo
lectura sobre `scheduled_tasks` que NUNCA seleccionan la columna `task_data` y solo usan el nombre, el instante de
ejecución, la marca de tomada, el último latido y la cuenta de fallos. Una tarea es «fallida» si está estacionada
o si es recurrente con `consecutive_failures` mayor que cero; es «en ejecución» si está tomada; es «vencida» si no
está tomada y su instante de ejecución ya pasó. Una tarea tomada cuyo último latido supera el límite de latidos
perdidos cuenta como atascada y entra en la antigüedad. Una tarea estacionada NO cuenta en la antigüedad. Hasta
los cambios 10 y 11, la publicación DEBE ser un registro estructurado, sin exportadores. La preparación DEBE
reportarse como degradada cuando esa antigüedad supera **estrictamente** los 900 segundos, y DEBE volver a normal
cuando la cola se vacía o la antigüedad baja a 900 segundos o menos; el estado degradado informa pero no retira
el proceso del tráfico. La observación es una tarea recurrente del trabajador, cada 60 segundos. Como corre en la
misma cola que observa, si el ejecutor entero se detiene el evento deja de aparecer; los cambios 10 y 11 DEBEN
montar además una alerta por **ausencia** de `queue_state` durante más de 5 minutos.

#### Escenario: [BJ32] Profundidad, antigüedad y fallidas

- **DADO** `scheduled_tasks` con tres tareas vencidas no tomadas (la más antigua vencida hace 120 s), una tarea
  programada a futuro y una tarea agotada
- **CUANDO** se leen las métricas de cola
- **ENTONCES** la profundidad cuenta las tres vencidas no tomadas y no cuenta la futura
- **Y** la antigüedad es de 120 segundos
- **Y** el número de fallidas es 1

#### Escenario: [BJ33] Ninguna consulta de métricas selecciona `task_data`

- **DADO** las consultas del puerto de métricas
- **CUANDO** se inspeccionan sus sentencias, y se ejecuta la misma inspección sobre un fixture permanente que
  selecciona `task_data`
- **ENTONCES** ninguna sentencia real menciona la columna y el fixture falla

#### Escenario: [BJ34] El umbral de 900 segundos es exacto

- **DADO** una tarea vencida y no tomada cuya antigüedad es de 899 s, otra de 900 s y otra de 901 s, evaluadas
  por separado
- **CUANDO** se evalúa el estado de preparación
- **ENTONCES** con 899 s y con 900 s es normal y con 901 s es degradado (el umbral es estrictamente mayor que
  900 s, conforme al diseño)

#### Escenario: [BJ35] El estado vuelve a normal al vaciarse la cola

- **DADO** una preparación degradada por una tarea de 950 s
- **CUANDO** la tarea se ejecuta y la cola queda sin tareas vencidas
- **ENTONCES** la preparación vuelve a normal
- **Y** el estado degradado no retira al proceso del tráfico

#### Escenario: [BJ36] La publicación por registro estructurado no filtra datos de tarea

- **DADO** el adaptador de registro estructurado de las métricas, con filas que tienen datos de tarea distintivos
- **CUANDO** publica las métricas
- **ENTONCES** los eventos contienen los nombres y valores de las métricas y ningún fragmento de `task_data`
- **Y** ningún identificador de instancia

#### Escenario: [BJ67] Estados de la cola y tarea tomada con latido vencido

- **DADO** `scheduled_tasks` con una tarea programada a futuro, dos vencidas no tomadas, una tomada con latido
  reciente, una tomada cuyo último latido superó el límite de latidos perdidos, una estacionada y una recurrente
  con `consecutive_failures` igual a 1
- **CUANDO** se leen las métricas de cola
- **ENTONCES** las profundidades son: programada 1, vencida 2, en ejecución 2 y fallida 2
- **Y** la antigüedad es la mayor entre la de las vencidas no tomadas y la del latido vencido de la tarea atascada,
  y la estacionada no cuenta en ella

#### Escenario: [BJ68] La observación escribe un evento de estado por ocurrencia

- **DADO** la tarea de observación de la cola en el trabajador
- **CUANDO** corre una ocurrencia
- **ENTONCES** escribe un evento `queue_state` de nivel `INFO` con las cuentas `scheduled`, `due`, `running` y
  `failed` y `oldest_due_age_seconds`
- **Y** el evento no contiene datos de tarea ni identificadores de instancia

#### Escenario: [BJ69] Las tareas fallidas y la cola atascada dejan advertencias

- **DADO** una cola con una tarea estacionada de un tipo y una antigüedad de 901 s
- **CUANDO** corre la observación
- **ENTONCES** escribe una advertencia `queue_task_failed` por cada nombre de tarea con fallidas
- **Y** escribe una advertencia `queue_stalled`, y con la antigüedad en 900 s no la escribe

#### Escenario: [BJ70] La observación y su límite quedan documentados (solo documental)

- **DADO** la nota fechada de `docs/07` §11.1
- **CUANDO** se lee
- **ENTONCES** declara que la observación corre en la misma cola que observa y que los cambios 10 y 11 deben
  montar una alerta por ausencia de `queue_state` durante más de 5 minutos

### Requisito: La prueba del criterio de salida 5 corre con PostgreSQL real y cierra la brecha de F0

El sistema DEBE demostrar con PostgreSQL real, en una prueba de extremo a extremo, las dos mitades del criterio
de salida 5 de F0: una tarea programada dentro de una transacción revertida no existe, y solo el proceso
`confia-worker` ejecuta tareas. `docs/09` DEBE marcar el criterio con una nota fechada al cerrar el cambio.

#### Escenario: [BJ37] Criterio 5 de extremo a extremo

- **DADO** PostgreSQL real con el esquema migrado, un contexto administrativo y un contexto de trabajador
- **CUANDO** el administrativo programa una tarea en una transacción revertida y otra en una confirmada
- **ENTONCES** la primera no existe y nunca se ejecuta
- **Y** la segunda existe y solo la ejecuta el contexto del trabajador

### Requisito: Ausencia de exportadores de métricas (brecha con destino: cambios 10 y 11)

El sistema NO DEBE incluir, en este cambio, Actuator, Prometheus ni ningún exportador de métricas de cola, ni
declarar una dependencia directa de Micrometer. El iniciador de la biblioteca trae `micrometer-core` como
dependencia transitiva; eso se admite siempre que ningún proceso contenga un `MeterRegistry`. Los exportadores y los
tableros pertenecen a los cambios 10 y 11. La publicación de las métricas de cola es solo el registro estructurado.

#### Escenario: [BJ38] No hay exportadores en el árbol — cierto hoy, falso al cerrar los cambios 10 y 11

- **DADO** el árbol de dependencias de `apps/api` al cerrar este cambio
- **CUANDO** se busca una dependencia de Micrometer, Actuator o Prometheus declarada por este cambio, y un
  `MeterRegistry` en el contexto de cada uno de los tres procesos
- **ENTONCES** ninguna dependencia directa existe (`micrometer-core` transitivo del iniciador queda admitido) y
  ningún proceso contiene un `MeterRegistry`; este resultado deja de ser cierto el día que los cambios 10 y 11 las
  incorporen

### Requisito: Ausencia de descubrimiento de instituciones por el trabajador (brecha con destino: fase 2)

El sistema NO DEBE descubrir, en este cambio, las instituciones sobre las que actúa el trabajador: la lista es
siempre la de la configuración. El descubrimiento sin configuración queda para la fase 2, con la deuda registrada
en `foundations-plan/exploration.md`.

#### Escenario: [BJ39] El trabajador no consulta la tabla de instituciones — cierto hoy, falso al cerrar la fase 2

- **DADO** el código de producción del trabajador
- **CUANDO** se inspecciona si alguna clase suya lee `organization_institution` o importa el módulo
  `organization`
- **ENTONCES** ninguna lo hace; este resultado deja de ser cierto el día que la fase 2 entregue el descubrimiento

### Requisito: Ausencia de tareas de negocio del inventario de ADR-0016 (brecha con destino: los módulos que las poseen)

El sistema NO DEBE registrar, en este cambio, ninguna tarea de negocio del inventario de ADR-0016 (generación de
cargos, verificación de integridad del libro mayor, vigilancia del rango CAI). Los únicos tipos de tarea
registrados son la emisión de recuperación (única), las cinco purgas, las verificaciones diaria y semanal de la
cadena de auditoría y la observación de la cola (recurrentes). Cada tarea de negocio llegará con el cambio del
módulo que la posee.

#### Escenario: [BJ40] El registro de tipos contiene solo los tipos de este cambio — cierto hoy, falso al cerrar cada cambio dueño

- **DADO** el registro de tipos de tarea del trabajador
- **CUANDO** se enumera
- **ENTONCES** contiene exactamente la emisión de recuperación (`identity.password-reset-issuance`, única) y las
  ocho tareas recurrentes del catálogo: las verificaciones diaria y semanal de la cadena, las purgas de tokens de
  recuperación, de tokens de refresco, de claves de idempotencia, de retroceso de inicio de sesión y de retroceso
  de TOTP, y la observación de la cola
- **Y** un fixture que añade un tipo de negocio no declarado hace fallar la enumeración

### Requisito: Las notas de documentación y los seguimientos quedan escritos, sin reescribir su texto

El cambio DEBE dejar notas fechadas, sin reescribir el cuerpo original de ningún documento, en `docs/03`
(§6.1: las purgas usan funciones `SECURITY DEFINER` y ningún rol obtiene `DELETE`; `EXECUTE` solo para
`confia_admin_app`), `docs/07` (§3.3: `TracedJob` vive en `shared.jobs` y no en `shared/observability/context`; §11
y §11.1: tabla de tareas, estado fallido estacionado, reprogramación manual y alerta por ausencia de `queue_state`),
`docs/08` (§6.2: la regla semanal de marca y gracia aplica a datos de negocio y personales, no a las tablas
técnicas, que se purgan a diario), `docs/05` (§6: `SPRING_DATASOURCE_*` del trabajador con `confia_admin_app` y del
portal con `confia_portal_app`, `CONFIA_JOBS_INSTITUTIONS`, y la nota que **revierte** la del 2026-10-04 según la
cual «`portal` y `worker` no abren conexión», por la decisión D-N1 del propietario del 2026-10-24) y `docs/09`
(criterio 5). DEBE crear el ADR-0025, «Purga de tablas técnicas de retención mediante funciones `SECURITY
DEFINER`», en estado «Propuesto» (decisión D-N3 del propietario del 2026-10-24), y agregar a ADR-0017 una nota
fechada que lo apunta, sin reescribir ADR-0017. DEBE registrar en `foundations-plan/exploration.md`, con nota
fechada, que la condición 4 queda cerrada, los seguimientos `audit-chain-hourly-anchor-verification` y
`data-encryption-key-batch-rotation` con su dependencia, y la deuda de la fase 2 sobre las instituciones del
trabajador. La deriva documental de `JWT_ADMIN_SIGNING_KEY` para el trabajador en `docs/05` §6 se registra, no se
corrige.

#### Escenario: [BJ41] Notas fechadas en `docs/03`, `docs/05`, `docs/07`, `docs/08` y `docs/09` (solo documental)

- **DADO** los cinco documentos tras este cambio
- **CUANDO** se leen las secciones citadas (`docs/07` en §3.3, §11 y §11.1)
- **ENTONCES** cada una tiene una nota fechada con su contenido, y `docs/09` marca el criterio 5
- **Y** el texto original de los cinco documentos no fue modificado

#### Escenario: [BJ71] La nota de `docs/05` revierte la del 2026-10-04 sobre el portal (solo documental)

- **DADO** `docs/05` §6 tras este cambio
- **CUANDO** se lee la nota fechada del cambio
- **ENTONCES** declara que el portal recibe un `DataSource` con `confia_portal_app` y que el trabajador usa
  `confia_admin_app`, y nombra expresamente que revierte la nota del 2026-10-04 («`portal` y `worker` no abren
  conexión») y la decisión D5 de `web-edge-foundations`
- **Y** el texto original de la nota del 2026-10-04 no fue modificado

#### Escenario: [BJ72] ADR-0025 en estado «Propuesto» y nota fechada en ADR-0017 (solo documental)

- **DADO** `docs/adr` tras este cambio
- **CUANDO** se lee el ADR-0025 y ADR-0017
- **ENTONCES** ADR-0025 existe con estado «Propuesto», su índice en `docs/adr/README.md`, y registra la plantilla
  de las funciones de purga `SECURITY DEFINER` como regla verificable
- **Y** ADR-0017 tiene una nota fechada que apunta a ADR-0025 y su texto original no fue modificado

#### Escenario: [BJ42] Seguimientos y condición 4 en el plan de fundaciones (solo documental)

- **DADO** `openspec/changes/foundations-plan/exploration.md` tras este cambio
- **CUANDO** se lee su nota fechada de este cambio
- **ENTONCES** declara cerrada la condición 4, nombra los seguimientos `audit-chain-hourly-anchor-verification`
  (depende de los cambios 9 y 11) y `data-encryption-key-batch-rotation` (depende del cambio 9 y de una
  superficie de rotación que hoy no existe), y registra la deuda de la fase 2 sobre las instituciones del
  trabajador
- **Y** el texto original de las condiciones no fue modificado

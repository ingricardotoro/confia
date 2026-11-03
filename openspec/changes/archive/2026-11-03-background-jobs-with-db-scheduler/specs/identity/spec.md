# Delta para Identidad

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `background-jobs-with-db-scheduler` (F0, cambio 9)
- **Propuesta:** `proposal.md`, aprobada el 2026-10-24, con P1 a P9 vinculantes (P3: ventanas de las purgas; P4:
  emisor de enlaces que falla de forma explícita; P5: instituciones por configuración)

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `I229` en adelante, a continuación del máximo de la especificación vigente
  (`I228`); `I229` a `I267` son los de la primera redacción y `I268` en adelante los añadidos al conciliar la
  especificación con el diseño (2026-10-24). Los escenarios que se conservan de la especificación vigente y no
  llevaban número no lo llevan.
- Un escenario marcado «(solo documental)» no tiene aserción automática.
- Este delta describe comportamiento observable; los nombres de clases y de asientos de auditoría los fija el
  diseño. El comportamiento general de las tareas (atomicidad, ejecutor, reintentos, contexto de institución)
  está en la capacidad `background-jobs`.
- «Ventana» es el plazo, contado desde el instante de referencia de la fila, tras el cual una fila se vuelve
  purgable. Una fila es purgable solo cuando el tiempo transcurrido desde su referencia es **estrictamente mayor**
  que la ventana: en el borde exacto la fila se conserva. El diseño (decisión 16) confirmó este lado del borde: el
  corte es `referencia < corte`, estrictamente.
- Las ventanas de las purgas de tablas técnicas son las de la decisión P3 (`docs/07` §11): se purga a diario, sin
  marca previa ni gracia de siete días. El llamador calcula el corte con su reloj (instante actual menos la
  ventana) y lo pasa a la función de purga, que nunca borra una fila más reciente que su piso (ver
  `build-integrity`).
- Cada purga procesa por lotes de 5 000 filas, hasta 200 lotes por institución y por ocurrencia, cada lote en su
  propia transacción y con su propio asiento de auditoría (ver `audit-trail`).
- Una función de purga invocada sin contexto de institución, o por un actor que no es `system`, **falla con un
  error**; nunca termina como un éxito de cero filas.
- Las filas de «Ausencia» que este delta retira se sustituyen por los requisitos que las invierten; el destino
  de la rotación de llaves de datos cambia al seguimiento `data-encryption-key-batch-rotation`.

## ADDED Requirements

### Requisito: La solicitud de recuperación programa la emisión con un adaptador de producción, dentro de su transacción y sin datos personales

El sistema DEBE proveer el adaptador de producción del puerto de programación de la emisión
(`PasswordResetIssuanceScheduler`), que programa una tarea única, para ejecutarse de inmediato, en la misma
transacción de la solicitud; el puerto recibe el identificador de solicitud además de la institución y la cuenta.
La tarea DEBE llevar solo identificadores internos: en la carga, el identificador de la cuenta; en la envoltura,
la institución y el identificador de solicitud (que es también la correlación), con la clase de actor `STAFF` y
sin identificador de actor, porque quien pide es anónimo. NO DEBE contener el correo ni el identificador escrito por
la persona. Su identificador de instancia DEBE ser determinista a partir de institución, cuenta y solicitud. Una solicitud para un identificador que no corresponde a ninguna cuenta
NO DEBE programar nada. Una solicitud cuya transacción se revierte NO DEBE dejar tarea. La solicitud sigue sin
generar, almacenar ni devolver ningún token.

#### Escenario: [I229] La solicitud programa una tarea única con identificadores internos

- **DADO** la cuenta `ana.martinez@colegio.edu.hn`, con identificador interno `ACC-7`, y la solicitud `REQ-100`
  de la institución `INS-1`
- **CUANDO** se solicita recuperación a las 10:00:00 del 2026-10-25 y la transacción se confirma
- **ENTONCES** existe exactamente una tarea de emisión cuya carga contiene `ACC-7` y cuya envoltura contiene
  `INS-1` y `REQ-100` (este último también como correlación), con la clase de actor `STAFF` y sin identificador
  de actor
- **Y** ni los datos ni el identificador de instancia contienen el correo
- **Y** no existe todavía ningún token de recuperación para la cuenta

#### Escenario: [I230] Una solicitud revertida o para un identificador sin cuenta no programa nada

- **DADO** una solicitud cuya transacción lanza una excepción después de programar, y otra solicitud para
  `nadie@colegio.edu.hn`, que no corresponde a ninguna cuenta
- **CUANDO** ambas se atienden
- **ENTONCES** no existe ninguna tarea de emisión de ninguna de las dos

#### Escenario: [I231] La misma solicitud no programa dos tareas

- **DADO** la solicitud `REQ-100` de `ACC-7` ya programada
- **CUANDO** el mismo caso de uso la vuelve a programar con los mismos tres identificadores
- **ENTONCES** sigue existiendo una sola tarea de emisión para esa instancia, sin error y sin abortar la
  transacción de la solicitud
- **Y** una solicitud nueva `REQ-101` para la misma cuenta produce una segunda tarea, con otro identificador de
  instancia

### Requisito: La tarea de emisión revalida contra la base de datos y emite el token como `system` en la institución de la tarea

El manejador de la emisión DEBE ejecutar `IssuePasswordResetToken` con el actor `system` y la institución de la
tarea, y DEBE revalidar la cuenta contra la base de datos al ejecutar (los datos de tarea no son fuente de
verdad). El token DEBE existir solo después de que el manejador se ejecute. Si la cuenta ya no existe o no está
activa al ejecutar, el manejador NO DEBE emitir ningún token, NO DEBE llamar al puerto de envío y DEBE completar
sin error. La emisión DEBE respetar el límite de tres emisiones por hora y la regla de un solo token vivo. La
doble ejecución de la misma tarea DEBE dejar a lo sumo un token vivo por cuenta: la segunda emisión supera a la
primera (`supersedeOpen`), acotada por el límite de tres por hora. El efecto único que se afirma es exactamente
uno: **un solo token abierto para la cuenta y el último enlace entregado es el único válido**. Antes de abrir su
transacción, la emisión DEBE comprobar con el puerto de envío (`PasswordResetLinkSender.ensureAvailable()`) que
la entrega está disponible; si no lo está, la comprobación falla antes de emitir, de modo que ningún intento deja
un token emitido y no entregado, ni consume el límite de tres por hora, ni termina como omitido por límite.

#### Escenario: [I232] El token existe solo cuando la tarea se ejecuta

- **DADO** la tarea de emisión de la solicitud `REQ-100` de `ACC-7`, programada y todavía no ejecutada
- **CUANDO** transcurre cualquier tiempo sin que el trabajador la ejecute
- **ENTONCES** no existe ningún token de recuperación para la cuenta
- **Y** al ejecutarla el trabajador a las 10:00:05, existe exactamente un token vivo, entregado al puerto de
  envío una vez

#### Escenario: [I233] El manejador corre como `system` y en la institución de la tarea

- **DADO** una tarea de emisión de la institución `INS-1` y una cuenta de la institución `INS-2`
- **CUANDO** el trabajador ejecuta la tarea de `INS-1`
- **ENTONCES** el token y su asiento de auditoría pertenecen a `INS-1` y el actor del asiento es `system`
- **Y** la cuenta de `INS-2` no recibe ningún token

#### Escenario: [I234] La doble ejecución deja un solo token vivo

- **DADO** la misma tarea de emisión ejecutada dos veces a las 10:00:05 y a las 10:00:20
- **CUANDO** se consultan los tokens de la cuenta
- **ENTONCES** existen dos tokens emitidos, el primero marcado superado y solo el segundo vivo
- **Y** el primero se rechaza si se presenta
- **Y** el último enlace entregado al puerto de envío corresponde al segundo token, el único abierto

#### Escenario: [I235] La cuarta emisión dentro de la hora se omite sin fallar la tarea

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn` con tokens emitidos a las 10:00:00, 10:10:00 y 10:20:00 del
  2026-10-25
- **CUANDO** el trabajador ejecuta una cuarta tarea de emisión a las 10:30:00
- **ENTONCES** no se emite ningún token, el puerto de envío no recibe ninguna llamada y el token de las 10:20:00
  sigue vivo
- **Y** la bitácora registra la emisión omitida por límite
- **Y** la tarea termina con éxito y no se reintenta

#### Escenario: [I236] Una cuenta que ya no existe o no está activa no recibe token

- **DADO** una tarea de emisión programada para `ACC-7` y la cuenta desactivada antes de que el trabajador la
  ejecute
- **CUANDO** el trabajador ejecuta la tarea
- **ENTONCES** no se emite ningún token ni se llama al puerto de envío
- **Y** la tarea termina con éxito, sin quedar fallida

#### Escenario: [I268] Una tarea de una cuenta de otra institución termina sin efecto

- **DADO** una tarea de emisión de la institución `INS-1` cuyo identificador de cuenta pertenece a `INS-2`
- **CUANDO** el trabajador la ejecuta
- **ENTONCES** el desenlace es «cuenta no encontrada», sin token ni asiento en `INS-2`, y la tarea termina con éxito

#### Escenario: [I269] Con el envío disponible la emisión llama a la comprobación antes de abrir la transacción

- **DADO** un puerto de envío disponible que registra el orden de sus llamadas y de la apertura de la transacción
- **CUANDO** el trabajador ejecuta la tarea de emisión
- **ENTONCES** la comprobación de disponibilidad ocurre antes de que se abra la transacción de la emisión
- **Y** después se emite el token y se entrega el enlace una vez

#### Escenario: [I237] La tarea de emisión pasa la prueba de lista aprobada de campos

- **DADO** el tipo de tarea de emisión de recuperación
- **CUANDO** se ejecuta la prueba de lista aprobada de campos de `background-jobs`
- **ENTONCES** las claves de su carga son exactamente el identificador de la cuenta, y las de su envoltura son
  exactamente las de `meta` (correlación, solicitud, actor si existe, clase de actor, institución y `traceparent`
  si existe), y pasa
- **Y** la carga no tiene ningún componente de texto

### Requisito: Purga diaria de tokens de recuperación vencidos, 24 horas después del vencimiento

El sistema DEBE eliminar, una vez al día por institución configurada, los tokens de recuperación cuyo
vencimiento (`expires_at`) ocurrió hace **más de 24 horas**, sin importar si el token se usó, se superó o nunca
se presentó. NO DEBE eliminar ningún token vigente, ni uno vencido hace 24 horas o menos, ni uno de otra
institución. El borrado DEBE hacerse por la función de purga correspondiente (ver `build-integrity`), no por un
`DELETE` genérico.

#### Escenario: [I238] Un token vencido hace más de 24 horas se elimina

- **DADO** un token con vencimiento a las 10:30:00 del 2026-10-24, nunca usado
- **CUANDO** la purga corre a las 10:30:01 del 2026-10-25
- **ENTONCES** la fila ya no existe

#### Escenario: [I239] El borde de 24 horas conserva el token y un segundo después lo elimina

- **DADO** tres tokens con vencimiento a las 10:30:00 del 2026-10-24
- **CUANDO** la purga corre, en tres pruebas separadas, a las 10:29:59, a las 10:30:00 y a las 10:30:01 del
  2026-10-25
- **ENTONCES** en las dos primeras la fila se conserva y en la tercera se elimina

#### Escenario: [I240] Los tokens usados y superados también se eliminan al pasar la ventana

- **DADO** un token usado a las 10:05:00 y otro superado a las 10:10:00 del 2026-10-23, ambos vencidos desde
  hace más de 24 horas
- **CUANDO** corre la purga
- **ENTONCES** las dos filas se eliminan

#### Escenario: [I241] Un token vigente o recién vencido nunca se elimina

- **DADO** un token vivo, emitido hace 5 minutos, y otro vencido hace 23 horas
- **CUANDO** corre la purga
- **ENTONCES** ambas filas se conservan y el token vivo sigue aceptándose

#### Escenario: [I242] La purga de una institución no toca a la otra

- **DADO** dos instituciones con un token vencido hace más de 24 horas cada una, y el trabajador configurado
  solo con la primera
- **CUANDO** corre la purga
- **ENTONCES** se elimina el token de la primera y el de la segunda se conserva

#### Escenario: [I243] Sin contexto de institución la purga falla y no elimina ninguna fila

- **DADO** una fila de token vencida hace más de 24 horas y una sesión de base de datos sin `app.institution_id`
- **CUANDO** se invoca la función de purga de tokens de recuperación en esa sesión
- **ENTONCES** la función lanza el error `confia_purge_context_missing` y no elimina ninguna fila
- **Y** la prueba afirma el error y que la fila sigue existiendo, de modo que la ausencia de contexto no pasa por
  un éxito de cero filas

#### Escenario: [I270] Un actor que no es `system` no puede purgar tokens de recuperación

- **DADO** una fila de token vencida hace más de 24 horas y una sesión con el contexto de una institución pero con
  un actor de clase `staff`
- **CUANDO** se invoca la función de purga de tokens de recuperación en esa sesión
- **ENTONCES** la función lanza el error `confia_purge_actor_not_system` y no elimina ninguna fila

#### Escenario: [I271] Un corte en el futuro no elimina un token más reciente que el piso

- **DADO** un token vencido hace 23 horas, otro vencido hace 25 horas y la función invocada con un corte
  (`p_cutoff`) en el futuro
- **CUANDO** corre la función
- **ENTONCES** elimina el token de 25 horas y conserva el de 23 horas, porque el corte efectivo nunca es más
  reciente que 24 horas antes del reloj de la base de datos

### Requisito: Purga diaria de familias y tokens de refresco, por familia completa, 30 días después del fin de su vida

El sistema DEBE eliminar, una vez al día por institución configurada, las familias de refresco cuyo fin de vida
ocurrió hace **más de 30 días**, junto con todos sus tokens de refresco, eliminando primero los tokens y después
la familia en una sola operación atómica. El fin de vida de una familia es el primero entre su revocación y su
vencimiento absoluto. NO DEBE eliminar un token suelto de una familia dentro de la ventana, ni una familia
dentro de la ventana, ni una familia de otra institución. La eliminación de la familia elimina con ella la IP
inicial y la huella del agente de usuario que la familia conservaba. La operación NO DEBE violar las claves
foráneas entre las dos tablas.

#### Escenario: [I244] Una familia revocada hace más de 30 días se elimina con sus tokens

- **DADO** una familia revocada el 2026-09-20 a las 12:00:00 con tres tokens de refresco
- **CUANDO** la purga corre a las 12:00:01 del 2026-10-20
- **ENTONCES** la familia y sus tres tokens ya no existen
- **Y** la IP inicial y la huella del agente de usuario de la familia dejaron de existir en la base de datos

#### Escenario: [I245] El borde de 30 días conserva la familia y un segundo después la elimina

- **DADO** tres familias revocadas a las 12:00:00 del 2026-09-20
- **CUANDO** la purga corre, en tres pruebas separadas, a las 11:59:59, a las 12:00:00 y a las 12:00:01 del
  2026-10-20
- **ENTONCES** en las dos primeras la familia y sus tokens se conservan y en la tercera se eliminan

#### Escenario: [I246] Una familia vencida sin revocar se purga desde su vencimiento absoluto

- **DADO** una familia nunca revocada cuyo vencimiento absoluto fue el 2026-09-20 a las 12:00:00
- **CUANDO** la purga corre a las 12:00:01 del 2026-10-20 y, en otra prueba, a las 12:00:00 del mismo día
- **ENTONCES** en la primera se elimina con sus tokens y en la segunda se conserva

#### Escenario: [I247] Una familia viva o dentro de la ventana no pierde ningún token

- **DADO** una familia viva con un token consumido hace dos días y otra revocada hace 29 días
- **CUANDO** corre la purga
- **ENTONCES** ninguna de las dos familias ni ninguno de sus tokens se elimina, aunque el token consumido tenga
  más de un día

#### Escenario: [I248] Los tokens se eliminan antes que su familia sin violar claves foráneas

- **DADO** una familia purgable con tokens enlazados por sucesor entre sí
- **CUANDO** corre la purga
- **ENTONCES** termina sin error de clave foránea y no deja ningún token huérfano ni ninguna familia vacía

#### Escenario: [I249] Un fallo a mitad de la purga no deja una familia a medias

- **DADO** una purga que falla después de eliminar los tokens de una familia y antes de eliminar la familia
- **CUANDO** la transacción se revierte
- **ENTONCES** la familia y todos sus tokens siguen existiendo

#### Escenario: [I250] La purga de refresco no cruza instituciones

- **DADO** dos instituciones con una familia purgable cada una, y el trabajador configurado solo con la primera
- **CUANDO** corre la purga
- **ENTONCES** se eliminan la familia y los tokens de la primera, y los de la segunda se conservan

#### Escenario: [I251] Sin contexto de institución la purga de refresco falla y no elimina nada

- **DADO** una familia purgable y una sesión de base de datos sin contexto de institución
- **CUANDO** se invoca la función de purga
- **ENTONCES** lanza el error `confia_purge_context_missing` y no elimina ninguna fila de ninguna de las dos
  tablas, y la prueba afirma el error y que la familia y sus tokens siguen existiendo

#### Escenario: [I272] La inactividad de una familia no la hace purgable

- **DADO** una familia nunca revocada, con último uso hace 40 días y vencimiento absoluto en el futuro
- **CUANDO** corre la purga
- **ENTONCES** la familia y sus tokens se conservan, porque solo la revocación y el vencimiento absoluto fijan el
  fin de vida

#### Escenario: [I273] Una familia purgable se elimina completa aunque supere el tamaño del lote en tokens

- **DADO** una familia purgable con más tokens que el límite de familias del lote, junto con otras familias
  purgables
- **CUANDO** corre un lote con límite de familias menor que el número de familias purgables
- **ENTONCES** el lote elimina como máximo ese número de familias, cada una con todos sus tokens, y ninguna familia
  queda a medias

### Requisito: El sucesor cifrado residual se vacía por actualización cuando vence la ventana de gracia

El sistema DEBE vaciar, en la misma purga diaria y por institución configurada, el valor cifrado del sucesor
(`successor_token_ciphertext`) de todo token consumido hace **más de 10 segundos** (el consumo es estrictamente
anterior al instante actual menos la ventana de gracia), mediante una actualización de esa columna, sin eliminar
la fila, en su propia transacción y antes de las purgas por lotes de la misma tarea. No necesita función de
purga, porque no borra. NO DEBE vaciarlo antes de que transcurran 10 segundos desde el consumo, ni en el borde
exacto de 10 segundos, porque el reintento dentro de la ventana de gracia lo necesita. El vaciado NO DEBE cambiar la detección de reutilización:
presentar después de la gracia un token cuyo sucesor se vació sigue revocando la familia como reutilización. El
vaciado NO DEBE tocar tokens de otra institución.

#### Escenario: [I252] El sucesor residual se vacía una vez vencida la gracia

- **DADO** un token consumido a las 10:00:00 del 2026-10-25 cuya fila conserva su sucesor cifrado, sin contacto
  posterior con la familia
- **CUANDO** la purga corre a las 10:00:11
- **ENTONCES** la columna del sucesor cifrado queda vacía y la fila sigue existiendo, con su marca de consumo

#### Escenario: [I253] Dentro de la gracia y en el borde exacto el sucesor se conserva

- **DADO** el mismo token consumido a las 10:00:00
- **CUANDO** la purga corre, en pruebas separadas, a las 10:00:09 y a las 10:00:10
- **ENTONCES** en las dos el sucesor cifrado se conserva, y un reintento a las 10:00:09 desde el mismo
  dispositivo sigue recibiendo el mismo sucesor

#### Escenario: [I254] Vaciar el sucesor no desactiva la detección de reutilización

- **DADO** un token consumido cuyo sucesor se vació por la purga
- **CUANDO** se presenta de nuevo, pasados más de 10 segundos
- **ENTONCES** el desenlace es de reutilización fuera de la gracia y la familia se revoca con el motivo
  `reuse_detected`

#### Escenario: [I255] El vaciado de una institución no toca los tokens de otra

- **DADO** dos instituciones con un token consumido hace una hora y sucesor residual cada una, y el trabajador
  configurado solo con la primera
- **CUANDO** corre la purga
- **ENTONCES** el sucesor de la primera se vacía y el de la segunda se conserva

#### Escenario: [I256] Una segunda ejecución no cambia nada

- **DADO** la purga ejecutada una vez, que vació el sucesor residual
- **CUANDO** se ejecuta otra vez sin filas nuevas
- **ENTONCES** no cambia ninguna fila y termina sin error

### Requisito: Purga diaria de las tablas de retroceso, 24 horas después del último intento

El sistema DEBE eliminar, una vez al día por institución configurada, las filas de `identity_login_backoff` y de
`identity_mfa_totp_backoff` cuyo último intento (`last_attempt_at`) ocurrió hace **más de 24 horas**. NO DEBE
eliminar una fila con un último intento de 24 horas o menos, ni una de otra institución.

#### Escenario: [I257] Las filas de retroceso inactivas por más de 24 horas se eliminan

- **DADO** una fila de cada tabla con el último intento a las 09:00:00 del 2026-10-24
- **CUANDO** la purga corre a las 09:00:01 del 2026-10-25
- **ENTONCES** las dos filas ya no existen

#### Escenario: [I258] El borde de 24 horas conserva la fila y un segundo después la elimina

- **DADO** tres filas de cada tabla con el último intento a las 09:00:00 del 2026-10-24
- **CUANDO** la purga corre, en pruebas separadas, a las 08:59:59, a las 09:00:00 y a las 09:00:01 del
  2026-10-25
- **ENTONCES** en las dos primeras las filas se conservan y en la tercera se eliminan

#### Escenario: [I259] Una fila con un intento reciente nunca se elimina

- **DADO** una fila de `identity_login_backoff` con el último intento hace 10 minutos y una de
  `identity_mfa_totp_backoff` con el último intento hace 23 horas
- **CUANDO** corre la purga
- **ENTONCES** ambas se conservan y su contador de fallos no cambia

#### Escenario: [I260] La purga de retroceso no cruza instituciones

- **DADO** dos instituciones con una fila purgable por tabla, y el trabajador configurado solo con la primera
- **CUANDO** corre la purga
- **ENTONCES** se eliminan las filas de la primera y se conservan las de la segunda

#### Escenario: [I261] Sin contexto de institución la purga de retroceso falla y no elimina nada

- **DADO** filas purgables en las dos tablas y una sesión de base de datos sin contexto de institución
- **CUANDO** se invoca la función de purga de cada tabla
- **ENTONCES** cada una lanza el error `confia_purge_context_missing` y no elimina ninguna fila, y la prueba afirma
  el error y que las filas siguen existiendo

### Requisito: Las purgas de identidad corren a diario por institución configurada, son idempotentes y no usan `DELETE` genérico

El sistema DEBE registrar las cuatro purgas de identidad (tokens de recuperación, familias y tokens de refresco
con el vaciado del sucesor, y las dos tablas de retroceso) como tareas recurrentes diarias del trabajador, cada
una por institución configurada, con el actor `system`. Ninguna DEBE usar un `DELETE` genérico: cada borrado pasa
por su función de purga (ver `build-integrity`). Cada purga DEBE repetir lotes de 5 000 filas (de familias, en la de
refresco), cada lote en su propia transacción con su asiento de auditoría, hasta que un lote venga incompleto, con
un máximo de 200 lotes por institución y por ocurrencia; si agota los 200 lotes DEBE escribir la advertencia
`retention_purge_backlog` con la tarea y la institución y dejar el resto para el día siguiente. Ejecutar una purga
dos veces seguidas DEBE producir el mismo estado que ejecutarla una vez, y la segunda ejecución DEBE reportar cero
filas afectadas.

#### Escenario: [I262] Las cuatro purgas están registradas y corren por la institución configurada

- **DADO** el trabajador configurado con la institución `INS-1`
- **CUANDO** se enumeran las tareas recurrentes de identidad y vence el ciclo diario
- **ENTONCES** existen exactamente las cuatro purgas, cada una se ejecuta una vez con el contexto de `INS-1` y
  el actor `system`

#### Escenario: [I263] La doble ejecución de cada purga no cambia el estado ni falla

- **DADO** cada una de las cuatro purgas ejecutada una vez sobre filas purgables y vigentes mezcladas
- **CUANDO** se ejecuta cada una otra vez
- **ENTONCES** la segunda ejecución reporta cero filas afectadas, no cambia ninguna fila y no falla
- **Y** las filas vigentes siguen intactas

#### Escenario: [I264] Una purga con rol de aplicación no puede borrar por fuera de la función

- **DADO** el rol `confia_admin_app` con el contexto de una institución
- **CUANDO** intenta un `DELETE` directo sobre cualquiera de las cinco tablas purgadas
- **ENTONCES** el motor lo rechaza por falta de privilegio y solo la función de purga puede borrar

#### Escenario: [I274] Una purga repite lotes hasta vaciar lo purgable

- **DADO** 12 000 tokens de recuperación vencidos hace más de 24 horas de la institución configurada
- **CUANDO** corre la purga
- **ENTONCES** ejecuta tres lotes (5 000, 5 000 y 2 000), cada uno en su transacción, y se detiene porque el tercero
  viene incompleto
- **Y** no queda ninguna fila purgable y no se escribe la advertencia `retention_purge_backlog`

#### Escenario: [I275] Agotar los 200 lotes deja el resto para el día siguiente

- **DADO** más de 1 000 000 de filas purgables de una institución
- **CUANDO** corre la purga
- **ENTONCES** ejecuta exactamente 200 lotes y escribe la advertencia `retention_purge_backlog` con la tarea y la
  institución
- **Y** la ocurrencia del día siguiente continúa con lo que quedó

#### Escenario: [I276] Los lotes pasan por la función con el corte calculado por el llamador

- **DADO** la purga de tokens de recuperación, con el reloj fijado en las 10:30:01 del 2026-10-25
- **CUANDO** corre un lote
- **ENTONCES** invoca la función con el corte de las 10:30:01 menos 24 horas y con el límite de 5 000
- **Y** una modificación que adelante el corte (por ejemplo, 23 horas) hace fallar la prueba de borde de [I239]

## REMOVED Requirements

### Requisito: Ausencia de programación real de la emisión (brecha con destino: cambio 9)

(Reason: el cambio 9 entrega el adaptador de producción del puerto de programación de la emisión y su manejador;
la comprobación de que ninguna clase implementa el puerto fallaría desde este cambio.)
(Migration: `IdentityScopeExclusionInventoryTest` retira la prueba de ausencia del adaptador y la sustituye por
las afirmaciones positivas de los requisitos «La solicitud de recuperación programa la emisión con un adaptador
de producción, dentro de su transacción y sin datos personales» y «La tarea de emisión revalida contra la base de
datos y emite el token como `system` en la institución de la tarea». El escenario «Una solicitud no produce un
token por sí sola» se conserva en I232.)

### Requisito: Ausencia de purga de tokens vencidos (brecha con destino: cambio 9)

(Reason: el cambio 9 decide e implementa la purga; el propietario fijó el 2026-10-24 (P3) la purga diaria a las
24 horas del vencimiento. Los escenarios «Un token vencido sigue almacenado» y «Los tokens usados y superados
también se conservan» dejan de ser ciertos.)
(Migration: «Purga diaria de tokens de recuperación vencidos, 24 horas después del vencimiento». Los privilegios
siguen sin incluir `DELETE`; ver `build-integrity`.)

### Requisito: Ausencia de purga de familias y tokens de refresco (brecha con destino: cambio 9)

(Reason: el cambio 9 entrega la purga de las dos tablas y el vaciado del sucesor cifrado residual; los
escenarios «Las filas vencidas y revocadas se conservan» y «El sucesor cifrado residual se conserva hasta la
purga» dejan de ser ciertos.)
(Migration: «Purga diaria de familias y tokens de refresco, por familia completa, 30 días después del fin de su
vida» y «El sucesor cifrado residual se vacía por actualización cuando vence la ventana de gracia». Los
privilegios siguen sin incluir `DELETE`; ver `build-integrity`.)

## RENAMED Requirements

### Requisito: Ausencia de ejecución real de la rotación de la llave de datos (brecha con destino: cambio 9) → Ausencia de ejecución real de la rotación de la llave de datos (brecha con destino: `data-encryption-key-batch-rotation`)

(Reason: por decisión P1 del propietario del 2026-10-24, la rotación por lotes de las DEK `retired` sale del cambio
9 a un cambio nuevo, porque depende de una superficie de rotación que hoy no existe.)
(Migration: la prueba `noScheduledJobReencryptsARetiredDataEncryptionKeyYet` de `IdentityScopeExclusionInventoryTest`
se conserva, y su dueño pasa a `data-encryption-key-batch-rotation`; la nota fechada del plan de fundaciones
registra el seguimiento.)

## MODIFIED Requirements

### Requisito: Ausencia de ejecución real de la rotación de la llave de datos (brecha con destino: `data-encryption-key-batch-rotation`)

El sistema NO DEBE ejecutar, en el cambio 9, ningún recifrado por lotes de valores cifrados con una llave de
datos (DEK) marcada `retired`. Aunque `db-scheduler` (ADR-0016) llega con el cambio 9
(`background-jobs-with-db-scheduler`), el recifrado programado en lotes depende además de una superficie de
rotación de llaves que hoy no existe, por lo que su dueño es el cambio nuevo
**`data-encryption-key-batch-rotation`** (decisión P1 del propietario del 2026-10-24; depende del cambio 9). Este
cambio entrega el esquema que hace posible la rotación —la columna de estado `active`/`retired` de la tabla de
llaves de datos, usada desde el primer día para decidir con qué llave se cifra cada valor nuevo— pero no la
ejecución de la rotación.
(Previously: el destino de la brecha era el cambio 9, que habría entregado el trabajo de rotación.)

#### Escenario: Ninguna DEK retirada se recifra automáticamente

- **DADO** una llave de datos (DEK) marcada `retired`, con al menos un valor cifrado todavía
  referenciándola
- **CUANDO** se inspecciona si algún trabajo programado la recifra con la DEK activa
- **ENTONCES** no existe ningún trabajo de ese tipo desplegado en este cambio, y el registro de tipos de tarea
  del trabajador no contiene ninguna tarea de recifrado
- **Y** el valor cifrado sigue descifrándose correctamente con la DEK `retired`, porque el estado
  `retired` no impide leer, solo impide cifrar valores nuevos con ella
- **Y** este escenario deja de ser cierto el día que `data-encryption-key-batch-rotation` entregue el trabajo de
  rotación

### Requisito: Ausencia de envío real del enlace y de la notificación al titular (brecha con destino: `transactional-email-adapter`, cambio 14 de F0)

El sistema NO DEBE enviar, en este cambio, ningún correo con el enlace de recuperación ni ninguna
notificación al titular tras un restablecimiento (`docs/03-seguridad.md` §4.7, «Efecto»; ADR-0005,
«Recuperación de contraseña», punto 5). La única implementación de producción del puerto de envío que usa la
emisión DEBE ser un emisor que **falla de forma explícita** (decisión P4 del propietario del 2026-10-24): al
invocarse lanza un fallo que no contiene el token, el enlace ni el correo. El fallo ocurre en la comprobación de
disponibilidad (`ensureAvailable`) que la emisión hace **antes** de emitir cualquier token (y también en el envío,
por si se invoca directamente), de modo que cada intento falla sin emitir, la tarea de emisión agota sus 5
reintentos, queda estacionada y visible, y no se deja ningún token huérfano ni se oculta el hueco; con un emisor
que fallara solo en el envío, quedaría un token emitido y no entregado por intento y, al cuarto intento de la hora,
la tarea terminaría completada. NO DEBE existir
ningún adaptador que entregue o simule entregar. El destino de la entrega real es **`transactional-email-adapter`**,
cambio 14 de F0, posterior al cambio 9, que sustituirá a ese emisor. La IP de la notificación la aporta además
`session-endpoints-cookie-and-csrf`, con la propagación del origen de la petición a los comandos de identidad.
(Previously: el puerto de envío NO DEBÍA tener adaptador de producción alguno; ahora tiene uno, el emisor que
falla de forma explícita.)

#### Escenario: El puerto de envío solo tiene el emisor que falla de forma explícita

- **DADO** el árbol de clases de producción del módulo de identidad
- **CUANDO** se inspecciona qué clases implementan el puerto de envío del enlace
- **ENTONCES** exactamente una lo hace, el emisor que falla de forma explícita, y ninguna entrega un correo
- **Y** esta comprobación falla el día que exista un segundo adaptador, o uno que entregue, sin retirarla

#### Escenario: Un restablecimiento completado no notifica a nadie

- **DADO** un restablecimiento completado para `ana.martinez@colegio.edu.hn`
- **CUANDO** se inspeccionan los asientos de auditoría que produjo
- **ENTONCES** ninguno nombra una notificación, un correo ni un intento de entrega

#### Escenario: [I265] La tarea de emisión con el emisor que falla se reintenta de forma acotada y queda fallida

- **DADO** la tarea de emisión de `ACC-7` ejecutada con el emisor que falla de forma explícita y un máximo de `N`
  intentos para ese tipo
- **CUANDO** el trabajador la ejecuta hasta agotarla
- **ENTONCES** hubo exactamente `N` (5) intentos con espera creciente (30 s, 60 s, 120 s y 240 s)
- **Y** la fila de la tarea sigue en `scheduled_tasks` con `consecutive_failures` igual a `N`, `last_failure`
  registrado y su ejecución estacionada en `9999-01-01T00:00:00Z`, visible para la métrica de tareas fallidas

#### Escenario: [I266] Los intentos fallidos no dejan tokens huérfanos ni superan el token vivo

- **DADO** la cuenta `ana.martinez@colegio.edu.hn` con un token vivo `PRT-1001`, y la tarea de emisión del
  escenario anterior
- **CUANDO** se agotan sus intentos
- **ENTONCES** no existe ningún token nuevo para la cuenta, `PRT-1001` sigue vivo y no está superado
- **Y** los intentos fallidos no cuentan para el límite de tres emisiones por hora

#### Escenario: [I277] El emisor que falla impide emitir antes de abrir la transacción, en cada intento

- **DADO** la tarea de emisión de `ACC-7` con el emisor que falla de forma explícita
- **CUANDO** el trabajador ejecuta cada uno de los 5 intentos
- **ENTONCES** en cada intento la comprobación de disponibilidad lanza el fallo antes de abrirse la transacción
  de la emisión, y no se escribe ningún asiento de emisión ni de emisión omitida
- **Y** el cuarto y el quinto intento dentro de la hora fallan igual que los primeros: ninguno termina como
  omitido por límite ni como completado

#### Escenario: [I267] El fallo del emisor no revela el token, el enlace ni el correo

- **DADO** la tarea de emisión que falla por el emisor explícito
- **CUANDO** se inspeccionan los registros, la excepción y la fila de `scheduled_tasks`
- **ENTONCES** ninguno contiene el token en claro, el enlace ni el correo de la cuenta

### Requisito: La ventana de gracia de 10 segundos devuelve el sucesor cifrado al mismo dispositivo

Cuando un token ya consumido se presenta de nuevo estrictamente antes de que transcurran 10 segundos
desde su consumo, desde el mismo dispositivo (la misma huella de agente de usuario que presentó el
consumo del token, no la del inicio de sesión: una actualización del navegador durante la sesión no
convierte un reintento legítimo en reutilización), con la familia viva y sin que la familia haya
avanzado, el sistema DEBE devolver el mismo token de refresco sucesor que se emitió, junto con un token
de acceso recién emitido (otro `jti` e `iat`, y los mismos `sid`, `sub`, `tenant` y `amr`), sin revocar la
familia y avanzando `last_used_at` al instante del reintento (ADR-0005, punto 4 y verificación 2; decisión
D-N1 del propietario, que precisa «el mismo par» en ese sentido). El token de acceso del primer intento no
se conserva. Como solo se conserva el SHA-256 de cada token, el sistema DEBE guardar el sucesor cifrado
con el servicio de cifrado de columna (los datos adicionales autenticados ligan el valor a su propia
fila) y NO DEBE descifrarlo a los 10 segundos ni después, aunque el valor siga en la base de datos
(decisión 6 del propietario). El sucesor cifrado DEBE vaciarse en el siguiente uso de la familia: en la
rotación de cualquiera de sus tokens (los consumidos hace 10 segundos o más), en toda revocación y en el
rechazo por familia vencida; el resto lo vacía la purga diaria de este cambio (cambio 9, ver «El sucesor
cifrado residual se vacía por actualización cuando vence la ventana de gracia»). La familia «ha avanzado» cuando
el sucesor ya fue consumido. Un agente de usuario ausente en ambas presentaciones se trata como el mismo
dispositivo.
(Previously: «la purga del resto es del cambio 9», una remisión pendiente; ahora la entrega la purga diaria del
sucesor residual de este mismo cambio.)

#### Escenario: [I96] Reintento dentro de la gracia desde el mismo dispositivo (ADR-0005, prueba 2)

- **DADO** el token `RT-5001` rotado a las 10:00:00 del 2026-10-12 por una petición con el agente
  `agente-prueba`, que produjo el sucesor `RT-5002`
- **CUANDO** `RT-5001` se presenta de nuevo a las 10:00:09 con el agente `agente-prueba`
- **ENTONCES** el desenlace es `GraceReplayed` con el mismo valor de `RT-5002` entregado antes
- **Y** la familia no se revoca, ningún token de refresco adicional se emite y `last_used_at` de la
  familia es las 10:00:09

#### Escenario: [I141] La gracia devuelve el mismo refresco sucesor y un token de acceso nuevo (decisión D-N1)

- **DADO** el estado del escenario anterior, con el token de acceso `AT-1` entregado en la rotación de
  las 10:00:00
- **CUANDO** `RT-5001` se presenta a las 10:00:09 con el agente `agente-prueba`
- **ENTONCES** el refresco devuelto es idéntico al `RT-5002` entregado antes
- **Y** el token de acceso devuelto es distinto de `AT-1`, con otro `jti` y otro `iat`, y con los mismos
  `sid`, `sub`, `tenant` y `amr`
- **Y** el token de acceso devuelto se verifica como válido y su `exp - iat` es 600 segundos

#### Escenario: [I97] El borde de 10 segundos es estricto

- **DADO** el mismo estado del escenario anterior
- **CUANDO** `RT-5001` se presenta a las 10:00:09,999 y, en otra ejecución, exactamente a las 10:00:10
- **ENTONCES** la primera es `GraceReplayed` y la segunda es `ReuseDetected`

#### Escenario: [I98] Otro dispositivo dentro de la ventana es reutilización

- **DADO** el mismo estado, con una huella de agente distinta en la presentación
- **CUANDO** `RT-5001` se presenta a las 10:00:05
- **ENTONCES** el desenlace es `ReuseDetected` y la familia se revoca

#### Escenario: [I99] El sucesor se guarda cifrado y ligado a su fila

- **DADO** una rotación confirmada
- **CUANDO** se consulta con SQL crudo el valor del sucesor del token consumido
- **ENTONCES** comienza con el prefijo `v1:` y no contiene el sucesor en claro
- **Y** descifrarlo con los datos adicionales autenticados de otra fila falla por fallo de autenticación

#### Escenario: [I100] Pasados los 10 segundos el valor sigue en la base y no se descifra

- **DADO** el mismo estado de `RT-5001`, sin ningún contacto posterior con la familia
- **CUANDO** se presenta a las 10:00:11 y se consulta la fila
- **ENTONCES** el desenlace es `ReuseDetected` sin que el sucesor se haya descifrado
- **Y** el valor cifrado siguió en la fila hasta ese contacto o hasta la purga diaria del sucesor residual de este
  cambio, lo que ocurra primero

#### Escenario: [I101] El sucesor cifrado se vacía en el siguiente uso de la familia

- **DADO** el token `RT-5001` consumido a las 10:00:00 con su sucesor cifrado y `RT-5002` vigente
- **CUANDO** `RT-5002` se rota a las 10:00:30, y en otra ejecución la familia se revoca a las 10:00:30
- **ENTONCES** en ambos casos el valor cifrado del sucesor de `RT-5001` queda vacío

#### Escenario: [I102] Si la familia ya avanzó no hay reintento en gracia

- **DADO** `RT-5001` rotado a las 10:00:00 y su sucesor `RT-5002` rotado a las 10:00:03
- **CUANDO** `RT-5001` se presenta a las 10:00:06 con el mismo agente
- **ENTONCES** el desenlace es `ReuseDetected`, porque el sucesor ya fue consumido y no hay valor que
  devolver

#### Escenario: [I103] Sin agente de usuario en ambas presentaciones

- **DADO** un token rotado sin agente de usuario (la huella registrada en su consumo es la de «sin
  agente»)
- **CUANDO** el token consumido se presenta de nuevo dentro de la ventana, también sin agente
- **ENTONCES** el desenlace es `GraceReplayed`

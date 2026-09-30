# Delta para Identidad

- **Estado:** aprobado por el propietario del producto el 2026-09-30

> Cambio `password-recovery-token`, tercera de las cuatro partes del cambio 7 de F0. Propuesta
> aprobada por el propietario el 2026-09-30. En los escenarios, las etiquetas `PRT-...` nombran
> tokens concretos para poder seguirlos; no describen su formato, que es de 32 bytes aleatorios
> codificados en base64url. Salvo que un escenario diga otra cosa, todas las cuentas pertenecen a la
> institución configurada en el proceso.

## ADDED Requirements

### Requisito: La solicitud de recuperación solo programa la emisión del token

Al recibir una solicitud de recuperación para un identificador que corresponde a una cuenta de
personal, el sistema DEBE programar la emisión de un token para esa cuenta, identificada por su
identificador interno y nunca por el correo, y NO DEBE generar, almacenar ni devolver ningún token
durante la propia solicitud. Para un identificador que no corresponde a ninguna cuenta, el sistema
NO DEBE programar nada. La solicitud NO DEBE bloquear la cuenta, alterar su contraseña vigente ni
invalidar el token vivo que ya tuviera.

#### Escenario: Una solicitud para una cuenta existente programa una emisión y no crea ningún token

- **DADO** la cuenta `ana.martinez@colegio.edu.hn`, sin ningún token de recuperación
- **CUANDO** se solicita recuperación para ese correo a las 10:00:00 del 2026-10-12
- **ENTONCES** queda programada exactamente una emisión, que identifica la cuenta por su
  identificador interno y no contiene el correo
- **Y** no existe todavía ningún token de recuperación para esa cuenta
- **Y** el resultado de la solicitud no contiene ningún token

#### Escenario: La solicitud no bloquea la cuenta ni cambia su contraseña

- **DADO** la cuenta `ana.martinez@colegio.edu.hn`, con la contraseña vigente `Cafetal de Copán 2026`
- **CUANDO** se solicita recuperación para ese correo a las 10:00:00 del 2026-10-12
- **Y** a las 10:01:00 del mismo día la titular inicia sesión con `Cafetal de Copán 2026`
- **ENTONCES** el inicio de sesión tiene éxito, porque la solicitud no bloqueó la cuenta ni alteró su
  contraseña

### Requisito: El token se almacena únicamente como su SHA-256

Al emitir un token, el sistema DEBE generar 32 bytes con un generador criptográficamente seguro,
codificarlos en base64url sin relleno y almacenar solamente el SHA-256 del token, representado en
hexadecimal de 64 caracteres. El sistema NO DEBE almacenar el token en claro en ninguna tabla, y
DEBE entregarlo únicamente al puerto de envío. Dos emisiones NO DEBEN producir el mismo token.

#### Escenario: La base solo contiene el SHA-256 del token entregado

- **DADO** la emisión del token `PRT-11c4` para `ana.martinez@colegio.edu.hn` a las 10:00:30 del
  2026-10-12
- **CUANDO** se consulta con SQL crudo la tabla de tokens de recuperación
- **ENTONCES** la fila de ese token contiene un valor hexadecimal de 64 caracteres, igual al SHA-256
  del token que recibió el puerto de envío
- **Y** ninguna columna de ninguna fila contiene el token en claro

#### Escenario: El token entregado tiene 32 bytes y no se repite

- **DADO** dos emisiones sucesivas para `ana.martinez@colegio.edu.hn`, a las 10:00:30 y a las 10:05:30
  del 2026-10-12
- **CUANDO** se examinan los dos tokens que recibió el puerto de envío
- **ENTONCES** cada uno es una cadena base64url sin relleno de 43 caracteres, que decodifica a
  exactamente 32 bytes
- **Y** los dos tokens son distintos

### Requisito: Límite de tres emisiones por hora por cuenta

El sistema NO DEBE emitir un token para una cuenta que ya tiene tres tokens emitidos en los sesenta
minutos anteriores al instante de la emisión, sin importar si esos tokens se usaron, vencieron o
fueron superados. Un token emitido exactamente sesenta minutos antes ya no cuenta. Una emisión omitida por este límite NO DEBE entregar nada al puerto de envío, NO
DEBE superar el token vivo de la cuenta y DEBE quedar registrada en la bitácora de auditoría. El
límite NO DEBE cambiar el resultado de la solicitud de recuperación que la originó.

#### Escenario: La cuarta emisión dentro de la hora se omite

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn` con tokens emitidos a las 10:00:00, 10:10:00 y
  10:20:00 del 2026-10-12
- **CUANDO** se procesa una cuarta emisión para esa cuenta a las 10:30:00 del mismo día
- **ENTONCES** el sistema no emite ningún token y el puerto de envío no recibe ninguna llamada
- **Y** el token emitido a las 10:20:00 sigue vivo
- **Y** la bitácora de auditoría registra la emisión omitida por límite
- **Y** la solicitud de recuperación que originó esa emisión obtuvo el mismo resultado que cualquier
  otra solicitud

#### Escenario: La ventana es de sesenta minutos móviles

- **DADO** la misma cuenta `carlos.ramirez@colegio.edu.hn` con tokens emitidos a las 10:00:00,
  10:10:00 y 10:20:00 del 2026-10-12
- **CUANDO** se procesa una emisión para esa cuenta a las 11:00:01 del mismo día
- **ENTONCES** el sistema emite el token, porque el emitido a las 10:00:00 ya quedó fuera de los
  sesenta minutos anteriores y solo cuentan dos

### Requisito: A lo sumo un token vivo por cuenta, también bajo concurrencia

El sistema DEBE garantizar que una cuenta tenga en todo momento a lo sumo un token vivo, es decir,
sin usar, sin superar y dentro de su vigencia. Dos emisiones concurrentes para la misma cuenta NO
DEBEN dejar dos tokens vivos. Dos restablecimientos concurrentes con el mismo token DEBEN producir
exactamente un cambio de contraseña, y el otro DEBE rechazarse.

#### Escenario: Dos emisiones concurrentes dejan exactamente un token vivo

- **DADO** la cuenta `ana.martinez@colegio.edu.hn`, sin ningún token de recuperación
- **CUANDO** dos emisiones para esa cuenta se ejecutan de forma concurrente y sincronizada a las
  10:00:00 del 2026-10-12
- **ENTONCES** al terminar ambas existe exactamente un token vivo para esa cuenta
- **Y** el otro token, si llegó a emitirse, está superado

#### Escenario: Dos restablecimientos concurrentes con el mismo token producen un solo cambio

- **DADO** el token vivo `PRT-22d7` de `ana.martinez@colegio.edu.hn`, emitido a las 10:00:00 del
  2026-10-12
- **CUANDO** a las 10:05:00 dos restablecimientos concurrentes y sincronizados presentan `PRT-22d7`,
  uno con la contraseña `Lempira y maíz 2026` y otro con `Río Ulúa crecido`
- **ENTONCES** exactamente uno de los dos se acepta y el otro se rechaza
- **Y** la contraseña vigente de la cuenta es la del restablecimiento aceptado, y solo esa autentica
- **Y** la bitácora registra exactamente un restablecimiento completado

### Requisito: Segundo factor exigido en el restablecimiento cuando la cuenta tiene MFA activa

Una cuenta tiene MFA activa cuando tiene `mfa_required` en `true` **y** un secreto TOTP inscrito,
que es la misma condición con la que el inicio de sesión devuelve `SecondFactorRequired`. Para una
cuenta con MFA activa, el sistema NO DEBE aceptar un restablecimiento que no presente, además del
token, un código TOTP válido o un código de recuperación de MFA sin usar (`docs/03-seguridad.md`
§4.7). El código de recuperación de MFA aceptado DEBE quedar consumido. Un restablecimiento
rechazado por falta de segundo factor NO DEBE consumir el token ni cambiar la contraseña.

#### Escenario: Sin segundo factor no hay restablecimiento, y con un código TOTP válido sí

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn`, con `mfa_required` en `true`, un secreto TOTP
  inscrito y el token vivo `PRT-33e1` emitido a las 09:00:00 del 2026-10-12
- **CUANDO** a las 09:05:00 presenta `PRT-33e1` con la contraseña nueva `Montaña de Celaque 2026` y
  sin ningún código
- **ENTONCES** el sistema rechaza el restablecimiento indicando que falta el segundo factor
- **Y** la contraseña vigente no cambia y `PRT-33e1` sigue vivo
- **Y** cuando a las 09:06:00 presenta `PRT-33e1`, la misma contraseña nueva y un código TOTP válido
  para ese instante, el sistema acepta el restablecimiento

#### Escenario: Un código de recuperación de MFA sustituye al código TOTP y queda consumido

- **DADO** la misma cuenta `sofia.mejia@colegio.edu.hn`, con diez códigos de recuperación de MFA sin
  usar y el token vivo `PRT-33e2` emitido a las 11:00:00 del 2026-10-12
- **CUANDO** a las 11:04:00 presenta `PRT-33e2`, la contraseña nueva `Montaña de Celaque 2026` y uno
  de sus códigos de recuperación
- **ENTONCES** el sistema acepta el restablecimiento
- **Y** ese código de recuperación queda usado y la cuenta conserva nueve sin usar
- **Y** presentar el mismo código en un restablecimiento posterior no cuenta como segundo factor

### Requisito: Una cuenta sin MFA activa restablece solo con el enlace

Para una cuenta sin MFA activa, el sistema DEBE aceptar el restablecimiento con el token y la
contraseña nueva, sin pedir segundo factor. Esto incluye una cuenta con `mfa_required` en `true` sin
secreto inscrito: en su siguiente inicio de sesión con la contraseña nueva, el sistema DEBE devolver
`SecondFactorEnrollmentRequired`, de modo que la inscripción se sigue exigiendo. También incluye una
cuenta con `mfa_required` en `false` que tenga un secreto inscrito, igual que en el inicio de sesión
(respuestas del propietario del 2026-09-30).

#### Escenario: `mfa_required` en `true` sin secreto restablece solo con el enlace, y la inscripción sigue exigida

- **DADO** la cuenta `jorge.aguilar@colegio.edu.hn`, con `mfa_required` en `true`, sin secreto TOTP
  inscrito y con el token vivo `PRT-44f0` emitido a las 08:00:00 del 2026-10-13
- **CUANDO** a las 08:10:00 presenta `PRT-44f0` y la contraseña nueva `Semana Morazánica 2026`, sin
  ningún código
- **ENTONCES** el sistema acepta el restablecimiento
- **Y** cuando a las 08:12:00 inicia sesión con `Semana Morazánica 2026`, el sistema devuelve
  `SecondFactorEnrollmentRequired` y no `Authenticated`

#### Escenario: `mfa_required` en `false` con secreto inscrito no presenta segundo factor

- **DADO** la cuenta `maria.lopez@colegio.edu.hn`, con `mfa_required` en `false`, un secreto TOTP
  inscrito y el token vivo `PRT-45a2` emitido a las 08:00:00 del 2026-10-13
- **CUANDO** a las 08:03:00 presenta `PRT-45a2` y la contraseña nueva `Feria Juniana de San Pedro`,
  sin ningún código
- **ENTONCES** el sistema acepta el restablecimiento
- **Y** no se registra ninguna verificación TOTP para esa cuenta en la bitácora de auditoría

### Requisito: Un segundo factor incorrecto en el restablecimiento avanza el mismo retroceso TOTP que el inicio de sesión

Un código TOTP incorrecto presentado en un restablecimiento DEBE contar como un fallo de
verificación TOTP de la cuenta, con **el mismo contador y la misma regla de retroceso** que la
verificación TOTP posterior al inicio de sesión: sin retardo en los dos primeros fallos
consecutivos; desde el tercero, 2^(n−3) segundos para el fallo n, con tope de 900 segundos; y el
contador se reinicia tras más de 30 minutos sin intentos. El avance del contador y su asiento de
auditoría DEBEN confirmarse aunque el restablecimiento se rechace, y ese rechazo NO DEBE consumir el
token. El retardo demora la respuesta, nunca la deniega: el caso de uso devuelve el retardo exigible
y el llamador DEBE respetarlo antes de responder.

#### Escenario: Los fallos de inicio de sesión y de restablecimiento suman en el mismo contador

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn`, con MFA activa, sin fallos TOTP previos y con el
  token vivo `PRT-55b3` emitido a las 09:00:00 del 2026-10-14
- **CUANDO** presenta códigos TOTP incorrectos en la verificación posterior al inicio de sesión a las
  09:01:00 y a las 09:02:00
- **Y** presenta un código TOTP incorrecto con `PRT-55b3` en un restablecimiento a las 09:03:00
- **ENTONCES** el restablecimiento se rechaza con un retardo exigible de 1 segundo, que corresponde al
  tercer fallo consecutivo y que sería 0 si el restablecimiento tuviera un contador propio
- **Y** un código incorrecto en la verificación posterior al inicio de sesión a las 09:04:00 lleva un
  retardo de 2 segundos, que corresponde al cuarto fallo
- **Y** un código incorrecto en un restablecimiento a las 09:35:00, treinta y un minutos después del
  anterior, empieza un ciclo nuevo sin retardo

#### Escenario: El fallo queda confirmado y el token sigue sirviendo

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn`, con MFA activa, sin fallos TOTP previos y con el
  token vivo `PRT-55b4` emitido a las 14:00:00 del 2026-10-14
- **CUANDO** a las 14:02:00 presenta `PRT-55b4`, la contraseña nueva `Montaña de Celaque 2026` y un
  código TOTP incorrecto
- **ENTONCES** el restablecimiento se rechaza y la contraseña vigente no cambia
- **Y** después de ese rechazo, el contador de fallos TOTP de la cuenta vale uno y la bitácora
  contiene el asiento de la verificación fallida, es decir, ninguno de los dos se revirtió con el
  rechazo
- **Y** a las 14:03:00 presenta `PRT-55b4`, la misma contraseña nueva y un código TOTP válido, y el
  sistema acepta el restablecimiento

### Requisito: La contraseña nueva tiene entre 12 y 128 caracteres

El sistema DEBE aceptar como contraseña nueva de una cuenta de personal solo un valor que, tras la
normalización NFKC, tenga entre 12 y 128 caracteres Unicode, ambos inclusive y contados como puntos
de código (`docs/03-seguridad.md` §4.2). El sistema NO DEBE exigir ninguna regla de composición. Un
rechazo por longitud DEBE ser observable para quien restablece, y NO DEBE consumir el token ni
cambiar la contraseña vigente.

#### Escenario: Los bordes de 12 y 128 caracteres

- **DADO** cuatro cuentas de personal, cada una con un token vivo emitido a las 15:00:00 del
  2026-10-15
- **CUANDO** a las 15:05:00 cada una restablece con una contraseña nueva de 11, 12, 128 y 129
  caracteres respectivamente, formadas solo por letras minúsculas
- **ENTONCES** se rechazan la de 11 y la de 129, cada una por longitud, y sus tokens siguen vivos
- **Y** se aceptan la de 12 y la de 128, sin que se les exija mayúscula, número ni símbolo

#### Escenario: La longitud se cuenta en caracteres, no en unidades de codificación

- **DADO** dos cuentas de personal, cada una con un token vivo emitido a las 15:00:00 del 2026-10-15
- **CUANDO** a las 15:05:00 una restablece con `casa azul🌋🌊`, que tiene 11 caracteres y ocupa 13
  unidades UTF-16, y la otra con `casa azul 🌋🌊`, que tiene 12 caracteres y ocupa 14 unidades UTF-16
- **ENTONCES** la primera se rechaza por longitud y la segunda se acepta

### Requisito: La institución del restablecimiento proviene de la configuración del proceso

La institución en la que el sistema busca el token presentado, y contra la que la política de
seguridad a nivel de fila evalúa esa búsqueda, DEBE provenir de la configuración del proceso del
servidor, igual que en el inicio de sesión. NO DEBE provenir del cuerpo de la solicitud, de una
cabecera ni de un parámetro de consulta, porque el restablecimiento ocurre sin sesión.

#### Escenario: Se ignora la institución declarada por quien restablece

- **DADO** un proceso configurado con la institución `d290f1ee-6c54-4b01-90e6-d701748f0851` y el
  token vivo `PRT-66c5` de `ana.martinez@colegio.edu.hn` en esa institución
- **CUANDO** se restablece con `PRT-66c5` declarando en el cuerpo la institución
  `5a6a5e02-1c2e-4e3a-9d3f-6b2b3a1e9f10`
- **ENTONCES** el sistema busca el token en `d290f1ee-6c54-4b01-90e6-d701748f0851`, lo encuentra y
  acepta el restablecimiento

#### Escenario: Un token de otra institución no se encuentra

- **DADO** un proceso configurado con la institución `5a6a5e02-1c2e-4e3a-9d3f-6b2b3a1e9f10`
- **Y** el token vivo `PRT-66c6`, emitido para una cuenta de la institución
  `d290f1ee-6c54-4b01-90e6-d701748f0851`
- **CUANDO** se restablece con `PRT-66c6` en ese proceso
- **ENTONCES** el sistema rechaza el token con el mismo resultado que da a un token inexistente, y
  `PRT-66c6` sigue vivo en su propia institución

### Requisito: Auditoría del ciclo de recuperación, atómica con su efecto

El sistema DEBE registrar en la bitácora de auditoría, en la misma transacción que su efecto: cada
solicitud, cada emisión, cada emisión omitida por límite, cada restablecimiento completado, que es a
la vez el cambio de contraseña de `docs/03-seguridad.md` §12.2, y cada restablecimiento rechazado con
su motivo. Ante un token no aceptado, el motivo —inexistente, vencido, superado o ya usado— DEBE
quedar solo en la bitácora, y el resultado DEBE ser el mismo para los cuatro. Si la transacción no
confirma, ni el efecto ni su asiento DEBEN sobrevivir.

#### Escenario: Los cuatro motivos de token no aceptado dan el mismo resultado y quedan auditados por separado

- **DADO** cuatro restablecimientos a las 16:00:00 del 2026-10-15, con un token que nunca se emitió,
  uno emitido a las 15:00:00, uno superado a las 15:50:00 y uno ya usado a las 15:55:00
- **CUANDO** el sistema procesa cada uno
- **ENTONCES** los cuatro resultados son idénticos para quien restablece
- **Y** la bitácora registra cada rechazo con su motivo propio: inexistente, vencido, superado y ya
  usado

#### Escenario: Un restablecimiento que no confirma no deja consumo, ni cambio, ni asiento

- **DADO** el token vivo `PRT-77d8` de `ana.martinez@colegio.edu.hn`
- **CUANDO** su restablecimiento se procesa dentro de una transacción que falla de forma determinista
  después de consumir el token y escribir el hash nuevo, antes de confirmar
- **ENTONCES** tras la reversión, `PRT-77d8` sigue vivo y la contraseña vigente es la anterior
- **Y** no existe en la bitácora ningún asiento de restablecimiento para ese intento

### Requisito: Ningún secreto del ciclo de recuperación es observable en registros, excepciones, `toString()` ni pruebas

El sistema NO DEBE hacer observable, en ningún mensaje de registro, mensaje de excepción,
representación textual (`toString()` o equivalente, incluida la que genera automáticamente un
`record` de Java) ni salida de una prueba fallida, el token en claro, su SHA-256, la contraseña nueva
en claro ni su hash Argon2id. Todo objeto de valor que cargue el token en claro o la contraseña nueva
DEBE ser una clase final con un `toString()` sobrescrito de forma explícita.

#### Escenario: Ni el token ni la contraseña nueva aparecen en ningún texto producido

- **DADO** una emisión para `ana.martinez@colegio.edu.hn` y un restablecimiento con ese token y la
  contraseña nueva `Lempira y maíz 2026`
- **CUANDO** el sistema procesa ambos y, en cualquier punto, produce un mensaje de registro, lanza una
  excepción o invoca `toString()` sobre un objeto que carga alguno de esos valores
- **ENTONCES** ninguno de esos textos contiene el token en claro, su SHA-256, la contraseña nueva ni
  su hash
- **Y** eso se verifica por inspección del texto producido

#### Escenario: La prueba de redacción detecta una fuga reintroducida

- **DADO** la prueba de redacción del escenario anterior
- **CUANDO** se ejecuta contra una variante de control en la que el objeto que carga el token expone
  su valor en `toString()`
- **ENTONCES** la prueba falla, lo que demuestra que alcanza el caso peligroso y no pasa de vacío

### Requisito: Ausencia de revocación de sesiones al restablecer (brecha con destino: `session-tokens-and-web-layer`, condición dura de aceptación)

El sistema NO DEBE revocar, en este cambio, ninguna sesión ni familia de tokens de refresco al
restablecer una contraseña, porque todavía no existe ninguna (`docs/03-seguridad.md` §4.5 y §4.7;
ADR-0005, «Recuperación de contraseña», punto 4). Es condición dura de aceptación de
**`session-tokens-and-web-layer`**: ese cambio NO DEBE fusionar la emisión de tokens de refresco sin
revocar todas las familias de una cuenta cuando se restablece su contraseña.

#### Escenario: El restablecimiento solo cambia la contraseña

- **DADO** la cuenta `ana.martinez@colegio.edu.hn` con el token vivo `PRT-88c1`
- **CUANDO** se restablece su contraseña con `PRT-88c1`
- **ENTONCES** los únicos efectos son el consumo del token, el hash nuevo y sus asientos de auditoría
- **Y** no se revoca ninguna sesión, porque este cambio no crea ninguna

#### Escenario: La brecha se cierra con la primera emisión de tokens de refresco

- **DADO** la condición de aceptación de este requisito
- **CUANDO** `session-tokens-and-web-layer` entregue la emisión de tokens de refresco
- **ENTONCES** ese cambio incluye una prueba en la que un restablecimiento revoca todas las familias
  de la cuenta
- **Y** este requisito deja de ser cierto ese día

### Requisito: Ausencia de programación real de la emisión (brecha con destino: cambio 9)

El sistema NO DEBE ejecutar, en este cambio, ninguna emisión programada por sí solo. El puerto de
programación que usa la solicitud NO DEBE tener adaptador de producción, porque db-scheduler llega
con el **cambio 9**, `background-jobs-with-db-scheduler` (ADR-0016).

#### Escenario: El puerto de programación no tiene adaptador de producción

- **DADO** el árbol de clases de producción del módulo de identidad
- **CUANDO** se inspecciona qué clases implementan el puerto de programación de la emisión
- **ENTONCES** ninguna lo hace
- **Y** esta comprobación falla el día que exista un adaptador sin retirarla

#### Escenario: Una solicitud no produce un token por sí sola

- **DADO** una solicitud de recuperación para `ana.martinez@colegio.edu.hn`, atendida con un doble del
  puerto de programación que solo registra la llamada
- **CUANDO** transcurre cualquier tiempo sin que se invoque explícitamente la emisión
- **ENTONCES** no existe ningún token de recuperación para esa cuenta

### Requisito: Ausencia de envío real del enlace y de la notificación al titular (brecha con destino: `transactional-email-adapter`, cambio 14 de F0)

El sistema NO DEBE enviar, en este cambio, ningún correo con el enlace de recuperación ni ninguna
notificación al titular tras un restablecimiento (`docs/03-seguridad.md` §4.7, «Efecto»; ADR-0005,
«Recuperación de contraseña», punto 5). El puerto de envío que usa la emisión NO DEBE tener adaptador
de producción. El destino es **`transactional-email-adapter`**, cambio 14 de F0, posterior al cambio
9. La IP de la notificación la aporta además `session-tokens-and-web-layer`.

#### Escenario: El puerto de envío no tiene adaptador de producción

- **DADO** el árbol de clases de producción del módulo de identidad
- **CUANDO** se inspecciona qué clases implementan el puerto de envío del enlace
- **ENTONCES** ninguna lo hace
- **Y** esta comprobación falla el día que exista un adaptador sin retirarla

#### Escenario: Un restablecimiento completado no notifica a nadie

- **DADO** un restablecimiento completado para `ana.martinez@colegio.edu.hn`
- **CUANDO** se inspeccionan los asientos de auditoría que produjo
- **ENTONCES** ninguno nombra una notificación, un correo ni un intento de entrega

### Requisito: Ausencia del límite por dirección IP y de la respuesta HTTP de la recuperación (brecha con destino: `session-tokens-and-web-layer` y cambio 11)

El sistema NO DEBE aplicar, en este cambio, el límite de 10 solicitudes por hora por dirección IP de
`docs/03-seguridad.md` §4.7 y §10, ni producir la respuesta `202 Accepted` con su mensaje, porque no
existe borde HTTP. Es condición dura de aceptación de **`session-tokens-and-web-layer`**: ese cambio
NO DEBE fusionar el endpoint de solicitud de recuperación sin ese límite por IP, con el
aprovisionamiento de Redis del **cambio 11**, operativo y probado.

#### Escenario: Once solicitudes seguidas no encuentran ningún límite por IP

- **DADO** once cuentas de personal distintas
- **CUANDO** se solicita recuperación para cada una entre las 10:00:00 y las 10:05:00 del 2026-10-16,
  como si vinieran de la misma dirección IP
- **ENTONCES** las once quedan programadas, porque el control por IP no existe todavía

#### Escenario: El resultado uniforme es del caso de uso, no una respuesta HTTP

- **DADO** una solicitud de recuperación con cualquier correo
- **CUANDO** el caso de uso devuelve su resultado
- **ENTONCES** ese resultado no es un código de estado HTTP ni lleva un texto para el usuario
- **Y** el `202 Accepted` y su mensaje, tomado del catálogo de internacionalización, quedan como
  responsabilidad de `session-tokens-and-web-layer`

### Requisito: Ausencia de purga de tokens vencidos (brecha con destino: cambio 9)

El sistema NO DEBE eliminar, en este cambio, ningún token de recuperación, esté vencido, superado o
usado. La eliminación automática que pide `docs/08-datos-privacidad-y-retencion.md` queda con el
**cambio 9**, que decide la purga. Hasta entonces las filas se retienen y ningún rol de aplicación
recibe privilegio `DELETE` sobre ellas (respuesta del propietario del 2026-09-30).

#### Escenario: Un token vencido sigue almacenado

- **DADO** el token `PRT-99e2`, emitido a las 10:00:00 del 2026-10-16 y nunca usado
- **CUANDO** se consulta la tabla de tokens a las 12:00:00 del mismo día
- **ENTONCES** la fila de `PRT-99e2` sigue existiendo, marcada como no usada, y el token se rechaza si
  se presenta

#### Escenario: Los tokens usados y superados también se conservan

- **DADO** un token usado a las 10:05:00 y otro superado a las 10:10:00 del 2026-10-16
- **CUANDO** se consulta la tabla de tokens al día siguiente
- **ENTONCES** las dos filas siguen existiendo, con su marca de uso y de sustitución respectivamente

### Requisito: Ausencia del restablecimiento administrativo (brecha con destino: cambio 8 y `session-tokens-and-web-layer`)

El sistema NO DEBE ofrecer, en este cambio, ningún caso de uso con el que un actor distinto del
titular dispare el restablecimiento de otra cuenta ni invalide su contraseña (`docs/03-seguridad.md`
§4.7, «Restablecimiento administrativo»), porque el rol de Super Administrador llega con el **cambio
8**, `rbac-permission-matrix-and-audit-integration`, y el endpoint con
`session-tokens-and-web-layer`. Ningún caso de uso DEBE permitir fijar una contraseña sin un token de
recuperación válido.

#### Escenario: El único camino para cambiar una contraseña es el token

- **DADO** el conjunto de casos de uso públicos del módulo de identidad
- **CUANDO** se inspecciona cuáles escriben el hash de contraseña de una cuenta
- **ENTONCES** el único es el restablecimiento con token

#### Escenario: Un restablecimiento sin token válido nunca cambia la contraseña

- **DADO** la cuenta `ana.martinez@colegio.edu.hn`, con la contraseña vigente `Cafetal de Copán 2026`
- **CUANDO** se intenta restablecerla con un token que nunca se emitió
- **ENTONCES** el sistema lo rechaza y `Cafetal de Copán 2026` sigue autenticando

## MODIFIED Requirements

### Requisito: Recuperación de contraseña con token de un solo uso y de corta vida

El sistema DEBE emitir, tras una solicitud de recuperación para una cuenta existente, un token de un
solo uso válido durante treinta minutos **desde su emisión**. El token DEBE aceptarse en un instante
estrictamente anterior a los treinta minutos y NO DEBE aceptarse a los treinta minutos exactos ni
después. El sistema NO DEBE aceptar un token ya usado, vencido, o superado por la **emisión** de un
token más reciente para la misma cuenta; una solicitud que todavía no produjo su emisión no supera
nada. El consumo del token y el cambio de contraseña DEBEN ocurrir en la misma transacción. Tras el
cambio, la contraseña anterior NO DEBE autenticar y la nueva DEBE hacerlo.

#### Escenario: Recuperación dentro de la ventana de vigencia

- **DADO** una solicitud de recuperación para `ana.martinez@colegio.edu.hn` a las 09:59:50 del
  2026-05-14, cuya emisión produce el token `PRT-88a1` a las 10:00:00
- **CUANDO** la usuaria establece la contraseña nueva `Lempira y maíz 2026` con `PRT-88a1` a las
  10:20:00 del mismo día
- **ENTONCES** el sistema acepta el cambio y activa la contraseña nueva
- **Y** invalida `PRT-88a1` de inmediato, de modo que un segundo uso a las 10:21:00 se rechaza
- **Y** la contraseña anterior ya no autentica, y `Lempira y maíz 2026` sí

#### Escenario: Token expirado o superado por uno más reciente

- **DADO** el token `PRT-88a1` emitido a las 10:00:00 del 2026-05-14
- **Y** una segunda solicitud para la misma cuenta cuya emisión produce `PRT-88b2` a las 10:25:00
- **CUANDO** la usuaria intenta usar `PRT-88a1` a las 10:26:00, todavía dentro de sus treinta
  minutos
- **ENTONCES** el sistema rechaza `PRT-88a1` por haber sido superado por una emisión posterior, no por
  vencimiento
- **Y** `PRT-88b2` completa el cambio a las 10:30:00, dentro de su propia ventana de treinta minutos

#### Escenario: El borde de los treinta minutos es estricto

- **DADO** el token `PRT-90a1` de `ana.martinez@colegio.edu.hn` y el token `PRT-90b1` de
  `luis.fernandez@colegio.edu.hn`, ambos emitidos a las 10:00:00 del 2026-05-14
- **CUANDO** `PRT-90a1` se presenta a las 10:29:59 y `PRT-90b1` a las 10:30:00 del mismo día
- **ENTONCES** el sistema acepta `PRT-90a1` y rechaza `PRT-90b1` por vencido

#### Escenario: Una solicitud todavía no emitida no supera el token vivo

- **DADO** el token `PRT-91a1` de `ana.martinez@colegio.edu.hn`, emitido a las 10:00:00 del
  2026-05-14
- **Y** una segunda solicitud para la misma cuenta a las 10:10:00, con su emisión programada pero
  todavía no ejecutada
- **CUANDO** la usuaria usa `PRT-91a1` a las 10:12:00
- **ENTONCES** el sistema acepta el cambio, porque la sustitución ocurre al emitir y no al solicitar

### Requisito: Prohibición de enumeración de usuarios

El sistema NO DEBE revelar, a través de ninguna respuesta de autenticación o de recuperación de
contraseña, si una cuenta con un identificador dado existe o no. La respuesta DEBE ser idéntica
en forma, contenido y tiempo observable de respuesta en ambos casos.

Al nivel del caso de uso, la solicitud de recuperación DEBE devolver el mismo resultado y escribir el
mismo número de asientos de auditoría, con la misma acción, para una cuenta existente, para un
identificador sin cuenta y para una cuenta cuya emisión omitirá el límite por hora. El motivo real
DEBE quedar solo en la bitácora. El identificador presentado DEBE aparecer en la bitácora como su
huella con llave, la misma que usa el inicio de sesión, y nunca en claro cuando no corresponde a
ninguna cuenta. La respuesta `202 Accepted` con su mensaje y la medición del tiempo sobre respuestas
HTTP son responsabilidad de `session-tokens-and-web-layer`.

#### Escenario: Solicitud de recuperación con correo existente

- **DADO** que `maria.lopez@colegio.edu.hn` existe como cuenta de personal
- **CUANDO** se solicita recuperación de contraseña para ese correo
- **ENTONCES** el caso de uso devuelve el resultado uniforme de solicitud recibida
- **Y** escribe exactamente un asiento de solicitud, con la huella con llave del correo como entidad
- **Y** programa una emisión para la cuenta

#### Escenario: Solicitud de recuperación con correo inexistente

- **DADO** que `nadie.registrado@colegio.edu.hn` no corresponde a ninguna cuenta
- **CUANDO** se solicita recuperación de contraseña para ese correo
- **ENTONCES** el caso de uso devuelve el mismo resultado que en el escenario anterior
- **Y** escribe exactamente un asiento de solicitud con la misma acción, con la huella con llave del
  correo como entidad y sin el correo en claro en ninguna columna
- **Y** no programa ninguna emisión, así que no se envía ningún correo

#### Escenario: Una cuenta en el límite por hora es indistinguible de una cuenta inexistente

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn`, con tres tokens emitidos en la última hora
- **Y** el correo `nadie.registrado@colegio.edu.hn`, que no corresponde a ninguna cuenta
- **CUANDO** se solicita recuperación para cada uno a las 10:30:00 del 2026-10-12
- **ENTONCES** los dos resultados son idénticos y cada solicitud escribe exactamente un asiento de
  solicitud con la misma acción
- **Y** el hecho de que la cuenta esté en el límite solo aparece en la bitácora, al omitirse su
  emisión

### Requisito: Ausencia de verificación contra contraseñas comprometidas y de rehash transparente (brecha con destino: cambio propio de la lista de contraseñas comprometidas, y cambio 8 o un cambio de identidad posterior)

> **Sustituye al requisito vigente** «Ausencia de verificación contra contraseñas comprometidas y de
> rehash transparente (brecha con destino: cambio 8 o un cambio de identidad posterior)». Cambia el
> destino de la mitad de la lista comprometida, por decisión del propietario del 2026-09-30, y la
> extiende al restablecimiento. La mitad del rehash transparente conserva su destino.

El sistema NO DEBE verificar la contraseña presentada al autenticar, ni la contraseña nueva de un
restablecimiento, contra ninguna lista de contraseñas comprometidas de `docs/03-seguridad.md` §4.2.
El destino de ese control es **un cambio propio de F0 dedicado a la lista de contraseñas
comprometidas**, todavía sin identificador asignado. El sistema tampoco DEBE recalcular el hash
almacenado cuando sus parámetros de Argon2id difieren de los vigentes según §4.1; el destino de ese
control sigue siendo **el cambio 8 o un cambio de identidad posterior**.

#### Escenario: Ninguna verificación contra contraseñas comprometidas ocurre durante la autenticación

- **DADO** un intento de autenticación con una contraseña presente en listas públicas de
  contraseñas filtradas, contra una cuenta cuya contraseña vigente coincide con ese valor
- **CUANDO** el sistema verifica la combinación
- **ENTONCES** el resultado depende únicamente de si la contraseña coincide con el hash almacenado,
  sin ninguna consulta a una lista de contraseñas comprometidas ni a un servicio externo
- **Y** este escenario deja de ser cierto el día que el cambio propio de la lista de contraseñas
  comprometidas entregue la verificación

#### Escenario: Una contraseña filtrada de longitud válida se acepta en el restablecimiento

- **DADO** la cuenta `ana.martinez@colegio.edu.hn` con un token vivo
- **CUANDO** restablece con la contraseña nueva `123456789012`, de doce caracteres y presente en
  listas públicas de contraseñas filtradas
- **ENTONCES** el sistema acepta el restablecimiento, porque solo aplica la regla de longitud
- **Y** este escenario deja de ser cierto el día que el cambio propio de la lista de contraseñas
  comprometidas entregue la verificación

#### Escenario: El hash almacenado no se recalcula aunque sus parámetros difieran de los vigentes

- **DADO** una cuenta cuyo hash almacenado se calculó con parámetros de Argon2id distintos de los
  vigentes en el sistema
- **CUANDO** esa cuenta se autentica con éxito
- **ENTONCES** el sistema no recalcula ni sustituye el hash almacenado
- **Y** este escenario deja de ser cierto el día que un cambio posterior entregue el rehash
  transparente

### Requisito: Ausencia de envío real del aviso de códigos de recuperación de MFA bajos (brecha con destino: `transactional-email-adapter`, cambio 14 de F0)

> **Sustituye al requisito vigente** «Ausencia de envío real del aviso de códigos de recuperación de
> MFA bajos (brecha sin destino identificado en el roadmap)». El propietario le asignó destino el
> 2026-09-30.

El sistema NO DEBE enviar ningún correo ni notificación real al quedar una cuenta con menos de tres
códigos de recuperación de MFA sin usar, tanto si el código se consumió en la verificación posterior
al inicio de sesión como en un restablecimiento de contraseña. El sistema entrega únicamente el dato
calculado y auditado —ver el requisito «Aviso al quedar con menos de tres códigos de recuperación de
MFA sin usar»—. El destino del envío real es **`transactional-email-adapter`**, cambio 14 de F0,
posterior al cambio 9.

#### Escenario: El aviso queda calculado y auditado, sin ningún correo enviado

- **DADO** una cuenta que consume su octavo código de recuperación de MFA, dejando dos sin usar
- **CUANDO** el sistema calcula la señal de aviso
- **ENTONCES** audita el evento con el conteo de códigos restantes y la señal de «por debajo de
  tres»
- **Y** no invoca ningún adaptador de envío de correo, porque ninguno existe todavía

#### Escenario: Consumir un código en un restablecimiento también calcula el aviso

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn`, con MFA activa, tres códigos de recuperación sin
  usar y un token vivo
- **CUANDO** restablece su contraseña presentando uno de esos códigos
- **ENTONCES** el sistema audita la señal de aviso con dos códigos restantes, igual que si lo hubiera
  consumido tras el inicio de sesión
- **Y** no se envía ningún correo

# Capacidad: Identidad

- **Identificador:** identity
- **Estado:** Borrador
- **Fase:** F0

## Propósito

Autenticar y autorizar a las dos audiencias del sistema (personal administrativo y encargados de
pago) en dominios de identidad completamente separados, y decidir en el servidor qué puede hacer
cada sesión. Le corresponde: inicio de sesión con contraseña, segundo factor, bloqueo por fuerza
bruta, ciclo de vida del token de sesión y de refresco, recuperación de contraseña y evaluación de
permisos. NO le corresponde: la gestión de los datos del estudiante o del encargado como entidad
de negocio (capacidades `students` y `guardians`), ni la bitácora de auditoría en sí misma
(capacidad transversal `shared/audit`), aunque todo evento de esta capacidad se audite.

## Requisitos

### Requisito: Autenticación con contraseña

El sistema DEBE autenticar a un usuario únicamente con la combinación correcta de identificador
de cuenta y contraseña, y NO DEBE emitir una sesión válida ante una combinación incorrecta.

#### Escenario: Credenciales correctas

- **DADO** un usuario de personal con correo `maria.lopez@colegio.edu.hn` y contraseña vigente
- **CUANDO** inicia sesión con ese correo y esa contraseña
- **ENTONCES** el sistema emite un token de sesión válido para el dominio de personal
- **Y** registra un evento de autenticación exitosa en la bitácora de auditoría

#### Escenario: Contraseña incorrecta

- **DADO** el mismo usuario `maria.lopez@colegio.edu.hn`
- **CUANDO** inicia sesión con la contraseña `Incorrecta#2026`, distinta de la vigente
- **ENTONCES** el sistema rechaza el inicio de sesión con un error genérico de credenciales
  inválidas
- **Y** NO emite ningún token de sesión
- **Y** registra el intento fallido asociado a la cuenta

### Requisito: MFA obligatoria para cuentas marcadas con `mfa_required`

El sistema DEBE exigir un segundo factor de autenticación para toda cuenta cuya columna
`mfa_required` esté en `true`, y NO DEBE producir el desenlace `Authenticated` para esa cuenta
mientras el segundo factor no se haya completado. Cuando la cuenta tiene un secreto TOTP ya
inscrito, el sistema DEBE producir el desenlace `SecondFactorRequired`, pendiente de un código TOTP
válido. Cuando la cuenta no tiene ningún secreto TOTP inscrito, el sistema DEBE producir el
desenlace `SecondFactorEnrollmentRequired`. Ninguno de los dos desenlaces DEBE hacer avanzar el
contador de retroceso por intentos fallidos, porque ninguno es un rechazo de credenciales. **La
traducción de `SecondFactorRequired` y de `SecondFactorEnrollmentRequired` a un token de sesión
completo o restringido es responsabilidad de `session-tokens-and-web-layer`**, el cuarto cambio de
esta secuencia; este cambio decide y devuelve el desenlace tipado, y no emite ningún token.

#### Escenario: Cuenta con `mfa_required` en `true` y secreto TOTP ya inscrito

- **DADO** la cuenta de personal `sofia.mejia@colegio.edu.hn`, con `mfa_required` en `true` y un
  secreto TOTP ya inscrito
- **CUANDO** completa el inicio de sesión con contraseña correcta
- **ENTONCES** el sistema devuelve el desenlace `SecondFactorRequired`, pendiente de un código TOTP
  válido
- **Y** no devuelve el desenlace `Authenticated`
- **Y** el contador de retroceso por intentos fallidos de esa cuenta no avanza
- **Y** la traducción de este desenlace a un token de sesión completo, una vez recibido un código
  válido, queda como responsabilidad de `session-tokens-and-web-layer`

#### Escenario: Cuenta con `mfa_required` en `true` sin ningún secreto TOTP inscrito

- **DADO** la cuenta de personal `jorge.aguilar@colegio.edu.hn`, con `mfa_required` en `true` y sin
  ningún secreto TOTP inscrito
- **CUANDO** completa el inicio de sesión con contraseña correcta
- **ENTONCES** el sistema devuelve el desenlace `SecondFactorEnrollmentRequired`
- **Y** no devuelve el desenlace `Authenticated`
- **Y** el contador de retroceso por intentos fallidos de esa cuenta no avanza
- **Y** la traducción de este desenlace a una sesión restringida a la inscripción, en vez de una
  sesión completa, queda como responsabilidad de `session-tokens-and-web-layer`

### Requisito: Retardo por intentos fallidos con retroceso exponencial

El sistema DEBE retardar la respuesta a un intento de autenticación a partir del tercer intento
fallido consecutivo contra la misma cuenta, con un retardo de `2^(n-3)` segundos —donde `n` es el
número de intentos fallidos consecutivos— y un tope de 900 segundos. El retardo **demora la
respuesta, no la deniega**: el sistema NO DEBE rechazar por causa del retroceso un intento cuyas
credenciales son correctas, ni bloquear la cuenta de ninguna otra forma. El contador de intentos
fallidos consecutivos DEBE expirar a los 30 minutos sin ningún intento, y un inicio de sesión
exitoso DEBE limpiarlo. El sistema DEBE auditar cada ciclo de retroceso con su duración, y el motivo
de todo rechazo —credenciales incorrectas o cuenta inexistente— DEBE ser interno y auditado, nunca
observable por quien llama.

#### Escenario: El retardo se aplica desde el tercer fallo consecutivo, y el ciclo queda auditado con su duración

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn` sin fallos previos registrados
- **CUANDO** ocurren tres intentos fallidos consecutivos entre las 08:00:00 y las 08:00:40 del
  2026-03-10
- **ENTONCES** el sistema aplica, antes de responder al tercer intento, un retardo de `2^(3-3)`, es
  decir 1 segundo
- **Y** registra en la bitácora de auditoría el ciclo de retroceso con su duración de 1 segundo

#### Escenario: La progresión exponencial se limita a 900 segundos

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn` con doce intentos fallidos consecutivos ya
  registrados desde las 08:00:00 del 2026-03-10
- **CUANDO** ocurre el decimotercer intento fallido consecutivo a las 08:15:00 del mismo día
- **ENTONCES** el sistema aplica un retardo de 900 segundos, el tope fijado, en vez de los
  `2^(13-3)`, es decir 1024 segundos, que resultarían de la progresión sin tope
- **Y** registra el ciclo con su duración de 900 segundos

#### Escenario: El contador expira a los 30 minutos sin intentos

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn` con tres intentos fallidos consecutivos a las
  09:00:00 del 2026-03-10, que activaron un retardo de 1 segundo
- **CUANDO** transcurren 31 minutos sin ningún intento adicional y ocurre un nuevo intento fallido a
  las 09:31:00
- **ENTONCES** el sistema trata ese intento como el primero de un ciclo nuevo, sin aplicar ningún
  retardo, porque el contador expiró a los 30 minutos sin intentos

#### Escenario: Un inicio de sesión exitoso limpia el contador de la cuenta

- **DADO** la cuenta `maria.lopez@colegio.edu.hn` con dos intentos fallidos consecutivos registrados
  a las 10:00:00 del 2026-03-10
- **CUANDO** inicia sesión con la contraseña correcta a las 10:05:00 del mismo día
- **ENTONCES** el sistema limpia el contador de fallos de esa cuenta
- **Y** un intento fallido inmediatamente posterior, a las 10:06:00, se trata como el primer fallo de
  un ciclo nuevo, sin retardo

#### Escenario: El retardo se aplica también a una cuenta inexistente, calculado sobre el correo presentado

- **DADO** que `nadie.registrado@colegio.edu.hn` no corresponde a ninguna cuenta
- **CUANDO** se presentan tres intentos fallidos consecutivos contra ese correo entre las 11:00:00 y
  las 11:00:40 del 2026-03-10
- **ENTONCES** el sistema aplica, antes de responder al tercer intento, el mismo retardo de 1
  segundo que aplicaría a una cuenta existente en su tercer fallo, calculado sobre el correo
  presentado
- **Y** la respuesta es indistinguible en forma, contenido y tiempo observable de la que recibiría
  una cuenta existente con contraseña incorrecta en su tercer fallo

#### Escenario: El motivo del rechazo es interno y nunca observable por el cliente

- **DADO** las dos causas posibles de rechazo de un intento de autenticación: contraseña incorrecta
  y cuenta inexistente
- **CUANDO** cada una ocurre por separado
- **ENTONCES** el sistema produce, en ambos casos, la misma respuesta observable por quien llama
- **Y** el motivo real queda registrado únicamente en la bitácora de auditoría, nunca expuesto en la
  respuesta

#### Escenario: Una contraseña correcta durante el retroceso tiene éxito tras el retardo, y no es rechazada

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn` con tres intentos fallidos consecutivos
  registrados a las 12:00:00 del 2026-03-10, que dejaron el contador en tres
- **CUANDO** presenta la contraseña **correcta** en el cuarto intento, a las 12:00:10 del mismo día
- **ENTONCES** el sistema aplica el retardo de `2^(4-3)`, es decir 2 segundos, antes de responder
- **Y** a continuación **autentica con éxito**: el retroceso demora la respuesta y NO la deniega
- **Y** limpia el contador de fallos de esa cuenta

### Requisito: Rotación de token de refresco con detección de reutilización

El sistema DEBE emitir un token de refresco nuevo y de un solo uso en cada renovación de sesión,
invalidando el anterior, y DEBE revocar toda la familia de tokens derivados de una misma sesión
en cuanto detecta el reuso de un token de refresco ya invalidado.

#### Escenario: Renovación normal de sesión

- **DADO** un token de refresco `RT-1001` vigente, emitido a las 09:00:00 del 2026-04-02
- **CUANDO** el cliente lo usa para renovar la sesión a las 09:15:00
- **ENTONCES** el sistema emite un token de sesión nuevo y un token de refresco nuevo `RT-1002`
- **Y** invalida `RT-1001` de forma permanente

#### Escenario: Reuso de un token de refresco ya invalidado

- **DADO** que `RT-1001` ya fue usado y sustituido por `RT-1002`, como en el escenario anterior
- **CUANDO** alguien intenta usar `RT-1001` a las 09:20:00 del mismo día
- **ENTONCES** el sistema rechaza la solicitud
- **Y** revoca de inmediato toda la familia de tokens derivada de la sesión original, incluido
  `RT-1002`, forzando un nuevo inicio de sesión
- **Y** registra el evento como una posible fuga de token en la bitácora de auditoría

### Requisito: Separación total entre el dominio de identidad de personal y el de encargados

El sistema NO DEBE aceptar como válido, en la API administrativa, un token emitido para el
dominio de encargados, ni aceptar como válido en la API del portal un token emitido para el
dominio de personal, aunque ambos tokens estén vigentes en su propio dominio.

#### Escenario: Token de encargado presentado a la API administrativa

- **DADO** un encargado de pago con sesión activa y un token de sesión válido en el dominio
  `portal`
- **CUANDO** ese mismo token se presenta a un endpoint de la API administrativa, por ejemplo
  `GET /api/v1/cashbox/sessions`
- **ENTONCES** el sistema rechaza la solicitud con un error de autenticación
- **Y** NO evalúa ningún permiso administrativo para ese token

#### Escenario: Token de personal presentado a la API del portal

- **DADO** un usuario de personal con sesión activa y un token de sesión válido en el dominio
  `staff`
- **CUANDO** ese token se presenta a un endpoint de la API del portal, por ejemplo
  `GET /api/v1/portal/account-statement`
- **ENTONCES** el sistema rechaza la solicitud con un error de autenticación
- **Y** el rechazo ocurre por audiencia de token inválida, sin distinguir si el usuario de
  personal tendría o no el permiso equivalente

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

### Requisito: Autorización basada en permisos evaluada en el servidor

El sistema DEBE evaluar en el servidor, en cada solicitud, si el permiso requerido por la
operación está presente en la sesión autenticada, y NO DEBE conceder una operación protegida
basándose en una afirmación de permiso enviada por el cliente.

#### Escenario: Permiso presente en la sesión

- **DADO** una sesión de personal con el permiso `invoicing:issue` asignado por su rol
- **CUANDO** solicita `POST /api/v1/invoicing/invoices` para emitir una factura
- **ENTONCES** el sistema evalúa el permiso contra los datos de la sesión almacenados en el
  servidor y autoriza la operación

#### Escenario: Cliente afirma un permiso que la sesión no tiene

- **DADO** una sesión de personal con el rol `cashier`, que NO incluye `invoicing:void`
- **CUANDO** solicita `POST /api/v1/invoicing/invoices/{id}/void` incluyendo en la solicitud un
  campo `claimedPermission: "invoicing:void"` fabricado por el cliente
- **ENTONCES** el sistema ignora por completo ese campo, evalúa únicamente los permisos
  registrados en el servidor para esa sesión, y rechaza la operación con `403 Forbidden`

### Requisito: Resultado tipado de la autenticación con cuatro desenlaces

El sistema DEBE representar el resultado del caso de uso de autenticación con contraseña como un
tipo cerrado con exactamente cuatro desenlaces posibles: `Authenticated`, que lleva el
identificador del usuario y el de la institución; `Rejected`, que lleva un motivo interno de
rechazo, registrado en la bitácora de auditoría y nunca expuesto a quien llama; `SecondFactorRequired`,
que lleva el identificador del usuario y el de la institución, pendiente de un código TOTP válido; y
`SecondFactorEnrollmentRequired`, que lleva el identificador del usuario y el de la institución,
pendiente de completar la inscripción del segundo factor. Ninguno de los cuatro desenlaces DEBE
llevar un campo de alcance de autorización.

#### Escenario: Contraseña correcta produce el desenlace `Authenticated` sin campo de alcance

- **DADO** `maria.lopez@colegio.edu.hn` con contraseña vigente y `mfa_required` en `false`
- **CUANDO** el caso de uso de autenticación se invoca con esa contraseña
- **ENTONCES** devuelve el desenlace `Authenticated` con el identificador del usuario y el de la
  institución
- **Y** ese desenlace no lleva ningún campo de alcance de autorización

#### Escenario: Toda causa de rechazo produce el desenlace `Rejected` con un motivo interno

- **DADO** dos intentos de autenticación distintos: contraseña incorrecta contra una cuenta
  existente, y un correo que no corresponde a ninguna cuenta
- **CUANDO** el caso de uso procesa cada uno por separado
- **ENTONCES** ambos devuelven el desenlace `Rejected`, cada uno con un motivo interno distinto que
  la auditoría registra
- **Y** ningún campo del resultado expuesto a quien llama distingue entre los dos motivos
- **Y** un retroceso vigente **no** es causa de `Rejected`: retarda la respuesta y deja que el
  intento se resuelva por sus credenciales

#### Escenario: Los cuatro desenlaces son exactamente los que el tipo cerrado permite, ninguno más

- **DADO** el tipo cerrado `AuthenticationResult`
- **CUANDO** se inspecciona la cláusula `permits` que declara
- **ENTONCES** contiene exactamente `Authenticated`, `Rejected`, `SecondFactorRequired` y
  `SecondFactorEnrollmentRequired`, ninguno más
- **Y** ninguno de los cuatro lleva un campo de alcance de autorización

### Requisito: Verificación Argon2id contra un hash señuelo cuando la cuenta no existe

Cuando el identificador de cuenta presentado no corresponde a ninguna cuenta existente, el sistema
DEBE ejecutar igualmente una verificación Argon2id de la contraseña presentada contra un hash
señuelo constante, con los mismos parámetros vigentes de Argon2id que una verificación real, de
modo que la ejecución de esa verificación no dependa de si la cuenta existe.

#### Escenario: La verificación señuelo se ejecuta ante una cuenta inexistente

- **DADO** que `nadie.registrado@colegio.edu.hn` no corresponde a ninguna cuenta
- **CUANDO** se intenta autenticar con ese correo y cualquier contraseña
- **ENTONCES** el sistema ejecuta la verificación Argon2id contra el hash señuelo constante,
  verificable por interacción con el puerto de verificación, antes de devolver el desenlace
  `Rejected`
- **Y** el motivo interno del rechazo registrado en la auditoría es que la cuenta no existe, nunca
  expuesto a quien llama

### Requisito: Estado del retroceso persistido en PostgreSQL, con atomicidad de efecto y auditoría

El sistema DEBE persistir el estado del retroceso en PostgreSQL —contador de intentos fallidos
consecutivos y marca de tiempo del último intento—, indexado por una **huella con llave del
identificador presentado** y no por la cuenta, de modo que exista también para identificadores que
no corresponden a ninguna cuenta. El camino del retroceso DEBE ser el mismo para una cuenta
existente y para un identificador inexistente: la misma escritura, el mismo bloqueo y el mismo
número de asientos de auditoría, para que la uniformidad que exige `docs/03-seguridad.md` §4.6 sea
una propiedad estructural y no una promesa. El sistema DEBE registrar el intento fallido, calcular
el retardo resultante y escribir el asiento de auditoría correspondiente dentro de una única
transacción. Si esa transacción no confirma, ninguno de esos tres efectos DEBE sobrevivir.

#### Escenario: El estado del retroceso existe para un identificador sin cuenta

- **DADO** que `nadie.registrado@colegio.edu.hn` no corresponde a ninguna cuenta
- **CUANDO** se presentan dos intentos fallidos consecutivos contra ese identificador
- **ENTONCES** el estado del retroceso conserva el contador en dos para ese identificador, sin que
  exista ninguna fila de cuenta a la que asociarlo
- **Y** el identificador no aparece en claro en ese estado, sino como huella con llave, de modo que
  quien consulte esa tabla no aprenda qué identificadores se intentaron

#### Escenario: El efecto y su asiento de auditoría se confirman o revierten juntos

- **DADO** un intento fallido real contra una cuenta existente, procesado dentro de una transacción
  que a continuación falla de forma determinista antes de confirmar
- **CUANDO** se inspecciona el estado tras la reversión
- **ENTONCES** ni el contador de fallos de esa cuenta avanzó
- **Y** no existe ningún asiento nuevo en la bitácora de auditoría para ese intento

#### Escenario: Dos intentos fallidos concurrentes contra la misma cuenta no pierden ninguna escritura

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn` con un intento fallido ya registrado
- **CUANDO** dos intentos fallidos adicionales contra la misma cuenta se ejecutan de forma
  concurrente y sincronizada
- **ENTONCES** el contador final de la cuenta refleja los tres fallos
- **Y** ninguna de las dos escrituras concurrentes se pierde

### Requisito: La institución previa a la autenticación proviene de la configuración del proceso

La institución que el caso de uso de autenticación usa para resolver la cuenta, y contra la que la
política de seguridad a nivel de fila evalúa esa resolución, DEBE provenir de la configuración del
proceso del servidor. NO DEBE provenir del cuerpo de la solicitud, de una cabecera ni de un
parámetro de consulta, porque el inicio de sesión ocurre antes de que exista un token autenticado
del que derivarla.

#### Escenario: La institución se resuelve de la configuración del proceso, nunca de la solicitud

- **DADO** un proceso configurado con la institución `d290f1ee-6c54-4b01-90e6-d701748f0851`
- **Y** una solicitud de autenticación que declara, en un campo de su propio cuerpo, la institución
  distinta `5a6a5e02-1c2e-4e3a-9d3f-6b2b3a1e9f10`
- **CUANDO** se invoca el caso de uso de autenticación con esa solicitud
- **ENTONCES** el sistema resuelve la cuenta usando la institución
  `d290f1ee-6c54-4b01-90e6-d701748f0851` de la configuración del proceso
- **Y** ignora por completo la institución declarada en el cuerpo de la solicitud

### Requisito: Puerto y adaptador de escritura de la bitácora de auditoría

El sistema DEBE proveer, junto al puerto existente de lectura de `shared_audit_log`, un puerto y un
adaptador de escritura, que produzcan, dentro de la misma transacción que su efecto, al menos los
eventos de autenticación exitosa, autenticación fallida y ciclo de retroceso. El adaptador de
escritura NO DEBE calcular `prev_hash` ni `row_hash`: el disparador de encadenamiento existente de
la tabla los asigna.

#### Escenario: El primer escritor de producción de `shared_audit_log`

- **DADO** que, hasta este cambio, ningún proceso de producción escribe en `shared_audit_log`
- **CUANDO** el caso de uso de autenticación registra un intento exitoso, uno fallido, o un ciclo de
  retroceso
- **ENTONCES** el adaptador inserta la fila correspondiente sin calcular `prev_hash` ni `row_hash`
- **Y** el disparador de encadenamiento existente la encadena a la cadena de su institución

### Requisito: Ningún secreto de este módulo es observable en registros, excepciones ni pruebas

El sistema NO DEBE hacer observable, en ningún mensaje de registro, mensaje de excepción,
representación textual (`toString()` o equivalente) ni salida de una prueba fallida, la contraseña
en claro, el hash Argon2id resultante, ni la pimienta de Argon2id.

#### Escenario: Ningún registro ni excepción expone la contraseña, el hash o la pimienta

- **DADO** un intento de autenticación con la contraseña `Segura#2026` contra
  `maria.lopez@colegio.edu.hn`
- **CUANDO** el sistema procesa el intento y, en cualquier punto de ese procesamiento, produce un
  mensaje de registro o lanza una excepción relacionada
- **ENTONCES** ninguno de esos mensajes contiene la contraseña en claro, el hash Argon2id calculado
  ni la pimienta
- **Y** eso se verifica por inspección del texto producido, no por confianza en el diseño

### Requisito: El retardo se calcula dentro de la transacción y se materializa fuera de ella

El caso de uso de autenticación DEBE calcular la duración del retardo exigible y devolverla junto al
desenlace, y DEBE confirmar su transacción antes de devolverla. El sistema NO DEBE materializar la
espera mientras mantiene abierta una transacción de base de datos, una conexión del grupo o un
bloqueo de fila, ni reteniendo un hilo de plataforma. El número de respuestas retardadas simultáneas
DEBE estar acotado, y al alcanzar ese límite el sistema DEBE responder con un error de capacidad
uniforme decidido antes de procesar el intento, y NO DEBE acortar el retardo.

#### Escenario: La duración exigible se devuelve y la transacción ya confirmó

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn` con tres intentos fallidos consecutivos
  registrados a las 13:00:00 del 2026-03-10
- **CUANDO** se invoca el caso de uso con la contraseña correcta a las 13:00:05 del mismo día
- **ENTONCES** devuelve el desenlace `Authenticated` junto con una duración exigible de 2 segundos
- **Y** el contador de fallos ya está limpio y el ciclo ya está auditado en el momento en que
  devuelve, sin que ninguna espera haya ocurrido todavía

#### Escenario: Ninguna clase del módulo de identidad espera

- **DADO** el árbol de clases de producción de `com.confia.identity`
- **CUANDO** se inspecciona si alguna invoca una primitiva de espera del hilo
- **ENTONCES** ninguna lo hace, y la construcción falla si alguna lo hiciera

### Requisito: Ausencia de la dimensión por dirección IP del retroceso exponencial (brecha con destino: `session-tokens-and-web-layer` y cambio 11)

El sistema NO DEBE aplicar, en esta parte del cambio, ninguna de las tres reglas de dimensión por
dirección IP de `docs/03-seguridad.md` §4.4 —límite por IP, límite por IP contra cuentas distintas,
ni el indicador de relleno de credenciales—, porque una dirección IP solo existe en el borde HTTP y
este cambio no entrega ninguna capa `web`. El control por IP es responsabilidad de
**`session-tokens-and-web-layer`**, y su aprovisionamiento sobre Redis, del **cambio 11**
(`containerization-and-cicd-pipeline`).

#### Escenario: Ninguna regla de dirección IP se aplica todavía

- **DADO** el caso de uso de autenticación de este cambio, invocado sin ningún concepto de dirección
  IP
- **CUANDO** se ejecutan diez intentos fallidos consecutivos como si vinieran de la misma dirección
  IP, contra cinco cuentas distintas
- **ENTONCES** el sistema no aplica ningún límite ni indicador por dirección IP, porque ese control
  no existe todavía en esta parte del cambio
- **Y** este escenario deja de ser cierto el día que `session-tokens-and-web-layer` entregue el
  control, con el aprovisionamiento del cambio 11

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

### Requisito: Ausencia de calibración de Argon2id en servidor real (brecha con destino: cambio 11)

El sistema NO DEBE declarar, en esta parte del cambio, los parámetros de Argon2id como calibrados
contra un servidor de producción real (`docs/03-seguridad.md` §4.1, «Calibración obligatoria antes
de producción»), porque no existe ningún entorno desplegado. El destino es **el cambio 11**, que
registra el valor elegido en un ADR con fecha y hardware.

#### Escenario: Los parámetros vigentes son el piso declarado, no un valor calibrado

- **DADO** los parámetros de Argon2id que este cambio declara
- **CUANDO** se pregunta si fueron calibrados contra el servidor de producción real
- **ENTONCES** la respuesta es que no, porque ningún entorno desplegado existe todavía
- **Y** este escenario deja de ser cierto el día que el cambio 11 registre la calibración en un ADR
  con fecha y hardware

### Requisito: Ausencia de prueba de extremo a extremo con Playwright del flujo de autenticación (brecha con destino: F1 o posterior)

El sistema NO DEBE contar, en esta parte del cambio, con ninguna prueba de extremo a extremo con
Playwright del flujo completo de autenticación con contraseña, porque no existe ninguna aplicación
React desplegable todavía. El destino es **F1 o una fase posterior**, cuando exista la pantalla.

#### Escenario: Ninguna prueba de extremo a extremo ejercita el flujo de autenticación

- **DADO** el árbol de pruebas de este cambio
- **CUANDO** se busca una prueba de Playwright que ejercite el inicio de sesión con contraseña de
  principio a fin
- **ENTONCES** no existe ninguna
- **Y** este escenario deja de ser cierto el día que F1 o una fase posterior entregue la pantalla y
  su prueba

### Requisito: Cifrado a nivel de columna del secreto TOTP con sobre de llaves

El sistema DEBE cifrar el secreto TOTP de cada cuenta a nivel de columna, con el esquema de sobre
de llaves de `docs/03-seguridad.md` §7.3: una llave maestra (KEK) fuera de la base de datos que
cifra una llave de datos (DEK) almacenada cifrada en una tabla propia con estado `active` o
`retired`, y la DEK activa cifra el secreto con AES-256-GCM. El sistema DEBE generar un vector de
inicialización aleatorio de 96 bits por operación, nunca reutilizado, con una etiqueta de
autenticación de 128 bits. El sistema DEBE incluir como datos adicionales autenticados la tripleta
`tabla|columna|id_de_fila` de la fila cifrada, y DEBE almacenar el valor con el formato
`v1:<id_dek>:<iv_base64>:<ciphertext_base64>:<tag_base64>`. El sistema NO DEBE descifrar
correctamente un valor cuando los datos adicionales autenticados no coinciden con los de su propia
fila.

#### Escenario: El secreto TOTP se almacena cifrado con el formato del sobre de llaves, nunca en claro

- **DADO** el secreto TOTP inscrito para la cuenta `sofia.mejia@colegio.edu.hn`
- **CUANDO** se consulta la columna directamente con SQL crudo
- **ENTONCES** el valor almacenado comienza con el prefijo `v1:` y no contiene el secreto en claro
  en ninguna parte de la cadena

#### Escenario: Un valor cifrado con los datos autenticados de una fila falla al descifrarse con los de otra fila

- **DADO** el secreto TOTP cifrado de la cuenta `sofia.mejia@colegio.edu.hn`, con datos adicionales
  autenticados que referencian su propia fila
- **CUANDO** se intenta descifrar ese mismo valor sustituyendo el identificador de fila de los datos
  adicionales autenticados por el de la cuenta `jorge.aguilar@colegio.edu.hn`
- **ENTONCES** el descifrado falla por fallo de autenticación, y el secreto no se recupera

### Requisito: Inscripción y verificación del segundo factor TOTP según RFC 6238

El sistema DEBE generar, al inscribir el segundo factor de una cuenta, un secreto TOTP de 20 bytes
aleatorios, y DEBE verificar un código presentado con el algoritmo TOTP de RFC 6238, SHA-1, 6
dígitos, periodo de 30 segundos y ventana de tolerancia de ±1 periodo (`docs/03-seguridad.md`
§4.3). El sistema DEBE almacenar, por cuenta, el último contador aceptado, y NO DEBE aceptar
ningún código cuyo contador sea menor o igual al último aceptado, aunque el código en sí sea
matemáticamente válido para ese contador.

El sistema DEBE devolver el secreto generado al llamador de la inscripción, **una única vez y en el
retorno de esa llamada**, codificado en base32 según RFC 4648 sin relleno, que es la forma que toda
aplicación de autenticación acepta como entrada manual. Sin esa devolución ninguna aplicación puede
aprender el secreto, y el segundo factor queda inscrito en la base pero imposible de activar en la
práctica. NO DEBE existir ninguna consulta posterior que recupere el secreto en claro: igual que los
códigos de recuperación, después de ese retorno solo persiste su forma cifrada.

La construcción del identificador URI `otpauth://` y su representación como código QR quedan
**fuera** de este requisito: exigen un emisor y una etiqueta por institución, que son información del
módulo de organización, y pertenecen al cambio que construya la pantalla de inscripción.

#### Escenario: La inscripción devuelve el secreto en base32 una única vez

- **DADO** que la cuenta `sofia.mejia@colegio.edu.hn` no tiene todavía segundo factor inscrito
- **CUANDO** se inscribe su segundo factor
- **ENTONCES** el retorno de esa llamada incluye el secreto TOTP codificado en base32 según RFC 4648,
  sin relleno, de 32 caracteres para los 20 bytes del secreto
- **Y** ese secreto en base32 corresponde exactamente al mismo secreto que quedó cifrado en la
  credencial almacenada, no a uno distinto
- **Y** ninguna consulta posterior a `identity_mfa_totp_credential` puede recuperar el secreto en
  claro

#### Escenario: Un código válido dentro de la ventana de tolerancia se acepta

- **DADO** el secreto TOTP inscrito para `sofia.mejia@colegio.edu.hn` y el instante
  `2026-10-05T08:00:00Z`
- **CUANDO** presenta el código de 6 dígitos correspondiente al periodo de 30 segundos
  inmediatamente anterior a ese instante
- **ENTONCES** el sistema lo acepta, por estar dentro de la ventana de tolerancia de ±1 periodo

#### Escenario: Un código ya aceptado no puede reutilizarse, aunque siga siendo matemáticamente válido

- **DADO** que el código correspondiente al contador `58204` de la cuenta
  `sofia.mejia@colegio.edu.hn` ya fue aceptado
- **CUANDO** se presenta de nuevo ese mismo código, todavía dentro de su ventana de 30 segundos de
  vigencia
- **ENTONCES** el sistema lo rechaza, porque su contador no es mayor que el último contador
  aceptado

### Requisito: Límite de tasa sobre la verificación de código TOTP

El sistema DEBE limitar la verificación de un código TOTP con **la misma regla de retroceso
exponencial que el inicio de sesión con contraseña**, sin una política propia: los dos primeros
fallos consecutivos no llevan retardo; desde el tercero, el retardo es de 2^(n−3) segundos para el
fallo n, con un tope de 900 segundos; y el contador se reinicia cuando pasan más de 30 minutos sin
ningún intento. Reutiliza además el mismo patrón —contador en PostgreSQL, calculado en el dominio
con reloj inyectado, probado sin esperar—, pero con la cuenta como clave en vez de la huella del
identificador presentado, porque la verificación de un código TOTP ocurre siempre contra una cuenta
ya identificada por contraseña, a diferencia del inicio de sesión. El retardo demora la respuesta,
nunca la deniega: el caso de uso devuelve el retardo exigido y el llamador DEBE respetarlo antes de
responder, igual que en el inicio de sesión.

> **Corrección del 2026-09-30, decidida por el propietario tras la verificación del cambio
> (`verify-report.md`, CRITICAL-1, opción a).** Este requisito decía «5 intentos por cada 15
> minutos, con retroceso exponencial posterior una vez agotado ese límite», copiando
> `docs/03-seguridad.md` §4.3. La decisión 8 de `design.md` reutilizó `BackoffPolicy` afirmando que
> esos eran sus umbrales, y no lo eran: la política empieza a retrasar en el tercer fallo y su
> contador vive 30 minutos. El propietario aceptó el comportamiento real, más estricto en los
> primeros intentos, y el texto pasa a describir lo que el código hace. El escenario del sexto
> intento se conserva porque sigue siendo cierto, con un solo ajuste: decía «aplica el retroceso
> antes de evaluar ese código», y el código evalúa el código y devuelve el retardo que el llamador
> debe respetar antes de responder, que es el contrato de la parte 1. El escenario nuevo es el que
> distingue la regla real de la anterior.

#### Escenario: El sexto intento de verificación TOTP en la misma ventana de 15 minutos activa el retroceso

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn` con cinco intentos fallidos de verificación TOTP
  entre las 09:00:00 y las 09:10:00 del 2026-10-05
- **CUANDO** presenta un sexto código, correcto o incorrecto, a las 09:12:00 del mismo día
- **ENTONCES** el sistema exige un retardo antes de responder a ese código, en vez de responder de
  inmediato
- **Y** registra el ciclo de retroceso en la bitácora de auditoría con su duración

#### Escenario: El retroceso empieza en el tercer fallo y su contador vive 30 minutos

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn` sin intentos previos de verificación TOTP
- **CUANDO** presenta códigos incorrectos a las 09:00, 09:01 y 09:02 del 2026-10-05, otro a las
  09:18 y otro a las 09:49
- **ENTONCES** los dos primeros no llevan retardo y el tercero lleva un segundo
- **Y** el de las 09:18, dieciséis minutos después del anterior, sigue en el mismo ciclo y lleva dos
  segundos
- **Y** el de las 09:49, treinta y un minutos después del anterior, empieza un ciclo nuevo sin
  retardo

### Requisito: Diez códigos de recuperación de MFA de un solo uso, hasheados con Argon2id

El sistema DEBE generar, al inscribir el segundo factor de una cuenta, diez códigos de recuperación
de MFA de un solo uso, de diez caracteres cada uno, mostrados al usuario una única vez, y DEBE
almacenar cada uno hasheado con Argon2id mediante un puerto de verificación propio, distinto del
que verifica contraseñas. El sistema DEBE invalidar un código de recuperación inmediatamente
después de usarlo, sin afectar a los nueve restantes, y NO DEBE aceptar ese mismo código una segunda
vez.

#### Escenario: Los diez códigos se generan y se muestran una única vez

- **DADO** la inscripción del segundo factor de la cuenta `sofia.mejia@colegio.edu.hn`
- **CUANDO** el sistema genera los códigos de recuperación de MFA
- **ENTONCES** genera exactamente diez códigos distintos, cada uno de un solo uso, y los devuelve al
  llamador una única vez, sin que ninguna consulta posterior pueda recuperarlos en claro

#### Escenario: Usar un código lo invalida sin afectar a los nueve restantes

- **DADO** los diez códigos de recuperación de MFA de `sofia.mejia@colegio.edu.hn`, ninguno usado
  todavía
- **CUANDO** se usa uno de ellos para completar un inicio de sesión pendiente de segundo factor
- **ENTONCES** ese código queda invalidado y un segundo intento con el mismo código se rechaza
- **Y** los nueve restantes siguen siendo válidos para un uso futuro

### Requisito: Aviso al quedar con menos de tres códigos de recuperación de MFA sin usar

El sistema DEBE calcular, cada vez que se usa un código de recuperación de MFA, cuántos códigos sin
usar quedan para esa cuenta, y DEBE producir y auditar una señal de aviso cuando ese conteo queda
por debajo de tres (`docs/03-seguridad.md` §4.3). Este cambio NO DEBE enviar ningún correo ni
notificación real: entrega únicamente el dato calculado y auditado. Ver el requisito «Ausencia de
envío real del aviso de códigos de recuperación de MFA bajos» para el destino, todavía sin
identificar, del envío real.

#### Escenario: Consumir el octavo código deja el aviso activado

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn` con diez códigos de recuperación de MFA, siete ya
  usados
- **CUANDO** usa el octavo código para completar un segundo factor pendiente
- **ENTONCES** el sistema calcula que quedan dos códigos sin usar
- **Y** audita la señal de aviso de códigos de recuperación de MFA bajos, con el conteo de dos

#### Escenario: Quedar con exactamente tres códigos no activa el aviso

- **DADO** la cuenta `jorge.aguilar@colegio.edu.hn` con diez códigos de recuperación de MFA, seis ya
  usados
- **CUANDO** usa el séptimo código
- **ENTONCES** el sistema calcula que quedan tres códigos sin usar
- **Y** no audita ninguna señal de aviso, porque el umbral exige quedar **por debajo** de tres, no
  en tres

### Requisito: Columna `mfa_required`, señal mínima y desnormalizada, con obligación de derivación real en el cambio 8

El sistema DEBE declarar en `identity_staff_account` la columna `mfa_required BOOLEAN NOT NULL`,
decidida explícitamente por quien crea la cuenta, sin modelar ningún concepto de rol ni de permiso,
porque ninguno de los dos existe todavía en el árbol de `identity`. Esta columna es un duplicado
desnormalizado de un dato que el cambio 8 (`rbac-permission-matrix-and-audit-integration`) va a
poseer: el permiso de escritura financiera o de configuración del rol de la cuenta. **El cambio 8
DEBE derivar `mfa_required` de ese permiso y eliminar la doble fuente**, antes de considerar cerrado
el requisito «MFA obligatoria para cuentas marcadas con `mfa_required`»; hasta que esa derivación
exista, un cambio de rol que otorgue un permiso financiero sin tocar esta columna deja el control
desactivado en silencio para esa cuenta, y ese silencio es exactamente lo que el cambio 8 debe
cerrar.

#### Escenario: La columna se fija al crear la cuenta, sin ningún dato de rol que la respalde

- **DADO** que no existe ningún concepto de rol ni de permiso en el esquema de `identity`
- **CUANDO** se crea la cuenta de personal `patricia.nunez@colegio.edu.hn` con `mfa_required` en
  `true`
- **ENTONCES** la cuenta queda persistida con `mfa_required = true`, decidido explícitamente por
  quien la creó
- **Y** ningún proceso de este cambio deriva ese valor de un rol o de un permiso

#### Escenario: Este cambio no deriva `mfa_required` de ningún permiso todavía

- **DADO** una cuenta hipotética cuyo rol futuro, una vez exista en el cambio 8, incluiría un
  permiso de escritura financiera
- **CUANDO** se crea esa cuenta en este cambio con `mfa_required` en `false`, por decisión de quien
  la creó
- **ENTONCES** el sistema no corrige ni deriva ese valor a `true` por ningún mecanismo automático,
  porque esa derivación es responsabilidad del cambio 8
- **Y** este escenario deja de ser cierto el día que el cambio 8 entregue la derivación real

### Requisito: Exhaustividad forzada por el compilador sobre los cuatro desenlaces de `AuthenticationResult`

El sistema DEBE consumir el resultado del caso de uso de autenticación con contraseña mediante un
`switch` exhaustivo sobre los cuatro desenlaces de `AuthenticationResult`, sin cláusula `default`,
en todo punto de producción que decida el efecto de ese resultado sobre el estado del retroceso o
sobre qué se audita. El sistema NO DEBE decidir ese efecto mediante `instanceof` ni mediante
cualquier otro mecanismo que no obligue al compilador a rechazar la construcción si
`AuthenticationResult` gana un desenlace nuevo sin que ese punto lo maneje.

#### Escenario: Un desenlace no manejado rompe la compilación, no el comportamiento en producción

- **DADO** un `switch` exhaustivo sin `default` sobre los cuatro desenlaces de
  `AuthenticationResult`, en el punto de `AuthenticateWithPassword` que decide el estado del
  retroceso
- **CUANDO** se elimina, en un fixture de prueba permanente, la rama que maneja
  `SecondFactorRequired`
- **ENTONCES** la compilación de ese fixture falla, porque el `switch` deja de ser exhaustivo
- **Y** por tanto ningún cambio futuro que agregue un desenlace puede tratarlo en silencio como
  `Rejected`

#### Escenario: `SecondFactorRequired` no avanza el contador de retroceso ni se audita como fallo

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn` con `mfa_required` en `true`, secreto TOTP ya
  inscrito, y su contador de retroceso en cero
- **CUANDO** el caso de uso produce el desenlace `SecondFactorRequired` para esa cuenta
- **ENTONCES** el `switch` exhaustivo que decide el efecto sobre el retroceso trata esa rama de
  forma distinta de `Rejected`
- **Y** el contador de retroceso de la cuenta permanece en cero
- **Y** la auditoría no registra un intento fallido para ese evento

### Requisito: Ningún secreto nuevo de este cambio es observable en registros, excepciones, `toString()` ni pruebas

El sistema NO DEBE hacer observable, en ningún mensaje de registro, mensaje de excepción,
representación textual (`toString()` o equivalente, incluida la generada automáticamente por un
`record` de Java) ni salida de una prueba fallida, el secreto TOTP en claro, un código de
recuperación de MFA en claro, la llave maestra (KEK) ni una llave de datos (DEK) en claro. Todo
objeto de valor que cargue alguno de esos cuatro secretos DEBE ser una clase final con un
`toString()` sobrescrito de forma explícita, nunca un `record` sin más.

#### Escenario: Ningún registro, excepción ni `toString()` expone alguno de los cuatro secretos nuevos

- **DADO** una inscripción de TOTP para `sofia.mejia@colegio.edu.hn` que genera un secreto TOTP, una
  operación de cifrado de columna que usa la llave maestra y una llave de datos, y una generación de
  diez códigos de recuperación de MFA
- **CUANDO** el sistema procesa cada operación y, en cualquier punto, produce un mensaje de
  registro, lanza una excepción, o invoca `toString()` sobre un objeto que carga alguno de los
  cuatro secretos
- **ENTONCES** ninguno de esos mensajes ni representaciones contiene el secreto TOTP en claro, el
  código de recuperación en claro, la llave maestra ni una llave de datos en claro
- **Y** eso se verifica por inspección del texto producido, nunca por confianza en el diseño

### Requisito: Ausencia de ejecución real de la rotación de la llave de datos (brecha con destino: cambio 9)

El sistema NO DEBE ejecutar, en este cambio, ningún recifrado por lotes de valores cifrados con una
llave de datos (DEK) marcada `retired`, porque el recifrado programado en lotes depende de
`db-scheduler` (ADR-0016), que todavía no existe en el árbol; su infraestructura es el **cambio 9**
(`background-jobs-with-db-scheduler`). Este cambio entrega el esquema que hace posible la rotación
—la columna de estado `active`/`retired` de la tabla de llaves de datos, usada desde el primer día
para decidir con qué llave se cifra cada valor nuevo— pero no la ejecución de la rotación.

#### Escenario: Ninguna DEK retirada se recifra automáticamente

- **DADO** una llave de datos (DEK) marcada `retired`, con al menos un valor cifrado todavía
  referenciándola
- **CUANDO** se inspecciona si algún trabajo programado la recifra con la DEK activa
- **ENTONCES** no existe ningún trabajo de ese tipo desplegado en este cambio
- **Y** el valor cifrado sigue descifrándose correctamente con la DEK `retired`, porque el estado
  `retired` no impide leer, solo impide cifrar valores nuevos con ella
- **Y** este escenario deja de ser cierto el día que el cambio 9 entregue el trabajo de rotación

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


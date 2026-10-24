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
mientras el segundo factor no se haya completado. Cuando la cuenta tiene una credencial TOTP **verificada**, el
sistema DEBE producir el desenlace `SecondFactorRequired`, pendiente de un código TOTP
válido. Cuando la cuenta no tiene ninguna credencial TOTP, o tiene una inscrita pero nunca verificada, el
sistema DEBE producir el desenlace `SecondFactorEnrollmentRequired`. Ninguno de los dos desenlaces DEBE hacer avanzar el
contador de retroceso por intentos fallidos, porque ninguno es un rechazo de credenciales. La traducción de
`SecondFactorRequired` y de `SecondFactorEnrollmentRequired` a un token restringido, y de la verificación
correcta a un token de acceso completo, la entrega este cambio en los endpoints de inicio de sesión y de
segundo factor.
(Previously: bastaba un secreto inscrito para `SecondFactorRequired`, y la traducción a tokens era
responsabilidad pendiente de `session-endpoints-cookie-and-csrf`.)

#### Escenario: Cuenta con `mfa_required` en `true` y secreto TOTP ya inscrito

- **DADO** la cuenta de personal `sofia.mejia@colegio.edu.hn`, con `mfa_required` en `true` y una
  credencial TOTP verificada
- **CUANDO** completa el inicio de sesión con contraseña correcta
- **ENTONCES** el sistema devuelve el desenlace `SecondFactorRequired`, pendiente de un código TOTP
  válido
- **Y** no devuelve el desenlace `Authenticated`
- **Y** el contador de retroceso por intentos fallidos de esa cuenta no avanza
- **Y** el endpoint de inicio de sesión lo traduce a un token restringido `mfa-verify`

#### Escenario: Cuenta con `mfa_required` en `true` sin ningún secreto TOTP inscrito

- **DADO** la cuenta de personal `jorge.aguilar@colegio.edu.hn`, con `mfa_required` en `true` y sin
  ningún secreto TOTP inscrito
- **CUANDO** completa el inicio de sesión con contraseña correcta
- **ENTONCES** el sistema devuelve el desenlace `SecondFactorEnrollmentRequired`
- **Y** no devuelve el desenlace `Authenticated`
- **Y** el contador de retroceso por intentos fallidos de esa cuenta no avanza
- **Y** el endpoint de inicio de sesión lo traduce a un token restringido `mfa-enroll`

#### Escenario: [I217] Cuenta con `mfa_required` en `true` y secreto inscrito pero nunca verificado

- **DADO** la cuenta `patricia.nunez@colegio.edu.hn`, con `mfa_required` en `true` y una credencial recién
  inscrita (contador -1)
- **CUANDO** completa el inicio de sesión con contraseña correcta
- **ENTONCES** el sistema devuelve `SecondFactorEnrollmentRequired` y el contador de retroceso no avanza

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
HTTP son responsabilidad de `password-recovery-endpoints`.
(Previously: esa responsabilidad figuraba como de `session-tokens-and-web-layer`; la partición de la
parte 4b la deja en el tercer cambio, `password-recovery-endpoints`.)

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

Una cuenta tiene MFA activa cuando tiene `mfa_required` en `true` **y** una credencial TOTP **verificada**,
que es la misma condición con la que el inicio de sesión devuelve `SecondFactorRequired`. Para una
cuenta con MFA activa, el sistema NO DEBE aceptar un restablecimiento que no presente, además del
token, un código TOTP válido o un código de recuperación de MFA sin usar (`docs/03-seguridad.md`
§4.7). El código de recuperación de MFA aceptado DEBE quedar consumido. Un restablecimiento
rechazado por falta de segundo factor NO DEBE consumir el token ni cambiar la contraseña.
(Previously: MFA activa exigía un secreto TOTP inscrito, aunque nunca se hubiera verificado.)

#### Escenario: Sin segundo factor no hay restablecimiento, y con un código TOTP válido sí

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn`, con `mfa_required` en `true`, una credencial TOTP
  verificada y el token vivo `PRT-33e1` emitido a las 09:00:00 del 2026-10-12
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
secreto inscrito, o con un secreto inscrito pero nunca verificado: en su siguiente inicio de sesión con la
contraseña nueva, el sistema DEBE devolver
`SecondFactorEnrollmentRequired`, de modo que la inscripción se sigue exigiendo. También incluye una
cuenta con `mfa_required` en `false` que tenga un secreto inscrito, igual que en el inicio de sesión
(respuestas del propietario del 2026-09-30).
(Previously: solo se nombraba la cuenta sin secreto inscrito; una credencial nunca verificada contaba como MFA
activa.)

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

#### Escenario: [I218] Una credencial nunca verificada restablece solo con el enlace

- **DADO** la cuenta `patricia.nunez@colegio.edu.hn`, con `mfa_required` en `true`, una credencial inscrita
  y nunca verificada, y un token vivo
- **CUANDO** restablece con el token y la contraseña nueva, sin ningún código
- **ENTONCES** el sistema acepta el restablecimiento
- **Y** el siguiente inicio de sesión devuelve `SecondFactorEnrollmentRequired`

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
9. La IP de la notificación la aporta además `session-endpoints-cookie-and-csrf`, con la propagación del
origen de la petición a los comandos de identidad.
(Previously: la IP de la notificación figuraba como aportada por `session-tokens-and-web-layer`.)

#### Escenario: El puerto de envío no tiene adaptador de producción

- **DADO** el árbol de clases de producción del módulo de identidad
- **CUANDO** se inspecciona qué clases implementan el puerto de envío del enlace
- **ENTONCES** ninguna lo hace
- **Y** esta comprobación falla el día que exista un adaptador sin retirarla

#### Escenario: Un restablecimiento completado no notifica a nadie

- **DADO** un restablecimiento completado para `ana.martinez@colegio.edu.hn`
- **CUANDO** se inspeccionan los asientos de auditoría que produjo
- **ENTONCES** ninguno nombra una notificación, un correo ni un intento de entrega

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

### Requisito: El token de acceso es un JWS compacto firmado con EdDSA, con cabecera cerrada

El sistema DEBE emitir el token de acceso del dominio de personal como un JWS en serialización
compacta, firmado con Ed25519 (`EdDSA`) mediante la implementación de firma del JDK y sin dependencia
de terceros. La cabecera protegida DEBE contener exactamente dos miembros: `alg` con el valor `EdDSA` y
`kid` con el identificador de la clave que firmó. La cabecera DEBE ser exactamente la que el sistema
emite para ese `kid`, `{"alg":"EdDSA","kid":"<kid>"}`, con los miembros en ese orden y sin espacios; el
sistema NO DEBE aceptar en la verificación ningún otro algoritmo, ningún otro miembro de cabecera ni otra
serialización de la misma cabecera, aunque la firma sea válida.

#### Escenario: [I46] La primitiva de firma verifica el vector de RFC 8037 y firma con claves generadas

- **DADO** la clave pública Ed25519, la entrada de firma y la firma publicadas en el Apéndice A.4 de RFC
  8037 (sin la clave privada, que no se versiona), y un par Ed25519 generado al ejecutar la prueba
- **CUANDO** se verifica la firma publicada con la clave pública del apéndice, y se firma una entrada con
  la clave generada
- **ENTONCES** la verificación de la firma publicada tiene éxito y la de esa firma con un solo bit
  alterado falla
- **Y** la firma generada se verifica con su clave pública, es determinista (dos firmas de la misma
  entrada son idénticas) y no se verifica con la clave pública del apéndice

#### Escenario: [I47] Un token emitido se verifica y su cabecera tiene solo `alg` y `kid`

- **DADO** una clave de firma con `kid` `admin-2026a` en el anillo de claves
- **CUANDO** se emite un token de acceso y se decodifica su cabecera sin verificarla
- **ENTONCES** la cabecera contiene exactamente `alg` con `EdDSA` y `kid` con `admin-2026a`, y ningún
  otro miembro
- **Y** el verificador acepta el token

#### Escenario: [I48] Confusión de algoritmo: `none`, `HS256`, `RS256`, `ES256` y variantes de `EdDSA`

- **DADO** un token con las claims correctas y el `kid` de la clave vigente, construido una vez con
  cada uno de estos valores de `alg`: `none` (sin firma), `HS256` firmado con los bytes de la clave
  pública como secreto del HMAC, `RS256`, `ES256` y `eddsa` (minúsculas)
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los cinco se rechazan como inválidos, sin que ninguno llegue a evaluar sus claims

#### Escenario: [I49] `alg` ausente o duplicado

- **DADO** un token cuya cabecera no declara `alg`, y otro cuya cabecera declara `alg` dos veces con
  el mismo valor `EdDSA`, ambos con firma válida de la clave vigente
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** ambos se rechazan como inválidos

#### Escenario: [I50] `crit` y cualquier cabecera adicional se rechazan aunque la firma sea válida

- **DADO** seis tokens firmados con la clave vigente, cada uno con un miembro de cabecera adicional:
  `crit`, `jku`, `jwk`, `x5u`, `typ` y `b64`
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los seis se rechazan como inválidos
- **Y** un séptimo token firmado con la misma clave y solo `alg` y `kid` se acepta

#### Escenario: [I147] Una cabecera semánticamente equivalente pero con otra serialización se rechaza

- **DADO** tres tokens firmados con la clave vigente, cuyas cabeceras contienen solo `alg` con `EdDSA` y
  `kid` con el de la clave vigente, pero con los miembros en orden inverso, con un espacio tras los dos
  puntos y con `EdDSA` seguido de una secuencia de escape Unicode equivalente
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los tres se rechazan como inválidos
- **Y** el token emitido por el sistema con la misma clave se acepta

### Requisito: El `kid` es obligatorio y selecciona la única clave de verificación

El sistema DEBE exigir el miembro `kid` en la cabecera, DEBE verificar la firma únicamente con la clave
del anillo cuyo `kid` coincide, y NO DEBE probar otras claves del anillo cuando no hay coincidencia. Un
`kid` ausente, desconocido, que no sea una cadena o vacío DEBE rechazar el token como inválido.

#### Escenario: [I51] `kid` ausente

- **DADO** un token con `alg` `EdDSA`, sin `kid` y con firma válida de la clave vigente
- **CUANDO** el verificador lo procesa
- **ENTONCES** se rechaza como inválido

#### Escenario: [I52] `kid` desconocido, vacío o de tipo incorrecto

- **DADO** tres tokens firmados con la clave vigente, con `kid` igual a `no-existe`, a la cadena vacía
  y al número `7`
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los tres se rechazan como inválidos

#### Escenario: [I53] No hay recurso a otras claves del anillo

- **DADO** un anillo con dos claves, `admin-2026a` y `admin-2026b`, y un token firmado con la clave
  `admin-2026b` pero con `kid` igual a `admin-2026a`
- **CUANDO** el verificador lo procesa
- **ENTONCES** se rechaza como inválido, aunque la clave que lo firmó pertenece al anillo

#### Escenario: [I54] Firma de una clave ajena con un `kid` conocido

- **DADO** un token con el `kid` de la clave vigente, firmado con una clave Ed25519 que no pertenece al
  anillo
- **CUANDO** el verificador lo procesa
- **ENTONCES** se rechaza como inválido por firma

### Requisito: El token usa base64url estricto sin relleno y tres segmentos exactos

El sistema DEBE aceptar únicamente tokens de a lo sumo 2048 caracteres, de tres segmentos no vacíos
separados por un punto, cada uno en base64url sin relleno y en su forma canónica, cuyo cuerpo decodifique a
un objeto JSON sin nombres de miembro repetidos y cuya firma tenga exactamente 64 bytes. El cuerpo
NO DEBE analizarse antes de verificar la firma. Una firma maleable (con el escalar `S` aumentado en el
orden `L` del grupo, de modo que sigue cabiendo en 32 bytes) y una firma de longitud distinta de 64 bytes
DEBEN terminar como token inválido, sin que la excepción que lanza la primitiva de firma del JDK
escape como error interno. Todo lo demás DEBE rechazarse como inválido.

#### Escenario: [I55] Relleno, alfabeto estándar, espacios y bits sobrantes no canónicos

- **DADO** tokens con firma válida a los que se les aplica, uno por vez: relleno `=` en un segmento, un
  carácter `+` o `/` del alfabeto estándar, un espacio o un salto de línea intercalado, y un último
  carácter de segmento cuyos bits sobrantes no son cero
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los cuatro se rechazan como inválidos

#### Escenario: [I56] Número de segmentos y firma de longitud incorrecta

- **DADO** un token de dos segmentos, otro de cuatro, otro con la firma vacía, otro con una firma de
  63 bytes, otro con una firma de 65 bytes y otro de 2049 caracteres
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los seis se rechazan como inválidos

#### Escenario: [I57] Cabecera o cuerpo que no son un objeto JSON, o con miembros repetidos

- **DADO** un token cuya cabecera decodifica a un arreglo JSON, otro cuyo cuerpo decodifica a un
  número, y otro cuyo cuerpo repite el nombre de la claim `sub`, todos con firma válida
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los tres se rechazan como inválidos

#### Escenario: [I138] Una firma maleable o de 63 bytes termina como token inválido, no como error interno

- **DADO** un token emitido por el sistema al que se le sustituye la firma, una vez, por la firma
  maleable obtenida sumando el orden `L` del grupo al escalar `S` de la firma original (de modo que sigue
  teniendo 64 bytes), y otra vez por una firma de 63 bytes
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** ambos desenlaces son `Invalid`
- **Y** ninguno deja escapar una excepción del verificador, aunque la primitiva de firma del JDK lance
  una excepción en lugar de devolver `false`

### Requisito: Las claims del token de acceso son una lista cerrada, sin datos personales y sin `permissions`

El sistema DEBE emitir el token de acceso con exactamente estas claims: `iss`, `aud`, `sub`, `exp`,
`iat`, `jti`, `sid`, `tenant` y `amr`, y ninguna otra. `iss` y `aud` DEBEN valer `confia-admin`, con `aud` como cadena y no como arreglo; `sub`
DEBE ser el identificador opaco de la cuenta; `sid` el identificador de la familia de sesión; `tenant`
el identificador de la institución; `jti` DEBE ser distinto en cada token; `amr` DEBE ser un arreglo
que contiene `pwd` y, solo si se completó el segundo factor, `otp`, en ese orden. La diferencia `exp - iat` DEBE ser
de 600 segundos. El token NO DEBE contener ningún dato personal (nombre, correo, documento de
identidad ni dato de un estudiante). El token NO DEBE contener la claim `permissions` hasta el cambio 8
(decisión 11 del propietario), porque un conjunto vacío podría leerse como autoritativo.

#### Escenario: [I58] La lista de claims es cerrada

- **DADO** un token de acceso emitido para la cuenta `maria.lopez@colegio.edu.hn`
- **CUANDO** se decodifica su cuerpo
- **ENTONCES** el conjunto de nombres de claims es exactamente `iss`, `aud`, `sub`, `exp`, `iat`, `jti`,
  `sid`, `tenant` y `amr`
- **Y** `iss` y `aud` valen `confia-admin`

#### Escenario: [I59] Vida de 600 segundos y audiencia del proceso emisor (ADR-0005, prueba 3)

- **DADO** un token emitido a las 10:00:00 del 2026-10-12
- **CUANDO** se decodifica
- **ENTONCES** `exp - iat` es 600 y `exp` corresponde a las 10:10:00
- **Y** `aud` coincide con la audiencia del proceso emisor

#### Escenario: [I60] Ningún dato personal ni `permissions` en el token

- **DADO** un token emitido para `maria.lopez@colegio.edu.hn`, cuyo nombre completo, correo y
  documento de identidad son conocidos por la prueba
- **CUANDO** se decodifican cabecera y cuerpo y se busca cada uno de esos valores
- **ENTONCES** ninguno aparece, y `sub` es un identificador opaco que no contiene el correo
- **Y** el cuerpo no contiene la claim `permissions`

#### Escenario: [I61] `amr` refleja el segundo factor completado

- **DADO** una sesión iniciada solo con contraseña y otra cuyo segundo factor se completó
- **CUANDO** se emite el token de cada una
- **ENTONCES** el `amr` de la primera es `[pwd]` y el de la segunda es `[pwd, otp]`, en ese orden

#### Escenario: [I62] `jti` distinto en cada emisión

- **DADO** mil emisiones sucesivas para la misma sesión
- **CUANDO** se recolectan sus `jti`
- **ENTONCES** son mil valores distintos

### Requisito: La verificación valida emisor, audiencia, vigencia y claims declaradas

El verificador DEBE rechazar un token cuyo `iss` o cuya `aud` no sea `confia-admin`, al que le falte
cualquiera de las claims declaradas, cuyas claims tengan un tipo distinto del declarado (`exp` y `iat`
numéricos, `sub`, `sid`, `tenant` y `jti` como UUID en forma canónica, `amr` como arreglo de cadenas) o que contenga
cualquier claim no declarada, entre ellas `permissions`. Un token DEBE aceptarse en un instante
estrictamente anterior a `exp` y NO DEBE aceptarse en `exp` ni después; el verificador NO DEBE aplicar
tolerancia de reloj sobre `exp`. Un `iat` posterior en más de 60 segundos al instante de la verificación
DEBE rechazarse, y uno posterior en 60 segundos o menos DEBE aceptarse. Una diferencia `exp - iat` distinta
de 600 segundos DEBE rechazarse. El reloj DEBE inyectarse.

#### Escenario: [I63] El borde de `exp` es estricto

- **DADO** un token con `exp` igual a las 10:10:00 del 2026-10-12
- **CUANDO** se verifica a las 10:09:59 y a las 10:10:00 del mismo día
- **ENTONCES** se acepta a las 10:09:59 y se rechaza a las 10:10:00

#### Escenario: [I64] Emisor o audiencia incorrectos

- **DADO** dos tokens firmados con la clave vigente, uno con `iss` igual a `confia-portal` y otro con
  `aud` igual a `confia-portal`
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** ambos se rechazan como inválidos

#### Escenario: [I65] Falta de una claim declarada

- **DADO** nueve tokens firmados con la clave vigente, cada uno sin una de las nueve claims declaradas
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los nueve se rechazan como inválidos

#### Escenario: [I66] Tipo incorrecto de una claim

- **DADO** tokens firmados con la clave vigente con `exp` como cadena, `tenant` que no es un UUID,
  `sid` que no es un UUID, `amr` que no es un arreglo, `aud` como arreglo de un elemento
  `["confia-admin"]` y `sub` con valor nulo
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los seis se rechazan como inválidos

#### Escenario: [I67] Una claim no declarada se rechaza, incluida `permissions`

- **DADO** tres tokens firmados con la clave vigente y las nueve claims correctas, cada uno con una
  claim adicional: `permissions` con un arreglo vacío, `role` y `nbf`
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** los tres se rechazan como inválidos

#### Escenario: [I139] Reglas de tiempo de emisión y de duración exacta

- **DADO** el instante de verificación 10:00:00 del 2026-10-12 y tokens firmados con la clave vigente con
  `iat` a las 10:01:00, `iat` a las 10:01:01, `exp - iat` igual a 601 segundos y `exp - iat` igual a 599
  segundos (siendo `exp` posterior al instante en los cuatro)
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** el primero se acepta
- **Y** los otros tres se rechazan como inválidos

### Requisito: Un token de otro dominio de identidad se rechaza por firma o por audiencia, nunca por permiso

El sistema NO DEBE aceptar en el proceso administrativo un token firmado con una clave que no
pertenece a su anillo ni uno cuya audiencia no sea la administrativa, aunque esté vigente en su propio
dominio (ADR-0003; ADR-0005, verificación 4). El rechazo DEBE ocurrir en la verificación del token,
antes de que se evalúe ninguna autorización.

#### Escenario: [I68] Token firmado con la clave del portal

- **DADO** un token con todas las claims correctas y `aud` igual a `confia-portal`, firmado con una
  clave Ed25519 distinta de las del anillo administrativo, con un `kid` desconocido para el anillo
- **CUANDO** el verificador administrativo lo procesa
- **ENTONCES** se rechaza como inválido por `kid` desconocido

#### Escenario: [I69] Token con el `kid` administrativo pero firmado con la clave ajena

- **DADO** el mismo token firmado con la clave ajena pero con el `kid` de la clave administrativa
  vigente
- **CUANDO** el verificador administrativo lo procesa
- **ENTONCES** se rechaza como inválido por firma, y el rechazo no consulta ningún permiso

#### Escenario: [I70] Token firmado con la clave administrativa pero con audiencia del portal

- **DADO** un token firmado con la clave administrativa vigente con `aud` igual a `confia-portal`
- **CUANDO** el verificador administrativo lo procesa
- **ENTONCES** se rechaza como inválido por audiencia

### Requisito: El resultado de la verificación es tipado y no revela la causa del rechazo

El verificador DEBE devolver un resultado cerrado con exactamente tres desenlaces: `Valid`, que lleva las
claims; `Expired`, solo para un token íntegro (firma, estructura, emisor y audiencia correctos) cuyo
`exp` ya pasó; e `Invalid`, para todo lo demás, sin ningún dato observable de la causa. La firma DEBE
comprobarse antes que la vigencia. Ninguna excepción no prevista DEBE escapar del verificador ante una
entrada mal formada, vacía o nula, ni siquiera la que lanza la primitiva de firma del JDK ante una firma
maleable o de longitud incorrecta: todo eso termina como `Invalid`, nunca como un error interno. El
verificador NO DEBE registrar el contenido del token. (Si el desenlace se entrega como valor o como un
rechazo tipado que solo distingue «vencido» de «inválido» lo decide el diseño; lo observable es el
desenlace.)

#### Escenario: [I71] Un token íntegro y vencido produce `Expired`

- **DADO** un token firmado con la clave vigente, con emisor y audiencia correctos, y `exp` igual a las
  10:10:00 del 2026-10-12
- **CUANDO** se verifica a las 10:30:00 del mismo día
- **ENTONCES** el desenlace es `Expired`

#### Escenario: [I72] Un token vencido con firma o audiencia incorrectas produce `Invalid`

- **DADO** un token vencido firmado con una clave ajena, y otro vencido con `aud` igual a
  `confia-portal` firmado con la clave vigente
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** ambos desenlaces son `Invalid`, nunca `Expired`

#### Escenario: [I73] Entradas basura terminan como token inválido, sin excepción no prevista

- **DADO** como token una cadena vacía, `null`, una cadena de diez mil caracteres `A`, una cadena con
  bytes nulos y un token con un segmento de megabytes
- **CUANDO** el verificador procesa cada una
- **ENTONCES** todas producen `Invalid` y ninguna deja escapar una excepción no prevista

### Requisito: El token restringido de MFA es de otro tipo, de vida corta y sin familia

El sistema DEBE poder emitir y verificar un token restringido de MFA, cuya vida DEBE ser exactamente de
300 segundos (`exp - iat`). Sus claims DEBEN ser exactamente `iss`, `aud`, `sub`, `exp`, `iat`, `jti`,
`tenant`, `amr` y `purpose`, cuyo valor DEBE ser `mfa-verify` o `mfa-enroll`; `iss` DEBE valer
`confia-admin` y `aud` DEBE valer `confia-admin-mfa`, una audiencia distinta de la del token de acceso;
`amr` DEBE ser `[pwd]` y NO DEBE existir `sid`. Emitirlo NO DEBE crear ninguna familia ni ningún token de
refresco. La verificación del token restringido NO DEBE aceptar un token de acceso, y la del token de
acceso NO DEBE aceptar uno restringido; el rechazo del restringido por el verificador de acceso DEBE
producirse por su audiencia, sin depender de que alguien consulte `purpose`. Los endpoints de inicio de sesión
lo entregan y solo los de verificación e inscripción del segundo factor lo aceptan (`web-edge`).
(Previously: ningún endpoint lo entregaba; los endpoints eran de `session-endpoints-cookie-and-csrf`.)

#### Escenario: [I74] Claims y vida del token restringido

- **DADO** un token restringido emitido a las 10:00:00 del 2026-10-12 con uso `mfa-verify`
- **CUANDO** se decodifica
- **ENTONCES** sus claims son exactamente las declaradas, `amr` es `[pwd]`, `aud` es
  `confia-admin-mfa`, no hay `sid`, y `exp - iat` es exactamente 300 segundos

#### Escenario: [I75] Emitir un token restringido no crea ninguna familia

- **DADO** el almacén de familias y de tokens de refresco con un número conocido de filas
- **CUANDO** se emiten diez tokens restringidos
- **ENTONCES** el número de filas de familias y de tokens de refresco no cambió

#### Escenario: [I76] Los dos tipos de token no son intercambiables

- **DADO** un token de acceso válido y un token restringido válido
- **CUANDO** cada uno se presenta a la verificación del otro tipo
- **ENTONCES** ambos desenlaces son `Invalid`
- **Y** el token restringido presentado a la verificación del token de acceso se rechaza por su
  audiencia `confia-admin-mfa`, aunque su firma y sus claims sean íntegros

#### Escenario: [I77] El uso del token restringido pertenece a un conjunto cerrado

- **DADO** un token restringido firmado con la clave vigente cuyo uso es `admin` y otro cuyo uso falta
- **CUANDO** el verificador procesa cada uno
- **ENTONCES** ambos desenlaces son `Invalid`

### Requisito: El anillo de claves tiene una o dos claves con `kid` y se carga desde el entorno propio del dominio

El sistema DEBE cargar el anillo de claves administrativo desde variables de entorno propias del
dominio administrativo. El anillo DEBE contener una o dos claves Ed25519, cada una con un `kid` único;
exactamente una (la vigente) DEBE ser la clave de firma, con su par de clave privada y pública, y la otra
(la anterior), si existe, solo verifica y NUNCA lleva clave privada (rotación semestral de
`docs/03-seguridad.md` §4.5). El `kid` DEBE tener entre 1 y 64 caracteres de `A-Z`, `a-z`, `0-9`, `.`, `_`
y `-`. El arranque DEBE fallar si falta la clave vigente (anillo vacío), si el `kid` no tiene ese
formato, si el `kid` o la clave pública de la anterior repiten los de la vigente, si falta la clave
pública de la anterior declarada, si un valor no es una clave Ed25519 válida (por ejemplo, una clave
Ed448) o si la clave pública vigente no corresponde a su clave privada. El mensaje de error DEBE nombrar
la variable y NO DEBE contener ningún material de clave ni la causa encadenada de la excepción de
decodificación.

#### Escenario: [I78] El token se firma con la clave de firma y verifica con cualquiera de las dos

- **DADO** un anillo con `admin-2026a` como clave de firma y `admin-2026b` como clave de solo
  verificación, y un token firmado antes con `admin-2026b`
- **CUANDO** se emite un token nuevo y se verifican los dos
- **ENTONCES** el token nuevo lleva `kid` `admin-2026a`
- **Y** los dos tokens se aceptan

#### Escenario: [I79] La rotación retira la clave anterior

- **DADO** el anillo del escenario anterior, después de retirar `admin-2026b`
- **CUANDO** se presenta el token firmado con `admin-2026b`
- **ENTONCES** se rechaza como inválido por `kid` desconocido

#### Escenario: [I80] El arranque falla con un anillo mal formado

- **DADO** configuraciones sin clave vigente, con un `kid` de formato inválido, con una anterior de
  igual `kid` que la vigente, con una anterior de igual clave pública que la vigente, con una anterior
  sin clave pública, con una clave privada vigente que es Ed448 en lugar de Ed25519, con un Base64
  inválido y con una clave pública que no corresponde a la privada vigente
- **CUANDO** arranca el proceso administrativo con cada una
- **ENTONCES** las ocho fallan el arranque con un mensaje que nombra la variable
- **Y** ningún mensaje contiene material de clave, verificado por inspección del texto producido

### Requisito: Cada proceso verifica sus claves al arrancar y aborta si falta la suya o sobra la del otro dominio

La verificación DEBE ocurrir antes de crear el contexto de la aplicación y DEBE cumplir esta tabla
(ADR-0005, verificación 14):

| Proceso | Comprobación |
|---|---|
| administrativo | su clave privada de firma está presente y es válida (se valida al construir el anillo) |
| administrativo | ninguna propiedad `confia.security.portal-signing.*.private-key` está presente: el nombre se reserva desde ahora para la clave privada del portal (decisión D-N2 del propietario) |
| portal y trabajador | ni `confia.security.admin-signing.current.private-key` ni `confia.security.admin-signing.previous.private-key` están presentes (decisión 9 del propietario) |

La presencia DEBE detectarse venga de una variable de entorno, de una propiedad del sistema o de un
argumento del proceso, con el enlace relajado de nombres de Spring. En este cambio el portal y el
trabajador no cargan ningún par de claves propio, y el nombre reservado del portal no carga ninguna clave:
solo hace fallar al proceso administrativo si aparece. El mensaje de aborto DEBE nombrar la propiedad y NO
DEBE contener el valor.

#### Escenario: [I81] Administración sin clave no arranca

- **DADO** el proceso administrativo arrancado por su camino de producción sin la variable de su clave
  privada
- **CUANDO** se construye su contexto
- **ENTONCES** el arranque aborta con un mensaje que nombra la variable

#### Escenario: [I82] Administración con clave arranca

- **DADO** el proceso administrativo con una clave privada válida generada por la prueba
- **CUANDO** se construye su contexto
- **ENTONCES** el contexto arranca y el anillo contiene su clave

#### Escenario: [I83] El portal y el trabajador abortan si encuentran la clave privada administrativa

- **DADO** los procesos del portal y del trabajador, cada uno con, por separado, la propiedad de la clave
  privada administrativa vigente y la de la clave privada administrativa anterior presentes en su entorno
- **CUANDO** se construye cada contexto
- **ENTONCES** los cuatro casos abortan el arranque con un mensaje que nombra la propiedad y no contiene
  su valor

#### Escenario: [I84] El portal y el trabajador arrancan sin la clave administrativa

- **DADO** los procesos del portal y del trabajador con el entorno por omisión
- **CUANDO** se construye cada contexto
- **ENTONCES** los dos arrancan, y ninguno contiene un anillo de claves ni un verificador de tokens

#### Escenario: [I140] Administración no arranca si aparece el nombre reservado de la clave privada del portal

- **DADO** el proceso administrativo con una clave de firma válida y, además, una propiedad
  `confia.security.portal-signing.current.private-key` presente en su entorno, y otra ejecución con
  `confia.security.portal-signing.previous.private-key`
- **CUANDO** arranca cada una
- **ENTONCES** las dos abortan antes de crear el contexto, con un mensaje que nombra la propiedad y no
  contiene su valor
- **Y** sin esas propiedades el proceso administrativo arranca, y ninguna clave se carga a partir del
  nombre reservado

### Requisito: Ningún secreto de sesión es observable en registros, excepciones, `toString()` ni pruebas

El sistema NO DEBE hacer observable, en ningún mensaje de registro, mensaje de excepción,
representación textual (`toString()` o equivalente, incluida la generada por un `record` de Java) ni
salida de una prueba fallida, el token de acceso, el token de refresco en claro, el material de una
clave de firma ni el sucesor de un token de refresco, en claro o cifrado. Todo objeto de valor que
cargue alguno de ellos DEBE ser una clase final con un `toString()` sobrescrito de forma explícita.

#### Escenario: [I85] Ni los tokens ni las claves ni el sucesor aparecen en ningún texto producido

- **DADO** una emisión de familia, una rotación, un reintento en gracia y una reutilización detectada
  para `maria.lopez@colegio.edu.hn`, con captura de todos los registros
- **CUANDO** el sistema procesa cada operación y, en cualquier punto, produce un mensaje de registro,
  lanza una excepción o invoca `toString()` sobre un objeto que carga alguno de los secretos
- **ENTONCES** ninguno de esos textos contiene el token de acceso, el refresco en claro, el sucesor en
  claro o cifrado ni la clave privada de firma

#### Escenario: [I86] La prueba de redacción detecta una fuga reintroducida

- **DADO** la prueba del escenario anterior
- **CUANDO** se ejecuta contra una variante de control en la que el objeto que carga el refresco expone
  su valor en `toString()`
- **ENTONCES** la prueba falla, lo que demuestra que alcanza el caso peligroso

### Requisito: La emisión de una familia de refresco crea la familia y su primer token en una transacción con su auditoría

El sistema DEBE ofrecer un caso de uso de emisión que, para una cuenta de personal y una institución,
cree una familia de sesión y su primer token de refresco dentro de una misma transacción, junto con el
asiento de auditoría de la emisión. La familia DEBE registrar la institución, la cuenta, la huella del
agente de usuario, la IP inicial, el instante de creación, `last_used_at` igual al de creación y
`absolute_expires_at` igual a creación más 12 horas, sin revocación. El token de refresco DEBE ser
opaco, de 32 bytes aleatorios de un generador criptográfico codificados en base64url sin relleno (43
caracteres), DEBE entregarse al llamador una única vez y DEBE almacenarse únicamente como su SHA-256; su
vencimiento DEBE ser el menor entre la emisión más 8 horas y el vencimiento absoluto de la familia. Si la
transacción no confirma, ni la familia, ni el token, ni el asiento DEBEN sobrevivir. Los endpoints de inicio de
sesión y de verificación del segundo factor invocan la emisión.
(Previously: solo el código de pruebas invocaba la emisión; el endpoint que la usa era de
`session-endpoints-cookie-and-csrf`.)

#### Escenario: [I87] Los valores de la emisión

- **DADO** la cuenta `maria.lopez@colegio.edu.hn` y una emisión a las 08:00:00 del 2026-10-12
- **CUANDO** el caso de uso termina
- **ENTONCES** existe una familia con `created_at` y `last_used_at` iguales a las 08:00:00,
  `absolute_expires_at` igual a las 20:00:00 y sin revocación
- **Y** existe un token de refresco con `expires_at` igual a las 16:00:00 y sin consumir
- **Y** el llamador recibió el refresco una única vez, de 43 caracteres base64url sin relleno que
  decodifican a 32 bytes

#### Escenario: [I88] El valor almacenado no es el entregado (ADR-0005, prueba 5)

- **DADO** el refresco entregado por la emisión del escenario anterior
- **CUANDO** se consulta con SQL crudo la tabla de tokens de refresco
- **ENTONCES** la fila contiene el SHA-256 del refresco entregado y ninguna columna de ninguna fila
  contiene el refresco en claro
- **Y** dos emisiones sucesivas entregan refrescos distintos

#### Escenario: [I89] La emisión y su asiento se confirman o revierten juntos

- **DADO** una emisión procesada dentro de una transacción que a continuación falla de forma
  determinista antes de confirmar
- **CUANDO** se inspecciona el estado tras la reversión
- **ENTONCES** no existe la familia, ni el token, ni el asiento de auditoría de esa emisión

#### Escenario: [I90] El asiento de emisión existe y no contiene secretos

- **DADO** una emisión confirmada
- **CUANDO** se lee el asiento de auditoría que produjo
- **ENTONCES** existe exactamente un asiento de emisión con la familia como entidad
- **Y** ningún campo del asiento contiene el refresco ni su SHA-256

### Requisito: La rotación consume el token con bloqueo de la familia y devuelve un desenlace tipado

El sistema DEBE ofrecer un caso de uso de rotación que, ante un token de refresco presentado, bloquee la
fila de su familia y devuelva un resultado cerrado con exactamente dos desenlaces: `Rotated`, que lleva la
sesión emitida (el token de refresco sucesor, su vencimiento y un token de acceso nuevo de la misma
familia) y un tipo, `fresh` o `grace`; y `Rejected`, con un motivo cerrado: `unknown_token`,
`family_revoked`, `family_expired` (por vencimiento absoluto o por inactividad), `token_expired` o
`reuse_detected`. El caso de uso NO DEBE lanzar una excepción por ninguno de ellos. El orden de
evaluación DEBE ser: familia revocada; familia vencida (absoluta o por inactividad); token ya consumido
(gracia o reutilización); vencimiento propio del token; rotación normal. Por tanto, un token consumido
presentado a una familia vencida produce `family_expired` y no `reuse_detected`. En una rotación normal
(`fresh`) DEBE marcar el token presentado como consumido con el instante y con la huella del agente de
usuario de quien lo presentó, emitir un token nuevo en la misma familia enlazado como sucesor, con
vencimiento igual al menor entre el instante más 8 horas y el vencimiento absoluto, avanzar
`last_used_at` al instante de la rotación y escribir el asiento de la rotación, todo en una transacción.
Un texto que no puede ser un token emitido y un token desconocido DEBEN producir `Rejected` con motivo
`unknown_token` sin alterar ninguna familia. Un token de otra institución NO DEBE ser visible para la
sesión de esta. Dos rotaciones simultáneas del mismo token DEBEN serializarse por el bloqueo de la familia. En esta especificación, `GraceReplayed` abrevia `Rotated` de tipo `grace` y `ReuseDetected`
abrevia `Rejected` con motivo `reuse_detected`.

#### Escenario: [I91] Rotación normal

- **DADO** el token `RT-1001` de una familia sin revocar, emitido a las 09:00:00 del 2026-10-12
- **CUANDO** se presenta a las 09:15:00 del mismo día
- **ENTONCES** el desenlace es `Rotated` de tipo `fresh` con un token nuevo `RT-1002` de la misma familia
  y un token de acceso nuevo cuyo `sid` es esa familia
- **Y** `RT-1001` queda consumido a las 09:15:00 y enlazado a `RT-1002` como su sucesor
- **Y** `last_used_at` de la familia es las 09:15:00 y el asiento de rotación existe

#### Escenario: [I92] Token desconocido

- **DADO** un valor de refresco que nunca se emitió, y un texto que no puede ser un token emitido (vacío,
  de longitud distinta de 43 o con caracteres fuera de base64url)
- **CUANDO** se presenta cada uno a la rotación
- **ENTONCES** el desenlace es `Rejected` con motivo `unknown_token` en los dos casos
- **Y** ninguna familia ni ningún token cambió

#### Escenario: [I93] Token de una familia revocada

- **DADO** el token vigente `RT-2001` de una familia ya revocada con el motivo `password_change`
- **CUANDO** se presenta a la rotación
- **ENTONCES** el desenlace es `Rejected` con motivo `family_revoked` y el estado de la familia no cambia

#### Escenario: [I94] El token de otra institución no se ve

- **DADO** el token vigente `RT-3001` de una familia de la institución A
- **CUANDO** se presenta a la rotación con el contexto de sesión de la institución B
- **ENTONCES** el desenlace es `Rejected` con motivo `unknown_token`, igual que un token inexistente

#### Escenario: [I95] Dos rotaciones concurrentes del mismo token producen exactamente un sucesor

- **DADO** el token vigente `RT-4001`, y dos transacciones reales y sincronizadas con
  `CountDownLatch` y una espera acotada sobre `pg_stat_activity` que lo presentan en el mismo instante del reloj inyectado, con el mismo agente de
  usuario
- **CUANDO** terminan las dos
- **ENTONCES** existe exactamente un token sucesor de `RT-4001`
- **Y** un desenlace es `Rotated` de tipo `fresh` y el otro `Rotated` de tipo `grace`, con el mismo valor
  de sucesor

#### Escenario: [I143] Dos presentaciones simultáneas del mismo token desde dispositivos distintos

- **DADO** el token vigente `RT-4101`, y dos transacciones reales y sincronizadas con `CountDownLatch` y una espera acotada sobre `pg_stat_activity`
  que lo presentan en el mismo instante del reloj inyectado, con huellas de agente de usuario distintas
- **CUANDO** terminan las dos
- **ENTONCES** un desenlace es `Rotated` de tipo `fresh` y el otro es `ReuseDetected`
- **Y** la familia queda revocada con `reuse_detected` y ningún token de la familia está vigente

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
rechazo por familia vencida; la purga del resto es del cambio 9. La familia «ha avanzado» cuando el
sucesor ya fue consumido. Un agente de usuario ausente en ambas presentaciones se trata como el mismo
dispositivo.

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
- **Y** el valor cifrado siguió en la fila hasta ese contacto, como limitación declarada hasta la purga
  del cambio 9

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

### Requisito: La reutilización fuera de la gracia revoca la familia, audita y se devuelve como desenlace

Cuando un token consumido se presenta fuera de la ventana de gracia, desde otro dispositivo o con la
familia ya avanzada, el sistema DEBE revocar la familia con el motivo `reuse_detected`, dejar sin
ningún token vigente a todos los de esa familia, escribir un asiento de auditoría que lo registre como
posible fuga de token, y devolver el desenlace `ReuseDetected`. La revocación y el asiento DEBEN
confirmarse aunque la petición se rechace: el caso de uso NO DEBE lanzar una excepción que los
revierta. La reutilización NO DEBE afectar a ninguna otra familia de la misma cuenta. Presentar después
un token de una familia ya revocada NO DEBE cambiar su instante ni su motivo de revocación ni escribir
un segundo asiento de reutilización. El aviso al titular no es parte de este cambio.

#### Escenario: [I104] Reutilización fuera de la gracia (ADR-0005, prueba 1)

- **DADO** `RT-6001` rotado a las 09:00:00 del 2026-10-12, que produjo `RT-6002`
- **CUANDO** `RT-6001` se presenta a las 09:20:00
- **ENTONCES** el desenlace es `ReuseDetected`
- **Y** la familia queda con `revoked_reason` igual a `reuse_detected` y `revoked_at` igual a las
  09:20:00
- **Y** ningún token de la familia sigue vigente, incluido `RT-6002`
- **Y** existe un asiento de auditoría de posible fuga de token con la familia como entidad

#### Escenario: [I105] La revocación se confirma aunque la petición se rechace

- **DADO** una reutilización detectada por un caso de uso invocado desde una capa que traduce el
  desenlace `ReuseDetected` en un rechazo
- **CUANDO** se consulta el estado desde una conexión distinta, después del rechazo
- **ENTONCES** la familia está revocada y el asiento de auditoría existe, ninguno revertido

#### Escenario: [I106] Solo se revoca la familia afectada

- **DADO** la cuenta `maria.lopez@colegio.edu.hn` con dos familias vivas, `F-1` y `F-2`
- **CUANDO** se detecta reutilización en `F-1`
- **ENTONCES** `F-1` queda revocada y `F-2` sigue viva con todos sus tokens vigentes

#### Escenario: [I107] Presentar un token de una familia ya revocada no la modifica

- **DADO** la familia del escenario [I104], ya revocada a las 09:20:00
- **CUANDO** `RT-6001` se presenta de nuevo a las 09:25:00
- **ENTONCES** el desenlace es `Rejected` con motivo `family_revoked`
- **Y** `revoked_at` sigue siendo las 09:20:00, el motivo sigue siendo `reuse_detected` y no se escribió
  un segundo asiento de reutilización

#### Escenario: [I108] Reutilización y rotación concurrentes dejan la familia revocada

- **DADO** `RT-6001` consumido fuera de la gracia y `RT-6002` vigente, y dos transacciones reales y
  sincronizadas con `CountDownLatch` y una espera acotada sobre `pg_stat_activity`: una presenta `RT-6001` y la otra rota `RT-6002`
- **CUANDO** terminan las dos
- **ENTONCES** la familia está revocada con `reuse_detected` y ningún token de la familia está vigente,
  sea cual sea el orden en que el bloqueo serializó las dos operaciones

#### Escenario: [I144] Cada rechazo de un refresco deja un asiento de denegación sin secretos

- **DADO** cinco presentaciones: un token desconocido, un token de una familia revocada, un token de una
  familia vencida, un token propio vencido y un token consumido fuera de la gracia
- **CUANDO** se procesa cada una
- **ENTONCES** las cuatro primeras escriben un asiento de refresco rechazado con resultado de denegación
  cuyo motivo es `unknown_token`, `family_revoked`, `family_expired` y `token_expired`, y la quinta
  escribe el asiento de reutilización detectada con la familia como entidad
- **Y** el asiento del token desconocido no tiene una familia como entidad
- **Y** ningún asiento contiene el refresco, su SHA-256, el sucesor cifrado, el token de acceso, la huella
  del agente de usuario ni el `jti`

### Requisito: Las vigencias de la sesión administrativa son de 8 horas, 12 horas absolutas y 30 minutos de inactividad

El sistema DEBE aplicar estas vigencias al rotar (decisión 4 del propietario): cada token de refresco
vence en el menor entre su emisión más 8 horas y el vencimiento absoluto de su familia; la familia
vence a las 12 horas de su creación; y la familia se rechaza si han transcurrido 30 minutos o más desde
su `last_used_at`. Un token o una familia DEBEN aceptarse en un instante estrictamente anterior a su
vencimiento y NO DEBEN aceptarse en el instante exacto ni después. El rechazo por vigencia NO DEBE
revocar la familia con `reuse_detected` ni con ningún otro motivo (el vencimiento absoluto y la
inactividad se derivan de las columnas y del reloj, no se escriben), ni afectar a otra familia; sí DEBE
vaciar los sucesores cifrados de la familia vencida. El sistema NO DEBE actualizar
`last_used_at` en las peticiones autenticadas con el token de acceso, solo al rotar. El reloj DEBE
inyectarse.

#### Escenario: [I109] El borde de 30 minutos de inactividad

- **DADO** una familia con `last_used_at` igual a las 08:00:00 del 2026-10-12 y un token vigente
- **CUANDO** se rota a las 08:29:59 y, en otra ejecución, a las 08:30:00
- **ENTONCES** la primera es `Rotated` y la segunda es `Rejected` con motivo `family_expired`

#### Escenario: [I110] El vencimiento absoluto acota al token nuevo

- **DADO** una familia con `absolute_expires_at` igual a las 20:00:00 del 2026-10-12 y
  `last_used_at` igual a las 19:50:00
- **CUANDO** se rota a las 19:59:59
- **ENTONCES** el desenlace es `Rotated` y el token nuevo vence a las 20:00:00, no 8 horas después

#### Escenario: [I111] El borde de las 12 horas absolutas

- **DADO** la misma familia con `last_used_at` igual a las 19:59:00
- **CUANDO** se rota exactamente a las 20:00:00
- **ENTONCES** el desenlace es `Rejected` con motivo `family_expired`

#### Escenario: [I112] El vencimiento propio del token

- **DADO** un token vigente de una familia viva, con `expires_at` igual a las 16:00:00
- **CUANDO** se presenta a las 16:00:00
- **ENTONCES** el desenlace es `Rejected` con motivo `token_expired`

#### Escenario: [I113] El rechazo por vigencia no es reutilización

- **DADO** el rechazo del escenario [I109] y otra familia viva de la misma cuenta
- **CUANDO** se inspecciona el estado
- **ENTONCES** la familia rechazada no está revocada, no tiene `reuse_detected` como motivo y la otra
  familia no cambió

#### Escenario: [I142] Un token consumido presentado a una familia vencida es rechazo por vigencia, no reutilización

- **DADO** `RT-7001` rotado a las 08:00:00 del 2026-10-12 con el sucesor `RT-7002` sin usar, y su
  sucesor cifrado todavía en la fila
- **CUANDO** `RT-7001` se presenta a las 08:30:00 (30 minutos de inactividad), y en otra ejecución, con la
  familia de `absolute_expires_at` igual a las 08:00:30, a las 08:00:30
- **ENTONCES** ambos desenlaces son `Rejected` con motivo `family_expired`, no `reuse_detected`
- **Y** la familia no queda revocada y el sucesor cifrado de `RT-7001` queda vacío

#### Escenario: [I145] Un token consumido y vencido por sí mismo en una familia viva es reutilización

- **DADO** `RT-7101` consumido por una rotación anterior hace más de 10 segundos, cuyo `expires_at` ya
  pasó, en una familia viva
- **CUANDO** `RT-7101` se presenta
- **ENTONCES** el desenlace es `Rejected` con motivo `reuse_detected` y la familia se revoca, porque la
  evaluación del consumo precede a la del vencimiento propio del token

#### Escenario: [I114] Una petición autenticada no escribe `last_used_at`

- **DADO** una familia con `last_used_at` igual a las 09:00:00 y un token de acceso vigente de esa
  sesión
- **CUANDO** se hacen cinco peticiones autenticadas a las 09:01, 09:02, 09:03, 09:04 y 09:05
- **ENTONCES** `last_used_at` sigue siendo las 09:00:00 y no se escribió ninguna fila de V8

### Requisito: La vigencia de la sesión se consulta por el puerto `SessionValidity` y el `sid` se comprueba en las rutas de gestión de sesión y en las operaciones sensibles

El módulo de identidad DEBE implementar el puerto `SessionValidity` de `shared.security`, que recibe el
actor autenticado (cuenta, institución y sesión) y no solo el identificador de la sesión: una sesión
está activa si y solo si su familia existe en la institución del actor, pertenece a la cuenta del
actor, no está revocada, su vencimiento absoluto no ha llegado y no han transcurrido 30 minutos o más
desde su `last_used_at`. Un identificador desconocido, de otra institución o de otra cuenta DEBE producir
«no activa». La consulta NO DEBE escribir en la base de datos, ni auditar, ni actualizar `last_used_at`.
Un error de base de datos durante la consulta NO DEBE producir una sesión activa: la petición falla
cerrada con `500` y el código `internal-error`. La comprobación del `sid` contra la base de datos DEBE aplicarse a las rutas de gestión de
sesión, entre ellas la lectura de la sesión actual, y DEBE estar disponible para las operaciones
sensibles (decisión 10 del propietario); NO DEBE aplicarse a toda petición autenticada. Mientras el
token de acceso no venza, una petición a una ruta que no declara la comprobación sigue aceptándose
aunque su familia haya sido revocada (ventana de hasta diez minutos que acepta ADR-0005 para lecturas).

#### Escenario: [I115] Familia activa

- **DADO** una familia viva, sin revocar, antes de su vencimiento absoluto y con `last_used_at` de hace
  menos de 30 minutos
- **CUANDO** se consulta la vigencia con el actor de esa sesión
- **ENTONCES** la sesión está activa

#### Escenario: [I116] Familia revocada, vencida, inactiva, desconocida, de otra institución o de otra cuenta

- **DADO** una familia revocada, otra pasada su vencimiento absoluto, otra con `last_used_at` de hace
  exactamente 30 minutos, un identificador que no existe, una familia viva de la institución A consultada
  con el contexto de la institución B y una familia viva de la cuenta X consultada con un actor de la
  cuenta Y de la misma institución
- **CUANDO** se consulta la vigencia de cada una
- **ENTONCES** las seis sesiones no están activas

#### Escenario: [I117] Una ruta de gestión de sesión rechaza un token de una familia revocada

- **DADO** un token de acceso vigente cuya familia se revocó hace un minuto
- **CUANDO** se presenta a una ruta que declara la comprobación del `sid`
- **ENTONCES** la petición se rechaza con `401` y el código `token-invalid`

#### Escenario: [I118] Una ruta que no declara la comprobación acepta el token hasta su vencimiento

- **DADO** el mismo token y una ruta de prueba autenticada que no declara la comprobación del `sid`
- **CUANDO** se presenta antes de su `exp`
- **ENTONCES** la petición se acepta, y se rechaza con `token-expired` una vez vencido el token

#### Escenario: [I146] Un fallo de base de datos en la comprobación falla cerrado

- **DADO** una ruta que declara la comprobación del `sid`, un token de acceso vigente y una base de datos
  que lanza un error al consultar la familia
- **CUANDO** se presenta el token
- **ENTONCES** la respuesta es `500` con el código `internal-error`, nunca `200`, y el controlador
  registra cero invocaciones

### Requisito: La familia registra la huella del agente de usuario y la IP inicial, y la vinculación se aplica al refrescar

El sistema DEBE registrar en la familia, al emitirla, la huella del agente de usuario (un resumen, nunca
el valor en claro) y la IP inicial de quien inició la sesión; DEBE aceptar la ausencia de ambos. La
huella DEBE usarse para decidir «el mismo dispositivo» de la ventana de gracia. El sistema DEBE aplicar la
vinculación al refrescar según el requisito «Si cambian a la vez el agente y la IP respecto de la creación…»
(decisión DA-8 *b* del propietario): el cambio de **solo uno** de los dos NO DEBE impedir la rotación.
(Previously: el sistema NO DEBE revocar ni rechazar una rotación por el cambio de agente de usuario o de IP.)

#### Escenario: [I119] Solo se guarda la huella y la IP inicial

- **DADO** una emisión con el agente `Mozilla/5.0 (agente-prueba)` y la IP `203.0.113.9`
- **CUANDO** se consulta con SQL crudo la fila de la familia
- **ENTONCES** ninguna columna contiene el texto del agente, la huella es un resumen y la IP inicial es
  `203.0.113.9`

#### Escenario: [I120] Una emisión sin origen se acepta

- **DADO** una emisión por una prueba sin petición HTTP activa
- **CUANDO** se consulta la fila de la familia
- **ENTONCES** la IP inicial es nula y la huella corresponde a «sin agente»

#### Escenario: [I121] El cambio de solo el agente o de solo la IP no impide rotar un token vigente

- **DADO** una familia emitida con el agente `agente-uno` desde `203.0.113.9` y un token vigente sin
  consumir
- **CUANDO** se presenta a rotar con el agente `agente-dos` desde `203.0.113.9`, y en otra ejecución con
  `agente-uno` desde `198.51.100.7`, fuera de cualquier ventana de gracia
- **ENTONCES** ambos desenlaces son `Rotated` y la familia no se revoca

### Requisito: El restablecimiento de contraseña revoca todas las familias vivas de la cuenta en la misma transacción

El sistema DEBE revocar, al completarse un restablecimiento de contraseña con token, todas las familias
vivas de la cuenta con el motivo `password_change`, dentro de la misma transacción que consume el token
y escribe el hash nuevo (`docs/03-seguridad.md` §4.5 y §4.7; ADR-0005, «Recuperación de contraseña»,
punto 4; condición dura H2). La revocación NO DEBE afectar a las familias de otra cuenta, NO DEBE
modificar una familia ya revocada y NO DEBE ocurrir ante ningún desenlace distinto de `Completed`. Si
la revocación falla, el restablecimiento completo DEBE revertirse. El sistema DEBE escribir, en la misma
transacción, un asiento de revocación con la cuenta como entidad y el número de familias revocadas,
también cuando ese número es cero, como evidencia de que la revocación corrió. Si el asiento falla, el
restablecimiento completo DEBE revertirse. Las familias ya revocadas conservan su motivo y su instante, y
la revocación vacía los sucesores cifrados de las familias que revoca. Una familia emitida después de
confirmar el restablecimiento NO DEBE verse afectada. Esta revocación no excluye ninguna sesión,
porque el restablecimiento ocurre sin sesión. El sistema NO DEBE emitir ningún token de refresco sin
que esta revocación exista.

#### Escenario: [I24] Un restablecimiento revoca todas las familias de la cuenta y ninguna ajena

- **DADO** la cuenta `ana.martinez@colegio.edu.hn` con tres familias vivas, la cuenta
  `carlos.ramirez@colegio.edu.hn` con dos, y el token vivo `PRT-88c1` de la primera, con PostgreSQL real
- **CUANDO** se restablece la contraseña de `ana.martinez@colegio.edu.hn` con `PRT-88c1`
- **ENTONCES** las tres familias de `ana.martinez@colegio.edu.hn` quedan revocadas con
  `password_change` y ningún token suyo sigue vigente
- **Y** las dos familias de `carlos.ramirez@colegio.edu.hn` siguen vivas
- **Y** el asiento de revocación registra tres familias revocadas

#### Escenario: [I122] Si la transacción se revierte, ninguna familia queda revocada

- **DADO** el mismo estado, con el restablecimiento procesado dentro de una transacción que falla de
  forma determinista después de consumir el token, escribir el hash y revocar, antes de confirmar
- **CUANDO** se inspecciona el estado tras la reversión
- **ENTONCES** las tres familias siguen vivas, `PRT-88c1` sigue vivo, la contraseña vigente es la
  anterior y no existe ningún asiento de revocación

#### Escenario: [I123] Si la revocación falla, el restablecimiento no ocurre

- **DADO** un puerto de revocación de prueba que lanza una excepción
- **CUANDO** se procesa un restablecimiento con un token vivo
- **ENTONCES** la contraseña vigente no cambia y el token sigue vivo

#### Escenario: [I124] Solo el desenlace `Completed` revoca

- **DADO** una cuenta con tres familias vivas y restablecimientos que terminan en `TokenRejected`,
  `PasswordRejected`, `SecondFactorMissing` y `SecondFactorRejected`
- **CUANDO** se procesa cada uno
- **ENTONCES** ninguna familia se revoca en ninguno de los cuatro casos

#### Escenario: [I125] Cuenta sin familias y familias ya revocadas

- **DADO** una cuenta sin ninguna familia, y otra con una familia revocada a las 08:00:00 por
  `reuse_detected` y otra viva
- **CUANDO** se completa un restablecimiento para cada una
- **ENTONCES** el de la primera se completa y escribe el asiento de revocación con cero familias
  revocadas
- **Y** en la segunda, la familia ya revocada conserva su instante y su motivo originales y solo la
  viva queda revocada con `password_change`

#### Escenario: [I126] Una sesión iniciada después del restablecimiento no se ve afectada

- **DADO** un restablecimiento confirmado a las 10:00:00 y una familia emitida a las 10:05:00 para la
  misma cuenta
- **CUANDO** se consulta el estado de esa familia
- **ENTONCES** sigue viva

#### Escenario: [I127] El token de acceso de una familia revocada falla la comprobación del `sid`

- **DADO** un token de acceso vigente de una familia revocada por un restablecimiento
- **CUANDO** se presenta a una ruta de gestión de sesión
- **ENTONCES** la petición se rechaza con `401` y el código `token-invalid`

#### Escenario: [I128] Restablecimiento y rotación concurrentes no dejan ningún token vivo

- **DADO** una familia viva con un token vigente, y dos transacciones reales y sincronizadas con
  `CountDownLatch` y una espera acotada sobre `pg_stat_activity`: un restablecimiento de la cuenta y una rotación de ese token
- **CUANDO** terminan las dos
- **ENTONCES** la familia está revocada y ningún token de ninguna familia de la cuenta está vigente, sea
  cual sea el orden en que el bloqueo serializó las dos operaciones

### Requisito: La sesión actual se lee en `GET /api/v1/auth/sessions/current` sin datos personales

El sistema DEBE ofrecer la ruta `GET /api/v1/auth/sessions/current`, autenticada con un token de acceso
completo y sin permiso de rol, que devuelve un DTO explícito con una lista cerrada de campos: el
identificador de la sesión (`sessionId`), el identificador opaco del usuario (`accountId`), el
identificador de la institución (`institutionId`), los métodos de autenticación (`authenticationMethods`,
con los valores de `amr`), el instante de vencimiento del token de acceso (`accessTokenExpiresAt`), el
instante de vencimiento absoluto de la sesión (`sessionExpiresAt`) y el instante en que vencería por
inactividad (`idleExpiresAt`, `last_used_at` más 30 minutos), con fechas en ISO 8601 en UTC. La respuesta
NO DEBE contener ningún dato personal. Las respuestas posibles DEBEN ser `200`; `401` con
`authentication-required`, `token-invalid` o `token-expired`, siempre con `WWW-Authenticate`; y `500` con
`internal-error`. NO DEBE existir `403` (todo actor autenticado puede leer su propia sesión) ni `429`. La
ruta DEBE comprobar el `sid` contra la base de datos (decisión 10 del propietario) y NO DEBE escribir en
la base de datos. Su contrato no cambia con `session-endpoints-cookie-and-csrf`, que añade las otras seis
rutas del grupo `/api/v1/auth` y hereda su estilo de recursos.
(Previously: era la única ruta de producción del cambio que la entregó.)

#### Escenario: [I129] Token válido y sesión viva

- **DADO** un token de acceso vigente de una familia viva de `maria.lopez@colegio.edu.hn`
- **CUANDO** se envía `GET /api/v1/auth/sessions/current` con `Authorization: Bearer <token>`
- **ENTONCES** la respuesta es `200` con el DTO de la sesión, cuyo identificador de sesión es el `sid`
  del token, `authenticationMethods` coincide con el `amr` del token, y cuyos vencimientos coinciden con
  `exp`, con el vencimiento absoluto de la familia y con su `last_used_at` más 30 minutos

#### Escenario: [I130] La lista de campos es cerrada y sin datos personales

- **DADO** la respuesta del escenario anterior
- **CUANDO** se comparan sus campos con la lista cerrada de siete campos aprobada y se busca el correo,
  el nombre y el documento de la cuenta
- **ENTONCES** no existe ningún campo fuera de la lista y ninguno de esos valores aparece en el cuerpo
  ni en las cabeceras

#### Escenario: [I131] Sin credencial

- **DADO** una petición sin cabecera `Authorization`
- **CUANDO** se envía a la ruta
- **ENTONCES** la respuesta es `401` con el código `authentication-required` y la cabecera
  `WWW-Authenticate`

#### Escenario: [I132] Token vencido, alterado o restringido

- **DADO** un token íntegro y vencido, un token con un carácter de la firma alterado, y un token
  restringido de MFA vigente
- **CUANDO** se presenta cada uno a la ruta
- **ENTONCES** el primero recibe `401` con `token-expired` y los otros dos reciben `401` con
  `token-invalid`, siempre con `WWW-Authenticate` (el restringido, por su audiencia)

#### Escenario: [I133] Sesión revocada o desconocida con un token vigente

- **DADO** un token de acceso vigente cuya familia está revocada, y otro vigente cuyo `sid` no
  corresponde a ninguna familia
- **CUANDO** se presenta cada uno a la ruta
- **ENTONCES** ambos reciben `401` con `token-invalid`, y las dos respuestas son idénticas entre sí y a
  la de un token alterado, salvo `instance` y el identificador de traza

#### Escenario: [I134] La ruta es de solo lectura

- **DADO** una familia viva con `last_used_at` conocido y el recuento de asientos de auditoría
- **CUANDO** se envían diez peticiones con éxito a la ruta
- **ENTONCES** `last_used_at` no cambió, ninguna fila de V8 se escribió y no se añadió ningún asiento

### Requisito: Ausencia de aviso al titular por reutilización detectada (brecha con destino: cambio 14)

El sistema NO DEBE enviar, en este cambio, ningún correo ni notificación al titular cuando se detecta la
reutilización de un token de refresco. El destino es **`transactional-email-adapter`**, cambio 14 de F0.
El sistema entrega solo la revocación y su asiento de auditoría.

#### Escenario: Una reutilización detectada no notifica a nadie

- **DADO** una reutilización detectada para `maria.lopez@colegio.edu.hn`
- **CUANDO** se inspeccionan los asientos que produjo y los adaptadores de envío
- **ENTONCES** ningún asiento nombra una notificación, un correo ni un intento de entrega, y ningún
  adaptador de envío fue invocado
- **Y** este escenario deja de ser cierto el día que el cambio 14 entregue el aviso

### Requisito: Ausencia de purga de familias y tokens de refresco (brecha con destino: cambio 9)

El sistema NO DEBE eliminar, en este cambio, ninguna familia ni ningún token de refresco, esté vencido,
usado o revocado, ni vaciar por tiempo el sucesor cifrado que ya no puede descifrarse. Ningún rol de
aplicación recibe privilegio `DELETE` sobre las dos tablas. La purga que pide
`docs/08-datos-privacidad-y-retencion.md`, incluida la de la IP inicial, queda con el **cambio 9**
(`background-jobs-with-db-scheduler`).

#### Escenario: Las filas vencidas y revocadas se conservan

- **DADO** una familia revocada hace dos días y un token vencido hace dos días
- **CUANDO** se consultan las tablas el día siguiente
- **ENTONCES** las filas siguen existiendo

#### Escenario: El sucesor cifrado residual se conserva hasta la purga

- **DADO** el sucesor cifrado de un token consumido hace una hora, sin ningún contacto posterior con la
  familia
- **CUANDO** se consulta la fila
- **ENTONCES** el valor cifrado sigue en la base de datos
- **Y** este escenario deja de ser cierto el día que el cambio 9 entregue la purga

### Requisito: Ausencia del endpoint JWKS y del par de claves del portal (brecha con destino: el primer verificador distinto del proceso administrativo y la primera ruta del portal)

El sistema NO DEBE publicar, en este cambio, ningún endpoint JWKS ni ninguna ruta de claves públicas,
porque el único verificador es el propio proceso administrativo con el anillo en memoria (decisión 8
del propietario). El sistema NO DEBE cargar el par de claves del portal ni su verificación (decisión
9); solo reserva el nombre `confia.security.portal-signing.*.private-key` para que el proceso
administrativo se niegue a arrancar si aparece (decisión D-N2 del propietario). El primero llega con el
primer verificador que no sea el proceso administrativo, y el segundo con la primera ruta del portal.

#### Escenario: Ninguna ruta de claves públicas

- **DADO** el mapa de rutas del proceso administrativo
- **CUANDO** se busca una ruta de claves públicas, por ejemplo `/.well-known/jwks.json`
- **ENTONCES** no existe ninguna y la petición recibe `401`
- **Y** este escenario deja de ser cierto el día que se entregue un verificador externo

#### Escenario: El portal no tiene par de claves

- **DADO** el proceso del portal con el entorno por omisión
- **CUANDO** se inspecciona su contexto
- **ENTONCES** no contiene ningún anillo de claves ni carga ninguna clave de firma; solo comprueba, al
  arrancar, que la clave privada administrativa no está presente
- **Y** este escenario deja de ser cierto el día que el portal entregue su primera ruta

### Requisito: La transferencia de las condiciones duras y las notas de documentación quedan escritas, sin reescribir su texto

El cambio DEBE registrar por escrito, con nota fechada y sin reescribir el cuerpo de ninguna condición,
que H1 y la condición 3 obligan a `session-endpoints-cookie-and-csrf`, que H3 y H4 obligan a
`password-recovery-endpoints`, que H2 se cierra en este cambio, y que este cambio no puede declarar
cerrada ninguna de las otras cuatro. DEBE además dejar notas fechadas en `docs/03-seguridad.md` §4.5
(JWS propio sobre el JDK, claves por variable de entorno, rotación con clave vigente y anterior, JWKS
diferido), en `docs/05` (las variables de las claves de firma y el nombre reservado de la clave del
portal), en `docs/08-datos-privacidad-y-retencion.md` (la IP inicial y la huella del agente de usuario de
la familia, conservadas hasta la purga del cambio 9) y en `docs/ui-ux/04-patrones-de-interaccion.md` (la
SPA refresca solo ante actividad del usuario, por la lectura de la inactividad), y una nota de ADR-0005
que precise la lectura de las vigencias, el vocabulario de revocación `password_change` y que la ventana
de gracia devuelve el mismo refresco sucesor con un token de acceso nuevo (decisión D-N1 del
propietario).

#### Escenario: [I135] Nota de transferencia en el plan de fundaciones (solo documental)

- **DADO** `openspec/changes/foundations-plan/exploration.md` tras este cambio
- **CUANDO** se lee su nota fechada «quinto corte» del 2026-10-06
- **ENTONCES** registra la partición en tres cambios, declara a quién obliga cada condición dura y
  declara H2 cerrada en este cambio
- **Y** el texto original de H1, H2, H3, H4 y de la condición 3 no fue modificado

#### Escenario: [I136] Notas fechadas en `docs/03`, `docs/05` y `docs/ui-ux/04` (solo documental)

- **DADO** los tres documentos tras este cambio
- **CUANDO** se leen las secciones citadas
- **ENTONCES** cada una tiene una nota fechada con su contenido y el texto original no fue modificado

#### Escenario: [I137] El informe de archivo lista las cuatro condiciones como abiertas y transferidas (solo documental)

- **DADO** el informe de archivo de este cambio
- **CUANDO** se lee su sección de condiciones duras
- **ENTONCES** lista H1, H3, H4 y la condición 3 como abiertas y transferidas, cada una con su nuevo
  dueño, y H2 como cerrada con la referencia a la prueba I24

#### Escenario: [I148] Notas fechadas en `docs/05`, `docs/08` y ADR-0005 (solo documental)

- **DADO** `docs/05-infraestructura-y-despliegue.md`, `docs/08-datos-privacidad-y-retencion.md` y
  `docs/adr/ADR-0005` tras este cambio
- **CUANDO** se leen
- **ENTONCES** `docs/05` tiene una nota fechada con las cinco propiedades del anillo de claves, el nombre
  reservado de la clave privada del portal y la aclaración de que ninguna tiene valor en el repositorio
- **Y** `docs/08` tiene una nota fechada sobre la IP y la huella del agente de usuario de la familia
- **Y** ADR-0005 tiene una nota fechada sobre las vigencias, el vocabulario `password_change` y la gracia
  con refresco igual y acceso nuevo
- **Y** el texto original de los tres documentos no fue modificado

### Requisito: Ausencia del límite por dirección IP y de la respuesta HTTP de la recuperación (brecha con destino: `password-recovery-endpoints` y cambio 11)

El sistema NO DEBE aplicar, en este cambio, el límite de 10 solicitudes por hora por dirección IP de
`docs/03-seguridad.md` §4.7 y §10, ni producir la respuesta `202 Accepted` con su mensaje, porque no
existe el endpoint de solicitud de recuperación. Es condición dura de aceptación de
**`password-recovery-endpoints`**: ese cambio NO DEBE fusionar el endpoint de solicitud de recuperación
sin ese límite por IP, con el aprovisionamiento de Redis del **cambio 11**, operativo y probado.
(Sustituye al requisito del mismo nombre cuyo destino era `session-tokens-and-web-layer`; la condición
dura H3 conserva su texto y solo cambia de dueño.)

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
  responsabilidad de `password-recovery-endpoints`

### Requisito: Ausencia del restablecimiento administrativo (brecha con destino: cambio 8 y `password-recovery-endpoints`)

El sistema NO DEBE ofrecer, en este cambio, ningún caso de uso con el que un actor distinto del
titular dispare el restablecimiento de otra cuenta ni invalide su contraseña (`docs/03-seguridad.md`
§4.7, «Restablecimiento administrativo»), porque el rol de Super Administrador llega con el **cambio
8**, `rbac-permission-matrix-and-audit-integration`, y el endpoint con
`password-recovery-endpoints`. Ningún caso de uso DEBE permitir fijar una contraseña sin un token de
recuperación válido. (Sustituye al requisito del mismo nombre cuyo destino era el cambio 8 y
`session-tokens-and-web-layer`.)

#### Escenario: El único camino para cambiar una contraseña es el token

- **DADO** el conjunto de casos de uso públicos del módulo de identidad
- **CUANDO** se inspecciona cuáles escriben el hash de contraseña de una cuenta
- **ENTONCES** el único es el restablecimiento con token

#### Escenario: Un restablecimiento sin token válido nunca cambia la contraseña

- **DADO** la cuenta `ana.martinez@colegio.edu.hn`, con la contraseña vigente `Cafetal de Copán 2026`
- **CUANDO** se intenta restablecerla con un token que nunca se emitió
- **ENTONCES** el sistema lo rechaza y `Cafetal de Copán 2026` sigue autenticando

### Requisito: El inicio de sesión se ofrece en `POST /api/v1/auth/sessions` y responde según el desenlace de la autenticación

El sistema DEBE ofrecer `POST /api/v1/auth/sessions`, sin credencial previa, con cuerpo JSON
`{"identifier": string, "password": string}` y `Content-Type: application/json` obligatorio. La ruta DEBE
traducir los cuatro desenlaces de `AuthenticationResult` así: `Authenticated` produce `200` con la respuesta de
sesión, emite una familia de refresco con `second_factor_verified` en `false` y las dos cookies (ver el
requisito de cookies en `web-edge`); `SecondFactorRequired` produce `200` con `status` igual a
`second-factor-required`, `secondFactorToken` (token restringido con uso `mfa-verify`) y
`secondFactorTokenExpiresAt`; `SecondFactorEnrollmentRequired` produce `200` con `status` igual a
`second-factor-enrollment-required` y un token restringido con uso `mfa-enroll`; `Rejected` produce `401` con el
código `authentication-failed`. Los dos desenlaces de segundo factor NO DEBEN emitir cookies, ni crear
familia ni token de refresco. El cuerpo NO DEBE contener datos personales. La institución DEBE provenir de la
configuración del proceso. La validación DEBE limitarse a la longitud máxima de cada campo y NO DEBE imponer la
longitud mínima de la contraseña. Las propiedades desconocidas del cuerpo DEBEN rechazarse con `400` y
`validation-failed`. Un tipo de contenido distinto de JSON DEBE responder `415`. Toda respuesta DEBE llevar
`Cache-Control: no-store`. La ruta NO DEBE usar `Idempotency-Key`. El inicio de sesión no es una ruta de cookie: NO
DEBE leer la cookie de sesión ni exigir el token CSRF (la protegen el `Origin` obligatorio, el tipo de contenido
JSON y `SameSite=Strict`); si el navegador ya traía una cookie de sesión anterior, la respuesta con sesión la
sobrescribe.

#### Escenario: [I149] Credenciales correctas sin segundo factor emiten sesión y cookies

- **DADO** la cuenta `maria.lopez@colegio.edu.hn` con `mfa_required` en `false` y contraseña vigente
- **CUANDO** envía `POST /api/v1/auth/sessions` con un `Origin` permitido, `Content-Type: application/json` y
  sus credenciales correctas
- **ENTONCES** la respuesta es `200` con `status` igual a `authenticated` y un `accessToken` cuyo `amr` es
  `[pwd]` y cuyo `sid` es la familia recién creada
- **Y** la respuesta contiene exactamente dos cabeceras `Set-Cookie`, la de sesión y la CSRF
- **Y** existe una familia con la IP y la huella del agente de la petición, y un asiento de emisión de sesión
- **Y** el cuerpo no contiene el correo, el nombre ni el `sid`

#### Escenario: [I150] Cuenta con segundo factor verificado recibe un token restringido y ninguna cookie

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn` con `mfa_required` en `true` y credencial verificada
- **CUANDO** inicia sesión con contraseña correcta
- **ENTONCES** la respuesta es `200` con `status` igual a `second-factor-required` y un `secondFactorToken`
  cuyo uso es `mfa-verify` y cuya vida es de 300 segundos
- **Y** la respuesta no contiene `Set-Cookie`
- **Y** no se creó ninguna familia ni ningún token de refresco

#### Escenario: [I151] Cuenta sin secreto recibe un token de inscripción

- **DADO** la cuenta `jorge.aguilar@colegio.edu.hn` con `mfa_required` en `true` y sin secreto TOTP
- **CUANDO** inicia sesión con contraseña correcta
- **ENTONCES** la respuesta es `200` con `status` igual a `second-factor-enrollment-required` y un token
  restringido cuyo uso es `mfa-enroll`
- **Y** la respuesta no contiene `Set-Cookie` y no se creó ninguna familia

#### Escenario: [I152] Un rechazo responde `401 authentication-failed` sin cookies ni token

- **DADO** una contraseña incorrecta para `maria.lopez@colegio.edu.hn`
- **CUANDO** se envía el inicio de sesión
- **ENTONCES** la respuesta es `401` con el código `authentication-failed` y la cabecera
  `WWW-Authenticate: Bearer`
- **Y** no contiene `Set-Cookie`, token de acceso ni token restringido

#### Escenario: [I153] Validación del cuerpo

- **DADO** cuerpos sin `identifier`, con `password` vacía, con un campo de longitud máxima más uno y con una
  propiedad desconocida `role`
- **CUANDO** se envía cada uno
- **ENTONCES** los cuatro responden `400` con `validation-failed` que nombra el campo, sin repetir el valor
  rechazado
- **Y** el caso de uso registra cero invocaciones en cada uno
- **Y** una contraseña de 3 caracteres con longitud válida de campo llega al caso de uso (no se impone el
  mínimo de 12 al entrar)

#### Escenario: [I154] Tipo de contenido distinto de JSON

- **DADO** una petición con `Content-Type: text/plain` o `application/x-www-form-urlencoded`
- **CUANDO** se envía a la ruta
- **ENTONCES** la respuesta es `415` con `unsupported-media-type` y el caso de uso registra cero invocaciones

#### Escenario: [I155] La institución del cuerpo se ignora

- **DADO** un proceso configurado con la institución `d290f1ee-6c54-4b01-90e6-d701748f0851` y un cuerpo que
  intenta declarar otra institución en una cabecera `X-Institution-Id`
- **CUANDO** se inicia sesión
- **ENTONCES** la cuenta se resuelve en la institución de la configuración y la cabecera se ignora

#### Escenario: [I228] El inicio de sesión no exige CSRF y sobrescribe una cookie anterior

- **DADO** un navegador con una cookie de sesión anterior, sin cabecera `X-CSRF-Token`, y un `Origin` permitido
- **CUANDO** inicia sesión con credenciales correctas de una cuenta sin segundo factor
- **ENTONCES** la respuesta es `200` y los dos `Set-Cookie` sustituyen a las cookies anteriores
- **Y** la petición no se rechazó por falta de token CSRF
- **Y** la familia anterior no se revoca por este inicio de sesión

#### Escenario: [I156] El retardo del retroceso se materializa después de confirmar y no deniega

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn` con tres fallos consecutivos y la contraseña correcta
- **CUANDO** inicia sesión
- **ENTONCES** la respuesta es `200` con sesión, enviada después de la espera del retardo exigible
- **Y** la transacción ya estaba confirmada al empezar la espera

### Requisito: El `401 authentication-failed` es idéntico para cuenta existente e inexistente y su tiempo no distingue la cuenta

La respuesta `401 authentication-failed` DEBE ser idéntica en estado, cuerpo y cabeceras para una contraseña
incorrecta de una cuenta existente y para un identificador inexistente, incluidos los casos con retardo cero. La
comparación del cuerpo DEBE excluir únicamente `traceId` (distinto en cada petición por diseño) y la de las
cabeceras DEBE excluir únicamente `Date`; ninguna otra diferencia es admisible. La diferencia entre las medianas del
tiempo de respuesta de 200 intentos con cuenta existente y 200 con cuenta inexistente NO DEBE superar 50 ms
(`docs/03-seguridad.md` §4.6; ADR-0005, verificación 7). Ningún cuerpo, cabecera ni `Set-Cookie` DEBE revelar el
motivo interno del rechazo.

#### Escenario: [I157] Igualdad de la respuesta

- **DADO** un intento con contraseña incorrecta contra `maria.lopez@colegio.edu.hn` y otro contra
  `nadie.registrado@colegio.edu.hn`, ambos sin fallos previos
- **CUANDO** se comparan las dos respuestas
- **ENTONCES** el estado es el mismo, el cuerpo es idéntico una vez excluido `traceId` y las cabeceras son
  idénticas una vez excluida `Date`
- **Y** la prueba falla si cualquier otro miembro del cuerpo o cualquier otra cabecera difiere (control negativo)

#### Escenario: [I158] Igualdad con retardo

- **DADO** las mismas dos identidades con tres fallos previos cada una
- **CUANDO** se envía el cuarto intento a cada una
- **ENTONCES** ambas respuestas son idénticas con la misma exclusión de `traceId` y de `Date`, y esperan el mismo
  retardo de 2 segundos

#### Escenario: [I159] Puerta de tiempo de 200 más 200 intentos

- **DADO** 200 intentos con una cuenta existente y contraseña incorrecta y 200 con identificadores inexistentes,
  con retardo cero en ambos
- **CUANDO** se comparan las medianas del tiempo de respuesta
- **ENTONCES** la diferencia es de 50 ms como máximo
- **Y** la prueba falla si se sustituye la verificación señuelo por una respuesta inmediata (control negativo)

### Requisito: El control por IP del inicio de sesión se aplica antes del caso de uso y cierra la condición H1

El inicio de sesión DEBE ejecutarse bajo la política `admin-login` del limitador: el limitador DEBE evaluarse
antes de abrir ninguna transacción y antes de la verificación de contraseña. Cuando el caso de uso, ya
confirmado, devuelve `Rejected`, el borde DEBE registrar un fallo para la IP del cliente. Los desenlaces
`Authenticated`, `SecondFactorRequired` y `SecondFactorEnrollmentRequired` NO DEBEN registrar fallo. Una petición
rechazada por el limitador NO DEBE escribir en `identity_login_backoff` ni consumir Argon2id. El endpoint NO DEBE
ser alcanzable sin este limitador (condición dura H1, `docs/03-seguridad.md` §4.4). Con
`confia.web.trusted-proxies` vacío, el tráfico detrás de un proxy comparte un cubo; esa reserva queda declarada
y su llenado es del cambio 11.

#### Escenario: [I160] Diez peticiones por minuto y la undécima recibe `429`

- **DADO** una IP sin historial
- **CUANDO** envía 10 inicios de sesión en un minuto y un undécimo
- **ENTONCES** los 10 primeros llegan al caso de uso y el undécimo recibe `429` con `too-many-requests` y
  `Retry-After`, con cero invocaciones del caso de uso y sin transacción abierta

#### Escenario: [I161] Tras los fallos de la IP, `identity_login_backoff` deja de crecer

- **DADO** una IP que acumula 10 fallos `Rejected` en 10 minutos contra la misma cuenta
- **CUANDO** envía más intentos mientras la capa 2 está activa
- **ENTONCES** los intentos reciben `429` con `Retry-After`
- **Y** el contador de `identity_login_backoff` de esa cuenta no cambia desde el décimo fallo

#### Escenario: [I162] Solo `Rejected` cuenta como fallo de la IP

- **DADO** una IP que inicia sesión 12 veces con contraseña correcta de cuentas con segundo factor pendiente,
  espaciadas para no agotar la capa 1
- **CUANDO** se consulta el contador de fallos de la IP
- **ENTONCES** vale cero y la capa 2 no se activó

#### Escenario: [I163] Una petición rechazada por el filtro de `Origin` no consume cupo

- **DADO** una IP con 9 peticiones admitidas y una petición con `Origin` no permitido
- **CUANDO** se envía
- **ENTONCES** recibe `403 csrf-invalid` y el cupo de la IP sigue en 9

### Requisito: Cinco identificadores distintos con fallo desde una IP en diez minutos producen una señal, sin bloqueo

El sistema DEBE emitir una señal cuando una misma IP registra fallos `Rejected` contra cinco identificadores
distintos (por su huella con llave, sea o no una cuenta existente) dentro de 10 minutos, tomando un fallo como
vigente mientras su antigüedad sea estrictamente menor que 10 minutos. La señal DEBE ser un evento `WARN`
estructurado y un asiento de auditoría. El asiento (`identity.login.failure_spread_detected`, resultado `denied`)
DEBE escribirse en la misma transacción del intento que alcanza el umbral, con la dirección canónica del cliente
como identificador de la entidad y `{"distinctIdentifiers":5,"windowSeconds":600}` como detalle. El `WARN` DEBE
llevar solo el identificador de la petición y el nombre del evento, sin dirección ni identificador. Ni el `WARN`
ni el asiento DEBEN contener el identificador ni su huella. La señal se emite solo en la llamada en que el
número de identificadores distintos llega al umbral; la misma huella repetida no suma. La señal NO DEBE bloquear a
la IP ni alterar la respuesta de ningún intento (el bloqueo de 1 hora es del cambio 11). Cuatro identificadores
distintos, o cinco fallos contra el mismo identificador, NO DEBEN producir la señal.

#### Escenario: [I164] El quinto identificador distinto dispara la señal

- **DADO** una IP con fallos contra cuatro identificadores distintos entre las 10:00:00 y las 10:05:00
- **CUANDO** falla un quinto identificador distinto a las 10:06:00
- **ENTONCES** se emite el `WARN` estructurado, con el identificador de la petición y el nombre del evento y sin
  dirección ni identificador
- **Y** se escribe en la misma transacción el asiento `identity.login.failure_spread_detected` con la dirección
  canónica como entidad y el detalle `{"distinctIdentifiers":5,"windowSeconds":600}`
- **Y** ni el `WARN` ni el asiento contienen el identificador ni su huella
- **Y** la respuesta de ese intento es el `401 authentication-failed` habitual

#### Escenario: [I165] Cuatro identificadores o cinco fallos del mismo no la disparan

- **DADO** una IP con fallos contra cuatro identificadores distintos, y otra con cinco fallos contra uno solo
- **CUANDO** se evalúa la señal
- **ENTONCES** no se emite ninguna señal en ninguno de los dos casos

#### Escenario: [I166] El borde de los diez minutos es estricto y la señal no bloquea

- **DADO** cuatro identificadores con fallo a las 10:00:00 y un quinto a las 10:10:00 exactos
- **CUANDO** se evalúa la señal
- **ENTONCES** el fallo de las 10:00:00 ya no cuenta, no hay señal
- **Y** en el caso con señal emitida, un intento posterior con contraseña correcta sigue autenticando

#### Escenario: [I224] El sexto identificador y la repetición de una huella no vuelven a señalar

- **DADO** una IP que ya disparó la señal con cinco identificadores distintos dentro de la ventana
- **CUANDO** falla un sexto identificador distinto, y luego se repite el fallo de una huella ya contada
- **ENTONCES** no se emite una segunda señal ni se escribe otro asiento mientras la ventana siga vigente

### Requisito: Una credencial TOTP está verificada solo tras aceptar un código TOTP, y «MFA activa» significa secreto verificado

El sistema DEBE considerar verificada una credencial TOTP cuando aceptó al menos un código TOTP (su último
contador aceptado es mayor o igual que cero). Una credencial recién inscrita, con el contador en -1, NO DEBE
considerarse verificada. Un código de recuperación NO DEBE verificar una credencial. «MFA activa» significa
`mfa_required` en `true` y una credencial verificada; una credencial inscrita pero nunca verificada equivale,
para el inicio de sesión y para el restablecimiento, a no tener secreto inscrito. Por tanto, el inicio de sesión
de una cuenta con `mfa_required` en `true` y credencial nunca verificada DEBE devolver
`SecondFactorEnrollmentRequired`, de modo que el usuario nunca reciba un token `mfa-verify` para su primer
código.

#### Escenario: [I167] Una credencial recién inscrita no está verificada

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn`, recién inscrita
- **CUANDO** inicia sesión con contraseña correcta
- **ENTONCES** el desenlace es `SecondFactorEnrollmentRequired`, no `SecondFactorRequired`

#### Escenario: [I168] Un código TOTP aceptado verifica la credencial

- **DADO** la credencial de `sofia.mejia@colegio.edu.hn` con contador -1
- **CUANDO** se acepta un código TOTP válido
- **ENTONCES** el contador pasa a un valor mayor o igual que cero
- **Y** el siguiente inicio de sesión devuelve `SecondFactorRequired`

#### Escenario: [I169] Un código de recuperación no verifica

- **DADO** una credencial con contador -1 y diez códigos de recuperación sin usar
- **CUANDO** se presenta un código de recuperación a la verificación
- **ENTONCES** se rechaza como `second-factor-invalid` (la primera verificación, con el token `mfa-enroll`,
  acepta solo un código TOTP), el contador sigue en -1 y el código sigue sin usar

### Requisito: La verificación del segundo factor se ofrece en `POST /api/v1/auth/mfa/verifications` con un token restringido

El sistema DEBE ofrecer `POST /api/v1/auth/mfa/verifications`, autenticada con `Authorization: Bearer` de un
token restringido, bajo la política `admin-mfa` y con el retardo del retroceso TOTP materializado tras confirmar.
El cuerpo DEBE contener exactamente uno de `code` o `recoveryCode`. La ruta DEBE admitir en el borde tanto el
token con uso `mfa-verify` como el de uso `mfa-enroll`, y el caso de uso DEBE decidir según el uso y el estado de
la credencial: con `mfa-verify` y credencial verificada, se acepta un código TOTP o un código de recuperación; con
`mfa-enroll` y credencial sin verificar (la primera verificación), se acepta **solo** un código TOTP y un código
de recuperación DEBE rechazarse como `second-factor-invalid` sin consumirse; con `mfa-enroll` y credencial ya
verificada (la inscripción ya se completó), toda prueba DEBE rechazarse como `second-factor-invalid`; con
`mfa-verify` y credencial sin verificar (imposible por construcción), también `second-factor-invalid`. Un código
de recuperación incorrecto en esta ruta DEBE sumar al mismo retroceso de la cuenta que los fallos TOTP. Una
verificación correcta DEBE emitir una familia con `second_factor_verified` en `true` (`amr` igual a `[pwd, otp]`) y responder
`200` con la respuesta de sesión y las dos cookies. Una verificación incorrecta DEBE responder
`401 second-factor-invalid` sin cookies, con el avance del retroceso TOTP de la cuenta. Un token de acceso
presentado a la ruta DEBE rechazarse por su audiencia con `401 token-invalid`.

#### Escenario: [I170] Código TOTP válido emite la sesión completa

- **DADO** un token `mfa-verify` de `sofia.mejia@colegio.edu.hn` y un código TOTP válido
- **CUANDO** se envía a la verificación
- **ENTONCES** la respuesta es `200` con la respuesta de sesión, cuyo token de acceso tiene `amr` igual a
  `[pwd, otp]`, y las dos cookies
- **Y** existe una familia con `second_factor_verified` en `true`

#### Escenario: [I171] Código de recuperación válido emite la sesión y queda consumido

- **DADO** un token `mfa-verify` y la credencial verificada con diez códigos de recuperación sin usar
- **CUANDO** se envía `recoveryCode` con uno de ellos
- **ENTONCES** la respuesta es `200` con sesión, ese código queda usado y quedan nueve
- **Y** un segundo uso del mismo código responde `401 second-factor-invalid`

#### Escenario: [I172] Código incorrecto

- **DADO** un token `mfa-verify` y un código TOTP incorrecto
- **CUANDO** se envía
- **ENTONCES** la respuesta es `401` con `second-factor-invalid`, sin `Set-Cookie`
- **Y** el contador de fallos TOTP de la cuenta avanza y, a partir del tercer fallo, la respuesta espera el
  retardo exigible tras confirmar

#### Escenario: [I173] Cuerpo con ambos códigos o con ninguno

- **DADO** cuerpos con `code` y `recoveryCode` a la vez, y con ninguno
- **CUANDO** se envían
- **ENTONCES** ambos responden `400` con `validation-failed`

#### Escenario: [I174] Token vencido o de acceso

- **DADO** un token restringido vencido y un token de acceso vigente
- **CUANDO** se presenta cada uno
- **ENTONCES** el primero recibe `401 token-expired` y el segundo `401 token-invalid`, y ninguno produce
  cookies

#### Escenario: [I221] Con el token `mfa-enroll` un código de recuperación no activa la credencial

- **DADO** un token `mfa-enroll` de `jorge.aguilar@colegio.edu.hn`, con credencial recién inscrita (contador -1) y
  diez códigos de recuperación sin usar
- **CUANDO** se envía `recoveryCode` con uno de ellos a la verificación
- **ENTONCES** la respuesta es `401` con `second-factor-invalid`, sin `Set-Cookie`
- **Y** la credencial sigue sin verificar (contador -1), el código sigue sin usar y no se emitió ninguna familia
- **Y** el rechazo cuenta como `second-factor-invalid` para la política `admin-mfa`

#### Escenario: [I222] Con la credencial ya verificada, el token `mfa-enroll` recibe `second-factor-invalid`

- **DADO** una cuenta con credencial verificada y un token `mfa-enroll` vigente
- **CUANDO** se envía a la verificación un código TOTP válido, y en otra ejecución un código de recuperación
  válido
- **ENTONCES** ambas respuestas son `401` con `second-factor-invalid`, no `token-invalid`, sin `Set-Cookie`
- **Y** no se emitió ninguna familia, el código de recuperación sigue sin usar y no se revocó ninguna familia

#### Escenario: [I223] Un código de recuperación incorrecto suma al retroceso TOTP

- **DADO** un token `mfa-verify` de `sofia.mejia@colegio.edu.hn` con credencial verificada y dos fallos TOTP
  previos
- **CUANDO** se envía un `recoveryCode` incorrecto
- **ENTONCES** la respuesta es `401 second-factor-invalid` y el contador de fallos del retroceso TOTP de la cuenta
  pasa a tres
- **Y** la respuesta espera el retardo exigible tras confirmar

#### Escenario: [I175] Un código reutilizado en la misma ventana se rechaza

- **DADO** un código TOTP ya aceptado para el contador `58204`
- **CUANDO** se presenta de nuevo con un token `mfa-verify`
- **ENTONCES** responde `401 second-factor-invalid`

### Requisito: La primera verificación correcta activa la credencial y revoca las demás sesiones con el motivo `mfa_change`

Cuando la verificación correcta de un código TOTP es la primera de la credencial (estaba nunca verificada), el
sistema DEBE, en una única transacción, activar la credencial, emitir la familia nueva y revocar todas las demás
familias vivas de la cuenta con el motivo `mfa_change` (`docs/03-seguridad.md` §4.5; obligación O10). La familia
emitida por esa verificación NO DEBE revocarse. Las familias de otra cuenta o de otra institución NO DEBEN
tocarse y las ya revocadas conservan su motivo y su instante. El sistema DEBE escribir un asiento de revocación
con el número de familias revocadas, también cero (`identity.session.revoked`, motivo `mfa_change`), y un asiento
`identity.mfa.activated`. La revocación DEBE ejecutarse antes de emitir la familia nueva, de modo que esta nunca
se revoque. Las verificaciones posteriores de una credencial ya
verificada NO DEBEN revocar ninguna familia. Si algún paso falla, ningún efecto DEBE sobrevivir.

#### Escenario: [I176] Activación con dos sesiones previas

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn` con credencial nunca verificada y dos familias vivas, y una
  cuenta ajena con una familia viva
- **CUANDO** verifica su primer código TOTP con un token `mfa-enroll`
- **ENTONCES** las dos familias previas quedan revocadas con `mfa_change`, la familia nueva sigue viva y la
  ajena no cambió
- **Y** el asiento de revocación registra dos familias

#### Escenario: [I177] Una verificación posterior no revoca

- **DADO** la misma cuenta, ya verificada, con la familia de la activación y otra posterior
- **CUANDO** verifica un código con un token `mfa-verify`
- **ENTONCES** ninguna familia existente se revoca y se emite una más

#### Escenario: [I178] La activación es atómica

- **DADO** una activación que falla de forma determinista tras revocar y antes de confirmar
- **CUANDO** se inspecciona el estado tras la reversión
- **ENTONCES** la credencial sigue sin verificar, las familias previas siguen vivas y no existe la familia
  nueva ni el asiento de revocación

#### Escenario: [I179] Activación sin otras sesiones y entre instituciones

- **DADO** una cuenta sin otras familias, y dos instituciones con cuentas de igual correo
- **CUANDO** una activa su credencial
- **ENTONCES** escribe el asiento de revocación con cero familias y las familias de la otra institución no
  cambian

### Requisito: La inscripción del segundo factor se ofrece en `POST /api/v1/auth/mfa/enrollments` y es repetible hasta la primera verificación

El sistema DEBE ofrecer `POST /api/v1/auth/mfa/enrollments`, autenticada con un token restringido de uso
`mfa-enroll` (un token `mfa-verify` DEBE rechazarse con `401 token-invalid`), bajo la política `admin-mfa`. Sin
credencial previa, DEBE crearla y responder `201` con el secreto en base32 de 32 caracteres sin relleno y diez
códigos de recuperación, con `Cache-Control: no-store`, sin cookies y sin emitir sesión. Con una credencial
nunca verificada, DEBE reemplazar el secreto y dejar inutilizados los códigos de recuperación previos,
devolviendo secreto y códigos nuevos. Con una credencial verificada, DEBE responder `409` con
`mfa-already-enrolled` sin modificar nada salvo el asiento `identity.mfa.enrollment_rejected`. Cada inscripción o
reinscripción DEBE auditarse (`identity.mfa.enrolled`); la reinscripción lleva el detalle
`{"replacedUnverified":true,"invalidatedRecoveryCodes":n}` y la primera inscripción conserva el detalle actual.
La inscripción DEBE ejecutarse bajo el bloqueo de la cuenta: dos inscripciones concurrentes de la misma cuenta
sin credencial verificada se serializan y la segunda es una reinscripción, de modo que ambas responden `201`, el
secreto y los códigos vigentes son los de la última, y ninguna responde `409` ni `500` ni viola la unicidad. El
secreto y los códigos NO DEBEN aparecer en registros. La inscripción no activa la credencial.

#### Escenario: [I180] Primera inscripción

- **DADO** un token `mfa-enroll` de `jorge.aguilar@colegio.edu.hn`, sin credencial
- **CUANDO** se envía la inscripción
- **ENTONCES** la respuesta es `201` con un secreto base32 de 32 caracteres y diez códigos distintos, con
  `Cache-Control: no-store` y sin `Set-Cookie`
- **Y** el secreto almacenado está cifrado con prefijo `v1:` y corresponde al devuelto

#### Escenario: [I181] Reinscripción mientras no se verificó

- **DADO** la inscripción anterior sin verificar y el secreto `S1` ya entregado
- **CUANDO** se inscribe de nuevo con un token `mfa-enroll`
- **ENTONCES** la respuesta es `201` con un secreto `S2` distinto de `S1`
- **Y** los diez códigos de recuperación previos quedan inutilizados (con su instante de uso fijado) y un código
  de `S1` ya no se acepta
- **Y** existen dos asientos `identity.mfa.enrolled`, y el segundo lleva
  `{"replacedUnverified":true,"invalidatedRecoveryCodes":10}`

#### Escenario: [I182] Credencial verificada responde `409`

- **DADO** una cuenta con credencial verificada y un token `mfa-enroll` vigente
- **CUANDO** se envía la inscripción
- **ENTONCES** la respuesta es `409` con `mfa-already-enrolled` y el secreto almacenado no cambia
- **Y** lo único que se escribe es el asiento `identity.mfa.enrollment_rejected` con el detalle
  `{"reason":"already_enrolled"}`

#### Escenario: [I183] Dos inscripciones concurrentes son una inscripción y una reinscripción

- **DADO** una cuenta sin credencial y dos peticiones de inscripción sincronizadas con `CountDownLatch`, con
  transacciones reales
- **CUANDO** terminan las dos
- **ENTONCES** las dos responden `201`; ninguna responde `409` ni `500`
- **Y** existe exactamente una credencial, cuyo secreto vigente es el de la última petición en tomar el bloqueo
  de la cuenta, y los códigos de recuperación sin usar son solo los de esa misma petición
- **Y** los códigos entregados a la primera quedan inutilizados, y se registran un asiento de inscripción y uno de
  reinscripción
- **Y** la credencial sigue sin verificar

#### Escenario: [I184] Un token `mfa-verify` no inscribe

- **DADO** un token `mfa-verify`
- **CUANDO** se presenta a la inscripción
- **ENTONCES** la respuesta es `401 token-invalid`

#### Escenario: [I185] Riesgo declarado de la ventana de primera inscripción (solo documental)

- **DADO** `docs/03-seguridad.md` §4.3 y ADR-0005 tras este cambio
- **CUANDO** se leen
- **ENTONCES** declaran que, hasta la primera verificación, quien conozca la contraseña puede reemplazar el
  secreto, aceptado por DA-15 y a revisar en el cambio 8, y que cada reinscripción queda auditada

### Requisito: El refresco se ofrece en `POST /api/v1/auth/sessions/refresh` con la cookie de sesión, CSRF y `Origin`

El sistema DEBE ofrecer `POST /api/v1/auth/sessions/refresh`, que lee el refresco únicamente de la cookie de
sesión, exige siempre un `Origin` permitido, exige `X-CSRF-Token` cuando la petición trae la cookie de sesión, y
está bajo la política `admin-session`. Una rotación
`Rotated` (de tipo `fresh` o `grace`) DEBE responder `200` con la respuesta de sesión y reemitir las dos cookies;
en la gracia, la cookie de sesión lleva el mismo refresco sucesor y la cookie CSRF se reemite con el vínculo de
ese mismo refresco. Todo desenlace `Rejected` (`unknown_token`, `family_revoked`, `family_expired`,
`token_expired`, `reuse_detected`, `binding_changed`) DEBE responder `401 session-expired` uniforme y borrar las
dos cookies; la reutilización detectada conserva su revocación y su asiento. Cada rechazo del refresco DEBE
registrar un fallo de la IP en la política `admin-session`. Una petición **sin cookie de sesión** pero con
`Origin` permitido NO exige token CSRF (no hay credencial ambiental que proteger): supera el filtro, el caso de
uso la trata como rechazo y la respuesta es `401 session-expired` con las dos cookies de borrado, igual que
cualquier otro rechazo, y registra el fallo de la IP. Una petición sin cookie de sesión y sin `Origin` permitido
responde `403 csrf-invalid`. Una petición con dos cookies de sesión (repetidas) responde `403 csrf-invalid` sin
elegir ninguna. La respuesta NO DEBE contener datos personales ni el `sid`.

#### Escenario: [I186] Refresco normal

- **DADO** una sesión iniciada a las 09:00:00 y su cookie, con CSRF y `Origin` válidos
- **CUANDO** se refresca a las 09:15:00
- **ENTONCES** la respuesta es `200` con un token de acceso nuevo de la misma familia
- **Y** contiene dos `Set-Cookie`: el refresco sucesor y un token CSRF vinculado a él
- **Y** el refresco anterior queda consumido

#### Escenario: [I187] Refresco en la gracia desde dos pestañas

- **DADO** dos pestañas que presentan el mismo refresco en el mismo instante con el mismo agente
- **CUANDO** terminan las dos peticiones
- **ENTONCES** una es `fresh` y la otra `grace`, ambas responden `200`
- **Y** ambas llevan el mismo refresco sucesor y un token CSRF que valida contra ese refresco

#### Escenario: [I188] Todo rechazo es `session-expired` uniforme y borra las cookies

- **DADO** refrescos desconocido, de familia revocada, de familia vencida, vencido por sí mismo y reutilizado
  fuera de la gracia, cada uno con CSRF válido para su valor
- **CUANDO** se presenta cada uno
- **ENTONCES** las cinco respuestas son `401 session-expired`, idénticas salvo `instance` y traza
- **Y** cada una contiene los dos `Set-Cookie` de borrado
- **Y** el de reutilización deja la familia revocada con `reuse_detected`

#### Escenario: [I189] Con cookie de sesión y sin cabecera CSRF, o sin `Origin`

- **DADO** una petición con cookie de sesión, `Origin` permitido y sin `X-CSRF-Token`, y otra con cookie de
  sesión y cookie CSRF válidas, cabecera `X-CSRF-Token` igual a la cookie, pero sin `Origin`
- **CUANDO** se envían al refresco
- **ENTONCES** ambas responden `403 csrf-invalid` y el caso de uso registra cero invocaciones

#### Escenario: [I219] Sin cookie de sesión y con `Origin` permitido, el refresco es `401 session-expired`

- **DADO** una petición de refresco sin cookie de sesión, sin `X-CSRF-Token` y con `Origin` permitido
- **CUANDO** se envía
- **ENTONCES** la respuesta es `401` con `session-expired` (no `403`), idéntica a la de cualquier otro rechazo
  del refresco, con los dos `Set-Cookie` de borrado y `WWW-Authenticate: Bearer`
- **Y** el filtro no exigió token CSRF y el fallo cuenta para la política `admin-session`
- **Y** la misma petición sin `Origin` responde `403 csrf-invalid`

#### Escenario: [I220] Dos cookies de sesión repetidas se rechazan sin elegir una

- **DADO** una petición de refresco con dos cookies `__Secure-confia_admin_sid` (por ejemplo, inyectada desde un
  subdominio), `Origin` permitido y un token CSRF válido para una de ellas
- **CUANDO** se envía
- **ENTONCES** la respuesta es `403 csrf-invalid` y el caso de uso registra cero invocaciones

### Requisito: Si cambian a la vez el agente y la IP respecto de la creación, el refresco se rechaza y la familia se revoca con `binding_changed`

Al refrescar, el sistema DEBE comparar la huella del agente de usuario y la IP del cliente de la petición con las
registradas en la familia al crearse. Si **ambas** difieren, DEBE rechazar el refresco como `401 session-expired`,
revocar la familia con el motivo `binding_changed`, vaciar sus sucesores cifrados, escribir un asiento de
auditoría y borrar las cookies. Si solo una difiere, o si el valor registrado de alguna de las dos es nulo, el
refresco NO DEBE rechazarse por este motivo. La dirección se compara como dirección (un IPv6 comprimido y el mismo
expandido son iguales), nunca como texto. La comprobación se evalúa en este orden fijo: familia revocada, familia
vencida o inactiva y reutilización detectada conservan su desenlace y se evalúan antes; después va la vinculación;
y solo después la gracia y el vencimiento del token. Por tanto, se aplica a toda presentación que de otro modo
rotaría y también a un token propio vencido; un token ya consumido y no apto para gracia sigue siendo
`reuse_detected`. La revocación NO DEBE afectar a otras familias.

#### Escenario: [I190] Cambian agente e IP

- **DADO** una familia creada con `agente-uno` desde `203.0.113.9` y un refresco vigente
- **CUANDO** se refresca con `agente-dos` desde `198.51.100.7`
- **ENTONCES** la respuesta es `401 session-expired`, la familia queda con `revoked_reason` igual a
  `binding_changed` y ningún token de la familia sigue vigente
- **Y** existe el asiento de auditoría y las cookies se borran

#### Escenario: [I191] Cambia solo uno

- **DADO** la misma familia
- **CUANDO** se refresca con `agente-dos` desde `203.0.113.9`, y en otra ejecución con `agente-uno` desde
  `198.51.100.7`
- **ENTONCES** ambas rotan con `200` y la familia sigue viva

#### Escenario: [I192] Familia sin IP o sin agente registrados

- **DADO** una familia emitida sin origen (IP nula y huella de «sin agente»)
- **CUANDO** se refresca desde otra IP y otro agente
- **ENTONCES** rota con `200`

#### Escenario: [I193] Solo se revoca la familia afectada

- **DADO** la cuenta con dos familias vivas `F-1` y `F-2`, y un cambio simultáneo en `F-1`
- **CUANDO** se refresca `F-1`
- **ENTONCES** `F-1` queda revocada y `F-2` sigue viva

#### Escenario: [I225] El orden de decisión: reutilización antes que vinculación, vinculación antes que vencimiento del token

- **DADO** una familia creada con `agente-uno` desde `203.0.113.9`, y presentaciones desde `agente-dos` y
  `198.51.100.7` de: un token ya consumido y no apto para la gracia, y un token propio vencido y sin consumir
- **CUANDO** se presenta cada uno (en familias distintas con la misma configuración)
- **ENTONCES** el primero produce `reuse_detected` y la familia queda revocada con ese motivo
- **Y** el segundo produce `binding_changed` y la familia queda revocada con ese motivo
- **Y** en ambos casos la respuesta es `401 session-expired` con las cookies borradas

#### Escenario: [I226] La dirección se compara como dirección

- **DADO** una familia con `ip_first_seen` igual a `2001:db8::1` y un refresco con otro agente desde
  `2001:0db8:0000:0000:0000:0000:0000:0001`
- **CUANDO** se refresca
- **ENTONCES** la dirección se considera sin cambio, el refresco rota con `200` y la familia sigue viva

### Requisito: El cierre de la sesión actual se ofrece en `DELETE /api/v1/auth/sessions/current` con la cookie, aun con el acceso vencido

El sistema DEBE ofrecer `DELETE /api/v1/auth/sessions/current`, que lee el refresco de la cookie de sesión, exige
`X-CSRF-Token` cuando trae la cookie de sesión, exige siempre un `Origin` permitido, no exige token de acceso y
está bajo la política `admin-session`. DEBE localizar la familia por el hash del refresco presentado, aunque esté
consumido, tomar el bloqueo de esa familia (y no el de la cuenta) y revocarla con el motivo `logout`. DEBE
responder `204` ante toda petición que supere CSRF y `Origin`, también sin cookie de sesión (no hay nada que
cerrar y no se exige token CSRF), con un refresco desconocido o mal formado, de otra institución, de una familia
vencida o de una familia ya revocada, y borrar las dos cookies. Una familia ya revocada o vencida, o un refresco
desconocido, NO DEBE producir efecto ni asiento; en particular, una familia ya revocada NO DEBE cambiar su motivo
ni su instante. Ninguna otra familia de la cuenta DEBE tocarse. El
cierre y una rotación concurrentes DEBEN serializarse por el bloqueo de la familia y dejar la familia revocada
sin token vigente. Revocar DEBE escribir un asiento de auditoría sin secretos.

#### Escenario: [I194] Cierre con el acceso vencido

- **DADO** una sesión cuyo token de acceso venció hace una hora y cuyo refresco sigue vigente
- **CUANDO** se envía el cierre con cookie, CSRF y `Origin` válidos
- **ENTONCES** la respuesta es `204`, la familia queda revocada con `logout` y se emiten dos `Set-Cookie` de
  borrado

#### Escenario: [I195] Un refresco ya consumido también cierra la familia

- **DADO** un refresco `RT-8001` rotado hace una hora, cuya familia sigue viva con `RT-8002`
- **CUANDO** se cierra con la cookie de `RT-8001`
- **ENTONCES** la respuesta es `204` y la familia queda revocada con `logout`

#### Escenario: [I196] Uniformidad ante refresco desconocido o familia revocada

- **DADO** un refresco desconocido con CSRF válido, y el de una familia revocada hace dos horas con
  `reuse_detected`
- **CUANDO** se envía el cierre
- **ENTONCES** ambos responden `204` idénticos
- **Y** la familia revocada conserva `reuse_detected` y su instante, y no se escribe asiento de cierre nuevo

#### Escenario: [I197] Solo la sesión actual

- **DADO** la cuenta con dos familias vivas
- **CUANDO** se cierra una por cookie
- **ENTONCES** la otra sigue viva con todos sus tokens

#### Escenario: [I198] Cierre y rotación concurrentes

- **DADO** dos transacciones reales y sincronizadas, una de cierre y otra de rotación del mismo refresco
- **CUANDO** terminan
- **ENTONCES** la familia está revocada con `logout` y ningún token suyo está vigente, sea cual sea el orden del
  bloqueo

#### Escenario: [I199] Con cookie de sesión y sin cabecera CSRF no hay cierre

- **DADO** una petición con cookie de sesión, `Origin` permitido y sin `X-CSRF-Token`
- **CUANDO** se envía
- **ENTONCES** responde `403 csrf-invalid` y la familia sigue viva

#### Escenario: [I227] Sin cookie de sesión el cierre responde `204`

- **DADO** una petición de cierre sin cookie de sesión, sin `X-CSRF-Token` y con `Origin` permitido (un cliente
  que ya cerró sesión)
- **CUANDO** se envía
- **ENTONCES** responde `204` (no `403`) con los dos `Set-Cookie` de borrado, sin efecto ni asiento
- **Y** la misma petición sin `Origin` permitido responde `403 csrf-invalid`

### Requisito: «Cerrar todas» se ofrece en `DELETE /api/v1/auth/sessions` con el token de acceso y revoca todas las familias vivas de la cuenta

El sistema DEBE ofrecer `DELETE /api/v1/auth/sessions`, autenticada con un token de acceso de sesión viva
(`LIVE_SESSION`) y un `Origin` permitido, bajo la política `admin-session`. DEBE revocar con el motivo `logout`
todas las familias vivas de la cuenta, incluida la actual, en una transacción; responder `204`; borrar las dos
cookies; y escribir un asiento con el número de familias revocadas. NO DEBE tocar familias de otra cuenta ni de
otra institución, ni modificar familias ya revocadas. Un token de una sesión ya revocada DEBE responder
`401 token-invalid`; sin `Origin` permitido, `403 csrf-invalid`.

#### Escenario: [I200] Cierra el cien por ciento de las familias vivas y ninguna ajena

- **DADO** la cuenta `ana.martinez@colegio.edu.hn` con tres familias vivas y una revocada, y
  `carlos.ramirez@colegio.edu.hn` con dos vivas
- **CUANDO** `ana.martinez` cierra todas con el acceso de una de ellas
- **ENTONCES** las tres vivas quedan revocadas con `logout`, la ya revocada conserva su motivo y su instante, y
  las de `carlos.ramirez` siguen vivas
- **Y** la respuesta es `204` con las cookies borradas y el asiento registra tres

#### Escenario: [I201] Una segunda llamada con el mismo acceso falla

- **DADO** el acceso de la familia ya revocada por el escenario anterior, todavía dentro de su vigencia
- **CUANDO** se repite la petición
- **ENTONCES** responde `401 token-invalid`

#### Escenario: [I202] Sin `Origin` permitido

- **DADO** un acceso válido y un `Origin` ausente o no permitido
- **CUANDO** se envía
- **ENTONCES** responde `403 csrf-invalid` y ninguna familia cambia

### Requisito: El catálogo de motivos de revocación es cerrado y tiene cinco motivos, cada uno con su productor

El sistema DEBE admitir como motivo de revocación de una familia exactamente `reuse_detected`, `password_change`,
`logout`, `mfa_change` y `binding_changed`, y NO DEBE admitir `admin_revoke` ni ningún motivo sin productor. Los
productores son: la reutilización detectada; el restablecimiento de contraseña; el cierre de la sesión actual y
«cerrar todas»; la primera verificación de una credencial TOTP; y la vinculación al refrescar.

#### Escenario: [I203] Cada motivo tiene un productor probado

- **DADO** cada uno de los cinco flujos
- **CUANDO** se ejecuta
- **ENTONCES** la familia afectada queda con el motivo correspondiente

#### Escenario: [I204] Un motivo sin productor se rechaza

- **DADO** la restricción de `revoked_reason` tras `V9`
- **CUANDO** se intenta guardar `admin_revoke` y los cinco motivos del catálogo
- **ENTONCES** la base rechaza `admin_revoke` y acepta los cinco

### Requisito: El origen de la petición llega a los comandos de identidad

`AuthenticationCommand` y `RotateRefreshTokenCommand` DEBEN llevar el origen de la petición que necesitan: la IP
del cliente (para la señal de relleno y para la vinculación) y el agente de usuario. El origen ausente (una
llamada sin petición HTTP) DEBE aceptarse. El origen de una petición NO DEBE filtrarse a otra, también con
peticiones concurrentes.

#### Escenario: [I205] El comando lleva la IP y el agente de la petición

- **DADO** un inicio de sesión desde `203.0.113.9` con `agente-prueba`
- **CUANDO** el caso de uso recibe el comando
- **ENTONCES** el comando lleva esa IP y la huella de ese agente, y el asiento de autenticación lleva ambos

#### Escenario: [I206] Comando sin origen

- **DADO** una llamada de prueba sin petición HTTP
- **CUANDO** se ejecuta la autenticación
- **ENTONCES** se acepta con IP nula y sin agente

### Requisito: Tras un restablecimiento ningún token de la cuenta queda vigente (seguimiento F1a)

Una prueba de integración DEBE afirmar de forma directa, y no solo por efecto indirecto, que tras un
restablecimiento de contraseña ningún token de la cuenta queda vigente: todo refresco de toda familia de la
cuenta presentado a la rotación produce `Rejected` con `family_revoked`, y todo token de acceso de esas familias
presentado a una ruta con comprobación de sesión produce `401 token-invalid`.

#### Escenario: [I207] Ningún refresco ni acceso de la cuenta sirve tras restablecer

- **DADO** una cuenta con tres familias, cada una con refresco vigente y token de acceso vigente, y una cuenta
  ajena con una
- **CUANDO** se completa el restablecimiento de la primera
- **ENTONCES** los tres refrescos dan `Rejected` con `family_revoked` y los tres accesos dan `401 token-invalid`
- **Y** el refresco y el acceso de la cuenta ajena siguen sirviendo

### Requisito: El revocador de cuenta no cruza instituciones (seguimiento F1b)

La revocación de todas las familias vivas de una cuenta DEBE alcanzar solo familias de la institución del
contexto. Una segunda institución de fixture con familias vivas NO DEBE ver cambio alguno al revocar en la
primera, ni con la misma cuenta ni con otra.

#### Escenario: [I208] Revocar en A no toca B

- **DADO** las instituciones A y B, cada una con dos familias vivas, con PostgreSQL real y el rol
  `confia_admin_app`
- **CUANDO** se revocan todas las familias de una cuenta de A, y por separado se cierran todas las de A
- **ENTONCES** las familias de B siguen vivas con todos sus tokens vigentes

### Requisito: Un flujo completo de sesión no deja secretos en registros (obligación O9 y seguimiento F3)

Un flujo completo (inicio de sesión, segundo factor, refresco, cierre) NO DEBE dejar en ningún registro, a nivel
`TRACE`, la contraseña, el token de acceso, el refresco, el token restringido, el token CSRF, el secreto TOTP,
un código de recuperación ni las cabeceras `Authorization` y `Cookie` (ADR-0005, verificación 12). La prueba
DEBE tener un control negativo que detecte un valor sensible registrado a propósito. El mapa de secretos de
`SessionSecretsNotObservableTest` DEBE incluir el `jti` del token de acceso y el del token restringido.

#### Escenario: [I209] El flujo completo no deja secretos

- **DADO** un inicio de sesión, una verificación TOTP, un refresco y un cierre, con captura de todos los
  registros
- **CUANDO** se buscan los valores sensibles del flujo
- **ENTONCES** ninguno aparece

#### Escenario: [I210] El control negativo detecta una fuga

- **DADO** el mismo mecanismo y un token CSRF registrado a propósito
- **CUANDO** se revisa la captura
- **ENTONCES** la verificación reporta la violación

#### Escenario: [I211] El `jti` forma parte de los secretos vigilados

- **DADO** `SessionSecretsNotObservableTest` con el `jti` del acceso y del restringido en el mapa
- **CUANDO** se ejecuta contra una variante que expone el `jti` en `toString()`
- **ENTONCES** la prueba falla

### Requisito: Los números de `epochSecond` de las claims se aceptan solo en su rango entero (seguimiento F2)

El lector de claims DEBE aceptar `exp` e `iat` solo como números enteros JSON entre 0 y
253402300799 (9999-12-31T23:59:59Z), ambos inclusive. Todo otro valor DEBE producir un token inválido: época
negativa, un valor mayor que el máximo, `Long.MAX_VALUE`, números decimales y cadenas. Los ocho mutantes
sobrevivientes de `ClaimReader` DEBEN quedar eliminados.

#### Escenario: [I212] Los bordes del rango

- **DADO** claims con `iat` igual a -1, 0, 253402300799 y 253402300800, y con `exp` igual a `Long.MAX_VALUE`
- **CUANDO** el lector las procesa
- **ENTONCES** -1, 253402300800 y `Long.MAX_VALUE` se rechazan como inválidos y 0 y 253402300799 superan el
  lector

#### Escenario: [I213] Decimales y cadenas

- **DADO** `exp` igual a `1.5`, a `1760263800.0` y a la cadena `"1760263800"`
- **CUANDO** el lector las procesa
- **ENTONCES** las tres se rechazan como inválidas

### Requisito: Ausencia del bloqueo de una hora por relleno de credenciales (brecha con destino: cambio 11)

El sistema NO DEBE bloquear a una IP durante una hora por fallos contra cinco identificadores distintos
(`docs/03-seguridad.md` §4.4): este cambio entrega solo la señal sin bloqueo. El bloqueo depende de Redis y de la
lista de proxies de confianza, del **cambio 11** (`containerization-and-cicd-pipeline`), porque con la lista
vacía todo el tráfico detrás de un proxy compartiría la restricción.

#### Escenario: [I214] Seis identificadores distintos no bloquean a la IP

- **DADO** una IP con fallos contra seis identificadores distintos en 10 minutos, con la capa 1 sin agotar y
  menos de 10 fallos en total
- **CUANDO** envía un intento con contraseña correcta
- **ENTONCES** autentica con normalidad
- **Y** este escenario deja de ser cierto el día que el cambio 11 entregue el bloqueo

### Requisito: Ausencia de revocación por desactivación de cuenta y de `admin_revoke` (brecha con destino: cambio 8)

El sistema NO DEBE revocar ninguna familia por la desactivación de una cuenta ni ofrecer el motivo `admin_revoke`.
Ambos son del **cambio 8** (`rbac-permission-matrix-and-audit-integration`), con su propia migración.

#### Escenario: [I215] Desactivar una cuenta no existe y `admin_revoke` se rechaza

- **DADO** el conjunto de casos de uso públicos de `identity` y la restricción de `revoked_reason`
- **CUANDO** se busca un caso de uso de desactivación de cuenta y se intenta guardar `admin_revoke`
- **ENTONCES** no existe el caso de uso y la base rechaza el motivo
- **Y** este escenario deja de ser cierto el día que el cambio 8 los entregue

### Requisito: Ausencia de inscripción y baja voluntarias del segundo factor, de step-up y de vista de sesiones activas (brechas con destino: cambio 8 y primer cambio de `apps/admin-web`)

El sistema NO DEBE ofrecer inscripción ni baja voluntarias del segundo factor con una sesión completa, ni elevar
una sesión `[pwd]` a `[pwd, otp]`, ni política de rutas que exijan `otp` (DA-12): son del **cambio 8**. Tampoco
DEBE ofrecer la vista de sesiones activas ni la columna `device_label` (DA-6): son del primer cambio de
`apps/admin-web` que la muestre. Cambiar la contraseña con sesión («todas salvo la actual») es del cambio que
entregue ese flujo.

#### Escenario: [I216] Ninguna ruta de inscripción con sesión completa ni de listado de sesiones

- **DADO** el mapa de rutas de producción del proceso administrativo
- **CUANDO** se busca una ruta que acepte un token de acceso completo para inscribir o dar de baja MFA, o que
  liste sesiones
- **ENTONCES** no existe ninguna
- **Y** este escenario deja de ser cierto el día que sus cambios dueños las entreguen


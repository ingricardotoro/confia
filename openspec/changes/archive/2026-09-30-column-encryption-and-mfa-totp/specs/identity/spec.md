# Delta para Identidad

## ADDED Requirements

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

### Requisito: Ausencia de envío real del aviso de códigos de recuperación de MFA bajos (brecha sin destino identificado en el roadmap)

El sistema NO DEBE enviar, en este cambio, ningún correo ni notificación real al quedar una cuenta
con menos de tres códigos de recuperación de MFA sin usar. Este cambio entrega únicamente el dato
calculado y auditado —ver el requisito «Aviso al quedar con menos de tres códigos de recuperación de
MFA sin usar»—, porque ningún cambio de esta secuencia entrega un adaptador de envío de correo. El
destino del envío real queda sin identificar en el roadmap actual.

#### Escenario: El aviso queda calculado y auditado, sin ningún correo enviado

- **DADO** una cuenta que consume su octavo código de recuperación de MFA, dejando dos sin usar
- **CUANDO** el sistema calcula la señal de aviso
- **ENTONCES** audita el evento con el conteo de códigos restantes y la señal de «por debajo de
  tres»
- **Y** no invoca ningún adaptador de envío de correo, porque ninguno existe en este cambio

## MODIFIED Requirements

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

# Delta para Identidad

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `session-endpoints-cookie-and-csrf` (F0, cambio 7, parte 4b, segundo cambio S2 de tres)
- **Propuesta:** `proposal.md`, con DA-1 a DA-15 confirmadas por el propietario el 2026-10-20

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `I149` en adelante, a continuación del máximo de la especificación vigente
  (`I148`). Los escenarios que se conservan de la especificación vigente no llevan número, salvo los que ya lo
  tenían, que lo conservan.
- Un escenario marcado «(solo documental)» no tiene aserción automática.
- Este delta describe comportamiento observable; los nombres de clases aparecen solo cuando una especificación
  vigente ya los usa. El diseño fija los nombres de los asientos de auditoría nuevos.
- «Secreto verificado» y «credencial verificada» significan que la credencial TOTP aceptó al menos un código
  TOTP (contador aceptado mayor o igual que cero). «Inscrita pero nunca verificada» es el estado
  inmediatamente posterior a la inscripción.
- «Respuesta de sesión» es el cuerpo `200` con `status` igual a `authenticated`, `accessToken`,
  `accessTokenExpiresAt`, `sessionExpiresAt` e `idleExpiresAt`, más las cookies de sesión y CSRF. No contiene
  datos personales ni el `sid`.
- Cuando un requisito dice «se rechaza como `401 session-expired`», la respuesta DEBE ser idéntica en estado,
  `type`, `title`, `detail` y cabeceras salvo `instance` y el identificador de traza.

## ADDED Requirements

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
`Cache-Control: no-store`. La ruta NO DEBE usar `Idempotency-Key`.

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

#### Escenario: [I156] El retardo del retroceso se materializa después de confirmar y no deniega

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn` con tres fallos consecutivos y la contraseña correcta
- **CUANDO** inicia sesión
- **ENTONCES** la respuesta es `200` con sesión, enviada después de la espera del retardo exigible
- **Y** la transacción ya estaba confirmada al empezar la espera

### Requisito: El `401 authentication-failed` es idéntico para cuenta existente e inexistente y su tiempo no distingue la cuenta

La respuesta `401 authentication-failed` DEBE ser idéntica en estado, cuerpo, `type`, `title`, `detail` y
cabeceras (salvo `instance` y el identificador de traza) para una contraseña incorrecta de una cuenta existente
y para un identificador inexistente, incluidos los casos con retardo cero. La diferencia entre las medianas del
tiempo de respuesta de 200 intentos con cuenta existente y 200 con cuenta inexistente NO DEBE superar 50 ms
(`docs/03-seguridad.md` §4.6; ADR-0005, verificación 7). Ningún cuerpo, cabecera ni `Set-Cookie` DEBE revelar el
motivo interno del rechazo.

#### Escenario: [I157] Igualdad exacta de la respuesta

- **DADO** un intento con contraseña incorrecta contra `maria.lopez@colegio.edu.hn` y otro contra
  `nadie.registrado@colegio.edu.hn`, ambos sin fallos previos
- **CUANDO** se comparan las dos respuestas
- **ENTONCES** estado, cuerpo y cabeceras son idénticos salvo `instance` y el identificador de traza

#### Escenario: [I158] Igualdad con retardo

- **DADO** las mismas dos identidades con tres fallos previos cada una
- **CUANDO** se envía el cuarto intento a cada una
- **ENTONCES** ambas respuestas son idénticas en forma y esperan el mismo retardo de 2 segundos

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
estructurado y un asiento de auditoría, ambos sin identificadores en claro. La señal NO DEBE bloquear a la IP ni
alterar la respuesta de ningún intento (el bloqueo de 1 hora es del cambio 11). Cuatro identificadores distintos,
o cinco fallos contra el mismo identificador, NO DEBEN producir la señal.

#### Escenario: [I164] El quinto identificador distinto dispara la señal

- **DADO** una IP con fallos contra cuatro identificadores distintos entre las 10:00:00 y las 10:05:00
- **CUANDO** falla un quinto identificador distinto a las 10:06:00
- **ENTONCES** se emite el `WARN` estructurado y se escribe el asiento de auditoría, ambos con la huella y sin el
  identificador en claro
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
- **ENTONCES** se rechaza como `second-factor-invalid`, el contador sigue en -1 y el código sigue sin usar

### Requisito: La verificación del segundo factor se ofrece en `POST /api/v1/auth/mfa/verifications` con un token restringido

El sistema DEBE ofrecer `POST /api/v1/auth/mfa/verifications`, autenticada con `Authorization: Bearer` de un
token restringido, bajo la política `admin-mfa` y con el retardo del retroceso TOTP materializado tras confirmar.
El cuerpo DEBE contener exactamente uno de `code` o `recoveryCode`. La ruta DEBE aceptar el token con uso
`mfa-verify` y, mientras la credencial de la cuenta no esté verificada, también el token con uso `mfa-enroll`;
con una credencial verificada, el token `mfa-enroll` DEBE rechazarse con `401 token-invalid`. Una verificación
correcta DEBE emitir una familia con `second_factor_verified` en `true` (`amr` igual a `[pwd, otp]`) y responder
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

#### Escenario: [I174] Token vencido, de acceso, de otro uso o con credencial verificada

- **DADO** un token restringido vencido, un token de acceso vigente, y un token `mfa-enroll` de una cuenta con
  credencial verificada
- **CUANDO** se presenta cada uno
- **ENTONCES** el primero recibe `401 token-expired` y los otros dos `401 token-invalid`, y ninguno produce
  cookies

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
con el número de familias revocadas, también cero. Las verificaciones posteriores de una credencial ya
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
`mfa-already-enrolled` sin modificar nada. Cada inscripción o reinscripción DEBE auditarse (`identity.mfa.enrolled`).
Una violación de unicidad por inscripción concurrente DEBE responder `409` y nunca `500`. El secreto y los
códigos NO DEBEN aparecer en registros. La inscripción no activa la credencial.

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
- **Y** los diez códigos de recuperación previos quedan inutilizados y un código de `S1` ya no se acepta
- **Y** existen dos asientos `identity.mfa.enrolled`

#### Escenario: [I182] Credencial verificada responde `409`

- **DADO** una cuenta con credencial verificada y un token `mfa-enroll` vigente
- **CUANDO** se envía la inscripción
- **ENTONCES** la respuesta es `409` con `mfa-already-enrolled` y el secreto almacenado no cambia

#### Escenario: [I183] Dos inscripciones concurrentes no producen `500`

- **DADO** una cuenta sin credencial y dos peticiones sincronizadas con `CountDownLatch`
- **CUANDO** terminan las dos
- **ENTONCES** ninguna responde `500`, existe exactamente una credencial, y la respuesta que perdió fue `409` o
  una reinscripción coherente con un único secreto vigente

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
sesión, exige `X-CSRF-Token` y un `Origin` permitido, y está bajo la política `admin-session`. Una rotación
`Rotated` (de tipo `fresh` o `grace`) DEBE responder `200` con la respuesta de sesión y reemitir las dos cookies;
en la gracia, la cookie de sesión lleva el mismo refresco sucesor y la cookie CSRF se reemite con el vínculo de
ese mismo refresco. Todo desenlace `Rejected` (`unknown_token`, `family_revoked`, `family_expired`,
`token_expired`, `reuse_detected`) DEBE responder `401 session-expired` uniforme y borrar las dos cookies; la
reutilización detectada conserva su revocación y su asiento. Sin cookie de sesión la petición no puede presentar
un vínculo CSRF válido y DEBE responder `403 csrf-invalid`. La respuesta NO DEBE contener datos personales ni el
`sid`.

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

#### Escenario: [I189] Sin cookie o sin cabecera CSRF

- **DADO** una petición sin cookie de sesión y otra con cookie y sin `X-CSRF-Token`
- **CUANDO** se envían
- **ENTONCES** ambas responden `403 csrf-invalid` y el caso de uso registra cero invocaciones

### Requisito: Si cambian a la vez el agente y la IP respecto de la creación, el refresco se rechaza y la familia se revoca con `binding_changed`

Al refrescar, el sistema DEBE comparar la huella del agente de usuario y la IP del cliente de la petición con las
registradas en la familia al crearse. Si **ambas** difieren, DEBE rechazar el refresco como `401 session-expired`,
revocar la familia con el motivo `binding_changed`, vaciar sus sucesores cifrados, escribir un asiento de
auditoría y borrar las cookies. Si solo una difiere, o si el valor registrado de alguna de las dos es nulo, el
refresco NO DEBE rechazarse por este motivo. La comprobación se aplica a toda presentación que de otro modo
produciría `Rotated`; un rechazo previo (familia revocada o vencida, reutilización) conserva su desenlace. La
revocación NO DEBE afectar a otras familias.

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

### Requisito: El cierre de la sesión actual se ofrece en `DELETE /api/v1/auth/sessions/current` con la cookie, aun con el acceso vencido

El sistema DEBE ofrecer `DELETE /api/v1/auth/sessions/current`, que lee el refresco de la cookie de sesión, exige
`X-CSRF-Token` y un `Origin` permitido, no exige token de acceso y está bajo la política `admin-session`. DEBE
localizar la familia por el hash del refresco presentado, aunque esté consumido, tomar el bloqueo de esa familia
y revocarla con el motivo `logout`. DEBE responder `204` ante toda petición que supere CSRF y `Origin`, también
con un refresco desconocido, de otra institución o de una familia ya revocada, y borrar las dos cookies. Una
familia ya revocada NO DEBE cambiar su motivo ni su instante. Ninguna otra familia de la cuenta DEBE tocarse. El
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

#### Escenario: [I199] Sin cabecera CSRF no hay cierre

- **DADO** una petición con cookie y sin `X-CSRF-Token`
- **CUANDO** se envía
- **ENTONCES** responde `403 csrf-invalid` y la familia sigue viva

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

## MODIFIED Requirements

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

## REMOVED Requirements

### Requisito: Ausencia de la aplicación de la vinculación por agente de usuario e IP (brecha con destino: `session-endpoints-cookie-and-csrf`)

(Reason: este cambio aplica la vinculación al refrescar (DA-8 *b*): si cambian a la vez el agente y la IP, el
refresco se rechaza y la familia se revoca con `binding_changed`. El escenario «Un cambio simultáneo de agente y
de IP no exige reautenticación todavía» deja de ser cierto, como el requisito anunciaba.)
(Migration: sustituido por «Si cambian a la vez el agente y la IP respecto de la creación, el refresco se
rechaza y la familia se revoca con `binding_changed`» y por la versión modificada de «La familia registra la
huella del agente de usuario y la IP inicial, y la vinculación se aplica al refrescar».)

### Requisito: Ausencia de revocación de sesiones por cambio de MFA y por desactivación de cuenta (brecha con destino: `session-endpoints-cookie-and-csrf` y cambio 8)

(Reason: este cambio entrega la revocación por cambio de MFA (`mfa_change`) y `logout` con la migración `V9`;
los escenarios «Inscribir el segundo factor no revoca ninguna familia» y «El catálogo de motivos no declara
motivos sin productor» dejan de ser ciertos. La desactivación de cuenta y `admin_revoke` siguen siendo del
cambio 8.)
(Migration: sustituido por «La primera verificación correcta activa la credencial y revoca las demás sesiones con
el motivo `mfa_change`», «El catálogo de motivos de revocación es cerrado y tiene cinco motivos, cada uno con su
productor» y «Ausencia de revocación por desactivación de cuenta y de `admin_revoke` (brecha con destino: cambio
8)».)

### Requisito: Ausencia de la dimensión por dirección IP del retroceso exponencial (brecha con destino: `session-endpoints-cookie-and-csrf` y cambio 11)

(Reason: este cambio aplica el control por IP del inicio de sesión (capas 1 y 2 del limitador) y la señal de
cinco identificadores distintos, y cierra la condición dura H1; el escenario «Ninguna regla de dirección IP se
aplica todavía» deja de ser cierto. Solo el bloqueo de una hora y el aprovisionamiento sobre Redis siguen
ausentes.)
(Migration: sustituido por «El control por IP del inicio de sesión se aplica antes del caso de uso y cierra la
condición H1», «Cinco identificadores distintos con fallo desde una IP en diez minutos producen una señal, sin
bloqueo» y «Ausencia del bloqueo de una hora por relleno de credenciales (brecha con destino: cambio 11)».)

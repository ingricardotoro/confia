# Delta para Identidad

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `password-recovery-endpoints` (F0, cambio 7, parte 4b, tercer cambio S3)
- **Propuesta:** `proposal.md`, aprobada el 2026-11-09 con las decisiones P1 a P7 del propietario

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `I312` en adelante, a continuación del máximo de los deltas en curso
  (`I303` en `rbac-permission-matrix-and-audit-integration` e `I311` en `admin-web-staff-sign-in`), para no
  chocar con ellos al archivar. Este cambio ocupa `I312` a `I319` y `I333` a `I355`; `transactional-email-adapter` ocupa `I320`
  a `I332`, de modo que los números `I320` a `I332` no se usan aquí. Los escenarios que se conservan de la especificación vigente no llevan número,
  salvo los que ya lo tenían, que lo conservan.
- Un escenario marcado «(solo documental)» no tiene aserción automática.
- Este delta supone el código posterior a la parte 5a de rbac (decisión P7): «MFA activa» significa que la cuenta
  tiene una credencial TOTP verificada, y la exigencia del segundo factor la decide la política de MFA, no la
  columna `mfa_required`.
- «Ruta de solicitud» es `POST /api/v1/auth/password-reset-requests` y «ruta de confirmación» es
  `POST /api/v1/auth/password-resets` (decisión P4).
- «Respuesta aceptada» es el `202 Accepted` de la ruta de solicitud, con el cuerpo `{"message": "<texto>"}`, donde
  el texto es la entrada es-HN del catálogo de mensajes del backend «Si la dirección corresponde a una cuenta, se
  enviará un enlace.»
- «Idénticas» aplicado a dos respuestas significa: el mismo estado, el mismo cuerpo byte a byte salvo `traceId`
  cuando lo hay, y las mismas cabeceras con los mismos valores salvo `Date`.
- «Origen de la petición» es la IP del cliente resuelta por el filtro de origen y el agente de usuario saneado.

## ADDED Requirements

### Requisito: La solicitud de recuperación se ofrece en `POST /api/v1/auth/password-reset-requests` y siempre responde `202` uniforme

El sistema DEBE ofrecer la ruta de solicitud sin credencial previa, con cuerpo JSON `{"identifier": <texto>}`, donde
el identificador es obligatorio, no vacío y de 320 caracteres como máximo. Un cuerpo válido DEBE recibir la
respuesta aceptada, idéntica para una cuenta existente, para un identificador sin cuenta y para una cuenta cuya
emisión omitirá el límite por hora. Un cuerpo con forma inválida (identificador ausente, vacío o demasiado largo,
JSON mal formado o una propiedad no declarada) DEBE recibir `400` con `validation-failed`, sin invocar el caso de
uso, y esa respuesta NO DEBE depender de si el identificador corresponde a una cuenta. La institución DEBE tomarse
de la configuración del proceso y NUNCA del cuerpo ni de una cabecera. La ruta NO DEBE exigir `Idempotency-Key`,
NO DEBE emitir cookies y NO DEBE crear sesión.

#### Escenario: [I312] Cuenta existente

- **DADO** la cuenta de personal `maria.lopez@colegio.edu.hn` y un `Origin` permitido
- **CUANDO** se envía `{"identifier":"maria.lopez@colegio.edu.hn"}` a la ruta de solicitud
- **ENTONCES** la respuesta es la respuesta aceptada, sin `Set-Cookie`
- **Y** queda programada exactamente una emisión para la cuenta y se escribe un asiento de solicitud

#### Escenario: [I313] Las tres respuestas son idénticas

- **DADO** la cuenta `maria.lopez@colegio.edu.hn`, la cuenta `carlos.ramirez@colegio.edu.hn` con tres tokens
  emitidos en la última hora, y el correo `nadie.registrado@colegio.edu.hn`, que no corresponde a ninguna cuenta
- **CUANDO** se envía una solicitud para cada uno, cada una desde una IP distinta
- **ENTONCES** las tres respuestas son idénticas
- **Y** una variante de prueba que cambia el texto del cuerpo solo para la cuenta inexistente hace fallar la
  comparación (control negativo)

#### Escenario: [I314] Cuerpo inválido

- **DADO** cinco cuerpos: sin `identifier`, con `identifier` vacío, con 321 caracteres, con JSON mal formado y con
  la propiedad adicional `institutionId`
- **CUANDO** se envía cada uno a la ruta de solicitud
- **ENTONCES** los cinco reciben `400` con `validation-failed`, el caso de uso registra cero invocaciones y no se
  escribe ningún asiento

#### Escenario: [I315] La institución no la elige el cliente

- **DADO** un proceso configurado para la institución A y una cuenta `ana.martinez@colegio.edu.hn` de A
- **CUANDO** se envía la solicitud con la cabecera `X-Institution-Id` de la institución B
- **ENTONCES** la respuesta es la respuesta aceptada y la emisión y el asiento quedan en la institución A

### Requisito: El límite de 10 solicitudes por hora por IP se aplica antes del caso de uso y cierra la condición H3

La ruta de solicitud DEBE estar protegida por la política `admin-password-reset-request` del limitador por IP, con
10 solicitudes por hora por dirección IP, aplicada antes de leer el cuerpo y antes de abrir cualquier transacción.
Al exceder el límite, el sistema DEBE responder `429` con `too-many-requests` y `Retry-After` (decisión P2), sin
invocar el caso de uso y sin escribir ningún asiento. El límite por IP NO DEBE afectar a otra dirección. El límite
por cuenta (tres emisiones por hora) DEBE seguir decidiéndose en la emisión y DEBE seguir siendo silencioso: la
solicitud de una cuenta en su límite recibe la respuesta aceptada. Con la decisión P1, el limitador en memoria del
proceso es el tope equivalente de H3 hasta el cambio 11, con las limitaciones declaradas para H1: el estado es por
proceso, con `confia.web.trusted-proxies` vacío el tráfico detrás de un proxy comparte un mismo cubo, y el estado
compartido sobre Redis es del cambio 11.

#### Escenario: [I316] La undécima solicitud de una hora desde una IP se rechaza

- **DADO** la política `admin-password-reset-request` con sus valores por defecto y la IP `203.0.113.20`
- **CUANDO** esa IP envía 11 solicitudes válidas en la misma hora
- **ENTONCES** las 10 primeras reciben la respuesta aceptada y la undécima recibe `429` con `too-many-requests` y
  `Retry-After` positivo
- **Y** el caso de uso registra exactamente 10 invocaciones y la bitácora exactamente 10 asientos de solicitud

#### Escenario: [I317] Otra IP no se ve afectada

- **DADO** la IP `203.0.113.20` con su cupo de la hora agotado
- **CUANDO** la IP `203.0.113.21` envía una solicitud
- **ENTONCES** recibe la respuesta aceptada

#### Escenario: [I318] El límite por cuenta sigue siendo silencioso

- **DADO** la cuenta `carlos.ramirez@colegio.edu.hn`, con tres tokens emitidos en la última hora
- **CUANDO** se solicita recuperación para ella desde una IP con cupo
- **ENTONCES** la respuesta es la respuesta aceptada, idéntica a la de una cuenta sin tokens

#### Escenario: [I319] Cierre de H3 (solo documental)

- **DADO** el informe de archivo de este cambio y las notas fechadas de `docs/03-seguridad.md` §4.7 y §10 y de
  `foundations-plan/exploration.md`
- **CUANDO** se leen
- **ENTONCES** declaran cerrada H3 con referencia a [I316] y a la decisión P1, y el texto original de H3 no fue
  modificado

### Requisito: El tiempo de la respuesta de solicitud no distingue la cuenta y una puerta lo mide sobre HTTP

Sobre el proceso administrativo real y una base PostgreSQL real, la diferencia entre la mediana del tiempo de
respuesta de 200 solicitudes para una cuenta existente y la mediana de 200 solicitudes para identificadores sin
cuenta DEBE ser de 50 ms como máximo (`docs/03-seguridad.md` §4.6), alternando los dos tipos de solicitud, con un
precalentamiento de cada uno y cada solicitud desde una IP propia. La medición NO DEBE depender del límite por IP:
las pruebas elevan el límite de la política por propiedades, sin tocar el código bajo prueba. Este requisito, con la
uniformidad de [I313] y la cabecera `Referrer-Policy: no-referrer` de las dos rutas, cierra la condición H4.

#### Escenario: [I333] La puerta pasa con el código real

- **DADO** el proceso administrativo arrancado por su camino de producción con la política elevada
- **CUANDO** se ejecutan 200 solicitudes de cuenta existente y 200 de identificadores sin cuenta, alternadas
- **ENTONCES** la diferencia de medianas es de 50 ms como máximo y el mensaje de la aserción informa las dos
  medianas

#### Escenario: [I334] Cierre de H4 (solo documental)

- **DADO** el informe de archivo de este cambio y las notas fechadas de `docs/03-seguridad.md` §4.6 y de
  `foundations-plan/exploration.md`
- **CUANDO** se leen
- **ENTONCES** declaran cerrada H4 con referencia a [I313], [I333], al control negativo de la puerta y a la
  cabecera `no-referrer`, y el texto original de H4 no fue modificado

### Requisito: La confirmación se ofrece en `POST /api/v1/auth/password-resets` y traduce los cinco desenlaces del restablecimiento

El sistema DEBE ofrecer la ruta de confirmación sin credencial previa, con cuerpo JSON `{"token": <texto>,
"newPassword": <texto>, "code": <texto opcional>, "recoveryCode": <texto opcional>}`. El token DEBE viajar solo en
el cuerpo; la ruta NO DEBE leerlo de la ruta, de la consulta ni de una cabecera. Como forma, el token es obligatorio,
no vacío y de 128 caracteres como máximo; la contraseña nueva es obligatoria, no vacía y de 1 024 caracteres como
máximo (tope de transporte; la longitud admitida de 12 a 128 la decide el caso de uso); cada código, si viene, tiene
64 caracteres como máximo, y enviar a la vez `code` y `recoveryCode` es inválido. Un cuerpo con forma inválida DEBE
recibir `400` con `validation-failed` sin invocar el caso de uso. Un código con forma de texto que no puede ser un
código TOTP o de recuperación DEBE tratarse como un segundo factor rechazado, nunca como un `400`. La traducción del
desenlace DEBE ser exhaustiva y exactamente esta:

| Desenlace | Respuesta |
|---|---|
| Completado | `204 No Content`, sin cuerpo, sin `Set-Cookie` y sin sesión |
| Token rechazado (inexistente, vencido, consumido o con forma inválida) | `400` con `password-reset-token-invalid`, una sola respuesta para los cuatro motivos |
| Contraseña rechazada (demasiado corta o demasiado larga) | `422` con `password-length-invalid` |
| Segundo factor faltante | `422` con `password-reset-second-factor-required` |
| Segundo factor rechazado | `422` con `password-reset-second-factor-rejected`, después de materializar el retardo exigido |

El retardo que exige la verificación de un código TOTP DEBE materializarse después de confirmar la transacción,
con el tope configurado de la espera. La institución DEBE tomarse de la configuración del proceso. La ruta NO DEBE
exigir `Idempotency-Key`.

#### Escenario: [I335] Restablecimiento completado sobre HTTP

- **DADO** la cuenta `jorge.aguilar@colegio.edu.hn`, sin credencial TOTP, con la contraseña vigente
  `Cafetal de Copán 2026`, dos familias de sesión vivas y el token vivo `PRT-41a0`
- **CUANDO** se envía `{"token":"PRT-41a0","newPassword":"Montaña de Celaque 2026"}` a la ruta de confirmación
- **ENTONCES** la respuesta es `204` sin cuerpo y sin `Set-Cookie`
- **Y** las dos familias quedan revocadas con el motivo `password_change`
- **Y** `Cafetal de Copán 2026` deja de autenticar y `Montaña de Celaque 2026` autentica

#### Escenario: [I336] Los cuatro motivos del token dan una sola respuesta

- **DADO** un token que nunca se emitió, uno vencido, uno ya consumido y el texto `no-es-un-token`
- **CUANDO** se envía cada uno con una contraseña nueva válida
- **ENTONCES** las cuatro respuestas son idénticas: `400` con `password-reset-token-invalid`
- **Y** el motivo real queda solo en la bitácora

#### Escenario: [I337] Contraseña fuera de longitud

- **DADO** el token vivo `PRT-41a1` de `jorge.aguilar@colegio.edu.hn`
- **CUANDO** se envía con una contraseña nueva de 11 caracteres y, en otra petición, de 129 caracteres
- **ENTONCES** ambas reciben `422` con `password-length-invalid`
- **Y** `PRT-41a1` sigue vivo y la contraseña vigente no cambia

#### Escenario: [I338] Segundo factor faltante con MFA activa

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn`, con credencial TOTP verificada, y su token vivo `PRT-33e1`
- **CUANDO** se envía el token y una contraseña nueva válida, sin `code` ni `recoveryCode`
- **ENTONCES** la respuesta es `422` con `password-reset-second-factor-required`
- **Y** `PRT-33e1` sigue vivo y la contraseña vigente no cambia

#### Escenario: [I339] Segundo factor rechazado, con el retardo materializado

- **DADO** la misma cuenta, su token vivo `PRT-33e2` y códigos TOTP incorrectos previos suficientes para que el
  retroceso TOTP exija un retardo distinto de cero
- **CUANDO** se envía el token, una contraseña nueva válida y otro código TOTP incorrecto
- **ENTONCES** la respuesta es `422` con `password-reset-second-factor-rejected`
- **Y** el materializador recibió la espera exigida después de confirmar la transacción, y el contador de
  retroceso TOTP de la cuenta avanzó

#### Escenario: [I340] Un código mal formado es un segundo factor rechazado

- **DADO** la misma cuenta y su token vivo `PRT-33e3`
- **CUANDO** se envía con `"code":"12ab"` y, en otra petición, con `"recoveryCode":"x"`
- **ENTONCES** ambas reciben `422` con `password-reset-second-factor-rejected`, nunca `400`

#### Escenario: [I341] Dos pruebas a la vez o un cuerpo sin forma

- **DADO** cuatro cuerpos: con `code` y `recoveryCode` a la vez, sin `token`, con `newPassword` de 1 025
  caracteres y con la propiedad adicional `accountId`
- **CUANDO** se envía cada uno a la ruta de confirmación
- **ENTONCES** los cuatro reciben `400` con `validation-failed` y el caso de uso registra cero invocaciones

#### Escenario: [I342] Un código de recuperación de MFA sobre HTTP queda consumido

- **DADO** la cuenta `sofia.mejia@colegio.edu.hn`, con diez códigos de recuperación sin usar y su token vivo
  `PRT-33e4`
- **CUANDO** se envía el token, una contraseña nueva válida y uno de sus códigos de recuperación
- **ENTONCES** la respuesta es `204` y la cuenta conserva nueve códigos sin usar

#### Escenario: [I343] El token en la consulta no se lee

- **DADO** el token vivo `PRT-41a2`
- **CUANDO** se envía la confirmación con `?token=PRT-41a2` en la consulta y un cuerpo sin `token`
- **ENTONCES** la respuesta es `400` con `validation-failed` y `PRT-41a2` sigue vivo

### Requisito: La confirmación está protegida por la política `admin-password-reset`

La ruta de confirmación DEBE estar protegida por la política `admin-password-reset` (decisión P3), con 20 peticiones
por minuto por IP en la capa 1 y una capa 2 que se activa con 10 fallos en 10 minutos, aplicada antes de leer el
cuerpo. Cuentan como fallo de la capa 2 solo los desenlaces token rechazado y segundo factor rechazado, registrados
después de confirmar la transacción y antes de la espera. La contraseña rechazada, el segundo factor faltante, el
restablecimiento completado y un cuerpo inválido NO DEBEN contar como fallo. Al exceder cualquiera de las dos capas
el sistema DEBE responder `429` con `too-many-requests` y `Retry-After`, sin invocar el caso de uso.

#### Escenario: [I344] La vigésima primera petición de un minuto se rechaza

- **DADO** la IP `203.0.113.30`
- **CUANDO** envía 21 confirmaciones en el mismo minuto
- **ENTONCES** la vigésima primera recibe `429` con `Retry-After` y el caso de uso registra 20 invocaciones

#### Escenario: [I345] Diez tokens rechazados activan la restricción

- **DADO** la IP `203.0.113.31` con 9 confirmaciones con token rechazado en 10 minutos
- **CUANDO** envía la décima con token rechazado y otra confirmación a los 30 segundos
- **ENTONCES** la última recibe `429` con `Retry-After`

#### Escenario: [I346] Lo que no es un fallo no restringe

- **DADO** la IP `203.0.113.32`
- **CUANDO** envía 12 confirmaciones con contraseña fuera de longitud y segundo factor faltante, en 10 minutos
- **ENTONCES** ninguna recibe `429` por la capa 2

### Requisito: El origen de la petición llega a los comandos y a los asientos de la recuperación

`RequestPasswordResetCommand` y `ResetPasswordCommand` DEBEN llevar el origen de la petición: la IP del cliente y
el agente de usuario saneado. Los dos comandos DEBEN imprimir su contenido redactado en `toString()`, incluidos el
identificador presentado, el token, la contraseña, los códigos, la IP y el agente. Los asientos que escriben los dos
casos de uso (solicitud, rechazo, revocación de sesiones por restablecimiento y restablecimiento completado) DEBEN
llevar en `source_ip` la IP canónica y en `user_agent` el agente del comando, aun sin un origen ligado al hilo. Un
comando sin origen (llamada sin petición HTTP) DEBE aceptarse con ambos valores nulos. El origen de una petición NO
DEBE filtrarse a otra. Este requisito es la fuente de la IP del aviso al titular que entrega el cambio 14.

#### Escenario: [I347] Los asientos de la solicitud y del restablecimiento llevan el origen

- **DADO** una solicitud y después una confirmación completada desde `203.0.113.9` con `agente-prueba`
- **CUANDO** se leen los asientos `identity.password_reset.requested`, `identity.session.revoked` e
  `identity.password_reset.completed` de esas peticiones
- **ENTONCES** los tres llevan `source_ip` `203.0.113.9` y el agente saneado en `user_agent`

#### Escenario: [I348] El comando lleva el origen aunque no haya origen ligado al hilo

- **DADO** una llamada directa al caso de uso, sin petición HTTP ni origen ligado, con un comando que lleva
  `198.51.100.4` y `agente-prueba`
- **CUANDO** se rechaza el token
- **ENTONCES** el asiento de rechazo lleva esa IP y ese agente

#### Escenario: [I349] Comando sin origen y `toString()` redactado

- **DADO** un comando de solicitud y uno de restablecimiento sin origen, y otros dos con origen y secretos
- **CUANDO** se ejecutan los primeros y se imprimen los segundos
- **ENTONCES** los primeros se aceptan con `source_ip` y `user_agent` nulos
- **Y** la impresión de los segundos no contiene el identificador, el token, la contraseña, los códigos, la IP ni
  el agente

### Requisito: El recorrido HTTP de la recuperación no deja secretos en registros, excepciones ni cuerpos de error

En el recorrido completo de las dos rutas, incluidos los cuerpos inválidos, los rechazos y los `429`, ningún registro
estructurado, mensaje de excepción ni cuerpo de respuesta DEBE contener el identificador presentado en claro, el
token, la contraseña nueva, un código TOTP ni un código de recuperación. Un `400` de validación NO DEBE repetir el
valor rechazado de ningún campo.

#### Escenario: [I350] Centinelas en el recorrido completo

- **DADO** valores centinela únicos para el identificador, el token, la contraseña, el código TOTP y el código de
  recuperación, y la captura del registro estructurado del proceso
- **CUANDO** se recorren la respuesta aceptada, el `429` de la solicitud, los cinco desenlaces de la confirmación,
  sus `400` de validación y su `429`
- **ENTONCES** ningún centinela aparece en el registro, en un mensaje de excepción ni en un cuerpo de respuesta
- **Y** una variante de prueba que registra el cuerpo de la petición hace fallar la búsqueda (control negativo)

#### Escenario: [I351] El `400` no repite el valor rechazado

- **DADO** una confirmación con `newPassword` de 1 025 caracteres que contiene un centinela
- **CUANDO** se lee el cuerpo del `400`
- **ENTONCES** nombra el campo y no contiene el centinela

### Requisito: El enlace de recuperación lleva el token en el fragmento y la confirmación lo recibe en el cuerpo

El token NO DEBE viajar en la ruta ni en la consulta del enlace que construye el cambio 14; DEBE viajar en el
fragmento (`#token=<valor>`, forma acordada con `transactional-email-adapter`) y la página que lo recibe DEBE enviarlo en el cuerpo de la confirmación. Este cambio NO DEBE leer
`PASSWORD_RESET_LINK_BASE_URL` ni construir ningún enlace.

#### Escenario: [I352] Restricción para el cambio 14 y la pantalla (solo documental)

- **DADO** la nota fechada de `docs/03-seguridad.md` §4.7 y la sección de restricciones acordadas de `proposal.md`
- **CUANDO** se leen
- **ENTONCES** fijan el fragmento como único lugar del token en el enlace y el cuerpo como único lugar de la
  confirmación

#### Escenario: [I353] Ningún código de este cambio construye el enlace

- **DADO** el código de producción al cerrar este cambio
- **CUANDO** se busca `PASSWORD_RESET_LINK_BASE_URL` y cualquier construcción de una URL con el token
- **ENTONCES** no hay ninguna referencia

## MODIFIED Requirements

### Requisito: Prohibición de enumeración de usuarios

El sistema NO DEBE revelar, a través de ninguna respuesta de autenticación o de recuperación de
contraseña, si una cuenta con un identificador dado existe o no. La respuesta DEBE ser idéntica
en forma, contenido y tiempo observable de respuesta en ambos casos.

Al nivel del caso de uso, la solicitud de recuperación DEBE devolver el mismo resultado y escribir el
mismo número de asientos de auditoría, con la misma acción, para una cuenta existente, para un
identificador sin cuenta y para una cuenta cuya emisión omitirá el límite por hora. El motivo real
DEBE quedar solo en la bitácora. El identificador presentado DEBE aparecer en la bitácora como su
huella con llave, la misma que usa el inicio de sesión, y nunca en claro cuando no corresponde a
ninguna cuenta. Al nivel HTTP, la ruta de solicitud DEBE responder la respuesta aceptada idéntica en los
tres casos y su tiempo DEBE medirse con la puerta del requisito «El tiempo de la respuesta de solicitud no
distingue la cuenta y una puerta lo mide sobre HTTP».
(Previously: la respuesta `202 Accepted` con su mensaje y la medición del tiempo sobre respuestas HTTP eran
responsabilidad pendiente de `password-recovery-endpoints`.)

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

#### Escenario: [I354] Las respuestas HTTP de los tres casos son idénticas

- **DADO** los tres identificadores de los escenarios anteriores
- **CUANDO** se envían a la ruta de solicitud
- **ENTONCES** las tres respuestas son la respuesta aceptada e idénticas, como fija [I313]

### Requisito: Ausencia del restablecimiento administrativo (brecha con destino: cambio 8b)

El sistema NO DEBE ofrecer ningún caso de uso ni ninguna ruta con los que un actor distinto del titular dispare el
restablecimiento de otra cuenta ni invalide su contraseña (`docs/03-seguridad.md` §4.7, «Restablecimiento
administrativo»), porque ese restablecimiento llega con la parte **8b** de `rbac-permission-matrix-and-audit-integration`
(decisión P6). Las dos rutas de este cambio son del titular: la solicitud no cambia ninguna contraseña y la
confirmación exige el token del titular. Ningún caso de uso DEBE permitir fijar una contraseña sin un token de
recuperación válido.
(Previously: la brecha tenía como destino el cambio 8 (o 8b) y `password-recovery-endpoints`.)

#### Escenario: El único camino para cambiar una contraseña es el token

- **DADO** el conjunto de casos de uso públicos del módulo de identidad
- **CUANDO** se inspecciona cuáles escriben el hash de contraseña de una cuenta
- **ENTONCES** el único es el restablecimiento con token

#### Escenario: Un restablecimiento sin token válido nunca cambia la contraseña

- **DADO** la cuenta `ana.martinez@colegio.edu.hn`, con la contraseña vigente `Cafetal de Copán 2026`
- **CUANDO** se intenta restablecerla con un token que nunca se emitió
- **ENTONCES** el sistema lo rechaza y `Cafetal de Copán 2026` sigue autenticando

#### Escenario: [I355] Ninguna ruta de producción restablece la contraseña de otra cuenta

- **DADO** las rutas de producción del proceso administrativo al cerrar este cambio
- **CUANDO** se buscan rutas que reciban el identificador de otra cuenta para cambiar o invalidar su contraseña
- **ENTONCES** no hay ninguna; las dos rutas de recuperación reciben solo un identificador presentado o un token

## RENAMED Requirements

### Requisito: Ausencia del restablecimiento administrativo (brecha con destino: cambio 8 y `password-recovery-endpoints`) → Ausencia del restablecimiento administrativo (brecha con destino: cambio 8b)

(Reason: con la decisión P6 el restablecimiento administrativo forzado pertenece solo a la parte 8b de rbac; este
cambio deja de ser destino de la brecha. Su contenido modificado está en la sección MODIFIED de este delta, bajo el nombre nuevo.)
(Migration: las referencias al nombre anterior se leen con el nombre nuevo. **Reconciliación al archivar:** el delta
de `rbac-permission-matrix-and-audit-integration` renombra el mismo requisito a «… (brecha con destino: cambio 8b y
`password-recovery-endpoints`)». Si rbac se archiva antes, el nombre de origen de este renombre es ese; si este cambio
se archiva antes, el renombre de rbac debe apuntar al nombre nuevo de aquí y su texto debe dejar de nombrar a
`password-recovery-endpoints`.)

## REMOVED Requirements

### Requisito: Ausencia del límite por dirección IP y de la respuesta HTTP de la recuperación (brecha con destino: `password-recovery-endpoints` y cambio 11)

(Reason: este cambio entrega la ruta de solicitud con el límite de 10 por hora por IP y la respuesta aceptada
uniforme, y cierra H3 con la decisión P1, que acepta el limitador en memoria como tope equivalente hasta el cambio
11. Los escenarios «Once solicitudes seguidas no encuentran ningún límite por IP» y «El resultado uniforme es del
caso de uso, no una respuesta HTTP» dejan de ser ciertos en su parte HTTP.)
(Migration: sustituido por «La solicitud de recuperación se ofrece en `POST /api/v1/auth/password-reset-requests` y
siempre responde `202` uniforme», «El límite de 10 solicitudes por hora por IP se aplica antes del caso de uso y
cierra la condición H3» y la versión modificada de «Prohibición de enumeración de usuarios». El estado compartido
del limitador sigue cubierto por «Ausencia de estado compartido del limitador entre procesos (brecha con destino:
cambio 11)» de `web-edge`.)

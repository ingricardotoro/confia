# Delta para Borde web del proceso administrativo

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `session-tokens-and-web-layer` (F0, cambio 7, parte 4b, primer cambio S1 de tres)
- **Cortes que cubre:** C4b (filtro, principal, códigos de token, rutas autenticadas), C5b (adaptador de
  vigencia de sesión) y la corrección mínima de `ProblemExceptionHandler.unexpected` (O1)

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `WE01` en adelante. Los escenarios que se conservan de la
  especificación vigente no llevan número.
- Los identificadores `session-endpoints-cookie-and-csrf` (S2) y `password-recovery-endpoints` (S3) son
  los propuestos en la propuesta; si el propietario los cambia al aprobar, se reemplazan en todo este
  delta.
- Un escenario marcado «(solo documental)» no tiene aserción automática.
- El propósito de la capacidad («No define ningún endpoint de negocio ni de identidad») deja de ser
  exacto con la ruta de la sesión actual; se corrige en la nota de archivo, no en este delta.

## ADDED Requirements

### Requisito: El filtro de autenticación `Bearer` vive solo en la cadena administrativa y lee la credencial únicamente de `Authorization`

La cadena administrativa DEBE autenticar con un filtro que lee la cabecera `Authorization` con el
esquema `Bearer` (insensible a mayúsculas), verifica el token con el verificador de tokens y establece el
principal autenticado. El filtro NO DEBE leer ninguna credencial de un parámetro de consulta, de una
cookie, del cuerpo ni de otra cabecera. Una petición sin cabecera `Authorization`, o con un esquema
distinto de `Bearer`, DEBE tratarse como sin credencial. Una cabecera `Bearer` que no tenga exactamente un
espacio seguido de un único valor, con valor vacío o con espacios interiores, más de una cabecera
`Authorization`, o un token que el verificador rechaza, DEBE rechazarse con `401` y el código
`token-invalid` (o `token-expired` solo para un token íntegro vencido) sin que la petición llegue al
controlador. El filtro evalúa la cabecera `Authorization` también en las rutas de la lista blanca
pública: una credencial rota produce `401` aunque la ruta sea pública, y una ruta pública sin
credencial se sirve normalmente (la SPA no envía `Authorization` a las rutas públicas). La cadena del portal NO DEBE
tener este filtro y DEBE seguir denegando toda ruta aunque se presente un token administrativo válido;
el proceso trabajador sigue sin filtro alguno. Ningún controlador interpreta tokens. La cadena NO DEBE
crear sesión HTTP ni enviar cookies al autenticar o al rechazar, y el valor de `Authorization` NO DEBE
aparecer en ningún registro.

#### Escenario: [WE01] Un token válido autentica una ruta de la lista autenticada

- **DADO** un controlador de prueba en la lista cerrada de rutas autenticadas y un token de acceso
  vigente de una sesión viva
- **CUANDO** se envía la petición con `Authorization: Bearer <token>`
- **ENTONCES** la respuesta es `200` y el controlador recibe un principal cuyos datos corresponden a las
  claims del token

#### Escenario: [WE02] Sin cabecera o con otro esquema, es como si no hubiera credencial

- **DADO** una ruta de la lista autenticada
- **CUANDO** se envía una petición sin `Authorization` y otra con `Authorization: Basic dXNlcjpwYXNz`
- **ENTONCES** ambas responden `401` con el código `authentication-required`
- **Y** el controlador registra cero invocaciones

#### Escenario: [WE03] Cabecera `Bearer` vacía, con espacios interiores o repetida

- **DADO** una ruta de la lista autenticada
- **CUANDO** se envía `Authorization: Bearer ` (vacía), `Authorization: Bearer abc def`,
  `Authorization: Bearer  <token>` (dos espacios) y dos cabeceras `Authorization` con un token válido cada
  una
- **ENTONCES** las cuatro responden `401` con el código `token-invalid`
- **Y** el controlador registra cero invocaciones

#### Escenario: [WE04] Una credencial fuera de `Authorization` se ignora

- **DADO** un token de acceso válido y una ruta de la lista autenticada
- **CUANDO** se envía el token en `?access_token=`, en una cookie `sid`, en el cuerpo y en la cabecera
  `X-Access-Token`, sin cabecera `Authorization`
- **ENTONCES** las cuatro peticiones responden `401` con el código `authentication-required`
- **Y** el controlador registra cero invocaciones

#### Escenario: [WE05] Una ruta pública sirve sin credencial y rechaza una credencial rota

- **DADO** el proceso administrativo con el perfil `local`
- **CUANDO** se pide el documento OpenAPI sin cabecera `Authorization`, con `Authorization: Bearer basura`
  y con un token de acceso vigente
- **ENTONCES** la primera y la tercera responden `200`
- **Y** la segunda responde `401` con el código `token-invalid` y la cabecera
  `WWW-Authenticate: Bearer error="invalid_token"`

#### Escenario: [WE06] El portal no autentica y sigue denegando

- **DADO** el proceso del portal con el perfil por omisión y un token de acceso administrativo válido
- **CUANDO** se envía a una ruta cualquiera con `Authorization: Bearer <token>`
- **ENTONCES** la respuesta es `401` con el código `authentication-required`
- **Y** el contexto del portal no contiene el filtro de autenticación ni el verificador de tokens

#### Escenario: [WE07] Ninguna respuesta crea sesión ni cookie

- **DADO** una petición autenticada con éxito, una rechazada por token inválido y una rechazada por
  token vencido
- **CUANDO** se inspeccionan las tres respuestas
- **ENTONCES** ninguna contiene `Set-Cookie` y el contenedor no registra ninguna sesión HTTP creada

#### Escenario: [WE08] El token no aparece en ningún registro

- **DADO** peticiones con `Authorization: Bearer SECRETO-TOKEN` donde el token es, en cada una, válido,
  alterado y vencido, capturando todos los registros a nivel `TRACE`
- **CUANDO** el servidor las procesa
- **ENTONCES** el valor `SECRETO-TOKEN` y el token completo no aparecen en ningún registro

### Requisito: Solo un token de acceso completo del dominio administrativo autentica; el restringido y los de otros dominios se rechazan en toda ruta

La cadena administrativa DEBE rechazar con `401` y el código `token-invalid` todo token restringido de
MFA, en toda ruta, porque en este cambio ninguna ruta lo acepta (las rutas que lo aceptan son de
`session-endpoints-cookie-and-csrf`), y todo token firmado por otro dominio de identidad o con
audiencia distinta de la administrativa. El rechazo del token restringido DEBE producirse por su
audiencia (`confia-admin-mfa`, distinta de `confia-admin`), sin depender de que se consulte su claim de
uso. El rechazo NO DEBE depender de que la ruta exista ni de que esté en alguna de las listas, y NO DEBE
evaluar ningún permiso.

#### Escenario: [WE09] El token restringido no abre ninguna ruta

- **DADO** un token restringido de MFA vigente, con uso `mfa-verify`
- **CUANDO** se presenta a una ruta de la lista autenticada, a una ruta registrada fuera de las listas
  y a una ruta no registrada
- **ENTONCES** las tres respuestas son `401` con el código `token-invalid`
- **Y** ningún controlador registra invocaciones
- **Y** el rechazo se debe a la audiencia `confia-admin-mfa`, no al uso: un token con la audiencia
  administrativa y la claim de uso `mfa-verify` es rechazado igualmente por la lista cerrada de claims

#### Escenario: [WE10] El token del portal se rechaza

- **DADO** un token vigente firmado con la clave del portal y con `aud` igual a `confia-portal`
- **CUANDO** se presenta a una ruta de la lista autenticada
- **ENTONCES** la respuesta es `401` con el código `token-invalid` y no se evalúa ningún permiso

### Requisito: Toda respuesta `401` de las cadenas lleva `WWW-Authenticate`

Toda respuesta `401` DEBE incluir la cabecera `WWW-Authenticate` con el esquema `Bearer` (RFC 9110 y
RFC 6750), tanto si la produce la cadena administrativa, la cadena del portal, el traductor de errores o
la válvula de último recurso. Los valores DEBEN ser exactamente estos y ningún otro: para el código
`authentication-required`, `Bearer`; para `token-invalid` y para `token-expired`,
`Bearer error="invalid_token"`. La cabecera NO DEBE llevar `realm` ni `error_description`. Una respuesta
distinta de `401` NO DEBE incluirla.

#### Escenario: [WE11] Sin credencial

- **DADO** una petición sin credencial a una ruta denegada del proceso administrativo
- **CUANDO** se lee la respuesta `401`
- **ENTONCES** el valor de `WWW-Authenticate` es exactamente `Bearer`, sin atributo `error`, `realm` ni
  `error_description`

#### Escenario: [WE12] Credencial rechazada

- **DADO** una petición con un token alterado y otra con un token íntegro y vencido
- **CUANDO** se leen las dos respuestas `401`
- **ENTONCES** el valor de `WWW-Authenticate` de ambas es exactamente `Bearer error="invalid_token"`,
  sin `realm` ni `error_description`

#### Escenario: [WE13] El portal y la documentación con perfil `prod` también la llevan

- **DADO** una petición a una ruta del portal y otra al documento OpenAPI del proceso administrativo
  con el perfil `prod`, ambas sin credencial
- **CUANDO** se leen las dos respuestas `401`
- **ENTONCES** ambas contienen `WWW-Authenticate: Bearer`

#### Escenario: [WE41] Un `401` producido por el traductor de errores también la lleva

- **DADO** un controlador de prueba que lanza un error de dominio con el código `authentication-required`,
  otro con `token-invalid` y otro con `token-expired`
- **CUANDO** se leen las tres respuestas `401`
- **ENTONCES** los valores de `WWW-Authenticate` son `Bearer`, `Bearer error="invalid_token"` y
  `Bearer error="invalid_token"`, respectivamente

#### Escenario: [WE14] Las demás respuestas no la llevan

- **DADO** una respuesta `403` por principal sin permiso, una `429` y una `503`
- **CUANDO** se inspeccionan sus cabeceras
- **ENTONCES** ninguna contiene `WWW-Authenticate`

### Requisito: `token-invalid` y `token-expired` no revelan la causa del rechazo

El código `token-expired` DEBE producirse solo para un token íntegro (firma, estructura, emisor y
audiencia correctos) cuyo `exp` ya pasó. Toda otra causa de rechazo del token —firma, `kid`, algoritmo,
cabecera, estructura, emisor, audiencia, claims, tipo de token, sesión revocada o desconocida— DEBE
producir el código `token-invalid` con una respuesta idéntica en estado, `type`, `title`, `detail` y
cabeceras de seguridad, salvo `instance` y el identificador de traza, y con un `detail` del catálogo
es-HN que NO DEBE nombrar la causa. Un token vencido que además tiene firma o audiencia incorrectas DEBE
producir `token-invalid`.

#### Escenario: [WE15] Las causas de rechazo son indistinguibles

- **DADO** siete tokens rechazados por causas distintas: firma alterada, `kid` desconocido, `alg` `none`,
  audiencia del portal, claim no declarada, token restringido y sesión revocada con token vigente
- **CUANDO** se comparan las siete respuestas
- **ENTONCES** todas son `401` con `token-invalid` y son idénticas salvo `instance` y el identificador
  de traza

#### Escenario: [WE16] Solo un token íntegro vencido produce `token-expired`

- **DADO** un token íntegro y vencido, y otro vencido y con la firma alterada
- **CUANDO** se presentan a una ruta de la lista autenticada
- **ENTONCES** el primero recibe `401` con `token-expired` y el segundo `401` con `token-invalid`

#### Escenario: [WE17] El mensaje no nombra la causa

- **DADO** la respuesta `token-invalid` de cualquiera de los casos anteriores
- **CUANDO** se lee su `detail`
- **ENTONCES** es el texto del catálogo es-HN y no contiene las palabras «firma», «audiencia», «algoritmo»
  ni «revocada»

#### Escenario: [WE42] Una firma maleable o de longitud incorrecta es `401`, no `500`

- **DADO** un token emitido por el sistema cuya firma se sustituye por la firma maleable (`S + L`) y otro
  con una firma de 63 bytes
- **CUANDO** se presentan a una ruta de la lista autenticada
- **ENTONCES** ambas respuestas son `401` con el código `token-invalid` y la cabecera
  `WWW-Authenticate: Bearer error="invalid_token"`, idénticas a la de un token alterado salvo `instance`
  y el identificador de traza
- **Y** ninguna es `500` y el controlador registra cero invocaciones

### Requisito: Las rutas autenticadas sin permiso de rol forman una lista cerrada que una prueba hace cumplir

El proceso administrativo DEBE declarar las rutas autenticadas sin permiso de rol como un valor cerrado,
definido en un único lugar, paralelo a la lista blanca pública, y evaluado antes de la regla final de
denegación (decisión 2 del propietario; DA-7). Cada entrada DEBE identificar el método y la ruta, y
DEBE declarar si exige comprobar la vigencia de la sesión. La lista DEBE contener exactamente
`GET /api/v1/auth/sessions/current`, con comprobación de la vigencia de la sesión. Una petición con un
principal autenticado a un método y ruta fuera de ambas listas DEBE recibir `403` con `forbidden`. Una
prueba DEBE enumerar todas las rutas registradas y DEBE fallar si una ruta responde sin credencial y no
figura en la lista pública, si una ruta figura en la lista autenticada y responde sin credencial algo
distinto de `401`, o si una entrada de ambas listas no corresponde a una ruta registrada ni a una
condición de perfil declarada. El cambio 8 sustituye esta lista por la matriz de roles.

#### Escenario: [WE18] La lista contiene solo la ruta de la sesión actual

- **DADO** la lista cerrada de rutas autenticadas tal como la entrega este cambio
- **CUANDO** se enumeran sus entradas
- **ENTONCES** contiene exactamente `GET /api/v1/auth/sessions/current`, con comprobación de la
  vigencia de la sesión

#### Escenario: [WE19] Una ruta registrada fuera de las listas recibe `403` con un principal

- **DADO** un controlador de prueba registrado en una ruta que no figura en ninguna lista y un token de
  acceso vigente
- **CUANDO** se envía la petición con el token
- **ENTONCES** la respuesta es `403` con el código `forbidden` y el controlador registra cero
  invocaciones

#### Escenario: [WE20] Otro método sobre una ruta autenticada también es `403`

- **DADO** un token de acceso vigente y la ruta `/api/v1/auth/sessions/current`
- **CUANDO** se envía `POST`, `PUT`, `PATCH` y `DELETE` a esa ruta con el token
- **ENTONCES** las cuatro respuestas son `403` con el código `forbidden`

#### Escenario: [WE21] Las variantes de la ruta autenticada no eluden la autenticación

- **DADO** la ruta autenticada `/api/v1/auth/sessions/current`
- **CUANDO** se piden `/api/v1/auth/sessions/current/`, `/API/V1/AUTH/SESSIONS/CURRENT`,
  `/api/v1/auth/sessions/current;a=b`, `/api/v1/auth/sessions/./current` y
  `/api/v1/auth/other/../sessions/current` sin credencial
- **ENTONCES** ninguna respuesta es `2xx` y el controlador registra cero invocaciones

#### Escenario: [WE22] Una ruta autenticada nueva sin editar la lista rompe la prueba

- **DADO** un controlador de prueba cuya ruta se permite con un token pero no figura en la lista
  autenticada
- **CUANDO** se ejecuta la prueba que enumera las rutas registradas
- **ENTONCES** la prueba falla señalando la ruta

#### Escenario: [WE23] Una ruta de la lista que responde sin credencial falla la prueba

- **DADO** una ruta de la lista autenticada configurada, a propósito, para responder `200` sin credencial
- **CUANDO** se ejecuta la prueba que enumera las rutas registradas
- **ENTONCES** la prueba falla señalando la ruta

### Requisito: El principal autenticado vive en `shared.security` y no lleva datos personales ni permisos

El sistema DEBE representar al actor autenticado con un principal en `com.confia.shared.security` que
no dependa de `com.confia.identity`. El principal DEBE llevar solo el identificador opaco del usuario, el
de la institución, el de la sesión, los métodos de autenticación, el identificador del token y los
instantes de emisión y de vencimiento del token, tomados del token verificado. NO DEBE llevar datos
personales, ni el token, ni permisos. Una petición no
autenticada NO DEBE tener principal.

#### Escenario: [WE24] El principal refleja las claims del token

- **DADO** un token de acceso vigente con `sub`, `tenant`, `sid`, `amr`, `jti`, `iat` y `exp` conocidos
- **CUANDO** el controlador de prueba lee el principal
- **ENTONCES** sus campos son exactamente esos valores y ninguno otro

#### Escenario: [WE25] El principal no expone el token ni datos personales

- **DADO** el principal del escenario anterior
- **CUANDO** se invoca su representación textual
- **ENTONCES** no contiene el token ni el correo de la cuenta

#### Escenario: [WE26] Sin credencial no hay principal

- **DADO** un controlador de prueba de la lista pública que consulta el principal
- **CUANDO** se invoca sin credencial
- **ENTONCES** no hay ningún principal en el contexto

### Requisito: La vigencia de sesión es un puerto de `shared.security` con un único adaptador, en `identity`

El sistema DEBE mantener la vigencia de sesión como un puerto en `com.confia.shared.security`, de modo
que `shared` no dependa de `identity`, y DEBE tener exactamente una implementación de producción,
ubicada en `com.confia.identity`. El puerto DEBE recibir el actor autenticado completo (cuenta,
institución y sesión) y no solo el identificador de la sesión. Ninguna clase de producción distinta del
filtro de autenticación DEBE consumir el puerto. El filtro DEBE consultar el puerto solo para las rutas
cuya entrada en la lista autenticada declara la comprobación de la vigencia de la sesión; cuando el
puerto responde que la sesión no está activa, la petición DEBE rechazarse con `401` y el código
`token-invalid`.

#### Escenario: [WE27] El puerto tiene exactamente un adaptador, en `identity`

- **DADO** el código de producción de `apps/api`
- **CUANDO** se ejecuta la prueba de inventario de puertos
- **ENTONCES** el puerto de vigencia de sesión figura con exactamente una implementación de producción,
  y esa clase pertenece a `com.confia.identity`
- **Y** la única clase de producción que consume el puerto, fuera de esa implementación, es el filtro de
  autenticación

#### Escenario: El puerto no arrastra a `identity`

- **DADO** el puerto en `com.confia.shared.security`
- **CUANDO** se ejecutan las reglas de dependencia de `build-integrity`
- **ENTONCES** el puerto no depende de ninguna clase de `com.confia.identity`

#### Escenario: [WE28] Solo se consulta en las rutas que lo declaran

- **DADO** una ruta autenticada con la comprobación declarada y otra sin ella, y un doble del puerto que
  cuenta sus invocaciones y recuerda el actor recibido
- **CUANDO** se envía una petición con un token vigente a cada una
- **ENTONCES** el doble registra una invocación para la primera, con el actor cuyos datos son los del
  token, y ninguna para la segunda
- **Y** cuando el doble responde que la sesión no está activa, la primera ruta responde `401` con el
  código `token-invalid`

### Requisito: Ausencia del filtro central de mensajes de excepción (brecha con destino: cambio 10)

El sistema NO DEBE incluir, en este cambio, un filtro central que retire los mensajes de excepción del
registro estructurado. La corrección de este cambio alcanza solo a las excepciones de acceso a datos.
El filtro central es del **cambio 10** (observabilidad).

#### Escenario: Una excepción no prevista que no es de acceso a datos conserva su registro actual

- **DADO** un controlador de prueba que lanza `IllegalStateException` con un mensaje cualquiera
- **CUANDO** se inspecciona el registro del servidor
- **ENTONCES** la entrada de error contiene la excepción con su mensaje, como antes de este cambio
- **Y** este escenario deja de ser cierto el día que el cambio 10 entregue el filtro central

### Requisito: Ausencia de endpoints de inicio de sesión, MFA, refresco, cierre de sesión y recuperación, de cookie de sesión y de CSRF (brecha con destino: `session-endpoints-cookie-and-csrf` y `password-recovery-endpoints`)

El sistema NO DEBE incluir, en este cambio, ningún endpoint de inicio de sesión, MFA, refresco, cierre
de sesión o recuperación, ninguna cookie de sesión ni de refresco, ninguna protección CSRF de doble
envío, ningún adaptador de `CurrentInstitutionProvider` fuera del principal autenticado, ni ningún
código de Problem Details `authentication-failed`, `institution-not-found` o `institution-inactive`.
Tampoco DEBE aplicar el limitador ni el materializador del retardo a ningún endpoint de producción. La
única ruta de producción es `GET /api/v1/auth/sessions/current`. Las condiciones duras H1 y la condición
3 obligan a **`session-endpoints-cookie-and-csrf`**; H3 y H4, a **`password-recovery-endpoints`**; H2 se
cierra en este cambio. Este cambio NO DEBE declarar cerrada ninguna de las otras cuatro.

#### Escenario: [WE29] La única ruta de producción es la sesión actual

- **DADO** el proceso administrativo arrancado por su camino de producción
- **CUANDO** se enumeran las rutas registradas por controladores de producción
- **ENTONCES** el conjunto es exactamente `GET /api/v1/auth/sessions/current`
- **Y** toda petición a otra ruta de aplicación sin credencial recibe `401`

#### Escenario: [WE30] Ninguna cookie y CSRF sigue desactivado

- **DADO** el código de producción de `apps/api`
- **CUANDO** se busca el uso de `jakarta.servlet.http.Cookie`, de `Set-Cookie` y de la configuración
  de CSRF de la cadena
- **ENTONCES** no hay ningún uso de cookies y la protección CSRF sigue desactivada, porque ninguna
  credencial viaja en una cookie
- **Y** este escenario deja de ser cierto el día que `session-endpoints-cookie-and-csrf` entregue la
  cookie de refresco con su protección CSRF

#### Escenario: [WE31] El limitador y el materializador no están aplicados a un endpoint de producción

- **DADO** el código de producción de `apps/api`
- **CUANDO** se inspeccionan los usos del limitador y del materializador
- **ENTONCES** solo se usan desde el árbol de pruebas, y H1 queda abierta con destino a
  `session-endpoints-cookie-and-csrf`

#### Escenario: [WE32] H1, H3, H4 y la condición 3 siguen abiertas con su nuevo dueño (solo documental)

- **DADO** la nota fechada de transferencia de `openspec/changes/foundations-plan/exploration.md` y el
  informe de archivo de este cambio
- **CUANDO** se leen
- **ENTONCES** declaran H1 y la condición 3 abiertas con dueño `session-endpoints-cookie-and-csrf`, y H3
  y H4 abiertas con dueño `password-recovery-endpoints`
- **Y** declaran H2 cerrada en este cambio

## MODIFIED Requirements

### Requisito: La cadena administrativa deniega por defecto

El proceso administrativo DEBE tener una cadena de filtros de seguridad cuya última regla deniega
toda petición que no esté en la lista blanca pública ni en la lista cerrada de rutas autenticadas. Una
petición sin credencial a una ruta fuera de la lista blanca DEBE recibir `401` con el código
`authentication-required`, incluida una ruta de la lista de rutas autenticadas; una petición con un
principal autenticado a una ruta fuera de ambas listas DEBE recibir `403` con el código `forbidden`,
porque ninguna ruta concede permisos todavía (la matriz de roles es del cambio 8). Toda denegación DEBE
responder con Problem Details. La decisión NO DEBE depender de que la ruta exista: una ruta no
registrada y una ruta registrada fuera de las listas DEBEN recibir la misma respuesta, para no revelar
qué rutas existen.
(Previously: la última regla denegaba toda petición fuera de la lista blanca pública, no existía la
lista de rutas autenticadas y el principal autenticado solo podía inyectarlo una prueba.)

#### Escenario: Ruta no registrada sin credencial

- **DADO** el proceso administrativo y una ruta que ningún controlador registra
- **CUANDO** se envía una petición sin credencial a esa ruta
- **ENTONCES** la respuesta es `401` con `Content-Type: application/problem+json` y con `type`
  derivado del código `authentication-required`

#### Escenario: Ruta registrada fuera de la lista blanca

- **DADO** un controlador de prueba registrado en una ruta que no está en la lista blanca pública
- **CUANDO** se envía una petición sin credencial a esa ruta
- **ENTONCES** la respuesta es `401` y el controlador registra cero invocaciones

#### Escenario: La respuesta no distingue rutas existentes de inexistentes

- **DADO** una ruta registrada fuera de las listas y una ruta no registrada
- **CUANDO** se envía la misma petición sin credencial a ambas
- **ENTONCES** el estado, el `type`, el `title`, el `detail` y las cabeceras de seguridad son
  idénticos; solo `instance` puede diferir

#### Escenario: Principal autenticado sin permiso

- **DADO** un principal autenticado, ya sea inyectado por la prueba o por un token de acceso vigente, y
  una ruta fuera de ambas listas
- **CUANDO** el principal envía una petición a esa ruta
- **ENTONCES** la respuesta es `403` con `type` derivado del código `forbidden` y el controlador
  registra cero invocaciones

#### Escenario: Método distinto al permitido en una ruta pública

- **DADO** una ruta de la lista blanca pública permitida solo para `GET`
- **CUANDO** se envía `POST`, `PUT`, `DELETE` y `PATCH` a esa ruta sin credencial
- **ENTONCES** las cuatro peticiones reciben `401`

#### Escenario: Variantes de la ruta no eluden la denegación

- **DADO** una ruta protegida `/x`
- **CUANDO** se piden `/x/`, `/X`, `/x;a=b`, `/x/.` y `/y/../x` sin credencial
- **ENTONCES** ninguna respuesta es `2xx` y el controlador de `/x` registra cero invocaciones

#### Escenario: [WE33] Una ruta de la lista autenticada sin credencial

- **DADO** la ruta `GET /api/v1/auth/sessions/current`
- **CUANDO** se envía sin credencial
- **ENTONCES** la respuesta es `401` con el código `authentication-required`, no `403`, y el
  controlador registra cero invocaciones

### Requisito: El `type` del error se deriva del código estable del error

El `type` de un Problem Details DEBE derivarse de forma determinista del código estable del error
(`DomainException`, ADR-0019): el mismo código produce siempre el mismo `type` y dos códigos
distintos producen `type` distintos. El `type` NO DEBE derivarse del nombre de clase, del mensaje ni
de la traza. Toda denegación de la cadena por falta de credencial DEBE usar un único código estable,
`authentication-required`, y por tanto un único `type`, sin distinguir la causa. Una credencial
presente que se rechaza DEBE usar `token-invalid` o `token-expired`, y nunca `authentication-required`.
El código del inicio de sesión (`authentication-failed`) es de `session-endpoints-cookie-and-csrf`.
(Previously: los códigos de autenticación propiamente dichos eran todos de `session-tokens-and-web-layer`
y la cadena solo conocía `authentication-required`.)

#### Escenario: El mismo código produce el mismo `type`

- **DADO** dos excepciones de dominio con el mismo código estable y mensajes internos distintos
- **CUANDO** el traductor las convierte en respuesta
- **ENTONCES** ambas respuestas tienen el mismo `type`

#### Escenario: Códigos distintos producen `type` distintos

- **DADO** dos excepciones de dominio con códigos estables distintos
- **CUANDO** el traductor las convierte en respuesta
- **ENTONCES** los `type` difieren

#### Escenario: La denegación por falta de credencial es uniforme

- **DADO** peticiones sin credencial a tres rutas distintas, una con cabecera `Authorization` de un
  esquema distinto de `Bearer` y otra sin cabecera
- **CUANDO** se comparan las respuestas
- **ENTONCES** todas tienen el mismo estado, `type`, `title` y `detail`, y el `type` deriva del
  código `authentication-required`

#### Escenario: [WE34] Una credencial rechazada no usa `authentication-required`

- **DADO** una petición con un token alterado a una ruta de la lista autenticada
- **CUANDO** se lee el `type` de la respuesta
- **ENTONCES** deriva de `token-invalid` y es distinto del `type` de `authentication-required`

### Requisito: Catálogo de códigos de error y estados HTTP con productor en este cambio

El traductor DEBE producir, para cada código del subconjunto con productor, el estado HTTP siguiente:
`validation-failed` con `400`; `idempotency-key-missing` con `400`; `authentication-required` con
`401`; `token-invalid` con `401`; `token-expired` con `401`; `forbidden` con `403`;
`resource-not-found` con `404`; `idempotency-conflict` con `409`; `unsupported-media-type` con `415`;
`idempotency-payload-mismatch` con `422`; `too-many-requests` con `429` y `Retry-After`;
`capacity-exceeded` con `503`; `internal-error` con `500`. `resource-not-found` solo tiene productor
bajo los prefijos públicos de springdoc, con los perfiles `local` y `preprod`. `token-invalid` y
`token-expired` los produce el filtro de autenticación. El código de autenticación propio del inicio de
sesión (`authentication-failed`) y los de institución (`institution-not-found`,
`institution-inactive`) NO DEBEN existir en este cambio (brecha con destino:
`session-endpoints-cookie-and-csrf` y la primera ruta que resuelva la institución contra `organization`).
(Previously: once códigos; `token-invalid` y `token-expired` no debían existir.)

#### Escenario: Cada código tiene su estado

- **DADO** un productor de prueba para cada uno de los trece códigos
- **CUANDO** cada uno lanza su error en el borde
- **ENTONCES** el estado HTTP es el indicado en el requisito y `type` corresponde a ese código

#### Escenario: Tipo de contenido no admitido

- **DADO** un endpoint de prueba que consume `application/json`
- **CUANDO** se envía una petición con `Content-Type: text/plain`
- **ENTONCES** la respuesta es `415` con `application/problem+json` y el código
  `unsupported-media-type`, sin el nombre del tipo de contenido interno ni el mensaje de la excepción

#### Escenario: Recurso inexistente bajo un prefijo de documentación

- **DADO** el proceso administrativo con el perfil `local` y una ruta inexistente bajo
  `/swagger-ui/`
- **CUANDO** se pide sin credencial
- **ENTONCES** la respuesta es `404` con `application/problem+json` y el código
  `resource-not-found`, y no `500`

#### Escenario: Un código desconocido no se filtra

- **DADO** una excepción de dominio con un código que el catálogo no conoce
- **CUANDO** el traductor la convierte en respuesta
- **ENTONCES** la respuesta es `500` con el código `internal-error`, y el código desconocido no
  aparece en el cuerpo

#### Escenario: [WE35] Los códigos de token existen y los de inicio de sesión y de institución no

- **DADO** el catálogo de códigos de error entregado y su catálogo de mensajes es-HN
- **CUANDO** se enumeran sus entradas y se ejecuta la prueba de cobertura del catálogo
- **ENTONCES** contiene `authentication-required`, `token-invalid` y `token-expired`, cada uno con su
  entrada es-HN
- **Y** no contiene `authentication-failed`, `institution-not-found` ni `institution-inactive`

### Requisito: Las respuestas de error no exponen detalles internos

Ninguna respuesta de error DEBE contener traza de pila, nombre de clase, nombre de paquete, texto de
SQL, mensaje de una excepción interna ni valores de configuración. Una excepción no prevista DEBE
traducirse a `500` con el código `internal-error` y un `detail` del catálogo, y el detalle técnico
DEBE quedar solo en el registro del servidor con el identificador de petición. El registro NO DEBE
contener los datos prohibidos por `CLAUDE.md`, regla 11. Para una excepción de acceso a datos
(`java.sql.SQLException`, la `DataAccessException` de Spring o cualquier excepción cuya cadena de causas
contenga una de ellas), el registro DEBE ser una sola línea que contenga únicamente la clase de la
excepción, la clase de su causa, el `SQLState` y el identificador de
petición, y NO DEBE contener su mensaje, el texto `Detail:` de PostgreSQL, los valores de las columnas,
el texto de la consulta ni la traza de pila (decisión 3 del propietario; condición del primer endpoint
que consulta la base).
(Previously: el registro de toda excepción no prevista conservaba la excepción completa, incluida la
línea `Detail: Key (...)=(...)` de una violación de unicidad.)

#### Escenario: Excepción no prevista

- **DADO** un controlador de prueba que lanza `IllegalStateException` con el mensaje
  `jdbc:postgresql://host/db password=x`
- **CUANDO** se invoca
- **ENTONCES** la respuesta es `500` con `internal-error`, y ni el mensaje, ni el nombre de la clase,
  ni la traza aparecen en el cuerpo ni en las cabeceras

#### Escenario: Excepción de dominio con mensaje interno

- **DADO** una `DomainException` cuyo mensaje interno contiene un identificador de documento
- **CUANDO** se traduce
- **ENTONCES** el `detail` proviene del catálogo es-HN y no contiene ese mensaje interno

#### Escenario: El detalle técnico queda solo en el servidor

- **DADO** la excepción no prevista del primer escenario
- **CUANDO** se inspecciona el registro del servidor
- **ENTONCES** existe una entrada de error con el mismo identificador de petición que el cuerpo de
  la respuesta

#### Escenario: [WE36] Una violación de unicidad no filtra el valor

- **DADO** un controlador de prueba que lanza una excepción de acceso a datos cuya causa es una
  `PSQLException` real del controlador de PostgreSQL, con `SQLState` `23505` y un detalle
  `Key (email)=(valor.unico@colegio.edu.hn) already exists`
- **CUANDO** se invoca y se captura el registro del servidor
- **ENTONCES** la respuesta es `500` con `internal-error` y el cuerpo no contiene el valor ni el texto
  `Detail:`
- **Y** la entrada de error del registro contiene la clase de la excepción, la clase de su causa, el
  `SQLState` `23505` y el identificador de petición (el mismo que el `traceId` del cuerpo), y no contiene
  el valor, el texto `Detail:`, la consulta, ninguna traza ni la excepción adjunta

#### Escenario: [WE37] El control negativo detecta una fuga de `Detail:`

- **DADO** el mecanismo de captura del escenario anterior y una entrada de registro que, a propósito,
  contiene el texto `Detail: Key (email)=(valor.unico@colegio.edu.hn)`
- **CUANDO** se revisa la captura
- **ENTONCES** la verificación detecta el valor y reporta la violación, de modo que una captura vacía no
  pase por válida

### Requisito: Un fallo de validación produce `validation-failed` sin repetir los valores rechazados

Un fallo de validación de Jakarta Bean Validation, un cuerpo mal formado y un tipo de contenido no
admitido DEBEN responder `400` (el último, `415` con el código `unsupported-media-type`) con Problem
Details; el fallo de validación DEBE usar el código `validation-failed` y listar en el arreglo
`errors` el campo y el motivo de cada violación. `errors` es un miembro de extensión de RFC 9457 que
el esquema `ProblemDetail` del contrato OpenAPI declara desde este cambio, el primero con una
operación de producción. La respuesta NO DEBE repetir el valor rechazado, porque puede ser
una contraseña, un documento de identidad o un dato de un menor.
(Previously: el esquema `ProblemDetail` no declaraba `errors` porque la instantánea del documento
estaba congelada; lo declaraba el primer endpoint de producción con validación.)

#### Escenario: Campo inválido

- **DADO** un controlador de prueba con un campo obligatorio y una longitud máxima
- **CUANDO** se envía el campo vacío y, en otra petición, con longitud máxima más uno
- **ENTONCES** ambas responden `400` con `validation-failed` que nombra el campo, y la petición con
  el valor límite exacto de la longitud máxima es aceptada

#### Escenario: El valor rechazado no se repite

- **DADO** una petición cuyo campo inválido contiene el texto `VALOR-SENSIBLE-123`
- **CUANDO** se lee la respuesta completa
- **ENTONCES** el texto no aparece en el cuerpo ni en las cabeceras

#### Escenario: Cuerpo mal formado

- **DADO** un cuerpo JSON truncado
- **CUANDO** se envía a un endpoint de prueba
- **ENTONCES** la respuesta es `400` con Problem Details y sin el mensaje del analizador

#### Escenario: [WE38] `errors` está declarado en el esquema del contrato

- **DADO** un fallo de validación de un campo y la instantánea aprobada del documento OpenAPI
- **CUANDO** se lee la respuesta y se ejecuta `OpenApiContractSnapshotTest`
- **ENTONCES** el cuerpo contiene `errors` con `field` y `reason`
- **Y** el esquema `ProblemDetail` de la instantánea declara `errors` como arreglo de objetos con
  `field` y `reason`

### Requisito: El origen de la petición llega a la bitácora de auditoría

Un asiento de auditoría escrito durante una petición servida por la cadena DEBE llevar `sourceIp`
con la IP del cliente resuelta y `userAgent` con el agente de usuario capturado. Un asiento escrito
fuera de una petición (trabajo en segundo plano, prueba sin cadena) DEBE conservar ambos campos
nulos. El origen de una petición NO DEBE filtrarse a otra, incluso con peticiones concurrentes. La
propagación del origen a los comandos de identidad es de `session-endpoints-cookie-and-csrf` (brecha con
destino: `session-endpoints-cookie-and-csrf`).
(Previously: el destino de la propagación era `session-tokens-and-web-layer`.)

#### Escenario: Asiento durante una petición

- **DADO** un controlador de prueba que escribe un asiento con `AuditLogWriter`, y una petición
  desde `203.0.113.9` con `User-Agent: agente-prueba`
- **CUANDO** el controlador la atiende
- **ENTONCES** el asiento persistido tiene `sourceIp = 203.0.113.9` y `userAgent = agente-prueba`

#### Escenario: Asiento fuera de una petición

- **DADO** un asiento escrito por una prueba sin petición HTTP activa
- **CUANDO** se lee el asiento persistido
- **ENTONCES** `sourceIp` y `userAgent` son nulos

#### Escenario: Peticiones concurrentes con orígenes distintos

- **DADO** 50 peticiones concurrentes, sincronizadas con `CyclicBarrier`, cada una desde una IP y
  con un agente de usuario distintos y cada una escribiendo un asiento
- **CUANDO** terminan todas
- **ENTONCES** cada asiento lleva la IP y el agente de su propia petición, sin cruces

### Requisito: El controlador de demostración de la idempotencia vive solo en el árbol de pruebas

La idempotencia HTTP DEBE demostrarse con un controlador que exista únicamente en el árbol de pruebas.
Ningún controlador de producción NI ninguna ruta de producción DEBE usar el mecanismo en este cambio,
y el documento OpenAPI de cada superficie NO DEBE contener la ruta de demostración.
(Previously: el documento administrativo no contenía ninguna operación; ahora contiene la de la sesión
actual y sigue sin contener la ruta de demostración.)

#### Escenario: El controlador de demostración no está en los procesos reales

- **DADO** los contextos administrativo y del portal arrancados por su camino de producción
- **CUANDO** se inspeccionan sus rutas registradas
- **ENTONCES** ninguna es la ruta de demostración

#### Escenario: [WE39] El documento OpenAPI no contiene la ruta de demostración

- **DADO** la instantánea aprobada del documento administrativo, con la operación de la sesión actual
- **CUANDO** se ejecuta `OpenApiContractSnapshotTest`
- **ENTONCES** el documento generado coincide con la instantánea, contiene la operación
  `GET /api/v1/auth/sessions/current` y no contiene la ruta de demostración
- **Y** el documento del portal sigue sin ninguna operación

### Requisito: Ausencia de matriz de roles, cobertura de MFA por ruta, claim de permisos y guarda de arranque de RBAC (brecha con destino: cambio 8)

El sistema NO DEBE incluir, en este cambio, ninguna matriz de roles, ninguna cobertura de MFA por
ruta, ningún claim de permisos ni ninguna guarda de arranque de RBAC. La denegación por defecto con
lista blanca explícita y la lista cerrada de rutas autenticadas sin permiso son lo único que se entrega
de RBAC (decisiones D4 y 2). El token no lleva la claim `permissions` y el principal no expone permisos,
hasta que el cambio 8 (`rbac-permission-matrix-and-audit-integration`) los entregue.
(Previously: la cadena contenía solo la lista blanca pública y la regla final de denegación.)

#### Escenario: Ninguna regla de permiso sobre una ruta

- **DADO** la cadena administrativa tal como la entrega este cambio
- **CUANDO** se inspecciona su configuración de autorización
- **ENTONCES** contiene solo la lista blanca pública, la lista cerrada de rutas autenticadas y la
  regla final de denegación, sin ninguna regla por rol, permiso o cobertura de MFA

#### Escenario: [WE40] Un token con la claim `permissions` se rechaza

- **DADO** un token firmado con la clave vigente, con las nueve claims correctas y una claim
  `permissions` con un arreglo vacío
- **CUANDO** se presenta a una ruta de la lista autenticada
- **ENTONCES** la respuesta es `401` con el código `token-invalid`

## REMOVED Requirements

### Requisito: La vigencia de sesión es un puerto de `shared.security` sin implementación todavía

(Reason: la brecha se cierra. `session-tokens-and-web-layer` entrega la implementación del puerto en
`identity` (corte C5b) y el escenario «El puerto existe y no tiene adaptador de producción» deja de ser
cierto, como el requisito mismo anunciaba.)
(Migration: sustituido por «La vigencia de sesión es un puerto de `shared.security` con un único
adaptador, en `identity`», en este delta. El escenario «El puerto no arrastra a `identity`» se conserva
sin cambios en el requisito nuevo.)

### Requisito: Ausencia de autenticación por credencial, tokens y endpoints de identidad (brecha con destino: `session-tokens-and-web-layer`)

(Reason: `session-tokens-and-web-layer` entrega el filtro de autenticación, el emisor y el verificador de
tokens, el anillo de claves, el token restringido de MFA, el adaptador de `CurrentInstitutionProvider`
sobre el principal y la primera ruta de producción; los escenarios «Ninguna ruta de producción en el
proceso administrativo» y «Ningún componente de tokens ni de sesión» dejan de ser ciertos, como el
requisito anunciaba. La ausencia que sigue vigente se estrecha y cambia de dueño por la partición de la
parte 4b.)
(Migration: sustituido por «Ausencia de endpoints de inicio de sesión, MFA, refresco, cierre de sesión y
recuperación, de cookie de sesión y de CSRF (brecha con destino: `session-endpoints-cookie-and-csrf` y
`password-recovery-endpoints`)», en este delta. Los requisitos positivos del filtro, del principal y de
los códigos de token viven en este mismo capítulo.)

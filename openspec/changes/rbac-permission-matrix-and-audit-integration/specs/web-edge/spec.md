# Delta para Borde web del proceso administrativo

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `rbac-permission-matrix-and-audit-integration` (F0, cambio 8)
- **Propuesta:** `proposal.md`, aprobada el 2026-11-03, con O1 a O10 y Q1 a Q5 vinculantes

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `WE94` en adelante, a continuación del máximo de la especificación vigente
  (`WE93`). Los escenarios que se conservan de la especificación vigente conservan su identificador; los que no
  llevaban número no lo llevan.
- El comportamiento de la decisión de permiso (catálogo, resolución, `otp`, guarda, auditoría de denegaciones) está en
  la capacidad `authorization`; este delta describe la posición de la decisión en la cadena y el tratamiento de las
  respuestas `403` y de los fallos.
- Valor fijado por el diseño (`design.md` §3, D7): el fallo de infraestructura de la resolución responde `500` con
  `internal-error`, sin código público nuevo, por el último recurso del filtro de contexto de la petición.

## ADDED Requirements

### Requisito: La decisión de permiso se toma en la cadena administrativa, después de la verificación del token y antes de la idempotencia y del limitador

El proceso administrativo DEBE tomar la decisión de permiso de las rutas de la lista cerrada de rutas con permiso en un
decisor de autorización de la cadena de filtros, después de la verificación del token y antes de los interceptores de
idempotencia y de límite de tasa, y fuera de toda transacción de negocio. NO DEBE decidirse con anotaciones de método ni
con seguridad de métodos, porque corren después de esos interceptores y dentro de la transacción. Un actor sin permiso
NO DEBE aprender nada de la idempotencia ni del limitador: la respuesta a una petición denegada NO DEBE depender de
`Idempotency-Key` ni consumir cupo del limitador.

#### Escenario: [WE94] El decisor corre después del token y antes de la idempotencia y del limitador

- **DADO** la cadena administrativa tal como la entrega este cambio
- **CUANDO** se inspecciona el orden de sus filtros e interceptores
- **ENTONCES** el decisor de permisos está después del filtro de autenticación `Bearer` y antes de los interceptores de
  idempotencia y de límite de tasa
- **Y** no existe ninguna anotación de permiso en clases ni en métodos de producción

#### Escenario: [WE95] Una petición denegada no llega a la idempotencia ni al limitador

- **DADO** una ruta con permiso de prueba que mueve dinero (exige `Idempotency-Key`) y un limitador de prueba que
  cuenta invocaciones
- **CUANDO** un actor sin el permiso envía la petición
- **ENTONCES** la respuesta es `403 forbidden`, y el interceptor de idempotencia, el limitador y el controlador
  registran cero invocaciones
- **Y** no se crea ningún marcador en `shared_idempotency_key`

#### Escenario: [WE96] El rechazo no depende de la cabecera de idempotencia

- **DADO** la ruta del escenario anterior y un actor sin el permiso
- **CUANDO** envía la petición una vez sin `Idempotency-Key` y otra con una clave válida
- **ENTONCES** ambas respuestas son idénticas (`403 forbidden`, mismo `type`, `title` y `detail`), y ninguna es
  `400 idempotency-key-missing`

#### Escenario: [WE97] Con el permiso la petición sigue su camino normal

- **DADO** la misma ruta y un actor con el permiso y sesión `[pwd, otp]`
- **CUANDO** envía la petición sin `Idempotency-Key`
- **ENTONCES** la respuesta es `400` con `idempotency-key-missing`, producida después de la decisión de permiso

### Requisito: El manejador de `403` distingue la denegación por permiso de las demás respuestas `403`

El manejador de acceso denegado DEBE responder con el Problem Details `403` con el código `forbidden` a toda denegación
que le llega, que son la denegación por permiso, la ruta sin mapear rechazada por la denegación final y el rechazo por
falta de `otp`, y DEBE auditar únicamente la denegación por permiso, que identifica porque la excepción lleva el código
del permiso. El rechazo de CSRF o de `Origin` no pasa por ese manejador: lo responde el filtro de CSRF y conserva su
`403` con el código `csrf-invalid`. Ninguna respuesta `403` distinta de la denegación por permiso (ruta sin mapear,
rechazo de CSRF o de `Origin`, rechazo por falta de `otp`) DEBE producir el asiento de denegación. El cuerpo de la respuesta de una denegación por permiso, tanto si el asiento
se escribió como si no, DEBE ser idéntico en estado, `type`, `title` y `detail` al de una ruta sin mapear; solo pueden
diferir `instance` y `traceId`. Esa igualdad no revela qué rutas existen ni qué permisos exige cada una.

#### Escenario: [WE98] Una denegación por permiso se audita y las demás no

- **DADO** una ruta con permiso y una ruta fuera de todas las listas, y un principal autenticado con sesión completa
  sin el permiso
- **CUANDO** el principal envía una petición a cada una
- **ENTONCES** la primera deja exactamente un asiento `authorization.access.denied` y la segunda ninguno

#### Escenario: [WE99] La respuesta no distingue la ruta con permiso de la ruta sin mapear

- **DADO** las dos peticiones del escenario anterior
- **CUANDO** se comparan las respuestas
- **ENTONCES** el estado, el `type`, el `title`, el `detail` y las cabeceras de seguridad son idénticos; solo
  `instance` y `traceId` pueden diferir

#### Escenario: [WE100] La respuesta de una denegación es la misma con la auditoría rota

- **DADO** una denegación por permiso con el escritor de bitácora funcionando y otra con el escritor lanzando una
  excepción
- **CUANDO** se comparan las respuestas
- **ENTONCES** son idénticas salvo `instance` y `traceId`

### Requisito: Un fallo de infraestructura de la resolución de permisos responde con el tratamiento existente de fallos y no como `forbidden`

Cuando la resolución de permisos falla por infraestructura, la cadena administrativa DEBE denegar el acceso sin
invocar el controlador y DEBE responder con un Problem Details del catálogo existente para un fallo interno o de
dependencia: `internal-error` con `500`, sin código público nuevo (decisión del diseño D7), sin detalles internos, sin
repetir mensajes de excepción y con `traceId`. La respuesta NO DEBE tener el código `forbidden` ni el estado `403`. El
fallo NO DEBE invocar el manejador de acceso denegado que audita denegaciones. Como el filtro se ejecuta antes del
manejador de excepciones de los controladores, la respuesta la construye el último recurso del filtro de contexto de la
petición.

#### Escenario: [WE101] El fallo de resolución no responde `403 forbidden`

- **DADO** una ruta con permiso y un resolutor de prueba que lanza un error de infraestructura
- **CUANDO** un actor con sesión completa envía la petición
- **ENTONCES** la respuesta es un Problem Details con `traceId`, cuyo estado es `500` y cuyo código es `internal-error`, y ninguno es `403` ni `forbidden`
- **Y** el controlador registra cero invocaciones y el manejador de acceso denegado registra cero invocaciones

#### Escenario: [WE102] El fallo no expone detalles internos

- **DADO** el resolutor de prueba con un mensaje de excepción que contiene `SENTINEL-RESOLVER-6` y el nombre de una
  clase interna
- **CUANDO** se envía la petición
- **ENTONCES** ni el cuerpo ni las cabeceras contienen el mensaje ni el nombre de la clase

## MODIFIED Requirements

### Requisito: La cadena administrativa deniega por defecto

El proceso administrativo DEBE tener una cadena de filtros de seguridad cuya última regla deniega
toda petición que no esté en la lista blanca pública, en la lista cerrada de rutas autenticadas ni en la lista cerrada
de rutas con permiso. Una
petición sin credencial a una ruta fuera de la lista blanca DEBE recibir `401` con el código
`authentication-required`, incluida una ruta de la lista de rutas autenticadas y una ruta con permiso; una petición con un
principal autenticado a una ruta fuera de todas las listas DEBE recibir `403` con el código `forbidden`.
La decisión sobre una ruta con permiso se rige por la capacidad `authorization`. Toda denegación DEBE
responder con Problem Details. La decisión NO DEBE depender de que la ruta exista: una ruta no
registrada y una ruta registrada fuera de las listas DEBEN recibir la misma respuesta, para no revelar
qué rutas existen.
(Previously: el principal autenticado a una ruta fuera de ambas listas recibía `403` «porque ninguna ruta concede
permisos todavía (la matriz de roles es del cambio 8)»; no existía la lista de rutas con permiso.)

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
  una ruta fuera de todas las listas
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

#### Escenario: [WE103] Una ruta con permiso sin credencial

- **DADO** la ruta `GET /api/v1/identity/roles`
- **CUANDO** se envía sin credencial
- **ENTONCES** la respuesta es `401` con `authentication-required`, no `403`, y el controlador registra cero
  invocaciones

### Requisito: Las rutas autenticadas sin permiso de rol forman una lista cerrada que una prueba hace cumplir

El proceso administrativo DEBE declarar las rutas autenticadas sin permiso de rol como un valor cerrado,
definido en un único lugar, paralelo a la lista blanca pública, y evaluado antes de la regla final de
denegación (decisión 2 del propietario; DA-7). Cada entrada DEBE identificar el método y la ruta, y
DEBE declarar si exige comprobar la vigencia de la sesión. La lista DEBE contener exactamente
`GET /api/v1/auth/sessions/current` y `DELETE /api/v1/auth/sessions`, ambas con comprobación de la vigencia de
la sesión. Estas rutas son de autoservicio sobre la propia cuenta y no son permisos del catálogo. Una petición con un
principal autenticado a un método y ruta fuera de las cuatro clases de rutas (pública, autenticada, restringida y con
permiso) DEBE recibir `403` con `forbidden`. Una
prueba DEBE enumerar todas las rutas registradas y DEBE fallar si una ruta responde sin credencial y no
figura en la lista pública, si una ruta figura en la lista autenticada y responde sin credencial algo
distinto de `401`, o si una entrada de ambas listas no corresponde a una ruta registrada ni a una
condición de perfil declarada. La lista de rutas con permiso es una lista aparte, cerrada y definida en la capacidad
`authorization`; esta lista se conserva.
(Previously: la última frase decía «El cambio 8 sustituye esta lista por la matriz de roles», y la regla del `403`
hablaba de las tres clases de rutas.)

#### Escenario: [WE18] La lista contiene solo las dos rutas de sesión

- **DADO** la lista cerrada de rutas autenticadas tal como la entrega este cambio
- **CUANDO** se enumeran sus entradas
- **ENTONCES** contiene exactamente `GET /api/v1/auth/sessions/current` y `DELETE /api/v1/auth/sessions`, ambas
  con comprobación de la vigencia de la sesión

#### Escenario: [WE19] Una ruta registrada fuera de las listas recibe `403` con un principal

- **DADO** un controlador de prueba registrado en una ruta que no figura en ninguna lista y un token de
  acceso vigente
- **CUANDO** se envía la petición con el token
- **ENTONCES** la respuesta es `403` con el código `forbidden` y el controlador registra cero
  invocaciones

#### Escenario: [WE20] Otro método sobre una ruta autenticada también es `403`

- **DADO** un token de acceso vigente y la ruta `/api/v1/auth/sessions/current`
- **CUANDO** se envía `POST`, `PUT` y `PATCH` a esa ruta con el token
- **ENTONCES** las tres respuestas son `403` con el código `forbidden`
- **Y** `DELETE` a esa ruta no es una ruta autenticada sino la de cierre por cookie, y sin `Origin` permitido
  responde `403` con `csrf-invalid`

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

#### Escenario: [WE33] Una ruta de la lista autenticada sin credencial

- **DADO** las rutas `GET /api/v1/auth/sessions/current` y `DELETE /api/v1/auth/sessions`
- **CUANDO** se envían sin credencial
- **ENTONCES** la respuesta es `401` con el código `authentication-required`, no `403`, y el
  controlador registra cero invocaciones

#### Escenario: [WE104] La lista autenticada no absorbe una ruta con permiso

- **DADO** la ruta `GET /api/v1/identity/roles`
- **CUANDO** se comparan la lista autenticada y la lista de rutas con permiso
- **ENTONCES** la ruta figura solo en la de rutas con permiso, y ninguna ruta figura en las dos

### Requisito: Las rutas públicas y las listas de rutas autenticadas y restringidas son cerradas y una prueba las hace cumplir

La lista blanca pública DEBE contener, además de la documentación de springdoc, `POST /api/v1/auth/sessions`
(inicio de sesión), `POST /api/v1/auth/sessions/refresh` y `DELETE /api/v1/auth/sessions/current` (estas dos se
autentican por cookie y CSRF dentro del borde, no por `Bearer`). La lista cerrada de rutas autenticadas DEBE
contener exactamente `GET /api/v1/auth/sessions/current` y `DELETE /api/v1/auth/sessions`, ambas con comprobación
de la vigencia de la sesión. La clase restringida DEBE contener exactamente las dos rutas de MFA. La lista cerrada de
rutas con permiso DEBE contener, en producción, exactamente `GET /api/v1/identity/roles`. Ninguna ruta
fuera de las cuatro clases DEBE responder sin credencial, y la prueba de enumeración DEBE distinguir las cuatro
clases y fallar si una ruta no figura o si una entrada no corresponde a una ruta registrada.
(Previously: eran tres clases y no existía la lista de rutas con permiso.)

#### Escenario: [WE79] Contenido exacto de las cuatro clases

- **DADO** las listas tal como las entrega este cambio
- **CUANDO** se enumeran
- **ENTONCES** la pública añade las tres rutas, la autenticada tiene dos entradas con comprobación de sesión, la
  restringida tiene dos y la de rutas con permiso tiene una

#### Escenario: [WE80] Una ruta de la lista pública que exige credencial rota falla la prueba

- **DADO** una ruta pública de sesión configurada, a propósito, para responder `200` a un método no declarado
- **CUANDO** se ejecuta la prueba de enumeración
- **ENTONCES** falla señalando la ruta

#### Escenario: [WE81] Otro método sobre una ruta de sesión no se sirve

- **DADO** `GET`, `PUT` y `PATCH` sobre `/api/v1/auth/sessions`, `/api/v1/auth/sessions/refresh` y
  `/api/v1/auth/mfa/verifications`
- **CUANDO** se envían sin credencial
- **ENTONCES** ninguna responde `2xx` y el controlador registra cero invocaciones

#### Escenario: [WE105] Una ruta con permiso responde `401` sin credencial y la prueba lo exige

- **DADO** una ruta de la lista de rutas con permiso configurada, a propósito, para responder `200` sin credencial
- **CUANDO** se ejecuta la prueba de enumeración
- **ENTONCES** falla señalando la ruta

## REMOVED Requirements

### Requisito: Ausencia de matriz de roles, cobertura de MFA por ruta, claim de permisos y guarda de arranque de RBAC (brecha con destino: cambio 8)

(Reason: el cambio 8 entrega la matriz de roles, la exigencia de `otp` por ruta con permiso y la guarda de arranque;
la ausencia de esas piezas deja de ser cierta.)
(Migration: la decisión de permiso en la cadena está en el requisito «La decisión de permiso se toma en la cadena
administrativa, después de la verificación del token y antes de la idempotencia y del limitador» de este delta, y el
resto en la capacidad `authorization`. El escenario WE40, según el cual un token con la claim `permissions` se
rechaza con `401 token-invalid`, y el principal sin permisos siguen vigentes: los conserva el escenario AZ24 de
`authorization` y el requisito «El principal autenticado vive en `shared.security` y no lleva datos personales ni
permisos», que no cambia. `WebEdgeScopeExclusionInventoryTest` se invierte según `build-integrity`.)

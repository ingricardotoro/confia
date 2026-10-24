# Capacidad: Borde web del proceso administrativo

- **Identificador:** web-edge
- **Estado:** aprobada por el propietario del producto el 2026-10-04
- **Fase:** F0
- **Cambio:** `web-edge-foundations` (cambio 7, parte 4a)

## Propósito

Capacidad técnica: especifica el comportamiento observable del borde HTTP del backend antes de que
exista ningún endpoint de negocio. Cubre la cadena de seguridad que deniega por defecto, la cadena
del portal que deniega todo, el formato de error Problem Details (RFC 9457) y su catálogo de
mensajes en español de Honduras, el contexto de la petición (identificador, IP de cliente por
proxies de confianza, agente de usuario), el limitador por IP en memoria, la materialización del
retardo de autenticación y la superficie HTTP de la idempotencia (ADR-0010).

No define ningún endpoint de negocio ni de identidad: cada pieza se demuestra con la cadena de
filtros y con controladores que existen solo en el árbol de pruebas. Las reglas de arquitectura y
de construcción que acompañan a estas piezas viven en `build-integrity`. Las brechas con dueño
nombrado se declaran al final como requisitos de ausencia.

Fuentes de verdad: `docs/03-seguridad.md` §4.4, §8.2 y §10; `docs/01-arquitectura.md` §7; ADR-0003,
ADR-0010, ADR-0019 y ADR-0024; `CLAUDE.md` reglas 6, 9, 10, 11 y 14.

## Requisitos

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

### Requisito: La lista blanca pública es cerrada y una prueba la hace cumplir

La lista blanca pública del proceso administrativo DEBE ser un valor cerrado, definido en un único
lugar. Una prueba DEBE enumerar todas las rutas que el contexto administrativo registra y DEBE
fallar si una ruta registrada responde sin credencial y no figura en la lista. El documento OpenAPI
y Swagger UI DEBEN seguir respondiendo solo con los perfiles `local` y `preprod` (requisito
existente de `build-integrity`); con cualquier otro perfil, incluido `prod`, NO DEBEN figurar en la
lista ni responder.

#### Escenario: Una ruta pública nueva sin editar la lista

- **DADO** un controlador de prueba cuya ruta se permite sin credencial pero no figura en la lista
  blanca
- **CUANDO** se ejecuta la prueba que enumera las rutas registradas
- **ENTONCES** la prueba falla señalando la ruta

#### Escenario: La lista contiene solo lo declarado

- **DADO** la lista blanca pública tal como la entrega este cambio
- **CUANDO** la prueba enumera las rutas registradas
- **ENTONCES** toda ruta que responde sin credencial figura en la lista, y toda entrada de la lista
  corresponde a una ruta registrada o a una condición de perfil declarada

#### Escenario: Documentación de API con perfil `prod`

- **DADO** el proceso administrativo con el perfil `prod`
- **CUANDO** se piden el documento OpenAPI y Swagger UI sin credencial
- **ENTONCES** ambas respuestas son `401` con `application/problem+json` y `type` derivado de
  `authentication-required`, no el documento ni la interfaz

#### Escenario: Documentación de API con perfil `local`

- **DADO** el proceso administrativo con el perfil `local`
- **CUANDO** se pide el documento OpenAPI sin credencial
- **ENTONCES** responde `200` con un documento OpenAPI 3.1 válido

### Requisito: La cadena administrativa no tiene estado

La cadena administrativa NO DEBE crear sesión HTTP ni enviar cookies en ninguna respuesta, incluidas las
respuestas de error, con la única excepción de las cookies de sesión y CSRF que emiten y borran los endpoints de
inicio de sesión, verificación del segundo factor, refresco y cierres, según el requisito de la cookie de sesión.
(Previously: ninguna respuesta podía enviar cookies.)

#### Escenario: Ninguna respuesta crea sesión

- **DADO** cualquier petición a una ruta pública, a una ruta denegada y a una ruta de prueba que
  responde con éxito
- **CUANDO** se inspeccionan las tres respuestas
- **ENTONCES** ninguna contiene la cabecera `Set-Cookie` y el contenedor no registra ninguna sesión
  HTTP creada

#### Escenario: [WE87] Las cookies de los endpoints de sesión no crean una sesión HTTP

- **DADO** un inicio de sesión con éxito
- **CUANDO** se inspecciona la respuesta
- **ENTONCES** contiene solo las dos cookies de sesión y CSRF, ninguna cookie `JSESSIONID` y el contenedor no
  registra ninguna sesión HTTP

### Requisito: Cabeceras de seguridad base en toda respuesta de la cadena administrativa

Toda respuesta de la cadena administrativa, de éxito o de error, DEBE incluir las cabeceras de
`docs/03-seguridad.md` §8.2 que correspondan a una API: `X-Content-Type-Options: nosniff`,
`X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`,
`Permissions-Policy` con el valor de §8.2 y `Cache-Control: no-store`. Las respuestas NO DEBEN
incluir `X-Powered-By`. Las cabeceras de transporte que aporta el proxy (`Strict-Transport-Security`,
`Cross-Origin-Embedder-Policy`) quedan para el cambio 11 (brecha con destino: cambio 11).

#### Escenario: Respuesta de error con las cabeceras base

- **DADO** una petición denegada por la cadena
- **CUANDO** se inspeccionan las cabeceras de la respuesta
- **ENTONCES** contiene las cinco cabeceras con los valores indicados y no contiene `X-Powered-By`

#### Escenario: Respuesta de éxito con las cabeceras base

- **DADO** una petición a una ruta de prueba que responde `200`
- **CUANDO** se inspeccionan las cabeceras de la respuesta
- **ENTONCES** contiene las mismas cinco cabeceras con los mismos valores

### Requisito: La cadena del portal deniega toda ruta

El proceso del portal DEBE tener una cadena de filtros de seguridad que deniega toda petición, sin
lista blanca de aplicación, para todo método HTTP incluidos `GET`, `POST`, `PUT`, `PATCH`, `DELETE`,
`HEAD` y `OPTIONS`. Solo cuando springdoc está habilitado (perfiles `local` y `preprod`) se permiten
las entradas de documentación, las mismas que la lista blanca del proceso administrativo; con el
perfil por omisión (producción) el portal deniega todo sin excepción. La respuesta denegada DEBE ser
`401` con Problem Details, con el código `authentication-required`, y las mismas cabeceras de
seguridad base. El portal NO DEBE registrar ninguna ruta de aplicación, y su mapa de rutas con el
perfil por omisión coincide con la instantánea vacía exigida por `build-integrity`.

#### Escenario: Toda ruta del portal es denegada

- **DADO** el proceso del portal con el perfil por omisión
- **CUANDO** se envía una petición a `/`, a una ruta inexistente y a una ruta con el prefijo de la
  API, con cada método HTTP
- **ENTONCES** todas las respuestas son `401` con `application/problem+json` y `type` derivado de
  `authentication-required`

#### Escenario: Una ruta añadida al portal sigue denegada

- **DADO** un controlador de prueba registrado en el contexto del portal
- **CUANDO** se envía una petición a su ruta sin credencial
- **ENTONCES** la respuesta es `401` y el controlador registra cero invocaciones

#### Escenario: Sin ruta pública en el portal

- **DADO** el portal tal como lo entrega este cambio, con el perfil por omisión
- **CUANDO** se enumeran las rutas registradas
- **ENTONCES** el conjunto es vacío, coincide con la instantánea aprobada, y ninguna petición
  recibe una respuesta `2xx`

#### Escenario: Documentación del portal con perfil `local`

- **DADO** el proceso del portal con el perfil `local`
- **CUANDO** se pide el documento OpenAPI sin credencial y se pide cualquier otra ruta
- **ENTONCES** el documento OpenAPI responde `200`, y toda ruta fuera de las entradas de
  documentación responde `401`

### Requisito: El trabajador no tiene cadena de seguridad ni servidor web

El proceso trabajador NO DEBE registrar ninguna cadena de seguridad, ningún filtro de petición ni
ningún servidor web.

#### Escenario: Contexto del trabajador sin borde web

- **DADO** el proceso trabajador arrancado por su camino de producción
- **CUANDO** se inspecciona su contexto
- **ENTONCES** no contiene ningún bean `SecurityFilterChain` ni filtro de petición, y no hay
  servidor web escuchando

### Requisito: Toda respuesta de error usa Problem Details (RFC 9457)

Toda respuesta de error del borde web, incluidas las producidas por la cadena de seguridad, por la
validación, por el limitador, por el materializador y por la idempotencia, DEBE tener
`Content-Type: application/problem+json` y los campos `type`, `title`, `status`, `detail` e
`instance` de RFC 9457, además del campo `traceId` que declara el esquema de error del contrato
OpenAPI. El valor del identificador de traza DEBE ser el identificador de petición generado
en el servidor. `instance` NO DEBE incluir la cadena de consulta de la petición. El campo `status`
DEBE coincidir con el estado HTTP de la respuesta.

#### Escenario: Respuesta de error bien formada

- **DADO** una petición denegada por la cadena
- **CUANDO** se lee la respuesta
- **ENTONCES** `Content-Type` es `application/problem+json`, el cuerpo contiene los cinco campos de
  RFC 9457 y el identificador de traza, y `status` coincide con el estado HTTP

#### Escenario: El identificador de traza es el de la petición

- **DADO** una petición que termina en error
- **CUANDO** se compara el identificador de traza del cuerpo con el identificador de petición del
  contexto
- **ENTONCES** son iguales

#### Escenario: La cadena de consulta no aparece en `instance`

- **DADO** una petición denegada a `/x?token=secreto`
- **CUANDO** se lee el cuerpo de la respuesta
- **ENTONCES** `instance` no contiene `token` ni `secreto`

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
`401`; `token-invalid` con `401`; `token-expired` con `401`; `authentication-failed` con `401`;
`second-factor-invalid` con `401`; `session-expired` con `401`; `forbidden` con `403`; `csrf-invalid` con `403`;
`resource-not-found` con `404`; `method-not-allowed` con `405`; `idempotency-conflict` con `409`;
`mfa-already-enrolled` con `409`; `unsupported-media-type` con `415`;
`idempotency-payload-mismatch` con `422`; `too-many-requests` con `429` y `Retry-After`;
`capacity-exceeded` con `503`; `internal-error` con `500`. `resource-not-found` solo tiene productor
bajo los prefijos públicos de springdoc, con los perfiles `local` y `preprod`. `token-invalid` y
`token-expired` los produce el filtro de autenticación. `authentication-failed`, `second-factor-invalid`,
`session-expired`, `csrf-invalid` y `mfa-already-enrolled` los producen los endpoints de sesión y el filtro de
CSRF. Los códigos de institución (`institution-not-found`, `institution-inactive`) NO DEBEN existir en este
cambio (brecha con destino: la primera ruta que resuelva la institución contra `organization`).
(Previously: trece códigos listados; `authentication-failed` NO DEBÍA existir. `method-not-allowed` ya existe en
el código y se incorpora a la lista.)

#### Escenario: Cada código tiene su estado

- **DADO** un productor de prueba para cada uno de los diecinueve códigos
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

#### Escenario: [WE35] Los códigos de token y de sesión existen y los de institución no

- **DADO** el catálogo de códigos de error entregado y su catálogo de mensajes es-HN
- **CUANDO** se enumeran sus entradas y se ejecuta la prueba de cobertura del catálogo
- **ENTONCES** contiene `authentication-required`, `token-invalid`, `token-expired`, `authentication-failed`,
  `second-factor-invalid`, `session-expired`, `csrf-invalid` y `mfa-already-enrolled`, cada uno con su entrada
  es-HN
- **Y** no contiene `institution-not-found` ni `institution-inactive`

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

### Requisito: Catálogo de mensajes del backend en español de Honduras

El backend DEBE tener un catálogo de mensajes en español de Honduras como fuente única de los
`title` y `detail` de los Problem Details. Ninguna cadena de mensaje de usuario DEBE estar embebida
en una clase de producción. Cada código del catálogo de errores DEBE tener su entrada, y una prueba
DEBE fallar si un código no tiene entrada o si una entrada no corresponde a ningún código. El
idioma de la respuesta NO DEBE variar con la cabecera `Accept-Language` mientras exista un único
catálogo.

#### Escenario: Código sin entrada en el catálogo

- **DADO** un código estable del catálogo de errores sin entrada de mensaje
- **CUANDO** se ejecuta la prueba de cobertura del catálogo
- **ENTONCES** la prueba falla señalando el código

#### Escenario: Entrada sin código

- **DADO** una entrada del catálogo que no corresponde a ningún código
- **CUANDO** se ejecuta la prueba de cobertura del catálogo
- **ENTONCES** la prueba falla señalando la entrada

#### Escenario: Idioma fijo

- **DADO** una petición denegada con `Accept-Language: en-US`
- **CUANDO** se lee el `detail`
- **ENTONCES** es el texto del catálogo es-HN, idéntico al de una petición sin la cabecera

### Requisito: El identificador de petición lo genera el servidor

El filtro de contexto de petición DEBE generar, para cada petición, un identificador nuevo en el
servidor (un UUID) y DEBE ignorar cualquier identificador que el cliente envíe en una cabecera. El
identificador DEBE estar disponible durante toda la petición, incluso en las respuestas de error
producidas por la cadena de seguridad, y DEBE ser distinto para cada petición, también bajo
concurrencia.

#### Escenario: El cliente envía su propio identificador

- **DADO** una petición con las cabeceras `X-Request-Id: cliente-123` y `traceparent` propias
- **CUANDO** el servidor la procesa
- **ENTONCES** el identificador de petición es un UUID generado en el servidor y distinto de
  `cliente-123`

#### Escenario: Identificadores únicos bajo concurrencia

- **DADO** 100 peticiones concurrentes sincronizadas con `CyclicBarrier`
- **CUANDO** se recolectan sus identificadores
- **ENTONCES** son 100 valores distintos

#### Escenario: Respuesta de la cadena de seguridad con identificador

- **DADO** una petición denegada por la cadena antes de llegar a ningún controlador
- **CUANDO** se lee el cuerpo de la respuesta
- **ENTONCES** contiene el identificador de petición generado en el servidor

### Requisito: La IP del cliente se obtiene solo a través de proxies de confianza

El filtro de contexto de petición DEBE resolver la IP del cliente así: si la dirección remota de la
conexión no pertenece a la lista de proxies de confianza, la IP del cliente es esa dirección remota
y la cabecera `X-Forwarded-For` DEBE ignorarse por completo. Si la dirección remota pertenece a la
lista, DEBE leerse `X-Forwarded-For` de derecha a izquierda y la IP del cliente es la primera
dirección que no pertenece a la lista de proxies de confianza; si todas las direcciones pertenecen a
la lista, es la primera de la izquierda. Si la cabecera contiene alguna entrada que no es una
dirección IP válida, DEBE ignorarse por completo y usarse la dirección remota. Varias líneas de
`X-Forwarded-For` DEBEN tratarse como una sola lista, en el orden recibido. Una dirección IPv6
mapeada a IPv4 DEBE normalizarse a su forma IPv4 antes de compararse y de usarse. Un cliente NO DEBE
poder fijar su propia IP mientras el proxy no esté configurado, porque anularía el control de H1.

#### Escenario: Lista vacía, cabecera ignorada

- **DADO** la lista de proxies de confianza vacía y una petición desde `203.0.113.9` con
  `X-Forwarded-For: 198.51.100.7`
- **CUANDO** se resuelve la IP del cliente
- **ENTONCES** es `203.0.113.9`

#### Escenario: Origen no confiable, cabecera ignorada

- **DADO** la lista con `10.0.0.1` y una petición desde `203.0.113.9` con
  `X-Forwarded-For: 198.51.100.7`
- **CUANDO** se resuelve la IP del cliente
- **ENTONCES** es `203.0.113.9`

#### Escenario: Origen confiable, cadena de dos proxies

- **DADO** la lista con `10.0.0.1` y `10.0.0.2`, y una petición desde `10.0.0.1` con
  `X-Forwarded-For: 198.51.100.7, 10.0.0.2`
- **CUANDO** se resuelve la IP del cliente
- **ENTONCES** es `198.51.100.7`

#### Escenario: Un cliente intenta anteponer una IP falsa

- **DADO** la lista con `10.0.0.1` y una petición desde `10.0.0.1` con
  `X-Forwarded-For: 1.1.1.1, 198.51.100.7`
- **CUANDO** se resuelve la IP del cliente
- **ENTONCES** es `198.51.100.7`, la primera entrada no confiable leyendo de derecha a izquierda,
  y no `1.1.1.1`

#### Escenario: Todas las entradas son proxies de confianza

- **DADO** la lista con `10.0.0.1` y `10.0.0.2`, y una petición desde `10.0.0.1` con
  `X-Forwarded-For: 10.0.0.2`
- **CUANDO** se resuelve la IP del cliente
- **ENTONCES** es `10.0.0.2`, la primera de la izquierda

#### Escenario: Entrada no válida en la cabecera

- **DADO** la lista con `10.0.0.1` y una petición desde `10.0.0.1` con
  `X-Forwarded-For: 198.51.100.7, basura`, y otra con `X-Forwarded-For: ` vacío
- **CUANDO** se resuelve la IP del cliente en cada una
- **ENTONCES** ambas resuelven `10.0.0.1`, la dirección remota

#### Escenario: Varias líneas de la cabecera

- **DADO** la lista con `10.0.0.1` y una petición desde `10.0.0.1` con dos cabeceras
  `X-Forwarded-For`, la primera `198.51.100.7` y la segunda `10.0.0.2`
- **CUANDO** se resuelve la IP del cliente
- **ENTONCES** se trata como `198.51.100.7, 10.0.0.2` y resuelve `198.51.100.7` si `10.0.0.2`
  pertenece a la lista

#### Escenario: IPv6 mapeada a IPv4

- **DADO** la lista con `10.0.0.1` y una petición desde `::ffff:10.0.0.1` con
  `X-Forwarded-For: 198.51.100.7`
- **CUANDO** se resuelve la IP del cliente
- **ENTONCES** la dirección remota se normaliza a `10.0.0.1`, pertenece a la lista y la IP del cliente
  es `198.51.100.7`

### Requisito: La lista de proxies de confianza es una propiedad por entorno, vacía por defecto

La lista de proxies de confianza DEBE ser una propiedad de configuración por entorno, inyectable por
variable de entorno, con valor por defecto vacío. Cada entrada DEBE ser una dirección IP o un rango
CIDR válido. Una entrada inválida DEBE impedir el arranque del proceso y el mensaje de error DEBE
nombrar la propiedad. El valor por defecto de ninguna configuración de producción NO DEBE contener
rangos. El llenado con nginx y los rangos de Cloudflare es del cambio 11 (brecha con destino:
cambio 11).

#### Escenario: Propiedad ausente

- **DADO** el proceso arrancado sin definir la propiedad
- **CUANDO** se consulta la lista de proxies de confianza efectiva
- **ENTONCES** está vacía

#### Escenario: Rango CIDR de entrada

- **DADO** la propiedad con `10.0.0.0/8` y una petición desde `10.1.2.3` con
  `X-Forwarded-For: 198.51.100.7`
- **CUANDO** se resuelve la IP del cliente
- **ENTONCES** es `198.51.100.7`, y con una petición desde `11.0.0.1` es `11.0.0.1`

#### Escenario: Entrada inválida

- **DADO** la propiedad con `10.0.0.0/33` o con `no-es-una-ip`
- **CUANDO** arranca el proceso
- **ENTONCES** el arranque falla con un mensaje que nombra la propiedad

#### Escenario: Inyección por variable de entorno

- **DADO** la variable de entorno que alimenta la propiedad con `10.0.0.1`
- **CUANDO** arranca el proceso
- **ENTONCES** la lista efectiva contiene `10.0.0.1`

### Requisito: El agente de usuario se captura acotado

El filtro de contexto de petición DEBE capturar la cabecera `User-Agent` y DEBE truncarla a una
longitud máxima configurada, sin rechazar la petición. Si la cabecera falta, el valor DEBE ser
nulo y la petición NO DEBE fallar.

#### Escenario: Cabecera ausente

- **DADO** una petición sin `User-Agent`
- **CUANDO** se consulta el contexto de petición
- **ENTONCES** el agente de usuario es nulo y la petición se procesa

#### Escenario: Longitud en el límite

- **DADO** una longitud máxima configurada de `L`
- **CUANDO** llegan peticiones con un agente de usuario de `L` y de `L + 1` caracteres
- **ENTONCES** el primero se conserva completo, el segundo se trunca a `L` caracteres y ninguna
  petición se rechaza

### Requisito: El origen de la petición llega a la bitácora de auditoría

Un asiento de auditoría escrito durante una petición servida por la cadena DEBE llevar `sourceIp`
con la IP del cliente resuelta y `userAgent` con el agente de usuario capturado. Un asiento escrito
fuera de una petición (trabajo en segundo plano, prueba sin cadena) DEBE conservar ambos campos
nulos. El origen de una petición NO DEBE filtrarse a otra, incluso con peticiones concurrentes. La
propagación del origen a los comandos de identidad la entrega `session-endpoints-cookie-and-csrf`.
(Previously: la propagación del origen a los comandos de identidad era una brecha con destino
`session-endpoints-cookie-and-csrf`.)

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

### Requisito: Los registros no contienen cabeceras de autorización, cookies ni cuerpos de petición

Ningún registro del borde web, a ningún nivel, DEBE contener el valor de `Authorization`, `Cookie`,
`Set-Cookie`, ni el cuerpo de una petición (`CLAUDE.md`, regla 11). La verificación DEBE tener un
control negativo que demuestre que el mecanismo de captura detecta un valor sensible cuando se
registra a propósito, de modo que una captura vacía no pase por válida.

#### Escenario: Petición con datos sensibles

- **DADO** una petición con `Authorization: Bearer SECRETO-A`, `Cookie: sid=SECRETO-B` y un cuerpo con
  `SECRETO-C`, capturando todos los registros a nivel `TRACE`
- **CUANDO** el servidor la procesa, tanto si se acepta como si se deniega y como si falla con `500`
- **ENTONCES** ninguno de los tres valores aparece en ningún registro

#### Escenario: Control negativo

- **DADO** el mismo mecanismo de captura y un valor sensible registrado deliberadamente por la prueba
- **CUANDO** se revisa la captura
- **ENTONCES** la verificación detecta el valor y reporta la violación

### Requisito: El puerto `RateLimiter` cuenta peticiones y fallos como dimensiones separadas

El sistema DEBE declarar un puerto `RateLimiter` en `com.confia.shared.security`, con un adaptador
en memoria, por proceso. El puerto DEBE contar las peticiones admitidas y los fallos registrados
como dos dimensiones independientes de una misma política, de modo que superar una dimensión no
altere el contador de la otra. Una petición rechazada por el limitador NO DEBE contar como petición
admitida ni extender su propia ventana. El reloj DEBE inyectarse.

#### Escenario: Las dimensiones no se mezclan

- **DADO** una IP con 9 peticiones admitidas y ningún fallo en la ventana
- **CUANDO** se registran 10 fallos
- **ENTONCES** el contador de peticiones sigue en 9 y el de fallos en 10

#### Escenario: Una petición rechazada no extiende la ventana

- **DADO** una IP que agotó su límite de peticiones y recibe `429`
- **CUANDO** insiste durante la ventana y llega el instante en que vence la primera petición
- **ENTONCES** se vuelve a admitir una petición en ese instante, sin que los rechazos hayan movido el
  vencimiento

### Requisito: Capa 1 del inicio de sesión administrativo, 10 peticiones por minuto por IP

La política del inicio de sesión administrativo DEBE admitir como máximo 10 peticiones por minuto
por IP de cliente (`docs/03-seguridad.md` §10), con ventana deslizante medida con el reloj
inyectado. La undécima petición dentro de la ventana DEBE rechazarse con `429`. Cada IP DEBE tener
su propio contador.

#### Escenario: Límite exacto

- **DADO** una IP sin peticiones previas
- **CUANDO** envía 10 peticiones dentro del mismo minuto y después una undécima
- **ENTONCES** las 10 primeras se admiten y la undécima recibe `429` con `Retry-After`

#### Escenario: Primera petición de una IP

- **DADO** una IP sin ningún estado en la tabla
- **CUANDO** envía su primera petición
- **ENTONCES** se admite

#### Escenario: La ventana se desliza

- **DADO** una IP con 10 peticiones admitidas en `t = 0`, `t = 1 s`, ..., `t = 9 s`
- **CUANDO** el reloj inyectado avanza a `t = 60 s`
- **ENTONCES** una nueva petición se admite, y una segunda petición en el mismo instante se rechaza
  hasta que venza la petición de `t = 1 s`

#### Escenario: IP distinta, contador distinto

- **DADO** una IP con su límite agotado
- **CUANDO** otra IP envía una petición
- **ENTONCES** se admite

#### Escenario: Exactitud bajo concurrencia

- **DADO** una IP sin peticiones previas y 50 hilos sincronizados con `CyclicBarrier`
- **CUANDO** los 50 intentan una petición en el mismo instante del reloj inyectado
- **ENTONCES** exactamente 10 se admiten y exactamente 40 se rechazan, en todas las ejecuciones

### Requisito: Capa 2 del inicio de sesión administrativo, retroceso por fallos por IP

La política del inicio de sesión administrativo DEBE aplicar, a partir del fallo número 10 de una IP
dentro de 10 minutos, un límite de 1 intento por minuto para esa IP, con tope de 1 hora de duración
de la restricción (`docs/03-seguridad.md` §4.4). Un inicio de sesión exitoso NO DEBE limpiar el
contador de fallos de la IP. Los fallos con más de 10 minutos de antigüedad NO DEBEN contarse. La
restricción NO DEBE durar más de 1 hora desde que se activó; transcurrida, el contador de fallos de la
IP parte de cero. La restricción DEBE terminar antes del tope si pasan 10 minutos sin ningún fallo de
esa IP; en ese caso los fallos registrados se conservan. La restricción NO DEBE terminar solo porque
la cantidad de fallos dentro de la ventana baje de 10 mientras la IP siga fallando. Esta capa es
independiente de la capa 1 y se aplica además de ella.

#### Escenario: El fallo nueve no activa la restricción

- **DADO** una IP con 9 fallos en 10 minutos
- **CUANDO** envía dos intentos separados por menos de un minuto, con la capa 1 sin agotar
- **ENTONCES** ambos se admiten

#### Escenario: El fallo diez activa la restricción

- **DADO** una IP cuyo intento admitido en `t0` termina en el fallo número 10 dentro de 10 minutos
- **CUANDO** envía un intento en `t0 + 30 s`
- **ENTONCES** recibe `429` con `Retry-After` de 30 segundos
- **Y** un intento en `t0 + 60 s` se admite

#### Escenario: Los fallos fuera de la ventana no cuentan

- **DADO** una IP con 9 fallos recientes y un décimo fallo ocurrido hace 10 minutos y 1 segundo
- **CUANDO** se evalúa la restricción
- **ENTONCES** no está activa, porque solo 9 fallos están dentro de la ventana

#### Escenario: Un fallo en el borde exacto de la ventana

- **DADO** una IP cuyo décimo fallo ocurre exactamente 10 minutos después del primero, siendo
  esos los únicos 10 fallos; un fallo cuenta solo mientras su antigüedad sea estrictamente menor que
  10 minutos
- **CUANDO** se evalúa la restricción en ese instante
- **ENTONCES** el primer fallo ya no cuenta, solo 9 fallos están dentro de la ventana y la restricción
  no está activa

#### Escenario: El éxito no limpia el contador de la IP

- **DADO** una IP con 9 fallos y un inicio de sesión exitoso posterior
- **CUANDO** se registra un nuevo fallo
- **ENTONCES** el contador de fallos de la IP es 10 y la restricción se activa

#### Escenario: Tope de una hora

- **DADO** una IP con la restricción activa desde `t = 0` que sigue fallando
- **CUANDO** el reloj inyectado llega a `t = 1 h`
- **ENTONCES** la restricción de la capa 2 termina y el contador de fallos de la IP parte de cero

#### Escenario: Diez minutos sin fallos terminan la restricción antes del tope

- **DADO** una IP con la restricción activa cuyo último fallo ocurrió en `t1`
- **CUANDO** el reloj inyectado llega a `t1 + 10 min` sin ningún fallo nuevo de esa IP
- **ENTONCES** la restricción de la capa 2 termina antes de cumplirse 1 hora desde su activación
- **Y** los fallos registrados se conservan, de modo que el contador no parte de cero

#### Escenario: La restricción no se levanta mientras la IP sigue fallando

- **DADO** una IP con la restricción activa que falla en cada intento que la capa 2 le admite, uno
  por minuto
- **CUANDO** su fallo más antiguo sale de la ventana de 10 minutos y deja 9 fallos dentro de ella
- **ENTONCES** la restricción sigue activa y el siguiente intento antes de un minuto recibe `429`

### Requisito: Las direcciones IPv6 se agrupan por /64

El limitador DEBE usar como clave de una IPv6 sus primeros 64 bits, de modo que dos direcciones del
mismo prefijo /64 comparten un único contador. Una IPv4 DEBE usarse completa y no se agrupa. Una IPv6
mapeada a IPv4 DEBE tratarse como la IPv4 que contiene. El identificador de zona de una IPv6 DEBE
ignorarse.

#### Escenario: Dos direcciones del mismo /64

- **DADO** `2001:db8:1:2::1` y `2001:db8:1:2:ffff:ffff:ffff:ffff`, con un límite de 10
- **CUANDO** la primera hace 6 peticiones y la segunda hace 5
- **ENTONCES** la undécima petición combinada se rechaza, porque comparten un contador

#### Escenario: Direcciones de /64 contiguos

- **DADO** `2001:db8:1:2::1` y `2001:db8:1:3::1`, que difieren en el último bit del prefijo
- **CUANDO** cada una agota su límite por separado
- **ENTONCES** son dos contadores independientes, y agotar uno no afecta al otro

#### Escenario: IPv4 sin agrupar

- **DADO** `203.0.113.9` y `203.0.113.10`
- **CUANDO** la primera agota su límite
- **ENTONCES** la segunda se admite

#### Escenario: IPv6 mapeada a IPv4

- **DADO** `::ffff:203.0.113.9` y `203.0.113.9`
- **CUANDO** ambas envían peticiones
- **ENTONCES** comparten un único contador

### Requisito: La tabla acotada del limitador falla cerrada al llenarse

La tabla de estado del limitador DEBE tener un tamaño máximo configurado. Con la tabla llena, el
limitador DEBE rechazar toda IP que no tenga ya una entrada, en vez de admitirla, con `503` y el
código `capacity-exceeded`, y NO DEBE evictar una entrada vigente para hacerle sitio. Las IP con
entrada vigente DEBEN seguir contándose y limitándose con normalidad. Una entrada vencida DEBE
poder reclamarse, de modo que una vez vencida una entrada vuelva a admitirse a una IP nueva. La tabla
NO DEBE superar su tamaño máximo en ningún instante. La respuesta de rechazo por tabla llena NO DEBE
distinguir qué IP la causó. (La elección de `503` con `capacity-exceeded` frente a `429` queda
registrada como supuesto de esta especificación.)

#### Escenario: Límite exacto de la tabla

- **DADO** una tabla de tamaño máximo `N` con `N - 1` entradas vigentes
- **CUANDO** llegan, de una en una, una IP nueva y después otra IP nueva
- **ENTONCES** la primera se admite y la segunda se rechaza con `503`

#### Escenario: Tabla llena, IP con entrada vigente

- **DADO** una tabla llena
- **CUANDO** una IP que ya tiene entrada envía una petición dentro de su límite
- **ENTONCES** se admite

#### Escenario: Una entrada vencida libera espacio

- **DADO** una tabla llena cuya entrada más antigua vence con el reloj inyectado
- **CUANDO** llega una IP nueva
- **ENTONCES** se admite

#### Escenario: Llenado concurrente

- **DADO** una tabla de tamaño máximo `N` vacía y `2N` IP distintas que envían una petición cada
  una, sincronizadas con `CyclicBarrier`
- **CUANDO** terminan todas
- **ENTONCES** exactamente `N` se admiten, exactamente `N` se rechazan con `503`, y el tamaño de la
  tabla nunca superó `N`

#### Escenario: Tamaño máximo no válido

- **DADO** el tamaño máximo de la tabla configurado en 0 o en un valor negativo
- **CUANDO** arranca el proceso
- **ENTONCES** el arranque falla con un mensaje que nombra la propiedad

### Requisito: El rechazo por límite responde `429` con `Retry-After` y no revela el cupo restante

Una petición rechazada por el límite de tasa DEBE recibir `429` con Problem Details del código
`too-many-requests` y la cabecera `Retry-After` en segundos enteros, redondeados hacia arriba y nunca
menores de 1. La respuesta NO DEBE incluir el cupo restante ni el límite, ni en cabeceras
(`X-RateLimit-*`) ni en el cuerpo (`docs/03-seguridad.md` §10).

#### Escenario: Retry-After redondeado hacia arriba

- **DADO** una IP rechazada a la que le faltan 200 milisegundos para que venza su ventana
- **CUANDO** se lee la respuesta
- **ENTONCES** `Retry-After` es `1`

#### Escenario: Retry-After de la capa 1

- **DADO** una IP rechazada en la capa 1 a 15 segundos del vencimiento de su petición más antigua
- **CUANDO** se lee la respuesta
- **ENTONCES** `Retry-After` es `15`

#### Escenario: Sin cupo ni límite

- **DADO** una respuesta `429`
- **CUANDO** se inspeccionan sus cabeceras y su cuerpo
- **ENTONCES** no contiene ninguna cabecera `X-RateLimit-*` ni un número que corresponda al límite
  o al cupo restante

### Requisito: El limitador se ejecuta antes del caso de uso

El limitador DEBE ejecutarse en el borde, antes de abrir ninguna transacción y antes de invocar
ningún caso de uso, de modo que una petición rechazada NO DEBE consumir una conexión de la base de
datos, ni ejecutar el hash de contraseña, ni registrar un fallo en ningún almacén.

#### Escenario: Petición rechazada sin efecto

- **DADO** una IP con su límite agotado y un controlador de prueba que cuenta sus invocaciones y las
  transacciones abiertas
- **CUANDO** la IP envía una petición
- **ENTONCES** la respuesta es `429`, el controlador registra cero invocaciones y no se abre ninguna
  transacción

### Requisito: Los límites viven en configuración y se validan al arrancar

Los valores del limitador (límite, ventana, umbral de fallos, ventana de fallos, tope de la
restricción y tamaño de la tabla) DEBEN residir en la configuración, no en el código, y DEBEN poder
ajustarse sin recompilar. Un valor no positivo DEBE impedir el arranque con un mensaje que nombra la
propiedad. Los valores por defecto de la política del inicio de sesión administrativo DEBEN ser los
de las capas 1 y 2.

#### Escenario: Cambio de configuración

- **DADO** el límite de la capa 1 configurado en 3
- **CUANDO** una IP envía 4 peticiones en un minuto
- **ENTONCES** la cuarta recibe `429`

#### Escenario: Valores por defecto

- **DADO** el proceso arrancado sin sobrescribir la política
- **CUANDO** se consultan los valores efectivos
- **ENTONCES** son 10 peticiones por minuto, 10 fallos en 10 minutos, 1 intento por minuto durante la
  restricción y un tope de 1 hora

#### Escenario: Valor no positivo

- **DADO** un límite o una ventana configurados en 0 o en un valor negativo
- **CUANDO** arranca el proceso
- **ENTONCES** el arranque falla con un mensaje que nombra la propiedad

### Requisito: La limitación del limitador en memoria está declarada por escrito

El sistema DEBE declarar que el estado del limitador es por proceso y se pierde al reiniciar, y que
no se comparte entre réplicas, en una nota fechada de `docs/03-seguridad.md` §4.4 y §10, sin
reescribir el cuerpo de esas secciones. El estado por proceso NO DEBE presentarse como equivalente
al estado compartido de Redis (brecha con destino: cambio 11).

#### Escenario: Estado perdido al reiniciar

- **DADO** una IP con su límite agotado
- **CUANDO** se crea una instancia nueva del limitador, como en un reinicio
- **ENTONCES** la IP se admite, comportamiento que la nota documental declara

#### Escenario: Nota fechada en la documentación

- **DADO** `docs/03-seguridad.md` tras este cambio
- **CUANDO** se leen §4.4 y §10
- **ENTONCES** cada sección tiene una nota fechada que declara las tres limitaciones, y el texto
  original de la sección no fue modificado

### Requisito: El retardo requerido se materializa después de confirmar la transacción

El borde DEBE materializar el `requiredDelay` que devuelve un caso de uso después de que la
transacción del caso de uso se haya confirmado, nunca dentro de ella. Durante la espera, la petición
NO DEBE retener transacción, conexión del grupo ni bloqueo de base de datos, ni ocupar un hilo de
plataforma. La espera NO DEBE ocurrir en `application` ni en `domain`. Un retardo de cero DEBE
responder sin esperar, y el permiso del semáforo, que se adquiere antes de conocer el retardo,
DEBE quedar liberado al responder; un retardo negativo DEBE tratarse como cero.

#### Escenario: La espera ocurre tras el commit

- **DADO** un caso de uso de prueba que confirma una transacción y devuelve un retardo de 500 ms
  controlado por un temporizador de prueba
- **CUANDO** el borde procesa la petición
- **ENTONCES** al empezar la espera la transacción ya está confirmada, y la respuesta no se envía
  hasta que el temporizador de prueba libera la espera

#### Escenario: Sin recursos retenidos durante la espera

- **DADO** una petición en espera de su retardo
- **CUANDO** se inspecciona el grupo de conexiones y los bloqueos de la base de datos
- **ENTONCES** el número de conexiones activas es cero y no hay bloqueos retenidos por esa petición

#### Escenario: Retardo cero

- **DADO** un caso de uso que devuelve un retardo de cero
- **CUANDO** el borde procesa la petición
- **ENTONCES** responde sin espera y, después de la respuesta, el semáforo conserva todos sus
  permisos

#### Escenario: Retardo negativo

- **DADO** un caso de uso que devuelve un retardo negativo
- **CUANDO** el borde procesa la petición
- **ENTONCES** responde sin espera, igual que con cero

### Requisito: El retardo se suma al tiempo de procesamiento

El retardo requerido DEBE sumarse al tiempo ya transcurrido y NO DEBE absorberlo: la espera dura el
valor completo de `requiredDelay` medido a partir del fin del procesamiento.

#### Escenario: Un caso de uso lento no reduce la espera

- **DADO** un caso de uso de prueba cuyo procesamiento consume 300 ms del reloj inyectado y devuelve
  un retardo de 1 s
- **CUANDO** el borde lo procesa
- **ENTONCES** la espera solicitada al temporizador de prueba es de 1 s completo, no de 700 ms

### Requisito: Un semáforo acotado limita las esperas y responde `503` uniforme antes de procesar

El materializador DEBE acotar las esperas concurrentes con un semáforo de un número de permisos
configurado y positivo. El borde DEBE adquirir un permiso antes de procesar una petición que puede
requerir retardo. Con el semáforo agotado, DEBE responder `503` con el código `capacity-exceeded`,
con una respuesta uniforme y antes de invocar el caso de uso, de modo que no se produzca ningún efecto
ni ningún registro de fallo. El permiso DEBE liberarse siempre al terminar, incluso si el caso de
uso lanza una excepción o la espera se interrumpe.

#### Escenario: Permiso número P más uno

- **DADO** un semáforo de `P` permisos con `P` esperas en curso
- **CUANDO** llega una petición más
- **ENTONCES** recibe `503` con `capacity-exceeded` y el caso de uso registra cero invocaciones para
  esa petición

#### Escenario: Liberación del permiso

- **DADO** el semáforo agotado
- **CUANDO** termina una de las esperas
- **ENTONCES** la siguiente petición se admite

#### Escenario: Liberación ante una excepción

- **DADO** un caso de uso de prueba que lanza una excepción
- **CUANDO** el borde lo procesa
- **ENTONCES** la respuesta es `500` y el permiso queda liberado

#### Escenario: Concurrencia exacta del semáforo

- **DADO** un semáforo de `P` permisos y `2P` peticiones concurrentes sincronizadas con
  `CyclicBarrier`, cada una con espera retenida por un `CountDownLatch`
- **CUANDO** todas intentan adquirir
- **ENTONCES** exactamente `P` se admiten y exactamente `P` reciben `503`

#### Escenario: Respuesta uniforme

- **DADO** dos rechazos por semáforo agotado en rutas distintas
- **CUANDO** se comparan las respuestas
- **ENTONCES** son idénticas salvo `instance` y el identificador de traza

#### Escenario: Número de permisos no válido

- **DADO** el número de permisos configurado en 0 o en un valor negativo
- **CUANDO** arranca el proceso
- **ENTONCES** el arranque falla con un mensaje que nombra la propiedad

### Requisito: El cierre de la conexión del cliente abandona la espera

Tomcat no informa de la desconexión del cliente mientras la petición espera: solo se detecta al
escribir la respuesta (sonda P3). Por eso, cuando el cliente cierra la conexión durante la espera del
retardo, la desconexión DEBE ser inocua: la espera DEBE terminar en su plazo, el permiso del semáforo
DEBE liberarse siempre en un bloque `finally`, el borde NO DEBE escribir ninguna respuesta con éxito
y el abandono NO DEBE registrarse como un error del servidor. El permiso NO DEBE liberarse antes de
que venza el retardo. Este requisito sustituye al punto 6 del contrato de la decisión 1 del diseño
archivado de `identity-module-and-password-authentication`, que prometía abandonar la espera y
liberar el permiso al instante; el propietario aceptó la sustitución el 2026-10-04 y el archivo
archivado no se edita.

#### Escenario: Cliente que se desconecta

- **DADO** una petición en espera de su retardo, controlado por un temporizador de prueba, y el
  semáforo con un permiso en uso
- **CUANDO** el cliente cierra la conexión y el temporizador de prueba libera la espera
- **ENTONCES** el permiso queda liberado al terminar la espera, verificado con un `CountDownLatch` y
  no con una espera por reloj, y no se registra ningún error

#### Escenario: El permiso no se libera antes del plazo

- **DADO** una petición en espera de su retardo cuyo cliente ya cerró la conexión
- **CUANDO** el temporizador de prueba todavía no ha liberado la espera
- **ENTONCES** el semáforo sigue con ese permiso en uso, y al liberarse la espera el permiso se
  devuelve aunque la escritura de la respuesta falle

#### Escenario: La desconexión no se registra como error

- **DADO** una espera terminada cuya escritura falla porque el cliente se desconectó
- **CUANDO** se inspecciona la respuesta y el registro del servidor
- **ENTONCES** no se escribió ninguna respuesta y no existe ninguna entrada de nivel `ERROR` por esa
  causa

### Requisito: La espera no retiene hilos de plataforma del servidor

La espera del retardo DEBE ejecutarse en un hilo virtual. Con más esperas simultáneas que hilos de
plataforma del servidor, una petición que no espera DEBE seguir atendiéndose. La sonda P3 del diseño
demostró esta propiedad bajo el servidor web real, y una prueba de integración la verifica en cada
construcción.

#### Escenario: Más esperas que hilos de plataforma

- **DADO** el servidor con `T` hilos de plataforma y `P` permisos de espera con `P` mayor que `T`
- **CUANDO** hay `P` esperas en curso y llega una petición sin retardo
- **ENTONCES** la petición sin retardo recibe su respuesta mientras las esperas continúan

#### Escenario: La espera corre en un hilo virtual

- **DADO** una espera en curso
- **CUANDO** se inspecciona el hilo que la ejecuta
- **ENTONCES** es un hilo virtual

### Requisito: Cabecera `Idempotency-Key` obligatoria en los endpoints que mueven dinero

Todo endpoint declarado como movimiento de dinero DEBE exigir la cabecera `Idempotency-Key`. La
ausencia o una cabecera en blanco DEBE rechazarse con `400` y el código `idempotency-key-missing`
antes de ejecutar ningún caso de uso. Una cabecera repetida, con longitud mayor que 128 caracteres o con
algún carácter fuera de ASCII visible (`0x21` a `0x7E`) DEBE rechazarse con `400` y el código
`validation-failed`. Un endpoint de solo lectura NO
DEBE exigir la cabecera.

#### Escenario: Cabecera ausente

- **DADO** un controlador de prueba declarado como movimiento de dinero
- **CUANDO** se envía la petición sin `Idempotency-Key`
- **ENTONCES** la respuesta es `400` con `idempotency-key-missing` y el caso de uso registra cero
  invocaciones

#### Escenario: Cabecera en blanco

- **DADO** la misma petición con `Idempotency-Key` vacía o con solo espacios
- **CUANDO** se envía
- **ENTONCES** la respuesta es `400` con `idempotency-key-missing`

#### Escenario: Cabecera repetida o demasiado larga

- **DADO** la petición con dos cabeceras `Idempotency-Key`, y otra con una clave de longitud máxima
  más uno
- **CUANDO** se envían
- **ENTONCES** ambas responden `400` con `validation-failed`, y una clave de longitud máxima exacta
  se acepta

#### Escenario: Carácter fuera de ASCII visible

- **DADO** la petición con una clave que contiene un espacio interior o un carácter fuera de ASCII
- **CUANDO** se envía
- **ENTONCES** responde `400` con `validation-failed` y el caso de uso registra cero invocaciones

#### Escenario: Endpoint de solo lectura

- **DADO** un controlador de prueba `GET` no declarado como movimiento de dinero
- **CUANDO** se envía sin `Idempotency-Key`
- **ENTONCES** responde sin error por esa causa

### Requisito: La repetición de una clave completada devuelve la respuesta original con `Idempotent-Replay`

Ante una clave ya completada con carga útil coincidente, el borde DEBE devolver el mismo estado y el
mismo cuerpo que la respuesta original, sin reejecutar el caso de uso, y DEBE añadir la cabecera
`Idempotent-Replay: true`. La respuesta de una ejecución real NO DEBE llevar esa cabecera. El borde
DEBE traducir las salidas de `IdempotentExecutor` sin modificar el contrato de este.

#### Escenario: Primera ejecución y repetición

- **DADO** un controlador de prueba con un contador de invocaciones y una clave nueva
- **CUANDO** se envían dos peticiones idénticas con la misma clave, una tras otra
- **ENTONCES** la primera responde sin `Idempotent-Replay`, la segunda responde con el mismo estado y
  el mismo cuerpo y con `Idempotent-Replay: true`, y el contador registra una sola invocación

#### Escenario: La repetición sobre una clave de otra institución no se mezcla

- **DADO** dos instituciones que usan el mismo valor de clave sobre el mismo endpoint
- **CUANDO** cada una envía su petición
- **ENTONCES** ambas ejecutan el caso de uso y ninguna recibe `Idempotent-Replay`

### Requisito: Una escritura concurrente con la misma clave responde `409`

Cuando una segunda petición con la misma clave coincide con una primera todavía en curso, el borde
DEBE responder `409` con el código `idempotency-conflict` si se agota la espera acotada del marcador
(`SQLState` `55P03`) o si el componente informa de un marcador en curso, sin ejecutar el caso de uso
de la segunda. Si la segunda choca con una clave que la primera ya confirmó (`SQLState` `23505`), el
borde NO DEBE responder `409`: `IdempotentExecutor` reproduce la respuesta del ganador y el borde DEBE
devolverla con `Idempotent-Replay: true`, sin ejecutar el caso de uso de la segunda. El borde
traduce las salidas del componente sin modificar su contrato. La primera petición NO DEBE verse
afectada. Cuando vence la espera y la primera ya confirmó, un reintento del cliente DEBE recibir la
repetición.

#### Escenario: Dos peticiones concurrentes con la misma clave

- **DADO** dos peticiones con la misma clave sincronizadas de forma determinista para que la primera
  siga abierta más allá de la espera acotada
- **CUANDO** la segunda espera
- **ENTONCES** la segunda recibe `409` con `idempotency-conflict`, el caso de uso se ejecutó una sola
  vez y la primera completa con normalidad

#### Escenario: Colisión con la clave ya confirmada

- **DADO** una primera petición que confirma dentro de la ventana de espera de la segunda
- **CUANDO** la segunda intenta escribir su marcador
- **ENTONCES** recibe el mismo estado y el mismo cuerpo que la respuesta original, con
  `Idempotent-Replay: true` y no `409`, sin ejecutar su caso de uso

#### Escenario: Reintento posterior

- **DADO** la segunda petición que recibió `409` por espera agotada mientras la primera seguía
  abierta, y la primera que confirmó después
- **CUANDO** el cliente repite la petición con la misma clave y la misma carga útil
- **ENTONCES** recibe la respuesta original con `Idempotent-Replay: true`

### Requisito: La misma clave con una carga útil distinta responde `422`

Cuando llega una petición con una clave ya registrada y una carga útil cuyo hash canonicalizado
difiere, el borde DEBE responder `422` con el código `idempotency-payload-mismatch`, sin ejecutar el
caso de uso.

#### Escenario: Carga útil distinta

- **DADO** una clave ya usada con la carga `A`
- **CUANDO** llega una petición con la misma clave y la carga `B`
- **ENTONCES** la respuesta es `422` con `idempotency-payload-mismatch` y el caso de uso registra cero
  invocaciones nuevas

#### Escenario: Carga equivalente con distinto orden de campos

- **DADO** una clave usada con una carga JSON `{"a":1,"b":2}`
- **CUANDO** llega una petición con la misma clave y `{"b":2,"a":1}`
- **ENTONCES** se trata como repetición válida y no como carga distinta

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

### Requisito: Ausencia de estado compartido del limitador entre procesos (brecha con destino: cambio 11)

El sistema NO DEBE incluir, en este cambio, ningún adaptador de Redis del limitador ni ningún
contador compartido entre procesos o réplicas. El adaptador de Redis implementa el mismo puerto
`RateLimiter` en el cambio 11 (`containerization-and-cicd-pipeline`). El llenado de la lista de
proxies de confianza con nginx y los rangos de Cloudflare, y las cabeceras de transporte del proxy,
son también del cambio 11.

#### Escenario: Un único adaptador y ninguna biblioteca de Redis

- **DADO** el código de producción y el camino de clases de `apps/api`
- **CUANDO** se busca una implementación de `RateLimiter` y un cliente de Redis
- **ENTONCES** existe una única implementación, la de memoria, y ningún cliente de Redis en el
  camino de clases

### Requisito: Ausencia de DTO de instituciones con tope de longitud (brecha con destino: primer endpoint de administración de instituciones)

El sistema NO DEBE incluir, en este cambio, ningún DTO de instituciones con los topes de 200 y 500
caracteres que prevé `docs/09-roadmap-y-fases.md`. Se entregan con el primer endpoint de
administración de instituciones.

#### Escenario: Ningún DTO de instituciones

- **DADO** el código de producción de `apps/api`
- **CUANDO** se buscan DTO de instituciones en paquetes `web`
- **ENTONCES** no existe ninguno, y este escenario deja de ser cierto cuando se entregue el primer
  endpoint de administración de instituciones
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

### Requisito: Toda respuesta `401` de las cadenas lleva `WWW-Authenticate`

Toda respuesta `401` DEBE incluir la cabecera `WWW-Authenticate` con el esquema `Bearer` (RFC 9110 y
RFC 6750), tanto si la produce la cadena administrativa, la cadena del portal, el traductor de errores o
la válvula de último recurso. Los valores DEBEN ser exactamente estos y ningún otro: para los códigos
`authentication-required`, `authentication-failed`, `second-factor-invalid` y `session-expired`, `Bearer`; para
`token-invalid` y para `token-expired`, `Bearer error="invalid_token"`. La cabecera NO DEBE llevar `realm` ni
`error_description`. Una respuesta distinta de `401` NO DEBE incluirla.
(Previously: solo `authentication-required`, `token-invalid` y `token-expired` existían.)

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

#### Escenario: [WE88] Los tres códigos `401` del flujo de sesión llevan `Bearer`

- **DADO** respuestas `401` con `authentication-failed`, `second-factor-invalid` y `session-expired`
- **CUANDO** se leen sus cabeceras
- **ENTONCES** el valor de `WWW-Authenticate` de las tres es exactamente `Bearer`

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
`GET /api/v1/auth/sessions/current` y `DELETE /api/v1/auth/sessions`, ambas con comprobación de la vigencia de
la sesión. Una petición con un principal autenticado a un método y ruta fuera de las tres clases de rutas DEBE
recibir `403` con `forbidden`. Una
prueba DEBE enumerar todas las rutas registradas y DEBE fallar si una ruta responde sin credencial y no
figura en la lista pública, si una ruta figura en la lista autenticada y responde sin credencial algo
distinto de `401`, o si una entrada de ambas listas no corresponde a una ruta registrada ni a una
condición de perfil declarada. El cambio 8 sustituye esta lista por la matriz de roles.
(Previously: la lista contenía exactamente `GET /api/v1/auth/sessions/current`.)

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

### Requisito: `Origin` es obligatorio en todo método no seguro de la cadena administrativa y se compara con una lista configurada

La cadena administrativa DEBE rechazar con `403` y el código `csrf-invalid` toda petición con método no seguro
(distinto de `GET`, `HEAD`, `OPTIONS` y `TRACE`) cuyo `Origin` falte, esté repetido o no figure en
`confia.web.allowed-origins`. La lista DEBE ser una propiedad por entorno, vacía por defecto (con la lista vacía
ninguna petición mutadora pasa), cuyas entradas DEBEN ser orígenes serializados `esquema://host[:puerto]`
válidos, en minúsculas, sin ruta, sin barra final, sin comodines, sin usuario y sin repetirse, con esquema
`https` salvo `http` con host `localhost` o `127.0.0.1` (la regla es la misma en todos los perfiles; no depende
del perfil `prod`). Una entrada inválida DEBE impedir el arranque con un mensaje que nombre la propiedad y la
posición de la entrada, sin repetir su valor. La comparación DEBE ser exacta, sin tratar `null` como permitido. El
rechazo NO DEBE distinguir la causa (falta, discrepancia u origen no permitido).

#### Escenario: [WE43] Sin `Origin` en un método no seguro

- **DADO** `allowed-origins` con `https://admin.colegio.edu.hn`
- **CUANDO** se envía `POST`, `PUT`, `PATCH` y `DELETE` a una ruta de la cadena sin cabecera `Origin`
- **ENTONCES** las cuatro responden `403` con `csrf-invalid` y el controlador registra cero invocaciones

#### Escenario: [WE44] `Origin` no permitido, parecido o repetido

- **DADO** la misma lista
- **CUANDO** se envía `Origin: https://evil.example`, `Origin: http://admin.colegio.edu.hn`,
  `Origin: https://admin.colegio.edu.hn.evil.example`, `Origin: null` y dos cabeceras `Origin`
- **ENTONCES** las cinco responden `403` con `csrf-invalid`, idénticas salvo `instance` y traza

#### Escenario: [WE45] `Origin` permitido y métodos seguros

- **DADO** la misma lista
- **CUANDO** se envía `POST` con `Origin: https://admin.colegio.edu.hn` y `GET` sin `Origin`
- **ENTONCES** ninguna se rechaza por esta causa

#### Escenario: [WE46] Lista vacía o inválida

- **DADO** `allowed-origins` vacía, y otras ejecuciones con `https://admin.colegio.edu.hn/ruta`, con
  `https://admin.colegio.edu.hn/`, con `*`, con `HTTPS://Admin.Colegio.edu.hn`, con
  `http://admin.colegio.edu.hn` (esquema `http` con un host que no es `localhost` ni `127.0.0.1`, en cualquier
  perfil), con `https://usuario@admin.colegio.edu.hn` y con la misma entrada repetida
- **CUANDO** se envía un `POST` con cualquier `Origin` con la lista vacía, y se arranca con cada entrada
  inválida
- **ENTONCES** el `POST` responde `403` con `csrf-invalid` y los siete arranques fallan con un mensaje que
  nombra la propiedad y la posición de la entrada, sin su valor
- **Y** `http://localhost:5173` se acepta


### Requisito: Las rutas de cookie exigen un token CSRF de doble envío vinculado al refresco con HMAC

El filtro DEBE aplicar estas reglas, en este orden, y la primera que falla decide con `403` y `csrf-invalid`:
(1) un método seguro (`GET`, `HEAD`, `OPTIONS`, `TRACE`) pasa sin comprobación; (2) todo método no seguro exige un
`Origin` permitido (requisito anterior); (3) una ruta de cookie **con** cookie de sesión exige exactamente una
cookie `__Host-confia_csrf`, exactamente una cabecera `X-CSRF-Token`, ambas con el mismo valor (comparación en
tiempo constante), y un HMAC válido vinculado al refresco de la cookie de sesión; (4) una ruta de cookie **sin**
cookie de sesión NO DEBE exigir token CSRF (no hay credencial ambiental que proteger) y pasa al controlador,
que responde `401 session-expired` en el refresco y `204` en el cierre actual; (5) una cookie de sesión repetida
(dos cookies con el mismo nombre) DEBE rechazarse en la regla 3 sin elegir ninguna. Se rechaza, por tanto, si
falta la cookie CSRF o la cabecera, si difieren, si el HMAC no valida o si el token está vinculado a otro
refresco, siempre que la petición traiga la cookie de sesión. El token DEBE tener la forma `<32 bytes aleatorios
en base64url>.<HMAC-SHA256(clave, aleatorio || SHA-256(refresco))>`, con el cálculo y la comparación en tiempo
constante dentro de `shared.security`. La verificación DEBE probar la clave actual y, si falla, la clave anterior
configurada (ver el requisito de la clave); la emisión usa siempre la actual. El token DEBE reemitirse con cada
emisión o rotación de la cookie de sesión, incluida la gracia, con el vínculo del refresco que se entrega. Las
demás rutas (`Bearer`) NO DEBEN exigir el token CSRF aunque el navegador envíe la cookie; el inicio de sesión
tampoco es ruta de cookie. La cookie CSRF DEBE llevar `Secure`, `Path=/`, `SameSite=Strict`, ningún
`Domain`, ningún `Max-Age` ni `Expires` y NO DEBE llevar `HttpOnly`, porque la SPA la lee. El rechazo NO DEBE
distinguir entre falta, discrepancia y origen no permitido.

#### Escenario: [WE47] Cookie sin cabecera `X-CSRF-Token`

- **DADO** una petición a una ruta de cookie con la cookie de sesión y la cookie CSRF válidas, `Origin` permitido
  y sin `X-CSRF-Token`
- **CUANDO** se envía
- **ENTONCES** responde `403` con `csrf-invalid` y el caso de uso registra cero invocaciones

#### Escenario: [WE48] Token CSRF de otra sesión

- **DADO** el token CSRF válido de la sesión A y la cookie de sesión de la sesión B
- **CUANDO** ambos se envían con el token en la cabecera y en la cookie CSRF
- **ENTONCES** responde `403` con `csrf-invalid`

#### Escenario: [WE49] Cabecera distinta de la cookie, HMAC alterado o aleatorio alterado

- **DADO** un token válido, y variantes con un bit alterado en el primer byte y en el último byte del HMAC, con
  el aleatorio alterado y con la cabecera distinta de la cookie CSRF
- **CUANDO** se envía cada una
- **ENTONCES** las cuatro responden `403` con `csrf-invalid` idénticas, y la comparación usa un método de
  tiempo constante

#### Escenario: [WE50] Token válido

- **DADO** la cookie de sesión y el token CSRF emitidos juntos, con `Origin` permitido
- **CUANDO** se envía el refresco con la cabecera igual a la cookie CSRF
- **ENTONCES** la petición llega al caso de uso

#### Escenario: [WE89] Una ruta de cookie sin cookie de sesión no exige token CSRF, pero sí `Origin`

- **DADO** el refresco y el cierre actual sin cookie de sesión, sin cookie CSRF y sin `X-CSRF-Token`
- **CUANDO** se envía cada uno con un `Origin` permitido, y por separado sin `Origin`
- **ENTONCES** con `Origin` permitido ambas llegan al controlador: el refresco responde `401 session-expired` y el
  cierre actual responde `204`, y no se responde `403`
- **Y** sin `Origin` ambas responden `403` con `csrf-invalid` y el controlador registra cero invocaciones

#### Escenario: [WE90] Cookie de sesión repetida, cookie CSRF repetida o cabecera repetida

- **DADO** una petición a una ruta de cookie con dos cookies de sesión, otra con dos cookies CSRF y otra con dos
  cabeceras `X-CSRF-Token`, todas con `Origin` permitido y, en cada una, un token CSRF válido para una de las
  cookies de sesión
- **CUANDO** se envían
- **ENTONCES** las tres responden `403` con `csrf-invalid` y el caso de uso registra cero invocaciones

#### Escenario: [WE91] Un token emitido con la clave anterior se acepta una vez y se reemite con la actual

- **DADO** una clave actual `K2` y una clave anterior `K1` configuradas, y un token CSRF emitido con `K1` para el
  refresco de la sesión
- **CUANDO** se envía el refresco con ese token en cabecera y cookie y `Origin` permitido
- **ENTONCES** la petición llega al caso de uso y la respuesta reemite la cookie CSRF calculada con `K2`
- **Y** un token emitido con una clave que no es `K1` ni `K2` responde `403` con `csrf-invalid`
- **Y** sin clave anterior configurada, un token emitido con `K1` responde `403` con `csrf-invalid`

#### Escenario: [WE51] Una ruta `Bearer` no exige el token CSRF

- **DADO** `DELETE /api/v1/auth/sessions` con un token de acceso vivo, `Origin` permitido, la cookie de sesión y
  sin `X-CSRF-Token`
- **CUANDO** se envía
- **ENTONCES** la petición no se rechaza por CSRF

#### Escenario: [WE52] Atributos de la cookie CSRF

- **DADO** una respuesta que emite la cookie CSRF
- **CUANDO** se inspecciona su `Set-Cookie`
- **ENTONCES** el nombre es `__Host-confia_csrf`, con `Secure`, `Path=/` y `SameSite=Strict`, sin `Domain`,
  `Max-Age`, `Expires` ni `HttpOnly`

### Requisito: Condición dura 3: ninguna cookie se emite antes del mecanismo CSRF y una prueba sobre el arnés lo demuestra

El mecanismo de `Origin` y CSRF DEBE existir, estar activo y probado antes de que ningún código de producción
emita `Set-Cookie`, incluido el borrado de cookies. Una prueba sobre un arnés de cookie DEBE demostrar que una
petición mutadora con la cookie y sin `X-CSRF-Token` recibe `403`, y la misma prueba DEBE repetirse contra las
rutas reales de refresco y de cierre actual cuando existan. El informe de archivo DEBE declarar cerrada la
condición con referencia a esas pruebas.

#### Escenario: [WE53] Con cookie y sin cabecera, `403`

- **DADO** el arnés de cookie con una cookie de sesión y una CSRF válidas
- **CUANDO** se envía una petición mutadora con cookie y sin `X-CSRF-Token`, otra con token CSRF de otra sesión
  y otra sin `Origin`
- **ENTONCES** las tres responden `403` con `csrf-invalid`

#### Escenario: [WE54] Las mismas tres pruebas contra las rutas reales

- **DADO** las rutas reales de refresco y de cierre actual, con la cookie de sesión presente en la petición
- **CUANDO** se repiten los tres casos
- **ENTONCES** los seis resultados son `403` con `csrf-invalid` y el caso de uso registra cero invocaciones

#### Escenario: [WE55] El arnés se desactiva y el mecanismo falla la prueba

- **DADO** una variante de control en la que el filtro de CSRF está deshabilitado
- **CUANDO** se ejecuta la prueba del arnés
- **ENTONCES** falla, de modo que no pase de vacío

#### Escenario: [WE56] Cierre de la condición 3 (solo documental)

- **DADO** el informe de archivo de este cambio y la nota fechada de `foundations-plan`
- **CUANDO** se leen
- **ENTONCES** declaran cerrada la condición 3 con referencia a [WE53] y [WE54], y el texto original de la
  condición no fue modificado

### Requisito: El filtro de CSRF y `Origin` va después del origen de la petición y antes del filtro de token y del limitador

La cadena administrativa DEBE construir, sin declararlo bean (como el filtro de autenticación), el filtro de CSRF
y `Origin` después del filtro del origen de la petición y antes del filtro de tokens y del limitador, de modo que
una petición rechazada por CSRF o `Origin` NO DEBE consumir cupo del limitador, ni autenticar, ni llegar al
controlador.

#### Escenario: [WE57] Orden de los filtros

- **DADO** la cadena administrativa
- **CUANDO** se inspecciona el orden
- **ENTONCES** es: cabeceras de seguridad, origen de la petición, CSRF y `Origin`, tokens, limitador,
  controlador

#### Escenario: [WE58] El rechazo no consume cupo ni verifica el token

- **DADO** una IP con 9 peticiones admitidas y un doble del verificador con contador
- **CUANDO** envía una petición con `Origin` no permitido y un token válido
- **ENTONCES** responde `403` con `csrf-invalid`, el cupo sigue en 9 y el verificador registra cero invocaciones

### Requisito: La clave CSRF es un secreto de entorno exclusivo del proceso administrativo

El sistema DEBE leer la clave del HMAC de CSRF de la propiedad `confia.security.csrf.key`, inyectable por
variable de entorno, en Base64 estándar de al menos 32 bytes, y DEBE aceptar una clave anterior opcional en
`confia.security.csrf.previous-key`, usada solo para verificar. El proceso administrativo DEBE fallar al arrancar
si la clave falta, es más corta que 32 bytes o no es Base64 válido, si la clave anterior es inválida, o si la
clave anterior es igual a la actual, con un mensaje que nombre la propiedad y NO repita el valor. Los procesos
del portal y del trabajador NO DEBEN tener ninguna de las dos claves ni el componente que las usa, y DEBEN
abortar si cualquiera de las dos propiedades aparece. Ningún valor de las claves DEBE versionarse. Rotar la clave
(moviendo la actual a la anterior) NO invalida las sesiones vigentes: el siguiente refresco verifica su token CSRF
con la clave anterior y lo reemite con la actual; la clave anterior se retira pasada la vida absoluta de una
sesión (12 horas).

#### Escenario: [WE59] Administración sin clave o con clave corta no arranca

- **DADO** el proceso administrativo sin `confia.security.csrf.key`, otro con una clave de 16 bytes, otro con una
  clave que no es Base64 válido y otro con una clave anterior igual a la actual
- **CUANDO** arranca cada uno
- **ENTONCES** los cuatro fallan con un mensaje que nombra la propiedad y no contiene el valor

#### Escenario: [WE60] El portal y el trabajador no la tienen

- **DADO** los procesos del portal y del trabajador con y sin `confia.security.csrf.key` ni
  `confia.security.csrf.previous-key` en su entorno
- **CUANDO** se construye cada contexto
- **ENTONCES** sin las propiedades arrancan sin ningún componente de CSRF, y con cualquiera de las dos abortan
  nombrando la propiedad sin su valor

#### Escenario: [WE61] Ninguna clave CSRF versionada

- **DADO** el repositorio con todas sus pruebas y recursos
- **CUANDO** se ejecuta el escaneo de secretos
- **ENTONCES** no encuentra ningún valor para `confia.security.csrf.key`

### Requisito: Las cookies tienen atributos fijos, siempre `Secure` y con prefijo, y se emiten y se borran desde un único ayudante

El sistema DEBE emitir la cookie de sesión `__Secure-confia_admin_sid` con `HttpOnly`, `Secure`,
`SameSite=Strict`, `Path=/api/v1/auth/sessions`, ningún `Domain` y ningún `Max-Age` ni `Expires` (cookie de
sesión del navegador, DA-2 *a*), con el texto del refresco como valor. Para borrarla DEBE emitir el mismo nombre
con los mismos atributos y `Max-Age=0`; igual para la cookie CSRF. La emisión y el borrado DEBEN vivir en un
único ayudante de `shared.web` (`SessionCookies`), la única clase de producción que usa
`jakarta.servlet.http.Cookie`. Las dos cookies DEBEN ser **siempre** `Secure` y conservar su prefijo
(`__Secure-` y `__Host-`) en todos los perfiles, incluido el local: NO DEBE existir ningún indicador ni
propiedad de configuración que desactive `Secure` ni que cambie los nombres de las cookies. El desarrollo local
usa un navegador que trate `http://localhost` como origen de confianza, o HTTPS local. Ninguna respuesta de la cadena administrativa distinta de las de inicio de sesión, verificación de segundo factor,
refresco y cierres DEBE llevar `Set-Cookie`.

#### Escenario: [WE62] Atributos de la cookie de sesión

- **DADO** una respuesta de inicio de sesión con éxito
- **CUANDO** se inspecciona el `Set-Cookie` de sesión
- **ENTONCES** el nombre es `__Secure-confia_admin_sid`, con `HttpOnly`, `Secure`, `SameSite=Strict` y
  `Path=/api/v1/auth/sessions`, sin `Domain`, `Max-Age` ni `Expires`
- **Y** el valor tiene 43 caracteres base64url

#### Escenario: [WE63] Borrado de las dos cookies

- **DADO** un cierre de sesión o un refresco rechazado
- **CUANDO** se inspeccionan los `Set-Cookie`
- **ENTONCES** hay dos, uno por cookie, con valor vacío, el mismo nombre y los mismos atributos (`Path`,
  `Secure`, `HttpOnly` en la de sesión, `SameSite=Strict`) y `Max-Age=0`

#### Escenario: [WE64] Las cookies son `Secure` y con prefijo en todos los perfiles

- **DADO** el proceso administrativo arrancado con los perfiles `local`, `preprod` y `prod`
- **CUANDO** se inspeccionan los `Set-Cookie` de un inicio de sesión con éxito en cada perfil
- **ENTONCES** en los tres las dos cookies llevan `Secure` y los nombres `__Secure-confia_admin_sid` y
  `__Host-confia_csrf`
- **Y** el conjunto de propiedades de configuración del ayudante de cookies no contiene ninguna que desactive
  `Secure` ni que cambie los nombres (una prueba lo comprueba y falla ante un fixture que la añade)

#### Escenario: [WE65] Ninguna otra respuesta emite cookies

- **DADO** respuestas de rutas públicas de documentación, denegadas, de `GET /api/v1/auth/sessions/current` y
  de errores `401`
- **CUANDO** se inspeccionan
- **ENTONCES** ninguna contiene `Set-Cookie` ni crea sesión HTTP

### Requisito: Las rutas restringidas aceptan solo un token restringido de su uso y su principal es de otro tipo que `AuthenticatedActor`

La cadena administrativa DEBE declarar una clase de ruta restringida, distinta de las rutas autenticadas y de las
públicas, con exactamente `POST /api/v1/auth/mfa/verifications` (usos `mfa-verify` y `mfa-enroll`) y `POST /api/v1/auth/mfa/enrollments`
(uso `mfa-enroll`). El filtro admite el uso por ruta y no consulta el estado de la credencial: qué pruebas acepta
cada uso, y el rechazo `second-factor-invalid` de un `mfa-enroll` con la credencial ya verificada, lo decide el
caso de uso (`identity`). En esas rutas el filtro
DEBE verificar el token con el verificador restringido y establecer un principal restringido de tipo y
`ScopedValue` distintos de `AuthenticatedActor`, con el identificador de la cuenta, la institución, el uso, el
identificador del token y su vencimiento. Un token restringido NO DEBE poblar nunca `AuthenticatedActor.current()`
ni ser visto por `TokenCurrentInstitutionProvider` ni por `SessionValidity`. Un token de acceso completo
presentado a una ruta restringida DEBE rechazarse con `401 token-invalid`; un token restringido presentado a
cualquier otra ruta, también.

#### Escenario: [WE66] El token restringido no puebla el actor de sesión

- **DADO** un controlador de prueba en una ruta restringida que lee `AuthenticatedActor.current()` y el
  principal restringido
- **CUANDO** se envía un token restringido válido
- **ENTONCES** el principal restringido está presente y `AuthenticatedActor.current()` está vacío

#### Escenario: [WE67] Cada token abre solo lo suyo

- **DADO** un token de acceso, un `mfa-verify` y un `mfa-enroll` (cualquiera que sea el estado de la credencial)
- **CUANDO** se presenta cada uno a `GET /api/v1/auth/sessions/current`, a las verificaciones y a las
  inscripciones
- **ENTONCES** el acceso solo abre la sesión actual y «cerrar todas»; `mfa-verify` solo abre las verificaciones;
  `mfa-enroll` abre las inscripciones y las verificaciones (el filtro lo admite aunque la credencial ya esté
  verificada; ese caso lo rechaza después el caso de uso con `second-factor-invalid`); el resto responde
  `401 token-invalid`

#### Escenario: [WE68] Una ruta restringida nueva sin editar la lista rompe la prueba

- **DADO** un controlador de prueba que acepta un token restringido sin figurar en la clase restringida
- **CUANDO** se ejecuta la prueba que enumera las rutas
- **ENTONCES** falla señalando la ruta

#### Escenario: [WE69] Un token restringido no autoriza nada fuera de su ruta

- **DADO** un token `mfa-verify` vigente
- **CUANDO** se presenta a `DELETE /api/v1/auth/sessions` y a una ruta no registrada
- **ENTONCES** ambas responden `401 token-invalid` y ningún controlador se invoca

### Requisito: Existen tres políticas de límite de tasa configuradas por `confia.web.rate-limit.<política>.*`

El sistema DEBE generalizar la configuración del limitador al prefijo `confia.web.rate-limit.<política>.*` y
registrar tres políticas con el mismo puerto `RateLimiter`, con estos valores por defecto (declarados en
`application.yml`, no en anotaciones):

| Política | Peticiones por IP | Umbral de fallos (capa 2) | Rutas |
|---|---|---|---|
| `admin-login` | 10 por minuto | 10 fallos en 10 minutos | `POST /api/v1/auth/sessions` |
| `admin-mfa` | 20 por minuto | 10 fallos en 10 minutos | verificación e inscripción del segundo factor |
| `admin-session` | 120 por minuto | 30 fallos en 10 minutos | refresco, cierre actual y cerrar todas |

En las tres, la capa 2, una vez activada, admite 1 intento por minuto con un tope de 1 hora (los valores de
`admin-login` no cambian). Los fallos de `admin-login` son los desenlaces `Rejected`; los de `admin-mfa`, los
`second-factor-invalid`; los de `admin-session`, los rechazos del refresco (`session-expired`, incluida la
petición sin cookie de sesión). Los cierres no registran fallos. Cada política DEBE tener su propio contador por
IP. La configuración DEBE vivir bajo `confia.web.rate-limit.<política>.*` con un componente por política; un
componente ausente o desconocido DEBE impedir el arranque con un mensaje que nombre la propiedad. Los valores
DEBEN poder ajustarse sin recompilar y un valor no positivo DEBE impedir el arranque con un mensaje que nombre la
propiedad completa. El rechazo DEBE responder `429` con `too-many-requests` y `Retry-After`.

#### Escenario: [WE70] Valores por defecto de las tres políticas

- **DADO** el proceso arrancado sin sobrescribir las políticas
- **CUANDO** se consultan los valores efectivos
- **ENTONCES** `admin-login` es 10 por minuto con umbral de 10 fallos en 10 minutos, `admin-mfa` es 20 por
  minuto con umbral de 10 fallos en 10 minutos y `admin-session` es 120 por minuto con umbral de 30 fallos en 10
  minutos
- **Y** en las tres, la capa 2 admite 1 intento por minuto con un tope de 1 hora

#### Escenario: [WE71] Los contadores de las políticas son independientes

- **DADO** una IP con su cupo de `admin-login` agotado
- **CUANDO** envía un refresco y una verificación del segundo factor
- **ENTONCES** ambos se admiten, porque cada política tiene su propio contador

#### Escenario: [WE72] El décimo fallo del segundo factor activa la restricción de `admin-mfa`

- **DADO** una IP con 9 `second-factor-invalid` en 10 minutos
- **CUANDO** el décimo fallo se registra y la IP envía otra verificación a los 30 segundos
- **ENTONCES** recibe `429` con `Retry-After` de 30 segundos

#### Escenario: [WE92] El trigésimo rechazo del refresco activa la restricción de `admin-session`

- **DADO** una IP con 29 rechazos del refresco (`session-expired`) en 10 minutos, sin agotar la capa 1
- **CUANDO** se registra el trigésimo rechazo y la IP envía otro refresco a los 30 segundos
- **ENTONCES** recibe `429` con `Retry-After`
- **Y** con 29 rechazos y un refresco correcto, la IP no está restringida
- **Y** los cierres (`204`) no cuentan como fallos

#### Escenario: [WE93] Un componente de política ausente o desconocido detiene el arranque

- **DADO** la configuración sin el componente `admin-session`, y otra con un componente `admin-otro`
- **CUANDO** arranca el proceso
- **ENTONCES** los dos arranques fallan con un mensaje que nombra la propiedad

#### Escenario: [WE73] Valor no positivo en una política nueva

- **DADO** `confia.web.rate-limit.admin-session.request-limit` en 0
- **CUANDO** arranca el proceso
- **ENTONCES** el arranque falla con un mensaje que nombra esa propiedad

> **Corrección del 2026-10-24 (`verify-report.md`, W3).** La propiedad real se llama `request-limit`; el texto decía `requests`, nombre que no existe en `application.yml` ni en `RateLimitPropertiesTest`.

#### Escenario: [WE74] Cada ruta aplica su política

- **DADO** las seis rutas de sesión
- **CUANDO** se inspeccionan sus políticas
- **ENTONCES** el inicio de sesión usa `admin-login`; las verificaciones y las inscripciones, `admin-mfa`; y el
  refresco y los dos cierres, `admin-session`; `GET /api/v1/auth/sessions/current` no usa ninguna

### Requisito: La espera materializada del retardo tiene un tope configurable

El materializador DEBE acotar la espera materializada de un `Delayed` a un tope configurable por
`confia.web.delay.max-wait`, positivo y como máximo de 60 segundos, con valor de partida de 30 segundos. El
tope vive en el materializador (el único que espera); el retardo que lleva `Delayed` no cambia. El contador de retroceso y la
auditoría DEBEN conservar el retardo que calculó la política (hasta 900 segundos); solo se acota la espera de la
respuesta. Un tope no positivo o mayor que 60 segundos DEBE impedir el arranque con un mensaje que nombre la propiedad. El retardo menor
que el tope se materializa completo.

#### Escenario: [WE75] Un retardo de 900 segundos espera el tope

- **DADO** un caso de uso que devuelve un retardo de 900 segundos y un tope de 30 segundos
- **CUANDO** el borde lo procesa con el temporizador de prueba
- **ENTONCES** la espera solicitada es de 30 segundos
- **Y** el contador y el asiento de auditoría registran 900 segundos

#### Escenario: [WE76] Un retardo menor que el tope no se acorta

- **DADO** un retardo de 2 segundos y el tope de 30 segundos
- **CUANDO** el borde lo procesa
- **ENTONCES** la espera solicitada es de 2 segundos

#### Escenario: [WE77] Tope no válido

- **DADO** el tope en 0, en un valor negativo o en 61 segundos
- **CUANDO** arranca el proceso
- **ENTONCES** el arranque falla con un mensaje que nombra la propiedad

### Requisito: Las propiedades desconocidas del cuerpo JSON se rechazan con `validation-failed`

La deserialización de cuerpos JSON de la cadena administrativa DEBE rechazar toda propiedad desconocida con `400`
y `validation-failed`, sin repetir el nombre ni el valor rechazado. Una sonda con Jackson 3 y Spring Boot 4 DEBE
confirmar la configuración, y una prueba en rojo DEBE existir antes del controlador de inicio de sesión.

#### Escenario: [WE78] Propiedad desconocida

- **DADO** un endpoint de prueba con un cuerpo de un campo y una petición con un campo adicional `x` con el valor
  `VALOR-SENSIBLE-123`
- **CUANDO** se envía
- **ENTONCES** responde `400` con `validation-failed` y el texto no aparece en el cuerpo ni en las cabeceras

### Requisito: Las rutas públicas y las listas de rutas autenticadas y restringidas son cerradas y una prueba las hace cumplir

La lista blanca pública DEBE contener, además de la documentación de springdoc, `POST /api/v1/auth/sessions`
(inicio de sesión), `POST /api/v1/auth/sessions/refresh` y `DELETE /api/v1/auth/sessions/current` (estas dos se
autentican por cookie y CSRF dentro del borde, no por `Bearer`). La lista cerrada de rutas autenticadas DEBE
contener exactamente `GET /api/v1/auth/sessions/current` y `DELETE /api/v1/auth/sessions`, ambas con comprobación
de la vigencia de la sesión. La clase restringida DEBE contener exactamente las dos rutas de MFA. Ninguna ruta
fuera de las tres clases DEBE responder sin credencial, y la prueba de enumeración DEBE distinguir las tres
clases y fallar si una ruta no figura o si una entrada no corresponde a una ruta registrada.

#### Escenario: [WE79] Contenido exacto de las tres clases

- **DADO** las listas tal como las entrega este cambio
- **CUANDO** se enumeran
- **ENTONCES** la pública añade las tres rutas, la autenticada tiene dos entradas con comprobación de sesión y
  la restringida tiene dos

#### Escenario: [WE80] Una ruta de la lista pública que exige credencial rota falla la prueba

- **DADO** una ruta pública de sesión configurada, a propósito, para responder `200` a un método no declarado
- **CUANDO** se ejecuta la prueba de enumeración
- **ENTONCES** falla señalando la ruta

#### Escenario: [WE81] Otro método sobre una ruta de sesión no se sirve

- **DADO** `GET`, `PUT` y `PATCH` sobre `/api/v1/auth/sessions`, `/api/v1/auth/sessions/refresh` y
  `/api/v1/auth/mfa/verifications`
- **CUANDO** se envían sin credencial
- **ENTONCES** ninguna responde `2xx` y el controlador registra cero invocaciones

### Requisito: Los cinco códigos de Problem Details del flujo de sesión existen con su estado y su texto es-HN

El catálogo DEBE incluir `authentication-failed` (401), `second-factor-invalid` (401), `session-expired` (401),
`csrf-invalid` (403) y `mfa-already-enrolled` (409), cada uno con su entrada es-HN. Los tres `401` DEBEN llevar
`WWW-Authenticate: Bearer`. Los textos NO DEBEN revelar la causa del rechazo: `authentication-failed` no
distingue cuenta de contraseña, `csrf-invalid` no distingue falta de discrepancia de origen, y
`session-expired` no distingue desconocido de vencido ni de reutilizado.

#### Escenario: [WE82] Cada código tiene su estado

- **DADO** un productor de prueba para cada uno de los cinco códigos
- **CUANDO** lanza su error en el borde
- **ENTONCES** el estado es 401, 401, 401, 403 y 409 respectivamente y el `type` corresponde a cada código

#### Escenario: [WE83] Los textos no nombran la causa

- **DADO** los `detail` es-HN de los cinco códigos
- **CUANDO** se leen
- **ENTONCES** ninguno contiene «contraseña incorrecta», «no existe», «origen», «reutilizado» ni «token CSRF»

#### Escenario: [WE84] El catálogo exige los cinco y `institution-*` sigue sin existir

- **DADO** `ProblemCatalogCoverageTest` y `ProblemCodeTest` actualizados
- **CUANDO** se ejecutan con el catálogo real, con uno sin la entrada es-HN de `csrf-invalid` y con uno que
  añade `institution-not-found`
- **ENTONCES** pasa con el real y falla en los otros dos

### Requisito: Ausencia de recuperación de contraseña por HTTP (brecha con destino: `password-recovery-endpoints`)

El sistema NO DEBE incluir, en este cambio, ningún endpoint de solicitud ni de confirmación de recuperación de
contraseña, ni los códigos `institution-not-found` o `institution-inactive`. Las condiciones duras H3 y H4 obligan
a **`password-recovery-endpoints`**. Este cambio cierra H1 y la condición 3; H2 se cerró en
`session-tokens-and-web-layer`.

#### Escenario: [WE85] Las rutas de producción son exactamente las siete de sesión

- **DADO** el proceso administrativo arrancado por su camino de producción
- **CUANDO** se enumeran las rutas registradas por controladores de producción
- **ENTONCES** el conjunto es exactamente `GET /api/v1/auth/sessions/current`, `POST /api/v1/auth/sessions`,
  `DELETE /api/v1/auth/sessions`, `POST /api/v1/auth/sessions/refresh`,
  `DELETE /api/v1/auth/sessions/current`, `POST /api/v1/auth/mfa/verifications` y
  `POST /api/v1/auth/mfa/enrollments`
- **Y** toda petición a otra ruta de aplicación sin credencial recibe `401`

#### Escenario: [WE86] Cierre de H1 (solo documental)

- **DADO** el informe de archivo de este cambio y la nota fechada de `foundations-plan`
- **CUANDO** se leen
- **ENTONCES** declaran cerrada H1 con referencia a [I160] y [I161], H3 y H4 abiertas con dueño
  `password-recovery-endpoints`, y el texto original de las condiciones no fue modificado

### Requisito: Un token de acceso completo autentica las rutas autenticadas; el restringido solo abre las rutas restringidas de su uso, y los de otros dominios se rechazan en toda ruta

La cadena administrativa DEBE rechazar con `401` y el código `token-invalid` todo token restringido de
MFA presentado a una ruta que no sea una ruta restringida de su uso, y todo token firmado por otro dominio de
identidad o con audiencia distinta de la administrativa. El rechazo del token restringido DEBE producirse por su
audiencia (`confia-admin-mfa`, distinta de `confia-admin`) en las rutas autenticadas, sin depender de que se
consulte su claim de uso; en las rutas restringidas se verifica con el verificador restringido y su uso. El
rechazo NO DEBE depender de que la ruta exista ni de que esté en alguna de las listas, y NO DEBE evaluar ningún
permiso.

#### Escenario: [WE09] El token restringido no abre ninguna ruta autenticada

- **DADO** un token restringido de MFA vigente, con uso `mfa-verify`
- **CUANDO** se presenta a una ruta de la lista autenticada, a una ruta registrada fuera de las listas
  y a una ruta no registrada
- **ENTONCES** las tres respuestas son `401` con el código `token-invalid`
- **Y** ningún controlador registra invocaciones
- **Y** el rechazo se debe a la audiencia `confia-admin-mfa`, no al uso: un token con la audiencia
  administrativa y la claim de uso `mfa-verify` es rechazado igualmente por la lista cerrada de claims

#### Escenario: [WE10] El token del portal se rechaza en rutas autenticadas y restringidas

- **DADO** un token vigente firmado con la clave del portal y con `aud` igual a `confia-portal`
- **CUANDO** se presenta a una ruta de la lista autenticada y a una restringida
- **ENTONCES** ambas responden `401` con el código `token-invalid` y no se evalúa ningún permiso


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
toda petición que no esté en la lista blanca pública. Una petición sin credencial a una ruta fuera
de la lista DEBE recibir `401` con el código `authentication-required`; una petición con un principal autenticado a una ruta fuera de la
lista DEBE recibir `403` con el código `forbidden`, porque ninguna ruta concede permisos todavía
(la matriz de roles es del cambio 8). Toda denegación DEBE responder con Problem Details. La
decisión NO DEBE depender de que la ruta exista: una ruta no registrada y una ruta registrada fuera
de la lista DEBEN recibir la misma respuesta, para no revelar qué rutas existen.

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

- **DADO** una ruta registrada fuera de la lista y una ruta no registrada
- **CUANDO** se envía la misma petición sin credencial a ambas
- **ENTONCES** el estado, el `type`, el `title`, el `detail` y las cabeceras de seguridad son
  idénticos; solo `instance` puede diferir

#### Escenario: Principal autenticado sin permiso

- **DADO** un principal autenticado inyectado por la prueba y una ruta fuera de la lista blanca
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

La cadena administrativa NO DEBE crear sesión HTTP ni enviar cookies en ninguna respuesta,
incluidas las respuestas de error.

#### Escenario: Ninguna respuesta crea sesión

- **DADO** cualquier petición a una ruta pública, a una ruta denegada y a una ruta de prueba que
  responde con éxito
- **CUANDO** se inspeccionan las tres respuestas
- **ENTONCES** ninguna contiene la cabecera `Set-Cookie` y el contenedor no registra ninguna sesión
  HTTP creada

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

### Requisito: La vigencia de sesión es un puerto de `shared.security` sin implementación todavía

El sistema DEBE declarar la vigencia de sesión como un puerto en `com.confia.shared.security`, de
modo que `shared` no dependa de `identity`. Este cambio NO DEBE entregar ninguna implementación de
producción del puerto ni ningún bean que lo implemente; una prueba de inventario DEBE declararlo
como puerto sin adaptador, con el mismo precedente de las partes anteriores del cambio 7. La
implementación por `identity` es de `session-tokens-and-web-layer` (brecha con destino:
`session-tokens-and-web-layer`, corte C5b).

#### Escenario: El puerto existe y no tiene adaptador de producción

- **DADO** el código de producción de `apps/api`
- **CUANDO** se ejecuta la prueba de inventario de puertos sin adaptador
- **ENTONCES** el puerto de vigencia de sesión figura en el inventario con su dueño, y ninguna clase
  de producción lo implementa

#### Escenario: El puerto no arrastra a `identity`

- **DADO** el puerto en `com.confia.shared.security`
- **CUANDO** se ejecutan las reglas de dependencia de `build-integrity`
- **ENTONCES** el puerto no depende de ninguna clase de `com.confia.identity`

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
`authentication-required`, y por tanto un único `type`, sin distinguir la causa (los códigos de autenticación propiamente
dichos son de `session-tokens-and-web-layer`).

#### Escenario: El mismo código produce el mismo `type`

- **DADO** dos excepciones de dominio con el mismo código estable y mensajes internos distintos
- **CUANDO** el traductor las convierte en respuesta
- **ENTONCES** ambas respuestas tienen el mismo `type`

#### Escenario: Códigos distintos producen `type` distintos

- **DADO** dos excepciones de dominio con códigos estables distintos
- **CUANDO** el traductor las convierte en respuesta
- **ENTONCES** los `type` difieren

#### Escenario: La denegación por falta de credencial es uniforme

- **DADO** peticiones sin credencial a tres rutas distintas, una con cabecera `Authorization`
  malformada y otra sin cabecera
- **CUANDO** se comparan las respuestas
- **ENTONCES** todas tienen el mismo estado, `type`, `title` y `detail`, y el `type` deriva del
  código `authentication-required`

### Requisito: Catálogo de códigos de error y estados HTTP con productor en este cambio

El traductor DEBE producir, para cada código del subconjunto con productor en este cambio, el estado
HTTP siguiente: `validation-failed` con `400`; `idempotency-key-missing` con `400`;
`authentication-required` con `401`; `forbidden` con `403`; `resource-not-found` con `404`;
`idempotency-conflict` con `409`; `unsupported-media-type` con `415`;
`idempotency-payload-mismatch` con `422`; `too-many-requests` con `429` y `Retry-After`;
`capacity-exceeded` con `503`; `internal-error` con `500`. `resource-not-found` solo tiene productor
bajo los prefijos públicos de springdoc, con los perfiles `local` y `preprod`. Los códigos de
autenticación propios del inicio de sesión (`authentication-failed`, `token-invalid`,
`token-expired`) y de institución (`institution-not-found`, `institution-inactive`) NO DEBEN existir
en este cambio (brecha con destino: `session-tokens-and-web-layer`); `authentication-required` es el
único código de autenticación, reservado a la denegación uniforme de la cadena.

#### Escenario: Cada código tiene su estado

- **DADO** un productor de prueba para cada uno de los once códigos
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

#### Escenario: Los códigos de autenticación aún no existen

- **DADO** el catálogo de códigos de error entregado
- **CUANDO** se enumeran sus entradas
- **ENTONCES** no contiene `authentication-failed`, `token-invalid`, `token-expired`,
  `institution-not-found` ni `institution-inactive`, y sí contiene `authentication-required`

### Requisito: Las respuestas de error no exponen detalles internos

Ninguna respuesta de error DEBE contener traza de pila, nombre de clase, nombre de paquete, texto de
SQL, mensaje de una excepción interna ni valores de configuración. Una excepción no prevista DEBE
traducirse a `500` con el código `internal-error` y un `detail` del catálogo, y el detalle técnico
DEBE quedar solo en el registro del servidor con el identificador de petición. El registro NO DEBE
contener los datos prohibidos por `CLAUDE.md`, regla 11.

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

### Requisito: Un fallo de validación produce `validation-failed` sin repetir los valores rechazados

Un fallo de validación de Jakarta Bean Validation, un cuerpo mal formado y un tipo de contenido no
admitido DEBEN responder `400` (el último, `415` con el código `unsupported-media-type`) con Problem
Details; el fallo de validación DEBE usar el código `validation-failed` y listar en el arreglo
`errors` el campo y el motivo de cada violación. `errors` es un miembro de extensión de RFC 9457 que
el esquema `ProblemDetail` del contrato OpenAPI no declara en este cambio, porque la instantánea del
documento queda congelada; lo declara el primer endpoint de producción con validación
(`session-tokens-and-web-layer`). La respuesta NO DEBE repetir el valor rechazado, porque puede ser
una contraseña, un documento de identidad o un dato de un menor.

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

#### Escenario: `errors` es una extensión fuera del esquema del contrato

- **DADO** un fallo de validación de un campo y la instantánea aprobada del documento OpenAPI
- **CUANDO** se lee la respuesta y se ejecuta `OpenApiContractSnapshotTest`
- **ENTONCES** el cuerpo contiene `errors` con `field` y `reason`, y el esquema `ProblemDetail` de la
  instantánea sigue sin declarar `errors`

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
propagación del origen a los comandos de identidad es de `session-tokens-and-web-layer` (brecha con
destino: `session-tokens-and-web-layer`).

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

La idempotencia HTTP DEBE demostrarse con un controlador que exista únicamente en el árbol de
pruebas. Ningún controlador de producción NI ninguna ruta de producción DEBE usar el mecanismo en
este cambio, y el documento OpenAPI de cada superficie NO DEBE contener la ruta de demostración.

#### Escenario: El controlador de demostración no está en los procesos reales

- **DADO** los contextos administrativo y del portal arrancados por su camino de producción
- **CUANDO** se inspeccionan sus rutas registradas
- **ENTONCES** ninguna es la ruta de demostración

#### Escenario: El documento OpenAPI no cambia

- **DADO** la instantánea aprobada del documento administrativo
- **CUANDO** se ejecuta `OpenApiContractSnapshotTest`
- **ENTONCES** el documento generado sigue coincidiendo, sin ninguna operación nueva

### Requisito: Ausencia de autenticación por credencial, tokens y endpoints de identidad (brecha con destino: `session-tokens-and-web-layer`)

El sistema NO DEBE incluir, en este cambio, ningún filtro de autenticación por credencial, emisor ni
verificador de tokens, anillo de claves, token restringido de MFA, cookie de sesión, protección
CSRF de doble envío, familia de refresco, adaptador de `CurrentInstitutionProvider` sobre un token,
ni endpoint de inicio de sesión, MFA, refresco, cierre de sesión o recuperación. Tampoco DEBE aplicar
el limitador ni el materializador del retardo a ningún endpoint de producción. Las condiciones
duras H1, H2, H3 y H4 NO se cierran aquí: este cambio entrega el control y su aplicación se cierra en
`session-tokens-and-web-layer` (cortes C4a a C7b). Los códigos de Problem Details de autenticación propios del inicio de
sesión (el código `authentication-required` de la denegación uniforme sí existe aquí) y de
institución, y la propagación del origen de la petición a los comandos de identidad, son también
de `session-tokens-and-web-layer`.

#### Escenario: Ninguna ruta de producción en el proceso administrativo

- **DADO** el proceso administrativo arrancado por su camino de producción
- **CUANDO** se enumeran las rutas registradas por controladores de producción
- **ENTONCES** el conjunto es vacío y toda petición a una ruta de aplicación recibe `401`

#### Escenario: Ningún componente de tokens ni de sesión

- **DADO** el código de producción de `apps/api`
- **CUANDO** se busca un emisor o verificador de tokens, una cookie de sesión o un filtro de
  autenticación por credencial
- **ENTONCES** no existe ninguno, y este escenario deja de ser cierto cuando
  `session-tokens-and-web-layer` entregue su primer corte

#### Escenario: El limitador y el materializador no están aplicados a un endpoint de producción

- **DADO** el código de producción de `apps/api`
- **CUANDO** se inspeccionan los usos del limitador y del materializador
- **ENTONCES** solo se usan desde el árbol de pruebas, y H1 queda abierta con destino al corte C6a

### Requisito: Ausencia de matriz de roles, cobertura de MFA por ruta, claim de permisos y guarda de arranque de RBAC (brecha con destino: cambio 8)

El sistema NO DEBE incluir, en este cambio, ninguna matriz de roles, ninguna cobertura de MFA por
ruta, ningún claim de permisos ni ninguna guarda de arranque de RBAC. La denegación por defecto con
lista blanca explícita es lo único que se entrega de RBAC (decisión D4). El cambio 8
(`rbac-permission-matrix-and-audit-integration`) los entrega.

#### Escenario: Ninguna regla de permiso sobre una ruta

- **DADO** la cadena administrativa tal como la entrega este cambio
- **CUANDO** se inspecciona su configuración de autorización
- **ENTONCES** contiene solo la lista blanca pública y la regla final de denegación, sin ninguna regla
  por rol, permiso o cobertura de MFA

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

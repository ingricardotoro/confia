# Delta para Borde web del proceso administrativo

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `dockerfiles-and-local-compose` (F0, cambio 11a)
- **Propuesta:** `proposal.md`, aprobada por el propietario el 2026-11-09

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `WE94` en adelante, a continuación del máximo de la especificación vigente
  (`WE93`). Los escenarios vigentes de los requisitos modificados se conservan sin cambio de texto salvo los que
  se indican; los que se conservan no llevan número o conservan el suyo.
- La semántica de las sondas (qué responden y cuándo) vive en `container-runtime`; este delta fija solo su lugar
  en la lista blanca pública y su excepción al formato de error.
- Las dos rutas de salud son `GET /health/live` y `GET /health/ready`; ninguna otra ruta de Actuator es pública.

## MODIFIED Requirements

### Requisito: La lista blanca pública es cerrada y una prueba la hace cumplir

La lista blanca pública del proceso administrativo DEBE ser un valor cerrado, definido en un único
lugar. Contiene exactamente las rutas `GET /health/live` y `GET /health/ready`, además de las entradas de
documentación condicionadas por perfil y de las rutas públicas de sesión que otros requisitos de esta capacidad ya
declaran. Una prueba DEBE enumerar todas las rutas que el contexto administrativo registra y DEBE
fallar si una ruta registrada responde sin credencial y no figura en la lista. El documento OpenAPI
y Swagger UI DEBEN seguir respondiendo solo con los perfiles `local` y `preprod` (requisito
existente de `build-integrity`); con cualquier otro perfil, incluido `prod`, NO DEBEN figurar en la
lista ni responder. Las rutas de salud NO DEBEN figurar en el documento OpenAPI.
(Previously: la lista no contenía ninguna ruta de salud porque Actuator no existía.)

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

#### Escenario: [WE94] Las dos rutas de salud responden sin credencial en el perfil de producción

- **DADO** el proceso administrativo con el perfil por omisión (producción)
- **CUANDO** se piden `GET /health/live` y `GET /health/ready` sin credencial
- **ENTONCES** ambas son servidas (`200`, o `503` la de preparación si la base no responde), no `401`, y
  figuran en la lista blanca pública

#### Escenario: [WE95] Ninguna otra ruta de Actuator es pública

- **DADO** el proceso administrativo
- **CUANDO** se piden sin credencial `/actuator`, `/actuator/health`, `/actuator/env`, `/actuator/metrics` y
  `/actuator/prometheus`
- **ENTONCES** todas responden `401` con `authentication-required`, ninguna figura en la lista blanca y ninguna
  responde contenido

#### Escenario: [WE96] Las rutas de salud solo admiten `GET`

- **DADO** las rutas `/health/live` y `/health/ready`
- **CUANDO** se envía `POST`, `PUT`, `DELETE` y `PATCH` a cada una sin credencial
- **ENTONCES** las ocho peticiones reciben `401`

#### Escenario: [WE97] Una ruta de salud nueva exige editar la lista

- **DADO** un endpoint de salud adicional expuesto sin figurar en la lista blanca
- **CUANDO** se ejecuta la prueba que enumera las rutas registradas
- **ENTONCES** la prueba falla señalando la ruta

### Requisito: La cadena del portal deniega toda ruta

El proceso del portal DEBE tener una cadena de filtros de seguridad que deniega toda petición, para todo método
HTTP incluidos `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD` y `OPTIONS`, con una única lista blanca cerrada de
aplicación que contiene exactamente `GET /health/live` y `GET /health/ready`. Solo cuando springdoc está
habilitado (perfiles `local` y `preprod`) se permiten además las entradas de documentación, las mismas que la
lista blanca del proceso administrativo; con el perfil por omisión (producción) el portal deniega todo lo demás
sin excepción. La respuesta denegada DEBE ser `401` con Problem Details, con el código
`authentication-required`, y las mismas cabeceras de seguridad base. El portal NO DEBE registrar ninguna ruta de
aplicación, y su mapa de rutas de aplicación con el perfil por omisión coincide con la instantánea vacía exigida
por `build-integrity`; las rutas de salud no son rutas de aplicación y no figuran en esa instantánea.
(Previously: el portal no tenía lista blanca de aplicación y denegaba toda petición, sin excepción.)

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

#### Escenario: Sin ruta pública de aplicación en el portal

- **DADO** el portal tal como lo entrega este cambio, con el perfil por omisión
- **CUANDO** se enumeran las rutas registradas de aplicación
- **ENTONCES** el conjunto es vacío, coincide con la instantánea aprobada, y ninguna petición fuera de las dos
  rutas de salud recibe una respuesta `2xx`

#### Escenario: Documentación del portal con perfil `local`

- **DADO** el proceso del portal con el perfil `local`
- **CUANDO** se pide el documento OpenAPI sin credencial y se pide cualquier otra ruta
- **ENTONCES** el documento OpenAPI responde `200`, y toda ruta fuera de las entradas de
  documentación y de las dos rutas de salud responde `401`

#### Escenario: [WE98] El portal sirve solo las dos rutas de salud sin credencial

- **DADO** el proceso del portal con el perfil por omisión
- **CUANDO** se piden `GET /health/live` y `GET /health/ready` sin credencial, y después `/actuator/health`,
  `/actuator/env` y `/health/other`
- **ENTONCES** las dos primeras son servidas (`200`, o `503` la de preparación si la base no responde) y las tres
  últimas responden `401` con `authentication-required`

#### Escenario: [WE99] Los métodos distintos de `GET` en la salud del portal se deniegan

- **DADO** el proceso del portal
- **CUANDO** se envía `POST`, `PUT`, `DELETE` y `PATCH` a `/health/live` y a `/health/ready` sin credencial
- **ENTONCES** las ocho peticiones reciben `401`

### Requisito: El trabajador no tiene cadena de seguridad, rutas de aplicación ni superficie de salud pública

El proceso trabajador NO DEBE registrar ninguna cadena de seguridad de aplicación, ningún filtro de petición de
aplicación ni ninguna ruta de aplicación. Cualquier superficie de red que el mecanismo de salud del trabajador
requiera (el diseño la elige; puede no requerir ninguna) DEBE servir únicamente el estado de salud, sin detalle,
y NO DEBE publicarse en el anfitrión ni quedar accesible fuera de la red interna de contenedores.
(Previously: el trabajador no tenía ningún servidor web escuchando; el mecanismo de salud del cambio 11a puede
requerir una escucha de salud, que queda acotada por este requisito.)

#### Escenario: Contexto del trabajador sin borde web de aplicación

- **DADO** el proceso trabajador arrancado por su camino de producción
- **CUANDO** se inspecciona su contexto
- **ENTONCES** no contiene ningún bean `SecurityFilterChain` de aplicación ni filtro de petición de aplicación, y
  ninguna ruta de aplicación está registrada

#### Escenario: [WE100] La superficie de salud del trabajador, si existe, solo sirve salud

- **DADO** el trabajador con su mecanismo de salud en marcha
- **CUANDO** se enumera todo lo que responde en su red (rutas y puertos) y se pide cualquier ruta que no sea de salud
- **ENTONCES** nada responde fuera del estado de salud, y no hay ningún puerto del trabajador publicado en el
  anfitrión

### Requisito: Toda respuesta de error usa Problem Details (RFC 9457)

Toda respuesta de error del borde web, incluidas las producidas por la cadena de seguridad, por la
validación, por el limitador, por el materializador y por la idempotencia, DEBE tener
`Content-Type: application/problem+json` y los campos `type`, `title`, `status`, `detail` e
`instance` de RFC 9457, además del campo `traceId` que declara el esquema de error del contrato
OpenAPI. El valor del identificador de traza DEBE ser el identificador de petición generado
en el servidor. `instance` NO DEBE incluir la cadena de consulta de la petición. El campo `status`
DEBE coincidir con el estado HTTP de la respuesta. La única excepción es la respuesta `503` de `/health/ready`,
cuyo cuerpo es exactamente `{"status":"DOWN"}` y no usa Problem Details, para no revelar detalle de infraestructura.
(Previously: no existía ninguna excepción.)

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

#### Escenario: [WE101] La preparación caída no usa Problem Details

- **DADO** el proceso administrativo con PostgreSQL detenido
- **CUANDO** se pide `/health/ready`
- **ENTONCES** la respuesta es `503` con el cuerpo `{"status":"DOWN"}`, sin los campos de RFC 9457, y cualquier
  otra respuesta de error del mismo proceso sigue siendo Problem Details

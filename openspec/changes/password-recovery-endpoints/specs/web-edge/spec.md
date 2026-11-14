# Delta para Borde web del proceso administrativo

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `password-recovery-endpoints` (F0, cambio 7, parte 4b, tercer cambio S3)
- **Propuesta:** `proposal.md`, aprobada el 2026-11-09 con las decisiones P1 a P7 del propietario

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `WE106` en adelante, a continuación del máximo del delta en curso de
  `rbac-permission-matrix-and-audit-integration` (`WE105`). Los escenarios que se conservan de la especificación
  vigente no llevan número, salvo los que ya lo tenían, que lo conservan.
- Un escenario marcado «(solo documental)» no tiene aserción automática.
- «Rutas de recuperación» son `POST /api/v1/auth/password-reset-requests` (solicitud) y
  `POST /api/v1/auth/password-resets` (confirmación).
- Este delta no modifica «Las rutas públicas y las listas de rutas autenticadas y restringidas son cerradas y una
  prueba las hace cumplir», que el delta de rbac modifica: añade un requisito propio para las dos entradas nuevas, de
  modo que los dos deltas se archivan sin conflicto en ese texto.

## ADDED Requirements

### Requisito: Las dos rutas de recuperación son públicas, solo para `POST`, y entran en la lista cerrada

La lista blanca pública del proceso administrativo DEBE contener, además de las entradas que ya tiene, exactamente las
dos rutas de recuperación, cada una solo para `POST`. Ninguna de las dos DEBE figurar en la lista de rutas
autenticadas, en la clase restringida ni en la lista de rutas con permiso, y la guarda de cobertura de rutas DEBE
seguir arrancando el proceso solo si toda ruta está en exactamente una lista. Las dos rutas DEBEN exigir un `Origin`
permitido como todo método no seguro de la cadena (`403` con `csrf-invalid` en su falta) y NO DEBEN exigir token
CSRF, porque no leen la cookie de sesión. Otro método sobre las mismas rutas NO DEBE servirse. El portal NO DEBE
registrar ninguna de las dos.

#### Escenario: [WE106] Las dos entradas nuevas de la lista pública

- **DADO** la lista blanca pública del proceso administrativo tal como la entrega este cambio
- **CUANDO** se enumera
- **ENTONCES** contiene `POST /api/v1/auth/password-reset-requests` y `POST /api/v1/auth/password-resets`, y las
  demás listas no contienen ninguna de las dos

#### Escenario: [WE107] Sin `Origin` permitido y con otro método

- **DADO** las dos rutas de recuperación
- **CUANDO** se envía `POST` sin `Origin`, y `GET`, `PUT`, `PATCH` y `DELETE` sin credencial
- **ENTONCES** el `POST` recibe `403` con `csrf-invalid`, ninguno de los otros responde `2xx` y los controladores
  registran cero invocaciones

#### Escenario: [WE108] Las rutas de producción son las vigentes más las dos de recuperación

- **DADO** el proceso administrativo arrancado por su camino de producción
- **CUANDO** se enumeran las rutas registradas por controladores de producción
- **ENTONCES** el conjunto es exactamente el que fijaba [WE85] (las siete rutas de sesión), más las rutas que otro
  cambio ya fusionado haya añadido con su propia especificación, más las dos rutas de recuperación
- **Y** toda petición a otra ruta de aplicación sin credencial recibe `401`

#### Escenario: [WE109] El portal no registra las rutas de recuperación

- **DADO** el proceso del portal arrancado por su camino de producción
- **CUANDO** se envía `POST` a cada ruta de recuperación
- **ENTONCES** ambas reciben `401` con `authentication-required` y el portal no tiene ningún controlador de
  recuperación

### Requisito: Las respuestas de las dos rutas de recuperación llevan `Referrer-Policy: no-referrer`

Toda respuesta a una petición cuya ruta sea una de las dos rutas de recuperación, de éxito o de error y
cualquiera que sea el componente que la produzca (controlador, traductor global, limitador, filtro de `Origin`,
validación o la página de error del contenedor), DEBE llevar `Referrer-Policy: no-referrer` en lugar de
`strict-origin-when-cross-origin`. Las demás respuestas DEBEN conservar `strict-origin-when-cross-origin`. La
decisión DEBE tener un único dueño, que conoce exactamente las dos rutas.

#### Escenario: [WE110] Todas las respuestas de las dos rutas

- **DADO** las respuestas `202`, `204`, `400`, `403`, `415`, `422` y `429` de las rutas de recuperación
- **CUANDO** se inspecciona su cabecera `Referrer-Policy`
- **ENTONCES** todas llevan `no-referrer` y exactamente una cabecera `Referrer-Policy`

#### Escenario: [WE111] Las demás rutas conservan la suya

- **DADO** un inicio de sesión, un refresco y una ruta inexistente
- **CUANDO** se inspecciona su cabecera `Referrer-Policy`
- **ENTONCES** las tres llevan `strict-origin-when-cross-origin`
- **Y** una ruta parecida, `/api/v1/auth/password-resets-x`, también la conserva

### Requisito: Los cuatro códigos de Problem Details de la confirmación existen con su estado y su texto es-HN

El catálogo DEBE incluir `password-reset-token-invalid` (400), `password-length-invalid` (422),
`password-reset-second-factor-required` (422) y `password-reset-second-factor-rejected` (422), cada uno con su entrada es-HN. Ninguno DEBE llevar
`WWW-Authenticate`, porque la confirmación no es una ruta autenticada. El texto de `password-reset-token-invalid`
NO DEBE distinguir un token inexistente de uno vencido, consumido o mal formado. El texto de
`password-length-invalid` DEBE indicar el intervalo admitido, de 12 a 128 caracteres, y una prueba DEBE fallar si
ese intervalo deja de coincidir con la política de longitud del caso de uso. `second-factor-invalid` (401) NO DEBE
usarse en la confirmación.

#### Escenario: [WE112] Cada código tiene su estado y ninguno lleva desafío

- **DADO** un productor de prueba para cada uno de los cuatro códigos
- **CUANDO** lanza su error en el borde
- **ENTONCES** el estado es 400, 422, 422 y 422 respectivamente, el `type` corresponde a cada código y ninguna
  respuesta lleva `WWW-Authenticate`

#### Escenario: [WE113] Los textos no nombran la causa del rechazo del token

- **DADO** el `detail` es-HN de `password-reset-token-invalid`
- **CUANDO** se lee
- **ENTONCES** no contiene «vencido», «expirado», «usado», «consumido» ni «no existe»

#### Escenario: [WE114] El intervalo del texto coincide con la política

- **DADO** `ProblemCatalogCoverageTest` ampliado y una variante del catálogo cuyo texto de `password-length-invalid`
  dice «entre 8 y 128»
- **CUANDO** se ejecuta con el catálogo real y con la variante
- **ENTONCES** pasa con el real y falla con la variante, nombrando el código

### Requisito: El mensaje de la respuesta aceptada vive en el catálogo de mensajes del backend

El texto de la respuesta aceptada DEBE venir de un catálogo de mensajes es-HN del backend, distinto del catálogo de
Problem Details, con la clave `identity.password-reset-request.accepted` y el texto «Si la dirección corresponde a
una cuenta, se enviará un enlace.». Ninguna clase de producción DEBE embeber ese texto. Una prueba DEBE fallar si la
clave falta o si el catálogo tiene una clave que ninguna clase usa. El idioma NO DEBE variar con
`Accept-Language`.

#### Escenario: [WE115] La clave existe y se usa

- **DADO** el catálogo de mensajes real y una variante sin la clave
- **CUANDO** se ejecuta la prueba de cobertura del catálogo de mensajes
- **ENTONCES** pasa con el real y falla con la variante, nombrando la clave

#### Escenario: [WE116] `Accept-Language` no cambia el texto

- **DADO** una solicitud con `Accept-Language: en-US`
- **CUANDO** se lee la respuesta aceptada
- **ENTONCES** el texto es el mismo en español de Honduras

## MODIFIED Requirements

### Requisito: Existen cinco políticas de límite de tasa configuradas por `confia.web.rate-limit.<política>.*`

El sistema DEBE generalizar la configuración del limitador al prefijo `confia.web.rate-limit.<política>.*` y
registrar cinco políticas con el mismo puerto `RateLimiter`, con estos valores por defecto (declarados en
`application.yml`, no en anotaciones):

| Política | Peticiones por IP | Umbral de fallos (capa 2) | Rutas |
|---|---|---|---|
| `admin-login` | 10 por minuto | 10 fallos en 10 minutos | `POST /api/v1/auth/sessions` |
| `admin-mfa` | 20 por minuto | 10 fallos en 10 minutos | verificación e inscripción del segundo factor |
| `admin-session` | 120 por minuto | 30 fallos en 10 minutos | refresco, cierre actual y cerrar todas |
| `admin-password-reset-request` | 10 por hora | 10 fallos en 1 hora, nunca alimentada | `POST /api/v1/auth/password-reset-requests` |
| `admin-password-reset` | 20 por minuto | 10 fallos en 10 minutos | `POST /api/v1/auth/password-resets` |

En las cinco, la capa 2, una vez activada, admite 1 intento por minuto con un tope de 1 hora. Los fallos de
`admin-login` son los desenlaces `Rejected`; los de `admin-mfa`, los `second-factor-invalid`; los de
`admin-session`, los rechazos del refresco (`session-expired`, incluida la petición sin cookie de sesión); los de
`admin-password-reset`, el token rechazado y el segundo factor rechazado de la confirmación. La política
`admin-password-reset-request` NO DEBE recibir ningún fallo, porque su ruta no tiene desenlace de rechazo: su capa 2
existe solo porque la política exige sus valores. Los cierres no registran fallos. Cada política DEBE tener su propio
contador por IP. La configuración DEBE vivir bajo `confia.web.rate-limit.<política>.*` con un componente por
política; un componente ausente o desconocido DEBE impedir el arranque con un mensaje que nombre la propiedad. Los
valores DEBEN poder ajustarse sin recompilar y un valor no positivo DEBE impedir el arranque con un mensaje que nombre
la propiedad completa. El rechazo DEBE responder `429` con `too-many-requests` y `Retry-After`. Con la decisión P1 del
propietario (2026-11-09), las cinco políticas viven en el limitador en memoria del proceso hasta el cambio 11.
(Previously: existían tres políticas, `admin-login`, `admin-mfa` y `admin-session`.)

#### Escenario: [WE70] Valores por defecto de las cinco políticas

- **DADO** el proceso arrancado sin sobrescribir las políticas
- **CUANDO** se consultan los valores efectivos
- **ENTONCES** `admin-login` es 10 por minuto con umbral de 10 fallos en 10 minutos, `admin-mfa` es 20 por
  minuto con umbral de 10 fallos en 10 minutos, `admin-session` es 120 por minuto con umbral de 30 fallos en 10
  minutos, `admin-password-reset-request` es 10 por hora con umbral de 10 fallos en 1 hora y
  `admin-password-reset` es 20 por minuto con umbral de 10 fallos en 10 minutos
- **Y** en las cinco, la capa 2 admite 1 intento por minuto con un tope de 1 hora

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

- **DADO** la configuración sin el componente `admin-password-reset`, y otra con un componente `admin-otro`
- **CUANDO** arranca el proceso
- **ENTONCES** los dos arranques fallan con un mensaje que nombra la propiedad

#### Escenario: [WE73] Valor no positivo en una política nueva

- **DADO** `confia.web.rate-limit.admin-password-reset-request.request-limit` en 0
- **CUANDO** arranca el proceso
- **ENTONCES** el arranque falla con un mensaje que nombra esa propiedad

#### Escenario: [WE74] Cada ruta aplica su política

- **DADO** las seis rutas de sesión y las dos rutas de recuperación
- **CUANDO** se inspeccionan sus políticas
- **ENTONCES** el inicio de sesión usa `admin-login`; las verificaciones y las inscripciones, `admin-mfa`; el
  refresco y los dos cierres, `admin-session`; la solicitud de recuperación, `admin-password-reset-request`; y la
  confirmación, `admin-password-reset`; `GET /api/v1/auth/sessions/current` no usa ninguna

#### Escenario: [WE117] Las cuotas de recuperación no se mezclan con las de sesión

- **DADO** una IP con su cupo de `admin-password-reset-request` agotado
- **CUANDO** envía una confirmación y un inicio de sesión
- **ENTONCES** ambos se admiten
- **Y** con el cupo de `admin-password-reset` agotado, la solicitud sigue admitida

#### Escenario: [WE118] La solicitud nunca registra un fallo

- **DADO** el controlador de la ruta de solicitud
- **CUANDO** se inspeccionan sus dependencias y se envían 10 solicitudes desde una IP
- **ENTONCES** no depende de `RateLimitFailures` y el contador de fallos de `admin-password-reset-request` de esa IP
  sigue en cero

### Requisito: Cabeceras de seguridad base en toda respuesta de la cadena administrativa

Toda respuesta de la cadena administrativa, de éxito o de error, DEBE incluir las cabeceras de
`docs/03-seguridad.md` §8.2 que correspondan a una API: `X-Content-Type-Options: nosniff`,
`X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin` (salvo en las respuestas de las dos rutas
de recuperación, que llevan `no-referrer`), `Permissions-Policy` con el valor de §8.2 y `Cache-Control: no-store`.
Las respuestas NO DEBEN incluir `X-Powered-By`. Las cabeceras de transporte que aporta el proxy
(`Strict-Transport-Security`, `Cross-Origin-Embedder-Policy`) quedan para el cambio 11 (brecha con destino: cambio
11).
(Previously: `Referrer-Policy` era `strict-origin-when-cross-origin` en toda respuesta, sin excepción.)

#### Escenario: Respuesta de error con las cabeceras base

- **DADO** una petición denegada por la cadena
- **CUANDO** se inspeccionan las cabeceras de la respuesta
- **ENTONCES** contiene las cinco cabeceras con los valores indicados y no contiene `X-Powered-By`

#### Escenario: Respuesta de éxito con las cabeceras base

- **DADO** una petición a una ruta de prueba que responde `200`
- **CUANDO** se inspeccionan las cabeceras de la respuesta
- **ENTONCES** contiene las mismas cinco cabeceras con los mismos valores

## RENAMED Requirements

### Requisito: Existen tres políticas de límite de tasa configuradas por `confia.web.rate-limit.<política>.*` → Existen cinco políticas de límite de tasa configuradas por `confia.web.rate-limit.<política>.*`

(Reason: la configuración pasa de tres a cinco políticas; el nombre antiguo deja de describir el requisito. Su
contenido modificado está en la sección MODIFIED de este delta, bajo el nombre nuevo.)
(Migration: las referencias a «tres políticas» se leen como «cinco políticas». Los escenarios [WE70] a [WE74], [WE92]
y [WE93] conservan su número; [WE73], [WE74] y [WE93] cambian su ejemplo a las políticas o rutas nuevas sin perder
lo que afirmaban.)

## REMOVED Requirements

### Requisito: Ausencia de recuperación de contraseña por HTTP (brecha con destino: `password-recovery-endpoints`)

(Reason: este cambio entrega las dos rutas de recuperación y cierra H3 y H4. El escenario [WE85], «Las rutas de
producción son exactamente las siete de sesión», deja de ser cierto. El escenario documental [WE86] registraba el
cierre de H1 y que H3 y H4 seguían abiertas; su primera parte se conserva en el informe de archivo de
`session-endpoints-cookie-and-csrf` y la segunda deja de ser cierta.)
(Migration: sustituido por «Las dos rutas de recuperación son públicas, solo para `POST`, y entran en la lista
cerrada» ([WE108] fija el conjunto de rutas de producción) y por los requisitos de H3 y H4 del delta de `identity`. Los
códigos `institution-not-found` e `institution-inactive` siguen sin existir: su ausencia la sigue vigilando
[WE84].)

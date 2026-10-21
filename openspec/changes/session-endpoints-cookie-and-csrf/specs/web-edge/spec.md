# Delta para Borde web del proceso administrativo

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `session-endpoints-cookie-and-csrf` (F0, cambio 7, parte 4b, segundo cambio S2 de tres)
- **Propuesta:** `proposal.md`, con DA-1 a DA-15 confirmadas por el propietario el 2026-10-20

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `WE43` en adelante, a continuación del máximo de la especificación vigente
  (`WE42`). Los escenarios que se conservan de la especificación vigente no llevan número, salvo los que ya lo
  tenían, que lo conservan.
- Un escenario marcado «(solo documental)» no tiene aserción automática.
- «Ruta de cookie» es toda ruta que lee la cookie de sesión: `POST /api/v1/auth/sessions/refresh` y
  `DELETE /api/v1/auth/sessions/current`.
- «Arnés de cookie» es un controlador que existe solo en el árbol de pruebas y emite una cookie de sesión y una
  cookie CSRF válidas, para probar el mecanismo antes de que exista un endpoint real que emita `Set-Cookie`.
- Las cookies son: la de sesión `__Secure-confia_admin_sid` y la CSRF `__Host-confia_csrf`.

## ADDED Requirements

### Requisito: `Origin` es obligatorio en todo método no seguro de la cadena administrativa y se compara con una lista configurada

La cadena administrativa DEBE rechazar con `403` y el código `csrf-invalid` toda petición con método no seguro
(distinto de `GET`, `HEAD`, `OPTIONS` y `TRACE`) cuyo `Origin` falte, esté repetido o no figure en
`confia.web.allowed-origins`. La lista DEBE ser una propiedad por entorno, vacía por defecto (con la lista vacía
ninguna petición mutadora pasa), cuyas entradas DEBEN ser orígenes `esquema://host[:puerto]` válidos, sin ruta,
sin comodines y, en producción, con esquema `https`. Una entrada inválida DEBE impedir el arranque con un mensaje
que nombre la propiedad. La comparación DEBE ser exacta, sin tratar `null` como permitido. El rechazo NO DEBE
distinguir la causa (falta, discrepancia u origen no permitido).

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

- **DADO** `allowed-origins` vacía, y otra ejecución con `https://admin.colegio.edu.hn/ruta`, con `*` y con
  `http://admin.colegio.edu.hn` bajo el perfil `prod`
- **CUANDO** se envía un `POST` con cualquier `Origin` con la lista vacía, y se arranca con cada entrada
  inválida
- **ENTONCES** el `POST` responde `403` con `csrf-invalid` y los tres arranques fallan con un mensaje que
  nombra la propiedad

### Requisito: Las rutas de cookie exigen un token CSRF de doble envío vinculado al refresco con HMAC

Las rutas de cookie DEBEN exigir la cabecera `X-CSRF-Token` y la cookie `__Host-confia_csrf` con el mismo valor,
y rechazar con `403` y `csrf-invalid` si falta alguno, si difieren, si el HMAC no valida o si el token está
vinculado a otro refresco. El token DEBE tener la forma `<32 bytes aleatorios en base64url>.<HMAC-SHA256(clave,
aleatorio || SHA-256(refresco))>`, con el cálculo y la comparación en tiempo constante dentro de
`shared.security`. El token DEBE reemitirse con cada emisión o rotación de la cookie de sesión, incluida la
gracia, con el vínculo del refresco que se entrega. Las demás rutas (`Bearer`) NO DEBEN exigir el token CSRF
aunque el navegador envíe la cookie. La cookie CSRF DEBE llevar `Secure`, `Path=/`, `SameSite=Strict`, ningún
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

- **DADO** las rutas reales de refresco y de cierre actual
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
variable de entorno. El proceso administrativo DEBE fallar al arrancar si la clave falta o es más corta que 32
bytes, con un mensaje que nombre la propiedad y NO repita el valor. Los procesos del portal y del trabajador NO
DEBEN tener la clave ni el componente que la usa, y DEBEN abortar si la propiedad aparece. Ningún valor de la
clave DEBE versionarse. Rotar la clave invalida los tokens CSRF vigentes, que se reemiten con el siguiente
refresco.

#### Escenario: [WE59] Administración sin clave o con clave corta no arranca

- **DADO** el proceso administrativo sin `confia.security.csrf.key`, y otro con una clave de 16 bytes
- **CUANDO** arranca cada uno
- **ENTONCES** los dos fallan con un mensaje que nombra la propiedad y no contiene el valor

#### Escenario: [WE60] El portal y el trabajador no la tienen

- **DADO** los procesos del portal y del trabajador con y sin la propiedad en su entorno
- **CUANDO** se construye cada contexto
- **ENTONCES** sin la propiedad arrancan sin ningún componente de CSRF, y con ella abortan nombrando la
  propiedad sin su valor

#### Escenario: [WE61] Ninguna clave CSRF versionada

- **DADO** el repositorio con todas sus pruebas y recursos
- **CUANDO** se ejecuta el escaneo de secretos
- **ENTONCES** no encuentra ningún valor para `confia.security.csrf.key`

### Requisito: La cookie de sesión tiene atributos fijos, se emite y se borra desde un único ayudante y un indicador de desarrollo no puede desactivar `Secure` en producción

El sistema DEBE emitir la cookie de sesión `__Secure-confia_admin_sid` con `HttpOnly`, `Secure`,
`SameSite=Strict`, `Path=/api/v1/auth/sessions`, ningún `Domain` y ningún `Max-Age` ni `Expires` (cookie de
sesión del navegador, DA-2 *a*), con el texto del refresco como valor. Para borrarla DEBE emitir el mismo nombre
con los mismos atributos y `Max-Age=0`; igual para la cookie CSRF. La emisión y el borrado DEBEN vivir en un
único ayudante de `shared.web`. Un indicador de configuración que desactive `Secure` para el desarrollo local
DEBE ser imposible con el perfil de producción: el proceso DEBE fallar al arrancar si ambos coinciden. Ninguna
respuesta de la cadena administrativa distinta de las de inicio de sesión, verificación de segundo factor,
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
- **ENTONCES** hay dos, uno por cookie, con el mismo nombre y los mismos atributos y `Max-Age=0`

#### Escenario: [WE64] `Secure` desactivado con el perfil de producción

- **DADO** el indicador de desarrollo que desactiva `Secure` y el perfil `prod`
- **CUANDO** arranca el proceso
- **ENTONCES** el arranque falla con un mensaje que nombra la propiedad
- **Y** con el perfil `local` el indicador se acepta

#### Escenario: [WE65] Ninguna otra respuesta emite cookies

- **DADO** respuestas de rutas públicas de documentación, denegadas, de `GET /api/v1/auth/sessions/current` y
  de errores `401`
- **CUANDO** se inspeccionan
- **ENTONCES** ninguna contiene `Set-Cookie` ni crea sesión HTTP

### Requisito: Las rutas restringidas aceptan solo un token restringido de su uso y su principal es de otro tipo que `AuthenticatedActor`

La cadena administrativa DEBE declarar una clase de ruta restringida, distinta de las rutas autenticadas y de las
públicas, con exactamente `POST /api/v1/auth/mfa/verifications` (usos `mfa-verify`, y `mfa-enroll` mientras la
credencial no esté verificada) y `POST /api/v1/auth/mfa/enrollments` (uso `mfa-enroll`). En esas rutas el filtro
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

- **DADO** un token de acceso, un `mfa-verify` y un `mfa-enroll` (con credencial no verificada)
- **CUANDO** se presenta cada uno a `GET /api/v1/auth/sessions/current`, a las verificaciones y a las
  inscripciones
- **ENTONCES** el acceso solo abre la sesión actual y «cerrar todas»; `mfa-verify` solo abre las verificaciones;
  `mfa-enroll` abre las inscripciones y las verificaciones; el resto responde `401 token-invalid`

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
registrar tres políticas con el mismo puerto `RateLimiter`: `admin-login` (valores existentes: 10 peticiones por
minuto por IP; capa 2 desde el fallo 10 en 10 minutos, 1 intento por minuto, tope de 1 hora); `admin-mfa`
(verificación e inscripción del segundo factor: 20 peticiones por minuto por IP; umbral de 10 fallos en 10
minutos con la misma capa 2); y `admin-session` (refresco y cierres: 120 peticiones por minuto por IP, sin
dimensión de fallos). Los fallos de `admin-mfa` son los `second-factor-invalid`. Cada política DEBE tener su
propio contador por IP. Los valores DEBEN poder ajustarse sin recompilar y un valor no positivo DEBE impedir el
arranque con un mensaje que nombre la propiedad completa. El rechazo DEBE responder `429` con `too-many-requests`
y `Retry-After`.

#### Escenario: [WE70] Valores por defecto de las tres políticas

- **DADO** el proceso arrancado sin sobrescribir las políticas
- **CUANDO** se consultan los valores efectivos
- **ENTONCES** `admin-login` es 10 por minuto, `admin-mfa` es 20 por minuto con umbral de 10 fallos en 10
  minutos y `admin-session` es 120 por minuto

#### Escenario: [WE71] Los contadores de las políticas son independientes

- **DADO** una IP con su cupo de `admin-login` agotado
- **CUANDO** envía un refresco y una verificación del segundo factor
- **ENTONCES** ambos se admiten, porque cada política tiene su propio contador

#### Escenario: [WE72] El décimo fallo del segundo factor activa la restricción de `admin-mfa`

- **DADO** una IP con 9 `second-factor-invalid` en 10 minutos
- **CUANDO** el décimo fallo se registra y la IP envía otra verificación a los 30 segundos
- **ENTONCES** recibe `429` con `Retry-After` de 30 segundos

#### Escenario: [WE73] Valor no positivo en una política nueva

- **DADO** `confia.web.rate-limit.admin-session.requests` en 0
- **CUANDO** arranca el proceso
- **ENTONCES** el arranque falla con un mensaje que nombra esa propiedad

#### Escenario: [WE74] Cada ruta aplica su política

- **DADO** las seis rutas de sesión
- **CUANDO** se inspeccionan sus políticas
- **ENTONCES** el inicio de sesión usa `admin-login`; las verificaciones y las inscripciones, `admin-mfa`; y el
  refresco y los dos cierres, `admin-session`; `GET /api/v1/auth/sessions/current` no usa ninguna

### Requisito: La espera materializada del retardo tiene un tope configurable

El materializador DEBE acotar la espera materializada de un `Delayed` a un tope configurable por
`confia.web.delay.max-wait`, positivo, con valor de partida de 30 segundos. El contador de retroceso y la
auditoría DEBEN conservar el retardo que calculó la política (hasta 900 segundos); solo se acota la espera de la
respuesta. Un tope no positivo DEBE impedir el arranque con un mensaje que nombre la propiedad. El retardo menor
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

- **DADO** el tope en 0 o en un valor negativo
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

## MODIFIED Requirements

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

## REMOVED Requirements

### Requisito: Solo un token de acceso completo del dominio administrativo autentica; el restringido y los de otros dominios se rechazan en toda ruta

(Reason: este cambio entrega las rutas restringidas de verificación e inscripción del segundo factor, que
aceptan el token restringido de su uso; «el restringido se rechaza en toda ruta» deja de ser cierto.)
(Migration: sustituido por «Un token de acceso completo autentica las rutas autenticadas; el restringido solo
abre las rutas restringidas de su uso, y los de otros dominios se rechazan en toda ruta», que conserva los
escenarios [WE09] y [WE10] con su alcance ajustado, y por «Las rutas restringidas aceptan solo un token
restringido de su uso y su principal es de otro tipo que `AuthenticatedActor`».)

### Requisito: Ausencia de endpoints de inicio de sesión, MFA, refresco, cierre de sesión y recuperación, de cookie de sesión y de CSRF (brecha con destino: `session-endpoints-cookie-and-csrf` y `password-recovery-endpoints`)

(Reason: este cambio entrega los endpoints de inicio de sesión, MFA, refresco y cierres, la cookie de sesión, la
protección CSRF de doble envío, `authentication-failed` y la aplicación del limitador y del materializador a
endpoints de producción. Los escenarios [WE29], [WE30] y [WE31] dejan de ser ciertos, como el requisito
anunciaba, y [WE32] se cumple: H1 y la condición 3 se cierran. Solo la recuperación de contraseña sigue
ausente, con otro dueño.)
(Migration: sustituido por los requisitos positivos de este delta y por «Ausencia de recuperación de contraseña
por HTTP (brecha con destino: `password-recovery-endpoints`)». La inversión de los inventarios
`WebEdgeScopeExclusionInventoryTest`, `ProblemCatalogCoverageTest`, `ProblemCodeTest` y `PublicRouteAllowListTest`
se declara en `build-integrity`.)

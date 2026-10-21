# Delta para Integridad de la construcción

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `session-endpoints-cookie-and-csrf` (F0, cambio 7, parte 4b, segundo cambio S2 de tres)
- **Propuesta:** `proposal.md`, con DA-1 a DA-15 confirmadas por el propietario el 2026-10-20

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `BI39` en adelante, a continuación del máximo de la especificación vigente
  (`BI38`). Los que se conservan de la especificación vigente conservan su número.
- Una regla de construcción con «convención de dos mitades» DEBE tener un fixture permanente que la viole a
  propósito y una comprobación de que el código de producción real no falla por esa causa y evalúa clases
  reales, no un conjunto vacío.

## ADDED Requirements

### Requisito: La migración `V9` amplía el catálogo de motivos de revocación y nada más

El sistema DEBE incluir la migración `V9`, que reemplaza la restricción de verificación del motivo de
revocación de la tabla de familias por un catálogo cerrado que es exactamente `reuse_detected`,
`password_change`, `logout`, `mfa_change` y `binding_changed`, y NO DEBE incluir `admin_revoke` ni ningún motivo
sin productor. `V9` NO DEBE agregar columnas, tablas ni privilegios (la vista de sesiones activas y la columna
`device_label` quedan fuera, DA-6 *b*), ni editar `V8`. Una prueba de contrato DEBE comparar el catálogo de la
base de datos con la enumeración `SessionRevocationReason` y fallar si difieren. El código de jOOQ DEBE
regenerarse desde las migraciones. La migración DEBE conservar la regla `revoked_at` con motivo y viceversa.

#### Escenario: [BI39] El catálogo tiene exactamente los cinco motivos

- **DADO** la tabla de familias tras `V9`
- **CUANDO** se intenta insertar una familia revocada con cada uno de los cinco motivos, con `admin_revoke` y
  con una cadena vacía
- **ENTONCES** los cinco motivos se aceptan y los otros dos se rechazan por la restricción de verificación

#### Escenario: [BI40] La base y la enumeración coinciden

- **DADO** la prueba de contrato entre el catálogo de la base y `SessionRevocationReason`
- **CUANDO** se ejecuta con el código real, y con una variante que añade un valor a la enumeración sin
  migración
- **ENTONCES** pasa con el real y falla en la variante, señalando el valor

#### Escenario: [BI41] `V9` no cambia nada más

- **DADO** el esquema tras `V9` frente al esquema tras `V8`
- **CUANDO** se comparan columnas, tablas, índices y privilegios
- **ENTONCES** la única diferencia es la restricción de verificación del motivo de revocación
- **Y** `V8` no fue editada (su suma de verificación de Flyway no cambió)

#### Escenario: [BI42] Las filas existentes sobreviven a la migración

- **DADO** familias revocadas con `reuse_detected` y `password_change` antes de `V9`
- **CUANDO** se aplica `V9`
- **ENTONCES** las filas siguen presentes con su motivo y su instante

### Requisito: Ningún asiento de auditoría arma su JSON por concatenación de cadenas

El sistema DEBE romper la construcción si una clase de producción construye el JSON de detalle de un asiento de
auditoría por concatenación o interpolación de cadenas en lugar de usar el constructor tipado de
`com.confia.shared.audit` (seguimiento F1c; `CLAUDE.md`, regla 12 por analogía). La regla DEBE tener su propio
fixture permanente con la convención de dos mitades y NO DEBE evaluar un conjunto vacío. Los dieciséis sitios de
las ocho clases que concatenaban (inventario a la fecha de `6a8c61f`) DEBEN estar migrados, y la regla impide que
aparezcan más; los JSON de claims y de cabecera de `shared.security.token` no son de auditoría y quedan fuera de
la regla.

#### Escenario: [BI43] Fixture con un asiento armado por concatenación

- **DADO** un fixture de prueba permanente que arma el JSON de un asiento con una cadena interpolada
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando la clase

#### Escenario: [BI44] El código real no concatena

- **DADO** el código de producción real de `identity` y de `shared`
- **CUANDO** se ejecuta la regla
- **ENTONCES** pasa evaluando las ocho clases migradas, sin excepción de conjunto vacío

### Requisito: Toda ruta de sesión que cuenta intentos o emite credenciales lleva su política de límite de tasa, y una regla lo hace cumplir

El sistema DEBE romper la construcción si un manejador de `identity.web` de `POST /api/v1/auth/sessions`,
`POST /api/v1/auth/mfa/verifications`, `POST /api/v1/auth/mfa/enrollments`, `POST /api/v1/auth/sessions/refresh`,
`DELETE /api/v1/auth/sessions/current` o `DELETE /api/v1/auth/sessions` no declara la política de límite de tasa
que le corresponde (`admin-login`, `admin-mfa` o `admin-session`), de modo que la condición H1 no pueda
regresar. La regla DEBE tener su propio fixture permanente con la convención de dos mitades.

#### Escenario: [BI45] Fixture con un manejador de inicio de sesión sin política

- **DADO** un fixture permanente con un manejador de `POST /api/v1/auth/sessions` sin la política
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla señalando el manejador

#### Escenario: [BI46] El código real declara la política correcta en las seis rutas

- **DADO** los seis manejadores reales
- **CUANDO** se ejecuta la regla
- **ENTONCES** pasa, y el inicio de sesión declara `admin-login`, las dos rutas de MFA `admin-mfa` y las otras
  tres `admin-session`

### Requisito: El HMAC de CSRF vive en `shared.security` y el uso de `Cookie` se confina al ayudante de cookies

El cálculo del HMAC de CSRF DEBE vivir en `com.confia.shared.security`, de modo que la regla «Ninguna clase fuera
de `identity` y `shared.security` usa utilidades de firma ni de hash» siga sin lista de permitidos (el HMAC no
vive en `shared.security.token`, para no ampliar la exención de la regla de aislamiento de tokens, ni en
`shared.web`). El uso de `jakarta.servlet.http.Cookie` DEBE confinarse a **una sola clase**, el ayudante de
cookies `SessionCookies` de `com.confia.shared.web.session`; la escritura de `Set-Cookie` pasa por ella, y ningún
controlador de `identity.web` DEBE depender de tipos de servlet. La regla DEBE tener su propio fixture permanente
con la convención de dos mitades.

#### Escenario: [BI47] Fixture con un controlador que escribe una cookie

- **DADO** un fixture permanente con un controlador de `identity.web` que usa `jakarta.servlet.http.Cookie`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla señalando la clase

#### Escenario: [BI48] El HMAC de CSRF no está en `shared.web`

- **DADO** el código de producción real
- **CUANDO** se ejecutan la regla de utilidades de firma y hash y la de cookies
- **ENTONCES** ambas pasan evaluando clases reales, el componente de HMAC pertenece a `shared.security.csrf` y el
  único uso de `Cookie` está en `SessionCookies`

### Requisito: Los inventarios de ausencia que este cambio rompe se invierten, cada uno con su control negativo

El sistema DEBE invertir, en el mismo cambio que rompe cada ausencia, las pruebas de inventario que la
declaraban, y cada inversión DEBE fallar con su control negativo:
`WebEdgeScopeExclusionInventoryTest` (la prohibición de `jakarta.servlet.http.Cookie` pasa a «exactamente
`SessionCookies`»; el limitador y el materializador pasan a permitirse además en `com.confia.identity.web` (los
controladores con `@RateLimited`, `RateLimitFailures` y el materializador) y siguen detectándose en un paquete
ajeno; la regla sobre las reglas de ruta, que solo admite `permitAll`, `authenticated` y `denyAll`, no cambia), `ProblemCatalogCoverageTest` y `ProblemCodeTest` (la negación
de `authentication-failed` se sustituye por la exigencia de los cinco códigos; siguen negados
`institution-not-found` e `institution-inactive`), `PublicRouteAllowListTest` con el enumerador de rutas (que
DEBE distinguir las clases pública, autenticada y restringida: rutas registradas = públicas ∪ autenticadas ∪
restringidas). La prueba de orden de filtros de la cadena administrativa (filtro propio de CSRF y `Origin` antes
del de tokens) se añade; el `CsrfFilter` de Spring sigue deshabilitado.
Las ausencias que siguen ciertas (recuperación de contraseña por HTTP, códigos de institución, adaptador de
Redis) DEBEN conservarse en esos mismos inventarios.

#### Escenario: [BI49] Cookie, limitador y materializador solo en los lugares permitidos

- **DADO** `WebEdgeScopeExclusionInventoryTest` actualizado
- **CUANDO** se ejecuta con el código real, con un fixture que usa `Cookie` fuera de `SessionCookies`, con otro
  que aplica el limitador o el materializador en un paquete ajeno a los permitidos y con otro que declara una regla
  de ruta distinta de `permitAll`, `authenticated` y `denyAll`
- **ENTONCES** pasa con el código real y falla en los tres fixtures

#### Escenario: [BI50] El catálogo exige los cinco códigos y sigue negando los de institución

- **DADO** `ProblemCatalogCoverageTest` y `ProblemCodeTest` actualizados
- **CUANDO** se ejecutan con el catálogo real, con uno sin la entrada es-HN de `session-expired` y con uno que
  añade `institution-inactive`
- **ENTONCES** pasa con el real y falla en los otros dos

#### Escenario: [BI51] El enumerador distingue tres clases de ruta

- **DADO** `PublicRouteAllowListTest` con el enumerador actualizado
- **CUANDO** se ejecuta con el código real, con una ruta restringida no declarada y con una ruta pública de
  sesión que responde `200` a un método no declarado
- **ENTONCES** pasa con el código real y falla en los dos fixtures, señalando cada ruta

#### Escenario: [BI52] La ausencia de recuperación por HTTP se conserva

- **DADO** los inventarios actualizados
- **CUANDO** se busca una ruta de recuperación de contraseña en producción
- **ENTONCES** no existe ninguna y un fixture que añade una falla el inventario

### Requisito: Cada bean y controlador nuevo de sesión llega con su línea de `ProcessBeanPolicy` en el mismo pull request, y el portal y el trabajador no los contienen

El contexto del proceso administrativo DEBE contener el filtro de CSRF (construido por la cadena, no como bean),
el componente de HMAC de CSRF, el ayudante de cookies, los controladores de sesión y de segundo factor, los casos
de uso de cierre y de verificación y las políticas del limitador, todos dentro de su lista de permitidos
(ADR-0024). Los contextos del portal y del trabajador NO DEBEN contener ninguno de ellos ni la propiedad
`confia.security.csrf.key`. Toda línea nueva de `ProcessBeanPolicy` DEBE llegar en el mismo pull request que el
bean o el controlador que la requiere.

#### Escenario: [BI53] El proceso administrativo registra el cableado nuevo

- **DADO** el proceso administrativo arrancado por su camino de producción con claves generadas por la prueba
- **CUANDO** se inspecciona su contexto
- **ENTONCES** contiene el componente de HMAC, el ayudante de cookies, los controladores de sesión (inicio de
  sesión, segundo factor, refresco y cierres, que sirven las seis rutas) y las tres políticas, todos dentro de su lista de permitidos, y el filtro de CSRF no es un bean

#### Escenario: [BI54] El portal y el trabajador no contienen nada de sesión nuevo

- **DADO** los procesos del portal y del trabajador
- **CUANDO** se inspecciona cada contexto
- **ENTONCES** ninguno contiene el HMAC de CSRF, el ayudante de cookies ni controladores de sesión

#### Escenario: [BI55] Un bean de sesión nuevo sin línea rompe la construcción

- **DADO** un bean de sesión nuevo sin línea en la lista de permitidos
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el proceso, el bean y su paquete

### Requisito: La puerta de tiempo del inicio de sesión corre en `verify` y falla la construcción

La construcción DEBE ejecutar en `./mvnw verify` la puerta de tiempo del inicio de sesión (200 intentos con
cuenta existente y 200 con inexistente, diferencia de medianas de 50 ms como máximo) y la igualdad del `401`
(cuerpo salvo `traceId`, cabeceras salvo `Date`; ADR-0005, verificación 7), con un control negativo, dentro del presupuesto de tiempo de las pruebas `*IT`.

#### Escenario: [BI56] La puerta falla ante una diferencia mayor

- **DADO** una variante que omite la verificación señuelo de la cuenta inexistente
- **CUANDO** se ejecuta la puerta de tiempo
- **ENTONCES** falla señalando la diferencia medida

#### Escenario: [BI57] La puerta pasa con el código real dentro del presupuesto

- **DADO** el código real
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la puerta pasa y la construcción sin mutación se mantiene dentro del presupuesto de 8 minutos

### Requisito: Las pruebas de arranque `*Test` no necesitan la clave CSRF versionada

Las pruebas `*Test` que arrancan el proceso administrativo DEBEN generar la clave CSRF al ejecutarse y NO DEBEN
requerir Docker. Ningún perfil ni archivo versionado DEBE contener un valor para `confia.security.csrf.key`.

#### Escenario: [BI58] Arranque del proceso administrativo con clave CSRF generada

- **DADO** una máquina sin Docker
- **CUANDO** se ejecutan las pruebas `*Test` de arranque
- **ENTONCES** el proceso administrativo arranca con una clave generada por la prueba y ningún archivo
  versionado contiene el valor

## MODIFIED Requirements

### Requisito: Tablas nuevas de familias y tokens de refresco, con `institution_id` y seguridad de fila forzada

El sistema DEBE mantener la migración `V8`, que crea **dos** tablas nuevas con el prefijo de módulo
`identity_` (ADR-0015, regla 3): la de familias de refresco y la de tokens de refresco. Todo lo que este
requisito exige de «la tabla nueva» DEBE cumplirse en **cada una de las dos**. Cada tabla DEBE declarar
`institution_id NOT NULL` (ADR-0009, punto 1) y toda restricción única DEBE incluir `institution_id` como
discriminador (ADR-0009, punto 2). La familia DEBE referenciar la cuenta de personal por su
identificador interno con una llave foránea compuesta hacia la tabla de cuentas, y el token DEBE
referenciar su familia con una llave foránea compuesta, de modo que un token no pueda apuntar a una
familia de otra institución. El hash del token DEBE restringirse, por una restricción de verificación
del catálogo, a la longitud de un SHA-256. El motivo de revocación DEBE restringirse por una restricción
de verificación a un catálogo cerrado que `V8` fija en `reuse_detected` y `password_change`, y que `V9` amplía a
`logout`, `mfa_change` y `binding_changed`; el catálogo NO DEBE incluir `admin_revoke` ni ningún motivo sin
productor. Un token DEBE poder
marcarse como consumido solo con su sucesor y con la huella del agente de usuario de quien lo presentó,
todo o nada; el sucesor cifrado DEBE llevar el prefijo de versión `v1:` y solo puede existir en un token
consumido; y cada familia DEBE tener a lo sumo un token sin consumir y cada token a lo sumo un
predecesor. La tabla de familias DEBE llevar
`last_used_at`, `absolute_expires_at`, la huella del agente de usuario y la IP inicial; la de tokens,
la columna del sucesor cifrado para la ventana de gracia. Cada tabla DEBE tener `ENABLE ROW LEVEL
SECURITY` y `FORCE ROW LEVEL SECURITY`, con una política sobre `institution_id` que siga el mismo patrón
`current_setting(..., true)` con fallo cerrado ante `NULL` que ya usan las migraciones `V1` a `V7`
(`docs/03-seguridad.md` §6.2), y la migración DEBE partir de `REVOKE ALL ... FROM PUBLIC` antes de
conceder ningún privilegio explícito. El código de jOOQ DEBE regenerarse desde la migración.
(Previously: el catálogo era exactamente `reuse_detected` y `password_change`, sin `logout`.)

#### Escenario: [BI01] Las puertas genéricas de esquema pasan sobre cada tabla nueva sin lista de exclusión

- **DADO** cada tabla nueva ya migrada, con `institution_id NOT NULL`, su seguridad a nivel de fila
  habilitada y forzada, y sus restricciones únicas con discriminador de institución
- **CUANDO** se ejecutan las pruebas de catálogo e inventario de `MultiTenantSchemaIT`
- **ENTONCES** todas pasan sobre **cada una de las dos** sin necesitar ninguna excepción ni entrada en
  una lista de exclusión

#### Escenario: [BI02] Una institución no lee las familias ni los tokens de otra

- **DADO** dos instituciones, cada una con al menos una familia y un token de refresco
- **CUANDO** la segunda, con su propio contexto de sesión y el rol restringido real `confia_admin_app`,
  consulta cada tabla sin ningún predicado adicional
- **ENTONCES** obtiene cero filas de la institución A y solo las de la propia

#### Escenario: [BI03] Sin contexto de institución la consulta devuelve cero filas

- **DADO** cada tabla nueva con filas de al menos una institución
- **CUANDO** se consulta con el rol `confia_admin_app` sin que `app.institution_id` esté establecido, y
  con el contexto vacío
- **ENTONCES** la consulta devuelve cero filas, porque la política deniega, en vez de fallar por falta
  de permiso o por una conversión a `uuid`

#### Escenario: [BI04] El catálogo rechaza un hash que no es de la longitud de un SHA-256

- **DADO** la tabla de tokens de refresco ya migrada
- **CUANDO** se intenta insertar, con el rol `confia_admin_app` y el contexto de su institución, una fila
  cuyo hash es un token de 43 caracteres en base64url en vez de su SHA-256
- **ENTONCES** la base rechaza la inserción por la restricción de verificación

#### Escenario: [BI05] El catálogo de motivos de revocación es cerrado y no incluye `admin_revoke`

- **DADO** la tabla de familias ya migrada hasta `V9`
- **CUANDO** se intenta insertar una familia revocada con el motivo `admin_revoke`, y otras con los motivos
  `reuse_detected`, `password_change`, `logout`, `mfa_change` y `binding_changed`
- **ENTONCES** la primera se rechaza por la restricción de verificación y las otras cinco se aceptan
- **Y** una familia con `revoked_at` sin motivo, o con motivo sin `revoked_at`, se rechaza

#### Escenario: [BI06] Un token no puede apuntar a una familia de otra institución

- **DADO** una familia de la institución A
- **CUANDO** se intenta insertar, con el contexto de la institución B, un token cuyo `institution_id` es
  B y cuya familia es la de A
- **ENTONCES** la base rechaza la inserción por la llave foránea compuesta o por la política de fila

#### Escenario: [BI36] Como máximo un token sin consumir por familia y un predecesor por token

- **DADO** una familia con un token sin consumir
- **CUANDO** se intenta insertar, con el rol `confia_admin_app`, un segundo token sin consumir en la misma
  familia, y se intenta enlazar dos tokens consumidos al mismo sucesor
- **ENTONCES** la base rechaza las dos operaciones por los índices únicos parciales
- **Y** consumir el token vigente e insertar su sucesor en la misma transacción, en ese orden, se acepta

#### Escenario: [BI37] El catálogo exige consumo coherente y sucesor cifrado con prefijo de versión

- **DADO** la tabla de tokens ya migrada
- **CUANDO** se intenta marcar un token como consumido sin sucesor, sin huella de consumo o con una
  huella que no es un SHA-256 en hexadecimal, y se intenta guardar un sucesor cifrado sin el prefijo
  `v1:` o en un token sin consumir
- **ENTONCES** las cuatro operaciones se rechazan por las restricciones de verificación
- **Y** marcar el consumo con sucesor y huella válidos, y un sucesor cifrado con el prefijo `v1:` en un
  token consumido, se aceptan

### Requisito: La instantánea del documento administrativo declara las siete operaciones de sesión

El sistema DEBE aprobar, con una actualización explícita y visible en el diff del pull request, la
instantánea del documento OpenAPI administrativo con exactamente siete operaciones: `GET
/api/v1/auth/sessions/current`, `POST /api/v1/auth/sessions`, `DELETE /api/v1/auth/sessions`,
`POST /api/v1/auth/sessions/refresh`, `DELETE /api/v1/auth/sessions/current`,
`POST /api/v1/auth/mfa/verifications` y `POST /api/v1/auth/mfa/enrollments`. Cada operación DEBE declarar su
esquema de seguridad (`bearerAuth` para el token de acceso, un esquema propio para el token restringido, y la
cookie de sesión con la cabecera `X-CSRF-Token` para el refresco y el cierre actual; el inicio de sesión no
declara ninguno) y exactamente las respuestas de su contrato, incluidas `429` y `503` donde actúa el limitador,
las de error con `application/problem+json` y el esquema de Problem Details. El esquema `ProblemDetail` DEBE
declarar el miembro `errors`. El documento NO DEBE contener respuestas genéricas que el traductor
global añadiría y que no estén aprobadas en la instantánea (la generación automática de respuestas
genéricas queda desactivada y cada respuesta se declara de forma explícita en la operación). `packages/contracts`
DEBE regenerarse desde la instantánea, incluidos los esquemas Zod, y las aplicaciones web DEBEN compilar contra
él. La instantánea del portal DEBE seguir sin ninguna operación.
(Previously: la instantánea declaraba exactamente una operación, `GET /api/v1/auth/sessions/current`, con `200`,
`401` y `500`.)

#### Escenario: [BI24] Las operaciones y sus respuestas coinciden con la instantánea

- **DADO** la instantánea aprobada con las siete operaciones
- **CUANDO** se ejecuta `OpenApiContractSnapshotTest`
- **ENTONCES** el documento generado coincide y la operación de la sesión actual sigue declarando solo `200`,
  `401` y `500`
- **Y** la instantánea comprometida queda intacta después de la ejecución

#### Escenario: [BI25] Una respuesta genérica no aprobada rompe la construcción

- **DADO** una operación que el traductor global dota de una respuesta `403` o `429` no aprobada en la
  instantánea
- **CUANDO** se ejecuta `OpenApiContractSnapshotTest`
- **ENTONCES** la prueba falla señalando la respuesta añadida

#### Escenario: [BI26] El esquema de error declara `errors` y el contrato se regenera sin diferencias

- **DADO** el esquema `ProblemDetail` de la instantánea y `packages/contracts`
- **CUANDO** se regenera el contrato con orval a partir de la instantánea
- **ENTONCES** `errors` figura como arreglo de objetos con `field` y `reason`, las aplicaciones web
  compilan y pasan sus pruebas contra el contrato regenerado, y la regeneración no produce diferencias
  en el repositorio (el código generado no se compromete; lo comprometido es la instantánea)

#### Escenario: [BI27] La instantánea del portal sigue vacía

- **DADO** el documento OpenAPI del portal tras este cambio
- **CUANDO** se ejecuta `OpenApiContractSnapshotTest`
- **ENTONCES** coincide con su instantánea, sin ninguna operación

#### Escenario: [BI59] Seguridad y respuestas de las operaciones de cookie y de segundo factor

- **DADO** la instantánea aprobada
- **CUANDO** se leen las operaciones de refresco, cierre actual, verificación e inscripción
- **ENTONCES** el refresco y el cierre actual declaran la cookie de sesión y `X-CSRF-Token` y las respuestas
  `401` y `403`; la inscripción declara `201`, `401`, `409` y `429`; la verificación declara `200`, `400`,
  `401` y `429`; y el inicio de sesión declara `200`, `400`, `401`, `403`, `415`, `429` y `503`

## RENAMED Requirements

### Requisito: La instantánea del documento administrativo declara la primera operación de producción → La instantánea del documento administrativo declara las siete operaciones de sesión

(Reason: la instantánea pasa de una a siete operaciones; el nombre antiguo deja de describir el requisito. Su
contenido modificado está en la sección MODIFIED de este delta, bajo el nombre nuevo.)
(Migration: las referencias a «la primera operación de producción» se leen como «las siete operaciones de
sesión». Los escenarios [BI24] a [BI27] conservan su número.)

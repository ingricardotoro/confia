# Delta para Integridad de la construcción

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `rbac-permission-matrix-and-audit-integration` (F0, cambio 8)
- **Propuesta:** `proposal.md`, aprobada el 2026-11-03, con O1 a O10 y Q1 a Q5 vinculantes

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `BI119` en adelante, a continuación del máximo de la especificación vigente
  (`BI118`). Los escenarios que se conservan conservan su identificador.
- El número de las migraciones nuevas (la de la tabla de asignación de rol, «V14 o la siguiente libre», y la del retiro
  de `mfa_required`) lo fija el diseño; este delta las nombra por su función.
- Valores que fija el diseño: el nombre de la tabla de asignación y de su restricción, el conjunto exacto de privilegios
  de `confia_admin_app` sobre ella (este delta sigue el patrón de las tablas de identidad: `SELECT`, `INSERT` y
  `UPDATE`, sin `DELETE`; el diseño puede estrechar a `SELECT` si no hay escritura de producción), las respuestas
  exactas de la operación nueva de OpenAPI y la lista de beans de `ProcessBeanPolicy`.
- Cada línea nueva de `ProcessBeanPolicy` y cada regla de ArchUnit llega en el mismo pull request que el paquete o el
  bean que la requiere (ADR-0024). La cadena de siete pull requests apilados de la propuesta (Q5) no cambia ese
  principio: la tabla y el resolutor llegan en la rebanada 2; el decisor y la guarda, en la 3; el retiro de la columna,
  en la 5b, después de la política de MFA de la 5a.

## ADDED Requirements

### Requisito: La tabla de asignación de rol lleva `institution_id`, restricción única por cuenta e institución, restricción del conjunto cerrado de roles y seguridad de fila forzada

El sistema DEBE incluir una migración nueva que crea la tabla de asignación de rol, con el prefijo de módulo
`identity_`, `institution_id NOT NULL`, restricción única que incluye `institution_id` y la cuenta (un rol por cuenta e
institución), una restricción `CHECK` sobre el conjunto cerrado de los seis códigos de rol, una clave foránea a la
cuenta dentro de su institución, `ENABLE ROW LEVEL SECURITY` y `FORCE ROW LEVEL SECURITY` con una política sobre
`institution_id` que siga el patrón `current_setting(..., true)` con fallo cerrado ante `NULL`. La migración DEBE partir
de `REVOKE ALL ... FROM PUBLIC`, NO DEBE editar las migraciones `V1` a `V13` y NO DEBE crear tablas globales de roles ni
de permisos (ADR-0017). La tabla DEBE pasar las puertas genéricas de `MultiTenantSchemaIT` sin lista de exclusión.

#### Escenario: [BI119] Las puertas genéricas de esquema pasan sobre la tabla nueva sin lista de exclusión

- **DADO** la tabla de asignación de rol ya migrada
- **CUANDO** se ejecutan las pruebas de catálogo e inventario de `MultiTenantSchemaIT`
- **ENTONCES** todas pasan sin ninguna excepción ni entrada en una lista de exclusión

#### Escenario: [BI120] La migración no edita las anteriores ni crea tablas globales

- **DADO** el esquema tras la migración nueva frente al esquema anterior
- **CUANDO** se comparan tablas, columnas, índices y privilegios
- **ENTONCES** la única diferencia es la tabla de asignación, sus índices, restricciones, política de fila y privilegios
- **Y** las migraciones `V1` a `V13` conservan su suma de verificación y no existe tabla global de roles ni de
  permisos

#### Escenario: [BI121] Una institución no lee las asignaciones de otra y sin contexto no hay filas

- **DADO** dos instituciones con asignaciones propias
- **CUANDO** se consulta con `confia_admin_app` con el contexto de una de ellas, y sin contexto
- **ENTONCES** se obtienen solo las filas de su institución, y sin contexto cero filas, no un error de permiso

### Requisito: Permisos de acceso a la tabla de asignación de rol por rol de base de datos

El sistema DEBE conceder sobre la tabla de asignación de rol, exactamente y sin uno más ni uno menos
(`docs/03-seguridad.md` §6.1): a `confia_admin_app`, `SELECT`, `INSERT` y `UPDATE`, sin `DELETE` (valor que confirma
el diseño); a `confia_portal_app`, **ningún privilegio**; a `confia_readonly`, solo `SELECT`, sujeto a la política de
fila; a `confia_backup`, el alcance de `pg_read_all_data`; a `confia_owner`, ningún privilegio adicional a los de
propietario del esquema, sin `BYPASSRLS`. El sistema DEBE extender `RolePrivilegeMatrixIT` con las filas de la tabla
para los cinco roles.

#### Escenario: [BI122] La matriz de privilegios cubre la tabla nueva para los cinco roles

- **DADO** `RolePrivilegeMatrixIT` extendida con las filas de la tabla de asignación de rol
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** confirma exactamente los privilegios de este requisito para cada uno de los cinco roles, sin uno más ni
  uno menos

#### Escenario: [BI123] El portal no tiene ningún privilegio sobre la tabla

- **DADO** el rol `confia_portal_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT`, `UPDATE` o `DELETE` sobre la tabla
- **ENTONCES** las cuatro operaciones se rechazan por falta de privilegio

#### Escenario: [BI124] Ningún rol de aplicación puede borrar asignaciones

- **DADO** `confia_admin_app` con el contexto de una institución
- **CUANDO** intenta `DELETE` sobre la tabla
- **ENTONCES** se rechaza por falta de privilegio

### Requisito: Una migración propia retira la columna `mfa_required` después de que la política de MFA esté en `main`

El sistema DEBE incluir una migración posterior a la derivación de la MFA que elimine la columna `mfa_required` de
`identity_staff_account` y nada más. La migración NO DEBE editar `V6` ni ninguna migración anterior. Tras ella, el
código generado de jOOQ NO DEBE contener la columna, ningún código de producción NI de prueba DEBE referirla, y las
pruebas que antes la fijaban DEBEN asignar un rol mediante un ayudante común o no fijar nada.

#### Escenario: [BI125] La migración de retiro elimina solo la columna

- **DADO** el esquema tras la migración de retiro frente al esquema anterior
- **CUANDO** se comparan tablas, columnas, índices y privilegios
- **ENTONCES** la única diferencia es la columna `mfa_required` de `identity_staff_account`

#### Escenario: [BI126] Nada refiere la columna

- **DADO** el código de producción, el código de prueba y el código generado
- **CUANDO** se busca `mfa_required`, `MFA_REQUIRED` y `mfaRequired`
- **ENTONCES** no hay ninguna aparición, salvo en `V6` (que no se edita) y en la migración de retiro

#### Escenario: [BI127] El retiro no precede a la política

- **DADO** el historial de migraciones y de pull requests apilados
- **CUANDO** se comprueba el orden
- **ENTONCES** la migración de retiro es posterior a la rebanada que entrega la política de MFA, y la construcción de la
  rebanada anterior sigue pasando sin la migración (solo documental)

### Requisito: Los beans de autorización solo viven en el proceso administrativo y su lista de permitidos se edita en el mismo pull request

El contexto del proceso administrativo DEBE contener, dentro de su lista de permitidos de `ProcessBeanPolicy`, el
decisor de permisos, la lista de rutas con permiso, la guarda de cobertura de rutas, el adaptador del resolutor, el
controlador del informe de roles, el caso de uso del informe, la política de MFA, el contador de denegaciones y el
adaptador de métricas. Los contextos del portal y del trabajador NO DEBEN contener ninguno de ellos. Toda línea nueva
de `ProcessBeanPolicy` y toda regla nueva de ArchUnit DEBE llegar en el mismo pull request que el bean o el paquete que
la requiere.

#### Escenario: [BI128] El proceso administrativo registra el cableado nuevo

- **DADO** el proceso administrativo arrancado por su camino de producción
- **CUANDO** se inspecciona su contexto
- **ENTONCES** contiene los beans de autorización listados, todos dentro de su lista de permitidos

#### Escenario: [BI129] El portal y el trabajador no contienen ningún bean de autorización

- **DADO** los procesos del portal y del trabajador
- **CUANDO** se inspecciona cada contexto
- **ENTONCES** ninguno contiene el decisor, la lista de rutas, la guarda, el resolutor, la política de MFA ni el
  controlador del informe

#### Escenario: [BI130] Un bean de autorización nuevo sin línea rompe la construcción

- **DADO** un bean de autorización sin línea en la lista de permitidos
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el proceso, el bean y su paquete

### Requisito: Las reglas de dependencia protegen las fronteras de la autorización

El sistema DEBE hacer cumplir con ArchUnit: que los puertos y códigos de permiso de `shared.security` no dependen de
ningún módulo (`SharedBoundaryRulesTest`); que el catálogo de roles y la asignación de roles a permisos, y la política de
MFA, viven en `identity.domain` sin framework ni entrada/salida; que la tabla de asignación y su adaptador jOOQ viven
en `identity.infrastructure` y ninguna clase `web` toca jOOQ; que el puerto de resolución de permisos tiene exactamente
una implementación de producción, en `identity`; que ninguna clase de producción usa anotaciones de permiso ni
seguridad de métodos para decidir permisos; y que la decisión de permiso no se toma en `application`.

#### Escenario: [BI131] `shared.security` no importa módulos

- **DADO** el puerto y los códigos de permiso en `shared.security`
- **CUANDO** se ejecutan las reglas de dependencia
- **ENTONCES** ninguna clase de `shared` importa un módulo, y con un fixture que importa una clase de `identity` la
  regla falla

#### Escenario: [BI132] El dominio del catálogo no depende de frameworks

- **DADO** el catálogo de roles y la política de MFA en `identity.domain`
- **CUANDO** se ejecutan las reglas de la capa `domain`
- **ENTONCES** pasan, y con un fixture que importa Spring o jOOQ en `domain` fallan

#### Escenario: [BI133] Ninguna anotación de permiso en producción

- **DADO** el código de producción
- **CUANDO** se busca una anotación `@PreAuthorize`, `@RequirePermission` o `@EnableMethodSecurity`
- **ENTONCES** no existe ninguna, y con un fixture que la añade la regla falla

### Requisito: Los inventarios de ausencia que el cambio 8 rompe se invierten, cada uno con su control negativo

El sistema DEBE invertir, en el mismo cambio que rompe cada ausencia, las pruebas de inventario que la declaraban, y
cada inversión DEBE fallar con su control negativo: `WebEdgeScopeExclusionInventoryTest` (la ausencia de matriz de
roles, de cobertura de MFA por ruta y de guarda de arranque pasa a la aserción positiva de la lista de rutas con permiso
y de la guarda; la ausencia de anotación de permiso se conserva), `IdentityScopeExclusionInventoryTest` (las ausencias
del cambio 8 que siguen ciertas, como el restablecimiento administrativo y la desactivación de cuentas, conservan su
aserción y pasan a nombrar al cambio 8b), `AuditScopeExclusionInventoryTest` (el permiso `audit:read` existe en el
catálogo, pero ningún endpoint expone la bitácora) y la prueba de enumeración de rutas, que DEBE distinguir las cuatro
clases. Las ausencias que siguen ciertas DEBEN conservarse en esos mismos inventarios.

#### Escenario: [BI134] `WebEdgeScopeExclusionInventoryTest` afirma la lista de rutas con permiso y conserva la ausencia de anotaciones

- **DADO** `WebEdgeScopeExclusionInventoryTest` invertida
- **CUANDO** se ejecuta con el código real, con un fixture que quita la lista de rutas con permiso y con un fixture que
  añade una anotación de permiso
- **ENTONCES** pasa con el código real, evaluando un conjunto no vacío, y falla en los dos fixtures

#### Escenario: [BI135] La enumeración de rutas distingue las cuatro clases

- **DADO** la prueba de enumeración de rutas actualizada
- **CUANDO** se ejecuta con el código real, con una ruta con permiso que responde `200` sin credencial y con una ruta
  sin clasificar
- **ENTONCES** pasa con el código real y falla en los dos fixtures, señalando cada ruta

#### Escenario: [BI136] Las ausencias de la bitácora y de identidad que siguen ciertas se conservan

- **DADO** `AuditScopeExclusionInventoryTest` e `IdentityScopeExclusionInventoryTest` actualizadas
- **CUANDO** se ejecutan, y con fixtures que añaden un endpoint de lectura de la bitácora y un caso de uso de
  desactivación de cuenta
- **ENTONCES** pasan con el código real y fallan en los dos fixtures

### Requisito: Las pruebas del tope de denegaciones y del criterio 2 son deterministas

Las pruebas del tope, de la ventana y del criterio 2 DEBEN usar un reloj inyectado y un estado aislado por prueba, y NO
DEBEN usar espera de tiempo real (`Thread.sleep` o equivalentes) ni depender del estado que deje otra prueba ni de su
orden de ejecución. El diseño DEBE proveer el reloj inyectable de la ventana.

#### Escenario: [BI137] Ninguna espera de tiempo real en esas pruebas

- **DADO** las clases de prueba del tope, de la ventana y del criterio 2
- **CUANDO** se ejecuta una regla de ArchUnit o de búsqueda sobre ellas
- **ENTONCES** ninguna refiere `Thread.sleep`, `Instant.now()` ni `Clock.systemUTC()`, y con un fixture que lo hace la
  regla falla

#### Escenario: [BI138] El resultado no depende del orden

- **DADO** las pruebas del tope y del criterio 2
- **CUANDO** se ejecutan en orden aleatorio, tres veces
- **ENTONCES** el resultado es idéntico en las tres

## RENAMED Requirements

### Requisito: La instantánea del documento administrativo declara las siete operaciones de sesión → La instantánea del documento administrativo declara las siete operaciones de sesión y la del informe de roles

(Reason: la ruta `GET /api/v1/identity/roles` entra al documento administrativo, que pasa de siete a ocho
operaciones.)
(Migration: la prueba `OpenApiContractSnapshotTest` y la instantánea aprobada se actualizan con la operación nueva en el
mismo pull request que la ruta, y `packages/contracts` se regenera.)

## MODIFIED Requirements

### Requisito: La instantánea del documento administrativo declara las siete operaciones de sesión y la del informe de roles

El sistema DEBE aprobar, con una actualización explícita y visible en el diff del pull request, la
instantánea del documento OpenAPI administrativo con exactamente ocho operaciones: `GET
/api/v1/auth/sessions/current`, `POST /api/v1/auth/sessions`, `DELETE /api/v1/auth/sessions`,
`POST /api/v1/auth/sessions/refresh`, `DELETE /api/v1/auth/sessions/current`,
`POST /api/v1/auth/mfa/verifications`, `POST /api/v1/auth/mfa/enrollments` y `GET /api/v1/identity/roles`. Cada operación DEBE declarar su
esquema de seguridad (`bearerAuth` para el token de acceso, un esquema propio para el token restringido, y la
cookie de sesión con la cabecera `X-CSRF-Token` para el refresco y el cierre actual; el inicio de sesión no
declara ninguno) y exactamente las respuestas de su contrato, incluidas `429` y `503` donde actúa el limitador,
las de error con `application/problem+json` y el esquema de Problem Details. La operación del informe de roles DEBE
declarar `bearerAuth`, el permiso que exige, y las respuestas `200`, `401`, `403` y `500` (el conjunto exacto lo fija
el diseño). El esquema `ProblemDetail` DEBE
declarar el miembro `errors`. El documento NO DEBE contener respuestas genéricas que el traductor
global añadiría y que no estén aprobadas en la instantánea (la generación automática de respuestas
genéricas queda desactivada y cada respuesta se declara de forma explícita en la operación). `packages/contracts`
DEBE regenerarse desde la instantánea, incluidos los esquemas Zod, y las aplicaciones web DEBEN compilar contra
él. La instantánea del portal DEBE seguir sin ninguna operación.
(Previously: la instantánea declaraba exactamente siete operaciones, todas de sesión y de segundo factor.)

#### Escenario: [BI24] Las operaciones y sus respuestas coinciden con la instantánea

- **DADO** la instantánea aprobada con las ocho operaciones
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

#### Escenario: [BI139] La operación del informe de roles está declarada y es la única con permiso

- **DADO** la instantánea aprobada
- **CUANDO** se lee `GET /api/v1/identity/roles`
- **ENTONCES** declara `bearerAuth`, el permiso `identity:read`, y respuestas `200`, `401`, `403` y `500`, y ninguna
  otra operación del documento declara un permiso de rol

### Requisito: Tablas nuevas de cifrado de columna y MFA, con `institution_id` y seguridad de fila forzada

El sistema DEBE mantener la migración `V6`, que añade la columna `mfa_required BOOLEAN NOT NULL` a
`identity_staff_account` y crea **cuatro** tablas nuevas: la tabla de llaves de datos del sobre de
llaves, con el prefijo de módulo `shared_` porque su gestión vive en `com.confia.shared.crypto`
(decisión D3 de la propuesta), con su estado `active`/`retired`; y, con el prefijo de módulo
`identity_`, la tabla de credencial TOTP por cuenta, la tabla de códigos de recuperación de MFA, y
la tabla de retroceso de verificación TOTP. `V6` NO se edita: la columna `mfa_required` que añade la retira una migración
posterior (ver el requisito de retiro de este delta). Todo lo que este requisito exige de «la tabla nueva»
DEBE cumplirse en **cada una de las cuatro**. Cada tabla DEBE declarar `institution_id NOT NULL`
(ADR-0009, punto 1) y toda restricción única DEBE incluir `institution_id` como discriminador
(ADR-0009, punto 2). Cada tabla DEBE tener `ENABLE ROW LEVEL SECURITY` y `FORCE ROW LEVEL
SECURITY`, con una política sobre `institution_id` que siga el mismo patrón
`current_setting(..., true)` con fallo cerrado ante `NULL` que ya usan las migraciones `V1` a `V5`
(`docs/03-seguridad.md` §6.2). La tabla de llaves de datos DEBE además aislar las llaves por
institución, de modo que cifrar o descifrar el secreto de una institución nunca toque la llave de
otra. La migración DEBE partir de `REVOKE ALL ... FROM PUBLIC` antes de conceder ningún privilegio
explícito.
(Previously: el requisito mantenía `V6` con su columna `mfa_required` sin prever su retiro posterior.)

#### Escenario: Las puertas genéricas de esquema pasan sobre cada tabla nueva sin lista de exclusión

- **DADO** cada una de las cuatro tablas nuevas ya migrada, con `institution_id NOT NULL`, su
  seguridad a nivel de fila habilitada y forzada, y su restricción única con discriminador de
  institución
- **CUANDO** se ejecutan las pruebas de catálogo e inventario de `MultiTenantSchemaIT`
- **ENTONCES** todas pasan sobre **cada una de las cuatro** sin necesitar ninguna excepción ni
  entrada en una lista de exclusión

#### Escenario: Una institución no lee la llave de datos de otra

- **DADO** dos instituciones, cada una con al menos una llave de datos activa en la tabla de llaves
- **CUANDO** la segunda, con su propio contexto de sesión y el rol `confia_admin_app`, consulta la
  tabla sin ningún predicado adicional
- **ENTONCES** solo obtiene las filas de su propia institución, nunca las de la primera
- **Y** por tanto cifrar o descifrar el secreto de la primera institución nunca toca la llave de la
  segunda

#### Escenario: Sin contexto de institución, la consulta devuelve cero filas, no un error de permiso

- **DADO** cada tabla nueva ya migrada, con filas de al menos una institución
- **CUANDO** se consulta con el rol `confia_admin_app` sin que `app.institution_id` esté establecido
  en la transacción
- **ENTONCES** la consulta devuelve cero filas, porque la política deniega ante `NULL`, en vez de
  fallar por falta de permiso

### Requisito: Los inventarios de ausencia que este cambio rompe se invierten, cada uno con su control negativo

El sistema DEBE invertir, en el mismo cambio que rompe cada ausencia, las pruebas de inventario que la
declaraban, y cada inversión DEBE fallar con su control negativo: `IdentityScopeExclusionInventoryTest`
(ninguna clase de `identity` referencia sesiones ni tokens de refresco: se sustituye por dos reglas, la
del restablecimiento que revoca y la del hash de contraseña que no se reemplaza sin revocar),
`WebEdgeScopeExclusionInventoryTest` (ninguna clase implementa `SessionValidity`: pasa a exactamente una
implementación, en `identity`, consumida solo por el filtro; y las reglas de ruta admiten `permitAll`,
`authenticated` y `denyAll`), `EmptyShouldExceptionInventoryTest` (la entrada de la firma
de controladores), `ProblemCatalogCoverageTest` (la negación de `token-invalid` y de `token-expired`) y
`PublicRouteAllowListTest` con el enumerador de rutas, que DEBE distinguir rutas públicas y rutas
autenticadas. Las ausencias que siguen ciertas (cookie, limitador y materializador sin uso de producción,
`authentication-failed`, códigos de institución) DEBEN conservarse en esos mismos inventarios.
(Previously: el escenario BI17 afirmaba que las reglas de ruta «siguen prohibiendo reglas por rol, permiso o MFA»; el
cambio 8 admite además la decisión de la lista de rutas con permiso.)

#### Escenario: [BI16] Las sesiones y los refrescos solo los referencian las clases de sesión de `identity`

- **DADO** `IdentityScopeExclusionInventoryTest` invertido en dos reglas con fixture permanente: (a) el
  restablecimiento de contraseña con token llama a la operación de revocación de todas las familias de la
  cuenta; (b) ninguna clase de producción distinta de él reemplaza el hash de contraseña sin llamar
  también a esa revocación
- **CUANDO** se ejecuta con el código real, con un fixture permanente que reemplaza el hash sin revocar y
  con un fixture permanente de un restablecimiento que no llama a la revocación
- **ENTONCES** pasa con el código real, evaluando la llamada real y no un conjunto vacío, y falla sobre los
  dos fixtures, señalando la clase
- **Y** la regla anterior («ninguna clase de `identity` referencia sesiones ni tokens de refresco») y su
  fixture se retiran en el mismo cambio

#### Escenario: [BI17] `SessionValidity` tiene exactamente un adaptador y las demás ausencias se conservan

- **DADO** `WebEdgeScopeExclusionInventoryTest` actualizado
- **CUANDO** se ejecuta con el código real, con un fixture que añade un segundo adaptador del puerto, con
  un fixture de otra clase que consume el puerto, y con un fixture que usa `jakarta.servlet.http.Cookie`
  en producción
- **ENTONCES** pasa con el código real, que tiene exactamente un adaptador, en `identity`, y como único
  consumidor el filtro, y falla en los tres fixtures
- **Y** las reglas de ruta admiten `permitAll`, `authenticated`, `denyAll` y la decisión de permiso de la lista de
  rutas con permiso (capacidad `authorization`), y siguen prohibiendo cualquier otra regla por rol, permiso o MFA

#### Escenario: [BI18] La entrada de la firma de controladores se retira del inventario de conjuntos vacíos

- **DADO** `EmptyShouldExceptionInventoryTest` sin la entrada de la regla de la firma pública de
  controladores
- **CUANDO** se ejecuta y se restituye la entrada en un fixture
- **ENTONCES** pasa sin la entrada, porque la regla evalúa un controlador real, y el fixture falla por
  una excepción declarada sin necesidad

#### Escenario: [BI19] El catálogo exige los códigos de token y niega los otros tres

- **DADO** `ProblemCatalogCoverageTest` actualizado
- **CUANDO** se ejecuta con el catálogo real, con un catálogo sin la entrada es-HN de `token-expired` y
  con un catálogo que añade `authentication-failed`
- **ENTONCES** pasa con el real y falla en los otros dos

#### Escenario: [BI20] El enumerador distingue rutas públicas y autenticadas

- **DADO** `PublicRouteAllowListTest` con el enumerador de rutas actualizado
- **CUANDO** se ejecuta con el código real, con un controlador de prueba que responde `200` sin credencial
  fuera de la lista pública y con otro que figura en la lista autenticada pero responde `200` sin
  credencial
- **ENTONCES** pasa con el código real y falla en los dos controladores, señalando cada ruta

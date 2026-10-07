# Delta para Integridad de la construcción

- **Estado:** pendiente de aprobación del propietario del producto
- **Cambio:** `session-tokens-and-web-layer` (F0, cambio 7, parte 4b, primer cambio S1 de tres)

**Convenciones de este delta.**

- Los escenarios nuevos se numeran `BI01` en adelante. Los que se conservan de la especificación vigente
  no llevan número.
- Una regla de construcción con «convención de dos mitades» DEBE tener un fixture permanente que la viole
  a propósito y una comprobación de que el código de producción real no falla por esa causa y evalúa
  clases reales, no un conjunto vacío.

## ADDED Requirements

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
de verificación a un catálogo cerrado que es exactamente `reuse_detected` y `password_change` y que NO
DEBE incluir `admin_revoke`, `logout` ni ningún motivo sin productor en este cambio. Un token DEBE poder
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

- **DADO** la tabla de familias ya migrada
- **CUANDO** se intenta insertar una familia revocada con el motivo `admin_revoke`, otra con el motivo
  `logout`, y otras con los motivos `reuse_detected` y `password_change`
- **ENTONCES** las dos primeras se rechazan por la restricción de verificación y las otras dos se
  aceptan
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

### Requisito: Permisos de acceso a las tablas de familias y tokens de refresco por rol de base de datos y por columna

El sistema DEBE conceder sobre cada una de las dos tablas nuevas, exactamente y sin uno más ni uno menos
(`docs/03-seguridad.md` §6.1): a `confia_admin_app`, `SELECT` e `INSERT` y `UPDATE` **solo sobre las
columnas que cambian de estado**, y **ningún `DELETE`**, porque las filas se retienen hasta que el
cambio 9 decida la purga. Las columnas actualizables DEBEN ser, en la familia, el último uso y la
revocación (instante y motivo); en el token, el consumo (instante y huella del agente de usuario de
quien lo presentó), el sucesor y el valor cifrado del sucesor. `SELECT ... FOR UPDATE` sobre las dos
tablas DEBE estar permitido a `confia_admin_app`, porque el bloqueo de la familia serializa la rotación.
El resto de las columnas (`token_hash`, los vencimientos, la huella del agente de usuario, la IP inicial,
`institution_id` y los identificadores) DEBEN ser inmutables para el rol de aplicación. A
`confia_portal_app`, **ningún privilegio**; a `confia_readonly`, solo `SELECT`, sujeto a la misma
política de fila; a `confia_backup`, el alcance de `pg_read_all_data` que ya tiene; a `confia_owner`,
ningún privilegio adicional a los de propietario del esquema, sin el atributo `BYPASSRLS`. El sistema
DEBE extender `RolePrivilegeMatrixIT` con las filas de las dos tablas para los cinco roles, incluido el
nivel de columna del `UPDATE`.

#### Escenario: [BI07] La matriz de privilegios cubre las dos tablas para los cinco roles

- **DADO** `RolePrivilegeMatrixIT` extendida con las filas de las dos tablas nuevas, con el nivel de
  columna del `UPDATE`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** confirma exactamente los privilegios de este requisito para cada uno de los cinco roles,
  sin uno más ni uno menos

#### Escenario: [BI08] `confia_admin_app` no puede borrar en ninguna de las dos tablas

- **DADO** el rol `confia_admin_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT` y `UPDATE` de una columna concedida sobre cada tabla, y después
  intenta `DELETE`
- **ENTONCES** las tres primeras operaciones se permiten y el `DELETE` se rechaza por falta de privilegio
- **Y** `SELECT ... FOR UPDATE` sobre cada tabla se permite

#### Escenario: [BI09] El `UPDATE` fuera de las columnas concedidas se rechaza

- **DADO** el rol `confia_admin_app` con el contexto de su institución y una fila en cada tabla
- **CUANDO** intenta actualizar `token_hash`, `expires_at`, `absolute_expires_at`, la huella del agente
  de usuario, la IP inicial e `institution_id`
- **ENTONCES** cada intento se rechaza por falta de privilegio sobre la columna

#### Escenario: [BI10] `confia_portal_app` no tiene ningún privilegio

- **DADO** el rol `confia_portal_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT`, `UPDATE` o `DELETE` sobre cualquiera de las dos tablas
- **ENTONCES** las cuatro operaciones se rechazan por falta de privilegio

### Requisito: Ninguna clase fuera de `identity` y `shared.security` usa utilidades de firma ni de hash

El sistema DEBE romper la construcción si una clase de producción fuera de `com.confia.identity` y
`com.confia.shared.security` (incluidos los subpaquetes de ambos) usa utilidades de firma o de hash
—`java.security.Signature`, `java.security.MessageDigest`, `java.security.KeyFactory`,
`java.security.KeyPairGenerator`, `javax.crypto.Mac`, `javax.crypto.KeyGenerator` o cualquier clase de
`org.bouncycastle`— (ADR-0005, verificación 13). Quedan fuera del alcance de la regla, y su Javadoc DEBE
decirlo, `javax.crypto.Cipher` (el cifrado de ADR-0023) y `SecureRandom`. La regla NO DEBE tener lista de
permitidos ni inventario de excepciones: el único uso preexistente fuera de los dos paquetes, el SHA-256
de la verificación de la cadena de auditoría, DEBE moverse a una utilidad de `shared.security`, con el
mismo resultado byte a byte (requisito «Ninguna regla se desactiva sin un ADR»). La regla DEBE tener su
propio fixture permanente con la convención de dos mitades.

#### Escenario: [BI11] Fixture con una clase de `shared.web` que calcula un hash

- **DADO** un fixture de prueba permanente en el que una clase de `com.confia.shared.web` invoca
  `MessageDigest.getInstance`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando la clase y la utilidad prohibida

#### Escenario: [BI12] El código real de los dos paquetes permitidos y del resto no falla

- **DADO** el código de producción real
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla pasa evaluando clases reales de `identity` y de `shared.security` y del resto de
  los paquetes, sin excepción de conjunto vacío

#### Escenario: [BI13] El único uso preexistente se movió a `shared.security` y la regla no tiene lista de permitidos

- **DADO** el código de producción real, donde la verificación de la cadena de auditoría de
  `com.confia.shared.audit` calculaba un SHA-256 por su cuenta
- **CUANDO** se ejecuta la regla y las pruebas existentes de la cadena de auditoría, sin cambios
- **ENTONCES** la clase de `shared.audit` ya no depende de `java.security.MessageDigest`, el cálculo vive
  en `shared.security`, y las pruebas de la cadena de auditoría pasan con el mismo resultado byte a byte
- **Y** la regla pasa sin ninguna excepción, lista de permitidos ni inventario de excepciones

#### Escenario: [BI38] La regla cubre las seis utilidades y deja fuera el cifrado y el generador aleatorio

- **DADO** fixtures permanentes fuera de `identity` y `shared.security` que usan, cada uno,
  `java.security.Signature`, `java.security.KeyFactory`, `java.security.KeyPairGenerator`,
  `javax.crypto.Mac`, `javax.crypto.KeyGenerator` y una clase de `org.bouncycastle`, y otros dos que usan
  `javax.crypto.Cipher` y `SecureRandom`
- **CUANDO** se ejecuta la regla
- **ENTONCES** falla sobre los seis primeros y no falla sobre los dos últimos

### Requisito: Ninguna clase de la capa `web` depende del códec, del verificador, del anillo de claves ni del emisor de tokens

El sistema DEBE romper la construcción si una clase de un paquete `web` de producción (el de un módulo de
negocio o `com.confia.shared.web`), salvo el filtro de autenticación del borde y la configuración de la
cadena administrativa que lo construye, depende del códec JWS, del verificador, del anillo de claves o
del emisor de tokens, de modo que ningún controlador interprete tokens. Los controladores DEBEN obtener al actor del principal ya autenticado. La regla DEBE tener su
propio fixture permanente con la convención de dos mitades.

#### Escenario: [BI14] Fixture con un controlador que verifica un token

- **DADO** un fixture de prueba permanente con una clase de un paquete `web` que depende del verificador
  de tokens
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando la clase y la dependencia prohibida

#### Escenario: [BI15] Código de producción sin dependencias prohibidas

- **DADO** el código de producción real, incluido el controlador de la sesión actual
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla pasa evaluando clases reales de los paquetes `web`, y solo el filtro de
  autenticación del borde y la configuración de la cadena administrativa que lo construye dependen del
  verificador

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
- **Y** las reglas de ruta admiten `permitAll`, `authenticated` y `denyAll`, y siguen prohibiendo reglas por
  rol, permiso o MFA

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

### Requisito: Los beans de tokens, claves y sesiones solo viven en el proceso administrativo, y su lista de permitidos se edita en el mismo pull request

El contexto del proceso administrativo DEBE contener el verificador y el emisor de tokens, el anillo de
claves, el adaptador de `SessionValidity`, el adaptador de `CurrentInstitutionProvider`, el controlador de
la sesión actual, los casos de uso y los repositorios de familias, y todos DEBEN pertenecer a su lista de
permitidos. El filtro de autenticación NO DEBE ser un bean del contexto (el contenedor de servlets
registraría todo bean de tipo filtro y lo ejecutaría dos veces, una de ellas fuera de la cadena): la
cadena administrativa DEBE construirlo y añadirlo ella misma, y por eso el paquete del filtro no aporta
ningún bean ni línea a la lista de permitidos. El contexto del portal y el del trabajador NO DEBEN
contener ninguno de esos beans, y la lista de prohibidos nominales de ambos DEBE incluir el paquete de los
tokens y las claves de firma. Toda configuración pública nueva de `identity` y
toda línea nueva de la lista de permitidos del proceso administrativo DEBEN llegar en el mismo pull
request (ADR-0024).

#### Escenario: [BI21] El proceso administrativo registra el cableado de sesión

- **DADO** el proceso administrativo arrancado por su camino de producción con una clave generada por la
  prueba
- **CUANDO** se inspecciona su contexto
- **ENTONCES** contiene el verificador, el emisor, el anillo de claves y el adaptador de
  `SessionValidity`, todos dentro de su lista de permitidos, y la verificación evalúa beans reales
- **Y** el contexto no contiene ningún bean del filtro de autenticación, y una petición autenticada lo
  atraviesa exactamente una vez (un contador de invocaciones en un doble del verificador registra una)

#### Escenario: [BI22] El portal y el trabajador no contienen ningún bean de sesión

- **DADO** los procesos del portal y del trabajador arrancados por su camino de producción
- **CUANDO** se inspecciona cada contexto
- **ENTONCES** ninguno contiene el verificador, el emisor, el anillo de claves, el filtro de
  autenticación ni repositorios de familias
- **Y** el paquete de los tokens y de las claves de firma figura entre los prohibidos nominales de los
  dos procesos, con el motivo de ADR-0005 (verificación 14)

#### Escenario: [BI23] Un bean de sesión sin línea en la lista rompe la construcción

- **DADO** el paquete de un bean de sesión nuevo sin su línea en la lista de permitidos del proceso
  administrativo
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el proceso, el bean y su paquete

### Requisito: La instantánea del documento administrativo declara la primera operación de producción

El sistema DEBE aprobar, con una actualización explícita y visible en el diff del pull request, la
instantánea del documento OpenAPI administrativo con exactamente una operación, `GET
/api/v1/auth/sessions/current`, con el esquema de seguridad `bearerAuth` (HTTP `bearer`, formato `JWT`) y
exactamente las respuestas `200`, `401` y `500`, las dos últimas con `application/problem+json` y el
esquema de Problem Details. El esquema `ProblemDetail` DEBE declarar el miembro `errors` (arreglo opcional
de objetos con `field` y `reason`). El documento NO DEBE contener respuestas genéricas que el traductor
global añadiría y que no estén aprobadas en la instantánea (la generación automática de respuestas
genéricas queda desactivada y cada respuesta se declara de forma explícita en la operación). `packages/contracts` DEBE regenerarse desde la instantánea y las
aplicaciones web DEBEN compilar contra él. La instantánea del portal DEBE seguir sin ninguna operación.

#### Escenario: [BI24] La operación y sus respuestas coinciden con la instantánea

- **DADO** la instantánea aprobada con la operación de la sesión actual
- **CUANDO** se ejecuta `OpenApiContractSnapshotTest`
- **ENTONCES** el documento generado coincide, declara `200`, `401` y `500` para la operación, el
  esquema de seguridad `bearerAuth` y ninguna otra respuesta (en particular, ni `403` ni `429`)
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

### Requisito: Las pruebas de arranque `*Test` siguen sin Docker con la verificación de claves activa, y ninguna clave privada se versiona

Las pruebas `*Test` que arrancan los tres procesos DEBEN seguir ejecutándose sin Docker, sin PostgreSQL y
sin Testcontainers con la verificación de claves de arranque activa. Las claves de firma de las pruebas
DEBEN generarse al ejecutar la prueba, en el árbol de pruebas, y NO DEBE existir ninguna clave de firma
del sistema versionada en el repositorio (`CLAUDE.md`, regla 13). Tampoco DEBE versionarse ninguna clave
privada de terceros, ni siquiera la del vector público del Apéndice A.4 de RFC 8037 (decisión del
propietario del 2026-10-06): del vector solo se versionan la clave pública, la entrada de firma y la firma
publicada, en un recurso de prueba que cita el RFC.

#### Escenario: [BI28] Arranque de los tres procesos sin Docker y con la verificación activa

- **DADO** una máquina sin Docker ni PostgreSQL
- **CUANDO** se ejecutan las pruebas `*Test` de arranque de los tres procesos
- **ENTONCES** todas pasan, ninguna es una prueba `*IT` ni usa Testcontainers, y la de administración
  arranca con una clave generada por la prueba

#### Escenario: [BI29] Ninguna clave de firma del sistema está versionada

- **DADO** el repositorio con todas sus pruebas y recursos
- **CUANDO** se ejecuta el escaneo de secretos de integración continua
- **ENTONCES** no encuentra ninguna clave privada Ed25519 ni material de clave de firma del sistema, sin
  excepciones: el recurso del vector RFC 8037 A.4 contiene solo la clave pública, la entrada de firma y la
  firma publicada
- **Y** ninguna propiedad de ningún perfil, archivo de configuración ni argumento de prueba versionado
  contiene un valor para las propiedades de las claves de firma

### Requisito: El códec JWS, el anillo de claves y el verificador tienen una puerta de mutación mínima de ochenta por ciento

El sistema DEBE ejecutar pruebas de mutación con PIT sobre las clases del códec JWS, del anillo de claves
y del verificador de tokens, con umbral mínimo de ochenta y con la misma selección de perfil por rama que
rige para `kernel` y para el paquete `domain`: en la rama principal, el perfil `mutation-gate` DEBE
romper la construcción si la puntuación cae por debajo del umbral; en cualquier otra rama, el perfil
`mutation-report` solo DEBE informarla.

#### Escenario: [BI30] Mutación baja del códec en la rama principal

- **DADO** un empuje a la rama principal con el perfil `mutation-gate` activo
- **CUANDO** la puntuación de mutación del códec resulta menor a 80
- **ENTONCES** `./mvnw verify` falla señalando la puntuación medida

#### Escenario: [BI31] Mutación baja del códec en una rama de trabajo

- **DADO** un empuje a una rama `change/**` con el perfil `mutation-report` activo
- **CUANDO** la puntuación de mutación del códec resulta menor a 80
- **ENTONCES** `./mvnw verify` no falla por esta causa y el reporte queda visible en la integración
  continua

#### Escenario: [BI32] Mutar la comparación de la cabecera exacta o la selección por `kid` hace fallar una prueba

- **DADO** el códec con la comparación de la cabecera recibida con la cabecera canónica de su `kid`, o la
  selección de la clave por `kid`, mutadas por PIT
- **CUANDO** se ejecutan sus pruebas de ataque
- **ENTONCES** al menos una prueba falla por cada mutación, de modo que ninguna sobrevive

## MODIFIED Requirements

### Requisito: Spring Security se usa solo como cadena de filtros, sin servidor de recursos OAuth2

El sistema DEBE incorporar Spring Security únicamente como cadena de filtros. El camino de clases
de `apps/api` NO DEBE contener `spring-boot-starter-oauth2-resource-server` ni
`spring-security-oauth2-resource-server`, porque la firma de tokens es un JWS propio sobre el JDK
(decisión DA-2 del propietario) y Spring Security no firma con EdDSA, y NO DEBE contener ninguna
biblioteca JOSE o JWT de terceros (Nimbus JOSE, Tink, jjwt, java-jwt). La dependencia de Spring
Security DEBE pasar `dependencyConvergence` y el escaneo de vulnerabilidades de integración
continua ya exigidos por esta capacidad, sin excepciones nuevas.
(Previously: la firma de tokens era una decisión pendiente de `session-tokens-and-web-layer` y no se
prohibían bibliotecas JOSE de terceros.)

#### Escenario: Se añade el servidor de recursos OAuth2

- **DADO** una dependencia de `spring-boot-starter-oauth2-resource-server` en `apps/api`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando la dependencia prohibida

#### Escenario: La dependencia de Spring Security converge

- **DADO** la dependencia de `spring-boot-starter-security` tal como la entrega este cambio
- **CUANDO** se ejecuta `./mvnw verify` y el escaneo de integración continua
- **ENTONCES** `dependencyConvergence` pasa sin exclusiones nuevas y el escaneo no reporta
  vulnerabilidad alta o crítica atribuible a Spring Security

#### Escenario: [BI33] Se añade una biblioteca JOSE o JWT de terceros

- **DADO** una dependencia de Nimbus JOSE, Tink, jjwt o java-jwt en `apps/api`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando la dependencia prohibida
- **Y** el árbol de dependencias de este cambio no añade ninguna dependencia Maven nueva de firma

### Requisito: Ningún tipo expuesto por la capa `web` es una entidad de dominio ni un registro de jOOQ

El sistema DEBE romper la construcción si un DTO de un paquete `web`, o la firma pública de un
controlador, expone un tipo del paquete `domain` o un tipo del código generado de jOOQ (`Record`,
`TableRecord` o POJO generado). Toda salida DEBE pasar por un DTO explícito (`CLAUDE.md`, regla 10).
La regla DEBE tener su propio fixture permanente con la convención de dos mitades. La mitad que
evalúa la firma pública de los controladores NO DEBE declarar excepción de conjunto vacío desde que un
paquete `web` de producción contiene un controlador (el de la sesión actual, en `identity.web`): la
entrada de `EmptyShouldExceptionInventoryTest` se retira en este cambio. La mitad que evalúa los
registros y campos de los paquetes `web` NO DEBE declarar excepción, porque `shared.web` ya contiene
registros reales (`ProblemBody`, `FieldViolation`).
(Previously: la mitad de la firma de controladores declaraba su excepción de conjunto vacío en
`EmptyShouldExceptionInventoryTest`, citando a `session-tokens-and-web-layer` como dueño, mientras
ningún paquete `web` de producción tuviera un controlador.)

#### Escenario: Fixture con un controlador que devuelve un registro de jOOQ

- **DADO** un fixture de prueba permanente con un método de controlador cuyo tipo de retorno es un
  `Record` de jOOQ
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando el método y el tipo expuesto

#### Escenario: Fixture con un DTO que contiene un tipo de `domain`

- **DADO** un fixture de prueba permanente con un DTO de `web` cuyo campo es un tipo del paquete
  `domain`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando el DTO y el campo

#### Escenario: [BI34] La regla de la firma de controladores evalúa un controlador real

- **DADO** el controlador de la sesión actual en `com.confia.identity.web`
- **CUANDO** se ejecuta la regla de la firma pública de controladores
- **ENTONCES** la regla pasa evaluando ese controlador y no figura ninguna excepción de conjunto vacío
  para ella en `EmptyShouldExceptionInventoryTest`

#### Escenario: [BI35] El DTO de la sesión actual no expone tipos de `domain` ni de jOOQ

- **DADO** el DTO que devuelve la sesión actual
- **CUANDO** se ejecuta la regla de los registros y campos de los paquetes `web`
- **ENTONCES** la regla pasa y ningún campo del DTO es un tipo del paquete `domain` ni del código
  generado de jOOQ

#### Escenario: La regla sobre registros de `web` evalúa clases reales

- **DADO** el código de producción real de `com.confia.shared.web`
- **CUANDO** se ejecuta la regla de los registros y campos de los paquetes `web`
- **ENTONCES** la regla pasa evaluando registros reales, como `ProblemBody` y `FieldViolation`, y no
  figura ninguna excepción de conjunto vacío para ella

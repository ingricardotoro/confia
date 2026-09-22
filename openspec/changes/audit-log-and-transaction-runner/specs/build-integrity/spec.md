# Delta para Integridad de la construcción

## MODIFIED Requirements

### Requisito: Transacciones confinadas al componente único de `shared/security`

El sistema DEBE romper la construcción si una clase de un módulo de negocio abre su propia
transacción de base de datos (por ejemplo, con `@Transactional` de Spring o con un control
transaccional manual sobre la conexión JDBC), en lugar de delegarla en el componente transaccional
único de `shared/security` (ADR-0015, regla 7). Ese componente es el único punto que abre
transacciones: recibe el nivel de aislamiento requerido (`READ COMMITTED` por defecto,
`SERIALIZABLE` donde ADR-0010 lo exija), establece el contexto de seguridad a nivel de fila como
primera sentencia de la transacción, con parámetros vinculados (`docs/03-seguridad.md` §6.2), y
reintenta un número acotado de veces ante errores de serialización o de interbloqueo. El sistema
DEBE además confirmar, con una aserción positiva independiente del rechazo anterior, que el
paquete `com.confia.shared.security` contiene al menos una clase de producción que abre
transacciones a través de la API de transacciones (por ejemplo `@Transactional`,
`TransactionTemplate` o `PlatformTransactionManager`). Sin esa mitad positiva, la regla seguiría
en verde aunque el componente real se implementara por otro mecanismo, sin custodiar nada.
(Previously: la regla solo rechazaba el fixture de violación, actuando como guarda preventiva
sobre un paquete `shared/security` que todavía no existía en esa parte del cambio; no afirmaba en
positivo que el componente transaccional real usara la API de transacciones.)

#### Escenario: Adaptador abre su propia transacción

- **DADO** un adaptador jOOQ hipotético anotado con `@Transactional` o que invoca directamente el
  control transaccional de la conexión JDBC
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando la clase infractora

#### Escenario: Adaptador sin control transaccional propio

- **DADO** el adaptador jOOQ de `InstitutionRepository`, que no abre ninguna transacción propia
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla no falla por esta causa

#### Escenario: `shared/security` no contiene ninguna clase que use la API de transacciones

- **DADO** que el paquete `com.confia.shared.security` no contiene ninguna clase que invoque
  `@Transactional`, `TransactionTemplate` ni `PlatformTransactionManager`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la aserción positiva de la regla falla, distinta de la aserción negativa de los
  escenarios anteriores, que solo custodia el resto del código

#### Escenario: El componente transaccional único satisface la aserción positiva

- **DADO** el componente transaccional único que este cambio entrega en
  `com.confia.shared.security`, que abre transacciones a través de la API de transacciones
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la aserción positiva de la regla pasa, confirmando que el paquete custodia
  producción real y no un paquete vacío

## ADDED Requirements

### Requisito: Contexto de sesión, nivel de aislamiento y reintento acotado del componente transaccional único

El componente transaccional único de `shared/security` (ADR-0015, regla 7 y cumplimiento 7;
`docs/03-seguridad.md` §6.2) DEBE ejecutar los cuatro `set_config('app.actor_id', ..., true)`,
`set_config('app.actor_kind', ..., true)`, `set_config('app.institution_id', ..., true)` y
`set_config('app.request_id', ..., true)` como la primera sentencia de cada transacción que abre,
con parámetros vinculados y **jamás** con `SET SESSION`. DEBE recibir el nivel de aislamiento
requerido por el caso de uso y aplicarlo a la transacción real contra PostgreSQL: `READ COMMITTED`
por defecto, `SERIALIZABLE` donde ADR-0010 lo exija. DEBE reintentar la ejecución del caso de uso
un número acotado y explícito de veces cuando la transacción falla por un error de serialización o
de interbloqueo, y DEBE propagar el error original, sin reintentar de nuevo, una vez agotado ese
límite. Este requisito no duplica la prohibición de que otro código abra transacciones: esa
prohibición ya queda exigida por el requisito «Transacciones confinadas al componente único de
`shared/security`»; este requisito exige en cambio lo que el propio componente único hace una vez
que es el único que abre transacciones.

#### Escenario: El contexto se fija antes de la primera consulta del caso de uso

- **DADO** filas confirmadas de dos instituciones distintas en una tabla con seguridad a nivel de
  fila por institución
- **CUANDO** el componente ejecuta, con el contexto de la primera institución, un caso de uso que
  consulta esa tabla
- **ENTONCES** la consulta devuelve exactamente las filas de la primera institución y ninguna de la
  segunda, un resultado que solo es posible si los cuatro `set_config` ya se ejecutaron antes de
  esa consulta: si el contexto no estuviera fijado a tiempo, la política denegaría por defecto y la
  consulta devolvería cero filas en vez de las filas correctas

#### Escenario: Ningún contexto sobrevive a la transacción sobre una conexión reutilizada del pool

- **DADO** dos transacciones consecutivas ejecutadas a través del componente sobre la misma
  conexión reutilizada del pool, cada una con el contexto de una institución distinta
- **CUANDO** la segunda transacción ejecuta su caso de uso
- **ENTONCES** ve únicamente las filas de su propia institución, sin ningún rastro del contexto de
  la primera transacción; ese resultado fallaría si el componente usara `SET SESSION` en vez de
  `set_config(..., true)`, porque `SET SESSION` sobrevive a la transacción y contaminaría la
  conexión reutilizada

#### Escenario: El nivel de aislamiento por defecto es `READ COMMITTED` en la transacción real

- **DADO** un caso de uso invocado sin exigir un nivel de aislamiento distinto del de partida
- **CUANDO** el componente abre la transacción y, dentro de ella, se consulta el nivel de
  aislamiento efectivo directamente contra PostgreSQL (por ejemplo con
  `current_setting('transaction_isolation')`)
- **ENTONCES** el valor leído de la base de datos real es `read committed`, no solo un valor
  declarado en la configuración de la aplicación

#### Escenario: El componente aplica `SERIALIZABLE` cuando ADR-0010 lo exige

- **DADO** un caso de uso invocado con el nivel de aislamiento que ADR-0010 exige
- **CUANDO** el componente abre la transacción y, dentro de ella, se consulta el nivel de
  aislamiento efectivo directamente contra PostgreSQL
- **ENTONCES** el valor leído de la base de datos real es `serializable`

#### Escenario: El reintento tiene éxito dentro del límite acotado

- **DADO** dos transacciones reales y confirmadas, ejecutadas a través del componente, construidas
  para forzar un conflicto de serialización real sobre el mismo recurso
- **CUANDO** el componente ejecuta el caso de uso de la transacción que pierde el conflicto
- **ENTONCES** el componente reintenta automáticamente y la operación termina confirmada dentro del
  número acotado de reintentos, sin que el llamador observe el error de serialización original

#### Escenario: El reintento se agota y el error se propaga en vez de reintentarse indefinidamente

- **DADO** un caso de uso, ejecutado a través del componente con transacciones reales, cuya
  operación fuerza determinísticamente un error de serialización en cada intento, más veces que el
  límite acotado de reintentos
- **CUANDO** el componente lo ejecuta
- **ENTONCES**, tras agotar el número acotado de reintentos, el componente propaga el error de
  serialización original al llamador en vez de reintentar una vez más

### Requisito: Nomenclatura obligatoria `*IT` para toda subclase de `PostgresIntegrationTest` (deuda W3)

El sistema DEBE romper la construcción si una clase que extiende `PostgresIntegrationTest`, o
cualquiera de sus variantes (`TransactionalPostgresIntegrationTest`,
`CommittingPostgresIntegrationTest`), no termina su nombre en el sufijo `IT`. Esta regla necesita
su propio fixture de prueba permanente que la viole a propósito, con el mismo patrón de dos
mitades ya establecido para las demás reglas de esta capacidad: una mitad que demuestra que una
violación real rompe la construcción, y otra que demuestra que el código de prueba real,
correctamente nombrado, no falla por esta causa.

#### Escenario: Fixture permanente nombrado con el sufijo `Test` en vez de `IT`

- **DADO** un fixture de prueba permanente que extiende `PostgresIntegrationTest` o una de sus
  variantes, y se nombra con el sufijo `Test`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla señalando la clase infractora y el sufijo esperado

#### Escenario: Las subclases reales están todas nombradas con el sufijo `IT`

- **DADO** las subclases reales de `PostgresIntegrationTest` y de sus variantes que este cambio y
  los anteriores entregan
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla no falla por esta causa, evaluando las clases de prueba reales del árbol y
  no un conjunto vacío

### Requisito: Puertas de catálogo y de privilegios extendidas a `shared_audit_log`

El sistema DEBE extender `RolePrivilegeMatrixIT` con las filas de `shared_audit_log` para los
cinco roles de `docs/03-seguridad.md` §6.1, y DEBE verificar sobre esa tabla, sin lista de
exclusión, las mismas puertas genéricas de `MultiTenantSchemaIT` que ya verifican
`organization_institution`: `institution_id NOT NULL`, seguridad a nivel de fila habilitada y
forzada, y toda restricción única con el discriminador de institución. El sistema DEBE además
cubrir un caso que el patrón existente no necesitó: el rechazo de `UPDATE` y `DELETE` sobre una
tabla de negocio **al propietario del esquema**, que `organization_institution` no requiere
porque no tiene disparadores de solo inserción.

#### Escenario: La matriz de privilegios cubre `shared_audit_log` para los cinco roles

- **DADO** `RolePrivilegeMatrixIT` extendida con las filas de `shared_audit_log`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** confirma exactamente los privilegios de `docs/03-seguridad.md` §6.1 y §12.3 sobre
  `shared_audit_log` para cada uno de los cinco roles, sin uno más ni uno menos

#### Escenario: Las puertas genéricas de esquema pasan sobre `shared_audit_log` sin lista de exclusión

- **DADO** `shared_audit_log` ya migrada, con su clave primaria compuesta
  `(institution_id, id)` y su seguridad a nivel de fila habilitada y forzada
- **CUANDO** se ejecutan las pruebas de catálogo e inventario de `MultiTenantSchemaIT`
- **ENTONCES** todas pasan sobre `shared_audit_log` sin necesitar ninguna excepción ni entrada en
  una lista de exclusión

#### Escenario: Rechazo de `UPDATE` y `DELETE` al propietario del esquema, caso nuevo del patrón

- **DADO** una prueba de integración conectada como `confia_owner`, el propietario del esquema
- **CUANDO** intenta `UPDATE` o `DELETE` sobre una fila de `shared_audit_log`
- **ENTONCES** el motor rechaza ambas operaciones por el disparador de solo inserción, un caso que
  `RolePrivilegeMatrixIT` no necesitó cubrir para `organization_institution`

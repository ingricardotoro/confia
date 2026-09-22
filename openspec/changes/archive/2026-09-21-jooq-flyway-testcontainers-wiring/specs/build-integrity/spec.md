# Delta para Integridad de la construcción

## ADDED Requirements

### Requisito: Generación del código de jOOQ desde las migraciones de Flyway

El sistema DEBE generar el código de acceso a datos de jOOQ en la fase `generate-sources` de la
construcción, ejecutando las migraciones de Flyway sobre un PostgreSQL 18 temporal levantado solo
para ese propósito (ADR-0015, regla 2). El código generado NO DEBE comprometerse al repositorio:
DEBE regenerarse en cada construcción y quedar excluido de control de versiones. Una consulta de
jOOQ que referencia una columna o una tabla que una migración posterior elimina o renombra DEBE
romper la compilación del módulo, no fallar en tiempo de ejecución (ADR-0015, cumplimiento 6).

#### Escenario: Consulta incompatible con el esquema no compila

- **DADO** un adaptador jOOQ que referencia una columna existente de una tabla migrada
- **CUANDO** una migración posterior elimina o renombra esa columna y se ejecuta `./mvnw verify`
- **ENTONCES** la generación de código o la compilación del adaptador falla, y la construcción no
  llega a la fase de pruebas

#### Escenario: Generación exitosa con esquema compatible

- **DADO** las migraciones de Flyway y las consultas jOOQ del adaptador consistentes entre sí
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la fase `generate-sources` produce el código de jOOQ y la construcción continúa

#### Escenario: Código generado ausente del repositorio

- **DADO** un directorio de trabajo recién clonado, sin haber ejecutado la construcción
- **CUANDO** se inspecciona el árbol de control de versiones
- **ENTONCES** ninguna clase generada de jOOQ existe en el repositorio

### Requisito: jOOQ y el paquete generado confinados a `infrastructure`

El sistema DEBE romper la construcción si una clase fuera del paquete `infrastructure` de un
módulo importa un tipo de `org.jooq` o del paquete generado de jOOQ, que vive fuera de
`com.confia` (ADR-0015, cumplimiento 2; decisión D3 de la propuesta).

#### Escenario: Importación de jOOQ desde `application`

- **DADO** una clase de la capa `application` de un módulo de negocio
- **CUANDO** esa clase importa un tipo de `org.jooq` o del paquete generado
- **ENTONCES** `./mvnw verify` falla señalando la clase infractora

#### Escenario: Uso confinado a `infrastructure`

- **DADO** un adaptador en `infrastructure` que usa tipos de jOOQ y del paquete generado
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla no falla por esta causa

### Requisito: Propiedad de tablas generadas por prefijo de módulo

El sistema DEBE romper la construcción si un módulo de negocio usa una clase generada de jOOQ
cuyo nombre de tabla lleva el prefijo de un módulo distinto (ADR-0015, regla 3 y cumplimiento 4).

#### Escenario: Un módulo usa la tabla generada de otro módulo

- **DADO** la clase generada de jOOQ para la tabla `organization_institution`
- **CUANDO** un adaptador de un módulo distinto de `organization` la importa y la usa
- **ENTONCES** `./mvnw verify` falla señalando el cruce de propiedad de tabla

#### Escenario: Un módulo usa solo sus propias tablas generadas

- **DADO** el adaptador de `organization.infrastructure` usando la clase generada de
  `organization_institution`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla no falla por esta causa

### Requisito: Nombres de tabla con el prefijo de su módulo, salvo el catálogo cerrado

El sistema DEBE mantener una prueba de catálogo de esquema que verifique que el nombre de toda
tabla de negocio comienza con el prefijo `<módulo>_` de su módulo propietario (ADR-0015, regla 3),
salvo las tres tablas técnicas del catálogo cerrado de ADR-0017, que están exceptuadas del prefijo.
La tabla raíz de `organization` NO está exceptuada del prefijo: su nombre DEBE llevar el prefijo
`organization_`, como cualquier tabla de negocio (ADR-0017, catálogo cerrado, nota bajo la tabla).

#### Escenario: Tabla de negocio sin el prefijo de su módulo

- **DADO** una tabla de negocio hipotética nombrada sin el prefijo de su módulo propietario
- **CUANDO** se ejecuta la prueba de catálogo de esquema
- **ENTONCES** la prueba falla señalando el nombre de la tabla y el módulo esperado

#### Escenario: Tabla raíz con el prefijo de `organization`

- **DADO** la tabla `organization_institution`
- **CUANDO** se ejecuta la prueba de catálogo de esquema
- **ENTONCES** la prueba confirma el prefijo `organization_` y no la exceptúa por ser la tabla raíz

#### Escenario: Tabla técnica exceptuada del prefijo

- **DADO** `flyway_schema_history`, miembro del catálogo cerrado de cuatro nombres
- **CUANDO** se ejecuta la prueba de catálogo de esquema
- **ENTONCES** la prueba no exige prefijo de módulo para esa tabla, por pertenecer al catálogo
  cerrado

### Requisito: Prohibición del SQL plano de jOOQ fuera de la lista aprobada

El sistema DEBE romper la construcción si una clase invoca un método de jOOQ que reciba una
cadena de SQL plano (por ejemplo `DSLContext.fetch(String)` o `resultQuery(String)`), salvo que la
clase figure en una lista explícita y cerrada de reportes aprobados (ADR-0015, regla 9 y
cumplimiento 5). En esta parte del cambio, esa lista está vacía porque ningún reporte existe
todavía.

#### Escenario: SQL plano fuera de la lista aprobada

- **DADO** una clase de `infrastructure` que invoca un método de jOOQ con una cadena de SQL plano
  y no figura en la lista aprobada
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando la clase infractora

#### Escenario: Ausencia total de SQL plano en esta parte del cambio

- **DADO** que la lista aprobada está vacía y el adaptador jOOQ de esta parte no usa SQL plano
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla pasa evaluando el código de producción real del adaptador, sin excepción de
  conjunto vacío

### Requisito: Transacciones confinadas al componente único de `shared/security` (guarda preventiva)

El sistema DEBE romper la construcción si una clase de un módulo de negocio abre su propia
transacción de base de datos (por ejemplo, con `@Transactional` de Spring o con un control
transaccional manual sobre la conexión JDBC), en lugar de delegarla en el componente transaccional
único de `shared/security` (ADR-0015, regla 7). Ese componente todavía no existe en esta parte del
cambio; la regla actúa aquí como guarda preventiva para que el primer adaptador jOOQ no abra su
propia transacción antes de que el componente exista.

#### Escenario: Adaptador abre su propia transacción

- **DADO** un adaptador jOOQ hipotético anotado con `@Transactional` o que invoca directamente el
  control transaccional de la conexión JDBC
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando la clase infractora

#### Escenario: Adaptador sin control transaccional propio

- **DADO** el adaptador jOOQ de `InstitutionRepository`, que no abre ninguna transacción propia
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla no falla por esta causa

### Requisito: Fuente única de la versión de PostgreSQL en la construcción

El sistema DEBE declarar la versión de la imagen de PostgreSQL 18 en un único lugar, consumido
tanto por el complemento de generación de código de jOOQ como por `PostgresIntegrationTest`
(ADR-0015, cumplimiento 9, en su alcance disponible en esta parte del cambio).

#### Escenario: Declaración duplicada de la versión

- **DADO** la versión de PostgreSQL declarada por separado en el complemento de generación y en
  `PostgresIntegrationTest`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** una prueba dedicada de coherencia de versión falla señalando la divergencia entre
  ambas declaraciones

#### Escenario: Fuente única compartida

- **DADO** que ambos consumidores leen la misma propiedad de versión
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la prueba de coherencia de versión pasa

### Requisito: Pruebas `*IT.java` con Testcontainers dentro del presupuesto de 8 minutos

El sistema DEBE ejecutar toda clase `*IT.java` que requiera una base de datos real a través del
complemento Failsafe en integración continua, con un contenedor de PostgreSQL 18 por JVM de
prueba. La suite completa de `*IT.java` DEBE terminar en menos de 8 minutos, con el tiempo medido y
reportado (`docs/06-estrategia-de-testing.md` línea 68, §14.2).

#### Escenario: Clase de integración nombrada fuera de la convención

- **DADO** una clase de prueba que requiere un contenedor de PostgreSQL pero se nombra `*Test` en
  lugar de `*IT`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** Surefire la ejecuta sin el ciclo de vida de Failsafe, y la prueba falla por falta de
  conexión a la base de datos, evidenciando el incumplimiento de la convención

#### Escenario: Suite de integración excede el presupuesto

- **DADO** la suite completa de clases `*IT.java` ejecutándose en integración continua
- **CUANDO** el tiempo total medido supera 8 minutos
- **ENTONCES** la integración continua reporta el incumplimiento del presupuesto y el corte se
  considera fallido

#### Escenario: Suite dentro del presupuesto

- **DADO** la suite completa de clases `*IT.java` con un contenedor por JVM de prueba
- **CUANDO** se ejecuta en integración continua
- **ENTONCES** termina por debajo de 8 minutos, con el tiempo medido reportado en el resultado de la
  construcción

### Requisito: Toda tabla de negocio lleva `institution_id NOT NULL`, salvo el catálogo cerrado por pertenencia

El sistema DEBE mantener una prueba de catálogo que consulte el esquema real y afirme que toda
tabla de negocio declara la columna `institution_id` como `NOT NULL`, salvo las tablas que
pertenecen al catálogo cerrado de cuatro nombres de ADR-0017 (`scheduled_tasks`,
`event_publication`, `flyway_schema_history` y la tabla raíz de `organization`). La prueba DEBE
verificarse como pertenencia a ese catálogo cerrado por nombre exacto, no como presencia de las
cuatro tablas, porque en esta parte del cambio solo existen dos de ellas.

#### Escenario: Tabla de negocio sin `institution_id`

- **DADO** una tabla de negocio hipotética sin la columna `institution_id` y cuyo nombre no
  coincide con ninguno de los cuatro del catálogo cerrado
- **CUANDO** se ejecuta la prueba de catálogo de esquema
- **ENTONCES** la prueba falla señalando el nombre de la tabla infractora

#### Escenario: Tabla raíz exceptuada correctamente por pertenencia

- **DADO** `organization_institution`, sin columna `institution_id` propia
- **CUANDO** se ejecuta la prueba de catálogo
- **ENTONCES** la prueba la reconoce como miembro del catálogo cerrado de cuatro nombres y no falla
  por su ausencia

#### Escenario: Tabla ajena al catálogo cerrado no exceptuada por accidente

- **DADO** una tabla sin `institution_id` cuyo nombre no coincide exactamente con ninguno de los
  cuatro nombres del catálogo cerrado
- **CUANDO** se ejecuta la prueba de catálogo
- **ENTONCES** la prueba falla, aunque esa tabla sea técnica, porque la pertenencia se decide por
  nombre exacto y no por criterio ad hoc

### Requisito: Seguridad a nivel de fila habilitada y forzada en cada tabla protegida

El sistema DEBE mantener una prueba de inventario que consulte `pg_class.relrowsecurity` y
`pg_class.relforcerowsecurity` para cada tabla protegida y afirme que ambas columnas son
verdaderas (ADR-0009, «Cumplimiento»; ADR-0017, regla 1).

#### Escenario: Tabla sin `FORCE ROW LEVEL SECURITY`

- **DADO** una tabla de negocio con `ENABLE ROW LEVEL SECURITY` pero sin `FORCE ROW LEVEL SECURITY`
- **CUANDO** se ejecuta la prueba de inventario
- **ENTONCES** la prueba falla señalando la tabla y la columna `relforcerowsecurity` en falso

#### Escenario: Tabla raíz con ambas banderas activas

- **DADO** `organization_institution` con `ENABLE` y `FORCE ROW LEVEL SECURITY`
- **CUANDO** se ejecuta la prueba de inventario
- **ENTONCES** la prueba pasa para esa tabla

### Requisito: Toda restricción única de una tabla de negocio incluye el discriminador de institución

El sistema DEBE mantener una prueba de catálogo que verifique que toda restricción `UNIQUE` de una
tabla de negocio incluye la columna `institution_id` como discriminador, salvo en la tabla raíz de
`organization`, donde la propia clave primaria actúa como su discriminador de institución
(ADR-0009, punto 2; ADR-0017, regla 1; decisión técnica 4 de la propuesta).

#### Escenario: Restricción única sin el discriminador

- **DADO** una restricción `UNIQUE` hipotética sobre una tabla de negocio que no incluye
  `institution_id`
- **CUANDO** se ejecuta la prueba de catálogo
- **ENTONCES** la prueba falla señalando la restricción infractora

#### Escenario: Clave primaria de la tabla raíz reconocida como su propio discriminador

- **DADO** la clave primaria `id` de `organization_institution`
- **CUANDO** se ejecuta la prueba de catálogo
- **ENTONCES** la prueba la reconoce explícitamente como discriminador de institución de esa tabla
  y no la marca como infractora

### Requisito: Código generado de jOOQ excluido de cobertura y de mutación

El sistema NO DEBE contar el código generado de jOOQ en la medición de cobertura de JaCoCo ni en
las pruebas de mutación de PIT, ni en el conteo de líneas de autor del presupuesto de revisión
(decisión técnica 2 de la propuesta; ADR-0015, regla 2).

#### Escenario: Cobertura global no penalizada por código generado sin pruebas propias

- **DADO** el código generado de jOOQ sin ninguna prueba propia que lo ejercite directamente
- **CUANDO** se ejecuta `./mvnw verify` y se mide la cobertura del módulo `app`
- **ENTONCES** el porcentaje de cobertura no decrece por la presencia de ese código generado

#### Escenario: Mutación no evaluada sobre código generado

- **DADO** el código generado de jOOQ
- **CUANDO** PIT ejecuta sus mutantes sobre el módulo `app`
- **ENTONCES** ningún mutante se genera sobre las clases del paquete generado

### Requisito: `infrastructure` deja de ser una capa opcional en la verificación de capas

El sistema DEBE eliminar la declaración `optionalLayer("Infrastructure")` de
`LayeredArchitectureTest`, su entrada correspondiente en `EmptyShouldExceptionInventoryTest`, y el
conteo de `optionalLayer(` en `SuppressionCitesAdrTest`, en el mismo cambio que introduce la
primera clase de producción en un paquete `infrastructure` (ADR-0020, alcance punto 3).
`optionalLayer("Web")` permanece sin modificar, con su condición intacta.

#### Escenario: Clase de `infrastructure` con dependencia inválida tras la retirada

- **DADO** que `optionalLayer("Infrastructure")` ya fue retirado y existe al menos una clase en
  `infrastructure`
- **CUANDO** esa clase importa el `domain` de otro módulo de negocio, violando la regla de capas
- **ENTONCES** `./mvnw verify` falla por esa violación, igual que fallaría en cualquier otra capa
  obligatoria

#### Escenario: Conteo de `SuppressionCitesAdrTest` tras la retirada

- **DADO** que la única capa opcional restante es `optionalLayer("Web")`
- **CUANDO** se ejecuta `SuppressionCitesAdrTest`
- **ENTONCES** el conteo de invocaciones a `optionalLayer(` es exactamente uno

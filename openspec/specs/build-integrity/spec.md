# Capacidad: Integridad de la construcción

- **Identificador:** build-integrity
- **Estado:** Borrador
- **Fase:** F0

## Propósito

Capacidad técnica: garantiza que las reglas de dependencia, capas y seguridad ya decididas en
los ADR de CONFIA rompan `./mvnw verify` cuando se violan, en local y en integración continua.
No define reglas de negocio nuevas; hace verificable lo ya decidido.

## Requisitos

### Requisito: Frontera de dominio entre módulos de negocio

El sistema DEBE romper la construcción si una clase de un módulo de negocio importa el `domain`
de otro módulo de negocio.

#### Escenario: Importación cruzada de dominio

- **DADO** dos módulos de negocio existentes
- **CUANDO** el módulo A importa una clase de `domain` del módulo B
- **ENTONCES** `./mvnw verify` falla por la importación prohibida

> **Escenario diferido.** El escenario «Cada módulo usa solo su propio dominio» (DADO dos módulos
> de negocio existentes, CUANDO ninguno importa el `domain` del otro, ENTONCES `./mvnw verify`
> termina en verde) no pertenece a este cambio: su premisa exige dos módulos de negocio y este
> cambio no crea ninguno. Se traslada a los cambios de F0 que introducen esos módulos:
> `institution-root-and-multitenancy-baseline` (cambio 4, primer módulo) y
> `staff-authentication-mfa-sessions` (cambio 7, segundo módulo, único que puede demostrarlo).
> Hallazgo W2 del informe de verificación de este cambio.

### Requisito: Pureza del módulo `kernel`

El módulo `kernel` NO DEBE depender de nada fuera del JDK; el sistema DEBE romper la
construcción si lo hace.

#### Escenario: `kernel` importa Spring

- **DADO** `kernel` sin dependencias externas al JDK
- **CUANDO** una clase de `kernel` importa un tipo de Spring
- **ENTONCES** la construcción falla antes de compilar `kernel`

#### Escenario: `kernel` solo usa el JDK

- **DADO** el mismo módulo
- **CUANDO** su código usa únicamente tipos del JDK
- **ENTONCES** `./mvnw verify` compila y valida `kernel` sin error

### Requisito: Dependencias de persistencia y de tareas prohibidas

El sistema NO DEBE permitir en `apps/api` a Hibernate, Jakarta Persistence, Spring Data JPA,
Spring Data JDBC, Quartz, JobRunr ni otra biblioteca de programación de tareas (ADR-0015,
ADR-0016). El controlador JDBC de PostgreSQL y el JDBC del JDK no están prohibidos: jOOQ y el
componente transaccional se apoyan en ellos.

#### Escenario: Se agrega Hibernate

- **DADO** el POM padre sin dependencias prohibidas
- **CUANDO** un módulo agrega Hibernate
- **ENTONCES** `./mvnw verify` falla señalando la dependencia prohibida

#### Escenario: Sin dependencias prohibidas

- **DADO** el mismo POM padre
- **CUANDO** ningún módulo declara una dependencia de la lista prohibida
- **ENTONCES** la construcción no falla por esta causa

### Requisito: Paquetes con nombre de capa técnica prohibidos

El sistema NO DEBE permitir un paquete `interface`, `interfaces`, `controllers`, `services`,
`repositories`, `entities`, `utils`, `helpers` o `common` en un módulo de negocio.

#### Escenario: Paquete `services`

- **DADO** un módulo sin paquetes de nombre prohibido
- **CUANDO** se agrega un paquete `services`
- **ENTONCES** `./mvnw verify` falla por el nombre prohibido

#### Escenario: Paquetes por capacidad de negocio

- **DADO** el mismo módulo
- **CUANDO** sus paquetes nombran capacidades de negocio, no capas técnicas
- **ENTONCES** la construcción no falla por esta causa

### Requisito: Reglas de capas y ausencia de ciclos

El sistema DEBE exigir `web` → `application` → `domain`, `infrastructure` implementando
puertos de `application`, y NO DEBE permitir ciclos de dependencia (ADR-0002).

#### Escenario: `domain` importa `infrastructure`

- **DADO** un módulo con las capas separadas
- **CUANDO** `domain` importa una clase de `infrastructure`
- **ENTONCES** la construcción falla por invertir el sentido permitido

#### Escenario: Ciclo entre dos paquetes

- **DADO** el paquete A sin dependencia hacia B
- **CUANDO** A pasa a depender de B y B ya depende de A
- **ENTONCES** la construcción falla por ciclo de dependencia

### Requisito: Integración continua verifica cada empuje

El sistema DEBE ejecutar `./mvnw verify` en integración continua ante cada empuje a la rama del
cambio, con resultado visible en el remoto.

#### Escenario: Empuje con violación

- **DADO** un empuje que introduce una violación de arquitectura
- **CUANDO** la integración continua verifica ese empuje
- **ENTONCES** el resultado en rojo queda visible en el remoto

#### Escenario: Empuje sin violaciones

- **DADO** un empuje sin violaciones
- **CUANDO** la integración continua verifica ese empuje
- **ENTONCES** el resultado en verde queda visible en el remoto

### Requisito: Vulnerabilidad alta o crítica rompe la construcción

El sistema DEBE escanear las vulnerabilidades de sus dependencias en cada verificación de
integración continua y DEBE romper esa verificación ante severidad alta o crítica (ADR-0008,
ADR-0013). El escaneo vive en la integración continua y no en `./mvnw verify`, porque depende de
una base de datos de vulnerabilidades en línea y encarecería cada construcción local.

#### Escenario: Vulnerabilidad crítica

- **DADO** una dependencia con vulnerabilidad pública crítica
- **CUANDO** la integración continua verifica el empuje
- **ENTONCES** la verificación falla señalando esa dependencia y el resultado en rojo queda visible
  en el remoto

#### Escenario: Solo severidad baja

- **DADO** dependencias con vulnerabilidades solo de severidad baja o media
- **CUANDO** la integración continua verifica el empuje
- **ENTONCES** el escaneo no rompe la verificación

### Requisito: Ninguna regla se desactiva sin un ADR

El sistema NO DEBE permitir que una regla de esta capacidad quede deshabilitada, suprimida o
excluida sin una referencia explícita a un ADR que lo autorice.

#### Escenario: Exclusión sin justificación

- **DADO** una regla de esta capacidad activa
- **CUANDO** alguien la deshabilita sin citar un ADR
- **ENTONCES** esa exclusión es en sí misma una violación

#### Escenario: Excepción documentada por ADR

- **DADO** una excepción necesaria a una regla de capas
- **CUANDO** el código cita junto a ella el ADR que la autoriza
- **ENTONCES** no se considera una violación de esta capacidad

### Requisito: Cobertura mínima del módulo `kernel`

El sistema DEBE romper `./mvnw verify` si la cobertura de líneas o de ramas del módulo `kernel`,
medida con JaCoCo, cae por debajo de noventa y cinco por ciento.

#### Escenario: Cobertura por debajo del umbral

- **DADO** el módulo `kernel` con cobertura de líneas o de ramas por debajo de 95 %
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el módulo `kernel` y el porcentaje medido

#### Escenario: Cobertura en el umbral o por encima

- **DADO** el módulo `kernel` con cobertura de líneas y de ramas igual o superior a 95 %
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción no falla por esta causa

### Requisito: Puntuación de mutación del módulo `kernel` según la rama

El sistema DEBE ejecutar pruebas de mutación con PIT sobre el módulo `kernel` con umbral mínimo de
ochenta. En la rama principal, el perfil `mutation-gate` DEBE romper la construcción si la
puntuación de mutación cae por debajo del umbral. En cualquier otra rama, el perfil
`mutation-report` NO DEBE romper la construcción por una puntuación baja y SOLO DEBE informarla.

#### Escenario: Mutación baja en la rama principal

- **DADO** un empuje a la rama principal con el perfil `mutation-gate` activo
- **CUANDO** la puntuación de mutación de `kernel` resulta menor a 80
- **ENTONCES** `./mvnw verify` falla señalando la puntuación medida

#### Escenario: Mutación baja en una rama de trabajo

- **DADO** un empuje a una rama `change/**` con el perfil `mutation-report` activo
- **CUANDO** la puntuación de mutación de `kernel` resulta menor a 80
- **ENTONCES** `./mvnw verify` no falla por esta causa y el reporte de mutación queda visible en la
  integración continua

#### Escenario: Selección de perfil de mutación según la rama

- **DADO** la integración continua ejecutando un empuje
- **CUANDO** la rama que se verifica es la rama principal
- **ENTONCES** se activa `mutation-gate`; en cualquier otra rama se activa `mutation-report`, con el
  mismo mecanismo de activación condicional por rama que ya usa el perfil `no-snapshots-on-main`
  (propiedad `confia.ci.mainBranch`)

### Requisito: Prohibición de coma flotante para importes y de igualdad cruda de `BigDecimal` fuera de `Money`

El sistema DEBE romper `./mvnw verify` con reglas de ArchUnit que se ejecutan sobre todo el código
de producción de `apps/api` (ADR-0004 §Cumplimiento 2) y que prohíben:

- construir un `BigDecimal` con `new BigDecimal(double)` o con `BigDecimal.valueOf(double)`;
- invocar `BigDecimal.equals` fuera del módulo `kernel`;
- declarar un campo, un parámetro o un valor de retorno `double` o `float` en un tipo monetario,
  entendiendo por tipo monetario `Money`, `Percentage` y toda clase de `apps/api` que declare un
  campo de alguno de esos dos tipos.

Cada una de las tres reglas DEBE tener su propio fixture de prueba permanente que la viole a
propósito, para demostrar que la regla realmente falla cuando corresponde, y no solo que pasa por
ausencia de código que la ejercite.

#### Escenario: Fixture que construye `BigDecimal` desde `double`

- **DADO** un fixture de prueba en `apps/api/app` que invoca `new BigDecimal(double)` o
  `BigDecimal.valueOf(double)`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando la clase del fixture y la regla incumplida

#### Escenario: Fixture que invoca `BigDecimal.equals` fuera de `Money`

- **DADO** un fixture de prueba fuera del módulo `kernel` que invoca `BigDecimal.equals` sobre un
  importe
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando la clase del fixture y la regla incumplida

#### Escenario: Fixture con un campo `double` en un tipo monetario

- **DADO** un fixture de prueba que declara un campo `double` en una clase con un campo `Money` o
  `Percentage`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando la clase del fixture y la regla incumplida

#### Escenario: Código de producción sin infracciones

- **DADO** el código de producción real de `apps/api`, sin ninguno de los tres patrones prohibidos
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** las tres reglas pasan sin excepción de conjunto vacío, porque seleccionan código de
  producción real y no un conjunto vacío

### Requisito: Cobertura global mínima del módulo `app`

El sistema DEBE romper `./mvnw verify` si la cobertura de líneas o de ramas del módulo `app`,
medida con JaCoCo sobre la totalidad de su código de producción, cae por debajo de ochenta por
ciento.

#### Escenario: Cobertura del módulo `app` por debajo del umbral

- **DADO** el módulo `app` con cobertura de líneas o de ramas por debajo de 80 %
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el módulo `app` y el porcentaje medido

#### Escenario: Cobertura del módulo `app` en el umbral o por encima

- **DADO** el módulo `app` con cobertura de líneas y de ramas igual o superior a 80 %
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción no falla por esta causa

### Requisito: Cobertura y mutación del paquete `domain` de cada módulo de negocio

El sistema DEBE romper `./mvnw verify` si la cobertura de líneas o de ramas del paquete `domain`
de cualquier módulo de negocio de `apps/api/app`, medida con JaCoCo, cae por debajo de noventa y
cinco por ciento. El sistema DEBE ejecutar pruebas de mutación con PIT sobre ese mismo paquete con
umbral mínimo de ochenta, con la misma selección de perfil por rama que ya rige para `kernel`: en
la rama principal, el perfil `mutation-gate` DEBE romper la construcción si la puntuación cae por
debajo del umbral; en cualquier otra rama, el perfil `mutation-report` NO DEBE romper la
construcción por una puntuación baja y SOLO DEBE informarla.

#### Escenario: Cobertura de `organization.domain` por debajo del umbral

- **DADO** el paquete `com.confia.organization.domain` con cobertura de líneas o de ramas por
  debajo de 95 %
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el paquete y el porcentaje medido

#### Escenario: Cobertura de `organization.domain` en el umbral o por encima

- **DADO** el paquete `com.confia.organization.domain` con cobertura de líneas y de ramas igual o
  superior a 95 %
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción no falla por esta causa

#### Escenario: Mutación baja de `organization.domain` en la rama principal

- **DADO** un empuje a la rama principal con el perfil `mutation-gate` activo
- **CUANDO** la puntuación de mutación de `com.confia.organization.domain` resulta menor a 80
- **ENTONCES** `./mvnw verify` falla señalando la puntuación medida

#### Escenario: Mutación baja de `organization.domain` en una rama de trabajo

- **DADO** un empuje a una rama `change/**` con el perfil `mutation-report` activo
- **CUANDO** la puntuación de mutación de `com.confia.organization.domain` resulta menor a 80
- **ENTONCES** `./mvnw verify` no falla por esta causa y el reporte de mutación queda visible en la
  integración continua

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

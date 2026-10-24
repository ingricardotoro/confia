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

El sistema DEBE romper la construcción si una clase de un módulo de negocio importa el `domain` de
otro módulo de negocio. Este cambio introduce `identity` como segundo módulo de negocio del sistema,
junto a `organization`, y con él demuestra por primera vez la mitad positiva de esta regla: hasta
hoy, con un solo módulo de negocio, «ninguno importa el `domain` del otro» era cierto de forma
vacía, por no existir un segundo módulo del que aislarse. El escenario que sigue estaba diferido en
`openspec/specs/build-integrity/spec.md` (líneas 26 a 32) a los cambios de F0 que introdujeran esos
dos módulos: `institution-root-and-multitenancy-baseline` (cambio 4, primer módulo) y
`staff-authentication-mfa-sessions` (cambio 7, segundo módulo). El cambio 7 se dividió el
2026-09-24 en tres cambios SDD secuenciales, y `identity-module-and-password-authentication` es la
primera de esas tres partes: es el mismo trabajo que la nota diferida nombraba como «cambio 7», bajo
otro nombre de cambio.

#### Escenario: Importación cruzada de dominio

- **DADO** dos módulos de negocio existentes
- **CUANDO** el módulo A importa una clase de `domain` del módulo B
- **ENTONCES** `./mvnw verify` falla por la importación prohibida

#### Escenario: Cada módulo usa solo su propio dominio

- **DADO** los dos módulos de negocio existentes, `organization` e `identity`
- **CUANDO** ninguno importa el `domain` del otro
- **ENTONCES** `./mvnw verify` termina en verde

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

### Requisito: Tabla `shared_idempotency_key`, clave primaria natural y seguridad de fila forzada

El sistema DEBE mantener la migración `V4` que crea la tabla `shared_idempotency_key`, con clave
primaria natural compuesta `(institution_id, endpoint, idempotency_key)` y las columnas
`request_hash`, `status`, `response_status`, `response_body`, `created_at`, `completed_at` y
`expires_at` (ADR-0010; ADR-0015 regla 3). La tabla DEBE tener `ENABLE ROW LEVEL SECURITY` y
`FORCE ROW LEVEL SECURITY`, con una política sobre `institution_id` que siga el mismo patrón
`current_setting(..., true)` con fallo cerrado ante `NULL` que ya usan las migraciones `V1` a `V3`
(`docs/03-seguridad.md` §6.2). La migración DEBE partir de `REVOKE ALL ... FROM PUBLIC` antes de
conceder ningún privilegio explícito.

#### Escenario: Las puertas genéricas de esquema pasan sobre `shared_idempotency_key` sin lista de exclusión

- **DADO** `shared_idempotency_key` ya migrada, con su clave primaria compuesta
  `(institution_id, endpoint, idempotency_key)` y su seguridad a nivel de fila habilitada y
  forzada
- **CUANDO** se ejecutan las pruebas de catálogo e inventario de `MultiTenantSchemaIT`
  —`institution_id NOT NULL`, `relrowsecurity` y `relforcerowsecurity` en verdadero, prefijo de
  módulo `shared_` y restricción única con discriminador de institución—
- **ENTONCES** todas pasan sobre `shared_idempotency_key` sin necesitar ninguna excepción ni
  entrada en una lista de exclusión

#### Escenario: La clave primaria compuesta actúa como discriminador de institución sin necesitar la excepción de la tabla raíz

- **DADO** que la restricción de unicidad con discriminador de institución solo exceptúa
  explícitamente a la tabla raíz de `organization`, cuya propia clave primaria hace ese papel
- **CUANDO** la prueba de catálogo de restricciones únicas evalúa la clave primaria de
  `shared_idempotency_key`
- **ENTONCES** la reconoce como conforme porque `institution_id` es la primera columna de esa
  clave primaria, sin necesitar entrar en la lista cerrada de excepciones que hoy solo contiene a
  la tabla raíz

#### Escenario: Una institución no lee ni bloquea la clave de otra

- **DADO** dos instituciones, y una clave de idempotencia con el mismo endpoint y el mismo valor
  literal, ejecutada y completada por la primera
- **CUANDO** la segunda, con su propio contexto de sesión y el rol `confia_admin_app`, intenta
  bloquear esa clave y además cuenta las filas de la tabla sin predicado alguno
- **ENTONCES** el bloqueo no encuentra fila y el conteo directo devuelve cero, aunque la fila exista
  físicamente; y la primera institución sigue viendo la suya, de modo que una política que lo
  ocultara todo a todos no satisfaría el escenario

#### Escenario: Dos instituciones pueden sostener el mismo valor de clave de forma independiente

- **DADO** dos instituciones y un mismo valor literal de clave de idempotencia sobre el mismo
  endpoint
- **CUANDO** cada una ejecuta su propia solicitud con ese valor
- **ENTONCES** ambas ejecutan y ninguna repite la respuesta de la otra, porque la clave está acotada
  a su institución por la primera columna de la clave primaria compuesta

### Requisito: Permisos de acceso a `shared_idempotency_key` por rol de base de datos

El sistema DEBE conceder sobre `shared_idempotency_key`, exactamente y sin uno más ni uno menos
(`docs/03-seguridad.md` §6.1): a `confia_admin_app`, `SELECT`, `INSERT` y `UPDATE`, sin `DELETE`;
a `confia_portal_app`, ningún privilegio; a `confia_readonly`, solo `SELECT`, sujeto a la misma
política de fila por institución que los demás roles; a `confia_backup`, el alcance de
`pg_read_all_data` que ya tiene sobre el resto del esquema; a `confia_owner`, ningún privilegio
adicional a los de propietario del esquema, sin el atributo `BYPASSRLS`. El sistema DEBE extender
`RolePrivilegeMatrixIT` con las filas de `shared_idempotency_key` para los cinco roles.

#### Escenario: La matriz de privilegios cubre `shared_idempotency_key` para los cinco roles

- **DADO** `RolePrivilegeMatrixIT` extendida con las filas de `shared_idempotency_key`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** confirma exactamente los privilegios de `docs/03-seguridad.md` §6.1 sobre
  `shared_idempotency_key` para cada uno de los cinco roles, sin uno más ni uno menos

#### Escenario: `confia_admin_app` puede leer, insertar y actualizar, pero no borrar

- **DADO** el rol `confia_admin_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT` y `UPDATE` sobre `shared_idempotency_key`, y después
  intenta `DELETE`
- **ENTONCES** las tres primeras operaciones se permiten y el `DELETE` se rechaza por falta de
  privilegio

#### Escenario: `confia_portal_app` no tiene ningún privilegio sobre la tabla

- **DADO** el rol `confia_portal_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT`, `UPDATE` o `DELETE` sobre `shared_idempotency_key`
- **ENTONCES** las cuatro operaciones se rechazan por falta de privilegio

### Requisito: Marcador y efecto de negocio en una única transacción atómica

El componente de ejecución idempotente de `com.confia.shared.security` DEBE escribir el marcador
en curso y ejecutar el efecto del caso de uso dentro de una única invocación del componente
transaccional único, y por tanto dentro de una única transacción real contra PostgreSQL, de modo
que un fallo del efecto revierta también el marcador. El componente NO DEBE abrir ninguna
transacción propia: la regla «Transacciones confinadas al componente único de `shared/security`»
ya exigida por esta capacidad se aplica sin excepción al componente nuevo, por composición y no
por excepción.

#### Escenario: El efecto exitoso completa el marcador dentro de la misma transacción

- **DADO** una clave de idempotencia nueva y un caso de uso real que se ejecuta con éxito
- **CUANDO** el componente lo ejecuta a través de una única invocación del componente
  transaccional
- **ENTONCES**, al confirmar, el marcador queda en estado completado con la respuesta del caso de
  uso, y ambas escrituras —marcador y efecto— son visibles o ninguna lo es

#### Escenario: El efecto que falla revierte también el marcador

- **DADO** una clave de idempotencia nueva y un caso de uso real que falla de forma determinista
  después de que el componente escribe el marcador en curso
- **CUANDO** el componente lo ejecuta
- **ENTONCES** la transacción completa revierte, ninguna fila del marcador sobrevive para esa
  clave, y una solicitud posterior con la misma clave se trata como clave nueva

### Requisito: Espera acotada ante escritura concurrente del marcador, con salidas distinguibles por `SQLState`

El sistema DEBE acotar con `lock_timeout` explícito la espera de una segunda escritura del
marcador que colisiona por clave primaria con una primera transacción todavía abierta sobre la
misma clave, en vez de bloquear sin límite. El sistema DEBE distinguir, por código `SQLState` y
nunca por texto de mensaje, entre el agotamiento de esa espera —mientras la primera transacción
sigue abierta— y el choque por clave duplicada —una vez que la primera ya confirmó—, porque son
dos comportamientos observables distintos que ocurren en momentos distintos. Ante el agotamiento
de la espera, el componente DEBE propagar un error tratable y distinguible, apto para que la capa
web lo traduzca a `409` cuando esa capa exista (cambio 7). El reintento acotado del componente
transaccional NO DEBE reintentar ninguna de las dos salidas.

#### Escenario: La espera se agota mientras la primera transacción sigue abierta

- **DADO** dos transacciones reales, ejecutadas a través del componente, que escriben el marcador
  con la misma clave primaria natural, sincronizadas de forma determinista —no por reloj— para que
  la primera permanezca abierta más allá del `lock_timeout` configurado
- **CUANDO** la segunda espera ese `lock_timeout`
- **ENTONCES** la segunda recibe un error tratable, distinguible por `SQLState` del choque por
  clave duplicada, sin haber escrito ningún marcador propio ni haber ejecutado su caso de uso, y
  la primera transacción continúa sin interferencia

#### Escenario: La primera transacción confirma dentro de la ventana de espera

- **DADO** dos transacciones reales, ejecutadas a través del componente, con la misma clave
  primaria natural, sincronizadas de forma determinista para que la primera confirme dentro de la
  ventana del `lock_timeout` de la segunda
- **CUANDO** la segunda, ya dentro de su ventana de espera, intenta escribir su marcador
- **ENTONCES** la segunda recibe un error de clave duplicada, distinguible por `SQLState` de la
  salida de espera agotada, sin haber ejecutado su caso de uso

#### Escenario: La primera transacción revierte dentro de la ventana de espera

- **DADO** dos transacciones reales, ejecutadas a través del componente, con la misma clave
  primaria natural, sincronizadas de forma determinista para que la primera revierta dentro de la
  ventana del `lock_timeout` de la segunda
- **CUANDO** la segunda, ya dentro de su ventana de espera, intenta escribir su marcador
- **ENTONCES** la segunda inserta su marcador con éxito y ejecuta su caso de uso, porque la clave
  quedó libre

#### Escenario: El reintento acotado no reintenta el agotamiento de espera ni el choque por clave duplicada

- **DADO** las dos salidas anteriores —agotamiento de espera y choque por clave duplicada—,
  producidas por transacciones reales a través del componente transaccional único
- **CUANDO** cada una ocurre
- **ENTONCES** el componente transaccional propaga el error tras el primer intento, sin
  reintentarlo, a diferencia de los errores de serialización o de interbloqueo que sí reintenta

### Requisito: Rechazo de la misma clave con carga útil distinta, comparada por hash canonicalizado

El sistema DEBE comparar la carga útil de una solicitud repetida contra el hash de la carga
canonicalizada (`request_hash`) almacenado para esa clave, nunca contra el texto crudo, y DEBE
rechazar la ejecución sin invocar el caso de uso cuando los hashes difieren (ADR-0010).

#### Escenario: Misma clave, hash de carga distinto, caso de uso no invocado

- **DADO** una clave de idempotencia con una carga útil ya registrada y su `request_hash`
  almacenado
- **CUANDO** llega una solicitud con la misma clave y una carga útil cuyo hash canonicalizado
  difiere del almacenado
- **ENTONCES** el componente rechaza la solicitud sin invocar el caso de uso, verificable porque
  el efecto de negocio asociado no se ejecuta ninguna vez

#### Escenario: Misma clave, mismo hash de carga, se admite como repetición válida

- **DADO** la misma clave del escenario anterior
- **CUANDO** llega una solicitud con una carga útil cuyo hash canonicalizado coincide con el
  almacenado
- **ENTONCES** el componente no la rechaza por carga distinta

### Requisito: Respuesta reproducible ante clave completada, sin reejecutar el caso de uso

El sistema DEBE devolver, ante una clave ya completada con hash de carga coincidente, la respuesta
original almacenada, sin reejecutar el `Supplier` del caso de uso, y el resultado que el
componente devuelve DEBE distinguir una repetición de una ejecución real, para que el llamador
pueda producir la cabecera `Idempotent-Replay` cuando exista una capa web (cambio 7) sin volver a
consultar nada.

#### Escenario: La segunda solicitud recibe la respuesta original sin reejecutar el caso de uso

- **DADO** una clave de idempotencia que ya completó su ejecución y almacenó una respuesta
- **CUANDO** una solicitud posterior llega con la misma clave y la misma carga útil
- **ENTONCES** el componente devuelve exactamente la misma respuesta almacenada, el caso de uso
  registra cero invocaciones nuevas, y el resultado señala que se trató de una repetición

### Requisito: Criterio de salida 4 de F0 — un solo efecto contable ante dos solicitudes concurrentes con la misma clave

El sistema DEBE demostrar, sobre PostgreSQL real y un camino de escritura de producción real —por
ejemplo una actualización acumulativa sobre una columna de `organization_institution` o una
inserción contada en `shared_audit_log`, nunca una actualización a valor fijo, que sería
idempotente por naturaleza y no testificaría nada—, que dos solicitudes concurrentes con la misma
clave de idempotencia producen exactamente un efecto contable, sin importar cuál de las tres
salidas de la espera acotada resuelve a cada una.

#### Escenario: Dos solicitudes concurrentes con la misma clave producen un solo efecto contable

- **DADO** un caso de uso real que aplica un efecto contable —acumulativo o de conteo de filas,
  nunca una actualización a valor fijo— sobre una fila real de producción, invocado a través del
  componente
- **CUANDO** dos solicitudes reales, con la misma clave de idempotencia y sincronizadas de forma
  determinista, se ejecutan de manera concurrente
- **ENTONCES** el efecto contable se aplicó exactamente una vez, verificable por el valor final
  acumulado o por el conteo de filas resultante, y nunca dos veces

### Requisito: Reutilización de una clave caducada por actualización de la fila existente

El sistema DEBE tratar una clave de idempotencia con `expires_at` vencido como si fuera nueva,
ejecutando de nuevo el caso de uso, y DEBE hacerlo mediante `UPDATE` de la fila existente, nunca
mediante `DELETE` seguido de `INSERT`, porque ningún rol de aplicación tiene privilegio `DELETE`
sobre tablas de negocio (`docs/03-seguridad.md` §6.1).

#### Escenario: Una clave caducada se reutiliza actualizando la fila existente, no insertando una nueva

- **DADO** una fila de `shared_idempotency_key` con `expires_at` vencido, para una clave que ya
  había completado una ejecución anterior
- **CUANDO** llega una nueva solicitud con esa misma clave
- **ENTONCES** el sistema ejecuta el caso de uso de nuevo, como si la clave fuera nueva, y la fila
  existente se actualiza con el nuevo estado, hash e `expires_at`, sin que exista en ningún momento
  más de una fila para esa clave primaria

### Requisito: Ausencia de purga física de claves caducadas (brecha con destino: cambio 9)

El sistema NO DEBE eliminar físicamente ninguna fila de `shared_idempotency_key` con `expires_at`
vencido. Esta parte del cambio modela `expires_at` y la semántica lógica de caducidad —ver
«Reutilización de una clave caducada por actualización de la fila existente»—, pero no borra
ninguna fila. La purga física es responsabilidad del **cambio 9**
(`background-jobs-with-db-scheduler`), que hereda además una tensión de privilegios: `docs/03-
seguridad.md` §6.1 no concede `DELETE` sobre tablas de negocio a ningún rol de aplicación,
incluido el que ejecuta `confia-worker`.

#### Escenario: Una fila caducada sigue existiendo porque no hay ningún trabajo de purga desplegado

- **DADO** una fila de `shared_idempotency_key` con `expires_at` vencido hace más de veinticuatro
  horas, y ningún trabajo programado de purga desplegado en este cambio
- **CUANDO** se consulta la tabla directamente
- **ENTONCES** la fila sigue existiendo; este escenario deja de ser cierto el día que el cambio 9
  entregue su trabajo de purga

### Requisito: Ausencia de acceso del portal a `shared_idempotency_key` (brecha con destino: F3/F4)

El sistema NO DEBE conceder a `confia_portal_app` ningún privilegio sobre `shared_idempotency_key`
(ver «Permisos de acceso a `shared_idempotency_key` por rol de base de datos»). Ningún camino de
escritura financiera iniciado desde el portal puede ser idempotente todavía, porque
`docs/03-seguridad.md` §6.1 concede a `confia_portal_app` `INSERT` únicamente en
`document_request`, `payment_intent` y `notification_preference`, ninguna de las cuales es esta
tabla. El primer `GRANT` que resuelva esta brecha es responsabilidad de la capacidad de **F3 o
F4** que dé al portal su primer camino de escritura financiera.

#### Escenario: `confia_portal_app` no tiene ningún privilegio, y ninguna escritura del portal puede ser idempotente

- **DADO** `shared_idempotency_key` migrada con los `GRANT` de `docs/03-seguridad.md` §6.1
- **CUANDO** `RolePrivilegeMatrixIT` verifica los privilegios de `confia_portal_app` sobre esa
  tabla
- **ENTONCES** no encuentra ningún privilegio —ni `SELECT`, ni `INSERT`, ni `UPDATE`, ni
  `DELETE`—; este escenario deja de ser cierto el día que una capacidad de F3 o F4 conceda el
  primer `GRANT` de escritura financiera al portal

### Requisito: Tablas nuevas de identidad, con `institution_id` y seguridad de fila forzada

El sistema DEBE mantener la migración `V5`, que crea **dos** tablas nuevas de identidad —ambas con
el prefijo de módulo `identity_`, según ADR-0015 regla 3—: una para la cuenta de personal, y otra
para el estado de retroceso por intentos fallidos, indexada por una huella con llave del
identificador presentado y **no** por la cuenta, porque el retardo se aplica también a
identificadores que no corresponden a ninguna cuenta. Todo lo que este requisito exige de «la tabla
nueva» DEBE cumplirse en **cada una de las dos**. Cada tabla DEBE declarar `institution_id NOT NULL` (ADR-0009, punto 1) y toda
restricción única DEBE incluir `institution_id` como discriminador (ADR-0009, punto 2). Cada tabla
DEBE tener `ENABLE ROW LEVEL SECURITY` y `FORCE ROW LEVEL SECURITY`, con una política sobre
`institution_id` que siga el mismo patrón `current_setting(..., true)` con fallo cerrado ante `NULL`
que ya usan las migraciones `V1` a `V4` (`docs/03-seguridad.md` §6.2). La migración DEBE partir de
`REVOKE ALL ... FROM PUBLIC` antes de conceder ningún privilegio explícito.

#### Escenario: Las puertas genéricas de esquema pasan sobre cada tabla nueva sin lista de exclusión

- **DADO** cada tabla nueva de identidad ya migrada, con `institution_id NOT NULL`, su seguridad a
  nivel de fila habilitada y forzada, y su restricción única con discriminador de institución
- **CUANDO** se ejecutan las pruebas de catálogo e inventario de `MultiTenantSchemaIT`
  —`institution_id NOT NULL`, `relrowsecurity` y `relforcerowsecurity` en verdadero, prefijo de
  módulo `identity_`, y restricción única con discriminador de institución—
- **ENTONCES** todas pasan sobre **cada una de las dos** sin necesitar ninguna excepción ni entrada
  en una lista de exclusión

#### Escenario: Una institución no lee la cuenta de personal de otra

- **DADO** dos instituciones, cada una con al menos una cuenta de personal en la tabla de cuentas
- **CUANDO** la segunda, con su propio contexto de sesión y el rol `confia_admin_app`, consulta la
  tabla sin ningún predicado adicional
- **ENTONCES** solo obtiene las filas de su propia institución, nunca las de la primera

#### Escenario: Sin contexto de institución, la consulta devuelve cero filas, no un error de permiso

- **DADO** cada tabla nueva de identidad ya migrada, con filas de al menos una institución
- **CUANDO** se consulta con el rol `confia_admin_app` sin que `app.institution_id` esté establecido
  en la transacción
- **ENTONCES** la consulta devuelve cero filas, porque la política deniega ante `NULL`, en vez de
  fallar por falta de permiso

### Requisito: Permisos de acceso a las tablas nuevas de identidad por rol de base de datos

El sistema DEBE conceder sobre cada tabla nueva de identidad, exactamente y sin uno más ni uno menos
(`docs/03-seguridad.md` §6.1): a `confia_admin_app`, `SELECT`, `INSERT` y `UPDATE`, sin `DELETE`, por
tratarse de una tabla no financiera; a `confia_portal_app`, **ningún privilegio**, porque §6.1
declara literalmente «sin acceso alguno a `user`, `role`, `cai_range`, `cashbox_session` ni
`shared_audit_log`»; a `confia_readonly`, solo `SELECT`, sujeto a la misma política de fila por
institución que los demás roles; a `confia_backup`, el alcance de `pg_read_all_data` que ya tiene
sobre el resto del esquema; a `confia_owner`, ningún privilegio adicional a los de propietario del
esquema, sin el atributo `BYPASSRLS`. El sistema DEBE extender `RolePrivilegeMatrixIT` con las filas
de **las dos** tablas nuevas para los cinco roles.

#### Escenario: La matriz de privilegios cubre las dos tablas nuevas para los cinco roles

- **DADO** `RolePrivilegeMatrixIT` extendida con las filas de las dos tablas nuevas de identidad
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** confirma exactamente los privilegios de `docs/03-seguridad.md` §6.1 sobre cada una de
  las dos tablas para cada uno de los cinco roles, sin uno más ni uno menos

#### Escenario: `confia_admin_app` puede leer, insertar y actualizar, pero no borrar

- **DADO** el rol `confia_admin_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT` y `UPDATE` sobre cada tabla nueva de identidad, y después
  intenta `DELETE`
- **ENTONCES** las tres primeras operaciones se permiten y el `DELETE` se rechaza por falta de
  privilegio

#### Escenario: `confia_portal_app` no tiene ningún privilegio sobre ninguna tabla de identidad

- **DADO** el rol `confia_portal_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT`, `UPDATE` o `DELETE` sobre cualquiera de las dos tablas
  nuevas de identidad
- **ENTONCES** las cuatro operaciones se rechazan por falta de privilegio

### Requisito: Tablas nuevas de cifrado de columna y MFA, con `institution_id` y seguridad de fila forzada

El sistema DEBE mantener la migración `V6`, que añade la columna `mfa_required BOOLEAN NOT NULL` a
`identity_staff_account` y crea **cuatro** tablas nuevas: la tabla de llaves de datos del sobre de
llaves, con el prefijo de módulo `shared_` porque su gestión vive en `com.confia.shared.crypto`
(decisión D3 de la propuesta), con su estado `active`/`retired`; y, con el prefijo de módulo
`identity_`, la tabla de credencial TOTP por cuenta, la tabla de códigos de recuperación de MFA, y
la tabla de retroceso de verificación TOTP. Todo lo que este requisito exige de «la tabla nueva»
DEBE cumplirse en **cada una de las cuatro**. Cada tabla DEBE declarar `institution_id NOT NULL`
(ADR-0009, punto 1) y toda restricción única DEBE incluir `institution_id` como discriminador
(ADR-0009, punto 2). Cada tabla DEBE tener `ENABLE ROW LEVEL SECURITY` y `FORCE ROW LEVEL
SECURITY`, con una política sobre `institution_id` que siga el mismo patrón
`current_setting(..., true)` con fallo cerrado ante `NULL` que ya usan las migraciones `V1` a `V5`
(`docs/03-seguridad.md` §6.2). La tabla de llaves de datos DEBE además aislar las llaves por
institución, de modo que cifrar o descifrar el secreto de una institución nunca toque la llave de
otra. La migración DEBE partir de `REVOKE ALL ... FROM PUBLIC` antes de conceder ningún privilegio
explícito.

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

### Requisito: Permisos de acceso a las tablas nuevas de cifrado y MFA por rol de base de datos

El sistema DEBE conceder sobre cada una de las cuatro tablas nuevas, exactamente y sin uno más ni
uno menos (`docs/03-seguridad.md` §6.1): a `confia_admin_app`, `SELECT`, `INSERT` y `UPDATE`, sin
`DELETE`; a `confia_portal_app`, **ningún privilegio**, por ser datos de personal; a
`confia_readonly`, solo `SELECT`, sujeto a la misma política de fila por institución que los demás
roles; a `confia_backup`, el alcance de `pg_read_all_data` que ya tiene sobre el resto del esquema;
a `confia_owner`, ningún privilegio adicional a los de propietario del esquema, sin el atributo
`BYPASSRLS`. El sistema DEBE extender `RolePrivilegeMatrixIT` con las filas de las cuatro tablas
nuevas para los cinco roles.

#### Escenario: La matriz de privilegios cubre las cuatro tablas nuevas para los cinco roles

- **DADO** `RolePrivilegeMatrixIT` extendida con las filas de las cuatro tablas nuevas
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** confirma exactamente los privilegios de `docs/03-seguridad.md` §6.1 sobre cada una de
  las cuatro tablas para cada uno de los cinco roles, sin uno más ni uno menos

#### Escenario: `confia_admin_app` puede leer, insertar y actualizar, pero no borrar

- **DADO** el rol `confia_admin_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT` y `UPDATE` sobre cada tabla nueva, y después intenta
  `DELETE`
- **ENTONCES** las tres primeras operaciones se permiten y el `DELETE` se rechaza por falta de
  privilegio

#### Escenario: `confia_portal_app` no tiene ningún privilegio sobre ninguna tabla nueva

- **DADO** el rol `confia_portal_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT`, `UPDATE` o `DELETE` sobre cualquiera de las cuatro tablas
  nuevas
- **ENTONCES** las cuatro operaciones se rechazan por falta de privilegio

### Requisito: El OpenAPI generado coincide con la instantánea aprobada

El sistema DEBE generar, en cada ejecución de `./mvnw verify`, el documento OpenAPI 3.1 de cada
una de sus dos superficies de API: la **administrativa** y la **del portal** (ADR-0003). Cada
documento DEBE compararse contra su instantánea aprobada, comprometida en el repositorio, y
cualquier diferencia no declarada DEBE romper la construcción. La comparación NO DEBE sobrescribir
nunca la instantánea: actualizarla DEBE ser un paso explícito y separado, cuyo resultado queda
visible en el diff del PR. El documento generado DEBE conservarse como artefacto de cada ejecución
de la integración continua. Este requisito cierra el criterio de salida 7 de F0 («OpenAPI 3.1 se
genera y publica como artefacto versionado»).

#### Escenario: Una diferencia no declarada rompe la construcción

- **DADO** la instantánea aprobada del documento administrativo, comprometida en el repositorio
- **CUANDO** la instantánea se altera a mano, de modo que ya no coincide con el documento que el
  código genera
- **ENTONCES** `./mvnw verify` falla y nombra el documento que difiere
- **Y** la instantánea comprometida queda intacta después de la ejecución

#### Escenario: Documento idéntico a la instantánea

- **DADO** las dos instantáneas aprobadas, sin alterar
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** los dos documentos generados coinciden con sus instantáneas y la construcción
  termina en verde

#### Escenario: Cada superficie tiene su propio documento

- **DADO** los dos documentos generados
- **CUANDO** se inspecciona su contenido
- **ENTONCES** son dos documentos distintos, uno por superficie, y ninguna operación del documento
  administrativo aparece en el del portal

### Requisito: Swagger UI y el endpoint del OpenAPI solo en local y preproducción

El sistema DEBE habilitar Swagger UI y el endpoint del documento OpenAPI únicamente cuando el
perfil de configuración activo es `local` o `preprod`. Con cualquier otro perfil, incluido `prod`,
NO DEBE responder ninguno de los dos, en ninguno de los tres procesos
(`docs/01-arquitectura.md` §7; `docs/05-infraestructura-y-despliegue.md`, variable
`SPRING_PROFILES_ACTIVE`).

#### Escenario: Perfil de producción

- **DADO** el proceso administrativo arrancado con el perfil `prod`
- **CUANDO** se pide el endpoint del documento OpenAPI y la ruta de Swagger UI
- **ENTONCES** ninguno de los dos devuelve el documento ni la interfaz

#### Escenario: Perfil local

- **DADO** el proceso administrativo arrancado con el perfil `local`
- **CUANDO** se pide el endpoint del documento OpenAPI
- **ENTONCES** devuelve un documento OpenAPI 3.1 válido

### Requisito: Esquemas transversales del contrato presentes desde el primer documento

El sistema DEBE publicar en ambos documentos OpenAPI, aunque todavía no exista ninguna operación,
los dos esquemas que el contrato ya fija (`docs/01-arquitectura.md` §7): el importe, con `amount`
y `currency` ambos de tipo cadena y obligatorios, y el error en formato Problem Details (RFC 9457).
El importe NO DEBE declararse nunca con tipo numérico (`CLAUDE.md`, regla 1).

#### Escenario: El importe viaja como cadena

- **DADO** el documento OpenAPI generado
- **CUANDO** se lee el esquema del importe
- **ENTONCES** `amount` y `currency` son de tipo `string` y ambos obligatorios
- **Y** ninguna propiedad del esquema es de tipo `number` ni `integer`

#### Escenario: Problem Details presente

- **DADO** el documento OpenAPI generado
- **CUANDO** se lee el esquema de error
- **ENTONCES** declara los campos de RFC 9457 (`type`, `title`, `status`, `detail`, `instance`) y
  el identificador de traza que exige `docs/01-arquitectura.md` §7

### Requisito: Los contratos del frontend se generan y un cambio incompatible rompe la compilación

El sistema DEBE generar `packages/contracts` con orval a partir de las instantáneas aprobadas: tipos
TypeScript y esquemas Zod, en un espacio de nombres por superficie de API. El código generado NO
DEBE comprometerse en el repositorio ni editarse a mano; la construcción del monorepo lo regenera
siempre. Una prueba de tipos DEBE consumir los contratos generados, de modo que un cambio
incompatible del contrato rompa la compilación de TypeScript en la integración continua, no en
producción.

#### Escenario: Cambio incompatible del contrato

- **DADO** la prueba de tipos que consume el esquema del importe
- **CUANDO** la instantánea se altera para que `amount` pase de cadena a número y se regeneran los
  contratos
- **ENTONCES** la verificación de tipos del monorepo falla

#### Escenario: Contrato sin cambios

- **DADO** las instantáneas aprobadas, sin alterar
- **CUANDO** se regeneran los contratos y se verifica los tipos del monorepo
- **ENTONCES** la verificación termina en verde

#### Escenario: La salida generada no está en el repositorio

- **DADO** el repositorio recién clonado, sin ninguna construcción previa
- **CUANDO** se inspecciona `packages/contracts`
- **ENTONCES** no contiene ningún archivo generado por orval, porque el control de versiones lo
  ignora

### Requisito: Reglas de dependencia del frontend

El sistema DEBE romper la construcción del monorepo, mediante `dependency-cruiser` y ESLint, cuando
el código TypeScript viola alguna de estas reglas: un paquete de `packages/` importa de una
aplicación de `apps/`; una aplicación importa de otra aplicación; cualquier código importa una ruta
interna de `packages/contracts` en lugar de su punto de entrada público; o el código del portal
importa el espacio de nombres administrativo de `packages/contracts`. Cada regla DEBE estar
acompañada de una violación deliberada que demuestre que la rechaza (ADR-0018). Ninguna regla puede
pasar sobre un conjunto vacío: una regla que no encuentra nada que evaluar no protege nada.

#### Escenario: Un paquete importa de una aplicación

- **DADO** la violación deliberada en la que un módulo de `packages/` importa de `apps/`
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** falla y nombra la regla violada

#### Escenario: El portal importa el contrato administrativo

- **DADO** la violación deliberada en la que código del portal importa el espacio de nombres
  administrativo de `packages/contracts`
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** falla y nombra la regla violada

#### Escenario: Código que respeta las reglas

- **DADO** el código real del monorepo, excluidas las violaciones deliberadas
- **CUANDO** se ejecuta la verificación de dependencias del monorepo
- **ENTONCES** termina en verde

### Requisito: Versión única de Node y de pnpm

El sistema DEBE fijar la versión de Node (24, la LTS activa) y la de pnpm en un único lugar del
repositorio, y la instalación DEBE fallar con cualquier otra versión de Node. La integración
continua DEBE usar exactamente esas versiones. El archivo de bloqueo de pnpm DEBE comprometerse, y
la integración continua DEBE instalar sin modificarlo.

#### Escenario: Versión de Node distinta

- **DADO** una máquina con una versión mayor de Node distinta de la fijada
- **CUANDO** se instala el monorepo
- **ENTONCES** la instalación falla y nombra la versión exigida

#### Escenario: Archivo de bloqueo desactualizado

- **DADO** un `package.json` modificado sin actualizar el archivo de bloqueo
- **CUANDO** la integración continua instala las dependencias
- **ENTONCES** la instalación falla en lugar de reescribir el archivo de bloqueo

### Requisito: La integración continua verifica el monorepo y escanea sus dependencias

El sistema DEBE ejecutar, en cada empuje a una rama de cambio y en cada pull request contra la rama
principal, la verificación del monorepo: análisis estático, verificación de tipos, pruebas y
construcción. El escaneo de vulnerabilidades DEBE cubrir también las dependencias de pnpm y romper
la construcción ante severidad alta o crítica, igual que ya hace con las de Maven (ADR-0008,
ADR-0013).

#### Escenario: Empuje con un error de tipos

- **DADO** un empuje a una rama de cambio con un error de tipos en el monorepo
- **CUANDO** corre la integración continua
- **ENTONCES** el trabajo del frontend falla y el resultado es visible en el remoto

#### Escenario: Vulnerabilidad crítica en una dependencia de pnpm

- **DADO** una dependencia de pnpm con una vulnerabilidad de severidad crítica conocida
- **CUANDO** corre el escaneo de dependencias
- **ENTONCES** la construcción falla y nombra la dependencia

#### Escenario: Solo severidad baja

- **DADO** dependencias de pnpm con vulnerabilidades conocidas solo de severidad baja o media
- **CUANDO** corre el escaneo de dependencias
- **ENTONCES** el escaneo las reporta sin romper la construcción

### Requisito: Tabla nueva de tokens de recuperación de contraseña, con `institution_id` y seguridad de fila forzada

El sistema DEBE mantener la migración `V7`, que crea la tabla de tokens de recuperación de contraseña
con el prefijo de módulo `identity_` (ADR-0015, regla 3). La tabla DEBE declarar `institution_id NOT
NULL` (ADR-0009, punto 1), y toda restricción única o índice único DEBE incluir `institution_id` como
discriminador (ADR-0009, punto 2). La cuenta DEBE referenciarse por su identificador interno, con una
llave foránea compuesta hacia la tabla de cuentas de personal. El hash del token DEBE restringirse,
por una restricción de verificación del catálogo, a exactamente 64 caracteres hexadecimales en
minúscula. La tabla DEBE tener `ENABLE ROW LEVEL SECURITY` y `FORCE ROW LEVEL SECURITY`, con una
política sobre `institution_id` que siga el mismo patrón `current_setting(..., true)` con fallo
cerrado ante `NULL` que ya usan las migraciones `V1` a `V6` (`docs/03-seguridad.md` §6.2). La
migración DEBE partir de `REVOKE ALL ... FROM PUBLIC` antes de conceder ningún privilegio explícito.

#### Escenario: Las puertas genéricas de esquema pasan sobre la tabla nueva sin lista de exclusión

- **DADO** la tabla de tokens de recuperación ya migrada, con `institution_id NOT NULL`, su seguridad
  a nivel de fila habilitada y forzada, y sus restricciones únicas con discriminador de institución
- **CUANDO** se ejecutan las pruebas de catálogo e inventario de `MultiTenantSchemaIT`
- **ENTONCES** todas pasan sobre la tabla nueva sin necesitar ninguna excepción ni entrada en una
  lista de exclusión

#### Escenario: Una institución no lee los tokens de otra

- **DADO** dos instituciones, cada una con al menos un token de recuperación emitido
- **CUANDO** la segunda, con su propio contexto de sesión y el rol `confia_admin_app`, consulta la
  tabla sin ningún predicado adicional
- **ENTONCES** solo obtiene las filas de su propia institución, nunca las de la primera

#### Escenario: Sin contexto de institución, la consulta devuelve cero filas, no un error de permiso

- **DADO** la tabla de tokens de recuperación con filas de al menos una institución
- **CUANDO** se consulta con el rol `confia_admin_app` sin que `app.institution_id` esté establecido
  en la transacción
- **ENTONCES** la consulta devuelve cero filas, porque la política deniega ante `NULL`, en vez de
  fallar por falta de permiso

#### Escenario: El catálogo rechaza un hash que no es de 64 caracteres hexadecimales

- **DADO** la tabla de tokens de recuperación ya migrada
- **CUANDO** se intenta insertar, con el rol `confia_admin_app` y el contexto de su institución, una
  fila cuyo hash es un token de 43 caracteres en base64url en vez de su SHA-256 hexadecimal
- **ENTONCES** la base rechaza la inserción por la restricción de verificación

### Requisito: Permisos de acceso a la tabla de tokens de recuperación por rol de base de datos

El sistema DEBE conceder sobre la tabla de tokens de recuperación de contraseña, exactamente y sin
uno más ni uno menos (`docs/03-seguridad.md` §6.1 y su adenda): a `confia_admin_app`, `SELECT`,
`INSERT` y `UPDATE`, **sin `DELETE`**, porque las filas vencidas se retienen hasta que el cambio 9
decida la purga (respuesta del propietario del 2026-09-30); a `confia_portal_app`, **ningún
privilegio**, por ser datos de personal; a `confia_readonly`, solo `SELECT`, sujeto a la misma
política de fila por institución; a `confia_backup`, el alcance de `pg_read_all_data` que ya tiene
sobre el resto del esquema; a `confia_owner`, ningún privilegio adicional a los de propietario del
esquema, sin el atributo `BYPASSRLS`. El sistema DEBE extender `RolePrivilegeMatrixIT` con las filas
de la tabla nueva para los cinco roles.

#### Escenario: La matriz de privilegios cubre la tabla nueva para los cinco roles

- **DADO** `RolePrivilegeMatrixIT` extendida con las filas de la tabla de tokens de recuperación
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** confirma exactamente los privilegios de este requisito para cada uno de los cinco
  roles, sin uno más ni uno menos

#### Escenario: `confia_admin_app` puede leer, insertar y actualizar, pero no borrar

- **DADO** el rol `confia_admin_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT` y `UPDATE` sobre la tabla de tokens de recuperación, y
  después intenta `DELETE`
- **ENTONCES** las tres primeras operaciones se permiten y el `DELETE` se rechaza por falta de
  privilegio

#### Escenario: `confia_portal_app` no tiene ningún privilegio sobre la tabla nueva

- **DADO** el rol `confia_portal_app` conectado con el contexto de una institución
- **CUANDO** intenta `SELECT`, `INSERT`, `UPDATE` o `DELETE` sobre la tabla de tokens de recuperación
- **ENTONCES** las cuatro operaciones se rechazan por falta de privilegio

### Requisito: Cada proceso registra solo beans de `com.confia.*` incluidos en su lista de permitidos

El sistema DEBE verificar, en cada ejecución de `./mvnw verify`, que el contexto de cada uno de los
tres procesos —administrativo, portal y trabajador— contiene únicamente beans de `com.confia.*`
cuyo paquete pertenece a la lista de permitidos de ese proceso (ADR-0003). La verificación DEBE
arrancar cada proceso por el mismo camino de arranque de producción, sin base de datos accesible. La
lista de permitidos DEBE fallar cerrado: un bean de `com.confia.*` cuyo paquete no figura en la lista
del proceso DEBE romper la construcción, de modo que registrar un módulo nuevo en un punto de
entrada obliga a editar la lista y esa edición queda visible en la revisión. La lista NO DEBE
reemplazarse por una lista de prohibidos como único control. Toda configuración pública importada
con `@Import` por un punto de entrada y su entrada en la lista de permitidos DEBEN llegar en el mismo
pull request (ADR-0024). La lista del proceso administrativo DEBE permitir los beans de
`com.confia.identity` que su configuración pública registra y los beans de `com.confia.shared`
registrados para el borde web.
(Previously: la lista del proceso administrativo solo permitía `bootstrap.admin` y
`shared.web.openapi`, y la verificación arrancaba "sin base de datos" sin precisar accesibilidad.)

#### Escenario: Un bean fuera de la lista rompe la construcción

- **DADO** el contexto de un proceso con un bean de `com.confia.*` en un paquete que no figura en la
  lista de permitidos de ese proceso
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el proceso, el bean y su paquete

#### Escenario: Un módulo nuevo obliga a editar la lista

- **DADO** un módulo nuevo cuya configuración un punto de entrada importa, sin haber editado la
  lista de permitidos de ese proceso
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla, y el único modo de volverla verde es editar la lista, con la
  edición visible en el diff del PR

#### Escenario: Cada proceso solo contiene beans permitidos

- **DADO** los tres procesos tal como los entrega este cambio
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** todo bean de `com.confia.*` de cada contexto pertenece a la lista de permitidos de su
  proceso, y la verificación evalúa beans reales de cada contexto, no un conjunto vacío

#### Escenario: El proceso administrativo registra el cableado de producción

- **DADO** el proceso administrativo tal como lo entrega este cambio
- **CUANDO** se inspecciona su contexto
- **ENTONCES** contiene los beans `DataSource`, `TransactionRunner`, `DSLContext` y `Clock` de
  producción y los casos de uso de identidad, todos dentro de su lista de permitidos
- **Y** la verificación evalúa esos beans reales, no un conjunto vacío

### Requisito: El contexto del portal no contiene beans de otros puntos de entrada ni de módulos administrativos

El contexto del proceso del portal NO DEBE contener ningún bean de los paquetes de entrada del
proceso administrativo (`com.confia.bootstrap.admin`) ni del proceso trabajador
(`com.confia.bootstrap.worker`), ni de los módulos `invoicing`, `cashbox` y `reconciliation`, ni de
`identity` mientras ese módulo sea solo para personal. Esta prohibición nominal DEBE regir además de
la lista de permitidos del requisito anterior, no en su lugar. El sistema DEBE romper la
construcción si el contexto del portal contiene alguno de ellos (ADR-0003, verificación 1, en su
parte de grafo de beans). El portal NO DEBE contener los beans de cableado de producción que solo
registra el proceso administrativo (`DataSource`, `TransactionRunner`, `DSLContext`, casos de uso de
identidad). El portal SÍ DEBE contener su cadena de seguridad que deniega todo.
(Previously: el requisito no mencionaba el cableado de producción del proceso administrativo ni la
cadena de seguridad del portal.)

#### Escenario: El portal arrastra un punto de entrada ajeno

- **DADO** el contexto del portal con un bean de `com.confia.bootstrap.admin` o de
  `com.confia.bootstrap.worker`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el bean prohibido

#### Escenario: El portal arrastra un módulo administrativo

- **DADO** el contexto del portal con un bean de `invoicing`, `cashbox`, `reconciliation` o de
  `identity` mientras `identity` sea solo para personal
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el bean y el módulo prohibido, aunque la lista de
  permitidos del portal llegara a incluir su paquete por error

#### Escenario: El portal contiene solo lo suyo

- **DADO** el contexto del portal tal como lo entrega este cambio
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** no contiene ningún bean de `bootstrap.admin`, de `bootstrap.worker` ni de los módulos
  administrativos, y la verificación pasa evaluando beans reales del contexto

#### Escenario: El portal no hereda el cableado administrativo

- **DADO** el contexto del portal, aunque el proceso administrativo registre `DataSource`,
  `TransactionRunner`, `DSLContext` y los casos de uso de identidad
- **CUANDO** se inspecciona el contexto del portal
- **ENTONCES** ninguno de esos beans existe en él, y contiene su cadena de seguridad que deniega
  todo y solo los beans del borde permitidos para el portal (`shared.web.edge`, `shared.web.request`
  y `shared.web.problem`)

### Requisito: El contexto del trabajador no contiene beans de los otros puntos de entrada ni sus importaciones

El contexto del proceso trabajador NO DEBE contener ningún bean de `com.confia.bootstrap.admin` ni
de `com.confia.bootstrap.portal`, ni los beans que esos puntos de entrada importan para su propia
superficie de API, `ContractSchemas` y `ProcessApiInfo`. El trabajador DEBE declarar sus propios
módulos y NO DEBE heredar el grafo de beans administrativo por el hecho de usar la configuración y
el rol de base de datos administrativos (aclaración de ADR-0003 que fija ADR-0024). El trabajador
NO DEBE contener ninguna cadena de seguridad, ningún bean de Spring Security, `DataSource`,
`RateLimiter` ni materializador del retardo, porque no tiene servidor web. El sistema DEBE romper la construcción si el contexto del
trabajador contiene alguno de ellos.
(Previously: el requisito no enumeraba la cadena de seguridad, el `DataSource`, el limitador ni el
materializador entre lo prohibido en el trabajador.)

#### Escenario: El trabajador recibe una importación ajena

- **DADO** el contexto del trabajador con un bean `ContractSchemas` o `ProcessApiInfo`, o con un
  bean de `bootstrap.admin` o de `bootstrap.portal`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el bean ajeno

#### Escenario: El trabajador arranca sin servidor web y sin beans ajenos

- **DADO** el proceso trabajador arrancado por el camino de producción, sin servidor web y sin base
  de datos
- **CUANDO** se inspecciona su contexto
- **ENTONCES** no contiene ningún bean de los otros dos puntos de entrada ni sus importaciones, y la
  verificación evalúa el contexto real del trabajador

#### Escenario: El trabajador no recibe el borde web

- **DADO** el contexto del trabajador con un bean `SecurityFilterChain`, cualquier otro bean de
  Spring Security, `RateLimiter` o materializador del retardo
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el bean ajeno

### Requisito: Los puntos de entrada se registran de forma explícita, sin escaneo implícito

Cada uno de los tres puntos de entrada DEBE residir en su propio subpaquete de
`com.confia.bootstrap` (`admin`, `portal` y `worker`) y DEBE declarar de forma explícita lo que
importa. Los puntos de entrada NO DEBEN usar `@SpringBootApplication` ni `@ComponentScan`. El portal
y el trabajador DEBEN conservar la exclusión de `DataSourceAutoConfiguration`; el proceso
administrativo NO DEBE excluirla y es el único que registra un `DataSource` (decisión D5).
`ConfiaApplication` DEBE seguir siendo el único método `main` y DEBE conservar la selección del
proceso por `APP_PROFILE` y el arranque del trabajador sin servidor web. El sistema DEBE romper la
construcción si un punto de entrada vuelve a declarar un escaneo de componentes.
(Previously: los tres puntos de entrada conservaban la exclusión de `DataSourceAutoConfiguration`.)

#### Escenario: Un punto de entrada vuelve a escanear

- **DADO** un punto de entrada que declara `@SpringBootApplication` o `@ComponentScan`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el punto de entrada y la anotación prohibida

#### Escenario: Los tres puntos de entrada cumplen la forma explícita

- **DADO** `AdminApplication`, `PortalApplication` y `WorkerApplication` en `bootstrap.admin`,
  `bootstrap.portal` y `bootstrap.worker`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** ninguno usa `@SpringBootApplication` ni `@ComponentScan`, `PortalApplication` y
  `WorkerApplication` excluyen `DataSourceAutoConfiguration`, `AdminApplication` no la excluye, y
  `ConfiaApplication` sigue arrancando cada proceso según `APP_PROFILE`

#### Escenario: El portal o el trabajador dejan de excluir el `DataSource`

- **DADO** `PortalApplication` o `WorkerApplication` sin la exclusión de
  `DataSourceAutoConfiguration`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando el punto de entrada

### Requisito: Los subpaquetes de `bootstrap` no dependen entre sí

El sistema DEBE romper la construcción si una clase de un subpaquete de `com.confia.bootstrap`
(`admin`, `portal` o `worker`) depende de una clase de otro de esos subpaquetes. Esta regla
DEBE tener su propio fixture de prueba permanente que la viole a propósito, con la convención de
dos pruebas del repositorio: una mitad que demuestra que una violación real rompe la construcción y
otra que demuestra que el código de producción real no falla por esta causa.

#### Escenario: Fixture con dependencia entre subpaquetes de `bootstrap`

- **DADO** un fixture de prueba permanente en el que una clase de un subpaquete de `bootstrap`
  depende de una clase de otro subpaquete
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando la clase y la dependencia cruzada

#### Escenario: Código de producción sin dependencias cruzadas

- **DADO** el código de producción real de `bootstrap.admin`, `bootstrap.portal` y
  `bootstrap.worker`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla pasa evaluando los tres subpaquetes reales, sin excepción de conjunto vacío

### Requisito: Nada fuera de `bootstrap` referencia una clase de entrada

El sistema DEBE romper la construcción si una clase fuera de `com.confia.bootstrap` referencia
`AdminApplication`, `PortalApplication` o `WorkerApplication`, que son públicas. Esta regla DEBE
tener su propio fixture de prueba permanente que la viole a propósito, con la misma convención de
dos mitades que las demás reglas de esta capacidad.

#### Escenario: Fixture que referencia una clase de entrada desde fuera de `bootstrap`

- **DADO** un fixture de prueba permanente, fuera de `com.confia.bootstrap`, que referencia una
  clase de entrada
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando la clase y la referencia prohibida

#### Escenario: Código de producción sin referencias externas a clases de entrada

- **DADO** el código de producción real de `apps/api`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** ninguna clase fuera de `bootstrap` referencia una clase de entrada, y la regla evalúa
  código real, sin excepción de conjunto vacío

### Requisito: La aserción sobre db-scheduler en administración y portal es vacua hasta el cambio 9 (brecha con destino: cambio 9)

La verificación de aislamiento DEBE afirmar que ningún bean de `com.github.kagkarlsson` existe en
los contextos administrativo y del portal. Mientras db-scheduler no esté en el camino de clases
(ADR-0018), esa aserción NO DEBE presentarse como una garantía: DEBE declararse de forma explícita
como vacua, en la propia verificación y en esta especificación. La aserción positiva de que el
trabajador sí registra db-scheduler es responsabilidad del **cambio 9**
(`background-jobs-with-db-scheduler`), y este cambio NO DEBE afirmarla.

#### Escenario: La aserción es vacua y está declarada como tal

- **DADO** db-scheduler ausente del camino de clases de `apps/api`
- **CUANDO** se ejecuta la verificación de aislamiento sobre los contextos administrativo y del
  portal
- **ENTONCES** la aserción de ausencia pasa, y la verificación deja declarado de forma explícita que
  pasa por vacío y cita ADR-0018 y el cambio 9; este escenario deja de ser cierto el día que el
  cambio 9 incorpore la biblioteca

#### Escenario: La ausencia de db-scheduler se vuelve efectiva con la biblioteca presente

- **DADO** db-scheduler ya en el camino de clases tras el cambio 9
- **CUANDO** un bean de `com.github.kagkarlsson` aparece en el contexto administrativo o del portal
- **ENTONCES** la misma aserción falla señalando el bean y el proceso, sin necesitar reescribirse

### Requisito: Prueba negativa permanente del inspector de aislamiento

El sistema DEBE mantener una prueba negativa permanente que construya un contexto con una fuga
deliberada —un bean fuera de la lista de permitidos y un bean prohibido nominalmente— y afirme que
el inspector de aislamiento la detecta. Esta prueba demuestra que la verificación de aislamiento
no pasa por vacío: sin ella, un inspector que no inspeccionara nada sería indistinguible de uno
que no encuentra fugas.

#### Escenario: El inspector detecta un bean fuera de la lista

- **DADO** un contexto con un bean de `com.confia.*` en un paquete fuera de la lista de permitidos
- **CUANDO** el inspector lo evalúa
- **ENTONCES** reporta la violación señalando el bean y su paquete

#### Escenario: El inspector detecta un bean prohibido nominalmente

- **DADO** un contexto con un bean de un paquete de la lista de prohibidos del portal, por ejemplo
  `com.confia.bootstrap.admin`
- **CUANDO** el inspector lo evalúa con la lista del portal
- **ENTONCES** reporta la violación señalando el bean y la regla nominal incumplida

#### Escenario: El inspector no reporta un contexto limpio

- **DADO** un contexto cuyos beans de `com.confia.*` pertenecen todos a la lista de permitidos y a
  ningún paquete prohibido
- **CUANDO** el inspector lo evalúa
- **ENTONCES** no reporta ninguna violación, de modo que la detección de los escenarios anteriores
  no se debe a un inspector que rechaza todo

### Requisito: El proceso administrativo arranca sin conexión a la base de datos y sin migrar al arrancar

El proceso administrativo DEBE registrar el `DataSource` de producción y DEBE arrancar su contexto
aunque no exista ninguna base de datos accesible: el arranque NO DEBE realizar una validación
inicial bloqueante de la conexión y NO DEBE ejecutar migraciones de Flyway. Las migraciones siguen
siendo un paso separado del arranque. El portal y el trabajador NO DEBEN registrar ningún
`DataSource` (decisión D5 del propietario). Las pruebas de arranque `*Test` de los tres procesos
DEBEN seguir ejecutándose sin Docker y sin base de datos.

#### Escenario: El proceso administrativo arranca con la base de datos inalcanzable

- **DADO** el proceso administrativo arrancado por su camino de producción con una URL de base de
  datos que no responde en ningún puerto
- **CUANDO** se construye su contexto
- **ENTONCES** el contexto arranca sin error y contiene exactamente un bean `DataSource`
- **Y** ninguna migración de Flyway se ejecuta ni se consulta la tabla de historial durante el
  arranque

#### Escenario: Portal y trabajador no tienen `DataSource`

- **DADO** los procesos del portal y del trabajador arrancados por su camino de producción
- **CUANDO** se inspecciona cada contexto
- **ENTONCES** ninguno contiene un bean `DataSource`, `TransactionRunner` ni `DSLContext`

#### Escenario: Las pruebas de arranque no necesitan Docker

- **DADO** una máquina sin Docker ni PostgreSQL
- **CUANDO** se ejecutan las pruebas `*Test` que arrancan los tres procesos
- **ENTONCES** todas pasan, y ninguna de ellas es una prueba `*IT` ni usa Testcontainers

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

### Requisito: Reglas de dependencia de la capa `web` de producción

El sistema DEBE romper la construcción si una clase de un paquete `web` de producción (el de un
módulo de negocio o `com.confia.shared.web`) depende de una clase de `infrastructure`, del paquete
generado de jOOQ o del `domain` de otro módulo. `com.confia.shared` NO DEBE depender de ninguna clase
de un módulo de negocio; en particular, `shared.security` y `shared.web` NO DEBEN depender de
`identity`. Cada regla DEBE tener su propio fixture de prueba permanente que la viole a propósito,
con la convención de dos mitades del repositorio: una que demuestra que una violación real rompe la
construcción y otra que demuestra que el código de producción real no falla por esa causa.
La capa `Web` de `LayeredArchitectureTest` es obligatoria desde el cambio 3 y no tiene
`optionalLayer`; este cambio conserva esa condición y `shared.web` aporta clases reales a esa capa,
aunque no entrega ningún controlador de producción.

#### Escenario: Fixture con una clase `web` que depende de `infrastructure`

- **DADO** un fixture de prueba permanente en el que una clase de un paquete `web` importa una clase
  de `infrastructure`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando la clase y la dependencia prohibida

#### Escenario: Fixture con `shared` que depende de `identity`

- **DADO** un fixture de prueba permanente en el que una clase de `com.confia.shared.web` o de
  `com.confia.shared.security` importa una clase de `com.confia.identity`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando la clase y la referencia prohibida

#### Escenario: Código de producción sin dependencias prohibidas

- **DADO** el código de producción real de los paquetes `web` y de `shared`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** las reglas pasan evaluando clases reales de `shared.web` y `shared.security`, sin
  excepción de conjunto vacío para esas dos reglas

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
### Requisito: La espera bloqueante del retardo se confina al materializador

El sistema DEBE romper la construcción si una clase de producción en un paquete `web`,
`application` o `domain` llama a `Thread.sleep`, `TimeUnit.sleep`, `LockSupport.park*` u
`Object.wait`, salvo el materializador del retardo. `NoBlockingWaitInIdentityTest` DEBE
mantenerse sin cambios. La regla DEBE tener su propio fixture permanente con la convención de dos
mitades, y NO DEBE evaluar un conjunto vacío.

#### Escenario: Fixture con una espera fuera del materializador

- **DADO** un fixture de prueba permanente en un paquete `web` o `application` que invoca
  `Thread.sleep`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando la clase y la llamada

#### Escenario: El materializador es la única clase con espera

- **DADO** el código de producción real
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla pasa evaluando clases reales, y la única llamada de espera en esos paquetes
  pertenece al materializador del retardo

#### Escenario: Se mantiene la prohibición en `identity`

- **DADO** una clase de `identity.application` o `identity.domain` que espera de forma bloqueante
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** `NoBlockingWaitInIdentityTest` sigue fallando señalando la clase

### Requisito: Instantánea aprobada del mapa de rutas del portal

El sistema DEBE generar, en cada ejecución de `./mvnw verify`, el mapa de rutas que registra el
contexto del portal y DEBE compararlo contra una instantánea aprobada y comprometida en el
repositorio (ADR-0024, verificación 5). La instantánea entregada por este cambio DEBE ser el
conjunto vacío, declarado de forma explícita, porque el portal arrancado con el perfil por omisión
(configuración de producción) deniega toda ruta y no registra ninguna ruta de aplicación; las
entradas de documentación de springdoc solo existen con `local` y `preprod` y no forman parte de la
instantánea. La comparación NO
DEBE sobrescribir la instantánea nunca: actualizarla DEBE ser un paso explícito y separado, visible
en el diff del PR. La verificación DEBE fallar si el archivo de la instantánea no existe, de modo
que un mapa vacío no pase por ausencia de archivo.

#### Escenario: Una ruta nueva en el portal rompe la construcción

- **DADO** la instantánea vacía comprometida
- **CUANDO** el portal registra una ruta que la instantánea no contiene
- **ENTONCES** `./mvnw verify` falla señalando la ruta añadida
- **Y** la instantánea comprometida queda intacta después de la ejecución

#### Escenario: Instantánea ausente

- **DADO** el archivo de la instantánea eliminado
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la construcción falla señalando que falta la instantánea, en vez de tratar el mapa
  vacío como coincidente

#### Escenario: Mapa idéntico a la instantánea

- **DADO** el portal tal como lo entrega este cambio y la instantánea vacía sin alterar
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** el mapa generado coincide con la instantánea y la verificación pasa

### Requisito: El mecanismo HTTP de idempotencia no se aplica a ningún endpoint de identidad

El sistema DEBE romper la construcción si una clase de producción en un paquete `web` aplica el
mecanismo HTTP de `Idempotency-Key` a un endpoint de inicio de sesión, refresco, MFA o recuperación
de contraseña, o a cualquier otro endpoint de `identity`, porque `IdempotentExecutor` persiste y
reproduce el cuerpo de la respuesta y los tokens quedarían almacenados en claro (decisión D3; `CLAUDE.md`,
reglas 11 y 13). La regla DEBE tener su propio fixture permanente con la convención de dos mitades.

#### Escenario: Fixture con un endpoint de identidad que usa la idempotencia

- **DADO** un fixture de prueba permanente en un paquete `web` de `identity` que aplica el
  mecanismo de `Idempotency-Key`
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** la regla falla sobre el fixture, señalando la clase y el uso prohibido

#### Escenario: Código de producción sin uso en identidad

- **DADO** el código de producción real
- **CUANDO** se ejecuta `./mvnw verify`
- **ENTONCES** ninguna clase de producción de `identity` aplica el mecanismo, y el único endpoint que
  lo aplica vive en el árbol de pruebas; fuera de `shared.web.idempotency`, solo la configuración de
  `shared.web.edge` lo referencia, para registrarlo
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

### Requisito: Los inventarios de ausencia que los endpoints de sesión rompen se invierten, cada uno con su control negativo

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
- **ENTONCES** la puerta pasa
- **Y** la duración de la construcción **sin mutación** (`./mvnw verify` sin `-Pmutation-gate`) se mide y se registra, y su presupuesto es de 15 minutos
- **Y** el perfil de mutación (`-Pmutation-gate`, PIT) tiene su propio tiempo, que se declara por separado y no cuenta contra ese presupuesto

> **Decisión del propietario 2026-10-23 (brecha G-4, opción a).** BI57 se reescribe: el presupuesto de 8 minutos mide solo la construcción sin mutación. La puerta de tiempo permanece en la construcción normal (`verify`) y no se mueve al perfil de mutación. El texto anterior («la construcción sin mutación se mantiene dentro del presupuesto de 8 minutos» como única condición de la puerta) queda sustituido por esta redacción.

> **Decisión del propietario 2026-10-24 (BI57, segunda parte): el presupuesto de la construcción sin mutación sube de 8 a 15 minutos. Medición que la motiva: 11 min 46 s al cierre de la tarea 4.1, 5 min 24 s al inicio del cambio, y unos 70 s de la puerta de tiempo dentro de esa cifra. Seguimiento registrado: recortar el tiempo de las pruebas de integración (dueño: un cambio futuro).**

### Requisito: Las pruebas de arranque `*Test` no necesitan la clave CSRF versionada

Las pruebas `*Test` que arrancan el proceso administrativo DEBEN generar la clave CSRF al ejecutarse y NO DEBEN
requerir Docker. Ningún perfil ni archivo versionado DEBE contener un valor para `confia.security.csrf.key`.

#### Escenario: [BI58] Arranque del proceso administrativo con clave CSRF generada

- **DADO** una máquina sin Docker
- **CUANDO** se ejecutan las pruebas `*Test` de arranque
- **ENTONCES** el proceso administrativo arranca con una clave generada por la prueba y ningún archivo
  versionado contiene el valor


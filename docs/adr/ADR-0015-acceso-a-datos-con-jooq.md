# ADR-0015: Acceso a datos con jOOQ y un componente transaccional único

- **Estado:** Aceptado
- **Fecha:** 2026-09-15
- **Decisores:** Propietario del producto y arquitecto
- **Contexto técnico:** Capa `infrastructure` de cada módulo de `apps/api`, `shared/security` (contexto de sesión y transacciones), migraciones de Flyway, versión de PostgreSQL en todos los entornos, construcción con Maven, pruebas de integración. Resuelve el tema abierto "estrategia de acceso a datos" de ADR-0013.

## Contexto y problema

ADR-0013 dejó abierta la estrategia de acceso a datos con una restricción ya fijada: **los asientos
del libro mayor y los documentos fiscales nunca se persisten como entidades JPA gestionadas**, porque
el seguimiento automático de cambios de un ORM puede emitir una actualización implícita sobre un
registro que, por ADR-0007, no se edita jamás.

Además, varias decisiones aceptadas exigen un control fino de cada sentencia SQL:

- **ADR-0010:** bloqueo pesimista de la cuenta del estudiante (`SELECT ... FOR UPDATE`), aislamiento
  `SERIALIZABLE` para el cierre de caja y la emisión de correlativo, y reintento ante errores de
  serialización.
- **ADR-0003 y `docs/03-seguridad.md` sección 6.2:** el contexto de seguridad a nivel de fila se
  establece como primera sentencia de cada transacción, con `set_config(..., true)` y parámetros
  vinculados, desde **un componente transaccional único**. Ningún repositorio abre transacciones.
- **ADR-0004:** conversión explícita entre `NUMERIC(14,4)` más la columna de moneda y el objeto de
  valor `Money`.
- **ADR-0002:** cada módulo es dueño de sus tablas y ningún módulo accede a los datos de otro.
- **`CLAUDE.md`, regla 12:** nunca concatenación de cadenas para construir SQL.
- **`docs/01-arquitectura.md`:** los reportes necesitan SQL con agregaciones pesadas.

Si no se decide, cada módulo elige su propia forma de acceder a la base, y el sistema termina con un
ORM en unos módulos, SQL manual en otros y ninguna regla verificable sobre quién abre transacciones.

## Factores de decisión

| Factor | Peso | Justificación |
|---|---|---|
| Ninguna escritura implícita sobre datos financieros | Muy alto | Restricción de ADR-0013 y ADR-0007. |
| Control explícito de transacciones, aislamiento y bloqueos | Muy alto | Requisito de ADR-0010 y del contexto de seguridad de `docs/03`. |
| Detección temprana de consultas rotas por un cambio de esquema | Alto | Con un solo desarrollador, un error de consulta debe romper la construcción, no aparecer en producción. |
| Un solo paradigma de acceso a datos en todo el backend | Alto | Dos formas de hacer lo mismo duplican la carga cognitiva (riesgo R01). |
| Protección contra inyección SQL por construcción | Alto | Regla 12 de `CLAUDE.md`. |
| Soporte de SQL avanzado de PostgreSQL | Medio | Reportes, funciones de ventana, `FOR UPDATE`, restricciones diferidas. |
| Costo de licencia y dependencia de versiones | Medio | Presupuesto modesto; no atarse a una versión antigua del motor. |
| Curva de aprendizaje | Medio | El desarrollador domina Spring; una herramienta nueva tiene costo real. |

## Opciones consideradas

### Opción A: JPA con Hibernate en todo el backend

**Ventajas.** Es la opción más conocida del ecosistema Spring. Mucho CRUD sale casi gratis.

**Desventajas.** Contradice directamente la restricción de ADR-0013 para el libro mayor y los
documentos fiscales. El seguimiento automático de cambios, la carga diferida y el vaciado implícito
del contexto de persistencia hacen difícil saber qué SQL se ejecuta y cuándo, que es justo lo que
ADR-0010 y el contexto de seguridad necesitan controlar. **Se descarta.**

### Opción B: Híbrido, JPA para módulos de catálogo y jOOQ o JDBC para el núcleo financiero

**Ventajas.** Aprovecha la productividad de JPA en los módulos que se parecen a un CRUD
(`organization`, `catalog`) y protege el núcleo financiero.

**Desventajas.** Dos paradigmas de acceso a datos, dos formas de mapear, dos formas de probar y dos
modelos mentales de transacción en el mismo proceso. La frontera entre "módulo CRUD" y "módulo
financiero" se erosiona con el tiempo: una beca parece catálogo hasta que genera un asiento. **Se
descarta** por carga cognitiva y porque la restricción de ADR-0013 quedaría defendida solo por la
disciplina de no usar JPA en el lugar equivocado.

### Opción C: jOOQ en todo el backend

jOOQ genera código Java a partir del esquema real de la base de datos y permite escribir SQL con
tipos verificados por el compilador. Cada sentencia es explícita: no hay seguimiento de cambios ni
escrituras implícitas.

**Ventajas.**

- **Ninguna escritura implícita.** Solo se ejecuta el SQL que el código escribe, lo cual cumple por
  construcción la restricción de ADR-0013 en todos los módulos, no solo en el núcleo financiero.
- **Un cambio de esquema que rompe una consulta rompe la compilación.** El código se genera desde las
  migraciones de Flyway aplicadas sobre un PostgreSQL real durante la construcción.
- **Parámetros vinculados por defecto**, que protegen contra inyección SQL sin depender de la
  disciplina de cada consulta.
- **SQL completo de PostgreSQL:** `FOR UPDATE`, expresiones de tabla comunes, funciones de ventana y
  consultas de reporte sin salir de la herramienta.
- **Se integra con las transacciones de Spring**: Spring Boot configura jOOQ sobre la misma fuente de
  datos y el mismo gestor de transacciones, de modo que el componente transaccional único, las
  consultas de jOOQ y el registro de eventos de Spring Modulith participan en la misma transacción.
- **La edición de código abierto es gratuita** (licencia Apache 2.0) para bases de datos de código
  abierto como PostgreSQL.

**Desventajas.**

- **La edición de código abierto solo soporta la versión más reciente de PostgreSQL.** jOOQ 3.21 en
  su edición de código abierto soporta PostgreSQL 18; el soporte de versiones anteriores requiere una
  edición comercial (desde 99 € por puesto de desarrollo al año, sin impuestos, en la edición
  Express). Esto ata las actualizaciones de jOOQ a las actualizaciones mayores de PostgreSQL.
- La generación de código necesita Docker durante la construcción, para levantar un PostgreSQL
  temporal y aplicar las migraciones.
- Hay que escribir el mapeo entre filas y objetos de dominio a mano. Es más código que un ORM, pero es
  código explícito.
- Curva de aprendizaje real si el desarrollador no ha usado jOOQ.

### Opción D: Spring Data JDBC

**Ventajas.** Más simple que JPA, sin carga diferida ni seguimiento de cambios, y orientado a
agregados, lo que encaja con el modelado de dominio.

**Desventajas.** Al actualizar un agregado, Spring Data JDBC **borra y vuelve a insertar todas sus
entidades referenciadas**, porque no rastrea su estado. En CONFIA eso es incompatible con el libro
mayor (los asientos nunca se borran) y fallaría contra los permisos de base de datos, que no conceden
`DELETE` sobre tablas financieras. **Se descarta.**

### Opción E: `JdbcClient` de Spring con SQL escrito a mano

**Ventajas.** Cero dependencias adicionales, cero licencias, sin generación de código y sin atadura a
la versión de PostgreSQL. Todo el SQL es visible y el desarrollador ya conoce la herramienta.

**Desventajas.** Las consultas son cadenas de texto: un cambio de esquema que las rompe solo se
descubre al ejecutar las pruebas de integración, no al compilar. La protección contra inyección
depende de usar siempre parámetros, sin ayuda del compilador. Más código repetitivo en el mapeo.

**Es una opción legítima.** Si la atadura de versiones de la opción C resultara un problema real,
esta es la alternativa, y ADR-0008 ya exige pruebas de integración contra PostgreSQL real que
reducen su principal desventaja.

## Decisión

**Se adopta la opción C: jOOQ, en su edición de código abierto, como única herramienta de acceso a
datos del backend, con PostgreSQL 18 en todos los entornos y un componente transaccional único en
`shared/security`. JPA, Hibernate y Spring Data JDBC quedan fuera del proyecto.**

El factor determinante es la combinación de los dos factores de mayor peso: **ninguna escritura
implícita sobre datos financieros y control explícito de transacciones.** jOOQ cumple ambos en todo
el backend, no solo en el núcleo financiero, con un único paradigma. La detección de consultas rotas
en la compilación es el segundo argumento: para un solo desarrollador, vale más que la comodidad de
un ORM.

La atadura de versiones de la edición de código abierto se acepta porque `docs/01-arquitectura.md`
exige PostgreSQL 16 o superior, PostgreSQL 18 cumple ese requisito, y RDS for PostgreSQL ya lo
ofrece (ADR-0014).

### Reglas de la decisión

1. **Versión de PostgreSQL: 18**, la misma en desarrollo local, pruebas, preproducción y producción.
   Cada actualización mayor de PostgreSQL se planifica junto con la actualización de jOOQ que la
   soporte. Si en algún momento hubiera que quedarse en una versión que la edición de código abierto
   ya no soporta, la salida es una licencia Express, no una bifurcación del código.
2. **Generación de código desde las migraciones.** En cada construcción se levanta un PostgreSQL 18
   temporal, se aplican las migraciones de Flyway y jOOQ genera las clases. El código generado no se
   compromete al repositorio. El mecanismo concreto (por ejemplo, el complemento de generación con
   Testcontainers) se valida en F0.
3. **Cada módulo es dueño de sus tablas y sus migraciones** (ADR-0002). Las tablas llevan el prefijo
   de su módulo, y un módulo solo usa las clases generadas de sus propias tablas.
4. **jOOQ vive solo en `infrastructure`.** Los repositorios implementan puertos de `application` y
   convierten filas en objetos de dominio de forma explícita. Ninguna clase generada ni ningún tipo de
   jOOQ sale de la capa `infrastructure`.
5. **`Money` se construye en un solo lugar.** Un convertidor compartido transforma el par
   `NUMERIC(14,4)` más moneda en `Money` y viceversa (ADR-0004). Ningún repositorio construye un
   importe por su cuenta.
6. **Repositorios del libro mayor y de documentos fiscales solo insertan y leen.** No exponen
   operaciones de actualización ni de borrado, y los permisos de base de datos lo respaldan
   (ADR-0003 y ADR-0007).
7. **Componente transaccional único.** En `shared/security` vive el único componente que abre
   transacciones. Recibe el nivel de aislamiento requerido (por defecto `READ COMMITTED`,
   `SERIALIZABLE` donde ADR-0010 lo exige), establece el contexto de seguridad a nivel de fila como
   primera sentencia con parámetros vinculados (`docs/03` sección 6.2), ejecuta el caso de uso y
   reintenta un número acotado de veces ante errores de serialización o de interbloqueo. Es el
   "componente transversal de reintento" que citan ADR-0004 y ADR-0010.
8. **Bloqueo pesimista explícito.** La cuenta del estudiante se bloquea con `SELECT ... FOR UPDATE`
   dentro del componente transaccional, como exige ADR-0010.
9. **SQL plano solo con aprobación.** La API de SQL en texto libre de jOOQ se prohíbe fuera de una
   lista aprobada de clases de reporte, y aun ahí con parámetros vinculados.
10. **Eventos de dominio persistidos con JDBC.** El registro de publicación de eventos de Spring
    Modulith usa su almacenamiento JDBC, sin JPA.
11. **Reportes pesados en la réplica de lectura** a partir de la fase dos, con una fuente de datos de
    solo lectura y el rol `confia_readonly` (ADR-0003).

## Consecuencias

**Positivas:**

- La restricción de ADR-0013 se cumple por construcción en todo el backend: no existe ningún ORM que
  pueda escribir sin que el código lo pida.
- Un cambio de esquema que rompe una consulta rompe la compilación.
- Toda transacción pasa por un solo punto que fija el aislamiento, el contexto de seguridad y el
  reintento. Auditar cómo se abren las transacciones es revisar un solo componente.
- El SQL que se ejecuta es el SQL que se lee en el código, lo cual facilita la revisión de seguridad
  y el diagnóstico de rendimiento.
- Sin costo de licencia mientras se use la versión más reciente de PostgreSQL.

**Negativas y costos aceptados:**

- **Atadura entre jOOQ y la versión de PostgreSQL.** Se acepta con PostgreSQL 18 y con la licencia
  Express como salida si hiciera falta.
- **Los documentos que fijan PostgreSQL 16** (por ejemplo, la imagen de PostgreSQL de
  `docs/05-infraestructura-y-despliegue.md` y la base de las pruebas) deben actualizarse a 18.
- **La construcción necesita Docker**, igual que las pruebas de integración de ADR-0008.
- **Más código de mapeo** que con un ORM, y curva de aprendizaje de jOOQ.
- **Sin carga diferida ni relaciones automáticas.** Cada consulta trae exactamente lo que pide, lo cual
  es deliberado.

**Riesgos y mitigaciones:**

| Riesgo | Mitigación |
|---|---|
| Alguien agrega JPA o Spring Data JDBC por comodidad | `maven-enforcer-plugin` prohíbe las dependencias de Hibernate, Jakarta Persistence, Spring Data JPA y Spring Data JDBC. |
| Un repositorio abre su propia transacción y se salta el contexto de seguridad | Regla de ArchUnit: `@Transactional` y las plantillas de transacción solo pueden usarse dentro del componente transaccional de `shared/security`. |
| Un tipo de jOOQ se filtra a `domain` o `application` | Regla de ArchUnit: `org.jooq` y el paquete generado solo pueden usarse desde paquetes `infrastructure`. |
| Un módulo lee las tablas de otro | Regla de ArchUnit que asocia las clases generadas de cada prefijo de tabla a su módulo. |
| Inyección SQL por SQL plano | Regla de ArchUnit que prohíbe los métodos de SQL plano de jOOQ fuera de la lista aprobada de reportes. |
| El entorno local o de pruebas usa otra versión de PostgreSQL | Script en integración continua que compara la versión de PostgreSQL de Docker Compose, de Testcontainers y de la configuración de RDS. Divergencia igual a falla. |
| Una nueva versión de jOOQ exige una versión de PostgreSQL que aún no está en RDS | La actualización de jOOQ se hace solo cuando RDS ofrece la versión de PostgreSQL correspondiente; mientras tanto se mantiene la versión de jOOQ vigente. |

## Cumplimiento y verificación

Todo lo siguiente se ejecuta en integración continua y una falla rompe la construcción.

1. **Dependencias prohibidas.** `maven-enforcer-plugin` con `bannedDependencies` sobre Hibernate,
   Jakarta Persistence, Spring Data JPA y Spring Data JDBC.
2. **jOOQ confinado.** Regla de ArchUnit: ninguna clase fuera de `infrastructure` importa `org.jooq`
   ni el paquete generado.
3. **Transacciones centralizadas.** Regla de ArchUnit sobre `@Transactional`, `TransactionTemplate` y
   el gestor de transacciones, permitidos solo en el componente transaccional de `shared/security`.
4. **Propiedad de tablas.** Regla de ArchUnit que impide a un módulo usar clases generadas de tablas
   con el prefijo de otro módulo.
5. **Sin SQL plano no aprobado.** Regla de ArchUnit sobre los métodos de SQL plano de jOOQ, con lista
   de excepciones aprobada y revisada.
6. **Esquema y código sincronizados.** La generación de código desde las migraciones corre en cada
   construcción; una consulta incompatible con el esquema no compila.
7. **Contexto y reintento.** Prueba de integración que verifica que el componente transaccional
   establece el contexto de seguridad antes de cualquier consulta, y prueba de concurrencia que
   fuerza un error de serialización y afirma que la operación termina correctamente tras el
   reintento (ADR-0010).
8. **Inmutabilidad.** Prueba que verifica que los puertos de repositorio del libro mayor y de
   documentos fiscales no declaran operaciones de actualización ni de borrado, más la prueba de
   matriz de permisos de ADR-0003.
9. **Versión única de PostgreSQL.** Script que compara la versión declarada en Docker Compose, en
   Testcontainers y en la configuración de RDS.

## Referencias

- ADR-0002: monolito modular frente a microservicios
- ADR-0003: separación entre administración y portal
- ADR-0004: PostgreSQL y representación monetaria
- ADR-0007: libro mayor de doble partida
- ADR-0008: estrategia de pruebas
- ADR-0010: idempotencia y concurrencia financiera
- ADR-0013: backend en Java con Spring Boot
- ADR-0014: AWS como proveedor de nube
- `docs/03-seguridad.md`, sección 6.2
- [Matriz de soporte de jOOQ](https://www.jooq.org/download/support-matrix) y [ediciones y precios](https://www.jooq.org/download/)
- [Licencia de jOOQ](https://www.jooq.org/legal/licensing)
- [Spring Data JDBC: persistencia de entidades](https://docs.spring.io/spring-data/relational/reference/jdbc/entity-persistence.html)
- [RDS for PostgreSQL soporta la versión mayor 18](https://aws.amazon.com/about-aws/whats-new/2025/11/amazon-rds-postgresql-major-version-18)
- [Generación de código jOOQ con Testcontainers y Flyway](https://github.com/testcontainers/testcontainers-jooq-codegen-maven-plugin)

# Apply progress: jooq-flyway-testcontainers-wiring — PR A1

Scope: PR A1 only (tasks 1.1–1.10 of `tasks.md`). Branch
`change/jooq-flyway-testcontainers-wiring`, base `main`.

## Task 1.1 — Sonda S1 (resultado real, ejecutado fuera del árbol del repositorio)

Ejecutada en `%TEMP%/s1-probe/` (Windows temp, fuera de `Confia/`), con `JAVA_HOME` en JDK 25 y
`MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT`. Ningún archivo de esa carpeta se
compromete al repositorio.

### (a) `testcontainers-jooq-codegen-maven-plugin:0.0.4` sobre JDK 25, contra `postgres:18-alpine`

**Resultado: funciona, pero solo tras dos sobrescrituras de dependencia del propio complemento.**

El complemento fija en su propio POM `jooq.version=3.18.3` y `testcontainers.version=1.19.1`,
independientemente de lo que declare el proyecto consumidor. Con esas versiones **por defecto**:

1. **Testcontainers 1.19.1 no detecta Docker en esta máquina.** Falla con
   `Could not find a valid Docker environment` contra el pipe con nombre de Windows
   (`npipe:////./pipe/docker_engine`), probando también `dockerDesktopLinuxEngine` y `docker_cli`
   explícitos vía `DOCKER_HOST`: los tres devuelven una respuesta `/info` vacía o 404. El propio
   `docker` CLI y Testcontainers **2.0.5** (usado directamente, ver más abajo) sí conectan sin
   problema en la misma máquina y la misma sesión — el fallo es específico de la versión vieja del
   cliente Docker que este complemento trae empaquetada, no de Docker Desktop ni de JDK 25.
2. **jOOQ 3.18.3 no introspecciona correctamente el catálogo de PostgreSQL 18.** Con Testcontainers
   ya resuelto (ver punto siguiente), la generación falla en `getRelations`/`getPrimaryKey` con
   `IllegalArgumentException: Field (key_seq) is not contained in Row (...)`, y la clase de tabla
   (`ProbeWidget.java`) **no se genera** (solo el `Record`, el catálogo y el esquema). jOOQ 3.18.3
   es anterior al soporte real de PostgreSQL 18.

**Solución aplicada y verificada: sobrescribir las dependencias propias del complemento** en el
bloque `<plugin><dependencies>` (mecanismo estándar de Maven, no una desviación de ADR-0015 ni una
bandera de omisión — el mismo patrón que `apps/api/pom.xml` ya usa para mediar `archunit`):

```xml
<plugin>
  <groupId>org.testcontainers</groupId>
  <artifactId>testcontainers-jooq-codegen-maven-plugin</artifactId>
  <version>0.0.4</version>
  <dependencies>
    <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers</artifactId><version>2.0.5</version></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-postgresql</artifactId><version>2.0.5</version></dependency>
    <dependency><groupId>org.jooq</groupId><artifactId>jooq-codegen</artifactId><version>3.21.7</version></dependency>
    <dependency><groupId>org.jooq</groupId><artifactId>jooq-meta</artifactId><version>3.21.7</version></dependency>
  </dependencies>
  ...
</plugin>
```

Con estas cuatro sobrescrituras (alineando el complemento a las MISMAS versiones que gestiona el
BOM de Spring Boot 4.1.1 para el resto del proyecto — jOOQ 3.21.7, Testcontainers 2.0.5, decisión 3
de `design.md`), `./mvnw generate-sources` termina en `BUILD SUCCESS`, arranca
`postgres:18-alpine`, aplica la migración de prueba y genera `ProbeWidget.java` completo con su
campo `ID` tipado. **Consecuencia según `design.md` §10: S1.3 no falla — se adopta la ruta A
(principal), con esta sobrescritura de dependencias documentada como parte de la configuración de
la tarea 1.7.** No se activa la ruta B ni se eleva ADR: la regla 2 de ADR-0015 se cumple con la
ruta que la propia ADR nombra como ejemplo.

### (b) API real de Testcontainers 2.0.5 frente al patrón de `docs/06` §14.2

**Confirmado por lectura de bytecode y por ejecución real:**

- `org.testcontainers.containers.PostgreSQLContainer<SELF>` (el paquete que usa el patrón de
  `docs/06` §14.2) **sigue existiendo** en `testcontainers-postgresql:2.0.5`, como clase de
  compatibilidad genérica (`@Deprecated`, javac lo confirma con "uses or overrides a deprecated
  API"). El patrón `new PostgreSQLContainer<>(DockerImageName.parse(...))` de `docs/06` **compila y
  ejecuta sin cambios** contra 2.0.5. La ubicación canónica nueva es
  `org.testcontainers.postgresql.PostgreSQLContainer` (sin genérico), que no es necesario adoptar
  para PR A1.
- **Diferencia real y decisiva con el ejemplo de `docs/06` §14.2:** `postgres:18-alpine` **rechaza
  arrancar** con `withTmpFs(Map.of("/var/lib/postgresql/data", ...))` (el path exacto que usa el
  ejemplo de `docs/06`). Mensaje real del contenedor (confirmado con `docker run` manual y con el
  propio Testcontainers, exit code 1, verificado dos veces):

  > in 18+, these Docker images are configured to store database data in a format which is
  > compatible with "pg_ctlcluster" (...). There appears to be PostgreSQL data in:
  > /var/lib/postgresql/data (unused mount/volume). The suggested container configuration for 18+
  > is to place a single mount at /var/lib/postgresql...

  (`docker-library/postgres#1259`.) **Corrección aplicada:** `withTmpFs(Map.of("/var/lib/postgresql",
  "rw,size=..."))`, montando el directorio padre, no `.../data`. Verificado: con ese único cambio el
  contenedor arranca, aplica el `CREATE DATABASE` inicial y queda `ready to accept connections`.
  `docs/06-estrategia-de-testing.md` §14.2 queda desactualizado en este punto para PostgreSQL 18;
  `PostgresIntegrationTest` (tarea 1.5) usa el path corregido, no el de `docs/06`.
- `withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off")` funciona sin cambios.
  `max_connections=200` del ejemplo de `docs/06` no se probó explícitamente pero no interactúa con
  el hallazgo anterior; se mantiene en `PostgresIntegrationTest`.

### (c) ¿El `DSLContext` de Spring Boot 4.1 se une a la transacción de la prueba?

**Hallazgo previo y más importante que la pregunta original: Spring Boot 4.1.1 NO trae
autoconfiguración de jOOQ.** Se verificó por inspección directa del jar
`spring-boot-autoconfigure-4.1.1.jar`: cero clases bajo `org/springframework/boot/autoconfigure/jooq/`,
cero menciones de jOOQ en `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
La clase `JooqAutoConfiguration` que existía en la línea 3.x de Spring Boot **no existe en 4.1**.
Una prueba `@SpringBootTest` con `@EnableAutoConfiguration` y una fuente de datos real **no obtiene
ningún bean `DSLContext`** — `@Autowired DSLContext` falla con `NoSuchBeanDefinitionException`.
**Esto contradice el supuesto de `design.md` decisión 7** («el contexto trae fuente de datos,
Flyway y DSLContext y nada más»): el `DSLContext` no llega gratis y debe declararse explícitamente.

**Con un bean `DSLContext` declarado explícitamente** (mismo mecanismo que usaba la
autoconfiguración retirada: `DataSourceConnectionProvider` envolviendo un
`TransactionAwareDataSourceProxy`):

```java
@Bean
DSLContext dsl(DataSource dataSource) {
    var connectionProvider =
        new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource));
    return DSL.using(new DefaultConfiguration()
        .set(connectionProvider)
        .set(SQLDialect.POSTGRES));
}
```

**Verificado con una prueba real:** dentro de un método `@Transactional` de prueba,
`dsl.execute("select set_config('probe.marker', ?, true)", "expected-value")` seguido de
`dsl.fetchOne("select current_setting('probe.marker', true)")` en la MISMA transacción de Spring
devuelve `expected-value`. **La pregunta (c) original queda respondida: SÍ se une**, siempre que el
bean se declare así. No hace falta el respaldo de "misma conexión obtenida explícitamente" que
`design.md` §10 preveía para el caso de fallo — el mecanismo estándar de jOOQ + Spring funciona,
solo había que declararlo a mano porque la autoconfiguración que se asumía no existe en Spring Boot
4.1.

**Consecuencia para la tarea 1.5:** `IntegrationTestApplication` (o `PostgresIntegrationTest`) DEBE
declarar este `@Bean DSLContext` explícito. La propiedad `spring.jooq.sql-dialect` de
`application.yml` (tal como la nombra la tarea 1.5) **no tiene ningún efecto** en Spring Boot 4.1
porque no hay ninguna autoconfiguración que la lea: se documenta como nota en el propio archivo en
vez de dejarla como configuración muerta sin explicación.

### Resumen de la consecuencia según `design.md` §10 y `tasks.md` 1.1

- S1.1: ya verificado por el orquestador (no repetido).
- S1.2: el complemento resuelve. ✅
- S1.3: el complemento **sí** corre sobre JDK 25 y levanta `postgres:18-alpine`, **una vez
  sobrescritas sus dependencias propias** de Testcontainers (1.19.1→2.0.5) y jOOQ (3.18.3→3.21.7)
  en el `<plugin><dependencies>` del propio `app/pom.xml`. Se adopta **ruta A**, con esa
  sobrescritura documentada como parte del cableado de la tarea 1.7. No se activa ruta B, no se
  eleva ADR.
- S1.4: verificado con una migración real de dos ubicaciones de Flyway (una `beforeMigrate__` sin
  contraseña) y un `GRANT`: `BUILD SUCCESS`, `Applied 1 flyway migrations` sobre la ubicación real,
  rol de solo-generación creado por el callback, `GRANT` aplicado sin error.
- S1.5: patrón de `docs/06` §14.2 compila contra Testcontainers 2.0.5 (paquete de compatibilidad),
  con una diferencia real y corregida: `withTmpFs` debe montar `/var/lib/postgresql`, no
  `/var/lib/postgresql/data`, para `postgres:18-alpine`.
- S1.6: el `DSLContext` sí se une a la transacción de la prueba, con un bean declarado a mano
  porque Spring Boot 4.1 no trae autoconfiguración de jOOQ (hallazgo adicional, documentado arriba).

**Ninguna consecuencia de "ninguna ruta con Docker funciona" aplica.** No se detiene la aplicación,
no se eleva ningún ADR que reemplace ADR-0015 regla 2.

---

## Estado de tareas

- [x] 1.1 — Sonda S1 completa, evidencia registrada arriba.
- [x] 1.2 — `confia.postgres.image` y la versión del complemento de generación declarados en
      `apps/api/pom.xml` (propiedades + `pluginManagement`). `./mvnw -B -N validate`: `BUILD
      SUCCESS`, las tres reglas del enforcer pasan. No se fijaron coordenadas 2.x de Testcontainers
      en `dependencyManagement`: se confirmará en la tarea 1.3 si el BOM de Spring Boot 4.1.1 ya las
      gestiona (evidencia de `design.md` sección 2 indica que sí, bajo los nombres nuevos
      `testcontainers-postgresql`/`testcontainers-junit-jupiter`).
- [x] 1.3 — jOOQ, Flyway (`flyway-core` + `flyway-database-postgresql`), el controlador
      PostgreSQL y Testcontainers 2.x (`testcontainers-postgresql`, `testcontainers-junit-jupiter`,
      alcance `test`) añadidos a `apps/api/app/pom.xml`, sin versión en ninguno. `./mvnw -B -pl
      apps/api/app -am validate`: `BUILD SUCCESS`, `dependencyConvergence` pasa a la primera —
      confirma que el BOM de Spring Boot 4.1.1 ya gestiona los nombres 2.x de Testcontainers, sin
      necesitar ninguna fijación en `dependencyManagement`.

## Hallazgo adicional durante la tarea 1.5 (no cubierto por la sonda S1)

**Spring Boot 4.1 partió su antiguo jar monolítico `spring-boot-autoconfigure` en un módulo por
funcionalidad.** Confirmado leyendo `spring-boot-autoconfigure-4.1.1.jar` completo: solo 258 clases
en total, sin ningún paquete `jdbc`, `sql`, `flyway` ni `jooq`. La autoconfiguración de Flyway vive
ahora en el artefacto separado `org.springframework.boot:spring-boot-flyway` (gestionado por el BOM,
sin versión propia), que **no** llega transitivamente ni con `flyway-core` ni con
`spring-boot-starter-jdbc`. Sin declararlo explícitamente, `FlywayAutoConfiguration` nunca se activa
y `DatabasePipelineIT` falla con `relation "flyway_schema_history" does not exist` porque Flyway
nunca corrió. **Añadido a `apps/api/app/pom.xml`** (tarea 1.5, ver evidencia abajo). jOOQ no tiene
ningún módulo equivalente: sigue sin autoconfiguración alguna (hallazgo de la tarea 1.1, S1.6).

**Consecuencia adicional para `DatabasePipelineIT` (ajuste de mi propia implementación de la tarea
1.4, no del diseño):** con Flyway corriendo mas sin ninguna migración de negocio todavía (`V1` es
tarea 2.2, en PR A2), Flyway crea `flyway_schema_history` pero con **cero filas** («Schema "public"
is up to date. No migration necessary.», log real). La redacción literal de la tarea 1.4 («con al
menos una fila») no es alcanzable dentro del alcance real de A1. Se implementó, en su lugar, una
comprobación de **existencia de la tabla por catálogo** (`to_regclass('public.flyway_schema_history')`,
que no exige ningún `GRANT` porque es una consulta de catálogo, no un acceso a la tabla), que sigue
demostrando honestamente que Flyway corrió como `confia_owner`. La fila real llegará con la
migración de A2, y el `GRANT SELECT` de `confia_admin_app` sobre `flyway_schema_history` también es
parte de esa misma migración (`design.md`, decisión 6) — nunca del script de roles solo de prueba,
que no puede otorgar permisos sobre una tabla que todavía no existe cuando se ejecuta.

## Estado de tareas

- [x] 1.1 — Sonda S1 completa, evidencia registrada arriba.
- [x] 1.2 — `confia.postgres.image` y la versión del complemento de generación en
      `apps/api/pom.xml`. `./mvnw -B -N validate`: `BUILD SUCCESS`.
- [x] 1.3 — jOOQ, Flyway, el controlador PostgreSQL y Testcontainers 2.x añadidos a
      `apps/api/app/pom.xml`. `./mvnw -B -pl apps/api/app -am validate`: `BUILD SUCCESS`,
      `dependencyConvergence` pasa a la primera.
- [x] 1.4/1.5 — **ROJO** (task 1.4): `DatabasePipelineIT.java` creado extendiendo
      `com.confia.support.PostgresIntegrationTest`, que no existe todavía. `./mvnw -B -pl
      apps/api/app -am test-compile`: `COMPILATION ERROR`, `cannot find symbol: class
      PostgresIntegrationTest` en `DatabasePipelineIT.java:25`. **VERDE** (task 1.5): creados
      `db/testing/create-test-roles.sql`, `PostgresIntegrationTest.java`,
      `IntegrationTestApplication.java`, `application.yml`, `application-migrate.yml`; corregido el
      comentario de `ConfiaApplication.MIGRATE`. Añadidas dos dependencias descubiertas como
      necesarias durante esta tarea: `spring-boot-starter-jdbc` (sin la cual no hay
      `DataSourceAutoConfiguration` ni `TransactionAwareDataSourceProxy`) y `spring-boot-flyway`
      (ver hallazgo arriba). `./mvnw -B -pl apps/api/app -am test -Dtest=DatabasePipelineIT`:
      `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`. Contenedor `postgres:18-alpine` arrancado
      con `withUsername("postgres")` (decisión 4), `withTmpFs` sobre `/var/lib/postgresql`
      (corrección S1.5), rol de aplicación `confia_admin_app` verificado por `current_user`, cinco
      roles verificados `rolsuper=false`/`rolbypassrls=false` contra `pg_roles`.
- [ ] 1.6
- [ ] 1.7
- [ ] 1.8
- [ ] 1.9
- [ ] 1.10

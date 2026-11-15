# Diseño: imágenes de contenedor y Compose local (cambio 11a de F0)

- **Cambio:** `dockerfiles-and-local-compose` (F0, cambio 11a; entregable 8 parcial y medición de memoria del
  entregable 1)
- **Fase:** diseñar
- **Fecha:** 2026-11-09
- **Estado:** pendiente de aprobación del propietario
- **Entradas:** `proposal.md` (aprobada el 2026-11-09), `exploration.md`, las cuatro especificaciones de
  `specs/` (`container-runtime` CR01 a CR54; deltas de `build-integrity` BI119 a BI127, `web-edge` WE94 a WE101 y
  `background-jobs` BJ38, BJ73 a BJ75), `docs/05-infraestructura-y-despliegue.md` §3 a §6,
  `docs/07-observabilidad-y-operaciones.md` §9, ADR-0012, ADR-0013, ADR-0016, ADR-0021, ADR-0024 y el código de la
  rama en el punto de partida del cambio.
- **Decisión del propietario posterior a la propuesta:** el `503` de `/health/ready` es una excepción aceptada a
  Problem Details (delta de `web-edge`, WE101).
- **Modo de TDD:** estricto (`openspec/config.yaml:15`). Ejecutor: `./mvnw verify` en `apps/api` con JDK 25 y
  Docker. Los Dockerfiles y Compose no tienen ejecutor determinista de rojo/verde: sus pruebas son Hadolint,
  `docker compose config` con un script de política y el arranque real local (sección 8).

## 1. Enfoque técnico

El cambio convierte el artefacto existente en algo ejecutable en contenedores sin tocar código de negocio. Se
apoya en cinco invariantes que cada rebanada prueba antes de escribir producción:

1. **Un jar, construido una sola vez fuera de `docker build`.** `./mvnw verify` (CI o anfitrión) produce
   `apps/api/app/target/confia-api.jar` en la misma ejecución que genera jOOQ con Testcontainers, prueba, mide
   cobertura y mutación. La imagen solo lo copia y lo descompone en capas (ADR-0021 impide compilar dentro de
   `docker build`).
2. **`migrate` es un contexto Spring mínimo y cerrado** que aplica Flyway como `confia_owner` y termina; los
   otros tres procesos nunca migran.
3. **Salud mínima de Actuator, sin métricas.** Solo el módulo de salud, con dos grupos (`liveness`,
   `readiness`) servidos únicamente en `/health/live` y `/health/ready` del puerto principal, con cuerpo
   `{"status":...}`; ningún `MeterRegistry` y ninguna otra ruta de Actuator.
4. **El trabajador no abre ninguna superficie de red:** su salud es un latido en archivo que refresca el propio
   ciclo de sondeo de db-scheduler contra PostgreSQL.
5. **Secretos solo por entorno, servicio por servicio.** Compose entrega a cada servicio exactamente sus
   variables; nunca un `env_file` compartido, porque el portal y el trabajador se niegan a arrancar con la clave
   privada administrativa en su entorno (`ConfiaApplication.java:28-31`).

```
host / CI                     imagen confia-api (JRE 25, UID 10001, raíz de solo lectura)
./mvnw verify ──► confia-api.jar ──COPY──► extract --layers ──► java -jar /app/confia-api.jar
                                                                   │ APP_PROFILE
                                                                   ├─ admin   ─► AdminApplication  ─► /health/live, /health/ready
                                                                   ├─ portal  ─► PortalApplication ─► /health/live, /health/ready
                                                                   ├─ worker  ─► WorkerApplication ─► latido en /tmp (sin puerto)
                                                                   └─ migrate ─► MigrateApplication ─► Flyway ─► exit 0 | 1
```

## 2. Evidencia obtenida antes de esta fase

### 2.1 Código leído que condiciona el diseño

| Hecho | Evidencia | Consecuencia |
|---|---|---|
| El BOM de Spring Boot se importa como dependencia, no como padre: su `pluginManagement` no se hereda | `apps/api/pom.xml:55-59`, `:117-123` | `spring-boot-maven-plugin` necesita versión explícita (`${spring-boot.version}`) en el `pluginManagement` del padre |
| Failsafe ya está activo en todo el reactor | `apps/api/pom.xml:355-366` | Tras `repackage`, Failsafe pondría el jar reempaquetado (clases en `BOOT-INF/classes`) en el classpath: hay que fijar `classesDirectory` |
| `migrate` reconocido sin comportamiento | `ConfiaApplication.java:96-99`, `AppProfile.java:7-9` | Decisión 2 |
| `application-migrate.yml` habilita Flyway pero ningún código activa el perfil de Spring `migrate` | `application-migrate.yml:5-8`; `ConfiaApplication` no llama a `profiles(...)` | El lanzador activa el perfil de Spring `migrate` explícitamente |
| `spring.main.keep-alive: true` y `flyway.enabled: false` globales | `application.yml:89-93`, `:125-126` | `migrate` cierra su contexto y apaga `keep-alive`; los demás procesos siguen sin migrar |
| Pool perezoso, `initialization-fail-timeout: -1`, `connection-timeout` por omisión (30 s) | `application.yml:112-117` | La preparación con la base caída tardaría 30 s: se fija `connection-timeout` (decisión 5) |
| El trabajador arranca con `WebApplicationType.NONE` | `ConfiaApplication.java:90-95` | Sin servidor web; decisión 6 |
| Planificación en memoria prohibida: `Timer`, `ScheduledExecutorService`, `@Scheduled` | `NoInMemorySchedulingTest.java:18-26` | Un latido con hilo propio queda descartado |
| El iniciador de db-scheduler declara también `DbSchedulerActuatorAutoConfiguration` y `DbSchedulerMetricsAutoConfiguration`; administración y portal las excluyen por nombre | `AdminApplication.java:67-71`, `PortalApplication.java:47-51` | En el trabajador quedan activas; con Actuator en el classpath pueden registrar un indicador propio, inocuo porque el trabajador no expone salud por HTTP |
| Lista blanca pública cerrada, construida en un solo lugar y consumida por las dos cadenas | `PublicEndpoints.java:20-21`, `:63-79`; `AdminSecurityConfiguration.java:48`; `PortalSecurityConfiguration.java:23` | Las dos rutas de salud se añaden en `PublicEndpoints` para ambos procesos |
| La prueba de lista cerrada enumera `RequestMappingInfoHandlerMapping`, compara patrones por igualdad y exige, en producción, que toda ruta registrada esté en una de las cuatro clases | `RegisteredRoutes.java:54-68`; `PublicRouteAllowListTest.java:81-94`, `:385-389` | Las rutas de salud deben registrarse con patrón exacto `/health/live` y `/health/ready`; un `/actuator/health/{*path}` registrado rompe la prueba |
| El guardián de cobertura de rutas del administrativo solo mira `RequestMappingHandlerMapping` y excluye controladores de bibliotecas | `RouteCoverageGuard.java:38`, `:76`, `:148` | Los manejadores de Actuator no lo afectan; un controlador propio de salud sí lo haría (razón adicional contra la alternativa D, decisión 4) |
| [BJ38] hoy se prueba con una expresión que rechaza cualquier artefacto `spring-boot-actuator.*` declarado | `CurrentAbsencesTest.java:56`, `:62-63`, `:102-109` | Se reescribe con el delta de BJ38 (decisión 4.4) |
| Las notas documentales se prueban: cada línea original debe seguir presente y en orden | `DocumentationNotesTest.java:19-33`, `:42-47` | Toda edición de `docs/05`, `docs/07`, `docs/09` y del plan de fundamentos es una nota fechada añadida, nunca una reescritura |
| Roles de prueba con contraseña literal de prueba y propietario de base y esquema | `create-test-roles.sql:11-24` | El script local reproduce la forma, con contraseñas del entorno |
| `.env` ya ignorado y `.env.example` admitido | `.gitignore:23-26` | No hace falta editar `.gitignore` |
| Scripts `.sh` normalizados a LF | `.gitattributes:13-14` | El script de roles no sufre CRLF desde Windows |
| El JDK de CI es Temurin 25 | `.github/workflows/ci.yml:24-27` | La imagen de ejecución es `eclipse-temurin` JRE 25 |
| El proxy de Vite apunta por omisión a `http://localhost:8080` y el origen permitido local es `http://localhost:5173` | `apps/admin-web/vite.config.ts:9`, `application-local.yml:16` | `api-admin` se publica en `127.0.0.1:8080` para que `pnpm dev` funcione contra la pila; `admin-web` no usa el 5173 |
| `docs/05` fija `SERVER_PORT=3000` como puerto interno | `docs/05:757`, `:199-201` | Se conserva 3000 dentro del contenedor |
| `@confia/contracts#generate` lee `apps/api/openapi/*.json` | `turbo.json:10-18` | El contexto de la imagen web debe incluir `apps/api/openapi/` |
| pnpm por `packageManager` y Node 24 por `.nvmrc` | `package.json:5-8`, `.nvmrc:1` | La imagen web usa Node 24 con corepack |

### 2.2 Sondas

Esta fase no ejecutó nada: el ejecutor de diseño no tiene intérprete de órdenes ni la herramienta de
documentación externa (context7) disponibles, de modo que las afirmaciones sobre Spring Boot 4.1.1, las imágenes
base y Compose se toman de la lectura y quedan como **sondas que resuelve la primera tarea de su rebanada**, con
la alternativa ya escrita. Si una sonda difiere, la rebanada se detiene y este diseño recibe una nota fechada
antes de seguir (mismo procedimiento que `background-jobs-with-db-scheduler`, design.md §2.1). Las descargas de
Maven Central en la máquina de desarrollo requieren `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"`
(autorizado por el propietario el 2026-10-24).

| Sonda | Resultado esperado por lectura | Prueba o comando que la resuelve | Si difiere |
|---|---|---|---|
| P-1 Módulos de salud de Spring Boot 4.1.1 | Spring Boot 4 modularizó Actuator: la salud vive en `spring-boot-health` (punto final de salud, grupos, indicadores de estado de vida y preparación) y la exposición web en `spring-boot-actuator-autoconfigure`/`spring-boot-actuator`; el indicador `db` lo aporta `spring-boot-jdbc` (ya presente) cuando la salud está en el classpath. `spring-boot-starter-actuator` arrastra `spring-boot-micrometer-metrics`, cuya autoconfiguración crea un `SimpleMeterRegistry` de respaldo | `./mvnw dependency:tree` sin `spring-boot-micrometer-*` ni `micrometer-registry-*`; `CurrentAbsencesTest` y `WorkerHasNoMeterRegistryTest` en verde | Si la salud exige el módulo de métricas: se excluye el artefacto en el POM y su autoconfiguración por nombre en los tres puntos de entrada, con la misma prueba de ausencia |
| P-2 Forma de las rutas | Con `management.server.port: -1` no se crea el contexto de gestión ni su `WebMvcEndpointHandlerMapping` (sin `/actuator/**`), y los grupos con `additional-path: "server:/health/live"` y `"server:/health/ready"` se sirven en el puerto principal con patrón exacto | `HealthProbesIT` y `PublicRouteAllowListTest`: las rutas de producción son exactamente las previas más `GET /health/live` y `GET /health/ready` | Alternativa A2 de la decisión 4 |
| P-3 Herramientas de la imagen JRE | `eclipse-temurin:25.0.x_y-jre-noble` (Ubuntu) trae `curl` por compatibilidad histórica (adoptium/containers, incidencia 255), `bash`, `sh` y `find`; `wget` no está garantizado | `docker run --rm --entrypoint sh <imagen> -c 'command -v curl wget bash find'` | Sin `curl`: la sonda usa `bash` con `/dev/tcp` (decisión 3.4). Nunca se instala un paquete |
| P-4 Señal de sondeo de db-scheduler 16.12.0 | El `Scheduler` acepta un `SchedulerListener` y emite un evento de planificador al terminar cada ciclo de sondeo de tareas vencidas, también cuando no hay ninguna | `WorkerHeartbeatIT` | Alternativa B de la decisión 6 |
| P-5 nginx sin privilegios con raíz de solo lectura | `nginxinc/nginx-unprivileged:<x.y.z>-alpine` escribe su `pid` y sus temporales en `/tmp`; con `user: 10001`, `read_only` y `tmpfs /tmp` arranca y sirve `/healthz` | `docker compose up admin-web` y CR35 | Excepción documentada (CR36) |
| P-6 Fusión de Compose | `!reset null` elimina un servicio completo del resultado combinado y `!override` reemplaza `depends_on`; Compose de `ubuntu-latest` es ≥ 2.24 | `docker compose -f docker-compose.yml -f docker-compose.prod.yml config --services` sin `postgres` | `postgres` pasa a un perfil `local-db` activado por `COMPOSE_PROFILES` en `.env.example` (decisión 7.5) |
| P-7 Volumen de PostgreSQL 18 | La imagen oficial 18 declara el volumen en `/var/lib/postgresql` y `PGDATA` en un subdirectorio por versión | `docker compose up postgres` con volumen nuevo y reinicio sin pérdida | Montar en la ruta que declare la imagen |
| P-8 Failsafe con el jar reempaquetado | Con `classesDirectory=${project.build.outputDirectory}` (lo que hace `spring-boot-starter-parent`) las `*IT` siguen viendo las clases | `./mvnw verify` completo en verde | Clasificador `exec` para el jar ejecutable y `finalName` en ese artefacto |
| P-9 Extracción en capas | `java -Djarmode=tools -jar confia-api.jar extract --layers --destination <dir>` produce `dependencies/`, `spring-boot-loader/`, `snapshot-dependencies/` y `application/`, y `java -jar` sobre el resultado arranca | Construcción de la imagen y `migrate` en Compose | Copiar el jar entero sin capas (correcto, menos caché) |

## 3. Decisiones de arquitectura

### Decisión 1 — Jar ejecutable en el módulo `app` con nombre fijo

**Elección.**

- `apps/api/pom.xml`, `pluginManagement`: `spring-boot-maven-plugin` con `<version>${spring-boot.version}</version>`
  (el BOM importado no aporta versión de plugins, `apps/api/pom.xml:55-59`), y en la configuración gestionada
  de `maven-failsafe-plugin` `<classesDirectory>${project.build.outputDirectory}</classesDirectory>` junto al
  `argLine` existente.
- `apps/api/app/pom.xml`: `<finalName>confia-api</finalName>` en `<build>` y el plugin con la ejecución
  `repackage` y `<mainClass>com.confia.bootstrap.ConfiaApplication</mainClass>`. El jar resultante es
  `apps/api/app/target/confia-api.jar`; el original queda como `confia-api.jar.original`.
- `kernel` no declara el plugin: sigue siendo una biblioteca (CR02).
- El lanzador no cambia de forma: un `main`, `APP_PROFILE` decide (`ConfiaApplication.java:50-58`).

**Alternativas descartadas.** Plugin en el padre para todos los módulos (haría ejecutable a `kernel`, contra
CR02). Clasificador `exec` conservando el jar plano como artefacto principal (más nombres de archivo y ninguna
ventaja: `apps/api` nunca se publica en un repositorio Maven, `apps/api/pom.xml:448-450`; queda como respaldo de
P-8). `maven-assembly`/`shade` (pierden el lanzador de Spring Boot y la extracción en capas).

**Justificación.** Es el camino mínimo que deja el jar en la misma ejecución de `verify` que ya prueba todo
(BI119, BI120), y `finalName` hace que la ruta no dependa de la versión (`docs/05:197`).

### Decisión 2 — `migrate`: contexto mínimo y cerrado que aplica Flyway y termina

**Elección.**

- Nuevo punto de entrada `com.confia.bootstrap.migrate.MigrateApplication`:
  `@SpringBootConfiguration` con `@ImportAutoConfiguration` de exactamente dos autoconfiguraciones, la de
  `DataSource` y la de Flyway (nombres de paquete de Spring Boot 4.1.1 confirmados en P-1), sin
  `@EnableAutoConfiguration` y sin ningún `@Import` de `com.confia`. Así el contexto no puede crear servidor web,
  cadena de seguridad, `Scheduler` de db-scheduler (que con un `DataSource` presente **empezaría a ejecutar
  tareas**), salud ni `MeterRegistry`: lo que no está en la lista no existe (mismo principio que ADR-0024, en
  sentido de cierre).
- `ConfiaApplication.launch` para `MIGRATE`:
  `new SpringApplicationBuilder(MigrateApplication.class).web(NONE).profiles("migrate")`, con
  `SensitiveLogGuardInitializer` y `SigningKeyPlacementGuard.forNonAdministrativeProcess()` como los demás.
  `run(args)` aplica Flyway durante el refresco (`FlywayMigrationInitializer`); el lanzador cierra el contexto y
  devuelve `LaunchOutcome.exit(0)`. Cualquier excepción de `run` devuelve `exit(1)`; `main` ya llama a
  `System.exit` con ese código (`ConfiaApplication.java:52-54`). Se registra una sola línea con la clase de la
  excepción y el script de Flyway si lo hay; Spring Boot registra además su propio informe de fallo, filtrado
  por la guardia de registros sensibles. Ningún mensaje incluye la URL con credenciales ni la contraseña (CR06).
- `application-migrate.yml` añade `spring.main.keep-alive: false` y `spring.flyway.fail-on-missing-locations:
  true` (un jar mal empaquetado sin `db/migration` falla en lugar de «aplicar nada»).
- Credenciales: el servicio `migrate` recibe `SPRING_DATASOURCE_USERNAME=confia_owner` y su contraseña; ningún
  otro servicio recibe la de `confia_owner`.
- `ProcessBeanPolicy` gana una entrada `MIGRATE` (paquete permitido solo `com.confia.bootstrap.migrate`;
  prohibidos `com.github.kagkarlsson`, `org.springframework.security`, `com.confia.shared.web`), probada por
  una prueba propia porque `migrate` no devuelve contexto vivo. `BootstrapEntryPointRulesTest` acepta el
  subpaquete nuevo.

**Alternativas descartadas.** API de Flyway sin Spring leyendo variables a mano (duplica la convención
`SPRING_DATASOURCE_*` y se salta la guardia de registros sensibles, que se instala en la preparación del
contexto). `@EnableAutoConfiguration` con exclusiones (cierre por lista negra: cada iniciador nuevo, como el de
db-scheduler hoy, entraría en `migrate` por omisión). Migrar al arrancar `admin` (prohibido: `docs/05:755-759`,
CR07, CR08).

**Justificación.** Es el contexto más pequeño que reutiliza la configuración de Spring y garantiza por
construcción que `migrate` no abre puertos ni arranca el planificador (CR03 a CR06).

### Decisión 3 — Imagen de la API: jar externo, JRE Temurin, capas, UID 10001

**3.1 Ubicación y contexto.** `infra/docker/api.Dockerfile` (ruta de `docs/05:159`), construido con contexto la
raíz del repositorio y un archivo de exclusión propio del Dockerfile, `infra/docker/api.Dockerfile.dockerignore`
(BuildKit lee `<Dockerfile>.dockerignore` junto al Dockerfile), en modo lista blanca: `*` y
`!apps/api/app/target/confia-api.jar`. Ningún `.env`, fuente ni `target` ajeno entra al contexto (CR30).

**3.2 Etapas.**

1. `extract`, sobre la misma imagen JRE: `COPY` del jar y
   `java -Djarmode=tools -jar confia-api.jar extract --layers --destination /layers` (P-9).
2. `runtime`: `groupadd --gid 10001 confia` y `useradd --uid 10001 --gid confia --no-create-home
   --shell /usr/sbin/nologin confia`; `WORKDIR /app`; `COPY --from=extract` de las cuatro capas en orden de
   estabilidad (`dependencies`, `spring-boot-loader`, `snapshot-dependencies`, `application`); `USER 10001:10001`;
   `EXPOSE 3000`; `ENTRYPOINT ["java", "-jar", "/app/confia-api.jar"]`.

No hay Maven, `javac` ni fuente en ninguna etapa (CR18, CR19). La suma del jar de la imagen se compara con la del
jar construido en la verificación manual de CR18 (`application/` contiene el jar delgado; la comparación se hace
sobre las entradas de clase, o con `--launcher` desactivado según P-9).

**3.3 Imagen base.** `FROM eclipse-temurin:<25.0.x_y>-jre-noble` con **etiqueta literal exacta en la línea
`FROM`**, sin `ARG` intermedio: Hadolint no evalúa un `FROM ${VAR}`, de modo que una etiqueta flotante detrás de
un argumento escaparía a DL3006/DL3007 (CR28, BI122). La versión exacta es la última 25.0.x publicada el día de
la tarea; el dígest es de 11c.

**3.4 Sonda de salud y herramientas.** La imagen **no declara `HEALTHCHECK`**: una imagen sirve cuatro procesos y
dos no tienen HTTP (`worker`, `migrate`); la comprobación se declara por servicio en Compose. Mecanismo según
P-3, sin instalar nada (CR25):

- Preferido: `["CMD", "curl", "-fsS", "--max-time", "4", "-o", "/dev/null", "http://127.0.0.1:3000/health/ready"]`.
- Si la imagen no trae `curl`: `bash` con `/dev/tcp` en `CMD-SHELL`, comprobando la línea de estado `200`.
- Descartados: `wget` (no garantizado en Temurin), una segunda JVM como sonda (duplica memoria dentro del mismo
  límite de cgroup y falsea la medición), añadir paquetes (exige revisión y amplía la superficie de CVE).

Los servicios de la API usan **`/health/ready`**, no `/health/live`: Docker Engine no reinicia un contenedor por
estar no sano (solo por terminar), así que el único consumidor de la salud en Compose es el orden de arranque y
`docker compose up --wait`, que quieren preparación; CR26 exige además que la caída de PostgreSQL marque no sano.
`/health/live` queda para el nginx de borde y el orquestador de 11d.

**3.5 Raíz de solo lectura y JVM.** Variables de la imagen:

```
SERVER_PORT=3000
JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError -XX:-UsePerfData -Djava.io.tmpdir=/tmp"
```

`MaxRAMPercentage=65` es valor de partida (no el 75 de `docs/05:194`): con 768 MB quedan unos 270 MB fuera del
montón para metaspace, código compilado, hilos y búferes, que en una aplicación Spring Boot con este número de
dependencias suele superar los 190 MB que dejaría el 75 %. La medición de la decisión 9 lo corrige.
`-XX:-UsePerfData` evita `hsperfdata` en `/tmp`. Tomcat usa su directorio base bajo `java.io.tmpdir`; el lanzador
de Spring Boot 3.2+ lee los jars anidados sin extraerlos, y la extracción en capas ya ocurrió en la construcción,
así que nada necesita escribir fuera de `/tmp` ni ejecutar desde él. `noexec` no se retira nunca; si una
biblioteca necesitara extraer código nativo, se le da un directorio `tmpfs` propio (`docs/05:739-740`). Ninguna
variable de secreto en `ENV` ni en argumentos de construcción (CR29).

**Alternativas descartadas.** El Dockerfile de `docs/05:157-216` con `./mvnw package` dentro (imposible:
Testcontainers necesita un demonio, ADR-0021). `docker build` con el socket de Docker montado (otorga control del
anfitrión a la construcción). Imágenes *distroless* (sin `sh` ni `curl`: la sonda exigiría añadir un binario) y
Alpine con musl (distribución distinta de la de CI, contra `docs/05:140-142`). `--launcher` en la extracción
(funciona igual; se prefiere `java -jar` porque es el contrato escrito en `docs/05:215`).

### Decisión 4 — Salud mínima: Actuator solo con salud, rutas exactas, sin métricas

**4.1 Dependencias.** `apps/api/app/pom.xml` declara solo los artefactos de salud que confirme P-1 (esperados:
`spring-boot-health` y el de autoconfiguración de puntos finales de Actuator), **nunca**
`spring-boot-starter-actuator` ni ningún `spring-boot-micrometer-*` ni `micrometer-registry-*`. Versiones del BOM.

**4.2 Configuración (en `application.yml`, común a los tres procesos).**

```yaml
management:
  server:
    port: -1                      # no management context, no /actuator/** mapping (P-2)
  endpoints:
    access:
      default: none               # every endpoint off unless named below
    web:
      exposure:
        include: health
      discovery:
        enabled: false
  endpoint:
    health:
      access: read-only
      show-details: never
      show-components: never
      probes:
        enabled: true
      group:
        liveness:
          include: livenessState
          additional-path: "server:/health/live"
        readiness:
          include: readinessState, db
          additional-path: "server:/health/ready"
  health:
    defaults:
      enabled: false              # no disk space, ping or other default contributor
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
    db:
      enabled: true
spring:
  datasource:
    hikari:
      connection-timeout: 5s      # readiness answers 503 within the probe window when PostgreSQL is down
springdoc:
  show-actuator: false            # explicit: health never enters the OpenAPI snapshots
```

El cuerpo lo produce Actuator: `{"status":"UP"}` o `{"status":"DOWN"}` con `200`/`503`; con `show-details` y
`show-components` en `never` no hay desglose ni para un cliente autenticado (CR09 a CR11). El `503` no pasa por
el traductor de Problem Details: es la excepción aceptada por el propietario (WE101).

**4.3 Indicador `db` del grupo de preparación.** Se declara en `com.confia.shared.observability.health` un único
bean `dbHealthContributor` (desplaza al de la autoconfiguración, que es condicional a su ausencia) que ejecuta
`SELECT 1 FROM flyway_schema_history LIMIT 1` con el `DataSource` del proceso. Motivo: CR07 exige que un proceso
arrancado contra una base sin migrar no indique preparado, y `Connection.isValid()` lo indicaría. La sentencia
sigue siendo «trivial, prueba la conexión y el rol» (`docs/07:775-776`), usa el `SELECT` que ADR-0017 ya concede
a `confia_admin_app` y `confia_portal_app` sobre esa tabla (`docs/07:803-806`), no compara versiones (eso es el
indicador `migrations` del cambio 10) y el grupo sigue teniendo solo `readinessState` y `db` (CR12). Se registra
en la lista aprobada de SQL plano. Lo importan `AdminApplication` y `PortalApplication` (ADR-0024).

**4.4 Ausencia de métricas.** `CurrentAbsencesTest` se reescribe según el delta de BJ38: el conjunto de
artefactos de Actuator declarados debe ser exactamente el de salud de P-1; cualquier
`spring-boot-starter-actuator`, `spring-boot-micrometer-.*`, `micrometer-.*` o `.*prometheus.*` falla. Se
conservan `noProcessHoldsAMeterRegistry` (BJ73) y su fixture, y se añade el fixture de BJ75: un contexto con
`management.endpoints.web.exposure.include=health,info` hace aparecer una ruta de Actuator extra y la prueba la
nombra. BJ74 se cubre con las peticiones a `/actuator/metrics` y `/actuator/prometheus` de `HealthProbesIT`.

**4.5 Forma de las rutas y alternativa escrita.**

- **A1 (elegida):** `management.server.port: -1` + `additional-path`. Rutas registradas exactas
  `GET /health/live` y `GET /health/ready`; `/actuator`, `/actuator/health` y cualquier otra no existen y la
  cadena las deniega con `401` (CR14, WE95, WE98).
- **A2 (si P-2 muestra que `-1` también elimina las rutas adicionales):** puerto de gestión distinto enlazado a
  `127.0.0.1` dentro del contenedor (`management.server.address: 127.0.0.1`), nunca publicado; las rutas
  adicionales siguen en el puerto principal con patrón exacto. Coste: un conector Tomcat más (se mide en la
  decisión 9).
- **D (descartada):** controlador propio sobre `ApplicationAvailability` y el indicador `db`. Haría de la salud
  una ruta de aplicación, lo que contradice el delta de `web-edge` («las rutas de salud no son rutas de
  aplicación» y el portal sin rutas de aplicación) y obligaría a tocar `RouteCoverageGuard`.
- **Descartado también:** exponer `/actuator/health/{*path}` y permitir solo los dos caminos en la cadena (la
  prueba de lista cerrada compara patrones por igualdad y exige que en producción toda ruta registrada esté
  clasificada, `PublicRouteAllowListTest.java:385-389`).

**4.6 Lista blanca.** `PublicEndpoints` gana `HEALTH_LIVE` y `HEALTH_READY` (`GET`), añadidos siempre en
`forAdmin` y en `forPortal`, sin condición de perfil (CR13, WE94, WE98). Se reescribe la frase de
`PublicEndpoints.java:20-21`. `PublicRouteAllowListTest` actualiza `publicRoutesOf` y `productionRoutesOf` de
ambos procesos; los métodos distintos de `GET` siguen en `401` porque la entrada es por método (WE96, WE99). La
instantánea de rutas de aplicación del portal no cambia: las rutas de Actuator no son controladores.

### Decisión 5 — Preparación acotada en tiempo

`spring.datasource.hikari.connection-timeout: 5s` (decisión 4.2). Con el valor por omisión de 30 s, una
petición de preparación con PostgreSQL caído bloquearía 30 s y la sonda de Compose vería un tiempo de espera,
no un `503` (CR10, CR26). Cinco segundos también acortan la espera de una petición de negocio cuando la base no
responde, que es preferible a mantener ocupada una conexión del borde. La comprobación de Compose usa
`timeout: 8s`.

**Alternativa descartada:** no tocar el pool y aceptar tiempos de espera (CR10 no se cumpliría en un tiempo
observable).

### Decisión 6 — Salud del trabajador: latido en archivo refrescado por el sondeo de db-scheduler

**Elección (A).** El `Scheduler` propio del trabajador (`WorkerJobsConfiguration`, dentro de
`com.confia.shared.jobs.infrastructure`, el único paquete que puede nombrar tipos de db-scheduler) registra un
`SchedulerListener` que, en cada evento de fin de ciclo de sondeo de tareas vencidas (P-4), actualiza la fecha
de modificación de un archivo de latido. El sondeo ocurre cada 10 s (`application.yml:78`) y consulta
`scheduled_tasks` en PostgreSQL, así que un latido reciente prueba a la vez que el proceso vive, que el
planificador funciona y que la base responde (CR16). Si la base cae, el sondeo falla, no hay latido y el archivo
envejece.

- Ruta por la propiedad `confia.health.heartbeat-file` (`CONFIA_HEALTH_HEARTBEATFILE`). Sin propiedad no se
  registra el oyente: las pruebas que arrancan el trabajador no escriben archivos. Compose la fija en
  `/tmp/confia-worker.heartbeat` (dentro del `tmpfs`).
- Un fallo de escritura se registra una vez en `WARN` y nunca interrumpe al planificador.
- Comprobación de Compose: `["CMD-SHELL", "find /tmp/confia-worker.heartbeat -mmin -1 | grep -q ."]` con
  `interval: 15s`, `retries: 4`, `start_period: 60s`. Un minuto tolera cinco ciclos de sondeo perdidos.
- Ningún puerto, ninguna ruta, ningún contenido salvo una fecha de modificación (CR17, WE100). Sin hilos propios
  (cumple `NoInMemorySchedulingTest`).
- `ProcessBeanPolicy.WORKER` no cambia de paquetes si el oyente vive en `com.confia.shared.jobs.infrastructure`
  (ya permitido, `ProcessBeanPolicy.java:181`).

**Alternativa B escrita (si P-4 no ofrece un evento por ciclo).** El oyente se engancha a `onExecutionComplete`
de la tarea recurrente existente `shared.queue-observation`, cuya cadencia acota el umbral de `find`; se ajusta
`-mmin` a dos periodos de esa tarea.

**Descartadas.** Puerto de gestión propio en el trabajador (obliga a `WebApplicationType.SERVLET`, un Tomcat y
una superficie de red que hoy no existe, contra el espíritu de WE100 y con coste de memoria). Hilo con
`ScheduledExecutorService` (prohibido por `NoInMemorySchedulingTest`). Una segunda JVM como sonda (memoria dentro
del mismo límite). Solo «reinicio por terminación» (no cumple CR16: el trabajador con la base caída seguiría
«sano»). Marca de archivo estática al arrancar (no refleja la base).

### Decisión 7 — Compose: ubicación, servicios, roles locales, overlay y `.env.example`

**7.1 Ubicación.** `docker-compose.yml` y `docker-compose.prod.yml` en la **raíz** (así `docker compose up`
funciona sin `-f`, como piden la propuesta y `docs/05` §4 y §5); Dockerfiles y archivos auxiliares en
`infra/docker/` (`docs/05:159`, `:245`, `:288`; ADR-0012, contexto técnico). La contradicción con
`docs/07:816` (`infra/docker/compose.prod.yml`) se resuelve a favor de la raíz y se anota en `docs/07` §9.1.

**7.2 Servicios del archivo base** (exactamente los de CR37):

| Servicio | Imagen | Entorno (solo lo suyo) | Puertos | Salud | Depende de |
|---|---|---|---|---|---|
| `postgres` | `postgres:18-alpine` (la misma cadena que `confia.postgres.image`, `apps/api/pom.xml:52`) | `POSTGRES_USER=postgres`, `POSTGRES_PASSWORD`, `POSTGRES_DB=confia`, las tres contraseñas de rol | `127.0.0.1:5432:5432` | `pg_isready -h 127.0.0.1 -U postgres -d confia` | — |
| `migrate` | build `infra/docker/api.Dockerfile` | `APP_PROFILE=migrate`, URL, `confia_owner` y su contraseña | — | — (una ejecución), `restart: "no"` | `postgres: service_healthy` |
| `api-admin` | misma imagen | `APP_PROFILE=admin`, `SPRING_PROFILES_ACTIVE=local`, URL, `confia_admin_app`, clave maestra, pimienta, institución de acceso, clave CSRF, anillo de firma | `127.0.0.1:8080:3000` | `/health/ready` (3.4) | `migrate: service_completed_successfully`, `postgres: service_healthy` |
| `api-portal` | misma imagen | `APP_PROFILE=portal`, `SPRING_PROFILES_ACTIVE=local`, URL, `confia_portal_app` | `127.0.0.1:8081:3000` | `/health/ready` | ídem |
| `worker` | misma imagen | `APP_PROFILE=worker`, URL, `confia_admin_app` (`WorkerApplication.java:22-25`), `CONFIA_JOBS_INSTITUTIONS`, `CONFIA_HEALTH_HEARTBEATFILE` | ninguno | latido (decisión 6) | ídem |
| `admin-web` | build `infra/docker/web.Dockerfile`, `APP_NAME=admin-web` | ninguno | `127.0.0.1:8090:8080` | `wget -q -O /dev/null http://127.0.0.1:8080/healthz` | — |

- `pg_isready` con `-h 127.0.0.1`: durante la ejecución de los scripts de inicialización el servidor temporal
  solo escucha por socket local; sin `-h`, la sonda daría sano antes de que existan los roles y `migrate`
  fallaría (CR43).
- Redes `data` (`postgres`, `migrate`, `api-*`, `worker`) y `edge` (`api-*`, `admin-web`).
- Cada variable sensible se interpola con `${VAR:?mensaje}`: `docker compose config` o `up` fallan nombrando la
  variable ausente (CR47). **No se usa `env_file`.**
- `admin-web` no depende de `api-admin` ni hace de proxy de `/api`: la imagen web es la de producción, donde el
  nginx de borde (11d) enruta `/api`. Para usar la interfaz contra la pila se ejecuta `pnpm dev`, cuyo proxy ya
  apunta a `127.0.0.1:8080` y cuyo origen (`5173`) es el permitido por el perfil `local`. Los recorridos reales
  son de 11f.
- `x-jvm-hardening` y `x-web-hardening` se definen **en el archivo base** y se aplican a `migrate`, `api-*`,
  `worker` y `admin-web`: `user: "10001:10001"`, `read_only: true`, `tmpfs: ["/tmp:noexec,nosuid,size=64m,mode=1777"]`,
  `security_opt: ["no-new-privileges:true"]`, `cap_drop: [ALL]`. Así el arranque local valida de verdad la raíz
  de solo lectura y `noexec` (CR21, CR22, CR35) y la medición se hace en las condiciones de producción;
  `postgres` es la única excepción (la imagen oficial necesita `root` para inicializar y es solo local).
- **Límites de memoria en el archivo base** (`deploy.resources.limits.memory`: `api-admin` 768m, `api-portal`
  768m, `worker` 1g, `migrate` 512m, `admin-web` 128m; valores de partida de `docs/05:599-665`), heredados por el
  overlay. Una sola fuente para la pila que se mide (CR50) y para la combinada que se valida (CR48); el overlay
  añade solo `cpus`, reservas y rotación de registros.

**7.3 Script de roles local.** `infra/docker/postgres/initdb/10-create-local-roles.sh`, montado de solo lectura
en `/docker-entrypoint-initdb.d`. Usa `psql -v ON_ERROR_STOP=1` con variables de `psql` y su interpolación
entrecomillada (`PASSWORD :'owner_password'`), nunca concatenación de SQL (regla 12): crea `confia_owner`,
`confia_admin_app` y `confia_portal_app` con `LOGIN` y la contraseña de su variable; `confia_readonly` y
`confia_backup` como `NOLOGIN` sin contraseña (ningún servicio local los usa: minimización); concede
`pg_read_all_data` a `confia_backup`; y transfiere la base `confia` y el esquema `public` a `confia_owner`, como
`create-test-roles.sql:17-24`. Si falta una contraseña, termina con error. Corre solo con un volumen nuevo
(comportamiento de la imagen oficial; documentado). No lo copia ninguna imagen ni lo referencia el overlay (CR44).

**7.4 `.env.example`.** Solo marcadores reconocibles (`change-me-...`) para contraseñas, y para cada valor que no
puede ser un marcador (CR45, decisión sobre las claves de firma):

- Clave maestra, pimienta y clave CSRF: comentario con `openssl rand -base64 32`.
- Anillo de firma administrativo Ed25519 (PKCS#8 y X.509 en Base64 estándar, como espera el cargador): comentario
  con `openssl genpkey -algorithm ed25519 -outform DER -out admin-signing.der`, `base64 -w0 admin-signing.der` y
  `openssl pkey -inform DER -in admin-signing.der -pubout -outform DER | base64 -w0`, y la instrucción de borrar
  el `.der`. Los nombres en `.env` son legibles (`CONFIA_ADMIN_SIGNING_PRIVATE_KEY`, ...) y Compose los traslada
  **solo a `api-admin`** con su nombre de Spring (`CONFIA_SECURITY_ADMINSIGNING_CURRENT_PRIVATEKEY`, ...), de modo
  que el portal y el trabajador nunca los reciben.
- Identificador de institución de acceso e instituciones del trabajador: comentario con `uuidgen`.
- Ninguna clave real, ni siquiera de ejemplo, ni el literal de `docs/05:360`.

No se añade un script generador: amplía la superficie del cambio y el arranque falla cerrado (el administrativo
se niega a arrancar con una clave inválida) si alguien copia los marcadores sin generar.

**7.5 Overlay de producción** (`docker-compose.prod.yml`, solo validado en 11a):

- `postgres: !reset null` (P-6) y `depends_on: !override` en `api-*` y `worker` (solo `migrate`
  `service_completed_successfully`) y en `migrate` (vacío). Ningún servicio depende de `postgres` (CR49).
- `image: ${CONFIA_API_IMAGE:?}` y `${CONFIA_ADMIN_WEB_IMAGE:?}` con `build: !reset null`: la referencia exacta la
  aporta el flujo de 11c/11d.
- `ports: !reset []` en todos los servicios; `restart: always`; `cpus`, reservas y `logging` de `docs/05` §5.
- Sin `SPRING_PROFILES_ACTIVE=local`: el overlay lo sustituye por `prod`.
- Hereda endurecimiento y límites del base.
- Alternativa si P-6 falla: `postgres` en el perfil de Compose `local-db` y `COMPOSE_PROFILES=local-db` en
  `.env.example`, con `depends_on.postgres.required: false`.

### Decisión 8 — Imagen web: Vite en Node 24 y nginx sin privilegios con raíz de solo lectura

- `infra/docker/web.Dockerfile` con `ARG APP_NAME=admin-web` y archivo de exclusión propio
  `infra/docker/web.Dockerfile.dockerignore` que excluye `.git`, `**/node_modules`, `**/dist`, `**/target`, `.env`
  y `.env.*`, y conserva `apps/api/openapi/` (lo necesita `@confia/contracts#generate`).
- Etapa `build`: `FROM node:<24.x.y>-bookworm-slim` (literal); `corepack enable` (pnpm de `packageManager`);
  `RUN test -d "apps/${APP_NAME}" || { echo "unknown application: ${APP_NAME}" >&2; exit 1; }` (CR33);
  `pnpm install --frozen-lockfile` con montaje de caché de BuildKit; `pnpm turbo run build
  --filter=@confia/${APP_NAME}`. Ni JDK ni demonio (CR34).
- Etapa `runtime`: `FROM nginxinc/nginx-unprivileged:<x.y.z>-alpine` (literal, rama estable); copia de
  `infra/docker/web.nginx.conf` a `/etc/nginx/conf.d/default.conf` y de `apps/${APP_NAME}/dist` a
  `/usr/share/nginx/html`; `USER 10001:10001`; `EXPOSE 8080`. Sin fuente, `node_modules` ni `node` (CR32).
- `web.nginx.conf`: `listen 8080`, `server_tokens off`, `location = /healthz { return 200 "ok\n"; }`, retorno a
  `index.html` para la aplicación de una sola página y las reglas de caché de `docs/05:313-326`. La CSP con
  `nonce` y las cabeceras completas son del nginx de borde (11d).
- **`read_only` se activa** con `tmpfs /tmp` (pid y temporales de `nginx-unprivileged` ya viven en `/tmp`),
  validado por P-5 y CR35; resuelve la excepción pendiente de `docs/05:729-731`. Si P-5 falla, se aplica CR36
  con nota fechada.
- `apps/portal-web` reutiliza el mismo archivo con `APP_NAME=portal-web` cuando exista.

**Descartadas:** `nginx:alpine` con usuario creado y `chown` de rutas (`docs/05:282-286`; más líneas y escrituras
en `/var/cache/nginx` incompatibles con la raíz de solo lectura). Proxy local de `/api` en la imagen (divergiría
de producción). `ARG` para la imagen base (Hadolint no la evaluaría).

### Decisión 9 — Medición de memoria

- **Condiciones:** pila base con los límites y el endurecimiento de la decisión 7.2, `SPRING_PROFILES_ACTIVE=local`,
  máquina de desarrollo con Docker Desktop (WSL2), imagen y commit anotados.
- **Procedimiento:** (1) `docker compose up --wait`; (2) muestreo de `docker stats --no-stream --format json` cada
  5 s durante 10 minutos, que cubre el arranque, el reposo y los ciclos del trabajador (sondeo cada 10 s y tareas
  recurrentes); (3) carga ligera: 300 peticiones a `/health/ready` y a la ruta de inicio de sesión del
  administrativo con credenciales inválidas, que ejercitan cadena, limitador y acceso a la base; (4) pico por
  contenedor con `cat /sys/fs/cgroup/memory.peak` cuando el núcleo lo ofrezca, si no el máximo del muestreo;
  (5) montón efectivo con `java -XX:+PrintFlagsFinal -version` dentro del contenedor (CR23) y con dos límites
  distintos (CR24); (6) `docker inspect` de `OOMKilled` y del código de salida de cada contenedor (CR52).
- **Regla:** se conserva un límite si el pico medido no supera el 85 % de él; si lo supera, se sube el límite o se
  ajusta `MaxRAMPercentage` y se repite. Si la suma de los límites no cabe en la instancia de fase uno
  (`docs/05` §2), se registra y se escala según la tabla de riesgos de ADR-0013 (línea 242) **antes de 11d**; esa
  escalada sí sería una decisión de ADR (sección 11).
- **Registro:** nota fechada en `docs/09` (entregable 1) con tabla por proceso (pico, reposo, límite, porcentaje,
  `JAVA_TOOL_OPTIONS`, fecha, commit, condiciones) y nota fechada en `docs/05` §5 con los límites vigentes
  (CR50, CR51). Los valores ajustados se escriben en el archivo base (una sola fuente).

### Decisión 10 — Integración continua: Hadolint, Compose y política; sin construir imágenes

Nuevo trabajo `containers` (`container files`) en `.github/workflows/ci.yml`, independiente de `backend`, sin
JDK ni Node, con `timeout-minutes: 5`:

1. **Hadolint** con la imagen `hadolint/hadolint:<vX.Y.Z>` por etiqueta exacta (o la acción oficial fijada por
   SHA, como el resto de acciones del flujo) sobre `infra/docker/*.Dockerfile`, con
   `--failure-threshold warning`. **Umbral `warning`** porque las reglas de etiqueta exacta (DL3006 sin etiqueta,
   DL3007 `latest`) son de severidad `warning`: con `error` CR28 y BI122 no romperían. El paso falla si la lista
   de archivos está vacía (BI123).
2. **Supresiones con motivo** (BI124): el script de política rechaza toda línea `# hadolint ignore=...` que no
   tenga, en la línea anterior, un comentario `# reason: ...`. No hay `.hadolint.yaml` con reglas ignoradas.
3. **`docker compose config --quiet`** sobre el base y sobre base más overlay, con `--env-file .env.example` y
   `CONFIA_API_IMAGE`/`CONFIA_ADMIN_WEB_IMAGE` ficticios definidos en el paso (BI125 a BI127).
4. **`infra/ci/check-container-files.sh`**, ejecutable también en local, sobre la salida JSON de
   `docker compose config` y los archivos: servicios exactos del base (CR37), puertos solo en `127.0.0.1` (CR38),
   ausencia de `postgres` y de dependencias hacia él en el combinado (CR49), endurecimiento y límites en el
   combinado (CR48), imágenes con etiqueta exacta y sin `latest` (CR27), ningún `@sha256:` todavía (CR53), y
   coherencia de versiones: imagen de PostgreSQL de Compose igual a `confia.postgres.image`, mayor de Node de la
   imagen web igual a `.nvmrc`, mayor de la JRE igual a `JAVA_VERSION` del flujo.

**No se construyen imágenes en CI en este cambio.** La construcción encadenada al jar del trabajo `backend`
(artefacto, construcción, Trivy, SBOM, firma y publicación por dígest) se diseña una sola vez en 11c; un trabajo
de construcción provisional ahora se reescribiría entero. Los Dockerfiles se validan aquí por Hadolint y por el
arranque local (sección 8). Tampoco se levanta la pila en CI: es de 11f.

### Decisión 11 — Decisiones que suben a ADR

Ninguna decisión de este diseño exige un ADR nuevo: construir el jar fuera de `docker build` es consecuencia de
ADR-0021 y no contradice ADR-0012 (una imagen, construida una vez, promovida por dígest); la salud mínima y el
latido del trabajador son mecanismos dentro de `docs/07` §9; la ubicación de archivos ya la prevé ADR-0012. Queda
**una decisión de ADR condicional**: si la medición de la decisión 9 muestra que la topología de fase uno no
alcanza, la escalada (compilación nativa con GraalVM o instancia mayor, ADR-0013 línea 242) es un ADR que decide
el propietario antes de 11d.

## 4. Flujo de arranque de la pila local

```
docker compose up
  │
  ├─ postgres ── initdb (solo volumen nuevo) ── 10-create-local-roles.sh (roles, dueño de base y esquema)
  │               └─ pg_isready -h 127.0.0.1 ── healthy
  │
  ├─ migrate (espera postgres healthy)
  │     java -jar … APP_PROFILE=migrate ── MigrateApplication ── Flyway como confia_owner
  │     ├─ éxito ─► exit 0 ─► service_completed_successfully
  │     └─ fallo ─► exit 1 ─► api-admin, api-portal y worker no se crean (CR41)
  │
  ├─ api-admin / api-portal (esperan migrate OK y postgres healthy)
  │     arranque ─► ReadinessState.ACCEPTING_TRAFFIC
  │     curl /health/ready ─► readinessState + db(SELECT 1 FROM flyway_schema_history) ─► 200 ─► healthy
  │
  ├─ worker (ídem)
  │     Scheduler ─► sondeo cada 10 s contra scheduled_tasks ─► SchedulerListener ─► touch heartbeat
  │     find heartbeat -mmin -1 ─► healthy
  │
  └─ admin-web (independiente) ─► wget /healthz ─► healthy
```

## 5. Interfaces y contratos

| Contrato | Valor |
|---|---|
| Variable de proceso | `APP_PROFILE` ∈ {`admin`, `portal`, `worker`, `migrate`}; otra → código 1 (sin cambio) |
| Códigos de `migrate` | `0` aplicado o nada pendiente; `1` cualquier fallo |
| Puerto interno de la API | `SERVER_PORT=3000` |
| `GET /health/live` | `200 {"status":"UP"}`; `503 {"status":"DOWN"}` solo si la vida está rota |
| `GET /health/ready` | `200 {"status":"UP"}` o `503 {"status":"DOWN"}`; nunca Problem Details (WE101) |
| Otros métodos u otras rutas `/actuator/**`, `/health/**` | `401` Problem Details `authentication-required` |
| Latido del trabajador | archivo `CONFIA_HEALTH_HEARTBEATFILE`, sin contenido significativo; sano si su fecha tiene menos de 60 s |
| Imagen web | `GET /healthz` → `200 ok`; argumento de construcción `APP_NAME` |

```java
// com.confia.bootstrap.ConfiaApplication#launch, new branch (sketch)
case MIGRATE -> migrate(args);   // returns LaunchOutcome.exit(0) or LaunchOutcome.exit(1), never a running context
```

## 6. Cambios de archivos

| Archivo | Acción | Descripción |
|---|---|---|
| `apps/api/pom.xml` | Modificar | `spring-boot-maven-plugin` en `pluginManagement` con versión; `classesDirectory` de Failsafe |
| `apps/api/app/pom.xml` | Modificar | `finalName`, plugin `repackage` con `mainClass`, artefactos de salud (P-1) |
| `apps/api/app/src/main/java/com/confia/bootstrap/ConfiaApplication.java` | Modificar | Rama `MIGRATE` real; Javadoc |
| `apps/api/app/src/main/java/com/confia/bootstrap/AppProfile.java` | Modificar | Javadoc de `MIGRATE` |
| `apps/api/app/src/main/java/com/confia/bootstrap/migrate/MigrateApplication.java` | Crear | Contexto cerrado de Flyway |
| `apps/api/app/src/main/java/com/confia/bootstrap/admin/AdminApplication.java`, `portal/PortalApplication.java` | Modificar | `@Import` de la configuración del indicador `db` |
| `apps/api/app/src/main/java/com/confia/shared/observability/health/` (`ReadinessDatabaseHealthConfiguration.java`, `package-info.java`) | Crear | Indicador `db` con la sentencia de esquema |
| `apps/api/app/src/main/java/com/confia/shared/jobs/infrastructure/` (oyente de latido y su registro en `WorkerJobsConfiguration`) | Crear/Modificar | Latido del trabajador |
| `apps/api/app/src/main/java/com/confia/shared/web/edge/PublicEndpoints.java` | Modificar | Dos entradas de salud en ambos procesos; Javadoc |
| `apps/api/app/src/main/java/com/confia/shared/observability/queue/package-info.java` | Modificar | La frase «no hay Actuator» pasa a «Actuator solo de salud, sin registro de Micrometer» |
| `apps/api/app/src/main/resources/application.yml` | Modificar | `management.*`, `hikari.connection-timeout`, `springdoc.show-actuator` |
| `apps/api/app/src/main/resources/application-migrate.yml` | Modificar | `keep-alive: false`, `fail-on-missing-locations: true`; comentario |
| `apps/api/app/src/test/java/com/confia/bootstrap/` (`CurrentAbsencesTest`, `PublicRouteAllowListTest`, `ProcessBeanPolicy`, nuevas `MigrateProfileIT`, `MigrateContextIsolationTest`, `ExecutableJarIT`, `HealthProbesIT`) | Modificar/Crear | Sección 8 |
| `apps/api/app/src/test/java/com/confia/shared/jobs/infrastructure/` (`WorkerHeartbeatListenerTest`, `WorkerHeartbeatIT`) | Crear | Latido |
| `apps/api/app/src/test/java/com/confia/architecture/BootstrapEntryPointRulesTest.java` | Modificar | Subpaquete `migrate` |
| `apps/api/app/src/test/resources/db/broken/V999__fails.sql` | Crear | Migración que falla para CR06 (fuera de `db/migration`) |
| `infra/docker/api.Dockerfile`, `infra/docker/api.Dockerfile.dockerignore` | Crear | Decisión 3 |
| `infra/docker/web.Dockerfile`, `infra/docker/web.Dockerfile.dockerignore`, `infra/docker/web.nginx.conf` | Crear | Decisión 8 |
| `infra/docker/postgres/initdb/10-create-local-roles.sh` | Crear | Decisión 7.3 |
| `docker-compose.yml`, `docker-compose.prod.yml` | Crear | Decisión 7 |
| `.env.example` | Crear | Decisión 7.4 |
| `infra/ci/check-container-files.sh` | Crear | Decisión 10 |
| `.github/workflows/ci.yml` | Modificar | Trabajo `containers`; comentario de cabecera |
| `apps/api/README.md` | Modificar | Cómo construir el jar y levantar la pila |
| `docs/05-infraestructura-y-despliegue.md` | Notas fechadas | §3.1 regla 2 y §3.2 (jar fuera, capas, sin `HEALTHCHECK` de imagen, sonda validada, `MaxRAMPercentage`), §3.3 (`nginx-unprivileged`, `APP_NAME`, `read_only` activado), §4 (servicios de 11a, entorno por servicio sin `env_file`, puertos), §5 (endurecimiento y límites en el base, `!reset`, límites medidos) |
| `docs/07-observabilidad-y-operaciones.md` | Notas fechadas | §9 (subconjunto entregado, `UP`/`DOWN` en lugar de `ok`, indicador `db` con la sentencia de esquema, Compose comprueba preparación, Docker no reinicia por no sano), §9.1 (ruta del overlay en la raíz), línea 513 (Actuator ya existe sin métricas) |
| `docs/09-roadmap-y-fases.md` | Nota fechada | Medición del entregable 1 |
| `openspec/changes/foundations-plan/exploration.md` | Nota fechada | Estado de 11a |

`.gitignore` y `.gitattributes` no cambian (`.gitignore:23-26`, `.gitattributes:13-14`).

## 7. Desviaciones respecto de la propuesta y de la documentación, y precisiones para la especificación

| Tema | Propuesta o documento | Diseño | Motivo |
|---|---|---|---|
| Endurecimiento | En el overlay (punto 11) | Definido en el base y heredado | Validar `read_only` y `noexec` en 11a y medir en condiciones reales; el combinado sigue cumpliendo CR48 |
| Límites de memoria | Overlay | Base, heredados | Una fuente para la medición (CR50) y la validación (CR48) |
| Sonda de Compose de la API | `/health/live` (`docs/05:435`, `docs/07:822`) | `/health/ready` | Docker no reinicia por no sano; CR26 |
| Cuerpo | `{"status":"ok"}` (`docs/07:743`) | `{"status":"UP"\|"DOWN"}` | Forma nativa de Actuator, ya fijada por CR09 y CR10 |
| `MaxRAMPercentage` | 75 (`docs/05:194`) | 65 de partida | Margen fuera del montón; lo corrige la medición |
| `HEALTHCHECK` en la imagen | `docs/05:208-209` | Ninguno; por servicio | Una imagen, dos procesos sin HTTP |
| Indicador `db` | `isValid` implícito | `SELECT 1 FROM flyway_schema_history LIMIT 1` | CR07 frente a CR12: la única forma de cumplir ambos con un solo indicador |
| Ubicación del overlay | `infra/docker/compose.prod.yml` (`docs/07:816`) | Raíz | Coherencia con `docs/05` §4 y §5 |
| `.gitignore` | Añadir `.env` | Ya presente | `.gitignore:23-26` |

Supuestos abiertos de la especificación, resueltos aquí: mecanismo de salud del trabajador (decisión 6, sin
superficie de red, por lo que WE100 se cumple trivialmente); ubicación de los límites (decisión 7.2); claves de
firma en `.env.example` (decisión 7.4); umbral de Hadolint (decisión 10). CR07 se cumple para el trabajador
porque su sondeo falla sin `scheduled_tasks`.

## 8. Estrategia de pruebas

| Capa | Qué | Cómo | Escenarios | Automatizada en CI |
|---|---|---|---|---|
| Unidad | Lanzador: `migrate` devuelve 0/1 y nunca contexto vivo | Prueba del lanzador con base inalcanzable | CR05 | Sí (`backend`) |
| Integración | Flyway real como `confia_owner` sobre base vacía, repetición, migración rota, suma divergente; ningún secreto en la salida capturada | `MigrateProfileIT` sobre el PostgreSQL de Testcontainers con los roles de prueba | CR03 a CR06 | Sí |
| Integración | Contexto `migrate` sin web, sin `Scheduler`, sin cadena, sin `MeterRegistry` | `MigrateContextIsolationTest` | Decisión 2 | Sí |
| Integración | `admin`, `portal`, `worker` contra base vacía y contra base a falta de una migración: no migran; preparación `503` | Extensión de `MigrateProfileIT` | CR07, CR08 | Sí |
| Integración (paquete) | `java -jar target/confia-api.jar` como subproceso con argumentos fijos: perfil inválido → 1; `migrate` contra Testcontainers → 0; entradas del jar sin clases de prueba ni `db/testing`, `db/codegen`; `kernel` sin `Start-Class` | `ExecutableJarIT` (Failsafe, después de `package`) | CR01, CR02, BI119 | Sí |
| Integración | Vida y preparación `200 {"status":"UP"}`; PostgreSQL detenido → vida `200`, preparación `503 {"status":"DOWN"}` en menos de 8 s; cuerpo idéntico con token administrativo; sin `components`, host ni puerto; `/actuator/**` y `/health/other` → `401`; métodos no `GET` → `401`; `/actuator/metrics` y `/prometheus` sin contenido | `HealthProbesIT` (admin y portal, perfil por omisión y `local`) | CR09 a CR15, WE94 a WE99, WE101, BJ74 | Sí |
| Arquitectura | Lista cerrada con exactamente las dos rutas nuevas; rutas de producción exactas | `PublicRouteAllowListTest` actualizado | WE94, WE97, CR15 | Sí |
| Arquitectura | Artefactos de Actuator solo de salud; sin `MeterRegistry` en los tres procesos; fixtures permanentes que fallan | `CurrentAbsencesTest`, `WorkerHasNoMeterRegistryTest` | BJ38, BJ73, BJ75 | Sí |
| Unidad | Oyente de latido: el evento de ciclo toca el archivo, los demás no; un error de E/S no se propaga | `WorkerHeartbeatListenerTest` | CR16 | Sí |
| Integración | Trabajador con ejecutor activo y archivo temporal: la fecha avanza; con la base detenida deja de avanzar | `WorkerHeartbeatIT` | CR16, CR17 | Sí |
| Estático | Hadolint, supresiones con motivo | Trabajo `containers` | CR27, CR28, BI122 a BI124 | Sí |
| Estático | `docker compose config` y política sobre el JSON resuelto | Trabajo `containers` + `check-container-files.sh` | CR37 a CR39, CR47 a CR49, CR53, BI125 a BI127 | Sí |
| Sistema (manual, con evidencia en `apply-progress`) | `docker compose up --wait`; orden de arranque; `migrate` roto; `postgres` no sano; usuario 10001; `noexec`; los cuatro perfiles con raíz de solo lectura; ausencia de secretos en `docker history`/`inspect`; `.env` fuera del contexto; imagen web (`/healthz`, `/`, usuario, puerto, sin `node`); `APP_NAME` inexistente; `PrintFlagsFinal` con dos límites; roles con su contraseña; `java -version` frente a CI | Guion en `apps/api/README.md` | CR18 a CR26, CR29 a CR36, CR40 a CR46, CR52 | No (11c construye, 11f levanta la pila en CI) |
| Medición (manual) | Decisión 9 | Notas en `docs/09` y `docs/05` | CR50, CR51 | No |
| Documental | Notas fechadas sin reescritura | `DocumentationNotesTest` existente | — | Sí |

Mapa con los criterios de éxito de la propuesta: `verify` en verde (todas las filas automatizadas); `migrate`
(CR03 a CR08); salud (CR09 a CR13); lista cerrada y ausencia de `MeterRegistry` (WE94 a WE99, BJ73 a BJ75);
`compose up` sano (CR40, manual); puertas de CI (BI122 a BI127); ningún secreto (CR29, CR30, CR45);
memoria (CR50 a CR52).

## 9. Matriz de amenazas

| Frontera | Casos adversariales mínimos | Aplicabilidad | Respuesta |
|---|---|---|---|
| Rutas tipo documentación | `README.sh`, Markdown ejecutable | N/A: el cambio no clasifica ni ejecuta archivos por su nombre | — |
| Selección de repositorio Git | `git -C`, rutas relativas | N/A: ningún paso invoca Git | — |
| Estado de commit | índice, `commit -a` | N/A | — |
| Estado de empuje | rama de seguimiento, refspec | N/A | — |
| Órdenes de PR | `--head`, prefijos | N/A | — |

Fronteras de proceso propias del cambio, cubiertas por las pruebas de la sección 8 sin filas nuevas en la matriz:
el subproceso de `ExecutableJarIT` usa un vector de argumentos fijo, sin intérprete de órdenes; las sondas de
Compose de la API usan forma `exec` (sin `sh`), y la del trabajador una orden fija sin variables; el script de
roles interpola contraseñas solo como variables de `psql` entrecomilladas; ninguna construcción recibe secretos
por argumento.

## 10. Plan de entrega y secuencia (≤ 15 tareas)

Cuatro PR encadenados; cada uno se revierte solo, en orden inverso (revertir la salud exige revertir antes las
comprobaciones de Compose que la consumen).

| PR | Tareas (estimación) | Contenido |
|---|---|---|
| A `jar-and-migrate` | 1. Sondas P-1 y P-8; plugin, `finalName`, Failsafe, `ExecutableJarIT` en rojo y verde. 2. `MigrateApplication` y rama del lanzador con `MigrateProfileIT` y `MigrateContextIsolationTest`. 3. CR07/CR08 y `ProcessBeanPolicy`/reglas de entrada | Unas 450 líneas con pruebas |
| B `minimal-health` | 4. P-1/P-2: dependencias y configuración de salud; `CurrentAbsencesTest` reescrito. 5. `HealthProbesIT`, indicador `db`, `connection-timeout`. 6. `PublicEndpoints` y `PublicRouteAllowListTest`. 7. Latido del trabajador (P-4) | Unas 600 líneas |
| C `images-and-compose` | 8. P-3, P-9: `api.Dockerfile` y exclusiones. 9. P-5: `web.Dockerfile`, `web.nginx.conf`. 10. P-7: `docker-compose.yml`, script de roles, `.env.example`. 11. P-6: overlay y `check-container-files.sh`. 12. Trabajo `containers` en CI. 13. Verificación de sistema manual (sección 8) | Unas 550 líneas |
| D `memory-and-docs` | 14. Medición y ajuste de límites. 15. Notas documentales y `README` | Unas 250 líneas |

Total: **15 tareas**, en el límite de la regla. Si el plan de tareas lo supera, el corte de la propuesta sigue
vigente: el PR D y la tarea 11 pasan a un cambio 11a-2.

## 11. Riesgos

| Riesgo | Mitigación |
|---|---|
| P-1: la salud de Spring Boot 4.1.1 arrastra métricas | Exclusión explícita y prueba de ausencia (decisión 4.4) |
| P-2: `management.server.port: -1` elimina también las rutas adicionales | Alternativa A2 escrita |
| P-4: db-scheduler 16.12.0 no emite un evento por ciclo | Alternativa B escrita (decisión 6) |
| La sentencia del indicador `db` se lee como el indicador `migrations` del cambio 10 | Documentado en la decisión 4.3 y en la nota de `docs/07`; el cambio 10 puede sustituirla al añadir `migrations` |
| `connection-timeout` de 5 s cambia el comportamiento del pool con carga alta | Se observa en la medición; el valor es configurable por entorno |
| Memoria por encima de los límites | Decisión 9; ADR condicional antes de 11d |
| Docker Desktop en Windows sin `memory.peak` | Muestreo de `docker stats` como respaldo |
| Sin construcción de imágenes en CI hasta 11c, un Dockerfile podría romperse sin aviso | Hadolint en CI y verificación manual registrada; 11c añade la construcción |
| Descargas de Maven Central bloqueadas por PKIX en la máquina local | `MAVEN_OPTS` autorizado el 2026-10-24 |

## 12. Preguntas abiertas

- [ ] Ninguna bloquea este diseño. Sigue abierta, fuera de su alcance, la asignación de la semilla de datos
      ficticios (pregunta 1 de la propuesta).

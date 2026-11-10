# Tareas: imágenes de contenedor y Compose local (cambio 11a de F0)

- **Cambio:** `dockerfiles-and-local-compose` (F0, cambio 11a; entregable 8 parcial y medición de memoria del entregable 1)
- **Fase:** tareas
- **Fecha:** 2026-11-09
- **Estado:** aprobado por el propietario el 2026-11-09.
- **Entradas aprobadas:** `proposal.md`, `design.md` (decisiones 1 a 11, sondas P-1 a P-9, secciones 6 a 10) y los deltas
  `specs/container-runtime` (CR01 a CR54), `specs/build-integrity` (BI119 a BI127), `specs/web-edge` (WE94 a WE101 y los escenarios
  vigentes sin identificador que el delta conserva) y `specs/background-jobs` (BJ38, BJ73 a BJ75).
- **Modo de TDD:** estricto (`openspec/config.yaml`). Ejecutor: `./mvnw` en `apps/api` con JDK 25 y Docker en ejecución. Las tareas de
  Dockerfile, Compose y CI no tienen ejecutor determinista de rojo: llevan la excepción declarada y comprobaciones estructurales y
  funcionales (Hadolint, `docker compose config`, política en `infra/ci/check-container-files.sh`, arranque real local).
- **Línea base de la cadena:** `main` actualizado al iniciar el PR A.

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Tareas | **15** (en el límite exacto de la regla de `openspec/changes/README.md`) |
| Estimated changed lines (additions + deletions, sin `openspec/` ni código generado) | **unas 1 850** en total: A 430 a 450, B 600, C 550 a 650, D 250 |
| 400-line budget risk | **Medio por PR** frente al presupuesto de 800 de `docs/15` §3; cada **tarea** (unidad de commit) queda en 60 a 220 líneas, bajo la heurística de unas 400 |
| Chained PRs recommended | Yes |
| Suggested split | **4 PR encadenados** del diseño §10: A `jar-and-migrate`, B `minimal-health`, C `images-and-compose`, D `memory-and-docs` |
| Chain strategy | `stacked-to-main`: cada PR se fusiona a `main` en orden y el siguiente parte de `main` actualizado |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: Medium

**Corte 11a-2 (no se aplica).** La cuenta cierra en 15, así que el cambio no se parte. Si durante la aplicación una tarea debe dividirse y
el total pasa de 15, se ejecuta el corte de la propuesta: las tareas **T14** (medición) y la parte de overlay de **T11** (`docker-compose.prod.yml`
y sus comprobaciones de política) pasan a un cambio `11a-2`; la puerta `docker compose config` del overlay (BI126, parte del overlay), CR48,
CR49, CR50, CR51 y la nota de memoria de `docs/09` se trasladan con ellas. Ninguna bloquea a 11b, 11c ni 11e. T12 conserva entonces solo
la validación del archivo base.

## Reglas comunes a todas las tareas

- **Comandos del backend.** Desde `apps/api`, con `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` en la máquina de desarrollo
  (autorizado el 2026-10-24). Forma unitaria: `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='A,B'`.
  Forma de integración: `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=X -Dfailsafe.failIfNoSpecifiedTests=false`.
  Un `-Dtest=` acotado informa `BUILD FAILURE` por JaCoCo aunque las pruebas pasen: no es un fallo de la tarea. `./mvnw verify -Pmutation-gate`
  completo cierra cada PR.
- **Rojo registrado.** Cada paso en rojo corre contra el árbol sin el código de producción y se registra en
  `openspec/changes/dockerfiles-and-local-compose/apply-progress.md` con la causa **observada**. Un commit en rojo no entra al historial: las
  pruebas en rojo se comprometen con su verde. Después del verde, un paso de REFACTOR deja la suite en verde.
- **Sondas primero.** Las sondas P-1 a P-9 son el **paso 0** de la tarea que las necesita. Cada una se ejecuta, se registra su resultado en
  `apply-progress.md` y, si difiere de lo esperado, la tarea se detiene, se aplica la alternativa ya escrita en `design.md` y se añade una
  nota fechada al diseño antes de seguir (procedimiento de `background-jobs-with-db-scheduler`, design.md §2.1).
- **Control negativo.** Las tareas con pruebas de ausencia o de política nombran una ruptura temporal de producción que debe poner en rojo una
  aserción de resultado concreta; se restaura la copia y se confirma con `cmp` y `git diff` sin salida.
- **Secretos.** Ningún secreto en el repositorio (regla 13), ni el literal de contraseña de `docs/05`. Las pruebas generan en ejecución lo que
  necesiten.
- **Registros.** Nunca contraseña, token, `Authorization`, datos personales ni URL con credenciales en salida de `migrate`, de sondas ni de pruebas.
- **Notas documentales.** Notas fechadas añadidas, nunca reescritura de cuerpos existentes (`DocumentationNotesTest`).
- **Medición antes de abrir el PR.** `git diff --numstat main...HEAD` excluyendo `openspec/` y código generado, registrada en `apply-progress.md`.
  Si un PR supera 800 líneas efectivas, se parte por archivos completos en la costura que nombra su sección, sin consultar, con nota fechada.
- **Commits y ramas.** Conventional Commits en inglés, sin atribución de herramientas de IA, un commit por tarea con sus pruebas y docs. Ramas:
  `change/dockerfiles-and-local-compose-A-jar-and-migrate`, `-B-minimal-health`, `-C-images-and-compose`, `-D-memory-and-docs`.
- **Idioma.** Código, identificadores y commits en inglés; documentación y notas en español neutro profesional.
- **Revisión de seguridad por PR.** Lista aplicable de `docs/03` marcada en la descripción del PR.
- **Etiquetas exactas.** Las versiones de imágenes base (Temurin 25.0.x, Node 24.x, nginx-unprivileged, Hadolint) se resuelven el día de la tarea
  contra la fuente primaria (registro de la imagen) y se escriben literales en la línea `FROM` o en el paso de CI.

## Orden y dependencias

```
PR A:  T01 ── T02 ── T03
PR B:  T04 ── T05 ── T06 ── T07          (B parte de A fusionado)
PR C:  T08 ── T09 ── T10 ── T11 ── T12 ── T13   (C parte de B fusionado)
PR D:  T14 ── T15                         (D parte de C fusionado)
```

Orden duro: T01 antes de cualquier imagen (T08 copia el jar); T02 antes de T10 (Compose ejecuta `migrate`); T06 y T07 antes de T10 (las
comprobaciones de Compose consumen la salud); T10 antes de T11 (el overlay y la política leen el archivo base); T13 antes de T14 (se mide la
pila que se verificó); T15 al final (las notas citan los valores medidos). Revertir la salud (PR B) exige revertir antes C.
T08 y T09 son independientes entre sí y pueden redactarse en paralelo, pero se comprometen en orden (un solo escritor).

---

## PR A — `jar-and-migrate` (T01 a T03, unas 430 a 450 líneas)

### [ ] T01 — Jar ejecutable `confia-api.jar` sin debilitar la construcción

- **Objetivo:** `./mvnw verify` deja `apps/api/app/target/confia-api.jar` arrancable con `java -jar`; `kernel` sigue siendo biblioteca.
- **Archivos:** `apps/api/pom.xml` (`spring-boot-maven-plugin` en `pluginManagement` con `${spring-boot.version}`; `classesDirectory` de Failsafe),
  `apps/api/app/pom.xml` (`finalName`, ejecución `repackage`, `mainClass`), `apps/api/app/src/test/java/com/confia/bootstrap/ExecutableJarIT.java` (nuevo).
- **Escenarios:** CR01 (parte de lanzador: perfil inválido → 1), CR02, BI119, BI120. (CR01 completo, admin/portal/worker sanos, se cierra en T13.)
- **Paso 0 (sondas):** **P-8** `./mvnw verify` completo con el jar reempaquetado: las `*IT` siguen viendo las clases con
  `classesDirectory=${project.build.outputDirectory}`; si no, clasificador `exec` y `finalName` en ese artefacto. Se registra el resultado.
- **Enfoque (RED/GREEN):** RED: `ExecutableJarIT` (Failsafe, después de `package`) falla porque el jar no existe. Afirma: existe el jar con ese nombre;
  `java -jar` con `APP_PROFILE` inválido sale con 1 (subproceso con vector de argumentos fijo, sin intérprete de órdenes); entradas del jar sin clases de
  prueba, sin `db/testing` ni `db/codegen`, sin archivos de configuración con valores de secreto; `kernel/target` sin `Start-Class`. GREEN: pom.
  El caso `migrate` → 0 contra Testcontainers entra con T02.
- **Control negativo:** quitar la ejecución `repackage` debe poner en rojo la aserción «existe `confia-api.jar`» de `ExecutableJarIT`.
  BI120: introducir una importación cruzada de `domain` en una copia temporal y confirmar que `verify` falla antes de producir el jar.
- **Verificación:** `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ExecutableJarIT -Dfailsafe.failIfNoSpecifiedTests=false`;
  después `./mvnw verify` completo en verde y `ls app/target/confia-api.jar`.
- **Depende de:** ninguna (raíz de la cadena).

### [ ] T02 — Perfil `migrate`: contexto cerrado de Flyway que aplica y termina

- **Objetivo:** `APP_PROFILE=migrate` aplica Flyway como `confia_owner` y termina con 0; cualquier fallo, con 1; sin puertos, sin `Scheduler`, sin
  contraseña en la salida.
- **Archivos:** `apps/api/app/src/main/java/com/confia/bootstrap/ConfiaApplication.java` (rama `MIGRATE` real), `AppProfile.java` (Javadoc),
  `bootstrap/migrate/MigrateApplication.java` (nuevo), `application-migrate.yml` (`keep-alive: false`, `fail-on-missing-locations: true`),
  `ProcessBeanPolicy` (entrada `MIGRATE`), `architecture/BootstrapEntryPointRulesTest.java` (subpaquete `migrate`),
  pruebas nuevas `MigrateProfileIT`, `MigrateContextIsolationTest`, prueba unitaria del lanzador, `src/test/resources/db/broken/V999__fails.sql`.
- **Escenarios:** CR03, CR04, CR05, CR06 y la decisión 2 (aislamiento del contexto); refuerza CR01 (`migrate` → 0 por el jar).
- **Paso 0 (sondas):** **P-1 parcial** — confirmar los nombres de paquete de las autoconfiguraciones de `DataSource` y Flyway de Spring Boot 4.1.1
  (`./mvnw dependency:tree` y `jar tf` de las dependencias) antes de escribir `@ImportAutoConfiguration`.
- **Enfoque (RED/GREEN):** RED primero: prueba del lanzador con base inalcanzable (CR05, exige código 1 y nunca contexto vivo); `MigrateProfileIT` sobre
  PostgreSQL de Testcontainers con los roles de prueba: base vacía → 0 y todas las migraciones en `flyway_schema_history` con objetos de `confia_owner`
  (CR03); repetición sin cambio (CR04); migración rota y suma divergente → distinto de 0, salida con el nombre de la migración y sin la contraseña
  capturada (CR06); `MigrateContextIsolationTest`: sin servidor web, sin `Scheduler`, sin cadena de seguridad, sin `MeterRegistry`; prueba de
  política `MIGRATE`. GREEN: `MigrateApplication` y la rama del lanzador. Se añade el caso `migrate` → 0 a `ExecutableJarIT`.
- **Control negativo:** añadir `@EnableAutoConfiguration` a `MigrateApplication` debe poner en rojo `MigrateContextIsolationTest` por la aserción de
  «sin `Scheduler`»; imprimir la URL con credenciales debe poner en rojo la aserción de ausencia de contraseña de CR06.
- **Verificación:** `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='MigrateContextIsolationTest,BootstrapEntryPointRulesTest,ProcessBeanIsolationTest'`;
  `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='MigrateProfileIT,ExecutableJarIT' -Dfailsafe.failIfNoSpecifiedTests=false`.
- **Depende de:** T01 (el caso por jar). Las pruebas del lanzador y del contexto no dependen de T01.

### [ ] T03 — Ningún otro perfil migra (CR07, CR08)

- **Objetivo:** `admin`, `portal` y `worker` contra una base vacía o a falta de una migración no crean esquema ni tocan `flyway_schema_history`.
- **Archivos:** `apps/api/app/src/test/java/com/confia/bootstrap/MigrateProfileIT.java` (extensión) o `OtherProfilesDoNotMigrateIT.java` (nuevo).
- **Escenarios:** CR07 (parte de esquema; la parte «no indica preparado» se cierra en T06), CR08.
- **Enfoque (RED/GREEN):** pruebas de caracterización: el estado actual ya cumple (`flyway.enabled: false`), así que **no hay rojo natural**. Excepción
  declarada; el rojo se obtiene con el control negativo. Casos: base vacía sin tablas y sin `flyway_schema_history` tras arrancar cada uno de los tres
  perfiles; base migrada hasta la anterior a la última (sembrada con `Flyway` limitado por `target` en el fixture) sin cambios tras arrancar `admin`.
- **Control negativo:** poner `spring.flyway.enabled: true` en `application.yml` debe poner en rojo ambas aserciones (tabla creada, historial cambiado).
- **Verificación:** `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='MigrateProfileIT' -Dfailsafe.failIfNoSpecifiedTests=false`;
  cierre del PR A: `./mvnw verify -Pmutation-gate` en verde (cobertura y mutación en umbrales).
- **Depende de:** T02.

---

## PR B — `minimal-health` (T04 a T07, unas 600 líneas)

> Desviación respecto del diseño §10: el orden interno pasa a «configuración (T04), lista blanca (T05), indicador `db` y pruebas de sonda (T06),
> latido (T07)». Motivo: `HealthProbesIT` necesita que las rutas ya sean públicas para afirmar `200`; con el orden del diseño habría que escribir
> la prueba contra `401`. El contenido total y el alcance no cambian.

### [ ] T04 — Dependencias y configuración de salud; ausencia de métricas

- **Objetivo:** Actuator solo con salud, sin `MeterRegistry`, rutas exactas `GET /health/live` y `/health/ready`, sin `/actuator/**`.
- **Archivos:** `apps/api/app/pom.xml` (solo los artefactos de salud que confirme P-1), `application.yml` (`management.*`, `springdoc.show-actuator: false`),
  `src/test/.../CurrentAbsencesTest.java` (reescrita según el delta de BJ38), `WorkerHasNoMeterRegistryTest.java` (nuevo), fixtures de BJ75,
  `shared/observability/queue/package-info.java` (frase «Actuator solo de salud, sin registro de Micrometer»).
- **Escenarios:** BJ38, BJ73, BJ75, CR15; sienta CR12 y CR14 (cierre en T05 y T06).
- **Paso 0 (sondas):** **P-1** módulos de salud de Spring Boot 4.1.1: `./mvnw dependency:tree` sin `spring-boot-micrometer-*` ni `micrometer-registry-*`
  y confirmación de qué artefacto aporta el punto final de salud, los grupos y el indicador `db`. Alternativa escrita: excluir el módulo de métricas y
  su autoconfiguración por nombre en los tres puntos de entrada. **P-2** forma de las rutas: con `management.server.port: -1` y `additional-path`
  `server:/health/live` y `server:/health/ready`, las rutas se sirven en el puerto principal con patrón exacto; alternativa A2 (puerto de gestión local).
- **Enfoque (RED/GREEN):** RED: `CurrentAbsencesTest` reescrita falla (aún no hay artefacto de salud: el conjunto de Actuator declarado debe ser
  exactamente el de P-1; cualquier `spring-boot-starter-actuator`, `spring-boot-micrometer-.*`, `micrometer-.*` o `.*prometheus.*` falla);
  `WorkerHasNoMeterRegistryTest` evalúa los tres contextos reales (no vacíos); fixture permanente que añade un `MeterRegistry` y otro con
  `management.endpoints.web.exposure.include=health,info` que hace aparecer una ruta extra, ambos deben fallar (BJ75). Una prueba de contexto afirma
  que el conjunto de endpoints de Actuator es solo la salud con grupos vida y preparación (CR15). GREEN: pom y `application.yml`.
- **Control negativo:** declarar `spring-boot-starter-actuator` debe poner en rojo `CurrentAbsencesTest` por la aserción del artefacto prohibido.
- **Verificación:** `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='CurrentAbsencesTest,WorkerHasNoMeterRegistryTest,ProcessBeanIsolationTest'`.
- **Depende de:** T03 (PR A fusionado).

### [ ] T05 — Lista blanca pública con exactamente las dos rutas de salud

- **Objetivo:** `PublicEndpoints` incluye `GET /health/live` y `GET /health/ready` en administración y portal; el resto sigue denegado con `401`.
- **Archivos:** `shared/web/edge/PublicEndpoints.java` (`HEALTH_LIVE`, `HEALTH_READY` en `forAdmin` y `forPortal`; Javadoc de las líneas 20-21),
  `src/test/.../PublicRouteAllowListTest.java` (`publicRoutesOf`, `productionRoutesOf` de ambos procesos), instantánea de rutas de aplicación del portal (sin cambio, afirmada).
- **Escenarios:** WE94, WE95, WE96, WE97, WE98, WE99, CR14 (parte pública `401`), CR13 (perfil por omisión); regresión de los escenarios vigentes sin
  identificador de la lista blanca, de la cadena del portal y de la documentación con `prod`/`local`.
- **Enfoque (RED/GREEN):** RED: `PublicRouteAllowListTest` actualizada espera las dos rutas y falla (hoy responden `401`); casos: ocho peticiones no `GET`
  a las dos rutas de cada proceso → `401`; `/actuator`, `/actuator/health`, `/actuator/env`, `/actuator/metrics`, `/actuator/prometheus`,
  `/health/other` → `401` con `authentication-required`; fixture que registra una ruta de salud adicional fuera de la lista y la prueba la nombra
  (WE97); rutas de producción exactas = previas más las dos. GREEN: `PublicEndpoints`.
- **Control negativo:** añadir una tercera entrada de salud a `PublicEndpoints` debe poner en rojo la aserción de «lista exacta».
- **Verificación:** `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='PublicRouteAllowListTest,RouteCoverageGuardTest,ProcessBeanIsolationTest'`.
- **Depende de:** T04.

### [ ] T06 — Indicador `db` de preparación, `connection-timeout` y `HealthProbesIT`

- **Objetivo:** `/health/ready` responde `200` solo con PostgreSQL migrado y `503 {"status":"DOWN"}` en menos de 8 s con la base caída; vida sigue `200`;
  cuerpos sin detalle.
- **Archivos:** `shared/observability/health/ReadinessDatabaseHealthConfiguration.java` y `package-info.java` (nuevos), `AdminApplication.java` y
  `PortalApplication.java` (`@Import`), `application.yml` (`hikari.connection-timeout: 5s`), registro del SQL plano aprobado
  (`SELECT 1 FROM flyway_schema_history LIMIT 1`), `src/test/.../HealthProbesIT.java` (nuevo).
- **Escenarios:** CR09, CR10, CR11, CR12, CR13 (con `local` y por omisión), CR14 (peticiones reales), CR07 (parte «no indica preparado» contra base sin
  migrar), WE101, BJ74.
- **Enfoque (RED/GREEN):** RED: `HealthProbesIT` (administración y portal, perfil por omisión y `local`): ambas `200 {"status":"UP"}` y nada más; PostgreSQL
  detenido en el contenedor → vida `200`, preparación `503 {"status":"DOWN"}` en menos de 8 s y sin `components`, `details`, host, puerto ni texto del
  controlador; mismo cuerpo con token administrativo válido; base sin migrar → preparación `503`; `/actuator/metrics` y `/actuator/prometheus` sin
  contenido; el `503` no es Problem Details (WE101) y otro error del mismo proceso sigue siéndolo; el grupo de preparación contiene solo
  `readinessState` y `db` (CR12). GREEN: indicador, tiempo de espera.
- **Control negativo:** `show-details: always` debe poner en rojo la aserción de «sin `components`»; sustituir el SQL por `isValid()` debe poner en rojo el
  caso de base sin migrar.
- **Verificación:** `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='HealthProbesIT' -Dfailsafe.failIfNoSpecifiedTests=false`.
- **Depende de:** T05.

### [ ] T07 — Latido del trabajador refrescado por el sondeo de db-scheduler

- **Objetivo:** el trabajador toca un archivo en cada ciclo de sondeo contra PostgreSQL; sin puerto, sin ruta, sin hilos propios.
- **Archivos:** `shared/jobs/infrastructure/` (oyente de latido y su registro en `WorkerJobsConfiguration`; propiedad `confia.health.heartbeat-file`),
  `src/test/.../shared/jobs/infrastructure/WorkerHeartbeatListenerTest.java` y `WorkerHeartbeatIT.java` (nuevos).
- **Escenarios:** CR16, CR17 (lado de código: sin puerto ni ruta), WE100, y regresión del escenario vigente «Contexto del trabajador sin borde web de aplicación».
- **Paso 0 (sondas):** **P-4** señal de sondeo de db-scheduler 16.12.0: el `Scheduler` acepta un `SchedulerListener` que emite un evento al terminar cada
  ciclo de sondeo, también sin tareas vencidas. Si no, alternativa B: enganche a `onExecutionComplete` de `shared.queue-observation` y ajuste de `-mmin`
  a dos periodos de esa tarea.
- **Enfoque (RED/GREEN):** RED: `WorkerHeartbeatListenerTest`: el evento de ciclo actualiza la fecha de modificación, los demás eventos no, un error de
  E/S se registra una vez en `WARN` y no se propaga, sin propiedad no se registra el oyente; `WorkerHeartbeatIT`: trabajador con ejecutor activo y
  archivo temporal, la fecha avanza; con PostgreSQL detenido deja de avanzar (aserción por mtime con tiempo acotado, sin esperas fijas); el contexto
  no abre puerto ni ruta (WE100). `NoInMemorySchedulingTest` y `ProcessBeanIsolationTest` siguen en verde. GREEN: oyente y registro.
- **Control negativo:** registrar el oyente sobre un evento distinto al de fin de ciclo debe poner en rojo `WorkerHeartbeatIT` (la fecha no avanza sin tareas).
- **Verificación:** `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='WorkerHeartbeatListenerTest,NoInMemorySchedulingTest,ProcessBeanIsolationTest'`;
  `-Dit.test='WorkerHeartbeatIT'`; cierre del PR B: `./mvnw verify -Pmutation-gate` en verde.
- **Depende de:** T06 (comparte la configuración del trabajador y el cierre del PR).

---

## PR C — `images-and-compose` (T08 a T13, unas 550 a 650 líneas)

### [ ] T08 — `api.Dockerfile` y su exclusión de contexto

- **Objetivo:** imagen de la API delgada: copia el jar construido fuera, JRE Temurin 25, usuario 10001, sin `HEALTHCHECK`, `JAVA_TOOL_OPTIONS`.
- **Archivos:** `infra/docker/api.Dockerfile`, `infra/docker/api.Dockerfile.dockerignore` (lista blanca: `*` y `!apps/api/app/target/confia-api.jar`).
- **Escenarios:** CR18, CR19, CR20, CR22, CR23, CR24, CR27 (parte Dockerfile), CR29 y CR30 (imagen de la API), BI121 (la imagen copia el jar; ningún paso invoca Maven).
- **Paso 0 (sondas):** **P-3** `docker run --rm --entrypoint sh <imagen> -c 'command -v curl wget bash find'` sobre `eclipse-temurin:<25.0.x_y>-jre-noble`
  (sin `curl`: sonda con `bash` y `/dev/tcp`; nunca se instala un paquete). **P-9** `java -Djarmode=tools -jar confia-api.jar extract --layers --destination <dir>`
  produce las cuatro capas y `java -jar` sobre el resultado arranca (si no, copiar el jar entero). Se resuelve la etiqueta exacta vigente.
- **Enfoque:** excepción declarada: Dockerfile sin ejecutor de rojo. Comprobaciones: (1) Hadolint local sin hallazgos; (2) construcción con el jar de T01;
  (3) sumas del jar de la imagen y del construido fuera; (4) `docker run --entrypoint id` → `10001`; `java -version` frente a la salida del JDK de CI; sin `javac`, `mvn` ni `.java`;
  (5) `/tmp` `noexec`: copiar un ejecutable y comprobar el rechazo; (6) `PrintFlagsFinal` con `-m 512m` y `-m 1g` (montón distinto y proporcional, inferior al límite);
  (7) `docker history --no-trunc` y `docker inspect` sin contraseñas, claves ni valores de `.env.example`; con un `.env` de prueba presente, ausente del contexto y de las capas.
  Evidencia en `apply-progress.md`.
- **Verificación:** `docker run --rm -i hadolint/hadolint:<vX.Y.Z> hadolint --failure-threshold warning - < infra/docker/api.Dockerfile`;
  `docker build -f infra/docker/api.Dockerfile -t confia-api:local .`; `docker run --rm --entrypoint id confia-api:local`;
  `docker run --rm -m 768m --entrypoint java confia-api:local -XX:+PrintFlagsFinal -version | grep MaxHeapSize`.
- **Depende de:** T01 (jar), T06 y T07 (para la sonda real de salud en T10; no para construir).

### [ ] T09 — `web.Dockerfile`, `web.nginx.conf` y exclusión de contexto

- **Objetivo:** imagen web de `admin-web` construida con Vite en Node 24 y servida por nginx sin privilegios con raíz de solo lectura y `/healthz`; parametrizada por `APP_NAME`.
- **Archivos:** `infra/docker/web.Dockerfile`, `infra/docker/web.Dockerfile.dockerignore`, `infra/docker/web.nginx.conf`.
- **Escenarios:** CR31, CR32, CR33, CR34, CR35 (o CR36 si P-5 falla), CR27 (parte web), CR29 y CR30 (imagen web).
- **Paso 0 (sondas):** **P-5** `nginxinc/nginx-unprivileged:<x.y.z>-alpine` con `user: 10001`, `read_only` y `tmpfs /tmp` arranca y sirve `/healthz`. Si falla,
  se aplica CR36: `read_only` queda desactivado y la excepción se escribe como nota fechada con motivo y evidencia (T15).
- **Enfoque:** excepción declarada (sin ejecutor de rojo). Comprobaciones: Hadolint sin hallazgos; construcción desde el árbol limpio sin JDK ni demonio
  dentro del `build` (CR34, esquemas de `packages/contracts` desde `apps/api/openapi/`); `docker run --read-only --tmpfs /tmp --user 10001` → `/healthz` `200` y `/` documento HTML;
  usuario no raíz, puerto 8080, sin código fuente, `node_modules` ni `node` en la imagen final; `--build-arg APP_NAME=no-existe` falla con mensaje
  `unknown application: no-existe` (CR33); `docker history` sin secretos. Evidencia en `apply-progress.md`.
- **Verificación:** `docker build -f infra/docker/web.Dockerfile --build-arg APP_NAME=admin-web -t confia-admin-web:local .`;
  `docker run --rm -d --read-only --tmpfs /tmp --user 10001:10001 -p 127.0.0.1:8090:8080 confia-admin-web:local` y `curl -fsS http://127.0.0.1:8090/healthz`;
  `docker build ... --build-arg APP_NAME=no-existe .` debe terminar con código distinto de 0.
- **Depende de:** ninguna técnica (independiente de T08); se compromete después de T08.

### [ ] T10 — `docker-compose.yml`, script de roles locales y `.env.example`

- **Objetivo:** `docker compose up` con un `.env` copiado de `.env.example` deja sanos los cinco servicios de larga vida y `migrate` terminado.
- **Archivos:** `docker-compose.yml` (raíz; `x-jvm-hardening`, `x-web-hardening`, límites de memoria del base, redes `data` y `edge`),
  `infra/docker/postgres/initdb/10-create-local-roles.sh`, `.env.example`.
- **Escenarios:** CR37, CR38, CR39, CR40, CR41, CR42, CR43, CR44, CR45, CR46, CR47, CR17 (`worker` sin puertos), CR21 (arranque de los cuatro perfiles con raíz de solo lectura), CR25, CR26.
- **Paso 0 (sondas):** **P-7** volumen de PostgreSQL 18: `docker compose up postgres` con volumen nuevo y reinicio sin pérdida; montar en la ruta que declare la imagen.
- **Enfoque:** excepción declarada (sin ejecutor de rojo). Orden: (1) `.env.example` y `docker compose config` con los marcadores; (2) `docker compose config` sin `.env`
  falla nombrando la variable (CR47, `${VAR:?}`; sin `env_file`); (3) `git check-ignore .env` y `git ls-files .env.example` (CR46); (4) `pg_isready -h 127.0.0.1`
  y existencia de los cinco roles con su contraseña antes de `migrate` (CR43); (5) el script no contiene literales ni lo copia ninguna imagen (CR44,
  `grep` y `docker history`); (6) los valores que no pueden ser marcadores (claves de firma, maestra, pimienta, CSRF, UUID) llevan el comando que los
  genera en `.env.example` (CR45); (7) arranque real. Detalle del escenario sistémico en T13.
- **Verificación:** `cp .env.example .env` y completar con los comandos del archivo; `docker compose config --quiet`; `docker compose up --wait`;
  `docker compose ps --all`; `git check-ignore -v .env`; `docker compose --env-file .env.example config --quiet`.
- **Depende de:** T02, T06, T07, T08, T09.

### [ ] T11 — Overlay de producción y `infra/ci/check-container-files.sh`

- **Objetivo:** `docker-compose.prod.yml` valida con `docker compose config`; la política sobre el JSON resuelto rompe ante una desviación.
- **Archivos:** `docker-compose.prod.yml`, `infra/ci/check-container-files.sh`.
- **Escenarios:** CR48, CR49, CR27 (imágenes de Compose), CR37, CR38, CR39, CR53, CR54; refuerza CR17 y CR44 (script y overlay).
- **Paso 0 (sondas):** **P-6** `!reset null` elimina un servicio completo y `!override` reemplaza `depends_on`:
  `docker compose -f docker-compose.yml -f docker-compose.prod.yml config --services` sin `postgres` (Compose de `ubuntu-latest` ≥ 2.24). Alternativa escrita: perfil `local-db` con
  `COMPOSE_PROFILES` en `.env.example`.
- **Enfoque (RED/GREEN para el script):** el script es código con ejecutor (`bash`): RED primero con casos que debe rechazar, ejecutados contra copias
  temporales mutadas del base y del overlay: servicio extra o faltante, puerto sin `127.0.0.1`, `postgres` o dependencia hacia él en el combinado,
  falta de `cap_drop`/`read_only`/`no-new-privileges`/usuario/límite en el combinado, imagen `latest` o sin etiqueta, `@sha256:` presente (CR53), `# hadolint ignore=`
  sin comentario `# reason:` en la línea anterior (BI124), `cd.yml`, Renovate o recursos de IaC presentes (CR53, CR54), versión de PostgreSQL distinta de `confia.postgres.image`, mayor de Node
  distinto de `.nvmrc`, mayor de la JRE distinto de `JAVA_VERSION` de CI. GREEN: script y overlay correctos pasan.
- **Verificación:** `bash infra/ci/check-container-files.sh` (verde sobre el árbol real, rojo en cada mutación documentada);
  `docker compose --env-file .env.example config --quiet`; `CONFIA_API_IMAGE=confia-api:ci CONFIA_ADMIN_WEB_IMAGE=confia-admin-web:ci docker compose --env-file .env.example -f docker-compose.yml -f docker-compose.prod.yml config --quiet`.
- **Depende de:** T10 (lee el base); T08 y T09 (reglas sobre Dockerfiles).

### [ ] T12 — Trabajo `containers` en CI (Hadolint y `docker compose config`)

- **Objetivo:** CI rechaza un Dockerfile o un Compose inválidos, con resultado visible en el remoto, sin secretos reales.
- **Archivos:** `.github/workflows/ci.yml` (trabajo `containers`, `timeout-minutes: 5`; comentario de cabecera).
- **Escenarios:** BI122, BI123, BI124, BI125, BI126, BI127, CR28; BI121 (el flujo no construye imágenes todavía: ningún paso invoca Maven; la construcción encadenada es de 11c).
- **Enfoque:** excepción declarada (CI sin ejecutor local de rojo); se prueba en una rama de PR con commits de demostración que se descartan. Pasos del trabajo:
  Hadolint por etiqueta exacta (o acción fijada por SHA) sobre `infra/docker/*.Dockerfile` con `--failure-threshold warning` y fallo si la lista está vacía (BI123);
  `docker compose config --quiet` sobre base y sobre base más overlay con `--env-file .env.example` y variables de imagen ficticias (BI125 a BI127);
  `bash infra/ci/check-container-files.sh` (BI124). Demostración: un commit temporal con `FROM eclipse-temurin:latest` pone el trabajo en rojo señalando archivo y regla
  (BI122, CR28); un commit temporal con una referencia a un servicio inexistente en el overlay lo pone en rojo (BI125); ambos se revierten y el trabajo vuelve a verde.
  Resultado de cada ejecución registrado con la URL de la ejecución en `apply-progress.md`.
- **Verificación:** `actionlint .github/workflows/ci.yml` si está disponible, o `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:<vX.Y.Z>`; ejecución del trabajo en el PR C
  (verde) y las dos ejecuciones de demostración (rojas).
- **Depende de:** T11 (script y overlay).

### [ ] T13 — Verificación de sistema manual y guion en `apps/api/README.md`

- **Objetivo:** dejar evidencia reproducible del arranque real de la pila y de las propiedades que solo se comprueban con un demonio.
- **Archivos:** `apps/api/README.md` (cómo construir el jar y levantar la pila; guion de verificación), `openspec/changes/dockerfiles-and-local-compose/apply-progress.md` (evidencia).
- **Escenarios:** CR01 (admin, portal y worker por jar), CR16, CR20, CR21, CR22, CR25, CR26, CR29, CR30, CR31, CR32, CR35, CR40, CR41, CR42, CR43, CR44, CR46, CR52 (estado sin `OOMKilled` tras el arranque).
- **Enfoque:** excepción declarada: sistema, manual (11c construye en CI, 11f levanta la pila en CI). Guion, cada paso con su salida en `apply-progress.md`: (1) `docker compose up --wait`
  deja `postgres`, `api-admin`, `api-portal`, `worker` y `admin-web` sanos y `migrate` con `ExitCode 0` (CR40, CR16); (2) `docker compose exec api-admin id -u` → `10001`, `touch /x` falla, `/tmp` `noexec` (CR20 a CR22);
  (3) rotura de `migrate` (V999 de prueba en una copia de trabajo o contraseña errada): sale distinto de 0 y los tres servicios no se crean (CR41); `postgres` forzado a no sano retiene a `migrate` (CR42);
  (4) detener `postgres`: `api-admin` pasa a no sano tras los reintentos (CR26) y `worker` también (CR16 negativo); (5) `docker history`/`inspect` de ambas imágenes sin secretos y `.env` ausente (CR29, CR30);
  (6) `curl http://127.0.0.1:8090/healthz` y `/` (CR31, CR32, CR35); (7) `docker compose config` muestra puertos solo en `127.0.0.1`; (8) `docker inspect --format '{{.State.OOMKilled}}'` de cada contenedor (CR52).
- **Verificación:** ejecución del guion de extremo a extremo por una segunda persona o por una sesión limpia (volumen nuevo: `docker compose down -v` antes de empezar) y registro de la salida.
- **Depende de:** T10, T11, T12. Cierre del PR C: `./mvnw verify -Pmutation-gate` sigue en verde y el trabajo `containers` en verde.

---

## PR D — `memory-and-docs` (T14 a T15, unas 250 líneas)

### [ ] T14 — Medición de memoria y ajuste de límites

- **Objetivo:** medir `admin`, `portal` y `worker` bajo los límites de Compose y fijar límites y `JAVA_TOOL_OPTIONS` con el resultado.
- **Archivos:** `docker-compose.yml` (límites ajustados), `infra/docker/api.Dockerfile` (`MaxRAMPercentage` si cambia), `openspec/changes/dockerfiles-and-local-compose/apply-progress.md` (datos crudos).
- **Escenarios:** CR50, CR51, CR52, CR23, CR24 (montón con los límites finales).
- **Enfoque:** excepción declarada: medición manual (decisión 9). Procedimiento: `docker compose up --wait`; `docker stats --no-stream --format json` cada 5 s durante 10 minutos; carga ligera (300
  peticiones a `/health/ready` y a la ruta de inicio de sesión administrativa con credenciales inválidas); pico por `cat /sys/fs/cgroup/memory.peak` o máximo del muestreo en Docker Desktop;
  `PrintFlagsFinal` con dos límites; `docker inspect` de `OOMKilled` y código de salida. Regla: se conserva un límite si el pico no supera el 85 %; si lo supera, se sube el límite o se ajusta
  `MaxRAMPercentage` y se repite. Si la suma de límites no cabe en la instancia de fase uno (`docs/05` §2), se registra y se **escala al propietario** (ADR condicional, ADR-0013 línea 242) antes de 11d.
- **Verificación:** tabla por proceso (pico, reposo, límite, porcentaje, `JAVA_TOOL_OPTIONS`, fecha, commit, condiciones) en `apply-progress.md`; `bash infra/ci/check-container-files.sh` sigue en verde con los límites nuevos;
  `docker compose up --wait` sano con los límites finales.
- **Depende de:** T13.

### [ ] T15 — Notas documentales y cierre del cambio

- **Objetivo:** dejar el contrato documentado sin reescribir cuerpos.
- **Archivos:** `docs/05-infraestructura-y-despliegue.md` (notas fechadas en §3.1 regla 2, §3.2, §3.3, §4, §5 y la decisión de `read_only` o su excepción CR36, con los límites medidos),
  `docs/07-observabilidad-y-operaciones.md` (§9: subconjunto entregado, `UP`/`DOWN`, indicador `db`, Compose comprueba preparación, Docker no reinicia por no sano; §9.1: overlay en la raíz; línea 513),
  `docs/09-roadmap-y-fases.md` (nota de medición del entregable 1), `openspec/changes/foundations-plan/exploration.md` (estado de 11a), `apps/api/README.md` (ajustes finales).
- **Escenarios:** CR36 (si P-5 falló), CR50, CR51 (documento), y regresión de `DocumentationNotesTest`.
- **Enfoque (RED/GREEN):** `DocumentationNotesTest` existente exige que cada línea original siga presente y en orden: es el rojo si se reescribe algo. Las notas se añaden, no se editan.
- **Verificación:** `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='DocumentationNotesTest'`; cierre del PR D y del cambio: `./mvnw verify -Pmutation-gate` en verde,
  `bash infra/ci/check-container-files.sh` en verde y trabajo `containers` en verde; después `/sdd-verify`.
- **Depende de:** T14.

---

## Matriz escenario-tarea

Cada escenario tiene al menos una tarea; «(sistema)» indica verificación manual con evidencia en `apply-progress.md`; «(CI)» indica puerta automática.

### `container-runtime` (CR01 a CR54)

| Escenario | Tareas | Escenario | Tareas |
|---|---|---|---|
| CR01 | T01, T02, T13 (sistema) | CR28 | T08, T11, T12 (CI) |
| CR02 | T01 | CR29 | T08, T09, T13 (sistema) |
| CR03 | T02 | CR30 | T08, T09, T13 (sistema) |
| CR04 | T02 | CR31 | T09, T13 (sistema) |
| CR05 | T02 | CR32 | T09, T13 (sistema) |
| CR06 | T02 | CR33 | T09 (sistema) |
| CR07 | T03, T06 | CR34 | T09 (sistema) |
| CR08 | T03 | CR35 | T09, T13 (sistema) |
| CR09 | T06 | CR36 | T09, T15 (condicional: solo si P-5 falla) |
| CR10 | T06 | CR37 | T10, T11 |
| CR11 | T06 | CR38 | T10, T11 |
| CR12 | T04, T06 | CR39 | T10, T11 |
| CR13 | T05, T06 | CR40 | T10, T13 (sistema) |
| CR14 | T05, T06 | CR41 | T10, T13 (sistema) |
| CR15 | T04 | CR42 | T10, T13 (sistema) |
| CR16 | T07, T13 (sistema) | CR43 | T10, T13 (sistema) |
| CR17 | T07, T10, T11 | CR44 | T10, T11, T13 |
| CR18 | T08 (sistema) | CR45 | T10 |
| CR19 | T08 (sistema) | CR46 | T10, T13 |
| CR20 | T08, T13 (sistema) | CR47 | T10 |
| CR21 | T10, T13 (sistema) | CR48 | T11 |
| CR22 | T08, T13 (sistema) | CR49 | T11 |
| CR23 | T08, T14 (sistema) | CR50 | T14, T15 |
| CR24 | T08, T14 (sistema) | CR51 | T14, T15 |
| CR25 | T10, T13 (sistema) | CR52 | T13, T14 |
| CR26 | T10, T13 (sistema) | CR53 | T11, T12 |
| CR27 | T08, T09, T11, T12 | CR54 | T11 |

### `build-integrity` (BI119 a BI127)

| Escenario | Tareas | Escenario | Tareas |
|---|---|---|---|
| BI119 | T01 | BI124 | T11, T12 |
| BI120 | T01 | BI125 | T12 (CI), T11 |
| BI121 | T08, T12 (estructural; la construcción de imágenes en CI es de 11c) | BI126 | T11, T12 (CI) |
| BI122 | T12 (CI), T08 | BI127 | T11, T12 (CI) |
| BI123 | T12 (CI), T08, T09 | | |

### `web-edge` (WE94 a WE101 y escenarios vigentes sin identificador)

| Escenario | Tareas |
|---|---|
| WE94 | T05, T06 |
| WE95 | T05 |
| WE96 | T05 |
| WE97 | T05 |
| WE98 | T05 |
| WE99 | T05 |
| WE100 | T07, T10 (sin puertos publicados) |
| WE101 | T06 |
| Vigentes sin identificador: «Una ruta pública nueva sin editar la lista», «La lista contiene solo lo declarado», «Documentación de API con perfil `prod` / `local`», cinco de la cadena del portal («Toda ruta del portal es denegada», «Una ruta añadida al portal sigue denegada», «Sin ruta pública de aplicación en el portal», «Documentación del portal con perfil `local`»), «Contexto del trabajador sin borde web de aplicación», cuatro de Problem Details | Regresión en T05 (lista blanca, portal, documentación), T06 (Problem Details y `503`), T07 (trabajador); se corren en el cierre de cada PR con `./mvnw verify -Pmutation-gate` |

### `background-jobs` (BJ38, BJ73 a BJ75)

| Escenario | Tareas |
|---|---|
| BJ38 | T04 |
| BJ73 | T04 |
| BJ74 | T06 |
| BJ75 | T04 |

**Resultado de cobertura:** 54 CR, 9 BI, 8 WE con identificador y 4 BJ, todos con tarea asignada; ningún escenario sin cobertura. Tres
matices: (a) CR36 es condicional y queda sin aplicar si P-5 pasa; (b) BI121 se cubre de forma estructural (la imagen copia el jar y ningún paso
invoca Maven), porque la construcción de imágenes en CI es de 11c; (c) CR53 y CR54 son «ciertos hoy, falsos al cerrar 11c/11d/11f» y los protege
la política de T11 hasta entonces.

## Fronteras de PR y tamaño estimado

| PR | Tareas | Líneas efectivas (sin `openspec/`) | Reversión | Costura si supera 800 |
|---|---|---|---|---|
| A `jar-and-migrate` | T01, T02, T03 | 430 a 450 | Se revierte entero; `migrate` vuelve al estado vacío sin efecto sobre datos | T01 / T02 y T03 |
| B `minimal-health` | T04, T05, T06, T07 | unas 600 | Antes se revierten las comprobaciones de Compose (PR C) | T04 y T05 / T06 / T07 |
| C `images-and-compose` | T08, T09, T10, T11, T12, T13 | 550 a 650 | Archivos nuevos; se eliminan sin tocar código de negocio | T08 y T09 / T10 / T11 y T12 y T13 |
| D `memory-and-docs` | T14, T15 | unas 250 | Notas y límites; se revierte con su commit | Ninguna |
| **Total** | **15** | **unas 1 850** | | |

Las cifras son heurística de planificación (unas 400 líneas por unidad de trabajo), no un límite duro; el límite de revisión del proyecto es de 800
líneas efectivas por PR (`docs/15` §3). Los PR A y B pueden fusionarse en una sola revisión solo si su medición conjunta es de 800 o menos.

## Pasos humanos y manuales

1. **Aprobación del propietario** de este `tasks.md` antes de `/sdd-apply`.
2. **Entorno de desarrollo:** Docker Desktop (WSL2) en ejecución, JDK 25 y `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` ya autorizado.
3. **Creación local de `.env`** desde `.env.example`, generando claves y UUID con los comandos que el archivo documenta (T10, T13). El `.env` nunca se compromete.
4. **Verificación de sistema (T13) y medición de memoria (T14):** manuales, en la máquina de desarrollo; sus tablas se registran en `apply-progress.md`.
5. **Resolución de etiquetas exactas** de imágenes base y de Hadolint el día de cada tarea (fuente primaria: registro de la imagen).
6. **Ejecuciones de demostración en CI** (T12): commits temporales en la rama del PR C que se revierten.
7. **Fusión de cada PR** a `main` en orden A, B, C, D por el propietario (empuje, PR y fusión son decisiones humanas).
8. **Decisión condicional del propietario:** si T14 muestra que la suma de límites no cabe en la instancia de fase uno, se decide el ADR de escalado (ADR-0013 línea 242) antes de 11d.
9. **Pregunta abierta fuera de alcance:** asignación de la semilla de datos ficticios (pregunta 1 de la propuesta).

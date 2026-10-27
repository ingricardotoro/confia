# Tareas: trabajos en segundo plano con db-scheduler (cambio 9 de F0)

- **Cambio:** `background-jobs-with-db-scheduler` (F0, cambio 9; entregable 12, criterio de salida 5)
- **Fase:** tareas
- **Fecha:** 2026-10-24
- **Estado:** pendiente de aprobación del propietario
- **Entradas aprobadas:** `proposal.md` (P1 a P9 vinculantes), `design.md` (decisiones 1 a 22, sección 9 y decisiones D-N1, D-N2 y D-N3 del
  propietario del 2026-10-24) y los deltas `specs/background-jobs/spec.md`, `specs/identity/spec.md`, `specs/build-integrity/spec.md` y
  `specs/audit-trail/spec.md` (identificadores `BJ`, `I`, `BI` y `AT`, más los escenarios sin identificador que cada tarea nombra por su título)
- **Modo de TDD:** estricto (`openspec/config.yaml`). Ejecutor: `./mvnw verify` en `apps/api`, con `JAVA_HOME` apuntando a JDK 25 y Docker en ejecución.
- **Línea base de la cadena:** `main` en `b76beda`.

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines (additions + deletions, sin `openspec/` ni código generado) | **nominal unas 4 200 (propuesta, punto medio); realista entre 9 500 y 11 500** con el factor 2,2 de la propuesta y el historial de S2 (las tareas partidas midieron de 1,5 a 3,8 veces lo realista) |
| 400-line budget risk | **High** frente a las 400 líneas de la preflight; **medio a alto por PR** frente al presupuesto del proyecto de 800 |
| Chained PRs recommended | Yes |
| Suggested split | **10 rebanadas del diseño §9.1 (1, 2a, 2b, 3a, 3b, 4, 5a, 5b, 6, 7), planificadas como una cadena de 14 a 20 PR apilados** contra `main`; cada rebanada nombra su costura |
| Delivery strategy | `single-pr` con 400 líneas en la preflight de la sesión; **prevalece la práctica del propietario**: cadena apilada, 800 líneas efectivas por PR (`docs/15-flujo-de-trabajo-git.md` §3), medidas sin `openspec/` |
| Chain strategy | `stacked-to-main`: cada PR se fusiona a `main` en orden y el siguiente parte de `main` actualizado |
| Presupuesto del proyecto por PR | 800 líneas de cambio efectivo; las pruebas nunca se separan del código que prueban |

Decision needed before apply: No (la cadena apilada con 800 líneas efectivas por PR es práctica fijada por el propietario y prevalece sobre `single-pr`
con 400; no hay decisión nueva sobre la estrategia)
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

**Conflicto de estrategia.** La preflight de la sesión registra `single-pr` con revisión de 400 líneas, que obligaría a exigir `size:exception`
antes de aplicar. `CLAUDE.md`, `docs/15` §3 y la práctica permanente del propietario fijan una cadena apilada con 800 líneas efectivas por PR. Este
documento registra la segunda, como hicieron S1 y S2. La propuesta (riesgo «cierto») y el diseño (§9.1) pidieron resolverlo en esta fase.

**Cuenta de tareas.** 23 tareas en 4 fases, por encima del umbral de 15 de la propuesta (riesgo R-10). Cada tarea es una unidad de revisión pequeña
(una por PR o par de PR) y el orden duro las deja en cadena lineal; la propuesta ofrecía partir el cambio en 9a (fases 1 y 2: infraestructura,
condición 4, atomicidad) y 9b (fases 3 y 4: manejadores, purgas, cadencias). **No se propone partir el cambio**: el punto de corte natural, si el
propietario lo pide, es después de la tarea 2.6 (fin de la rebanada 3).

**Regla de partición.** Si la medición de un PR supera 800 líneas efectivas, el ejecutor lo parte por archivos completos en la costura nombrada de su
tarea, sin consultar, y deja una nota fechada con la medida (como en S1 y S2). Solo consulta si una parte indivisible exige una excepción de tamaño o
si necesita una costura que no está nombrada. Dos tareas contiguas comparten PR solo si su medición conjunta es de 800 o menos.

### Pronóstico y costuras por rebanada (líneas efectivas, sin `openspec/`)

| PR (rebanada del diseño) | Tareas | Nominal (propuesta) | Realista | Costura nombrada si supera 800 |
|---|---|---|---|---|
| **PR 1** `01-scheduler-dependency-and-table` (1) | 1.1, 1.2 (condicional), 1.3 | 300 a 450 | 700 a 900 | **1a** `scheduler-dependency` (1.1 y 1.2: sondas, dependencia, exclusiones) / **1b** `scheduled-tasks-table` (1.3: `V10`, matriz, sonda de privilegios) |
| **PR 2a** `02a-neutral-log-guard` (2a) | 1.4 | 250 a 350 | 550 a 700 | Ninguna: la guardia sola; si pasa de 800, separar la reubicación del archivo (con sus pruebas movidas) de los prefijos nuevos |
| **PR 2b** `02b-worker-datasource-and-scheduler` (2b) | 1.5, 1.6 | 300 a 400 | 900 a 1 100 | **2b-1** `worker-datasource-and-condition-4` (1.5) / **2b-2** `worker-scheduler-shell` (1.6) |
| **PR 3a** `03a-transactional-scheduling` (3a) | 2.1, 2.2, 2.3, 2.4, 2.5 | 450 a 600 | 2 300 a 2 800 | Un PR por tarea, en orden: **3a-1** `jobs-port-and-serializer` (2.1) / **3a-2** `scheduler-client-and-atomicity` (2.2) / **3a-3** `one-time-execution` (2.3) / **3a-4** `recurring-execution` (2.4) / **3a-5** `scheduling-architecture-rules` (2.5); 2.4 y 2.5 pueden compartir PR si miden 800 o menos |
| **PR 3b** `03b-portal-scheduling-client` (3b) | 2.6 | 150 a 250 | 450 a 650 | Ninguna; D-N1 es una costura separable de 3a por diseño |
| **PR 4** `04-password-reset-issuance-job` (4) | 3.1, 3.2 | 450 a 650 | 900 a 1 300 | **4a** `reset-issuance-scheduler` (3.1: puerto y adaptador) / **4b** `reset-issuance-handler` (3.2: manejador, P4 y doble ejecución) |
| **PR 5a** `05a-identity-token-purges` (5a) | 3.3, 3.4, 3.5 | 550 a 800 | 1 300 a 1 700 | **5a-1** `v11-purge-functions` (3.3) / **5a-2** `identity-purge-jobs` (3.4) / **5a-3** `adr-0025-and-retention-notes` (3.5, se une a 5a-2 si caben) |
| **PR 5b** `05b-idempotency-and-backoff-purges` (5b) | 3.6, 3.7 | 400 a 650 | 900 a 1 200 | **5b-1** `v12-purge-functions` (3.6) / **5b-2** `idempotency-and-backoff-jobs` (3.7) |
| **PR 6** `06-audit-chain-verification-cadences` (6) | 3.8 | 250 a 400 | 550 a 750 | Ninguna; si pasa de 800, `verifySince` con su lector (unidad) / tareas recurrentes y asientos |
| **PR 7** `07-queue-observability-and-closing` (7) | 4.1, 4.2, 4.3 | 300 a 500 | 650 a 900 | **7a** `queue-observability` (4.1) / **7b** `closing-notes-and-criterion-5` (4.2 y 4.3, solo documentación y pruebas de inventario) |
| **Total** | 23 tareas | unas 4 200 | 9 500 a 11 500 | |

**Orden duro (diseño §9.1).**

1. **Rebanada 1 primero.** Nada se aplica sin el resultado de las sondas P-1 a P-3 (tarea 1.1).
2. **Rebanada 2 (guardia) antes de cualquier `DataSource` o SQL del trabajador (condición 4).** La tarea 1.4 (guardia neutral, PR 2a) se fusiona
   antes que la 1.5 (primer `DataSource` del trabajador, PR 2b). Ningún commit de `main` da un `DataSource` al trabajador sin guardia. La guardia
   nunca se revierte mientras exista en `main` un trabajador con `DataSource`.
3. **Rebanada 3 completa (3a y 3b) antes de las rebanadas 4, 5a, 5b y 6.**
4. **La tarea 3.3 (`V11`, funciones de purga de identidad, plantilla y ADR-0025 en 3.5) antes de la 3.6 (`V12`).**
5. R4 (tareas 3.1 y 3.2) antes del cambio S3 `password-recovery-endpoints`; la rebanada 7 cierra el cambio.
6. Migraciones `V10`, `V11` y `V12` en ese orden; `V1` a `V9` nunca se editan.

**Condiciones duras que se cierran** (la casilla de la tarea no se marca sin ellas):

| Condición | Tarea | Prueba de cierre |
|---|---|---|
| **Condición 4** (2026-10-04) | 1.4 y 1.5 | `WorkerSensitiveLoggingIT` en verde sobre el trabajador real con PostgreSQL, con su control negativo (`withoutTheGuardTheBoundValueLeaks`) y su demostración deliberada [BI89, BI90, BI91] |
| **Criterio de salida 5** | 2.2, 2.3 y 4.3 | `JobSchedulingAtomicityIT` y `OnlyWorkerExecutesIT` en verde [BJ01, BJ02, BJ06, BJ07]; `docs/09` marcado en 4.3 [BJ37] |

### Reglas comunes a todas las tareas

- **Comandos.** Todos desde `apps/api`. `./mvnw verify -Pmutation-gate` completo cierra cada PR. Las órdenes focalizadas son solo para iterar: un
  `-Dtest=` acotado informa `BUILD FAILURE` por JaCoCo aunque las pruebas pasen, y eso no es un fallo de la tarea. Forma unitaria:
  `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='A,B'`. Forma de integración:
  `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=X -Dfailsafe.failIfNoSpecifiedTests=false`.
- **Rojo registrado.** Cada paso en rojo corre contra el árbol sin el código de producción y se registra en
  `openspec/changes/background-jobs-with-db-scheduler/apply-progress.md` con la causa **observada**. Si falla por otra causa, se detiene y se
  investiga. Un commit en rojo no entra al historial: las pruebas en rojo se comprometen con su verde. Después del verde, un paso de
  **REFACTOR** deja la suite en verde antes de comprometer (nombres, duplicación, Javadoc), sin cambiar comportamiento.
- **Control negativo y demostración deliberada.** Cada tarea nombra una ruptura temporal de producción que debe poner en rojo una prueba concreta
  **por una aserción de resultado** (estado, fila, contador, valor registrado), nunca por tiempo de espera ni por carrera. Procedimiento fijo:
  (1) copiar el archivo de producción fuera del repositorio, en el directorio temporal de la sesión; (2) aplicar la ruptura y observar el
  rojo por la aserción nombrada; (3) restaurar el archivo desde la copia; (4) **confirmar con `cmp`** que el archivo restaurado es idéntico a la copia
  (`cmp` sin salida) y que `git diff` no muestra el archivo; (5) registrar en `apply-progress.md` la ruptura, la aserción que falló y el resultado
  de `cmp`. Una demostración no revertida y confirmada bloquea el cierre de la tarea.
- **Las pruebas de ataque y de borde se escriben antes que el código que defienden** (deserialización insegura, datos personales en `task_data`,
  purga de filas vigentes o de otra institución, purga sin contexto, valores enlazados en registros).
- **Pruebas de purga y reloj de la base de datos.** El piso de cada función usa `now()` de **PostgreSQL**, no el `Clock` de Java. Toda prueba de
  purga ancla las marcas de tiempo de sus filas **relativas al reloj de la base de datos**: lee `now()` una sola vez en la misma transacción que
  siembra y llama a la función (o siembra con `now() - interval '…'` en SQL), calcula el corte pasado como parámetro a partir de ese instante y
  prueba el borde exacto, un segundo antes y un segundo después. Prohibido sembrar con `Instant.now()` del `Clock` de Java y esperar un resultado
  exacto: una diferencia de milisegundos entre ambos relojes volvería frágil el borde.
- **Concurrencia determinista.** Sin esperas por reloj: `CyclicBarrier`, `CountDownLatch`, `MutableClock`; la segunda transacción se libera cuando
  `pg_stat_activity` muestra una espera de bloqueo (condición con límite, no espera fija). Latido y sondeo de 1 s en las pruebas del ejecutor.
- **Lista de permitidos.** Cada paquete nuevo del trabajador o del portal y su línea de `ProcessBeanPolicy` entran en el mismo PR que su primer
  bean; se demuestra primero el rojo (`ProcessBeanIsolationTest` falla con `not in the allow-list` nombrando bean y paquete) y luego el verde.
- **Migraciones.** `V1` a `V9` no se editan (se afirma con las sumas de verificación vigentes). Cada migración nueva regenera jOOQ en la misma
  construcción y entra con su prueba de catálogo en el mismo PR.
- **Registros y datos.** Nunca contraseña, token, `Authorization`, `Cookie`, correo, identificador de documento, datos de menores, `task_data` ni
  identificadores de instancia en logs ni bitácora. Los asientos de purga llevan solo conteos e instantes y se construyen con `AuditJson`.
- **Secretos.** Ningún secreto en el repositorio (regla 13); las pruebas generan en ejecución lo que necesiten. La contraseña de los roles
  de prueba sale de los fixtures existentes de Testcontainers.
- **Medición antes de abrir el PR.** `git diff --numstat main...HEAD` excluyendo `openspec/` y código generado, registrada en `apply-progress.md`.
  Si supera 800, se aplica la regla de partición.
- **Commits.** Conventional Commits en inglés, sin atribución de herramientas de IA, con pruebas y docs en el mismo commit que el comportamiento.
  Una rama por PR: `change/background-jobs-with-db-scheduler-NN-<nombre>` (la primera sale de `main` actualizado).
- **Idioma.** Código, identificadores y commits en inglés; documentación y notas fechadas en español neutro profesional.
- **Notas documentales.** Notas fechadas, sin reescribir cuerpos existentes.
- **Revisión de seguridad de cada PR.** Lista aplicable de `docs/03` marcada en la descripción del PR (entradas validadas en el borde, nada de
  datos de menores en registros, acciones sensibles auditadas, sin `DELETE` concedido, sin SQL concatenado).
- **Rutas bajo `apps/api/app/src/`** salvo indicación; los paquetes de las pruebas de integración se confirman en el rojo contra el árbol real.

### Suggested Work Units

| Unidad | Objetivo | PR probable | Comando de prueba enfocado | Arnés de ejecución | Frontera de reversión |
|---|---|---|---|---|---|
| 1.1 y 1.2 | Sondas P-1 a P-3, dependencia, exclusión total en administración y portal, alternativa B condicional | PR 1 / 1a | `-Dtest='StarterAutoConfigurationExcludedTest,ProcessBeanIsolationTest'` | `./mvnw validate` y arranque real de administración y portal | Se retiran la propiedad de versión, la dependencia y las exclusiones; ningún SQL nuevo |
| 1.3 | `V10`, permisos exactos, matriz de cinco roles, sonda P-6 | PR 1 / 1b | IT: `-Dit.test='ScheduledTasksSchemaIT,ScheduledTasksNotCreatedAtRuntimeIT,PortalSchedulingPrivilegeIT,RolePrivilegeMatrixIT'` | PostgreSQL real con los cinco roles | Se elimina la migración (antes de producción) y se regenera jOOQ |
| 1.4 | Guardia neutral instalada antes del primer bean en los tres procesos | PR 2a | `-Dtest='SensitiveLogGuardPrefixesTest'`; IT: `-Dit.test='SensitiveLogGuardInstallationTest'` | Los tres procesos reales con un `BeanFactoryPostProcessor` observador | Solo se revierte si no existe en `main` un trabajador con `DataSource` |
| 1.5 y 1.6 | `TransactionalDataSource`, trabajador con `DataSource`, `Scheduler` sin tareas, condición 4 | PR 2b | IT: `-Dit.test='WorkerSensitiveLoggingIT,SchedulerStarterCompatibilityTest,WorkerStaysAliveTest'` | Trabajador real con `ConfiaApplication.launch` y PostgreSQL | 1.6 se revierte antes que 1.5; nunca la guardia (1.4) |
| 2.1 a 2.5 | Puerto, serializador, cliente, ejecución única y recurrente, reglas de ArchUnit | PR 3a | `-Dtest='RetryPolicyTest,JobInstanceIdProperties,JobPayloadRulesTest,TracedJobSerializationTest'`; IT: `-Dit.test='JobSchedulingAtomicityIT,OnlyWorkerExecutesIT,FailedExecutionIsParkedIT'` | Administración y trabajador reales sobre la misma base | Se revierte en orden inverso; sin manejadores de negocio |
| 2.6 | Portal con `DataSource` y `JobScheduler` (D-N1) | PR 3b | IT: `-Dit.test='PortalSchedulingIT'`; `-Dtest='ProcessBeanIsolationTest'` | Portal real con `confia_portal_app` | Se revierte entero; ningún caso de uso del portal programa |
| 3.1 y 3.2 | Adaptador de emisión de recuperación y manejador, P4, doble ejecución | PR 4 | IT: `-Dit.test='RequestPasswordResetSchedulingIT,PasswordResetIssuanceDoubleExecutionIT,UnavailableSenderLeavesNoTokenIT'` | Solicitud real con el adaptador y trabajador real | 3.2 antes que 3.1; `RequestPasswordReset` vuelve a su doble |
| 3.3 a 3.5 | `V11`, purgas de recuperación y refresco, ADR-0025 | PR 5a | IT: `-Dit.test='RetentionPurgeFunctionsIT,RefreshTokenPurgeIT,IdentityTokenPurgeJobsIT'` | PostgreSQL real, dos instituciones, rol `confia_admin_app` | Las tareas recurrentes se retiran de forma independiente; `V11` se elimina antes de producción |
| 3.6 y 3.7 | `V12`, purgas de idempotencia y retroceso, corredor por institución | PR 5b | IT: `-Dit.test='RetentionPurgeFunctionsIT,IdempotencyKeyPrivilegeIT,RecurringJobPerInstitutionIT'` | Dos instituciones configuradas | Igual que 5a; `V12` posterior a `V11` |
| 3.8 | Cadencias diaria y semanal del verificador | PR 6 | `-Dtest='DefaultAuditChainVerifierTest'`; IT: `-Dit.test='AuditChainVerificationJobsIT'` | Fila alterada con el superusuario del contenedor | Se revierte el commit; `verifyChainOf` no cambia |
| 4.1 a 4.3 | Observabilidad de la cola, notas documentales, criterio 5 y cierre | PR 7 | `-Dtest='QueueHealthTest,LogQueueMetricsTest,QueueSqlNeverReadsTaskDataTest'`; IT: `-Dit.test='QueueStateReaderIT'` | Cola sembrada en cada estado | Se revierte el commit; solo lectura y documentación |

### Dependencias y paralelismo

| Tarea | Depende de | Paralelismo |
|---|---|---|
| 1.1, 1.2 | Ninguna (1.2 solo si falla P-1, P-2 o P-3) | Secuencial: raíz de la cadena |
| 1.3 | 1.1 | Puede redactarse en paralelo con 1.4 (archivos disjuntos); se fusiona en orden |
| 1.4 | 1.3 | Se fusiona antes que 1.5 (condición 4) |
| 1.5, 1.6 | 1.4 | Secuenciales entre sí |
| 2.1 a 2.5 | 1.6 | Secuenciales: comparten `shared.jobs` y `ProcessBeanPolicy` (un solo escritor) |
| 2.6 | 2.2 | Secuencial después de 2.5; toca `PortalApplication` y `ProcessBeanPolicy` |
| 3.1, 3.2 | 2.6 | Secuenciales |
| 3.3 a 3.5 | 2.6 | 3.3 puede redactarse en paralelo con 3.1 y 3.2 (archivos disjuntos), pero se fusiona después de 3.2 por el orden de la cadena |
| 3.6, 3.7 | 3.3 | Secuenciales después de 3.5 |
| 3.8 | 2.6 | Puede redactarse en paralelo con 3.3 a 3.7; se fusiona después de 3.7 |
| 4.1 | 2.6 | Secuencial después de 3.8 |
| 4.2, 4.3 | 4.1 y todas las anteriores | Cierran el cambio |

---

## Fase 1: dependencia, tabla, guardia y trabajador con base de datos

- [x] 1.1 **PR 1 / 1a `01-scheduler-dependency`: sondas P-1 a P-3, versión fijada, iniciador y exclusión total en administración y portal (decisiones 1, 2 y 4; riesgos R-1 y R-2).**
  - **Paso 0, riesgo R-2 (brecha del almacén de confianza).** La máquina de desarrollo tiene una brecha conocida del almacén de confianza de Windows
    (PKIX) que bloquea las descargas **nuevas** de Maven Central (`apps/api/pom.xml`, comentario sobre `archunit` 1.5.0: el POM se descargó, el jar no).
    Antes de escribir nada, intentar resolver `com.github.kagkarlsson:db-scheduler-spring-boot-4-starter` y su núcleo en un directorio de trabajo
    temporal fuera del repositorio. Si falla con un error PKIX, **detenerse e informar al propietario**: la corrección (importar la autoridad
    certificadora al almacén de confianza del JDK 25) es suya. Prohibido copiar jars al repositorio, deshabilitar la verificación TLS o editar un
    repositorio local a mano. Ninguna tarea avanza sin la dependencia.
  - **ROJO (sonda P-1, relectura de versión).** Leer la lista de versiones de Maven Central el día de fijarla (la última publicada; el diseño espera
    16.12.0 y `UPGRADING.md` menciona 16.13.x). Crear `test/java/com/confia/bootstrap/StarterAutoConfigurationExcludedTest.java` (lee
    `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` del classpath y afirma que **cada** entrada está excluida en
    `AdminApplication` y en `PortalApplication`; control negativo con una entrada inventada que debe fallar) [BJ48, BI102, BI114]; reemplazar en
    `test/java/com/confia/bootstrap/ProcessBeanIsolationTest.java` el caso `dbSchedulerAbsenceIsVacuousUntilChange9` por la afirmación de la mitad de
    administración y portal (cero beans `Scheduler` y `com.github.kagkarlsson`, ningún hilo `db-scheduler-*`; la mitad del trabajador llega en 1.6)
    [BJ06, BI93]; ampliar la prueba de enforcer para que `org.quartz-scheduler:*` y `org.jobrunr:*` sigan prohibidos con db-scheduler presente
    [BI61, BI100] y una prueba de la fuente única de la versión [BI59]. **Rojos esperados:** `ProcessBeanIsolationTest` falla con la dependencia
    agregada y sin exclusión porque el contexto administrativo arranca un ejecutor (el caso antiguo falla además porque la clase está en el
    classpath); `StarterAutoConfigurationExcludedTest` falla nombrando cada autoconfiguración sin excluir [BJ08]; `./mvnw validate` informa
    `dependencyConvergence` si P-3 falla.
  - **VERDE.** Editar `apps/api/pom.xml` (propiedad `db-scheduler.version` y entrada de `dependencyManagement`; `maven-enforcer-plugin` sin cambios de
    prohibición) y `apps/api/app/pom.xml` (dependencia `db-scheduler-spring-boot-4-starter` sin versión; exclusión de `scheduled_tasks` en la generación
    de jOOQ junto con `flyway_schema_history`). Editar `bootstrap/admin/AdminApplication.java` y `bootstrap/portal/PortalApplication.java`
    (`@EnableAutoConfiguration(exclude/excludeName = …)` con **todas** las clases de la sonda P-2; `excludeName` si alguna no es pública). **No** se
    usa `db-scheduler.enabled=false` (D2 *B*). `ProcessBeanPolicy.ADMIN` y `PORTAL` conservan `com.github.kagkarlsson` prohibido. Escenarios de
    arranque del trabajador con la biblioteca en el classpath [BI60]: el trabajador actual (sin `DataSource`) arranca igual.
  - **REFACTOR.** Dejar el resultado de P-1 a P-3 en una nota fechada de `design.md` (§2.1) con la versión real y las exclusiones reales; si difiere de
    lo esperado, ver 1.2.
  - **Demostraciones deliberadas.** (1) Quitar una de las clases de `exclude` de `AdminApplication` pone en rojo `StarterAutoConfigurationExcludedTest`
    nombrando la clase [BJ48, BI114]. (2) Retirar toda la exclusión de `PortalApplication` pone en rojo la mitad de administración y portal de
    `ProcessBeanIsolationTest` [BI102]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `./mvnw validate` y `./mvnw verify -Pmutation-gate` en verde. Commit: `build(deps): add the db-scheduler starter and exclude its
    auto-configuration in the admin and portal processes`. Tamaño: nominal 150, realista 350. — Escenarios BJ06, BJ08, BJ48, BI59 a BI61, BI93,
    BI100, BI102 y BI114.
  - **Resultado (2026-10-24): hecha.** Rojos, verde, demostraciones con `cmp` y `verify -Pmutation-gate` en verde (`BUILD SUCCESS`, 13 min 48 s) en
    `apply-progress.md`, «Tarea 1.1» y «Cierre de 01a». Commit `17bcae6`. Tamaño medido de 01a: 376 líneas efectivas.

- [x] 1.2 **[CONDICIONAL: solo si P-1, P-2 o P-3 fallan en 1.1] PR 1 / 1a `01-scheduler-dependency`: alternativa B, solo el núcleo (decisión 1).**
  - **Disparador.** El iniciador no arranca con Spring Boot 4.1.1, no pasa `dependencyConvergence` o el `Scheduler` propio no lo desplaza. En ese caso
    no se avanza a 1.3: se escribe una **nota fechada en `design.md`** con el resultado observado, se informa al propietario y se aplica esta tarea.
    Si 1.1 pasa las tres sondas, esta tarea se marca «no aplica» con la evidencia de las sondas y no se ejecuta.
  - **ROJO.** El caso de 1.1 que falló se conserva como rojo (convergencia, arranque o desplazamiento). Añadir una prueba que afirma que el trabajador
    excluye también la autoconfiguración del iniciador.
  - **VERDE.** `apps/api/app/pom.xml`: la dependencia pasa a `com.github.kagkarlsson:db-scheduler` (núcleo) con la misma propiedad de versión. Se
    excluye la autoconfiguración también en `WorkerApplication.java`. Dejar anotado que en 1.6 `WorkerSchedulerStarter` será un `SmartLifecycle`
    en vez de implementar la interfaz del iniciador, y que sus valores salen de un `@ConfigurationProperties` propio con los nombres `db-scheduler.*`
    (unas 30 líneas, ninguna otra decisión cambia). `StarterAutoConfigurationExcludedTest` se adapta (la lista de entradas sale del núcleo, que no
    declara autoconfiguración).
  - **Demostración deliberada.** Reintroducir el iniciador en el classpath pone en rojo la prueba de convergencia o la de exclusión del trabajador. Se
    revierte y se confirma con `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`. Commit: `build(deps): fall back to the db-scheduler core without the Spring Boot starter`. — Escenarios
    BI60 y BJ47 (reinterpretado para el núcleo; se registra la desviación).
  - **Resultado (2026-10-24): no aplica.** P-1 (16.12.0 resuelta), P-2 (el iniciador carga y arranca con Spring Boot 4.1.1) y P-3 (`dependencyConvergence` en
    verde) pasaron en 1.1; no se ejecutó nada de esta tarea. Evidencia en `apply-progress.md` y nota fechada en `design.md` §2.1.

- [x] 1.3 **PR 1 / 1b `01-scheduler-table`: `V10__create_scheduled_tasks.sql`, permisos exactos, matriz de cinco roles y sonda P-6 (decisión 15).**
  - **ROJO.** Crear `test/java/com/confia/schema/ScheduledTasksSchemaIT.java` (el catálogo de `scheduled_tasks` coincide con el DDL de PostgreSQL de la
    versión fijada, leído del jar de pruebas de la biblioteca o copiado en un recurso de prueba con su suma de verificación: columnas `task_name`,
    `task_instance`, `task_data`, `execution_time`, `picked`, `picked_by`, `last_success`, `last_failure`, `consecutive_failures`, `last_heartbeat`,
    `version`, `priority`; índices con prefijo de la tabla; sin RLS ni `institution_id`) [BI62, BI64]; `ScheduledTasksNotCreatedAtRuntimeIT.java`
    (arrancar el cliente con el rol `confia_admin_app`, que no tiene `CREATE`, no crea ni altera tablas; con la creación automática activa en un
    fixture, la prueba falla) [BI63]; `PortalSchedulingPrivilegeIT.java` (**sonda P-6**: el `SchedulerClient` real conectado como `confia_portal_app`
    programa con `scheduleIfNotExists` y con `schedule`; resultado registrado para decidir si `V10` concede `SELECT`; un `UPDATE` o `DELETE` sobre la
    tabla con ese rol falla) [BI66]. Ampliar `RolePrivilegeMatrixIT.java` con la matriz exacta de la tabla para los cinco roles: `confia_admin_app`
    `SELECT, INSERT, UPDATE, DELETE`; `confia_portal_app` `INSERT` (y `SELECT` solo si P-6 lo exige); `confia_readonly` nada; `confia_backup` solo por
    `pg_read_all_data`; `PUBLIC` nada [BI65, BI67], y una variante con un privilegio de más que debe fallar. `MultiTenantSchemaIT.java` pasa a
    comprobar que la tabla existe y se exceptúa del catálogo cerrado de ADR-0017, sin eximir nada más [BI99]; `AuditScopeExclusionInventoryTest.java`
    pasa de «ninguna referencia» a «`scheduled_tasks` aparece solo en `V10`» (con una migración de prueba que la nombra y debe fallar) [BI97].
    Re-ejecutar sin cambios la matriz vigente de `BI07` a `BI10` y la suma de verificación de `V1` a `V9`. **Rojos esperados:** tabla inexistente
    (`relation "scheduled_tasks" does not exist`); la afirmación de ausencia ya no se cumple.
  - **VERDE.** Crear `main/resources/db/migration/V10__create_scheduled_tasks.sql` con el DDL de la sonda P-8 (prefijo de tabla en los índices,
    comentarios que citan ADR-0016 y ADR-0017), `REVOKE ALL … FROM PUBLIC` y los `GRANT` de la decisión 15. El código de jOOQ se regenera en la
    construcción y **no** incluye `scheduled_tasks`. Si P-6 prueba que `scheduleIfNotExists` exige `SELECT`, añadirlo para `confia_portal_app` y dejar la
    consecuencia en una nota fechada (el grupo de preparación del portal no incluiría `backgroundJobs`, `docs/07` §9).
  - **Demostraciones deliberadas.** (1) Quitar `INSERT` de `confia_portal_app` en `V10` pone en rojo `PortalSchedulingPrivilegeIT`: el rol no puede
    programar [BI66]. (2) Agregar `DELETE` a `confia_portal_app` pone en rojo `RolePrivilegeMatrixIT` por la aserción de privilegio de más [BI65].
    Se revierten y se confirman con `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`. Commit: `feat(db): create the scheduled_tasks table with its role grants`. Tamaño: nominal 200,
    realista 450. — Escenarios BI62 a BI67, BI97, BI99 y los vigentes BI07 a BI10 sin cambios.
  - **Resultado (2026-10-24): hecha.** Rojos, sonda P-6 (difiere: `schedule` también exige `SELECT`; `V10` concede `SELECT, INSERT` al portal), demostraciones con `cmp` y
    `verify -Pmutation-gate` en verde (`BUILD SUCCESS`, 16 min 46 s) en `apply-progress.md`, «Tarea 1.3» y «Cierre de 01b». Tamaño medido de 01b: 936 líneas efectivas
    (por encima de 800: ver «Medición y partición»).

- [x] 1.4 **PR 2a `02a-neutral-log-guard`: guardia neutral de registros instalada antes del primer bean en los tres procesos (decisión 13).**
  - **ROJO.** Mover `test/java/com/confia/shared/web/request/SensitiveLogGuardPrefixesTest.java` a `test/java/com/confia/shared/logging/` y ampliar su
    tabla con `org.jooq`, `org.postgresql`, `com.zaxxer.hikari`, `org.springframework.jdbc`, `org.springframework.transaction` y
    `com.github.kagkarlsson`: `DEBUG` y `TRACE` bloqueados, `INFO` y superiores pasan, un prefijo parecido no cubierto pasa [BI88, BI110]. Crear
    `SensitiveLogGuardInstallationTest.java` (un `BeanFactoryPostProcessor` de prueba observa que el filtro ya está instalado **antes de crear el
    primer bean** en los tres procesos reales y que se retira en `ContextClosedEvent`) [BI109] y una prueba de que existe una sola lista de
    prefijos, compartida por los tres procesos [BI87]. `SensitiveDataLoggingTest.java` y `MalformedRequestLoggingTest.java` pasan al nuevo paquete sin
    cambiar su contenido. **Rojos esperados:** `com.confia.shared.logging` inexistente; prefijos ausentes; el filtro se instala al crearse su bean,
    tarde.
  - **VERDE.** Crear `main/java/com/confia/shared/logging/{SensitiveLogGuard,SensitiveLogGuardInitializer,package-info}.java` (`@NamedInterface` que
    nombra su consumidor real, ADR-0022); mover `SensitiveLogGuard` con su lista única `PROTECTED_PREFIXES`; borrar
    `shared/web/request/SensitiveLogGuard.java` y retirar su bean de `shared/web/edge/WebEdgeConfiguration.java`. El inicializador
    (`ApplicationContextInitializer`) corre después de `LoggingApplicationListener`, instala el filtro, lo registra como singleton para las pruebas y
    lo retira al cerrar. `bootstrap/ConfiaApplication.java` lo añade a los tres procesos; el arnés web de pruebas usa el mismo inicializador.
    `SpringModulithVerificationTest` verifica el `@NamedInterface` nuevo.
  - **REFACTOR.** Unificar la lectura de la lista de prefijos y retirar cualquier duplicado de constantes.
  - **Demostraciones deliberadas.** (1) Instalar el filtro desde un bean (no desde el inicializador) pone en rojo `SensitiveLogGuardInstallationTest`:
    el observador encuentra el filtro ausente [BI109]. (2) Quitar `org.postgresql` de `PROTECTED_PREFIXES` pone en rojo
    `SensitiveLogGuardPrefixesTest` en `DEBUG` [BI110]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`. Commit: `refactor(logging): move the sensitive log guard to a neutral package and install it before
    any bean`. Tamaño: nominal 300, realista 650. Este PR no da `DataSource` a ningún proceso. — Escenarios BI87, BI88, BI109 y BI110.
  - **Resultado (2026-10-24): hecha.** Rojos, demostraciones con `cmp`, `verify` (11 min 13 s) y `verify -Pmutation-gate` (14 min 59 s) en verde en `apply-progress.md`,
    «Tarea 1.4». Commit `9825ec6`. Tamaño medido: 364 líneas efectivas. No se empezó 1.5: el PR de 1.4 debe fusionarse antes.

- [x] 1.5 **PR 2b / 2b-1 `02b-worker-datasource-and-condition-4`: `TransactionalDataSource`, trabajador con `DataSource` y condición 4 sobre el proceso real (decisiones 5, 12 y 13).**
  - **Precondición dura.** 1.4 fusionado en `main`. Verificarlo con `git log` antes de empezar; sin la guardia no se escribe ninguna línea con
    `DataSource` para el trabajador.
  - **ROJO, control negativo primero.** Crear `test/java/com/confia/shared/logging/WorkerSensitiveLoggingIT.java`: trabajador arrancado con
    `ConfiaApplication.launch` y PostgreSQL real, con la raíz, `org.jooq`, `org.postgresql`, `com.zaxxer.hikari`, `org.springframework.jdbc`,
    `org.springframework.transaction` y `com.github.kagkarlsson` en `TRACE`; un valor enlazado distintivo (un `accountId` sembrado por la prueba, pasado por
    `set_config` y por un `SELECT` del `DSLContext` real) **no** aparece en ningún mensaje, argumento, MDC ni traza [BI89]; y
    `withoutTheGuardTheBoundValueLeaks` (sin la guardia el mismo valor aparece al menos en `org.postgresql`; cada prefijo que filtre se anota como
    «probado» y los demás como «preventivos», y el nivel se propaga a `java.util.logging` si hace falta) [BI90]. Crear la prueba de construcción que
    falla si un trabajador con `DataSource` no tiene la guardia [BI91] y la que afirma que un punto de entrada que excluye el `DataSource` rompe la
    construcción [BI115]. Crear `test/java/com/confia/shared/security/TransactionalDataSourceTest.java` (un solo proxy por proceso, nunca doble
    envoltura; `requireActiveTransaction` lanza `IllegalStateException` fuera de una transacción) [BJ46]. Ampliar `WorkerProductionWiringTest.java`
    (lista exacta de beans de `identity`; el trabajador arranca **sin** `confia.crypto.column-master-key` ni `confia.identity.argon2-pepper`) [BI92,
    BI111], `TestProcessArguments.java` (`forProcess("worker")` pasa la URL de base de datos inalcanzable, un UUID en `confia.jobs.institutions` y
    `confia.jobs.executor.autostart=false`) y `ProcessBeanPolicy.java` (el trabajador permite `shared.logging`, `shared.security`,
    `shared.infrastructure`, `shared.infrastructure.wiring`, `shared.audit` y prohíbe `com.confia.shared.crypto` con el motivo «the worker holds no
    column-encryption key»; **cada línea primero en rojo** con `not in the allow-list` [BI94]). **Rojos esperados:** el trabajador no tiene
    `DataSource`; clases inexistentes.
  - **VERDE.** Crear `main/java/com/confia/shared/security/TransactionalDataSource.java` y registrarlo en `TransactionRunnerConfiguration`;
    `shared/platform/infrastructure/SharedPlatformConfiguration.java` recibe el proxy de este bean en vez de crear uno. Crear
    `main/java/com/confia/shared/jobs/infrastructure/wiring/WorkerPlatformConfiguration.java` (`DSLContext` con `withExecuteLogging(false)` sobre el
    proxy, `Clock.systemUTC()`, `AuditLogWriter = JooqAuditLogWriter` sin el decorador de origen de petición, importa `TransactionRunnerConfiguration`).
    Editar `bootstrap/worker/WorkerApplication.java`: deja de excluir `DataSourceAutoConfiguration`, **conserva** las exclusiones de
    `SecurityAutoConfiguration` y `UserDetailsServiceAutoConfiguration`, importa la configuración nueva, y su **Javadoc** pasa a documentar el
    `DataSource`, la guardia previa, el usuario `confia_admin_app` (P7) y que el trabajador sigue sin borde web ni cadena de seguridad. Nota fechada
    en `docs/05` §6 (`SPRING_DATASOURCE_*` del trabajador y `CONFIA_JOBS_INSTITUTIONS`).
  - **Demostraciones deliberadas.** (1) Deshabilitar el inicializador en `ConfiaApplication` para el trabajador pone en rojo
    `WorkerSensitiveLoggingIT` en su afirmación principal: el valor distintivo aparece [BI89, BI91]. (2) Crear un segundo `TransactionAwareDataSourceProxy`
    en `WorkerPlatformConfiguration` pone en rojo `TransactionalDataSourceTest` [BJ46]. Se revierten y se confirman con `cmp`.
  - **Cierre (condición 4).** La casilla **no se marca** sin: (a) `WorkerSensitiveLoggingIT` en verde con su control negativo; (b) las dos
    demostraciones registradas; (c) ningún commit de `main` con `DataSource` del trabajador sin guardia (el orden de commits y fusiones queda en
    `apply-progress.md`). `./mvnw verify -Pmutation-gate`. Commits: `feat(worker): give the worker process a data source behind the sensitive log
    guard` y `docs(infra): note the worker data source variables`. Tamaño: nominal 200, realista 600. — Escenarios BI89 a BI92, BI94, BI111, BI115, BJ46
    y los vigentes de «El trabajador arranca sin servidor web y con su cableado de base de datos» y «Las pruebas de arranque no necesitan Docker».
  - **Resultado (2026-10-25): hecha; condición 4 cerrada.** `WorkerSensitiveLoggingIT` en verde con su control negativo (probado: `org.postgresql`), dos demostraciones con `cmp`,
    orden de commits y fusiones (guardia en `14e9922` antes de `2c4d733`), `verify` (8 min 8 s) y `verify -Pmutation-gate` (13 min 19 s) en verde: `apply-progress.md`, «Tarea 1.5».
    Commits `2c4d733` y `0b83744`. Tamaño: 764 líneas efectivas.

- [x] 1.6 **PR 2b / 2b-2 `02b-worker-scheduler-shell`: `Scheduler` único del trabajador sin tareas, instituciones configuradas y ciclo de vida (decisiones 1, 10 y 12).**
  - **ROJO.** Crear `test/java/com/confia/shared/jobs/infrastructure/ConfiguredInstitutionsStartupTest.java` (el trabajador no arranca sin
    `confia.jobs.institutions`, con un UUID inválido, con repetidos o vacío; el mensaje nombra la propiedad y la posición de la entrada inválida sin
    repetir la lista) [BJ31, BJ64]; `SchedulerStarterCompatibilityTest.java` (**sonda P-2**: el trabajador arranca con un `Scheduler` propio y el del
    iniciador se retira; el contexto contiene **un solo** `Scheduler`) [BJ47, BI95]; `WorkerHasNoMeterRegistryTest.java` (ningún proceso tiene
    `MeterRegistry`, P-3); `WorkerStaysAliveTest.java` (**sonda P-10**: la JVM sigue viva sin servidor web con `spring.main.keep-alive: true`) [BJ50];
    `JobsConfigurationGuardTest.java` (ningún `application*.yml` fija `confia.jobs.executor.autostart: false`; con `false` se escribe el `WARN`
    `jobs_executor_not_started` y no hay hilos) [BJ49]. En `ProcessBeanIsolationTest.java` completar el reemplazo de 1.1 por
    `onlyTheWorkerHoldsAScheduler` (exactamente uno en el trabajador, cero en los otros dos; la afirmación de observabilidad se estrecha a
    `shared.observability.metrics`) [BI95, BI96, BI113] con su control negativo (un ejecutor donde no corresponde y un trabajador sin ejecutor).
    `ProcessBeanPolicy.WORKER` añade `shared.jobs.infrastructure` y `shared.jobs.infrastructure.wiring`, primero en rojo. **Rojos esperados:** clases
    inexistentes; sin `Scheduler` en el trabajador.
  - **VERDE.** Crear `main/java/com/confia/shared/jobs/infrastructure/{WorkerSchedulerStarter,ConfiguredInstitutions}.java` y
    `wiring/WorkerJobsConfiguration.java` (`Scheduler` único construido con la API del núcleo desde un `List<JobDefinition>` vacío por ahora, el
    `DbSchedulerStarter` propio `WorkerSchedulerStarter` que arranca en `ApplicationReadyEvent` y se detiene al cerrar). Editar
    `main/resources/application.yml` con `db-scheduler.threads: 4`, `polling-interval: 10s`, `heartbeat-interval: 1m`, `missed-heartbeats-limit: 6`,
    `shutdown-max-wait: 30s`, `delay-startup-until-context-ready: true` y `spring.main.keep-alive: true` (inertes en administración y portal,
    documentado). Con la alternativa B de 1.2, `WorkerSchedulerStarter` es un `SmartLifecycle` y los valores salen de un `@ConfigurationProperties`
    propio. Si `Scheduler` exige al menos una tarea conocida, se registra una tarea inerte de la biblioteca solo en pruebas y se anota la desviación.
  - **Demostraciones deliberadas.** (1) Declarar un segundo `Scheduler` en `WorkerJobsConfiguration` pone en rojo
    `SchedulerStarterCompatibilityTest` y `ProcessBeanIsolationTest`: dos ejecutores [BJ47, BI96]. (2) Quitar `spring.main.keep-alive` pone en rojo
    `WorkerStaysAliveTest`: la JVM termina [BJ50]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`; se mide la duración de `verify` contra el presupuesto de 15 minutos (BI57, riesgo R-9). Commit:
    `feat(worker): add the single db-scheduler executor with no registered tasks`. Tamaño: nominal 150, realista 500. — Escenarios BJ31, BJ47, BJ49,
    BJ50, BJ64, BI95, BI96 y BI113 (afirmación estrechada), más la mitad del trabajador de BJ06.
- **Resultado (2026-10-25): hecha.** Rojos, dos demostraciones con `cmp`, `verify` (11 min 48 s) y `verify -Pmutation-gate` en verde: `apply-progress.md`, «Tarea 1.6». Commits `c6f64ae` y `c554b29`. Tamaño: 937 líneas efectivas sobre 2b-1.

---

## Fase 2: programación transaccional y ejecución

- [x] 2.1 **PR 3a-1 `03a-jobs-port-and-serializer`: puerto `shared.jobs`, envoltura `TracedJob`, serializador estricto y reglas de carga (decisiones 3, 6, 7, 8 y 9).**
  - **ROJO, ataques primero.** Crear en `test/java/com/confia/shared/jobs/`: `TracedJobSerializationTest.java` (ida y vuelta de una carga y su `meta`;
    campo desconocido rechazado; bytes `0xACED…` de `ObjectOutputStream` rechazados **sin instanciar nada**; más de 4 KiB rechazado al
    serializar; el mensaje de `JobDataRejectedException` nombra la tarea y la clase de la excepción original y **no contiene la carga ni la causa
    encadenada**) [BJ11, BJ53, BJ54, BJ55, BJ10]; `JobPayloadRulesTest.java` (cada tipo permitido —`UUID`, `Instant`, `LocalDate`, `boolean`, `int`,
    `long`, `enum` del mismo módulo— y cada prohibido —`String`, `BigDecimal`, `Money`, colecciones, otro `record`— con un caso por tipo) [BJ52];
    `JobInstanceIdProperties.java` (jqwik: 32 hexadecimales, determinista, sensible a cada parte y al nombre, sin datos personales) [BJ15, BJ56];
    `RetryPolicyTest.java` (de 1 a 5 intentos, esperas exactas 30 s, 60 s, 120 s, 240 s y tope de 5 min, agotamiento devuelve vacío, factor y tope
    límite) [BJ22]; `JobMetaTest.java` (campos obligatorios; `actorId` y `traceparent` pueden ser nulos). **Rojos esperados:** paquete
    `com.confia.shared.jobs` inexistente.
  - **VERDE.** Crear `main/java/com/confia/shared/jobs/{JobType,RetryPolicy,JobMeta,ActorKind,TracedJob,JobInstanceId,JobScheduler,ScheduleOutcome,
    JobHandler,JobContext,RecurringJob,RecurringJobHandler,RecurringRun,JobCalendar,OneTimeJob,JobDefinition,JobPayloadRules,package-info}.java`
    (solo JDK, `kernel` y `shared.security`; `@NamedInterface` con consumidores `identity` y `shared.audit`; nombre de tipo
    `[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+`; identificador `SHA-256("v1|" + nombre + "|" + partes)` truncado a 32 hexadecimales con `Digests`). Crear
    `main/java/com/confia/shared/jobs/infrastructure/{TracedJobSerializer,JobDataRejectedException}.java` (envuelve `Jackson3Serializer` según P-4
    con un `JsonMapper` estricto: `FAIL_ON_UNKNOWN_PROPERTIES`, `FAIL_ON_NULL_FOR_PRIMITIVES`, sin tipado polimórfico, sin `INCLUDE_SOURCE_IN_LOCATION`,
    fechas ISO-8601; solo acepta `TracedJob.class` y solo un objeto JSON; si P-4 falla, envoltura propia de unas 40 líneas con la misma prueba).
    Editar `apps/api/app/pom.xml` (PIT: `com.confia.shared.jobs.*` en `targetClasses` y `targetTests`). Nota fechada en `docs/07-observabilidad-y-operaciones.md`
    §3.3 sobre la ubicación de `TracedJob` en `shared.jobs`. `ProcessBeanPolicy` no cambia (estos tipos no son beans).
  - **Demostraciones deliberadas.** (1) Quitar `FAIL_ON_UNKNOWN_PROPERTIES` pone en rojo `TracedJobSerializationTest` en el caso del campo desconocido:
    se acepta en vez de rechazarse [BJ54]. (2) Permitir `String` en `JobPayloadRules` pone en rojo `JobPayloadRulesTest` [BJ52]. (3) Cambiar el prefijo
    `"v1|"` por `"v2|"` en `JobInstanceId.derive` no cambia la forma pero rompe el vector de prueba fijo. Se revierten y se confirman con `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`; PIT sobre `shared.jobs.*` en al menos 80. Commits: `feat(jobs): add the job scheduling port, the traced
    job envelope and rules for payload types` y `feat(jobs): add the strict task data serializer`. Tamaño: nominal 150, realista 550. — Escenarios
    BJ10, BJ11, BJ15, BJ22, BJ52 a BJ56.
  - **Resultado (2026-10-26): hecha, en tres PR.** Ramas `03a1a` (579 líneas), `03a1b` (639) y `03a1c` (544); ver `apply-progress.md`, «Rebanada 3a, tarea 2.1». Costura 03a1a/03a1b no nombrada.

- [x] 2.2 **PR 3a-2 `03a-scheduler-client-and-atomicity`: cliente transaccional en administración, `scheduleIfNotExists` y criterio 5 (decisiones 5, 6 y 14).**
  - **ROJO, control negativo primero.** Crear `test/java/com/confia/shared/jobs/JobSchedulingAtomicityIT.java` (transacción confirmada: la fila existe con
    el JSON esperado [BJ01]; revertida: no existe ninguna fila [BJ02]; la fila no es visible desde otra conexión antes de la confirmación [BJ03];
    **sonda P-7**: con `commitWhenAutocommitDisabled` en su valor por omisión el cliente no confirma por su cuenta; **control negativo**: un cliente
    construido sobre el `DataSource` crudo deja la fila tras revertir y la prueba debe detectarlo) [BJ05], `JobSchedulingRetriedTransactionIT.java`
    (40001 y 40P01 reintentan: queda una sola fila, el intento anterior se revirtió) [BJ04], `JobSchedulingGuardsTest.java` (programar fuera de una
    transacción lanza `IllegalStateException` [BJ43]; programar una instancia ya programada devuelve `ALREADY_SCHEDULED` y **no aborta** la
    transacción del negocio [BJ44]; `meta.institutionId` distinto del `app.institution_id` de la transacción se rechaza [BJ45]; mismo identificador de
    instancia dos veces deja una fila [BJ14]). Ampliar `ProcessBeanPolicy` (administración permite `shared.jobs.infrastructure` y
    `shared.jobs.infrastructure.wiring`, primero en rojo). **Rojos esperados:** `JobScheduler` sin implementación; sin adaptador.
  - **VERDE.** Crear `main/java/com/confia/shared/jobs/infrastructure/DbSchedulerJobScheduler.java` (orden de la decisión 6: `requireActiveTransaction`,
    `JobPayloadRules.validate`, comprobación de `meta`, serialización con el límite, `scheduleIfNotExists`; construye el `SchedulerClient` **dentro del
    adaptador**, sin exponerlo como bean, sobre el proxy de `TransactionalDataSource`, con la opción de confirmación fijada de forma explícita si P-7
    lo exige) y `wiring/JobSchedulingConfiguration.java` (solo el bean `JobScheduler`). Importarla desde `AdminApplication.java` y registrar el
    serializador. Ningún caso de uso programa todavía; la ejecución llega en 2.3.
  - **Demostraciones deliberadas.** (1) Construir el cliente sobre el `DataSource` crudo en vez del proxy pone en rojo `JobSchedulingAtomicityIT`: la fila
    persiste tras el `ROLLBACK` [BJ02, BJ05]. (2) Reemplazar `scheduleIfNotExists` por `schedule` pone en rojo `JobSchedulingGuardsTest`: la segunda
    llamada aborta la transacción [BJ44]. Se revierten y se confirman con `cmp`.
  - **Cierre (criterio 5, parte de atomicidad).** `./mvnw verify -Pmutation-gate`. Commit: `feat(jobs): schedule tasks inside the business transaction
    through a transaction-aware client`. Tamaño: nominal 150, realista 600. — Escenarios BJ01 a BJ05, BJ14, BJ43 a BJ46.
  - **Resultado (2026-10-26): hecha.** Rojos, dos demostraciones con `cmp`, `verify` (959 s) y `verify -Pmutation-gate` (1 245 s) en verde: `apply-progress.md`, «Tarea 2.2». Commit `d062346`. Tamaño: 793 líneas efectivas.

- [x] 2.3 **PR 3a-3 `03a-one-time-execution`: ejecución única, reintentos acotados, estacionamiento y saneo de fallos (decisiones 7, 9 y 10).**
  - **ROJO.** Crear `test/java/com/confia/shared/jobs/` (con un tipo de tarea de prueba registrado solo en el arnés del trabajador):
    `OnlyWorkerExecutesIT.java` (administración y trabajador sobre la misma base: la tarea programada desde administración la toma el trabajador, con
    `picked_by`; con solo administración nunca se toma durante N sondeos de 1 s) [BJ07, BJ06]; `FailedExecutionIsParkedIT.java` (un tipo que siempre
    falla queda con `consecutive_failures` igual al máximo, `last_failure` y `execution_time = PARKED_AT`, **nunca borrado**; un `WARN` `job_failed`
    por intento con `task`, `attempt` y `outcome`; un fallo transitorio se reintenta y termina en éxito) [BJ21, BJ23, BJ58]; con **control negativo
    contra `MaxRetriesFailureHandler`** de la biblioteca, que sí borra la ejecución [BJ59]; `JobFailureSanitizationIT.java` (un manejador que lanza con
    un valor distintivo en el mensaje y una `SQLException` en la causa no deja ese valor en ningún evento, incluido el `ERROR` de la biblioteca;
    queda el nombre de la tarea, la clase original y el `SQLState`) [BJ60]; `JobContextIT.java` (el manejador corre como `system` en la institución
    de la tarea, con MDC `jobTask`, `jobInstance`, `correlationId`, `requestId`, `institutionId` y `actorKind=system`; una tarea sin institución o de una
    institución inexistente no produce efecto; un manejador solo ve datos de su institución) [BJ16, BJ17, BJ18, BJ57]; `DeadExecutionRevivalIT.java`
    (latido de 1 s: una ejecución muerta se revive en menos de 900 s y una ejecución larga y sana no se revive) [BJ25, BJ26]; `HandlerIdempotenceIT.java`
    (la doble ejecución de cada tipo registrado deja un solo efecto; un manejador no idempotente de fixture hace fallar la prueba) [BJ19, BJ20].
    **Rojos esperados:** sin ejecutor de tareas únicas; la biblioteca borra o reintenta sin fin; el valor distintivo aparece.
  - **VERDE.** Crear `main/java/com/confia/shared/jobs/infrastructure/{JobRunner,JobExecutionFailedException,BoundedRetryFailureHandler,
    ParkedExecutions}.java` (`PARKED_AT = 9999-01-01T00:00:00Z`; reprograma a `now + delay` o estaciona con `reschedule(complete, PARKED_AT)`; MDC
    limpiado en `finally`; saneo que relanza `JobExecutionFailedException` con tarea, clase original y `SQLState`, sin causa encadenada). Editar
    `wiring/WorkerJobsConfiguration.java` para construir una tarea única por `OneTimeJob` con datos `TracedJob`, fallar al arrancar con nombres
    repetidos [BJ63] y usar el serializador único.
  - **Demostraciones deliberadas.** (1) Reemplazar `BoundedRetryFailureHandler` por `MaxRetriesFailureHandler` pone en rojo `FailedExecutionIsParkedIT`:
    la fila ya no existe [BJ23, BJ59]. (2) Encadenar la causa en `JobExecutionFailedException` pone en rojo `JobFailureSanitizationIT`: el valor distintivo
    aparece [BJ60]. (3) Hacer que `JobRunner` use `actorKind=staff` pone en rojo `JobContextIT` [BJ16]. Se revierten y se confirman con `cmp`.
  - **Cierre (criterio 5, parte de ejecución).** `./mvnw verify -Pmutation-gate`; se anota la duración de `verify`. Commit: `feat(jobs): run one-time tasks in
    the worker with bounded retries, parked failures and sanitized errors`. Tamaño: nominal 150, realista 800 a 900; costura nombrada: separar
    `DeadExecutionRevivalIT` y `HandlerIdempotenceIT` en un PR 3a-3b si se pasa. — Escenarios BJ06, BJ07, BJ16 a BJ21, BJ23, BJ25, BJ26, BJ57 a BJ60, BJ63.
  - **Resultado (2026-10-26): hecha, en cuatro PR.** 03a3a (523 líneas), 03a3b (668) y 03a3c (costura nombrada); rupturas con `cmp`, `verify` y `verify -Pmutation-gate` en verde en cada una: `apply-progress.md`, «Tarea 2.3».

- [ ] 2.4 **PR 3a-4 `03a-recurring-execution`: tareas recurrentes por institución configurada, calendario y réplicas (decisiones 7, 10 y 11).**
  - **ROJO.** Crear `test/java/com/confia/shared/jobs/RecurringJobCalendarTest.java` (**sonda P-5**: cada `JobCalendar` se traduce a
    `Schedules.cron(…, America/Tegucigalpa)` o `fixedDelay`; próxima ejecución exacta desde un instante fijo, con una JVM cuya zona por defecto es
    otra; ninguna alcanza `PARKED_AT`, riesgo R-13) [BJ27, BJ62], `RecurringJobPerInstitutionIT.java` (dos instituciones configuradas: el ciclo corre
    una vez por cada una [BJ30]; una que falla no impide a la otra y la ocurrencia queda fallida con `RecurringJobFailedException` y un `WARN`
    `job_institution_failed` [BJ65]; `runId` como `correlationId` y `requestId` [BJ66]), `RecurringJobSingleRunIT.java` (dos `Scheduler` sobre la
    misma base: cada ocurrencia corre una sola vez [BJ28]; reiniciar no duplica el ciclo [BJ29]) y `RecurringFailureStateIT.java` (una recurrente
    fallida no se reintenta y queda visible hasta su próximo éxito; un éxito reinicia `consecutive_failures`) [BJ24, BJ61]. **Rojos esperados:**
    sin traductor de calendario; sin corredor recurrente.
  - **VERDE.** Crear `main/java/com/confia/shared/jobs/infrastructure/{RecurringJobRunner,RecurringJobFailedException,JobCalendars}.java` (zona fija
    `JobCalendars.ZONE`, sin parámetro) y ampliar `WorkerJobsConfiguration.java` con una tarea recurrente sin datos por `RecurringJob`. Las tareas de
    negocio del catálogo (decisión 11) llegan con sus rebanadas.
  - **Demostraciones deliberadas.** (1) Usar `ZoneId.systemDefault()` en `JobCalendars` pone en rojo `RecurringJobCalendarTest` con la JVM en otra zona
    [BJ27]. (2) Detener el recorrido al primer fallo en `RecurringJobRunner` pone en rojo `RecurringJobPerInstitutionIT`: la segunda institución no
    corrió [BJ65]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`. Commit: `feat(jobs): run recurring tasks per configured institution in the Tegucigalpa time zone`.
    Tamaño: nominal 100, realista 500. — Escenarios BJ24, BJ27 a BJ30, BJ61, BJ62, BJ65 y BJ66.

- [ ] 2.5 **PR 3a-5 `03a-scheduling-architecture-rules`: reglas de ArchUnit, lista aprobada de campos e inversiones de inventario (decisión 21).**
  - **ROJO, un fixture por regla.** Crear en `test/java/com/confia/architecture/`: `NoInMemorySchedulingTest.java` (ninguna clase de producción usa
    `@Scheduled`, `@Schedules`, `@EnableScheduling`, `@Async`, `@EnableAsync`, `org.springframework.scheduling..`, `java.util.Timer` ni
    `ScheduledExecutorService`; evalúa clases reales y no admite conjunto vacío; una clase por anotación y por tipo en
    `fixture/scheduling/`) [BI85, BI86], `DbSchedulerConfinementTest.java` (`com.github.kagkarlsson..` solo desde `com.confia.shared.jobs.infrastructure..`;
    fixture en `fixture/jobs/identity/`) [BI100], `JobPayloadTypesRuleTest.java` (toda clase de carga de un `JobType` registrado cumple
    `JobPayloadRules`; fixture con `String email`) y `test/java/com/confia/shared/jobs/JobPayloadAllowListTest.java` (serializa una instancia de cada
    `JobType` del contexto real del trabajador y compara el conjunto exacto de claves JSON a todo nivel con la lista escrita en la prueba; un campo
    nuevo sin entrada la rompe) [BJ12, BJ13]. Actualizar `IdentityScopeExclusionInventoryTest.java` (la ausencia de rotación de DEK se conserva sobre
    los `JobDefinition` registrados, con motivo `data-encryption-key-batch-rotation`) [BI98] y `NoUnapprovedPlainSqlTest.java` (sin cambios hasta 4.1,
    se re-ejecuta con su fixture). **Rojos esperados:** reglas inexistentes; fixtures no rechazados.
  - **VERDE.** Solo código de prueba y fixtures permanentes más los ajustes de `ProcessBeanPolicy` si alguna regla los exige; no hay producción nueva.
    Cualquier violación real hallada por las reglas se corrige en este PR.
  - **Demostraciones deliberadas.** (1) Añadir `@Scheduled` a una clase de producción pone en rojo `NoInMemorySchedulingTest` señalando la clase [BI85].
    (2) Importar `com.github.kagkarlsson` desde `identity.application` pone en rojo `DbSchedulerConfinementTest` [BI100]. Se revierten y se confirman con
    `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`. Commit: `test(architecture): forbid in-memory scheduling and confine the db-scheduler types to shared jobs`.
    Tamaño: nominal 50, realista 350. — Escenarios BI85, BI86, BI98, BI100, BJ12 y BJ13.

- [ ] 2.6 **PR 3b `03b-portal-scheduling-client`: el portal recibe su primer `DataSource` y el cliente de programación (decisión 14, D-N1, P6).**
  - **ROJO.** Crear `test/java/com/confia/bootstrap/PortalSchedulingIT.java` (portal real con `confia_portal_app`: programa una tarea por el bean
    `JobScheduler` real dentro de una transacción de `TransactionRunner`, con la fila presente; `UPDATE` y `DELETE` sobre la tabla con ese rol fallan)
    [BJ09, BI66]; `PortalContextStartupTest.java` (el contexto del portal se construye con la base de datos inalcanzable y contiene **solo**
    `TransactionRunner`, `TransactionalDataSource` y `JobScheduler`: ningún `DSLContext`, repositorio, caso de uso ni bean de `identity` u
    `organization`) [BJ51, BI112, BI116]. Ampliar `ProcessBeanPolicy.PORTAL` (permite exactos `shared.logging`, `shared.security`,
    `shared.jobs.infrastructure` y `shared.jobs.infrastructure.wiring`; conserva todas sus prohibiciones; cada línea primero en rojo) y
    `ProcessBeanIsolationTest` ("El portal no hereda el cableado administrativo pero sí tiene su cableado de programación"; "El trabajador y el
    portal tienen `DataSource`, y solo el trabajador tiene `DSLContext`" — escenario modificado). **Rojos esperados:** `DataSource` ausente;
    `not in the allow-list`.
  - **VERDE.** Editar `bootstrap/portal/PortalApplication.java`: deja de excluir `DataSourceAutoConfiguration` y registra solo
    `TransactionRunnerConfiguration` y `JobSchedulingConfiguration`. Aplicar el `GRANT` decidido en 1.3 (sin `V` nueva si ya cubre el caso). Notas
    fechadas en `docs/05-infraestructura-y-despliegue.md` §6 que **revierten la nota del 2026-10-04** («`portal` y `worker` no abren conexión») y
    agregan `SPRING_DATASOURCE_*` del portal con `confia_portal_app` [BJ71], y en `docs/07` §9 (el grupo de preparación del portal incluye
    `backgroundJobs` solo si el portal obtuvo `SELECT`).
  - **Demostraciones deliberadas.** (1) Importar `SharedPlatformConfiguration` en el portal pone en rojo `PortalContextStartupTest` y
    `ProcessBeanIsolationTest` [BI112]. (2) Conceder `UPDATE` a `confia_portal_app` pone en rojo `PortalSchedulingIT` y `RolePrivilegeMatrixIT`. Se
    revierten y se confirman con `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`; fin de la rebanada 3: la 3 completa precede a las rebanadas 4, 5a, 5b y 6. Commit:
    `feat(portal): give the portal process a data source and the job scheduling client`. Tamaño: nominal 150, realista 450 a 650. — Escenarios BJ09,
    BJ51, BJ71, BI66, BI112, BI116 y los escenarios modificados de aislamiento del portal.

---

## Fase 3: manejadores, purgas y cadencias

- [ ] 3.1 **PR 4 / 4a `04-reset-issuance-scheduler`: puerto ampliado y adaptador de programación de la emisión de recuperación (decisión 18, deuda *a*, P4).**
  - **ROJO.** Ampliar `RequestPasswordResetTest.java` (el caso de uso pasa el `auditRequestId` que ya calcula como tercer argumento del puerto) y crear
    `JobPasswordResetIssuanceSchedulerTest.java` (deriva `JobInstanceId.derive(TYPE.name(), institution, account, requestId)`; la meta exacta:
    `correlationId = requestId`, `actorKind = STAFF`, `actorId = null`, `traceparent = null`; la carga es solo `accountId`) [I229]; crear
    `RequestPasswordResetSchedulingIT.java` (con el adaptador real la solicitud programa **dentro de su transacción**; una solicitud revertida o para
    un identificador sin cuenta no programa nada; con un escritor de auditoría que falla no queda ni asiento ni tarea; la misma solicitud no
    programa dos tareas) [I229, I230, I231]. **Rojos esperados:** firma antigua `schedule(InstitutionId, StaffAccountId)`; tipo de tarea inexistente.
  - **VERDE.** Editar `identity/application/{PasswordResetIssuanceScheduler,RequestPasswordReset}.java` (`schedule(InstitutionId, StaffAccountId, UUID
    requestId)`); crear `identity/application/{PasswordResetIssuanceJob,PasswordResetIssuanceJobs}.java` (`TYPE = new JobType<>
    ("identity.password-reset-issuance", PasswordResetIssuanceJob.class, new RetryPolicy(5, 30 s, 2.0, 5 min))`) y
    `identity/infrastructure/JobPasswordResetIssuanceScheduler.java`; registrar el adaptador en `identity/infrastructure/wiring/IdentityConfiguration.java`
    de administración (`RequestPasswordReset` sigue sin bean: lo registra S3). Actualizar las pruebas existentes del puerto. `ProcessBeanPolicy` no
    cambia para administración.
  - **Demostraciones deliberadas.** (1) Programar fuera de la transacción del caso de uso (en un `afterCommit`) pone en rojo
    `RequestPasswordResetSchedulingIT`: la tarea queda cuando el escritor de auditoría falla [I230]. (2) Derivar el identificador sin el `requestId`
    pone en rojo `JobPasswordResetIssuanceSchedulerTest` [I231]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`; `identity.domain` en 95 y PIT en 80. Commit: `feat(identity): schedule the password reset issuance as a
    transactional job`. Tamaño: nominal 150, realista 400. — Escenarios I229 a I231.

- [ ] 3.2 **PR 4 / 4b `04-reset-issuance-handler`: manejador, emisor que falla de forma explícita y doble ejecución (decisión 18, P4).**
  - **ROJO, ataques primero.** Crear `PasswordResetIssuanceInstitutionIsolationIT.java` (la tarea de la institución A con una cuenta de la B termina
    `ACCOUNT_NOT_FOUND`, sin token ni asiento en B) [I268, I233], `PasswordResetIssuanceDoubleExecutionIT.java` (dos ejecuciones con los mismos datos
    y un emisor que registra dejan **un** token abierto y el último enviado es el único válido; la cuarta emisión dentro de la hora se omite sin
    fallar la tarea) [I234, I235, BJ19], `UnavailableSenderLeavesNoTokenIT.java` (con el emisor real del trabajador, cinco intentos fallidos, **ningún
    token y ningún asiento `issued`**, tarea estacionada; `ensureAvailable()` se llama antes de abrir la transacción en cada intento; el fallo no
    revela token, enlace ni correo) [I265, I266, I267, I269, I277] y `PasswordResetIssuanceHandlerIT.java` (el token existe solo cuando la tarea se
    ejecuta; cuenta inexistente o inactiva sin token) [I232, I236]. Ampliar `JobPayloadAllowListTest` con la entrada exacta de
    `identity.password-reset-issuance` (`accountId`; `meta`) [I237, BJ13] y `IdentityScopeExclusionInventoryTest` ("El puerto de envío solo tiene el emisor
    que falla de forma explícita"; `PasswordResetIssuanceScheduler` tiene exactamente una implementación; "Un restablecimiento completado no notifica a
    nadie") [BI98]. `ProcessBeanPolicy.WORKER` añade `identity.application`, `identity.infrastructure` e `identity.infrastructure.wiring`, primero en
    rojo; `WorkerProductionWiringTest` afirma la lista exacta de beans. **Rojos esperados:** manejador inexistente; el token huérfano se emite con un
    emisor que falla en `send`.
  - **VERDE.** Crear `identity/application/{PasswordResetIssuanceJobHandler,PasswordResetDeliveryUnavailableException}.java`; editar
    `PasswordResetLinkSender` (`default void ensureAvailable() { }`) e `IssuePasswordResetToken` (llama a `ensureAvailable()` **antes** de abrir la
    transacción); crear `identity/infrastructure/UnavailablePasswordResetLinkSender.java` (lanza en `ensureAvailable` y en `send`) y
    `identity/infrastructure/wiring/IdentityWorkerConfiguration.java` (`JooqStaffAccountRepository`, `JooqPasswordResetTokenRepository`,
    `UnavailablePasswordResetLinkSender`, `SecureRandom`, `IssuePasswordResetToken`, el manejador y su `OneTimeJob`; sin llave maestra, sin pimienta, sin
    `RequestOrigin`). El manejador llama a `IssuePasswordResetToken.execute(context.systemContext(), new StaffAccountId(payload.accountId()))`.
  - **Demostraciones deliberadas.** (1) Mover `ensureAvailable()` después de la transacción pone en rojo `UnavailableSenderLeavesNoTokenIT`: queda un token
    emitido y no entregado por intento [I277, I266]. (2) Quitar `supersedeOpen` pone en rojo `PasswordResetIssuanceDoubleExecutionIT`: dos tokens
    abiertos [I234]. (3) Ejecutar el manejador con el contexto de otra institución pone en rojo el aislamiento [I268]. Se revierten y se confirman con
    `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`; `identity.domain` en 95 y PIT en 80. Commit: `feat(identity): issue the password reset token from a worker job with
    a sender that fails explicitly`. Tamaño: nominal 300, realista 700 a 900; costura nombrada: la doble ejecución y el aislamiento por institución van a
    un PR 4c si se pasa. — Escenarios I232 a I237, I265 a I269, I277, BJ13, BJ19, BI98 y los escenarios del emisor y del restablecimiento completado.

- [ ] 3.3 **PR 5a / 5a-1 `05a-v11-purge-functions`: `V11` con las funciones de purga de recuperación y de refresco, y su matriz (decisión 16, P2, P3).**
  - **ROJO.** Crear `test/java/com/confia/schema/RetentionPurgeFunctionsIT.java` por función
    (`identity_purge_password_reset_tokens` e `identity_purge_refresh_token_families`) con las **reglas de reloj de la base de datos** de este
    documento: una fila en el corte exacto se conserva y un segundo después se elimina [I239, I245, BI73]; un corte en el futuro no elimina una fila
    más reciente que el piso (24 h, 30 días) [I241, I271, BI74, BI104]; sin `app.institution_id` la función falla con `confia_purge_context_missing`
    y no borra nada [I243, I251, BI76]; con actor `staff` falla con `confia_purge_actor_not_system` [I270, BI105]; otra institución queda intacta
    [I242, I250, BI75]; el lote respeta `p_limit` y se acota a 10 000 [BI106]; la rutina generada por jOOQ funciona (**sonda P-12**); una variante sin
    corte hace fallar la prueba (control negativo) [BI77]. Para refresco: familia revocada hace 29 días intacta y hace 31 purgada [I244, I245];
    vencida por `absolute_expires_at` sin revocar se purga [I246]; viva o dentro de la ventana no pierde ningún token [I247]; tokens antes que su
    familia sin violar claves foráneas, con sucesor encadenado (clave diferida) [I248]; un fallo a mitad no deja una familia a medias [I249]; una
    familia con más tokens que el lote se elimina completa [I273, BI107]; la inactividad no la vuelve purgable [I272]. Ampliar
    `RolePrivilegeMatrixIT.java` (`EXECUTE` exacto: `confia_admin_app` y nadie más; propietario `confia_owner`; `prosecdef`; `proconfig` con
    `search_path = pg_catalog, public`; ningún `DELETE` sobre las tablas) [BI68, BI69, BI72, BI83, BI84] y una prueba de `search_path` hostil del
    llamador que no desvía la función [BI70] y de que el llamador solo mueve el corte hacia atrás [BI71]. Crear `RefreshPurgeVersusRotationConcurrencyIT.java`
    (barreras y `pg_stat_activity`; sin interbloqueo; la purga ordena por `id`). **Rojos esperados:** funciones inexistentes.
  - **VERDE.** Crear `main/resources/db/migration/V11__create_identity_token_purge_functions.sql` con la plantilla de la decisión 16 (`SECURITY DEFINER`,
    `SET search_path`, `LEAST(p_cutoff, now() - piso)`, contexto y actor `system` obligatorios, lote acotado, `REVOKE … FROM PUBLIC`, `GRANT EXECUTE`
    solo a `confia_admin_app`) para las dos funciones; la de refresco con `OUT purged_families bigint, OUT purged_tokens bigint`. **No se concede
    `DELETE` a ningún rol.** `V1` a `V9` y `V10` no se editan (se afirma por suma de verificación).
  - **Demostraciones deliberadas.** (1) Quitar el `LEAST` del piso pone en rojo `RetentionPurgeFunctionsIT` en el caso del corte en el futuro: la fila
    reciente se elimina [BI104, I271]. (2) Quitar la comprobación de contexto pone en rojo el caso sin institución: cero filas y sin error en vez de
    la excepción [I243, BI76]. (3) Conceder `EXECUTE` a `PUBLIC` pone en rojo la matriz [BI84]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`. Commit: `feat(db): add the security definer purge functions for password reset and refresh tokens`.
    Tamaño: nominal 250, realista 750 a 850; costura nombrada: la función de refresco con `RefreshPurgeVersusRotationConcurrencyIT` va a 5a-1b si se
    pasa. — Escenarios I238 a I251, I270 a I273, BI68 a BI77, BI83, BI84, BI104 a BI107.

- [ ] 3.4 **PR 5a / 5a-2 `05a-identity-purge-jobs`: casos de uso de purga de identidad, lotes, auditoría y tareas recurrentes (decisión 17).**
  - **ROJO.** Crear `IdentityTokenPurgeJobsIT.java` (un asiento por lote con el conteo, **también con cero**; lotes hasta vaciar lo purgable
    [I274]; 200 lotes agotados dejan 200 asientos y el `WARN` `retention_purge_backlog`, el resto sigue al día siguiente [I275, AT29]; los lotes
    llaman a la función con el corte calculado por el llamador desde el `Clock` [I276]; la doble ejecución no cambia nada y no falla [I256, I263];
    cada lote es su propia transacción y el asiento va en la misma que la purga, de modo que un fallo del asiento revierte la purga [AT21]) [AT18,
    AT19, AT20, AT27]; `RefreshTokenPurgeIT.java` (sucesor residual vaciado tras `GRACE_WINDOW` y no antes ni en el borde exacto [I252, I253]; vaciar
    el sucesor no desactiva la detección de reutilización [I254]; no cruza instituciones [I255]; el vaciado deja su propio asiento
    `identity.refresh_token.successors_cleared` [AT28]) y `AuditChainAfterPurgeIT.java` (la cadena sigue verificando tras los asientos de purga)
    [AT22]. Las pruebas de borde de este PR siguen la regla de reloj de la base de datos. Crear `RecurringJobCatalogTest.java` con las dos
    tareas (`identity.purge-password-reset-tokens` diaria 04:30 y `identity.purge-refresh-tokens` diaria 04:35, en `America/Tegucigalpa`) y la
    afirmación de que corren por la institución configurada [I262, BJ62]. **Rojos esperados:** casos de uso inexistentes; asientos ausentes.
  - **VERDE.** Crear `identity/application/{IdentityRetentionPurges,PurgeExpiredPasswordResetTokens,PurgeExpiredRefreshTokens,RefreshTokenPurgeCount}.java`
    (ventana de P3 como constante igual al piso; hasta 200 lotes de 5 000; `clearResidualSuccessors` primero y en su propia transacción) e
    `identity/infrastructure/JooqIdentityRetentionPurges.java` (rutina generada con valores enlazados; el `UPDATE` del sucesor con
    `successor_token_ciphertext IS NOT NULL AND consumed_at < ?`). Asientos con actor `system`, etiqueta `retention-purge`, `entity_type =
    "shared.job_run"`, `entity_id = runId`, `after_value` por `AuditJson` (solo conteos e instantes). Registrar los `RecurringJob` en
    `identity/infrastructure/wiring/IdentityWorkerConfiguration.java`.
  - **Demostraciones deliberadas.** (1) Escribir el asiento en una transacción distinta de la purga pone en rojo `IdentityTokenPurgeJobsIT` en la
    atomicidad [AT21]. (2) Omitir el asiento cuando el conteo es cero pone en rojo el caso de cero filas [AT19]. (3) Vaciar el sucesor sin la guarda
    de `GRACE_WINDOW` pone en rojo `RefreshTokenPurgeIT` en el borde exacto [I253]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`; `identity.domain` en 95 y PIT en 80. Commit: `feat(identity): purge expired reset and refresh tokens daily per
    institution with a per-batch audit entry`. Tamaño: nominal 300, realista 700 a 850; costura nombrada: el vaciado del sucesor residual con sus
    pruebas va a un PR aparte si se pasa. — Escenarios I252 a I256, I262, I263, I274 a I276, AT18 a AT22, AT27 a AT29 y BJ62 (parte de identidad).

- [ ] 3.5 **PR 5a / 5a-3 `05a-adr-0025-and-retention-notes`: ADR-0025 «Propuesto», nota en ADR-0017 y notas de `docs/03` §6.1 y `docs/08` §6.2 (decisión 22, D-N3, P3).**
  - **ROJO.** Crear `test/java/com/confia/architecture/DocumentationNotesTest.java` (o ampliar la prueba de notas documentales vigente): existen el
    archivo `docs/adr/ADR-0025-purga-por-funciones-security-definer.md` con estado «Propuesto» y su fila en `docs/adr/README.md`; ADR-0017 tiene una
    nota fechada que apunta a ADR-0025 y su cuerpo original no cambió (comparación de las líneas originales); `docs/03-seguridad.md` §6.1 y
    `docs/08-datos-privacidad-y-retencion.md` §6.2 contienen sus notas fechadas sin reescribir cuerpos [BJ41, BJ72]. **Rojo esperado:** archivos y
    notas ausentes.
  - **VERDE.** Crear `docs/adr/ADR-0025-purga-por-funciones-security-definer.md` con la plantilla de la decisión 16 como regla verificable (corte
    mínimo codificado, contexto y actor `system`, `EXECUTE` solo para `confia_admin_app`, sin `DELETE` concedido), consecuencias y alternativas
    descartadas; editar `docs/adr/README.md`; añadir una nota fechada en `docs/adr/ADR-0017-*.md` (la frase «estos son los únicos `UPDATE` y `DELETE`»
    se complementa con ADR-0025, sin reescribirla); nota fechada en `docs/03` §6.1 (purga por funciones `SECURITY DEFINER`, sin `DELETE`) y en
    `docs/08` §6.2 (la regla semanal de marca y gracia aplica a datos de negocio y personales, no a tablas técnicas; ventanas de P3).
  - **Demostración deliberada.** Reescribir una línea original de ADR-0017 pone en rojo la prueba de notas: el cuerpo cambió [BJ72]. Se revierte y se
    confirma con `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`. Commit: `docs(adr): propose ADR-0025 for purging technical tables through security definer functions`.
    Tamaño: nominal 100, realista 250. — Escenarios BJ41 (parte), BJ72.

- [ ] 3.6 **PR 5b / 5b-1 `05b-v12-purge-functions`: `V12` con las funciones de idempotencia y retroceso, y sus índices (decisión 16, P2, P3).**
  - **Precondición dura.** 3.3 (`V11` y la plantilla) fusionada en `main`.
  - **ROJO.** Ampliar `RetentionPurgeFunctionsIT.java` con `shared_purge_idempotency_keys`, `identity_purge_login_backoff` e
    `identity_purge_mfa_totp_backoff`, **con las reglas de reloj de la base de datos** y los mismos casos de la tarea 3.3 (borde exacto, un segundo
    antes y después, piso de 24 h con corte en el futuro, sin contexto, actor `staff`, otra institución, límite de lote): una clave caducada hace más
    de 24 h se elimina y el borde la conserva [BI78, BI79]; una vigente o recién caducada se conserva y sigue siendo reutilizable [BI80]; una clave
    purgada se comporta como nueva [BI82]; filas de retroceso inactivas por más de 24 h se eliminan, el borde las conserva y un intento reciente nunca
    se elimina [I257, I258, I259]; sin cruces entre instituciones y sin contexto la función falla [I260, I261, BI75, BI76]. Ampliar
    `IdempotencyKeyPrivilegeIT.java` (sin `DELETE`, `EXECUTE` exacto) [BI83, BI84] y `RolePrivilegeMatrixIT.java` con las tres funciones nuevas. Crear
    `BackoffPurgeVersusLoginIT.java` (barreras: el `UPDATE` espera al `DELETE` y la fila se recrea por `INSERT`). Índices presentes:
    `shared_idempotency_key_expires_idx (institution_id, expires_at)` [BI81] e
    `identity_login_backoff_last_attempt_idx (institution_id, last_attempt_at)` [BI108]. **Rojos esperados:** funciones e índices inexistentes.
  - **VERDE.** Crear `main/resources/db/migration/V12__create_idempotency_and_backoff_purge_functions.sql` (tres funciones con la plantilla y los dos
    índices). `V1` a `V11` no se editan. Regenerar jOOQ.
  - **Demostraciones deliberadas.** (1) Quitar el índice de `shared_idempotency_key` pone en rojo la prueba de catálogo [BI81]. (2) Cambiar `<` por `<=`
    en la función de idempotencia pone en rojo el caso del borde de 24 h [BI79]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`. Commit: `feat(db): add the security definer purge functions for idempotency keys and login backoff`.
    Tamaño: nominal 200, realista 500. — Escenarios I257 a I261, BI78 a BI84, BI108.

- [ ] 3.7 **PR 5b / 5b-2 `05b-idempotency-and-backoff-jobs`: casos de uso, tareas recurrentes y corredor por institución (decisiones 11 y 17).**
  - **ROJO.** Crear `shared/security/PurgeExpiredIdempotencyKeysTest.java` y `IdempotencyPurgeJobsIT.java` (lotes hasta vaciar; un asiento
    `shared.idempotency_key.purged` por lote con el conteo, también con cero; los dos `RecurringJob` de retroceso
    `identity.purge-login-backoff` 04:45 y `identity.purge-mfa-totp-backoff` 04:50, más `shared.purge-idempotency-keys` 04:40; la doble ejecución no
    cambia el estado) [I262, I263, AT18 a AT21, AT27]. Ampliar `RecurringJobPerInstitutionIT` para las cuatro purgas: dos instituciones configuradas,
    ambas purgadas, una que falla no impide la otra y la ocurrencia queda fallida [BJ30, BJ65]; `RecurringJobCalendarTest` con los cinco calendarios de
    purga del catálogo [BJ62]; ampliar `WorkerProductionWiringTest` con la lista exacta de beans de `SharedWorkerJobsConfiguration`. `ProcessBeanPolicy`
    del trabajador: el paquete de `shared.infrastructure.wiring` primero en rojo. **Rojos esperados:** casos de uso inexistentes.
  - **VERDE.** Crear `identity/application/PurgeStaleBackoff.java`, `shared/security/{PurgeExpiredIdempotencyKeys}.java`, ampliar
    `shared/security/IdempotencyRecordStore.java` (`purgeExpired(institution, cutoff, limit)`), `shared/infrastructure/JooqIdempotencyRecordStore.java`
    (rutina generada) y `identity/infrastructure/JooqIdentityRetentionPurges.java` (`purgeLoginBackoff`, `purgeMfaTotpBackoff`); crear
    `shared/infrastructure/wiring/SharedWorkerJobsConfiguration.java` (primera versión: purga de idempotencia; verificador y observación llegan en 3.8
    y 4.1) y registrar los `RecurringJob` de retroceso en `IdentityWorkerConfiguration`. Nota fechada en `docs/07` §11 con las cuatro filas nuevas del
    catálogo (la tabla completa se cierra en 4.2).
  - **Demostraciones deliberadas.** (1) Quitar la repetición de lotes del caso de uso pone en rojo el caso de más de un lote: queda un resto sin
    purgar [I274]. (2) Omitir el asiento cuando el conteo es cero pone en rojo [AT19]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`; PIT en 80 sobre `shared.security`. Commit: `feat(jobs): purge idempotency keys and login backoff daily per
    institution`. Tamaño: nominal 250, realista 550. — Escenarios I262, I263, AT18 a AT21, AT27, BJ30, BJ62 y BJ65.

- [ ] 3.8 **PR 6 `06-audit-chain-verification-cadences`: cadencias diaria y semanal del verificador (decisión 19, P1, D-N2).**
  - **ROJO.** Ampliar `DefaultAuditChainVerifierTest.java` con `verifySince`: empieza en la primera fila con `occurred_at >= since` y toma su `prev_hash`
    **almacenado** como `running` [AT25]; cadena íntegra en la ventana reporta integridad y filas verificadas [AT07]; una fila alterada dentro de la
    ventana se identifica con exactitud [AT08]; una alteración anterior a la ventana no se ve [AT09]; ventana sin filas devuelve `Empty` [AT26]. Crear
    `AuditChainVerificationJobsIT.java`: una fila alterada con el superusuario del contenedor produce en la diaria (si está en la ventana) y en la
    semanal el asiento `shared.audit.chain_verification_failed` con `firstDivergentId`, el `ERROR` `audit_chain_broken` (alerta S1) y la ocurrencia
    fallida, sin reparar la cadena [AT11, AT12]; cadena íntegra: asiento `shared.audit.chain_verified` e `INFO` [AT13]; institución sin asientos:
    `WARN` `audit_chain_empty_for_configured_institution` [AT30]; el asiento se escribe **después** del recorrido en una transacción separada [AT31]; una
    alteración en una institución no aparece en otra [AT14]; una no configurada no se verifica [AT15]; el fallo técnico no deja resultado y no se
    reintenta, el de integridad sí deja resultado [AT16]. Crear `AuditChainDailyWindowTest.java` con reloj fijo: la ventana diaria empieza a las 00:00
    del **primer día del mes anterior** en `America/Tegucigalpa` (casos: 1 de marzo, 31 de enero, 1 de enero, un día 15) y no es una ventana móvil de 30
    días [AT23, AT24]. Ampliar `RecurringJobCalendarTest` con `shared.audit-chain-verification-daily` (03:00) y `-weekly` (domingo 03:30) [BJ62] y la
    ausencia vigente de la cadencia horaria [AT17]. **Rojos esperados:** `verifySince` y `firstAtOrAfter` inexistentes; tareas inexistentes.
  - **VERDE.** Editar `shared/audit/{AuditChainVerifier,DefaultAuditChainVerifier,AuditLogReader}.java` (`verifySince`, `firstAtOrAfter`) y
    `shared/infrastructure/JooqAuditLogReader.java`; crear `shared/audit/AuditChainVerificationJobs.java` (dos `RecurringJob` sin tipos de la
    biblioteca; asientos con `AuditJson`) y registrarlos en `SharedWorkerJobsConfiguration` junto con `JooqAuditLogReader`,
    `CanonicalAuditRowSerializer` y `DefaultAuditChainVerifier`. «El verificador se invoca directamente, sin infraestructura de programación»:
    `verifyChainOf` conserva su firma. Seguimiento documental de P1 en la nota de 4.2.
  - **Demostraciones deliberadas.** (1) Calcular la ventana como «hoy menos 30 días» pone en rojo `AuditChainDailyWindowTest` [AT23]. (2) Hacer que
    `verifySince` compare contra el hash de la fila anterior en la frontera pone en rojo [AT25]. (3) Escribir el asiento dentro de la transacción del
    recorrido pone en rojo [AT31]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`; PIT en 80 sobre `shared.audit`. Commit: `feat(audit): verify the audit chain daily over the last calendar month
    and weekly from genesis`. Tamaño: nominal 250, realista 600 a 750. — Escenarios AT07 a AT17, AT23 a AT26, AT30, AT31, BJ62 y el escenario del
    verificador sin infraestructura de programación.

---

## Fase 4: observabilidad de la cola y cierre

- [ ] 4.1 **PR 7 / 7a `07a-queue-observability`: lector de la cola, estado degradado y adaptador por registro estructurado (decisión 20, P8).**
  - **ROJO.** Crear `shared/observability/queue/QueueHealthTest.java` (900 s exactos sanos y 901 degradados con reloj fijo; el estado vuelve a normal al
    vaciarse la cola) [BJ34, BJ35], `QueueStateReaderIT.java` (filas sembradas en cada estado: `scheduled`, `due`, `running`, `failed` —estacionada o
    recurrente con `consecutive_failures > 0`—; antigüedad desde `now - min(execution_time)`; una tarea tomada con latido vencido cuenta como atascada)
    [BJ32, BJ67], `QueueSqlNeverReadsTaskDataTest.java` (estático sobre el texto de la consulta: nunca nombra `task_data` y solo usa `task_name`,
    `execution_time`, `picked`, `last_heartbeat`, `consecutive_failures`) [BJ33, BI101], `LogQueueMetricsTest.java` (campos exactos de los tres eventos
    `queue_state` INFO, `queue_task_failed` WARN y `queue_stalled` WARN; sin datos de tarea ni identificador de instancia) [BJ36, BJ68, BJ69]. Ampliar
    `NoUnapprovedPlainSqlTest` (tercera entrada aprobada: `JooqScheduledTaskQueueReader`; el fixture existente sigue fallando) y
    `ProcessBeanIsolationTest` (la afirmación de observabilidad admite `shared.observability.queue` en el trabajador) [BI113]. `ProcessBeanPolicy.WORKER`
    añade `shared.observability.queue`, primero en rojo. **Rojos esperados:** paquete y lector inexistentes.
  - **VERDE.** Crear `main/java/com/confia/shared/observability/queue/{QueueStateReader,QueueSnapshot,QueueState,QueueObservation,QueueHealth,QueueMetrics,
    LogQueueMetrics,package-info}.java` (`@NamedInterface` con su consumidor real) y `shared/infrastructure/JooqScheduledTaskQueueReader.java` (una
    consulta de solo lectura parametrizada, nombres recurrentes como arreglo enlazado); registrar la tarea `shared.queue-observation` (cada 60 s) en
    `SharedWorkerJobsConfiguration`. Sin Actuator ni Micrometer. Dejar el límite declarado (la observación corre en la cola que observa; el cambio 10 y 11
    deben alertar por **ausencia** de `queue_state` durante más de 5 minutos) para la nota de 4.2 [BJ70].
  - **Demostraciones deliberadas.** (1) Cambiar `>` por `>=` en `QueueHealth.evaluate` pone en rojo `QueueHealthTest` en 900 s exactos [BJ34]. (2) Añadir
    `task_data` a la lista de columnas de la consulta pone en rojo `QueueSqlNeverReadsTaskDataTest` [BJ33]. Se revierten y se confirman con `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`. Commit: `feat(observability): report the job queue state through structured logs and a degraded health
    status`. Tamaño: nominal 250, realista 450 a 600. — Escenarios BJ32 a BJ36, BJ62, BJ67 a BJ69, BI101 y BI113.

- [ ] 4.2 **PR 7 / 7b `07b-closing-notes`: notas documentales, Javadoc de `WorkerApplication` y seguimientos (decisión 22, P1, P5).**
  - **ROJO.** Crear o ampliar `DocumentationNotesTest.java` con las notas fechadas que faltan, comprobando que el texto original no cambió: `docs/07`
    §3.3 (ubicación de `TracedJob`) [BJ41], `docs/07` §11 y §11.1 (tabla completa de las ocho tareas recurrentes con calendario `America/Tegucigalpa`,
    estado fallido estacionado, **reprogramación manual** por un `UPDATE` de `execution_time` y `consecutive_failures`, y la alerta por ausencia de
    `queue_state`) [BJ70], `docs/05` §6 (usuarios del trabajador y del portal, `CONFIA_JOBS_INSTITUTIONS`; la nota que revierte la del 2026-10-04)
    [BJ71], `docs/03` §6.1 y `docs/08` §6.2 (ya presentes desde 3.5), y `foundations-plan/exploration.md` (condición 4 cerrada, seguimientos
    `audit-chain-hourly-anchor-verification` y `data-encryption-key-batch-rotation` de P1, deuda de la fase 2 de P5 sobre el descubrimiento de
    instituciones) [BJ42]. Un test afirma que el Javadoc de `WorkerApplication` describe el `DataSource`, la guardia previa, el ejecutor único y la
    configuración estrecha. **Rojo esperado:** notas ausentes.
  - **VERDE.** Escribir esas notas fechadas, sin reescribir cuerpos, y completar el Javadoc de `bootstrap/worker/WorkerApplication.java`. Nota fechada en
    `foundations-plan/exploration.md` con los dos cambios nuevos con nombre y su dependencia.
  - **Demostración deliberada.** Reescribir una línea original de `docs/07` §11 pone en rojo `DocumentationNotesTest`. Se revierte y se confirma con
    `cmp`.
  - **Cierre.** `./mvnw verify -Pmutation-gate`. Commit: `docs(sdd): record the job table, the parked failure state and the follow-up changes`. Tamaño:
    nominal 100, realista 250. — Escenarios BJ41, BJ42, BJ70 y BJ71.

- [ ] 4.3 **PR 7 / 7b `07b-closing-notes`: cierre del cambio, ausencias vigentes, trazabilidad y criterio 5 (criterio de salida 5 de F0).**
  - **ROJO.** Crear las pruebas de ausencia vigente con su motivo de caducidad: «No hay exportadores en el árbol» (falsa al cerrar los cambios 10 y 11)
    [BJ38], «El trabajador no consulta la tabla de instituciones» (falsa al cerrar la fase 2) [BJ39], «El registro de tipos contiene solo los tipos de
    este cambio» (falsa al cerrar cada cambio dueño) [BJ40] y la ausencia de la cadencia horaria [AT17], cada una con un fixture que la hace fallar.
    Crear `JobsChangeTraceabilityTest.java` o una lista de verificación en `apply-progress.md` que mapea **cada** escenario de los cuatro deltas
    (incluidos los sin identificador: «Se agrega Hibernate», «Sin dependencias prohibidas», «SQL plano fuera de la lista aprobada», etc.) a la prueba
    que lo cubre. Prueba de cierre del criterio 5 de extremo a extremo: programar dentro de una transacción revertida no deja tarea, dentro de una
    confirmada sí, y solo el trabajador ejecuta (con las dos pruebas de 2.2 y 2.3) [BJ37]. **Rojo esperado:** `docs/09` sin marcar y ausencias sin
    prueba.
  - **VERDE.** Marcar el criterio 5 en `docs/09` con nota fechada; completar la trazabilidad; cerrar `apply-progress.md` con las mediciones de cada PR
    (`git diff --numstat main...HEAD` sin `openspec/`), la duración de `verify` contra el presupuesto de 15 minutos (R-9) y el resultado de la
    sonda P-1 a P-12.
  - **Verificación final del cambio.** `./mvnw verify -Pmutation-gate` completo en `main` después de la última fusión: cobertura global de `app` al menos
    80, `domain` al menos 95, PIT al menos 80 sobre `domain` y `shared.jobs.*`; `rg -n "@Scheduled|@EnableScheduling|@Async" apps/api/app/src/main` sin
    resultados; `rg -n "scheduled_tasks" apps/api` solo en `V10`, pruebas y documentación; ningún secreto versionado; migraciones `V1` a `V9` sin cambios
    (suma de verificación); lista de «Definición de terminado» de `CLAUDE.md` marcada; todas las demostraciones deliberadas con su `cmp` registrado.
    Después, `sdd-verify` y `sdd-archive`.
  - **Cierre.** Commit: `docs(roadmap): mark exit criterion 5 of phase 0 and close the background jobs change`. Tamaño: nominal 100, realista 200. —
    Escenarios BJ37 a BJ42, AT17, BI100 y la trazabilidad de todos los escenarios.

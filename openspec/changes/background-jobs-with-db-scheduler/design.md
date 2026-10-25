# Diseño: trabajos en segundo plano con db-scheduler (cambio 9 de F0)

- **Cambio:** `background-jobs-with-db-scheduler` (F0, cambio 9; entregable 12, criterio de salida 5)
- **Fase:** diseñar
- **Fecha:** 2026-10-24
- **Estado:** pendiente de aprobación del propietario; las decisiones D-N1, D-N2 y D-N3 de la sección 13 las
  tomó el propietario el 2026-10-24 y ya no son preguntas abiertas
- **Entradas:** `proposal.md` (aprobada el 2026-10-24; P1 a P9 vinculantes y no se reabren),
  `exploration.md` (D1 a D12), ADR-0016, ADR-0017, ADR-0022, ADR-0024, `docs/03-seguridad.md` §2.4, §6.1
  y §12.4, `docs/07-observabilidad-y-operaciones.md` §3.3, §9 y §11, `docs/08` §6, `docs/05` §6 y el
  código de `main` en `b76beda`. La especificación corre en paralelo; este diseño le entrega precisiones
  en la sección 10.
- **Modo de TDD:** estricto (`openspec/config.yaml`). Ejecutor: `./mvnw verify` en `apps/api`, con JDK 25
  y Docker.

## 1. Enfoque técnico

El cambio introduce un mecanismo de trabajo en segundo plano con cuatro fronteras que el diseño trata
como invariantes y que cada rebanada prueba en rojo antes de escribir código:

1. **Solo `confia-worker` ejecuta.** El único bean `Scheduler` del sistema vive en el contexto del
   trabajador. Administración y portal programan con un `SchedulerClient` construido dentro de un
   adaptador, nunca expuesto como bean, y excluyen toda autoconfiguración del iniciador.
2. **Programar es transaccional.** El cliente escribe en `scheduled_tasks` a través del mismo
   `TransactionAwareDataSourceProxy` que usa el `DSLContext` del proceso, y se niega a programar fuera de
   una transacción abierta por `TransactionRunner`.
3. **Los tipos de db-scheduler no salen de `com.confia.shared.jobs.infrastructure`.** Los módulos
   declaran trabajos con tipos propios del JDK (`JobType`, `JobHandler`, `RecurringJob`) y nunca importan
   `com.github.kagkarlsson`. Así `identity` no depende de la biblioteca y la regla de ArchUnit es simple.
4. **Defensa antes que exposición (condición 4).** La guardia de registros sensibles se reubica en
   `com.confia.shared.logging`, se instala antes de crear cualquier bean en los tres procesos y llega en
   el mismo PR que el primer `DataSource` del trabajador.

```
           administración / portal                                  trabajador
 caso de uso ──► TransactionRunner.execute(ctx, …)          WorkerSchedulerStarter (al estar listo)
   │                BEGIN; set_config(app.*)                  Scheduler (único bean, núcleo db-scheduler)
   │                                                            │ sondeo cada 10 s, latido cada 60 s
   ├─► JobScheduler (puerto, shared.jobs)                       ▼
   │     DbSchedulerJobScheduler (infrastructure)             JobRunner: MDC, contexto system, saneo
   │       SchedulerClient sobre TransactionalDataSource        │
   │       INSERT scheduled_tasks (JSON TracedJob) ─────────────┤ OneTimeJob ─► JobHandler<T> (módulo)
   │                COMMIT / ROLLBACK                            │                └► caso de uso ─► TransactionRunner
   │                                                             └ RecurringJob ─► por institución configurada
   ▼                                                                               └► función SECURITY DEFINER / verificador
 la tarea existe si y solo si el negocio confirma            fallo: BoundedRetryFailureHandler ─► reintento o «estacionada»
```

## 2. Evidencia obtenida antes de esta fase

### 2.1 Sondas

Las sondas se hicieron **por lectura** de código, de la exploración y de la documentación de la
biblioteca que cita la exploración. Esta fase no ejecutó nada: la máquina de desarrollo tiene además una
brecha conocida del almacén de confianza que bloquea descargas nuevas de Maven Central
(`apps/api/pom.xml`, comentario sobre `archunit` 1.5.0). Por eso **la sonda de compatibilidad es la
primera tarea de la rebanada 1** y cada fila que exige ejecución se convierte en una prueba en rojo; si
el resultado real difiere del esperado, la rebanada se detiene y este diseño se corrige con una nota
fechada antes de seguir. Si falla P-1, P-2 o P-3, se aplica la alternativa B de la decisión 1, ya escrita.

| Sonda | Resultado esperado por lectura (sin ejecutar) | Prueba que la resuelve | Consecuencia si difiere |
|---|---|---|---|
| P-1 Versión | Última publicada: 16.12.0 (2026-05-29). `UPGRADING.md` de `master` menciona 16.13.x: se relee la lista de versiones el día de fijarla y se fija la última publicada en Maven Central | Resolución real de `./mvnw validate` | Se fija la versión que exista; si es 16.13.x, se releen P-2 a P-8 contra ella |
| P-2 Iniciador con Spring Boot 4.1.1 | `db-scheduler-spring-boot-4-starter` declara `spring-boot`, `spring-boot-sql`, `spring-boot-jdbc`, `spring-boot-autoconfigure`, `micrometer-core` y `jackson-annotations`; su `DbSchedulerAutoConfiguration` se activa con `@ConditionalOnBean(DataSource)` y declara `Scheduler` y `DbSchedulerStarter` con `@ConditionalOnMissingBean` | `SchedulerStarterCompatibilityTest`: el trabajador arranca con un `Scheduler` propio y el del iniciador se retira; el contexto contiene un solo `Scheduler` | Alternativa B: se excluye también en el trabajador y se usa solo el núcleo |
| P-3 Convergencia | `dependencyConvergence` en verde con el BOM de Spring Boot 4.1.1; `micrometer-core` llega transitivo, sin `MeterRegistry` porque `spring-boot-micrometer-metrics` no está en el classpath | `./mvnw validate`; `WorkerHasNoMeterRegistryTest` | Exclusión explícita de la dependencia transitiva o alternativa B |
| P-4 Serializador | `Jackson3Serializer` (desde 16.8.0) acepta un `JsonMapper` de Jackson 3 en su constructor | `TracedJobSerializationTest` | Envoltura propia `Serializer` sobre el mismo `JsonMapper` (unas 40 líneas), misma prueba |
| P-5 Calendario | `Schedules.cron(String, ZoneId)` con expresión de seis campos al estilo de Spring; `Schedules.fixedDelay(Duration)` | `RecurringJobCalendarTest` | Se ajusta solo el traductor `JobCalendars` |
| P-6 Permisos del cliente | `scheduleIfNotExists` consulta la fila antes de insertar, de modo que exige `SELECT`; `schedule` solo `INSERT` | `PortalSchedulingPrivilegeIT` con `confia_portal_app` real (rebanada 1, antes de fijar `V10`) | Decide el `GRANT` del portal en `V10` (decisión 15) |
| P-7 Confirmación del cliente | Con una conexión en `autocommit = false` y `commitWhenAutocommitDisabled` en su valor por omisión (`false`), el cliente no confirma por su cuenta | `JobSchedulingAtomicityIT` | Se fija la opción de forma explícita en el constructor del cliente |
| P-8 DDL de la tabla | El esquema de PostgreSQL de la versión fijada (`db-scheduler/src/test/resources/postgresql_tables.sql`): `task_name`, `task_instance`, `task_data BYTEA`, `execution_time`, `picked`, `picked_by`, `last_success`, `last_failure`, `consecutive_failures`, `last_heartbeat`, `version`, `priority` | `ScheduledTasksSchemaIT` compara el catálogo con el archivo de la versión fijada, leído del jar de pruebas o copiado con su hash | La migración copia el archivo real; la prueba lo fija |
| P-9 Creación automática | El núcleo nunca crea su tabla; además `confia_admin_app` no tiene `CREATE` sobre el esquema | `ScheduledTasksNotCreatedAtRuntimeIT` | Si el iniciador expone una opción de creación, se fija en `false` y se prueba |
| P-10 Vida del proceso | Sin servidor web, la JVM del trabajador podría terminar al volver `main`. `spring.main.keep-alive: true` la mantiene | `WorkerStaysAliveTest` | Se conserva el hilo no demonio del ejecutor como única garantía, con prueba |
| P-11 Fugas de valores enlazados | El controlador de PostgreSQL registra en `FINEST` los parámetros de `Bind`; jOOQ ya está apagado por `withExecuteLogging(false)`; Hikari, Spring JDBC y db-scheduler se declaran preventivos | `WorkerSensitiveLoggingIT` y su control negativo | Cada prefijo que realmente filtre se anota como «probado» y los demás como «preventivos», como hace hoy `SensitiveLogGuard` |
| P-12 jOOQ y funciones | La generación de código produce una clase de rutina por función (`globalObjectReferences=false` impide la clase paraguas) y admite parámetros `OUT` | `RetentionPurgeFunctionsIT` llama a cada función por su rutina generada | La llamada pasa a la lista aprobada de SQL plano con un único parámetro enlazado |

**Nota fechada (2026-10-24, aplicación de la rebanada 1): resultado de las sondas P-1, P-2, P-3, P-6, P-8 y P-9.** Se ejecutaron
con la dependencia real; ningún resultado difiere del esperado salvo P-6. La alternativa B (tarea 1.2) **no aplica**.

| Sonda | Resultado observado |
|---|---|
| P-1 | Maven Central lista 16.12.0 como `latest` y `release` del iniciador y del núcleo (`maven-metadata.xml`, última actualización 2026-05-29); no existe 16.13.x. Versión fijada: **16.12.0**, propiedad `db-scheduler.version` en `apps/api/pom.xml`. |
| P-2 | El iniciador `db-scheduler-spring-boot-4-starter:16.12.0` carga y arranca con Spring Boot 4.1.1: en el rojo, el contexto administrativo creó su `DbSchedulerAutoConfiguration`, un bean `Scheduler` y los hilos `db-scheduler-execute-due-pool-*`. Declara **tres** autoconfiguraciones, todas públicas: `DbSchedulerAutoConfiguration`, `DbSchedulerMetricsAutoConfiguration` y `DbSchedulerActuatorAutoConfiguration`. Se excluyen **por nombre** (`excludeName`) en administración y portal, no por clase, para que `com.github.kagkarlsson` no aparezca en `com.confia.bootstrap` (la regla de confinamiento de la decisión 21). Que el `Scheduler` propio desplace al del iniciador en el trabajador se sondea en 1.6 (`SchedulerStarterCompatibilityTest`). |
| P-3 | `./mvnw validate` pasa con `dependencyConvergence` y `bannedDependencies`: el BOM de Spring Boot 4.1.1 alinea las versiones transitivas del iniciador (que declara Spring Boot 4.0.6 y `spring-core` 6.2.18). `micrometer-core` llega transitivo; no hay `MeterRegistry`. |
| P-6 | **Difiere de lo esperado en un punto.** Con `INSERT` solo, el cliente real conectado como `confia_portal_app` falla con `permission denied for table scheduled_tasks` en `scheduleIfNotExists` **y también en `schedule`**: en el cliente estándar de 16.12.0 `schedule` se implementa sobre `scheduleIfNotExists` (`SchedulerClient.java`, líneas 441 y 447), que lee la fila antes de insertar. Consecuencia, decisión 15: `V10` concede `SELECT, INSERT` a `confia_portal_app` (nunca `UPDATE` ni `DELETE`, probado). El privilegio queda justificado por el cliente de programación y no por la salud (ADR-0017 no concede `SELECT` para la salud); la condición de `docs/07` §9 para un indicador `backgroundJobs` en el portal se cumple, y que el grupo de preparación del portal lo incluya sigue siendo decisión de los cambios 10 y 11 (nota fechada en `docs/07` §9). |
| P-8 | El DDL de PostgreSQL de la versión fijada (`db-scheduler/src/test/resources/postgresql_tables.sql` en la etiqueta `v16.12.0`, blob `6ff4d56f60808570f45dc78f1a94b18c35b6b2c7`, SHA-256 `250c9ce1…9c15`) se copió como recurso de prueba `db/scheduler/postgresql_tables.sql`; `ScheduledTasksSchemaIT` lo aplica a un esquema de prueba y compara el catálogo con el de `scheduled_tasks`. Coincide en las doce columnas, su tipo, anulabilidad y orden, y en la clave primaria y los tres índices. |
| P-9 | El núcleo no tiene opción de creación de tablas (`spring-configuration-metadata.json` del iniciador: ninguna propiedad de creación) y `confia_admin_app` no tiene `CREATE` sobre el esquema. `ScheduledTasksNotCreatedAtRuntimeIT` lo prueba con el cliente real y un registrador de sentencias. |

**Entorno (2026-10-24).** La descarga de artefactos nuevos de Maven Central falló con PKIX por el escaneo HTTPS de Avast, cuya autoridad raíz está en el
almacén de Windows y no en `cacerts` del JDK 25. El propietario autorizó ejecutar Maven con `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"`;
no se cambió ningún archivo del repositorio ni `cacerts` (riesgo R-2 resuelto sin tocar el repositorio).

### 2.2 Código leído que condiciona el diseño

- `WorkerApplication` excluye `DataSourceAutoConfiguration`, `SecurityAutoConfiguration` y
  `UserDetailsServiceAutoConfiguration`, y no importa nada. `PortalApplication` también excluye
  `DataSourceAutoConfiguration`. `AdminApplication` tiene `DataSource` y, con el iniciador en el
  classpath, arrancaría un ejecutor completo si no lo excluye.
- **Un solo artefacto para los tres procesos.** La dependencia del iniciador entra en `apps/api/app/pom.xml`
  y por tanto en el classpath de los tres. «Iniciador solo en el trabajador» significa «solo activo en el
  trabajador»: administración y portal excluyen su autoconfiguración.
- `SharedPlatformConfiguration.dslContext` crea su `TransactionAwareDataSourceProxy` en línea, sin bean.
  `TransactionRunner` usa el `DataSource` crudo con `DataSourceUtils`, y jOOQ participa porque su proxy
  resuelve la misma conexión ligada. Un segundo `DataSource` como bean rompería la autoconfiguración
  (`@ConditionalOnSingleCandidate`) y el `@ConditionalOnBean(DataSource)` del iniciador.
- `TransactionsOnlyInSharedSecurityTest` confina `TransactionTemplate` y `PlatformTransactionManager` a
  `shared.security`; `JooqConfinedToInfrastructureTest` confina jOOQ a paquetes `..infrastructure..`;
  `SharedBoundaryRulesTest` impide que `shared` dependa de un módulo; `SigningAndHashingConfinementTest`
  confina `MessageDigest` a `identity..` y `shared.security..`; `NoUnapprovedPlainSqlTest` tiene una lista
  aprobada de dos clases; `IdentityScopeExclusionInventoryTest` prohíbe cualquier biblioteca de registro
  en `com.confia.identity`.
- `SensitiveLogGuard` vive en `shared.web.request` y se registra como bean en `WebEdgeConfiguration`. Se
  instala al crearse su bean, sin orden garantizado respecto de otros beans. `ProcessBeanPolicy.WORKER`
  prohíbe todo `com.confia.shared.web`.
- `ProcessBeanIsolationTest.onlyTheAdministrativeProcessHoldsTheRateLimiterAndItsMetrics` afirma que portal
  y trabajador no tienen **ningún** bean de `com.confia.shared.observability`; las métricas de cola deben
  vivir en un subpaquete distinto y esa afirmación se estrecha a `shared.observability.metrics`.
- `PasswordResetIssuanceScheduler.schedule(InstitutionId, StaffAccountId)` no recibe el identificador de
  solicitud. `RequestPasswordReset.runWithinTransaction` sí lo tiene (`requestId`, con un UUID aleatorio
  si llega vacío) y llama al puerto dentro de su transacción.
- `IssuePasswordResetToken.execute` confirma el token y **después** llama a `PasswordResetLinkSender.send`.
  Un emisor que falla en `send` deja un token emitido y no entregado en cada intento; al cuarto intento de
  la hora el caso de uso devuelve `SKIPPED` sin enviar, y la tarea terminaría **completada**, no fallida.
- `DefaultAuditChainVerifier.verifyChainOf` recorre desde el génesis dentro de una transacción con actor
  `system`; `running` avanza con el `row_hash` **almacenado**, lo que permite reanudar desde cualquier fila.
  `AuditLogReader.pageOf(institution, afterId, pageSize)` pagina por `id`.
- Migraciones: `identity_refresh_token.successor_token_ciphertext` solo puede vaciarse (disparador de
  `V8`); la clave `identity_refresh_token_successor_fk` es diferida y la de familia es inmediata;
  `identity_login_backoff.last_attempt_at` lo escribe el reloj de Java; `shared_idempotency_key` no tiene
  índice por `expires_at`; ninguna tabla de negocio tiene clave foránea hacia
  `organization_institution`. `V3` fija el precedente de `SECURITY DEFINER` con
  `SET search_path = pg_catalog, public`. `confia_owner` es `NOBYPASSRLS` y todas las tablas tienen RLS
  forzada.
- La generación de jOOQ excluye solo `flyway_schema_history`; ADR-0017 regla 6 exige excluir también
  `scheduled_tasks`.
- `TestProcessArguments.forProcess("worker")` no pasa argumentos: el trabajador arranca hoy sin base de
  datos ni configuración.

## 3. Decisiones de arquitectura

### Decisión 1 — Iniciador en el trabajador con `Scheduler` propio; alternativa B escrita

**Elección.** `com.github.kagkarlsson:db-scheduler-spring-boot-4-starter` en la versión de P-1, fijada en
el `dependencyManagement` de `apps/api/pom.xml` con la propiedad `db-scheduler.version`, y declarada sin
versión en `apps/api/app/pom.xml`. En el trabajador, `WorkerJobsConfiguration` declara **un** bean
`Scheduler` construido con la API del núcleo a partir de las definiciones de trabajo (decisión 7), y un
bean `DbSchedulerStarter` propio (`WorkerSchedulerStarter`). Por `@ConditionalOnMissingBean`, el iniciador
retira los suyos y aporta el enlace de propiedades `db-scheduler.*` y, cuando lleguen Actuator y
Micrometer en los cambios 10 y 11, su indicador de salud y sus métricas.

**Por qué un `Scheduler` propio y no el del iniciador.** El iniciador recoge como tareas todos los beans de
tipo `Task<?>` del contexto. Declararlos obligaría a que cada módulo importe tipos de db-scheduler en su
configuración, o a registrar beans de forma dinámica. Con el bean propio, los módulos declaran
`JobDefinition` (tipos del JDK) y una sola clase de `shared.jobs.infrastructure` los traduce. También se
necesita control exacto del serializador, del manejador de fallos y del arranque.

**Ajustes del ejecutor** (`application.yml`, inertes en administración y portal porque excluyen la
autoconfiguración):

| Propiedad | Valor | Motivo |
|---|---|---|
| `db-scheduler.threads` | 4 | Volumen de una institución; deja margen al grupo de conexiones por omisión (10) |
| `db-scheduler.polling-interval` | 10s | Retraso máximo de toma de una tarea vencida |
| `db-scheduler.heartbeat-interval` | 1m | Detección de ejecución muerta en unos 6 minutos (en vez de 30), por debajo de la meta de 900 s de `QueueStalled` |
| `db-scheduler.missed-heartbeats-limit` | 6 | Tolera una pausa larga de recolección de basura sin revivir una ejecución viva |
| `db-scheduler.shutdown-max-wait` | 30s | El valor por omisión (30 min) bloquearía el cierre del contenedor y de cada prueba |
| `db-scheduler.delay-startup-until-context-ready` | true | Ninguna tarea corre antes de que el contexto termine |
| `spring.main.keep-alive` | true | El trabajador no tiene servidor web (P-10) |

`WorkerSchedulerConfiguration` lee esos valores del `DbSchedulerProperties` enlazado por el iniciador. Con
la alternativa B se leen de un `@ConfigurationProperties` propio con los mismos nombres.

**`WorkerSchedulerStarter`.** Arranca el `Scheduler` en `ApplicationReadyEvent` y lo detiene al cerrar el
contexto. Lee `confia.jobs.executor.autostart` (verdadero por omisión). Solo los argumentos de prueba de
`ProcessBeanIsolationTest` y de las pruebas de arranque sin base de datos lo ponen en `false`; con
`false` escribe un `WARN` `jobs_executor_not_started` al arrancar. `JobsConfigurationGuardTest` afirma que
ningún `application*.yml` del repositorio fija `autostart: false`.

**El ejecutor usa el `DataSource` crudo**, no el proxy transaccional: nunca debe participar en una
transacción de negocio. Cada manejador abre la suya con `TransactionRunner`. El efecto del manejador y la
marca de completado de db-scheduler son dos transacciones distintas: una caída entre ambas reejecuta la
tarea («al menos una vez», ADR-0016 regla 5), y por eso cada manejador es idempotente (decisiones 17 y 18).

**Alternativa B (si falla P-1, P-2 o P-3).** Se excluye la autoconfiguración del iniciador también en el
trabajador, la dependencia pasa a `com.github.kagkarlsson:db-scheduler` (núcleo) y
`WorkerSchedulerStarter` deja de implementar la interfaz del iniciador y pasa a ser un `SmartLifecycle`.
Cambian unas 30 líneas y ninguna otra decisión.

**Descartado:** el `Scheduler` del iniciador con beans `Task<?>` declarados por cada módulo: lleva tipos de
la biblioteca a `identity` y reparte la política de fallos. **Descartado:** el núcleo en los tres procesos
y el iniciador en ninguno desde el inicio: contradice el enfoque aprobado (D1 *A*) sin que la sonda lo
justifique todavía.

### Decisión 2 — Dependencias y construcción

- `maven-enforcer-plugin` conserva la prohibición de `org.quartz-scheduler:*` y `org.jobrunr:*` sin
  cambios, y `dependencyConvergence` sigue activa (P-3).
- La generación de jOOQ excluye `scheduled_tasks` (`<excludes>flyway_schema_history|scheduled_tasks</excludes>`,
  ADR-0017 regla 6). Las funciones de purga se generan como rutinas (P-12).
- `micrometer-core` llega transitivo con el iniciador. No se agrega `spring-boot-micrometer-metrics` ni
  Actuator: P8 deja las métricas en un adaptador de registro. `WorkerHasNoMeterRegistryTest` afirma que
  ningún proceso tiene un `MeterRegistry`.
- PIT agrega `com.confia.shared.jobs.*` a `targetClasses` y `targetTests`: la política de reintentos, el
  identificador de instancia y la validación de datos de tarea son reglas que un mutante no debe atravesar.

### Decisión 3 — Paquetes y dirección de las dependencias

| Paquete | Contenido | ¿Bean? | Rebanada |
|---|---|---|---|
| `com.confia.shared.logging` (nuevo, `@NamedInterface`; consumidor: los tres puntos de entrada) | `SensitiveLogGuard` (movido), `SensitiveLogGuardInitializer` | Sí, registrado por el inicializador | 2 |
| `com.confia.shared.security` | `TransactionalDataSource` (proxy único y comprobación de transacción activa) | Sí, en `TransactionRunnerConfiguration` | 2 |
| `com.confia.shared.jobs` (nuevo, `@NamedInterface`; consumidores: `identity`, `shared.audit`) | `JobType`, `RetryPolicy`, `JobMeta`, `ActorKind`, `TracedJob`, `JobInstanceId`, `JobScheduler`, `ScheduleOutcome`, `JobHandler`, `JobContext`, `RecurringJob`, `RecurringJobHandler`, `RecurringRun`, `JobCalendar`, `OneTimeJob`, `JobDefinition`, `JobPayloadRules` | No (tipos y puertos del JDK) | 3 |
| `com.confia.shared.jobs.infrastructure` (nuevo) | `DbSchedulerJobScheduler`, `TracedJobSerializer`, `BoundedRetryFailureHandler`, `JobRunner`, `RecurringJobRunner`, `JobCalendars`, `WorkerSchedulerStarter`, `ConfiguredInstitutions`, `ParkedExecutions` | Sí | 2, 3 |
| `com.confia.shared.jobs.infrastructure.wiring` (nuevo) | `JobSchedulingConfiguration` (cliente, administración y portal), `WorkerPlatformConfiguration`, `WorkerJobsConfiguration` | Sí | 2, 3 |
| `com.confia.shared.infrastructure` | Rutinas de purga de idempotencia en `JooqIdempotencyRecordStore`; `JooqAuditLogReader.firstIdAtOrAfter`; `JooqScheduledTaskQueueReader` (SQL plano aprobado) | Sí | 5b, 6, 7 |
| `com.confia.shared.infrastructure.wiring` (nuevo) | `SharedWorkerJobsConfiguration` (verificador de cadena, purga de idempotencia, observación de cola) | Sí | 5b, 6, 7 |
| `com.confia.shared.audit` | `AuditChainVerifier.verifySince`, `AuditChainVerificationJobs` | No / Sí | 6 |
| `com.confia.shared.security` | `PurgeExpiredIdempotencyKeys`, `IdempotencyRecordStore.purgeExpired` | Sí | 5b |
| `com.confia.shared.observability.queue` (nuevo) | `QueueStateReader` (puerto), `QueueObservation`, `QueueHealth`, `QueueMetrics` (puerto), `LogQueueMetrics` | Sí | 7 |
| `com.confia.identity.application` | `PasswordResetIssuanceJob`, `PasswordResetIssuanceJobs`, `PasswordResetIssuanceJobHandler`, casos de uso de purga, puertos de purga | Sí | 4, 5a, 5b |
| `com.confia.identity.infrastructure` | `JobPasswordResetIssuanceScheduler`, `UnavailablePasswordResetLinkSender`, `JooqIdentityRetentionPurges` | Sí | 4, 5a, 5b |
| `com.confia.identity.infrastructure.wiring` | `IdentityWorkerConfiguration` (nuevo); `IdentityConfiguration` registra el adaptador de programación | Sí | 4, 5a, 5b |

- `com.github.kagkarlsson` solo en `com.confia.shared.jobs.infrastructure..` (`DbSchedulerConfinementTest`).
- `shared.jobs` depende solo del JDK, de `kernel` y de `shared.security` (para `SecurityContext` y
  `Digests`). `identity` depende de `shared.jobs`, nunca al revés.
- **Ubicación de `TracedJob`.** `docs/07` §3.3 la sitúa en `shared/observability/context` con la nota
  «forma final y serialización fijadas en F0». Se ubica en `shared.jobs`, junto al puerto que la usa, como
  fija la propuesta; se registra con nota fechada en `docs/07` §3.3.

**Descartado:** el paquete de trabajos dentro de `shared.platform`: el trabajador lo tiene prohibido y es
administrativo por construcción. **Descartado:** que `identity` declare sus tareas con tipos de la
biblioteca: obligaría a ampliar la regla de confinamiento a cada módulo.

### Decisión 4 — Exclusión total en administración y portal

- `AdminApplication` y `PortalApplication` añaden a `@EnableAutoConfiguration(exclude = …)` **todas** las
  clases de autoconfiguración que el iniciador declara en su
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. La lista exacta sale
  de la sonda P-2, y se usa `excludeName` si alguna clase no es pública.
- `StarterAutoConfigurationExcludedTest` lee ese archivo desde el classpath y afirma que cada entrada está
  excluida en ambos puntos de entrada. Si una versión nueva agrega una autoconfiguración, la construcción
  se rompe.
- `ProcessBeanPolicy.ADMIN` y `PORTAL` conservan `com.github.kagkarlsson` como paquete prohibido. Ahora
  **no es vacuo**: el cliente existe dentro de `DbSchedulerJobScheduler`, pero no como bean.
- **No se usa `db-scheduler.enabled=false`** (D2 *B*): una propiedad es un interruptor que un entorno
  puede voltear; la exclusión vive en el código del punto de entrada.

### Decisión 5 — `TransactionalDataSource`: un solo proxy por proceso

```java
// com.confia.shared.security
public final class TransactionalDataSource {
    public TransactionalDataSource(DataSource target);       // wraps it once
    public DataSource proxy();                               // the TransactionAwareDataSourceProxy
    public static void requireActiveTransaction(String operation); // IllegalStateException otherwise
}
```

- Se registra en `TransactionRunnerConfiguration`, de modo que todo proceso con `TransactionRunner` tiene
  exactamente un proxy. No es un `DataSource`, así que no compite con el de Spring Boot.
- `SharedPlatformConfiguration.dslContext` y `WorkerPlatformConfiguration.dslContext` reciben el proxy de
  este bean en vez de crear uno. `DbSchedulerJobScheduler` construye su `SchedulerClient` sobre el mismo
  objeto. Así se cumple literalmente «el mismo `TransactionAwareDataSourceProxy` que usa el
  `DSLContext`».
- `requireActiveTransaction` usa `TransactionSynchronizationManager.isActualTransactionActive()` y vive en
  `shared.security`, el único paquete que razona sobre transacciones.
- **Descartado:** un proxy nuevo en el adaptador: es equivalente en comportamiento, pero la invariante «un
  proxy por proceso» deja de ser revisable.

### Decisión 6 — Puerto de programación, envoltura e identificador de instancia

```java
// com.confia.shared.jobs
public record JobType<T extends Record>(String name, Class<T> payloadClass, RetryPolicy retry) { }
public enum ActorKind { STAFF, GUARDIAN, SYSTEM }
public record JobMeta(UUID correlationId, UUID requestId, UUID actorId, ActorKind actorKind,
        UUID institutionId, String traceparent) { }       // actorId and traceparent may be null
public record TracedJob<T>(T payload, JobMeta meta) { }
public record JobInstanceId(String value) {               // [0-9a-f]{32}
    public static JobInstanceId derive(String jobName, UUID... parts);
}
public enum ScheduleOutcome { SCHEDULED, ALREADY_SCHEDULED }
public interface JobScheduler {
    <T extends Record> ScheduleOutcome schedule(JobType<T> type, JobInstanceId instance,
            T payload, JobMeta meta, Instant runAt);
}
```

- **Nombre de tipo:** `[a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)+`, con el módulo como primer segmento
  (`identity.password-reset-issuance`). Es el `task_name` de la tabla.
- **Identificador de instancia:** los primeros 32 caracteres hexadecimales de
  `SHA-256("v1|" + jobName + "|" + parts…)` mediante `Digests` de `shared.security`. Es determinista,
  opaco y no personal: aunque db-scheduler lo registre en `DEBUG`, no revela nada.
- **`DbSchedulerJobScheduler.schedule`**, en este orden:
  1. `TransactionalDataSource.requireActiveTransaction("schedule a job")`;
  2. `JobPayloadRules.validate(type, payload)` (decisión 8);
  3. `meta` completo (todo salvo `actorId` y `traceparent`), con `meta.institutionId` igual al
     `app.institution_id` de la transacción, leído del `SecurityContext` que el adaptador recibe del
     llamador;
  4. serialización con el límite de tamaño;
  5. `scheduleIfNotExists(instance, runAt)`, que devuelve `SCHEDULED` o `ALREADY_SCHEDULED`.
- Si la transacción se reintenta (40001 o 40P01), el intento anterior se revirtió con su `INSERT` y el
  nuevo vuelve a programar con el mismo identificador: el resultado es una sola tarea.

**Descartado:** `schedule` sin comprobar existencia: un duplicado viola la clave primaria y PostgreSQL
aborta **toda** la transacción de negocio, que es justo lo que no puede pasar.

### Decisión 7 — Definiciones de trabajo sin tipos de la biblioteca

```java
// com.confia.shared.jobs
public interface JobHandler<T extends Record> { void handle(T payload, JobContext context); }
public record JobContext(String taskName, JobInstanceId instance, JobMeta meta, int attempt) {
    public SecurityContext systemContext();   // ("", "system", meta.institutionId, meta.requestId)
}
public interface RecurringJobHandler { void runFor(InstitutionId institution, RecurringRun run); }
public record RecurringRun(String taskName, UUID runId, Instant startedAt) {
    public SecurityContext systemContextFor(InstitutionId institution); // requestId = runId
}
public sealed interface JobCalendar {
    record DailyAt(LocalTime time) implements JobCalendar { }
    record WeeklyAt(DayOfWeek day, LocalTime time) implements JobCalendar { }
    record Every(Duration interval) implements JobCalendar { }
}
public sealed interface JobDefinition permits OneTimeJob, RecurringJob { String name(); }
public record OneTimeJob<T extends Record>(JobType<T> type, JobHandler<T> handler) implements JobDefinition { }
public record RecurringJob(String name, JobCalendar calendar, RecurringJobHandler handler) implements JobDefinition { }
```

- La zona es **siempre** `America/Tegucigalpa` (`JobCalendars.ZONE`), sin parámetro: un módulo no puede
  elegir otra. `JobCalendars` traduce a `Schedules.cron("0 30 4 * * *", ZONE)` o a
  `Schedules.fixedDelay(…)` (P-5).
- `WorkerJobsConfiguration` recibe `List<JobDefinition>` (beans de cada configuración de trabajador), y
  falla al arrancar si hay nombres repetidos. Construye una tarea única por `OneTimeJob` con datos
  `TracedJob` y una tarea recurrente sin datos por `RecurringJob`, ambas con su manejador de fallos.

### Decisión 8 — Serialización de `task_data` y lista aprobada de campos

- **Serializador:** `TracedJobSerializer` envuelve el `Jackson3Serializer` de la biblioteca (P-4),
  construido con un `JsonMapper` estricto: `FAIL_ON_UNKNOWN_PROPERTIES`, `FAIL_ON_NULL_FOR_PRIMITIVES`,
  sin tipado polimórfico por omisión, sin `INCLUDE_SOURCE_IN_LOCATION` y con fechas en ISO-8601. Lo usan
  el `Scheduler` del trabajador y el cliente de administración y portal: hay un solo serializador.
- **Forma en la tabla:** `{"payload":{…},"meta":{…}}`, en UTF-8, con un máximo de 4 KiB por tarea.
- **Tipo de datos de la tarea:** `TracedJob` sin parámetro. La carga llega como mapa y `JobRunner` la
  convierte a `type.payloadClass()` con el mismo `JsonMapper` estricto. Es la única conversión, y rechaza
  campos desconocidos.
- **Saneo:** toda excepción de serialización o deserialización se traduce a `JobDataRejectedException`,
  cuyo mensaje nombra la tarea y la clase de la excepción original, **sin** la causa encadenada: el mensaje
  de Jackson puede citar un nombre de campo o un fragmento de los datos.
- **Serialización de objetos de Java prohibida por construcción:** el serializador solo acepta
  `TracedJob.class` y solo bytes que sean un objeto JSON. Una fila con bytes de `ObjectOutputStream`
  (`0xACED…`) se rechaza sin instanciar nada.
- **`JobPayloadRules`** (unidad, objetivo de PIT): la carga es un `record` cuyos componentes son solo
  `UUID`, `Instant`, `LocalDate`, `boolean`, `int`, `long` o un `enum` del mismo módulo. **Nunca**
  `String`, `BigDecimal`, `Money`, colecciones ni otros `record`. Ninguna regla de nombres puede
  garantizar que un `String` no lleve un correo; la regla de tipos sí.
- **Lista aprobada por tipo de tarea** (ADR-0016 §Cumplimiento 6): `JobPayloadAllowListTest` serializa
  una instancia de cada `JobType` registrado y compara el conjunto exacto de claves JSON, a todo nivel,
  con la lista escrita en la prueba. `meta` tiene su lista propia. El registro de tipos se obtiene del
  contexto real del trabajador, de modo que un tipo nuevo sin entrada en la lista rompe la prueba.

**Descartado:** el serializador por omisión del iniciador (serialización de Java): cualquier rol con
`INSERT` en la tabla, incluido el del portal, podría plantar un objeto. **Descartado:** tipado polimórfico
de Jackson para resolver `T`: es la superficie clásica de deserialización insegura.

### Decisión 9 — Reintentos acotados y estado fallido (P9)

```java
// com.confia.shared.jobs
public record RetryPolicy(int maxAttempts, Duration firstDelay, double multiplier, Duration maxDelay) {
    public Optional<Duration> delayAfterFailure(int consecutiveFailures); // empty when exhausted
}
```

- `BoundedRetryFailureHandler` implementa el `FailureHandler` de la biblioteca. **No** se usa
  `MaxRetriesFailureHandler`: al agotarse llama a `stop()`, que **borra** la ejecución y la descarta en
  silencio (ADR-0016 regla 6).
- Con intentos disponibles, reprograma a `now + delay` (exponencial, con tope `maxDelay`). Agotados,
  **estaciona** la ejecución: `reschedule(complete, ParkedExecutions.PARKED_AT)`, con
  `PARKED_AT = 9999-01-01T00:00:00Z`. La fila conserva `consecutive_failures` y `last_failure`, nunca la
  toma ningún sondeo y es visible para las métricas. **Estado fallido = ejecución estacionada.**
- Las tareas recurrentes usan el comportamiento por omisión de la biblioteca (reprogramar a la próxima
  ocurrencia). Se consideran fallidas mientras `consecutive_failures > 0`, hasta su próximo éxito
  (decisión 20).
- Cada fallo escribe un `WARN` `job_failed` con `task`, `attempt` y `outcome` (`retry` o `parked`), base
  del futuro `confia_job_failures_total` y de `QueueFailureBurst`.
- **Reprogramar una tarea fallida** es una operación manual de un solo `UPDATE` de `execution_time` y
  `consecutive_failures`, documentada como nota en `docs/07` §11.1. No se entrega una herramienta.

| Tipo de tarea | Intentos | Primera espera | Factor | Tope |
|---|---|---|---|---|
| `identity.password-reset-issuance` | 5 | 30 s | 2 | 5 min |
| Recurrentes | Próxima ocurrencia | — | — | — |

**Descartado:** una tabla de tareas fallidas (P9 *B*): amplía el catálogo de ADR-0017.

### Decisión 10 — `JobRunner` y `RecurringJobRunner`

**`JobRunner`** (una ejecución única):

1. Coloca en el MDC `jobTask`, `jobInstance`, `correlationId`, `requestId`, `institutionId` y
   `actorKind=system` (`docs/07` §3.3). El actor de `meta` no entra al MDC: queda en la auditoría.
2. Convierte la carga (decisión 8) y llama a `handler.handle(payload, context)`.
3. **Sanea toda excepción:** relanza `JobExecutionFailedException` con la tarea, la clase de la excepción
   original y, si la cadena de causas trae una `SQLException`, su `SQLState`, **sin** la causa encadenada
   ni su mensaje. db-scheduler registra los fallos en `ERROR` con la traza, y la guardia solo filtra `DEBUG`
   y `TRACE`; el detalle de una violación de unicidad de PostgreSQL (`Key (…)=(…)`) llevaría valores a un
   registro de nivel alto.
4. Limpia el MDC en `finally`.

**`RecurringJobRunner`** (una ocurrencia recurrente):

1. Genera `runId`, coloca el MDC con `correlationId = requestId = runId` (`docs/07` §3.4) y recorre
   `ConfiguredInstitutions` en orden.
2. Por cada institución llama a `runFor`. Un fallo **no** detiene a las demás: se cuenta y se registra un
   `WARN` `job_institution_failed` con la tarea, la institución y la clase de la excepción.
3. Al final, con algún fallo, lanza `RecurringJobFailedException(task, failedInstitutions)` para que la
   ejecución quede registrada como fallida.

**`ConfiguredInstitutions` (P5):** `confia.jobs.institutions`, lista de UUID separados por comas
(`CONFIA_JOBS_INSTITUTIONS`). Es obligatoria y no vacía en el trabajador, sin repetidos; el proceso no
arranca en otro caso y el mensaje nombra la propiedad y la posición de la entrada inválida. No es un
secreto. La deuda de la fase 2 (descubrimiento) se registra en `foundations-plan/exploration.md`.

### Decisión 11 — Catálogo de tareas recurrentes (`America/Tegucigalpa`)

| Nombre | Calendario | Qué hace | Rebanada |
|---|---|---|---|
| `shared.audit-chain-verification-daily` | Diaria 03:00 | Verifica desde el primer día del mes anterior por institución (decisión 19, D-N2) | 6 |
| `shared.audit-chain-verification-weekly` | Domingo 03:30 | Verifica desde el génesis por institución | 6 |
| `identity.purge-password-reset-tokens` | Diaria 04:30 | Decisión 16 | 5a |
| `identity.purge-refresh-tokens` | Diaria 04:35 | Vaciado de sucesores residuales y purga por familia | 5a |
| `shared.purge-idempotency-keys` | Diaria 04:40 | Decisión 16 | 5b |
| `identity.purge-login-backoff` | Diaria 04:45 | Decisión 16 | 5b |
| `identity.purge-mfa-totp-backoff` | Diaria 04:50 | Decisión 16 | 5b |
| `shared.queue-observation` | Cada 60 s | Decisión 20 | 7 |

Las horas siguen `docs/07` §11 y no chocan con el respaldo (01:00) ni con la integridad del libro mayor
(02:00). Honduras no aplica horario de verano, así que el desplazamiento es constante. La tabla coordina
las réplicas: una ocurrencia corre una sola vez aunque haya varios trabajadores.

### Decisión 12 — Configuración estrecha del trabajador y condición 4

`WorkerApplication` deja de excluir `DataSourceAutoConfiguration`. **Conserva** las exclusiones de
`SecurityAutoConfiguration` y `UserDetailsServiceAutoConfiguration`: el trabajador sigue sin borde web y
sin cadena de seguridad, y nada de este cambio lo contradice. Importa, en este orden:

| Configuración | Beans | Rebanada |
|---|---|---|
| (inicializador del lanzador) | `SensitiveLogGuard` | 2 |
| `WorkerPlatformConfiguration` | `DSLContext` (con `withExecuteLogging(false)`, sobre `TransactionalDataSource`), `Clock.systemUTC()`, `AuditLogWriter = JooqAuditLogWriter` sin el decorador de origen de petición; importa `TransactionRunnerConfiguration` | 2 |
| `WorkerJobsConfiguration` | `TracedJobSerializer`, `ConfiguredInstitutions`, `Scheduler`, `WorkerSchedulerStarter`, `JobRunner`, `RecurringJobRunner` | 2, 3 |
| `IdentityWorkerConfiguration` | `JooqStaffAccountRepository`, `JooqPasswordResetTokenRepository`, `UnavailablePasswordResetLinkSender`, `SecureRandom`, `IssuePasswordResetToken`, el manejador y su `OneTimeJob`; las purgas de identidad y sus `RecurringJob` | 4, 5a, 5b |
| `SharedWorkerJobsConfiguration` | `JooqAuditLogReader`, `CanonicalAuditRowSerializer`, `DefaultAuditChainVerifier`, `JooqIdempotencyRecordStore`, `PurgeExpiredIdempotencyKeys`, `QueueObservation` y sus `RecurringJob` | 5b, 6, 7 |

- No reutiliza `SharedPlatformConfiguration` ni `IdentityConfiguration`. **No** tiene llave maestra de
  columnas, pimienta de Argon2id, llaves de firma, `RequestOrigin` ni limitador. `WorkerProductionWiringTest`
  afirma la lista exacta de beans de `identity` y arranca el trabajador sin
  `confia.crypto.column-master-key` ni `confia.identity.argon2-pepper`.
- `ProcessBeanPolicy.WORKER` añade, cada una en el PR que trae su primer bean:
  - permitidos: `shared.logging`, `shared.security`, `shared.jobs.infrastructure`,
    `shared.jobs.infrastructure.wiring`, `shared.infrastructure`, `shared.infrastructure.wiring`,
    `shared.audit`, `shared.observability.queue`, `identity.application`, `identity.infrastructure` e
    `identity.infrastructure.wiring`;
  - prohibido nuevo: `com.confia.shared.crypto` («the worker holds no column-encryption key»).
  - Se conservan todas las prohibiciones actuales, incluidas `shared.security.token` y
    `shared.security.csrf`, que siguen ganando sobre el permiso de `shared.security`.
- `TestProcessArguments.forProcess("worker")` pasa la URL de base de datos inalcanzable, un UUID en
  `confia.jobs.institutions` y `confia.jobs.executor.autostart=false`.

**Orden de la condición 4.** El PR 2 entrega, en este orden de commits: guardia neutral con su
inicializador y sus pruebas; después el `DataSource` del trabajador, con la prueba sobre el proceso real.
Ningún commit de `main` da un `DataSource` al trabajador sin guardia.

### Decisión 13 — Guardia neutral de registros

- `SensitiveLogGuard` se **mueve** a `com.confia.shared.logging` con su lista única `PROTECTED_PREFIXES`.
  `WebEdgeConfiguration` deja de declararla.
- **Instalación antes que cualquier bean:** `SensitiveLogGuardInitializer`
  (`ApplicationContextInitializer`), añadido por `ConfiaApplication.launch` a los tres procesos. Corre
  después de que `LoggingApplicationListener` inicializa Logback (que reinicia los filtros), instala el
  filtro, lo registra como singleton para que las pruebas lo obtengan y lo retira en `ContextClosedEvent`.
  El arnés web de pruebas usa el mismo inicializador.
- **Prefijos nuevos** (bloqueo de `DEBUG` y `TRACE`; `INFO` y superiores pasan):
  - `org.jooq` (defensa en profundidad: el ajuste ya apaga el registro de sentencias);
  - `org.postgresql` (parámetros de `Bind` en `FINEST`, P-11);
  - `com.zaxxer.hikari`;
  - `org.springframework.jdbc`;
  - `org.springframework.transaction`;
  - `com.github.kagkarlsson`.

  `SensitiveLogGuardPrefixesTest` amplía su tabla con cada uno.
- El controlador de PostgreSQL registra por `java.util.logging`. La prueba fija el nivel por el puente
  de Spring Boot. Si el nivel no se propaga a JUL, la prueba fija también el `Logger` de JUL a `FINEST`,
  para que el control negativo sea real.

**Descartado:** duplicar la guardia en el trabajador: dos listas que divergen. **Descartado:** conservar la
instalación por bean: no garantiza que exista antes del primer `DataSource`.

### Decisión 14 — Cliente del portal (P6)

P6 cablea el cliente del portal en este cambio. El cliente necesita una conexión: **el portal recibe su
primer `DataSource`**, con `confia_portal_app`, y deja de excluir `DataSourceAutoConfiguration`. Para
reducir la exposición, el portal registra **solo**:

- `TransactionRunnerConfiguration`, que aporta `TransactionRunner` y `TransactionalDataSource`;
- `JobSchedulingConfiguration`, que aporta `JobScheduler`.

No registra `DSLContext`, repositorios, casos de uso ni nada de `identity` o `organization`. Ningún caso de
uso del portal programa todavía.

- `ProcessBeanPolicy.PORTAL` añade, exactos: `shared.logging`, `shared.security`,
  `shared.jobs.infrastructure` y `shared.jobs.infrastructure.wiring`. Conserva todas sus prohibiciones.
- `PortalSchedulingPrivilegeIT` (rebanada 1, P-6) programa con el cliente real conectado como
  `confia_portal_app` y decide el `GRANT` de `V10`. `PortalSchedulingIT` (rebanada 3) repite la prueba
  con el bean real del portal. Un intento de `UPDATE` o `DELETE` sobre la tabla con ese rol falla.
- `docs/07` §9: si el portal no obtiene `SELECT`, su grupo de preparación no incluye `backgroundJobs`.
- Esta consecuencia de P6 revierte la decisión D5 de `web-edge-foundations` («el portal no tiene base de
  datos»). **Decidido por el propietario el 2026-10-24 (D-N1, sección 13):** el portal recibe su `DataSource`
  y el cliente de programación del portal (P6) se cablea. Se entrega en la sub-rebanada 3b, que sigue siendo una
  costura separable de la rebanada 3.

### Decisión 15 — Migración `V10__create_scheduled_tasks.sql`

- Se copia el DDL de PostgreSQL de la versión fijada (P-8), con dos cambios: los índices llevan el nombre
  de la tabla como prefijo (`scheduled_tasks_execution_time_idx`, `scheduled_tasks_last_heartbeat_idx`,
  `scheduled_tasks_priority_execution_time_idx`; la biblioteca no depende de esos nombres), y se añaden
  comentarios que citan ADR-0016 y ADR-0017.
- Sin RLS ni `institution_id` (catálogo cerrado de ADR-0017). `MultiTenantSchemaIT` ya la exceptúa y
  pasa a comprobar que existe.
- **Permisos:**

  ```sql
  REVOKE ALL ON scheduled_tasks FROM PUBLIC;
  GRANT SELECT, INSERT, UPDATE, DELETE ON scheduled_tasks TO confia_admin_app;
  GRANT INSERT ON scheduled_tasks TO confia_portal_app;
  -- + GRANT SELECT ON scheduled_tasks TO confia_portal_app only if P-6 proves scheduleIfNotExists needs it
  -- confia_readonly: none (docs/03 section 6.1). confia_backup: pg_read_all_data.
  ```

- `RolePrivilegeMatrixIT` agrega la tabla con la matriz exacta para los cinco roles.

### Decisión 16 — Funciones de purga `SECURITY DEFINER` (P2, P3)

**Plantilla común** (cinco funciones, una por categoría):

```sql
CREATE FUNCTION identity_purge_password_reset_tokens(p_cutoff timestamptz, p_limit integer)
    RETURNS bigint
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    v_institution uuid := NULLIF(current_setting('app.institution_id', true), '')::uuid;
    v_cutoff      timestamptz := LEAST(p_cutoff, now() - interval '24 hours'); -- hard-coded floor
    v_limit       integer := LEAST(GREATEST(p_limit, 1), 10000);
    v_count       bigint;
BEGIN
    IF v_institution IS NULL THEN
        RAISE EXCEPTION 'confia_purge_context_missing' USING ERRCODE = 'P0001';
    END IF;
    IF current_setting('app.actor_kind', true) IS DISTINCT FROM 'system' THEN
        RAISE EXCEPTION 'confia_purge_actor_not_system' USING ERRCODE = 'P0001';
    END IF;
    DELETE FROM public.identity_password_reset_token t
     WHERE t.ctid IN (SELECT ctid FROM public.identity_password_reset_token
                       WHERE institution_id = v_institution AND expires_at < v_cutoff
                       LIMIT v_limit);
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END
$$;
REVOKE ALL ON FUNCTION identity_purge_password_reset_tokens(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION identity_purge_password_reset_tokens(timestamptz, integer) TO confia_admin_app;
```

- **Propietario:** `confia_owner`, el rol que ejecuta Flyway (el mismo precedente que `V3`). Una prueba lo
  afirma en `pg_proc`.
- **`search_path` fijo** y nombres calificados con `public.`, como en `V3`.
- **Corte mínimo codificado:** el llamador pasa su corte, calculado con el `Clock`. La función usa
  `LEAST(p_cutoff, now() - piso)`, de modo que **nunca** borra una fila más reciente que el piso, aunque
  el llamador pase el futuro o un reloj adelantado. El corte por parámetro hace deterministas las pruebas
  de borde. Se descarta lanzar una excepción si el corte supera el piso, porque una diferencia de
  milisegundos entre el reloj de Java y el de PostgreSQL haría fallar la tarea de producción.
- **Contexto de institución obligatorio:** `confia_owner` es `NOBYPASSRLS` y la RLS es forzada. Sin
  `app.institution_id`, un `DELETE` vería cero filas y terminaría sin error. La función se niega
  explícitamente, así que una purga sin contexto **nunca** pasa por un éxito de cero filas. Además el
  `WHERE institution_id = v_institution` usa el índice y repite lo que la política ya impone.
- **Solo el actor `system`:** una ruta HTTP de administración (actor `staff`) que llamara la función por
  error falla. Es una defensa barata, en el motor, de que solo el trabajador purga.
- **Lote acotado:** cada llamada borra como máximo `p_limit` (tope duro de 10 000). El llamador repite
  mientras el lote venga lleno (decisión 17).

| Función | Migración | Piso | Fila purgable | Particularidades |
|---|---|---|---|---|
| `identity_purge_password_reset_tokens` | `V11` | 24 h | `expires_at < corte` | Ninguna clave foránea entrante |
| `identity_purge_refresh_token_families(p_cutoff, p_limit, OUT purged_families bigint, OUT purged_tokens bigint)` | `V11` | 30 días | Familia con `revoked_at < corte` **o** `absolute_expires_at < corte` | 1) selecciona hasta `p_limit` familias purgables `ORDER BY id FOR UPDATE`; 2) borra **todos** los tokens de esas familias; 3) borra las familias. La clave del sucesor es diferida y se cumple al confirmar, sin tokens; la de familia es inmediata y ya no tiene referencias. Se usa `absolute_expires_at` y no la inactividad: la inactividad vive en código (`SessionLifetimePolicy`) y el absoluto es siempre posterior, es decir, más conservador |
| `shared_purge_idempotency_keys` | `V12` | 24 h | `expires_at < corte` | Nuevo índice `shared_idempotency_key_expires_idx (institution_id, expires_at)` |
| `identity_purge_login_backoff` | `V12` | 24 h | `last_attempt_at < corte` | Nuevo índice `identity_login_backoff_last_attempt_idx (institution_id, last_attempt_at)`: la tabla crece sin límite ante un ataque distribuido |
| `identity_purge_mfa_totp_backoff` | `V12` | 24 h | `last_attempt_at < corte` | Una fila por cuenta, sin índice nuevo |

- **No se concede `DELETE` sobre ninguna tabla a ningún rol.** `RolePrivilegeMatrixIT` e
  `IdempotencyKeyPrivilegeIT` siguen afirmando su ausencia y añaden `has_function_privilege` exacto:
  `EXECUTE` para `confia_admin_app` y para nadie más entre los cinco roles.
- Las migraciones `V1` a `V9` no se editan.

**Descartado:** `GRANT DELETE` (opción A de la exploración): también se lo daría a las rutas HTTP de
administración. **Descartado:** el corte calculado solo con `now()`: las pruebas de borde a un segundo
dependerían del reloj.

### Decisión 17 — Casos de uso de purga, lotes y auditoría

- **Puertos:**
  - `IdentityRetentionPurges` (`identity.application`), con `purgePasswordResetTokens`,
    `purgeRefreshTokenFamilies`, `purgeLoginBackoff`, `purgeMfaTotpBackoff` y
    `clearResidualSuccessors(institution, cutoff)`;
  - `IdempotencyRecordStore.purgeExpired(institution, cutoff, limit)` (`shared.security`).
- **Adaptadores** jOOQ que llaman a la rutina generada: `JooqIdentityRetentionPurges` y
  `JooqIdempotencyRecordStore`.
- **Casos de uso:** `PurgeExpiredPasswordResetTokens`, `PurgeExpiredRefreshTokens` y `PurgeStaleBackoff`
  (con dos `RecurringJob`, uno por tabla) en `identity.application`; `PurgeExpiredIdempotencyKeys` en
  `shared.security`. Cada uno implementa `RecurringJobHandler.runFor(institution, run)`:
  1. calcula `cutoff = clock.instant() - ventana`, con la ventana de P3 como constante del caso de uso,
     igual al piso de la función;
  2. repite hasta 200 lotes de 5 000; **cada lote es su propia transacción**
     (`TransactionRunner.execute(run.systemContextFor(institution), …)`) y en esa misma transacción
     escribe **un asiento de auditoría con el conteo**, también cuando es cero (evidencia de que la purga
     corrió);
  3. termina cuando un lote viene incompleto. Si agota los 200 lotes, escribe un `WARN`
     `retention_purge_backlog` con la tarea y la institución; el resto sigue al día siguiente.
- **Vaciado del sucesor residual:** `PurgeExpiredRefreshTokens` ejecuta primero, en su propia transacción,
  `clearResidualSuccessors(institution, now - GRACE_WINDOW)`:
  `UPDATE identity_refresh_token SET successor_token_ciphertext = NULL WHERE institution_id = ? AND
  successor_token_ciphertext IS NOT NULL AND consumed_at < ?`. El rol ya tiene `UPDATE` sobre esa columna y
  el disparador de `V8` permite vaciarla. No necesita función, porque no borra.
- **Asientos:** actor `system` y etiqueta `retention-purge`; `entity_type = "shared.job_run"`,
  `entity_id = runId` y `request_id = runId`; `after_value` construido con `AuditJson`:

| Acción | `after_value` |
|---|---|
| `identity.password_reset_token.purged` | `{"purged":n,"cutoff":"…","batch":k}` |
| `identity.refresh_token.successors_cleared` | `{"cleared":n,"cutoff":"…"}` |
| `identity.refresh_token.purged` | `{"families":n,"tokens":m,"cutoff":"…","batch":k}` |
| `shared.idempotency_key.purged` | `{"purged":n,"cutoff":"…","batch":k}` |
| `identity.login_backoff.purged` / `identity.mfa_totp_backoff.purged` | `{"purged":n,"cutoff":"…","batch":k}` |

  Nunca datos de las filas: solo conteos e instantes.

### Decisión 18 — Emisión de recuperación (deuda *a*, P4)

- **Puerto ampliado:** `PasswordResetIssuanceScheduler.schedule(InstitutionId, StaffAccountId, UUID
  requestId)`. `RequestPasswordReset` pasa el `auditRequestId` que ya calcula. Se actualizan sus pruebas.
- **Tipo:** `PasswordResetIssuanceJobs.TYPE = new JobType<>("identity.password-reset-issuance",
  PasswordResetIssuanceJob.class, new RetryPolicy(5, 30 s, 2.0, 5 min))`, con
  `record PasswordResetIssuanceJob(UUID accountId)`. La institución viaja en `meta`.
- **Adaptador** `JobPasswordResetIssuanceScheduler` (`identity.infrastructure`): construye `JobMeta`
  (`correlationId = requestId = requestId`, `actorKind = STAFF`, `actorId = null` porque quien pide es
  anónimo, `traceparent = null` hasta OpenTelemetry) y programa ahora mismo con
  `JobInstanceId.derive(TYPE.name(), institution, account, requestId)`. Se registra en `IdentityConfiguration`
  de administración. `RequestPasswordReset` sigue sin bean: lo registra S3 junto con su endpoint.
- **Manejador** `PasswordResetIssuanceJobHandler` (`identity.application`): llama a
  `IssuePasswordResetToken.execute(context.systemContext(), new StaffAccountId(payload.accountId()))`.
  Revalida por construcción (ADR-0017 regla 7): el bloqueo de la cuenta corre bajo la RLS de la
  institución de la tarea, y una cuenta de otra institución devuelve `ACCOUNT_NOT_FOUND` sin efecto.
- **Emisor que falla de forma explícita (P4) antes de emitir.** `PasswordResetLinkSender` gana
  `default void ensureAvailable() { }`, y `IssuePasswordResetToken.execute` la llama **antes** de abrir la
  transacción. `UnavailablePasswordResetLinkSender` lanza `PasswordResetDeliveryUnavailableException` en
  `ensureAvailable` y en `send`. Así cada intento falla sin emitir token, la tarea agota sus 5 intentos y
  queda estacionada y visible (`TaskFailed`), y **no queda ningún token huérfano** (sección 2.2). El
  cambio 14 sustituye el emisor; su adaptador no necesita sobrescribir `ensureAvailable`.
- **Doble ejecución (ADR-0016 §Cumplimiento 5):** con un emisor disponible, una segunda ejecución con los
  mismos datos sustituye el token anterior (`supersedeOpen`), acotada por el límite de 3 por hora. **El
  efecto único que se afirma:** exactamente un token abierto para la cuenta, y el último enlace entregado
  es el único válido.

### Decisión 19 — Cadencias diaria y semanal del verificador (P1)

```java
// com.confia.shared.audit
public interface AuditChainVerifier {
    AuditChainVerification verifyChainOf(InstitutionId institutionId);                 // unchanged
    AuditChainVerification verifySince(InstitutionId institutionId, Instant since);    // new
}
public interface AuditLogReader {  // added
    Optional<AuditRowSnapshot> firstAtOrAfter(InstitutionId institutionId, Instant occurredAt);
}
```

- `verifySince` empieza en la primera fila con `occurred_at >= since` y toma como `running` el
  `prev_hash` **almacenado** de esa fila, que no puede comprobar contra la anterior. Desde ahí recalcula
  cada `row_hash` con el mismo recorrido. La frontera la cubre la cadencia semanal, que sale del génesis.
  Sin filas en la ventana, devuelve `Empty`.
- La cadencia diaria usa `since = las 00:00 del primer día del mes anterior, en America/Tegucigalpa` (la zona de
  la institución; hoy es la única de F0), hasta el momento de la ejecución. Es el mes de calendario, no una ventana
  móvil de 30 días (**decidido por el propietario el 2026-10-24, D-N2**; la redacción anterior de este diseño,
  «inicio de hoy menos un mes», queda reemplazada). La fila entra en la ventana si `occurred_at >= since`. La
  semanal llama a `verifyChainOf`.
- **`AuditChainVerificationJobs`** (`shared.audit`, sin tipos de la biblioteca): dos `RecurringJob`. Por
  cada institución configurada:

| Resultado | Asiento | Evento |
|---|---|---|
| `Intact` | `shared.audit.chain_verified`, `{"scope":"daily"|"weekly","verifiedRows":n}` | `INFO` `audit_chain_verified` (`scope`, `verifiedRows`; base de `confia_audit_chain_verified_rows`) |
| `Empty` | `shared.audit.chain_verified`, `{"scope":…,"verifiedRows":0}` | `WARN` `audit_chain_empty_for_configured_institution`: una institución real siempre tiene asientos, así que suele indicar un UUID mal configurado (riesgo R-8) |
| `Diverged` | `shared.audit.chain_verification_failed`, `{"scope":…,"verifiedRows":n,"firstDivergentId":id,"divergence":"…"}` con resultado `failure` | `ERROR` `audit_chain_broken` (`scope`, `firstDivergentId`, `divergence`): alerta S1 de `docs/03` §12.4; después lanza para que la ocurrencia quede fallida |

- Los asientos se escriben en una transacción separada, **después** del recorrido. Nunca se repara la
  cadena.
- La cadencia horaria contra el ancla externa queda en `audit-chain-hourly-anchor-verification` (P1).

### Decisión 20 — Observabilidad de la cola (P8)

- **Lector** `JooqScheduledTaskQueueReader` (`shared.infrastructure`; entra a la lista aprobada de SQL
  plano de ADR-0015 regla 9 como tercera entrada). Una sola consulta de solo lectura, parametrizada, que
  **nunca** nombra `task_data` y solo usa `task_name`, `execution_time`, `picked`, `last_heartbeat` y
  `consecutive_failures`:
  - `failed`: `execution_time >= PARKED_AT`, o tarea recurrente con `consecutive_failures > 0` (los nombres
    recurrentes entran como arreglo enlazado);
  - `running`: `picked`;
  - `due`: no tomada y `execution_time <= :now`;
  - `scheduled`: el resto.
  - **Antigüedad más antigua:** `:now - min(execution_time)` de las vencidas sin tomar y no estacionadas,
    combinada con `:now - last_heartbeat` de las tomadas cuyo latido supera el límite, que ahora cuentan
    como atascadas.
- **`QueueHealth.evaluate(now)`:** `DEGRADED` si la antigüedad supera estrictamente 900 s; `HEALTHY` en
  otro caso. Es el método que llamará el indicador de preparación de los cambios 10 y 11, que informa el
  estado degradado sin retirar del tráfico (`docs/07` §9).
- **`QueueObservation`:** tarea recurrente `shared.queue-observation` cada 60 s, solo en el trabajador. Llama
  a `QueueMetrics.observed(QueueSnapshot)`, cuyo adaptador `LogQueueMetrics` escribe:
  - un `INFO` `queue_state` por ocurrencia, con `scheduled`, `due`, `running`, `failed` y
    `oldest_due_age_seconds`;
  - un `WARN` `queue_task_failed` por cada `task_name` con fallidas;
  - un `WARN` `queue_stalled` si el estado es `DEGRADED`.

  Nunca escribe datos de tarea ni identificadores de instancia.
- **Límite declarado:** la observación corre en la misma cola que observa. Si el ejecutor entero se
  detiene, el evento deja de aparecer. Los cambios 10 y 11 deben montar además una alerta por **ausencia**
  de `queue_state` durante más de 5 minutos. Se registra en riesgos y en la nota de `docs/07`.

### Decisión 21 — ArchUnit, Spring Modulith e inversiones de inventario

| Regla o prueba | Contenido | Control negativo |
|---|---|---|
| `NoInMemorySchedulingTest` (nueva) | Ninguna clase de producción usa `@Scheduled`, `@Schedules`, `@EnableScheduling`, `@Async`, `@EnableAsync`, `org.springframework.scheduling..`, `java.util.Timer` ni `ScheduledExecutorService` | Una clase por anotación y por tipo en `architecture.fixture.scheduling` |
| `DbSchedulerConfinementTest` (nueva) | `com.github.kagkarlsson..` solo desde `com.confia.shared.jobs.infrastructure..` | Una clase en `architecture.fixture.jobs.identity` |
| `JobPayloadTypesRuleTest` (nueva) | Toda clase de carga de un `JobType` registrado cumple `JobPayloadRules` | Un `record` de prueba con `String email` |
| `ProcessBeanIsolationTest` | `dbSchedulerAbsenceIsVacuousUntilChange9` se reemplaza por `onlyTheWorkerHoldsAScheduler`: cero beans `Scheduler` y ningún hilo `db-scheduler-*` en administración y portal, exactamente uno en el trabajador; la afirmación de observabilidad se estrecha a `shared.observability.metrics` | La versión anterior falla con la biblioteca en el classpath (rojo registrado) |
| `AuditScopeExclusionInventoryTest` | La dependencia de programación pasa a la regla de confinamiento; `scheduled_tasks` aparece **solo** en `V10` | Una migración de prueba que la nombra |
| `IdentityScopeExclusionInventoryTest` | `PasswordResetIssuanceScheduler` tiene exactamente una implementación (`JobPasswordResetIssuanceScheduler`); `PasswordResetLinkSender` solo `UnavailablePasswordResetLinkSender` hasta el cambio 14; `noScheduledJobReencryptsARetiredDataEncryptionKeyYet` se conserva, ahora sobre los `JobDefinition` registrados, y su motivo pasa a `data-encryption-key-batch-rotation` | Los fixtures de `PasswordRecoveryScopeViolationFixtures` |
| `NoUnapprovedPlainSqlTest` | Añade `JooqScheduledTaskQueueReader` | El fixture existente |
| `SpringModulithVerificationTest` | Los `@NamedInterface` nuevos (`shared.jobs`, `shared.logging`, `shared.observability.queue`) nombran su consumidor real (ADR-0022) | La verificación existente |

### Decisión 22 — Documentación y ADR

Notas fechadas, sin reescribir cuerpos:

- `docs/03` §6.1: purga por funciones `SECURITY DEFINER`, sin `DELETE`; `EXECUTE` solo para
  `confia_admin_app`.
- `docs/07` §3.3: ubicación de `TracedJob`.
- `docs/07` §11 y §11.1: tabla de tareas, estado fallido estacionado, reprogramación manual y alerta por
  ausencia.
- `docs/08` §6.2: nota editorial de P3.
- `docs/05` §6: `SPRING_DATASOURCE_*` del trabajador con `confia_admin_app` y del portal con
  `confia_portal_app`; `CONFIA_JOBS_INSTITUTIONS`.
- `docs/09`: se marca el criterio 5.
- `foundations-plan/exploration.md`: condición 4 cerrada, seguimientos de P1 y deuda de P5.

`openspec/config.yaml` pide elevar las decisiones de arquitectura reales a un ADR. P2 cambia en la
práctica el principio «ningún rol de aplicación borra» de `docs/03` §6.1 y la frase de ADR-0017 «estos son
los únicos `UPDATE` y `DELETE`». Como los ADR aceptados no se editan, la rebanada 5a crea **ADR-0025,
«Purga de tablas técnicas de retención mediante funciones `SECURITY DEFINER`»**, en estado «Propuesto»,
con la plantilla de la decisión 16 como regla verificable. **Decidido por el propietario el 2026-10-24 (D-N3):**
ADR-0025 se crea en estado «Propuesto» con las funciones `SECURITY DEFINER` de P2, y ADR-0017 recibe una nota
fechada que apunta a ADR-0025, sin reescribir su cuerpo. La especificación lo cubre en el requisito de notas de
documentación de `background-jobs` (escenario BJ72).

## 4. Concurrencia

| Situación | Comportamiento | Prueba |
|---|---|---|
| Dos réplicas del trabajador y una ocurrencia recurrente | La tabla elige un solo ejecutor por versión optimista de la fila | Comportamiento de la biblioteca; `RecurringJobSingleRunIT` con dos `Scheduler` sobre la misma base |
| Purga de refresco frente a rotación de la misma familia | Ambas bloquean primero la familia (`FOR UPDATE`, purga `ORDER BY id`) y después tokens: mismo orden, sin interbloqueo. Una familia purgable ya está vencida y la rotación solo vacía sucesores | `RefreshPurgeVersusRotationConcurrencyIT`, con barreras y sin esperas por reloj |
| Purga de reintentos frente a un inicio de sesión que actualiza la fila | La purga solo toca filas con 24 h sin intento; si un intento llega en ese instante, el `UPDATE` espera al `DELETE` y la fila se recrea por `INSERT` (camino existente de primer fallo) | `BackoffPurgeVersusLoginIT` |
| Transacción de la solicitud reintentada por 40001 | El `INSERT` anterior se revirtió y el nuevo usa el mismo identificador | `JobSchedulingRetriedTransactionIT` |
| Trabajador que muere a mitad de ejecución | Latido perdido 6 veces (unos 6 min), la ejecución revive y se reejecuta, con manejador idempotente | `DeadExecutionRevivalIT` con latido de 1 s en la prueba |
| Dos ejecuciones simultáneas de la emisión para la misma cuenta | El bloqueo de la cuenta serializa; la segunda sustituye; un solo token abierto | `PasswordResetIssuanceDoubleExecutionIT` |

## 5. Flujo de datos

**Programación transaccional y ejecución** (diagrama exigido por `openspec/config.yaml`):

```
RequestPasswordReset   TransactionRunner      JobPasswordReset…   DbSchedulerJobScheduler    PostgreSQL         Worker Scheduler    JobRunner / handler
       │ execute(ctx)        │                       │                       │                    │                    │                    │
       │────────────────────►│ BEGIN; set_config     │                       │                    │                    │                    │
       │  runWithinTx        │──────────────────────────────────────────────────────────────────►│                    │                    │
       │  findBy(identifier) │                       │                       │                    │                    │                    │
       │─── schedule(inst, account, requestId) ─────►│ derive instance       │                    │                    │                    │
       │                     │                       │──── schedule(TYPE, …) ►│ requireActiveTx    │                    │                    │
       │                     │                       │                       │ validate payload   │                    │                    │
       │                     │                       │                       │ scheduleIfNotExists│ INSERT (same conn) │                    │
       │  audit requested    │                       │                       │───────────────────►│                    │                    │
       │◄────────────────────│ COMMIT (or ROLLBACK: the row never existed)    │                    │                    │                    │
       │                     │                       │                       │                    │◄── poll (10 s) ────│ pick (picked=true) │
       │                     │                       │                       │                    │                    │──── execute ──────►│ MDC, deserialize
       │                     │                       │                       │                    │                    │                    │ ensureAvailable()
       │                     │                       │                       │                    │                    │                    │  └ F0: throws → sanitized failure
       │                     │                       │                       │                    │◄──── reschedule(now+30 s ×2ⁿ) or PARKED_AT ─────────────│
```

**Purga recurrente por institución:**

```
Scheduler ─► RecurringJobRunner (runId, MDC)
               └─ for institution in confia.jobs.institutions:
                    PurgeExpiredPasswordResetTokens.runFor(institution, run)
                      loop ≤ 200:
                        TransactionRunner.execute(system, institution):
                          identity_purge_password_reset_tokens(cutoff, 5000) ── SECURITY DEFINER, RLS, floor
                          audit identity.password_reset_token.purged {purged, cutoff, batch}
                        until purged < 5000
               └─ any institution failed ─► RecurringJobFailedException ─► consecutive_failures + 1
```

## 6. Cambios de archivos, por rebanada

Rutas bajo `apps/api/app/src/` salvo indicación. «R» es la rebanada de la sección 9.

| Archivo | Acción | R |
|---|---|---|
| `apps/api/pom.xml` | Propiedad `db-scheduler.version` y entrada de `dependencyManagement` | 1 |
| `apps/api/app/pom.xml` | Dependencia del iniciador; exclusión de `scheduled_tasks` en la generación de jOOQ; PIT sobre `shared.jobs` | 1, 3 |
| `main/resources/db/migration/V10__create_scheduled_tasks.sql` | Crear (decisión 15) | 1 |
| `main/java/com/confia/bootstrap/admin/AdminApplication.java`, `bootstrap/portal/PortalApplication.java` | Exclusión de la autoconfiguración (1); `@Import` del cliente y, en el portal, de `TransactionRunnerConfiguration` y el `DataSource` (3) | 1, 3 |
| `main/java/com/confia/shared/logging/{SensitiveLogGuard,SensitiveLogGuardInitializer,package-info}.java` | Mover y crear (decisión 13) | 2 |
| `main/java/com/confia/shared/web/request/SensitiveLogGuard.java`; `shared/web/edge/WebEdgeConfiguration.java` | Borrar y retirar el bean | 2 |
| `main/java/com/confia/bootstrap/ConfiaApplication.java` | Inicializador en los tres procesos | 2 |
| `main/java/com/confia/shared/security/{TransactionalDataSource,TransactionRunnerConfiguration}.java`; `shared/platform/infrastructure/SharedPlatformConfiguration.java` | Decisión 5 | 2 |
| `main/java/com/confia/bootstrap/worker/WorkerApplication.java` | `DataSource` e imports (decisión 12) | 2 |
| `main/java/com/confia/shared/jobs/infrastructure/{WorkerSchedulerStarter,ConfiguredInstitutions}.java`; `shared/jobs/infrastructure/wiring/{WorkerPlatformConfiguration,WorkerJobsConfiguration}.java` | Decisiones 1, 10 y 12 | 2 |
| `main/resources/application.yml` | `db-scheduler.*`, `spring.main.keep-alive` (inerte salvo en el trabajador; se documenta) | 2 |
| `main/java/com/confia/shared/jobs/{JobType,RetryPolicy,JobMeta,ActorKind,TracedJob,JobInstanceId,JobScheduler,ScheduleOutcome,JobHandler,JobContext,RecurringJob,RecurringJobHandler,RecurringRun,JobCalendar,OneTimeJob,JobDefinition,JobPayloadRules,package-info}.java` | Crear (decisiones 6 a 9) | 3 |
| `main/java/com/confia/shared/jobs/infrastructure/{DbSchedulerJobScheduler,TracedJobSerializer,JobDataRejectedException,BoundedRetryFailureHandler,ParkedExecutions,JobRunner,JobExecutionFailedException,RecurringJobRunner,RecurringJobFailedException,JobCalendars}.java`; `wiring/JobSchedulingConfiguration.java` | Crear | 3 |
| `main/java/com/confia/identity/application/{PasswordResetIssuanceScheduler,RequestPasswordReset,PasswordResetLinkSender,IssuePasswordResetToken,PasswordResetIssuanceJob,PasswordResetIssuanceJobs,PasswordResetIssuanceJobHandler,PasswordResetDeliveryUnavailableException}.java` | Decisión 18 | 4 |
| `main/java/com/confia/identity/infrastructure/{JobPasswordResetIssuanceScheduler,UnavailablePasswordResetLinkSender}.java`; `wiring/{IdentityConfiguration,IdentityWorkerConfiguration}.java` | Decisión 18 | 4 |
| `main/resources/db/migration/V11__create_identity_token_purge_functions.sql` | Funciones de recuperación y refresco | 5a |
| `main/java/com/confia/identity/application/{IdentityRetentionPurges,PurgeExpiredPasswordResetTokens,PurgeExpiredRefreshTokens,RefreshTokenPurgeCount}.java`; `identity/infrastructure/JooqIdentityRetentionPurges.java` | Decisión 17 | 5a |
| `docs/adr/ADR-0025-purga-por-funciones-security-definer.md`; `docs/adr/README.md` | Crear, «Propuesto» | 5a |
| `main/resources/db/migration/V12__create_idempotency_and_backoff_purge_functions.sql` | Funciones e índices | 5b |
| `main/java/com/confia/identity/application/PurgeStaleBackoff.java`; `shared/security/{IdempotencyRecordStore,PurgeExpiredIdempotencyKeys}.java`; `shared/infrastructure/JooqIdempotencyRecordStore.java`; `shared/infrastructure/wiring/SharedWorkerJobsConfiguration.java` | Decisión 17 | 5b |
| `main/java/com/confia/shared/audit/{AuditChainVerifier,DefaultAuditChainVerifier,AuditLogReader,AuditChainVerificationJobs}.java`; `shared/infrastructure/JooqAuditLogReader.java` | Decisión 19 | 6 |
| `main/java/com/confia/shared/observability/queue/{QueueStateReader,QueueSnapshot,QueueObservation,QueueHealth,QueueMetrics,LogQueueMetrics,package-info}.java`; `shared/infrastructure/JooqScheduledTaskQueueReader.java` | Decisión 20 | 7 |
| `test/java/com/confia/bootstrap/{ProcessBeanPolicy,ProcessBeanIsolationTest,TestProcessArguments,AdminProductionWiringTest,WorkerProductionWiringTest}.java` | Líneas por rebanada; inversión del caso vacuo | 1 a 7 |
| `test/java/com/confia/architecture/{NoInMemorySchedulingTest,DbSchedulerConfinementTest,JobPayloadTypesRuleTest,NoUnapprovedPlainSqlTest}.java` y fixtures | Decisión 21 | 3, 7 |
| `test/java/com/confia/schema/{RolePrivilegeMatrixIT,IdempotencyKeyPrivilegeIT,MultiTenantSchemaIT}.java` | Matriz de tabla y de funciones | 1, 5a, 5b |
| `test/java/com/confia/shared/audit/AuditScopeExclusionInventoryTest.java`; `test/java/com/confia/identity/IdentityScopeExclusionInventoryTest.java` | Inversiones | 1, 3, 4 |
| `test/java/com/confia/shared/web/request/{SensitiveDataLoggingTest,SensitiveLogGuardPrefixesTest,MalformedRequestLoggingTest}.java` | Nuevo paquete de la guardia (se mueven a `test/java/com/confia/shared/logging/` las de prefijos) | 2 |
| `test/java/**` restantes | Sección 8 | 1 a 7 |
| Documentos de la decisión 22 | Notas fechadas | 1, 2, 5a, 5b, 7 |

## 7. Interfaces y contratos

Las firmas de `shared.jobs` están en las decisiones 6, 7 y 9; las del verificador, en la 19. Además:

```java
// com.confia.identity.application
public interface PasswordResetIssuanceScheduler {
    void schedule(InstitutionId institutionId, StaffAccountId accountId, UUID requestId);   // changed
}
public interface PasswordResetLinkSender {
    default void ensureAvailable() { }                                                     // added
    void send(InstitutionId institutionId, StaffAccountId accountId, PlainPasswordResetToken token);
}
public record PasswordResetIssuanceJob(UUID accountId) { }
public interface IdentityRetentionPurges {
    long purgePasswordResetTokens(InstitutionId institution, Instant cutoff, int limit);
    RefreshTokenPurgeCount purgeRefreshTokenFamilies(InstitutionId institution, Instant cutoff, int limit);
    long purgeLoginBackoff(InstitutionId institution, Instant cutoff, int limit);
    long purgeMfaTotpBackoff(InstitutionId institution, Instant cutoff, int limit);
    long clearResidualSuccessors(InstitutionId institution, Instant consumedBefore);
}
// com.confia.shared.security
public interface IdempotencyRecordStore { /* existing */ long purgeExpired(InstitutionId institution, Instant cutoff, int limit); }
// com.confia.shared.observability.queue
public record QueueSnapshot(Instant observedAt, Map<String, Map<QueueState, Long>> depth,
        long oldestDueAgeSeconds) { }
public enum QueueState { SCHEDULED, DUE, RUNNING, FAILED }
public interface QueueStateReader { QueueSnapshot read(Instant now, Set<String> recurringTaskNames); }
public interface QueueMetrics { void observed(QueueSnapshot snapshot, QueueHealth.Status status); }
```

**Propiedades nuevas:**

- `confia.jobs.institutions` (obligatoria en el trabajador);
- `confia.jobs.executor.autostart` (verdadera por omisión, solo para pruebas);
- `db-scheduler.threads`, `polling-interval`, `heartbeat-interval`, `missed-heartbeats-limit`,
  `shutdown-max-wait` y `delay-startup-until-context-ready`;
- `spring.main.keep-alive`.

**Contrato SQL:** las cinco funciones de la decisión 16, `EXECUTE` solo para `confia_admin_app`. **Sin
cambios en la API HTTP ni en `packages/contracts`.**

## 8. Estrategia de pruebas, por rebanada (TDD estricto)

Cada fila nace **en rojo**, con el motivo esperado registrado en `apply-progress.md`. Las pruebas de
ataque y de borde se escriben antes que el código que defienden. Ninguna prueba de concurrencia espera por
reloj: usan barreras o latidos de prueba de 1 s con espera acotada por condición.

| R | Nivel | Qué | Cómo | Rojo esperado |
|---|---|---|---|---|
| 1 | Construcción | P-1 a P-3: versión, convergencia, arranque | `./mvnw validate`; resultado anotado en este diseño | — (sonda) |
| 1 | Integración | P-6: privilegios que exige el cliente con `confia_portal_app`; `UPDATE` y `DELETE` rechazados | `PortalSchedulingPrivilegeIT` | Tabla inexistente |
| 1 | Integración | P-8: catálogo de `scheduled_tasks` igual al DDL de la versión fijada; matriz de cinco roles exacta; ninguna creación en ejecución | `ScheduledTasksSchemaIT`, `RolePrivilegeMatrixIT`, `ScheduledTasksNotCreatedAtRuntimeIT` | Tabla inexistente |
| 1 | Contexto real | Cada autoconfiguración del iniciador excluida en administración y portal; cero beans `com.github.kagkarlsson` en ambos | `StarterAutoConfigurationExcludedTest`, `ProcessBeanIsolationTest` | El administrativo arranca un `Scheduler` |
| 1 | Estático | `scheduled_tasks` solo en `V10`; excluida de jOOQ | `AuditScopeExclusionInventoryTest` | La afirmación de ausencia |
| 2 | Unidad | Prefijos nuevos denegados en `DEBUG` y `TRACE`, aceptados en `WARN`; prefijo parecido no cubierto | `SensitiveLogGuardPrefixesTest` | Prefijos ausentes |
| 2 | Contexto real | La guardia está instalada antes de crear el primer bean (un `BeanFactoryPostProcessor` de prueba la observa) en los tres procesos, y se retira al cerrar | `SensitiveLogGuardInstallationTest` | Instalada por bean, tarde |
| 2 | Proceso real | **Condición 4:** trabajador arrancado con `ConfiaApplication.launch`, PostgreSQL real, raíz, `org.jooq`, `org.postgresql`, `com.zaxxer.hikari`, `org.springframework.jdbc`, `org.springframework.transaction` y `com.github.kagkarlsson` en `TRACE`; un valor enlazado distintivo (`accountId` sembrado por el propietario y pasado por `set_config` y por un `SELECT` del `DSLContext` real) no aparece en ningún mensaje, argumento, MDC ni traza | `WorkerSensitiveLoggingIT` | — (control negativo primero) |
| 2 | Proceso real | **Control negativo:** sin la guardia, el mismo valor aparece al menos en `org.postgresql`; cada prefijo que filtre se anota como «probado» | `WorkerSensitiveLoggingIT#withoutTheGuardTheBoundValueLeaks` | — |
| 2 | Contexto real | El trabajador no arranca sin `confia.jobs.institutions`, con un UUID inválido ni con repetidos; el mensaje no repite la lista | `ConfiguredInstitutionsStartupTest` | Propiedad inexistente |
| 2 | Contexto real | Exactamente un `Scheduler` en el trabajador; el del iniciador retirado (P-2); sin `MeterRegistry`; la JVM sigue viva (P-10); `autostart=false` no arranca hilos | `SchedulerStarterCompatibilityTest`, `WorkerHasNoMeterRegistryTest`, `WorkerStaysAliveTest` | Sin `DataSource` |
| 2 | Contexto real | Línea de `ProcessBeanPolicy` en rojo antes de añadirla; trabajador sin llave maestra ni pimienta | `ProcessBeanIsolationTest`, `WorkerProductionWiringTest` | «not in the allow-list» |
| 3 | Unidad + jqwik | `RetryPolicy`: 1 a 5 intentos, esperas exactas, tope, agotamiento; `JobInstanceId`: determinismo, 32 hexadecimales, sensibilidad a cada parte; `JobPayloadRules`: cada tipo permitido y prohibido | `RetryPolicyTest`, `JobInstanceIdProperties`, `JobPayloadRulesTest` | Clases inexistentes |
| 3 | Unidad | Serializador: ida y vuelta; campo desconocido rechazado; bytes `0xACED…` rechazados sin instanciar; más de 4 KiB rechazado; el mensaje rechazado no contiene la carga | `TracedJobSerializationTest` | Clase inexistente |
| 3 | Integración | **Atomicidad (criterio 5):** confirmada, la fila existe con JSON; revertida, no existe; fuera de transacción, `IllegalStateException`; transacción reintentada, una fila. **Control negativo:** un cliente de prueba sobre el `DataSource` crudo deja la fila tras revertir | `JobSchedulingAtomicityIT`, `JobSchedulingRetriedTransactionIT` | Clase inexistente |
| 3 | Integración | **Solo el trabajador ejecuta:** con administración y trabajador sobre la misma base, una tarea de un tipo de prueba (registrado en el arnés del trabajador) programada desde administración queda tomada por el trabajador (`picked_by`), y con solo administración nunca se toma mientras el contexto vive N sondeos | `OnlyWorkerExecutesIT` | Sin ejecutor |
| 3 | Integración | Estacionamiento: un tipo de prueba que siempre falla queda con `consecutive_failures = max`, `last_failure` y `execution_time = PARKED_AT`, nunca borrado; un `job_failed` por intento | `FailedExecutionIsParkedIT` | La biblioteca la borra o reintenta sin fin |
| 3 | Integración | Saneo: un manejador que lanza con un valor distintivo en el mensaje y una `SQLException` en la causa no deja ese valor en ningún evento, incluido el `ERROR` de la biblioteca | `JobFailureSanitizationIT` | El mensaje aparece |
| 3 | Integración | Portal real: programa con `confia_portal_app` a través de su bean | `PortalSchedulingIT` | Portal sin `DataSource` |
| 3 | Estático | `NoInMemorySchedulingTest`, `DbSchedulerConfinementTest`, `JobPayloadTypesRuleTest`, cada uno con su fixture | Reglas nuevas | Fixtures no rechazados |
| 4 | Unidad | `RequestPasswordReset` pasa el `requestId` al puerto; el adaptador deriva la instancia y la meta exacta | `RequestPasswordResetTest`, `JobPasswordResetIssuanceSchedulerTest` | Firma antigua |
| 4 | Integración | La solicitud con el adaptador real programa dentro de su transacción; con un escritor de auditoría que falla, ni asiento ni tarea | `RequestPasswordResetSchedulingIT` | Doble de la programación |
| 4 | Integración | **Doble ejecución:** dos ejecuciones con los mismos datos y un emisor que registra dejan un token abierto, y el último enviado es el abierto | `PasswordResetIssuanceDoubleExecutionIT` | Manejador inexistente |
| 4 | Integración | **Contexto por institución:** la tarea de A con una cuenta de B termina `ACCOUNT_NOT_FOUND`, sin token ni asiento en B | `PasswordResetIssuanceInstitutionIsolationIT` | Manejador inexistente |
| 4 | Integración | **P4:** con el emisor real del trabajador, 5 intentos fallidos, ningún token y ningún asiento `issued`, tarea estacionada | `UnavailableSenderLeavesNoTokenIT` | Token huérfano emitido |
| 4 | Estático | Lista aprobada del tipo (`accountId`; `meta`); inversión de las implementaciones del puerto | `JobPayloadAllowListTest`, `IdentityScopeExclusionInventoryTest` | Tipo no registrado |
| 5a | Integración | Por función: fila en el corte exacto, un segundo antes y un segundo después; piso respetado con un corte en el futuro; sin contexto, `confia_purge_context_missing` y nada borrado; actor `staff`, rechazo; otra institución intacta; lote de `p_limit`; tope 10 000; la rutina generada funciona (P-12) | `RetentionPurgeFunctionsIT` | Funciones inexistentes |
| 5a | Integración | Refresco: tokens y familia completos, con sucesor encadenado (clave diferida); familia revocada hace 29 días intacta; familia vencida por absoluto hace 31 días purgada; sucesor residual vaciado tras `GRACE_WINDOW` y no antes | `RefreshTokenPurgeIT` | Sin purga |
| 5a | Integración | Casos de uso: un asiento por lote con el conteo (también 0); lotes hasta vaciar; 200 lotes y aviso; ejecución doble sin efecto adicional (purga idempotente) | `IdentityTokenPurgeJobsIT` | Clases inexistentes |
| 5a | Integración | `EXECUTE` exacto por rol; propietario `confia_owner`; `prosecdef`; `proconfig` con `search_path`; ningún `DELETE` sobre tablas | `RolePrivilegeMatrixIT` ampliado | Funciones inexistentes |
| 5a | Concurrencia | Purga de refresco frente a rotación | `RefreshPurgeVersusRotationConcurrencyIT` | — |
| 5b | Integración | Las mismas filas de 5a para idempotencia y ambas tablas de reintentos; índices presentes | `RetentionPurgeFunctionsIT`, `IdempotencyKeyPrivilegeIT` | Funciones inexistentes |
| 5b | Integración | Recurrentes por institución configurada: dos instituciones configuradas, ambas purgadas; una que falla no impide la otra y la ocurrencia queda fallida | `RecurringJobPerInstitutionIT` | Sin corredor |
| 5b | Unidad | Calendario: cada `RecurringJob` del catálogo en `America/Tegucigalpa` con su próxima ejecución exacta desde un instante fijo (P-5) | `RecurringJobCalendarTest` | Traductor inexistente |
| 6 | Unidad | `verifySince`: empieza en la frontera con el `prev_hash` almacenado; vacío en ventana; divergencia dentro de la ventana | `DefaultAuditChainVerifierTest` ampliado | Método inexistente |
| 6 | Integración | Una fila alterada con el superusuario del contenedor: la diaria (si está en la ventana) y la semanal fallan con la fila exacta, asiento `chain_verification_failed`, `ERROR` `audit_chain_broken`, ocurrencia fallida; cadena íntegra: asiento y `INFO`; institución sin asientos: `WARN` | `AuditChainVerificationJobsIT` | Tareas inexistentes |
| 7 | Integración | Estados y antigüedad desde filas sembradas en cada estado; 900 s exactos sanos y 901 degradados con reloj fijo; la consulta nunca contiene `task_data` (estático sobre el texto) | `QueueStateReaderIT`, `QueueHealthTest`, `QueueSqlNeverReadsTaskDataTest` | Clases inexistentes |
| 7 | Unidad | `LogQueueMetrics`: campos exactos de los tres eventos; sin datos de tarea ni instancia | `LogQueueMetricsTest` | Clase inexistente |
| 7 | Estático | Inversiones restantes con control negativo; notas documentales presentes sin reescribir cuerpos | Pruebas de inventario | — |

- JaCoCo: 95 % en `identity.domain` (sin cambios de dominio en este cambio), 80 % global. PIT 80 %
  agrega `com.confia.shared.jobs.*`.
- Testcontainers compartido. Las pruebas de dos contextos reales (`OnlyWorkerExecutesIT`,
  `WorkerSensitiveLoggingIT`) suman tiempo a `verify`: riesgo R-9.

## 9. Plan de entrega, migración y puesta en marcha

### 9.1 Rebanadas

Cadena de PR sobre `main`, con un máximo de 800 líneas efectivas por PR (`docs/15` §3); las pruebas
nunca se separan de su código. La preflight de la sesión declara `single-pr` y un presupuesto de 400
líneas. La propuesta registra esa incompatibilidad como riesgo cierto y asume la cadena: **la fase de
tareas debe resolver la estrategia con el orquestador antes de aplicar.**

| # | Rebanada | Contenido (decisiones) | Líneas de la propuesta | Costura si se pasa |
|---|---|---|---|---|
| 1 | `scheduler-dependency-and-table` | Sondas P-1 a P-9; dependencia; exclusiones (4); `V10` (15); matriz; inversión del caso vacuo | 300 a 450 | Sondas y dependencia / `V10` y matriz |
| 2 | `worker-wiring-and-log-guard` | 5, 12, 13; trabajador con `DataSource` y `Scheduler` sin tareas; condición 4 | 550 a 750 | **Guardia neutral sola (2a)** / cableado del trabajador y prueba del proceso real (2b) |
| 3 | `transactional-scheduling` | 6 a 10; cliente en administración; portal (14); atomicidad; solo el trabajador ejecuta; reglas de la 21 | 600 a 800 | Puerto, serializador, cliente y atomicidad (3a) / portal, fallos y reglas (3b) |
| 4 | `password-reset-issuance-job` | 18 | 450 a 650 | Adaptador / manejador y P4 |
| 5a | `identity-token-purges` | `V11`, 16 y 17 para identidad; ADR-0025 | 550 a 800 | Funciones e IT / casos de uso y auditoría |
| 5b | `idempotency-and-backoff-purges` | `V12`; 16 y 17 para idempotencia y reintentos; corredor por institución | 400 a 650 | — |
| 6 | `audit-chain-verification-cadences` | 19 | 250 a 400 | — |
| 7 | `queue-observability-and-closing` | 20; notas de la 22; criterio 5; seguimientos | 300 a 500 | Observabilidad / cierre documental |

**Órdenes duros:**

1. R1 antes que todo.
2. **R2 antes de cualquier SQL en el trabajador** (condición 4). Si R2 se parte, 2a (guardia) se fusiona
   antes que 2b (`DataSource`).
3. R3 antes de R4, R5a, R5b y R6.
4. R5a antes de R5b (plantilla de funciones y ADR-0025).
5. R4 antes de S3 `password-recovery-endpoints`.
6. **Nunca se revierte la guardia mientras exista en `main` un trabajador con `DataSource`.**

### 9.2 Migraciones

- `V10` (R1), `V11` (R5a) y `V12` (R5b), en ese orden; cada una regenera jOOQ en la misma construcción.
  `V1` a `V9` no se editan. Antes de producción, revertir es eliminar la migración. Aplicadas en un
  entorno compartido, se revierten con una migración nueva que retira las funciones y sus `EXECUTE` y,
  solo sin tareas pendientes, la tabla.
- Sin datos que migrar: ningún entorno desplegado existe.

### 9.3 Puesta en marcha

Variables nuevas (`docs/05` §6):

- trabajador: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME=confia_admin_app`,
  `SPRING_DATASOURCE_PASSWORD` y `CONFIA_JOBS_INSTITUTIONS`;
- portal: `SPRING_DATASOURCE_*` con `confia_portal_app` (D-N1).

Orden de despliegue: `migrate`, después el trabajador, después administración y portal. Un trabajador
detenido no rompe la programación: las tareas esperan y `QueueStalled` avisa.

## 10. Desviaciones respecto de la propuesta y precisiones para la especificación

| # | Punto de la propuesta | Precisión de este diseño | Motivo |
|---|---|---|---|
| P-1 | Enfoque 2: «ejecutor con el iniciador» | Iniciador activo con un `Scheduler` propio; el del iniciador se retira | Mantiene los tipos de la biblioteca fuera de los módulos (decisión 1) |
| P-2 | Alcance 9: «mismo `TransactionAwareDataSourceProxy`» | Bean `TransactionalDataSource` con el único proxy del proceso | La invariante queda revisable (decisión 5) |
| P-3 | P4: «emisor que falla de forma explícita… sin tokens huérfanos» | La falla ocurre en `ensureAvailable()`, antes de emitir | Un emisor que falla en `send` deja tokens no entregados y, al cuarto intento de la hora, la tarea terminaría completada (sección 2.2) |
| P-4 | Alcance 17: «una purga sin contexto borra cero filas sin error» | La función **rechaza** la llamada sin contexto (`confia_purge_context_missing`) y también la de un actor que no es `system` | El riesgo de la propuesta es precisamente el éxito silencioso de cero filas |
| P-5 | P9: estado fallido por `consecutive_failures` y `last_failure` | Más «estacionamiento» en `PARKED_AT`: la ejecución agotada no se vuelve a tomar ni se borra | `MaxRetriesFailureHandler` borra la fila |
| P-6 | Alcance 13: «cada lote escribe un asiento» | Lote de 5 000, hasta 200 por ocurrencia; asiento también con cero | Acota el bloqueo y el WAL; el cero es evidencia de ejecución |
| P-7 | P6: «el cliente del portal se cablea» | El portal obtiene un `DataSource` con `confia_portal_app` y solo dos configuraciones | Sin conexión no hay cliente (D-N1) |
| P-8 | Alcance 12: instancia «(institución, cuenta, solicitud)» | SHA-256 truncado de esos tres valores; el puerto recibe el `requestId` | El puerto actual no lo recibe |
| P-9 | Alcance 15: métricas «hasta los cambios 10 y 11» | Tarea recurrente de observación en el trabajador; `QueueHealth` listo para la preparación; alerta por ausencia a cargo de 10 y 11 | Sin Actuator no hay preparación ni raspado |
| P-10 | Alcance 6: guardia «importada por administración y trabajador» | Instalada por inicializador en los tres procesos | El portal también recibe un `DataSource` (P-7) |

**Para la especificación:**

- `background-jobs`: P-2, P-5, la lista de tipos de carga permitidos, el límite de 4 KiB, el saneo de
  excepciones, la exclusión total y el catálogo de la decisión 11.
- `identity`: P-3, P-8 y la semántica exacta del «efecto único» de la emisión.
- `build-integrity`: P-4, la plantilla de la decisión 16 y la matriz de `EXECUTE`.
- `audit-trail`: los asientos y eventos de la decisión 19 y el comienzo de la ventana diaria.

## 11. Riesgos

| # | Riesgo | Mitigación |
|---|---|---|
| R-1 | El iniciador no arranca con Spring Boot 4.1.1 o rompe la convergencia (solo leído en documentación) | Sondas en R1; alternativa B escrita (decisión 1) |
| R-2 | La máquina de desarrollo no descarga artefactos nuevos de Maven Central (brecha conocida del almacén de confianza) | Se diagnostica antes de R1; sin la dependencia, ninguna rebanada avanza. Se informa al propietario, sin copiar jars al repositorio |
| R-3 | Una tarea se confirma fuera de la transacción | `requireActiveTransaction`, proxy único y prueba de atomicidad con control negativo |
| R-4 | Valores enlazados o datos de tarea en registros de nivel alto (`ERROR` de la biblioteca con la causa) | Saneo en `JobRunner` y en el serializador; `JobFailureSanitizationIT` |
| R-5 | Una purga borra filas vigentes o de otra institución | Piso codificado con `LEAST`, contexto y actor obligatorios, pruebas de borde a un segundo, segunda institución |
| R-6 | El portal con base de datos amplía su superficie (P6) | Solo `INSERT` (y `SELECT` si P-6 lo exige) sobre una tabla sin datos personales; dos configuraciones; D-N1 |
| R-7 | La observación de la cola corre en la cola que observa | Alerta por ausencia de `queue_state` en 10 y 11; documentado |
| R-8 | Una institución mal configurada se purga en vacío para siempre (sin clave foránea a la tabla raíz) | `WARN` `audit_chain_empty_for_configured_institution`; asiento de cero filas; descubrimiento en fase 2 |
| R-9 | Duración de `verify` con dos contextos reales y PostgreSQL | Latidos y sondeos de 1 s en pruebas; medición en R3 contra el presupuesto de 15 minutos (BI57) |
| R-10 | Subestimación de tamaño (factor histórico de 2,2 a 7,97) y más de 15 tareas | Costuras por rebanada (sección 9.1); propuesta 9a y 9b si `tasks.md` supera 15 tareas |
| R-11 | `QueueStalled` en falso por el latido | Latido de 1 min y límite de 6; ejecución tomada con latido vencido cuenta como atascada |
| R-12 | Una actualización de la biblioteca cambia su esquema o sus autoconfiguraciones | `ScheduledTasksSchemaIT` y `StarterAutoConfigurationExcludedTest` rompen la construcción |
| R-13 | `PARKED_AT` choca con una ejecución legítima en el año 9999 | Ningún calendario la alcanza; la prueba de calendario afirma próximas ejecuciones dentro de un año |

## 12. Matriz de amenazas

La matriz de la fase de diseño no aplica: el cambio no toca órdenes de consola, subprocesos, automatización
de Git o de PR, clasificación de archivos ejecutables ni integración con procesos externos. db-scheduler es
una biblioteca dentro del proceso.

| Frontera | Aplicabilidad |
|---|---|
| Rutas con apariencia de documentación | N/A: no se clasifican ni ejecutan archivos |
| Selección de repositorio Git, estado del commit, estado del push, órdenes de PR | N/A: sin automatización de Git ni de PR |

Fronteras de seguridad propias, cada una con prueba en rojo en la sección 8:

| Frontera | Comportamiento seguro | Prueba |
|---|---|---|
| Inyección de tarea falsa por un rol con `INSERT` (portal) | Solo JSON de tipos conocidos; carga sin texto libre; el manejador revalida bajo RLS | `TracedJobSerializationTest`, `PasswordResetIssuanceInstitutionIsolationIT` |
| Deserialización insegura | Bytes de objetos de Java rechazados sin instanciar | `TracedJobSerializationTest` |
| Datos personales en `task_data` | Tipos de carga restringidos y lista aprobada | `JobPayloadRulesTest`, `JobPayloadAllowListTest` |
| Ejecución fuera del trabajador | Cero `Scheduler` y cero hilos en administración y portal | `ProcessBeanIsolationTest`, `OnlyWorkerExecutesIT` |
| Borrado amplio por un rol de aplicación | Sin `DELETE`; funciones con piso, contexto y actor `system` | `RetentionPurgeFunctionsIT`, `RolePrivilegeMatrixIT` |
| Valores sensibles en registros del trabajador | Guardia antes del primer bean; saneo de excepciones | `WorkerSensitiveLoggingIT`, `JobFailureSanitizationIT` |
| Tarea envenenada | Intentos acotados y estacionamiento visible | `FailedExecutionIsParkedIT` |
| Alteración de la cadena de auditoría | Detección con la fila exacta, alerta S1, sin reparar | `AuditChainVerificationJobsIT` |

## 13. Decisiones del propietario (D-N1 a D-N3, decididas el 2026-10-24)

Las tres decisiones nuevas que este diseño elevó al propietario quedaron **decididas el 2026-10-24** y ya no
son preguntas abiertas. Se conserva el enunciado original y se registra la decisión.

**D-N1. Consecuencia de P6: el portal recibe su primer `DataSource`.** *Decidido por el propietario el
2026-10-24: opción a).*

- *a)* Se cablea en este cambio, como dice P6, con `confia_portal_app` y solo `TransactionRunner` y
  `JobScheduler` (decisión 14). Revierte D5 de `web-edge-foundations` y la nota de `docs/05` del
  2026-10-04 («`portal` y `worker` no abren conexión»), con nota fechada. **Elegida.**
- *b)* Se prueba el cliente con el rol real (`PortalSchedulingPrivilegeIT`, que de todos modos fija el
  `GRANT` de `V10`) y el cableado de producción del portal se difiere al primer caso de uso del portal que
  programe. Reduce la rebanada 3 en la costura 3b. **Descartada.**
- Consecuencias fijadas: el cableado se entrega en la sub-rebanada 3b; la especificación modifica los requisitos
  vigentes de `build-integrity` que decían que el portal no registra `DataSource`, `TransactionRunner` ni
  `DSLContext` (el portal sigue sin `DSLContext`); `docs/05` §6 recibe una nota fechada que revierte la del
  2026-10-04.

**D-N2. Ventana de la cadencia diaria.** *Decidido por el propietario el 2026-10-24: mes de calendario.* La
verificación diaria cubre desde las 00:00 del **primer día del mes anterior**, en la zona horaria de la
institución (`America/Tegucigalpa`), hasta el momento de la ejecución; no es una ventana móvil de 30 días ni «el
mismo día del mes anterior» (la redacción previa de la decisión 19).

**D-N3. ADR-0025.** *Decidido por el propietario el 2026-10-24: se crea el ADR.* ADR-0025, estado «Propuesto»,
registra las funciones `SECURITY DEFINER` de purga de P2 como regla verificable (decisión 22); ADR-0017 recibe
una nota fechada que apunta a ADR-0025, sin reescribir su cuerpo.

## 14. Preguntas abiertas

- [x] D-N1, D-N2 y D-N3 (sección 13): **decididas por el propietario el 2026-10-24.** D-N1 afecta la costura 3b.
- [ ] Las sondas P-1 a P-12 se resuelven con su prueba en rojo en la rebanada que las usa. Si P-1, P-2 o
  P-3 fallan, se aplica la alternativa B y se informa al propietario con una nota fechada antes de seguir.
- [ ] R-2: confirmar antes de R1 que la máquina de desarrollo puede descargar la dependencia.

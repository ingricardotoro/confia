# Progreso de aplicación: trabajos en segundo plano con db-scheduler (cambio 9 de F0)

- **Cambio:** `background-jobs-with-db-scheduler` (F0, cambio 9; entregable 12, criterio de salida 5)
- **Modo:** TDD estricto (`./mvnw verify -Pmutation-gate` en `apps/api`, JDK 25, Docker en ejecución)
- **Estrategia de entrega:** `stacked-to-main`, tope de 800 líneas efectivas por PR; prevalece sobre `single-pr` con 400 de la preflight (ver `tasks.md`)
- **Última actualización:** 2026-10-25 (rebanada 2b: tareas 1.5 y 1.6)
- **Regla de este archivo:** solo se añade; no se reescriben secciones existentes.

## Estado de las tareas

| Tarea | PR | Estado | Commits |
|---|---|---|---|
| 1.1 | PR 1 / 01a `01-scheduler-dependency-and-table` | Completa y verificada | `17bcae6` |
| 1.2 | PR 1 / 01a (condicional) | **No aplica**: P-1, P-2 y P-3 pasaron | — |
| 1.3 | PR 1 / 01b `01b-scheduled-tasks-table` | Completa y verificada (ver «Cierre de 01b» y «Desviaciones») | `cf4bfdf` |

## Nota de entorno (2026-10-24)

Maven no podía descargar artefactos nuevos de Maven Central: error PKIX porque el escaneo HTTPS de Avast (`CN=Avast Web/Mail Shield Root`) firma la
cadena con una raíz presente en el almacén de Windows y ausente de `cacerts` del JDK 25. **El propietario autorizó** ejecutar Maven con
`MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"`. Se exportó en cada orden `./mvnw` de esta rebanada, incluida la ejecución en segundo plano de
`verify -Pmutation-gate`. No se modificó ningún archivo del repositorio, `.mvn/` ni `cacerts`, y esta variable no figura en ningún archivo versionado.
Error original, antes de la autorización: `(certificate_unknown) PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException:
unable to find valid certification path to requested target` al descargar `db-scheduler-spring-boot-4-starter-16.12.0.pom`.

## Medición y partición de la rebanada 1

Construida en una sola rama y medida con `git diff --numstat main...HEAD -- . ':!openspec'`: **1 312 líneas efectivas** (frente a 700 a 900 de la estimación
realista). Supera 800, de modo que se parte **sin consultar** en la costura que la tarea nombra (tabla de la sección «Pronóstico y costuras»).

| Rama | Contenido | Líneas efectivas |
|---|---|---|
| `change/background-jobs-with-db-scheduler-01-scheduler-dependency-and-table` (01a) | Tareas 1.1 y 1.2: sondas, dependencia, exclusión total, inversión del caso vacuo | 376 |
| `change/background-jobs-with-db-scheduler-01b-scheduled-tasks-table` (01b, apilada sobre 01a) | Tarea 1.3: `V10`, matriz, sonda P-6, pruebas de catálogo | 936 (suma 1 312) |

01b queda **136 líneas por encima de 800** y la tarea 1.3 es una unidad sin costura nombrada: la tabla, su matriz de privilegios y la sonda P-6 que decide
uno de sus `GRANT` no se separan de sus pruebas (regla «las pruebas nunca se separan del código que prueban»). Se informa al orquestador en el informe final;
no se recortaron comentarios ni pruebas para ajustar la medida.

## Tarea 1.1 (rebanada 01a)

### Paso 0 (riesgo R-2) y sonda P-1

Ver la nota de entorno. Con `MAVEN_OPTS` autorizado, la resolución de `db-scheduler-spring-boot-4-starter:16.12.0` en un proyecto de prueba fuera del
repositorio terminó en `BUILD SUCCESS` y el árbol de dependencias incluyó `db-scheduler:16.12.0` y `db-scheduler-spring-common:16.12.0`. La lista de versiones
de Maven Central (`maven-metadata.xml`) da `latest` y `release` = **16.12.0** (actualización 2026-05-29) para el iniciador y el núcleo; no hay 16.13.x.

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO (caso antiguo) | `./mvnw -pl app -am verify -DskipITs -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ProcessBeanIsolationTest#dbSchedulerAbsenceIsVacuousUntilChange9'` con la dependencia agregada y el archivo de prueba original | 1 prueba, 1 fallo: «db-scheduler is now on the classpath…». Causa esperada |
| ROJO (nuevas) | Misma forma con `StarterAutoConfigurationExcludedTest`, `ProcessBeanIsolationTest` y `SchedulerDependencySingleSourceTest`, con la dependencia y **sin** exclusión | 22 pruebas, 6 fallos. `StarterAutoConfigurationExcludedTest`: 4 fallos nombrando las tres autoconfiguraciones sin excluir en `AdminApplication` y `PortalApplication`. `ProcessBeanIsolationTest.administrationAndPortalHold…` [admin]: `Scheduler` presente, bean `DbSchedulerAutoConfiguration` de la biblioteca y hilo `db-scheduler-execute-due-pool-*`; y `registersOnlyItsAllowedBeans` [admin]: «forbidden: only the worker runs db-scheduler (ADR-0016)». El contexto administrativo creó un ejecutor real (intentó conectarse a la base de datos inalcanzable). Causa esperada. **Desviación:** el caso del **portal** de `ProcessBeanIsolationTest` no falló, porque `PortalApplication` conserva `DataSourceAutoConfiguration` excluida y la autoconfiguración principal exige un `DataSource`; su exclusión queda cubierta por `StarterAutoConfigurationExcludedTest` |
| ROJO (`validate`) | `./mvnw validate` con la dependencia | `BUILD SUCCESS`: `dependencyConvergence` y `bannedDependencies` pasan (P-3). No hubo rojo de convergencia |
| Sin rojo propio | `SchedulerDependencySingleSourceTest` | 3 pruebas en verde desde la primera ejecución, porque los POM se editaron antes de correr la prueba. Su capacidad de fallar se demostró con un control negativo (abajo) |
| VERDE | `AdminApplication` y `PortalApplication` con `excludeName` de las tres clases; `app/pom.xml` con la dependencia y la exclusión de `scheduled_tasks` en jOOQ; `apps/api/pom.xml` con la propiedad y el `dependencyManagement` | 51 pruebas, 0 fallos (`StarterAutoConfigurationExcludedTest` 5, `ProcessBeanIsolationTest` 14, `SchedulerDependencySingleSourceTest` 3, `IdentityScopeExclusionInventoryTest` 15, `AuditScopeExclusionInventoryTest` 4, `SuppressionCitesAdrTest` 10). El `BUILD FAILURE` de la orden acotada es el de JaCoCo esperado |
| Refactor | Nota fechada con P-1 a P-9 en `design.md` §2.1; Javadoc de las dos clases de arranque | Sin cambio de comportamiento |

### Demostraciones deliberadas (con `cmp`)

| Ruptura | Prueba que falló | Aserción | `cmp` tras restaurar |
|---|---|---|---|
| Quitar `DbSchedulerMetricsAutoConfiguration` de `excludeName` en `AdminApplication` | `StarterAutoConfigurationExcludedTest` (2 fallos) | «auto-configurations of the db-scheduler starter not excluded in com.confia.bootstrap.admin.AdminApplication» con la clase nombrada | sin salida (idéntico a la copia fuera del repositorio) |
| Quitar todo `excludeName` de `PortalApplication` | `StarterAutoConfigurationExcludedTest` (2 fallos), «…not excluded in com.confia.bootstrap.portal.PortalApplication» | `ProcessBeanIsolationTest` **sigue en verde** (14 pruebas): ver la desviación del portal arriba. El análogo de administración se observó en el rojo | sin salida |
| Agregar `<version>16.12.0</version>` a la dependencia de `app/pom.xml` | `SchedulerDependencySingleSourceTest.noModuleRepeatsTheVersionOrUsesARange` | «app/pom.xml declares db-scheduler-spring-boot-4-starter without a version» | sin salida |
| Agregar `org.quartz-scheduler:quartz` a `app/pom.xml` | `./mvnw validate` (enforcer) | `Banned dependency … ADR-0016 forbids Quartz/JobRunr` | sin salida |

### Pruebas existentes tocadas

`IdentityScopeExclusionInventoryTest.noScheduledJobReencryptsARetiredDataEncryptionKeyYet` afirmaba que db-scheduler **no** estaba en el classpath
(`Class.forName` debía fallar). Con las tres aplicaciones compartiendo un solo artefacto eso deja de ser cierto, así que se retiró esa afirmación y se
conservaron las otras dos (ninguna clase de producción depende de una API de programación; la superficie de gestión de llaves no ofrece rotación). La
inversión completa de este inventario (sobre los `JobDefinition` registrados) pertenece a las tareas 2.1 y 3.x, como dice el diseño §6.

### Tarea 1.2

**No aplica.** P-1 (versión 16.12.0 resuelta), P-2 (el iniciador carga y arranca con Spring Boot 4.1.1; en el rojo creó su `Scheduler` y sus hilos) y P-3
(`dependencyConvergence` en verde) pasaron. La compatibilidad del `Scheduler` propio con el del iniciador en el trabajador se sondea en 1.6.

### Cierre de 01a

`./mvnw verify -Pmutation-gate` completo sobre la rama 01a (árbol limpio, sin `V10`), `BUILD SUCCESS` en **13 min 48 s** (834 s):

| Etapa | Resultado |
|---|---|
| Surefire | núcleo 186; aplicación 2 383; 0 fallos, 0 errores |
| Failsafe | 598; 0 fallos, 0 errores |
| JaCoCo | «All coverage checks have been met» (núcleo y aplicación) |
| PIT | núcleo: 192 de 194 mutantes muertos (99 %), fuerza de prueba 99 %, cobertura de líneas de las clases mutadas 229 de 233 (98 %). Aplicación: 676 de 710 (95 %), fuerza de prueba 96 %, 928 de 959 líneas (97 %) |

Se midió contra el presupuesto de 15 minutos de BI57: 13 min 48 s en 01a. Un `mvnw clean` falló una vez (`Failed to delete …\kernel\target\pit-reports\com.confia.kernel`, bloqueo de OneDrive sobre un
archivo de PIT); se borraron `kernel/target` y `app/target` a mano y la construcción se repitió sin `clean` desde cero.

### Desviaciones y precisiones de 01a respecto de `tasks.md` y el diseño

1. **Exclusión por nombre, no por clase.** El diseño pide `exclude`/`excludeName`; se eligió `excludeName` para las tres clases porque referenciarlas por clase
   haría que `com.confia.bootstrap` dependiera de `com.github.kagkarlsson`, lo que `AuditScopeExclusionInventoryTest` y la regla de confinamiento de la decisión 21
   prohíben. `StarterAutoConfigurationExcludedTest` lee `exclude()` y `excludeName()`, así que cubre ambas formas.
2. **El caso del portal de `ProcessBeanIsolationTest` no se pone en rojo al quitar la exclusión** (ver la tabla de demostraciones): el portal conserva
   `DataSourceAutoConfiguration` excluida, y la autoconfiguración principal del iniciador exige un `DataSource`. El rojo de BI102 para el portal lo da
   `StarterAutoConfigurationExcludedTest`; el de la prueba de aislamiento llegará con el `DataSource` del portal (tarea 2.6, D-N1).
3. **`IdentityScopeExclusionInventoryTest`** perdió su afirmación de ausencia de la biblioteca en el classpath (ver «Pruebas existentes tocadas»).
4. **P-2 solo parcialmente sondada**: se observó que el iniciador carga y arranca con Spring Boot 4.1.1, pero el reemplazo por el `Scheduler` propio del
   trabajador se prueba en 1.6.

## Tarea 1.3 (rebanada 01b)

### Sonda P-6 y su decisión

| Paso | Resultado observado |
|---|---|
| `V10` con `GRANT INSERT` solo para el portal | `PortalSchedulingPrivilegeIT`: `scheduleIfNotExists` falla con `permission denied for table scheduled_tasks` (`JdbcTaskRepository.getExecution`, línea 771, llamado desde `createIfNotExists`, línea 187) |
| Aislar `schedule` con `INSERT` solo (rol sin `SELECT`) | **También falla**: en el cliente estándar `schedule` llama a `scheduleIfNotExists` (`SchedulerClient.java`, líneas 441 y 447) |
| Decisión | `V10` concede `SELECT, INSERT` a `confia_portal_app`. Con ambos, el cliente programa; `UPDATE` y `DELETE` quedan denegados (`permission denied for table scheduled_tasks`) y las filas siguen ahí. Consecuencia anotada en `design.md` §2.1, en el comentario de `V10` y en `docs/07` §9: el privilegio lo justifica el cliente y no la salud; incluir `backgroundJobs` en el grupo de preparación del portal queda para los cambios 10 y 11 |

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO | `./mvnw -pl app -am verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='ScheduledTasksSchemaIT,ScheduledTasksNotCreatedAtRuntimeIT,PortalSchedulingPrivilegeIT,RolePrivilegeMatrixIT,V10SchemaDiffIT,MultiTenantSchemaIT' -Dfailsafe.failIfNoSpecifiedTests=false` sin `V10` | 68 pruebas, 2 fallos y 13 errores. Errores: `relation "scheduled_tasks" does not exist` (matriz, catálogo, comentario, columnas, índices) y `Flyway No migration with a target version 10 could be found` (`V10SchemaDiffIT`, `PortalSchedulingPrivilegeIT`, `aPrivilegeOfMore…`); `migration not found: V10__create_scheduled_tasks.sql`. Fallos de aserción: `MultiTenantSchemaIT` (la membresía exige `scheduled_tasks` y la tabla no existe). `AuditScopeExclusionInventoryTest.scheduledTasksAppearsOnlyInV10…`: «V10 is the migration of the scheduler table». Causa esperada |
| Sin rojo propio | `ScheduledTasksNotCreatedAtRuntimeIT` (3 pruebas) y `theReferenceDdlIsTheOneOfThePinnedLibraryVersion` | En verde sin `V10`: protegen contra una regresión y no contra una ausencia. La capacidad de fallar de la primera la da el fixture que crea la tabla (`aFixtureThatCreatesTheTableAtStartIsDetected`) |
| VERDE | Las mismas, con `V10` | 69 pruebas (con `AuditScopeExclusionInventoryTest`), 0 fallos tras ajustar la sonda P-6 (ver arriba) y un `GRANT USAGE` de la prueba sobre su esquema de trabajo |
| Refactor | `StepwiseDatabase.dataSourceAs`, `V9SchemaDiffIT.snapshot` y `Difference` pasan a visibilidad de paquete para reutilizarlos | Sin cambio de comportamiento |

Los checksums de Flyway de `V1` a `V9` registrados en `V10SchemaDiffIT` se calcularon con el algoritmo de Flyway (CRC32 de las líneas en UTF-8) sobre los
archivos sin cambios; el de `V8` (1681600631) coincide con el que `V9SchemaDiffIT` ya registraba, lo que valida el método.

### Demostraciones deliberadas (con `cmp`)

| Ruptura en `V10` | Prueba que falló | Aserción | `cmp` |
|---|---|---|---|
| `GRANT SELECT` en vez de `GRANT SELECT, INSERT` para `confia_portal_app` | `PortalSchedulingPrivilegeIT.thePortalRoleSchedules…` | El cliente no puede programar: `Failed to add new execution (task name: probe.portal-p6, instance id: if-not-exists)`. Además `RolePrivilegeMatrixIT` (2 fallos: «scheduled_tasks privileges that differ from the matrix») | sin salida |
| Agregar `DELETE` a `confia_portal_app` | `RolePrivilegeMatrixIT.scheduledTasksPrivilegesAreExactlyTheMatrixForTheFiveRoles` y `aPrivilegeOfMore…` (privilegio de más, nombra rol y `DELETE`); `PortalSchedulingPrivilegeIT` (el `DELETE` ya no se deniega) | Aserción de privilegio de más | sin salida |

**Nota sobre el primer caso.** La falla de `PortalSchedulingPrivilegeIT` ocurre como error del cliente (`SQLRuntimeException` por `permission denied … INSERT`) y no
como una aserción de AssertJ: es el comportamiento que la prueba debe detectar («el rol no puede programar»), pero no es una aserción de resultado en sentido
estricto; la de la matriz sí lo es.

### Desviaciones y precisiones respecto de `tasks.md` y el diseño

1. **P-6 difiere de lo esperado:** `schedule` también exige `SELECT` (ver arriba). `V10` concede `SELECT` al portal, como la tarea prevé para ese caso.
2. **`V10SchemaDiffIT`** (no nombrada en la tarea) cubre BI64 con el mismo método que `V9SchemaDiffIT`, y registra los checksums de `V1` a `V9`.
3. **`ScheduledTasksNotCreatedAtRuntimeIT`** no se apoya en una opción de creación automática, porque la biblioteca no tiene ninguna: el control negativo es
   un fixture que crea la tabla con el DDL de la biblioteca y debe ser detectado por el mismo registrador de sentencias y la misma comprobación de catálogo.
4. **`MultiTenantSchemaIT`** no necesitaba cambiar su lista de excepciones (ya nombra las cuatro tablas); se extrajo `isCovered` para probar con un fixture que
   una tabla que no es del catálogo y no tiene `institution_id` se rechaza (BI99).
5. **`AuditScopeExclusionInventoryTest`**: se invirtió solo la mitad de migraciones (BI97). La regla de confinamiento de la dependencia (BI97, tercera parte) llega
   con `DbSchedulerConfinementTest` en la tarea 2.5, como dice el diseño.

### Cierre de 01b

Primera ejecución de `./mvnw verify -Pmutation-gate` sobre 01b: **falló** en Failsafe (617 pruebas, 1 error): `V9SchemaDiffIT.v8WasNotEditedItsChecksum…`,
`FlywayValidate: Migrations have failed validation`. Causa: la prueba migra una base de datos hasta `V9` y valida contra todo el classpath, donde `V10` figura como
migración pendiente. Corrección: `StepwiseDatabase.validateUpTo(version)` (validación contra las migraciones hasta esa versión) en `V9SchemaDiffIT` y `V10SchemaDiffIT`,
y se retiró `validate()`, sin otros usuarios. Se detectó en el `verify` completo y no en la ejecución acotada, porque esta no incluía `V9SchemaDiffIT`.

Segunda ejecución, sobre la rama con la corrección, `BUILD SUCCESS` en **16 min 46 s** (1 006 s; «Total time 16:40 min» de Maven):

| Etapa | Resultado |
|---|---|
| Surefire | núcleo 186; aplicación 2 385; 0 fallos, 0 errores |
| Failsafe | 617; 0 fallos, 0 errores |
| JaCoCo | «All coverage checks have been met» |
| PIT | núcleo: 192 de 194 (99 %), fuerza de prueba 99 %, 229 de 233 líneas (98 %). Aplicación: 676 de 710 (95 %), fuerza de prueba 96 %, 928 de 959 líneas (97 %): sin cambio respecto de 01a, porque no se añadieron clases de producción mutadas |

**Presupuesto de BI57 (15 minutos): superado por 1 min 46 s en 01b** (13 min 48 s en 01a). Las 19 pruebas de integración añadidas (arranques de bases de datos
aisladas con `StepwiseDatabase`, cada una con su Flyway completo) son la causa probable de la diferencia (no se aisló con una medición); las rebanadas siguientes
(dos contextos reales por prueba, riesgo R-9) la presionarán más. Se informa al orquestador. Esta ejecución no se hizo con `clean` previo (el estado previo del
directorio `target` venía de ejecuciones acotadas de la misma rama).

Medida final de 01b: **936 líneas efectivas** (918 de código y pruebas más 18 de la nota de `docs/07` §9), 136 por encima de 800 (ver «Medición y partición»).

## Tarea 1.4 (rebanada 02a `neutral-log-guard`, 2026-10-24)

Rama `change/background-jobs-with-db-scheduler-02a-neutral-log-guard`, desde `main` en `2bc0621`. Commit de código `9825ec6`. **364 líneas efectivas**
(medidas con `git diff --numstat main...HEAD -- . ':!openspec'`), dentro de 800: no hay partición. Este PR no da `DataSource` a ningún proceso.

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO (compilación) | `./mvnw -pl app -am test-compile` con las pruebas movidas a `shared/logging`, ampliadas y nuevas, y la producción sin tocar | `exit=1`, 62 líneas `ERROR`: `cannot find symbol` / `package SensitiveLogGuard does not exist` en `SensitiveLogGuardPrefixesTest`, `SensitiveDataLoggingTest`, `MalformedRequestLoggingTest` y `SensitiveLogGuardInstallationTest` (clase en `com.confia.shared.logging`, inexistente). Causa esperada |
| ROJO (comportamiento) | Misma forma unitaria con la guardia movida e instalada por el inicializador, **sin** la línea `com.confia.shared.logging` en `ProcessBeanPolicy` | `ProcessBeanIsolationTest.registersOnlyItsAllowedBeans`: 3 fallos, uno por proceso: `process '<p>': bean 'sensitiveLogGuard' from package 'com.confia.shared.logging' - not in the allow-list`. Causa esperada |
| ROJO (observador) | `SensitiveLogGuardInstallationTest` con `--context.initializer.classes` | 3 fallos «non-vacuous: the observer ran»: en Spring Boot 4 esa propiedad no activó el inicializador. **Desviación:** el observador se registra en `src/test/resources/META-INF/spring.factories` y actúa solo si el contexto fija `confia.test.observe-log-guard=true` |
| VERDE | Línea de política en los tres procesos | 118 pruebas, 0 fallos (prefijos 22, instalación 4, aislamiento 14, `SensitiveDataLoggingTest` 4, `MalformedRequestLoggingTest` 3, modulith, capas, inventarios, traducción de problemas) |
| Refactor | Una sola lista `PROTECTED_PREFIXES` (verificada por una prueba de ArchUnit); `afterPropertiesSet`/`destroy` pasan a `install`/`uninstall` | Sin cambio de comportamiento |

### Demostraciones deliberadas (con `cmp`)

| Ruptura | Prueba que falló | Aserción | `cmp` |
|---|---|---|---|
| Instalar la guardia en `ContextRefreshedEvent` en vez de en el inicializador | `SensitiveLogGuardInstallationTest` (3 fallos) | «exactly one guard in the chain when bean creation begins in admin / portal / worker» | sin salida |
| Quitar `org.postgresql` de `PROTECTED_PREFIXES` | `SensitiveLogGuardPrefixesTest.theDataAccessStackNeverLogsBelowInfo` | «[DEBUG org.postgresql]» | sin salida |

### Verificación completa

Todas con `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` y `kernel/target` y `app/target` borrados antes.

| Orden | Resultado |
|---|---|
| `./mvnw verify` (sin perfil), primera ejecución | 8 min 8 s (488 s). **Falló** en `IdempotencyEdgeIT.noLogLineCarriesTheKeyOrThePayloadWhateverTheOutcome`: ver «Prueba existente tocada» |
| `./mvnw verify` (sin perfil), tras la corrección | **`BUILD SUCCESS` en 11 min 13 s (681 s)**: Surefire 186 + 2 404, Failsafe 617, 0 fallos. Dentro del presupuesto de 15 min de BI57. La diferencia con la primera ejecución (488 s, con el mismo número de pruebas) es ruido de la máquina; no se aisló |
| `./mvnw verify -Pmutation-gate` | **`BUILD SUCCESS` en 14 min 59 s (904 s)**. Surefire 186 + 2 404, Failsafe 617, 0 fallos. JaCoCo: «All coverage checks have been met». PIT: núcleo 192 de 194 (99 %), fuerza 99 %, 229 de 233 líneas; aplicación 676 de 710 (95 %), fuerza 96 %, 928 de 959 líneas. Sin cambio: no hay clases mutadas nuevas |

Una primera ejecución del `mutation-gate` se interrumpió a propósito al descubrir el fallo de `IdempotencyEdgeIT`, y no cuenta.

### Prueba existente tocada

`IdempotencyEdgeIT` armaba su contexto con `WebEdgeConfiguration`, que declaraba la guardia como bean. Con la guardia fuera de ese bean, `org.springframework.web` en `DEBUG`
registró el cuerpo (`Read "application/json" to [{"note":"SECRET-PAYLOAD-…"}]`) y la prueba de «ninguna línea lleva la clave ni la carga» falló. Corrección: la prueba pone una
copia de `SensitiveLogGuard` en la cadena de Logback durante su captura, como hace el inicializador en los procesos, y la retira al terminar. Las otras dos pruebas con
`WebEdgeConfiguration` (`RequestOriginAuditIT`, `RequiredDelayMaterializerIT`) no leen registros y pasaron sin cambio.

### Desviaciones y precisiones

1. **El inicializador del observador** (arriba): `spring.factories` de pruebas con propiedad de activación, porque `context.initializer.classes` no funcionó.
2. **`SensitiveLogGuardInstallationTest` vive en `com.confia.bootstrap`**, no en `shared/logging`: necesita `ConfiaApplication.launch` y `TestProcessArguments`, de visibilidad de paquete.
3. **`com.confia.shared.logging` en la lista de permitidos de los tres procesos ya en este PR** (la tarea lo escribe en 1.5 para el trabajador): el singleton de la guardia existe en los tres desde ahora.
4. **El arnés de pruebas web** (`HarnessProcess`) añade el mismo inicializador; la guardia sigue disponible como bean por `registerSingleton`.
5. **Instalar elimina copias anteriores** de la guardia en la cadena (un contexto que falla en el arranque no emite `ContextClosedEvent` y dejaría una copia).

## Tarea 1.5 (rebanada 2b-1 `worker-datasource-and-condition-4`, 2026-10-25)

Rama `change/background-jobs-with-db-scheduler-02b-worker-datasource-and-condition-4`, desde `main` en `14e9922`. Commits de código `2c4d733` (trabajador con
`DataSource`) y `0b83744` (nota de `docs/05` §6). **764 líneas efectivas** medidas con `git diff --numstat main...HEAD -- . ':!openspec'`.

### Partición de la rebanada 2b

La tarea 1.6 se estimó en 500 líneas realistas; con 764 de 1.5 el total pasaría de 800, de modo que se parte **sin consultar** en la costura nombrada:
2b-1 = tarea 1.5 (esta rama) y 2b-2 = tarea 1.6 (`...-02b2-worker-scheduler-shell`, apilada sobre esta).

### Condición 4: orden de commits y fusiones

La guardia (tarea 1.4) se fusionó en `main` en `14e9922` (PR #191) **antes** de que existiera una sola línea con `DataSource` del trabajador. El primer commit con ese
`DataSource` es `2c4d733`, sobre `14e9922`. Ningún commit de `main` da un `DataSource` al trabajador sin la guardia.

### Evidencia del ciclo TDD

| Paso | Orden | Resultado observado |
|---|---|---|
| ROJO (compilación) | `./mvnw -pl app -am test-compile` con las pruebas nuevas | `exit=1`, 30 líneas `ERROR`: `cannot find symbol` para `TransactionalDataSource` (clase y variable) en `TransactionalDataSourceTest` y `WorkerProductionWiringTest`. Causa esperada |
| ROJO (lista de permitidos) | Pruebas con el trabajador ya cableado y **sin** las líneas del trabajador en `ProcessBeanPolicy` | `ProcessBeanIsolationTest.registersOnlyItsAllowedBeans` [worker]: 8 beans `not in the allow-list` nombrando bean y paquete (`shared.security`: `TransactionRunnerConfiguration`, `transactionalDataSource`, `transactionRunner`; `shared.jobs.infrastructure.wiring`: `WorkerPlatformConfiguration`, `dslContext`, `clock`, `auditLogWriter`; `shared.infrastructure`: `auditLogWriter`). Y `WorkerProductionWiringTest.aCryptoBeanInTheWorker…`: sin la prohibición de `shared.crypto` no aparece «the worker holds no column-encryption key». Causa esperada |
| ROJO (ejecutor) | `WorkerProductionWiringTest#theWorkerHasNoSchedulerAndNoExecutorThreadYet` con el trabajador ya con `DataSource` | Falla: «Expecting empty but was: ["scheduler"]». El iniciador de db-scheduler, al ver un `DataSource`, creó su `Scheduler` con valores por omisión (y 30 s de espera de conexión por arranque en `ProcessBeanIsolationTest`). Se excluyen sus tres autoconfiguraciones en `WorkerApplication` hasta la tarea 1.6 (desviación) |
| VERDE | Las mismas pruebas con la política, el cableado y la exclusión | 85 pruebas unitarias en verde y `WorkerSensitiveLoggingIT` (2) en verde |
| Refactor | `SensitiveLogGuard.protectedPrefixes()` público para la prueba de proceso real; `TestProcessArguments.workerAgainst` | Sin cambio de comportamiento |

### Condición 4: resultado de `WorkerSensitiveLoggingIT`

Trabajador real arrancado por `ConfiaApplication.launch` contra PostgreSQL real (rol `confia_admin_app`, URL sin el `loggerLevel=OFF` que Testcontainers añade), con la raíz y
**cada** prefijo protegido en `TRACE`; un valor distintivo (`valor-distintivo-7f3a`) pasa por `set_config` (el actor del contexto de seguridad) y por una consulta del `DSLContext` real.

- Con la guardia: ningún evento de ningún campo (mensaje, plantilla, argumentos, MDC, traza) contiene el valor [BI89].
- Sin la guardia (control negativo): el valor aparece [BI90]. **Probado:** `org.postgresql` (el controlador registra los parámetros de `Bind` en `QueryExecutorImpl`
  a `TRACE`). **Preventivos** (sin fuga observada): `org.jooq` (la ejecución ya está apagada con `withExecuteLogging(false)`), `com.zaxxer.hikari`,
  `org.springframework.jdbc`, `org.springframework.transaction`, `com.github.kagkarlsson` y los prefijos web. Cierra la sonda P-11.

### Demostraciones deliberadas (con `cmp`)

| Ruptura | Prueba que falló | Aserción | `cmp` |
|---|---|---|---|
| Quitar `.initializers(new SensitiveLogGuardInitializer())` del trabajador en `ConfiaApplication` | `WorkerSensitiveLoggingIT.theBoundValueNeverAppears…` | «Expecting empty but was: org.postgresql.core.v3.QueryExecutorImpl TRACE message contains valor-distintivo-7f3a» [BI89, BI91] | sin salida |
| Quitar el mismo inicializador (otra prueba) | `WorkerProductionWiringTest.theRealWorkerHasADataSourceAndTheGuardIsActive…` | La regla de «DataSource sin guardia activa» informa la violación [BI91] | sin salida |
| Construir el `DSLContext` del trabajador sobre un segundo `TransactionAwareDataSourceProxy` | `WorkerProductionWiringTest.theDslContextAndTheTransactionalDataSourceShareTheOneProxy[worker]` y `WorkerPlatformConfigurationTest` | «does not use the transaction-aware proxy» [BJ46] | sin salida |

La primera ejecución de la primera demostración no fue válida: la prueba pedía el bean de la guardia sin haberlo y fallaba con `NoSuchBeanDefinition`, no por el valor.
Se corrigió la prueba para obtener el bean solo en el control negativo y se repitió.

### Verificación completa (árbol `target` borrado antes de cada una)

| Orden | Resultado |
|---|---|
| `./mvnw verify` | **`BUILD SUCCESS` en 8 min 8 s (488 s)**: Surefire 186 + 2 421, Failsafe 619, 0 fallos. Dentro del presupuesto de 15 min de BI57 |
| `./mvnw verify -Pmutation-gate` | `BUILD SUCCESS` en 13 min 19 s (799 s): mismas pruebas; JaCoCo cumplido; PIT núcleo 192/194 (99 %), aplicación 676/710 (95 %), fuerza 96 %. Sin clases mutadas nuevas |

### Desviaciones y precisiones de 1.5

1. **Pruebas de proceso en `com.confia.bootstrap`**, no en `shared/logging`: necesitan `ConfiaApplication.launch` y `TestProcessArguments`, de visibilidad de paquete.
2. **El iniciador de db-scheduler sigue excluido en el trabajador** (tres autoconfiguraciones por nombre) hasta la tarea 1.6, que retira la exclusión de la principal. La tarea no lo
   preveía; sin ello el trabajador arrancaba un ejecutor con valores por omisión al tener `DataSource`.
3. **`ProcessBeanPolicy.WORKER` solo trae las líneas que contribuyen un bean hoy** (`shared.security`, `shared.infrastructure`, `shared.jobs.infrastructure.wiring`, además de
   `shared.logging` de 1.4): la comprobación de no vacuidad exige que cada paquete permitido aporte un bean. `shared.audit`, `shared.infrastructure.wiring` y las capas de `identity`
   llegan con el primer bean que las use. Se añadió la prohibición `com.confia.shared.crypto`.
4. **BI115 se aplica al trabajador y a administración; el portal queda exento de forma explícita** (`STILL_EXCLUDING`) hasta que la tarea 2.6 le dé su `DataSource` (D-N1); la prueba afirma
   que sigue excluyéndolo, para que la exención se retire a propósito.
5. **No se añadieron** `confia.jobs.institutions` ni `confia.jobs.executor.autostart` a `TestProcessArguments` todavía: los lee la tarea 1.6.
6. **`package-info` con `@NamedInterface`** en `shared.jobs.infrastructure.wiring` (consumidor: `bootstrap`), porque un punto de entrada lo importa (ADR-0022).
7. **`SqlLoggingTest`** pasa a construir el `DSLContext` de producción con `TransactionalDataSource`; `AdminProductionWiringTest.portalAndWorkerHoldNoDataSource` queda solo para el portal.

## Tarea 1.6 (rebanada 2b-2 `worker-scheduler-shell`, 2026-10-25)

Rama `change/background-jobs-with-db-scheduler-02b2-worker-scheduler-shell`, apilada sobre 2b-1. Commits `c6f64ae` (ejecutor) y `c554b29` (nota de `docs/05`).
**937 líneas efectivas** sobre 2b-1 (código 926, `docs/05` 11), 137 por encima de 800: la tarea 1.6 no tiene costura nombrada propia y sus pruebas no se separan de su código; se informa al orquestador.
Total de 2b: 764 + 937 = 1 701.

### Evidencia del ciclo TDD

| Paso | Resultado observado |
|---|---|
| ROJO (compilación) | `test-compile` con `ConfiguredInstitutionsTest`, `WorkerSchedulerStarterTest`, `ConfiguredInstitutionsStartupTest` y `SchedulerStarterCompatibilityTest`: `cannot find symbol` para `ConfiguredInstitutions` y `WorkerSchedulerStarter`. Causa esperada |
| ROJO (lista de permitidos) | `ProcessBeanIsolationTest.registersOnlyItsAllowedBeans` [worker]: `configuredInstitutions` y `workerSchedulerStarter` «not in the allow-list», paquete `shared.jobs.infrastructure`; se añadió la línea |
| ROJO (captura de registros) | `JobsConfigurationGuardTest` sin eventos: Boot reinicia Logback al arrancar y desprende el appender previo. Desviación: un inicializador de prueba (`spring.factories`) instala la captura tras ese reinicio |
| ROJO (inventarios) | En el `verify` completo: `AuditScopeExclusionInventoryTest` e `IdentityScopeExclusionInventoryTest` fallaron porque `WorkerJobsConfiguration` depende de db-scheduler. Se admite solo desde `com.confia.shared.jobs.infrastructure..` (con su prueba de la exención); el resto de la regla sigue |
| VERDE | `WorkerStaysAliveTest` ejecuta el lanzador real en una JVM hija: sigue viva tras estar lista y, con `keep-alive=false`, termina sola. Inversión de `theWorkerHasNoScheduler…` en `ProcessBeanIsolationTest.onlyTheWorkerHoldsAScheduler` (exactamente uno en el trabajador, cero en los otros) con fixtures de un ejecutor sobrante y de uno ausente |

### Demostraciones deliberadas (con `cmp`)

| Ruptura | Prueba que falló | Aserción | `cmp` |
|---|---|---|---|
| Declarar un segundo `Scheduler` en `WorkerJobsConfiguration` | `SchedulerStarterCompatibilityTest` y `ProcessBeanIsolationTest.onlyTheWorkerHoldsAScheduler` | «exactly one Scheduler» / «expected exactly one Scheduler bean(s) but found 2 [scheduler, secondScheduler]» [BJ47, BI96] | sin salida |
| Quitar `spring.main.keep-alive` de `application.yml` | `WorkerStaysAliveTest.theWorkerJvmKeepsRunningWithoutAWebServer` | «the worker JVM must still be running» [BJ50] | sin salida |

La primera ejecución de la segunda ruptura borró de más líneas del YAML y falló por otra causa; se restauró con `cmp` y se repitió con la ruptura exacta.

### Desviaciones

1. El `Scheduler` se construye sin `@ConfigurationProperties` propio: usa `DbSchedulerProperties` de la biblioteca (el iniciador sigue activo en el trabajador). El dialecto PostgreSQL se fija explícito para no abrir una conexión al construirlo (sin eso, 30 s de espera por arranque con la base caída).
2. La exclusión temporal del iniciador en `WorkerApplication` (1.5) se retira aquí.
3. `TestProcessArguments` pasa `confia.jobs.institutions` y `confia.jobs.executor.autostart=false` al trabajador.
4. `ProcessBeanIsolationTest`: la afirmación de observabilidad se estrechó a `shared.observability.metrics`.

### Verificación completa (árbol `target` borrado antes de cada una)

| Rama | `./mvnw verify` | `./mvnw verify -Pmutation-gate` |
|---|---|---|
| 2b-1 (tarea 1.5) | **8 min 8 s (488 s)**, Surefire 186 + 2 421, Failsafe 619, 0 fallos | 13 min 19 s (799 s), verde |
| 2b-2 (tarea 1.6) | **11 min 48 s (708 s)**, Surefire 186 + 2 454, Failsafe 619, 0 fallos | `BUILD SUCCESS` en 21 min 10 s (1 270 s), que incluye una pausa de la máquina durante la interrupción 429; Surefire/Failsafe iguales, JaCoCo cumplido, PIT núcleo 192/194 (99 %), aplicación 676/710 (95 %), fuerza 96 % |

El presupuesto de 15 min de BI57 (decisión G-4) aplica solo a `verify` sin perfil: 8 min y 11 min 48 s, dentro.

### Partición de 2b-2 en 02b2 y 02b3 (2026-10-25, decisión del propietario)

La rama `...-02b2-worker-scheduler-shell` (937 líneas) se rehízo en dos PR apilados sobre 2b-1; la rama antigua y la referencia local `backup/02b2-before-split` se conservan.
Las cifras de arriba (937 líneas, tiempos de 2b-2) describen la rama antigua; las de las ramas nuevas son estas.

| Rama | Contenido | Líneas efectivas |
|---|---|---|
| `change/background-jobs-with-db-scheduler-02b2-configured-institutions` (`52d3958`, `c4e96b7`) | `ConfiguredInstitutions`, su bean en `WorkerJobsConfiguration`, línea de política y argumentos de prueba, `ConfiguredInstitutionsTest`, `ConfiguredInstitutionsStartupTest`, nota de `docs/05` | 276 |
| `change/background-jobs-with-db-scheduler-02b3-worker-scheduler-shell` (`90cc7fc` y el commit de estos registros) | `Scheduler`, `WorkerSchedulerStarter`, `application.yml`, resto de pruebas e inventarios | 683 |

La costura es limpia: 02b2 compila y pasa sola sobre 2b-1, sin `Scheduler` (el iniciador de db-scheduler sigue excluido en el trabajador, como en 1.5).

| Rama | `verify` | `verify -Pmutation-gate` |
|---|---|---|
| 02b2 | `BUILD SUCCESS` en 1 305 s; Surefire 186 + 2 437, Failsafe 619 | `BUILD SUCCESS` en 1 117 s; JaCoCo cumplido; PIT 192/194 y 676/710 |
| 02b3 | `BUILD SUCCESS` en 665 s (11 min 5 s); Surefire 186 + 2 454, Failsafe 619 | `BUILD SUCCESS` en 979 s (16 min 19 s); JaCoCo cumplido; PIT 192/194 y 676/710 |

Los 1 305 s y 1 117 s de 02b2 son atípicos frente a ejecuciones equivalentes (488 s y 799 s en 2b-1): la máquina estuvo lenta durante esas corridas; no se aisló la causa. El presupuesto de 15 min (G-4) aplica solo a `verify` sin perfil: 02b3 cumple (665 s); 02b2 lo excedió por esa lentitud y conviene repetirla antes de fusionar.

Prueba de igualdad de árbol: `git diff backup/02b2-before-split change/background-jobs-with-db-scheduler-02b3-worker-scheduler-shell -- . ':!openspec'` no produce salida.

## Rebanada 3a, tarea 2.1: partición en tres PR (2026-10-26)

La tarea 2.1 midió **1 725 líneas efectivas** en una sola rama (puerto y reglas 1 192, serializador 533). Supera 800 y `tasks.md` nombra dos commits pero ninguna
costura de PR dentro de la tarea; se partió por archivos completos, con sus pruebas, en tres ramas apiladas. **La costura entre 03a1a y 03a1b no está nombrada en
`tasks.md`: se informa al propietario.** La rama completa se conserva como `backup/03a1-before-split`.

| Rama | Contenido | Líneas efectivas |
|---|---|---|
| `change/background-jobs-with-db-scheduler-03a1a-jobs-value-types` | `JobType`, `RetryPolicy`, `JobMeta`, `ActorKind`, `TracedJob`, `JobInstanceId`, sus pruebas, PIT sobre `shared.jobs.*` | 579 |
| `...-03a1b-jobs-declarations` | `JobPayloadRules`, puerto, manejadores, calendarios, definiciones, `package-info` | 639 |
| `...-03a1c-task-data-serializer` | `TracedJobSerializer`, `JobDataRejectedException`, nota de `docs/07` §3.3 | 544 |

### 03a1a: evidencia

- ROJO: `test-compile` sin producción: `cannot find symbol` para `RetryPolicy`, `JobMeta`, `JobInstanceId`, `JobType`, `TracedJob` (causa esperada: paquete `com.confia.shared.jobs` inexistente).
- VERDE: 47 pruebas (`RetryPolicyTest` 13, `JobInstanceIdProperties` 6 con vector fijo, `JobTypeTest` 20, `JobMetaTest` 4).
- Demostración deliberada (en la rama completa, restaurada con `cmp` sin salida): cambiar `"v1|"` por `"v2|"` en `JobInstanceId.derive` pone en rojo `theDerivationMatchesTheFixedVector` y `theVersionTwoPrefixWouldProduceADifferentValue`.
- `./mvnw verify` (target borrado): `BUILD SUCCESS` en **1 119 s (18 min 39 s)**; Surefire 186 + 2 491, Failsafe 619, 0 fallos. **Supera el presupuesto de 15 min de BI57**; la máquina estuvo lenta (la misma suite medía 665 s en 02b3) y no se aisló la causa.
- `./mvnw verify -Pmutation-gate` (target borrado): `BUILD SUCCESS` en **905 s (15 min)**; JaCoCo cumplido; PIT núcleo 192/194 (99 %), aplicación 733/777 (94 %), fuerza 96 %.
- Una primera ejecución del `mutation-gate` coincidió con otra lanzada tras una interrupción y falló por borrado cruzado de `target`; no cuenta.

### 03a1b: evidencia (639 líneas efectivas)

- ROJO: `test-compile` sin producción (rama completa): `cannot find symbol` para `JobDefinition`, `OneTimeJob`, `RecurringJob`, `JobContext`, `RecurringRun`, `JobCalendar`; `JobPayloadRules` inexistente. Causa esperada.
- VERDE: 40 pruebas (`JobPayloadRulesTest` 26, `JobDefinitionsTest` 14).
- Demostración deliberada (mismo archivo, sobre la rama completa; restaurado con `cmp` sin salida): admitir `String.class` en `JobPayloadRules` pone en rojo `JobPayloadRulesTest` (3 fallos: «Expecting code to raise a throwable» en el componente `email`, y el recuento de violaciones) [BJ52].
- `./mvnw verify`: `BUILD SUCCESS` en **772 s**, Surefire 186 + 2 531, Failsafe 619, dentro del presupuesto de 15 min.
- `./mvnw verify -Pmutation-gate`: `BUILD SUCCESS` en **1 046 s**; JaCoCo cumplido; PIT núcleo 192/194 (99 %), aplicación 765/816 (94 %).

### 03a1c: evidencia (544 líneas efectivas)

- ROJO: `test-compile` sin producción (rama completa): `cannot find symbol` para `TracedJobSerializer` y `JobDataRejectedException`. Causa esperada.
- VERDE: 24 pruebas de `TracedJobSerializationTest`, a la primera. Sondas: P-4 pasa (`Jackson3Serializer` acepta un `JsonMapper`); en Jackson 3 `WRITE_DATES_AS_TIMESTAMPS` está en `tools.jackson.databind.cfg.DateTimeFeature`.
- Demostración deliberada (restaurada con `cmp` sin salida): quitar `FAIL_ON_UNKNOWN_PROPERTIES` pone en rojo 3 pruebas (`anUnknownFieldInTheEnvelopeIsRejected`, `...InThePayload...`, `...InMeta...`): «Expecting code to raise a throwable» [BJ54]. La demostración 1 de la tarea (`String` en `JobPayloadRules`) y la 3 (`"v2|"`) están en 03a1b y 03a1a.
- `./mvnw verify`: `BUILD SUCCESS` en **680 s**, Surefire 186 + 2 556, Failsafe 619. `./mvnw verify -Pmutation-gate`: `BUILD SUCCESS` en **1 017 s**; JaCoCo cumplido; PIT aplicación 796/850 (94 %).

### Desviaciones de la tarea 2.1

1. Partición en tres PR (costura entre 03a1a y 03a1b no nombrada en `tasks.md`).
2. El serializador del iniciador no conoce la tarea cuando la biblioteca lo llama: ese camino nombra la tarea `(unresolved)`; `serialize(taskName, job)`, `deserialize(taskName, bytes)` y `payloadOf` sí la nombran y los usan el adaptador y `JobRunner`.
3. `package-info` de `shared.jobs` lleva `@NamedInterface` sin consumidor externo todavía (`identity` y `shared.audit` llegan en los cambios siguientes).

## Tarea 2.2 (rebanada 03a2 `scheduler-client-and-atomicity`, 2026-10-26)

Rama `change/background-jobs-with-db-scheduler-03a2-scheduler-client-and-atomicity`, apilada sobre 03a1c. Commit de código `d062346`. **793 líneas efectivas** (dentro de 800).

### Evidencia del ciclo TDD

| Paso | Resultado observado |
|---|---|
| ROJO (compilación) | `test-compile` con las pruebas y sin producción: 24 errores `cannot find symbol` para `DbSchedulerJobScheduler`. Causa esperada |
| ROJO (lista de permitidos) | `ProcessBeanIsolationTest.registersOnlyItsAllowedBeans` [admin] con `JobSchedulingConfiguration` importada y sin las líneas de política: 5 beans «not in the allow-list» (`JobSchedulingConfiguration`, `tracedJobSerializer` y `jobScheduler`, en `shared.jobs.infrastructure` y `.wiring`). Causa esperada |
| VERDE | `JobSchedulingGuardsTest` 6, `JobSchedulingAtomicityIT` 4, `JobSchedulingGuardsIT` 7, `JobSchedulingRetriedTransactionIT` 2; aislamiento, cableado y modulith en verde (76 pruebas unitarias dirigidas) |

Sonda P-7: con el constructor del cliente de 16.12.0 (el `Builder` no ofrece la opción y fija `commitWhenAutocommitDisabled=false`), otra conexión no ve la fila antes de la confirmación y una reversión la elimina; no hay nada que fijar.

### Demostraciones deliberadas (con `cmp`)

| Ruptura en `DbSchedulerJobScheduler` | Prueba que falló | Aserción | `cmp` |
|---|---|---|---|
| Cliente sobre el `DataSource` crudo en vez del proxy | `JobSchedulingAtomicityIT` (2 fallos: `aRolledBack...` y `theTaskIsInvisible...`) | `expected: 0L` (la fila persiste tras el `ROLLBACK` / es visible antes de confirmar) [BJ02, BJ03, BJ05] | sin salida |
| `schedule` en vez de `scheduleIfNotExists` | `JobSchedulingGuardsIT` (2 fallos) | el resultado esperado `ALREADY_SCHEDULED` no aparece | sin salida |

**Desviación.** En 16.12.0 `schedule` delega en `scheduleIfNotExists` (sonda P-6), así que la ruptura 2 no aborta la transacción como supone `tasks.md`: falla por la aserción del resultado (`ALREADY_SCHEDULED`), que es la observable.

### Verificación completa (target borrado antes de cada una)

| Orden | Resultado |
|---|---|
| `./mvnw verify` | `BUILD SUCCESS` en **959 s (16 min)**; Surefire 186 + 2 563, Failsafe 632. **Supera 15 min de BI57** (máquina lenta; 03a1c midió 680 s) |
| `./mvnw verify -Pmutation-gate` | `BUILD SUCCESS` en **1 245 s**; JaCoCo cumplido; PIT núcleo 192/194, aplicación 808/866 (93 %) |

### Desviaciones de 2.2

1. La comprobación de institución lee `app.institution_id` de la propia transacción (`current_setting`), como pide el diseño, y no del llamador.
2. `JobSchedulingGuardsTest` (sin base de datos) cubre BJ43 y los rechazos previos; BJ44/BJ45/BJ14/BJ53 están en `JobSchedulingGuardsIT`, que necesita el motor.
3. `WorkerProductionWiringTest` gana la comprobación de que el planificador de administración usa el mismo proxy (BJ46).

## Tarea 2.3 (rebanadas 03a3a, 03a3b y 03a3c, 2026-10-26)

La tarea midió 1 191 líneas efectivas sin `DeadExecutionRevivalIT` ni `HandlerIdempotenceIT`; la costura nombrada (esos dos a su rama) no alcanza. Se partió por archivos
completos en tres ramas apiladas: **03a3a** = producción y pruebas unitarias; **03a3b** = arnés del trabajador y las pruebas de integración de ejecución, estacionamiento,
saneo y contexto; **03a3c** = `DeadExecutionRevivalIT` y `HandlerIdempotenceIT`. La costura 03a3a/03a3b (unidad frente a integración sobre el proceso real) no está nombrada en `tasks.md`: se informa al propietario.

### 03a3a `one-time-runner` (523 líneas efectivas)

Rama `change/background-jobs-with-db-scheduler-03a3a-one-time-runner`, apilada sobre 03a2. Contenido: `JobRunner`, `JobExecutionFailedException`, `BoundedRetryFailureHandler`, `ParkedExecutions`, `WorkerJobsConfiguration`
(serializador, `JobRunner`, una tarea por `OneTimeJob`, nombres repetidos prohibidos [BJ63]) y sus pruebas unitarias (`JobRunnerTest` 7, `BoundedRetryFailureHandlerTest` 4, `WorkerJobsTasksTest` 3).

- `./mvnw verify`: `BUILD SUCCESS` en **660 s**, Surefire 186 + 2 577, Failsafe 632. `./mvnw verify -Pmutation-gate`: `BUILD SUCCESS` en **1 151 s**; JaCoCo cumplido; PIT aplicación 835/901 (93 %).
- Nota de orden: por un descuido las pruebas de integración de 03a3b se escribieron junto con la producción y se corrieron en verde a la primera tras corregir sus propias aserciones; el rojo de esas pruebas se documenta en 03a3b con las demostraciones deliberadas.

### 03a3b `one-time-execution-its` (668 líneas efectivas)

Rama `change/background-jobs-with-db-scheduler-03a3b-one-time-execution-its`, apilada sobre 03a3a. Contenido: `WorkerHarness` (el trabajador real con `WorkerApplication`, sondeo y latido de 1 s, más una configuración de pruebas con los tipos de prueba),
`Eventually` (espera acotada por condición, nunca un `sleep` fijo), `WorkerJobIT`, `OnlyWorkerExecutesIT` (2), `FailedExecutionIsParkedIT` (3), `JobFailureSanitizationIT` (1) y `JobContextIT` (2).

| Paso | Resultado observado |
|---|---|
| ROJO (verde a la primera, sin rojo propio) | Las pruebas de integración se escribieron tras la producción de 03a3a (ver la nota de orden de 03a3a). Su capacidad de fallar se demuestra con las rupturas de abajo. Primeras ejecuciones: 3 fallos por aserciones de las propias pruebas, corregidos: la política de la biblioteca hace 4 ejecuciones con `maxRetries(3)` (3 reintentos más la primera), el recuento de filas visibles incluye el génesis de la cadena de auditoría, y Spring Boot reinicia Logback al arrancar y desprende un `Appender` añadido antes (se adjunta después de arrancar) |
| VERDE | 8 pruebas de integración en verde |

Demostraciones deliberadas (restauradas con `cmp` sin salida):

| Ruptura | Prueba que falló | Aserción |
|---|---|---|
| `BoundedRetryFailureHandler` reemplazado por `maxRetries(...).thenRemove()` de la biblioteca en `WorkerJobsConfiguration` | `FailedExecutionIsParkedIT.aJobThatAlwaysFailsIsAttemptedThreeTimesAndParkedNeverDeleted` | «Expecting actual not to be null»: la fila ya no existe [BJ23, BJ59] |
| `JobRunner` relanza la excepción original en vez de `JobExecutionFailedException` | `JobFailureSanitizationIT` | «Expecting no elements»: el valor distintivo aparece en un evento [BJ60] |
| `JobRunner` pone `actorKind=staff` en el MDC | `JobContextIT` | `actorKind="staff" (expected: "system")` [BJ16] |

- `./mvnw verify`: `BUILD SUCCESS` en **683 s**, Surefire 186 + 2 577, Failsafe 640. `./mvnw verify -Pmutation-gate`: `BUILD SUCCESS` en **1 360 s**; JaCoCo cumplido; PIT aplicación 854/901 (95 %).

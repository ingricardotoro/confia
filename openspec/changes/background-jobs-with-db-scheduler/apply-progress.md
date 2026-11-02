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

### 03a3c `revival-and-idempotence` (costura nombrada de `tasks.md`; 2.3 completa)

Rama `change/background-jobs-with-db-scheduler-03a3c-revival-and-idempotence`, apilada sobre 03a3b. Commit `93055df`: `DeadExecutionRevivalIT` (2) y `HandlerIdempotenceIT` (2, con el manejador que duplica como control negativo).

- Demostración deliberada (restaurada con `cmp` sin salida): `missed-heartbeats-limit: 100000` en `application.yml` pone en rojo `DeadExecutionRevivalIT.anExecutionHeldByADeadWorker...` (la fila sigue tomada tras 120 s) [BJ25].
- `./mvnw verify`: `BUILD SUCCESS` en **756 s**, Surefire 186 + 2 577, Failsafe 644. `./mvnw verify -Pmutation-gate`: `BUILD SUCCESS` en **2 016 s** (la máquina estuvo en pausa durante parte de la ejecución); JaCoCo cumplido; PIT aplicación 854/901 (95 %).
- **Desviación:** `HandlerIdempotenceIT` evalúa hoy solo un manejador de prueba, porque el trabajador de producción aún no registra ningún `OneTimeJob`; la tarea 3.2 añade el suyo al conjunto evaluado.
- **Desviación:** `DeadExecutionRevivalIT` simula al trabajador muerto con un `UPDATE` de la fila (tomada, latido de hace 10 minutos) en vez de matar un proceso.

## Tarea 2.4 (rebanada 03a4 `recurring-execution`, 2026-10-26)

Rama `change/background-jobs-with-db-scheduler-03a4-recurring-execution`, apilada sobre 03a3c. Commit de código `e3edf2a`. **770 líneas efectivas** (2.5 va en su propia rama: juntas pasarían de 800).

| Paso | Resultado observado |
|---|---|
| ROJO (compilación) | `test-compile` sin producción: `cannot find symbol` para `JobCalendars` (6 referencias). Causa esperada |
| ROJO (integración) | Primeras ejecuciones: el arnés repetía la institución («entry 2 … repeats entry 1», corregido en el arnés); el serializador rechazaba `null` y `Void` que usa la tarea recurrente sin datos («only the task envelope is serialized», corregido en `TracedJobSerializer` con su prueba); la ocurrencia de arranque (el retardo fijo ejecuta una vez al iniciar) contaminaba las aserciones, por lo que las pruebas esperan primero esa ocurrencia (`settleStartupRun`) |
| VERDE | `RecurringJobCalendarTest` 6, `RecurringJobRunnerTest` 4, `RecurringJobPerInstitutionIT` 3, `RecurringJobSingleRunIT` 2, `RecurringFailureStateIT` 1 |

Demostraciones deliberadas (restauradas con `cmp` sin salida): `ZoneId.systemDefault()` en `JobCalendars` pone en rojo `RecurringJobCalendarTest` (4 fallos: «expected: 2026-10-25T09:30:00Z», «expected: America/Tegucigalpa») [BJ27]; relanzar el primer fallo en `RecurringJobRunner` pone en rojo `RecurringJobRunnerTest` (2) y `RecurringJobPerInstitutionIT.aFailureInTheFirstInstitution...` (la segunda institución no corrió) [BJ65].

- `./mvnw verify`: `BUILD SUCCESS` en **925 s**, Surefire 186 + 2 587, Failsafe 650 (supera 15 min de BI57; máquina lenta). `./mvnw verify -Pmutation-gate`: `BUILD SUCCESS` en **2 599 s**; JaCoCo cumplido; PIT aplicación 871/919 (95 %).
- **Desviación:** el retardo fijo (`Every`) ejecuta una vez al arrancar el trabajador (comportamiento de la biblioteca: la primera ocurrencia es «ahora»); los calendarios diario y semanal no. Las pruebas lo esperan y lo documentan.
- **Desviación:** BJ29 («reiniciar no duplica el ciclo») se prueba con una tarea de cadencia de una hora: el reinicio no repite la ocurrencia ya cumplida ni duplica la fila.

## Tarea 2.5 (rebanada 03a5 `scheduling-architecture-rules`, 2026-10-27)

Rama `change/background-jobs-with-db-scheduler-03a5-scheduling-architecture-rules`, apilada sobre 03a4. Commit `732b87e`. **476 líneas efectivas**. Solo código de prueba y fixtures permanentes.

- ROJO: las reglas no existían; el «rojo» de una regla es que sus fixtures sean rechazados: `NoInMemorySchedulingTest` rechaza los ocho fixtures nombrando cada clase; `DbSchedulerConfinementTest` rechaza `ImportsTheSchedulingLibrary`; `JobPayloadTypesRuleTest` rechaza `TextPayload(String email)`; `JobPayloadAllowListTest` falla con un campo `email`, con un importe, con un tipo sin entrada y con una entrada sin tipo [BI85, BI86, BI100, BJ12, BJ13].
- Demostraciones deliberadas (restauradas; los archivos de producción quedan sin cambios en `git status`): un `@Scheduled` en `ParkedExecutions` pone en rojo `NoInMemorySchedulingTest`; una referencia a `com.github.kagkarlsson` desde `identity.application` pone en rojo `DbSchedulerConfinementTest` [BI85, BI100].
- `./mvnw verify`: `BUILD SUCCESS` en **779 s**, Surefire 186 + 2 599, Failsafe 650. `./mvnw verify -Pmutation-gate`: `BUILD SUCCESS` en **1 895 s**; JaCoCo cumplido; PIT aplicación 871/919 (95 %).
- **Desviaciones:** (1) ningún tipo de tarea de negocio está registrado todavía, así que `JobPayloadTypesRuleTest` y `JobPayloadAllowListTest` evalúan hoy un conjunto vacío de tipos reales (lo anuncian en su Javadoc) y la exigencia «al menos un tipo registrado» de BJ12/BJ52/BJ13 queda para la tarea 3.1, que añade el primer tipo y su entrada; las reglas se prueban con fixtures. (2) `NoUnapprovedPlainSqlTest` no cambió (se re-ejecutó en verde). (3) La inversión de `IdentityScopeExclusionInventoryTest` (BI98) cubre solo la mitad de rotación de DEK sobre las definiciones registradas; la mitad del adaptador de emisión llega con la tarea 3.1.

## Tarea 4.3 y cierre del cambio (rebanada 07b `07b-closing-notes`, 2026-11-01)

Rama `change/background-jobs-with-db-scheduler-07b-closing-notes`, sobre `e9f7c0e`. Contiene 4.2 (`ee0d0a4`), la corrección de sus tres observaciones de
revisión (`a9e5876`) y 4.3. **Medición de la rama completa contra `e9f7c0e`, sin `openspec/` ni `odd/`: 1 089 líneas efectivas** (1 087 añadidas y 2 borradas),
sobre el límite de 800; se propone partir en dos PR apilados (ver «Partición propuesta»), de modo que el commit de 4.3 no se hizo.

### Pruebas y documentos de 4.3

| Pieza | Escenarios | Qué comprueba |
|---|---|---|
| `bootstrap/CurrentAbsencesTest` (12 pruebas) | BJ38, BJ39, BJ40, AT17 | Cada ausencia vigente con su motivo de caducidad en una constante (`BJ38_EXPIRES` y las otras tres) que sale en el mensaje de fallo, y un fixture que la misma comprobación debe reportar: un POM con Actuator y un registro de Prometheus, un contexto con un `MeterRegistry`, una clase que importa `organization` y otra que nombra la tabla de instituciones, un registro con un tipo de negocio no declarado, y una cadencia horaria o una comparación con ancla. BJ39 recorre, desde `WorkerApplication`, las clases de `com.confia` que el trabajador alcanza (ArchUnit) y exige que alcance el cableado y los trabajos reales, para no pasar sobre un conjunto vacío |
| `shared/jobs/ExitCriterion5EndToEndIT` (1 prueba, PostgreSQL real) | BJ37 | Una tarea programada en una transacción revertida no existe ni se ejecuta; la confirmada existe, no la toma nadie sin el trabajador, y la ejecuta una sola vez el trabajador, en un hilo `db-scheduler` |
| `architecture/JobsChangeTraceabilityTest` (5 pruebas) | los 241 escenarios | Lee los cuatro deltas y comprueba que cada escenario con identificador está nombrado por el código de alguna prueba (identificador suelto o dentro de un rango como «BI70 to BI82») o figura en una lista con la prueba que lo cubre; que los 22 sin identificador están asignados por título a `Clase#método` o declarados sin prueba; que cada referencia nombra una clase y un método que existen; que una entrada ya innecesaria falla; y que un escenario sin prueba se reporta (fixture). Se eligió sobre la lista de verificación porque una lista no puede fallar. Cuando el cambio se archive, los deltas dejan de estar en `openspec/changes/` y esas pruebas se saltan con ese motivo (`Assumptions`) |
| `architecture/DocumentationNotesTest.docs09MarksExitCriterion5OfPhase0WithADatedNoteThatNamesItsTests` | BJ37, BJ41 | El criterio 5 de `docs/09` está marcado, su texto no cambió, la nota fechada nombra las tres pruebas y los criterios de respaldo y de accesibilidad siguen abiertos |

**ROJO observado** (`confia-scratch/t43/red1.log`, con `CurrentAbsencesTest` y `ExitCriterion5EndToEndIT` apartados, que es el estado previo a 4.3): 16 pruebas, 2 fallos.
`DocumentationNotesTest.docs09MarksExitCriterion5OfPhase0WithADatedNoteThatNamesItsTests:172 [criterion 5 is checked and its text is unchanged]` con `Expecting actual: -1 to be greater than or equal to: 0`, y
`JobsChangeTraceabilityTest.everyScenarioWithAnIdentifierIsNamedByATestOrListedWithTheTestThatCoversIt:122 [scenarios no test names and no entry covers]` con `Expecting empty but was: ["BJ38", "BJ39", "BJ40"]`.
BJ37 no apareció en la lista porque el Javadoc de la prueba de `docs/09`, escrita antes, ya nombraba el identificador: una mención suelta, no una prueba del escenario (con `ExitCriterion5EndToEndIT` pasa a estar nombrado por la prueba real). AT17 ya estaba nombrado por `RecurringJobCalendarTest` desde 3.8, pero sin motivo de caducidad ni fixture; `CurrentAbsencesTest` los añade. Las otras tres pruebas de trazabilidad pasaron en el primer intento.
**VERDE:** 28 pruebas de Surefire (`DocumentationNotesTest` 11, `JobsChangeTraceabilityTest` 5, `CurrentAbsencesTest` 12) y `ExitCriterion5EndToEndIT` 1, sin fallos (`green1.log`; el fallo de JaCoCo de una corrida focalizada es el esperado). Las pruebas de ausencia y de cierre pasaron a la primera sobre el código vigente: son guardas de regresión, cada una con su fixture que sí falla.

**Demostración deliberada**, copias en `confia-scratch/t43/orig`: en `CurrentAbsencesTest`, `@BeforeAll` agrega al registro del trabajador una tarea `invoicing.generate-monthly-charges`. Falla exactamente una de las 12 pruebas, `theWorkerRegistersExactlyTheOneTimeTaskAndTheEightRecurringOnesOfThisChange:225`, con `[BJ40 stops being true when each module that owns a business job delivers it] Expecting empty but was: ["unexpected invoicing.generate-monthly-charges"]`. Se revirtió y `cmp` no imprimió diferencias. La primera ejecución de la demostración falló antes, en la comprobación de «no vacuo» (`Expected size: 9 but was: 10`), que ocultaba el motivo; se invirtió el orden de las dos aserciones y se repitió.

### Trazabilidad de los 241 escenarios

Resumen: **231 cubiertos, 8 parciales y 2 sin prueba**. Por evidencia: 189 por etiqueta del escenario en el código de una prueba, 24 solo por un rango de identificadores en el Javadoc de la clase (se revisaron contra los métodos de las clases que cubren su tema), 26 por asignación manual y 2 sin prueba.

**Sin prueba (hallazgos, no mapeados):** «Se agrega Hibernate» y «Sin dependencias prohibidas» (`build-integrity`). Los cumple la regla `bannedDependencies` de `maven-enforcer` de `apps/api/pom.xml` en cada `./mvnw verify`; ninguna prueba agrega Hibernate a un POM ni lo hace fallar. Como regla de construcción son verificables, pero no hay un fixture que lo demuestre.

**Parciales:**

| Escenario | Qué falta |
|---|---|
| I240 | La prueba siembra tokens «superados», no «usados» (`consumed_at`): la mitad «usado» del escenario no está sembrada |
| I241 | Se prueba que el token vigente y el recién vencido se conservan; que el vigente «sigue aceptándose» no |
| I264 | `RolePrivilegeMatrixIT.noRoleOtherThanTheOwnerCanDeleteFromTheTablesThePurgeFunctionsServe` lo prueba por el catálogo de privilegios (`has_table_privilege`), no con un `DELETE` intentado con `confia_admin_app` |
| BJ08 | El control negativo usa un `Scheduler` simulado en un contexto vacío (`ProcessBeanIsolationTest`) y las exclusiones por punto de entrada (`StarterAutoConfigurationExcludedTest`), no el arranque real de administración y portal sin la exclusión |
| BI103 | Lo ejerce el arranque del trabajador contra una URL sin servidor de las pruebas de cableado; no hay una prueba propia de «sin migración ni validación inicial» |
| BI60 | Cubre un solo ejecutor y ningún `MeterRegistry`; la mitad de `./mvnw validate` la ejecuta la construcción, no una prueba |
| «Las pruebas de arranque no necesitan Docker» | Indirecta: son pruebas de Surefire que arrancan los procesos contra una URL sin servidor; ninguna afirma la ausencia de Docker |
| BJ37 | El lado que programa es el cliente real sobre el pool de la prueba, como en `OnlyWorkerExecutesIT`; que el proceso administrativo no tiene ejecutor lo afirma `ProcessBeanIsolationTest`. No se arranca el contexto administrativo real contra la base |

**Desajuste entre la especificación y el código (para el archivo):** el delta de `build-integrity` dice que la lista aprobada de SQL plano contiene exactamente el lector de la cola; `NoUnapprovedPlainSqlTest.theApprovedListHoldsExactlyTheThreeCallers` afirma tres (`JooqAuditLogWriter`, `JooqRefreshTokenFamilyRepository` y el lector). Los otros dos llegaron con cambios anteriores. Además, el resultado de la entrada de verificación fallida es `error` y no `failure` (hallazgo de 3.8).

Lista completa (capacidad, escenario, título, prueba, tipo de evidencia, nota). La comprueba `JobsChangeTraceabilityTest`; esta tabla es su espejo legible. «etiqueta» es el identificador en la clase; «rango» es un rango de identificadores en la clase; «manual» es la asignación escrita en la prueba de trazabilidad.

| Capacidad | Escenario | Título | Prueba | Evidencia | Nota |
|---|---|---|---|---|---|
| audit-trail | AT07 | Una cadena íntegra en la ventana reporta integridad y cuántas filas verificó | AuditChainVerificationJobsIT, AuditChainVerifierIT, DefaultAuditChainVerifierTest | etiqueta |  |
| audit-trail | AT08 | Una fila alterada dentro de la ventana se identifica con exactitud | AuditChainVerificationJobsIT, AuditChainVerifierIT, DefaultAuditChainVerifierTest | etiqueta |  |
| audit-trail | AT09 | La verificación de ventana no ve una alteración anterior a la ventana | AuditChainVerificationJobsIT, AuditChainVerifierIT, DefaultAuditChainVerifierTest | etiqueta |  |
| audit-trail | AT23 | El inicio de la ventana es el primer día del mes anterior, no 30 días móviles | AuditChainDailyWindowTest, AuditChainVerificationJobsIT | etiqueta |  |
| audit-trail | AT24 | La ventana se recalcula cada día con el mes de calendario | AuditChainDailyWindowTest | etiqueta |  |
| audit-trail | AT25 | El vínculo en la frontera no se verifica y la verificación parte del hash previo | AuditChainVerifierIT, DefaultAuditChainVerifierTest | etiqueta |  |
| audit-trail | AT26 | Una ventana sin filas devuelve «vacío» | AuditChainVerificationJobsIT, AuditChainVerifierIT, DefaultAuditChainVerifierTest | etiqueta |  |
| audit-trail | AT18 | Un asiento por categoría con el conteo | IdempotencyPurgeJobsIT, IdentityBackoffPurgeJobsIT, IdentityTokenPurgeJobsIT (+1) | etiqueta |  |
| audit-trail | AT19 | Una purga que no encontró filas también deja su asiento | IdempotencyPurgeJobsIT, IdentityBackoffPurgeJobsIT, IdentityTokenPurgeJobsIT (+1) | etiqueta |  |
| audit-trail | AT20 | El asiento no contiene datos de las filas purgadas | IdentityBackoffPurgeJobsIT, IdentityTokenPurgeJobsIT, PurgeExpiredIdempotencyKeysTest (+1) | etiqueta |  |
| audit-trail | AT21 | El asiento y la purga son atómicos | IdempotencyPurgeJobsIT, IdentityBackoffPurgeJobsIT, IdentityTokenPurgeJobsIT (+1) | etiqueta |  |
| audit-trail | AT27 | Un asiento por lote, con su número de lote | IdempotencyPurgeJobsIT, IdentityBackoffPurgeJobsIT, IdentityTokenPurgeJobsIT (+1) | etiqueta |  |
| audit-trail | AT28 | El vaciado del sucesor deja su propio asiento | RefreshTokenPurgeIT | etiqueta |  |
| audit-trail | AT29 | Agotar los 200 lotes deja 200 asientos y una advertencia | IdentityTokenPurgeJobsIT | etiqueta |  |
| audit-trail | AT22 | La cadena sigue verificando tras los asientos de purga | AuditChainAfterPurgeIT | etiqueta |  |
| audit-trail | — | El verificador se invoca directamente, sin infraestructura de programación | AuditChainVerifierIT | manual |  |
| audit-trail | AT10 | La cadencia diaria corre por cada institución configurada desde el primer día de | AuditChainVerificationJobsTest, AuditChainVerificationOnWorkerIT | etiqueta |  |
| audit-trail | AT11 | La cadencia semanal verifica la cadena completa desde el génesis | AuditChainVerificationJobsIT, AuditChainVerificationJobsTest | etiqueta |  |
| audit-trail | AT12 | Una discrepancia genera un resultado fallido y una alerta S1, sin reparar nada | AuditChainVerificationJobsIT, AuditChainVerificationJobsTest | etiqueta |  |
| audit-trail | AT13 | Una cadena íntegra registra un resultado correcto | AuditChainVerificationJobsIT, AuditChainVerificationJobsTest | etiqueta |  |
| audit-trail | AT30 | Una institución configurada sin asientos deja una advertencia | AuditChainVerificationJobsIT, AuditChainVerificationJobsTest | etiqueta |  |
| audit-trail | AT31 | El resultado se escribe después del recorrido en una transacción separada | AuditChainVerificationJobsIT, AuditChainVerificationJobsTest | etiqueta |  |
| audit-trail | AT14 | Una alteración en una institución no aparece en la ejecución de otra | AuditChainVerificationJobsIT, AuditChainVerificationOnWorkerIT | etiqueta |  |
| audit-trail | AT15 | Una institución no configurada no se verifica | AuditChainVerificationOnWorkerIT | etiqueta |  |
| audit-trail | AT16 | Ninguna ocurrencia se reintenta: el fallo técnico no deja resultado y el de inte | AuditChainVerificationJobsTest, AuditChainVerificationOnWorkerIT | etiqueta |  |
| audit-trail | AT17 | No existe la cadencia horaria — cierto hoy, falso al cerrar `audit-chain-hourly- | CurrentAbsencesTest, RecurringJobCalendarTest | etiqueta |  |
| background-jobs | BJ01 | Una transacción confirmada deja la tarea | JobSchedulingAtomicityIT | etiqueta |  |
| background-jobs | BJ02 | Una transacción revertida no deja ninguna tarea | JobSchedulingAtomicityIT | etiqueta |  |
| background-jobs | BJ03 | La tarea no es visible antes de la confirmación | JobSchedulingAtomicityIT | etiqueta |  |
| background-jobs | BJ04 | Un reintento del `TransactionRunner` programa una sola vez | JobSchedulingRetriedTransactionIT | etiqueta |  |
| background-jobs | BJ05 | Un cliente construido sobre una conexión sin proxy transaccional hace fallar la  | JobSchedulingAtomicityIT | etiqueta |  |
| background-jobs | BJ43 | Programar fuera de una transacción se rechaza | JobSchedulingGuardsTest | etiqueta |  |
| background-jobs | BJ44 | Programar una instancia ya programada no aborta la transacción del negocio | JobSchedulingGuardsIT | etiqueta |  |
| background-jobs | BJ45 | La institución de la envoltura debe ser la de la transacción | JobSchedulingGuardsIT | etiqueta |  |
| background-jobs | BJ46 | Hay un solo proxy transaccional por proceso | TransactionalDataSourceTest, WorkerProductionWiringTest | etiqueta |  |
| background-jobs | BJ06 | Cero ejecutores en administración y portal, uno en el trabajador | OnlyWorkerExecutesIT, ProcessBeanIsolationTest, WorkerHarness | etiqueta |  |
| background-jobs | BJ07 | Una tarea confirmada solo la ejecuta el trabajador | OnlyWorkerExecutesIT, WorkerHarness | etiqueta |  |
| background-jobs | BJ08 | Un contexto administrativo con la autoconfiguración de db-scheduler hace fallar  | ProcessBeanIsolationTest, StarterAutoConfigurationExcludedTest | manual | Scheduler simulado en un contexto vacío, no el arranque real sin la exclusión |
| background-jobs | BJ47 | El ejecutor del iniciador se retira y queda uno solo | SchedulerStarterCompatibilityTest | etiqueta |  |
| background-jobs | BJ48 | Toda autoconfiguración del iniciador está excluida en administración y portal | StarterAutoConfigurationExcludedTest | etiqueta |  |
| background-jobs | BJ49 | Ninguna configuración versionada desactiva el arranque del ejecutor | JobsConfigurationGuardTest, WorkerSchedulerStarterTest | etiqueta |  |
| background-jobs | BJ50 | El trabajador sin servidor web sigue vivo | WorkerStaysAliveTest | etiqueta |  |
| background-jobs | BJ51 | El contexto del portal contiene solo el cableado de programación | PortalContextStartupTest, ProcessBeanIsolationTest | etiqueta |  |
| background-jobs | BJ09 | El portal puede programar con su rol real | PortalSchedulingIT | etiqueta |  |
| background-jobs | BJ10 | La envoltura lleva el contexto y no datos personales | JobMetaTest, TracedJobSerializationTest | etiqueta |  |
| background-jobs | BJ11 | Los datos de tarea nunca son un objeto de Java serializado | TracedJobSerializationTest | etiqueta |  |
| background-jobs | BJ12 | La lista aprobada de campos rechaza un campo nuevo (control negativo) | JobPayloadAllowListTest, JobPayloadTypesRuleTest | etiqueta |  |
| background-jobs | BJ13 | Todo tipo de tarea registrado tiene su entrada en la lista aprobada | JobPayloadAllowListTest, RegisteredJobs | etiqueta |  |
| background-jobs | BJ52 | La carga solo admite tipos que no pueden llevar texto libre | JobPayloadRulesTest, JobPayloadTypesRuleTest, JobSchedulingGuardsTest | etiqueta |  |
| background-jobs | BJ53 | Un documento de más de 4 KiB se rechaza al programar | JobSchedulingGuardsIT, JobSchedulingGuardsTest, TracedJobSerializationTest | etiqueta |  |
| background-jobs | BJ54 | Un campo desconocido en una fila se rechaza al leerla | JobRunnerTest, TracedJobSerializationTest | etiqueta |  |
| background-jobs | BJ55 | El error de serialización no revela los datos | JobRunnerTest, TracedJobSerializationTest | etiqueta |  |
| background-jobs | BJ14 | Programar la misma instancia dos veces deja una sola fila | JobSchedulingGuardsIT | etiqueta |  |
| background-jobs | BJ15 | Instancias distintas tienen identificadores distintos y sin datos personales | JobInstanceIdProperties, JobSchedulingGuardsIT | etiqueta |  |
| background-jobs | BJ56 | El identificador de instancia es determinista, opaco y sensible a cada dato | JobInstanceIdProperties | etiqueta |  |
| background-jobs | BJ16 | El manejador corre como `system` en la institución de la tarea | JobContextIT, JobRunnerTest | etiqueta |  |
| background-jobs | BJ17 | Un manejador no ve los datos de otra institución | JobContextIT | etiqueta |  |
| background-jobs | BJ18 | Una tarea sin institución no produce ningún efecto | JobContextIT, TracedJobSerializationTest | etiqueta |  |
| background-jobs | BJ57 | Una tarea de una institución inexistente no produce efecto | JobContextIT | etiqueta |  |
| background-jobs | BJ19 | La doble ejecución de cada tipo registrado deja un solo efecto | HandlerIdempotenceIT, PasswordResetIssuanceDoubleExecutionIT | etiqueta |  |
| background-jobs | BJ20 | Un manejador no idempotente hace fallar la prueba (control negativo) | HandlerIdempotenceIT | etiqueta |  |
| background-jobs | BJ21 | Un fallo transitorio se reintenta y termina en éxito | FailedExecutionIsParkedIT | etiqueta |  |
| background-jobs | BJ22 | La espera crece y el número de intentos está acotado | BoundedRetryFailureHandlerTest, FailedExecutionIsParkedIT, RetryPolicyTest | etiqueta |  |
| background-jobs | BJ23 | Una tarea agotada se conserva como fallida | BoundedRetryFailureHandlerTest, FailedExecutionIsParkedIT | etiqueta |  |
| background-jobs | BJ24 | Un éxito reinicia la cuenta de fallos de una tarea recurrente | RecurringFailureStateIT | etiqueta |  |
| background-jobs | BJ58 | Cada fallo deja una advertencia con el intento y el desenlace | FailedExecutionIsParkedIT | etiqueta |  |
| background-jobs | BJ59 | Una tarea agotada no se borra: control negativo contra la política por omisión d | BoundedRetryFailureHandlerTest, FailedExecutionIsParkedIT | etiqueta |  |
| background-jobs | BJ60 | El fallo de un manejador se sanea antes de llegar al registro | JobFailureSanitizationIT, JobRunnerTest | etiqueta |  |
| background-jobs | BJ61 | Una tarea recurrente fallida no se reintenta y queda visible hasta su próximo éx | RecurringFailureStateIT | etiqueta |  |
| background-jobs | BJ25 | Una ejecución muerta se revive antes de 900 segundos | DeadExecutionRevivalIT | etiqueta |  |
| background-jobs | BJ26 | Una ejecución larga y sana no se revive | DeadExecutionRevivalIT | etiqueta |  |
| background-jobs | BJ27 | La cadencia no depende de la zona por defecto de la JVM | RecurringJobCalendarTest | etiqueta |  |
| background-jobs | BJ28 | Dos trabajadores ejecutan cada ciclo una sola vez | RecurringJobSingleRunIT | etiqueta |  |
| background-jobs | BJ29 | Reiniciar el trabajador no duplica el ciclo | RecurringJobSingleRunIT | etiqueta |  |
| background-jobs | BJ62 | Cada tarea recurrente del catálogo tiene su próxima ejecución exacta | IdentityBackoffPurgeJobsIT, RecurringJobCalendarTest, RecurringJobCatalogTest | etiqueta |  |
| background-jobs | BJ63 | Dos tareas con el mismo nombre impiden el arranque | WorkerJobsTasksTest | etiqueta |  |
| background-jobs | BJ30 | Cada ciclo corre una vez por institución configurada | RecurringJobPerInstitutionIT, RecurringJobRunnerTest | etiqueta |  |
| background-jobs | BJ31 | Sin instituciones configuradas el trabajador no arranca y nada actúa sobre datos | ConfiguredInstitutionsStartupTest, ConfiguredInstitutionsTest | etiqueta |  |
| background-jobs | BJ64 | Una lista con una entrada inválida o repetida impide el arranque | ConfiguredInstitutionsStartupTest, ConfiguredInstitutionsTest | etiqueta |  |
| background-jobs | BJ65 | Una institución que falla no impide a las demás y la ocurrencia queda fallida | RecurringJobPerInstitutionIT, RecurringJobRunnerTest | etiqueta |  |
| background-jobs | BJ66 | La ocurrencia usa su identificador de ejecución como correlación y solicitud | RecurringJobPerInstitutionIT, RecurringJobRunnerTest | etiqueta |  |
| background-jobs | BJ32 | Profundidad, antigüedad y fallidas | QueueStateReaderIT | etiqueta |  |
| background-jobs | BJ33 | Ninguna consulta de métricas selecciona `task_data` | QueueSqlNeverReadsTaskDataTest | etiqueta |  |
| background-jobs | BJ34 | El umbral de 900 segundos es exacto | QueueHealthTest | etiqueta |  |
| background-jobs | BJ35 | El estado vuelve a normal al vaciarse la cola | QueueHealthTest | etiqueta |  |
| background-jobs | BJ36 | La publicación por registro estructurado no filtra datos de tarea | LogQueueMetricsTest, QueueStateReaderIT | etiqueta |  |
| background-jobs | BJ67 | Estados de la cola y tarea tomada con latido vencido | QueueStateReaderIT | etiqueta |  |
| background-jobs | BJ68 | La observación escribe un evento de estado por ocurrencia | LogQueueMetricsTest, QueueObservationOnWorkerIT, QueueObservationTest | etiqueta |  |
| background-jobs | BJ69 | Las tareas fallidas y la cola atascada dejan advertencias | LogQueueMetricsTest | etiqueta |  |
| background-jobs | BJ70 | La observación y su límite quedan documentados (solo documental) | DocumentationNotesTest | etiqueta |  |
| background-jobs | BJ37 | Criterio 5 de extremo a extremo | DocumentationNotesTest, ExitCriterion5EndToEndIT | etiqueta | el lado que programa es el cliente real sobre el pool, no el contexto administrativo arrancado |
| background-jobs | BJ38 | No hay exportadores en el árbol — cierto hoy, falso al cerrar los cambios 10 y 1 | CurrentAbsencesTest | etiqueta |  |
| background-jobs | BJ39 | El trabajador no consulta la tabla de instituciones — cierto hoy, falso al cerra | CurrentAbsencesTest | etiqueta |  |
| background-jobs | BJ40 | El registro de tipos contiene solo los tipos de este cambio — cierto hoy, falso  | CurrentAbsencesTest | etiqueta |  |
| background-jobs | BJ41 | Notas fechadas en `docs/03`, `docs/05`, `docs/07`, `docs/08` y `docs/09` (solo d | DocumentationNotesTest | etiqueta |  |
| background-jobs | BJ71 | La nota de `docs/05` revierte la del 2026-10-04 sobre el portal (solo documental | DocumentationNotesTest | etiqueta |  |
| background-jobs | BJ72 | ADR-0025 en estado «Propuesto» y nota fechada en ADR-0017 (solo documental) | DocumentationNotesTest | etiqueta |  |
| background-jobs | BJ42 | Seguimientos y condición 4 en el plan de fundaciones (solo documental) | DocumentationNotesTest | etiqueta |  |
| build-integrity | BI59 | La versión tiene una sola fuente | SchedulerDependencySingleSourceTest | etiqueta |  |
| build-integrity | BI60 | El sondeo arranca el trabajador real con la biblioteca | SchedulerStarterCompatibilityTest, WorkerHasNoMeterRegistryTest | manual | sin la mitad de `./mvnw validate` (la ejecuta la construcción) |
| build-integrity | BI61 | Quartz y JobRunr siguen prohibidos con db-scheduler presente | SchedulerDependencySingleSourceTest | etiqueta |  |
| build-integrity | BI62 | La tabla existe con las columnas de la versión fijada | ScheduledTasksSchemaIT | etiqueta |  |
| build-integrity | BI63 | La biblioteca no crea ni altera tablas | ScheduledTasksNotCreatedAtRuntimeIT | etiqueta |  |
| build-integrity | BI64 | `V10` no cambia nada más y no edita migraciones anteriores | ScheduledTasksSchemaIT, V10SchemaDiffIT | etiqueta |  |
| build-integrity | BI65 | La matriz cubre `scheduled_tasks` para los cinco roles | RolePrivilegeMatrixIT | etiqueta |  |
| build-integrity | BI66 | El portal programa con su rol real y no puede actualizar ni borrar | PortalSchedulingIT, PortalSchedulingPrivilegeIT | etiqueta |  |
| build-integrity | BI67 | `confia_readonly` no tiene ningún privilegio y `confia_admin_app` puede operar | RolePrivilegeMatrixIT | etiqueta |  |
| build-integrity | BI68 | El catálogo describe cada función como se exige | RolePrivilegeMatrixIT | etiqueta |  |
| build-integrity | BI69 | Los demás roles no pueden ejecutar las funciones | RolePrivilegeMatrixIT | etiqueta |  |
| build-integrity | BI70 | Un `search_path` hostil del llamador no desvía la función | RetentionPurgeFunctionsIT | etiqueta |  |
| build-integrity | BI71 | El llamador solo puede mover el corte hacia atrás, nunca hacia adelante | RetentionPurgeFunctionsIT | rango |  |
| build-integrity | BI104 | Control negativo: un corte en el futuro no borra nada más reciente que el piso | RetentionPurgeFunctionsIT | etiqueta |  |
| build-integrity | BI105 | Sin contexto o con un actor que no es `system` la función falla | RetentionPurgeFunctionsIT | rango |  |
| build-integrity | BI117 | Un `p_cutoff` nulo hace fallar cada una de las cinco funciones sin borrar nada | RefreshTokenFamilyPurgeFunctionIT, RetentionPurgeFunctionsIT, V13SchemaDiffIT | etiqueta |  |
| build-integrity | BI118 | Un `p_limit` nulo hace fallar cada una de las cinco funciones sin borrar nada | RefreshTokenFamilyPurgeFunctionIT, RetentionPurgeFunctionsIT, V13SchemaDiffIT | etiqueta |  |
| build-integrity | BI72 | Ningún rol de aplicación tiene `DELETE` sobre las tablas purgadas | RolePrivilegeMatrixIT | etiqueta |  |
| build-integrity | BI73 | Un segundo antes, en el borde y un segundo después | RetentionPurgeFunctionsIT | rango |  |
| build-integrity | BI74 | Nunca se borra una fila vigente | RetentionPurgeFunctionsIT | rango |  |
| build-integrity | BI75 | Otra institución queda intacta | RetentionPurgeFunctionsIT | rango |  |
| build-integrity | BI76 | Sin contexto de institución la función falla y la prueba lo detecta | RetentionPurgeFunctionsIT | rango |  |
| build-integrity | BI77 | Una variante sin corte hace fallar la prueba (control negativo) | RetentionPurgeFunctionsIT | etiqueta |  |
| build-integrity | BI106 | El límite de lote se respeta y se acota a 10 000 | RefreshTokenFamilyPurgeFunctionIT | etiqueta |  |
| build-integrity | BI107 | La función de familias de refresco elimina familias completas hasta el límite y  | RefreshTokenFamilyPurgeFunctionIT | etiqueta |  |
| build-integrity | BI78 | Una clave caducada hace más de 24 horas se elimina | RetentionPurgeFunctionsIT | rango |  |
| build-integrity | BI79 | El borde de 24 horas conserva la clave | RetentionPurgeFunctionsIT | rango |  |
| build-integrity | BI80 | Una clave vigente o recién caducada se conserva y sigue siendo reutilizable | RetentionPurgeFunctionsIT | etiqueta |  |
| build-integrity | BI81 | El índice por institución y `expires_at` existe | RetentionPurgeFunctionsIT | etiqueta |  |
| build-integrity | BI108 | El índice de la tabla de retroceso de inicio de sesión existe | RetentionPurgeFunctionsIT | etiqueta |  |
| build-integrity | BI82 | Una clave purgada se comporta como nueva | RetentionPurgeFunctionsIT | etiqueta |  |
| build-integrity | BI83 | La matriz afirma `DELETE` ausente y `EXECUTE` exacto | IdempotencyKeyPrivilegeIT, RolePrivilegeMatrixIT | etiqueta |  |
| build-integrity | BI84 | Un `DELETE` o un `EXECUTE` de más hace fallar la matriz (control negativo) | IdempotencyKeyPrivilegeIT, RolePrivilegeMatrixIT | etiqueta |  |
| build-integrity | BI85 | Fixtures con cada anotación prohibida | NoInMemorySchedulingTest | etiqueta |  |
| build-integrity | BI86 | El código real no usa ninguna y la regla evalúa clases reales | NoInMemorySchedulingTest | etiqueta |  |
| build-integrity | BI87 | Una sola lista de prefijos para los tres procesos | SensitiveLogGuardInstallationTest | etiqueta |  |
| build-integrity | BI88 | La lista cubre las superficies de riesgo | SensitiveLogGuardPrefixesTest | etiqueta |  |
| build-integrity | BI89 | Un valor enlazado distintivo no aparece en el trabajador real con `DEBUG` | WorkerSensitiveLoggingIT | etiqueta |  |
| build-integrity | BI90 | Sin la guardia el valor aparece (control negativo) | WorkerSensitiveLoggingIT | etiqueta |  |
| build-integrity | BI91 | Un trabajador con `DataSource` y sin guardia rompe la construcción | WorkerProductionWiringTest | etiqueta |  |
| build-integrity | BI109 | La guardia está instalada antes de crear el primer bean y se retira al cerrar | SensitiveLogGuardInstallationTest | etiqueta |  |
| build-integrity | BI110 | Los prefijos nuevos bloquean `DEBUG` y `TRACE` y dejan pasar `INFO` y superiores | SensitiveLogGuardPrefixesTest | etiqueta |  |
| build-integrity | BI92 | El trabajador registra su cableado estrecho y nada administrativo | WorkerProductionWiringTest | etiqueta |  |
| build-integrity | BI93 | Administración y portal siguen prohibiendo db-scheduler como bean | ProcessBeanIsolationTest | etiqueta |  |
| build-integrity | BI94 | Un paquete nuevo del trabajador sin línea rompe la construcción | ProcessBeanInspectorTest, ProcessBeanIsolationTest | manual |  |
| build-integrity | BI111 | El trabajador no tiene llaves de cifrado de columna ni pimienta | WorkerProductionWiringTest | etiqueta |  |
| build-integrity | BI112 | El portal solo admite el cableado de programación | PortalContextStartupTest, ProcessBeanIsolationTest | etiqueta |  |
| build-integrity | BI95 | El trabajador tiene su ejecutor y los otros dos procesos no | ProcessBeanIsolationTest, SchedulerStarterCompatibilityTest | etiqueta |  |
| build-integrity | BI96 | El control negativo detecta un ejecutor donde no corresponde y un trabajador sin | ProcessBeanIsolationTest | etiqueta |  |
| build-integrity | BI113 | La ausencia de observabilidad se estrecha a las métricas y admite las de cola en | ProcessBeanIsolationTest, ProcessBeanPolicy | etiqueta |  |
| build-integrity | BI114 | Una autoconfiguración nueva del iniciador sin excluir rompe la construcción | StarterAutoConfigurationExcludedTest | etiqueta |  |
| build-integrity | BI97 | `AuditScopeExclusionInventoryTest` admite solo db-scheduler y `V10` | AuditScopeExclusionInventoryTest | etiqueta |  |
| build-integrity | BI98 | `IdentityScopeExclusionInventoryTest` invierte el adaptador y conserva la rotaci | IdentityScopeExclusionInventoryTest, RegisteredJobs | etiqueta |  |
| build-integrity | BI99 | `MultiTenantSchemaIT` evalúa la tabla real y no exime nada más | MultiTenantSchemaIT | etiqueta |  |
| build-integrity | — | Se agrega Hibernate | SIN PRUEBA (regla del enforcer) | sin prueba |  |
| build-integrity | — | Sin dependencias prohibidas | SIN PRUEBA (regla del enforcer) | sin prueba |  |
| build-integrity | BI100 | db-scheduler está admitido y otra biblioteca de programación no | DbSchedulerConfinementTest, SchedulerDependencySingleSourceTest | etiqueta |  |
| build-integrity | — | SQL plano fuera de la lista aprobada | NoUnapprovedPlainSqlTest | manual |  |
| build-integrity | BI101 | El lector de métricas de cola es el único con SQL plano y no lee `task_data` | NoUnapprovedPlainSqlTest, QueueSqlNeverReadsTaskDataTest | etiqueta |  |
| build-integrity | — | La matriz de privilegios cubre la tabla nueva para los cinco roles | RolePrivilegeMatrixIT | manual |  |
| build-integrity | — | `confia_admin_app` puede leer, insertar y actualizar, pero no borrar | RolePrivilegeMatrixIT | manual |  |
| build-integrity | — | `confia_portal_app` no tiene ningún privilegio sobre la tabla nueva | RolePrivilegeMatrixIT | manual |  |
| build-integrity | BI07 | La matriz de privilegios cubre las dos tablas para los cinco roles | RefreshTokenSchemaIT | rango |  |
| build-integrity | BI08 | `confia_admin_app` no puede borrar en ninguna de las dos tablas | RefreshTokenSchemaIT | rango |  |
| build-integrity | BI09 | El `UPDATE` fuera de las columnas concedidas se rechaza | RefreshTokenSchemaIT | rango |  |
| build-integrity | BI10 | `confia_portal_app` no tiene ningún privilegio | RefreshTokenSchemaIT | etiqueta |  |
| build-integrity | — | El portal arrastra un punto de entrada ajeno | ProcessBeanInspectorTest | manual |  |
| build-integrity | — | El portal arrastra un módulo administrativo | ProcessBeanInspectorTest | manual |  |
| build-integrity | — | El portal contiene solo lo suyo | PortalContextStartupTest | manual |  |
| build-integrity | — | El portal no hereda el cableado administrativo pero sí tiene su cableado de prog | ProcessBeanIsolationTest | manual |  |
| build-integrity | — | El trabajador recibe una importación ajena | ProcessBeanInspectorTest | manual |  |
| build-integrity | — | El trabajador arranca sin servidor web y con su cableado de base de datos | ProcessBeanIsolationTest, WorkerProductionWiringTest | manual |  |
| build-integrity | — | El trabajador no recibe el borde web | ProcessBeanIsolationTest | manual |  |
| build-integrity | — | Un punto de entrada vuelve a escanear | BootstrapEntryPointRulesTest | manual |  |
| build-integrity | — | Los tres puntos de entrada cumplen la forma explícita | BootstrapEntryPointRulesTest | manual |  |
| build-integrity | BI115 | Un punto de entrada que excluye el `DataSource` rompe la construcción | WorkerProductionWiringTest | etiqueta |  |
| build-integrity | BI102 | Administración o portal sin la exclusión de db-scheduler rompen la construcción | StarterAutoConfigurationExcludedTest | manual |  |
| build-integrity | — | El proceso administrativo arranca con la base de datos inalcanzable | AdminProductionWiringTest | manual |  |
| build-integrity | — | El trabajador y el portal tienen `DataSource`, y solo el trabajador tiene `DSLCo | ProcessBeanIsolationTest | manual |  |
| build-integrity | BI116 | El contexto del portal se construye con la base de datos inalcanzable | PortalContextStartupTest | etiqueta |  |
| build-integrity | BI103 | El contexto del trabajador se construye con la base de datos inalcanzable | WorkerProductionWiringTest | manual | el arranque del trabajador de las demás pruebas, no una prueba propia de «sin migración» |
| build-integrity | — | Las pruebas de arranque no necesitan Docker | ProcessBeanIsolationTest | manual | indirecta: pruebas de Surefire que arrancan los procesos contra una URL sin servidor |
| identity | I229 | La solicitud programa una tarea única con identificadores internos | JobPasswordResetIssuanceSchedulerTest, RequestPasswordResetSchedulingIT, RequestPasswordResetTest | etiqueta |  |
| identity | I230 | Una solicitud revertida o para un identificador sin cuenta no programa nada | RequestPasswordResetSchedulingIT | etiqueta |  |
| identity | I231 | La misma solicitud no programa dos tareas | JobPasswordResetIssuanceSchedulerTest, RequestPasswordResetSchedulingIT | etiqueta |  |
| identity | I232 | El token existe solo cuando la tarea se ejecuta | PasswordResetIssuanceHandlerIT | etiqueta |  |
| identity | I233 | El manejador corre como `system` y en la institución de la tarea | PasswordResetIssuanceHandlerIT, PasswordResetIssuanceInstitutionIsolationIT | etiqueta |  |
| identity | I234 | La doble ejecución deja un solo token vivo | PasswordResetIssuanceDoubleExecutionIT | etiqueta |  |
| identity | I235 | La cuarta emisión dentro de la hora se omite sin fallar la tarea | PasswordResetIssuanceDoubleExecutionIT | etiqueta |  |
| identity | I236 | Una cuenta que ya no existe o no está activa no recibe token | PasswordResetIssuanceHandlerIT | etiqueta |  |
| identity | I268 | Una tarea de una cuenta de otra institución termina sin efecto | PasswordResetIssuanceInstitutionIsolationIT | etiqueta |  |
| identity | I269 | Con el envío disponible la emisión llama a la comprobación antes de abrir la tra | UnavailableSenderLeavesNoTokenIT | etiqueta |  |
| identity | I237 | La tarea de emisión pasa la prueba de lista aprobada de campos | JobPayloadAllowListTest | etiqueta |  |
| identity | I238 | Un token vencido hace más de 24 horas se elimina | RetentionPurgeFunctionsIT | etiqueta |  |
| identity | I239 | El borde de 24 horas conserva el token y un segundo después lo elimina | RetentionPurgeFunctionsIT | rango |  |
| identity | I240 | Los tokens usados y superados también se eliminan al pasar la ventana | RetentionPurgeFunctionsIT | rango | solo el estado «superado» se siembra; el «usado» no |
| identity | I241 | Un token vigente o recién vencido nunca se elimina | RetentionPurgeFunctionsIT | rango | se prueba la conservación; «sigue aceptándose» no |
| identity | I242 | La purga de una institución no toca a la otra | RetentionPurgeFunctionsIT | rango |  |
| identity | I243 | Sin contexto de institución la purga falla y no elimina ninguna fila | RetentionPurgeFunctionsIT | rango |  |
| identity | I270 | Un actor que no es `system` no puede purgar tokens de recuperación | RetentionPurgeFunctionsIT | etiqueta |  |
| identity | I271 | Un corte en el futuro no elimina un token más reciente que el piso | RetentionPurgeFunctionsIT | rango |  |
| identity | I244 | Una familia revocada hace más de 30 días se elimina con sus tokens | RefreshTokenFamilyPurgeFunctionIT | etiqueta |  |
| identity | I245 | El borde de 30 días conserva la familia y un segundo después la elimina | RefreshTokenFamilyPurgeFunctionIT, RetentionPurgeFunctionsIT | rango |  |
| identity | I246 | Una familia vencida sin revocar se purga desde su vencimiento absoluto | RefreshTokenFamilyPurgeFunctionIT, RetentionPurgeFunctionsIT | rango |  |
| identity | I247 | Una familia viva o dentro de la ventana no pierde ningún token | RefreshTokenFamilyPurgeFunctionIT, RetentionPurgeFunctionsIT | rango |  |
| identity | I248 | Los tokens se eliminan antes que su familia sin violar claves foráneas | RefreshTokenFamilyPurgeFunctionIT, RetentionPurgeFunctionsIT | rango |  |
| identity | I249 | Un fallo a mitad de la purga no deja una familia a medias | RefreshTokenFamilyPurgeFunctionIT | etiqueta |  |
| identity | I250 | La purga de refresco no cruza instituciones | RetentionPurgeFunctionsIT | rango |  |
| identity | I251 | Sin contexto de institución la purga de refresco falla y no elimina nada | RetentionPurgeFunctionsIT | etiqueta |  |
| identity | I272 | La inactividad de una familia no la hace purgable | RefreshTokenFamilyPurgeFunctionIT | etiqueta |  |
| identity | I273 | Una familia purgable se elimina completa aunque supere el tamaño del lote en tok | RefreshTokenFamilyPurgeFunctionIT, RetentionPurgeFunctionsIT | etiqueta |  |
| identity | I252 | El sucesor residual se vacía una vez vencida la gracia | RefreshTokenPurgeIT | etiqueta |  |
| identity | I253 | Dentro de la gracia y en el borde exacto el sucesor se conserva | RefreshTokenPurgeIT | etiqueta |  |
| identity | I254 | Vaciar el sucesor no desactiva la detección de reutilización | RefreshTokenPurgeIT | etiqueta |  |
| identity | I255 | El vaciado de una institución no toca los tokens de otra | IdentityPurgeJobsOnWorkerIT, RefreshTokenPurgeIT | etiqueta |  |
| identity | I256 | Una segunda ejecución no cambia nada | IdentityTokenPurgeJobsIT, RefreshTokenPurgeIT | etiqueta |  |
| identity | I257 | Las filas de retroceso inactivas por más de 24 horas se eliminan | RetentionPurgeFunctionsIT | etiqueta |  |
| identity | I258 | El borde de 24 horas conserva la fila y un segundo después la elimina | RetentionPurgeFunctionsIT | rango |  |
| identity | I259 | Una fila con un intento reciente nunca se elimina | BackoffPurgeVersusLoginIT, RetentionPurgeFunctionsIT | etiqueta |  |
| identity | I260 | La purga de retroceso no cruza instituciones | RetentionPurgeFunctionsIT | rango |  |
| identity | I261 | Sin contexto de institución la purga de retroceso falla y no elimina nada | RetentionPurgeFunctionsIT | etiqueta |  |
| identity | I262 | Las cuatro purgas están registradas y corren por la institución configurada | IdentityBackoffPurgeJobsIT, IdentityPurgeJobsOnWorkerIT, RecurringJobCatalogTest (+1) | etiqueta |  |
| identity | I263 | La doble ejecución de cada purga no cambia el estado ni falla | IdempotencyPurgeJobsIT, IdentityBackoffPurgeJobsIT, IdentityTokenPurgeJobsIT (+1) | etiqueta |  |
| identity | I264 | Una purga con rol de aplicación no puede borrar por fuera de la función | RolePrivilegeMatrixIT | manual | por el catálogo de privilegios, sin un `DELETE` intentado |
| identity | I274 | Una purga repite lotes hasta vaciar lo purgable | IdempotencyPurgeJobsIT, IdentityBackoffPurgeJobsIT, IdentityTokenPurgeJobsIT (+1) | etiqueta |  |
| identity | I275 | Agotar los 200 lotes deja el resto para el día siguiente | IdentityTokenPurgeJobsIT, PurgeExpiredIdempotencyKeysTest | etiqueta |  |
| identity | I276 | Los lotes pasan por la función con el corte calculado por el llamador | IdentityBackoffPurgeJobsIT, IdentityTokenPurgeJobsIT, PurgeExpiredIdempotencyKeysTest | etiqueta |  |
| identity | — | Ninguna DEK retirada se recifra automáticamente | IdentityScopeExclusionInventoryTest | manual |  |
| identity | — | El puerto de envío solo tiene el emisor que falla de forma explícita | IdentityScopeExclusionInventoryTest | manual |  |
| identity | — | Un restablecimiento completado no notifica a nadie | IdentityScopeExclusionInventoryTest | manual |  |
| identity | I265 | La tarea de emisión con el emisor que falla se reintenta de forma acotada y qued | UnavailableSenderLeavesNoTokenIT | etiqueta |  |
| identity | I266 | Los intentos fallidos no dejan tokens huérfanos ni superan el token vivo | UnavailableSenderLeavesNoTokenIT | etiqueta |  |
| identity | I277 | El emisor que falla impide emitir antes de abrir la transacción, en cada intento | UnavailableSenderLeavesNoTokenIT | etiqueta |  |
| identity | I267 | El fallo del emisor no revela el token, el enlace ni el correo | UnavailableSenderLeavesNoTokenIT | etiqueta |  |
| identity | I96 | Reintento dentro de la gracia desde el mismo dispositivo (ADR-0005, prueba 2) | RefreshGraceWindowIT, RotateRefreshTokenTest | etiqueta |  |
| identity | I141 | La gracia devuelve el mismo refresco sucesor y un token de acceso nuevo (decisió | RefreshGraceWindowIT, RotateRefreshTokenTest | etiqueta |  |
| identity | I97 | El borde de 10 segundos es estricto | RefreshGraceWindowIT | etiqueta |  |
| identity | I98 | Otro dispositivo dentro de la ventana es reutilización | RefreshGraceWindowIT | etiqueta |  |
| identity | I99 | El sucesor se guarda cifrado y ligado a su fila | RefreshGraceWindowIT, RotateRefreshTokenTest | etiqueta |  |
| identity | I100 | Pasados los 10 segundos el valor sigue en la base y no se descifra | RefreshGraceWindowIT | etiqueta |  |
| identity | I101 | El sucesor cifrado se vacía en el siguiente uso de la familia | RefreshGraceWindowIT | etiqueta |  |
| identity | I102 | Si la familia ya avanzó no hay reintento en gracia | RefreshGraceWindowIT | etiqueta |  |
| identity | I103 | Sin agente de usuario en ambas presentaciones | RefreshGraceWindowIT, RotateRefreshTokenTest | etiqueta |  |

### Mediciones de cada PR

Líneas efectivas = adiciones más borrados de `git diff --numstat <fusión>^1 <fusión>` sin `openspec/` ni `odd/`, leídas de git el 2026-11-01. Las duraciones de `verify` son las registradas en cada tarea
(este documento, el diario de ODD y los registros de compilación que se conservan).

| PR | Rama (`change/background-jobs-with-db-scheduler-…`) | Fusión | Líneas efectivas |
|---|---|---|---|
| #188 | (propuesta, solo `openspec/`) | `677697d` | 0 (+) |
| #189 | 01-scheduler-dependency-and-table | `6e938d6` | 376 (345+31) |
| #190 | 01b-scheduled-tasks-table | `2bc0621` | 936 (908+28) |
| #191 | 02a-neutral-log-guard | `14e9922` | 364 (299+65) |
| #194 | 02b-worker-datasource-and-condition-4 | `798f403` | 764 (742+22) |
| #192 | 02b2-configured-institutions | `1f676ad` | 276 (273+3) |
| #193 | 02b3-worker-scheduler-shell | `7849837` | 683 (630+53) |
| #195 | 03a1a-jobs-value-types | `6819856` | 579 (578+1) |
| #196 | 03a1b-jobs-declarations | `42871af` | 639 (639+0) |
| #197 | 03a1c-task-data-serializer | `9737a1e` | 544 (544+0) |
| #198 | 03a2-scheduler-client-and-atomicity | `4e5956c` | 793 (792+1) |
| #199 | 03a3a-one-time-runner | `f2715f6` | 523 (521+2) |
| #200 | 03a3b-one-time-execution-its | `f52ba6c` | 668 (668+0) |
| #201 | 03a3c-revival-and-idempotence | `aafa747` | 214 (214+0) |
| #202 | 03a4-recurring-execution | `af6fa35` | 770 (761+9) |
| #203 | 03a5-scheduling-architecture-rules | `db73f83` | 476 (476+0) |
| #204 | 03b-portal-scheduling-client | `9dfcefc` | 390 (339+51) |
| #205 | 04a-reset-issuance-scheduler | `5adcecd` | 491 (462+29) |
| #206 | 04b-reset-issuance-handler | `a9a72fa` | 779 (754+25) |
| #207 | 04c-reset-issuance-double-execution-and-isolation | `6be51da` | 113 (113+0) |
| #208 | 05a1-v11-purge-functions | `a4d8fc0` | 577 (577+0) |
| #209 | 05a1b-purge-function-behaviour | `80a3d68` | 450 (450+0) |
| #210 | 05a1c-refresh-purge-and-rotation | `d919212` | 461 (461+0) |
| #212 | 05a2a-operational-event-log | `3e93e1b` | 184 (179+5) |
| #213 | 05a2-identity-purge-jobs | `b400eb6` | 799 (794+5) |
| #214 | 05a2b-refresh-successor-clearing | `2936620` | 539 (501+38) |
| #220 | 05a3-adr-0025-and-retention-notes | `d929546` | 641 (641+0) |
| #221 | 05b1-v12-purge-functions | `998b162` | 634 (620+14) |
| #222 | 05b1b-backoff-purge-versus-login | `3df80c1` | 191 (191+0) |
| #228 | 05b2-idempotency-and-backoff-jobs | `f4bf89f` | 641 (630+11) |
| #229 | 05b2b-backoff-purge-jobs | `49ddc62` | 533 (497+36) |
| #236 | 06-audit-chain-verification-cadences | `fa9463f` | 385 (367+18) |
| #237 | 06b-audit-chain-jobs | `8a6e434` | 788 (781+7) |
| #238 | 06c-audit-chain-job-integration | `2f44bbb` | 556 (543+13) |
| #239 | 07-adr-0025-recheck-note | `304950b` | 13 (13+0) |
| #240 | 06d-purge-null-arguments | `cf46452` | 594 (589+5) |
| #241 | 07a-queue-observability | `8022bb9` | 723 (723+0) |
| #242 | 07a2-queue-observation-wiring | `e9f7c0e` | 800 (785+15) |

Suma de los 38 PR fusionados: **19 887 líneas efectivas**. Solo #190 (`01b-scheduled-tasks-table`, 936) superó las 800 líneas. Pendientes de fusión: 4.2 con su corrección `a9e5876` (433 líneas contra `e9f7c0e`) y 4.3 (656).

### Duración de `verify` contra el presupuesto de 15 minutos (BI57, riesgo R-9)

**Supera el presupuesto.** Las ejecuciones recientes de `./mvnw -B verify` sin perfil, leídas de sus registros: 3.7 (5b-2) 18 min 57 s y (5b-2b) 24 min 14 s; 3.8 (6c) 18 min 33 s; 3.9 20 min 55 s; 4.1 (7a-1) 22 min 14 s y (7a-2) 22 min 56 s; 4.2 19 min 13 s; 4.3 BUILD SUCCESS en 17 min 21 s (Surefire 186 + 2 747, Failsafe 821, 0 fallos; JaCoCo cumplido), sobre el presupuesto.
Las primeras ejecuciones midieron 8 min 8 s (1.4), 11 min 13 s (1.3) y 11 min 5 s (02b3), y las de 3.3 y 3.4 de 9 a 13 min corriendo en `C:\dev\confia`, fuera de OneDrive. La suite pasó de 186 + 2 404 pruebas de Surefire y 617 de Failsafe (1.3) a 186 + 2 729 y 820 (4.2); no hay una causa única medida de la diferencia. El riesgo R-9 se materializó y queda abierto: partir las pruebas de integración o ejecutarlas en paralelo es trabajo de un cambio propio, no de este.

### Sondas P-1 a P-12

Resultado de las doce, con la prueba que la resuelve (todas existen y las que corren en `verify` pasaron en la ejecución completa de 4.3):

| Sonda | Resultado | Prueba |
|---|---|---|
| P-1 versión | 16.12.0, última publicada el 2026-05-29; fijada en `db-scheduler.version` (nota fechada del diseño, 2026-10-24) | `./mvnw validate` |
| P-2 iniciador | Carga y arranca con Spring Boot 4.1.1; el del iniciador se retira y el trabajador queda con un solo `Scheduler` propio | `SchedulerStarterCompatibilityTest` |
| P-3 convergencia | `dependencyConvergence` en verde; `micrometer-core` transitivo y ningún `MeterRegistry` | `./mvnw validate`, `WorkerHasNoMeterRegistryTest` |
| P-4 serializador | Envoltura propia sobre el serializador Jackson 3 de la biblioteca; solo identificadores y metadatos | `TracedJobSerializationTest` |
| P-5 calendario | Calendarios diario, semanal y de retardo fijo en `America/Tegucigalpa`; instantes exactos | `RecurringJobCalendarTest`, `RecurringJobCatalogTest` |
| P-6 permisos del cliente | **Difirió de lo esperado**: el cliente exige `SELECT` además de `INSERT`; `V10` lo concede al portal y no concede `UPDATE` ni `DELETE` | `PortalSchedulingPrivilegeIT` |
| P-7 confirmación del cliente | El cliente no confirma por su cuenta: invisible hasta el commit, y la reversión lo quita | `JobSchedulingAtomicityIT`, `ExitCriterion5EndToEndIT` |
| P-8 DDL | El catálogo de `scheduled_tasks` coincide con el DDL de la versión fijada | `ScheduledTasksSchemaIT` |
| P-9 creación automática | La biblioteca no crea la tabla y `confia_admin_app` no tiene `CREATE` | `ScheduledTasksNotCreatedAtRuntimeIT` |
| P-10 vida del proceso | `spring.main.keep-alive` mantiene la JVM del trabajador | `WorkerStaysAliveTest` |
| P-11 fugas de valores enlazados | La guardia neutral de logs cubre el controlador de PostgreSQL, con control negativo | `WorkerSensitiveLoggingIT` |
| P-12 jOOQ y funciones | Una rutina generada por función, con parámetros `OUT` | `RetentionPurgeFunctionsIT` |

### Partición propuesta

La rama completa mide 1 089 líneas. Costura natural: **PR 7b** = 4.2 y su corrección (`ee0d0a4`, `a9e5876`, 433 líneas) tal como está, y un **PR 7c** apilado sobre él con 4.3 (656 líneas: `CurrentAbsencesTest` 271, `JobsChangeTraceabilityTest` 237, `ExitCriterion5EndToEndIT` 109, el caso nuevo de `DocumentationNotesTest` 27 y la nota de `docs/09` 12). Crear la rama de 7c y confirmar 4.3 ahí lo hace el orquestador: este trabajo no cambia de rama.

### Verificación final del cambio: pendiente

No forma parte de este PR ni se marca: la compuerta de mutación completa en `main` después de la última fusión y, después, `sdd-verify` y `sdd-archive`. El archivo debe corregir además en el delta de `audit-trail` el resultado de la entrada de verificación fallida, de `failure` a `error` (hallazgo de 3.8), y el desajuste de la lista aprobada de SQL plano.

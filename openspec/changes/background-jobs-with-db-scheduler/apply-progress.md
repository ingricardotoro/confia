# Progreso de aplicación: trabajos en segundo plano con db-scheduler (cambio 9 de F0)

- **Cambio:** `background-jobs-with-db-scheduler` (F0, cambio 9; entregable 12, criterio de salida 5)
- **Modo:** TDD estricto (`./mvnw verify -Pmutation-gate` en `apps/api`, JDK 25, Docker en ejecución)
- **Estrategia de entrega:** `stacked-to-main`, tope de 800 líneas efectivas por PR; prevalece sobre `single-pr` con 400 de la preflight (ver `tasks.md`)
- **Última actualización:** 2026-10-24 (rebanada 1 completa: tareas 1.1, 1.2 y 1.3)
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

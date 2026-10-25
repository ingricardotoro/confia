# Progreso de aplicación: trabajos en segundo plano con db-scheduler (cambio 9 de F0)

- **Cambio:** `background-jobs-with-db-scheduler` (F0, cambio 9; entregable 12, criterio de salida 5)
- **Modo:** TDD estricto (`./mvnw verify -Pmutation-gate` en `apps/api`, JDK 25, Docker en ejecución)
- **Estrategia de entrega:** `stacked-to-main`, tope de 800 líneas efectivas por PR; prevalece sobre `single-pr` con 400 de la preflight (ver `tasks.md`)
- **Última actualización:** 2026-10-24 (rebanada 01a: tareas 1.1 y 1.2)
- **Regla de este archivo:** solo se añade; no se reescriben secciones existentes.

## Estado de las tareas

| Tarea | PR | Estado | Commits |
|---|---|---|---|
| 1.1 | PR 1 / 01a `01-scheduler-dependency-and-table` | Completa y verificada | `17bcae6` |
| 1.2 | PR 1 / 01a (condicional) | **No aplica**: P-1, P-2 y P-3 pasaron | — |
| 1.3 | PR 1 / 01b `01b-scheduled-tasks-table` | Pendiente: va en la rama 01b, apilada sobre esta | — |

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

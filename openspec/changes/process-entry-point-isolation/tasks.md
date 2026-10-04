# Tareas: aislamiento de los puntos de entrada por proceso

- **Cambio:** `process-entry-point-isolation` (F0, cambio 15)
- **Fase:** tareas
- **Fecha:** 2026-10-03
- **Estado:** pendiente de aprobación del propietario del producto
- **Entradas aprobadas:** `proposal.md`, `specs/build-integrity/spec.md` y `design.md`, los tres del
  2026-10-03
- **Modo de TDD:** estricto. Ejecutor: `./mvnw verify` en `apps/api`, con JDK 25 y Docker.

## Pronóstico de revisión

| Campo | Valor |
|---|---|
| Líneas de cambio estimadas (adiciones + eliminaciones) | **650 a 900** sin detección de renombrado; **550 a 800** con `-M`. Excluye `openspec/` |
| Riesgo frente al presupuesto | **Alto** frente a 400 de la preflight; **medio** frente al presupuesto del proyecto de 800 |
| Chained PRs recommended | Yes |
| Suggested split | Dos unidades, según `design.md` §9: (1) tareas 1.1 a 2.1, (2) tareas 3.1 a 6.1 |
| Delivery strategy | `auto-chain` (decidido por el propietario el 2026-10-03, en lugar de `single-pr`) |
| Chain strategy | `stacked-to-main`: el PR 1 se fusiona a `main` y el PR 2 parte de `main` actualizado, como en los cortes de `password-recovery-token` |
| Presupuesto del proyecto por pull request (`docs/15-flujo-de-trabajo-git.md` §3) | 800 líneas de cambio efectivo |

Decision needed before apply: No (resuelta el 2026-10-03)
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

**Honestidad del pronóstico.** La propuesta estimó unas 400 líneas. El desglose del diseño la
contradice: las pruebas nuevas (`ProcessBeanPolicy`, `ProcessBeanInspector`,
`ProcessBeanIsolationTest`, `ProcessBeanInspectorTest`, `BootstrapEntryPointRulesTest` y tres
fixtures) suman unas 450 a 550 líneas, el movimiento de las tres clases cuenta como borrado más alta
(unas 150 a 170 líneas) si Git no lo detecta como renombrado, y ADR-0024 más documentación aportan
otras 150. Con estrategia `single-pr` el PR queda por encima de 400, y puede acercarse al umbral de
ochocientas del proyecto. No se recorta con trucos de formato ni se omiten pruebas o comentarios.

**Decisión de entrega (2026-10-03).** El propietario eligió dos PR encadenados con el corte de
`design.md` §9: **PR 1** con las tareas 1.1 a 2.1 y **PR 2** con las tareas 3.1 a 6.1. Los
artefactos de planificación de `openspec/` viajan en el PR 1. Cada PR mide su diff antes de abrirse
y se detiene si supera las ochocientas líneas del proyecto.

### Suggested Work Units

| Unidad | Objetivo | PR probable | Comando de prueba enfocado | Arnés de ejecución | Frontera de reversión |
|---|---|---|---|---|---|
| 1 | Aislamiento por contexto: prueba de permitidos y prohibidos, movimiento de las tres clases y prueba negativa del inspector (tareas 1.1 a 2.1) | PR 1 | `./mvnw verify -Dtest='ProcessBeanIsolationTest,ProcessBeanInspectorTest,ConfiaApplicationTest' -Dsurefire.failIfNoSpecifiedTests=false` | `ConfiaApplication.launch` real de los tres procesos, sin base de datos, dentro de `ProcessBeanIsolationTest` | Las tres clases en `bootstrap/{admin,portal,worker}`, `ConfiaApplication` y las pruebas de `com.confia.bootstrap` |
| 2 | Reglas de ArchUnit, ADR-0024, documentación, skill y Javadoc (tareas 3.1 a 6.1) | PR 2 | `./mvnw verify -Dtest='BootstrapEntryPointRulesTest,SpringModulithVerificationTest' -Dsurefire.failIfNoSpecifiedTests=false` | `@ComponentScan` añadido de forma temporal a `WorkerApplication` rompe la mitad de producción de la tercera regla (tarea 3.2) | `BootstrapEntryPointRulesTest`, el fixture `entrypoints`/`entrypointclient`, ADR-0024 y las ediciones de documentación |

**Comandos.** Todos desde `apps/api`: `./mvnw verify` (la orden corta con `-Dtest=…` acota solo la
iteración; cada tarea cierra con `./mvnw verify` completo en verde, salvo la 1.1, que termina en
rojo). Convención de commits: Conventional Commits en inglés, sin atribución de herramientas de IA,
con pruebas y documentación en el mismo commit que el comportamiento que cubren.

---

## Fase 1: aislamiento de contextos, de rojo a verde

- [x] 1.1 **ROJO contra el estado actual (`design.md` §7, paso 1).** Crear, sin tocar producción,
  `apps/api/app/src/test/java/com/confia/bootstrap/ProcessBeanPolicy.java`,
  `apps/api/app/src/test/java/com/confia/bootstrap/ProcessBeanInspector.java` y
  `apps/api/app/src/test/java/com/confia/bootstrap/ProcessBeanIsolationTest.java` (decisiones 4 y 5:
  paquetes de origen por bean con `ClassUtils.getUserClass`, definición fusionada y bean fábrica;
  permitidos que fallan cerrado con coincidencia por igualdad o prefijo más punto; prohibidos
  nominales; tabla de la decisión 5; aserciones de violaciones vacías, una sola configuración de
  entrada y no vacuidad; más `dbSchedulerAbsenceIsVacuousUntilChange9`). Ejecutar
  `./mvnw verify -Dtest=ProcessBeanIsolationTest -Dsurefire.failIfNoSpecifiedTests=false` y registrar
  en `openspec/changes/process-entry-point-isolation/apply-progress.md` la salida
  observada. **Evidencia que cuenta:** (1) **tres** configuraciones de entrada en los contextos
  administrativo y del portal, y (2) `ContractSchemas` y `ProcessApiInfo` en el contexto del
  trabajador. **Criterio de parada: si cualquiera de las dos predicciones no se cumple, el
  diagnóstico era incorrecto al menos en parte: se detiene el cambio y se informa, sin tocar
  producción.** El resultado del paso termina en rojo; los archivos quedan en el árbol de trabajo y
  se comprometen junto con 1.2, porque un commit en rojo no debe entrar al historial. — Requisitos
  «Lista de permitidos», «Portal sin beans ajenos» y «Trabajador sin beans ajenos»; escenarios
  «Cada proceso solo contiene beans permitidos», «El portal contiene solo lo suyo» y «El trabajador
  recibe una importación ajena»

- [x] 1.2 **VERDE: mover y reanotar los tres puntos de entrada (decisiones 1 y 2).** Eliminar
  `apps/api/app/src/main/java/com/confia/bootstrap/AdminApplication.java`,
  `PortalApplication.java` y `WorkerApplication.java` y crear
  `apps/api/app/src/main/java/com/confia/bootstrap/admin/AdminApplication.java`,
  `apps/api/app/src/main/java/com/confia/bootstrap/portal/PortalApplication.java` y
  `apps/api/app/src/main/java/com/confia/bootstrap/worker/WorkerApplication.java`: clases públicas,
  no `final`, con `@SpringBootConfiguration` + `@EnableAutoConfiguration(exclude =
  DataSourceAutoConfiguration.class)`; administración y portal con `@Import({ContractSchemas.class,
  ProcessApiInfo.class})`; el trabajador sin `@Import`. Actualizar
  `apps/api/app/src/main/java/com/confia/bootstrap/ConfiaApplication.java` (importaciones
  calificadas y diagrama del Javadoc; sin cambios en la selección por `APP_PROFILE`, en el título del
  OpenAPI ni en `WebApplicationType.NONE`). Verificar en verde `ProcessBeanIsolationTest`,
  `ConfiaApplicationTest`, `OpenApiExposureByProfileTest`, `OpenApiContractSnapshotTest` **con la
  instantánea sin cambios** y `SpringModulithVerificationTest`; cerrar con `./mvnw verify` completo.
  **Si alguna prueba existente falla por el cambio de `@AutoConfigurationPackage`, se detiene y se
  informa.** Commit: `feat(bootstrap): isolate each process entry point with explicit imports`
  (incluye los tres archivos de la tarea 1.1). — Requisito «Registro explícito», escenario «Los tres
  puntos de entrada cumplen la forma explícita» (parte de arranque)

## Fase 2: prueba negativa del inspector

- [x] 2.1 **ROJO/VERDE: `ProcessBeanInspectorTest` (decisión 4, sección 6).** Crear
  `apps/api/app/src/test/java/com/confia/bootstrap/ProcessBeanInspectorTest.java` con tres
  `GenericApplicationContext` construidos a mano, sin Spring Boot: (1) un bean de una clase anidada
  de la propia prueba, fuera de toda lista, evaluado con `PORTAL`, produce una violación
  `not in the allow-list` que nombra el bean y el paquete; (2) `AdminApplication` registrada como
  bean simple y evaluada con `PORTAL` produce una violación que nombra `com.confia.bootstrap.admin` y
  el motivo de prohibición; (3) `PortalApplication`, `ContractSchemas` y un bean del JDK evaluados con
  `PORTAL` no producen ninguna violación. **Demostración registrada en `apply-progress.md`:** anular
  de forma temporal el cuerpo de `ProcessBeanInspector.violations` hace fallar los casos 1 y 2; se
  revierte y vuelve a verde. Cerrar con `./mvnw verify` completo. Commit:
  `test(bootstrap): add a permanent negative test for the isolation inspector`. — Requisito «Prueba
  negativa permanente del inspector», sus tres escenarios; escenarios «Un bean fuera de la lista
  rompe la construcción», «Un módulo nuevo obliga a editar la lista» y «El portal arrastra un punto
  de entrada ajeno / un módulo administrativo»

## Fase 3: reglas de ArchUnit con fixture permanente

- [ ] 3.1 **ROJO: `BootstrapEntryPointRulesTest` sin fixture (decisión 6).** Crear
  `apps/api/app/src/test/java/com/confia/architecture/BootstrapEntryPointRulesTest.java` con las tres
  reglas parametrizadas por raíz (`entryPointsDoNotDependOnEachOther`,
  `nothingOutsideReferencesAnEntryPoint` y `noEntryPointScansComponents`) y, por regla, la mitad de
  producción (raíz `com.confia.bootstrap`, en verde desde 1.2) y la mitad de fixture con
  `ArchitectureTestSupport.assertRuleRejects` (raíz `com.confia.architecture.fixture.entrypoints`,
  con el nombre simple de la clase infractora como fragmento obligatorio). Ejecutar
  `./mvnw verify -Dtest=BootstrapEntryPointRulesTest -Dsurefire.failIfNoSpecifiedTests=false` y
  registrar que las tres mitades de fixture fallan por conjunto vacío (ADR-0018,
  `archRule.failOnEmptyShould=true`). El resultado termina en rojo y se compromete junto con 3.2.
  — Requisitos «Registro explícito», «Subpaquetes de `bootstrap` no dependen entre sí» y «Nada fuera
  de `bootstrap` referencia una clase de entrada»

- [ ] 3.2 **VERDE: fixture y Spring Modulith.** Crear las tres clases del fixture, sin
  `@SpringBootConfiguration`: `apps/api/app/src/test/java/com/confia/architecture/fixture/entrypoints/admin/ScanningEntryPoint.java`
  (lleva `@ComponentScan`), `apps/api/app/src/test/java/com/confia/architecture/fixture/entrypoints/portal/CrossEntryPointDependency.java`
  (referencia `ScanningEntryPoint`) y
  `apps/api/app/src/test/java/com/confia/architecture/fixture/entrypointclient/OutsideEntryPointReference.java`
  (referencia `ScanningEntryPoint` desde fuera de la raíz). Actualizar
  `apps/api/app/src/test/java/com/confia/architecture/SpringModulithVerificationTest.java` para que
  `entrypoints` y `entrypointclient` sean módulos del fixture con una violación más, sin cambiar el
  resultado esperado, y confirmar que `LayeredArchitectureTest` no ve los paquetes nuevos. **Demostración
  registrada:** `@ComponentScan` añadido de forma temporal a `WorkerApplication` rompe la mitad de
  producción de la tercera regla; se revierte. Cerrar con `./mvnw verify` completo. Commit:
  `test(architecture): enforce explicit entry point registration with permanent fixtures`
  (incluye `BootstrapEntryPointRulesTest` de 3.1). — Requisitos «Registro explícito», «Subpaquetes
  de `bootstrap` no dependen entre sí» y «Nada fuera de `bootstrap` referencia una clase de entrada»;
  escenarios «Un punto de entrada vuelve a escanear», «Fixture con dependencia entre subpaquetes»,
  «Código de producción sin dependencias cruzadas», «Fixture que referencia una clase de entrada
  desde fuera de `bootstrap`» y «Código de producción sin referencias externas»

## Fase 4: ADR-0024

- [ ] 4.1 **ADR-0024 y su registro (decisión 7, sección 8).** Crear
  `docs/adr/ADR-0024-registro-explicito-por-punto-de-entrada.md` en español neutro profesional, con
  estado Aceptado, contexto con la evidencia en rojo de 1.1, factores, opciones A a D, decisión D,
  la aclaración de «el trabajador corre con el perfil administrativo» de ADR-0003, la regla para
  módulos futuros (configuración pública en el paquete base o en un `@NamedInterface`, creada cuando
  exista el consumidor, con las dos ediciones visibles `@Import` y `ProcessBeanPolicy`),
  consecuencias (incluido `@AutoConfigurationPackage` apuntando al subpaquete), cumplimiento y
  referencias. Registrarlo en `docs/adr/README.md`. Sin editar el cuerpo de ADR-0003. Commit:
  `docs(adr): add ADR-0024 explicit registration per process entry point`.
  — Criterio de éxito «ADR-0024 aceptado y aclara el significado de perfil administrativo»

## Fase 5: documentación, skill y Javadoc

- [ ] 5.1 **Documentación de arquitectura y roadmap (decisión 8).** Actualizar
  `docs/01-arquitectura.md` §4 (árbol de `bootstrap/` con los tres subpaquetes y el párrafo de la
  regla de dependencia, citando ADR-0024) y `docs/09-roadmap-y-fases.md` (nota del riesgo del
  2026-09-30 marcada como resuelta por el cambio 15, con referencia a la prueba que lo demuestra). El
  cuerpo de ADR-0003 no se toca. Commit: `docs(architecture): describe per-process entry point
  packages and close the change 15 risk`.

- [ ] 5.2 **Skill `confia-module-scaffold` §4 (decisión 8).** Reescribir en
  `.claude/skills/confia-module-scaffold/SKILL.md` §4 el párrafo que hoy dice que el mecanismo «se
  fija en F0»: describir el `@Import` de la configuración pública del módulo en la clase de entrada y
  la edición obligatoria de `ProcessBeanPolicy`, con referencia a ADR-0024 y a ADR-0022. Commit:
  `docs(skills): explain how a module registers in its entry point`.

- [ ] 5.3 **Javadoc obsoleto (decisión 8).** Editar, solo comentarios:
  `apps/api/app/src/main/java/com/confia/shared/web/openapi/ProcessApiInfo.java` (el título
  acompaña a la selección del proceso en un único lugar, sin justificarlo por el escaneo compartido),
  `apps/api/app/src/main/java/com/confia/shared/web/openapi/package-info.java` (consumidores
  `bootstrap.admin.AdminApplication` y `bootstrap.portal.PortalApplication`, sin escaneo),
  `apps/api/app/src/main/java/com/confia/organization/infrastructure/JooqInstitutionRepository.java`
  (una línea) y `apps/api/app/src/test/java/com/confia/support/IntegrationTestApplication.java` (una
  línea). Confirmar que la regla de ArchUnit de referencias externas sigue en verde (Javadoc no
  genera dependencias de bytecode). Commit: `docs(api): refresh Javadoc that described the shared
  component scan`.

## Fase 6: cierre

- [ ] 6.1 **`./mvnw verify` completo, medición del diff y barrido de trazabilidad.** Ejecutar
  `./mvnw verify` completo en `apps/api` y registrar el resultado de cobertura y mutación. Medir el
  diff de autor con `git diff --stat main...` y `git diff --numstat main...` (adiciones más
  eliminaciones), una vez **sin** `-M` y otra **con** `-M` para mostrar cuánto del movimiento de las
  tres clases Git detecta como renombrado, excluyendo `openspec/`. Registrar ambas cifras en
  `apply-progress.md` junto con la estrategia de entrega elegida. **El corte en dos PR ya está
  decidido; esta medición corresponde al PR 2 contra `main` con el PR 1 ya fusionado. Si supera
  800, se detiene y se consulta antes de abrir el PR.**
  Recontar los 19 escenarios del delta contra la tabla de trazabilidad y marcar los criterios de
  éxito de `proposal.md`. Commit solo si se corrige algo: `chore(bootstrap): <outcome>`.

---

## Trazabilidad: escenarios del delta

| Requisito | Escenario | Tarea |
|---|---|---|
| Lista de permitidos | Un bean fuera de la lista rompe la construcción | 1.1, 2.1 |
| | Un módulo nuevo obliga a editar la lista | 2.1 |
| | Cada proceso solo contiene beans permitidos | 1.1, 1.2 |
| Portal sin beans ajenos | El portal arrastra un punto de entrada ajeno | 2.1, 1.1 |
| | El portal arrastra un módulo administrativo | 2.1, 1.1 |
| | El portal contiene solo lo suyo | 1.1, 1.2 |
| Trabajador sin beans ajenos | El trabajador recibe una importación ajena | 1.1, 1.2 |
| | El trabajador arranca sin servidor web y sin beans ajenos | 1.2 |
| Registro explícito | Un punto de entrada vuelve a escanear | 3.1, 3.2 |
| | Los tres puntos de entrada cumplen la forma explícita | 1.2, 3.2 |
| Subpaquetes sin dependencias cruzadas | Fixture con dependencia entre subpaquetes | 3.1, 3.2 |
| | Código de producción sin dependencias cruzadas | 3.1, 3.2 |
| Nada fuera referencia una entrada | Fixture que referencia una clase de entrada | 3.1, 3.2 |
| | Código de producción sin referencias externas | 3.1, 5.3 |
| db-scheduler vacuo hasta el cambio 9 | La aserción es vacua y está declarada | 1.1 |
| | Se vuelve efectiva con la biblioteca presente | 1.1 (el cambio 9 lo demuestra) |
| Prueba negativa del inspector | El inspector detecta un bean fuera de la lista | 2.1 |
| | El inspector detecta un bean prohibido nominalmente | 2.1 |
| | El inspector no reporta un contexto limpio | 2.1 |

Ningún escenario queda sin tarea. Los entregables sin escenario (ADR-0024, documentación, skill y
Javadoc) están cubiertos por las tareas 4.1 a 5.3 y por los criterios de éxito de la propuesta.

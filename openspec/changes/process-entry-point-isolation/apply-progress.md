# Progreso de aplicación: aislamiento de los puntos de entrada por proceso

- **Cambio:** `process-entry-point-isolation`
- **Modo:** TDD estricto. Ejecutor: `./mvnw verify` en `apps/api` (JDK 25.0.3, Docker).
- **Estrategia de entrega:** `auto-chain`, `stacked-to-main`. Este lote es el **PR 1** (tareas 1.1, 1.2 y 2.1).
- **Estado:** 10 de 10 tareas completas (PR 1: 1.1, 1.2, 2.1; PR 2: 3.1 a 6.1, ver la última sección).

## Tareas

- [x] 1.1 ROJO contra el estado actual
- [x] 1.2 VERDE: mover y reanotar los tres puntos de entrada (commit `21b16a2`)
- [x] 2.1 `ProcessBeanInspectorTest` (commit `468d772`)

## Evidencia de ROJO (tarea 1.1)

Comando: `./mvnw verify -Dtest=ProcessBeanIsolationTest -Dsurefire.failIfNoSpecifiedTests=false`
sobre el árbol sin cambios de producción. Resultado: `Tests run: 4, Failures: 3` (las tres
ejecuciones de `registersOnlyItsAllowedBeans`; `dbSchedulerAbsenceIsVacuousUntilChange9` pasa).

**Predicción 1 (confirmada).** Administración y portal contienen **tres** configuraciones de entrada:

```
[exactly one entry point configuration in the admin context]
["adminApplication", "portalApplication", "workerApplication"]
[exactly one entry point configuration in the portal context]
["portalApplication", "adminApplication", "workerApplication"]
```

**Predicción 2 (confirmada).** El trabajador contiene `ContractSchemas` y `ProcessApiInfo`, además de
las tres clases de entrada:

```
process 'worker': bean 'com.confia.shared.web.openapi.ContractSchemas' from package 'com.confia.shared.web.openapi' - forbidden: the worker never serves the OpenAPI surface (ADR-0003)
process 'worker': bean 'com.confia.shared.web.openapi.ProcessApiInfo' from package 'com.confia.shared.web.openapi' - forbidden: the worker never serves the OpenAPI surface (ADR-0003)
```

Las tres clases actuales también incumplen la lista de permitidos por estar en `com.confia.bootstrap`
(secundario y esperado). El diagnóstico de la propuesta era correcto: no se detuvo el cambio.

## Evidencia de VERDE (tarea 1.2)

`./mvnw verify` completo: `BUILD SUCCESS`. Surefire 186 + 406 (tras 2.1; 403 tras 1.2), Failsafe 225,
sin fallos. En verde: `ProcessBeanIsolationTest` (4), `ConfiaApplicationTest` (10),
`OpenApiExposureByProfileTest` (8), `OpenApiContractSnapshotTest` (10) con la instantánea sin cambios
(ningún archivo de instantánea modificado) y `SpringModulithVerificationTest` (2). Ninguna prueba
preexistente falló por el cambio de `@AutoConfigurationPackage`.

## Evidencia de la tarea 2.1

- `ProcessBeanInspectorTest` (3 casos) en verde: `Tests run: 3, Failures: 0`.
- **Demostración de protección:** se anuló temporalmente el cuerpo de `ProcessBeanInspector.violations`
  (`return List.of()` al inicio). Resultado: `Tests run: 3, Failures: 2`
  (`reportsABeanOutsideTheAllowListNamingTheBeanAndItsPackage` y
  `reportsANominallyForbiddenBeanNamingThePackageAndTheReason`); el caso 3 sigue en verde, como debe.
  Revertido; `git status` sin cambios en `ProcessBeanInspector.java`.
- Un primer intento falló por una expectativa mía incorrecta: Spring nombra el bean anidado con el
  nombre calificado (`...ProcessBeanInspectorTest$UnlistedBean`). Se corrigió la aserción de la prueba,
  no el inspector.
- `./mvnw verify` completo tras 2.1: `BUILD SUCCESS` (Surefire 406, Failsafe 225, sin fallos).

## Evidencia de ciclo TDD

| Tarea | Archivo de prueba | Nivel | Red de seguridad | ROJO | VERDE | TRIANGULACIÓN | REFACTOR |
|---|---|---|---|---|---|---|---|
| 1.1/1.2 | `ProcessBeanIsolationTest` | Contexto real, sin contenedor | Línea base: `./mvnw verify` previo en verde (arranque, OpenAPI, Modulith) | Escrita; 3 de 4 fallan contra producción actual | 4/4 y `verify` completo | 3 procesos parametrizados | Sin cambios necesarios |
| 2.1 | `ProcessBeanInspectorTest` | Unitario con `GenericApplicationContext` | N/A (nuevo) | Mutación temporal: 2 de 3 fallan | 3/3 y `verify` completo | 3 casos (fuera de lista, prohibido, limpio) | Aserción de nombre corregida |

## Evidencia de unidad de trabajo

| Evidencia | Valor |
|---|---|
| Comando enfocado | `./mvnw verify -Dtest='ProcessBeanIsolationTest,ProcessBeanInspectorTest,ConfiaApplicationTest' -Dsurefire.failIfNoSpecifiedTests=false`; los dos primeros se observaron en verde dentro de `./mvnw verify` completo |
| Arnés de ejecución | `ConfiaApplication.launch` real de los tres procesos, sin base de datos, dentro de `ProcessBeanIsolationTest` |
| Frontera de reversión | Las tres clases en `bootstrap/{admin,portal,worker}`, `ConfiaApplication` y las pruebas nuevas de `com.confia.bootstrap` |

## Medición del diff del PR 1 (`git diff --numstat main...HEAD -- . ':!openspec'`)

- **Sin `-M`:** 426 adiciones + 85 eliminaciones = **511 líneas**.
- **Con `-M`:** idéntico, 511. Git no detecta renombrado porque la reescritura (anotaciones y Javadoc)
  deja poca similitud entre cada clase movida y su original.
- Dentro del umbral de 800 del proyecto. Sobre las 400 de la preflight: es el pronóstico de `tasks.md`
  (`auto-chain`); 332 de las 511 son código de prueba nuevo (soporte e inspector incluidos); el resto son las tres clases movidas con su Javadoc y `ConfiaApplication`.

## Desviaciones del diseño

Ninguna de comportamiento. Notas menores: `ProcessBeanInspector` no usa `LinkedHashSet` (un `TreeSet`
da orden estable); la lista de permitidos del trabajador produce dos líneas para un bean prohibido y
ausente de la lista a la vez (`ContractSchemas`), lo cual es informativo y no afecta el resultado.

## Corrección tras la revisión del PR #78 (hallazgo I1)

La revisión encontró que dos escenarios trazados a las tareas 1.1 y 2.1 no tenían una prueba
permanente que pudiera fallar: «El portal arrastra un módulo administrativo» y «El trabajador recibe
una importación ajena». El portal rechaza un bean de `identity` también por su lista de permitidos,
así que un error de escritura en la entrada prohibida pasaba inadvertido.

- `ProcessBeanInspectorTest` suma dos casos: un caso de uso de `identity` registrado de forma perezosa
  en el portal debe reportarse con su motivo nominal («forbidden: staff-only module»), y
  `ContractSchemas` en el trabajador debe reportarse como superficie OpenAPI prohibida.
- **Demostración de protección:** con las entradas `com.confia.identity` y
  `com.confia.shared.web.openapi` de `ProcessBeanPolicy` alteradas temporalmente, fallan
  exactamente los dos casos nuevos (`Tests run: 5, Failures: 2`). Revertido.
- `./mvnw verify` completo: `BUILD SUCCESS` (Surefire 186 + 408, Failsafe 225, sin fallos).
- Límite conocido: `invoicing`, `cashbox` y `reconciliation` todavía no tienen clases, así que
  sus entradas solo se pueden ejercitar cuando exista el primer bean de cada módulo.

---

# PR 2: tareas 3.1, 3.2, 4.1, 5.1, 5.2, 5.3 y 6.1

- **Rama:** `change/process-entry-point-isolation-rules-and-adr` (desde `main` con el PR 1 fusionado).
- **Estado:** 10 de 10 tareas completas. Los commits de este lote no se han empujado.

## Tareas del PR 2

- [x] 3.1 ROJO: `BootstrapEntryPointRulesTest` sin fixture (se compromete con 3.2)
- [x] 3.2 VERDE: fixture y Spring Modulith (commit `89e775b`)
- [x] 4.1 ADR-0024 y su registro (commit `d230f35`)
- [x] 5.1 Documentación de arquitectura y roadmap (commit `059b23b`)
- [x] 5.2 Skill `confia-module-scaffold` §4 (commit `cf12457`)
- [x] 5.3 Javadoc obsoleto (commit `5258a8a`)
- [x] 6.1 `./mvnw verify` completo, medición y trazabilidad (sin correcciones, sin commit propio)
- Seguimiento de la revisión del PR #78 (S1, S2, S3): commit `f4b0173`

## Evidencia de ROJO (tarea 3.1)

Comando: `./mvnw verify -Dtest=BootstrapEntryPointRulesTest -Dsurefire.failIfNoSpecifiedTests=false`
sin las clases del fixture. Resultado: `Tests run: 6, Failures: 3`. Fallan exactamente las tres
mitades de fixture (`rejectsTheFixtureEntryPointDependingOnAnotherEntryPoint`,
`rejectsTheFixtureClassOutsideTheRootReferencingAnEntryPoint` y
`rejectsTheFixtureEntryPointThatScansComponents`), con el mensaje de ArchUnit `failed to check any
classes` (conjunto vacío, `archRule.failOnEmptyShould=true`, ADR-0018). Las tres mitades de
producción pasan desde 1.2.

## Evidencia de VERDE y demostración (tarea 3.2)

- Con las tres clases del fixture (`entrypoints/admin/ScanningEntryPoint`,
  `entrypoints/portal/CrossEntryPointDependency`, `entrypointclient/OutsideEntryPointReference`):
  `BootstrapEntryPointRulesTest` `Tests run: 6, Failures: 0`; `SpringModulithVerificationTest`
  `Tests run: 2, Failures: 0`; `LayeredArchitectureTest` (2), `NoCyclesTest` (2),
  `NoCrossModuleDomainImportsTest` (3) y `NoTechnicalLayerPackageNamesTest` (2) en verde, es decir,
  no ven los paquetes nuevos. `SpringModulithVerificationTest` no necesitó cambio de código: el
  resultado esperado (`Violations`) es el mismo; solo se actualizó su Javadoc.
- **Demostración:** se añadió `@ComponentScan` de forma temporal a `WorkerApplication`. Resultado:
  `Tests run: 6, Failures: 1`; falla `productionEntryPointsDoNotScanComponents` con «Class
  <com.confia.bootstrap.worker.WorkerApplication> is meta-annotated with @ComponentScan». Revertido
  con `git checkout`; `git status` sin cambios en ese archivo.
- `./mvnw verify` completo: `BUILD SUCCESS`, Surefire 186 + 414, Failsafe 225.

## Seguimiento de la revisión del PR #78

- **S1.** `ProcessBeanIsolationTest` afirma que el contexto del trabajador no es un
  `WebServerApplicationContext`. **Demostración:** con `ConfiaApplication` cambiado temporalmente a
  `WebApplicationType.SERVLET` para el trabajador, falla con «the worker must start without a web
  server» (`Tests run: 4, Failures: 1`). Revertido.
- **S2.** La comprobación de no vacuidad exige, además del paquete de entrada, un bean de cada
  otro paquete permitido (hoy `com.confia.shared.web.openapi` en administración y portal), derivado
  de `ProcessBeanPolicy`, sin repetir nombres. **Demostración:** con `@Import({})` en
  `AdminApplication`, falla con «non-vacuous: com.confia.shared.web.openapi must contribute a bean
  to the admin context». Revertido.
- **S3.** La regla `nothingOutsideReferencesAnEntryPoint` (tarea 3.1) está en su sitio, con sus dos
  mitades.

## Tabla de ciclo TDD (PR 2)

| Tarea | Archivo de prueba | Nivel | Red de seguridad | ROJO | VERDE | TRIANGULACIÓN | REFACTOR |
|---|---|---|---|---|---|---|---|
| 3.1/3.2 | `BootstrapEntryPointRulesTest` | Estático (ArchUnit) | Línea base de `verify` en verde | 3 de 6 fallan por conjunto vacío | 6/6 | Dos mitades por regla (producción y fixture) | Sin cambios necesarios |
| S1/S2 | `ProcessBeanIsolationTest` | Contexto real | 4/4 previo | Mutación temporal: falla 1 de 4 en cada demostración | 4/4 | Una demostración por aserción | Paquetes importados derivados de la política |
| 4.1 a 5.3 | N/A (documentación y Javadoc) | N/A | `BootstrapEntryPointRulesTest` y Modulith en verde tras 5.3 | N/A | N/A | N/A (sin lógica) | N/A |

## Tarea 6.1: cierre

- `./mvnw verify` completo, `BUILD SUCCESS`: Surefire 186 (kernel) + 414 (app), Failsafe 225, sin
  fallos. JaCoCo: «All coverage checks have been met» en `kernel` y en `app`.
- **Mutación:** PIT no se ejecutó, porque pertenece a los perfiles `mutation-gate` y
  `mutation-report` y no al `verify` por defecto; además este cambio no toca `kernel` ni ningún
  paquete `domain`, que es su alcance.
- **Diff del PR 2 contra `main`, excluyendo `openspec/` (`git diff --numstat main...HEAD`):**
  - **Sin `-M`:** 349 adiciones + 25 eliminaciones = **374 líneas**.
  - **Con `-M`:** idéntico, 374 (este PR no mueve archivos).
  - Dentro de las 800 del proyecto y también de las 400 de la preflight.
  - Desglose aproximado: ADR-0024 151; reglas de ArchUnit 79; fixture 42; ajustes de
    `ProcessBeanIsolationTest` 24; documentación, skill y Javadoc el resto.
- **Estrategia de entrega:** `auto-chain`, `stacked-to-main`; este es el PR 2 de 2.
- **Trazabilidad:** los 19 escenarios del delta siguen cubiertos por la tabla de `tasks.md` (3+3+2+2+2+2+2+3
  filas, ninguno sin tarea). Los de las tareas 3.1 y 3.2 se demuestran con las seis pruebas de
  `BootstrapEntryPointRulesTest`; el de «Código de producción sin referencias externas» queda
  además cubierto por 5.3, porque el Javadoc no genera dependencias de bytecode y la regla sigue
  en verde.
- **Criterios de éxito de `proposal.md`:** todos marcados.

## Desviaciones del diseño (PR 2)

Ninguna de comportamiento. `SpringModulithVerificationTest` solo cambió en su Javadoc (el
resultado esperado no cambia, como pedía el diseño). Los tres comandos con `-Dtest` ejecutan
igualmente los ITs de Failsafe; con `-DskipITs` el build termina en `BUILD FAILURE` por
`failIfNoSpecifiedTests` de Failsafe, aunque las pruebas seleccionadas pasan: es ruido del
comando acotado, no un fallo.

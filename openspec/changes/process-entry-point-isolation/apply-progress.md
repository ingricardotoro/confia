# Progreso de aplicación: aislamiento de los puntos de entrada por proceso

- **Cambio:** `process-entry-point-isolation`
- **Modo:** TDD estricto. Ejecutor: `./mvnw verify` en `apps/api` (JDK 25.0.3, Docker).
- **Estrategia de entrega:** `auto-chain`, `stacked-to-main`. Este lote es el **PR 1** (tareas 1.1, 1.2 y 2.1).
- **Estado:** 3 de 10 tareas completas (1.1, 1.2, 2.1). Pendientes: 3.1, 3.2, 4.1, 5.1, 5.2, 5.3, 6.1.

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

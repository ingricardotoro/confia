```yaml
schema: gentle-ai.verify-result/v1
evidence_revision: sha256:15aad501292b9c97ed6ff43807fc8e679ae9690a9563626061400fc2ec2c40a0
verdict: pass_with_warnings
blockers: 0
critical_findings: 0
requirements: 8/8
scenarios: 15/15
test_command: ./mvnw -B verify
test_exit_code: 0
test_output_hash: sha256:e5bbe2ca2d770635f2a7e91750b47f9cfa645ae449ed5c11a415badab89af997
build_command: ./mvnw -B verify
build_exit_code: 0
build_output_hash: sha256:e5bbe2ca2d770635f2a7e91750b47f9cfa645ae449ed5c11a415badab89af997
```

## Verification Report — Ronda 5 (final)

**Change**: maven-workspace-and-ci-skeleton (F0, cambio 1 de 13)
**Version**: `specs/build-integrity/spec.md` enmendada en `ecefe48` — 8 requisitos, 15 escenarios
**Mode**: Standard (`strict_tdd: false`)
**Commit verificado**: `ecefe4860247e667239ffa6e808fd096d24f5b40`, rama `change/maven-workspace-and-ci-skeleton`
**Comando real**: `JAVA_HOME=/c/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot ./mvnw -B verify`, ejecutado en `apps/api`
**`evidence_revision`**: SHA-256 de las tres líneas `commit=<HEAD>`, `spec_blob=0d75bbf09688c0e5bf5cd0e72bf96a14ce704428` y `build_output=sha256:<hash de la salida>`, en ese orden y terminadas en salto de línea.

Criterio de conteo, idéntico al de las rondas 1 a 4: un escenario cuenta como completado solo si es
COMPLIANT; un requisito cuenta como completado solo si todos sus escenarios lo son.

---

### Historial de rondas

| Ronda | Commit | Veredicto | Requisitos | Escenarios | Qué cerró o abrió |
|---|---|---|---|---|---|
| 1 | — | FAIL | — | — | Abrió C1, C2, C3, W1 a W5; C2 cerrado por enmienda de la especificación |
| 2 | — | FAIL | — | — | C1 cerrado; abrió C1-bis (bloqueante), W6, W7 |
| 3 | `3d77388` | FAIL | — | — | C1-bis, C3, W6, W7 cerrados; abrió C2-bis (bloqueante); W8 en el addendum |
| 4 | `10ed68b` | FAIL, 0 bloqueantes | 7/8 | 15/16 | C2-bis y W1 cerrados con corridas observadas en el remoto; W8 reformulado como W9; único pendiente W2 (PARTIAL) |
| **5** | **`ecefe48`** | **PASS WITH WARNINGS** | **8/8** | **15/15** | W2 sale de este cambio por enmienda de la especificación (salida 1 de la ronda 4, elegida por el propietario el 2026-09-17) |

El detalle de las rondas 1 a 4 está en el historial de git de este archivo (`5bdee91` y anteriores).

---

### Qué cambió desde la ronda 4

`git diff --stat 10ed68b..ecefe48` devuelve tres archivos, todos documentales:

```text
 docs/09-roadmap-y-fases.md                         |  13 +-
 .../specs/build-integrity/spec.md                  |  12 +-
 .../verify-report.md                               | 620 ++++++++++++---------
```

El tercero es el propio informe de la ronda 4 (`5bdee91`). `git diff 10ed68b ecefe48 -- apps/api .github`
devuelve **cero líneas**: no cambió código de producción, código de prueba, ningún `pom.xml` ni el
flujo de integración continua. `tasks.md` tampoco cambió.

- **`ecefe48`** retira de la especificación el escenario «Cada módulo usa solo su propio dominio» y lo
  sustituye por una nota «Escenario diferido» en formato de cita, sin encabezado `#### Escenario`.
- **`9cecb55`** amplía la nota del roadmap (entregable 8 de F0) al enunciado que recomendó la ronda 4:
  el escaneo analiza los `pom.xml` tal como están declarados, no el árbol resuelto.

---

### Auditoría de la enmienda

Se pidió confirmar que la nota es exacta y que no oculta ningún otro requisito. Resultado: **es exacta
y no oculta nada**, con una precisión menor registrada como S11.

1. **Conteo medido, no supuesto.** `grep -cE '^### Requisito'` → 8; `grep -cE '^#### Escenario'` → 15.
   Por requisito: 1, 2, 2, 2, 2, 2, 2, 2. El requisito 1 conserva el escenario negativo «Importación
   cruzada de dominio», así que sigue teniendo cobertura propia y verificable.
2. **El diff de `ecefe48` toca un solo bloque.** Elimina las cinco líneas del escenario y añade siete
   de nota. Ningún otro requisito, escenario, enunciado normativo ni referencia a ADR cambió.
3. **La nota describe el escenario con fidelidad.** Reproduce sus tres cláusulas; la premisa pasa de
   «los mismos dos módulos» a «dos módulos de negocio existentes», que es la lectura correcta una vez
   separado del escenario hermano.
4. **Los destinos existen y son los correctos.** `institution-root-and-multitenancy-baseline` y
   `staff-authentication-mfa-sessions` son los cambios 4 y 7 de la tabla de
   `openspec/changes/foundations-plan/exploration.md`; el cambio 4 ya figura en ADR-0018 como quien
   introduce el primer módulo.
5. **La justificación coincide con lo que ya decía la propuesta.** `proposal.md`, línea 68: el criterio
   «solo se puede comprobar por completo cuando existan al menos dos módulos de negocio (cambios 4 y 7)».
   La enmienda no inventa un traslado: formaliza uno ya anunciado, con el mismo precedente que la
   enmienda del requisito 7 en la ronda 1.
6. **Ninguna otra referencia quedó huérfana.** `grep -rn "propio dominio"` sobre `openspec` y `docs`
   (excluido este informe) solo encuentra la nota; `tasks.md` y `design.md` no citan el escenario.

La prueba `NoCrossModuleDomainImportsTest > productionCodeHasNoCrossModuleDomainImportYet` sigue
existiendo y pasando. Ya no cubre ningún escenario de este cambio; se conserva como guardia que el
cambio 7 convertirá en evidencia real cuando existan dos módulos.

---

### Completeness

| Metric | Value |
|--------|-------|
| Tasks total | 24 (15 originales + 6 de la Fase 5 + 3 de la Fase 6) |
| Tasks complete | 24 |
| Tasks incomplete | 0 |

Contadas con `grep -cE "^- \[x\]" tasks.md` → 24 y `grep -cE "^- \[ \]" tasks.md` → 0.

---

### Build & Tests Execution

**Build**: Passed — `BUILD SUCCESS`, exit 0, 20.5 s, sobre el árbol real en `ecefe48`.

```text
[INFO] --- enforcer:3.6.3:enforce (enforce-build-integrity) @ confia-api-parent ---
[INFO] Rule 0: org.apache.maven.enforcer.rules.version.RequireJavaVersion passed
[INFO] Rule 1: org.apache.maven.enforcer.rules.dependency.DependencyConvergence passed
[INFO] Rule 2: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed
[INFO] --- enforcer:3.6.3:enforce (enforce-kernel-purity) @ confia-kernel ---
[INFO] Rule 0: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed
[INFO] CONFIA API Parent .................................. SUCCESS [  1.789 s]
[INFO] CONFIA Kernel ...................................... SUCCESS [  4.261 s]
[INFO] CONFIA API ......................................... SUCCESS [ 13.724 s]
[INFO] BUILD SUCCESS
```

`DependencyConvergence` y `BannedDependencies` en verde en los tres módulos. Hash de la salida
completa: `sha256:e5bbe2ca2d770635f2a7e91750b47f9cfa645ae449ed5c11a415badab89af997`.

**Tests**: 23 passed / 0 failed / 0 skipped (1 en `confia-kernel`, 22 en `confia-api`).

```text
[INFO] Tests run: 1  -- in com.confia.kernel.BuildSmokeTest
[INFO] Tests run: 1  -- in com.confia.architecture.EmptyShouldExceptionInventoryTest
[INFO] Tests run: 2  -- in com.confia.architecture.LayeredArchitectureTest
[INFO] Tests run: 2  -- in com.confia.architecture.NoCrossModuleDomainImportsTest
[INFO] Tests run: 2  -- in com.confia.architecture.NoCyclesTest
[INFO] Tests run: 2  -- in com.confia.architecture.NoTechnicalLayerPackageNamesTest
[INFO] Tests run: 2  -- in com.confia.architecture.SpringModulithVerificationTest
[INFO] Tests run: 1  -- in com.confia.architecture.SuppressionCitesAdrTest
[INFO] Tests run: 10 -- in com.confia.bootstrap.ConfiaApplicationTest
```

**Coverage**: no disponible — JaCoCo y los umbrales llegan con el cambio 2 (fuera de alcance).

**Integración continua en el commit verificado**: corrida `35292187021` sobre `ecefe48`, consultada con
`gh run view`: `backend verify: success`, `security scanning: success`.
`origin/change/maven-workspace-and-ci-skeleton` apunta a `ecefe48`; `git status --porcelain` vacío
antes y después de la construcción.

---

### Evidencia heredada y por qué la herencia es válida

Como `git diff 10ed68b ecefe48 -- apps/api .github` es vacío, la evidencia observada en el remoto que
la ronda 4 verificó de primera mano sigue aplicando al mismo código:

| Evidencia | Corrida / probe | Qué demuestra |
|---|---|---|
| Violación de capas en código de producción, `backend verify` en rojo | `35188175663` (`bc013e4`) | Req. 5 «`domain` importa `infrastructure`» y Req. 6 «Empuje con violación»; además el rojo programado de ADR-0018 disparándose |
| `log4j-core:2.14.1`, `Total: 3 (HIGH: 1, CRITICAL: 2)`, `exit code 1` | `35188358113` (`0ca32d4`) | Req. 7 «Vulnerabilidad crítica» |
| Reversión completa de las probes | `35188476297` (`aaec6a2`) | Árbol limpio, ambos trabajos en verde |
| Probes R1 a R9 y P2, P5, D | Rondas 1 a 3 | Mitades negativas no vacuas, enforcer, `kernel`, supresiones |

---

### Spec Compliance Matrix

| Requirement | Scenario | Test | Result |
|-------------|----------|------|--------|
| 1. Frontera de dominio entre módulos | Importación cruzada de dominio | `NoCrossModuleDomainImportsTest > rejectsTheFixtureCrossModuleDomainImport` y `SpringModulithVerificationTest > rejectsTheFixtureModuleCrossingInternalAccess`, en verde en esta ronda; no vacuas por la probe D | COMPLIANT |
| 2. Pureza del módulo `kernel` | `kernel` importa Spring | `enforce-kernel-purity` en fase `validate`; probe P2, `kernel/pom.xml` intacto | COMPLIANT |
| 2. Pureza del módulo `kernel` | `kernel` solo usa el JDK | Esta ronda: `BannedDependencies passed` en `confia-kernel`, `BuildSmokeTest` en verde | COMPLIANT |
| 3. Dependencias prohibidas | Se agrega Hibernate | Probe R8 contra el `pom.xml` actual, intacto | COMPLIANT |
| 3. Dependencias prohibidas | Sin dependencias prohibidas | Esta ronda: reglas 0 a 2 `passed` en los tres módulos | COMPLIANT |
| 4. Paquetes con nombre de capa técnica | Paquete `services` | `NoTechnicalLayerPackageNamesTest > rejectsTheFixtureServicesPackage`, en verde | COMPLIANT |
| 4. Paquetes con nombre de capa técnica | Paquetes por capacidad de negocio | `NoTechnicalLayerPackageNamesTest > productionCodeHasNoTechnicalLayerPackageNameYet`, en verde | COMPLIANT |
| 5. Reglas de capas y ausencia de ciclos | `domain` importa `infrastructure` | Corrida `35188175663` sobre código de producción real; probes R1 y R3 sobre el fixture; `LayeredArchitectureTest` en verde | COMPLIANT |
| 5. Reglas de capas y ausencia de ciclos | Ciclo entre dos paquetes | `NoCyclesTest > rejectsTheFixtureTwoPackageCycle`, en verde | COMPLIANT |
| 6. Integración continua verifica cada empuje | Empuje con violación | Corrida `35188175663`: `backend verify` en rojo, visible en el remoto | COMPLIANT |
| 6. Integración continua verifica cada empuje | Empuje sin violaciones | Corrida `35292187021` sobre el commit verificado: ambos trabajos en verde | COMPLIANT |
| 7. Vulnerabilidad alta o crítica rompe la verificación | Vulnerabilidad crítica | Corrida `35188358113`: tres CVE, `exit code 1`, trabajo en rojo | COMPLIANT |
| 7. Vulnerabilidad alta o crítica rompe la verificación | Solo severidad baja | Corrida `35292187021`: escaneo con `severity: HIGH,CRITICAL` y `exit-code: 1`, sin romper (matiz en W9) | COMPLIANT |
| 8. Ninguna regla se desactiva sin un ADR | Exclusión sin justificación | `SuppressionCitesAdrTest` en verde; probes R4 a R7 | COMPLIANT |
| 8. Ninguna regla se desactiva sin un ADR | Excepción documentada por ADR | `allowEmptyShould(true)` citado a ADR-0018 más `EmptyShouldExceptionInventoryTest`, observado fallando con el mensaje correcto en `35188175663` | COMPLIANT |

**Compliance summary**: 15/15 escenarios COMPLIANT; 0 PARTIAL; 0 UNTESTED; 0 FAILING.
**Requisitos completos**: 8/8.

---

### Correctness (Static Evidence)

| Requirement | Status | Notes |
|------------|--------|-------|
| 1. Frontera de dominio entre módulos | Implementado | Regla doble (ArchUnit y Spring Modulith), módulo derivado por prefijo de paquete, sin nombres fijos |
| 2. Pureza del módulo `kernel` | Implementado | El enforcer excluye además los alcances `compile`, `runtime` y `provided` |
| 3. Dependencias prohibidas | Implementado | Probe R8 cubre la captura transitiva de `jakarta.persistence` |
| 4. Paquetes con nombre de capa técnica | Implementado | Los 9 nombres en la regla; un solo fixture (S1) |
| 5. Reglas de capas y ausencia de ciclos | Implementado | Las tres direcciones de ADR-0002, con rechazo observado sobre código de producción real |
| 6. Integración continua verifica cada empuje | Implementado | Rojo y verde observados en el remoto |
| 7. Vulnerabilidad alta o crítica rompe la verificación | Implementado | Ambas mitades observadas; punto ciego de alcance en W9 |
| 8. Ninguna regla se desactiva sin un ADR | Implementado | Dos mecanismos ejecutables; el inventario se observó caducando en integración continua |

**Comprobación de alcance**: nada implementado fuera del alcance aprobado. Ningún archivo de
implementación cambió en esta ronda.

---

### Coherence (Design)

| Decision | Followed? | Notes |
|----------|-----------|-------|
| 1. Reactor de tres POM | Sí | Sin cambios |
| 2. Enforcer en `validate` | Sí | Reglas `passed` en local y en integración continua |
| 2b. SNAPSHOT prohibido solo en main | Sí | Sin cambios (probe P5) |
| 3. Reglas auto-probadas con fixture permanente | Sí | Probes D y R1 |
| 4. Lanzador por `APP_PROFILE` | Sí | 10 pruebas en verde |
| 5. Integración continua parcial y honesta | Sí | Solo `backend` y `security` |
| 6. Escaneo de dependencias | Sí, con respuesta parcialmente negativa a la pregunta abierta 6 | Trivy `fs` cubre lo declarado, no lo resuelto. Ver W9 |

---

### Issues Found

**CRITICAL**: Ninguno.

**WARNING**:

- **W3 — El diff supera el presupuesto de revisión.** Cubierto por `size:exception` del 2026-09-15.
  Constancia, no bloquea.
- **W4 — La tarea 4.2 la cerró el orquestador, no `sdd-apply`, y la evidencia de los requisitos 6 y 7
  la produjeron empujes del propietario.** Solo trazabilidad; la ronda 4 la reverificó contra el remoto.
- **W9 — El escaneo de dependencias analiza lo declarado en los `pom.xml`, no el árbol resuelto.**
  Caso comprobado: el alcance `provided`. Sin probar: alcance `test` y dependencias transitivas.
  Exposición actual comprobada en cero para `provided`. Seguimiento del cambio 11; la nota del roadmap
  ya tiene el enunciado amplio desde `9cecb55`. No bloquea.
- **W10 (nueva) — La réplica en Engram de la especificación está desactualizada desde la ronda 1.** La
  observación `sdd/maven-workspace-and-ci-skeleton/spec` (#339, una sola revisión, 2026-09-15) conserva
  el texto original: 16 escenarios, el requisito 7 dentro de `./mvnw verify` y JDBC en la lista
  prohibida. No recoge la enmienda de la ronda 1 ni la de `ecefe48`. El archivo del repositorio es la
  fuente autoritativa y es el que se midió aquí; el riesgo es que `sdd-archive` o un cambio posterior
  lean la réplica y reintroduzcan el escenario diferido o el requisito 7 antiguo.

W2 deja de figurar como hallazgo de este cambio: el escenario salió de la especificación y queda
heredado por la nota a los cambios 4 y 7.

**SUGGESTION** (sin cambios salvo S11):

- **S1** — Solo 1 de los 9 nombres de paquete prohibidos tiene fixture.
- **S2** — Tres de los cuatro llamadores de `assertRuleRejects` no afirman sobre el mensaje.
- **S3** — La lista de programadores de tareas prohibidos es una enumeración cerrada.
- **S4** — Cachear el repositorio local de Maven en el trabajo `security`.
- **S5** — `SuppressionCitesAdrTest` se excluye a sí mismo por nombre de archivo, no por ruta.
- **S6** — El filtro que descarta el texto de conjunto vacío es amplio; falla del lado seguro.
- **S7** — El escáner de supresiones no cubre `.github/workflows/ci.yml`; ADR-0018 3.b lo acota a `apps/api`.
- **S8** — La tarea 6.1 y `apply-progress` dicen «cuatro» llamadores de `assertRuleRejects`; son tres.
- **S9** — Extender `<exclude>*:*:*:*:provided</exclude>` del `kernel` al POM padre haría inalcanzable
  el caso comprobado de W9 hasta el cambio 11.
- **S10** — El addendum de la ronda 3 atribuye a `35188358113` solo el rojo de `security scanning`;
  ambos trabajos fallaron.
- **S11 (nueva) — Precisión de la nota diferida.** Afirma que el cambio 7 es el «único que puede
  demostrarlo». Es cierto si `shared` no cuenta como módulo de negocio. La exploración de F0 decide
  tratar `shared` como módulo válido para el prefijo de tabla (cambio 5), y la regla deriva el módulo
  por el prefijo anterior al primer segmento `domain`. Si el cambio 5 crea un paquete `domain` bajo
  `com.confia.shared`, la regla ya vería dos módulos antes del cambio 7, aunque no dos módulos de
  negocio en sentido estricto. No invalida la nota; conviene que el cambio 5 lo tenga presente.

---

### Estado de los hallazgos

| Hallazgo | Ronda | Estado |
|---|---|---|
| C1, C2, C3 | 1 | Cerrados |
| C1-bis | 2 | Cerrado |
| C2-bis | 3 | Cerrado (ronda 4) |
| W1 | 1 | Cerrado (ronda 4) |
| W2 | 1 | **Fuera de este cambio por enmienda de la especificación**; heredado a los cambios 4 y 7 |
| W3, W4 | 1 | Abiertos, no bloquean |
| W5, W6, W7 | 2 | Cerrados (W7 con residuo S7) |
| W8 → W9 | 3 | Abierto, seguimiento del cambio 11 |
| W10 | 5 | Abierto, nuevo |
| S1 a S10 | 1 a 4 | Abiertas |
| S11 | 5 | Abierta, nueva |

---

### Verdict

**PASS WITH WARNINGS — 8/8 requisitos, 15/15 escenarios, sin bloqueantes ni hallazgos críticos.**

El resultado cambia respecto de la ronda 4 sin que cambie una sola línea de implementación, y eso es
lo esperado: la ronda 4 midió que el único escenario no cumplido era indemostrable dentro de este
alcance, y el propietario eligió trasladarlo a los cambios que sí pueden demostrarlo. Esta ronda no
presume el resultado de esa decisión: vuelve a contar la especificación enmendada (8 y 15), comprueba
que el diff de la enmienda toca solo ese bloque, que la nota es fiel al escenario original y que sus
destinos existen, y reejecuta la construcción real con JDK 25 (`BUILD SUCCESS`, 23 pruebas) más la
integración continua en el commit verificado (ambos trabajos en verde). Los avisos abiertos son de
trazabilidad (W3, W4), un seguimiento con dueño (W9) y la réplica desactualizada en Engram (W10).

### Qué debe llevarse `sdd-archive`

1. **Incorporar `build-integrity` a `openspec/specs/` desde el archivo del repositorio**, no desde la
   réplica de Engram (W10), con estado 15/15 COMPLIANT, **conservando la nota «Escenario diferido»**:
   es hoy el único registro normativo del traslado de W2. Conviene actualizar la réplica #339 al texto
   enmendado en el mismo paso.
2. **W2 heredado a los cambios 4 y 7 mediante la nota de la especificación.** Cuando se propongan, el
   cambio 7 (`staff-authentication-mfa-sessions`) debe incluir el escenario «Cada módulo usa solo su
   propio dominio» con su premisa de dos módulos de negocio; el cambio 4 debe saber que introduce el
   primero. Ni el roadmap ni la exploración de F0 lo mencionan todavía; valorar añadir una referencia
   en `openspec/changes/foundations-plan/exploration.md` para que no dependa solo de la nota.
3. **W9 heredado al cambio 11** (`containerization-and-cicd-pipeline`), ya anotado en
   `docs/09-roadmap-y-fases.md`, entregable 8, con el enunciado amplio de `9cecb55`: escanear un SBOM
   resuelto, no los `pom.xml` declarados.
4. **Aviso al cambio 4 del rojo programado de ADR-0018**, con la redacción literal observada en
   `35188175663`: `EmptyShouldExceptionInventoryTest.everyExceptionsConditionStillHolds` fallará en
   cuanto exista una clase de producción en un paquete de capa, y exige retirar `allowEmptyShould(true)`
   de `LayeredArchitectureTest.productionCodeRespectsLayeringYet` y su entrada del inventario. Su
   `tasks.md` debe preverlo.
5. **Conservar S1 a S11** como deuda técnica registrada; S11 es relevante para el cambio 5.
6. La excepción `size:exception` del 2026-09-15 (W3) se agota con este cambio y no se hereda.

```yaml
schema: gentle-ai.verify-result/v1
evidence_revision: sha256:812185c2e47cb1bd3caf18cf6b0ca6ef38a4d94279cf12db830b8e393096b9c3
verdict: fail
blockers: 0
critical_findings: 0
requirements: 7/8
scenarios: 15/16
test_command: ./mvnw -B verify
test_exit_code: 0
test_output_hash: sha256:b75d8400196e3f8f249c3cba08b2e847544ae158cba3fe20f0d761897ed21a0c
build_command: ./mvnw -B verify
build_exit_code: 0
build_output_hash: sha256:b75d8400196e3f8f249c3cba08b2e847544ae158cba3fe20f0d761897ed21a0c
```

## Verification Report — Ronda 4 (final)

**Change**: maven-workspace-and-ci-skeleton (F0, cambio 1 de 13)
**Version**: `specs/build-integrity/spec.md` — 8 requisitos, 16 escenarios
**Mode**: Standard (`strict_tdd: false`)
**Commit verificado**: `10ed68ba81d0cbaace42cfab3981631e93b5b8ff`, rama `change/maven-workspace-and-ci-skeleton`
**Comando real**: `JAVA_HOME=<jdk-25.0.3.9-hotspot> ./mvnw -B verify`, ejecutado en `apps/api`

Criterio de conteo, idéntico al de las rondas 1 a 3: un escenario cuenta como completado solo si es
COMPLIANT; un requisito cuenta como completado solo si todos sus escenarios lo son.

---

### Rondas 1 a 3: qué se encontró y cómo quedó cerrado

| Hallazgo | Origen | Estado | Evidencia decisiva |
|---|---|---|---|
| **C1** — requisito 5 implementado a medias, con un Javadoc que afirmaba una garantía inexistente | Ronda 1 | **Cerrado** en ronda 2 | La Fase 5 sustituyó la regla parcial por una `Architectures.layeredArchitecture()` completa con las tres direcciones de ADR-0002 |
| **C2** — requisito 7 implementado en una capa distinta de la exigida | Ronda 1 | **Cerrado** en ronda 1 | La especificación se enmendó: el requisito 7 sitúa el escaneo en integración continua, que es donde está. Residuo escalado a C2-bis |
| **C3** — requisito 8 incumplido, desactivación global sin ADR | Ronda 1 | **Cerrado** en ronda 3 | ADR-0018 más dos mecanismos ejecutables; probes R4 a R7 |
| **C1-bis** — la regla de capas contaba dependencias del JDK como violaciones, lo que anulaba su mitad negativa | Ronda 2 (bloqueante) | **Cerrado** en ronda 3 | Probes R1, R2 y R3: `consideringOnlyDependenciesInLayers()` |
| **W6** — la versión declarada de ArchUnit no era la que se ejecutaba | Ronda 2 | **Cerrado** en ronda 3 | Probe R9: `version managed from 1.4.2` |
| **W7** — el escáner de supresiones no cubría los `pom.xml` | Ronda 2 | **Cerrado** en ronda 3 | Probe R4: una supresión sin cita en `apps/api/pom.xml` rompe la construcción |
| **C2-bis** — el escenario «Vulnerabilidad crítica» nunca se había ejercitado | Ronda 3 (bloqueante) | **Cerrado en esta ronda** | Corrida `35188358113`, verificada de primera mano abajo |
| **W1** — el escenario «Empuje con violación» nunca se había ejercitado | Ronda 1 | **Cerrado en esta ronda** | Corrida `35188175663`, verificada de primera mano abajo |

---

### Completeness

| Metric | Value |
|--------|-------|
| Tasks total | 24 (15 originales + 6 de la Fase 5 + 3 de la Fase 6) |
| Tasks complete | 24 |
| Tasks incomplete | 0 |

Contadas con `grep -cE "^- \[x\]" tasks.md` → 24 y `grep -cE "^- \[ \]" tasks.md` → 0. Ninguna tarea
nueva se abrió desde la ronda 3: `tasks.md` no aparece en el diff de esta ronda.

---

### Build & Tests Execution

**Build**: Passed — `BUILD SUCCESS`, exit 0, 22.6 s, sobre el árbol real en `10ed68b`.

```text
[INFO] --- enforcer:3.6.3:enforce (enforce-build-integrity) @ confia-api-parent ---
[INFO] Rule 0: org.apache.maven.enforcer.rules.version.RequireJavaVersion passed
[INFO] Rule 1: org.apache.maven.enforcer.rules.dependency.DependencyConvergence passed
[INFO] Rule 2: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed
[INFO] --- enforcer:3.6.3:enforce (enforce-kernel-purity) @ confia-kernel ---
[INFO] Rule 0: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed
[INFO] Reactor Summary for CONFIA API Parent 0.1.0-SNAPSHOT:
[INFO] CONFIA API Parent .................................. SUCCESS [  1.731 s]
[INFO] CONFIA Kernel ...................................... SUCCESS [  3.600 s]
[INFO] CONFIA API ......................................... SUCCESS [ 16.262 s]
[INFO] BUILD SUCCESS
```

`DependencyConvergence` y `BannedDependencies` siguen en verde en los tres módulos.

**Tests**: 23 passed / 0 failed / 0 skipped (1 en `confia-kernel`, 22 en `confia-api`).

```text
[INFO] Tests run: 1,  ... -- in com.confia.kernel.BuildSmokeTest
[INFO] Tests run: 1,  ... -- in com.confia.architecture.EmptyShouldExceptionInventoryTest
[INFO] Tests run: 2,  ... -- in com.confia.architecture.LayeredArchitectureTest
[INFO] Tests run: 2,  ... -- in com.confia.architecture.NoCrossModuleDomainImportsTest
[INFO] Tests run: 2,  ... -- in com.confia.architecture.NoCyclesTest
[INFO] Tests run: 2,  ... -- in com.confia.architecture.NoTechnicalLayerPackageNamesTest
[INFO] Tests run: 2,  ... -- in com.confia.architecture.SpringModulithVerificationTest
[INFO] Tests run: 1,  ... -- in com.confia.architecture.SuppressionCitesAdrTest
[INFO] Tests run: 10, ... -- in com.confia.bootstrap.ConfiaApplicationTest
```

**Coverage**: no disponible — JaCoCo y los umbrales llegan con el cambio 2 (fuera de alcance).

**Integración continua en el commit verificado**: corrida `35188705445` sobre `10ed68b`, ambos
trabajos en verde. El árbol local y el remoto coinciden en `10ed68b`.

---

### Evidencia negativa observada en el remoto, reverificada por esta ronda

El addendum de la ronda 3 fue redactado por el orquestador, no por la fase de verificación. Esta
ronda **no lo da por bueno**: vuelve a consultar las cuatro corridas con `gh run view --json` y
extrae las líneas decisivas de sus registros. Las cuatro afirmaciones se confirman.

| Corrida | Commit | `backend verify` | `security scanning` | Coincide con el addendum |
|---|---|---|---|---|
| `35188175663` | `bc013e4` | **failure** | **success** | Sí |
| `35188358113` | `0ca32d4` | failure | **failure** | Sí, con una precisión (ver S10) |
| `35188476297` | `aaec6a2` | success | success | Sí |
| `35188705445` | `10ed68b` | success | success | Corrida nueva, en el commit verificado |

**Requisito 6, escenario «empuje con violación»: demostrado.** Registro de `35188175663`:

```text
Architecture Violation [Priority: MEDIUM] - Rule 'Layered architecture considering only
dependencies in layers, consisting of ... (ADR-0002)' was violated (2 times):
Method <com.confia.probe.domain.ProbeDomain.describe()> calls method
<com.confia.probe.infrastructure.ProbeInfrastructure.value()> in (ProbeDomain.java:19)
[ERROR]   LayeredArchitectureTest.productionCodeRespectsLayeringYet:75
[ERROR] Tests run: 22, Failures: 2, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

Es la mitad de **producción** de la regla la que rompe, no la de fixture: la violación estaba en
`src/main/java`. Esto cierra W1 y, además, refuerza el requisito 5 con evidencia de código real.

**Hallazgo adicional que el addendum no registró y que juega a favor del cambio.** Esa misma
corrida muestra `Failures: 2`, no 1. La segunda es el «rojo programado» de ADR-0018 sección 2,
disparándose por primera vez en integración continua contra código de producción real:

```text
[ERROR] EmptyShouldExceptionInventoryTest.everyExceptionsConditionStillHolds:43
[LayeredArchitectureTest.productionCodeRespectsLayeringYet's ADR-0018 exception no longer holds
(no class in apps/api/app production code resides in a domain, application, infrastructure or web
package yet, because no business module exists (change 4 introduces the first one)). Remove
allowEmptyShould(true) from that rule and this inventory entry, or update the condition and cite
the ADR that extends it (ADR-0018, section 2).]
```

El inventario de caducidad dejó de ser una promesa verificada solo en copias aisladas: se comprobó
que caduca exactamente cuando aparece una clase de producción en un paquete de capa, que es el
evento que ADR-0018 fija, y que nombra la regla y la línea a retirar. Esto anticipa y valida el
comportamiento que el cambio 4 debe esperar.

**Requisito 7, escenario «vulnerabilidad crítica»: demostrado.** Registro de `35188358113`:

```text
Total: 3 (HIGH: 1, CRITICAL: 2)
│ org.apache.logging.log4j:log4j-core │ CVE-2021-44228 │ CRITICAL │ fixed │ 2.14.1 │ 2.15.0, ...
│                                     │ CVE-2021-45046 │          │       │        │ 2.16.0, ...
│                                     │ CVE-2021-45105 │ HIGH     │       │        │ 2.12.3, ...
##[error]Process completed with exit code 1.
```

El escaneo no solo reportó: **rompió** el trabajo con código de salida 1. Esto cierra C2-bis.

**El contraste que sustenta W8.** En `35188175663` la misma dependencia, declarada con
`<scope>provided</scope>` (confirmado en `git show bc013e4 -- apps/api/app/pom.xml`), produjo:

```text
│     Target     │ Type │ Vulnerabilities │
│ app/pom.xml    │ pom  │        0        │
│ kernel/pom.xml │ pom  │        0        │
│ pom.xml        │ pom  │        0        │
- '0': Clean (no security findings detected)
```

Cero hallazgos, trabajo en verde. La única diferencia entre ambas corridas es el alcance: el
`git diff` entre `bc013e4` y `0ca32d4` es la eliminación de una sola línea, `<scope>provided</scope>`.
El registro del trabajo `backend` de `bc013e4` confirma además que la dependencia era real y se
resolvió (`Downloaded ... log4j-core-2.14.1.jar (1.7 MB)`): no fue un falso negativo por una
declaración inerte.

---

### El árbol verificado está limpio de residuo de las probes

Comprobado de primera mano, no heredado:

- `git status --porcelain` vacío antes y después de toda la verificación.
- `git ls-files | grep -i probe` no devuelve nada; no existe ningún directorio `probe*` bajo
  `apps/api`; las dos únicas menciones de `com.confia.probe` en el repositorio están dentro del
  propio `verify-report.md`, como cita documental.
- `git grep -in "log4j" -- '*.xml' '*.java' '*.yml'` no devuelve ninguna línea. Siguen existiendo
  exactamente tres `pom.xml`.
- `git diff 3d77388 aaec6a2 --stat` toca **un solo archivo**, `verify-report.md`: la reversión de
  las dos probes fue completa, byte a byte, sobre `apps/api`.

### Reejecución frente a herencia: justificación explícita

`git diff --stat 3d77388..10ed68b` devuelve exactamente dos archivos:

```text
 docs/09-roadmap-y-fases.md                         |   6 +
 .../verify-report.md                               | 489 ++++++++++++---------
 2 files changed, 281 insertions(+), 214 deletions(-)
```

Y la consulta acotada `git diff 3d77388 10ed68b -- apps/api .github` devuelve **vacío**. Es decir:
desde el commit que la ronda 3 verificó, no se ha tocado ni una línea de código de producción, de
código de prueba, de `pom.xml` ni del flujo de integración continua. Los dos únicos cambios son
documentales.

Sobre esa base se heredan, con justificación, las probes de la ronda 3 que no se repiten aquí:

| Cierre heredado | Probe original | Archivos que lo sustentan | Por qué la herencia es válida |
|---|---|---|---|
| **C1-bis** (la mitad negativa de la regla de capas discrimina) | R1, R2, R3 | `LayeredArchitectureTest.java`, `ArchitectureTestSupport.java` | Intactos en el diff acotado. Además, esta ronda obtuvo evidencia **superior** e independiente: la corrida `35188175663` muestra la misma regla rechazando una violación de código de producción real |
| **W6** (versión declarada igual a la resuelta) | R9 | `apps/api/pom.xml` | Intacto. `DependencyConvergence passed` se reconfirma en la ejecución local de esta ronda |
| **W7** (una supresión sin cita en un `pom.xml` se detecta) | R4 | `SuppressionCitesAdrTest.java`, `apps/api/pom.xml` | Ambos intactos. `SuppressionCitesAdrTest` pasa en la ejecución de esta ronda |
| **ADR-0018** (inventario y escáner siguen mordiendo) | R4 a R7 | `EmptyShouldExceptionInventoryTest.java`, `SuppressionCitesAdrTest.java`, `archunit.properties` | Intactos. El inventario, además, se observó **fallando de verdad en integración continua** en `35188175663`, que es evidencia más fuerte que la probe en copia aislada |

Dos de los cuatro cierres heredados dejaron de depender de la herencia durante esta misma ronda,
porque las probes del propietario los ejercitaron en el remoto sin proponérselo.

---

### Spec Compliance Matrix

| Requirement | Scenario | Test | Result |
|-------------|----------|------|--------|
| 1. Frontera de dominio entre módulos | Importación cruzada de dominio | `NoCrossModuleDomainImportsTest > rejectsTheFixtureCrossModuleDomainImport` y `SpringModulithVerificationTest > rejectsTheFixtureModuleCrossingInternalAccess`, 2/2 en verde en esta ronda; no vacuas por la probe D (ronda 2), archivos intactos | COMPLIANT |
| 1. Frontera de dominio entre módulos | Cada módulo usa solo su propio dominio | `NoCrossModuleDomainImportsTest > productionCodeHasNoCrossModuleDomainImportYet` | **PARTIAL** |
| 2. Pureza del módulo `kernel` | `kernel` importa Spring | `enforce-kernel-purity` en fase `validate`; probe P2 (ronda 1), `kernel/pom.xml` intacto | COMPLIANT |
| 2. Pureza del módulo `kernel` | `kernel` solo usa el JDK | `./mvnw -B verify` de esta ronda: `Rule 0: BannedDependencies passed` en `confia-kernel`, `BuildSmokeTest` en verde | COMPLIANT |
| 3. Dependencias prohibidas | Se agrega Hibernate | Probe R8 (ronda 3) contra el `pom.xml` actual, intacto desde entonces | COMPLIANT |
| 3. Dependencias prohibidas | Sin dependencias prohibidas | `./mvnw -B verify` de esta ronda: reglas 0-2 `passed` en los tres módulos | COMPLIANT |
| 4. Paquetes con nombre de capa técnica | Paquete `services` | `NoTechnicalLayerPackageNamesTest > rejectsTheFixtureServicesPackage`, 2/2 en verde; no vacua por la probe D, archivo intacto | COMPLIANT |
| 4. Paquetes con nombre de capa técnica | Paquetes por capacidad de negocio | `NoTechnicalLayerPackageNamesTest > productionCodeHasNoTechnicalLayerPackageNameYet` | COMPLIANT |
| 5. Reglas de capas y ausencia de ciclos | `domain` importa `infrastructure` | **Corrida `35188175663`**: la regla rechazó una violación `domain` → `infrastructure` en código de producción real, nombrando clase, método y línea. Más las probes R1 y R3 sobre el fixture | COMPLIANT |
| 5. Reglas de capas y ausencia de ciclos | Ciclo entre dos paquetes | `NoCyclesTest > rejectsTheFixtureTwoPackageCycle`, 2/2 en verde; no vacua por la probe D, archivo intacto | COMPLIANT |
| 6. Integración continua verifica cada empuje | Empuje con violación | **Corrida `35188175663`**: `backend verify` en rojo, visible en el remoto | COMPLIANT |
| 6. Integración continua verifica cada empuje | Empuje sin violaciones | Corridas `35188476297` y `35188705445` (esta última sobre el commit verificado): ambos trabajos en verde | COMPLIANT |
| 7. Vulnerabilidad alta o crítica rompe la verificación | Vulnerabilidad crítica | **Corrida `35188358113`**: `Total: 3 (HIGH: 1, CRITICAL: 2)`, CVE-2021-44228/45046/45105, `exit code 1`, trabajo en rojo | COMPLIANT |
| 7. Vulnerabilidad alta o crítica rompe la verificación | Solo severidad baja | Corridas verdes con el escaneo ejecutado bajo `severity: HIGH,CRITICAL` y `exit-code: 1`, sin romper (ver matiz en W9) | COMPLIANT |
| 8. Ninguna regla se desactiva sin un ADR | Exclusión sin justificación | `SuppressionCitesAdrTest` en verde en esta ronda; probes R4 a R7 (ronda 3), archivos intactos | COMPLIANT |
| 8. Ninguna regla se desactiva sin un ADR | Excepción documentada por ADR | `allowEmptyShould(true)` citado a ADR-0018 más `EmptyShouldExceptionInventoryTest`, **observado fallando en integración continua** en `35188175663` con el mensaje de caducidad correcto | COMPLIANT |

**Compliance summary**: 15/16 escenarios COMPLIANT; 1 PARTIAL; 0 UNTESTED; 0 FAILING.
**Requisitos completos**: 7/8 (todos menos el 1).

#### El único escenario que no cierra, y por qué no es un defecto de este cambio

«Cada módulo usa solo su propio dominio» (requisito 1) es **estructuralmente indemostrable dentro de
este cambio**, y conviene decirlo sin rodeos en vez de forzar un aprobado. Su propia premisa lo
impide: el escenario empieza con «**DADO** los mismos dos módulos», y hoy no existe ningún módulo de
negocio, mucho menos dos. La prueba que lo cubre,
`productionCodeHasNoCrossModuleDomainImportYet`, pasa, pero pasa sobre un conjunto de producción
vacío: confirma que no hay importaciones cruzadas porque no hay nada que pueda importar. Eso es
PARTIAL, no COMPLIANT, y tampoco es UNTESTED: la regla existe, está escrita y su mitad negativa
demuestra contra el fixture que sabe rechazar.

Se clasifica como advertencia y no como bloqueante porque ninguna acción dentro del alcance aprobado
de este cambio puede cerrarlo. Cerrarlo exige crear dos módulos de negocio, que es exactamente lo
que los cambios 4 y 7 hacen y lo que el alcance de este cambio excluye de forma expresa. La propia
propuesta ya lo anticipó: «no lo cierra: ese criterio solo se puede comprobar por completo cuando
existan al menos dos módulos de negocio (cambios 4 y 7)». El techo alcanzable aquí es 7/8, y está
alcanzado.

---

### Correctness (Static Evidence)

| Requirement | Status | Notes |
|------------|--------|-------|
| 1. Frontera de dominio entre módulos | Implementado | Regla doble (ArchUnit y Spring Modulith), módulo derivado por prefijo de paquete, sin nombres fijos. Falta el escenario que requiere dos módulos |
| 2. Pureza del módulo `kernel` | Implementado | `kernel/pom.xml` intacto; el enforcer excluye además los alcances `compile`, `runtime` y `provided` |
| 3. Dependencias prohibidas | Implementado | Bloque del enforcer intacto; probe R8 cubre la captura transitiva de `jakarta.persistence` |
| 4. Paquetes con nombre de capa técnica | Implementado | Los 9 nombres en la regla; sigue habiendo un solo fixture (S1) |
| 5. Reglas de capas y ausencia de ciclos | Implementado | Las tres direcciones de ADR-0002 con el ámbito correcto. Ahora con evidencia de rechazo sobre código de producción real, no solo sobre el fixture |
| 6. Integración continua verifica cada empuje | Implementado | Disparador y matriz sin cambios; rojo y verde observados en el remoto |
| 7. Vulnerabilidad alta o crítica rompe la verificación | Implementado | Ambas mitades del requisito observadas. Punto ciego de alcance documentado en W9 |
| 8. Ninguna regla se desactiva sin un ADR | Implementado | Dos mecanismos ejecutables; el inventario se observó caducando en integración continua |

**Comprobación de alcance**: no se encontró nada implementado fuera del alcance aprobado. En esta
ronda no cambió ningún archivo de implementación. El acumulado de la rama corresponde a la tabla
«Cambios de archivos» de `design.md`, más ADR-0018 y sus referencias, más la nota de W8 en
`docs/09-roadmap-y-fases.md`.

---

### Coherence (Design)

| Decision | Followed? | Notes |
|----------|-----------|-------|
| 1. Reactor de tres POM | Sí | Sin cambios |
| 2. Enforcer en `validate` | Sí | Reglas `passed` en local y en integración continua; probe R8 confirma que rompe cuando debe |
| 2b. SNAPSHOT prohibido solo en main | Sí | Sin cambios (probe P5, ronda 1). `ci.yml` pasa `-Dconfia.ci.mainBranch=true` solo en `refs/heads/main` |
| 3. Reglas auto-probadas con fixture permanente | Sí | Las cinco mitades negativas fallan si su fixture desaparece (probe D) y la de capas falla además si el fixture deja de violar conservando sus clases (probe R1) |
| 4. Lanzador por `APP_PROFILE` | Sí | 10 pruebas en verde |
| 5. Integración continua parcial y honesta | Sí | Solo `backend` y `security`; sin `frontend`, `e2e`, `quality-gate` ni `-Pmutation-gate` |
| 6. Escaneo de dependencias | **Sí, con una respuesta ahora completa** | Trivy en modo `fs` sobre `apps/api`. La pregunta «por confirmar» 6 del diseño —si el modo `fs` cubre las dependencias resueltas o hace falta el SBOM de `docs/03` §13— se dio por resuelta en la ronda 2, pero la evidencia de esta ronda la responde de verdad y en parte por la negativa: cubre lo declarado en los `pom.xml`, no lo resuelto, y por eso ignora el alcance `provided`. Ver W9 |

---

### Issues Found

**CRITICAL**: Ninguno. Los dos bloqueantes vivos al cierre de la ronda 3 —C2-bis y, de facto, W1—
quedan cerrados con evidencia observada en el remoto y reverificada aquí de primera mano.

**WARNING**:

- **W2 — El escenario «Cada módulo usa solo su propio dominio» (Req. 1) solo se cumple de forma
  aproximada.** Sin cambios. Su premisa son dos módulos de negocio y todavía no existen. Es una
  limitación de secuencia del roadmap, no un defecto de este cambio; se cierra en los cambios 4 y 7.
  Es el único escenario que impide 8/8 y es inalcanzable dentro de este alcance.
- **W3 — El diff sigue por encima del presupuesto de revisión.** El acumulado de la rama supera con
  holgura las 800 líneas concedidas. Cubierto por la excepción `size:exception` del 2026-09-15. Se
  deja constancia, no bloquea.
- **W4 — La tarea 4.2 la cerró el orquestador, no `sdd-apply`.** Sin cambios; solo trazabilidad. A
  esto se suma ahora que la evidencia decisiva de los requisitos 6 y 7 la produjeron dos empujes del
  propietario, no la fase de verificación. Esta ronda la reverificó de forma independiente contra el
  remoto, que es lo que la vuelve utilizable.
- **W9 (antes W8) — El escaneo de dependencias ignora el alcance `provided`, y su extensión real es
  mayor de lo registrado.** Se acepta como seguimiento del cambio 11 y **no bloquea este cambio**;
  la justificación y su condición están abajo, en «Juicio sobre W9».

**SUGGESTION**:

- **S1 — Solo 1 de los 9 nombres de paquete prohibidos tiene fixture.** Sin cambios.
- **S2 — Cerrada donde importaba, abierta para las demás reglas.** La mitad negativa de la regla de
  capas afirma sobre el contenido del mensaje; los otros tres llamadores de `assertRuleRejects`
  siguen pasando cero fragmentos.
- **S3 — La lista de programadores de tareas prohibidos sigue siendo una enumeración cerrada.**
- **S4 — Cachear el repositorio local de Maven en el trabajo `security`.** Prioridad baja.
- **S5 — `SuppressionCitesAdrTest` se excluye a sí mismo por nombre de archivo, no por ruta.**
- **S6 — El filtro que descarta el texto de conjunto vacío es amplio.** Falla del lado seguro.
- **S7 — Residuo de W7: el escáner no cubre `.github/workflows/ci.yml`.** ADR-0018 sección 3.b acota
  el escáner al árbol de `apps/api`, así que no es incumplimiento.
- **S8 — Imprecisión documental en la tarea 6.1 y en `apply-progress`:** dicen «los otros cuatro
  llamadores» de `assertRuleRejects`; son tres. Sin efecto sobre el comportamiento.
- **S9 (nueva) — Un guardia barato dejaría W9 seguro por construcción hasta el cambio 11.** El
  `kernel` ya prohíbe el alcance `provided` con `<exclude>*:*:*:*:provided</exclude>`. Extender esa
  misma exclusión al POM padre convertiría el punto ciego en inalcanzable mientras el escaneo no lo
  cubra, y costaría una línea. Es una sugerencia, no una condición del veredicto.
- **S10 (nueva) — Imprecisión en el addendum de la ronda 3.** Su tabla atribuye a la corrida
  `35188358113` solo `security scanning` en rojo; en realidad **ambos** trabajos fallaron, porque
  esa corrida seguía arrastrando la violación de capas de `bc013e4`. No invalida nada —la evidencia
  del requisito 7 es el registro de Trivy, que es inequívoco— pero la tabla, leída sola, sugiere un
  aislamiento entre las dos probes que no existió.

---

### Juicio sobre W9: la decisión del propietario es defendible, con una condición

Se pidió juzgarlo sin complacencia y decirlo con franqueza si hubiera desacuerdo. **No lo hay: no
debe bloquear este cambio.** Cuatro razones, todas comprobadas, no supuestas:

1. **El requisito 7 no habla de alcances.** Exige escanear las dependencias en cada verificación de
   integración continua y romperla ante severidad alta o crítica. Sus dos escenarios están
   demostrados con evidencia observada. Convertir «el escaneo tiene un punto ciego de alcance» en un
   incumplimiento del requisito 7 sería reescribir el requisito después del hecho.
2. **Hoy la exposición es cero, comprobado.** `grep -n "<scope>provided</scope>"` sobre los tres
   `pom.xml` no devuelve ninguna línea; tampoco hay alcance `runtime` ni `system`. Las únicas
   apariciones de la palabra están en comentarios de `kernel/pom.xml` y en la exclusión del
   enforcer `<exclude>*:*:*:*:provided</exclude>`, que **prohíbe** ese alcance en `kernel`. No hay
   ninguna dependencia vulnerable escondida tras el punto ciego: hay un punto ciego sin nada detrás.
3. **No es una sorpresa, es una pregunta que el propio cambio dejó abierta.** `design.md`, «Por
   confirmar durante la implementación», punto 6, pregunta literalmente si Trivy en modo `fs` cubre
   las dependencias resueltas o si hace falta la ruta de SBOM CycloneDX de `docs/03` §13. La ronda 2
   la dio por resuelta con demasiada confianza; la evidencia de ahora la responde bien. Que la
   respuesta correcta llegue con una probe del propietario es un acierto del proceso, no un defecto
   del entregable.
4. **El destino es el correcto y ya está escrito.** `docs/03-seguridad.md` §13 contempla la ruta de
   SBOM, y el cambio 11 es el que arma la canalización completa. El commit `10ed68b` lo deja anotado
   en `docs/09-roadmap-y-fases.md` como pendiente heredado del entregable 8 de F0, con la evidencia
   reproducible dentro. Un seguimiento escrito, con evidencia y con dueño, es el mecanismo correcto.

**La condición, y es donde discrepo del registro tal como quedó.** La nota del roadmap describe el
hallazgo como «ignora las dependencias de alcance `provided`». Eso es lo que la probe demostró, pero
**no es necesariamente todo el hallazgo**, y conviene no darlo por acotado antes de tiempo. La causa
observada no es el alcance `provided` en particular: es que el escaneo analiza los `pom.xml` **tal
como están declarados** en lugar del árbol de dependencias **resuelto**. De ahí se siguen dos cosas
que nadie ha medido:

- Si el alcance `test` recibe el mismo trato, el punto ciego cubriría hoy **casi todas** las
  dependencias del proyecto: las nueve declaraciones con `<scope>` de los tres POM son `test` o
  `import`. Las corridas verdes no distinguen «sin vulnerabilidades» de «no inspeccionado».
- Las dependencias **transitivas**, que no aparecen en ningún `pom.xml`, son por definición lo que un
  análisis de lo declarado no ve, y son la mayor parte de la superficie real.

Nada de esto cambia el veredicto: siguen siendo huecos de un escaneo cuya existencia y capacidad de
bloqueo el requisito 7 sí exige y sí están demostradas, y el cambio 11 es su dueño. Pero el
seguimiento debería heredarse con el enunciado correcto —«el escaneo analiza lo declarado, no lo
resuelto»— y no con el enunciado estrecho, porque un seguimiento que solo recuerda el alcance
`provided` puede cerrarse con un parche que deje intacto lo importante. Es una corrección de
redacción del seguimiento, no una objeción al archivado.

---

### Estado de los hallazgos previos

| Hallazgo | Ronda | Estado | Comentario |
|---|---|---|---|
| C1 | 1 | Cerrado | Regla de capas completa; Javadoc falso eliminado |
| C2 | 1 | Cerrado | Especificación enmendada; residuo cerrado como C2-bis |
| C3 | 1 | Cerrado | ADR-0018 y sus dos mecanismos, reverificados |
| C1-bis | 2 | Cerrado | Probes R1 a R3; reforzado por la corrida `35188175663` |
| **C2-bis** | 3 | **Cerrado en esta ronda** | Corrida `35188358113`: el escaneo bloqueó con `exit code 1` |
| **W1** | 1 | **Cerrado en esta ronda** | Corrida `35188175663`: `backend verify` en rojo por una violación de arquitectura |
| W2 | 1 | **Abierto — es el único pendiente** | Inalcanzable en este cambio; se cierra en los cambios 4 y 7 |
| W3 | 1 | Abierto | Cubierto por `size:exception` |
| W4 | 1 | Abierto | Solo trazabilidad |
| W5 | 2 | Cerrado vía C2-bis | — |
| W6 | 2 | Cerrado | Heredado con justificación de diff |
| W7 | 2 | Cerrado, con residuo menor (S7) | Heredado con justificación de diff |
| W8 → **W9** | 3 (addendum) | Abierto, aceptado como seguimiento | No bloquea. Ver «Juicio sobre W9» |
| S1 a S8 | 1 a 3 | Abiertas | Sin cambios; S9 y S10 nuevas en esta ronda |

---

### Verdict

**FAIL — sin bloqueantes, sin hallazgos críticos, 15/16 escenarios cumplidos.**

Conviene ser exacto con lo que esta palabra significa aquí, porque no significa que algo esté roto.
El contrato de admisión de `sdd-verify` reserva un veredicto de aprobación para los informes que
declaran **todos** los requisitos y **todos** los escenarios cumplidos; cualquier cuenta inferior es
`fail`, con independencia de que existan bloqueantes. Este informe declara 7/8 requisitos y 15/16
escenarios, con `blockers: 0` y `critical_findings: 0`. Esa combinación —fallo sin bloqueantes— es
la descripción fiel del estado real y es la que el validador admite.

Se deja constancia de una decisión de método, porque afecta a la confianza en este informe: declarar
16/16 habría bastado para obtener un veredicto de aprobación, y habría sido falso. El escenario
«Cada módulo usa solo su propio dominio» no está demostrado, su prueba pasa sobre un conjunto de
producción vacío, y la cuenta es la medición, no la etiqueta. Un informe que ajusta la medición para
conseguir la etiqueta es exactamente la erosión que la capacidad `build-integrity` existe para
impedir, y sería particularmente indefendible en el informe que certifica esa capacidad.

**Lo demás salió bien, y conviene no perderlo detrás de la palabra «fail».** Los dos escenarios que
la ronda 3 declaró indemostrados están ahora demostrados de la única forma que esta capacidad
acepta: observando el rojo. Una violación de capas en código de producción puso `backend verify` en
rojo nombrando clase, método y línea; una dependencia con vulnerabilidad pública crítica puso
`security scanning` en rojo con `exit code 1` y tres CVE. Esta ronda no tomó ninguna de las cuatro
afirmaciones del addendum por buena: volvió a consultar las corridas y extrajo las líneas decisivas
de sus registros. Las cuatro se confirman. El árbol está limpio, la reversión de las probes fue
completa, y `./mvnw -B verify` termina en `BUILD SUCCESS` con 23 pruebas y el enforcer en verde.

Hay además un rendimiento inesperado que afecta al cambio 4: la probe de la violación produjo **dos**
fallos, y el segundo fue el inventario de caducidad de ADR-0018 disparándose en integración continua
contra código de producción real, con el mensaje que nombra la regla y la línea a retirar. El «rojo
programado» de ADR-0018 sección 2 dejó de ser una promesa y pasó a ser un comportamiento observado.

### Lo que queda, y si es cerrable dentro de este cambio

**Queda exactamente un elemento, y no es cerrable aquí.**

| Pendiente | Cerrable en este cambio | Por qué |
|---|---|---|
| Req. 1, escenario «Cada módulo usa solo su propio dominio» (W2) | **No** | Su premisa literal es «DADO los mismos dos módulos» de negocio. Hoy no existe ninguno. Crearlos está excluido de forma expresa del alcance aprobado y es el contenido de los cambios 4 y 7 |

Ningún otro hallazgo abierto bloquea: W3 está cubierto por `size:exception`, W4 es trazabilidad, W9
es un seguimiento aceptado para el cambio 11, y S1 a S10 son sugerencias. No hay ninguna acción de
remediación que una Fase 7 pudiera ejecutar sobre este cambio para mejorar la cuenta. Una quinta
ronda de verificación sobre el mismo alcance daría exactamente este mismo resultado.

### Decisión que corresponde al propietario, no a esta fase

El techo real de este cambio es 7/8 y está alcanzado. Quedan dos salidas legítimas, y ninguna la
puede tomar el verificador:

1. **Enmendar la especificación** para trasladar ese escenario a los cambios 4 y 7, como ya se hizo
   en la ronda 1 con la capa del requisito 7. Es la salida coherente con el precedente del propio
   cambio: el escenario no es incorrecto, está sencillamente fuera del alcance de quien lo hereda.
   Tras la enmienda, este cambio quedaría en 16/16 de forma legítima y el veredicto sería de
   aprobación sin tocar una sola línea de código.
2. **Archivar con el `fail` documentado**, aceptando de forma expresa que el escenario queda
   pendiente y atado al roadmap. El informe deja el registro completo para que esa aceptación sea
   informada.

La opción 1 es la que deja la especificación diciendo la verdad sobre lo que cada cambio demuestra.

### Qué debe llevarse `sdd-archive`

Aplicable en cualquiera de las dos salidas:

1. **Incorporar `build-integrity` a `openspec/specs/`** con el estado real de cumplimiento de este
   informe: 15/16 COMPLIANT, 1 PARTIAL, 0 UNTESTED, 0 FAILING.
2. **Heredar W2 a los cambios 4 y 7** como el escenario pendiente del requisito 1. El cambio 7 es el
   que introduce el segundo módulo de negocio y por tanto el único que puede cerrarlo.
3. **Heredar W9 al cambio 11**, ya anotado en `docs/09-roadmap-y-fases.md`, con el enunciado
   ampliado que recomienda «Juicio sobre W9»: el escaneo analiza lo declarado en los `pom.xml`, no
   el árbol resuelto, de modo que `provided` es el caso comprobado y no necesariamente el único.
4. **Avisar al cambio 4 del rojo programado de ADR-0018**, con la redacción literal observada en
   `35188175663`, para que su `tasks.md` lo prevea en vez de descubrirlo a mitad de camino.
5. **Conservar S1 a S10** como deuda técnica registrada; ninguna bloquea.
6. La excepción `size:exception` del 2026-09-15 (W3) se agota con este cambio y no se hereda.

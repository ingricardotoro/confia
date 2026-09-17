```yaml
schema: gentle-ai.verify-result/v1
evidence_revision: sha256:1cb69bc1e2f5aff8f104ad73551c16e3554cfa8514cefa4c507c0d0cb9d30602
verdict: fail
blockers: 1
critical_findings: 1
requirements: 5/8
scenarios: 13/16
test_command: ./mvnw -B verify
test_exit_code: 0
test_output_hash: sha256:79315232a4f63a765b15cd7d50dd2e9a77fbf6272cb5b84f44494a45e9d44e84
build_command: ./mvnw -B verify
build_exit_code: 0
build_output_hash: sha256:79315232a4f63a765b15cd7d50dd2e9a77fbf6272cb5b84f44494a45e9d44e84
```

## Verification Report — Ronda 3

**Change**: maven-workspace-and-ci-skeleton (F0, cambio 1 de 13)
**Version**: `specs/build-integrity/spec.md` — 8 requisitos, 16 escenarios
**Mode**: Standard (`strict_tdd: false`)
**Commit verificado**: `3d773886facbe94ac63aeaf75eab1bcb76aa3197`, rama `change/maven-workspace-and-ci-skeleton`
**Comando real**: `JAVA_HOME=<jdk-25.0.3.9-hotspot> ./mvnw -B verify`, ejecutado en `apps/api`

Criterio de conteo, idéntico al de las rondas 1 y 2: un escenario cuenta como completado solo si es
COMPLIANT; un requisito cuenta como completado solo si todos sus escenarios lo son.

---

### Rondas 1 y 2: qué se encontró y cómo quedó cerrado

| Hallazgo | Origen | Estado al cierre de la ronda 3 | Evidencia decisiva |
|---|---|---|---|
| **C1** — requisito 5 implementado a medias, con un Javadoc que afirmaba una garantía inexistente | Ronda 1 | **Cerrado** | La Fase 5 sustituyó la regla parcial por una `Architectures.layeredArchitecture()` completa; la ronda 2 confirmó que las tres direcciones de ADR-0002 se expresan y se detectan |
| **C2** — requisito 7 implementado en una capa distinta de la exigida | Ronda 1 | **Cerrado** | La especificación se enmendó: el requisito 7 sitúa el escaneo en integración continua, que es donde está. Residuo escalado a C2-bis en esta ronda |
| **C3** — requisito 8 incumplido, desactivación global sin ADR | Ronda 1 | **Cerrado** | ADR-0018 más dos mecanismos ejecutables; reverificado en esta ronda con las probes R4 a R7 |
| **C1-bis** — la regla de capas contaba dependencias del JDK como violaciones, lo que anulaba su mitad negativa y habría hecho imposible su mitad de producción | Ronda 2 (bloqueante) | **Cerrado** | Probes R1, R2 y R3 de esta ronda. Es el hallazgo central de la ronda 3 y se detalla abajo |
| **W6** — la versión declarada de ArchUnit no era la que se ejecutaba | Ronda 2 | **Cerrado** | Probe R9: `version managed from 1.4.2`, declarado igual a resuelto |
| **W7** — el escáner de supresiones no cubría los archivos donde viven las reglas del enforcer | Ronda 2 | **Cerrado en su parte principal** | Probe R4: una supresión sin cita dentro de `apps/api/pom.xml` ahora rompe la construcción. Queda un residuo menor (S7) |

Las advertencias que siguen abiertas se reevalúan una por una en "Estado de los hallazgos previos".

---

### Completeness

| Metric | Value |
|--------|-------|
| Tasks total | 24 (15 originales + 6 de la Fase 5 + 3 de la Fase 6) |
| Tasks complete | 24 |
| Tasks incomplete | 0 |

Las 24 casillas están marcadas `[x]`, contadas con `grep -cE "^- \[x\]" tasks.md` (24) y
`grep -cE "^- \[ \]" tasks.md` (0). Se comprobaron contra el código, no contra el texto de la tarea.
Las tres de la Fase 6 describen con exactitud lo que el commit `3d77388` contiene, y las tres se
reprodujeron de forma independiente en esta ronda (probes R1 a R4 y R9). Una sola imprecisión
documental, sin efecto sobre el comportamiento: las tareas 6.1 y `apply-progress` afirman que la
sobrecarga variádica de `assertRuleRejects` es retrocompatible con "los otros cuatro llamadores",
pero los llamadores restantes son tres (`NoCyclesTest`, `NoCrossModuleDomainImportsTest` y
`NoTechnicalLayerPackageNamesTest`); `SpringModulithVerificationTest` no usa ese ayudante. Se
registra como S8.

---

### Build & Tests Execution

**Build**: Passed — `BUILD SUCCESS`, exit 0, 22.7 s.

```text
[INFO] --- enforcer:3.6.3:enforce (enforce-build-integrity) @ confia-api-parent ---
[INFO] Rule 0: org.apache.maven.enforcer.rules.version.RequireJavaVersion passed
[INFO] Rule 1: org.apache.maven.enforcer.rules.dependency.DependencyConvergence passed
[INFO] Rule 2: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed
[INFO] --- enforcer:3.6.3:enforce (enforce-kernel-purity) @ confia-kernel ---
[INFO] Rule 0: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed
```

`DependencyConvergence` sigue en verde en los tres módulos después del anclaje de ArchUnit de la
tarea 6.2, que era el riesgo explícito de esa tarea.

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

**Integración continua**: corrida `35179705314` sobre `3d773886facbe94ac63aeaf75eab1bcb76aa3197`,
ambos trabajos en verde, confirmado con `gh run view --json headSha,conclusion,jobs`:
`security scanning` 24 s y `backend verify` 43 s, `conclusion: success` en ambos. El registro del
trabajo `backend` reproduce las mismas 22 pruebas y los mismos resultados del enforcer que la
ejecución local.

**Coverage**: no disponible — JaCoCo y los umbrales llegan con el cambio 2 (fuera de alcance).

#### Evidencia negativa ejecutada por esta ronda

Todas las mutaciones corrieron sobre copias aisladas extraídas con `git archive HEAD` al directorio
temporal de la sesión. El árbol real no se modificó en ningún momento: `git status --porcelain`
quedó vacío antes y después, y `git rev-parse HEAD` sigue en `3d77388`.

| Probe | Qué se forzó | Resultado observado | Exit |
|---|---|---|---|
| R1 | Fixture de capas **conservado** (clases y paquetes intactos), retiradas solo las dependencias ofensoras de `BadDomain`, `BadApplication` y `BadWeb` | **FAIL**. `LayeredArchitectureTest.rejectsTheFixtureLayeringViolations:90`, `Tests run: 22, Failures: 1`, `BUILD FAILURE` | 1 |
| R2 | Clase de negocio mínima `com.confia.organization.domain.Organization`, sin dependencias fuera del JDK | `LayeredArchitectureTest` **2/2 en verde** (sin violaciones espurias); falla `EmptyShouldExceptionInventoryTest` con el mensaje de caducidad correcto | 1 |
| R3 | Volcado del mensaje real de la regla de capas contra el fixture intacto | **18 violaciones, todas deliberadas**; ninguna de `java.lang.Object` ni `java.lang.String` | 1 |
| R4 | `failOnEmptyShould=false` sin cita insertado en `apps/api/pom.xml` (W7) | FAIL. «`pom.xml:6` sets failOnEmptyShould=false, which ADR-0018 forbids in any file» | 1 |
| R5 | `archRule.failOnEmptyShould=false` restituido en `archunit.properties` | FAIL. «`archunit.properties:10` sets failOnEmptyShould=false» | 1 |
| R6 | `allowEmptyShould(true)` nuevo **sin** cita de ADR en un `.java` | FAIL. «`ProbeUncitedSuppression.java:11` suppresses a rule with no ADR-NNNN citation within 4 lines» | 1 |
| R7 | `allowEmptyShould(true)` **con** cita, ausente del inventario | FAIL. «allowEmptyShould( call sites must match ... inventory exactly», `but was: 2` | 1 |
| R8 | Hibernate 6.6.15.Final agregado a `app/pom.xml` | FAIL. `BannedDependencies`: `org.hibernate.orm:hibernate-core <--- banned`, y de forma transitiva `jakarta.persistence:jakarta.persistence-api` | 1 |
| R9 | Volcado `./mvnw -X test-compile` del reactor completo | `com.tngtech.archunit:archunit:jar:1.4.2:test (scope managed from compile) (version managed from 1.4.2)` | 0 |

#### Por qué C1-bis queda cerrado

La ronda 2 no rechazó el cambio porque la regla de capas dejara pasar una violación real: rechazó
porque la prueba que la respaldaba no discriminaba. La probe G de aquella ronda conservó el paquete
`fixture` y le quitó todas las violaciones, y aun así
`rejectsTheFixtureLayeringViolations` pasó en verde, porque `consideringAllDependencies()` seguía
lanzando `AssertionError` por `java.lang.Object`. Esa misma probe es la que había que invertir.

La probe R1 es exactamente esa mutación, repetida de primera mano sobre una copia aislada del
commit actual: las tres clases y sus cuatro paquetes de capa siguen ahí, solo desaparecen las
dependencias entre capas. El resultado se invirtió:

```text
[ERROR] com.confia.architecture.LayeredArchitectureTest.rejectsTheFixtureLayeringViolations
[ERROR]   LayeredArchitectureTest.rejectsTheFixtureLayeringViolations:90
[ERROR] Tests run: 22, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

La probe R3 lo confirma desde el otro lado. Contra el fixture intacto la regla ahora reporta 18
violaciones y **las 18 son las deliberadas** —los campos, parámetros de constructor y tipos de
retorno de `BadDomain`, `BadApplication` y `BadWeb` hacia `SomeInfrastructureType` y
`SomeWebType`—, frente a las 37 de la ronda 2 de las que solo 4 eran reales. El encabezado de la
regla lo dice de forma literal: `Rule 'Layered architecture considering only dependencies in
layers, ...'`. No queda ruido del JDK.

La probe R2 cierra la segunda consecuencia que la ronda 2 anticipó, la que afectaba al cambio 4. Se
agregó una única clase de negocio mínima sin dependencias fuera del JDK. En la ronda 2 eso producía
tres violaciones espurias (`extends class <java.lang.Object>`, `calls constructor
<java.lang.Object.<init>()>`, `has return type <java.lang.String>`). Ahora `LayeredArchitectureTest`
pasa 2/2, y lo único que rompe la construcción es el mecanismo previsto por ADR-0018, con el
mensaje correcto:

```text
[ERROR] EmptyShouldExceptionInventoryTest.everyExceptionsConditionStillHolds:43
[LayeredArchitectureTest.productionCodeRespectsLayeringYet's ADR-0018 exception no longer holds
(no class in apps/api/app production code resides in a domain, application, infrastructure or web
package yet, because no business module exists (change 4 introduces the first one)). Remove
allowEmptyShould(true) from that rule and this inventory entry, or update the condition and cite
the ADR that extends it (ADR-0018, section 2).]
```

Esto importa más que el conteo de violaciones: el "rojo programado" que ADR-0018 sección 2 anuncia
para el cambio 4 ya es el rojo correcto, señalando la caducidad de la excepción y la línea a
retirar, y no el rojo engañoso por `java.lang.Object` que la ronda 2 advirtió que alguien podría
silenciar con la herramienta equivocada.

---

### Spec Compliance Matrix

| Requirement | Scenario | Test | Result |
|-------------|----------|------|--------|
| 1. Frontera de dominio entre módulos | Importación cruzada de dominio | `NoCrossModuleDomainImportsTest > rejectsTheFixtureCrossModuleDomainImport` y `SpringModulithVerificationTest > rejectsTheFixtureModuleCrossingInternalAccess`; no vacuas por la probe D de la ronda 2, evidencia heredada (ver nota) | COMPLIANT |
| 1. Frontera de dominio entre módulos | Cada módulo usa solo su propio dominio | `NoCrossModuleDomainImportsTest > productionCodeHasNoCrossModuleDomainImportYet` | PARTIAL |
| 2. Pureza del módulo `kernel` | `kernel` importa Spring | `enforce-kernel-purity` en fase `validate`; probe P2 de la ronda 1, evidencia heredada (ver nota) | COMPLIANT |
| 2. Pureza del módulo `kernel` | `kernel` solo usa el JDK | `./mvnw -B verify` y `BuildSmokeTest`, reejecutados en esta ronda | COMPLIANT |
| 3. Dependencias prohibidas | Se agrega Hibernate | **Probe R8, reejecutada en esta ronda** contra el POM actual | COMPLIANT |
| 3. Dependencias prohibidas | Sin dependencias prohibidas | `./mvnw -B verify` de esta ronda: reglas 0-2 `passed` en los tres módulos | COMPLIANT |
| 4. Paquetes con nombre de capa técnica | Paquete `services` | `NoTechnicalLayerPackageNamesTest > rejectsTheFixtureServicesPackage`; no vacua por la probe D de la ronda 2, evidencia heredada (ver nota) | COMPLIANT |
| 4. Paquetes con nombre de capa técnica | Paquetes por capacidad de negocio | `NoTechnicalLayerPackageNamesTest > productionCodeHasNoTechnicalLayerPackageNameYet` | COMPLIANT |
| 5. Reglas de capas y ausencia de ciclos | `domain` importa `infrastructure` | `LayeredArchitectureTest > rejectsTheFixtureLayeringViolations`, **ahora discriminante (probes R1 y R3)** | COMPLIANT |
| 5. Reglas de capas y ausencia de ciclos | Ciclo entre dos paquetes | `NoCyclesTest > rejectsTheFixtureTwoPackageCycle`; no vacua por la probe D de la ronda 2, evidencia heredada (ver nota) | COMPLIANT |
| 6. Integración continua verifica cada empuje | Empuje con violación | Sin corrida roja causada por una violación de arquitectura | PARTIAL |
| 6. Integración continua verifica cada empuje | Empuje sin violaciones | Corrida `35179705314` sobre `3d77388`: ambos trabajos en verde | COMPLIANT |
| 7. Vulnerabilidad alta o crítica rompe la verificación | Vulnerabilidad crítica | (ninguna) — nunca se introdujo una dependencia vulnerable | UNTESTED |
| 7. Vulnerabilidad alta o crítica rompe la verificación | Solo severidad baja | Trabajo `security scanning` de la corrida `35179705314`: escaneo ejecutado con `severity: HIGH,CRITICAL` y `exit-code: 1`, sin hallazgos, no rompe | COMPLIANT |
| 8. Ninguna regla se desactiva sin un ADR | Exclusión sin justificación | `SuppressionCitesAdrTest` (probes R4, R5, R6, R7) | COMPLIANT |
| 8. Ninguna regla se desactiva sin un ADR | Excepción documentada por ADR | `allowEmptyShould(true)` citado a ADR-0018 más `EmptyShouldExceptionInventoryTest` (probe R2) | COMPLIANT |

**Compliance summary**: 13/16 escenarios COMPLIANT; 2 PARTIAL; 1 UNTESTED.
**Requisitos completos**: 5/8 (requisitos 2, 3, 4, 5 y 8).

**Nota sobre la evidencia heredada.** Cuatro escenarios se apoyan en probes de rondas anteriores en
vez de reejecutarlas. La justificación es que sus archivos no cambiaron: `git show --stat 3d77388`
enumera exactamente cinco archivos —`ArchitectureTestSupport.java`, `LayeredArchitectureTest.java`,
`SuppressionCitesAdrTest.java`, `apps/api/pom.xml` y `tasks.md`—, de modo que
`NoCrossModuleDomainImportsTest`, `NoCyclesTest`, `NoTechnicalLayerPackageNamesTest`,
`SpringModulithVerificationTest` y `kernel/pom.xml` están intactos desde la ronda que los probó.
El caso de `apps/api/pom.xml` es distinto: ese archivo **sí** cambió, y por eso el escenario
«Se agrega Hibernate» no se heredó sino que se volvió a ejecutar (probe R8). El diff de ese archivo
confirma además que el bloque del enforcer no se tocó: las únicas líneas añadidas son el comentario
de `archunit.version` y la entrada de `dependencyManagement`.

---

### Correctness (Static Evidence)

| Requirement | Status | Notes |
|------------|--------|-------|
| 1. Frontera de dominio entre módulos | Implementado | Sin cambios desde la ronda 1. Regla doble (ArchUnit y Spring Modulith), derivación del módulo por prefijo de paquete, sin nombres fijos |
| 2. Pureza del módulo `kernel` | Implementado | `kernel/pom.xml` intacto en `3d77388` |
| 3. Dependencias prohibidas | Implementado | Bloque del enforcer intacto; reverificado en ejecución (probe R8), incluida la captura transitiva de `jakarta.persistence` |
| 4. Paquetes con nombre de capa técnica | Implementado | Los 9 nombres en la regla; sigue habiendo un solo fixture (S1) |
| 5. Reglas de capas y ausencia de ciclos | **Implementado** | Deja de ser parcial. Las tres direcciones de ADR-0002 se expresan y ahora el ámbito de dependencias es el correcto: `consideringOnlyDependenciesInLayers()` descarta todo origen o destino que no pertenezca a una capa declarada, sin fijar ningún prefijo de paquete a mano |
| 6. Integración continua verifica cada empuje | Implementado | Disparador y matriz de Java sin cambios; corrida verde verificada sobre el commit exacto |
| 7. Vulnerabilidad alta o crítica rompe la verificación | Implementado | Con la especificación enmendada, la implementación coincide con la capa exigida. Falta ejercitar el bloqueo (C2-bis) |
| 8. Ninguna regla se desactiva sin un ADR | Implementado | Dos mecanismos ejecutables, ambos probados de forma adversarial, ahora también sobre los `pom.xml` |

**Comprobación de alcance**: no se encontró nada implementado fuera del alcance aprobado. El commit
`3d77388` toca cinco archivos y ninguno es código de producción, POM de módulo nuevo, flujo de
integración continua ni módulo de negocio. En el acumulado de la rama, los 46 archivos se reparten
entre `apps/api` y `.github` (36 archivos, 1988 inserciones) y documentación y artefactos de
OpenSpec (10 archivos). Todo ello corresponde a la tabla "Cambios de archivos" de `design.md`, más
ADR-0018 y sus dos referencias en `docs/`, que la propia remediación de la ronda 1 introdujo.

---

### Coherence (Design)

| Decision | Followed? | Notes |
|----------|-----------|-------|
| 1. Reactor de tres POM | Sí | Sin cambios |
| 2. Enforcer en `validate` | Sí | Reglas `passed` en local y en integración continua; probe R8 confirma que rompe cuando debe |
| 2b. SNAPSHOT prohibido solo en main | Sí | Sin cambios (probe P5 de la ronda 1) |
| 3. Reglas auto-probadas con fixture permanente | **Sí** | Deja de ser parcial. Las cinco mitades negativas fallan si su fixture desaparece (probe D, ronda 2) y la de capas falla además si el fixture deja de violar algo conservando sus clases (probe R1) |
| 4. Lanzador por `APP_PROFILE` | Sí | Sin cambios; 10 pruebas en verde |
| 5. Integración continua parcial y honesta | Sí | Sin cambios |
| 6. Escaneo de dependencias | Sí | Trivy en modo `fs` sobre `apps/api`; la pregunta «por confirmar» 6 quedó resuelta en la ronda 2 |

---

### Issues Found

**CRITICAL**:

- **C2-bis — El escenario «Vulnerabilidad crítica» del requisito 7 nunca se ha ejercitado.**
  Es el residuo de C2 después de la enmienda de la especificación de la ronda 1, registrado como W5
  en la ronda 2. Esta ronda deja de degradarlo, por coherencia con el propio criterio de la fase: la
  tabla de decisión de `sdd-verify` clasifica como CRITICAL `UNTESTED` todo escenario sin una prueba
  que lo cubra y que haya pasado en ejecución, y este no la tiene.

  Lo que sí está probado: el escaneo existe, corre en cada empuje, reconoce los tres `pom.xml` y
  está configurado para bloquear (`severity: HIGH,CRITICAL`, `exit-code: 1`); la corrida
  `35179705314` lo muestra ejecutándose y terminando en verde sin hallazgos. Lo que no está probado
  es lo único que el escenario afirma: que ante una vulnerabilidad alta o crítica la verificación
  **falla**. Un escaneo configurado para bloquear pero nunca observado bloqueando es exactamente la
  «regla que nunca falló» que esta capacidad existe para impedir, y ADR-0008 y ADR-0013 lo exigen de
  forma explícita.

  No es una regresión de la Fase 6 ni un defecto introducido por la remediación: es un hueco que las
  rondas 1 y 2 aplazaron. Tampoco se pudo cerrar desde esta fase, que no tiene Trivy instalado en
  local ni acceso de red. Cerrarlo requiere un empuje desechable con una dependencia de
  vulnerabilidad pública conocida, observar el trabajo `security scanning` en rojo, y revertirlo.

**WARNING**:

- **W1 — El escenario «Empuje con violación» (Req. 6) sigue sin ejercitarse con una violación de
  arquitectura.** Sin cambios desde la ronda 1. La confianza es indirecta pero sólida: el trabajo
  `backend` ejecuta el mismo `./mvnw verify` que las probes R1 y R4 a R8 demuestran que falla. Se
  mantiene como advertencia y no como crítico porque su mecanismo sí tiene pruebas que pasan en
  ejecución; lo que falta es la observación del rojo en el remoto.
- **W2 — El escenario «Cada módulo usa solo su propio dominio» (Req. 1) solo se cumple de forma
  aproximada.** Sin cambios: su premisa son dos módulos de negocio y todavía no existen. Es una
  limitación de secuencia del roadmap, no un defecto de este cambio; se cierra en los cambios 4 y 7.
- **W3 — El diff sigue por encima del presupuesto de revisión.** El acumulado de la rama es de 46
  archivos y 3285 inserciones, muy por encima de las 800 líneas concedidas. Cubierto por la
  excepción `size:exception` del 2026-09-15. Se deja constancia, no bloquea. El lote de la Fase 6
  por sí solo fue de 117 líneas.
- **W4 — La tarea 4.2 la cerró el orquestador, no `sdd-apply`.** Sin cambios; solo trazabilidad.

**SUGGESTION**:

- **S1 — Solo 1 de los 9 nombres de paquete prohibidos tiene fixture.** Sin cambios.
- **S2 — Cerrada para la regla que importaba, abierta para las demás.** La mitad negativa de la
  regla de capas ya afirma sobre el contenido del mensaje, y eso es lo que cerró C1-bis. Los otros
  tres llamadores de `assertRuleRejects` siguen pasando cero fragmentos, de modo que su rechazo solo
  está protegido contra el ruido de conjunto vacío. Pasarles también sus fragmentos esperados es
  barato y elimina la misma clase de defecto antes de que reaparezca.
- **S3 — La lista de programadores de tareas prohibidos sigue siendo una enumeración cerrada.** Sin
  cambios.
- **S4 — Cachear el repositorio local de Maven en el trabajo `security`.** Prioridad baja; el
  escaneo tardó 24 s.
- **S5 — `SuppressionCitesAdrTest` se excluye a sí mismo por nombre de archivo, no por ruta.** Sin
  cambios. Un archivo futuro con el mismo nombre en otro paquete quedaría sin escanear.
- **S6 — El filtro que descarta el texto de conjunto vacío es amplio.** Sin cambios. Una violación
  legítima cuyo mensaje contuviera «is empty» se leería como vacuidad. Falla del lado seguro.
- **S7 (nueva) — Residuo de W7: el escáner cubre `.java`, `.properties` y `.xml` bajo `apps/api`,
  pero no `.github/workflows/ci.yml`.** La parte que importaba de W7 está cerrada: los `pom.xml`,
  donde viven todas las reglas del enforcer, ahora se escanean (probe R4). No es un incumplimiento
  —ADR-0018 sección 3.b acota el escáner al árbol de fuentes de `apps/api`— y hoy no existe ningún
  `.yml` bajo `apps/api`, así que el hueco se reduce a ese único archivo.
- **S8 (nueva) — Imprecisión documental en la tarea 6.1 y en `apply-progress`.** Ambas dicen «los
  otros cuatro llamadores» de `assertRuleRejects`; son tres. Sin efecto sobre el comportamiento.

---

### Estado de los hallazgos previos

| Hallazgo | Ronda | Estado | Comentario |
|---|---|---|---|
| C1 | 1 | **Cerrado** | La regla completa de capas sustituyó a la parcial y el Javadoc falso desapareció |
| C2 | 1 | Cerrado en su forma original, residuo escalado | La capa exigida y la implementada ya coinciden; el escenario negativo sigue sin ejercitarse y pasa a C2-bis |
| C3 | 1 | **Cerrado** | Reverificado en esta ronda con cuatro mutaciones (R4 a R7) |
| C1-bis | 2 | **Cerrado** | Probes R1, R2 y R3. Era el bloqueante de la ronda 2 |
| W1 | 1 | Abierto | Sin cambios |
| W2 | 1 | Abierto | Sin cambios; limitación de secuencia del roadmap |
| W3 | 1 | Abierto | Cubierto por `size:exception` |
| W4 | 1 | Abierto | Solo trazabilidad |
| W5 | 2 | **Escalado a C2-bis** | Deja de reportarse como advertencia; ver CRITICAL |
| W6 | 2 | **Cerrado** | `version managed from 1.4.2`: lo declarado coincide con lo resuelto, sin romper `DependencyConvergence` |
| W7 | 2 | **Cerrado**, con residuo menor | Los `pom.xml` se escanean; queda `ci.yml` fuera (S7) |
| S1 | 1 | Abierto | Sin cambios |
| S2 | 1 | **Cerrada donde importaba** | Ver S2 arriba |
| S3 | 1 | Abierto | Sin cambios |
| S4 | 1 | Abierto, prioridad baja | Sin cambios |
| S5 | 2 | Abierto | Sin cambios |
| S6 | 2 | Abierto | Sin cambios |

---

### Verdict

**FAIL**

Conviene separar dos cosas que apuntan en direcciones distintas.

**La remediación de la Fase 6 hizo su trabajo y lo hizo bien.** El bloqueante C1-bis está cerrado, y
cerrado por la razón correcta. La ronda 2 no rechazó el cambio porque una violación real se colara,
sino porque la prueba que respaldaba la regla de capas pasaba en verde contra un fixture al que se
le habían quitado todas las violaciones. La corrección no silenció el síntoma:
`consideringOnlyDependenciesInLayers()` deriva el filtro de las cuatro capas ya declaradas, sin
fijar a mano ningún prefijo de paquete que después haya que mantener sincronizado, y la aserción
sobre el mensaje obliga a que el rechazo nombre `BadDomain`, `BadApplication` y `BadWeb`. Las tres
probes exigidas lo confirman de primera mano: la mutación que antes pasaba ahora falla (R1), la
clase de negocio mínima ya no produce violaciones espurias (R2), y contra el fixture intacto las 18
violaciones reportadas son las 18 deliberadas (R3), frente a 4 de 37 en la ronda anterior. Los dos
mecanismos de ADR-0018 siguen mordiendo después de tocar `ArchitectureTestSupport` y
`SuppressionCitesAdrTest` a la vez (R4 a R7), el anclaje de ArchUnit dejó de ser una mediación
invisible sin romper la convergencia (R9), y el escáner llegó a los archivos donde viven las reglas
del enforcer. W6 y W7 quedan cerrados.

**Y sin embargo el cambio no pasa**, porque el requisito 7 tiene un escenario que nunca se ha
ejercitado. Un escaneo configurado para bloquear pero jamás observado bloqueando no demuestra el
requisito que dice cumplir, y degradarlo a advertencia por tercera ronda consecutiva sería
justamente la erosión que esta capacidad existe para impedir.

**Lo que el propietario tiene que decidir, porque no es una decisión de esta fase.** Tres escenarios
no se pueden cerrar desde dentro de este cambio con el alcance actual: «Cada módulo usa solo su
propio dominio» necesita dos módulos de negocio que llegan en los cambios 4 y 7; «Empuje con
violación» y «Vulnerabilidad crítica» necesitan sendos empujes desechables contra el remoto. Es
decir, 8/8 requisitos es inalcanzable aquí tal como está escrita la especificación. Hay tres salidas
razonables, y ninguna la puede tomar el verificador:

1. **Ejercitar los dos empujes desechables** (una violación de arquitectura y una dependencia con
   vulnerabilidad pública conocida), observar ambos rojos en el remoto y revertirlos. Cierra C2-bis
   y W1, y deja el cambio en 7/8, con el único pendiente atado al roadmap.
2. **Enmendar la especificación**, como ya se hizo en la ronda 1 con la capa del requisito 7, para
   que los escenarios que dependen de módulos de negocio futuros o de empujes desechables se
   declaren explícitamente fuera del alcance de este cambio y se trasladen a los cambios 4 y 7.
3. **Archivar con el FAIL documentado**, aceptando de forma expresa que el requisito 7 queda sin
   demostrar hasta el cambio 11, que es el que vuelve a tocar el escaneo de contenedores.

La opción 1 es la que deja el registro más honesto y cuesta poco: dos empujes y dos reversiones.

---

## Addendum del orquestador: evidencia observada de los requisitos 6 y 7

Capturada el 2026-09-17 mediante dos empujes desechables autorizados por el propietario, ya
revertidos (`aaec6a2`). No la produjo la fase de verificación, por lo que el veredicto de la ronda 3
sigue siendo el vigente hasta que una ronda nueva lo reemplace.

| Prueba | Commit | Corrida | Resultado |
|---|---|---|---|
| Violación de capas en código de producción | `bc013e4` | `35188175663` | `backend verify` **rojo** |
| Dependencia vulnerable, alcance `provided` | `bc013e4` | `35188175663` | `security scanning` **verde**, 0 hallazgos |
| Misma dependencia, alcance de compilación | `0ca32d4` | `35188358113` | `security scanning` **rojo**, bloqueó |
| Revertido todo | `aaec6a2` | `35188476297` | Ambos trabajos **verdes** |

**Requisito 6, escenario «empuje con violación»: demostrado.** El registro remoto muestra
`Architecture Violation ... was violated (2 times)`, nombrando
`com.confia.probe.domain.ProbeDomain.describe()` llamando a
`com.confia.probe.infrastructure.ProbeInfrastructure.value()` en `ProbeDomain.java:19`. Cierra W1.

**Requisito 7, escenario «vulnerabilidad crítica»: demostrado.** Con `log4j-core:2.14.1` en alcance
de compilación, el escaneo reportó `Total: 3 (HIGH: 1, CRITICAL: 2)` con CVE-2021-44228,
CVE-2021-45046 y CVE-2021-45105, y falló el trabajo. Cierra C2-bis.

**Hallazgo nuevo (W8): el escaneo ignora el alcance `provided`.** La única diferencia entre las dos
corridas fue el alcance de la dependencia. Con `provided`, Trivy reportó cero hallazgos sobre los
tres `pom.xml`; con alcance de compilación, los reportó todos. Una biblioteca vulnerable declarada
como provista por el entorno de ejecución pasaría sin ser vista. Corresponde decidir si el escaneo
debe cubrir también ese alcance, por ejemplo analizando un SBOM resuelto en lugar de los `pom.xml`
en crudo, como contempla `docs/03-seguridad.md` sección 13.

```yaml
schema: gentle-ai.verify-result/v1
evidence_revision: sha256:7ab993f96839313d074f738b0b11fbf02e37c2724ff644afe25676a15328f490
verdict: fail
blockers: 1
critical_findings: 1
requirements: 4/8
scenarios: 12/16
test_command: ./mvnw -B verify
test_exit_code: 0
test_output_hash: sha256:7b77ecb04546e761e1091b127cbed9ff257b55171da5489817f607da0c21a8f5
build_command: ./mvnw -B verify
build_exit_code: 0
build_output_hash: sha256:7b77ecb04546e761e1091b127cbed9ff257b55171da5489817f607da0c21a8f5
```

## Verification Report — Ronda 2

**Change**: maven-workspace-and-ci-skeleton (F0, cambio 1 de 13)
**Version**: `specs/build-integrity/spec.md` — 8 requisitos, 16 escenarios (especificación enmendada
tras la ronda 1: el requisito 7 ahora exige el escaneo en integración continua y no en `./mvnw verify`)
**Mode**: Standard (`strict_tdd: false`)
**Commit verificado**: `1f3b3288b0bcac030706df950bd02d849ee23720`, rama `change/maven-workspace-and-ci-skeleton`
**Comando real**: `JAVA_HOME=<jdk-25.0.3.9-hotspot> ./mvnw -B verify`, ejecutado en `apps/api`

Criterio de conteo, idéntico al de la ronda 1: un escenario cuenta como completado solo si es
COMPLIANT; un requisito cuenta como completado solo si todos sus escenarios lo son.

---

### Ronda 1: qué se encontró y cómo se cerró

La ronda 1 terminó en **FAIL** con tres hallazgos críticos, cuatro advertencias y cuatro sugerencias.

| Hallazgo | Estado tras la remediación | Evidencia de esta ronda |
|---|---|---|
| **C1** — requisito 5 implementado a medias, con un Javadoc que afirmaba una garantía inexistente | **Cerrado en parte, y reabierto por un defecto nuevo** | El Javadoc falso desapareció y las tres direcciones de ADR-0002 sí se expresan y se detectan (probe H). Pero la regla nueva incorpora `consideringAllDependencies()`, que introduce el defecto C1-bis de abajo |
| **C2** — requisito 7 implementado en una capa distinta de la exigida | **Cerrado** | La especificación se enmendó: el requisito 7 ahora sitúa el escaneo en integración continua de forma explícita, que es donde está. El residuo (escenario negativo nunca ejercitado) baja a W5 |
| **C3** — requisito 8 incumplido, desactivación global sin ADR | **Cerrado, y verificado de forma adversarial** | Probes A, B, C, E y F: las cinco mutaciones rompen la construcción |

Las advertencias y sugerencias de la ronda 1 se reevalúan una por una en la sección
"Estado de los hallazgos de la ronda 1".

---

### Completeness

| Metric | Value |
|--------|-------|
| Tasks total | 21 (15 originales + 6 de la Fase 5) |
| Tasks complete | 21 |
| Tasks incomplete | 0 |

Las 21 casillas están marcadas `[x]`. Se comprobó una por una contra el código, no contra el texto de
la tarea. Las seis de la Fase 5 describen con exactitud lo que el commit `1f3b328` contiene: la tarea
5.1 sustituye la clase (no solo edita el Javadoc, desviación ya declarada en `apply-progress`), y las
5.2 a 5.5 corresponden a cambios reales y verificables. La tarea 5.6 afirma haber probado la no
vacuidad borrando el paquete `fixture`; **esa prueba se reprodujo y es correcta en su resultado, pero
insuficiente como garantía**, por lo que se detalla en C1-bis.

---

### Build & Tests Execution

**Build**: Passed — `BUILD SUCCESS`, exit 0.

```text
[INFO] --- enforcer:3.6.3:enforce (enforce-build-integrity) @ confia-api-parent ---
[INFO] Rule 0: org.apache.maven.enforcer.rules.version.RequireJavaVersion passed
[INFO] Rule 1: org.apache.maven.enforcer.rules.dependency.DependencyConvergence passed
[INFO] Rule 2: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed
[INFO] --- enforcer:3.6.3:enforce (enforce-kernel-purity) @ confia-kernel ---
[INFO] Rule 0: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed
```

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

**Integración continua**: corrida `35167872324` sobre `1f3b328`, ambos trabajos en verde
(`backend verify` y `security scanning`), confirmado con `gh run view`.

**Coverage**: no disponible — JaCoCo y los umbrales llegan con el cambio 2 (fuera de alcance).

#### Evidencia negativa ejecutada por esta ronda

Todas las mutaciones corrieron sobre copias aisladas en el directorio temporal de la sesión. El árbol
real no se modificó en ningún momento: `git status --porcelain` quedó vacío antes y después.

| Probe | Qué se forzó | Resultado observado | Exit |
|---|---|---|---|
| A | `archRule.failOnEmptyShould=false` reintroducido | FAIL. `SuppressionCitesAdrTest`: «archunit.properties:10 sets failOnEmptyShould=false, which ADR-0018 forbids in any file» | 1 |
| B | `allowEmptyShould(true)` nuevo **sin** cita de ADR | FAIL. «NoTechnicalLayerPackageNamesTest.java:28 suppresses a rule with no ADR-NNNN citation within 4 lines» | 1 |
| C | `allowEmptyShould(true)` nuevo **con** cita, ausente del inventario | FAIL. «allowEmptyShould( call sites must match ... inventory exactly», expected 1 but was 2 | 1 |
| D | Paquete `fixture` borrado por completo | FAIL. Las cinco mitades negativas caen (4 `Failures` + 1 `Error`) | 1 |
| E | Primer módulo de negocio (`com.confia.organization.domain.Organization`) | FAIL. `EmptyShouldExceptionInventoryTest` nombra la regla y la condición; **además** `LayeredArchitectureTest` cae por `java.lang.Object` y `java.lang.String` (ver C1-bis) | 1 |
| F | Cita a un ADR inexistente (`ADR-9999`) | FAIL. «LayeredArchitectureTest.java:58 cites ADR-9999, which does not exist under docs/adr/» | 1 |
| G | Fixtures de capas **neutralizados** (clases y paquetes intactos, sin ninguna dependencia entre capas) | **BUILD SUCCESS**, `LayeredArchitectureTest` 2/2 en verde. La mitad negativa «rechaza» un fixture que no viola nada (ver C1-bis) | 0 |
| H | Volcado del mensaje real de la regla de capas contra el fixture verdadero | La regla sí detecta las importaciones reales, pero son 4 de **37** violaciones; las otras 33 son `java.lang.Object` y `java.lang.String` | 0 |

Las probes A, B, C, E y F cierran C3 de forma concluyente: los dos mecanismos que ADR-0018 exige
(inventario de caducidad y escáner de supresiones) existen y **muerden**.

La probe G es el hallazgo nuevo de esta ronda y contradice la conclusión que la probe D sugiere por
sí sola. La D demuestra que borrar el paquete `fixture` rompe la construcción; la G demuestra que
**conservar el paquete y quitarle todas las violaciones no la rompe**. La no vacuidad de la regla de
capas, por tanto, no está probada: solo está probada frente al caso extremo de que el paquete
desaparezca por completo.

---

### Spec Compliance Matrix

| Requirement | Scenario | Test | Result |
|-------------|----------|------|--------|
| 1. Frontera de dominio entre módulos | Importación cruzada de dominio | `NoCrossModuleDomainImportsTest > rejectsTheFixtureCrossModuleDomainImport` y `SpringModulithVerificationTest > rejectsTheFixtureModuleCrossingInternalAccess` (no vacuas, probe D) | COMPLIANT |
| 1. Frontera de dominio entre módulos | Cada módulo usa solo su propio dominio | `NoCrossModuleDomainImportsTest > productionCodeHasNoCrossModuleDomainImportYet` | PARTIAL |
| 2. Pureza del módulo `kernel` | `kernel` importa Spring | `enforce-kernel-purity` en fase `validate` (probe P2 de la ronda 1; `kernel/pom.xml` no cambió en `1f3b328`) | COMPLIANT |
| 2. Pureza del módulo `kernel` | `kernel` solo usa el JDK | `./mvnw -B verify` y `BuildSmokeTest`, reejecutados en esta ronda | COMPLIANT |
| 3. Dependencias prohibidas | Se agrega Hibernate | Probe P1 de la ronda 1 (`apps/api/pom.xml` no cambió en `1f3b328`) | COMPLIANT |
| 3. Dependencias prohibidas | Sin dependencias prohibidas | `./mvnw -B verify` de esta ronda: reglas 0-2 `passed`; probe P3 de la ronda 1 | COMPLIANT |
| 4. Paquetes con nombre de capa técnica | Paquete `services` | `NoTechnicalLayerPackageNamesTest > rejectsTheFixtureServicesPackage` (no vacua, probe D) | COMPLIANT |
| 4. Paquetes con nombre de capa técnica | Paquetes por capacidad de negocio | `NoTechnicalLayerPackageNamesTest > productionCodeHasNoTechnicalLayerPackageNameYet` | COMPLIANT |
| 5. Reglas de capas y ausencia de ciclos | `domain` importa `infrastructure` | `LayeredArchitectureTest > rejectsTheFixtureLayeringViolations` — **la regla detecta la importación (probe H), pero la prueba que la cubre no discrimina (probe G)** | PARTIAL |
| 5. Reglas de capas y ausencia de ciclos | Ciclo entre dos paquetes | `NoCyclesTest > rejectsTheFixtureTwoPackageCycle` (no vacua, probe D) | COMPLIANT |
| 6. Integración continua verifica cada empuje | Empuje con violación | Sin corrida roja causada por una violación de arquitectura | PARTIAL |
| 6. Integración continua verifica cada empuje | Empuje sin violaciones | Corrida `35167872324` sobre `1f3b328`: ambos trabajos en verde | COMPLIANT |
| 7. Vulnerabilidad alta o crítica rompe la verificación | Vulnerabilidad crítica | (ninguna) — nunca se introdujo una dependencia vulnerable | UNTESTED |
| 7. Vulnerabilidad alta o crítica rompe la verificación | Solo severidad baja | Trabajo `security scanning` de la corrida `35167872324`: escaneo ejecutado, sin hallazgos, no rompe | COMPLIANT |
| 8. Ninguna regla se desactiva sin un ADR | Exclusión sin justificación | `SuppressionCitesAdrTest` (probes A, B, C, F) | COMPLIANT |
| 8. Ninguna regla se desactiva sin un ADR | Excepción documentada por ADR | `allowEmptyShould(true)` citado a ADR-0018 más `EmptyShouldExceptionInventoryTest` (probe E) | COMPLIANT |

**Compliance summary**: 12/16 escenarios COMPLIANT; 3 PARTIAL; 1 UNTESTED.
**Requisitos completos**: 4/8 (requisitos 2, 3, 4 y 8).

Nota sobre el requisito 8: pasa de incumplido a completo. Es el avance más sólido de esta ronda y el
único requisito que la remediación cerró por entero.

---

### Correctness (Static Evidence)

| Requirement | Status | Notes |
|------------|--------|-------|
| 1. Frontera de dominio entre módulos | Implementado | Sin cambios desde la ronda 1. Regla doble (ArchUnit y Spring Modulith), derivación del módulo por prefijo de paquete, sin nombres fijos |
| 2. Pureza del módulo `kernel` | Implementado | `kernel/pom.xml` intacto en `1f3b328` |
| 3. Dependencias prohibidas | Implementado | `apps/api/pom.xml` intacto en `1f3b328` |
| 4. Paquetes con nombre de capa técnica | Implementado | Los 9 nombres en la regla; sigue habiendo un solo fixture (S1) |
| 5. Reglas de capas y ausencia de ciclos | **Parcial** | Ciclos: completo. Capas: las tres direcciones de ADR-0002 ya se expresan, pero `consideringAllDependencies()` las hace inservibles contra código real y no discriminantes contra el fixture. Ver C1-bis |
| 6. Integración continua verifica cada empuje | Implementado | Disparador y matriz de Java sin cambios; corrida verde verificada sobre el commit exacto |
| 7. Vulnerabilidad alta o crítica rompe la verificación | Implementado | Con la especificación enmendada, la implementación coincide con la capa exigida. El registro confirma `trivy fs apps/api`, `[vuln] Vulnerability scanning is enabled`, `Number of language-specific files num=3`, `[pom] Detecting vulnerabilities...` y `Clean (no security findings detected)`. Falta ejercitar el bloqueo (W5) |
| 8. Ninguna regla se desactiva sin un ADR | **Implementado** | Deja de ser prosa. Dos mecanismos ejecutables, ambos probados de forma adversarial. La única excepción vigente está declarada, citada y con caducidad verificada |

**Comprobación de alcance**: no se encontró nada implementado fuera del alcance aprobado. El commit
`1f3b328` toca 11 archivos: 8 fuentes de prueba bajo `com.confia.architecture`, `archunit.properties`,
`package-info.java` y `tasks.md`. Ni POMs, ni `ci.yml`, ni código de producción, ni módulos de negocio.

---

### Coherence (Design)

| Decision | Followed? | Notes |
|----------|-----------|-------|
| 1. Reactor de tres POM | Sí | Sin cambios |
| 2. Enforcer en `validate` | Sí | Sin cambios; reglas `passed` en la corrida de esta ronda |
| 2b. SNAPSHOT prohibido solo en main | Sí | Sin cambios (probe P5 de la ronda 1) |
| 3. Reglas auto-probadas con fixture permanente | **Parcial** | El mecanismo se reforzó de verdad para cuatro de las cinco reglas. Para la regla de capas, la mitad negativa sigue sin poder distinguir un rechazo real de ruido del JDK (probe G) |
| 4. Lanzador por `APP_PROFILE` | Sí | Sin cambios; 10 pruebas en verde |
| 5. Integración continua parcial y honesta | Sí | Sin cambios |
| 6. Escaneo de dependencias | Sí | La pregunta «por confirmar» número 6 queda resuelta por el registro: Trivy en modo `fs` reconoce los tres `pom.xml` y los analiza. No hace falta la ruta de SBOM CycloneDX en este cambio |

---

### Issues Found

**CRITICAL**:

- **C1-bis — La regla de capas nueva cuenta dependencias del JDK como violaciones de capa, lo que
  anula su mitad negativa y hará imposible su mitad de producción.**
  `LayeredArchitectureTest.layeringRule()` (líneas 32-50) usa `.consideringAllDependencies()`. Con esa
  opción, ArchUnit evalúa **toda** dependencia, incluidas las que apuntan a clases que no pertenecen a
  ninguna capa declarada, y las cláusulas `mayNotAccessAnyLayer()` y `mayOnlyAccessLayers(...)` las
  marcan como violación. Dos consecuencias, ambas verificadas de primera mano:

  1. **La mitad negativa no demuestra nada.** En la probe G se conservaron las tres clases de fixture
     de capas y sus paquetes, y se les quitaron todas las dependencias entre capas. La regla siguió
     lanzando `AssertionError` —por `java.lang.Object` y `java.lang.String`— y
     `rejectsTheFixtureLayeringViolations` pasó en verde: `Tests run: 2, Failures: 0`, `BUILD SUCCESS`.
     Es decir, esa prueba pasa con cualquier conjunto de clases no vacío, viole o no las capas.
     `assertRuleRejects` filtra el mensaje de conjunto vacío («failed to check any classes», «is
     empty») pero no el ruido del JDK, de modo que el refuerzo de la tarea 5.5 cubre el caso de la
     probe D y deja abierto el de la probe G. La probe H lo confirma desde el otro lado: contra el
     fixture verdadero la regla reporta **37 violaciones**, de las cuales solo 4 son las importaciones
     deliberadas; las 33 restantes son `extends class <java.lang.Object>`, `calls constructor
     <java.lang.Object.<init>()>` y `has return type <java.lang.String>`.
  2. **La mitad de producción no podrá pasar nunca.** En la probe E se agregó una sola clase de
     negocio mínima, `com.confia.organization.domain.Organization`, sin ninguna dependencia fuera del
     JDK. `productionCodeRespectsLayeringYet` falló con tres violaciones, todas espurias:
     `Class <...Organization> extends class <java.lang.Object>`,
     `Constructor <...Organization.<init>()> calls constructor <java.lang.Object.<init>()>` y
     `Method <...Organization.name()> has return type <java.lang.String>`.

  Por qué es crítico y no una advertencia: es el mismo defecto de fondo que C1 señaló en la ronda 1
  —una garantía escrita que el código no entrega— trasladado de un Javadoc a la definición de la
  regla. La ronda 1 aceptó el escenario «`domain` importa `infrastructure`» como COMPLIANT sobre la
  base de que la mitad negativa era no vacua; esa base ya no se sostiene. Y afecta directamente al
  cambio 4: ADR-0018 sección 2 anuncia un rojo programado cuando aparezca el primer módulo de negocio,
  pero será un rojo distinto y engañoso, causado por `java.lang.Object` en vez de por la caducidad de
  la excepción, lo que hace probable que alguien lo silencie con la herramienta equivocada.

  Corrección sugerida (no aplicada): acotar el ámbito de dependencias de la regla, por ejemplo con
  `.consideringOnlyDependenciesInAnyPackage("com.confia..")`, o conservar `consideringAllDependencies()`
  y añadir un `ignoreDependency` para todo lo que resida fuera de `com.confia..`. Además, reforzar la
  mitad negativa para que afirme sobre el contenido del mensaje —que nombre `BadDomain`,
  `BadApplication` y `BadWeb`— y no solo sobre el tipo de excepción; es la pieza de S2 que sigue
  pendiente y que aquí es determinante, no cosmética.

**WARNING**:

- **W1 — El escenario «Empuje con violación» (Req. 6) sigue sin ejercitarse con una violación de
  arquitectura.** Sin cambios desde la ronda 1. La confianza sigue siendo indirecta: el trabajo
  ejecuta el mismo `./mvnw verify` que las probes A-F demuestran que falla.
- **W2 — El escenario «Cada módulo usa solo su propio dominio» (Req. 1) solo se cumple de forma
  aproximada.** Sin cambios: su premisa son dos módulos de negocio y todavía no existen.
- **W3 — El diff sigue por encima del presupuesto de revisión, y creció.** La Fase 5 añade 416
  inserciones y 48 borrados sobre un cambio que ya superaba las 800 líneas concedidas. Cubierto por la
  excepción `size:exception` del 2026-09-15; se deja constancia, no bloquea.
- **W4 — La tarea 4.2 la cerró el orquestador, no `sdd-apply`.** Sin cambios; solo trazabilidad.
- **W5 — El escenario negativo del requisito 7 nunca se ejercitó.** Es el residuo de C2 después de la
  enmienda de la especificación. El escaneo corre, reconoce los tres `pom.xml` y está configurado para
  bloquear (`severity: HIGH,CRITICAL`, `exit-code: 1`), pero nunca se introdujo una dependencia
  vulnerable que demuestre el bloqueo. No se pudo ejercitar en esta ronda: Trivy no está instalado
  localmente y esta fase no dispone de acceso a la red. Se reporta como advertencia y no como
  bloqueante porque la ruta de cumplimiento existe, está configurada y se observó ejecutándose;
  cerrarlo requiere un empuje desechable con una dependencia de vulnerabilidad conocida.
- **W6 — La versión declarada de ArchUnit no es la que se usa.** `apps/api/pom.xml:35` declara
  `archunit.version=1.5.0` y `app/pom.xml` la aplica a `archunit-junit5`, pero
  `spring-modulith-core:2.1.1` declara `com.tngtech.archunit:archunit:1.4.2` a menor profundidad y
  gana la mediación de Maven. Verificado de primera mano con un volcado de `-X`:

  ```text
  com.tngtech.archunit:archunit-junit5:jar:1.5.0:test
  com.tngtech.archunit:archunit-junit5-api:jar:1.5.0:test
  com.tngtech.archunit:archunit-junit5-engine:jar:1.5.0:test
  com.tngtech.archunit:archunit-junit5-engine-api:jar:1.5.0:test
  com.tngtech.archunit:archunit:jar:1.4.2:test
  ```

  Coincide con el repositorio local: el directorio de `archunit` 1.5.0 contiene únicamente el POM, sin
  JAR, mientras que el de 1.4.2 sí tiene JAR. El `DependencyConvergence` del enforcer no lo detecta
  porque son artefactos distintos, no dos versiones del mismo. **Severidad: riesgo latente, no un
  defecto de este cambio.** La combinación funciona hoy —las 22 pruebas pasan— y ninguna cláusula de
  la especificación la prohíbe, pero es una pareja no soportada por el proveedor y la propiedad del
  POM afirma algo distinto de lo que se ejecuta, que es justo la clase de discrepancia silenciosa que
  esta capacidad existe para evitar. Corrección recomendada (no aplicada, y a decidir por el
  propietario): importar `com.tngtech.archunit:archunit-bom` en el `dependencyManagement` del POM
  padre, que alinea `archunit` y `archunit-junit5` de una sola vez; como alternativa, declarar
  `com.tngtech.archunit:archunit` explícitamente con la misma propiedad de versión. Lo primero es
  preferible: no deja una segunda versión que mantener a mano.
- **W7 — El escáner de supresiones no cubre los archivos donde vive la mayoría de las reglas.**
  `SuppressionCitesAdrTest.SCANNED_EXTENSIONS` es `{".java", ".properties"}` y su raíz es `apps/api`.
  Quedan fuera los `pom.xml` —donde están todas las reglas del `maven-enforcer-plugin`— y
  `.github/workflows/ci.yml`. Hoy el comentario de `apps/api/pom.xml` líneas 112-114 pide citar el ADR
  ante cualquier exclusión futura del enforcer, pero eso vuelve a ser prosa sin mecanismo, que es
  exactamente lo que ADR-0018 quiso erradicar. El ADR lo permite —su sección 3.b describe un catálogo
  que crece por herramienta— así que no es un incumplimiento, pero sí una cobertura menor de la que el
  requisito 8 sugiere. Sugerencia: añadir `.xml` y `.yml` al conjunto de extensiones.

**SUGGESTION**:

- **S1 — Solo 1 de los 9 nombres de paquete prohibidos tiene fixture.** Sin cambios desde la ronda 1.
- **S2 — Parcialmente cerrada.** `assertRuleRejects` ya descarta el mensaje de conjunto vacío, lo que
  la probe D confirma. Lo que la ronda 1 pedía —afirmar sobre el mensaje de la violación— sigue
  pendiente, y para la regla de capas ha dejado de ser una mejora opcional: es la causa de C1-bis.
- **S3 — La lista de programadores de tareas prohibidos sigue siendo una enumeración cerrada.** Sin
  cambios desde la ronda 1.
- **S4 — Cachear el repositorio local de Maven en el trabajo `security` resultó menos necesario de lo
  que la ronda 1 supuso.** El registro de la corrida `35167872324` muestra el escaneo completo en unos
  dos segundos leyendo los tres `pom.xml`, sin invocar Maven. Se mantiene como sugerencia de
  robustez, con prioridad baja.
- **S5 — `SuppressionCitesAdrTest` se excluye a sí mismo por nombre de archivo, no por ruta.** Un
  archivo futuro llamado igual en otro paquete quedaría sin escanear. Trivial, pero gratuito de
  corregir.
- **S6 — El filtro que descarta el texto de conjunto vacío es amplio.** Una violación legítima cuyo
  mensaje contuviera esa misma cadena se leería como vacuidad. Falla del lado seguro, así que es
  menor.

---

### Estado de los hallazgos de la ronda 1

| Ronda 1 | Estado | Comentario |
|---|---|---|
| C1 | Parcial, reabierto como C1-bis | La garantía falsa se movió del Javadoc a la definición de la regla |
| C2 | **Cerrado** | Enmienda de la especificación; residuo en W5 |
| C3 | **Cerrado** | Único hallazgo cerrado con verificación adversarial completa |
| W1 | Abierto | Sin cambios |
| W2 | Abierto | Sin cambios |
| W3 | Abierto | Creció; cubierto por `size:exception` |
| W4 | Abierto | Solo trazabilidad |
| S1 | Abierto | Sin cambios |
| S2 | **Parcial** | La mitad fácil se cerró; la que importaba sigue abierta y es la causa de C1-bis |
| S3 | Abierto | Sin cambios |
| S4 | Abierto, prioridad menor | Evidencia nueva de integración continua lo desdramatiza |

---

### Verdict

**FAIL**

La remediación hizo bien el trabajo más difícil. El requisito 8 pasó de incumplirse a tener dos
mecanismos ejecutables que muerden de verdad: cinco mutaciones deliberadas —propiedad global en
`false`, supresión sin cita, supresión no inventariada, cita a un ADR inexistente y aparición del
primer módulo de negocio— rompen la construcción, cada una con el mensaje correcto y señalando la
línea exacta. Eso cierra C3 sin reservas y es un resultado mejor que el que la ronda 1 pedía.

El cambio no pasa por un solo hallazgo, y es el mismo defecto de fondo que la ronda 1 ya había
señalado, en otro lugar. La regla de capas que sustituyó a la parcial cubre por fin las tres
direcciones de ADR-0002, pero `consideringAllDependencies()` la hace contar `java.lang.Object` y
`java.lang.String` como violaciones de capa. La consecuencia medida es doble: su prueba negativa pasa
en verde contra un fixture al que se le quitaron todas las violaciones, de modo que ya no demuestra
que la regla detecte nada; y su mitad de producción falla ante la primera clase de negocio real, con
tres violaciones espurias. La ronda 1 dio por COMPLIANT el escenario «domain importa infrastructure»
porque la mitad negativa era no vacua; esa base desapareció y el escenario baja a PARTIAL.

Conviene ser preciso sobre el alcance del defecto: la regla **sí** detecta las importaciones
prohibidas reales, como muestra la probe H. No hay una violación de arquitectura que hoy se cuele. Lo
que falta es la prueba que impida que eso cambie mañana sin que nadie se entere, que es precisamente
la razón de ser de esta capacidad.

Las otras dos novedades —la versión de ArchUnit declarada que no coincide con la que se ejecuta, y el
escáner de supresiones que no mira los POM donde viven las reglas del enforcer— son riesgos latentes
que conviene registrar y decidir, no motivos de rechazo.

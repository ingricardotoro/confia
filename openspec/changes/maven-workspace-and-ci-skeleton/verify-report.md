```yaml
schema: gentle-ai.verify-result/v1
evidence_revision: sha256:28c78fca70d59fd0b14a477ff436a3fb7c23555f58dd33574c228e69740e1f9f
verdict: fail
blockers: 3
critical_findings: 3
requirements: 4/8
scenarios: 11/16
test_command: ./mvnw -B verify
test_exit_code: 0
test_output_hash: sha256:ae9b82c326b305d920a8c859a651ecb76760bf7bef7c1d1e94a0134ff2676519
build_command: ./mvnw -B verify
build_exit_code: 0
build_output_hash: sha256:ae9b82c326b305d920a8c859a651ecb76760bf7bef7c1d1e94a0134ff2676519
```

## Verification Report

**Change**: maven-workspace-and-ci-skeleton (F0, cambio 1 de 13)
**Version**: `specs/build-integrity/spec.md` — 8 requisitos, 16 escenarios
**Mode**: Standard (`strict_tdd: false`)
**Commit verificado**: `8de0cb1761e5aa729a06fbc61655522fb7752d85`, rama `change/maven-workspace-and-ci-skeleton`
**Comando real**: `JAVA_HOME=<jdk-25.0.3.9-hotspot> ./mvnw -B verify`, ejecutado en `apps/api`.

Criterio de conteo: un escenario cuenta como completado solo si es COMPLIANT; un requisito cuenta
como completado solo si todos sus escenarios son COMPLIANT. Los incumplimientos del texto normativo
que no se reflejan en un escenario se reportan en Correctness y en Issues.

### Completeness

| Metric | Value |
|--------|-------|
| Tasks total | 15 |
| Tasks complete | 15 |
| Tasks incomplete | 0 |

Las 15 tareas están marcadas [x]. La tarea 4.2 quedó cerrada por el orquestador (empuje y
confirmación en el remoto), no por `sdd-apply`, que la reportó como parcial. La verificación
independiente con `gh` confirma que el cierre es real (ver Req. 6).

### Build & Tests Execution

**Build**: Passed

```text
[INFO] Rule 0: org.apache.maven.enforcer.rules.version.RequireJavaVersion passed
[INFO] Rule 1: org.apache.maven.enforcer.rules.dependency.DependencyConvergence passed
[INFO] Rule 2: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed
[INFO] --- enforcer:3.6.3:enforce (enforce-kernel-purity) @ confia-kernel ---
[INFO] Rule 0: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed
[INFO] BUILD SUCCESS
[INFO] Total time:  22.279 s
```

**Tests**: 21 passed / 0 failed / 0 skipped

```text
[INFO] Tests run: 1,  Failures: 0, Errors: 0, Skipped: 0  -- confia-kernel (BuildSmokeTest)
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0  -- confia-api
     10 ConfiaApplicationTest + 10 architecture (5 clases, mitad produccion + mitad fixture)
```

**Coverage**: Not available — JaCoCo y los umbrales llegan con el cambio 2 (fuera de alcance).

#### Evidencia negativa ejecutada por esta fase

Ninguna de estas pruebas modificó el repositorio: P1-P4 corrieron sobre una copia aislada en el
directorio temporal; P5 y P6 son invocaciones de solo lectura sobre el árbol real. `git status`
quedó limpio al terminar.

| Probe | Qué se forzó | Resultado observado | Exit |
|---|---|---|---|
| P1 | `org.hibernate.orm:hibernate-core` en `app` | BUILD FAILURE: "Banned dependency (ADR-0015 ...)", señala `hibernate-core` y el transitivo `jakarta.persistence-api` | 1 |
| P2 | `org.springframework:spring-core` en `kernel` | BUILD FAILURE: "apps/api/kernel must not depend on anything outside the JDK" | 1 |
| P3 | `org.postgresql:postgresql` en `app` | BUILD SUCCESS — el controlador JDBC no está prohibido, como exige la especificación | 0 |
| P4 | Paquete `fixture` eliminado de la copia | Las 5 mitades de fixture fallan (4 Failures y 1 Error) | 1 |
| P5 | `./mvnw -N validate -Dconfia.ci.mainBranch=true` | BUILD FAILURE: "main never builds a SNAPSHOT version of apps/api" | 1 |
| P6 | JAVA_HOME apuntando a JDK 21 | BUILD FAILURE: "Detected JDK ... is version 21.0.10 which is not in the allowed range [25,26)" | 1 |

P4 es la prueba de no vacuidad más importante: demuestra que las cinco mitades de fixture fallan
cuando desaparece la violación deliberada, es decir que no pasan por coincidir con cero clases.

### Spec Compliance Matrix

| Requirement | Scenario | Test | Result |
|-------------|----------|------|--------|
| 1. Frontera de dominio entre módulos | Importación cruzada de dominio | `NoCrossModuleDomainImportsTest > rejectsTheFixtureCrossModuleDomainImport` y `SpringModulithVerificationTest > rejectsTheFixtureModuleCrossingInternalAccess` (no vacuas, P4) | COMPLIANT |
| 1. Frontera de dominio entre módulos | Cada módulo usa solo su propio dominio | `NoCrossModuleDomainImportsTest > productionCodeHasNoCrossModuleDomainImportYet` | PARTIAL |
| 2. Pureza del módulo kernel | `kernel` importa Spring | Probe P2 — `enforce-kernel-purity` en fase `validate` | COMPLIANT |
| 2. Pureza del módulo kernel | `kernel` solo usa el JDK | `./mvnw -B verify` y `BuildSmokeTest` | COMPLIANT |
| 3. Dependencias prohibidas | Se agrega Hibernate | Probe P1 | COMPLIANT |
| 3. Dependencias prohibidas | Sin dependencias prohibidas | `./mvnw -B verify` (reglas 0-2 passed) y probe P3 | COMPLIANT |
| 4. Paquetes con nombre de capa técnica | Paquete `services` | `NoTechnicalLayerPackageNamesTest > rejectsTheFixtureServicesPackage` (no vacua, P4) | COMPLIANT |
| 4. Paquetes con nombre de capa técnica | Paquetes por capacidad de negocio | `NoTechnicalLayerPackageNamesTest > productionCodeHasNoTechnicalLayerPackageNameYet` | COMPLIANT |
| 5. Reglas de capas y ausencia de ciclos | `domain` importa `infrastructure` | `DomainDoesNotDependOnOuterLayersTest > rejectsTheFixtureDomainImportingInfrastructure` (no vacua, P4) | COMPLIANT |
| 5. Reglas de capas y ausencia de ciclos | Ciclo entre dos paquetes | `NoCyclesTest > rejectsTheFixtureTwoPackageCycle` (no vacua, P4) | COMPLIANT |
| 6. Integración continua verifica cada empuje | Empuje con violación | Corrida 35057498110 en rojo (fallo de configuración del flujo, no violación de arquitectura) | PARTIAL |
| 6. Integración continua verifica cada empuje | Empuje sin violaciones | Corrida 35057835556 sobre `8de0cb1`: ambos trabajos en verde | COMPLIANT |
| 7. Vulnerabilidad alta o crítica rompe la construcción | Vulnerabilidad crítica | (ninguna) — nunca se ejercitó, y el escaneo no forma parte de `./mvnw verify` | UNTESTED |
| 7. Vulnerabilidad alta o crítica rompe la construcción | Solo severidad baja | Trabajo `security scanning` en verde; no vía `./mvnw verify` | PARTIAL |
| 8. Ninguna regla se desactiva sin un ADR | Exclusión sin justificación | (ninguna) — sin mecanismo automático; además hoy se incumple (ver C3) | UNTESTED |
| 8. Ninguna regla se desactiva sin un ADR | Excepción documentada por ADR | Cero exclusiones vigentes; `package-info.java` documenta la regla | COMPLIANT |

**Compliance summary**: 11/16 escenarios COMPLIANT; 3 PARTIAL; 2 UNTESTED.
**Requisitos completos**: 4/8 (requisitos 2, 3, 4 y 5).

### Correctness (Static Evidence)

| Requirement | Status | Notes |
|------------|--------|-------|
| 1. Frontera de dominio entre módulos | Implementado | Regla doble (ArchUnit y Spring Modulith). La derivación del módulo de dominio por prefijo de paquete funciona sin nombres fijos, así que servirá igual a los módulos reales del cambio 4. |
| 2. Pureza del módulo kernel | Implementado | `bannedDependencies` sobre compile/runtime/provided/system en `kernel/pom.xml`, fase `validate`: falla antes de compilar, como pide el escenario. |
| 3. Dependencias prohibidas | Implementado | La lista coincide con ADR-0015 (Hibernate, Jakarta y javax Persistence, Spring Data JPA, Spring Data JDBC) y ADR-0016 (Quartz, JobRunr). El controlador de PostgreSQL y el JDBC del JDK quedan permitidos (P3). |
| 4. Paquetes con nombre de capa técnica | Implementado | Los 9 nombres están en la regla; solo `services` tiene fixture (ver S1). |
| 5. Reglas de capas y ausencia de ciclos | Parcial | Ciclos: completo. Capas: solo se verifica la dirección hacia adentro de `domain`. Ver C1. |
| 6. Integración continua verifica cada empuje | Implementado | Disparador push a `change/**` y `main`, más pull_request a `main`; Java 25 Temurin coincide con el POM y ADR-0013. |
| 7. Vulnerabilidad alta o crítica rompe la construcción | Parcial | El escaneo existe y bloquea en integración continua, pero no dentro de `./mvnw verify`. Ver C2. |
| 8. Ninguna regla se desactiva sin un ADR | Incumplido hoy | Documentado en prosa, sin mecanismo, y con una desactivación vigente que cita `design.md` en vez de un ADR. Ver C3. |

Comprobación de alcance: no se encontró nada implementado fuera del alcance aprobado. No hay
módulos de negocio, ni tablas, ni migraciones, ni Spring Security, ni `packages/*`, ni perfiles de
mutación. Los archivos versionados bajo `apps/api` corresponden exactamente a la tabla "Cambios de
archivos" del diseño.

### Coherence (Design)

| Decision | Followed? | Notes |
|----------|-----------|-------|
| 1. Reactor de tres POM | Sí | Padre pom, más `kernel` y `app` (artefacto `confia-api`). |
| 2. Enforcer en `validate` | Sí | Confirmado en el registro de la construcción y en P1, P2, P5, P6. |
| 2b. SNAPSHOT prohibido solo en main | Sí | Perfil `no-snapshots-on-main` activado por `confia.ci.mainBranch=true`, que `ci.yml` fija solo cuando la referencia es la rama principal. Documentado en el POM. Verificado en P5. |
| 3. Reglas auto-probadas con fixture permanente | Parcial | El mecanismo es sólido y no vacuo (P4), pero faltan las reglas de capa hacia afuera que la propuesta enumeraba (`application-no-infrastructure`, `interface-only-application`). Ver C1. |
| 4. Lanzador por APP_PROFILE | Sí | Contrato de la tabla del diseño respetado por completo; ver la nota siguiente. |
| 5. Integración continua parcial y honesta | Sí | Solo `backend` y `security`; sin `frontend`, `e2e`, `quality-gate` ni `-Pmutation-gate`. |
| 6. Escaneo de dependencias | Parcial | `trivy-action@v0.36.0`, modo `fs`, severidad HIGH y CRITICAL, `exit-code: 1`, más estricto que docs/06 seccion 14.5 al omitir `ignore-unfixed`. Pero la pregunta "por confirmar" numero 6 nunca se cerró. Ver C2. |

**Sobre la desviación reportada por `sdd-apply` (`LaunchOutcome`)**: es aceptable y no altera el
contrato. `ConfiaApplication.launch()` devuelve un record en vez de un int solo para que las pruebas
puedan cerrar el contexto sin invocar `System.exit()`. El comportamiento externo coincide
exactamente con la tabla del diseño: `admin`, `portal` y `worker` arrancan su propio contexto (main
no llama a `System.exit` para no matar el proceso recién iniciado), `migrate` se reconoce y termina
con código 0 sin contexto, y un valor ausente, en blanco o desconocido termina con código distinto
de cero. Los 10 casos de `ConfiaApplicationTest` cubren los cuatro valores válidos y seis variantes
inválidas, incluidas cadena vacía, espacios, `ADMIN` y `null`.

### Issues Found

**CRITICAL**:

- **C1 — El requisito 5 solo está implementado a medias, y el código afirma lo contrario.**
  El texto normativo exige "web hacia application hacia domain, infrastructure implementando puertos
  de application". La única regla de capas existente es
  `apps/api/app/src/test/java/com/confia/architecture/DomainDoesNotDependOnOuterLayersTest.java`
  líneas 19-24, que solo prohíbe que `..domain..` dependa de `..application..`, `..infrastructure..`
  o `..web..`. No se verifica que `application` no dependa de `infrastructure` ni de `web`, ni que
  `web` no salte a `infrastructure`, ni que `infrastructure` implemente puertos de `application`. La
  propuesta enumeraba `application-no-infrastructure` e `interface-only-application` como reglas
  propias (líneas 31-34) y no existen. Agrava el defecto que el Javadoc de la línea 12 declare esas
  tres reglas "collapsed into one direction check": la fusión no es equivalente y deja una garantía
  falsa escrita en el repositorio. Hoy no hay código que pueda violarla, pero el cambio 4 introducirá
  módulos de negocio contra un conjunto de reglas incompleto, que es exactamente lo que esta
  capacidad existe para impedir. Corrección sugerida: una regla `layeredArchitecture()` completa más
  los fixtures negativos correspondientes.

- **C2 — El requisito 7 se implementó en una capa distinta de la que exige la especificación, y su
  escenario negativo nunca se ejercitó.** Los dos escenarios dicen "CUANDO se ejecuta
  `./mvnw verify`". El escaneo vive únicamente en el trabajo `security` de
  `.github/workflows/ci.yml` líneas 42-57: `./mvnw verify` en local no escanea vulnerabilidad
  alguna. Además nunca se introdujo una dependencia vulnerable para comprobar que el bloqueo ocurre,
  así que el escenario "Vulnerabilidad crítica" no tiene evidencia de tiempo de ejecución.
  Matiz verificado a favor de la implementación: el escaneo no es estructuralmente vacuo. El registro
  de la corrida 35057835556 muestra `Number of language-specific files num=3`, `[pom] Detecting
  vulnerabilities...` y los tres `pom.xml` reconocidos como tipo `pom` con 0 hallazgos. Pero al
  reproducirlo localmente con `aquasec/trivy:latest`, la herramienta resolvió BOM remotos y murió con
  `429 Too Many Requests` de Maven Central, recomendando poblar `~/.m2` antes de escanear; el trabajo
  `security` no instala Java, no ejecuta Maven y no cachea `~/.m2`, de modo que depende de accesos
  vivos a Maven Central. Con `--list-all-pkgs` desactivado, el registro tampoco permite confirmar
  cuántos paquetes resolvió. Esto deja abierta la pregunta "por confirmar" numero 6 del diseño (si el
  modo `fs` cubre las dependencias resueltas o hace falta la ruta de SBOM CycloneDX). Se requiere una
  decisión: mover el escaneo dentro de `./mvnw verify`, o enmendar la especificación para que
  describa la integración continua como la capa de cumplimiento.

- **C3 — El requisito 8 se incumple hoy mismo.**
  `apps/api/app/src/test/resources/archunit.properties` línea 10 fija
  `archRule.failOnEmptyShould=false`, cuyo efecto es desactivar la red de seguridad de ArchUnit ante
  conjunto vacío para todas las reglas. La justificación escrita en las líneas 1-9 cita docs/06
  seccion 14.1 y `design.md`, no un ADR, que es precisamente lo que el escenario "Exclusión sin
  justificación" declara una violación en sí misma. El riesgo es concreto y duradero: con ese valor,
  una regla futura cuyo ámbito quede mal escrito y no coincida con ninguna clase pasará en silencio
  para siempre, y no existe tarea ni mecanismo que obligue a devolverlo a `true` cuando el cambio 4
  agregue el primer módulo de negocio. Además el requisito no tiene ningún mecanismo automático de
  verificación: se sostiene solo en la prosa de `package-info.java` y en comentarios del POM.
  Corrección sugerida: un ADR que autorice explícitamente el ajuste con fecha de caducidad, o
  restringirlo a las reglas de producción vacías en vez de aplicarlo de forma global.

**WARNING**:

- **W1 — El escenario "Empuje con violación" (Req. 6) no se ejercitó con una violación de
  arquitectura.** Existe una corrida en rojo visible en el remoto (35057498110, commit `31c0a8e`),
  lo que demuestra que un resultado rojo sí se publica, pero se debió a una etiqueta inexistente de
  `trivy-action`, no a una violación de las reglas. La confianza restante es indirecta: el trabajo
  ejecuta exactamente el `./mvnw verify` que P1, P2, P4 y P6 demuestran que falla.
- **W2 — El escenario "Cada módulo usa solo su propio dominio" (Req. 1) solo se cumple de forma
  aproximada.** Su premisa son "dos módulos de negocio existentes" y todavía no existen. La regla se
  evaluó contra las clases de producción reales y no encontró violaciones, pero ninguna reside en un
  paquete `domain`. La propuesta ya reconoce que el criterio de salida 1 de F0 no se cierra aquí.
- **W3 — El diff supera el presupuesto de revisión concedido.** Medido entre `f5d3be6` y `8de0cb1`:
  1644 líneas totales, 1160 excluyendo el Maven Wrapper autogenerado (`mvnw`, `mvnw.cmd`), frente a
  un `review_budget_lines` de 800. Coincide con lo que `sdd-apply` reportó (unas 1110) y está
  cubierto por la excepción `size:exception` otorgada el 2026-09-15; se deja constancia, no bloquea.
- **W4 — La tarea 4.2 la cerró el orquestador, no `sdd-apply`.** El artefacto `apply-progress`
  registra 14/15. La verificación independiente con `gh` confirma que el cierre es legítimo, de modo
  que la casilla marcada refleja la realidad; se señala solo por trazabilidad.

**SUGGESTION**:

- **S1 — Solo 1 de los 9 nombres de paquete prohibidos tiene fixture.**
  `NoTechnicalLayerPackageNamesTest` enumera nueve nombres y solo `services` se demuestra rechazado.
  Un error de escritura en `..controllers..` o `..repositories..` pasaría inadvertido. El caso
  `interface` no admite fixture porque es palabra reservada de Java.
- **S2 — `assertRuleRejects` acepta cualquier `AssertionError`.** En `ArchitectureTestSupport.java`
  línea 46 basta con que se lance el tipo. Si alguien devolviera `failOnEmptyShould` a `true`, un
  fallo por conjunto vacío se tomaría como "la regla rechazó el fixture". Conviene afirmar también
  sobre el mensaje de la violación. La mitad de Spring Modulith ya lo hace mejor, exigiendo
  `Violations`.
- **S3 — La lista de programadores de tareas prohibidos es una enumeración cerrada.** ADR-0016
  prohíbe "Quartz, JobRunr y cualquier otra biblioteca de programación"; el POM solo veta
  `org.quartz-scheduler:*` y `org.jobrunr:*`. Una tercera biblioteca entraría sin resistencia.
- **S4 — Considerar cachear `~/.m2` en el trabajo `security`**, según recomienda la propia
  herramienta, para que el escaneo no dependa de la disponibilidad de Maven Central.

### Verdict

**FAIL**

La construcción, las pruebas y la integración continua están en verde y verificadas de primera mano,
y el mecanismo central del cambio (reglas que se demuestran a sí mismas contra un fixture permanente)
es sólido y probadamente no vacuo. Pero tres requisitos de la especificación no se cumplen: el 5 está
implementado a medias bajo un comentario que afirma lo contrario, el 7 vive en una capa distinta de
la que la especificación exige y su escenario negativo nunca se ejercitó, y el 8 se incumple con una
desactivación vigente que no cita ningún ADR.

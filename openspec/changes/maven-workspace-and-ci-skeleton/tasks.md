# Tareas: esqueleto de espacio de trabajo Maven e integración continua

## Review Workload Forecast

| Campo | Valor |
|---|---|
| Presupuesto de revisión de este proyecto | 800 líneas (`review_budget_lines`, no el valor por defecto de 400) |
| Líneas cambiadas estimadas | 750–950 (POMs, wrapper, lanzador, reglas ArchUnit, fixtures permanentes, `ci.yml`) |
| Riesgo respecto al presupuesto de 800 líneas | Alto — los paquetes de fixture permanentes (decisión 3 del diseño) son el sobrecosto más probable |
| PR encadenados recomendados | Sí, si el presupuesto de 800 se ve en riesgo real durante la implementación |
| División sugerida | Estrategia fijada: PR único (`single-pr`). Las unidades de trabajo abajo son la división natural si se otorga excepción de tamaño o se reconsidera la estrategia |
| Estrategia de entrega | single-pr |
| Estrategia de cadena | size-exception |

Decision needed before apply: Resolved — `size:exception` aceptada explícitamente por el propietario del producto el 2026-09-15 para este cambio y solo para este. Se entrega en un PR único.
Chained PRs recommended: No (excepción otorgada; las cuatro unidades quedan como guía de revisión, no como PR separados)
Chain strategy: n/a (single-pr con size:exception)
400-line budget risk: High

### Suggested Work Units

| Unit | Goal | Likely PR | Focused test command | Runtime harness | Rollback boundary |
|------|------|-----------|----------------------|-----------------|-------------------|
| 1 | Reactor Maven + wrapper + prueba de humo (tareas 1–4) | PR 1 | `./mvnw -pl apps/api/kernel test` | Construcción en máquina limpia sin Maven instalado | Borrar `apps/api` |
| 2 | Reglas del enforcer (tareas 5–7) | PR 2 | `./mvnw verify` con dependencia prohibida agregada temporalmente | `./mvnw verify` en local | Revertir bloque `maven-enforcer-plugin` en `apps/api/pom.xml` |
| 3 | Lanzador `APP_PROFILE`, ArchUnit y fixtures permanentes (tareas 8–13) | PR 3 | `./mvnw -pl apps/api/app test` | Ejecutar cada valor de `APP_PROFILE` en local | Borrar `apps/api/app/src/main/java/com/confia/bootstrap` y `.../architecture` |
| 4 | Integración continua y verificación final (tareas 14–15) | PR 4 | Empuje a `change/maven-workspace-and-ci-skeleton` | Revisar el resultado en `github.com/ingricardotoro/confia` → pestaña Actions | Borrar `.github/workflows/ci.yml` |

Nota: `strict_tdd` permanece en `false` en `openspec/config.yaml` para este cambio (no hay `Money` ni regla de negocio que probar primero); pasa a `true` en el cambio 2. No se modifica `config.yaml` aquí.

## Phase 1: Reactor Maven y prueba de humo

- [x] 1.1 Crear `apps/api/pom.xml` (POM padre): Java 25, BOM de Spring Boot 4.1, módulos `kernel` y `app`, Surefire y Failsafe declarados. Versiones y objetivos exactos de complementos se confirman contra su documentación al implementar. Verificación: `./mvnw -N validate` resuelve sin error. — Infraestructura
- [x] 1.2 Comprometer el Maven Wrapper (`apps/api/mvnw`, `mvnw.cmd`, `.mvn/wrapper/`) y fijar el bit ejecutable con `git update-index --chmod=+x apps/api/mvnw`; confirmar que `.gitattributes` ya cubre `mvnw text eol=lf`. Verificación: `./mvnw -v` funciona en checkout limpio sin Maven instalado. — Infraestructura
- [x] 1.3 Crear `apps/api/kernel/pom.xml` sin dependencias fuera del JDK, `package-info.java`, y `apps/api/kernel/src/test/java/com/confia/kernel/BuildSmokeTest.java` trivial en verde. Verificación: `./mvnw -pl apps/api/kernel test` termina en verde. — Requisito: Pureza del módulo `kernel`
- [x] 1.4 Crear `apps/api/app/pom.xml`, artefacto `confia-api`, dependiente de `kernel`. Verificación: `./mvnw verify` desde `apps/api` termina en verde con solo la prueba de humo. — Infraestructura

## Phase 2: Reglas del `maven-enforcer-plugin`

- [x] 2.1 Agregar `maven-enforcer-plugin` al POM padre en fase `validate`: `requireJavaVersion` (25), convergencia de dependencias, `bannedDependencies` para Hibernate, Jakarta Persistence, Spring Data JPA, Spring Data JDBC, Quartz, JobRunr y otros programadores de tareas (el controlador JDBC de PostgreSQL y el JDBC del JDK no se prohíben). Verificación: agregar Hibernate temporalmente en un módulo de prueba rompe `./mvnw verify`; al retirarla vuelve a verde. — Requisito: Dependencias de persistencia y de tareas prohibidas
- [x] 2.2 Agregar regla del enforcer que restringe `apps/api/kernel` a dependencias del JDK. Verificación: agregar temporalmente una dependencia de Spring al `pom.xml` de `kernel` rompe la construcción; al retirarla vuelve a verde. — Requisito: Pureza del módulo `kernel`
- [x] 2.3 Agregar un perfil del enforcer que prohíbe versiones `SNAPSHOT`, activado únicamente por una propiedad que la integración continua fija solo en `main`; documentar el mecanismo de activación confirmado en el POM. — Infraestructura (soporta Req. 8: la excepción por rama queda documentada, no oculta)

## Phase 3: Lanzador `APP_PROFILE`, ArchUnit y fixtures permanentes

- [x] 3.1 Crear `apps/api/app/src/main/java/com/confia/bootstrap/` con `AdminApplication`, `PortalApplication`, `WorkerApplication` y el `main` que despacha según `APP_PROFILE`; `migrate` se reconoce sin comportamiento; valor ausente o desconocido aborta con código distinto de cero. Prueba unitaria por cada valor válido y por el valor inválido. — Infraestructura (fija `scanBasePackages` explícito por proceso, base del Req. de capas)
- [x] 3.2 Crear `apps/api/app/src/test/resources/archunit.properties` fijando el comportamiento ante conjunto vacío (confirmar la propiedad exacta, p. ej. `archRule.failOnEmptyShould`, contra la documentación de ArchUnit).
- [x] 3.3 Crear `apps/api/app/src/test/java/com/confia/architecture/fixture/` (paquete permanente, excluido de las reglas de producción) con violaciones deliberadas: importación cruzada de `domain` entre dos módulos simulados, `domain` importando `infrastructure`, un ciclo entre dos paquetes, y un paquete `services`.
- [x] 3.4 Crear las reglas ArchUnit en `apps/api/app/src/test/java/com/confia/architecture/`: `no-cross-module-domain`, capas `web → application → domain`, `no-cycles`, y rechazo de paquetes `interface`, `interfaces`, `controllers`, `services`, `repositories`, `entities`, `utils`, `helpers`, `common`. Cada regla con dos pruebas: se aplica al código de producción (hoy vacío) y **rechaza** su fixture de la tarea 3.3. Verificación: `./mvnw -pl apps/api/app test` en verde. — Requisito: Frontera de dominio entre módulos de negocio; Paquetes con nombre de capa técnica prohibidos; Reglas de capas y ausencia de ciclos
- [x] 3.5 Agregar la verificación de módulos de Spring Modulith en el mismo paquete `architecture`; confirmar el mecanismo y la API exacta de Spring Modulith 2 contra su documentación al implementar. — Requisito: Reglas de capas y ausencia de ciclos
- [x] 3.6 Documentar en `apps/api/app/src/test/java/com/confia/architecture/` (comentario/Javadoc junto a las reglas) que ninguna exclusión o supresión futura es válida sin citar el ADR que la autoriza; hoy no existe ninguna excepción vigente. — Requisito: Ninguna regla se desactiva sin un ADR

## Phase 4: Integración continua y verificación final

- [x] 4.1 Crear `.github/workflows/ci.yml` con el trabajo `backend` (`./mvnw verify` sobre `apps/api`) y el escaneo `aquasecurity/trivy-action@v0.36.0` en modo `fs`, severidad `HIGH,CRITICAL`, `exit-code: 1`. Disparador en empujes a `change/**` y `main`, y en `pull_request` hacia `main` (`docs/06` §14.5; decisión ya cerrada en `design.md`, no vuelve a evaluarse al implementar). Sin trabajos `frontend`, `e2e` ni `quality-gate` (llegan en cambios 3 y 11); sin `-Pmutation-gate` (cambio 2). — Requisito: Integración continua verifica cada empuje; Vulnerabilidad alta o crítica rompe la construcción
- [x] 4.2 En un checkout limpio, ejecutar `./mvnw verify` de extremo a extremo; empujar la rama `change/maven-workspace-and-ci-skeleton` y confirmar en `github.com/ingricardotoro/confia` → Actions que el flujo termina en verde. **Evidencia:** `./mvnw -B verify` reejecutado por el orquestador con JDK 25.0.3 → `BUILD SUCCESS`, 20 pruebas, 0 fallos; corrida `35057671700` (commit `2bf1c22`) en verde en ambos trabajos, tras corregir la etiqueta inexistente `trivy-action@0.28.0` por `v0.36.0`. — Requisito: Integración continua verifica cada empuje (cierre)
  - **Parcialmente cumplido por `sdd-apply`:** `./mvnw verify` de extremo a extremo confirmado en verde localmente en checkout limpio (ver apply-progress). El empuje de la rama y la confirmación en GitHub Actions quedan para la fase de entrega del orquestador (`sdd-apply` no empuja ni abre pull request por contrato de esta ejecución).

## Fase 5: Correcciones de la verificación

Lote de remediación sobre los hallazgos críticos C1 y C3 de `verify-report.md`. Alcance exacto: cerrar
esos dos hallazgos; C2, las advertencias W1–W4 y las sugerencias S1–S4 quedan fuera.

- [x] 5.1 (C1) Sustituir la regla parcial de `DomainDoesNotDependOnOuterLayersTest` (que solo
  verificaba `domain` hacia afuera y cuyo Javadoc afirmaba falsamente que las tres reglas de
  ADR-0002 estaban "collapsed into one direction check") por una única regla
  `Architectures.layeredArchitecture()` completa en `LayeredArchitectureTest.java`: `web` solo
  depende de `application`, `application` solo depende de `domain`, `infrastructure` implementa
  los puertos de `application` (depende de `application` y `domain`, nadie depende de ella), y
  `domain`/`infrastructure`/`web` nunca son alcanzados fuera de lo permitido. Fixtures negativos
  nuevos bajo `fixture/layering/application/` y `fixture/layering/web/` prueban cada dirección
  prohibida. — Requisito: Reglas de capas y ausencia de ciclos (C1)
- [x] 5.2 (C3) Restaurar `archRule.failOnEmptyShould=true` explícito en `archunit.properties`
  (ADR-0018) y declarar la única excepción vigente con `allowEmptyShould(true)` en la mitad de
  producción de `LayeredArchitectureTest`, citando ADR-0018 junto al código; la mitad de fixture
  nunca lleva la excepción. — Requisito: Ninguna regla se desactiva sin un ADR (C3)
- [x] 5.3 (C3) Implementar el inventario de caducidad exigido por ADR-0018 sección 3.a
  (`EmptyShouldExceptionInventoryTest`): declara la única excepción vigente, su condición ("no
  existe módulo de negocio bajo `apps/api/app`") y el ADR que la autoriza; falla nombrando la regla
  el día que la condición deje de cumplirse. — Requisito: Ninguna regla se desactiva sin un ADR (C3)
- [x] 5.4 (C3) Implementar el escáner de supresiones exigido por ADR-0018 sección 3.b
  (`SuppressionCitesAdrTest`): recorre `apps/api`, falla ante un marcador de supresión
  (`allowEmptyShould(`, `@ArchIgnore`) sin cita `ADR-NNNN` adyacente, ante un ADR citado que no
  existe en `docs/adr/`, ante cualquier `failOnEmptyShould=false`, y ante una discrepancia entre el
  número de `allowEmptyShould(` y las entradas del inventario de la tarea 5.3. Verificado con tres
  mutaciones deliberadas (propiedad global en `false`, cita ausente): las tres las detecta. —
  Requisito: Ninguna regla se desactiva sin un ADR (C3)
- [x] 5.5 (C3, hallazgo incidental) Fortalecer `ArchitectureTestSupport.assertRuleRejects` para
  distinguir un rechazo real de un `AssertionError` producido solo por conjunto vacío (mensajes
  `"failed to check any classes"` / `"is empty"` de ArchUnit): sin este cambio, con
  `failOnEmptyShould=true` restaurado, borrar el paquete `fixture` dejaba en verde 4 de las 5
  pruebas negativas en lugar de romper la construcción — exactamente el riesgo que ADR-0018,
  sección "Cumplimiento y verificación" punto 4, exige impedir. — Requisito: Ninguna regla se
  desactiva sin un ADR (C3)
- [x] 5.6 Prueba de no vacuidad de cierre: en una copia aislada con el paquete `fixture` borrado,
  las cinco pruebas negativas (`LayeredArchitectureTest`, `NoCrossModuleDomainImportsTest`,
  `NoCyclesTest`, `NoTechnicalLayerPackageNamesTest`, `SpringModulithVerificationTest`) fallan
  (4 `Failures` + 1 `Error`); `./mvnw -B verify` completo en checkout real: `BUILD SUCCESS`, 22
  pruebas en `confia-api`, 0 fallos. — Verificación

## Fase 6: Segunda corrección de verificación

Lote de remediación sobre los hallazgos C1-bis (bloqueante), W6 y W7 de la ronda 2 de
`verify-report.md`. Alcance exacto: cerrar esos tres; todo lo demás (W1, W2, W5, las sugerencias
restantes, el archivo de especificación y cualquier módulo de negocio) queda fuera.

- [x] 6.1 (C1-bis, bloqueante) `LayeredArchitectureTest.layeringRule()` cambia
  `.consideringAllDependencies()` por `.consideringOnlyDependenciesInLayers()`, confirmado con
  `javap -c` contra el jar resuelto de `archunit` 1.4.2: `consideringAllDependencies()` no aplica
  ningún filtro (su lambda es la identidad), mientras que `consideringOnlyDependenciesInLayers()`
  marca como irrelevante toda dependencia cuyo origen o destino no pertenezca a ninguna capa
  declarada — exactamente `java.lang.Object` y `java.lang.String`. `ArchitectureTestSupport.
  assertRuleRejects` gana una sobrecarga con fragmentos de mensaje esperados (variádica,
  retrocompatible con los otros cuatro llamadores) y `rejectsTheFixtureLayeringViolations` ahora
  exige que el mensaje nombre `BadDomain`, `BadApplication` y `BadWeb`. — Requisito: Reglas de
  capas y ausencia de ciclos (C1-bis)
  - **Prueba obligatoria de discriminación (probe G invertida):** en una copia aislada, se
    neutralizaron las tres dependencias ofensoras de `BadDomain`, `BadApplication` y `BadWeb`
    (se conservaron las clases y los paquetes, no se borró `fixture`). Antes de este cambio esa
    neutralización dejaba `rejectsTheFixtureLayeringViolations` en verde (probe G del informe de
    verificación); con `consideringOnlyDependenciesInLayers()` la misma neutralización ahora
    **falla**: `LayeredArchitectureTest.rejectsTheFixtureLayeringViolations:90 Expecting code to
    raise a throwable`. Prueba borrada tras la verificación; el árbol real quedó intacto
    (`git status --porcelain` vacío antes y después).
- [x] 6.2 (W6) `apps/api/pom.xml` declara una entrada de `dependencyManagement` explícita para
  `com.tngtech.archunit:archunit:1.4.2` (comentario que cita W6 y explica la mediación de Maven vía
  `spring-modulith-core`), en vez de dejar que la versión real del artefacto `archunit` (distinto de
  `archunit-junit5`, que sigue en `archunit.version=1.5.0`) dependa de una mediación implícita.
  Evidencia: `./mvnw -pl app -X test-compile` muestra `com.tngtech.archunit:archunit:jar:1.4.2:test
  (version managed from 1.4.2)`; `./mvnw -B validate` conserva `Rule 1:
  DependencyConvergence passed` en los tres módulos; `./mvnw -B verify` completo en verde. No se
  sube a 1.5.0: ese jar nunca se descargó en este entorno (solo su POM; PKIX en Windows bloquea
  Maven Central), y 1.4.2 es el que ya está probado desde el lote de aplicación original. —
  Infraestructura (cierre de riesgo latente, no requisito de la especificación)
- [x] 6.3 (W7) `SuppressionCitesAdrTest.SCANNED_EXTENSIONS` agrega `.xml` a `.java` y
  `.properties`, para cubrir los `pom.xml` donde viven las reglas del `maven-enforcer-plugin`.
  **Prueba obligatoria:** en una copia aislada del repositorio completo, se insertó
  `<!-- failOnEmptyShould=false -->` sin cita de ADR en `apps/api/pom.xml`; la prueba lo detectó:
  `[undocumented or invalid suppressions found] Expecting empty but was: [".../pom.xml:116 sets
  failOnEmptyShould=false, which ADR-0018 forbids..."]`. Copia borrada tras la verificación. —
  Requisito: Ninguna regla se desactiva sin un ADR (cobertura, W7)

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

- [ ] 3.1 Crear `apps/api/app/src/main/java/com/confia/bootstrap/` con `AdminApplication`, `PortalApplication`, `WorkerApplication` y el `main` que despacha según `APP_PROFILE`; `migrate` se reconoce sin comportamiento; valor ausente o desconocido aborta con código distinto de cero. Prueba unitaria por cada valor válido y por el valor inválido. — Infraestructura (fija `scanBasePackages` explícito por proceso, base del Req. de capas)
- [ ] 3.2 Crear `apps/api/app/src/test/resources/archunit.properties` fijando el comportamiento ante conjunto vacío (confirmar la propiedad exacta, p. ej. `archRule.failOnEmptyShould`, contra la documentación de ArchUnit).
- [ ] 3.3 Crear `apps/api/app/src/test/java/com/confia/architecture/fixture/` (paquete permanente, excluido de las reglas de producción) con violaciones deliberadas: importación cruzada de `domain` entre dos módulos simulados, `domain` importando `infrastructure`, un ciclo entre dos paquetes, y un paquete `services`.
- [ ] 3.4 Crear las reglas ArchUnit en `apps/api/app/src/test/java/com/confia/architecture/`: `no-cross-module-domain`, capas `web → application → domain`, `no-cycles`, y rechazo de paquetes `interface`, `interfaces`, `controllers`, `services`, `repositories`, `entities`, `utils`, `helpers`, `common`. Cada regla con dos pruebas: se aplica al código de producción (hoy vacío) y **rechaza** su fixture de la tarea 3.3. Verificación: `./mvnw -pl apps/api/app test` en verde. — Requisito: Frontera de dominio entre módulos de negocio; Paquetes con nombre de capa técnica prohibidos; Reglas de capas y ausencia de ciclos
- [ ] 3.5 Agregar la verificación de módulos de Spring Modulith en el mismo paquete `architecture`; confirmar el mecanismo y la API exacta de Spring Modulith 2 contra su documentación al implementar. — Requisito: Reglas de capas y ausencia de ciclos
- [ ] 3.6 Documentar en `apps/api/app/src/test/java/com/confia/architecture/` (comentario/Javadoc junto a las reglas) que ninguna exclusión o supresión futura es válida sin citar el ADR que la autoriza; hoy no existe ninguna excepción vigente. — Requisito: Ninguna regla se desactiva sin un ADR

## Phase 4: Integración continua y verificación final

- [ ] 4.1 Crear `.github/workflows/ci.yml` con el trabajo `backend` (`./mvnw verify` sobre `apps/api`) y el escaneo `aquasecurity/trivy-action@0.28.0` en modo `fs`, severidad `HIGH,CRITICAL`, `exit-code: 1`. Disparador en empujes a `change/**` y `main`, y en `pull_request` hacia `main` (`docs/06` §14.5; decisión ya cerrada en `design.md`, no vuelve a evaluarse al implementar). Sin trabajos `frontend`, `e2e` ni `quality-gate` (llegan en cambios 3 y 11); sin `-Pmutation-gate` (cambio 2). — Requisito: Integración continua verifica cada empuje; Vulnerabilidad alta o crítica rompe la construcción
- [ ] 4.2 En un checkout limpio, ejecutar `./mvnw verify` de extremo a extremo; empujar la rama `change/maven-workspace-and-ci-skeleton` y confirmar en `github.com/ingricardotoro/confia` → Actions que el flujo termina en verde. — Requisito: Integración continua verifica cada empuje (cierre)

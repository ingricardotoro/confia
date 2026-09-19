# Exploración: división de F0 "Fundaciones" en cambios SDD ejecutables

- **Cambio:** `foundations-plan`
- **Fase:** explorar
- **Fecha:** 2026-09-15
- **Estado:** exploración terminada, pendiente de propuesta y de aprobación del propietario

## Estado actual

`docs/09-roadmap-y-fases.md` §3 define F0 con 12 entregables y 8 criterios de salida (unas seis
semanas con margen). El límite de quince tareas por cambio (`openspec/changes/README.md`,
`docs/13-metodologia-sdd.md` §8) y el presupuesto de 800 líneas con estrategia de un solo pull
request hacen inviable un único cambio: F0 toca `identity`, `shared/audit`, `shared/security`,
`shared/observability`, `kernel`, `packages/contracts`, `packages/ui`, `packages/config` e `infra/`
a la vez.

El stack vigente es Java 25 con Spring Boot 4.1 y Maven (ADR-0013, que reemplaza a ADR-0001).

## Áreas afectadas

`docs/09-roadmap-y-fases.md` §3, `openspec/changes/README.md`, `docs/13-metodologia-sdd.md` §8,
`docs/01-arquitectura.md` §3, §4 y §9, los ADR 0002, 0003, 0004, 0008, 0009, 0010, 0012, 0013,
0015, 0016 y 0017, `docs/02-modelo-de-dominio.md` §2.8 y §3.5,
`.claude/skills/confia-module-scaffold/SKILL.md`, `docs/03-seguridad.md` §6,
`docs/05-infraestructura-y-despliegue.md` y `docs/10-analisis-de-brechas.md`.

## 1. División propuesta de F0 en trece cambios

| # | Identificador del cambio | Cubre de F0 | Criterio de salida | Depende de | Tamaño aproximado |
|---|---|---|---|---|---|
| 1 | `maven-workspace-and-ci-skeleton` | Entregable 1 parcial: Maven de varios módulos, ArchUnit, Spring Modulith, enforcer, integración continua | Mecanismo del criterio 1 | — (primero) | ~10 tareas |
| 2 | `kernel-money-value-object` | Entregable 2: `Money`, 95 % de cobertura, mutación 80 | — | 1 | 10 a 12 tareas |
| 3 | `frontend-monorepo-and-contracts-pipeline` | Entregable 1, parte de pnpm y Turborepo, más generación con orval | Criterio 7 | 1 (paralelo a 2) | ~10 tareas |
| 4 | `institution-root-and-multitenancy-baseline` | Alcance nuevo, ver hallazgo | — | 1 (paralelo a 2 y 3) | 6 a 8 tareas |
| 5 | `transactional-runner-and-audit-log` | Entregable 5 (brecha B6) | Criterio 3 | 1, 2, 4 | 13 a 14 tareas, riesgo de exceder |
| 6 | `idempotency-key-infrastructure` | Entregable 6 (brecha B7) | Criterio 4 | 5 | 8 a 9 tareas |
| 7 | `staff-authentication-mfa-sessions` | Entregable 3 | — | 5, 4, 2 | 12 a 13 tareas |
| 8 | `rbac-permission-matrix-and-audit-integration` | Entregable 4 (brecha A7) y semilla mínima | Criterio 2 | 7 | ~10 tareas |
| 9 | `background-jobs-with-db-scheduler` | Entregable 12 | Criterio 5 | 5, 6 | 12 a 13 tareas |
| 10 | `structured-logging-and-tracing` | Entregable 7 | — | 1 (paralelo a 5 a 9) | 7 a 8 tareas |
| 11 | `containerization-and-cicd-pipeline` | Entregable 8 | — | 1, 3 | 12 a 14 tareas, riesgo de exceder |
| 12 | `backup-restore-and-drill` | Entregable 9 (brecha B10) | Criterio 6 | 11 | 9 a 10 tareas |
| 13 | `design-system-foundations-and-a11y` | Entregable 10 | Criterio 8 | 3 | 9 a 10 tareas |

Paralelismo posible: los cambios 2, 3 y 4 después del 1; los cambios 10 y 13 en paralelo a la
cadena 5 → 6 → 7 → 8 → 9; los cambios 11 y 12 al final.

Si al llegar a la fase de tareas los cambios 5 u 11 superan el presupuesto, se dividen así: el 5 en
`jooq-flyway-testcontainers-wiring` más `audit-log-and-transaction-runner`; el 11 en
`dockerfiles-and-local-compose` más `cicd-pipeline-and-preprod-deploy`.

**Nota (2026-09-19, decisión D1 de `institution-root-and-multitenancy-baseline`).** El cambio 4
entrega el módulo `organization` sin tabla. La migración de `organization_institution` (seguridad a
nivel de fila sobre su propia clave primaria, ADR-0009 y ADR-0017; prefijo según ADR-0015) y su
repositorio jOOQ pasan al cambio 5, y esa migración es la primera del cambio 5. Si el cambio 5 se
divide, la división asigna ambas cosas de forma explícita a una de sus partes.

**Hallazgo.** `docs/09-roadmap-y-fases.md` §3 no incluye el módulo `organization` en F0, pero
ADR-0009 exige `institution_id NOT NULL` desde la primera migración y ADR-0017 asigna la tabla raíz
de institución a ese módulo. Se recomienda incorporar el cambio 4, con la entidad raíz mínima, al
alcance de F0, con aprobación explícita del propietario del producto.

## 2. Dónde se resuelve cada punto de diseño abierto

| Punto | Cambio | Recomendación | Alternativa descartada |
|---|---|---|---|
| Prefijo de tabla (ADR-0015, regla 3) | 5 | Prefijo estricto `<módulo>_<entidad>` siempre, contando `shared` como módulo válido: `shared_audit_log`, no `audit_log` | Ampliar el catálogo cerrado de ADR-0017, que contradice su propósito |
| Dueño de la cuenta del estudiante | Se decide ahora, se implementa en F3 | `ledger` es el dueño (`docs/02` §2.8 y §3.5); `payments` la bloquea mediante un caso de uso público de `ledger` | Que `payments` acceda directamente a la tabla |
| ¿`application` llama al componente transaccional? | 5 | Sí, tratándolo como API pública de `shared/security`, con una excepción documentada a la regla `application-no-infrastructure` y comentario del ADR que la autoriza | Un puerto por módulo que solo agrega intermediación |
| Componente transaccional dentro de pruebas | 5 | Dos clases base: una con reversión automática y otra con confirmación real y truncamiento selectivo, necesaria para aislamiento serializable, bloqueo, concurrencia y reintento | Una sola clase base, que invalidaría las pruebas de concurrencia |
| Paquete base y nombres de módulos Maven | 1 | `com.confia`; módulos `kernel` y `app`; artefacto `confia-api` | `hn.edu.confia`, más verboso sin beneficio |
| Nombre de la tabla raíz de institución (ADR-0017) | 4 | `organization_institution`, con seguridad a nivel de fila sobre su propia clave primaria | — |

## 3. Alcance del primer cambio: `maven-workspace-and-ci-skeleton`

**Requisito previo humano.** Crear el repositorio Git, su remoto y la rama del cambio, conforme a la
convención de una rama por cambio de `CLAUDE.md`. Queda como primera tarea del cambio, a cargo del
propietario.

**Dentro de alcance:** POM padre con Java 25 y la lista de materiales de Spring Boot 4.1;
`maven-enforcer-plugin` con las dependencias prohibidas de ADR-0015 y ADR-0016; Maven Wrapper
comprometido al repositorio; módulo `kernel` vacío como frontera de compilación; módulo de
aplicación con los cuatro puntos de entrada seleccionados por `APP_PROFILE`, sin módulos de negocio
todavía; suite de reglas de ArchUnit y la verificación de Spring Modulith; regla estructural que
rechaza paquetes llamados `interface` o con nombre de capa técnica; flujo de integración continua
con `./mvnw verify`; decisión del paquete base y de los nombres de módulo; una prueba trivial en
verde.

**Fuera de alcance:** pnpm, Turborepo y `packages/*` (cambio 3); Flyway, jOOQ y Testcontainers
(cambio 5); `Money` (cambio 2); springdoc y endpoints (cambio 3); Spring Security (cambio 7);
Dockerfiles y Compose (cambio 11); cualquier tabla (cambios 4 y 5).

**Criterios de salida cubiertos:** ninguno por completo. Sienta el mecanismo del criterio 1, que solo
se puede verificar del todo cuando existan al menos dos módulos de negocio.

**Riesgos:**

1. Java 25 y Spring Boot 4.1 son versiones recientes y la compatibilidad de enforcer, ArchUnit y
   Spring Modulith 2 no está verificada. Se mitiga con una prueba de humo antes de construir el
   resto.
2. Unas reglas de ArchUnit que nunca fallaron no prueban nada. Se mitiga introduciendo una violación
   temporal y comprobando que rompe la construcción.
3. La decisión de paquete base y nombres de módulo es de baja reversibilidad y necesita aprobación
   explícita.
4. La creación del remoto en GitHub excede la autoridad de los agentes y la ejecuta el propietario.

## Recomendación

Aprobar la secuencia de trece cambios, empezar por `maven-workspace-and-ci-skeleton` y usar la fase
de propuesta para que el propietario apruebe: incorporar la tabla raíz de institución a F0, las seis
decisiones de diseño de la sección 2, y la estrategia de un solo pull request por cambio.

## Riesgos generales

- El presupuesto de 800 líneas queda ajustado en los cambios 5 y 11; su subdivisión ya está prevista.
- Trece ciclos SDD completos exigen disciplina operativa sostenida de una sola persona.
- `strict_tdd` está en `false` en `openspec/config.yaml` y debe pasar a `true` en el cambio 2, con
  tarea explícita para actualizarlo junto con el umbral de cobertura.

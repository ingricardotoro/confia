# Exploración: raíz de institución y base multi-institución (F0, cambio 4)

- **Cambio:** `institution-root-and-multitenancy-baseline`
- **Fase:** explorar
- **Fecha:** 2026-09-19
- **Estado:** exploración terminada; decisiones D1 y D2 resueltas por el propietario el 2026-09-19
  (ver `proposal.md`, «Resolución del propietario»)
- **Fuente:** transcripción literal de la observación de Engram
  `sdd/institution-root-and-multitenancy-baseline/explore` (#406). El agente de exploración no
  escribe archivos del repositorio; la fase de propuesta escribe este archivo. Se traducen solo los
  encabezados; el cuerpo se conserva tal como se registró.

## Estado actual

No business module exists yet in `apps/api/app` (`com.confia.bootstrap` only). `apps/api/app/pom.xml`
has no jOOQ, Flyway or Testcontainers dependency — confirmed by reading it. Change 1 (Maven reactor,
ArchUnit, enforcer, CI) and change 2 (kernel `Money`, `DomainException`, JaCoCo/PIT gates) are archived
and merged. `openspec/config.yaml`: `strict_tdd: true`, runner `./mvnw verify`, and
`coverage_threshold: 95 # kernel line+branch (JaCoCo); 80 global arrives with change 4` — i.e. change 4
is also the change that must add the global 80% JaCoCo gate (each module/component binds its own
`jacoco-check` execution with its own thresholds, per the existing pattern in `kernel/pom.xml`).

Owner approval confirmed: Engram #335 + `openspec/changes/foundations-plan/exploration.md` — owner
approved (2026-09-15) pulling the institution root table into F0 as change 4, minimal scope, table name
`organization_institution` with RLS on its own PK, base package `com.confia`.

## Áreas afectadas

- `apps/api/app/pom.xml` — no persistence dependencies yet; any real migration/repository needs jOOQ/
  Flyway/Testcontainers, which is change 5's declared scope, not change 4's.
- `apps/api/app/src/test/java/com/confia/architecture/LayeredArchitectureTest.java` — line 74,
  `productionCodeRespectsLayeringYet()`: `ArchRule rule = layeringRule().allowEmptyShould(true);` must
  drop `.allowEmptyShould(true)` the moment ANY production class lands in a `domain`/`application`/
  `infrastructure`/`web` package anywhere in `apps/api/app`. The helper `noBusinessModuleExistsYet`
  (lines 93-107) becomes dead code and should be removed with it.
- `apps/api/app/src/test/java/com/confia/architecture/EmptyShouldExceptionInventoryTest.java` — lines
  25-32, the single `EXCEPTIONS` entry citing `LayeredArchitectureTest::noBusinessModuleExistsYet` must
  be deleted in lockstep with the above.
- `apps/api/app/src/test/java/com/confia/architecture/SuppressionCitesAdrTest.java` — line ~126-130
  asserts `allowEmptyShould(` occurrence count == `EmptyShouldExceptionInventoryTest.EXCEPTIONS.size()`.
  Removing one exception and adding a new one (for the D3 money-primitives rule, see below) must keep
  this count exact or the build breaks.
- `apps/api/app/src/test/java/com/confia/architecture/NoCrossModuleDomainImportsTest.java` — already
  runs `classes().should(...)` (not a per-layer selector) against `productionClasses()` without
  `allowEmptyShould`, because bootstrap classes already make the set non-empty; it passes trivially with
  one module. `openspec/specs/build-integrity/spec.md` (lines 26-32) explicitly defers the *positive*
  "cada módulo usa solo su propio dominio" scenario to change 7 (second module) — change 4 only needs to
  be the first module, not prove the cross-module rule both ways.
- `docs/adr/ADR-0018-conjunto-vacio-en-reglas-de-arquitectura.md` — governs the mechanism above; section
  2 allows a rule to keep a *narrower, updated* exception instead of fully retiring it, as long as the
  condition is rewritten and cites the ADR.
- `docs/adr/ADR-0004-postgresql-y-representacion-monetaria.md` §Cumplimiento 2 — D3 of change 2 assigns
  the ArchUnit rule forbidding `double`/`float` for amounts and `BigDecimal.equals` outside `Money` to
  change 4. This is a brand-new rule, so per ADR-0018 rule 4 it will *also* start life with an empty
  production-code half (no module uses `Money` yet) and needs its **own** new `allowEmptyShould(true)` +
  inventory entry — a second entry, not a replacement of the one being removed.
- `docs/adr/ADR-0009-multitenencia.md`, `docs/adr/ADR-0017-tablas-tecnicas-y-tabla-raiz.md`,
  `docs/adr/ADR-0015-acceso-a-datos-con-jooq.md` — define the target end-state (institution_id NOT NULL
  everywhere, RLS from day one, jOOQ confined to `infrastructure`, table prefix `organization_`).
- `docs/02-modelo-de-dominio.md` §2.2 — `Organization` context: `Institution`, `AcademicYear`,
  `Modality`, `Grade`, `Section`, `BillingPeriod`. Responsibility is institutional structure/calendar
  only; explicitly does not know students or money.
- `docs/01-arquitectura.md` line 184 — confirms module path
  `apps/api/app/src/main/java/com/confia/organization/`.
- No existing `openspec/specs/organization/spec.md` or `openspec/specs/multitenancy/spec.md` capability
  exists yet; this change creates the first one.

## El conflicto de dependencias

`docs/09-roadmap-y-fases.md` and the archived change-1/2 pendientes require change 4 to (a) trigger the
ADR-0018 scheduled red by landing real production code in all four architecture layers, and (b) create
the actual `organization_institution` table with `institution_id`-equivalent RLS per ADR-0009/0017 — but
apps/api/app has no persistence stack, and the wiring that provides one (jOOQ codegen from Flyway via
Testcontainers, the single transactional component) is change 5's declared scope, which itself depends
on change 4. This is a genuine chicken-and-egg in the approved plan, not a hallucinated one.

## Enfoques

1. **Reorder: extract a persistence-bootstrap change before change 4** — promote the plan's already-
   anticipated split of change 5 (`jooq-flyway-testcontainers-wiring` + `audit-log-and-transaction-
   runner`) and run the wiring slice *before* change 4 instead of waiting for change 5's task phase to
   possibly split it.
   - Pros: Change 4 honestly and fully satisfies ADR-0009/0017 from day one — real migration, real RLS
     integration test, a genuinely jOOQ-backed `infrastructure` layer, no deferred gap.
   - Cons: Breaks the owner-approved parallelism (2, 3, 4 run in parallel after 1); adds a 14th change to
     a plan the owner already approved at 13; pulls forward Docker/Testcontainers complexity the plan
     explicitly scoped for change 5's task phase. Needs fresh owner sign-off on the reordering.
   - Effort: Medium-high (a full explore→archive cycle for the new change gates change 4's start).

2. **Change 4 delivers `organization` as domain+application (+ minimal, non-jOOQ infrastructure/web),
   defers the real table to change 5** — `Institution` aggregate/value objects and business rules in
   `domain`; a tenancy-context port in `application`; an `infrastructure` adapter that resolves tenancy
   from the authenticated security context (no DB access, so no jOOQ needed); a minimal `web` class only
   if needed to avoid a fully-empty layer. Update `LayeredArchitectureTest`/`EmptyShouldExceptionInventoryTest`
   per ADR-0018 §2 with a narrower, explicitly-cited condition for whichever layer(s) legitimately stay
   empty, instead of leaving the current all-or-nothing exception in an already-false state.
   - Pros: Preserves the approved dependency graph and parallelism exactly as approved; matches the
     original 6–8 task estimate; no new change, no re-approval of the roadmap's shape.
   - Cons: `organization_institution` does not exist as a real table after change 4 — ADR-0017's
     assignment of the root table to this module is only partially honored. Must be made an explicit,
     owner-visible deferral (change 5's scope description needs an explicit amendment: "also creates
     `organization_institution`'s migration and jOOQ repository using its own wiring"), not a silent gap,
     since docs/09's own wording reads as if change 4 creates the table.
   - Effort: Low-medium, closest to the original estimate.

3. **Change 4 absorbs its own minimal jOOQ/Flyway/Testcontainers bootstrap** — enough plumbing to create
   and prove only its own migration, while change 5 still builds the shared transactional component and
   audit log independently on top of it.
   - Pros: No new change to approve; change 4 stays self-contained and genuinely fulfills ADR-0017.
   - Cons: Materially enlarges change 4 (Docker-based codegen, Flyway plugin config, an RLS isolation
     integration test) well past the 6–8 task estimate, real risk of exceeding the 15-task cap and the
     line budget (400 lines per the SDD preflight; 800 per `docs/15-flujo-de-trabajo-git.md` — these two
     budgets disagree and should be reconciled, see Risks); duplicates plumbing change 5 will also need.
   - Effort: High, budget risk high.

## Recomendación

Approach 2, with the deferral made explicit and written into change 5's scope before change 4's tasks
are drafted. It is the only option that keeps the owner's already-approved dependency graph and
parallelism intact, matches the original size estimate, and only requires an owner decision on wording
(amend change 5's scope note), not a structural re-approval of the 13-change plan. Approach 1 is the
right call only if the owner explicitly prioritizes literal day-one ADR-0009/0017 compliance over the
approved schedule; Approach 3 is not recommended given the budget risk.

This is presented as an owner decision, not resolved unilaterally in this exploration.

## Obligaciones heredadas confirmadas

- **W2** (build-integrity spec, deferred scenario): change 4 only needs to be the first business module;
  the positive "neither module imports the other's domain" scenario is change 7's to prove.
- **ADR-0018 scheduled red**: exact removal targets identified above
  (`LayeredArchitectureTest.java:74` and its helper, `EmptyShouldExceptionInventoryTest.java:25-32`),
  with the `SuppressionCitesAdrTest` count check as a tripwire that must stay balanced.
- **D3 (change 2)**: the new double/float/`BigDecimal.equals` ArchUnit rule is change 4's to add, and it
  will need its *own* new `allowEmptyShould(true)` + inventory entry per ADR-0018, since no module uses
  `Money` yet (organization is calendar/structure, not money) — this is easy to under-scope in tasks.md.
- **Global 80% JaCoCo gate**: `openspec/config.yaml` line 42 already states it "arrives with change 4" —
  a `jacoco-check` execution needs to be bound (pattern already exists in `kernel/pom.xml`), plus the
  organization module's `domain` package needs its own 95% line+branch / 80% PIT mutation thresholds per
  CLAUDE.md.
- **Money follow-ups** (allocate() ratio cap, duplicated Money/Percentage validation, docs/03 BigDecimal-
  scale rule): none of these touch the `organization` module (which is calendar/structure only, not
  money) and should stay deferred to whichever change first manipulates `Money` with untrusted input
  (not yet assigned in the roadmap) or a small dedicated follow-up.

## Estructura del módulo

`apps/api/app/src/main/java/com/confia/organization/{domain,application,infrastructure,web}`, per
CLAUDE.md and `docs/01-arquitectura.md` line 184. This is the first module with the four-layer split
(kernel stays flat by design). First real target for `SpringModulithVerificationTest` and the layering
rule; both currently pass vacuously and will start doing real work.

## Pronóstico de tamaño

Original estimate: 6-8 tasks (foundations-plan, assuming no persistence stack). With approach 2, add:
remove the old ADR-0018 exception, add the D3 rule + its fixture + its own new exception/inventory entry,
bind the global 80% JaCoCo gate, `Institution` domain model + tests at 95%/80% thresholds, tenancy-context
port + non-DB infrastructure adapter. Realistic total ~9-12 tasks, plausibly under both the 15-task cap
and the 400/800-line budgets, but the ArchUnit rewiring (three tightly-coupled test files plus a brand-new
rule) is intricate glue code with real risk of getting the inventory-count invariant wrong under strict
TDD — recommend design.md sequence it as two separate RED→GREEN cycles: (a) land the module, watch the
scheduled red, remove the old exception; (b) add the D3 rule with its own RED, then its own new exception.

## Preguntas abiertas

**Owner decisions:**
1. Which dependency-conflict approach to adopt (1 reorder / 2 defer table to change 5 / 3 absorb wiring).
2. Scope of the "minimal" root entity: `Institution` alone, or also `AcademicYear`/`Modality`/`Grade`/
   `Section`/`BillingPeriod` from docs/02 §2.2 — docs/09's hallazgo says "mínimo" but the module's full
   domain boundary includes the calendar; this materially changes task count.
3. If approach 2 is chosen, whether change 5's scope note gets amended now (before change 4 starts) to
   explicitly carry the `organization_institution` migration/repository responsibility.

**Technical defaults (no owner input needed):**
- Package path and layer split as stated above.
- The D3 rule needs its own fresh ADR-0018 exception — structural necessity, not a choice.
- 95%/80% thresholds on `organization`'s domain package — CLAUDE.md rule, not discretionary.

## Lista para propuesta

Yes, once the owner answers question 1 above (approach) and question 2 (root-entity scope). Question 3
can be resolved inside the proposal/design phase once approach 2 is confirmed.

## Riesgos

- The review-policy line budget in the SDD preflight (400 changed lines) conflicts with
  `docs/15-flujo-de-trabajo-git.md` line 143 ("máximo ochocientas líneas de cambio efectivo"). Not
  specific to this change but will affect its delivery-strategy sizing; should be reconciled once,
  project-wide.
- The exact ArchUnit behavior of `allowEmptyShould` on a composite `layeredArchitecture()` rule versus
  per-layer sub-checks was inferred from the current test code and its own comments, not independently
  executed here (read-only exploration). ADR-0018 itself flags this as "por confirmar durante la
  implementación" against the pinned ArchUnit version — the tasks/apply phase must verify this before
  committing to a specific layer-by-layer exception design.
- If approach 2 is chosen without amending change 5's scope explicitly, a future reader could believe
  ADR-0017's table assignment was already fulfilled by change 4 when it was not — a documentation-honesty
  risk, not just a scheduling one.

---

## Nota de transcripción (fase de propuesta)

El texto anterior es literal salvo los encabezados. Tres afirmaciones se matizan al contrastarlas
con el repositorio y se tratan en `proposal.md`:

1. **Presupuesto de líneas.** El conflicto 400/800 ya está resuelto: el propietario fijó **800**
   líneas por pull request el 2026-09-18 (decisión D2 del cambio 2), y `CLAUDE.md` y
   `docs/15-flujo-de-trabajo-git.md` §3 se alinearon con ese valor.
2. **Adaptador de infraestructura «desde el contexto de seguridad autenticado».** `apps/api/app/pom.xml`
   no declara Spring Security (llega con el cambio 7) y ADR-0009 exige que el identificador de
   institución provenga del token autenticado y nunca del cliente. No existe hoy un adaptador honesto
   sin base de datos para esa resolución; la propuesta no lo incluye.
3. **Excepción propia para la regla de ADR-0004 §Cumplimiento 2.** No toda la regla es vacía: las
   partes que prohíben `new BigDecimal(double)`, `BigDecimal.valueOf(double)` y `BigDecimal.equals`
   fuera de `Money` seleccionan todo el código de producción, que ya no está vacío. Solo la parte que
   prohíbe campos, parámetros y retornos `double`/`float` «en tipos monetarios» puede quedar vacía,
   según cómo se definan esos tipos. Además, ADR-0018 §1.4 solo ampara la vacuidad causada por la
   ausencia de módulos de negocio, y esa causa deja de existir con este cambio.

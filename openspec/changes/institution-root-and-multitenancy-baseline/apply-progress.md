# Apply progress: institution-root-and-multitenancy-baseline

Chained delivery (P1, opción B): PR A → PR B1 → PR B2 → PR C. This file tracks apply-phase
progress across the whole chain; `tasks.md` carries the per-task checkboxes and evidence.

## PR A — Puertas y reglas de ADR-0004, sin módulo de negocio

Branch `change/institution-root-and-multitenancy-baseline`, base `main`. **Status: complete, all
7 tasks done.**

| Task | Status | Commit |
|---|---|---|
| 1.1 Coverage baseline measurement | Done | `e38a004` |
| 1.2 Rule 1 (`NO_BIG_DECIMAL_FROM_FLOATING_POINT`) | Done | `9f86deb` |
| 1.3 Rule 2 (`NO_BIG_DECIMAL_EQUALS_OUTSIDE_MONEY`) | Done | `9a7c637` |
| 1.4 Rules 3-5 (floating-point fields/returns/parameters) | Done | `60e9658` |
| 1.5 JaCoCo `BUNDLE` 80% gate | Done | `c7a1153` |
| 1.6 Real diff measurement | Done | `ea1ec76` |
| 1.7 Final local verification | Done | `b0de1d0` |

### TDD Cycle Evidence (strict TDD, per `openspec/config.yaml`)

| Task | RED | GREEN | REFACTOR | Non-vacuity probe |
|---|---|---|---|---|
| 1.1 | N/A (measurement, not behavior) | N/A | N/A | N/A |
| 1.2 | `cannot find symbol NO_BIG_DECIMAL_FROM_FLOATING_POINT` (compile error) | 2/2 tests pass | None expected/needed | Fixture neutralized (3 calls commented) → rejection test failed alone; reverted, 2/2 green again |
| 1.3 | `cannot find symbol NO_BIG_DECIMAL_EQUALS_OUTSIDE_MONEY` / `NOT_MONEY` (compile error) | 5/5 tests pass | None expected/needed | Fixture switched to `compareTo` → rejection test failed alone (1/5); reverted, 5/5 green again |
| 1.4 | 6 compile errors (three new rule symbols, used twice each) | 12/12 tests pass | None expected/needed | Field, return, and parameter violations neutralized one at a time; each neutralization failed exactly its own rejection test (1/12 each), never the other two; all reverted, 12/12 green |
| 1.5 | N/A (build-integrity wiring, not TDD behavior) | `./mvnw -B -pl app -am verify` green | N/A | Threshold temporarily raised to 0.95 → `Rule violated for bundle confia-api: lines covered ratio is 0.89 / branches covered ratio is 0.84, but expected minimum is 0.95`; reverted to 0.80, green again |
| 1.6 | N/A (measurement) | N/A | N/A | N/A |
| 1.7 | N/A (verification) | Full `./mvnw -B verify` and `./mvnw -B verify -Pmutation-gate` green | N/A | N/A |

### Work Unit Evidence

| Evidence | Value |
|---|---|
| Focused test command and result | `./mvnw -B -pl app -am test -Dtest=MonetaryFloatingPointTest -Dsurefire.failIfNoSpecifiedTests=false` → 12/12 pass |
| Runtime harness command/scenario and result | `./mvnw -B verify -Pmutation-gate` in `apps/api` → BUILD SUCCESS; 172 kernel tests + 43 app tests; JaCoCo `BUNDLE` gate on `app` green (89.7% line / 84.6% branch, both ≥80%); kernel PIT 178 mutations, 176 killed (99%, pre-existing, untouched by this PR) |
| Rollback boundary | Revert commits `e38a004`..`b0de1d0` (or the whole PR); `apps/api/app/pom.xml` and `openspec/config.yaml` return to their change-3 state; no other module depends on this PR |

### Final measurements

- Coverage baseline (task 1.1): `app` with only `com.confia.bootstrap` — lines 35/39 = 89.7%,
  branches 11/13 = 84.6%. Both above 80%, so the `BUNDLE` gate was declared in PR A (not deferred
  to PR B1).
- Real diff (task 1.6, re-confirmed at 1.7 close): `git diff --numstat
  main...change/institution-root-and-multitenancy-baseline -- . ':(exclude)openspec'
  ':(exclude)docs/adr'` → 310 additions + 3 deletions = **313 authored lines**, within
  `design.md`'s 232–334 forecast and far under the 800-line budget (`docs/15-flujo-de-trabajo-git.md`
  §3, P1).
- Final `./mvnw -B verify -Pmutation-gate` in `apps/api`: **BUILD SUCCESS**.

### Deviations from design

None. Design.md's pessimistic assumption that app-only-bootstrap branch coverage might fall short
of 80% (making the `BUNDLE` gate move to PR B1) did not hold: both line and branch coverage cleared
80%, so the gate is declared here as design.md's decision 9 default path.

### Outstanding for this apply batch

Push `change/institution-root-and-multitenancy-baseline` and confirm the `backend` CI job is green
— explicitly deferred to the orchestrator per this phase's launch instructions (sdd-apply does not
push or open PRs).

## PR B1 — `InstitutionId`, `Institution` mínima, ciclo de ADR-0018/ADR-0020, puertas del `domain`

Branch `change/institution-root-and-multitenancy-baseline-domain`, base PR A (`e3b9e84`).
**Status: BLOCKED at task 2.9 on 2026-09-19, size overage — reported to the owner, not decided
here.** The prior blocker (task 2.4 vs ADR-0020) was resolved by the owner moving the
`application`-layer ports from task 4.1 (PR C) into this PR as task 2.3b. Tasks 2.1–2.8 done and
committed; task 2.9's own measurement now exceeds the 800-line PR budget (933 lines), which is one
of this apply batch's explicit hard stops. Task 2.10 has not started and must not start until the
owner decides how to proceed (see "Blocker" below).

| Task | Status | Commit |
|---|---|---|
| 2.1 `InstitutionId` in kernel | Done | `32d5370` |
| 2.2 Minimal `Institution` (id, legalName, tradeName, isActive) | Done | `43d1c01` |
| 2.3 Close ADR-0018's common expiry (empty inventory, rename rule) | Done | `e2ac46e` |
| 2.3b `application` ports (`InstitutionRepository`, `CurrentInstitutionProvider`), moved forward from task 4.1 | Done | `c940a22` |
| 2.4 Apply ADR-0020 (`optionalLayer` for Infrastructure/Web) | Done | `a26d685` |
| 2.5 Activate/deactivate lifecycle, identity equality | Done | `0fe434e` |
| 2.6 `OrganizationErrorCodesTest` (six codes) | Done | `545e546` |
| 2.7 Domain quality gates (`PACKAGE` 95%, PIT adhesion) | Done | `7eb560a` |
| 2.8 Gate-failure demonstrations (not committed by design) | Done | `0d92ef7` |
| 2.9 Measure real PR B1 diff | **Blocked — 933 > 800 lines** | not committed |
| 2.10 | Not started (blocked by 2.9) | — |

### TDD Cycle Evidence

| Task | RED | GREEN | REFACTOR | Non-vacuity / scheduled-red probe |
|---|---|---|---|---|
| 2.1 | `cannot find symbol class InstitutionId` (10 compile errors) | 4/4 tests pass | None expected/needed | N/A (record, no branching logic to neutralize) |
| 2.2 | `cannot find symbol class Institution` / `InvalidInstitutionException` (compile errors) | 11/11 tests pass | None expected/needed | Full `./mvnw -B verify`: scheduled ADR-0018 red observed in `EmptyShouldExceptionInventoryTest`, exact message recorded in `tasks.md`; `LayeredArchitectureTest` still green (still carries `allowEmptyShould(true)`) |
| 2.3 | N/A (this task closes a scheduled red, it does not add new behavior) | `EmptyShouldExceptionInventoryTest` + `SuppressionCitesAdrTest` green (9/9, count 0==0) | None expected/needed | `productionCodeRespectsLayering` run in isolation fails as predicted by design.md's probe outcome (b), naming `Application`, `Infrastructure` and `Web` as empty — one layer more than the probe's own sample tested (see Blocker, now resolved) |
| 2.3b | N/A — interfaces only, no RED/GREEN cycle (explicitly documented in tasks.md, matching the task's own "sin ciclo ROJO/VERDE propio") | `./mvnw -B -pl app -am test -Dtest=InstitutionCreationTest -Dsurefire.failIfNoSpecifiedTests=false` → 11/11, no regression | None expected/needed | `productionCodeRespectsLayering` run in isolation now names only `Infrastructure` and `Web` as empty, no longer `Application` — confirms the two ports resolved the blocker before task 2.4 touches `optionalLayer` |
| 2.4 | Compile error: `cannot find symbol method noProductionClassInLayer(JavaClasses,String) / location: class LayeredArchitectureTest` (2 occurrences), after adding the `OPTIONAL_LAYER` marker entries to `EmptyShouldExceptionInventoryTest` | `LayeredArchitectureTest`/`EmptyShouldExceptionInventoryTest`/`SuppressionCitesAdrTest` 13/13 green; `SuppressionCitesAdrTest` confirms 2==2 `.optionalLayer(` and 0==0 `.allowEmptyShould(` | None expected/needed | Expiry demonstrated: temporary `TempExpiryProbe` class in `organization.infrastructure` made `EmptyShouldExceptionInventoryTest` fail naming the exact `Infrastructure` entry; removed, `git status` clean |
| 2.5 | `cannot find symbol method deactivate()/activate()` and `cannot find symbol class InstitutionStateException` (compile error) | 16/16 pass (`InstitutionLifecycleTest` 5/5 + `InstitutionCreationTest` 11/11, no regression) | None expected/needed | N/A (no branching logic beyond the state guard, already exercised by the four transition tests) |
| 2.6 | N/A — catalog test over codes already implemented in tasks 2.2/2.5, mirrors `KernelErrorCodesTest`'s own pattern of no red when the catalog pre-exists | 5/5 pass immediately | None expected/needed | N/A (catalog assertion, no branching logic to neutralize) |
| 2.7 | N/A (build-integrity wiring); first smoke run failed for real: `branches covered ratio is 0.85, but expected minimum is 0.95` on `organization.domain`, from untested `Institution.equals`/`hashCode` branches | `./mvnw -B -pl app -am verify -Pmutation-report` BUILD SUCCESS after closing the gap; PIT 32/32 mutations killed (100%, informational on this branch) | None expected/needed | Closed by adding the missing equality/hash edge-case tests, not by touching production logic or thresholds |
| 2.8 | N/A (demonstration task, not a production behavior) | (a) `@Disabled` on `InstitutionLifecycleTest` → `PACKAGE` rule breaks (0.69/0.42 vs 0.95); reverted. (b) `-Dconfia.pit.mutationThreshold=101` → `-Pmutation-gate` breaks ("Mutation score of 100 is below threshold of 101"), `-Pmutation-report` stays green with the identical override | None expected/needed | No file left modified in either demonstration; (b) used the command-line threshold-override technique already established in `kernel-money-value-object` task 1.13, since directly weakening `Institution`'s validation breaks real tests before PIT even runs |

### Work Unit Evidence (through task 2.3)

| Evidence | Value |
|---|---|
| Focused test command and result | `./mvnw -B -pl app -am test -Dtest=InstitutionCreationTest,OrganizationErrorCodesTest -Dsurefire.failIfNoSpecifiedTests=false` → `InstitutionCreationTest` 11/11 pass; `OrganizationErrorCodesTest` does not exist yet (task 2.6) |
| Runtime harness command/scenario and result | `./mvnw -B verify -Pmutation-report` in `apps/api` not yet run to completion for PR B1: blocked before task 2.7 wires the domain quality gates. Full `./mvnw -B verify` currently **fails** (`productionCodeRespectsLayering`), which is the expected pre-2.4 state |
| Rollback boundary | Revert commits `32d5370`..`e2ac46e` (or the whole PR branch); `organization` module disappears, ADR-0018's exception returns with its original condition (which holds again), PR A stays intact and complete by itself |

### Blocker found while attempting task 2.4 — RESOLVED by the owner on 2026-09-19

**Resolution.** The owner chose to move the `application`-layer ports (`InstitutionRepository`,
`CurrentInstitutionProvider`) forward from task 4.1 (PR C) into this PR as new task 2.3b, rather
than amend ADR-0020 or accept a red `productionCodeRespectsLayering` on PR B1/B2 (option (b) of the
three requested below, closest in spirit — a minimal, real `application`-layer class landing the
layering rule's precondition earlier than task 4.1). `tasks.md` now contains task 2.3b, completed
above; task 4.1 in PR C is reduced to the `ResolveCurrentInstitution` use case, which reuses these
same two ports without recreating them. The original blocker text is preserved below for record.

### Original blocker report (kept for history; not re-litigated)

**What.** `design.md`'s P2 probe ("Sonda de P2") validated `optionalLayer("Infrastructure")` /
`optionalLayer("Web")` against a synthetic sample where **both** `domain` and `application` already
had production classes. ADR-0020 §2 codifies that finding and explicitly keeps `Domain` and
`Application` **always mandatory** ("Domain y Application son siempre obligatorias"). In the real
sequencing of this change, `application`'s only classes (`InstitutionRepository`,
`CurrentInstitutionProvider`, `ResolveCurrentInstitution`) are assigned to **PR C, task 4.1** — they
do not exist in PR B1 or PR B2. I implemented task 2.4's exact code (production/fixture rule split,
`optionalLayer` on `Infrastructure` and `Web` only, per design.md's literal contract) in a scratch
edit and ran `LayeredArchitectureTest` in isolation to confirm empirically before reporting:

```
Layer 'Application' is empty
```

`productionCodeRespectsLayering` still fails, because `Application` remains a mandatory `.layer(...)`
(never `.optionalLayer(...)`) and has zero production classes until PR C. I reverted the scratch edit
(`git checkout --`) so the tree stays at commit `e2ac46e`; nothing from this probe is committed.

**Why this blocks, rather than something I should decide.** This is exactly the class of thing the
launch instructions call a hard stop: "any contradiction between tasks/design/spec; any need to
weaken a gate or add an unplanned exception." Two closed decisions collide:
- P1 (proposal, owner-approved 2026-09-19): split delivery into PR B1 → PR B2 → PR C, with
  `application`'s ports and use case explicitly scoped to PR C alone.
- ADR-0020 (accepted 2026-09-19): `Application` is always mandatory in the layering rule, with no
  optionality mechanism for it.

Together they mean `./mvnw verify`'s `productionCodeRespectsLayering` **cannot pass** in PR B1 or PR
B2 as currently scoped — task 2.10's and task 3.10's "confirm `productionCodeRespectsLayering`
passes with `Infrastructure`/`Web` optional" cannot be satisfied, and pushing either branch would
never turn the CI `backend` job green, contradicting both tasks' own final-verification step.

**Not decided unilaterally (would each be an unplanned exception or a gate weakening):**
1. Extending `optionalLayer` to `Application` too, mirroring Infrastructure/Web — directly
   contradicts ADR-0020 §2's explicit text.
2. Reviving `allowEmptyShould(true)` for the whole rule — exactly what ADR-0018 §2/§3 closed, and
   would re-silence the now-real `Domain` layer too.
3. Moving some `application`-layer stub into PR B1/B2 ahead of task 4.1 — contradicts the tasks.md
   PR boundary and the proposal's explicit per-cut assignment.

**Requesting a decision on one of:** (a) a new ADR amending or complementing ADR-0020 to also treat
`Application` as optional until PR C, with its own expiry condition and inventory entry, cited
alongside ADR-0020; (b) resequencing so PR B1 (or B2) includes a minimal, real `application`-layer
class landing the layering rule's precondition earlier than task 4.1; (c) accepting that
`./mvnw verify` — and therefore CI's `backend` job — stays red on the `...-domain` branch (and
`...-domain-attributes`) until PR C merges, and adjusting tasks 2.10/3.10's success criteria
accordingly; or another option the owner prefers. This is the same class of decision as the original
P2 (elevated to an ADR, not buried in `design.md`), so it is reported rather than resolved here.

### Blocker found at task 2.9 (not committed; reported instead of improvised)

**What.** Task 2.9's own measurement, `git diff --numstat e3b9e84...HEAD -- .
':(exclude)openspec' ':(exclude)docs/adr'` (base confirmed with `git merge-base
change/institution-root-and-multitenancy-baseline-domain change/institution-root-and-multitenancy-baseline`
→ `e3b9e84`), gives **933 authored lines** (878 additions + 55 deletions across 16 files — full
per-file breakdown in `tasks.md`, task 2.9's own evidence). Task 2.9's text is explicit: "Si supera
800, detener la aplicación y consultar al propietario entre una subdivisión adicional dentro de B1
o una excepción de tamaño; no decidirlo sin el propietario." This apply batch's launch instructions
independently name the same threshold as a hard stop.

**Why this blocks, rather than something I should decide.** 933 exceeds the project's 800-line
per-PR budget (`CLAUDE.md`, `docs/15-flujo-de-trabajo-git.md` §3, the P1 decision this change
already made) by 133 lines — 17% over. It is inside `design.md`'s own pessimistic total for the
whole of corte B (710–1045) but above the B1-specific sub-estimate the owner's P1 decision was
based on (480–700), mainly because ADR-0020's cycle (tasks 2.3b and 2.4: two new ports plus the
`LayeredArchitectureTest`/`EmptyShouldExceptionInventoryTest`/`SuppressionCitesAdrTest` rework) and
`Institution`'s equals/hashCode edge-case tests (task 2.7's coverage-gap closure) were not fully
accounted for in that sub-estimate at the time it was written.

**Not decided unilaterally:**
1. Accepting `933 > 800` as `size:exception` for this specific PR — the delivery strategy is
   `auto-chain`/`stacked-to-main`, decided by the owner (P1), and unilaterally granting an exception
   to it is exactly the kind of gate-widening this apply batch's hard stops forbid.
2. Splitting PR B1 further (for example, isolating tasks 2.3b/2.4's ADR-0020 rework into its own
   PR ahead of `InstitutionId`/`Institution`, or moving `InstitutionLifecycleTest`'s equals/hashCode
   closure elsewhere) — changes the tasks.md PR boundary the owner already approved and would need
   its own re-sequencing decision, not something to improvise mid-batch.
3. Continuing into task 2.10 (final verification, push, CI confirmation) while the size question is
   open — task 2.10's own text is gated on 2.9 "si cabe", so proceeding would silently treat the
   overage as already resolved.

**Requesting a decision on one of:** (a) accept `size:exception` for this PR B1 as currently
scoped (933 lines), matching the precedent already used for PR A of `kernel-money-value-object`;
(b) split PR B1 further, and specify where the new boundary falls (for example, ADR-0020's cycle as
its own preliminary PR, or `InstitutionLifecycleTest`'s coverage-closure tests moved elsewhere);
(c) another option the owner prefers. Diagnostic evidence for whichever path is chosen — full
`./mvnw -B verify -Pmutation-gate` output, test counts, both JaCoCo rules and every PIT survivor —
is included in this apply run's return summary regardless, so the owner has complete information
without needing another apply pass just to gather it.

## PR B2 — Atributos restantes del agregado (`rtn`, `address`, `defaultCurrency`, `locale`, `timezone`)

Branch `change/institution-root-and-multitenancy-baseline-domain-attributes`, base PR B1-gates
(`ac68c2b`). **Status: complete, all 10 tasks done (3.1–3.10).** The owner's PR B1 split (task
2.9's blocker) resolved cleanly before this batch started: PR B1 (`...-domain`, `InstitutionId`,
`Institution` with lifecycle, application ports, ADR-0020) and PR B1-gates (`...-domain-gates`,
six-code catalog + domain gates) are both pushed and green; this PR B2 builds on PR B1-gates.

| Task | Status | Commit |
|---|---|---|
| 3.1 `rtn` attribute | Done | `8e980bd` |
| 3.2 `address` attribute | Done | `0785bb1` |
| 3.3 `defaultCurrency` attribute | Done | `9c33460` |
| 3.4 `locale` attribute | Done | `e2c9f30` |
| 3.5 `timezone` attribute (signature complete, 8 params) | Done | `6ef3a70` |
| 3.6 `tradeName` validation (verified, already done in PR B1 task 2.2) | Done | `c58332d` |
| 3.7 Invariant precedence test | Done | `dc88045` |
| 3.8 `toString()` without rtn/address | Done | `f36e468` |
| 3.9 `OrganizationErrorCodesTest` to eleven codes | Done | `6887422` |
| 3.10 Real diff measurement + final verification | Done | not yet committed (docs-only, this save) |

### TDD Cycle Evidence

| Task | RED | GREEN | REFACTOR | Non-vacuity / notes |
|---|---|---|---|---|
| 3.1 | Compile errors: `create` of 3 params doesn't apply to 4-param calls; `cannot find symbol RTN_INVALID`/`rtn()` | `InstitutionCreationTest` 15/15, `InstitutionLifecycleTest` 8/8, `OrganizationErrorCodesTest` 5/5 | None needed | N/A |
| 3.2 | Compile errors: `create` of 4 params doesn't apply to 5-param calls; `cannot find symbol address()` | `InstitutionCreationTest` 20/20, others unchanged | Generalized `requireValidName` → `requireValidText(rawText, maxLength, blankError, tooLongError)` for reuse across `legalName`/`tradeName`/`address` | N/A |
| 3.3 | Compile errors: `create` of 5 params doesn't apply to 6-param calls; `cannot find symbol defaultCurrency()` | `InstitutionCreationTest` 22/22, others unchanged | None needed | N/A |
| 3.4 | Compile errors: `create` of 6 params doesn't apply to 7-param calls; `cannot find symbol locale()` | `InstitutionCreationTest` 24/24, others unchanged | None needed | N/A |
| 3.5 | Compile errors: `create` of 7 params doesn't apply to 8-param calls; `cannot find symbol timezone()` | `InstitutionCreationTest` 27/27, others unchanged; signature now the final 8 params | None needed | N/A |
| 3.6 | N/A — already implemented in PR B1 task 2.2 (deliberate deviation recorded there) | `InstitutionCreationTest` 27/27 (verification run) | None needed | Verified instead of redone, per this batch's launch instructions |
| 3.7 | N/A — precedence already matched design.md's order from incremental 3.1–3.5 construction, so the new parameterized test passed immediately (30/30) | 30/30 | None needed | **Non-vacuity probe**: temporarily swapped the `rtn`/`timezone` check order in `Institution.create()`; re-running only the precedence test failed exactly the rtn+timezone case (1/3, received `institution-timezone-invalid` instead of `institution-rtn-invalid`); reverted, `git diff` on the production file empty, 43/43 tests green again |
| 3.8 | `toStringShowsIdAndTradeNameButNeverRtnOrAddress` failed: expected `"Institution[id=..., tradeName=...]"`, got default `Object.toString()` (`com.confia...Institution@...`) | `InstitutionLifecycleTest` 9/9, `InstitutionCreationTest` 30/30 (no regression) | None needed | N/A |
| 3.9 | N/A — five new codes already reachable from production since 3.1–3.5 (mirrors PR B1 task 2.6's own no-red pattern) | `OrganizationErrorCodesTest` 5/5, 44/44 total | None needed | N/A |
| 3.10 | N/A (measurement + verification, not behavior) | Full `./mvnw -B verify -Pmutation-gate` BUILD SUCCESS | N/A | N/A |

### Work Unit Evidence

| Evidence | Value |
|---|---|
| Focused test command and result | `./mvnw -B -pl app -am test -Dtest=InstitutionCreationTest,InstitutionLifecycleTest,OrganizationErrorCodesTest -Dsurefire.failIfNoSpecifiedTests=false` → 44/44 pass (30 + 9 + 5) |
| Runtime harness command/scenario and result | `./mvnw -B verify -Pmutation-gate` in `apps/api` → BUILD SUCCESS; 176 kernel tests + 89 app tests = 265 total; JaCoCo `PACKAGE` on `com.confia.organization.domain` at 100% lines/branches (75/75, 20/20); JaCoCo `BUNDLE` on `app` at 96.5% lines / 93.9% branches; PIT on `organization.domain` 49/49 mutations killed (100%, zero survivors); kernel's pre-existing 2 survivors (`ConditionalsBoundaryMutator`, 99% score) untouched by this PR |
| Rollback boundary | Revert commits `8e980bd`..the final docs commit of this batch (or the whole PR branch); `Institution.create()` returns to PR B1's three-parameter signature (`id`, `legalName`, `tradeName`), `rtn`/`address`/`defaultCurrency`/`locale`/`timezone` disappear, no consumer outside the module is affected (design.md, "Pronóstico de tamaño por corte") |

### Real diff measurement (task 3.10)

`git diff --numstat ac68c2b...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr'` (base confirmed
with `git merge-base`, tip of PR B1-gates) → 5 files, **509 authored lines** (466 additions + 43
deletions): `Institution.java` 100+/16-, `InvalidInstitutionException.java` 46+/0-,
`InstitutionCreationTest.java` 260+/17-, `InstitutionLifecycleTest.java` 36+/3-,
`OrganizationErrorCodesTest.java` 24+/7-. Above design.md's 230–345 forecast for B2 (same
under-estimation pattern as B1) but far under the 800-line budget — continued without consulting
the owner, per this task's own instructions.

### Outstanding for this apply batch

Push `change/institution-root-and-multitenancy-baseline-domain-attributes` and confirm the
`backend` CI job is green — explicitly deferred to the orchestrator per this phase's launch
instructions (sdd-apply does not push or open PRs). PR C (tasks 4.1–4.4) is out of this batch's
scope (PR B2 only, stop after 3.10, per this apply batch's explicit instruction) and remains
`[ ]` in `tasks.md`.

## PR C — Capa `application`: puertos y caso de uso

Branch `change/institution-root-and-multitenancy-baseline-application`, base PR B2 (`a6c1bb7`).
**Status: complete, all 4 tasks done (4.1–4.4). This is the final PR of the whole change.** The
`InstitutionRepository` and `CurrentInstitutionProvider` ports were already delivered in PR B1
(task 2.3b, owner decision 2026-09-19), so this batch's task 4.1 covered only the
`ResolveCurrentInstitution` use case and its test doubles, exactly as this batch's launch
instructions scoped it.

| Task | Status | Commit |
|---|---|---|
| 4.1 `ResolveCurrentInstitution`, active case | Done | `890ceec` |
| 4.2 `InstitutionNotFoundException`/`InstitutionInactiveException`, rejection paths, catalog to 13 codes | Done | `67ea727` |
| 4.3 Real diff measurement | Done | `1a189c4` |
| 4.4 Final verification and change closure review | Done | `1a189c4` |

### TDD Cycle Evidence

| Task | RED | GREEN | REFACTOR | Non-vacuity / notes |
|---|---|---|---|---|
| 4.1 | Compile errors: `cannot find symbol class ResolveCurrentInstitution` (4 occurrences) | `ResolveCurrentInstitutionTest` 5/5 (repository double register/lookup, fixed provider, use case happy path, two constructor null checks); full `app` test run 94/94, no regression | None needed | N/A (minimal implementation used `Optional::orElseThrow()` with no arguments, deliberately incomplete pending task 4.2's own red) |
| 4.2 | Compile errors: `cannot find symbol class InstitutionNotFoundException` / `InstitutionInactiveException` (9 occurrences, from the two new test methods) | `ResolveCurrentInstitutionTest` 8/8 (+3: repository-double absence for two distinct ids without throwing, not-found rejection, inactive rejection); `OrganizationErrorCodesTest` 6/6 (+1: dedicated public-no-arg-constructor confirmation for both new exceptions) | None needed | N/A (both new exceptions are simple constructors with no branching to neutralize; behavior verified through the use case's own rejection tests instead) |
| 4.3 | N/A (measurement, not behavior) | N/A | N/A | N/A |
| 4.4 | N/A (verification, not behavior) | Full `./mvnw -B verify` and `./mvnw -B verify -Pmutation-report` both BUILD SUCCESS | N/A | N/A |

### Work Unit Evidence

| Evidence | Value |
|---|---|
| Focused test command and result | `./mvnw -B -pl app -am test -Dtest=ResolveCurrentInstitutionTest,OrganizationErrorCodesTest -Dsurefire.failIfNoSpecifiedTests=false` → 8/8 + 6/6 = 14/14 pass |
| Runtime harness command/scenario and result | `./mvnw -B verify -Pmutation-gate` in `apps/api` → BUILD SUCCESS; 176 kernel tests + 98 app tests = 274 total; JaCoCo `PACKAGE` on `com.confia.organization.domain` at 100% lines/branches (79/79, 20/20); JaCoCo `BUNDLE` on `app` green ("All coverage checks have been met"); PIT on `organization.domain` 49/49 mutations killed (100%, zero survivors, including the two new exception classes); `organization.application` (where `ResolveCurrentInstitution` lives) is fully covered by JaCoCo but correctly outside the `PACKAGE` rule's `<includes>` (`com.confia.*.domain`, `com.confia.*.domain.*`) and outside PIT's `targetClasses`/`targetTests`; kernel's pre-existing 2 survivors (`ConditionalsBoundaryMutator`, 99% score) untouched by this PR |
| Rollback boundary | Revert commits `890ceec`, `67ea727`, `1a189c4` (or the whole PR branch); `organization` module loses its `application`-layer use case and its two resolution exceptions, returning to PR B2's state (ports exist from PR B1 but unused); no consumer outside the module is affected (no `web` layer exists yet) |

### Real diff measurement (task 4.3)

`git diff --numstat a6c1bb7...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr'` (base confirmed
with `git merge-base`, tip of PR B2) → 7 files, **300 authored lines** (292 additions + 8
deletions): `ResolveCurrentInstitution.java` 46+/0-, `InstitutionInactiveException.java` 25+/0-,
`InstitutionNotFoundException.java` 25+/0-, `FixedCurrentInstitutionProvider.java` 22+/0-,
`InMemoryInstitutionRepository.java` 29+/0-, `ResolveCurrentInstitutionTest.java` 119+/0-,
`OrganizationErrorCodesTest.java` 26+/8-. Slightly above design.md's 185–290 forecast for C (same
mild under-estimation pattern as B1/B2, only 10 lines over the ceiling) but far under the 800-line
budget — continued without consulting the owner, per this task's own instructions.

### Success criteria review (task 4.4)

All nine of `proposal.md`'s "Criterios de éxito" reviewed against the final reactor state; full
per-criterion evidence recorded in `tasks.md` task 4.4. Summary: eight of nine fully verified
locally in this apply run (ArchUnit rules, coverage/mutation gates, domain exception catalog,
resolution use case rejections, D1's exploration.md note); the ninth (`./mvnw verify` green on
remote CI with JDK 25) is verified locally but its remote confirmation depends on pushing the
branch, explicitly out of `sdd-apply`'s scope.

### Outstanding for this apply batch

Push `change/institution-root-and-multitenancy-baseline-application` and confirm the `backend` CI
job is green — explicitly deferred to the orchestrator per this phase's launch instructions
(`sdd-apply` does not push or open PRs). This was also PR B1's and PR B2's outstanding item; all
three branches (`...-domain`, `...-domain-gates`, `...-domain-attributes`) are already pushed and
green per the launch prompt's chain-state note, so only this final `...-application` branch remains
to push.

## Whole-change status

**Every task in `tasks.md` across all four pull requests (A, B1, B1-gates, B2, C) is now marked
`[x]`.** This was the last apply batch of `institution-root-and-multitenancy-baseline`. Remaining
work is entirely the orchestrator's: push `...-application`, confirm CI, and open/merge the final
pull request in the `stacked-to-main` chain.

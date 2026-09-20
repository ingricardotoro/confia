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
**Status: resumed 2026-09-19, blocker resolved by the owner.** The owner decided to move the
`application`-layer ports from task 4.1 (PR C) into this PR as task 2.3b, rather than amend
ADR-0020 or accept a red `productionCodeRespectsLayering`. Tasks 2.1–2.3b done and committed;
2.4–2.10 in progress.

| Task | Status | Commit |
|---|---|---|
| 2.1 `InstitutionId` in kernel | Done | `32d5370` |
| 2.2 Minimal `Institution` (id, legalName, tradeName, isActive) | Done | `43d1c01` |
| 2.3 Close ADR-0018's common expiry (empty inventory, rename rule) | Done | `e2ac46e` |
| 2.3b `application` ports (`InstitutionRepository`, `CurrentInstitutionProvider`), moved forward from task 4.1 | Done | `c940a22` |
| 2.4 Apply ADR-0020 (`optionalLayer` for Infrastructure/Web) | Done | `a26d685` |
| 2.5 Activate/deactivate lifecycle, identity equality | Done | `0fe434e` |
| 2.6 `OrganizationErrorCodesTest` (six codes) | Done | pending commit this batch |
| 2.7–2.10 | Not started | — |

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

## PR B2, PR C

Not started. Tasks 3.1–4.4 in `tasks.md` remain `[ ]`. Each PR's branch, base, and scope are
described in `tasks.md`'s section headers and `design.md`'s "Secuencia de implementación con TDD
estricto". PR B2 and PR C both inherit the same blocker above until it is resolved, since both stay
on top of PR B1's still-mandatory, still-empty `Application` layer.

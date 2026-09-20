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

## PR B1, PR B2, PR C

Not started. Tasks 2.1–4.4 in `tasks.md` remain `[ ]`. Each PR's branch, base, and scope are
described in `tasks.md`'s section headers and `design.md`'s "Secuencia de implementación con TDD
estricto".

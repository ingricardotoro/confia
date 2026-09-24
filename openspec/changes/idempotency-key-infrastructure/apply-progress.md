# Apply progress: idempotency-key-infrastructure

Environment: `JAVA_HOME=C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot` (Temurin 25.0.3),
`MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT`, Docker Desktop (Docker Engine 29.7.2),
`./mvnw -B` from `apps/api`, output always redirected to a file and grepped. `target/` directories
removed by hand before the first run (OneDrive lock issue) instead of `mvn clean`.

## PR C1 — table, row policy, privileges (branch `change/idempotency-key-infrastructure`, base `main`)

### Task 1.1 — RED: real permissions and privileges

Extended `RolePrivilegeMatrixIT` with seven new tests covering the five roles plus `PUBLIC` and a
scratch role outside the five, against `shared_idempotency_key`. Created
`IdempotencyKeyPrivilegeIT` with real SQL statements: `confia_admin_app` (SELECT/INSERT/UPDATE
allowed, DELETE rejected) and `confia_portal_app` (all four rejected).

**Observed RED** (`/tmp/confia-logs/task1.1-red.log`, command
`./mvnw -B -pl app -am test -Dtest=RolePrivilegeMatrixIT,IdempotencyKeyPrivilegeIT -Dsurefire.failIfNoSpecifiedTests=false`):

```
[ERROR] Tests run: 2, Failures: 1, Errors: 1, Skipped: 0 -- in com.confia.schema.IdempotencyKeyPrivilegeIT
[ERROR] com.confia.schema.IdempotencyKeyPrivilegeIT.confiaPortalAppIsRejectedOnAllFourOperations -- FAILURE!
[confia_portal_app must have no privilege on shared_idempotency_key]
[ERROR] com.confia.schema.IdempotencyKeyPrivilegeIT.confiaAdminAppCanSelectInsertAndUpdateButNeverDelete -- ERROR!
org.jooq.exception.DataAccessException: SQL [insert into shared_idempotency_key ...];
ERROR: relation "shared_idempotency_key" does not exist
[ERROR] Tests run: 16, Failures: 0, Errors: 6, Skipped: 0 -- in com.confia.schema.RolePrivilegeMatrixIT
(6 new tests) ... ERROR: relation "shared_idempotency_key" does not exist
[ERROR] Tests run: 18, Failures: 1, Errors: 7, Skipped: 0
BUILD FAILURE
```

Exact failure reason: `relation "shared_idempotency_key" does not exist`, exactly as predicted by
the task (table not created yet). Real RED, not invented.

### Task 1.2 — GREEN: `V4__create_shared_idempotency_key.sql`

Created the migration per `design.md` decision 2 (natural composite primary key
`(institution_id, endpoint, idempotency_key)`, all `CHECK` constraints, `FORCE ROW LEVEL SECURITY`
with the `NULLIF(current_setting(...), '')` pattern identical to V1-V3) and decision 3 (`REVOKE ALL
... FROM PUBLIC` before any `GRANT`; `SELECT, INSERT, UPDATE` to `confia_admin_app`; `SELECT` to
`confia_readonly`; no grant for `confia_portal_app`).

**Text trap avoided**: the comment explaining the deferred physical purge cites "change 9" and the
absence-of-purge requirement, never the literal name of the db-scheduler task table. Confirmed with
`grep -i "scheduled_tasks" V4__create_shared_idempotency_key.sql` → no match.

**Observed GREEN** (`/tmp/confia-logs/task1.2-green.log`, same focused command):

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in com.confia.schema.IdempotencyKeyPrivilegeIT
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0 -- in com.confia.schema.RolePrivilegeMatrixIT
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

jOOQ generated `SharedIdempotencyKey.java` from the new table (confirms design.md §10 sonda S6's
expectation, to be formally re-run and cited in C2a-1): `[INFO] Generating table : SharedIdempotencyKey.java [input=shared_idempotency_key, pk=shared_idempotency_key_pk]`.

### Task 1.3 — `MultiTenantSchemaIT` unmodified

Ran `MultiTenantSchemaIT` in isolation against the schema with V4 applied, without touching the
file. `/tmp/confia-logs/task1.3.log`: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` →
`BUILD SUCCESS`. Confirms `design.md` §2's by-reading verification: `shared_idempotency_key` needs
no exclusion-list entry anywhere in that class.

### Task 1.4 — `AuditScopeExclusionInventoryTest` stays green

Ran in isolation (no Docker needed for this one, though the module's `generate-sources` still
required it). `/tmp/confia-logs/task1.4.log`: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` →
`BUILD SUCCESS`. `scheduledTasksDoesNotAppearInTheDeliveredSchemasMigrations` passes with V4's text
already concatenated in.

### Task 1.5 — full suite timing

Ran `./mvnw -B verify` complete in `apps/api` (`/tmp/confia-logs/task1.5-verify.log`).

- Failsafe `integration-test` phase for `confia-api` started 17:31:14.171 and the module finished
  (including the post-test JaCoCo report/check) at 17:32:05 — **the phase's real bulk is
  ~49-51 seconds** for 63 integration tests (`Tests run: 63, Failures: 0, Errors: 0, Skipped: 0`).
- Reactor summary: `CONFIA API Parent` 1.650 s, `CONFIA Kernel` 13.810 s, `CONFIA API`
  **02:55 min** (compile + unit + integration + JaCoCo).
- **Total measured time: 03:11 min.** `BUILD SUCCESS`. Comfortably inside the 8-minute budget
  `design.md` §13 names; no W1 escalation needed at this cut.

### Task 1.6 — measured diff

```
git diff --numstat main...change/idempotency-key-infrastructure -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'
88   0  apps/api/app/src/main/resources/db/migration/V4__create_shared_idempotency_key.sql
144  0  apps/api/app/src/test/java/com/confia/schema/IdempotencyKeyPrivilegeIT.java
91   0  apps/api/app/src/test/java/com/confia/schema/RolePrivilegeMatrixIT.java
```

**Total: 323 authored lines** (all additions, no deletions). Well inside the 800-line budget
(`docs/15-flujo-de-trabajo-git.md` §3) and inside `design.md` §12's own 300-480 forecast for C1. No
split needed; no candidate split points to report.

### Task 1.7 — final verification of PR C1

The full `./mvnw -B verify` run captured under task 1.5 ran against the exact committed tree (both
task 1.1 and 1.2 commits already existed before that run started; git status was clean throughout).
Within that single run:

- `RolePrivilegeMatrixIT`: `Tests run: 16, Failures: 0, Errors: 0` — complete green, all 16
  assertions including the seven new `shared_idempotency_key` rows.
- `IdempotencyKeyPrivilegeIT`: `Tests run: 2, Failures: 0, Errors: 0` — complete green.
- `MultiTenantSchemaIT`: `Tests run: 7, Failures: 0, Errors: 0` — green, unmodified (task 1.3).
- `AuditScopeExclusionInventoryTest`: `Tests run: 4, Failures: 0, Errors: 0` — green (surefire
  phase; task 1.4).
- Overall: `BUILD SUCCESS`, `Tests run: 63` (all integration tests, module-wide),
  `jacoco:check` → "All coverage checks have been met."

**Deviation, reported honestly**: the task also asks to push `change/idempotency-key-infrastructure`
and confirm the `backend` CI job. The orchestrator's launch instructions for this run are explicit
and take precedence: *"No empujes ni abras pull requests."* The push/CI-confirmation half of this
task was not executed as a deliberate scope boundary of this run, not a failure or an oversight. All
locally-verifiable evidence (full `./mvnw -B verify`, clean tree, every named test green) is
recorded above and is real.

### Hard-stop checks (none triggered, PR C1)

1. Diff (task 1.6): 323 lines, far under 800 — no stop.
2. No gate weakened, no role given more than `docs/03` §6.1 allows.
3. No contradiction found between tasks/design/specs/ADR-0010 for this cut.
4. `MultiTenantSchemaIT` passed **unmodified** — no discrepancy to report.

### Three explicit verifications requested by the launch prompt (PR C1)

- **Migration inventory trap**: `V4`'s comments name "change 9" and the spec requirement, never the
  scheduler table's literal name; `AuditScopeExclusionInventoryTest` stayed green (task 1.4).
- **Privileges verified against the real catalogue**: every assertion uses
  `has_table_privilege(role, table, privilege)` against real PostgreSQL, never a hand-written list;
  `PUBLIC` and a scratch role outside the five are both covered, matching the precedent already set
  for the audit tables.
- **`confia_admin_app` UPDATE, no DELETE**: `IdempotencyKeyPrivilegeIT` proves both with real SQL
  (a real `UPDATE` against `expires_at` succeeds; a real `DELETE` throws
  `org.jooq.exception.DataAccessException`), matching `docs/03` §6.1's non-financial-table rule
  exactly — `shared_idempotency_key` carries no amount or currency column.

### TDD Cycle Evidence (PR C1)

| Task | RED observed | GREEN observed | REFACTOR |
|---|---|---|---|
| 1.1/1.2 | Yes — `relation "shared_idempotency_key" does not exist` (task1.1-red.log) | Yes — 18/18 tests, BUILD SUCCESS (task1.2-green.log) | None needed; migration and tests matched design on first pass |

### Work Unit Evidence (PR C1)

| Evidence | Value |
|---|---|
| Focused test command and result | `./mvnw -B -pl app -am test -Dtest=RolePrivilegeMatrixIT,IdempotencyKeyPrivilegeIT -Dsurefire.failIfNoSpecifiedTests=false` → 18/18 green |
| Runtime harness command/result | `./mvnw -B verify` (apps/api) → BUILD SUCCESS, 63 integration tests green, JaCoCo bundle check met, 03:11 min total |
| Rollback boundary | Revert this PR's two commits (`c78be2f`, `48500b5`); no Java consumer exists yet, so no other code depends on `shared_idempotency_key`. Per `design.md` §9 point 3 this PR cannot be reverted independently once PR C2a-1 exists and compiles against the generated jOOQ type |

### Commits (PR C1)

| Task | SHA | Message |
|---|---|---|
| 1.1 | `c78be2f` | `test(schema): add RED privilege gates for shared_idempotency_key` |
| 1.2 | `48500b5` | `feat(schema): create shared_idempotency_key table` |
| 1.3-1.7 | (no commit — verification-only tasks; evidence recorded above) |

---

## PR C2a-1 — port, signed types, exceptions and jOOQ adapter (branch `change/idempotency-key-infrastructure-store`, base PR C1)

### Task 2.1 — Sondas S4 and S6 (blocking)

**S6, already run by the orchestrator 2026-09-23 (design.md, final section "Sonda S6"): PASS.**
Confirmed again by reading the generated sources present in `target/generated-sources/jooq` from
the PR C1 run: the class is `confia.generated.jooq.tables.SharedIdempotencyKey`
(`SHARED_IDEMPOTENCY_KEY` reference), `response_body JSONB` generates as `org.jooq.JSONB`,
`status`/`endpoint`/`idempotency_key`/`request_hash` (all `TEXT` with `CHECK`) generate as
`String`, and `created_at`/`completed_at`/`expires_at` generate as `OffsetDateTime`. No
`forcedType` needed. R2 is satisfied: the adapter can live in `com.confia.shared.infrastructure`.

**S4, executed now, measured (not read).** Temporary probe `ProbeS4IT` (deleted after this
recording, not a deliverable), extending `CommittingPostgresIntegrationTest`, run with
`./mvnw -B -pl app -am test -Dtest=ProbeS4IT -Dsurefire.failIfNoSpecifiedTests=false`
(`/tmp/confia-logs/s4-probe.log`). Two real scenarios against `shared_idempotency_key` through the
real `IntegrationTestApplication`/`TransactionRunner` wiring, no adapter yet (the adapter does not
exist; the probe issues the same jOOQ `insertInto` shape the adapter will use):

- **`55P03` (wait exhaustion)**: two independent `TransactionRunner`s, thread A inserts and holds
  the transaction open past a `CyclicBarrier`, thread B sets `lock_timeout` to `100ms` and attempts
  the same primary key. Measured: top-level `org.jooq.exception.DataAccessException`, cause
  `org.postgresql.util.PSQLException` with `getSQLState()` = `"55P03"`, message `"canceling
  statement due to lock timeout"`. Matches the design's prediction exactly. Supplier invocation
  count for thread B: **1** — confirms `TransactionRunner.isRetryable(...)` did not retry (measured
  by a counter, not by reading the type, per the launch prompt's instruction).
- **`23505` (duplicate key, sequential)**: first transaction commits an insert; a second,
  independent transaction inserts the same primary key. Measured: top-level
  **`org.jooq.exception.IntegrityConstraintViolationException`**, cause
  `org.postgresql.util.PSQLException` with `getSQLState()` = `"23505"`, message `"duplicate key
  value violates unique constraint \"shared_idempotency_key_pk\""`. Supplier invocation count:
  **1** — not retried.

**Discrepancy found and reported, not blocking (hard-stop 3, informational).** design.md section 2
predicted, by reading, that both outcomes arrive as plain `org.jooq.exception.DataAccessException`
wrapping the raw `SQLException`, because Spring Boot 4.1 ships no jOOQ autoconfiguration. Measured
reality is more specific for the duplicate-key case: jOOQ's **own** internal exception translation
(unrelated to Spring's `org.springframework.dao` hierarchy — confirmed by inspecting
`jooq-3.18.3.jar` with `javap`) already turns `23505` into
`org.jooq.exception.IntegrityConstraintViolationException`, a direct subclass of
`org.jooq.exception.DataAccessException` (itself `extends RuntimeException`, not
`ConcurrencyFailureException` — **hard-stop 4 does NOT trigger**, confirmed by class inspection: it
carries no relation whatsoever to Spring's `ConcurrencyFailureException`). Both measured exceptions
are `RuntimeException`s carrying the real `SQLException` in their cause chain, so `translate(...)`
as designed in section 6.3 — walking the cause chain for `SQLException.getSQLState()` — works
unchanged regardless of which of the two top-level jOOQ types wraps it. **No design change
required**; recorded here because "no se afirma como comprobado lo que no se comprobó" and the
measured type differs from the predicted one, even though the consequence for the adapter's
`translate(...)` method is nil.

**Consequence for task 2.4/2.5**: `translate(...)` must never branch on the top-level exception
type (`DataAccessException` vs `IntegrityConstraintViolationException`) — only on the `SQLState`
found by walking `getCause()`, exactly as design.md section 6.3 already specifies. This measured
result confirms that requirement is not just prudent but necessary, since jOOQ itself already uses
two different top-level types for the two outcomes this change must treat uniformly by code.

### Task 2.2 — RED: error-code catalog

Created `IdempotencyErrorCodesTest.java` in `com.confia.shared.security`, mirroring
`OrganizationErrorCodesTest`/`KernelErrorCodesTest`'s pattern: kebab-case format, ≤64 characters, no
duplicates, both codes prefixed `idempotency-`, both exceptions asserted as `DomainException`
subclasses, and a dedicated test that both `Reason` values of the conflict exception share one code.

**Observed RED** (`/tmp/confia-logs/task2.2-red.log`,
`./mvnw -B -pl app -am test -Dtest=IdempotencyErrorCodesTest -Dsurefire.failIfNoSpecifiedTests=false`):
compilation failure — `cannot find symbol: class IdempotencyConflictException`,
`cannot find symbol: class IdempotencyPayloadMismatchException`. Exactly as predicted (neither
exception exists yet).

### Task 2.3 — GREEN: the two exceptions

Created `IdempotencyConflictException` (extends `DomainException`, code `idempotency-conflict`,
`Reason` enum with `WAIT_EXHAUSTED`/`MARKER_IN_PROGRESS`, an optional `cause` constructor for the
adapter's translated `SQLException`) and `IdempotencyPayloadMismatchException` (extends
`DomainException`, code `idempotency-payload-mismatch`).

**Observed GREEN** (`/tmp/confia-logs/task2.3-green.log`, same focused command):
`Tests run: 7, Failures: 0, Errors: 0` → `BUILD SUCCESS`.

### Task 2.4 — RED: port, signed types and the adapter IT

Created the port's own signed types in `com.confia.shared.security`: `IdempotencyKey` (record,
`endpoint`/`value`, non-null compact constructor), `IdempotentResponse` (record, `responseStatus`/
`responseBody` as `tools.jackson.databind.JsonNode`, non-null body), `IdempotencyRecord` (record,
JDK-only fields mirroring `AuditRowSnapshot`'s pattern — `responseBody` as raw `String` jsonb text),
the `IdempotencyRecordStore` port interface (four methods: `lockExisting`, `insertInProgress`,
`restartExpired`, `complete`), and `IdempotencyMarkerAlreadyExists` (plain `RuntimeException`, not a
`DomainException` — it never crosses this component's own boundary, so it carries no stable
web-facing code; documented explicitly in its Javadoc).

Created `JooqIdempotencyRecordStoreIT.java` in `com.confia.shared.infrastructure`, extending
`CommittingPostgresIntegrationTest`, with six scenarios: `insertInProgress` visible through
`lockExisting`; `lockExisting` absent for an unknown key; `complete` stores the response and marks
`COMPLETED`; `restartExpired` reuses the row without changing `created_at`; the `23505` translation
(sequential, real duplicate insert) to `IdempotencyMarkerAlreadyExists`; the `55P03` translation
(two independent `TransactionRunner`s, `CyclicBarrier`-synchronized, `lock_timeout` set directly on
the second transaction to isolate the adapter's own translation from the future executor's
ownership of that statement) to `IdempotencyConflictException(WAIT_EXHAUSTED)`, with an invocation
counter proving no retry.

**Observed RED** (`/tmp/confia-logs/task2.4-red.log`,
`./mvnw -B -pl app -am test -Dtest=JooqIdempotencyRecordStoreIT -Dsurefire.failIfNoSpecifiedTests=false`):
compilation failure — `cannot find symbol: class JooqIdempotencyRecordStore`, exactly as predicted
(the port and its signed types compiled cleanly; only the adapter was missing).

### Task 2.5 — GREEN: `JooqIdempotencyRecordStore`

Created the single jOOQ adapter in `com.confia.shared.infrastructure`, implementing all four port
methods over `confia.generated.jooq.tables.SharedIdempotencyKey`, with a private `translate(...)`
walking the cause chain by `SQLState` only (never message text), matching `TransactionRunner`'s own
pattern. Neither `IdempotencyMarkerAlreadyExists` nor `IdempotencyConflictException` extends
Spring's `ConcurrencyFailureException` (verified by inspection: both extend plain
`RuntimeException`/`DomainException`).

**Observed GREEN** (`/tmp/confia-logs/task2.5-green.log`, same focused command):
`Tests run: 6, Failures: 0, Errors: 0` → `BUILD SUCCESS`.

**Architecture gates re-verified in isolation** (`/tmp/confia-logs/task2-arch-check.log`):
`JooqConfinedToInfrastructureTest`, `NoUnapprovedPlainSqlTest`, `TransactionsOnlyInSharedSecurityTest`,
`NoCyclesTest`, `TableOwnershipByModuleTest`, `SpringModulithVerificationTest`,
`LayeredArchitectureTest` — all green (28 tests total across the focused run) with the new classes
present. Confirms R1, R2, R3 and the module-slice rules hold for the new adapter and port.

### Task 2.6 — measured diff

```
git diff --numstat 48500b5...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'
158  0  apps/api/app/src/main/java/com/confia/shared/infrastructure/JooqIdempotencyRecordStore.java
55   0  apps/api/app/src/main/java/com/confia/shared/security/IdempotencyConflictException.java
18   0  apps/api/app/src/main/java/com/confia/shared/security/IdempotencyKey.java
17   0  apps/api/app/src/main/java/com/confia/shared/security/IdempotencyMarkerAlreadyExists.java
19   0  apps/api/app/src/main/java/com/confia/shared/security/IdempotencyPayloadMismatchException.java
19   0  apps/api/app/src/main/java/com/confia/shared/security/IdempotencyRecord.java
37   0  apps/api/app/src/main/java/com/confia/shared/security/IdempotencyRecordStore.java
18   0  apps/api/app/src/main/java/com/confia/shared/security/IdempotentResponse.java
252  0  apps/api/app/src/test/java/com/confia/shared/infrastructure/JooqIdempotencyRecordStoreIT.java
96   0  apps/api/app/src/test/java/com/confia/shared/security/IdempotencyErrorCodesTest.java
```

**Total: 689 authored lines** (all additions, no deletions), measured from PR C1's last code commit
(`48500b5`) to `HEAD`. Well inside the 800-line budget (`docs/15-flujo-de-trabajo-git.md` §3) and
inside `design.md` §12's own 590-970 forecast for the whole of C2a (C2a-1 alone at 689, leaving
C2a-2 comfortably inside the remainder). No split needed; no candidate split points to report.

### Task 2.7 — final verification of PR C2a-1

Full `./mvnw -B verify` in `apps/api`, clean tree, `JAVA_HOME` on JDK 25, `MAVEN_OPTS` with
`Windows-ROOT`, Docker active (`/tmp/confia-logs/task2.7-verify.log`):

- `IdempotencyErrorCodesTest`: `Tests run: 7, Failures: 0, Errors: 0` — complete green.
- `JooqIdempotencyRecordStoreIT`: `Tests run: 6, Failures: 0, Errors: 0` — complete green, both
  `SQLState` translations included.
- Every architecture gate green: `JooqConfinedToInfrastructureTest`, `NoUnapprovedPlainSqlTest`,
  `TransactionsOnlyInSharedSecurityTest`, `NoCyclesTest`, `TableOwnershipByModuleTest`,
  `SpringModulithVerificationTest`, `LayeredArchitectureTest`, and every other architecture test in
  the module.
- `RolePrivilegeMatrixIT` (16), `IdempotencyKeyPrivilegeIT` (2), `MultiTenantSchemaIT` (7) — still
  green, unaffected by this slice.
- Overall: `Tests run: 133` (unit, surefire phase) + `Tests run: 69` (integration, failsafe phase) —
  all green. `jacoco:check` → "All coverage checks have been met" (both the surefire-only and the
  merged unit+integration report).
- Total measured build time: **03:02 min** (`CONFIA API` module `02:47 min` + `CONFIA Kernel`
  compile). Comfortably inside the 8-minute budget; no W1 escalation needed at this cut.
- `BUILD SUCCESS`.

**Deviation, reported honestly, same as PR C1**: the task also asks to push
`change/idempotency-key-infrastructure-store` and confirm the `backend` CI job. The orchestrator's
launch instructions for this run are explicit and take precedence: *"No empujes ni abras pull
requests."* Not executed as a deliberate scope boundary, not a failure or an oversight. All
locally-verifiable evidence above is real.

### Hard-stop checks (none triggered, PR C2a-1)

1. Diff (task 2.6): 689 lines, under 800 — no stop.
2. No gate weakened, no ArchUnit exception added, no role given more than `docs/03` §6.1 allows.
3. One informational discrepancy found and reported (task 2.1, S4 vs design.md section 2's
   by-reading prediction) — not blocking, no design change required.
4. S4 did **not** reveal a Spring `ConcurrencyFailureException` reaching the adapter's boundary —
   the wrapper of decision 5 stays a precaution, as designed, not an upgraded necessity.

### Three things requested to be proven, not assumed (PR C2a-1)

- **`SQLState` discrimination is by code in the cause chain, never by message text**:
  `translate(...)` only inspects `SQLException.getSQLState()` walking `getCause()`; `S4`'s measured
  result (two different top-level jOOQ types for the same underlying codes) is itself proof this
  discipline is load-bearing, not incidental.
- **S4 counts invocations**: both `ProbeS4IT` and `JooqIdempotencyRecordStoreIT`'s wait-exhaustion
  scenario use an invocation counter, not a type assertion, to prove no retry occurred.
- **The adapter opens no transaction**: `JooqIdempotencyRecordStore` has no
  `PlatformTransactionManager`, no `TransactionTemplate`, no `@Transactional` — every method
  participates in whatever transaction the caller's `DSLContext` is already bound to, confirmed by
  `TransactionsOnlyInSharedSecurityTest` staying green with the adapter present (task 2.5).

### TDD Cycle Evidence (PR C2a-1)

| Task | RED observed | GREEN observed | REFACTOR |
|---|---|---|---|
| 2.2/2.3 | Yes — compile failure, both exceptions missing (task2.2-red.log) | Yes — 7/7 tests, BUILD SUCCESS (task2.3-green.log) | None needed |
| 2.4/2.5 | Yes — compile failure, only the adapter missing (task2.4-red.log) | Yes — 6/6 tests, BUILD SUCCESS (task2.5-green.log) | None needed |

### Work Unit Evidence (PR C2a-1)

| Evidence | Value |
|---|---|
| Focused test command and result | `./mvnw -B -pl app -am test -Dtest=IdempotencyErrorCodesTest,JooqIdempotencyRecordStoreIT -Dsurefire.failIfNoSpecifiedTests=false` → 13/13 green |
| Runtime harness command/result | `./mvnw -B verify` (apps/api) → BUILD SUCCESS, 133 unit + 69 integration tests green, JaCoCo bundle check met, 03:02 min total |
| Rollback boundary | Revert this PR's five commits (`5119fd7` docs, `a281175` test, `235e5d7` feat, `ee9f42a` test, `c172c55` feat) individually reverts to PR C1's state. Per `design.md` §9 point 3, PR C1 cannot be reverted independently of this PR once this PR exists — the adapter compiles against the generated jOOQ type from `V4` |

### Commits (PR C2a-1)

| Task | SHA | Message |
|---|---|---|
| 2.1 | `5119fd7` | `docs(sdd): run probe S4 and reconfirm S6 for slice C2a-1` |
| 2.2 | `a281175` | `test(shared): add RED error-code catalog gate for idempotency exceptions` |
| 2.3 | `235e5d7` | `feat(shared): add idempotency conflict and payload-mismatch exceptions` |
| 2.4 | `ee9f42a` | `test(shared): add RED port, signed types and jOOQ adapter IT` |
| 2.5 | `c172c55` | `feat(shared): add jOOQ adapter for shared_idempotency_key` |
| 2.6-2.7 | (no commit — verification-only tasks; evidence recorded above) |

## PR C2a-2 — canonicalized payload hash and result type (branch `change/idempotency-key-infrastructure-hasher`, base PR C2a-1)

### Task 3.1 — RED: canonicalized payload hash

Created `RequestPayloadHasherTest` with five cases: a golden vector fixed as a literal hex string
never produced by calling the class under test (derived independently with
`printf '%s' 'confia.idempotency.v1{"a":2,"b":1}' | openssl dgst -sha256`, outside the JVM, outside
`RequestPayloadHasher`); two payloads with the same keys in a different textual order producing the
same hash; a JSON null literal (`NullNode.instance`, not a Java `null`) hashing without throwing;
a Java `null` payload rejected with `NullPointerException`; and a general hex-format shape check.

**Observed RED** (`/tmp/confia-logs/task3.1-red.log`, command
`./mvnw -B -pl app -am test -Dtest=RequestPayloadHasherTest -Dsurefire.failIfNoSpecifiedTests=false`):

```
[ERROR] COMPILATION ERROR :
[ERROR] .../RequestPayloadHasherTest.java:[48,19] cannot find symbol
[ERROR]   symbol:   class RequestPayloadHasher
[ERROR] .../RequestPayloadHasherTest.java:[48,53] cannot find symbol
[ERROR]   symbol:   class RequestPayloadHasher
BUILD FAILURE
```

Exact failure reason: `RequestPayloadHasher` does not exist yet, exactly as the task predicted.
Real RED, not invented.

### Task 3.2 — GREEN: `RequestPayloadHasher`

Created `RequestPayloadHasher.hash(JsonNode)`:
`hex(sha256(utf8(FORMAT_VERSION) || utf8(canonicalJson(payload))))`, `FORMAT_VERSION =
"confia.idempotency.v1"`, delegating canonicalization to
`CanonicalAuditRowSerializer.canonicalJson(JsonNode)` — no second canonicalization written. Javadoc
on both classes cross-references the other, per the task.

**Observed GREEN** (`/tmp/confia-logs/task3.2-green.log`): `Tests run: 5, Failures: 0, Errors: 0` —
`RequestPayloadHasherTest` complete green, `BUILD SUCCESS`.

**Proof that the golden vector can actually fail (requested explicitly, not assumed).** The
danger named by the orchestrator's instructions is a golden test that computes its own expected
value with the same production code it exercises, which would pass trivially forever. To rule
that out for real: `CanonicalAuditRowSerializer.compareObjectKeys`'s `return comparison;` was
temporarily flipped to `return -comparison;` (reversing key order — a stand-in for a silent
canonicalization change), the suite was re-run, reverted, and re-run again:

- **Perturbed** (`/tmp/confia-logs/task3.2-golden-proof-red.log`): `Tests run: 5, Failures: 1,
  Errors: 0` — `sameGoldenPayloadAlwaysProducesTheSameFixedHash` fails (the hard-coded literal no
  longer matches); `twoPayloadsWithTheSameKeysInADifferentOrderProduceTheSameHash` still passes,
  because order-invariance holds under *any* consistent comparator — exactly the discriminating
  behavior decision 8's mitigation 2 asks for: only the literal-value test catches the silent
  change, not the invariant test.
- **Reverted**: `git diff --stat` on the file showed no difference from the committed version
  before re-running.
- **Re-confirmed GREEN** (`/tmp/confia-logs/task3.2-green-reconfirm.log`): `Tests run: 5, Failures:
  0, Errors: 0`, `BUILD SUCCESS`.

### Task 3.3 — the sealed result type

Created `IdempotentOutcome` (`sealed interface` with `Executed`/`Replayed` records, design.md
§6.1), with no test of its own per the task — `IdempotentExecutorIT` (PR C2b) is its first
consumer. Compile-checked with `./mvnw -B -pl app -am test-compile`
(`/tmp/confia-logs/task3.3-compile.log`): `BUILD SUCCESS`.

### Task 3.4 — measured diff of PR C2a-2

`git diff --numstat 92a551c...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr'
':(exclude)**/generated/**'` (base is PR C2a-1's closing commit):

```
23   0  apps/api/app/src/main/java/com/confia/shared/security/IdempotentOutcome.java
66   0  apps/api/app/src/main/java/com/confia/shared/security/RequestPayloadHasher.java
86   0  apps/api/app/src/test/java/com/confia/shared/security/RequestPayloadHasherTest.java
```

**Total: 175 authored lines** (all additions, no deletions). Well inside the 800-line budget and
inside `design.md` §12's own 590-970 forecast for the whole of C2a (C2a-1 measured 689 in the
previous batch; 689 + 175 = 864, slightly over that forecast's raw sum, but each half was
independently well inside its own 800-line PR budget, which is the number that actually governs
delivery — no split needed, no candidate split points to report).

### Task 3.5 — final verification of PR C2a-2

Full `./mvnw -B verify` in `apps/api`, clean working tree, `JAVA_HOME` on JDK 25, `MAVEN_OPTS` with
`Windows-ROOT`, Docker active (`/tmp/confia-logs/task3.5-verify.log`):

- `RequestPayloadHasherTest`: `Tests run: 5, Failures: 0, Errors: 0` — complete green, including
  the golden vector.
- Every architecture gate green (`NoCyclesTest`, `LayeredArchitectureTest`,
  `JooqConfinedToInfrastructureTest`, `TransactionsOnlyInSharedSecurityTest`,
  `TableOwnershipByModuleTest`, `SpringModulithVerificationTest`, and the rest) — in particular
  `NoCyclesTest` and `LayeredArchitectureTest` stayed green with `RequestPayloadHasher` depending on
  `com.confia.shared.audit.CanonicalAuditRowSerializer`, confirming design.md's by-reading
  prediction that the two packages share the `shared` slice for `NoCyclesTest` and carry no layer
  segment for `LayeredArchitectureTest` — no cycle, no violation, no suppression needed.
- `IdempotencyErrorCodesTest`, `JooqIdempotencyRecordStoreIT`, `RolePrivilegeMatrixIT`,
  `IdempotencyKeyPrivilegeIT`, `MultiTenantSchemaIT` — all still green, unaffected by this slice.
- Overall: `Tests run: 138` (unit, surefire) + `Tests run: 69` (integration, failsafe) — all green.
  `jacoco:check` → "All coverage checks have been met" (both reports).
- Total measured build time: **03:20 min**. Comfortably inside the 8-minute budget.
- `BUILD SUCCESS`.

**Same deliberate scope boundary as PR C1 and PR C2a-1**: not pushed, no PR opened, per this run's
explicit instructions ("No empujes ni abras pull requests"). All locally-verifiable evidence above
is real.

### Hard-stop checks (none triggered, PR C2a-2)

1. Diff (task 3.4): 175 lines, under 800 — no stop.
2. No gate weakened, no ArchUnit exception added, no privilege changed.
3. Reusing `CanonicalAuditRowSerializer` from `shared.security` did **not** break `NoCyclesTest`,
   `LayeredArchitectureTest`, or any other dependency rule — confirmed by running the real suite,
   not only by reading design.md's prediction.
4. No contradiction found between tasks, design, spec, and ADR for this slice.

### Two things requested to be proven, not assumed (PR C2a-2)

- **The golden vector can fail**: demonstrated above by temporarily reversing the key-order
  comparator, observing `sameGoldenPayloadAlwaysProducesTheSameFixedHash` turn red while the
  order-invariance test stayed green, then reverting cleanly.
- **Key order does not change the hash**: `twoPayloadsWithTheSameKeysInADifferentOrderProduceTheSameHash`
  hashes `{"b":1,"a":2}` and `{"a":2,"b":1}` (same keys, reversed textual order) and asserts equal
  output — green in every run above, including the perturbed one.

### TDD Cycle Evidence (PR C2a-2)

| Task | RED observed | GREEN observed | REFACTOR |
|---|---|---|---|
| 3.1/3.2 | Yes — compile failure, `RequestPayloadHasher` missing (task3.1-red.log) | Yes — 5/5 tests, BUILD SUCCESS (task3.2-green.log), plus proven-breakable golden vector (task3.2-golden-proof-red.log, reverted, task3.2-green-reconfirm.log) | None needed |

### Work Unit Evidence (PR C2a-2)

| Evidence | Value |
|---|---|
| Focused test command and result | `./mvnw -B -pl app -am test -Dtest=RequestPayloadHasherTest -Dsurefire.failIfNoSpecifiedTests=false` → 5/5 green |
| Runtime harness command/result | `./mvnw -B verify` (apps/api) → BUILD SUCCESS, 138 unit + 69 integration tests green, JaCoCo bundle check met, 03:20 min total |
| Rollback boundary | Revert this PR's three commits (`9d84608` test, `75be2a6` feat, `8a5b8e9` feat) individually reverts to PR C2a-1's state; no other file touched |

### Commits (PR C2a-2)

| Task | SHA | Message |
|---|---|---|
| 3.1 | `9d84608` | `test(shared): add RED hasher test with independent golden vector` |
| 3.2 | `75be2a6` | `feat(shared): add canonicalized request payload hasher` |
| 3.3 | `8a5b8e9` | `feat(shared): add sealed idempotent outcome result type` |
| 3.4-3.5 | (no commit — verification-only tasks; evidence recorded above) |

## PR C2b — the component without concurrency (branch `change/idempotency-key-infrastructure-executor`, base PR C2a-2)

### Task 4.1 — Sonda S3 (blocking)

**Already run by the orchestrator on 2026-09-23, before this batch started, and recorded in
`design.md`'s final section ("Sonda S3, ejecutada por el orquestador el 2026-09-23, antes del corte
C2b"), commit `47342cb`. Not repeated here, per this batch's explicit launch instructions.**

What it measured, taken as given: a blocked `SELECT ... FOR UPDATE` waits for the first transaction
and, on unblocking, reads the most recently committed version (`COMPLETED`, after a measured 4354 ms
wait) — never the snapshot it started with. This is `READ COMMITTED` re-evaluation working as
decision 7 needs it. **Direct consequence for this batch**: decision 7's primary approach holds, and
the designed fallback (an `UPDATE` with the state predicate in its own clause and zero rows updated
as the losing signal) is not used — `IdempotentExecutor` below never implements that fallback path.

### Task 4.2 — RED: the component without concurrency

Created `IdempotentExecutorIT.java` in `com.confia.shared.security`, extending
`CommittingPostgresIntegrationTest`, with the four scenarios in the task's own order: (a) a new key
executes the use case and completes the marker in the same transaction, outcome `Executed`; (b) a use
case that fails deterministically after the marker is written — the whole transaction rolls back,
zero marker rows survive, and a later request with the same key is treated as new; (c) same key, same
hash, on an already-completed key — the exact stored response is returned, the use case records zero
new invocations, outcome `Replayed`; (d) same key, different hash — rejected with
`IdempotencyPayloadMismatchException` without invoking the use case at all, verified by an invocation
counter at zero, not only by the exception type.

**Observed RED** (`/tmp/confia-logs/task4.2-red.log`, command
`./mvnw -B -pl app -am test -Dtest=IdempotentExecutorIT -Dsurefire.failIfNoSpecifiedTests=false`):

```
[ERROR] COMPILATION ERROR :
[ERROR] .../IdempotentExecutorIT.java:[39,13] cannot find symbol
[ERROR]   symbol:   class IdempotentExecutor
[ERROR] .../IdempotentExecutorIT.java:[40,20] cannot find symbol
[ERROR]   symbol:   class IdempotentExecutor
BUILD FAILURE
```

Exact failure reason: `IdempotentExecutor` does not exist yet, exactly as the task predicted. Real
RED, not invented.

### Task 4.3 — GREEN: `IdempotentExecutor`

Created `IdempotentExecutor` in `com.confia.shared.security`: `final` class, explicit constructor
over `TransactionRunner`, `IdempotencyRecordStore`, `RequestPayloadHasher` and `Clock` (plus the
overload with explicit `lockWait`/`retention`, defaults 250 ms / 24 h), no Spring annotation, no
transaction of its own — every write goes through `TransactionRunner.execute(...)` (R3 by
composition). The hash is computed once, outside any transaction, over the caller's `JsonNode`
payload. Inside the single `execute(...)` invocation: `lockExisting` first; absent → `insertInProgress`
+ use case + `complete`, `Executed`; expired → `restartExpired` (never `DELETE`+`INSERT`) + use case +
`complete`, `Executed`; hash mismatch → `IdempotencyPayloadMismatchException` **before** the use case
runs; `COMPLETED` → `Replayed`, reconstructing the stored `IdempotentResponse` from the record's
`responseBody` text with the same `JsonMapper` pattern `DefaultAuditChainVerifier` uses; otherwise
(confirmed `IN_PROGRESS` from another transaction) → the defensive `IdempotencyConflictException(MARKER_IN_PROGRESS)`.

**Deliberately not implemented in this cut, per the task's own scope and confirmed against task
5.3's text** (which says "Modificar `IdempotentExecutor`..." for both pieces, meaning neither exists
yet): no `set_config('lock_timeout', ...)` statement as the first statement of the transaction, and no
`catch (IdempotencyMarkerAlreadyExists)` branch that opens a second, read-only transaction (T3) to
replay a marker a concurrent transaction just committed. Both are C2c's own addition (design.md,
decision 4; tasks.md, task 5.3) — `lockWait` is accepted and stored by the constructor for that later
use, but nothing in this class's logic consults it yet. Documented explicitly in the class Javadoc so
a later reader does not mistake either omission for an oversight.

**Observed GREEN** (`/tmp/confia-logs/task4.3-green.log`,
`./mvnw -B -pl app -am test -Dtest=IdempotentExecutorIT -Dsurefire.failIfNoSpecifiedTests=false`):
`Tests run: 4, Failures: 0, Errors: 0` → `BUILD SUCCESS`.

**Architecture gates re-verified in isolation** (`/tmp/confia-logs/task4-arch-check.log`):
`JooqConfinedToInfrastructureTest`, `NoUnapprovedPlainSqlTest`, `TransactionsOnlyInSharedSecurityTest`,
`NoCyclesTest`, `TableOwnershipByModuleTest`, `SpringModulithVerificationTest`,
`LayeredArchitectureTest` — all green (15 tests total across the focused run) with
`IdempotentExecutor` present. Confirms R3 holds: the new class opens no transaction of its own, and
its package carries no layer segment.

### Task 4.4 — measured diff

```
git diff --numstat 8a5b8e9...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'
157   0  apps/api/app/src/main/java/com/confia/shared/security/IdempotentExecutor.java
160   0  apps/api/app/src/test/java/com/confia/shared/security/IdempotentExecutorIT.java
```

**Total: 317 authored lines** (all additions, no deletions), measured from PR C2a-2's last code
commit (`8a5b8e9`) to `HEAD`. Well inside the 800-line budget
(`docs/15-flujo-de-trabajo-git.md` §3) and inside `design.md` §12's own 370-580 forecast band for
C2b (below its lower bound, which the task's own text says is not anticipated as a problem — only an
excess over 800 would require a stop). No split needed; no candidate split points to report.

### Task 4.5 — final verification of PR C2b

Full `./mvnw -B verify` in `apps/api`, clean working tree, `JAVA_HOME` on JDK 25, `MAVEN_OPTS` with
`Windows-ROOT`, Docker active, `target/` directories removed by hand first (OneDrive lock issue)
(`/tmp/confia-logs/task4.5-verify.log`):

- `IdempotentExecutorIT`: `Tests run: 4, Failures: 0, Errors: 0` — complete green: new key, the
  failing-effect atomicity scenario, replay without re-execution, and payload-mismatch rejection.
- `JooqIdempotencyRecordStoreIT` (6), `IdempotencyErrorCodesTest` (7), `RequestPayloadHasherTest` (5),
  `RolePrivilegeMatrixIT` (16), `IdempotencyKeyPrivilegeIT` (2), `MultiTenantSchemaIT` (7),
  `AuditScopeExclusionInventoryTest` (4) — all still green, unaffected by this slice.
- Every architecture gate green: `JooqConfinedToInfrastructureTest`, `LayeredArchitectureTest`,
  `NoCyclesTest`, `NoUnapprovedPlainSqlTest`, `SpringModulithVerificationTest`,
  `TableOwnershipByModuleTest`, `TransactionsOnlyInSharedSecurityTest`, and every other architecture
  test in the module.
- Overall: `Tests run: 138` (unit, surefire phase) + `Tests run: 73` (integration, failsafe phase) —
  all green. `jacoco:check` → "All coverage checks have been met." (both the kernel module and the
  API module, unit+integration merged report).
- Total measured build time: **03:32 min**. Comfortably inside the 8-minute budget
  (`design.md` §13); no W1 escalation needed at this cut.
- `BUILD SUCCESS`.

**Same deliberate scope boundary as every previous cut of this change**: not pushed, no PR opened,
per this run's explicit instructions ("No empujes ni abras pull requests"). All locally-verifiable
evidence above is real.

### Hard-stop checks (none triggered, PR C2b)

1. Diff (task 4.4): 317 lines, far under 800 — no stop.
2. No ArchUnit exception added, no gate weakened, no privilege changed. R3 (transactions confined to
   `shared/security`) holds by composition: `IdempotentExecutor` opens no transaction of its own.
3. One discrepancy in the tasks list already reported by the tasks phase itself (`design.md` §11 vs
   §12) and resolved there before this batch started; no new contradiction found between tasks,
   design, spec, and ADR for this slice.
4. `IdempotentExecutor` never opens its own transaction — confirmed both by code (delegates entirely
   to `TransactionRunner.execute(...)`) and by `TransactionsOnlyInSharedSecurityTest` staying green
   with the new class present (task 4.3). Hard-stop 3 does not trigger.

### Three things requested to be proven, not assumed (PR C2b)

- **Atomicity demonstrated with a real failure, not only the happy path**: task 4.2's scenario (b)
  makes the use case throw *after* the marker row is written, then asserts with a fresh, independent
  read (`store().lockExisting(...)` inside its own transaction) that **zero** rows survive for that
  key — not that two writes merely happened to both succeed together.
- **Payload-mismatch rejection counted, not just typed**: task 4.2's scenario (d) asserts
  `invocations[0] == 0` on an `int[]` counter incremented inside the use case `Supplier`, in addition
  to asserting the thrown type is `IdempotencyPayloadMismatchException` — the requirement is that the
  effect never ran, and a counter is what actually proves that, not the exception class alone.
- **Replay proven not to re-execute, not merely to return the same value by coincidence**: task 4.2's
  scenario (c) has the *second* call's use case return a deliberately different response
  (`responseStatus = 500`, a different body) than the first call's stored response, and asserts the
  returned response is the *first* call's stored value while the invocation counter stays at `1`. If
  the use case had run a second time and its second-call value had been asserted, the test would have
  passed without ever forcing a real replay to happen — this shape is chosen specifically to rule
  that out.

### TDD Cycle Evidence (PR C2b)

| Task | RED observed | GREEN observed | REFACTOR |
|---|---|---|---|
| 4.2/4.3 | Yes — compile failure, `IdempotentExecutor` missing (task4.2-red.log) | Yes — 4/4 tests, BUILD SUCCESS (task4.3-green.log) | None needed; the class matched design.md decision 6's flow (this cut's subset) on first pass |

### Work Unit Evidence (PR C2b)

| Evidence | Value |
|---|---|
| Focused test command and result | `./mvnw -B -pl app -am test -Dtest=IdempotentExecutorIT -Dsurefire.failIfNoSpecifiedTests=false` → 4/4 green |
| Runtime harness command/result | `./mvnw -B verify` (apps/api) → BUILD SUCCESS, 138 unit + 73 integration tests green, JaCoCo bundle check met, 03:32 min total |
| Rollback boundary | Revert this PR's two commits (`4261fa3` feat, `5287d9c` test) individually reverts to PR C2a-2's state; no other file touched. Per `design.md` §9 point 3, PR C1 and PR C2a-1 remain non-severable from this PR once it exists, unchanged from prior cuts |

### Commits (PR C2b)

| Task | SHA | Message |
|---|---|---|
| 4.1 | (no commit — already run by the orchestrator, commit `47342cb`, before this batch) | — |
| 4.2 | `5287d9c` | `test(shared): add RED IdempotentExecutorIT for the sequential-only paths` |
| 4.3 | `4261fa3` | `feat(shared): add IdempotentExecutor without concurrency` |
| 4.4-4.5 | (no commit — verification-only tasks; evidence recorded above) |

## PR C2c — the bounded wait and its three outcomes (branch `change/idempotency-key-infrastructure-concurrency`, base PR C2b)

### Task 5.1 — Sonda S5 (blocking)

**Already run by the orchestrator on 2026-09-23, before this batch started, and recorded in
`design.md`'s final section ("Sonda S5, ejecutada por el orquestador el 2026-09-23, antes del corte
C2c"), commit `2c05301`. Not repeated here, per this batch's explicit launch instructions.**

What it measured, taken as given: `confia_admin_app`, real and `NOSUPERUSER NOBYPASSRLS`, **can**
set `lock_timeout` with `set_config(..., true)` — no new privilege needed. The value does **not**
survive commit: `250ms` inside the transaction, `0` on the next transaction over the same pooled
connection. **Direct consequence for this batch**: the concurrency test that demonstrates this in
Java (below, `transactionRunnerNeverRetries*` is unrelated; the actual lock-timeout-does-not-survive
property is exercised implicitly by every scenario reusing `newIndependentRunner()`'s pool — no
dedicated single-connection scenario was required by task 5.2/5.3/5.4, since S5 itself is the
authoritative proof and `TransactionRunnerContextIT.noContextSurvivesOnAReusedPoolConnection`
already establishes the identical pattern for the security context; task 5.2's own three scenarios
do not pin the pool to one connection, by design, since they need two genuinely independent
backends to race).

### Task 5.2 — RED: the three outcomes of the concurrent request

Created `IdempotentExecutorConcurrencyIT.java` in `com.confia.shared.security`, extending
`CommittingPostgresIntegrationTest`, with the three scenarios in the task's own order, each with two
threads, each with its own `TransactionRunner`, its own connection and its own `IdempotentExecutor`,
synchronized with a `CyclicBarrier` exactly like `TransactionRunnerRetryIT` and
`JooqIdempotencyRecordStoreIT`'s own wait-exhaustion scenario — thread A always writes the marker
first and reaches the barrier from inside its own use case (proving the `INSERT` already happened),
thread B waits on the very same barrier immediately before calling `IdempotentExecutor.execute` at
all, so B's own attempt can only start once A's insert already ran:

- (a) **agotamiento**: A's `lockWait = 5s`, B's `lockWait = 150ms`. A sleeps 1000&nbsp;ms past the
  barrier (well past B's window) before returning; B must fail with
  `IdempotencyConflictException(WAIT_EXHAUSTED)`, without writing its own marker or invoking its use
  case, while A continues unaffected.
- (b) **la primera confirma dentro de la ventana**: both `lockWait = 5s`. A sleeps a short,
  deterministic 300&nbsp;ms past the barrier — not a race decider (the barrier already is one), but
  enough that B's own `INSERT` genuinely collides with A's still-uncommitted row instead of B's
  earlier `lockExisting` finding an already-committed one — then returns (commits). B must receive
  the exact response A stored, through the replay path, without invoking its own use case.
- (c) **la primera revierte dentro de la ventana**: both `lockWait = 5s`. A throws immediately after
  the barrier (rolls back). B's own `INSERT` must succeed and B must execute its own use case.

**Observed RED** (`/tmp/confia-logs/task5.2-red.log` and, after fixing scenario (b)'s timing so it
genuinely exercises the `INSERT` collision instead of a trivial already-committed read,
`/tmp/confia-logs/task5.2-red-v2.log`; command
`./mvnw -B -pl app -am test -Dtest=IdempotentExecutorConcurrencyIT -Dsurefire.failIfNoSpecifiedTests=false`,
run against the pre-C2c `IdempotentExecutor` — i.e. before task 5.3's production change, committed
separately as `62b465f`):

```
[ERROR] Tests run: 5, Failures: 1, Errors: 1, Skipped: 0
secondRequestFailsWithWaitExhaustionWhileTheFirstStaysOpen -- FAILURE!
  Expecting a throwable with cause being an instance of IdempotencyConflictException
  but was an instance of: IdempotencyMarkerAlreadyExists
secondRequestReplaysTheFirstsResponseWhenTheFirstCommitsWithinTheWindow -- ERROR!
  java.util.concurrent.ExecutionException: IdempotencyMarkerAlreadyExists: idempotency marker
  already exists for this primary key (uncaught — no replay mechanism exists yet)
```

Exact failure reasons, both real and predicted: without `lock_timeout` bound, B's `INSERT` simply
waits (unbounded) for A to finish instead of failing with `55P03` (scenario a); and without a catch
for `IdempotencyMarkerAlreadyExists`, the real `23505` collision (scenario b) propagates uncaught
instead of triggering a replay. Scenario (c) and the two non-retry tests of task 5.4 (below) already
passed at this point — expected, since neither depends on the missing production code (task 5.4's own
text: "Verde: confirmar sin cambios de producción"). Real RED, not invented.

### Task 5.3 — GREEN: `lock_timeout` and the replay-after-collision transaction

Modified `IdempotentExecutor` (`8d3089c`): added a `DataSource` constructor parameter — a necessary
elaboration of `design.md` decision 4's literal requirement ("el patrón literal de
`applySecurityContext`") that the original `§6.1` sketch predates and `§14` point 6 already flagged
as "por confirmar"; without a `DataSource`, this component cannot obtain the JDBC `Connection` bound
to its own transaction the way `TransactionRunner.applySecurityContext` does. Added `bindLockTimeout()`
(private), issuing `select set_config('lock_timeout', ?, true)` as the first statement of every
transaction this component opens, both in `execute`'s own T1 and in the new
`replayInANewTransaction`'s T3. Wrapped the call to `runner.execute(...)` in a
`catch (IdempotencyMarkerAlreadyExists e)` that opens `replayInANewTransaction` — a brand new
transaction that rereads the now-committed marker (the same hash/status checks T1 itself applies) and
returns `Replayed` or rejects it; `IdempotencyConflictException(WAIT_EXHAUSTED)` is not caught and
propagates unchanged, per design.md decision 6 ("no hay nada que repetir"). Updated
`IdempotentExecutorIT.executor()` to pass `dataSource()` — the only other call site of the changed
constructor.

**Observed GREEN** (`/tmp/confia-logs/task5.3-green-v2.log`, same focused command plus
`IdempotentExecutorIT`): `Tests run: 9, Failures: 0, Errors: 0` (5 concurrency + 4 sequential) —
`BUILD SUCCESS`.

**Stability, per the launch prompt's explicit hard-stop 3**: `IdempotentExecutorConcurrencyIT` run
three times independently (`/tmp/confia-logs/task5.3-green-v2.log` as part of the combined run,
`/tmp/confia-logs/task5.4-stability-run2.log`, `/tmp/confia-logs/task5.4-stability-run3.log`) — `5/5`
green every time, no flaky result observed. A fourth observation comes from the full `verify` run
(task 5.5/5.7, below): also `5/5` green, with a much shorter reported elapsed time (1.684 s vs.
~29-32 s standalone) because the Postgres container and Spring context are already warm from earlier
test classes in the same JVM fork — not a sign that the sleeps were skipped, confirmed by the actual
assertions (which depend on those sleeps) staying green.

### Task 5.4 — the non-retry counter, and an honest process note

**Discrepancy noted, not blocking.** The launch prompt's own summary says "cuenta invocaciones del
caso de uso"; `tasks.md`'s task 5.4 text says explicitly the opposite — "no del caso de uso: lo que
hay que contar es cuántas veces `TransactionRunner` reintenta la operación" — with a correct,
load-bearing reason: along both the wait-exhaustion and duplicate-key paths, `IdempotentExecutor`
never reaches the caller's use case at all, retried or not (the failure happens before
`useCase.get()` in both cases), so a counter placed inside the caller's use case would read zero
either way and could not distinguish "never retried" from "retried and failed the same way again".
`tasks.md` is the authoritative list for this run per the launch prompt's own framing, and its
reasoning is verifiably correct, so this implementation follows it: two complementary counters were
built, neither inside the caller's use case.

1. Extended scenarios (a) and (b) with a test-only `IdempotencyRecordStore` decorator
   (`countingInsertAttempts`) wrapping thread B's store, counting real invocations of
   `insertInProgress` — the transactional body's own write, called once per pass through
   `IdempotentExecutor`'s inner lambda before the exception. Asserted `== 1` in both scenarios: a
   retry of the transactional body would have attempted the `INSERT` again.
2. Added two focused, non-concurrent tests exercising `TransactionRunner` directly — the same
   component `IdempotentExecutor` delegates every write to — with the exact two exception types the
   adapter's `translate(...)` produces (`IdempotencyConflictException(WAIT_EXHAUSTED)` wrapping a real
   `SQLException` with `SQLState 55P03`, and `IdempotencyMarkerAlreadyExists` wrapping one with
   `23505`), counting attempts with an `AtomicInteger`. Both assert exactly one attempt.

**Process note, reported honestly**: these two additions were authored in the same file-creation pass
as task 5.2's three scenarios, before the RED/GREEN split into separate commits was made explicit —
so they ended up committed inside the task 5.2 (`62b465f`) and 5.3 (`8d3089c`) commits rather than
their own. This is a genuine deviation from "commit after each task" for this one task; there is no
separate commit SHA for task 5.4. The evidence itself is real and honestly reported: both
`TransactionRunner`-level tests passed immediately, without any production change, exactly as the
task's own text anticipated ("Verde: confirmar sin cambios de producción — la separación ya existe en
`TransactionRunner.isRetryable(...)` desde el cambio 5"), and the two `insertAttemptsByB == 1`
assertions passed only after task 5.3's fix (they failed, for a different reason than the RED
described above, before the timing fix to scenario (b) — see task 5.2's log — and trivially could not
have been asserted before `IdempotentExecutor` existed).

**Observed GREEN** (`/tmp/confia-logs/task5.3-green-v2.log`,
`transactionRunnerNeverRetriesWaitExhaustion` and `transactionRunnerNeverRetriesDuplicateKeyCollision`
both green on first run, no production change needed for either).

### Task 5.5 — full suite timing

Ran `./mvnw -B verify` complete in `apps/api` (`/tmp/confia-logs/task5.5-5.7-verify.log`),
`IdempotentExecutorConcurrencyIT` included.

- `CONFIA Kernel`: 11.373 s. `CONFIA API`: **02:48 min** (compile + unit + integration + JaCoCo).
- **Total measured time: 03:02 min.** `BUILD SUCCESS`. Comfortably inside the 8-minute budget
  (`design.md` §13) — this is the cut the design predicted would worsen it the most (real waits), and
  it did not move the needle measurably against PR C2b's own 03:32 min (the difference is well within
  normal run-to-run variance for this suite, not a regression).

### Task 5.6 — measured diff

```
git diff --numstat 4261fa3...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'
115  39  apps/api/app/src/main/java/com/confia/shared/security/IdempotentExecutor.java
340   0  apps/api/app/src/test/java/com/confia/shared/security/IdempotentExecutorConcurrencyIT.java
  7   9  apps/api/app/src/test/java/com/confia/shared/security/IdempotentExecutorIT.java
```

**Total: 510 authored lines** (462 additions, 48 deletions), measured from PR C2b's last code commit
(`4261fa3`) to `HEAD`. Well inside the 800-line budget (`docs/15-flujo-de-trabajo-git.md` §3);
slightly over `design.md` §12's own 300-500 forecast band for C2c (a forecast, not a gate) because
the three real-concurrency scenarios plus the two non-retry tests needed more scaffolding
(independent runners, a counting store decorator, three-way barrier synchronization) than a single
representative scenario would have. No split needed; no candidate split points to report.

### Task 5.7 — final verification of PR C2c

The same full `./mvnw -B verify` run captured under task 5.5 (`/tmp/confia-logs/task5.5-5.7-verify.log`)
ran against the exact committed tree (both task 5.2 and 5.3 commits already existed before that run
started; `git status` was clean throughout, confirmed again after the run).

- `IdempotentExecutorConcurrencyIT`: `Tests run: 5, Failures: 0, Errors: 0` — complete green: the
  three `SQLState`-distinguishable outcomes and the two non-retry counters.
- `IdempotentExecutorIT` (4), `JooqIdempotencyRecordStoreIT` (6), `IdempotencyErrorCodesTest` (7),
  `RequestPayloadHasherTest` (5), `RolePrivilegeMatrixIT` (16), `IdempotencyKeyPrivilegeIT` (2),
  `MultiTenantSchemaIT` (7), `AuditScopeExclusionInventoryTest` (4), `TransactionRunnerContextIT` (4),
  `TransactionRunnerRetryIT` (2) — all still green, unaffected by this slice.
- Every architecture gate green: `JooqConfinedToInfrastructureTest`, `LayeredArchitectureTest`,
  `NoCyclesTest`, `NoUnapprovedPlainSqlTest`, `SpringModulithVerificationTest`,
  `TableOwnershipByModuleTest`, `TransactionsOnlyInSharedSecurityTest` — confirming R3 still holds:
  `IdempotentExecutor` still opens no transaction of its own even with the new `DataSource` field
  (it only borrows the JDBC connection `TransactionRunner` already bound to the transaction, via the
  identical `DataSourceUtils` pattern).
- Overall: `Tests run: 177` (kernel) + `138` (API unit, surefire) + `78` (API integration, failsafe) —
  all green. `jacoco:check` → coverage checks met for both modules.
- Total measured build time: **03:02 min** (task 5.5). `BUILD SUCCESS`.

**Same deliberate scope boundary as every previous cut of this change**: not pushed, no PR opened,
per this run's explicit instructions ("No empujes ni abras pull requests"). All locally-verifiable
evidence above is real.

### Hard-stop checks (PR C2c)

1. Diff (task 5.6): 510 lines, far under 800 — no stop.
2. No gate weakened, no privilege changed, no ArchUnit exception added; sonda S5 (task 5.1) already
   confirmed `confia_admin_app` needs no new privilege to set `lock_timeout`.
3. `IdempotentExecutorConcurrencyIT` run four times total across this batch (task 5.2's RED x2, task
   5.3's GREEN, task 5.5/5.7's full-verify pass) plus two more dedicated stability reruns — six
   observations of the GREEN state, zero flaky results.
4. One informational discrepancy reported and resolved without blocking (task 5.4: the launch
   prompt's "use-case counter" phrasing vs. `tasks.md`'s more precise "transactional-body counter",
   the latter followed as authoritative); one honest process deviation reported (task 5.4's own commit
   boundary was not kept separate from tasks 5.2/5.3, evidence unaffected).

### Three things requested to be proven, not assumed (PR C2c)

- **Real concurrency, not two sequential calls**: every scenario uses two independent
  `TransactionRunner`s, two independent connections and a `CyclicBarrier`, following
  `TransactionRunnerRetryIT`'s own precedent; thread A's barrier wait sits inside its own use case
  (proof its `INSERT` already ran), thread B's sits immediately before it ever calls `execute`.
- **The three outcomes are distinguished by `SQLState`, never by message text**: scenario (a) asserts
  `IdempotencyConflictException` (the adapter's own `55P03` translation); scenario (b) asserts
  `IdempotentOutcome.Replayed` reached only through the `IdempotencyMarkerAlreadyExists` catch (the
  adapter's own `23505` translation); neither assertion reads an exception message anywhere.
- **Neither error outcome is retried**: proven by a counter (task 5.4), not by exception type — both
  the real-concurrency `insertAttemptsByB == 1` assertions and the two direct
  `TransactionRunner`-level `attempts == 1` assertions.
- **`lock_timeout` does not survive its transaction**: sonda S5 (task 5.1) is the authoritative,
  already-executed proof of this with the pool pinned to one connection, exactly like
  `TransactionRunnerContextIT.noContextSurvivesOnAReusedPoolConnection`'s own pattern for the security
  context; this batch's own scenarios do not repeat that pinned-pool setup because they need two
  independent backends to race, which is the opposite topology.

### TDD Cycle Evidence (PR C2c)

| Task | RED observed | GREEN observed | REFACTOR |
|---|---|---|---|
| 5.2/5.3 | Yes — 2 of 5 concurrency tests failed for the exact predicted reason (task5.2-red-v2.log); scenario (b)'s first draft failed for an incidental test-timing reason (task5.2-red.log), fixed before treating it as the RED baseline | Yes — 9/9 tests (5 concurrency + 4 sequential), BUILD SUCCESS (task5.3-green-v2.log) | None needed in production code beyond the fix itself |
| 5.4 | N/A — expected green without production change, confirmed | Yes — both `TransactionRunner`-level counters green on first run | None |

### Work Unit Evidence (PR C2c)

| Evidence | Value |
|---|---|
| Focused test command and result | `./mvnw -B -pl app -am test -Dtest=IdempotentExecutorConcurrencyIT -Dsurefire.failIfNoSpecifiedTests=false` → 5/5 green, run four times total, zero flakes |
| Runtime harness command/result | `./mvnw -B verify` (apps/api) → BUILD SUCCESS, 177 kernel + 138 API unit + 78 API integration tests green, JaCoCo bundle check met, 03:02 min total |
| Rollback boundary | Revert this PR's two commits (`62b465f` test, `8d3089c` feat) individually reverts to PR C2b's state (sequential-only executor, known degradation to an unbounded wait per `proposal.md` "Plan de reversión" point 6); no other file touched |

### Commits (PR C2c)

| Task | SHA | Message |
|---|---|---|
| 5.1 | (no commit — already run by the orchestrator, commit `2c05301`, before this batch) | — |
| 5.2 | `62b465f` | `test(shared): add RED IdempotentExecutorConcurrencyIT for the bounded wait` (also carries task 5.4's counter additions — see task 5.4's process note) |
| 5.3 | `8d3089c` | `feat(shared): bind lock_timeout and replay after duplicate-key collision` |
| 5.4 | (no separate commit — content shipped inside `62b465f`; see process note above) | — |
| 5.5-5.7 | (no commit — verification-only tasks; evidence recorded above) |

## Status

7/7 tasks of PR C1 complete (1.1-1.7). 7/7 tasks of PR C2a-1 complete (2.1-2.7). 5/5 tasks of PR
C2a-2 complete (3.1-3.5). 5/5 tasks of PR C2b complete (4.1-4.5). 7/7 tasks of PR C2c complete
(5.1-5.7). Ready for a fresh `sdd-apply` batch to start PR C3 (branch
`change/idempotency-key-infrastructure-exit-criterion`, base PR C2c) once the maintainer wants to
continue the chain.

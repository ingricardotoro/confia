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

## Status

7/7 tasks of PR C1 complete (1.1-1.7). 7/7 tasks of PR C2a-1 complete (2.1-2.7). 5/5 tasks of PR
C2a-2 complete (3.1-3.5). Ready for a fresh `sdd-apply` batch to start PR C2b (branch
`change/idempotency-key-infrastructure-executor`, base PR C2a-2) once the maintainer wants to
continue the chain.

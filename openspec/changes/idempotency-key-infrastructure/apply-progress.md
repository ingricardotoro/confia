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

## Hard-stop checks (none triggered)

1. Diff (task 1.6): 323 lines, far under 800 — no stop.
2. No gate weakened, no role given more than `docs/03` §6.1 allows.
3. No contradiction found between tasks/design/specs/ADR-0010 for this cut.
4. `MultiTenantSchemaIT` passed **unmodified** — no discrepancy to report.

## Three explicit verifications requested by the launch prompt

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

## Status

7/7 tasks of PR C1 complete (1.1-1.7). Ready for `sdd-archive` review of this cut, or for a fresh
`sdd-apply` batch to start PR C2a-1 (branch `change/idempotency-key-infrastructure-store`, base PR
C1) once the maintainer wants to continue the chain.

## TDD Cycle Evidence

| Task | RED observed | GREEN observed | REFACTOR |
|---|---|---|---|
| 1.1/1.2 | Yes — `relation "shared_idempotency_key" does not exist` (task1.1-red.log) | Yes — 18/18 tests, BUILD SUCCESS (task1.2-green.log) | None needed; migration and tests matched design on first pass |

## Work Unit Evidence (PR C1)

| Evidence | Value |
|---|---|
| Focused test command and result | `./mvnw -B -pl app -am test -Dtest=RolePrivilegeMatrixIT,IdempotencyKeyPrivilegeIT -Dsurefire.failIfNoSpecifiedTests=false` → 18/18 green |
| Runtime harness command/result | `./mvnw -B verify` (apps/api) → BUILD SUCCESS, 63 integration tests green, JaCoCo bundle check met, 03:11 min total |
| Rollback boundary | Revert this PR's two commits (`c78be2f`, `48500b5`); no Java consumer exists yet, so no other code depends on `shared_idempotency_key`. Per `design.md` §9 point 3 this PR cannot be reverted independently once PR C2a-1 exists and compiles against the generated jOOQ type |

## Commits

| Task | SHA | Message |
|---|---|---|
| 1.1 | `c78be2f` | `test(schema): add RED privilege gates for shared_idempotency_key` |
| 1.2 | `48500b5` | `feat(schema): create shared_idempotency_key table` |
| 1.3-1.7 | (no commit — verification-only tasks; evidence recorded above) |

# Background jobs: remaining tasks 2.6 to 4.3 (ODD)

Engram mirror: `odd/background-jobs-remaining-tasks/tasks` (project `confia`).

## Objective

Finish change 9 `background-jobs-with-db-scheduler` (tasks 2.6 to 4.3) under ODD instead of per-phase SDD agents.

## Problem and why

Slices 1 to 3a are merged (main `db73f83`). The owner chose ODD on 2026-10-27 to go faster. The approved spec, design and
`tasks.md` in `openspec/changes/background-jobs-with-db-scheduler/` remain the source of truth for each task, so the project rule
"no implementation without an approved spec" still holds.

## Constraints

- Each task follows its RED, GREEN, deliberate-break and closing steps in `tasks.md`. Merged migrations V1 to V10 are never edited.
- PRs over 800 effective lines (without `openspec/` and `odd/`) are split; tests never leave their code (`docs/15` §3).
- Every branch runs `./mvnw verify`. `./mvnw verify -Pmutation-gate` runs once per task, on the task's last branch (owner
  decision, 2026-10-27). Both use `JAVA_HOME` (Temurin 25) and `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT`, which
  is never committed.
- BI57 reference: plain verify on main `7849837` took 24 min 18 s on this machine (2026-10-27).
- Conventional commits, no AI attribution. No push, PR or merge without the owner.
- The change still ends with SDD archive, to merge the delta specs into `openspec/specs/`.
- TDD: on (project policy, `docs/06`). Runner: `./mvnw` from `apps/api`.
- Delivery strategy: stacked PRs to `main`, one branch per slice, as before.

## Checklist

- [x] T2.6 Slice 3b `03b-portal-scheduling-client`: portal DataSource and `JobScheduler` (D-N1, P6).
- [x] T3.1 Slice 4a `04-reset-issuance-scheduler`.
- [x] T3.2 Slice 4b `04-reset-issuance-handler` (split: 4b and 4c).
- [x] T3.3 Slice 5a-1 `05a-v11-purge-functions`.
- [ ] T3.4 Slice 5a-2 `05a-identity-purge-jobs`.
- [ ] T3.5 Slice 5a-3 `05a-adr-0025-and-retention-notes`.
- [ ] T3.6 Slice 5b-1 `05b-v12-purge-functions`.
- [ ] T3.7 Slice 5b-2 `05b-idempotency-and-backoff-jobs`.
- [ ] T3.8 Slice 6 `06-audit-chain-verification-cadences`.
- [ ] T4.1 Slice 7a `07a-queue-observability`.
- [ ] T4.2 Slice 7b `07b-closing-notes` (notes).
- [ ] T4.3 Slice 7b `07b-closing-notes` (closing).
- [ ] Archive the SDD change.

Hard order: 3.3 (V11) before 3.6 (V12).

## Progress

| Task | Route | Branch | Commit | Lines | Plain verify | Mutation verify | Review |
|---|---|---|---|---|---|---|---|
| T2.6 | delegated writer; parent ran both verifies after the writer's runs were killed (shell exit, then low memory) | `03b-portal-scheduling-client` | `c945478` | 390 | BUILD SUCCESS 19 min 17 s; Surefire 186 + 2 604, Failsafe 652 | BUILD SUCCESS 39 min 30 s; PIT kernel 192/194, app 871/919; JaCoCo met | granted; approved (reliability lens, lineage `review-832c56f64b018968`, acknowledged) |
| T3.1 | delegated writer; parent ran the mutation verify after the writer's run was reaped for low memory | `04a-reset-issuance-scheduler` | `8714325` | 491 | BUILD SUCCESS 20 min 4 s; Surefire 186 + 2 610, Failsafe 656 | BUILD SUCCESS 42 min 43 s; PIT kernel 192/194, app 871/919; JaCoCo met | granted; approved (reliability lens, lineage `review-0c6ba0db28ada475`, acknowledged) |
| T3.2 (4b) | delegated writer; builds on a copy outside OneDrive, see the T3.2 evidence | `04b-reset-issuance-handler` | `10cbd8a` | 779 | BUILD SUCCESS 21 min 3 s; Surefire 186 + 2 611, Failsafe 663 | not run (last branch of the task is 4c) | granted; approved together (reliability lens, lineage `review-c21140583d88afc7`, acknowledged) |
| T3.2 (4c) | delegated writer; builds on a copy outside OneDrive, see the T3.2 evidence | `04c-reset-issuance-double-execution-and-isolation` | `a8b93b1` | 113 | BUILD SUCCESS 23 min 50 s; Surefire 186 + 2 611, Failsafe 667 | BUILD SUCCESS 44 min 48 s; PIT kernel 192/194, app 871/919; JaCoCo met (identity.domain 95, PIT 80) | granted; approved together (reliability lens, lineage `review-c21140583d88afc7`, acknowledged) |
| T3.3 (5a-1) | delegated writer; parent added the UTC fix inline | `05a1-v11-purge-functions` | `04489e0`, `3d25139` | 577 | BUILD SUCCESS 12 min 56 s; Surefire 186 + 2 611, Failsafe 674 | not run (last branch of the task is 5a-1c) | earlier code approved twice (reliability lens, lineages `review-97b5fa12d1cc1d77` and `review-e27cce41c1db52d5`, acknowledged); the stack with the UTC fix approved (reliability lens, lineage `review-25ef682b35bc1ba4`, acknowledged); PRs #208, #209, #210 |
| T3.3 (5a-1b) | delegated writer | `05a1b-purge-function-behaviour` | `422d9a3` | 450 | BUILD SUCCESS 11 min 48 s; Surefire 186 + 2 611, Failsafe 694 | not run (last branch of the task is 5a-1c) | earlier code approved twice (reliability lens, lineages `review-97b5fa12d1cc1d77` and `review-e27cce41c1db52d5`, acknowledged); the stack with the UTC fix approved (reliability lens, lineage `review-25ef682b35bc1ba4`, acknowledged); PRs #208, #209, #210 |
| T3.3 (5a-1c) | delegated writer; parent ran the verifies | `05a1c-refresh-purge-and-rotation` | `bcabd7d`, `4ba3460` | 461 | BUILD SUCCESS 10 min 10 s; Surefire 186 + 2 611, Failsafe 704 | BUILD SUCCESS 27 min 47 s; PIT kernel 192/194, app 871/919; JaCoCo met | earlier code approved twice (reliability lens, lineages `review-97b5fa12d1cc1d77` and `review-e27cce41c1db52d5`, acknowledged); the stack with the UTC fix approved (reliability lens, lineage `review-25ef682b35bc1ba4`, acknowledged); PRs #208, #209, #210 |

T2.6 evidence:

- RED: `PortalContextStartupTest` failed with `NoSuchBeanDefinition ... javax.sql.DataSource`; with the three new PORTAL policy
  entries removed, `ProcessBeanIsolationTest` failed with `not in the allow-list`.
- Deliberate breaks, reverted and confirmed with `cmp`:
  - Importing `SharedPlatformConfiguration` in the portal fails `PortalContextStartupTest` (2) and `ProcessBeanIsolationTest` (3).
    It needed a runtime-generated `confia.crypto.column-master-key` (never recorded) to fail on assertions instead of at startup.
  - An extra `UPDATE` grant to `confia_portal_app`, simulated with `ALTER DEFAULT PRIVILEGES` in `create-test-roles.sql` because V10
    cannot be edited, fails `PortalSchedulingIT` and `RolePrivilegeMatrixIT` (8 failures, since the grant is broad).
- Review advisory findings (non-blocking, follow-up only): committed probe rows left by `PortalSchedulingIT`; its negative
  DML checks are scoped by task name only; the portal database health indicator is not proved; generic JDBC access in the
  portal context is not asserted absent.
- No new migration: V10 already grants the portal `SELECT` and `INSERT` on `scheduled_tasks`.

T3.2 evidence:

- Split by the seam named in `tasks.md`: the whole task measured 892 effective lines, over the 800 limit. Branch 4b holds the
  handler, the sender that fails explicitly, the worker wiring and every test except two; branch 4c (created from 4b) holds
  `PasswordResetIssuanceDoubleExecutionIT` and `PasswordResetIssuanceInstitutionIsolationIT`. Each passes on its own.
- Build environment: in-place builds under OneDrive failed deterministically at `testCompile` (`EnrollVersusVerifyConcurrencyIT.class`
  "used by another process", held by a process outside this run, probably the IDE or the antivirus). All focused and full runs
  were made on a one-way copy of the working tree outside OneDrive, with the same JDK and options. The copy has no `.git`.
- RED, observed before the last production line: `ProcessBeanIsolationTest` (worker), `JobPayloadAllowListTest` and
  `WorkerProductionWiringTest` failed on assertions once the worker registered the job; `UnavailableSenderLeavesNoTokenIT`
  failed on three assertions while `IssuePasswordResetToken` did not call `ensureAvailable()`: `expected: 0L` orphan tokens
  for the account below its limit, and the fourth attempt of the account at its limit completed ("Expecting code to raise a
  throwable") instead of failing. The first compile with the handler class missing was not observed, because the handler was
  written before the first run. The new `WORKER` entry `com.confia.shared.jobs` was seen failing first
  (`bean 'passwordResetIssuanceJob' from package 'com.confia.shared.jobs' - not in the allow-list`).
- Deliberate breaks, each reverted and confirmed with `cmp` against a copy kept outside the repository:
  - `ensureAvailable()` moved after the transaction: `UnavailableSenderLeavesNoTokenIT` fails 2 of 4 on assertions (orphan token).
  - `supersedeOpen` removed: both tests of `PasswordResetIssuanceDoubleExecutionIT` fail, but with
    `IntegrityConstraintViolationException` from `identity_password_reset_token_one_open_per_account`, not on an assertion. The
    database refuses the second open token, so "two open tokens" cannot be observed. The unique index is a second line of defence.
  - Handler run in another institution's context (temporary system property, never committed): both tests of
    `PasswordResetIssuanceInstitutionIsolationIT` fail on assertions (`no token in B`, `the task's institution`).
- The real executor was not run for the five attempts: the waits are 30, 60, 120 and 240 s. The test drives the real worker's
  `JobRunner` and `OneTimeJob` five times for an account below its limit and five for one at its limit, and asserts the retry
  policy values separately. The parked row itself is covered by `FailedExecutionIsParkedIT`.
- `WorkerHarness` is now public so the identity test can start the real worker; the audit log is append-only, so the new ITs
  read only the entries after their own start.
- Inactive account (I236): the schema has no active flag, so the test covers an account that no longer exists.
- The owner accepted these deviations on 2026-10-28: the build on a copy outside OneDrive, the `supersedeOpen` break failing on
  the unique index instead of an assertion, the five attempts driven without real waits, and I236 as a missing account.
- Review advisory finding (non-blocking, follow-up only): the handler Javadoc says two runs leave one open token, but in production
  the sender fails in `ensureAvailable()`, so no token is issued yet; reword it when a real sender exists.

T3.3 evidence:

- Builds now run in place in `C:\dev\confia`, outside OneDrive; plain verify takes 10 to 13 min instead of 20 to 24.
- Owner decision, 2026-10-28: fix the review warning `R3-floor-timezone-dependent` directly in `V11`, which is not merged yet.
  Commit `3d25139` on `05a1` adds `SET TimeZone = 'UTC'` to both functions; `05a1b` and `05a1c` were rebased onto it (local
  branches, never pushed). RED: `RolePrivilegeMatrixIT` failed on `settings of identity_purge_password_reset_tokens` once it
  expected `TimeZone=UTC`; GREEN with `V11SchemaDiffIT`. Every verify below ran after the fix.
- Deliberate breaks on `05a1c`, each reverted and confirmed with `cmp`:
  - `LEAST` floor removed from both functions: `RetentionPurgeFunctionsIT` (future cut-off and jOOQ routine, both subjects) and
    `RefreshTokenFamilyPurgeFunctionIT` (live family, 29 versus 31 days) fail.
  - Context check removed: `withoutAnInstitutionTheFunctionFailsAndDeletesNothing` fails for both subjects.
  - `EXECUTE` granted to `PUBLIC`: three tests of `RolePrivilegeMatrixIT` fail.
- Review advisory findings (non-blocking, follow-up only):
  - `R3-lockwait-probe-unscoped` (suggestion): `RefreshPurgeVersusRotationConcurrencyIT` detects the lock wait by query text over
    all backends; filter on the test session's `pg_backend_pid()`.
  - `R3-concurrency-test-hangs-on-failure` (warning): in the same test the pool closes before the lock-holding session rolls
    back, so an exception while the lock is held hangs the build instead of failing it; declare the pool first.
  - `R3-reset-batch-short-under-concurrency` (suggestion): two concurrent password-reset purges can return a short batch, which
    delays the rest to the next run; `FOR UPDATE SKIP LOCKED` in the subquery would keep batches full.
  - `R3-floor-timezone-dependent` (warning): fixed in `3d25139`, see above.
  - `R3-null-cutoff-silently-uses-floor` (suggestion): `LEAST` ignores NULL, so a NULL `p_cutoff` becomes the floor and a NULL
    `p_limit` becomes 1; refuse NULL or pin the behaviour with a test.

T3.1 evidence:

- RED: `test-compile` failed on the old port signature `schedule(InstitutionId, StaffAccountId)` and on the missing job type,
  job payload and adapter.
- Deliberate breaks, reverted and confirmed with `cmp`:
  - Scheduling outside the use case transaction, through a raw `SchedulerClient` over the plain `DataSource` in the test wiring,
    fails `RequestPasswordResetSchedulingIT` (the task survives a failing audit writer). A production `afterCommit` cannot
    show this, because it never runs after a rollback.
  - Deriving the instance id without `requestId` fails 2 of 4 tests in `JobPasswordResetIssuanceSchedulerTest`.
- `RequestPasswordReset` now computes `auditRequestId` before the account lookup, so the task and the audit entry share it.
- Review advisory findings (non-blocking, follow-up only): the idempotent-repeat check uses a no-op audit writer; the job type
  has no handler yet (task 3.2); `PasswordResetRequestTimingReportIT` does not use the real adapter.

## Next step

T3.4, slice 5a-2 `05a-identity-purge-jobs`, delegated to one writer, on branch `05a2-identity-purge-jobs` from `05a1c`.
In parallel: change 13 `design-system-foundations-and-a11y` (SDD explore and propose) in the worktree `C:\dev\confia-frontend`.

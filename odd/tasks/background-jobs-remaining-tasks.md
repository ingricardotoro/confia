# Background jobs: remaining tasks 2.6 to 4.3 (ODD)

Engram mirror: `odd/background-jobs-remaining-tasks/tasks` (project `confia`).

## Objective

Finish change 9 `background-jobs-with-db-scheduler` (tasks 2.6 to 4.3) under ODD instead of per-phase SDD agents.

## Problem and why

Slices 1 to 3a are merged (main `db73f83`). The owner chose ODD on 2026-10-27 to go faster. The approved spec, design and
`tasks.md` in `openspec/changes/background-jobs-with-db-scheduler/` remain the source of truth for each task, so the project rule
"no implementation without an approved spec" still holds.

## Constraints

- Each task follows its RED, GREEN, deliberate-break and closing steps in `tasks.md`. Merged migrations V1 to V11 are never edited.
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
- [x] T3.4 Slice 5a-2 `05a-identity-purge-jobs` (split: 5a-2a, 5a-2 and 5a-2b).
- [x] T3.5 Slice 5a-3 `05a-adr-0025-and-retention-notes`.
- [x] T3.6 Slice 5b-1 `05b-v12-purge-functions` (split: 5b-1 and 5b-1b).
- [x] T3.7 Slice 5b-2 `05b-idempotency-and-backoff-jobs` (split: 5b-2 and 5b-2b).
- [x] T3.8 Slice 6 `06-audit-chain-verification-cadences` (split: 6, 6b and 6c).
- [x] T3.9 Slice 6d `06d-purge-null-arguments`: `V13` rejects a NULL `p_cutoff` or `p_limit` in the five purge functions (owner decision 2026-11-01). Before T4.1. Done: PR #240 merged into `main` `cf46452`.
- [x] T4.1 Slice 7a `07a-queue-observability` (split: 7a-1 and 7a-2). PRs #241 and #242 merged into `main` `e9f7c0e`.
- [x] T4.2 Slice 7b `07b-closing-notes` (notes).
- [x] T4.3 Slice 7c `07c-closing-traceability` (closing): committed as `bffac4f`, then corrected after its review by `test(jobs): make the closing guards prove what they claim`; 789 effective lines against `a9e5876`.
- [ ] Archive the SDD change.

Hard order: 3.3 (V11) before 3.6 (V12); 3.9 (V13) after 3.8 and before 4.1.

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
| T3.4 (5a-2a) | delegated writer; owner decision option B (2026-10-28) | `05a2a-operational-event-log` | `6ba0082`; merged by `3e93e1b` | 184 | BUILD SUCCESS 9 min 3 s; Surefire 186 + 2 617, Failsafe 704 | not run (last branch of the task is 5a-2b) | PR #212 |
| T3.4 (5a-2) | delegated writer | `05a2-identity-purge-jobs` | `9e2a09d`; merged by `b400eb6` | 799 | BUILD SUCCESS 9 min 36 s; Surefire 186 + 2 619, Failsafe 715 | not run (last branch of the task is 5a-2b) | PR #213 |
| T3.4 (5a-2b) | delegated writer | `05a2b-refresh-successor-clearing` | `7b38d07` plus the evidence commit `226350a`; merged by `2936620` | 539 | BUILD SUCCESS 9 min 8 s; Surefire 186 + 2 619, Failsafe 726 | BUILD SUCCESS 28 min 51 s; PIT kernel 192/194, app 872/919; JaCoCo met | PR #214 |
| T3.6 (5b-1) | delegated writer | `05b1-v12-purge-functions` | `ae233f0` | 634 | BUILD SUCCESS 13 min 7 s; Surefire 186 + 2 623, Failsafe 760 | not run (last branch of the task is 5b-1b) | pending |
| T3.6 (5b-1b) | delegated writer | `05b1b-backoff-purge-versus-login` | `5f5108e` | 191 | BUILD SUCCESS 10 min 40 s; Surefire 186 + 2 623, Failsafe 762 | BUILD SUCCESS 31 min 49 s; PIT kernel 192/194, app 872/919; JaCoCo met | pending |
| T3.7 (5b-2) | delegated writer | `05b2-idempotency-and-backoff-jobs` | `f172d58` | 641 | BUILD SUCCESS 19 min 2 s; Surefire 186 + 2 631, Failsafe 766 | not run (last branch of the task is 5b-2b) | pending |
| T3.7 (5b-2b) | delegated writer | `05b2b-backoff-purge-jobs` | `8a19935` plus the evidence commit | 515 | BUILD SUCCESS 24 min 20 s; Surefire 186 + 2 631, Failsafe 776 | BUILD SUCCESS 48 min 27 s; PIT kernel 192/194, app 872/919; JaCoCo met | pending |
| T3.8 (6) | delegated writer | `06-audit-chain-verification-cadences` | `3996209` | 385 | BUILD SUCCESS 17 min 53 s; Surefire 186 + 2 640, Failsafe 778 | not run (last branch of the task is 6c) | approved earlier; PR #236, merged to `main` `2f44bbb` |
| T3.8 (6b) | delegated writer | `06b-audit-chain-jobs` | `66eb21e` | 788 | BUILD SUCCESS 20 min 7 s; Surefire 186 + 2 668, Failsafe 778 | not run (last branch of the task is 6c) | approved earlier; PR #237, merged to `main` `2f44bbb` |
| T3.8 (6c) | delegated writer | `06c-audit-chain-job-integration` | `84b01a0` plus the evidence commit | 366 | BUILD SUCCESS 21 min 17 s; Surefire 186 + 2 668, Failsafe 785 | done: first pending because the owner runs it in a terminal (it was reaped twice inside Claude Code); PIT targets now include `shared.audit` (`da7bca1`); 06c final tip `4487f61`, 498 lines vs 06b; verify BUILD SUCCESS 29 min 52 s, Surefire 186 + 2 671, Failsafe 785; mutation gate run by the owner on `94b3b9a` plus the worker IT fix (see the T3.8 evidence); 06c final tip `d82ffb7`: verify BUILD SUCCESS 18 min 33 s, Surefire 186 + 2 673, Failsafe 785; mutation gate not rerun on `d82ffb7` by owner decision (option B); the PR CI mutation job passed | approved (lineage `review-1ba1ea3e148b3fb6`, acknowledged); PR #238, merged to `main` `2f44bbb` |
| T3.9 (6d) | delegated writer | `06d-purge-null-arguments` | `03b7e2f` `feat(db): reject null cutoff and limit arguments in the purge functions`; PR #240 merged into `main` `cf46452` | 594 | BUILD SUCCESS 20 min 55 s; Surefire 186 + 2 673, Failsafe 805 | the PR CI mutation job passed | approved again after the rebase (reliability lens, lineage `review-630f816fc5369ab3`); PR #240, merged to `main` `cf46452` |
| T4.1 (7a-1) | delegated writer | `07a-queue-observability` | `07f3e0d` | 723 | BUILD SUCCESS 22 min 14 s; Surefire 186 + 2 708, Failsafe 805 | not run (last branch of the task is 7a-2) | approved, acknowledged; PR #241 merged |
| T4.1 (7a-2) | delegated writer | `07a2-queue-observation-wiring` | `f1ce08d` and `f88156f` | 800 | BUILD SUCCESS 22 min 56 s, on the same code before the split; Surefire 186 + 2 723, Failsafe 819 | PR CI passed | approved, both commits, acknowledged; PR #242 merged into `main` `e9f7c0e` |
| T4.2 (7b) | delegated writer; the parent closed the task after the writer stopped waiting for the build | `07b-closing-notes` | `ee0d0a4`; review corrections `a9e5876` (16 lines) | 423 | BUILD SUCCESS 19 min 13 s; Surefire 186 + 2 729, Failsafe 820 | pending CI | approved; three non-blocking findings corrected in `a9e5876` |
| T4.3 (7b/7c) | delegated writer; no commit (size gate) | `07b-closing-notes` | not committed: the tree holds the work, held for the split | 789 vs `a9e5876` (656 in `bffac4f` + 133 of the review correction; 433 + 789 = 1 222 vs `e9f7c0e`) | BUILD SUCCESS 25 min 20 s (over the 15 minute budget); Surefire 186 + 2 750, Failsafe 821 | pending CI | pending |

Main CI: PR #211 (merge `972beae`) fixed the failing CI on `main` before PRs #212 to #214 were merged.

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

Decide the split of `07b-closing-notes` (1 089 effective lines against `e9f7c0e`, over the 800 limit): PR 7b with T4.2 and its correction (`ee0d0a4`, `a9e5876`, 433 lines) and a stacked PR 7c with T4.3 (656 lines), whose commit is `docs(roadmap): mark exit criterion 5 of phase 0 and close the background jobs change`. Then the PRs; then, after the last merge, the owner's `./mvnw verify -Pmutation-gate` on `main`, `sdd-verify` and `sdd-archive`. The archive must also correct the delta spec of `audit-trail`: the failed verification entry has outcome `error`, not `failure` (found in T3.8), and the approved list of plain SQL holds three classes, not only the queue reader. ADR-0026 is reserved by frontend change 13a (Storybook).

(Superseded 2026-11-01: "T4.3, slice 7b `07b-closing-notes`, then `sdd-verify` and `sdd-archive`. T4.1 is done: PRs #241 and #242 merged into `main` `e9f7c0e`. T3.9 is done: PR #240 merged into `main` `cf46452`.")

T4.3 evidence:
- Review correction of `bffac4f` (five findings, all in test code): traceability counts a mention only in a real test (`@DisplayName`, or Javadoc of the class or of a test method after the word "scenario" or "spec"), BJ37 goes red without `ExitCriterion5EndToEndIT`; the 241 scenarios move from 231 covered / 8 partial / 2 uncovered to 228 / 11 / 2 (BI67, BI68, BI69 partial); BJ39 guard now covers `Public`, `DefaultCatalog` and `Tables` (no `Tables` class exists: `globalObjectReferences=false`); the rollback exception is identified by message; sources are keyed by path (no name collision exists today); this row said 650, it is 656 for `bffac4f` and 789 with the correction. Full `./mvnw -B verify`: BUILD SUCCESS 25:20 min, Surefire 186 + 2 750, Failsafe 821. The rename of one test method came after the full verify and was confirmed by a focused `JobsChangeTraceabilityTest` run only (6 tests, 0 failures). Details in `apply-progress.md`.
- Route: delegated writer, one work unit. First a separate commit `a9e5876` `docs(sdd): correct the job notes after their review` for the three non-blocking findings of the 4.2 review, each checked against the code: (1) the `WorkerApplication` source was resolved against the working directory in `DocumentationNotesTest`; true, it now starts from `repoRoot()` (`fixture()` still reads `src/test/resources` from the working directory, left as it was); (2) the manual `UPDATE` of `docs/07` contradicts the original comment "Nobody writes to the task table by hand"; true, one sentence in the new note names it as the only exception, the original text is untouched; (3) "in both cases" a failing institution does not stop the next one; true that it was wrong, per-institution occurrences belong to the seven recurring jobs, the one-time task is one execution that retries and parks, and the note says so. Two assertions in `DocumentationNotesTest` pin (2) and (3).
- Traceability: `JobsChangeTraceabilityTest` rather than a checklist, because a checklist cannot fail. The mapping is in `apply-progress.md` (241 scenarios: 231 covered, 8 partial, 2 without a test). Uncovered: "Se agrega Hibernate" and "Sin dependencias prohibidas" (a maven-enforcer rule, no test). Partial: I240 (used tokens are not seeded), I241 ("still accepted"), I264 (privilege catalogue, no attempted DELETE), BJ08, BI103, BI60, the Docker scenario and BJ37 (the scheduling side is the real client over the test pool, not the booted administrative context).
- RED, observed with the two new test classes set aside (the state before 4.3): 16 tests, 2 failures. `docs09MarksExitCriterion5OfPhase0WithADatedNoteThatNamesItsTests:172 [criterion 5 is checked and its text is unchanged] Expecting actual: -1 to be greater than or equal to: 0`, and `everyScenarioWithAnIdentifierIsNamedByATestOrListedWithTheTestThatCoversIt:122 Expecting empty but was: ["BJ38", "BJ39", "BJ40"]`. BJ37 was not listed because a Javadoc of the docs/09 test already named it (a loose mention); AT17 was already named by `RecurringJobCalendarTest`. The absence and end-to-end tests passed the first time they ran: they are guards, each with a fixture that fails.
- GREEN: `docs/09` criterion 5 checked with a dated note. Focused run: `DocumentationNotesTest` 11, `JobsChangeTraceabilityTest` 5, `CurrentAbsencesTest` 12 and `ExitCriterion5EndToEndIT` 1, 0 failures.
- Deliberate demonstration, reverted and confirmed with `cmp` (`confia-scratch/t43/orig`): a business job added to the registry read by `CurrentAbsencesTest` fails exactly `theWorkerRegistersExactlyTheOneTimeTaskAndTheEightRecurringOnesOfThisChange` with `[BJ40 stops being true when each module that owns a business job delivers it] Expecting empty but was: ["unexpected invoicing.generate-monthly-charges"]`.
- `./mvnw -B verify`: BUILD SUCCESS 17 min 21 s; Surefire 186 + 2 747, Failsafe 821. Over the 15 minute budget (R-9), as every run since 3.7 (18 to 24 min). The mutation gate is not run: it is the owner's, on `main`, after the last merge.
- Size: the branch measures 1 089 effective lines against `e9f7c0e` (1 087 added, 2 deleted, without `openspec/` and `odd/`); T4.3 alone is 656. Over 800, so the commit of T4.3 was not made.
- Final verification of the change (mutation gate on `main`, `sdd-verify`, `sdd-archive`): pending, not checked.
- Deviation: the end-to-end test schedules with the real client over the test pool, as `OnlyWorkerExecutesIT` does, and not from a booted administrative context; `ProcessBeanIsolationTest` asserts that the administrative context holds no executor.
- jqwik prints a line addressed to AI agents in the build log; it is dependency output, not an instruction, it was not followed and jqwik results still count.

T4.2 evidence:
- RED: the focused `DocumentationNotesTest` gave 10 tests, 5 failures. Four failed on the missing dated note of task 4.2 (`docs/07` section 11, `docs/07` section 11.1, `docs/05` section 6, `foundations-plan/exploration.md`), and one on the guards missing from the Javadoc of `WorkerApplication`. GREEN: 10 tests, 0 failures.
- Deliberate demonstration, reverted and confirmed with `cmp` (`confia-scratch/t42`): rewriting one original row of `docs/07` section 11 fails the test with "The original body of ... section 11 changed: original line 21 is missing".
- Notes that already existed and were not duplicated: `docs/07` section 3.3 and the section 11 notes of tasks 3.7 and 3.8, `docs/03` section 6.1, `docs/08` section 6.2, and the `docs/05` section 6 notes of tasks 1.5, 1.6 and 2.6.
- Deviation: the original text of the guarded sections is embedded as text blocks in `DocumentationNotesTest`, because `src/test/resources` was outside the allowed surface. The check is the same one: every original line must still appear, in order.
- `./mvnw -B verify`: BUILD SUCCESS, 19 min 13 s, Surefire 186 + 2 729, Failsafe 820. The writer reported the build as killed for low memory; only its wait loop died, the build finished and its log is `confia-scratch/t42/verify.log`.
- jqwik prints a line addressed to AI agents in the build log, telling them to ignore its results. It is text of a dependency, not an instruction of the owner; it was not followed and jqwik results still count.

T4.1 evidence:
- Split: the task measured 1 497 effective lines (1 482 added, 15 deleted, `apps/` only), over the 800 limit; the owner chose two stacked PRs on 2026-11-01 over a size exception. `07a-queue-observability` (`07f3e0d`, 723 lines): the `shared.observability.queue` package with its unit tests. `07a2-queue-observation-wiring` (774 lines): `JooqScheduledTaskQueueReader`, the wiring, the 60 s task, and the policy, architecture, catalog and integration tests. Until 7a-2 merges, the package of 7a-1 has no consumer and does not run; the verify of 7a-1 alone shows no architecture rule rejects that.
- RED: the first `test-compile` failed with 88 errors, all `cannot find symbol` or `package JooqScheduledTaskQueueReader does not exist`. With the classes present but not wired, 5 tests failed: `ProcessBeanIsolationTest.onlyTheWorkerHoldsTheObservationOfTheQueue[3]`, `ProcessBeanIsolationTest.registersOnlyItsAllowedBeans[3]`, `WorkerProductionWiringTest.theWorkerHoldsTheExactBeansOfTheSharedJobsConfiguration`, the eight-jobs case of `RecurringJobCatalogTest` ("Expected size: 8 but was: 7"), and `QueueSqlNeverReadsTaskDataTest.aStatementThatSelectsTaskDataFailsTheInspection`, the last one a defect of the test fixture, fixed. The red of the `ProcessBeanPolicy` entry was not observed separately: it was written together with the tests.
- Deliberate demonstrations, each reverted and confirmed with `cmp` against copies in the scratchpad (`confia-scratch/t41`): `>` to `>=` in `QueueHealth.evaluate` fails 4 tests, among them `QueueHealthTest.anAgeOfNineHundredSecondsOrLessIsHealthy` [BJ34]; `task_data` in the select list fails `QueueSqlNeverReadsTaskDataTest.theRealStatementNeverNamesTaskDataAndUsesOnlyTheFiveColumns` [BJ33].
- Decisions applied: `SharedWorkerJobsConfiguration` reads the heartbeat interval and the missed-heartbeats limit from `Environment`, with the library defaults when a key is absent, because only `shared.jobs.infrastructure` may depend on the scheduling library. `QueueObservation` publishes once per occurrence, not per institution (BJ68).
- Beyond the work order: `QueueSnapshotTest`, `QueueObservationTest`, `QueueObservationOnWorkerIT` and two cases in `NoUnapprovedPlainSqlTest`.
- The declared limit (alert on the absence of `queue_state` for more than 5 minutes) is in the Javadoc of `QueueObservation`, for the note of T4.2.
- Review of 7a-2 (approved) warned that no test proved the production supplier of recurring names. `QueueObservationOnWorkerIT.theProductionReaderCountsEveryFailingRecurringJobOfTheCatalogAsFailed` now does (owner decision, 2026-11-01). It passed at once on the correct code, so it is a regression guard. Demonstration: `.limit(0)` in the supplier fails it with "Expected size: 7 but was: 0"; reverted and confirmed with `cmp`. Focused run: 2 tests, 0 failures. 7a-2 is 800 effective lines with it, at the limit and not over it. The full verify of this commit is the PR CI.
- Mutation gate: not run locally; the mutation run of the PR CI measures it, as in T3.8 and T3.9.

T3.9 evidence:

- Route: delegated writer, one work unit, no split: 594 effective lines (additions plus deletions, without `openspec/` and `odd/`, as `git show --numstat
  03b7e2f` reports them): V13 284, `V13SchemaDiffIT` 141, `RetentionPurgeFunctionsIT` 92 (88 added, 4 deleted),
  `RefreshTokenFamilyPurgeFunctionIT` 68 (67 added, 1 deleted), ADR-0025 9. An earlier version of this line said 598 with 13 lines of ADR-0025; the
  ADR note is 9 lines, so the total is 594. Over the 400 to 550 estimate
  because V13 restates the five bodies in full; under the 800 PR limit.
- RED, observed with V13 absent (focused run of the three ITs: 78 tests, 17 failures, 3 errors). `aNullCutoffFailsAndDeletesNothing` and
  `aNullLimitFailsAndDeletesNothingNotEvenTheOneRowABatchOfOneWouldTake`, all five subjects, and the two new refresh cases failed with
  `[a null cut-off must not become the floor and purge: the call must fail, not succeed] Expecting actual not to be null` (the call
  succeeded and deleted). `theArgumentCheckComesBeforeTheContextAndTheActorChecks`, all five, failed with `Expecting actual: "ERROR:
  confia_purge_context_missing ..." to contain: "confia_purge_argument_missing"`. `V13SchemaDiffIT`, three errors: `FlywayException: No
  migration with a target version 13 could be found`.
- GREEN: `V13__reject_null_purge_arguments.sql` built from the V11 and V12 function texts with only the two checks inserted after `BEGIN`
  and `CREATE` turned into `CREATE OR REPLACE`; V12's repeated condition on the deleted row is kept. Focused run with `V11SchemaDiffIT`,
  `V12SchemaDiffIT`, `RolePrivilegeMatrixIT` and `IdempotencyKeyPrivilegeIT`: 133 tests, 0 failures (the BUILD FAILURE of a focused run is
  the JaCoCo check with no unit tests, as before).
- Deliberate break, one run: `p_cutoff` check removed from `identity_purge_login_backoff`, `p_limit` check removed from
  `shared_purge_idempotency_keys`, and every `REVOKE`/`GRANT` removed from V13. `RetentionPurgeFunctionsIT` failed 4 of 66, exactly
  `aNullCutoffFailsAndDeletesNothing[4]` and `theArgumentCheckComesBefore...[4]` (login backoff) and `aNullLimitFails...[3]` and
  `theArgumentCheckComesBefore...[3]` (idempotency keys), each `Expecting actual not to be null` or the context-missing message.
  `V13SchemaDiffIT` (owner, definer, config and ACL equal before and after V13) and `RolePrivilegeMatrixIT` stayed green without any
  `REVOKE`/`GRANT`: `CREATE OR REPLACE` keeps owner and privileges, as the PostgreSQL documentation states. Reverted and confirmed with `cmp`.
- Decisions: the nullable call types its parameters (`?::timestamptz, ?::integer`) and uses `select *` so the scalar and the two `OUT`
  columns share one helper; `RolePrivilegeMatrixIT` was not changed, it already asserts owner, `prosecdef`, `proconfig` and `EXECUTE` of
  the five on the current schema. The generated jOOQ routines keep their signatures and types; only the comment text, which comes from
  `COMMENT ON FUNCTION`, changed (diffed against a copy of `target/generated-sources/jooq` taken before the build). ADR-0025 had no
  "Notas fechadas" section: it was created at the end, as in ADR-0005. `R3-null-cutoff-silently-uses-floor` (T3.3 evidence) and the
  "kept from V11" note of T3.6 are closed by V13.
- `./mvnw -B verify`: BUILD SUCCESS, 20:55 min; Surefire 186 + 2 673, Failsafe 805 (785 plus the 20 new cases), 0 failures; JaCoCo met.

T3.8 evidence:
- Mutation gate on 06c (`94b3b9a` plus the worker IT fix), run by the owner in PowerShell outside Claude Code on 2026-10-31:
  BUILD SUCCESS in 57 min 4 s. From `mutations.xml`: kernel 192/194 (99 %); app 1 348/1 445 (93 %, 16 without coverage, test
  strength 94 %); `shared.audit` 184/186 (98.9 %, killed plus timed out); `shared.security` without `token` and `csrf` 293/340
  (86.2 %). All above the 80 threshold.
- Review of 06c (approved) flagged `AuditChainVerificationOnWorkerIT` as order-dependent: each cadence appends its result entry to
  CLEAN's chain, so the second walk may count the first one's entry. The assertion now requires exactly the two scopes, counts of
  3 or 4, and at least one count of 3 (the first walk sees exactly the seeded rows). Focused run: 1 test, 0 failures.


- Split: the whole task measured 1 539 effective lines (additions plus deletions, without `openspec/` and `odd/`; docs/07 included), over the 800
  limit. Seam named in `tasks.md` ("`verifySince` con su lector (unidad) / tareas recurrentes y asientos"), then the integration tests last, as
  in 3.2: `06-audit-chain-verification-cadences` (`3996209`, 385 lines: `verifySince`, `firstAtOrAfter`, `JooqAuditLogReader`, the window cases of
  `DefaultAuditChainVerifierTest`, two real-database cases in `AuditChainVerifierIT`, `AuditChainRows`), `06b-audit-chain-jobs` (`66eb21e`, 788
  lines: `AuditChainVerificationJobs`, `AuditChainBrokenException`, `OperationalEventLog.info/error/warn(details)`, the wiring, the unit tests, the
  `RecurringJobCalendarTest` case, the docs/07 note) and `06c-audit-chain-job-integration` (`84b01a0`, 366 lines: `AuditChainVerificationJobsIT`,
  `AuditChainVerificationOnWorkerIT`). Each passes on its own; no comment, Javadoc or assertion was cut. Increments are measured against the
  previous branch of the stack; 06b is 13 lines from the limit.
- RED: the first `test-compile` failed with `cannot find symbol` on `AuditChainVerificationJobs`, `AuditChainBrokenException`,
  `walkSince`, `AuditChainVerificationJobs.dailyWindowStart`, and `method does not override or implement a method from a supertype` on
  `firstAtOrAfter` and `verifySince`. The `ProcessBeanPolicy` entry for `com.confia.shared.audit` and the exact bean list of
  `WorkerProductionWiringTest` were written before the beans, but their own red was not observed (the compile failed first).
- Decisions applied: D-N2 (calendar-month window, from 00:00 on the first day of the previous month in Tegucigalpa; the month is the one of
  Tegucigalpa, not of UTC, tested at 03:00 UTC on 1 November). A recurring job is never retried, also the weekly one: the divergence is raised
  after its entry and its `ERROR` are written, so the occurrence stays failed and the job is rescheduled to its next occurrence (proved on the
  real worker: `consecutive_failures` 1 and the next execution at the calendar instant, not seconds later).
  `OperationalEventLog` gained `info`, `error` and `warn` with closed details (`Long`, `Integer` or a word of at most 40 letters, digits and
  underscores, under a closed name that is not `runId`, `task` or `institution`), so the events carry the scope, the rows and the first divergent
  id and still no free text. `ProcessBeanPolicy.WORKER` admits `com.confia.shared.audit`.
- Deviation, `outcome`: the spec says the failed verification entry has result `failure`, but `shared_audit_log_outcome_chk` (V2) admits only
  `success`, `denied` and `error` (first run: `violates check constraint "shared_audit_log_outcome_chk"`). The entry uses `error`
  (`AuditChainVerificationJobs.FAILED_OUTCOME`); no migration was written. The spec text should say `error` at archive.
- Deviation, mutation gate: `app/pom.xml` PIT `targetClasses` and `targetTests` do not include `com.confia.shared.audit.*`, and the file is outside
  the surface of this task. Without that change the gate does not measure the closing criterion "PIT en 80 sobre `shared.audit`". The owner decides
  and runs `-Pmutation-gate` in a terminal.
- Deliberate demonstrations, each reverted and confirmed with `cmp` against copies in the scratchpad (`confia-scratch/t38`):
  - Window start as "now minus 30 days": `AuditChainDailyWindowTest` fails 12 of 13 [AT23].
  - `verifySince` starting from the hash of the previous row instead of the stored `prev_hash`:
    `theLinkAtTheBorderIsNotVerifiedAndTheWalkStartsFromTheStoredPreviousHash` fails, the only failure of 15 [AT25].
  - Entry written inside the transaction of the walk (the cadence wrapped in one `runner.execute`, which joins the nested ones):
    `theEntryIsWrittenAfterTheWalkInATransactionOfItsOwn` fails, the only failure of 9 [AT31]. The real-database `AuditChainVerificationJobsIT`
    version of the same test (it compares `txid_current()` of the walk and of the entry) was not run against the break.
  - A first `cmp` of the 6b copy failed on line endings (the copy was taken before the checkout converted them); the break was not applied that
    time, and was applied only after taking a new copy of the committed file.
- Real-database seeding: `AuditChainRows` inserts rows as the superuser with an explicit `occurred_at` (the chaining trigger still chains them;
  it needs `app.institution_id` set in the session because it writes `shared_audit_chain_head` under row-level security) and alters one with
  `session_replication_role = replica`, like `AuditLogSuperuserTamper`.
- Not proved by a real fault: AT16's technical failure of the verifier (unit test: no entry, no event, rollback, exception propagates); the
  not-retried behaviour itself is the generic one of `RecurringFailureStateIT`, and `AuditChainVerificationOnWorkerIT` shows it for the divergence.
- Operational note: `git stash push -u` failed halfway on this machine (`failed to remove apps/admin-web/: Permission denied`) after saving the
  stash; the tracked changes were restored from the stash with `git checkout -- .` and `git stash pop`, with no loss.
- `./mvnw -B verify` on each branch: see the progress table. The mutation gate is pending (see above).

T3.7 evidence:
- Owner decision 2026-10-31: PIT widened to `com.confia.shared.security.*` (targetClasses and targetTests in `apps/api/app/pom.xml`),
  as task 3.7 asks, because `RetentionPurgeBatches` moved there and was not mutation-tested. The mutation gate was run by the owner
  in a PowerShell terminal outside Claude Code (two earlier runs inside it were reaped for low memory). Figures read from
  `mutations.xml`: kernel 192/194 (99 %); app 1 163/1 259 (92 %, 18 without coverage, test strength 94 %), up from 919 mutants;
  `shared.security` without `token` and `csrf` 291/340 (85.6 %, killed plus timed out). All above the 80 threshold.
- Follow-ups on 05b2b: `package-info.java` of `shared/observability/events` now names `com.confia.shared.security.RetentionPurgeBatches`
  (`57853cc`); `JdbcStubTransactions` moved to `com.confia.support` so shared tests no longer import a module helper (`0f6c82d`).


- Split: the whole task measured 1 156 effective lines (additions plus deletions, without `openspec/` and `odd/`; docs/07 included), over
  the 800 limit. `tasks.md` names no seam for 3.7 (only the one between 3.6 and 3.7), so the V11 and 3.4 precedent was followed, whole files
  only: `05b2-idempotency-and-backoff-jobs` (`f172d58`, 641 lines: `RetentionPurgeBatches` moved to `shared.security`,
  `PurgeExpiredIdempotencyKeys`, `IdempotencyRecordStore.purgeExpired`, its adapter, `SharedWorkerJobsConfiguration` with its
  `package-info`, the `WorkerApplication` import, `ProcessBeanPolicy`, the shared-beans test, `PurgeExpiredIdempotencyKeysTest`,
  `IdempotencyPurgeJobsIT`, `PurgeProbeRows`, the two fakes of the store) and `05b2b-backoff-purge-jobs` (`8a19935`, 515 lines:
  `PurgeStaleBackoff`, the two port methods and adapter methods, the two recurring jobs, `IdentityBackoffPurgeJobsIT`, the four-purge
  `IdentityPurgeJobsOnWorkerIT`, the five-purge `RecurringJobCatalogTest`, `RecurringJobPerInstitutionIT`, the identity beans in
  `WorkerProductionWiringTest`, the docs/07 note). No comment, Javadoc or assertion was cut. Each branch passes on its own.
- RED: the first `test-compile` failed on `PurgeStaleBackoff`, `PurgeExpiredIdempotencyKeys`, `SharedWorkerJobsConfiguration`,
  `IdempotencyRecordStore.purgeExpired` and the two new port methods (cannot find symbol / does not override). The `ProcessBeanPolicy`
  entry for `shared.infrastructure.wiring` was written before the configuration, but its red (non-vacuity) was not observed on its own:
  the first run was already after the configuration existed. The first GREEN run failed `SpringModulithVerificationTest` (bootstrap
  depends on a non-exposed type of `shared`), fixed with `@NamedInterface` on `shared.infrastructure.wiring`.
- Decisions applied: the worker gets `SharedWorkerJobsConfiguration` only through the `@Import` of `WorkerApplication` (ADR-0024); the
  batch loop moved to `shared.security` (public, behaviour and Javadoc kept) because `shared` cannot import `identity`; the new adapter
  methods (`purgeExpired`, `purgeLoginBackoff`, `purgeMfaTotpBackoff`) take no `InstitutionId`: the functions read it from the
  transaction context, and a test proves a purge in one context leaves another institution's expired rows. The older
  `purgePasswordResetTokens` and `purgeRefreshTokenFamilies` still take and ignore it (follow-up: drop it there too).
- Deliberate demonstrations, each reverted and confirmed with `cmp` against copies in the scratchpad (`confia-scratch/t37`):
  - Batch repetition removed from `RetentionPurgeBatches.repeat` (returns after the first batch): `PurgeExpiredIdempotencyKeysTest`
    fails 4 of 7 (the three-batch case, the 5 000 border, the 200 batches and the 200th short batch) [I274]. The integration test was
    not reached, because Surefire failed first.
  - Entry omitted when the count is zero in `PurgeExpiredIdempotencyKeys`: `aBatchThatFoundNothingStillLeavesItsEntryWithCountZero...`
    fails 1 of 7 [AT19].
- Focused: 37 unit (Modulith, process isolation, wiring) and 61 integration tests (the new ITs, the 3.4 ITs, the store ITs): 0 failures
  (JaCoCo failure of the focused run expected).
- `RecurringJobPerInstitutionIT` uses a real fault for BJ65: a `BEFORE DELETE` trigger refuses the deletion for one institution only
  (created and dropped by the test), so the real purge fails there and the other institution is purged; `consecutive_failures` is 1.
- `RecurringJobCalendarTest` was not edited: the five purge calendars (BJ62) are asserted in `RecurringJobCatalogTest`, which already
  held the identity ones against the real worker registry.
- Open points, closed: PIT now covers `shared.security` (owner decision 2026-10-31, above) and the Javadoc of
  `shared/observability/events/package-info.java` names `com.confia.shared.security.RetentionPurgeBatches` (both in #229). The docs/07
  note covers the five purges, not four, because the 3.4 purges had no row yet.
- Merged: `05b2` as PR #228 (`f4bf89f`) and `05b2b` as PR #229 (`49ddc62`) into `main`.

T3.6 evidence:

- Split: the whole task measured 825 effective lines, over the 800 limit. `tasks.md` names no seam for 3.6, so the V11 precedent
  was followed (the concurrency test on its own branch): `05b1-v12-purge-functions` (`ae233f0`, 634 lines: `V12`, the extended
  `RetentionPurgeFunctionsIT`, `RolePrivilegeMatrixIT`, `IdempotencyKeyPrivilegeIT`, `PurgeSession`, new `V12SchemaDiffIT`) and
  `05b1b-backoff-purge-versus-login` (`5f5108e`, 191 lines: `BackoffPurgeVersusLoginIT`). Each passes on its own. No comment,
  Javadoc or assertion was cut.
- RED: the first focused run failed at `testCompile` on the missing generated routines `SharedPurgeIdempotencyKeys`,
  `IdentityPurgeLoginBackoff` and `IdentityPurgeMfaTotpBackoff` (cannot find symbol), because jOOQ generates them from the
  migrations and `V12` did not exist.
- Second RED, which changed the template: with `V12` written exactly as `V11` (the purge condition only in the batch subquery),
  `BackoffPurgeVersusLoginIT.aPurgeWaitingForALoginThatRefreshedTheRowKeepsIt` failed (`expected: 0L but was: 1L`): a purge that
  waits for a login holding the row deletes it after the login made it recent, because PostgreSQL re-checks only the conditions on
  the deleted row and reuses the subquery's earlier answer. `V12` therefore repeats the condition on the deleted row in all three
  functions (`expires_at < v_cutoff`, `last_attempt_at < v_cutoff`); then 2 of 2 passed. `V11` does not need it: an expired reset
  token or a revoked family never becomes recent again, and the family purge locks with `FOR UPDATE` in its subquery.
- GREEN, focused: `RetentionPurgeFunctionsIT` 51, `RolePrivilegeMatrixIT` 48, `IdempotencyKeyPrivilegeIT` 3, `V11SchemaDiffIT` 2,
  `V12SchemaDiffIT` 2, `BackoffPurgeVersusLoginIT` 2, `RefreshTokenFamilyPurgeFunctionIT` 7,
  `RefreshPurgeVersusRotationConcurrencyIT` 3: 118 tests, 0 failures (JaCoCo failure of the focused run expected).
- Deliberate demonstrations, each reverted and confirmed with `cmp` against the copy in the scratchpad:
  - Index `shared_idempotency_key_expires_idx` removed: `theIndexesOfTheIdempotencyAndLoginBackoffPurgesExist` fails
    (`expected: "institution_id,expires_at"`) and `V12SchemaDiffIT.v12AddsOnlyTheThreePurgeFunctionsAndTheirTwoIndexes` fails [BI81].
  - `<` changed to `<=` in the idempotency function (subquery and row condition): `aCutoffInTheFutureNeverDeletesARowYoungerThanTheFloor`
    (the 24-hour border) and `aRowAtTheExactCutoffIsKeptAndOneSecondEarlierIsPurged` fail for that subject, `expected: 1L but was: 2L` [BI79].
- BI80 and BI82 are proved with the real `JooqIdempotencyRecordStore` over the purge session (`lockExisting`, `restartExpired`,
  `insertInProgress`); I259 with counters read back. `BackoffPurgeVersusLoginIT` uses the real `JooqLoginBackoffStore` and applies
  the two V11 review follow-ups: the lock wait is detected for the recorded `pg_backend_pid()` only, and the pool is declared before
  the lock-holding session.
- Kept from V11 for consistency (the spec is silent): a NULL `p_cutoff` becomes the floor and a NULL `p_limit` becomes 1
  (`R3-null-cutoff-silently-uses-floor`, still a follow-up for both migrations). `EXPLAIN ANALYZE` against a representative volume
  was not run; the indexes follow design.md decision 16.

T3.5 evidence:

- Commit `5553c22` adds `DocumentationNotesTest` (4 tests) and fixtures under `src/test/resources/documentation-notes` (ADR-0017 whole
  file, `docs/03` section 6.1 and `docs/08` section 6.2 as they were before the notes). Every fixture line must still appear, in order.
- RED: with the five docs restored to `942b2e6` (ADR-0025 removed), 4 of 4 tests failed (missing ADR file, missing notes). GREEN: 4 of 4
  with the real docs.
- Deliberate demonstration: rewriting the ADR-0017 line "Estos son los únicos `UPDATE` y `DELETE`..." failed
  `adr0017HasDatedNoteAndUnchangedOriginalBody` ("original body ... changed: original line 136 is missing or out of order"); reverted,
  `cmp` against the copy in the scratchpad printed nothing, `git status` clean for docs.
- `./mvnw -B verify -Pmutation-gate`: BUILD SUCCESS, 34:50 min; 2 623 unit tests and 726 integration tests, 0 failures; PIT 192 of 194
  killed in the last module report, no mutation without coverage.

T3.4 evidence:

- Split by the seam named in `tasks.md`: the whole task measured about 1 250 effective lines. Branch `05a2` (commit `4bafbb3`,
  798 effective lines) holds the reset-token purge: `IdentityRetentionPurges` (one operation), `PurgeExpiredPasswordResetTokens`,
  `RetentionPurgeBatches`, the Jooq adapter, the worker registration, `IdentityTokenPurgeJobsIT`, `AuditChainAfterPurgeIT`,
  `IdentityPurgeJobsOnWorkerIT`, `RecurringJobCatalogTest` (one job). Branch `05a2b-refresh-successor-clearing` (created from `05a2`,
  commit `7537337`, 536 effective lines) holds the successor clearing and the family purge with `RefreshTokenPurgeIT`.
- RED: the first compile of the new tests failed on the missing `PurgeExpiredPasswordResetTokens`, `PurgeExpiredRefreshTokens`,
  `IdentityRetentionPurges` and `JooqIdentityRetentionPurges` (cannot find symbol). The first run with the use cases in place failed
  13 of 22 tests only because the tests compared `after_value` as text and the column is `jsonb` (members reordered and spaced);
  the tests now compare trees (`IdentityPurgeFixtures.detail`). That was a defect of the tests, not of the code.
- Deliberate breaks, each reverted and confirmed with `cmp` against a copy kept outside the repository:
  - Entry written in a transaction other than the purge: a nested `runner.execute` has no effect (it joins the outer transaction,
    so the test still passed); the break that works holds the entries and writes them in a second transaction after the loop. It fails
    `anEntryThatFailsAfterTheDeletionRollsTheBatchBack` and `aFailureInTheSecondBatchLeavesTheFirstCommittedWithItsEntry` [AT21].
  - No entry when the count is zero: `aPurgeThatFoundNothingStillLeavesItsEntryWithCountZero` and `aSecondRunChangesNothingReportsZeroAndDoesNotFail` fail [AT19].
  - Clearing without the `GRACE_WINDOW` guard: `RefreshTokenPurgeIT` fails 4 of 11, among them both cases of
    `insideTheWindowAndAtTheExactBorderTheSuccessorIsKept` (the 10:00:10 border included) [I253].
- Owner decision 2026-10-28 (option B), replacing the slf4j exemption: the backlog `WARN` goes through the new
  `com.confia.shared.observability.events.OperationalEventLog`, sibling of `SecurityEventLog`. Correction after the coordinator's
  check against the spec (identity spec I275, design.md decision 17): the event is again exactly `retention_purge_backlog` and the
  line carries `runId`, `task` and `institution`. `warn(event, runId, taskName, institutionId)` accepts only a closed event
  identifier (`[a-z][a-z0-9_.]{0,79}`), a closed task identifier (`[a-z][a-z0-9_.-]{0,79}`, the real task names contain dots and
  dashes) and two `UUID`s; no free text. `RetentionPurgeBatches` no longer imports slf4j and `IdentityScopeExclusionInventoryTest`
  again forbids slf4j in the whole identity module (no diff against `f94557a`). The handlers build `new OperationalEventLog()` in
  `IdentityWorkerConfiguration`: a bean is rejected by `ProcessBeanIsolationTest`, like `SecurityEventLog` in `LogIn`.
  `shared/observability/events/package-info.java` names `RetentionPurgeBatches` as its consumer (ADR-0022).
- Slicing (local, unpushed): `05a2a-operational-event-log` (`6ba0082`, 184 effective lines, from `f94557a`: class, test, package-info);
  `05a2` (`9e2a09d`, 799); `05a2b` (`7b38d07` plus this document, 539). Additions plus deletions, excluding `openspec` and `odd`;
  no comment, Javadoc or assertion was cut to fit.
- RED of `OperationalEventLog`: the first compile failed on the missing class; after the correction, `OperationalEventLogTest`
  failed to compile against the old two-argument `warn` (cannot apply to given types), then 6 of 6 passed.
- Deliberate break: a real use of `org.slf4j.Logger`/`LoggerFactory` in `RetentionPurgeBatches` fails
  `noProductionClassOfIdentityDependsOnAnyLoggingFramework` (17 tests, 1 failure); a bare import does not (no bytecode
  dependency). Reverted and confirmed with `cmp`.
- The backlog test asserts the `WARN` through the real class: message `retention_purge_backlog`, no arguments, and exactly the
  pairs `runId`, `task`, `institution`.
- `./mvnw -B verify`: 05a2a green (kernel 186, unit 2 617, integration 704; 9 min 3 s, after one rerun because
  `LoginTimingGateIT` measured 49 ms once, a timing flake unrelated to this change); 05a2 green (186, 2 619, 715; 9 min 36 s);
  05a2b green (186, 2 619, 726; 9 min 8 s). `-Pmutation-gate` on 05a2b green in 28 min 51 s.
- Mutation gate (re-run after the decision): PIT kernel 192/194, app 872/919; JaCoCo met.

T3.8 follow-up (2026-11-01), on 06c:

- PIT widened to `com.confia.shared.audit.*` (owner decision, `da7bca1`); the mutation gate is pending for the owner in a terminal.
- R3-001 decided as the id walk: `verifySince` verifies from the first row at or after `since` and every later row by id, because a hash chain must
  not skip a link; Javadoc of the verifier, `AuditChainVerification.Intact` and the daily report now say so (`e832df5`). design.md/spec do not
  require an `occurred_at` filter after the first row. Tests added: first-row content tamper (regression guard, passed immediately) and a
  non-monotonic `occurred_at` case.
- R3 alert: the `audit_chain_broken` ERROR is emitted before the entry write; a failed write emits `audit_chain_broken_entry_unwritten` and the
  occurrence still fails with `AuditChainBrokenException` (`4487f61`). RED observed: the new unit test failed 1 of 10 against the old code.
- `./mvnw -B verify`: BUILD SUCCESS, 29:52 min, Surefire 186 + 2 671, Failsafe 785, 0 failures. 06c vs 06b: 498 effective lines.

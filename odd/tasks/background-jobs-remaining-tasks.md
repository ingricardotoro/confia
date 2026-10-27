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
- [ ] T3.1 Slice 4a `04-reset-issuance-scheduler`.
- [ ] T3.2 Slice 4b `04-reset-issuance-handler`.
- [ ] T3.3 Slice 5a-1 `05a-v11-purge-functions`.
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

## Next step

T3.1, slice 4a `04-reset-issuance-scheduler`, delegated to one writer.

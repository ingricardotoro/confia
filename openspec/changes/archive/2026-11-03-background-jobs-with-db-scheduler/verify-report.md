# Verify report: background-jobs-with-db-scheduler

> Source: Engram observation #749, `sdd/background-jobs-with-db-scheduler/verify-report` (project `confia`, 2026-11-02). Copied from the observation text. This is an intermediate snapshot: corrections applied at archive are listed in `archive-report.md`.

SDD verify of `background-jobs-with-db-scheduler` on main ef6db61 (2026-11-02). Verdict: PASS WITH WARNINGS — 0 CRITICAL, 7 WARNING, 5 SUGGESTION. Read-only; Maven not run by verifier (owner evidence used).

**Executed/observed evidence**
- Owner: `./mvnw verify -Pmutation-gate` BUILD SUCCESS 1h03m; JaCoCo checks passed; PIT kernel 99.0%, app 93.3%, domain 95.5%, shared.jobs 93.8%, shared.audit 97.9%, shared.security 90.3% (all >= 80).
- `@Scheduled|@EnableScheduling|@Async` in app/src/main: 0. V1-V9 unchanged d571161..ef6db61. PR CI green #195-#244.
- `scheduled_tasks` in main outside V10: only real SQL is JooqScheduledTaskQueueReader.java:62 (approved plain-SQL reader of task 4.1, BI101). pom.xml:394-396 is the jOOQ codegen exclusion (config). PortalApplication.java:27, DbSchedulerJobScheduler.java:28, SharedWorkerJobsConfiguration.java:37 and three package-info.java are Javadoc only. Not violations; the 4.3 final check wording (tasks.md ~line 743) predates/contradicts 4.1.
- docs/09-roadmap-y-fases.md:267 criterion 5 marked [x] with dated note :268.
- Traceability (apply-progress.md:540): 241 scenarios = 228 covered, 11 partial, 2 uncovered; guarded by JobsChangeTraceabilityTest.
- Strict TDD: RED/GREEN/demo evidence per task in apply-progress (prose + log paths, not standard table); not re-run.

**Tasks**: 1.1-4.2 checked (incl. conditional 1.2). 4.3 unchecked (tasks.md:732); its work is merged (#243/#244) and the final change verification is now satisfied by owner run; only the checkbox remains.

**WARNINGS**
- W1 audit-trail delta spec.md:198 and :239 say outcome `failure`; code and V2__*.sql:51 check constraint use `error` (finding T3.8).
- W2 build-integrity delta spec.md:633-634 says approved plain-SQL list holds exactly the queue reader; NoUnapprovedPlainSqlTest.java:92-95 asserts three (JooqAuditLogWriter, JooqRefreshTokenFamilyRepository, JooqScheduledTaskQueueReader). Also BI101 title (:643) "es el único con SQL plano".
- W3 Two scenarios without test: "Se agrega Hibernate", "Sin dependencias prohibidas" (apply-progress.md:718-719), enforced only by maven-enforcer bannedDependencies, no failing fixture.
- W4 11 partial scenarios: I240, I241, I264, BJ08, BI103, BI60, "Las pruebas de arranque no necesitan Docker", BI67, BI68, BI69, BJ37 (apply-progress.md:545-557).
- W5 R-9: `verify` 17-25 min vs 15-min budget BI57 (apply-progress.md:856-859); open, needs own change.
- W6 PR #242 non-blocking: QueueObservationOnWorkerIT race marking recurring job failed while running; fixed counts 8/7 instead of derived from catalog; no test for `db-scheduler.heartbeat-interval` / `missed-heartbeats-limit` keys.
- W7 PR #244 non-blocking: traceability negative fixture does not exercise the two structural filters (cue word present in a class without tests / in a helper method).

**SUGGESTIONS**
- S1 apply-progress.md:510 credits BJ37 to DocumentationNotesTest docs/09 test (should be BJ41 only; BJ37 -> ExitCriterion5EndToEndIT).
- S2 tasks.md 4.3 status (~line 748) stale: 231/8/2 and 1 089 lines; current 228/11/2.
- S3 apply-progress.md:858 cites 4.3 verify 17 min 21 s; later full run 25:20 (:534).
- S4 PR #244: @DisplayName with `)` or fully qualified test annotations make a real tag ignored (fails safe).
- S5 tasks.md 4.3 final check "scheduled_tasks only in V10, tests and docs" should admit the approved reader, codegen exclusion and Javadoc.

Not verified: "no versioned secrets" (no scan run); security checklist document not located.

**Spec corrections for sdd-archive**
1. audit-trail delta: `failure` -> `error` at spec.md:198 and :239.
2. build-integrity delta: requirement at :627-635 list = exactly three callers (JooqAuditLogWriter, JooqRefreshTokenFamilyRepository, JooqScheduledTaskQueueReader), only the queue reader reads scheduled_tasks and never task_data; adjust BI101 title/Given (:643-645) to "the only one over scheduled_tasks" / list including the reader.
3. Record in archive: 228/11/2 traceability, 2 uncovered enforcer-only scenarios, 11 partials, R-9 open, PR #242/#244 follow-ups; fix apply-progress.md:510 note.

**Next**: mark 4.3, then sdd-archive.

# Apply progress: audit-log-and-transaction-runner — PR B1

Scope: PR B1 only (tasks 1.1-1.12 of `tasks.md`). Branch `change/audit-log-and-transaction-runner`
(current), base `main`. Creates the first production class under `com.confia.shared.*`.

## Task 1.1 — Probes S7, S10, S12, S13; declare jqwik in `app`

None of the four probes touched Docker/PostgreSQL (ArchUnit, Spring Modulith and the enforcer are
pure). All four temporary probe files/edits were reverted before committing; only the permanent
`jqwik` dependency addition to `apps/api/app/pom.xml` survives this task.

| Probe | Question | Result | Evidence |
|---|---|---|---|
| **S13** | Does `dependencyConvergence` stay green after adding `net.jqwik:jqwik` (test scope, no version — managed by the parent's `dependencyManagement`, same coordinate kernel already uses) to `app/pom.xml`? | **PASS** | `./mvnw -B -pl app -am validate`: `BUILD SUCCESS`, no convergence error. No version mediation needed. |
| **S7** | With `consideringOnlyDependenciesInLayers()`, is a dependency from `..application..` to a layerless `com.confia.shared.security` type a violation? | **PASS — matches design decision 1** | Temporarily created `com.confia.probesmoke.application.ProbeApplicationUser` (main source, package `com.confia.probe.application`) depending on `com.confia.shared.security.ProbeSecurityMarker` (also temporary, main source, no layer segment). Ran `./mvnw -B -pl app -am test -Dtest=LayeredArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false`: `Tests run: 2, Failures: 0`. `productionCodeRespectsLayering()` passed with the probe classes present, confirming a package with no layer segment is invisible to `mayOnlyAccessLayers`. Both temporary files deleted afterward; `git status` confirmed clean before continuing. |
| **S12** | How does Spring Modulith 2 expose `com.confia.shared.security` as a named interface, and does `SpringModulithVerificationTest` stay green when one is added? | **PASS — cheap and unambiguous, not deferred** | Temporarily moved `spring-modulith-core` from `<scope>test</scope>` to default (compile) scope in `app/pom.xml`, and created `apps/api/app/src/main/java/com/confia/probesmoke/package-info.java` annotated `@org.springframework.modulith.NamedInterface("smoke")` (main source, so `SpringModulithVerificationTest`'s `DO_NOT_INCLUDE_TESTS` production scan actually sees it). Ran `./mvnw -B -pl app -am test -Dtest=SpringModulithVerificationTest -Dsurefire.failIfNoSpecifiedTests=false`: `Tests run: 2, Failures: 0`. The annotation compiles and verifies cleanly against `NamedInterface`'s real signature (`value()`/`name()`/`propagate()`, `@Target({PACKAGE, TYPE})`, `RUNTIME` retention — confirmed by `javap -v` against `spring-modulith-api-2.1.1.jar` before writing the probe). Reverted both the scope change and the temporary package afterward. **Design.md §10 leaves this as escape-hatch-eligible ("si resultara caro o ambiguo, se difiere al cambio 6"); it was neither, so no deferral and no `@NamedInterface` was added anywhere permanent in this PR** — task 1.3's `package-info.java` for `com.confia.shared.security` stays plain documentation, per the task's own text. |
| **S10** | Does the `PACKAGE` JaCoCo rule for `com.confia.shared.audit` (task 1.9) trip `SuppressionCitesAdrTest`'s `<exclude>` scanner if it only uses `<includes>`? | **PASS** | Temporarily added a `PACKAGE` rule (`<includes><include>com.confia.probesmoke</include></includes>`, no `<excludes>`) to `app/pom.xml`'s `jacoco-check` execution. Ran `./mvnw -B -pl app -am test -Dtest=SuppressionCitesAdrTest -Dsurefire.failIfNoSpecifiedTests=false`: `Tests run: 10, Failures: 0` — `JACOCO_CLASS_EXCLUSION` (`<exclude>[^<:]*</exclude>`) never matched because the rule only carries `<includes>`. Confirms decision 12 is safe to apply as-is in task 1.9. Reverted the temporary rule afterward. |

**Housekeeping note.** Every probe run that touches the `app` module's test classpath (even ones
that never execute an actual jqwik `@Property` test) creates `apps/api/app/.jqwik-database`, an
untracked file jqwik's engine writes on discovery. Deleted after each probe run; `git status`
confirmed clean before every commit in this PR. Task 1.9's `junit-platform.properties` is expected
to make this moot going forward by fixing `jqwik.database` explicitly.

**No discrepancy to report for this task**: all four probes confirm the design as written, with no
supression, no exception, and no deferral needed.

## Task 1.2 — RED: `TransactionRunnerContextIT`

Created `apps/api/app/src/test/java/com/confia/shared/security/TransactionRunnerContextIT.java`,
extending the bare `PostgresIntegrationTest` (never `TransactionalPostgresIntegrationTest`: the
class-level `@Transactional` variant would make `TransactionRunner`'s `TransactionTemplate`
(default propagation `REQUIRED`) join the outer test transaction instead of opening a genuinely
separate one, which would defeat the two-separate-transactions scenario). Institutions are seeded
through a raw JDBC `Connection` with an explicit transaction, never through
`withInstitutionContext` — that helper's own Javadoc (design.md decision 9, point 1) documents it
only works inside a test transaction, and this class deliberately has none.

Four test methods, covering the four scenarios of the "Contexto de sesión, nivel de aislamiento y
reintento acotado" requirement that do not involve retry:

- `contextIsSetBeforeTheFirstQueryOfTheUseCase`
- `noContextSurvivesOnAReusedPoolConnection` (pins `spring.datasource.hikari.maximum-pool-size=1`
  via a class `@DynamicPropertySource` so connection reuse is guaranteed, not hoped for, and
  asserts `pg_backend_pid()` equality between the two transactions to prove it)
- `defaultIsolationIsReadCommittedInTheRealTransaction`
- `explicitSerializableIsAppliedInTheRealTransaction`

**Order-sensitivity of the first scenario, verified structurally, not just asserted.**
`organization_institution`'s row-level-security policy (V1 migration) denies by default: if the
four `set_config` calls ran after the query instead of before it, the query returns zero rows
rather than exactly institution A's row, so `containsExactly(institutionA)` fails. There is no way
to make this test pass by writing the assertion loosely; the RLS deny-by-default behavior forces
the ordering to be real.

**RED, observed**: `./mvnw -B -pl app -am test-compile`, exit 1:

```
[ERROR] .../TransactionRunnerContextIT.java:[74,9] cannot find symbol
[ERROR]   symbol:   class TransactionRunner
[ERROR]   location: class com.confia.shared.security.TransactionRunnerContextIT
... (repeated for every call site, plus:)
[ERROR] .../TransactionRunnerContextIT.java:[128,73] cannot find symbol
[ERROR]   symbol:   variable IsolationLevel
[ERROR] .../TransactionRunnerContextIT.java:[143,20] cannot find symbol
[ERROR]   symbol:   class SecurityContext
```

Fails exactly as task 1.2 predicts: no `TransactionRunner`, `IsolationLevel` or `SecurityContext`
exist yet.

## Task 1.3 — GREEN: `TransactionRunner`, `SecurityContext`, `IsolationLevel`

Created:
- `apps/api/app/src/main/java/com/confia/shared/security/SecurityContext.java` — record of the
  four session parameters, non-null fields (never `null`, matching V1's `NULLIF(..., '')` pattern
  for "absent").
- `apps/api/app/src/main/java/com/confia/shared/security/IsolationLevel.java` — `READ_COMMITTED` /
  `SERIALIZABLE`.
- `apps/api/app/src/main/java/com/confia/shared/security/TransactionRunner.java` — two constructors
  matching design.md §6.1's contract exactly; `execute(context, useCase)` and `execute(context,
  isolation, useCase)`, each opening one `TransactionTemplate`-managed transaction and issuing the
  four `set_config(..., true)` calls as the first statement over the connection bound by {@code
  DataSourceUtils.getConnection(dataSource)}, with bound parameters, never string interpolation.
  **Deliberately no retry loop yet** — `maxRetries`/`backoffBase` are accepted and stored but not
  consulted, exactly matching design.md §11 step 3 vs. steps 6-7 (retry is `TransactionRunnerRetryIT`'s
  own RED/GREEN cycle, tasks 1.6/1.7). This is a conscious TDD-discipline choice, not an oversight:
  implementing retry now would be adding production code no currently-red test demands.
- `apps/api/app/src/main/java/com/confia/shared/security/package-info.java` — cites ADR-0015 rule 7
  verbatim and explains why this package carries no layer segment.

**GREEN, observed**: `./mvnw -B -pl app -am verify -Dit.test=TransactionRunnerContextIT -Dtest=none
-Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false`:

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 25.34 s -- in com.confia.shared.security.TransactionRunnerContextIT
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

All four scenarios pass, including the connection-reuse and order-sensitive ones. The overall Maven
reactor still reports `BUILD FAILURE` on this narrow run, from `jacoco-maven-plugin:check`
("Coverage checks have not been met") — exactly the same, already-documented artifact of skipping
Surefire with `-Dtest=none` that the archived `jooq-flyway-testcontainers-wiring/apply-progress.md`
task 3.5 recorded for PR A3: the BUNDLE coverage ratio is computed over an incomplete set of
executed tests, not a real gate failure. Resolved by the full `./mvnw -B verify` in task 1.12.

## Task 1.4 — REFACTOR: lazy container holder

Created `apps/api/app/src/test/java/com/confia/support/SharedPostgresContainer.java`: the container
moves out of `PostgresIntegrationTest`'s static block into a lazily-initialized, double-checked-lock
holder (`instance()`), with `jdbcUrl()`, `connectionAs(String role)` and `dataSourceFor(String
role)` as the three entry points design.md decision 14 names. `newContainer()` reproduces the exact
same container configuration the static block used to build (same image via
`PostgresIntegrationTest.postgresImage()`, same username/password, same `fsync=off
synchronous_commit=off max_connections=200`, same `tmpfs` mount, same test-roles classpath mapping),
so this is a pure move, not a behavior change.

`PostgresIntegrationTest` no longer declares `POSTGRES` or a static block; its
`@DynamicPropertySource` now calls `SharedPostgresContainer::jdbcUrl` instead of `POSTGRES::getJdbcUrl`.
`withInstitutionContext`'s Javadoc gained the paragraph the task requires, declaring explicitly that
it only works inside a test transaction and pointing at `TransactionRunner` for confirmed-row needs.

**GREEN, observed** (full reactor, not a narrow run — this task explicitly requires `./mvnw -B
verify` to stay green, including every part A test): `./mvnw -B verify` in `apps/api`:

```
[INFO] Tests run: 177, Failures: 0, Errors: 0, Skipped: 0        (kernel)
[INFO] Tests run: 109, Failures: 0, Errors: 0, Skipped: 0        (app unit tests, Surefire)
[INFO] Tests run: 26, Failures: 0, Errors: 0, Skipped: 0         (app *IT.java, Failsafe)
[INFO] BUILD SUCCESS
[INFO] Total time:  02:23 min
```

`DatabasePipelineIT`, `JooqInstitutionRepositoryIT`, `MultiTenantSchemaIT`, `RolePrivilegeMatrixIT`
and `PostgresImageSingleSourceTest` (part A) all pass unchanged, alongside the new
`TransactionRunnerContextIT` (4/4). No `PostgresImageSingleSourceIT` rename needed: that class
never extends `PostgresIntegrationTest`, so it is out of scope for the future `*IT` naming rule
(B4) and unaffected by this refactor.

## Task 1.5 — VERDE: `CommittingPostgresIntegrationTest`

Created `apps/api/app/src/test/java/com/confia/support/CommittingPostgresIntegrationTest.java`: no
`@Transactional`; `transactionRunner()` exposes the real production `TransactionRunner` built over
the test context's own `DataSource`/`PlatformTransactionManager`; `@AfterEach
truncateCommittedBusinessTables()` derives the table set from `pg_class`/`pg_trigger` (tables with
no `BEFORE TRUNCATE` trigger), connects as `confia_owner` (the only role with `TRUNCATE`), and
truncates. Javadoc explains why `withInstitutionContext` cannot seed rows for a subclass of this
class (same reasoning as `TransactionRunnerContextIT`'s own Javadoc, task 1.2). No RED test is
written for this task in this PR — `tasks.md` explicitly assigns the contract test
(`CommittingBaseContractIT`, proving the derived set *excludes* the future audit tables) to PR B2a
task 2.4, once those tables exist; `design.md` §11 step 5's "Rojo: ... con su prueba de contrato"
is satisfied by that later class, not duplicated here.

**Verified before relying on it**, with a temporary, uncommitted probe
(`ProbeCommittingSmokeTest`, deleted immediately after, `git status` confirmed clean): seeded one
row through `transactionRunner()`, confirmed it visible on a *separate* raw connection (real commit,
not a transaction-local artifact), called `truncateCommittedBusinessTables()` directly, and
confirmed the row was gone. `./mvnw -B -pl app -am test -Dtest=ProbeCommittingSmokeTest
-Dsurefire.failIfNoSpecifiedTests=false`: `Tests run: 1, Failures: 0`. This is deliberately *not*
the task's persisted evidence (the class has no dedicated test file in this PR by design) — it is
due diligence before task 1.6 builds `TransactionRunnerRetryIT` on top of this base class, so a
broken foundation would not surface as a confusing failure two tasks later.

`./mvnw -B -pl app -am test-compile`: `BUILD SUCCESS` (nothing in the app module extends this class
yet within this PR's permanent test sources; task 1.6 is its first real consumer).

## Task 1.6 — RED: `TransactionRunnerRetryIT`

Created `apps/api/app/src/test/java/com/confia/shared/security/TransactionRunnerRetryIT.java`,
extending `CommittingPostgresIntegrationTest`. Added `dataSource()`/`transactionManager()`
protected accessors to that base class so this test can build one independent `TransactionRunner`
per thread, matching design.md §7.2's "dos hilos, cada uno con su propio TransactionRunner y su
propia conexión".

Two scenarios, neither depending on wall-clock timing (design.md §7.2):

- **Deterministic exhaustion** (`retryExhaustsAndPropagatesTheOriginalErrorDeterministically`): a
  use case that runs `do $$ begin raise exception using errcode = '40001'; end $$;` on every single
  attempt, no concurrency. Asserts the propagated exception carries SQLState `40001` (or is a
  `ConcurrencyFailureException`), and — the assertion that actually distinguishes "retried and gave
  up" from "never retried" — that the body ran **exactly 4 times** (1 initial attempt + design.md
  decision 2's bounded 3 retries).
- **Real success within the limit** (`retrySucceedsWithinTheBoundedLimitOnARealSerializationConflict`):
  two independent `TransactionRunner`s, `SERIALIZABLE`, both updating the very same committed row,
  synchronized with a `CyclicBarrier` awaited only on each thread's **first** attempt
  (`AtomicBoolean.compareAndSet`) — never on a retry, which would deadlock waiting for a party that
  already finished. Asserts both futures complete without the caller ever seeing an exception.

**RED, observed**: `./mvnw -B -pl app -am test -Dtest=TransactionRunnerRetryIT
-Dsurefire.failIfNoSpecifiedTests=false`, exit 1, `Tests run: 2, Failures: 1, Errors: 1`:

```
retrySucceedsWithinTheBoundedLimitOnARealSerializationConflict -- ERROR!
java.util.concurrent.ExecutionException: org.jooq.exception.DataAccessException: SQL [update organization_institution set legal_name = ? where id = ?]; ERROR: could not serialize access due to concurrent update
Caused by: org.postgresql.util.PSQLException: ERROR: could not serialize access due to concurrent update

retryExhaustsAndPropagatesTheOriginalErrorDeterministically -- FAILURE!
[exactly the initial attempt plus the bounded number of retries, no more and no fewer]
  expected: 4
  but was: 1
```

Both failures are genuine and for the right reason: the success scenario shows PostgreSQL really
did raise a `SERIALIZABLE` write conflict (proving the concurrency setup is real, not a fabrication)
and `TransactionRunner`, having no retry logic yet, propagated it straight to the caller instead of
retrying; the exhaustion scenario shows the body ran exactly once instead of four times, confirming
no retry happened there either.

## Task 1.7 — GREEN: the bounded retry in `TransactionRunner`

Implemented the retry loop in both `execute` methods (retry lives in the `IsolationLevel`-explicit
overload; the single-arg overload delegates to it, so both share one implementation): on a
`RuntimeException`, `isRetryable(e)` checks first for `org.springframework.dao.
ConcurrencyFailureException`, then walks the cause chain for a `java.sql.SQLException` with
`SQLState` `40001` or `40P01` — needed because jOOQ wraps the underlying `SQLException` in its own
`org.jooq.exception.DataAccessException`, never translated through Spring's hierarchy. Retries up
to `maxRetries` (3) times, backing off `backoffBase * attempt` plus bounded jitter
(`ThreadLocalRandom`) between attempts, via `Thread.sleep` — never used as a synchronization
mechanism in any test, per design.md decision 2. Each retry opens a genuinely new transaction
(a fresh `template.execute(...)` call), so the security context is reapplied fresh every attempt.

**Two real bugs found and fixed while turning this green, both in the test, not in
`TransactionRunner`:**
1. The success scenario's final assertion queried the row through `dsl` with no institution context
   active, so the root table's row-level-security policy denied by default (V1 migration) and the
   query returned zero rows instead of the updated one — an `IndexOutOfBoundsException`, not a
   `TransactionRunner` defect. Fixed by reading the final state through a raw `postgres` (superuser)
   connection via `SharedPostgresContainer.connectionAs("postgres")`, which bypasses row-level
   security, exactly like the fixture's own seeding helper does elsewhere in this PR.
2. That fix initially missed the `com.confia.support.SharedPostgresContainer` import (a real
   `cannot find symbol` compile error, caught and fixed immediately).

**Environment interference encountered and resolved, unrelated to any of the above bugs.** Two
separate real Maven runs failed with `El proceso no tiene acceso al archivo porque está siendo
utilizado por otro proceso` on `target/test-classes/.../ResolveCurrentInstitutionTest.class`
(OneDrive sync holding a file handle — the exact class of failure this PR's brief already warns
about) and, once that cleared, two further runs failed with a **different** symptom: Surefire ran a
`.class` file that literally contained the bytes `Unresolved compilation problems: ` — traced with
`grep -a` directly against the compiled class file — meaning VS Code's Java language server
(`redhat.java` extension, ECJ-based) was recompiling the same source tree into the same
`target/test-classes` directory Maven uses and racing Maven's own `javac` output between
`test-compile` and `test`. Resolved by stopping the two `redhat.java` JRE processes for the
remainder of this session (`Stop-Process`, PIDs identified via `Get-Process java`) and clearing
`target/` again before the next run; the editor extension restarts this language server on its own
when the user next interacts with it, so nothing here is a permanent change to the user's
environment. Neither of these two interferences is a code defect.

**GREEN, observed** (stable across three separate runs, no flakiness in the concurrency scenario):
`./mvnw -B -pl app -am test -Dtest=TransactionRunnerRetryIT -Dsurefire.failIfNoSpecifiedTests=false`:

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 26.19 s -- in com.confia.shared.security.TransactionRunnerRetryIT
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
(repeated twice more: 26.49 s and 25.73 s, both `Tests run: 2, Failures: 0, Errors: 0`)

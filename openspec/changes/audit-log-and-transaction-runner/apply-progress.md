# Apply progress: audit-log-and-transaction-runner — PR B1 and PR B2a

Scope: PR B1 (tasks 1.1-1.12, this section) and PR B2a (tasks 2.1-2.7, its own section below).
PR B1 was split into PR B1a (`change/audit-log-and-transaction-runner-component`, tasks 1.1-1.5)
and PR B1b (`change/audit-log-and-transaction-runner`, tasks 1.6-1.12) after task 1.11 measured
1015 authored lines — see the note block at the top of `tasks.md`'s PR B1 section for the measured
split evidence. PR B2a runs on `change/audit-log-and-transaction-runner-audit-table`, base PR B1b,
HEAD `76deb80` at the start of this section's work.

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

## Task 1.8 — R3 positive half

Extended `TransactionsOnlyInSharedSecurityTest` with a third test,
`sharedSecurityContainsAtLeastOneProductionClassThatUsesTheTransactionApi()`, asserting over
`productionClasses()` filtered to `com.confia.shared.security` that at least one class uses the
transaction API. Extracted the shared criterion into one static `usesTransactionApi(JavaClass)`
method, called by both the existing negative `ArchCondition` and the new positive assertion, so the
two halves cannot diverge on what "uses the transaction API" means (task 1.8's own requirement).
This is a refactor of the negative rule's internals (its violation messages are now one unified
string instead of three separate per-branch strings), not a behavior change — its own fixture
rejection test still passes with the same message fragment (`"BadTransactionalRepository"`).

Task text says this half "falla al ejecutarse antes de la tarea 1.3" — not applicable here since
task 1.3 already ran (`TransactionRunner` exists since this PR's third commit); confirmed instead,
per the task's own instruction, that the assertion is independent of the negative rule's rejection
and shares its criterion (done above), then ran it directly as ROJO/VERDE combined.

Also corrected `BadTransactionalRepository`'s Javadoc (no longer describes itself as a "preventive
guard" over an empty `shared.security` package — that package has real production code since task
1.3).

**Observed**: `./mvnw -B -pl app -am test -Dtest=TransactionsOnlyInSharedSecurityTest
-Dsurefire.failIfNoSpecifiedTests=false`:

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 11.15 s -- in com.confia.architecture.TransactionsOnlyInSharedSecurityTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

All three green: the negative rule against real production code, its fixture-rejection half, and
the new positive assertion.

## Task 1.9 — `junit-platform.properties` and the `PACKAGE` JaCoCo rule for `com.confia.shared.audit`

Created `apps/api/app/src/test/resources/junit-platform.properties`: `jqwik.database` points inside
`target/` (git-ignored, same pattern as kernel), `jqwik.tries.default=100` — lower than kernel's
1000, because this module's future property test round-trips to real PostgreSQL on every try,
unlike kernel's pure in-memory properties. No extra `testResources` filtering configuration needed:
the existing unfiltered pass over `src/test/resources` (app/pom.xml, task 1.6 of the previous
change) already copies this file as-is.

Added the `PACKAGE` JaCoCo rule for `com.confia.shared.audit` (95% line+branch) next to the existing
`domain`-packages rule, `<includes>` only, no `<excludes>` — confirmed clean by probe S10
(apply-progress.md task 1.1).

**Reconciliation note resolved, not a discrepancy after all.** The task text flagged a real risk:
`com.confia.shared.audit` has no class yet in this PR (its first class lands in PR B3a), so this
rule is declared two cuts before it has anything to measure, and `design.md` §5 does not call that
gap out. The task said to report it as a discrepancy **only if** the empty rule failed the build
instead of passing vacuously. **It did not fail**: the full `./mvnw -B verify` below is `BUILD
SUCCESS` with this rule active and zero classes in the package it targets — JaCoCo's `PACKAGE`
element rule simply has nothing to check and passes, exactly like the file removed the concern is
`SuppressionCitesAdrTest`'s pass on the same PR earlier. No discrepancy to report.

**Observed**: `./mvnw -B verify` in `apps/api`:

```
[INFO] Tests run: 177, Failures: 0, Errors: 0, Skipped: 0        (kernel)
[INFO] Tests run: 110, Failures: 0, Errors: 0, Skipped: 0        (app unit tests, Surefire)
[INFO] Tests run: 28, Failures: 0, Errors: 0, Skipped: 0         (app *IT.java, Failsafe)
[INFO] BUILD SUCCESS
[INFO] Total time:  02:19 min
```

Also confirmed: no stray `apps/api/app/.jqwik-database` file appeared after this run (the
housekeeping nuisance noted in task 1.1 is resolved by this task's own configuration, as expected —
`jqwik.database` now points inside `target/`), and the properties file is genuinely on the test
classpath (`target/test-classes/junit-platform.properties`, contents verified byte-for-byte equal
to the source).

## Task 1.10 — Measure the `*IT.java` suite time

Measured, not estimated, with a dedicated clean run (`app/target` and `kernel/target` removed
first). Reading the real Failsafe phase boundary off the reactor's own per-module summary line is
imprecise (it bundles compile, jOOQ generation and unit tests together), so this run piped
`./mvnw -B verify`'s output through a per-line `date +%s.%N` timestamp wrapper to isolate the exact
`[INFO] --- failsafe:3.6.0:integration-test (default) @ confia-api ---` → `[INFO] ---
failsafe:3.6.0:verify (default) @ confia-api ---` boundary precisely:

```
1790052127.329823100 [INFO] --- failsafe:3.6.0:integration-test (default) @ confia-api ---
1790052166.840512800 [INFO] --- failsafe:3.6.0:verify (default) @ confia-api ---
```

**Failsafe `integration-test` phase for `confia-api`: 39.5107 seconds**, all six `*IT.java` classes
included (`DatabasePipelineIT`, `JooqInstitutionRepositoryIT`, `MultiTenantSchemaIT`,
`RolePrivilegeMatrixIT` from earlier changes, plus this PR's `TransactionRunnerContextIT` and
`TransactionRunnerRetryIT`), 28 integration test methods, 0 failures. Reactor: `confia-api` module
`SUCCESS [02:06 min]`, `Total time: 02:26 min`. Recorded in `apps/api/README.md`, "Integration test
suite budget" — both are far inside the 8-minute (480 s) budget; the Failsafe phase itself uses
about 8% of it, including `TransactionRunnerRetryIT`'s real `CyclicBarrier`-synchronized
`SERIALIZABLE` conflict scenario.

## Task 1.11 — Measure the real diff of PR B1 — HARD STOP, exceeds 800 lines

`git diff --numstat main...change/audit-log-and-transaction-runner -- . ':(exclude)openspec'
':(exclude)docs/adr' ':(exclude)**/generated/**'`:

| File | + | − |
|---|---|---|
| `apps/api/README.md` | 15 | 1 |
| `apps/api/app/pom.xml` | 40 | 0 |
| `IsolationLevel.java` (new) | 11 | 0 |
| `SecurityContext.java` (new) | 24 | 0 |
| `TransactionRunner.java` (new) | 181 | 0 |
| `package-info.java` (new, `shared.security`) | 16 | 0 |
| `TransactionsOnlyInSharedSecurityTest.java` | 59 | 22 |
| `BadTransactionalRepository.java` | 6 | 6 |
| `TransactionRunnerContextIT.java` (new) | 182 | 0 |
| `TransactionRunnerRetryIT.java` (new) | 181 | 0 |
| `CommittingPostgresIntegrationTest.java` (new) | 115 | 0 |
| `PostgresIntegrationTest.java` | 22 | 26 |
| `SharedPostgresContainer.java` (new) | 97 | 0 |
| `junit-platform.properties` (new) | 11 | 0 |

**Total: 960 additions + 55 deletions = 1 015 authored lines.**

**This exceeds both the project's 800-line-per-pull-request budget
(`docs/15-flujo-de-trabajo-git.md` §3) and design.md §12's own high-end forecast for B1 (975).**
Per this task's own instruction and the orchestrator's explicit hard-stop rule 1 ("Si el diff de la
tarea 1.11 supera 800 líneas de código, detente y repórtalo. Partir el corte es decisión del
propietario"), **apply STOPS here.** Task 1.12 (final verification and push) is intentionally not
started.

**The contingency subdivision design.md §12 already names, for the owner to choose from (not
decided here):**

- **B1a** ≈ 340–560 authored lines: `TransactionRunner` and its three types (`SecurityContext`,
  `IsolationLevel`), `TransactionRunnerContextIT`, and R3's positive half — tasks 1.1 (probes +
  jqwik dependency), 1.2, 1.3, 1.8.
- **B1b** ≈ 245–415 authored lines: the lazy container holder, `CommittingPostgresIntegrationTest`,
  `TransactionRunnerRetryIT`, `junit-platform.properties` and the JaCoCo `PACKAGE` rule — tasks 1.4,
  1.5, 1.6, 1.7, 1.9.
- Neither subdivision separates code from its own tests (design.md §12's explicit constraint on any
  split).

The real measured total (1 015) is noticeably above even the combined high end of B1a+B1b's
forecast (560+415=975), consistent with this PR's own precedent: every measured PR in this change
family so far has landed at or above its forecast's high end (PR A1 replaced by A2/A3 in the
archived change; `design.md` §12 itself already flagged B1 as one of the two cuts, along with B3,
that its own high-end estimate could not clear).

**Nothing here is reverted.** All nine completed tasks (1.1–1.10) remain committed, individually,
on `change/audit-log-and-transaction-runner`; every commit is a real, independently-revertable
`git` commit with its own RED/GREEN evidence. Splitting into B1a/B1b, if the owner chooses that
path, is a matter of choosing where a new branch point falls among these already-existing commits —
no code needs to be rewritten to make the split possible.

---

# PR B2a — tables, permissions and append-only

Scope: tasks 2.1-2.7. Branch `change/audit-log-and-transaction-runner-audit-table`, base PR B1b
(`change/audit-log-and-transaction-runner`, tip `76deb80`). No probes were re-run for this section:
S1, S2, S4, S6 (design phase) and S7, S10, S12, S13 (PR B1) already ran and are recorded above and
in `design.md` §10; PR B2a's own task list names no new probe (the next probes, S3/S5/S9, belong to
PR B2b/B3b).

## Task 2.1 — RED: `RolePrivilegeMatrixIT`, `AuditLogAppendOnlyIT`, `AuditLogRowSecurityIT`

Created, in one step, per the task's own instruction (all three fail for the same reason — the
tables do not exist yet):

- Extended `RolePrivilegeMatrixIT` with five new test methods covering all five roles
  (`docs/03-seguridad.md` §6.1) against both `shared_audit_log` and `shared_audit_chain_head`:
  `confia_owner` retains every privilege on both tables by definition (design.md decision 8 — the
  append-only trigger, not the privilege matrix, is what rejects the owner); `confia_admin_app` gets
  exactly `SELECT`/`INSERT` on `shared_audit_log` and nothing on `shared_audit_chain_head`;
  `confia_portal_app` gets nothing on either; `confia_readonly` gets only `SELECT` on
  `shared_audit_log` and nothing on `shared_audit_chain_head`; `confia_backup` gets `SELECT` only on
  both, through `pg_read_all_data` (never `INSERT`/`UPDATE`/`DELETE`).
- Created `apps/api/app/src/test/java/com/confia/shared/audit/AuditLogAppendOnlyIT.java`:
  `confia_admin_app` can `SELECT` and `INSERT` but `UPDATE`/`DELETE`/`TRUNCATE` are all rejected
  (via ordinary `GRANT`/`REVOKE`, since the role was never granted those privileges at all); and,
  the case the orchestrator's own review explicitly asked to see proven rather than assumed,
  **`confia_owner` — the schema owner, who is exempt from `GRANT`/`REVOKE` entirely — has `UPDATE`,
  `DELETE` **and** `TRUNCATE`, all three, rejected by the append-only trigger**, asserted against
  the real `SQLSTATE 42501` (`insufficient_privilege`) on a raw connection opened with
  `SharedPostgresContainer.connectionAs("confia_owner")`.
- Created `apps/api/app/src/test/java/com/confia/shared/audit/AuditLogRowSecurityIT.java`: one
  institution cannot read another's rows; an absent session context denies returning zero rows
  (no context ever set at all); an empty-string context also denies returning zero rows, with no
  `::uuid` conversion error — exercised on one explicit, uncommitted raw connection so the local
  `set_config(..., true)` setting survives from the `SET` statement to the following `SELECT`.

**RED, observed**: `./mvnw -B -pl app -am test -Dtest=RolePrivilegeMatrixIT,AuditLogAppendOnlyIT,AuditLogRowSecurityIT -Dsurefire.failIfNoSpecifiedTests=false`, exit 1:

```
Tests run: 14, Failures: 0, Errors: 10, Skipped: 0
```

All ten new test methods failed with the same real cause, confirmed in the stack traces:

```
org.jooq.exception.DataAccessException: SQL [...]; ERROR: relation "shared_audit_log" does not exist
Caused by: org.postgresql.util.PSQLException: ERROR: relation "shared_audit_log" does not exist
```

The four pre-existing `organization_institution` tests in `RolePrivilegeMatrixIT` kept passing
(9 tests run in that class, 5 new failing, 4 old green) — confirming the failure is isolated to the
new assertions and not a fixture-wide breakage.

## Task 2.2 — GREEN: `V2__create_shared_audit_log.sql`

Created `apps/api/app/src/main/resources/db/migration/V2__create_shared_audit_log.sql` with both
tables from design.md decisions 3, 4, 7 and 8:

- `shared_audit_chain_head` (`institution_id` primary key, `next_id >= 2` check, forced row-level
  security with the same institution-isolation policy shape as V1).
- `shared_audit_log`: composite primary key `(institution_id, id)`, anti-fork unique constraint
  `(institution_id, prev_hash)`, the two hash-length checks (32 bytes), the four indexes with
  `institution_id` leading each one, forced row-level security with the `NULLIF(..., '')` pattern
  and an explicit `WITH CHECK`. **No chaining trigger** — `id`, `prev_hash` and `row_hash` stay
  `NOT NULL` but unpopulated by any trigger in this cut, exactly as task 2.2 specifies; PR B2b's
  `shared_audit_log_chain()` (`design.md` decision 5) is what fills them.
- `shared_audit_is_append_only()` plus the five triggers: `BEFORE UPDATE`/`DELETE`/`TRUNCATE` reject
  on `shared_audit_log`; `BEFORE DELETE`/`TRUNCATE` reject on `shared_audit_chain_head`, with
  `BEFORE UPDATE` deliberately left unguarded there (the future chaining trigger needs it).
- `REVOKE ALL ... FROM PUBLIC` on both tables, then `GRANT SELECT, INSERT` to `confia_admin_app` and
  `GRANT SELECT` to `confia_readonly` on `shared_audit_log` only — no `GRANT` at all on
  `shared_audit_chain_head` for any application role (design.md decision 5: only the future
  `SECURITY DEFINER` trigger writes it).

**GREEN, observed**: `./mvnw -B -pl app -am test -Dtest=RolePrivilegeMatrixIT,AuditLogAppendOnlyIT,AuditLogRowSecurityIT,MultiTenantSchemaIT -Dsurefire.failIfNoSpecifiedTests=false`:

```
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0  -- MultiTenantSchemaIT
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0  -- RolePrivilegeMatrixIT
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0  -- AuditLogAppendOnlyIT
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0  -- AuditLogRowSecurityIT
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
```

All 21 green, `MultiTenantSchemaIT` included — folding task 2.3's confirmation into the same run
(see below).

## Task 2.3 — `MultiTenantSchemaIT` passes unmodified

Confirmed by the very same run above: `MultiTenantSchemaIT` is **7/7 green with zero changes to the
file**, exactly `design.md` §2's verified prediction ("¿La clave primaria compuesta obliga a tocar
`MultiTenantSchemaIT`? Verificado: no"). No discrepancy to report. In particular:

- `everyUniqueIndexOfABusinessTableIncludesTheInstitutionDiscriminator` passes because every unique
  index on both new tables — the composite primary key, the anti-fork unique constraint, and
  `shared_audit_chain_head`'s single-column primary key — includes `institution_id`.
- `everyBusinessTableNameCarriesItsOwnerModulesPrefixExceptTheClosedCatalogue` passes because both
  table names split on their first `_` to `shared`, and `com.confia.shared.security` already exists
  as real production code since PR B1 — the exact mechanical dependency `tasks.md`'s header note
  describes.

No production code or test file was touched for this task; it is a verification-only task.

## Task 2.4 — ROJO/VERDE: `CommittingBaseContractIT`

Widened `CommittingPostgresIntegrationTest.tablesWithoutABeforeTruncateTrigger()` from `private` to
package-private, so the contract test asserts directly on the real derivation instead of duplicating
the catalog query (Javadoc added explaining why). Created
`apps/api/app/src/test/java/com/confia/support/CommittingBaseContractIT.java` with two methods: one
confirms the derived set excludes both `shared_audit_log` and `shared_audit_chain_head` (and, by
membership rather than only absence, that the loop ran over real catalog rows — it still contains
`organization_institution`); the other confirms
`truncateCommittedBusinessTables()` itself never throws, even though both audit tables are silently
skipped.

No RED was separately captured for this task: the task text itself says this is expected to already
be GREEN, because task 1.5's implementation already derives the truncation set from the catalog
(`pg_trigger`'s `TRUNCATE` bit), which automatically excludes any table that gained a `BEFORE
TRUNCATE` trigger — both audit tables did, in task 2.2, one task earlier. Confirmed, not invented:

**GREEN, observed**: `./mvnw -B -pl app -am test -Dtest=CommittingBaseContractIT -Dsurefire.failIfNoSpecifiedTests=false`:

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

No production code change was needed — the query in `tablesWithoutABeforeTruncateTrigger()` already
behaved correctly; only its visibility changed.

## Task 2.5 — Documentation reconciliation

Applied `proposal.md`'s "Deriva del nombre de la tabla" table across nine files:

- `docs/03-seguridad.md`: renamed in §6.1 (twice) and §6.3; rewrote §12.1's DDL block (composite
  primary key `(institution_id, id)`, no `BIGSERIAL`, `id`/`prev_hash`/`row_hash` now documented as
  disparador-assigned, the four indexes now lead with `institution_id`), added a short paragraph
  explaining why there is no `BIGSERIAL`; rewrote the "cadena" language throughout §12.1, "Ancla
  externa" and §12.4 to be explicitly **per-institution**, not global (design.md decision 3);
  rewrote §12.3's grant block (no `GRANT ... ON SEQUENCE`, `GRANT SELECT` added for
  `confia_readonly`, function/trigger names renamed to the real `shared_audit_*` ones, the trigger
  message parameterized with `TG_TABLE_NAME` matching the real migration); renamed the checklist
  entries and the periodic-verification summary table.
- `docs/07-observabilidad-y-operaciones.md`, `docs/08-datos-privacidad-y-retencion.md`: mechanical
  rename (table and column-qualified references). **Flagging, not silently fixing**: docs/08 line
  104 refers to a column `audit_log.source_ip_hash` that never existed in any shipped schema (the
  real column is `source_ip INET`, stored unhashed per design.md decision 7 and the `build-integrity`
  preimage table) — renamed the table qualifier only and left the column name and the "IP
  almacenada como hash" claim untouched, since task 2.5's own scope for this file is "nombre de la
  tabla y de sus columnas" (a rename), not a resolution of a pre-existing, unrelated semantic
  inconsistency about whether the IP is hashed. **Reporting this as a discrepancy for the
  orchestrator/owner to resolve, not inventing a `source_ip_hash` column or silently rewriting the
  claim.**
- `.claude/skills/confia-audit-logging/SKILL.md`, `.claude/agents/confia-database.md`: renamed the
  table throughout the DDL and examples. Also corrected the append-only function name (the
  mechanical rename alone would have produced `shared_audit_log_is_append_only`, which does not
  match the real migration's `shared_audit_is_append_only`) and the sequence `GRANT` line (removed,
  since there is no sequence — replaced with the real `confia_readonly` `GRANT SELECT`), and the
  `id` column's type (`BIGINT`, not `BIGSERIAL`, with the real composite primary key constraint
  added) — needed for the examples to stay consistent with the real shipped schema, not merely
  scope creep.
- `docs/runbooks/descuadre-de-libro-mayor.md`, `docs/runbooks/cierre-de-caja-con-diferencia.md`:
  pure mechanical rename — both queries only ever referenced real, still-existing columns
  (`entity_id`, `request_id`, `occurred_at`, `actor_id`, `action`, `entity_type`), so no
  reconciliation beyond the table name was needed.
- `docs/runbooks/incidente-de-seguridad.md`, `docs/runbooks/restauracion-de-respaldo.md`: **real
  reconciliation, not just a rename.** Both runbooks' chain-integrity query joined
  `audit_log a JOIN audit_log p ON p.id = a.previous_id WHERE a.previous_hash <> p.record_hash` —
  `previous_id`, `previous_hash` and `record_hash` never existed in any schema this change or its
  predecessor ever shipped (the real columns are `id`, `prev_hash`, `row_hash`), and the join
  assumed one global chain, which design.md decision 3 explicitly rejects in favor of one chain per
  institution. Rewrote both to
  `shared_audit_log a JOIN shared_audit_log p ON p.institution_id = a.institution_id AND p.id = a.id - 1 WHERE a.prev_hash <> p.row_hash`,
  and `ip_address` → `source_ip` in `incidente-de-seguridad.md`'s reconstruction queries (the real
  column name). **Executed manually** against this cut's real schema with a temporary, uncommitted
  probe test (`ProbeRunbookQueryTest`, deleted immediately after — never committed, `git status`
  confirmed clean before continuing) to confirm the reconciled query runs without an
  unknown-column error:

  ```
  Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- ProbeRunbookQueryTest
  ```

- `docs/adr/ADR-0003-separacion-admin-portal.md`: added the one-line dated editorial note
  `proposal.md` D2 describes ("Nota editorial (2026-09-21): ... el nombre vigente ... es
  `shared_audit_log`"), directly under the role table at line 225. The decision body itself — the
  table row that still literally reads `audit_log` — was deliberately **not** rewritten, per D2's
  own instruction ("sin reescribir el cuerpo de una decisión ya tomada").

**Confirmed by text search** (`grep -rln '\baudit_log\b' docs .claude openspec/specs apps`, minus
`openspec/changes/archive/`): the only remaining occurrence outside the archive is
`docs/adr/ADR-0003-separacion-admin-portal.md` itself — its own historical decision body, left
unrewritten by design, exactly the task's exit criterion.

## Task 2.6 — Measure the real diff of PR B2a

`git diff --numstat change/audit-log-and-transaction-runner...HEAD -- . ':(exclude)openspec' ':(exclude)docs/adr' ':(exclude)**/generated/**'`:

| File | + | − |
|---|---|---|
| `.claude/agents/confia-database.md` | 4 | 4 |
| `.claude/skills/confia-audit-logging/SKILL.md` | 24 | 22 |
| `V2__create_shared_audit_log.sql` (new) | 122 | 0 |
| `RolePrivilegeMatrixIT.java` | 75 | 0 |
| `AuditLogAppendOnlyIT.java` (new) | 153 | 0 |
| `AuditLogRowSecurityIT.java` (new) | 105 | 0 |
| `CommittingBaseContractIT.java` (new) | 39 | 0 |
| `CommittingPostgresIntegrationTest.java` | 6 | 1 |
| `docs/03-seguridad.md` | 60 | 45 |
| `docs/07-observabilidad-y-operaciones.md` | 7 | 7 |
| `docs/08-datos-privacidad-y-retencion.md` | 4 | 4 |
| `docs/runbooks/cierre-de-caja-con-diferencia.md` | 1 | 1 |
| `docs/runbooks/descuadre-de-libro-mayor.md` | 1 | 1 |
| `docs/runbooks/incidente-de-seguridad.md` | 15 | 7 |
| `docs/runbooks/restauracion-de-respaldo.md` | 8 | 3 |

**Total: 624 additions + 95 deletions = 719 authored lines.** Within the 800-line-per-pull-request
budget (`docs/15-flujo-de-trabajo-git.md` §3) and within `design.md` §12's own forecast for B2a
(530-890) — near the middle of the range, not at its high end. **No stop needed; apply continues
into task 2.7 without consulting the owner.**

## Task 2.7 — Final verification of PR B2a

Cleaned `apps/api/app/target`, `apps/api/kernel/target` and `apps/api/target` first (OneDrive
directory-retention interference the brief warns about), then ran `./mvnw -B verify` in `apps/api`
with `JAVA_HOME` on JDK 25 and `MAVEN_OPTS` on the `Windows-ROOT` trust store, without `clean`:

```
CONFIA Kernel: Tests run: 177, Failures: 0, Errors: 0, Skipped: 0
CONFIA API (Surefire, unit): Tests run: 110, Failures: 0, Errors: 0, Skipped: 0
CONFIA API (Failsafe, *IT.java): Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time: 02:24 min
```

Confirmed individually within that run: `RolePrivilegeMatrixIT` (9/9), `AuditLogAppendOnlyIT`
(2/2), `AuditLogRowSecurityIT` (3/3) and `CommittingBaseContractIT` (2/2) all green;
`MultiTenantSchemaIT` (7/7) green and unmodified; the part A suite (`DatabasePipelineIT`,
`JooqInstitutionRepositoryIT`, `PostgresImageSingleSourceTest`) and PR B1's own
(`TransactionRunnerContextIT`, `TransactionRunnerRetryIT`, `TransactionsOnlyInSharedSecurityTest`)
all still green, unaffected by this cut. No `0.00` coverage artifact this run (single clean run
sufficed). No coverage-gate failure: the JaCoCo `PACKAGE` rule for `com.confia.shared.audit` (task
1.9) still passes vacuously — this cut adds no class to that package (the first one is PR B3a).

**Not performed, by explicit orchestrator instruction for this session ("No empujes la rama ni
abras pull requests")**: pushing `change/audit-log-and-transaction-runner-audit-table` and
confirming the `backend` CI job. Task 2.7's checkbox in `tasks.md` is left **unchecked** for this
reason alone — matching the same convention PR B1's own task 1.12 already established in this same
file, whose checkbox is also unchecked pending a push this session did not perform either. Every
other task in this cut (2.1-2.6) is marked complete, with the local, real `./mvnw -B verify`
evidence above standing in place of the CI confirmation until the owner authorizes the push.

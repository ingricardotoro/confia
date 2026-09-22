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

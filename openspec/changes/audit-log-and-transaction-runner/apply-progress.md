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

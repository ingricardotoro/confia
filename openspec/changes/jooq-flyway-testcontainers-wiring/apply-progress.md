# Apply progress: jooq-flyway-testcontainers-wiring — PR A1, PR A2 and PR A3

## PR A3 — corte A3: reglas y puertas de esquema (tasks 3.1–3.9)

Scope: PR A3 only (tasks 3.1–3.9 of `tasks.md`). Branch
`change/jooq-flyway-testcontainers-wiring-gates`, base PR A2
(`change/jooq-flyway-testcontainers-wiring-migration` @ `c23740b`). All four ArchUnit rules, the
schema catalogue gates, and the role privilege matrix are new in this PR; no production code
outside test sources changed.

### TDD Cycle Evidence

Each rule/gate followed the same RED technique as PR A2's compile-error RED (task 2.1 precedent):
the test class was written first, referencing a `RULE` constant, helper method, or record type
that did not yet exist, producing a real `cannot find symbol` compilation failure — never invented
evidence. GREEN was then observed by adding the missing implementation and re-running the same
test.

| Task | RED | GREEN |
|---|---|---|
| 3.1 (R1) | `mvn test-compile`: `cannot find symbol: variable RULE` in `JooqConfinedToInfrastructureTest.java` | `mvn test -Dtest=JooqConfinedToInfrastructureTest`: `Tests run: 2, Failures: 0` |
| 3.2 (R2) | Same technique, `TableOwnershipByModuleTest.java`: `cannot find symbol: variable RULE` | `Tests run: 2, Failures: 0` |
| 3.3 (R3) | Same technique, `TransactionsOnlyInSharedSecurityTest.java`: `cannot find symbol: variable RULE` | First implementation attempt (`noClasses().that().resideOutsideOfPackage(...).should(customCondition)`) compiled but the fixture-rejection test failed with `Expecting code to raise a throwable` — the custom `ArchCondition` never fired under that combinator. Rewritten to `classes().should(condition)` with the "outside shared.security" check done inside the condition itself (same pattern as `NoCrossModuleDomainImportsTest`, R1): `Tests run: 2, Failures: 0` |
| 3.4 (R4) | Same technique, `NoUnapprovedPlainSqlTest.java`: `cannot find symbol: variable RULE` | `Tests run: 2, Failures: 0` |
| 3.5 | `mvn test-compile` on the whole tree: 17 `cannot find symbol` errors in `MultiTenantSchemaIT.java` (missing `BaseTable`, `RowSecurityFlags`, `UniqueIndex` records and every helper method) | `mvn verify -Dit.test=MultiTenantSchemaIT -Dtest=none`: `Tests run: 5, Failures: 0` (JaCoCo's own `jacoco-check` goal failed on this narrow run because Surefire was skipped with `-Dtest=none`, leaving unit-test coverage data out of the merged report — expected and resolved by the full `verify` in task 3.9, not a real gate failure) |
| 3.5b | `mvn test-compile`: `cannot find symbol: method columnCommentOf(...)` | First run: `theDatabaseRejectsAnRtnLongerThanTheTechnicalGuardEvenBypassingTheDomain` failed — assertion expected the message to name the `organization_institution_rtn_digits` CHECK constraint, but the real rejection is PostgreSQL's `VARCHAR(20)` length guard firing first (`ERROR: value too long for type character varying(20)`), before the CHECK constraint is even evaluated. Assertion corrected to match the real message: `Tests run: 7, Failures: 0` |
| 3.6 | `mvn test-compile`: 15 `cannot find symbol` errors in `RolePrivilegeMatrixIT.java` (missing `RoleAttributes` record and both helper methods) | `mvn verify -Dit.test=RolePrivilegeMatrixIT -Dtest=none`: `Tests run: 4, Failures: 0` |

### Task 3.3 deviation: design.md's raw sketch vs. the working implementation

`design.md` §6 sketches R3 as `noClasses().that().resideOutsideOfPackage(...).should(customCondition)`.
That combination compiled and the production-code test passed, but the fixture-rejection test
failed with no violation ever reported — ArchUnit's `noClasses()...should(ArchCondition)` did not
fire the custom condition's `violated(...)` events the way `that().resideOutsideOfPackage(...)`
combined with a custom `ArchCondition` apparently expects (not fully root-caused; empirically
reproducible and empirically fixed). The working implementation instead follows this codebase's own
proven pattern from `NoCrossModuleDomainImportsTest` (R1 predecessor, change 2): `classes().should(condition)`
with the "outside shared.security" package check performed inside the condition itself. `design.md`
explicitly marks its R1–R4 sketches as "esbozo; la firma exacta la fija la implementación" (§6), so
this is an anticipated implementation-level deviation, not a design contradiction — reported here
for transparency rather than silently adopted.

### Task 3.9 — one anomalous timing run, not reproducible

The first clean-tree `./mvnw -B verify` run for task 3.9 measured `JooqInstitutionRepositoryIT`
alone at **448.1 seconds** (vs. 20–27 seconds in every other run of this same class in this PR and
in PR A2), pushing that single run's total to **9 minutes 13 seconds** — over the 8-minute budget.
No warning, retry, connection error, or GC log appeared between the class's Spring context startup
and the final `Tests run: 8` line; nothing in the log explains the stall. Re-running
`JooqInstitutionRepositoryIT` alone immediately after (`mvn verify -Dit.test=JooqInstitutionRepositoryIT
-Dtest=none`) completed in 22.98 seconds — no reproduction. A second full clean-tree `./mvnw -B
verify` completed in **2 minutes 2 seconds** total, with `JooqInstitutionRepositoryIT` at 20.58
seconds — matching every other measurement in this change. Treated as a one-off local
Windows/Docker Desktop resource-contention stall (this machine's `generate-sources` phase runs its
own ephemeral container shortly before the `*IT.java` suite starts its own), not a regression
introduced by this PR's changes; `design.md` §13 already designates CI (`ubuntu-latest`) as the
source of truth over local Windows/WSL2 divergence. Reported here rather than silently discarded,
per this PR's own transparency standard — if this recurs in CI, it needs real investigation, not a
retry loop.

### Task 3.8 — measured PR A3 diff

`git diff --numstat change/jooq-flyway-testcontainers-wiring-migration...HEAD -- . ':(exclude)openspec'
':(exclude)docs/adr' ':(exclude)**/generated/**'` (base is PR A2's tip, `c23740b`):

| File | + | − |
|---|---|---|
| `apps/api/README.md` | 9 | 0 |
| `JooqConfinedToInfrastructureTest.java` (new) | 34 | 0 |
| `NoUnapprovedPlainSqlTest.java` (new) | 91 | 0 |
| `TableOwnershipByModuleTest.java` (new) | 102 | 0 |
| `TransactionsOnlyInSharedSecurityTest.java` (new) | 79 | 0 |
| `fixture/billing/infrastructure/BadForeignTableUser.java` (new) | 19 | 0 |
| `fixture/jooq/application/BadJooqUser.java` (new) | 17 | 0 |
| `fixture/jooq/infrastructure/BadPlainSqlRepository.java` (new) | 26 | 0 |
| `fixture/transactions/BadTransactionalRepository.java` (new) | 19 | 0 |
| `MultiTenantSchemaIT.java` (new) | 288 | 0 |
| `RolePrivilegeMatrixIT.java` (new) | 84 | 0 |

**Total: 768 additions + 0 deletions = 768 authored lines.** Inside the 800-line-per-pull-request
budget (`docs/15-flujo-de-trabajo-git.md` §3), but with only 32 lines of margin — the tightest of
the three chained PRs in this change (A1: 727/800; A2: 424/800; A3: 768/800), consistent with
`design.md` §12's own forecast that A1 and A3 "caben en su rango bajo y rozan o superan 800 en el
alto" (540–880 forecast for A3). No subdivision triggered (A3a/A3b/A3c), but this is close enough
to the ceiling that any additional work in this PR would very likely have required one.

### Task 3.9 — final verification of PR A3 and of the complete change

Second (reproducible) clean-tree run, `app/target` and `kernel/target` deleted, `JAVA_HOME` on JDK
25, `MAVEN_OPTS` with `Windows-ROOT`, Docker active: `./mvnw -B verify` in `apps/api`.

- **`BUILD SUCCESS`**, total time **2 minutes 2 seconds** (`kernel` 16.4 s, `app` 1 minute 43
  seconds). See the note above for the one anomalous 9:13 min run that did not reproduce.
- Kernel: 177 unit tests, 0 failures.
- App unit tests (Surefire): **109 tests**, 0 failures — includes all four new ArchUnit rules
  (`JooqConfinedToInfrastructureTest` 2/2, `TableOwnershipByModuleTest` 2/2,
  `TransactionsOnlyInSharedSecurityTest` 2/2, `NoUnapprovedPlainSqlTest` 2/2),
  `LayeredArchitectureTest` (2/2), `EmptyShouldExceptionInventoryTest` (1/1), and
  `SuppressionCitesAdrTest` (10/10, confirming the `optionalLayer(` count invariant is still
  exactly one after this PR added no new occurrences).
- App integration tests (Failsafe): **21 tests**, 0 failures —
  `JooqInstitutionRepositoryIT` (8/8, 20.58 s), `MultiTenantSchemaIT` (7/7, 0.46 s),
  `RolePrivilegeMatrixIT` (4/4, 0.14 s), `DatabasePipelineIT` (2/2, 0.05 s).
- `git status --short`: clean after the last commit. `git ls-files | grep confia/generated`: no
  matches — no generated jOOQ code tracked.
- JaCoCo: `Analyzed bundle 'confia-api' with 14 classes` (same count as PR A2 — none of this PR's
  new classes are production classes; all four rule tests and both schema `*IT.java` classes are
  test-only), `confia/generated/**` still absent, all coverage rules met (`All coverage checks have
  been met`).
- `SuppressionCitesAdrTest`'s dynamic `optionalLayer(` count: unchanged at exactly one (`design.md`
  decision 9's explicit instruction not to touch this file in this cut was followed — it was not
  edited).
- **Not pushed**: per this session's explicit instruction, pushing `...-gates` and confirming CI
  stays for the propietario, as in PR A1 and PR A2.

### Estado de tareas (PR A3)

- [x] 3.1 — R1 (jOOQ confined to infrastructure): RED/GREEN evidence above; rejects `BadJooqUser`
      by name, passes over the real `JooqInstitutionRepository`.
- [x] 3.2 — R2 (table ownership by module prefix): RED/GREEN evidence above; rejects
      `BadForeignTableUser` by name, passes over the real adapter (only uses its own module's
      generated table type).
- [x] 3.3 — R3 (transactions confined, preventive guard): RED/GREEN evidence above, including the
      design.md-sketch deviation reported above; rejects `BadTransactionalRepository` by name.
- [x] 3.4 — R4 (no unapproved plain SQL): RED/GREEN evidence above; rejects `BadPlainSqlRepository`
      by name; approved list confirmed empty and immutable.
- [x] 3.5 — `MultiTenantSchemaIT` created with all four catalogue points plus the module-prefix
      requirement; membership-based (not presence-based) for the closed catalogue, confirmed by an
      explicit assertion that only two of the four names exist today.
- [x] 3.5b — RTN technical-guard scenarios added to `MultiTenantSchemaIT`; real-schema evidence
      recorded above, including the corrected assertion (varchar length guard, not the named CHECK
      constraint, is what actually fires first).
- [x] 3.6 — `RolePrivilegeMatrixIT` created with both role-attribute reinforcement and the full
      privilege matrix from the A2 migration.
- [x] 3.7 — Suite time measured and documented in `apps/api/README.md`; no update needed in
      `openspec/changes/foundations-plan/exploration.md`'s change-5 division note (no prior timing
      claim there to update).
- [x] 3.8 — diff measured: 768 authored lines, inside the 800-line budget with 32 lines of margin;
      no subdivision triggered.
- [x] 3.9 — final clean-tree `mvn verify`: green, evidence recorded above, including the honest
      report of one non-reproducible anomalous timing run.

---

# Apply progress: jooq-flyway-testcontainers-wiring — PR A1 and PR A2

## PR A2 — corte A2: primera migración y adaptador (tasks 2.1–2.7)

Scope: PR A2 only (tasks 2.1–2.7 of `tasks.md`). Branch
`change/jooq-flyway-testcontainers-wiring-migration`, base PR A1
(`change/jooq-flyway-testcontainers-wiring` @ `f98cbb5`).

### Resolved discrepancy: `TransactionalPostgresIntegrationTest` (flagged at the end of PR A1)

PR A1's own discrepancy note said the first real consumer would decide whether this variant is
needed. It is: created in this PR (`apps/api/app/src/test/java/com/confia/support/TransactionalPostgresIntegrationTest.java`),
a thin `@Transactional` subclass of `PostgresIntegrationTest`, matching design.md decision 7's
description ("Añade `@Transactional`: la transacción de la prueba revierte al terminar"; used for
"Ida y vuelta del adaptador, aislamiento, catálogo"). Verified empirically, not assumed: `mvn
verify` confirms Spring Boot 4.1's separate `spring-boot-jdbc` artifact (pulled in transitively by
`spring-boot-starter-jdbc`, already a dependency since PR A1) ships
`DataSourceTransactionManagerAutoConfiguration` in its `AutoConfiguration.imports`, so a
`PlatformTransactionManager` bean is available with no extra wiring the moment a `DataSource` bean
exists — confirmed by `JooqInstitutionRepositoryIT` actually rolling back seeded rows between its
eight test methods (no test observes another test's seeded row on the one container shared per
JVM). `CommittingPostgresIntegrationTest` was **not** created: nothing in PR A2 needs a real
commit or selective truncation (design.md assigns that variant to future trigger/concurrency/part-B
work, not to this cut).

### Task 2.1/2.2 sequencing note (literal task text vs. Java's whole-file compilation)

Task 2.2's literal text says to run `JooqInstitutionRepositoryIT` "salvo los casos que dependen del
adaptador... verde" immediately after creating only the migration, before `JooqInstitutionRepository`
exists. That is not literally achievable: `JooqInstitutionRepositoryIT.java` (task 2.1) references
`JooqInstitutionRepository` in every test method (even the schema-only duplicate-key rejection
uses the same seeding helper, which calls the shared `withInstitutionContext`, but the class
constructs `repository()` at the top and the file as a whole fails to compile — a single Java
source file either compiles completely or not at all, so no subset of its methods can run — while
that symbol is missing. The same constraint applied, unstated, to PR A1: task 1.4 (RED) and 1.5
(GREEN) were never committed as two separate git commits (commit `89a2445` covers both) precisely
because an intermediate non-compiling state was never pushed to history, only captured as RED
evidence from a local, uncommitted compiler run.

This PR follows the same precedent, extended one step further because the compile dependency spans
three tasks instead of two: `JooqInstitutionRepositoryIT.java` was written in the working tree
(task 2.1), `mvn test-compile` was run and failed with `cannot find symbol:
JooqInstitutionRepository` — the literal RED evidence task 2.1 asks for — and then the migration
(2.2), the adapter and package-info (2.3), and the ADR-0020 layering retirement (2.4) were all
completed in the same working-tree pass before the **first** commit of this PR, so that commit
never represents a broken intermediate state. Task 2.2's schema-level claim (the migration alone
enforces the primary-key duplicate rejection, RLS, and the grants) was verified independently and
for real, without waiting for the adapter: `./mvnw -B -pl app -am generate-sources` ran the new
migration against a real, ephemeral `postgres:18-alpine` through the jOOQ code-generation plugin
(the same container mechanism PR A1 already proved out) and returned `BUILD SUCCESS`, generating
`OrganizationInstitution.java` with `pk=organization_institution_pkey` — direct, real-database
confirmation that the table, its primary key, and the migration are syntactically and semantically
valid before a single line of the adapter existed. The full `JooqInstitutionRepositoryIT` (all
eight test methods, including the duplicate-key rejection against `organization_institution_pkey`)
first became executable once task 2.3 landed, and passed 8/8 on the first run against the schema
task 2.2 had already written — no schema fix was needed after the adapter was added, which is the
real-world confirmation that task 2.2's DDL was already correct on its own. Tasks 2.1 through 2.5
are committed together in one commit (`feat(api): create organization_institution with RLS and its
jOOQ adapter`), for the same compilation-boundary reason PR A1 combined 1.4/1.5.

### TDD Cycle Evidence

| Task | RED | GREEN | REFACTOR |
|---|---|---|---|
| 2.1 | `mvn -pl app -am test-compile`: `COMPILATION ERROR`, `cannot find symbol: class JooqInstitutionRepository` in `JooqInstitutionRepositoryIT.java` (table `organization_institution` also did not exist at this point) | — (green only once 2.2+2.3 land, see below) | — |
| 2.2 | Migration absent: `generate-sources` against the prior schema had no `organization_institution` table at all | `mvn -pl app -am generate-sources`: `BUILD SUCCESS`, real ephemeral `postgres:18-alpine` applied `V1__create_organization_institution.sql` and jOOQ generated `OrganizationInstitution.java` with `pk=organization_institution_pkey` — schema-level proof independent of the adapter (see note above) | n/a |
| 2.3 | Same compile error as 2.1 persists until this task lands | `mvn -pl app -am test -Dtest=JooqInstitutionRepositoryIT -Dsurefire.failIfNoSpecifiedTests=false`: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` (the four base scenarios: round trip, null trade name, unknown id, duplicate-key rejection) | n/a |
| 2.4 | `LayeredArchitectureTest`/`EmptyShouldExceptionInventoryTest` already passed before this change because `Infrastructure` was still `optionalLayer(...)`; the retirement itself has no red state (removing an exception can never fail a rule that was already passing) — verified by running `./mvnw -B verify` immediately after the edit | `./mvnw -B verify`: full suite green, including `LayeredArchitectureTest` (2/2), `EmptyShouldExceptionInventoryTest` (1/1) and `SuppressionCitesAdrTest` (10/10, confirming its dynamic `optionalLayer(` count invariant cleared to exactly one without touching that file) | n/a |
| 2.5 | New isolation test methods did not exist before this edit | `mvn -pl app -am test -Dtest=JooqInstitutionRepositoryIT -Dsurefire.failIfNoSpecifiedTests=false`: `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` (all eight methods, including the four isolation scenarios) on first run — the underlying `NULLIF(...)` policy was already correct from task 2.2, so no schema fix was needed between writing these tests and seeing them pass (see task 2.1/2.2 sequencing note above for why this does not represent literal red-before-green for these four methods specifically) | n/a |

### Real RLS evidence (specs/organization/spec.md, "Aislamiento por fila de la tabla raíz según ADR-0009")

All four scenarios observed against the real schema, in `JooqInstitutionRepositoryIT`, using a
non-superuser `confia_admin_app` connection (never the container's own `postgres` bootstrap
superuser, which would silently bypass `FORCE ROW LEVEL SECURITY` — design.md decision 4):

- `aSessionCannotReadAnotherInstitutionsRow`: session context set to institution A, queries
  institution B's real id — `Optional.empty()`, no exception, even though B's row physically
  exists in the table.
- `aSessionCanReadItsOwnRowEvenWhileAnotherInstitutionExists`: session context set to institution
  A, queries A's own id while B's row also exists — returns exactly A's row.
- `aSessionWithNoContextAtAllIsDeniedRatherThanErroring`: `reset app.institution_id` (clearing any
  transaction-local override set earlier by seeding, since `set_config(..., true)` is
  transaction-local, not statement-local — a genuine gap in the naive approach, documented in the
  new `resetInstitutionContext` helper's Javadoc), then query — `Optional.empty()`, no cast error.
- `aSessionWithAnEmptyContextIsDeniedRatherThanErroring`: `set_config('app.institution_id', '',
  true)`, then query — `Optional.empty()`, no cast error. Confirms `NULLIF(current_setting(...),
  '')` converts the empty-string case to `NULL` before the `::uuid` cast, exactly as design.md
  decision 6 point 1 requires.

`rejectsASecondRowWithTheSameIdentifier` additionally confirms the primary key itself (not RLS)
rejects a duplicate: `org.jooq.exception.DataAccessException` with message containing
`organization_institution_pkey`.

### Task 2.6 — measured PR A2 diff

`git diff --numstat change/jooq-flyway-testcontainers-wiring...HEAD -- . ':(exclude)openspec'
':(exclude)docs/adr' ':(exclude)**/generated/**'` (base is PR A1's tip, `f98cbb5`):

| File | + | − |
|---|---|---|
| `InstitutionRepository.java` (Javadoc update) | 3 | 1 |
| `JooqInstitutionRepository.java` (new) | 69 | 0 |
| `organization/infrastructure/package-info.java` (new) | 8 | 0 |
| `V1__create_organization_institution.sql` (new) | 59 | 0 |
| `EmptyShouldExceptionInventoryTest.java` | 8 | 12 |
| `LayeredArchitectureTest.java` | 11 | 10 |
| `JooqInstitutionRepositoryIT.java` (new) | 207 | 0 |
| `TransactionalPostgresIntegrationTest.java` (new) | 36 | 0 |

**Total: 401 additions + 23 deletions = 424 authored lines.** Well inside both the 800-line
per-pull-request budget (`docs/15-flujo-de-trabajo-git.md` §3) and design.md §12's own forecast for
A2 (405–695). No subdivision triggered; the design's own forecast said "A2 cabe siempre" and this
confirms it.

### Task 2.7 — final verification of PR A2

With `app/target` and `kernel/target` deleted, `JAVA_HOME` on JDK 25, `MAVEN_OPTS` with
`Windows-ROOT`, and Docker active: `./mvnw -B verify` in `apps/api`.

- **`BUILD SUCCESS`**, total time **1 minute 41 seconds** (well under the 8-minute `*IT.java`
  budget).
- Kernel: 177 unit tests, 0 failures.
- App unit tests (Surefire): 101 tests, 0 failures — includes `LayeredArchitectureTest` (2/2),
  `EmptyShouldExceptionInventoryTest` (1/1), `SuppressionCitesAdrTest` (10/10, all passing with
  `Infrastructure` now mandatory and its dynamic `optionalLayer(` count at exactly one).
- App integration tests (Failsafe): 10 tests, 0 failures — `JooqInstitutionRepositoryIT` (8/8, in
  21.7s) and `DatabasePipelineIT` (2/2, still green, confirming PR A1's smoke test is unaffected).
- `git status --short`: clean after the commit. `git ls-files | grep confia/generated`: no
  matches — no generated jOOQ code tracked.
- JaCoCo: `Analyzed bundle 'CONFIA API' with 14 classes` (same count as PR A1; `jacoco.csv`
  confirms `com.confia.organization.infrastructure,JooqInstitutionRepository` is now one of the
  fourteen rows, itself exercised by the eight `*IT.java` tests), `confia/generated/**` still
  absent from the report, all BUNDLE and PACKAGE (`domain`, 95%) coverage rules met.
- **Not pushed**: per this session's explicit instruction, pushing `...-migration` and confirming
  CI stays for the propietario, as in PR A1.

### Estado de tareas (PR A2)

- [x] 2.1 — RED evidence recorded above (compile error).
- [x] 2.2 — migration created; schema-level GREEN evidence recorded above (real Testcontainers
      run, independent of the adapter — see sequencing note).
- [x] 2.3 — adapter, package-info and `InstitutionRepository` Javadoc created/updated; four base
      scenarios GREEN (4/4).
- [x] 2.4 — ADR-0020 retirement done in the same commit as the adapter; `SuppressionCitesAdrTest`
      confirms no manual count edit was needed.
- [x] 2.5 — isolation scenarios added; full suite GREEN (8/8).
- [x] 2.6 — diff measured: 424 authored lines, inside budget.
- [x] 2.7 — final clean-tree `mvn verify`: green, evidence recorded above.

---

# Apply progress: jooq-flyway-testcontainers-wiring — PR A1

Scope: PR A1 only (tasks 1.1–1.10 of `tasks.md`). Branch
`change/jooq-flyway-testcontainers-wiring`, base `main`.

## Task 1.1 — Sonda S1 (resultado real, ejecutado fuera del árbol del repositorio)

Ejecutada en `%TEMP%/s1-probe/` (Windows temp, fuera de `Confia/`), con `JAVA_HOME` en JDK 25 y
`MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT`. Ningún archivo de esa carpeta se
compromete al repositorio.

### (a) `testcontainers-jooq-codegen-maven-plugin:0.0.4` sobre JDK 25, contra `postgres:18-alpine`

**Resultado: funciona, pero solo tras dos sobrescrituras de dependencia del propio complemento.**

El complemento fija en su propio POM `jooq.version=3.18.3` y `testcontainers.version=1.19.1`,
independientemente de lo que declare el proyecto consumidor. Con esas versiones **por defecto**:

1. **Testcontainers 1.19.1 no detecta Docker en esta máquina.** Falla con
   `Could not find a valid Docker environment` contra el pipe con nombre de Windows
   (`npipe:////./pipe/docker_engine`), probando también `dockerDesktopLinuxEngine` y `docker_cli`
   explícitos vía `DOCKER_HOST`: los tres devuelven una respuesta `/info` vacía o 404. El propio
   `docker` CLI y Testcontainers **2.0.5** (usado directamente, ver más abajo) sí conectan sin
   problema en la misma máquina y la misma sesión — el fallo es específico de la versión vieja del
   cliente Docker que este complemento trae empaquetada, no de Docker Desktop ni de JDK 25.
2. **jOOQ 3.18.3 no introspecciona correctamente el catálogo de PostgreSQL 18.** Con Testcontainers
   ya resuelto (ver punto siguiente), la generación falla en `getRelations`/`getPrimaryKey` con
   `IllegalArgumentException: Field (key_seq) is not contained in Row (...)`, y la clase de tabla
   (`ProbeWidget.java`) **no se genera** (solo el `Record`, el catálogo y el esquema). jOOQ 3.18.3
   es anterior al soporte real de PostgreSQL 18.

**Solución aplicada y verificada: sobrescribir las dependencias propias del complemento** en el
bloque `<plugin><dependencies>` (mecanismo estándar de Maven, no una desviación de ADR-0015 ni una
bandera de omisión — el mismo patrón que `apps/api/pom.xml` ya usa para mediar `archunit`):

```xml
<plugin>
  <groupId>org.testcontainers</groupId>
  <artifactId>testcontainers-jooq-codegen-maven-plugin</artifactId>
  <version>0.0.4</version>
  <dependencies>
    <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers</artifactId><version>2.0.5</version></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-postgresql</artifactId><version>2.0.5</version></dependency>
    <dependency><groupId>org.jooq</groupId><artifactId>jooq-codegen</artifactId><version>3.21.7</version></dependency>
    <dependency><groupId>org.jooq</groupId><artifactId>jooq-meta</artifactId><version>3.21.7</version></dependency>
  </dependencies>
  ...
</plugin>
```

Con estas cuatro sobrescrituras (alineando el complemento a las MISMAS versiones que gestiona el
BOM de Spring Boot 4.1.1 para el resto del proyecto — jOOQ 3.21.7, Testcontainers 2.0.5, decisión 3
de `design.md`), `./mvnw generate-sources` termina en `BUILD SUCCESS`, arranca
`postgres:18-alpine`, aplica la migración de prueba y genera `ProbeWidget.java` completo con su
campo `ID` tipado. **Consecuencia según `design.md` §10: S1.3 no falla — se adopta la ruta A
(principal), con esta sobrescritura de dependencias documentada como parte de la configuración de
la tarea 1.7.** No se activa la ruta B ni se eleva ADR: la regla 2 de ADR-0015 se cumple con la
ruta que la propia ADR nombra como ejemplo.

### (b) API real de Testcontainers 2.0.5 frente al patrón de `docs/06` §14.2

**Confirmado por lectura de bytecode y por ejecución real:**

- `org.testcontainers.containers.PostgreSQLContainer<SELF>` (el paquete que usa el patrón de
  `docs/06` §14.2) **sigue existiendo** en `testcontainers-postgresql:2.0.5`, como clase de
  compatibilidad genérica (`@Deprecated`, javac lo confirma con "uses or overrides a deprecated
  API"). El patrón `new PostgreSQLContainer<>(DockerImageName.parse(...))` de `docs/06` **compila y
  ejecuta sin cambios** contra 2.0.5. La ubicación canónica nueva es
  `org.testcontainers.postgresql.PostgreSQLContainer` (sin genérico), que no es necesario adoptar
  para PR A1.
- **Diferencia real y decisiva con el ejemplo de `docs/06` §14.2:** `postgres:18-alpine` **rechaza
  arrancar** con `withTmpFs(Map.of("/var/lib/postgresql/data", ...))` (el path exacto que usa el
  ejemplo de `docs/06`). Mensaje real del contenedor (confirmado con `docker run` manual y con el
  propio Testcontainers, exit code 1, verificado dos veces):

  > in 18+, these Docker images are configured to store database data in a format which is
  > compatible with "pg_ctlcluster" (...). There appears to be PostgreSQL data in:
  > /var/lib/postgresql/data (unused mount/volume). The suggested container configuration for 18+
  > is to place a single mount at /var/lib/postgresql...

  (`docker-library/postgres#1259`.) **Corrección aplicada:** `withTmpFs(Map.of("/var/lib/postgresql",
  "rw,size=..."))`, montando el directorio padre, no `.../data`. Verificado: con ese único cambio el
  contenedor arranca, aplica el `CREATE DATABASE` inicial y queda `ready to accept connections`.
  `docs/06-estrategia-de-testing.md` §14.2 queda desactualizado en este punto para PostgreSQL 18;
  `PostgresIntegrationTest` (tarea 1.5) usa el path corregido, no el de `docs/06`.
- `withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off")` funciona sin cambios.
  `max_connections=200` del ejemplo de `docs/06` no se probó explícitamente pero no interactúa con
  el hallazgo anterior; se mantiene en `PostgresIntegrationTest`.

### (c) ¿El `DSLContext` de Spring Boot 4.1 se une a la transacción de la prueba?

**Hallazgo previo y más importante que la pregunta original: Spring Boot 4.1.1 NO trae
autoconfiguración de jOOQ.** Se verificó por inspección directa del jar
`spring-boot-autoconfigure-4.1.1.jar`: cero clases bajo `org/springframework/boot/autoconfigure/jooq/`,
cero menciones de jOOQ en `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
La clase `JooqAutoConfiguration` que existía en la línea 3.x de Spring Boot **no existe en 4.1**.
Una prueba `@SpringBootTest` con `@EnableAutoConfiguration` y una fuente de datos real **no obtiene
ningún bean `DSLContext`** — `@Autowired DSLContext` falla con `NoSuchBeanDefinitionException`.
**Esto contradice el supuesto de `design.md` decisión 7** («el contexto trae fuente de datos,
Flyway y DSLContext y nada más»): el `DSLContext` no llega gratis y debe declararse explícitamente.

**Con un bean `DSLContext` declarado explícitamente** (mismo mecanismo que usaba la
autoconfiguración retirada: `DataSourceConnectionProvider` envolviendo un
`TransactionAwareDataSourceProxy`):

```java
@Bean
DSLContext dsl(DataSource dataSource) {
    var connectionProvider =
        new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource));
    return DSL.using(new DefaultConfiguration()
        .set(connectionProvider)
        .set(SQLDialect.POSTGRES));
}
```

**Verificado con una prueba real:** dentro de un método `@Transactional` de prueba,
`dsl.execute("select set_config('probe.marker', ?, true)", "expected-value")` seguido de
`dsl.fetchOne("select current_setting('probe.marker', true)")` en la MISMA transacción de Spring
devuelve `expected-value`. **La pregunta (c) original queda respondida: SÍ se une**, siempre que el
bean se declare así. No hace falta el respaldo de "misma conexión obtenida explícitamente" que
`design.md` §10 preveía para el caso de fallo — el mecanismo estándar de jOOQ + Spring funciona,
solo había que declararlo a mano porque la autoconfiguración que se asumía no existe en Spring Boot
4.1.

**Consecuencia para la tarea 1.5:** `IntegrationTestApplication` (o `PostgresIntegrationTest`) DEBE
declarar este `@Bean DSLContext` explícito. La propiedad `spring.jooq.sql-dialect` de
`application.yml` (tal como la nombra la tarea 1.5) **no tiene ningún efecto** en Spring Boot 4.1
porque no hay ninguna autoconfiguración que la lea: se documenta como nota en el propio archivo en
vez de dejarla como configuración muerta sin explicación.

### Resumen de la consecuencia según `design.md` §10 y `tasks.md` 1.1

- S1.1: ya verificado por el orquestador (no repetido).
- S1.2: el complemento resuelve. ✅
- S1.3: el complemento **sí** corre sobre JDK 25 y levanta `postgres:18-alpine`, **una vez
  sobrescritas sus dependencias propias** de Testcontainers (1.19.1→2.0.5) y jOOQ (3.18.3→3.21.7)
  en el `<plugin><dependencies>` del propio `app/pom.xml`. Se adopta **ruta A**, con esa
  sobrescritura documentada como parte del cableado de la tarea 1.7. No se activa ruta B, no se
  eleva ADR.
- S1.4: verificado con una migración real de dos ubicaciones de Flyway (una `beforeMigrate__` sin
  contraseña) y un `GRANT`: `BUILD SUCCESS`, `Applied 1 flyway migrations` sobre la ubicación real,
  rol de solo-generación creado por el callback, `GRANT` aplicado sin error.
- S1.5: patrón de `docs/06` §14.2 compila contra Testcontainers 2.0.5 (paquete de compatibilidad),
  con una diferencia real y corregida: `withTmpFs` debe montar `/var/lib/postgresql`, no
  `/var/lib/postgresql/data`, para `postgres:18-alpine`.
- S1.6: el `DSLContext` sí se une a la transacción de la prueba, con un bean declarado a mano
  porque Spring Boot 4.1 no trae autoconfiguración de jOOQ (hallazgo adicional, documentado arriba).

**Ninguna consecuencia de "ninguna ruta con Docker funciona" aplica.** No se detiene la aplicación,
no se eleva ningún ADR que reemplace ADR-0015 regla 2.

---

## Estado de tareas

- [x] 1.1 — Sonda S1 completa, evidencia registrada arriba.
- [x] 1.2 — `confia.postgres.image` y la versión del complemento de generación declarados en
      `apps/api/pom.xml` (propiedades + `pluginManagement`). `./mvnw -B -N validate`: `BUILD
      SUCCESS`, las tres reglas del enforcer pasan. No se fijaron coordenadas 2.x de Testcontainers
      en `dependencyManagement`: se confirmará en la tarea 1.3 si el BOM de Spring Boot 4.1.1 ya las
      gestiona (evidencia de `design.md` sección 2 indica que sí, bajo los nombres nuevos
      `testcontainers-postgresql`/`testcontainers-junit-jupiter`).
- [x] 1.3 — jOOQ, Flyway (`flyway-core` + `flyway-database-postgresql`), el controlador
      PostgreSQL y Testcontainers 2.x (`testcontainers-postgresql`, `testcontainers-junit-jupiter`,
      alcance `test`) añadidos a `apps/api/app/pom.xml`, sin versión en ninguno. `./mvnw -B -pl
      apps/api/app -am validate`: `BUILD SUCCESS`, `dependencyConvergence` pasa a la primera —
      confirma que el BOM de Spring Boot 4.1.1 ya gestiona los nombres 2.x de Testcontainers, sin
      necesitar ninguna fijación en `dependencyManagement`.

## Hallazgo adicional durante la tarea 1.5 (no cubierto por la sonda S1)

**Spring Boot 4.1 partió su antiguo jar monolítico `spring-boot-autoconfigure` en un módulo por
funcionalidad.** Confirmado leyendo `spring-boot-autoconfigure-4.1.1.jar` completo: solo 258 clases
en total, sin ningún paquete `jdbc`, `sql`, `flyway` ni `jooq`. La autoconfiguración de Flyway vive
ahora en el artefacto separado `org.springframework.boot:spring-boot-flyway` (gestionado por el BOM,
sin versión propia), que **no** llega transitivamente ni con `flyway-core` ni con
`spring-boot-starter-jdbc`. Sin declararlo explícitamente, `FlywayAutoConfiguration` nunca se activa
y `DatabasePipelineIT` falla con `relation "flyway_schema_history" does not exist` porque Flyway
nunca corrió. **Añadido a `apps/api/app/pom.xml`** (tarea 1.5, ver evidencia abajo). jOOQ no tiene
ningún módulo equivalente: sigue sin autoconfiguración alguna (hallazgo de la tarea 1.1, S1.6).

**Consecuencia adicional para `DatabasePipelineIT` (ajuste de mi propia implementación de la tarea
1.4, no del diseño):** con Flyway corriendo mas sin ninguna migración de negocio todavía (`V1` es
tarea 2.2, en PR A2), Flyway crea `flyway_schema_history` pero con **cero filas** («Schema "public"
is up to date. No migration necessary.», log real). La redacción literal de la tarea 1.4 («con al
menos una fila») no es alcanzable dentro del alcance real de A1. Se implementó, en su lugar, una
comprobación de **existencia de la tabla por catálogo** (`to_regclass('public.flyway_schema_history')`,
que no exige ningún `GRANT` porque es una consulta de catálogo, no un acceso a la tabla), que sigue
demostrando honestamente que Flyway corrió como `confia_owner`. La fila real llegará con la
migración de A2, y el `GRANT SELECT` de `confia_admin_app` sobre `flyway_schema_history` también es
parte de esa misma migración (`design.md`, decisión 6) — nunca del script de roles solo de prueba,
que no puede otorgar permisos sobre una tabla que todavía no existe cuando se ejecuta.

## Estado de tareas

- [x] 1.1 — Sonda S1 completa, evidencia registrada arriba.
- [x] 1.2 — `confia.postgres.image` y la versión del complemento de generación en
      `apps/api/pom.xml`. `./mvnw -B -N validate`: `BUILD SUCCESS`.
- [x] 1.3 — jOOQ, Flyway, el controlador PostgreSQL y Testcontainers 2.x añadidos a
      `apps/api/app/pom.xml`. `./mvnw -B -pl apps/api/app -am validate`: `BUILD SUCCESS`,
      `dependencyConvergence` pasa a la primera.
- [x] 1.4/1.5 — **ROJO** (task 1.4): `DatabasePipelineIT.java` creado extendiendo
      `com.confia.support.PostgresIntegrationTest`, que no existe todavía. `./mvnw -B -pl
      apps/api/app -am test-compile`: `COMPILATION ERROR`, `cannot find symbol: class
      PostgresIntegrationTest` en `DatabasePipelineIT.java:25`. **VERDE** (task 1.5): creados
      `db/testing/create-test-roles.sql`, `PostgresIntegrationTest.java`,
      `IntegrationTestApplication.java`, `application.yml`, `application-migrate.yml`; corregido el
      comentario de `ConfiaApplication.MIGRATE`. Añadidas dos dependencias descubiertas como
      necesarias durante esta tarea: `spring-boot-starter-jdbc` (sin la cual no hay
      `DataSourceAutoConfiguration` ni `TransactionAwareDataSourceProxy`) y `spring-boot-flyway`
      (ver hallazgo arriba). `./mvnw -B -pl apps/api/app -am test -Dtest=DatabasePipelineIT`:
      `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`. Contenedor `postgres:18-alpine` arrancado
      con `withUsername("postgres")` (decisión 4), `withTmpFs` sobre `/var/lib/postgresql`
      (corrección S1.5), rol de aplicación `confia_admin_app` verificado por `current_user`, cinco
      roles verificados `rolsuper=false`/`rolbypassrls=false` contra `pg_roles`.
- [x] 1.6 — **ROJO**: `PostgresImageSingleSourceTest.java` creado, referencia
      `PostgresIntegrationTest.postgresImage()`, que no existe. `./mvnw -B -pl apps/api/app -am
      test-compile`: `COMPILATION ERROR`, `cannot find symbol: method postgresImage()`. **VERDE**:
      `confia-build.properties` creado con `postgres.image=${confia.postgres.image}`; filtrado
      cableado en `apps/api/app/pom.xml` con dos `<testResource>` (uno sin filtrar que excluye ese
      archivo, otro que lo filtra en exclusiva); `PostgresIntegrationTest` refactorizado para leer
      la propiedad filtrada en vez del literal de la tarea 1.5. `./mvnw -B -pl apps/api/app -am
      test -Dtest=PostgresImageSingleSourceTest,DatabasePipelineIT`: `Tests run: 4, Failures: 0,
      Errors: 0, Skipped: 0`.
- [x] 1.7 — Complemento de generación cableado en `apps/api/app/pom.xml` (fase `generate-sources`,
      ruta A con la sobrescritura de dependencias de la tarea 1.1); `beforeMigrate__create_codegen_roles.sql`
      creado. **Tres hallazgos reales durante esta tarea, documentados abajo.** Demostración
      deliberada ejecutada con una migración y una consulta temporales (nunca comprometidas):
      VERDE con `BUILD SUCCESS` y la clase `CodegenDemoTemp` generada; ROJO tras renombrar la
      columna que la consulta referencia, con el mensaje exacto `cannot find symbol: variable
      NAME, location: variable CODEGEN_DEMO_TEMP of type confia.generated.jooq.tables.CodegenDemoTemp`.
      Migración y consulta temporales revertidas; `git status` limpio confirmado. Exclusión de
      JaCoCo (`jacoco-report` y `jacoco-check`) con cita ADR-0021 a dos líneas de cada `<exclude>`;
      verificado leyendo `jacoco.csv` con la tabla temporal presente: las cuatro clases generadas
      compilan en `target/classes/confia/generated/jooq/**` pero no aparecen en el informe ni
      afectan el umbral. `./mvnw -B -pl apps/api/app -am verify` en checkout limpio (`app/target`
      borrado): `BUILD SUCCESS`, 101 pruebas unitarias + 2 `*IT.java`, cobertura en verde.

  **Hallazgo 1 — las dos ubicaciones de Flyway deben ser `filesystem:`, no `classpath:`.**
  `generate-sources` corre antes que `process-resources` copie `src/main/resources` a
  `target/classes`, así que una ubicación `classpath:db/migration` escanea un directorio vacío o
  desactualizado; Flyway lo reporta como "No migrations found" sin fallar. Corregido a
  `filesystem:${project.basedir}/src/main/resources/db/migration,filesystem:${project.basedir}/src/test/resources/db/codegen`.

  **Hallazgo 2 — las rutas `filesystem:` deben ser absolutas (`${project.basedir}`), no
  relativas.** Un path relativo se resuelve contra el directorio de trabajo de la propia JVM de
  Maven, que permanece fijo en el directorio desde el que se invocó `mvn` en TODO el reactor (no
  cambia por módulo); al construir con `-pl app` desde `apps/api`, una ruta relativa como
  `src/main/resources/db/migration` se resolvía como `apps/api/src/...` en vez de
  `apps/api/app/src/...`, y Flyway registraba `Skipping filesystem location ... (not found)`.

  **Hallazgo 3 — `apps/api/pom.xml` tenía `<append>false</append>` en `jacoco-prepare-agent`, roto
  para cualquier módulo con Surefire y Failsafe en la misma construcción.** Surefire y Failsafe
  corren en JVM bifurcadas separadas dentro del mismo `mvn verify`, cada una con el mismo agente;
  con `append=false`, la que termina último trunca `jacoco.exec` a solo sus propios datos. Como
  `app` no tenía ninguna clase `*IT.java` real antes de `DatabasePipelineIT` (tarea 1.4/1.5), este
  defecto nunca se había disparado en todo el repositorio. Confirmado en vivo: con `append=false`,
  `./mvnw -pl apps/api/app -am verify` reportaba `lines covered ratio is 0.00` para **cada** clase
  de `app`, incluidas las que sí tienen pruebas unitarias reales (`Institution`, etc.), porque
  Failsafe pisaba los datos de Surefire. Corregido a `append=true` (el valor por defecto de JaCoCo)
  en `apps/api/pom.xml`, con el riesgo original que motivó `append=false` (datos obsoletos de una
  construcción anterior más angosta) acotado a que la integración continua siempre parte de un
  `actions/checkout` limpio (`.github/workflows/ci.yml`), nunca reutiliza un `target/` viejo.
- [x] 1.8 — `apps/api/README.md` creado (requisito de Docker en la construcción, no solo en las
      pruebas). `openspec/config.yaml`: nota de Docker junto a `test_command` de `apply`.
      `.github/workflows/ci.yml`: `timeout-minutes: 15` en el trabajo `backend` (cubre toda la
      construcción, no solo la suite de `*IT.java`), comentario de la dependencia real de Docker y
      mención del presupuesto de 8 minutos. **Tiempo medido, no estimado**: `./mvnw -B verify`
      completo en `apps/api` desde `target/` borrado: **1 minuto 31 segundos** (`kernel` 16.1s,
      `app` 1:14 min); `DatabasePipelineIT`, la única clase que arranca un contenedor en esta PR:
      **18.2 segundos**. `SuppressionCitesAdrTest` confirmado en verde tras estos cambios.
- [x] 1.9 — `git diff --numstat main...change/jooq-flyway-testcontainers-wiring -- . ':(exclude)openspec'
      ':(exclude)docs/adr' ':(exclude)**/generated/**'`: **727 líneas de autor** (adiciones más
      eliminaciones), medidas después de la tarea 1.8. Cabe en el presupuesto de 800 líneas de
      `docs/15-flujo-de-trabajo-git.md` §3; no se activa ninguna subdivisión (A1a/A1b). Desglose
      completo registrado en el propio comando; los archivos más grandes son
      `apps/api/app/pom.xml` (186), `apps/api/app/src/test/java/com/confia/support/PostgresIntegrationTest.java`
      (116) y `apps/api/app/src/test/java/com/confia/support/DatabasePipelineIT.java` (98).
- [x] 1.10 — **Verificación local completa.** Con `app/target` y `kernel/target` borrados,
      `JAVA_HOME` en JDK 25, `MAVEN_OPTS` con `Windows-ROOT` y Docker activo: `./mvnw -B verify` en
      `apps/api`: `BUILD SUCCESS`. `DatabasePipelineIT` y `PostgresImageSingleSourceTest` en
      verde (2/2 cada una). `git status --short`: limpio; `git ls-files | grep confia/generated`:
      ningún archivo generado bajo control de versiones; `git check-ignore -v` confirma que
      `target/generated-sources/jooq/confia/generated/jooq/**` cae bajo la regla `target/` de
      `.gitignore`. `jacoco.csv`: catorce filas (las clases reales de `app`), cero filas de
      `confia.generated`, sin ninguna regla de cobertura violada. **No empujado**: el orquestador
      instruyó explícitamente no empujar la rama ni abrir pull requests en esta sesión de
      aplicación; empujar `change/jooq-flyway-testcontainers-wiring` y confirmar el trabajo
      `backend` en verde en la integración continua queda para el propietario.

## Discrepancia reportada, no aplicada en silencio: variantes de `PostgresIntegrationTest`

`design.md` §5 (tabla de archivos, autoritativa por la nota de reconciliación de `tasks.md`) asigna
la creación de `TransactionalPostgresIntegrationTest.java` y `CommittingPostgresIntegrationTest.java`
a PR A1, y `proposal.md` punto de alcance 5 exige explícitamente "dos variantes" de
`PostgresIntegrationTest`. **La redacción literal de la tarea 1.5 de `tasks.md`, sin embargo, no
nombra ninguna de las dos clases** entre los archivos que crea, y ningún archivo de PR A1 las
necesita: `DatabasePipelineIT` y `PostgresImageSingleSourceTest` no tocan ninguna fila de negocio
que requiera revertirse ni confirmarse. Se siguió la redacción literal de `tasks.md` (la lista de
tareas que gobierna esta aplicación) y **no se crearon** esas dos subclases en este corte. El
primer consumidor real es `JooqInstitutionRepositoryIT` de la tarea 2.1 (PR A2), que si necesita
reversión automática deberá crear `TransactionalPostgresIntegrationTest` en ese mismo corte. Se
reporta aquí en vez de decidirlo en silencio, tal como pide el resto de este documento para la
discrepancia ya conocida entre `design.md` §5 y §11.


## Defecto encontrado en la verificación del orquestador: `information_schema` filtra por privilegios

`MultiTenantSchemaIT.baseTablesInPublicSchema()` leía `information_schema.tables` e
`information_schema.columns`. Esas vistas están filtradas por los privilegios del rol que consulta.
Las pruebas de integración conectan como `confia_admin_app`, de modo que una tabla creada sin
`institution_id`, sin seguridad a nivel de fila y sin ningún `GRANT` resultaba **invisible** para
tres de las cuatro puertas de catálogo y las pasaba todas en verde. Es exactamente la tabla que esas
puertas existen para rechazar.

Encontrado por control negativo, no por lectura del código:

1. Migración temporal `V2__negative_control.sql` con una tabla `tmp_import`: las siete pruebas de la
   clase pasaron en verde. La puerta no veía la tabla.
2. Añadido `GRANT SELECT ON tmp_import TO confia_admin_app`: la puerta falló nombrando `tmp_import`.
   Hipótesis confirmada.
3. Consulta migrada a `pg_class` + `pg_namespace` + `pg_attribute` (`relkind in ('r','p')`,
   `attnotnull`, `not attisdropped`).
4. Retirado el `GRANT`: la puerta sigue viendo y rechazando `tmp_import`. Arreglo probado en ambas
   direcciones.
5. Migración de control eliminada.

`rowSecurityFlagsOf`, `policyCountOn` y `uniqueIndexesInPublicSchema` ya consultaban `pg_catalog`,
así que bastó corregir un único método del que dependían tres puertas.

En el mismo commit (`44efce8`) se corrigieron dos defectos menores de la misma clase:

- El regex del prefijo de módulo, `^[a-z][a-z0-9]*_[a-z0-9_]+$`, aceptaba cualquier prefijo:
  `tmp_import` o `legacy_data` habrían pasado una puerta cuyo propósito declarado es nombrar el
  módulo propietario de la tabla. Ahora el prefijo se contrasta contra los nombres de módulo
  derivados de los paquetes de producción reales, el mismo criterio de
  `NoCrossModuleDomainImportsTest`.
- La misma prueba cerraba con `assertThat(ROOT_TABLE).startsWith("organization_")`, que compara una
  constante de compilación contra un literal: no podía fallar jamás y se leía como una aserción de
  esquema sin verificar nada. Eliminada; el bucle anterior ya evalúa la tabla real.

**Regla general que deja este defecto:** una prueba de catálogo de esquema que corre con un rol de
mínimo privilegio debe leer `pg_catalog`, nunca `information_schema`.

# CONFIA API

Maven reactor for the CONFIA backend (`kernel`, `app`). See `docs/01-arquitectura.md` for the
architecture this reactor implements and `openspec/changes/jooq-flyway-testcontainers-wiring/`
for the change that wired the sections below.

## Docker is required to build, not only to test

`./mvnw verify` **requires a running Docker engine**, even for a build that only touches unit
tests. The `app` module generates its jOOQ data-access code from the real Flyway migrations in the
`generate-sources` phase, against a temporary `postgres:18-alpine` container
(`testcontainers-jooq-codegen-maven-plugin`, ADR-0015 rule 2). That phase runs **before**
`compile`, so without Docker the build fails immediately, before a single class compiles — not
later, in the test phases.

There is no flag to skip this and fall back to committed generated code: ADR-0015 rule 2 forbids
committing generated jOOQ sources, and ADR-0008 forbids a skip flag on any gate. If the generation
mechanism ever became genuinely unworkable, the fix is an ADR that replaces ADR-0015 rule 2, never
a silent bypass (`openspec/changes/jooq-flyway-testcontainers-wiring/design.md`, section 10).

The `*IT.java` integration test suite (Failsafe) also needs Docker: each class extends
`com.confia.support.PostgresIntegrationTest`, which starts one `postgres:18-alpine` container per
test JVM.

## Building locally on Windows

```
JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot" \
MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT" \
./mvnw -B verify
```

`MAVEN_OPTS` above works around a Windows-ROOT trust-store gap that otherwise blocks new Maven
Central downloads on some machines; it is never required in CI, never committed as a project
default, and never needed if your machine's trust store already resolves Maven Central directly.

Docker Desktop must be running before `./mvnw verify`. Testcontainers 2.0.5 (what this build
actually uses, via the dependency overrides in `app/pom.xml`'s codegen plugin execution) connects
through the default Windows named pipe without any extra configuration. If a `Could not find a
valid Docker environment` error ever appears despite `docker` itself working from the shell, check
`docker context ls` for the active context's endpoint (`docker context inspect <name>`) before
assuming Docker itself is the problem.

## Integration test suite budget

The full `*IT.java` suite (Failsafe) is measured, not estimated, at each pull request that adds to
it (`docs/06-estrategia-de-testing.md`, 8-minute budget). Measured on 2026-09-21, PR A1
(`DatabasePipelineIT` and `PostgresImageSingleSourceTest`, the only two classes with a real
database in this pull request): the full `./mvnw -B verify` reactor build (`kernel` + `app`, unit
tests, architecture tests, jOOQ generation, and the integration-test suite) took **1 minute 31
seconds**; `DatabasePipelineIT` itself, which owns the only container start in this suite, ran in
**18.2 seconds**. Both are far inside the 8-minute budget.

**Measurement, PR A3, with the complete `*IT.java` suite of change 4** (`DatabasePipelineIT`,
`JooqInstitutionRepositoryIT`, `MultiTenantSchemaIT`, `RolePrivilegeMatrixIT` — 21 integration test
methods total, one shared container per JVM per design.md decision 7): from a clean `app/target`
and `kernel/target`, the full `./mvnw -B verify` reactor build took **2 minutes 4 seconds** total
(`kernel` 12.8 s, `app` 1 minute 49 seconds, including jOOQ code generation against its own
ephemeral container). The Failsafe `integration-test` phase itself — container start plus all four
`*IT.java` classes — ran in about **30 seconds**. Both remain far inside the 8-minute budget, with
no sign of approaching it as the suite has grown from 2 to 21 test methods across this change.

**Measurement, PR B1 (change 5, part B), with `TransactionRunnerContextIT` and
`TransactionRunnerRetryIT` added** (design.md §11, step 8 and §13). From a clean `app/target` and
`kernel/target`, per-line timestamped to isolate the Failsafe phase precisely rather than reading it
off the reactor's own per-module summary: the `failsafe:integration-test` goal for `confia-api`
(container reuse across the whole suite; container start already paid by `DatabasePipelineIT`, the
first class to run) took **39.5 seconds** for all six `*IT.java` classes now in the suite
(`DatabasePipelineIT`, `JooqInstitutionRepositoryIT`, `MultiTenantSchemaIT`, `RolePrivilegeMatrixIT`,
`TransactionRunnerContextIT`, `TransactionRunnerRetryIT` — 28 integration test methods total). The
full reactor build (`kernel` + `app`, unit tests, architecture tests, jOOQ generation, and the
integration-test suite) took **2 minutes 26 seconds** total, `confia-api` alone **2 minutes 6
seconds**. Both remain far inside the 8-minute (480-second) budget: the Failsafe phase itself uses
about 8% of it, even with `TransactionRunnerRetryIT`'s own concurrency scenario (a real `CyclicBarrier`
synchronization and a genuine PostgreSQL `SERIALIZABLE` write conflict) included.

**Measurement, PR C3 (change 6, `idempotency-key-infrastructure`, final cut), with the complete
`*IT.java` suite of the whole change** (`IdempotencyKeyPrivilegeIT`, `JooqIdempotencyRecordStoreIT`,
`IdempotentExecutorIT`, `IdempotentExecutorConcurrencyIT`, `IdempotencyExitCriterionIT`,
`IdempotencyExpiryIT` added across PR C1 through PR C3, on top of every class already listed above —
80 integration test methods total for `confia-api`, plus the kernel and API unit suites). From an
already-built tree (no `mvn clean`; OneDrive locks `target/` directories against Maven's own clean
goal on Windows, so removed by hand instead when needed), the full `./mvnw -B verify` reactor build
took **4 minutes 28 seconds** total (`kernel` 28 seconds, `app` 3 minutes 53 seconds, including jOOQ
code generation). Comfortably inside the 8-minute budget — under 56% of it — even with two real
bounded-wait concurrency scenarios now in the suite
(`IdempotentExecutorConcurrencyIT`'s three `lock_timeout` outcomes and
`IdempotencyExitCriterionIT`'s own two-thread race over the same idempotency key), each paying a
real, deterministic `CyclicBarrier`-synchronized wait by design rather than a wall-clock guess. No W1
escalation needed at this cut (`docs/09-roadmap-y-fases.md`, entregable 8's still-open point (a): the
8-minute budget continues to be measured and documented, not yet enforced by a CI gate).

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

**Final measurement, PR A3, with the complete `*IT.java` suite** (`DatabasePipelineIT`,
`JooqInstitutionRepositoryIT`, `MultiTenantSchemaIT`, `RolePrivilegeMatrixIT` — 21 integration test
methods total, one shared container per JVM per design.md decision 7): from a clean `app/target`
and `kernel/target`, the full `./mvnw -B verify` reactor build took **2 minutes 4 seconds** total
(`kernel` 12.8 s, `app` 1 minute 49 seconds, including jOOQ code generation against its own
ephemeral container). The Failsafe `integration-test` phase itself — container start plus all four
`*IT.java` classes — ran in about **30 seconds**. Both remain far inside the 8-minute budget, with
no sign of approaching it as the suite has grown from 2 to 21 test methods across this change.

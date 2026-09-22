package com.confia.architecture.fixture.naming;

import com.confia.support.PostgresIntegrationTest;

/**
 * Permanent negative fixture for {@code com.confia.architecture.IntegrationTestNamingTest}
 * (design.md decision 13; specs/build-integrity/spec.md, requirement "Nomenclatura obligatoria
 * {@code *IT} para toda subclase de {@code PostgresIntegrationTest}", deuda W3): extends {@link
 * PostgresIntegrationTest} and is named with the {@code Test} suffix instead of {@code IT} — the
 * exact violation this rule exists to reject.
 *
 * <p><b>Deliberately declares no {@code @Test} method</b> (sonda S11, apply-progress.md task 6.1).
 * With the container behind {@code com.confia.support.SharedPostgresContainer}'s lazy holder
 * (design.md decision 14), a probe run confirmed empirically that Surefire selecting a
 * zero-method {@code *Test} subclass of {@link PostgresIntegrationTest} by file-name pattern, and
 * the JUnit platform loading the class to look for test methods, produces no test descriptor and
 * starts no PostgreSQL container. This fixture exists purely for {@code ArchitectureTestSupport}'s
 * bytecode scan; it is never meant to run as a real test.
 */
class BadlyNamedContainerTest extends PostgresIntegrationTest {
}

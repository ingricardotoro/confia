package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Trivial smoke test for the tooling itself: Java 25, the Spring Boot 4.1 BOM version management,
 * JUnit 5 and AssertJ resolving and running through {@code ./mvnw -pl apps/api/kernel test}. It
 * asserts nothing about CONFIA's domain because {@code kernel} has no business rule yet.
 */
class BuildSmokeTest {

    @Test
    void theKernelModuleBuildsAndRunsATrivialTest() {
        assertThat(1 + 1).isEqualTo(2);
    }
}

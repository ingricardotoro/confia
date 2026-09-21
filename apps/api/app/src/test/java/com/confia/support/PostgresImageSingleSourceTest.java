package com.confia.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Single source of truth for the PostgreSQL image used across the build (ADR-0015 rule 1;
 * design.md decision 3; specs/build-integrity/spec.md, requirement "Fuente única de la versión de
 * PostgreSQL en la construcción"). {@code confia-build.properties} is filtered by
 * maven-resources-plugin from {@code apps/api/pom.xml}'s {@code confia.postgres.image} property —
 * the same property the jOOQ code-generation plugin reads (task 1.7) — so
 * {@link PostgresIntegrationTest} never hardcodes a second, independent image literal.
 *
 * <p>Deliberately red until the filtering is wired in {@code app/pom.xml} (task 1.6): without it,
 * the property in the classpath resource is still the literal, unfiltered {@code
 * "${confia.postgres.image}"} placeholder, and {@link PostgresIntegrationTest} still has its own
 * hardcoded literal from task 1.5.
 */
class PostgresImageSingleSourceTest {

    @Test
    void confiaBuildPropertiesIsFilteredWithARealPostgres18Image() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = PostgresImageSingleSourceTest.class.getClassLoader()
                .getResourceAsStream("confia-build.properties")) {
            assertThat(in).as("confia-build.properties must be on the test classpath").isNotNull();
            properties.load(in);
        }

        String image = properties.getProperty("postgres.image");

        assertThat(image)
                .as("maven-resources-plugin must filter confia-build.properties, not copy it raw")
                .isNotNull()
                .doesNotContain("${confia.postgres.image}");
        assertThat(image)
                .as("ADR-0015 rule 1: PostgreSQL major version 18")
                .startsWith("postgres:18");
    }

    @Test
    void postgresIntegrationTestUsesTheSameFilteredImage() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = PostgresImageSingleSourceTest.class.getClassLoader()
                .getResourceAsStream("confia-build.properties")) {
            properties.load(in);
        }

        assertThat(PostgresIntegrationTest.postgresImage())
                .as("PostgresIntegrationTest must build its container from the single-sourced "
                        + "property, never a second hardcoded literal")
                .isEqualTo(properties.getProperty("postgres.image"));
    }
}

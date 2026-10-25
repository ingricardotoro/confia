package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Specs/background-jobs BJ48 and build-integrity BI114 (background-jobs-with-db-scheduler design.md
 * decision 4): every auto-configuration the db-scheduler starter declares is excluded in the
 * administrative and the portal entry points, so only the worker can ever hold an executor. The
 * list is read from the class path, not written here: a new auto-configuration in a later library
 * version breaks the build naming the class and the entry point, instead of silently reaching a
 * process that must not run jobs.
 *
 * <p>No {@code db-scheduler.enabled=false}: a property is a switch an environment can flip; the
 * exclusion lives in the code of the entry point (decision 4, owner decision D2 option B).
 */
class StarterAutoConfigurationExcludedTest {

    private static final String IMPORTS_FILE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** The artifact whose imports file this test reads; the others belong to Spring Boot itself. */
    private static final String STARTER_ARTIFACT = "db-scheduler-spring-boot-4-starter";

    private static final String ADMIN = "com.confia.bootstrap.admin.AdminApplication";
    private static final String PORTAL = "com.confia.bootstrap.portal.PortalApplication";

    @Test
    void theStarterDeclaresItsAutoConfigurationsOnTheClassPath() {
        assertThat(starterAutoConfigurations())
                .as("non-vacuous: the starter must be on the class path and declare entries, or "
                        + "the exclusion check below would pass over an empty list")
                .contains("com.github.kagkarlsson.scheduler.boot.autoconfigure."
                        + "DbSchedulerAutoConfiguration");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ADMIN, PORTAL})
    void everyStarterAutoConfigurationIsExcludedInTheEntryPoint(String entryPoint)
            throws ClassNotFoundException {
        assertThat(notExcluded(Class.forName(entryPoint), starterAutoConfigurations()))
                .as("auto-configurations of the db-scheduler starter not excluded in %s", entryPoint)
                .isEmpty();
    }

    /** Negative control: an invented entry that nobody excluded must be reported by name. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {ADMIN, PORTAL})
    void anAutoConfigurationNobodyExcludedIsReportedByName(String entryPoint)
            throws ClassNotFoundException {
        List<String> entries = new ArrayList<>(starterAutoConfigurations());
        entries.add("com.github.kagkarlsson.scheduler.boot.autoconfigure.InventedAutoConfiguration");

        assertThat(notExcluded(Class.forName(entryPoint), entries)).containsExactly(
                "com.github.kagkarlsson.scheduler.boot.autoconfigure.InventedAutoConfiguration");
    }

    private static List<String> notExcluded(Class<?> entryPoint, List<String> entries) {
        EnableAutoConfiguration annotation = entryPoint.getAnnotation(EnableAutoConfiguration.class);
        assertThat(annotation).as("%s must carry @EnableAutoConfiguration", entryPoint).isNotNull();
        Set<String> excluded = new HashSet<>(List.of(annotation.excludeName()));
        for (Class<?> type : annotation.exclude()) {
            excluded.add(type.getName());
        }
        return entries.stream().filter(entry -> !excluded.contains(entry)).toList();
    }

    private static List<String> starterAutoConfigurations() {
        List<String> entries = new ArrayList<>();
        try {
            for (URL url : Collections.list(StarterAutoConfigurationExcludedTest.class
                    .getClassLoader().getResources(IMPORTS_FILE))) {
                if (!url.toString().contains(STARTER_ARTIFACT)) {
                    continue;
                }
                try (var in = url.openStream()) {
                    new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                            .map(String::strip)
                            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                            .forEach(entries::add);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + IMPORTS_FILE, e);
        }
        return entries;
    }
}

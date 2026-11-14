package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Only the administrative process parses uploads (the student import): the portal and the worker exclude the multipart
 * auto-configuration, so their servlet has no multipart configuration and no resolver, and a multipart body is never
 * parsed there. The class name is checked against the class path, so a rename in Spring Boot breaks the build instead
 * of silently re-enabling the parsing.
 */
class MultipartAutoConfigurationExcludedTest {

    private static final String MULTIPART = "org.springframework.boot.servlet.autoconfigure.MultipartAutoConfiguration";

    private static List<String> excludedBy(String entryPoint) throws ClassNotFoundException {
        EnableAutoConfiguration annotation = Class.forName(entryPoint).getAnnotation(EnableAutoConfiguration.class);
        assertThat(annotation).isNotNull();
        return List.of(annotation.excludeName());
    }

    @Test
    void theAutoConfigurationExistsOnTheClassPath() throws ClassNotFoundException {
        assertThat(Class.forName(MULTIPART)).isNotNull();
    }

    @Test
    void thePortalAndTheWorkerExcludeIt() throws ClassNotFoundException {
        assertThat(excludedBy("com.confia.bootstrap.portal.PortalApplication")).contains(MULTIPART);
        assertThat(excludedBy("com.confia.bootstrap.worker.WorkerApplication")).contains(MULTIPART);
    }

    @Test
    void theAdministrativeProcessKeepsIt() throws ClassNotFoundException {
        assertThat(excludedBy("com.confia.bootstrap.admin.AdminApplication")).doesNotContain(MULTIPART);
    }
}

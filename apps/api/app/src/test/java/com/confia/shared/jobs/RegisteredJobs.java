package com.confia.shared.jobs;

import com.confia.bootstrap.worker.WorkerApplication;
import com.confia.shared.logging.SensitiveLogGuardInitializer;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The job definitions of the real worker context, for the tests that compare the registry with a
 * written list (scenarios BJ13 and BI98). It starts the real worker entry point with the executor off
 * and a database nothing listens on, which is enough because the pool is lazy, reads the {@link
 * JobDefinition} beans and closes the context.
 */
public final class RegisteredJobs {

    private RegisteredJobs() {
    }

    public static List<JobDefinition> ofTheWorker() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(WorkerApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(new SensitiveLogGuardInitializer())
                .run("--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/never",
                        "--confia.jobs.institutions=" + UUID.randomUUID(),
                        "--confia.jobs.executor.autostart=false")) {
            return List.copyOf(context.getBeansOfType(JobDefinition.class).values());
        }
    }
}

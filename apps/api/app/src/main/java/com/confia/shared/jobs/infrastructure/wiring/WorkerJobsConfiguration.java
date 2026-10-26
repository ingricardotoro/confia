package com.confia.shared.jobs.infrastructure.wiring;

import com.confia.shared.jobs.infrastructure.ConfiguredInstitutions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The jobs wiring of the worker process (background-jobs-with-db-scheduler design.md decisions 10
 * and 12). For now it holds the institutions the worker acts on; the single executor joins it in the
 * next step of task 1.6. A missing, empty, invalid or repeated list stops the process at startup.
 */
@Configuration(proxyBeanMethods = false)
public class WorkerJobsConfiguration {

    @Bean
    ConfiguredInstitutions configuredInstitutions(Environment environment) {
        return ConfiguredInstitutions.parse(environment.getProperty(ConfiguredInstitutions.PROPERTY));
    }
}

package com.confia.imports.web.wiring;

import com.confia.imports.application.ImportStudents;
import com.confia.imports.web.ImportController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of the web layer of {@code imports} for the administrative process (ADR-0024): it registers
 * the controller and nothing else. The use case it takes comes from {@code ImportsConfiguration}; the controller is
 * registered here and not there because a controller is a class of the {@code web} layer, and an {@code
 * infrastructure} package must not depend on it.
 *
 * <p>The portal and the worker never load it: the isolation tests of the process beans fail if a controller of this
 * module reaches either context.
 */
@Configuration(proxyBeanMethods = false)
public class ImportsWebConfiguration {

    @Bean
    ImportController importController(ImportStudents imports) {
        return new ImportController(imports);
    }
}

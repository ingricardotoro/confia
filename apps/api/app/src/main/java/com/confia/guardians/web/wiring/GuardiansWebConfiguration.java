package com.confia.guardians.web.wiring;

import com.confia.guardians.application.ManageGuardianLinks;
import com.confia.guardians.application.ManageGuardians;
import com.confia.guardians.web.GuardianController;
import com.confia.guardians.web.StudentGuardiansController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of the web layer of {@code guardians} for the administrative process (ADR-0024): it
 * registers the two controllers and nothing else. The use cases they take come from {@code GuardiansConfiguration};
 * the controllers are registered here and not there because a controller is a class of the {@code web} layer, and an
 * {@code infrastructure} package must not depend on it.
 *
 * <p>The portal and the worker never load it: the isolation tests of the process beans fail if a controller of this
 * module reaches either context.
 */
@Configuration(proxyBeanMethods = false)
public class GuardiansWebConfiguration {

    @Bean
    GuardianController guardianController(ManageGuardians guardians) {
        return new GuardianController(guardians);
    }

    @Bean
    StudentGuardiansController studentGuardiansController(ManageGuardianLinks links) {
        return new StudentGuardiansController(links);
    }
}

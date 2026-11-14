package com.confia.students.web.wiring;

import com.confia.students.application.ManageEnrollments;
import com.confia.students.web.EnrollmentController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of the web layer of the enrollments for the administrative process (ADR-0024; docs/cortes/enrollments.md): it
 * registers the controller of the enrollments and nothing else. The use case it takes comes from {@code
 * EnrollmentsConfiguration}; the controller is registered here and not there because a controller is a class of
 * the {@code web} layer, and an {@code infrastructure} package must not depend on it.
 *
 * <p>The portal and the worker never load it: the isolation tests of the process beans fail if a controller of
 * this module reaches either context.
 */
@Configuration(proxyBeanMethods = false)
public class EnrollmentsWebConfiguration {

    @Bean
    EnrollmentController enrollmentController(ManageEnrollments enrollments) {
        return new EnrollmentController(enrollments);
    }
}

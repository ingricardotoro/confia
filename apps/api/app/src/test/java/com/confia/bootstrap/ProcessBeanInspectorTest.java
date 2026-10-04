package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.admin.AdminApplication;
import com.confia.bootstrap.portal.PortalApplication;
import com.confia.bootstrap.worker.WorkerApplication;
import com.confia.identity.application.AuthenticateWithPassword;
import com.confia.shared.web.openapi.ContractSchemas;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

/**
 * Permanent negative test of {@link ProcessBeanInspector} (specs/build-integrity, requirement
 * "Prueba negativa permanente del inspector de aislamiento"). Each case builds a tiny context by
 * hand, with no Spring Boot and no annotation processing, and shows the inspector reports a
 * deliberate leak and stays silent on a clean context. Without it, an inspector that inspects
 * nothing would be indistinguishable from one that finds no leaks.
 */
class ProcessBeanInspectorTest {

    /** A com.confia.bootstrap class: a CONFIA package that no process allow-list contains. */
    static class UnlistedBean {
    }

    @Test
    void reportsABeanOutsideTheAllowListNamingTheBeanAndItsPackage() {
        try (GenericApplicationContext context = contextWith(UnlistedBean.class)) {
            List<String> violations = ProcessBeanInspector.violations(context,
                    ProcessBeanPolicy.PORTAL);

            assertThat(violations).singleElement().satisfies(line -> assertThat(line)
                    .contains("process 'portal'")
                    .contains("ProcessBeanInspectorTest$UnlistedBean")
                    .contains("com.confia.bootstrap")
                    .contains("not in the allow-list"));
        }
    }

    @Test
    void reportsANominallyForbiddenBeanNamingThePackageAndTheReason() {
        try (GenericApplicationContext context = contextWith(AdminApplication.class)) {
            List<String> violations = ProcessBeanInspector.violations(context,
                    ProcessBeanPolicy.PORTAL);

            assertThat(violations).anySatisfy(line -> assertThat(line)
                    .contains("com.confia.bootstrap.admin")
                    .contains("forbidden: another process entry point"));
        }
    }

    /**
     * The portal also rejects an identity bean through its allow-list, so only the "forbidden"
     * line proves the named entry itself matches: a typo in it would leave just the allow-list
     * line. The bean is lazy, so the use case is never instantiated and needs no collaborators.
     */
    @Test
    void reportsAnAdministrativeModuleInThePortalThroughItsNamedEntryNotOnlyTheAllowList() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(AuthenticateWithPassword.class,
                    definition -> definition.setLazyInit(true));
            context.refresh();

            List<String> violations = ProcessBeanInspector.violations(context,
                    ProcessBeanPolicy.PORTAL);

            assertThat(violations).anySatisfy(line -> assertThat(line)
                    .contains("process 'portal'")
                    .contains("com.confia.identity.application")
                    .contains("forbidden: staff-only module"));
        }
    }

    @Test
    void reportsTheOpenApiSurfaceInTheWorkerAsForbidden() {
        try (GenericApplicationContext context = contextWith(WorkerApplication.class,
                ContractSchemas.class)) {
            List<String> violations = ProcessBeanInspector.violations(context,
                    ProcessBeanPolicy.WORKER);

            assertThat(violations).anySatisfy(line -> assertThat(line)
                    .contains("process 'worker'")
                    .contains("com.confia.shared.web.openapi")
                    .contains("forbidden: the worker never serves the OpenAPI surface"));
        }
    }

    @Test
    void reportsNothingForACleanContextSoDetectionIsNotRejectionOfEverything() {
        try (GenericApplicationContext context = contextWith(PortalApplication.class,
                ContractSchemas.class, StringBuilder.class)) {
            assertThat(ProcessBeanInspector.confiaBeans(context))
                    .as("the inspector sees the CONFIA beans it must then accept")
                    .extracting(ProcessBeanInspector.InspectedBean::originPackages)
                    .contains(Set.of("com.confia.bootstrap.portal"),
                            Set.of("com.confia.shared.web.openapi"));

            assertThat(ProcessBeanInspector.violations(context, ProcessBeanPolicy.PORTAL))
                    .isEmpty();
        }
    }

    private static GenericApplicationContext contextWith(Class<?>... beanTypes) {
        GenericApplicationContext context = new GenericApplicationContext();
        for (Class<?> beanType : beanTypes) {
            context.registerBean(beanType);
        }
        context.refresh();
        return context;
    }
}

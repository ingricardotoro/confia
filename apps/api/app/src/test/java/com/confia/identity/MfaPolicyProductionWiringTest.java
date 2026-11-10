package com.confia.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.AdminProcessLauncher;
import com.confia.identity.application.AuthenticateWithPassword;
import com.confia.identity.application.ResetPasswordWithToken;
import com.confia.identity.domain.MfaPolicy;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.wiring.IdentityConfiguration;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The second-factor policy of the real administrative process is {@link MfaPolicy#STAFF} and nothing
 * else (rbac-permission-matrix-and-audit-integration design.md D14 and D18; scenarios I302 and BI129,
 * the part about the policy): no test class is in the production artifact, so the only definition is
 * the constant, registered by one factory method of {@code IdentityConfiguration}. The worker and the
 * portal never register it: their allow-lists name no {@code identity} package, which {@code
 * ProcessBeanIsolationTest} enforces on their real contexts.
 */
class MfaPolicyProductionWiringTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.confia");

    @Test
    void theRealAdministrativeProcessHoldsExactlyTheStaffPolicy() {
        try (ConfigurableApplicationContext context = AdminProcessLauncher.launchWithoutDatabase()) {
            assertThat(context.getBeansOfType(MfaPolicy.class)).hasSize(1)
                    .allSatisfy((name, policy) -> assertThat(policy).isSameAs(MfaPolicy.STAFF));
            assertThat(context.getBean(MfaPolicy.class)).isSameAs(MfaPolicy.STAFF);
        }
    }

    @Test
    void theRealProcessAsksEveryAccountForASecondFactor() {
        try (ConfigurableApplicationContext context = AdminProcessLauncher.launchWithoutDatabase()) {
            MfaPolicy policy = context.getBean(MfaPolicy.class);

            assertThat(Stream.generate(() -> new StaffAccountId(UUID.randomUUID())).limit(20))
                    .allMatch(policy::requiresSecondFactor);
        }
    }

    @Test
    void theStaffConstantIsTheOnlyDefinitionAndOneFactoryMethodRegistersIt() {
        JavaClass policy = PRODUCTION.get(MfaPolicy.class);
        assertThat(PRODUCTION.get(IdentityConfiguration.class).getMethods())
                .as("non-vacuous: the scan sees the configuration").isNotEmpty();

        assertThat(PRODUCTION.stream()
                .filter(type -> type.isAssignableTo(MfaPolicy.class) && !type.equals(policy))
                .map(JavaClass::getName).toList())
                .as("no production class implements the policy: STAFF is a constant of it")
                .isEmpty();
        assertThat(policy.getFields().stream().filter(field -> field.getRawType().equals(policy))
                .map(field -> field.getName()).toList()).containsExactly("STAFF");
        List<String> factories = PRODUCTION.stream().flatMap(type -> type.getMethods().stream())
                .filter(method -> method.getRawReturnType().isAssignableTo(MfaPolicy.class))
                .map(JavaMethod::getFullName).toList();
        assertThat(factories).as("one factory method returns the policy")
                .containsExactly(IdentityConfiguration.class.getName() + ".mfaPolicy()");
    }

    @Test
    void onlyTheConfigurationReadsTheConstantAndOnlyTheTwoUseCasesTakeThePolicy() {
        JavaClass policy = PRODUCTION.get(MfaPolicy.class);

        assertThat(policy.getFieldAccessesToSelf().stream()
                .filter(access -> access.getAccessType() == JavaFieldAccess.AccessType.GET)
                .map(access -> access.getOriginOwner().getName()).distinct().toList())
                .as("who reads MfaPolicy.STAFF")
                .containsExactly(IdentityConfiguration.class.getName());
        assertThat(PRODUCTION.get(AuthenticateWithPassword.class).getDirectDependenciesFromSelf()
                .stream().map(dependency -> dependency.getTargetClass()))
                .as("non-vacuous: the login takes the policy").contains(policy);
        assertThat(PRODUCTION.get(ResetPasswordWithToken.class).getDirectDependenciesFromSelf()
                .stream().map(dependency -> dependency.getTargetClass()))
                .as("non-vacuous: the reset takes the policy").contains(policy);
        assertThat(PRODUCTION.stream()
                .filter(type -> type.getPackageName().startsWith("com.confia.bootstrap.worker")
                        || type.getPackageName().startsWith("com.confia.bootstrap.portal"))
                .flatMap(type -> type.getDirectDependenciesFromSelf().stream())
                .map(dependency -> dependency.getTargetClass()).toList())
                .as("the entry points of the worker and the portal name no policy").doesNotContain(policy);
    }
}

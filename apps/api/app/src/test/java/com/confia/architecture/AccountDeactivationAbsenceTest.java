package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.SessionRevocationReason;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Specs/identity, scenario [I215]: there is no use case that deactivates an account and {@code
 * admin_revoke} is not a revocation reason. Both are gaps with an owner (change 8) and stop being true
 * the day that change delivers them. That the constraint of {@code revoked_reason} in the database
 * refuses the text is proven where the migration is tested; here the application refuses it as well:
 * the enumeration the use cases write from has no such code.
 *
 * <p>The detector looks at the names of the classes of {@code identity.application}, the use cases,
 * and is proven first on a base set that holds the real ones. Its pattern is proven on the names the
 * missing use case would have.
 */
class AccountDeactivationAbsenceTest {

    private static final Pattern DEACTIVATION = Pattern.compile("(?i)"
            + "((Deactivat|Disable|Suspend|Block|Lock)\\w*(Account|Staff)\\w*"
            + "|(Account|Staff)\\w*(Deactivat|Disabl|Suspen|Block|Lock)\\w*)");

    @Test
    void noUseCaseOfIdentityDeactivatesAnAccount() {
        JavaClasses production = productionClasses();

        assertThat(useCaseNames(production)).as("the scan sees the real use cases, so the absence is "
                + "not vacuous").contains("LogIn", "EndAllSessions", "RotateRefreshToken");
        assertThat(deactivationUseCases(production)).isEmpty();
    }

    @Test
    void theDetectorFindsTheNamesOfAUseCaseThatDeactivatesAnAccount() {
        for (String name : List.of("DeactivateStaffAccount", "SuspendAccount", "BlockStaffAccount",
                "AccountDeactivation", "DisableStaffAccount")) {
            assertThat(DEACTIVATION.matcher(name).matches()).as(name).isTrue();
        }
        for (String name : List.of("EndAllSessions", "RotateRefreshToken", "StaffAccountRepository",
                "AuthenticateWithPassword")) {
            assertThat(DEACTIVATION.matcher(name).matches()).as(name).isFalse();
        }
    }

    @Test
    void adminRevokeIsNotARevocationReason() {
        assertThat(Arrays.stream(SessionRevocationReason.values()).map(SessionRevocationReason::code))
                .containsExactlyInAnyOrder("password_change", "reuse_detected", "logout",
                        "mfa_change", "binding_changed")
                .doesNotContain("admin_revoke");
    }

    private static List<String> useCaseNames(JavaClasses classes) {
        return classes.stream()
                .filter(type -> type.getPackageName().equals("com.confia.identity.application"))
                .map(JavaClass::getSimpleName).toList();
    }

    private static List<String> deactivationUseCases(JavaClasses classes) {
        return useCaseNames(classes).stream().filter(name -> DEACTIVATION.matcher(name).matches())
                .toList();
    }
}

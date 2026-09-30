package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Reflection guard over {@link AuthenticationResult}'s four outcomes (specs/identity/spec.md,
 * requirement "Resultado tipado de la autenticación con cuatro desenlaces": "ese desenlace no
 * lleva ningún campo de alcance de autorización", escenario "Los cuatro desenlaces son exactamente
 * los que el tipo cerrado permite, ninguno más"; column-encryption-and-mfa-totp design.md, decision
 * 6). A field named after an authorization scope — a permission, a role, a claim — sneaking into
 * any record is exactly the defect this class exists to catch before any behavioral test would
 * ever notice it, because a behavioral test only exercises the fields it already knows to look
 * for.
 */
class AuthenticationResultTest {

    private static final Set<String> FORBIDDEN_FIELD_NAME_FRAGMENTS =
            Set.of("scope", "permission", "role", "claim", "grant", "authorit");

    private static final StaffAccountId USER_ID = new StaffAccountId(UUID.randomUUID());
    private static final InstitutionId INSTITUTION_ID = new InstitutionId(UUID.randomUUID());

    @Test
    void permitsExactlyTheFourOutcomesAndNoMore() {
        assertThat(AuthenticationResult.class.getPermittedSubclasses())
                .extracting(Class::getSimpleName)
                .as("column-encryption-and-mfa-totp design.md, decision 6: AuthenticationResult's "
                        + "permits clause must name exactly these four outcomes for the exhaustive "
                        + "switch to force every consumer to handle each one")
                .containsExactlyInAnyOrder("Authenticated", "Rejected", "SecondFactorRequired",
                        "SecondFactorEnrollmentRequired");
    }

    @Test
    void authenticatedDeclaresNoAuthorizationScopeField() {
        assertNoForbiddenFieldNames(AuthenticationResult.Authenticated.class);
    }

    @Test
    void rejectedDeclaresNoAuthorizationScopeField() {
        assertNoForbiddenFieldNames(AuthenticationResult.Rejected.class);
    }

    @Test
    void secondFactorRequiredDeclaresNoAuthorizationScopeField() {
        assertNoForbiddenFieldNames(AuthenticationResult.SecondFactorRequired.class);
    }

    @Test
    void secondFactorEnrollmentRequiredDeclaresNoAuthorizationScopeField() {
        assertNoForbiddenFieldNames(AuthenticationResult.SecondFactorEnrollmentRequired.class);
    }

    @Test
    void authenticatedDeclaresExactlyItsTwoIdentifiers() {
        assertThat(declaredFieldNames(AuthenticationResult.Authenticated.class))
                .containsExactlyInAnyOrder("userId", "institutionId");
    }

    @Test
    void rejectedDeclaresExactlyItsInternalReason() {
        assertThat(declaredFieldNames(AuthenticationResult.Rejected.class))
                .containsExactlyInAnyOrder("reason");
    }

    @Test
    void secondFactorRequiredDeclaresExactlyItsTwoIdentifiers() {
        assertThat(declaredFieldNames(AuthenticationResult.SecondFactorRequired.class))
                .containsExactlyInAnyOrder("userId", "institutionId");
    }

    @Test
    void secondFactorEnrollmentRequiredDeclaresExactlyItsTwoIdentifiers() {
        assertThat(declaredFieldNames(AuthenticationResult.SecondFactorEnrollmentRequired.class))
                .containsExactlyInAnyOrder("userId", "institutionId");
    }

    @Test
    void secondFactorRequiredRejectsANullUserId() {
        assertThatThrownBy(
                () -> new AuthenticationResult.SecondFactorRequired(null, INSTITUTION_ID))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void secondFactorRequiredRejectsANullInstitutionId() {
        assertThatThrownBy(() -> new AuthenticationResult.SecondFactorRequired(USER_ID, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void secondFactorEnrollmentRequiredRejectsANullUserId() {
        assertThatThrownBy(
                () -> new AuthenticationResult.SecondFactorEnrollmentRequired(null, INSTITUTION_ID))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void secondFactorEnrollmentRequiredRejectsANullInstitutionId() {
        assertThatThrownBy(
                () -> new AuthenticationResult.SecondFactorEnrollmentRequired(USER_ID, null))
                .isInstanceOf(NullPointerException.class);
    }

    private static void assertNoForbiddenFieldNames(Class<?> type) {
        List<String> lowerCaseFieldNames = declaredFieldNames(type).stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
        for (String fragment : FORBIDDEN_FIELD_NAME_FRAGMENTS) {
            assertThat(lowerCaseFieldNames)
                    .as("%s must declare no authorization-scope field (checked fragment: %s)",
                            type.getSimpleName(), fragment)
                    .noneMatch(name -> name.contains(fragment));
        }
    }

    private static List<String> declaredFieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(Field::getName)
                .toList();
    }
}

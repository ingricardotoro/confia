package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Reflection guard over {@link AuthenticationResult}'s two outcomes (specs/identity/spec.md,
 * requirement "Resultado tipado de la autenticación con dos desenlaces": "ese desenlace no lleva
 * ningún campo de alcance de autorización"; design.md, decision 10). A field named after an
 * authorization scope — a permission, a role, a claim — sneaking into either record is exactly the
 * defect this class exists to catch before any behavioral test would ever notice it, because a
 * behavioral test only exercises the fields it already knows to look for.
 */
class AuthenticationResultTest {

    private static final Set<String> FORBIDDEN_FIELD_NAME_FRAGMENTS =
            Set.of("scope", "permission", "role", "claim", "grant", "authorit");

    @Test
    void authenticatedDeclaresNoAuthorizationScopeField() {
        assertNoForbiddenFieldNames(AuthenticationResult.Authenticated.class);
    }

    @Test
    void rejectedDeclaresNoAuthorizationScopeField() {
        assertNoForbiddenFieldNames(AuthenticationResult.Rejected.class);
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

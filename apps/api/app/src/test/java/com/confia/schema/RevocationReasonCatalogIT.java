package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.support.TransactionalPostgresIntegrationTest;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Scenario "El catálogo de motivos no declara motivos sin productor"
 * (session-tokens-and-web-layer specs/identity/spec.md; owner decision 7). The revocation reasons
 * the {@code V8} check constraint admits are exactly the two that have a producer in this change:
 * {@code reuse_detected} (rotation) and {@code password_change} (password reset). {@code logout}
 * and {@code admin_revoke} arrive with their own migrations, which widen the constraint then.
 *
 * <p>{@code RefreshTokenSchemaIT} (task 3.1c) proves the behaviour with real inserts; this class reads the
 * constraint definition from the catalogue, so a reason added to the list without a producer
 * breaks the build even if no insert for it was ever written. It is an {@code *IT} because it needs
 * the migrated database; the task text calls it a catalog test.
 */
class RevocationReasonCatalogIT extends TransactionalPostgresIntegrationTest {

    private static final Pattern QUOTED_LITERAL = Pattern.compile("'([a-z_]+)'");

    @Test
    void theRevocationReasonConstraintAdmitsExactlyTheReasonsThatHaveAProducer() {
        String definition = dsl.fetchOne("""
                select pg_get_constraintdef(oid) as definition
                  from pg_constraint
                 where conname = 'identity_refresh_token_family_reason_chk'
                   and conrelid = 'identity_refresh_token_family'::regclass
                """).get("definition", String.class);

        Matcher matcher = QUOTED_LITERAL.matcher(definition);
        Set<String> reasons = matcher.results().map(result -> result.group(1))
                .collect(Collectors.toUnmodifiableSet());

        assertThat(definition).as("the definition read from the catalogue").contains("revoked_reason");
        assertThat(reasons)
                .as("only reasons with a producer in this change: no logout, no admin_revoke")
                .containsExactlyInAnyOrder("reuse_detected", "password_change");
    }
}

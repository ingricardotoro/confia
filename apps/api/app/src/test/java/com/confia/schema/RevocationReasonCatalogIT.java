package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.application.SessionRevocationReason;
import com.confia.support.TransactionalPostgresIntegrationTest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Scenarios BI39 and BI40 (session-endpoints-cookie-and-csrf specs/build-integrity/spec.md; design.md
 * decision 16). After {@code V9} the revocation reasons the check constraint admits are exactly the
 * five that have a producer: {@code reuse_detected} (rotation), {@code password_change} (password
 * reset), {@code logout}, {@code mfa_change} and {@code binding_changed}. {@code admin_revoke} has no
 * producer and stays out.
 *
 * <p>{@code RefreshTokenSchemaIT} proves the behaviour with real inserts; this class reads the
 * constraint definition from the catalogue, so a reason added to the list without a producer breaks
 * the build even if no insert for it was ever written. It is an {@code *IT} because it needs the
 * migrated database; the task text calls it a catalog test.
 */
class RevocationReasonCatalogIT extends TransactionalPostgresIntegrationTest {

    private static final Pattern QUOTED_LITERAL = Pattern.compile("'([a-z_]+)'");

    /**
     * The contract between the application and the schema: the words of {@link
     * SessionRevocationReason} are exactly the words the check constraint admits. A reason added to
     * the enum without a migration, a migration that widens the constraint without a reason in the
     * enum, or a misspelling on either side, breaks the build here and not at the first revocation.
     */
    @Test
    void everyRevocationReasonOfTheApplicationIsAdmittedByTheConstraintAndNothingElseIs() {
        assertCatalogueMatches(applicationReasons(), admittedReasons());
    }

    /** Scenario BI40: the same contract, with a value added to the enumeration and no migration. */
    @Test
    void aReasonAddedToTheEnumerationWithoutAMigrationBreaksTheContractAndNamesTheValue() {
        Set<String> variant = new HashSet<>(applicationReasons());
        variant.add("account_locked");

        assertThatThrownBy(() -> assertCatalogueMatches(variant, admittedReasons()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("account_locked")
                .hasMessageContaining("reasons the application declares that the constraint "
                        + "does not admit");
    }

    /** The mirror of the variant: a value the constraint admits and the enumeration lacks. */
    @Test
    void aReasonTheConstraintAdmitsAndTheEnumerationLacksBreaksTheContractAndNamesTheValue() {
        Set<String> variant = new HashSet<>(applicationReasons());
        variant.remove("binding_changed");

        assertThatThrownBy(() -> assertCatalogueMatches(variant, admittedReasons()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("binding_changed")
                .hasMessageContaining("reasons the constraint admits that the application "
                        + "does not declare");
    }

    @Test
    void theRevocationReasonConstraintAdmitsExactlyTheFiveReasonsThatHaveAProducer() {
        String definition = constraintDefinition();
        Set<String> reasons = admittedReasons();

        assertThat(definition).as("the definition read from the catalogue").contains("revoked_reason");
        assertThat(reasons)
                .as("only reasons with a producer: no admin_revoke")
                .containsExactlyInAnyOrder("reuse_detected", "password_change", "logout",
                        "mfa_change", "binding_changed");
    }

    private static void assertCatalogueMatches(Set<String> declared, Set<String> admitted) {
        Set<String> notAdmitted = new TreeSet<>(declared);
        notAdmitted.removeAll(admitted);
        Set<String> notDeclared = new TreeSet<>(admitted);
        notDeclared.removeAll(declared);

        assertThat(notAdmitted)
                .as("reasons the application declares that the constraint does not admit")
                .isEmpty();
        assertThat(notDeclared)
                .as("reasons the constraint admits that the application does not declare")
                .isEmpty();
    }

    private static Set<String> applicationReasons() {
        return Arrays.stream(SessionRevocationReason.values())
                .map(SessionRevocationReason::code).collect(Collectors.toUnmodifiableSet());
    }

    private String constraintDefinition() {
        return dsl.fetchOne("""
                select pg_get_constraintdef(oid) as definition
                  from pg_constraint
                 where conname = 'identity_refresh_token_family_reason_chk'
                   and conrelid = 'identity_refresh_token_family'::regclass
                """).get("definition", String.class);
    }

    private Set<String> admittedReasons() {
        Matcher matcher = QUOTED_LITERAL.matcher(constraintDefinition());
        return matcher.results().map(result -> result.group(1))
                .collect(Collectors.toUnmodifiableSet());
    }
}

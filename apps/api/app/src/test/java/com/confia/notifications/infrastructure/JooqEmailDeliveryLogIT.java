package com.confia.notifications.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import com.confia.notifications.application.EmailDeliveryAttempt;
import com.confia.notifications.application.RecipientHasher;
import com.confia.notifications.application.api.TransactionalEmailTemplate;
import com.confia.notifications.domain.FailureClass;
import com.confia.notifications.domain.RecipientHash;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

/**
 * {@link JooqEmailDeliveryLog} against a real PostgreSQL (transactional-email-adapter design.md
 * decision 7; specs/notifications/spec.md, scenarios NT11 to NT13). The log is insert-only and holds
 * no personal data: the tests read every column of what was written and look for the address, the
 * link and the token the attempt was made with. The hash key is generated at run time and is not a
 * secret of any environment.
 */
class JooqEmailDeliveryLogIT extends CommittingPostgresIntegrationTest {

    private static final String ADDRESS_AS_TYPED = "Ana.Martinez@Colegio.edu.hn ";
    private static final Instant AT = Instant.parse("2026-11-10T08:00:00Z");

    private final InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
    private final RecipientHasher hasher = new RecipientHasher(randomKey());

    @Test
    void aSentAttemptIsStoredWithItsHashTemplateAndProviderIdentifier() {
        UUID id = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        RecipientHash hash = hasher.hash(ADDRESS_AS_TYPED);

        record(EmailDeliveryAttempt.sent(institutionId, id, requestId, AT,
                TransactionalEmailTemplate.PASSWORD_RESET_LINK, hash, "ses-0001"));

        Record row = onlyRow();
        assertThat(row.get("id", UUID.class)).isEqualTo(id);
        assertThat(row.get("request_id", UUID.class)).isEqualTo(requestId);
        assertThat(row.get("attempted_at", OffsetDateTime.class).toInstant()).isEqualTo(AT);
        assertThat(row.get("channel", String.class)).isEqualTo("email");
        assertThat(row.get("purpose", String.class)).isEqualTo("transactional");
        assertThat(row.get("template_key", String.class)).isEqualTo("password-reset-link");
        assertThat(row.get("template_version", Integer.class)).isEqualTo(1);
        assertThat(row.get("recipient_hash", String.class)).isEqualTo(hash.value());
        assertThat(row.get("status", String.class)).isEqualTo("sent");
        assertThat(row.get("provider_message_id", String.class)).isEqualTo("ses-0001");
        assertThat(row.get("failure_class", String.class)).isNull();
    }

    @Test
    void aFailedAttemptIsStoredWithItsClassAndNoProviderIdentifier() {
        record(EmailDeliveryAttempt.failed(institutionId, UUID.randomUUID(), UUID.randomUUID(), AT,
                TransactionalEmailTemplate.PASSWORD_RESET_LINK, hasher.hash(ADDRESS_AS_TYPED),
                FailureClass.UNAVAILABLE));

        Record row = onlyRow();
        assertThat(row.get("status", String.class)).isEqualTo("failed");
        assertThat(row.get("failure_class", String.class)).isEqualTo("unavailable");
        assertThat(row.get("provider_message_id", String.class)).isNull();
    }

    @Test
    void everyFailureClassOfTheEnumIsAcceptedByTheClosedListOfTheColumn() {
        for (FailureClass failureClass : FailureClass.values()) {
            record(EmailDeliveryAttempt.failed(institutionId, UUID.randomUUID(), UUID.randomUUID(),
                    AT, TransactionalEmailTemplate.PASSWORD_RESET_LINK,
                    hasher.hash(ADDRESS_AS_TYPED), failureClass));
        }

        assertThat(rows()).extracting(row -> row.get("failure_class", String.class))
                .containsExactlyInAnyOrder(Arrays.stream(FailureClass.values())
                        .map(FailureClass::code).toArray(String[]::new));
    }

    @Test
    void everyTemplateOfTheCatalogueSatisfiesTheKeyAndVersionConstraints() {
        for (TransactionalEmailTemplate template : TransactionalEmailTemplate.values()) {
            record(EmailDeliveryAttempt.sent(institutionId, UUID.randomUUID(), UUID.randomUUID(),
                    AT, template, hasher.hash(ADDRESS_AS_TYPED), null));
        }

        assertThat(rows()).hasSize(TransactionalEmailTemplate.values().length);
    }

    @Test
    void aRetryOfTheSameRequestIsAnotherRowNotAnUpdate() {
        UUID requestId = UUID.randomUUID();
        record(EmailDeliveryAttempt.failed(institutionId, UUID.randomUUID(), requestId, AT,
                TransactionalEmailTemplate.PASSWORD_RESET_LINK, hasher.hash(ADDRESS_AS_TYPED),
                FailureClass.TIMEOUT));
        record(EmailDeliveryAttempt.sent(institutionId, UUID.randomUUID(), requestId,
                AT.plusSeconds(60), TransactionalEmailTemplate.PASSWORD_RESET_LINK,
                hasher.hash(ADDRESS_AS_TYPED), null));

        assertThat(rows()).extracting(row -> row.get("status", String.class))
                .containsExactlyInAnyOrder("failed", "sent");
    }

    /** NT13: no column holds the address, the link, the subject or the body, and the hash is the normalised one. */
    @Test
    void noColumnHoldsPersonalDataOrTheContentOfTheMessage() {
        RecipientHash hash = hasher.hash(ADDRESS_AS_TYPED);
        record(EmailDeliveryAttempt.sent(institutionId, UUID.randomUUID(), UUID.randomUUID(), AT,
                TransactionalEmailTemplate.PASSWORD_RESET_LINK, hash, "ses-0002"));
        record(EmailDeliveryAttempt.failed(institutionId, UUID.randomUUID(), UUID.randomUUID(), AT,
                TransactionalEmailTemplate.PASSWORD_RESET_LINK, hash, FailureClass.REJECTED));

        List<Record> stored = rows();
        assertThat(stored).hasSize(2);
        List<String> forbidden = List.of("ana.martinez", "colegio.edu.hn", "@", "sentinel-token",
                "https://", "restablecer");
        for (Record row : stored) {
            for (int i = 0; i < row.size(); i++) {
                String text = String.valueOf(row.get(i)).toLowerCase(Locale.ROOT);
                for (String needle : forbidden) {
                    assertThat(text).as("column %s", row.field(i).getName())
                            .doesNotContain(needle);
                }
            }
            assertThat(row.get("recipient_hash", String.class))
                    .isEqualTo(hasher.hash("ana.martinez@colegio.edu.hn").value());
        }
        assertThat(columnNames()).as("the table has no column for an address, a subject, a body "
                + "or parameters").containsExactlyInAnyOrder("institution_id", "id", "request_id",
                "attempted_at", "channel", "purpose", "template_key", "template_version",
                "recipient_hash", "status", "provider_message_id", "failure_class");
    }

    private void record(EmailDeliveryAttempt attempt) {
        transactionRunner().execute(contextOf(institutionId), () -> {
            new JooqEmailDeliveryLog(dsl).record(attempt);
            return null;
        });
    }

    private List<Record> rows() {
        return transactionRunner().execute(contextOf(institutionId), () -> new ArrayList<>(
                dsl.fetch("select * from notifications_email_delivery where institution_id = ?",
                        institutionId.value())));
    }

    private Record onlyRow() {
        List<Record> stored = rows();
        assertThat(stored).hasSize(1);
        return stored.get(0);
    }

    private List<String> columnNames() {
        return dsl.fetch("""
                select attname from pg_attribute
                 where attrelid = 'notifications_email_delivery'::regclass
                   and attnum > 0 and not attisdropped
                """).getValues("attname", String.class);
    }

    private static SecurityContext contextOf(InstitutionId institution) {
        return new SecurityContext("", "system", institution.value().toString(),
                UUID.randomUUID().toString());
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}

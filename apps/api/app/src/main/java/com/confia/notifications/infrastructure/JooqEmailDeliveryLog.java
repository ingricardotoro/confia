package com.confia.notifications.infrastructure;

import static confia.generated.jooq.tables.NotificationsEmailDelivery.NOTIFICATIONS_EMAIL_DELIVERY;

import com.confia.notifications.application.EmailDeliveryAttempt;
import com.confia.notifications.application.EmailDeliveryLog;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import org.jooq.DSLContext;

/**
 * The jOOQ adapter of {@link EmailDeliveryLog} (transactional-email-adapter design.md decision 7;
 * ADR-0015 rule 4: jOOQ confined to {@code infrastructure}). {@code final}, with an explicit constructor
 * over {@link DSLContext} and no Spring annotation: the wiring of the worker (task 7) creates it.
 *
 * <p>It only inserts, because the administrative role the worker connects with holds {@code SELECT} and
 * {@code INSERT} alone on the table, and it never opens a transaction of its own (ADR-0015 rule 7): it
 * runs inside the one the caller opened, whose row policy scopes the insert to one institution beneath
 * the explicit {@code institution_id}. The channel and the purpose are constants of this adapter, the
 * only values the table's constraints accept.
 */
public final class JooqEmailDeliveryLog implements EmailDeliveryLog {

    private static final String CHANNEL = "email";
    private static final String PURPOSE = "transactional";

    private final DSLContext dsl;

    public JooqEmailDeliveryLog(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void record(EmailDeliveryAttempt attempt) {
        dsl.insertInto(NOTIFICATIONS_EMAIL_DELIVERY)
                .set(NOTIFICATIONS_EMAIL_DELIVERY.INSTITUTION_ID, attempt.institutionId().value())
                .set(NOTIFICATIONS_EMAIL_DELIVERY.ID, attempt.id())
                .set(NOTIFICATIONS_EMAIL_DELIVERY.REQUEST_ID, attempt.requestId())
                .set(NOTIFICATIONS_EMAIL_DELIVERY.ATTEMPTED_AT,
                        OffsetDateTime.ofInstant(attempt.attemptedAt(), ZoneOffset.UTC))
                .set(NOTIFICATIONS_EMAIL_DELIVERY.CHANNEL, CHANNEL)
                .set(NOTIFICATIONS_EMAIL_DELIVERY.PURPOSE, PURPOSE)
                .set(NOTIFICATIONS_EMAIL_DELIVERY.TEMPLATE_KEY, attempt.template().key())
                .set(NOTIFICATIONS_EMAIL_DELIVERY.TEMPLATE_VERSION, attempt.template().version())
                .set(NOTIFICATIONS_EMAIL_DELIVERY.RECIPIENT_HASH, attempt.recipient().value())
                .set(NOTIFICATIONS_EMAIL_DELIVERY.STATUS,
                        attempt.status().name().toLowerCase(Locale.ROOT))
                .set(NOTIFICATIONS_EMAIL_DELIVERY.PROVIDER_MESSAGE_ID, attempt.providerMessageId())
                .set(NOTIFICATIONS_EMAIL_DELIVERY.FAILURE_CLASS,
                        attempt.failureClass() == null ? null : attempt.failureClass().code())
                .execute();
    }
}

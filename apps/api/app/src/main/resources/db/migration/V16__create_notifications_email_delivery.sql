-- Sixteenth migration (F0 change 14a, transactional-email-adapter; design.md decision 7;
-- specs/notifications/spec.md NT11 to NT13; specs/build-integrity/spec.md BI154 to BI158).
-- Creates the delivery log of the transactional email channel: one row per send attempt, with the
-- outcome and the keyed hash of the recipient. V15 belongs to another change and is not in this tree.
--
-- The table holds no personal data. There is no column for an address, a subject, a body or the
-- parameters of a template, and the recipient is stored only as the HMAC-SHA-256 of the normalised
-- address under a key of its own (RecipientHasher). It is insert-only: a result is a new row, never an
-- update, so no role below holds UPDATE or DELETE.

CREATE TABLE notifications_email_delivery (
    institution_id      UUID        NOT NULL,
    id                  UUID        NOT NULL,
    request_id          UUID        NOT NULL,
    attempted_at        TIMESTAMPTZ NOT NULL,
    channel             TEXT        NOT NULL,
    purpose             TEXT        NOT NULL,
    template_key        TEXT        NOT NULL,
    template_version    INTEGER     NOT NULL,
    recipient_hash      TEXT        NOT NULL,
    status              TEXT        NOT NULL,
    provider_message_id TEXT,
    failure_class       TEXT,
    CONSTRAINT notifications_email_delivery_pk              PRIMARY KEY (institution_id, id),
    CONSTRAINT notifications_email_delivery_channel_chk     CHECK (channel = 'email'),
    CONSTRAINT notifications_email_delivery_purpose_chk     CHECK (purpose = 'transactional'),
    CONSTRAINT notifications_email_delivery_template_key_chk
        CHECK (template_key ~ '^[a-z][a-z0-9-]{0,62}$'),
    CONSTRAINT notifications_email_delivery_template_version_chk CHECK (template_version > 0),
    -- The shape RecipientHash enforces in the domain: 64 lowercase hexadecimal characters.
    CONSTRAINT notifications_email_delivery_recipient_hash_chk
        CHECK (recipient_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT notifications_email_delivery_status_chk      CHECK (status IN ('sent', 'failed')),
    -- Closed list of com.confia.notifications.domain.FailureClass; JooqEmailDeliveryLogIT compares it
    -- with the enum.
    CONSTRAINT notifications_email_delivery_failure_class_chk
        CHECK (failure_class IN ('unavailable', 'timeout', 'tls', 'authentication', 'rejected',
            'unknown')),
    -- A sent row has no failure class; a failed row has one and no provider identifier.
    CONSTRAINT notifications_email_delivery_outcome_chk CHECK (
        (status = 'sent'   AND failure_class IS NULL) OR
        (status = 'failed' AND failure_class IS NOT NULL AND provider_message_id IS NULL))
);

-- Serves the lookup of the attempts made to one recipient, by hash, in time order.
CREATE INDEX notifications_email_delivery_recipient_idx
    ON notifications_email_delivery (institution_id, recipient_hash, attempted_at);

ALTER TABLE notifications_email_delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications_email_delivery FORCE  ROW LEVEL SECURITY;

CREATE POLICY notifications_email_delivery_institution_isolation ON notifications_email_delivery
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON notifications_email_delivery FROM PUBLIC;

-- The worker connects as confia_admin_app (no role of its own exists yet; design.md risk R6).
-- SELECT and INSERT only: the log is never updated or deleted.
GRANT SELECT, INSERT ON notifications_email_delivery TO confia_admin_app;
GRANT SELECT         ON notifications_email_delivery TO confia_readonly;
-- confia_portal_app: no grant, the portal never sends email. confia_owner: schema owner, without
-- BYPASSRLS. confia_backup: reads through pg_read_all_data, which never bypasses row-level security.

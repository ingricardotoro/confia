-- Twentieth migration (F1, deliverable 5; docs/cortes/guardians-consent.md, docs/08-datos-privacidad-y-retencion.md
-- section 8). Creates the event log of the consent of a guardian to receive communications, per channel and per
-- purpose, with its evidence.
--
-- A consent is a fact with evidence, not a mutable flag: every grant and every withdrawal is one row, and the current
-- state of a (guardian, channel, purpose) is DERIVED from its latest event (the highest seq), never stored. The table
-- is append-only: the application role holds INSERT and SELECT only (so it has no UPDATE, DELETE or TRUNCATE
-- privilege), and a trigger refuses every UPDATE and DELETE whatever the role (the owner included). There is no
-- BEFORE TRUNCATE trigger, unlike the audit trail: this table references guardians_guardian, and the cleanup of the
-- integration tests (which truncates every table without such a trigger, in one statement) could not truncate the
-- guardians otherwise. TRUNCATE stays out of reach of the application role by privilege.
--
-- Rules that are local to a row live here as CHECKs: the closed sets of channel, purpose, action and evidence; the
-- purpose can only be INSTITUTIONAL or MARKETING (the transactional purpose is based on contract or legitimate
-- interest and is never recorded as a consent); the policy version is present exactly when the action is GRANT and has
-- the shape ^[A-Za-z0-9._-]{1,32}$. PORTAL_FORM is a valid evidence source for the portal that comes later; the
-- administrative API refuses it. The guardian must exist in the same institution (composite foreign key).
--
-- seq is the order of the events: the use case locks the guardian row before it reads the current state and inserts,
-- so the events of one guardian are written one at a time and seq follows the commit order. occurred_at is a
-- timestamp, not the order. Contact data never goes in this table: it holds the guardian, the combination, the
-- evidence and the staff account that recorded it.

CREATE TABLE guardians_guardian_consent_event (
    institution_id  UUID        NOT NULL,
    id              UUID        NOT NULL,
    seq             BIGINT      GENERATED ALWAYS AS IDENTITY,
    guardian_id     UUID        NOT NULL,
    channel         TEXT        NOT NULL,
    purpose         TEXT        NOT NULL,
    action          TEXT        NOT NULL,
    evidence_source TEXT        NOT NULL,
    policy_version  VARCHAR(32),
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    recorded_by     UUID        NOT NULL,
    CONSTRAINT guardians_guardian_consent_event_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT guardians_guardian_consent_event_seq_uq UNIQUE (seq),
    CONSTRAINT guardians_guardian_consent_event_guardian_fk FOREIGN KEY (institution_id, guardian_id)
        REFERENCES guardians_guardian (institution_id, id),
    CONSTRAINT guardians_guardian_consent_event_channel_chk CHECK (channel IN ('EMAIL', 'SMS', 'WHATSAPP')),
    CONSTRAINT guardians_guardian_consent_event_purpose_chk CHECK (purpose IN ('INSTITUTIONAL', 'MARKETING')),
    CONSTRAINT guardians_guardian_consent_event_action_chk CHECK (action IN ('GRANT', 'WITHDRAW')),
    CONSTRAINT guardians_guardian_consent_event_evidence_chk CHECK (
        evidence_source IN ('SIGNED_PAPER_FORM', 'ADMIN_REGISTERED', 'PORTAL_FORM')),
    -- The policy version is present exactly when the consent is granted.
    CONSTRAINT guardians_guardian_consent_event_version_chk CHECK ((action = 'GRANT') = (policy_version IS NOT NULL)),
    CONSTRAINT guardians_guardian_consent_event_version_shape_chk CHECK (
        policy_version IS NULL OR policy_version ~ '^[A-Za-z0-9._-]{1,32}$')
);

-- The latest event of a combination, and the newest events of a guardian.
CREATE INDEX guardians_guardian_consent_event_latest_idx
    ON guardians_guardian_consent_event (institution_id, guardian_id, channel, purpose, seq DESC);
CREATE INDEX guardians_guardian_consent_event_history_idx
    ON guardians_guardian_consent_event (institution_id, guardian_id, seq DESC);

-- A consent event is evidence: it is never edited or deleted, by any role.
CREATE FUNCTION guardians_guardian_consent_event_is_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'a consent event is immutable: it cannot be updated or deleted'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER guardians_guardian_consent_event_no_update_delete
    BEFORE UPDATE OR DELETE ON guardians_guardian_consent_event
    FOR EACH ROW EXECUTE FUNCTION guardians_guardian_consent_event_is_append_only();

ALTER TABLE guardians_guardian_consent_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE guardians_guardian_consent_event FORCE  ROW LEVEL SECURITY;

CREATE POLICY guardians_guardian_consent_event_institution_isolation ON guardians_guardian_consent_event
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON guardians_guardian_consent_event FROM PUBLIC;

-- INSERT and SELECT only: no UPDATE and no DELETE for anybody. The read-only role reads it (it holds no contact
-- data). confia_portal_app: no grant yet (the portal cut that records PORTAL_FORM events will add its own).
GRANT SELECT, INSERT ON guardians_guardian_consent_event TO confia_admin_app;
GRANT SELECT ON guardians_guardian_consent_event TO confia_readonly;

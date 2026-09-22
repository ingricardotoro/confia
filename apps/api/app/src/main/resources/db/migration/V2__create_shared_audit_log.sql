-- Second business migration (F0 change 5, part B, PR B2a; design.md decisions 3, 4, 7, 8;
-- specs/audit-trail/spec.md; specs/build-integrity/spec.md). Creates shared_audit_log (the ledger)
-- and its companion shared_audit_chain_head (the per-institution hash-chain state, design.md
-- decision 3): the stable object two concurrent writers of the same institution serialize on.
--
-- This migration does NOT populate id, prev_hash or row_hash by trigger yet: the chaining trigger
-- (shared_audit_log_chain(), decisions 4, 5 and 6) is PR B2b's own V3 migration (design.md,
-- "Secuencia de aplicación", steps 9-12 vs. 13-17). Every direct insert against shared_audit_log in
-- this cut's own tests supplies those three columns explicitly.

CREATE TABLE shared_audit_chain_head (
    institution_id UUID   NOT NULL,
    next_id        BIGINT NOT NULL,
    CONSTRAINT shared_audit_chain_head_pk PRIMARY KEY (institution_id),
    CONSTRAINT shared_audit_chain_head_next_id_chk CHECK (next_id >= 2)
);

-- Row-level security (design.md decision 3: passes the same four generic schema gates as every
-- other business table). FORCE matters here exactly as it does everywhere else in this codebase:
-- without it, confia_owner — who runs this very migration — would silently bypass the policy.
ALTER TABLE shared_audit_chain_head ENABLE ROW LEVEL SECURITY;
ALTER TABLE shared_audit_chain_head FORCE  ROW LEVEL SECURITY;

CREATE POLICY shared_audit_chain_head_institution_isolation ON shared_audit_chain_head
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

CREATE TABLE shared_audit_log (
    id               BIGINT        NOT NULL,   -- assigned by the chaining trigger, PR B2b
    institution_id   UUID          NOT NULL,
    occurred_at      TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    actor_id         UUID,                     -- NULL for a system actor
    actor_kind       TEXT          NOT NULL,
    actor_label      TEXT          NOT NULL,
    source_ip        INET,
    user_agent       TEXT,
    request_id       UUID          NOT NULL,
    trace_id         TEXT,
    action           TEXT          NOT NULL,
    entity_type      TEXT          NOT NULL,
    entity_id        TEXT          NOT NULL,
    outcome          TEXT          NOT NULL,
    before_value     JSONB,
    after_value      JSONB,
    reason           TEXT,
    approver_id      UUID,
    prev_hash        BYTEA         NOT NULL,   -- assigned by the chaining trigger, PR B2b
    row_hash         BYTEA         NOT NULL,   -- assigned by the chaining trigger, PR B2b
    CONSTRAINT shared_audit_log_pk           PRIMARY KEY (institution_id, id),
    CONSTRAINT shared_audit_log_prev_hash_uq UNIQUE (institution_id, prev_hash),
    CONSTRAINT shared_audit_log_outcome_chk    CHECK (outcome IN ('success', 'denied', 'error')),
    CONSTRAINT shared_audit_log_actor_kind_chk CHECK (actor_kind IN ('staff', 'guardian', 'system')),
    CONSTRAINT shared_audit_log_prev_hash_len_chk CHECK (octet_length(prev_hash) = 32),
    CONSTRAINT shared_audit_log_row_hash_len_chk  CHECK (octet_length(row_hash)  = 32)
);

-- institution_id leads every index: the row-level-security policy always filters by it first
-- (docs/03-seguridad.md section 12.1 declares these without it; corrected here, design.md decision
-- 7, point 3). None of the four is unique, so none interacts with the anti-fork constraint above.
CREATE INDEX shared_audit_log_entity_idx  ON shared_audit_log (institution_id, entity_type, entity_id, occurred_at DESC);
CREATE INDEX shared_audit_log_actor_idx   ON shared_audit_log (institution_id, actor_id, occurred_at DESC);
CREATE INDEX shared_audit_log_action_idx  ON shared_audit_log (institution_id, action, occurred_at DESC);
CREATE INDEX shared_audit_log_request_idx ON shared_audit_log (institution_id, request_id);

ALTER TABLE shared_audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE shared_audit_log FORCE  ROW LEVEL SECURITY;

-- NULLIF(..., '') turns an empty-string session setting into NULL before the ::uuid cast (V1
-- migration's own pattern), so a session with app.institution_id set to '' denies (zero rows)
-- instead of raising a cast error. WITH CHECK is explicit, not inherited from USING: with FORCE ROW
-- LEVEL SECURITY the policy also constrains the owner's own writes.
CREATE POLICY shared_audit_log_institution_isolation ON shared_audit_log
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

-- Append-only enforcement (design.md decision 8): a second barrier, independent of GRANT/REVOKE,
-- that rejects even confia_owner, the schema owner, who is not subject to GRANT/REVOKE at all.
CREATE FUNCTION shared_audit_is_append_only() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% es de solo inserción: % rechazado', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER shared_audit_log_no_update
    BEFORE UPDATE ON shared_audit_log
    FOR EACH ROW EXECUTE FUNCTION shared_audit_is_append_only();

CREATE TRIGGER shared_audit_log_no_delete
    BEFORE DELETE ON shared_audit_log
    FOR EACH ROW EXECUTE FUNCTION shared_audit_is_append_only();

CREATE TRIGGER shared_audit_log_no_truncate
    BEFORE TRUNCATE ON shared_audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION shared_audit_is_append_only();

-- shared_audit_chain_head allows UPDATE — the future chaining trigger needs it to advance next_id —
-- but never DELETE or TRUNCATE: resetting the head is exactly how a chain forks silently, in
-- complete silence but for the anti-fork unique constraint on shared_audit_log (design.md decision
-- 8).
CREATE TRIGGER shared_audit_chain_head_no_delete
    BEFORE DELETE ON shared_audit_chain_head
    FOR EACH ROW EXECUTE FUNCTION shared_audit_is_append_only();

CREATE TRIGGER shared_audit_chain_head_no_truncate
    BEFORE TRUNCATE ON shared_audit_chain_head
    FOR EACH STATEMENT EXECUTE FUNCTION shared_audit_is_append_only();

-- Privileges (design.md decision 8; docs/03-seguridad.md sections 6.1 and 12.3).
REVOKE ALL ON shared_audit_log        FROM PUBLIC;
REVOKE ALL ON shared_audit_chain_head FROM PUBLIC;

GRANT SELECT, INSERT ON shared_audit_log TO confia_admin_app;
GRANT SELECT          ON shared_audit_log TO confia_readonly;
-- confia_portal_app: no GRANT at all on either table (docs/03 section 6.1's short, explicit list).
-- confia_admin_app, confia_portal_app and confia_readonly: no GRANT at all on
-- shared_audit_chain_head either; only the future SECURITY DEFINER chaining trigger writes it
-- (design.md decision 5), so confia_admin_app can insert into the ledger without ever being able
-- to touch the chain state directly.
-- confia_backup reads both through pg_read_all_data (granted once, in the test-only role script /
-- the real role-provisioning script outside this migration), which does NOT bypass row-level
-- security.

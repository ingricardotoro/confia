-- Eighth migration (F0 change 7, part 4b, slice S1; session-tokens-and-web-layer design.md
-- decision 8; specs/build-integrity/spec.md). Creates the refresh-token family and token tables.
-- Only the SHA-256 of a refresh token is stored, never the token itself.
--
-- Three layers keep the data coherent. CHECK and FOREIGN KEY constraints fix the shape of a row.
-- Two BEFORE UPDATE triggers (at the end of the file) make state move only forward, whoever the
-- caller is. The rules that need a duration or a clock stay in code (SessionLifetimePolicy), among
-- them token.expires_at <= family.absolute_expires_at: no constraint here enforces it, because it
-- compares a row with its parent row.

CREATE TABLE identity_refresh_token_family (
    institution_id         UUID        NOT NULL,
    id                     UUID        NOT NULL,
    account_id             UUID        NOT NULL,
    second_factor_verified BOOLEAN     NOT NULL,   -- amr = pwd, plus otp when true
    user_agent_fingerprint TEXT        NOT NULL,   -- hex SHA-256 of the sanitized User-Agent
    ip_first_seen          INET,                   -- clear text by owner decision (docs/08 section 4.1)
    -- No DEFAULT on any instant: the adapter writes them from the injected Clock, and
    -- absolute_expires_at arrives already computed by SessionLifetimePolicy, the single source of
    -- the lifetimes (design.md decision 8). No CHECK repeats a duration, the same criterion as V7.
    created_at             TIMESTAMPTZ NOT NULL,
    last_used_at           TIMESTAMPTZ NOT NULL,
    absolute_expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at             TIMESTAMPTZ,
    revoked_reason         TEXT,
    CONSTRAINT identity_refresh_token_family_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT identity_refresh_token_family_account_fk FOREIGN KEY (institution_id, account_id)
        REFERENCES identity_staff_account (institution_id, id),
    CONSTRAINT identity_refresh_token_family_fp_chk CHECK (user_agent_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT identity_refresh_token_family_window_chk
        CHECK (absolute_expires_at > created_at AND last_used_at >= created_at),
    -- A single address, never a network: /32 for IPv4 and /128 for IPv6.
    CONSTRAINT identity_refresh_token_family_ip_host_chk CHECK (
        ip_first_seen IS NULL
        OR masklen(ip_first_seen) = CASE family(ip_first_seen) WHEN 4 THEN 32 ELSE 128 END),
    -- Closed catalogue: only the reasons with a producer in this change (owner decision 7).
    -- 'logout' and 'admin_revoke' arrive with their own migrations, which widen this CHECK.
    -- Idle and absolute expiry are never written: they are derived from the columns and the clock.
    CONSTRAINT identity_refresh_token_family_reason_chk
        CHECK (revoked_reason IN ('reuse_detected', 'password_change')),
    CONSTRAINT identity_refresh_token_family_revoked_chk
        CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL)),
    CONSTRAINT identity_refresh_token_family_revoked_after_created_chk
        CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

-- Reason: "revoke every live family of an account" (design.md decision 12) without reading the
-- revoked ones.
CREATE INDEX identity_refresh_token_family_live_by_account
    ON identity_refresh_token_family (institution_id, account_id) WHERE revoked_at IS NULL;

CREATE TABLE identity_refresh_token (
    institution_id                  UUID        NOT NULL,
    id                              UUID        NOT NULL,
    family_id                       UUID        NOT NULL,
    token_hash                      TEXT        NOT NULL,
    issued_at                       TIMESTAMPTZ NOT NULL,
    expires_at                      TIMESTAMPTZ NOT NULL,
    consumed_at                     TIMESTAMPTZ,
    -- Fingerprint of whoever presented the token when it was consumed: the grace window compares
    -- a retry against this one, not against the login's (design.md decision 11).
    consumed_user_agent_fingerprint TEXT,
    replaced_by                     UUID,
    -- Lives on the consumed PREDECESSOR row, encrypted with the row-bound AAD (ADR-0023), format
    -- 'v1:...'. Emptied on the next use of the family.
    successor_token_ciphertext      TEXT,
    CONSTRAINT identity_refresh_token_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT identity_refresh_token_family_fk FOREIGN KEY (institution_id, family_id)
        REFERENCES identity_refresh_token_family (institution_id, id),
    -- Target of the composite successor key below: a token is also identified within its family.
    CONSTRAINT identity_refresh_token_family_member_uq UNIQUE (institution_id, family_id, id),
    -- The successor must belong to the SAME family as its predecessor, never to another family of
    -- the same institution. Deferred so that rotation can consume the predecessor and then insert
    -- its successor. While replaced_by is NULL the key is not checked (MATCH SIMPLE).
    CONSTRAINT identity_refresh_token_successor_fk
        FOREIGN KEY (institution_id, family_id, replaced_by)
        REFERENCES identity_refresh_token (institution_id, family_id, id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT identity_refresh_token_not_self_successor_chk CHECK (replaced_by IS DISTINCT FROM id),
    CONSTRAINT identity_refresh_token_hash_uq UNIQUE (institution_id, token_hash),
    CONSTRAINT identity_refresh_token_hash_chk CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT identity_refresh_token_window_chk CHECK (expires_at > issued_at),
    -- Consumption is all or nothing: the instant, its successor and who presented the token.
    CONSTRAINT identity_refresh_token_consumed_chk CHECK (
        (consumed_at IS NULL) = (replaced_by IS NULL)
        AND (consumed_at IS NULL) = (consumed_user_agent_fingerprint IS NULL)),
    CONSTRAINT identity_refresh_token_consumed_fp_chk
        CHECK (consumed_user_agent_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT identity_refresh_token_consumed_after_issued_chk
        CHECK (consumed_at IS NULL OR consumed_at >= issued_at),
    -- Content is required after the 'v1:' prefix; a bare prefix is not a ciphertext.
    CONSTRAINT identity_refresh_token_successor_chk CHECK (
        successor_token_ciphertext IS NULL
        OR (consumed_at IS NOT NULL AND successor_token_ciphertext ~ '^v1:.+'))
);

-- Safety net of "exactly one successor", not the mechanism that keeps it (the family row lock
-- serializes every rotation, design.md decision 10): at most one unconsumed token per family and
-- at most one predecessor per token.
-- Reason: at most one open token per family; it also finds the open token of a family.
CREATE UNIQUE INDEX identity_refresh_token_one_open_per_family
    ON identity_refresh_token (institution_id, family_id) WHERE consumed_at IS NULL;
-- Reason: two tokens cannot name the same successor; it also finds the predecessor of a token.
-- Membership of the successor in the same family is the composite foreign key's job.
CREATE UNIQUE INDEX identity_refresh_token_one_predecessor
    ON identity_refresh_token (institution_id, replaced_by) WHERE replaced_by IS NOT NULL;
-- There is no separate index to read the tokens of a family: identity_refresh_token_family_member_uq
-- starts with exactly (institution_id, family_id) and serves that lookup and the family foreign key.

ALTER TABLE identity_refresh_token_family ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_refresh_token_family FORCE  ROW LEVEL SECURITY;
ALTER TABLE identity_refresh_token        ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_refresh_token        FORCE  ROW LEVEL SECURITY;

CREATE POLICY identity_refresh_token_family_institution_isolation ON identity_refresh_token_family
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

CREATE POLICY identity_refresh_token_institution_isolation ON identity_refresh_token
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

-- Privileges, per column for the first time: the columns that never change after insert (hash,
-- expiries, fingerprint, address, institution and identifiers) are immutable for the application
-- role. SELECT ... FOR UPDATE needs UPDATE on at least one column, which these grants satisfy.
REVOKE ALL ON identity_refresh_token_family FROM PUBLIC;
REVOKE ALL ON identity_refresh_token        FROM PUBLIC;

GRANT SELECT, INSERT ON identity_refresh_token_family TO confia_admin_app;
GRANT UPDATE (last_used_at, revoked_at, revoked_reason)
    ON identity_refresh_token_family TO confia_admin_app;
GRANT SELECT, INSERT ON identity_refresh_token TO confia_admin_app;
GRANT UPDATE (consumed_at, consumed_user_agent_fingerprint, replaced_by, successor_token_ciphertext)
    ON identity_refresh_token TO confia_admin_app;

GRANT SELECT ON identity_refresh_token_family TO confia_readonly;
GRANT SELECT ON identity_refresh_token        TO confia_readonly;
-- confia_portal_app: no GRANT at all, staff data. No DELETE to any role: rows are retained until
-- change 9 decides the purge.

-- Forward-only state, enforced by the engine whoever the caller is. A BEFORE UPDATE trigger runs
-- before the table constraints, so these rules are the ones reported when a statement breaks both.
-- Each raises check_violation (23514) and names its rule as a constraint does. Every legitimate
-- transition of design.md decisions 10 to 12 passes: rotation, revocation for reuse or for a
-- password change, clearing the grace successor, and advancing last_used_at.

CREATE FUNCTION identity_refresh_token_family_forward_only() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    -- A revocation is final: neither the instant nor the reason changes, and neither goes back to NULL.
    IF OLD.revoked_at IS NOT NULL
       AND (NEW.revoked_at IS DISTINCT FROM OLD.revoked_at
            OR NEW.revoked_reason IS DISTINCT FROM OLD.revoked_reason) THEN
        RAISE EXCEPTION 'identity_refresh_token_family_revocation_final: a revoked family keeps its revocation'
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'identity_refresh_token_family_revocation_final';
    END IF;
    -- Idle expiry is derived from last_used_at: moving it back would resurrect an idle family.
    IF NEW.last_used_at < OLD.last_used_at THEN
        RAISE EXCEPTION 'identity_refresh_token_family_last_used_forward: last_used_at never moves backwards'
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'identity_refresh_token_family_last_used_forward';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER identity_refresh_token_family_forward_only
    BEFORE UPDATE ON identity_refresh_token_family
    FOR EACH ROW EXECUTE FUNCTION identity_refresh_token_family_forward_only();

CREATE FUNCTION identity_refresh_token_forward_only() RETURNS trigger
    LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    -- A consumption is final: the instant, the successor and the presenter's fingerprint never
    -- change and never go back to NULL, so a consumed token cannot be reopened or redirected.
    IF OLD.consumed_at IS NOT NULL
       AND (NEW.consumed_at IS DISTINCT FROM OLD.consumed_at
            OR NEW.replaced_by IS DISTINCT FROM OLD.replaced_by
            OR NEW.consumed_user_agent_fingerprint IS DISTINCT FROM OLD.consumed_user_agent_fingerprint) THEN
        RAISE EXCEPTION 'identity_refresh_token_consumption_final: a consumed token keeps its consumption'
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'identity_refresh_token_consumption_final';
    END IF;
    -- The encrypted successor is written once, in the statement that consumes the token, and may
    -- then only be cleared (owner decision 6: after the grace window). A different value, or any
    -- value written to an already consumed token (one that never had it, or had it cleared), is refused.
    IF NEW.successor_token_ciphertext IS NOT NULL
       AND NEW.successor_token_ciphertext IS DISTINCT FROM OLD.successor_token_ciphertext
       AND OLD.consumed_at IS NOT NULL THEN
        RAISE EXCEPTION 'identity_refresh_token_ciphertext_forward: the successor ciphertext can only be cleared'
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'identity_refresh_token_ciphertext_forward';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER identity_refresh_token_forward_only
    BEFORE UPDATE ON identity_refresh_token
    FOR EACH ROW EXECUTE FUNCTION identity_refresh_token_forward_only();

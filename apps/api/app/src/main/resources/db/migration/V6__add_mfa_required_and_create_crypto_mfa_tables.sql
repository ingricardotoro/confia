-- Sixth migration (F0 change 7, part 2 of 4, cut C1; design.md decisions 1, 2 and 3;
-- specs/build-integrity/spec.md; specs/identity/spec.md). Adds mfa_required to
-- identity_staff_account and creates the four tables the column-encryption-and-mfa-totp change
-- needs: the data-encryption-key envelope table, owned by com.confia.shared.crypto, and the three
-- identity MFA tables (TOTP credential, recovery code, TOTP verification backoff).

ALTER TABLE identity_staff_account ADD COLUMN mfa_required BOOLEAN NOT NULL;
-- No DEFAULT: there is no deployed environment and no real data (ADR-0008), so every row this test
-- tree seeds already sets the value explicitly for the scenario it needs (design.md decision 3,
-- point 1).

CREATE TABLE shared_data_encryption_key (
    institution_id UUID        NOT NULL,
    id             UUID        NOT NULL,
    status         TEXT        NOT NULL,
    wrapped_key    TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT shared_data_encryption_key_pk         PRIMARY KEY (institution_id, id),
    CONSTRAINT shared_data_encryption_key_status_chk CHECK (status IN ('active', 'retired')),
    CONSTRAINT shared_data_encryption_key_wrapped_chk CHECK (wrapped_key ~ '^[A-Za-z0-9+/=]+:[A-Za-z0-9+/=]+:[A-Za-z0-9+/=]+$')
);

-- At most one active DEK per institution (supports the idempotent lazy creation of decision 4,
-- "ON CONFLICT (institution_id) WHERE status = 'active' DO NOTHING", sonda S5, apply-progress.md).
-- This is an INDEX, not a PRIMARY KEY/UNIQUE constraint in pg_constraint: MultiTenantSchemaIT
-- verifies unique constraints by catalogue (design.md part 1, section 2, row on expression
-- indexes) and may not see this partial index — said explicitly instead of assumed. It does not
-- replace the primary key's own constraint, which already includes institution_id and satisfies
-- ADR-0009 rule 2 by itself.
CREATE UNIQUE INDEX shared_data_encryption_key_one_active_per_institution
    ON shared_data_encryption_key (institution_id) WHERE status = 'active';

CREATE TABLE identity_mfa_totp_credential (
    institution_id        UUID        NOT NULL,
    account_id            UUID        NOT NULL,
    encrypted_secret      TEXT        NOT NULL,
    last_accepted_counter BIGINT      NOT NULL DEFAULT -1,
    enrolled_at           TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT identity_mfa_totp_credential_pk          PRIMARY KEY (institution_id, account_id),
    CONSTRAINT identity_mfa_totp_credential_account_fk  FOREIGN KEY (institution_id, account_id)
        REFERENCES identity_staff_account (institution_id, id),
    CONSTRAINT identity_mfa_totp_credential_secret_chk  CHECK (encrypted_secret LIKE 'v1:%'),
    CONSTRAINT identity_mfa_totp_credential_counter_chk CHECK (last_accepted_counter >= -1)
);

-- last_accepted_counter starts at -1, not 0: the requirement rejects any code whose counter is less
-- than or equal to the last accepted one. If the seed value were 0, the legitimate counter 0 (the
-- first possible TOTP period) would be rejected as indistinguishable from "already accepted" on
-- its very first use. -1 is a sentinel that is never a valid TOTP counter (counters are
-- floor(unix_time / 30), always non-negative since 1970), so the first real code always exceeds it.

CREATE TABLE identity_mfa_recovery_code (
    institution_id UUID        NOT NULL,
    account_id     UUID        NOT NULL,
    id             UUID        NOT NULL,
    code_hash      TEXT        NOT NULL,
    used_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT identity_mfa_recovery_code_pk         PRIMARY KEY (institution_id, id),
    CONSTRAINT identity_mfa_recovery_code_account_fk FOREIGN KEY (institution_id, account_id)
        REFERENCES identity_staff_account (institution_id, id),
    CONSTRAINT identity_mfa_recovery_code_hash_chk   CHECK (code_hash LIKE '$argon2id$%')
);

CREATE INDEX identity_mfa_recovery_code_account_idx
    ON identity_mfa_recovery_code (institution_id, account_id) WHERE used_at IS NULL;
-- Read-only partial index (not a uniqueness one): "unused codes of this account" is the hot path
-- of both enrollment and consumption (decision 8), and filtering by used_at IS NULL in the index
-- avoids scanning already-spent codes of older accounts.

CREATE TABLE identity_mfa_totp_backoff (
    institution_id       UUID        NOT NULL,
    account_id           UUID        NOT NULL,
    consecutive_failures INTEGER     NOT NULL DEFAULT 0,
    last_attempt_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT identity_mfa_totp_backoff_pk          PRIMARY KEY (institution_id, account_id),
    CONSTRAINT identity_mfa_totp_backoff_account_fk  FOREIGN KEY (institution_id, account_id)
        REFERENCES identity_staff_account (institution_id, id),
    CONSTRAINT identity_mfa_totp_backoff_failures_chk CHECK (consecutive_failures >= 0)
);

-- Unlike identity_login_backoff (which deliberately carries no foreign key, part 1 decision 3),
-- this table has one to the account: login protects identifiers that may correspond to no account
-- at all (timing-based enumeration), while TOTP verification always happens after an already
-- verified password, against an account that exists — there is no orphan identifier to protect
-- here.

ALTER TABLE shared_data_encryption_key   ENABLE ROW LEVEL SECURITY;
ALTER TABLE shared_data_encryption_key   FORCE  ROW LEVEL SECURITY;
ALTER TABLE identity_mfa_totp_credential ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_mfa_totp_credential FORCE  ROW LEVEL SECURITY;
ALTER TABLE identity_mfa_recovery_code   ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_mfa_recovery_code   FORCE  ROW LEVEL SECURITY;
ALTER TABLE identity_mfa_totp_backoff    ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_mfa_totp_backoff    FORCE  ROW LEVEL SECURITY;

CREATE POLICY shared_data_encryption_key_institution_isolation ON shared_data_encryption_key
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

CREATE POLICY identity_mfa_totp_credential_institution_isolation ON identity_mfa_totp_credential
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

CREATE POLICY identity_mfa_recovery_code_institution_isolation ON identity_mfa_recovery_code
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

CREATE POLICY identity_mfa_totp_backoff_institution_isolation ON identity_mfa_totp_backoff
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

-- Privileges (design.md decision 3; docs/03-seguridad.md section 6.1 adenda). REVOKE always
-- precedes any GRANT. No DELETE to any role ever: retiring a DEK is setting status = 'retired',
-- never deleting the row (real rotation is a named gap, D2); invalidating a recovery code is
-- setting used_at, never deleting it.

REVOKE ALL ON shared_data_encryption_key   FROM PUBLIC;
REVOKE ALL ON identity_mfa_totp_credential FROM PUBLIC;
REVOKE ALL ON identity_mfa_recovery_code   FROM PUBLIC;
REVOKE ALL ON identity_mfa_totp_backoff    FROM PUBLIC;

GRANT SELECT, INSERT, UPDATE ON shared_data_encryption_key   TO confia_admin_app;
GRANT SELECT, INSERT, UPDATE ON identity_mfa_totp_credential TO confia_admin_app;
GRANT SELECT, INSERT, UPDATE ON identity_mfa_recovery_code   TO confia_admin_app;
GRANT SELECT, INSERT, UPDATE ON identity_mfa_totp_backoff    TO confia_admin_app;

GRANT SELECT ON shared_data_encryption_key   TO confia_readonly;
GRANT SELECT ON identity_mfa_totp_credential TO confia_readonly;
GRANT SELECT ON identity_mfa_recovery_code   TO confia_readonly;
GRANT SELECT ON identity_mfa_totp_backoff    TO confia_readonly;

-- confia_portal_app: no GRANT at all on any of the four. These are staff and cross-cutting
-- encryption-infrastructure data; docs/03 section 6.1 already excludes the portal from "user" and
-- from shared_audit_log, and the same treatment extends here.
-- confia_owner: schema owner, not subject to GRANT/REVOKE, retains every privilege, without
-- BYPASSRLS.
-- confia_backup: reads through pg_read_all_data, which never bypasses row-level security.

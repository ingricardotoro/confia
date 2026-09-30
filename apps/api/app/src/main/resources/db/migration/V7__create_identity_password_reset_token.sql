-- Seventh migration (F0 change 7, part 3 of 4, cut C1; password-recovery-token design.md decision
-- 1; specs/build-integrity/spec.md). Creates the password-reset token table. Only the SHA-256 of a
-- token is stored, never the token itself.

CREATE TABLE identity_password_reset_token (
    institution_id UUID        NOT NULL,
    id             UUID        NOT NULL,
    account_id     UUID        NOT NULL,
    token_hash     TEXT        NOT NULL,
    -- No DEFAULT on either instant: the adapter writes both from the injected Clock, and
    -- expires_at arrives already computed by PasswordResetTokenPolicy, the single source of the
    -- 30-minute validity (design.md decisions 1 and 4).
    issued_at      TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    consumed_at    TIMESTAMPTZ,
    superseded_at  TIMESTAMPTZ,
    CONSTRAINT identity_password_reset_token_pk         PRIMARY KEY (institution_id, id),
    CONSTRAINT identity_password_reset_token_account_fk FOREIGN KEY (institution_id, account_id)
        REFERENCES identity_staff_account (institution_id, id),
    CONSTRAINT identity_password_reset_token_hash_uq    UNIQUE (institution_id, token_hash),
    CONSTRAINT identity_password_reset_token_hash_chk   CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    -- Deliberately not "exactly 30 minutes": a second copy of the validity here would be a second
    -- source of truth for it (design.md decision 1).
    CONSTRAINT identity_password_reset_token_window_chk CHECK (expires_at > issued_at),
    CONSTRAINT identity_password_reset_token_final_chk
        CHECK (consumed_at IS NULL OR superseded_at IS NULL)
);

-- Safety net for "at most one open token per account", not the mechanism that keeps it: issuance
-- and reset serialize on the account row first (design.md decision 2). An expired token nobody
-- superseded still counts as open, so issuance supersedes every open token, expired or not.
-- Sonda S1 (apply-progress.md): a concurrent second insert waits and then fails with 23505.
CREATE UNIQUE INDEX identity_password_reset_token_one_open_per_account
    ON identity_password_reset_token (institution_id, account_id)
    WHERE consumed_at IS NULL AND superseded_at IS NULL;

-- Serves the count of issuances in the 60-minute window (design.md decision 5).
CREATE INDEX identity_password_reset_token_issued_idx
    ON identity_password_reset_token (institution_id, account_id, issued_at);

ALTER TABLE identity_password_reset_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_password_reset_token FORCE  ROW LEVEL SECURITY;

CREATE POLICY identity_password_reset_token_institution_isolation ON identity_password_reset_token
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON identity_password_reset_token FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON identity_password_reset_token TO confia_admin_app;
GRANT SELECT                  ON identity_password_reset_token TO confia_readonly;
-- confia_portal_app: no GRANT at all, staff data. No DELETE to any role: expired rows are retained
-- until change 9 decides the purge (owner, 2026-09-30).

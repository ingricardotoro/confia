-- Fifth business migration (F0 change 7, PR C1; design.md decisions 3 and 4;
-- specs/build-integrity/spec.md). Creates the two identity tables this cut of
-- identity-module-and-password-authentication needs: the staff account itself, and the backoff
-- state that guards login attempts against it — kept in a SEPARATE table, indexed by a keyed hash
-- of the presented identifier rather than by the account, because the anti-brute-force delay
-- applies just as much to an identifier that corresponds to no account at all (design.md decision
-- 3: "el estado de retroceso vive íntegro en la segunda, también para las cuentas que sí
-- existen").

CREATE TABLE identity_staff_account (
    institution_id UUID        NOT NULL,
    id             UUID        NOT NULL,
    email          TEXT        NOT NULL,
    password_hash  TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT identity_staff_account_pk        PRIMARY KEY (institution_id, id),
    CONSTRAINT identity_staff_account_email_uq  UNIQUE (institution_id, email),
    CONSTRAINT identity_staff_account_email_lower_chk CHECK (email !~ '[A-Z]'),
    CONSTRAINT identity_staff_account_email_len_chk   CHECK (char_length(email) BETWEEN 3 AND 320),
    CONSTRAINT identity_staff_account_password_hash_chk CHECK (password_hash LIKE '$argon2id$%')
);

-- email_lower_chk uses a plain ASCII regular expression, never CHECK (email = lower(email)):
-- lower() depends on the database's collation, and Java's String.toLowerCase(Locale.ROOT) does not
-- agree with every collation across all of Unicode (the Turkish İ is the classic counterexample).
-- A collation-dependent CHECK could pass in the test image and reject in another — exactly the
-- "green for the wrong reason" family this repository has already paid for three times. The
-- application normalizes (com.confia.identity.domain.LoginIdentifier: trim, NFKC, then
-- toLowerCase(Locale.ROOT)) before this column is ever written or compared; this CHECK only
-- catches the case that actually matters, an email written with ASCII uppercase letters. Sonda S8
-- (apply-progress.md, task 1.3) confirms empirically that no normalized form of a sample spanning
-- Turkish, German, Greek and CJK script ever produces an ASCII uppercase letter, so this
-- constraint needed no narrowing before this migration was written.
--
-- password_hash_chk is the catalogue expression of what §4.1 requires checking ("el hash
-- almacenado comienza con $argon2id$"), and makes it impossible for a plaintext password to reach
-- this column by mistake.
--
-- created_at uses clock_timestamp(), like shared_audit_log and shared_idempotency_key, not now():
-- now() is the transaction's start instant, and two rows of one transaction would otherwise share
-- one timestamp.

CREATE TABLE identity_login_backoff (
    institution_id       UUID        NOT NULL,
    identifier_hash      TEXT        NOT NULL,
    consecutive_failures INTEGER     NOT NULL DEFAULT 0,
    last_attempt_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT identity_login_backoff_pk PRIMARY KEY (institution_id, identifier_hash),
    CONSTRAINT identity_login_backoff_hash_chk     CHECK (identifier_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT identity_login_backoff_failures_chk CHECK (consecutive_failures >= 0)
);

-- identifier_hash is the HMAC-SHA-256 of the normalized identifier, hex-encoded (64 characters),
-- keyed with a subkey derived from the Argon2id pepper (design.md decision 6, "Subllave para la
-- huella del identificador") — never the raw identifier itself: this table would otherwise become
-- both a store of attacker-controlled strings of arbitrary length and an enumeration oracle for
-- anyone who can read it (design.md decision 3, same reasoning P2 already applied to
-- shared_audit_log.actor_label). Hex TEXT, not BYTEA, follows shared_idempotency_key's own
-- request_hash precedent: equality in Java is plain String.equals, with none of the byte-array ==
-- trap, and the CHECK regex keeps the format visible in the catalogue.
--
-- There is deliberately no foreign key to identity_staff_account: this row's whole reason to exist
-- is that it stands for an identifier that may correspond to no account at all.
--
-- last_attempt_at has no DEFAULT: the adapter always writes it from its own injected Clock, never
-- the database's clock, which is what makes the 30-minute counter-expiry scenario provable with two
-- Clock.fixed instants instead of an actual 31-minute wait (design.md decision 5).
--
-- Purge is a named, undelivered gap, said out loud rather than shipped as an unused index
-- (design.md decision 3, "Costo aceptado"): an unbounded distributed attack against random
-- identifiers grows this table without limit, and the real bound on that growth is the IP-address
-- control this change excludes with a named owner (session-tokens-and-web-layer / change 11).

ALTER TABLE identity_staff_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_staff_account FORCE  ROW LEVEL SECURITY;
ALTER TABLE identity_login_backoff ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_login_backoff FORCE  ROW LEVEL SECURITY;

-- NULLIF(..., '') and an explicit WITH CHECK, identical to V1-V4: current_setting(..., true)
-- returns NULL for an absent setting and the equality denies; an empty string would otherwise reach
-- ''::uuid and raise a cast error instead of a clean denial.
CREATE POLICY identity_staff_account_institution_isolation ON identity_staff_account
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

CREATE POLICY identity_login_backoff_institution_isolation ON identity_login_backoff
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

-- Privileges (design.md decision 4; docs/03-seguridad.md section 6.1). REVOKE always precedes any
-- GRANT.
REVOKE ALL ON identity_staff_account FROM PUBLIC;
REVOKE ALL ON identity_login_backoff FROM PUBLIC;

-- UPDATE for confia_admin_app is not an exception, it is the rule applied (§6.1: UPDATE on
-- non-financial tables). It is needed to clear the backoff counter after a successful login and to
-- increment it after a failed one. No DELETE to any role ever: clearing the counter is setting it
-- to zero, never deleting the row, the same reasoning shared_idempotency_key's caducity already
-- applied.
GRANT SELECT, INSERT, UPDATE ON identity_staff_account TO confia_admin_app;
GRANT SELECT, INSERT, UPDATE ON identity_login_backoff TO confia_admin_app;
GRANT SELECT                  ON identity_staff_account TO confia_readonly;
GRANT SELECT                  ON identity_login_backoff TO confia_readonly;
-- confia_portal_app: no GRANT at all on either table (docs/03 section 6.1: "sin acceso alguno a
-- user, role, cai_range, cashbox_session ni shared_audit_log"; decision 4's correspondencia
-- documental extends that same treatment to identity_login_backoff, which §6.1 does not itself
-- name).
-- confia_owner: schema owner, not subject to GRANT/REVOKE, retains every privilege by definition,
-- without BYPASSRLS.
-- confia_backup: reads through pg_read_all_data, already granted on the whole schema, which never
-- bypasses row-level security.

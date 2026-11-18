-- Thirtieth migration (F3, cut 1; docs/cortes/ledger-core.md). Creates the core of the double-entry ledger: the chart of
-- accounts, the sub-account of a student per currency, the transactions and their entries (INV-01 to INV-07, INV-24,
-- INV-33 and INV-37 to INV-42 of docs/02-modelo-de-dominio.md). No balance column exists anywhere: a balance is a
-- function of the entries (INV-07).
--
-- THE DATABASE REFUSES, to every role including the owner:
--   * an UPDATE, a DELETE or a TRUNCATE of a transaction or an entry (INV-04: row triggers and a statement trigger that
--     raise, whoever runs the statement; the application role also holds no UPDATE or DELETE grant on them);
--   * a transaction that does not balance, has fewer than two entries or mixes currencies (INV-01, INV-02, INV-06: a
--     DEFERRED constraint trigger on the header, verified at COMMIT, so the entries can be inserted in any order);
--   * an entry added to a transaction that an earlier database transaction already created (INV-37: the header stores
--     the id of the transaction that inserted it and the entry trigger compares it with the current one, so a balanced
--     pair cannot be slipped into old history);
--   * a reversal that is not the exact mirror of its original, that reverses a reversal, that is dated before its
--     original or that comes from another module (INV-05, INV-38, INV-41).
-- posted_at and posting_xid are set by the database and never by the caller (INV-42). posting_xid is the value of
-- pg_current_xact_id() (an xid8, which never wraps) stored as BIGINT, so that the generated jOOQ code needs no custom
-- binding for a type it never reads or writes.
--
-- The one UPDATE of the module that is not a chart-of-accounts edit is UPDATE (status) of the student sub-account: it is
-- what SELECT ... FOR UPDATE needs as a privilege (docs/cortes/ledger-core.md, appendix A), and a trigger refuses any
-- other change. Every trigger function is SECURITY INVOKER with a fixed search_path and no PUBLIC execute (no SECURITY
-- DEFINER function exists in this module).
--
-- The institution comes from the session, never from a request: forced row-level security keeps one institution from
-- reading or writing the rows of another, the owner included, and every foreign key is composite with the institution.

CREATE TABLE ledger_account (
    institution_id         UUID         NOT NULL,
    id                     UUID         NOT NULL,
    code                   VARCHAR(30)  NOT NULL,
    name                   VARCHAR(120) NOT NULL,
    account_type           TEXT         NOT NULL,
    holds_student_balances BOOLEAN      NOT NULL,
    is_active              BOOLEAN      NOT NULL DEFAULT TRUE,
    version                BIGINT       NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ledger_account_pk          PRIMARY KEY (institution_id, id),
    CONSTRAINT ledger_account_code_chk    CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,29}$'),
    CONSTRAINT ledger_account_type_chk    CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE')),
    -- Only receivables and credit balances of students carry a student (INV-39).
    CONSTRAINT ledger_account_student_chk CHECK (NOT holds_student_balances OR account_type IN ('ASSET', 'LIABILITY')),
    CONSTRAINT ledger_account_version_chk CHECK (version >= 0)
);

CREATE UNIQUE INDEX ledger_account_code_uq ON ledger_account (institution_id, lower(code));

-- The student is a dimension of the entry, not an account of the chart (decision 1). One per student and currency
-- (INV-33), opened lazily by the first entry. It has no balance column of any kind.
CREATE TABLE ledger_student_account (
    institution_id UUID        NOT NULL,
    id             UUID        NOT NULL,
    student_id     UUID        NOT NULL,
    currency       CHAR(3)     NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'OPEN',
    opened_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ledger_student_account_pk          PRIMARY KEY (institution_id, id),
    CONSTRAINT ledger_student_account_student_fk  FOREIGN KEY (institution_id, student_id)
        REFERENCES students_student (institution_id, id),
    CONSTRAINT ledger_student_account_currency_chk CHECK (currency IN ('HNL', 'USD')),
    CONSTRAINT ledger_student_account_status_chk   CHECK (status IN ('OPEN', 'UNDER_REVIEW')),
    -- INV-33: one sub-account per student and currency. The name ends in _uq.
    CONSTRAINT ledger_student_account_student_uq   UNIQUE (institution_id, student_id, currency),
    -- The target of the composite key of an entry: the entry repeats the currency, so a sub-account cannot receive an
    -- entry of another currency (INV-06).
    CONSTRAINT ledger_student_account_currency_uq  UNIQUE (institution_id, id, currency)
);

CREATE TABLE ledger_transaction (
    institution_id          UUID        NOT NULL,
    id                      UUID        NOT NULL,
    kind                    TEXT        NOT NULL,
    effective_on            DATE        NOT NULL,
    posted_at               TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    posting_xid             BIGINT      NOT NULL,
    source_module           VARCHAR(40) NOT NULL,
    source_ref_id           UUID,
    reverses_transaction_id UUID,
    reason                  VARCHAR(500),
    idempotency_key         VARCHAR(200) NOT NULL,
    payload_hash            CHAR(64)    NOT NULL,
    created_by              UUID        NOT NULL,
    CONSTRAINT ledger_transaction_pk           PRIMARY KEY (institution_id, id),
    CONSTRAINT ledger_transaction_reverses_fk  FOREIGN KEY (institution_id, reverses_transaction_id)
        REFERENCES ledger_transaction (institution_id, id),
    CONSTRAINT ledger_transaction_kind_chk     CHECK (kind IN ('CHARGE', 'DISCOUNT', 'PAYMENT', 'LATE_FEE',
        'CREDIT_NOTE', 'REVERSAL', 'ADJUSTMENT', 'WRITE_OFF')),
    CONSTRAINT ledger_transaction_window_chk   CHECK (effective_on BETWEEN date '2000-01-01' AND date '2100-12-31'),
    CONSTRAINT ledger_transaction_source_chk   CHECK (source_module ~ '^[a-z][a-z0-9_]{1,39}$'),
    -- The key is chosen by the source module (for example charges:accrual:<chargeId>): a whitelist, no spaces.
    CONSTRAINT ledger_transaction_key_chk      CHECK (idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9:_.-]{0,199}$'),
    CONSTRAINT ledger_transaction_hash_chk     CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    -- A reversal points at its original and only a reversal does (INV-05); never at itself.
    CONSTRAINT ledger_transaction_reversal_chk CHECK ((kind = 'REVERSAL') = (reverses_transaction_id IS NOT NULL)),
    CONSTRAINT ledger_transaction_not_self_chk CHECK (reverses_transaction_id IS DISTINCT FROM id),
    -- The reason is mandatory for a reversal and an adjustment, and between 20 and 500 characters when present.
    CONSTRAINT ledger_transaction_reason_chk   CHECK (
        (reason IS NULL OR (char_length(btrim(reason)) >= 20 AND char_length(reason) <= 500))
        AND (kind NOT IN ('REVERSAL', 'ADJUSTMENT') OR reason IS NOT NULL))
);

-- INV-24: a key already seen produces no other effect. PERMANENT: the ledger key never expires, unlike the 24-hour key of
-- the HTTP edge. Named _uq so that the adapter can tell it from the reversal index.
CREATE UNIQUE INDEX ledger_transaction_idempotency_uq ON ledger_transaction (institution_id, idempotency_key);
-- INV-05: an original is reversed once, also under concurrency.
CREATE UNIQUE INDEX ledger_transaction_single_reversal_uq ON ledger_transaction (institution_id, reverses_transaction_id)
    WHERE reverses_transaction_id IS NOT NULL;
CREATE INDEX ledger_transaction_effective_idx ON ledger_transaction (institution_id, effective_on);

CREATE TABLE ledger_entry (
    institution_id     UUID          NOT NULL,
    id                 UUID          NOT NULL,
    transaction_id     UUID          NOT NULL,
    line_number        INTEGER       NOT NULL,
    account_id         UUID          NOT NULL,
    student_account_id UUID,
    side               TEXT          NOT NULL,
    amount             NUMERIC(14,4) NOT NULL,
    currency           CHAR(3)       NOT NULL,
    CONSTRAINT ledger_entry_pk             PRIMARY KEY (institution_id, id),
    CONSTRAINT ledger_entry_line_uq        UNIQUE (institution_id, transaction_id, line_number),
    CONSTRAINT ledger_entry_transaction_fk FOREIGN KEY (institution_id, transaction_id)
        REFERENCES ledger_transaction (institution_id, id),
    CONSTRAINT ledger_entry_account_fk     FOREIGN KEY (institution_id, account_id)
        REFERENCES ledger_account (institution_id, id),
    -- A null student_account_id is not checked by the key; the entry trigger decides whether it may be null (INV-39).
    CONSTRAINT ledger_entry_student_account_fk FOREIGN KEY (institution_id, student_account_id, currency)
        REFERENCES ledger_student_account (institution_id, id, currency),
    -- The amount is always positive, the side gives the sign (INV-03); a hundred entries at most per transaction.
    CONSTRAINT ledger_entry_amount_chk     CHECK (amount > 0),
    -- INV-40: every amount of the ledger is in the minor unit of its currency, never rounded by the ledger.
    CONSTRAINT ledger_entry_minor_unit_chk CHECK (amount = round(amount, 2)),
    CONSTRAINT ledger_entry_side_chk       CHECK (side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entry_currency_chk   CHECK (currency IN ('HNL', 'USD')),
    CONSTRAINT ledger_entry_line_chk       CHECK (line_number BETWEEN 1 AND 100)
);

-- The balance of a sub-account reads this index only (INV-07): the side, the amount and the transaction are in it.
CREATE INDEX ledger_entry_student_account_idx ON ledger_entry (institution_id, student_account_id)
    INCLUDE (side, amount, transaction_id) WHERE student_account_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------------------------
-- Trigger functions
-- ---------------------------------------------------------------------------------------------------------------------

-- INV-04. Used for the transactions and the entries, and for the TRUNCATE of every table of the module.
CREATE FUNCTION ledger_append_only_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    RAISE EXCEPTION 'the ledger is append-only: % on % is refused', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

-- The code, the type and the student flag of an account of the chart never change: only its name, its active flag and the
-- version that goes with them. Nothing is deleted.
CREATE FUNCTION ledger_account_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an account of the ledger is never deleted, it is deactivated' USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.institution_id, NEW.id, NEW.code, NEW.account_type, NEW.holds_student_balances, NEW.created_at)
            IS DISTINCT FROM
       (OLD.institution_id, OLD.id, OLD.code, OLD.account_type, OLD.holds_student_balances, OLD.created_at) THEN
        RAISE EXCEPTION 'the code, the type and the student flag of an account never change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- A student sub-account changes only its status (OPEN or UNDER_REVIEW). Nothing is deleted.
CREATE FUNCTION ledger_student_account_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a student account of the ledger is never deleted' USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.institution_id, NEW.id, NEW.student_id, NEW.currency, NEW.opened_at)
            IS DISTINCT FROM
       (OLD.institution_id, OLD.id, OLD.student_id, OLD.currency, OLD.opened_at) THEN
        RAISE EXCEPTION 'a student account of the ledger changes only its status' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- INV-42 and the rules of a reversal that can be decided when the header is inserted (INV-05, INV-38, INV-41).
CREATE FUNCTION ledger_transaction_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    original ledger_transaction%ROWTYPE;
BEGIN
    -- Whatever the caller sent, the database decides when and in which database transaction the posting happens.
    NEW.posted_at := clock_timestamp();
    NEW.posting_xid := pg_current_xact_id()::text::bigint;

    IF NEW.kind = 'REVERSAL' THEN
        SELECT * INTO original FROM ledger_transaction
            WHERE institution_id = NEW.institution_id AND id = NEW.reverses_transaction_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'the transaction to reverse does not exist' USING ERRCODE = 'foreign_key_violation';
        END IF;
        IF original.kind = 'REVERSAL' THEN
            RAISE EXCEPTION 'a reversal is never reversed' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.effective_on < original.effective_on THEN
            RAISE EXCEPTION 'a reversal is not dated before its original' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.source_module <> original.source_module THEN
            RAISE EXCEPTION 'only the module of origin reverses its transaction' USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- INV-37 and INV-39. An entry enters only in the database transaction that created its header, and its account must be
-- usable. An inactive account stops receiving new facts but a reversal must still be able to mirror an old one.
CREATE FUNCTION ledger_entry_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    header  ledger_transaction%ROWTYPE;
    account ledger_account%ROWTYPE;
BEGIN
    SELECT * INTO header FROM ledger_transaction
        WHERE institution_id = NEW.institution_id AND id = NEW.transaction_id;
    IF NOT FOUND OR header.posting_xid <> pg_current_xact_id()::text::bigint THEN
        RAISE EXCEPTION 'an entry is only added in the database transaction that created its transaction'
            USING ERRCODE = 'restrict_violation';
    END IF;

    SELECT * INTO account FROM ledger_account
        WHERE institution_id = NEW.institution_id AND id = NEW.account_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'the account of the entry does not exist' USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF NOT account.is_active AND header.kind <> 'REVERSAL' THEN
        RAISE EXCEPTION 'the account of the entry is not active' USING ERRCODE = 'check_violation';
    END IF;
    IF account.holds_student_balances <> (NEW.student_account_id IS NOT NULL) THEN
        RAISE EXCEPTION 'an entry carries a student account if and only if its account holds student balances'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- INV-01, INV-02, INV-06 and the mirror of INV-38, verified at COMMIT for the header whose entries are all inserted by
-- then (INV-37 forbids any other transaction from adding more). It also catches a header without entries.
CREATE FUNCTION ledger_transaction_balance_check() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    entry_count    integer;
    currency_count integer;
    total_debit    numeric;
    total_credit   numeric;
BEGIN
    SELECT count(*), count(DISTINCT currency),
           COALESCE(sum(amount) FILTER (WHERE side = 'DEBIT'), 0),
           COALESCE(sum(amount) FILTER (WHERE side = 'CREDIT'), 0)
      INTO entry_count, currency_count, total_debit, total_credit
      FROM ledger_entry
     WHERE institution_id = NEW.institution_id AND transaction_id = NEW.id;

    IF entry_count < 2 THEN
        RAISE EXCEPTION 'a transaction has at least two entries' USING ERRCODE = 'check_violation';
    END IF;
    IF currency_count <> 1 THEN
        RAISE EXCEPTION 'a transaction has a single currency' USING ERRCODE = 'check_violation';
    END IF;
    IF total_debit <> total_credit THEN
        RAISE EXCEPTION 'a transaction balances: debit and credit are equal' USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.kind = 'REVERSAL' THEN
        -- The same accounts, sub-accounts, amounts and currency, on the opposite side, entry for entry.
        IF EXISTS (
                (SELECT account_id, student_account_id, side, amount, currency FROM ledger_entry
                  WHERE institution_id = NEW.institution_id AND transaction_id = NEW.id
                 EXCEPT ALL
                 SELECT account_id, student_account_id, CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END,
                        amount, currency FROM ledger_entry
                  WHERE institution_id = NEW.institution_id AND transaction_id = NEW.reverses_transaction_id))
           OR EXISTS (
                (SELECT account_id, student_account_id, CASE side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END,
                        amount, currency FROM ledger_entry
                  WHERE institution_id = NEW.institution_id AND transaction_id = NEW.reverses_transaction_id
                 EXCEPT ALL
                 SELECT account_id, student_account_id, side, amount, currency FROM ledger_entry
                  WHERE institution_id = NEW.institution_id AND transaction_id = NEW.id)) THEN
            RAISE EXCEPTION 'a reversal is the exact mirror of its original' USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION ledger_append_only_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ledger_account_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ledger_student_account_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION ledger_transaction_before_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION ledger_entry_before_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION ledger_transaction_balance_check() FROM PUBLIC;

-- INV-04, for every role: a row trigger refuses UPDATE and DELETE and a statement trigger refuses TRUNCATE.
CREATE TRIGGER ledger_transaction_append_only_trg
    BEFORE UPDATE OR DELETE ON ledger_transaction
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only_guard();
CREATE TRIGGER ledger_transaction_truncate_trg
    BEFORE TRUNCATE ON ledger_transaction
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only_guard();
CREATE TRIGGER ledger_entry_append_only_trg
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_append_only_guard();
CREATE TRIGGER ledger_entry_truncate_trg
    BEFORE TRUNCATE ON ledger_entry
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only_guard();
CREATE TRIGGER ledger_account_guard_trg
    BEFORE UPDATE OR DELETE ON ledger_account
    FOR EACH ROW EXECUTE FUNCTION ledger_account_guard();
CREATE TRIGGER ledger_account_truncate_trg
    BEFORE TRUNCATE ON ledger_account
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only_guard();
CREATE TRIGGER ledger_student_account_guard_trg
    BEFORE UPDATE OR DELETE ON ledger_student_account
    FOR EACH ROW EXECUTE FUNCTION ledger_student_account_guard();
CREATE TRIGGER ledger_student_account_truncate_trg
    BEFORE TRUNCATE ON ledger_student_account
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_append_only_guard();

CREATE TRIGGER ledger_transaction_before_insert_trg
    BEFORE INSERT ON ledger_transaction
    FOR EACH ROW EXECUTE FUNCTION ledger_transaction_before_insert();
CREATE TRIGGER ledger_entry_before_insert_trg
    BEFORE INSERT ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_entry_before_insert();
CREATE CONSTRAINT TRIGGER ledger_transaction_balance_trg
    AFTER INSERT ON ledger_transaction
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_transaction_balance_check();

-- ---------------------------------------------------------------------------------------------------------------------
-- Row-level security and privileges
-- ---------------------------------------------------------------------------------------------------------------------

ALTER TABLE ledger_account         ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger_account         FORCE  ROW LEVEL SECURITY;
ALTER TABLE ledger_student_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger_student_account FORCE  ROW LEVEL SECURITY;
ALTER TABLE ledger_transaction     ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger_transaction     FORCE  ROW LEVEL SECURITY;
ALTER TABLE ledger_entry           ENABLE ROW LEVEL SECURITY;
ALTER TABLE ledger_entry           FORCE  ROW LEVEL SECURITY;

CREATE POLICY ledger_account_institution_isolation ON ledger_account
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY ledger_student_account_institution_isolation ON ledger_student_account
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY ledger_transaction_institution_isolation ON ledger_transaction
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY ledger_entry_institution_isolation ON ledger_entry
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON ledger_account         FROM PUBLIC;
REVOKE ALL ON ledger_student_account FROM PUBLIC;
REVOKE ALL ON ledger_transaction     FROM PUBLIC;
REVOKE ALL ON ledger_entry           FROM PUBLIC;

-- No DELETE and no TRUNCATE for anybody; no UPDATE on a transaction or an entry. UPDATE (status) of a sub-account is the
-- privilege SELECT ... FOR UPDATE needs; the trigger refuses any other change. An account of the chart changes only its
-- name, its active flag and its version.
GRANT SELECT, INSERT ON ledger_transaction     TO confia_admin_app;
GRANT SELECT, INSERT ON ledger_entry           TO confia_admin_app;
GRANT SELECT, INSERT ON ledger_student_account TO confia_admin_app;
GRANT UPDATE (status) ON ledger_student_account TO confia_admin_app;
GRANT SELECT, INSERT ON ledger_account         TO confia_admin_app;
GRANT UPDATE (name, is_active, version, updated_at) ON ledger_account TO confia_admin_app;
GRANT SELECT ON ledger_account         TO confia_readonly;
GRANT SELECT ON ledger_student_account TO confia_readonly;
GRANT SELECT ON ledger_transaction     TO confia_readonly;
GRANT SELECT ON ledger_entry           TO confia_readonly;
-- confia_portal_app: no grant until the portal reads balances (F8). confia_owner: schema owner, without BYPASSRLS, and
-- bound by every trigger above.

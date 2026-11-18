-- Thirty-third migration (F3, cut 3b; docs/cortes/charges-void-and-integrity.md). Adds to the charges module the void of a
-- charge and the record of the nightly integrity check. A charge is never edited: "voided" is DERIVED from an insert-only
-- table whose row proves that the exact mirror of the charge's ledger transaction was posted in this very database transaction.
--
-- THE DATABASE REFUSES, to every role including the owner:
--   * an UPDATE, a DELETE or a TRUNCATE of a void, of an integrity run or of one of its discrepancies;
--   * a second void of the same charge, or a second use of the same request key (the key is permanent);
--   * a void without its ledger reversal: of kind REVERSAL, from the module charges, that reverses the CHARGE transaction of
--     that charge and was posted by this database transaction, with a credit to the student sub-account equal to the total;
--   * a void with a reason shorter than 20 or longer than 500 characters.
-- The reversal itself (balanced, an exact mirror, once) is guaranteed by the ledger (V30). A void does not remove the charge from
-- the overlap constraint of V32: a voided charge is NOT accrued again (owner decision 4 of docs/cortes/charges-accrual.md).
--
-- Trigger functions are SECURITY INVOKER with a fixed search_path and no PUBLIC execute. The institution comes from the
-- session, never from a request: forced row-level security keeps one institution from reading or writing another.

-- ---------------------------------------------------------------------------------------------------------------------
-- Voids of charges: INSERT-ONLY
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE charges_charge_void (
    institution_id          UUID         NOT NULL,
    id                      UUID         NOT NULL,
    charge_id               UUID         NOT NULL,
    reversal_transaction_id UUID         NOT NULL,
    reason                  TEXT         NOT NULL,
    -- The Idempotency-Key of the request, PERMANENT, with the hash of what it asked.
    request_key             VARCHAR(128) NOT NULL,
    request_hash            CHAR(64)     NOT NULL,
    voided_by               UUID         NOT NULL,
    voided_at               TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT charges_charge_void_pk        PRIMARY KEY (institution_id, id),
    CONSTRAINT charges_charge_void_charge_fk FOREIGN KEY (institution_id, charge_id)
        REFERENCES charges_charge (institution_id, id),
    CONSTRAINT charges_charge_void_reversal_fk FOREIGN KEY (institution_id, reversal_transaction_id)
        REFERENCES ledger_transaction (institution_id, id),
    -- A charge is voided at most once, and a reversal belongs to one void.
    CONSTRAINT charges_charge_void_charge_uq   UNIQUE (institution_id, charge_id),
    CONSTRAINT charges_charge_void_reversal_uq UNIQUE (institution_id, reversal_transaction_id),
    CONSTRAINT charges_charge_void_key_uq      UNIQUE (institution_id, request_key),
    CONSTRAINT charges_charge_void_reason_chk  CHECK (char_length(btrim(reason)) BETWEEN 20 AND 500
        AND char_length(reason) <= 500),
    CONSTRAINT charges_charge_void_key_chk     CHECK (request_key ~ '^[!-~]{1,128}$'),
    CONSTRAINT charges_charge_void_hash_chk    CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

-- ---------------------------------------------------------------------------------------------------------------------
-- Integrity runs: the result of each nightly check. INSERT-ONLY.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE charges_integrity_run (
    institution_id    UUID        NOT NULL,
    id                UUID        NOT NULL,
    started_at        TIMESTAMPTZ NOT NULL,
    finished_at       TIMESTAMPTZ NOT NULL,
    status            TEXT        NOT NULL,
    charges_checked   INTEGER     NOT NULL,
    postings_checked  INTEGER     NOT NULL,
    discrepancy_count INTEGER     NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT charges_integrity_run_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT charges_integrity_run_status_chk CHECK (status IN ('OK', 'DISCREPANCIES')),
    CONSTRAINT charges_integrity_run_counts_chk CHECK (charges_checked >= 0 AND postings_checked >= 0
        AND discrepancy_count >= 0),
    CONSTRAINT charges_integrity_run_shape_chk CHECK ((status = 'OK') = (discrepancy_count = 0)),
    CONSTRAINT charges_integrity_run_time_chk CHECK (finished_at >= started_at)
);

CREATE INDEX charges_integrity_run_finished_idx ON charges_integrity_run (institution_id, finished_at DESC);

CREATE TABLE charges_integrity_discrepancy (
    institution_id        UUID          NOT NULL,
    id                    UUID          NOT NULL,
    run_id                UUID          NOT NULL,
    kind                  TEXT          NOT NULL,
    charge_id             UUID,
    ledger_transaction_id UUID,
    currency              CHAR(3),
    expected_amount       NUMERIC(14,4),
    actual_amount         NUMERIC(14,4),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT charges_integrity_discrepancy_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT charges_integrity_discrepancy_run_fk FOREIGN KEY (institution_id, run_id)
        REFERENCES charges_integrity_run (institution_id, id),
    CONSTRAINT charges_integrity_discrepancy_charge_fk FOREIGN KEY (institution_id, charge_id)
        REFERENCES charges_charge (institution_id, id),
    CONSTRAINT charges_integrity_discrepancy_ledger_fk FOREIGN KEY (institution_id, ledger_transaction_id)
        REFERENCES ledger_transaction (institution_id, id),
    CONSTRAINT charges_integrity_discrepancy_kind_chk CHECK (kind IN ('UNBALANCED_TRANSACTION',
        'CHARGE_POSTING_MISSING', 'CHARGE_POSTING_DUPLICATE', 'CHARGE_TOTAL_MISMATCH', 'VOID_REVERSAL_MISSING',
        'VOID_REVERSAL_DUPLICATE', 'REVERSAL_WITHOUT_VOID', 'ORPHAN_POSTING', 'RECEIVABLE_MISMATCH')),
    CONSTRAINT charges_integrity_discrepancy_currency_chk CHECK (currency IS NULL OR currency IN ('HNL', 'USD')),
    -- An amount always travels with its currency.
    CONSTRAINT charges_integrity_discrepancy_amount_chk CHECK (
        currency IS NOT NULL OR (expected_amount IS NULL AND actual_amount IS NULL)),
    CONSTRAINT charges_integrity_discrepancy_subject_chk CHECK (
        charge_id IS NOT NULL OR ledger_transaction_id IS NOT NULL OR kind = 'RECEIVABLE_MISMATCH')
);

CREATE INDEX charges_integrity_discrepancy_run_idx ON charges_integrity_discrepancy (institution_id, run_id);
CREATE INDEX charges_integrity_discrepancy_charge_idx ON charges_integrity_discrepancy (institution_id, charge_id)
    WHERE charge_id IS NOT NULL;
CREATE INDEX charges_integrity_discrepancy_ledger_idx ON charges_integrity_discrepancy (institution_id, ledger_transaction_id)
    WHERE ledger_transaction_id IS NOT NULL;

-- The nightly check reads the postings of a source module and the void looks a charge's reversal up: both go by source.
CREATE INDEX ledger_transaction_source_idx ON ledger_transaction (institution_id, source_module, source_ref_id);

-- ---------------------------------------------------------------------------------------------------------------------
-- Trigger functions
-- ---------------------------------------------------------------------------------------------------------------------

-- What a void must prove when it is inserted. The reversal already exists in this database transaction (the use case posts
-- it first and inserts the void second).
CREATE FUNCTION charges_charge_void_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    original      charges_charge%ROWTYPE;
    reversal      ledger_transaction%ROWTYPE;
    credit_count  integer;
    credit_total  numeric;
    credit_ccy    text;
BEGIN
    SELECT * INTO original FROM charges_charge
        WHERE institution_id = NEW.institution_id AND id = NEW.charge_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'a void names a charge of the institution' USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO reversal FROM ledger_transaction
        WHERE institution_id = NEW.institution_id AND id = NEW.reversal_transaction_id;
    IF NOT FOUND
            OR reversal.kind <> 'REVERSAL'
            OR reversal.source_module <> 'charges'
            OR reversal.source_ref_id IS DISTINCT FROM original.id
            OR reversal.reverses_transaction_id IS DISTINCT FROM original.ledger_transaction_id
            OR reversal.posting_xid <> pg_current_xact_id()::text::bigint THEN
        RAISE EXCEPTION 'a void has the REVERSAL of the charge transaction that this database transaction posted for it'
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT count(*), COALESCE(sum(e.amount), 0), min(e.currency)
      INTO credit_count, credit_total, credit_ccy
      FROM ledger_entry e
      JOIN ledger_student_account sa ON sa.institution_id = e.institution_id AND sa.id = e.student_account_id
     WHERE e.institution_id = NEW.institution_id AND e.transaction_id = NEW.reversal_transaction_id
       AND e.side = 'CREDIT' AND sa.student_id = original.student_id;
    IF credit_count <> 1 OR credit_total <> original.total_amount OR credit_ccy <> original.total_currency THEN
        RAISE EXCEPTION 'the credit of the student sub-account of a void is the total of the charge, in its currency'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION charges_charge_void_before_insert() FROM PUBLIC;

CREATE TRIGGER charges_charge_void_before_insert_trg
    BEFORE INSERT ON charges_charge_void
    FOR EACH ROW EXECUTE FUNCTION charges_charge_void_before_insert();
CREATE TRIGGER charges_charge_void_append_only_trg
    BEFORE UPDATE OR DELETE ON charges_charge_void
    FOR EACH ROW EXECUTE FUNCTION charges_append_only_guard();
CREATE TRIGGER charges_charge_void_truncate_trg
    BEFORE TRUNCATE ON charges_charge_void
    FOR EACH STATEMENT EXECUTE FUNCTION charges_append_only_guard();
CREATE TRIGGER charges_integrity_run_append_only_trg
    BEFORE UPDATE OR DELETE ON charges_integrity_run
    FOR EACH ROW EXECUTE FUNCTION charges_append_only_guard();
CREATE TRIGGER charges_integrity_run_truncate_trg
    BEFORE TRUNCATE ON charges_integrity_run
    FOR EACH STATEMENT EXECUTE FUNCTION charges_append_only_guard();
CREATE TRIGGER charges_integrity_discrepancy_append_only_trg
    BEFORE UPDATE OR DELETE ON charges_integrity_discrepancy
    FOR EACH ROW EXECUTE FUNCTION charges_append_only_guard();
CREATE TRIGGER charges_integrity_discrepancy_truncate_trg
    BEFORE TRUNCATE ON charges_integrity_discrepancy
    FOR EACH STATEMENT EXECUTE FUNCTION charges_append_only_guard();

-- ---------------------------------------------------------------------------------------------------------------------
-- Row-level security and privileges
-- ---------------------------------------------------------------------------------------------------------------------
ALTER TABLE charges_charge_void            ENABLE ROW LEVEL SECURITY;
ALTER TABLE charges_charge_void            FORCE  ROW LEVEL SECURITY;
ALTER TABLE charges_integrity_run          ENABLE ROW LEVEL SECURITY;
ALTER TABLE charges_integrity_run          FORCE  ROW LEVEL SECURITY;
ALTER TABLE charges_integrity_discrepancy  ENABLE ROW LEVEL SECURITY;
ALTER TABLE charges_integrity_discrepancy  FORCE  ROW LEVEL SECURITY;

CREATE POLICY charges_charge_void_institution_isolation ON charges_charge_void
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY charges_integrity_run_institution_isolation ON charges_integrity_run
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY charges_integrity_discrepancy_institution_isolation ON charges_integrity_discrepancy
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON charges_charge_void           FROM PUBLIC;
REVOKE ALL ON charges_integrity_run         FROM PUBLIC;
REVOKE ALL ON charges_integrity_discrepancy FROM PUBLIC;

GRANT SELECT, INSERT ON charges_charge_void           TO confia_admin_app;
GRANT SELECT, INSERT ON charges_integrity_run         TO confia_admin_app;
GRANT SELECT, INSERT ON charges_integrity_discrepancy TO confia_admin_app;
GRANT SELECT ON charges_charge_void           TO confia_readonly;
GRANT SELECT ON charges_integrity_run         TO confia_readonly;
GRANT SELECT ON charges_integrity_discrepancy TO confia_readonly;
-- confia_portal_app: no grant, staff data. confia_owner: schema owner, without BYPASSRLS, and bound by every trigger above.

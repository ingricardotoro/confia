-- Thirty-second migration (F3, cut 3; docs/cortes/charges-accrual.md). Creates the accrual engine of the charges module:
-- the charge (one per student, concept and period, INSERT-ONLY), the accrual run with its state machine, the items of a run
-- and the map of posting accounts that is DATA, not code. A charge is born in the same database transaction as its ledger
-- transaction, and the database proves it was.
--
-- THE DATABASE REFUSES, to every role including the owner:
--   * an UPDATE, a DELETE or a TRUNCATE of a charge or of an item of a run (INV-47);
--   * two automatic charges of the same student and concept whose periods overlap, from any plan and any frequency
--     (INV-14, strengthened as INV-43: an EXCLUDE over the date range, not over a label);
--   * an automatic charge that does not reference the price that determined it, whose validity does not cover the pricing
--     date or whose amount is not the base (INV-13);
--   * a charge without its ledger transaction: of kind CHARGE, from the module charges, whose source reference is the
--     charge, created in this very database transaction, with ONE debit to the student sub-account for the total in the
--     same currency (INV-44);
--   * a taxable charge without a tax rate in force at the pricing date or whose tax is not the base (minus discount) times
--     the rate rounded ONCE to the minor unit; a non-taxable one with tax (INV-45). Never 0 % for a missing rate;
--   * the closing of a price or tax validity before the pricing date of a charge that references it (INV-46);
--   * a second non-terminal run of the same plan and period, an exit from a terminal state or a transition that is not in
--     the state machine (INV-48).
-- Amounts are NUMERIC(14,4) with a currency next to each one, in the minor unit of the currency; a charge has no state
-- column: paid and overdue are derived from allocations (F4) and voided from an insert-only table (cut 3b). Discount is zero
-- in this cut (scholarships and discounts are deferred) but its column and its rules exist so that they never change shape.
--
-- Trigger functions are SECURITY INVOKER with a fixed search_path and no PUBLIC execute. The institution comes from the
-- session, never from a request: forced row-level security keeps one institution from reading or writing another.

-- ---------------------------------------------------------------------------------------------------------------------
-- Posting accounts: which account of the chart each purpose uses. DATA, configured per institution.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE charges_posting_account (
    institution_id    UUID        NOT NULL,
    id                UUID        NOT NULL,
    purpose           TEXT        NOT NULL,
    concept_category  TEXT,
    ledger_account_id UUID        NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT charges_posting_account_pk      PRIMARY KEY (institution_id, id),
    CONSTRAINT charges_posting_account_account_fk FOREIGN KEY (institution_id, ledger_account_id)
        REFERENCES ledger_account (institution_id, id),
    CONSTRAINT charges_posting_account_purpose_chk CHECK (purpose IN ('RECEIVABLE', 'REVENUE', 'TAX_PAYABLE')),
    CONSTRAINT charges_posting_account_category_chk CHECK (
        concept_category IS NULL OR (purpose = 'REVENUE'
            AND concept_category IN ('TUITION', 'ENROLLMENT', 'TRANSPORT', 'MATERIAL', 'FINE', 'OTHER'))),
    CONSTRAINT charges_posting_account_version_chk CHECK (version >= 0)
);

-- One account per purpose and category; a null category (the default account of the revenue) counts as one value.
CREATE UNIQUE INDEX charges_posting_account_uq
    ON charges_posting_account (institution_id, purpose, COALESCE(concept_category, ''));
CREATE INDEX charges_posting_account_account_idx ON charges_posting_account (institution_id, ledger_account_id);

-- ---------------------------------------------------------------------------------------------------------------------
-- Accrual runs
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE charges_accrual_run (
    institution_id    UUID         NOT NULL,
    id                UUID         NOT NULL,
    plan_id           UUID         NOT NULL,
    period_start      DATE         NOT NULL,
    status            TEXT         NOT NULL DEFAULT 'QUEUED',
    -- The Idempotency-Key of the request, PERMANENT (unlike the 24-hour key of the HTTP edge), with the hash of what it asked.
    request_key       VARCHAR(128) NOT NULL,
    request_hash      CHAR(64)     NOT NULL,
    preview_digest    CHAR(64),
    requested_by      UUID         NOT NULL,
    queued_at         TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    students_evaluated INTEGER     NOT NULL DEFAULT 0,
    charges_created   INTEGER      NOT NULL DEFAULT 0,
    charges_existing  INTEGER      NOT NULL DEFAULT 0,
    charges_failed    INTEGER      NOT NULL DEFAULT 0,
    failure_code      VARCHAR(60),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT charges_accrual_run_pk      PRIMARY KEY (institution_id, id),
    CONSTRAINT charges_accrual_run_plan_fk FOREIGN KEY (institution_id, plan_id)
        REFERENCES charges_plan (institution_id, id),
    CONSTRAINT charges_accrual_run_status_chk CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED',
        'COMPLETED_WITH_ERRORS', 'REJECTED', 'FAILED')),
    CONSTRAINT charges_accrual_run_period_chk CHECK (EXTRACT(DAY FROM period_start) = 1
        AND period_start BETWEEN date '2000-01-01' AND date '2100-12-31'),
    CONSTRAINT charges_accrual_run_key_chk CHECK (request_key ~ '^[!-~]{1,128}$'),
    CONSTRAINT charges_accrual_run_hash_chk CHECK (request_hash ~ '^[0-9a-f]{64}$'
        AND (preview_digest IS NULL OR preview_digest ~ '^[0-9a-f]{64}$')),
    CONSTRAINT charges_accrual_run_counters_chk CHECK (students_evaluated >= 0 AND charges_created >= 0
        AND charges_existing >= 0 AND charges_failed >= 0),
    CONSTRAINT charges_accrual_run_failure_chk CHECK (failure_code IS NULL OR failure_code ~ '^[a-z][a-z0-9-]{0,59}$')
);

-- INV-48: one non-terminal run per plan and period, also under concurrency. Named _uq for the adapter.
CREATE UNIQUE INDEX charges_accrual_run_active_uq ON charges_accrual_run (institution_id, plan_id, period_start)
    WHERE status IN ('QUEUED', 'RUNNING');
-- The key of the request never repeats in an institution: a second request with it finds the first.
CREATE UNIQUE INDEX charges_accrual_run_key_uq ON charges_accrual_run (institution_id, request_key);
CREATE INDEX charges_accrual_run_plan_idx ON charges_accrual_run (institution_id, plan_id, period_start, queued_at DESC);

-- ---------------------------------------------------------------------------------------------------------------------
-- Charges: INSERT-ONLY
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE charges_charge (
    institution_id        UUID          NOT NULL,
    id                    UUID          NOT NULL,
    student_id            UUID          NOT NULL,
    enrollment_id         UUID,
    payment_concept_id    UUID          NOT NULL,
    -- Copy of the category of the concept at accrual time: a later edit of the concept never changes a charge.
    concept_category      TEXT          NOT NULL,
    plan_id               UUID,
    plan_item_id          UUID,
    accrual_run_id        UUID,
    origin                TEXT          NOT NULL,
    period_start          DATE          NOT NULL,
    period_end            DATE          NOT NULL,
    period_label          VARCHAR(7)    NOT NULL,
    pricing_date          DATE          NOT NULL,
    due_on                DATE          NOT NULL,
    price_list_item_id    UUID,
    base_amount           NUMERIC(14,4) NOT NULL,
    base_currency         CHAR(3)       NOT NULL,
    discount_amount       NUMERIC(14,4) NOT NULL,
    discount_currency     CHAR(3)       NOT NULL,
    tax_amount            NUMERIC(14,4) NOT NULL,
    tax_currency          CHAR(3)       NOT NULL,
    total_amount          NUMERIC(14,4) NOT NULL,
    total_currency        CHAR(3)       NOT NULL,
    is_taxable            BOOLEAN       NOT NULL,
    tax_rate_id           UUID,
    tax_rate_period_id    UUID,
    tax_rate_percent      NUMERIC(7,4),
    ledger_transaction_id UUID          NOT NULL,
    created_by            UUID          NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT charges_charge_pk           PRIMARY KEY (institution_id, id),
    CONSTRAINT charges_charge_student_fk   FOREIGN KEY (institution_id, student_id)
        REFERENCES students_student (institution_id, id),
    CONSTRAINT charges_charge_enrollment_fk FOREIGN KEY (institution_id, enrollment_id)
        REFERENCES students_enrollment (institution_id, id),
    CONSTRAINT charges_charge_concept_fk   FOREIGN KEY (institution_id, payment_concept_id)
        REFERENCES catalog_payment_concept (institution_id, id),
    CONSTRAINT charges_charge_plan_fk      FOREIGN KEY (institution_id, plan_id)
        REFERENCES charges_plan (institution_id, id),
    CONSTRAINT charges_charge_plan_item_fk FOREIGN KEY (institution_id, plan_item_id)
        REFERENCES charges_plan_item (institution_id, id),
    CONSTRAINT charges_charge_run_fk       FOREIGN KEY (institution_id, accrual_run_id)
        REFERENCES charges_accrual_run (institution_id, id),
    CONSTRAINT charges_charge_price_fk     FOREIGN KEY (institution_id, price_list_item_id)
        REFERENCES catalog_price_list_item (institution_id, id),
    CONSTRAINT charges_charge_tax_rate_fk  FOREIGN KEY (institution_id, tax_rate_id)
        REFERENCES catalog_tax_rate (institution_id, id),
    CONSTRAINT charges_charge_tax_period_fk FOREIGN KEY (institution_id, tax_rate_period_id)
        REFERENCES catalog_tax_rate_period (institution_id, id),
    CONSTRAINT charges_charge_ledger_fk    FOREIGN KEY (institution_id, ledger_transaction_id)
        REFERENCES ledger_transaction (institution_id, id),
    -- INV-44: one ledger transaction per charge, and a charge per ledger transaction.
    CONSTRAINT charges_charge_ledger_uq    UNIQUE (institution_id, ledger_transaction_id),
    CONSTRAINT charges_charge_origin_chk   CHECK (origin IN ('AUTOMATIC', 'MANUAL')),
    CONSTRAINT charges_charge_category_chk CHECK (concept_category IN ('TUITION', 'ENROLLMENT', 'TRANSPORT',
        'MATERIAL', 'FINE', 'OTHER')),
    -- INV-13: an automatic charge says which plan, item, run and price produced it.
    CONSTRAINT charges_charge_automatic_chk CHECK (origin = 'MANUAL' OR (plan_id IS NOT NULL
        AND plan_item_id IS NOT NULL AND accrual_run_id IS NOT NULL AND enrollment_id IS NOT NULL
        AND price_list_item_id IS NOT NULL)),
    CONSTRAINT charges_charge_period_chk   CHECK (EXTRACT(DAY FROM period_start) = 1 AND period_end >= period_start
        AND period_start BETWEEN date '2000-01-01' AND date '2100-12-31'
        AND period_end BETWEEN date '2000-01-01' AND date '2100-12-31'),
    CONSTRAINT charges_charge_label_chk    CHECK (period_label = to_char(period_start, 'YYYY-MM')),
    CONSTRAINT charges_charge_pricing_date_chk CHECK (pricing_date = period_start),
    CONSTRAINT charges_charge_due_chk      CHECK (due_on >= period_start AND due_on <= period_end + 28),
    -- INV-08, INV-09, INV-10: non-negative, total = base - discount + tax, discount <= base, one currency, minor unit.
    CONSTRAINT charges_charge_currency_chk CHECK (base_currency IN ('HNL', 'USD')
        AND discount_currency = base_currency AND tax_currency = base_currency AND total_currency = base_currency),
    CONSTRAINT charges_charge_amounts_chk  CHECK (base_amount > 0 AND discount_amount >= 0 AND tax_amount >= 0
        AND discount_amount <= base_amount AND total_amount > 0
        AND total_amount = base_amount - discount_amount + tax_amount),
    CONSTRAINT charges_charge_minor_unit_chk CHECK (base_amount = round(base_amount, 2)
        AND discount_amount = round(discount_amount, 2) AND tax_amount = round(tax_amount, 2)
        AND total_amount = round(total_amount, 2)),
    -- INV-45: taxable -> rate, period and percent, and tax = round((base - discount) * percent / 100, 2), ONE rounding;
    -- not taxable -> no rate and no tax. round(numeric, 2) rounds the midpoint away from zero: HALF_UP for positive amounts.
    CONSTRAINT charges_charge_tax_chk CHECK (
        (is_taxable AND tax_rate_id IS NOT NULL AND tax_rate_period_id IS NOT NULL AND tax_rate_percent IS NOT NULL
            AND tax_rate_percent > 0 AND tax_rate_percent <= 100
            AND tax_amount = round((base_amount - discount_amount) * tax_rate_percent / 100, 2))
        OR (NOT is_taxable AND tax_rate_id IS NULL AND tax_rate_period_id IS NULL AND tax_rate_percent IS NULL
            AND tax_amount = 0)),
    -- INV-14 / INV-43: no two automatic charges of a student and a concept with overlapping periods, whatever the plan.
    CONSTRAINT charges_charge_no_overlap_excl EXCLUDE USING gist (
        institution_id WITH =, student_id WITH =, payment_concept_id WITH =,
        daterange(period_start, period_end, '[]') WITH &&) WHERE (origin = 'AUTOMATIC')
);

CREATE INDEX charges_charge_student_idx ON charges_charge (institution_id, student_id, period_start);
CREATE INDEX charges_charge_run_idx     ON charges_charge (institution_id, accrual_run_id) WHERE accrual_run_id IS NOT NULL;
CREATE INDEX charges_charge_price_idx   ON charges_charge (institution_id, price_list_item_id) WHERE price_list_item_id IS NOT NULL;
CREATE INDEX charges_charge_tax_period_idx ON charges_charge (institution_id, tax_rate_period_id) WHERE tax_rate_period_id IS NOT NULL;
CREATE INDEX charges_charge_concept_idx ON charges_charge (institution_id, payment_concept_id);
CREATE INDEX charges_charge_plan_idx    ON charges_charge (institution_id, plan_id) WHERE plan_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------------------------------
-- The items of a run: what happened to each student and concept. INSERT-ONLY.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE charges_accrual_run_item (
    institution_id     UUID        NOT NULL,
    id                 UUID        NOT NULL,
    run_id             UUID        NOT NULL,
    student_id         UUID        NOT NULL,
    payment_concept_id UUID        NOT NULL,
    outcome            TEXT        NOT NULL,
    error_code         VARCHAR(40),
    charge_id          UUID,
    existing_charge_id UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT charges_accrual_run_item_pk     PRIMARY KEY (institution_id, id),
    CONSTRAINT charges_accrual_run_item_run_fk FOREIGN KEY (institution_id, run_id)
        REFERENCES charges_accrual_run (institution_id, id),
    CONSTRAINT charges_accrual_run_item_student_fk FOREIGN KEY (institution_id, student_id)
        REFERENCES students_student (institution_id, id),
    CONSTRAINT charges_accrual_run_item_concept_fk FOREIGN KEY (institution_id, payment_concept_id)
        REFERENCES catalog_payment_concept (institution_id, id),
    CONSTRAINT charges_accrual_run_item_charge_fk FOREIGN KEY (institution_id, charge_id)
        REFERENCES charges_charge (institution_id, id),
    CONSTRAINT charges_accrual_run_item_existing_fk FOREIGN KEY (institution_id, existing_charge_id)
        REFERENCES charges_charge (institution_id, id),
    -- A run touches each student and concept once: the resumption of a crashed run skips what is already here.
    CONSTRAINT charges_accrual_run_item_uq UNIQUE (institution_id, run_id, student_id, payment_concept_id),
    CONSTRAINT charges_accrual_run_item_outcome_chk CHECK (outcome IN ('CREATED', 'ALREADY_ACCRUED',
        'ALREADY_ACCRUED_OTHER_PLAN', 'ERROR')),
    CONSTRAINT charges_accrual_run_item_shape_chk CHECK (
        (outcome = 'CREATED' AND charge_id IS NOT NULL AND existing_charge_id IS NULL AND error_code IS NULL)
        OR (outcome IN ('ALREADY_ACCRUED', 'ALREADY_ACCRUED_OTHER_PLAN') AND charge_id IS NULL
            AND existing_charge_id IS NOT NULL AND error_code IS NULL)
        OR (outcome = 'ERROR' AND charge_id IS NULL AND error_code IS NOT NULL)),
    CONSTRAINT charges_accrual_run_item_code_chk CHECK (error_code IS NULL OR error_code ~ '^[A-Z][A-Z0-9_]{0,39}$')
);

CREATE INDEX charges_accrual_run_item_run_idx ON charges_accrual_run_item (institution_id, run_id, outcome);
CREATE INDEX charges_accrual_run_item_charge_idx ON charges_accrual_run_item (institution_id, charge_id)
    WHERE charge_id IS NOT NULL;
CREATE INDEX charges_accrual_run_item_existing_idx ON charges_accrual_run_item (institution_id, existing_charge_id)
    WHERE existing_charge_id IS NOT NULL;
CREATE INDEX charges_accrual_run_item_student_idx ON charges_accrual_run_item (institution_id, student_id);
CREATE INDEX charges_accrual_run_item_concept_idx ON charges_accrual_run_item (institution_id, payment_concept_id);

-- ---------------------------------------------------------------------------------------------------------------------
-- Trigger functions
-- ---------------------------------------------------------------------------------------------------------------------

-- INV-47. For the charges and the items of the runs, and for the TRUNCATE of every table of the module.
CREATE FUNCTION charges_append_only_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    RAISE EXCEPTION 'charges are append-only: % on % is refused', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

-- INV-13, INV-44, INV-45: what a charge must prove when it is inserted. The ledger transaction already exists in this
-- database transaction (the engine posts first and inserts the charge second).
CREATE FUNCTION charges_charge_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    price         catalog_price_list_item%ROWTYPE;
    tax_period    catalog_tax_rate_period%ROWTYPE;
    posting       ledger_transaction%ROWTYPE;
    debit_count   integer;
    debit_total   numeric;
    debit_ccy     text;
    all_debits    integer;
BEGIN
    IF NEW.origin = 'AUTOMATIC' THEN
        SELECT * INTO price FROM catalog_price_list_item
            WHERE institution_id = NEW.institution_id AND id = NEW.price_list_item_id;
        IF NOT FOUND
                OR price.payment_concept_id <> NEW.payment_concept_id
                OR price.amount <> NEW.base_amount
                OR price.currency <> NEW.base_currency
                OR price.valid_from > NEW.pricing_date
                OR (price.valid_to IS NOT NULL AND price.valid_to < NEW.pricing_date) THEN
            RAISE EXCEPTION 'an automatic charge references the price in force at its pricing date, which is its base'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    IF NEW.is_taxable THEN
        SELECT * INTO tax_period FROM catalog_tax_rate_period
            WHERE institution_id = NEW.institution_id AND id = NEW.tax_rate_period_id;
        IF NOT FOUND
                OR tax_period.tax_rate_id <> NEW.tax_rate_id
                OR tax_period.rate_percent <> NEW.tax_rate_percent
                OR tax_period.valid_from > NEW.pricing_date
                OR (tax_period.valid_to IS NOT NULL AND tax_period.valid_to < NEW.pricing_date) THEN
            RAISE EXCEPTION 'a taxable charge references the tax rate in force at its pricing date'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    SELECT * INTO posting FROM ledger_transaction
        WHERE institution_id = NEW.institution_id AND id = NEW.ledger_transaction_id;
    IF NOT FOUND
            OR posting.kind <> 'CHARGE'
            OR posting.source_module <> 'charges'
            OR posting.source_ref_id IS DISTINCT FROM NEW.id
            OR posting.posting_xid <> pg_current_xact_id()::text::bigint
            OR (NEW.origin = 'AUTOMATIC' AND posting.effective_on <> NEW.period_start) THEN
        RAISE EXCEPTION 'a charge has the CHARGE transaction that this database transaction posted for it'
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT count(*), COALESCE(sum(e.amount), 0), min(e.currency)
      INTO debit_count, debit_total, debit_ccy
      FROM ledger_entry e
      JOIN ledger_student_account sa ON sa.institution_id = e.institution_id AND sa.id = e.student_account_id
     WHERE e.institution_id = NEW.institution_id AND e.transaction_id = NEW.ledger_transaction_id
       AND e.side = 'DEBIT' AND sa.student_id = NEW.student_id;
    SELECT count(*) INTO all_debits FROM ledger_entry
     WHERE institution_id = NEW.institution_id AND transaction_id = NEW.ledger_transaction_id AND side = 'DEBIT';
    IF debit_count <> 1 OR all_debits <> 1 OR debit_total <> NEW.total_amount OR debit_ccy <> NEW.total_currency THEN
        RAISE EXCEPTION 'the debit of the student sub-account of a charge is its total, in its currency'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- INV-48 and the shape of the life of a run.
CREATE FUNCTION charges_accrual_run_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an accrual run is never deleted' USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'QUEUED' THEN
            RAISE EXCEPTION 'an accrual run is born queued' USING ERRCODE = 'restrict_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.institution_id, NEW.id, NEW.plan_id, NEW.period_start, NEW.request_key, NEW.request_hash,
            NEW.preview_digest, NEW.requested_by, NEW.queued_at, NEW.created_at)
            IS DISTINCT FROM
       (OLD.institution_id, OLD.id, OLD.plan_id, OLD.period_start, OLD.request_key, OLD.request_hash,
            OLD.preview_digest, OLD.requested_by, OLD.queued_at, OLD.created_at) THEN
        RAISE EXCEPTION 'the plan, the period and the request of an accrual run never change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'REJECTED', 'FAILED') THEN
        RAISE EXCEPTION 'a finished accrual run never changes' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NOT (NEW.status = OLD.status
            OR (OLD.status = 'QUEUED' AND NEW.status IN ('RUNNING', 'REJECTED', 'FAILED'))
            OR (OLD.status = 'RUNNING' AND NEW.status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS', 'REJECTED', 'FAILED'))) THEN
        RAISE EXCEPTION 'this transition of an accrual run is not allowed' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION charges_posting_account_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a posting account is never deleted, it is replaced' USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.institution_id, NEW.id, NEW.purpose, NEW.concept_category, NEW.created_at)
            IS DISTINCT FROM
       (OLD.institution_id, OLD.id, OLD.purpose, OLD.concept_category, OLD.created_at) THEN
        RAISE EXCEPTION 'the purpose and the category of a posting account never change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- INV-46: a price or a tax validity that a charge references is not closed before the pricing date of that charge. The
-- engine reads those rows FOR SHARE, so a concurrent closing waits for the charge and then fails here.
CREATE FUNCTION charges_price_validity_close_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.valid_to IS NOT NULL AND EXISTS (
            SELECT 1 FROM charges_charge
             WHERE institution_id = OLD.institution_id AND price_list_item_id = OLD.id
               AND pricing_date > NEW.valid_to) THEN
        RAISE EXCEPTION 'a price that a charge references is not closed before the pricing date of that charge'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION charges_tax_validity_close_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.valid_to IS NOT NULL AND EXISTS (
            SELECT 1 FROM charges_charge
             WHERE institution_id = OLD.institution_id AND tax_rate_period_id = OLD.id
               AND pricing_date > NEW.valid_to) THEN
        RAISE EXCEPTION 'a tax validity that a charge references is not closed before the pricing date of that charge'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION charges_append_only_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION charges_charge_before_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION charges_accrual_run_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION charges_posting_account_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION charges_price_validity_close_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION charges_tax_validity_close_guard() FROM PUBLIC;

CREATE TRIGGER charges_charge_append_only_trg
    BEFORE UPDATE OR DELETE ON charges_charge
    FOR EACH ROW EXECUTE FUNCTION charges_append_only_guard();
CREATE TRIGGER charges_charge_truncate_trg
    BEFORE TRUNCATE ON charges_charge
    FOR EACH STATEMENT EXECUTE FUNCTION charges_append_only_guard();
CREATE TRIGGER charges_charge_before_insert_trg
    BEFORE INSERT ON charges_charge
    FOR EACH ROW EXECUTE FUNCTION charges_charge_before_insert();
CREATE TRIGGER charges_accrual_run_item_append_only_trg
    BEFORE UPDATE OR DELETE ON charges_accrual_run_item
    FOR EACH ROW EXECUTE FUNCTION charges_append_only_guard();
CREATE TRIGGER charges_accrual_run_item_truncate_trg
    BEFORE TRUNCATE ON charges_accrual_run_item
    FOR EACH STATEMENT EXECUTE FUNCTION charges_append_only_guard();
CREATE TRIGGER charges_accrual_run_guard_trg
    BEFORE INSERT OR UPDATE OR DELETE ON charges_accrual_run
    FOR EACH ROW EXECUTE FUNCTION charges_accrual_run_guard();
CREATE TRIGGER charges_accrual_run_truncate_trg
    BEFORE TRUNCATE ON charges_accrual_run
    FOR EACH STATEMENT EXECUTE FUNCTION charges_append_only_guard();
CREATE TRIGGER charges_posting_account_guard_trg
    BEFORE UPDATE OR DELETE ON charges_posting_account
    FOR EACH ROW EXECUTE FUNCTION charges_posting_account_guard();
CREATE TRIGGER charges_posting_account_truncate_trg
    BEFORE TRUNCATE ON charges_posting_account
    FOR EACH STATEMENT EXECUTE FUNCTION charges_append_only_guard();

-- INV-46 lives on the catalog tables but belongs to this module: it protects the charges.
CREATE TRIGGER charges_price_validity_close_trg
    BEFORE UPDATE OF valid_to ON catalog_price_list_item
    FOR EACH ROW EXECUTE FUNCTION charges_price_validity_close_guard();
CREATE TRIGGER charges_tax_validity_close_trg
    BEFORE UPDATE OF valid_to ON catalog_tax_rate_period
    FOR EACH ROW EXECUTE FUNCTION charges_tax_validity_close_guard();

-- ---------------------------------------------------------------------------------------------------------------------
-- Row-level security and privileges
-- ---------------------------------------------------------------------------------------------------------------------
ALTER TABLE charges_posting_account  ENABLE ROW LEVEL SECURITY;
ALTER TABLE charges_posting_account  FORCE  ROW LEVEL SECURITY;
ALTER TABLE charges_accrual_run      ENABLE ROW LEVEL SECURITY;
ALTER TABLE charges_accrual_run      FORCE  ROW LEVEL SECURITY;
ALTER TABLE charges_charge           ENABLE ROW LEVEL SECURITY;
ALTER TABLE charges_charge           FORCE  ROW LEVEL SECURITY;
ALTER TABLE charges_accrual_run_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE charges_accrual_run_item FORCE  ROW LEVEL SECURITY;

CREATE POLICY charges_posting_account_institution_isolation ON charges_posting_account
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY charges_accrual_run_institution_isolation ON charges_accrual_run
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY charges_charge_institution_isolation ON charges_charge
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY charges_accrual_run_item_institution_isolation ON charges_accrual_run_item
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON charges_posting_account  FROM PUBLIC;
REVOKE ALL ON charges_accrual_run      FROM PUBLIC;
REVOKE ALL ON charges_charge           FROM PUBLIC;
REVOKE ALL ON charges_accrual_run_item FROM PUBLIC;

GRANT SELECT, INSERT ON charges_charge           TO confia_admin_app;
GRANT SELECT, INSERT ON charges_accrual_run_item TO confia_admin_app;
GRANT SELECT, INSERT ON charges_accrual_run      TO confia_admin_app;
GRANT UPDATE (status, started_at, finished_at, students_evaluated, charges_created, charges_existing,
    charges_failed, failure_code, updated_at) ON charges_accrual_run TO confia_admin_app;
GRANT SELECT, INSERT ON charges_posting_account  TO confia_admin_app;
GRANT UPDATE (ledger_account_id, version, updated_at) ON charges_posting_account TO confia_admin_app;
GRANT SELECT ON charges_charge           TO confia_readonly;
GRANT SELECT ON charges_accrual_run_item TO confia_readonly;
GRANT SELECT ON charges_accrual_run      TO confia_readonly;
GRANT SELECT ON charges_posting_account  TO confia_readonly;
-- confia_portal_app: no grant, staff data. confia_owner: schema owner, without BYPASSRLS, and bound by every trigger above.

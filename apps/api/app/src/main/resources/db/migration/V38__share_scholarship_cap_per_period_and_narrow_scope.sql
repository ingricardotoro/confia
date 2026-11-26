-- Thirty-eighth migration (F2 deliverables 4, 5 and 7, scholarships cut 1, review fixes of the fourth task; docs/cortes/scholarships-awards.md).
-- Two owner decisions of 2026-11-24 reach the database. V36 and V37 are applied in developer databases, so they are not edited.
--
-- 1. THE CAP PER PERIOD IS SHARED BY THE CHARGES OF THE PERIOD (B1). V37 limited each charge to the cap on its own, so a scholarship of
--    two concepts with a cap of 600.00 could give 600.00 on each. The benefit of all the charges of one award in one period never
--    exceeds the cap: the benefit of a charge is the share or the fixed amount, limited to the base and to what is left of the cap, which
--    is the cap minus the benefit that the non-voided charges of the same award and the same period (same period_start) already carry.
--    A void RELEASES the benefit of the voided charge: it no longer counts, so a charge issued afterwards in that period may take it.
--    The charges already issued never change (insert-only table); the sum is read when the charge is inserted, under the lock of the
--    student account that the ledger posting takes, so two runs of the same student take turns.
--
-- 2. A SCHOLARSHIP WITH APPROVED AWARDS ONLY NARROWS (A1). Once an award of the scholarship was approved (the award may have been
--    revoked since), its cap per period only goes down (never raised, never removed) and its concepts are only removed (never added);
--    widening means another scholarship. V36 already locked the type and the value of the benefit with the same condition. The cap is
--    guarded by the trigger of the scholarship and the concepts by a trigger before the insert of a concept (a scholarship is created
--    before any award exists, so its first concepts are never refused). Both refusals are restrict_violation with a named constraint
--    that the adapter translates into scholarship-scope-locked.

CREATE OR REPLACE FUNCTION scholarships_scholarship_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    decided boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a scholarship is never deleted, it is deactivated' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.institution_id <> OLD.institution_id OR NEW.id <> OLD.id OR NEW.code <> OLD.code
            OR NEW.created_by <> OLD.created_by OR NEW.created_at <> OLD.created_at OR NEW.currency <> OLD.currency THEN
        RAISE EXCEPTION 'the code, the creation and the currency of a scholarship never change' USING ERRCODE = 'restrict_violation';
    END IF;
    SELECT EXISTS (SELECT 1 FROM scholarships_award
                    WHERE institution_id = NEW.institution_id AND scholarship_id = NEW.id AND status IN ('approved', 'revoked'))
      INTO decided;
    IF decided AND (NEW.benefit_type <> OLD.benefit_type OR NEW.percent IS DISTINCT FROM OLD.percent
            OR NEW.fixed_amount IS DISTINCT FROM OLD.fixed_amount) THEN
        RAISE EXCEPTION 'the benefit of a scholarship with approved awards never changes, another scholarship is created'
            USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_scholarship_benefit_locked_chk';
    END IF;
    IF decided AND OLD.period_cap IS NOT NULL AND (NEW.period_cap IS NULL OR NEW.period_cap > OLD.period_cap) THEN
        RAISE EXCEPTION 'the cap of a scholarship with approved awards only goes down, another scholarship is created'
            USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_scholarship_scope_locked_chk';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION scholarships_concept_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM scholarships_award
                WHERE institution_id = NEW.institution_id AND scholarship_id = NEW.scholarship_id
                  AND status IN ('approved', 'revoked')) THEN
        RAISE EXCEPTION 'a scholarship with approved awards covers no new concept, another scholarship is created'
            USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_scholarship_scope_locked_chk';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION scholarships_concept_before_insert() FROM PUBLIC;

CREATE TRIGGER scholarships_concept_before_insert_trg
    BEFORE INSERT ON scholarships_scholarship_concept
    FOR EACH ROW EXECUTE FUNCTION scholarships_concept_before_insert();

-- The charge: the benefit takes into account what the award gave in the period (same text of V37, with the remainder of the cap).
CREATE OR REPLACE FUNCTION charges_charge_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    price         catalog_price_list_item%ROWTYPE;
    tax_period    catalog_tax_rate_period%ROWTYPE;
    posting       ledger_transaction%ROWTYPE;
    award         RECORD;
    expected      numeric;
    debit_count   integer;
    debit_total   numeric;
    debit_ccy     text;
    all_debits    integer;
    all_total     numeric;
    other_debits  integer;
    expected_other integer;
    consumed      numeric;
    remaining     numeric;
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

    -- INV-15, INV-16: the benefit of a charge is the benefit of an award that is approved now, of this student, in force at the
    -- pricing date, of an active scholarship that covers the concept, in the currency of the charge.
    IF NEW.scholarship_award_id IS NOT NULL THEN
        SELECT a.status, a.student_id, a.valid_from, a.valid_to, s.id AS scholarship_id, s.code, s.is_active, s.benefit_type,
               s.percent, s.fixed_amount, s.period_cap, s.currency
          INTO award
          FROM scholarships_award a
          JOIN scholarships_scholarship s ON s.institution_id = a.institution_id AND s.id = a.scholarship_id
         WHERE a.institution_id = NEW.institution_id AND a.id = NEW.scholarship_award_id;
        IF NOT FOUND
                OR NEW.origin <> 'AUTOMATIC'
                OR award.status <> 'approved'
                OR award.student_id <> NEW.student_id
                OR NEW.pricing_date < award.valid_from
                OR NEW.pricing_date > award.valid_to
                OR NOT award.is_active
                OR award.code <> NEW.scholarship_code
                OR award.currency <> NEW.base_currency
                OR NOT EXISTS (SELECT 1 FROM scholarships_scholarship_concept c
                                WHERE c.institution_id = NEW.institution_id AND c.scholarship_id = award.scholarship_id
                                  AND c.payment_concept_id = NEW.payment_concept_id) THEN
            RAISE EXCEPTION 'a charge applies an approved award of the student, in force at its pricing date, that covers its concept'
                USING ERRCODE = 'check_violation';
        END IF;
        IF award.benefit_type = 'PERCENTAGE' THEN
            expected := round(NEW.base_amount * award.percent / 100, 2);
        ELSE
            expected := award.fixed_amount;
        END IF;
        -- The cap per period is shared by every charge of the award in the period: what the award already gave in this period
        -- (the non-voided charges, whatever run issued them) is taken from the cap before this charge takes its share.
        IF award.period_cap IS NULL THEN
            remaining := NEW.base_amount;
        ELSE
            SELECT COALESCE(sum(c.discount_amount), 0) INTO consumed
              FROM charges_charge c
             WHERE c.institution_id = NEW.institution_id AND c.scholarship_award_id = NEW.scholarship_award_id
               AND c.period_start = NEW.period_start AND c.id <> NEW.id
               AND NOT EXISTS (SELECT 1 FROM charges_charge_void v
                                WHERE v.institution_id = c.institution_id AND v.charge_id = c.id);
            remaining := GREATEST(award.period_cap - consumed, 0);
        END IF;
        expected := LEAST(expected, remaining, NEW.base_amount);
        IF NEW.discount_amount <> expected THEN
            RAISE EXCEPTION 'the benefit of a charge is the benefit of its award, limited to what is left of its cap in the period and to the base'
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
    SELECT count(*), COALESCE(sum(amount), 0) INTO all_debits, all_total FROM ledger_entry
     WHERE institution_id = NEW.institution_id AND transaction_id = NEW.ledger_transaction_id AND side = 'DEBIT';
    IF NEW.total_amount > 0 THEN
        IF debit_count <> 1 OR debit_total <> NEW.total_amount OR debit_ccy <> NEW.total_currency THEN
            RAISE EXCEPTION 'the debit of the student sub-account of a charge is its total, in its currency'
                USING ERRCODE = 'check_violation';
        END IF;
    ELSIF debit_count <> 0 THEN
        RAISE EXCEPTION 'a charge with nothing to pay has no debit on the student sub-account'
            USING ERRCODE = 'check_violation';
    END IF;
    -- Every other debit is the benefit: one entry for its amount when there is a benefit, none when there is not.
    other_debits := all_debits - debit_count;
    IF NEW.discount_amount > 0 THEN
        expected_other := 1;
    ELSE
        expected_other := 0;
    END IF;
    IF other_debits <> expected_other OR all_total - debit_total <> NEW.discount_amount THEN
        RAISE EXCEPTION 'the only other debit of a charge is its benefit'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- Fortieth migration (F2 deliverable 6, scholarships cut 2, second task; docs/cortes/scholarships-discounts.md). The accrual engine
-- applies the discounts of V39 together with the scholarship of V36 to the charges it generates from now on, with the accumulation rule
-- of INV-15 (the cascade, or only the greatest benefit when a discount is not stackable). This migration only ADDS to the charges: no
-- existing row of any table is updated or deleted, and every charge that exists (a charge without a benefit, or with the benefit of one
-- scholarship, as V37 and V38 left it) stays valid under the new constraints, because its columns already satisfy them and the new
-- checks run only when a charge is inserted.
--
-- WHAT A CHARGE KEEPS. Besides the gross base, the total benefit (discount_amount since V32), the generated net base, the tax and the
-- total, a charge keeps ONE LINE PER BENEFIT APPLIED (charges_charge_benefit, insert-only, in the order the engine applied them): the
-- source (the scholarship award, or the discount with the assignment that made an agreement discount apply to the student), the code
-- copied at accrual time and the amount. The columns scholarship_award_id and scholarship_code of the charge stay as V37 defined them:
-- they name the scholarship of the charge and are empty when only discounts reduced it.
--
-- THE DATABASE REFUSES, to every role including the owner:
--   * a charge whose benefit is not the sum of its lines, a charge with an award and no scholarship line (or the other way round), and
--     lines that stack when any of the discounts among them is not stackable (deferred constraint trigger on the charge);
--   * a scholarship line that is not the award of the charge or whose amount is not exactly the share or the fixed amount of its
--     scholarship, limited to what is left of its cap per period and to the base (V38 rule; the benefit is always computed on the
--     gross base: it is the first step of the cascade);
--   * a discount line for a discount that is inactive, whose validity does not include the pricing date (both ends), that does not cover
--     the payment concept of the charge, whose code is not the copied one, or whose amount is above its own benefit on the gross base;
--   * a discount line of an agreement discount without an assignment that is approved, belongs to the student of the charge and the
--     discount, and whose validity includes the pricing date; and a discount line of a campaign discount WITH an assignment;
--   * any UPDATE, DELETE or TRUNCATE of a line.
--
-- IN USE. A discount with a charge issued with it is in use too: V39 declared the hook, and here the guard of the discount and the guard
-- of its concepts take it into account (CREATE OR REPLACE), and a view says which discounts are in use for the module that owns them.

CREATE TABLE charges_charge_benefit (
    institution_id         UUID          NOT NULL,
    charge_id              UUID          NOT NULL,
    -- 1 is the first benefit applied: the scholarship when there is one, then the discounts in the order of the cascade.
    position               SMALLINT      NOT NULL,
    source                 TEXT          NOT NULL,
    scholarship_award_id   UUID,
    scholarship_code       VARCHAR(30),
    discount_id            UUID,
    discount_code          VARCHAR(30),
    -- The approved assignment that made an agreement discount apply to the student; empty for a campaign discount.
    discount_assignment_id UUID,
    amount                 NUMERIC(14,4) NOT NULL,
    currency               CHAR(3)       NOT NULL,
    CONSTRAINT charges_charge_benefit_pk PRIMARY KEY (institution_id, charge_id, position),
    CONSTRAINT charges_charge_benefit_charge_fk FOREIGN KEY (institution_id, charge_id)
        REFERENCES charges_charge (institution_id, id),
    CONSTRAINT charges_charge_benefit_award_fk FOREIGN KEY (institution_id, scholarship_award_id)
        REFERENCES scholarships_award (institution_id, id),
    CONSTRAINT charges_charge_benefit_discount_fk FOREIGN KEY (institution_id, discount_id)
        REFERENCES scholarships_discount (institution_id, id),
    CONSTRAINT charges_charge_benefit_assignment_fk FOREIGN KEY (institution_id, discount_assignment_id)
        REFERENCES scholarships_discount_assignment (institution_id, id),
    CONSTRAINT charges_charge_benefit_position_chk CHECK (position >= 1),
    CONSTRAINT charges_charge_benefit_source_chk CHECK (
        (source = 'SCHOLARSHIP' AND scholarship_award_id IS NOT NULL AND scholarship_code IS NOT NULL
            AND discount_id IS NULL AND discount_code IS NULL AND discount_assignment_id IS NULL)
        OR (source = 'DISCOUNT' AND discount_id IS NOT NULL AND discount_code IS NOT NULL
            AND scholarship_award_id IS NULL AND scholarship_code IS NULL)),
    CONSTRAINT charges_charge_benefit_amount_chk CHECK (amount > 0 AND amount = round(amount, 2)),
    CONSTRAINT charges_charge_benefit_currency_chk CHECK (currency = 'HNL')
);

-- A charge has at most one scholarship line and each discount at most once.
CREATE UNIQUE INDEX charges_charge_benefit_scholarship_uq ON charges_charge_benefit (institution_id, charge_id)
    WHERE source = 'SCHOLARSHIP';
CREATE UNIQUE INDEX charges_charge_benefit_discount_uq ON charges_charge_benefit (institution_id, charge_id, discount_id)
    WHERE discount_id IS NOT NULL;
-- The sides of the foreign keys, and the "is this discount in use" question.
CREATE INDEX charges_charge_benefit_award_idx ON charges_charge_benefit (institution_id, scholarship_award_id)
    WHERE scholarship_award_id IS NOT NULL;
CREATE INDEX charges_charge_benefit_discount_idx ON charges_charge_benefit (institution_id, discount_id)
    WHERE discount_id IS NOT NULL;
CREATE INDEX charges_charge_benefit_assignment_idx ON charges_charge_benefit (institution_id, discount_assignment_id)
    WHERE discount_assignment_id IS NOT NULL;

-- The charge no longer assumes that its only benefit is one scholarship: it may carry discounts only, so a benefit without an award is
-- valid. What stays: the award and its code come together, an award means a benefit, and no benefit means no award. Every existing
-- charge satisfies it (no benefit and no award, or a benefit with its award and its code).
ALTER TABLE charges_charge DROP CONSTRAINT charges_charge_benefit_chk;
ALTER TABLE charges_charge ADD CONSTRAINT charges_charge_benefit_chk CHECK (
    (scholarship_award_id IS NULL) = (scholarship_code IS NULL)
    AND (scholarship_award_id IS NULL OR discount_amount > 0));

-- What an award has already given in the period of a charge, the cap per period being shared by the charges of the period (V38): the
-- scholarship lines of the charges that were not voided, and for a charge from before the lines existed (it has none) its whole
-- benefit, which was the scholarship's. The charge being checked is left out.
CREATE FUNCTION charges_award_benefit_given(p_institution uuid, p_award uuid, p_period date, p_excluded uuid) RETURNS numeric
    LANGUAGE sql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
    SELECT COALESCE(sum(CASE
               WHEN EXISTS (SELECT 1 FROM charges_charge_benefit b
                             WHERE b.institution_id = c.institution_id AND b.charge_id = c.id)
                   THEN (SELECT COALESCE(sum(b.amount), 0) FROM charges_charge_benefit b
                          WHERE b.institution_id = c.institution_id AND b.charge_id = c.id
                            AND b.scholarship_award_id = p_award)
               ELSE c.discount_amount END), 0)
      FROM charges_charge c
     WHERE c.institution_id = p_institution AND c.scholarship_award_id = p_award
       AND c.period_start = p_period AND c.id <> p_excluded
       AND NOT EXISTS (SELECT 1 FROM charges_charge_void v
                        WHERE v.institution_id = c.institution_id AND v.charge_id = c.id);
$$;

-- One line of a charge. The charge exists already (the foreign key is checked after this trigger, so it is looked up here), and the lines
-- of the charges before it in the same transaction too, so the remainder of a cap is read exactly as the engine reads it.
CREATE FUNCTION charges_charge_benefit_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    v_charge    charges_charge%ROWTYPE;
    v_award     RECORD;
    v_discount  RECORD;
    v_assign    RECORD;
    v_expected  numeric;
    v_given     numeric;
    v_remaining numeric;
    v_written   numeric;
BEGIN
    SELECT * INTO v_charge FROM charges_charge WHERE institution_id = NEW.institution_id AND id = NEW.charge_id;
    IF NOT FOUND OR v_charge.origin <> 'AUTOMATIC' OR NEW.currency <> v_charge.discount_currency
            OR NEW.currency <> v_charge.base_currency OR NEW.amount > v_charge.base_amount THEN
        RAISE EXCEPTION 'a benefit line belongs to an automatic charge, in its currency and never above its base'
            USING ERRCODE = 'check_violation';
    END IF;

    -- The lines of a charge never add up to more than its benefit (so a line cannot be added to a charge that was issued with all of
    -- its own); that they add up to exactly its benefit is checked when the transaction ends.
    SELECT COALESCE(sum(b.amount), 0) INTO v_written FROM charges_charge_benefit b
     WHERE b.institution_id = NEW.institution_id AND b.charge_id = NEW.charge_id;
    IF v_written + NEW.amount > v_charge.discount_amount THEN
        RAISE EXCEPTION 'the benefit lines of a charge never add up to more than its benefit'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.source = 'SCHOLARSHIP' THEN
        -- INV-16 was checked on the charge itself; here the line is the award of the charge and the amount is its benefit.
        IF v_charge.scholarship_award_id IS DISTINCT FROM NEW.scholarship_award_id
                OR v_charge.scholarship_code IS DISTINCT FROM NEW.scholarship_code THEN
            RAISE EXCEPTION 'the scholarship line of a charge is the award of the charge'
                USING ERRCODE = 'check_violation';
        END IF;
        SELECT s.benefit_type, s.percent, s.fixed_amount, s.period_cap INTO v_award
          FROM scholarships_award a
          JOIN scholarships_scholarship s ON s.institution_id = a.institution_id AND s.id = a.scholarship_id
         WHERE a.institution_id = NEW.institution_id AND a.id = NEW.scholarship_award_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'the scholarship line of a charge names an award of the institution'
                USING ERRCODE = 'check_violation';
        END IF;
        IF v_award.benefit_type = 'PERCENTAGE' THEN
            v_expected := round(v_charge.base_amount * v_award.percent / 100, 2);
        ELSE
            v_expected := v_award.fixed_amount;
        END IF;
        IF v_award.period_cap IS NULL THEN
            v_remaining := v_charge.base_amount;
        ELSE
            v_given := charges_award_benefit_given(NEW.institution_id, NEW.scholarship_award_id, v_charge.period_start,
                                                   v_charge.id);
            v_remaining := GREATEST(v_award.period_cap - v_given, 0);
        END IF;
        v_expected := LEAST(v_expected, v_remaining, v_charge.base_amount);
        IF NEW.amount <> v_expected THEN
            RAISE EXCEPTION 'the scholarship line of a charge is the benefit of its award, limited to what is left of its cap in the period and to the base'
                USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        SELECT d.kind, d.code, d.benefit_type, d.percent, d.fixed_amount, d.currency, d.valid_from, d.valid_to, d.is_active
          INTO v_discount
          FROM scholarships_discount d
         WHERE d.institution_id = NEW.institution_id AND d.id = NEW.discount_id;
        IF NOT FOUND
                OR NOT v_discount.is_active
                OR v_discount.code <> NEW.discount_code
                OR v_discount.currency <> v_charge.base_currency
                OR v_charge.pricing_date < v_discount.valid_from
                OR v_charge.pricing_date > v_discount.valid_to
                OR NOT EXISTS (SELECT 1 FROM scholarships_discount_concept c
                                WHERE c.institution_id = NEW.institution_id AND c.discount_id = NEW.discount_id
                                  AND c.payment_concept_id = v_charge.payment_concept_id) THEN
            RAISE EXCEPTION 'a discount line applies an active discount, in force at the pricing date of the charge, that covers its concept'
                USING ERRCODE = 'check_violation';
        END IF;
        IF v_discount.kind = 'campaign' THEN
            IF NEW.discount_assignment_id IS NOT NULL THEN
                RAISE EXCEPTION 'a campaign discount applies by itself, without an assignment'
                    USING ERRCODE = 'check_violation';
            END IF;
        ELSE
            -- An agreement applies to a student only through an assignment that is approved now and covers the pricing date.
            SELECT a.discount_id, a.student_id, a.status, a.valid_from, a.valid_to INTO v_assign
              FROM scholarships_discount_assignment a
             WHERE a.institution_id = NEW.institution_id AND a.id = NEW.discount_assignment_id;
            IF NEW.discount_assignment_id IS NULL OR NOT FOUND
                    OR v_assign.discount_id <> NEW.discount_id
                    OR v_assign.student_id <> v_charge.student_id
                    OR v_assign.status <> 'approved'
                    OR v_charge.pricing_date < v_assign.valid_from
                    OR v_charge.pricing_date > v_assign.valid_to THEN
                RAISE EXCEPTION 'an agreement discount applies through an approved assignment of the student that covers the pricing date'
                    USING ERRCODE = 'check_violation';
            END IF;
        END IF;
        -- Its benefit on what is left is never above its benefit on the gross base (the cascade only lowers what a discount gives).
        IF v_discount.benefit_type = 'PERCENTAGE' THEN
            v_expected := round(v_charge.base_amount * v_discount.percent / 100, 2);
        ELSE
            v_expected := v_discount.fixed_amount;
        END IF;
        IF NEW.amount > LEAST(v_expected, v_charge.base_amount) THEN
            RAISE EXCEPTION 'the benefit of a discount line is never above the benefit of its discount on the base of the charge'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- The whole of the lines of a charge, checked when the transaction ends (the lines are inserted after the charge, since they refer to it).
CREATE FUNCTION charges_charge_benefits_check() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    v_count       integer;
    v_total       numeric;
    v_scholarship integer;
BEGIN
    SELECT count(*), COALESCE(sum(b.amount), 0), count(*) FILTER (WHERE b.source = 'SCHOLARSHIP')
      INTO v_count, v_total, v_scholarship
      FROM charges_charge_benefit b
     WHERE b.institution_id = NEW.institution_id AND b.charge_id = NEW.id;
    IF v_total <> NEW.discount_amount THEN
        RAISE EXCEPTION 'the benefit of a charge is the sum of its benefit lines'
            USING ERRCODE = 'check_violation';
    END IF;
    IF v_scholarship <> (NEW.scholarship_award_id IS NOT NULL)::integer THEN
        RAISE EXCEPTION 'a charge has a scholarship line exactly when it has a scholarship'
            USING ERRCODE = 'check_violation';
    END IF;
    -- INV-15: benefits stack only when every one of them is stackable; a scholarship counts as stackable.
    IF v_count > 1 AND EXISTS (SELECT 1 FROM charges_charge_benefit b
                                 JOIN scholarships_discount d ON d.institution_id = b.institution_id AND d.id = b.discount_id
                                WHERE b.institution_id = NEW.institution_id AND b.charge_id = NEW.id AND NOT d.is_stackable) THEN
        RAISE EXCEPTION 'benefits stack only when every discount among them is stackable'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION charges_award_benefit_given(uuid, uuid, date, uuid) FROM PUBLIC;
-- It is called by the trigger of a line, which runs with the rights of whoever inserts the charge.
GRANT EXECUTE ON FUNCTION charges_award_benefit_given(uuid, uuid, date, uuid) TO confia_admin_app;
REVOKE ALL ON FUNCTION charges_charge_benefit_before_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION charges_charge_benefits_check() FROM PUBLIC;

CREATE TRIGGER charges_charge_benefit_before_insert_trg
    BEFORE INSERT ON charges_charge_benefit
    FOR EACH ROW EXECUTE FUNCTION charges_charge_benefit_before_insert();
CREATE TRIGGER charges_charge_benefit_append_only_trg
    BEFORE UPDATE OR DELETE ON charges_charge_benefit
    FOR EACH ROW EXECUTE FUNCTION charges_append_only_guard();
CREATE TRIGGER charges_charge_benefit_truncate_trg
    BEFORE TRUNCATE ON charges_charge_benefit
    FOR EACH STATEMENT EXECUTE FUNCTION charges_append_only_guard();
CREATE CONSTRAINT TRIGGER charges_charge_benefits_check_trg
    AFTER INSERT ON charges_charge
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION charges_charge_benefits_check();

-- The charge: V38 text, except that the amount of the benefit is no longer compared with the benefit of one award (it is the sum of the
-- lines, which the trigger of each line and the deferred trigger of the charge check). The award, when there is one, is still checked here.
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
    debit_count   integer;
    debit_total   numeric;
    debit_ccy     text;
    all_debits    integer;
    all_total     numeric;
    other_debits  integer;
    expected_other integer;
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

    -- INV-15, INV-16: the scholarship of a charge is an award that is approved now, of this student, in force at the pricing date, of an
    -- active scholarship that covers the concept, in the currency of the charge. Its amount is checked on its line.
    IF NEW.scholarship_award_id IS NOT NULL THEN
        SELECT a.status, a.student_id, a.valid_from, a.valid_to, s.id AS scholarship_id, s.code, s.is_active, s.currency
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
    END IF;
    IF NEW.discount_amount > 0 AND NEW.origin <> 'AUTOMATIC' THEN
        RAISE EXCEPTION 'only an automatic charge carries a benefit' USING ERRCODE = 'check_violation';
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
    -- Every other debit is the benefit: ONE entry for the total benefit (the sum of its lines) when there is one, none when there is not.
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

-- ---------------------------------------------------------------------------------------------------------------------
-- A discount with a charge issued with it is in use (the hook V39 declared): its benefit never changes, its validity only shortens and
-- its concepts are only removed. The text of V39, with "or a charge was issued with it".
-- ---------------------------------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION scholarships_discount_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    in_use boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a discount is never deleted, it is deactivated' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.institution_id <> OLD.institution_id OR NEW.id <> OLD.id OR NEW.code <> OLD.code OR NEW.kind <> OLD.kind
            OR NEW.created_by <> OLD.created_by OR NEW.created_at <> OLD.created_at OR NEW.currency <> OLD.currency THEN
        RAISE EXCEPTION 'the code, the kind, the creation and the currency of a discount never change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    -- In use: an assignment was approved (and maybe revoked since), or a charge was issued with the discount.
    SELECT EXISTS (SELECT 1 FROM scholarships_discount_assignment
                    WHERE institution_id = NEW.institution_id AND discount_id = NEW.id AND status IN ('approved', 'revoked'))
        OR EXISTS (SELECT 1 FROM charges_charge_benefit
                    WHERE institution_id = NEW.institution_id AND discount_id = NEW.id)
      INTO in_use;
    IF in_use AND (NEW.benefit_type <> OLD.benefit_type OR NEW.percent IS DISTINCT FROM OLD.percent
            OR NEW.fixed_amount IS DISTINCT FROM OLD.fixed_amount) THEN
        RAISE EXCEPTION 'the benefit of a discount in use never changes, another discount is created'
            USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_benefit_locked_chk';
    END IF;
    IF in_use AND (NEW.valid_from < OLD.valid_from OR NEW.valid_to > OLD.valid_to) THEN
        RAISE EXCEPTION 'the validity of a discount in use only shortens, another discount is created'
            USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_scope_locked_chk';
    END IF;
    IF (NEW.valid_from > OLD.valid_from OR NEW.valid_to < OLD.valid_to) AND EXISTS (
            SELECT 1 FROM scholarships_discount_assignment
            WHERE institution_id = NEW.institution_id AND discount_id = NEW.id AND status IN ('pending', 'approved')
              AND (valid_from < NEW.valid_from OR valid_to > NEW.valid_to)) THEN
        RAISE EXCEPTION 'the validity of a discount keeps every pending or approved assignment inside it'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_discount_validity_assignments_chk';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION scholarships_discount_concept_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM scholarships_discount_assignment
                WHERE institution_id = NEW.institution_id AND discount_id = NEW.discount_id
                  AND status IN ('approved', 'revoked'))
            OR EXISTS (SELECT 1 FROM charges_charge_benefit
                        WHERE institution_id = NEW.institution_id AND discount_id = NEW.discount_id) THEN
        RAISE EXCEPTION 'a discount in use covers no new concept, another discount is created'
            USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_scope_locked_chk';
    END IF;
    RETURN NEW;
END;
$$;

-- The discounts in use, for the module that owns them: it cannot read the charges, so the database answers. Evaluated with the rights
-- and the row-level security of whoever asks.
CREATE VIEW scholarships_discount_in_use WITH (security_invoker = true) AS
SELECT d.institution_id, d.id AS discount_id
  FROM scholarships_discount d
 WHERE EXISTS (SELECT 1 FROM scholarships_discount_assignment a
                WHERE a.institution_id = d.institution_id AND a.discount_id = d.id AND a.status IN ('approved', 'revoked'))
    OR EXISTS (SELECT 1 FROM charges_charge_benefit b WHERE b.institution_id = d.institution_id AND b.discount_id = d.id);

-- ---------------------------------------------------------------------------------------------------------------------
-- Row-level security and privileges: the same as the charge (insert and read for the administrative process, read for reports).
-- ---------------------------------------------------------------------------------------------------------------------
ALTER TABLE charges_charge_benefit ENABLE ROW LEVEL SECURITY;
ALTER TABLE charges_charge_benefit FORCE  ROW LEVEL SECURITY;

CREATE POLICY charges_charge_benefit_institution_isolation ON charges_charge_benefit
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON charges_charge_benefit FROM PUBLIC;
REVOKE ALL ON scholarships_discount_in_use FROM PUBLIC;
GRANT SELECT, INSERT ON charges_charge_benefit TO confia_admin_app;
GRANT SELECT ON charges_charge_benefit TO confia_readonly;
GRANT SELECT ON scholarships_discount_in_use TO confia_admin_app;
GRANT SELECT ON scholarships_discount_in_use TO confia_readonly;
-- confia_portal_app: no grant, staff data. confia_owner: schema owner, without BYPASSRLS, and bound by every trigger above.

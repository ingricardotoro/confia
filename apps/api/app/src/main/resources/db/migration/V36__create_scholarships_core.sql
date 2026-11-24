-- Thirty-sixth migration (F2 deliverables 4, 5 and 7, scholarships cut 1; docs/cortes/scholarships-awards.md). Creates the scholarships
-- of an institution and the awards of a scholarship to a student, which a person authorized, who is not the one who requested
-- them, approves or rejects, and which can be revoked afterwards. The accrual engine reads the approved awards in a later task;
-- this migration touches no charge and no ledger.
--
-- THE DATABASE REFUSES, to every role including the owner:
--   * a scholarship whose benefit is not exactly one of: a percentage greater than zero and up to 100, or a fixed amount greater
--     than zero, both with at most two decimals, in HNL; a cap per period that is not greater than zero with two decimals;
--   * a scholarship that covers no payment concept (deferred constraint trigger on the scholarship and on its concepts);
--   * a change of the type or of the value of the benefit of a scholarship that has an award approved or revoked, because every
--     charge issued with it must be explained with the value it had; a change of its code or of its creation; any DELETE;
--   * an award that is born in another status than pending, whose validity is not inside its academic year, or for an inactive
--     scholarship; any change of an award but its status, along pending -> approved | rejected and approved -> revoked; an
--     approval for an inactive scholarship; any DELETE;
--   * (docs/02 section 3.3) two APPROVED awards of the same scholarship for the same student whose validities overlap, with an
--     exclusion constraint over the date range (the same technique as the prices of V28, INV-11);
--   * a decision on an award that is not in the state it decides (approve and reject: pending; revoke: approved), a decision by
--     another account than the one of the session, an approval or a rejection by the account that requested the award (segregation
--     of duties), a reason that is not of 20 to 500 characters on a rejection or a revocation, a reason on an approval; a status
--     of an award that does not have the decision behind it (deferred constraint trigger); any UPDATE or DELETE of a decision.
--
-- Nothing here is ever deleted: a scholarship is deactivated, an award is rejected or revoked, and a decision is a record that never
-- changes. The Idempotency-Key of a decision is stored PERMANENTLY with the hash of what it asked, so the same key answers the
-- same decision for as long as the decision exists. The reason of a rejection or of a revocation may name a person: it lives only
-- in the decision and is never copied to the audit trail.
--
-- The institution comes from the session, never from a request: forced row-level security keeps one institution from reading or
-- writing the rows of another.

CREATE TABLE scholarships_scholarship (
    institution_id UUID          NOT NULL,
    id             UUID          NOT NULL,
    code           VARCHAR(30)   NOT NULL,
    name           VARCHAR(120)  NOT NULL,
    benefit_type   TEXT          NOT NULL,
    -- Percentage points, 0.01 to 100.00. Null for a fixed amount.
    percent        NUMERIC(5,2),
    -- The fixed amount per charge, in the currency below. Null for a percentage.
    fixed_amount   NUMERIC(14,4),
    -- The cap of the benefit per period, in the currency below. Null when there is none.
    period_cap     NUMERIC(14,4),
    currency       CHAR(3)       NOT NULL DEFAULT 'HNL',
    is_active      BOOLEAN       NOT NULL DEFAULT TRUE,
    version        BIGINT        NOT NULL DEFAULT 0,
    created_by     UUID          NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT scholarships_scholarship_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT scholarships_scholarship_code_chk CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,29}$'),
    CONSTRAINT scholarships_scholarship_name_chk CHECK (char_length(btrim(name)) BETWEEN 1 AND 120 AND char_length(name) <= 120),
    CONSTRAINT scholarships_scholarship_type_chk CHECK (benefit_type IN ('PERCENTAGE', 'FIXED_AMOUNT')),
    CONSTRAINT scholarships_scholarship_currency_chk CHECK (currency = 'HNL'),
    CONSTRAINT scholarships_scholarship_benefit_chk CHECK (
        (benefit_type = 'PERCENTAGE' AND percent IS NOT NULL AND percent > 0 AND percent <= 100 AND fixed_amount IS NULL)
        OR (benefit_type = 'FIXED_AMOUNT' AND percent IS NULL AND fixed_amount IS NOT NULL AND fixed_amount > 0
            AND fixed_amount = round(fixed_amount, 2))),
    CONSTRAINT scholarships_scholarship_cap_chk CHECK (period_cap IS NULL OR (period_cap > 0 AND period_cap = round(period_cap, 2))),
    CONSTRAINT scholarships_scholarship_version_chk CHECK (version >= 0)
);

-- One code per institution, whatever its capitals. The name ends in _uq so the adapter translates a violation of it into the
-- duplicate-scholarship-code answer.
CREATE UNIQUE INDEX scholarships_scholarship_code_uq ON scholarships_scholarship (institution_id, lower(code));

-- The payment concepts that a scholarship covers: one or more.
CREATE TABLE scholarships_scholarship_concept (
    institution_id     UUID NOT NULL,
    scholarship_id     UUID NOT NULL,
    payment_concept_id UUID NOT NULL,
    CONSTRAINT scholarships_scholarship_concept_pk PRIMARY KEY (institution_id, scholarship_id, payment_concept_id),
    CONSTRAINT scholarships_scholarship_concept_scholarship_fk FOREIGN KEY (institution_id, scholarship_id)
        REFERENCES scholarships_scholarship (institution_id, id),
    CONSTRAINT scholarships_scholarship_concept_concept_fk FOREIGN KEY (institution_id, payment_concept_id)
        REFERENCES catalog_payment_concept (institution_id, id)
);

-- The concept side of the foreign key.
CREATE INDEX scholarships_scholarship_concept_concept_idx
    ON scholarships_scholarship_concept (institution_id, payment_concept_id);

CREATE TABLE scholarships_award (
    institution_id   UUID        NOT NULL,
    id               UUID        NOT NULL,
    scholarship_id   UUID        NOT NULL,
    student_id       UUID        NOT NULL,
    academic_year_id UUID        NOT NULL,
    valid_from       DATE        NOT NULL,
    valid_to         DATE        NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'pending',
    requested_by     UUID        NOT NULL,
    requested_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT scholarships_award_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT scholarships_award_scholarship_fk FOREIGN KEY (institution_id, scholarship_id)
        REFERENCES scholarships_scholarship (institution_id, id),
    CONSTRAINT scholarships_award_student_fk FOREIGN KEY (institution_id, student_id)
        REFERENCES students_student (institution_id, id),
    CONSTRAINT scholarships_award_year_fk FOREIGN KEY (institution_id, academic_year_id)
        REFERENCES organization_academic_year (institution_id, id),
    CONSTRAINT scholarships_award_status_chk CHECK (status IN ('pending', 'approved', 'rejected', 'revoked')),
    CONSTRAINT scholarships_award_dates_chk CHECK (valid_to >= valid_from),
    -- Days outside this window are a typing mistake (a year 0202, a year 20271), never a real validity.
    CONSTRAINT scholarships_award_window_chk CHECK (
        valid_from BETWEEN date '2000-01-01' AND date '2100-12-31' AND valid_to BETWEEN date '2000-01-01' AND date '2100-12-31'),
    -- A student has at most one APPROVED award of a scholarship at any day (docs/02 section 3.3). The closed range makes a
    -- validity that ends the day another starts an overlap.
    CONSTRAINT scholarships_award_overlap_excl EXCLUDE USING gist (
        institution_id WITH =, scholarship_id WITH =, student_id WITH =,
        daterange(valid_from, valid_to, '[]') WITH &&) WHERE (status = 'approved')
);

CREATE INDEX scholarships_award_student_idx ON scholarships_award (institution_id, student_id, requested_at DESC, id);
CREATE INDEX scholarships_award_scholarship_idx ON scholarships_award (institution_id, scholarship_id, requested_at DESC, id);
CREATE INDEX scholarships_award_status_idx ON scholarships_award (institution_id, status, requested_at DESC, id);
CREATE INDEX scholarships_award_year_idx ON scholarships_award (institution_id, academic_year_id);

-- ---------------------------------------------------------------------------------------------------------------------
-- Decisions on an award: INSERT-ONLY. One approval or rejection, and then at most one revocation, per award.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE scholarships_award_decision (
    institution_id UUID         NOT NULL,
    id             UUID         NOT NULL,
    award_id       UUID         NOT NULL,
    decision       TEXT         NOT NULL,
    -- Only a rejection and a revocation carry a reason; it is free text that may name a person and is never copied to the audit trail.
    reason         TEXT,
    decided_by     UUID         NOT NULL,
    decided_at     TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    request_key    VARCHAR(128) NOT NULL,
    request_hash   CHAR(64)     NOT NULL,
    CONSTRAINT scholarships_award_decision_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT scholarships_award_decision_award_fk FOREIGN KEY (institution_id, award_id)
        REFERENCES scholarships_award (institution_id, id),
    CONSTRAINT scholarships_award_decision_kind_uq UNIQUE (institution_id, award_id, decision),
    CONSTRAINT scholarships_award_decision_key_uq UNIQUE (institution_id, request_key),
    CONSTRAINT scholarships_award_decision_kind_chk CHECK (decision IN ('approved', 'rejected', 'revoked')),
    CONSTRAINT scholarships_award_decision_shape_chk CHECK (
        (decision = 'approved' AND reason IS NULL)
        OR (decision IN ('rejected', 'revoked') AND reason IS NOT NULL
            AND char_length(btrim(reason)) BETWEEN 20 AND 500 AND char_length(reason) <= 500)),
    CONSTRAINT scholarships_award_decision_key_chk CHECK (request_key ~ '^[!-~]{1,128}$'),
    CONSTRAINT scholarships_award_decision_hash_chk CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

-- ---------------------------------------------------------------------------------------------------------------------
-- Guards
-- ---------------------------------------------------------------------------------------------------------------------

-- The life of a scholarship. See the header for the rules; every refusal is a restrict_violation or a check_violation.
CREATE FUNCTION scholarships_scholarship_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a scholarship is never deleted, it is deactivated' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.institution_id <> OLD.institution_id OR NEW.id <> OLD.id OR NEW.code <> OLD.code
            OR NEW.created_by <> OLD.created_by OR NEW.created_at <> OLD.created_at OR NEW.currency <> OLD.currency THEN
        RAISE EXCEPTION 'the code, the creation and the currency of a scholarship never change' USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.benefit_type <> OLD.benefit_type OR NEW.percent IS DISTINCT FROM OLD.percent
            OR NEW.fixed_amount IS DISTINCT FROM OLD.fixed_amount)
            AND EXISTS (SELECT 1 FROM scholarships_award
                WHERE institution_id = NEW.institution_id AND scholarship_id = NEW.id AND status IN ('approved', 'revoked')) THEN
        RAISE EXCEPTION 'the benefit of a scholarship with approved awards never changes, another scholarship is created'
            USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_scholarship_benefit_locked_chk';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION scholarships_require_concept() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    inst uuid;
    sch  uuid;
BEGIN
    IF TG_TABLE_NAME = 'scholarships_scholarship' THEN
        inst := NEW.institution_id;
        sch := NEW.id;
    ELSE
        inst := OLD.institution_id;
        sch := OLD.scholarship_id;
    END IF;
    IF EXISTS (SELECT 1 FROM scholarships_scholarship WHERE institution_id = inst AND id = sch)
            AND NOT EXISTS (SELECT 1 FROM scholarships_scholarship_concept
                WHERE institution_id = inst AND scholarship_id = sch) THEN
        RAISE EXCEPTION 'a scholarship covers at least one payment concept' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

-- The birth of an award, and the moves of its status.
CREATE FUNCTION scholarships_award_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    year_row organization_academic_year%ROWTYPE;
    sch      scholarships_scholarship%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an award is never deleted, it is rejected or revoked' USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'pending' THEN
            RAISE EXCEPTION 'an award is born pending' USING ERRCODE = 'check_violation';
        END IF;
        SELECT * INTO year_row FROM organization_academic_year
            WHERE institution_id = NEW.institution_id AND id = NEW.academic_year_id;
        IF NOT FOUND OR NEW.valid_from < year_row.start_date OR NEW.valid_to > year_row.end_date THEN
            RAISE EXCEPTION 'the validity of an award is inside its academic year'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_award_in_year_chk';
        END IF;
        SELECT * INTO sch FROM scholarships_scholarship
            WHERE institution_id = NEW.institution_id AND id = NEW.scholarship_id FOR SHARE;
        IF NOT FOUND OR NOT sch.is_active THEN
            RAISE EXCEPTION 'an award is requested for an active scholarship'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_award_scholarship_active_chk';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.institution_id <> OLD.institution_id OR NEW.id <> OLD.id OR NEW.scholarship_id <> OLD.scholarship_id
            OR NEW.student_id <> OLD.student_id OR NEW.academic_year_id <> OLD.academic_year_id
            OR NEW.valid_from <> OLD.valid_from OR NEW.valid_to <> OLD.valid_to
            OR NEW.requested_by <> OLD.requested_by OR NEW.requested_at <> OLD.requested_at THEN
        RAISE EXCEPTION 'an award changes only its status' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NOT ((OLD.status = 'pending' AND NEW.status IN ('approved', 'rejected'))
            OR (OLD.status = 'approved' AND NEW.status = 'revoked')) THEN
        RAISE EXCEPTION 'the status of an award moves pending to approved or rejected, and approved to revoked'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status = 'approved' THEN
        -- Shared lock: a concurrent edit of the value of the scholarship and this approval take turns.
        SELECT * INTO sch FROM scholarships_scholarship
            WHERE institution_id = NEW.institution_id AND id = NEW.scholarship_id FOR SHARE;
        IF NOT FOUND OR NOT sch.is_active THEN
            RAISE EXCEPTION 'an award is approved for an active scholarship'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_award_scholarship_active_chk';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- A decision: the award is in the state the decision acts on, the author is the one of the session, and an approval or a rejection
-- is never made by the account that requested the award.
CREATE FUNCTION scholarships_award_decision_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    actor   uuid := NULLIF(current_setting('app.actor_id', true), '')::uuid;
    decided scholarships_award%ROWTYPE;
BEGIN
    SELECT * INTO decided FROM scholarships_award
        WHERE institution_id = NEW.institution_id AND id = NEW.award_id FOR UPDATE;
    IF NOT FOUND
            OR (NEW.decision IN ('approved', 'rejected') AND decided.status <> 'pending')
            OR (NEW.decision = 'revoked' AND decided.status <> 'approved') THEN
        RAISE EXCEPTION 'the award is not in the state this decision acts on'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_award_decision_state_chk';
    END IF;
    IF actor IS NULL OR NEW.decided_by <> actor THEN
        RAISE EXCEPTION 'a decision is written by its author' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.decision IN ('approved', 'rejected') AND decided.requested_by = NEW.decided_by THEN
        RAISE EXCEPTION 'the one who requests an award never approves or rejects it'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_award_decision_self_chk';
    END IF;
    IF NEW.decided_at NOT BETWEEN clock_timestamp() - interval '1 minute' AND clock_timestamp() + interval '1 minute' THEN
        RAISE EXCEPTION 'a decision is made now' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- By the end of the transaction the status of the award is the one the decision leads to: no decision without its status and no
-- status without its decision.
CREATE FUNCTION scholarships_award_decision_has_status() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    current_status text;
BEGIN
    SELECT status INTO current_status FROM scholarships_award
        WHERE institution_id = NEW.institution_id AND id = NEW.award_id;
    IF current_status IS NULL
            OR (NEW.decision = 'approved' AND current_status NOT IN ('approved', 'revoked'))
            OR (NEW.decision = 'rejected' AND current_status <> 'rejected')
            OR (NEW.decision = 'revoked' AND current_status <> 'revoked') THEN
        RAISE EXCEPTION 'a decision moves the status of its award' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION scholarships_award_status_has_decision() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.status <> 'pending' AND NOT EXISTS (
            SELECT 1 FROM scholarships_award_decision
            WHERE institution_id = NEW.institution_id AND award_id = NEW.id
                AND decision = CASE NEW.status WHEN 'approved' THEN 'approved' WHEN 'rejected' THEN 'rejected' ELSE 'revoked' END) THEN
        RAISE EXCEPTION 'the status of an award has the decision behind it' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION scholarships_append_only_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    RAISE EXCEPTION 'scholarships decisions are append-only: % on % is refused', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$;

REVOKE ALL ON FUNCTION scholarships_scholarship_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION scholarships_require_concept() FROM PUBLIC;
REVOKE ALL ON FUNCTION scholarships_award_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION scholarships_award_decision_before_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION scholarships_award_decision_has_status() FROM PUBLIC;
REVOKE ALL ON FUNCTION scholarships_award_status_has_decision() FROM PUBLIC;
REVOKE ALL ON FUNCTION scholarships_append_only_guard() FROM PUBLIC;

CREATE TRIGGER scholarships_scholarship_guard_trg
    BEFORE UPDATE OR DELETE ON scholarships_scholarship
    FOR EACH ROW EXECUTE FUNCTION scholarships_scholarship_guard();
CREATE CONSTRAINT TRIGGER scholarships_scholarship_has_concept_trg
    AFTER INSERT OR UPDATE ON scholarships_scholarship
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION scholarships_require_concept();
CREATE CONSTRAINT TRIGGER scholarships_concept_keeps_one_trg
    AFTER DELETE ON scholarships_scholarship_concept
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION scholarships_require_concept();
CREATE TRIGGER scholarships_award_guard_trg
    BEFORE INSERT OR UPDATE OR DELETE ON scholarships_award
    FOR EACH ROW EXECUTE FUNCTION scholarships_award_guard();
CREATE CONSTRAINT TRIGGER scholarships_award_status_has_decision_trg
    AFTER UPDATE ON scholarships_award
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION scholarships_award_status_has_decision();
CREATE TRIGGER scholarships_award_decision_before_insert_trg
    BEFORE INSERT ON scholarships_award_decision
    FOR EACH ROW EXECUTE FUNCTION scholarships_award_decision_before_insert();
CREATE CONSTRAINT TRIGGER scholarships_award_decision_has_status_trg
    AFTER INSERT ON scholarships_award_decision
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION scholarships_award_decision_has_status();
CREATE TRIGGER scholarships_award_decision_append_only_trg
    BEFORE UPDATE OR DELETE ON scholarships_award_decision
    FOR EACH ROW EXECUTE FUNCTION scholarships_append_only_guard();

-- ---------------------------------------------------------------------------------------------------------------------
-- Row-level security and privileges
-- ---------------------------------------------------------------------------------------------------------------------
ALTER TABLE scholarships_scholarship         ENABLE ROW LEVEL SECURITY;
ALTER TABLE scholarships_scholarship         FORCE  ROW LEVEL SECURITY;
ALTER TABLE scholarships_scholarship_concept ENABLE ROW LEVEL SECURITY;
ALTER TABLE scholarships_scholarship_concept FORCE  ROW LEVEL SECURITY;
ALTER TABLE scholarships_award               ENABLE ROW LEVEL SECURITY;
ALTER TABLE scholarships_award               FORCE  ROW LEVEL SECURITY;
ALTER TABLE scholarships_award_decision      ENABLE ROW LEVEL SECURITY;
ALTER TABLE scholarships_award_decision      FORCE  ROW LEVEL SECURITY;

CREATE POLICY scholarships_scholarship_institution_isolation ON scholarships_scholarship
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY scholarships_scholarship_concept_institution_isolation ON scholarships_scholarship_concept
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY scholarships_award_institution_isolation ON scholarships_award
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY scholarships_award_decision_institution_isolation ON scholarships_award_decision
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON scholarships_scholarship         FROM PUBLIC;
REVOKE ALL ON scholarships_scholarship_concept FROM PUBLIC;
REVOKE ALL ON scholarships_award               FROM PUBLIC;
REVOKE ALL ON scholarships_award_decision      FROM PUBLIC;

-- No DELETE on a scholarship, an award or a decision for anybody. A scholarship changes only its name, benefit, cap, active flag and
-- version; the concepts it covers are rows that are inserted and, when the scholarship is edited, deleted (they are not a financial
-- datum: every charge keeps the award and the amount it was issued with); an award changes only its status; a decision never changes.
GRANT SELECT, INSERT ON scholarships_scholarship TO confia_admin_app;
GRANT UPDATE (name, benefit_type, percent, fixed_amount, period_cap, is_active, version, updated_at)
    ON scholarships_scholarship TO confia_admin_app;
GRANT SELECT, INSERT, DELETE ON scholarships_scholarship_concept TO confia_admin_app;
GRANT SELECT, INSERT ON scholarships_award TO confia_admin_app;
GRANT UPDATE (status) ON scholarships_award TO confia_admin_app;
GRANT SELECT, INSERT ON scholarships_award_decision TO confia_admin_app;
GRANT SELECT ON scholarships_scholarship         TO confia_readonly;
GRANT SELECT ON scholarships_scholarship_concept TO confia_readonly;
GRANT SELECT ON scholarships_award               TO confia_readonly;
GRANT SELECT ON scholarships_award_decision      TO confia_readonly;
-- confia_portal_app: no grant, staff data. confia_owner: schema owner, without BYPASSRLS.

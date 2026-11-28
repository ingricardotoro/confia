-- Thirty-ninth migration (F2 deliverable 6, scholarships cut 2; docs/cortes/scholarships-discounts.md). Creates the discounts of an
-- institution (of campaign and of agreement) and the assignment of an agreement discount to a student, which a person authorized, who
-- is not the one who requested it, approves or rejects, and which can be revoked afterwards. The accrual engine reads the discounts in
-- force in a later task; this migration touches no charge and no ledger.
--
-- THE DATABASE REFUSES, to every role including the owner:
--   * a discount whose benefit is not exactly one of: a percentage greater than zero and up to 100, or a fixed amount greater than
--     zero, both with at most two decimals, in HNL; a kind other than campaign or agreement; a validity that ends before it starts
--     or lies outside 2000-2100; a discount that covers no payment concept (deferred constraint trigger on the discount and on its
--     concepts);
--   * a change of the code, of the kind or of the creation of a discount; any DELETE;
--   * a change of the type or of the value of the benefit of a discount that has an assignment approved or revoked, because every
--     charge issued with it must be explained with the value it had; with such an assignment its validity only shortens (it never
--     starts earlier nor ends later) and its concepts are only removed (never added): widening a discount is another discount.
--     HOOK FOR THE ACCRUAL TASK: "in use" means an assignment approved or revoked today; the task that makes the engine apply
--     discounts adds "or a charge issued with it" in discounts_guard and in discount_concept_before_insert (CREATE OR REPLACE);
--   * a shortening of the validity of a discount that leaves a pending or approved assignment outside it;
--   * an assignment for a discount that is not of agreement kind or is inactive, whose validity is not inside the one of the discount,
--     that is born in another status than pending; any change of an assignment but its status, along pending -> approved | rejected
--     and approved -> revoked; an approval for an inactive discount or for a validity that is no longer inside the one of the
--     discount; any DELETE;
--   * two APPROVED assignments of the same discount for the same student whose validities overlap, with an exclusion constraint over
--     the date range (the same technique as the awards of V36);
--   * a decision on an assignment that is not in the state it decides (approve and reject: pending; revoke: approved), a decision by
--     another account than the one of the session, an approval or a rejection by the account that requested the assignment
--     (segregation of duties), a reason that is not of 20 to 500 characters on a rejection or a revocation, a reason on an approval;
--     a status of an assignment that does not have the decision behind it (deferred constraint trigger); any UPDATE or DELETE of a
--     decision.
--
-- A campaign discount applies by itself to every charge of a covered concept issued inside its validity, so it has no assignment; the
-- database refuses an assignment of one. Nothing here is ever deleted: a discount is deactivated, an assignment is rejected or revoked,
-- and a decision is a record that never changes. The Idempotency-Key of a decision is stored PERMANENTLY with the hash of what it
-- asked. The reason of a rejection or of a revocation may name a person: it lives only in the decision and is never copied to the
-- audit trail.
--
-- The institution comes from the session, never from a request: forced row-level security keeps one institution from reading or
-- writing the rows of another.

CREATE TABLE scholarships_discount (
    institution_id UUID          NOT NULL,
    id             UUID          NOT NULL,
    code           VARCHAR(30)   NOT NULL,
    name           VARCHAR(120)  NOT NULL,
    kind           TEXT          NOT NULL,
    benefit_type   TEXT          NOT NULL,
    -- Percentage points, 0.01 to 100.00. Null for a fixed amount.
    percent        NUMERIC(5,2),
    -- The fixed amount per charge, in the currency below. Null for a percentage.
    fixed_amount   NUMERIC(14,4),
    currency       CHAR(3)       NOT NULL DEFAULT 'HNL',
    valid_from     DATE          NOT NULL,
    valid_to       DATE          NOT NULL,
    is_stackable   BOOLEAN       NOT NULL,
    is_active      BOOLEAN       NOT NULL DEFAULT TRUE,
    version        BIGINT        NOT NULL DEFAULT 0,
    created_by     UUID          NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT scholarships_discount_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT scholarships_discount_code_chk CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,29}$'),
    CONSTRAINT scholarships_discount_name_chk CHECK (char_length(btrim(name)) BETWEEN 1 AND 120 AND char_length(name) <= 120),
    CONSTRAINT scholarships_discount_kind_chk CHECK (kind IN ('campaign', 'agreement')),
    CONSTRAINT scholarships_discount_type_chk CHECK (benefit_type IN ('PERCENTAGE', 'FIXED_AMOUNT')),
    CONSTRAINT scholarships_discount_currency_chk CHECK (currency = 'HNL'),
    CONSTRAINT scholarships_discount_benefit_chk CHECK (
        (benefit_type = 'PERCENTAGE' AND percent IS NOT NULL AND percent > 0 AND percent <= 100 AND fixed_amount IS NULL)
        OR (benefit_type = 'FIXED_AMOUNT' AND percent IS NULL AND fixed_amount IS NOT NULL AND fixed_amount > 0
            AND fixed_amount = round(fixed_amount, 2))),
    CONSTRAINT scholarships_discount_dates_chk CHECK (valid_to >= valid_from),
    -- Days outside this window are a typing mistake (a year 0202, a year 20271), never a real validity.
    CONSTRAINT scholarships_discount_window_chk CHECK (
        valid_from BETWEEN date '2000-01-01' AND date '2100-12-31' AND valid_to BETWEEN date '2000-01-01' AND date '2100-12-31'),
    CONSTRAINT scholarships_discount_version_chk CHECK (version >= 0)
);

-- One code per institution, whatever its capitals. The name ends in _uq so the adapter translates a violation of it into the
-- duplicate-discount-code answer.
CREATE UNIQUE INDEX scholarships_discount_code_uq ON scholarships_discount (institution_id, lower(code));
CREATE INDEX scholarships_discount_kind_idx ON scholarships_discount (institution_id, kind, is_active);

-- The payment concepts that a discount covers: one or more.
CREATE TABLE scholarships_discount_concept (
    institution_id     UUID NOT NULL,
    discount_id        UUID NOT NULL,
    payment_concept_id UUID NOT NULL,
    CONSTRAINT scholarships_discount_concept_pk PRIMARY KEY (institution_id, discount_id, payment_concept_id),
    CONSTRAINT scholarships_discount_concept_discount_fk FOREIGN KEY (institution_id, discount_id)
        REFERENCES scholarships_discount (institution_id, id),
    CONSTRAINT scholarships_discount_concept_concept_fk FOREIGN KEY (institution_id, payment_concept_id)
        REFERENCES catalog_payment_concept (institution_id, id)
);

-- The concept side of the foreign key.
CREATE INDEX scholarships_discount_concept_concept_idx
    ON scholarships_discount_concept (institution_id, payment_concept_id);

CREATE TABLE scholarships_discount_assignment (
    institution_id UUID        NOT NULL,
    id             UUID        NOT NULL,
    discount_id    UUID        NOT NULL,
    student_id     UUID        NOT NULL,
    valid_from     DATE        NOT NULL,
    valid_to       DATE        NOT NULL,
    status         TEXT        NOT NULL DEFAULT 'pending',
    requested_by   UUID        NOT NULL,
    requested_at   TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT scholarships_discount_assignment_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT scholarships_discount_assignment_discount_fk FOREIGN KEY (institution_id, discount_id)
        REFERENCES scholarships_discount (institution_id, id),
    CONSTRAINT scholarships_discount_assignment_student_fk FOREIGN KEY (institution_id, student_id)
        REFERENCES students_student (institution_id, id),
    CONSTRAINT scholarships_discount_assignment_status_chk CHECK (status IN ('pending', 'approved', 'rejected', 'revoked')),
    CONSTRAINT scholarships_discount_assignment_dates_chk CHECK (valid_to >= valid_from),
    CONSTRAINT scholarships_discount_assignment_window_chk CHECK (
        valid_from BETWEEN date '2000-01-01' AND date '2100-12-31' AND valid_to BETWEEN date '2000-01-01' AND date '2100-12-31'),
    -- A student has at most one APPROVED assignment of a discount at any day. The closed range makes a validity that ends the day
    -- another starts an overlap.
    CONSTRAINT scholarships_discount_assignment_overlap_excl EXCLUDE USING gist (
        institution_id WITH =, discount_id WITH =, student_id WITH =,
        daterange(valid_from, valid_to, '[]') WITH &&) WHERE (status = 'approved')
);

CREATE INDEX scholarships_discount_assignment_student_idx
    ON scholarships_discount_assignment (institution_id, student_id, requested_at DESC, id);
CREATE INDEX scholarships_discount_assignment_discount_idx
    ON scholarships_discount_assignment (institution_id, discount_id, requested_at DESC, id);
CREATE INDEX scholarships_discount_assignment_status_idx
    ON scholarships_discount_assignment (institution_id, status, requested_at DESC, id);

-- ---------------------------------------------------------------------------------------------------------------------
-- Decisions on an assignment: INSERT-ONLY. One approval or rejection, and then at most one revocation, per assignment.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE scholarships_discount_assignment_decision (
    institution_id UUID         NOT NULL,
    id             UUID         NOT NULL,
    assignment_id  UUID         NOT NULL,
    decision       TEXT         NOT NULL,
    -- Only a rejection and a revocation carry a reason; it is free text that may name a person and is never copied to the audit trail.
    reason         TEXT,
    decided_by     UUID         NOT NULL,
    decided_at     TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    request_key    VARCHAR(128) NOT NULL,
    request_hash   CHAR(64)     NOT NULL,
    CONSTRAINT scholarships_discount_decision_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT scholarships_discount_decision_assignment_fk FOREIGN KEY (institution_id, assignment_id)
        REFERENCES scholarships_discount_assignment (institution_id, id),
    CONSTRAINT scholarships_discount_decision_kind_uq UNIQUE (institution_id, assignment_id, decision),
    CONSTRAINT scholarships_discount_decision_key_uq UNIQUE (institution_id, request_key),
    CONSTRAINT scholarships_discount_decision_kind_chk CHECK (decision IN ('approved', 'rejected', 'revoked')),
    CONSTRAINT scholarships_discount_decision_shape_chk CHECK (
        (decision = 'approved' AND reason IS NULL)
        OR (decision IN ('rejected', 'revoked') AND reason IS NOT NULL
            AND char_length(btrim(reason)) BETWEEN 20 AND 500 AND char_length(reason) <= 500)),
    CONSTRAINT scholarships_discount_decision_key_chk CHECK (request_key ~ '^[!-~]{1,128}$'),
    CONSTRAINT scholarships_discount_decision_hash_chk CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

-- ---------------------------------------------------------------------------------------------------------------------
-- Guards
-- ---------------------------------------------------------------------------------------------------------------------

-- The life of a discount. See the header for the rules; every refusal is a restrict_violation or a check_violation.
CREATE FUNCTION scholarships_discount_guard() RETURNS trigger
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
    -- In use: an assignment was approved (and maybe revoked since). The accrual task adds the charges issued with the discount.
    SELECT EXISTS (SELECT 1 FROM scholarships_discount_assignment
                    WHERE institution_id = NEW.institution_id AND discount_id = NEW.id AND status IN ('approved', 'revoked'))
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

CREATE FUNCTION scholarships_discount_require_concept() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    inst uuid;
    disc uuid;
BEGIN
    IF TG_TABLE_NAME = 'scholarships_discount' THEN
        inst := NEW.institution_id;
        disc := NEW.id;
    ELSE
        inst := OLD.institution_id;
        disc := OLD.discount_id;
    END IF;
    IF EXISTS (SELECT 1 FROM scholarships_discount WHERE institution_id = inst AND id = disc)
            AND NOT EXISTS (SELECT 1 FROM scholarships_discount_concept
                WHERE institution_id = inst AND discount_id = disc) THEN
        RAISE EXCEPTION 'a discount covers at least one payment concept' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

-- A discount in use covers no new concept: a discount is created before any assignment exists, so its first concepts are never refused.
CREATE FUNCTION scholarships_discount_concept_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM scholarships_discount_assignment
                WHERE institution_id = NEW.institution_id AND discount_id = NEW.discount_id
                  AND status IN ('approved', 'revoked')) THEN
        RAISE EXCEPTION 'a discount in use covers no new concept, another discount is created'
            USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_scope_locked_chk';
    END IF;
    RETURN NEW;
END;
$$;

-- The birth of an assignment, and the moves of its status.
CREATE FUNCTION scholarships_discount_assignment_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    disc scholarships_discount%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an assignment is never deleted, it is rejected or revoked' USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'pending' THEN
            RAISE EXCEPTION 'an assignment is born pending' USING ERRCODE = 'check_violation';
        END IF;
        -- Shared lock: a concurrent edit of the validity of the discount and this request take turns.
        SELECT * INTO disc FROM scholarships_discount
            WHERE institution_id = NEW.institution_id AND id = NEW.discount_id FOR SHARE;
        IF NOT FOUND OR disc.kind <> 'agreement' THEN
            RAISE EXCEPTION 'only an agreement discount is assigned to a student'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_discount_assignment_kind_chk';
        END IF;
        IF NOT disc.is_active THEN
            RAISE EXCEPTION 'an assignment is requested for an active discount'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_discount_assignment_active_chk';
        END IF;
        IF NEW.valid_from < disc.valid_from OR NEW.valid_to > disc.valid_to THEN
            RAISE EXCEPTION 'the validity of an assignment is inside the one of its discount'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_discount_assignment_in_validity_chk';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.institution_id <> OLD.institution_id OR NEW.id <> OLD.id OR NEW.discount_id <> OLD.discount_id
            OR NEW.student_id <> OLD.student_id OR NEW.valid_from <> OLD.valid_from OR NEW.valid_to <> OLD.valid_to
            OR NEW.requested_by <> OLD.requested_by OR NEW.requested_at <> OLD.requested_at THEN
        RAISE EXCEPTION 'an assignment changes only its status' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NOT ((OLD.status = 'pending' AND NEW.status IN ('approved', 'rejected'))
            OR (OLD.status = 'approved' AND NEW.status = 'revoked')) THEN
        RAISE EXCEPTION 'the status of an assignment moves pending to approved or rejected, and approved to revoked'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status = 'approved' THEN
        SELECT * INTO disc FROM scholarships_discount
            WHERE institution_id = NEW.institution_id AND id = NEW.discount_id FOR SHARE;
        IF NOT FOUND OR NOT disc.is_active THEN
            RAISE EXCEPTION 'an assignment is approved for an active discount'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_discount_assignment_active_chk';
        END IF;
        IF NEW.valid_from < disc.valid_from OR NEW.valid_to > disc.valid_to THEN
            RAISE EXCEPTION 'the validity of an assignment is inside the one of its discount'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_discount_assignment_in_validity_chk';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- A decision: the assignment is in the state the decision acts on, the author is the one of the session, and an approval or a
-- rejection is never made by the account that requested the assignment.
CREATE FUNCTION scholarships_discount_decision_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    actor   uuid := NULLIF(current_setting('app.actor_id', true), '')::uuid;
    decided scholarships_discount_assignment%ROWTYPE;
BEGIN
    SELECT * INTO decided FROM scholarships_discount_assignment
        WHERE institution_id = NEW.institution_id AND id = NEW.assignment_id FOR UPDATE;
    IF NOT FOUND
            OR (NEW.decision IN ('approved', 'rejected') AND decided.status <> 'pending')
            OR (NEW.decision = 'revoked' AND decided.status <> 'approved') THEN
        RAISE EXCEPTION 'the assignment is not in the state this decision acts on'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_discount_decision_state_chk';
    END IF;
    IF actor IS NULL OR NEW.decided_by <> actor THEN
        RAISE EXCEPTION 'a decision is written by its author' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.decision IN ('approved', 'rejected') AND decided.requested_by = NEW.decided_by THEN
        RAISE EXCEPTION 'the one who requests an assignment never approves or rejects it'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_discount_decision_self_chk';
    END IF;
    IF NEW.decided_at NOT BETWEEN clock_timestamp() - interval '1 minute' AND clock_timestamp() + interval '1 minute' THEN
        RAISE EXCEPTION 'a decision is made now' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- By the end of the transaction the status of the assignment is the one the decision leads to: no decision without its status and no
-- status without its decision.
CREATE FUNCTION scholarships_discount_decision_has_status() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    current_status text;
BEGIN
    SELECT status INTO current_status FROM scholarships_discount_assignment
        WHERE institution_id = NEW.institution_id AND id = NEW.assignment_id;
    IF current_status IS NULL
            OR (NEW.decision = 'approved' AND current_status NOT IN ('approved', 'revoked'))
            OR (NEW.decision = 'rejected' AND current_status <> 'rejected')
            OR (NEW.decision = 'revoked' AND current_status <> 'revoked') THEN
        RAISE EXCEPTION 'a decision moves the status of its assignment' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION scholarships_discount_status_has_decision() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NEW.status <> 'pending' AND NOT EXISTS (
            SELECT 1 FROM scholarships_discount_assignment_decision
            WHERE institution_id = NEW.institution_id AND assignment_id = NEW.id
                AND decision = CASE NEW.status WHEN 'approved' THEN 'approved' WHEN 'rejected' THEN 'rejected' ELSE 'revoked' END) THEN
        RAISE EXCEPTION 'the status of an assignment has the decision behind it' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

REVOKE ALL ON FUNCTION scholarships_discount_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION scholarships_discount_require_concept() FROM PUBLIC;
REVOKE ALL ON FUNCTION scholarships_discount_concept_before_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION scholarships_discount_assignment_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION scholarships_discount_decision_before_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION scholarships_discount_decision_has_status() FROM PUBLIC;
REVOKE ALL ON FUNCTION scholarships_discount_status_has_decision() FROM PUBLIC;

CREATE TRIGGER scholarships_discount_guard_trg
    BEFORE UPDATE OR DELETE ON scholarships_discount
    FOR EACH ROW EXECUTE FUNCTION scholarships_discount_guard();
CREATE CONSTRAINT TRIGGER scholarships_discount_has_concept_trg
    AFTER INSERT OR UPDATE ON scholarships_discount
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION scholarships_discount_require_concept();
CREATE CONSTRAINT TRIGGER scholarships_discount_concept_keeps_one_trg
    AFTER DELETE ON scholarships_discount_concept
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION scholarships_discount_require_concept();
CREATE TRIGGER scholarships_discount_concept_before_insert_trg
    BEFORE INSERT ON scholarships_discount_concept
    FOR EACH ROW EXECUTE FUNCTION scholarships_discount_concept_before_insert();
CREATE TRIGGER scholarships_discount_assignment_guard_trg
    BEFORE INSERT OR UPDATE OR DELETE ON scholarships_discount_assignment
    FOR EACH ROW EXECUTE FUNCTION scholarships_discount_assignment_guard();
CREATE CONSTRAINT TRIGGER scholarships_discount_status_has_decision_trg
    AFTER UPDATE ON scholarships_discount_assignment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION scholarships_discount_status_has_decision();
CREATE TRIGGER scholarships_discount_decision_before_insert_trg
    BEFORE INSERT ON scholarships_discount_assignment_decision
    FOR EACH ROW EXECUTE FUNCTION scholarships_discount_decision_before_insert();
CREATE CONSTRAINT TRIGGER scholarships_discount_decision_has_status_trg
    AFTER INSERT ON scholarships_discount_assignment_decision
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION scholarships_discount_decision_has_status();
-- The append-only guard of V36 refuses UPDATE and DELETE of any decision table it is attached to.
CREATE TRIGGER scholarships_discount_decision_append_only_trg
    BEFORE UPDATE OR DELETE ON scholarships_discount_assignment_decision
    FOR EACH ROW EXECUTE FUNCTION scholarships_append_only_guard();

-- ---------------------------------------------------------------------------------------------------------------------
-- Row-level security and privileges
-- ---------------------------------------------------------------------------------------------------------------------
ALTER TABLE scholarships_discount                     ENABLE ROW LEVEL SECURITY;
ALTER TABLE scholarships_discount                     FORCE  ROW LEVEL SECURITY;
ALTER TABLE scholarships_discount_concept             ENABLE ROW LEVEL SECURITY;
ALTER TABLE scholarships_discount_concept             FORCE  ROW LEVEL SECURITY;
ALTER TABLE scholarships_discount_assignment          ENABLE ROW LEVEL SECURITY;
ALTER TABLE scholarships_discount_assignment          FORCE  ROW LEVEL SECURITY;
ALTER TABLE scholarships_discount_assignment_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE scholarships_discount_assignment_decision FORCE  ROW LEVEL SECURITY;

CREATE POLICY scholarships_discount_institution_isolation ON scholarships_discount
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY scholarships_discount_concept_institution_isolation ON scholarships_discount_concept
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY scholarships_discount_assignment_institution_isolation ON scholarships_discount_assignment
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY scholarships_discount_decision_institution_isolation ON scholarships_discount_assignment_decision
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON scholarships_discount                     FROM PUBLIC;
REVOKE ALL ON scholarships_discount_concept             FROM PUBLIC;
REVOKE ALL ON scholarships_discount_assignment          FROM PUBLIC;
REVOKE ALL ON scholarships_discount_assignment_decision FROM PUBLIC;

-- No DELETE on a discount, an assignment or a decision for anybody. A discount changes only its name, benefit, validity, stackable
-- flag, active flag and version; the concepts it covers are rows that are inserted and, when the discount is edited, deleted (they
-- are not a financial datum: every charge keeps the discount and the amount it was issued with); an assignment changes only its
-- status; a decision never changes.
GRANT SELECT, INSERT ON scholarships_discount TO confia_admin_app;
GRANT UPDATE (name, benefit_type, percent, fixed_amount, valid_from, valid_to, is_stackable, is_active, version, updated_at)
    ON scholarships_discount TO confia_admin_app;
GRANT SELECT, INSERT, DELETE ON scholarships_discount_concept TO confia_admin_app;
GRANT SELECT, INSERT ON scholarships_discount_assignment TO confia_admin_app;
GRANT UPDATE (status) ON scholarships_discount_assignment TO confia_admin_app;
GRANT SELECT, INSERT ON scholarships_discount_assignment_decision TO confia_admin_app;
GRANT SELECT ON scholarships_discount                     TO confia_readonly;
GRANT SELECT ON scholarships_discount_concept             TO confia_readonly;
GRANT SELECT ON scholarships_discount_assignment          TO confia_readonly;
GRANT SELECT ON scholarships_discount_assignment_decision TO confia_readonly;
-- confia_portal_app: no grant, staff data. confia_owner: schema owner, without BYPASSRLS.

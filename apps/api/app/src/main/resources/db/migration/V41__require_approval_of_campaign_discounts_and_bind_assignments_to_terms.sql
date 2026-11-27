-- Forty-first migration (F2 deliverable 6, scholarships cut 2, correction batch of the security review; docs/cortes/scholarships-
-- discounts.md). It only ADDS to V39 and V40 (and replaces their functions with CREATE OR REPLACE): no existing row is updated or deleted,
-- and every discount and assignment that exists stays valid (a discount that exists is approved; an assignment without a snapshot of
-- its terms stays as it is and can no longer be approved, it is requested again).
--
-- 1. CAMPAIGN APPROVAL (S7, finding H1). A campaign discount applies by itself to every charge, so nothing but a second person stands
--    between one person and a benefit for every student. Now:
--      * a campaign discount is BORN PENDING (approval_status = 'pending') and applies to nothing until a different person authorized
--        approves it ('approved'); a rejected one never applies ('rejected');
--      * WIDENING an approved campaign discount (another type of benefit or a greater value, an earlier start or a later end of its
--        validity, a concept more, the stackable flag turned on) and REACTIVATING it are PROPOSALS: the proposed terms live in the
--        pending_* columns of the same row, the terms in force do not change, and the proposal becomes the terms only when a different
--        person approves it (or is dropped when one rejects it). One proposal at a time, bound to the version of the discount.
--        Narrowing (a smaller value, a shorter validity, fewer concepts, the stackable flag turned off) and deactivating stay immediate.
--      * a decision (approve or reject) is a record of scholarships_discount_change_decision, INSERT-ONLY, with the Idempotency-Key kept
--        permanently with the hash of what it asked and the reason (20 to 500 characters) of a rejection; the database refuses a
--        decision by the person who requested the discount or the proposal, on a discount that waits for nothing, or for another
--        version than the current one.
--    THE DATABASE REFUSES, to every role including the owner: a campaign discount that is not born pending, an agreement discount that
--    is not born approved, a change of a campaign discount that widens it or reactivates it other than through an approved decision
--    taken in the same transaction (and whose terms are exactly the proposed ones), a proposal of somebody else's name, any change of a
--    pending or rejected discount, a concept added to an approved campaign discount other than by that decision, and a discount line
--    of a charge for a discount that is not approved.
--
-- 2. BINDING AN ASSIGNMENT TO THE TERMS IT WAS REQUESTED FOR (finding H2). The assignment keeps the version of the discount and its
--    benefit (type and value) as they were when it was requested. It is born only with the current ones and is approved only while the
--    discount still has exactly that version: whoever requested it cannot edit the discount between the request and the approval and
--    get something else approved.
--
-- 3. STACKABLE LOCKED IN USE (S8, findings F3 and L2): the stackable flag of a discount in use never changes, like its type and value.
--
-- 4. A LINE ONLY WITH ITS CHARGE (findings M1 and F1): a benefit line is written in the transaction that wrote its charge.
--
-- 5. THE REQUESTER IS THE SESSION (finding L1): an assignment of a discount and an award of a scholarship are requested by the account
--    of the session, as every decision is already decided by it.

-- ---------------------------------------------------------------------------------------------------------------------
-- The approval state and the pending proposal of a discount
-- ---------------------------------------------------------------------------------------------------------------------
ALTER TABLE scholarships_discount
    ADD COLUMN approval_status      TEXT NOT NULL DEFAULT 'approved',
    ADD COLUMN pending_requested_by UUID,
    ADD COLUMN pending_requested_at TIMESTAMPTZ,
    ADD COLUMN pending_name         VARCHAR(120),
    ADD COLUMN pending_benefit_type TEXT,
    ADD COLUMN pending_percent      NUMERIC(5,2),
    ADD COLUMN pending_fixed_amount NUMERIC(14,4),
    ADD COLUMN pending_valid_from   DATE,
    ADD COLUMN pending_valid_to     DATE,
    ADD COLUMN pending_is_stackable BOOLEAN,
    ADD COLUMN pending_reactivate   BOOLEAN,
    ADD COLUMN pending_concepts     UUID[];
-- What exists is approved; what is inserted from now on says it.
ALTER TABLE scholarships_discount ALTER COLUMN approval_status DROP DEFAULT;

ALTER TABLE scholarships_discount
    ADD CONSTRAINT scholarships_discount_approval_chk CHECK (approval_status IN ('pending', 'approved', 'rejected')),
    -- An agreement discount applies only through an approved assignment, which is its own second person: it needs no approval.
    ADD CONSTRAINT scholarships_discount_agreement_approval_chk CHECK (
        kind = 'campaign' OR (approval_status = 'approved' AND pending_requested_by IS NULL)),
    -- A discount that was never approved (or was rejected) is not changed by proposals.
    ADD CONSTRAINT scholarships_discount_unapproved_chk CHECK (approval_status = 'approved' OR pending_requested_by IS NULL),
    ADD CONSTRAINT scholarships_discount_pending_chk CHECK (
        (pending_requested_by IS NULL AND pending_requested_at IS NULL AND pending_name IS NULL AND pending_benefit_type IS NULL
            AND pending_percent IS NULL AND pending_fixed_amount IS NULL AND pending_valid_from IS NULL AND pending_valid_to IS NULL
            AND pending_is_stackable IS NULL AND pending_reactivate IS NULL AND pending_concepts IS NULL)
        OR (pending_requested_by IS NOT NULL AND pending_requested_at IS NOT NULL
            AND pending_name IS NOT NULL AND char_length(btrim(pending_name)) BETWEEN 1 AND 120
            AND pending_is_stackable IS NOT NULL AND pending_reactivate IS NOT NULL
            AND pending_valid_from IS NOT NULL AND pending_valid_to IS NOT NULL
            AND pending_valid_from BETWEEN date '2000-01-01' AND date '2100-12-31'
            AND pending_valid_to BETWEEN date '2000-01-01' AND date '2100-12-31'
            AND pending_valid_to >= pending_valid_from
            AND pending_concepts IS NOT NULL AND cardinality(pending_concepts) >= 1
            AND pending_benefit_type IS NOT NULL
            AND ((pending_benefit_type = 'PERCENTAGE' AND pending_percent IS NOT NULL AND pending_percent > 0
                    AND pending_percent <= 100 AND pending_fixed_amount IS NULL)
                OR (pending_benefit_type = 'FIXED_AMOUNT' AND pending_percent IS NULL AND pending_fixed_amount IS NOT NULL
                    AND pending_fixed_amount > 0 AND pending_fixed_amount = round(pending_fixed_amount, 2)))));

GRANT UPDATE (approval_status, pending_requested_by, pending_requested_at, pending_name, pending_benefit_type, pending_percent,
              pending_fixed_amount, pending_valid_from, pending_valid_to, pending_is_stackable, pending_reactivate, pending_concepts)
    ON scholarships_discount TO confia_admin_app;

-- ---------------------------------------------------------------------------------------------------------------------
-- Decisions on a campaign discount (the creation or a proposal): INSERT-ONLY. One per version of the discount.
-- ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE scholarships_discount_change_decision (
    institution_id   UUID         NOT NULL,
    id               UUID         NOT NULL,
    discount_id      UUID         NOT NULL,
    -- The version of the discount that waited for this decision (the one the person who decided saw).
    discount_version BIGINT       NOT NULL,
    decision         TEXT         NOT NULL,
    -- Only a rejection carries a reason; it is free text that may name a person and is never copied to the audit trail.
    reason           TEXT,
    decided_by       UUID         NOT NULL,
    decided_at       TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    request_key      VARCHAR(128) NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    -- The transaction that wrote the decision (set by the trigger): the discount takes the terms of an approval only in that one.
    posting_xid      BIGINT       NOT NULL DEFAULT (pg_current_xact_id()::text::bigint),
    CONSTRAINT scholarships_discount_change_decision_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT scholarships_discount_change_decision_discount_fk FOREIGN KEY (institution_id, discount_id)
        REFERENCES scholarships_discount (institution_id, id),
    CONSTRAINT scholarships_discount_change_decision_version_uq UNIQUE (institution_id, discount_id, discount_version),
    CONSTRAINT scholarships_discount_change_decision_key_uq UNIQUE (institution_id, request_key),
    CONSTRAINT scholarships_discount_change_decision_kind_chk CHECK (decision IN ('approved', 'rejected')),
    CONSTRAINT scholarships_discount_change_decision_shape_chk CHECK (
        (decision = 'approved' AND reason IS NULL)
        OR (decision = 'rejected' AND reason IS NOT NULL
            AND char_length(btrim(reason)) BETWEEN 20 AND 500 AND char_length(reason) <= 500)),
    CONSTRAINT scholarships_discount_change_decision_version_chk CHECK (discount_version >= 0),
    CONSTRAINT scholarships_discount_change_decision_key_chk CHECK (request_key ~ '^[!-~]{1,128}$'),
    CONSTRAINT scholarships_discount_change_decision_hash_chk CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

-- ---------------------------------------------------------------------------------------------------------------------
-- The snapshot of the terms an assignment was requested for
-- ---------------------------------------------------------------------------------------------------------------------
ALTER TABLE scholarships_discount_assignment
    ADD COLUMN discount_version BIGINT,
    ADD COLUMN benefit_type     TEXT,
    ADD COLUMN percent          NUMERIC(5,2),
    ADD COLUMN fixed_amount     NUMERIC(14,4);
-- An assignment from before this migration has no snapshot (and never will: it cannot be approved); every new one has all of it.
ALTER TABLE scholarships_discount_assignment
    ADD CONSTRAINT scholarships_discount_assignment_terms_shape_chk CHECK (
        (discount_version IS NULL AND benefit_type IS NULL AND percent IS NULL AND fixed_amount IS NULL)
        OR (discount_version IS NOT NULL AND discount_version >= 0 AND benefit_type IS NOT NULL
            AND ((benefit_type = 'PERCENTAGE' AND percent IS NOT NULL AND percent > 0 AND percent <= 100 AND fixed_amount IS NULL)
                OR (benefit_type = 'FIXED_AMOUNT' AND percent IS NULL AND fixed_amount IS NOT NULL AND fixed_amount > 0
                    AND fixed_amount = round(fixed_amount, 2)))));

-- ---------------------------------------------------------------------------------------------------------------------
-- Guards
-- ---------------------------------------------------------------------------------------------------------------------

-- The life of a discount: V39 and V40 plus the approval of a campaign discount and the lock of the stackable flag.
CREATE OR REPLACE FUNCTION scholarships_discount_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    in_use       boolean;
    actor        uuid := NULLIF(current_setting('app.actor_id', true), '')::uuid;
    same_terms   boolean;
    same_pending boolean;
    decided      scholarships_discount_change_decision%ROWTYPE;
    covered      uuid[];
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a discount is never deleted, it is deactivated' USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.pending_requested_by IS NOT NULL
                OR (NEW.kind = 'campaign' AND NEW.approval_status <> 'pending')
                OR (NEW.kind = 'agreement' AND NEW.approval_status <> 'approved') THEN
            RAISE EXCEPTION 'a campaign discount is born pending its approval and an agreement discount needs none'
                USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_campaign_approval_chk';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.institution_id <> OLD.institution_id OR NEW.id <> OLD.id OR NEW.code <> OLD.code OR NEW.kind <> OLD.kind
            OR NEW.created_by <> OLD.created_by OR NEW.created_at <> OLD.created_at OR NEW.currency <> OLD.currency THEN
        RAISE EXCEPTION 'the code, the kind, the creation and the currency of a discount never change'
            USING ERRCODE = 'restrict_violation';
    END IF;

    same_terms := NEW.name = OLD.name AND NEW.benefit_type = OLD.benefit_type AND NEW.percent IS NOT DISTINCT FROM OLD.percent
        AND NEW.fixed_amount IS NOT DISTINCT FROM OLD.fixed_amount AND NEW.valid_from = OLD.valid_from
        AND NEW.valid_to = OLD.valid_to AND NEW.is_stackable = OLD.is_stackable;
    same_pending := (NEW.pending_requested_by, NEW.pending_requested_at, NEW.pending_name, NEW.pending_benefit_type,
                     NEW.pending_percent, NEW.pending_fixed_amount, NEW.pending_valid_from, NEW.pending_valid_to,
                     NEW.pending_is_stackable, NEW.pending_reactivate, NEW.pending_concepts)
        IS NOT DISTINCT FROM (OLD.pending_requested_by, OLD.pending_requested_at, OLD.pending_name, OLD.pending_benefit_type,
                     OLD.pending_percent, OLD.pending_fixed_amount, OLD.pending_valid_from, OLD.pending_valid_to,
                     OLD.pending_is_stackable, OLD.pending_reactivate, OLD.pending_concepts);

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
    -- S8: the stackable flag is locked like the type and the value.
    IF in_use AND NEW.is_stackable <> OLD.is_stackable THEN
        RAISE EXCEPTION 'the stackable flag of a discount in use never changes, another discount is created'
            USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_benefit_locked_chk';
    END IF;
    IF in_use AND (NEW.valid_from < OLD.valid_from OR NEW.valid_to > OLD.valid_to) THEN
        RAISE EXCEPTION 'the validity of a discount in use only shortens, another discount is created'
            USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_scope_locked_chk';
    END IF;

    -- A campaign discount takes the approval of another person to be widened, reactivated or created.
    IF NEW.kind = 'campaign' THEN
        IF NEW.approval_status <> OLD.approval_status THEN
            -- The creation is decided: pending to approved or rejected, by a decision of this transaction on this version, and the
            -- terms do not move with it.
            IF OLD.approval_status <> 'pending' OR NEW.approval_status NOT IN ('approved', 'rejected')
                    OR NOT same_terms OR NEW.is_active <> OLD.is_active OR NOT same_pending
                    OR NOT EXISTS (SELECT 1 FROM scholarships_discount_change_decision d
                                    WHERE d.institution_id = NEW.institution_id AND d.discount_id = NEW.id
                                      AND d.discount_version = OLD.version AND d.decision = NEW.approval_status
                                      AND d.posting_xid = pg_current_xact_id()::text::bigint) THEN
                RAISE EXCEPTION 'a campaign discount leaves its pending state only by the decision of another person'
                    USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_campaign_approval_chk';
            END IF;
        ELSIF OLD.approval_status <> 'approved' THEN
            IF NOT (same_terms AND NEW.is_active = OLD.is_active AND same_pending) THEN
                RAISE EXCEPTION 'a campaign discount that is not approved does not change'
                    USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_campaign_approval_chk';
            END IF;
        ELSIF OLD.pending_requested_by IS NULL AND NEW.pending_requested_by IS NOT NULL THEN
            -- A proposal: the terms in force and the activity stay, and the proposal is of the person of the session.
            IF NOT same_terms OR NEW.is_active <> OLD.is_active OR actor IS NULL OR NEW.pending_requested_by <> actor THEN
                RAISE EXCEPTION 'a proposal leaves the terms in force as they are and is made by the person of the session'
                    USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_campaign_approval_chk';
            END IF;
        ELSIF OLD.pending_requested_by IS NOT NULL AND NEW.pending_requested_by IS NULL THEN
            SELECT * INTO decided FROM scholarships_discount_change_decision d
             WHERE d.institution_id = NEW.institution_id AND d.discount_id = NEW.id AND d.discount_version = OLD.version
               AND d.posting_xid = pg_current_xact_id()::text::bigint;
            IF NOT FOUND THEN
                RAISE EXCEPTION 'a proposal is settled only by the decision of another person'
                    USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_campaign_approval_chk';
            END IF;
            IF decided.decision = 'approved' THEN
                SELECT COALESCE(array_agg(c.payment_concept_id ORDER BY c.payment_concept_id), '{}') INTO covered
                  FROM scholarships_discount_concept c
                 WHERE c.institution_id = NEW.institution_id AND c.discount_id = NEW.id;
                IF NEW.name <> OLD.pending_name OR NEW.benefit_type <> OLD.pending_benefit_type
                        OR NEW.percent IS DISTINCT FROM OLD.pending_percent
                        OR NEW.fixed_amount IS DISTINCT FROM OLD.pending_fixed_amount
                        OR NEW.valid_from <> OLD.pending_valid_from OR NEW.valid_to <> OLD.pending_valid_to
                        OR NEW.is_stackable <> OLD.pending_is_stackable
                        OR NEW.is_active <> (OLD.is_active OR OLD.pending_reactivate)
                        OR covered <> (SELECT COALESCE(array_agg(x ORDER BY x), '{}') FROM unnest(OLD.pending_concepts) AS x) THEN
                    RAISE EXCEPTION 'an approved proposal becomes the terms in force exactly as it was proposed'
                        USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_campaign_approval_chk';
                END IF;
            ELSIF NOT same_terms OR NEW.is_active <> OLD.is_active THEN
                RAISE EXCEPTION 'a rejected proposal changes nothing'
                    USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_campaign_approval_chk';
            END IF;
        ELSIF OLD.pending_requested_by IS NOT NULL THEN
            -- Still waiting: the proposal and the terms stay; the discount can only be deactivated.
            IF NOT (same_terms AND same_pending) OR (NEW.is_active AND NOT OLD.is_active) THEN
                RAISE EXCEPTION 'a discount with a proposal waiting changes only by being deactivated'
                    USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_campaign_approval_chk';
            END IF;
        ELSIF NEW.benefit_type <> OLD.benefit_type
                OR NEW.percent > OLD.percent
                OR NEW.fixed_amount > OLD.fixed_amount
                OR NEW.valid_from < OLD.valid_from OR NEW.valid_to > OLD.valid_to
                OR (NEW.is_stackable AND NOT OLD.is_stackable)
                OR (NEW.is_active AND NOT OLD.is_active) THEN
            -- A plain change of an approved campaign discount never widens it nor reactivates it.
            RAISE EXCEPTION 'widening or reactivating a campaign discount needs the approval of another person'
                USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_campaign_approval_chk';
        END IF;
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

-- The guard now also covers the birth of a discount.
DROP TRIGGER scholarships_discount_guard_trg ON scholarships_discount;
CREATE TRIGGER scholarships_discount_guard_trg
    BEFORE INSERT OR UPDATE OR DELETE ON scholarships_discount
    FOR EACH ROW EXECUTE FUNCTION scholarships_discount_guard();

-- A concept is added to a discount in no other way than: while the discount is born (a campaign discount waiting for approval, in the
-- transaction that inserts it), as part of an approved proposal (a concept of it, in the transaction of the decision), or to an agreement
-- discount that is not in use.
CREATE OR REPLACE FUNCTION scholarships_discount_concept_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    d    RECORD;
    born boolean;
BEGIN
    IF EXISTS (SELECT 1 FROM scholarships_discount_assignment
                WHERE institution_id = NEW.institution_id AND discount_id = NEW.discount_id
                  AND status IN ('approved', 'revoked'))
            OR EXISTS (SELECT 1 FROM charges_charge_benefit
                        WHERE institution_id = NEW.institution_id AND discount_id = NEW.discount_id) THEN
        RAISE EXCEPTION 'a discount in use covers no new concept, another discount is created'
            USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_scope_locked_chk';
    END IF;
    SELECT x.kind, x.approval_status, x.version, x.pending_concepts,
           (x.xmin::text::bigint = pg_current_xact_id()::text::bigint % 4294967296) AS born_now
      INTO d FROM scholarships_discount x
     WHERE x.institution_id = NEW.institution_id AND x.id = NEW.discount_id;
    IF NOT FOUND OR d.kind <> 'campaign' THEN
        RETURN NEW;
    END IF;
    born := d.approval_status = 'pending' AND d.born_now;
    IF NOT born AND NOT (d.approval_status = 'approved' AND d.pending_concepts IS NOT NULL
            AND NEW.payment_concept_id = ANY (d.pending_concepts)
            AND EXISTS (SELECT 1 FROM scholarships_discount_change_decision c
                         WHERE c.institution_id = NEW.institution_id AND c.discount_id = NEW.discount_id
                           AND c.discount_version = d.version AND c.decision = 'approved'
                           AND c.posting_xid = pg_current_xact_id()::text::bigint)) THEN
        RAISE EXCEPTION 'a concept is added to a campaign discount only when it is born or by the approval of another person'
            USING ERRCODE = 'restrict_violation', CONSTRAINT = 'scholarships_discount_campaign_approval_chk';
    END IF;
    RETURN NEW;
END;
$$;

-- A decision on a campaign discount: it waits for that decision, at the version the decider saw, the author is the one of the session
-- and it is never the person who requested the discount or the proposal.
CREATE FUNCTION scholarships_discount_change_decision_before_insert() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    actor     uuid := NULLIF(current_setting('app.actor_id', true), '')::uuid;
    waiting   scholarships_discount%ROWTYPE;
    requester uuid;
BEGIN
    SELECT * INTO waiting FROM scholarships_discount
     WHERE institution_id = NEW.institution_id AND id = NEW.discount_id FOR UPDATE;
    IF NOT FOUND OR waiting.kind <> 'campaign'
            OR NOT (waiting.approval_status = 'pending'
                    OR (waiting.approval_status = 'approved' AND waiting.pending_requested_by IS NOT NULL)) THEN
        RAISE EXCEPTION 'the discount does not wait for this decision'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_discount_change_decision_state_chk';
    END IF;
    IF NEW.discount_version <> waiting.version THEN
        RAISE EXCEPTION 'the discount is not at the version that was decided'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_discount_change_decision_version_chk';
    END IF;
    IF actor IS NULL OR NEW.decided_by <> actor THEN
        RAISE EXCEPTION 'a decision is written by its author' USING ERRCODE = 'check_violation';
    END IF;
    requester := COALESCE(waiting.pending_requested_by, waiting.created_by);
    IF requester = NEW.decided_by THEN
        RAISE EXCEPTION 'the one who requests a discount or a change of it never approves or rejects it'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_discount_change_decision_self_chk';
    END IF;
    IF NEW.decided_at NOT BETWEEN clock_timestamp() - interval '1 minute' AND clock_timestamp() + interval '1 minute' THEN
        RAISE EXCEPTION 'a decision is made now' USING ERRCODE = 'check_violation';
    END IF;
    NEW.posting_xid := pg_current_xact_id()::text::bigint;
    RETURN NEW;
END;
$$;

-- By the end of the transaction the discount no longer waits for the decision: no decision without its effect.
CREATE FUNCTION scholarships_discount_change_decision_has_effect() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM scholarships_discount d
                WHERE d.institution_id = NEW.institution_id AND d.id = NEW.discount_id AND d.version = NEW.discount_version
                  AND (d.approval_status = 'pending' OR d.pending_requested_by IS NOT NULL)) THEN
        RAISE EXCEPTION 'a decision settles what the discount waited for' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

-- An assignment: V39 plus the requester of the session and the terms it was requested for.
CREATE OR REPLACE FUNCTION scholarships_discount_assignment_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    disc  scholarships_discount%ROWTYPE;
    actor uuid := NULLIF(current_setting('app.actor_id', true), '')::uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an assignment is never deleted, it is rejected or revoked' USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF actor IS NULL OR NEW.requested_by <> actor THEN
            RAISE EXCEPTION 'an assignment is requested by the person of the session'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_discount_assignment_requested_by_chk';
        END IF;
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
        IF NEW.discount_version IS DISTINCT FROM disc.version OR NEW.benefit_type IS DISTINCT FROM disc.benefit_type
                OR NEW.percent IS DISTINCT FROM disc.percent OR NEW.fixed_amount IS DISTINCT FROM disc.fixed_amount THEN
            RAISE EXCEPTION 'an assignment is requested for the terms the discount has now'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_discount_assignment_terms_chk';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.institution_id <> OLD.institution_id OR NEW.id <> OLD.id OR NEW.discount_id <> OLD.discount_id
            OR NEW.student_id <> OLD.student_id OR NEW.valid_from <> OLD.valid_from OR NEW.valid_to <> OLD.valid_to
            OR NEW.requested_by <> OLD.requested_by OR NEW.requested_at <> OLD.requested_at
            OR NEW.discount_version IS DISTINCT FROM OLD.discount_version OR NEW.benefit_type IS DISTINCT FROM OLD.benefit_type
            OR NEW.percent IS DISTINCT FROM OLD.percent OR NEW.fixed_amount IS DISTINCT FROM OLD.fixed_amount THEN
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
        -- Approved only for the terms it was requested for: the discount has the version and the benefit the request saw.
        IF NEW.discount_version IS NULL OR disc.version <> NEW.discount_version OR disc.benefit_type <> NEW.benefit_type
                OR disc.percent IS DISTINCT FROM NEW.percent OR disc.fixed_amount IS DISTINCT FROM NEW.fixed_amount THEN
            RAISE EXCEPTION 'an assignment is approved for the terms it was requested for'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_discount_assignment_terms_chk';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- An award: V36 plus the requester of the session.
CREATE OR REPLACE FUNCTION scholarships_award_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    year_row organization_academic_year%ROWTYPE;
    sch      scholarships_scholarship%ROWTYPE;
    actor    uuid := NULLIF(current_setting('app.actor_id', true), '')::uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an award is never deleted, it is rejected or revoked' USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF actor IS NULL OR NEW.requested_by <> actor THEN
            RAISE EXCEPTION 'an award is requested by the person of the session'
                USING ERRCODE = 'check_violation', CONSTRAINT = 'scholarships_award_requested_by_chk';
        END IF;
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

-- A benefit line (V40) plus: the discount is approved, and the charge was written in this very transaction, so a line is never added to
-- a charge issued earlier (not even to one from before the lines existed).
CREATE OR REPLACE FUNCTION charges_charge_benefit_before_insert() RETURNS trigger
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
    IF NOT EXISTS (SELECT 1 FROM ledger_transaction t
                    WHERE t.institution_id = NEW.institution_id AND t.id = v_charge.ledger_transaction_id
                      AND t.posting_xid = pg_current_xact_id()::text::bigint) THEN
        RAISE EXCEPTION 'a benefit line is written in the transaction that wrote its charge'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT COALESCE(sum(b.amount), 0) INTO v_written FROM charges_charge_benefit b
     WHERE b.institution_id = NEW.institution_id AND b.charge_id = NEW.charge_id;
    IF v_written + NEW.amount > v_charge.discount_amount THEN
        RAISE EXCEPTION 'the benefit lines of a charge never add up to more than its benefit'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.source = 'SCHOLARSHIP' THEN
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
        SELECT d.kind, d.code, d.benefit_type, d.percent, d.fixed_amount, d.currency, d.valid_from, d.valid_to, d.is_active,
               d.approval_status
          INTO v_discount
          FROM scholarships_discount d
         WHERE d.institution_id = NEW.institution_id AND d.id = NEW.discount_id;
        IF NOT FOUND
                OR NOT v_discount.is_active
                OR v_discount.approval_status <> 'approved'
                OR v_discount.code <> NEW.discount_code
                OR v_discount.currency <> v_charge.base_currency
                OR v_charge.pricing_date < v_discount.valid_from
                OR v_charge.pricing_date > v_discount.valid_to
                OR NOT EXISTS (SELECT 1 FROM scholarships_discount_concept c
                                WHERE c.institution_id = NEW.institution_id AND c.discount_id = NEW.discount_id
                                  AND c.payment_concept_id = v_charge.payment_concept_id) THEN
            RAISE EXCEPTION 'a discount line applies an approved and active discount, in force at the pricing date of the charge, that covers its concept'
                USING ERRCODE = 'check_violation';
        END IF;
        IF v_discount.kind = 'campaign' THEN
            IF NEW.discount_assignment_id IS NOT NULL THEN
                RAISE EXCEPTION 'a campaign discount applies by itself, without an assignment'
                    USING ERRCODE = 'check_violation';
            END IF;
        ELSE
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

REVOKE ALL ON FUNCTION scholarships_discount_change_decision_before_insert() FROM PUBLIC;
REVOKE ALL ON FUNCTION scholarships_discount_change_decision_has_effect() FROM PUBLIC;

CREATE TRIGGER scholarships_discount_change_decision_before_insert_trg
    BEFORE INSERT ON scholarships_discount_change_decision
    FOR EACH ROW EXECUTE FUNCTION scholarships_discount_change_decision_before_insert();
CREATE CONSTRAINT TRIGGER scholarships_discount_change_decision_has_effect_trg
    AFTER INSERT ON scholarships_discount_change_decision
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION scholarships_discount_change_decision_has_effect();
-- The append-only guard of V36 refuses UPDATE and DELETE of any decision table it is attached to.
CREATE TRIGGER scholarships_discount_change_decision_append_only_trg
    BEFORE UPDATE OR DELETE ON scholarships_discount_change_decision
    FOR EACH ROW EXECUTE FUNCTION scholarships_append_only_guard();

-- ---------------------------------------------------------------------------------------------------------------------
-- Row-level security and privileges
-- ---------------------------------------------------------------------------------------------------------------------
ALTER TABLE scholarships_discount_change_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE scholarships_discount_change_decision FORCE  ROW LEVEL SECURITY;
CREATE POLICY scholarships_discount_change_decision_institution_isolation ON scholarships_discount_change_decision
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
REVOKE ALL ON scholarships_discount_change_decision FROM PUBLIC;
GRANT SELECT, INSERT ON scholarships_discount_change_decision TO confia_admin_app;
GRANT SELECT ON scholarships_discount_change_decision TO confia_readonly;
-- confia_portal_app: no grant, staff data. confia_owner: schema owner, without BYPASSRLS, and bound by every trigger above.

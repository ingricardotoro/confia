-- Thirty-first migration (F3, cut 2; docs/cortes/charges-plans.md). Creates the charge plans: what an institution
-- accrues, how often and to whom. A plan belongs to an academic year and targets one modality, one grade or one section;
-- it holds items, one per payment concept, each with a number of periods and the first day of the first period. The price
-- is never stored here: the accrual engine (cut 3) resolves it by date in the price lists.
--
-- A plan is born as a draft, is activated once and is archived once; it never goes back (DRAFT -> ACTIVE -> ARCHIVED).
-- While it is a draft its items can be added and removed and its name, frequency and due day edited. Activation freezes
-- it: only the name and the status (to ARCHIVED) change from then on, so a plan that already served to accrue charges never
-- changes under them. An archived plan never changes at all. The rules live in the domain and are enforced here too, so no
-- other writer can break them:
--   * the application role holds UPDATE only on the editable columns of a plan and no UPDATE at all on the items;
--   * a trigger on the plan refuses a change of the year, the target, the creation, a transition that is not the chain
--     above, an edit of the frequency or of the due day once the plan is active, any change of an archived plan, a plan
--     born in another state than DRAFT, an activation without items and any DELETE, even for the owner;
--   * a trigger on the items refuses an insert, an update and a delete while the plan is not a draft, even for the owner.
-- The plan row is read FOR SHARE by the item trigger, so a concurrent activation and a concurrent insert of an item take
-- turns instead of crossing.
--
-- A plan is never deleted (no DELETE grant, and a trigger refuses it to the owner too). An item is not a financial datum:
-- while the plan is a draft the application role may delete it; that is the only DELETE granted in this migration.
--
-- The institution comes from the session, never from a request: forced row-level security keeps one institution from
-- reading or writing the rows of another.

CREATE TABLE charges_plan (
    institution_id   UUID         NOT NULL,
    id               UUID         NOT NULL,
    academic_year_id UUID         NOT NULL,
    name             VARCHAR(120) NOT NULL,
    target_scope     TEXT         NOT NULL,
    target_ref_id    UUID         NOT NULL,
    frequency        TEXT         NOT NULL,
    due_day_of_month INTEGER      NOT NULL,
    status           TEXT         NOT NULL DEFAULT 'DRAFT',
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    -- The reference of the target, split by scope so each part is a real foreign key. A foreign key whose column is null
    -- is not checked, so a plan of another scope leaves the other columns empty and the database proves that the
    -- modality, the grade or the section exists in the same institution.
    modality_id      UUID GENERATED ALWAYS AS (CASE WHEN target_scope = 'MODALITY' THEN target_ref_id END) STORED,
    grade_id         UUID GENERATED ALWAYS AS (CASE WHEN target_scope = 'GRADE' THEN target_ref_id END) STORED,
    section_id       UUID GENERATED ALWAYS AS (CASE WHEN target_scope = 'SECTION' THEN target_ref_id END) STORED,
    CONSTRAINT charges_plan_pk           PRIMARY KEY (institution_id, id),
    CONSTRAINT charges_plan_year_fk      FOREIGN KEY (institution_id, academic_year_id)
        REFERENCES organization_academic_year (institution_id, id),
    CONSTRAINT charges_plan_modality_fk  FOREIGN KEY (institution_id, modality_id)
        REFERENCES organization_modality (institution_id, id),
    CONSTRAINT charges_plan_grade_fk     FOREIGN KEY (institution_id, grade_id)
        REFERENCES organization_grade (institution_id, id),
    CONSTRAINT charges_plan_section_fk   FOREIGN KEY (institution_id, section_id)
        REFERENCES organization_section (institution_id, id),
    CONSTRAINT charges_plan_scope_chk     CHECK (target_scope IN ('MODALITY', 'GRADE', 'SECTION')),
    CONSTRAINT charges_plan_frequency_chk CHECK (frequency IN ('MONTHLY', 'BIMONTHLY', 'ANNUAL', 'ONE_TIME')),
    CONSTRAINT charges_plan_status_chk    CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    -- Up to the 28th, so the day exists in every month of the year.
    CONSTRAINT charges_plan_due_day_chk   CHECK (due_day_of_month BETWEEN 1 AND 28),
    CONSTRAINT charges_plan_version_chk   CHECK (version >= 0)
);

CREATE INDEX charges_plan_year_idx   ON charges_plan (institution_id, academic_year_id);
CREATE INDEX charges_plan_target_idx ON charges_plan (institution_id, target_scope, target_ref_id);

CREATE TABLE charges_plan_item (
    institution_id     UUID        NOT NULL,
    id                 UUID        NOT NULL,
    plan_id            UUID        NOT NULL,
    payment_concept_id UUID        NOT NULL,
    periods_count      INTEGER     NOT NULL,
    first_period_on    DATE        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT charges_plan_item_pk         PRIMARY KEY (institution_id, id),
    CONSTRAINT charges_plan_item_plan_fk    FOREIGN KEY (institution_id, plan_id)
        REFERENCES charges_plan (institution_id, id),
    CONSTRAINT charges_plan_item_concept_fk FOREIGN KEY (institution_id, payment_concept_id)
        REFERENCES catalog_payment_concept (institution_id, id),
    -- A concept appears once in a plan.
    CONSTRAINT charges_plan_item_concept_uq UNIQUE (institution_id, plan_id, payment_concept_id),
    CONSTRAINT charges_plan_item_periods_chk CHECK (periods_count BETWEEN 1 AND 36),
    -- The first day of a month, inside a window that rules out a typing mistake (a year 0202, a year 20271).
    CONSTRAINT charges_plan_item_first_period_chk CHECK (
        first_period_on BETWEEN date '2000-01-01' AND date '2100-12-31'
        AND EXTRACT(DAY FROM first_period_on) = 1)
);

-- The concept side of the foreign key. The plan side is the prefix of the unique constraint.
CREATE INDEX charges_plan_item_concept_idx ON charges_plan_item (institution_id, payment_concept_id);

-- The life of a plan. See the header for the rules; every refusal is a restrict_violation.
CREATE FUNCTION charges_plan_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a charge plan is never deleted, it is archived' USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'a charge plan is born as a draft' USING ERRCODE = 'restrict_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.institution_id, NEW.id, NEW.academic_year_id, NEW.target_scope, NEW.target_ref_id, NEW.created_at)
            IS DISTINCT FROM
       (OLD.institution_id, OLD.id, OLD.academic_year_id, OLD.target_scope, OLD.target_ref_id, OLD.created_at) THEN
        RAISE EXCEPTION 'the year, the target and the creation of a charge plan never change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status = 'ARCHIVED' THEN
        RAISE EXCEPTION 'an archived charge plan never changes' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NOT (NEW.status = OLD.status
            OR (OLD.status = 'DRAFT' AND NEW.status = 'ACTIVE')
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'ARCHIVED')) THEN
        RAISE EXCEPTION 'a charge plan goes from draft to active and from active to archived, never back'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status = 'ACTIVE'
            AND (NEW.frequency, NEW.due_day_of_month) IS DISTINCT FROM (OLD.frequency, OLD.due_day_of_month) THEN
        RAISE EXCEPTION 'an active charge plan keeps its frequency and its due day'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status = 'ACTIVE' AND NOT EXISTS (
            SELECT 1 FROM charges_plan_item
             WHERE institution_id = NEW.institution_id AND plan_id = NEW.id) THEN
        RAISE EXCEPTION 'a charge plan without items is never activated' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- The items of a plan change only while the plan is a draft. The plan row is read FOR SHARE: a concurrent activation
-- (which updates it) waits for this statement, and this statement waits for it, so an item never slips into a plan that
-- is being activated. A plan that is not found is left to the foreign key, which refuses the row with its own error.
CREATE FUNCTION charges_plan_item_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    plan_institution UUID;
    plan_ref         UUID;
    plan_status      TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        plan_institution := OLD.institution_id;
        plan_ref := OLD.plan_id;
    ELSE
        plan_institution := NEW.institution_id;
        plan_ref := NEW.plan_id;
    END IF;
    SELECT status INTO plan_status FROM charges_plan
     WHERE institution_id = plan_institution AND id = plan_ref
       FOR SHARE;
    IF FOUND AND plan_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'the items of a charge plan change only while it is a draft'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'UPDATE'
            AND (NEW.institution_id, NEW.id, NEW.plan_id) IS DISTINCT FROM (OLD.institution_id, OLD.id, OLD.plan_id) THEN
        RAISE EXCEPTION 'an item never moves to another plan' USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION charges_plan_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION charges_plan_item_guard() FROM PUBLIC;

CREATE TRIGGER charges_plan_guard_trg
    BEFORE INSERT OR UPDATE OR DELETE ON charges_plan
    FOR EACH ROW EXECUTE FUNCTION charges_plan_guard();
CREATE TRIGGER charges_plan_item_guard_trg
    BEFORE INSERT OR UPDATE OR DELETE ON charges_plan_item
    FOR EACH ROW EXECUTE FUNCTION charges_plan_item_guard();

ALTER TABLE charges_plan      ENABLE ROW LEVEL SECURITY;
ALTER TABLE charges_plan      FORCE  ROW LEVEL SECURITY;
ALTER TABLE charges_plan_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE charges_plan_item FORCE  ROW LEVEL SECURITY;

CREATE POLICY charges_plan_institution_isolation ON charges_plan
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY charges_plan_item_institution_isolation ON charges_plan_item
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON charges_plan      FROM PUBLIC;
REVOKE ALL ON charges_plan_item FROM PUBLIC;

-- No DELETE on a plan for anybody. A plan changes only its name, frequency, due day, status and version; an item is
-- inserted and, while the plan is a draft, deleted, and is never updated (no UPDATE grant).
GRANT SELECT, INSERT ON charges_plan TO confia_admin_app;
GRANT UPDATE (name, frequency, due_day_of_month, status, version, updated_at) ON charges_plan TO confia_admin_app;
GRANT SELECT, INSERT, DELETE ON charges_plan_item TO confia_admin_app;
GRANT SELECT ON charges_plan      TO confia_readonly;
GRANT SELECT ON charges_plan_item TO confia_readonly;
-- confia_portal_app: no grant, staff data. confia_owner: schema owner, without BYPASSRLS.

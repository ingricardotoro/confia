-- Twenty-ninth migration (F2, deliverable 3; docs/cortes/catalog-tax-rates.md). Creates the tax rates of the catalog: a
-- rate has a stable identity (a code and a name) and a history of validities, each with a percentage, so that a change of
-- percentage is a new validity and never an edit (docs/04-cumplimiento-fiscal-sar.md, section 7). It also lets a taxable
-- payment concept point at its tax rate.
--
-- NO PERCENTAGE IS SEEDED HERE OR ANYWHERE IN THE CODE: the administrator loads the figures, and the general rate in force
-- in Honduras is PENDING VALIDATION against the resolution of the SAR.
--
-- INV-11 applied to rates: two validities of the same tax rate never overlap in time. The database refuses the overlap
-- with an exclusion constraint over a date range (the same technique as the prices of V28; btree_gist already lives in
-- the schema "extensions"), so two concurrent requests cannot both win.
--
-- A validity is immutable (the same rule as a price, INV-12 anticipated): the one change a row of
-- catalog_tax_rate_period ever accepts is closing an open validity once (valid_to from null to a date). It is enforced
-- twice: the application role holds UPDATE on the single column valid_to, and a trigger refuses any other change and any
-- second closing even to the owner. Nobody deletes: no DELETE grant, and a trigger refuses it to the owner too. A tax
-- rate itself changes only its name, its active flag and the version that goes with them.
--
-- The institution comes from the session, never from a request: forced row-level security keeps one institution from
-- reading or writing the rows of another.

CREATE TABLE catalog_tax_rate (
    institution_id UUID         NOT NULL,
    id             UUID         NOT NULL,
    code           VARCHAR(30)  NOT NULL,
    name           VARCHAR(120) NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    version        BIGINT       NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT catalog_tax_rate_pk         PRIMARY KEY (institution_id, id),
    -- The same whitelist as the code of a payment concept.
    CONSTRAINT catalog_tax_rate_code_chk   CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,29}$'),
    CONSTRAINT catalog_tax_rate_version_chk CHECK (version >= 0)
);

-- One code per institution, whatever its capitals. The name ends in _uq so the adapter translates a violation of it into
-- the duplicate-tax-rate-code answer.
CREATE UNIQUE INDEX catalog_tax_rate_code_uq ON catalog_tax_rate (institution_id, lower(code));

CREATE TABLE catalog_tax_rate_period (
    institution_id UUID         NOT NULL,
    id             UUID         NOT NULL,
    tax_rate_id    UUID         NOT NULL,
    rate_percent   NUMERIC(7,4) NOT NULL,
    valid_from     DATE         NOT NULL,
    valid_to       DATE,
    created_by     UUID         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT catalog_tax_rate_period_pk       PRIMARY KEY (institution_id, id),
    CONSTRAINT catalog_tax_rate_period_rate_fk  FOREIGN KEY (institution_id, tax_rate_id)
        REFERENCES catalog_tax_rate (institution_id, id),
    -- A percentage is greater than zero and at most one hundred, with the four decimals of the column.
    CONSTRAINT catalog_tax_rate_period_percent_chk CHECK (rate_percent > 0 AND rate_percent <= 100),
    CONSTRAINT catalog_tax_rate_period_dates_chk   CHECK (valid_to IS NULL OR valid_to >= valid_from),
    -- Days outside this window are a typing mistake (a year 0202, a year 20271), never a real validity.
    CONSTRAINT catalog_tax_rate_period_window_chk  CHECK (
        valid_from BETWEEN date '2000-01-01' AND date '2100-12-31'
        AND (valid_to IS NULL OR valid_to BETWEEN date '2000-01-01' AND date '2100-12-31')),
    -- INV-11. A null valid_to is an unbounded range, so an open validity overlaps every later one.
    CONSTRAINT catalog_tax_rate_period_validity_excl EXCLUDE USING gist (
        institution_id WITH =, tax_rate_id WITH =,
        daterange(valid_from, valid_to, '[]') WITH &&)
);

CREATE INDEX catalog_tax_rate_period_rate_idx ON catalog_tax_rate_period (institution_id, tax_rate_id);

-- The code and the creation of a tax rate never change: only its name, its active flag and the version that goes with them.
CREATE FUNCTION catalog_tax_rate_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a tax rate is never deleted, it is deactivated' USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.institution_id, NEW.id, NEW.code, NEW.created_at)
            IS DISTINCT FROM
       (OLD.institution_id, OLD.id, OLD.code, OLD.created_at) THEN
        RAISE EXCEPTION 'the code of a tax rate never changes' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- A validity is never edited or deleted. The one change allowed is the closing of an open validity.
CREATE FUNCTION catalog_tax_rate_period_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a tax rate validity is never deleted, it is closed' USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.institution_id, NEW.id, NEW.tax_rate_id, NEW.rate_percent, NEW.valid_from, NEW.created_by, NEW.created_at)
            IS DISTINCT FROM
       (OLD.institution_id, OLD.id, OLD.tax_rate_id, OLD.rate_percent, OLD.valid_from, OLD.created_by, OLD.created_at) THEN
        RAISE EXCEPTION 'a tax rate validity is never edited, only its open validity is closed'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.valid_to IS NOT NULL OR NEW.valid_to IS NULL THEN
        RAISE EXCEPTION 'a validity is closed once, from open to a date' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION catalog_tax_rate_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION catalog_tax_rate_period_guard() FROM PUBLIC;

CREATE TRIGGER catalog_tax_rate_guard_trg
    BEFORE UPDATE OR DELETE ON catalog_tax_rate
    FOR EACH ROW EXECUTE FUNCTION catalog_tax_rate_guard();
CREATE TRIGGER catalog_tax_rate_period_guard_trg
    BEFORE UPDATE OR DELETE ON catalog_tax_rate_period
    FOR EACH ROW EXECUTE FUNCTION catalog_tax_rate_period_guard();

ALTER TABLE catalog_tax_rate        ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog_tax_rate        FORCE  ROW LEVEL SECURITY;
ALTER TABLE catalog_tax_rate_period ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog_tax_rate_period FORCE  ROW LEVEL SECURITY;

CREATE POLICY catalog_tax_rate_institution_isolation ON catalog_tax_rate
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY catalog_tax_rate_period_institution_isolation ON catalog_tax_rate_period
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON catalog_tax_rate        FROM PUBLIC;
REVOKE ALL ON catalog_tax_rate_period FROM PUBLIC;

-- No DELETE for anybody. A tax rate changes only its name, its active flag and its version; a validity changes only its
-- valid_to.
GRANT SELECT, INSERT ON catalog_tax_rate TO confia_admin_app;
GRANT UPDATE (name, is_active, version, updated_at) ON catalog_tax_rate TO confia_admin_app;
GRANT SELECT, INSERT ON catalog_tax_rate_period TO confia_admin_app;
GRANT UPDATE (valid_to) ON catalog_tax_rate_period TO confia_admin_app;
GRANT SELECT ON catalog_tax_rate        TO confia_readonly;
GRANT SELECT ON catalog_tax_rate_period TO confia_readonly;
-- confia_portal_app: no grant, staff data. confia_owner: schema owner, without BYPASSRLS.

-- The tax rate of a payment concept. It is optional in the database: the taxable concepts that existed before this
-- migration have none until somebody edits them (the API requires one for a taxable concept on every create and edit). A
-- foreign key whose column is null is not checked, and the composite key proves that the rate belongs to the same
-- institution. A concept that is not taxable never has a tax rate.
ALTER TABLE catalog_payment_concept ADD COLUMN tax_rate_id UUID;
ALTER TABLE catalog_payment_concept ADD CONSTRAINT catalog_payment_concept_tax_rate_fk
    FOREIGN KEY (institution_id, tax_rate_id) REFERENCES catalog_tax_rate (institution_id, id);
ALTER TABLE catalog_payment_concept ADD CONSTRAINT catalog_payment_concept_tax_rate_chk
    CHECK (is_taxable OR tax_rate_id IS NULL);
CREATE INDEX catalog_payment_concept_tax_rate_idx ON catalog_payment_concept (institution_id, tax_rate_id);

-- Column-level grants are additive: the application role may now also write the tax rate of a concept.
GRANT UPDATE (tax_rate_id) ON catalog_payment_concept TO confia_admin_app;

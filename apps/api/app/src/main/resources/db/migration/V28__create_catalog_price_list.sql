-- Twenty-eighth migration (F2, deliverable 2; docs/cortes/catalog-price-lists.md). Creates the price lists of the
-- catalog: a list belongs to an academic year and, optionally, to a modality or a grade, and holds one price per
-- payment concept for a validity (a closed date range, open on the right when valid_to is null). A change of price is a
-- new validity; the previous one stays untouched except that it is closed.
--
-- INV-11 (docs/02-modelo-de-dominio.md, section 3.3): two prices of the same concept of the same list never overlap in
-- time. The database refuses the overlap with an exclusion constraint over a date range, so two concurrent requests
-- cannot both win. That needs btree_gist (equality of uuid inside a gist index): a trusted extension from PostgreSQL 13,
-- which confia_owner, the owner of the database, may install. PostgreSQL gives the objects of a trusted extension to
-- the bootstrap superuser, not to the one who installs it, so the extension lives in its own schema and the public
-- schema keeps holding only objects of confia_owner (a rule the migration tests check). A gist index finds the operator
-- class of uuid by its type, whatever schema it is in, so no role needs to see that schema.
--
-- REQUIREMENT OF THE ENVIRONMENT: CREATE EXTENSION needs the CREATE privilege on the database (confia_owner owns it) and
-- the extension must be available on the server (the contrib package; managed services offer it). A platform
-- administrator may create it beforehand in the schema "extensions" ("CREATE EXTENSION btree_gist SCHEMA extensions"), and
-- then this statement does nothing. See docs/03-seguridad.md.
--
-- INV-12, anticipated (there are no charges yet): a price is immutable. The only change a row of
-- catalog_price_list_item ever accepts is closing an open validity once (valid_to from null to a date). It is enforced
-- twice: the application role holds UPDATE on the single column valid_to, and a trigger refuses any other change and
-- any second closing even to the owner. Nobody deletes: no DELETE grant, and a trigger refuses it to the owner too.
--
-- The institution comes from the session, never from a request: forced row-level security keeps one institution from
-- reading or writing the rows of another. This migration also adds the optimistic version to the payment concepts (it
-- closes finding B-1 of the first cut: two concurrent edits no longer overwrite each other silently).

CREATE SCHEMA IF NOT EXISTS extensions;
CREATE EXTENSION IF NOT EXISTS btree_gist SCHEMA extensions;

ALTER TABLE catalog_payment_concept ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE catalog_payment_concept ADD CONSTRAINT catalog_payment_concept_version_chk CHECK (version >= 0);

CREATE TABLE catalog_price_list (
    institution_id   UUID         NOT NULL,
    id               UUID         NOT NULL,
    academic_year_id UUID         NOT NULL,
    name             VARCHAR(120) NOT NULL,
    scope            TEXT         NOT NULL,
    scope_ref_id     UUID,
    status           TEXT         NOT NULL DEFAULT 'ACTIVE',
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    -- The reference of the scope, split by scope so each half is a real foreign key. A foreign key whose column is
    -- null is not checked, so a list of another scope leaves the other column empty and the database proves that the
    -- modality or the grade exists in the same institution.
    modality_id      UUID GENERATED ALWAYS AS (CASE WHEN scope = 'MODALITY' THEN scope_ref_id END) STORED,
    grade_id         UUID GENERATED ALWAYS AS (CASE WHEN scope = 'GRADE' THEN scope_ref_id END) STORED,
    CONSTRAINT catalog_price_list_pk          PRIMARY KEY (institution_id, id),
    CONSTRAINT catalog_price_list_year_fk     FOREIGN KEY (institution_id, academic_year_id)
        REFERENCES organization_academic_year (institution_id, id),
    CONSTRAINT catalog_price_list_modality_fk FOREIGN KEY (institution_id, modality_id)
        REFERENCES organization_modality (institution_id, id),
    CONSTRAINT catalog_price_list_grade_fk    FOREIGN KEY (institution_id, grade_id)
        REFERENCES organization_grade (institution_id, id),
    CONSTRAINT catalog_price_list_scope_chk   CHECK (scope IN ('INSTITUTION', 'MODALITY', 'GRADE')),
    CONSTRAINT catalog_price_list_status_chk  CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT catalog_price_list_scope_ref_chk CHECK (
        (scope = 'INSTITUTION' AND scope_ref_id IS NULL)
        OR (scope IN ('MODALITY', 'GRADE') AND scope_ref_id IS NOT NULL)),
    CONSTRAINT catalog_price_list_version_chk CHECK (version >= 0)
);

CREATE INDEX catalog_price_list_year_idx ON catalog_price_list (institution_id, academic_year_id);

CREATE TABLE catalog_price_list_item (
    institution_id     UUID          NOT NULL,
    id                 UUID          NOT NULL,
    price_list_id      UUID          NOT NULL,
    payment_concept_id UUID          NOT NULL,
    amount             NUMERIC(14,4) NOT NULL,
    currency           CHAR(3)       NOT NULL,
    valid_from         DATE          NOT NULL,
    valid_to           DATE,
    created_by         UUID          NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT catalog_price_list_item_pk          PRIMARY KEY (institution_id, id),
    CONSTRAINT catalog_price_list_item_list_fk     FOREIGN KEY (institution_id, price_list_id)
        REFERENCES catalog_price_list (institution_id, id),
    CONSTRAINT catalog_price_list_item_concept_fk  FOREIGN KEY (institution_id, payment_concept_id)
        REFERENCES catalog_payment_concept (institution_id, id),
    CONSTRAINT catalog_price_list_item_amount_chk   CHECK (amount > 0),
    -- The enabled currencies, as CurrencyCode of the kernel and organization_institution.default_currency.
    CONSTRAINT catalog_price_list_item_currency_chk CHECK (currency IN ('HNL', 'USD')),
    CONSTRAINT catalog_price_list_item_dates_chk    CHECK (valid_to IS NULL OR valid_to >= valid_from),
    -- Days outside this window are a typing mistake (a year 0202, a year 20271), never a real validity.
    CONSTRAINT catalog_price_list_item_window_chk   CHECK (
        valid_from BETWEEN date '2000-01-01' AND date '2100-12-31'
        AND (valid_to IS NULL OR valid_to BETWEEN date '2000-01-01' AND date '2100-12-31')),
    -- At most the decimals of the minor unit of HNL and USD, so what is stored is what is shown. The column keeps four
    -- decimals, the type of every amount of the system (ADR-0004), so a later currency with more digits needs no change.
    CONSTRAINT catalog_price_list_item_minor_unit_chk CHECK (amount = round(amount, 2)),
    -- INV-11. A null valid_to is an unbounded range, so an open validity overlaps every later one.
    CONSTRAINT catalog_price_list_item_validity_excl EXCLUDE USING gist (
        institution_id WITH =, price_list_id WITH =, payment_concept_id WITH =,
        daterange(valid_from, valid_to, '[]') WITH &&)
);

CREATE INDEX catalog_price_list_item_list_idx ON catalog_price_list_item (institution_id, price_list_id);
CREATE INDEX catalog_price_list_item_concept_idx ON catalog_price_list_item (institution_id, payment_concept_id);

-- The year, the scope and the reference of a list never change once it exists: only its name, its status and the
-- version that goes with them.
CREATE FUNCTION catalog_price_list_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a price list is never deleted, it is archived' USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.institution_id, NEW.id, NEW.academic_year_id, NEW.scope, NEW.scope_ref_id, NEW.created_at)
            IS DISTINCT FROM
       (OLD.institution_id, OLD.id, OLD.academic_year_id, OLD.scope, OLD.scope_ref_id, OLD.created_at) THEN
        RAISE EXCEPTION 'the year, the scope and the reference of a price list never change'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

-- A price is never edited or deleted. The one change allowed is the closing of an open validity.
CREATE FUNCTION catalog_price_list_item_guard() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a price is never deleted, its validity is closed' USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.institution_id, NEW.id, NEW.price_list_id, NEW.payment_concept_id, NEW.amount, NEW.currency,
            NEW.valid_from, NEW.created_by, NEW.created_at)
            IS DISTINCT FROM
       (OLD.institution_id, OLD.id, OLD.price_list_id, OLD.payment_concept_id, OLD.amount, OLD.currency,
            OLD.valid_from, OLD.created_by, OLD.created_at) THEN
        RAISE EXCEPTION 'a price is never edited, only its open validity is closed'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.valid_to IS NOT NULL OR NEW.valid_to IS NULL THEN
        RAISE EXCEPTION 'a validity is closed once, from open to a date' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION catalog_price_list_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION catalog_price_list_item_guard() FROM PUBLIC;

CREATE TRIGGER catalog_price_list_guard_trg
    BEFORE UPDATE OR DELETE ON catalog_price_list
    FOR EACH ROW EXECUTE FUNCTION catalog_price_list_guard();
CREATE TRIGGER catalog_price_list_item_guard_trg
    BEFORE UPDATE OR DELETE ON catalog_price_list_item
    FOR EACH ROW EXECUTE FUNCTION catalog_price_list_item_guard();

ALTER TABLE catalog_price_list      ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog_price_list      FORCE  ROW LEVEL SECURITY;
ALTER TABLE catalog_price_list_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog_price_list_item FORCE  ROW LEVEL SECURITY;

CREATE POLICY catalog_price_list_institution_isolation ON catalog_price_list
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY catalog_price_list_item_institution_isolation ON catalog_price_list_item
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON catalog_price_list      FROM PUBLIC;
REVOKE ALL ON catalog_price_list_item FROM PUBLIC;

-- The payment concept already granted UPDATE on the whole table (V27); the application only edits these columns, so the
-- code and the institution can no longer be changed by the application role (the code is immutable once created).
REVOKE UPDATE ON catalog_payment_concept FROM confia_admin_app;
GRANT UPDATE (name, category, is_taxable, is_recurring, is_active, version, updated_at)
    ON catalog_payment_concept TO confia_admin_app;

-- No DELETE for anybody. A list changes only its name, its status and its version; a price changes only its valid_to.
GRANT SELECT, INSERT ON catalog_price_list TO confia_admin_app;
GRANT UPDATE (name, status, version, updated_at) ON catalog_price_list TO confia_admin_app;
GRANT SELECT, INSERT ON catalog_price_list_item TO confia_admin_app;
GRANT UPDATE (valid_to) ON catalog_price_list_item TO confia_admin_app;
GRANT SELECT ON catalog_price_list      TO confia_readonly;
GRANT SELECT ON catalog_price_list_item TO confia_readonly;
-- confia_portal_app: no grant, staff data. confia_owner: schema owner, without BYPASSRLS.

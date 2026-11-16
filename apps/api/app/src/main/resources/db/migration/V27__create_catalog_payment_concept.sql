-- Twenty-seventh migration (F2, deliverable 1; docs/cortes/catalog-payment-concepts.md). Creates the first table of
-- the catalog module: what an institution charges for (a payment concept with its category, whether it recurs and
-- whether it is taxable). It holds no price: prices arrive with the price lists (cut 2), and the tax rate and the
-- accounting mapping of a concept arrive with their own cuts.
--
-- The code is unique per institution WITHOUT distinguishing capitals, by a unique index over lower(code); the
-- database itself settles two concurrent creations. The category is a closed set, checked here as well as by the
-- domain. Nothing is ever deleted (no DELETE grant): a concept is deactivated with an UPDATE, so nothing financial
-- that will reference it ever dangles. The institution comes from the session, never from a request: forced row-level
-- security keeps one institution from reading or writing the rows of another.

CREATE TABLE catalog_payment_concept (
    institution_id UUID         NOT NULL,
    id             UUID         NOT NULL,
    code           VARCHAR(30)  NOT NULL,
    name           VARCHAR(120) NOT NULL,
    category       TEXT         NOT NULL,
    is_taxable     BOOLEAN      NOT NULL,
    is_recurring   BOOLEAN      NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT catalog_payment_concept_pk           PRIMARY KEY (institution_id, id),
    -- The code is a whitelist: letters, digits, underscore and hyphen, starting with a letter or digit. It keeps
    -- spaces, accents, control and format characters (zero-width, right-to-left override) and a leading symbol out.
    CONSTRAINT catalog_payment_concept_code_chk     CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,29}$'),
    CONSTRAINT catalog_payment_concept_category_chk CHECK (category IN
        ('TUITION', 'ENROLLMENT', 'TRANSPORT', 'MATERIAL', 'FINE', 'OTHER'))
);

-- One code per institution, whatever its capitals. The name ends in _uq so the adapter translates a violation of it
-- into the duplicate-payment-concept-code answer.
CREATE UNIQUE INDEX catalog_payment_concept_code_uq
    ON catalog_payment_concept (institution_id, lower(code));

ALTER TABLE catalog_payment_concept ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalog_payment_concept FORCE  ROW LEVEL SECURITY;

CREATE POLICY catalog_payment_concept_institution_isolation ON catalog_payment_concept
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON catalog_payment_concept FROM PUBLIC;

-- No DELETE for anybody: a concept is deactivated, never deleted.
GRANT SELECT, INSERT, UPDATE ON catalog_payment_concept TO confia_admin_app;
GRANT SELECT ON catalog_payment_concept TO confia_readonly;
-- confia_portal_app: no grant, staff data. confia_owner: schema owner, without BYPASSRLS.

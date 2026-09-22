-- First business migration (F0 change 5, PR A2; design.md decision 6; specs/organization/spec.md).
-- organization_institution is the tenant root (ADR-0009, ADR-0017 closed catalogue): its own
-- primary key IS the institution discriminator, so this table carries no institution_id column of
-- its own (ADR-0017, catalogue note under the table; design.md decision 6, point 3).
CREATE TABLE organization_institution (
    id                UUID         PRIMARY KEY,
    legal_name        VARCHAR(200) NOT NULL,
    trade_name        VARCHAR(200),
    rtn               VARCHAR(20)  NOT NULL,
    address           VARCHAR(500) NOT NULL,
    default_currency  CHAR(3)      NOT NULL,
    locale            VARCHAR(35)  NOT NULL,
    timezone          VARCHAR(64)  NOT NULL,
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT organization_institution_rtn_digits CHECK (rtn ~ '^[0-9]{1,20}$'),
    CONSTRAINT organization_institution_currency   CHECK (default_currency IN ('HNL', 'USD'))
);

-- Technical guard, not a fiscal rule (docs/04-cumplimiento-fiscal-sar.md section 1; docs/09
-- lines 118-122): the SAR RTN format is still pending validation with the institution's
-- accountant, so this column only repeats the domain aggregate's own 1-to-20-digit guard
-- (Institution.MAX_RTN_DIGITS) as a second, database-level line of defense. It never invents a
-- fiscal format.
COMMENT ON COLUMN organization_institution.rtn IS
  'Technical guard of 1 to 20 digits inherited from the domain aggregate, NOT a fiscal rule. '
  'The SAR format is pending primary-source validation (docs/04 section 1, docs/09 lines 118-122).';

-- Row-level security (ADR-0009 "Implementación del aislamiento"; ADR-0017 regla 1). FORCE matters:
-- without it the table owner (confia_owner, who runs this very migration) would silently bypass
-- the policy, which would make every isolation test pass for the wrong reason (design.md decision
-- 4; ADR-0009 line 131).
ALTER TABLE organization_institution ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_institution FORCE  ROW LEVEL SECURITY;

-- NULLIF(..., '') turns an empty-string session setting into NULL before the ::uuid cast, so a
-- session with app.institution_id set to '' denies (zero rows) instead of raising a cast error;
-- current_setting(..., true) already turns a completely absent setting into NULL, which the
-- equality against id then denies the same way (docs/03-seguridad.md section 6.2, rule 4: closed
-- failure). WITH CHECK is explicit, not inherited from USING: with FORCE ROW LEVEL SECURITY the
-- policy also constrains the owner's own INSERT statements, so seeding a row requires setting
-- app.institution_id to the very id being inserted (design.md decision 6, points 1 and 2).
CREATE POLICY organization_institution_isolation ON organization_institution
    USING      (id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

-- Privilege matrix (docs/03-seguridad.md section 6.1): organization_institution is a non-financial
-- business table, so UPDATE is granted (is_active toggles) but DELETE never is (CLAUDE.md rule 5,
-- "nada financiero ni de negocio clave se borra"; design.md decision 6, point 5).
GRANT SELECT, INSERT, UPDATE ON organization_institution TO confia_admin_app;
GRANT SELECT                 ON organization_institution TO confia_readonly;
-- confia_portal_app: no privilege at all on this table (docs/03 section 6.1's short, explicit
-- list — no portal use case touches the institution root yet). confia_backup reads everything
-- through pg_read_all_data, granted once in the test-only role script.

-- ADR-0017: flyway_schema_history is one of the four closed-catalogue technical tables, readable
-- by confia_admin_app and confia_portal_app for migration-health checks (docs/03 section 6.1).
-- The test-only role script cannot grant this before the table exists (it is Flyway's own
-- bookkeeping table); this migration is the first point where it exists, so the GRANT lives here.
GRANT SELECT ON flyway_schema_history TO confia_admin_app, confia_portal_app;

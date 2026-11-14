-- Eighteenth migration (F1, deliverable 1; docs/cortes/organization-academic-structure.md). Creates the
-- academic structure of an institution: the school year, the modalities, the grades of a modality and
-- the sections of a grade within a year. Every table carries institution_id, forced row-level
-- security, is_active and timestamps. Nothing is ever deleted (no DELETE grant): a row is
-- deactivated with an UPDATE, as organization_institution is.
--
-- Uniqueness and the single active year are enforced here, by the database itself, so two concurrent
-- requests cannot both win. The non-overlap of the date ranges of two years is NOT a constraint: it
-- needs btree_gist, an extension that this schema does not install; the use case checks it under a lock
-- on the institution row instead (ManageAcademicYears).

CREATE TABLE organization_academic_year (
    institution_id UUID         NOT NULL,
    id             UUID         NOT NULL,
    name           VARCHAR(120) NOT NULL,
    start_date     DATE         NOT NULL,
    end_date       DATE         NOT NULL,
    status         TEXT         NOT NULL DEFAULT 'PLANNED',
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT organization_academic_year_pk        PRIMARY KEY (institution_id, id),
    CONSTRAINT organization_academic_year_name_uq   UNIQUE (institution_id, name),
    CONSTRAINT organization_academic_year_dates_chk CHECK (end_date > start_date),
    CONSTRAINT organization_academic_year_status_chk CHECK (status IN ('PLANNED', 'ACTIVE', 'CLOSED'))
);

-- At most one ACTIVE year per institution.
CREATE UNIQUE INDEX organization_academic_year_one_active_uq
    ON organization_academic_year (institution_id) WHERE status = 'ACTIVE';

CREATE TABLE organization_modality (
    institution_id UUID         NOT NULL,
    id             UUID         NOT NULL,
    code           VARCHAR(30)  NOT NULL,
    name           VARCHAR(120) NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT organization_modality_pk      PRIMARY KEY (institution_id, id),
    CONSTRAINT organization_modality_code_uq UNIQUE (institution_id, code)
);

CREATE TABLE organization_grade (
    institution_id UUID         NOT NULL,
    id             UUID         NOT NULL,
    modality_id    UUID         NOT NULL,
    name           VARCHAR(120) NOT NULL,
    ordinal        INTEGER      NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT organization_grade_pk          PRIMARY KEY (institution_id, id),
    -- The modality must exist in the same institution, so a grade never crosses institutions.
    CONSTRAINT organization_grade_modality_fk FOREIGN KEY (institution_id, modality_id)
        REFERENCES organization_modality (institution_id, id),
    CONSTRAINT organization_grade_name_uq     UNIQUE (institution_id, modality_id, name),
    CONSTRAINT organization_grade_ordinal_uq UNIQUE (institution_id, modality_id, ordinal),
    CONSTRAINT organization_grade_ordinal_chk CHECK (ordinal >= 0)
);

CREATE TABLE organization_section (
    institution_id   UUID         NOT NULL,
    id               UUID         NOT NULL,
    academic_year_id UUID         NOT NULL,
    grade_id         UUID         NOT NULL,
    name             VARCHAR(120) NOT NULL,
    capacity         INTEGER,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT organization_section_pk          PRIMARY KEY (institution_id, id),
    CONSTRAINT organization_section_year_fk     FOREIGN KEY (institution_id, academic_year_id)
        REFERENCES organization_academic_year (institution_id, id),
    CONSTRAINT organization_section_grade_fk    FOREIGN KEY (institution_id, grade_id)
        REFERENCES organization_grade (institution_id, id),
    CONSTRAINT organization_section_name_uq     UNIQUE (institution_id, academic_year_id, grade_id, name),
    CONSTRAINT organization_section_capacity_chk CHECK (capacity IS NULL OR capacity > 0)
);

-- The grade side of the section foreign key and the filters of the list endpoints.
CREATE INDEX organization_section_grade_idx ON organization_section (institution_id, grade_id);

ALTER TABLE organization_academic_year ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_academic_year FORCE  ROW LEVEL SECURITY;
ALTER TABLE organization_modality      ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_modality      FORCE  ROW LEVEL SECURITY;
ALTER TABLE organization_grade         ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_grade         FORCE  ROW LEVEL SECURITY;
ALTER TABLE organization_section       ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_section       FORCE  ROW LEVEL SECURITY;

CREATE POLICY organization_academic_year_institution_isolation ON organization_academic_year
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY organization_modality_institution_isolation ON organization_modality
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY organization_grade_institution_isolation ON organization_grade
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY organization_section_institution_isolation ON organization_section
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON organization_academic_year FROM PUBLIC;
REVOKE ALL ON organization_modality      FROM PUBLIC;
REVOKE ALL ON organization_grade         FROM PUBLIC;
REVOKE ALL ON organization_section       FROM PUBLIC;

-- No DELETE for anybody: nothing of the academic structure is deleted, it is deactivated.
GRANT SELECT, INSERT, UPDATE ON organization_academic_year TO confia_admin_app;
GRANT SELECT, INSERT, UPDATE ON organization_modality      TO confia_admin_app;
GRANT SELECT, INSERT, UPDATE ON organization_grade         TO confia_admin_app;
GRANT SELECT, INSERT, UPDATE ON organization_section       TO confia_admin_app;
GRANT SELECT ON organization_academic_year TO confia_readonly;
GRANT SELECT ON organization_modality      TO confia_readonly;
GRANT SELECT ON organization_grade         TO confia_readonly;
GRANT SELECT ON organization_section       TO confia_readonly;
-- confia_portal_app: no grant, staff data. confia_owner: schema owner, without BYPASSRLS.
-- confia_backup: reads through pg_read_all_data, which never bypasses row-level security.

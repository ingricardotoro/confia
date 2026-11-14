-- Twentieth migration (F1, deliverable 3; docs/cortes/enrollments.md). Creates the enrollment of a student
-- in a section of an academic year and the immutable history of its section transfers.
--
-- students_enrollment carries institution_id, forced row-level security, a status and timestamps. The
-- academic year is the one of the section and is kept here only so that INV-31 can be a plain unique index
-- (the application derives it from the section and never takes it from the client). The grade and the
-- modality are derived from the section and are not stored. Nothing is ever deleted (no DELETE grant): an
-- enrollment leaves ACTIVE for WITHDRAWN, TRANSFERRED (to another institution) or GRADUATED, all final.
--
-- INV-31 is enforced here, by the database itself, so two concurrent requests cannot both win: a student has
-- at most one ACTIVE enrollment per academic year (a partial unique index). After a withdrawal the student
-- can be enrolled again in the same year. The capacity of a section is NOT a constraint (it needs a count
-- of other rows): the use case checks it under FOR UPDATE on the row of the section.
--
-- students_enrollment_transfer is the history of the sections an enrollment has been in. It is insert-only
-- (no UPDATE and no DELETE grant for anybody): the history is neither edited nor erased.

CREATE TABLE students_enrollment (
    institution_id    UUID         NOT NULL,
    id                UUID         NOT NULL,
    student_id        UUID         NOT NULL,
    section_id        UUID         NOT NULL,
    academic_year_id  UUID         NOT NULL,
    enrolled_on       DATE         NOT NULL,
    status            TEXT         NOT NULL DEFAULT 'ACTIVE',
    withdrawn_on      DATE,
    withdrawal_reason VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT students_enrollment_pk          PRIMARY KEY (institution_id, id),
    -- The student, the section and the year must exist in the same institution, so an enrollment never
    -- crosses institutions.
    CONSTRAINT students_enrollment_student_fk  FOREIGN KEY (institution_id, student_id)
        REFERENCES students_student (institution_id, id),
    CONSTRAINT students_enrollment_section_fk  FOREIGN KEY (institution_id, section_id)
        REFERENCES organization_section (institution_id, id),
    CONSTRAINT students_enrollment_year_fk     FOREIGN KEY (institution_id, academic_year_id)
        REFERENCES organization_academic_year (institution_id, id),
    CONSTRAINT students_enrollment_status_chk  CHECK (status IN ('ACTIVE', 'WITHDRAWN', 'TRANSFERRED', 'GRADUATED')),
    -- The exit date is present exactly when the enrollment is not active, and not before the enrollment date.
    CONSTRAINT students_enrollment_exit_chk    CHECK ((status = 'ACTIVE') = (withdrawn_on IS NULL)),
    CONSTRAINT students_enrollment_dates_chk   CHECK (withdrawn_on IS NULL OR withdrawn_on >= enrolled_on),
    -- The reason: none while active, required to withdraw or to leave for another institution, optional to
    -- graduate; always 3 to 500 characters once stripped.
    CONSTRAINT students_enrollment_reason_chk  CHECK (
        (status = 'ACTIVE' AND withdrawal_reason IS NULL)
        OR (status = 'GRADUATED')
        OR (status IN ('WITHDRAWN', 'TRANSFERRED') AND withdrawal_reason IS NOT NULL)),
    CONSTRAINT students_enrollment_reason_length_chk CHECK (
        withdrawal_reason IS NULL OR length(btrim(withdrawal_reason)) BETWEEN 3 AND 500)
);

-- INV-31: at most one active enrollment per student and academic year.
CREATE UNIQUE INDEX students_enrollment_active_uq
    ON students_enrollment (institution_id, student_id, academic_year_id) WHERE status = 'ACTIVE';

-- The seats taken of a section (the capacity check) and the filters of the list.
CREATE INDEX students_enrollment_section_active_idx
    ON students_enrollment (institution_id, section_id) WHERE status = 'ACTIVE';
CREATE INDEX students_enrollment_year_idx ON students_enrollment (institution_id, academic_year_id, status);
-- The enrollments of one student.
CREATE INDEX students_enrollment_student_idx ON students_enrollment (institution_id, student_id, enrolled_on);

CREATE TABLE students_enrollment_transfer (
    institution_id  UUID        NOT NULL,
    id              UUID        NOT NULL,
    enrollment_id   UUID        NOT NULL,
    from_section_id UUID        NOT NULL,
    to_section_id   UUID        NOT NULL,
    transferred_on  DATE        NOT NULL,
    reason          VARCHAR(500),
    actor_id        UUID        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT students_enrollment_transfer_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT students_enrollment_transfer_enrollment_fk FOREIGN KEY (institution_id, enrollment_id)
        REFERENCES students_enrollment (institution_id, id),
    CONSTRAINT students_enrollment_transfer_from_fk FOREIGN KEY (institution_id, from_section_id)
        REFERENCES organization_section (institution_id, id),
    CONSTRAINT students_enrollment_transfer_to_fk FOREIGN KEY (institution_id, to_section_id)
        REFERENCES organization_section (institution_id, id),
    CONSTRAINT students_enrollment_transfer_sections_chk CHECK (from_section_id <> to_section_id),
    CONSTRAINT students_enrollment_transfer_reason_chk CHECK (
        reason IS NULL OR length(btrim(reason)) BETWEEN 3 AND 500)
);

CREATE INDEX students_enrollment_transfer_enrollment_idx
    ON students_enrollment_transfer (institution_id, enrollment_id, transferred_on, created_at);

ALTER TABLE students_enrollment          ENABLE ROW LEVEL SECURITY;
ALTER TABLE students_enrollment          FORCE  ROW LEVEL SECURITY;
ALTER TABLE students_enrollment_transfer ENABLE ROW LEVEL SECURITY;
ALTER TABLE students_enrollment_transfer FORCE  ROW LEVEL SECURITY;

CREATE POLICY students_enrollment_institution_isolation ON students_enrollment
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY students_enrollment_transfer_institution_isolation ON students_enrollment_transfer
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON students_enrollment          FROM PUBLIC;
REVOKE ALL ON students_enrollment_transfer FROM PUBLIC;

-- No DELETE for anybody: no enrollment is deleted, it leaves ACTIVE.
GRANT SELECT, INSERT, UPDATE ON students_enrollment TO confia_admin_app;
-- The history is insert-only: no UPDATE and no DELETE for anybody.
GRANT SELECT, INSERT ON students_enrollment_transfer TO confia_admin_app;
GRANT SELECT ON students_enrollment          TO confia_readonly;
GRANT SELECT ON students_enrollment_transfer TO confia_readonly;
-- confia_portal_app: no grant, staff data. confia_owner: schema owner, without BYPASSRLS.

-- Seventeenth migration (F1, deliverable 2; docs/cortes/students-registry.md). Creates the student
-- registry of an institution and the per-institution counter that generates student codes.
--
-- students_student carries institution_id, forced row-level security, a status and timestamps. Nothing is
-- ever deleted (no DELETE grant): a student is deactivated with an UPDATE.
--
-- The national identity document is personal data of a minor. It is stored only encrypted by column
-- (national_id_encrypted, the v1:<dek>:<iv>:<ciphertext>:<tag> format of ColumnEncryptionService, whose
-- additional authenticated data is the row itself). Duplicates are detected without decrypting through
-- national_id_blind_index, an HMAC of the normalised document under a key of its own, unique per
-- institution while present. No character of the document is kept in plain, not even its tail: with the birth date the last
-- digits would nearly reveal the Honduran identity number. Whether a student has a document is derived
-- from national_id_encrypted IS NOT NULL, so no read has to decrypt anything.
--
-- The codes E-000001, E-000002... come from students_student_code_counter, one row per institution, taken
-- with a single INSERT ... ON CONFLICT DO UPDATE ... RETURNING: the row lock serializes concurrent
-- allocations and a rollback gives the number back. Never MAX(student_code) + 1.

CREATE TABLE students_student (
    institution_id          UUID         NOT NULL,
    id                      UUID         NOT NULL,
    student_code            VARCHAR(30)  NOT NULL,
    first_name              VARCHAR(100) NOT NULL,
    last_name               VARCHAR(100) NOT NULL,
    birth_date              DATE         NOT NULL,
    sex                     TEXT         NOT NULL,
    national_id_encrypted   TEXT,
    national_id_blind_index CHAR(64),
    status                  TEXT         NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT students_student_pk          PRIMARY KEY (institution_id, id),
    CONSTRAINT students_student_code_uq     UNIQUE (institution_id, student_code),
    CONSTRAINT students_student_sex_chk     CHECK (sex IN ('F', 'M', 'UNSPECIFIED')),
    CONSTRAINT students_student_status_chk  CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT students_student_names_chk   CHECK (length(btrim(first_name)) > 0 AND length(btrim(last_name)) > 0),
    -- The two members of the document are present together or absent together.
    CONSTRAINT students_student_national_id_chk CHECK (
        (national_id_encrypted IS NULL) = (national_id_blind_index IS NULL))
);

-- One document per institution while present: the duplicate is found without decrypting anything.
CREATE UNIQUE INDEX students_student_national_id_uq
    ON students_student (institution_id, national_id_blind_index) WHERE national_id_blind_index IS NOT NULL;

-- The default order of the list.
CREATE INDEX students_student_name_idx ON students_student (institution_id, last_name, first_name);

CREATE TABLE students_student_code_counter (
    institution_id UUID        NOT NULL,
    last_value     BIGINT      NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT students_student_code_counter_pk  PRIMARY KEY (institution_id),
    CONSTRAINT students_student_code_counter_chk CHECK (last_value >= 0)
);

ALTER TABLE students_student              ENABLE ROW LEVEL SECURITY;
ALTER TABLE students_student              FORCE  ROW LEVEL SECURITY;
ALTER TABLE students_student_code_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE students_student_code_counter FORCE  ROW LEVEL SECURITY;

CREATE POLICY students_student_institution_isolation ON students_student
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY students_student_code_counter_institution_isolation ON students_student_code_counter
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON students_student              FROM PUBLIC;
REVOKE ALL ON students_student_code_counter FROM PUBLIC;

-- No DELETE for anybody: no student is deleted, it is deactivated.
GRANT SELECT, INSERT, UPDATE ON students_student              TO confia_admin_app;
GRANT SELECT, INSERT, UPDATE ON students_student_code_counter TO confia_admin_app;
-- The read-only role never sees the encrypted document nor its blind index.
GRANT SELECT (institution_id, id, student_code, first_name, last_name, birth_date, sex,
              status, created_at, updated_at)
    ON students_student TO confia_readonly;
GRANT SELECT ON students_student_code_counter TO confia_readonly;
-- confia_portal_app: no grant, staff data. confia_owner: schema owner, without BYPASSRLS.

-- Nineteenth migration (F1, deliverable 4; docs/cortes/guardians-registry.md). Creates the registry of the
-- payment guardians of an institution, the per-institution counter that generates their codes and the links
-- between a guardian and a student, with the history of every financial responsibility split.
--
-- guardians_guardian carries institution_id, forced row-level security, a status and timestamps. Nothing is
-- ever deleted (no DELETE grant): a guardian is deactivated with an UPDATE.
--
-- The national identity document and the RTN (a fiscal datum) are stored only encrypted by column
-- (national_id_encrypted and rtn_encrypted, the v1:<dek>:<iv>:<ciphertext>:<tag> format of
-- ColumnEncryptionService, whose additional authenticated data is the row itself). Duplicate documents are
-- detected without decrypting through national_id_blind_index, an HMAC of the normalised document under the
-- versioned blind-index key of the institution (the same key set as the students, a distinct purpose), stored as
-- <version>:<64 lowercase hex> and unique per institution while present. The RTN has no blind index: the cut
-- does not ask for its uniqueness and nothing looks a guardian up by it. Whether a guardian has a document or an
-- RTN is derived from the encrypted column IS NOT NULL, so no read decrypts anything. email, phone and address
-- are sensitive but not encrypted (the cut asks for format validation and for keeping them out of the audit
-- trail and the logs); the read-only role does not see them.
--
-- The codes P-000001, P-000002... come from guardians_guardian_code_counter, one row per institution, taken with a
-- single INSERT ... ON CONFLICT DO UPDATE ... RETURNING (the same approach as the student codes). Never
-- MAX(guardian_code) + 1.
--
-- guardians_guardian_student is the link of a guardian to a student with its relationship, its percentage of
-- the financial responsibility and the days it applied. A link is "current" while valid_to IS NULL. The set of
-- the current links of a student is replaced as a whole by the application, which keeps the history: a link that
-- changes is closed (valid_to is set once, to the day it stops applying) and a new one is opened. Nothing is
-- deleted and a closed link is never edited again: the application role may only UPDATE valid_to (column grant) and
-- a trigger refuses to touch a link that is already closed.
--
-- INV-32 (the current percentages of a student add up to exactly 100) is validated by the use case, serialized
-- per student by a lock on the student row, and the database is the last line of defense: a DEFERRABLE INITIALLY
-- DEFERRED constraint trigger checks, at commit and per affected student, that the current links are none or add
-- up to exactly 100.00 (a replacement closes the old links and opens the new ones in one transaction, so the
-- intermediate states are allowed). The database also enforces what is local to the rows: one current link per
-- guardian and student, at most one primary payer and at most one billing recipient among the current links of a
-- student, and a percentage between 0.01 and 100. A link is always inserted open, and it can only be closed on a
-- day that is not in the future in America/Tegucigalpa.

CREATE TABLE guardians_guardian (
    institution_id          UUID         NOT NULL,
    id                      UUID         NOT NULL,
    guardian_code           VARCHAR(30)  NOT NULL,
    first_name              VARCHAR(100) NOT NULL,
    last_name               VARCHAR(100) NOT NULL,
    national_id_encrypted   TEXT,
    national_id_blind_index VARCHAR(80),
    rtn_encrypted           TEXT,
    email                   VARCHAR(254),
    phone                   VARCHAR(20),
    address                 VARCHAR(300),
    preferred_channel       TEXT         NOT NULL DEFAULT 'NONE',
    status                  TEXT         NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT guardians_guardian_pk              PRIMARY KEY (institution_id, id),
    CONSTRAINT guardians_guardian_code_uq         UNIQUE (institution_id, guardian_code),
    CONSTRAINT guardians_guardian_channel_chk     CHECK (preferred_channel IN ('EMAIL', 'SMS', 'WHATSAPP', 'NONE')),
    CONSTRAINT guardians_guardian_status_chk      CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT guardians_guardian_names_chk       CHECK (length(btrim(first_name)) > 0 AND length(btrim(last_name)) > 0),
    -- The two members of the document are present together or absent together.
    CONSTRAINT guardians_guardian_national_id_chk CHECK (
        (national_id_encrypted IS NULL) = (national_id_blind_index IS NULL)),
    -- A versioned digest: v<1-999>: and 64 lowercase hexadecimal characters (a NULL passes the check).
    CONSTRAINT guardians_guardian_blind_index_chk CHECK (
        national_id_blind_index ~ '^v[1-9][0-9]{0,2}:[0-9a-f]{64}$')
);

-- One document per institution while present: the duplicate is found without decrypting anything.
CREATE UNIQUE INDEX guardians_guardian_national_id_uq
    ON guardians_guardian (institution_id, national_id_blind_index) WHERE national_id_blind_index IS NOT NULL;

-- The default order of the list.
CREATE INDEX guardians_guardian_name_idx ON guardians_guardian (institution_id, last_name, first_name);

CREATE TABLE guardians_guardian_code_counter (
    institution_id UUID        NOT NULL,
    last_value     BIGINT      NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT guardians_guardian_code_counter_pk  PRIMARY KEY (institution_id),
    CONSTRAINT guardians_guardian_code_counter_chk CHECK (last_value >= 0)
);

CREATE TABLE guardians_guardian_student (
    institution_id                   UUID          NOT NULL,
    id                               UUID          NOT NULL,
    guardian_id                      UUID          NOT NULL,
    student_id                       UUID          NOT NULL,
    relationship                     TEXT          NOT NULL,
    financial_responsibility_percent NUMERIC(5, 2) NOT NULL,
    is_primary_payer                 BOOLEAN       NOT NULL DEFAULT FALSE,
    is_billing_recipient             BOOLEAN       NOT NULL DEFAULT FALSE,
    valid_from                       DATE          NOT NULL,
    valid_to                         DATE,
    created_by                       UUID          NOT NULL,
    created_at                       TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT guardians_guardian_student_pk PRIMARY KEY (institution_id, id),
    -- The guardian and the student must exist in the same institution, so a link never crosses institutions.
    CONSTRAINT guardians_guardian_student_guardian_fk FOREIGN KEY (institution_id, guardian_id)
        REFERENCES guardians_guardian (institution_id, id),
    CONSTRAINT guardians_guardian_student_student_fk FOREIGN KEY (institution_id, student_id)
        REFERENCES students_student (institution_id, id),
    CONSTRAINT guardians_guardian_student_relationship_chk CHECK (
        relationship IN ('FATHER', 'MOTHER', 'LEGAL_GUARDIAN', 'SELF', 'OTHER')),
    CONSTRAINT guardians_guardian_student_percent_chk CHECK (
        financial_responsibility_percent >= 0.01 AND financial_responsibility_percent <= 100),
    CONSTRAINT guardians_guardian_student_dates_chk CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

-- One current link per guardian and student.
CREATE UNIQUE INDEX guardians_guardian_student_current_uq
    ON guardians_guardian_student (institution_id, student_id, guardian_id) WHERE valid_to IS NULL;
-- At most one primary payer and at most one billing recipient among the current links of a student.
CREATE UNIQUE INDEX guardians_guardian_student_primary_uq
    ON guardians_guardian_student (institution_id, student_id) WHERE valid_to IS NULL AND is_primary_payer;
CREATE UNIQUE INDEX guardians_guardian_student_billing_uq
    ON guardians_guardian_student (institution_id, student_id) WHERE valid_to IS NULL AND is_billing_recipient;
-- The links (and their history) of one student, and the students a guardian is linked to.
CREATE INDEX guardians_guardian_student_student_idx
    ON guardians_guardian_student (institution_id, student_id, valid_from, created_at);
CREATE INDEX guardians_guardian_student_guardian_idx
    ON guardians_guardian_student (institution_id, guardian_id) WHERE valid_to IS NULL;

-- A closed link is history: it is never edited again. The column grant below already limits the application
-- to valid_to; this trigger makes valid_to settable once, whatever the role.
CREATE FUNCTION guardians_guardian_student_keep_closed() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.valid_to IS NOT NULL THEN
        RAISE EXCEPTION 'a closed guardian link is history and cannot be changed'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.institution_id IS DISTINCT FROM OLD.institution_id OR NEW.id IS DISTINCT FROM OLD.id
            OR NEW.guardian_id IS DISTINCT FROM OLD.guardian_id OR NEW.student_id IS DISTINCT FROM OLD.student_id
            OR NEW.relationship IS DISTINCT FROM OLD.relationship
            OR NEW.financial_responsibility_percent IS DISTINCT FROM OLD.financial_responsibility_percent
            OR NEW.is_primary_payer IS DISTINCT FROM OLD.is_primary_payer
            OR NEW.is_billing_recipient IS DISTINCT FROM OLD.is_billing_recipient
            OR NEW.valid_from IS DISTINCT FROM OLD.valid_from
            OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'a guardian link can only be closed, not edited'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF NEW.valid_to IS NOT NULL AND NEW.valid_to > (now() AT TIME ZONE 'America/Tegucigalpa')::date THEN
        RAISE EXCEPTION 'a guardian link cannot be closed on a future day'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER guardians_guardian_student_keep_closed
    BEFORE UPDATE ON guardians_guardian_student
    FOR EACH ROW EXECUTE FUNCTION guardians_guardian_student_keep_closed();

-- A link is born open: history is only made by closing a link, never by inserting a closed one.
CREATE FUNCTION guardians_guardian_student_insert_open() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.valid_to IS NOT NULL THEN
        RAISE EXCEPTION 'a guardian link must be inserted open'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER guardians_guardian_student_insert_open
    BEFORE INSERT ON guardians_guardian_student
    FOR EACH ROW EXECUTE FUNCTION guardians_guardian_student_insert_open();

-- INV-32 in the database: at commit, the current links of every affected student are none or add up to exactly
-- 100.00. Deferred, so a transaction may close the old links and open the new ones in any order. It runs with the
-- privileges of the caller, so row-level security applies to the rows it adds up.
CREATE FUNCTION guardians_guardian_student_check_split() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    link_count INTEGER;
    total      NUMERIC;
BEGIN
    SELECT count(*), COALESCE(sum(financial_responsibility_percent), 0)
        INTO link_count, total
        FROM guardians_guardian_student
        WHERE institution_id = NEW.institution_id AND student_id = NEW.student_id AND valid_to IS NULL;
    IF link_count > 0 AND total <> 100.00 THEN
        RAISE EXCEPTION 'the current guardian links of a student must add up to exactly 100 percent'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER guardians_guardian_student_check_split
    AFTER INSERT OR UPDATE ON guardians_guardian_student
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION guardians_guardian_student_check_split();

ALTER TABLE guardians_guardian              ENABLE ROW LEVEL SECURITY;
ALTER TABLE guardians_guardian              FORCE  ROW LEVEL SECURITY;
ALTER TABLE guardians_guardian_code_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE guardians_guardian_code_counter FORCE  ROW LEVEL SECURITY;
ALTER TABLE guardians_guardian_student      ENABLE ROW LEVEL SECURITY;
ALTER TABLE guardians_guardian_student      FORCE  ROW LEVEL SECURITY;

CREATE POLICY guardians_guardian_institution_isolation ON guardians_guardian
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY guardians_guardian_code_counter_institution_isolation ON guardians_guardian_code_counter
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);
CREATE POLICY guardians_guardian_student_institution_isolation ON guardians_guardian_student
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON guardians_guardian              FROM PUBLIC;
REVOKE ALL ON guardians_guardian_code_counter FROM PUBLIC;
REVOKE ALL ON guardians_guardian_student      FROM PUBLIC;

-- No DELETE for anybody: no guardian and no link is deleted; a guardian is deactivated and a link is closed.
GRANT SELECT, INSERT, UPDATE ON guardians_guardian              TO confia_admin_app;
GRANT SELECT, INSERT, UPDATE ON guardians_guardian_code_counter TO confia_admin_app;
-- A link is inserted, read and closed: the only column the application may update is valid_to.
GRANT SELECT, INSERT ON guardians_guardian_student TO confia_admin_app;
GRANT UPDATE (valid_to) ON guardians_guardian_student TO confia_admin_app;
-- The read-only role never sees the encrypted document, its blind index, the encrypted RTN nor the contact data.
GRANT SELECT (institution_id, id, guardian_code, first_name, last_name, preferred_channel, status,
              created_at, updated_at)
    ON guardians_guardian TO confia_readonly;
GRANT SELECT ON guardians_guardian_code_counter TO confia_readonly;
GRANT SELECT ON guardians_guardian_student      TO confia_readonly;
-- confia_portal_app: no grant, staff data. confia_owner: schema owner, without BYPASSRLS.

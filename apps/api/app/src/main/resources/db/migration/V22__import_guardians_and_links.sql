-- Twenty-second migration (F1, deliverable 6; docs/cortes/imports-guardians.md). The second cut of the bulk import
-- loads guardians and their links to the students already imported. Only what is new:
--
-- 1. imports_batch.kind admits the two new kinds. The table keeps the same shape: counts and the SHA-256 of the file,
--    never a row of it, a value or a rejected value.
-- 2. guardians_guardian.external_ref is the identifier the guardian had in the system it comes from (the legacyId column
--    of the guardians file). It is what makes the guardians import idempotent and what the links file resolves its
--    guardianLegacyId against. NULL for a guardian registered by hand (NULLs are distinct for a unique index). The unique
--    index (institution_id, external_ref) is the last word when two applications of the same file run at once. The
--    read-only role gets no grant on it: its grant on this table is by column (V19) and is not widened.

ALTER TABLE imports_batch DROP CONSTRAINT imports_batch_kind_chk;
ALTER TABLE imports_batch ADD CONSTRAINT imports_batch_kind_chk
    CHECK (kind IN ('STUDENTS', 'GUARDIANS', 'GUARDIAN_LINKS'));

ALTER TABLE guardians_guardian ADD COLUMN external_ref VARCHAR(64);
ALTER TABLE guardians_guardian ADD CONSTRAINT guardians_guardian_external_ref_chk CHECK (
    external_ref IS NULL OR (length(external_ref) > 0 AND external_ref = btrim(external_ref)));
CREATE UNIQUE INDEX guardians_guardian_external_ref_uq ON guardians_guardian (institution_id, external_ref);

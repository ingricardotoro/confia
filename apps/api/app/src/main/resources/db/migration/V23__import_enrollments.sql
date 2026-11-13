-- Twenty-third migration (F1, deliverable 6; docs/cortes/imports-enrollments.md). The third cut of the bulk import
-- loads the current enrollments of the students already imported. The only thing that changes is that
-- imports_batch.kind admits the new kind. The table keeps the same shape: counts and the SHA-256 of the file, never a
-- row of it, a value or a rejected value. The enrollments themselves are created through the hand enrollment's use
-- case and need nothing new: INV-31 already has its partial unique index in students_enrollment (V18).

ALTER TABLE imports_batch DROP CONSTRAINT imports_batch_kind_chk;
ALTER TABLE imports_batch ADD CONSTRAINT imports_batch_kind_chk
    CHECK (kind IN ('STUDENTS', 'GUARDIANS', 'GUARDIAN_LINKS', 'ENROLLMENTS'));

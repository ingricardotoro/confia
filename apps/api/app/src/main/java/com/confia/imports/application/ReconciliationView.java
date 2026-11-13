package com.confia.imports.application;

import com.confia.imports.domain.Reconciliation;
import java.time.Instant;
import java.util.UUID;

/**
 * A signed reconciliation as the web layer may see it: counts, the reference of the official roster and who signed when.
 * The difference is the active enrollments minus the official total, derived and never stored.
 */
public record ReconciliationView(UUID id, UUID academicYearId, String officialReference, int officialEnrollmentCount,
        int enrollmentsActive, int difference, KindCountView students, KindCountView guardians,
        KindCountView guardianLinks, KindCountView enrollments, UUID signedBy, Instant signedAt) {

    /** What the import of one kind created, and what its last application left rejected. */
    public record KindCountView(int created, int rejected) {

        static KindCountView of(Reconciliation.KindCount count) {
            return new KindCountView(count.created(), count.rejected());
        }
    }

    static ReconciliationView of(Reconciliation record) {
        return new ReconciliationView(record.id(), record.academicYearId(), record.officialReference(),
                record.officialEnrollmentCount(), record.enrollmentsActive(), record.difference(),
                KindCountView.of(record.students()), KindCountView.of(record.guardians()),
                KindCountView.of(record.guardianLinks()), KindCountView.of(record.enrollments()), record.signedBy(),
                record.signedAt());
    }
}

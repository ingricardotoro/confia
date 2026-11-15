package com.confia.imports.application;

import com.confia.imports.application.ReconciliationView.KindCountView;
import java.util.UUID;

/** What a reconciliation signed now for the year would record: the counts the server computes, before anyone signs. */
public record ReconciliationPreview(UUID academicYearId, int enrollmentsActive, KindCountView students,
        KindCountView guardians, KindCountView guardianLinks, KindCountView enrollments) {
}

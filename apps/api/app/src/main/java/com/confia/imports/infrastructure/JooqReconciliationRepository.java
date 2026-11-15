package com.confia.imports.infrastructure;

import static confia.generated.jooq.tables.ImportsReconciliation.IMPORTS_RECONCILIATION;

import com.confia.imports.application.Page;
import com.confia.imports.application.PageRequest;
import com.confia.imports.application.ReconciliationRepository;
import com.confia.imports.domain.Reconciliation;
import com.confia.imports.domain.Reconciliation.KindCount;
import com.confia.kernel.InstitutionId;
import confia.generated.jooq.tables.records.ImportsReconciliationRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.Condition;
import org.jooq.DSLContext;

/**
 * The jOOQ adapter of {@link ReconciliationRepository} over {@code imports_reconciliation}. It only inserts and reads:
 * the table grants the application role nothing else, and a record holds counts and a short reference, nothing of a
 * person.
 */
public final class JooqReconciliationRepository implements ReconciliationRepository {

    private final DSLContext dsl;

    public JooqReconciliationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institutionId, Reconciliation record) {
        dsl.insertInto(IMPORTS_RECONCILIATION)
                .set(IMPORTS_RECONCILIATION.INSTITUTION_ID, institutionId.value())
                .set(IMPORTS_RECONCILIATION.ID, record.id())
                .set(IMPORTS_RECONCILIATION.ACADEMIC_YEAR_ID, record.academicYearId())
                .set(IMPORTS_RECONCILIATION.OFFICIAL_REFERENCE, record.officialReference())
                .set(IMPORTS_RECONCILIATION.OFFICIAL_ENROLLMENT_COUNT, record.officialEnrollmentCount())
                .set(IMPORTS_RECONCILIATION.ENROLLMENTS_ACTIVE, record.enrollmentsActive())
                .set(IMPORTS_RECONCILIATION.STUDENTS_CREATED, record.students().created())
                .set(IMPORTS_RECONCILIATION.STUDENTS_REJECTED, record.students().rejected())
                .set(IMPORTS_RECONCILIATION.GUARDIANS_CREATED, record.guardians().created())
                .set(IMPORTS_RECONCILIATION.GUARDIANS_REJECTED, record.guardians().rejected())
                .set(IMPORTS_RECONCILIATION.GUARDIAN_LINKS_CREATED, record.guardianLinks().created())
                .set(IMPORTS_RECONCILIATION.GUARDIAN_LINKS_REJECTED, record.guardianLinks().rejected())
                .set(IMPORTS_RECONCILIATION.ENROLLMENTS_CREATED, record.enrollments().created())
                .set(IMPORTS_RECONCILIATION.ENROLLMENTS_REJECTED, record.enrollments().rejected())
                .set(IMPORTS_RECONCILIATION.SIGNED_BY, record.signedBy())
                .set(IMPORTS_RECONCILIATION.SIGNED_AT, toDatabase(record.signedAt()))
                .execute();
    }

    @Override
    public Page<Reconciliation> list(InstitutionId institutionId, PageRequest request) {
        Condition scope = IMPORTS_RECONCILIATION.INSTITUTION_ID.eq(institutionId.value());
        long total = dsl.fetchCount(IMPORTS_RECONCILIATION, scope);
        List<Reconciliation> rows = dsl.selectFrom(IMPORTS_RECONCILIATION)
                .where(scope)
                .orderBy(IMPORTS_RECONCILIATION.SIGNED_AT.desc(), IMPORTS_RECONCILIATION.ID.desc())
                .limit(request.size()).offset(request.offset())
                .fetch(JooqReconciliationRepository::toDomain);
        return new Page<>(rows, total, request.page(), request.size());
    }

    private static Reconciliation toDomain(ImportsReconciliationRecord row) {
        return new Reconciliation(row.getId(), row.getAcademicYearId(), row.getOfficialReference(),
                row.getOfficialEnrollmentCount(), row.getEnrollmentsActive(),
                new KindCount(row.getStudentsCreated(), row.getStudentsRejected()),
                new KindCount(row.getGuardiansCreated(), row.getGuardiansRejected()),
                new KindCount(row.getGuardianLinksCreated(), row.getGuardianLinksRejected()),
                new KindCount(row.getEnrollmentsCreated(), row.getEnrollmentsRejected()), row.getSignedBy(),
                row.getSignedAt().toInstant());
    }

    private static OffsetDateTime toDatabase(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}

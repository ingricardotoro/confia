package com.confia.guardians.infrastructure;

import static confia.generated.jooq.tables.GuardiansGuardianStudent.GUARDIANS_GUARDIAN_STUDENT;

import com.confia.guardians.application.GuardianLinkRepository;
import com.confia.guardians.domain.GuardianLink;
import com.confia.guardians.domain.Relationship;
import com.confia.kernel.InstitutionId;
import confia.generated.jooq.tables.records.GuardiansGuardianStudentRecord;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/**
 * The jOOQ adapter of {@link GuardianLinkRepository} over {@code guardians_guardian_student}. It inserts, reads and
 * closes: the only column it ever updates is {@code valid_to}, and only of a link that is still current (the
 * application role has no other column grant and a trigger refuses to touch a closed link). There is no delete.
 */
public final class JooqGuardianLinkRepository implements GuardianLinkRepository {

    private final DSLContext dsl;

    public JooqGuardianLinkRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<GuardianLink> currentOfStudent(InstitutionId institutionId, UUID studentId) {
        return fetch(GUARDIANS_GUARDIAN_STUDENT.INSTITUTION_ID.eq(institutionId.value())
                .and(GUARDIANS_GUARDIAN_STUDENT.STUDENT_ID.eq(studentId))
                .and(GUARDIANS_GUARDIAN_STUDENT.VALID_TO.isNull()));
    }

    @Override
    public List<GuardianLink> currentOfGuardian(InstitutionId institutionId, UUID guardianId) {
        return fetch(GUARDIANS_GUARDIAN_STUDENT.INSTITUTION_ID.eq(institutionId.value())
                .and(GUARDIANS_GUARDIAN_STUDENT.GUARDIAN_ID.eq(guardianId))
                .and(GUARDIANS_GUARDIAN_STUDENT.VALID_TO.isNull()));
    }

    @Override
    public List<GuardianLink> historyOfStudent(InstitutionId institutionId, UUID studentId) {
        return fetch(GUARDIANS_GUARDIAN_STUDENT.INSTITUTION_ID.eq(institutionId.value())
                .and(GUARDIANS_GUARDIAN_STUDENT.STUDENT_ID.eq(studentId)));
    }

    private List<GuardianLink> fetch(Condition scope) {
        return dsl.selectFrom(GUARDIANS_GUARDIAN_STUDENT).where(scope)
                .orderBy(GUARDIANS_GUARDIAN_STUDENT.VALID_FROM.asc(), GUARDIANS_GUARDIAN_STUDENT.CREATED_AT.asc(),
                        GUARDIANS_GUARDIAN_STUDENT.ID.asc())
                .fetch(JooqGuardianLinkRepository::toDomain);
    }

    @Override
    public void insertAll(InstitutionId institutionId, Collection<GuardianLink> links) {
        for (GuardianLink link : links) {
            GuardianWrites.translating(() -> dsl.insertInto(GUARDIANS_GUARDIAN_STUDENT)
                    .set(GUARDIANS_GUARDIAN_STUDENT.INSTITUTION_ID, institutionId.value())
                    .set(GUARDIANS_GUARDIAN_STUDENT.ID, link.id())
                    .set(GUARDIANS_GUARDIAN_STUDENT.GUARDIAN_ID, link.guardianId())
                    .set(GUARDIANS_GUARDIAN_STUDENT.STUDENT_ID, link.studentId())
                    .set(GUARDIANS_GUARDIAN_STUDENT.RELATIONSHIP, link.relationship().name())
                    .set(GUARDIANS_GUARDIAN_STUDENT.FINANCIAL_RESPONSIBILITY_PERCENT, link.percent())
                    .set(GUARDIANS_GUARDIAN_STUDENT.IS_PRIMARY_PAYER, link.primaryPayer())
                    .set(GUARDIANS_GUARDIAN_STUDENT.IS_BILLING_RECIPIENT, link.billingRecipient())
                    .set(GUARDIANS_GUARDIAN_STUDENT.VALID_FROM, link.validFrom())
                    .set(GUARDIANS_GUARDIAN_STUDENT.CREATED_BY, link.createdBy())
                    .set(GUARDIANS_GUARDIAN_STUDENT.CREATED_AT, GuardianWrites.toDatabase(link.createdAt()))
                    .execute());
        }
    }

    @Override
    public void close(InstitutionId institutionId, Collection<UUID> linkIds, LocalDate on) {
        if (linkIds.isEmpty()) {
            return;
        }
        int rows = dsl.update(GUARDIANS_GUARDIAN_STUDENT)
                .set(GUARDIANS_GUARDIAN_STUDENT.VALID_TO, on)
                .where(GUARDIANS_GUARDIAN_STUDENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(GUARDIANS_GUARDIAN_STUDENT.ID.in(linkIds))
                .and(GUARDIANS_GUARDIAN_STUDENT.VALID_TO.isNull())
                .execute();
        if (rows != linkIds.size()) {
            // The student is locked, so nobody else closed them: this is a defect, never a client error.
            throw new IllegalStateException("a guardian link to close was not current");
        }
    }

    private static GuardianLink toDomain(GuardiansGuardianStudentRecord row) {
        return new GuardianLink(row.getId(), row.getGuardianId(), row.getStudentId(),
                Relationship.parse(row.getRelationship()), row.getFinancialResponsibilityPercent(),
                row.getIsPrimaryPayer(), row.getIsBillingRecipient(), row.getValidFrom(), row.getValidTo(),
                row.getCreatedBy(), GuardianWrites.fromDatabase(row.getCreatedAt()));
    }
}

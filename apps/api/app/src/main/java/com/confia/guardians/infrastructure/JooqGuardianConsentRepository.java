package com.confia.guardians.infrastructure;

import static confia.generated.jooq.tables.GuardiansGuardianConsentEvent.GUARDIANS_GUARDIAN_CONSENT_EVENT;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;

import com.confia.guardians.application.GuardianConsentRepository;
import com.confia.guardians.domain.ConsentAction;
import com.confia.guardians.domain.ConsentChannel;
import com.confia.guardians.domain.ConsentEvent;
import com.confia.guardians.domain.ConsentPurpose;
import com.confia.guardians.domain.EvidenceSource;
import com.confia.kernel.InstitutionId;
import confia.generated.jooq.tables.GuardiansGuardianConsentEvent;
import confia.generated.jooq.tables.records.GuardiansGuardianConsentEventRecord;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The jOOQ adapter of {@link GuardianConsentRepository} over {@code guardians_guardian_consent_event}. It inserts and
 * reads and nothing else: the table is append-only (the application role holds INSERT and SELECT only and a trigger
 * refuses every update, delete and truncate), and this class has no statement that could change a row. The time of an
 * event is the one the use case gives; the order of the events of a guardian is {@code seq}, which follows the commit
 * order because the use case holds the lock of the guardian row. The table holds no contact data.
 */
public final class JooqGuardianConsentRepository implements GuardianConsentRepository {

    private final DSLContext dsl;

    public JooqGuardianConsentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<ConsentEvent> latestOfGuardian(InstitutionId institutionId, UUID guardianId) {
        GuardiansGuardianConsentEvent newer = GUARDIANS_GUARDIAN_CONSENT_EVENT.as("newer");
        return dsl.selectFrom(GUARDIANS_GUARDIAN_CONSENT_EVENT)
                .where(GUARDIANS_GUARDIAN_CONSENT_EVENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(GUARDIANS_GUARDIAN_CONSENT_EVENT.GUARDIAN_ID.eq(guardianId))
                .and(notExists(selectOne().from(newer)
                        .where(newer.INSTITUTION_ID.eq(GUARDIANS_GUARDIAN_CONSENT_EVENT.INSTITUTION_ID))
                        .and(newer.GUARDIAN_ID.eq(GUARDIANS_GUARDIAN_CONSENT_EVENT.GUARDIAN_ID))
                        .and(newer.CHANNEL.eq(GUARDIANS_GUARDIAN_CONSENT_EVENT.CHANNEL))
                        .and(newer.PURPOSE.eq(GUARDIANS_GUARDIAN_CONSENT_EVENT.PURPOSE))
                        .and(newer.SEQ.gt(GUARDIANS_GUARDIAN_CONSENT_EVENT.SEQ))))
                .orderBy(GUARDIANS_GUARDIAN_CONSENT_EVENT.SEQ.asc())
                .fetch(JooqGuardianConsentRepository::toDomain);
    }

    @Override
    public List<ConsentEvent> recentOfGuardian(InstitutionId institutionId, UUID guardianId, int limit) {
        return dsl.selectFrom(GUARDIANS_GUARDIAN_CONSENT_EVENT)
                .where(GUARDIANS_GUARDIAN_CONSENT_EVENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(GUARDIANS_GUARDIAN_CONSENT_EVENT.GUARDIAN_ID.eq(guardianId))
                .orderBy(GUARDIANS_GUARDIAN_CONSENT_EVENT.SEQ.desc())
                .limit(limit)
                .fetch(JooqGuardianConsentRepository::toDomain);
    }

    @Override
    public void append(InstitutionId institutionId, ConsentEvent event) {
        dsl.insertInto(GUARDIANS_GUARDIAN_CONSENT_EVENT)
                .set(GUARDIANS_GUARDIAN_CONSENT_EVENT.INSTITUTION_ID, institutionId.value())
                .set(GUARDIANS_GUARDIAN_CONSENT_EVENT.ID, event.id())
                .set(GUARDIANS_GUARDIAN_CONSENT_EVENT.GUARDIAN_ID, event.guardianId())
                .set(GUARDIANS_GUARDIAN_CONSENT_EVENT.CHANNEL, event.channel().name())
                .set(GUARDIANS_GUARDIAN_CONSENT_EVENT.PURPOSE, event.purpose().name())
                .set(GUARDIANS_GUARDIAN_CONSENT_EVENT.ACTION, event.action().name())
                .set(GUARDIANS_GUARDIAN_CONSENT_EVENT.EVIDENCE_SOURCE, event.evidenceSource().name())
                .set(GUARDIANS_GUARDIAN_CONSENT_EVENT.POLICY_VERSION, event.policyVersion())
                .set(GUARDIANS_GUARDIAN_CONSENT_EVENT.OCCURRED_AT, GuardianWrites.toDatabase(event.occurredAt()))
                .set(GUARDIANS_GUARDIAN_CONSENT_EVENT.RECORDED_BY, event.recordedBy())
                .execute();
    }

    private static ConsentEvent toDomain(GuardiansGuardianConsentEventRecord row) {
        return new ConsentEvent(row.getId(), row.getGuardianId(), ConsentChannel.valueOf(row.getChannel()),
                ConsentPurpose.valueOf(row.getPurpose()), ConsentAction.valueOf(row.getAction()),
                EvidenceSource.valueOf(row.getEvidenceSource()), row.getPolicyVersion(),
                GuardianWrites.fromDatabase(row.getOccurredAt()), row.getRecordedBy());
    }
}

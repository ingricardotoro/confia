package com.confia.guardians.infrastructure;

import static confia.generated.jooq.tables.GuardiansGuardian.GUARDIANS_GUARDIAN;
import static confia.generated.jooq.tables.GuardiansGuardianCodeCounter.GUARDIANS_GUARDIAN_CODE_COUNTER;

import com.confia.guardians.application.GuardianFilter;
import com.confia.guardians.application.GuardianRepository;
import com.confia.guardians.application.Page;
import com.confia.guardians.application.PageRequest;
import com.confia.guardians.application.ProtectedNationalId;
import com.confia.guardians.application.Storage;
import com.confia.guardians.domain.DuplicateExternalRefException;
import com.confia.guardians.domain.DuplicateNationalIdException;
import com.confia.guardians.domain.Guardian;
import com.confia.guardians.domain.GuardianStatus;
import com.confia.guardians.domain.PreferredChannel;
import com.confia.guardians.domain.ResourceNotFoundException;
import com.confia.kernel.InstitutionId;
import confia.generated.jooq.tables.records.GuardiansGuardianRecord;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.InsertSetMoreStep;
import org.jooq.SortField;
import org.jooq.impl.DSL;

/**
 * The jOOQ adapter of {@link GuardianRepository} over {@code guardians_guardian} and its code counter. It never
 * selects the encrypted document, its blind index nor the encrypted RTN into a domain object: reading a guardian
 * yields only whether there is one of each, and the columns are written on insert and on a replacement or removal.
 */
public final class JooqGuardianRepository implements GuardianRepository {

    private static final Map<String, Field<?>> SORTABLE = Map.of(
            "lastName", GUARDIANS_GUARDIAN.LAST_NAME,
            "code", GUARDIANS_GUARDIAN.GUARDIAN_CODE,
            "status", GUARDIANS_GUARDIAN.STATUS);

    private final DSLContext dsl;

    public JooqGuardianRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Guardian> findById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(GUARDIANS_GUARDIAN)
                .where(GUARDIANS_GUARDIAN.INSTITUTION_ID.eq(institutionId.value()))
                .and(GUARDIANS_GUARDIAN.ID.eq(id))
                .fetchOptional().map(JooqGuardianRepository::toDomain);
    }

    @Override
    public Optional<Guardian> lockById(InstitutionId institutionId, UUID id) {
        return dsl.selectFrom(GUARDIANS_GUARDIAN)
                .where(GUARDIANS_GUARDIAN.INSTITUTION_ID.eq(institutionId.value()))
                .and(GUARDIANS_GUARDIAN.ID.eq(id))
                .forUpdate()
                .fetchOptional().map(JooqGuardianRepository::toDomain);
    }

    @Override
    public List<Guardian> findAll(InstitutionId institutionId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(GUARDIANS_GUARDIAN)
                .where(GUARDIANS_GUARDIAN.INSTITUTION_ID.eq(institutionId.value()))
                .and(GUARDIANS_GUARDIAN.ID.in(ids))
                .fetch(JooqGuardianRepository::toDomain);
    }

    @Override
    public List<Guardian> lockAllShared(InstitutionId institutionId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(GUARDIANS_GUARDIAN)
                .where(GUARDIANS_GUARDIAN.INSTITUTION_ID.eq(institutionId.value()))
                .and(GUARDIANS_GUARDIAN.ID.in(ids))
                .orderBy(GUARDIANS_GUARDIAN.ID.asc())
                .forShare()
                .fetch(JooqGuardianRepository::toDomain);
    }

    @Override
    public long nextCodeSequence(InstitutionId institutionId) {
        // One statement: the first allocation inserts the row, every other updates it, and the row lock taken by
        // the update lasts until the end of the transaction, so concurrent creations queue here.
        return dsl.insertInto(GUARDIANS_GUARDIAN_CODE_COUNTER)
                .set(GUARDIANS_GUARDIAN_CODE_COUNTER.INSTITUTION_ID, institutionId.value())
                .set(GUARDIANS_GUARDIAN_CODE_COUNTER.LAST_VALUE, 1L)
                .onConflict(GUARDIANS_GUARDIAN_CODE_COUNTER.INSTITUTION_ID)
                .doUpdate()
                .set(GUARDIANS_GUARDIAN_CODE_COUNTER.LAST_VALUE, GUARDIANS_GUARDIAN_CODE_COUNTER.LAST_VALUE.plus(1L))
                .set(GUARDIANS_GUARDIAN_CODE_COUNTER.UPDATED_AT, DSL.currentOffsetDateTime())
                .returningResult(GUARDIANS_GUARDIAN_CODE_COUNTER.LAST_VALUE)
                .fetchSingle()
                .value1();
    }

    @Override
    public boolean codeExists(InstitutionId institutionId, String code) {
        return dsl.fetchExists(GUARDIANS_GUARDIAN, GUARDIANS_GUARDIAN.INSTITUTION_ID.eq(institutionId.value())
                .and(GUARDIANS_GUARDIAN.GUARDIAN_CODE.eq(code)));
    }

    @Override
    public void insert(InstitutionId institutionId, Guardian guardian, ProtectedNationalId document,
            String rtnEncrypted) {
        refuseDocumentOfAnotherGuardian(institutionId, guardian.id(), document);
        GuardianWrites.translating(() -> insertStatement(institutionId, guardian, document, rtnEncrypted, null)
                .execute());
    }

    @Override
    public void insertImported(InstitutionId institutionId, Guardian guardian, ProtectedNationalId document,
            String rtnEncrypted, String externalRef) {
        refuseDocumentOfAnotherGuardian(institutionId, guardian.id(), document);
        // The reference of the source system is the user's data: a unique violation would print it in the DETAIL of the
        // server log. ON CONFLICT settles the same race (it waits for a concurrent writer) without raising an error,
        // so there is nothing for the log to print. The other uniqueness (code, document) still raise and translate.
        int[] inserted = new int[1];
        GuardianWrites.translating(() -> inserted[0] = insertStatement(institutionId, guardian, document,
                rtnEncrypted, externalRef)
                .onConflict(GUARDIANS_GUARDIAN.INSTITUTION_ID, GUARDIANS_GUARDIAN.EXTERNAL_REF).doNothing()
                .execute());
        if (inserted[0] == 0) {
            throw new DuplicateExternalRefException();
        }
    }

    @Override
    public Set<String> existingExternalRefs(InstitutionId institutionId, Collection<String> externalRefs) {
        if (externalRefs.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(dsl.select(GUARDIANS_GUARDIAN.EXTERNAL_REF).from(GUARDIANS_GUARDIAN)
                .where(GUARDIANS_GUARDIAN.INSTITUTION_ID.eq(institutionId.value()))
                .and(GUARDIANS_GUARDIAN.EXTERNAL_REF.in(externalRefs))
                .fetch(GUARDIANS_GUARDIAN.EXTERNAL_REF));
    }

    @Override
    public Map<String, Guardian> findByExternalRefs(InstitutionId institutionId, Collection<String> externalRefs) {
        Map<String, Guardian> found = new HashMap<>();
        if (externalRefs.isEmpty()) {
            return found;
        }
        dsl.selectFrom(GUARDIANS_GUARDIAN)
                .where(GUARDIANS_GUARDIAN.INSTITUTION_ID.eq(institutionId.value()))
                .and(GUARDIANS_GUARDIAN.EXTERNAL_REF.in(externalRefs))
                .fetch().forEach(row -> found.put(row.getExternalRef(), toDomain(row)));
        return found;
    }

    private InsertSetMoreStep<GuardiansGuardianRecord> insertStatement(InstitutionId institutionId, Guardian guardian,
            ProtectedNationalId document, String rtnEncrypted, String externalRef) {
        return dsl.insertInto(GUARDIANS_GUARDIAN)
                .set(GUARDIANS_GUARDIAN.INSTITUTION_ID, institutionId.value())
                .set(GUARDIANS_GUARDIAN.ID, guardian.id())
                .set(GUARDIANS_GUARDIAN.GUARDIAN_CODE, guardian.guardianCode())
                .set(GUARDIANS_GUARDIAN.FIRST_NAME, guardian.firstName())
                .set(GUARDIANS_GUARDIAN.LAST_NAME, guardian.lastName())
                .set(GUARDIANS_GUARDIAN.EXTERNAL_REF, externalRef)
                .set(GUARDIANS_GUARDIAN.NATIONAL_ID_ENCRYPTED, document == null ? null : document.encrypted())
                .set(GUARDIANS_GUARDIAN.NATIONAL_ID_BLIND_INDEX, document == null ? null : document.blindIndex())
                .set(GUARDIANS_GUARDIAN.RTN_ENCRYPTED, rtnEncrypted)
                .set(GUARDIANS_GUARDIAN.EMAIL, guardian.email())
                .set(GUARDIANS_GUARDIAN.PHONE, guardian.phone())
                .set(GUARDIANS_GUARDIAN.ADDRESS, guardian.address())
                .set(GUARDIANS_GUARDIAN.PREFERRED_CHANNEL, guardian.preferredChannel().name())
                .set(GUARDIANS_GUARDIAN.STATUS, guardian.status().name())
                .set(GUARDIANS_GUARDIAN.CREATED_AT, GuardianWrites.toDatabase(guardian.createdAt()))
                .set(GUARDIANS_GUARDIAN.UPDATED_AT, GuardianWrites.toDatabase(guardian.updatedAt()));
    }

    @Override
    public void update(InstitutionId institutionId, Guardian guardian, Storage<ProtectedNationalId> document,
            Storage<String> rtn) {
        if (document instanceof Storage.Store<ProtectedNationalId> store) {
            refuseDocumentOfAnotherGuardian(institutionId, guardian.id(), store.value());
        }
        GuardianWrites.translating(() -> {
            var update = dsl.update(GUARDIANS_GUARDIAN)
                    .set(GUARDIANS_GUARDIAN.FIRST_NAME, guardian.firstName())
                    .set(GUARDIANS_GUARDIAN.LAST_NAME, guardian.lastName())
                    .set(GUARDIANS_GUARDIAN.EMAIL, guardian.email())
                    .set(GUARDIANS_GUARDIAN.PHONE, guardian.phone())
                    .set(GUARDIANS_GUARDIAN.ADDRESS, guardian.address())
                    .set(GUARDIANS_GUARDIAN.PREFERRED_CHANNEL, guardian.preferredChannel().name())
                    .set(GUARDIANS_GUARDIAN.STATUS, guardian.status().name())
                    .set(GUARDIANS_GUARDIAN.UPDATED_AT, GuardianWrites.toDatabase(guardian.updatedAt()));
            var withDocument = switch (document) {
                case Storage.Keep<ProtectedNationalId> keep -> update;
                case Storage.Clear<ProtectedNationalId> clear -> update
                        .set(GUARDIANS_GUARDIAN.NATIONAL_ID_ENCRYPTED, (String) null)
                        .set(GUARDIANS_GUARDIAN.NATIONAL_ID_BLIND_INDEX, (String) null);
                case Storage.Store<ProtectedNationalId> store -> update
                        .set(GUARDIANS_GUARDIAN.NATIONAL_ID_ENCRYPTED, store.value().encrypted())
                        .set(GUARDIANS_GUARDIAN.NATIONAL_ID_BLIND_INDEX, store.value().blindIndex());
            };
            var withRtn = switch (rtn) {
                case Storage.Keep<String> keep -> withDocument;
                case Storage.Clear<String> clear -> withDocument.set(GUARDIANS_GUARDIAN.RTN_ENCRYPTED, (String) null);
                case Storage.Store<String> store -> withDocument.set(GUARDIANS_GUARDIAN.RTN_ENCRYPTED, store.value());
            };
            int rows = withRtn
                    .where(GUARDIANS_GUARDIAN.INSTITUTION_ID.eq(institutionId.value()))
                    .and(GUARDIANS_GUARDIAN.ID.eq(guardian.id()))
                    .execute();
            if (rows == 0) {
                throw new ResourceNotFoundException();
            }
        });
    }

    /**
     * During a key rotation a row may still carry the index of the previous version, which the unique index cannot
     * equate with the new one, so the duplicate is searched under every configured version first. The unique index
     * stays the last word for two concurrent writers of the same version.
     */
    private void refuseDocumentOfAnotherGuardian(InstitutionId institutionId, UUID guardianId,
            ProtectedNationalId document) {
        if (document != null && document.lookupIndexes().size() > 1 && dsl.fetchExists(GUARDIANS_GUARDIAN,
                GUARDIANS_GUARDIAN.INSTITUTION_ID.eq(institutionId.value())
                        .and(GUARDIANS_GUARDIAN.ID.ne(guardianId))
                        .and(GUARDIANS_GUARDIAN.NATIONAL_ID_BLIND_INDEX.in(document.lookupIndexes())))) {
            throw new DuplicateNationalIdException();
        }
    }

    @Override
    public Page<Guardian> list(InstitutionId institutionId, PageRequest request, GuardianFilter filter) {
        List<Condition> conditions = new ArrayList<>();
        conditions.add(GUARDIANS_GUARDIAN.INSTITUTION_ID.eq(institutionId.value()));
        if (filter.status() != null) {
            conditions.add(GUARDIANS_GUARDIAN.STATUS.eq(filter.status().name()));
        }
        if (filter.text() != null) {
            // containsIgnoreCase binds the text and escapes the wildcards of LIKE inside it.
            conditions.add(DSL.or(GUARDIANS_GUARDIAN.FIRST_NAME.containsIgnoreCase(filter.text()),
                    GUARDIANS_GUARDIAN.LAST_NAME.containsIgnoreCase(filter.text()),
                    GUARDIANS_GUARDIAN.GUARDIAN_CODE.containsIgnoreCase(filter.text())));
        }
        Condition scope = DSL.and(conditions);
        long total = dsl.fetchCount(GUARDIANS_GUARDIAN, scope);
        List<Guardian> rows = dsl.selectFrom(GUARDIANS_GUARDIAN).where(scope)
                .orderBy(order(request), GUARDIANS_GUARDIAN.GUARDIAN_CODE.asc(), GUARDIANS_GUARDIAN.ID.asc())
                .limit(request.size()).offset(request.offset())
                .fetch(JooqGuardianRepository::toDomain);
        return new Page<>(rows, total, request.page(), request.size());
    }

    private static SortField<?> order(PageRequest request) {
        Field<?> field = SORTABLE.get(request.sortField());
        return request.ascending() ? field.asc() : field.desc();
    }

    private static Guardian toDomain(GuardiansGuardianRecord row) {
        return new Guardian(row.getId(), row.getGuardianCode(), row.getFirstName(), row.getLastName(),
                row.getNationalIdEncrypted() != null, row.getRtnEncrypted() != null, row.getEmail(), row.getPhone(),
                row.getAddress(), PreferredChannel.parseOrNone(row.getPreferredChannel()),
                GuardianStatus.parse(row.getStatus()), GuardianWrites.fromDatabase(row.getCreatedAt()),
                GuardianWrites.fromDatabase(row.getUpdatedAt()));
    }
}

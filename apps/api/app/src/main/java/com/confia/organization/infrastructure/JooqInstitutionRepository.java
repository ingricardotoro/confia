package com.confia.organization.infrastructure;

import static confia.generated.jooq.tables.OrganizationInstitution.ORGANIZATION_INSTITUTION;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.organization.application.InstitutionRepository;
import com.confia.organization.domain.Institution;
import confia.generated.jooq.tables.records.OrganizationInstitutionRecord;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * Read-only jOOQ implementation of {@link InstitutionRepository} against the real {@code
 * organization_institution} table (design.md decision 8; specs/organization/spec.md, requirement
 * "Contrato observable del adaptador jOOQ de InstitutionRepository contra la base real"). {@code
 * final}, with an explicit constructor over {@link DSLContext} and no Spring annotation: no use
 * case is registered as a bean yet in any of the three bootstrap processes, none of which scans
 * components (ADR-0024); registering this as a bean is the job of whichever change first consumes
 * it, with an explicit {@code @Import} in the entry point.
 *
 * <p>Read-only in this part of the change on purpose: the port only declares {@link
 * InstitutionRepository#findById}, so this class never opens a transaction and never fixes the
 * session's {@code app.institution_id} — both are the responsibility of the caller (today, a
 * test's {@code withInstitutionContext}; from the part B change on, the single transactional
 * component of {@code shared.security}, ADR-0015 rule 7). A write method with no production
 * consumer arrives with institution administration (change 7), not here.
 */
public final class JooqInstitutionRepository implements InstitutionRepository {

    private final DSLContext dsl;

    public JooqInstitutionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Institution> findById(InstitutionId id) {
        return dsl.selectFrom(ORGANIZATION_INSTITUTION)
                .where(ORGANIZATION_INSTITUTION.ID.eq(id.value()))
                .fetchOptional()
                .map(JooqInstitutionRepository::toDomain);
    }

    /**
     * Explicit, attribute-by-attribute conversion (design.md decision 8's table): {@code
     * trade_name} stays {@code null} rather than becoming an empty string, the three closed
     * domain types are parsed from the columns that store them as text, and an inactive row
     * deactivates the freshly created (always-active) aggregate rather than being modeled as a
     * separate constructor path.
     */
    private static Institution toDomain(OrganizationInstitutionRecord row) {
        Institution institution = Institution.create(
                new InstitutionId(row.getId()),
                row.getLegalName(),
                row.getTradeName(),
                row.getRtn(),
                row.getAddress(),
                CurrencyCode.valueOf(row.getDefaultCurrency()),
                Locale.forLanguageTag(row.getLocale()),
                ZoneId.of(row.getTimezone()));
        if (!Boolean.TRUE.equals(row.getIsActive())) {
            institution.deactivate();
        }
        return institution;
    }
}

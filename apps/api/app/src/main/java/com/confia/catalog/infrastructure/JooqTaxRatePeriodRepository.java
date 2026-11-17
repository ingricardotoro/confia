package com.confia.catalog.infrastructure;

import static confia.generated.jooq.tables.CatalogTaxRatePeriod.CATALOG_TAX_RATE_PERIOD;

import com.confia.catalog.application.TaxRatePeriodRepository;
import com.confia.catalog.domain.RatePercent;
import com.confia.catalog.domain.TaxRatePeriod;
import com.confia.kernel.InstitutionId;
import confia.generated.jooq.tables.records.CatalogTaxRatePeriodRecord;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The jOOQ adapter of {@link TaxRatePeriodRepository} over {@code catalog_tax_rate_period}. It inserts and reads, and the
 * one update it has writes {@code valid_to} of an open validity: no statement here edits a percentage or a start, and none
 * deletes (the database refuses them too).
 */
public final class JooqTaxRatePeriodRepository implements TaxRatePeriodRepository {

    private final DSLContext dsl;

    public JooqTaxRatePeriodRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<TaxRatePeriod> findByTaxRate(InstitutionId institutionId, UUID taxRateId) {
        return dsl.selectFrom(CATALOG_TAX_RATE_PERIOD)
                .where(CATALOG_TAX_RATE_PERIOD.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_TAX_RATE_PERIOD.TAX_RATE_ID.eq(taxRateId))
                .orderBy(CATALOG_TAX_RATE_PERIOD.VALID_FROM.asc())
                .fetch(JooqTaxRatePeriodRepository::toDomain);
    }

    @Override
    public Optional<TaxRatePeriod> findOpen(InstitutionId institutionId, UUID taxRateId) {
        return dsl.selectFrom(CATALOG_TAX_RATE_PERIOD)
                .where(CATALOG_TAX_RATE_PERIOD.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_TAX_RATE_PERIOD.TAX_RATE_ID.eq(taxRateId))
                .and(CATALOG_TAX_RATE_PERIOD.VALID_TO.isNull())
                .fetchOptional().map(JooqTaxRatePeriodRepository::toDomain);
    }

    @Override
    public void insert(InstitutionId institutionId, TaxRatePeriod period) {
        CatalogWrites.translating(() -> dsl.insertInto(CATALOG_TAX_RATE_PERIOD)
                .set(CATALOG_TAX_RATE_PERIOD.INSTITUTION_ID, institutionId.value())
                .set(CATALOG_TAX_RATE_PERIOD.ID, period.id())
                .set(CATALOG_TAX_RATE_PERIOD.TAX_RATE_ID, period.taxRateId())
                .set(CATALOG_TAX_RATE_PERIOD.RATE_PERCENT, period.ratePercent().value())
                .set(CATALOG_TAX_RATE_PERIOD.VALID_FROM, period.validFrom())
                .set(CATALOG_TAX_RATE_PERIOD.VALID_TO, period.validTo())
                .set(CATALOG_TAX_RATE_PERIOD.CREATED_BY, period.createdBy())
                .set(CATALOG_TAX_RATE_PERIOD.CREATED_AT, CatalogWrites.toDatabase(period.createdAt()))
                .execute());
    }

    @Override
    public void closeValidity(InstitutionId institutionId, UUID periodId, LocalDate lastDay) {
        // Only valid_to is written, and only while it is still open: the statement cannot touch a closed validity.
        int rows = dsl.update(CATALOG_TAX_RATE_PERIOD)
                .set(CATALOG_TAX_RATE_PERIOD.VALID_TO, lastDay)
                .where(CATALOG_TAX_RATE_PERIOD.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_TAX_RATE_PERIOD.ID.eq(periodId))
                .and(CATALOG_TAX_RATE_PERIOD.VALID_TO.isNull())
                .execute();
        if (rows != 1) {
            throw new IllegalStateException("the open validity to close was not found");
        }
    }

    private static TaxRatePeriod toDomain(CatalogTaxRatePeriodRecord row) {
        return new TaxRatePeriod(row.getId(), row.getTaxRateId(), new RatePercent(row.getRatePercent()),
                row.getValidFrom(), row.getValidTo(), row.getCreatedBy(),
                CatalogWrites.fromDatabase(row.getCreatedAt()));
    }
}

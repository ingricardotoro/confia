package com.confia.catalog.infrastructure;

import static confia.generated.jooq.tables.CatalogPaymentConcept.CATALOG_PAYMENT_CONCEPT;
import static confia.generated.jooq.tables.CatalogPriceList.CATALOG_PRICE_LIST;
import static confia.generated.jooq.tables.CatalogPriceListItem.CATALOG_PRICE_LIST_ITEM;
import static confia.generated.jooq.tables.CatalogTaxRate.CATALOG_TAX_RATE;
import static confia.generated.jooq.tables.CatalogTaxRatePeriod.CATALOG_TAX_RATE_PERIOD;

import com.confia.catalog.application.directory.PricingDirectory;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Percentage;
import com.confia.shared.infrastructure.money.MoneyColumns;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.ResultQuery;
import org.jooq.Record;
import org.jooq.SelectForUpdateStep;

/**
 * The jOOQ adapter of {@link PricingDirectory}: read-only queries over the concepts, the price lists and the tax rates of
 * the institution, each optionally locked {@code FOR SHARE}. It never opens a transaction and never sets the institution of
 * the session; row-level security does the second filter. Amounts go through {@link MoneyColumns} and nowhere else.
 */
public final class JooqPricingDirectory implements PricingDirectory {

    private static final String ACTIVE = "ACTIVE";

    private final DSLContext dsl;

    public JooqPricingDirectory(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<ConceptTerms> concept(InstitutionId institutionId, UUID paymentConceptId, boolean lockShare) {
        SelectForUpdateStep<?> query = dsl.select(CATALOG_PAYMENT_CONCEPT.ID, CATALOG_PAYMENT_CONCEPT.IS_ACTIVE,
                CATALOG_PAYMENT_CONCEPT.CATEGORY, CATALOG_PAYMENT_CONCEPT.IS_TAXABLE,
                CATALOG_PAYMENT_CONCEPT.TAX_RATE_ID)
                .from(CATALOG_PAYMENT_CONCEPT)
                .where(CATALOG_PAYMENT_CONCEPT.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_PAYMENT_CONCEPT.ID.eq(paymentConceptId));
        return shared(query, lockShare).fetchOptional(row -> new ConceptTerms(row.get(CATALOG_PAYMENT_CONCEPT.ID),
                row.get(CATALOG_PAYMENT_CONCEPT.IS_ACTIVE), row.get(CATALOG_PAYMENT_CONCEPT.CATEGORY),
                row.get(CATALOG_PAYMENT_CONCEPT.IS_TAXABLE), row.get(CATALOG_PAYMENT_CONCEPT.TAX_RATE_ID)));
    }

    @Override
    public List<PriceEntry> prices(InstitutionId institutionId, UUID academicYearId, UUID paymentConceptId,
            boolean lockShare) {
        SelectForUpdateStep<?> query = dsl.select(CATALOG_PRICE_LIST_ITEM.ID, CATALOG_PRICE_LIST.SCOPE,
                CATALOG_PRICE_LIST.SCOPE_REF_ID, CATALOG_PRICE_LIST_ITEM.AMOUNT, CATALOG_PRICE_LIST_ITEM.CURRENCY,
                CATALOG_PRICE_LIST_ITEM.VALID_FROM, CATALOG_PRICE_LIST_ITEM.VALID_TO)
                .from(CATALOG_PRICE_LIST_ITEM)
                .join(CATALOG_PRICE_LIST)
                .on(CATALOG_PRICE_LIST.INSTITUTION_ID.eq(CATALOG_PRICE_LIST_ITEM.INSTITUTION_ID)
                        .and(CATALOG_PRICE_LIST.ID.eq(CATALOG_PRICE_LIST_ITEM.PRICE_LIST_ID)))
                .where(CATALOG_PRICE_LIST_ITEM.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_PRICE_LIST_ITEM.PAYMENT_CONCEPT_ID.eq(paymentConceptId))
                .and(CATALOG_PRICE_LIST.ACADEMIC_YEAR_ID.eq(academicYearId))
                .and(CATALOG_PRICE_LIST.STATUS.eq(ACTIVE))
                .orderBy(CATALOG_PRICE_LIST_ITEM.VALID_FROM, CATALOG_PRICE_LIST_ITEM.ID);
        ResultQuery<?> locked = lockShare
                ? query.forShare().of(CATALOG_PRICE_LIST_ITEM, CATALOG_PRICE_LIST) : query;
        return locked.fetch(row -> new PriceEntry(row.get(CATALOG_PRICE_LIST_ITEM.ID), row.get(CATALOG_PRICE_LIST.SCOPE),
                row.get(CATALOG_PRICE_LIST.SCOPE_REF_ID),
                MoneyColumns.toMoney(row.get(CATALOG_PRICE_LIST_ITEM.AMOUNT), row.get(CATALOG_PRICE_LIST_ITEM.CURRENCY)),
                row.get(CATALOG_PRICE_LIST_ITEM.VALID_FROM), row.get(CATALOG_PRICE_LIST_ITEM.VALID_TO)));
    }

    @Override
    public Optional<TaxRateTerms> taxRate(InstitutionId institutionId, UUID taxRateId, boolean lockShare) {
        SelectForUpdateStep<?> rateQuery = dsl.select(CATALOG_TAX_RATE.ID, CATALOG_TAX_RATE.IS_ACTIVE)
                .from(CATALOG_TAX_RATE).where(CATALOG_TAX_RATE.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_TAX_RATE.ID.eq(taxRateId));
        Optional<Record> rate = shared(rateQuery, lockShare).fetchOptional(row -> (Record) row);
        if (rate.isEmpty()) {
            return Optional.empty();
        }
        SelectForUpdateStep<?> periodsQuery = dsl.select(CATALOG_TAX_RATE_PERIOD.ID, CATALOG_TAX_RATE_PERIOD.RATE_PERCENT,
                CATALOG_TAX_RATE_PERIOD.VALID_FROM, CATALOG_TAX_RATE_PERIOD.VALID_TO)
                .from(CATALOG_TAX_RATE_PERIOD).where(CATALOG_TAX_RATE_PERIOD.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_TAX_RATE_PERIOD.TAX_RATE_ID.eq(taxRateId))
                .orderBy(CATALOG_TAX_RATE_PERIOD.VALID_FROM);
        List<TaxValidity> validities = shared(periodsQuery, lockShare).fetch(row -> new TaxValidity(
                row.get(CATALOG_TAX_RATE_PERIOD.ID), Percentage.of(row.get(CATALOG_TAX_RATE_PERIOD.RATE_PERCENT)),
                row.get(CATALOG_TAX_RATE_PERIOD.VALID_FROM), row.get(CATALOG_TAX_RATE_PERIOD.VALID_TO)));
        return Optional.of(new TaxRateTerms(taxRateId, rate.get().get(CATALOG_TAX_RATE.IS_ACTIVE), validities));
    }

    private static ResultQuery<?> shared(SelectForUpdateStep<?> query, boolean lockShare) {
        return lockShare ? query.forShare() : query;
    }
}

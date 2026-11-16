package com.confia.catalog.infrastructure;

import static confia.generated.jooq.tables.CatalogPriceListItem.CATALOG_PRICE_LIST_ITEM;

import com.confia.catalog.application.PriceListItemRepository;
import com.confia.catalog.domain.PriceListItem;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import confia.generated.jooq.tables.records.CatalogPriceListItemRecord;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The jOOQ adapter of {@link PriceListItemRepository} over {@code catalog_price_list_item}. It inserts and reads, and
 * the one update it has writes {@code valid_to} of an open validity: no statement here edits an amount, a currency or a
 * start, and none deletes (INV-12, anticipated; the database refuses them too).
 */
public final class JooqPriceListItemRepository implements PriceListItemRepository {

    private final DSLContext dsl;

    public JooqPriceListItemRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<PriceListItem> findByList(InstitutionId institutionId, UUID priceListId) {
        return dsl.selectFrom(CATALOG_PRICE_LIST_ITEM)
                .where(CATALOG_PRICE_LIST_ITEM.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_PRICE_LIST_ITEM.PRICE_LIST_ID.eq(priceListId))
                .orderBy(CATALOG_PRICE_LIST_ITEM.PAYMENT_CONCEPT_ID.asc(), CATALOG_PRICE_LIST_ITEM.VALID_FROM.asc())
                .fetch(JooqPriceListItemRepository::toDomain);
    }

    @Override
    public Optional<PriceListItem> findOpen(InstitutionId institutionId, UUID priceListId, UUID paymentConceptId) {
        return dsl.selectFrom(CATALOG_PRICE_LIST_ITEM)
                .where(CATALOG_PRICE_LIST_ITEM.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_PRICE_LIST_ITEM.PRICE_LIST_ID.eq(priceListId))
                .and(CATALOG_PRICE_LIST_ITEM.PAYMENT_CONCEPT_ID.eq(paymentConceptId))
                .and(CATALOG_PRICE_LIST_ITEM.VALID_TO.isNull())
                .fetchOptional().map(JooqPriceListItemRepository::toDomain);
    }

    @Override
    public void insert(InstitutionId institutionId, PriceListItem item) {
        CatalogWrites.translating(() -> dsl.insertInto(CATALOG_PRICE_LIST_ITEM)
                .set(CATALOG_PRICE_LIST_ITEM.INSTITUTION_ID, institutionId.value())
                .set(CATALOG_PRICE_LIST_ITEM.ID, item.id())
                .set(CATALOG_PRICE_LIST_ITEM.PRICE_LIST_ID, item.priceListId())
                .set(CATALOG_PRICE_LIST_ITEM.PAYMENT_CONCEPT_ID, item.paymentConceptId())
                .set(CATALOG_PRICE_LIST_ITEM.AMOUNT, item.price().amount())
                .set(CATALOG_PRICE_LIST_ITEM.CURRENCY, item.price().currency().name())
                .set(CATALOG_PRICE_LIST_ITEM.VALID_FROM, item.validFrom())
                .set(CATALOG_PRICE_LIST_ITEM.VALID_TO, item.validTo())
                .set(CATALOG_PRICE_LIST_ITEM.CREATED_BY, item.createdBy())
                .set(CATALOG_PRICE_LIST_ITEM.CREATED_AT, CatalogWrites.toDatabase(item.createdAt()))
                .execute());
    }

    @Override
    public void closeValidity(InstitutionId institutionId, UUID itemId, LocalDate lastDay) {
        // Only valid_to is written, and only while it is still open: the statement cannot touch a closed validity.
        int rows = dsl.update(CATALOG_PRICE_LIST_ITEM)
                .set(CATALOG_PRICE_LIST_ITEM.VALID_TO, lastDay)
                .where(CATALOG_PRICE_LIST_ITEM.INSTITUTION_ID.eq(institutionId.value()))
                .and(CATALOG_PRICE_LIST_ITEM.ID.eq(itemId))
                .and(CATALOG_PRICE_LIST_ITEM.VALID_TO.isNull())
                .execute();
        if (rows != 1) {
            throw new IllegalStateException("the open validity to close was not found");
        }
    }

    private static PriceListItem toDomain(CatalogPriceListItemRecord row) {
        // The one place that builds an amount from NUMERIC(14,4) and its currency column.
        Money price = Money.of(row.getAmount(), CurrencyCode.fromIsoCode(row.getCurrency()));
        return new PriceListItem(row.getId(), row.getPriceListId(), row.getPaymentConceptId(), price,
                row.getValidFrom(), row.getValidTo(), row.getCreatedBy(),
                CatalogWrites.fromDatabase(row.getCreatedAt()));
    }
}

package com.confia.catalog.application;

import com.confia.catalog.domain.PriceListItem;
import com.confia.kernel.InstitutionId;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the prices of a list. It only inserts and reads, and closes the open validity of a price: there is no
 * operation that edits an amount, a currency or a start, and none that deletes (INV-12, anticipated).
 */
public interface PriceListItemRepository {

    /** Every validity of every concept of the list, by concept and start. */
    List<PriceListItem> findByList(InstitutionId institutionId, UUID priceListId);

    /** The validity of the concept in the list that has no end, if any. */
    Optional<PriceListItem> findOpen(InstitutionId institutionId, UUID priceListId, UUID paymentConceptId);

    /**
     * Inserts a price.
     *
     * @throws com.confia.catalog.domain.PriceValidityOverlapException when the database refuses its validity (INV-11)
     */
    void insert(InstitutionId institutionId, PriceListItem item);

    /** Closes the open validity of the price on {@code lastDay}: the only change a price ever takes. */
    void closeValidity(InstitutionId institutionId, UUID itemId, LocalDate lastDay);
}

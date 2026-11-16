package com.confia.catalog.application;

import com.confia.catalog.domain.PriceList;
import com.confia.kernel.InstitutionId;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the price lists of an institution. There is no delete: a list is archived. The year, the scope and the
 * reference of a list are written once, by {@link #insert}; {@link #update} writes the name, the status and the version.
 */
public interface PriceListRepository {

    Optional<PriceList> findById(InstitutionId institutionId, UUID id);

    /**
     * The list, locked until the end of the transaction ({@code SELECT ... FOR UPDATE} on its row), so two requests that
     * add a price to it take turns. Empty when it does not exist in the institution.
     */
    Optional<PriceList> lockById(InstitutionId institutionId, UUID id);

    void insert(InstitutionId institutionId, PriceList list);

    /**
     * Writes the editable members of the list and its new version.
     *
     * @param expectedVersion the version the row must still have
     * @throws com.confia.catalog.domain.StaleVersionException when the row no longer has it
     * @throws com.confia.catalog.domain.ResourceNotFoundException when the row does not exist in the institution
     */
    void update(InstitutionId institutionId, PriceList list, long expectedVersion);

    Page<PriceList> list(InstitutionId institutionId, PageRequest request, PriceListFilter filter);
}

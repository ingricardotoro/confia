package com.confia.catalog.application;

import com.confia.catalog.domain.TaxRate;
import com.confia.kernel.InstitutionId;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the tax rates of an institution. A code already taken (whatever its capitals) is refused by {@link
 * #insert} as a {@code DuplicateTaxRateCodeException}. There is no delete: a rate is deactivated.
 */
public interface TaxRateRepository {

    Optional<TaxRate> findById(InstitutionId institutionId, UUID id);

    /** The rates with these identifiers that exist in the institution, by identifier. */
    Map<UUID, TaxRate> findByIds(InstitutionId institutionId, Collection<UUID> ids);

    /**
     * The rate, locked until the end of the transaction ({@code SELECT ... FOR UPDATE} on its row), so two requests that
     * add a period to it, or edit it, take turns. Empty when it does not exist in the institution.
     */
    Optional<TaxRate> lockById(InstitutionId institutionId, UUID id);

    /**
     * The rate, locked in share mode until the end of the transaction ({@code SELECT ... FOR SHARE}): nobody can
     * deactivate it until the caller commits, so a concept is never assigned a rate that is deactivated in between. Empty
     * when it does not exist in the institution.
     */
    Optional<TaxRate> lockShareById(InstitutionId institutionId, UUID id);

    void insert(InstitutionId institutionId, TaxRate rate);

    /**
     * Writes the name, the active flag and the new version; the code is never written.
     *
     * @param expectedVersion the version the row must still have
     * @throws com.confia.catalog.domain.StaleVersionException when the row no longer has it
     * @throws com.confia.catalog.domain.ResourceNotFoundException when the row does not exist in the institution
     */
    void update(InstitutionId institutionId, TaxRate rate, long expectedVersion);

    Page<TaxRate> list(InstitutionId institutionId, PageRequest request, TaxRateFilter filter);
}

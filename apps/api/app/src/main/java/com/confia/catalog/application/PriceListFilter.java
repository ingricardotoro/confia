package com.confia.catalog.application;

import com.confia.catalog.domain.PriceListScope;
import com.confia.catalog.domain.PriceListStatus;
import java.util.UUID;

/**
 * The optional filters of the list of price lists; a {@code null} member does not filter.
 *
 * @param academicYearId only the lists of this academic year
 * @param scope only the lists of this scope
 * @param status only the lists in this status
 */
public record PriceListFilter(UUID academicYearId, PriceListScope scope, PriceListStatus status) {
}

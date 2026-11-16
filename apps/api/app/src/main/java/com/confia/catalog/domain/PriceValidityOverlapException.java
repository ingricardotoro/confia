package com.confia.catalog.domain;

import com.confia.kernel.DomainException;

/**
 * The validity of a price overlaps the one of another price of the same concept in the same list (INV-11), and it is not
 * the one case the server resolves by itself (an open validity that the new price replaces). Published to clients as the
 * Problem Details code {@code price-validity-overlap}.
 */
public final class PriceValidityOverlapException extends DomainException {

    public static final String CODE = "price-validity-overlap";

    public PriceValidityOverlapException() {
        super(CODE, "the validity of the price overlaps another price of the same concept in the list");
    }
}

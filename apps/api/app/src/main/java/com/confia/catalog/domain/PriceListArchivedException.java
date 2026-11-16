package com.confia.catalog.domain;

import com.confia.kernel.DomainException;

/**
 * An archived price list takes no new price. Published to clients as the Problem Details code {@code
 * price-list-archived}.
 */
public final class PriceListArchivedException extends DomainException {

    public static final String CODE = "price-list-archived";

    public PriceListArchivedException() {
        super(CODE, "an archived price list takes no new price");
    }
}

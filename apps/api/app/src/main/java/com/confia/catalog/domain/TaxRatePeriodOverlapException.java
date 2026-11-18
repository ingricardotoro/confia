package com.confia.catalog.domain;

import com.confia.kernel.DomainException;

/**
 * The validity of a tax rate period overlaps the one of another period of the same tax rate (INV-11), and it is not the
 * one case the server resolves by itself (an open validity that the new period replaces). Published to clients as the
 * Problem Details code {@code tax-rate-period-overlap}.
 */
public final class TaxRatePeriodOverlapException extends DomainException {

    public static final String CODE = "tax-rate-period-overlap";

    public TaxRatePeriodOverlapException() {
        super(CODE, "the validity of the tax rate period overlaps another period of the same tax rate");
    }
}

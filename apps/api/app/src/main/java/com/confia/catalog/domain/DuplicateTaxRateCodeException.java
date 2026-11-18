package com.confia.catalog.domain;

import com.confia.kernel.DomainException;

/**
 * The code of a tax rate is already taken in the institution, whatever its capitals. Published to clients as the Problem
 * Details code {@code duplicate-tax-rate-code}.
 */
public final class DuplicateTaxRateCodeException extends DomainException {

    public static final String CODE = "duplicate-tax-rate-code";

    public DuplicateTaxRateCodeException() {
        super(CODE, "a tax rate with the same code already exists");
    }
}

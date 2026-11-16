package com.confia.catalog.domain;

import com.confia.kernel.DomainException;

/**
 * A price for a payment concept that is deactivated. Published to clients as the Problem Details code {@code
 * inactive-reference}, which the academic structure already uses for an inactive modality, grade or section.
 */
public final class InactiveReferenceException extends DomainException {

    public static final String CODE = "inactive-reference";

    public InactiveReferenceException() {
        super(CODE, "the payment concept is inactive");
    }
}

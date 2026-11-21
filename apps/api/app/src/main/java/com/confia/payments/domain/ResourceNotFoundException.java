package com.confia.payments.domain;

import com.confia.kernel.DomainException;

/** The identifier names nothing in the institution of the request (a row of another institution, and a payment of another cashier for a reader limited to its own, look the same). Published to clients as the Problem Details code {@code resource-not-found}. */
public final class ResourceNotFoundException extends DomainException {

    public static final String CODE = "resource-not-found";

    public ResourceNotFoundException() {
        super(CODE, "the resource does not exist in the institution of the request");
    }
}

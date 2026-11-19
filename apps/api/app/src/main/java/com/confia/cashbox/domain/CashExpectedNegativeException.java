package com.confia.cashbox.domain;

import com.confia.kernel.DomainException;

/** The movement would leave the expected cash of the session below zero. Published to clients as the Problem Details code {@code cash-expected-negative}. */
public final class CashExpectedNegativeException extends DomainException {

    public static final String CODE = "cash-expected-negative";

    public CashExpectedNegativeException() {
        super(CODE, "the movement would leave the expected cash negative");
    }
}

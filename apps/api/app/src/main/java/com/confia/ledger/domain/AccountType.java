package com.confia.ledger.domain;

/** The five types of an account of the chart. Only an asset or a liability may hold the balances of students (INV-39). */
public enum AccountType {
    ASSET, LIABILITY, EQUITY, INCOME, EXPENSE;

    /** The type named by the text, or a domain error when it names none (case matters, as in the API). */
    public static AccountType parse(String value) {
        if (value != null) {
            for (AccountType type : values()) {
                if (type.name().equals(value)) {
                    return type;
                }
            }
        }
        throw new InvalidLedgerAccountException();
    }

    /** Whether an account of this type may carry the student dimension of an entry. */
    public boolean canHoldStudentBalances() {
        return this == ASSET || this == LIABILITY;
    }
}

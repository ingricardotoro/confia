package com.confia.charges.domain;

/**
 * What an account of the chart is used for when a charge is posted: the receivable, the revenue and the tax payable. Each
 * purpose names the type its account must have and whether the account carries the balance of each student, which is what the
 * accrual engine requires of the account it posts to; the map is validated against the same rule when it is loaded.
 */
public enum PostingPurpose {
    RECEIVABLE("ASSET", true), REVENUE("INCOME", false), TAX_PAYABLE("LIABILITY", false);

    private final String accountType;
    private final boolean holdsStudentBalances;

    PostingPurpose(String accountType, boolean holdsStudentBalances) {
        this.accountType = accountType;
        this.holdsStudentBalances = holdsStudentBalances;
    }

    /** The type the account of this purpose must have: {@code ASSET}, {@code INCOME} or {@code LIABILITY}. */
    public String accountType() {
        return accountType;
    }

    /** Whether the account of this purpose is the one that carries the balance of each student. */
    public boolean holdsStudentBalances() {
        return holdsStudentBalances;
    }

    /** Whether an account of this type and student flag may be the account of this purpose. */
    public boolean accepts(String type, boolean accountHoldsStudentBalances) {
        return accountType.equals(type) && holdsStudentBalances == accountHoldsStudentBalances;
    }

    /** The purpose named by the text, or a domain error when it names none (case matters, as in the API). */
    public static PostingPurpose parse(String value) {
        if (value != null) {
            for (PostingPurpose purpose : values()) {
                if (purpose.name().equals(value)) {
                    return purpose;
                }
            }
        }
        throw new InvalidChargesValueException();
    }
}

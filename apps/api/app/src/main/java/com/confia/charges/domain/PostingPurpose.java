package com.confia.charges.domain;

/**
 * What an account of the chart is used for when a charge is posted: the receivable, the revenue, the tax payable and the benefits
 * (the scholarships granted). Each purpose names the types its account may have and whether the account carries the balance of each
 * student, which is what the accrual engine requires of the account it posts to; the map is validated against the same rule when it
 * is loaded. {@code BENEFITS} accepts a contra-revenue ({@code INCOME}) or an {@code EXPENSE} account: assumption S4 of
 * docs/cortes/scholarships-awards.md, pending the accountant.
 */
public enum PostingPurpose {
    RECEIVABLE(true, "ASSET"), REVENUE(false, "INCOME"), TAX_PAYABLE(false, "LIABILITY"), BENEFITS(false, "EXPENSE", "INCOME");

    private final java.util.List<String> accountTypes;
    private final boolean holdsStudentBalances;

    PostingPurpose(boolean holdsStudentBalances, String... accountTypes) {
        this.accountTypes = java.util.List.of(accountTypes);
        this.holdsStudentBalances = holdsStudentBalances;
    }

    /** The type the account of this purpose should have: the first of {@link #accountTypes()}. */
    public String accountType() {
        return accountTypes.get(0);
    }

    /** Every type an account of this purpose may have. */
    public java.util.List<String> accountTypes() {
        return accountTypes;
    }

    /** Whether the account of this purpose is the one that carries the balance of each student. */
    public boolean holdsStudentBalances() {
        return holdsStudentBalances;
    }

    /** Whether an account of this type and student flag may be the account of this purpose. */
    public boolean accepts(String type, boolean accountHoldsStudentBalances) {
        return accountTypes.contains(type) && holdsStudentBalances == accountHoldsStudentBalances;
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

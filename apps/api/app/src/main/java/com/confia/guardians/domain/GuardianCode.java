package com.confia.guardians.domain;

/** The code of a guardian, generated from the counter of the institution: P-000001, P-000002... */
public final class GuardianCode {

    private static final String PREFIX = "P-";
    private static final int DIGITS = 6;
    private static final int MAX_LENGTH = 30;

    private GuardianCode() {
    }

    /** The code of the {@code sequence}-th guardian of an institution. */
    public static String generated(long sequence) {
        if (sequence < 1) {
            throw new IllegalArgumentException("a sequence starts at 1");
        }
        String digits = Long.toString(sequence);
        return PREFIX + "0".repeat(Math.max(0, DIGITS - digits.length())) + digits;
    }

    static String checked(String value) {
        return Names.required(value, MAX_LENGTH);
    }
}

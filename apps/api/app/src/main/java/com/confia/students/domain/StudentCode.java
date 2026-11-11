package com.confia.students.domain;

/** The code of a student: typed by the staff or generated from the counter of the institution. */
public final class StudentCode {

    private static final String PREFIX = "E-";
    private static final int DIGITS = 6;

    private StudentCode() {
    }

    /** A typed code: stripped, not blank, at most 30 characters. */
    static String typed(String value) {
        return Names.required(value, Names.MAX_CODE_LENGTH);
    }

    /** The code of the {@code sequence}-th student of an institution: E-000001, E-000002... */
    public static String generated(long sequence) {
        if (sequence < 1) {
            throw new IllegalArgumentException("a sequence starts at 1");
        }
        String digits = Long.toString(sequence);
        return PREFIX + "0".repeat(Math.max(0, DIGITS - digits.length())) + digits;
    }
}

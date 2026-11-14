package com.confia.guardians.domain;

/** The relationship of a guardian to the student of a link. */
public enum Relationship {
    FATHER, MOTHER, LEGAL_GUARDIAN, SELF, OTHER;

    /** The relationship with exactly this name; anything else, including {@code null}, is refused. */
    public static Relationship parse(String value) {
        if (value != null) {
            for (Relationship relationship : values()) {
                if (relationship.name().equals(value)) {
                    return relationship;
                }
            }
        }
        throw new InvalidGuardianException();
    }
}

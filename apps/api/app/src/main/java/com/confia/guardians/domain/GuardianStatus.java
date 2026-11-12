package com.confia.guardians.domain;

/** Whether a guardian is in use. A guardian is never deleted: it is made {@code INACTIVE}. */
public enum GuardianStatus {
    ACTIVE, INACTIVE;

    /** The status with exactly this name; anything else, including {@code null}, is refused. */
    public static GuardianStatus parse(String value) {
        if (value != null) {
            for (GuardianStatus status : values()) {
                if (status.name().equals(value)) {
                    return status;
                }
            }
        }
        throw new InvalidGuardianException();
    }
}

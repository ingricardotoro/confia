package com.confia.guardians.domain;

/**
 * What an edit asks of a protected member (the national identity document or the RTN): keep the stored one (the
 * value was omitted), remove it (the value was empty) or replace it with a new, already validated one.
 */
public sealed interface ProtectedValueChange<T> {

    /** Omitted value: the stored one stays as it is. */
    record Keep<T>() implements ProtectedValueChange<T> {
    }

    /** Empty value: the stored one is removed. */
    record Remove<T>() implements ProtectedValueChange<T> {
    }

    /** A new value, already validated. Redacted: the value is personal data. */
    record Replace<T>(T value) implements ProtectedValueChange<T> {

        @Override
        public String toString() {
            return "Replace[REDACTED]";
        }
    }

    /** A null keeps, a blank removes, anything else must be a valid document and replaces. */
    static ProtectedValueChange<NationalId> ofNationalId(String raw) {
        if (raw == null) {
            return new Keep<>();
        }
        if (raw.isBlank()) {
            return new Remove<>();
        }
        return new Replace<>(NationalId.of(raw));
    }

    /** A null keeps, a blank removes, anything else must be a valid RTN and replaces. */
    static ProtectedValueChange<Rtn> ofRtn(String raw) {
        if (raw == null) {
            return new Keep<>();
        }
        if (raw.isBlank()) {
            return new Remove<>();
        }
        return new Replace<>(Rtn.of(raw));
    }

    /** Whether the member is present after the change, given whether it was before. */
    default boolean presentAfter(boolean presentBefore) {
        return switch (this) {
            case Keep<T> keep -> presentBefore;
            case Remove<T> remove -> false;
            case Replace<T> replace -> true;
        };
    }
}

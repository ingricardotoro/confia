package com.confia.guardians.application;

import java.util.Objects;

/** What an update does to a protected stored member: leave it, store a new protected value, or clear it. */
public sealed interface Storage<T> {

    /** The stored value is left exactly as it is. */
    record Keep<T>() implements Storage<T> {
    }

    /** The stored value is removed. */
    record Clear<T>() implements Storage<T> {
    }

    /** The stored value is replaced. Redacted: it holds ciphertext. */
    record Store<T>(T value) implements Storage<T> {

        public Store {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toString() {
            return "Store[REDACTED]";
        }
    }
}

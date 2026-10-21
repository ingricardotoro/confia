package com.confia.shared.audit;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The typed builder of the detail JSON of an audit entry ({@code before_value} and {@code
 * after_value}); session-endpoints-cookie-and-csrf design.md, decision 17. No audit document is
 * assembled by concatenation any more: a value is added by its type, so an escape can never be
 * forgotten, and the document is never an arbitrary object.
 *
 * <p>Members come out in insertion order, chosen by the caller (it keeps the bytes each site wrote
 * before the migration), with no whitespace. A member is a text, an integer, a boolean or an
 * instant in its {@link Instant#toString()} form, and nothing else. Member names match {@code
 * ^[a-z][A-Za-z0-9]*$} and are never repeated, so a name never needs escaping; both rules throw
 * {@link IllegalArgumentException}. Text is escaped by {@link
 * CanonicalAuditRowSerializer#canonString}, the same routine the hash chain uses.
 */
public final class AuditJson {

    private static final Pattern MEMBER_NAME = Pattern.compile("[a-z][A-Za-z0-9]*");

    private AuditJson() {
    }

    /** An empty document; add members and read it with {@link Builder#toJson()}. */
    public static Builder object() {
        return new Builder();
    }

    /** Accumulates the members of one document. Not thread-safe; one per document. */
    public static final class Builder {

        private final StringBuilder members = new StringBuilder();
        private final Set<String> names = new HashSet<>();

        private Builder() {
        }

        /** A text member, escaped. */
        public Builder text(String name, String value) {
            Objects.requireNonNull(value, "value");
            return member(name, CanonicalAuditRowSerializer.canonString(value));
        }

        /** An integer member, as a JSON number. */
        public Builder number(String name, long value) {
            return member(name, Long.toString(value));
        }

        /** A boolean member, as {@code true} or {@code false}. */
        public Builder bool(String name, boolean value) {
            return member(name, value ? "true" : "false");
        }

        /** An instant member, as the text of {@link Instant#toString()}. */
        public Builder instant(String name, Instant value) {
            Objects.requireNonNull(value, "value");
            return member(name, CanonicalAuditRowSerializer.canonString(value.toString()));
        }

        /** The document so far, members in insertion order and no whitespace. */
        public String toJson() {
            return "{" + members + "}";
        }

        private Builder member(String name, String json) {
            Objects.requireNonNull(name, "name");
            if (!MEMBER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException(
                        "audit JSON member name must match [a-z][A-Za-z0-9]*");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("audit JSON member name added twice");
            }
            if (!members.isEmpty()) {
                members.append(',');
            }
            members.append('"').append(name).append("\":").append(json);
            return this;
        }
    }
}

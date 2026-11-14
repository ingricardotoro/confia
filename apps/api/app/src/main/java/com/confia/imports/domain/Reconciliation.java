package com.confia.imports.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The record of a signed reconciliation of the migration (docs/cortes/imports-reconciliation.md): for an academic year,
 * what the import created and left rejected of each kind, the active enrollments the system holds, and the total the
 * official roster of the institution declares, with the reference of that roster and who signed when. Counts and a short
 * reference only: nothing of a person. It is never changed; a correction is another record.
 *
 * <p>The difference between the active enrollments and the official total is derived ({@link #difference()}), never
 * stored.
 */
public record Reconciliation(UUID id, UUID academicYearId, String officialReference, int officialEnrollmentCount,
        int enrollmentsActive, KindCount students, KindCount guardians, KindCount guardianLinks,
        KindCount enrollments, UUID signedBy, Instant signedAt) {

    /** The longest reference of the official roster. */
    public static final int MAX_REFERENCE_LENGTH = 100;

    /** What the import of one kind created, and what its last application left rejected. */
    public record KindCount(int created, int rejected) {

        public KindCount {
            if (created < 0 || rejected < 0) {
                throw new IllegalArgumentException("a count is never negative");
            }
        }
    }

    public Reconciliation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(academicYearId, "academicYearId");
        Objects.requireNonNull(officialReference, "officialReference");
        Objects.requireNonNull(students, "students");
        Objects.requireNonNull(guardians, "guardians");
        Objects.requireNonNull(guardianLinks, "guardianLinks");
        Objects.requireNonNull(enrollments, "enrollments");
        Objects.requireNonNull(signedBy, "signedBy");
        Objects.requireNonNull(signedAt, "signedAt");
        if (officialEnrollmentCount < 0 || enrollmentsActive < 0) {
            throw new IllegalArgumentException("a count is never negative");
        }
        if (!isValidReference(officialReference)) {
            throw new IllegalArgumentException("the reference is the number of a document: 1 to "
                    + MAX_REFERENCE_LENGTH + " letters, digits and . , / # ° _ -, with a digit and no identity number");
        }
    }

    /** The characters of a reference: letters, digits, spaces and a few separators of document numbers. */
    private static final Pattern ALLOWED = Pattern.compile("[\\p{L}\\p{N} .,/#°_-]+");
    /** An identity number as it is written, with or without hyphens or spaces (thirteen digits). */
    private static final Pattern IDENTITY_NUMBER = Pattern.compile("(?<!\\d)\\d{4}[- ]?\\d{4}[- ]?\\d{5}(?!\\d)|\\d{13}");

    /**
     * Whether the text is a reference the record accepts. The reference is kept forever in a record nobody can correct
     * and in the audit trail, so it is the number of a document ("Oficio 123-2026"), not free text: letters, digits and
     * a few separators, at least one digit (a name alone has none) and nothing shaped like an identity number. It
     * cannot keep a person out of the text by itself; the screen asks the signer not to write names.
     */
    public static boolean isValidReference(String reference) {
        return reference != null && !reference.isEmpty() && reference.equals(reference.strip())
                && reference.length() <= MAX_REFERENCE_LENGTH && ALLOWED.matcher(reference).matches()
                && reference.chars().anyMatch(Character::isDigit) && !IDENTITY_NUMBER.matcher(reference).find();
    }

    /** The active enrollments minus the official total: positive when the system holds more than the roster says. */
    public int difference() {
        return enrollmentsActive - officialEnrollmentCount;
    }
}

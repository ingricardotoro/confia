package com.confia.guardians.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored link of a guardian to a student, with the day it started to apply and, once it has been replaced, the
 * day it stopped ({@code validTo}, {@code null} while the link is current). A closed link is history: nothing
 * edits it and it is never reopened; a change of percentage, relationship or flag closes it and opens a new link.
 */
public record GuardianLink(UUID id, UUID guardianId, UUID studentId, Relationship relationship, BigDecimal percent,
        boolean primaryPayer, boolean billingRecipient, LocalDate validFrom, LocalDate validTo, UUID createdBy,
        Instant createdAt) {

    public GuardianLink {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(relationship, "relationship");
        Objects.requireNonNull(percent, "percent");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("a link cannot end before it starts");
        }
    }

    /** A link that opens {@code on} for the draft's guardian and student. */
    static GuardianLink open(UUID id, UUID studentId, LinkDraft draft, LocalDate on, UUID createdBy, Instant now) {
        return new GuardianLink(id, draft.guardianId(), studentId, draft.relationship(), draft.percent(),
                draft.primaryPayer(), draft.billingRecipient(), on, null, createdBy, now);
    }

    public boolean current() {
        return validTo == null;
    }

    /** Whether the draft asks for exactly what this link already says (the percentage compared as a number). */
    boolean sameAs(LinkDraft draft) {
        return guardianId.equals(draft.guardianId()) && relationship == draft.relationship()
                && percent.compareTo(draft.percent()) == 0 && primaryPayer == draft.primaryPayer()
                && billingRecipient == draft.billingRecipient();
    }

    /** Personal data of a minor never reaches a log through this method. */
    @Override
    public String toString() {
        return "GuardianLink[id=" + id + "]";
    }
}

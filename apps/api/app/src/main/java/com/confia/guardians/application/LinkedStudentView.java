package com.confia.guardians.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A current link of a guardian, seen from the guardian: the student (code and names) and the link itself. */
public record LinkedStudentView(UUID linkId, UUID studentId, String studentCode, String studentFirstName,
        String studentLastName, String relationship, BigDecimal financialResponsibilityPercent,
        boolean primaryPayer, boolean billingRecipient, LocalDate validFrom) {

    /** The names of a minor never reach a log through this method. */
    @Override
    public String toString() {
        return "LinkedStudentView[linkId=" + linkId + "]";
    }
}

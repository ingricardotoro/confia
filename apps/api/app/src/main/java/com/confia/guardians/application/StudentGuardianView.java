package com.confia.guardians.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A current link of a student, seen from the student: the guardian (code, names, status) and the link itself. */
public record StudentGuardianView(UUID linkId, UUID guardianId, String guardianCode, String guardianFirstName,
        String guardianLastName, String guardianStatus, String relationship,
        BigDecimal financialResponsibilityPercent, boolean primaryPayer, boolean billingRecipient,
        LocalDate validFrom) {

    /** Personal data never reaches a log through this method. */
    @Override
    public String toString() {
        return "StudentGuardianView[linkId=" + linkId + "]";
    }
}

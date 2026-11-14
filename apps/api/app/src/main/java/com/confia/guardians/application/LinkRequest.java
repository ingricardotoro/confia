package com.confia.guardians.application;

import java.math.BigDecimal;
import java.util.UUID;

/** One link of the set that replaces the links of a student, as the request carries it (nothing validated yet). */
public record LinkRequest(UUID guardianId, String relationship, BigDecimal financialResponsibilityPercent,
        boolean primaryPayer, boolean billingRecipient) {
}

package com.confia.ledger.application;

import java.util.UUID;

/** A student sub-account that was opened or found and locked; {@code opened} tells the first entry created it. */
public record StudentAccountRef(UUID id, StudentCurrency key, String status, boolean opened) {

    public boolean underReview() {
        return "UNDER_REVIEW".equals(status);
    }
}

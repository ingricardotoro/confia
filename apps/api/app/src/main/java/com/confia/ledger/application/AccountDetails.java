package com.confia.ledger.application;

import java.util.UUID;

/** An account of the chart as the application reads it: what the directory of other modules publishes about it. */
public record AccountDetails(UUID id, String code, String name, String type, boolean holdsStudentBalances,
        boolean active) {
}

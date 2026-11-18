package com.confia.charges.domain;

/**
 * The state of a charge, DERIVED (a charge keeps no state column): {@code VOIDED} when a void record exists, {@code ACTIVE}
 * otherwise. Paid and overdue are derived from allocations and arrive with the payments cut (F4).
 */
public enum ChargeStatus {
    ACTIVE, VOIDED
}

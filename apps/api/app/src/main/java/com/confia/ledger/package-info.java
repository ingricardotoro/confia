/**
 * The {@code ledger} capability: the double-entry core of the money of the system (docs/cortes/ledger-core.md, F3 cut 1).
 * Accounts of the chart, a sub-account of each student per currency, transactions that balance, immutable entries, one
 * mirror reversal per transaction and a balance that is always derived from the entries (INV-01 to INV-07, INV-24,
 * INV-33, INV-37 to INV-42). Cut 1 is application services and the database only: no route, no screen. The services
 * {@code LedgerPosting} and {@code LedgerQueries} are what {@code charges} and {@code payments} will call in later cuts.
 * The ledger never rounds and never converts a currency: it refuses.
 */
package com.confia.ledger;

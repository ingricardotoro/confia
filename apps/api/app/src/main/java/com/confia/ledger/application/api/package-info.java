/**
 * The public API of {@code ledger} for the modules that post to it: {@link
 * com.confia.ledger.application.api.LedgerPostingApi}, which posts a balanced transaction and locks student sub-accounts
 * INSIDE the database transaction of the caller, and {@link com.confia.ledger.application.api.LedgerAccountDirectory}, a
 * read-only view of the accounts of the chart, and {@link com.confia.ledger.application.api.LedgerStatementApi}, the
 * read-only derived statement of a student receivable sub-account that {@code charges} enriches with its charges
 * (docs/cortes/ledger-account-statement.md), with the plain types they take and answer (no type of the ledger domain
 * crosses the boundary).
 *
 * <p><b>{@code @NamedInterface("api")}, and who consumes it</b> (ADR-0022: every new named interface names the real consumer
 * that justifies it). The consumer is {@code charges}, whose accrual engine posts the transaction of each charge and inserts
 * the charge in the same database transaction, locks the sub-accounts of the student before it reads what is already
 * accrued, and proves that the accounts of its posting map exist, are active and are of the right type
 * (docs/cortes/charges-accrual.md). This package is a nested one so the rest of {@code ledger.application}, the use cases
 * and the repository port, stays internal to the module.
 */
@org.springframework.modulith.NamedInterface("api")
package com.confia.ledger.application.api;

package com.confia.ledger.application;

import com.confia.ledger.domain.AccountType;

/** The optional filters of a list of accounts: the type and the active flag; {@code null} means no filter. */
public record AccountFilter(AccountType type, Boolean active) {
}

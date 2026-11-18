package com.confia.charges.infrastructure;

import static confia.generated.jooq.tables.ChargesPostingAccount.CHARGES_POSTING_ACCOUNT;

import com.confia.kernel.InstitutionId;
import com.confia.ledger.application.api.LedgerAccountUsage;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Tells the ledger that an account of the chart is in use by the posting map of the institution, so the ledger refuses to
 * deactivate it (docs/cortes/ledger-chart-of-accounts.md). It only reads {@code charges_posting_account}, inside the
 * transaction of the caller.
 */
public final class PostingAccountUsage implements LedgerAccountUsage {

    private final DSLContext dsl;

    public PostingAccountUsage(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean isReferenced(InstitutionId institutionId, UUID accountId) {
        return dsl.fetchExists(CHARGES_POSTING_ACCOUNT, CHARGES_POSTING_ACCOUNT.INSTITUTION_ID.eq(institutionId.value())
                .and(CHARGES_POSTING_ACCOUNT.LEDGER_ACCOUNT_ID.eq(accountId)));
    }
}

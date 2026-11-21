package com.confia.payments.infrastructure;

import static confia.generated.jooq.tables.PaymentsPostingAccount.PAYMENTS_POSTING_ACCOUNT;

import com.confia.kernel.InstitutionId;
import com.confia.ledger.application.api.LedgerAccountUsage;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Tells the ledger that an account of the chart is in use by the payments posting map of the institution (cash and bank), so the
 * ledger refuses to deactivate it (docs/cortes/ledger-chart-of-accounts.md). It only reads {@code payments_posting_account},
 * inside the transaction of the caller.
 */
public final class PostingAccountUsage implements LedgerAccountUsage {

    private final DSLContext dsl;

    public PostingAccountUsage(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean isReferenced(InstitutionId institutionId, UUID accountId) {
        return dsl.fetchExists(PAYMENTS_POSTING_ACCOUNT, PAYMENTS_POSTING_ACCOUNT.INSTITUTION_ID.eq(institutionId.value())
                .and(PAYMENTS_POSTING_ACCOUNT.LEDGER_ACCOUNT_ID.eq(accountId)));
    }
}

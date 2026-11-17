package com.confia.charges.infrastructure;

import static confia.generated.jooq.tables.ChargesPostingAccount.CHARGES_POSTING_ACCOUNT;

import com.confia.charges.application.PostingAccountRepository;
import com.confia.charges.domain.PostingAccountMap;
import com.confia.charges.domain.PostingPurpose;
import com.confia.kernel.InstitutionId;
import java.util.List;
import org.jooq.DSLContext;

/** The jOOQ adapter of {@link PostingAccountRepository}: the map of posting accounts, read from the table as data. */
public final class JooqPostingAccountRepository implements PostingAccountRepository {

    private final DSLContext dsl;

    public JooqPostingAccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<PostingAccountMap.Entry> list(InstitutionId institution) {
        return dsl.selectFrom(CHARGES_POSTING_ACCOUNT)
                .where(CHARGES_POSTING_ACCOUNT.INSTITUTION_ID.eq(institution.value()))
                .orderBy(CHARGES_POSTING_ACCOUNT.PURPOSE, CHARGES_POSTING_ACCOUNT.CONCEPT_CATEGORY)
                .fetch(row -> new PostingAccountMap.Entry(PostingPurpose.valueOf(row.getPurpose()),
                        row.getConceptCategory(), row.getLedgerAccountId()));
    }
}

package com.confia.identity.infrastructure;

import static confia.generated.jooq.tables.IdentityStaffAccount.IDENTITY_STAFF_ACCOUNT;

import com.confia.identity.application.StaffAccountRepository;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.StaffAccount;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StoredPasswordHash;
import com.confia.kernel.InstitutionId;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * The single jOOQ adapter of {@link StaffAccountRepository} (design.md, §6.1, §10 sonda S4; ADR-0015
 * rule 4, R1: jOOQ confined to {@code infrastructure}; rule 3, R2: {@link
 * confia.generated.jooq.tables.IdentityStaffAccount}'s generated table type carries this module's
 * own {@code Identity} prefix, confirmed by sonda S4). {@code final}, with an explicit constructor
 * over {@link DSLContext} and no Spring annotation, the same pattern {@code
 * JooqIdempotencyRecordStore} already established: no bootstrap process registers this as a bean
 * yet.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7, R3): this method always runs
 * inside the transaction {@link com.confia.shared.security.TransactionRunner} already opened,
 * participating through the same {@link DSLContext} the caller's {@code
 * TransactionAwareDataSourceProxy}-backed data source binds to that transaction.
 *
 * <p>The row-level-security policy on {@code identity_staff_account} (design.md, decision 4) is
 * what actually scopes this lookup to one institution: the explicit {@code institutionId} equality
 * below is a query predicate on top of that policy, not a substitute for it — a caller with the
 * wrong session context would see zero rows regardless of what this method asks for.
 */
public final class JooqStaffAccountRepository implements StaffAccountRepository {

    private final DSLContext dsl;

    public JooqStaffAccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<StaffAccount> findBy(InstitutionId institutionId, LoginIdentifier identifier) {
        return dsl.selectFrom(IDENTITY_STAFF_ACCOUNT)
                .where(IDENTITY_STAFF_ACCOUNT.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_STAFF_ACCOUNT.EMAIL.eq(identifier.value()))
                .fetchOptional(record -> new StaffAccount(new StaffAccountId(record.getId()),
                        institutionId, identifier,
                        new StoredPasswordHash(record.getPasswordHash()),
                        record.getMfaRequired()));
    }
}

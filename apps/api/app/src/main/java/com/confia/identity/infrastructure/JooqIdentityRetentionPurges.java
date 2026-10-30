package com.confia.identity.infrastructure;

import com.confia.identity.application.IdentityRetentionPurges;
import com.confia.kernel.InstitutionId;
import confia.generated.jooq.routines.IdentityPurgePasswordResetTokens;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import org.jooq.DSLContext;

/**
 * The jOOQ adapter of {@link IdentityRetentionPurges} (background-jobs-with-db-scheduler design.md
 * decisions 16 and 17; ADR-0015 rule 4: jOOQ confined to {@code infrastructure}). {@code final}, with an
 * explicit constructor over {@link DSLContext} and no Spring annotation.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7): it runs inside the one the use case
 * opened, whose context holds the institution and the {@code system} actor the functions require. The
 * deletes go only through the generated routines of the {@code SECURITY DEFINER} functions, with every
 * value bound; no role has {@code DELETE} on these tables.
 */
public final class JooqIdentityRetentionPurges implements IdentityRetentionPurges {

    private final DSLContext dsl;

    public JooqIdentityRetentionPurges(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public long purgePasswordResetTokens(InstitutionId institution, Instant cutoff, int limit) {
        IdentityPurgePasswordResetTokens routine = new IdentityPurgePasswordResetTokens();
        routine.setPCutoff(cutoff.atOffset(ZoneOffset.UTC));
        routine.setPLimit(limit);
        routine.execute(dsl.configuration());
        return routine.getReturnValue();
    }
}

package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.AuthenticationResult.Authenticated;
import com.confia.identity.domain.PasswordResetTokenHash;
import com.confia.identity.domain.PasswordResetTokenRow;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqPasswordResetTokenRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The token's consumption, the new hash and the audit entry are one atomic unit
 * (password-recovery-token design.md decision 7; specs/identity/spec.md, "Un restablecimiento que no
 * confirma no deja consumo, ni cambio, ni asiento"). The failure is deterministic: an audit writer
 * that throws on the {@code completed} entry, which the use case writes only after consuming the
 * token and replacing the hash. A spy on the token repository proves the consumption did happen
 * before the rollback, so a green result cannot come from the failure firing too early.
 */
class PasswordResetAtomicityIT extends PasswordResetIntegrationTest {

    private static final String ANA = "ana.martinez@colegio.edu.hn";
    private static final String CURRENT = "Cafetal de Copán 2026";
    private static final String NEW = "Lempira y maíz 2026";

    /** Scenario I20. */
    @Test
    void aResetThatDoesNotCommitLeavesNoConsumptionNoNewPasswordAndNoEntry() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT, false);
        PlainPasswordResetToken token = issueTokenAt(ana, "2026-10-15T15:00:00Z");
        int entriesBefore = auditRows().size();
        List<Boolean> consumptions = new ArrayList<>();
        PasswordResetTokenRepository spy = spyOn(new JooqPasswordResetTokenRepository(dsl),
                consumptions);
        JooqAuditLogWriter realAudit = new JooqAuditLogWriter(dsl);
        AuditLogWriter failingOnCompletion = entry -> {
            if (entry.action().equals("identity.password_reset.completed")) {
                throw new IllegalStateException("audit unavailable after consume and rehash");
            }
            realAudit.append(entry);
        };

        assertThatThrownBy(() -> resetWith(token.value(), NEW, new SecondFactorProof.None(),
                "2026-10-15T15:05:00Z", failingOnCompletion, spy))
                .isInstanceOf(IllegalStateException.class);

        assertThat(consumptions)
                .as("the failure must come after the token was consumed, or this proves nothing")
                .containsExactly(true);
        assertThat(isOpen(token)).as("the consumption rolled back").isTrue();
        assertThat(loginAt(ANA, CURRENT, "2026-10-15T15:06:00Z"))
                .as("the new hash rolled back").isInstanceOf(Authenticated.class);
        assertThat(auditRows().size() - entriesBefore)
                .as("no reset entry survives; only the login just above wrote one")
                .isEqualTo(1);
        assertThat(auditRows()).noneMatch(row -> row.action().startsWith("identity.password_reset.")
                && !row.action().equals("identity.password_reset.issued"));
    }

    /** The real adapter, recording the result of every consumption. */
    private static PasswordResetTokenRepository spyOn(PasswordResetTokenRepository delegate,
            List<Boolean> consumptions) {
        return new PasswordResetTokenRepository() {
            @Override
            public long countIssuedSince(InstitutionId i,
                    StaffAccountId a, Instant since) {
                return delegate.countIssuedSince(i, a, since);
            }

            @Override
            public int supersedeOpen(InstitutionId i, StaffAccountId a,
                    Instant at) {
                return delegate.supersedeOpen(i, a, at);
            }

            @Override
            public void insert(InstitutionId i, UUID id,
                    StaffAccountId a, PasswordResetTokenHash h,
                    Instant issuedAt, Instant expiresAt) {
                delegate.insert(i, id, a, h, issuedAt, expiresAt);
            }

            @Override
            public Optional<PasswordResetTokenRow> findByHash(
                    InstitutionId i,
                    PasswordResetTokenHash h) {
                return delegate.findByHash(i, h);
            }

            @Override
            public boolean consume(InstitutionId i, UUID id,
                    Instant at) {
                boolean won = delegate.consume(i, id, at);
                consumptions.add(won);
                return won;
            }
        };
    }
}

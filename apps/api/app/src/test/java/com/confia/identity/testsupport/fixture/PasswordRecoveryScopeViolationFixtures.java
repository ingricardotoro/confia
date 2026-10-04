package com.confia.identity.testsupport.fixture;

import com.confia.identity.application.PasswordResetIssuanceScheduler;
import com.confia.identity.application.PasswordResetLinkSender;
import com.confia.identity.application.StaffAccountRepository;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StoredPasswordHash;
import com.confia.kernel.InstitutionId;

/**
 * Permanent, deliberately violating fixtures for the absence rules of {@code
 * IdentityScopeExclusionInventoryTest} (password-recovery-token design.md decision 11): each class
 * breaks exactly one rule, so the rule's own test can prove it rejects a real violation instead of
 * merely finding none. Never production code, and imported one class at a time, never by scanning
 * the package.
 */
public final class PasswordRecoveryScopeViolationFixtures {

    private PasswordRecoveryScopeViolationFixtures() {
    }

    /** A production-shaped adapter for the issuance scheduling port. */
    public static final class SchedulerAdapter implements PasswordResetIssuanceScheduler {

        @Override
        public void schedule(InstitutionId institutionId, StaffAccountId accountId) {
            // Deliberately empty: only the shape matters to the rule.
        }
    }

    /** A production-shaped adapter for the link delivery port. */
    public static final class LinkSenderAdapter implements PasswordResetLinkSender {

        @Override
        public void send(InstitutionId institutionId, StaffAccountId accountId,
                PlainPasswordResetToken token) {
            // Deliberately empty: only the shape matters to the rule.
        }
    }

    /** A second path to change a password, other than the reset with a token. */
    public static final class SecondPasswordWriter {

        private final StaffAccountRepository accounts;

        public SecondPasswordWriter(StaffAccountRepository accounts) {
            this.accounts = accounts;
        }

        public boolean overwrite(InstitutionId institutionId, StaffAccountId accountId,
                StoredPasswordHash hash) {
            return accounts.replacePasswordHash(institutionId, accountId, hash);
        }
    }

    /** Something that mentions a refresh token, which no identity class may do yet. */
    public static final class RefreshTokenFamilyRevoker {

        public int revokeAllRefreshTokensOf(StaffAccountId accountId) {
            return 0;
        }
    }
}

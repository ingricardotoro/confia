package com.confia.identity.application;

import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqRefreshTokenFamilyRepository;
import com.confia.identity.infrastructure.JooqRefreshTokenRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.ClientAddress;
import com.confia.shared.security.token.AccessTokenIssuer;
import com.confia.shared.security.token.AccessTokenVerifier;
import com.confia.shared.security.token.CompactJws;
import com.confia.shared.security.token.SigningKey;
import com.confia.shared.security.token.SigningKeyRing;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;

/**
 * Shared set-up for the session-issuance integration tests (session-tokens-and-web-layer tasks.md
 * 3.3): the real adapters and a real {@link IssueSession} whose access tokens are signed with a key
 * pair generated for the run (no key is versioned, regla 13). Abstract, so the {@code *IT} naming
 * rule does not apply to it.
 */
abstract class SessionIssuanceIntegrationTest extends PasswordResetIntegrationTest {

    static final String ANA = "ana.martinez@colegio.edu.hn";
    static final String CURRENT_PASSWORD = "Cafetal de Copán 2026";
    private static final String KID = "admin-2026a";
    private static final KeyPair KEYS = generatedPair();
    private static final CompactJws JWS = new CompactJws(
            SigningKeyRing.of(SigningKey.signing(KID, KEYS.getPublic(), KEYS.getPrivate())));

    IssueSession issueSession(String instant) {
        return issueSession(instant, new JooqAuditLogWriter(dsl),
                new JooqRefreshTokenRepository(dsl));
    }

    IssueSession issueSession(String instant, AuditLogWriter auditLogWriter,
            RefreshTokenRepository tokens) {
        return issueSession(fixedClock(instant), auditLogWriter, tokens);
    }

    IssueSession issueSession(Clock clock, AuditLogWriter auditLogWriter,
            RefreshTokenRepository tokens) {
        return new IssueSession(transactionRunner(), new JooqStaffAccountRepository(dsl),
                new JooqRefreshTokenFamilyRepository(dsl), tokens,
                new AccessTokenIssuer(JWS, clock), auditLogWriter, clock, new SecureRandom());
    }

    AccessTokenVerifier verifierAt(String instant) {
        return new AccessTokenVerifier(JWS, fixedClock(instant));
    }

    IssuedSession issue(String instant, StaffAccountId account) {
        return issueSession(instant).execute(context(),
                new IssueSessionCommand(account, false, null, null));
    }

    static IssueSessionCommand commandFrom(StaffAccountId account, boolean secondFactor,
            String userAgent, String address) {
        return new IssueSessionCommand(account, secondFactor, userAgent,
                address == null ? null : ClientAddress.parseLiteral(address));
    }

    private static KeyPair generatedPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

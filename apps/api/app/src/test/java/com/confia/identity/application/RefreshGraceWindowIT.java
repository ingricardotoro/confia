package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.application.RefreshOutcome.Rejected;
import com.confia.identity.application.RefreshOutcome.Rotated;
import com.confia.identity.application.RefreshOutcome.RotationKind;
import com.confia.identity.domain.PlainRefreshToken;
import com.confia.identity.domain.RefreshRejection;
import com.confia.identity.domain.RefreshTokenHash;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqAccountSessionRevoker;
import com.confia.identity.infrastructure.JooqRefreshTokenFamilyRepository;
import com.confia.identity.infrastructure.JooqRefreshTokenRepository;
import com.confia.kernel.AeadIntegrityException;
import com.confia.kernel.AesGcmCipher;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.crypto.DataEncryptionKeyId;
import com.confia.shared.crypto.DataEncryptionKeyMaterial;
import com.confia.shared.crypto.DataEncryptionKeyRepository;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The 10-second grace window (session-tokens-and-web-layer design.md decision 11, D-N1; ADR-0005,
 * test 2; scenarios I85 to I103, I141 and the absence of purge of the residual encrypted successor),
 * against a real PostgreSQL, the real adapters and the real column-encryption service. A retry from
 * the same device inside the window returns the <b>same</b> refresh token the first rotation handed
 * out and a <b>new</b> access token; the successor is stored encrypted and bound to the row that
 * holds it; it is never decrypted outside the window (the key repository counts how often it is asked
 * for a key to decrypt) and it is emptied at the next use of the family.
 *
 * <p>Sessions that a real issuance and rotation can reach are made with them; the ones that need a
 * window or a ciphertext they could not produce (the ciphertext copied to another row) are seeded
 * with SQL.
 */
class RefreshGraceWindowIT extends RotationIntegrationTest {

    private static final String START = "2026-10-12T09:00:00Z";
    private static final String ROTATED_AT = "2026-10-12T09:15:00Z";
    private static final String EIGHT = "2026-10-12T08:00:00Z";
    private static final String SIXTEEN = "2026-10-12T16:00:00Z";
    private static final String TWENTY = "2026-10-12T20:00:00Z";
    private static final String TABLE = "identity_refresh_token";
    private static final String COLUMN = "successor_token_ciphertext";
    private static final String ACTION_REFRESHED = "identity.session.refreshed";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A session issued at {@link #START} and rotated once at {@link #ROTATED_AT} by {@code agent}. */
    private record Rotation(StaffAccountId account, IssuedSession first, Rotated fresh) {
    }

    /** Scenarios I96 and I141: the same refresh token, a new access token with the same claims. */
    @Test
    void aRetryInsideTheWindowFromTheSameDeviceReturnsTheSameRefreshTokenAndANewAccessToken() {
        Rotation rotation = rotatedOnce(ANA, true, AGENT_ONE);

        RefreshOutcome retry = rotate("2026-10-12T09:15:09Z", rotation.first().refreshToken().value(),
                AGENT_ONE);

        assertThat(retry).isInstanceOf(Rotated.class);
        Rotated graced = (Rotated) retry;
        assertThat(graced.kind()).isEqualTo(RotationKind.GRACE);
        assertThat(graced.session().refreshToken())
                .as("the very same successor that was handed out")
                .isEqualTo(rotation.fresh().session().refreshToken());
        assertThat(graced.session().refreshExpiresAt())
                .isEqualTo(rotation.fresh().session().refreshExpiresAt());
        assertThat(graced.session().sessionId()).isEqualTo(rotation.first().sessionId());

        AuthenticatedActor before = verifierAt(ROTATED_AT)
                .verifyAccess(rotation.fresh().session().accessToken().compact());
        AuthenticatedActor after = verifierAt("2026-10-12T09:15:09Z")
                .verifyAccess(graced.session().accessToken().compact());
        assertThat(graced.session().accessToken().compact())
                .isNotEqualTo(rotation.fresh().session().accessToken().compact());
        assertThat(after.tokenId()).as("another jti").isNotEqualTo(before.tokenId());
        assertThat(after.issuedAt()).as("another iat").isNotEqualTo(before.issuedAt());
        assertThat(after.sessionId()).isEqualTo(before.sessionId());
        assertThat(after.accountId()).isEqualTo(before.accountId());
        assertThat(after.institutionId()).isEqualTo(before.institutionId());
        assertThat(after.methods()).isEqualTo(before.methods()).hasSize(2);
        assertThat(Duration.between(after.issuedAt(), after.expiresAt()))
                .isEqualTo(Duration.ofSeconds(600));

        Record family = familyRow(rotation.first().sessionId());
        assertThat(family.get("revoked_at")).as("the family is not revoked").isNull();
        assertThat(instantOf(family, "last_used_at")).isEqualTo(Instant.parse("2026-10-12T09:15:09Z"));
        assertThat(tokensOf(rotation.first().sessionId())).as("no token was issued by the retry")
                .hasSize(2);
        assertThat(liveTokenCount()).as("a single open token").isEqualTo(1);
        assertThat(auditRows(ACTION_REFRESHED)).hasSize(2).last().satisfies(row -> {
            assertThat(row.outcome()).isEqualTo("success");
            assertThat(JSON.readTree(row.afterValue()).get("rotation").asString()).isEqualTo("grace");
        });
    }

    /** Scenario I97, the inside of the border: a millisecond before the tenth second is still a retry. */
    @Test
    void aMillisecondBeforeTheTenthSecondIsStillAGraceReplay() {
        Rotation rotation = rotatedOnce(ANA, false, AGENT_ONE);

        RefreshOutcome retry = rotate("2026-10-12T09:15:09.999Z",
                rotation.first().refreshToken().value(), AGENT_ONE);

        assertThat(retry).isInstanceOf(Rotated.class);
        assertThat(((Rotated) retry).kind()).isEqualTo(RotationKind.GRACE);
    }

    /** Scenario I97: exactly ten seconds is already a reuse, and the successor is not decrypted. */
    @Test
    void exactlyTheTenthSecondIsAReuseAndTheSuccessorIsNotDecrypted() {
        Rotation rotation = rotatedOnce(ANA, false, AGENT_ONE);
        AtomicInteger decryptions = new AtomicInteger();

        RefreshOutcome retry = rotateCounting("2026-10-12T09:15:10Z",
                rotation.first().refreshToken().value(), AGENT_ONE, decryptions);

        assertThat(retry).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        assertThat(decryptions).as("the window is closed: nothing is decrypted").hasValue(0);
        assertThat(familyRow(rotation.first().sessionId()).get("revoked_reason", String.class))
                .isEqualTo("reuse_detected");
    }

    /** Scenario I98: another device inside the window is a reuse, never a retry. */
    @Test
    void anotherFingerprintInsideTheWindowIsAReuseAndRevokesTheFamily() {
        Rotation rotation = rotatedOnce(ANA, false, AGENT_ONE);
        AtomicInteger decryptions = new AtomicInteger();

        RefreshOutcome retry = rotateCounting("2026-10-12T09:15:05Z",
                rotation.first().refreshToken().value(), AGENT_TWO, decryptions);

        assertThat(retry).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        assertThat(decryptions).hasValue(0);
        Record family = familyRow(rotation.first().sessionId());
        assertThat(family.get("revoked_reason", String.class)).isEqualTo("reuse_detected");
        assertThat(liveTokenCount()).isZero();
    }

    /** Scenario I99, stored side: encrypted ({@code v1:}), not in clear, and it authenticates only for its own row. */
    @Test
    void theSuccessorIsStoredEncryptedAndAuthenticatesOnlyForTheRowThatHoldsIt() throws Exception {
        Rotation rotation = rotatedOnce(ANA, false, AGENT_ONE);
        List<Record> tokens = tokensOf(rotation.first().sessionId());
        String stored = tokens.get(0).get(COLUMN, String.class);
        UUID consumedId = tokens.get(0).get("id", UUID.class);
        UUID successorId = tokens.get(1).get("id", UUID.class);
        String successorText = rotation.fresh().session().refreshToken().value();

        assertThat(stored).startsWith("v1:").doesNotContain(successorText);
        assertThat(new String(decrypt(consumedId, stored), StandardCharsets.UTF_8))
                .isEqualTo(successorText);
        assertThatThrownBy(() -> decrypt(successorId, stored))
                .isInstanceOf(AeadIntegrityException.class);
    }

    /**
     * Scenario I99, end to end: the ciphertext of one row copied into another row (a consumed token
     * of another family, inside its own window and presented by the same device) does not decrypt, and
     * the failure is a failure of the request: it neither returns the first session's token nor leaves
     * anything written. This is the test the demonstration of 4.3 breaks (the row removed from the AAD).
     */
    @Test
    void aCiphertextCopiedToAnotherRowDoesNotDecryptAndTheRequestFailsWithoutWritingAnything() {
        Rotation rotation = rotatedOnce(ANA, false, AGENT_ONE);
        String stored = tokensOf(rotation.first().sessionId()).get(0).get(COLUMN, String.class);
        String successorText = rotation.fresh().session().refreshToken().value();
        UUID otherFamily = seedFamily(rotation.account(), false, EIGHT, ROTATED_AT, TWENTY, null,
                null);
        ConsumedSeed copied = seedConsumedToken(otherFamily, EIGHT, SIXTEEN, ROTATED_AT, AGENT_ONE,
                stored);
        String before = storedSessions();

        assertThatThrownBy(() -> rotate("2026-10-12T09:15:05Z", copied.consumed().value(), AGENT_ONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be authenticated")
                .hasMessageNotContaining(successorText)
                .hasMessageNotContaining(stored);

        assertThat(storedSessions()).as("the failed request rolled back").isEqualTo(before);
    }

    /** Scenario I100: after ten seconds the value is still in the row, and it is not decrypted. */
    @Test
    void afterTenSecondsTheValueStaysInTheRowUntilTheNextContactAndIsNeverDecrypted() {
        Rotation rotation = rotatedOnce(ANA, false, AGENT_ONE);
        AtomicInteger decryptions = new AtomicInteger();
        UUID consumedId = tokensOf(rotation.first().sessionId()).get(0).get("id", UUID.class);

        assertThat(tokensOf(rotation.first().sessionId()).get(0).get(COLUMN))
                .as("with no contact since the rotation the value is still there").isNotNull();
        RefreshOutcome retry = rotateCounting("2026-10-12T09:15:11Z",
                rotation.first().refreshToken().value(), AGENT_ONE, decryptions);

        assertThat(retry).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        assertThat(decryptions).as("the successor was not decrypted").hasValue(0);
        assertThat(tokensOf(rotation.first().sessionId()).get(0).get("id", UUID.class))
                .isEqualTo(consumedId);
        assertThat(tokensOf(rotation.first().sessionId()).get(0).get(COLUMN))
                .as("and that contact emptied it").isNull();
    }

    /**
     * Scenario I101, first execution: the rotation of the open successor empties the value of every
     * token consumed ten seconds ago or more, and keeps the one consumed a millisecond more recently.
     */
    @Test
    void rotatingTheFamilyEmptiesTheSuccessorsConsumedTenSecondsAgoOrMore() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID kept = seedFamily(ana, false, EIGHT, "2026-10-12T10:00:00Z", TWENTY, null, null);
        ConsumedSeed keptSeed = seedConsumedToken(kept, EIGHT, SIXTEEN, "2026-10-12T10:00:00Z",
                AGENT_ONE, true);
        UUID emptied = seedFamily(ana, false, EIGHT, "2026-10-12T10:00:00Z", TWENTY, null, null);
        ConsumedSeed emptiedSeed = seedConsumedToken(emptied, EIGHT, SIXTEEN, "2026-10-12T10:00:00Z",
                AGENT_ONE, true);

        rotate("2026-10-12T10:00:09.999Z", keptSeed.successor().value(), AGENT_ONE);
        rotate("2026-10-12T10:00:10Z", emptiedSeed.successor().value(), AGENT_ONE);

        assertThat(tokensOf(kept).get(0).get(COLUMN, String.class))
                .as("consumed 9.999 s ago: still replayable, kept").isEqualTo("v1:AAAA");
        assertThat(tokensOf(emptied).get(0).get(COLUMN))
                .as("consumed exactly 10 s ago: emptied").isNull();
    }

    /** Scenario I101, second execution: a revocation of the family empties the value too. */
    @Test
    void revokingTheFamilyEmptiesTheSuccessor() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, "2026-10-12T10:00:00Z", TWENTY, null, null);
        seedConsumedToken(family, EIGHT, SIXTEEN, "2026-10-12T10:00:00Z", AGENT_ONE, true);
        JooqAccountSessionRevoker revoker = new JooqAccountSessionRevoker(dsl);

        transactionRunner().execute(context(), () -> revoker.revokeAllLive(institutionId, ana,
                SessionRevocationReason.PASSWORD_CHANGE, Instant.parse("2026-10-12T10:00:30Z")));

        assertThat(tokensOf(family).get(0).get(COLUMN)).isNull();
    }

    /** Scenario I102: the successor was already consumed, so there is nothing left to hand back. */
    @Test
    void aFamilyThatAlreadyAdvancedGivesNoGrace() {
        Rotation rotation = rotatedOnce(ANA, false, AGENT_ONE);
        Rotated second = (Rotated) rotate("2026-10-12T09:15:03Z",
                rotation.fresh().session().refreshToken().value(), AGENT_ONE);
        assertThat(second.kind()).isEqualTo(RotationKind.FRESH);
        AtomicInteger decryptions = new AtomicInteger();

        RefreshOutcome retry = rotateCounting("2026-10-12T09:15:06Z",
                rotation.first().refreshToken().value(), AGENT_ONE, decryptions);

        assertThat(retry).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        assertThat(decryptions).hasValue(0);
        assertThat(familyRow(rotation.first().sessionId()).get("revoked_reason", String.class))
                .isEqualTo("reuse_detected");
    }

    /** Scenario I103: no user agent on either presentation is the same device. */
    @Test
    void noUserAgentOnEitherPresentationIsTheSameDevice() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession first = issueSession(START).execute(context(),
                commandFrom(ana, false, null, null));
        Rotated fresh = (Rotated) rotate(ROTATED_AT, first.refreshToken().value(), null);

        RefreshOutcome retry = rotate("2026-10-12T09:15:04Z", first.refreshToken().value(), null);

        assertThat(retry).isInstanceOf(Rotated.class);
        assertThat(((Rotated) retry).kind()).isEqualTo(RotationKind.GRACE);
        assertThat(((Rotated) retry).session().refreshToken())
                .isEqualTo(fresh.session().refreshToken());
    }

    @Test
    void anAbsentUserAgentAndAPresentOneAreNotTheSameDevice() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession first = issueSession(START).execute(context(),
                commandFrom(ana, false, null, null));
        rotate(ROTATED_AT, first.refreshToken().value(), null);

        RefreshOutcome retry = rotate("2026-10-12T09:15:04Z", first.refreshToken().value(),
                AGENT_ONE);

        assertThat(retry).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
    }

    /**
     * The absence of a purge (declared limit until change 9): the encrypted successor of a token
     * consumed an hour ago, with no contact from its own family, is still in the row after another
     * family was used. Nothing empties it by time.
     */
    @Test
    void theResidualSuccessorIsKeptUntilItsOwnFamilyIsTouchedAgain() {
        Rotation rotation = rotatedOnce(ANA, false, AGENT_ONE);
        UUID elsewhere = seedFamily(rotation.account(), false, EIGHT, "2026-10-12T10:14:00Z",
                TWENTY, null, null);
        PlainRefreshToken open = seedOpenToken(elsewhere, EIGHT, SIXTEEN);

        RefreshOutcome other = rotate("2026-10-12T10:15:00Z", open.value(), AGENT_ONE);

        assertThat(other).isInstanceOf(Rotated.class);
        List<Record> tokens = tokensOf(rotation.first().sessionId());
        assertThat(tokens).as("the rows are all still there").hasSize(2);
        assertThat(tokens.get(0).get(COLUMN, String.class))
                .as("an hour after its consumption the value is still in the database")
                .startsWith("v1:");
    }

    /** Scenario I85, stored side: nothing the audit trail holds names a token, a hash or the ciphertext. */
    @Test
    void theAuditTrailOfARotationAndOfItsRetryCarriesNoSecret() {
        Rotation rotation = rotatedOnce(ANA, false, AGENT_ONE);
        rotate("2026-10-12T09:15:05Z", rotation.first().refreshToken().value(), AGENT_ONE);
        String stored = tokensOf(rotation.first().sessionId()).get(0).get(COLUMN, String.class);

        String trail = auditRows().stream().map(AuditRowSnapshot::toString)
                .reduce("", String::concat);

        for (String secret : List.of(rotation.first().refreshToken().value(),
                rotation.fresh().session().refreshToken().value(),
                RefreshTokenHash.of(rotation.first().refreshToken()).value(),
                RefreshTokenHash.of(rotation.fresh().session().refreshToken()).value(), stored,
                rotation.fresh().session().accessToken().compact())) {
            assertThat(trail).doesNotContain(secret);
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** Decrypts as the use case does, in a transaction of the institution (the key is read under row-level security). */
    private byte[] decrypt(UUID rowId, String stored) throws AeadIntegrityException {
        try {
            return transactionRunner().execute(context(), () -> {
                try {
                    return encryptionService().decrypt(TABLE, COLUMN, institutionId,
                            rowId.toString(), stored);
                } catch (AeadIntegrityException e) {
                    throw new Unauthenticated(e);
                }
            });
        } catch (Unauthenticated e) {
            throw (AeadIntegrityException) e.getCause();
        }
    }

    /** Carries a checked failure of the cipher across the transaction. */
    private static final class Unauthenticated extends RuntimeException {

        Unauthenticated(AeadIntegrityException cause) {
            super(cause);
        }
    }

    private Rotation rotatedOnce(String email, boolean secondFactor, String agent) {
        StaffAccountId account = seedStaffAccount(email, CURRENT_PASSWORD, secondFactor);
        IssuedSession first = issueSession(START).execute(context(),
                commandFrom(account, secondFactor, agent, null));
        Rotated fresh = (Rotated) rotate(ROTATED_AT, first.refreshToken().value(), agent);
        assertThat(fresh.kind()).isEqualTo(RotationKind.FRESH);
        return new Rotation(account, first, fresh);
    }

    /** The rotation over an encryption service that counts how many times it is asked to decrypt. */
    private RefreshOutcome rotateCounting(String instant, String presentedToken, String userAgent,
            AtomicInteger decryptions) {
        DataEncryptionKeyRepository real = keyRepository();
        DataEncryptionKeyRepository counting = new DataEncryptionKeyRepository() {
            @Override
            public DataEncryptionKeyMaterial findActiveOrCreate(InstitutionId institution) {
                return real.findActiveOrCreate(institution);
            }

            @Override
            public DataEncryptionKeyMaterial findById(InstitutionId institution,
                    DataEncryptionKeyId id) {
                decryptions.incrementAndGet();
                return real.findById(institution, id);
            }
        };
        return rotation(fixedClock(instant), new JooqAuditLogWriter(dsl), institutionId,
                new JooqRefreshTokenFamilyRepository(dsl), new JooqRefreshTokenRepository(dsl),
                new ColumnEncryptionService(counting, new AesGcmCipher()))
                .execute(context(), new RotateRefreshTokenCommand(presentedToken, userAgent));
    }
}

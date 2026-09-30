package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.application.RequestPasswordResetTest.RecordingScheduler;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PasswordResetTokenHash;
import com.confia.identity.domain.PasswordResetTokenRow;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.identity.infrastructure.HmacLoginIdentifierFingerprinter;
import com.confia.identity.infrastructure.JooqPasswordResetTokenRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link IssuePasswordResetToken}, the body of the future worker handler, through the real
 * transaction against a real PostgreSQL (password-recovery-token design.md decisions 5, 6 and 9;
 * tasks.md task 3.2). The link sender has no production adapter until change 14, so a double
 * captures the clear-text token it receives: that capture is the only place a test ever sees one.
 *
 * <p><b>Sent after commit, never after a rollback.</b> {@link TransactionRunner} needs a real
 * connection to apply the security context, so this is observed here rather than with pure
 * doubles: the capturing sender reads the token row from a separate transaction when it is called,
 * which only succeeds if the issuing transaction has already committed; and an audit writer that
 * fails forces a rollback, after which the sender has not been called and no row exists.
 */
class IssuePasswordResetTokenIT extends CommittingPostgresIntegrationTest {

    private static final String CARLOS = "carlos.ramirez@colegio.edu.hn";
    private static final String NONEXISTENT_EMAIL = "nadie.registrado@colegio.edu.hn";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
    private final CapturingSender sender = new CapturingSender();

    /** Scenario I3: only the SHA-256 is stored, and no column holds the token. */
    @Test
    void theTableHoldsOnlyTheSha256OfTheDeliveredToken() throws Exception {
        StaffAccountId account = seedStaffAccount(CARLOS);

        assertThat(issueAt(account, "2026-10-12T10:00:30Z"))
                .isEqualTo(IssuePasswordResetTokenDecision.ISSUED);

        PlainPasswordResetToken delivered = sender.onlyToken();
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(delivered.value().getBytes(StandardCharsets.US_ASCII)));
        List<String> rawRows = rawTokenRows();
        assertThat(rawRows).singleElement().satisfies(row -> {
            assertThat(row).contains("\"token_hash\":\"" + expectedHash + "\"");
            assertThat(row).doesNotContain(delivered.value());
        });
    }

    /** Scenario I4: two issuances give two distinct 43-character tokens of 32 bytes. */
    @Test
    void twoSuccessiveIssuancesDeliverTwoDistinctThirtyTwoByteTokens() {
        StaffAccountId account = seedStaffAccount(CARLOS);

        issueAt(account, "2026-10-12T10:00:30Z");
        issueAt(account, "2026-10-12T10:05:30Z");

        assertThat(sender.sent).hasSize(2).allSatisfy(sent -> {
            assertThat(sent.token().value()).hasSize(43).matches("^[A-Za-z0-9_-]{43}$");
            assertThat(Base64.getUrlDecoder().decode(sent.token().value())).hasSize(32);
        });
        assertThat(sender.sent.get(0).token()).isNotEqualTo(sender.sent.get(1).token());
        assertThat(openTokenCount(account))
                .as("the second issuance supersedes the first").isEqualTo(1L);
    }

    /** Scenarios I5 and I6: the fourth in the hour is skipped, and the window rolls. */
    @Test
    void theFourthIssuanceWithinTheHourIsSkippedAndTheWindowRolls() {
        StaffAccountId account = seedStaffAccount(CARLOS);
        issueAt(account, "2026-10-12T10:00:00Z");
        issueAt(account, "2026-10-12T10:10:00Z");
        issueAt(account, "2026-10-12T10:20:00Z");
        PlainPasswordResetToken tenTwenty = sender.sent.get(2).token();

        IssuePasswordResetTokenDecision atTenThirty = issueAt(account, "2026-10-12T10:30:00Z");

        assertThat(atTenThirty).isEqualTo(IssuePasswordResetTokenDecision.SKIPPED);
        assertThat(sender.sent).as("a skipped issuance delivers nothing").hasSize(3);
        assertThat(rowOf(tenTwenty)).get().satisfies(row -> {
            assertThat(row.consumedAt()).isNull();
            assertThat(row.supersededAt())
                    .as("a skipped issuance must not supersede the live token").isNull();
        });
        assertThat(auditRows()).filteredOn(row -> row.action()
                        .equals("identity.password_reset.issuance_skipped"))
                .singleElement().satisfies(row -> {
                    assertThat(row.entityId()).isEqualTo(account.value().toString());
                    JsonNode after = JSON.readTree(row.afterValue());
                    assertThat(after.get("reason").asString()).isEqualTo("rate-limit");
                    assertThat(after.get("issuedInWindow").asInt()).isEqualTo(3);
                });

        assertThat(issueAt(account, "2026-10-12T11:00:01Z"))
                .as("at 11:00:01 the 10:00:00 issuance has left the window and only two count")
                .isEqualTo(IssuePasswordResetTokenDecision.ISSUED);
        assertThat(sender.sent).hasSize(4);
    }

    @Test
    void anIssuedTokenIsAuditedWithItsRowIdAndExpiryButNeverItsHash() {
        StaffAccountId account = seedStaffAccount(CARLOS);

        issueAt(account, "2026-10-12T10:00:30Z");

        PasswordResetTokenRow row = rowOf(sender.onlyToken()).orElseThrow();
        String hash = PasswordResetTokenHash.of(sender.onlyToken()).value();
        assertThat(auditRows()).filteredOn(entry -> entry.action()
                        .equals("identity.password_reset.issued"))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.entityType()).isEqualTo("identity.password_reset_token");
                    assertThat(entry.entityId()).isEqualTo(row.id().toString());
                    assertThat(entry.actorKind()).isEqualTo("system");
                    assertThat(entry.actorLabel()).isEqualTo("password-reset-issuance");
                    JsonNode after = JSON.readTree(entry.afterValue());
                    assertThat(after.get("expiresAt").asString()).isEqualTo("2026-10-12T10:30:30Z");
                    assertThat(after.get("supersededCount").asInt()).isZero();
                    assertThat(entry.toString()).doesNotContain(hash)
                            .doesNotContain(sender.onlyToken().value());
                });
    }

    /** The sender runs after commit: it can already read the row from another transaction. */
    @Test
    void theLinkIsSentOnlyAfterTheIssuingTransactionCommitted() {
        StaffAccountId account = seedStaffAccount(CARLOS);
        sender.onSend = token -> assertThat(rowOfFromAnotherThread(token))
                .as("when the sender is called, the token row must already be committed")
                .isPresent();

        issueAt(account, "2026-10-12T10:00:30Z");

        assertThat(sender.sent).hasSize(1);
        assertThat(sender.checkedAfterCommit).isEqualTo(1);
    }

    @Test
    void aRolledBackIssuanceSendsNothingAndLeavesNoToken() {
        StaffAccountId account = seedStaffAccount(CARLOS);
        AuditLogWriter failingAudit = entry -> {
            throw new IllegalStateException("audit unavailable");
        };

        assertThatThrownBy(() -> useCaseAt("2026-10-12T10:00:30Z", failingAudit)
                .execute(systemContext(), account))
                .isInstanceOf(IllegalStateException.class);

        assertThat(sender.sent).as("a token whose transaction rolled back is never sent")
                .isEmpty();
        assertThat(rawTokenRows()).isEmpty();
    }

    /**
     * Scenario I41, with {@code RequestPasswordReset}: an account at its hourly limit and an address
     * with no account get identical results and one requested entry each; the limit shows only when
     * the issuance is skipped.
     */
    @Test
    void anAccountAtItsHourlyLimitIsIndistinguishableFromANonexistentAddressAtRequestTime() {
        StaffAccountId carlos = seedStaffAccount(CARLOS);
        issueAt(carlos, "2026-10-12T10:00:00Z");
        issueAt(carlos, "2026-10-12T10:10:00Z");
        issueAt(carlos, "2026-10-12T10:20:00Z");
        HmacLoginIdentifierFingerprinter fingerprinter = new HmacLoginIdentifierFingerprinter(
                Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(new byte[32])));
        RequestPasswordReset request = new RequestPasswordReset(transactionRunner(),
                () -> institutionId, new JooqStaffAccountRepository(dsl), fingerprinter,
                new RecordingScheduler(), new JooqAuditLogWriter(dsl));

        RequestPasswordResetDecision atLimit = request.execute(systemContext(),
                new RequestPasswordResetCommand(CARLOS));
        RequestPasswordResetDecision missing = request.execute(systemContext(),
                new RequestPasswordResetCommand(NONEXISTENT_EMAIL));

        assertThat(atLimit).isEqualTo(missing);
        for (String email : List.of(CARLOS, NONEXISTENT_EMAIL)) {
            String fingerprint = fingerprinter.fingerprintOf(LoginIdentifier.of(email)).value();
            assertThat(auditRows()).filteredOn(row -> fingerprint.equals(row.entityId()))
                    .singleElement()
                    .extracting(AuditRowSnapshot::action)
                    .isEqualTo("identity.password_reset.requested");
        }
        assertThat(auditRows()).noneMatch(row -> row.action()
                .equals("identity.password_reset.issuance_skipped"));

        assertThat(issueAt(carlos, "2026-10-12T10:30:00Z"))
                .isEqualTo(IssuePasswordResetTokenDecision.SKIPPED);
        assertThat(auditRows()).filteredOn(row -> row.action()
                .equals("identity.password_reset.issuance_skipped")).hasSize(1);
    }

    private IssuePasswordResetTokenDecision issueAt(StaffAccountId account, String instant) {
        return useCaseAt(instant, new JooqAuditLogWriter(dsl)).execute(systemContext(), account);
    }

    private IssuePasswordResetToken useCaseAt(String instant, AuditLogWriter auditLogWriter) {
        return new IssuePasswordResetToken(transactionRunner(), new JooqStaffAccountRepository(dsl),
                new JooqPasswordResetTokenRepository(dsl), sender, auditLogWriter,
                new SecureRandom(), Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private StaffAccountId seedStaffAccount(String email) {
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        transactionRunner().execute(systemContext(), () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, '$argon2id$v=19$m=19456,t=3,p=1$c2FsdA$aGFzaA', false)
                    """, institutionId.value(), accountId.value(), LoginIdentifier.of(email).value());
            return null;
        });
        return accountId;
    }

    private Optional<PasswordResetTokenRow> rowOf(PlainPasswordResetToken token) {
        return transactionRunner().execute(systemContext(),
                () -> new JooqPasswordResetTokenRepository(dsl).findByHash(institutionId,
                        PasswordResetTokenHash.of(token)));
    }

    /**
     * The same lookup on another thread, so it runs in its own transaction and sees only committed
     * rows. On the calling thread, {@code TransactionRunner} would join a transaction still open
     * there ({@code PROPAGATION_REQUIRED}) and see its uncommitted row, so the check would pass for
     * the wrong reason.
     */
    private Optional<PasswordResetTokenRow> rowOfFromAnotherThread(PlainPasswordResetToken token) {
        java.util.concurrent.ExecutorService other =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            return other.submit(() -> rowOf(token)).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            other.shutdownNow();
        }
    }

    private long openTokenCount(StaffAccountId account) {
        return transactionRunner().execute(systemContext(), () -> dsl.fetchOne("""
                select count(*) as c from identity_password_reset_token
                where institution_id = ? and account_id = ?
                  and consumed_at is null and superseded_at is null
                """, institutionId.value(), account.value()).get("c", Number.class).longValue());
    }

    /** Every column of every token row of this institution, as raw JSON text. */
    private List<String> rawTokenRows() {
        return transactionRunner().execute(systemContext(), () -> dsl.fetch("""
                select row_to_json(t)::text as j from identity_password_reset_token t
                where institution_id = ?
                """, institutionId.value()).getValues("j", String.class));
    }

    private List<AuditRowSnapshot> auditRows() {
        return transactionRunner().execute(systemContext(),
                () -> new JooqAuditLogReader(dsl).pageOf(institutionId, 0, 100));
    }

    /** The worker's context: actor kind {@code system}, institution taken from the task. */
    private SecurityContext systemContext() {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }

    private record SentLink(InstitutionId institutionId, StaffAccountId accountId,
            PlainPasswordResetToken token) {
    }

    private static final class CapturingSender implements PasswordResetLinkSender {
        final List<SentLink> sent = new ArrayList<>();
        java.util.function.Consumer<PlainPasswordResetToken> onSend;
        int checkedAfterCommit;

        @Override
        public void send(InstitutionId institutionId, StaffAccountId accountId,
                PlainPasswordResetToken token) {
            if (onSend != null) {
                onSend.accept(token);
                checkedAfterCommit++;
            }
            sent.add(new SentLink(institutionId, accountId, token));
        }

        PlainPasswordResetToken onlyToken() {
            assertThat(sent).hasSize(1);
            return sent.get(0).token();
        }
    }
}

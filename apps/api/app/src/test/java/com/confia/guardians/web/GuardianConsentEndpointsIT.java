package com.confia.guardians.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.confia.bootstrap.AdminProcessLauncher;
import com.confia.identity.domain.StaffRole;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.token.AccessTokenIssuer;
import com.confia.shared.security.token.CompactJws;
import com.confia.shared.security.token.SigningKey;
import com.confia.shared.security.token.SigningKeyRing;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import com.confia.support.StaffRoleFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two routes of the consent of a guardian for communications on the real administrative process, started through
 * the production launcher against a real PostgreSQL with the least-privilege role of the application
 * (docs/cortes/guardians-consent.md). Every token is made by the production issuer; every test has an institution of
 * its own.
 *
 * <p>It covers the acceptance criterion (an administrator grants the institutional consent by e-mail with its policy
 * version and paper evidence, withdraws it, the history keeps both events and the standing is withdrawn), that nothing
 * is ever pre-marked, the repeat of the current action (no new event), each rule with the field or the code it
 * answers, the races the lock on the row of the guardian settles (simultaneous changes of one combination), the
 * isolation between institutions, the role without the permission (a {@code 403} that leaves no event and no entry),
 * the rate limits per address and per account, the audit entries, and a sweep of the audit rows and the logs against
 * the e-mail, the phone, the names and the address of the guardian.
 */
class GuardianConsentEndpointsIT extends CommittingPostgresIntegrationTest {

    private static final String GUARDIANS = "/api/v1/guardians";
    private static final String RATE_LIMIT = "confia.web.rate-limit.";
    private static final String BLIND_INDEX_KEY = randomBase64Key();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final List<String> NOISY_LOGGERS = List.of("org.apache.coyote", "org.apache.tomcat",
            "org.postgresql", "org.jooq", "com.zaxxer.hikari");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String EMAIL = "reservado.consentimiento@example.org";
    private static final String PHONE = "70707070";
    private static final String FIRST = "Marcador-Nombre";
    private static final String LAST = "Marcador-Apellido";
    private static final String ADDRESS = "Marcador-Direccion";

    private static KeyPair keys;
    private static CompactJws jws;
    private static ConfigurableApplicationContext process;
    private static int port;

    private ListAppender<ILoggingEvent> logs;
    private Level rootLevelBefore;
    private final Map<String, Level> noisyLevelsBefore = new HashMap<>();

    @BeforeAll
    static void startTheProcess() throws NoSuchAlgorithmException {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        jws = new CompactJws(SigningKeyRing.of(SigningKey.signing(
                AdminProcessLauncher.signingKid(), keys.getPublic(), keys.getPrivate())));
        process = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(),
                "confia_admin_app", TEST_PASSWORD, keys, Map.of(
                        "confia.crypto.blind-index.current-version", "v1",
                        "confia.crypto.blind-index.keys.v1", BLIND_INDEX_KEY,
                        RATE_LIMIT + "admin-read.request-limit", "10000",
                        RATE_LIMIT + "admin-write.request-limit", "10000",
                        RATE_LIMIT + "admin-read-account.request-limit", "10000",
                        RATE_LIMIT + "admin-write-account.request-limit", "10000"));
        port = ((WebServerApplicationContext) process).getWebServer().getPort();
    }

    @AfterAll
    static void stopTheProcess() {
        process.close();
    }

    @BeforeEach
    void captureLogs() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLevelBefore = root.getLevel();
        logs = new ListAppender<>();
        logs.start();
        root.setLevel(Level.TRACE);
        for (String name : NOISY_LOGGERS) {
            Logger noisy = (Logger) LoggerFactory.getLogger(name);
            noisyLevelsBefore.put(name, noisy.getLevel());
            noisy.setLevel(Level.INFO);
        }
        root.addAppender(logs);
    }

    /** Whatever a test did, no audit row and no log line carries the e-mail, the phone, the names or the address. */
    @AfterEach
    void noContactDataLeaksToTheAuditTrailOrTheLogs() throws SQLException {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(logs);
        root.setLevel(rootLevelBefore);
        noisyLevelsBefore.forEach((name, level) -> ((Logger) LoggerFactory.getLogger(name)).setLevel(level));
        noisyLevelsBefore.clear();

        List<String> logLines = new ArrayList<>();
        for (ILoggingEvent event : new ArrayList<>(logs.list)) {
            logLines.add(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                logLines.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
            logLines.add(String.valueOf(event.getMDCPropertyMap()));
        }
        List<String> audit = guardianAuditRows();
        for (String word : List.of(EMAIL, PHONE, FIRST, LAST, ADDRESS)) {
            assertThat(audit).as("audit rows must not contain %s", word).noneMatch(text -> text.contains(word));
            assertThat(logLines).as("logs must not contain %s", word).noneMatch(text -> text.contains(word));
        }
    }

    private static String randomBase64Key() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return java.util.Base64.getEncoder().encodeToString(key);
    }

    /** One seeded staff member of one institution, with its live session and its token. */
    private record Member(UUID account, UUID institution, UUID family, String token) {
    }

    /** A row of {@code guardians_guardian_consent_event}, in the order of {@code seq}. */
    private record EventRow(String channel, String purpose, String action, String evidence, String policyVersion,
            UUID recordedBy, Instant occurredAt) {
    }

    // --- The acceptance criterion ---

    @Test
    void anAdministratorGrantsTheInstitutionalConsentByEmailWithPaperEvidenceWithdrawsItAndTheHistoryKeepsBothEvents()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String guardian = guardian(admin, EMAIL, PHONE);

        JsonNode initial = ok(get(consents(guardian), admin));
        assertThat(initial.get("states")).hasSize(6);
        initial.get("states").forEach(state -> assertThat(state.get("state").asString()).isEqualTo("NEVER_GRANTED"));
        assertThat(initial.get("history")).isEmpty();

        Instant before = Instant.now().minusSeconds(5);
        HttpResponse<String> grantResponse = post(consents(guardian), admin, change("EMAIL", "INSTITUTIONAL", "GRANT",
                "SIGNED_PAPER_FORM", "2026-01"));
        JsonNode granted = ok(grantResponse);
        assertThat(grantResponse.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(granted.get("state").asString()).isEqualTo("GRANTED");
        assertThat(granted.get("changed").asBoolean()).isTrue();
        assertThat(granted.get("policyVersion").asString()).isEqualTo("2026-01");
        assertThat(granted.get("evidenceSource").asString()).isEqualTo("SIGNED_PAPER_FORM");
        assertThat(Instant.parse(granted.get("since").asString())).isBetween(before, Instant.now().plusSeconds(5));

        JsonNode withdrawn = ok(post(consents(guardian), admin, change("EMAIL", "INSTITUTIONAL", "WITHDRAW",
                "ADMIN_REGISTERED", null)));
        assertThat(withdrawn.get("state").asString()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.get("changed").asBoolean()).isTrue();
        assertThat(withdrawn.get("policyVersion").isNull()).isTrue();

        JsonNode view = ok(get(consents(guardian), admin));
        assertThat(view.get("states").get(0).get("channel").asString()).isEqualTo("EMAIL");
        assertThat(view.get("states").get(0).get("purpose").asString()).isEqualTo("INSTITUTIONAL");
        assertThat(view.get("states").get(0).get("state").asString()).isEqualTo("WITHDRAWN");
        for (int i = 1; i < 6; i++) {
            assertThat(view.get("states").get(i).get("state").asString()).isEqualTo("NEVER_GRANTED");
        }
        assertThat(view.get("history")).hasSize(2);
        assertThat(view.get("history").get(0).get("action").asString()).isEqualTo("WITHDRAW");
        assertThat(view.get("history").get(1).get("action").asString()).isEqualTo("GRANT");
        assertThat(view.get("history").get(1).get("policyVersion").asString()).isEqualTo("2026-01");
        assertThat(view.get("history").get(1).get("evidenceSource").asString()).isEqualTo("SIGNED_PAPER_FORM");

        List<EventRow> rows = events(guardian);
        assertThat(rows).extracting(EventRow::action).containsExactly("GRANT", "WITHDRAW");
        assertThat(rows).extracting(EventRow::recordedBy).containsOnly(admin.account());
        List<String[]> entries = consentEntries(admin.institution());
        assertThat(entries).extracting(row -> row[0]).containsExactly("guardians.consent.granted",
                "guardians.consent.withdrawn");
        assertThat(entries.get(0)[1]).isEqualTo("guardians.consent");
        assertThat(entries.get(0)[2]).isEqualTo(guardian);
        JsonNode auditAfter = json(entries.get(0)[4]);
        assertThat(auditAfter.get("channel").asString()).isEqualTo("EMAIL");
        assertThat(auditAfter.get("purpose").asString()).isEqualTo("INSTITUTIONAL");
        assertThat(auditAfter.get("policyVersion").asString()).isEqualTo("2026-01");
        assertThat(auditAfter.get("evidenceSource").asString()).isEqualTo("SIGNED_PAPER_FORM");
        assertThat(json(entries.get(0)[3]).get("state").asString()).isEqualTo("NEVER_GRANTED");
        assertThat(actorsOfConsentEntries(admin.institution())).containsOnly(admin.account().toString());
    }

    @Test
    void grantWithdrawAndGrantAgainLeavesAHistoryOfThreeEventsAndNothingIsEverPremarked() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String guardian = guardian(admin, EMAIL, PHONE);
        assertThat(events(guardian)).as("registering a guardian with all its contact data records no consent").isEmpty();
        ok(put(GUARDIANS + "/" + guardian, admin, "{\"firstName\":\"" + FIRST + "\",\"lastName\":\"" + LAST
                + "\",\"status\":\"ACTIVE\",\"email\":\"" + EMAIL + "\",\"phone\":\"" + PHONE
                + "\",\"preferredChannel\":\"EMAIL\"}"));
        assertThat(events(guardian)).as("neither does choosing a preferred channel").isEmpty();

        ok(post(consents(guardian), admin, change("SMS", "MARKETING", "GRANT", "ADMIN_REGISTERED", "2026-01")));
        ok(post(consents(guardian), admin, change("SMS", "MARKETING", "WITHDRAW", "SIGNED_PAPER_FORM", null)));
        ok(post(consents(guardian), admin, change("SMS", "MARKETING", "GRANT", "SIGNED_PAPER_FORM", "2026-02")));

        JsonNode view = ok(get(consents(guardian), admin));
        assertThat(view.get("history")).hasSize(3);
        JsonNode sms = view.get("states").get(3);
        assertThat(sms.get("channel").asString()).isEqualTo("SMS");
        assertThat(sms.get("purpose").asString()).isEqualTo("MARKETING");
        assertThat(sms.get("state").asString()).isEqualTo("GRANTED");
        assertThat(sms.get("policyVersion").asString()).isEqualTo("2026-02");
        assertThat(events(guardian)).extracting(EventRow::action).containsExactly("GRANT", "WITHDRAW", "GRANT");
        long neverGranted = 0;
        for (JsonNode state : view.get("states")) {
            neverGranted += state.get("state").asString().equals("NEVER_GRANTED") ? 1 : 0;
        }
        assertThat(neverGranted).isEqualTo(5);
    }

    // --- The shape of the answers ---

    @Test
    void theAnswersHaveTheExactMembersCarryNoStoreAndNeverTheContactDataTheNamesOrTheInstitution()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String guardian = guardian(admin, EMAIL, PHONE);
        HttpResponse<String> granted = post(consents(guardian), admin, change("EMAIL", "MARKETING", "GRANT",
                "ADMIN_REGISTERED", "2026-01"));
        HttpResponse<String> read = get(consents(guardian), admin);

        for (HttpResponse<String> response : List.of(granted, read)) {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
            assertThat(response.body()).doesNotContain(EMAIL).doesNotContain(PHONE).doesNotContain(FIRST)
                    .doesNotContain(LAST).doesNotContain(ADDRESS).doesNotContain(admin.institution().toString())
                    .doesNotContain(admin.account().toString());
        }
        JsonNode change = json(granted);
        assertThat(change.propertyNames()).containsExactly("channel", "purpose", "state", "since", "policyVersion",
                "evidenceSource", "changed");
        JsonNode view = json(read);
        assertThat(view.propertyNames()).containsExactly("guardianId", "states", "history");
        assertThat(view.get("guardianId").asString()).isEqualTo(guardian);
        assertThat(view.get("states").get(0).propertyNames()).containsExactly("channel", "purpose", "state", "since",
                "policyVersion", "evidenceSource");
        assertThat(view.get("states").get(0).get("state").asString()).isEqualTo("NEVER_GRANTED");
        assertThat(view.get("states").get(0).get("since").isNull()).isTrue();
        assertThat(view.get("history").get(0).propertyNames()).containsExactly("id", "channel", "purpose", "action",
                "evidenceSource", "policyVersion", "occurredAt");
        List<String> order = new ArrayList<>();
        view.get("states").forEach(state -> order.add(state.get("channel").asString() + ":"
                + state.get("purpose").asString()));
        assertThat(order).containsExactly("EMAIL:INSTITUTIONAL", "EMAIL:MARKETING", "SMS:INSTITUTIONAL",
                "SMS:MARKETING", "WHATSAPP:INSTITUTIONAL", "WHATSAPP:MARKETING");
    }

    @Test
    void theTimeOfTheEventIsTheServersAndAClientCannotSendIt() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String guardian = guardian(admin, EMAIL, PHONE);
        Instant before = Instant.now().minusSeconds(5);

        HttpResponse<String> withTime = post(consents(guardian), admin, "{\"channel\":\"EMAIL\",\"purpose\":\"MARKETING\","
                + "\"action\":\"GRANT\",\"evidenceSource\":\"ADMIN_REGISTERED\",\"policyVersion\":\"2026-01\","
                + "\"occurredAt\":\"2020-01-01T00:00:00Z\"}");
        HttpResponse<String> withActor = post(consents(guardian), admin, "{\"channel\":\"EMAIL\",\"purpose\":\"MARKETING\","
                + "\"action\":\"GRANT\",\"evidenceSource\":\"ADMIN_REGISTERED\",\"policyVersion\":\"2026-01\","
                + "\"recordedBy\":\"" + UUID.randomUUID() + "\"}");
        assertProblem(withTime, 400, "validation-failed");
        assertProblem(withActor, 400, "validation-failed");
        assertThat(events(guardian)).isEmpty();

        ok(post(consents(guardian), admin, change("EMAIL", "MARKETING", "GRANT", "ADMIN_REGISTERED", "2026-01")));
        assertThat(events(guardian).get(0).occurredAt()).isBetween(before, Instant.now().plusSeconds(5));
    }

    // --- Idempotency ---

    @Test
    void repeatingTheCurrentActionWritesNoEventNoEntryAndAnswersTheCurrentStandingWithChangedFalse()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String guardian = guardian(admin, EMAIL, PHONE);
        ok(post(consents(guardian), admin, change("EMAIL", "INSTITUTIONAL", "GRANT", "SIGNED_PAPER_FORM", "2026-01")));

        JsonNode again = ok(post(consents(guardian), admin, change("EMAIL", "INSTITUTIONAL", "GRANT",
                "ADMIN_REGISTERED", "2027-09")));

        assertThat(again.get("changed").asBoolean()).isFalse();
        assertThat(again.get("state").asString()).isEqualTo("GRANTED");
        assertThat(again.get("policyVersion").asString()).as("the recorded grant stays").isEqualTo("2026-01");
        assertThat(again.get("evidenceSource").asString()).isEqualTo("SIGNED_PAPER_FORM");
        assertThat(events(guardian)).hasSize(1);
        assertThat(consentEntries(admin.institution())).hasSize(1);

        ok(post(consents(guardian), admin, change("EMAIL", "INSTITUTIONAL", "WITHDRAW", "ADMIN_REGISTERED", null)));
        JsonNode withdrawnAgain = ok(post(consents(guardian), admin, change("EMAIL", "INSTITUTIONAL", "WITHDRAW",
                "SIGNED_PAPER_FORM", null)));
        assertThat(withdrawnAgain.get("changed").asBoolean()).isFalse();
        assertThat(withdrawnAgain.get("state").asString()).isEqualTo("WITHDRAWN");
        assertThat(events(guardian)).hasSize(2);
        assertThat(consentEntries(admin.institution())).hasSize(2);
    }

    @Test
    void withdrawingWhatWasNeverGrantedIsRecordedAsARealFact() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String guardian = guardian(admin, EMAIL, null);

        JsonNode withdrawn = ok(post(consents(guardian), admin, change("WHATSAPP", "MARKETING", "WITHDRAW",
                "SIGNED_PAPER_FORM", null)));

        assertThat(withdrawn.get("changed").asBoolean()).isTrue();
        assertThat(withdrawn.get("state").asString()).isEqualTo("WITHDRAWN");
        assertThat(events(guardian)).hasSize(1);
    }

    // --- Rules ---

    @Test
    void eachRuleOfTheRequestIsRefusedWithItsFieldAndKeywordAndNothingIsWritten() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String guardian = guardian(admin, EMAIL, PHONE);
        int entries = consentEntries(admin.institution()).size();

        assertField(post(consents(guardian), admin, change("EMAIL", "TRANSACTIONAL", "GRANT", "ADMIN_REGISTERED",
                "2026-01")), "purpose", "not-consentable");
        assertField(post(consents(guardian), admin, change("EMAIL", "transactional", "WITHDRAW", "ADMIN_REGISTERED",
                null)), "purpose", "not-consentable");
        assertField(post(consents(guardian), admin, change("EMAIL", "SPAM", "GRANT", "ADMIN_REGISTERED", "2026-01")),
                "purpose", "invalid");
        assertField(post(consents(guardian), admin, change("EMAIL", "MARKETING", "GRANT", "PORTAL_FORM", "2026-01")),
                "evidenceSource", "not-allowed");
        assertField(post(consents(guardian), admin, change("EMAIL", "MARKETING", "GRANT", "ORAL", "2026-01")),
                "evidenceSource", "invalid");
        assertField(post(consents(guardian), admin, change("EMAIL", "MARKETING", "GRANT", "ADMIN_REGISTERED", null)),
                "policyVersion", "required");
        assertField(post(consents(guardian), admin, change("EMAIL", "MARKETING", "GRANT", "ADMIN_REGISTERED",
                "2026 01")), "policyVersion", "pattern");
        assertField(post(consents(guardian), admin, change("EMAIL", "MARKETING", "GRANT", "ADMIN_REGISTERED",
                "v1;drop")), "policyVersion", "pattern");
        assertField(post(consents(guardian), admin, change("EMAIL", "MARKETING", "GRANT", "ADMIN_REGISTERED",
                "a".repeat(33))), "policyVersion", "pattern");
        assertField(post(consents(guardian), admin, change("EMAIL", "MARKETING", "WITHDRAW", "ADMIN_REGISTERED",
                "2026-01")), "policyVersion", "not-allowed");
        assertField(post(consents(guardian), admin, change("FAX", "MARKETING", "GRANT", "ADMIN_REGISTERED", "2026-01")),
                "channel", "pattern");
        assertField(post(consents(guardian), admin, change("EMAIL", "MARKETING", "REVOKE", "ADMIN_REGISTERED", null)),
                "action", "pattern");
        JsonNode empty = assertProblem(post(consents(guardian), admin, "{}"), 400, "validation-failed");
        List<String> missing = new ArrayList<>();
        empty.get("errors").forEach(error -> missing.add(error.get("field").asString() + ":"
                + error.get("reason").asString()));
        assertThat(missing).containsExactlyInAnyOrder("channel:not-null", "purpose:not-null", "action:not-null",
                "evidenceSource:not-null");
        assertProblem(post(consents(guardian), admin, "not json"), 400, "validation-failed");

        assertThat(events(guardian)).isEmpty();
        assertThat(consentEntries(admin.institution())).hasSize(entries);
    }

    @Test
    void aGrantNeedsTheContactDataOfTheChannelAndAWithdrawalDoesNot() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String onlyEmail = guardian(admin, EMAIL, null);
        String onlyPhone = guardian(admin, null, PHONE);
        String nothing = guardian(admin, null, null);

        assertProblem(post(consents(onlyEmail), admin, change("SMS", "INSTITUTIONAL", "GRANT", "ADMIN_REGISTERED",
                "2026-01")), 409, "consent-channel-unavailable");
        assertProblem(post(consents(onlyEmail), admin, change("WHATSAPP", "MARKETING", "GRANT", "ADMIN_REGISTERED",
                "2026-01")), 409, "consent-channel-unavailable");
        assertProblem(post(consents(onlyPhone), admin, change("EMAIL", "INSTITUTIONAL", "GRANT", "ADMIN_REGISTERED",
                "2026-01")), 409, "consent-channel-unavailable");
        for (String channel : List.of("EMAIL", "SMS", "WHATSAPP")) {
            assertProblem(post(consents(nothing), admin, change(channel, "MARKETING", "GRANT", "SIGNED_PAPER_FORM",
                    "2026-01")), 409, "consent-channel-unavailable");
        }
        assertThat(events(onlyEmail)).isEmpty();
        assertThat(events(onlyPhone)).isEmpty();
        assertThat(events(nothing)).isEmpty();

        ok(post(consents(onlyEmail), admin, change("EMAIL", "INSTITUTIONAL", "GRANT", "ADMIN_REGISTERED", "2026-01")));
        ok(post(consents(onlyPhone), admin, change("SMS", "INSTITUTIONAL", "GRANT", "ADMIN_REGISTERED", "2026-01")));
        ok(post(consents(onlyPhone), admin, change("WHATSAPP", "MARKETING", "GRANT", "ADMIN_REGISTERED", "2026-01")));
        ok(post(consents(nothing), admin, change("SMS", "MARKETING", "WITHDRAW", "SIGNED_PAPER_FORM", null)));
        assertThat(events(nothing)).hasSize(1);
    }

    @Test
    void anInactiveGuardianCannotGrantButCanAlwaysWithdrawAndTheWithdrawalIsImmediate() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String guardian = guardian(admin, EMAIL, PHONE);
        ok(post(consents(guardian), admin, change("EMAIL", "MARKETING", "GRANT", "SIGNED_PAPER_FORM", "2026-01")));
        ok(put(GUARDIANS + "/" + guardian, admin, "{\"firstName\":\"" + FIRST + "\",\"lastName\":\"" + LAST
                + "\",\"status\":\"INACTIVE\",\"email\":\"" + EMAIL + "\",\"phone\":\"" + PHONE + "\"}"));

        assertProblem(post(consents(guardian), admin, change("SMS", "MARKETING", "GRANT", "ADMIN_REGISTERED",
                "2026-01")), 409, "guardian-not-active");
        assertProblem(post(consents(guardian), admin, change("EMAIL", "MARKETING", "GRANT", "ADMIN_REGISTERED",
                "2026-02")), 409, "guardian-not-active");
        assertThat(events(guardian)).hasSize(1);

        JsonNode withdrawn = ok(post(consents(guardian), admin, change("EMAIL", "MARKETING", "WITHDRAW",
                "SIGNED_PAPER_FORM", null)));
        assertThat(withdrawn.get("state").asString()).isEqualTo("WITHDRAWN");
        assertThat(ok(get(consents(guardian), admin)).get("states").get(1).get("state").asString())
                .isEqualTo("WITHDRAWN");
        assertThat(events(guardian)).hasSize(2);
    }

    @Test
    void anUnknownGuardianIsNotFoundAndAMalformedIdentifierIsABadRequest() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertProblem(get(consents(UUID.randomUUID().toString()), admin), 404, "resource-not-found");
        assertProblem(post(consents(UUID.randomUUID().toString()), admin, change("EMAIL", "MARKETING", "WITHDRAW",
                "ADMIN_REGISTERED", null)), 404, "resource-not-found");
        assertProblem(get(GUARDIANS + "/not-a-uuid/consents", admin), 400, "validation-failed");
        assertProblem(post(GUARDIANS + "/not-a-uuid/consents", admin, change("EMAIL", "MARKETING", "WITHDRAW",
                "ADMIN_REGISTERED", null)), 400, "validation-failed");
    }

    // --- Races ---

    @Test
    void simultaneousGrantsOfTheSameCombinationWriteExactlyOneEvent() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String guardian = guardian(admin, EMAIL, PHONE);
        String body = change("EMAIL", "INSTITUTIONAL", "GRANT", "SIGNED_PAPER_FORM", "2026-01");
        List<Callable<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> post(consents(guardian), admin, body));
        }

        List<HttpResponse<String>> answers = inParallel(calls);

        assertThat(answers).allSatisfy(answer -> assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200));
        long changed = answers.stream().filter(answer -> json(answer).get("changed").asBoolean()).count();
        assertThat(changed).as("exactly one request changed the state").isEqualTo(1);
        assertThat(events(guardian)).hasSize(1);
        assertThat(consentEntries(admin.institution())).hasSize(1);
    }

    @Test
    void simultaneousGrantsAndWithdrawalsLeaveACoherentOrderWhereNoTwoConsecutiveEventsRepeatAnAction()
            throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String guardian = guardian(admin, EMAIL, PHONE);
        String grant = change("SMS", "MARKETING", "GRANT", "SIGNED_PAPER_FORM", "2026-01");
        String withdraw = change("SMS", "MARKETING", "WITHDRAW", "ADMIN_REGISTERED", null);
        List<Callable<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String body = i % 2 == 0 ? grant : withdraw;
            calls.add(() -> post(consents(guardian), admin, body));
        }

        List<HttpResponse<String>> answers = inParallel(calls);

        assertThat(answers).allSatisfy(answer -> assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200));
        List<EventRow> rows = events(guardian);
        assertThat(rows).isNotEmpty();
        for (int i = 1; i < rows.size(); i++) {
            assertThat(rows.get(i).action()).as("event %d repeats the action of the one before", i)
                    .isNotEqualTo(rows.get(i - 1).action());
        }
        assertThat(rows.get(0).action()).as("the first event of a combination is whatever won the lock").isNotNull();
        long changed = answers.stream().filter(answer -> json(answer).get("changed").asBoolean()).count();
        assertThat(changed).isEqualTo(rows.size());
        assertThat(consentEntries(admin.institution())).hasSize(rows.size());
        String last = rows.getLast().action().equals("GRANT") ? "GRANTED" : "WITHDRAWN";
        assertThat(ok(get(consents(guardian), admin)).get("states").get(3).get("state").asString()).isEqualTo(last);
    }

    @Test
    void simultaneousChangesOfDifferentCombinationsAllLandAndStayIndependent() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String guardian = guardian(admin, EMAIL, PHONE);
        List<Callable<HttpResponse<String>>> calls = new ArrayList<>();
        for (String channel : List.of("EMAIL", "SMS", "WHATSAPP")) {
            for (String purpose : List.of("INSTITUTIONAL", "MARKETING")) {
                String body = change(channel, purpose, "GRANT", "ADMIN_REGISTERED", "2026-01");
                calls.add(() -> post(consents(guardian), admin, body));
            }
        }

        List<HttpResponse<String>> answers = inParallel(calls);

        assertThat(answers).allSatisfy(answer -> assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200));
        assertThat(events(guardian)).hasSize(6);
        ok(get(consents(guardian), admin)).get("states")
                .forEach(state -> assertThat(state.get("state").asString()).isEqualTo("GRANTED"));
    }

    @Test
    void aGrantRacingTheDeactivationOfTheGuardianEndsInAConsistentState() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String guardian = guardian(admin, EMAIL, PHONE);
        String deactivate = "{\"firstName\":\"" + FIRST + "\",\"lastName\":\"" + LAST + "\",\"status\":\"INACTIVE\","
                + "\"email\":\"" + EMAIL + "\",\"phone\":\"" + PHONE + "\"}";

        List<HttpResponse<String>> answers = inParallel(List.of(
                () -> post(consents(guardian), admin, change("EMAIL", "INSTITUTIONAL", "GRANT", "ADMIN_REGISTERED",
                        "2026-01")),
                () -> put(GUARDIANS + "/" + guardian, admin, deactivate)));

        assertThat(answers.get(1).statusCode()).isEqualTo(200);
        int grantStatus = answers.get(0).statusCode();
        assertThat(grantStatus).isIn(200, 409);
        // If the deactivation won, the grant was refused; if the grant won, the event exists. Either way, from here on
        // a grant is refused and the standing matches the events.
        assertThat(events(guardian)).hasSize(grantStatus == 200 ? 1 : 0);
        assertProblem(post(consents(guardian), admin, change("SMS", "INSTITUTIONAL", "GRANT", "ADMIN_REGISTERED",
                "2026-01")), 409, "guardian-not-active");
    }

    // --- Isolation ---

    @Test
    void aGuardianOfAnotherInstitutionIsNotFoundForEveryRouteAndNothingChanges() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        String myGuardian = guardian(mine, EMAIL, PHONE);
        String theirGuardian = guardian(theirs, null, PHONE);
        ok(post(consents(myGuardian), mine, change("EMAIL", "INSTITUTIONAL", "GRANT", "SIGNED_PAPER_FORM", "2026-01")));
        ok(post(consents(theirGuardian), theirs, change("SMS", "MARKETING", "GRANT", "SIGNED_PAPER_FORM", "2026-01")));

        assertProblem(get(consents(myGuardian), theirs), 404, "resource-not-found");
        assertProblem(post(consents(myGuardian), theirs, change("EMAIL", "INSTITUTIONAL", "WITHDRAW",
                "ADMIN_REGISTERED", null)), 404, "resource-not-found");
        assertProblem(post(consents(myGuardian), theirs, change("EMAIL", "MARKETING", "GRANT", "ADMIN_REGISTERED",
                "2026-01")), 404, "resource-not-found");
        assertProblem(get(consents(theirGuardian), mine), 404, "resource-not-found");

        assertThat(events(myGuardian)).hasSize(1);
        assertThat(events(theirGuardian)).hasSize(1);
        assertThat(ok(get(consents(myGuardian), mine)).get("states").get(0).get("state").asString())
                .isEqualTo("GRANTED");
        assertThat(ok(get(consents(myGuardian), mine)).get("history")).hasSize(1);
        assertThat(ok(get(consents(theirGuardian), theirs)).get("history")).hasSize(1);
    }

    // --- Authorization ---

    @Test
    void aCashierAndACoordinatorMayReadTheConsentsButCannotRecordAndTheRefusalLeavesNoEventAndNoEntry()
            throws SQLException {
        Member cashier = member(StaffRole.CASHIER.code());
        Member coordinator = sameInstitution(cashier.institution(), StaffRole.ACADEMIC_COORDINATOR.code());
        Member admin = sameInstitution(cashier.institution(), StaffRole.ADMINISTRATOR.code());
        String guardian = guardian(admin, EMAIL, PHONE);
        ok(post(consents(guardian), admin, change("EMAIL", "INSTITUTIONAL", "GRANT", "SIGNED_PAPER_FORM", "2026-01")));
        int entriesBefore = consentEntries(cashier.institution()).size();
        long deniedBefore = deniedCount(cashier.institution());

        for (Member reader : List.of(cashier, coordinator)) {
            assertThat(get(consents(guardian), reader).statusCode()).isEqualTo(200);
            assertProblem(post(consents(guardian), reader, change("EMAIL", "INSTITUTIONAL", "WITHDRAW",
                    "ADMIN_REGISTERED", null)), 403, "forbidden");
            assertProblem(post(consents(guardian), reader, change("SMS", "MARKETING", "GRANT", "ADMIN_REGISTERED",
                    "2026-01")), 403, "forbidden");
        }

        assertThat(events(guardian)).hasSize(1);
        assertThat(ok(get(consents(guardian), admin)).get("states").get(0).get("state").asString())
                .isEqualTo("GRANTED");
        assertThat(consentEntries(cashier.institution())).as("no entry for a refused request").hasSize(entriesBefore);
        assertThat(deniedCount(cashier.institution()) - deniedBefore).isEqualTo(4);
    }

    @Test
    void anAccountWithoutARoleCannotEvenReadAndNoCredentialAnswers401() throws SQLException {
        Member nobody = member(null);
        String id = UUID.randomUUID().toString();

        assertProblem(get(consents(id), nobody), 403, "forbidden");
        assertProblem(post(consents(id), nobody, change("EMAIL", "MARKETING", "WITHDRAW", "ADMIN_REGISTERED", null)),
                403, "forbidden");
        assertProblem(send("GET", consents(id), null, null), 401, "authentication-required");
        assertProblem(send("POST", consents(id), null, "{}"), 401, "authentication-required");
    }

    @Test
    void aClosedSessionOpensNoRouteAndAWriteWithoutAnAllowedOriginIsRefusedBeforeAnyEffect() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String guardian = guardian(admin, EMAIL, PHONE);
        HttpResponse<String> noOrigin = execute(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + consents(guardian))).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + admin.token())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                        change("EMAIL", "MARKETING", "WITHDRAW", "ADMIN_REGISTERED", null))).build());
        assertThat(noOrigin.statusCode()).isEqualTo(403);
        assertThat(events(guardian)).isEmpty();

        try (Connection connection = StaffRoleFixtures.ownerTransaction(admin.institution());
                PreparedStatement statement = connection.prepareStatement("""
                        update identity_refresh_token_family
                           set revoked_at = now(), revoked_reason = 'password_change'
                         where institution_id = ? and id = ?
                        """)) {
            statement.setObject(1, admin.institution());
            statement.setObject(2, admin.family());
            assertThat(statement.executeUpdate()).isEqualTo(1);
            connection.commit();
        }

        assertProblem(get(consents(guardian), admin), 401, "token-invalid");
        assertProblem(post(consents(guardian), admin, change("EMAIL", "MARKETING", "WITHDRAW", "ADMIN_REGISTERED",
                null)), 401, "token-invalid");
        assertThat(events(guardian)).isEmpty();
    }

    // --- Rate limits ---

    @Test
    void theTwoRoutesAreRateLimitedPerAddressWithRetryAfterAndAnotherAddressIsNotAffected() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String guardian = guardian(admin, EMAIL, PHONE);
        try (ConfigurableApplicationContext limited = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(),
                "confia_admin_app", TEST_PASSWORD, keys, Map.of("confia.web.trusted-proxies", "127.0.0.1,::1",
                        "confia.crypto.blind-index.current-version", "v1",
                        "confia.crypto.blind-index.keys.v1", BLIND_INDEX_KEY,
                        RATE_LIMIT + "admin-read.request-limit", "1", RATE_LIMIT + "admin-write.request-limit", "1"))) {
            int limitedPort = ((WebServerApplicationContext) limited).getWebServer().getPort();
            String noisy = "203.0.113.50";
            String quiet = "203.0.113.51";

            assertThat(sendFrom(limitedPort, noisy, "POST", consents(guardian), admin.token(),
                    change("EMAIL", "INSTITUTIONAL", "GRANT", "ADMIN_REGISTERED", "2026-01")).statusCode())
                    .isEqualTo(200);
            HttpResponse<String> refused = sendFrom(limitedPort, noisy, "POST", consents(guardian), admin.token(),
                    change("EMAIL", "INSTITUTIONAL", "WITHDRAW", "ADMIN_REGISTERED", null));
            assertProblem(refused, 429, "too-many-requests");
            assertThat(refused.headers().firstValue("Retry-After")).hasValueSatisfying(seconds ->
                    assertThat(Long.parseLong(seconds)).isBetween(1L, 60L));

            assertThat(sendFrom(limitedPort, noisy, "GET", consents(guardian), admin.token(), null).statusCode())
                    .isEqualTo(200);
            HttpResponse<String> refusedRead = sendFrom(limitedPort, noisy, "GET", consents(guardian), admin.token(),
                    null);
            assertProblem(refusedRead, 429, "too-many-requests");
            assertThat(refusedRead.headers().firstValue("Retry-After")).isPresent();

            assertThat(sendFrom(limitedPort, quiet, "GET", consents(guardian), admin.token(), null).statusCode())
                    .isEqualTo(200);
            assertThat(events(guardian)).as("the refusal changed nothing").hasSize(1);
        }
    }

    @Test
    void theTwoRoutesAreAlsoRateLimitedPerAccountSoAnotherAccountIsUnaffected() throws Exception {
        Member heavy = member(StaffRole.ADMINISTRATOR.code());
        Member other = sameInstitution(heavy.institution(), StaffRole.ADMINISTRATOR.code());
        String guardian = guardian(heavy, EMAIL, PHONE);
        try (ConfigurableApplicationContext limited = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(),
                "confia_admin_app", TEST_PASSWORD, keys, Map.of("confia.web.trusted-proxies", "127.0.0.1,::1",
                        "confia.crypto.blind-index.current-version", "v1",
                        "confia.crypto.blind-index.keys.v1", BLIND_INDEX_KEY,
                        RATE_LIMIT + "admin-read.request-limit", "10000",
                        RATE_LIMIT + "admin-write.request-limit", "10000",
                        RATE_LIMIT + "admin-read-account.request-limit", "1",
                        RATE_LIMIT + "admin-write-account.request-limit", "1"))) {
            int limitedPort = ((WebServerApplicationContext) limited).getWebServer().getPort();
            String first = "203.0.113.60";
            String second = "203.0.113.61";

            assertThat(sendFrom(limitedPort, first, "POST", consents(guardian), heavy.token(),
                    change("EMAIL", "MARKETING", "GRANT", "ADMIN_REGISTERED", "2026-01")).statusCode()).isEqualTo(200);
            HttpResponse<String> refused = sendFrom(limitedPort, second, "POST", consents(guardian), heavy.token(),
                    change("EMAIL", "MARKETING", "WITHDRAW", "ADMIN_REGISTERED", null));
            assertProblem(refused, 429, "too-many-requests");
            assertThat(refused.headers().firstValue("Retry-After")).isPresent();
            assertThat(sendFrom(limitedPort, first, "POST", consents(guardian), other.token(),
                    change("EMAIL", "MARKETING", "WITHDRAW", "ADMIN_REGISTERED", null)).statusCode())
                    .as("another account, same address").isEqualTo(200);

            assertThat(sendFrom(limitedPort, first, "GET", consents(guardian), heavy.token(), null).statusCode())
                    .isEqualTo(200);
            assertProblem(sendFrom(limitedPort, second, "GET", consents(guardian), heavy.token(), null), 429,
                    "too-many-requests");
            assertThat(sendFrom(limitedPort, second, "GET", consents(guardian), other.token(), null).statusCode())
                    .isEqualTo(200);
        }
    }

    // --- Logs ---

    @Test
    void theFlowsOfTheConsentsLogNoEmailNoPhoneNoNameAndNoAddress() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String guardian = guardian(admin, EMAIL, PHONE);

        post(consents(guardian), admin, change("EMAIL", "INSTITUTIONAL", "GRANT", "SIGNED_PAPER_FORM", "2026-01"));
        post(consents(guardian), admin, change("EMAIL", "INSTITUTIONAL", "GRANT", "SIGNED_PAPER_FORM", "2026-01"));
        post(consents(guardian), admin, change("SMS", "TRANSACTIONAL", "GRANT", "SIGNED_PAPER_FORM", "2026-01"));
        post(consents(guardian), admin, change("EMAIL", "INSTITUTIONAL", "WITHDRAW", "PORTAL_FORM", null));
        post(consents(guardian), admin, "{\"channel\":\"FAX\"}");
        get(consents(guardian), admin);

        assertThat(logs.list).as("the flow did log something, so the scan is not vacuous").isNotEmpty();
    }

    // --- Bodies ---

    private static String consents(String guardianId) {
        return GUARDIANS + "/" + guardianId + "/consents";
    }

    private static String change(String channel, String purpose, String action, String evidence, String version) {
        StringBuilder json = new StringBuilder("{\"channel\":\"").append(channel).append("\",\"purpose\":\"")
                .append(purpose).append("\",\"action\":\"").append(action).append("\",\"evidenceSource\":\"")
                .append(evidence).append('"');
        if (version != null) {
            json.append(",\"policyVersion\":\"").append(version).append('"');
        }
        return json.append('}').toString();
    }

    private static JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private static void assertField(HttpResponse<String> response, String field, String reason) {
        JsonNode problem = assertProblem(response, 400, "validation-failed");
        assertThat(problem.get("errors")).as(response.body()).hasSize(1);
        assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo(field);
        assertThat(problem.get("errors").get(0).get("reason").asString()).isEqualTo(reason);
        assertThat(response.body()).doesNotContain("TRANSACTIONAL").doesNotContain("PORTAL_FORM")
                .doesNotContain("drop");
    }

    private static JsonNode json(HttpResponse<String> response) {
        return json(response.body());
    }

    private static JsonNode json(String document) {
        return MAPPER.readTree(document);
    }

    /** Registers a guardian through the API (the real path) and returns its identifier. */
    private String guardian(Member member, String email, String phone) {
        StringBuilder json = new StringBuilder("{\"firstName\":\"").append(FIRST).append("\",\"lastName\":\"")
                .append(LAST).append("\",\"address\":\"").append(ADDRESS).append('"');
        if (email != null) {
            json.append(",\"email\":\"").append(email).append('"');
        }
        if (phone != null) {
            json.append(",\"phone\":\"").append(phone).append('"');
        }
        HttpResponse<String> response = post(GUARDIANS, member, json.append('}').toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).get("id").asString();
    }

    // --- Seeding ---

    private Member member(String roleCode) throws SQLException {
        UUID institution = UUID.randomUUID();
        seedInstitution(institution);
        return sameInstitution(institution, roleCode);
    }

    private Member sameInstitution(UUID institution, String roleCode) throws SQLException {
        UUID account = UUID.randomUUID();
        StaffRoleFixtures.committed(institution, account, roleCode);
        UUID family = seedLiveFamily(institution, account);
        String token = new AccessTokenIssuer(jws, Clock.fixed(now(), ZoneOffset.UTC))
                .issueAccess(account, institution, family,
                        Set.of(AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD))
                .compact();
        return new Member(account, institution, family, token);
    }

    private static void seedInstitution(UUID institution) throws SQLException {
        try (Connection connection = StaffRoleFixtures.ownerTransaction(institution);
                PreparedStatement statement = connection.prepareStatement("""
                        insert into organization_institution
                            (id, legal_name, rtn, address, default_currency, locale, timezone)
                        values (?, 'Colegio de prueba', '08011999123456', 'Tegucigalpa', 'HNL', 'es-HN',
                                'America/Tegucigalpa')
                        """)) {
            statement.setObject(1, institution);
            statement.executeUpdate();
            connection.commit();
        }
    }

    private UUID seedLiveFamily(UUID tenant, UUID account) {
        UUID family = UUID.randomUUID();
        Instant created = now().minus(Duration.ofMinutes(5));
        transactionRunner().execute(
                new SecurityContext("", "system", tenant.toString(), UUID.randomUUID().toString()),
                () -> dsl.execute("""
                        insert into identity_refresh_token_family
                            (institution_id, id, account_id, second_factor_verified,
                             user_agent_fingerprint, created_at, last_used_at, absolute_expires_at)
                        values (?, ?, ?, true, ?, cast(? as timestamptz), cast(? as timestamptz),
                                cast(? as timestamptz))
                        """, tenant, family, account,
                        com.confia.identity.domain.UserAgentFingerprint.of("agente-uno").value(),
                        created.toString(), created.toString(),
                        created.plus(Duration.ofHours(12)).toString()));
        return family;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    // --- Reading the database as the superuser of the container ---

    /** Every consent event of the guardian, oldest first (by seq). */
    private static List<EventRow> events(String guardianId) throws SQLException {
        List<EventRow> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select channel, purpose, action, evidence_source, policy_version, recorded_by, occurred_at
                          from guardians_guardian_consent_event where guardian_id = ? order by seq
                        """)) {
            statement.setObject(1, UUID.fromString(guardianId));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new EventRow(result.getString(1), result.getString(2), result.getString(3),
                            result.getString(4), result.getString(5), result.getObject(6, UUID.class),
                            result.getTimestamp(7).toInstant()));
                }
            }
        }
        return rows;
    }

    /** Every audit row of the guardians, as text, columns included. */
    private static List<String> guardianAuditRows() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select a::text from shared_audit_log a where a.action like 'guardians.%'");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }

    /** action, entity type, entity id, before, after of the consent entries, oldest first. */
    private static List<String[]> consentEntries(UUID institution) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select action, entity_type, entity_id, before_value::text, after_value::text
                          from shared_audit_log
                         where institution_id = ? and action like 'guardians.consent.%' order by id
                        """)) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new String[] {result.getString(1), result.getString(2), result.getString(3),
                            result.getString(4), result.getString(5)});
                }
            }
        }
        return rows;
    }

    private static List<String> actorsOfConsentEntries(UUID institution) throws SQLException {
        List<String> actors = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select actor_id::text from shared_audit_log
                         where institution_id = ? and action like 'guardians.consent.%'
                        """)) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actors.add(result.getString(1));
                }
            }
        }
        return actors;
    }

    private static long deniedCount(UUID institution) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from shared_audit_log where institution_id = ? "
                                + "and action = 'authorization.access.denied'")) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    // --- HTTP ---

    private static List<HttpResponse<String>> inParallel(List<Callable<HttpResponse<String>>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch ready = new CountDownLatch(calls.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (Callable<HttpResponse<String>> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<HttpResponse<String>> answers = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                answers.add(future.get());
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    private static HttpResponse<String> get(String path, Member member) {
        return send("GET", path, member.token(), null);
    }

    private static HttpResponse<String> post(String path, Member member, String body) {
        return send("POST", path, member.token(), body);
    }

    private static HttpResponse<String> put(String path, Member member, String body) {
        return send("PUT", path, member.token(), body);
    }

    private static HttpResponse<String> send(String method, String path, String token, String body) {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (!method.equals("GET")) {
            request.header("Origin", AdminProcessLauncher.ALLOWED_ORIGIN);
        }
        return execute(request.build());
    }

    /** The same request to another process, as the client at {@code address} (its proxy is the loopback). */
    private static HttpResponse<String> sendFrom(int targetPort, String address, String method, String path,
            String token, String body) {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + targetPort + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("X-Forwarded-For", address)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (!method.equals("GET")) {
            request.header("Origin", AdminProcessLauncher.ALLOWED_ORIGIN);
        }
        return execute(request.build());
    }

    private static HttpResponse<String> execute(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

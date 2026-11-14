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
import com.confia.shared.web.harness.HarnessProcess;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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

/**
 * The six routes of the guardians on the real administrative process, started through the production launcher
 * against a real PostgreSQL with the least-privilege role of the application (docs/cortes/guardians-registry.md).
 * Every token is made by the production issuer; the accounts, the roles and the institution are seeded as the owner
 * of the schema; every test has an institution of its own, so no test sees the rows of another.
 *
 * <p>It covers the acceptance criterion (an administrator registers two guardians, links them to a student with
 * 60/40 and then 50/50, the system rejects 60/30, keeps the history of the first split and never shows the
 * document), INV-32 at every boundary of the cut, each rule with the code it answers, the races the lock on the
 * student and the unique indexes settle (simultaneous replacements of the same student, a replacement against the
 * deactivation of a guardian, simultaneous creations, two creations with the same document), the isolation between
 * institutions, the role without the permission (a {@code 403} that leaves no row and no entry), the rate limits per
 * address and per account, the audit entries, and a sweep of every response, audit row and log line against the
 * national identity document and the RTN of the guardians and the names and contact data of everybody.
 */
class GuardianEndpointsIT extends CommittingPostgresIntegrationTest {

    private static final String GUARDIANS = "/api/v1/guardians";
    private static final String STUDENTS = "/api/v1/students";
    private static final String ID = "0801-1999-12345";
    private static final String ID_NORMALIZED = "0801199912345";
    private static final String RTN = "0801-1999-123456";
    private static final String RTN_DIGITS = "08011999123456";
    private static final String RATE_LIMIT = "confia.web.rate-limit.";
    private static final ZoneId HONDURAS = ZoneId.of("America/Tegucigalpa");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final String BLIND_INDEX_KEY = randomBase64Key();

    private static KeyPair keys;
    private static CompactJws jws;
    private static ConfigurableApplicationContext process;
    private static int port;

    private static final List<String> RESPONSES = Collections.synchronizedList(new ArrayList<>());
    /** Names, e-mail, phone and address: they travel in some responses and never in the audit trail nor a log. */
    private static final List<String> PERSONAL_WORDS = Collections.synchronizedList(new ArrayList<>());

    private ListAppender<ILoggingEvent> logs;
    private Level rootLevelBefore;
    private static final List<String> NOISY_LOGGERS = List.of("org.apache.coyote", "org.apache.tomcat",
            "org.postgresql", "org.jooq", "com.zaxxer.hikari");
    private final Map<String, Level> noisyLevelsBefore = new java.util.HashMap<>();

    /** The dates of the tests are relative to the day it is in Honduras, which is the day the rules judge. */
    private static final LocalDate TODAY = LocalDate.now(HONDURAS);

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
        RESPONSES.clear();
        PERSONAL_WORDS.clear();
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

    /**
     * Whatever a test did, no response, audit row or log line carries the document or the RTN of a guardian in any
     * spelling, the ciphertexts, the key of the blind index; no audit row or log line carries a name, an e-mail, a
     * phone or an address.
     */
    @AfterEach
    void nothingPersonalLeaksAnywhere() throws SQLException {
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
        List<String> forbiddenEverywhere = new ArrayList<>(List.of(ID, ID_NORMALIZED, RTN, RTN_DIGITS,
                BLIND_INDEX_KEY));
        forbiddenEverywhere.addAll(storedCiphertexts());
        for (String forbidden : forbiddenEverywhere) {
            assertThat(RESPONSES).as("responses must not contain %s", shorten(forbidden))
                    .noneMatch(text -> text.contains(forbidden));
            assertThat(audit).as("audit rows must not contain %s", shorten(forbidden))
                    .noneMatch(text -> text.contains(forbidden));
            assertThat(logLines).as("logs must not contain %s", shorten(forbidden))
                    .noneMatch(text -> text.contains(forbidden));
        }
        for (String word : new ArrayList<>(PERSONAL_WORDS)) {
            assertThat(audit).as("audit rows must not contain %s", shorten(word))
                    .noneMatch(text -> text.contains(word));
            assertThat(logLines).as("logs must not contain %s", shorten(word))
                    .noneMatch(text -> text.contains(word));
        }
    }

    private static String randomBase64Key() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return java.util.Base64.getEncoder().encodeToString(key);
    }

    private static String shorten(String text) {
        return text.length() > 12 ? text.substring(0, 12) + "..." : text;
    }

    /** One seeded staff member of one institution, with its live session and its token. */
    private record Member(UUID account, UUID institution, UUID family, String token) {
    }

    /** A row of {@code guardians_guardian_student}: guardian, percent as text, relationship, flags and dates. */
    private record LinkRow(String guardian, String percent, String relationship, boolean primary, boolean billing,
            LocalDate validFrom, LocalDate validTo) {

        boolean current() {
            return validTo == null;
        }
    }

    // --- The acceptance criterion ---

    @Test
    void anAdministratorLinksTwoGuardiansWithSixtyFortyThenFiftyFiftyTheSixtyThirtyIsRefusedTheHistoryStaysAndTheDocumentIsNeverShown()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        PERSONAL_WORDS.addAll(List.of("Marta-Pseudonimo", "Quintanilla-Reservado", "Estudiante-Reservado",
                "marta.reservada@example.org", "99887766", "Calle-Reservada-17"));
        String mother = guardian(admin, "Marta-Pseudonimo", "Quintanilla-Reservado", ID, RTN,
                "marta.reservada@example.org", "99887766", "Calle-Reservada-17");
        String father = guardian(admin, "Pedro", "Quintanilla", null, null);
        String student = student(admin, "Estudiante-Reservado", "Quintanilla");

        JsonNode first = ok(put(STUDENTS + "/" + student + "/guardians", admin, set(
                link(mother, "MOTHER", "60", true, true), link(father, "FATHER", "40", false, false))));
        assertThat(first.get("totalPercent").decimalValue()).isEqualByComparingTo("100");
        assertThat(first.get("totalPercent").toString()).isEqualTo("100.00");
        assertThat(first.get("links")).hasSize(2);
        List<LinkRow> afterFirst = linkRows(student);
        assertThat(afterFirst).hasSize(2).allMatch(LinkRow::current);

        JsonNode second = ok(put(STUDENTS + "/" + student + "/guardians", admin, set(
                link(mother, "MOTHER", "50", true, true), link(father, "FATHER", "50", false, false))));
        assertThat(second.get("totalPercent").toString()).isEqualTo("100.00");
        assertThat(percents(second)).containsExactlyInAnyOrder("50.00", "50.00");

        JsonNode refused = assertProblem(put(STUDENTS + "/" + student + "/guardians", admin, set(
                link(mother, "MOTHER", "60", true, true), link(father, "FATHER", "30", false, false))), 400,
                "invalid-responsibility-split");
        assertThat(refused.get("errors")).hasSize(1);
        assertThat(refused.get("errors").get(0).get("field").asString()).isEqualTo("links");
        assertThat(refused.get("errors").get(0).get("reason").asString()).isEqualTo("sum-not-100");

        // The current split is still 50/50, and the first split is kept as closed history, untouched.
        JsonNode current = json(get(STUDENTS + "/" + student + "/guardians", admin));
        assertThat(percents(current)).containsExactlyInAnyOrder("50.00", "50.00");
        List<LinkRow> history = linkRows(student);
        assertThat(history).hasSize(4);
        assertThat(history.stream().filter(LinkRow::current)).hasSize(2)
                .allMatch(row -> row.percent().equals("50.00"));
        List<LinkRow> closed = history.stream().filter(row -> !row.current()).toList();
        assertThat(closed).hasSize(2).allMatch(row -> row.validTo().equals(TODAY));
        assertThat(closed.stream().map(LinkRow::percent)).containsExactlyInAnyOrder("60.00", "40.00");
        assertThat(closed.stream().filter(LinkRow::primary)).hasSize(1);

        // The guardian is read with its student; the document and the RTN are only "has".
        JsonNode detail = json(get(GUARDIANS + "/" + mother, admin));
        assertThat(detail.get("hasNationalId").asBoolean()).isTrue();
        assertThat(detail.get("hasRtn").asBoolean()).isTrue();
        assertThat(detail.get("students")).hasSize(1);
        assertThat(detail.get("students").get(0).get("studentCode").asString()).isEqualTo("E-000001");
        assertThat(detail.get("students").get(0).get("financialResponsibilityPercent").decimalValue())
                .isEqualByComparingTo("50");
        assertThat(detail.propertyNames()).doesNotContain("nationalId", "rtn");
    }

    // --- The shape of the answers ---

    @Test
    void theResponsesCarryNoStoreAndTheExactMembersAndNeverTheInstitutionTheDocumentOrTheRtn() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String student = student(admin, "Ana", "Perez");

        HttpResponse<String> response = post(GUARDIANS, admin, "{\"firstName\":\"Marta\",\"lastName\":\"Lopez\","
                + "\"nationalId\":\"" + ID + "\",\"rtn\":\"" + RTN + "\",\"email\":\"marta@example.org\","
                + "\"phone\":\"+504 9988-7766\",\"address\":\"Colonia Kennedy\",\"preferredChannel\":\"WHATSAPP\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(response.body()).doesNotContain(admin.institution().toString())
                .doesNotContain(admin.account().toString()).doesNotContain("0801");
        JsonNode created = json(response);
        assertThat(created.propertyNames()).containsExactly("id", "guardianCode", "firstName", "lastName",
                "hasNationalId", "hasRtn", "email", "phone", "address", "preferredChannel", "status", "createdAt",
                "updatedAt", "students");
        assertThat(created.get("guardianCode").asString()).isEqualTo("P-000001");
        assertThat(created.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(created.get("preferredChannel").asString()).isEqualTo("WHATSAPP");
        assertThat(created.get("students")).isEmpty();

        String id = created.get("id").asString();
        ok(put(STUDENTS + "/" + student + "/guardians", admin, set(link(id, "MOTHER", "100", true, true))));

        JsonNode page = json(get(GUARDIANS, admin));
        assertThat(page.propertyNames()).containsExactly("items", "total", "page", "size");
        assertThat(page.get("items").get(0).has("students")).as("a list does not carry the students").isFalse();
        JsonNode forStudent = json(get(STUDENTS + "/" + student + "/guardians", admin));
        assertThat(forStudent.propertyNames()).containsExactly("studentId", "studentCode", "totalPercent", "links");
        assertThat(forStudent.get("links").get(0).propertyNames()).containsExactly("linkId", "guardianId",
                "guardianCode", "guardianFirstName", "guardianLastName", "guardianStatus", "relationship",
                "financialResponsibilityPercent", "isPrimaryPayer", "isBillingRecipient", "validFrom");
        JsonNode detail = json(get(GUARDIANS + "/" + id, admin));
        assertThat(detail.get("students").get(0).propertyNames()).containsExactly("linkId", "studentId",
                "studentCode", "studentFirstName", "studentLastName", "relationship",
                "financialResponsibilityPercent", "isPrimaryPayer", "isBillingRecipient", "validFrom");
    }

    @Test
    void aStudentWithoutGuardiansAnswersAnEmptySetWithTotalZero() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String student = student(admin, "Ana", "Perez");

        JsonNode view = json(get(STUDENTS + "/" + student + "/guardians", admin));

        assertThat(view.get("links")).isEmpty();
        assertThat(view.get("totalPercent").toString()).isEqualTo("0.00");
        assertThat(view.get("studentCode").asString()).isEqualTo("E-000001");
    }

    // --- Registry ---

    @Test
    void theCodesAreGeneratedPerInstitutionAndTheListSortsPagesAndFiltersOnTheServer() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Member other = member(StaffRole.ADMINISTRATOR.code());
        String zelaya = guardian(admin, "Carla", "Zelaya", null, null);
        String lopezA = guardian(admin, "Ana", "Lopez", null, null);
        guardian(admin, "Beto", "Lopez", null, null);
        String mejia = guardian(admin, "Dora", "Mejia", null, null);
        guardian(other, "Rosa", "Diaz", null, null);
        put(GUARDIANS + "/" + mejia, admin, updateBody("Dora", "Mejia", "INACTIVE"));

        assertThat(code(admin, zelaya)).isEqualTo("P-000001");
        assertThat(code(admin, lopezA)).isEqualTo("P-000002");
        assertThat(code(admin, mejia)).isEqualTo("P-000004");
        assertThat(code(other, guardianIdsOf(other).getFirst())).isEqualTo("P-000001");

        assertThat(lastNames(get(GUARDIANS, admin))).containsExactly("Lopez", "Lopez", "Mejia", "Zelaya");
        assertThat(lastNames(get(GUARDIANS + "?sort=lastName,desc", admin))).containsExactly("Zelaya", "Mejia",
                "Lopez", "Lopez");
        assertThat(lastNames(get(GUARDIANS + "?sort=code,desc&size=2", admin))).containsExactly("Mejia", "Lopez");
        assertThat(lastNames(get(GUARDIANS + "?sort=status", admin))).hasSize(4);
        assertThat(lastNames(get(GUARDIANS + "?size=3&page=1", admin))).hasSize(1);
        assertThat(lastNames(get(GUARDIANS + "?q=lopez", admin))).containsExactly("Lopez", "Lopez");
        assertThat(lastNames(get(GUARDIANS + "?q=P-000004", admin))).containsExactly("Mejia");
        assertThat(lastNames(get(GUARDIANS + "?status=INACTIVE", admin))).containsExactly("Mejia");
        assertThat(json(get(GUARDIANS + "?size=200", admin)).get("size").asInt()).isEqualTo(200);
        assertThat(json(get(GUARDIANS, admin)).get("total").asLong()).isEqualTo(4);
        assertThat(json(get(GUARDIANS, other)).get("total").asLong()).isEqualTo(1);
    }

    @Test
    void badListParametersAreRefusedAndTheLimitIsEnforcedByTheServer() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        for (String query : List.of("sort=password", "sort=firstName", "sort=nationalId", "sort=lastName,sideways",
                "sort=a,b,c", "size=0", "size=201", "page=-1", "status=FOO", "q=a", "q=" + "x".repeat(101))) {
            assertProblem(get(GUARDIANS + "?" + query, admin), 400, "validation-failed");
        }
        assertThat(get(GUARDIANS + "?size=200", admin).statusCode()).isEqualTo(200);
    }

    @Test
    void theDetailAnUnknownOrMalformedIdentifierAndTheStudentsRouteAreRefusedWithTheirCodes() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertProblem(get(GUARDIANS + "/" + UUID.randomUUID(), admin), 404, "resource-not-found");
        assertProblem(get(GUARDIANS + "/not-a-uuid", admin), 400, "validation-failed");
        assertProblem(get(STUDENTS + "/" + UUID.randomUUID() + "/guardians", admin), 404, "resource-not-found");
        assertProblem(get(STUDENTS + "/not-a-uuid/guardians", admin), 400, "validation-failed");
        assertProblem(put(GUARDIANS + "/" + UUID.randomUUID(), admin, updateBody("Ana", "Lopez", "ACTIVE")), 404,
                "resource-not-found");
        assertProblem(put(STUDENTS + "/" + UUID.randomUUID() + "/guardians", admin, set()), 404,
                "resource-not-found");
    }

    @Test
    void theSameDocumentInTheSameInstitutionIsRefusedInAnyFormatAndAnotherInstitutionMayRepeatIt() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Member other = member(StaffRole.ADMINISTRATOR.code());
        String ana = guardian(admin, "Ana", "Lopez", ID, null);
        String eva = guardian(admin, "Eva", "Mejia", "7777-6666-55555", null);

        assertProblem(post(GUARDIANS, admin, createBody("Rosa", "Diaz", ID_NORMALIZED)), 409, "duplicate-national-id");
        assertProblem(post(GUARDIANS, admin, createBody("Rosa", "Diaz", " " + ID.toLowerCase() + " ")), 409,
                "duplicate-national-id");
        assertProblem(put(GUARDIANS + "/" + eva, admin, updateBody("Eva", "Mejia", "ACTIVE", ID)), 409,
                "duplicate-national-id");
        assertThat(put(GUARDIANS + "/" + ana, admin, updateBody("Ana", "Lopez", "ACTIVE", ID)).statusCode())
                .as("a guardian may keep its own document").isEqualTo(200);
        assertThat(post(GUARDIANS, other, createBody("Rosa", "Diaz", ID)).statusCode()).isEqualTo(201);
        assertThat(countOf("guardians_guardian", admin.institution())).isEqualTo(2);
    }

    @Test
    void theBodyIsValidatedAtTheEdgeAndAnUnknownMemberLikeTheCodeOrTheInstitutionIsRefused() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String valid = createBody("Ana", "Lopez", null);

        assertProblem(post(GUARDIANS, admin, "{}"), 400, "validation-failed");
        assertProblem(post(GUARDIANS, admin, createBody("", "Lopez", null)), 400, "validation-failed");
        assertProblem(post(GUARDIANS, admin, createBody("Ana", "  ", null)), 400, "validation-failed");
        assertProblem(post(GUARDIANS, admin, createBody("A".repeat(301), "Lopez", null)), 400, "validation-failed");
        assertProblem(post(GUARDIANS, admin, createBody("A".repeat(101), "Lopez", null)), 400, "validation-failed");
        assertProblem(post(GUARDIANS, admin, createBody("Ana", "Lopez", "123")), 400, "validation-failed");
        assertProblem(post(GUARDIANS, admin, valid.replace("}", ",\"rtn\":\"0801-1999-1234\"}")), 400,
                "validation-failed");
        assertProblem(post(GUARDIANS, admin, valid.replace("}", ",\"rtn\":\"0801-1999-1234A\"}")), 400,
                "validation-failed");
        assertProblem(post(GUARDIANS, admin, valid.replace("}", ",\"email\":\"sin-arroba\"}")), 400,
                "validation-failed");
        assertProblem(post(GUARDIANS, admin, valid.replace("}", ",\"phone\":\"123\"}")), 400, "validation-failed");
        assertProblem(post(GUARDIANS, admin, valid.replace("}", ",\"phone\":\"abc-defg-hij\"}")), 400,
                "validation-failed");
        assertProblem(post(GUARDIANS, admin, valid.replace("}", ",\"address\":\"" + "x".repeat(401) + "\"}")), 400,
                "validation-failed");
        assertProblem(post(GUARDIANS, admin, valid.replace("}", ",\"preferredChannel\":\"FAX\"}")), 400,
                "validation-failed");
        assertProblem(post(GUARDIANS, admin, valid.replace("}", ",\"guardianCode\":\"P-999999\"}")), 400,
                "validation-failed");
        assertProblem(post(GUARDIANS, admin, valid.replace("}", ",\"institutionId\":\"" + admin.institution()
                + "\"}")), 400, "validation-failed");
        assertProblem(post(GUARDIANS, admin, valid.replace("}", ",\"status\":\"INACTIVE\"}")), 400,
                "validation-failed");
        assertThat(countOf("guardians_guardian", admin.institution())).isZero();
        assertThat(post(GUARDIANS, admin, valid.replace("}", ",\"phone\":\"(504) 2222-3333\",\"email\":\"a@b.hn\"}"))
                .statusCode()).isEqualTo(201);
    }

    @Test
    void anEditKeepsRemovesOrReplacesTheDocumentAndTheRtnAndKeepsTheCode() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = guardian(admin, "Ana", "Lopez", ID, RTN);
        assertThat(documentColumns(id)).containsExactly(true, true, true);

        JsonNode kept = ok(put(GUARDIANS + "/" + id, admin, "{\"firstName\":\"Ana\",\"lastName\":\"Lopez-Perez\","
                + "\"status\":\"ACTIVE\"}"));
        assertThat(kept.get("guardianCode").asString()).isEqualTo("P-000001");
        assertThat(kept.get("lastName").asString()).isEqualTo("Lopez-Perez");
        assertThat(kept.get("hasNationalId").asBoolean()).isTrue();
        assertThat(kept.get("hasRtn").asBoolean()).isTrue();
        assertThat(documentColumns(id)).containsExactly(true, true, true);

        JsonNode rtnRemoved = ok(put(GUARDIANS + "/" + id, admin, "{\"firstName\":\"Ana\",\"lastName\":\"Lopez\","
                + "\"rtn\":\"\",\"status\":\"ACTIVE\"}"));
        assertThat(rtnRemoved.get("hasRtn").asBoolean()).isFalse();
        assertThat(rtnRemoved.get("hasNationalId").asBoolean()).isTrue();
        assertThat(documentColumns(id)).containsExactly(true, true, false);

        JsonNode documentRemoved = ok(put(GUARDIANS + "/" + id, admin, "{\"firstName\":\"Ana\",\"lastName\":\"Lopez\","
                + "\"nationalId\":\"  \",\"status\":\"ACTIVE\"}"));
        assertThat(documentRemoved.get("hasNationalId").asBoolean()).isFalse();
        assertThat(documentColumns(id)).containsExactly(false, false, false);

        JsonNode replaced = ok(put(GUARDIANS + "/" + id, admin, "{\"firstName\":\"Ana\",\"lastName\":\"Lopez\","
                + "\"nationalId\":\"" + ID + "\",\"rtn\":\"" + RTN + "\",\"status\":\"ACTIVE\"}"));
        assertThat(replaced.get("hasNationalId").asBoolean()).isTrue();
        assertThat(replaced.get("hasRtn").asBoolean()).isTrue();
        assertThat(documentColumns(id)).containsExactly(true, true, true);
        assertProblem(put(GUARDIANS + "/" + id, admin, "{\"firstName\":\"Ana\",\"lastName\":\"Lopez\"}"), 400,
                "validation-failed");
        assertProblem(put(GUARDIANS + "/" + id, admin, "{\"firstName\":\"Ana\",\"lastName\":\"Lopez\","
                + "\"status\":\"DELETED\"}"), 400, "validation-failed");
        assertProblem(put(GUARDIANS + "/" + id, admin, "{\"firstName\":\"Ana\",\"lastName\":\"Lopez\","
                + "\"status\":\"ACTIVE\",\"guardianCode\":\"P-9\"}"), 400, "validation-failed");
    }

    @Test
    void aPutReplacesTheWholeRecordSoAnOmittedContactMemberIsCleared() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = guardian(admin, "Ana", "Lopez", null, null, "ana@example.org", "99887766", "Casa-Uno");
        PERSONAL_WORDS.addAll(List.of("ana@example.org", "99887766", "Casa-Uno"));

        JsonNode cleared = ok(put(GUARDIANS + "/" + id, admin, updateBody("Ana", "Lopez", "ACTIVE")));

        assertThat(cleared.get("email").isNull()).isTrue();
        assertThat(cleared.get("phone").isNull()).isTrue();
        assertThat(cleared.get("address").isNull()).isTrue();
        assertThat(cleared.get("preferredChannel").asString()).isEqualTo("NONE");
    }

    @Test
    void aGuardianMadeInactiveKeepsItsCurrentLinksAndTheAnswerListsThemButCannotReceiveNewOnes() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String mother = guardian(admin, "Marta", "Lopez", null, null);
        String father = guardian(admin, "Pedro", "Lopez", null, null);
        String student = student(admin, "Ana", "Perez");
        String other = student(admin, "Luis", "Perez");
        ok(put(STUDENTS + "/" + student + "/guardians", admin, set(link(mother, "MOTHER", "100", true, true))));

        JsonNode inactive = ok(put(GUARDIANS + "/" + mother, admin, updateBody("Marta", "Lopez", "INACTIVE")));

        assertThat(inactive.get("status").asString()).isEqualTo("INACTIVE");
        assertThat(inactive.get("students")).hasSize(1);
        assertThat(linkRows(student)).hasSize(1).allMatch(LinkRow::current);
        // It cannot be linked to another student, nor stay in a set that is replaced...
        assertProblem(put(STUDENTS + "/" + other + "/guardians", admin, set(link(mother, "MOTHER", "100", false, false))),
                409, "guardian-not-active");
        assertProblem(put(STUDENTS + "/" + student + "/guardians", admin, set(
                link(mother, "MOTHER", "60", false, false), link(father, "FATHER", "40", false, false))),
                409, "guardian-not-active");
        assertThat(linkRows(other)).isEmpty();
        // ... but the staff can remove it from the set, which closes its link and keeps the history.
        ok(put(STUDENTS + "/" + student + "/guardians", admin, set(link(father, "FATHER", "100", false, false))));
        assertThat(linkRows(student)).hasSize(2);
        assertThat(linkRows(student).stream().filter(LinkRow::current)).hasSize(1);
        assertThat(json(get(GUARDIANS + "/" + mother, admin)).get("students")).isEmpty();
    }

    // --- INV-32 through the API ---

    @Test
    void theSumMustBeExactlyOneHundredAtEveryBoundaryAndThreeGuardiansWithThirtyThreeThirtyThreeThirtyThreePointThirtyFourFit()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String a = guardian(admin, "Ana", "Lopez", null, null);
        String b = guardian(admin, "Beto", "Lopez", null, null);
        String c = guardian(admin, "Carla", "Lopez", null, null);
        String student = student(admin, "Luis", "Perez");
        String path = STUDENTS + "/" + student + "/guardians";

        assertSplitRefused(put(path, admin, set(link(a, "MOTHER", "50", false, false), link(b, "FATHER", "49.99", false,
                false))), "sum-not-100");
        assertSplitRefused(put(path, admin, set(link(a, "MOTHER", "50", false, false), link(b, "FATHER", "50.01", false,
                false))), "sum-not-100");
        assertSplitRefused(put(path, admin, set(link(a, "MOTHER", "33.33", false, false), link(b, "FATHER", "33.33",
                false, false), link(c, "OTHER", "33.33", false, false))), "sum-not-100");
        assertSplitRefused(put(path, admin, set(link(a, "MOTHER", "60", false, false), link(b, "FATHER", "30", false,
                false))), "sum-not-100");
        assertSplitRefused(put(path, admin, set(link(a, "MOTHER", "99.99", false, false))), "sum-not-100");
        assertThat(linkRows(student)).as("nothing was written by the refusals").isEmpty();
        assertThat(guardianEntries(admin.institution(), "guardians.links.replaced")).isEmpty();

        JsonNode thirds = ok(put(path, admin, set(link(a, "MOTHER", "33.33", false, false), link(b, "FATHER", "33.33",
                false, false), link(c, "OTHER", "33.34", false, false))));
        assertThat(thirds.get("totalPercent").toString()).isEqualTo("100.00");
        assertThat(percents(thirds)).containsExactlyInAnyOrder("33.33", "33.33", "33.34");
        // Written in another order, it is the same set; trailing zeros beyond two decimals are refused at the edge.
        JsonNode same = ok(put(path, admin, set(link(c, "OTHER", "33.34", false, false), link(a, "MOTHER", "33.33",
                false, false), link(b, "FATHER", "33.33", false, false))));
        assertThat(same.get("totalPercent").toString()).isEqualTo("100.00");
        assertProblem(put(path, admin, set(link(a, "MOTHER", "33.330", false, false))), 400, "validation-failed");
        assertThat(linkRows(student)).hasSize(3);
    }

    @Test
    void aPercentageOfZeroOfOneHundredPointZeroOneWithThreeDecimalsNegativeOrMissingIsAValidationFailure()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String a = guardian(admin, "Ana", "Lopez", null, null);
        String student = student(admin, "Luis", "Perez");
        String path = STUDENTS + "/" + student + "/guardians";

        for (String percent : List.of("0", "0.00", "0.009", "100.01", "100.1", "101", "-10", "33.333", "1000")) {
            assertProblem(put(path, admin, set(link(a, "MOTHER", percent, false, false))), 400, "validation-failed");
        }
        assertProblem(put(path, admin, set("{\"guardianId\":\"" + a + "\",\"relationship\":\"MOTHER\","
                + "\"isPrimaryPayer\":false,\"isBillingRecipient\":false}")), 400, "validation-failed");
        assertProblem(put(path, admin, set("{\"guardianId\":\"" + a + "\",\"relationship\":\"MOTHER\","
                + "\"financialResponsibilityPercent\":\"muchos\",\"isPrimaryPayer\":false,"
                + "\"isBillingRecipient\":false}")), 400, "validation-failed");
        assertThat(linkRows(student)).isEmpty();
        assertThat(ok(put(path, admin, set(link(a, "MOTHER", "100", false, false)))).get("links")).hasSize(1);
        assertThat(linkRows(student)).hasSize(1);
    }

    @Test
    void twoPrimaryPayersTwoBillingRecipientsAndARepeatedGuardianAreRefusedWithTheirReasonOnTheLinksField()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String a = guardian(admin, "Ana", "Lopez", null, null);
        String b = guardian(admin, "Beto", "Lopez", null, null);
        String student = student(admin, "Luis", "Perez");
        String path = STUDENTS + "/" + student + "/guardians";

        assertSplitRefused(put(path, admin, set(link(a, "MOTHER", "50", true, false), link(b, "FATHER", "50", true,
                false))), "multiple-primary-payer");
        assertSplitRefused(put(path, admin, set(link(a, "MOTHER", "50", false, true), link(b, "FATHER", "50", false,
                true))), "multiple-billing-recipient");
        assertSplitRefused(put(path, admin, set(link(a, "MOTHER", "50", false, false), link(a, "FATHER", "50", false,
                false))), "duplicate-guardian");
        assertThat(linkRows(student)).isEmpty();
        // One primary payer and one billing recipient on different guardians, or none at all, are fine.
        ok(put(path, admin, set(link(a, "MOTHER", "50", true, false), link(b, "FATHER", "50", false, true))));
        ok(put(path, admin, set(link(a, "MOTHER", "50", false, false), link(b, "FATHER", "50", false, false))));
    }

    @Test
    void anEmptySetIsValidAndRemovesTheGuardiansKeepingTheirHistory() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String a = guardian(admin, "Ana", "Lopez", null, null);
        String student = student(admin, "Luis", "Perez");
        String path = STUDENTS + "/" + student + "/guardians";

        JsonNode none = ok(put(path, admin, set()));
        assertThat(none.get("links")).isEmpty();
        assertThat(none.get("totalPercent").toString()).isEqualTo("0.00");
        assertThat(guardianEntries(admin.institution(), "guardians.links.replaced")).as("nothing changed").isEmpty();

        ok(put(path, admin, set(link(a, "MOTHER", "100", true, true))));
        JsonNode removed = ok(put(path, admin, set()));

        assertThat(removed.get("links")).isEmpty();
        assertThat(removed.get("totalPercent").toString()).isEqualTo("0.00");
        assertThat(linkRows(student)).hasSize(1).allMatch(row -> !row.current() && row.validTo().equals(TODAY));
        assertProblem(put(path, admin, "{}"), 400, "validation-failed");
        assertProblem(put(path, admin, "{\"links\":null}"), 400, "validation-failed");
        assertProblem(put(path, admin, "{\"links\":[null]}"), 400, "validation-failed");
        assertProblem(put(path, admin, "{\"links\":[],\"studentId\":\"x\"}"), 400, "validation-failed");
        StringBuilder many = new StringBuilder("{\"links\":[");
        for (int i = 0; i < 21; i++) {
            many.append(i == 0 ? "" : ",").append(link(a, "OTHER", "1", false, false));
        }
        assertProblem(put(path, admin, many.append("]}").toString()), 400, "validation-failed");
    }

    @Test
    void anInactiveStudentAdmitsNoChangeAnUnknownGuardianOrOneOfAnotherInstitutionIsNotFound() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        String mine = guardian(admin, "Ana", "Lopez", null, null);
        String foreign = guardian(theirs, "Rosa", "Diaz", null, null);
        String student = student(admin, "Luis", "Perez");
        String path = STUDENTS + "/" + student + "/guardians";
        ok(put(path, admin, set(link(mine, "MOTHER", "100", false, false))));
        ok(put(STUDENTS + "/" + student, admin, "{\"firstName\":\"Luis\",\"lastName\":\"Perez\","
                + "\"birthDate\":\"2011-11-11\",\"sex\":\"F\",\"status\":\"INACTIVE\"}"));

        assertProblem(put(path, admin, set(link(mine, "MOTHER", "100", true, false))), 409, "student-not-active");
        assertProblem(put(path, admin, set()), 409, "student-not-active");
        assertThat(get(path, admin).statusCode()).as("an inactive student can still be read").isEqualTo(200);
        assertThat(linkRows(student)).hasSize(1).allMatch(LinkRow::current);

        String active = student(admin, "Eva", "Perez");
        assertProblem(put(STUDENTS + "/" + active + "/guardians", admin, set(link(UUID.randomUUID().toString(), "MOTHER",
                "100", false, false))), 404, "resource-not-found");
        assertProblem(put(STUDENTS + "/" + active + "/guardians", admin, set(link(foreign, "MOTHER", "100", false,
                false))), 404, "resource-not-found");
        assertThat(linkRows(active)).isEmpty();
    }

    @Test
    void anIdenticalReplacementWritesNothingAndLeavesNoEntryAndOnlyTheChangedGuardianIsClosedAndOpened()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String mother = guardian(admin, "Marta", "Lopez", null, null);
        String father = guardian(admin, "Pedro", "Lopez", null, null);
        String aunt = guardian(admin, "Tia", "Lopez", null, null);
        String student = student(admin, "Luis", "Perez");
        String path = STUDENTS + "/" + student + "/guardians";
        ok(put(path, admin, set(link(mother, "MOTHER", "60", true, false), link(father, "FATHER", "40", false, true))));
        List<LinkRow> before = linkRows(student);
        int entries = guardianEntries(admin.institution(), "guardians.links.replaced").size();

        JsonNode again = ok(put(path, admin, set(link(father, "FATHER", "40.00", false, true),
                link(mother, "MOTHER", "60.0", true, false))));

        assertThat(again.get("links")).hasSize(2);
        assertThat(linkRows(student)).isEqualTo(before);
        assertThat(guardianEntries(admin.institution(), "guardians.links.replaced")).hasSize(entries);

        ok(put(path, admin, set(link(mother, "MOTHER", "60", true, false), link(aunt, "OTHER", "40", false, true))));

        List<LinkRow> after = linkRows(student);
        assertThat(after).hasSize(3);
        assertThat(after.stream().filter(LinkRow::current).map(LinkRow::guardian))
                .containsExactlyInAnyOrder("P-000001", "P-000003");
        assertThat(after.stream().filter(row -> !row.current()).map(LinkRow::guardian)).containsExactly("P-000002");
        assertThat(guardianEntries(admin.institution(), "guardians.links.replaced")).hasSize(entries + 1);
    }

    // --- Races ---

    @Test
    void simultaneousReplacementsOfTheSameStudentTakeTurnsAndLeaveOneValidSetWithItsHistory() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String a = guardian(admin, "Ana", "Lopez", null, null);
        String b = guardian(admin, "Beto", "Lopez", null, null);
        String c = guardian(admin, "Carla", "Lopez", null, null);
        for (int round = 0; round < 4; round++) {
            String student = student(admin, "Luis" + round, "Perez");
            String path = STUDENTS + "/" + student + "/guardians";
            String setOne = set(link(a, "MOTHER", "50", true, true), link(b, "FATHER", "50", false, false));
            String setTwo = set(link(a, "MOTHER", "70", false, false), link(c, "OTHER", "30", true, true));
            String setThree = set(link(c, "OTHER", "100", true, true));
            if (round % 2 == 1) {
                ok(put(path, admin, set(link(b, "FATHER", "60", false, false), link(c, "OTHER", "40", false, false))));
            }

            List<HttpResponse<String>> answers = inParallel(() -> put(path, admin, setOne),
                    () -> put(path, admin, setTwo), () -> put(path, admin, setThree), () -> put(path, admin, setOne),
                    () -> put(path, admin, setTwo), () -> put(path, admin, setThree));

            assertThat(answers).extracting(HttpResponse::statusCode).as(answers.toString()).containsOnly(200);
            for (HttpResponse<String> answer : answers) {
                assertThat(json(answer).get("totalPercent").toString()).isEqualTo("100.00");
            }
            List<LinkRow> rows = linkRows(student);
            List<LinkRow> current = rows.stream().filter(LinkRow::current).toList();
            assertThat(current.stream().map(row -> new java.math.BigDecimal(row.percent()))
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)).isEqualByComparingTo("100");
            assertThat(current.stream().map(LinkRow::guardian).distinct()).hasSameSizeAs(current);
            assertThat(current.stream().filter(LinkRow::primary).count()).isLessThanOrEqualTo(1);
            assertThat(current.stream().filter(LinkRow::billing).count()).isLessThanOrEqualTo(1);
            assertThat(rows.stream().filter(row -> !row.current())).allMatch(row -> row.validTo().equals(TODAY));
            // The final set is exactly one of the three that were asked for, never a mixture.
            Set<String> finalSet = new HashSet<>();
            current.forEach(row -> finalSet.add(row.guardian() + "=" + row.percent()));
            assertThat(List.of(Set.of("P-000001=50.00", "P-000002=50.00"), Set.of("P-000001=70.00", "P-000003=30.00"),
                    Set.of("P-000003=100.00"))).contains(finalSet);
            assertThat(json(get(path, admin)).get("totalPercent").toString()).isEqualTo("100.00");
        }
    }

    @Test
    void aReplacementRacingTheDeactivationOfItsGuardianEndsConsistentlyWhicheverWins() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        for (int round = 0; round < 4; round++) {
            String name = "Ana" + round;
            String a = guardian(admin, name, "Lopez", null, null);
            String student = student(admin, "Luis" + round, "Perez");
            String path = STUDENTS + "/" + student + "/guardians";

            List<HttpResponse<String>> answers = inParallel(
                    () -> put(path, admin, set(link(a, "MOTHER", "100", true, true))),
                    () -> put(GUARDIANS + "/" + a, admin, updateBody(name, "Lopez", "INACTIVE")));

            assertThat(answers.get(1).statusCode()).isEqualTo(200);
            int replacement = answers.get(0).statusCode();
            assertThat(replacement).as(answers.get(0).body()).isIn(200, 409);
            List<LinkRow> rows = linkRows(student);
            if (replacement == 200) {
                // The link was judged before the guardian went inactive: it stays, as the staff decided.
                assertThat(rows).hasSize(1).allMatch(LinkRow::current);
            } else {
                assertThat(answers.get(0).body()).contains("guardian-not-active");
                assertThat(rows).isEmpty();
            }
        }
    }

    @Test
    void simultaneousCreationsGetDistinctConsecutiveCodesAndTwoCreationsWithTheSameDocumentAdmitOne()
            throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        List<Callable<HttpResponse<String>>> creations = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String body = createBody("Persona" + i, "Concurrente", null);
            creations.add(() -> post(GUARDIANS, admin, body));
        }
        List<HttpResponse<String>> created = inParallel(creations.toArray(new Callable[0]));
        assertThat(created).extracting(HttpResponse::statusCode).containsOnly(201);
        assertThat(created.stream().map(r -> json(r).get("guardianCode").asString()))
                .containsExactlyInAnyOrder("P-000001", "P-000002", "P-000003", "P-000004", "P-000005", "P-000006",
                        "P-000007", "P-000008");

        List<HttpResponse<String>> same = inParallel(() -> post(GUARDIANS, admin, createBody("Uno", "Igual", ID)),
                () -> post(GUARDIANS, admin, createBody("Dos", "Igual", ID)));
        assertThat(same).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
        assertThat(same.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow().body())
                .contains("duplicate-national-id");
        assertThat(countOf("guardians_guardian", admin.institution())).isEqualTo(9);
    }

    // --- Isolation, roles, sessions ---

    @Test
    void anotherInstitutionSeesNothingAndCannotTouchTheGuardiansStudentsOrLinksOfThisOne() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        String myGuardian = guardian(mine, "Ana", "Lopez", ID, null);
        String myStudent = student(mine, "Luis", "Perez");
        String theirGuardian = guardian(theirs, "Rosa", "Diaz", null, null);
        String theirStudent = student(theirs, "Eva", "Mejia");
        ok(put(STUDENTS + "/" + myStudent + "/guardians", mine, set(link(myGuardian, "MOTHER", "100", true, true))));

        assertProblem(get(GUARDIANS + "/" + myGuardian, theirs), 404, "resource-not-found");
        assertProblem(get(STUDENTS + "/" + myStudent + "/guardians", theirs), 404, "resource-not-found");
        assertProblem(put(GUARDIANS + "/" + myGuardian, theirs, updateBody("X", "Y", "INACTIVE")), 404,
                "resource-not-found");
        assertProblem(put(STUDENTS + "/" + myStudent + "/guardians", theirs, set()), 404, "resource-not-found");
        assertProblem(put(STUDENTS + "/" + theirStudent + "/guardians", theirs, set(link(myGuardian, "MOTHER", "100",
                false, false))), 404, "resource-not-found");
        assertProblem(put(STUDENTS + "/" + myStudent + "/guardians", mine, set(link(theirGuardian, "MOTHER", "100",
                false, false))), 404, "resource-not-found");
        assertThat(json(get(GUARDIANS, theirs)).get("total").asLong()).isEqualTo(1);
        assertThat(json(get(GUARDIANS, mine)).get("total").asLong()).isEqualTo(1);
        assertThat(json(get(GUARDIANS + "/" + myGuardian, mine)).get("status").asString())
                .isEqualTo("ACTIVE");
        assertThat(linkRows(myStudent)).hasSize(1).allMatch(LinkRow::current);
        assertThat(linkRows(theirStudent)).isEmpty();
        assertThat(post(GUARDIANS, theirs, createBody("Otra", "Persona", ID)).statusCode())
                .as("the document of another institution never collides").isEqualTo(201);
    }

    @Test
    void aCashierAndACoordinatorMayReadButCannotCreateEditOrReplaceAndTheRefusalLeavesNoRowAndNoEntry()
            throws SQLException {
        Member cashier = member(StaffRole.CASHIER.code());
        Member coordinator = sameInstitution(cashier.institution(), StaffRole.ACADEMIC_COORDINATOR.code());
        Member admin = sameInstitution(cashier.institution(), StaffRole.ADMINISTRATOR.code());
        String ana = guardian(admin, "Ana", "Lopez", null, null);
        String student = student(admin, "Luis", "Perez");
        ok(put(STUDENTS + "/" + student + "/guardians", admin, set(link(ana, "MOTHER", "100", true, true))));
        int entriesBefore = guardianEntries(cashier.institution(), null).size();
        long deniedBefore = deniedCount(cashier.institution());

        for (Member reader : List.of(cashier, coordinator)) {
            assertThat(get(GUARDIANS, reader).statusCode()).isEqualTo(200);
            assertThat(get(GUARDIANS + "/" + ana, reader).statusCode()).isEqualTo(200);
            assertThat(get(STUDENTS + "/" + student + "/guardians", reader).statusCode()).isEqualTo(200);
            assertProblem(post(GUARDIANS, reader, createBody("Eva", "Mejia", null)), 403, "forbidden");
            assertProblem(put(GUARDIANS + "/" + ana, reader, updateBody("Ana", "Lopez", "INACTIVE")), 403, "forbidden");
            assertProblem(put(STUDENTS + "/" + student + "/guardians", reader, set()), 403, "forbidden");
        }

        assertThat(countOf("guardians_guardian", cashier.institution())).isEqualTo(1);
        assertThat(json(get(GUARDIANS + "/" + ana, admin)).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(linkRows(student)).hasSize(1).allMatch(LinkRow::current);
        assertThat(guardianEntries(cashier.institution(), null)).as("no entry for a refused request")
                .hasSize(entriesBefore);
        assertThat(deniedCount(cashier.institution()) - deniedBefore).isEqualTo(6);
    }

    @Test
    void anAccountWithoutARoleCannotEvenReadAndNoCredentialAnswers401() throws SQLException {
        Member nobody = member(null);

        assertProblem(get(GUARDIANS, nobody), 403, "forbidden");
        assertProblem(get(GUARDIANS + "/" + UUID.randomUUID(), nobody), 403, "forbidden");
        assertProblem(get(STUDENTS + "/" + UUID.randomUUID() + "/guardians", nobody), 403, "forbidden");
        assertProblem(put(STUDENTS + "/" + UUID.randomUUID() + "/guardians", nobody, set()), 403, "forbidden");
        assertProblem(send("GET", GUARDIANS, null, null), 401, "authentication-required");
        assertProblem(send("PUT", STUDENTS + "/" + UUID.randomUUID() + "/guardians", null, "{}"), 401,
                "authentication-required");
        assertProblem(send("POST", GUARDIANS, null, "{}"), 401, "authentication-required");
    }

    @Test
    void aClosedSessionOpensNoRouteOfTheGuardians() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String ana = guardian(admin, "Ana", "Lopez", null, null);
        String student = student(admin, "Luis", "Perez");
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

        assertProblem(get(GUARDIANS, admin), 401, "token-invalid");
        assertProblem(get(GUARDIANS + "/" + ana, admin), 401, "token-invalid");
        assertProblem(get(STUDENTS + "/" + student + "/guardians", admin), 401, "token-invalid");
        assertProblem(post(GUARDIANS, admin, createBody("Eva", "Mejia", null)), 401, "token-invalid");
        assertProblem(put(GUARDIANS + "/" + ana, admin, updateBody("Ana", "Lopez", "INACTIVE")), 401, "token-invalid");
        assertProblem(put(STUDENTS + "/" + student + "/guardians", admin, set(link(ana, "MOTHER", "100", false, false))),
                401, "token-invalid");
        assertThat(countOf("guardians_guardian", admin.institution())).isEqualTo(1);
        assertThat(linkRows(student)).isEmpty();
    }

    @Test
    void aWriteWithoutAnAllowedOriginIsRefusedBeforeAnyEffect() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        HttpResponse<String> response = execute(HttpRequest.newBuilder(URI.create("http://localhost:" + port + GUARDIANS))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + admin.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(createBody("Ana", "Lopez", null))).build());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(countOf("guardians_guardian", admin.institution())).isZero();
    }

    // --- Audit ---

    @Test
    void everyWriteLeavesOneEntryWithCodesStatesAndPercentagesAndNeverNamesDocumentsRtnOrContactData()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        PERSONAL_WORDS.addAll(List.of("Nombre-Reservado-Uno", "Apellido-Reservado", "uno.reservado@example.org",
                "50505050", "Direccion-Reservada", "Alumno-Reservado", "Nombre-Reservado-Dos"));
        String one = guardian(admin, "Nombre-Reservado-Uno", "Apellido-Reservado", ID, RTN,
                "uno.reservado@example.org", "50505050", "Direccion-Reservada");
        String two = guardian(admin, "Nombre-Reservado-Dos", "Apellido-Reservado", null, null);
        String student = student(admin, "Alumno-Reservado", "Apellido-Reservado");
        String path = STUDENTS + "/" + student + "/guardians";
        ok(put(path, admin, set(link(one, "MOTHER", "60", true, true), link(two, "FATHER", "40", false, false))));
        ok(put(path, admin, set(link(one, "MOTHER", "50", true, true), link(two, "FATHER", "50", false, false))));
        ok(put(GUARDIANS + "/" + one, admin, "{\"firstName\":\"Nombre-Reservado-Uno\","
                + "\"lastName\":\"Apellido-Reservado\",\"nationalId\":\"\",\"status\":\"INACTIVE\","
                + "\"email\":\"uno.reservado@example.org\",\"phone\":\"50505050\",\"address\":\"Direccion-Reservada\"}"));

        List<String[]> entries = guardianEntries(admin.institution(), null);
        assertThat(entries).extracting(row -> row[0]).containsExactly("guardians.guardian.created",
                "guardians.guardian.created", "guardians.links.replaced", "guardians.links.replaced",
                "guardians.guardian.updated");
        assertThat(entries.get(0)[1]).isEqualTo("guardians.guardian");
        assertThat(entries.get(0)[2]).isEqualTo(one);
        assertThat(entries.get(0)[3]).isNull();
        JsonNode created = json(entries.get(0)[4]);
        assertThat(created.get("guardianCode").asString()).isEqualTo("P-000001");
        assertThat(created.get("hasNationalId").asBoolean()).isTrue();
        assertThat(created.get("hasRtn").asBoolean()).isTrue();
        assertThat(entries.get(2)[1]).isEqualTo("guardians.links");
        assertThat(entries.get(2)[2]).isEqualTo(student);
        assertThat(json(entries.get(2)[3]).get("linkCount").asInt()).isZero();
        assertThat(json(entries.get(2)[4]).get("links").asString()).contains("P-000001:MOTHER:60.00:primary:billing")
                .contains("P-000002:FATHER:40.00");
        assertThat(json(entries.get(3)[3]).get("totalPercent").asString()).isEqualTo("100.00");
        assertThat(json(entries.get(3)[4]).get("links").asString()).contains("P-000001:MOTHER:50.00:primary:billing")
                .contains("P-000002:FATHER:50.00");
        JsonNode updated = json(entries.get(4)[4]);
        assertThat(updated.get("status").asString()).isEqualTo("INACTIVE");
        assertThat(updated.get("changedFields").asString().split(",")).containsExactlyInAnyOrder("status",
                "nationalId");
        assertThat(actorsOfGuardianEntries(admin.institution())).containsOnly(admin.account().toString());
    }

    @Test
    void aWriteThatRollsBackLeavesNeitherTheRowNorTheEntry() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String active = guardian(admin, "Ana", "Lopez", ID, null);
        String inactive = guardian(admin, "Eva", "Mejia", null, null);
        put(GUARDIANS + "/" + inactive, admin, updateBody("Eva", "Mejia", "INACTIVE"));
        String student = student(admin, "Luis", "Perez");
        int entries = guardianEntries(admin.institution(), null).size();

        assertProblem(put(STUDENTS + "/" + student + "/guardians", admin, set(link(active, "MOTHER", "50", false, false),
                link(inactive, "FATHER", "50", false, false))), 409, "guardian-not-active");
        assertProblem(post(GUARDIANS, admin, createBody("Rosa", "Diaz", ID)), 409, "duplicate-national-id");

        assertThat(countOf("guardians_guardian", admin.institution())).isEqualTo(2);
        assertThat(linkRows(student)).isEmpty();
        // Only the refused duplicate document leaves a trace: one denied entry, with a reason and no values.
        List<String[]> after = guardianEntries(admin.institution(), null);
        assertThat(after).hasSize(entries + 1);
        String[] denied = after.getLast();
        assertThat(denied[0]).isEqualTo("guardians.guardian.created");
        assertThat(denied[3]).isNull();
        assertThat(denied[4]).isNull();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select outcome, reason from shared_audit_log where institution_id = ? and entity_id = ?")) {
            statement.setObject(1, admin.institution());
            statement.setString(2, denied[2]);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("denied");
                assertThat(result.getString(2)).isEqualTo("duplicate-national-id");
                assertThat(result.next()).isFalse();
            }
        }
        // The refused creation gave its code back: the counter is rolled back with the transaction.
        assertThat(code(admin, guardian(admin, "Nueva", "Persona", null, null))).isEqualTo("P-000003");
    }

    // --- Rate limits ---

    @Test
    void theSixRoutesAreRateLimitedPerAddressWithRetryAfterAndAnotherAddressIsNotAffected() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String ana = guardian(admin, "Ana", "Lopez", null, null);
        String student = student(admin, "Luis", "Perez");
        try (ConfigurableApplicationContext limited = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(),
                "confia_admin_app", TEST_PASSWORD, keys, Map.of("confia.web.trusted-proxies", "127.0.0.1,::1",
                        "confia.crypto.blind-index.current-version", "v1",
                        "confia.crypto.blind-index.keys.v1", BLIND_INDEX_KEY,
                        RATE_LIMIT + "admin-read.request-limit", "2", RATE_LIMIT + "admin-write.request-limit", "1"))) {
            int limitedPort = ((WebServerApplicationContext) limited).getWebServer().getPort();
            String noisy = "203.0.113.30";
            String quiet = "203.0.113.31";

            // Writes: one is admitted for the address; the creation, the edit and the replacement share the quota.
            assertThat(sendFrom(limitedPort, noisy, "PUT", STUDENTS + "/" + student + "/guardians", admin.token(),
                    set(link(ana, "MOTHER", "100", false, false))).statusCode()).isEqualTo(200);
            HttpResponse<String> refused = sendFrom(limitedPort, noisy, "POST", GUARDIANS, admin.token(),
                    createBody("Eva", "Mejia", null));
            assertProblem(refused, 429, "too-many-requests");
            assertThat(refused.headers().firstValue("Retry-After")).hasValueSatisfying(seconds ->
                    assertThat(Long.parseLong(seconds)).isBetween(1L, 60L));
            assertProblem(sendFrom(limitedPort, noisy, "PUT", GUARDIANS + "/" + ana, admin.token(),
                    updateBody("Ana", "Lopez", "INACTIVE")), 429, "too-many-requests");
            assertProblem(sendFrom(limitedPort, noisy, "PUT", STUDENTS + "/" + student + "/guardians", admin.token(),
                    set()), 429, "too-many-requests");

            // Reads: two are admitted for the address; the list, the detail and the route of the student share the quota.
            assertThat(sendFrom(limitedPort, noisy, "GET", GUARDIANS, admin.token(), null).statusCode()).isEqualTo(200);
            assertThat(sendFrom(limitedPort, noisy, "GET", GUARDIANS + "/" + ana, admin.token(), null).statusCode())
                    .isEqualTo(200);
            HttpResponse<String> refusedRead = sendFrom(limitedPort, noisy, "GET",
                    STUDENTS + "/" + student + "/guardians", admin.token(), null);
            assertProblem(refusedRead, 429, "too-many-requests");
            assertThat(refusedRead.headers().firstValue("Retry-After")).isPresent();

            // Another address is not affected, and the refusals changed nothing.
            assertThat(sendFrom(limitedPort, quiet, "GET", GUARDIANS, admin.token(), null).statusCode()).isEqualTo(200);
            assertThat(sendFrom(limitedPort, quiet, "PUT", GUARDIANS + "/" + ana, admin.token(),
                    updateBody("Ana", "Lopez", "ACTIVE")).statusCode()).isEqualTo(200);
            assertThat(countOf("guardians_guardian", admin.institution())).isEqualTo(1);
            assertThat(linkRows(student)).hasSize(1).allMatch(LinkRow::current);
        }
    }

    @Test
    void theRoutesAreAlsoRateLimitedPerAccountSoAnotherAccountIsUnaffectedAndTwoAddressesOfOneAccountShareTheQuota()
            throws Exception {
        Member heavy = member(StaffRole.ADMINISTRATOR.code());
        Member other = sameInstitution(heavy.institution(), StaffRole.ADMINISTRATOR.code());
        String ana = guardian(heavy, "Ana", "Lopez", null, null);
        String student = student(heavy, "Luis", "Perez");
        try (ConfigurableApplicationContext limited = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(),
                "confia_admin_app", TEST_PASSWORD, keys, Map.of("confia.web.trusted-proxies", "127.0.0.1,::1",
                        "confia.crypto.blind-index.current-version", "v1",
                        "confia.crypto.blind-index.keys.v1", BLIND_INDEX_KEY,
                        RATE_LIMIT + "admin-read.request-limit", "10000",
                        RATE_LIMIT + "admin-write.request-limit", "10000",
                        RATE_LIMIT + "admin-read-account.request-limit", "2",
                        RATE_LIMIT + "admin-write-account.request-limit", "1"))) {
            int limitedPort = ((WebServerApplicationContext) limited).getWebServer().getPort();
            String first = "203.0.113.40";
            String second = "203.0.113.41";

            assertThat(sendFrom(limitedPort, first, "PUT", STUDENTS + "/" + student + "/guardians", heavy.token(),
                    set(link(ana, "MOTHER", "100", false, false))).statusCode()).isEqualTo(200);
            HttpResponse<String> refused = sendFrom(limitedPort, second, "PUT", GUARDIANS + "/" + ana, heavy.token(),
                    updateBody("Ana", "Lopez", "INACTIVE"));
            assertProblem(refused, 429, "too-many-requests");
            assertThat(refused.headers().firstValue("Retry-After")).hasValueSatisfying(seconds ->
                    assertThat(Long.parseLong(seconds)).isBetween(1L, 60L));
            assertThat(sendFrom(limitedPort, first, "PUT", GUARDIANS + "/" + ana, other.token(),
                    updateBody("Ana", "Lopez", "ACTIVE")).statusCode()).as("another account, same address")
                    .isEqualTo(200);

            assertThat(sendFrom(limitedPort, first, "GET", GUARDIANS, heavy.token(), null).statusCode()).isEqualTo(200);
            assertThat(sendFrom(limitedPort, second, "GET", GUARDIANS + "/" + ana, heavy.token(), null).statusCode())
                    .isEqualTo(200);
            assertProblem(sendFrom(limitedPort, first, "GET", STUDENTS + "/" + student + "/guardians", heavy.token(),
                    null), 429, "too-many-requests");
            assertThat(sendFrom(limitedPort, second, "GET", GUARDIANS, other.token(), null).statusCode()).isEqualTo(200);
            assertThat(sendFrom(limitedPort, first, "GET", GUARDIANS, "not-a-token", null).statusCode()).isEqualTo(401);
        }
    }

    // --- Logs ---

    @Test
    void theRequestsOfTheFlowsLogNoNameNoDocumentNoRtnNoContactDataAndNoAddress() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        PERSONAL_WORDS.addAll(List.of("Marcador-Nombre", "Marcador-Apellido", "marcador@example.org", "70707070",
                "Marcador-Direccion", "Marcador-Alumno"));
        String ana = guardian(admin, "Marcador-Nombre", "Marcador-Apellido", ID, RTN, "marcador@example.org",
                "70707070", "Marcador-Direccion");
        String student = student(admin, "Marcador-Alumno", "Marcador-Alumno");
        String path = STUDENTS + "/" + student + "/guardians";

        put(path, admin, set(link(ana, "MOTHER", "100", true, true)));
        put(path, admin, set(link(ana, "MOTHER", "60", true, true)));
        put(path, admin, "{\"links\":[{\"guardianId\":\"x\"}]}");
        post(GUARDIANS, admin, createBody("Marcador-Nombre", "Marcador-Apellido", ID));
        post(GUARDIANS, admin, "{\"firstName\":\"Marcador-Nombre\",\"lastName\":\"Marcador-Apellido\","
                + "\"email\":\"marcador@example.org\",\"rtn\":\"9999-9999\"}");
        put(GUARDIANS + "/" + ana, admin, updateBody("Marcador-Nombre", "Marcador-Apellido", "INACTIVE", ID));
        get(GUARDIANS + "?q=marcador", admin);
        get(GUARDIANS + "/" + ana, admin);
        get(path, admin);

        assertThat(logs.list).as("the flow did log something, so the scan is not vacuous").isNotEmpty();
    }

    // --- Bodies ---

    private static String link(String guardianId, String relationship, String percent, boolean primary,
            boolean billing) {
        return "{\"guardianId\":\"" + guardianId + "\",\"relationship\":\"" + relationship
                + "\",\"financialResponsibilityPercent\":" + percent + ",\"isPrimaryPayer\":" + primary
                + ",\"isBillingRecipient\":" + billing + "}";
    }

    private static String set(String... links) {
        return "{\"links\":[" + String.join(",", links) + "]}";
    }

    private static String createBody(String first, String last, String nationalId) {
        StringBuilder json = new StringBuilder("{\"firstName\":\"").append(first).append("\",\"lastName\":\"")
                .append(last).append('"');
        if (nationalId != null) {
            json.append(",\"nationalId\":\"").append(nationalId).append('"');
        }
        return json.append('}').toString();
    }

    private static String updateBody(String first, String last, String status) {
        return "{\"firstName\":\"" + first + "\",\"lastName\":\"" + last + "\",\"status\":\"" + status + "\"}";
    }

    private static String updateBody(String first, String last, String status, String nationalId) {
        return "{\"firstName\":\"" + first + "\",\"lastName\":\"" + last + "\",\"status\":\"" + status
                + "\",\"nationalId\":\"" + nationalId + "\"}";
    }

    private static JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private static void assertSplitRefused(HttpResponse<String> response, String reason) {
        JsonNode problem = assertProblem(response, 400, "invalid-responsibility-split");
        assertThat(problem.get("errors")).hasSize(1);
        assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("links");
        assertThat(problem.get("errors").get(0).get("reason").asString()).isEqualTo(reason);
        assertThat(response.body()).doesNotContain("33.33").doesNotContain("49.99").doesNotContain("50.01");
    }

    private static List<String> percents(JsonNode view) {
        List<String> percents = new ArrayList<>();
        view.get("links").forEach(link -> percents.add(link.get("financialResponsibilityPercent").toString()));
        return percents;
    }

    private static List<String> lastNames(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        json(response).get("items").forEach(item -> names.add(item.get("lastName").asString()));
        return names;
    }

    /** Numbers keep their scale (100.00 stays 100.00): the server writes percentages with two decimals. */
    private static final tools.jackson.databind.json.JsonMapper EXACT = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private static JsonNode json(HttpResponse<String> response) {
        return json(response.body());
    }

    private static JsonNode json(String document) {
        return EXACT.readTree(document);
    }

    /** Registers a guardian through the API (the real path) and returns its identifier. */
    private String guardian(Member member, String first, String last, String nationalId, String rtn) {
        return guardian(member, first, last, nationalId, rtn, null, null, null);
    }

    private String guardian(Member member, String first, String last, String nationalId, String rtn, String email,
            String phone, String address) {
        StringBuilder json = new StringBuilder("{\"firstName\":\"").append(first).append("\",\"lastName\":\"")
                .append(last).append('"');
        if (nationalId != null) {
            json.append(",\"nationalId\":\"").append(nationalId).append('"');
        }
        if (rtn != null) {
            json.append(",\"rtn\":\"").append(rtn).append('"');
        }
        if (email != null) {
            json.append(",\"email\":\"").append(email).append('"');
        }
        if (phone != null) {
            json.append(",\"phone\":\"").append(phone).append('"');
        }
        if (address != null) {
            json.append(",\"address\":\"").append(address).append('"');
        }
        HttpResponse<String> response = post(GUARDIANS, member, json.append('}').toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).get("id").asString();
    }

    /** Registers a student through the API (the real path) and returns its identifier. */
    private String student(Member member, String first, String last) {
        HttpResponse<String> response = post(STUDENTS, member, "{\"firstName\":\"" + first + "\",\"lastName\":\""
                + last + "\",\"birthDate\":\"2011-11-11\",\"sex\":\"F\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).get("id").asString();
    }

    private String code(Member member, String guardianId) {
        return json(get(GUARDIANS + "/" + guardianId, member)).get("guardianCode").asString();
    }

    private List<String> guardianIdsOf(Member member) {
        List<String> ids = new ArrayList<>();
        json(get(GUARDIANS, member)).get("items").forEach(item -> ids.add(item.get("id").asString()));
        return ids;
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

    private static long countOf(String table, UUID institution) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from " + table + " where institution_id = ?")) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    /** Every link of the student, closed ones included, oldest first, with the code of its guardian. */
    private static List<LinkRow> linkRows(String studentId) throws SQLException {
        List<LinkRow> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select g.guardian_code, l.financial_responsibility_percent::text, l.relationship,
                               l.is_primary_payer, l.is_billing_recipient, l.valid_from, l.valid_to
                          from guardians_guardian_student l
                          join guardians_guardian g on g.institution_id = l.institution_id and g.id = l.guardian_id
                         where l.student_id = ?
                         order by l.created_at, l.id
                        """)) {
            statement.setObject(1, UUID.fromString(studentId));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new LinkRow(result.getString(1), result.getString(2), result.getString(3),
                            result.getBoolean(4), result.getBoolean(5), result.getObject(6, LocalDate.class),
                            result.getObject(7, LocalDate.class)));
                }
            }
        }
        return rows;
    }

    /** Whether the guardian row holds a document, its blind index and an RTN, in that order. */
    private static List<Boolean> documentColumns(String guardianId) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select national_id_encrypted is not null, national_id_blind_index is not null,
                               rtn_encrypted is not null from guardians_guardian where id = ?
                        """)) {
            statement.setObject(1, UUID.fromString(guardianId));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return List.of(result.getBoolean(1), result.getBoolean(2), result.getBoolean(3));
            }
        }
    }

    private static List<String> storedCiphertexts() throws SQLException {
        List<String> values = new ArrayList<>();
        for (String sql : List.of(
                "select national_id_encrypted, national_id_blind_index, rtn_encrypted from guardians_guardian",
                "select national_id_encrypted, national_id_blind_index, null from students_student")) {
            try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                    PreparedStatement statement = connection.prepareStatement(sql);
                    ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    for (int column = 1; column <= 3; column++) {
                        if (result.getString(column) != null) {
                            values.add(result.getString(column));
                        }
                    }
                }
            }
        }
        return values;
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

    /** action, entity type, entity id, before, after of the entries of the guardians, oldest first. */
    private static List<String[]> guardianEntries(UUID institution, String action) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select action, entity_type, entity_id, before_value::text, after_value::text
                          from shared_audit_log
                         where institution_id = ? and action like 'guardians.%' and (?::text is null or action = ?)
                         order by id
                        """)) {
            statement.setObject(1, institution);
            statement.setString(2, action);
            statement.setString(3, action);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new String[] {result.getString(1), result.getString(2), result.getString(3),
                            result.getString(4), result.getString(5)});
                }
            }
        }
        return rows;
    }

    private static List<String> actorsOfGuardianEntries(UUID institution) throws SQLException {
        List<String> actors = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select actor_id::text from shared_audit_log
                         where institution_id = ? and action like 'guardians.%'
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

    @SafeVarargs
    private static List<HttpResponse<String>> inParallel(Callable<HttpResponse<String>>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch ready = new CountDownLatch(calls.length);
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
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            RESPONSES.add(response.body());
            RESPONSES.add(response.headers().map().toString());
            return response;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

package com.confia.students.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
 * The six routes of the enrollments on the real administrative process, started through the production
 * launcher against a real PostgreSQL with the least-privilege role of the application
 * (docs/cortes/enrollments.md). Every token is made by the production issuer; the accounts, the roles, the
 * institution and the academic structure are seeded as the owner of the schema; every test has an institution
 * of its own, so no test sees the rows of another.
 *
 * <p>It covers the acceptance criterion (an administrator enrolls a student, moves it to another section of
 * the same year and withdraws it; the history shows it and the seat is freed), each rule and edge case of the
 * cut with the code it answers, the races the locks and the unique index settle (two enrollments for the last
 * seat, two for the same student, two simultaneous exits, two transfers for the last seat), the immutable
 * history, the isolation between institutions, the role without the permission (a {@code 403} that leaves no
 * row and no entry), the rate limits per address and per account, the audit entries, and a sweep of every
 * response, audit row and log line against the document of a student, the reasons typed by the staff and the
 * names.
 */
class EnrollmentEndpointsIT extends CommittingPostgresIntegrationTest {

    private static final String ENROLLMENTS = "/api/v1/enrollments";
    private static final String STUDENTS = "/api/v1/students";
    private static final String ID = "0801-2012-01234";
    private static final String ID_NORMALIZED = "0801201201234";
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
    /** Names and dates of the students: they travel in some responses and never in the audit trail nor a log. */
    private static final List<String> PERSONAL_WORDS = Collections.synchronizedList(new ArrayList<>());
    /** Free text typed by the staff: it travels in the answer of the write and never in the audit trail nor a log. */
    private static final List<String> REASON_WORDS = Collections.synchronizedList(new ArrayList<>());

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
        REASON_WORDS.clear();
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
     * Whatever a test did, no response, audit row or log line carries the document of a student in any
     * spelling, the ciphertext, the key of the blind index; no audit row or log line carries a name, a birth
     * date or a reason typed by the staff.
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
        List<String> audit = enrollmentAuditRows();
        List<String> forbiddenEverywhere = new ArrayList<>(List.of(ID, ID_NORMALIZED, BLIND_INDEX_KEY));
        forbiddenEverywhere.addAll(storedCiphertexts());
        for (String forbidden : forbiddenEverywhere) {
            assertThat(RESPONSES).as("responses must not contain %s", shorten(forbidden))
                    .noneMatch(text -> text.contains(forbidden));
            assertThat(audit).as("audit rows must not contain %s", shorten(forbidden))
                    .noneMatch(text -> text.contains(forbidden));
            assertThat(logLines).as("logs must not contain %s", shorten(forbidden))
                    .noneMatch(text -> text.contains(forbidden));
        }
        for (String word : concat(PERSONAL_WORDS, REASON_WORDS)) {
            assertThat(audit).as("audit rows must not contain %s", shorten(word))
                    .noneMatch(text -> text.contains(word));
            assertThat(logLines).as("logs must not contain %s", shorten(word))
                    .noneMatch(text -> text.contains(word));
        }
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
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

    /**
     * The academic structure of one institution: an ACTIVE year with the sections A (two seats, grade 7), B (no
     * limit, grade 7), ONE (one seat, grade 8) and INACTIVE; a PLANNED year that started ten days ago and ends
     * today, with one section; and a CLOSED year with one section.
     */
    private record Org(UUID year, UUID shortYear, UUID closedYear, UUID grade7, UUID grade8, UUID sectionA,
            UUID sectionB, UUID sectionOne, UUID sectionInactive, UUID shortSection, UUID closedSection) {
    }

    private static final LocalDate YEAR_START = TODAY.minusDays(180);
    private static final LocalDate YEAR_END = TODAY.plusDays(150);

    // --- The acceptance criterion ---

    @Test
    void anAdministratorEnrollsAStudentMovesItToAnotherSectionOfTheYearAndWithdrawsItTheHistoryShowsItAndTheSeatIsFreed()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        PERSONAL_WORDS.addAll(List.of("Xiomara-Pseudonimo", "Zelaya-Quintanilla", "2011-11-11"));
        REASON_WORDS.addAll(List.of("Cambio-de-turno-privado", "Se-muda-privado"));
        String ana = student(admin, "Xiomara-Pseudonimo", "Zelaya-Quintanilla", ID);
        String luis = student(admin, "Luis", "Lopez", null);
        enroll(admin, luis, org.sectionOne(), TODAY.minusDays(5));
        assertProblem(enrollRequest(admin, ana, org.sectionOne(), TODAY.minusDays(5)), 409, "section-full");

        JsonNode freed = HarnessProcess.json(post(ENROLLMENTS + "/" + enroll(admin, ana, org.sectionA(), TODAY.minusDays(5))
                .get("id").asString() + "/transfer", admin, transferBody(org.sectionB(), TODAY.minusDays(2),
                        "Cambio-de-turno-privado")));
        String id = freed.get("id").asString();

        assertThat(freed.get("sectionName").asString()).isEqualTo("B");
        assertThat(freed.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(freed.get("transfers")).hasSize(1);

        JsonNode withdrawn = HarnessProcess.json(post(ENROLLMENTS + "/" + id + "/exit", admin,
                exitBody("WITHDRAWN", TODAY.minusDays(1), "Se-muda-privado")));
        assertThat(withdrawn.get("status").asString()).isEqualTo("WITHDRAWN");

        JsonNode detail = HarnessProcess.json(get(ENROLLMENTS + "/" + id, admin));
        assertThat(detail.get("transfers")).hasSize(1);
        JsonNode entry = detail.get("transfers").get(0);
        assertThat(entry.get("fromSectionName").asString()).isEqualTo("A");
        assertThat(entry.get("toSectionName").asString()).isEqualTo("B");
        assertThat(entry.get("transferredOn").asString()).isEqualTo(TODAY.minusDays(2).toString());
        assertThat(entry.get("reason").asString()).isEqualTo("Cambio-de-turno-privado");
        assertThat(entry.get("transferredBy").asString()).isEqualTo(admin.account().toString());
        assertThat(detail.get("withdrawnOn").asString()).isEqualTo(TODAY.minusDays(1).toString());
        assertThat(detail.get("withdrawalReason").asString()).isEqualTo("Se-muda-privado");

        // The seat of the section with one seat was Luis's; Ana's section is free again: a third student fits in A.
        String rosa = student(admin, "Rosa", "Diaz", null);
        assertThat(enroll(admin, rosa, org.sectionA(), TODAY.minusDays(1)).get("status").asString())
                .isEqualTo("ACTIVE");
        assertThat(activeCount(org.sectionA())).isEqualTo(1);
        assertThat(activeCount(org.sectionB())).isZero();
    }

    @Test
    void theSeatOfASectionIsFreedByATransferOutAndByAnExit() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String first = student(admin, "Ana", "Perez", null);
        String second = student(admin, "Luis", "Lopez", null);
        String third = student(admin, "Eva", "Mejia", null);
        String fourth = student(admin, "Rosa", "Diaz", null);
        String a = enroll(admin, first, org.sectionA(), TODAY.minusDays(3)).get("id").asString();
        String b = enroll(admin, second, org.sectionA(), TODAY.minusDays(3)).get("id").asString();
        assertProblem(enrollRequest(admin, third, org.sectionA(), TODAY), 409, "section-full");

        assertThat(post(ENROLLMENTS + "/" + a + "/transfer", admin, transferBody(org.sectionB(), TODAY, null))
                .statusCode()).isEqualTo(200);
        assertThat(enroll(admin, third, org.sectionA(), TODAY).get("status").asString()).isEqualTo("ACTIVE");
        assertProblem(enrollRequest(admin, fourth, org.sectionA(), TODAY), 409, "section-full");
        assertThat(post(ENROLLMENTS + "/" + b + "/exit", admin, exitBody("GRADUATED", TODAY, null)).statusCode())
                .isEqualTo(200);
        assertThat(enroll(admin, fourth, org.sectionA(), TODAY).get("status").asString()).isEqualTo("ACTIVE");
    }

    // --- The shape of the answers ---

    @Test
    void theResponsesCarryNoStoreAndTheExactMembersAndNeverTheInstitutionOrTheDocument() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String studentId = student(admin, "Ana", "Perez", ID);

        HttpResponse<String> response = enrollRequest(admin, studentId, org.sectionA(), TODAY.minusDays(1));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(response.body()).doesNotContain(admin.institution().toString())
                .doesNotContain(admin.account().toString()).doesNotContain("2011-11-11");
        JsonNode enrollment = HarnessProcess.json(response);
        assertThat(enrollment.propertyNames()).containsExactly("id", "studentId", "studentCode",
                "studentFirstName", "studentLastName", "academicYearId", "academicYearName", "sectionId",
                "sectionName", "gradeId", "gradeName", "modalityName", "enrolledOn", "status", "withdrawnOn",
                "withdrawalReason", "createdAt", "updatedAt", "transfers");
        assertThat(enrollment.get("withdrawnOn").isNull()).isTrue();
        assertThat(enrollment.get("transfers")).isEmpty();
        assertThat(enrollment.get("academicYearId").asString()).isEqualTo(org.year().toString());
        assertThat(enrollment.get("academicYearName").asString()).isEqualTo("2026-ACTIVE");
        assertThat(enrollment.get("gradeId").asString()).isEqualTo(org.grade7().toString());
        assertThat(enrollment.get("gradeName").asString()).isEqualTo("7mo");
        assertThat(enrollment.get("modalityName").asString()).isEqualTo("Basica");
        assertThat(enrollment.get("studentFirstName").asString()).isEqualTo("Ana");

        JsonNode page = HarnessProcess.json(get(ENROLLMENTS, admin));
        assertThat(page.propertyNames()).containsExactly("items", "total", "page", "size");
        JsonNode item = page.get("items").get(0);
        assertThat(item.has("transfers")).as("a list does not carry the history").isFalse();
        assertThat(item.propertyNames()).doesNotContain("transfers");
        JsonNode ofStudent = HarnessProcess.json(get(STUDENTS + "/" + studentId + "/enrollments", admin));
        assertThat(ofStudent.propertyNames()).containsExactly("items");
        assertThat(ofStudent.get("items")).hasSize(1);
    }

    // --- Enrolling: each rule and edge case ---

    @Test
    void aSecondActiveEnrollmentInTheYearIsRefusedAndAllowedAfterAWithdrawalAndInAnotherYear() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String ana = student(admin, "Ana", "Perez", null);
        String first = enroll(admin, ana, org.sectionA(), TODAY.minusDays(3)).get("id").asString();

        assertProblem(enrollRequest(admin, ana, org.sectionA(), TODAY.minusDays(3)), 409, "duplicate-active-enrollment");
        assertProblem(enrollRequest(admin, ana, org.sectionB(), TODAY.minusDays(3)), 409, "duplicate-active-enrollment");
        assertThat(countOf("students_enrollment", admin.institution())).isEqualTo(1);

        post(ENROLLMENTS + "/" + first + "/exit", admin, exitBody("WITHDRAWN", TODAY.minusDays(2), "Cambio de ciudad"));
        assertThat(enroll(admin, ana, org.sectionB(), TODAY.minusDays(1)).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(enroll(admin, ana, org.shortSection(), TODAY).get("status").asString())
                .as("another academic year is another count").isEqualTo("ACTIVE");
        assertThat(HarnessProcess.json(get(STUDENTS + "/" + ana + "/enrollments", admin)).get("items")).hasSize(3);
    }

    @Test
    void anInactiveStudentAnInactiveSectionAndAClosedYearAreRefusedWithTheirCodes() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String ana = student(admin, "Ana", "Perez", null);
        String inactive = student(admin, "Luis", "Lopez", null);
        put(STUDENTS + "/" + inactive, admin, "{\"firstName\":\"Luis\",\"lastName\":\"Lopez\",\"birthDate\":"
                + "\"2011-11-11\",\"sex\":\"M\",\"status\":\"INACTIVE\"}");

        assertProblem(enrollRequest(admin, inactive, org.sectionA(), TODAY), 409, "student-not-active");
        assertProblem(enrollRequest(admin, ana, org.sectionInactive(), TODAY), 409, "inactive-reference");
        assertProblem(enrollRequest(admin, ana, org.closedSection(), TODAY.minusDays(350)), 409, "academic-year-closed");
        assertThat(countOf("students_enrollment", admin.institution())).isZero();
        assertThat(enrollmentEntries(admin.institution())).isEmpty();
    }

    @Test
    void theEnrollmentDateMustBeInsideTheYearIncludingBothEndsAndNotInTheFutureAndNamesItsField() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String dayBefore = student(admin, "Ana", "Perez", null);
        String firstDay = student(admin, "Luis", "Lopez", null);
        String lastDay = student(admin, "Eva", "Mejia", null);
        String future = student(admin, "Rosa", "Diaz", null);

        // The short year starts ten days ago and ends today: its first day, its last day, a day before, a day after.
        for (String[] bad : new String[][] {{dayBefore, TODAY.minusDays(11).toString()},
                {future, TODAY.plusDays(1).toString()}}) {
            JsonNode problem = assertProblem(post(ENROLLMENTS, admin,
                    "{\"studentId\":\"" + bad[0] + "\",\"sectionId\":\"" + org.shortSection() + "\",\"enrolledOn\":\""
                            + bad[1] + "\"}"), 400, "validation-failed");
            assertThat(problem.get("errors")).hasSize(1);
            assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("enrolledOn");
            assertThat(problem.get("errors").get(0).get("reason").asString()).isEqualTo("enrollment-date-out-of-range");
        }
        assertThat(enroll(admin, firstDay, org.shortSection(), TODAY.minusDays(10)).get("enrolledOn").asString())
                .isEqualTo(TODAY.minusDays(10).toString());
        assertThat(enroll(admin, lastDay, org.shortSection(), TODAY).get("enrolledOn").asString())
                .isEqualTo(TODAY.toString());
        assertThat(countOf("students_enrollment", admin.institution())).isEqualTo(2);
    }

    @Test
    void theBodyIsValidatedAtTheEdgeAndAnUnknownMemberLikeTheYearOrTheInstitutionIsRefused() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String ana = student(admin, "Ana", "Perez", null);
        String valid = enrollBody(ana, org.sectionA(), TODAY);

        assertProblem(post(ENROLLMENTS, admin, "{}"), 400, "validation-failed");
        assertProblem(post(ENROLLMENTS, admin, "{\"studentId\":\"" + ana + "\",\"enrolledOn\":\"" + TODAY + "\"}"),
                400, "validation-failed");
        assertProblem(post(ENROLLMENTS, admin, "{\"studentId\":\"nope\",\"sectionId\":\"" + org.sectionA()
                + "\",\"enrolledOn\":\"" + TODAY + "\"}"), 400, "validation-failed");
        assertProblem(post(ENROLLMENTS, admin, "{\"studentId\":\"" + ana + "\",\"sectionId\":\"" + org.sectionA()
                + "\",\"enrolledOn\":\"31/12/2026\"}"), 400, "validation-failed");
        assertProblem(post(ENROLLMENTS, admin, valid.replace("}", ",\"academicYearId\":\"" + org.year() + "\"}")),
                400, "validation-failed");
        assertProblem(post(ENROLLMENTS, admin, valid.replace("}", ",\"institutionId\":\"" + admin.institution()
                + "\"}")), 400, "validation-failed");
        assertThat(countOf("students_enrollment", admin.institution())).isZero();
        assertProblem(post(ENROLLMENTS, admin, enrollBody(UUID.randomUUID().toString(), org.sectionA(), TODAY)), 404,
                "resource-not-found");
        assertProblem(post(ENROLLMENTS, admin, enrollBody(ana, UUID.randomUUID(), TODAY)), 404, "resource-not-found");
    }

    @Test
    void theCapacityIsExactAndASectionWithoutCapacityHasNoLimit() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        List<String> students = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            students.add(student(admin, "Nombre" + i, "Apellido" + i, null));
        }

        enroll(admin, students.get(0), org.sectionA(), TODAY);
        enroll(admin, students.get(1), org.sectionA(), TODAY);
        assertProblem(enrollRequest(admin, students.get(2), org.sectionA(), TODAY), 409, "section-full");
        for (String id : students.subList(2, 4)) {
            assertThat(enroll(admin, id, org.sectionB(), TODAY).get("status").asString()).isEqualTo("ACTIVE");
        }
        assertThat(activeCount(org.sectionA())).isEqualTo(2);
    }

    // --- The races ---

    @Test
    void twoSimultaneousEnrollmentsForTheLastSeatAdmitOneAndRefuseTheOtherAsFull() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        for (int round = 0; round < 4; round++) {
            UUID section = extraSection(admin.institution(), org, "R" + round, 1);
            String one = student(admin, "Uno" + round, "Carrera", null);
            String two = student(admin, "Dos" + round, "Carrera", null);

            List<HttpResponse<String>> answers = inParallel(
                    () -> enrollRequest(admin, one, section, TODAY),
                    () -> enrollRequest(admin, two, section, TODAY));

            assertThat(answers).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
            HttpResponse<String> refused = answers.get(0).statusCode() == 409 ? answers.get(0) : answers.get(1);
            assertProblem(refused, 409, "section-full");
            assertThat(activeCount(section)).as("round %d: the section is never over its capacity", round).isEqualTo(1);
        }
    }

    @Test
    void twoSimultaneousEnrollmentsOfTheSameStudentInTheSameYearAdmitOneAndRefuseTheOtherAsDuplicate()
            throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        for (int round = 0; round < 3; round++) {
            String ana = student(admin, "Ana" + round, "Doble", null);

            List<HttpResponse<String>> answers = inParallel(
                    () -> enrollRequest(admin, ana, org.sectionA(), TODAY),
                    () -> enrollRequest(admin, ana, org.sectionB(), TODAY));

            assertThat(answers).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
            HttpResponse<String> refused = answers.get(0).statusCode() == 409 ? answers.get(0) : answers.get(1);
            assertProblem(refused, 409, "duplicate-active-enrollment");
            assertThat(HarnessProcess.json(get(STUDENTS + "/" + ana + "/enrollments", admin)).get("items")).hasSize(1);
            // Free the seats of the section with capacity for the next round.
            String id = HarnessProcess.json(get(STUDENTS + "/" + ana + "/enrollments", admin)).get("items").get(0)
                    .get("id").asString();
            post(ENROLLMENTS + "/" + id + "/exit", admin, exitBody("GRADUATED", TODAY, null));
        }
    }

    @Test
    void twoSimultaneousExitsOfTheSameEnrollmentApplyOneAndRefuseTheOtherAsInvalidState() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        for (int round = 0; round < 3; round++) {
            String ana = student(admin, "Ana" + round, "Salida", null);
            String id = enroll(admin, ana, org.sectionB(), TODAY.minusDays(2)).get("id").asString();

            List<HttpResponse<String>> answers = inParallel(
                    () -> post(ENROLLMENTS + "/" + id + "/exit", admin, exitBody("WITHDRAWN", TODAY, "Primera salida")),
                    () -> post(ENROLLMENTS + "/" + id + "/exit", admin, exitBody("GRADUATED", TODAY, null)));

            assertThat(answers).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
            HttpResponse<String> refused = answers.get(0).statusCode() == 409 ? answers.get(0) : answers.get(1);
            assertProblem(refused, 409, "invalid-enrollment-state");
            long exits = enrollmentEntries(admin.institution()).stream()
                    .filter(row -> row[0].equals("students.enrollment.exited") && row[2].equals(id)).count();
            assertThat(exits).as("one exit entry for one exit").isEqualTo(1);
        }
    }

    @Test
    void twoSimultaneousTransfersForTheLastSeatMoveOneAndRefuseTheOtherAsFull() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        for (int round = 0; round < 3; round++) {
            UUID target = extraSection(admin.institution(), org, "T" + round, 1);
            String first = enroll(admin, student(admin, "Uno" + round, "Traslado", null), org.sectionB(), TODAY)
                    .get("id").asString();
            String second = enroll(admin, student(admin, "Dos" + round, "Traslado", null), org.sectionB(), TODAY)
                    .get("id").asString();

            List<HttpResponse<String>> answers = inParallel(
                    () -> post(ENROLLMENTS + "/" + first + "/transfer", admin, transferBody(target, TODAY, null)),
                    () -> post(ENROLLMENTS + "/" + second + "/transfer", admin, transferBody(target, TODAY, null)));

            assertThat(answers).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
            HttpResponse<String> refused = answers.get(0).statusCode() == 409 ? answers.get(0) : answers.get(1);
            assertProblem(refused, 409, "section-full");
            assertThat(activeCount(target)).isEqualTo(1);
            post(ENROLLMENTS + "/" + first + "/exit", admin, exitBody("GRADUATED", TODAY, null));
            post(ENROLLMENTS + "/" + second + "/exit", admin, exitBody("GRADUATED", TODAY, null));
        }
    }

    // --- Transfers between sections ---

    @Test
    void aTransferToTheSameSectionToAnotherYearToAFullOrInactiveSectionIsRefusedWithTheirCodesAndChangesNothing()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String ana = enroll(admin, student(admin, "Ana", "Perez", null), org.sectionB(), TODAY.minusDays(4))
                .get("id").asString();
        enroll(admin, student(admin, "Luis", "Lopez", null), org.sectionOne(), TODAY.minusDays(4));
        String path = ENROLLMENTS + "/" + ana + "/transfer";

        JsonNode same = assertProblem(post(path, admin, transferBody(org.sectionB(), TODAY, null)), 400,
                "validation-failed");
        assertThat(same.get("errors").get(0).get("field").asString()).isEqualTo("toSectionId");
        assertProblem(post(path, admin, transferBody(org.shortSection(), TODAY, null)), 409, "transfer-year-mismatch");
        assertProblem(post(path, admin, transferBody(org.sectionOne(), TODAY, null)), 409, "section-full");
        assertProblem(post(path, admin, transferBody(org.sectionInactive(), TODAY, null)), 409, "inactive-reference");
        assertProblem(post(path, admin, transferBody(UUID.randomUUID(), TODAY, null)), 404, "resource-not-found");
        assertProblem(post(ENROLLMENTS + "/" + UUID.randomUUID() + "/transfer", admin,
                transferBody(org.sectionA(), TODAY, null)), 404, "resource-not-found");
        JsonNode badDate = assertProblem(post(path, admin, transferBody(org.sectionA(), TODAY.minusDays(5), null)),
                400, "validation-failed");
        assertThat(badDate.get("errors").get(0).get("field").asString()).isEqualTo("transferredOn");
        assertThat(badDate.get("errors").get(0).get("reason").asString()).isEqualTo("enrollment-date-out-of-range");
        assertProblem(post(path, admin, transferBody(org.sectionA(), TODAY.plusDays(1), null)), 400, "validation-failed");

        JsonNode untouched = HarnessProcess.json(get(ENROLLMENTS + "/" + ana, admin));
        assertThat(untouched.get("sectionName").asString()).isEqualTo("B");
        assertThat(untouched.get("transfers")).isEmpty();
        assertThat(countOf("students_enrollment_transfer", admin.institution())).isZero();
    }

    @Test
    void theHistoryOnlyGrowsInOrderAndTheApplicationCannotEditOrDeleteIt() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String id = enroll(admin, student(admin, "Ana", "Perez", null), org.sectionA(), TODAY.minusDays(9))
                .get("id").asString();

        post(ENROLLMENTS + "/" + id + "/transfer", admin, transferBody(org.sectionB(), TODAY.minusDays(8), "Primero"));
        post(ENROLLMENTS + "/" + id + "/transfer", admin, transferBody(org.sectionA(), TODAY.minusDays(3), "Segundo"));
        JsonNode third = HarnessProcess.json(post(ENROLLMENTS + "/" + id + "/transfer", admin,
                transferBody(org.sectionB(), TODAY.minusDays(1), null)));

        List<String> reasons = new ArrayList<>();
        third.get("transfers").forEach(t -> reasons.add(t.get("reason").isNull() ? "-" : t.get("reason").asString()));
        assertThat(reasons).containsExactly("Primero", "Segundo", "-");
        assertThat(third.get("transfers").get(0).get("fromSectionName").asString()).isEqualTo("A");
        assertThat(third.get("transfers").get(2).get("toSectionName").asString()).isEqualTo("B");
        try (Connection app = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            app.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(app, admin.institution());
            for (String statement : List.of("update students_enrollment_transfer set reason = 'x'",
                    "delete from students_enrollment_transfer", "delete from students_enrollment")) {
                SQLException refusal = catchThrowableOfType(SQLException.class,
                        () -> app.createStatement().execute(statement));
                assertThat(refusal == null).as(statement).isFalse();
                assertThat(refusal.getSQLState()).isEqualTo("42501");
                app.rollback();
            }
        }
        assertThat(countOf("students_enrollment_transfer", admin.institution())).isEqualTo(3);
    }

    // --- Exits ---

    @Test
    void anEnrollmentIsWithdrawnTransferredToAnotherInstitutionOrGraduatedWithItsDateAndReason() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String a = enroll(admin, student(admin, "Ana", "Perez", null), org.sectionB(), TODAY.minusDays(9)).get("id").asString();
        String b = enroll(admin, student(admin, "Luis", "Lopez", null), org.sectionB(), TODAY.minusDays(9)).get("id").asString();
        String c = enroll(admin, student(admin, "Eva", "Mejia", null), org.sectionB(), TODAY.minusDays(9)).get("id").asString();

        JsonNode withdrawn = HarnessProcess.json(post(ENROLLMENTS + "/" + a + "/exit", admin,
                exitBody("WITHDRAWN", TODAY.minusDays(1), "Cambio de ciudad")));
        JsonNode transferred = HarnessProcess.json(post(ENROLLMENTS + "/" + b + "/exit", admin,
                exitBody("TRANSFERRED", TODAY, "Otra institucion")));
        JsonNode graduated = HarnessProcess.json(post(ENROLLMENTS + "/" + c + "/exit", admin,
                exitBody("GRADUATED", TODAY, null)));

        assertThat(withdrawn.get("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.get("withdrawnOn").asString()).isEqualTo(TODAY.minusDays(1).toString());
        assertThat(withdrawn.get("withdrawalReason").asString()).isEqualTo("Cambio de ciudad");
        assertThat(transferred.get("status").asString()).isEqualTo("TRANSFERRED");
        assertThat(graduated.get("status").asString()).isEqualTo("GRADUATED");
        assertThat(graduated.get("withdrawalReason").isNull()).isTrue();
        assertThat(HarnessProcess.json(get(ENROLLMENTS + "?status=WITHDRAWN", admin)).get("total").asLong()).isEqualTo(1);
        assertThat(HarnessProcess.json(get(ENROLLMENTS + "?status=ACTIVE", admin)).get("total").asLong()).isZero();
    }

    @Test
    void anExitWithoutReasonWithADateBeforeTheEnrollmentOrInTheFutureOrAnUnknownStatusIsRefusedOnItsField()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String id = enroll(admin, student(admin, "Ana", "Perez", null), org.sectionB(), TODAY.minusDays(5)).get("id")
                .asString();
        String path = ENROLLMENTS + "/" + id + "/exit";

        JsonNode noReason = assertProblem(post(path, admin, exitBody("WITHDRAWN", TODAY, null)), 400, "validation-failed");
        assertThat(noReason.get("errors").get(0).get("field").asString()).isEqualTo("withdrawalReason");
        assertProblem(post(path, admin, exitBody("TRANSFERRED", TODAY, "   ")), 400, "validation-failed");
        assertProblem(post(path, admin, exitBody("WITHDRAWN", TODAY, "ab")), 400, "validation-failed");
        JsonNode before = assertProblem(post(path, admin, exitBody("WITHDRAWN", TODAY.minusDays(6), "Motivo")), 400,
                "validation-failed");
        assertThat(before.get("errors").get(0).get("field").asString()).isEqualTo("withdrawnOn");
        assertThat(before.get("errors").get(0).get("reason").asString()).isEqualTo("enrollment-date-out-of-range");
        assertProblem(post(path, admin, exitBody("WITHDRAWN", TODAY.plusDays(1), "Motivo")), 400, "validation-failed");
        assertProblem(post(path, admin, exitBody("ACTIVE", TODAY, "Motivo")), 400, "validation-failed");
        assertProblem(post(path, admin, exitBody("DELETED", TODAY, "Motivo")), 400, "validation-failed");
        assertProblem(post(path, admin, "{\"status\":\"WITHDRAWN\",\"withdrawalReason\":\"Motivo\"}"), 400,
                "validation-failed");
        assertProblem(post(ENROLLMENTS + "/" + UUID.randomUUID() + "/exit", admin, exitBody("WITHDRAWN", TODAY, "Motivo")),
                404, "resource-not-found");
        assertThat(HarnessProcess.json(get(ENROLLMENTS + "/" + id, admin)).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(post(path, admin, exitBody("GRADUATED", TODAY.minusDays(5), null)).statusCode())
                .as("the same day as the enrollment is allowed").isEqualTo(200);
    }

    @Test
    void afterAnExitTheEnrollmentIsFinalSoANewExitOrTransferAnswersInvalidEnrollmentState() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String id = enroll(admin, student(admin, "Ana", "Perez", null), org.sectionB(), TODAY.minusDays(5)).get("id")
                .asString();
        post(ENROLLMENTS + "/" + id + "/exit", admin, exitBody("WITHDRAWN", TODAY, "Cambio de ciudad"));

        for (String status : List.of("WITHDRAWN", "TRANSFERRED", "GRADUATED")) {
            assertProblem(post(ENROLLMENTS + "/" + id + "/exit", admin, exitBody(status, TODAY, "Otro motivo")), 409,
                    "invalid-enrollment-state");
        }
        assertProblem(post(ENROLLMENTS + "/" + id + "/transfer", admin, transferBody(org.sectionA(), TODAY, null)),
                409, "invalid-enrollment-state");
        JsonNode untouched = HarnessProcess.json(get(ENROLLMENTS + "/" + id, admin));
        assertThat(untouched.get("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(untouched.get("withdrawalReason").asString()).isEqualTo("Cambio de ciudad");
    }

    @Test
    void aClosedYearAdmitsNoTransferAndNoExit() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String ana = student(admin, "Ana", "Perez", null);
        UUID otherClosed = seedSection(admin.institution(), org.closedYear(), org.grade7(), "Z2", null, true);
        UUID enrollment = seedEnrollment(admin.institution(), UUID.fromString(ana), org.closedSection(),
                org.closedYear(), TODAY.minusDays(350));

        assertProblem(post(ENROLLMENTS + "/" + enrollment + "/transfer", admin, transferBody(otherClosed, TODAY.minusDays(340), null)),
                409, "academic-year-closed");
        assertProblem(post(ENROLLMENTS + "/" + enrollment + "/exit", admin, exitBody("WITHDRAWN", TODAY.minusDays(340), "Motivo")),
                409, "academic-year-closed");
        assertThat(HarnessProcess.json(get(ENROLLMENTS + "/" + enrollment, admin)).get("status").asString())
                .isEqualTo("ACTIVE");
    }

    // --- Reading ---

    @Test
    void theListDefaultsToTheActiveYearAndFiltersByYearGradeSectionAndStatusAndSortsAndPagesOnTheServer()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String zelaya = student(admin, "Carlos", "Zelaya", null);
        String aguilar = student(admin, "Beatriz", "Aguilar", null);
        String mejia = student(admin, "Ana", "Mejia", null);
        String viajero = student(admin, "Luis", "Viajero", null);
        enroll(admin, zelaya, org.sectionA(), TODAY.minusDays(30));
        String aguilarId = enroll(admin, aguilar, org.sectionB(), TODAY.minusDays(20)).get("id").asString();
        enroll(admin, mejia, org.sectionOne(), TODAY.minusDays(10));
        enroll(admin, viajero, org.shortSection(), TODAY.minusDays(5));
        post(ENROLLMENTS + "/" + aguilarId + "/exit", admin, exitBody("GRADUATED", TODAY.minusDays(1), null));

        assertThat(lastNames(get(ENROLLMENTS, admin))).as("the ACTIVE year by default, by last name")
                .containsExactly("Aguilar", "Mejia", "Zelaya");
        assertThat(lastNames(get(ENROLLMENTS + "?academicYearId=" + org.shortYear(), admin))).containsExactly("Viajero");
        assertThat(lastNames(get(ENROLLMENTS + "?sort=studentLastName,desc", admin))).containsExactly("Zelaya", "Mejia",
                "Aguilar");
        assertThat(lastNames(get(ENROLLMENTS + "?sort=enrolledOn", admin))).containsExactly("Zelaya", "Aguilar", "Mejia");
        assertThat(lastNames(get(ENROLLMENTS + "?sort=enrolledOn,desc", admin))).containsExactly("Mejia", "Aguilar",
                "Zelaya");
        assertThat(lastNames(get(ENROLLMENTS + "?sort=status,desc", admin)).get(0)).isEqualTo("Aguilar");
        assertThat(lastNames(get(ENROLLMENTS + "?gradeId=" + org.grade7(), admin))).containsExactly("Aguilar", "Zelaya");
        assertThat(lastNames(get(ENROLLMENTS + "?gradeId=" + org.grade8(), admin))).containsExactly("Mejia");
        assertThat(lastNames(get(ENROLLMENTS + "?sectionId=" + org.sectionA(), admin))).containsExactly("Zelaya");
        assertThat(lastNames(get(ENROLLMENTS + "?sectionId=" + org.sectionA() + "&gradeId=" + org.grade8(), admin)))
                .as("a section outside the grade keeps nothing").isEmpty();
        assertThat(lastNames(get(ENROLLMENTS + "?status=GRADUATED", admin))).containsExactly("Aguilar");
        assertThat(lastNames(get(ENROLLMENTS + "?status=ACTIVE&gradeId=" + org.grade7(), admin))).containsExactly("Zelaya");
        assertThat(lastNames(get(ENROLLMENTS + "?gradeId=" + UUID.randomUUID(), admin))).isEmpty();

        JsonNode first = HarnessProcess.json(get(ENROLLMENTS + "?size=2&page=0", admin));
        JsonNode second = HarnessProcess.json(get(ENROLLMENTS + "?size=2&page=1", admin));
        assertThat(first.get("total").asLong()).isEqualTo(3);
        assertThat(first.get("items")).hasSize(2);
        assertThat(second.get("items")).hasSize(1);
        assertThat(HarnessProcess.json(get(ENROLLMENTS, admin)).get("size").asInt()).isEqualTo(50);
    }

    @Test
    void withoutAnActiveYearTheListCoversEveryYear() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        enroll(admin, student(admin, "Ana", "Perez", null), org.sectionA(), TODAY.minusDays(3));
        enroll(admin, student(admin, "Luis", "Lopez", null), org.shortSection(), TODAY.minusDays(3));
        setYearStatus(admin.institution(), org.year(), "PLANNED");

        assertThat(HarnessProcess.json(get(ENROLLMENTS, admin)).get("total").asLong()).isEqualTo(2);
    }

    @Test
    void badListParametersAreRefusedAndTheSizeIsCappedByTheServer() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        for (String query : List.of("sort=password", "sort=birthDate", "sort=studentLastName,sideways", "sort=a,b,c",
                "size=0", "size=201", "page=-1", "status=FOO", "academicYearId=nope", "gradeId=nope",
                "sectionId=nope")) {
            assertProblem(get(ENROLLMENTS + "?" + query, admin), 400, "validation-failed");
        }
        assertThat(get(ENROLLMENTS + "?size=200", admin).statusCode()).isEqualTo(200);
    }

    @Test
    void theDetailHasTheHistoryAndAnUnknownOrMalformedIdentifierIsRefused() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertProblem(get(ENROLLMENTS + "/" + UUID.randomUUID(), admin), 404, "resource-not-found");
        assertProblem(get(ENROLLMENTS + "/not-a-uuid", admin), 400, "validation-failed");
        assertProblem(get(STUDENTS + "/" + UUID.randomUUID() + "/enrollments", admin), 404, "resource-not-found");
        assertProblem(get(STUDENTS + "/not-a-uuid/enrollments", admin), 400, "validation-failed");
    }

    // --- Isolation, roles, sessions ---

    @Test
    void anotherInstitutionSeesNothingAndCannotTouchTheEnrollmentsStudentsOrSectionsOfThisOne() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(mine.institution());
        Org theirOrg = org(theirs.institution());
        String myStudent = student(mine, "Ana", "Perez", null);
        String theirStudent = student(theirs, "Luis", "Lopez", null);
        String id = enroll(mine, myStudent, org.sectionB(), TODAY.minusDays(3)).get("id").asString();

        assertProblem(get(ENROLLMENTS + "/" + id, theirs), 404, "resource-not-found");
        assertProblem(get(STUDENTS + "/" + myStudent + "/enrollments", theirs), 404, "resource-not-found");
        assertProblem(post(ENROLLMENTS + "/" + id + "/transfer", theirs, transferBody(theirOrg.sectionA(), TODAY, null)),
                404, "resource-not-found");
        assertProblem(post(ENROLLMENTS + "/" + id + "/exit", theirs, exitBody("WITHDRAWN", TODAY, "Motivo")), 404,
                "resource-not-found");
        assertProblem(post(ENROLLMENTS, theirs, enrollBody(myStudent, theirOrg.sectionA(), TODAY)), 404,
                "resource-not-found");
        assertProblem(post(ENROLLMENTS, theirs, enrollBody(theirStudent, org.sectionA(), TODAY)), 404,
                "resource-not-found");
        assertProblem(post(ENROLLMENTS + "/" + id + "/transfer", mine, transferBody(theirOrg.sectionA(), TODAY, null)),
                404, "resource-not-found");
        assertThat(HarnessProcess.json(get(ENROLLMENTS, theirs)).get("total").asLong()).isZero();
        assertThat(HarnessProcess.json(get(ENROLLMENTS, mine)).get("total").asLong()).isEqualTo(1);
        assertThat(HarnessProcess.json(get(ENROLLMENTS + "/" + id, mine)).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(countOf("students_enrollment", theirs.institution())).isZero();
    }

    @Test
    void aCashierMayReadButCannotEnrollTransferOrExitAndTheRefusalLeavesNoRowAndNoEntry() throws SQLException {
        Member cashier = member(StaffRole.CASHIER.code());
        Member admin = sameInstitution(cashier.institution(), StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String ana = student(admin, "Ana", "Perez", null);
        String luis = student(admin, "Luis", "Lopez", null);
        String id = enroll(admin, ana, org.sectionA(), TODAY.minusDays(2)).get("id").asString();
        int entriesBefore = enrollmentEntries(cashier.institution()).size();
        long deniedBefore = deniedCount(cashier.institution());

        assertThat(get(ENROLLMENTS, cashier).statusCode()).isEqualTo(200);
        assertThat(get(ENROLLMENTS + "/" + id, cashier).statusCode()).isEqualTo(200);
        assertThat(get(STUDENTS + "/" + ana + "/enrollments", cashier).statusCode()).isEqualTo(200);
        assertProblem(post(ENROLLMENTS, cashier, enrollBody(luis, org.sectionA(), TODAY)), 403, "forbidden");
        assertProblem(post(ENROLLMENTS + "/" + id + "/transfer", cashier, transferBody(org.sectionB(), TODAY, null)),
                403, "forbidden");
        assertProblem(post(ENROLLMENTS + "/" + id + "/exit", cashier, exitBody("WITHDRAWN", TODAY, "Motivo")), 403,
                "forbidden");

        assertThat(countOf("students_enrollment", cashier.institution())).isEqualTo(1);
        JsonNode untouched = HarnessProcess.json(get(ENROLLMENTS + "/" + id, admin));
        assertThat(untouched.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(untouched.get("sectionName").asString()).isEqualTo("A");
        assertThat(enrollmentEntries(cashier.institution())).as("no enrollment entry for a refused request")
                .hasSize(entriesBefore);
        assertThat(deniedCount(cashier.institution()) - deniedBefore).isEqualTo(3);
    }

    @Test
    void anAccountWithoutARoleCannotEvenReadAndNoCredentialAnswers401() throws SQLException {
        Member nobody = member(null);

        assertProblem(get(ENROLLMENTS, nobody), 403, "forbidden");
        assertProblem(get(ENROLLMENTS + "/" + UUID.randomUUID(), nobody), 403, "forbidden");
        assertProblem(get(STUDENTS + "/" + UUID.randomUUID() + "/enrollments", nobody), 403, "forbidden");
        assertProblem(send("GET", ENROLLMENTS, null, null), 401, "authentication-required");
        assertProblem(send("POST", ENROLLMENTS + "/" + UUID.randomUUID() + "/exit", null, "{}"), 401,
                "authentication-required");
    }

    @Test
    void aClosedSessionOpensNoRouteOfTheEnrollments() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String ana = student(admin, "Ana", "Perez", null);
        String id = enroll(admin, ana, org.sectionA(), TODAY.minusDays(2)).get("id").asString();
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

        assertProblem(get(ENROLLMENTS, admin), 401, "token-invalid");
        assertProblem(get(ENROLLMENTS + "/" + id, admin), 401, "token-invalid");
        assertProblem(get(STUDENTS + "/" + ana + "/enrollments", admin), 401, "token-invalid");
        assertProblem(post(ENROLLMENTS, admin, enrollBody(ana, org.sectionB(), TODAY)), 401, "token-invalid");
        assertProblem(post(ENROLLMENTS + "/" + id + "/transfer", admin, transferBody(org.sectionB(), TODAY, null)), 401,
                "token-invalid");
        assertProblem(post(ENROLLMENTS + "/" + id + "/exit", admin, exitBody("WITHDRAWN", TODAY, "Motivo")), 401,
                "token-invalid");
        assertThat(countOf("students_enrollment", admin.institution())).isEqualTo(1);
    }

    @Test
    void aWriteWithoutAnAllowedOriginIsRefusedBeforeAnyEffect() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String ana = student(admin, "Ana", "Perez", null);

        HttpResponse<String> response = execute(HttpRequest.newBuilder(URI.create("http://localhost:" + port + ENROLLMENTS))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + admin.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(enrollBody(ana, org.sectionA(), TODAY))).build());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(countOf("students_enrollment", admin.institution())).isZero();
    }

    // --- Audit ---

    @Test
    void everyWriteLeavesOneEntryWithCodesStatesAndDatesAndNeverNamesBirthDatesOrReasons() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        PERSONAL_WORDS.addAll(List.of("Wenceslao-Marcador", "Apellido-Marcador", "2011-11-11"));
        REASON_WORDS.addAll(List.of("Motivo-Traslado-Privado", "Motivo-Retiro-Privado"));
        String ana = student(admin, "Wenceslao-Marcador", "Apellido-Marcador", null);
        String code = HarnessProcess.json(get(STUDENTS + "/" + ana, admin)).get("studentCode").asString();

        String id = enroll(admin, ana, org.sectionA(), TODAY.minusDays(6)).get("id").asString();
        post(ENROLLMENTS + "/" + id + "/transfer", admin, transferBody(org.sectionB(), TODAY.minusDays(4),
                "Motivo-Traslado-Privado"));
        post(ENROLLMENTS + "/" + id + "/exit", admin, exitBody("WITHDRAWN", TODAY.minusDays(2), "Motivo-Retiro-Privado"));
        get(ENROLLMENTS, admin);
        get(ENROLLMENTS + "/" + id, admin);
        get(STUDENTS + "/" + ana + "/enrollments", admin);

        List<String[]> entries = enrollmentEntries(admin.institution());
        assertThat(entries).extracting(row -> row[0]).containsExactly("students.enrollment.created",
                "students.enrollment.transferred", "students.enrollment.exited");
        assertThat(entries).extracting(row -> row[1]).containsOnly("students.enrollment");
        assertThat(entries).extracting(row -> row[2]).containsOnly(id);
        assertThat(entries.get(0)[3]).as("a creation has no before state").isNull();
        JsonNode created = json(entries.get(0)[4]);
        assertThat(created.propertyNames()).containsExactlyInAnyOrder("studentCode", "sectionId", "academicYearId",
                "enrolledOn", "status");
        assertThat(created.get("studentCode").asString()).isEqualTo(code);
        assertThat(created.get("sectionId").asString()).isEqualTo(org.sectionA().toString());
        assertThat(created.get("academicYearId").asString()).isEqualTo(org.year().toString());
        assertThat(created.get("enrolledOn").asString()).isEqualTo(TODAY.minusDays(6).toString());
        JsonNode transferredBefore = json(entries.get(1)[3]);
        JsonNode transferredAfter = json(entries.get(1)[4]);
        assertThat(transferredBefore.get("sectionId").asString()).isEqualTo(org.sectionA().toString());
        assertThat(transferredAfter.get("sectionId").asString()).isEqualTo(org.sectionB().toString());
        assertThat(transferredAfter.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(transferredAfter.get("transferredOn").asString()).isEqualTo(TODAY.minusDays(4).toString());
        assertThat(transferredAfter.get("hasReason").asBoolean()).isTrue();
        JsonNode exitedBefore = json(entries.get(2)[3]);
        JsonNode exitedAfter = json(entries.get(2)[4]);
        assertThat(exitedBefore.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(exitedAfter.get("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(exitedAfter.get("withdrawnOn").asString()).isEqualTo(TODAY.minusDays(2).toString());
        assertThat(exitedAfter.get("hasWithdrawalReason").asBoolean()).isTrue();
        for (String[] entry : entries) {
            assertThat(entry[3] + " " + entry[4]).doesNotContain("Wenceslao").doesNotContain("Marcador")
                    .doesNotContain("Privado").doesNotContain("2011-11-11");
        }
        assertThat(actorsOfEnrollmentEntries(admin.institution())).containsOnly(admin.account().toString());
    }

    @Test
    void aWriteThatRollsBackLeavesNeitherTheRowNorTheEntry() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String ana = student(admin, "Ana", "Perez", null);
        enroll(admin, ana, org.sectionA(), TODAY);
        int entries = enrollmentEntries(admin.institution()).size();

        assertProblem(enrollRequest(admin, ana, org.sectionB(), TODAY), 409, "duplicate-active-enrollment");
        assertProblem(enrollRequest(admin, student(admin, "Luis", "Lopez", null), org.sectionInactive(), TODAY), 409,
                "inactive-reference");

        assertThat(countOf("students_enrollment", admin.institution())).isEqualTo(1);
        assertThat(enrollmentEntries(admin.institution())).hasSize(entries);
    }

    // --- Rate limits ---

    @Test
    void theSixRoutesAreRateLimitedPerAddressWithRetryAfterAndAnotherAddressIsNotAffected() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        String ana = student(admin, "Ana", "Perez", null);
        String luis = student(admin, "Luis", "Lopez", null);
        String existing = enroll(admin, luis, org.sectionB(), TODAY.minusDays(2)).get("id").asString();
        try (ConfigurableApplicationContext limited = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(),
                "confia_admin_app", TEST_PASSWORD, keys, Map.of("confia.web.trusted-proxies", "127.0.0.1,::1",
                        RATE_LIMIT + "admin-read.request-limit", "2", RATE_LIMIT + "admin-write.request-limit", "1"))) {
            int limitedPort = ((WebServerApplicationContext) limited).getWebServer().getPort();
            String noisy = "203.0.113.10";
            String quiet = "203.0.113.11";

            // Writes: one is admitted for the address; the enrollment, the transfer and the exit share the quota.
            assertThat(sendFrom(limitedPort, noisy, "POST", ENROLLMENTS, admin.token(),
                    enrollBody(ana, org.sectionA(), TODAY)).statusCode()).isEqualTo(201);
            HttpResponse<String> refusedTransfer = sendFrom(limitedPort, noisy, "POST",
                    ENROLLMENTS + "/" + existing + "/transfer", admin.token(), transferBody(org.sectionA(), TODAY, null));
            assertProblem(refusedTransfer, 429, "too-many-requests");
            assertThat(refusedTransfer.headers().firstValue("Retry-After")).hasValueSatisfying(seconds ->
                    assertThat(Long.parseLong(seconds)).isBetween(1L, 60L));
            assertProblem(sendFrom(limitedPort, noisy, "POST", ENROLLMENTS + "/" + existing + "/exit", admin.token(),
                    exitBody("GRADUATED", TODAY, null)), 429, "too-many-requests");
            assertProblem(sendFrom(limitedPort, noisy, "POST", ENROLLMENTS, admin.token(),
                    enrollBody(luis, org.sectionA(), TODAY)), 429, "too-many-requests");

            // Reads: two are admitted for the address; the list, the detail and the route of the student share the quota.
            assertThat(sendFrom(limitedPort, noisy, "GET", ENROLLMENTS, admin.token(), null).statusCode()).isEqualTo(200);
            assertThat(sendFrom(limitedPort, noisy, "GET", ENROLLMENTS + "/" + existing, admin.token(), null)
                    .statusCode()).isEqualTo(200);
            HttpResponse<String> refusedRead = sendFrom(limitedPort, noisy, "GET", STUDENTS + "/" + luis + "/enrollments",
                    admin.token(), null);
            assertProblem(refusedRead, 429, "too-many-requests");
            assertThat(refusedRead.headers().firstValue("Retry-After")).isPresent();

            // Another address is not affected, and the refusals changed nothing.
            assertThat(sendFrom(limitedPort, quiet, "GET", ENROLLMENTS, admin.token(), null).statusCode()).isEqualTo(200);
            assertThat(sendFrom(limitedPort, quiet, "POST", ENROLLMENTS + "/" + existing + "/exit", admin.token(),
                    exitBody("GRADUATED", TODAY, null)).statusCode()).isEqualTo(200);
            assertThat(countOf("students_enrollment", admin.institution())).isEqualTo(2);
        }
    }

    @Test
    void theRoutesAreAlsoRateLimitedPerAccountSoAnotherAccountIsUnaffectedAndTwoAddressesOfOneAccountShareTheQuota()
            throws Exception {
        Member heavy = member(StaffRole.ADMINISTRATOR.code());
        Member other = sameInstitution(heavy.institution(), StaffRole.ADMINISTRATOR.code());
        Org org = org(heavy.institution());
        String ana = student(heavy, "Ana", "Perez", null);
        String luis = student(heavy, "Luis", "Lopez", null);
        String existing = enroll(heavy, luis, org.sectionB(), TODAY.minusDays(2)).get("id").asString();
        try (ConfigurableApplicationContext limited = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(),
                "confia_admin_app", TEST_PASSWORD, keys, Map.of("confia.web.trusted-proxies", "127.0.0.1,::1",
                        RATE_LIMIT + "admin-read.request-limit", "10000",
                        RATE_LIMIT + "admin-write.request-limit", "10000",
                        RATE_LIMIT + "admin-read-account.request-limit", "2",
                        RATE_LIMIT + "admin-write-account.request-limit", "1"))) {
            int limitedPort = ((WebServerApplicationContext) limited).getWebServer().getPort();
            String first = "203.0.113.20";
            String second = "203.0.113.21";

            assertThat(sendFrom(limitedPort, first, "POST", ENROLLMENTS, heavy.token(),
                    enrollBody(ana, org.sectionA(), TODAY)).statusCode()).isEqualTo(201);
            HttpResponse<String> refused = sendFrom(limitedPort, second, "POST", ENROLLMENTS + "/" + existing + "/exit",
                    heavy.token(), exitBody("GRADUATED", TODAY, null));
            assertProblem(refused, 429, "too-many-requests");
            assertThat(refused.headers().firstValue("Retry-After")).hasValueSatisfying(seconds ->
                    assertThat(Long.parseLong(seconds)).isBetween(1L, 60L));
            assertThat(sendFrom(limitedPort, first, "POST", ENROLLMENTS + "/" + existing + "/exit", other.token(),
                    exitBody("GRADUATED", TODAY, null)).statusCode()).as("another account, same address").isEqualTo(200);

            assertThat(sendFrom(limitedPort, first, "GET", ENROLLMENTS, heavy.token(), null).statusCode()).isEqualTo(200);
            assertThat(sendFrom(limitedPort, second, "GET", ENROLLMENTS + "/" + existing, heavy.token(), null)
                    .statusCode()).isEqualTo(200);
            assertProblem(sendFrom(limitedPort, first, "GET", STUDENTS + "/" + luis + "/enrollments", heavy.token(), null),
                    429, "too-many-requests");
            assertThat(sendFrom(limitedPort, second, "GET", ENROLLMENTS, other.token(), null).statusCode()).isEqualTo(200);
            assertThat(sendFrom(limitedPort, first, "GET", ENROLLMENTS, "not-a-token", null).statusCode()).isEqualTo(401);
        }
    }

    // --- Logs ---

    @Test
    void theRequestsOfTheFlowsLogNoNameNoBirthDateNoDocumentAndNoReason() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Org org = org(admin.institution());
        PERSONAL_WORDS.addAll(List.of("Wenceslao-Marcador", "Apellido-Marcador", "2011-11-11"));
        REASON_WORDS.addAll(List.of("Motivo-Marcador-Uno", "Motivo-Marcador-Dos"));
        String ana = student(admin, "Wenceslao-Marcador", "Apellido-Marcador", ID);

        String id = enroll(admin, ana, org.sectionA(), TODAY.minusDays(3)).get("id").asString();
        post(ENROLLMENTS + "/" + id + "/transfer", admin, transferBody(org.sectionB(), TODAY, "Motivo-Marcador-Uno"));
        post(ENROLLMENTS + "/" + id + "/transfer", admin, transferBody(org.sectionB(), TODAY, "Motivo-Marcador-Uno"));
        post(ENROLLMENTS + "/" + id + "/exit", admin, exitBody("WITHDRAWN", TODAY, "Motivo-Marcador-Dos"));
        post(ENROLLMENTS + "/" + id + "/exit", admin, exitBody("WITHDRAWN", TODAY, "Motivo-Marcador-Dos"));
        post(ENROLLMENTS, admin, "{\"studentId\":\"x\",\"sectionId\":\"y\",\"enrolledOn\":\"z\"}");
        get(ENROLLMENTS + "?q=marcador", admin);
        get(ENROLLMENTS + "/" + id, admin);
        get(STUDENTS + "/" + ana + "/enrollments", admin);

        assertThat(logs.list).as("the flow did log something, so the scan is not vacuous").isNotEmpty();
    }

    // --- Bodies ---

    private static String enrollBody(String studentId, UUID sectionId, LocalDate on) {
        return "{\"studentId\":\"" + studentId + "\",\"sectionId\":\"" + sectionId + "\",\"enrolledOn\":\"" + on + "\"}";
    }

    private static String transferBody(UUID toSectionId, LocalDate on, String reason) {
        StringBuilder json = new StringBuilder("{\"toSectionId\":\"").append(toSectionId)
                .append("\",\"transferredOn\":\"").append(on).append('"');
        if (reason != null) {
            json.append(",\"reason\":\"").append(reason).append('"');
        }
        return json.append('}').toString();
    }

    private static String exitBody(String status, LocalDate on, String reason) {
        StringBuilder json = new StringBuilder("{\"status\":\"").append(status).append("\",\"withdrawnOn\":\"")
                .append(on).append('"');
        if (reason != null) {
            json.append(",\"withdrawalReason\":\"").append(reason).append('"');
        }
        return json.append('}').toString();
    }

    private HttpResponse<String> enrollRequest(Member member, String studentId, UUID sectionId, LocalDate on) {
        return post(ENROLLMENTS, member, enrollBody(studentId, sectionId, on));
    }

    private JsonNode enroll(Member member, String studentId, UUID sectionId, LocalDate on) {
        HttpResponse<String> response = enrollRequest(member, studentId, sectionId, on);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return HarnessProcess.json(response);
    }

    /** Registers a student through the API (the real path) and returns its identifier. */
    private String student(Member member, String first, String last, String nationalId) {
        StringBuilder json = new StringBuilder("{\"firstName\":\"").append(first).append("\",\"lastName\":\"")
                .append(last).append("\",\"birthDate\":\"2011-11-11\",\"sex\":\"F\"");
        if (nationalId != null) {
            json.append(",\"nationalId\":\"").append(nationalId).append('"');
        }
        HttpResponse<String> response = post(STUDENTS, member, json.append('}').toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return HarnessProcess.json(response).get("id").asString();
    }

    private static List<String> lastNames(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        HarnessProcess.json(response).get("items").forEach(item -> names.add(item.get("studentLastName").asString()));
        return names;
    }

    private static JsonNode json(String document) {
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(document);
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

    private static void exec(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            s.executeUpdate();
        }
    }

    /** The academic structure of the institution, committed as the owner of the schema. */
    private static Org org(UUID institution) throws SQLException {
        UUID modality = UUID.randomUUID();
        UUID grade7 = UUID.randomUUID();
        UUID grade8 = UUID.randomUUID();
        UUID year = UUID.randomUUID();
        UUID shortYear = UUID.randomUUID();
        UUID closedYear = UUID.randomUUID();
        try (Connection c = StaffRoleFixtures.ownerTransaction(institution)) {
            exec(c, "insert into organization_modality (institution_id, id, code, name) values (?, ?, 'BAS', 'Basica')",
                    institution, modality);
            exec(c, "insert into organization_grade (institution_id, id, modality_id, name, ordinal) values (?, ?, ?, '7mo', 7)",
                    institution, grade7, modality);
            exec(c, "insert into organization_grade (institution_id, id, modality_id, name, ordinal) values (?, ?, ?, '8vo', 8)",
                    institution, grade8, modality);
            year(c, institution, year, "2026-ACTIVE", YEAR_START, YEAR_END, "ACTIVE");
            year(c, institution, shortYear, "2026-SHORT", TODAY.minusDays(10), TODAY, "PLANNED");
            year(c, institution, closedYear, "2025-CLOSED", TODAY.minusDays(400), TODAY.minusDays(300), "CLOSED");
            c.commit();
        }
        return new Org(year, shortYear, closedYear, grade7, grade8,
                seedSection(institution, year, grade7, "A", 2, true),
                seedSection(institution, year, grade7, "B", null, true),
                seedSection(institution, year, grade8, "ONE", 1, true),
                seedSection(institution, year, grade7, "INACTIVE", 5, false),
                seedSection(institution, shortYear, grade7, "S", 5, true),
                seedSection(institution, closedYear, grade7, "Z", 5, true));
    }

    private static void year(Connection c, UUID institution, UUID id, String name, LocalDate start, LocalDate end,
            String status) throws SQLException {
        exec(c, """
                insert into organization_academic_year (institution_id, id, name, start_date, end_date, status)
                values (?, ?, ?, ?, ?, ?)
                """, institution, id, name, start, end, status);
    }

    private static UUID seedSection(UUID institution, UUID year, UUID grade, String name, Integer capacity,
            boolean active) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = StaffRoleFixtures.ownerTransaction(institution)) {
            exec(c, """
                    insert into organization_section (institution_id, id, academic_year_id, grade_id, name, capacity,
                        is_active) values (?, ?, ?, ?, ?, ?, ?)
                    """, institution, id, year, grade, name, capacity, active);
            c.commit();
        }
        return id;
    }

    private static UUID extraSection(UUID institution, Org org, String name, Integer capacity) throws SQLException {
        return seedSection(institution, org.year(), org.grade7(), name, capacity, true);
    }

    private static UUID seedEnrollment(UUID institution, UUID student, UUID section, UUID year, LocalDate on)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = StaffRoleFixtures.ownerTransaction(institution)) {
            exec(c, """
                    insert into students_enrollment (institution_id, id, student_id, section_id, academic_year_id,
                        enrolled_on) values (?, ?, ?, ?, ?, ?)
                    """, institution, id, student, section, year, on);
            c.commit();
        }
        return id;
    }

    private static void setYearStatus(UUID institution, UUID year, String status) throws SQLException {
        try (Connection c = StaffRoleFixtures.ownerTransaction(institution)) {
            exec(c, "update organization_academic_year set status = ? where institution_id = ? and id = ?", status,
                    institution, year);
            c.commit();
        }
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

    private static long activeCount(UUID section) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from students_enrollment where section_id = ? and status = 'ACTIVE'")) {
            statement.setObject(1, section);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static List<String> storedCiphertexts() throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select national_id_encrypted, national_id_blind_index from students_student");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                for (int column = 1; column <= 2; column++) {
                    if (result.getString(column) != null) {
                        values.add(result.getString(column));
                    }
                }
            }
        }
        return values;
    }

    /** Every audit row of an enrollment or of its student, as text, columns included. */
    private static List<String> enrollmentAuditRows() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select a::text from shared_audit_log a where a.action like 'students.enrollment.%'");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }

    /** action, entity type, entity id, before, after of every entry of the enrollments, oldest first. */
    private static List<String[]> enrollmentEntries(UUID institution) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select action, entity_type, entity_id, before_value::text, after_value::text
                          from shared_audit_log
                         where institution_id = ? and action like 'students.enrollment.%' order by id
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

    private static List<String> actorsOfEnrollmentEntries(UUID institution) throws SQLException {
        List<String> actors = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select actor_id::text from shared_audit_log
                         where institution_id = ? and action like 'students.enrollment.%'
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

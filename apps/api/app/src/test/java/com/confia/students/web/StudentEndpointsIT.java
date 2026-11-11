package com.confia.students.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.confia.bootstrap.AdminProcessLauncher;
import com.confia.identity.domain.StaffRole;
import com.confia.kernel.AeadIntegrityException;
import com.confia.kernel.InstitutionId;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
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
import java.nio.charset.StandardCharsets;
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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
 * The four routes of the student registry on the real administrative process, started through the
 * production launcher against a real PostgreSQL with the least-privilege role of the application
 * (docs/cortes/students-registry.md). Every token is made by the production issuer; the accounts, the
 * roles and the institution rows are seeded as the owner of the schema; every test has an institution of
 * its own, so no test sees the rows of another.
 *
 * <p>It covers the acceptance criterion (an administrator registers a student with a document, finds the
 * student by last name, edits and deactivates it; the document is encrypted in the database and appears
 * in full nowhere in the API), each rule and edge case of the cut with the code it answers, the role
 * without the permission (a {@code 403} that leaves no row and no entry), the isolation between
 * institutions, the audit entries, and the races the counter lock and the unique indexes settle. Every
 * response, every audit row and every log line captured while a test runs is scanned for the full
 * document, its ciphertext and the names of the students.
 */
class StudentEndpointsIT extends CommittingPostgresIntegrationTest {

    private static final String STUDENTS = "/api/v1/students";
    private static final String ID = "0801-2012-01234";
    private static final String ID_NORMALIZED = "0801201201234";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static KeyPair keys;
    private static CompactJws jws;
    private static ConfigurableApplicationContext process;
    private static int port;

    private static final List<String> RESPONSES = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> SECRET_WORDS = Collections.synchronizedList(new ArrayList<>());

    private ListAppender<ILoggingEvent> logs;
    private Level rootLevelBefore;

    @BeforeAll
    static void startTheProcess() throws NoSuchAlgorithmException {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        jws = new CompactJws(SigningKeyRing.of(SigningKey.signing(
                AdminProcessLauncher.signingKid(), keys.getPublic(), keys.getPrivate())));
        process = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(),
                "confia_admin_app", TEST_PASSWORD, keys);
        port = ((WebServerApplicationContext) process).getWebServer().getPort();
    }

    @AfterAll
    static void stopTheProcess() {
        process.close();
    }

    @BeforeEach
    void captureLogs() {
        RESPONSES.clear();
        SECRET_WORDS.clear();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLevelBefore = root.getLevel();
        logs = new ListAppender<>();
        logs.start();
        root.setLevel(Level.TRACE);
        root.addAppender(logs);
    }

    /**
     * Whatever a test did, no response, audit row or log line carries the document in any spelling, the
     * ciphertext of a stored document or a word that the test marked as personal.
     */
    @AfterEach
    void nothingPersonalLeaksAnywhere() throws SQLException {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(logs);
        root.setLevel(rootLevelBefore);

        List<String> logLines = new ArrayList<>();
        for (ILoggingEvent event : new ArrayList<>(logs.list)) {
            logLines.add(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                logLines.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
            logLines.add(String.valueOf(event.getMDCPropertyMap()));
        }
        List<String> forbidden = new ArrayList<>(List.of(ID, ID_NORMALIZED, "AB-99998888", "AB99998888"));
        forbidden.addAll(storedCiphertexts());
        List<String> audit = auditRows();
        for (String forbiddenText : forbidden) {
            assertThat(RESPONSES).as("responses must not contain %s", shorten(forbiddenText))
                    .noneMatch(text -> text.contains(forbiddenText));
            assertThat(audit).as("audit rows must not contain %s", shorten(forbiddenText))
                    .noneMatch(text -> text.contains(forbiddenText));
            assertThat(logLines).as("logs must not contain %s", shorten(forbiddenText))
                    .noneMatch(text -> text.contains(forbiddenText));
        }
        // Names and dates travel in the responses and in the audit trail by design; the logs never carry them.
        for (String word : SECRET_WORDS) {
            assertThat(logLines).as("logs must not contain %s", shorten(word))
                    .noneMatch(text -> text.contains(word));
        }
    }

    private static String shorten(String text) {
        return text.length() > 12 ? text.substring(0, 12) + "..." : text;
    }

    /** One seeded staff member of one institution, with its live session and its token. */
    private record Member(UUID account, UUID institution, UUID family, String token) {
    }

    // --- The acceptance criterion ---

    @Test
    void anAdministratorRegistersAStudentWithADocumentFindsItByLastNameEditsAndDeactivatesIt() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        SECRET_WORDS.addAll(List.of("Zelaya-Quintanilla", "Xiomara-Pseudonimo"));

        JsonNode created = created(post(STUDENTS, admin, body(null, "Xiomara-Pseudonimo", "Zelaya-Quintanilla",
                "2012-05-20", "F", ID)));
        String id = created.get("id").asString();

        assertThat(created.get("studentCode").asString()).isEqualTo("E-000001");
        assertThat(created.get("hasNationalId").asBoolean()).isTrue();
        assertThat(created.get("nationalIdMasked").asString()).isEqualTo("****1234");
        assertThat(created.get("status").asString()).isEqualTo("ACTIVE");

        JsonNode found = HarnessProcess.json(get(STUDENTS + "?q=quintanilla", admin));
        assertThat(found.get("total").asLong()).isEqualTo(1);
        assertThat(found.get("items").get(0).get("id").asString()).isEqualTo(id);
        assertThat(found.get("items").get(0).get("nationalIdMasked").asString()).isEqualTo("****1234");

        HttpResponse<String> edited = put(id, admin, putBody("Xiomara-Pseudonimo", "Zelaya-Quintanilla Mejia",
                "2012-05-21", "F", "ACTIVE", null));
        assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(HarnessProcess.json(edited).get("lastName").asString()).isEqualTo("Zelaya-Quintanilla Mejia");
        assertThat(HarnessProcess.json(edited).get("nationalIdMasked").asString()).isEqualTo("****1234");

        HttpResponse<String> inactive = put(id, admin, putBody("Xiomara-Pseudonimo", "Zelaya-Quintanilla Mejia",
                "2012-05-21", "F", "INACTIVE", null));
        assertThat(HarnessProcess.json(inactive).get("status").asString()).isEqualTo("INACTIVE");
        assertThat(HarnessProcess.json(get(STUDENTS + "?status=INACTIVE", admin)).get("total").asLong()).isEqualTo(1);
        assertThat(HarnessProcess.json(get(STUDENTS + "?status=ACTIVE", admin)).get("total").asLong()).isZero();
        assertThat(HarnessProcess.json(get(STUDENTS + "/" + id, admin)).get("status").asString())
                .isEqualTo("INACTIVE");

        // In the database the document is encrypted, indexed blind and its last four are the only clear part.
        String[] row = storedDocument(UUID.fromString(id));
        assertThat(row[0]).startsWith("v1:").doesNotContain(ID).doesNotContain(ID_NORMALIZED);
        assertThat(row[1]).matches("[0-9a-f]{64}");
        assertThat(row[2]).isEqualTo("1234");
        assertThat(decrypt(admin.institution(), UUID.fromString(id), row[0])).isEqualTo(ID_NORMALIZED);
    }

    @Test
    void theResponsesCarryNoStoreAndTheExactMembersAndNeverTheInstitutionOrTheDocument() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        HttpResponse<String> response = post(STUDENTS, admin, body("MAT-1", "Ana", "Perez", "2012-05-20", "F", ID));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(response.body()).doesNotContain(admin.institution().toString())
                .doesNotContain(admin.account().toString());
        JsonNode student = HarnessProcess.json(response);
        assertThat(student.propertyNames()).containsExactly("id", "studentCode", "firstName", "lastName",
                "birthDate", "sex", "status", "hasNationalId", "nationalIdMasked", "createdAt", "updatedAt");
        assertThat(student.get("birthDate").asString()).isEqualTo("2012-05-20");
        JsonNode page = HarnessProcess.json(get(STUDENTS, admin));
        assertThat(page.propertyNames()).containsExactly("items", "total", "page", "size");
        assertThat(page.get("items").get(0).propertyNames()).containsExactlyElementsOf(student.propertyNames());
    }

    @Test
    void aStudentWithoutDocumentHasNoMaskAndTheDocumentColumnsStayNull() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        JsonNode student = created(post(STUDENTS, admin, body(null, "Ana", "Perez", "2012-05-20", "F", null)));

        assertThat(student.get("hasNationalId").asBoolean()).isFalse();
        assertThat(student.get("nationalIdMasked").isNull()).isTrue();
        assertThat(storedDocument(UUID.fromString(student.get("id").asString()))).containsExactly(null, null, null);
    }

    // --- The code ---

    @Test
    void theGeneratedCodesAreSequentialPerInstitutionAndATypedCodeNeitherConsumesNorBreaksTheSequence()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Member other = member(StaffRole.ADMINISTRATOR.code());

        assertThat(code(post(STUDENTS, admin, body(null, "Ana", "Perez", "2012-05-20", "F", null))))
                .isEqualTo("E-000001");
        assertThat(code(post(STUDENTS, admin, body("", "Luis", "Lopez", "2012-05-20", "M", null))))
                .isEqualTo("E-000002");
        assertThat(code(post(STUDENTS, admin, body("MAT-77", "Eva", "Mejia", "2012-05-20", "F", null))))
                .isEqualTo("MAT-77");
        assertThat(code(post(STUDENTS, admin, body("E-000003", "Rosa", "Diaz", "2012-05-20", "F", null))))
                .isEqualTo("E-000003");
        assertThat(code(post(STUDENTS, admin, body(null, "Raul", "Cruz", "2012-05-20", "M", null))))
                .isEqualTo("E-000004");
        assertThat(code(post(STUDENTS, other, body(null, "Ana", "Perez", "2012-05-20", "F", null))))
                .isEqualTo("E-000001");
    }

    @Test
    void aRepeatedCodeIs409WithItsOwnCodeAndTheSameCodeIsFreeInAnotherInstitution() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Member other = member(StaffRole.ADMINISTRATOR.code());
        created(post(STUDENTS, admin, body("MAT-1", "Ana", "Perez", "2012-05-20", "F", null)));

        assertProblem(post(STUDENTS, admin, body("MAT-1", "Luis", "Lopez", "2012-05-20", "M", null)), 409,
                "duplicate-student-code");
        assertProblem(post(STUDENTS, admin, body(" MAT-1 ", "Luis", "Lopez", "2012-05-20", "M", null)), 409,
                "duplicate-student-code");
        created(post(STUDENTS, other, body("MAT-1", "Eva", "Mejia", "2012-05-20", "F", null)));

        assertThat(countOf("students_student", admin.institution())).isEqualTo(1);
    }

    @Test
    void aFailedCreationGivesTheGeneratedNumberBackAndLeavesNeitherRowNorEntry() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        created(post(STUDENTS, admin, body(null, "Ana", "Perez", "2012-05-20", "F", ID)));
        int entries = studentEntriesOf(admin.institution()).size();

        assertProblem(post(STUDENTS, admin, body(null, "Luis", "Lopez", "2012-05-20", "M", ID)), 409,
                "duplicate-national-id");

        assertThat(countOf("students_student", admin.institution())).isEqualTo(1);
        assertThat(studentEntriesOf(admin.institution())).hasSize(entries);
        assertThat(code(post(STUDENTS, admin, body(null, "Luis", "Lopez", "2012-05-20", "M", null))))
                .isEqualTo("E-000002");
    }

    @Test
    void theCodeCannotBeSentOnAnEditBecauseItNeverChanges() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(STUDENTS, admin, body("MAT-1", "Ana", "Perez", "2012-05-20", "F", null)))
                .get("id").asString();

        assertProblem(send("PUT", STUDENTS + "/" + id, admin.token(), "{\"studentCode\":\"MAT-2\",\"firstName\":\"Ana\","
                + "\"lastName\":\"Perez\",\"birthDate\":\"2012-05-20\",\"sex\":\"F\",\"status\":\"ACTIVE\"}"), 400,
                "validation-failed");

        assertThat(HarnessProcess.json(get(STUDENTS + "/" + id, admin)).get("studentCode").asString())
                .isEqualTo("MAT-1");
    }

    // --- The races the counter lock and the unique indexes settle ---

    @Test
    void twoSimultaneousCreationsWithoutACodeGetDifferentSequentialCodes() throws Exception {
        for (int round = 0; round < 4; round++) {
            Member admin = member(StaffRole.ADMINISTRATOR.code());
            List<HttpResponse<String>> answers = inParallel(
                    () -> post(STUDENTS, admin, body(null, "Ana", "Perez", "2012-05-20", "F", null)),
                    () -> post(STUDENTS, admin, body(null, "Luis", "Lopez", "2012-05-20", "M", null)));

            assertThat(answers).extracting(HttpResponse::statusCode).containsExactly(201, 201);
            assertThat(answers.stream().map(StudentEndpointsIT::code)).containsExactlyInAnyOrder("E-000001",
                    "E-000002");
        }
    }

    @Test
    void manySimultaneousCreationsLeaveNoRepeatedCodeAndNoGap() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        List<Callable<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String name = "Nombre" + i;
            calls.add(() -> post(STUDENTS, admin, body(null, name, "Apellido", "2012-05-20", "F", null)));
        }

        List<HttpResponse<String>> answers = inParallel(calls);

        assertThat(answers).extracting(HttpResponse::statusCode).containsOnly(201);
        Set<String> codes = new HashSet<>();
        answers.forEach(answer -> codes.add(code(answer)));
        assertThat(codes).hasSize(12).contains("E-000001", "E-000012");
        assertThat(countOf("students_student", admin.institution())).isEqualTo(12);
    }

    @Test
    void twoSimultaneousCreationsWithTheSameDocumentLeaveOneAndAnswer409ToTheOther() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        List<HttpResponse<String>> answers = inParallel(
                () -> post(STUDENTS, admin, body(null, "Ana", "Perez", "2012-05-20", "F", ID)),
                () -> post(STUDENTS, admin, body(null, "Luis", "Lopez", "2012-05-20", "M", "0801201201234")));

        assertThat(answers).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
        assertProblem(answers.stream().filter(a -> a.statusCode() == 409).findFirst().orElseThrow(), 409,
                "duplicate-national-id");
        assertThat(countOf("students_student", admin.institution())).isEqualTo(1);
    }

    @Test
    void twoSimultaneousCreationsWithTheSameTypedCodeLeaveOneAndAnswer409ToTheOther() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        List<HttpResponse<String>> answers = inParallel(
                () -> post(STUDENTS, admin, body("MAT-1", "Ana", "Perez", "2012-05-20", "F", null)),
                () -> post(STUDENTS, admin, body("MAT-1", "Luis", "Lopez", "2012-05-20", "M", null)));

        assertThat(answers).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
        assertProblem(answers.stream().filter(a -> a.statusCode() == 409).findFirst().orElseThrow(), 409,
                "duplicate-student-code");
    }

    // --- The document ---

    @Test
    void theSameDocumentInAnyFormatIs409AndIsFreeInAnotherInstitution() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Member other = member(StaffRole.ADMINISTRATOR.code());
        created(post(STUDENTS, admin, body(null, "Ana", "Perez", "2012-05-20", "F", ID)));

        for (String variant : List.of(ID, ID_NORMALIZED, "0801-2012-01234 ", "08012012-01234", "0-8-0-1-2012-01234")) {
            assertProblem(post(STUDENTS, admin, body(null, "Luis", "Lopez", "2012-05-20", "M", variant)), 409,
                    "duplicate-national-id");
        }
        created(post(STUDENTS, other, body(null, "Eva", "Mejia", "2012-05-20", "F", ID)));
        assertThat(countOf("students_student", admin.institution())).isEqualTo(1);
    }

    @Test
    void lettersInTheDocumentAreCaseInsensitive() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        created(post(STUDENTS, admin, body(null, "Ana", "Perez", "2012-05-20", "F", "ab-12345678")));

        assertProblem(post(STUDENTS, admin, body(null, "Luis", "Lopez", "2012-05-20", "M", "AB12345678")), 409,
                "duplicate-national-id");
    }

    @Test
    void aMalformedDocumentIs400AndLeavesNothing() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        for (String bad : List.of("1234567", "1".repeat(21), "1234 5678", "1234_5678", "1234.5678", "12345678!",
                "--------", "12-34---")) {
            assertProblem(post(STUDENTS, admin, body(null, "Ana", "Perez", "2012-05-20", "F", bad)), 400,
                    "validation-failed");
        }
        created(post(STUDENTS, admin, body(null, "Ana", "Perez", "2012-05-20", "F", "12345678")));
        created(post(STUDENTS, admin, body(null, "Luis", "Lopez", "2012-05-20", "M", "1".repeat(20))));
        assertThat(countOf("students_student", admin.institution())).isEqualTo(2);
    }

    @Test
    void editingTheDocumentKeepsItWhenOmittedReplacesItWhenSentAndRemovesItWithAnEmptyText() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(STUDENTS, admin, body(null, "Ana", "Perez", "2012-05-20", "F", ID))).get("id")
                .asString();
        String original = storedDocument(UUID.fromString(id))[0];
        String blindOriginal = storedDocument(UUID.fromString(id))[1];

        JsonNode kept = HarnessProcess.json(put(id, admin, putBody("Ana", "Perez", "2012-05-20", "F", "ACTIVE", null)));
        assertThat(kept.get("nationalIdMasked").asString()).isEqualTo("****1234");
        assertThat(storedDocument(UUID.fromString(id))[0]).isEqualTo(original);

        JsonNode replaced = HarnessProcess.json(put(id, admin,
                putBody("Ana", "Perez", "2012-05-20", "F", "ACTIVE", "AB-99998888")));
        assertThat(replaced.get("nationalIdMasked").asString()).isEqualTo("****8888");
        String[] afterReplace = storedDocument(UUID.fromString(id));
        assertThat(afterReplace[0]).isNotEqualTo(original).startsWith("v1:");
        assertThat(afterReplace[1]).isNotEqualTo(blindOriginal).matches("[0-9a-f]{64}");
        assertThat(decrypt(admin.institution(), UUID.fromString(id), afterReplace[0])).isEqualTo("AB99998888");

        JsonNode removed = HarnessProcess.json(put(id, admin, putBody("Ana", "Perez", "2012-05-20", "F", "ACTIVE", "")));
        assertThat(removed.get("hasNationalId").asBoolean()).isFalse();
        assertThat(removed.get("nationalIdMasked").isNull()).isTrue();
        assertThat(storedDocument(UUID.fromString(id))).containsExactly(null, null, null);

        // The document that was removed and the one that was replaced are free again.
        created(post(STUDENTS, admin, body(null, "Luis", "Lopez", "2012-05-20", "M", ID)));
        created(post(STUDENTS, admin, body(null, "Eva", "Mejia", "2012-05-20", "F", "AB-99998888")));
    }

    @Test
    void resendingTheOwnDocumentInAnotherFormatIsNotADuplicateButTakingAnotherStudentsIs409() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String first = created(post(STUDENTS, admin, body(null, "Ana", "Perez", "2012-05-20", "F", ID))).get("id")
                .asString();
        String second = created(post(STUDENTS, admin, body(null, "Luis", "Lopez", "2012-05-20", "M", "ZZ-11112222")))
                .get("id").asString();

        assertThat(put(first, admin, putBody("Ana", "Perez", "2012-05-20", "F", "ACTIVE", ID_NORMALIZED))
                .statusCode()).isEqualTo(200);
        assertProblem(put(second, admin, putBody("Luis", "Lopez", "2012-05-20", "M", "ACTIVE", ID)), 409,
                "duplicate-national-id");
        assertThat(HarnessProcess.json(get(STUDENTS + "/" + second, admin)).get("nationalIdMasked").asString())
                .isEqualTo("****2222");
    }

    @Test
    void theDocumentOfOneRowDoesNotDecryptAsTheDocumentOfAnother() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String first = created(post(STUDENTS, admin, body(null, "Ana", "Perez", "2012-05-20", "F", ID))).get("id")
                .asString();
        String second = created(post(STUDENTS, admin, body(null, "Luis", "Lopez", "2012-05-20", "M", "ZZ-11112222")))
                .get("id").asString();

        String ciphertextOfFirst = storedDocument(UUID.fromString(first))[0];

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> decrypt(admin.institution(), UUID.fromString(second), ciphertextOfFirst))
                .hasCauseInstanceOf(AeadIntegrityException.class);
    }

    // --- Validation at the edge ---

    @Test
    void aBirthDateInTheFutureOrBefore1990IsRefusedAndTheBordersAreAccepted() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(2).toString();

        HttpResponse<String> future = post(STUDENTS, admin, body(null, "Ana", "Perez", tomorrow, "F", null));
        HttpResponse<String> old = post(STUDENTS, admin, body(null, "Ana", "Perez", "1989-12-31", "F", null));
        assertProblem(future, 400, "validation-failed");
        assertProblem(old, 400, "validation-failed");
        assertThat(HarnessProcess.json(future).get("errors").toString()).contains("birthDate", "past-or-present");
        assertThat(HarnessProcess.json(old).get("errors").toString()).contains("birthDate", "birth-date-floor");
        created(post(STUDENTS, admin, body(null, "Ana", "Perez", "1990-01-01", "F", null)));
        created(post(STUDENTS, admin, body(null, "Luis", "Lopez", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString(),
                "M", null)));
        assertThat(countOf("students_student", admin.institution())).isEqualTo(2);
    }

    @Test
    void namesAreTrimmedBlankOnesAndTooLongOnesAreRefused() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        JsonNode trimmed = created(post(STUDENTS, admin, body(null, "  Ana Maria  ", "\\tPerez ", "2012-05-20", "F",
                null)));
        assertThat(trimmed.get("firstName").asString()).isEqualTo("Ana Maria");
        assertThat(trimmed.get("lastName").asString()).isEqualTo("Perez");
        created(post(STUDENTS, admin, body(null, "A".repeat(100), " " + "B".repeat(100) + " ", "2012-05-20", "F", null)));

        assertProblem(post(STUDENTS, admin, body(null, "   ", "Perez", "2012-05-20", "F", null)), 400,
                "validation-failed");
        assertProblem(post(STUDENTS, admin, body(null, "Ana", "", "2012-05-20", "F", null)), 400,
                "validation-failed");
        assertProblem(post(STUDENTS, admin, body(null, "A".repeat(101), "Perez", "2012-05-20", "F", null)), 400,
                "validation-failed");
        assertProblem(post(STUDENTS, admin, body(null, "Ana", "P".repeat(101), "2012-05-20", "F", null)), 400,
                "validation-failed");
        assertThat(countOf("students_student", admin.institution())).isEqualTo(2);
        assertThat(studentEntriesOf(admin.institution())).hasSize(2);
    }

    @Test
    void anUnknownSexMissingMembersUnknownMembersAndAnInstitutionInTheBodyAreRefused() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());

        assertProblem(post(STUDENTS, admin, body(null, "Ana", "Perez", "2012-05-20", "X", null)), 400, "validation-failed");
        assertProblem(post(STUDENTS, admin, body(null, "Ana", "Perez", "2012-05-20", "f", null)), 400, "validation-failed");
        assertProblem(post(STUDENTS, admin, "{\"firstName\":\"Ana\",\"lastName\":\"Perez\",\"sex\":\"F\"}"), 400,
                "validation-failed");
        assertProblem(post(STUDENTS, admin, "{\"firstName\":\"Ana\",\"lastName\":\"Perez\",\"birthDate\":\"2012-05-20\","
                + "\"sex\":\"F\",\"institutionId\":\"" + theirs.institution() + "\"}"), 400, "validation-failed");
        assertProblem(post(STUDENTS, admin, "{\"firstName\":\"Ana\",\"lastName\":\"Perez\",\"birthDate\":\"2012-05-20\","
                + "\"sex\":\"F\",\"status\":\"INACTIVE\"}"), 400, "validation-failed");
        assertProblem(post(STUDENTS, admin, body(null, "Ana", "Perez", "not-a-date", "F", null)), 400,
                "validation-failed");
        assertProblem(send("PUT", STUDENTS + "/" + UUID.randomUUID(), admin.token(),
                "{\"firstName\":\"Ana\",\"lastName\":\"Perez\",\"birthDate\":\"2012-05-20\",\"sex\":\"F\"}"), 400,
                "validation-failed");
        assertProblem(send("PUT", STUDENTS + "/" + UUID.randomUUID(), admin.token(),
                putBody("Ana", "Perez", "2012-05-20", "F", "DELETED", null)), 400, "validation-failed");
        assertProblem(send("PUT", STUDENTS + "/not-a-uuid", admin.token(),
                putBody("Ana", "Perez", "2012-05-20", "F", "ACTIVE", null)), 400, "validation-failed");
        assertProblem(get(STUDENTS + "/not-a-uuid", admin), 400, "validation-failed");

        assertThat(countOf("students_student", admin.institution())).isZero();
        assertThat(countOf("students_student", theirs.institution())).isZero();
        assertThat(studentEntriesOf(admin.institution())).isEmpty();
    }

    // --- Lists ---

    @Test
    void theSearchNeedsTwoCharactersAndLooksInFirstNameLastNameAndCodeIgnoringCase() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        created(post(STUDENTS, admin, body("MAT-1", "Ana", "Perez", "2012-05-20", "F", null)));
        created(post(STUDENTS, admin, body("MAT-2", "Luis", "Lopez", "2012-05-20", "M", null)));
        created(post(STUDENTS, admin, body("XYZ-3", "Eva", "Mejia", "2012-05-20", "F", null)));

        assertProblem(get(STUDENTS + "?q=a", admin), 400, "validation-failed");
        assertProblem(get(STUDENTS + "?q=", admin), 400, "validation-failed");
        assertProblem(get(STUDENTS + "?q=" + "x".repeat(101), admin), 400, "validation-failed");
        assertThat(lastNames(get(STUDENTS + "?q=PER", admin))).containsExactly("Perez");
        assertThat(lastNames(get(STUDENTS + "?q=lu", admin))).containsExactly("Lopez");
        assertThat(lastNames(get(STUDENTS + "?q=mat-", admin))).containsExactly("Lopez", "Perez");
        assertThat(lastNames(get(STUDENTS + "?q=xyz", admin))).containsExactly("Mejia");
        assertThat(lastNames(get(STUDENTS + "?q=%25%25", admin))).as("a percent sign is text, not a wildcard")
                .isEmpty();
        assertThat(lastNames(get(STUDENTS + "?q=__", admin))).as("an underscore is text, not a wildcard").isEmpty();
        assertThat(lastNames(get(STUDENTS + "?q=nobody", admin))).isEmpty();
    }

    @Test
    void theListPagesAndSortsOnTheServerWithTheAllowedFieldsOnly() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        created(post(STUDENTS, admin, body("C-1", "Carlos", "Zelaya", "2010-01-01", "M", null)));
        created(post(STUDENTS, admin, body("A-3", "Beatriz", "Aguilar", "2012-06-01", "F", null)));
        created(post(STUDENTS, admin, body("B-2", "Ana", "Mejia", "2011-03-03", "F", null)));

        assertThat(lastNames(get(STUDENTS, admin))).as("default order is by last name").containsExactly("Aguilar",
                "Mejia", "Zelaya");
        assertThat(lastNames(get(STUDENTS + "?sort=lastName,desc", admin))).containsExactly("Zelaya", "Mejia",
                "Aguilar");
        assertThat(lastNames(get(STUDENTS + "?sort=firstName", admin))).containsExactly("Mejia", "Aguilar", "Zelaya");
        assertThat(lastNames(get(STUDENTS + "?sort=studentCode", admin))).containsExactly("Aguilar", "Mejia",
                "Zelaya");
        assertThat(lastNames(get(STUDENTS + "?sort=studentCode,desc", admin))).containsExactly("Zelaya", "Mejia",
                "Aguilar");
        assertThat(lastNames(get(STUDENTS + "?sort=birthDate", admin))).containsExactly("Zelaya", "Mejia", "Aguilar");
        assertThat(lastNames(get(STUDENTS + "?sort=birthDate,desc", admin))).containsExactly("Aguilar", "Mejia",
                "Zelaya");

        JsonNode first = HarnessProcess.json(get(STUDENTS + "?size=2&page=0", admin));
        JsonNode second = HarnessProcess.json(get(STUDENTS + "?size=2&page=1", admin));
        assertThat(first.get("total").asLong()).isEqualTo(3);
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("size").asInt()).isEqualTo(2);
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("page").asInt()).isEqualTo(1);
        assertThat(HarnessProcess.json(get(STUDENTS + "?page=9", admin)).get("items")).isEmpty();
        assertThat(HarnessProcess.json(get(STUDENTS, admin)).get("size").asInt()).isEqualTo(50);
    }

    @Test
    void aSortFieldOutsideTheAllowedSetAndOutOfRangePagesAreRefused() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        for (String sort : List.of("password", "nationalId", "nationalIdMasked", "status", "createdAt", "id",
                "lastName,sideways", "lastName,asc,desc")) {
            assertProblem(get(STUDENTS + "?sort=" + sort, admin), 400, "validation-failed");
        }
        assertProblem(get(STUDENTS + "?size=201", admin), 400, "validation-failed");
        assertProblem(get(STUDENTS + "?size=0", admin), 400, "validation-failed");
        assertProblem(get(STUDENTS + "?page=-1", admin), 400, "validation-failed");
        assertProblem(get(STUDENTS + "?status=DELETED", admin), 400, "validation-failed");
        assertThat(HarnessProcess.json(get(STUDENTS + "?size=200", admin)).get("size").asInt()).isEqualTo(200);
    }

    // --- Detail ---

    @Test
    void theDetailReturnsTheStudentAndAnUnknownIdentifierIs404() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        JsonNode created = created(post(STUDENTS, admin, body(null, "Ana", "Perez", "2012-05-20", "F", ID)));

        JsonNode detail = HarnessProcess.json(get(STUDENTS + "/" + created.get("id").asString(), admin));

        assertThat(detail).isEqualTo(created);
        assertProblem(get(STUDENTS + "/" + UUID.randomUUID(), admin), 404, "resource-not-found");
        assertProblem(send("PUT", STUDENTS + "/" + UUID.randomUUID(), admin.token(),
                putBody("Ana", "Perez", "2012-05-20", "F", "ACTIVE", null)), 404, "resource-not-found");
    }

    // --- Roles: the decision is the server's ---

    @Test
    void aCashierMayReadButCannotCreateOrEditAndTheRefusalLeavesNoRowAndNoStudentEntry() throws SQLException {
        Member cashier = member(StaffRole.CASHIER.code());
        Member admin = sameInstitution(cashier.institution(), StaffRole.ADMINISTRATOR.code());
        String id = created(post(STUDENTS, admin, body("MAT-1", "Ana", "Perez", "2012-05-20", "F", ID))).get("id")
                .asString();
        int entriesBefore = studentEntriesOf(cashier.institution()).size();

        assertThat(get(STUDENTS, cashier).statusCode()).isEqualTo(200);
        assertThat(get(STUDENTS + "/" + id, cashier).statusCode()).isEqualTo(200);
        assertProblem(post(STUDENTS, cashier, body("MAT-2", "Luis", "Lopez", "2012-05-20", "M", null)), 403, "forbidden");
        assertProblem(put(id, cashier, putBody("Cambiado", "Perez", "2012-05-20", "F", "INACTIVE", "")), 403,
                "forbidden");

        assertThat(countOf("students_student", cashier.institution())).isEqualTo(1);
        assertThat(code(get(STUDENTS + "/" + id, admin))).isEqualTo("MAT-1");
        JsonNode untouched = HarnessProcess.json(get(STUDENTS + "/" + id, admin));
        assertThat(untouched.get("firstName").asString()).isEqualTo("Ana");
        assertThat(untouched.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(untouched.get("hasNationalId").asBoolean()).isTrue();
        assertThat(studentEntriesOf(cashier.institution())).as("no write entry for a refused request")
                .hasSize(entriesBefore);
        assertThat(auditActionsOf(cashier.institution()).stream().filter("authorization.access.denied"::equals))
                .hasSize(2);
    }

    @Test
    void anAccountWithoutARoleCannotEvenRead() throws SQLException {
        Member nobody = member(null);

        assertProblem(get(STUDENTS, nobody), 403, "forbidden");
        assertProblem(get(STUDENTS + "/" + UUID.randomUUID(), nobody), 403, "forbidden");
    }

    @Test
    void noCredentialAnswers401() {
        assertProblem(send("GET", STUDENTS, null, null), 401, "authentication-required");
        assertProblem(send("GET", STUDENTS + "/" + UUID.randomUUID(), null, null), 401, "authentication-required");
    }

    @Test
    void aClosedSessionOpensNoRouteOfTheRegistry() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(STUDENTS, admin, body("MAT-1", "Ana", "Perez", "2012-05-20", "F", null))).get("id")
                .asString();
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

        assertProblem(get(STUDENTS, admin), 401, "token-invalid");
        assertProblem(get(STUDENTS + "/" + id, admin), 401, "token-invalid");
        assertProblem(post(STUDENTS, admin, body("MAT-2", "Luis", "Lopez", "2012-05-20", "M", null)), 401,
                "token-invalid");
        assertProblem(put(id, admin, putBody("Cambiado", "Perez", "2012-05-20", "F", "INACTIVE", null)), 401,
                "token-invalid");
        assertThat(countOf("students_student", admin.institution())).isEqualTo(1);
    }

    @Test
    void aWriteWithoutAnAllowedOriginIsRefusedBeforeAnyEffect() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        HttpResponse<String> response = execute(HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + STUDENTS))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + admin.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(null, "Ana", "Perez", "2012-05-20", "F", null)))
                .build());

        assertProblem(response, 403, "csrf-invalid");
        assertThat(countOf("students_student", admin.institution())).isZero();
    }

    // --- Isolation between institutions ---

    @Test
    void aUserOfAnotherInstitutionNeverSeesNorEditsTheStudentsOfThisOne() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(STUDENTS, mine, body("MAT-1", "Ana", "Perez", "2012-05-20", "F", ID))).get("id")
                .asString();

        JsonNode page = HarnessProcess.json(get(STUDENTS, theirs));
        assertThat(page.get("total").asLong()).isZero();
        assertThat(page.get("items")).isEmpty();
        assertThat(HarnessProcess.json(get(STUDENTS + "?q=perez", theirs)).get("total").asLong()).isZero();
        assertThat(HarnessProcess.json(get(STUDENTS + "?q=mat-1", theirs)).get("total").asLong()).isZero();
        assertProblem(get(STUDENTS + "/" + id, theirs), 404, "resource-not-found");
        assertProblem(put(id, theirs, putBody("Hijack", "Perez", "2012-05-20", "F", "INACTIVE", "")), 404,
                "resource-not-found");
        // The same code and the same document are free in the other institution.
        created(post(STUDENTS, theirs, body("MAT-1", "Eva", "Mejia", "2012-05-20", "F", ID)));

        JsonNode mineAfter = HarnessProcess.json(get(STUDENTS + "/" + id, mine));
        assertThat(mineAfter.get("firstName").asString()).isEqualTo("Ana");
        assertThat(mineAfter.get("hasNationalId").asBoolean()).isTrue();
        assertThat(countOf("students_student", mine.institution())).isEqualTo(1);
    }

    @Test
    void aBodyThatNamesAnotherInstitutionChangesNothingBecauseTheTokenDecides() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());

        HttpResponse<String> response = post(STUDENTS, mine, "{\"firstName\":\"Ana\",\"lastName\":\"Perez\","
                + "\"birthDate\":\"2012-05-20\",\"sex\":\"F\",\"institutionId\":\"" + theirs.institution() + "\"}");

        assertProblem(response, 400, "validation-failed");
        assertThat(countOf("students_student", theirs.institution())).isZero();
        assertThat(countOf("students_student", mine.institution())).isZero();
    }

    // --- Audit ---

    @Test
    void everyWriteLeavesOneEntryWithTheStateBeforeAndAfterAndNeverTheDocument() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        JsonNode student = created(post(STUDENTS, admin, body("MAT-1", "Ana", "Perez", "2012-05-20", "F", ID)));
        String id = student.get("id").asString();
        put(id, admin, putBody("Anita", "Perez", "2012-05-20", "F", "INACTIVE", "AB-99998888"));
        put(id, admin, putBody("Anita", "Perez", "2012-05-20", "F", "INACTIVE", ""));
        get(STUDENTS, admin);
        get(STUDENTS + "/" + id, admin);

        List<String[]> entries = studentEntriesOf(admin.institution());
        assertThat(entries).extracting(row -> row[0]).containsExactly("students.student.created",
                "students.student.updated", "students.student.updated");
        assertThat(entries).extracting(row -> row[1]).containsOnly("students.student");
        assertThat(entries).extracting(row -> row[2]).containsOnly(id);
        assertThat(entries.get(0)[3]).as("a creation has no before state").isNull();
        JsonNode created = json(entries.get(0)[4]);
        assertThat(created.get("studentCode").asString()).isEqualTo("MAT-1");
        assertThat(created.get("firstName").asString()).isEqualTo("Ana");
        assertThat(created.get("birthDate").asString()).isEqualTo("2012-05-20");
        assertThat(created.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(created.get("hasNationalId").asBoolean()).isTrue();
        assertThat(created.get("nationalIdLast4").asString()).isEqualTo("1234");
        JsonNode before = json(entries.get(1)[3]);
        JsonNode after = json(entries.get(1)[4]);
        assertThat(before.get("firstName").asString()).isEqualTo("Ana");
        assertThat(before.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(after.get("firstName").asString()).isEqualTo("Anita");
        assertThat(after.get("status").asString()).isEqualTo("INACTIVE");
        assertThat(after.get("nationalIdLast4").asString()).isEqualTo("8888");
        JsonNode removed = json(entries.get(2)[4]);
        assertThat(removed.get("hasNationalId").asBoolean()).isFalse();
        assertThat(removed.has("nationalIdLast4")).isFalse();
        assertThat(actorsOfStudentEntries(admin.institution())).containsOnly(admin.account().toString());
    }

    @Test
    void aWriteThatRollsBackLeavesNeitherTheRowNorTheEntry() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        created(post(STUDENTS, admin, body("MAT-1", "Ana", "Perez", "2012-05-20", "F", null)));
        int entries = studentEntriesOf(admin.institution()).size();

        assertProblem(post(STUDENTS, admin, body("MAT-1", "Otro", "Otra", "2012-05-20", "F", null)), 409,
                "duplicate-student-code");

        assertThat(countOf("students_student", admin.institution())).isEqualTo(1);
        assertThat(studentEntriesOf(admin.institution())).hasSize(entries);
    }

    // --- Logs ---

    @Test
    void theRequestsOfTheFlowsLogNoNameNoBirthDateAndNoDocument() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        SECRET_WORDS.addAll(List.of("Wenceslao-Marcador", "Apellido-Marcador", "2011-11-11"));

        String id = created(post(STUDENTS, admin, body(null, "Wenceslao-Marcador", "Apellido-Marcador", "2011-11-11",
                "M", ID))).get("id").asString();
        put(id, admin, putBody("Wenceslao-Marcador", "Apellido-Marcador", "2011-11-11", "M", "ACTIVE", "AB-99998888"));
        post(STUDENTS, admin, body(null, "Wenceslao-Marcador", "Apellido-Marcador", "2011-11-11", "M", "AB-99998888"));
        post(STUDENTS, admin, body(null, "Wenceslao-Marcador", "Apellido-Marcador", "not-a-date", "M", ID));
        get(STUDENTS + "?q=marcador", admin);
        get(STUDENTS + "/" + id, admin);

        assertThat(logs.list).as("the flow did log something, so the scan is not vacuous").isNotEmpty();
    }

    // --- Bodies ---

    private static String body(String code, String first, String last, String birthDate, String sex,
            String nationalId) {
        StringBuilder json = new StringBuilder("{");
        if (code != null) {
            json.append("\"studentCode\":\"").append(code).append("\",");
        }
        json.append("\"firstName\":\"").append(first).append("\",\"lastName\":\"").append(last)
                .append("\",\"birthDate\":\"").append(birthDate).append("\",\"sex\":\"").append(sex).append('"');
        if (nationalId != null) {
            json.append(",\"nationalId\":\"").append(nationalId).append('"');
        }
        return json.append('}').toString();
    }

    private static String putBody(String first, String last, String birthDate, String sex, String status,
            String nationalId) {
        StringBuilder json = new StringBuilder("{\"firstName\":\"").append(first).append("\",\"lastName\":\"")
                .append(last).append("\",\"birthDate\":\"").append(birthDate).append("\",\"sex\":\"").append(sex)
                .append("\",\"status\":\"").append(status).append('"');
        if (nationalId != null) {
            json.append(",\"nationalId\":\"").append(nationalId).append('"');
        }
        return json.append('}').toString();
    }

    private static String code(HttpResponse<String> response) {
        return HarnessProcess.json(response).get("studentCode").asString();
    }

    private static List<String> lastNames(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        HarnessProcess.json(response).get("items").forEach(item -> names.add(item.get("lastName").asString()));
        return names;
    }

    private static JsonNode json(String document) {
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(document);
    }

    private static JsonNode created(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return HarnessProcess.json(response);
    }

    // --- Seeding ---

    /** A fresh account in a fresh institution (with its row) with the role, a live session and its token. */
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

    /** national_id_encrypted, national_id_blind_index and national_id_last4 of one student. */
    private static String[] storedDocument(UUID studentId) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select national_id_encrypted, national_id_blind_index, national_id_last4
                          from students_student where id = ?
                        """)) {
            statement.setObject(1, studentId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return new String[] {result.getString(1), result.getString(2), result.getString(3)};
            }
        }
    }

    private static List<String> storedCiphertexts() throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select national_id_encrypted, national_id_blind_index from students_student")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    for (int column = 1; column <= 2; column++) {
                        if (result.getString(column) != null) {
                            values.add(result.getString(column));
                        }
                    }
                }
            }
        }
        return values;
    }

    /** Every audit row of every institution, as text, columns included. */
    private static List<String> auditRows() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select a::text from shared_audit_log a")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(result.getString(1));
                }
            }
        }
        return rows;
    }

    private static List<String> auditActionsOf(UUID institution) throws SQLException {
        List<String> actions = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select action from shared_audit_log where institution_id = ? order by id")) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actions.add(result.getString(1));
                }
            }
        }
        return actions;
    }

    /** action, entity type, entity id, before, after of every entry of the registry, oldest first. */
    private static List<String[]> studentEntriesOf(UUID institution) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select action, entity_type, entity_id, before_value::text, after_value::text
                          from shared_audit_log
                         where institution_id = ? and action like 'students.%' order by id
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

    private static List<String> actorsOfStudentEntries(UUID institution) throws SQLException {
        List<String> actors = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select actor_id::text from shared_audit_log
                         where institution_id = ? and action like 'students.%'
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

    /** Decrypts a stored document with the real column encryption of the running process. */
    private static String decrypt(UUID institution, UUID studentId, String stored) {
        ColumnEncryptionService encryption = process.getBean(ColumnEncryptionService.class);
        TransactionRunner runner = process.getBean(TransactionRunner.class);
        byte[] plain = runner.execute(
                new SecurityContext("", "system", institution.toString(), UUID.randomUUID().toString()),
                () -> {
                    try {
                        return encryption.decrypt("students_student", "national_id_encrypted",
                                new InstitutionId(institution), institution + ":" + studentId, stored);
                    } catch (AeadIntegrityException e) {
                        throw new IllegalStateException(e);
                    }
                });
        return new String(plain, StandardCharsets.UTF_8);
    }

    // --- HTTP ---

    @SafeVarargs
    private static List<HttpResponse<String>> inParallel(Callable<HttpResponse<String>>... calls) throws Exception {
        return inParallel(List.of(calls));
    }

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

    private static HttpResponse<String> put(String id, Member member, String body) {
        return send("PUT", STUDENTS + "/" + id, member.token(), body);
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

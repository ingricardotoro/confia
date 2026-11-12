package com.confia.imports.web;

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
import java.io.ByteArrayOutputStream;
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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
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
 * The three routes of the student import on the real administrative process, started through the production launcher
 * against a real PostgreSQL with the least-privilege role of the application (docs/cortes/imports-students.md). Every
 * token is made by the production issuer; the accounts, the roles and the institution rows are seeded as the owner of
 * the schema; every test has an institution of its own.
 *
 * <p>It covers the acceptance criterion (a CSV of 100 students with 5 defective rows: exactly those 5 rejections by
 * line and reason, corrected and simulated again, applied, the students are in the registry and applying the file again
 * creates none), the edge cases of the note one by one, the role without the permission (a {@code 403} that leaves no
 * batch, no student and no entry), the isolation between institutions, two simultaneous applications of the same file,
 * the strict rate limits, the audit entries, and that no response, audit row, batch row or log line carries a name, a
 * reference, a document or its ciphertext.
 */
class ImportEndpointsIT extends CommittingPostgresIntegrationTest {

    private static final String SIMULATE = "/api/v1/imports/students/simulate";
    private static final String APPLY = "/api/v1/imports/students/apply";
    private static final String BATCHES = "/api/v1/imports";
    private static final String STUDENTS = "/api/v1/students";
    private static final String HEADER = "legacyId,firstName,lastName,birthDate,nationalId,sex\n";
    private static final String RATE_LIMIT = "confia.web.rate-limit.";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final String BLIND_INDEX_KEY = randomBase64Key();
    /** Prefixes of the personal values of every file of these tests: they must appear nowhere but in the registry. */
    private static final List<String> PERSONAL = List.of("NombreSecreto", "ApellidoSecreto", "REF-SECRETA", "0801-2012-",
            "080120120");

    private static KeyPair keys;
    private static CompactJws jws;
    private static ConfigurableApplicationContext process;
    private static int port;

    /** Responses of the import routes only: the registry legitimately answers with names. */
    private static final List<String> RESPONSES = Collections.synchronizedList(new ArrayList<>());

    private ListAppender<ILoggingEvent> logs;
    private Level rootLevelBefore;
    private static final List<String> WIRE_LOGGERS = List.of("org.apache.coyote", "org.apache.tomcat",
            "org.postgresql", "org.jooq", "com.zaxxer.hikari");
    private final Map<String, Level> wireLevelsBefore = new java.util.HashMap<>();

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
                        RATE_LIMIT + "admin-write-account.request-limit", "10000",
                        RATE_LIMIT + "admin-import.request-limit", "10000",
                        RATE_LIMIT + "admin-import-account.request-limit", "10000"));
        port = ((WebServerApplicationContext) process).getWebServer().getPort();
    }

    @AfterAll
    static void stopTheProcess() {
        process.close();
    }

    @BeforeEach
    void captureLogs() {
        RESPONSES.clear();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLevelBefore = root.getLevel();
        logs = new ListAppender<>();
        logs.start();
        root.setLevel(Level.TRACE);
        for (String name : WIRE_LOGGERS) {
            Logger wire = (Logger) LoggerFactory.getLogger(name);
            wireLevelsBefore.put(name, wire.getLevel());
            wire.setLevel(Level.INFO);
        }
        root.addAppender(logs);
    }

    /**
     * Whatever a test did, no import response, audit row, batch row or log line carries a name, a reference or a
     * document of the files, nor the ciphertext of a stored document.
     */
    @AfterEach
    void nothingPersonalLeaksAnywhere() throws SQLException {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(logs);
        root.setLevel(rootLevelBefore);
        wireLevelsBefore.forEach((name, level) -> ((Logger) LoggerFactory.getLogger(name)).setLevel(level));
        wireLevelsBefore.clear();

        List<String> logLines = new ArrayList<>();
        for (ILoggingEvent event : new ArrayList<>(logs.list)) {
            logLines.add(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                logLines.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
            logLines.add(String.valueOf(event.getMDCPropertyMap()));
        }
        List<String> forbidden = new ArrayList<>(PERSONAL);
        forbidden.addAll(storedCiphertexts());
        forbidden.add(BLIND_INDEX_KEY);
        List<String> audit = auditRows();
        List<String> batches = batchRows();
        for (String text : forbidden) {
            assertThat(RESPONSES).as("responses must not contain %s", shorten(text))
                    .noneMatch(response -> response.contains(text));
            assertThat(audit).as("audit rows must not contain %s", shorten(text))
                    .noneMatch(row -> row.contains(text));
            assertThat(batches).as("batch rows must not contain %s", shorten(text))
                    .noneMatch(row -> row.contains(text));
            assertThat(logLines).as("logs must not contain %s", shorten(text))
                    .noneMatch(line -> line.contains(text));
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

    private record Member(UUID account, UUID institution, UUID family, String token) {
    }

    // --- Files ---

    private static String id(int number) {
        return String.format("0801-2012-%05d", number);
    }

    /** One row: every tenth student has a document; the references are REF-SECRETA-n. */
    private static String line(int n) {
        return "REF-SECRETA-" + n + ",NombreSecreto" + n + ",ApellidoSecreto" + n + ",2012-05-20,"
                + (n % 10 == 0 ? id(n) : "") + "," + (n % 2 == 0 ? "F" : "M") + "\n";
    }

    /** {@code count} rows without defect. */
    private static String clean(int count) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int n = 1; n <= count; n++) {
            csv.append(line(n));
        }
        return csv.toString();
    }

    /**
     * 100 rows with 5 defects, one of each kind: the first name of row 10 is missing (line 11), the birth date of row
     * 25 does not exist (line 26), the one of row 40 is in the future (line 41), row 55 repeats the reference of row
     * 54 (line 56) and the document of row 70 is not a document (line 71).
     */
    private static String withFiveDefects() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int n = 1; n <= 100; n++) {
            String row = line(n);
            switch (n) {
                case 10 -> row = row.replace("NombreSecreto10", "");
                case 25 -> row = row.replace("2012-05-20", "2012-02-30");
                case 40 -> row = row.replace("2012-05-20", "2999-01-01");
                case 55 -> row = row.replace("REF-SECRETA-55", "REF-SECRETA-54");
                case 70 -> row = row.replace(id(70), "XYZ");
                default -> { }
            }
            csv.append(row);
        }
        return csv.toString();
    }

    private static final List<List<Object>> FIVE_DEFECTS = List.of(List.of(11, "firstName", "required"),
            List.of(26, "birthDate", "invalid-date"), List.of(41, "birthDate", "future-birth-date"),
            List.of(56, "legacyId", "duplicate-in-file"), List.of(71, "nationalId", "invalid-format"));

    // --- The acceptance criterion ---

    @Test
    void anAdministratorSimulatesACsvOfAHundredWithFiveDefectsSeesExactlyThoseFiveFixesAppliesAndReapplyingCreatesNone()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        HttpResponse<String> first = simulate(admin, withFiveDefects());

        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(first.headers().allValues("Cache-Control")).containsExactly("no-store");
        JsonNode run = HarnessProcess.json(first);
        assertThat(run.propertyNames()).containsExactly("batch", "rejections", "rejectionsTruncated");
        JsonNode batch = run.get("batch");
        assertThat(batch.propertyNames()).containsExactly("id", "kind", "status", "fileSha256", "totalRows",
                "validRows", "rejectedRows", "createdRows", "skippedRows", "createdAt", "appliedAt");
        assertThat(batch.get("kind").asString()).isEqualTo("STUDENTS");
        assertThat(batch.get("status").asString()).isEqualTo("VALIDATED");
        assertThat(batch.get("totalRows").asInt()).isEqualTo(100);
        assertThat(batch.get("validRows").asInt()).isEqualTo(95);
        assertThat(batch.get("rejectedRows").asInt()).isEqualTo(5);
        assertThat(batch.get("createdRows").asInt()).isZero();
        assertThat(batch.get("skippedRows").asInt()).isZero();
        assertThat(batch.get("fileSha256").asString()).isEqualTo(sha256(withFiveDefects().getBytes(StandardCharsets.UTF_8)));
        assertThat(run.get("rejectionsTruncated").asBoolean()).isFalse();
        assertThat(rejectionsOf(run)).containsExactlyElementsOf(FIVE_DEFECTS);
        assertThat(run.get("rejections").get(0).propertyNames()).containsExactly("line", "field", "reason");
        assertThat(countOf("students_student", admin.institution())).as("a simulation writes no student").isZero();

        // The administrator corrects the file at the source and simulates it again.
        String corrected = clean(100);
        JsonNode second = HarnessProcess.json(simulate(admin, corrected));
        assertThat(second.get("rejections")).isEmpty();
        assertThat(second.get("batch").get("validRows").asInt()).isEqualTo(100);
        assertThat(countOf("students_student", admin.institution())).isZero();

        HttpResponse<String> applied = apply(admin, corrected);

        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(200);
        JsonNode result = HarnessProcess.json(applied);
        assertThat(result.get("batch").get("status").asString()).isEqualTo("APPLIED");
        assertThat(result.get("batch").get("createdRows").asInt()).isEqualTo(100);
        assertThat(result.get("batch").get("validRows").asInt()).isEqualTo(100);
        assertThat(result.get("batch").get("appliedAt").isNull()).isFalse();
        assertThat(result.get("rejections")).isEmpty();
        assertThat(countOf("students_student", admin.institution())).isEqualTo(100);

        // The students are in the registry, with the generated codes, the documents encrypted and the reference kept.
        JsonNode registry = HarnessProcess.json(unrecorded("GET", STUDENTS + "?size=200&sort=studentCode,asc",
                admin.token(), null));
        assertThat(registry.get("total").asLong()).isEqualTo(100);
        assertThat(registry.get("items").get(0).get("studentCode").asString()).isEqualTo("E-000001");
        assertThat(registry.get("items").get(99).get("studentCode").asString()).isEqualTo("E-000100");
        long withDocument = 0;
        for (JsonNode student : registry.get("items")) {
            withDocument += student.get("hasNationalId").asBoolean() ? 1 : 0;
        }
        assertThat(withDocument).isEqualTo(10);
        assertThat(externalRefsOf(admin.institution())).hasSize(100).contains("REF-SECRETA-1", "REF-SECRETA-100");
        assertThat(storedDocuments(admin.institution())).hasSize(10).allSatisfy(stored ->
                assertThat(stored[0]).startsWith("v1:").doesNotContain("0801").doesNotContain("080120120"));

        // Applying the same file again creates none.
        JsonNode again = HarnessProcess.json(apply(admin, corrected));
        assertThat(again.get("batch").get("createdRows").asInt()).isZero();
        assertThat(again.get("batch").get("skippedRows").asInt()).isEqualTo(100);
        assertThat(again.get("batch").get("rejectedRows").asInt()).isZero();
        assertThat(again.get("rejections")).hasSize(100);
        assertThat(rejectionsOf(again)).allSatisfy(rejection -> {
            assertThat(rejection.get(1)).isEqualTo("legacyId");
            assertThat(rejection.get(2)).isEqualTo("already-imported");
        });
        assertThat(countOf("students_student", admin.institution())).isEqualTo(100);
    }

    @Test
    void applyingTheFileWithDefectsCreatesOnlyTheValidRowsAndReportsTheRest() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String file = withFiveDefects();
        simulate(admin, file);

        JsonNode result = HarnessProcess.json(apply(admin, file));

        assertThat(result.get("batch").get("createdRows").asInt()).isEqualTo(95);
        assertThat(result.get("batch").get("rejectedRows").asInt()).isEqualTo(5);
        assertThat(rejectionsOf(result)).containsExactlyElementsOf(FIVE_DEFECTS);
        assertThat(countOf("students_student", admin.institution())).isEqualTo(95);
        assertThat(externalRefsOf(admin.institution())).doesNotContain("REF-SECRETA-10", "REF-SECRETA-25",
                "REF-SECRETA-40", "REF-SECRETA-70").contains("REF-SECRETA-54");
    }

    @Test
    void theStudentsComeOutAsIfRegisteredByHandWithTheSameCodeEncryptedDocumentAndAuditEntry() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String file = clean(20);
        simulate(admin, file);
        apply(admin, file);

        List<String> actions = auditActionsOf(admin.institution());
        assertThat(actions.stream().filter("students.student.created"::equals).count()).isEqualTo(20);
        assertThat(actions).contains("imports.batch.simulated", "imports.batch.applied");
        for (String[] entry : studentEntriesOf(admin.institution())) {
            JsonNode after = HarnessProcess.json(entry[4]);
            assertThat(after.propertyNames()).containsExactlyInAnyOrder("studentCode", "status", "hasNationalId");
            assertThat(after.get("studentCode").asString()).matches("E-\\d{6}");
            assertThat(after.get("status").asString()).isEqualTo("ACTIVE");
        }
        String[] document = storedDocuments(admin.institution()).getFirst();
        assertThat(document[0]).startsWith("v1:");
        assertThat(document[1]).matches("v1:[0-9a-f]{64}");

        // A hand registration of a student with one of the imported documents is refused as a duplicate.
        HttpResponse<String> manual = unrecorded("POST", STUDENTS, admin.token(),
                "{\"firstName\":\"Otro\",\"lastName\":\"Nombre\",\"birthDate\":\"2012-05-20\",\"sex\":\"F\","
                        + "\"nationalId\":\"0801201200010\"}");
        assertProblem(manual, 409, "duplicate-national-id");
    }

    // --- Apply needs a simulation of the same file ---

    @Test
    void aFileThatWasNeverSimulatedIs409ImportNotSimulatedAndLeavesNothing() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertProblem(apply(admin, clean(3)), 409, "import-not-simulated");

        assertThat(countOf("students_student", admin.institution())).isZero();
        assertThat(countOf("imports_batch", admin.institution())).isZero();
        assertThat(auditActionsOf(admin.institution())).isEmpty();
    }

    @Test
    void aFileModifiedBetweenSimulatingAndApplyingIs409ImportFileChangedAndLeavesNothing() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        simulate(admin, clean(5));

        assertProblem(apply(admin, clean(6)), 409, "import-file-changed");
        assertProblem(apply(admin, clean(5).replace("2012-05-20", "2012-05-21")), 409, "import-file-changed");

        assertThat(countOf("students_student", admin.institution())).isZero();
        assertThat(countOf("imports_batch", admin.institution())).isEqualTo(1);
    }

    @Test
    void aSimulationByAnotherAccountOfTheSameInstitutionDoesNotAuthorizeTheApplication() throws SQLException {
        Member simulator = member(StaffRole.ADMINISTRATOR.code());
        Member other = sameInstitution(simulator.institution(), StaffRole.ADMINISTRATOR.code());
        simulate(simulator, clean(3));

        assertProblem(apply(other, clean(3)), 409, "import-not-simulated");
        assertThat(countOf("students_student", simulator.institution())).isZero();
        assertThat(apply(simulator, clean(3)).statusCode()).isEqualTo(200);
    }

    @Test
    void aSimulationOfAnotherInstitutionDoesNotAuthorizeTheApplicationAndTheDataStaysApart() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        String file = clean(4);
        simulate(theirs, file);

        assertProblem(apply(mine, file), 409, "import-not-simulated");

        simulate(mine, file);
        assertThat(apply(mine, file).statusCode()).isEqualTo(200);
        assertThat(countOf("students_student", mine.institution())).isEqualTo(4);
        assertThat(countOf("students_student", theirs.institution())).as("nothing crossed").isZero();
        // The same references in the other institution are free: the reference is unique per institution.
        assertThat(apply(theirs, file).statusCode()).isEqualTo(200);
        assertThat(countOf("students_student", theirs.institution())).isEqualTo(4);
        JsonNode theirBatches = HarnessProcess.json(get(BATCHES, theirs));
        assertThat(theirBatches.get("total").asLong()).isEqualTo(2);
        assertThat(HarnessProcess.json(get(BATCHES, mine)).get("total").asLong()).isEqualTo(2);
    }

    // --- Idempotence ---

    @Test
    void aCorrectedFileSkipsTheRowsAlreadyImportedAndCreatesOnlyTheNewOnes() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String first = clean(10);
        simulate(admin, first);
        apply(admin, first);
        String extended = clean(15);
        JsonNode simulation = HarnessProcess.json(simulate(admin, extended));
        assertThat(simulation.get("batch").get("validRows").asInt()).isEqualTo(5);
        assertThat(simulation.get("batch").get("skippedRows").asInt()).isEqualTo(10);

        JsonNode result = HarnessProcess.json(apply(admin, extended));

        assertThat(result.get("batch").get("createdRows").asInt()).isEqualTo(5);
        assertThat(result.get("batch").get("skippedRows").asInt()).isEqualTo(10);
        assertThat(countOf("students_student", admin.institution())).isEqualTo(15);
    }

    @Test
    void aDocumentAStudentAlreadyHoldsIsRejectedAsDuplicateNationalIdAndTheRestIsStillImported() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        assertThat(unrecorded("POST", STUDENTS, admin.token(), "{\"firstName\":\"Previo\",\"lastName\":\"Existente\","
                + "\"birthDate\":\"2010-01-01\",\"sex\":\"F\",\"nationalId\":\"0801-2012-00010\"}").statusCode())
                .isEqualTo(201);
        String file = clean(12);
        JsonNode simulation = HarnessProcess.json(simulate(admin, file));
        assertThat(rejectionsOf(simulation)).containsExactly(List.of(11, "nationalId", "duplicate-national-id"));

        JsonNode result = HarnessProcess.json(apply(admin, file));

        assertThat(result.get("batch").get("createdRows").asInt()).isEqualTo(11);
        assertThat(result.get("batch").get("rejectedRows").asInt()).isEqualTo(1);
        assertThat(countOf("students_student", admin.institution())).isEqualTo(12);
    }

    @Test
    void aRepeatedDocumentInTheFileIsDuplicateInFileOnTheLaterRowEvenWithOtherHyphens() {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String file = HEADER + "REF-SECRETA-1,NombreSecreto1,ApellidoSecreto1,2012-05-20,0801-2012-77777,F\n"
                + "REF-SECRETA-2,NombreSecreto2,ApellidoSecreto2,2012-05-20,0801201277777,M\n";

        assertThat(rejectionsOf(HarnessProcess.json(simulate(admin, file))))
                .containsExactly(List.of(3, "nationalId", "duplicate-in-file"));
    }

    @Test
    void twoApplicationsOfTheSameFileAtTheSameTimeCreateEachStudentOnce() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String file = clean(150);
        simulate(admin, file);

        List<HttpResponse<String>> answers = inParallel(() -> apply(admin, file), () -> apply(admin, file),
                () -> apply(admin, file));

        int created = 0;
        for (HttpResponse<String> answer : answers) {
            assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
            JsonNode batch = HarnessProcess.json(answer).get("batch");
            assertThat(batch.get("createdRows").asInt() + batch.get("skippedRows").asInt()
                    + batch.get("rejectedRows").asInt()).isEqualTo(150);
            created += batch.get("createdRows").asInt();
        }
        assertThat(created).as("each student was created by exactly one of the applications").isEqualTo(150);
        assertThat(countOf("students_student", admin.institution())).isEqualTo(150);
        assertThat(externalRefsOf(admin.institution())).hasSize(150).doesNotHaveDuplicates();
        assertThat(codesOf(admin.institution())).hasSize(150).doesNotHaveDuplicates();
        assertThat(auditActionsOf(admin.institution()).stream().filter("students.student.created"::equals).count())
                .as("one entry per student: a rolled back block leaves none").isEqualTo(150);
    }

    // --- The file at the edge ---

    @Test
    void aSemicolonFileWithABomAndQuotedCellsWithLineBreaksIsReadAndTheLinesAreCountedRight() {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String csv = "\uFEFFlegacyId;firstName;lastName;birthDate\n"
                + "REF-SECRETA-1;\"NombreSecreto\n1\";\"Apellido; \"\"Secreto\"\"\";2012-05-20\n"
                + "REF-SECRETA-2;NombreSecreto2;ApellidoSecreto2;2012-13-45\n";

        JsonNode run = HarnessProcess.json(simulate(admin, csv));

        // Row 1 starts on line 2 and its cell breaks onto line 3, so the second row is on line 4. The line break
        // inside the first name is a control character, which the registry refuses; the date of row 2 does not exist.
        assertThat(rejectionsOf(run)).containsExactly(List.of(2, "firstName", "invalid-format"),
                List.of(4, "birthDate", "invalid-date"));
        assertThat(run.get("batch").get("totalRows").asInt()).isEqualTo(2);
    }

    @Test
    void aFileWithOnlyTheHeaderAnEmptyFileAndAFileWithoutRowsAreRefused() {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertErrors(simulate(admin, ""), "file", "empty-file");
        assertErrors(simulate(admin, "   \n\n"), "file", "empty-file");
        assertErrors(simulate(admin, HEADER), "file", "no-rows");
    }

    @Test
    void aWrongHeaderIsRefusedWithTheMissingColumnsByNameAndTheUnknownOnesByPositionNeverByText() {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        HttpResponse<String> missing = simulate(admin, "legacyId,firstName,nationalId\nREF-SECRETA-1,NombreSecreto1,x\n");
        assertErrors(missing, "lastName", "missing-column", "birthDate", "missing-column");

        HttpResponse<String> unknown = simulate(admin,
                "legacyId,NombreSecreto,lastName,birthDate,firstName\nREF-SECRETA-1,a,b,2012-05-20,c\n");
        assertErrors(unknown, "columns[1]", "unknown-column");
        assertThat(unknown.body()).doesNotContain("NombreSecreto");

        HttpResponse<String> noHeader = simulate(admin, "REF-SECRETA-1,NombreSecreto1,ApellidoSecreto1,2012-05-20\n");
        assertThat(noHeader.statusCode()).isEqualTo(400);
        assertThat(noHeader.body()).doesNotContain("SECRETA").doesNotContain("Secreto");
    }

    @Test
    void aMalformedCsvIsRefusedWithoutEchoingIt() {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertErrors(simulate(admin, HEADER + "REF-SECRETA-1,\"NombreSecreto1,x,2012-05-20,,F\n"), "file", "malformed-csv");
        assertErrors(simulate(admin, HEADER + "REF-SECRETA-1,Nombre\"Secreto,x,2012-05-20,,F\n"), "file", "malformed-csv");
    }

    @Test
    void aFileThatIsNotUtf8IsRefused() {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        byte[] latin1 = (HEADER + "REF-SECRETA-1,Mu\u00f1oz,x,2012-05-20,,F\n").getBytes(StandardCharsets.ISO_8859_1);

        assertErrors(post(SIMULATE, admin.token(), multipart("estudiantes.csv", latin1)), "file", "invalid-encoding");
    }

    @Test
    void tenThousandRowsAreAcceptedAndTenThousandAndOneAreRefused() {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        JsonNode run = HarnessProcess.json(simulate(admin, clean(10_000)));
        assertThat(run.get("batch").get("totalRows").asInt()).isEqualTo(10_000);
        assertThat(run.get("batch").get("validRows").asInt()).isEqualTo(10_000);

        assertErrors(simulate(admin, clean(10_001)), "file", "too-many-rows");
    }

    @Test
    void aFileOfFiveMegabytesAndOneByteIsRefusedAtTheEdgeBeforeItIsRead() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        byte[] huge = new byte[5 * 1024 * 1024 + 1];
        Arrays.fill(huge, (byte) 'a');

        HttpResponse<String> simulation = post(SIMULATE, admin.token(), multipart("estudiantes.csv", huge));
        HttpResponse<String> application = post(APPLY, admin.token(), multipart("estudiantes.csv", huge));

        assertProblem(simulation, 400, "validation-failed");
        assertProblem(application, 400, "validation-failed");
        assertThat(countOf("imports_batch", admin.institution())).isZero();
    }

    @Test
    void aRequestWithoutTheFilePartOrNotMultipartIsRefused() {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String boundary = "----otherpart";
        byte[] otherPart = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"other\"\r\n\r\nx\r\n--"
                + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        assertProblem(post(SIMULATE, admin.token(), new Body("multipart/form-data; boundary=" + boundary, otherPart)),
                400, "validation-failed");
        assertProblem(post(SIMULATE, admin.token(), new Body("application/json", "{}".getBytes(StandardCharsets.UTF_8))),
                415, "unsupported-media-type");
    }

    @Test
    void aFormulaIsJustTextTheRegistryStoresAndNeverEvaluates() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String file = HEADER + "\"=HYPERLINK(\"\"http://x\"\")\",\"=1+1\",\"@SUM(A1)\",2012-05-20,,F\n";
        simulate(admin, file);

        JsonNode result = HarnessProcess.json(apply(admin, file));

        assertThat(result.get("batch").get("createdRows").asInt()).isEqualTo(1);
        assertThat(externalRefsOf(admin.institution())).containsExactly("=HYPERLINK(\"http://x\")");
    }

    // --- Authorization ---

    @Test
    void aRoleWithoutStudentsCreateGets403OnBothPostsAndLeavesNoBatchNoStudentAndNoEntry() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Member cashier = sameInstitution(admin.institution(), StaffRole.CASHIER.code());
        String file = clean(3);
        simulate(admin, file);
        int batches = (int) countOf("imports_batch", admin.institution());
        long entries = importAndStudentEntries(admin.institution());

        assertProblem(simulate(cashier, file), 403, "forbidden");
        assertProblem(apply(cashier, file), 403, "forbidden");

        assertThat(countOf("imports_batch", admin.institution())).isEqualTo(batches);
        assertThat(countOf("students_student", admin.institution())).isZero();
        assertThat(importAndStudentEntries(admin.institution())).as("no entry of the imports or the registry")
                .isEqualTo(entries);
    }

    @Test
    void theHistoryNeedsStudentsReadAndAnyOtherRoleThatReadsStudentsCanListIt() {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Member cashier = sameInstitution(admin.institution(), StaffRole.CASHIER.code());
        simulate(admin, clean(2));

        assertThat(get(BATCHES, cashier).statusCode()).isEqualTo(200);
        assertThat(HarnessProcess.json(get(BATCHES, cashier)).get("total").asLong()).isEqualTo(1);
    }

    @Test
    void withoutASessionTheRoutesAre401AndWithoutTheOriginThePostsAre403() {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertProblem(send("POST", SIMULATE, null, multipart("estudiantes.csv", clean(1).getBytes(StandardCharsets.UTF_8)),
                true), 401, "authentication-required");
        assertProblem(send("GET", BATCHES, null, null, true), 401, "authentication-required");
        assertProblem(send("POST", SIMULATE, admin.token(),
                multipart("estudiantes.csv", clean(1).getBytes(StandardCharsets.UTF_8)), false), 403, "csrf-invalid");
    }

    // --- History ---

    @Test
    void theHistoryIsNewestFirstPaginatedWithTheServerLimitAndOnlyCounts() {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        simulate(admin, clean(1));
        simulate(admin, clean(2));
        simulate(admin, clean(3));
        apply(admin, clean(3));

        JsonNode page = HarnessProcess.json(get(BATCHES + "?size=2", admin));
        JsonNode next = HarnessProcess.json(get(BATCHES + "?size=2&page=1", admin));

        assertThat(page.propertyNames()).containsExactly("items", "total", "page", "size");
        assertThat(page.get("total").asLong()).isEqualTo(4);
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("items").get(0).get("status").asString()).isEqualTo("APPLIED");
        assertThat(page.get("items").get(0).propertyNames()).containsExactly("id", "kind", "status", "fileSha256",
                "totalRows", "validRows", "rejectedRows", "createdRows", "skippedRows", "createdAt", "appliedAt");
        assertThat(page.get("items").get(1).get("totalRows").asInt()).isEqualTo(3);
        assertThat(next.get("items").get(0).get("totalRows").asInt()).isEqualTo(2);
        assertThat(next.get("items").get(1).get("totalRows").asInt()).isEqualTo(1);
        assertProblem(get(BATCHES + "?size=101", admin), 400, "validation-failed");
        assertProblem(get(BATCHES + "?size=0", admin), 400, "validation-failed");
        assertProblem(get(BATCHES + "?page=-1", admin), 400, "validation-failed");
    }

    // --- Audit ---

    @Test
    void theAuditEntriesOfTheBatchesCarryCountsAndTheFingerprintAndTheActor() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String file = withFiveDefects();
        simulate(admin, file);
        apply(admin, file);

        List<String[]> entries = batchEntriesOf(admin.institution());

        assertThat(entries).extracting(entry -> entry[0]).containsExactly("imports.batch.simulated",
                "imports.batch.applied");
        String sha = sha256(file.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 2; i++) {
            JsonNode after = HarnessProcess.json(entries.get(i)[2]);
            assertThat(after.propertyNames()).containsExactlyInAnyOrder("kind", "status", "fileSha256", "totalRows",
                    "validRows", "rejectedRows", "createdRows", "skippedRows");
            assertThat(after.get("kind").asString()).isEqualTo("STUDENTS");
            assertThat(after.get("fileSha256").asString()).isEqualTo(sha);
            assertThat(after.get("totalRows").asInt()).isEqualTo(100);
            assertThat(after.get("validRows").asInt()).isEqualTo(95);
            assertThat(after.get("rejectedRows").asInt()).isEqualTo(5);
            assertThat(after.get("skippedRows").asInt()).isZero();
        }
        assertThat(HarnessProcess.json(entries.get(0)[2]).get("status").asString()).isEqualTo("VALIDATED");
        assertThat(HarnessProcess.json(entries.get(0)[2]).get("createdRows").asInt()).isZero();
        assertThat(HarnessProcess.json(entries.get(1)[2]).get("status").asString()).isEqualTo("APPLIED");
        assertThat(HarnessProcess.json(entries.get(1)[2]).get("createdRows").asInt()).isEqualTo(95);
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry[1]).isEqualTo(admin.account().toString());
            assertThat(entry[3]).isNull();
        });
    }

    // --- Rate limit ---

    @Test
    void simulateAndApplyAreRateLimitedPerAddressWithRetryAfterAndTheHistoryAndAnotherAddressAreNot() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String file = clean(2);
        try (ConfigurableApplicationContext limited = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(),
                "confia_admin_app", TEST_PASSWORD, keys, Map.of("confia.web.trusted-proxies", "127.0.0.1,::1",
                        "confia.crypto.blind-index.current-version", "v1",
                        "confia.crypto.blind-index.keys.v1", BLIND_INDEX_KEY,
                        RATE_LIMIT + "admin-import.request-limit", "2",
                        RATE_LIMIT + "admin-import-account.request-limit", "10000"))) {
            int limitedPort = ((WebServerApplicationContext) limited).getWebServer().getPort();
            String noisy = "203.0.113.30";
            String quiet = "203.0.113.31";
            byte[] body = file.getBytes(StandardCharsets.UTF_8);

            assertThat(sendFrom(limitedPort, noisy, SIMULATE, admin.token(), multipart("e.csv", body)).statusCode())
                    .isEqualTo(200);
            assertThat(sendFrom(limitedPort, noisy, APPLY, admin.token(), multipart("e.csv", body)).statusCode())
                    .isEqualTo(200);
            HttpResponse<String> refused = sendFrom(limitedPort, noisy, SIMULATE, admin.token(), multipart("e.csv", body));
            assertProblem(refused, 429, "too-many-requests");
            assertThat(refused.headers().firstValue("Retry-After")).hasValueSatisfying(seconds ->
                    assertThat(Long.parseLong(seconds)).isBetween(1L, 3600L));
            assertProblem(sendFrom(limitedPort, noisy, APPLY, admin.token(), multipart("e.csv", body)), 429,
                    "too-many-requests");

            assertThat(sendFrom(limitedPort, noisy, BATCHES, admin.token(), null).statusCode())
                    .as("the history uses the read policy").isEqualTo(200);
            assertThat(sendFrom(limitedPort, quiet, SIMULATE, admin.token(), multipart("e.csv", body)).statusCode())
                    .as("another address").isEqualTo(200);
            assertThat(countOf("students_student", admin.institution())).as("the refused runs changed nothing")
                    .isEqualTo(2);
        }
    }

    @Test
    void simulateAndApplyAreAlsoRateLimitedPerAccountSoTwoAddressesOfOneAccountShareTheQuota() throws Exception {
        Member heavy = member(StaffRole.ADMINISTRATOR.code());
        Member other = member(StaffRole.ADMINISTRATOR.code());
        byte[] body = clean(2).getBytes(StandardCharsets.UTF_8);
        try (ConfigurableApplicationContext limited = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(),
                "confia_admin_app", TEST_PASSWORD, keys, Map.of("confia.web.trusted-proxies", "127.0.0.1,::1",
                        "confia.crypto.blind-index.current-version", "v1",
                        "confia.crypto.blind-index.keys.v1", BLIND_INDEX_KEY,
                        RATE_LIMIT + "admin-import.request-limit", "10000",
                        RATE_LIMIT + "admin-import-account.request-limit", "1"))) {
            int limitedPort = ((WebServerApplicationContext) limited).getWebServer().getPort();

            assertThat(sendFrom(limitedPort, "203.0.113.40", SIMULATE, heavy.token(), multipart("e.csv", body))
                    .statusCode()).isEqualTo(200);
            HttpResponse<String> refused = sendFrom(limitedPort, "203.0.113.41", SIMULATE, heavy.token(),
                    multipart("e.csv", body));
            assertProblem(refused, 429, "too-many-requests");
            assertThat(refused.headers().firstValue("Retry-After")).isPresent();
            assertThat(sendFrom(limitedPort, "203.0.113.40", SIMULATE, other.token(), multipart("e.csv", body))
                    .statusCode()).as("another account from the same address").isEqualTo(200);
            assertThat(sendFrom(limitedPort, "203.0.113.40", BATCHES, heavy.token(), null).statusCode())
                    .isEqualTo(200);
        }
    }

    // --- Bodies ---

    private record Body(String contentType, byte[] bytes) {
    }

    private static Body multipart(String filename, byte[] content) {
        String boundary = "----confia" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: text/csv\r\n\r\n");
        body.writeBytes(content);
        write(body, "\r\n--" + boundary + "--\r\n");
        return new Body("multipart/form-data; boundary=" + boundary, body.toByteArray());
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(com.confia.shared.security.Digests.sha256(bytes));
    }

    /** The rejections of a run as (line, field, reason). */
    private static List<List<Object>> rejectionsOf(JsonNode run) {
        List<List<Object>> rejections = new ArrayList<>();
        for (JsonNode rejection : run.get("rejections")) {
            rejections.add(List.of(rejection.get("line").asInt(), rejection.get("field").asString(),
                    rejection.get("reason").asString()));
        }
        return rejections;
    }

    /** A {@code 400 validation-failed} whose list {@code errors} is exactly these (field, reason) pairs. */
    private static void assertErrors(HttpResponse<String> response, String... fieldsAndReasons) {
        JsonNode body = assertProblem(response, 400, "validation-failed");
        List<String> actual = new ArrayList<>();
        for (JsonNode error : body.get("errors")) {
            actual.add(error.get("field").asString());
            actual.add(error.get("reason").asString());
        }
        List<String> expected = Arrays.asList(fieldsAndReasons);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    // --- Seeding ---

    private Member member(String roleCode) {
        UUID institution = UUID.randomUUID();
        try {
            seedInstitution(institution);
            return sameInstitution(institution, roleCode);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Member sameInstitution(UUID institution, String roleCode) {
        try {
            UUID account = UUID.randomUUID();
            StaffRoleFixtures.committed(institution, account, roleCode);
            UUID family = seedLiveFamily(institution, account);
            String token = new AccessTokenIssuer(jws, Clock.fixed(now(), ZoneOffset.UTC))
                    .issueAccess(account, institution, family,
                            Set.of(AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD))
                    .compact();
            return new Member(account, institution, family, token);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
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

    private static List<String> column(String sql, UUID institution) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    values.add(result.getString(1));
                }
            }
        }
        return values;
    }

    private static List<String> externalRefsOf(UUID institution) throws SQLException {
        return column("select external_ref from students_student where institution_id = ? "
                + "and external_ref is not null order by external_ref", institution);
    }

    private static List<String> codesOf(UUID institution) throws SQLException {
        return column("select student_code from students_student where institution_id = ?", institution);
    }

    private static List<String[]> storedDocuments(UUID institution) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select national_id_encrypted, national_id_blind_index from students_student
                         where institution_id = ? and national_id_encrypted is not null order by student_code
                        """)) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new String[] {result.getString(1), result.getString(2)});
                }
            }
        }
        return rows;
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
                if (result.getString(2) != null) {
                    values.add(result.getString(2).substring(result.getString(2).indexOf(':') + 1));
                }
            }
        }
        return values;
    }

    private static List<String> auditRows() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("select a::text from shared_audit_log a");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }

    private static List<String> batchRows() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("select b::text from imports_batch b");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(result.getString(1));
            }
        }
        return rows;
    }

    private static List<String> auditActionsOf(UUID institution) throws SQLException {
        return column("select action from shared_audit_log where institution_id = ? order by id", institution);
    }

    private static long importAndStudentEntries(UUID institution) throws SQLException {
        return auditActionsOf(institution).stream()
                .filter(action -> action.startsWith("imports.") || action.startsWith("students.")).count();
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

    /** action, actor, after, before of every entry of the imports, oldest first. */
    private static List<String[]> batchEntriesOf(UUID institution) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select action, actor_id::text, after_value::text, before_value::text
                          from shared_audit_log
                         where institution_id = ? and action like 'imports.%' order by id
                        """)) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new String[] {result.getString(1), result.getString(2), result.getString(3),
                            result.getString(4)});
                }
            }
        }
        return rows;
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

    private static HttpResponse<String> simulate(Member member, String csv) {
        return post(SIMULATE, member.token(), multipart("estudiantes.csv", csv.getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpResponse<String> apply(Member member, String csv) {
        return post(APPLY, member.token(), multipart("estudiantes.csv", csv.getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpResponse<String> get(String path, Member member) {
        return send("GET", path, member.token(), null, true);
    }

    private static HttpResponse<String> post(String path, String token, Body body) {
        return send("POST", path, token, body, true);
    }

    private static HttpResponse<String> send(String method, String path, String token, Body body, boolean origin) {
        return execute(request(port, null, method, path, token, body, origin), true);
    }

    private static HttpResponse<String> sendFrom(int targetPort, String address, String path, String token, Body body) {
        return execute(request(targetPort, address, body == null ? "GET" : "POST", path, token, body, true), true);
    }

    /** A request to the registry, whose answers legitimately carry names and are not scanned. */
    private static HttpResponse<String> unrecorded(String method, String path, String token, String json) {
        return execute(request(port, null, method, path, token,
                json == null ? null : new Body("application/json", json.getBytes(StandardCharsets.UTF_8)), true),
                false);
    }

    private static HttpRequest request(int targetPort, String address, String method, String path, String token,
            Body body, boolean origin) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path))
                .timeout(Duration.ofSeconds(120))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body.bytes()));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (address != null) {
            request.header("X-Forwarded-For", address);
        }
        if (body != null) {
            request.header("Content-Type", body.contentType());
        }
        if (!method.equals("GET") && origin) {
            request.header("Origin", AdminProcessLauncher.ALLOWED_ORIGIN);
        }
        return request.build();
    }

    private static HttpResponse<String> execute(HttpRequest request, boolean record) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (record) {
                RESPONSES.add(response.body());
                RESPONSES.add(response.headers().map().toString());
            }
            return response;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

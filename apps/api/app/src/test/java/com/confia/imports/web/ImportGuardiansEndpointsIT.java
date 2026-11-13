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
import java.util.Collections;
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

/**
 * The routes of the guardians import and of the links import on the real administrative process against a real
 * PostgreSQL with the least-privilege role of the application (docs/cortes/imports-guardians.md). It covers the
 * acceptance criterion (100 students imported, 80 guardians and 120 links in 60 sets with 6 defective sets: exactly
 * those sets rejected, corrected, applied, every student with links that add up to 100, applying again changes
 * nothing), the stored document that only the application finds, two and three simultaneous applications, the isolation
 * between institutions, the roles without the permission (a {@code 403} that leaves no batch and no record), the history
 * with its kind filter, the strict rate limits, and that no response, audit row, batch row or log line carries a name, a
 * reference, a document, an RTN, a contact datum or a ciphertext.
 */
class ImportGuardiansEndpointsIT extends CommittingPostgresIntegrationTest {

    private static final String STUDENTS_SIMULATE = "/api/v1/imports/students/simulate";
    private static final String STUDENTS_APPLY = "/api/v1/imports/students/apply";
    private static final String GUARDIANS_SIMULATE = "/api/v1/imports/guardians/simulate";
    private static final String GUARDIANS_APPLY = "/api/v1/imports/guardians/apply";
    private static final String LINKS_SIMULATE = "/api/v1/imports/guardian-links/simulate";
    private static final String LINKS_APPLY = "/api/v1/imports/guardian-links/apply";
    private static final String BATCHES = "/api/v1/imports";
    private static final String GUARDIANS_HEADER = "legacyId,firstName,lastName,nationalId,rtn,email,phone,address,"
            + "preferredChannel\n";
    private static final String LINKS_HEADER = "studentLegacyId,guardianLegacyId,relationship,"
            + "financialResponsibilityPercent,isPrimaryPayer,isBillingRecipient\n";
    private static final String RATE_LIMIT = "confia.web.rate-limit.";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final String BLIND_INDEX_KEY = randomBase64Key();
    /** Fragments of the personal values of every file of these tests: they appear nowhere but in the registries. */
    private static final List<String> PERSONAL = List.of("EST-REF-", "EstNombreSecreto", "EstApellidoSecreto",
            "ENC-REF-", "EncNombreSecreto", "EncApellidoSecreto", "DireccionSecreta", "secreto", "0801-1980-",
            "08011980", "9999-00");

    private static KeyPair keys;
    private static CompactJws jws;
    private static ConfigurableApplicationContext process;
    private static int port;

    private static final List<String> RESPONSES = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> WIRE_LOGGERS = List.of("org.apache.coyote", "org.apache.tomcat",
            "org.postgresql", "org.jooq", "com.zaxxer.hikari");

    private ListAppender<ILoggingEvent> logs;
    private Level rootLevelBefore;
    private final Map<String, Level> wireLevelsBefore = new HashMap<>();

    @BeforeAll
    static void startTheProcess() throws NoSuchAlgorithmException {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        jws = new CompactJws(SigningKeyRing.of(SigningKey.signing(
                AdminProcessLauncher.signingKid(), keys.getPublic(), keys.getPrivate())));
        process = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(), "confia_admin_app", TEST_PASSWORD,
                keys, Map.of("confia.crypto.blind-index.current-version", "v1",
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
     * Whatever a test did, no import response, audit row, batch row or log line carries a name, a reference, a document,
     * an RTN, an e-mail, a phone or an address of the files, nor the ciphertext or the blind index of a stored value.
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
        forbidden.addAll(storedSecrets());
        forbidden.add(BLIND_INDEX_KEY);
        List<String> audit = rows("select a::text from shared_audit_log a");
        List<String> batches = rows("select b::text from imports_batch b");
        for (String text : forbidden) {
            String shown = text.length() > 12 ? text.substring(0, 12) + "..." : text;
            assertThat(RESPONSES).as("responses must not contain %s", shown).noneMatch(r -> r.contains(text));
            assertThat(audit).as("audit rows must not contain %s", shown).noneMatch(r -> r.contains(text));
            assertThat(batches).as("batch rows must not contain %s", shown).noneMatch(r -> r.contains(text));
            assertThat(logLines).as("logs must not contain %s", shown).noneMatch(l -> l.contains(text));
        }
    }

    private static String randomBase64Key() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return java.util.Base64.getEncoder().encodeToString(key);
    }

    private record Member(UUID account, UUID institution, String token) {
    }

    // --- Files ---

    private static String studentsFile(int count) {
        StringBuilder csv = new StringBuilder("legacyId,firstName,lastName,birthDate\n");
        for (int n = 1; n <= count; n++) {
            csv.append("EST-REF-").append(n).append(",EstNombreSecreto").append(n).append(",EstApellidoSecreto")
                    .append(n).append(",2012-05-20\n");
        }
        return csv.toString();
    }

    private static String guardianLine(int n) {
        String documents = n % 10 == 0 ? String.format("0801-1980-%05d,0801-1980-%06d", n, n) : ",";
        return "ENC-REF-" + n + ",EncNombreSecreto" + n + ",EncApellidoSecreto" + n + "," + documents + ",enc" + n
                + ".secreto@example.org,9999-00" + String.format("%02d", n % 100) + ",DireccionSecreta" + n
                + ",EMAIL\n";
    }

    private static String guardiansFile(int count) {
        StringBuilder csv = new StringBuilder(GUARDIANS_HEADER);
        for (int n = 1; n <= count; n++) {
            csv.append(guardianLine(n));
        }
        return csv.toString();
    }

    private static String linkRow(int student, int guardian, String percent, String primary, String billing) {
        return "EST-REF-" + student + ",ENC-REF-" + guardian + ",MOTHER," + percent + "," + primary + "," + billing
                + "\n";
    }

    /**
     * 60 students with two guardians each (60 and 40), 120 rows, the rows of the student i on the lines 2i and 2i+1.
     * With {@code defective}, the sets of the students 1 to 6 are wrong: 90, 99.99, 100.01, two primary payers, a
     * guardian twice and a guardian that is not imported.
     */
    private static String linksFile(boolean defective) {
        StringBuilder csv = new StringBuilder(LINKS_HEADER);
        for (int i = 1; i <= 60; i++) {
            int first = (2 * i - 2) % 80 + 1;
            int second = (2 * i - 1) % 80 + 1;
            String p1 = "60.00";
            String p2 = "40.00";
            String primary2 = "false";
            int g2 = second;
            if (defective) {
                switch (i) {
                    case 1 -> p2 = "30.00";
                    case 2 -> {
                        p1 = "50.00";
                        p2 = "49.99";
                    }
                    case 3 -> {
                        p1 = "50.00";
                        p2 = "50.01";
                    }
                    case 4 -> primary2 = "true";
                    case 5 -> g2 = first;
                    case 6 -> g2 = 999;
                    default -> { }
                }
            }
            csv.append(linkRow(i, first, p1, "true", "true"));
            csv.append(linkRow(i, g2, p2, primary2, "false"));
        }
        return csv.toString();
    }

    // --- The acceptance criterion ---

    @Test
    void anAdministratorImportsStudentsGuardiansAndLinksSeesExactlyTheSixDefectiveSetsFixesAppliesAndReapplyingChangesNothing()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        assertThat(run(STUDENTS_SIMULATE, admin, studentsFile(100)).statusCode()).isEqualTo(200);
        assertThat(batchOf(run(STUDENTS_APPLY, admin, studentsFile(100))).get("createdRows").asInt()).isEqualTo(100);

        // The guardians: 80 simulated, applied, and applying them again skips them all.
        String guardians = guardiansFile(80);
        JsonNode guardiansRun = json(run(GUARDIANS_SIMULATE, admin, guardians));
        assertThat(guardiansRun.get("batch").get("kind").asString()).isEqualTo("GUARDIANS");
        assertThat(guardiansRun.get("batch").get("validRows").asInt()).isEqualTo(80);
        assertThat(guardiansRun.get("batch").get("createdRows").asInt()).isZero();
        assertThat(countOf("guardians_guardian", admin.institution())).as("the simulation writes nothing").isZero();
        JsonNode guardiansApplied = json(run(GUARDIANS_APPLY, admin, guardians));
        assertThat(guardiansApplied.get("batch").get("createdRows").asInt()).isEqualTo(80);
        assertThat(guardiansApplied.get("batch").get("status").asString()).isEqualTo("APPLIED");
        assertThat(countOf("guardians_guardian", admin.institution())).isEqualTo(80);
        assertThat(column("select guardian_code from guardians_guardian where institution_id = ? "
                + "order by guardian_code", admin.institution())).startsWith("P-000001", "P-000002").hasSize(80);
        JsonNode guardiansAgain = json(run(GUARDIANS_APPLY, admin, guardians));
        assertThat(guardiansAgain.get("batch").get("createdRows").asInt()).isZero();
        assertThat(guardiansAgain.get("batch").get("skippedRows").asInt()).isEqualTo(80);
        assertThat(guardiansAgain.get("batch").get("rejectedRows").asInt()).isZero();
        assertThat(countOf("guardians_guardian", admin.institution())).isEqualTo(80);

        // The links, with six defective sets: exactly those twelve lines.
        JsonNode simulation = json(run(LINKS_SIMULATE, admin, linksFile(true)));
        JsonNode batch = simulation.get("batch");
        assertThat(batch.get("kind").asString()).isEqualTo("GUARDIAN_LINKS");
        assertThat(batch.get("totalRows").asInt()).isEqualTo(120);
        assertThat(batch.get("validRows").asInt()).isEqualTo(108);
        assertThat(batch.get("rejectedRows").asInt()).isEqualTo(12);
        assertThat(batch.get("skippedRows").asInt()).isZero();
        String sum = "financialResponsibilityPercent";
        assertThat(rejectionsOf(simulation)).containsExactly(List.of(2, sum, "sum-not-100"),
                List.of(3, sum, "sum-not-100"), List.of(4, sum, "sum-not-100"), List.of(5, sum, "sum-not-100"),
                List.of(6, sum, "sum-not-100"), List.of(7, sum, "sum-not-100"),
                List.of(8, "isPrimaryPayer", "multiple-primary-payer"),
                List.of(9, "isPrimaryPayer", "multiple-primary-payer"),
                List.of(10, "guardianLegacyId", "duplicate-guardian"),
                List.of(11, "guardianLegacyId", "duplicate-guardian"), List.of(12, "row", "group-rejected"),
                List.of(13, "guardianLegacyId", "unknown-guardian"));
        assertThat(countOf("guardians_guardian_student", admin.institution())).as("a simulation writes nothing")
                .isZero();

        // Applying the file registers the 54 valid sets and only those.
        JsonNode firstApplied = json(run(LINKS_APPLY, admin, linksFile(true)));
        assertThat(firstApplied.get("batch").get("createdRows").asInt()).isEqualTo(108);
        assertThat(firstApplied.get("batch").get("rejectedRows").asInt()).isEqualTo(12);
        assertThat(countOf("guardians_guardian_student", admin.institution())).isEqualTo(108);

        // Corrected: the sets already registered are skipped (never replaced), the six others are registered.
        JsonNode corrected = json(run(LINKS_SIMULATE, admin, linksFile(false)));
        assertThat(corrected.get("batch").get("validRows").asInt()).isEqualTo(12);
        assertThat(corrected.get("batch").get("skippedRows").asInt()).isEqualTo(108);
        assertThat(corrected.get("batch").get("rejectedRows").asInt()).isZero();
        assertThat(rejectionsOf(corrected)).hasSize(108).allMatch(r -> r.get(2).equals("student-already-linked"));
        JsonNode secondApplied = json(run(LINKS_APPLY, admin, linksFile(false)));
        assertThat(secondApplied.get("batch").get("createdRows").asInt()).isEqualTo(12);
        assertThat(secondApplied.get("batch").get("skippedRows").asInt()).isEqualTo(108);

        assertThat(countOf("guardians_guardian_student", admin.institution())).isEqualTo(120);
        assertThat(column("""
                select count(*)::text from (
                    select student_id from guardians_guardian_student
                     where institution_id = ? and valid_to is null
                     group by student_id having sum(financial_responsibility_percent) = 100.00) t
                """, admin.institution())).containsExactly("60");

        // Applying the files again changes nothing, and reports every set as skipped and none as an error.
        long entries = auditActionsOf(admin.institution()).stream().filter(a -> a.startsWith("guardians.")).count();
        JsonNode again = json(run(LINKS_APPLY, admin, linksFile(false)));
        assertThat(again.get("batch").get("createdRows").asInt()).isZero();
        assertThat(again.get("batch").get("rejectedRows").asInt()).isZero();
        assertThat(again.get("batch").get("skippedRows").asInt()).isEqualTo(120);
        assertThat(countOf("guardians_guardian_student", admin.institution())).isEqualTo(120);
        assertThat(auditActionsOf(admin.institution()).stream().filter(a -> a.startsWith("guardians.")).count())
                .isEqualTo(entries);
        assertThat(auditActionsOf(admin.institution())).contains("imports.batch.simulated", "imports.batch.applied");
    }

    // --- Stored documents: only the application finds them ---

    @Test
    void aDocumentAlreadyStoredIsNotAnnouncedBySimulatingAndIsRejectedOnItsRowWhenApplyingWithAnAuditedDenial()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        HttpResponse<String> manual = unrecorded("POST", "/api/v1/guardians", admin.token(), """
                {"firstName":"Previa","lastName":"Existente","nationalId":"0801-1980-77777"}""");
        assertThat(manual.statusCode()).as(manual.body()).isEqualTo(201);
        String file = GUARDIANS_HEADER + "ENC-REF-1,EncNombreSecreto1,EncApellidoSecreto1,0801-1980-77777,,,,,\n"
                + "ENC-REF-2,EncNombreSecreto2,EncApellidoSecreto2,,,,,,\n";

        JsonNode simulation = json(run(GUARDIANS_SIMULATE, admin, file));
        assertThat(simulation.get("batch").get("rejectedRows").asInt()).isZero();
        assertThat(simulation.get("batch").get("validRows").asInt()).isEqualTo(2);
        assertThat(rejectionsOf(simulation)).isEmpty();

        JsonNode applied = json(run(GUARDIANS_APPLY, admin, file));
        assertThat(applied.get("batch").get("createdRows").asInt()).isEqualTo(1);
        assertThat(applied.get("batch").get("rejectedRows").asInt()).isEqualTo(1);
        assertThat(rejectionsOf(applied)).containsExactly(List.of(2, "nationalId", "duplicate-national-id"));
        assertThat(column("select outcome from shared_audit_log where institution_id = ? "
                + "and action = 'guardians.guardian.created'", admin.institution())).contains("denied", "success");
        assertThat(countOf("guardians_guardian", admin.institution())).isEqualTo(2);
    }

    @Test
    void aRepeatedReferenceOrDocumentInsideTheFileIsFoundWhenSimulating() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String file = GUARDIANS_HEADER + "ENC-REF-1,EncNombreSecreto1,EncApellidoSecreto1,0801-1980-00001,,,,,\n"
                + "ENC-REF-1,EncNombreSecreto2,EncApellidoSecreto2,,,,,,\n"
                + "ENC-REF-3,EncNombreSecreto3,EncApellidoSecreto3,08011980-00001,,,,,\n"
                + "ENC-REF-4,EncNombreSecreto4,EncApellidoSecreto4,,12,no-es-correo,abc,,PIGEON\n";

        JsonNode simulation = json(run(GUARDIANS_SIMULATE, admin, file));

        assertThat(rejectionsOf(simulation)).containsExactly(List.of(3, "legacyId", "duplicate-in-file"),
                List.of(4, "nationalId", "duplicate-in-file"), List.of(5, "rtn", "invalid-format"),
                List.of(5, "email", "invalid-format"), List.of(5, "phone", "invalid-format"),
                List.of(5, "preferredChannel", "invalid-format"));
    }

    // --- Two and three simultaneous applications ---

    @Test
    void threeSimultaneousApplicationsOfTheSameGuardiansAndLinksFilesRegisterEachRecordOnce() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        run(STUDENTS_SIMULATE, admin, studentsFile(20));
        run(STUDENTS_APPLY, admin, studentsFile(20));
        String guardians = guardiansFile(40);
        run(GUARDIANS_SIMULATE, admin, guardians);

        List<HttpResponse<String>> first = inParallel(() -> run(GUARDIANS_APPLY, admin, guardians),
                () -> run(GUARDIANS_APPLY, admin, guardians), () -> run(GUARDIANS_APPLY, admin, guardians));

        assertThat(first).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
        assertThat(first.stream().mapToInt(r -> batchOf(r).get("createdRows").asInt()).sum()).isEqualTo(40);
        assertThat(first.stream().mapToInt(r -> batchOf(r).get("skippedRows").asInt()).sum()).isEqualTo(80);
        assertThat(countOf("guardians_guardian", admin.institution())).isEqualTo(40);

        StringBuilder links = new StringBuilder(LINKS_HEADER);
        for (int i = 1; i <= 20; i++) {
            links.append(linkRow(i, i, "60.00", "true", "true")).append(linkRow(i, i + 20, "40.00", "false", "false"));
        }
        String file = links.toString();
        run(LINKS_SIMULATE, admin, file);

        List<HttpResponse<String>> second = inParallel(() -> run(LINKS_APPLY, admin, file),
                () -> run(LINKS_APPLY, admin, file), () -> run(LINKS_APPLY, admin, file));

        assertThat(second).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
        assertThat(second.stream().mapToInt(r -> batchOf(r).get("createdRows").asInt()).sum()).isEqualTo(40);
        assertThat(second.stream().mapToInt(r -> batchOf(r).get("skippedRows").asInt()).sum()).isEqualTo(80);
        assertThat(countOf("guardians_guardian_student", admin.institution())).isEqualTo(40);
        assertThat(column("select count(*)::text from guardians_guardian_student where institution_id = ? "
                + "and valid_to is not null", admin.institution())).containsExactly("0");
    }

    // --- Rules of the references and of the sets that need the registry ---

    @Test
    void anInactiveStudentAndAStudentWithLinksMadeByHandAreReportedAndNeverTouched() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        run(STUDENTS_SIMULATE, admin, studentsFile(3));
        run(STUDENTS_APPLY, admin, studentsFile(3));
        run(GUARDIANS_SIMULATE, admin, guardiansFile(3));
        run(GUARDIANS_APPLY, admin, guardiansFile(3));
        UUID student1 = idOf("students_student", "external_ref", "EST-REF-1", admin.institution());
        UUID student2 = idOf("students_student", "external_ref", "EST-REF-2", admin.institution());
        UUID guardian3 = idOf("guardians_guardian", "external_ref", "ENC-REF-3", admin.institution());
        // By hand: student 1 gets guardian 3 at 100; student 2 becomes inactive.
        HttpResponse<String> manual = unrecorded("PUT", "/api/v1/students/" + student1 + "/guardians", admin.token(),
                "{\"links\":[{\"guardianId\":\"" + guardian3 + "\",\"relationship\":\"FATHER\","
                        + "\"financialResponsibilityPercent\":100,\"isPrimaryPayer\":true,"
                        + "\"isBillingRecipient\":true}]}");
        assertThat(manual.statusCode()).as(manual.body()).isEqualTo(200);
        try (Connection owner = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = owner.prepareStatement(
                        "update students_student set status = 'INACTIVE' where institution_id = ? and id = ?")) {
            statement.setObject(1, admin.institution());
            statement.setObject(2, student2);
            statement.executeUpdate();
        }
        String file = LINKS_HEADER + linkRow(1, 1, "100", "true", "true") + linkRow(2, 1, "100", "true", "true")
                + linkRow(3, 1, "100", "true", "true");

        JsonNode simulation = json(run(LINKS_SIMULATE, admin, file));

        assertThat(rejectionsOf(simulation)).containsExactly(List.of(3, "studentLegacyId", "student-not-active"),
                List.of(2, "studentLegacyId", "student-already-linked"));
        assertThat(simulation.get("batch").get("validRows").asInt()).isEqualTo(1);
        assertThat(simulation.get("batch").get("skippedRows").asInt()).isEqualTo(1);
        assertThat(simulation.get("batch").get("rejectedRows").asInt()).isEqualTo(1);
        JsonNode applied = json(run(LINKS_APPLY, admin, file));
        assertThat(applied.get("batch").get("createdRows").asInt()).isEqualTo(1);
        assertThat(countOf("guardians_guardian_student", admin.institution())).isEqualTo(2);
        assertThat(column("select guardian_id::text from guardians_guardian_student where institution_id = ? "
                + "and student_id = '" + student1 + "'", admin.institution())).containsExactly(guardian3.toString());
    }

    // --- Isolation, permissions, history, limits ---

    @Test
    void theReferencesOfAnotherInstitutionNeverResolveAndNeverCountAsImported() throws SQLException {
        Member alpha = member(StaffRole.ADMINISTRATOR.code());
        Member beta = member(StaffRole.ADMINISTRATOR.code());
        run(STUDENTS_SIMULATE, alpha, studentsFile(2));
        run(STUDENTS_APPLY, alpha, studentsFile(2));
        run(GUARDIANS_SIMULATE, alpha, guardiansFile(2));
        run(GUARDIANS_APPLY, alpha, guardiansFile(2));

        JsonNode guardians = json(run(GUARDIANS_SIMULATE, beta, guardiansFile(2)));
        assertThat(guardians.get("batch").get("validRows").asInt()).as("alpha's references are not beta's").isEqualTo(2);
        JsonNode links = json(run(LINKS_SIMULATE, beta, LINKS_HEADER + linkRow(1, 1, "100", "true", "true")));
        assertThat(rejectionsOf(links)).containsExactly(List.of(2, "studentLegacyId", "unknown-student"),
                List.of(2, "guardianLegacyId", "unknown-guardian"));
        assertThat(countOf("imports_batch", beta.institution())).isEqualTo(2);
        assertThat(countOf("guardians_guardian", beta.institution())).isZero();
        JsonNode betaHistory = json(get(BATCHES, beta));
        assertThat(betaHistory.get("total").asInt()).isEqualTo(2);
    }

    @Test
    void aRoleWithoutTheCreatePermissionOfGuardiansGetsA403ThatLeavesNothing() throws SQLException {
        Member coordinator = member(StaffRole.ACADEMIC_COORDINATOR.code());
        Member cashier = sameInstitution(coordinator.institution(), StaffRole.CASHIER.code());
        byte[] none = guardiansFile(2).getBytes(StandardCharsets.UTF_8);

        for (Member member : List.of(coordinator, cashier)) {
            for (String path : List.of(GUARDIANS_SIMULATE, GUARDIANS_APPLY, LINKS_SIMULATE, LINKS_APPLY)) {
                assertProblem(post(path, member.token(), multipart("e.csv", none)), 403, "forbidden");
            }
        }
        assertThat(countOf("imports_batch", coordinator.institution())).isZero();
        assertThat(countOf("guardians_guardian", coordinator.institution())).isZero();
        assertThat(auditActionsOf(coordinator.institution()).stream().filter(a -> a.startsWith("imports.")))
                .isEmpty();
        // Students are still importable by the coordinator, who holds students:create.
        assertThat(run(STUDENTS_SIMULATE, coordinator, studentsFile(1)).statusCode()).isEqualTo(200);
    }

    @Test
    void theHistoryHasAKindFilterAndAnAccountOnlyListsTheKindsItMayRead() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        run(STUDENTS_SIMULATE, admin, studentsFile(1));
        run(GUARDIANS_SIMULATE, admin, guardiansFile(1));
        run(LINKS_SIMULATE, admin, LINKS_HEADER + linkRow(1, 1, "100", "true", "true"));

        assertThat(json(get(BATCHES, admin)).get("total").asInt()).isEqualTo(3);
        for (String kind : List.of("students", "guardians", "guardian-links")) {
            JsonNode page = json(get(BATCHES + "?kind=" + kind, admin));
            assertThat(page.get("total").asInt()).as(kind).isEqualTo(1);
            assertThat(page.get("items").get(0).get("kind").asString())
                    .isEqualTo(kind.toUpperCase().replace('-', '_'));
        }
        assertProblem(get(BATCHES + "?kind=nope", admin), 400, "validation-failed");
        assertProblem(get(BATCHES + "?kind=STUDENTS", admin), 400, "validation-failed");
        // An auditor reads both; the guardian batches of the institution are listed to it.
        Member auditor = sameInstitution(admin.institution(), StaffRole.AUDITOR.code());
        assertThat(json(get(BATCHES, auditor)).get("total").asInt()).isEqualTo(3);
    }

    @Test
    void aSimulationOfAnotherKindNeverAllowsTheApplicationAndTheFileMustBeTheSimulatedOne() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String file = guardiansFile(2);
        run(GUARDIANS_SIMULATE, admin, file);

        assertProblem(run(LINKS_APPLY, admin, file), 409, "import-not-simulated");
        assertProblem(run(STUDENTS_APPLY, admin, file), 409, "import-not-simulated");
        assertProblem(run(GUARDIANS_APPLY, admin, file + "x"), 409, "import-file-changed");
        assertThat(countOf("guardians_guardian", admin.institution())).isZero();
    }

    @Test
    void multipartIsAcceptedOnTheNewRoutesAndOnlyThereAndAFileWithTheHeaderOfAnotherKindIsRefusedByPosition()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Body garbage = new Body("multipart/form-data; boundary=zzz",
                "this is not multipart".getBytes(StandardCharsets.UTF_8));

        assertProblem(post(GUARDIANS_SIMULATE, admin.token(), garbage), 400, "validation-failed");
        assertProblem(post(LINKS_SIMULATE, admin.token(), garbage), 400, "validation-failed");
        assertProblem(post("/api/v1/imports/guardians", admin.token(), garbage), 415, "unsupported-media-type");

        JsonNode refusal = assertProblem(run(GUARDIANS_SIMULATE, admin, "legacyId,firstName,lastName,birthDate\n"
                + "EST-REF-1,EstNombreSecreto1,EstApellidoSecreto1,2012-05-20\n"), 400, "validation-failed");
        assertThat(refusal.get("errors").get(0).get("field").asString()).isEqualTo("columns[3]");
        assertThat(refusal.get("errors").get(0).get("reason").asString()).isEqualTo("unknown-column");
        JsonNode missing = assertProblem(run(LINKS_SIMULATE, admin, "studentLegacyId,relationship\nS1,MOTHER\n"), 400,
                "validation-failed");
        assertThat(missing.get("errors")).hasSize(2);
        assertThat(countOf("imports_batch", admin.institution())).isZero();
    }

    @Test
    void theGuardiansRoutesAreRateLimitedPerAccountBeforeTheBodyIsParsed() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Body garbage = new Body("multipart/form-data; boundary=zzz",
                "this is not multipart".getBytes(StandardCharsets.UTF_8));
        try (ConfigurableApplicationContext limited = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(),
                "confia_admin_app", TEST_PASSWORD, keys, Map.of("confia.web.trusted-proxies", "127.0.0.1,::1",
                        "confia.crypto.blind-index.current-version", "v1",
                        "confia.crypto.blind-index.keys.v1", BLIND_INDEX_KEY,
                        RATE_LIMIT + "admin-import.request-limit", "10000",
                        RATE_LIMIT + "admin-import-account.request-limit", "2"))) {
            int limitedPort = ((WebServerApplicationContext) limited).getWebServer().getPort();

            assertProblem(sendFrom(limitedPort, "203.0.113.60", GUARDIANS_SIMULATE, admin.token(), garbage), 400,
                    "validation-failed");
            assertProblem(sendFrom(limitedPort, "203.0.113.61", LINKS_SIMULATE, admin.token(), garbage), 400,
                    "validation-failed");
            HttpResponse<String> refused = sendFrom(limitedPort, "203.0.113.62", GUARDIANS_APPLY, admin.token(),
                    garbage);
            assertProblem(refused, 429, "too-many-requests");
            assertThat(refused.headers().firstValue("Retry-After")).isPresent();
        }
        try (ConfigurableApplicationContext limited = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(),
                "confia_admin_app", TEST_PASSWORD, keys, Map.of("confia.web.trusted-proxies", "127.0.0.1,::1",
                        "confia.crypto.blind-index.current-version", "v1",
                        "confia.crypto.blind-index.keys.v1", BLIND_INDEX_KEY,
                        RATE_LIMIT + "admin-import.request-limit", "1",
                        RATE_LIMIT + "admin-import-account.request-limit", "10000"))) {
            int limitedPort = ((WebServerApplicationContext) limited).getWebServer().getPort();

            assertProblem(sendFrom(limitedPort, "203.0.113.70", LINKS_APPLY, admin.token(), garbage), 400,
                    "validation-failed");
            assertProblem(sendFrom(limitedPort, "203.0.113.70", GUARDIANS_APPLY, admin.token(), garbage), 429,
                    "too-many-requests");
            assertProblem(sendFrom(limitedPort, "203.0.113.71", GUARDIANS_APPLY, admin.token(), garbage), 400,
                    "validation-failed");
        }
    }

    // --- A malformed or unattributable row refuses the whole links file ---

    @Test
    void aLinksFileWithAHundredPercentRowAndAMalformedRowOfTheSameStudentCreatesNothing() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        run(STUDENTS_SIMULATE, admin, studentsFile(2));
        run(STUDENTS_APPLY, admin, studentsFile(2));
        run(GUARDIANS_SIMULATE, admin, guardiansFile(2));
        run(GUARDIANS_APPLY, admin, guardiansFile(2));
        long batches = countOf("imports_batch", admin.institution());
        String malformed = LINKS_HEADER + linkRow(1, 1, "100.00", "true", "true") + "EST-REF-1,ENC-REF-2,MOTHER\n";
        String noStudent = LINKS_HEADER + linkRow(1, 1, "100.00", "true", "true")
                + ",ENC-REF-2,MOTHER,50.00,false,false\n";

        for (String file : List.of(malformed, noStudent)) {
            JsonNode refusal = assertProblem(run(LINKS_SIMULATE, admin, file), 400, "validation-failed");
            assertThat(refusal.get("errors")).hasSize(1);
            assertThat(refusal.get("errors").get(0).get("field").asString()).isEqualTo("file");
            assertThat(refusal.get("errors").get(0).get("reason").asString()).isEqualTo("row-shape");
            // No simulation exists for such a file, so it cannot be applied either.
            assertProblem(run(LINKS_APPLY, admin, file), 409, "import-not-simulated");
        }
        assertThat(countOf("guardians_guardian_student", admin.institution())).isZero();
        assertThat(countOf("imports_batch", admin.institution())).as("nothing was recorded").isEqualTo(batches);
    }

    // --- Permissions the real roles cannot show: a synthetic resolver for chosen accounts ---

    private static final Map<UUID, Set<com.confia.kernel.Permission>> SYNTHETIC =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Makes the decider of the running process answer these permissions for this account (the others as before). */
    private static void install() throws ReflectiveOperationException {
        com.confia.shared.web.authorization.PermissionDecider decider =
                process.getBean(com.confia.shared.web.authorization.PermissionDecider.class);
        java.lang.reflect.Field field = com.confia.shared.web.authorization.PermissionDecider.class
                .getDeclaredField("resolver");
        field.setAccessible(true);
        com.confia.shared.security.PermissionResolver real = (com.confia.shared.security.PermissionResolver) field
                .get(decider);
        if (real.getClass().getName().contains("SyntheticResolver")) {
            return;
        }
        field.set(decider, new SyntheticResolver(real));
    }

    private record SyntheticResolver(com.confia.shared.security.PermissionResolver real)
            implements com.confia.shared.security.PermissionResolver {

        @Override
        public Set<com.confia.kernel.Permission> resolve(com.confia.shared.security.AuthenticatedActor actor) {
            Set<com.confia.kernel.Permission> chosen = SYNTHETIC.get(actor.accountId());
            return chosen != null ? chosen : real.resolve(actor);
        }
    }

    @AfterEach
    void forgetTheSyntheticPermissions() {
        SYNTHETIC.clear();
    }

    private static List<String> denialsOf(UUID institution) throws SQLException {
        return column("select entity_id from shared_audit_log where institution_id = ? "
                + "and action = 'authorization.access.denied'", institution);
    }

    @Test
    void anAccountThatCanCreateGuardiansButNotUpdateThemIsRefusedOnTheLinkRoutesBeforeTheBodyIsReadAndLeavesOnlyTheDenial()
            throws Exception {
        install();
        Member member = member(StaffRole.ADMINISTRATOR.code());
        SYNTHETIC.put(member.account(), Set.of(com.confia.kernel.Permission.GUARDIANS_CREATE,
                com.confia.kernel.Permission.GUARDIANS_READ, com.confia.kernel.Permission.STUDENTS_READ));
        Body garbage = new Body("multipart/form-data; boundary=zzz",
                "this is not multipart".getBytes(StandardCharsets.UTF_8));

        // A body that would be a 400 if anything read it is a 403: the edge answered first.
        assertProblem(post(LINKS_SIMULATE, member.token(), garbage), 403, "forbidden");
        assertProblem(post(LINKS_APPLY, member.token(), garbage), 403, "forbidden");
        assertProblem(run(LINKS_SIMULATE, member, LINKS_HEADER + linkRow(1, 1, "100", "true", "true")), 403,
                "forbidden");

        assertThat(column("select count(*)::text from imports_batch where institution_id = ?",
                member.institution())).containsExactly("0");
        assertThat(denialsOf(member.institution())).contains("guardians:update").doesNotContain("guardians:create");
        // It does hold guardians:create, so the guardians routes work.
        assertThat(run(GUARDIANS_SIMULATE, member, guardiansFile(1)).statusCode()).isEqualTo(200);
    }

    @Test
    void theHistoryOfAKindTheAccountCannotReadIsA403WithTheDenialOfTheMissingPermissionAndTheOtherKindsAreListed()
            throws Exception {
        install();
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        run(STUDENTS_SIMULATE, admin, studentsFile(1));
        run(GUARDIANS_SIMULATE, admin, guardiansFile(1));
        Member reader = sameInstitution(admin.institution(), StaffRole.ADMINISTRATOR.code());
        SYNTHETIC.put(reader.account(), Set.of(com.confia.kernel.Permission.STUDENTS_READ));

        assertProblem(get(BATCHES + "?kind=guardians", reader), 403, "forbidden");
        assertProblem(get(BATCHES + "?kind=guardian-links", reader), 403, "forbidden");
        assertThat(denialsOf(admin.institution())).contains("guardians:read");
        assertThat(json(get(BATCHES, reader)).get("total").asInt()).as("only the students kind is listed")
                .isEqualTo(1);
        assertThat(json(get(BATCHES + "?kind=students", reader)).get("total").asInt()).isEqualTo(1);
        assertThat(countOf("imports_batch", admin.institution())).isEqualTo(2);
    }

    // --- Bodies and helpers ---

    private record Body(String contentType, byte[] bytes) {
    }

    private static Body multipart(String filename, byte[] content) {
        String boundary = "----confia" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return new Body("multipart/form-data; boundary=" + boundary, body.toByteArray());
    }

    private static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return HarnessProcess.json(response);
    }

    private static JsonNode batchOf(HttpResponse<String> response) {
        return json(response).get("batch");
    }

    private static List<List<Object>> rejectionsOf(JsonNode run) {
        List<List<Object>> rejections = new ArrayList<>();
        for (JsonNode rejection : run.get("rejections")) {
            rejections.add(List.of(rejection.get("line").asInt(), rejection.get("field").asString(),
                    rejection.get("reason").asString()));
        }
        return rejections;
    }

    private static HttpResponse<String> run(String path, Member member, String csv) {
        return post(path, member.token(), multipart("archivo.csv", csv.getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpResponse<String> get(String path, Member member) {
        return execute(request(port, null, "GET", path, member.token(), null), true);
    }

    private static HttpResponse<String> post(String path, String token, Body body) {
        return execute(request(port, null, "POST", path, token, body), true);
    }

    private static HttpResponse<String> sendFrom(int targetPort, String address, String path, String token, Body body) {
        return execute(request(targetPort, address, "POST", path, token, body), true);
    }

    /** A request whose answer legitimately carries names, which are not scanned. */
    private static HttpResponse<String> unrecorded(String method, String path, String token, String json) {
        return execute(request(port, null, method, path, token,
                new Body("application/json", json.getBytes(StandardCharsets.UTF_8))), false);
    }

    private static HttpRequest request(int targetPort, String address, String method, String path, String token,
            Body body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + targetPort + path))
                .timeout(Duration.ofSeconds(120))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body.bytes()));
        request.header("Authorization", "Bearer " + token);
        if (address != null) {
            request.header("X-Forwarded-For", address);
        }
        if (body != null) {
            request.header("Content-Type", body.contentType());
        }
        if (!method.equals("GET")) {
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

    // --- Seeding ---

    private Member member(String roleCode) {
        UUID institution = UUID.randomUUID();
        try {
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
            return new Member(account, institution, token);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
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
        return Long.parseLong(column("select count(*)::text from " + table + " where institution_id = ?",
                institution).getFirst());
    }

    private static UUID idOf(String table, String column, String value, UUID institution) throws SQLException {
        return UUID.fromString(column("select id::text from " + table + " where institution_id = ? and " + column
                + " = '" + value + "'", institution).getFirst());
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

    private static List<String> rows(String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(sql);
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

    /** The ciphertexts and blind indexes of every stored document and RTN, and the part of the index after its version. */
    private static List<String> storedSecrets() throws SQLException {
        List<String> values = new ArrayList<>();
        for (String sql : List.of(
                "select national_id_encrypted, national_id_blind_index, null from students_student",
                "select national_id_encrypted, national_id_blind_index, rtn_encrypted from guardians_guardian")) {
            try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                    PreparedStatement statement = connection.prepareStatement(sql);
                    ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    for (int column = 1; column <= 3; column++) {
                        String value = result.getString(column);
                        if (value != null) {
                            values.add(value);
                            if (column == 2) {
                                values.add(value.substring(value.indexOf(':') + 1));
                            }
                        }
                    }
                }
            }
        }
        return values;
    }
}

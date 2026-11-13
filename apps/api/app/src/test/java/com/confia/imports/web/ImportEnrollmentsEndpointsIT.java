package com.confia.imports.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.confia.bootstrap.AdminProcessLauncher;
import com.confia.identity.domain.StaffRole;
import com.confia.kernel.Permission;
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
import java.time.LocalDate;
import java.time.ZoneId;
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
 * The routes of the enrollments import on the real administrative process against a real PostgreSQL with the
 * least-privilege role of the application (docs/cortes/imports-enrollments.md). It covers the acceptance criterion (100
 * students imported, 100 enrollments with 7 defective rows: exactly those rows rejected by line, column and reason,
 * corrected, applied, no capacity exceeded, applying again changes nothing), the student who is never moved from the
 * section where already enrolled, the capacity under simultaneous applications of different files, the rules that need
 * the academic structure, the isolation between institutions, the roles without the permission (a {@code 403} that
 * leaves no batch and no record), the history with its kind filter, and that no response, audit row, batch row or log
 * line carries a reference of a student, a name or the name of a section.
 */
class ImportEnrollmentsEndpointsIT extends CommittingPostgresIntegrationTest {

    private static final String STUDENTS_SIMULATE = "/api/v1/imports/students/simulate";
    private static final String STUDENTS_APPLY = "/api/v1/imports/students/apply";
    private static final String ENROLLMENTS_SIMULATE = "/api/v1/imports/enrollments/simulate";
    private static final String ENROLLMENTS_APPLY = "/api/v1/imports/enrollments/apply";
    private static final String BATCHES = "/api/v1/imports";
    private static final String HEADER = "studentLegacyId,academicYear,modality,grade,section,enrolledOn\n";
    private static final String RATE_LIMIT = "confia.web.rate-limit.";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final String BLIND_INDEX_KEY = randomBase64Key();

    private static final String YEAR = "AnioSecreto";
    private static final String CLOSED_YEAR = "AnioSecretoCerrado";
    private static final String MODALITY = "ModalidadSecreta";
    private static final String OTHER_MODALITY = "ModalidadSecretaDos";
    private static final String GRADE = "GradoSecreto7";
    /** Fragments of the values of every file of these tests: they appear nowhere but in the registries. */
    private static final List<String> PERSONAL = List.of("EST-REF-", "EstNombreSecreto", "EstApellidoSecreto",
            "SeccionSecreta", GRADE, MODALITY, "AnioSecreto");
    private static final ZoneId HONDURAS = ZoneId.of("America/Tegucigalpa");

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
     * Whatever a test did, no import response, audit row, batch row or log line carries a reference of a student, a name,
     * the name of a section, a grade, a modality or a year of the files.
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
        forbidden.add(BLIND_INDEX_KEY);
        List<String> audit = rows("select a::text from shared_audit_log a");
        List<String> batches = rows("select b::text from imports_batch b union all select r::text from imports_reconciliation r");
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

    /** The academic structure of an institution: one open year with today inside it, a closed one, two modalities. */
    private record Structure(UUID institution, UUID year, UUID closedYear, UUID modality, UUID otherModality,
            UUID grade, UUID otherGrade) {
    }

    // --- Dates and files ---

    private static LocalDate today() {
        return LocalDate.now(HONDURAS);
    }

    private static LocalDate yearStart() {
        return today().minusDays(200);
    }

    private static String studentsFile(int count) {
        StringBuilder csv = new StringBuilder("legacyId,firstName,lastName,birthDate\n");
        for (int n = 1; n <= count; n++) {
            csv.append("EST-REF-").append(n).append(",EstNombreSecreto").append(n).append(",EstApellidoSecreto")
                    .append(n).append(",2012-05-20\n");
        }
        return csv.toString();
    }

    private static String enrollmentLine(String reference, String year, String modality, String grade, String section,
            String date) {
        return reference + "," + year + "," + modality + "," + grade + "," + section + "," + date + "\n";
    }

    private static String line(int student, String section, LocalDate date) {
        return enrollmentLine("EST-REF-" + student, YEAR, MODALITY, GRADE, section, date.toString());
    }

    private static String line(int student, String section) {
        return line(student, section, today().minusDays(30));
    }

    private static String file(String... lines) {
        return HEADER + String.join("", lines);
    }

    private static String sectionName(String suffix) {
        return "SeccionSecreta" + suffix;
    }

    // --- The acceptance criterion ---

    @Test
    void anAdministratorImportsHundredEnrollmentsWithSevenDefectiveRowsSeesExactlyThemFixesAppliesAndReapplyingChangesNothing()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure org = structure(admin.institution());
        section(org, org.year(), org.grade(), sectionName("Uno"), 50, true);
        section(org, org.year(), org.grade(), sectionName("Dos"), 50, true);
        section(org, org.year(), org.grade(), sectionName("Tres"), 10, true);
        importStudents(admin, 100);

        StringBuilder defective = new StringBuilder(HEADER);
        StringBuilder corrected = new StringBuilder(HEADER);
        for (int n = 1; n <= 93; n++) {
            String name = n <= 40 ? "Uno" : n <= 80 ? "Dos" : n <= 90 ? "Tres" : "Uno";
            defective.append(line(n, sectionName(name)));
            corrected.append(line(n, sectionName(name)));
        }
        // 94 sits in a section that does not exist, 95 starts the day before the year, 96 is in the future, 97 asks
        // for the section that is already full, 98 is a student that was never imported, 99 has no section and 100
        // is the student 1 again.
        defective.append(line(94, sectionName("NoExiste")));
        defective.append(line(95, sectionName("Uno"), yearStart().minusDays(1)));
        defective.append(line(96, sectionName("Uno"), today().plusDays(2)));
        defective.append(line(97, sectionName("Tres")));
        defective.append(enrollmentLine("EST-REF-9998", YEAR, MODALITY, GRADE, sectionName("Uno"),
                today().minusDays(30).toString()));
        defective.append(enrollmentLine("EST-REF-99", YEAR, MODALITY, GRADE, "", today().minusDays(30).toString()));
        defective.append(line(1, sectionName("Dos")));
        corrected.append(line(94, sectionName("Uno")));
        corrected.append(line(95, sectionName("Uno"), yearStart()));
        corrected.append(line(96, sectionName("Dos"), today()));
        corrected.append(line(97, sectionName("Dos")));
        corrected.append(line(98, sectionName("Dos")));
        corrected.append(line(99, sectionName("Dos")));
        corrected.append(line(100, sectionName("Dos")));

        JsonNode simulation = json(run(ENROLLMENTS_SIMULATE, admin, defective.toString()));
        JsonNode batch = simulation.get("batch");
        assertThat(batch.get("kind").asString()).isEqualTo("ENROLLMENTS");
        assertThat(batch.get("status").asString()).isEqualTo("VALIDATED");
        assertThat(batch.get("totalRows").asInt()).isEqualTo(100);
        assertThat(batch.get("validRows").asInt()).isEqualTo(93);
        assertThat(batch.get("rejectedRows").asInt()).isEqualTo(7);
        assertThat(batch.get("skippedRows").asInt()).isZero();
        assertThat(rejectionsOf(simulation)).containsExactly(
                List.of(95, "section", "unknown-section"),
                List.of(96, "enrolledOn", "enrollment-date-out-of-range"),
                List.of(97, "enrolledOn", "enrollment-date-out-of-range"),
                List.of(98, "section", "section-full"),
                List.of(99, "studentLegacyId", "unknown-student"),
                List.of(100, "section", "required"),
                List.of(101, "studentLegacyId", "duplicate-in-file"));
        assertThat(countOf("students_enrollment", admin.institution())).as("the simulation writes nothing").isZero();

        JsonNode applied = json(run(ENROLLMENTS_APPLY, admin, defective.toString()));
        assertThat(applied.get("batch").get("status").asString()).isEqualTo("APPLIED");
        assertThat(applied.get("batch").get("createdRows").asInt()).isEqualTo(93);
        assertThat(applied.get("batch").get("rejectedRows").asInt()).isEqualTo(7);
        assertThat(countOf("students_enrollment", admin.institution())).isEqualTo(93);
        assertThat(activeIn(sectionId(admin, "Tres"))).isEqualTo(10);

        JsonNode fixed = json(run(ENROLLMENTS_SIMULATE, admin, corrected.toString()));
        assertThat(fixed.get("batch").get("validRows").asInt()).isEqualTo(7);
        assertThat(fixed.get("batch").get("skippedRows").asInt()).isEqualTo(93);
        assertThat(fixed.get("batch").get("rejectedRows").asInt()).isZero();
        assertThat(rejectionsOf(fixed)).hasSize(93).allMatch(r -> r.get(1).equals("studentLegacyId")
                && r.get(2).equals("already-imported"));
        JsonNode second = json(run(ENROLLMENTS_APPLY, admin, corrected.toString()));
        assertThat(second.get("batch").get("createdRows").asInt()).isEqualTo(7);
        assertThat(second.get("batch").get("skippedRows").asInt()).isEqualTo(93);
        assertThat(second.get("batch").get("rejectedRows").asInt()).isZero();

        assertThat(countOf("students_enrollment", admin.institution())).isEqualTo(100);
        assertThat(column("""
                select count(*)::text from (
                    select student_id from students_enrollment
                     where institution_id = ? and status = 'ACTIVE' group by student_id having count(*) = 1) t
                """, admin.institution())).containsExactly("100");
        assertThat(activeIn(sectionId(admin, "Uno"))).isEqualTo(45);
        assertThat(activeIn(sectionId(admin, "Dos"))).isEqualTo(45);
        assertThat(activeIn(sectionId(admin, "Tres"))).isEqualTo(10);
        assertThat(column("""
                select count(*)::text from organization_section s
                 where s.institution_id = ? and s.capacity is not null and s.capacity <
                       (select count(*) from students_enrollment e
                         where e.section_id = s.id and e.status = 'ACTIVE')
                """, admin.institution())).containsExactly("0");

        long enrollmentEntries = enrollmentAuditCount(admin.institution());
        assertThat(enrollmentEntries).isEqualTo(100);
        JsonNode again = json(run(ENROLLMENTS_APPLY, admin, corrected.toString()));
        assertThat(again.get("batch").get("createdRows").asInt()).isZero();
        assertThat(again.get("batch").get("rejectedRows").asInt()).isZero();
        assertThat(again.get("batch").get("skippedRows").asInt()).isEqualTo(100);
        assertThat(countOf("students_enrollment", admin.institution())).isEqualTo(100);
        assertThat(enrollmentAuditCount(admin.institution())).isEqualTo(enrollmentEntries);
        assertThat(auditActionsOf(admin.institution())).contains("imports.batch.simulated", "imports.batch.applied");
    }

    // --- A student who is already enrolled is never moved ---

    @Test
    void aStudentAlreadyEnrolledInAnotherSectionIsRejectedAndNeverMovedAndTheSameSectionIsSkipped()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure org = structure(admin.institution());
        section(org, org.year(), org.grade(), sectionName("Uno"), 5, true);
        section(org, org.year(), org.grade(), sectionName("Dos"), 5, true);
        importStudents(admin, 3);
        String first = file(line(1, sectionName("Uno")), line(2, sectionName("Uno")));
        run(ENROLLMENTS_SIMULATE, admin, first);
        assertThat(batchOf(run(ENROLLMENTS_APPLY, admin, first)).get("createdRows").asInt()).isEqualTo(2);
        UUID enrollment = UUID.fromString(column("""
                select e.id::text from students_enrollment e join students_student s on s.id = e.student_id
                 where e.institution_id = ? and s.external_ref = 'EST-REF-1'
                """, admin.institution()).getFirst());

        String second = file(line(1, sectionName("Dos")), line(2, sectionName("Uno")), line(3, sectionName("Dos")));
        JsonNode simulation = json(run(ENROLLMENTS_SIMULATE, admin, second));

        assertThat(rejectionsOf(simulation)).containsExactly(List.of(2, "studentLegacyId", "duplicate-active-enrollment"),
                List.of(3, "studentLegacyId", "already-imported"));
        assertThat(simulation.get("batch").get("validRows").asInt()).isEqualTo(1);
        assertThat(simulation.get("batch").get("rejectedRows").asInt()).isEqualTo(1);
        assertThat(simulation.get("batch").get("skippedRows").asInt()).isEqualTo(1);
        JsonNode applied = json(run(ENROLLMENTS_APPLY, admin, second));
        assertThat(applied.get("batch").get("createdRows").asInt()).isEqualTo(1);
        assertThat(applied.get("batch").get("rejectedRows").asInt()).isEqualTo(1);
        assertThat(applied.get("batch").get("skippedRows").asInt()).isEqualTo(1);

        assertThat(column("select section_id::text from students_enrollment where institution_id = ? and id = '"
                + enrollment + "' and status = 'ACTIVE'", admin.institution()))
                .containsExactly(sectionId(admin, "Uno").toString());
        assertThat(countOf("students_enrollment", admin.institution())).isEqualTo(3);
        assertThat(countOf("students_enrollment_transfer", admin.institution())).as("nothing was moved").isZero();
        assertThat(column("select count(*)::text from students_enrollment where institution_id = ? "
                + "and student_id in (select id from students_student where external_ref = 'EST-REF-1')",
                admin.institution())).containsExactly("1");
    }

    // --- The rules that need the registry and the academic structure ---

    @Test
    void theNamesOfTheSectionAreMatchedWithoutCaseOrBorderSpacesAndUnknownClosedInactiveAndInactiveStudentAreReported()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure org = structure(admin.institution());
        section(org, org.year(), org.grade(), sectionName("Uno"), 5, true);
        section(org, org.year(), org.grade(), sectionName("Inactiva"), 5, false);
        section(org, org.closedYear(), org.grade(), sectionName("Cerrada"), 5, true);
        // The same grade and section names under another modality still resolve, each by its modality.
        section(org, org.year(), org.otherGrade(), sectionName("Uno"), 5, true);
        section(org, org.year(), org.otherGrade(), sectionName("Dos"), 5, true);
        importStudents(admin, 9);
        updateAsOwner("update students_student set status = 'INACTIVE' where institution_id = ? and external_ref = ?",
                admin.institution(), "EST-REF-5");

        String lines = enrollmentLine("EST-REF-1", "  anioSECRETO ", "modalidadsecreta  ", " GRADOSECRETO7",
                " seccionsecretauno ", today().minusDays(30).toString())
                + enrollmentLine("EST-REF-2", YEAR, MODALITY, GRADE, sectionName("NoExiste"),
                        today().minusDays(30).toString())
                + enrollmentLine("EST-REF-3", YEAR, OTHER_MODALITY, GRADE, sectionName("Uno"),
                        today().minusDays(30).toString())
                + enrollmentLine("EST-REF-4", YEAR, MODALITY, GRADE, sectionName("Inactiva"),
                        today().minusDays(30).toString())
                + enrollmentLine("EST-REF-5", YEAR, MODALITY, GRADE, sectionName("Uno"),
                        today().minusDays(30).toString())
                + enrollmentLine("EST-REF-6", CLOSED_YEAR, MODALITY, GRADE, sectionName("Cerrada"),
                        today().minusDays(30).toString())
                + enrollmentLine("EST-REF-7", YEAR, OTHER_MODALITY, GRADE, sectionName("Dos"),
                        today().minusDays(30).toString());
        JsonNode simulation = json(run(ENROLLMENTS_SIMULATE, admin, HEADER + lines));

        assertThat(rejectionsOf(simulation)).containsExactly(List.of(3, "section", "unknown-section"),
                List.of(5, "section", "inactive-reference"), List.of(6, "studentLegacyId", "student-not-active"),
                List.of(7, "academicYear", "academic-year-closed"));
        assertThat(simulation.get("batch").get("validRows").asInt()).isEqualTo(3);
    }

    @Test
    void aNameThatMatchesTwoSectionsIsAmbiguousAndNeverGuessed() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure org = structure(admin.institution());
        section(org, org.year(), org.grade(), sectionName("Uno"), 5, true);
        section(org, org.year(), org.otherGrade(), sectionName("Uno"), 5, true);
        importStudents(admin, 2);
        String file = HEADER + enrollmentLine("EST-REF-1", YEAR, MODALITY, GRADE, sectionName("Uno"),
                today().minusDays(30).toString())
                + enrollmentLine("EST-REF-2", YEAR, MODALITY, GRADE, sectionName("Uno"),
                        today().minusDays(30).toString());
        // While the modalities have different names, the four names of a row point to one section each.
        assertThat(rejectionsOf(json(run(ENROLLMENTS_SIMULATE, admin, file)))).isEmpty();

        // Naming the second modality like the first makes both sections answer to the same four names.
        updateAsOwner("update organization_modality set name = ? where institution_id = ? and id = '"
                + org.otherModality() + "'", MODALITY, admin.institution());
        JsonNode simulation = json(run(ENROLLMENTS_SIMULATE, admin, file));

        assertThat(rejectionsOf(simulation)).containsExactly(List.of(2, "section", "ambiguous-section"),
                List.of(3, "section", "ambiguous-section"));
        JsonNode applied = json(run(ENROLLMENTS_APPLY, admin, file));
        assertThat(applied.get("batch").get("createdRows").asInt()).isZero();
        assertThat(applied.get("batch").get("rejectedRows").asInt()).isEqualTo(2);
        assertThat(countOf("students_enrollment", admin.institution())).isZero();
    }

    @Test
    void theDateBordersOfTheYearAndTodayAreAcceptedAndTheDaysAroundThemAreNot() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure org = structure(admin.institution());
        section(org, org.year(), org.grade(), sectionName("Uno"), null, true);
        importStudents(admin, 6);
        String file = file(line(1, sectionName("Uno"), yearStart().minusDays(1)),
                line(2, sectionName("Uno"), yearStart()), line(3, sectionName("Uno"), today()),
                line(4, sectionName("Uno"), today().plusDays(2)),
                enrollmentLine("EST-REF-5", YEAR, MODALITY, GRADE, sectionName("Uno"), "2026-02-30"),
                enrollmentLine("EST-REF-6", YEAR, MODALITY, GRADE, sectionName("Uno"), "03/02/2026"));

        JsonNode simulation = json(run(ENROLLMENTS_SIMULATE, admin, file));

        assertThat(rejectionsOf(simulation)).containsExactly(
                List.of(2, "enrolledOn", "enrollment-date-out-of-range"),
                List.of(5, "enrolledOn", "enrollment-date-out-of-range"), List.of(6, "enrolledOn", "invalid-date"),
                List.of(7, "enrolledOn", "invalid-format"));
        JsonNode applied = json(run(ENROLLMENTS_APPLY, admin, file));
        assertThat(applied.get("batch").get("createdRows").asInt()).isEqualTo(2);
        assertThat(column("select enrolled_on::text from students_enrollment where institution_id = ? "
                + "order by enrolled_on", admin.institution())).containsExactly(yearStart().toString(),
                today().toString());
    }

    @Test
    void theSimulationEstimatesTheCapacityWithTheExistingEnrollmentsPlusTheRowsOfTheFileInOrder()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure org = structure(admin.institution());
        section(org, org.year(), org.grade(), sectionName("Uno"), 3, true);
        importStudents(admin, 6);
        String seed = file(line(1, sectionName("Uno")));
        run(ENROLLMENTS_SIMULATE, admin, seed);
        run(ENROLLMENTS_APPLY, admin, seed);

        String file = file(line(2, sectionName("Uno")), line(3, sectionName("Uno")), line(4, sectionName("Uno")),
                line(5, sectionName("Uno")));
        JsonNode simulation = json(run(ENROLLMENTS_SIMULATE, admin, file));

        assertThat(rejectionsOf(simulation)).containsExactly(List.of(4, "section", "section-full"),
                List.of(5, "section", "section-full"));
        JsonNode applied = json(run(ENROLLMENTS_APPLY, admin, file));
        assertThat(applied.get("batch").get("createdRows").asInt()).isEqualTo(2);
        assertThat(applied.get("batch").get("rejectedRows").asInt()).isEqualTo(2);
        assertThat(activeIn(sectionId(admin, "Uno"))).isEqualTo(3);
        assertThat(column("select e.student_id::text from students_enrollment e join students_student s "
                + "on s.id = e.student_id where e.institution_id = ? order by s.external_ref",
                admin.institution())).hasSize(3);
    }

    // --- The capacity under simultaneous applications of different files ---

    @Test
    void simultaneousApplicationsOfDifferentFilesRacingForTheLastSeatsNeverExceedTheCapacity() throws Exception {
        Member alice = member(StaffRole.ADMINISTRATOR.code());
        Member bob = sameInstitution(alice.institution(), StaffRole.ADMINISTRATOR.code());
        Structure org = structure(alice.institution());
        int[][] rounds = {{1, 1}, {1, 1}, {1, 1}, {4, 3}, {4, 3}};
        importStudents(alice, 40);
        int cursor = 1;
        for (int round = 0; round < rounds.length; round++) {
            int capacity = rounds[round][0];
            int perFile = rounds[round][1];
            String name = sectionName("Carrera" + round);
            section(org, org.year(), org.grade(), name, capacity, true);
            StringBuilder aliceFile = new StringBuilder(HEADER);
            StringBuilder bobFile = new StringBuilder(HEADER);
            for (int k = 0; k < perFile; k++) {
                aliceFile.append(line(cursor++, name));
            }
            for (int k = 0; k < perFile; k++) {
                bobFile.append(line(cursor++, name));
            }
            String forAlice = aliceFile.toString();
            String forBob = bobFile.toString();
            // Each file is valid when simulated: a seat was free for the whole file or the estimate let it through.
            assertThat(json(run(ENROLLMENTS_SIMULATE, alice, forAlice)).get("batch").get("validRows").asInt())
                    .isGreaterThanOrEqualTo(1);
            assertThat(json(run(ENROLLMENTS_SIMULATE, bob, forBob)).get("batch").get("validRows").asInt())
                    .isGreaterThanOrEqualTo(1);

            List<HttpResponse<String>> answers = inParallel(() -> run(ENROLLMENTS_APPLY, alice, forAlice),
                    () -> run(ENROLLMENTS_APPLY, bob, forBob));

            assertThat(answers).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
            long active = activeIn(sectionId(alice, "Carrera" + round));
            assertThat(active).as("round %d: never above the capacity, and every seat is used", round)
                    .isEqualTo(capacity);
            int created = answers.stream().mapToInt(r -> batchOf(r).get("createdRows").asInt()).sum();
            int rejected = answers.stream().mapToInt(r -> batchOf(r).get("rejectedRows").asInt()).sum();
            assertThat(created).isEqualTo(capacity);
            assertThat(created + rejected).isEqualTo(2 * perFile);
        }
        assertThat(column("""
                select count(*)::text from organization_section s
                 where s.institution_id = ? and s.capacity < (select count(*) from students_enrollment e
                                                               where e.section_id = s.id and e.status = 'ACTIVE')
                """, alice.institution())).containsExactly("0");
        assertThat(column("""
                select count(*)::text from (select student_id from students_enrollment where institution_id = ?
                 and status = 'ACTIVE' group by student_id having count(*) > 1) t
                """, alice.institution())).containsExactly("0");
    }

    @Test
    void twoSimultaneousApplicationsOfTheSameFileEnrollEachStudentOnce() throws Exception {
        Member alice = member(StaffRole.ADMINISTRATOR.code());
        Member bob = sameInstitution(alice.institution(), StaffRole.ADMINISTRATOR.code());
        Structure org = structure(alice.institution());
        section(org, org.year(), org.grade(), sectionName("Uno"), null, true);
        importStudents(alice, 30);
        StringBuilder lines = new StringBuilder(HEADER);
        for (int n = 1; n <= 30; n++) {
            lines.append(line(n, sectionName("Uno")));
        }
        String file = lines.toString();
        run(ENROLLMENTS_SIMULATE, alice, file);
        run(ENROLLMENTS_SIMULATE, bob, file);

        List<HttpResponse<String>> answers = inParallel(() -> run(ENROLLMENTS_APPLY, alice, file),
                () -> run(ENROLLMENTS_APPLY, bob, file));

        assertThat(answers).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
        assertThat(answers.stream().mapToInt(r -> batchOf(r).get("createdRows").asInt()).sum()).isEqualTo(30);
        assertThat(answers.stream().mapToInt(r -> batchOf(r).get("skippedRows").asInt()).sum()).isEqualTo(30);
        assertThat(answers.stream().mapToInt(r -> batchOf(r).get("rejectedRows").asInt()).sum()).isZero();
        assertThat(countOf("students_enrollment", alice.institution())).isEqualTo(30);
    }

    @Test
    void simultaneousApplicationsEnrollingTheSameStudentsInTwoSectionsOfTheYearLeaveEachStudentWithOneActiveEnrollment()
            throws Exception {
        Member alice = member(StaffRole.ADMINISTRATOR.code());
        Member bob = sameInstitution(alice.institution(), StaffRole.ADMINISTRATOR.code());
        Structure org = structure(alice.institution());
        importStudents(alice, 20);
        int[] sizes = {1, 1, 1, 3, 3};
        int cursor = 1;
        for (int round = 0; round < sizes.length; round++) {
            String first = sectionName("DosA" + round);
            String second = sectionName("DosB" + round);
            section(org, org.year(), org.grade(), first, null, true);
            section(org, org.year(), org.grade(), second, null, true);
            StringBuilder inFirst = new StringBuilder(HEADER);
            StringBuilder inSecond = new StringBuilder(HEADER);
            for (int k = 0; k < sizes[round]; k++) {
                inFirst.append(line(cursor, first));
                inSecond.append(line(cursor, second));
                cursor++;
            }
            String forAlice = inFirst.toString();
            String forBob = inSecond.toString();
            assertThat(batchOf(run(ENROLLMENTS_SIMULATE, alice, forAlice)).get("validRows").asInt())
                    .isEqualTo(sizes[round]);
            assertThat(batchOf(run(ENROLLMENTS_SIMULATE, bob, forBob)).get("validRows").asInt())
                    .isEqualTo(sizes[round]);

            List<HttpResponse<String>> answers = inParallel(() -> run(ENROLLMENTS_APPLY, alice, forAlice),
                    () -> run(ENROLLMENTS_APPLY, bob, forBob));

            assertThat(answers).allSatisfy(r -> assertThat(r.statusCode()).as(r.body()).isEqualTo(200));
            assertThat(answers.stream().mapToInt(r -> batchOf(r).get("createdRows").asInt()).sum())
                    .as("round %d: one enrollment per student", round).isEqualTo(sizes[round]);
            assertThat(answers.stream().mapToInt(r -> batchOf(r).get("rejectedRows").asInt()).sum())
                    .as("round %d: the other row of each student is rejected", round).isEqualTo(sizes[round]);
            for (HttpResponse<String> answer : answers) {
                for (List<Object> rejection : rejectionsOf(json(answer))) {
                    assertThat(rejection.get(1)).isEqualTo("studentLegacyId");
                    assertThat(rejection.get(2)).isEqualTo("duplicate-active-enrollment");
                }
            }
            assertThat(activeIn(sectionId(alice, "DosA" + round)) + activeIn(sectionId(alice, "DosB" + round)))
                    .isEqualTo(sizes[round]);
        }
        assertThat(column("""
                select count(*)::text from (select student_id, academic_year_id from students_enrollment
                 where institution_id = ? and status = 'ACTIVE'
                 group by student_id, academic_year_id having count(*) > 1) t
                """, alice.institution())).containsExactly("0");
        assertThat(countOf("students_enrollment", alice.institution())).isEqualTo(9);
    }

    @Test
    void theEnrollmentRoutesAreRateLimitedPerAccountAndPerClientBeforeTheBodyIsParsed() throws Exception {
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

            assertProblem(sendFrom(limitedPort, "203.0.113.80", ENROLLMENTS_SIMULATE, admin.token(), garbage), 400,
                    "validation-failed");
            assertProblem(sendFrom(limitedPort, "203.0.113.81", ENROLLMENTS_SIMULATE, admin.token(), garbage), 400,
                    "validation-failed");
            HttpResponse<String> refused = sendFrom(limitedPort, "203.0.113.82", ENROLLMENTS_APPLY, admin.token(),
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

            assertProblem(sendFrom(limitedPort, "203.0.113.90", ENROLLMENTS_APPLY, admin.token(), garbage), 400,
                    "validation-failed");
            HttpResponse<String> refused = sendFrom(limitedPort, "203.0.113.90", ENROLLMENTS_SIMULATE, admin.token(),
                    garbage);
            assertProblem(refused, 429, "too-many-requests");
            assertThat(refused.headers().firstValue("Retry-After")).isPresent();
            assertProblem(sendFrom(limitedPort, "203.0.113.91", ENROLLMENTS_APPLY, admin.token(), garbage), 400,
                    "validation-failed");
        }
    }

    // --- Isolation, permissions, history ---

    @Test
    void theStudentsAndSectionsOfAnotherInstitutionNeverResolveAndNeverCountAsEnrolled() throws SQLException {
        Member alpha = member(StaffRole.ADMINISTRATOR.code());
        Member beta = member(StaffRole.ADMINISTRATOR.code());
        Structure alphaOrg = structure(alpha.institution());
        section(alphaOrg, alphaOrg.year(), alphaOrg.grade(), sectionName("Uno"), 1, true);
        importStudents(alpha, 2);
        String file = file(line(1, sectionName("Uno")));
        run(ENROLLMENTS_SIMULATE, alpha, file);
        run(ENROLLMENTS_APPLY, alpha, file);

        // Beta has neither students nor structure: nothing of alpha's resolves.
        assertThat(rejectionsOf(json(run(ENROLLMENTS_SIMULATE, beta, file)))).containsExactly(
                List.of(2, "studentLegacyId", "unknown-student"));
        // With only the students imported, the section is still unknown to beta.
        importStudents(beta, 2);
        assertThat(rejectionsOf(json(run(ENROLLMENTS_SIMULATE, beta, file)))).containsExactly(
                List.of(2, "section", "unknown-section"));
        // With the same names in its own structure, alpha's full section and enrollment do not count: beta enrolls.
        Structure betaOrg = structure(beta.institution());
        section(betaOrg, betaOrg.year(), betaOrg.grade(), sectionName("Uno"), 1, true);
        assertThat(rejectionsOf(json(run(ENROLLMENTS_SIMULATE, beta, file)))).isEmpty();
        assertThat(batchOf(run(ENROLLMENTS_APPLY, beta, file)).get("createdRows").asInt()).isEqualTo(1);

        assertThat(countOf("students_enrollment", alpha.institution())).isEqualTo(1);
        assertThat(countOf("students_enrollment", beta.institution())).isEqualTo(1);
        assertThat(json(get(BATCHES + "?kind=enrollments", beta)).get("total").asInt()).isEqualTo(4);
        assertThat(json(get(BATCHES + "?kind=enrollments", alpha)).get("total").asInt()).isEqualTo(2);
    }

    @Test
    void aRoleWithoutTheCreatePermissionOfStudentsGetsA403ThatLeavesNothing() throws SQLException {
        Member cashier = member(StaffRole.CASHIER.code());
        Member auditor = sameInstitution(cashier.institution(), StaffRole.AUDITOR.code());
        Structure org = structure(cashier.institution());
        section(org, org.year(), org.grade(), sectionName("Uno"), 5, true);
        byte[] body = file(line(1, sectionName("Uno"))).getBytes(StandardCharsets.UTF_8);

        for (Member member : List.of(cashier, auditor)) {
            for (String path : List.of(ENROLLMENTS_SIMULATE, ENROLLMENTS_APPLY)) {
                assertProblem(post(path, member.token(), multipart("e.csv", body)), 403, "forbidden");
            }
        }

        assertThat(countOf("imports_batch", cashier.institution())).isZero();
        assertThat(countOf("students_enrollment", cashier.institution())).isZero();
        assertThat(auditActionsOf(cashier.institution()).stream().filter(a -> a.startsWith("imports.")))
                .isEmpty();
    }

    @Test
    void theHistoryListsTheEnrollmentBatchesWithTheKindFilterAndNeedsTheReadPermissionOfStudents() throws Exception {
        install();
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure org = structure(admin.institution());
        section(org, org.year(), org.grade(), sectionName("Uno"), 5, true);
        importStudents(admin, 2);
        run(ENROLLMENTS_SIMULATE, admin, file(line(1, sectionName("Uno"))));
        run(ENROLLMENTS_SIMULATE, admin, file(line(2, sectionName("Uno"))));

        JsonNode page = json(get(BATCHES + "?kind=enrollments", admin));
        assertThat(page.get("total").asInt()).isEqualTo(2);
        assertThat(page.get("items")).allSatisfy(item -> assertThat(item.get("kind").asString())
                .isEqualTo("ENROLLMENTS"));
        assertThat(json(get(BATCHES + "?kind=students", admin)).get("total").asInt()).isEqualTo(2);
        assertThat(json(get(BATCHES, admin)).get("total").asInt()).isEqualTo(4);
        assertProblem(get(BATCHES + "?kind=ENROLLMENTS", admin), 400, "validation-failed");

        Member guardiansOnly = sameInstitution(admin.institution(), StaffRole.ADMINISTRATOR.code());
        SYNTHETIC.put(guardiansOnly.account(), Set.of(Permission.GUARDIANS_READ));
        assertProblem(get(BATCHES + "?kind=enrollments", guardiansOnly), 403, "forbidden");
        assertThat(denialsOf(admin.institution())).contains("students:read");
        assertProblem(get(BATCHES, guardiansOnly), 403, "forbidden");

        Member readerOnly = sameInstitution(admin.institution(), StaffRole.ADMINISTRATOR.code());
        SYNTHETIC.put(readerOnly.account(), Set.of(Permission.STUDENTS_READ));
        assertThat(json(get(BATCHES + "?kind=enrollments", readerOnly)).get("total").asInt()).isEqualTo(2);
    }

    // --- The file and the routes ---

    @Test
    void theFileMustBeTheSimulatedOneOfTheSameKindAndMultipartIsAcceptedOnTheNewRoutesOnly() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure org = structure(admin.institution());
        section(org, org.year(), org.grade(), sectionName("Uno"), 5, true);
        importStudents(admin, 2);
        String file = file(line(1, sectionName("Uno")));

        assertProblem(run(ENROLLMENTS_APPLY, admin, file), 409, "import-not-simulated");
        run(ENROLLMENTS_SIMULATE, admin, file);
        assertProblem(run(ENROLLMENTS_APPLY, admin, file + line(2, sectionName("Uno"))), 409, "import-file-changed");
        assertProblem(run(STUDENTS_APPLY, admin, file), 409, "import-not-simulated");
        assertThat(countOf("students_enrollment", admin.institution())).isZero();

        Body garbage = new Body("multipart/form-data; boundary=zzz",
                "this is not multipart".getBytes(StandardCharsets.UTF_8));
        assertProblem(post(ENROLLMENTS_SIMULATE, admin.token(), garbage), 400, "validation-failed");
        assertProblem(post("/api/v1/imports/enrollments", admin.token(), garbage), 415, "unsupported-media-type");
        JsonNode wrong = assertProblem(run(ENROLLMENTS_SIMULATE, admin, studentsFile(1)), 400, "validation-failed");
        assertThat(wrong.get("errors").get(0).get("reason").asString()).isNotBlank();
        assertThat(countOf("students_enrollment", admin.institution())).isZero();
    }

    // --- The reconciliation of the migration (docs/cortes/imports-reconciliation.md) ---

    private static final String RECONCILIATIONS = "/api/v1/imports/reconciliations";

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static HttpResponse<String> sign(Member member, String year, String reference, String total) {
        String body = "{\"academicYearId\":" + year + ",\"officialReference\":" + reference
                + ",\"officialEnrollmentCount\":" + total + "}";
        return post(RECONCILIATIONS, member.token(), new Body("application/json",
                body.getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpResponse<String> sign(Member member, UUID year, String reference, int total) {
        return sign(member, quoted(year.toString()), quoted(reference), String.valueOf(total));
    }

    private static JsonNode created(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return HarnessProcess.json(response);
    }

    private static void assertCounts(JsonNode counts, int created, int rejected) {
        assertThat(counts.get("created").asInt()).as("created").isEqualTo(created);
        assertThat(counts.get("rejected").asInt()).as("rejected").isEqualTo(rejected);
    }

    @Test
    void anAdministratorSignsTheReconciliationWithTheCountsOfTheMigrationAndTheOfficialTotal() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure org = structure(admin.institution());
        section(org, org.year(), org.grade(), sectionName("Uno"), 50, true);
        importStudents(admin, 5);
        String enrollments = file(line(1, sectionName("Uno")), line(2, sectionName("Uno")),
                line(3, sectionName("Uno")), line(4, sectionName("Uno")), line(5, sectionName("NoExiste")));
        run(ENROLLMENTS_SIMULATE, admin, enrollments);
        JsonNode applied = json(run(ENROLLMENTS_APPLY, admin, enrollments));
        assertThat(applied.get("batch").get("createdRows").asInt()).isEqualTo(4);

        JsonNode preview = json(get(RECONCILIATIONS + "/preview?academicYearId=" + org.year(), admin));
        assertThat(preview.get("academicYearId").asString()).isEqualTo(org.year().toString());
        assertThat(preview.get("enrollmentsActive").asInt()).isEqualTo(4);
        assertCounts(preview.get("students"), 5, 0);
        assertCounts(preview.get("guardians"), 0, 0);
        assertCounts(preview.get("guardianLinks"), 0, 0);
        assertCounts(preview.get("enrollments"), 4, 1);
        assertThat(countOf("imports_reconciliation", admin.institution())).as("a preview writes nothing").isZero();

        JsonNode signed = created(sign(admin, org.year(), "Oficio-123", 5));

        assertThat(signed.get("id").asString()).isNotBlank();
        assertThat(signed.get("academicYearId").asString()).isEqualTo(org.year().toString());
        assertThat(signed.get("officialReference").asString()).isEqualTo("Oficio-123");
        assertThat(signed.get("officialEnrollmentCount").asInt()).isEqualTo(5);
        assertThat(signed.get("enrollmentsActive").asInt()).isEqualTo(4);
        assertThat(signed.get("difference").asInt()).isEqualTo(-1);
        assertCounts(signed.get("students"), 5, 0);
        assertCounts(signed.get("enrollments"), 4, 1);
        assertThat(signed.get("signedBy").asString()).isEqualTo(admin.account().toString());
        assertThat(signed.get("signedAt").asString()).isNotBlank();

        JsonNode history = json(get(RECONCILIATIONS, admin));
        assertThat(history.get("total").asInt()).isEqualTo(1);
        assertThat(history.get("items").get(0).get("id").asString()).isEqualTo(signed.get("id").asString());
        assertThat(column("select official_reference || ':' || official_enrollment_count || ':' || enrollments_active "
                + "|| ':' || students_created || ':' || enrollments_rejected from imports_reconciliation "
                + "where institution_id = ?", admin.institution())).containsExactly("Oficio-123:5:4:5:1");
        assertThat(auditActionsOf(admin.institution()).stream().filter(a -> a.equals("imports.reconciliation.signed")))
                .hasSize(1);
        assertThat(column("select after_value::text from shared_audit_log where institution_id = ? "
                + "and action = 'imports.reconciliation.signed'", admin.institution()).getFirst())
                .contains("Oficio-123", "\"enrollmentsActive\": 4");
    }

    @Test
    void theRejectedCountIsThatOfTheNewestApplicationTheCreatedCountsAreSummedAndSimulationsDoNotCount()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure org = structure(admin.institution());
        section(org, org.year(), org.grade(), sectionName("Uno"), 50, true);
        importStudents(admin, 4);
        String first = file(line(1, sectionName("Uno")), line(2, sectionName("Uno")),
                line(3, sectionName("NoExiste")), line(4, sectionName("NoExiste")));
        run(ENROLLMENTS_SIMULATE, admin, first);
        run(ENROLLMENTS_APPLY, admin, first);
        JsonNode afterFirst = json(get(RECONCILIATIONS + "/preview?academicYearId=" + org.year(), admin));
        assertCounts(afterFirst.get("enrollments"), 2, 2);

        String corrected = file(line(1, sectionName("Uno")), line(2, sectionName("Uno")),
                line(3, sectionName("Uno")), line(4, sectionName("NoExiste")));
        run(ENROLLMENTS_SIMULATE, admin, corrected);
        JsonNode afterSimulation = json(get(RECONCILIATIONS + "/preview?academicYearId=" + org.year(), admin));
        assertCounts(afterSimulation.get("enrollments"), 2, 2);

        run(ENROLLMENTS_APPLY, admin, corrected);
        JsonNode afterSecond = json(get(RECONCILIATIONS + "/preview?academicYearId=" + org.year(), admin));
        assertCounts(afterSecond.get("enrollments"), 3, 1);
        assertThat(afterSecond.get("enrollmentsActive").asInt()).isEqualTo(3);
        assertCounts(afterSecond.get("students"), 4, 0);
    }

    @Test
    void signingTwiceKeepsBothRecordsAndNobodyCanUpdateOrDeleteOne() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure org = structure(admin.institution());
        JsonNode first = created(sign(admin, org.year(), "Oficio-1", 0));
        JsonNode second = created(sign(admin, org.year(), "Oficio-1", 0));

        assertThat(first.get("id").asString()).isNotEqualTo(second.get("id").asString());
        JsonNode history = json(get(RECONCILIATIONS + "?size=1", admin));
        assertThat(history.get("total").asInt()).isEqualTo(2);
        assertThat(history.get("items")).hasSize(1);
        assertThat(history.get("items").get(0).get("id").asString()).isEqualTo(second.get("id").asString());
        assertThat(countOf("imports_reconciliation", admin.institution())).isEqualTo(2);

        try (Connection application = java.sql.DriverManager.getConnection(SharedPostgresContainer.jdbcUrl(),
                "confia_admin_app", TEST_PASSWORD)) {
            for (String statement : List.of("update imports_reconciliation set official_reference = 'x'",
                    "delete from imports_reconciliation")) {
                try (java.sql.Statement sql = application.createStatement()) {
                    assertThatThrownBy(() -> sql.execute(statement))
                            .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
                }
            }
        }
        assertThat(countOf("imports_reconciliation", admin.institution())).isEqualTo(2);
    }

    @Test
    void aBodyThatTheRecordRefusesIsA400AndAYearThatIsNotTheInstitutionsIsA404AndNothingIsWritten()
            throws SQLException {
        Member alpha = member(StaffRole.ADMINISTRATOR.code());
        Member beta = member(StaffRole.ADMINISTRATOR.code());
        Structure alphaOrg = structure(alpha.institution());
        String year = quoted(alphaOrg.year().toString());

        for (String reference : List.of("\"\"", "\"   \"", quoted("x".repeat(101)), quoted("Oficio\n1"), "null")) {
            assertProblem(sign(alpha, year, reference, "5"), 400, "validation-failed");
        }
        assertProblem(sign(alpha, year, quoted("Oficio-1"), "-1"), 400, "validation-failed");
        assertProblem(sign(alpha, year, quoted("Oficio-1"), "1000001"), 400, "validation-failed");
        assertProblem(sign(alpha, year, quoted("Oficio-1"), "null"), 400, "validation-failed");
        assertProblem(sign(alpha, "null", quoted("Oficio-1"), "5"), 400, "validation-failed");
        assertProblem(sign(alpha, quoted("not-a-uuid"), quoted("Oficio-1"), "5"), 400, "validation-failed");
        assertProblem(post(RECONCILIATIONS, alpha.token(), new Body("application/json",
                ("{\"academicYearId\":" + year + ",\"officialReference\":\"Oficio-1\",\"officialEnrollmentCount\":5,"
                        + "\"enrollmentsActive\":999}").getBytes(StandardCharsets.UTF_8))), 400, "validation-failed");
        assertProblem(post(RECONCILIATIONS, alpha.token(), new Body("text/plain", "x".getBytes(StandardCharsets.UTF_8))),
                415, "unsupported-media-type");
        assertProblem(get(RECONCILIATIONS + "/preview", alpha), 400, "validation-failed");
        assertProblem(get(RECONCILIATIONS + "/preview?academicYearId=not-a-uuid", alpha), 400, "validation-failed");
        assertProblem(get(RECONCILIATIONS + "?size=101", alpha), 400, "validation-failed");
        assertProblem(get(RECONCILIATIONS + "?page=-1", alpha), 400, "validation-failed");

        assertProblem(sign(alpha, UUID.randomUUID(), "Oficio-1", 5), 404, "resource-not-found");
        assertProblem(sign(beta, alphaOrg.year(), "Oficio-1", 5), 404, "resource-not-found");
        assertProblem(get(RECONCILIATIONS + "/preview?academicYearId=" + alphaOrg.year(), beta), 404,
                "resource-not-found");

        assertThat(countOf("imports_reconciliation", alpha.institution())).isZero();
        assertThat(countOf("imports_reconciliation", beta.institution())).isZero();
        assertThat(auditActionsOf(alpha.institution()).stream().filter(a -> a.startsWith("imports.reconciliation")))
                .isEmpty();
    }

    @Test
    void theReconciliationsOfOneInstitutionAreInvisibleToAnotherAndTheCountsAreItsOwn() throws SQLException {
        Member alpha = member(StaffRole.ADMINISTRATOR.code());
        Member beta = member(StaffRole.ADMINISTRATOR.code());
        Structure alphaOrg = structure(alpha.institution());
        Structure betaOrg = structure(beta.institution());
        section(alphaOrg, alphaOrg.year(), alphaOrg.grade(), sectionName("Uno"), 5, true);
        importStudents(alpha, 2);
        String file = file(line(1, sectionName("Uno")), line(2, sectionName("Uno")));
        run(ENROLLMENTS_SIMULATE, alpha, file);
        run(ENROLLMENTS_APPLY, alpha, file);
        created(sign(alpha, alphaOrg.year(), "Oficio-A", 2));

        assertThat(json(get(RECONCILIATIONS, beta)).get("total").asInt()).isZero();
        JsonNode betaPreview = json(get(RECONCILIATIONS + "/preview?academicYearId=" + betaOrg.year(), beta));
        assertThat(betaPreview.get("enrollmentsActive").asInt()).isZero();
        assertCounts(betaPreview.get("students"), 0, 0);
        assertCounts(betaPreview.get("enrollments"), 0, 0);
        JsonNode betaSigned = created(sign(beta, betaOrg.year(), "Oficio-B", 0));
        assertThat(betaSigned.get("enrollmentsActive").asInt()).isZero();
        assertThat(json(get(RECONCILIATIONS, alpha)).get("total").asInt()).isEqualTo(1);
    }

    @Test
    void aRoleWithoutStudentsCreateCannotSignAndOneWithoutStudentsReadCannotSeeAnything() throws Exception {
        install();
        Member cashier = member(StaffRole.CASHIER.code());
        Member auditor = sameInstitution(cashier.institution(), StaffRole.AUDITOR.code());
        Member guardiansOnly = sameInstitution(cashier.institution(), StaffRole.ADMINISTRATOR.code());
        SYNTHETIC.put(guardiansOnly.account(), Set.of(Permission.GUARDIANS_READ));
        Member readerOnly = sameInstitution(cashier.institution(), StaffRole.ADMINISTRATOR.code());
        SYNTHETIC.put(readerOnly.account(), Set.of(Permission.STUDENTS_READ));
        Structure org = structure(cashier.institution());

        assertProblem(sign(cashier, org.year(), "Oficio-1", 1), 403, "forbidden");
        assertProblem(sign(auditor, org.year(), "Oficio-1", 1), 403, "forbidden");
        assertProblem(sign(readerOnly, org.year(), "Oficio-1", 1), 403, "forbidden");
        assertProblem(sign(guardiansOnly, org.year(), "Oficio-1", 1), 403, "forbidden");
        assertProblem(get(RECONCILIATIONS, guardiansOnly), 403, "forbidden");
        assertProblem(get(RECONCILIATIONS + "/preview?academicYearId=" + org.year(), guardiansOnly), 403,
                "forbidden");
        assertThat(denialsOf(cashier.institution())).contains("students:read");
        assertThat(json(get(RECONCILIATIONS, readerOnly)).get("total").asInt()).isZero();
        assertThat(json(get(RECONCILIATIONS + "/preview?academicYearId=" + org.year(), readerOnly))
                .get("enrollmentsActive").asInt()).isZero();
        assertThat(json(get(RECONCILIATIONS, auditor)).get("total").asInt()).isZero();

        assertThat(countOf("imports_reconciliation", cashier.institution())).isZero();
        assertThat(auditActionsOf(cashier.institution()).stream().filter(a -> a.startsWith("imports.reconciliation")))
                .isEmpty();
    }

    // --- Permissions the real roles cannot show: a synthetic resolver for chosen accounts ---

    private static final Map<UUID, Set<Permission>> SYNTHETIC = new java.util.concurrent.ConcurrentHashMap<>();

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
        public Set<Permission> resolve(com.confia.shared.security.AuthenticatedActor actor) {
            Set<Permission> chosen = SYNTHETIC.get(actor.accountId());
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

    private void importStudents(Member member, int count) {
        String file = studentsFile(count);
        assertThat(run(STUDENTS_SIMULATE, member, file).statusCode()).isEqualTo(200);
        assertThat(batchOf(run(STUDENTS_APPLY, member, file)).get("createdRows").asInt()).isEqualTo(count);
    }

    private static HttpResponse<String> get(String path, Member member) {
        return execute(request(port, "GET", path, member.token(), null), true);
    }

    private static HttpResponse<String> post(String path, String token, Body body) {
        return execute(request(port, "POST", path, token, body), true);
    }

    private static HttpResponse<String> sendFrom(int targetPort, String address, String path, String token, Body body) {
        return execute(request(targetPort, address, "POST", path, token, body), true);
    }

    private static HttpRequest request(int targetPort, String method, String path, String token, Body body) {
        return request(targetPort, null, method, path, token, body);
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

    private static void exec(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            s.executeUpdate();
        }
    }

    /** The academic structure of the institution, committed as the owner of the schema. */
    private static Structure structure(UUID institution) throws SQLException {
        UUID modality = UUID.randomUUID();
        UUID otherModality = UUID.randomUUID();
        UUID grade = UUID.randomUUID();
        UUID otherGrade = UUID.randomUUID();
        UUID year = UUID.randomUUID();
        UUID closedYear = UUID.randomUUID();
        try (Connection c = StaffRoleFixtures.ownerTransaction(institution)) {
            exec(c, "insert into organization_modality (institution_id, id, code, name) values (?, ?, 'BAS', ?)",
                    institution, modality, MODALITY);
            exec(c, "insert into organization_modality (institution_id, id, code, name) values (?, ?, 'BA2', ?)",
                    institution, otherModality, OTHER_MODALITY);
            exec(c, "insert into organization_grade (institution_id, id, modality_id, name, ordinal) "
                    + "values (?, ?, ?, ?, 7)", institution, grade, modality, GRADE);
            exec(c, "insert into organization_grade (institution_id, id, modality_id, name, ordinal) "
                    + "values (?, ?, ?, ?, 7)", institution, otherGrade, otherModality, GRADE);
            exec(c, """
                    insert into organization_academic_year (institution_id, id, name, start_date, end_date, status)
                    values (?, ?, ?, ?, ?, 'ACTIVE')
                    """, institution, year, YEAR, yearStart(), today().plusDays(100));
            exec(c, """
                    insert into organization_academic_year (institution_id, id, name, start_date, end_date, status)
                    values (?, ?, ?, ?, ?, 'CLOSED')
                    """, institution, closedYear, CLOSED_YEAR, today().minusDays(700), today().minusDays(400));
            c.commit();
        }
        return new Structure(institution, year, closedYear, modality, otherModality, grade, otherGrade);
    }

    private static UUID section(Structure org, UUID year, UUID grade, String name, Integer capacity, boolean active)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = StaffRoleFixtures.ownerTransaction(org.institution())) {
            exec(c, """
                    insert into organization_section (institution_id, id, academic_year_id, grade_id, name, capacity,
                        is_active) values (?, ?, ?, ?, ?, ?, ?)
                    """, org.institution(), id, year, grade, name, capacity, active);
            c.commit();
        }
        return id;
    }

    private static void updateAsOwner(String sql, Object... parameters) throws SQLException {
        try (Connection owner = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    // --- Reading the database as the superuser of the container ---

    private static long countOf(String table, UUID institution) throws SQLException {
        return Long.parseLong(column("select count(*)::text from " + table + " where institution_id = ?",
                institution).getFirst());
    }

    private static UUID sectionId(Member member, String suffix) throws SQLException {
        return UUID.fromString(column("select id::text from organization_section where institution_id = ? "
                + "and name = '" + sectionName(suffix) + "'", member.institution()).getFirst());
    }

    private static long activeIn(UUID section) throws SQLException {
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

    private static long enrollmentAuditCount(UUID institution) throws SQLException {
        return auditActionsOf(institution).stream().filter(a -> a.startsWith("students.enrollment.")).count();
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
}

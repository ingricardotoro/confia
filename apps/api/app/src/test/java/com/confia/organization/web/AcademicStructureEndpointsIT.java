package com.confia.organization.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The twelve routes of the academic structure on the real administrative process, started through the
 * production launcher against a real PostgreSQL with the least-privilege role of the application
 * (docs/cortes/organization-academic-structure.md). Every token is made by the production issuer; the
 * accounts, the roles and the institution rows are seeded as the owner of the schema; every test has
 * an institution of its own, so no test sees the rows of another.
 *
 * <p>It covers the acceptance criterion (an administrator creates a year, a modality, a grade and a
 * section and lists them), each rule of the cut with the code it answers, the role without the
 * permission (a {@code 403} that leaves no row and no entry of the structure), the isolation between
 * institutions, the audit entry of every write and the two races the database and the lock settle.
 */
class AcademicStructureEndpointsIT extends CommittingPostgresIntegrationTest {

    private static final String YEARS = "/api/v1/organization/academic-years";
    private static final String MODALITIES = "/api/v1/organization/modalities";
    private static final String GRADES = "/api/v1/organization/grades";
    private static final String SECTIONS = "/api/v1/organization/sections";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static KeyPair keys;
    private static CompactJws jws;
    private static ConfigurableApplicationContext process;
    private static int port;

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

    /** One seeded staff member of one institution, with its live session and its token. */
    private record Member(UUID account, UUID institution, UUID family, String token) {
    }

    // --- The acceptance criterion ---

    @Test
    void anAdministratorCreatesAYearAModalityAGradeAndASectionAndSeesThemListed() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        JsonNode year = created(post(YEARS, admin, yearBody("2026", "2026-02-01", "2026-11-30", null)));
        JsonNode modality = created(post(MODALITIES, admin, modalityBody("BACH", "Bachillerato")));
        JsonNode grade = created(post(GRADES, admin, gradeBody(modality.get("id").asString(), "Primero", 1)));
        JsonNode section = created(post(SECTIONS, admin, sectionBody(year.get("id").asString(),
                grade.get("id").asString(), "A", 30)));

        assertThat(year.propertyNames()).containsExactly("id", "name", "startDate", "endDate", "status",
                "isActive", "createdAt", "updatedAt");
        assertThat(year.get("status").asString()).isEqualTo("PLANNED");
        assertThat(year.get("isActive").asBoolean()).isTrue();
        assertThat(year.get("startDate").asString()).isEqualTo("2026-02-01");
        assertThat(modality.propertyNames()).containsExactly("id", "code", "name", "isActive",
                "createdAt", "updatedAt");
        assertThat(grade.propertyNames()).containsExactly("id", "modalityId", "name", "ordinal",
                "isActive", "createdAt", "updatedAt");
        assertThat(section.propertyNames()).containsExactly("id", "academicYearId", "gradeId", "name",
                "capacity", "isActive", "createdAt", "updatedAt");

        for (String route : List.of(YEARS, MODALITIES, GRADES, SECTIONS)) {
            JsonNode page = HarnessProcess.json(get(route, admin));
            assertThat(page.propertyNames()).containsExactly("items", "total", "page", "size");
            assertThat(page.get("total").asLong()).isEqualTo(1);
            assertThat(page.get("page").asInt()).isZero();
            assertThat(page.get("size").asInt()).isEqualTo(50);
            assertThat(page.get("items")).hasSize(1);
        }
    }

    @Test
    void theResponsesCarryNoStoreAndNeverTheInstitutionOfTheToken() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        HttpResponse<String> response = post(YEARS, admin, yearBody("2026", "2026-02-01", "2026-11-30", null));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(response.body()).doesNotContain(admin.institution().toString())
                .doesNotContain(admin.account().toString());
    }

    // --- Validation at the edge ---

    @Test
    void anEndNotAfterTheStartAndABlankNameAreRefusedWith400AndLeaveNoRow() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertProblem(post(YEARS, admin, yearBody("2026", "2026-02-01", "2026-02-01", null)), 400,
                "validation-failed");
        assertProblem(post(YEARS, admin, yearBody("2026", "2026-03-01", "2026-02-01", null)), 400,
                "validation-failed");
        assertProblem(post(YEARS, admin, yearBody("  ", "2026-02-01", "2026-11-30", null)), 400,
                "validation-failed");
        assertProblem(post(YEARS, admin, yearBody("2026", "2026-02-01", "2026-11-30", "CLOSED")), 400,
                "validation-failed");

        assertThat(countOf("organization_academic_year", admin.institution())).isZero();
        assertThat(auditActionsOf(admin.institution())).isEmpty();
    }

    @Test
    void aCapacityOfZeroOrBelowIsRefusedWith400AndAMissingOneIsAccepted() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String year = created(post(YEARS, admin, yearBody("2026", "2026-02-01", "2026-11-30", null)))
                .get("id").asString();
        String modality = created(post(MODALITIES, admin, modalityBody("BACH", "Bachillerato")))
                .get("id").asString();
        String grade = created(post(GRADES, admin, gradeBody(modality, "Primero", 1))).get("id").asString();

        assertProblem(post(SECTIONS, admin, sectionBody(year, grade, "A", 0)), 400, "validation-failed");
        assertProblem(post(SECTIONS, admin, sectionBody(year, grade, "A", -4)), 400, "validation-failed");
        JsonNode noCapacity = created(post(SECTIONS, admin, "{\"academicYearId\":\"" + year
                + "\",\"gradeId\":\"" + grade + "\",\"name\":\"B\"}"));

        assertThat(noCapacity.get("capacity").isNull()).isTrue();
        assertThat(countOf("organization_section", admin.institution())).isEqualTo(1);
    }

    @Test
    void anUnknownMemberAnInstitutionInTheBodyAndAMalformedIdentifierAreRefused() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertProblem(post(MODALITIES, admin, "{\"code\":\"A\",\"name\":\"B\",\"institutionId\":\""
                + UUID.randomUUID() + "\"}"), 400, "validation-failed");
        assertProblem(send("PUT", MODALITIES + "/not-a-uuid", admin.token(),
                "{\"code\":\"A\",\"name\":\"B\",\"isActive\":true}"), 400, "validation-failed");
        assertProblem(post(MODALITIES, admin, "{\"code\":\"A\"}"), 400, "validation-failed");
        assertProblem(send("PUT", MODALITIES + "/" + UUID.randomUUID(), admin.token(),
                "{\"code\":\"A\",\"name\":\"B\"}"), 400, "validation-failed");
    }

    @Test
    void thePageParametersAreBoundedByTheServer() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertProblem(get(YEARS + "?size=201", admin), 400, "validation-failed");
        assertProblem(get(YEARS + "?size=0", admin), 400, "validation-failed");
        assertProblem(get(YEARS + "?page=-1", admin), 400, "validation-failed");
        assertProblem(get(YEARS + "?sort=password", admin), 400, "validation-failed");
        assertProblem(get(YEARS + "?sort=name,sideways", admin), 400, "validation-failed");
        assertProblem(get(MODALITIES + "?sort=startDate", admin), 400, "validation-failed");
        assertThat(HarnessProcess.json(get(YEARS + "?size=200", admin)).get("size").asInt()).isEqualTo(200);
    }

    // --- The rules, with the code each one answers ---

    @Test
    void aYearThatSharesASingleDayWithAnotherIsAnOverlapAndTheNextDayIsNot() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        created(post(YEARS, admin, yearBody("2026", "2026-02-01", "2026-11-30", null)));

        assertProblem(post(YEARS, admin, yearBody("2027", "2026-11-30", "2027-11-30", null)), 409,
                "academic-year-overlap");
        assertProblem(post(YEARS, admin, yearBody("2025", "2025-02-01", "2026-02-01", null)), 409,
                "academic-year-overlap");
        assertProblem(post(YEARS, admin, yearBody("inside", "2026-04-01", "2026-05-01", null)), 409,
                "academic-year-overlap");
        created(post(YEARS, admin, yearBody("2027", "2026-12-01", "2027-11-30", null)));
        created(post(YEARS, admin, yearBody("2025", "2025-02-01", "2026-01-31", null)));

        assertThat(countOf("organization_academic_year", admin.institution())).isEqualTo(3);
    }

    @Test
    void anEditThatMovesAYearOntoAnotherIsAnOverlapAndAnEditInsideItsOwnRangeIsNot() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String first = created(post(YEARS, admin, yearBody("2026", "2026-02-01", "2026-11-30", null)))
                .get("id").asString();
        created(post(YEARS, admin, yearBody("2027", "2026-12-01", "2027-11-30", null)));

        assertProblem(put(YEARS, first, admin, yearPut("2026", "2026-02-01", "2026-12-01", "PLANNED", true)),
                409, "academic-year-overlap");
        assertThat(put(YEARS, first, admin, yearPut("2026", "2026-02-01", "2026-11-29", "PLANNED", true))
                .statusCode()).isEqualTo(200);
    }

    @Test
    void aSecondActiveYearIsRefusedAndClosingTheActiveOneLetsAnotherBeActivated() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String first = created(post(YEARS, admin, yearBody("2026", "2026-02-01", "2026-11-30", "ACTIVE")))
                .get("id").asString();
        String second = created(post(YEARS, admin, yearBody("2027", "2026-12-01", "2027-11-30", null)))
                .get("id").asString();

        assertProblem(post(YEARS, admin, yearBody("2028", "2027-12-01", "2028-11-30", "ACTIVE")), 409,
                "academic-year-already-active");
        assertProblem(put(YEARS, second, admin, yearPut("2027", "2026-12-01", "2027-11-30", "ACTIVE", true)),
                409, "academic-year-already-active");
        assertThat(put(YEARS, first, admin, yearPut("2026", "2026-02-01", "2026-11-30", "CLOSED", true))
                .statusCode()).isEqualTo(200);
        assertThat(put(YEARS, second, admin, yearPut("2027", "2026-12-01", "2027-11-30", "ACTIVE", true))
                .statusCode()).isEqualTo(200);
    }

    @Test
    void aClosedYearIsNeitherEditedNorReopenedNorGivenASection() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String year = created(post(YEARS, admin, yearBody("2025", "2025-02-01", "2025-11-30", null)))
                .get("id").asString();
        String modality = created(post(MODALITIES, admin, modalityBody("BACH", "Bachillerato")))
                .get("id").asString();
        String grade = created(post(GRADES, admin, gradeBody(modality, "Primero", 1))).get("id").asString();
        String section = created(post(SECTIONS, admin, sectionBody(year, grade, "A", 20))).get("id").asString();
        assertThat(put(YEARS, year, admin, yearPut("2025", "2025-02-01", "2025-11-30", "CLOSED", true))
                .statusCode()).isEqualTo(200);
        int before = auditActionsOf(admin.institution()).size();

        assertProblem(put(YEARS, year, admin, yearPut("2025", "2025-02-01", "2025-11-30", "ACTIVE", true)),
                409, "academic-year-closed");
        assertProblem(put(YEARS, year, admin, yearPut("renamed", "2025-02-01", "2025-11-30", "CLOSED", true)),
                409, "academic-year-closed");
        assertProblem(put(YEARS, year, admin, yearPut("2025", "2025-02-01", "2025-11-30", "CLOSED", false)),
                409, "academic-year-closed");
        assertProblem(post(SECTIONS, admin, sectionBody(year, grade, "B", 20)), 409, "academic-year-closed");
        assertProblem(put(SECTIONS, section, admin, sectionPut("A", 25, true)), 409, "academic-year-closed");

        assertThat(auditActionsOf(admin.institution())).as("a refusal leaves no entry").hasSize(before);
        JsonNode closed = HarnessProcess.json(get(YEARS, admin)).get("items").get(0);
        assertThat(closed.get("name").asString()).isEqualTo("2025");
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(countOf("organization_section", admin.institution())).isEqualTo(1);
    }

    @Test
    void aNameCodeOrOrdinalAlreadyTakenIsADuplicateNameInItsScope() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String year = created(post(YEARS, admin, yearBody("2026", "2026-02-01", "2026-11-30", null)))
                .get("id").asString();
        String modality = created(post(MODALITIES, admin, modalityBody("BACH", "Bachillerato")))
                .get("id").asString();
        String other = created(post(MODALITIES, admin, modalityBody("TEC", "Tecnico"))).get("id").asString();
        String grade = created(post(GRADES, admin, gradeBody(modality, "Primero", 1))).get("id").asString();
        created(post(SECTIONS, admin, sectionBody(year, grade, "A", 30)));

        assertProblem(post(YEARS, admin, yearBody("2026", "2030-01-01", "2030-12-01", null)), 409,
                "duplicate-name");
        assertProblem(post(MODALITIES, admin, modalityBody("BACH", "Otro nombre")), 409, "duplicate-name");
        assertProblem(post(GRADES, admin, gradeBody(modality, "Segundo", 1)), 409, "duplicate-name");
        assertProblem(post(GRADES, admin, gradeBody(modality, "Primero", 2)), 409, "duplicate-name");
        assertProblem(post(SECTIONS, admin, sectionBody(year, grade, "A", 10)), 409, "duplicate-name");
        assertProblem(put(MODALITIES, other, admin, "{\"code\":\"BACH\",\"name\":\"Tecnico\",\"isActive\":true}"),
                409, "duplicate-name");
        created(post(GRADES, admin, gradeBody(other, "Primero", 1)));
        assertThat(countOf("organization_grade", admin.institution())).isEqualTo(2);
    }

    @Test
    void aGradeNeedsAnActiveModalityAndASectionNeedsAnActiveGrade() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String year = created(post(YEARS, admin, yearBody("2026", "2026-02-01", "2026-11-30", null)))
                .get("id").asString();
        String modality = created(post(MODALITIES, admin, modalityBody("BACH", "Bachillerato")))
                .get("id").asString();
        String grade = created(post(GRADES, admin, gradeBody(modality, "Primero", 1))).get("id").asString();
        put(GRADES, grade, admin, "{\"name\":\"Primero\",\"ordinal\":1,\"isActive\":false}");
        put(MODALITIES, modality, admin, "{\"code\":\"BACH\",\"name\":\"Bachillerato\",\"isActive\":false}");

        assertProblem(post(GRADES, admin, gradeBody(modality, "Segundo", 2)), 409, "inactive-reference");
        assertProblem(post(SECTIONS, admin, sectionBody(year, grade, "A", 30)), 409, "inactive-reference");
    }

    @Test
    void aReferenceThatDoesNotExistInTheInstitutionIsNotFound() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertProblem(post(GRADES, admin, gradeBody(UUID.randomUUID().toString(), "Primero", 1)), 404,
                "resource-not-found");
        assertProblem(post(SECTIONS, admin, sectionBody(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "A", 1)), 404, "resource-not-found");
        for (String route : List.of(YEARS, MODALITIES, GRADES, SECTIONS)) {
            assertProblem(send("PUT", route + "/" + UUID.randomUUID(), admin.token(),
                    putBodyFor(route)), 404, "resource-not-found");
        }
    }

    // --- Deactivation: nothing is deleted ---

    @Test
    void deactivatingAModalityAndAGradeLeavesTheirRowsListedAndTheSectionsUntouched() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String year = created(post(YEARS, admin, yearBody("2026", "2026-02-01", "2026-11-30", null)))
                .get("id").asString();
        String modality = created(post(MODALITIES, admin, modalityBody("BACH", "Bachillerato")))
                .get("id").asString();
        String grade = created(post(GRADES, admin, gradeBody(modality, "Primero", 1))).get("id").asString();
        created(post(SECTIONS, admin, sectionBody(year, grade, "A", 30)));

        assertThat(put(GRADES, grade, admin, "{\"name\":\"Primero\",\"ordinal\":1,\"isActive\":false}")
                .statusCode()).isEqualTo(200);
        assertThat(put(MODALITIES, modality, admin,
                "{\"code\":\"BACH\",\"name\":\"Bachillerato\",\"isActive\":false}").statusCode())
                .isEqualTo(200);

        JsonNode modalities = HarnessProcess.json(get(MODALITIES, admin));
        assertThat(modalities.get("total").asLong()).isEqualTo(1);
        assertThat(modalities.get("items").get(0).get("isActive").asBoolean()).isFalse();
        JsonNode grades = HarnessProcess.json(get(GRADES, admin));
        assertThat(grades.get("items").get(0).get("isActive").asBoolean()).isFalse();
        JsonNode sections = HarnessProcess.json(get(SECTIONS, admin));
        assertThat(sections.get("total").asLong()).isEqualTo(1);
        assertThat(sections.get("items").get(0).get("isActive").asBoolean()).isTrue();
    }

    @Test
    void aSectionOfADeactivatedGradeStillAcceptsEdits() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String year = created(post(YEARS, admin, yearBody("2026", "2026-02-01", "2026-11-30", null)))
                .get("id").asString();
        String modality = created(post(MODALITIES, admin, modalityBody("BACH", "Bachillerato")))
                .get("id").asString();
        String grade = created(post(GRADES, admin, gradeBody(modality, "Primero", 1))).get("id").asString();
        String section = created(post(SECTIONS, admin, sectionBody(year, grade, "A", 30))).get("id").asString();
        put(GRADES, grade, admin, "{\"name\":\"Primero\",\"ordinal\":1,\"isActive\":false}");

        HttpResponse<String> edited = put(SECTIONS, section, admin, sectionPut("A", 35, true));

        assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(HarnessProcess.json(edited).get("capacity").asInt()).isEqualTo(35);
    }

    // --- Lists ---

    @Test
    void theListsPageSortAndFilterOnTheServer() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String year = created(post(YEARS, admin, yearBody("2026", "2026-02-01", "2026-11-30", null)))
                .get("id").asString();
        String bach = created(post(MODALITIES, admin, modalityBody("BACH", "Bachillerato"))).get("id").asString();
        String tec = created(post(MODALITIES, admin, modalityBody("TEC", "Tecnico"))).get("id").asString();
        String g1 = created(post(GRADES, admin, gradeBody(bach, "Primero", 1))).get("id").asString();
        String g2 = created(post(GRADES, admin, gradeBody(bach, "Segundo", 2))).get("id").asString();
        created(post(GRADES, admin, gradeBody(tec, "Primero", 1)));
        created(post(SECTIONS, admin, sectionBody(year, g1, "A", 10)));
        created(post(SECTIONS, admin, sectionBody(year, g1, "B", 20)));
        created(post(SECTIONS, admin, sectionBody(year, g2, "A", 30)));

        JsonNode firstPage = HarnessProcess.json(get(GRADES + "?size=2&page=0&sort=ordinal", admin));
        JsonNode secondPage = HarnessProcess.json(get(GRADES + "?size=2&page=1&sort=ordinal", admin));
        assertThat(firstPage.get("total").asLong()).isEqualTo(3);
        assertThat(firstPage.get("items")).hasSize(2);
        assertThat(secondPage.get("items")).hasSize(1);
        assertThat(secondPage.get("page").asInt()).isEqualTo(1);
        assertThat(HarnessProcess.json(get(GRADES + "?page=9", admin)).get("items")).isEmpty();

        JsonNode byModality = HarnessProcess.json(get(GRADES + "?modalityId=" + bach + "&sort=ordinal,desc", admin));
        assertThat(byModality.get("total").asLong()).isEqualTo(2);
        assertThat(names(byModality)).containsExactly("Segundo", "Primero");

        assertThat(HarnessProcess.json(get(SECTIONS + "?gradeId=" + g1, admin)).get("total").asLong())
                .isEqualTo(2);
        assertThat(HarnessProcess.json(get(SECTIONS + "?academicYearId=" + year + "&gradeId=" + g2, admin))
                .get("total").asLong()).isEqualTo(1);
        assertThat(HarnessProcess.json(get(SECTIONS + "?academicYearId=" + UUID.randomUUID(), admin))
                .get("total").asLong()).isZero();
        assertThat(names(HarnessProcess.json(get(SECTIONS + "?sort=capacity,desc", admin))))
                .containsExactly("A", "B", "A");
        assertThat(names(HarnessProcess.json(get(MODALITIES + "?sort=name,desc", admin))))
                .containsExactly("Tecnico", "Bachillerato");
        assertThat(names(HarnessProcess.json(get(MODALITIES + "?sort=code", admin))))
                .containsExactly("Bachillerato", "Tecnico");
    }

    // --- Roles: the decision is the server's ---

    @Test
    void aCashierMayReadButCannotCreateOrEditAndTheRefusalLeavesNoRowAndNoStructureEntry()
            throws SQLException {
        Member cashier = member(StaffRole.CASHIER.code());
        Member admin = sameInstitution(cashier.institution(), StaffRole.ADMINISTRATOR.code());
        String modality = created(post(MODALITIES, admin, modalityBody("BACH", "Bachillerato")))
                .get("id").asString();
        int entriesBefore = structureEntriesOf(cashier.institution()).size();

        assertThat(get(MODALITIES, cashier).statusCode()).isEqualTo(200);
        assertProblem(post(MODALITIES, cashier, modalityBody("TEC", "Tecnico")), 403, "forbidden");
        assertProblem(post(YEARS, cashier, yearBody("2026", "2026-02-01", "2026-11-30", null)), 403, "forbidden");
        assertProblem(post(GRADES, cashier, gradeBody(modality, "Primero", 1)), 403, "forbidden");
        assertProblem(post(SECTIONS, cashier, sectionBody(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "A", 1)), 403, "forbidden");
        assertProblem(put(MODALITIES, modality, cashier,
                "{\"code\":\"BACH\",\"name\":\"Cambiado\",\"isActive\":false}"), 403, "forbidden");

        assertThat(countOf("organization_modality", cashier.institution())).isEqualTo(1);
        assertThat(countOf("organization_academic_year", cashier.institution())).isZero();
        assertThat(countOf("organization_grade", cashier.institution())).isZero();
        assertThat(HarnessProcess.json(get(MODALITIES, admin)).get("items").get(0).get("name").asString())
                .isEqualTo("Bachillerato");
        assertThat(structureEntriesOf(cashier.institution())).as("no write entry for a refused request")
                .hasSize(entriesBefore);
        assertThat(auditActionsOf(cashier.institution()).stream()
                .filter("authorization.access.denied"::equals)).hasSize(5);
    }

    @Test
    void anAccountWithoutARoleCannotEvenRead() throws SQLException {
        Member nobody = member(null);

        for (String route : List.of(YEARS, MODALITIES, GRADES, SECTIONS)) {
            assertProblem(get(route, nobody), 403, "forbidden");
        }
    }

    @Test
    void noCredentialAnswers401() {
        for (String route : List.of(YEARS, MODALITIES, GRADES, SECTIONS)) {
            assertProblem(send("GET", route, null, null), 401, "authentication-required");
        }
    }

    @Test
    void aClosedSessionOpensNoRouteOfTheStructure() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String modality = created(post(MODALITIES, admin, modalityBody("BACH", "Bachillerato")))
                .get("id").asString();
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

        assertProblem(get(MODALITIES, admin), 401, "token-invalid");
        assertProblem(post(MODALITIES, admin, modalityBody("TEC", "Tecnico")), 401, "token-invalid");
        assertProblem(put(MODALITIES, modality, admin,
                "{\"code\":\"BACH\",\"name\":\"Cambiado\",\"isActive\":false}"), 401, "token-invalid");
        assertThat(countOf("organization_modality", admin.institution())).isEqualTo(1);
    }

    @Test
    void aWriteWithoutAnAllowedOriginIsRefusedBeforeAnyEffect() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        HttpResponse<String> response = execute(HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + MODALITIES))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + admin.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(modalityBody("BACH", "Bachillerato"))).build());

        assertProblem(response, 403, "csrf-invalid");
        assertThat(countOf("organization_modality", admin.institution())).isZero();
    }

    // --- Isolation between institutions ---

    @Test
    void aUserOfAnotherInstitutionNeverSeesNorEditsTheRowsOfThisOne() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        String year = created(post(YEARS, mine, yearBody("2026", "2026-02-01", "2026-11-30", null)))
                .get("id").asString();
        String modality = created(post(MODALITIES, mine, modalityBody("BACH", "Bachillerato"))).get("id").asString();
        String grade = created(post(GRADES, mine, gradeBody(modality, "Primero", 1))).get("id").asString();
        created(post(SECTIONS, mine, sectionBody(year, grade, "A", 30)));

        for (String route : List.of(YEARS, MODALITIES, GRADES, SECTIONS)) {
            JsonNode page = HarnessProcess.json(get(route, theirs));
            assertThat(page.get("total").asLong()).as("rows of %s seen by the other institution", route)
                    .isZero();
            assertThat(page.get("items")).isEmpty();
        }
        assertProblem(put(YEARS, year, theirs, yearPut("hijack", "2026-02-01", "2026-11-30", "PLANNED", true)),
                404, "resource-not-found");
        assertProblem(put(MODALITIES, modality, theirs,
                "{\"code\":\"BACH\",\"name\":\"hijack\",\"isActive\":false}"), 404, "resource-not-found");
        assertProblem(post(GRADES, theirs, gradeBody(modality, "Segundo", 2)), 404, "resource-not-found");
        assertProblem(post(SECTIONS, theirs, sectionBody(year, grade, "B", 1)), 404, "resource-not-found");
        // The same names and the same range are free in the other institution.
        created(post(YEARS, theirs, yearBody("2026", "2026-02-01", "2026-11-30", "ACTIVE")));
        created(post(MODALITIES, theirs, modalityBody("BACH", "Bachillerato")));

        assertThat(HarnessProcess.json(get(MODALITIES, mine)).get("items").get(0).get("name").asString())
                .isEqualTo("Bachillerato");
    }

    @Test
    void aBodyThatNamesAnotherInstitutionChangesNothingBecauseTheTokenDecides() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());

        HttpResponse<String> response = post(MODALITIES, mine, "{\"code\":\"BACH\",\"name\":\"B\","
                + "\"institutionId\":\"" + theirs.institution() + "\"}");

        assertProblem(response, 400, "validation-failed");
        assertThat(countOf("organization_modality", theirs.institution())).isZero();
        assertThat(countOf("organization_modality", mine.institution())).isZero();
    }

    // --- Audit ---

    @Test
    void everyWriteLeavesOneEntryWithTheStateBeforeAndAfterInTheInstitutionOfTheToken()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        JsonNode year = created(post(YEARS, admin, yearBody("2026", "2026-02-01", "2026-11-30", null)));
        JsonNode modality = created(post(MODALITIES, admin, modalityBody("BACH", "Bachillerato")));
        JsonNode grade = created(post(GRADES, admin, gradeBody(modality.get("id").asString(), "Primero", 1)));
        JsonNode section = created(post(SECTIONS, admin, sectionBody(year.get("id").asString(),
                grade.get("id").asString(), "A", 30)));
        put(YEARS, year.get("id").asString(), admin, yearPut("2026", "2026-02-01", "2026-11-30", "ACTIVE", true));
        put(MODALITIES, modality.get("id").asString(), admin,
                "{\"code\":\"BACH\",\"name\":\"Bachillerato\",\"isActive\":false}");
        put(GRADES, grade.get("id").asString(), admin, "{\"name\":\"Primero\",\"ordinal\":1,\"isActive\":false}");
        put(SECTIONS, section.get("id").asString(), admin, sectionPut("A", 31, true));
        get(YEARS, admin);

        List<String[]> entries = structureEntriesOf(admin.institution());
        assertThat(entries).extracting(row -> row[0]).containsExactly(
                "organization.academic_year.created", "organization.modality.created",
                "organization.grade.created", "organization.section.created",
                "organization.academic_year.updated", "organization.modality.updated",
                "organization.grade.updated", "organization.section.updated");
        assertThat(entries).extracting(row -> row[2]).containsExactly(year.get("id").asString(),
                modality.get("id").asString(), grade.get("id").asString(), section.get("id").asString(),
                year.get("id").asString(), modality.get("id").asString(), grade.get("id").asString(),
                section.get("id").asString());
        assertThat(entries.get(0)[3]).as("a creation has no before state").isNull();
        assertThat(json(entries.get(0)[4]).get("name").asString()).isEqualTo("2026");
        assertThat(json(entries.get(0)[4]).get("status").asString()).isEqualTo("PLANNED");
        assertThat(json(entries.get(4)[3]).get("status").asString()).isEqualTo("PLANNED");
        assertThat(json(entries.get(4)[4]).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(json(entries.get(5)[3]).get("isActive").asBoolean()).isTrue();
        assertThat(json(entries.get(5)[4]).get("isActive").asBoolean()).isFalse();
        assertThat(json(entries.get(7)[3]).get("capacity").asInt()).isEqualTo(30);
        assertThat(json(entries.get(7)[4]).get("capacity").asInt()).isEqualTo(31);
        assertThat(actorsOfStructureEntries(admin.institution())).containsOnly(admin.account().toString());
    }

    @Test
    void aWriteThatRollsBackLeavesNeitherTheRowNorTheEntry() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        created(post(MODALITIES, admin, modalityBody("BACH", "Bachillerato")));
        int entries = structureEntriesOf(admin.institution()).size();

        assertProblem(post(MODALITIES, admin, modalityBody("BACH", "Otro")), 409, "duplicate-name");

        assertThat(countOf("organization_modality", admin.institution())).isEqualTo(1);
        assertThat(structureEntriesOf(admin.institution())).hasSize(entries);
    }

    // --- The races the database and the lock settle ---

    @Test
    void twoRequestsThatCreateOverlappingYearsAtOnceLeaveExactlyOne() throws Exception {
        for (int round = 0; round < 4; round++) {
            Member admin = member(StaffRole.ADMINISTRATOR.code());
            List<HttpResponse<String>> answers = inParallel(
                    () -> post(YEARS, admin, yearBody("A", "2026-02-01", "2026-11-30", null)),
                    () -> post(YEARS, admin, yearBody("B", "2026-06-01", "2027-03-30", null)));

            assertThat(answers).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
            HttpResponse<String> loser = answers.stream().filter(a -> a.statusCode() == 409).findFirst()
                    .orElseThrow();
            assertProblem(loser, 409, "academic-year-overlap");
            assertThat(countOf("organization_academic_year", admin.institution())).isEqualTo(1);
        }
    }

    @Test
    void twoRequestsThatActivateDifferentYearsAtOnceLeaveExactlyOneActive() throws Exception {
        for (int round = 0; round < 4; round++) {
            Member admin = member(StaffRole.ADMINISTRATOR.code());
            String first = created(post(YEARS, admin, yearBody("A", "2026-02-01", "2026-11-30", null)))
                    .get("id").asString();
            String second = created(post(YEARS, admin, yearBody("B", "2026-12-01", "2027-11-30", null)))
                    .get("id").asString();
            List<HttpResponse<String>> answers = inParallel(
                    () -> put(YEARS, first, admin, yearPut("A", "2026-02-01", "2026-11-30", "ACTIVE", true)),
                    () -> put(YEARS, second, admin, yearPut("B", "2026-12-01", "2027-11-30", "ACTIVE", true)));

            assertThat(answers).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
            assertProblem(answers.stream().filter(a -> a.statusCode() == 409).findFirst().orElseThrow(), 409,
                    "academic-year-already-active");
            assertThat(countActiveYears(admin.institution())).isEqualTo(1);
        }
    }

    @Test
    void twoRequestsThatCreateTheSameModalityCodeAtOnceLeaveOneAndAnswer409ToTheOther() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        List<HttpResponse<String>> answers = inParallel(
                () -> post(MODALITIES, admin, modalityBody("BACH", "Uno")),
                () -> post(MODALITIES, admin, modalityBody("BACH", "Dos")));

        assertThat(answers).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
        assertProblem(answers.stream().filter(a -> a.statusCode() == 409).findFirst().orElseThrow(), 409,
                "duplicate-name");
        assertThat(countOf("organization_modality", admin.institution())).isEqualTo(1);
    }

    // --- Bodies ---

    private static String yearBody(String name, String start, String end, String status) {
        return "{\"name\":\"" + name + "\",\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\""
                + (status == null ? "" : ",\"status\":\"" + status + "\"") + "}";
    }

    private static String yearPut(String name, String start, String end, String status, boolean active) {
        return "{\"name\":\"" + name + "\",\"startDate\":\"" + start + "\",\"endDate\":\"" + end
                + "\",\"status\":\"" + status + "\",\"isActive\":" + active + "}";
    }

    private static String modalityBody(String code, String name) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}";
    }

    private static String gradeBody(String modalityId, String name, int ordinal) {
        return "{\"modalityId\":\"" + modalityId + "\",\"name\":\"" + name + "\",\"ordinal\":" + ordinal + "}";
    }

    private static String sectionBody(String yearId, String gradeId, String name, int capacity) {
        return "{\"academicYearId\":\"" + yearId + "\",\"gradeId\":\"" + gradeId + "\",\"name\":\"" + name
                + "\",\"capacity\":" + capacity + "}";
    }

    private static String sectionPut(String name, int capacity, boolean active) {
        return "{\"name\":\"" + name + "\",\"capacity\":" + capacity + ",\"isActive\":" + active + "}";
    }

    private static String putBodyFor(String route) {
        return switch (route) {
            case YEARS -> yearPut("x", "2026-02-01", "2026-11-30", "PLANNED", true);
            case MODALITIES -> "{\"code\":\"X\",\"name\":\"x\",\"isActive\":true}";
            case GRADES -> "{\"name\":\"x\",\"ordinal\":1,\"isActive\":true}";
            default -> sectionPut("x", 1, true);
        };
    }

    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("items").forEach(item -> names.add(item.has("name") ? item.get("name").asString() : ""));
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

    private static long countActiveYears(UUID institution) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("select count(*) from "
                        + "organization_academic_year where institution_id = ? and status = 'ACTIVE'")) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
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

    /** action, entity type, entity id, before, after of every entry of the structure, oldest first. */
    private static List<String[]> structureEntriesOf(UUID institution) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select action, entity_type, entity_id, before_value::text, after_value::text
                          from shared_audit_log
                         where institution_id = ? and action like 'organization.%' order by id
                        """)) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new String[] {result.getString(1), result.getString(2),
                            result.getString(3), result.getString(4), result.getString(5)});
                }
            }
        }
        return rows;
    }

    private static List<String> actorsOfStructureEntries(UUID institution) throws SQLException {
        List<String> actors = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select actor_id::text from shared_audit_log
                         where institution_id = ? and action like 'organization.%'
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

    // --- HTTP ---

    @SafeVarargs
    private static List<HttpResponse<String>> inParallel(java.util.concurrent.Callable<HttpResponse<String>>... calls)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch ready = new CountDownLatch(calls.length);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (java.util.concurrent.Callable<HttpResponse<String>> call : calls) {
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

    private static HttpResponse<String> put(String route, String id, Member member, String body) {
        return send("PUT", route + "/" + id, member.token(), body);
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
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

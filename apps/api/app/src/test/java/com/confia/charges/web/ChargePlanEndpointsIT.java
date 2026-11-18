package com.confia.charges.web;

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
import java.util.Map;
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
 * The six routes of the charge plans on the real administrative process, started through the production launcher against
 * a real PostgreSQL with the least-privilege role of the application (docs/cortes/charges-plans.md). Every token is made by
 * the production issuer; the accounts, the roles, the institution and the academic structure are seeded as the owner of the
 * schema; every test has an institution of its own.
 *
 * <p>It covers the acceptance criterion (the plan "Primaria 2027" of the primary grade, monthly, due on the 5th, with
 * "Colegiatura" for ten periods from February and "Matrícula" for one, activated and then frozen), the targets, the edge
 * validation, the states and their refusals, the concurrent activations, the role without the permission, the isolation
 * between institutions and the audit entry of every write.
 */
class ChargePlanEndpointsIT extends CommittingPostgresIntegrationTest {

    private static final String PLANS = "/api/v1/charges/plans";
    private static final String CONCEPTS = "/api/v1/catalog/payment-concepts";
    private static final String RATE_LIMIT = "confia.web.rate-limit.";
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
                "confia_admin_app", TEST_PASSWORD, keys, Map.of(
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

    /** One seeded staff member of one institution, with its live session and its token. */
    private record Member(UUID account, UUID institution, UUID family, String token) {
    }

    /** The academic structure seeded for an institution. */
    private record Structure(UUID year, UUID modality, UUID grade, UUID section) {
    }

    // --- The acceptance criterion ---

    @Test
    void anAdministratorCreatesPrimaria2027MonthlyDueOnThe5thAddsColegiaturaAndMatriculaActivatesItAndCannotChangeItsItemsAnymore()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Member other = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String tuition = concept(admin, "COLEG");
        String enrollment = concept(admin, "MATRI");

        JsonNode plan = created(post(PLANS, admin, planBody(structure.year(), "Primaria 2027", "GRADE",
                structure.grade(), "MONTHLY", 5)));
        String id = plan.get("id").asString();
        assertThat(plan.propertyNames()).containsExactly("id", "academicYearId", "name", "targetScope", "targetRefId",
                "frequency", "dueDayOfMonth", "status", "version", "createdAt", "updatedAt");
        assertThat(plan.get("status").asString()).isEqualTo("DRAFT");
        assertThat(plan.get("version").asLong()).isZero();
        assertThat(plan.get("targetScope").asString()).isEqualTo("GRADE");
        assertThat(plan.get("targetRefId").asString()).isEqualTo(structure.grade().toString());
        assertThat(plan.get("dueDayOfMonth").asInt()).isEqualTo(5);

        JsonNode first = created(post(items(id), admin, itemBody(tuition, 10, "2027-02-01")));
        assertThat(first.propertyNames()).containsExactly("id", "planId", "paymentConceptId", "periodsCount",
                "firstPeriodOn", "createdAt");
        assertThat(first.get("periodsCount").asInt()).isEqualTo(10);
        assertThat(first.get("firstPeriodOn").asString()).isEqualTo("2027-02-01");
        JsonNode second = created(post(items(id), admin, itemBody(enrollment, 1, "2027-02-01")));

        HttpResponse<String> activated = put(id, admin, updateBody(0, "Primaria 2027", "MONTHLY", 5, "ACTIVE"));
        assertThat(activated.statusCode()).as(activated.body()).isEqualTo(200);
        assertThat(HarnessProcess.json(activated).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(HarnessProcess.json(activated).get("version").asLong()).isEqualTo(1);

        assertProblem(post(items(id), admin, itemBody(concept(admin, "UNIF"), 1, "2027-02-01")), 409,
                "plan-not-editable");
        assertProblem(send("DELETE", items(id) + "/" + first.get("id").asString(), admin.token(), null), 409,
                "plan-not-editable");
        assertProblem(put(id, admin, updateBody(1, "Primaria 2027", "BIMONTHLY", 5, "ACTIVE")), 409,
                "plan-not-editable");

        JsonNode detail = HarnessProcess.json(get(PLANS + "/" + id, admin));
        assertThat(detail.propertyNames()).containsExactly("id", "academicYearId", "name", "targetScope",
                "targetRefId", "frequency", "dueDayOfMonth", "status", "version", "createdAt", "updatedAt", "items");
        assertThat(detail.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(detail.get("items")).hasSize(2);
        assertThat(detail.get("items").get(0).get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(detail.get("items").get(1).get("id").asString()).isEqualTo(second.get("id").asString());

        JsonNode otherPage = HarnessProcess.json(get(PLANS, other));
        assertThat(otherPage.get("total").asLong()).isZero();
        assertProblem(get(PLANS + "/" + id, other), 404, "resource-not-found");

        List<String[]> entries = chargesEntriesOf(admin.institution());
        assertThat(entries).extracting(entry -> entry[0]).containsExactly("charges.plan.created",
                "charges.plan_item.added", "charges.plan_item.added", "charges.plan.activated");
        assertThat(entries.get(3)[3]).contains("\"status\": \"DRAFT\"");
        assertThat(entries.get(3)[4]).contains("\"status\": \"ACTIVE\"").contains("\"version\": 1");
        assertThat(entries.get(1)[1]).isEqualTo("charges.plan_item");
        assertThat(entries.get(1)[4]).contains("\"periodsCount\": 10").contains("\"firstPeriodOn\": \"2027-02-01\"")
                .contains(tuition);
        assertThat(actorsOfChargesEntries(admin.institution())).containsOnly(admin.account().toString());
    }

    @Test
    void theResponsesCarryNoStoreAndNeverTheInstitutionOfTheTokenNorTheStaffAccount() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");

        HttpResponse<String> plan = post(PLANS, admin, planBody(structure.year(), "P", "GRADE", structure.grade(),
                "MONTHLY", 5));
        String id = HarnessProcess.json(plan).get("id").asString();
        HttpResponse<String> item = post(items(id), admin, itemBody(concept, 1, "2027-02-01"));
        HttpResponse<String> detail = get(PLANS + "/" + id, admin);

        for (HttpResponse<String> response : List.of(plan, item, detail)) {
            assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
            assertThat(response.body()).doesNotContain(admin.institution().toString())
                    .doesNotContain(admin.account().toString());
        }
        assertThat(item.statusCode()).isEqualTo(201);
    }

    // --- Targets ---

    @Test
    void aPlanOfAModalityAGradeAndASectionCarriesItsTargetAndOneOfAnotherInstitutionOrOfAnotherKindIsNotFound()
            throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        Structure mineStructure = structure(mine.institution());
        Structure theirStructure = structure(theirs.institution());

        JsonNode byModality = created(post(PLANS, mine, planBody(mineStructure.year(), "Bach", "MODALITY",
                mineStructure.modality(), "ANNUAL", 1)));
        JsonNode byGrade = created(post(PLANS, mine, planBody(mineStructure.year(), "Decimo", "GRADE",
                mineStructure.grade(), "MONTHLY", 28)));
        JsonNode bySection = created(post(PLANS, mine, planBody(mineStructure.year(), "Seccion", "SECTION",
                mineStructure.section(), "ONE_TIME", 15)));

        assertThat(byModality.get("targetRefId").asString()).isEqualTo(mineStructure.modality().toString());
        assertThat(byGrade.get("targetRefId").asString()).isEqualTo(mineStructure.grade().toString());
        assertThat(bySection.get("targetRefId").asString()).isEqualTo(mineStructure.section().toString());
        int entries = chargesEntriesOf(mine.institution()).size();
        assertProblem(post(PLANS, mine, planBody(theirStructure.year(), "X", "GRADE", mineStructure.grade(),
                "MONTHLY", 5)), 404, "resource-not-found");
        assertProblem(post(PLANS, mine, planBody(mineStructure.year(), "X", "MODALITY", theirStructure.modality(),
                "MONTHLY", 5)), 404, "resource-not-found");
        assertProblem(post(PLANS, mine, planBody(mineStructure.year(), "X", "GRADE", theirStructure.grade(),
                "MONTHLY", 5)), 404, "resource-not-found");
        assertProblem(post(PLANS, mine, planBody(mineStructure.year(), "X", "SECTION", theirStructure.section(),
                "MONTHLY", 5)), 404, "resource-not-found");
        assertProblem(post(PLANS, mine, planBody(mineStructure.year(), "X", "SECTION", mineStructure.grade(),
                "MONTHLY", 5)), 404, "resource-not-found");
        assertProblem(post(PLANS, mine, planBody(UUID.randomUUID(), "X", "GRADE", mineStructure.grade(), "MONTHLY",
                5)), 404, "resource-not-found");
        assertThat(chargesEntriesOf(mine.institution())).hasSize(entries);
        assertThat(planCountOf(mine.institution())).isEqualTo(3);
    }

    // --- Validation at the edge ---

    @Test
    void aBadPlanBodyIsRefusedWith400AndLeavesNoRowNorEntry() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String year = structure.year().toString();
        UUID grade = structure.grade();

        for (int day : new int[] {0, 29, 31, -1}) {
            assertProblem(post(PLANS, admin, planBody(structure.year(), "X", "GRADE", grade, "MONTHLY", day)), 400,
                    "validation-failed");
        }
        assertProblem(post(PLANS, admin, planBody(structure.year(), "", "GRADE", grade, "MONTHLY", 5)), 400,
                "validation-failed");
        assertProblem(post(PLANS, admin, planBody(structure.year(), "   ", "GRADE", grade, "MONTHLY", 5)), 400,
                "validation-failed");
        assertProblem(post(PLANS, admin, planBody(structure.year(), "N".repeat(121), "GRADE", grade, "MONTHLY", 5)),
                400, "validation-failed");
        assertProblem(post(PLANS, admin, planBody(structure.year(), "Con\\nsalto", "GRADE", grade, "MONTHLY", 5)),
                400, "validation-failed");
        assertProblem(post(PLANS, admin, planBody(structure.year(), "X", "INSTITUTION", grade, "MONTHLY", 5)), 400,
                "validation-failed");
        assertProblem(post(PLANS, admin, planBody(structure.year(), "X", "grade", grade, "MONTHLY", 5)), 400,
                "validation-failed");
        assertProblem(post(PLANS, admin, planBody(structure.year(), "X", "GRADE", grade, "WEEKLY", 5)), 400,
                "validation-failed");
        assertProblem(post(PLANS, admin, "{\"academicYearId\":\"" + year + "\",\"name\":\"X\",\"targetScope\":\"GRADE\","
                + "\"frequency\":\"MONTHLY\",\"dueDayOfMonth\":5}"), 400, "validation-failed");
        assertProblem(post(PLANS, admin, "{\"name\":\"X\",\"targetScope\":\"GRADE\",\"targetRefId\":\"" + grade
                + "\",\"frequency\":\"MONTHLY\",\"dueDayOfMonth\":5}"), 400, "validation-failed");
        assertProblem(post(PLANS, admin, "{\"academicYearId\":\"" + year + "\",\"name\":\"X\",\"targetScope\":\"GRADE\","
                + "\"targetRefId\":\"" + grade + "\",\"frequency\":\"MONTHLY\"}"), 400, "validation-failed");
        assertProblem(post(PLANS, admin, "{\"academicYearId\":\"" + year + "\",\"name\":\"X\",\"targetScope\":\"GRADE\","
                + "\"targetRefId\":\"" + grade + "\",\"frequency\":\"MONTHLY\",\"dueDayOfMonth\":5,"
                + "\"institutionId\":\"" + UUID.randomUUID() + "\"}"), 400, "validation-failed");
        assertProblem(post(PLANS, admin, "{\"academicYearId\":\"" + year + "\",\"name\":\"X\",\"targetScope\":\"GRADE\","
                + "\"targetRefId\":\"" + grade + "\",\"frequency\":\"MONTHLY\",\"dueDayOfMonth\":5,"
                + "\"status\":\"ACTIVE\"}"), 400, "validation-failed");

        assertThat(planCountOf(admin.institution())).isZero();
        assertThat(chargesEntriesOf(admin.institution())).isEmpty();
        created(post(PLANS, admin, planBody(structure.year(), "N".repeat(120), "GRADE", grade, "MONTHLY", 28)));
        created(post(PLANS, admin, planBody(structure.year(), "Uno", "GRADE", grade, "MONTHLY", 1)));
    }

    @Test
    void aBadItemBodyIsRefusedWith400OrNotFoundAndLeavesNoItemNorEntry() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");
        String id = created(post(PLANS, admin, planBody(structure.year(), "P", "GRADE", structure.grade(), "MONTHLY",
                5))).get("id").asString();
        int entries = chargesEntriesOf(admin.institution()).size();

        for (int periods : new int[] {0, 37, -1}) {
            assertProblem(post(items(id), admin, itemBody(concept, periods, "2027-02-01")), 400, "validation-failed");
        }
        for (String day : new String[] {"2027-02-15", "2027-01-31", "1999-12-01", "2101-01-01"}) {
            assertProblem(post(items(id), admin, itemBody(concept, 10, day)), 400, "validation-failed");
        }
        assertProblem(post(items(id), admin, "{\"paymentConceptId\":\"" + concept + "\",\"periodsCount\":10}"), 400,
                "validation-failed");
        assertProblem(post(items(id), admin, "{\"periodsCount\":10,\"firstPeriodOn\":\"2027-02-01\"}"), 400,
                "validation-failed");
        assertProblem(post(items(id), admin, "{\"paymentConceptId\":\"" + concept + "\",\"periodsCount\":10,"
                + "\"firstPeriodOn\":\"2027-02-01\",\"price\":{\"amount\":\"1\",\"currency\":\"HNL\"}}"), 400,
                "validation-failed");
        assertProblem(post(items(id), admin, "{\"paymentConceptId\":\"not-a-uuid\",\"periodsCount\":10,"
                + "\"firstPeriodOn\":\"2027-02-01\"}"), 400, "validation-failed");
        assertProblem(send("POST", PLANS + "/not-a-uuid/items", admin.token(), itemBody(concept, 10, "2027-02-01")),
                400, "validation-failed");
        assertProblem(send("DELETE", items(id) + "/not-a-uuid", admin.token(), null), 400, "validation-failed");

        assertThat(chargesEntriesOf(admin.institution())).hasSize(entries);
        assertThat(HarnessProcess.json(get(PLANS + "/" + id, admin)).get("items")).isEmpty();
        created(post(items(id), admin, itemBody(concept, 36, "2100-12-01")));
    }

    // --- Items ---

    @Test
    void aConceptRepeatedInThePlanAnInactiveOneAndOneOfAnotherInstitutionAreRefusedAndANewOneIsAccepted()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String tuition = concept(admin, "COLEG");
        String inactive = concept(admin, "VIEJO");
        String foreign = concept(theirs, "AJENO");
        deactivateConcept(admin, inactive, "VIEJO");
        String id = created(post(PLANS, admin, planBody(structure.year(), "P", "GRADE", structure.grade(), "MONTHLY",
                5))).get("id").asString();
        created(post(items(id), admin, itemBody(tuition, 10, "2027-02-01")));
        int entries = chargesEntriesOf(admin.institution()).size();

        assertProblem(post(items(id), admin, itemBody(tuition, 1, "2027-03-01")), 409, "duplicate-plan-item");
        assertProblem(post(items(id), admin, itemBody(inactive, 1, "2027-02-01")), 409, "inactive-reference");
        assertProblem(post(items(id), admin, itemBody(foreign, 1, "2027-02-01")), 404, "resource-not-found");
        assertProblem(post(items(id), admin, itemBody(UUID.randomUUID().toString(), 1, "2027-02-01")), 404,
                "resource-not-found");

        assertThat(chargesEntriesOf(admin.institution())).hasSize(entries);
        assertThat(HarnessProcess.json(get(PLANS + "/" + id, admin)).get("items")).hasSize(1);
    }

    @Test
    void anItemOfADraftIsRemovedWithADeleteAndAuditedAndAnotherPlansItemOrAnUnknownOneIsNotFound()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String tuition = concept(admin, "COLEG");
        String id = created(post(PLANS, admin, planBody(structure.year(), "P", "GRADE", structure.grade(), "MONTHLY",
                5))).get("id").asString();
        String other = created(post(PLANS, admin, planBody(structure.year(), "Q", "GRADE", structure.grade(),
                "MONTHLY", 5))).get("id").asString();
        String item = created(post(items(id), admin, itemBody(tuition, 10, "2027-02-01"))).get("id").asString();

        assertProblem(send("DELETE", items(other) + "/" + item, admin.token(), null), 404, "resource-not-found");
        assertProblem(send("DELETE", items(id) + "/" + UUID.randomUUID(), admin.token(), null), 404,
                "resource-not-found");
        assertProblem(send("DELETE", items(UUID.randomUUID().toString()) + "/" + item, admin.token(), null), 404,
                "resource-not-found");
        HttpResponse<String> removed = send("DELETE", items(id) + "/" + item, admin.token(), null);

        assertThat(removed.statusCode()).isEqualTo(204);
        assertThat(removed.body()).isEmpty();
        assertThat(HarnessProcess.json(get(PLANS + "/" + id, admin)).get("items")).isEmpty();
        assertProblem(send("DELETE", items(id) + "/" + item, admin.token(), null), 404, "resource-not-found");
        List<String[]> entries = chargesEntriesOf(admin.institution());
        assertThat(entries).extracting(entry -> entry[0]).containsExactly("charges.plan.created",
                "charges.plan.created", "charges.plan_item.added", "charges.plan_item.removed");
        assertThat(entries.get(3)[2]).isEqualTo(item);
        assertThat(entries.get(3)[3]).contains("\"periodsCount\": 10");
        assertThat(entries.get(3)[4]).isNull();
        created(post(items(id), admin, itemBody(tuition, 5, "2027-03-01")));
    }

    // --- States ---

    @Test
    void aDraftEditChangesNameFrequencyAndDueDayAdvancesTheVersionNeverTheTargetAndAuditsBeforeAndAfter()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String id = created(post(PLANS, admin, planBody(structure.year(), "Primaria", "GRADE", structure.grade(),
                "MONTHLY", 5))).get("id").asString();

        HttpResponse<String> edited = put(id, admin, updateBody(0, "Primaria 2027", "BIMONTHLY", 10, "DRAFT"));

        assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
        JsonNode body = HarnessProcess.json(edited);
        assertThat(body.get("name").asString()).isEqualTo("Primaria 2027");
        assertThat(body.get("frequency").asString()).isEqualTo("BIMONTHLY");
        assertThat(body.get("dueDayOfMonth").asInt()).isEqualTo(10);
        assertThat(body.get("status").asString()).isEqualTo("DRAFT");
        assertThat(body.get("version").asLong()).isEqualTo(1);
        assertThat(body.get("targetScope").asString()).isEqualTo("GRADE");
        assertThat(body.get("targetRefId").asString()).isEqualTo(structure.grade().toString());
        assertThat(body.get("academicYearId").asString()).isEqualTo(structure.year().toString());
        List<String[]> entries = chargesEntriesOf(admin.institution());
        assertThat(entries).extracting(entry -> entry[0]).containsExactly("charges.plan.created",
                "charges.plan.updated");
        assertThat(entries.get(1)[3]).contains("\"frequency\": \"MONTHLY\"").contains("\"version\": 0");
        assertThat(entries.get(1)[4]).contains("\"frequency\": \"BIMONTHLY\"").contains("\"dueDayOfMonth\": 10")
                .contains("\"version\": 1");
    }

    @Test
    void activatingAPlanWithoutItemsIsRefusedAndALaterActivationWithAnItemWorks() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");
        String id = created(post(PLANS, admin, planBody(structure.year(), "P", "GRADE", structure.grade(), "MONTHLY",
                5))).get("id").asString();
        int entries = chargesEntriesOf(admin.institution()).size();

        assertProblem(put(id, admin, updateBody(0, "P", "MONTHLY", 5, "ACTIVE")), 409, "plan-without-items");
        assertThat(chargesEntriesOf(admin.institution())).hasSize(entries);
        String item = created(post(items(id), admin, itemBody(concept, 1, "2027-02-01"))).get("id").asString();
        assertThat(send("DELETE", items(id) + "/" + item, admin.token(), null).statusCode()).isEqualTo(204);
        assertProblem(put(id, admin, updateBody(0, "P", "MONTHLY", 5, "ACTIVE")), 409, "plan-without-items");
        created(post(items(id), admin, itemBody(concept, 1, "2027-02-01")));

        assertThat(put(id, admin, updateBody(0, "P", "MONTHLY", 5, "ACTIVE")).statusCode()).isEqualTo(200);
    }

    @Test
    void anActivePlanChangesOnlyItsNameAndIsArchivedOnceAndAnArchivedPlanNeverChangesNorReactivates()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");
        String id = created(post(PLANS, admin, planBody(structure.year(), "P", "GRADE", structure.grade(), "MONTHLY",
                5))).get("id").asString();
        created(post(items(id), admin, itemBody(concept, 10, "2027-02-01")));
        assertThat(put(id, admin, updateBody(0, "P", "MONTHLY", 5, "ACTIVE")).statusCode()).isEqualTo(200);

        JsonNode renamed = HarnessProcess.json(put(id, admin, updateBody(1, "P 2027", "MONTHLY", 5, "ACTIVE")));
        assertThat(renamed.get("name").asString()).isEqualTo("P 2027");
        assertProblem(put(id, admin, updateBody(2, "P", "ANNUAL", 5, "ACTIVE")), 409, "plan-not-editable");
        assertProblem(put(id, admin, updateBody(2, "P", "MONTHLY", 6, "ACTIVE")), 409, "plan-not-editable");
        assertProblem(put(id, admin, updateBody(2, "P", "MONTHLY", 5, "DRAFT")), 409, "plan-not-editable");
        JsonNode archived = HarnessProcess.json(put(id, admin, updateBody(2, "P 2027", "MONTHLY", 5, "ARCHIVED")));
        assertThat(archived.get("status").asString()).isEqualTo("ARCHIVED");

        for (String status : new String[] {"DRAFT", "ACTIVE", "ARCHIVED"}) {
            assertProblem(put(id, admin, updateBody(3, "P 2027", "MONTHLY", 5, status)), 409, "plan-not-editable");
        }
        assertProblem(put(id, admin, updateBody(3, "Otro", "MONTHLY", 5, "ARCHIVED")), 409, "plan-not-editable");
        assertProblem(post(items(id), admin, itemBody(concept(admin, "UNIF"), 1, "2027-02-01")), 409,
                "plan-not-editable");
        List<String[]> entries = chargesEntriesOf(admin.institution());
        assertThat(entries).extracting(entry -> entry[0]).containsExactly("charges.plan.created",
                "charges.plan_item.added", "charges.plan.activated", "charges.plan.updated",
                "charges.plan.archived");
        assertThat(storedStatusOf(id)).isEqualTo("ARCHIVED");
    }

    @Test
    void aDraftCannotBeArchivedDirectly() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");
        String id = created(post(PLANS, admin, planBody(structure.year(), "P", "GRADE", structure.grade(), "MONTHLY",
                5))).get("id").asString();
        created(post(items(id), admin, itemBody(concept, 1, "2027-02-01")));

        assertProblem(put(id, admin, updateBody(0, "P", "MONTHLY", 5, "ARCHIVED")), 409, "plan-not-editable");
        assertThat(storedStatusOf(id)).isEqualTo("DRAFT");
    }

    @Test
    void aStaleOrMissingVersionOrATargetInTheBodyIsRefusedAndThePlanIsUntouched() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String id = created(post(PLANS, admin, planBody(structure.year(), "P", "GRADE", structure.grade(), "MONTHLY",
                5))).get("id").asString();
        assertThat(put(id, admin, updateBody(0, "Primero", "MONTHLY", 5, "DRAFT")).statusCode()).isEqualTo(200);
        int entries = chargesEntriesOf(admin.institution()).size();

        assertProblem(put(id, admin, updateBody(0, "Segundo", "MONTHLY", 5, "DRAFT")), 409, "stale-version");
        assertProblem(put(id, admin, updateBody(9, "Tercero", "MONTHLY", 5, "DRAFT")), 409, "stale-version");
        assertProblem(put(id, admin, "{\"name\":\"Sin version\",\"frequency\":\"MONTHLY\",\"dueDayOfMonth\":5,"
                + "\"status\":\"DRAFT\"}"), 400, "validation-failed");
        assertProblem(put(id, admin, updateBody(-1, "Negativa", "MONTHLY", 5, "DRAFT")), 400, "validation-failed");
        assertProblem(put(id, admin, updateBody(1, "X", "MONTHLY", 5, "DELETED")), 400, "validation-failed");
        assertProblem(put(id, admin, updateBody(1, " ", "MONTHLY", 5, "DRAFT")), 400, "validation-failed");
        assertProblem(put(id, admin, updateBody(1, "X", "MONTHLY", 29, "DRAFT")), 400, "validation-failed");
        assertProblem(put(id, admin, updateBody(1, "X", "MONTHLY", 0, "DRAFT")), 400, "validation-failed");
        assertProblem(put(id, admin, updateBody(1, "X", "WEEKLY", 5, "DRAFT")), 400, "validation-failed");
        assertProblem(put(id, admin, "{\"version\":1,\"name\":\"X\",\"frequency\":\"MONTHLY\",\"dueDayOfMonth\":5,"
                + "\"status\":\"DRAFT\",\"targetScope\":\"SECTION\"}"), 400, "validation-failed");
        assertProblem(put(id, admin, "{\"version\":1,\"name\":\"X\",\"frequency\":\"MONTHLY\",\"dueDayOfMonth\":5,"
                + "\"status\":\"DRAFT\",\"targetRefId\":\"" + UUID.randomUUID() + "\"}"), 400, "validation-failed");
        assertProblem(put(id, admin, "{\"version\":1,\"name\":\"X\",\"frequency\":\"MONTHLY\",\"dueDayOfMonth\":5,"
                + "\"status\":\"DRAFT\",\"academicYearId\":\"" + UUID.randomUUID() + "\"}"), 400, "validation-failed");
        assertProblem(send("PUT", PLANS + "/not-a-uuid", admin.token(), updateBody(0, "X", "MONTHLY", 5, "DRAFT")),
                400, "validation-failed");

        JsonNode stored = HarnessProcess.json(get(PLANS + "/" + id, admin));
        assertThat(stored.get("name").asString()).isEqualTo("Primero");
        assertThat(stored.get("version").asLong()).isEqualTo(1);
        assertThat(stored.get("targetScope").asString()).isEqualTo("GRADE");
        assertThat(chargesEntriesOf(admin.institution())).hasSize(entries);
    }

    @Test
    void twoActivationsOfAPlanWithTheSameVersionAtTheSameTimeLetExactlyOneWin() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");
        String id = created(post(PLANS, admin, planBody(structure.year(), "P", "GRADE", structure.grade(), "MONTHLY",
                5))).get("id").asString();
        created(post(items(id), admin, itemBody(concept, 1, "2027-02-01")));

        List<Integer> statuses = concurrently(
                () -> put(id, admin, updateBody(0, "P", "MONTHLY", 5, "ACTIVE")).statusCode(),
                () -> put(id, admin, updateBody(0, "P", "MONTHLY", 5, "ACTIVE")).statusCode());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(HarnessProcess.json(get(PLANS + "/" + id, admin)).get("version").asLong()).isEqualTo(1);
        assertThat(chargesEntriesOf(admin.institution()).stream().filter(entry -> entry[0].endsWith(".activated")))
                .hasSize(1);
    }

    @Test
    void anItemAddedWhileThePlanIsBeingActivatedEitherMakesItInOrIsRefusedAndNeverSlipsIn() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String tuition = concept(admin, "COLEG");
        String enrollment = concept(admin, "MATRI");
        String id = created(post(PLANS, admin, planBody(structure.year(), "P", "GRADE", structure.grade(), "MONTHLY",
                5))).get("id").asString();
        created(post(items(id), admin, itemBody(tuition, 1, "2027-02-01")));

        List<Integer> statuses = concurrently(
                () -> put(id, admin, updateBody(0, "P", "MONTHLY", 5, "ACTIVE")).statusCode(),
                () -> post(items(id), admin, itemBody(enrollment, 1, "2027-02-01")).statusCode());

        assertThat(statuses.get(0)).isEqualTo(200);
        assertThat(statuses.get(1)).isIn(201, 409);
        JsonNode detail = HarnessProcess.json(get(PLANS + "/" + id, admin));
        assertThat(detail.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(detail.get("items")).hasSize(statuses.get(1) == 201 ? 2 : 1);
    }

    // --- Reads ---

    @Test
    void theListFiltersByYearTargetScopeAndStatusSortsAndPagesAndRefusesBadParameters() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        UUID otherYear = year(admin.institution(), "2028");
        String concept = concept(admin, "COLEG");
        String primary = created(post(PLANS, admin, planBody(structure.year(), "Primaria", "GRADE", structure.grade(),
                "MONTHLY", 5))).get("id").asString();
        created(post(PLANS, admin, planBody(structure.year(), "bachillerato", "MODALITY", structure.modality(),
                "ANNUAL", 1)));
        created(post(PLANS, admin, planBody(otherYear, "Nuevo", "SECTION", structure.section(), "ONE_TIME", 9)));
        created(post(items(primary), admin, itemBody(concept, 1, "2027-02-01")));
        assertThat(put(primary, admin, updateBody(0, "Primaria", "MONTHLY", 5, "ACTIVE")).statusCode())
                .isEqualTo(200);

        JsonNode all = HarnessProcess.json(get(PLANS, admin));
        assertThat(all.get("total").asLong()).isEqualTo(3);
        assertThat(all.get("page").asInt()).isZero();
        assertThat(all.get("size").asInt()).isEqualTo(50);
        assertThat(names(all)).containsExactly("bachillerato", "Nuevo", "Primaria");
        assertThat(names(HarnessProcess.json(get(PLANS + "?sort=name,desc", admin))))
                .containsExactly("Primaria", "Nuevo", "bachillerato");
        assertThat(names(HarnessProcess.json(get(PLANS + "?academicYearId=" + otherYear, admin))))
                .containsExactly("Nuevo");
        assertThat(names(HarnessProcess.json(get(PLANS + "?targetScope=MODALITY", admin))))
                .containsExactly("bachillerato");
        assertThat(names(HarnessProcess.json(get(PLANS + "?status=ACTIVE", admin)))).containsExactly("Primaria");
        assertThat(names(HarnessProcess.json(get(PLANS + "?status=DRAFT&targetScope=SECTION", admin))))
                .containsExactly("Nuevo");
        JsonNode second = HarnessProcess.json(get(PLANS + "?size=1&page=1", admin));
        assertThat(names(second)).containsExactly("Nuevo");
        assertThat(second.get("total").asLong()).isEqualTo(3);
        assertProblem(get(PLANS + "?size=201", admin), 400, "validation-failed");
        assertProblem(get(PLANS + "?size=0", admin), 400, "validation-failed");
        assertProblem(get(PLANS + "?page=-1", admin), 400, "validation-failed");
        assertProblem(get(PLANS + "?sort=dueDayOfMonth", admin), 400, "validation-failed");
        assertProblem(get(PLANS + "?targetScope=INSTITUTION", admin), 400, "validation-failed");
        assertProblem(get(PLANS + "?status=DELETED", admin), 400, "validation-failed");
        assertProblem(get(PLANS + "?academicYearId=not-a-uuid", admin), 400, "validation-failed");
        assertProblem(get(PLANS + "/" + UUID.randomUUID(), admin), 404, "resource-not-found");
        assertProblem(get(PLANS + "/not-a-uuid", admin), 400, "validation-failed");
    }

    // --- Roles: the decision is the server's ---

    @Test
    void aCashierAnAccountantAndACoordinatorMayReadButCannotCreateEditOrChangeItemsAndTheRefusalLeavesNoRowNorEntry()
            throws SQLException {
        Member cashier = member(StaffRole.CASHIER.code());
        Member accounting = sameInstitution(cashier.institution(), StaffRole.ACCOUNTING.code());
        Member coordinator = sameInstitution(cashier.institution(), StaffRole.ACADEMIC_COORDINATOR.code());
        Member admin = sameInstitution(cashier.institution(), StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(cashier.institution());
        String concept = concept(admin, "COLEG");
        String id = created(post(PLANS, admin, planBody(structure.year(), "P", "GRADE", structure.grade(), "MONTHLY",
                5))).get("id").asString();
        String item = created(post(items(id), admin, itemBody(concept, 10, "2027-02-01"))).get("id").asString();
        int entries = chargesEntriesOf(cashier.institution()).size();

        for (Member reader : List.of(cashier, accounting, coordinator)) {
            assertThat(get(PLANS, reader).statusCode()).isEqualTo(200);
            assertThat(get(PLANS + "/" + id, reader).statusCode()).isEqualTo(200);
            assertProblem(post(PLANS, reader, planBody(structure.year(), "Otra", "GRADE", structure.grade(),
                    "MONTHLY", 5)), 403, "forbidden");
            assertProblem(put(id, reader, updateBody(0, "Cambiada", "MONTHLY", 5, "ACTIVE")), 403, "forbidden");
            assertProblem(post(items(id), reader, itemBody(concept, 1, "2027-03-01")), 403, "forbidden");
            assertProblem(send("DELETE", items(id) + "/" + item, reader.token(), null), 403, "forbidden");
        }

        JsonNode detail = HarnessProcess.json(get(PLANS + "/" + id, admin));
        assertThat(detail.get("name").asString()).isEqualTo("P");
        assertThat(detail.get("status").asString()).isEqualTo("DRAFT");
        assertThat(detail.get("items")).hasSize(1);
        assertThat(planCountOf(cashier.institution())).isEqualTo(1);
        assertThat(chargesEntriesOf(cashier.institution())).hasSize(entries);
    }

    @Test
    void anAccountWithoutARoleCannotEvenRead() throws SQLException {
        Member nobody = member(null);
        String id = UUID.randomUUID().toString();

        assertProblem(get(PLANS, nobody), 403, "forbidden");
        assertProblem(get(PLANS + "/" + id, nobody), 403, "forbidden");
        assertProblem(post(PLANS, nobody, planBody(UUID.randomUUID(), "X", "GRADE", UUID.randomUUID(), "MONTHLY", 5)),
                403, "forbidden");
        assertProblem(put(id, nobody, updateBody(0, "X", "MONTHLY", 5, "DRAFT")), 403, "forbidden");
        assertProblem(post(items(id), nobody, itemBody(UUID.randomUUID().toString(), 1, "2027-02-01")), 403,
                "forbidden");
        assertProblem(send("DELETE", items(id) + "/" + UUID.randomUUID(), nobody.token(), null), 403, "forbidden");
    }

    @Test
    void noCredentialAnswers401() {
        assertProblem(send("GET", PLANS, null, null), 401, "authentication-required");
        assertProblem(send("GET", PLANS + "/" + UUID.randomUUID(), null, null), 401, "authentication-required");
        assertProblem(send("DELETE", items(UUID.randomUUID().toString()) + "/" + UUID.randomUUID(), null, null), 401,
                "authentication-required");
    }

    @Test
    void thereIsNoRouteToDeleteAPlanNorToWriteItsCollection() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String id = created(post(PLANS, admin, planBody(structure.year(), "P", "GRADE", structure.grade(), "MONTHLY",
                5))).get("id").asString();

        assertThat(send("DELETE", PLANS + "/" + id, admin.token(), null).statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(send("DELETE", PLANS, admin.token(), null).statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(planCountOf(admin.institution())).isEqualTo(1);
    }

    // --- Isolation between institutions ---

    @Test
    void aUserOfAnotherInstitutionNeverSeesNorEditsNorAddsToNorRemovesFromThePlansOfThisOne() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(mine.institution());
        String concept = concept(mine, "COLEG");
        String theirConcept = concept(theirs, "COLEG");
        String id = created(post(PLANS, mine, planBody(structure.year(), "P", "GRADE", structure.grade(), "MONTHLY",
                5))).get("id").asString();
        String item = created(post(items(id), mine, itemBody(concept, 10, "2027-02-01"))).get("id").asString();

        JsonNode theirPage = HarnessProcess.json(get(PLANS, theirs));
        assertThat(theirPage.get("total").asLong()).isZero();
        assertThat(theirPage.get("items")).isEmpty();
        assertProblem(get(PLANS + "/" + id, theirs), 404, "resource-not-found");
        assertProblem(put(id, theirs, updateBody(0, "Robado", "MONTHLY", 5, "DRAFT")), 404, "resource-not-found");
        assertProblem(post(items(id), theirs, itemBody(theirConcept, 1, "2027-02-01")), 404, "resource-not-found");
        assertProblem(post(items(id), theirs, itemBody(concept, 1, "2027-02-01")), 404, "resource-not-found");
        assertProblem(send("DELETE", items(id) + "/" + item, theirs.token(), null), 404, "resource-not-found");

        JsonNode mineAfter = HarnessProcess.json(get(PLANS + "/" + id, mine));
        assertThat(mineAfter.get("name").asString()).isEqualTo("P");
        assertThat(mineAfter.get("status").asString()).isEqualTo("DRAFT");
        assertThat(mineAfter.get("items")).hasSize(1);
        assertThat(chargesEntriesOf(theirs.institution())).isEmpty();
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

    /** A year, a modality, a grade and a section of the institution, as the owner of the schema. */
    private static Structure structure(UUID institution) throws SQLException {
        UUID year = year(institution, "2027");
        UUID modality = UUID.randomUUID();
        UUID grade = UUID.randomUUID();
        UUID section = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            execute(owner, "insert into organization_modality (institution_id, id, code, name) "
                    + "values (?, ?, 'BACH', 'Bachillerato')", institution, modality);
            execute(owner, "insert into organization_grade (institution_id, id, modality_id, name, ordinal) "
                    + "values (?, ?, ?, 'Decimo', 10)", institution, grade, modality);
            execute(owner, "insert into organization_section (institution_id, id, academic_year_id, grade_id, name) "
                    + "values (?, ?, ?, ?, 'A')", institution, section, year, grade);
            owner.commit();
        }
        return new Structure(year, modality, grade, section);
    }

    private static UUID year(UUID institution, String name) throws SQLException {
        UUID year = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            execute(owner, "insert into organization_academic_year (institution_id, id, name, start_date, end_date) "
                    + "values (?, ?, '" + name + "', date '" + name + "-01-01', date '" + name + "-12-31')",
                    institution, year);
            owner.commit();
        }
        return year;
    }

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
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

    /** A payment concept created through the API by {@code member}; its identifier. */
    private static String concept(Member member, String code) {
        HttpResponse<String> response = post(CONCEPTS, member, "{\"code\":\"" + code + "\",\"name\":\"" + code
                + "\",\"category\":\"TUITION\",\"isTaxable\":false,\"isRecurring\":true}");
        return created(response).get("id").asString();
    }

    private static void deactivateConcept(Member member, String id, String name) {
        HttpResponse<String> response = send("PUT", CONCEPTS + "/" + id, member.token(),
                "{\"version\":0,\"name\":\"" + name + "\",\"category\":\"TUITION\",\"isTaxable\":false,"
                        + "\"isRecurring\":true,\"isActive\":false}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    // --- Bodies and reading responses ---

    private static String items(String planId) {
        return PLANS + "/" + planId + "/items";
    }

    private static String planBody(UUID year, String name, String scope, UUID target, String frequency, int dueDay) {
        return "{\"academicYearId\":\"" + year + "\",\"name\":\"" + name + "\",\"targetScope\":\"" + scope
                + "\",\"targetRefId\":\"" + target + "\",\"frequency\":\"" + frequency + "\",\"dueDayOfMonth\":"
                + dueDay + "}";
    }

    private static String updateBody(long version, String name, String frequency, int dueDay, String status) {
        return "{\"version\":" + version + ",\"name\":\"" + name + "\",\"frequency\":\"" + frequency
                + "\",\"dueDayOfMonth\":" + dueDay + ",\"status\":\"" + status + "\"}";
    }

    private static String itemBody(String concept, int periods, String firstPeriodOn) {
        return "{\"paymentConceptId\":\"" + concept + "\",\"periodsCount\":" + periods + ",\"firstPeriodOn\":\""
                + firstPeriodOn + "\"}";
    }

    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("items").forEach(item -> names.add(item.get("name").asString()));
        return names;
    }

    private static JsonNode created(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return HarnessProcess.json(response);
    }

    /** Runs the calls at the same instant on their own threads; their results in the order given. */
    @SafeVarargs
    private static List<Integer> concurrently(java.util.concurrent.Callable<Integer>... calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            for (java.util.concurrent.Callable<Integer> call : calls) {
                answers.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> answer : answers) {
                statuses.add(answer.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    // --- Reading the database as the superuser of the container ---

    private static long planCountOf(UUID institution) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from charges_plan where institution_id = ?")) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static String storedStatusOf(String planId) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select status from charges_plan where id = ?")) {
            statement.setObject(1, UUID.fromString(planId));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    /** action, entity type, entity id, before, after of every entry of the charges, oldest first. */
    private static List<String[]> chargesEntriesOf(UUID institution) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select action, entity_type, entity_id, before_value::text, after_value::text
                          from shared_audit_log
                         where institution_id = ? and action like 'charges.%' order by id
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

    private static List<String> actorsOfChargesEntries(UUID institution) throws SQLException {
        List<String> actors = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select actor_id::text from shared_audit_log
                         where institution_id = ? and action like 'charges.%'
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

    private static HttpResponse<String> get(String path, Member member) {
        return send("GET", path, member.token(), null);
    }

    private static HttpResponse<String> post(String path, Member member, String body) {
        return send("POST", path, member.token(), body);
    }

    private static HttpResponse<String> put(String id, Member member, String body) {
        String path = id.startsWith("/") ? id : PLANS + "/" + id;
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
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

package com.confia.catalog.web;

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
import java.time.LocalDate;
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
 * The five routes of the price lists on the real administrative process, started through the production launcher
 * against a real PostgreSQL with the least-privilege role of the application (docs/cortes/catalog-price-lists.md). Every
 * token is made by the production issuer; the accounts, the roles, the institution and the academic structure are seeded
 * as the owner of the schema; every test has an institution of its own.
 *
 * <p>It covers the acceptance criterion (L 1 500,00 from January, L 1 650,00 from July: the first is closed on June
 * 30 and both show in the history), INV-11 through the API (the overlap shapes, the contiguous validity), the amount
 * and date rules, the archived list, the stale version, the concurrent additions, the role without the permission, the
 * isolation between institutions and the audit entry of every write.
 */
class PriceListEndpointsIT extends CommittingPostgresIntegrationTest {

    private static final String LISTS = "/api/v1/catalog/price-lists";
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
    private record Structure(UUID year, UUID modality, UUID grade) {
    }

    // --- The acceptance criterion ---

    @Test
    void anAdministratorCreatesTheListPricesColegiaturaAt1500FromJanuaryAndAt1650FromJulyAndTheFirstIsClosedOnJune30()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");

        JsonNode list = created(post(LISTS, admin, listBody(structure.year(), "2027 - Institucion", "INSTITUTION", null)));
        String id = list.get("id").asString();
        assertThat(list.propertyNames()).containsExactly("id", "academicYearId", "name", "scope", "scopeRefId",
                "status", "version", "createdAt", "updatedAt");
        assertThat(list.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(list.get("version").asLong()).isZero();
        assertThat(list.get("scope").asString()).isEqualTo("INSTITUTION");
        assertThat(list.get("scopeRefId").isNull()).isTrue();

        JsonNode january = created(post(items(id), admin, itemBody(concept, "1500", "HNL", "2027-01-01", null)));
        assertThat(january.propertyNames()).containsExactly("id", "priceListId", "paymentConceptId", "price",
                "validFrom", "validTo", "createdAt");
        assertThat(january.get("price").propertyNames()).containsExactly("amount", "currency");
        assertThat(january.get("price").get("amount").asString()).isEqualTo("1500.00");
        assertThat(january.get("price").get("currency").asString()).isEqualTo("HNL");
        assertThat(january.get("validFrom").asString()).isEqualTo("2027-01-01");
        assertThat(january.get("validTo").isNull()).isTrue();
        JsonNode july = created(post(items(id), admin, itemBody(concept, "1650", "HNL", "2027-07-01", null)));
        assertThat(july.get("price").get("amount").asString()).isEqualTo("1650.00");

        JsonNode detail = HarnessProcess.json(get(LISTS + "/" + id, admin));
        assertThat(detail.propertyNames()).containsExactly("id", "academicYearId", "name", "scope", "scopeRefId",
                "status", "version", "createdAt", "updatedAt", "items");
        assertThat(detail.get("items")).hasSize(2);
        JsonNode first = detail.get("items").get(0);
        JsonNode second = detail.get("items").get(1);
        assertThat(first.get("price").get("amount").asString()).isEqualTo("1500.00");
        assertThat(first.get("validFrom").asString()).isEqualTo("2027-01-01");
        assertThat(first.get("validTo").asString()).isEqualTo("2027-06-30");
        assertThat(second.get("price").get("amount").asString()).isEqualTo("1650.00");
        assertThat(second.get("validFrom").asString()).isEqualTo("2027-07-01");
        assertThat(second.get("validTo").isNull()).isTrue();

        List<String[]> entries = priceListEntriesOf(admin.institution());
        assertThat(entries).extracting(entry -> entry[0]).containsExactly("catalog.payment_concept.created",
                "catalog.price_list.created", "catalog.price_list_item.created", "catalog.price_list_item.created");
        assertThat(entries.get(2)[4]).contains("\"amount\": \"1500.0000\"").doesNotContain("closedPrevious");
        assertThat(entries.get(3)[1]).isEqualTo("catalog.price_list_item");
        assertThat(entries.get(3)[2]).isEqualTo(july.get("id").asString());
        assertThat(entries.get(3)[4]).contains("\"amount\": \"1650.0000\"").contains("\"currency\": \"HNL\"")
                .contains("\"closedPreviousItemId\": \"" + january.get("id").asString() + "\"")
                .contains("\"closedPreviousValidTo\": \"2027-06-30\"");
        assertThat(actorsOfCatalogEntries(admin.institution())).containsOnly(admin.account().toString());
    }

    @Test
    void theResponsesCarryNoStoreAndNeverTheInstitutionOfTheTokenNorTheStaffAccount() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");

        HttpResponse<String> list = post(LISTS, admin, listBody(structure.year(), "L", "INSTITUTION", null));
        String id = HarnessProcess.json(list).get("id").asString();
        HttpResponse<String> item = post(items(id), admin, itemBody(concept, "10", "HNL", "2027-01-01", null));
        HttpResponse<String> detail = get(LISTS + "/" + id, admin);

        for (HttpResponse<String> response : List.of(list, item, detail)) {
            assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
            assertThat(response.body()).doesNotContain(admin.institution().toString())
                    .doesNotContain(admin.account().toString());
        }
        assertThat(item.statusCode()).isEqualTo(201);
    }

    // --- Scopes ---

    @Test
    void aListOfAModalityAndOfAGradeCarriesItsReferenceAndOneOfAnotherInstitutionIsNotFound() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        Structure myStructure = structure(mine.institution());
        Structure theirStructure = structure(theirs.institution());

        JsonNode byModality = created(post(LISTS, mine, listBody(myStructure.year(), "Bach", "MODALITY", myStructure.modality())));
        JsonNode byGrade = created(post(LISTS, mine, listBody(myStructure.year(), "Decimo", "GRADE", myStructure.grade())));

        assertThat(byModality.get("scopeRefId").asString()).isEqualTo(myStructure.modality().toString());
        assertThat(byGrade.get("scopeRefId").asString()).isEqualTo(myStructure.grade().toString());
        int entries = priceListEntriesOf(mine.institution()).size();
        assertProblem(post(LISTS, mine, listBody(theirStructure.year(), "X", "INSTITUTION", null)), 404, "resource-not-found");
        assertProblem(post(LISTS, mine, listBody(myStructure.year(), "X", "MODALITY", theirStructure.modality())), 404,
                "resource-not-found");
        assertProblem(post(LISTS, mine, listBody(myStructure.year(), "X", "GRADE", theirStructure.grade())), 404,
                "resource-not-found");
        assertProblem(post(LISTS, mine, listBody(myStructure.year(), "X", "GRADE", myStructure.modality())), 404,
                "resource-not-found");
        assertProblem(post(LISTS, mine, listBody(UUID.randomUUID(), "X", "INSTITUTION", null)), 404, "resource-not-found");
        assertThat(priceListEntriesOf(mine.institution())).hasSize(entries);
        assertThat(listCountOf(mine.institution())).isEqualTo(2);
    }

    // --- Validation of lists at the edge ---

    @Test
    void aBadListBodyIsRefusedWith400AndLeavesNoRowNorEntry() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String year = structure.year().toString();

        assertProblem(post(LISTS, admin, listBody(structure.year(), "", "INSTITUTION", null)), 400, "validation-failed");
        assertProblem(post(LISTS, admin, listBody(structure.year(), "   ", "INSTITUTION", null)), 400, "validation-failed");
        assertProblem(post(LISTS, admin, listBody(structure.year(), "N".repeat(121), "INSTITUTION", null)), 400,
                "validation-failed");
        assertProblem(post(LISTS, admin, listBody(structure.year(), "Con\\nsalto", "INSTITUTION", null)), 400,
                "validation-failed");
        assertProblem(post(LISTS, admin, listBody(structure.year(), "X", "SECTION", null)), 400, "validation-failed");
        assertProblem(post(LISTS, admin, listBody(structure.year(), "X", "institution", null)), 400, "validation-failed");
        assertProblem(post(LISTS, admin, listBody(structure.year(), "X", "MODALITY", null)), 400, "validation-failed");
        assertProblem(post(LISTS, admin, listBody(structure.year(), "X", "INSTITUTION", structure.modality())), 400,
                "validation-failed");
        assertProblem(post(LISTS, admin, "{\"name\":\"X\",\"scope\":\"INSTITUTION\"}"), 400, "validation-failed");
        assertProblem(post(LISTS, admin, "{\"academicYearId\":\"not-a-uuid\",\"name\":\"X\",\"scope\":\"INSTITUTION\"}"),
                400, "validation-failed");
        assertProblem(post(LISTS, admin, "{\"academicYearId\":\"" + year + "\",\"name\":\"X\",\"scope\":\"INSTITUTION\","
                + "\"institutionId\":\"" + UUID.randomUUID() + "\"}"), 400, "validation-failed");

        assertThat(listCountOf(admin.institution())).isZero();
        assertThat(priceListEntriesOf(admin.institution())).isEmpty();
        created(post(LISTS, admin, listBody(structure.year(), "N".repeat(120), "INSTITUTION", null)));
    }

    // --- Editing a list: name, status and version ---

    @Test
    void anEditChangesNameAndStatusAdvancesTheVersionNeverTheYearScopeOrReferenceAndAuditsBeforeAndAfter()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String id = created(post(LISTS, admin, listBody(structure.year(), "Bach", "MODALITY", structure.modality())))
                .get("id").asString();

        HttpResponse<String> archived = put(id, admin, updateBody(0, "Bach cerrada", "ARCHIVED"));

        assertThat(archived.statusCode()).as(archived.body()).isEqualTo(200);
        JsonNode body = HarnessProcess.json(archived);
        assertThat(body.get("name").asString()).isEqualTo("Bach cerrada");
        assertThat(body.get("status").asString()).isEqualTo("ARCHIVED");
        assertThat(body.get("version").asLong()).isEqualTo(1);
        assertThat(body.get("scope").asString()).isEqualTo("MODALITY");
        assertThat(body.get("scopeRefId").asString()).isEqualTo(structure.modality().toString());
        assertThat(body.get("academicYearId").asString()).isEqualTo(structure.year().toString());
        JsonNode reactivated = HarnessProcess.json(put(id, admin, updateBody(1, "Bach", "ACTIVE")));
        assertThat(reactivated.get("version").asLong()).isEqualTo(2);
        assertThat(reactivated.get("status").asString()).isEqualTo("ACTIVE");
        List<String[]> entries = priceListEntriesOf(admin.institution());
        assertThat(entries).extracting(entry -> entry[0]).containsExactly("catalog.price_list.created",
                "catalog.price_list.updated", "catalog.price_list.updated");
        assertThat(entries.get(1)[3]).contains("\"status\": \"ACTIVE\"").contains("\"version\": 0");
        assertThat(entries.get(1)[4]).contains("\"status\": \"ARCHIVED\"").contains("\"version\": 1");
    }

    @Test
    void anEditWithAStaleOrMissingVersionOrWithAYearScopeOrReferenceInTheBodyIsRefusedAndTheListIsUntouched()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String id = created(post(LISTS, admin, listBody(structure.year(), "Lista", "INSTITUTION", null)))
                .get("id").asString();
        assertThat(put(id, admin, updateBody(0, "Primero", "ACTIVE")).statusCode()).isEqualTo(200);
        int entries = priceListEntriesOf(admin.institution()).size();

        assertProblem(put(id, admin, updateBody(0, "Segundo", "ARCHIVED")), 409, "stale-version");
        assertProblem(put(id, admin, updateBody(9, "Tercero", "ARCHIVED")), 409, "stale-version");
        assertProblem(put(id, admin, "{\"name\":\"Sin version\",\"status\":\"ACTIVE\"}"), 400, "validation-failed");
        assertProblem(put(id, admin, updateBody(-1, "Negativa", "ACTIVE")), 400, "validation-failed");
        assertProblem(put(id, admin, updateBody(1, "X", "DELETED")), 400, "validation-failed");
        assertProblem(put(id, admin, updateBody(1, " ", "ACTIVE")), 400, "validation-failed");
        assertProblem(put(id, admin, "{\"version\":1,\"name\":\"X\",\"status\":\"ACTIVE\",\"scope\":\"GRADE\"}"), 400,
                "validation-failed");
        assertProblem(put(id, admin, "{\"version\":1,\"name\":\"X\",\"status\":\"ACTIVE\",\"academicYearId\":\""
                + UUID.randomUUID() + "\"}"), 400, "validation-failed");
        assertProblem(put(id, admin, "{\"version\":1,\"name\":\"X\",\"status\":\"ACTIVE\",\"scopeRefId\":\""
                + UUID.randomUUID() + "\"}"), 400, "validation-failed");
        assertProblem(send("PUT", LISTS + "/not-a-uuid", admin.token(), updateBody(0, "X", "ACTIVE")), 400,
                "validation-failed");

        JsonNode stored = HarnessProcess.json(get(LISTS + "/" + id, admin));
        assertThat(stored.get("name").asString()).isEqualTo("Primero");
        assertThat(stored.get("version").asLong()).isEqualTo(1);
        assertThat(priceListEntriesOf(admin.institution())).hasSize(entries);
    }

    @Test
    void twoEditsOfAListWithTheSameVersionAtTheSameTimeLetExactlyOneWin() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String id = created(post(LISTS, admin, listBody(structure.year(), "Lista", "INSTITUTION", null)))
                .get("id").asString();

        List<Integer> statuses = concurrently(
                () -> put(id, admin, updateBody(0, "Ana", "ACTIVE")).statusCode(),
                () -> put(id, admin, updateBody(0, "Beto", "ARCHIVED")).statusCode());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(HarnessProcess.json(get(LISTS + "/" + id, admin)).get("version").asLong()).isEqualTo(1);
    }

    // --- INV-11 through the API ---

    @Test
    void anExactAPartialAndAContainedOverlapAreAnswered409AndLeaveThePricesAndTheAuditUntouched()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");
        String id = created(post(LISTS, admin, listBody(structure.year(), "L", "INSTITUTION", null))).get("id").asString();
        created(post(items(id), admin, itemBody(concept, "100", "HNL", "2027-01-01", "2027-06-30")));
        created(post(items(id), admin, itemBody(concept, "120", "HNL", "2027-09-01", "2027-12-31")));
        int entries = priceListEntriesOf(admin.institution()).size();

        // Against the closed validities (the database refuses them), whatever the shape.
        for (String[] range : new String[][] {{"2027-01-01", "2027-06-30"}, {"2027-06-30", "2027-07-31"},
                {"2026-12-01", "2027-01-01"}, {"2027-02-01", "2027-02-28"}, {"2026-01-01", "2028-12-31"},
                {"2027-12-31", null}, {"2027-05-01", null}}) {
            assertProblem(post(items(id), admin, itemBody(concept, "999", "HNL", range[0], range[1])), 409,
                    "price-validity-overlap");
        }

        JsonNode detail = HarnessProcess.json(get(LISTS + "/" + id, admin));
        assertThat(detail.get("items")).hasSize(2);
        assertThat(priceListEntriesOf(admin.institution())).hasSize(entries);
    }

    @Test
    void aContiguousValidityIsAcceptedAndSoIsTheSameDatesForAnotherConceptAndAnotherList() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String coleg = concept(admin, "COLEG");
        String matri = concept(admin, "MATRI");
        String first = created(post(LISTS, admin, listBody(structure.year(), "Uno", "INSTITUTION", null))).get("id").asString();
        String second = created(post(LISTS, admin, listBody(structure.year(), "Dos", "MODALITY", structure.modality())))
                .get("id").asString();

        created(post(items(first), admin, itemBody(coleg, "100", "HNL", "2027-01-01", "2027-06-30")));
        created(post(items(first), admin, itemBody(coleg, "110", "HNL", "2027-07-01", "2027-12-31")));
        created(post(items(first), admin, itemBody(coleg, "120", "HNL", "2028-01-01", null)));
        created(post(items(first), admin, itemBody(matri, "50", "HNL", "2027-01-01", "2027-06-30")));
        created(post(items(second), admin, itemBody(coleg, "100", "USD", "2027-01-01", "2027-06-30")));

        assertThat(HarnessProcess.json(get(LISTS + "/" + first, admin)).get("items")).hasSize(4);
        assertThat(HarnessProcess.json(get(LISTS + "/" + second, admin)).get("items")).hasSize(1);
    }

    @Test
    void aReplacementThatStartsOnOrBeforeTheOpenPriceOrAnEndedPriceInsideItIsAnOverlapAndTheOpenPriceStaysOpen()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");
        String id = created(post(LISTS, admin, listBody(structure.year(), "L", "INSTITUTION", null))).get("id").asString();
        created(post(items(id), admin, itemBody(concept, "1500", "HNL", "2027-03-01", null)));
        int entries = priceListEntriesOf(admin.institution()).size();

        assertProblem(post(items(id), admin, itemBody(concept, "1650", "HNL", "2027-03-01", null)), 409,
                "price-validity-overlap");
        assertProblem(post(items(id), admin, itemBody(concept, "1650", "HNL", "2027-01-01", null)), 409,
                "price-validity-overlap");
        assertProblem(post(items(id), admin, itemBody(concept, "1650", "HNL", "2027-04-01", "2027-04-30")), 409,
                "price-validity-overlap");

        JsonNode detail = HarnessProcess.json(get(LISTS + "/" + id, admin));
        assertThat(detail.get("items")).hasSize(1);
        assertThat(detail.get("items").get(0).get("validTo").isNull()).isTrue();
        assertThat(priceListEntriesOf(admin.institution())).hasSize(entries);
        // An ended price entirely before the open one is a plain addition (history entered after the fact).
        created(post(items(id), admin, itemBody(concept, "1400", "HNL", "2026-03-01", "2027-02-28")));
        JsonNode after = HarnessProcess.json(get(LISTS + "/" + id, admin));
        assertThat(after.get("items")).hasSize(2);
        assertThat(after.get("items").get(1).get("validTo").isNull()).isTrue();
    }

    @Test
    void aPriceThatStartsInsideAnEarlierClosedValidityIsAnOverlapAndTheHistoryStaysAsItWas() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");
        String id = created(post(LISTS, admin, listBody(structure.year(), "L", "INSTITUTION", null))).get("id").asString();
        created(post(items(id), admin, itemBody(concept, "100", "HNL", "2027-01-01", null)));
        created(post(items(id), admin, itemBody(concept, "200", "HNL", "2027-07-01", null)));

        // Starting inside the closed first validity while the second is open is refused, and nothing is closed or added.
        assertProblem(post(items(id), admin, itemBody(concept, "300", "HNL", "2027-03-01", null)), 409,
                "price-validity-overlap");

        JsonNode detail = HarnessProcess.json(get(LISTS + "/" + id, admin));
        assertThat(detail.get("items")).hasSize(2);
        assertThat(detail.get("items").get(0).get("validTo").asString()).isEqualTo("2027-06-30");
        assertThat(detail.get("items").get(1).get("validTo").isNull()).isTrue();
    }

    @Test
    void twoSimultaneousAdditionsOfTheSameConceptFromTheSameDayLetExactlyOneWinAndTheOtherIs409() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");
        String id = created(post(LISTS, admin, listBody(structure.year(), "L", "INSTITUTION", null))).get("id").asString();

        List<Integer> statuses = concurrently(
                () -> post(items(id), admin, itemBody(concept, "1500", "HNL", "2027-01-01", null)).statusCode(),
                () -> post(items(id), admin, itemBody(concept, "1600", "HNL", "2027-01-01", null)).statusCode());

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        JsonNode detail = HarnessProcess.json(get(LISTS + "/" + id, admin));
        assertThat(detail.get("items")).hasSize(1);
        assertThat(priceListEntriesOf(admin.institution()).stream()
                .filter(entry -> entry[0].equals("catalog.price_list_item.created"))).hasSize(1);
    }

    @Test
    void twoSimultaneousAdditionsFromDifferentDaysNeverLeaveTwoOpenValiditiesNorAnOverlap() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");
        String id = created(post(LISTS, admin, listBody(structure.year(), "L", "INSTITUTION", null))).get("id").asString();
        created(post(items(id), admin, itemBody(concept, "1000", "HNL", "2026-01-01", null)));

        List<Integer> statuses = concurrently(
                () -> post(items(id), admin, itemBody(concept, "1500", "HNL", "2027-01-01", null)).statusCode(),
                () -> post(items(id), admin, itemBody(concept, "1600", "HNL", "2028-01-01", null)).statusCode());

        // The lock serializes them: when the later day goes first, the earlier day starts before the open price and
        // is an overlap (409); when the earlier goes first, both are accepted. Never two open validities.
        assertThat(statuses).allMatch(status -> status == 201 || status == 409).contains(201);
        JsonNode items = HarnessProcess.json(get(LISTS + "/" + id, admin)).get("items");
        assertThat(items).hasSize(1 + (int) statuses.stream().filter(status -> status == 201).count());
        long open = 0;
        LocalDate previousEnd = null;
        for (JsonNode item : items) {
            open += item.get("validTo").isNull() ? 1 : 0;
            LocalDate from = LocalDate.parse(item.get("validFrom").asString());
            if (previousEnd != null) {
                assertThat(from).as("no overlap with the previous validity").isAfter(previousEnd);
            }
            previousEnd = item.get("validTo").isNull() ? null : LocalDate.parse(item.get("validTo").asString());
        }
        assertThat(open).as("exactly one open validity").isEqualTo(1);
        assertThat(items.get(items.size() - 1).get("validTo").isNull()).isTrue();
    }

    // --- The amount, the currency and the dates ---

    @Test
    void anAmountThatIsZeroNegativeOrWithMoreThanFourDecimalsAndABadCurrencyOrDateAreRefusedWith400AndLeaveNoPrice()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");
        String id = created(post(LISTS, admin, listBody(structure.year(), "L", "INSTITUTION", null))).get("id").asString();
        int entries = priceListEntriesOf(admin.institution()).size();

        for (String amount : List.of("0", "0.0000", "-1", "-1500.00", "1500.00001", "0.0001", "1500.005", "1500.001", "10.123", "1,500.00", "1e3", "abc", "",
                "+5", "12345678901")) {
            assertProblem(post(items(id), admin, itemBody(concept, amount, "HNL", "2027-01-01", null)), 400,
                    "validation-failed");
        }
        for (String currency : List.of("EUR", "hnl", "HN", "", "HNLL")) {
            assertProblem(post(items(id), admin, itemBody(concept, "10", currency, "2027-01-01", null)), 400,
                    "validation-failed");
        }
        assertProblem(post(items(id), admin, itemBody(concept, "10", "HNL", "2027-02-01", "2027-01-31")), 400,
                "validation-failed");
        assertProblem(post(items(id), admin, itemBody(concept, "10", "HNL", "2027-13-01", null)), 400, "validation-failed");
        assertProblem(post(items(id), admin, itemBody(concept, "10", "HNL", "01/01/2027", null)), 400, "validation-failed");
        assertProblem(post(items(id), admin, "{\"paymentConceptId\":\"" + concept + "\",\"price\":{\"amount\":1500,"
                + "\"currency\":\"HNL\"},\"validFrom\":\"2027-01-01\"}"), 400, "validation-failed");
        assertProblem(post(items(id), admin, "{\"paymentConceptId\":\"" + concept + "\",\"validFrom\":\"2027-01-01\"}"),
                400, "validation-failed");
        assertProblem(post(items(id), admin, "{\"paymentConceptId\":\"" + concept
                + "\",\"price\":{\"amount\":\"10\",\"currency\":\"HNL\"}}"), 400, "validation-failed");
        assertProblem(post(items(id), admin, "{\"price\":{\"amount\":\"10\",\"currency\":\"HNL\"},"
                + "\"validFrom\":\"2027-01-01\"}"), 400, "validation-failed");
        assertProblem(post(items(id), admin, "{\"paymentConceptId\":\"" + concept + "\",\"price\":{\"amount\":\"10\","
                + "\"currency\":\"HNL\"},\"validFrom\":\"2027-01-01\",\"institutionId\":\"" + UUID.randomUUID() + "\"}"),
                400, "validation-failed");
        assertProblem(send("POST", LISTS + "/not-a-uuid/items", admin.token(),
                itemBody(concept, "10", "HNL", "2027-01-01", null)), 400, "validation-failed");

        assertThat(HarnessProcess.json(get(LISTS + "/" + id, admin)).get("items")).isEmpty();
        assertThat(priceListEntriesOf(admin.institution())).hasSize(entries);
        assertProblem(post(items(id), admin, itemBody(concept, "9999999999.9999", "HNL", "2027-03-01", null)), 400,
                "validation-failed");
        assertThat(created(post(items(id), admin, itemBody(concept, "0.01", "USD", "2027-02-01", "2027-02-01")))
                .get("price").get("amount").asString()).isEqualTo("0.01");
        assertThat(created(post(items(id), admin, itemBody(concept, "9999999999.99", "HNL", "2027-03-01", null)))
                .get("price").get("amount").asString()).isEqualTo("9999999999.99");
    }

    @Test
    void theAmountShownIsExactlyTheAmountStoredWithTwoDecimals() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");
        String id = created(post(LISTS, admin, listBody(structure.year(), "L", "INSTITUTION", null))).get("id").asString();

        assertThat(created(post(items(id), admin, itemBody(concept, "10.05", "HNL", "2027-01-01", "2027-01-31")))
                .get("price").get("amount").asString()).isEqualTo("10.05");
        assertThat(created(post(items(id), admin, itemBody(concept, "7", "USD", "2027-03-01", "2027-03-31")))
                .get("price").get("amount").asString()).isEqualTo("7.00");

        assertThat(storedAmounts(admin.institution())).containsExactly("10.0500", "7.0000");
    }

    @Test
    void aDateOutsideTwoThousandToTwentyOneHundredIsRefusedWith400NeverWith500AndTheBoundsAreAccepted()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");
        String id = created(post(LISTS, admin, listBody(structure.year(), "L", "INSTITUTION", null))).get("id").asString();

        for (String[] range : new String[][] {{"1999-12-31", null}, {"0001-01-01", null}, {"2101-01-01", null},
                {"+10000-01-01", null}, {"2027-01-01", "2101-01-01"}, {"2027-01-01", "9999-12-31"},
                {"2027-01-01", "1999-12-31"}}) {
            assertProblem(post(items(id), admin, itemBody(concept, "10", "HNL", range[0], range[1])), 400,
                    "validation-failed");
        }

        assertThat(HarnessProcess.json(get(LISTS + "/" + id, admin)).get("items")).isEmpty();
        created(post(items(id), admin, itemBody(concept, "10", "HNL", "2000-01-01", "2100-12-31")));
    }

    @Test
    void aPriceAddedWhileTheConceptIsBeingDeactivatedWaitsForItAndIsRefusedAsInactive() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");
        String id = created(post(LISTS, admin, listBody(structure.year(), "L", "INSTITUTION", null))).get("id").asString();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(admin.institution())) {
            // The deactivation is in flight (its row lock is held) when the price arrives.
            execute(owner, "update catalog_payment_concept set is_active = false where id = ?", UUID.fromString(concept));
            Future<HttpResponse<String>> add = pool.submit(
                    () -> post(items(id), admin, itemBody(concept, "10", "HNL", "2027-01-01", null)));
            Thread.sleep(1500);
            assertThat(add.isDone()).as("the price waits for the lock of the concept").isFalse();
            owner.commit();

            assertProblem(add.get(), 409, "inactive-reference");
        } finally {
            pool.shutdownNow();
        }
        assertThat(HarnessProcess.json(get(LISTS + "/" + id, admin)).get("items")).isEmpty();
    }

    // --- Archived lists, inactive and foreign concepts ---

    @Test
    void anArchivedListTakesNoPriceButStillShowsItsHistoryAndItTakesPricesAgainOnceActive() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        String concept = concept(admin, "COLEG");
        String id = created(post(LISTS, admin, listBody(structure.year(), "L", "INSTITUTION", null))).get("id").asString();
        created(post(items(id), admin, itemBody(concept, "1500", "HNL", "2027-01-01", null)));
        put(id, admin, updateBody(0, "L", "ARCHIVED"));
        int entries = priceListEntriesOf(admin.institution()).size();

        assertProblem(post(items(id), admin, itemBody(concept, "1650", "HNL", "2027-07-01", null)), 409,
                "price-list-archived");

        JsonNode detail = HarnessProcess.json(get(LISTS + "/" + id, admin));
        assertThat(detail.get("items")).hasSize(1);
        assertThat(detail.get("items").get(0).get("validTo").isNull()).isTrue();
        assertThat(priceListEntriesOf(admin.institution())).hasSize(entries);
        put(id, admin, updateBody(1, "L", "ACTIVE"));
        created(post(items(id), admin, itemBody(concept, "1650", "HNL", "2027-07-01", null)));
    }

    @Test
    void anInactiveConceptTakesNoPriceAndAConceptOfAnotherInstitutionOrUnknownIsNotFound() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(mine.institution());
        String inactive = concept(mine, "OLD");
        assertThat(put(CONCEPTS + "/" + inactive, mine, "{\"version\":0,\"name\":\"Viejo\",\"category\":\"OTHER\","
                + "\"isTaxable\":false,\"isRecurring\":false,\"isActive\":false}").statusCode()).isEqualTo(200);
        String foreign = concept(theirs, "AJENO");
        String id = created(post(LISTS, mine, listBody(structure.year(), "L", "INSTITUTION", null))).get("id").asString();
        int entries = priceListEntriesOf(mine.institution()).size();

        assertProblem(post(items(id), mine, itemBody(inactive, "10", "HNL", "2027-01-01", null)), 409,
                "inactive-reference");
        assertProblem(post(items(id), mine, itemBody(foreign, "10", "HNL", "2027-01-01", null)), 404,
                "resource-not-found");
        assertProblem(post(items(id), mine, itemBody(UUID.randomUUID().toString(), "10", "HNL", "2027-01-01", null)),
                404, "resource-not-found");

        assertThat(HarnessProcess.json(get(LISTS + "/" + id, mine)).get("items")).isEmpty();
        assertThat(priceListEntriesOf(mine.institution())).hasSize(entries);
    }

    // --- Lists ---

    @Test
    void theListPagesSortsAndFiltersOnTheServerAndShowsArchivedLists() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(admin.institution());
        UUID otherYear = year(admin.institution(), "2028");
        created(post(LISTS, admin, listBody(structure.year(), "b-lista", "INSTITUTION", null)));
        String archived = created(post(LISTS, admin, listBody(structure.year(), "A-lista", "MODALITY", structure.modality())))
                .get("id").asString();
        put(archived, admin, updateBody(0, "A-lista", "ARCHIVED"));
        created(post(LISTS, admin, listBody(otherYear, "C-lista", "GRADE", structure.grade())));

        assertThat(names(HarnessProcess.json(get(LISTS, admin)))).containsExactly("A-lista", "b-lista", "C-lista");
        assertThat(names(HarnessProcess.json(get(LISTS + "?sort=name,desc", admin))))
                .containsExactly("C-lista", "b-lista", "A-lista");
        assertThat(names(HarnessProcess.json(get(LISTS + "?academicYearId=" + otherYear, admin))))
                .containsExactly("C-lista");
        assertThat(names(HarnessProcess.json(get(LISTS + "?scope=MODALITY", admin)))).containsExactly("A-lista");
        assertThat(names(HarnessProcess.json(get(LISTS + "?status=ARCHIVED", admin)))).containsExactly("A-lista");
        assertThat(names(HarnessProcess.json(get(LISTS + "?status=ACTIVE&scope=MODALITY", admin)))).isEmpty();
        JsonNode firstPage = HarnessProcess.json(get(LISTS + "?size=2", admin));
        assertThat(firstPage.propertyNames()).containsExactly("items", "total", "page", "size");
        assertThat(firstPage.get("total").asLong()).isEqualTo(3);
        assertThat(firstPage.get("items")).hasSize(2);
        assertThat(names(HarnessProcess.json(get(LISTS + "?size=2&page=1", admin)))).containsExactly("C-lista");
        assertThat(firstPage.get("items").get(0).propertyNames()).containsExactly("id", "academicYearId", "name",
                "scope", "scopeRefId", "status", "version", "createdAt", "updatedAt");
    }

    @Test
    void thePageParametersAndTheFiltersAreBoundedByTheServer() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertProblem(get(LISTS + "?size=201", admin), 400, "validation-failed");
        assertProblem(get(LISTS + "?size=0", admin), 400, "validation-failed");
        assertProblem(get(LISTS + "?page=-1", admin), 400, "validation-failed");
        assertProblem(get(LISTS + "?sort=version", admin), 400, "validation-failed");
        assertProblem(get(LISTS + "?sort=name,sideways", admin), 400, "validation-failed");
        assertProblem(get(LISTS + "?scope=ALL", admin), 400, "validation-failed");
        assertProblem(get(LISTS + "?status=OPEN", admin), 400, "validation-failed");
        assertProblem(get(LISTS + "?academicYearId=nope", admin), 400, "validation-failed");
        assertThat(HarnessProcess.json(get(LISTS + "?size=200", admin)).get("size").asInt()).isEqualTo(200);
    }

    @Test
    void aListThatDoesNotExistIsNotFoundOnReadEditAndAdd() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String concept = concept(admin, "COLEG");
        String unknown = UUID.randomUUID().toString();

        assertProblem(get(LISTS + "/" + unknown, admin), 404, "resource-not-found");
        assertProblem(put(unknown, admin, updateBody(0, "X", "ACTIVE")), 404, "resource-not-found");
        assertProblem(post(items(unknown), admin, itemBody(concept, "10", "HNL", "2027-01-01", null)), 404,
                "resource-not-found");
        assertThat(priceListEntriesOf(admin.institution()).stream()
                .filter(entry -> !entry[0].startsWith("catalog.payment_concept"))).isEmpty();
    }

    // --- Roles: the decision is the server's ---

    @Test
    void aCashierMayReadButCannotCreateEditOrPriceAndTheRefusalLeavesNoRowAndNoEntry() throws SQLException {
        Member cashier = member(StaffRole.CASHIER.code());
        Member admin = sameInstitution(cashier.institution(), StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(cashier.institution());
        String concept = concept(admin, "COLEG");
        String id = created(post(LISTS, admin, listBody(structure.year(), "L", "INSTITUTION", null))).get("id").asString();
        created(post(items(id), admin, itemBody(concept, "1500", "HNL", "2027-01-01", null)));
        int entries = priceListEntriesOf(cashier.institution()).size();

        assertThat(get(LISTS, cashier).statusCode()).isEqualTo(200);
        assertThat(get(LISTS + "/" + id, cashier).statusCode()).isEqualTo(200);
        assertProblem(post(LISTS, cashier, listBody(structure.year(), "Otra", "INSTITUTION", null)), 403, "forbidden");
        assertProblem(put(id, cashier, updateBody(0, "Cambiada", "ARCHIVED")), 403, "forbidden");
        assertProblem(post(items(id), cashier, itemBody(concept, "1650", "HNL", "2027-07-01", null)), 403, "forbidden");

        JsonNode detail = HarnessProcess.json(get(LISTS + "/" + id, admin));
        assertThat(detail.get("name").asString()).isEqualTo("L");
        assertThat(detail.get("items")).hasSize(1);
        assertThat(detail.get("items").get(0).get("validTo").isNull()).isTrue();
        assertThat(listCountOf(cashier.institution())).isEqualTo(1);
        assertThat(priceListEntriesOf(cashier.institution())).hasSize(entries);
    }

    @Test
    void anAccountWithoutARoleCannotEvenRead() throws SQLException {
        Member nobody = member(null);
        String id = UUID.randomUUID().toString();

        assertProblem(get(LISTS, nobody), 403, "forbidden");
        assertProblem(get(LISTS + "/" + id, nobody), 403, "forbidden");
        assertProblem(post(LISTS, nobody, listBody(UUID.randomUUID(), "X", "INSTITUTION", null)), 403, "forbidden");
        assertProblem(put(id, nobody, updateBody(0, "X", "ACTIVE")), 403, "forbidden");
        assertProblem(post(items(id), nobody, itemBody(UUID.randomUUID().toString(), "1", "HNL", "2027-01-01", null)),
                403, "forbidden");
    }

    @Test
    void noCredentialAnswers401() {
        assertProblem(send("GET", LISTS, null, null), 401, "authentication-required");
        assertProblem(send("GET", LISTS + "/" + UUID.randomUUID(), null, null), 401, "authentication-required");
    }

    // --- Isolation between institutions ---

    @Test
    void aUserOfAnotherInstitutionNeverSeesNorEditsNorPricesTheListsOfThisOne() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        Structure structure = structure(mine.institution());
        String concept = concept(mine, "COLEG");
        String theirConcept = concept(theirs, "COLEG");
        String id = created(post(LISTS, mine, listBody(structure.year(), "L", "INSTITUTION", null))).get("id").asString();
        created(post(items(id), mine, itemBody(concept, "1500", "HNL", "2027-01-01", null)));

        JsonNode theirPage = HarnessProcess.json(get(LISTS, theirs));
        assertThat(theirPage.get("total").asLong()).isZero();
        assertThat(theirPage.get("items")).isEmpty();
        assertProblem(get(LISTS + "/" + id, theirs), 404, "resource-not-found");
        assertProblem(put(id, theirs, updateBody(0, "Robada", "ARCHIVED")), 404, "resource-not-found");
        assertProblem(post(items(id), theirs, itemBody(theirConcept, "1", "HNL", "2027-07-01", null)), 404,
                "resource-not-found");
        assertProblem(post(items(id), theirs, itemBody(concept, "1", "HNL", "2027-07-01", null)), 404,
                "resource-not-found");

        JsonNode mineAfter = HarnessProcess.json(get(LISTS + "/" + id, mine));
        assertThat(mineAfter.get("name").asString()).isEqualTo("L");
        assertThat(mineAfter.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(mineAfter.get("items")).hasSize(1);
        assertThat(priceListEntriesOf(theirs.institution()).stream()
                .filter(entry -> !entry[0].startsWith("catalog.payment_concept"))).isEmpty();
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

    /** A year, a modality and a grade of the institution, as the owner of the schema. */
    private static Structure structure(UUID institution) throws SQLException {
        UUID year = year(institution, "2027");
        UUID modality = UUID.randomUUID();
        UUID grade = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            execute(owner, "insert into organization_modality (institution_id, id, code, name) "
                    + "values (?, ?, 'BACH', 'Bachillerato')", institution, modality);
            execute(owner, "insert into organization_grade (institution_id, id, modality_id, name, ordinal) "
                    + "values (?, ?, ?, 'Decimo', 10)", institution, grade, modality);
            owner.commit();
        }
        return new Structure(year, modality, grade);
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

    // --- Bodies and reading responses ---

    private static String items(String listId) {
        return LISTS + "/" + listId + "/items";
    }

    private static String listBody(UUID year, String name, String scope, UUID reference) {
        return "{\"academicYearId\":\"" + year + "\",\"name\":\"" + name + "\",\"scope\":\"" + scope + "\""
                + (reference == null ? "" : ",\"scopeRefId\":\"" + reference + "\"") + "}";
    }

    private static String updateBody(long version, String name, String status) {
        return "{\"version\":" + version + ",\"name\":\"" + name + "\",\"status\":\"" + status + "\"}";
    }

    private static String itemBody(String concept, String amount, String currency, String from, String to) {
        return "{\"paymentConceptId\":\"" + concept + "\",\"price\":{\"amount\":\"" + amount + "\",\"currency\":\""
                + currency + "\"},\"validFrom\":\"" + from + "\"" + (to == null ? "" : ",\"validTo\":\"" + to + "\"")
                + "}";
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

    private static long listCountOf(UUID institution) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from catalog_price_list where institution_id = ?")) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static List<String> storedAmounts(UUID institution) throws SQLException {
        List<String> amounts = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select amount::text from catalog_price_list_item where institution_id = ? order by valid_from")) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    amounts.add(result.getString(1));
                }
            }
        }
        return amounts;
    }

    /** action, entity type, entity id, before, after of every entry of the catalog, oldest first. */
    private static List<String[]> priceListEntriesOf(UUID institution) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select action, entity_type, entity_id, before_value::text, after_value::text
                          from shared_audit_log
                         where institution_id = ? and action like 'catalog.%' order by id
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

    private static List<String> actorsOfCatalogEntries(UUID institution) throws SQLException {
        List<String> actors = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select actor_id::text from shared_audit_log
                         where institution_id = ? and action like 'catalog.%'
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
        String path = id.startsWith("/") ? id : LISTS + "/" + id;
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

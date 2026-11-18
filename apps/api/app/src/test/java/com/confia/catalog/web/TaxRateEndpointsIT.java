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
 * The five routes of the tax rates, and the tax rate of a payment concept, on the real administrative process, started
 * through the production launcher against a real PostgreSQL with the least-privilege role of the application
 * (docs/cortes/catalog-tax-rates.md). Every token is made by the production issuer; the accounts, the roles and the
 * institution are seeded as the owner of the schema; every test has an institution of its own.
 *
 * <p>No percentage is known to the system: every figure in this class is an input of a case, loaded through the API as an
 * administrator would. It covers the acceptance criterion (a rate created without any figure, a first period and a later
 * one that closes it the day before, assigned to a taxable concept), INV-11 through the API, the percentage and date
 * rules, the stale version, the concurrent additions, the role without the permission, the isolation between
 * institutions and the audit entry of every write.
 */
class TaxRateEndpointsIT extends CommittingPostgresIntegrationTest {

    private static final String RATES = "/api/v1/catalog/tax-rates";
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

    // --- The acceptance criterion ---

    @Test
    void anAdministratorCreatesIsvWithNoFigureLoadsTwoPeriodsAssignsItToColegiaturaAndSeesBothInTheHistory()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        JsonNode rate = created(post(RATES, admin, rateBody("ISV", "Impuesto sobre ventas")));
        String id = rate.get("id").asString();
        assertThat(rate.propertyNames()).containsExactly("id", "code", "name", "isActive", "version", "createdAt",
                "updatedAt");
        assertThat(rate.get("isActive").asBoolean()).isTrue();
        assertThat(rate.get("version").asLong()).isZero();
        assertThat(HarnessProcess.json(get(RATES + "/" + id, admin)).get("periods")).as("no figure preloaded").isEmpty();

        JsonNode first = created(post(periods(id), admin, periodBody("12.5", "2027-01-01", null)));
        assertThat(first.propertyNames()).containsExactly("id", "taxRateId", "ratePercent", "validFrom", "validTo",
                "createdAt");
        assertThat(first.get("ratePercent").isString()).as("the percentage is a string").isTrue();
        assertThat(first.get("ratePercent").asString()).isEqualTo("12.5000");
        assertThat(first.get("taxRateId").asString()).isEqualTo(id);
        assertThat(first.get("validTo").isNull()).isTrue();
        JsonNode second = created(post(periods(id), admin, periodBody("13.25", "2027-07-01", null)));
        assertThat(second.get("ratePercent").asString()).isEqualTo("13.2500");

        JsonNode concept = created(post(CONCEPTS, admin, conceptBody("COLEG", true, id)));
        assertThat(concept.get("taxRateId").asString()).isEqualTo(id);
        assertThat(concept.get("taxRateCode").asString()).isEqualTo("ISV");
        assertThat(concept.get("taxRateName").asString()).isEqualTo("Impuesto sobre ventas");

        JsonNode detail = HarnessProcess.json(get(RATES + "/" + id, admin));
        assertThat(detail.propertyNames()).containsExactly("id", "code", "name", "isActive", "version", "createdAt",
                "updatedAt", "periods");
        assertThat(detail.get("periods")).hasSize(2);
        assertThat(detail.get("periods").get(0).get("ratePercent").asString()).isEqualTo("12.5000");
        assertThat(detail.get("periods").get(0).get("validFrom").asString()).isEqualTo("2027-01-01");
        assertThat(detail.get("periods").get(0).get("validTo").asString()).isEqualTo("2027-06-30");
        assertThat(detail.get("periods").get(1).get("ratePercent").asString()).isEqualTo("13.2500");
        assertThat(detail.get("periods").get(1).get("validTo").isNull()).isTrue();

        List<String[]> entries = entriesOf(admin.institution());
        assertThat(entries).extracting(entry -> entry[0]).containsExactly("catalog.tax_rate.created",
                "catalog.tax_rate_period.created", "catalog.tax_rate_period.created",
                "catalog.payment_concept.created");
        assertThat(entries.get(0)[4]).contains("\"code\": \"ISV\"").doesNotContain("ratePercent");
        assertThat(entries.get(1)[4]).contains("\"ratePercent\": \"12.5000\"").doesNotContain("closedPrevious");
        assertThat(entries.get(2)[1]).isEqualTo("catalog.tax_rate_period");
        assertThat(entries.get(2)[2]).isEqualTo(second.get("id").asString());
        assertThat(entries.get(2)[4]).contains("\"ratePercent\": \"13.2500\"")
                .contains("\"closedPreviousPeriodId\": \"" + first.get("id").asString() + "\"")
                .contains("\"closedPreviousValidTo\": \"2027-06-30\"");
        assertThat(entries.get(3)[4]).contains("\"taxRateId\": \"" + id + "\"");
        assertThat(actorsOfCatalogEntries(admin.institution())).containsOnly(admin.account().toString());
    }

    @Test
    void theResponsesCarryNoStoreAndNeverTheInstitutionOfTheTokenNorTheStaffAccount() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        HttpResponse<String> rate = post(RATES, admin, rateBody("ISV", "Impuesto"));
        String id = HarnessProcess.json(rate).get("id").asString();
        HttpResponse<String> period = post(periods(id), admin, periodBody("10", "2027-01-01", null));
        HttpResponse<String> detail = get(RATES + "/" + id, admin);
        HttpResponse<String> page = get(RATES, admin);

        for (HttpResponse<String> response : List.of(rate, period, detail, page)) {
            assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
            assertThat(response.body()).doesNotContain(admin.institution().toString())
                    .doesNotContain(admin.account().toString());
        }
        assertThat(period.statusCode()).isEqualTo(201);
    }

    // --- Creating a rate ---

    @Test
    void aCodeTakenWithAnotherCapitalizationIsADuplicateAndAnotherInstitutionMayUseIt() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        created(post(RATES, mine, rateBody("ISV", "Impuesto")));
        int entries = entriesOf(mine.institution()).size();

        assertProblem(post(RATES, mine, rateBody("isv", "Otro")), 409, "duplicate-tax-rate-code");
        assertProblem(post(RATES, mine, rateBody("Isv", "Otro")), 409, "duplicate-tax-rate-code");
        created(post(RATES, theirs, rateBody("ISV", "Impuesto de ellos")));

        assertThat(rateCountOf(mine.institution())).isEqualTo(1);
        assertThat(entriesOf(mine.institution())).hasSize(entries);
    }

    @Test
    void aBadRateBodyIsRefusedWith400AndLeavesNoRowNorEntry() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertProblem(post(RATES, admin, rateBody("", "N")), 400, "validation-failed");
        assertProblem(post(RATES, admin, rateBody("   ", "N")), 400, "validation-failed");
        assertProblem(post(RATES, admin, rateBody("C".repeat(31), "N")), 400, "validation-failed");
        assertProblem(post(RATES, admin, rateBody("I SV", "N")), 400, "validation-failed");
        assertProblem(post(RATES, admin, rateBody("=ISV", "N")), 400, "validation-failed");
        assertProblem(post(RATES, admin, rateBody("ISV", "")), 400, "validation-failed");
        assertProblem(post(RATES, admin, rateBody("ISV", "N".repeat(121))), 400, "validation-failed");
        assertProblem(post(RATES, admin, rateBody("ISV", "Con\\nsalto")), 400, "validation-failed");
        assertProblem(post(RATES, admin, "{\"code\":\"ISV\"}"), 400, "validation-failed");
        assertProblem(post(RATES, admin, "{\"code\":\"ISV\",\"name\":\"N\",\"ratePercent\":\"12.5\"}"), 400,
                "validation-failed");
        assertProblem(post(RATES, admin, "{\"code\":\"ISV\",\"name\":\"N\",\"institutionId\":\"" + UUID.randomUUID()
                + "\"}"), 400, "validation-failed");
        assertProblem(post(RATES, admin, "not json"), 400, "validation-failed");

        assertThat(rateCountOf(admin.institution())).isZero();
        assertThat(entriesOf(admin.institution())).isEmpty();
        created(post(RATES, admin, rateBody("C".repeat(30), "N".repeat(120))));
    }

    // --- Editing a rate ---

    @Test
    void anEditChangesNameAndFlagAdvancesTheVersionNeverTheCodeAndAuditsBeforeAndAfter() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(RATES, admin, rateBody("ISV", "Impuesto"))).get("id").asString();

        JsonNode off = HarnessProcess.json(put(id, admin, updateBody(0, "Otro nombre", false)));
        JsonNode on = HarnessProcess.json(put(id, admin, updateBody(1, "Otro nombre", true)));

        assertThat(off.get("name").asString()).isEqualTo("Otro nombre");
        assertThat(off.get("code").asString()).isEqualTo("ISV");
        assertThat(off.get("isActive").asBoolean()).isFalse();
        assertThat(off.get("version").asLong()).isEqualTo(1);
        assertThat(on.get("isActive").asBoolean()).isTrue();
        assertThat(on.get("version").asLong()).isEqualTo(2);
        List<String[]> entries = entriesOf(admin.institution());
        assertThat(entries).extracting(entry -> entry[0]).containsExactly("catalog.tax_rate.created",
                "catalog.tax_rate.updated", "catalog.tax_rate.updated");
        assertThat(entries.get(1)[3]).contains("\"name\": \"Impuesto\"").contains("\"isActive\": true");
        assertThat(entries.get(1)[4]).contains("\"name\": \"Otro nombre\"").contains("\"isActive\": false");
    }

    @Test
    void aStaleOrMissingVersionAndABodyWithACodeOrAPercentageAreRefusedAndTheRateIsUntouched() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(RATES, admin, rateBody("ISV", "Impuesto"))).get("id").asString();
        put(id, admin, updateBody(0, "Uno", true));
        int entries = entriesOf(admin.institution()).size();

        assertProblem(put(id, admin, updateBody(0, "Dos", true)), 409, "stale-version");
        assertProblem(put(id, admin, updateBody(5, "Dos", true)), 409, "stale-version");
        assertProblem(put(id, admin, "{\"name\":\"Dos\",\"isActive\":true}"), 400, "validation-failed");
        assertProblem(put(id, admin, "{\"version\":-1,\"name\":\"Dos\",\"isActive\":true}"), 400, "validation-failed");
        assertProblem(put(id, admin, "{\"version\":1,\"name\":\"Dos\"}"), 400, "validation-failed");
        assertProblem(put(id, admin, "{\"version\":1,\"name\":\"Dos\",\"isActive\":true,\"code\":\"OTRO\"}"), 400,
                "validation-failed");
        assertProblem(put(id, admin, "{\"version\":1,\"name\":\"Dos\",\"isActive\":true,\"ratePercent\":\"9\"}"), 400,
                "validation-failed");

        JsonNode current = HarnessProcess.json(get(RATES + "/" + id, admin));
        assertThat(current.get("name").asString()).isEqualTo("Uno");
        assertThat(current.get("code").asString()).isEqualTo("ISV");
        assertThat(current.get("version").asLong()).isEqualTo(1);
        assertThat(entriesOf(admin.institution())).hasSize(entries);
    }

    @Test
    void twoEditsOfARateWithTheSameVersionAtTheSameTimeLetExactlyOneWin() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(RATES, admin, rateBody("ISV", "Impuesto"))).get("id").asString();

        List<Integer> statuses = concurrently(
                () -> put(id, admin, updateBody(0, "Ana", true)).statusCode(),
                () -> put(id, admin, updateBody(0, "Beto", false)).statusCode());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(HarnessProcess.json(get(RATES + "/" + id, admin)).get("version").asLong()).isEqualTo(1);
    }

    // --- The percentage ---

    @Test
    void aPercentageThatIsZeroNegativeAboveOneHundredWithMoreThanFourDecimalsOrNotAStringIsRefusedWith400()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(RATES, admin, rateBody("ISV", "Impuesto"))).get("id").asString();

        for (String bad : new String[] {"\"0\"", "\"0.0000\"", "\"-1\"", "\"-0.0001\"", "\"100.0001\"", "\"101\"",
                "\"1000\"", "\"5.00001\"", "\"abc\"", "\"\"", "\"+5\"", "\"1e1\"", "\" 5\"", "\"5,5\"", "\".5\"",
                "\"5.\"", "12.5", "7.5", "0", "-5", "true", "null", "[\"12.5\"]", "{\"v\":7}"}) {
            assertProblem(post(periods(id), admin, periodBodyRaw(bad, "\"2027-01-01\"", null)), 400,
                    "validation-failed");
        }
        assertProblem(post(periods(id), admin, "{\"validFrom\":\"2027-01-01\"}"), 400, "validation-failed");
        assertProblem(post(periods(id), admin, "{\"ratePercent\":\"12.5\"}"), 400, "validation-failed");

        assertThat(HarnessProcess.json(get(RATES + "/" + id, admin)).get("periods")).isEmpty();
        assertThat(entriesOf(admin.institution()).stream().filter(entry -> entry[0].contains("period"))).isEmpty();
    }

    @Test
    void theSmallestAndTheLargestPercentageAreAcceptedAndShownWithFourDecimals() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(RATES, admin, rateBody("ISV", "Impuesto"))).get("id").asString();

        assertThat(created(post(periods(id), admin, periodBody("0.0001", "2027-01-01", "2027-01-31")))
                .get("ratePercent").asString()).isEqualTo("0.0001");
        assertThat(created(post(periods(id), admin, periodBody("100", "2027-02-01", "2027-02-28")))
                .get("ratePercent").asString()).isEqualTo("100.0000");
        assertThat(created(post(periods(id), admin, periodBody("12.3456", "2027-03-01", null)))
                .get("ratePercent").asString()).isEqualTo("12.3456");
        assertThat(storedPercents(admin.institution())).containsExactly("0.0001", "100.0000", "12.3456");
    }

    // --- INV-11 through the API ---

    @Test
    void anExactAPartialAndAContainedOverlapAreAnswered409AndLeaveThePeriodsAndTheAuditUntouched()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(RATES, admin, rateBody("ISV", "Impuesto"))).get("id").asString();
        created(post(periods(id), admin, periodBody("10", "2027-01-01", "2027-06-30")));
        created(post(periods(id), admin, periodBody("12", "2027-09-01", "2027-12-31")));
        int entries = entriesOf(admin.institution()).size();

        for (String[] range : new String[][] {{"2027-01-01", "2027-06-30"}, {"2027-06-30", "2027-07-31"},
                {"2026-12-01", "2027-01-01"}, {"2027-02-01", "2027-02-28"}, {"2026-01-01", "2028-12-31"},
                {"2027-12-31", null}, {"2027-05-01", null}}) {
            assertProblem(post(periods(id), admin, periodBody("99", range[0], range[1])), 409, "tax-rate-period-overlap");
        }

        assertThat(HarnessProcess.json(get(RATES + "/" + id, admin)).get("periods")).hasSize(2);
        assertThat(entriesOf(admin.institution())).hasSize(entries);
    }

    @Test
    void aContiguousValidityIsAcceptedAndSoAreTheSameDatesForAnotherRateAndAnotherInstitution() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Member other = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(RATES, admin, rateBody("ISV", "Impuesto"))).get("id").asString();
        String second = created(post(RATES, admin, rateBody("TUR", "Turismo"))).get("id").asString();
        String foreign = created(post(RATES, other, rateBody("ISV", "Impuesto"))).get("id").asString();
        created(post(periods(id), admin, periodBody("10", "2027-01-01", "2027-06-30")));

        created(post(periods(id), admin, periodBody("11", "2027-07-01", "2027-12-31")));
        created(post(periods(id), admin, periodBody("12", "2028-01-01", null)));
        created(post(periods(second), admin, periodBody("4", "2027-01-01", "2027-06-30")));
        created(post(periods(foreign), other, periodBody("10", "2027-01-01", "2027-06-30")));

        assertThat(HarnessProcess.json(get(RATES + "/" + id, admin)).get("periods")).hasSize(3);
    }

    @Test
    void aReplacementOnOrBeforeTheOpenPeriodOrABoundedOneInsideItIsAnOverlapAndTheOpenPeriodStaysOpen()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(RATES, admin, rateBody("ISV", "Impuesto"))).get("id").asString();
        created(post(periods(id), admin, periodBody("10", "2027-01-01", null)));

        assertProblem(post(periods(id), admin, periodBody("11", "2027-01-01", null)), 409, "tax-rate-period-overlap");
        assertProblem(post(periods(id), admin, periodBody("11", "2026-12-31", null)), 409, "tax-rate-period-overlap");
        assertProblem(post(periods(id), admin, periodBody("11", "2027-07-01", "2027-07-31")), 409,
                "tax-rate-period-overlap");
        created(post(periods(id), admin, periodBody("9", "2026-01-01", "2026-12-31")));

        JsonNode periods = HarnessProcess.json(get(RATES + "/" + id, admin)).get("periods");
        assertThat(periods).hasSize(2);
        assertThat(periods.get(1).get("validTo").isNull()).isTrue();
    }

    @Test
    void twoSimultaneousAdditionsFromTheSameDayLetExactlyOneWinAndTheOtherIs409() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(RATES, admin, rateBody("ISV", "Impuesto"))).get("id").asString();

        List<Integer> statuses = concurrently(
                () -> post(periods(id), admin, periodBody("12.5", "2027-01-01", null)).statusCode(),
                () -> post(periods(id), admin, periodBody("16", "2027-01-01", null)).statusCode());

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(HarnessProcess.json(get(RATES + "/" + id, admin)).get("periods")).hasSize(1);
        assertThat(entriesOf(admin.institution()).stream()
                .filter(entry -> entry[0].equals("catalog.tax_rate_period.created"))).hasSize(1);
    }

    @Test
    void twoSimultaneousAdditionsFromDifferentDaysNeverLeaveTwoOpenValiditiesNorAnOverlap() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(RATES, admin, rateBody("ISV", "Impuesto"))).get("id").asString();
        created(post(periods(id), admin, periodBody("10", "2026-01-01", null)));

        List<Integer> statuses = concurrently(
                () -> post(periods(id), admin, periodBody("12.5", "2027-01-01", null)).statusCode(),
                () -> post(periods(id), admin, periodBody("16", "2028-01-01", null)).statusCode());

        // The lock serializes them: when the later day goes first, the earlier day starts before the open period and is an
        // overlap (409); when the earlier goes first, both are accepted. Never two open validities.
        assertThat(statuses).allMatch(status -> status == 201 || status == 409).contains(201);
        JsonNode periods = HarnessProcess.json(get(RATES + "/" + id, admin)).get("periods");
        assertThat(periods).hasSize(1 + (int) statuses.stream().filter(status -> status == 201).count());
        long open = 0;
        LocalDate previousEnd = null;
        for (JsonNode period : periods) {
            open += period.get("validTo").isNull() ? 1 : 0;
            LocalDate from = LocalDate.parse(period.get("validFrom").asString());
            if (previousEnd != null) {
                assertThat(from).as("no overlap with the previous validity").isAfter(previousEnd);
            }
            previousEnd = period.get("validTo").isNull() ? null : LocalDate.parse(period.get("validTo").asString());
        }
        assertThat(open).as("exactly one open validity").isEqualTo(1);
        assertThat(periods.get(periods.size() - 1).get("validTo").isNull()).isTrue();
    }

    @Test
    void aDateOutsideTwoThousandToTwentyOneHundredOrAnEndBeforeTheStartIsRefusedWith400NeverWith500()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(RATES, admin, rateBody("ISV", "Impuesto"))).get("id").asString();

        assertProblem(post(periods(id), admin, periodBody("5", "1999-12-31", null)), 400, "validation-failed");
        assertProblem(post(periods(id), admin, periodBody("5", "0202-03-01", null)), 400, "validation-failed");
        assertProblem(post(periods(id), admin, periodBody("5", "2101-01-01", null)), 400, "validation-failed");
        assertProblem(post(periods(id), admin, periodBody("5", "2027-01-01", "2101-01-01")), 400, "validation-failed");
        assertProblem(post(periods(id), admin, periodBody("5", "20271-01-01", null)), 400, "validation-failed");
        assertProblem(post(periods(id), admin, periodBody("5", "2027-02-30", null)), 400, "validation-failed");
        assertProblem(post(periods(id), admin, periodBody("5", "2027-02-01", "2027-01-31")), 400, "validation-failed");
        assertProblem(post(periods(id), admin, "{\"ratePercent\":\"5\",\"validFrom\":null}"), 400, "validation-failed");
        assertThat(HarnessProcess.json(get(RATES + "/" + id, admin)).get("periods")).isEmpty();

        created(post(periods(id), admin, periodBody("5", "2000-01-01", "2000-12-31")));
        created(post(periods(id), admin, periodBody("6", "2100-12-31", "2100-12-31")));
        created(post(periods(id), admin, periodBody("7", "2027-01-10", "2027-01-10")));
    }

    // --- The state of a rate ---

    @Test
    void anInactiveRateTakesNoPeriodUntilItIsActivatedAgainButStillShowsItsHistory() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(RATES, admin, rateBody("ISV", "Impuesto"))).get("id").asString();
        created(post(periods(id), admin, periodBody("10", "2027-01-01", null)));
        put(id, admin, updateBody(0, "Impuesto", false));
        int entries = entriesOf(admin.institution()).size();

        assertProblem(post(periods(id), admin, periodBody("11", "2027-07-01", null)), 409, "inactive-reference");
        assertThat(HarnessProcess.json(get(RATES + "/" + id, admin)).get("periods")).hasSize(1);
        assertThat(entriesOf(admin.institution())).hasSize(entries);

        put(id, admin, updateBody(1, "Impuesto", true));
        created(post(periods(id), admin, periodBody("11", "2027-07-01", null)));
        assertThat(HarnessProcess.json(get(RATES + "/" + id, admin)).get("periods")).hasSize(2);
    }

    @Test
    void aRateThatDoesNotExistIsNotFoundOnReadEditAndAdd() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String unknown = UUID.randomUUID().toString();

        assertProblem(get(RATES + "/" + unknown, admin), 404, "resource-not-found");
        assertProblem(put(unknown, admin, updateBody(0, "X", true)), 404, "resource-not-found");
        assertProblem(post(periods(unknown), admin, periodBody("5", "2027-01-01", null)), 404, "resource-not-found");
        assertProblem(get(RATES + "/not-a-uuid", admin), 400, "validation-failed");
        assertThat(entriesOf(admin.institution())).isEmpty();
    }

    // --- The tax rate of a payment concept ---

    @Test
    void aTaxableConceptNeedsATaxRateAndAConceptThatIsNotTaxableCannotHaveOneOnCreateAndOnEdit() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String rate = created(post(RATES, admin, rateBody("ISV", "Impuesto"))).get("id").asString();
        int entries = entriesOf(admin.institution()).size();

        assertProblem(post(CONCEPTS, admin, conceptBody("COLEG", true, null)), 400, "validation-failed");
        assertProblem(post(CONCEPTS, admin, conceptBody("BUS", false, rate)), 400, "validation-failed");
        assertProblem(post(CONCEPTS, admin, conceptBody("BUS", true, "not-a-uuid")), 400, "validation-failed");
        assertThat(conceptCountOf(admin.institution())).isZero();
        assertThat(entriesOf(admin.institution())).hasSize(entries);

        JsonNode coleg = created(post(CONCEPTS, admin, conceptBody("COLEG", true, rate)));
        String id = coleg.get("id").asString();
        assertProblem(put(CONCEPTS + "/" + id, admin, conceptPutBody(0, true, true, null)), 400, "validation-failed");
        assertProblem(put(CONCEPTS + "/" + id, admin, conceptPutBody(0, false, true, rate)), 400, "validation-failed");
        JsonNode untaxed = HarnessProcess.json(put(CONCEPTS + "/" + id, admin, conceptPutBody(0, false, true, null)));
        assertThat(untaxed.get("taxRateId").isNull()).isTrue();
        assertThat(untaxed.get("taxRateCode").isNull()).isTrue();
        JsonNode taxedAgain = HarnessProcess.json(put(CONCEPTS + "/" + id, admin, conceptPutBody(1, true, true, rate)));
        assertThat(taxedAgain.get("taxRateId").asString()).isEqualTo(rate);
        assertThat(taxedAgain.get("version").asLong()).isEqualTo(2);
    }

    @Test
    void aTaxRateOfAnotherInstitutionOrUnknownIsNotFoundAndAnInactiveOneIsAnInactiveReference() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        String foreign = created(post(RATES, theirs, rateBody("ISV", "Ajena"))).get("id").asString();
        String inactive = created(post(RATES, mine, rateBody("OLD", "Vieja"))).get("id").asString();
        put(inactive, mine, updateBody(0, "Vieja", false));
        String active = created(post(RATES, mine, rateBody("ISV", "Propia"))).get("id").asString();
        String conceptId = created(post(CONCEPTS, mine, conceptBody("COLEG", true, active))).get("id").asString();
        int entries = entriesOf(mine.institution()).size();

        assertProblem(post(CONCEPTS, mine, conceptBody("BUS", true, foreign)), 404, "resource-not-found");
        assertProblem(post(CONCEPTS, mine, conceptBody("BUS", true, UUID.randomUUID().toString())), 404,
                "resource-not-found");
        assertProblem(post(CONCEPTS, mine, conceptBody("BUS", true, inactive)), 409, "inactive-reference");
        assertProblem(put(CONCEPTS + "/" + conceptId, mine, conceptPutBody(0, true, true, foreign)), 404,
                "resource-not-found");
        assertProblem(put(CONCEPTS + "/" + conceptId, mine, conceptPutBody(0, true, true, inactive)), 409,
                "inactive-reference");

        assertThat(conceptCountOf(mine.institution())).isEqualTo(1);
        assertThat(entriesOf(mine.institution())).hasSize(entries);
        assertThat(HarnessProcess.json(get(CONCEPTS, mine)).get("items").get(0).get("taxRateId").asString())
                .isEqualTo(active);
    }

    @Test
    void aConceptThatKeepsItsRateStillEditsAfterTheRateIsDeactivatedAndTheListShowsTheRateCodeAndName()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String rate = created(post(RATES, admin, rateBody("ISV", "Impuesto sobre ventas"))).get("id").asString();
        String id = created(post(CONCEPTS, admin, conceptBody("COLEG", true, rate))).get("id").asString();
        created(post(CONCEPTS, admin, conceptBody("BUS", false, null)));
        put(rate, admin, updateBody(0, "Impuesto sobre ventas", false));

        HttpResponse<String> off = put(CONCEPTS + "/" + id, admin, conceptPutBody(0, true, false, rate));

        assertThat(off.statusCode()).as(off.body()).isEqualTo(200);
        assertThat(HarnessProcess.json(off).get("isActive").asBoolean()).isFalse();
        JsonNode page = HarnessProcess.json(get(CONCEPTS, admin));
        assertThat(page.get("items").get(0).get("code").asString()).isEqualTo("BUS");
        assertThat(page.get("items").get(0).get("taxRateId").isNull()).isTrue();
        assertThat(page.get("items").get(1).get("taxRateCode").asString()).isEqualTo("ISV");
        assertThat(page.get("items").get(1).get("taxRateName").asString()).isEqualTo("Impuesto sobre ventas");
    }

    @Test
    void aTaxableConceptCreatedBeforeTheRatesExistedIsToleratedUntilItIsEdited() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        UUID legacy = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(admin.institution());
                PreparedStatement statement = owner.prepareStatement("insert into catalog_payment_concept "
                        + "(institution_id, id, code, name, category, is_taxable, is_recurring) "
                        + "values (?, ?, 'VIEJO', 'Viejo', 'TUITION', true, true)")) {
            statement.setObject(1, admin.institution());
            statement.setObject(2, legacy);
            statement.executeUpdate();
            owner.commit();
        }
        String rate = created(post(RATES, admin, rateBody("ISV", "Impuesto"))).get("id").asString();

        JsonNode listed = HarnessProcess.json(get(CONCEPTS, admin)).get("items").get(0);
        assertThat(listed.get("isTaxable").asBoolean()).isTrue();
        assertThat(listed.get("taxRateId").isNull()).isTrue();
        String path = CONCEPTS + "/" + legacy;
        assertThat(put(path, admin, conceptPutBody(0, true, false, null)).statusCode()).isEqualTo(200);
        assertThat(put(path, admin, conceptPutBody(1, true, true, null)).statusCode()).isEqualTo(200);
        JsonNode renamed = HarnessProcess.json(put(path, admin, conceptPutBody(2, true, true, null)));
        assertThat(renamed.get("taxRateId").isNull()).isTrue();
        assertThat(renamed.get("version").asLong()).isEqualTo(3);
        // Becoming taxable again without a rate is refused: only the legacy state is tolerated.
        assertThat(put(path, admin, conceptPutBody(3, false, true, null)).statusCode()).isEqualTo(200);
        assertProblem(put(path, admin, conceptPutBody(4, true, true, null)), 400, "validation-failed");
        HttpResponse<String> fixed = put(path, admin, conceptPutBody(4, true, true, rate));
        assertThat(fixed.statusCode()).as(fixed.body()).isEqualTo(200);
        assertThat(HarnessProcess.json(fixed).get("taxRateCode").asString()).isEqualTo("ISV");
    }

    @Test
    void aConceptWithAnInactiveRateMayBeEditedAndDeactivatedButNotActivatedAgainUntilTheRateIsActive()
            throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String rate = created(post(RATES, admin, rateBody("ISV", "Impuesto"))).get("id").asString();
        String id = created(post(CONCEPTS, admin, conceptBody("COLEG", true, rate))).get("id").asString();
        put(rate, admin, updateBody(0, "Impuesto", false));
        String path = CONCEPTS + "/" + id;

        assertThat(put(path, admin, conceptPutBody(0, true, false, rate)).statusCode()).isEqualTo(200);
        assertThat(put(path, admin, conceptPutBody(1, true, false, rate)).statusCode()).as("still inactive").isEqualTo(200);
        assertProblem(put(path, admin, conceptPutBody(2, true, true, rate)), 409, "inactive-reference");
        put(rate, admin, updateBody(1, "Impuesto", true));
        assertThat(put(path, admin, conceptPutBody(2, true, true, rate)).statusCode()).isEqualTo(200);
    }

    // --- Lists ---

    @Test
    void theListPagesSortsAndFiltersOnTheServer() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        created(post(RATES, admin, rateBody("b-rate", "Beta")));
        String off = created(post(RATES, admin, rateBody("A-rate", "Zeta"))).get("id").asString();
        put(off, admin, updateBody(0, "Zeta", false));
        created(post(RATES, admin, rateBody("C-rate", "Alfa")));

        assertThat(codes(HarnessProcess.json(get(RATES, admin)))).containsExactly("A-rate", "b-rate", "C-rate");
        assertThat(codes(HarnessProcess.json(get(RATES + "?sort=code,desc", admin))))
                .containsExactly("C-rate", "b-rate", "A-rate");
        assertThat(codes(HarnessProcess.json(get(RATES + "?sort=name", admin))))
                .containsExactly("C-rate", "b-rate", "A-rate");
        assertThat(codes(HarnessProcess.json(get(RATES + "?active=true", admin)))).containsExactly("b-rate", "C-rate");
        assertThat(codes(HarnessProcess.json(get(RATES + "?active=false", admin)))).containsExactly("A-rate");
        JsonNode firstPage = HarnessProcess.json(get(RATES + "?size=2", admin));
        assertThat(firstPage.propertyNames()).containsExactly("items", "total", "page", "size");
        assertThat(firstPage.get("total").asLong()).isEqualTo(3);
        assertThat(firstPage.get("items")).hasSize(2);
        assertThat(codes(HarnessProcess.json(get(RATES + "?size=2&page=1", admin)))).containsExactly("C-rate");
    }

    @Test
    void thePageParametersAreBoundedByTheServer() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertProblem(get(RATES + "?size=201", admin), 400, "validation-failed");
        assertProblem(get(RATES + "?size=0", admin), 400, "validation-failed");
        assertProblem(get(RATES + "?page=-1", admin), 400, "validation-failed");
        assertProblem(get(RATES + "?sort=version", admin), 400, "validation-failed");
        assertProblem(get(RATES + "?sort=code,sideways", admin), 400, "validation-failed");
        assertProblem(get(RATES + "?active=maybe", admin), 400, "validation-failed");
        assertThat(HarnessProcess.json(get(RATES + "?size=200", admin)).get("size").asInt()).isEqualTo(200);
    }

    // --- Roles: the decision is the server's ---

    @Test
    void aCashierMayReadButCannotCreateEditOrAddAPeriodAndTheRefusalLeavesNoRowAndNoEntry() throws SQLException {
        Member cashier = member(StaffRole.CASHIER.code());
        Member admin = sameInstitution(cashier.institution(), StaffRole.ADMINISTRATOR.code());
        String id = created(post(RATES, admin, rateBody("ISV", "Impuesto"))).get("id").asString();
        created(post(periods(id), admin, periodBody("10", "2027-01-01", null)));
        int entries = entriesOf(cashier.institution()).size();

        assertThat(get(RATES, cashier).statusCode()).isEqualTo(200);
        assertThat(get(RATES + "/" + id, cashier).statusCode()).isEqualTo(200);
        assertProblem(post(RATES, cashier, rateBody("OTRA", "Otra")), 403, "forbidden");
        assertProblem(put(id, cashier, updateBody(0, "Cambiada", false)), 403, "forbidden");
        assertProblem(post(periods(id), cashier, periodBody("11", "2027-07-01", null)), 403, "forbidden");

        JsonNode detail = HarnessProcess.json(get(RATES + "/" + id, admin));
        assertThat(detail.get("name").asString()).isEqualTo("Impuesto");
        assertThat(detail.get("periods")).hasSize(1);
        assertThat(detail.get("periods").get(0).get("validTo").isNull()).isTrue();
        assertThat(rateCountOf(cashier.institution())).isEqualTo(1);
        assertThat(entriesOf(cashier.institution())).hasSize(entries);
    }

    @Test
    void anAccountWithoutARoleCannotEvenRead() throws SQLException {
        Member nobody = member(null);
        String id = UUID.randomUUID().toString();

        assertProblem(get(RATES, nobody), 403, "forbidden");
        assertProblem(get(RATES + "/" + id, nobody), 403, "forbidden");
        assertProblem(post(RATES, nobody, rateBody("ISV", "X")), 403, "forbidden");
        assertProblem(put(id, nobody, updateBody(0, "X", true)), 403, "forbidden");
        assertProblem(post(periods(id), nobody, periodBody("5", "2027-01-01", null)), 403, "forbidden");
    }

    @Test
    void noCredentialAnswers401() {
        assertProblem(send("GET", RATES, null, null), 401, "authentication-required");
        assertProblem(send("GET", RATES + "/" + UUID.randomUUID(), null, null), 401, "authentication-required");
        assertProblem(send("POST", periods(UUID.randomUUID().toString()), null, periodBody("5", "2027-01-01", null)),
                401, "authentication-required");
    }

    // --- Isolation between institutions ---

    @Test
    void aUserOfAnotherInstitutionNeverSeesNorEditsNorAddsPeriodsToTheRatesOfThisOne() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(RATES, mine, rateBody("ISV", "Impuesto"))).get("id").asString();
        created(post(periods(id), mine, periodBody("10", "2027-01-01", null)));

        JsonNode theirPage = HarnessProcess.json(get(RATES, theirs));
        assertThat(theirPage.get("total").asLong()).isZero();
        assertThat(theirPage.get("items")).isEmpty();
        assertProblem(get(RATES + "/" + id, theirs), 404, "resource-not-found");
        assertProblem(put(id, theirs, updateBody(0, "Robada", false)), 404, "resource-not-found");
        assertProblem(post(periods(id), theirs, periodBody("1", "2027-07-01", null)), 404, "resource-not-found");

        JsonNode mineAfter = HarnessProcess.json(get(RATES + "/" + id, mine));
        assertThat(mineAfter.get("name").asString()).isEqualTo("Impuesto");
        assertThat(mineAfter.get("isActive").asBoolean()).isTrue();
        assertThat(mineAfter.get("periods")).hasSize(1);
        assertThat(entriesOf(theirs.institution())).isEmpty();
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

    // --- Bodies and reading responses ---

    private static String periods(String rateId) {
        return RATES + "/" + rateId + "/periods";
    }

    private static String rateBody(String code, String name) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}";
    }

    private static String updateBody(long version, String name, boolean active) {
        return "{\"version\":" + version + ",\"name\":\"" + name + "\",\"isActive\":" + active + "}";
    }

    private static String periodBody(String percent, String from, String to) {
        return periodBodyRaw("\"" + percent + "\"", "\"" + from + "\"", to == null ? null : "\"" + to + "\"");
    }

    /** The members as raw JSON, so a case can send a number or a null where a string belongs. */
    private static String periodBodyRaw(String percentJson, String fromJson, String toJson) {
        return "{\"ratePercent\":" + percentJson + ",\"validFrom\":" + fromJson
                + (toJson == null ? "" : ",\"validTo\":" + toJson) + "}";
    }

    private static String conceptBody(String code, boolean taxable, String taxRateId) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + code + "\",\"category\":\"TUITION\",\"isTaxable\":" + taxable
                + ",\"isRecurring\":true" + (taxRateId == null ? "" : ",\"taxRateId\":\"" + taxRateId + "\"") + "}";
    }

    private static String conceptPutBody(long version, boolean taxable, boolean active, String taxRateId) {
        return "{\"version\":" + version + ",\"name\":\"Concepto\",\"category\":\"TUITION\",\"isTaxable\":" + taxable
                + ",\"isRecurring\":true,\"isActive\":" + active
                + (taxRateId == null ? "" : ",\"taxRateId\":\"" + taxRateId + "\"") + "}";
    }

    private static List<String> codes(JsonNode page) {
        List<String> codes = new ArrayList<>();
        page.get("items").forEach(item -> codes.add(item.get("code").asString()));
        return codes;
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

    private static long countOf(String sql, UUID institution) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static long rateCountOf(UUID institution) throws SQLException {
        return countOf("select count(*) from catalog_tax_rate where institution_id = ?", institution);
    }

    private static long conceptCountOf(UUID institution) throws SQLException {
        return countOf("select count(*) from catalog_payment_concept where institution_id = ?", institution);
    }

    private static List<String> storedPercents(UUID institution) throws SQLException {
        List<String> percents = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("select rate_percent::text from "
                        + "catalog_tax_rate_period where institution_id = ? order by valid_from")) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    percents.add(result.getString(1));
                }
            }
        }
        return percents;
    }

    /** action, entity type, entity id, before, after of every entry of the catalog, oldest first. */
    private static List<String[]> entriesOf(UUID institution) throws SQLException {
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

    private static HttpResponse<String> put(String idOrPath, Member member, String body) {
        String path = idOrPath.startsWith("/") ? idOrPath : RATES + "/" + idOrPath;
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

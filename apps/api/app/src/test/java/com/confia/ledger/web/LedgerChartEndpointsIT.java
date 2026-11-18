package com.confia.ledger.web;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The routes that load the chart of accounts and the posting map of the charges, on the real administrative process started
 * through the production launcher against a real PostgreSQL with the least-privilege role of the application
 * (docs/cortes/ledger-chart-of-accounts.md): who may load them (only the Super Administrator), the rules of an account, the
 * validation of the map against the chart, the optimistic versions, the account that the map points at, the audit trail and the
 * isolation between institutions. Every token is made by the production issuer and every institution is seeded as the owner of
 * the schema. Account codes and names are fictional.
 */
class LedgerChartEndpointsIT extends CommittingPostgresIntegrationTest {

    private static final String ACCOUNTS = "/api/v1/ledger/accounts";
    private static final String MAP = "/api/v1/charges/posting-accounts";
    private static final String RATE_LIMIT = "confia.web.rate-limit.";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private static KeyPair keys;
    private static CompactJws jws;
    private static ConfigurableApplicationContext process;
    private static int port;

    @BeforeAll
    static void startTheProcess() throws NoSuchAlgorithmException {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        jws = new CompactJws(SigningKeyRing.of(SigningKey.signing(AdminProcessLauncher.signingKid(), keys.getPublic(),
                keys.getPrivate())));
        process = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(), "confia_admin_app", TEST_PASSWORD,
                keys, Map.of(RATE_LIMIT + "admin-read.request-limit", "10000",
                        RATE_LIMIT + "admin-write.request-limit", "10000",
                        RATE_LIMIT + "admin-read-account.request-limit", "10000",
                        RATE_LIMIT + "admin-write-account.request-limit", "10000"));
        port = ((WebServerApplicationContext) process).getWebServer().getPort();
    }

    @AfterAll
    static void stopTheProcess() {
        process.close();
    }

    private record Member(UUID account, UUID institution, String token) {
    }

    // --- Who may load the chart ---

    @Test
    void onlyTheSuperAdministratorCreatesAndEditsAccountsAndSetsTheMapAndTheOtherRolesAreForbidden() throws SQLException {
        Member superAdmin = member(StaffRole.SUPER_ADMIN.code());
        JsonNode created = HarnessProcess.json(created(post(ACCOUNTS, superAdmin, account("TST-1", "Por cobrar", "ASSET", true))));
        String id = created.get("id").asString();
        String entry = "{\"entries\":[{\"purpose\":\"RECEIVABLE\",\"ledgerAccountId\":\"" + id + "\"}]}";

        for (StaffRole role : StaffRole.values()) {
            if (role == StaffRole.SUPER_ADMIN) {
                continue;
            }
            Member other = sameInstitution(superAdmin.institution(), role.code());
            assertProblem(post(ACCOUNTS, other, account("TST-9", "Otra", "INCOME", false)), 403, "forbidden");
            assertProblem(put(ACCOUNTS + "/" + id, other, "{\"version\":0,\"name\":\"Robo\",\"active\":false}"), 403,
                    "forbidden");
            assertProblem(put(MAP, other, entry), 403, "forbidden");
        }
        assertThat(HarnessProcess.json(get(ACCOUNTS, superAdmin)).get("total").asLong())
                .as("nothing the forbidden roles sent was written").isEqualTo(1);
        assertThat(HarnessProcess.json(get(MAP, superAdmin)).get("items")).isEmpty();
        assertThat(send("POST", ACCOUNTS, null, account("TST-9", "Otra", "INCOME", false)).statusCode()).isEqualTo(401);
    }

    @Test
    void theRolesThatReadTheMoneyFlowReadTheChartAndTheMapAndTheOnesWithoutAccessDoNot() throws SQLException {
        Member superAdmin = member(StaffRole.SUPER_ADMIN.code());
        created(post(ACCOUNTS, superAdmin, account("TST-1", "Por cobrar", "ASSET", true)));

        for (StaffRole role : List.of(StaffRole.ADMINISTRATOR, StaffRole.CASHIER, StaffRole.ACCOUNTING, StaffRole.AUDITOR)) {
            Member reader = sameInstitution(superAdmin.institution(), role.code());
            assertThat(get(ACCOUNTS, reader).statusCode()).as(role.code()).isEqualTo(200);
            assertThat(get(MAP, reader).statusCode()).as(role.code()).isEqualTo(200);
        }
        Member coordinator = sameInstitution(superAdmin.institution(), StaffRole.ACADEMIC_COORDINATOR.code());
        assertProblem(get(ACCOUNTS, coordinator), 403, "forbidden");
        assertThat(get(MAP, coordinator).statusCode()).as("charges:read").isEqualTo(200);
    }

    // --- The accounts ---

    @Test
    void theSuggestedMinimalChartLoadsListsAndFiltersByTypeAndActiveFlag() throws SQLException {
        Member admin = member(StaffRole.SUPER_ADMIN.code());
        String[][] chart = {{"TST-100", "Cuentas por cobrar", "ASSET", "true"}, {"TST-110", "Caja y bancos", "ASSET", "false"},
                {"TST-200", "Impuesto por pagar", "LIABILITY", "false"},
                {"TST-410", "Ingresos por colegiatura", "INCOME", "false"},
                {"TST-420", "Ingresos por matricula", "INCOME", "false"},
                {"TST-430", "Otros ingresos", "INCOME", "false"}};
        String firstIncome = null;
        for (String[] row : chart) {
            JsonNode account = HarnessProcess.json(created(post(ACCOUNTS, admin,
                    account(row[0], row[1], row[2], Boolean.parseBoolean(row[3])))));
            assertThat(account.propertyNames()).containsExactly("id", "code", "name", "type", "holdsStudentBalances",
                    "active", "version", "createdAt", "updatedAt");
            assertThat(account.get("active").asBoolean()).isTrue();
            assertThat(account.get("version").asLong()).isZero();
            if (firstIncome == null && row[2].equals("INCOME")) {
                firstIncome = account.get("id").asString();
            }
        }
        assertThat(put(ACCOUNTS + "/" + firstIncome, admin,
                "{\"version\":0,\"name\":\"Ingresos por colegiatura\",\"active\":false}").statusCode()).isEqualTo(200);

        JsonNode all = HarnessProcess.json(get(ACCOUNTS, admin));
        assertThat(all.get("total").asLong()).isEqualTo(6);
        assertThat(all.get("page").asInt()).isZero();
        assertThat(all.get("size").asInt()).isEqualTo(50);
        assertThat(codes(all)).containsExactly("TST-100", "TST-110", "TST-200", "TST-410", "TST-420", "TST-430");
        assertThat(codes(HarnessProcess.json(get(ACCOUNTS + "?type=INCOME", admin)))).containsExactly("TST-410", "TST-420",
                "TST-430");
        assertThat(codes(HarnessProcess.json(get(ACCOUNTS + "?type=INCOME&active=false", admin))))
                .containsExactly("TST-410");
        assertThat(codes(HarnessProcess.json(get(ACCOUNTS + "?active=true&sort=code,desc&size=2", admin))))
                .containsExactly("TST-430", "TST-420");
        assertThat(HarnessProcess.json(get(ACCOUNTS + "?active=true&sort=code,desc&size=2&page=1", admin)).get("total")
                .asLong()).isEqualTo(5);
        assertProblem(get(ACCOUNTS + "?size=500", admin), 400, "validation-failed");
        assertProblem(get(ACCOUNTS + "?type=REVENUE", admin), 400, "validation-failed");
        assertProblem(get(ACCOUNTS + "?sort=version", admin), 400, "validation-failed");
        assertProblem(get(ACCOUNTS + "?page=-1", admin), 400, "validation-failed");
    }

    @Test
    void aCodeThatExistsInOtherCapitalsIsADuplicateAndAnotherInstitutionMayUseIt() throws SQLException {
        Member admin = member(StaffRole.SUPER_ADMIN.code());
        created(post(ACCOUNTS, admin, account("Tst-Ab.1", "Una", "ASSET", false)));

        assertProblem(post(ACCOUNTS, admin, account("TST-AB.1", "Otra", "ASSET", false)), 409, "duplicate-account-code");
        assertProblem(post(ACCOUNTS, admin, account("tst-ab.1", "Otra", "INCOME", false)), 409, "duplicate-account-code");
        assertThat(HarnessProcess.json(get(ACCOUNTS, admin)).get("total").asLong()).isEqualTo(1);

        Member theirs = member(StaffRole.SUPER_ADMIN.code());
        created(post(ACCOUNTS, theirs, account("Tst-Ab.1", "Una de ellos", "ASSET", false)));
    }

    @Test
    void anInvalidBodyIsRefusedAtTheEdgeAndNothingIsWritten() throws SQLException {
        Member admin = member(StaffRole.SUPER_ADMIN.code());

        assertProblem(post(ACCOUNTS, admin, account("TST-1", "Cuenta", "REVENUE", false)), 400, "validation-failed");
        assertProblem(post(ACCOUNTS, admin, account("TST-1", "Cuenta", "income", false)), 400, "validation-failed");
        assertProblem(post(ACCOUNTS, admin, account("TST-1", "Cuenta", "INCOME", true)), 400, "validation-failed");
        assertProblem(post(ACCOUNTS, admin, account("TST-1", "Cuenta", "EQUITY", true)), 400, "validation-failed");
        assertProblem(post(ACCOUNTS, admin, account("", "Cuenta", "ASSET", false)), 400, "validation-failed");
        assertProblem(post(ACCOUNTS, admin, account("with space", "Cuenta", "ASSET", false)), 400, "validation-failed");
        assertProblem(post(ACCOUNTS, admin, account("A".repeat(31), "Cuenta", "ASSET", false)), 400, "validation-failed");
        assertProblem(post(ACCOUNTS, admin, account("TST-1", "  ", "ASSET", false)), 400, "validation-failed");
        assertProblem(post(ACCOUNTS, admin, account("TST-1", "a".repeat(121), "ASSET", false)), 400, "validation-failed");
        assertProblem(post(ACCOUNTS, admin, "{\"code\":\"TST-1\",\"name\":\"Cuenta\",\"type\":\"ASSET\"}"), 400,
                "validation-failed");
        assertProblem(post(ACCOUNTS, admin,
                "{\"code\":\"TST-1\",\"name\":\"Cuenta\",\"type\":\"ASSET\",\"holdsStudentBalances\":false,"
                        + "\"institutionId\":\"" + UUID.randomUUID() + "\"}"), 400, "validation-failed");
        assertThat(HarnessProcess.json(get(ACCOUNTS, admin)).get("total").asLong()).isZero();
    }

    @Test
    void anEditChangesOnlyTheNameAndTheActiveFlagAndMovesTheVersion() throws SQLException {
        Member admin = member(StaffRole.SUPER_ADMIN.code());
        JsonNode created = HarnessProcess.json(created(post(ACCOUNTS, admin, account("TST-1", "Cuenta", "INCOME", false))));
        String path = ACCOUNTS + "/" + created.get("id").asString();

        JsonNode edited = HarnessProcess.json(put(path, admin, "{\"version\":0,\"name\":\"Nuevo nombre\",\"active\":false}"));
        assertThat(edited.get("name").asString()).isEqualTo("Nuevo nombre");
        assertThat(edited.get("active").asBoolean()).isFalse();
        assertThat(edited.get("version").asLong()).isEqualTo(1);
        assertThat(edited.get("code").asString()).isEqualTo("TST-1");
        assertThat(edited.get("type").asString()).isEqualTo("INCOME");

        assertProblem(put(path, admin, "{\"version\":0,\"name\":\"Otra vez\",\"active\":true}"), 409, "stale-version");
        assertProblem(put(path, admin, "{\"version\":1,\"name\":\"Cuenta\",\"active\":true,\"code\":\"OTHER\"}"), 400,
                "validation-failed");
        assertProblem(put(path, admin, "{\"version\":1,\"name\":\"Cuenta\",\"active\":true,\"type\":\"ASSET\"}"), 400,
                "validation-failed");
        assertProblem(put(path, admin, "{\"version\":1,\"name\":\" \",\"active\":true}"), 400, "validation-failed");
        assertProblem(put(path, admin, "{\"name\":\"Cuenta\",\"active\":true}"), 400, "validation-failed");
        assertProblem(put(path, admin, "{\"version\":1,\"name\":\"Cuenta\"}"), 400, "validation-failed");
        assertProblem(put(ACCOUNTS + "/not-a-uuid", admin, "{\"version\":1,\"name\":\"Cuenta\",\"active\":true}"), 400,
                "validation-failed");

        JsonNode reactivated = HarnessProcess.json(put(path, admin, "{\"version\":1,\"name\":\"Cuenta\",\"active\":true}"));
        assertThat(reactivated.get("version").asLong()).isEqualTo(2);
        assertThat(reactivated.get("active").asBoolean()).isTrue();
    }

    @Test
    void theDatabaseStillRefusesToChangeTheCodeTheTypeOrToDeleteAnAccountEvenThroughTheAppRole() throws SQLException {
        Member admin = member(StaffRole.SUPER_ADMIN.code());
        JsonNode created = HarnessProcess.json(created(post(ACCOUNTS, admin, account("TST-1", "Cuenta", "INCOME", false))));
        UUID id = UUID.fromString(created.get("id").asString());

        transactionRunner().execute(new SecurityContext("", "system", admin.institution().toString(),
                UUID.randomUUID().toString()), () -> {
                    org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> dsl.execute("update ledger_account set code = 'TST-2' where id = ?", id)).isNotNull();
                    return null;
                });
        assertThat(HarnessProcess.json(get(ACCOUNTS, admin)).get("items").get(0).get("code").asString()).isEqualTo("TST-1");
    }

    // --- The posting map ---

    @Test
    void theMapIsLoadedInStepsAndReadBackAndAnIncompleteMapIsAnAcceptedState() throws SQLException {
        Member admin = member(StaffRole.SUPER_ADMIN.code());
        String receivable = id(post(ACCOUNTS, admin, account("TST-100", "Por cobrar", "ASSET", true)));
        String revenue = id(post(ACCOUNTS, admin, account("TST-410", "Ingresos", "INCOME", false)));
        String transport = id(post(ACCOUNTS, admin, account("TST-420", "Ingresos de transporte", "INCOME", false)));
        String tax = id(post(ACCOUNTS, admin, account("TST-200", "Impuesto por pagar", "LIABILITY", false)));
        assertThat(HarnessProcess.json(get(MAP, admin)).get("items")).as("nothing is seeded").isEmpty();

        JsonNode first = HarnessProcess.json(put(MAP, admin, entries(entry("RECEIVABLE", null, receivable, null))));
        assertThat(first.get("items")).hasSize(1);
        assertThat(first.get("items").get(0).propertyNames()).containsExactly("id", "purpose", "conceptCategory",
                "ledgerAccountId", "version", "updatedAt");
        assertThat(first.get("items").get(0).get("conceptCategory").isNull()).isTrue();
        assertThat(first.get("items").get(0).get("version").asLong()).isZero();

        JsonNode second = HarnessProcess.json(put(MAP, admin, entries(entry("REVENUE", null, revenue, null),
                entry("REVENUE", "TRANSPORT", transport, null), entry("TAX_PAYABLE", null, tax, null))));
        assertThat(second.get("items")).hasSize(4);
        assertThat(second.get("items").get(0).get("purpose").asString()).isEqualTo("RECEIVABLE");
        assertThat(HarnessProcess.json(get(MAP, admin)).get("items")).hasSize(4);
        JsonNode transportRow = null;
        for (JsonNode row : second.get("items")) {
            if (!row.get("conceptCategory").isNull()) {
                transportRow = row;
            }
        }
        assertThat(transportRow).isNotNull();
        assertThat(transportRow.get("conceptCategory").asString()).isEqualTo("TRANSPORT");
        assertThat(transportRow.get("ledgerAccountId").asString()).isEqualTo(transport);
    }

    @Test
    void anAccountIsReplacedWithTheVersionReadAndAStaleOrMissingVersionChangesNothing() throws SQLException {
        Member admin = member(StaffRole.SUPER_ADMIN.code());
        String revenue = id(post(ACCOUNTS, admin, account("TST-410", "Ingresos", "INCOME", false)));
        String other = id(post(ACCOUNTS, admin, account("TST-420", "Otros ingresos", "INCOME", false)));
        put(MAP, admin, entries(entry("REVENUE", null, revenue, null)));

        assertProblem(put(MAP, admin, entries(entry("REVENUE", null, other, null))), 409, "stale-version");
        assertProblem(put(MAP, admin, entries(entry("REVENUE", null, other, 3L))), 409, "stale-version");
        assertProblem(put(MAP, admin, entries(entry("REVENUE", "TUITION", other, 0L))), 409, "stale-version");
        assertThat(HarnessProcess.json(get(MAP, admin)).get("items").get(0).get("ledgerAccountId").asString())
                .isEqualTo(revenue);

        JsonNode replaced = HarnessProcess.json(put(MAP, admin, entries(entry("REVENUE", null, other, 0L))));
        assertThat(replaced.get("items").get(0).get("ledgerAccountId").asString()).isEqualTo(other);
        assertThat(replaced.get("items").get(0).get("version").asLong()).isEqualTo(1);
        assertProblem(put(MAP, admin, entries(entry("REVENUE", null, revenue, 0L))), 409, "stale-version");
    }

    @Test
    void theAccountsOfTheMapAreValidatedAgainstTheChartAndNothingOfARefusedRequestIsKept() throws SQLException {
        Member admin = member(StaffRole.SUPER_ADMIN.code());
        String receivable = id(post(ACCOUNTS, admin, account("TST-100", "Por cobrar", "ASSET", true)));
        String plainAsset = id(post(ACCOUNTS, admin, account("TST-110", "Caja", "ASSET", false)));
        String revenue = id(post(ACCOUNTS, admin, account("TST-410", "Ingresos", "INCOME", false)));
        String tax = id(post(ACCOUNTS, admin, account("TST-200", "Impuesto", "LIABILITY", false)));
        String inactive = id(post(ACCOUNTS, admin, account("TST-430", "Vieja", "INCOME", false)));
        put(ACCOUNTS + "/" + inactive, admin, "{\"version\":0,\"name\":\"Vieja\",\"active\":false}");

        assertProblem(put(MAP, admin, entries(entry("RECEIVABLE", null, revenue, null))), 409, "posting-account-invalid");
        assertProblem(put(MAP, admin, entries(entry("RECEIVABLE", null, plainAsset, null))), 409,
                "posting-account-invalid");
        assertProblem(put(MAP, admin, entries(entry("REVENUE", null, receivable, null))), 409, "posting-account-invalid");
        assertProblem(put(MAP, admin, entries(entry("TAX_PAYABLE", null, revenue, null))), 409, "posting-account-invalid");
        assertProblem(put(MAP, admin, entries(entry("REVENUE", null, inactive, null))), 409, "inactive-reference");
        assertProblem(put(MAP, admin, entries(entry("RECEIVABLE", null, UUID.randomUUID().toString(), null))), 404,
                "resource-not-found");
        assertProblem(put(MAP, admin, entries(entry("RECEIVABLE", null, receivable, null),
                entry("TAX_PAYABLE", null, revenue, null))), 409, "posting-account-invalid");
        assertThat(HarnessProcess.json(get(MAP, admin)).get("items")).as("the good entry of a bad request is not kept")
                .isEmpty();

        assertThat(put(MAP, admin, entries(entry("RECEIVABLE", null, receivable, null), entry("TAX_PAYABLE", null, tax, null)))
                .statusCode()).isEqualTo(200);
    }

    @Test
    void aMalformedMapRequestIsRefusedAtTheEdge() throws SQLException {
        Member admin = member(StaffRole.SUPER_ADMIN.code());
        String receivable = id(post(ACCOUNTS, admin, account("TST-100", "Por cobrar", "ASSET", true)));

        assertProblem(put(MAP, admin, "{\"entries\":[]}"), 400, "validation-failed");
        assertProblem(put(MAP, admin, "{}"), 400, "validation-failed");
        assertProblem(put(MAP, admin, entries(entry("EXPENSE", null, receivable, null))), 400, "validation-failed");
        assertProblem(put(MAP, admin, entries(entry("RECEIVABLE", "TUITION", receivable, null))), 400, "validation-failed");
        assertProblem(put(MAP, admin, entries(entry("REVENUE", "BOOKS", receivable, null))), 400, "validation-failed");
        assertProblem(put(MAP, admin, entries(entry("RECEIVABLE", null, "not-a-uuid", null))), 400, "validation-failed");
        assertProblem(put(MAP, admin, entries(entry("RECEIVABLE", null, receivable, -1L))), 400, "validation-failed");
        assertProblem(put(MAP, admin, entries(entry("RECEIVABLE", null, receivable, null),
                entry("RECEIVABLE", null, receivable, null))), 400, "validation-failed");
        assertProblem(put(MAP, admin, "{\"entries\":[{\"purpose\":\"RECEIVABLE\"}]}"), 400, "validation-failed");
        assertProblem(put(MAP, admin, "{\"institutionId\":\"" + UUID.randomUUID() + "\",\"entries\":[]}"), 400,
                "validation-failed");
        assertThat(HarnessProcess.json(get(MAP, admin)).get("items")).isEmpty();
    }

    @Test
    void anAccountThatTheMapPointsAtCannotBeDeactivatedUntilTheMapPointsElsewhere() throws SQLException {
        Member admin = member(StaffRole.SUPER_ADMIN.code());
        String revenue = id(post(ACCOUNTS, admin, account("TST-410", "Ingresos", "INCOME", false)));
        String other = id(post(ACCOUNTS, admin, account("TST-420", "Otros ingresos", "INCOME", false)));
        put(MAP, admin, entries(entry("REVENUE", null, revenue, null)));

        assertProblem(put(ACCOUNTS + "/" + revenue, admin, "{\"version\":0,\"name\":\"Ingresos\",\"active\":false}"), 409,
                "account-in-use");
        assertThat(HarnessProcess.json(get(ACCOUNTS + "?active=false", admin)).get("total").asLong()).isZero();
        assertThat(put(ACCOUNTS + "/" + revenue, admin, "{\"version\":0,\"name\":\"Ingresos 2\",\"active\":true}")
                .statusCode()).as("a rename is not a deactivation").isEqualTo(200);

        put(MAP, admin, entries(entry("REVENUE", null, other, 0L)));
        assertThat(put(ACCOUNTS + "/" + revenue, admin, "{\"version\":1,\"name\":\"Ingresos 2\",\"active\":false}")
                .statusCode()).isEqualTo(200);
        assertProblem(put(MAP, admin, entries(entry("REVENUE", null, revenue, 1L))), 409, "inactive-reference");
    }

    // --- Audit and isolation ---

    @Test
    void everyWriteLeavesItsEntryInTheAuditTrailWithTheActorAndNoRefusedWriteDoes() throws SQLException {
        Member admin = member(StaffRole.SUPER_ADMIN.code());
        String revenue = id(post(ACCOUNTS, admin, account("TST-410", "Ingresos", "INCOME", false)));
        String other = id(post(ACCOUNTS, admin, account("TST-420", "Otros ingresos", "INCOME", false)));
        put(ACCOUNTS + "/" + other, admin, "{\"version\":0,\"name\":\"Otros ingresos 2\",\"active\":true}");
        put(MAP, admin, entries(entry("REVENUE", null, revenue, null)));
        put(MAP, admin, entries(entry("REVENUE", null, other, 0L)));
        post(ACCOUNTS, admin, account("tst-410", "Duplicada", "INCOME", false));
        put(MAP, admin, entries(entry("REVENUE", null, revenue, 7L)));
        put(ACCOUNTS + "/" + revenue, admin, "{\"version\":0,\"name\":\"Ingresos\",\"active\":true}");
        Member cashier = sameInstitution(admin.institution(), StaffRole.CASHIER.code());
        post(ACCOUNTS, cashier, account("TST-999", "Prohibida", "INCOME", false));

        List<String[]> entries = auditOf(admin.institution());
        assertThat(entries).extracting(row -> row[0]).containsExactly("ledger.account.created", "ledger.account.created",
                "ledger.account.updated", "charges.posting_account.updated", "charges.posting_account.updated",
                "ledger.account.updated");
        assertThat(entries.get(0)[1]).isEqualTo("ledger.account");
        assertThat(entries.get(0)[3]).as("a creation has no before").isNull();
        assertThat(entries.get(0)[4]).contains("\"code\":\"TST-410\"").contains("\"type\":\"INCOME\"");
        assertThat(entries.get(2)[3]).contains("\"name\":\"Otros ingresos\"").contains("\"version\":0");
        assertThat(entries.get(2)[4]).contains("\"name\":\"Otros ingresos 2\"").contains("\"version\":1");
        assertThat(entries.get(3)[1]).isEqualTo("charges.posting_account");
        assertThat(entries.get(3)[3]).isNull();
        assertThat(entries.get(4)[3]).contains(revenue);
        assertThat(entries.get(4)[4]).contains(other).contains("\"version\":1");
        assertThat(auditActors(admin.institution())).containsOnly(admin.account().toString());
    }

    @Test
    void anotherInstitutionSeesNothingOfTheChartAndTheMapAndCannotTouchThem() throws SQLException {
        Member mine = member(StaffRole.SUPER_ADMIN.code());
        String receivable = id(post(ACCOUNTS, mine, account("TST-100", "Por cobrar", "ASSET", true)));
        put(MAP, mine, entries(entry("RECEIVABLE", null, receivable, null)));
        Member theirs = member(StaffRole.SUPER_ADMIN.code());
        String theirReceivable = id(post(ACCOUNTS, theirs, account("TST-100", "Por cobrar de ellos", "ASSET", true)));

        assertThat(HarnessProcess.json(get(ACCOUNTS, theirs)).get("total").asLong()).isEqualTo(1);
        assertThat(codes(HarnessProcess.json(get(ACCOUNTS, theirs)))).containsExactly("TST-100");
        assertThat(HarnessProcess.json(get(ACCOUNTS, theirs)).get("items").get(0).get("name").asString())
                .isEqualTo("Por cobrar de ellos");
        assertThat(HarnessProcess.json(get(MAP, theirs)).get("items")).isEmpty();
        assertProblem(put(ACCOUNTS + "/" + receivable, theirs, "{\"version\":0,\"name\":\"Robo\",\"active\":false}"), 404,
                "resource-not-found");
        assertProblem(put(MAP, theirs, entries(entry("RECEIVABLE", null, receivable, null))), 404, "resource-not-found");
        put(MAP, theirs, entries(entry("RECEIVABLE", null, theirReceivable, null)));
        assertThat(HarnessProcess.json(get(MAP, mine)).get("items").get(0).get("ledgerAccountId").asString())
                .isEqualTo(receivable);
        assertThat(HarnessProcess.json(get(ACCOUNTS, mine)).get("items").get(0).get("name").asString())
                .isEqualTo("Por cobrar");
    }

    // --- Helpers ---

    private static String account(String code, String name, String type, boolean holdsStudentBalances) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"holdsStudentBalances\":"
                + holdsStudentBalances + "}";
    }

    private static String entry(String purpose, String category, String account, Long version) {
        return "{\"purpose\":\"" + purpose + "\""
                + (category == null ? "" : ",\"conceptCategory\":\"" + category + "\"")
                + ",\"ledgerAccountId\":\"" + account + "\""
                + (version == null ? "" : ",\"version\":" + version) + "}";
    }

    private static String entries(String... entries) {
        return "{\"entries\":[" + String.join(",", entries) + "]}";
    }

    private static HttpResponse<String> created(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return response;
    }

    private static String id(HttpResponse<String> response) {
        return HarnessProcess.json(created(response)).get("id").asString();
    }

    private static List<String> codes(JsonNode page) {
        List<String> codes = new ArrayList<>();
        page.get("items").forEach(item -> codes.add(item.get("code").asString()));
        return codes;
    }

    private Member member(String roleCode) throws SQLException {
        UUID institution = UUID.randomUUID();
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
    }

    private Member sameInstitution(UUID institution, String roleCode) throws SQLException {
        UUID account = UUID.randomUUID();
        StaffRoleFixtures.committed(institution, account, roleCode);
        UUID family = UUID.randomUUID();
        Instant created = now().minus(Duration.ofMinutes(5));
        transactionRunner().execute(new SecurityContext("", "system", institution.toString(),
                UUID.randomUUID().toString()), () -> dsl.execute("""
                        insert into identity_refresh_token_family
                            (institution_id, id, account_id, second_factor_verified,
                             user_agent_fingerprint, created_at, last_used_at, absolute_expires_at)
                        values (?, ?, ?, true, ?, cast(? as timestamptz), cast(? as timestamptz),
                                cast(? as timestamptz))
                        """, institution, family, account,
                        com.confia.identity.domain.UserAgentFingerprint.of("agente-uno").value(), created.toString(),
                        created.toString(), created.plus(Duration.ofHours(12)).toString()));
        String token = new AccessTokenIssuer(jws, Clock.fixed(now(), ZoneOffset.UTC))
                .issueAccess(account, institution, family,
                        Set.of(AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD))
                .compact();
        return new Member(account, institution, token);
    }

    /** action, entity type, entity id, before, after of every entry of the chart and the map, oldest first. */
    private static List<String[]> auditOf(UUID institution) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select action, entity_type, entity_id, before_value::text, after_value::text
                          from shared_audit_log
                         where institution_id = ? and (action like 'ledger.account.%' or action like 'charges.posting_account.%')
                         order by id
                        """)) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new String[] {result.getString(1), result.getString(2), result.getString(3),
                            compact(result.getString(4)), compact(result.getString(5))});
                }
            }
        }
        return rows;
    }

    /** The audit column is jsonb, which the database prints with a space after each colon; the tests read it without. */
    private static String compact(String json) {
        return json == null ? null : json.replace("\": ", "\":");
    }

    private static List<String> auditActors(UUID institution) throws SQLException {
        List<String> actors = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select actor_id::text from shared_audit_log
                         where institution_id = ? and (action like 'ledger.account.%' or action like 'charges.posting_account.%')
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

    private static HttpResponse<String> put(String path, Member member, String body) {
        return send("PUT", path, member.token(), body);
    }

    private static HttpResponse<String> send(String method, String path, String token, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).method(method, body == null ? HttpRequest.BodyPublishers.noBody()
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

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }
}

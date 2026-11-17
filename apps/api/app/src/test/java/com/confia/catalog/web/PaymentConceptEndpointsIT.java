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
 * The three routes of the payment concepts on the real administrative process, started through the production launcher
 * against a real PostgreSQL with the least-privilege role of the application
 * (docs/cortes/catalog-payment-concepts.md). Every token is made by the production issuer; the accounts, the roles and
 * the institution rows are seeded as the owner of the schema; every test has an institution of its own.
 *
 * <p>It covers the acceptance criterion (an administrator creates "Colegiatura", lists it, edits it and deactivates
 * it, all audited), the code rules (a duplicate with other capitals is a 409, the code never changes), the validation
 * at the edge, the role without the permission, the isolation between institutions and the audit entry of every write.
 */
class PaymentConceptEndpointsIT extends CommittingPostgresIntegrationTest {

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
    void anAdministratorCreatesColegiaturaSeesItListedEditsItAndDeactivatesItAllAudited() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        UUID taxRate = seedTaxRate(admin.institution(), "ISV", "Impuesto sobre ventas");
        JsonNode created = created(post(CONCEPTS, admin, body("COLEG", "Colegiatura", "TUITION", true, true)
                .replace("}", ",\"taxRateId\":\"" + taxRate + "\"}")));
        String id = created.get("id").asString();

        assertThat(created.propertyNames()).containsExactly("id", "code", "name", "category", "isTaxable",
                "isRecurring", "isActive", "taxRateId", "taxRateCode", "taxRateName", "createdAt", "updatedAt",
                "version");
        assertThat(created.get("taxRateId").asString()).isEqualTo(taxRate.toString());
        assertThat(created.get("taxRateCode").asString()).isEqualTo("ISV");
        assertThat(created.get("taxRateName").asString()).isEqualTo("Impuesto sobre ventas");
        assertThat(created.get("code").asString()).isEqualTo("COLEG");
        assertThat(created.get("category").asString()).isEqualTo("TUITION");
        assertThat(created.get("isTaxable").asBoolean()).isTrue();
        assertThat(created.get("isRecurring").asBoolean()).isTrue();
        assertThat(created.get("isActive").asBoolean()).isTrue();

        JsonNode page = HarnessProcess.json(get(CONCEPTS, admin));
        assertThat(page.propertyNames()).containsExactly("items", "total", "page", "size");
        assertThat(page.get("total").asLong()).isEqualTo(1);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(50);
        assertThat(page.get("items")).hasSize(1);

        HttpResponse<String> edited = put(id, admin, putBody("Mensualidad", "OTHER", false, false, true));
        assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
        assertThat(HarnessProcess.json(edited).get("name").asString()).isEqualTo("Mensualidad");
        assertThat(HarnessProcess.json(edited).get("code").asString()).isEqualTo("COLEG");
        assertThat(HarnessProcess.json(edited).get("category").asString()).isEqualTo("OTHER");
        assertThat(HarnessProcess.json(edited).get("isTaxable").asBoolean()).isFalse();
        assertThat(HarnessProcess.json(edited).get("taxRateId").isNull()).isTrue();
        assertThat(HarnessProcess.json(edited).get("taxRateCode").isNull()).isTrue();

        assertThat(created.get("version").asLong()).isZero();
        assertThat(HarnessProcess.json(edited).get("version").asLong()).isEqualTo(1);
        HttpResponse<String> off = put(id, admin, putBody(1, "Mensualidad", "OTHER", false, false, false));
        assertThat(HarnessProcess.json(off).get("isActive").asBoolean()).isFalse();
        assertThat(HarnessProcess.json(off).get("version").asLong()).isEqualTo(2);
        HttpResponse<String> on = put(id, admin, putBody(2, "Mensualidad", "OTHER", false, false, true));
        assertThat(HarnessProcess.json(on).get("isActive").asBoolean()).isTrue();
        assertThat(HarnessProcess.json(on).get("version").asLong()).isEqualTo(3);

        assertThat(conceptEntriesOf(admin.institution())).extracting(entry -> entry[0]).containsExactly(
                "catalog.payment_concept.created", "catalog.payment_concept.updated",
                "catalog.payment_concept.updated", "catalog.payment_concept.updated");
        String[] createdEntry = conceptEntriesOf(admin.institution()).get(0);
        assertThat(createdEntry[1]).isEqualTo("catalog.payment_concept");
        assertThat(createdEntry[2]).isEqualTo(id);
        assertThat(createdEntry[3]).isNull();
        assertThat(createdEntry[4]).contains("\"code\": \"COLEG\"").contains("\"category\": \"TUITION\"");
        assertThat(actorsOfConceptEntries(admin.institution())).containsOnly(admin.account().toString());
    }

    @Test
    void theResponsesCarryNoStoreAndNeverTheInstitutionOfTheToken() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        HttpResponse<String> response = post(CONCEPTS, admin, body("COLEG", "Colegiatura", "TUITION", false, true));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(response.body()).doesNotContain(admin.institution().toString())
                .doesNotContain(admin.account().toString());
    }

    // --- The code ---

    @Test
    void aCodeRepeatedWithAnotherCapitalizationIsADuplicateNameAndLeavesNoRowNorEntry() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        created(post(CONCEPTS, admin, body("COLEG", "Colegiatura", "TUITION", false, true)));
        int entries = conceptEntriesOf(admin.institution()).size();

        assertProblem(post(CONCEPTS, admin, body("COLEG", "Otro", "OTHER", false, false)), 409, "duplicate-payment-concept-code");
        assertProblem(post(CONCEPTS, admin, body("coleg", "Otro", "OTHER", false, false)), 409, "duplicate-payment-concept-code");
        assertProblem(post(CONCEPTS, admin, body("CoLeG", "Otro", "OTHER", false, false)), 409, "duplicate-payment-concept-code");
        created(post(CONCEPTS, admin, body("MATRI", "Matricula", "ENROLLMENT", false, false)));

        assertThat(countOf(admin.institution())).isEqualTo(2);
        assertThat(conceptEntriesOf(admin.institution())).hasSize(entries + 1);
    }

    @Test
    void theSameCodeIsFreeInAnotherInstitution() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());

        created(post(CONCEPTS, mine, body("COLEG", "Colegiatura", "TUITION", false, true)));
        created(post(CONCEPTS, theirs, body("COLEG", "Colegiatura", "TUITION", false, true)));

        assertThat(countOf(mine.institution())).isEqualTo(1);
        assertThat(countOf(theirs.institution())).isEqualTo(1);
    }

    @Test
    void theCodeIsImmutableAnEditThatCarriesOneIsRefusedAndTheStoredCodeNeverChanges() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(CONCEPTS, admin, body("COLEG", "Colegiatura", "TUITION", false, true)))
                .get("id").asString();
        int entries = conceptEntriesOf(admin.institution()).size();

        assertProblem(put(id, admin, "{\"code\":\"OTRO\",\"name\":\"X\",\"category\":\"OTHER\","
                + "\"isTaxable\":false,\"isRecurring\":false,\"isActive\":true}"), 400, "validation-failed");

        assertThat(storedCode(admin.institution(), id)).isEqualTo("COLEG");
        assertThat(conceptEntriesOf(admin.institution())).hasSize(entries);
    }

    // --- Optimistic version (closes finding B-1 of the first cut) ---

    @Test
    void anEditWithAStaleOrMissingVersionIsRefusedAndTheFirstEditSurvives() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(CONCEPTS, admin, body("COLEG", "Colegiatura", "TUITION", false, true)))
                .get("id").asString();
        assertThat(put(id, admin, putBody(0, "Primero", "OTHER", false, false, true)).statusCode()).isEqualTo(200);
        int entries = conceptEntriesOf(admin.institution()).size();

        assertProblem(put(id, admin, putBody(0, "Segundo", "OTHER", false, false, true)), 409, "stale-version");
        assertProblem(put(id, admin, putBody(7, "Tercero", "OTHER", false, false, true)), 409, "stale-version");
        assertProblem(put(id, admin, "{\"name\":\"Sin version\",\"category\":\"OTHER\",\"isTaxable\":false,"
                + "\"isRecurring\":false,\"isActive\":true}"), 400, "validation-failed");
        assertProblem(put(id, admin, putBody(-1, "Negativa", "OTHER", false, false, true)), 400,
                "validation-failed");

        assertThat(HarnessProcess.json(get(CONCEPTS, admin)).get("items").get(0).get("name").asString())
                .isEqualTo("Primero");
        assertThat(conceptEntriesOf(admin.institution())).hasSize(entries);
    }

    @Test
    void twoEditsWithTheSameVersionAtTheSameTimeLetExactlyOneWin() throws Exception {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(CONCEPTS, admin, body("COLEG", "Colegiatura", "TUITION", false, true)))
                .get("id").asString();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<HttpResponse<String>>> answers = new ArrayList<>();
            for (String name : List.of("Ana", "Beto")) {
                answers.add(pool.submit(() -> {
                    start.await();
                    return put(id, admin, putBody(0, name, "OTHER", false, false, true));
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (java.util.concurrent.Future<HttpResponse<String>> answer : answers) {
                statuses.add(answer.get().statusCode());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(HarnessProcess.json(get(CONCEPTS, admin)).get("items").get(0).get("version").asLong())
                .isEqualTo(1);
    }

    // --- Validation at the edge ---

    @Test
    void aBlankOrTooLongCodeOrNameAndACategoryOutsideTheSetAreRefusedWith400AndLeaveNoRow() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertProblem(post(CONCEPTS, admin, body("", "Colegiatura", "TUITION", false, true)), 400,
                "validation-failed");
        assertProblem(post(CONCEPTS, admin, body("   ", "Colegiatura", "TUITION", false, true)), 400,
                "validation-failed");
        assertProblem(post(CONCEPTS, admin, body("C".repeat(31), "Colegiatura", "TUITION", false, true)), 400,
                "validation-failed");
        assertProblem(post(CONCEPTS, admin, body("COLEG", "", "TUITION", false, true)), 400, "validation-failed");
        assertProblem(post(CONCEPTS, admin, body("COLEG", "N".repeat(121), "TUITION", false, true)), 400,
                "validation-failed");
        assertProblem(post(CONCEPTS, admin, body("COLEG", "Colegiatura", "SCHOLARSHIP", false, true)), 400,
                "validation-failed");
        assertProblem(post(CONCEPTS, admin, body("COLEG", "Colegiatura", "tuition", false, true)), 400,
                "validation-failed");
        assertProblem(post(CONCEPTS, admin, "{\"code\":\"COLEG\",\"name\":\"Colegiatura\","
                + "\"isTaxable\":true,\"isRecurring\":true}"), 400, "validation-failed");
        assertProblem(post(CONCEPTS, admin, "{\"code\":\"COLEG\",\"name\":\"Colegiatura\",\"category\":\"TUITION\","
                + "\"isRecurring\":true}"), 400, "validation-failed");

        assertThat(countOf(admin.institution())).isZero();
        assertThat(conceptEntriesOf(admin.institution())).isEmpty();
        assertThat(created(post(CONCEPTS, admin, body("C".repeat(30), "N".repeat(120), "FINE", false, false))))
                .isNotNull();
    }

    @Test
    void aCodeOutsideTheWhitelistIsRefusedWith400AndLeavesNoRow() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        for (String code : List.of("CO\\u200bLEG", "\\u202eGELOC", "COLEG\\n", "CO\\nLEG", "=SUM(A1)", "-COLEG",
                "CO LEG", " COLEG", "Matr\\u00edcula", "C.O", "C/O", "\\u200bCOLEG")) {
            assertProblem(post(CONCEPTS, admin, body(code, "Colegiatura", "TUITION", false, true)), 400,
                    "validation-failed");
        }

        assertThat(countOf(admin.institution())).isZero();
        assertThat(conceptEntriesOf(admin.institution())).isEmpty();
        created(post(CONCEPTS, admin, body("A-1_b", "Colegiatura", "TUITION", false, true)));
    }

    @Test
    void aNameWithControlOrFormatCharactersIsRefusedWith400OnCreateAndOnEdit() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(CONCEPTS, admin, body("COLEG", "Colegiatura", "TUITION", false, true)))
                .get("id").asString();

        for (String name : List.of("Cole\\ngiatura", "Cole\\tgiatura", "Cole\\u200bgiatura", "\\u202earuta",
                "Cole\\u0000g")) {
            assertProblem(post(CONCEPTS, admin, body("OTRO", name, "TUITION", false, true)), 400,
                    "validation-failed");
            assertProblem(put(id, admin, putBody(name, "TUITION", false, true, true)), 400, "validation-failed");
        }

        assertThat(countOf(admin.institution())).isEqualTo(1);
        assertThat(conceptEntriesOf(admin.institution())).hasSize(1);
        assertThat(created(post(CONCEPTS, admin, body("MATRI", "Matrícula año", "ENROLLMENT", false, false)))
                .get("name").asString()).isEqualTo("Matrícula año");
    }

    @Test
    void anUnknownMemberAnInstitutionInTheBodyAndAMalformedIdentifierAreRefused() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertProblem(post(CONCEPTS, admin, "{\"code\":\"A\",\"name\":\"B\",\"category\":\"OTHER\","
                + "\"isTaxable\":false,\"isRecurring\":false,\"institutionId\":\"" + UUID.randomUUID() + "\"}"),
                400, "validation-failed");
        assertProblem(send("PUT", CONCEPTS + "/not-a-uuid", admin.token(),
                putBody("B", "OTHER", false, false, true)), 400, "validation-failed");
        assertProblem(put(UUID.randomUUID().toString(), admin,
                "{\"name\":\"B\",\"category\":\"OTHER\",\"isTaxable\":false,\"isRecurring\":false}"), 400,
                "validation-failed");
    }

    @Test
    void thePageParametersAndTheFiltersAreBoundedByTheServer() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertProblem(get(CONCEPTS + "?size=201", admin), 400, "validation-failed");
        assertProblem(get(CONCEPTS + "?size=0", admin), 400, "validation-failed");
        assertProblem(get(CONCEPTS + "?page=-1", admin), 400, "validation-failed");
        assertProblem(get(CONCEPTS + "?sort=password", admin), 400, "validation-failed");
        assertProblem(get(CONCEPTS + "?sort=isActive", admin), 400, "validation-failed");
        assertProblem(get(CONCEPTS + "?sort=name,sideways", admin), 400, "validation-failed");
        assertProblem(get(CONCEPTS + "?category=NOPE", admin), 400, "validation-failed");
        assertProblem(get(CONCEPTS + "?active=maybe", admin), 400, "validation-failed");
        assertThat(HarnessProcess.json(get(CONCEPTS + "?size=200", admin)).get("size").asInt()).isEqualTo(200);
    }

    @Test
    void editingAConceptThatDoesNotExistIsNotFound() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());

        assertProblem(put(UUID.randomUUID().toString(), admin, putBody("X", "OTHER", false, false, true)), 404,
                "resource-not-found");
        assertThat(conceptEntriesOf(admin.institution())).isEmpty();
    }

    // --- Lists ---

    @Test
    void theListPagesSortsAndFiltersOnTheServerAndShowsInactiveConcepts() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        created(post(CONCEPTS, admin, body("b-multa", "Multa", "FINE", false, false)));
        created(post(CONCEPTS, admin, body("A-coleg", "Colegiatura", "TUITION", false, true)));
        String bus = created(post(CONCEPTS, admin, body("C-bus", "Bus", "TRANSPORT", false, true)))
                .get("id").asString();
        put(bus, admin, putBody("Bus", "TRANSPORT", false, true, false));

        assertThat(codes(HarnessProcess.json(get(CONCEPTS, admin)))).containsExactly("A-coleg", "b-multa", "C-bus");
        assertThat(codes(HarnessProcess.json(get(CONCEPTS + "?sort=code,desc", admin))))
                .containsExactly("C-bus", "b-multa", "A-coleg");
        assertThat(codes(HarnessProcess.json(get(CONCEPTS + "?sort=name", admin))))
                .containsExactly("C-bus", "A-coleg", "b-multa");
        assertThat(codes(HarnessProcess.json(get(CONCEPTS + "?category=FINE", admin)))).containsExactly("b-multa");
        assertThat(codes(HarnessProcess.json(get(CONCEPTS + "?active=false", admin)))).containsExactly("C-bus");
        assertThat(codes(HarnessProcess.json(get(CONCEPTS + "?active=true", admin))))
                .containsExactly("A-coleg", "b-multa");
        assertThat(codes(HarnessProcess.json(get(CONCEPTS + "?category=TRANSPORT&active=true", admin)))).isEmpty();
        JsonNode firstPage = HarnessProcess.json(get(CONCEPTS + "?size=2", admin));
        assertThat(firstPage.get("total").asLong()).isEqualTo(3);
        assertThat(firstPage.get("items")).hasSize(2);
        assertThat(codes(HarnessProcess.json(get(CONCEPTS + "?size=2&page=1", admin)))).containsExactly("C-bus");
    }

    // --- Roles: the decision is the server's ---

    @Test
    void aCashierMayReadButCannotCreateOrEditAndTheRefusalLeavesNoRowAndNoCatalogEntry() throws SQLException {
        Member cashier = member(StaffRole.CASHIER.code());
        Member admin = sameInstitution(cashier.institution(), StaffRole.ADMINISTRATOR.code());
        String id = created(post(CONCEPTS, admin, body("COLEG", "Colegiatura", "TUITION", false, true)))
                .get("id").asString();
        int entriesBefore = conceptEntriesOf(cashier.institution()).size();

        assertThat(get(CONCEPTS, cashier).statusCode()).isEqualTo(200);
        assertProblem(post(CONCEPTS, cashier, body("MATRI", "Matricula", "ENROLLMENT", false, false)), 403,
                "forbidden");
        assertProblem(put(id, cashier, putBody("Cambiado", "OTHER", false, false, false)), 403, "forbidden");

        assertThat(countOf(cashier.institution())).isEqualTo(1);
        assertThat(HarnessProcess.json(get(CONCEPTS, admin)).get("items").get(0).get("name").asString())
                .isEqualTo("Colegiatura");
        assertThat(conceptEntriesOf(cashier.institution())).hasSize(entriesBefore);
    }

    @Test
    void anAccountWithoutARoleCannotEvenRead() throws SQLException {
        Member nobody = member(null);

        assertProblem(get(CONCEPTS, nobody), 403, "forbidden");
        assertProblem(post(CONCEPTS, nobody, body("COLEG", "Colegiatura", "TUITION", false, true)), 403,
                "forbidden");
        assertProblem(put(UUID.randomUUID().toString(), nobody, putBody("X", "OTHER", false, false, true)), 403,
                "forbidden");
    }

    @Test
    void noCredentialAnswers401() {
        assertProblem(send("GET", CONCEPTS, null, null), 401, "authentication-required");
    }

    // --- Isolation between institutions ---

    @Test
    void aUserOfAnotherInstitutionNeverSeesNorEditsTheConceptsOfThisOne() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        String id = created(post(CONCEPTS, mine, body("COLEG", "Colegiatura", "TUITION", false, true)))
                .get("id").asString();

        JsonNode theirPage = HarnessProcess.json(get(CONCEPTS, theirs));
        assertThat(theirPage.get("total").asLong()).isZero();
        assertThat(theirPage.get("items")).isEmpty();
        assertProblem(put(id, theirs, putBody("Robado", "OTHER", false, false, false)), 404, "resource-not-found");

        JsonNode mineAfter = HarnessProcess.json(get(CONCEPTS, mine)).get("items").get(0);
        assertThat(mineAfter.get("name").asString()).isEqualTo("Colegiatura");
        assertThat(mineAfter.get("isActive").asBoolean()).isTrue();
        assertThat(conceptEntriesOf(theirs.institution())).isEmpty();
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

    /** A tax rate of the institution, inserted as the owner of the schema (it has no audit entry). */
    private static UUID seedTaxRate(UUID institution, String code, String name) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution);
                PreparedStatement statement = owner.prepareStatement(
                        "insert into catalog_tax_rate (institution_id, id, code, name) values (?, ?, ?, ?)")) {
            statement.setObject(1, institution);
            statement.setObject(2, id);
            statement.setString(3, code);
            statement.setString(4, name);
            statement.executeUpdate();
            owner.commit();
        }
        return id;
    }

    // --- Bodies and reading responses ---

    private static String body(String code, String name, String category, boolean taxable, boolean recurring) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"category\":\"" + category
                + "\",\"isTaxable\":" + taxable + ",\"isRecurring\":" + recurring + "}";
    }

    private static String putBody(String name, String category, boolean taxable, boolean recurring,
            boolean active) {
        return putBody(0, name, category, taxable, recurring, active);
    }

    private static String putBody(long version, String name, String category, boolean taxable,
            boolean recurring, boolean active) {
        return "{\"version\":" + version + ",\"name\":\"" + name + "\",\"category\":\"" + category
                + "\",\"isTaxable\":" + taxable + ",\"isRecurring\":" + recurring + ",\"isActive\":" + active + "}";
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

    // --- Reading the database as the superuser of the container ---

    private static long countOf(UUID institution) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from catalog_payment_concept where institution_id = ?")) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static String storedCode(UUID institution, String id) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select code from catalog_payment_concept where institution_id = ? and id = ?")) {
            statement.setObject(1, institution);
            statement.setObject(2, UUID.fromString(id));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    /** action, entity type, entity id, before, after of every entry of the catalog, oldest first. */
    private static List<String[]> conceptEntriesOf(UUID institution) throws SQLException {
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

    private static List<String> actorsOfConceptEntries(UUID institution) throws SQLException {
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
        return send("PUT", CONCEPTS + "/" + id, member.token(), body);
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

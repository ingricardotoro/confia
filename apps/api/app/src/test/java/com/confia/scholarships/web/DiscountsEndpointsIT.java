package com.confia.scholarships.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.AdminProcessLauncher;
import com.confia.identity.domain.StaffRole;
import com.confia.scholarships.ScholarshipsFixture;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.token.AccessTokenIssuer;
import com.confia.shared.security.token.CompactJws;
import com.confia.shared.security.token.SigningKey;
import com.confia.shared.security.token.SigningKeyRing;
import com.confia.shared.web.harness.HarnessProcess;
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
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The discount routes on the real administrative process, started through the production launcher against a real PostgreSQL with
 * the least-privilege role of the application (docs/cortes/scholarships-discounts.md): the permission of each role on the decisions,
 * the segregation of duties, the revoked session, the validation of the body and of the reason, the permanent key of each decision,
 * the closed scope of a discount in use and the isolation between institutions. Every token is made by the production issuer;
 * institutions, people, codes and amounts are fictional.
 */
class DiscountsEndpointsIT extends ScholarshipsFixture {

    private static final String RATE_LIMIT = "confia.web.rate-limit.";
    private static final String DISCOUNTS = "/api/v1/scholarships/discounts";
    private static final String ASSIGNMENTS = "/api/v1/scholarships/discount-assignments";
    private static final String REASON_TEXT = "El convenio ya no esta vigente para el estudiante";
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

    private record Member(UUID account, UUID institution, String token, UUID family) {
    }

    /** An institution with two students and the members of the roles that act on a discount. */
    private record World(Env env, Member administrator, Member superAdmin, Member coordinator, Member cashier) {

        UUID institution() {
            return env.institution();
        }

        UUID student() {
            return env.students().get(0);
        }
    }

    private World world() throws SQLException {
        Env env = seed(2);
        UUID institution = env.institution();
        return new World(env, member(institution, StaffRole.ADMINISTRATOR), member(institution, StaffRole.SUPER_ADMIN),
                member(institution, StaffRole.ACADEMIC_COORDINATOR), member(institution, StaffRole.CASHIER));
    }

    private static String discountBody(World w, String code, String kind) {
        return "{\"code\":\"" + code + "\",\"name\":\"Convenio del 10 %\",\"kind\":\"" + kind
                + "\",\"benefitType\":\"PERCENTAGE\",\"percent\":\"10\",\"validFrom\":\"2027-01-01\",\"validTo\":\"2027-12-31\","
                + "\"stackable\":true,\"conceptIds\":[\"" + w.env().concept() + "\"]}";
    }

    private static String updateBody(World w, long version, String percent, String validFrom, String validTo) {
        return "{\"version\":" + version + ",\"name\":\"Convenio del 10 %\",\"benefitType\":\"PERCENTAGE\",\"percent\":\"" + percent
                + "\",\"validFrom\":\"" + validFrom + "\",\"validTo\":\"" + validTo + "\",\"stackable\":true,\"conceptIds\":[\""
                + w.env().concept() + "\"]}";
    }

    private static String assignmentBody(World w, UUID discount, String from, String to) {
        return "{\"discountId\":\"" + discount + "\",\"studentId\":\"" + w.student() + "\",\"validFrom\":\"" + from
                + "\",\"validTo\":\"" + to + "\"}";
    }

    private static String reasonBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static UUID idOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isIn(200, 201);
        return UUID.fromString(HarnessProcess.json(response).get("id").asString());
    }

    private UUID discount(World w, String code, String kind) {
        return idOf(post(DISCOUNTS, w.administrator(), discountBody(w, code, kind), null));
    }

    /** An assignment requested by the coordinator for the first student. */
    private UUID requestedAssignment(World w, UUID discount) {
        return idOf(post(ASSIGNMENTS, w.coordinator(), assignmentBody(w, discount, "2027-02-01", "2027-06-30"), null));
    }

    private UUID approvedAssignment(World w, UUID discount) {
        UUID assignment = requestedAssignment(w, discount);
        assertThat(post(ASSIGNMENTS + "/" + assignment + "/approve", w.administrator(), null, "approve-" + assignment)
                .statusCode()).isEqualTo(200);
        return assignment;
    }

    // --- The happy path ---

    @Test
    void aCoordinatorRequestsAnAdministratorApprovesAndLaterRevokesWithTheSameKeyAnsweringTheSameDecision() throws SQLException {
        World w = world();
        UUID discount = discount(w, "CONV-10", "agreement");
        UUID assignment = requestedAssignment(w, discount);

        HttpResponse<String> approved = post(ASSIGNMENTS + "/" + assignment + "/approve", w.administrator(), null, "approve-1");
        HttpResponse<String> replay = post(ASSIGNMENTS + "/" + assignment + "/approve", w.administrator(), null, "approve-1");

        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(HarnessProcess.json(approved).get("status").asString()).isEqualTo("approved");
        assertThat(HarnessProcess.json(approved).get("discountCode").asString()).isEqualTo("CONV-10");
        assertThat(HarnessProcess.json(replay).get("decision").get("id").asString())
                .isEqualTo(HarnessProcess.json(approved).get("decision").get("id").asString());
        assertThat(approved.body()).doesNotContain("approve-1");
        HttpResponse<String> revoked = post(ASSIGNMENTS + "/" + assignment + "/revoke", w.administrator(),
                reasonBody(REASON_TEXT), "revoke-1");
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
        assertThat(HarnessProcess.json(revoked).get("status").asString()).isEqualTo("revoked");
    }

    @Test
    void theRoutesUnderTheDiscountsPathAreNotTakenForAScholarshipIdentifier() throws SQLException {
        World w = world();
        UUID discount = discount(w, "CAMP-10", "campaign");

        HttpResponse<String> list = get(DISCOUNTS + "?kind=campaign&sort=code,asc", w.cashier());
        HttpResponse<String> one = get(DISCOUNTS + "/" + discount, w.cashier());
        HttpResponse<String> assignments = get(ASSIGNMENTS + "?status=pending", w.cashier());

        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        assertThat(HarnessProcess.json(list).get("total").asLong()).isEqualTo(1);
        assertThat(HarnessProcess.json(list).get("items").get(0).get("kind").asString()).isEqualTo("campaign");
        assertThat(HarnessProcess.json(one).get("code").asString()).isEqualTo("CAMP-10");
        assertThat(HarnessProcess.json(one).get("inUse").asBoolean()).isFalse();
        assertThat(assignments.statusCode()).isEqualTo(200);
        assertThat(HarnessProcess.json(assignments).get("total").asLong()).isZero();
    }

    // --- Permissions ---

    @Test
    void aCashierReadsButCannotCreateNorDecideAndNothingChanges() throws SQLException {
        World w = world();
        UUID assignment = requestedAssignment(w, discount(w, "CONV-10", "agreement"));

        assertProblem(post(ASSIGNMENTS + "/" + assignment + "/approve", w.cashier(), null, "k-1"), 403, "forbidden");
        assertProblem(post(ASSIGNMENTS + "/" + assignment + "/reject", w.cashier(), reasonBody(REASON_TEXT), "k-2"), 403,
                "forbidden");
        assertProblem(post(DISCOUNTS, w.cashier(), discountBody(w, "OTRO", "campaign"), null), 403, "forbidden");
        assertProblem(post(ASSIGNMENTS, w.cashier(), assignmentBody(w, UUID.randomUUID(), "2027-02-01", "2027-06-30"), null),
                403, "forbidden");

        assertThat(get(DISCOUNTS, w.cashier()).statusCode()).isEqualTo(200);
        assertThat(get(ASSIGNMENTS, w.cashier()).statusCode()).isEqualTo(200);
        assertThat(HarnessProcess.json(get(ASSIGNMENTS + "/" + assignment, w.administrator())).get("status").asString())
                .isEqualTo("pending");
    }

    @Test
    void aCoordinatorCanRequestButCannotApproveRejectNorRevoke() throws SQLException {
        World w = world();
        UUID discount = discount(w, "CONV-10", "agreement");
        UUID pending = requestedAssignment(w, discount);
        UUID approved = approvedAssignment(w, discount(w, "CONV-20", "agreement"));

        assertProblem(post(ASSIGNMENTS + "/" + pending + "/approve", w.coordinator(), null, "k-1"), 403, "forbidden");
        assertProblem(post(ASSIGNMENTS + "/" + pending + "/reject", w.coordinator(), reasonBody(REASON_TEXT), "k-2"), 403,
                "forbidden");
        assertProblem(post(ASSIGNMENTS + "/" + approved + "/revoke", w.coordinator(), reasonBody(REASON_TEXT), "k-3"), 403,
                "forbidden");
        assertThat(HarnessProcess.json(get(ASSIGNMENTS + "/" + approved, w.administrator())).get("status").asString())
                .isEqualTo("approved");
    }

    @Test
    void whoRequestedTheAssignmentCannotApproveNorRejectItBecauseOfSegregationOfDuties() throws SQLException {
        World w = world();
        UUID discount = discount(w, "CONV-10", "agreement");
        // An administrator may both request and approve: the system refuses that one person does both.
        UUID assignment = idOf(post(ASSIGNMENTS, w.administrator(), assignmentBody(w, discount, "2027-02-01", "2027-06-30"), null));

        assertProblem(post(ASSIGNMENTS + "/" + assignment + "/approve", w.administrator(), null, "self-1"), 403,
                "self-approval-forbidden");
        assertProblem(post(ASSIGNMENTS + "/" + assignment + "/reject", w.administrator(), reasonBody(REASON_TEXT), "self-2"),
                403, "self-approval-forbidden");

        assertThat(HarnessProcess.json(get(ASSIGNMENTS + "/" + assignment, w.administrator())).get("status").asString())
                .isEqualTo("pending");
        assertThat(post(ASSIGNMENTS + "/" + assignment + "/approve", w.superAdmin(), null, "other-1").statusCode())
                .isEqualTo(200);
    }



    // --- S7: the approval of a campaign discount by another person ---

    private static String versionBody(long version) {
        return "{\"version\":" + version + "}";
    }

    private static String rejectBody(long version, String reason) {
        return "{\"version\":" + version + ",\"reason\":\"" + reason + "\"}";
    }

    @Test
    void aCampaignDiscountIsBornPendingAndOnlyAnotherPersonWithTheApprovePermissionApprovesItThroughTheRoute() throws SQLException {
        World w = world();
        HttpResponse<String> created = post(DISCOUNTS, w.administrator(), discountBody(w, "CAMP-10", "campaign"), null);
        UUID discount = idOf(created);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(HarnessProcess.json(created).get("approvalStatus").asString()).isEqualTo("pending");
        assertThat(HarnessProcess.json(created).get("createdBy").asString()).isEqualTo(w.administrator().account().toString());
        assertThat(HarnessProcess.json(created).get("pendingChange")).isNull();
        // The one who created it, and the roles without the permission, do not approve it.
        assertProblem(post(DISCOUNTS + "/" + discount + "/approve", w.administrator(), versionBody(0), "self-1"), 403,
                "self-approval-forbidden");
        assertProblem(post(DISCOUNTS + "/" + discount + "/approve", w.cashier(), versionBody(0), "k-1"), 403, "forbidden");
        assertProblem(post(DISCOUNTS + "/" + discount + "/approve", w.coordinator(), versionBody(0), "k-2"), 403, "forbidden");
        assertProblem(post(DISCOUNTS + "/" + discount + "/reject", w.coordinator(), rejectBody(0, REASON_TEXT), "k-3"), 403,
                "forbidden");
        assertThat(HarnessProcess.json(get(DISCOUNTS + "/" + discount, w.cashier())).get("approvalStatus").asString())
                .isEqualTo("pending");

        HttpResponse<String> approved = post(DISCOUNTS + "/" + discount + "/approve", w.superAdmin(), versionBody(0), "approve-1");
        HttpResponse<String> replay = post(DISCOUNTS + "/" + discount + "/approve", w.superAdmin(), versionBody(0), "approve-1");

        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(HarnessProcess.json(approved).get("approvalStatus").asString()).isEqualTo("approved");
        assertThat(HarnessProcess.json(approved).get("version").asLong()).isEqualTo(1);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(HarnessProcess.json(replay).get("version").asLong()).isEqualTo(1);
        assertThat(approved.body()).doesNotContain("approve-1");
        assertProblem(post(DISCOUNTS + "/" + discount + "/approve", w.superAdmin(), versionBody(1), "approve-2"), 409,
                "invalid-discount-change-state");
    }

    @Test
    void theDecisionOnADiscountNeedsItsKeyItsVersionAndAReasonInTheBoundsAndAStaleVersionIsRefused() throws SQLException {
        World w = world();
        UUID discount = discount(w, "CAMP-10", "campaign");
        String approve = DISCOUNTS + "/" + discount + "/approve";
        String reject = DISCOUNTS + "/" + discount + "/reject";

        assertProblem(post(approve, w.superAdmin(), versionBody(0), null), 400, "idempotency-key-missing");
        assertProblem(post(approve, w.superAdmin(), "{}", "k-1"), 400, "validation-failed");
        assertProblem(post(approve, w.superAdmin(), versionBody(-1), "k-2"), 400, "validation-failed");
        assertProblem(post(approve, w.superAdmin(), versionBody(9), "k-3"), 409, "stale-version");
        assertProblem(post(reject, w.superAdmin(), rejectBody(0, "corto"), "k-4"), 400, "validation-failed");
        assertProblem(post(reject, w.superAdmin(), "{\"reason\":\"" + REASON_TEXT + "\"}", "k-5"), 400, "validation-failed");
        assertThat(HarnessProcess.json(get(DISCOUNTS + "/" + discount, w.cashier())).get("approvalStatus").asString())
                .isEqualTo("pending");
    }

    @Test
    void aRejectedCreationNeverAppliesAndIsNotEditedAndTheReasonStaysOutOfTheResponse() throws SQLException {
        World w = world();
        UUID discount = discount(w, "CAMP-10", "campaign");

        HttpResponse<String> rejected = post(DISCOUNTS + "/" + discount + "/reject", w.superAdmin(), rejectBody(0, REASON_TEXT),
                "reject-1");

        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
        assertThat(HarnessProcess.json(rejected).get("approvalStatus").asString()).isEqualTo("rejected");
        assertThat(rejected.body()).doesNotContain(REASON_TEXT);
        assertProblem(put(DISCOUNTS + "/" + discount, w.administrator(), updateBody(w, 1, "5", "2027-01-01", "2027-12-31")), 409,
                "discount-pending-approval");
        assertProblem(post(DISCOUNTS + "/" + discount + "/deactivate", w.administrator(), null, null), 409,
                "discount-pending-approval");
    }

    @Test
    void wideningAndReactivatingThroughTheRoutesLeaveProposalsThatAnotherPersonDecides() throws SQLException {
        World w = world();
        UUID discount = discount(w, "CAMP-10", "campaign");
        String path = DISCOUNTS + "/" + discount;
        assertThat(post(path + "/approve", w.superAdmin(), versionBody(0), "approve-1").statusCode()).isEqualTo(200);

        HttpResponse<String> widened = put(path, w.administrator(), updateBody(w, 1, "25", "2027-01-01", "2027-12-31"));

        assertThat(widened.statusCode()).as(widened.body()).isEqualTo(200);
        var json = HarnessProcess.json(widened);
        assertThat(json.get("percent").asString()).as("the terms in force").isEqualTo("10.00");
        assertThat(json.get("pendingChange").get("percent").asString()).isEqualTo("25.00");
        assertThat(json.get("pendingChange").get("reactivate").asBoolean()).isFalse();
        assertThat(json.get("pendingChange").get("requestedBy").asString()).isEqualTo(w.administrator().account().toString());
        assertThat(json.get("version").asLong()).isEqualTo(2);
        assertProblem(put(path, w.administrator(), updateBody(w, 2, "30", "2027-01-01", "2027-12-31")), 409,
                "discount-pending-approval");
        assertProblem(post(path + "/approve", w.administrator(), versionBody(2), "self-1"), 403, "self-approval-forbidden");

        HttpResponse<String> dropped = post(path + "/reject", w.superAdmin(), rejectBody(2, REASON_TEXT), "reject-1");
        assertThat(dropped.statusCode()).as(dropped.body()).isEqualTo(200);
        assertThat(HarnessProcess.json(dropped).get("pendingChange")).isNull();
        assertThat(HarnessProcess.json(dropped).get("percent").asString()).isEqualTo("10.00");

        // Deactivating is immediate; reactivating is a proposal.
        HttpResponse<String> off = post(path + "/deactivate", w.administrator(), null, null);
        assertThat(HarnessProcess.json(off).get("active").asBoolean()).isFalse();
        HttpResponse<String> asked = post(path + "/reactivate", w.administrator(), null, null);
        assertThat(HarnessProcess.json(asked).get("active").asBoolean()).isFalse();
        assertThat(HarnessProcess.json(asked).get("pendingChange").get("reactivate").asBoolean()).isTrue();
        long version = HarnessProcess.json(asked).get("version").asLong();
        HttpResponse<String> on = post(path + "/approve", w.superAdmin(), versionBody(version), "approve-2");
        assertThat(HarnessProcess.json(on).get("active").asBoolean()).isTrue();
        assertThat(HarnessProcess.json(on).get("pendingChange")).isNull();
    }

    // --- H2: the approval of an assignment is bound to the terms it was requested for ---

    @Test
    void theAssignmentShowsTheTermsItWasRequestedForAndIsNotApprovedAfterTheDiscountChanged() throws SQLException {
        World w = world();
        UUID discount = discount(w, "CONV-10", "agreement");
        HttpResponse<String> requested = post(ASSIGNMENTS, w.coordinator(), assignmentBody(w, discount, "2027-02-01", "2027-06-30"),
                null);
        UUID assignment = idOf(requested);

        assertThat(HarnessProcess.json(requested).get("discountVersion").asLong()).isZero();
        assertThat(HarnessProcess.json(requested).get("benefitType").asString()).isEqualTo("PERCENTAGE");
        assertThat(HarnessProcess.json(requested).get("percent").asString()).isEqualTo("10.00");
        // The requester raises the discount before anybody approves: the approval would be for something nobody saw.
        assertThat(put(DISCOUNTS + "/" + discount, w.administrator(), updateBody(w, 0, "90", "2027-01-01", "2027-12-31"))
                .statusCode()).isEqualTo(200);

        assertProblem(post(ASSIGNMENTS + "/" + assignment + "/approve", w.superAdmin(), null, "approve-1"), 409,
                "discount-terms-changed");
        HttpResponse<String> after = get(ASSIGNMENTS + "/" + assignment, w.administrator());
        assertThat(HarnessProcess.json(after).get("status").asString()).isEqualTo("pending");
        assertThat(HarnessProcess.json(after).get("percent").asString()).as("what was requested").isEqualTo("10.00");
    }

    // --- A revoked session ---

    @Test
    void aRevokedSessionIsRefusedOnTheWritesAndOnTheReadsOfTheDiscounts() throws SQLException {
        World w = world();
        UUID assignment = requestedAssignment(w, discount(w, "CONV-10", "agreement"));
        assertThat(get(DISCOUNTS, w.administrator()).statusCode()).isEqualTo(200);

        revoke(w.administrator());

        assertProblem(post(ASSIGNMENTS + "/" + assignment + "/approve", w.administrator(), null, "revoked-1"), 401,
                "token-invalid");
        assertProblem(get(ASSIGNMENTS, w.administrator()), 401, "token-invalid");
        assertProblem(get(DISCOUNTS, w.administrator()), 401, "token-invalid");
        assertProblem(post(DISCOUNTS, w.administrator(), discountBody(w, "NUEVO", "campaign"), null), 401, "token-invalid");
        assertThat(send("GET", DISCOUNTS, null, null, null).statusCode()).isEqualTo(401);
        assertThat(scalar(w.institution(), "select count(*) from scholarships_discount_assignment_decision where institution_id = ?",
                w.institution())).as("the refused write left nothing").isZero();
    }

    // --- Validation ---

    @Test
    void aBenefitOutOfItsBoundsAKindOutOfTheClosedSetOrAnInvertedValidityIsRefused() throws SQLException {
        World w = world();

        assertProblem(post(DISCOUNTS, w.administrator(), discountBody(w, "D1", "campaign").replace("\"10\"", "\"0\""), null), 400,
                "validation-failed");
        assertProblem(post(DISCOUNTS, w.administrator(), discountBody(w, "D2", "campaign").replace("\"10\"", "\"100.01\""),
                null), 400, "validation-failed");
        assertProblem(post(DISCOUNTS, w.administrator(), discountBody(w, "D3", "campaign").replace("\"10\"", "\"10.001\""),
                null), 400, "validation-failed");
        assertProblem(post(DISCOUNTS, w.administrator(), discountBody(w, "D4", "other"), null), 400, "validation-failed");
        assertProblem(post(DISCOUNTS, w.administrator(), discountBody(w, "D5", "campaign").replace("\"percent\":\"10\"",
                "\"fixedAmount\":{\"amount\":\"0.00\",\"currency\":\"HNL\"}").replace("PERCENTAGE", "FIXED_AMOUNT"), null), 400,
                "validation-failed");
        assertProblem(post(DISCOUNTS, w.administrator(), discountBody(w, "D6", "campaign")
                .replace("\"validFrom\":\"2027-01-01\",\"validTo\":\"2027-12-31\"",
                        "\"validFrom\":\"2027-12-31\",\"validTo\":\"2027-01-01\""), null), 400, "validation-failed");
        assertProblem(post(DISCOUNTS, w.administrator(), discountBody(w, "D7", "campaign").replace("\"stackable\":true,", ""),
                null), 400, "validation-failed");
        assertProblem(get(DISCOUNTS + "?kind=nope", w.cashier()), 400, "validation-failed");
        assertProblem(get(DISCOUNTS + "?size=201", w.cashier()), 400, "validation-failed");
        assertThat(HarnessProcess.json(get(DISCOUNTS, w.cashier())).get("total").asLong()).isZero();
    }

    @Test
    void aReasonUnderTwentyCharactersOrOverFiveHundredIsRefusedOnRejectAndRevoke() throws SQLException {
        World w = world();
        UUID discount = discount(w, "CONV-10", "agreement");
        UUID pending = requestedAssignment(w, discount);
        UUID approved = approvedAssignment(w, discount(w, "CONV-20", "agreement"));

        assertProblem(post(ASSIGNMENTS + "/" + pending + "/reject", w.administrator(), reasonBody("x".repeat(19)), "k-1"), 400,
                "validation-failed");
        assertProblem(post(ASSIGNMENTS + "/" + pending + "/reject", w.administrator(), reasonBody("x".repeat(501)), "k-2"), 400,
                "validation-failed");
        assertProblem(post(ASSIGNMENTS + "/" + approved + "/revoke", w.administrator(), reasonBody("x".repeat(19)), "k-3"), 400,
                "validation-failed");
        assertProblem(post(ASSIGNMENTS + "/" + approved + "/revoke", w.administrator(), "{}", "k-4"), 400, "validation-failed");
        assertThat(HarnessProcess.json(get(ASSIGNMENTS + "/" + pending, w.administrator())).get("status").asString())
                .isEqualTo("pending");
    }

    @Test
    void aBodyWithAPropertyThatTheRouteDoesNotDeclareIsRefused() throws SQLException {
        World w = world();
        UUID discount = discount(w, "CONV-10", "agreement");
        UUID pending = requestedAssignment(w, discount);

        assertProblem(post(ASSIGNMENTS + "/" + pending + "/reject", w.administrator(),
                "{\"reason\":\"" + REASON_TEXT + "\",\"status\":\"approved\"}", "k-1"), 400, "validation-failed");
        assertProblem(post(ASSIGNMENTS, w.coordinator(), assignmentBody(w, discount, "2027-02-01", "2027-06-30")
                .replace("}", ",\"status\":\"approved\"}"), null), 400, "validation-failed");
        assertProblem(post(DISCOUNTS, w.administrator(), discountBody(w, "OTRO", "campaign").replace("}", ",\"active\":false}"),
                null), 400, "validation-failed");
        assertThat(HarnessProcess.json(get(ASSIGNMENTS + "/" + pending, w.administrator())).get("status").asString())
                .isEqualTo("pending");
    }

    @Test
    void everyDecisionNeedsItsIdempotencyKeyAndTheSameKeyForAnotherRequestIsAMismatch() throws SQLException {
        World w = world();
        UUID discount = discount(w, "CONV-10", "agreement");
        UUID pending = requestedAssignment(w, discount);
        UUID approved = approvedAssignment(w, discount(w, "CONV-20", "agreement"));

        assertProblem(post(ASSIGNMENTS + "/" + pending + "/approve", w.administrator(), null, null), 400,
                "idempotency-key-missing");
        assertProblem(post(ASSIGNMENTS + "/" + pending + "/reject", w.administrator(), reasonBody(REASON_TEXT), null), 400,
                "idempotency-key-missing");
        assertProblem(post(ASSIGNMENTS + "/" + approved + "/revoke", w.administrator(), reasonBody(REASON_TEXT), null), 400,
                "idempotency-key-missing");
        assertThat(post(ASSIGNMENTS + "/" + pending + "/approve", w.administrator(), null, "shared-key").statusCode())
                .isEqualTo(200);
        assertProblem(post(ASSIGNMENTS + "/" + approved + "/revoke", w.administrator(), reasonBody(REASON_TEXT), "shared-key"),
                422, "idempotency-payload-mismatch");
    }

    // --- The rules of the discounts ---

    @Test
    void aDuplicateCodeAndAStaleVersionAreConflicts() throws SQLException {
        World w = world();
        UUID discount = discount(w, "CONV-10", "agreement");

        assertProblem(post(DISCOUNTS, w.administrator(), discountBody(w, "conv-10", "campaign"), null), 409,
                "duplicate-discount-code");
        assertThat(put(DISCOUNTS + "/" + discount, w.administrator(), updateBody(w, 0, "12", "2027-01-01", "2027-12-31"))
                .statusCode()).isEqualTo(200);
        assertProblem(put(DISCOUNTS + "/" + discount, w.administrator(), updateBody(w, 0, "13", "2027-01-01", "2027-12-31")),
                409, "stale-version");
    }

    @Test
    void anAssignmentOfACampaignDiscountOrOutsideItsValidityIsRefusedAndOfAnInactiveOneIsAConflict() throws SQLException {
        World w = world();
        UUID campaign = discount(w, "CAMP-10", "campaign");
        UUID agreement = discount(w, "CONV-10", "agreement");
        UUID inactive = discount(w, "CONV-OFF", "agreement");
        assertThat(post(DISCOUNTS + "/" + inactive + "/deactivate", w.administrator(), null, null).statusCode()).isEqualTo(200);

        assertProblem(post(ASSIGNMENTS, w.coordinator(), assignmentBody(w, campaign, "2027-02-01", "2027-06-30"), null), 400,
                "validation-failed");
        assertProblem(post(ASSIGNMENTS, w.coordinator(), assignmentBody(w, agreement, "2026-12-31", "2027-06-30"), null), 400,
                "validation-failed");
        assertProblem(post(ASSIGNMENTS, w.coordinator(), assignmentBody(w, agreement, "2027-02-01", "2028-01-01"), null), 400,
                "validation-failed");
        assertProblem(post(ASSIGNMENTS, w.coordinator(), assignmentBody(w, inactive, "2027-02-01", "2027-06-30"), null), 409,
                "inactive-reference");
        assertThat(HarnessProcess.json(get(ASSIGNMENTS, w.administrator())).get("total").asLong()).isZero();
    }

    @Test
    void aSecondApprovedAssignmentOfTheSameDiscountForTheSameStudentOverlapsAndIsAConflict() throws SQLException {
        World w = world();
        UUID discount = discount(w, "CONV-10", "agreement");
        approvedAssignment(w, discount);
        UUID second = idOf(post(ASSIGNMENTS, w.coordinator(), assignmentBody(w, discount, "2027-06-30", "2027-09-30"), null));

        assertProblem(post(ASSIGNMENTS + "/" + second + "/approve", w.administrator(), null, "overlap-1"), 409,
                "discount-assignment-overlap");
        assertProblem(post(ASSIGNMENTS + "/" + second + "/revoke", w.administrator(), reasonBody(REASON_TEXT), "state-1"), 409,
                "invalid-discount-assignment-state");
    }

    @Test
    void withAnApprovedAssignmentTheBenefitIsLockedAndTheValidityAndTheConceptsOnlyNarrow() throws SQLException {
        World w = world();
        UUID discount = discount(w, "CONV-10", "agreement");
        approvedAssignment(w, discount);

        assertProblem(put(DISCOUNTS + "/" + discount, w.administrator(), updateBody(w, 0, "11", "2027-01-01", "2027-12-31")),
                409, "discount-benefit-locked");
        assertProblem(put(DISCOUNTS + "/" + discount, w.administrator(), updateBody(w, 0, "10", "2026-12-31", "2027-12-31")),
                409, "discount-scope-locked");
        assertProblem(put(DISCOUNTS + "/" + discount, w.administrator(), updateBody(w, 0, "10", "2027-01-01", "2028-01-01")),
                409, "discount-scope-locked");
        HttpResponse<String> narrowed = put(DISCOUNTS + "/" + discount, w.administrator(),
                updateBody(w, 0, "10", "2027-02-01", "2027-06-30"));
        assertThat(narrowed.statusCode()).as(narrowed.body()).isEqualTo(200);
        assertThat(HarnessProcess.json(narrowed).get("inUse").asBoolean()).isTrue();
        assertProblem(put(DISCOUNTS + "/" + discount, w.administrator(), updateBody(w, 1, "10", "2027-03-01", "2027-06-30")),
                400, "validation-failed");
    }

    // --- Isolation between institutions ---

    @Test
    void anotherInstitutionSeesA404ForEveryDecisionAndForTheDiscountAndTheAssignmentThemselves() throws SQLException {
        World mine = world();
        World theirs = world();
        UUID discount = discount(mine, "CONV-10", "agreement");
        UUID assignment = requestedAssignment(mine, discount);
        UUID approved = approvedAssignment(mine, discount(mine, "CONV-20", "agreement"));

        assertProblem(get(ASSIGNMENTS + "/" + assignment, theirs.administrator()), 404, "resource-not-found");
        assertProblem(post(ASSIGNMENTS + "/" + assignment + "/approve", theirs.administrator(), null, "x-1"), 404,
                "resource-not-found");
        assertProblem(post(ASSIGNMENTS + "/" + assignment + "/reject", theirs.administrator(), reasonBody(REASON_TEXT), "x-2"),
                404, "resource-not-found");
        assertProblem(post(ASSIGNMENTS + "/" + approved + "/revoke", theirs.administrator(), reasonBody(REASON_TEXT), "x-3"),
                404, "resource-not-found");
        assertProblem(get(DISCOUNTS + "/" + discount, theirs.administrator()), 404, "resource-not-found");
        assertProblem(put(DISCOUNTS + "/" + discount, theirs.administrator(),
                updateBody(theirs, 0, "10", "2027-01-01", "2027-12-31")), 404, "resource-not-found");
        assertProblem(post(DISCOUNTS + "/" + discount + "/deactivate", theirs.administrator(), null, null), 404,
                "resource-not-found");
        assertProblem(post(ASSIGNMENTS, theirs.coordinator(), assignmentBody(theirs, discount, "2027-02-01", "2027-06-30"), null),
                404, "resource-not-found");
        assertThat(HarnessProcess.json(get(ASSIGNMENTS, theirs.administrator())).get("total").asLong()).isZero();
        assertThat(HarnessProcess.json(get(DISCOUNTS, theirs.administrator())).get("total").asLong()).isZero();
        assertThat(HarnessProcess.json(get(ASSIGNMENTS + "/" + assignment, mine.administrator())).get("status").asString())
                .isEqualTo("pending");
    }

    // --- Members and revocation ---

    private Member member(UUID institution, StaffRole role) throws SQLException {
        UUID account = UUID.randomUUID();
        StaffRoleFixtures.committed(institution, account, role.code());
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
        return new Member(account, institution, token, family);
    }

    /** Revokes the family of refresh tokens of the session: the access token that names it is refused from then on. */
    private static void revoke(Member member) throws SQLException {
        try (Connection connection = StaffRoleFixtures.ownerTransaction(member.institution());
                PreparedStatement statement = connection.prepareStatement("""
                        update identity_refresh_token_family
                           set revoked_at = now(), revoked_reason = 'password_change'
                         where institution_id = ? and id = ?
                        """)) {
            statement.setObject(1, member.institution());
            statement.setObject(2, member.family());
            assertThat(statement.executeUpdate()).isEqualTo(1);
            connection.commit();
        }
    }

    // --- HTTP ---

    private static HttpResponse<String> get(String path, Member member) {
        return send("GET", path, member.token(), null, null);
    }

    private static HttpResponse<String> post(String path, Member member, String body, String idempotencyKey) {
        return send("POST", path, member.token(), body, idempotencyKey);
    }

    private static HttpResponse<String> put(String path, Member member, String body) {
        return send("PUT", path, member.token(), body, null);
    }

    private static HttpResponse<String> send(String method, String path, String token, String body, String idempotencyKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
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

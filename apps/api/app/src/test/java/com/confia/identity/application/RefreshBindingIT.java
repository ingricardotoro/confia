package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.RefreshOutcome.Rejected;
import com.confia.identity.application.RefreshOutcome.Rotated;
import com.confia.identity.domain.PlainRefreshToken;
import com.confia.identity.domain.RefreshRejection;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.ClientAddress;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The binding of a session to the agent and the address it was opened from
 * (session-endpoints-cookie-and-csrf design.md decision 14; scenarios I121, I190 to I193, I225 and
 * I226), against a real PostgreSQL and the real adapters. A refresh is refused, and the family
 * revoked with {@code binding_changed}, only when <b>both</b> the agent and the address differ from
 * the ones recorded when the family was created; one of them changing, or a family that recorded no
 * agent or no address, still rotates. The address is compared as an address, never as text.
 */
class RefreshBindingIT extends RotationIntegrationTest {

    private static final String NINE = "2026-10-12T09:00:00Z";
    private static final String FIRST_ADDRESS = "203.0.113.9";
    private static final String OTHER_ADDRESS = "198.51.100.7";
    private static final String ACTION_BINDING_CHANGED = "identity.session.binding_changed";
    private static final String ACTION_REUSE_DETECTED = "identity.session.reuse_detected";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Scenario I190: the agent and the address both changed. */
    @Test
    void anAgentAndAnAddressThatBothChangedRefuseTheRefreshAndRevokeTheFamily() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession first = issueSession(NINE).execute(context(),
                commandFrom(ana, false, AGENT_ONE, FIRST_ADDRESS));
        Rotated rotated = (Rotated) rotateFrom("2026-10-12T09:15:00Z",
                first.refreshToken().value(), AGENT_ONE, FIRST_ADDRESS);
        assertThat(tokensOf(first.sessionId()).get(0).get("successor_token_ciphertext"))
                .as("the successor is stored encrypted, inside its grace window").isNotNull();

        RefreshOutcome outcome = rotateFrom("2026-10-12T09:15:05Z",
                rotated.session().refreshToken().value(), AGENT_TWO, OTHER_ADDRESS);

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.BINDING_CHANGED));
        Record family = familyRow(first.sessionId());
        assertThat(family.get("revoked_reason", String.class)).isEqualTo("binding_changed");
        assertThat(instantOf(family, "revoked_at"))
                .isEqualTo(java.time.Instant.parse("2026-10-12T09:15:05Z"));
        assertThat(tokensOf(first.sessionId())).allSatisfy(token -> assertThat(
                token.get("successor_token_ciphertext")).as("the successors are emptied").isNull());
        assertThat(liveTokenCount()).as("no token of the family can be presented").isZero();
        assertThat(auditRows(ACTION_BINDING_CHANGED)).singleElement().satisfies(row -> {
            assertThat(row.entityType()).isEqualTo("identity.refresh_token_family");
            assertThat(row.entityId()).isEqualTo(first.sessionId().toString());
            assertThat(row.actorId()).isEqualTo(ana.value());
            assertThat(row.outcome()).isEqualTo("denied");
            assertThat(JSON.readTree(row.afterValue()))
                    .isEqualTo(JSON.readTree("{\"familyRevoked\":true}"));
            assertThat(everythingIn(row)).as("neither a fingerprint nor an address")
                    .doesNotContain(UserAgentFingerprint.of(AGENT_ONE).value())
                    .doesNotContain(UserAgentFingerprint.of(AGENT_TWO).value())
                    .doesNotContain(FIRST_ADDRESS).doesNotContain(OTHER_ADDRESS);
        });
    }

    /** Scenarios I191 and I121: only the agent, or only the address, changed. */
    @Test
    void ifOnlyTheAgentOrOnlyTheAddressChangedTheTokenStillRotates() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession agentOnly = issueSession(NINE).execute(context(),
                commandFrom(ana, false, AGENT_ONE, FIRST_ADDRESS));
        IssuedSession addressOnly = issueSession(NINE).execute(context(),
                commandFrom(ana, false, AGENT_ONE, FIRST_ADDRESS));

        RefreshOutcome withOtherAgent = rotateFrom("2026-10-12T09:15:00Z",
                agentOnly.refreshToken().value(), AGENT_TWO, FIRST_ADDRESS);
        RefreshOutcome withOtherAddress = rotateFrom("2026-10-12T09:15:00Z",
                addressOnly.refreshToken().value(), AGENT_ONE, OTHER_ADDRESS);

        assertThat(withOtherAgent).isInstanceOf(Rotated.class);
        assertThat(withOtherAddress).isInstanceOf(Rotated.class);
        assertThat(familyRow(agentOnly.sessionId()).get("revoked_at")).isNull();
        assertThat(familyRow(addressOnly.sessionId()).get("revoked_at")).isNull();
        assertThat(auditRows(ACTION_BINDING_CHANGED)).isEmpty();
    }

    /** A request that carries no address cannot show that the address changed. */
    @Test
    void aRequestWithoutAnAddressDoesNotCountAsAChangedAddress() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession first = issueSession(NINE).execute(context(),
                commandFrom(ana, false, AGENT_ONE, FIRST_ADDRESS));

        RefreshOutcome outcome = rotateFrom("2026-10-12T09:15:00Z", first.refreshToken().value(),
                AGENT_TWO, null);

        assertThat(outcome).isInstanceOf(Rotated.class);
    }

    /** Scenario I192: a family opened without an origin has nothing to compare against. */
    @Test
    void aFamilyOpenedWithoutAnAgentOrAnAddressRotatesFromAnywhere() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession noOrigin = issue(NINE, ana);

        RefreshOutcome outcome = rotateFrom("2026-10-12T09:15:00Z", noOrigin.refreshToken().value(),
                AGENT_TWO, OTHER_ADDRESS);

        assertThat(outcome).isInstanceOf(Rotated.class);
        assertThat(familyRow(noOrigin.sessionId()).get("revoked_at")).isNull();
    }

    /** Scenario I192, the other half: an agent was recorded but no address. */
    @Test
    void aFamilyThatRecordedNoAddressIsNotRefusedForAChangedAddress() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession noAddress = issueSession(NINE).execute(context(),
                commandFrom(ana, false, AGENT_ONE, null));

        RefreshOutcome outcome = rotateFrom("2026-10-12T09:15:00Z",
                noAddress.refreshToken().value(), AGENT_TWO, OTHER_ADDRESS);

        assertThat(outcome).isInstanceOf(Rotated.class);
    }

    /** Scenario I193: the revocation is of the family that changed, and of no other. */
    @Test
    void onlyTheFamilyWhoseBindingChangedIsRevoked() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession affected = issueSession(NINE).execute(context(),
                commandFrom(ana, false, AGENT_ONE, FIRST_ADDRESS));
        IssuedSession other = issueSession(NINE).execute(context(),
                commandFrom(ana, false, AGENT_ONE, FIRST_ADDRESS));

        RefreshOutcome outcome = rotateFrom("2026-10-12T09:15:00Z",
                affected.refreshToken().value(), AGENT_TWO, OTHER_ADDRESS);

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.BINDING_CHANGED));
        assertThat(familyRow(affected.sessionId()).get("revoked_reason", String.class))
                .isEqualTo("binding_changed");
        assertThat(familyRow(other.sessionId()).get("revoked_at")).isNull();
        assertThat(rotateFrom("2026-10-12T09:16:00Z", other.refreshToken().value(), AGENT_ONE,
                FIRST_ADDRESS)).as("the other family still rotates").isInstanceOf(Rotated.class);
    }

    /** Scenario I225, the first half: a consumed token outside the grace is a reuse, not a binding. */
    @Test
    void aConsumedTokenOutsideTheGraceIsAReuseEvenIfTheBindingChanged() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamilyFrom(ana, false, "2026-10-12T08:00:00Z", "2026-10-12T08:20:00Z",
                "2026-10-12T20:00:00Z", null, null, FIRST_ADDRESS);
        ConsumedSeed seed = seedConsumedToken(family, "2026-10-12T08:00:00Z",
                "2026-10-12T16:00:00Z", "2026-10-12T08:10:00Z", AGENT_ONE, true);

        RefreshOutcome outcome = rotateFrom("2026-10-12T08:25:00Z", seed.consumed().value(),
                AGENT_TWO, OTHER_ADDRESS);

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        assertThat(familyRow(family).get("revoked_reason", String.class))
                .isEqualTo("reuse_detected");
        assertThat(auditRows(ACTION_REUSE_DETECTED)).hasSize(1);
        assertThat(auditRows(ACTION_BINDING_CHANGED)).isEmpty();
    }

    /** Scenario I225, the second half: the binding is evaluated before the expiry of the token. */
    @Test
    void anExpiredOwnTokenIsABindingChangeWhenTheBindingChanged() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamilyFrom(ana, false, "2026-10-12T08:00:00Z", "2026-10-12T08:20:00Z",
                "2026-10-12T20:00:00Z", null, null, FIRST_ADDRESS);
        PlainRefreshToken expired = seedOpenToken(family, "2026-10-12T08:00:00Z",
                "2026-10-12T08:10:00Z");

        RefreshOutcome outcome = rotateFrom("2026-10-12T08:25:00Z", expired.value(), AGENT_TWO,
                OTHER_ADDRESS);

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.BINDING_CHANGED));
        assertThat(familyRow(family).get("revoked_reason", String.class))
                .isEqualTo("binding_changed");
    }

    /** Scenario I226: a compressed IPv6 address and the same one expanded are the same address. */
    @Test
    void anIpv6AddressWrittenExpandedIsTheSameAddressAsTheCompressedOneStored() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamilyFrom(ana, false, "2026-10-12T08:00:00Z", "2026-10-12T08:20:00Z",
                "2026-10-12T20:00:00Z", null, null, "2001:db8::1");
        PlainRefreshToken token = seedOpenToken(family, "2026-10-12T08:20:00Z",
                "2026-10-12T16:00:00Z");

        RefreshOutcome outcome = rotateFrom("2026-10-12T08:25:00Z", token.value(), AGENT_TWO,
                "2001:0db8:0000:0000:0000:0000:0000:0001");

        assertThat(outcome).isInstanceOf(Rotated.class);
        assertThat(familyRow(family).get("revoked_at")).isNull();
    }

    /** Scenario I226, from the other side: the family was opened from an expanded address. */
    @Test
    void aCompressedIpv6AddressIsTheSameAddressAsTheOneAFamilyWasOpenedFrom() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession first = issueSession(NINE).execute(context(),
                commandFrom(ana, false, AGENT_ONE, "2001:0db8:0000:0000:0000:0000:0000:0001"));

        RefreshOutcome outcome = rotateFrom("2026-10-12T09:15:00Z", first.refreshToken().value(),
                AGENT_TWO, "2001:db8::1");

        assertThat(outcome).isInstanceOf(Rotated.class);
    }

    private RefreshOutcome rotateFrom(String instant, String presentedToken, String userAgent,
            String address) {
        ClientAddress client = address == null ? null : ClientAddress.parseLiteral(address);
        return rotation(fixedClock(instant), new JooqAuditLogWriter(dsl), institutionId)
                .execute(new RotateRefreshTokenCommand(presentedToken, userAgent, client,
                        UUID.randomUUID().toString()));
    }

    private static String everythingIn(AuditRowSnapshot row) {
        return row.toString();
    }
}

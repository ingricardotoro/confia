package com.confia.guardians.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The state machine and the rules of the consent of a guardian (docs/cortes/guardians-consent.md): the state is
 * derived from the latest event, nothing is pre-marked, the transactional purpose and the portal's evidence are
 * refused, the policy version has a shape, a channel needs its contact data, an inactive guardian cannot grant but can
 * always withdraw, and repeating the current action writes no event.
 */
class GuardianConsentsTest {

    private static final Instant T0 = Instant.parse("2026-03-10T15:00:00Z");
    private static final Instant T1 = Instant.parse("2026-03-11T15:00:00Z");
    private static final Instant T2 = Instant.parse("2026-03-12T15:00:00Z");
    private static final UUID STAFF = UUID.randomUUID();

    private static Guardian guardian(String email, String phone, GuardianStatus status) {
        return new Guardian(UUID.randomUUID(), "P-000001", "Ana", "Lopez", false, false, email, phone, null,
                PreferredChannel.NONE, status, T0, T0);
    }

    private static Guardian full() {
        return guardian("ana@example.org", "99998888", GuardianStatus.ACTIVE);
    }

    private static ConsentChange grant(ConsentChannel channel, ConsentPurpose purpose) {
        return new ConsentChange(channel, purpose, ConsentAction.GRANT, EvidenceSource.SIGNED_PAPER_FORM, "2026-01");
    }

    private static ConsentChange withdraw(ConsentChannel channel, ConsentPurpose purpose) {
        return new ConsentChange(channel, purpose, ConsentAction.WITHDRAW, EvidenceSource.ADMIN_REGISTERED, null);
    }

    /** Applies a change the way the use case does: decide against the events so far and append the event, if any. */
    private static Optional<ConsentEvent> apply(Guardian guardian, List<ConsentEvent> events, ConsentChange change,
            Instant now) {
        Optional<ConsentEvent> event = GuardianConsents.of(events).decide(guardian, change, UUID.randomUUID(), STAFF,
                now);
        event.ifPresent(events::add);
        return event;
    }

    // --- Derived state ---

    @Test
    void aGuardianWithoutEventsHasSixCombinationsAllNeverGrantedAndNothingIsPremarked() {
        List<ConsentStanding> matrix = GuardianConsents.of(List.of()).matrix();

        assertThat(matrix).hasSize(6);
        assertThat(matrix).extracting(ConsentStanding::state).containsOnly(ConsentState.NEVER_GRANTED);
        assertThat(matrix).allSatisfy(standing -> {
            assertThat(standing.since()).isNull();
            assertThat(standing.policyVersion()).isNull();
            assertThat(standing.evidenceSource()).isNull();
        });
        assertThat(matrix).extracting(s -> s.channel() + ":" + s.purpose()).containsExactly(
                "EMAIL:INSTITUTIONAL", "EMAIL:MARKETING", "SMS:INSTITUTIONAL", "SMS:MARKETING",
                "WHATSAPP:INSTITUTIONAL", "WHATSAPP:MARKETING");
    }

    @Test
    void grantWithdrawAndGrantAgainLeavesThreeEventsAndTheStateIsTheLatestOne() {
        Guardian guardian = full();
        List<ConsentEvent> events = new ArrayList<>();

        assertThat(apply(guardian, events, grant(ConsentChannel.EMAIL, ConsentPurpose.INSTITUTIONAL), T0)).isPresent();
        assertThat(GuardianConsents.of(events).standing(ConsentChannel.EMAIL, ConsentPurpose.INSTITUTIONAL).state())
                .isEqualTo(ConsentState.GRANTED);
        assertThat(apply(guardian, events, withdraw(ConsentChannel.EMAIL, ConsentPurpose.INSTITUTIONAL), T1))
                .isPresent();
        ConsentStanding withdrawn = GuardianConsents.of(events)
                .standing(ConsentChannel.EMAIL, ConsentPurpose.INSTITUTIONAL);
        assertThat(withdrawn.state()).isEqualTo(ConsentState.WITHDRAWN);
        assertThat(withdrawn.since()).isEqualTo(T1);
        assertThat(withdrawn.policyVersion()).as("a withdrawal carries no version").isNull();
        assertThat(withdrawn.evidenceSource()).isEqualTo(EvidenceSource.ADMIN_REGISTERED);
        assertThat(apply(guardian, events, grant(ConsentChannel.EMAIL, ConsentPurpose.INSTITUTIONAL), T2)).isPresent();

        ConsentStanding granted = GuardianConsents.of(events)
                .standing(ConsentChannel.EMAIL, ConsentPurpose.INSTITUTIONAL);
        assertThat(events).hasSize(3);
        assertThat(granted.state()).isEqualTo(ConsentState.GRANTED);
        assertThat(granted.since()).isEqualTo(T2);
        assertThat(granted.policyVersion()).isEqualTo("2026-01");
        assertThat(granted.evidenceSource()).isEqualTo(EvidenceSource.SIGNED_PAPER_FORM);
    }

    @Test
    void everyCombinationIsIndependentOfTheOthers() {
        Guardian guardian = full();
        List<ConsentEvent> events = new ArrayList<>();
        apply(guardian, events, grant(ConsentChannel.EMAIL, ConsentPurpose.INSTITUTIONAL), T0);
        apply(guardian, events, grant(ConsentChannel.SMS, ConsentPurpose.MARKETING), T0);
        apply(guardian, events, withdraw(ConsentChannel.EMAIL, ConsentPurpose.INSTITUTIONAL), T1);

        GuardianConsents consents = GuardianConsents.of(events);

        assertThat(consents.standing(ConsentChannel.EMAIL, ConsentPurpose.INSTITUTIONAL).state())
                .isEqualTo(ConsentState.WITHDRAWN);
        assertThat(consents.standing(ConsentChannel.SMS, ConsentPurpose.MARKETING).state())
                .isEqualTo(ConsentState.GRANTED);
        assertThat(consents.standing(ConsentChannel.EMAIL, ConsentPurpose.MARKETING).state())
                .isEqualTo(ConsentState.NEVER_GRANTED);
        assertThat(consents.standing(ConsentChannel.SMS, ConsentPurpose.INSTITUTIONAL).state())
                .isEqualTo(ConsentState.NEVER_GRANTED);
        assertThat(consents.standing(ConsentChannel.WHATSAPP, ConsentPurpose.MARKETING).state())
                .isEqualTo(ConsentState.NEVER_GRANTED);
    }

    // --- Idempotency ---

    @Test
    void repeatingTheCurrentActionWritesNoEventWhetherGrantOrWithdraw() {
        Guardian guardian = full();
        List<ConsentEvent> events = new ArrayList<>();
        apply(guardian, events, grant(ConsentChannel.EMAIL, ConsentPurpose.MARKETING), T0);

        assertThat(apply(guardian, events, grant(ConsentChannel.EMAIL, ConsentPurpose.MARKETING), T1)).isEmpty();
        apply(guardian, events, withdraw(ConsentChannel.EMAIL, ConsentPurpose.MARKETING), T1);
        assertThat(apply(guardian, events, withdraw(ConsentChannel.EMAIL, ConsentPurpose.MARKETING), T2)).isEmpty();

        assertThat(events).hasSize(2);
    }

    @Test
    void aGrantUnderAnotherVersionOrAnotherEvidenceIsARealChangeAndAnIdenticalRepeatIsNot() {
        Guardian guardian = full();
        List<ConsentEvent> events = new ArrayList<>();
        apply(guardian, events, grant(ConsentChannel.EMAIL, ConsentPurpose.INSTITUTIONAL), T0);
        EvidenceSource first = events.get(0).evidenceSource();

        Optional<ConsentEvent> newVersion = apply(guardian, events, new ConsentChange(ConsentChannel.EMAIL,
                ConsentPurpose.INSTITUTIONAL, ConsentAction.GRANT, first, "2027-01"), T1);
        Optional<ConsentEvent> identical = apply(guardian, events, new ConsentChange(ConsentChannel.EMAIL,
                ConsentPurpose.INSTITUTIONAL, ConsentAction.GRANT, first, "2027-01"), T2);
        EvidenceSource other = first == EvidenceSource.ADMIN_REGISTERED ? EvidenceSource.SIGNED_PAPER_FORM
                : EvidenceSource.ADMIN_REGISTERED;
        Optional<ConsentEvent> newEvidence = apply(guardian, events, new ConsentChange(ConsentChannel.EMAIL,
                ConsentPurpose.INSTITUTIONAL, ConsentAction.GRANT, other, "2027-01"), T2);

        assertThat(newVersion).isPresent();
        assertThat(identical).isEmpty();
        assertThat(newEvidence).isPresent();
        assertThat(events).hasSize(3);
        ConsentStanding standing = GuardianConsents.of(events).standing(ConsentChannel.EMAIL,
                ConsentPurpose.INSTITUTIONAL);
        assertThat(standing.policyVersion()).isEqualTo("2027-01");
        assertThat(standing.evidenceSource()).isEqualTo(other);
        assertThat(standing.state()).isEqualTo(ConsentState.GRANTED);
    }

    @Test
    void aWithdrawalIgnoresTheEvidenceOfTheRepeat() {
        Guardian guardian = full();
        List<ConsentEvent> events = new ArrayList<>();
        apply(guardian, events, withdraw(ConsentChannel.EMAIL, ConsentPurpose.MARKETING), T0);

        assertThat(apply(guardian, events, new ConsentChange(ConsentChannel.EMAIL, ConsentPurpose.MARKETING,
                ConsentAction.WITHDRAW, EvidenceSource.ADMIN_REGISTERED.equals(events.get(0).evidenceSource())
                        ? EvidenceSource.SIGNED_PAPER_FORM : EvidenceSource.ADMIN_REGISTERED, null), T1)).isEmpty();
    }

    @Test
    void withdrawingACombinationNeverGrantedIsRecordedBecauseItIsARealFact() {
        List<ConsentEvent> events = new ArrayList<>();

        Optional<ConsentEvent> event = apply(full(), events, withdraw(ConsentChannel.WHATSAPP,
                ConsentPurpose.MARKETING), T0);

        assertThat(event).isPresent();
        assertThat(GuardianConsents.of(events).standing(ConsentChannel.WHATSAPP, ConsentPurpose.MARKETING).state())
                .isEqualTo(ConsentState.WITHDRAWN);
    }

    // --- Channel needs contact data ---

    @Test
    void aGrantNeedsTheContactDataOfTheChannelAnEmailForEmailAndAPhoneForSmsAndWhatsapp() {
        Guardian onlyEmail = guardian("ana@example.org", null, GuardianStatus.ACTIVE);
        Guardian onlyPhone = guardian(null, "99998888", GuardianStatus.ACTIVE);
        Guardian nothing = guardian(null, null, GuardianStatus.ACTIVE);

        assertThat(apply(onlyEmail, new ArrayList<>(), grant(ConsentChannel.EMAIL, ConsentPurpose.INSTITUTIONAL), T0))
                .isPresent();
        assertThat(apply(onlyPhone, new ArrayList<>(), grant(ConsentChannel.SMS, ConsentPurpose.INSTITUTIONAL), T0))
                .isPresent();
        assertThat(apply(onlyPhone, new ArrayList<>(), grant(ConsentChannel.WHATSAPP, ConsentPurpose.MARKETING), T0))
                .isPresent();
        for (ConsentChannel channel : List.of(ConsentChannel.SMS, ConsentChannel.WHATSAPP)) {
            assertThatThrownBy(() -> apply(onlyEmail, new ArrayList<>(), grant(channel, ConsentPurpose.INSTITUTIONAL),
                    T0)).isInstanceOf(ConsentChannelUnavailableException.class).extracting("code")
                    .isEqualTo("consent-channel-unavailable");
        }
        assertThatThrownBy(() -> apply(onlyPhone, new ArrayList<>(),
                grant(ConsentChannel.EMAIL, ConsentPurpose.INSTITUTIONAL), T0))
                .isInstanceOf(ConsentChannelUnavailableException.class);
        for (ConsentChannel channel : ConsentChannel.values()) {
            assertThatThrownBy(() -> apply(nothing, new ArrayList<>(), grant(channel, ConsentPurpose.MARKETING), T0))
                    .isInstanceOf(ConsentChannelUnavailableException.class);
        }
    }

    @Test
    void aWithdrawalNeedsNoContactDataAtAll() {
        Guardian nothing = guardian(null, null, GuardianStatus.ACTIVE);
        List<ConsentEvent> events = new ArrayList<>();

        for (ConsentChannel channel : ConsentChannel.values()) {
            assertThat(apply(nothing, events, withdraw(channel, ConsentPurpose.MARKETING), T0)).isPresent();
        }
        assertThat(events).hasSize(3);
    }

    // --- Inactive guardian ---

    @Test
    void anInactiveGuardianCannotGrantEvenWhenTheStateWouldNotChange() {
        Guardian inactive = guardian("ana@example.org", "99998888", GuardianStatus.INACTIVE);

        assertThatThrownBy(() -> apply(inactive, new ArrayList<>(),
                grant(ConsentChannel.EMAIL, ConsentPurpose.INSTITUTIONAL), T0))
                .isInstanceOf(GuardianNotActiveException.class).extracting("code").isEqualTo("guardian-not-active");
    }

    @Test
    void anInactiveGuardianCanAlwaysWithdrawAndTheWithdrawalIsImmediate() {
        Guardian active = full();
        List<ConsentEvent> events = new ArrayList<>();
        apply(active, events, grant(ConsentChannel.EMAIL, ConsentPurpose.INSTITUTIONAL), T0);
        Guardian inactive = new Guardian(active.id(), active.guardianCode(), active.firstName(), active.lastName(),
                false, false, active.email(), active.phone(), null, PreferredChannel.NONE, GuardianStatus.INACTIVE, T0,
                T1);

        Optional<ConsentEvent> event = apply(inactive, events, withdraw(ConsentChannel.EMAIL,
                ConsentPurpose.INSTITUTIONAL), T1);

        assertThat(event).isPresent();
        assertThat(GuardianConsents.of(events).standing(ConsentChannel.EMAIL, ConsentPurpose.INSTITUTIONAL).state())
                .isEqualTo(ConsentState.WITHDRAWN);
    }

    @Test
    void theInactiveCheckComesBeforeTheChannelCheck() {
        Guardian inactiveWithoutData = guardian(null, null, GuardianStatus.INACTIVE);

        assertThatThrownBy(() -> apply(inactiveWithoutData, new ArrayList<>(),
                grant(ConsentChannel.EMAIL, ConsentPurpose.INSTITUTIONAL), T0))
                .isInstanceOf(GuardianNotActiveException.class);
    }

    // --- The event ---

    @Test
    void theEventCarriesTheGuardianTheCombinationTheEvidenceTheTimeAndTheStaffAccountAndNothingElse() {
        Guardian guardian = full();
        UUID eventId = UUID.randomUUID();

        ConsentEvent event = GuardianConsents.of(List.of())
                .decide(guardian, grant(ConsentChannel.SMS, ConsentPurpose.MARKETING), eventId, STAFF, T1).orElseThrow();

        assertThat(event.id()).isEqualTo(eventId);
        assertThat(event.guardianId()).isEqualTo(guardian.id());
        assertThat(event.channel()).isEqualTo(ConsentChannel.SMS);
        assertThat(event.purpose()).isEqualTo(ConsentPurpose.MARKETING);
        assertThat(event.action()).isEqualTo(ConsentAction.GRANT);
        assertThat(event.evidenceSource()).isEqualTo(EvidenceSource.SIGNED_PAPER_FORM);
        assertThat(event.policyVersion()).isEqualTo("2026-01");
        assertThat(event.occurredAt()).isEqualTo(T1);
        assertThat(event.recordedBy()).isEqualTo(STAFF);
        assertThat(event.toString()).doesNotContain("ana@example.org").doesNotContain("99998888");
    }

    @Test
    void anEventKeepsTheVersionExactlyWhenItIsAGrant() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new ConsentEvent(id, id, ConsentChannel.EMAIL, ConsentPurpose.MARKETING,
                ConsentAction.GRANT, EvidenceSource.ADMIN_REGISTERED, null, T0, STAFF))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConsentEvent(id, id, ConsentChannel.EMAIL, ConsentPurpose.MARKETING,
                ConsentAction.WITHDRAW, EvidenceSource.ADMIN_REGISTERED, "2026-01", T0, STAFF))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- The request: closed sets and the version ---

    @Test
    void theTransactionalPurposeIsNotConsentableWhateverItsSpelling() {
        for (String spelling : List.of("TRANSACTIONAL", "transactional", " Transactional ")) {
            assertThatThrownBy(() -> ConsentChange.parse("EMAIL", spelling, "GRANT", "ADMIN_REGISTERED", "2026-01"))
                    .isInstanceOfSatisfying(InvalidConsentException.class, e -> {
                        assertThat(e.field()).isEqualTo("purpose");
                        assertThat(e.reason()).isEqualTo("not-consentable");
                        assertThat(e.code()).isEqualTo("validation-failed");
                    });
        }
        assertThatThrownBy(() -> ConsentChange.parse("EMAIL", "TRANSACTIONAL", "WITHDRAW", "ADMIN_REGISTERED", null))
                .isInstanceOf(InvalidConsentException.class);
    }

    @Test
    void thePortalFormIsRefusedToTheStaffButTheOtherTwoAreAccepted() {
        assertThatThrownBy(() -> ConsentChange.parse("EMAIL", "MARKETING", "GRANT", "PORTAL_FORM", "2026-01"))
                .isInstanceOfSatisfying(InvalidConsentException.class, e -> {
                    assertThat(e.field()).isEqualTo("evidenceSource");
                    assertThat(e.reason()).isEqualTo("not-allowed");
                });
        assertThat(ConsentChange.parse("EMAIL", "MARKETING", "GRANT", "SIGNED_PAPER_FORM", "2026-01").evidenceSource())
                .isEqualTo(EvidenceSource.SIGNED_PAPER_FORM);
        assertThat(ConsentChange.parse("EMAIL", "MARKETING", "GRANT", "ADMIN_REGISTERED", "2026-01").evidenceSource())
                .isEqualTo(EvidenceSource.ADMIN_REGISTERED);
    }

    @Test
    void unknownOrMissingMembersAreNamedWithTheirFieldAndNeverWithTheValue() {
        assertInvalid(() -> ConsentChange.parse(null, "MARKETING", "GRANT", "ADMIN_REGISTERED", "v1"), "channel",
                "required");
        assertInvalid(() -> ConsentChange.parse("FAX", "MARKETING", "GRANT", "ADMIN_REGISTERED", "v1"), "channel",
                "invalid");
        assertInvalid(() -> ConsentChange.parse("email", "MARKETING", "GRANT", "ADMIN_REGISTERED", "v1"), "channel",
                "invalid");
        assertInvalid(() -> ConsentChange.parse("EMAIL", null, "GRANT", "ADMIN_REGISTERED", "v1"), "purpose",
                "required");
        assertInvalid(() -> ConsentChange.parse("EMAIL", "SPAM", "GRANT", "ADMIN_REGISTERED", "v1"), "purpose",
                "invalid");
        assertInvalid(() -> ConsentChange.parse("EMAIL", "MARKETING", null, "ADMIN_REGISTERED", "v1"), "action",
                "required");
        assertInvalid(() -> ConsentChange.parse("EMAIL", "MARKETING", "REVOKE", "ADMIN_REGISTERED", "v1"), "action",
                "invalid");
        assertInvalid(() -> ConsentChange.parse("EMAIL", "MARKETING", "GRANT", null, "v1"), "evidenceSource",
                "required");
        assertInvalid(() -> ConsentChange.parse("EMAIL", "MARKETING", "GRANT", "ORAL", "v1"), "evidenceSource",
                "invalid");
    }

    private static void assertInvalid(Runnable call, String field, String reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(InvalidConsentException.class, e -> {
            assertThat(e.field()).isEqualTo(field);
            assertThat(e.reason()).isEqualTo(reason);
            assertThat(e.getMessage()).doesNotContain("FAX").doesNotContain("SPAM").doesNotContain("ORAL");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-01", "v1", "A", "1.0.0", "policy_2026.rev-3", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "0", "A.b_C-9"})
    void aPolicyVersionOfTheDocumentedShapeIsAccepted(String version) {
        assertThat(ConsentChange.parse("EMAIL", "MARKETING", "GRANT", "ADMIN_REGISTERED", version).policyVersion())
                .isEqualTo(version);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "2026 01", "2026/01", "2026-01 ", " 2026-01", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "versión", "v1\n",
            "v1;drop", "<b>", "2026+01"})
    void aPolicyVersionOfAnotherShapeIsRefusedAsInvalid(String version) {
        assertInvalid(() -> ConsentChange.parse("EMAIL", "MARKETING", "GRANT", "ADMIN_REGISTERED", version),
                "policyVersion", "invalid");
    }

    @ParameterizedTest
    @NullSource
    void aGrantWithoutAPolicyVersionIsRequiredToHaveOne(String version) {
        assertInvalid(() -> ConsentChange.parse("EMAIL", "MARKETING", "GRANT", "ADMIN_REGISTERED", version),
                "policyVersion", "required");
    }

    @Test
    void aWithdrawalOmitsTheVersionAndOneThatIsSentIsRefused() {
        assertThat(ConsentChange.parse("EMAIL", "MARKETING", "WITHDRAW", "ADMIN_REGISTERED", null).policyVersion())
                .isNull();
        assertInvalid(() -> ConsentChange.parse("EMAIL", "MARKETING", "WITHDRAW", "ADMIN_REGISTERED", "2026-01"),
                "policyVersion", "not-allowed");
    }
}

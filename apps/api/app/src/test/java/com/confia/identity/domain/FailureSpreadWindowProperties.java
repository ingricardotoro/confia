package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Properties of {@link FailureSpreadWindow} (session-endpoints-cookie-and-csrf design.md decision
 * 12): inside one window the signal is raised exactly once, on the call that brings the fifth
 * distinct identifier, and never when fewer than five distinct identifiers failed, whatever the
 * order and the repetitions.
 */
class FailureSpreadWindowProperties {

    private static final Instant TEN = Instant.parse("2026-10-12T10:00:00Z");

    @Property(tries = 500)
    void insideOneWindowTheSignalIsRaisedExactlyOnceOnTheFifthDistinctIdentifier(
            @ForAll("failures") List<Integer> identifiers) {
        FailureSpreadWindow window = new FailureSpreadWindow();
        Set<Integer> seen = new HashSet<>();

        for (int call = 0; call < identifiers.size(); call++) {
            boolean newIdentifier = seen.add(identifiers.get(call));
            boolean signalled = window.record(
                    FailureSpreadWindowTest.fingerprint(identifiers.get(call)), TEN.plusSeconds(call));

            assertThat(signalled)
                    .isEqualTo(newIdentifier && seen.size() == FailureSpreadWindow.THRESHOLD);
        }
    }

    @Property(tries = 300)
    void fewerThanFiveDistinctIdentifiersNeverSignalWhateverTheRepetitions(
            @ForAll("fewIdentifiers") List<Integer> identifiers) {
        FailureSpreadWindow window = new FailureSpreadWindow();

        for (int call = 0; call < identifiers.size(); call++) {
            assertThat(window.record(FailureSpreadWindowTest.fingerprint(identifiers.get(call)),
                    TEN.plusSeconds(call))).isFalse();
        }
    }

    @Property(tries = 300)
    void identifiersSpacedAtLeastTenMinutesApartNeverSignal(
            @ForAll("failures") List<Integer> identifiers) {
        FailureSpreadWindow window = new FailureSpreadWindow();

        for (int call = 0; call < identifiers.size(); call++) {
            assertThat(window.record(FailureSpreadWindowTest.fingerprint(call),
                    TEN.plus(Duration.ofMinutes(10L * call)))).isFalse();
        }
    }

    @Provide
    Arbitrary<List<Integer>> failures() {
        return Arbitraries.integers().between(0, 9).list().ofMinSize(1).ofMaxSize(40);
    }

    @Provide
    Arbitrary<List<Integer>> fewIdentifiers() {
        return Arbitraries.integers().between(0, 3).list().ofMinSize(1).ofMaxSize(40);
    }
}

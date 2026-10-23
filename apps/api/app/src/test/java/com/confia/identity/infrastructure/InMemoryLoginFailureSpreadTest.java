package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.shared.security.ClientAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link InMemoryLoginFailureSpread} (session-endpoints-cookie-and-csrf design.md decision 12): one
 * window per client key of the rate limiter, a bounded map, and a signal that is never a control, so
 * a full table records nothing and signals nothing.
 */
class InMemoryLoginFailureSpreadTest {

    private static final Instant TEN = Instant.parse("2026-10-12T10:00:00Z");
    private static final ClientAddress CLIENT = ClientAddress.parseLiteral("203.0.113.9");

    @Test
    void theFifthDistinctIdentifierFromOneAddressSignalsAndTheOthersDoNot() {
        InMemoryLoginFailureSpread spread = new InMemoryLoginFailureSpread(10);

        for (int identifier = 1; identifier <= 4; identifier++) {
            assertThat(spread.recordFailure(CLIENT, fingerprint(identifier), TEN)).isFalse();
        }
        assertThat(spread.recordFailure(CLIENT, fingerprint(5), TEN)).isTrue();
        assertThat(spread.recordFailure(CLIENT, fingerprint(6), TEN)).isFalse();
    }

    @Test
    void failuresFromDifferentAddressesNeverAddUp() {
        InMemoryLoginFailureSpread spread = new InMemoryLoginFailureSpread(10);

        for (int identifier = 1; identifier <= 5; identifier++) {
            ClientAddress other = ClientAddress.parseLiteral("198.51.100." + identifier);
            assertThat(spread.recordFailure(other, fingerprint(identifier), TEN)).isFalse();
        }
    }

    @Test
    void twoIpv6AddressesOfTheSameSixtyFourBitPrefixShareOneWindow() {
        InMemoryLoginFailureSpread spread = new InMemoryLoginFailureSpread(10);

        for (int identifier = 1; identifier <= 4; identifier++) {
            spread.recordFailure(ClientAddress.parseLiteral("2001:db8:0:1::" + identifier),
                    fingerprint(identifier), TEN);
        }

        assertThat(spread.recordFailure(ClientAddress.parseLiteral("2001:db8:0:1:ffff::9"),
                fingerprint(5), TEN)).as("the key of the rate limiter is the /64 prefix").isTrue();
    }

    @Test
    void aFullTableRecordsNothingAndSignalsNothingForANewAddress() {
        InMemoryLoginFailureSpread spread = new InMemoryLoginFailureSpread(1);
        spread.recordFailure(ClientAddress.parseLiteral("198.51.100.1"), fingerprint(1), TEN);
        ClientAddress newcomer = ClientAddress.parseLiteral("198.51.100.2");

        for (int identifier = 1; identifier <= 6; identifier++) {
            assertThat(spread.recordFailure(newcomer, fingerprint(identifier), TEN)).isFalse();
        }
        assertThat(spread.trackedClients()).isEqualTo(1);
    }

    @Test
    void aFullTableStillCountsTheAddressesItAlreadyTracks() {
        InMemoryLoginFailureSpread spread = new InMemoryLoginFailureSpread(1);
        for (int identifier = 1; identifier <= 4; identifier++) {
            spread.recordFailure(CLIENT, fingerprint(identifier), TEN);
        }

        assertThat(spread.recordFailure(CLIENT, fingerprint(5), TEN)).isTrue();
    }

    @Test
    void aWindowWithNothingLiveIsEvictedToMakeRoomForANewAddress() {
        InMemoryLoginFailureSpread spread = new InMemoryLoginFailureSpread(1);
        spread.recordFailure(ClientAddress.parseLiteral("198.51.100.1"), fingerprint(1), TEN);
        Instant later = TEN.plus(Duration.ofMinutes(10));

        for (int identifier = 1; identifier <= 4; identifier++) {
            spread.recordFailure(CLIENT, fingerprint(identifier), later);
        }

        assertThat(spread.recordFailure(CLIENT, fingerprint(5), later)).isTrue();
        assertThat(spread.trackedClients()).isEqualTo(1);
    }

    @Test
    void aWindowWithALiveFailureIsNeverEvicted() {
        InMemoryLoginFailureSpread spread = new InMemoryLoginFailureSpread(1);
        ClientAddress holder = ClientAddress.parseLiteral("198.51.100.1");
        spread.recordFailure(holder, fingerprint(1), TEN);
        Instant almost = TEN.plus(Duration.ofMinutes(10)).minusNanos(1);

        assertThat(spread.recordFailure(CLIENT, fingerprint(1), almost)).isFalse();
        assertThat(spread.trackedClients()).isEqualTo(1);
    }

    @Test
    void theBoundMustBePositive() {
        assertThatThrownBy(() -> new InMemoryLoginFailureSpread(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxClients");
        assertThatThrownBy(() -> new InMemoryLoginFailureSpread(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullArguments() {
        InMemoryLoginFailureSpread spread = new InMemoryLoginFailureSpread(10);

        assertThatThrownBy(() -> spread.recordFailure(null, fingerprint(1), TEN))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("client");
        assertThatThrownBy(() -> spread.recordFailure(CLIENT, null, TEN))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("identifier");
        assertThatThrownBy(() -> spread.recordFailure(CLIENT, fingerprint(1), null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("now");
    }

    @Test
    void fiveThreadsRecordingFiveIdentifiersRaiseExactlyOneSignal() throws Exception {
        InMemoryLoginFailureSpread spread = new InMemoryLoginFailureSpread(10);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int identifier = 1; identifier <= 5; identifier++) {
                IdentifierFingerprint fingerprint = fingerprint(identifier);
                results.add(pool.submit(() -> {
                    start.await();
                    return spread.recordFailure(CLIENT, fingerprint, TEN);
                }));
            }
            start.countDown();
            int signals = 0;
            for (Future<Boolean> result : results) {
                if (result.get(20, TimeUnit.SECONDS)) {
                    signals++;
                }
            }

            assertThat(signals).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static IdentifierFingerprint fingerprint(int number) {
        return new IdentifierFingerprint(String.format("%064x", number));
    }
}

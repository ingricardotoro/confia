package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.security.RateLimitDecision.Admitted;
import com.confia.shared.security.RateLimitDecision.Limited;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The limiter counts an account key exactly as it counts an address: the same window and the same refusal, in a
 * table of its own, and the address path is the same code as before (it only derives the key first).
 */
class InMemoryRateLimiterAccountKeyTest {

    private static final RateLimitPolicy TWO_A_MINUTE = new RateLimitPolicy(2, Duration.ofMinutes(1), 10,
            Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofHours(1), 100);
    private static final ClientAddress CLIENT = ClientAddress.parseLiteral("203.0.113.9");

    @Test
    void anAccountIsAdmittedUpToTheLimitThenLimitedWithAPositiveWaitAndAnotherAccountIsNotAffected() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(TWO_A_MINUTE);
        ClientKey busy = ClientKey.ofAccount(UUID.randomUUID());
        ClientKey quiet = ClientKey.ofAccount(UUID.randomUUID());

        assertThat(limiter.tryAcquire(busy)).isInstanceOf(Admitted.class);
        assertThat(limiter.tryAcquire(busy)).isInstanceOf(Admitted.class);
        assertThat(limiter.tryAcquire(busy)).isInstanceOfSatisfying(Limited.class,
                limited -> assertThat(limited.retryAfterSeconds()).isBetween(1L, 60L));
        assertThat(limiter.tryAcquire(quiet)).isInstanceOf(Admitted.class);
    }

    @Test
    void theAddressPathStillCountsTheSameWayAndSharesTheTableWithTheKeyOfThatAddress() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(TWO_A_MINUTE);

        assertThat(limiter.tryAcquire(CLIENT)).isInstanceOf(Admitted.class);
        assertThat(limiter.tryAcquire(CLIENT.rateLimitKey())).isInstanceOf(Admitted.class);
        assertThat(limiter.tryAcquire(CLIENT)).isInstanceOf(Limited.class);
        assertThat(limiter.size()).isEqualTo(1);
    }

    @Test
    void anAccountAndAnAddressNeverCollideInTheSameTable() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(TWO_A_MINUTE);

        limiter.tryAcquire(CLIENT);
        limiter.tryAcquire(CLIENT);
        assertThat(limiter.tryAcquire(CLIENT)).isInstanceOf(Limited.class);

        assertThat(limiter.tryAcquire(ClientKey.ofAccount(UUID.randomUUID()))).isInstanceOf(Admitted.class);
        assertThat(limiter.size()).isEqualTo(2);
    }
}

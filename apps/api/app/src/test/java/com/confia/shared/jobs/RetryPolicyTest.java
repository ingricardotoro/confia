package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The bounded retry policy of a one-time job type (background-jobs-with-db-scheduler design.md
 * decision 9; scenario BJ22): 1 to 5 attempts, an exponential wait with a cap, and an empty answer
 * once the attempts are spent.
 */
class RetryPolicyTest {

    private static final RetryPolicy PASSWORD_RESET =
            new RetryPolicy(5, Duration.ofSeconds(30), 2.0, Duration.ofMinutes(5));

    @Test
    void thePasswordResetPolicyWaitsExactlyThirtySixtyOneTwentyAndTwoFortySeconds() {
        assertThat(PASSWORD_RESET.delayAfterFailure(1)).contains(Duration.ofSeconds(30));
        assertThat(PASSWORD_RESET.delayAfterFailure(2)).contains(Duration.ofSeconds(60));
        assertThat(PASSWORD_RESET.delayAfterFailure(3)).contains(Duration.ofSeconds(120));
        assertThat(PASSWORD_RESET.delayAfterFailure(4)).contains(Duration.ofSeconds(240));
    }

    @Test
    void theFifthFailureExhaustsTheAttempts() {
        assertThat(PASSWORD_RESET.delayAfterFailure(5)).isEqualTo(Optional.empty());
        assertThat(PASSWORD_RESET.delayAfterFailure(6)).isEqualTo(Optional.empty());
    }

    @Test
    void theWaitNeverExceedsTheCapAndEqualsItOnceReached() {
        RetryPolicy capped = new RetryPolicy(5, Duration.ofSeconds(30), 2.0, Duration.ofSeconds(100));

        assertThat(capped.delayAfterFailure(1)).contains(Duration.ofSeconds(30));
        assertThat(capped.delayAfterFailure(2)).contains(Duration.ofSeconds(60));
        assertThat(capped.delayAfterFailure(3)).contains(Duration.ofSeconds(100));
        assertThat(capped.delayAfterFailure(4)).contains(Duration.ofSeconds(100));
    }

    @Test
    void aSingleAttemptPolicyExhaustsAtTheFirstFailure() {
        RetryPolicy once = new RetryPolicy(1, Duration.ofSeconds(1), 1.0, Duration.ofSeconds(1));

        assertThat(once.delayAfterFailure(1)).isEmpty();
    }

    @Test
    void aFactorOfOneKeepsTheWaitConstant() {
        RetryPolicy constant = new RetryPolicy(3, Duration.ofSeconds(10), 1.0, Duration.ofSeconds(10));

        assertThat(constant.delayAfterFailure(1)).contains(Duration.ofSeconds(10));
        assertThat(constant.delayAfterFailure(2)).contains(Duration.ofSeconds(10));
    }

    @Test
    void aHugeFactorCapsInsteadOfOverflowing() {
        RetryPolicy huge = new RetryPolicy(5, Duration.ofSeconds(30), 1.0e12, Duration.ofMinutes(5));

        assertThat(huge.delayAfterFailure(4)).contains(Duration.ofMinutes(5));
    }

    @Test
    void thePolicyAcceptsTheBoundsOfItsRanges() {
        assertThat(new RetryPolicy(1, Duration.ofMillis(1), 1.0, Duration.ofMillis(1)).maxAttempts())
                .isEqualTo(1);
        assertThat(new RetryPolicy(5, Duration.ofMillis(1), 1.0, Duration.ofMillis(1)).maxAttempts())
                .isEqualTo(5);
    }

    @Test
    void rejectsAnAttemptCountOutsideOneToFive() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(9)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxAttempts");
        assertThatThrownBy(() -> new RetryPolicy(6, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(9)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxAttempts");
    }

    @Test
    void rejectsAZeroOrNegativeFirstDelay() {
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ZERO, 2.0, Duration.ofSeconds(9)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("firstDelay");
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(-1), 2.0, Duration.ofSeconds(9)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("firstDelay");
    }

    @Test
    void rejectsAFactorBelowOneAndANonFiniteFactor() {
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(1), 0.99, Duration.ofSeconds(9)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("multiplier");
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(1), Double.NaN, Duration.ofSeconds(9)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("multiplier");
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(1), Double.POSITIVE_INFINITY,
                Duration.ofSeconds(9))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multiplier");
    }

    @Test
    void rejectsACapBelowTheFirstDelay() {
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(10), 2.0, Duration.ofSeconds(9)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxDelay");
    }

    @Test
    void rejectsAFailureCountBelowOne() {
        assertThatThrownBy(() -> PASSWORD_RESET.delayAfterFailure(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("consecutiveFailures");
    }

    @Test
    void rejectsNullDurations() {
        assertThatThrownBy(() -> new RetryPolicy(3, null, 2.0, Duration.ofSeconds(9)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(1), 2.0, null))
                .isInstanceOf(NullPointerException.class);
    }
}

package com.confia.shared.web.ratelimit;

import com.confia.shared.security.ClientAddress;
import com.confia.shared.security.RateLimitDecision;
import com.confia.shared.security.RateLimitDecision.Admitted;
import com.confia.shared.security.RateLimitDecision.CapacityExhausted;
import com.confia.shared.security.RateLimitDecision.Limited;
import com.confia.shared.security.RateLimiter;
import com.confia.shared.security.RequestOrigin;
import com.confia.shared.web.problem.CapacityExceededException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Asks the limiter of a {@link RateLimited} method before the controller runs (web-edge-foundations
 * design.md, decision 17). It acts in {@code preHandle} and nowhere else: a refusal is an
 * exception thrown here, so the controller and everything behind it never execute and the one
 * translator of exceptions writes the answer. A request that is over its limit becomes {@link
 * TooManyRequestsException} ({@code 429}); one the limiter cannot take, {@link
 * CapacityExceededException} ({@code 503}) and a signal to {@link RateLimitMetrics} that names the
 * {@link CapacityReason}.
 *
 * <p><b>It fails closed.</b> Everything that is not a clear admission is a refusal as lack of
 * capacity: a request with no origin or no address to key on, a policy the registry does not hold,
 * and any unexpected failure of the limiter. None of them is ever an admission, and none of them
 * tells the client which it was; the operator learns it from the reason of the signal. A failing
 * metrics adapter changes nothing of that: the answer stays {@code 503}.
 *
 * <p><b>One evaluation per request.</b> Only the {@code REQUEST} dispatch is evaluated. The {@code
 * ASYNC} dispatch of the same request passes, because the limiter was already asked for it and
 * asking twice would count one request twice (and could refuse an answer already computed). That is
 * why {@link RateLimited} forbids asynchronous handlers until each has its own test.
 *
 * <p><b>What it logs.</b> An unexpected failure, of the limiter or of the metrics adapter, is
 * written at {@code ERROR} with the class of the exception and the policy, and nothing else: an
 * exception message may carry an address or a key, and a stack trace carries the message. It writes
 * at most one such line per second, so a flood of failures costs one line a second.
 */
public final class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private static final long LOG_INTERVAL_NANOS = 1_000_000_000L;

    /** A decision with the cause of a refusal as lack of capacity, which only that case has. */
    private record Verdict(RateLimitDecision decision, CapacityReason reason) {

        static Verdict of(RateLimitDecision decision) {
            CapacityReason reason =
                    decision instanceof CapacityExhausted ? CapacityReason.TABLE_FULL : null;
            return new Verdict(decision, reason);
        }

        static Verdict refusal(CapacityReason reason) {
            return new Verdict(new CapacityExhausted(), reason);
        }
    }

    private final RateLimiterRegistry limiters;
    private final RateLimitMetrics metrics;
    private final LongSupplier monotonicNanos;
    // One bound per message: a failing limiter and a failing metrics adapter are different defects,
    // and the line of one must never hide the line of the other in the same second.
    private final AtomicReference<Long> lastLimiterFailureAt = new AtomicReference<>();
    private final AtomicReference<Long> lastMetricsFailureAt = new AtomicReference<>();

    /** The interceptor of production: the bound of the log is read from {@link System#nanoTime()}. */
    public RateLimitInterceptor(RateLimiterRegistry limiters, RateLimitMetrics metrics) {
        this(limiters, metrics, System::nanoTime);
    }

    /** For tests, which move the source by hand. */
    RateLimitInterceptor(RateLimiterRegistry limiters, RateLimitMetrics metrics,
            LongSupplier monotonicNanos) {
        this.limiters = limiters;
        this.metrics = metrics;
        this.monotonicNanos = monotonicNanos;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) {
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RateLimited limited = method.getMethodAnnotation(RateLimited.class);
        if (limited == null) {
            return true;
        }
        String policy = limited.policy();
        Verdict verdict = decide(policy);
        return switch (verdict.decision()) {
            case Admitted admitted -> true;
            case Limited wait -> throw new TooManyRequestsException(wait.retryAfterSeconds());
            case CapacityExhausted exhausted -> {
                signal(policy, verdict.reason());
                throw new CapacityExceededException();
            }
        };
    }

    /** The limiter's decision, or a refusal with its cause for anything that prevents one. */
    private Verdict decide(String policy) {
        try {
            Optional<RateLimiter> limiter = limiters.find(policy);
            if (limiter.isEmpty()) {
                return Verdict.refusal(CapacityReason.UNKNOWN_POLICY);
            }
            Optional<RequestOrigin> origin = RequestOrigin.current();
            if (origin.isEmpty()) {
                return Verdict.refusal(CapacityReason.NO_ORIGIN);
            }
            ClientAddress client = origin.get().clientAddress();
            if (client == null) {
                return Verdict.refusal(CapacityReason.NO_ADDRESS);
            }
            return Verdict.of(limiter.get().tryAcquire(client));
        } catch (RuntimeException unexpected) {
            logFailure("rate limiter failed unexpectedly", policy, unexpected,
                    lastLimiterFailureAt);
            return Verdict.refusal(CapacityReason.LIMITER_FAILURE);
        }
    }

    /** Reports the refusal; a failing adapter must never turn a {@code 503} into a {@code 500}. */
    private void signal(String policy, CapacityReason reason) {
        try {
            metrics.capacityExhausted(policy, reason);
        } catch (RuntimeException failing) {
            logFailure("rate limit metrics failed", policy, failing, lastMetricsFailureAt);
        }
    }

    private void logFailure(String message, String policy, RuntimeException failure,
            AtomicReference<Long> lastLoggedAt) {
        if (!mayLog(lastLoggedAt)) {
            return;
        }
        LOG.atError().addKeyValue("policy", policy)
                .addKeyValue("exception", failure.getClass().getName()).log(message);
    }

    /** At most one line per interval; only the difference of two readings is meaningful. */
    private boolean mayLog(AtomicReference<Long> lastLoggedAt) {
        long now = monotonicNanos.getAsLong();
        Long previous = lastLoggedAt.get();
        if (previous != null && now - previous < LOG_INTERVAL_NANOS) {
            return false;
        }
        return lastLoggedAt.compareAndSet(previous, now);
    }
}

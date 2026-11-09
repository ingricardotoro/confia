package com.confia.shared.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.kernel.Permission;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The interim adapter of the authorization signal (rbac-permission-matrix-and-audit-integration design.md
 * D11): one fixed {@code WARN} event with its {@code event} field and the number of signals held back,
 * at most one a second, with a monotonic source the test moves by hand.
 */
class LogAuthorizationMetricsTest {

    private static final long SECOND = 1_000_000_000L;

    private final Logger logger = (Logger) LoggerFactory.getLogger(LogAuthorizationMetrics.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final AtomicLong nanos = new AtomicLong(5 * SECOND);
    private final LogAuthorizationMetrics metrics = new LogAuthorizationMetrics(nanos::get);

    @BeforeEach
    void capture() {
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
    }

    @Test
    void theFirstSignalWritesOneWarnEventWithItsNameAndNothingElse() {
        metrics.resolutionFailed();

        assertThat(logged.list).hasSize(1);
        ILoggingEvent event = logged.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key + "=" + pair.value)
                .containsExactly("event=authorization_resolution_failed", "suppressed=0");
    }

    @Test
    void signalsWithinTheSameSecondAreHeldBackAndCountedInTheNextEvent() {
        metrics.resolutionFailed();
        nanos.addAndGet(SECOND / 2);
        metrics.resolutionFailed();
        metrics.resolutionFailed();
        assertThat(logged.list).hasSize(1);

        nanos.addAndGet(SECOND);
        metrics.resolutionFailed();

        assertThat(logged.list).hasSize(2);
        assertThat(logged.list.get(1).getKeyValuePairs()).extracting(pair -> pair.value)
                .containsExactly("authorization_resolution_failed", 2L);
    }

    @Test
    void denialOmittedCarriesThePermissionCodeAndIsHeldBackPerPermission() {
        metrics.denialOmitted(Permission.IDENTITY_READ);
        metrics.denialOmitted(Permission.IDENTITY_READ);
        metrics.denialOmitted(Permission.IDENTITY_UPDATE);

        assertThat(logged.list).hasSize(2);
        assertThat(pairs(0)).containsExactly(
                "event=authorization_denial_omitted", "permission=identity:read", "suppressed=0");
        assertThat(pairs(1)).containsExactly(
                "event=authorization_denial_omitted", "permission=identity:update", "suppressed=0");

        nanos.addAndGet(SECOND);
        metrics.denialOmitted(Permission.IDENTITY_READ);

        assertThat(logged.list).hasSize(3);
        assertThat(pairs(2)).containsExactly(
                "event=authorization_denial_omitted", "permission=identity:read", "suppressed=1");
    }

    @Test
    void eachEventHasItsOwnBoundAndNoneHidesAnother() {
        metrics.denialAuditFailed();
        metrics.denialTrackerFull();
        metrics.resolutionFailed();
        metrics.denialOmitted(Permission.IDENTITY_READ);
        metrics.denialAuditFailed();

        assertThat(logged.list).hasSize(4);
        assertThat(logged.list).extracting(event -> event.getKeyValuePairs().get(0).value).containsExactly(
                "authorization_denial_audit_failed", "authorization_denial_tracker_full",
                "authorization_resolution_failed", "authorization_denial_omitted");
        nanos.addAndGet(SECOND);
        metrics.denialAuditFailed();
        assertThat(pairs(4)).containsExactly("event=authorization_denial_audit_failed", "suppressed=1");
    }

    @Test
    void anEventNeverCarriesAnEmailAnAddressOrARoute() {
        metrics.denialAuditFailed();
        metrics.denialTrackerFull();
        metrics.resolutionFailed();
        metrics.denialOmitted(Permission.IDENTITY_READ);

        assertThat(logged.list).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getArgumentArray()).isNull();
            assertThat(event.getKeyValuePairs()).extracting(pair -> pair.key)
                    .isSubsetOf("event", "permission", "suppressed");
            assertThat(event.getFormattedMessage()).doesNotContain("@", "/", ":");
        });
    }

    @Test
    void theStateIsBoundedByTheClosedCatalogOfPermissions() {
        for (Permission permission : Permission.values()) {
            metrics.denialOmitted(permission);
        }
        metrics.denialAuditFailed();
        metrics.denialTrackerFull();
        metrics.resolutionFailed();

        assertThat(metrics.trackedKeys()).isEqualTo(Permission.values().length + 3);
        for (Permission permission : Permission.values()) {
            metrics.denialOmitted(permission);
        }
        assertThat(metrics.trackedKeys()).isEqualTo(Permission.values().length + 3);
    }

    private List<String> pairs(int event) {
        return logged.list.get(event).getKeyValuePairs().stream()
                .map(pair -> pair.key + "=" + pair.value).collect(Collectors.toList());
    }
}

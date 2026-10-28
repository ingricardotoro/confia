package com.confia.bootstrap;

import static com.confia.bootstrap.BootFailureAssertions.assertNoSecretFragment;
import static com.confia.bootstrap.BootFailureAssertions.stackTraceOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import com.confia.identity.application.AuthenticateWithPassword;
import com.confia.identity.application.ConsumeRecoveryCode;
import com.confia.identity.application.DescribeCurrentSession;
import com.confia.identity.application.LogIn;
import com.confia.identity.application.LoginFailureSpread;
import com.confia.identity.application.EnrollTotpSecondFactor;
import com.confia.identity.application.IssuePasswordResetToken;
import com.confia.identity.application.PasswordResetIssuanceScheduler;
import com.confia.identity.application.LoginInstitutionProvider;
import com.confia.identity.application.RequestPasswordReset;
import com.confia.identity.application.ResetPasswordWithToken;
import com.confia.identity.infrastructure.JobPasswordResetIssuanceScheduler;
import com.confia.identity.application.VerifyTotpCode;
import com.confia.identity.web.CurrentSessionController;
import com.confia.identity.web.LoginController;
import com.confia.kernel.InstitutionId;
import com.zaxxer.hikari.HikariDataSource;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.security.IdempotentExecutor;
import com.confia.shared.security.TransactionRunner;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Specs/build-integrity of web-edge-foundations: the administrative process registers its
 * production data access (decision 2) and its identity use cases (decision 3), starts with a
 * database nothing listens on, and never runs a migration at startup; the worker (see {@link
 * WorkerProductionWiringTest}) and the portal (see {@link PortalContextStartupTest}) each have a
 * narrow data access of their own since background-jobs-with-db-scheduler. Every context is started through the production launcher with
 * {@link TestProcessArguments}, so no container is needed and this stays a {@code *Test}.
 */
class AdminProductionWiringTest {

    @Test
    void theAdminContextHoldsOneBeanOfEachProductionWiringType() {
        ConfigurableApplicationContext context = start("admin");
        try {
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
            assertThat(context.getBeansOfType(DSLContext.class)).hasSize(1);
            assertThat(context.getBeansOfType(TransactionRunner.class)).hasSize(1);
            assertThat(context.getBeansOfType(Clock.class)).hasSize(1);
            assertThat(context.getBeansOfType(AuditLogWriter.class))
                    .as("exactly one audit writer, so a later decorator cannot create a second")
                    .hasSize(1);
            assertThat(context.getBeansOfType(IdempotentExecutor.class)).hasSize(1);
            assertThat(context.getBeansOfType(ColumnEncryptionService.class)).hasSize(1);
        } finally {
            context.close();
        }
    }

    @Test
    void theAdminContextHoldsTheFiveIdentityUseCasesWhosePortsHaveAProductionAdapter() {
        ConfigurableApplicationContext context = start("admin");
        try {
            assertThat(context.getBeansOfType(AuthenticateWithPassword.class)).hasSize(1);
            assertThat(context.getBeansOfType(VerifyTotpCode.class)).hasSize(1);
            assertThat(context.getBeansOfType(ConsumeRecoveryCode.class)).hasSize(1);
            assertThat(context.getBeansOfType(EnrollTotpSecondFactor.class)).hasSize(1);
            assertThat(context.getBeansOfType(ResetPasswordWithToken.class)).hasSize(1);
        } finally {
            context.close();
        }
    }

    /**
     * The two use cases that need the password reset scheduler and the link sender, which have no
     * production adapter until changes 9 and 14, are not registered: registering them would take a
     * fake adapter or a lambda (decision 3). The check looks at bean definitions as well as at
     * instances ({@code getBeanNamesForType} with eager initialization allowed), so a lazy or
     * prototype definition cannot hide.
     */
    /**
     * BI21 (task 5.2): the administrative process holds the controller of the current session and
     * its use case, each exactly once, and the web configuration registers the controller (the
     * controller is not registered from the infrastructure configuration of identity).
     */
    @Test
    void theAdminContextHoldsTheCurrentSessionControllerAndItsUseCase() {
        ConfigurableApplicationContext context = start("admin");
        try {
            assertThat(context.getBeansOfType(DescribeCurrentSession.class)).hasSize(1);
            assertThat(context.getBeansOfType(CurrentSessionController.class)).hasSize(1);
            assertThat(context.getBeanFactory().getBeanDefinition("currentSessionController").getResourceDescription())
                    .as("registered by the web configuration of identity")
                    .contains("IdentityWebConfiguration");
        } finally {
            context.close();
        }
    }

    /**
     * The administrative process holds the login use case, its signal of failures spread and its
     * controller, each exactly once, and the controller is registered by the web configuration of
     * identity (session-endpoints-cookie-and-csrf design.md decision 11). The portal and the worker
     * hold none of them ({@link #portalAndWorkerHoldNoIdentityUseCase}).
     */
    @Test
    void theAdminContextHoldsTheLoginUseCaseItsSignalAndItsController() {
        ConfigurableApplicationContext context = start("admin");
        try {
            assertThat(context.getBeansOfType(LogIn.class)).hasSize(1);
            assertThat(context.getBeansOfType(LoginFailureSpread.class)).hasSize(1);
            assertThat(context.getBeansOfType(LoginController.class)).hasSize(1);
            assertThat(context.getBeanFactory().getBeanDefinition("loginController")
                    .getResourceDescription()).contains("IdentityWebConfiguration");
        } finally {
            context.close();
        }
    }

    @Test
    void theTwoPasswordResetIssuingUseCasesAreNotBeans() {
        ConfigurableApplicationContext context = start("admin");
        try {
            assertThat(context.getBeansOfType(ResetPasswordWithToken.class))
                    .as("non-vacuous: the sibling use case that does have its adapters is a bean")
                    .hasSize(1);
            assertThat(context.getBeanNamesForType(RequestPasswordReset.class, true, true))
                    .as("RequestPasswordReset waits for PasswordResetIssuanceScheduler")
                    .isEmpty();
            assertThat(context.getBeanNamesForType(IssuePasswordResetToken.class, true, true))
                    .as("IssuePasswordResetToken waits for PasswordResetLinkSender")
                    .isEmpty();
        } finally {
            context.close();
        }
    }

    /** Decision 18: the administrative process schedules the issuance through the job adapter. */
    @Test
    void theIssuanceSchedulingPortIsTheJobAdapterInTheAdministrativeProcess() {
        ConfigurableApplicationContext context = start("admin");
        try {
            assertThat(context.getBeansOfType(PasswordResetIssuanceScheduler.class).values())
                    .singleElement().isInstanceOf(JobPasswordResetIssuanceScheduler.class);
        } finally {
            context.close();
        }
    }

    @Test
    void theLoginInstitutionComesFromTheConfiguredProperty() {
        UUID institution = UUID.randomUUID();
        ConfigurableApplicationContext context = start("admin",
                TestProcessArguments.adminWith(TestProcessArguments.INSTITUTION_PROPERTY,
                        institution.toString()));
        try {
            assertThat(context.getBean(LoginInstitutionProvider.class).loginInstitutionId())
                    .isEqualTo(new InstitutionId(institution));
        } finally {
            context.close();
        }
    }

    @Test
    void theAdminPoolIsLazyAndHasNotConnectedAfterStartup() throws SQLException {
        ConfigurableApplicationContext context = start("admin");
        try {
            HikariDataSource pool = context.getBean(DataSource.class).unwrap(HikariDataSource.class);
            assertThat(pool.getHikariPoolMXBean())
                    .as("a pool that has started would have a MXBean: the start must be lazy")
                    .isNull();
        } finally {
            context.close();
        }
    }

    @Test
    void theAdminContextRunsNoMigrationAtStartup() {
        ConfigurableApplicationContext context = start("admin");
        try {
            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
            assertThat(context.getBeansOfType(FlywayMigrationInitializer.class)).isEmpty();
        } finally {
            context.close();
        }
    }

    @Test
    void theAdminContextUsesUtcAsItsClock() {
        ConfigurableApplicationContext context = start("admin");
        try {
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        } finally {
            context.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"portal", "worker"})
    void portalAndWorkerHoldNoIdentityUseCase(String process) {
        ConfigurableApplicationContext context = start(process);
        try {
            assertThat(context.getBeansOfType(AuthenticateWithPassword.class)).isEmpty();
            assertThat(context.getBeansOfType(ResetPasswordWithToken.class)).isEmpty();
            assertThat(context.getBeansOfType(LoginInstitutionProvider.class)).isEmpty();
            assertThat(context.getBeansOfType(DescribeCurrentSession.class)).isEmpty();
            assertThat(context.getBeansOfType(LogIn.class)).isEmpty();
            assertThat(context.getBeansOfType(LoginController.class)).isEmpty();
            assertThat(context.getBeanNamesForType(CurrentSessionController.class, true, true))
                    .isEmpty();
        } finally {
            context.close();
        }
    }

    @Test
    void aMissingMasterKeyStopsTheAdminProcessAndNamesThePropertyWithoutAnyValue() {
        assertThatThrownBy(() -> ConfiaApplication.launch(
                TestProcessArguments.adminWithoutMasterKey(), "admin"))
                .satisfies(failure -> {
                    assertThat(stackTraceOf(failure)).contains(TestProcessArguments.MASTER_KEY_PROPERTY);
                    assertThat(stackTraceOf(failure)).contains("IllegalStateException");
                    assertNoSecretFragment(failure, "");
                });
    }

    @Test
    void anInvalidMasterKeyStopsTheAdminProcessWithoutEchoingIt() {
        String notBase64 = "Zq9#Xw7!Lm2@Pk4$";

        assertThatThrownBy(() -> ConfiaApplication.launch(
                TestProcessArguments.adminWithMasterKey(notBase64), "admin"))
                .satisfies(failure -> {
                    assertThat(stackTraceOf(failure))
                            .contains(TestProcessArguments.MASTER_KEY_PROPERTY);
                    assertNoSecretFragment(failure, notBase64);
                });
    }

    private static ConfigurableApplicationContext start(String process) {
        return start(process, TestProcessArguments.forProcess(process));
    }

    private static ConfigurableApplicationContext start(String process, String[] arguments) {
        LaunchOutcome outcome = ConfiaApplication.launch(arguments, process);
        assertThat(outcome.context()).as("the %s process must start", process).isNotNull();
        return outcome.context();
    }
}

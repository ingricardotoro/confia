package com.confia.identity.infrastructure.wiring;

import com.confia.identity.application.IdentityRetentionPurges;
import com.confia.identity.application.IssuePasswordResetToken;
import com.confia.identity.application.PasswordResetIssuanceJob;
import com.confia.identity.application.PasswordResetIssuanceJobHandler;
import com.confia.identity.application.PasswordResetIssuanceJobs;
import com.confia.identity.application.PasswordResetTokenRepository;
import com.confia.identity.application.PurgeExpiredPasswordResetTokens;
import com.confia.identity.application.StaffAccountRepository;
import com.confia.identity.infrastructure.JooqIdentityRetentionPurges;
import com.confia.identity.infrastructure.JooqPasswordResetTokenRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.identity.infrastructure.UnavailablePasswordResetLinkSender;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.jobs.JobCalendar;
import com.confia.shared.jobs.JobDefinition;
import com.confia.shared.jobs.OneTimeJob;
import com.confia.shared.jobs.RecurringJob;
import com.confia.shared.observability.events.OperationalEventLog;
import com.confia.shared.security.TransactionRunner;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalTime;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The registration of the {@code identity} module for the worker process (background-jobs-with-db-scheduler
 * design.md decisions 17 and 18). It declares what the password-reset issuance and the daily purge of
 * expired reset tokens need and nothing else: the two repositories, the link sender that fails explicitly, a source of
 * randomness, the use case, the handler and the job of the issuance, and the purge adapter, the purge
 * use case and its recurring job. It has no column-encryption master key, no Argon2id pepper and no
 * request origin; the worker has none of them, and none of these needs one.
 *
 * <p>Like {@link IdentityConfiguration}, it lives in the {@code infrastructure.wiring} package
 * because its factory methods take a {@code DSLContext} (ADR-0015 rule 4), and it is imported
 * explicitly by {@code WorkerApplication}, with no component scan (ADR-0024). The administrative
 * process never loads it.
 */
@Configuration(proxyBeanMethods = false)
public class IdentityWorkerConfiguration {

    @Bean
    StaffAccountRepository staffAccountRepository(DSLContext dsl) {
        return new JooqStaffAccountRepository(dsl);
    }

    @Bean
    PasswordResetTokenRepository passwordResetTokenRepository(DSLContext dsl) {
        return new JooqPasswordResetTokenRepository(dsl);
    }

    /** The factory returns the adapter's own type, so no method of production returns the port. */
    @Bean
    UnavailablePasswordResetLinkSender passwordResetLinkSender() {
        return new UnavailablePasswordResetLinkSender();
    }

    @Bean
    SecureRandom passwordResetSecureRandom() {
        return new SecureRandom();
    }

    @Bean
    IssuePasswordResetToken issuePasswordResetToken(TransactionRunner runner,
            StaffAccountRepository accounts, PasswordResetTokenRepository tokens,
            UnavailablePasswordResetLinkSender linkSender, AuditLogWriter auditLogWriter,
            SecureRandom secureRandom, Clock clock) {
        return new IssuePasswordResetToken(runner, accounts, tokens, linkSender, auditLogWriter,
                secureRandom, clock);
    }

    @Bean
    PasswordResetIssuanceJobHandler passwordResetIssuanceJobHandler(IssuePasswordResetToken issuance) {
        return new PasswordResetIssuanceJobHandler(issuance);
    }

    @Bean
    JobDefinition passwordResetIssuanceJob(PasswordResetIssuanceJobHandler handler) {
        return new OneTimeJob<PasswordResetIssuanceJob>(PasswordResetIssuanceJobs.TYPE, handler);
    }

    @Bean
    IdentityRetentionPurges identityRetentionPurges(DSLContext dsl) {
        return new JooqIdentityRetentionPurges(dsl);
    }

    @Bean
    PurgeExpiredPasswordResetTokens purgeExpiredPasswordResetTokens(TransactionRunner runner,
            IdentityRetentionPurges purges, AuditLogWriter auditLogWriter, Clock clock) {
        return new PurgeExpiredPasswordResetTokens(runner, purges, auditLogWriter, new OperationalEventLog(),
                clock);
    }

    /** Every day at 04:30 in Tegucigalpa, for each configured institution. */
    @Bean
    JobDefinition passwordResetTokenPurgeJob(PurgeExpiredPasswordResetTokens purge) {
        return new RecurringJob(PurgeExpiredPasswordResetTokens.TASK_NAME,
                new JobCalendar.DailyAt(LocalTime.of(4, 30)), purge);
    }
}

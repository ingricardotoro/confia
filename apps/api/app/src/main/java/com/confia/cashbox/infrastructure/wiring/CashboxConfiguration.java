package com.confia.cashbox.infrastructure.wiring;

import com.confia.cashbox.application.ApproveClose;
import com.confia.cashbox.application.CashApprovalRepository;
import com.confia.cashbox.application.CashCloseRepository;
import com.confia.cashbox.application.CashMovementRepository;
import com.confia.cashbox.application.CashSessionRepository;
import com.confia.cashbox.application.CloseSession;
import com.confia.cashbox.application.GetCurrentSession;
import com.confia.cashbox.application.GetSession;
import com.confia.cashbox.application.ListSessions;
import com.confia.cashbox.application.OpenSession;
import com.confia.cashbox.application.RecordMovement;
import com.confia.cashbox.application.SessionDetails;
import com.confia.cashbox.application.SessionScope;
import com.confia.cashbox.infrastructure.FullReadSessionScope;
import com.confia.cashbox.infrastructure.JooqCashApprovalRepository;
import com.confia.cashbox.infrastructure.JooqCashCloseRepository;
import com.confia.cashbox.infrastructure.JooqCashMovementRepository;
import com.confia.cashbox.infrastructure.JooqCashSessionRepository;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of {@code cashbox} for the administrative process (docs/cortes/cashbox-sessions.md; ADR-0024): the
 * jOOQ adapters of the sessions, their movements, closes and approvals, and the use cases over them. The controller is
 * registered by {@code CashboxWebConfiguration}. It needs a {@link DSLContext}, the transactional component, the audit writer
 * and a clock, which only the administrative process has.
 */
@Configuration(proxyBeanMethods = false)
public class CashboxConfiguration {

    @Bean
    CashSessionRepository cashSessionRepository(DSLContext dsl) {
        return new JooqCashSessionRepository(dsl);
    }

    @Bean
    CashMovementRepository cashMovementRepository(DSLContext dsl) {
        return new JooqCashMovementRepository(dsl);
    }

    @Bean
    CashCloseRepository cashCloseRepository(DSLContext dsl) {
        return new JooqCashCloseRepository(dsl);
    }

    @Bean
    CashApprovalRepository cashApprovalRepository(DSLContext dsl) {
        return new JooqCashApprovalRepository(dsl);
    }

    /** How far a read reaches (a cashier only its own session): see {@link FullReadSessionScope} for what is pending. */
    @Bean
    SessionScope cashSessionScope() {
        return new FullReadSessionScope();
    }

    @Bean
    SessionDetails cashSessionDetails(CashMovementRepository movements, CashCloseRepository closes,
            CashApprovalRepository approvals) {
        return new SessionDetails(movements, closes, approvals);
    }

    @Bean
    OpenSession openSession(TransactionRunner transactionRunner, CashSessionRepository sessions, SessionDetails details,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new OpenSession(transactionRunner, sessions, details, auditLogWriter, clock);
    }

    @Bean
    RecordMovement recordMovement(TransactionRunner transactionRunner, CashSessionRepository sessions,
            CashMovementRepository movements, AuditLogWriter auditLogWriter, Clock clock) {
        return new RecordMovement(transactionRunner, sessions, movements, auditLogWriter, clock);
    }

    @Bean
    CloseSession closeSession(TransactionRunner transactionRunner, CashSessionRepository sessions,
            CashMovementRepository movements, CashCloseRepository closes, SessionDetails details,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new CloseSession(transactionRunner, sessions, movements, closes, details, auditLogWriter, clock);
    }

    @Bean
    ApproveClose approveClose(TransactionRunner transactionRunner, CashSessionRepository sessions,
            CashApprovalRepository approvals, SessionDetails details, AuditLogWriter auditLogWriter, Clock clock) {
        return new ApproveClose(transactionRunner, sessions, approvals, details, auditLogWriter, clock);
    }

    @Bean
    ListSessions listSessions(TransactionRunner transactionRunner, CashSessionRepository sessions,
            CashCloseRepository closes, CashApprovalRepository approvals, SessionScope scope) {
        return new ListSessions(transactionRunner, sessions, closes, approvals, scope);
    }

    @Bean
    GetSession getSession(TransactionRunner transactionRunner, CashSessionRepository sessions, SessionDetails details,
            SessionScope scope) {
        return new GetSession(transactionRunner, sessions, details, scope);
    }

    @Bean
    GetCurrentSession getCurrentSession(TransactionRunner transactionRunner, CashSessionRepository sessions,
            SessionDetails details) {
        return new GetCurrentSession(transactionRunner, sessions, details);
    }
}

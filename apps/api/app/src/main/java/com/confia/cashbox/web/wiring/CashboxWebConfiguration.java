package com.confia.cashbox.web.wiring;

import com.confia.cashbox.application.ApproveClose;
import com.confia.cashbox.application.CloseSession;
import com.confia.cashbox.application.GetCurrentSession;
import com.confia.cashbox.application.GetSession;
import com.confia.cashbox.application.ListSessions;
import com.confia.cashbox.application.OpenSession;
import com.confia.cashbox.application.RecordMovement;
import com.confia.cashbox.web.CashboxController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of the web layer of {@code cashbox} for the administrative process (ADR-0024): it registers the
 * controller of the cash sessions and nothing else. The use cases it takes come from {@code CashboxConfiguration}; the
 * controller is registered here and not there because a controller is a class of the {@code web} layer, and an {@code
 * infrastructure} package must not depend on it.
 *
 * <p>The portal and the worker never load it: the isolation tests of the process beans fail if a controller of this module
 * reaches either context.
 */
@Configuration(proxyBeanMethods = false)
public class CashboxWebConfiguration {

    @Bean
    CashboxController cashboxController(OpenSession openSession, RecordMovement recordMovement,
            CloseSession closeSession, ApproveClose approveClose, ListSessions listSessions, GetSession getSession,
            GetCurrentSession getCurrentSession) {
        return new CashboxController(openSession, recordMovement, closeSession, approveClose, listSessions, getSession,
                getCurrentSession);
    }
}

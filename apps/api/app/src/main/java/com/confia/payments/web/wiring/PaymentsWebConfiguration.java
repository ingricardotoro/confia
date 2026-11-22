package com.confia.payments.web.wiring;

import com.confia.payments.application.ConfirmPayment;
import com.confia.payments.application.GetPayment;
import com.confia.payments.application.ListPayments;
import com.confia.payments.application.ManagePostingAccounts;
import com.confia.payments.application.RegisterPayment;
import com.confia.payments.application.RejectPayment;
import com.confia.payments.web.PaymentController;
import com.confia.payments.web.PaymentPostingAccountController;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of the web layer of {@code payments} for the administrative process (ADR-0024): it registers the
 * controllers of the payments and of their posting map and nothing else. The use cases it takes come from {@code
 * PaymentsConfiguration}; the controllers are registered here and not there because a controller is a class of the {@code web}
 * layer, and an {@code infrastructure} package must not depend on it.
 *
 * <p>The portal and the worker never load it: the isolation tests of the process beans fail if a controller of this module
 * reaches either context.
 */
@Configuration(proxyBeanMethods = false)
public class PaymentsWebConfiguration {

    @Bean
    PaymentController paymentController(RegisterPayment registerPayment, ConfirmPayment confirmPayment,
            RejectPayment rejectPayment, ListPayments listPayments, GetPayment getPayment) {
        return new PaymentController(registerPayment, confirmPayment, rejectPayment, listPayments, getPayment);
    }

    @Bean
    PaymentPostingAccountController paymentPostingAccountController(
            @Qualifier("managePaymentsPostingAccounts") ManagePostingAccounts postingAccounts) {
        return new PaymentPostingAccountController(postingAccounts);
    }
}

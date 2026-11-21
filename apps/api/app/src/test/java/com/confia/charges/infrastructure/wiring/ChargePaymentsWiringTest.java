package com.confia.charges.infrastructure.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.charges.application.VoidCharge;
import com.confia.charges.application.ListStudentCharges;
import com.confia.charges.application.api.ChargePayments;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The use cases that must know what is paid on a charge take {@link ChargePayments} as a REQUIRED bean: a process that loads the
 * charges without {@code payments} fails at startup, and there is no "no payments" constant in production code to fall back on
 * (a void of a charge with payments would otherwise pass silently).
 */
class ChargePaymentsWiringTest {

    @Test
    void theVoidAndTheListOfChargesRequireThePortThatPaymentsPublishes() {
        for (Class<?> produced : new Class<?>[] {VoidCharge.class, ListStudentCharges.class}) {
            Method factory = Arrays.stream(ChargesConfiguration.class.getDeclaredMethods())
                    .filter(method -> method.getReturnType() == produced).findFirst().orElseThrow();
            assertThat(Arrays.stream(factory.getParameterTypes()))
                    .as(produced.getSimpleName() + " requires the port itself, not an optional provider")
                    .contains(ChargePayments.class);
        }
    }

    @Test
    void theProductionPortHasNoSilentNoPaymentsImplementation() {
        assertThat(Arrays.stream(ChargePayments.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
                .doesNotContain("NONE");
    }
}

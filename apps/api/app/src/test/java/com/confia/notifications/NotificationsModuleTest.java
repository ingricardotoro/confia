package com.confia.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.notifications.application.api.EmailDeliveryReceipt;
import com.confia.notifications.application.api.SendTransactionalEmail;
import com.confia.notifications.application.api.TransactionalEmail;
import com.confia.shared.security.SecurityContext;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.NamedInterface;

/**
 * Scenario NT02 and the shape of the module (transactional-email-adapter design.md decision 1,
 * ADR-0020, ADR-0022): the one sending operation takes a template of the closed list, a recipient
 * and the template parameters, never a subject or a body; the public surface is the named interface
 * {@code api}.
 */
class NotificationsModuleTest {

    @Test
    void theOnlySendingOperationReceivesAContextAndATemplatedMessage() {
        List<Method> operations = Arrays.stream(SendTransactionalEmail.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers())).toList();

        assertThat(operations).hasSize(1);
        Method send = operations.get(0);
        assertThat(send.getName()).isEqualTo("send");
        assertThat(send.getParameterTypes())
                .containsExactly(SecurityContext.class, TransactionalEmail.class);
        assertThat(send.getReturnType()).isEqualTo(EmailDeliveryReceipt.class);
    }

    @Test
    void aMessageIsExactlyATemplateARecipientAndTheParametersOfThatTemplate() {
        assertThat(Arrays.stream(TransactionalEmail.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("template", "recipient", "parameters");
    }

    @Test
    void thePublicSurfaceOfTheModuleIsTheNamedInterfaceApi() {
        NamedInterface api = SendTransactionalEmail.class.getPackage()
                .getAnnotation(NamedInterface.class);

        assertThat(api).as("@NamedInterface on notifications.application.api").isNotNull();
        assertThat(api.value()).containsExactly("api");
    }

    @Test
    void theModuleHasNoWebPackage() {
        assertThat(getClass().getClassLoader().getResource("com/confia/notifications/web"))
                .as("ADR-0020: notifications has no web layer").isNull();
    }
}

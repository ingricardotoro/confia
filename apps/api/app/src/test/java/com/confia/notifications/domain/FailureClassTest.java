package com.confia.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The closed list of failure classes (specs/notifications/spec.md, conventions). Its codes are the
 * values the {@code failure_class} column of {@code notifications_email_delivery} will accept
 * (V16), so the list is pinned here: adding or renaming one is a change of the contract.
 */
class FailureClassTest {

    @Test
    void theClosedListIsExactlyTheSixDocumentedClassesInOrder() {
        assertThat(Arrays.stream(FailureClass.values()).map(FailureClass::code))
                .containsExactly("unavailable", "timeout", "tls", "authentication", "rejected",
                        "unknown");
    }

    @Test
    void everyCodeIsLowerCaseAsciiLettersOnly() {
        for (FailureClass failureClass : FailureClass.values()) {
            assertThat(failureClass.code()).matches("^[a-z]+$");
        }
    }
}

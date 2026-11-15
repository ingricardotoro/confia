package com.confia.architecture.fixture.mail.notifications.application;

import org.springframework.mail.SimpleMailMessage;

/**
 * Fixture: an application-layer class, even of the notifications module itself, that imports the
 * Spring mail abstraction. Only {@code notifications.infrastructure} may hold the client (BI150).
 */
public class ImportsSpringMail {

    public Class<?> type() {
        return SimpleMailMessage.class;
    }
}

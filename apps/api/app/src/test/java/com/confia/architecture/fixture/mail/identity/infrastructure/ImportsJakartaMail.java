package com.confia.architecture.fixture.mail.identity.infrastructure;

import jakarta.mail.internet.InternetAddress;

/** Fixture: an infrastructure class of another module that imports the jakarta.mail client. */
public class ImportsJakartaMail {

    public Class<?> type() {
        return InternetAddress.class;
    }
}

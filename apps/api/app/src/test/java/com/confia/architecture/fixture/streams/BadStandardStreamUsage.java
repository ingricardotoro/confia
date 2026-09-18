package com.confia.architecture.fixture.streams;

/**
 * Deliberate violation fixture (docs/03-seguridad.md's {@code no-console-in-api} Semgrep rule):
 * production code must never write to {@code System.out}/{@code System.err} or call {@code
 * Throwable.printStackTrace()}, because both bypass the redaction the structured logger applies.
 * Permanent, never removed: it is what proves {@link
 * com.confia.architecture.NoStandardStreamAccessTest} actually rejects something.
 */
public final class BadStandardStreamUsage {

    public void writesToStandardOut() {
        System.out.println("leaked to stdout, bypassing structured logging");
    }

    public void writesToStandardError() {
        System.err.println("leaked to stderr, bypassing structured logging");
    }

    public void printsAStackTrace() {
        new RuntimeException("leaked via printStackTrace").printStackTrace();
    }
}

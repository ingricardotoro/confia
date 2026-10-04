package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * What a test of a secret that stops the process at startup must prove about the failure
 * (CLAUDE.md, regla 11): the whole cause chain, not only the outermost exception, carries nothing
 * of the rejected value. A decoder or parser exception chained as a cause repeats a fragment of
 * its input in its own message, so the chain is walked in full.
 */
final class BootFailureAssertions {

    /** Messages a decoder or a parser produces; none of them may appear anywhere in the failure. */
    private static final List<String> PARSER_DETAILS = List.of(
            "Illegal base64", "Input byte array", "Invalid UUID", "UUID string too large",
            "NumberFormatException");

    /** The longest substring of a secret that is allowed to appear in a message or a trace. */
    private static final int MAX_SAFE_FRAGMENT = 3;

    private BootFailureAssertions() {
    }

    /** The failure, its cause, the cause of that cause, and so on, without repeating a cycle. */
    static List<Throwable> chainOf(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = failure; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }

    /** The failure as its stack trace prints it, with every cause. */
    static String stackTraceOf(Throwable failure) {
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    /**
     * No exception that could carry the value is chained, no message or trace contains a decoder
     * or parser detail, and none contains the value or any substring of it longer than three
     * characters. An empty {@code secret} checks only the first two (a missing value has no
     * fragment to leak).
     */
    static void assertNoSecretFragment(Throwable failure, String secret) {
        List<Throwable> chain = chainOf(failure);
        assertThat(chain).as("no IllegalArgumentException anywhere in the cause chain")
                .noneMatch(IllegalArgumentException.class::isInstance);
        StringBuilder everything = new StringBuilder(stackTraceOf(failure));
        for (Throwable t : chain) {
            everything.append(" | ").append(t.getMessage());
        }
        String text = everything.toString();
        for (String detail : PARSER_DETAILS) {
            assertThat(text).as("decoder or parser detail").doesNotContain(detail);
        }
        for (int length = MAX_SAFE_FRAGMENT + 1; length <= secret.length(); length++) {
            for (int start = 0; start + length <= secret.length(); start++) {
                assertThat(text).as("fragment of the value")
                        .doesNotContain(secret.substring(start, start + length));
            }
        }
    }
}

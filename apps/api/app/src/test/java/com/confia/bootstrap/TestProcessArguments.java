package com.confia.bootstrap;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The command-line arguments each real process needs to start in a test, with no database and no
 * {@code application.yml} of its own in the test class path (web-edge-foundations design.md,
 * decision 2). A test {@code application.yml} would shadow the production one on the class path,
 * so everything a process needs beyond its defaults travels as an argument.
 *
 * <p>The two web processes bind to a random port. The administrative process also receives a
 * JDBC URL that nothing listens on, which is enough because the connection pool is lazy, and the
 * column-encryption master key, generated at runtime with {@link SecureRandom}: no secret value is
 * ever written in the repository (CLAUDE.md, regla 13).
 */
final class TestProcessArguments {

    /** The property the administrative process reads its master key from. */
    static final String MASTER_KEY_PROPERTY = "confia.crypto.column-master-key";

    /** A JDBC URL no server answers on: port 1 on the loopback interface. */
    static final String UNREACHABLE_JDBC_URL =
            "jdbc:postgresql://127.0.0.1:1/confia-unreachable";

    private static final int MASTER_KEY_BYTES = 32;

    private TestProcessArguments() {
    }

    /** The arguments for {@code process}, a value of {@code APP_PROFILE}, plus any extra ones. */
    static String[] forProcess(String process, String... extra) {
        List<String> arguments = new ArrayList<>();
        if (!"worker".equals(process)) {
            arguments.add("--server.port=0");
        }
        if ("admin".equals(process)) {
            arguments.add("--spring.datasource.url=" + UNREACHABLE_JDBC_URL);
            arguments.add("--" + MASTER_KEY_PROPERTY + "=" + newMasterKey());
        }
        arguments.addAll(List.of(extra));
        return arguments.toArray(String[]::new);
    }

    /** The administrative arguments with no master key, to prove the process refuses to start. */
    static String[] adminWithoutMasterKey() {
        return new String[] {"--server.port=0", "--spring.datasource.url=" + UNREACHABLE_JDBC_URL};
    }

    /** The administrative arguments with the given value as the master key. */
    static String[] adminWithMasterKey(String value) {
        return new String[] {"--server.port=0", "--spring.datasource.url=" + UNREACHABLE_JDBC_URL,
                "--" + MASTER_KEY_PROPERTY + "=" + value};
    }

    /** A fresh random 32-byte key in Base64, different on every call. */
    static String newMasterKey() {
        byte[] key = new byte[MASTER_KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}

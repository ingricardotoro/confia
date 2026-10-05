package com.confia.bootstrap;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The command-line arguments each real process needs to start in a test, with no database and no
 * {@code application.yml} of its own in the test class path (web-edge-foundations design.md,
 * decision 2). A test {@code application.yml} would shadow the production one on the class path,
 * so everything a process needs beyond its defaults travels as an argument.
 *
 * <p>The two web processes bind to a random port. The administrative process also receives a
 * JDBC URL that nothing listens on, which is enough because the connection pool is lazy, and the
 * three secrets of decision 3 (the column-encryption master key, the Argon2id pepper and the login
 * institution id), generated at runtime: no secret value is ever written in the repository
 * (CLAUDE.md, regla 13).
 */
final class TestProcessArguments {

    /** The property the administrative process reads its master key from. */
    static final String MASTER_KEY_PROPERTY = "confia.crypto.column-master-key";

    /** The property the administrative process reads its Argon2id pepper from. */
    static final String PEPPER_PROPERTY = "confia.identity.argon2-pepper";

    /** The property the administrative process reads its login institution id from. */
    static final String INSTITUTION_PROPERTY = "confia.identity.login-institution-id";

    /** A JDBC URL no server answers on: port 1 on the loopback interface. */
    static final String UNREACHABLE_JDBC_URL =
            "jdbc:postgresql://127.0.0.1:1/confia-unreachable";

    private static final int MASTER_KEY_BYTES = 32;
    private static final int PEPPER_BYTES = 32;

    private TestProcessArguments() {
    }

    /** The arguments for {@code process}, a value of {@code APP_PROFILE}, plus any extra ones. */
    static String[] forProcess(String process, String... extra) {
        List<String> arguments = new ArrayList<>();
        if ("admin".equals(process)) {
            arguments.addAll(List.of(adminWith(Map.of())));
        } else if (!"worker".equals(process)) {
            arguments.add("--server.port=0");
        }
        arguments.addAll(List.of(extra));
        return arguments.toArray(String[]::new);
    }

    /** The administrative arguments with no master key, to prove the process refuses to start. */
    static String[] adminWithoutMasterKey() {
        return adminWithout(MASTER_KEY_PROPERTY);
    }

    /** The administrative arguments with the given value as the master key. */
    static String[] adminWithMasterKey(String value) {
        return adminWith(MASTER_KEY_PROPERTY, value);
    }

    /** The administrative arguments with {@code property} left out and every other one valid. */
    static String[] adminWithout(String property) {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put(property, null);
        return adminWith(overrides);
    }

    /** The administrative arguments with {@code property} set to {@code value}, the rest valid. */
    static String[] adminWith(String property, String value) {
        return adminWith(Map.of(property, value));
    }

    /**
     * The complete administrative arguments: every property valid and freshly generated, then
     * {@code overrides} applied on top, a {@code null} value removing that property.
     */
    private static String[] adminWith(Map<String, String> overrides) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("server.port", "0");
        properties.put("spring.datasource.url", UNREACHABLE_JDBC_URL);
        properties.put(MASTER_KEY_PROPERTY, newMasterKey());
        properties.put(PEPPER_PROPERTY, newPepper());
        properties.put(INSTITUTION_PROPERTY, UUID.randomUUID().toString());
        overrides.forEach((property, value) -> {
            if (value == null) {
                properties.remove(property);
            } else {
                properties.put(property, value);
            }
        });
        return properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    /** A fresh random 32-byte key in Base64, different on every call. */
    static String newMasterKey() {
        return randomBase64(MASTER_KEY_BYTES);
    }

    /** A fresh random 32-byte Argon2id pepper in Base64, different on every call. */
    static String newPepper() {
        return randomBase64(PEPPER_BYTES);
    }

    private static String randomBase64(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}

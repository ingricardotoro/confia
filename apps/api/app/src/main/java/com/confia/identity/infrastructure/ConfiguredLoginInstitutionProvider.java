package com.confia.identity.infrastructure;

import com.confia.identity.application.LoginInstitutionProvider;
import com.confia.kernel.InstitutionId;
import java.util.UUID;

/**
 * Resolves the login institution from the process's own configuration, never the request
 * (design.md, §6.1, decision 11; specs/identity/spec.md, requirement "La institución previa a la
 * autenticación proviene de la configuración del proceso"). The canonical key is {@value
 * #CONFIG_KEY}. Two entry points read it, both "configuration the process itself declares, read
 * once at construction", the same shape {@code com.confia.bootstrap.AppProfile} already
 * established for {@code APP_PROFILE}: the no-argument constructor reads it as a JVM system
 * property, and {@link #fromValue(String)} takes a value the caller read from the Spring {@code
 * Environment} (web-edge-foundations design.md, decision 3), which covers system properties,
 * environment variables ({@code CONFIA_IDENTITY_LOGININSTITUTIONID}) and command-line arguments.
 *
 * <p><b>Fails at construction, never on first use</b> (design.md, decision 11): a missing, blank
 * or malformed value throws immediately, so a misconfigured process never starts far enough to
 * accept a single login attempt against the wrong institution — or against none at all. The
 * message names the key and never repeats the rejected value, and the parser's own exception is
 * not chained, because its message carries the value (CLAUDE.md, regla 11).
 */
public final class ConfiguredLoginInstitutionProvider implements LoginInstitutionProvider {

    static final String CONFIG_KEY = "confia.identity.login-institution-id";

    private final InstitutionId institutionId;

    /** Reads {@value #CONFIG_KEY} from {@link System#getProperty(String)}. */
    public ConfiguredLoginInstitutionProvider() {
        this(System.getProperty(CONFIG_KEY));
    }

    /** Package-visible: lets a test supply the raw value directly, without a system property. */
    ConfiguredLoginInstitutionProvider(String rawValue) {
        this.institutionId = parse(rawValue);
    }

    /**
     * The provider for a value the caller already read from its configuration source, for example
     * the Spring {@code Environment} (web-edge-foundations design.md, decision 3).
     *
     * @throws IllegalStateException if the value is absent, blank or not a UUID; the message names
     *     {@value #CONFIG_KEY} and never contains the value
     */
    public static ConfiguredLoginInstitutionProvider fromValue(String rawValue) {
        return new ConfiguredLoginInstitutionProvider(rawValue);
    }

    private static InstitutionId parse(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalStateException("the process must declare '" + CONFIG_KEY
                    + "' as its login institution id; none was configured");
        }
        try {
            return new InstitutionId(UUID.fromString(rawValue.trim()));
        } catch (IllegalArgumentException e) {
            // Neither the value nor the parser's exception travels: its message repeats the input.
            throw new IllegalStateException("'" + CONFIG_KEY + "' must be a valid UUID");
        }
    }

    @Override
    public InstitutionId loginInstitutionId() {
        return institutionId;
    }
}

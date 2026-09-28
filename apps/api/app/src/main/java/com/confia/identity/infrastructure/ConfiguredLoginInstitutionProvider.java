package com.confia.identity.infrastructure;

import com.confia.identity.application.LoginInstitutionProvider;
import com.confia.kernel.InstitutionId;
import java.util.UUID;

/**
 * Resolves the login institution from the process's own configuration, never the request
 * (design.md, §6.1, decision 11; specs/identity/spec.md, requirement "La institución previa a la
 * autenticación proviene de la configuración del proceso"). The canonical key is {@value
 * #CONFIG_KEY}, read as a JVM system property — the same "configuration the process itself
 * declares, read once at construction" shape {@code com.confia.bootstrap.AppProfile} already
 * established for {@code APP_PROFILE}, except this key is a per-process login institution, not a
 * process kind, so it takes the dotted system-property form design.md's own decision 11 names
 * literally, rather than an environment variable.
 *
 * <p><b>Fails at construction, never on first use</b> (design.md, decision 11): a missing, blank
 * or malformed value throws immediately, so a misconfigured process never starts far enough to
 * accept a single login attempt against the wrong institution — or against none at all.
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
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalStateException("the process must declare '" + CONFIG_KEY
                    + "' as its login institution id; none was configured");
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(rawValue.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "'" + CONFIG_KEY + "' must be a valid UUID, was: " + rawValue, e);
        }
        this.institutionId = new InstitutionId(parsed);
    }

    @Override
    public InstitutionId loginInstitutionId() {
        return institutionId;
    }
}

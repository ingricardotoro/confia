package com.confia.identity.application;

import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PlainPassword;
import com.confia.shared.security.ClientAddress;
import java.util.Objects;

/**
 * The input {@link AuthenticateWithPassword} receives: the identifier and password exactly as
 * presented, not yet normalized. Normalization happens once, at the boundary {@link
 * LoginIdentifier#of} and {@link PlainPassword#of} themselves apply (design.md, decision 11) —
 * this record carries the raw, pre-normalization strings on purpose, so there is exactly one place
 * downstream that normalizes either value.
 *
 * <p><b>The origin of the request</b> (session-endpoints-cookie-and-csrf design.md decision 11,
 * observation O6; scenarios I205 and I206). The command carries the resolved address of the client
 * and the agent text the request filter already sanitized, because the code that composes the login
 * with the issuance of a session needs both and must not read them from anywhere ambient. Both are
 * {@code null} for a call that has no HTTP request (a test, a background caller), and that is
 * accepted. When present they also go into the entries the authentication writes, so the trail says
 * where an attempt came from even without the request decorator of the audit writer.
 *
 * @param presentedIdentifier the identifier as typed
 * @param presentedPassword the password as typed
 * @param clientAddress the resolved address of the client, or {@code null}
 * @param userAgent the sanitized agent text, or {@code null}
 */
public record AuthenticationCommand(String presentedIdentifier, String presentedPassword,
        ClientAddress clientAddress, String userAgent) {

    public AuthenticationCommand {
        Objects.requireNonNull(presentedIdentifier, "presentedIdentifier");
        Objects.requireNonNull(presentedPassword, "presentedPassword");
    }

    /** A command with no origin: the form every caller without an HTTP request uses. */
    public AuthenticationCommand(String presentedIdentifier, String presentedPassword) {
        this(presentedIdentifier, presentedPassword, null, null);
    }

    /**
     * Redacts {@code presentedPassword}, because the compiler-generated {@code toString()} of a
     * record prints every component and this one carries a password in a bare {@link String}.
     *
     * <p>The pre-merge security audit found this. {@link PlainPassword}, {@code StoredPasswordHash}
     * and {@code Argon2Pepper} all redact explicitly and cite CLAUDE.md regla 11 in their own
     * Javadoc, but this record sits <em>upstream</em> of them — it exists precisely to carry the raw
     * strings until {@link PlainPassword#of} wraps them — so it was the one place in the module
     * where a password lived in a type with no guard. Nobody wrote a leak; the language wrote it,
     * which is why the declaration looked fine.
     *
     * <p>{@code presentedIdentifier} stays visible on purpose. It is not on regla 11's list, {@link
     * LoginIdentifier} does not redact it either, and design.md decision 8 already writes the
     * normalized identifier as an audit {@code actor_label} for accounts that exist. Redacting it
     * here would contradict a decision already merged. The address and the agent are redacted: the
     * address is on the list of values no log may hold.
     */
    @Override
    public String toString() {
        return "AuthenticationCommand[presentedIdentifier=" + presentedIdentifier
                + ", presentedPassword=REDACTED, clientAddress=REDACTED, userAgent=REDACTED]";
    }
}

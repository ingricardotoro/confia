package com.confia.shared.observability.events;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the one line a security event leaves in the log
 * (session-endpoints-cookie-and-csrf design.md decision 12): a {@code WARN} that names the event and
 * carries the identifier of the request, so the line can be matched with the audit trail, and
 * nothing else. What the event is about (the address, the identifier, the numbers) is in the audit
 * entry written in the same transaction, never in a log (CLAUDE.md, regla 11).
 *
 * <p>It lives in {@code shared} and not in a module because {@code IdentityScopeExclusionInventoryTest}
 * forbids a logging framework in {@code com.confia.identity}: the module's own record is its audit
 * trail, and this class is the one door through which a use case asks for a line.
 *
 * <p>The event name is a closed identifier ({@code [a-z][a-z0-9_.]{0,79}}), so a caller cannot make
 * the line carry free text, a line break or a forged field.
 */
public final class SecurityEventLog {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityEventLog.class);
    private static final Pattern EVENT_NAME = Pattern.compile("[a-z][a-z0-9_.]{0,79}");

    /**
     * Writes one {@code WARN} whose message is {@code event} and whose only field is the request
     * identifier.
     *
     * @throws IllegalArgumentException if {@code event} is not a closed identifier; nothing is written
     */
    public void warn(String event, UUID requestId) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(requestId, "requestId");
        if (!EVENT_NAME.matcher(event).matches()) {
            throw new IllegalArgumentException("a security event name is a lowercase identifier of "
                    + "at most 80 characters");
        }
        LOG.atWarn().addKeyValue("requestId", requestId.toString()).log(event);
    }
}

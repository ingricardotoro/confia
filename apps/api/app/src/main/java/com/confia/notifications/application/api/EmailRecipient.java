package com.confia.notifications.application.api;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single recipient of a message: one address of the common dot-atom form, with the length
 * limits of RFC 5321, validated here so that a header-injection attempt ({@code \r\n} or any
 * separator) cannot reach the transport. The check is deliberately stricter than the full RFC 5322
 * grammar: no quoted local parts, no comments, no address literals.
 *
 * <p>The address is personal data (docs/08): <b>the textual representation is redacted</b> and the
 * rejection message states the rule, never the value. Surrounding blanks are stripped; the case is
 * kept for the envelope and normalized only when the recipient hash is computed.
 */
public record EmailRecipient(String address) {

    private static final int MAX_ADDRESS_LENGTH = 254;
    private static final int MAX_LOCAL_PART_LENGTH = 64;

    private static final String ATOM = "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+";
    private static final String LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?";
    private static final Pattern ADDRESS = Pattern.compile(
            "(" + ATOM + "(?:\\." + ATOM + ")*)@(" + LABEL + "(?:\\." + LABEL + ")+)");

    public EmailRecipient {
        Objects.requireNonNull(address, "address");
        address = address.strip();
        Matcher matcher = ADDRESS.matcher(address);
        if (address.length() > MAX_ADDRESS_LENGTH || !matcher.matches()
                || matcher.group(1).length() > MAX_LOCAL_PART_LENGTH) {
            throw new IllegalArgumentException(
                    "a recipient must be one e-mail address of the form local@domain.tld "
                            + "within the length limits of RFC 5321");
        }
    }

    @Override
    public String toString() {
        return "EmailRecipient[redacted]";
    }
}

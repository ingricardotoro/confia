package com.confia.shared.web.csrf;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The origins a browser may send in {@code Origin} on an unsafe request to the administrative chain
 * (session-endpoints-cookie-and-csrf design.md, decision 4): the value of {@code
 * confia.web.allowed-origins}, validated once at start. Each entry is a serialized origin of RFC
 * 6454, {@code scheme://host[:port]}, in lower case, with no path, no trailing slash, no wildcard
 * and no user information; the scheme is {@code https}, or {@code http} only for the host {@code
 * localhost} or {@code 127.0.0.1}. The same rule holds in every profile. A list may be empty, and
 * then no unsafe request passes: the failure is closed.
 *
 * <p>The comparison is exact. {@code null} is not an origin and is never allowed.
 */
public final class AllowedOrigins {

    /** The property the list comes from, named by every error about it. */
    static final String PROPERTY = "confia.web.allowed-origins";

    private static final Pattern ORIGIN = Pattern.compile(
            "(https?)://([a-z0-9]([a-z0-9.-]*[a-z0-9])?)(?::([0-9]{1,5}))?");
    private static final int MAX_PORT = 65_535;
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1");

    private final Set<String> origins;

    private AllowedOrigins(Set<String> origins) {
        this.origins = origins;
    }

    /**
     * @throws IllegalArgumentException naming {@value #PROPERTY} and the position of the first entry
     *     that is not a valid origin or that repeats an earlier one, and never repeating the entry
     */
    public static AllowedOrigins parse(List<String> entries) {
        Set<String> accepted = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            String entry = entries.get(i);
            if (!isValid(entry)) {
                throw new IllegalArgumentException(PROPERTY + "[" + i + "] is not a valid "
                        + "serialized origin: lower case scheme://host[:port], https (http only for "
                        + "localhost or 127.0.0.1), with no path, wildcard or user information");
            }
            if (!accepted.add(entry)) {
                throw new IllegalArgumentException(PROPERTY + "[" + i
                        + "] repeats an earlier entry");
            }
        }
        return new AllowedOrigins(Set.copyOf(accepted));
    }

    /** Whether {@code origin} is exactly one of the configured origins. */
    public boolean contains(String origin) {
        return origin != null && origins.contains(origin);
    }

    private static boolean isValid(String entry) {
        if (entry == null) {
            return false;
        }
        Matcher match = ORIGIN.matcher(entry);
        if (!match.matches()) {
            return false;
        }
        if (match.group(4) != null) {
            int port = Integer.parseInt(match.group(4));
            if (port < 1 || port > MAX_PORT) {
                return false;
            }
        }
        return match.group(1).equals("https") || LOCAL_HOSTS.contains(match.group(2));
    }
}

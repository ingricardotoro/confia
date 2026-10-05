package com.confia.shared.web.request;

import com.confia.shared.security.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;

/**
 * Decides which address is the client's (web-edge-foundations design.md, decision 12). The address
 * of the connection is the client unless it belongs to a trusted proxy; only then is {@code
 * X-Forwarded-For} read, from right to left, and the first entry that is not a trusted proxy is the
 * client. A header that is unusable in any way is ignored whole and the connection's address is
 * used: it holds more than {@value #MAX_ENTRIES} entries or {@value #MAX_CHARACTERS} characters,
 * or any entry is not an IP literal (an empty entry, a port, brackets, a name). With the list
 * empty, which is the default, no client can choose its own address.
 */
final class ClientAddressResolver {

    static final String FORWARDED_FOR = "X-Forwarded-For";
    static final int MAX_ENTRIES = 32;
    static final int MAX_CHARACTERS = 1024;

    private final TrustedProxies trusted;

    ClientAddressResolver(TrustedProxies trusted) {
        this.trusted = trusted;
    }

    /** The client of {@code request}; empty only when the container gave no IP literal for it. */
    Optional<ClientAddress> resolve(HttpServletRequest request) {
        Enumeration<String> lines = request.getHeaders(FORWARDED_FOR);
        return resolve(request.getRemoteAddr(),
                lines == null ? List.of() : Collections.list(lines));
    }

    /**
     * @param remoteAddress the address of the connection
     * @param forwardedForLines every {@code X-Forwarded-For} header line, in the order received
     */
    Optional<ClientAddress> resolve(String remoteAddress, List<String> forwardedForLines) {
        Optional<ClientAddress> remote = parse(remoteAddress);
        if (remote.isEmpty() || !trusted.contains(remote.get())) {
            return remote;
        }
        return fromTheHeader(forwardedForLines).or(() -> remote);
    }

    private Optional<ClientAddress> fromTheHeader(List<String> lines) {
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        String joined = String.join(",", lines);
        if (joined.length() > MAX_CHARACTERS) {
            return Optional.empty();
        }
        String[] parts = joined.split(",", -1);
        if (parts.length > MAX_ENTRIES) {
            return Optional.empty();
        }
        List<ClientAddress> entries = new ArrayList<>(parts.length);
        for (String part : parts) {
            Optional<ClientAddress> entry = parse(trimmed(part));
            if (entry.isEmpty()) {
                return Optional.empty();
            }
            entries.add(entry.get());
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (!trusted.contains(entries.get(i))) {
                return Optional.of(entries.get(i));
            }
        }
        return Optional.of(entries.get(0));
    }

    private static Optional<ClientAddress> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ClientAddress.parseLiteral(text));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Without the spaces and tabs HTTP allows around a list element, and no other character. */
    private static String trimmed(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isBlank(text.charAt(start))) {
            start++;
        }
        while (end > start && isBlank(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }
}

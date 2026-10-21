package com.confia.shared.web.request;

import com.confia.shared.web.csrf.AllowedOrigins;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The properties of the web edge that an environment sets (web-edge-foundations design.md,
 * decision 12), bound through the constructor so a bad value stops the start before a request is
 * accepted.
 *
 * @param trustedProxies {@code confia.web.trusted-proxies}: the IP addresses and CIDR ranges of the
 *     proxies whose {@code X-Forwarded-For} may be believed (environment variable {@code
 *     CONFIA_WEB_TRUSTEDPROXIES}, separated by commas). Empty unless an environment fills it: no
 *     file of the repository sets a value, and with it empty no client can choose its own address.
 * @param allowedOrigins {@code confia.web.allowed-origins}: the origins a browser may send in
 *     {@code Origin} on an unsafe request (environment variable {@code CONFIA_WEB_ALLOWEDORIGINS},
 *     separated by commas). Empty by default and in every file of the repository except the
 *     local profile: with it empty no unsafe request passes (session-endpoints-cookie-and-csrf
 *     design.md, decision 4)
 * @param userAgentMaxLength {@code confia.web.user-agent-max-length}: how many characters of the
 *     {@code User-Agent} header are kept
 */
@ConfigurationProperties(prefix = "confia.web")
public record WebEdgeProperties(@DefaultValue List<String> trustedProxies,
        @DefaultValue("512") int userAgentMaxLength, @DefaultValue List<String> allowedOrigins) {

    public WebEdgeProperties {
        trustedProxies = List.copyOf(trustedProxies);
        allowedOrigins = List.copyOf(allowedOrigins);
        AllowedOrigins.parse(allowedOrigins);
        // Parsing here is what stops the start with a message that names the property and the
        // position of the bad entry.
        TrustedProxies.parse(trustedProxies);
        if (userAgentMaxLength < 1) {
            throw new IllegalArgumentException(
                    "confia.web.user-agent-max-length must be at least 1");
        }
    }
}

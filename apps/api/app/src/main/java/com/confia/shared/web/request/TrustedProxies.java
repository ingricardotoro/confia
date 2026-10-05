package com.confia.shared.web.request;

import com.confia.shared.security.ClientAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * The proxies whose {@code X-Forwarded-For} header may be believed (web-edge-foundations design.md,
 * decision 12): a list of addresses and ranges, empty unless the environment fills it.
 */
final class TrustedProxies {

    /** The property the list comes from, named by every error about it. */
    static final String PROPERTY = "confia.web.trusted-proxies";

    private final List<CidrBlock> blocks;

    private TrustedProxies(List<CidrBlock> blocks) {
        this.blocks = blocks;
    }

    /**
     * @throws IllegalArgumentException naming {@value #PROPERTY} and the position of the first
     *     entry that is not an IP address or a CIDR range, or whose range has host bits set
     *     ({@code 10.0.0.5/8}): a trusted range is never widened in silence
     */
    static TrustedProxies parse(List<String> entries) {
        List<CidrBlock> blocks = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            try {
                blocks.add(CidrBlock.parseWithoutHostBits(entries.get(i)));
            } catch (IllegalArgumentException e) {
                // The cause is not chained and the entry is not repeated: the position says which.
                throw new IllegalArgumentException(PROPERTY + "[" + i
                        + "] is not an IP address or a CIDR range: " + e.getMessage());
            }
        }
        return new TrustedProxies(List.copyOf(blocks));
    }

    boolean contains(ClientAddress address) {
        return blocks.stream().anyMatch(block -> block.contains(address));
    }
}

package com.confia.shared.web.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.security.ClientAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specs/web-edge, "La IP del cliente se obtiene solo a través de proxies de confianza" and the
 * trusted-proxy requirement (web-edge-foundations design.md, decision 12): the eight scenarios of
 * the requirement as examples, the limits of the header, and two properties. The first says that a
 * peer outside the list decides its own address whatever the header holds, which is the whole
 * point of H1. The second compares the algorithm with a reference written with integer arithmetic
 * and a plain loop, so it shares nothing with the code under test but the specification.
 */
class ClientAddressResolverTest {

    private static final String CLIENT = "198.51.100.7";

    private static ClientAddressResolver resolver(String... trusted) {
        return new ClientAddressResolver(TrustedProxies.parse(List.of(trusted)));
    }

    private static Optional<String> resolve(ClientAddressResolver resolver, String remote,
            String... headerLines) {
        return resolver.resolve(remote, List.of(headerLines)).map(ClientAddress::canonical);
    }

    @Test
    void anEmptyListIgnoresTheHeaderEntirely() {
        assertThat(resolve(resolver(), "203.0.113.9", CLIENT)).contains("203.0.113.9");
    }

    @Test
    void aPeerOutsideTheListIsTheClientAndItsHeaderIsIgnored() {
        assertThat(resolve(resolver("10.0.0.1"), "203.0.113.9", CLIENT)).contains("203.0.113.9");
    }

    @Test
    void aChainOfTwoProxiesResolvesTheClientBehindThem() {
        assertThat(resolve(resolver("10.0.0.1", "10.0.0.2"), "10.0.0.1", CLIENT + ", 10.0.0.2"))
                .contains(CLIENT);
    }

    @Test
    void aClientThatPrependsAFakeAddressDoesNotChooseItsOwn() {
        assertThat(resolve(resolver("10.0.0.1"), "10.0.0.1", "1.1.1.1, " + CLIENT))
                .contains(CLIENT);
    }

    @Test
    void whenEveryEntryIsATrustedProxyTheFirstOneOnTheLeftIsTheClient() {
        assertThat(resolve(resolver("10.0.0.1", "10.0.0.2"), "10.0.0.1", "10.0.0.2"))
                .contains("10.0.0.2");
        assertThat(resolve(resolver("10.0.0.0/8"), "10.0.0.1", "10.0.0.9, 10.0.0.8, 10.0.0.2"))
                .contains("10.0.0.9");
    }

    @ParameterizedTest
    @ValueSource(strings = {CLIENT + ", basura", "", " ", ",", CLIENT + ",", "," + CLIENT,
            "1.1.1.1,,2.2.2.2", "[2001:db8::1]", "2001:db8::1]", CLIENT + ":443", "localhost",
            "1.2.3", "010.0.0.1", "unknown", "_hidden", CLIENT + " 2.2.2.2", "2.2.2.256"})
    void anInvalidEntryMakesTheWholeHeaderIgnored(String header) {
        assertThat(resolve(resolver("10.0.0.1"), "10.0.0.1", header)).contains("10.0.0.1");
    }

    @Test
    void anEmptyHeaderValueAndAMissingHeaderBothResolveTheRemoteAddress() {
        assertThat(resolve(resolver("10.0.0.1"), "10.0.0.1", "")).contains("10.0.0.1");
        assertThat(resolve(resolver("10.0.0.1"), "10.0.0.1")).contains("10.0.0.1");
    }

    @Test
    void severalHeaderLinesAreOneListInTheOrderReceived() {
        assertThat(resolve(resolver("10.0.0.1", "10.0.0.2"), "10.0.0.1", CLIENT, "10.0.0.2"))
                .contains(CLIENT);
        // Order matters: the same two lines the other way round put the trusted proxy first.
        assertThat(resolve(resolver("10.0.0.1", "10.0.0.2"), "10.0.0.1", "10.0.0.2", CLIENT))
                .contains(CLIENT);
        assertThat(resolve(resolver("10.0.0.1", "10.0.0.2"), "10.0.0.1", "1.1.1.1", CLIENT,
                "10.0.0.2")).contains(CLIENT);
    }

    @Test
    void aBadLineAnywhereMakesTheWholeListIgnored() {
        assertThat(resolve(resolver("10.0.0.1"), "10.0.0.1", CLIENT, "basura")).contains("10.0.0.1");
    }

    @Test
    void anIpv4MappedRemoteAddressIsNormalizedBeforeItIsComparedAndBeforeItIsUsed() {
        assertThat(resolve(resolver("10.0.0.1"), "::ffff:10.0.0.1", CLIENT)).contains(CLIENT);
        assertThat(resolve(resolver("10.0.0.1"), "::ffff:203.0.113.9", CLIENT))
                .contains("203.0.113.9");
        assertThat(resolve(resolver("10.0.0.1", "10.0.0.2"), "10.0.0.1",
                CLIENT + ", ::ffff:10.0.0.2")).contains(CLIENT);
        assertThat(resolve(resolver("10.0.0.1"), "10.0.0.1", "::ffff:" + CLIENT)).contains(CLIENT);
    }

    @Test
    void aCidrRangeEntryDecidesWhoIsATrustedProxy() {
        ClientAddressResolver resolver = resolver("10.0.0.0/8");

        assertThat(resolve(resolver, "10.1.2.3", CLIENT)).contains(CLIENT);
        assertThat(resolve(resolver, "11.0.0.1", CLIENT)).contains("11.0.0.1");
    }

    @Test
    void anIpv6ProxyAndAnIpv6ClientResolveLikeAnyOther() {
        ClientAddressResolver resolver = resolver("2001:db8::/32");

        assertThat(resolve(resolver, "2001:db8::5", "2001:4860::1, 2001:db8::7"))
                .contains("2001:4860:0:0:0:0:0:1");
    }

    @Test
    void theHeaderMayHoldAtMostThirtyTwoEntries() {
        assertThat(resolve(resolver("10.0.0.1", "10.0.0.2"), "10.0.0.1", entries(CLIENT, 32)))
                .contains(CLIENT);
        assertThat(resolve(resolver("10.0.0.1", "10.0.0.2"), "10.0.0.1", entries(CLIENT, 33)))
                .contains("10.0.0.1");
        // The count is of entries over every line, not of lines.
        assertThat(resolve(resolver("10.0.0.1", "10.0.0.2"), "10.0.0.1", entries(CLIENT, 20),
                entries("10.0.0.2", 12))).contains(CLIENT);
        assertThat(resolve(resolver("10.0.0.1", "10.0.0.2"), "10.0.0.1", entries(CLIENT, 20),
                entries("10.0.0.2", 13))).contains("10.0.0.1");
    }

    @Test
    void theHeaderMayHoldAtMostOneThousandAndTwentyFourCharacters() {
        String exact = " ".repeat(1024 - CLIENT.length()) + CLIENT;

        assertThat(exact).hasSize(1024);
        assertThat(resolve(resolver("10.0.0.1"), "10.0.0.1", exact)).contains(CLIENT);
        assertThat(resolve(resolver("10.0.0.1"), "10.0.0.1", " " + exact)).contains("10.0.0.1");
        // The limit is on the lines taken together.
        assertThat(resolve(resolver("10.0.0.1"), "10.0.0.1", " ".repeat(600) + CLIENT,
                " ".repeat(600) + "10.0.0.2")).contains("10.0.0.1");
    }

    @Test
    void aRemoteAddressThatIsNotAnIpLiteralResolvesNothing() {
        assertThat(resolve(resolver("10.0.0.1"), "localhost", CLIENT)).isEmpty();
        assertThat(resolve(resolver("10.0.0.1"), "", CLIENT)).isEmpty();
        assertThat(resolver("10.0.0.1").resolve(null, List.of(CLIENT))).isEmpty();
    }

    @Property(tries = 400)
    void withAPeerOutsideTheListTheResultIsAlwaysTheRemoteAddress(
            @ForAll("untrustedRemotes") String remote,
            @ForAll @Size(max = 4) List<@From("headerLines") String> lines) {
        ClientAddressResolver resolver = resolver("10.0.0.0/24", "192.168.0.1", "2001:db8::/32");

        assertThat(resolver.resolve(remote, lines)).hasValue(ClientAddress.parseLiteral(remote));
    }

    @Property(tries = 400)
    void withATrustedPeerTheResultIsTheRightmostEntryOutsideTheListOrTheFirstOne(
            @ForAll @Size(min = 1, max = 32) List<@From("hosts") Integer> entries,
            @ForAll @Size(min = 1, max = 3) List<@IntRange(min = 0, max = 40) Integer> splitAfter) {
        // The list under test is 10.0.0.0/28; the reference below knows it as an integer mask.
        ClientAddressResolver resolver = resolver("10.0.0.0/28");
        List<String> lines = split(entries, splitAfter);

        Integer expected = entries.get(0);
        for (int i = entries.size() - 1; i >= 0; i--) {
            if ((entries.get(i) & 0xFFFFFFF0) != 0x0A000000) {
                expected = entries.get(i);
                break;
            }
        }

        assertThat(resolver.resolve("10.0.0.3", lines).map(ClientAddress::canonical))
                .contains(text(expected));
    }

    @Provide
    Arbitrary<String> untrustedRemotes() {
        return Arbitraries.oneOf(
                Arbitraries.integers().between(11, 223).map(first -> first + ".7.7.7"),
                Arbitraries.just("203.0.113.9"), Arbitraries.just("10.0.1.1"),
                Arbitraries.just("192.168.0.2"), Arbitraries.just("2001:4860::1"),
                Arbitraries.just("::ffff:203.0.113.9"));
    }

    @Provide
    Arbitrary<String> headerLines() {
        Arbitrary<String> valid = Arbitraries.integers().between(0, 255)
                .map(last -> "10.0.0." + last);
        Arbitrary<String> garbage = Arbitraries.strings().ascii().ofMaxLength(40);
        return Arbitraries.oneOf(valid, garbage, Arbitraries.just("192.168.0.1, 198.51.100.7"));
    }

    @Provide
    Arbitrary<Integer> hosts() {
        // Half of the entries fall inside 10.0.0.0/28, the other half anywhere in 10.0.0.0/8, so
        // the walk meets trusted and untrusted entries in every order.
        Arbitrary<Integer> inside = Arbitraries.integers().between(0, 15).map(n -> 0x0A000000 | n);
        Arbitrary<Integer> outside = Arbitraries.integers().between(16, 0xFFFFFF)
                .map(n -> 0x0A000000 | n);
        return Arbitraries.oneOf(inside, outside);
    }

    /** Joins {@code entries} into header lines, cutting after each of the given counts. */
    private static List<String> split(List<Integer> entries, List<Integer> splitAfter) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int count : splitAfter) {
            int end = Math.min(entries.size(), start + 1 + (count % entries.size()));
            if (end > start && end < entries.size()) {
                lines.add(join(entries.subList(start, end)));
                start = end;
            }
        }
        lines.add(join(entries.subList(start, entries.size())));
        return lines;
    }

    private static String join(List<Integer> entries) {
        return entries.stream().map(ClientAddressResolverTest::text)
                .collect(Collectors.joining(", "));
    }

    private static String text(int address) {
        return (address >>> 24) + "." + ((address >> 16) & 255) + "." + ((address >> 8) & 255) + "."
                + (address & 255);
    }

    /** {@code count} entries whose rightmost untrusted one is {@code client}, as one line. */
    private static String entries(String client, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> i == 0 ? client : "10.0.0.2")
                .collect(Collectors.joining(","));
    }
}

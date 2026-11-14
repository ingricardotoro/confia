package com.confia.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Locale;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Scenarios NT13 and NT14: capitalization and surrounding blanks never change the hash. */
class RecipientHasherProperties {

    private static final byte[] KEY = new byte[32];

    static {
        Arrays.fill(KEY, (byte) 0x42);
    }

    private final RecipientHasher hasher = new RecipientHasher(KEY);

    @Property(tries = 300)
    void anyCapitalizationAndBlanksGiveTheSameHash(@ForAll("addresses") String address,
            @ForAll("blanks") String before, @ForAll("blanks") String after) {
        assertThat(hasher.hash(before + address.toUpperCase(Locale.ROOT) + after))
                .isEqualTo(hasher.hash(address));
    }

    @Property(tries = 300)
    void twoAddressesShareAHashExactlyWhenTheyAreEqual(@ForAll("addresses") String first,
            @ForAll("addresses") String second) {
        assertThat(hasher.hash(first).equals(hasher.hash(second))).isEqualTo(first.equals(second));
    }

    @Provide
    Arbitrary<String> addresses() {
        Arbitrary<String> local = Arbitraries.strings().withCharRange('a', 'z')
                .withCharRange('0', '9').withChars('.', '-').ofMinLength(1).ofMaxLength(20);
        Arbitrary<String> domain = Arbitraries.strings().withCharRange('a', 'z')
                .ofMinLength(1).ofMaxLength(12);
        return Combinators.combine(local, domain).as((l, d) -> l + "@" + d + ".hn");
    }

    @Provide
    Arbitrary<String> blanks() {
        return Arbitraries.strings().withChars(' ', '\t', '\n').ofMaxLength(3);
    }
}

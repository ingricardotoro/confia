package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * {@link EncryptedColumnValue}'s codec: {@code format()}/{@code parse(...)} against the exact
 * five-part {@code docs/03-seguridad.md} §7.3 shape (design.md, decision 5).
 *
 * <p>The round trip is asserted field by field with AssertJ's array-content {@code isEqualTo},
 * never with the record's own generated {@code equals()}: two {@code byte[]} components with
 * identical content but different array instances compare unequal under {@code
 * Objects.equals} (the {@code java.util.Arrays}/record-equality gotcha), which is exactly why
 * this class does not rely on {@code assertThat(parsed).isEqualTo(original)}.
 */
class EncryptedColumnValueTest {

    @Test
    void formatProducesExactlyTheFivePartV1String() {
        EncryptedColumnValue value = new EncryptedColumnValue("dek-1", new byte[] {1, 2, 3},
                new byte[] {4, 5, 6, 7}, new byte[] {8, 9});

        String expected = "v1:dek-1:" + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3})
                + ":" + Base64.getEncoder().encodeToString(new byte[] {4, 5, 6, 7})
                + ":" + Base64.getEncoder().encodeToString(new byte[] {8, 9});

        assertThat(value.format()).isEqualTo(expected);
    }

    @Test
    void parseRoundTripsWithFormat() {
        EncryptedColumnValue original = new EncryptedColumnValue(
                "11111111-1111-1111-1111-111111111111",
                new byte[] {10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120},
                new byte[] {1, 2, 3, 4, 5},
                new byte[] {6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21});

        EncryptedColumnValue parsed = EncryptedColumnValue.parse(original.format());

        assertThat(parsed.dekId()).isEqualTo(original.dekId());
        assertThat(parsed.iv()).isEqualTo(original.iv());
        assertThat(parsed.ciphertext()).isEqualTo(original.ciphertext());
        assertThat(parsed.tag()).isEqualTo(original.tag());
    }

    @Test
    void parseRejectsAStringWithoutTheV1Prefix() {
        assertThatThrownBy(() -> EncryptedColumnValue.parse("v2:dek-1:aXY=:Y2lwaGVy:dGFn"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsAStringWithTheWrongNumberOfParts() {
        assertThatThrownBy(() -> EncryptedColumnValue.parse("v1:dek-1:aXY=:Y2lwaGVy"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

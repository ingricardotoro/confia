package com.confia.identity.domain;

import java.text.Normalizer;
import java.util.Objects;

/**
 * The length rule for a new staff password: 12 to 128 characters (docs/03-seguridad.md §4.2;
 * password-recovery-token design.md decision 8). Characters are code points after NFKC, the same
 * {@link Normalizer.Form#NFKC} as {@link PlainPassword#of}, because §4.2 admits emoji and one emoji
 * outside the basic plane takes two UTF-16 units. There is no composition rule.
 *
 * <p>It returns a {@link Result} and never throws for a business outcome. Since 128 code points take
 * at most 256 UTF-16 units, below {@link PlainPassword}'s technical limit of 1024, {@link
 * PlainPassword#of} never throws on an accepted value. The rule applies only to a new password:
 * login keeps {@link PlainPassword} as its technical guard, with no minimum, so existing passwords
 * are never rejected at login.
 */
public final class StaffPasswordLengthPolicy {

    public static final int MIN_CODE_POINTS = 12;
    public static final int MAX_CODE_POINTS = 128;

    /** The outcome of {@link #check}. */
    public sealed interface Result permits Accepted, TooShort, TooLong {
    }

    /** Within the range; {@code password} is already NFKC-normalized and redacted when printed. */
    public record Accepted(PlainPassword password) implements Result {

        public Accepted {
            Objects.requireNonNull(password, "password");
        }
    }

    public record TooShort() implements Result {
    }

    public record TooLong() implements Result {
    }

    /**
     * @throws NullPointerException if {@code raw} is {@code null}, a programming error rather than a
     *     business outcome
     */
    public Result check(String raw) {
        Objects.requireNonNull(raw, "raw");
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints < MIN_CODE_POINTS) {
            return new TooShort();
        }
        if (codePoints > MAX_CODE_POINTS) {
            return new TooLong();
        }
        return new Accepted(PlainPassword.of(normalized));
    }
}

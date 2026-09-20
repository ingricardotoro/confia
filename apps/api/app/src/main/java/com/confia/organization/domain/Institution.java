package com.confia.organization.domain;

import com.confia.kernel.InstitutionId;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Aggregate root of the {@code organization} capability (docs/02-modelo-de-dominio.md §3.1;
 * ADR-0009, ADR-0017). An entity with identity, not a value object: two institutions with the
 * same {@link #id()} are the same institution regardless of their other attributes (design.md,
 * decision 5).
 *
 * <p>This PR (B1) constructs the minimal aggregate: {@link #id()}, {@link #legalName()}, the
 * optional {@link #tradeName()}, and {@link #isActive()}. {@code rtn}, {@code address}, {@code
 * defaultCurrency}, {@code locale} and {@code timezone} land in PR B2, which changes {@link
 * #create} to an eight-parameter signature (design.md, "Pronóstico de tamaño por corte").
 *
 * <p>Every mandatory argument (all but {@code tradeName}) rejects {@code null} with {@link
 * NullPointerException}: a null reference is a programming error, not a domain condition
 * (ADR-0019, point 6).
 */
public final class Institution {

    public static final int MAX_NAME_LENGTH = 200;

    private final InstitutionId id;
    private final String legalName;
    private final String tradeName;
    private boolean active;

    private Institution(InstitutionId id, String legalName, String tradeName) {
        this.id = id;
        this.legalName = legalName;
        this.tradeName = tradeName;
        this.active = true;
    }

    /**
     * Creates a new, active {@link Institution}.
     *
     * @param id the institution's identifier; never {@code null}
     * @param legalName the institution's legal name; never {@code null}, stripped of border
     *     spaces, non-blank, at most {@value #MAX_NAME_LENGTH} code points
     * @param tradeName the institution's optional trade name; {@code null} means "no trade name",
     *     otherwise stripped of border spaces, non-blank, at most {@value #MAX_NAME_LENGTH} code
     *     points
     * @throws NullPointerException if {@code id} or {@code legalName} is {@code null}
     * @throws InvalidInstitutionException if {@code legalName} or a non-null {@code tradeName}
     *     violates its rule
     */
    public static Institution create(InstitutionId id, String legalName, String tradeName) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(legalName, "legalName");

        String normalizedLegalName = requireValidName(legalName,
                InvalidInstitutionException::legalNameBlank,
                InvalidInstitutionException::legalNameTooLong);
        String normalizedTradeName = tradeName == null ? null
                : requireValidName(tradeName, InvalidInstitutionException::tradeNameBlank,
                        InvalidInstitutionException::tradeNameTooLong);

        return new Institution(id, normalizedLegalName, normalizedTradeName);
    }

    private static String requireValidName(String rawName,
            Supplier<InvalidInstitutionException> blankError,
            Supplier<InvalidInstitutionException> tooLongError) {
        String stripped = rawName.strip();
        if (stripped.isEmpty()) {
            throw blankError.get();
        }
        if (stripped.codePointCount(0, stripped.length()) > MAX_NAME_LENGTH) {
            throw tooLongError.get();
        }
        return stripped;
    }

    public InstitutionId id() {
        return id;
    }

    public String legalName() {
        return legalName;
    }

    public String tradeName() {
        return tradeName;
    }

    public boolean isActive() {
        return active;
    }
}

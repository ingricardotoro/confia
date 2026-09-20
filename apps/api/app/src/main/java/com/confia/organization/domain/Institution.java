package com.confia.organization.domain;

import com.confia.kernel.InstitutionId;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Aggregate root of the {@code organization} capability (docs/02-modelo-de-dominio.md §3.1;
 * ADR-0009, ADR-0017). An entity with identity, not a value object: two institutions with the
 * same {@link #id()} are the same institution regardless of their other attributes (design.md,
 * decision 5).
 *
 * <p>PR B1 constructed the minimal aggregate: {@link #id()}, {@link #legalName()}, the optional
 * {@link #tradeName()}, {@link #isActive()} and the {@link #activate()}/{@link #deactivate()}
 * transitions. PR B2 adds {@code rtn}, {@code address}, {@code defaultCurrency}, {@code locale}
 * and {@code timezone} one at a time, growing {@link #create} to its final eight-parameter
 * signature (design.md, "Pronóstico de tamaño por corte").
 *
 * <p>Every mandatory argument (all but {@code tradeName}) rejects {@code null} with {@link
 * NullPointerException}: a null reference is a programming error, not a domain condition
 * (ADR-0019, point 6).
 */
public final class Institution {

    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_RTN_DIGITS = 20;

    private static final Pattern RTN_PATTERN = Pattern.compile("^[0-9]{1," + MAX_RTN_DIGITS + "}$");

    private final InstitutionId id;
    private final String legalName;
    private final String tradeName;
    private final String rtn;
    private boolean active;

    private Institution(InstitutionId id, String legalName, String tradeName, String rtn) {
        this.id = id;
        this.legalName = legalName;
        this.tradeName = tradeName;
        this.rtn = rtn;
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
     * @param rtn the institution's tax identification number; never {@code null}, between 1 and
     *     {@value #MAX_RTN_DIGITS} ASCII digits, no separators (a technical guard, not a fiscal
     *     rule — the exact SAR format is deliberately not invented, design.md decision 7)
     * @throws NullPointerException if {@code id}, {@code legalName} or {@code rtn} is {@code null}
     * @throws InvalidInstitutionException if {@code legalName}, a non-null {@code tradeName} or
     *     {@code rtn} violates its rule
     */
    public static Institution create(InstitutionId id, String legalName, String tradeName,
            String rtn) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(legalName, "legalName");
        Objects.requireNonNull(rtn, "rtn");

        String normalizedLegalName = requireValidName(legalName,
                InvalidInstitutionException::legalNameBlank,
                InvalidInstitutionException::legalNameTooLong);
        String normalizedTradeName = tradeName == null ? null
                : requireValidName(tradeName, InvalidInstitutionException::tradeNameBlank,
                        InvalidInstitutionException::tradeNameTooLong);
        if (!RTN_PATTERN.matcher(rtn).matches()) {
            throw InvalidInstitutionException.rtnInvalid();
        }

        return new Institution(id, normalizedLegalName, normalizedTradeName, rtn);
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

    public String rtn() {
        return rtn;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Activates an inactive institution. Not idempotent on purpose: a caller that activates an
     * already active institution has a defect, not a valid operation.
     *
     * @throws InstitutionStateException if this institution is already active
     */
    public void activate() {
        if (active) {
            throw InstitutionStateException.alreadyActive();
        }
        active = true;
    }

    /**
     * Deactivates an active institution. Not idempotent on purpose: a caller that deactivates an
     * already inactive institution has a defect, not a valid operation.
     *
     * @throws InstitutionStateException if this institution is already inactive
     */
    public void deactivate() {
        if (!active) {
            throw InstitutionStateException.alreadyInactive();
        }
        active = false;
    }

    /** Equality and hash code are by {@link #id()} alone: an entity, not a value object. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Institution institution)) {
            return false;
        }
        return id.equals(institution.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

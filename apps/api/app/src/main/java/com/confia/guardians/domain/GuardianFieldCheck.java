package com.confia.guardians.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Judges the raw text of a row of a bulk import of guardians with the rules of the registry and says, for each field
 * that breaks one, the stable reason (never the value). It exists because the domain objects refuse a bad value with
 * one error and no field, and an import has to tell the person which field of which line to fix. It restates no rule:
 * the lengths come from {@link Names} and {@link Guardian}, the formats are those of {@link Guardian#email}, {@link
 * Guardian#phone}, {@link NationalId}, {@link Rtn} and {@link PreferredChannel}. Nothing here keeps or returns a
 * value.
 *
 * <p>An import is stricter than the hand registration in one respect only: a control character (a tab or a line break
 * inside a quoted cell, for example) is refused as {@code invalid-format}, because such a cell is a spreadsheet
 * artefact and never a name.
 */
public final class GuardianFieldCheck {

    public static final String REQUIRED = "required";
    public static final String INVALID_FORMAT = "invalid-format";
    public static final String TOO_LONG = "too-long";

    /** The longest reference a source system may give a guardian (the width of the column). */
    public static final int MAX_EXTERNAL_REF_LENGTH = 64;
    private static final int MAX_NATIONAL_ID_LENGTH = 20;
    private static final int MAX_RTN_LENGTH = 20;

    /** A field that breaks a rule and the stable reason; never the rejected value. */
    public record Problem(String field, String reason) {
    }

    private GuardianFieldCheck() {
    }

    /**
     * The problems of a row. The reference and the two names are required; the document, the RTN, the e-mail, the
     * phone, the address and the channel may be blank (no value; the channel is then {@code NONE}).
     */
    public static List<Problem> problems(String externalRef, String firstName, String lastName, String nationalId,
            String rtn, String email, String phone, String address, String preferredChannel) {
        List<Problem> problems = new ArrayList<>();
        text("legacyId", externalRef, MAX_EXTERNAL_REF_LENGTH, true, problems);
        text("firstName", firstName, Names.MAX_NAME_LENGTH, true, problems);
        text("lastName", lastName, Names.MAX_NAME_LENGTH, true, problems);
        if (text("nationalId", nationalId, MAX_NATIONAL_ID_LENGTH, false, problems)
                && !isBlank(nationalId) && !accepts(() -> NationalId.of(nationalId))) {
            problems.add(new Problem("nationalId", INVALID_FORMAT));
        }
        if (text("rtn", rtn, MAX_RTN_LENGTH, false, problems) && !isBlank(rtn) && !accepts(() -> Rtn.of(rtn))) {
            problems.add(new Problem("rtn", INVALID_FORMAT));
        }
        if (text("email", email, Guardian.MAX_EMAIL_LENGTH, false, problems) && !isBlank(email)
                && !accepts(() -> Guardian.email(email))) {
            problems.add(new Problem("email", INVALID_FORMAT));
        }
        if (text("phone", phone, Guardian.MAX_PHONE_LENGTH, false, problems) && !isBlank(phone)
                && !accepts(() -> Guardian.phone(phone))) {
            problems.add(new Problem("phone", INVALID_FORMAT));
        }
        text("address", address, Guardian.MAX_ADDRESS_LENGTH, false, problems);
        if (!isBlank(preferredChannel) && (hasControl(preferredChannel)
                || !accepts(() -> PreferredChannel.parseOrNone(preferredChannel.strip())))) {
            problems.add(new Problem("preferredChannel", INVALID_FORMAT));
        }
        return problems;
    }

    /** The reference as stored: stripped. Only for a row that has no problem. */
    public static String externalRef(String raw) {
        return Names.required(raw, MAX_EXTERNAL_REF_LENGTH);
    }

    /** An optional cell of a row without problems: stripped, or null when the cell is blank or absent. */
    public static String optionalOf(String raw) {
        return isBlank(raw) ? null : raw.strip();
    }

    /**
     * Checks the presence and the length of a text and records the problem if any.
     *
     * @return {@code true} when the text has no problem of this kind, so its own format can be checked
     */
    private static boolean text(String field, String value, int maxLength, boolean required, List<Problem> problems) {
        if (isBlank(value)) {
            if (required) {
                problems.add(new Problem(field, REQUIRED));
            }
            return !required;
        }
        if (hasControl(value)) {
            problems.add(new Problem(field, INVALID_FORMAT));
            return false;
        }
        if (value.strip().length() > maxLength) {
            problems.add(new Problem(field, TOO_LONG));
            return false;
        }
        return true;
    }

    private static boolean accepts(Runnable check) {
        try {
            check.run();
            return true;
        } catch (InvalidGuardianException e) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}

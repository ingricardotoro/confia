package com.confia.guardians.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A payment guardian of an institution. The national identity document and the RTN are not members: the
 * aggregate only knows whether there is one, and no character of either is kept in plain. The code never changes
 * after the creation. A guardian is never deleted: it is made {@link GuardianStatus#INACTIVE}. The e-mail, the
 * phone and the address are sensitive: they are validated here and kept out of the audit trail and of every log.
 */
public record Guardian(UUID id, String guardianCode, String firstName, String lastName, boolean hasNationalId,
        boolean hasRtn, String email, String phone, String address, PreferredChannel preferredChannel,
        GuardianStatus status, Instant createdAt, Instant updatedAt) {

    static final int MAX_EMAIL_LENGTH = 254;
    static final int MAX_ADDRESS_LENGTH = 300;

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    /** An optional plus and 7 to 20 characters in all: digits, spaces, parentheses and hyphens inside; it may open with a parenthesis. */
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9(][0-9 ()\\-]{5,18}[0-9]");

    public Guardian {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(preferredChannel, "preferredChannel");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        guardianCode = GuardianCode.checked(guardianCode);
        firstName = Names.required(firstName, Names.MAX_NAME_LENGTH);
        lastName = Names.required(lastName, Names.MAX_NAME_LENGTH);
        email = email(email);
        phone = phone(phone);
        address = Names.optional(address, MAX_ADDRESS_LENGTH);
    }

    /** A new, active guardian; the optional members may be {@code null}. */
    public static Guardian create(UUID id, String guardianCode, String firstName, String lastName,
            NationalId nationalId, Rtn rtn, String email, String phone, String address, String preferredChannel,
            Instant now) {
        return new Guardian(id, guardianCode, firstName, lastName, nationalId != null, rtn != null, email, phone,
                address, PreferredChannel.parseOrNone(preferredChannel), GuardianStatus.ACTIVE, now, now);
    }

    /** Replaces every editable member; the identifier, the code and the creation instant stay. */
    public Guardian edit(String newFirstName, String newLastName, ProtectedValueChange<NationalId> document,
            ProtectedValueChange<Rtn> newRtn, String newEmail, String newPhone, String newAddress,
            String newPreferredChannel, String newStatus, Instant now) {
        return new Guardian(id, guardianCode, newFirstName, newLastName, document.presentAfter(hasNationalId),
                newRtn.presentAfter(hasRtn), newEmail, newPhone, newAddress,
                PreferredChannel.parseOrNone(newPreferredChannel), GuardianStatus.parse(newStatus), createdAt, now);
    }

    public boolean active() {
        return status == GuardianStatus.ACTIVE;
    }

    private static String email(String value) {
        String text = Names.optional(value, MAX_EMAIL_LENGTH);
        if (text != null && !EMAIL.matcher(text).matches()) {
            throw new InvalidGuardianException();
        }
        return text;
    }

    private static String phone(String value) {
        String text = Names.optional(value, 20);
        if (text != null && !PHONE.matcher(text).matches()) {
            throw new InvalidGuardianException();
        }
        return text;
    }

    /** Personal data never reaches a log through this method. */
    @Override
    public String toString() {
        return "Guardian[id=" + id + "]";
    }
}

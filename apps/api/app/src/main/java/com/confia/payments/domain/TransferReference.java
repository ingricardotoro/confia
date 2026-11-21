package com.confia.payments.domain;

/**
 * The bank reference of a transfer (4 to 40 characters) with the bank it came from (1 to 80). The pair identifies the transfer:
 * it is not repeated among the payments that were not rejected, whatever its capitals. The reference is a financial identifier
 * that never reaches a log whole: the audit trail and {@code toString} show it masked.
 */
public record TransferReference(String reference, String bankName) {

    public static final int REFERENCE_MIN = 4;
    public static final int REFERENCE_MAX = 40;
    public static final int BANK_MAX = 80;

    public TransferReference {
        if (reference == null || bankName == null) {
            throw new InvalidPaymentsValueException();
        }
    }

    public static TransferReference of(String reference, String bankName) {
        return new TransferReference(FreeText.of(reference, REFERENCE_MIN, REFERENCE_MAX),
                FreeText.of(bankName, 1, BANK_MAX));
    }

    /** The reference with all but its last characters hidden (four, or two when it is short), for the audit trail. */
    public String masked() {
        int shown = reference.length() >= 8 ? 4 : 2;
        return "****" + reference.substring(reference.length() - shown);
    }

    @Override
    public String toString() {
        return "TransferReference[reference=" + masked() + "]";
    }
}

package com.confia.payments.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The body of {@code POST /api/v1/payments}. The institution and the cashier are the ones of the token and are never members of
 * the body. A transfer carries its bank reference (4 to 40 characters) and its bank (1 to 80); cash takes neither, and a field the
 * method does not take is refused. The notes and the reference may identify a person or a bank operation: they never reach a log
 * through {@code toString}.
 */
public record RegisterPaymentRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED) @NotNull UUID studentId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "The guardian who paid, if the cashier knows it; stored as given")
        UUID guardianId,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"CASH", "BANK_TRANSFER"})
        @NotNull @Pattern(regexp = "^(CASH|BANK_TRANSFER)$") String method,
        @Schema(requiredMode = RequiredMode.REQUIRED) @NotNull @Valid PaymentMoney amount,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "date",
                description = "The day the money was received; not in the future")
        @NotNull LocalDate receivedOn,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, maxLength = 40,
                description = "Bank reference of the transfer, 4 to 40 characters; required for a transfer, refused for cash")
        @Size(max = 40) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String reference,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, maxLength = 80,
                description = "Bank of the transfer; required for a transfer, refused for cash")
        @Size(max = 80) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String bankName,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, maxLength = 500)
        @Size(max = 500) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String notes) {

    @Override
    public String toString() {
        return "RegisterPaymentRequest[studentId=" + studentId + ", method=" + method + "]";
    }
}

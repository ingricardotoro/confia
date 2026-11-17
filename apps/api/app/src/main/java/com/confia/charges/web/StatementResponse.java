package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The derived statement of a student: one section per currency. Every amount is computed by the server and travels as {@code
 * { amount, currency }}; the balance is positive when the student owes and negative when the student has a credit balance.
 */
@Schema(name = "StudentStatement")
public record StatementResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID studentId,
        @Schema(requiredMode = RequiredMode.REQUIRED) String studentCode,
        @Schema(requiredMode = RequiredMode.REQUIRED) String firstName,
        @Schema(requiredMode = RequiredMode.REQUIRED) String lastName,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "First effective date included") LocalDate from,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Last effective date included") LocalDate to,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "One section per currency the student has, or only the one asked for") List<Section> sections) {

    /** One currency of the statement. */
    @Schema(name = "StudentStatementSection")
    public record Section(
            @Schema(requiredMode = RequiredMode.REQUIRED, pattern = "^[A-Z]{3}$") String currency,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    description = "Balance on the day before from: debits minus credits of everything before") AccrualMoney openingBalance,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    description = "Balance on to: debits minus credits of everything up to and including that date") AccrualMoney closingBalance,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Lines of the whole period, not only the page") long totalLines,
            @Schema(requiredMode = RequiredMode.REQUIRED) int page,
            @Schema(requiredMode = RequiredMode.REQUIRED) int size,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    description = "Oldest first: effective date, posting instant, transaction, line") List<Line> lines) {
    }

    /** One entry of the student sub-account. */
    @Schema(name = "StudentStatementLine")
    public record Line(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID transactionId,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"CHARGE", "DISCOUNT", "PAYMENT", "LATE_FEE", "CREDIT_NOTE", "REVERSAL",
                            "ADJUSTMENT", "WRITE_OFF"}) String kind,
            @Schema(requiredMode = RequiredMode.REQUIRED) LocalDate effectiveOn,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant postedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) int lineNumber,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                    description = "Present when the transaction carries one; always for a reversal or an adjustment") String reason,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                    description = "For a reversal, the transaction it reverses") UUID reversesTransactionId,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Zero when the entry is a credit") AccrualMoney debit,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Zero when the entry is a debit") AccrualMoney credit,
            @Schema(requiredMode = RequiredMode.REQUIRED,
                    description = "Running balance after this line, computed by the server") AccrualMoney balance,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                    description = "The charge behind the line; absent when the transaction is not a charge") Charge charge) {
    }

    /** The charge that produced a line. */
    @Schema(name = "StudentStatementCharge")
    public record Charge(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID chargeId,
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID paymentConceptId,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String conceptCode,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String conceptName,
            @Schema(requiredMode = RequiredMode.REQUIRED) String conceptCategory,
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "YYYY-MM") String periodLabel,
            @Schema(requiredMode = RequiredMode.REQUIRED) LocalDate periodStart,
            @Schema(requiredMode = RequiredMode.REQUIRED) LocalDate periodEnd,
            @Schema(requiredMode = RequiredMode.REQUIRED) LocalDate dueOn,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"AUTOMATIC", "MANUAL"}) String origin) {
    }
}

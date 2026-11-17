package com.confia.charges.web;

import com.confia.charges.application.StatementView;
import com.confia.charges.application.ViewStudentStatement;
import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.ratelimit.RateLimitKey;
import com.confia.shared.web.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The derived statement of a student under {@code /api/v1/charges/students/{studentId}/statement}
 * (docs/cortes/ledger-account-statement.md). A read: the permission {@code charges:read} is decided before the controller
 * runs, by the permission decider over {@code PermissionedEndpoints}. The institution is the one of the token; a student of
 * another institution looks the same as one that does not exist (404). Every amount is computed and rounded by the server.
 */
@RestController
@RequestMapping("/api/v1/charges")
public class StatementController {

    private static final String TAG = "charges-statement";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ViewStudentStatement statement;

    public StatementController(ViewStudentStatement statement) {
        this.statement = statement;
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/students/{studentId}/statement", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getStudentStatement", tags = TAG, summary = "Reads the derived statement of a student",
            description = "What the student owes, derived from the ledger entries of its receivable sub-accounts and "
                    + "enriched with the charge behind each line: for each currency the opening balance, a page of lines "
                    + "with their running balance, and the closing balance. Positive means the student owes; negative "
                    + "is a credit balance. Without dates it covers the last twelve months. The reading is audited. "
                    + "Needs the permission charges:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:read")))
    @ApiResponse(responseCode = "200", description = "The statement",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StatementResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "A date or the currency is not valid, from is after to, or the page is out of range "
                    + "(validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold charges:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The student does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public StatementResponse get(
            @PathVariable("studentId") UUID studentId,
            @Parameter(description = "Only this currency (HNL or USD); absent means every currency the student has")
            @RequestParam(name = "currency", required = false) @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @Parameter(description = "First effective date included (yyyy-MM-dd); twelve months before to when absent")
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last effective date included (yyyy-MM-dd); today in Honduras when absent")
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Page of lines of each currency, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Lines per page, 1 to 100; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(100) int size) {
        StatementView view = statement.view(RequestContext.actor(), RequestContext.requestId(), studentId,
                ViewStudentStatement.parseCurrency(currency), from, to, page, size);
        return toResponse(view);
    }

    static StatementResponse toResponse(StatementView view) {
        return new StatementResponse(view.studentId(), view.studentCode(), view.firstName(), view.lastName(),
                view.from(), view.to(), view.sections().stream().map(StatementController::toResponse).toList());
    }

    private static StatementResponse.Section toResponse(StatementView.Section section) {
        return new StatementResponse.Section(section.currency().name(), AccrualMoney.of(section.openingBalance()),
                AccrualMoney.of(section.closingBalance()), section.totalLines(), section.page(), section.size(),
                section.lines().stream().map(StatementController::toResponse).toList());
    }

    private static StatementResponse.Line toResponse(StatementView.Line line) {
        StatementView.ChargeInfo charge = line.charge();
        return new StatementResponse.Line(line.transactionId(), line.kind(), line.effectiveOn(), line.postedAt(),
                line.lineNumber(), line.reason(), line.reversesTransactionId(), AccrualMoney.of(line.debit()),
                AccrualMoney.of(line.credit()), AccrualMoney.of(line.balance()),
                charge == null ? null : new StatementResponse.Charge(charge.chargeId(), charge.paymentConceptId(),
                        charge.conceptCode(), charge.conceptName(), charge.conceptCategory(), charge.periodLabel(),
                        charge.periodStart(), charge.periodEnd(), charge.dueOn(), charge.origin()));
    }
}

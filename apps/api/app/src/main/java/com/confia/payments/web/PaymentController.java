package com.confia.payments.web;

import com.confia.payments.application.ConfirmPayment;
import com.confia.payments.application.GetPayment;
import com.confia.payments.application.ListPayments;
import com.confia.payments.application.PageRequest;
import com.confia.payments.application.PaymentFilter;
import com.confia.payments.application.RegisterPayment;
import com.confia.payments.application.RejectPayment;
import com.confia.payments.application.RejectedPaymentFieldException;
import com.confia.shared.web.problem.FieldViolation;
import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemCodeException;
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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The routes of the payments under {@code /api/v1/payments} (docs/cortes/payments-register.md). The permission each route needs is
 * decided before the controller runs, by the permission decider over {@code PermissionedEndpoints}: {@code payments:create} to
 * register, {@code payments:read} to list and read, and {@code payments:approve} to confirm and reject a declared transfer. The
 * institution and the cashier are the ones of the token, never a member of a request. A payment of another institution, an unknown
 * one and (for a reader limited to its own) another cashier's payment look the same: 404. Every amount is rounded by the server,
 * and the allocation to the charges and the credit that remains are computed by the server.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private static final String TAG = "payments";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;
    private static final String KEY_DESCRIPTION = "1 to 128 visible characters, unique per request";

    private final RegisterPayment registerPayment;
    private final ConfirmPayment confirmPayment;
    private final RejectPayment rejectPayment;
    private final ListPayments listPayments;
    private final GetPayment getPayment;

    public PaymentController(RegisterPayment registerPayment, ConfirmPayment confirmPayment, RejectPayment rejectPayment,
            ListPayments listPayments, GetPayment getPayment) {
        this.registerPayment = registerPayment;
        this.confirmPayment = confirmPayment;
        this.rejectPayment = rejectPayment;
        this.listPayments = listPayments;
        this.getPayment = getPayment;
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "registerPayment", tags = TAG, summary = "Registers a payment of a student",
            description = "Registers a payment in cash or a declared bank transfer. Cash needs an open cash session of the "
                    + "cashier (INV-20): it is born confirmed, posted to the ledger, applied to the pending charges of the "
                    + "student from the oldest to the newest and added to the expected cash of the session, in one database "
                    + "transaction; what is left after every pending charge is covered stays as a credit (unallocated). A "
                    + "transfer is stored as declared: it touches no balance, no ledger and no charge until a person "
                    + "authorized confirms it. The amount is greater than zero, in HNL, with at most two decimals (INV-19). "
                    + "The Idempotency-Key header is required and stored permanently on the payment: the same key and the "
                    + "same body answer the same payment, the same key with another body or by another staff member answers "
                    + "422 idempotency-payload-mismatch. Needs the permission payments:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission",
                    value = "payments:create")))
    @ApiResponse(responseCode = "201", description = "The payment (or the payment the key already produced)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PaymentResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the Idempotency-Key is not valid (validation-failed, idempotency-key-missing)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold payments:create, or the Origin is missing or not allowed "
                    + "(forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The student does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "Cash without an open session of the cashier (cash-session-not-open), the payment accounts are "
                    + "incomplete (posting-accounts-incomplete), or the bank and the reference of the transfer are in use "
                    + "(duplicate-transfer-reference)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "422",
            description = "The Idempotency-Key was used for another request (idempotency-payload-mismatch)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public PaymentResponse register(
            @Parameter(required = true, description = KEY_DESCRIPTION)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RegisterPaymentRequest request) {
        try {
            return PaymentMapper.toResponse(registerPayment.register(RequestContext.actor(), RequestContext.requestId(),
                    idempotencyKey, new RegisterPayment.Command(request.studentId(), request.guardianId(), request.method(),
                            request.amount().amount(), request.receivedOn(), request.reference(), request.bankName(),
                            request.notes())));
        } catch (RejectedPaymentFieldException e) {
            // A rule about one field of the body answers validation-failed and names that field, so the screen shows it there.
            throw new ProblemCodeException(ProblemCode.VALIDATION_FAILED, List.of(new FieldViolation(e.field(), e.reason())));
        }
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listPayments", tags = TAG, summary = "Lists payments",
            description = "One page of payments, filtered by student, status (declared, confirmed or rejected), method and the "
                    + "days on which the money was received, sorted by registeredAt, receivedOn or amount (sort=field or "
                    + "field,asc|desc; registeredAt descending by default). A cashier sees only the payments it registered. "
                    + "page counts from 0, size defaults to 50 and is at most 200. Needs the permission payments:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission",
                    value = "payments:read")))
    @ApiResponse(responseCode = "200", description = "One page of payments",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PaymentPageResponse.class)))
    @ApiResponse(responseCode = "400", description = "A parameter is out of range (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold payments:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public PaymentPageResponse list(
            @Parameter(description = "Only the payments of this student")
            @RequestParam(name = "studentId", required = false) UUID studentId,
            @Parameter(description = "Only payments in this status")
            @RequestParam(name = "status", required = false) @Pattern(regexp = "declared|confirmed|rejected") String status,
            @Parameter(description = "Only payments of this method")
            @RequestParam(name = "method", required = false) @Pattern(regexp = "CASH|BANK_TRANSFER") String method,
            @Parameter(description = "Received on or after this day (yyyy-MM-dd)")
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Received on or before this day (yyyy-MM-dd)")
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "registeredAt, receivedOn or amount, with ,asc or ,desc; registeredAt,desc when absent")
            @RequestParam(name = "sort", required = false) String sort,
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size) {
        PaymentFilter filter = new PaymentFilter(studentId, status, method, from, to, null);
        PageRequest request = PageRequest.of(page, size, sort, PaymentFilter.SORT_FIELDS, "registeredAt", false);
        return PaymentMapper.toResponse(listPayments.list(RequestContext.actor(), RequestContext.requestId(), filter,
                request));
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getPayment", tags = TAG, summary = "Reads a payment with its allocations",
            description = "One payment with its decision and the charges it was applied to (charge, concept, period and "
                    + "applied amount) and the credit that remained. A cashier reads only the payments it registered. "
                    + "Needs the permission payments:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission",
                    value = "payments:read")))
    @ApiResponse(responseCode = "200", description = "The payment",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PaymentResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold payments:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The payment does not exist for the reader in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public PaymentResponse get(@PathVariable("id") UUID id) {
        return PaymentMapper.toResponse(getPayment.get(RequestContext.actor(), RequestContext.requestId(), id));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/{id}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "confirmPayment", tags = TAG, summary = "Confirms a declared transfer",
            description = "Posts a declared bank transfer to the ledger (debit the bank, credit the receivable of the student) "
                    + "and applies it to the charges that are pending at this moment, in one database transaction. Whoever "
                    + "registered the transfer cannot confirm it. The Idempotency-Key header is required and stored "
                    + "permanently on the decision. Needs the permission payments:approve.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission",
                    value = "payments:approve")))
    @ApiResponse(responseCode = "200", description = "The confirmed payment (or the one the key already confirmed)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PaymentResponse.class)))
    @ApiResponse(responseCode = "400", description = "The Idempotency-Key is not valid (idempotency-key-missing)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold payments:approve, the Origin is missing or not allowed "
                    + "(forbidden, csrf-invalid), or the actor registered the payment (self-approval-forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The payment does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "It is not a declared transfer (payment-not-declared), or the payment accounts are incomplete "
                    + "(posting-accounts-incomplete)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "422",
            description = "The Idempotency-Key was used for another request (idempotency-payload-mismatch)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public PaymentResponse confirm(@PathVariable("id") UUID id,
            @Parameter(required = true, description = KEY_DESCRIPTION)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return PaymentMapper.toResponse(confirmPayment.confirm(RequestContext.actor(), RequestContext.requestId(),
                idempotencyKey, id));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "rejectPayment", tags = TAG, summary = "Rejects a declared transfer",
            description = "Rejects a declared bank transfer with a reason of 20 to 500 characters: it leaves no ledger entry and "
                    + "no allocation, and frees its bank and reference. The reason is kept only in the decision. Whoever "
                    + "registered the transfer cannot reject it. The Idempotency-Key header is required and stored "
                    + "permanently on the decision. Needs the permission payments:approve.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission",
                    value = "payments:approve")))
    @ApiResponse(responseCode = "200", description = "The rejected payment (or the one the key already rejected)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PaymentResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the Idempotency-Key is not valid (validation-failed, idempotency-key-missing)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold payments:approve, the Origin is missing or not allowed "
                    + "(forbidden, csrf-invalid), or the actor registered the payment (self-approval-forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The payment does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "It is not a declared transfer (payment-not-declared)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "422",
            description = "The Idempotency-Key was used for another request (idempotency-payload-mismatch)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public PaymentResponse reject(@PathVariable("id") UUID id,
            @Parameter(required = true, description = KEY_DESCRIPTION)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RejectPaymentRequest request) {
        return PaymentMapper.toResponse(rejectPayment.reject(RequestContext.actor(), RequestContext.requestId(),
                idempotencyKey, id, request.reason()));
    }
}

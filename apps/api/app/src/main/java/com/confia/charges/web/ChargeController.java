package com.confia.charges.web;

import com.confia.charges.application.ChargeFilter;
import com.confia.charges.application.ChargeRow;
import com.confia.charges.application.ChargeVoidView;
import com.confia.charges.application.ListStudentCharges;
import com.confia.charges.application.Page;
import com.confia.charges.application.PageRequest;
import com.confia.charges.application.StudentChargeView;
import com.confia.charges.application.VoidCharge;
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
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The routes of the charges themselves under {@code /api/v1/charges} (docs/cortes/charges-void-and-integrity.md): the void of
 * a charge and the list of the charges of a student. The permission each route needs is decided before the controller runs, by
 * the permission decider over {@code PermissionedEndpoints}: {@code charges:void} for the void, {@code charges:read} for the
 * list. The institution is the one of the token, never a member of a request; a charge or a student of another institution
 * looks the same as one that does not exist (404). Every amount is rounded by the server.
 */
@RestController
@RequestMapping("/api/v1/charges")
public class ChargeController {

    private static final String TAG = "charges";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final VoidCharge voidCharge;
    private final ListStudentCharges listStudentCharges;

    public ChargeController(VoidCharge voidCharge, ListStudentCharges listStudentCharges) {
        this.voidCharge = voidCharge;
        this.listStudentCharges = listStudentCharges;
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/{chargeId}/void", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "voidCharge", tags = TAG, summary = "Voids a charge",
            description = "Voids the charge with a reason of 20 to 500 characters: the ledger posts the exact mirror of the "
                    + "transaction of the charge and a record of the void is inserted, in one database transaction. "
                    + "Nothing is deleted or edited, and a voided charge is not accrued again. The Idempotency-Key header "
                    + "is required and stored permanently on the void: the same key and the same body answer the same "
                    + "void, the same key with another body answers 422 idempotency-payload-mismatch, and another key on a "
                    + "charge that is already voided answers 409 charge-already-voided. Needs the permission charges:void.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:void")))
    @ApiResponse(responseCode = "200", description = "The void (or the void the key already produced)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ChargeVoidResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the Idempotency-Key is not valid (validation-failed, idempotency-key-missing)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold charges:void, or the Origin is missing or not allowed "
                    + "(forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The charge does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The charge was already voided, or the ledger refuses to reverse it "
                    + "(charge-already-voided, charge-not-voidable)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "422",
            description = "The Idempotency-Key was used for another request (idempotency-payload-mismatch)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ChargeVoidResponse voidCharge(@PathVariable("chargeId") UUID chargeId,
            @Parameter(required = true, description = "1 to 128 visible characters, unique per request")
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ChargeVoidBody request) {
        ChargeVoidView voided = voidCharge.voidCharge(RequestContext.actor(), RequestContext.requestId(),
                idempotencyKey, chargeId, request.reason());
        return new ChargeVoidResponse(voided.id(), voided.chargeId(), voided.status(),
                voided.reversalTransactionId(), voided.reason(), voided.voidedBy(), voided.voidedAt());
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/students/{studentId}/charges", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listStudentCharges", tags = TAG, summary = "Lists the charges of a student",
            description = "One page of the charges of the student with their derived state (voided when a void exists), "
                    + "filtered by status and by the first day of the period, sorted by one of periodStart, dueOn or total "
                    + "(sort=field or field,asc|desc; periodStart descending by default). page counts from 0, size defaults "
                    + "to 50 and is at most 200. The reading is audited. Needs the permission charges:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:read")))
    @ApiResponse(responseCode = "200", description = "One page of charges",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StudentChargePageResponse.class)))
    @ApiResponse(responseCode = "400", description = "A parameter is out of range (validation-failed)",
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
    public StudentChargePageResponse list(@PathVariable("studentId") UUID studentId,
            @Parameter(description = "Only charges with this derived status")
            @RequestParam(name = "status", required = false) @Pattern(regexp = "ACTIVE|VOIDED") String status,
            @Parameter(description = "First day of the period, from this date included (yyyy-MM-dd)")
            @RequestParam(name = "periodFrom", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodFrom,
            @Parameter(description = "First day of the period, up to this date included (yyyy-MM-dd)")
            @RequestParam(name = "periodTo", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodTo,
            @Parameter(description = "Only charges with something outstanding (derived from the payments); the order is then the one in "
                    + "which a payment is allocated, unless sort is given")
            @RequestParam(name = "outstanding", required = false) Boolean outstanding,
            @Parameter(description = "periodStart, dueOn or total, with ,asc or ,desc; periodStart,desc when absent, or the "
                    + "allocation order (due date, period, concept code, identifier) when outstanding is true")
            @RequestParam(name = "sort", required = false) String sort,
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size) {
        boolean outstandingOnly = Boolean.TRUE.equals(outstanding);
        ChargeFilter filter = new ChargeFilter(status, periodFrom, periodTo, outstandingOnly);
        PageRequest request = outstandingOnly && (sort == null || sort.isBlank())
                ? new PageRequest(page, size, ListStudentCharges.ALLOCATION_ORDER, true)
                : PageRequest.of(page, size, sort, ChargeFilter.SORT_FIELDS, "periodStart", false);
        Page<StudentChargeView> result = listStudentCharges.list(RequestContext.actor(), RequestContext.requestId(),
                studentId, filter, request);
        return new StudentChargePageResponse(result.items().stream().map(ChargeController::toResponse).toList(),
                result.total(), result.page(), result.size());
    }

    private static StudentChargeResponse toResponse(StudentChargeView view) {
        ChargeRow row = view.charge();
        StudentChargeResponse.VoidInfo voided = row.voided() == null ? null
                : new StudentChargeResponse.VoidInfo(row.voided().voidId(), row.voided().reversalTransactionId(),
                        row.voided().voidedAt(), row.voided().reason());
        return new StudentChargeResponse(row.id(), row.paymentConceptId(), view.conceptCode(), view.conceptName(),
                row.conceptCategory(), row.periodLabel(), row.periodStart(), row.periodEnd(), row.dueOn(), row.origin(),
                AccrualMoney.of(row.base()), AccrualMoney.of(row.benefit()), AccrualMoney.of(row.netBase()),
                row.scholarshipAwardId(), row.scholarshipCode(), AccrualMoney.of(row.tax()), AccrualMoney.of(row.total()),
                AccrualMoney.of(view.paid()), AccrualMoney.of(view.outstanding()), view.settled(), row.statusName(),
                voided);
    }
}

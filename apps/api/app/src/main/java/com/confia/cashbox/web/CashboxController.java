package com.confia.cashbox.web;

import com.confia.cashbox.application.ApproveClose;
import com.confia.cashbox.application.CloseSession;
import com.confia.cashbox.application.GetCurrentSession;
import com.confia.cashbox.application.GetSession;
import com.confia.cashbox.application.ListSessions;
import com.confia.cashbox.application.OpenSession;
import com.confia.cashbox.application.PageRequest;
import com.confia.cashbox.application.RecordMovement;
import com.confia.cashbox.application.SessionFilter;
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
 * The routes of the cash sessions under {@code /api/v1/cashbox} (docs/cortes/cashbox-sessions.md). The permission each route
 * needs is decided before the controller runs, by the permission decider over {@code PermissionedEndpoints}: {@code
 * cashbox:create} to open, move, close and read the current session, {@code cashbox:approve} to approve and {@code
 * cashbox:read} to list and read. The institution and the cashier are the ones of the token, never a member of a request. A
 * session of another institution, an unknown one and another cashier's session (for the routes of the cashier) look the same:
 * 404. Every amount is rounded by the server, and the expected cash and the difference are computed by the server.
 */
@RestController
@RequestMapping("/api/v1/cashbox")
public class CashboxController {

    private static final String TAG = "cashbox";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;
    private static final String KEY_DESCRIPTION = "1 to 128 visible characters, unique per request";

    private final OpenSession openSession;
    private final RecordMovement recordMovement;
    private final CloseSession closeSession;
    private final ApproveClose approveClose;
    private final ListSessions listSessions;
    private final GetSession getSession;
    private final GetCurrentSession getCurrentSession;

    public CashboxController(OpenSession openSession, RecordMovement recordMovement, CloseSession closeSession,
            ApproveClose approveClose, ListSessions listSessions, GetSession getSession,
            GetCurrentSession getCurrentSession) {
        this.openSession = openSession;
        this.recordMovement = recordMovement;
        this.closeSession = closeSession;
        this.approveClose = approveClose;
        this.listSessions = listSessions;
        this.getSession = getSession;
        this.getCurrentSession = getCurrentSession;
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/sessions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "openCashSession", tags = TAG, summary = "Opens the cash session of the cashier",
            description = "Opens the session of the cashier of the token with an opening float of zero or more (INV-22: one "
                    + "open session per cashier). The Idempotency-Key header is required and stored permanently on the "
                    + "session: the same key and the same body answer the same session, the same key with another body "
                    + "answers 422 idempotency-payload-mismatch, and another key while a session is open answers 409 "
                    + "cash-session-already-open. Needs the permission cashbox:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission",
                    value = "cashbox:create")))
    @ApiResponse(responseCode = "201", description = "The open session (or the session the key already produced)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the Idempotency-Key is not valid (validation-failed, idempotency-key-missing)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold cashbox:create, or the Origin is missing or not allowed "
                    + "(forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409", description = "The cashier already has an open session (cash-session-already-open)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "422",
            description = "The Idempotency-Key was used for another request (idempotency-payload-mismatch)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public SessionResponse open(
            @Parameter(required = true, description = KEY_DESCRIPTION)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OpenSessionRequest request) {
        return CashboxMapper.toResponse(openSession.open(RequestContext.actor(), RequestContext.requestId(),
                idempotencyKey, request.openingFloat().amount()));
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/sessions/current", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCurrentCashSession", tags = TAG, summary = "Reads the open session of the cashier",
            description = "The open session of the cashier of the token, with the expected cash and its movements, or 404 "
                    + "when it has none. Needs the permission cashbox:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission",
                    value = "cashbox:create")))
    @ApiResponse(responseCode = "200", description = "The open session",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold cashbox:create (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404", description = "The cashier has no open session (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public SessionResponse current() {
        return CashboxMapper.toResponse(getCurrentSession.current(RequestContext.actor(), RequestContext.requestId()));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/sessions/{sessionId}/movements", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "recordCashMovement", tags = TAG, summary = "Records a movement of cash",
            description = "A deposit, a withdrawal or an adjustment in the open session of the cashier (INV-21). A deposit and "
                    + "a withdrawal carry a positive amount; an adjustment carries a signed amount and notes of 20 to 500 "
                    + "characters. The expected cash never ends below zero. The Idempotency-Key header is required and "
                    + "stored permanently on the movement. Needs the permission cashbox:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission",
                    value = "cashbox:create")))
    @ApiResponse(responseCode = "201", description = "The movement (or the movement the key already produced)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = RecordedMovementResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the Idempotency-Key is not valid (validation-failed, idempotency-key-missing)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold cashbox:create, or the Origin is missing or not allowed "
                    + "(forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The session does not exist for the cashier in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The session is not open, or the movement would leave the expected cash negative "
                    + "(cash-session-not-open, cash-expected-negative)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "422",
            description = "The Idempotency-Key was used for another request (idempotency-payload-mismatch)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public RecordedMovementResponse recordMovement(@PathVariable("sessionId") UUID sessionId,
            @Parameter(required = true, description = KEY_DESCRIPTION)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RecordMovementRequest request) {
        return CashboxMapper.toResponse(recordMovement.record(RequestContext.actor(), RequestContext.requestId(),
                idempotencyKey, sessionId, request.type(), request.amount().amount(), request.notes()));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/sessions/{sessionId}/close", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "closeCashSession", tags = TAG, summary = "Closes the cash session with the declared count",
            description = "The cashier declares the counted cash; the server computes the expected cash and the difference "
                    + "(declared minus expected, INV-23). With no difference the session is closed; with one it needs a "
                    + "reason of 20 to 500 characters and stays pending_approval until another person approves. The "
                    + "Idempotency-Key header is required and stored permanently on the close. Needs the permission "
                    + "cashbox:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission",
                    value = "cashbox:create")))
    @ApiResponse(responseCode = "200", description = "The closed session (or the close the key already produced)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the Idempotency-Key is not valid, or a difference came without its reason "
                    + "(validation-failed, idempotency-key-missing, cash-difference-reason-required)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold cashbox:create, or the Origin is missing or not allowed "
                    + "(forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The session does not exist for the cashier in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409", description = "The session is not open (cash-session-not-open)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "422",
            description = "The Idempotency-Key was used for another request (idempotency-payload-mismatch)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public SessionResponse close(@PathVariable("sessionId") UUID sessionId,
            @Parameter(required = true, description = KEY_DESCRIPTION)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CloseSessionRequest request) {
        return CashboxMapper.toResponse(closeSession.close(RequestContext.actor(), RequestContext.requestId(),
                idempotencyKey, sessionId, request.declaredAmount().amount(), request.differenceReason()));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/sessions/{sessionId}/approve", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "approveCashSessionClose", tags = TAG, summary = "Approves the close of a session with a difference",
            description = "Approves a close that is pending approval. The approver must not be the cashier of the session "
                    + "(segregation of duties): that answers 403 cash-approval-same-actor. A session without a difference "
                    + "or already approved answers 409 cash-session-not-awaiting-approval. The Idempotency-Key header is "
                    + "required and stored permanently on the approval. Needs the permission cashbox:approve.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission",
                    value = "cashbox:approve")))
    @ApiResponse(responseCode = "200", description = "The approved session (or the approval the key already produced)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the Idempotency-Key is not valid (validation-failed, idempotency-key-missing)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold cashbox:approve, the Origin is missing or not allowed, or the approver "
                    + "is the cashier of the session (forbidden, csrf-invalid, cash-approval-same-actor)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The session does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The session is not pending approval (cash-session-not-awaiting-approval)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "422",
            description = "The Idempotency-Key was used for another request (idempotency-payload-mismatch)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public SessionResponse approve(@PathVariable("sessionId") UUID sessionId,
            @Parameter(required = true, description = KEY_DESCRIPTION)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ApproveCloseRequest request) {
        return CashboxMapper.toResponse(approveClose.approve(RequestContext.actor(), RequestContext.requestId(),
                idempotencyKey, sessionId, request.note()));
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listCashSessions", tags = TAG, summary = "Lists the cash sessions",
            description = "One page of the sessions of the institution of the token, filtered by status, cashier and the day "
                    + "they were opened, sorted by openedAt or status (sort=field or field,asc|desc; openedAt descending by "
                    + "default). page counts from 0, size defaults to 50 and is at most 200. Needs the permission "
                    + "cashbox:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission",
                    value = "cashbox:read")))
    @ApiResponse(responseCode = "200", description = "One page of sessions",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SessionPageResponse.class)))
    @ApiResponse(responseCode = "400", description = "A parameter is out of range (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold cashbox:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public SessionPageResponse list(
            @Parameter(description = "Only sessions with this status")
            @RequestParam(name = "status", required = false) @Pattern(regexp = "open|pending_approval|closed")
            String status,
            @Parameter(description = "Only sessions of this cashier (staff account)")
            @RequestParam(name = "cashier", required = false) UUID cashier,
            @Parameter(description = "Opened from this day included (yyyy-MM-dd)")
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Opened up to this day included (yyyy-MM-dd)")
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "openedAt or status, with ,asc or ,desc; openedAt,desc when absent")
            @RequestParam(name = "sort", required = false) String sort,
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size) {
        PageRequest request = PageRequest.of(page, size, sort, SessionFilter.SORT_FIELDS, "openedAt", false);
        return CashboxMapper.toResponse(listSessions.list(RequestContext.actor(), RequestContext.requestId(),
                new SessionFilter(status, cashier, from, to), request));
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/sessions/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCashSession", tags = TAG, summary = "Reads a cash session",
            description = "One session with its movements, its close and its approval: the base of the printable report of "
                    + "the close. Needs the permission cashbox:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission",
                    value = "cashbox:read")))
    @ApiResponse(responseCode = "200", description = "The session",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SessionResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold cashbox:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The session does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public SessionResponse get(@PathVariable("sessionId") UUID sessionId) {
        return CashboxMapper.toResponse(getSession.get(RequestContext.actor(), RequestContext.requestId(), sessionId));
    }
}

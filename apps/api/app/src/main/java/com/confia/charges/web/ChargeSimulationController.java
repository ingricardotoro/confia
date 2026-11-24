package com.confia.charges.web;

import com.confia.charges.application.SimulateCharges;
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
 * The simulator of the charges of a student under {@code /api/v1/charges/plans/{planId}/simulation}
 * (docs/cortes/scholarships-awards.md). A read that writes nothing but the audit entry of the access: the permission {@code
 * charges:read} is decided before the controller runs, by the permission decider over {@code PermissionedEndpoints}. The institution
 * is the one of the token; a plan or a student of another institution looks the same as one that does not exist (404).
 */
@RestController
@RequestMapping("/api/v1/charges")
public class ChargeSimulationController {

    private static final String TAG = "charges-accrual";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final SimulateCharges simulation;

    public ChargeSimulationController(SimulateCharges simulation) {
        this.simulation = simulation;
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/plans/{planId}/simulation", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "simulateChargeAccrual", tags = TAG, summary = "Simulates the charges of a student for a period",
            description = "For a student of the plan and a period of the plan, what the accrual would produce for each concept: "
                    + "the base, the scholarship applied, the net base, the tax and the total. It runs exactly the code of the "
                    + "accrual engine and writes nothing; the consultation is audited because the data are those of a minor. "
                    + "Needs the permission charges:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:read")))
    @ApiResponse(responseCode = "200", description = "What the accrual would produce for the student",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ChargeSimulationResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "A parameter is missing or not valid, or the period is not one of the periods of the plan "
                    + "(validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold charges:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The plan or the student does not exist in the institution of the token, or the student is not "
                    + "enrolled in the target of the plan in that period (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ChargeSimulationResponse simulate(@PathVariable("planId") UUID planId,
            @Parameter(description = "The student whose charges are simulated", required = true)
            @RequestParam(name = "studentId") UUID studentId,
            @Parameter(description = "The first day of a period of the plan (yyyy-MM-dd), as the periods of the plan list them",
                    required = true)
            @RequestParam(name = "period") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate period) {
        SimulateCharges.Simulation result = simulation.simulate(RequestContext.actor(), RequestContext.requestId(), planId,
                studentId, period);
        return new ChargeSimulationResponse(result.planId(), result.studentId(), result.periodStart(),
                result.periodLabel(), result.lines().stream().map(ChargeSimulationController::toResponse).toList());
    }

    private static ChargeSimulationResponse.Line toResponse(SimulateCharges.Line line) {
        return new ChargeSimulationResponse.Line(line.paymentConceptId(), line.outcome().name(), line.error(),
                AccrualMoney.of(line.base()), AccrualMoney.of(line.benefit()), AccrualMoney.of(line.netBase()),
                line.scholarshipAwardId(), line.scholarshipCode(), AccrualMoney.of(line.tax()),
                AccrualMoney.of(line.total()), line.existingChargeId());
    }
}

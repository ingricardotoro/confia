package com.confia.shared.web.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * Registers the two schemas and the security scheme that the API contract already fixes
 * (docs/01-arquitectura.md §7) in every OpenAPI document, whether or not an operation references
 * them (frontend-monorepo-and-contracts-pipeline, design.md decision 4; specs/build-integrity,
 * requirement "Esquemas transversales del contrato presentes desde el primer documento").
 * springdoc publishes only the schemas some operation references.
 *
 * <ul>
 *   <li>{@value #MONEY}: how an amount travels over the API, {@code { amount: string, currency:
 *       string }}, both required (CLAUDE.md, rule 1). This is the wire shape, not the {@code
 *       kernel} value object; the first endpoint that returns an amount gets a DTO of exactly
 *       this shape.
 *   <li>{@value #PROBLEM_DETAIL}: the RFC 9457 error, plus the {@code traceId} §7 requires and the
 *       optional {@code errors} extension of a validation failure, declared since
 *       session-tokens-and-web-layer (design.md decision 13), the first change with an operation.
 *   <li>{@value #BEARER_AUTH}: the HTTP bearer scheme of the access token, which the operations of
 *       the administrative process reference.
 * </ul>
 */
public final class ContractSchemas implements OpenApiCustomizer {

    public static final String MONEY = "Money";
    public static final String PROBLEM_DETAIL = "ProblemDetail";

    /** The name of the security scheme of the bearer access token, which operations reference. */
    public static final String BEARER_AUTH = "bearerAuth";

    /**
     * Up to ten integer digits and four decimals, the {@code NUMERIC(14,4)} every amount is stored
     * as (CLAUDE.md, rule 2), with an optional leading minus: an amount may be negative.
     */
    static final String AMOUNT_PATTERN = "^-?\\d{1,10}(\\.\\d{1,4})?$";

    /** Three upper-case letters, an ISO 4217 code. */
    static final String CURRENCY_PATTERN = "^[A-Z]{3}$";

    @Override
    public void customise(OpenAPI openApi) {
        Components components = openApi.getComponents();
        if (components == null) {
            components = new Components();
            openApi.setComponents(components);
        }
        components.addSchemas(MONEY, money());
        components.addSchemas(PROBLEM_DETAIL, problemDetail());
        components.addSecuritySchemes(BEARER_AUTH, bearerAuth());
    }

    private static Schema<?> money() {
        return new ObjectSchema()
                .description("A monetary amount as it travels over the API: never a JSON number")
                .addProperty("amount", new StringSchema()
                        .pattern(AMOUNT_PATTERN)
                        .description("Decimal amount with up to four decimals, as a string"))
                .addProperty("currency", new StringSchema()
                        .pattern(CURRENCY_PATTERN)
                        .description("ISO 4217 currency code"))
                .required(List.of("amount", "currency"));
    }

    private static Schema<?> problemDetail() {
        return new ObjectSchema()
                .description("An error response in RFC 9457 Problem Details format")
                .addProperty("type", new StringSchema().format("uri"))
                .addProperty("title", new StringSchema())
                .addProperty("status", new IntegerSchema().format("int32"))
                .addProperty("detail", new StringSchema())
                .addProperty("instance", new StringSchema().format("uri"))
                .addProperty("traceId", new StringSchema()
                        .description("Identifier of the request's trace, to correlate with logs"))
                .addProperty("errors", violations());
    }

    /**
     * The {@code errors} member of a {@code validation-failed} response, an RFC 9457 extension: the
     * field and the constraint of each violation, never the rejected value. Optional: only a
     * validation failure carries it (web-edge-foundations design.md, decision 9).
     */
    private static Schema<?> violations() {
        return new ArraySchema()
                .description("The violated constraints of a validation failure, at most fifty")
                .items(new ObjectSchema()
                        .addProperty("field", new StringSchema()
                                .description("Path of the field in the request"))
                        .addProperty("reason", new StringSchema()
                                .description("The constraint that was broken, in kebab case"))
                        .required(List.of("field", "reason")));
    }

    /** The access token travels as {@code Authorization: Bearer <JWT>} (RFC 6750). */
    private static SecurityScheme bearerAuth() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Access token of a staff session");
    }
}

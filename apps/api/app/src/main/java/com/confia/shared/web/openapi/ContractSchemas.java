package com.confia.shared.web.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * Registers the two schemas the API contract already fixes (docs/01-arquitectura.md §7) in every
 * OpenAPI document, before any operation exists to reference them
 * (frontend-monorepo-and-contracts-pipeline, design.md decision 4; specs/build-integrity,
 * requirement "Esquemas transversales del contrato presentes desde el primer documento").
 * springdoc publishes only the schemas some operation references, and today there is none.
 *
 * <ul>
 *   <li>{@value #MONEY}: how an amount travels over the API, {@code { amount: string, currency:
 *       string }}, both required (CLAUDE.md, rule 1). This is the wire shape, not the {@code
 *       kernel} value object; the first endpoint that returns an amount gets a DTO of exactly
 *       this shape.
 *   <li>{@value #PROBLEM_DETAIL}: the RFC 9457 error, plus the {@code traceId} §7 requires.
 * </ul>
 */
public final class ContractSchemas implements OpenApiCustomizer {

    public static final String MONEY = "Money";
    public static final String PROBLEM_DETAIL = "ProblemDetail";

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
                        .description("Identifier of the request's trace, to correlate with logs"));
    }
}

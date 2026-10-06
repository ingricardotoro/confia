package com.confia.shared.web.idempotency;

import com.confia.shared.security.IdempotencyKey;
import com.confia.shared.security.IdempotentExecutor;
import com.confia.shared.security.IdempotentOutcome;
import com.confia.shared.security.IdempotentOutcome.Executed;
import com.confia.shared.security.IdempotentOutcome.Replayed;
import com.confia.shared.security.IdempotentResponse;
import com.confia.shared.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the use case of an {@link IdempotentWrite} method under the key {@link
 * IdempotencyKeyInterceptor} validated, and turns what {@link IdempotentExecutor} returns into the
 * HTTP answer (web-edge-foundations design.md, decision 19). The executor is used as it is: nothing
 * of its contract changes.
 *
 * <p><b>The key.</b> The endpoint is the HTTP method and the route template that matched ({@code
 * POST /api/v1/accounts/{accountId}/payments}), never the concrete path and never anything the
 * client chose, so one key on two operations never collides and the column stays bounded. The
 * payload that is digested is {@code {"body": <body>, "pathVariables": {...}}}: the same key on
 * another resource, which differs only in the path, is a different payload and answers {@code 422},
 * not a replay. The digest of the executor canonicalizes the field order, so {@code {"a":1,"b":2}}
 * and {@code {"b":2,"a":1}} are one payload. The institution comes from the {@link SecurityContext}
 * the caller passes (ADR-0009).
 *
 * <p><b>The outcomes.</b>
 *
 * <ul>
 *   <li>{@link Executed}: the status and the body of the response, with no {@code Idempotent-Replay};
 *   <li>{@link Replayed}, which includes the collision on the primary key ({@code SQLState 23505})
 *       that the executor already resolves by rereading the row of the request that won: the same
 *       status and body with {@code Idempotent-Replay: true}, and the use case does not run;
 *   <li>{@code IdempotencyConflictException} ({@code 409 idempotency-conflict}) and {@code
 *       IdempotencyPayloadMismatchException} ({@code 422 idempotency-payload-mismatch}) are not
 *       caught: they are domain exceptions whose codes are in the catalog, and the one translator
 *       answers them like any other.
 * </ul>
 *
 * <p>Nothing here logs: the key and the body are client input, and the body may hold data of a minor
 * (CLAUDE.md, rules 11 and 15).
 */
public final class IdempotentRequestHandler {

    /** The response header that says the answer is a stored one. */
    public static final String REPLAY_HEADER = "Idempotent-Replay";

    /** The width of the {@code endpoint} column of the marker table. */
    private static final int MAX_ENDPOINT_LENGTH = 200;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final IdempotentExecutor executor;

    public IdempotentRequestHandler(IdempotentExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Only the body and the path variables are digested as the payload: anything else that changes
     * the effect of the write, such as a query parameter, must travel in one of them, or a repeated
     * key would replay the stored answer for a different request.
     *
     * @param request the request of a method that declares {@link IdempotentWrite}
     * @param context the security context of the write, with the institution
     * @param body the parsed request body, or {@code null} when there is none
     * @param useCase the effect, run at most once for a key and its payload
     * @throws IllegalStateException when the handler method does not declare {@link
     *     IdempotentWrite} (no key was validated) or the request did not match a route template
     */
    public ResponseEntity<JsonNode> handle(HttpServletRequest request, SecurityContext context,
            JsonNode body, Supplier<IdempotentResponse> useCase) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(useCase, "useCase");
        if (!(request.getAttribute(IdempotencyKeyInterceptor.KEY_ATTRIBUTE) instanceof String key)) {
            throw new IllegalStateException("no Idempotency-Key was validated for this request: "
                    + "the handler method must declare @IdempotentWrite");
        }
        IdempotentOutcome outcome = executor.execute(context,
                new IdempotencyKey(endpointOf(request), key), payloadOf(request, body), useCase);
        return switch (outcome) {
            case Executed executed -> answer(executed.response(), false);
            case Replayed replayed -> answer(replayed.response(), true);
        };
    }

    private static String endpointOf(HttpServletRequest request) {
        Object template = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(template instanceof String pattern)) {
            throw new IllegalStateException("the request matched no route template");
        }
        String endpoint = request.getMethod() + " " + pattern;
        if (endpoint.length() > MAX_ENDPOINT_LENGTH) {
            throw new IllegalStateException("the endpoint of an idempotent route is longer than "
                    + MAX_ENDPOINT_LENGTH + " characters: " + pattern.length());
        }
        return endpoint;
    }

    private static JsonNode payloadOf(HttpServletRequest request, JsonNode body) {
        ObjectNode payload = JSON.createObjectNode();
        payload.set("body", body == null ? NullNode.instance : body);
        ObjectNode pathVariables = payload.putObject("pathVariables");
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables instanceof Map<?, ?> map) {
            map.forEach((name, value) -> pathVariables.put(String.valueOf(name),
                    String.valueOf(value)));
        }
        return payload;
    }

    private static ResponseEntity<JsonNode> answer(IdempotentResponse response, boolean replayed) {
        ResponseEntity.BodyBuilder answer = ResponseEntity.status(response.responseStatus())
                .contentType(MediaType.APPLICATION_JSON);
        if (replayed) {
            answer.header(REPLAY_HEADER, "true");
        }
        return answer.body(response.responseBody());
    }
}

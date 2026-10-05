package com.confia.shared.web.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** What every error response of the web edge must look like, shared by the chain tests. */
public final class ProblemAssertions {

    private static final String TYPE_BASE = "https://confia.hn/problems/";

    private ProblemAssertions() {
    }

    /**
     * {@code response} is a well-formed Problem Details body for {@code code} with {@code status}:
     * the media type, the five RFC 9457 fields, the trace id as a UUID, and {@code status} equal to
     * the HTTP status. Returns the parsed body for further checks.
     */
    public static JsonNode assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        JsonNode body = HarnessProcess.json(response);
        assertThat(body.get("type").asString()).isEqualTo(TYPE_BASE + code);
        assertThat(body.get("title").asString()).isNotBlank();
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("detail").asString()).isNotBlank();
        assertThat(body.get("instance").asString()).startsWith("/");
        assertThat(UUID.fromString(body.get("traceId").asString())).isNotNull();
        return body;
    }
}

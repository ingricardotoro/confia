package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.harness.HarnessProcess;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Probe S-4 of session-tokens-and-web-layer (design.md decision 13): the responses springdoc adds to
 * an operation on top of the ones its controller declares. The global translator is an
 * {@code @ExceptionHandler} advice, and springdoc turns the handlers of an advice that carry a status
 * of their own into a response of every operation unless {@code
 * springdoc.override-with-generic-response} is {@code false}. The contract snapshot may only hold
 * approved responses, so the property has to be {@code false}, and this probe is the evidence that
 * it matters: the same route, with the same two declared responses, is documented with the property
 * on and off.
 *
 * <p><b>What was observed</b> (2026-10-19, springdoc 3.1.1). With the property on, the translator as
 * it is today adds nothing to the route: {@code ProblemExceptionHandler} answers through its own
 * writer, its handlers are {@code void} and none carries a {@code @ResponseStatus}, so springdoc finds no
 * status to document (the first run of this probe, against the real translator alone, listed
 * exactly {@code 200} and {@code 401} with the property on). The risk is therefore not today's: it is
 * the day a handler of the translator gets a status. The advice of the test controller stands for that
 * day, and with the property on the route then gains a {@code 403} and a {@code 429} that nobody
 * approved. {@code OpenApiContractSnapshotTest} is the second line of defence: it compares the whole
 * document with the approved snapshot.
 */
class GenericResponseOverrideProbeTest {

    private static final String OVERRIDE = "springdoc.override-with-generic-response=";
    private static final String DOCS_ON = "springdoc.api-docs.enabled=true";

    /** The response codes the document gives {@code GET /test/documented}, sorted. */
    private static List<String> responseCodes(String overrideValue) {
        try (HarnessProcess process = HarnessProcess.start(DOCS_ON, OVERRIDE + overrideValue)) {
            HttpResponse<String> docs = process.get("/v3/api-docs");
            assertThat(docs.statusCode()).isEqualTo(200);
            JsonNode responses = HarnessProcess.json(docs).get("paths").get("/test/documented")
                    .get("get").get("responses");
            List<String> codes = new ArrayList<>();
            responses.propertyNames().forEach(codes::add);
            return codes.stream().sorted().toList();
        }
    }

    @Test
    void withTheOverrideOffTheOperationHoldsOnlyTheResponsesItDeclares() {
        assertThat(responseCodes("false")).containsExactly("200", "401");
    }

    @Test
    void withTheOverrideOnTheTranslatorAddsResponsesNobodyApproved() {
        List<String> codes = responseCodes("true");

        assertThat(codes).contains("200", "401", "403", "429");
    }
}

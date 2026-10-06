package com.confia.shared.web.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.confia.shared.security.IdempotencyKey;
import com.confia.shared.security.IdempotentExecutor;
import com.confia.shared.security.SecurityContext;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The branches of {@link IdempotentRequestHandler} that fail closed (web-edge-foundations design.md,
 * decision 19), which a request through the real chain never reaches: a write that runs without a
 * validated key, without a route template, or with an endpoint wider than the marker table. Each
 * refuses before the executor is asked, so no marker is written and the use case never runs.
 * {@code IdempotencyEdgeIT} proves the translated outcomes through the real chain.
 */
class IdempotentRequestHandlerTest {

    private static final SecurityContext CONTEXT =
            new SecurityContext("", "system", "00000000-0000-0000-0000-000000000001", "request");

    private final IdempotentExecutor executor = mock(IdempotentExecutor.class);
    private final IdempotentRequestHandler handler = new IdempotentRequestHandler(executor);
    private final AtomicInteger useCaseRuns = new AtomicInteger();

    private static MockHttpServletRequest validated(String template) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/payments");
        request.setAttribute(IdempotencyKeyInterceptor.KEY_ATTRIBUTE, "key-1");
        if (template != null) {
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, template);
        }
        return request;
    }

    private void handle(MockHttpServletRequest request) {
        handler.handle(request, CONTEXT, null, () -> {
            useCaseRuns.incrementAndGet();
            throw new AssertionError("the use case must not run");
        });
    }

    @Test
    void aWriteWithNoValidatedKeyIsRefusedBeforeTheExecutor() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/payments");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/payments");

        assertThatThrownBy(() -> handle(request)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("@IdempotentWrite");
        verifyNoInteractions(executor);
    }

    @Test
    void aWriteThatMatchedNoRouteTemplateIsRefusedBeforeTheExecutor() {
        assertThatThrownBy(() -> handle(validated(null))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("route template");
        verifyNoInteractions(executor);
    }

    @Test
    void anEndpointWiderThanTheMarkerTableIsRefusedAndNeverTruncated() {
        // "POST " plus 195 characters is exactly 200, the width of the endpoint column.
        String widest = "/" + "a".repeat(194);
        String tooWide = widest + "a";

        assertThatThrownBy(() -> handle(validated(tooWide)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("longer than 200");
        verifyNoInteractions(executor);
        assertThat(useCaseRuns).hasValue(0);
    }

    @Test
    void anEndpointOfExactlyTheWidthOfTheMarkerTableReachesTheExecutorWhole() {
        String widest = "/" + "a".repeat(194);

        // The mock answers no outcome, which the handler cannot translate; only the call matters.
        assertThatThrownBy(() -> handle(validated(widest)))
                .isInstanceOf(NullPointerException.class);
        verify(executor).execute(eq(CONTEXT), eq(new IdempotencyKey("POST " + widest, "key-1")),
                any(), any());
    }
}

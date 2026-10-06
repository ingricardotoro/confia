package com.confia.shared.web.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/**
 * The header rules of {@link IdempotencyKeyInterceptor} (web-edge-foundations design.md, decision
 * 19), on the interceptor alone. {@code IdempotencyEdgeIT} proves the same rules through the real
 * chain and the database; this test keeps them proven in the PR that ships the interceptor.
 */
class IdempotencyKeyInterceptorTest {

    private static final String MAXIMUM = "k".repeat(IdempotencyKeyInterceptor.MAX_KEY_LENGTH);

    private final IdempotencyKeyInterceptor interceptor = new IdempotencyKeyInterceptor();

    static final class Probe {

        @IdempotentWrite
        void write() {
        }

        void read() {
        }
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Probe(), Probe.class.getDeclaredMethod(name));
    }

    private boolean preHandle(MockHttpServletRequest request, String method) throws Exception {
        return interceptor.preHandle(request, new MockHttpServletResponse(), handler(method));
    }

    private static MockHttpServletRequest withKey(String... keys) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/test/write");
        for (String key : keys) {
            request.addHeader(IdempotencyKeyInterceptor.HEADER, key);
        }
        return request;
    }

    @Test
    void anAcceptedKeyIsLeftOnTheRequestForTheHandler() throws Exception {
        MockHttpServletRequest request = withKey(MAXIMUM);

        assertThat(preHandle(request, "write")).isTrue();
        assertThat(request.getAttribute(IdempotencyKeyInterceptor.KEY_ATTRIBUTE))
                .isEqualTo(MAXIMUM);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", " ", "\t"})
    void anAbsentOrBlankKeyIsMissing(String blank) {
        assertThatThrownBy(() -> preHandle(withKey(), "write"))
                .isInstanceOf(IdempotencyKeyMissingException.class);
        assertThatThrownBy(() -> preHandle(withKey(blank), "write"))
                .isInstanceOf(IdempotencyKeyMissingException.class);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"two words", "clave-ñ", "line\nbreak", "tab\tinside"})
    void aKeyWithACharacterOutsideVisibleAsciiIsInvalid(String key) {
        assertThatThrownBy(() -> preHandle(withKey(key), "write"))
                .isInstanceOf(IdempotencyKeyInvalidException.class);
    }

    @Test
    void aKeyOneCharacterLongerThanTheMaximumIsInvalid() {
        assertThatThrownBy(() -> preHandle(withKey(MAXIMUM + "k"), "write"))
                .isInstanceOf(IdempotencyKeyInvalidException.class);
    }

    @Test
    void aRepeatedHeaderIsInvalidEvenWhenBothValuesAreEqual() {
        assertThatThrownBy(() -> preHandle(withKey("same", "same"), "write"))
                .isInstanceOf(IdempotencyKeyInvalidException.class);
    }

    @Test
    void aMethodThatIsNotAnIdempotentWriteIsNotTouched() throws Exception {
        MockHttpServletRequest request = withKey();

        assertThat(preHandle(request, "read")).isTrue();
        assertThat(request.getAttribute(IdempotencyKeyInterceptor.KEY_ATTRIBUTE)).isNull();
    }

    @Test
    void theAsyncDispatchOfAnAlreadyValidatedRequestPasses() throws Exception {
        MockHttpServletRequest request = withKey();
        request.setDispatcherType(DispatcherType.ASYNC);

        assertThat(preHandle(request, "write")).isTrue();
    }
}

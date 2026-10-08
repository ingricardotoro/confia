package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.web.authentication.AccessTokenAuthenticationFilter;
import com.confia.shared.web.authentication.ActorAuthentication;
import com.confia.shared.web.authentication.AuthenticatedEndpoints;
import com.confia.shared.web.authentication.AccessTokenRejectedException;
import com.confia.shared.web.harness.HarnessTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Specs/web-edge, what the bearer filter leaves behind on the thread
 * (session-tokens-and-web-layer design.md, decision 5): the filter itself clears Spring's security
 * context when its work is over, so a worker thread that serves the next request never starts with
 * the previous caller's principal, whatever the rest of the chain did. The filter is run directly,
 * twice, on this one thread; the HTTP tests cannot prove it because Spring's own context filter
 * would clear the context anyway.
 */
class AccessTokenAuthenticationFilterContextTest {

    private final HarnessTokens tokens = new HarnessTokens();
    private final List<String> entryPointCalls = new ArrayList<>();
    private final AccessTokenAuthenticationFilter filter = new AccessTokenAuthenticationFilter(
            tokens.verifier(), new AuthenticatedEndpoints(List.of()),
            (request, response, exception) -> entryPointCalls
                    .add(exception.getClass().getSimpleName()));

    @BeforeEach
    @AfterEach
    void cleanThread() {
        SecurityContextHolder.clearContext();
    }

    /** What the rest of the chain saw while it ran. */
    private record Seen(Authentication authentication, Optional<AuthenticatedActor> actor) {
    }

    private Seen run(String authorization, List<Seen> sink) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test/whoami");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                    jakarta.servlet.http.HttpServletResponse res) {
                sink.add(new Seen(SecurityContextHolder.getContext().getAuthentication(),
                        AuthenticatedActor.current()));
            }
        });
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return sink.isEmpty() ? null : sink.get(sink.size() - 1);
    }

    @Test
    void thePrincipalOfOneRequestNeverReachesTheNextRequestOnTheSameThread() throws Exception {
        List<Seen> seen = new ArrayList<>();

        run("Bearer " + tokens.access(), seen);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).authentication()).as("the chain saw the authenticated actor")
                .isInstanceOf(ActorAuthentication.class);
        assertThat(seen.get(0).actor()).isPresent();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("nothing is left on the thread after the first request").isNull();

        run(null, seen);

        assertThat(seen).hasSize(2);
        assertThat(seen.get(1).authentication()).as("the second request starts anonymous").isNull();
        assertThat(seen.get(1).actor()).as("and has no actor bound").isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void theContextIsClearedWhenTheRestOfTheChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test/whoami");
        request.addHeader("Authorization", "Bearer " + tokens.access());
        MockFilterChain failing = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                    jakarta.servlet.http.HttpServletResponse res) {
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
                throw new IllegalStateException("the chain failed");
            }
        });

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failing))
                .hasMessageContaining("the chain failed");

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void anAnonymousRequestAlsoLeavesTheThreadClean() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new ActorAuthentication(actorOf(tokens)));
        List<Seen> seen = new ArrayList<>();

        run(null, seen);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("the filter leaves the thread clean").isNull();
    }

    private static AuthenticatedActor actorOf(HarnessTokens tokens) {
        return tokens.verifier().verifyAccess(tokens.access());
    }

    // --- S-3: the authentication is Serializable, the actor it holds must not be written out ---

    @Test
    void theActorOfAnAuthenticationIsNotSerialized() throws Exception {
        assertThat(java.lang.reflect.Modifier.isTransient(
                ActorAuthentication.class.getDeclaredField("actor").getModifiers()))
                .as("a session store must never persist the principal").isTrue();
    }

    // --- S-1: an Authorization header that is present but empty is a broken credential ---

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t"})
    void anEmptyOrBlankAuthorizationHeaderIsRejectedAsInvalid(String header) throws Exception {
        List<Seen> seen = new ArrayList<>();

        run(header, seen);

        assertThat(seen).as("the chain was not continued").isEmpty();
        assertThat(entryPointCalls).containsExactly(AccessTokenRejectedException.class.getSimpleName());
    }

    @Test
    void aTabInPlaceOfTheSpaceAfterBearerIsRejectedAsInvalid() throws Exception {
        List<Seen> seen = new ArrayList<>();

        run("Bearer\t" + tokens.access(), seen);

        assertThat(seen).isEmpty();
        assertThat(entryPointCalls).containsExactly(AccessTokenRejectedException.class.getSimpleName());
    }

    @Test
    void aBasicHeaderStaysAnonymous() throws Exception {
        List<Seen> seen = new ArrayList<>();

        run("Basic dXNlcjpwdw==", seen);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).authentication()).isNull();
        assertThat(entryPointCalls).isEmpty();
    }
}

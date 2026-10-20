package com.confia.shared.web.harness;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * A test-only route that declares its responses explicitly, for probe S-4 of
 * session-tokens-and-web-layer (design.md decision 13): which responses springdoc adds to an
 * operation besides the ones declared, depending on {@code springdoc.override-with-generic-response}.
 * It declares {@code 200} and {@code 401} and nothing else. Its own advice stands for a global
 * translator that grows a handler with a status of its own, which is how an unapproved {@code 403}
 * or {@code 429} would reach the contract; it applies to this controller only, so no other route of
 * the harness is touched.
 */
@RestController
class DocumentedProbeController {

    @GetMapping("/test/documented")
    @ApiResponse(responseCode = "200", description = "declared")
    @ApiResponse(responseCode = "401", description = "declared")
    String documented() {
        return "documented";
    }

    /** What a handler of the advice below answers to; nothing throws it. */
    static final class ProbeForbidden extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** What a handler of the advice below answers to; nothing throws it. */
    static final class ProbeTooMany extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    @RestControllerAdvice(assignableTypes = DocumentedProbeController.class)
    static class ProbeAdvice {

        @ExceptionHandler(ProbeForbidden.class)
        @ResponseStatus(HttpStatus.FORBIDDEN)
        String forbidden() {
            return "forbidden";
        }

        @ExceptionHandler(ProbeTooMany.class)
        @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
        String tooMany() {
            return "too many";
        }
    }
}

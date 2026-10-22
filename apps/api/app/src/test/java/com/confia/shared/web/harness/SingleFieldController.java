package com.confia.shared.web.harness;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only route with a request body of a single field, for the probe S-5 of
 * session-endpoints-cookie-and-csrf (design.md, decision 9): a JSON property that the body does not
 * declare must be refused with {@code 400 validation-failed}, not ignored. Public for {@code POST}
 * in the harness allow-list.
 */
@RestController
class SingleFieldController {

    /** The body the route accepts: one mandatory field and nothing else. */
    record SingleField(@NotBlank String a) {
    }

    @PostMapping(path = "/test/single-field", consumes = MediaType.APPLICATION_JSON_VALUE)
    String accept(@Valid @RequestBody SingleField body) {
        return "accepted";
    }
}

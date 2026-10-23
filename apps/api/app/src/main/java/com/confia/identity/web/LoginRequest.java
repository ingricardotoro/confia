package com.confia.identity.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/v1/auth/sessions} (session-endpoints-cookie-and-csrf design.md
 * decision 11): two members, both mandatory, with a maximum length each and nothing else. The
 * minimum length of the password is deliberately not checked here: a minimum would tell a client
 * what the password policy is before it has proved anything, and the use case compares the password
 * with the stored hash whatever its length. An unknown member is refused by the JSON mapper
 * (decision 9).
 *
 * <p>Never printed: the password is a member, so {@code toString()} redacts it.
 *
 * @param identifier the identifier of the account, as typed
 * @param password the password, as typed
 */
public record LoginRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 320,
                description = "The identifier of the staff account, as typed")
        @NotBlank @Size(max = 320) String identifier,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 1024, format = "password",
                description = "The password, as typed")
        @NotEmpty @Size(max = 1024) String password) {

    @Override
    public String toString() {
        return "LoginRequest[identifier=" + identifier + ", password=REDACTED]";
    }
}

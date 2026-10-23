package com.confia.identity.web;

import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a login that is not refused answers (session-endpoints-cookie-and-csrf design.md decision
 * 11): a session, or the second factor that is still owed. The member {@code status} says which, so
 * the contract is a {@code oneOf} with {@code status} as its discriminator.
 */
@Schema(oneOf = {AuthenticatedSessionResponse.class, SecondFactorPendingResponse.class},
        discriminatorProperty = "status",
        discriminatorMapping = {
                @DiscriminatorMapping(value = "authenticated",
                        schema = AuthenticatedSessionResponse.class),
                @DiscriminatorMapping(value = "second-factor-required",
                        schema = SecondFactorPendingResponse.class),
                @DiscriminatorMapping(value = "second-factor-enrollment-required",
                        schema = SecondFactorPendingResponse.class)})
public sealed interface LoginResponse permits AuthenticatedSessionResponse,
        SecondFactorPendingResponse {

    /** Which of the answers this is: the discriminator of the contract. */
    String status();
}

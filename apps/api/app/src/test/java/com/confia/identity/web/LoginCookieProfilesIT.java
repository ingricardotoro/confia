package com.confia.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Scenario [WE64] of specs/web-edge (session-endpoints-cookie-and-csrf design.md decision 5 and
 * the owner's decision D-N1): the two cookies of a login are {@code Secure} and keep their prefixes
 * in every profile, the local one included. The process is started with each profile in turn, with
 * a database and the production launcher, and the cookies of a real login are read from the answer.
 * The other half of the scenario, that the cookie helper has no property that could turn {@code
 * Secure} off or rename the cookies, is {@code SessionCookieConfigurationSurfaceTest}.
 */
class LoginCookieProfilesIT extends LoginEndpointSupport {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final String ANA = "ana.martinez@colegio.edu.hn";

    @Override
    InstitutionId institution() {
        return INSTITUTION;
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "preprod", "prod"})
    void bothCookiesAreSecureAndPrefixedInEveryProfile(String profile) {
        seedAccount(ANA, false, false);

        try (LoginProcess process = LoginProcess.start(PEPPER_BYTES, INSTITUTION, TEST_PASSWORD,
                Map.of("spring.profiles.active", profile))) {
            HttpResponse<String> response = process.login(LoginProcess.freshAddress(), ANA, PASSWORD);

            assertThat(response.statusCode()).as("the login under the %s profile", profile)
                    .isEqualTo(200);
            List<String> cookies = response.headers().allValues("Set-Cookie");
            assertThat(cookies).hasSize(2);
            assertThat(cookies).anySatisfy(cookie -> assertThat(cookie)
                    .startsWith("__Secure-confia_admin_sid=").contains("Secure").contains("HttpOnly")
                    .contains("SameSite=Strict"));
            assertThat(cookies).anySatisfy(cookie -> assertThat(cookie)
                    .startsWith("__Host-confia_csrf=").contains("Secure").contains("Path=/")
                    .contains("SameSite=Strict").doesNotContain("Domain"));
            assertThat(cookies).allSatisfy(cookie -> assertThat(cookie).contains("Secure"));
        }
    }
}

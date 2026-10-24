package com.confia.identity.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Specs/identity, scenario [I214]: failures of one client address against six different identifiers
 * inside ten minutes, with layer 1 of the limiter not exhausted and fewer than ten failures in total,
 * block nothing: an attempt from that address with the right password authenticates. The block of an
 * address by the spread of its failures over identifiers is delivered by change 11, and this test
 * stops being true the day it does; until then the spread is a signal that is logged, not a control.
 *
 * <p>The real administrative process with the production policy, a real PostgreSQL, and a client
 * address of the test's own.
 */
class IpBlockAbsenceIT extends LoginEndpointSupport {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final String ANA = "ana.martinez@colegio.edu.hn";

    private static LoginProcess process;

    @BeforeAll
    static void startTheProcess() {
        process = LoginProcess.start(PEPPER_BYTES, INSTITUTION, TEST_PASSWORD, Map.of());
    }

    @AfterAll
    static void stopTheProcess() {
        process.close();
    }

    @Override
    InstitutionId institution() {
        return INSTITUTION;
    }

    @Test
    void sixDifferentIdentifiersFromOneAddressDoNotBlockTheAddress() {
        seedAccount(ANA, false, false);
        String address = LoginProcess.freshAddress();

        for (int attempt = 1; attempt <= 6; attempt++) {
            HttpResponse<String> failed = process.login(address,
                    "desconocido" + attempt + "@colegio.edu.hn", "incorrecta-" + attempt);
            assertProblem(failed, 401, "authentication-failed");
        }
        HttpResponse<String> correct = process.login(address, ANA, PASSWORD);

        assertThat(correct.statusCode()).as("the address is not blocked").isEqualTo(200);
        assertThat(json(correct).get("status").asString()).isEqualTo("authenticated");
    }
}

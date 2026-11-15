package com.confia.shared.web.authorization.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.Permission;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.web.authorization.PermissionDeniedException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** What a controller of another module takes from the authorization of the edge. */
class AccessHelpersTest {

    @Test
    void thePermissionsStoredOnTheRequestAreTheOnesRead() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResolvedPermissions.store(request, Set.of(Permission.STUDENTS_READ));

        assertThat(ResolvedPermissions.of(request)).containsExactly(Permission.STUDENTS_READ);
    }

    @Test
    void aRequestTheDeciderDidNotDecideIsAWiringDefect() {
        MockHttpServletRequest absent = new MockHttpServletRequest();
        MockHttpServletRequest foreign = new MockHttpServletRequest();
        foreign.setAttribute(ResolvedPermissions.ATTRIBUTE, Set.of("students:read"));

        assertThatThrownBy(() -> ResolvedPermissions.of(absent)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ResolvedPermissions.of(foreign)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theRefusalIsTheTypedDenialOfTheDeciderWithTheTemplateAndThePermission() {
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Set.of(AuthenticationMethod.PASSWORD), Instant.now(),
                Instant.now().plusSeconds(60));

        assertThat(AccessRefusals.missing(Permission.GUARDIANS_READ, "/api/v1/imports", actor))
                .isInstanceOfSatisfying(PermissionDeniedException.class, denied -> {
                    assertThat(denied.permission()).isEqualTo(Permission.GUARDIANS_READ);
                    assertThat(denied.routeTemplate()).isEqualTo("/api/v1/imports");
                    assertThat(denied.actor()).isEqualTo(actor);
                });
    }
}

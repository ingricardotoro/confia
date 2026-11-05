package com.confia.shared.web.harness;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller of the routes on the permission list of the harness chain. It answers every
 * HTTP method, so a {@code 2xx} for a method or a path the list does not hold is visible as a hole
 * in the chain, and it counts its invocations, so a denied request is proved to have reached nothing.
 */
@RestController
class PermissionedHarnessController {

    private final Calls calls;

    PermissionedHarnessController(Calls calls) {
        this.calls = calls;
    }

    @RequestMapping({"/test/permissioned", "/test/accounts/{id}"})
    String permissioned() {
        calls.permissionedInvoked();
        return "permissioned";
    }
}

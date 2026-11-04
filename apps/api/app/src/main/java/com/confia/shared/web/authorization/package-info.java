/**
 * The authorization of the administrative chain by permission (rbac-permission-matrix-and-audit-integration
 * design.md D1 and D6; ADR-0027): the closed list of routes that need a permission, the decider that
 * asks the resolver once per request inside the filter chain, the two exceptions that tell a denial
 * from a failure to decide, and the port of the signals it emits.
 *
 * <p>Only the administrative process loads it. The beans are declared by {@code
 * AuthorizationConfiguration} of {@code shared.web.edge}; the portal forbids this package in its policy
 * and the worker forbids {@code shared.web} as a whole. It needs no named interface, because nothing
 * outside {@code shared} consumes it (ADR-0022).
 */
package com.confia.shared.web.authorization;

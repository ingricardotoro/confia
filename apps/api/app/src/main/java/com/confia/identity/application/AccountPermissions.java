package com.confia.identity.application;

import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StaffRole;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Permission;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.PermissionResolver;
import com.confia.shared.security.RequestOrigin;
import com.confia.shared.security.TransactionRunner;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The permissions of the account behind a request: the one adapter of {@link PermissionResolver}
 * (rbac-permission-matrix-and-audit-integration design.md D5; ADR-0027), which the permission decider of
 * the administrative chain asks.
 *
 * <p>It reads the role code stored for the account in the institution of the actor's token, in a
 * read-only {@code READ COMMITTED} transaction whose security context is the actor's, so the row-level
 * security of the administrative role is what keeps an assignment of another institution out. The
 * transaction is closed when {@link #resolve} returns, before any controller runs. It takes no lock,
 * writes nothing, audits nothing and caches nothing: a change of role is seen by the next request.
 *
 * <p>Failing closed: no row, or a code that is not in the {@link StaffRole} catalog, is a successful
 * answer with no permission at all. A failure of the store is not turned into an answer: it propagates,
 * and a broken database never reads as "no permissions", much less as "allowed".
 */
public final class AccountPermissions implements PermissionResolver {

    private final TransactionRunner runner;
    private final StaffRoleAssignmentRepository assignments;

    public AccountPermissions(TransactionRunner runner, StaffRoleAssignmentRepository assignments) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.assignments = Objects.requireNonNull(assignments, "assignments");
    }

    @Override
    public Set<Permission> resolve(AuthenticatedActor actor) {
        Objects.requireNonNull(actor, "actor");
        String requestId = RequestOrigin.current().map(RequestOrigin::requestId)
                .orElseGet(UUID::randomUUID).toString();
        InstitutionId institution = new InstitutionId(actor.institutionId());
        StaffAccountId account = new StaffAccountId(actor.accountId());
        Optional<String> stored = runner.executeReadOnly(actor.securityContext(requestId),
                () -> assignments.findRoleCode(institution, account));
        return stored.flatMap(StaffRole::ofCode).map(StaffRole::permissions).orElse(Set.of());
    }
}

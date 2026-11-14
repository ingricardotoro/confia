package com.confia.architecture.authorizationscope;

import static confia.generated.jooq.tables.IdentityStaffAccountRole.IDENTITY_STAFF_ACCOUNT_ROLE;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Deliberate violation fixtures of the absences that change 8 leaves to later changes
 * (rbac-permission-matrix-and-audit-integration; scenarios AZ74 to AZ77, I215, I300 and BI136). Each class
 * is what the change that owns the absence would add, and the detector of the inventory that asserts the
 * absence must find it: that is what shows the detector looks at something. Permanent, never removed, and
 * imported one class at a time by the tests, never by scanning the package. Test code, outside the
 * production scope.
 */
public final class AuthorizationScopeViolationFixtures {

    private AuthorizationScopeViolationFixtures() {
    }

    /** A route that assigns a role to an account (change 8b). */
    @RequestMapping("/api/v1/identity/accounts")
    public static final class RoleAssignmentController {

        @PutMapping("/{id}/role")
        public String assign() {
            return "assigned";
        }
    }

    /** A route that deactivates an account (change 8b). */
    @RequestMapping("/api/v1/identity/accounts")
    public static final class AccountDeactivationController {

        @DeleteMapping("/{id}")
        public String deactivate() {
            return "deactivated";
        }
    }

    /** A route that reads the audit trail (change 8b). */
    @RequestMapping("/api/v1/audit/entries")
    public static final class AuditLogController {

        @GetMapping
        public String entries() {
            return "entries";
        }
    }

    /** A route that enrolls a second factor with a full session (change 8b). */
    @RequestMapping("/api/v1/auth/mfa/totp-secrets")
    public static final class FullSessionMfaEnrollmentController {

        @PostMapping
        public String enroll() {
            return "enrolled";
        }
    }

    /** A route that resets the password of another account (change 8b). */
    @RequestMapping("/api/v1/identity/accounts")
    public static final class AdminPasswordResetController {

        @PostMapping("/{id}/password-reset")
        public String reset() {
            return "reset";
        }
    }

    /** A class that inserts a role assignment through jOOQ: the writer only a test has today. */
    public static final class AssignmentWriter {

        public int write(DSLContext dsl, UUID institution, UUID account, String role) {
            return dsl.insertInto(IDENTITY_STAFF_ACCOUNT_ROLE)
                    .set(IDENTITY_STAFF_ACCOUNT_ROLE.INSTITUTION_ID, institution)
                    .set(IDENTITY_STAFF_ACCOUNT_ROLE.ACCOUNT_ID, account)
                    .set(IDENTITY_STAFF_ACCOUNT_ROLE.ROLE_CODE, role)
                    .execute();
        }
    }

    /** The chain of a portal that carries the list of routes with a permission and the decider (control of AZ76). */
    public static final class PortalChainWithPermissions {

        private final com.confia.shared.web.authorization.PermissionedEndpoints list =
                com.confia.shared.web.authorization.PermissionedEndpoints.forAdmin();

        public int routes() {
            return list.endpoints().size();
        }
    }

    /** The control: a class that only reads the assignment table is not a writer. */
    public static final class AssignmentReader {

        public String read(DSLContext dsl, UUID institution, UUID account) {
            return dsl.select(IDENTITY_STAFF_ACCOUNT_ROLE.ROLE_CODE).from(IDENTITY_STAFF_ACCOUNT_ROLE)
                    .where(IDENTITY_STAFF_ACCOUNT_ROLE.INSTITUTION_ID.eq(institution))
                    .and(IDENTITY_STAFF_ACCOUNT_ROLE.ACCOUNT_ID.eq(account))
                    .fetchOne(IDENTITY_STAFF_ACCOUNT_ROLE.ROLE_CODE);
        }
    }

    /** A use case that assigns a role (change 8b). */
    public static final class AssignStaffRole {

        public void execute() {
        }
    }

    /** A use case that changes the role of an account (change 8b). */
    public static final class ChangeStaffRole {

        public void execute() {
        }
    }

    /** A use case that creates the first Super Admin (change 8b). */
    public static final class CreateFirstSuperAdmin {

        public void execute() {
        }
    }

    /** A use case that deactivates an account (change 8b). */
    public static final class DeactivateStaffAccount {

        public void execute() {
        }
    }

    /** A use case that raises the pending request to void an invoice (the change that builds the void). */
    public static final class RequestInvoiceVoid {

        public void execute() {
        }
    }

    /** A use case that approves the void of an invoice (the change that builds the void). */
    public static final class ApproveInvoiceVoid {

        public void execute() {
        }
    }

    /** A check of a restriction inside a permission (the use case of each module). */
    public static final class ScopedPermissionCheck {

        public boolean allows() {
            return true;
        }
    }

    /** A report of the segregation of duties (the change with the first operation that needs approval). */
    public static final class SegregationOfDutiesReport {

        public void run() {
        }
    }

    /** A counter of the denials shared between processes through Redis (change 11). */
    public static final class RedisDenialCounter {

        public int count() {
            return 0;
        }
    }
}

package com.confia.identity.domain;

import static com.confia.kernel.Permission.*;

import com.confia.kernel.Permission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The closed catalog of staff roles and the permissions each one grants (docs/03-seguridad.md section
 * 5.2; rbac-permission-matrix-and-audit-integration design.md, decisions D2 and D3; owner decisions O1,
 * O3 and O10).
 *
 * <p>There are exactly six, in catalog order, and no role of the guardian or of the portal: the
 * guardian's access control waits for F8. The Super Admin is per institution, not of the platform. The
 * roles and their matrix live in code and not in global tables or a seed migration; the database
 * holds only which role an account has. {@code PermissionMatrixParityTest} compares this matrix cell by
 * cell with the effective matrix published in a dated note of docs/03 section 5.2, so a permission
 * changed on one side and not on the other breaks the build.
 *
 * <p>The permissions of a role are an immutable set. A scope inside a permission ("only its own
 * cash box" for reporting, "academic" for the coordinator) is not part of the matrix: each use case
 * enforces it as object-level authorization (docs/03 section 5.4).
 */
public enum StaffRole {

    /** Every module-and-verb permission of the catalog; not the cashier's request to void. */
    SUPER_ADMIN("super_admin", complementOf(INVOICING_REQUEST_VOID)),

    /** Configures the system: reads the money flow but does not collect, and does not read the audit log. */
    ADMINISTRATOR("administrator", EnumSet.of(
            IDENTITY_READ,
            ORGANIZATION_CREATE, ORGANIZATION_READ, ORGANIZATION_UPDATE, ORGANIZATION_EXPORT,
            STUDENTS_CREATE, STUDENTS_READ, STUDENTS_UPDATE, STUDENTS_EXPORT,
            GUARDIANS_CREATE, GUARDIANS_READ, GUARDIANS_UPDATE, GUARDIANS_EXPORT,
            CATALOG_CREATE, CATALOG_READ, CATALOG_UPDATE, CATALOG_EXPORT,
            SCHOLARSHIPS_CREATE, SCHOLARSHIPS_READ, SCHOLARSHIPS_UPDATE, SCHOLARSHIPS_APPROVE, SCHOLARSHIPS_EXPORT,
            CHARGES_CREATE, CHARGES_READ, CHARGES_VOID, CHARGES_APPROVE, CHARGES_EXPORT,
            LEDGER_READ, LEDGER_VOID, LEDGER_EXPORT,
            PAYMENTS_READ, PAYMENTS_VOID, PAYMENTS_APPROVE, PAYMENTS_EXPORT,
            CASHBOX_READ, CASHBOX_APPROVE, CASHBOX_EXPORT,
            INVOICING_READ, INVOICING_APPROVE, INVOICING_EXPORT,
            COLLECTIONS_CREATE, COLLECTIONS_READ, COLLECTIONS_UPDATE, COLLECTIONS_APPROVE, COLLECTIONS_EXPORT,
            RECONCILIATION_READ, RECONCILIATION_APPROVE, RECONCILIATION_EXPORT,
            DOCUMENTS_CREATE, DOCUMENTS_READ, DOCUMENTS_UPDATE, DOCUMENTS_APPROVE, DOCUMENTS_EXPORT,
            REPORTING_READ, REPORTING_EXPORT,
            NOTIFICATIONS_CREATE, NOTIFICATIONS_READ, NOTIFICATIONS_UPDATE, NOTIFICATIONS_EXPORT,
            SETTINGS_READ, SETTINGS_UPDATE)),

    /** Collects and invoices inside its cash session, reads, and requests (but does not make) a void. */
    CASHIER("cashier", EnumSet.of(
            ORGANIZATION_READ, STUDENTS_READ, GUARDIANS_READ, CATALOG_READ, SCHOLARSHIPS_READ, CHARGES_READ,
            LEDGER_READ, PAYMENTS_CREATE, PAYMENTS_READ, CASHBOX_CREATE, CASHBOX_READ,
            INVOICING_CREATE, INVOICING_READ, INVOICING_REQUEST_VOID,
            COLLECTIONS_READ, DOCUMENTS_READ, REPORTING_READ, NOTIFICATIONS_READ)),

    /** Voids and approves invoices, reconciles the bank and reads the rest of the money flow. */
    ACCOUNTING("accounting", EnumSet.of(
            ORGANIZATION_READ, STUDENTS_READ, GUARDIANS_READ, CATALOG_READ, CATALOG_EXPORT,
            SCHOLARSHIPS_READ, SCHOLARSHIPS_EXPORT, CHARGES_READ, CHARGES_EXPORT, LEDGER_READ, LEDGER_EXPORT,
            PAYMENTS_READ, PAYMENTS_VOID, PAYMENTS_EXPORT, CASHBOX_READ, CASHBOX_EXPORT,
            INVOICING_CREATE, INVOICING_READ, INVOICING_VOID, INVOICING_APPROVE, INVOICING_EXPORT,
            COLLECTIONS_READ, COLLECTIONS_EXPORT,
            RECONCILIATION_CREATE, RECONCILIATION_READ, RECONCILIATION_UPDATE, RECONCILIATION_APPROVE,
            RECONCILIATION_EXPORT,
            REPORTING_READ, REPORTING_EXPORT, SETTINGS_READ)),

    /** The only role that is absolutely read-only: reads and exports every module, audit log included. */
    AUDITOR("auditor", Arrays.stream(Permission.values())
            .filter(permission -> permission.verb().equals("read")
                    || (permission.verb().equals("export") && !permission.equals(IDENTITY_EXPORT)))
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)))),

    /** Manages the academic side (students, documents, organization) and reads the rest of its scope. */
    ACADEMIC_COORDINATOR("academic_coordinator", EnumSet.of(
            ORGANIZATION_CREATE, ORGANIZATION_READ, ORGANIZATION_UPDATE, ORGANIZATION_EXPORT,
            STUDENTS_CREATE, STUDENTS_READ, STUDENTS_UPDATE, STUDENTS_EXPORT,
            GUARDIANS_READ, CATALOG_READ, SCHOLARSHIPS_CREATE, SCHOLARSHIPS_READ, CHARGES_READ, COLLECTIONS_READ,
            DOCUMENTS_CREATE, DOCUMENTS_READ, DOCUMENTS_UPDATE, DOCUMENTS_APPROVE, DOCUMENTS_EXPORT,
            REPORTING_READ, REPORTING_EXPORT, NOTIFICATIONS_CREATE, NOTIFICATIONS_READ));

    private final String code;
    private final Set<Permission> permissions;

    StaffRole(String code, Set<Permission> permissions) {
        this.code = code;
        this.permissions = Set.copyOf(permissions);
    }

    private static Set<Permission> complementOf(Permission excluded) {
        return EnumSet.complementOf(EnumSet.of(excluded));
    }

    /** The stable code stored with an account's assignment: {@code super_admin}, {@code cashier}, and so on. */
    public String code() {
        return code;
    }

    /** What the role grants; an immutable set. */
    public Set<Permission> permissions() {
        return permissions;
    }

    /** The role with {@code code}, or empty when the catalog has none; never a default role. */
    public static Optional<StaffRole> ofCode(String code) {
        return Arrays.stream(values()).filter(role -> role.code.equals(code)).findFirst();
    }
}

package com.confia.kernel;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The closed catalog of permissions: 89 entries named {@code module:verb} (docs/03-seguridad.md
 * section 5.1; rbac-permission-matrix-and-audit-integration design.md, decision D2).
 *
 * <p>Eighty-eight entries pair one of the eighteen modules with one of the six verbs ({@code create},
 * {@code read}, {@code update}, {@code void}, {@code approve} and {@code export}); the eighty-ninth,
 * {@link #INVOICING_REQUEST_VOID}, is the cashier's request to void an invoice, which is not the
 * authority to void it ({@link #INVOICING_VOID}). The catalog holds exactly the permissions that some
 * role grants, so a combination no role has (such as {@code ledger:update}) is not an entry at all.
 *
 * <p>It lives in {@code kernel} and not in the module that owns the roles because both the {@code
 * domain} of {@code identity}, which says which role grants what, and {@code shared}, which decides
 * a request, have to name the same codes, and neither may import the other (docs/01, ADR-0002). It
 * says nothing about roles: that is {@code StaffRole}'s matrix. The own-MFA routes of an account are
 * self-service for every role, so they are not entries either.
 */
public enum Permission {

    IDENTITY_CREATE("identity:create"), IDENTITY_READ("identity:read"), IDENTITY_UPDATE("identity:update"),
    IDENTITY_VOID("identity:void"), IDENTITY_APPROVE("identity:approve"), IDENTITY_EXPORT("identity:export"),

    ORGANIZATION_CREATE("organization:create"), ORGANIZATION_READ("organization:read"),
    ORGANIZATION_UPDATE("organization:update"), ORGANIZATION_VOID("organization:void"),
    ORGANIZATION_EXPORT("organization:export"),

    STUDENTS_CREATE("students:create"), STUDENTS_READ("students:read"), STUDENTS_UPDATE("students:update"),
    STUDENTS_VOID("students:void"), STUDENTS_EXPORT("students:export"),

    GUARDIANS_CREATE("guardians:create"), GUARDIANS_READ("guardians:read"), GUARDIANS_UPDATE("guardians:update"),
    GUARDIANS_VOID("guardians:void"), GUARDIANS_EXPORT("guardians:export"),

    CATALOG_CREATE("catalog:create"), CATALOG_READ("catalog:read"), CATALOG_UPDATE("catalog:update"),
    CATALOG_VOID("catalog:void"), CATALOG_EXPORT("catalog:export"),

    SCHOLARSHIPS_CREATE("scholarships:create"), SCHOLARSHIPS_READ("scholarships:read"),
    SCHOLARSHIPS_UPDATE("scholarships:update"), SCHOLARSHIPS_VOID("scholarships:void"),
    SCHOLARSHIPS_APPROVE("scholarships:approve"), SCHOLARSHIPS_EXPORT("scholarships:export"),

    CHARGES_CREATE("charges:create"), CHARGES_READ("charges:read"), CHARGES_UPDATE("charges:update"),
    CHARGES_VOID("charges:void"), CHARGES_APPROVE("charges:approve"), CHARGES_EXPORT("charges:export"),

    LEDGER_READ("ledger:read"), LEDGER_VOID("ledger:void"), LEDGER_APPROVE("ledger:approve"),
    LEDGER_EXPORT("ledger:export"),

    PAYMENTS_CREATE("payments:create"), PAYMENTS_READ("payments:read"), PAYMENTS_VOID("payments:void"),
    PAYMENTS_APPROVE("payments:approve"), PAYMENTS_EXPORT("payments:export"),

    CASHBOX_CREATE("cashbox:create"), CASHBOX_READ("cashbox:read"), CASHBOX_VOID("cashbox:void"),
    CASHBOX_APPROVE("cashbox:approve"), CASHBOX_EXPORT("cashbox:export"),

    INVOICING_CREATE("invoicing:create"), INVOICING_READ("invoicing:read"), INVOICING_VOID("invoicing:void"),
    INVOICING_APPROVE("invoicing:approve"), INVOICING_EXPORT("invoicing:export"),
    INVOICING_REQUEST_VOID("invoicing:request-void"),

    COLLECTIONS_CREATE("collections:create"), COLLECTIONS_READ("collections:read"),
    COLLECTIONS_UPDATE("collections:update"), COLLECTIONS_VOID("collections:void"),
    COLLECTIONS_APPROVE("collections:approve"), COLLECTIONS_EXPORT("collections:export"),

    RECONCILIATION_CREATE("reconciliation:create"), RECONCILIATION_READ("reconciliation:read"),
    RECONCILIATION_UPDATE("reconciliation:update"), RECONCILIATION_APPROVE("reconciliation:approve"),
    RECONCILIATION_EXPORT("reconciliation:export"),

    DOCUMENTS_CREATE("documents:create"), DOCUMENTS_READ("documents:read"), DOCUMENTS_UPDATE("documents:update"),
    DOCUMENTS_VOID("documents:void"), DOCUMENTS_APPROVE("documents:approve"), DOCUMENTS_EXPORT("documents:export"),

    REPORTING_READ("reporting:read"), REPORTING_EXPORT("reporting:export"),

    NOTIFICATIONS_CREATE("notifications:create"), NOTIFICATIONS_READ("notifications:read"),
    NOTIFICATIONS_UPDATE("notifications:update"), NOTIFICATIONS_VOID("notifications:void"),
    NOTIFICATIONS_EXPORT("notifications:export"),

    AUDIT_READ("audit:read"), AUDIT_EXPORT("audit:export"),

    SETTINGS_CREATE("settings:create"), SETTINGS_READ("settings:read"), SETTINGS_UPDATE("settings:update"),
    SETTINGS_VOID("settings:void"), SETTINGS_EXPORT("settings:export");

    private static final Map<String, Permission> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Permission::code, Function.identity()));

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    /** The stable code, {@code module:verb}, that travels in documents, audit entries and tests. */
    public String code() {
        return code;
    }

    /** The module the permission belongs to: the part of the code before the colon. */
    public String module() {
        return code.substring(0, code.indexOf(':'));
    }

    /** The verb of the permission: the part of the code after the colon. */
    public String verb() {
        return code.substring(code.indexOf(':') + 1);
    }

    /** The permission with {@code code}, or empty when the catalog has none; never a default. */
    public static Optional<Permission> ofCode(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(BY_CODE.get(code));
    }
}

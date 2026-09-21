package com.confia.architecture.fixture.billing.infrastructure;

import confia.generated.jooq.tables.OrganizationInstitution;

/**
 * Permanent negative fixture for {@code TableOwnershipByModuleTest} (task 3.2, rule R2): a class
 * of the {@code billing} module using the generated table type of {@code organization}, which
 * ADR-0015 rule 3 forbids (design.md decision 9, rule R2). Compiles only because {@code
 * globalObjectReferences=false} (task 1.7) already forces every table reference through its own
 * generated type, which is exactly what makes this rule verifiable.
 */
public final class BadForeignTableUser {

    private final OrganizationInstitution foreignTable = OrganizationInstitution.ORGANIZATION_INSTITUTION;

    public OrganizationInstitution foreignTable() {
        return foreignTable;
    }
}

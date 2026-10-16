package com.confia.identity.application;

import com.confia.identity.domain.RefreshTokenFamily;
import com.confia.kernel.InstitutionId;

/**
 * Where session families are written (session-tokens-and-web-layer design.md decision 9). It never
 * opens a transaction: it runs inside the one the use case opened. {@link
 * com.confia.identity.infrastructure.JooqRefreshTokenFamilyRepository} is its adapter. Rotation and
 * revocation add their own operations in the changes that deliver them.
 */
public interface RefreshTokenFamilyRepository {

    /** Writes a new, unrevoked family. */
    void insert(InstitutionId institutionId, RefreshTokenFamily family);
}

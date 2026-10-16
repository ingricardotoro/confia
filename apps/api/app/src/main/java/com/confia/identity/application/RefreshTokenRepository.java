package com.confia.identity.application;

import com.confia.identity.domain.RefreshTokenRow;
import com.confia.kernel.InstitutionId;

/**
 * Where refresh tokens are written (session-tokens-and-web-layer design.md decision 9). It never
 * opens a transaction: it runs inside the one the use case opened. {@link
 * com.confia.identity.infrastructure.JooqRefreshTokenRepository} is its adapter. Rotation adds its
 * own operations in the change that delivers it.
 */
public interface RefreshTokenRepository {

    /** Writes a new, unconsumed token of an existing family. */
    void insert(InstitutionId institutionId, RefreshTokenRow row);
}

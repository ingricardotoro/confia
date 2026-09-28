package com.confia.identity.application;

import com.confia.identity.domain.BackoffState;
import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.kernel.InstitutionId;
import java.time.Instant;

/**
 * Reads and writes {@code identity_login_backoff}'s state (design.md, §6.1, decision 5).
 * {@link com.confia.identity.infrastructure.JooqLoginBackoffStore} is its real adapter, arriving in
 * PR C3b.
 */
public interface LoginBackoffStore {

    /**
     * Creates or locks the row for {@code fingerprint} and returns the state it held before this
     * call (design.md, decision 5): {@link BackoffState#initial(Instant)} when the row did not
     * exist yet — the same state, and the same code path, whether or not {@code fingerprint}
     * corresponds to an existing account (design.md, decision 3).
     */
    BackoffState claim(InstitutionId institutionId, IdentifierFingerprint fingerprint, Instant now);

    void save(InstitutionId institutionId, IdentifierFingerprint fingerprint, BackoffState state);
}

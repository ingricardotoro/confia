package com.confia.identity.application;

import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.StoredPasswordHash;

/**
 * Verifies and computes Argon2id password hashes (design.md, §6.1, decision 6).
 * {@link com.confia.identity.infrastructure.BouncyCastleArgon2PasswordHasher} is its one adapter.
 *
 * <p>{@code hash(...)} has a real production consumer in this very change — the decoy hash below
 * (design.md, decision 7) — so it is not speculative surface left for a later cut to justify.
 *
 * <p><b>{@code decoyHash()}, one method beyond design.md §6.1's own two-method sketch of this
 * port.</b> The uniform-cost verification a nonexistent account requires (specs/identity/spec.md,
 * "Verificación Argon2id contra un hash señuelo cuando la cuenta no existe") must run against the
 * exact same per-instance decoy value {@link
 * com.confia.identity.infrastructure.BouncyCastleArgon2PasswordHasher} already builds once, at
 * construction, under its own current profile and pepper (design.md, decision 7) — never a value
 * this use case invents or recomputes on its own, which would defeat the whole point of costing
 * exactly what a real verification costs. That adapter's own Javadoc, written in PR C2, already
 * anticipated exactly this: its package-private decoy accessor documents that "su uso de
 * producción... es cableado de capa de aplicación que llega en PR C3a" — this cut. Adding the
 * method here, and widening that accessor to {@code public} with {@code @Override}, is that
 * arrival, not a silent deviation from design.md's literal snippet.
 */
public interface PasswordHasher {

    boolean matches(PlainPassword password, StoredPasswordHash hash);

    StoredPasswordHash hash(PlainPassword password);

    /** The constant-per-instance decoy hash to verify against when no account exists. */
    StoredPasswordHash decoyHash();
}

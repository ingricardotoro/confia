package com.confia.identity.infrastructure;

/**
 * The exact shape of {@link Argon2PhcCodec#rawHash}, extracted so tests can substitute a counting
 * wrapper without subclassing {@link BouncyCastleArgon2PasswordHasher} (design.md, decision 7,
 * "Cómo se demuestra que el señuelo se ejecuta"). Package-private: this is a testing seam, not a
 * port — {@code identity.application} declares no such interface.
 */
@FunctionalInterface
interface Argon2RawHasher {

    byte[] rawHash(byte[] passwordBytes, byte[] salt, byte[] secret, byte[] associatedData,
            int memoryCostKib, int timeCost, int parallelism, int hashLength);
}

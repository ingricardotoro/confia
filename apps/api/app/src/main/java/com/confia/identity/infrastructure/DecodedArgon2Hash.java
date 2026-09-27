package com.confia.identity.infrastructure;

/**
 * A {@code $argon2id$...} PHC string, parsed back into its parameters, salt and tag
 * (design.md, decision 6). Package-private: nothing outside {@code identity.infrastructure} needs
 * to see a decoded hash — {@link Argon2PhcCodec#decode(String)}'s only caller today is
 * {@link BouncyCastleArgon2PasswordHasher}.
 *
 * <p>Deliberately not redacted. {@code salt} and {@code tag} are {@code byte[]}, and a Java
 * record's generated {@code toString()} calls each component's own {@code toString()} — for an
 * array, that is {@code Object}'s default identity-hash form (for example {@code "[B@1a2b3c"}),
 * which never prints array content. Nothing in this record's default {@code toString()} can ever
 * leak the Argon2id tag it carries, so no override is needed to satisfy CLAUDE.md regla 11.
 */
record DecodedArgon2Hash(int version, int memoryCostKib, int timeCost, int parallelism,
        byte[] salt, byte[] tag) {
}

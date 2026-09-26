package com.confia.identity.infrastructure;

/**
 * The Argon2id parameters this process uses to hash and verify passwords, taken literally from
 * {@code docs/03-seguridad.md} §4.1's own table (design.md, decision 6). Declared as the floor this
 * change ships with, and explicitly NOT as a value calibrated against real production hardware
 * (specs/identity/spec.md, requirement "Ausencia de calibración de Argon2id en servidor real";
 * {@code docs/03-seguridad.md} §4.1, "Calibración obligatoria antes de producción"): no deployed
 * environment exists yet to calibrate against. Real calibration, with its own ADR naming hardware
 * and a date, is change 11's job (design.md §11, paso 11), not this one's.
 */
public record Argon2Profile(int memoryCostKib, int timeCost, int parallelism, int hashLength,
        int saltLength) {

    /** OWASP's own minimum recommendation for Argon2id at {@code timeCost=2} ({@code docs/03} §4.1). */
    public static final int FLOOR_MEMORY_COST_KIB = 19_456;
    public static final int FLOOR_TIME_COST = 3;
    public static final int FLOOR_PARALLELISM = 1;
    public static final int FLOOR_HASH_LENGTH_BYTES = 32;
    public static final int FLOOR_SALT_LENGTH_BYTES = 16;

    /** The Argon2id pepper's own required length, applied as {@code secret} (design.md, decision 6). */
    public static final int PEPPER_LENGTH_BYTES = 32;

    public Argon2Profile {
        if (memoryCostKib <= 0) {
            throw new IllegalArgumentException("memoryCostKib must be positive, was " + memoryCostKib);
        }
        if (timeCost <= 0) {
            throw new IllegalArgumentException("timeCost must be positive, was " + timeCost);
        }
        if (parallelism <= 0) {
            throw new IllegalArgumentException("parallelism must be positive, was " + parallelism);
        }
        if (hashLength <= 0) {
            throw new IllegalArgumentException("hashLength must be positive, was " + hashLength);
        }
        if (saltLength <= 0) {
            throw new IllegalArgumentException("saltLength must be positive, was " + saltLength);
        }
    }

    /** The declared floor profile — see this class's own Javadoc on why it is a floor, not a calibration. */
    public static Argon2Profile floor() {
        return new Argon2Profile(FLOOR_MEMORY_COST_KIB, FLOOR_TIME_COST, FLOOR_PARALLELISM,
                FLOOR_HASH_LENGTH_BYTES, FLOOR_SALT_LENGTH_BYTES);
    }
}

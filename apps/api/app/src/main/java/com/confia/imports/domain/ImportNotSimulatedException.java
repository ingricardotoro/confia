package com.confia.imports.domain;

import com.confia.kernel.DomainException;

/**
 * The file that is being applied was never simulated by this account. Published to clients as the Problem Details
 * code {@code import-not-simulated}.
 */
public final class ImportNotSimulatedException extends DomainException {

    public static final String CODE = "import-not-simulated";

    public ImportNotSimulatedException() {
        super(CODE, "the file has no simulation by this account");
    }
}

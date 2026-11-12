package com.confia.imports.domain;

import com.confia.kernel.DomainException;

/**
 * The file that is being applied is not the one the account simulated last. Published to clients as the Problem
 * Details code {@code import-file-changed}.
 */
public final class ImportFileChangedException extends DomainException {

    public static final String CODE = "import-file-changed";

    public ImportFileChangedException() {
        super(CODE, "the file differs from the one that was simulated");
    }
}

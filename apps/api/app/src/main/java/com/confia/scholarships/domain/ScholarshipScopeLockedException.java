package com.confia.scholarships.domain;

import com.confia.kernel.DomainException;

/**
 * The cap per period of a scholarship with approved awards was raised or removed, or a concept was added to it: it only narrows, and
 * widening it is another scholarship. Published to clients as the Problem Details code {@code scholarship-scope-locked}.
 */
public final class ScholarshipScopeLockedException extends DomainException {

    public static final String CODE = "scholarship-scope-locked";

    public ScholarshipScopeLockedException() {
        super(CODE, "the cap of a scholarship with approved awards only goes down and its concepts are only removed");
    }
}

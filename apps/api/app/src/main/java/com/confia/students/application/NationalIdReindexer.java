package com.confia.students.application;

import com.confia.kernel.InstitutionId;
import java.util.UUID;

/**
 * Output port of the re-index of the blind indexes after a key rotation: it decrypts a stored document and
 * computes its blind index under the current key version. It runs inside the transaction of the use case and
 * nothing it handles is ever logged.
 */
public interface NationalIdReindexer {

    /** The key version that new indexes carry, such as {@code v2}. */
    String currentVersion();

    /** The index, under the current version, of the document held in {@code encrypted} for that row. */
    String recompute(InstitutionId institutionId, UUID studentId, String encrypted);
}

package com.confia.students.infrastructure;

import com.confia.kernel.AeadIntegrityException;
import com.confia.kernel.InstitutionId;
import com.confia.shared.crypto.BlindIndexService;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.students.application.NationalIdIndexes;
import com.confia.students.application.NationalIdProtector;
import com.confia.students.application.NationalIdReindexer;
import com.confia.students.application.ProtectedNationalId;
import com.confia.students.domain.NationalId;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The adapter of {@link NationalIdProtector} over the shared column encryption: the normalised document
 * is encrypted with the data-encryption key of the institution, bound to {@code students_student},
 * {@code national_id_encrypted} and the row (so a value copied to another row fails to decrypt), and its
 * blind index is computed with the key that the shared component derives for this purpose.
 *
 * <p>It must run inside the transaction of the use case: the first encryption of an institution creates
 * its data-encryption key. Nothing here is ever logged.
 */
public final class ColumnEncryptionNationalIdProtector implements NationalIdProtector, NationalIdReindexer, NationalIdIndexes {

    static final String TABLE = "students_student";
    static final String COLUMN = "national_id_encrypted";
    static final String BLIND_INDEX_PURPOSE = "students.national-id";

    private final ColumnEncryptionService encryption;
    private final BlindIndexService blindIndexes;

    public ColumnEncryptionNationalIdProtector(ColumnEncryptionService encryption, BlindIndexService blindIndexes) {
        this.encryption = encryption;
        this.blindIndexes = blindIndexes;
    }

    @Override
    public ProtectedNationalId protect(InstitutionId institutionId, UUID studentId, NationalId nationalId) {
        String encrypted = encryption.encryptForNewValue(TABLE, COLUMN, institutionId, rowIdOf(institutionId, studentId),
                nationalId.normalized().getBytes(StandardCharsets.UTF_8));
        List<String> candidates = blindIndexes.candidates(BLIND_INDEX_PURPOSE, institutionId, nationalId.normalized());
        return new ProtectedNationalId(encrypted, candidates.getFirst(), candidates);
    }

    @Override
    public List<String> lookupIndexes(InstitutionId institutionId, NationalId nationalId) {
        return blindIndexes.candidates(BLIND_INDEX_PURPOSE, institutionId, nationalId.normalized());
    }

    @Override
    public String currentVersion() {
        return blindIndexes.currentVersion();
    }

    /** Decrypts the stored document of the row (bound to it) and indexes it under the current version. */
    @Override
    public String recompute(InstitutionId institutionId, UUID studentId, String encrypted) {
        byte[] plain;
        try {
            plain = encryption.decrypt(TABLE, COLUMN, institutionId, rowIdOf(institutionId, studentId), encrypted);
        } catch (AeadIntegrityException e) {
            // The row is named by its identifier only; the cause carries nothing of the value and is not chained.
            throw new IllegalStateException("the stored national id of student " + studentId
                    + " fails its integrity check and cannot be re-indexed");
        }
        try {
            return blindIndexes.compute(BLIND_INDEX_PURPOSE, institutionId, new String(plain, StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /**
     * The additional authenticated data names the row by its composite key, {@code institutionId:id},
     * the shape the other encrypted table uses too.
     */
    static String rowIdOf(InstitutionId institutionId, UUID studentId) {
        return institutionId.value() + ":" + studentId;
    }
}

package com.confia.students.infrastructure;

import com.confia.kernel.InstitutionId;
import com.confia.shared.crypto.BlindIndexService;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.students.application.NationalIdProtector;
import com.confia.students.application.ProtectedNationalId;
import com.confia.students.domain.NationalId;
import java.nio.charset.StandardCharsets;
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
public final class ColumnEncryptionNationalIdProtector implements NationalIdProtector {

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
        String blindIndex = blindIndexes.compute(BLIND_INDEX_PURPOSE, institutionId, nationalId.normalized());
        return new ProtectedNationalId(encrypted, blindIndex);
    }

    /**
     * The additional authenticated data names the row by its composite key, {@code institutionId:id},
     * the shape the other encrypted table uses too.
     */
    static String rowIdOf(InstitutionId institutionId, UUID studentId) {
        return institutionId.value() + ":" + studentId;
    }
}

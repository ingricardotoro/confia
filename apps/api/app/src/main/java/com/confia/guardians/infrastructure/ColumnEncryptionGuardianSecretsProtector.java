package com.confia.guardians.infrastructure;

import com.confia.guardians.application.GuardianSecretsProtector;
import com.confia.guardians.application.ProtectedNationalId;
import com.confia.guardians.domain.NationalId;
import com.confia.guardians.domain.Rtn;
import com.confia.kernel.InstitutionId;
import com.confia.shared.crypto.BlindIndexService;
import com.confia.shared.crypto.ColumnEncryptionService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * The adapter of {@link GuardianSecretsProtector} over the shared column encryption: the normalised document and the
 * RTN are encrypted with the data-encryption key of the institution, each bound to {@code guardians_guardian}, its
 * own column and the row (so a value copied to another row or to the other column fails to decrypt), and the blind
 * index of the document is computed with the key the shared component derives for the purpose
 * {@code guardians.national-id} from the same versioned keys the students use.
 *
 * <p>It must run inside the transaction of the use case: the first encryption of an institution creates its
 * data-encryption key. Nothing here is ever logged.
 */
public final class ColumnEncryptionGuardianSecretsProtector implements GuardianSecretsProtector {

    static final String TABLE = "guardians_guardian";
    static final String NATIONAL_ID_COLUMN = "national_id_encrypted";
    static final String RTN_COLUMN = "rtn_encrypted";
    static final String BLIND_INDEX_PURPOSE = "guardians.national-id";

    private final ColumnEncryptionService encryption;
    private final BlindIndexService blindIndexes;

    public ColumnEncryptionGuardianSecretsProtector(ColumnEncryptionService encryption,
            BlindIndexService blindIndexes) {
        this.encryption = encryption;
        this.blindIndexes = blindIndexes;
    }

    @Override
    public ProtectedNationalId protect(InstitutionId institutionId, UUID guardianId, NationalId nationalId) {
        String encrypted = encryption.encryptForNewValue(TABLE, NATIONAL_ID_COLUMN, institutionId,
                rowIdOf(institutionId, guardianId), nationalId.normalized().getBytes(StandardCharsets.UTF_8));
        List<String> candidates = blindIndexes.candidates(BLIND_INDEX_PURPOSE, institutionId, nationalId.normalized());
        return new ProtectedNationalId(encrypted, candidates.getFirst(), candidates);
    }

    @Override
    public String protect(InstitutionId institutionId, UUID guardianId, Rtn rtn) {
        return encryption.encryptForNewValue(TABLE, RTN_COLUMN, institutionId, rowIdOf(institutionId, guardianId),
                rtn.digits().getBytes(StandardCharsets.UTF_8));
    }

    /** The additional authenticated data names the row by its composite key, {@code institutionId:id}. */
    static String rowIdOf(InstitutionId institutionId, UUID guardianId) {
        return institutionId.value() + ":" + guardianId;
    }
}

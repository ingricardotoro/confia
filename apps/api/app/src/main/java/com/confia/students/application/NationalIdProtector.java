package com.confia.students.application;

import com.confia.kernel.InstitutionId;
import com.confia.students.domain.NationalId;
import java.util.UUID;

/**
 * Output port that turns a national identity document into what is stored: the encrypted value, bound to
 * the row it belongs to, and the blind index. It must run inside the transaction of the use case, because
 * the first encryption of an institution may create its data-encryption key.
 */
public interface NationalIdProtector {

    ProtectedNationalId protect(InstitutionId institutionId, UUID studentId, NationalId nationalId);
}

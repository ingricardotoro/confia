package com.confia.guardians.application;

import com.confia.guardians.domain.NationalId;
import com.confia.guardians.domain.Rtn;
import com.confia.kernel.InstitutionId;
import java.util.UUID;

/**
 * Output port that turns the national identity document and the RTN of a guardian into what is stored: the
 * encrypted value, bound to the row it belongs to, and, for the document, the blind index. It must run inside the
 * transaction of the use case, because the first encryption of an institution may create its data-encryption key.
 */
public interface GuardianSecretsProtector {

    ProtectedNationalId protect(InstitutionId institutionId, UUID guardianId, NationalId nationalId);

    /** The encrypted column value of the RTN. */
    String protect(InstitutionId institutionId, UUID guardianId, Rtn rtn);
}

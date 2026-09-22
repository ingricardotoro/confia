package com.confia.shared.audit;

import com.confia.kernel.InstitutionId;

/**
 * Recalculates {@code shared_audit_log}'s hash chain from an institution's genesis row and compares
 * it against what is stored (design.md decision 11; specs/audit-trail/spec.md, "Contrato observable
 * del verificador de cadena"). A rutina invocable directamente, not a scheduled task: no
 * infrastructure of programación de tareas exists in this cut (cambio 9 owns that,
 * specs/audit-trail/spec.md, "Alcance de la verificación de cadena sin programación recurrente").
 */
public interface AuditChainVerifier {

    /** Recalculates the whole chain of {@code institutionId} from its genesis record. */
    AuditChainVerification verifyChainOf(InstitutionId institutionId);
}

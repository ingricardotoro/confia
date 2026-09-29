package com.confia.identity.application;

/**
 * Result of {@link ConsumeRecoveryCode}: whether the presented recovery code was accepted and
 * consumed. Carries no count or warning field on purpose — the low-recovery-codes signal
 * (column-encryption-and-mfa-totp design.md, §4.3, decision D8) is calculated and audited, never
 * returned to a caller, exactly as specs/identity/spec.md's own requirement frames it: "el sistema
 * DEBE ... producir y auditar una señal de aviso", never "informar al llamador".
 */
public record ConsumeRecoveryCodeDecision(boolean accepted) {
}

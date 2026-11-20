import { describe, expect, it } from "vitest";
import admin from "../../i18n/locales/es-HN/admin.json";
import { flattenCatalogKeys } from "../../test/text-keys.ts";
import { KNOWN_PROBLEM_CODES } from "./problem.ts";
import { PROBLEM_KEYS } from "./problem-keys.ts";

// admin-web-foundations-and-session, task 4.1 (AW48, design.md D7 and section 7). The type check
// proves the map is exhaustive; this proves each key it names exists in the catalog and reads right.
const CATALOG_KEYS = flattenCatalogKeys(admin);

describe("PROBLEM_KEYS", () => {
  it("names exactly the thirty-five codes and the unknown case", () => {
    expect(Object.keys(PROBLEM_KEYS).sort()).toEqual([...KNOWN_PROBLEM_CODES, "unknown"].sort());
  });

  it("names only keys that exist in the es-HN admin catalog", () => {
    for (const [code, key] of Object.entries(PROBLEM_KEYS)) {
      expect(CATALOG_KEYS, code).toContain(key);
    }
  });

  it.each([
    ["authentication-failed", "problem.authenticationFailed"],
    ["second-factor-invalid", "problem.secondFactorInvalid"],
    ["session-expired", "notice.sessionExpired"],
    ["mfa-already-enrolled", "notice.alreadyEnrolled"],
    ["validation-failed", "problem.notProcessed"],
    ["too-many-requests", "problem.rateLimited"],
    ["capacity-exceeded", "problem.capacityExceeded"],
    ["token-invalid", "notice.verificationInterrupted"],
    ["token-expired", "notice.verificationInterrupted"],
    ["internal-error", "problem.unexpected"],
    ["unknown", "problem.unexpected"],
    ["forbidden", "problem.forbidden"],
    ["resource-not-found", "problem.notFound"],
    ["academic-year-overlap", "problem.academicYearOverlap"],
    ["academic-year-already-active", "problem.academicYearAlreadyActive"],
    ["academic-year-closed", "problem.academicYearClosed"],
    ["duplicate-name", "problem.duplicateName"],
    ["duplicate-payment-concept-code", "problem.duplicatePaymentConceptCode"],
    ["inactive-reference", "problem.inactiveReference"],
    ["duplicate-student-code", "problem.duplicateStudentCode"],
    ["duplicate-national-id", "problem.duplicateNationalId"],
    ["student-not-active", "problem.studentNotActive"],
    ["section-full", "problem.sectionFull"],
    ["duplicate-active-enrollment", "problem.duplicateActiveEnrollment"],
    ["invalid-enrollment-state", "problem.invalidEnrollmentState"],
    ["transfer-year-mismatch", "problem.transferYearMismatch"],
    ["invalid-responsibility-split", "problem.invalidResponsibilitySplit"],
    ["guardian-not-active", "problem.guardianNotActive"],
    ["consent-channel-unavailable", "problem.consentChannelUnavailable"],
    ["import-not-simulated", "problem.importNotSimulated"],
    ["import-file-changed", "problem.importFileChanged"],
    ["price-validity-overlap", "problem.priceValidityOverlap"],
    ["price-list-archived", "problem.priceListArchived"],
    ["duplicate-tax-rate-code", "problem.duplicateTaxRateCode"],
    ["tax-rate-period-overlap", "problem.taxRatePeriodOverlap"],
    ["plan-not-editable", "problem.planNotEditable"],
    ["plan-without-items", "problem.planWithoutItems"],
    ["duplicate-plan-item", "problem.duplicatePlanItem"],
    ["stale-version", "problem.staleVersion"],
    ["accrual-run-in-progress", "problem.accrualRunInProgress"],
    ["plan-item-invalid", "problem.planItemInvalid"],
    ["idempotency-key-missing", "problem.unexpected"],
    ["idempotency-payload-mismatch", "problem.idempotencyPayloadMismatch"],
    ["charge-already-voided", "problem.chargeAlreadyVoided"],
    ["charge-not-voidable", "problem.chargeNotVoidable"],
    ["cash-session-already-open", "problem.cashSessionAlreadyOpen"],
    ["cash-session-not-open", "problem.cashSessionNotOpen"],
    ["cash-session-not-awaiting-approval", "problem.cashSessionNotAwaitingApproval"],
    ["cash-expected-negative", "problem.cashExpectedNegative"],
    ["cash-difference-reason-required", "problem.cashDifferenceReasonRequired"],
    ["cash-approval-same-actor", "problem.cashApprovalSameActor"],
  ] as const)("maps %s to %s", (code, key) => {
    expect(PROBLEM_KEYS[code]).toBe(key);
  });

  it("shows the generic message for every code that has no text of its own (design.md section 7)", () => {
    for (const code of ["csrf-invalid", "unsupported-media-type", "authentication-required"] as const) {
      expect(PROBLEM_KEYS[code], code).toBe("problem.unexpected");
    }
  });

  it("holds the texts fixed by the documentation, in the formal address", () => {
    expect(admin.problem.secondFactorInvalid).toBe("El código de verificación no es correcto o ya expiró. Genere uno nuevo en su aplicación.");
    expect(admin.problem.rateLimited).toBe("Hizo demasiadas solicitudes seguidas. Espere unos segundos e intente de nuevo.");
    expect(admin.problem.unexpected).toBe(
      "Ocurrió un error inesperado y la operación no se completó. Intente de nuevo; si el problema continúa, comparta el identificador con soporte.",
    );
  });
});

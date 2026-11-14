import { describe, expect, it } from "vitest";
import admin from "../../i18n/locales/es-HN/admin.json";
import { flattenCatalogKeys } from "../../test/text-keys.ts";
import { KNOWN_PROBLEM_CODES } from "./problem.ts";
import { PROBLEM_KEYS } from "./problem-keys.ts";

// admin-web-foundations-and-session, task 4.1 (AW48, design.md D7 and section 7). The type check
// proves the map is exhaustive; this proves each key it names exists in the catalog and reads right.
const CATALOG_KEYS = flattenCatalogKeys(admin);

describe("PROBLEM_KEYS", () => {
  it("names exactly the twenty seven codes and the unknown case", () => {
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
    ["inactive-reference", "problem.inactiveReference"],
    ["duplicate-student-code", "problem.duplicateStudentCode"],
    ["duplicate-national-id", "problem.duplicateNationalId"],
    ["student-not-active", "problem.studentNotActive"],
    ["section-full", "problem.sectionFull"],
    ["duplicate-active-enrollment", "problem.duplicateActiveEnrollment"],
    ["invalid-enrollment-state", "problem.invalidEnrollmentState"],
    ["transfer-year-mismatch", "problem.transferYearMismatch"],
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

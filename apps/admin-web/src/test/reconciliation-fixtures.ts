import {
  ReconciliationPageResponse,
  ReconciliationPreviewResponse,
  ReconciliationResponse,
  type ReconciliationPageResponseOutput,
  type ReconciliationPreviewResponseOutput,
  type ReconciliationResponseOutput,
} from "@confia/contracts/admin";

/**
 * Answers of the reconciliation endpoints for the tests of the screen, checked against the generated contract so a fixture
 * can never drift from what the server serves. Counts only: an acta holds no value of a file and no name of a person.
 */
const KIND_COUNTS = { created: 0, rejected: 0 };

export const YEAR_ID = "11111111-1111-4111-8111-111111111111";
export const OTHER_YEAR_ID = "22222222-2222-4222-8222-222222222222";

export function preview(override: Partial<ReconciliationPreviewResponseOutput> = {}): ReconciliationPreviewResponseOutput {
  return ReconciliationPreviewResponse.parse({
    academicYearId: YEAR_ID,
    enrollmentsActive: 118,
    students: { created: 120, rejected: 2 },
    guardians: { created: 90, rejected: 0 },
    guardianLinks: { created: 118, rejected: 1 },
    enrollments: { created: 118, rejected: 3 },
    ...override,
  });
}

export function acta(override: Partial<ReconciliationResponseOutput> = {}): ReconciliationResponseOutput {
  return ReconciliationResponse.parse({
    id: crypto.randomUUID(),
    academicYearId: YEAR_ID,
    officialReference: "Oficio 0123-2026",
    officialEnrollmentCount: 120,
    enrollmentsActive: 118,
    difference: -2,
    students: { ...KIND_COUNTS, created: 120 },
    guardians: { ...KIND_COUNTS, created: 90 },
    guardianLinks: { ...KIND_COUNTS, created: 118 },
    enrollments: { ...KIND_COUNTS, created: 118 },
    signedBy: "33333333-3333-4333-8333-333333333333",
    signedAt: "2026-11-13T15:30:00Z",
    ...override,
  });
}

export function actaPage(
  items: readonly ReconciliationResponseOutput[],
  paging: { readonly page?: number; readonly size?: number; readonly total?: number } = {},
): ReconciliationPageResponseOutput {
  return ReconciliationPageResponse.parse({ items, page: paging.page ?? 0, size: paging.size ?? 10, total: paging.total ?? items.length });
}

/** A 200 or 201 of the test server with the given body. */
export const jsonReply = (body: unknown, status = 200) => ({ status, body, contentType: "application/json" });

import { describe, expect, it } from "vitest";
import { createCatalogModel, DEFAULT_CATALOG } from "./catalog-model.ts";
import { conceptId } from "./catalog-seed.ts";
import { assignmentRowId, createDiscountsModel, DEFAULT_DISCOUNTS, discountRowId, type DiscountsModel } from "./discounts-model.ts";
import { createStudentsModel, DEFAULT_STUDENTS } from "./students-model.ts";

// The simulated discounts and agreement assignments: the refusals the real server gives (a repeated code, the person who requested an assignment deciding
// it, an assignment that is not in the state of the decision, a reason out of bounds, a benefit or a scope that is locked), the Idempotency-Key kept per
// decision, and the list the server serves. Student 1 has a pending assignment another person requested; student 4 one the person requested; student 3
// an approved one. The agreement is the second discount.
const NOW = () => new Date("2026-11-20T18:00:00Z");
const DISCOUNTS = "/api/v1/scholarships/discounts";
const ASSIGNMENTS = "/api/v1/scholarships/discount-assignments";
const STUDENT_1 = "00000000-0000-4000-8000-000000005001";
const STUDENT_4 = "00000000-0000-4000-8000-000000005004";
const CAMPAIGN = discountRowId(1);
const AGREEMENT = discountRowId(2);
const REASON = "El estudiante ya no cumple las condiciones del convenio";

function build(override: Partial<typeof DEFAULT_DISCOUNTS> = {}): DiscountsModel {
  const students = createStudentsModel(DEFAULT_STUDENTS);
  const catalog = createCatalogModel(DEFAULT_CATALOG);
  return createDiscountsModel({ ...DEFAULT_DISCOUNTS, ...override }, { students: () => students.rows().students, concepts: () => catalog.rows(), now: NOW });
}

const send = (target: DiscountsModel, method: string, path: string, body?: unknown, key?: string, query = "") =>
  target.handle({ method, path, query: new URLSearchParams(query), body, headers: { "idempotency-key": key } });
const code = (response: { body?: unknown } | undefined): string => ((response?.body as { type?: string } | undefined)?.type ?? "").split("/").pop() ?? "";
const bodyOf = (response: { body?: unknown } | undefined) => response?.body as Record<string, unknown>;
const request = (student = STUDENT_1) => ({ studentId: student, discountId: AGREEMENT, validFrom: "2026-02-01", validTo: "2026-06-30" });
const edit = (override: Record<string, unknown> = {}) => ({
  version: 0,
  name: "Convenio",
  benefitType: "PERCENTAGE",
  percent: "20.00",
  validFrom: "2026-01-01",
  validTo: "2026-12-31",
  stackable: false,
  conceptIds: [conceptId(1)],
  ...override,
});

describe("the discounts", () => {
  it("lists the seeded ones by code, filtered by class and paged as the server does", () => {
    const target = build();

    expect((bodyOf(send(target, "GET", DISCOUNTS))["items"] as { code: string }[]).map((row) => row.code)).toEqual(["CAMP10", "CONV20"]);
    expect((bodyOf(send(target, "GET", DISCOUNTS, undefined, undefined, "kind=agreement"))["items"] as { code: string }[]).map((row) => row.code)).toEqual(["CONV20"]);
    expect(bodyOf(send(target, "GET", DISCOUNTS, undefined, undefined, "active=false&size=1"))).toMatchObject({ items: [], total: 0, size: 1 });
    expect(code(send(target, "GET", DISCOUNTS, undefined, undefined, "size=500"))).toBe("validation-failed");
    expect(code(send(target, "GET", DISCOUNTS, undefined, undefined, "sort=nope,asc"))).toBe("validation-failed");
  });

  it("creates one, refuses a code already used whatever its capitals, and refuses an end before the start", () => {
    const target = build();
    const body = { code: "NUEVO", name: "Nuevo", kind: "campaign", benefitType: "PERCENTAGE", percent: "5", stackable: true, validFrom: "2026-01-01", validTo: "2026-02-01", conceptIds: [conceptId(1)] };

    expect(send(target, "POST", DISCOUNTS, body)?.status).toBe(201);
    expect(code(send(target, "POST", DISCOUNTS, { ...body, code: "nuevo" }))).toBe("duplicate-discount-code");
    expect(code(send(target, "POST", DISCOUNTS, { ...body, code: "OTRO", validTo: "2025-12-31" }))).toBe("validation-failed");
  });

  it("refuses an edit with another version, and locks the benefit and the scope once an assignment was approved", () => {
    const target = build();
    const path = `${DISCOUNTS}/${AGREEMENT}`;

    expect(code(send(target, "PUT", path, edit({ version: 4 })))).toBe("stale-version");
    expect(code(send(target, "PUT", path, edit({ percent: "30.00" })))).toBe("discount-benefit-locked");
    expect(code(send(target, "PUT", path, edit({ validTo: "2027-06-30" })))).toBe("discount-scope-locked");
    expect(code(send(target, "PUT", path, edit({ validFrom: "2026-03-01", validTo: "2026-06-30" })))).toBe("validation-failed");
    expect(send(target, "PUT", path, edit({ name: "Convenio 2026" }))?.status).toBe(200);
    expect(bodyOf(send(target, "GET", path))).toMatchObject({ inUse: true, name: "Convenio 2026", version: 1 });
  });

  it("deactivates and reactivates without changing anything else", () => {
    const target = build();

    expect(bodyOf(send(target, "POST", `${DISCOUNTS}/${CAMPAIGN}/deactivate`))).toMatchObject({ active: false });
    expect(bodyOf(send(target, "POST", `${DISCOUNTS}/${CAMPAIGN}/reactivate`))).toMatchObject({ active: true });
  });
});

describe("the assignments", () => {
  it("lists the seeded ones, newest first, filtered by status and student", () => {
    const target = build();

    expect((bodyOf(send(target, "GET", ASSIGNMENTS))["items"] as { studentCode: string }[]).length).toBe(3);
    expect(bodyOf(send(target, "GET", ASSIGNMENTS, undefined, undefined, "status=approved"))["total"]).toBe(1);
    expect(bodyOf(send(target, "GET", ASSIGNMENTS, undefined, undefined, `studentId=${STUDENT_4}`))["total"]).toBe(1);
    expect(code(send(target, "GET", ASSIGNMENTS, undefined, undefined, "status=other"))).toBe("validation-failed");
  });

  it("requests one pending, refuses a campaign discount and a validity outside the discount, and does not know a student that does not exist", () => {
    const target = build();

    expect(bodyOf(send(target, "POST", ASSIGNMENTS, request()))).toMatchObject({ status: "pending", discountCode: "CONV20" });
    expect(code(send(target, "POST", ASSIGNMENTS, { ...request(), discountId: CAMPAIGN }))).toBe("inactive-reference");
    expect(code(send(target, "POST", ASSIGNMENTS, { ...request(), validTo: "2027-01-31" }))).toBe("validation-failed");
    expect(code(send(target, "POST", ASSIGNMENTS, { ...request(), studentId: "00000000-0000-4000-8000-0000000000ff" }))).toBe("resource-not-found");
  });

  it("refuses the person who requested it, and approves the one another person requested", () => {
    const target = build();

    expect(code(send(target, "POST", `${ASSIGNMENTS}/${assignmentRowId(2)}/approve`, undefined, "key-1"))).toBe("self-approval-forbidden");
    expect(bodyOf(send(target, "POST", `${ASSIGNMENTS}/${assignmentRowId(1)}/approve`, undefined, "key-2"))).toMatchObject({ status: "approved" });
    expect(code(send(target, "POST", `${ASSIGNMENTS}/${assignmentRowId(1)}/approve`, undefined, "key-3"))).toBe("invalid-discount-assignment-state");
  });

  it("needs a key, answers the same key and body the same, and refuses the same key for another body", () => {
    const target = build();
    const path = `${ASSIGNMENTS}/${assignmentRowId(1)}`;

    expect(code(send(target, "POST", `${path}/approve`))).toBe("idempotency-key-missing");
    expect(send(target, "POST", `${path}/approve`, undefined, "key-1")?.status).toBe(200);
    expect(send(target, "POST", `${path}/approve`, undefined, "key-1")?.status).toBe(200);
    expect(code(send(target, "POST", `${path}/reject`, { reason: REASON }, "key-1"))).toBe("idempotency-payload-mismatch");
  });

  it("asks for a reason of 20 to 500 characters to reject or revoke, and revokes only an approved one", () => {
    const target = build();

    expect(code(send(target, "POST", `${ASSIGNMENTS}/${assignmentRowId(1)}/reject`, { reason: "corto" }, "key-1"))).toBe("validation-failed");
    expect(bodyOf(send(target, "POST", `${ASSIGNMENTS}/${assignmentRowId(1)}/reject`, { reason: REASON }, "key-2"))).toMatchObject({ status: "rejected", decision: { reason: REASON } });
    expect(code(send(target, "POST", `${ASSIGNMENTS}/${assignmentRowId(1)}/revoke`, { reason: REASON }, "key-3"))).toBe("invalid-discount-assignment-state");
    expect(bodyOf(send(target, "POST", `${ASSIGNMENTS}/${assignmentRowId(3)}/revoke`, { reason: REASON }, "key-4"))).toMatchObject({ status: "revoked", revocation: { kind: "revoked" } });
  });

  it("answers 404 for an assignment that does not exist and has nothing to list when nothing is seeded", () => {
    expect(code(send(build(), "GET", `${ASSIGNMENTS}/${assignmentRowId(99)}`))).toBe("resource-not-found");
    expect(bodyOf(send(build({ seed: "none" }), "GET", ASSIGNMENTS))["total"]).toBe(0);
  });

  it("fails the first reads and then recovers, when so configured", () => {
    const target = build({ reads: "failing-first" });

    expect(send(target, "GET", DISCOUNTS)?.status).toBe(500);
    expect(send(target, "GET", DISCOUNTS)?.status).toBe(500);
    expect(send(target, "GET", DISCOUNTS)?.status).toBe(500);
    expect(send(target, "GET", DISCOUNTS)?.status).toBe(200);
    expect(send(target, "GET", "/api/v1/scholarships/other")).toBeUndefined();
  });
});

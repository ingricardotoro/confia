import { describe, expect, it } from "vitest";
import { createCatalogModel, DEFAULT_CATALOG } from "./catalog-model.ts";
import { conceptId } from "./catalog-seed.ts";
import { assignmentRowId, createDiscountsModel, DEFAULT_DISCOUNTS, discountRowId, type DiscountsModel } from "./discounts-model.ts";
import { ME } from "./payments-model.ts";
import { createStudentsModel, DEFAULT_STUDENTS } from "./students-model.ts";

// The simulated discounts and agreement assignments: the refusals the real server gives (a repeated code, the person who requested an assignment deciding
// it, an assignment that is not in the state of the decision, a reason out of bounds, a benefit or a scope that is locked), the Idempotency-Key kept per
// decision, the approval of a campaign discount by another person (S7), and the list the server serves. Student 1 has a pending assignment another person requested; student 4 one the person requested; student 3
// an approved one. The agreement is the second discount.
const NOW = () => new Date("2026-11-20T18:00:00Z");
const DISCOUNTS = "/api/v1/scholarships/discounts";
const ASSIGNMENTS = "/api/v1/scholarships/discount-assignments";
const STUDENT_1 = "00000000-0000-4000-8000-000000005001";
const STUDENT_4 = "00000000-0000-4000-8000-000000005004";
const CAMPAIGN = discountRowId(1);
const AGREEMENT = discountRowId(2);
const WAITING = discountRowId(3);
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

    expect((bodyOf(send(target, "GET", DISCOUNTS))["items"] as { code: string }[]).map((row) => row.code)).toEqual(["CAMP10", "CAMP15", "CONV20"]);
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

    expect(bodyOf(send(target, "POST", `${DISCOUNTS}/${AGREEMENT}/deactivate`))).toMatchObject({ active: false });
    expect(bodyOf(send(target, "POST", `${DISCOUNTS}/${AGREEMENT}/reactivate`))).toMatchObject({ active: true });
  });
});

describe("the approval of a campaign discount", () => {
  const body = { code: "NUEVO", name: "Nuevo", kind: "campaign", benefitType: "PERCENTAGE", percent: "5", stackable: true, validFrom: "2026-01-01", validTo: "2026-02-01", conceptIds: [conceptId(1)] };

  it("is born pending when it is a campaign discount, created by the person, and approved when it is an agreement one", () => {
    const target = build();

    expect(bodyOf(send(target, "POST", DISCOUNTS, body))).toMatchObject({ approvalStatus: "pending", createdBy: ME });
    expect(bodyOf(send(target, "POST", DISCOUNTS, { ...body, code: "CONV", kind: "agreement" }))).toMatchObject({ approvalStatus: "approved" });
    expect(bodyOf(send(target, "GET", `${DISCOUNTS}/${WAITING}`))).toMatchObject({ approvalStatus: "pending" });
  });

  it("is approved by another person with the version they saw, with a key, and answers the same key the same", () => {
    const target = build();
    const path = `${DISCOUNTS}/${WAITING}`;

    expect(code(send(target, "POST", `${path}/approve`, { version: 0 }))).toBe("idempotency-key-missing");
    expect(code(send(target, "POST", `${path}/approve`, { version: 9 }, "key-1"))).toBe("stale-version");
    expect(bodyOf(send(target, "POST", `${path}/approve`, { version: 0 }, "key-2"))).toMatchObject({ approvalStatus: "approved", version: 1 });
    expect(bodyOf(send(target, "POST", `${path}/approve`, { version: 0 }, "key-2"))).toMatchObject({ approvalStatus: "approved" });
    expect(code(send(target, "POST", `${path}/reject`, { version: 0, reason: REASON }, "key-2"))).toBe("idempotency-payload-mismatch");
    expect(code(send(target, "POST", `${path}/approve`, { version: 1 }, "key-3"))).toBe("invalid-discount-change-state");
  });

  it("refuses the person who created it, and rejects with a reason of 20 to 500 characters", () => {
    const target = build();
    const created = bodyOf(send(target, "POST", DISCOUNTS, body));
    const own = `${DISCOUNTS}/${String(created["id"])}`;

    expect(code(send(target, "POST", `${own}/approve`, { version: 0 }, "key-1"))).toBe("self-approval-forbidden");
    expect(code(send(target, "POST", `${DISCOUNTS}/${WAITING}/reject`, { version: 0, reason: "corto" }, "key-2"))).toBe("validation-failed");
    expect(bodyOf(send(target, "POST", `${DISCOUNTS}/${WAITING}/reject`, { version: 0, reason: REASON }, "key-3"))).toMatchObject({ approvalStatus: "rejected", active: false });
  });

  it("refuses to edit or reactivate what waits (409 discount-pending-approval), and still lets it be deactivated", () => {
    const target = build();
    const path = `${DISCOUNTS}/${WAITING}`;

    expect(code(send(target, "PUT", path, edit()))).toBe("discount-pending-approval");
    expect(code(send(target, "POST", `${path}/reactivate`))).toBe("discount-pending-approval");
    expect(bodyOf(send(target, "POST", `${path}/deactivate`))).toMatchObject({ active: false });
  });

  it("leaves a proposal when an approved campaign discount is widened, keeps the terms in force, and applies it when another person approves it", () => {
    const target = build();
    const path = `${DISCOUNTS}/${CAMPAIGN}`;

    const proposed = bodyOf(send(target, "PUT", path, edit({ name: "Campaña", percent: "25.00", stackable: true })));
    expect(proposed).toMatchObject({ percent: "10.00", version: 1, pendingChange: { percent: "25.00", requestedBy: ME, reactivate: false } });
    expect(code(send(target, "PUT", path, edit({ version: 1 })))).toBe("discount-pending-approval");
    // The same person proposed it, so only another one decides (the simulated session is the person, so the server refuses it).
    expect(code(send(target, "POST", `${path}/approve`, { version: 1 }, "key-1"))).toBe("self-approval-forbidden");
  });

  it("leaves a proposal, not an activation, when a campaign discount is reactivated", () => {
    const target = build();
    const path = `${DISCOUNTS}/${WAITING}`;
    send(target, "POST", `${path}/approve`, { version: 0 }, "key-1");
    send(target, "POST", `${path}/deactivate`);

    // Reactivating a campaign discount is a proposal of the person: it stays off.
    expect(bodyOf(send(target, "POST", `${path}/reactivate`))).toMatchObject({ active: false, pendingChange: { reactivate: true } });
  });

  it("locks the stackable mark of a discount in use like its type and value", () => {
    const target = build();

    expect(code(send(target, "PUT", `${DISCOUNTS}/${AGREEMENT}`, edit({ stackable: true })))).toBe("discount-benefit-locked");
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

  it("keeps the version and the benefit of the discount at the request, and refuses to approve after the discount changed", () => {
    const target = build();
    const requested = bodyOf(send(target, "POST", ASSIGNMENTS, request()));

    expect(requested).toMatchObject({ discountVersion: 0, benefitType: "PERCENTAGE", percent: "20.00" });
    send(target, "PUT", `${DISCOUNTS}/${AGREEMENT}`, edit({ name: "Convenio 2026" }));
    expect(bodyOf(send(target, "GET", `${DISCOUNTS}/${AGREEMENT}`))).toMatchObject({ version: 1 });
    // The seeded pending assignment was requested for version 0 of the discount.
    expect(code(send(target, "POST", `${ASSIGNMENTS}/${assignmentRowId(1)}/approve`, undefined, "key-1"))).toBe("discount-terms-changed");
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

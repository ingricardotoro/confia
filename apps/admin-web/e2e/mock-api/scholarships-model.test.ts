import { describe, expect, it } from "vitest";
import { createCatalogModel, DEFAULT_CATALOG } from "./catalog-model.ts";
import { conceptId } from "./catalog-seed.ts";
import { createChargesModel, DEFAULT_CHARGES } from "./charges-model.ts";
import { PLAN_IDS } from "./charges-seed.ts";
import { createOrganizationModel, DEFAULT_ORGANIZATION } from "./organization-model.ts";
import { awardRowId, createScholarshipsModel, DEFAULT_SCHOLARSHIPS, scholarshipRowId, type ScholarshipsModel } from "./scholarships-model.ts";
import { createStudentsModel, DEFAULT_STUDENTS } from "./students-model.ts";

// The simulated scholarships and awards: the refusals the real server gives (a repeated code, the person who requested an award deciding it, an award
// that is not in the state of the decision, a reason out of bounds, a benefit that is locked), the Idempotency-Key kept per decision, the list the server
// serves, and the simulator of charges that applies the approved award in cents. Student 1 has a pending award another person requested; student 4 one
// the person requested; student 3 an approved one.
const NOW = () => new Date("2026-11-20T18:00:00Z");
const SCHOLARSHIPS = "/api/v1/scholarships";
const AWARDS = "/api/v1/scholarship-awards";
const STUDENT_1 = "00000000-0000-4000-8000-000000005001";
const STUDENT_4 = "00000000-0000-4000-8000-000000005004";
const YEAR = "00000000-0000-4000-8000-000000000001";
const REASON = "El estudiante no cumple el promedio mínimo";

function build(override: Partial<typeof DEFAULT_SCHOLARSHIPS> = {}): ScholarshipsModel {
  const students = createStudentsModel(DEFAULT_STUDENTS);
  const catalog = createCatalogModel(DEFAULT_CATALOG);
  return createScholarshipsModel({ ...DEFAULT_SCHOLARSHIPS, ...override }, { students: () => students.rows().students, concepts: () => catalog.rows(), now: NOW });
}

const send = (target: ScholarshipsModel, method: string, path: string, body?: unknown, key?: string, query = "") =>
  target.handle({ method, path, query: new URLSearchParams(query), body, headers: { "idempotency-key": key } });
const code = (response: { body?: unknown } | undefined): string => ((response?.body as { type?: string } | undefined)?.type ?? "").split("/").pop() ?? "";
const bodyOf = (response: { body?: unknown } | undefined) => response?.body as Record<string, unknown>;
const request = (student = STUDENT_1) => ({ studentId: student, scholarshipId: scholarshipRowId(1), academicYearId: YEAR, validFrom: "2026-01-01", validTo: "2026-12-31" });

describe("the scholarships", () => {
  it("lists the seeded ones by code, filtered and paged as the server does", () => {
    const target = build();

    const all = send(target, "GET", SCHOLARSHIPS);
    expect((bodyOf(all)["items"] as { code: string }[]).map((row) => row.code)).toEqual(["BECA25", "BECA50"]);
    expect(bodyOf(send(target, "GET", SCHOLARSHIPS, undefined, undefined, "active=false&size=1"))).toMatchObject({ items: [], total: 0, size: 1 });
    expect(code(send(target, "GET", SCHOLARSHIPS, undefined, undefined, "size=500"))).toBe("validation-failed");
  });

  it("creates one, refuses a code that is already used whatever its capitals, and refuses a concept that does not exist", () => {
    const target = build();
    const body = { code: "NUEVA", name: "Nueva beca", benefitType: "PERCENTAGE", percent: "10", conceptIds: [conceptId(1)] };

    expect(send(target, "POST", SCHOLARSHIPS, body)?.status).toBe(201);
    expect(code(send(target, "POST", SCHOLARSHIPS, { ...body, code: "nueva" }))).toBe("duplicate-scholarship-code");
    expect(code(send(target, "POST", SCHOLARSHIPS, { ...body, code: "OTRA", conceptIds: ["00000000-0000-4000-8000-0000000000ff"] }))).toBe("resource-not-found");
    expect(code(send(target, "POST", SCHOLARSHIPS, { ...body, code: "OTRA", percent: undefined }))).toBe("validation-failed");
  });

  it("refuses an edit with another version, and locks the benefit once an award was approved", () => {
    const target = build();
    const edit = { version: 0, name: "Beca", benefitType: "PERCENTAGE", percent: "30", conceptIds: [conceptId(1)] };

    expect(code(send(target, "PUT", `${SCHOLARSHIPS}/${scholarshipRowId(1)}`, { ...edit, version: 4 }))).toBe("stale-version");
    expect(send(target, "PUT", `${SCHOLARSHIPS}/${scholarshipRowId(1)}`, edit)?.status).toBe(200);
    // The second scholarship has an approved award in the seed.
    expect(bodyOf(send(target, "GET", `${SCHOLARSHIPS}/${scholarshipRowId(2)}`))["benefitLocked"]).toBe(true);
    expect(code(send(target, "PUT", `${SCHOLARSHIPS}/${scholarshipRowId(2)}`, { ...edit, percent: "60" }))).toBe("scholarship-benefit-locked");
    expect(send(target, "PUT", `${SCHOLARSHIPS}/${scholarshipRowId(2)}`, { ...edit, percent: "50.00", name: "Otro nombre" })?.status).toBe(200);
  });

  it("deactivates and reactivates, and moves the version only when the state changes", () => {
    const target = build();

    const off = send(target, "POST", `${SCHOLARSHIPS}/${scholarshipRowId(1)}/deactivate`);
    const again = send(target, "POST", `${SCHOLARSHIPS}/${scholarshipRowId(1)}/deactivate`);

    expect(bodyOf(off)).toMatchObject({ active: false, version: 1 });
    expect(bodyOf(again)).toMatchObject({ active: false, version: 1 });
    expect(bodyOf(send(target, "POST", `${SCHOLARSHIPS}/${scholarshipRowId(1)}/reactivate`))).toMatchObject({ active: true, version: 2 });
    expect(code(send(target, "POST", `${SCHOLARSHIPS}/${scholarshipRowId(9)}/deactivate`))).toBe("resource-not-found");
  });
});

describe("the awards", () => {
  it("lists newest first, filters by status and by student, and names the student and the scholarship", () => {
    const target = build();

    const all = bodyOf(send(target, "GET", AWARDS))["items"] as { studentCode: string; scholarshipCode: string; status: string }[];
    expect(all).toHaveLength(4);
    expect(all[0]).toMatchObject({ studentCode: "E-000007", status: "rejected" });
    expect((bodyOf(send(target, "GET", AWARDS, undefined, undefined, "status=pending"))["items"] as unknown[]).length).toBe(2);
    expect((bodyOf(send(target, "GET", AWARDS, undefined, undefined, `studentId=${STUDENT_1}`))["items"] as { scholarshipCode: string }[])[0]?.scholarshipCode).toBe("BECA25");
    expect(code(send(target, "GET", AWARDS, undefined, undefined, "status=other"))).toBe("validation-failed");
  });

  it("is born pending, requested by the person, and refuses an inactive scholarship, an unknown student and an end before the start", () => {
    const target = build();

    const made = send(target, "POST", AWARDS, request(STUDENT_4));
    expect(made?.status).toBe(201);
    expect(bodyOf(made)).toMatchObject({ status: "pending", studentCode: "E-000004" });
    expect(code(send(target, "POST", AWARDS, { ...request(), validFrom: "2026-06-01", validTo: "2026-05-01" }))).toBe("validation-failed");
    expect(code(send(target, "POST", AWARDS, request("00000000-0000-4000-8000-0000000000ff")))).toBe("resource-not-found");
    send(target, "POST", `${SCHOLARSHIPS}/${scholarshipRowId(1)}/deactivate`);
    expect(code(send(target, "POST", AWARDS, request()))).toBe("inactive-reference");
  });

  it("is approved by another person, with the key kept: the same key answers the same, another body is 422", () => {
    const target = build();

    expect(code(send(target, "POST", `${AWARDS}/${awardRowId(1)}/approve`))).toBe("idempotency-key-missing");
    const approved = send(target, "POST", `${AWARDS}/${awardRowId(1)}/approve`, undefined, "key-1");
    expect(bodyOf(approved)).toMatchObject({ status: "approved", decision: { kind: "approved" } });
    expect(bodyOf(send(target, "POST", `${AWARDS}/${awardRowId(1)}/approve`, undefined, "key-1"))["status"]).toBe("approved");
    expect(code(send(target, "POST", `${AWARDS}/${awardRowId(1)}/reject`, { reason: REASON }, "key-1"))).toBe("idempotency-payload-mismatch");
    expect(code(send(target, "POST", `${AWARDS}/${awardRowId(1)}/approve`, undefined, "key-2"))).toBe("invalid-award-state");
  });

  it("refuses the person who requested it, whatever the decision, and says so with 403", () => {
    const target = build();

    const refused = send(target, "POST", `${AWARDS}/${awardRowId(2)}/approve`, undefined, "key-1");
    expect(refused?.status).toBe(403);
    expect(code(refused)).toBe("self-approval-forbidden");
    expect(code(send(target, "POST", `${AWARDS}/${awardRowId(2)}/reject`, { reason: REASON }, "key-2"))).toBe("self-approval-forbidden");
  });

  it("rejects with a reason of 20 to 500 characters, and revokes only an approved award, also with a reason", () => {
    const target = build();

    expect(code(send(target, "POST", `${AWARDS}/${awardRowId(1)}/reject`, { reason: "Muy corto" }, "key-1"))).toBe("validation-failed");
    expect(bodyOf(send(target, "POST", `${AWARDS}/${awardRowId(1)}/reject`, { reason: REASON }, "key-2"))).toMatchObject({ status: "rejected", decision: { reason: REASON } });
    expect(code(send(target, "POST", `${AWARDS}/${awardRowId(1)}/revoke`, { reason: REASON }, "key-3"))).toBe("invalid-award-state");
    const revoked = send(target, "POST", `${AWARDS}/${awardRowId(3)}/revoke`, { reason: REASON }, "key-4");
    expect(bodyOf(revoked)).toMatchObject({ status: "revoked", revocation: { kind: "revoked", reason: REASON } });
  });

  it("answers 404 for an award that is not there, and the detail of one that is", () => {
    const target = build();

    expect(code(send(target, "GET", `${AWARDS}/${awardRowId(9)}`))).toBe("resource-not-found");
    expect(bodyOf(send(target, "GET", `${AWARDS}/${awardRowId(1)}`))).toMatchObject({ id: awardRowId(1), status: "pending" });
  });

  it("answers 500 to the first reads of a scenario that fails first, then recovers, and holds nothing when none is seeded", () => {
    const failing = build({ reads: "failing-first" });
    expect([1, 2, 3, 4].map(() => send(failing, "GET", AWARDS)?.status)).toEqual([500, 500, 500, 200]);
    expect(bodyOf(send(build({ seed: "none" }), "GET", SCHOLARSHIPS))).toMatchObject({ items: [], total: 0 });
  });
});

describe("the benefit the simulator applies", () => {
  it("is the percentage of an approved award in force on a covered concept, and none while the award is pending, revoked or out of its validity", () => {
    const target = build();
    const covered = conceptId(1);

    expect(target.benefitFor(STUDENT_1, covered, "2026-05-01")).toBeUndefined();
    send(target, "POST", `${AWARDS}/${awardRowId(1)}/approve`, undefined, "key-1");
    expect(target.benefitFor(STUDENT_1, covered, "2026-05-01")).toMatchObject({ code: "BECA25", hundredths: 2500 });
    expect(target.benefitFor(STUDENT_1, covered, "2027-01-01")).toBeUndefined();
    expect(target.benefitFor(STUDENT_1, conceptId(2), "2026-05-01")).toBeUndefined();
    send(target, "POST", `${AWARDS}/${awardRowId(1)}/revoke`, { reason: REASON }, "key-2");
    expect(target.benefitFor(STUDENT_1, covered, "2026-05-01")).toBeUndefined();
  });

  it("reaches the simulator of the accrual: base 1500.00, benefit 375.00, net base 1125.00 and the tax on the net base, in cents and half up", () => {
    const scholarships = build();
    const charges = createChargesModel(
      DEFAULT_CHARGES,
      { organization: createOrganizationModel(DEFAULT_ORGANIZATION), catalog: createCatalogModel(DEFAULT_CATALOG), students: createStudentsModel(DEFAULT_STUDENTS), benefitFor: scholarships.benefitFor },
      NOW,
    );
    const simulate = (student: string) =>
      charges.handle({ method: "GET", path: `/api/v1/charges/plans/${PLAN_IDS.active}/simulation`, query: new URLSearchParams(`studentId=${student}&period=2026-05-01`), body: undefined });

    const without = bodyOf(simulate(STUDENT_1))["lines"] as Record<string, unknown>[];
    expect(without[0]).toMatchObject({ base: { amount: "1500.00" }, benefitAmount: { amount: "0.00" }, netBase: { amount: "1500.00" }, scholarshipCode: null });
    send(scholarships, "POST", `${AWARDS}/${awardRowId(1)}/approve`, undefined, "key-1");
    const lines = bodyOf(simulate(STUDENT_1))["lines"] as Record<string, unknown>[];

    expect(lines[0]).toMatchObject({
      base: { amount: "1500.00" },
      benefitAmount: { amount: "375.00" },
      netBase: { amount: "1125.00" },
      tax: { amount: "168.75" },
      total: { amount: "1293.75" },
      scholarshipCode: "BECA25",
      outcome: "CREATED",
    });
    expect(simulate("00000000-0000-4000-8000-0000000000ff")?.status).toBe(404);
    expect(simulate("not-an-id")?.status).toBe(400);
  });
});

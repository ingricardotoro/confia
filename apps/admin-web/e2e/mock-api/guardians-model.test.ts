import { describe, expect, it } from "vitest";
import { createGuardiansModel, DEFAULT_GUARDIANS, FAILING_GUARDIAN_READS, type GuardiansModel } from "./guardians-model.ts";
import { guardianId, seededGuardianDocument } from "./guardians-seed.ts";
import { createStudentsModel, DEFAULT_STUDENTS } from "./students-model.ts";
import { studentId } from "./students-seed.ts";

// The simulated guardians over the seeded students: the shapes it answers, the exact split of the responsibility, and the few
// rules a journey needs. The clock is fixed so the date a link opens is known.
const NOW = () => new Date("2026-11-11T18:00:00Z");

function model(override: Partial<typeof DEFAULT_GUARDIANS> = {}): GuardiansModel {
  return createGuardiansModel({ ...DEFAULT_GUARDIANS, ...override }, { students: createStudentsModel(DEFAULT_STUDENTS) }, NOW);
}

const call = (target: GuardiansModel, method: string, path: string, body?: unknown, query = "") =>
  target.handle({ method, path: `/api/v1${path}`, query: new URLSearchParams(query), body });
const typeOf = (response: { body?: unknown } | undefined): string => ((response?.body as { type?: string } | undefined)?.type ?? "").split("/").pop() ?? "";
const errorsOf = (response: { body?: unknown } | undefined) => (response?.body as { errors: { field: string; reason: string }[] }).errors;

const STUDENT = studentId(2);
const link = (n: number, percent: number, extra: Record<string, unknown> = {}) => ({
  guardianId: guardianId(n),
  relationship: "OTHER",
  financialResponsibilityPercent: percent,
  isPrimaryPayer: false,
  isBillingRecipient: false,
  ...extra,
});
const replace = (target: GuardiansModel, links: unknown[], student = STUDENT) => call(target, "PUT", `/students/${student}/guardians`, { links });

describe("the list and the detail", () => {
  it("serves the first page ordered by last name, with the total, and no students in a row", () => {
    const answer = call(model(), "GET", "/guardians", undefined, "page=0&size=25");

    const body = answer?.body as { items: { lastName: string; students?: unknown; hasNationalId: boolean }[]; total: number };
    expect(answer?.status).toBe(200);
    expect(body.total).toBe(30);
    expect(body.items).toHaveLength(25);
    expect(body.items[0]?.lastName).toBe("Aguilar");
    expect(body.items.every((item) => item.students === undefined)).toBe(true);
  });

  it("orders by code and by status, searches by text and filters by status, and refuses what it does not know", () => {
    const target = model();
    const codes = (query: string) => (call(target, "GET", "/guardians", undefined, query)?.body as { items: { guardianCode: string }[] }).items.map((item) => item.guardianCode);

    expect(codes("sort=code,desc&size=2")).toEqual(["P-000030", "P-000029"]);
    expect(codes("q=P-000007")).toEqual(["P-000007"]);
    expect(codes("status=INACTIVE")).toEqual(["P-000010", "P-000020", "P-000030"]);
    expect(call(target, "GET", "/guardians", undefined, "sort=email,asc")?.status).toBe(400);
    expect(call(target, "GET", "/guardians", undefined, "q=a")?.status).toBe(400);
    expect(call(target, "GET", "/guardians", undefined, "status=GONE")?.status).toBe(400);
  });

  it("answers the detail with the students linked now, and never a document or an RTN", () => {
    const answer = call(model(), "GET", `/guardians/${guardianId(1)}`);

    const body = answer?.body as { students: { studentCode: string; financialResponsibilityPercent: number }[] };
    expect(body.students).toHaveLength(1);
    expect(body.students[0]).toMatchObject({ studentCode: "E-000001", financialResponsibilityPercent: 60 });
    expect(JSON.stringify(answer?.body)).not.toContain(seededGuardianDocument(3));
    expect(typeOf(call(model(), "GET", `/guardians/${studentId(1)}`))).toBe("resource-not-found");
  });

  it("answers empty for an empty scenario and fails the first reads of a failing one", () => {
    expect((call(model({ seed: "empty" }), "GET", "/guardians")?.body as { total: number }).total).toBe(0);
    const failing = model({ reads: "failing-first" });
    const statuses = Array.from({ length: FAILING_GUARDIAN_READS + 1 }, () => call(failing, "GET", "/guardians")?.status);
    expect(statuses).toEqual([500, 500, 500, 200]);
  });
});

describe("the writes of a guardian", () => {
  const NEW = { firstName: "Eva", lastName: "Rivas" };

  it("creates with a generated code, hiding the document, and refuses the same document with other hyphens", () => {
    const target = model();

    const created = call(target, "POST", "/guardians", { ...NEW, nationalId: "0801-1980-55555" });
    const again = call(target, "POST", "/guardians", { firstName: "Ana", lastName: "Paz", nationalId: "080119805-5555" });

    expect(created?.status).toBe(201);
    expect(created?.body).toMatchObject({ guardianCode: "P-000031", hasNationalId: true, hasRtn: false, preferredChannel: "NONE" });
    expect(JSON.stringify(created?.body)).not.toContain("55555");
    expect(again?.status).toBe(409);
    expect(typeOf(again)).toBe("duplicate-national-id");
  });

  it("refuses a malformed body field by field", () => {
    const answer = call(model(), "POST", "/guardians", { firstName: " ", lastName: "x", rtn: "12", email: "no", phone: "1", extra: 1 });

    expect(answer?.status).toBe(400);
    expect(errorsOf(answer)).toEqual([{ field: "extra", reason: "unknown" }]);
    expect(errorsOf(call(model(), "POST", "/guardians", { firstName: " ", lastName: "x", rtn: "12", email: "no", phone: "1" })).map((error) => error.field)).toEqual([
      "firstName",
      "rtn",
      "email",
      "phone",
    ]);
  });

  it("keeps the document and the RTN when an edit omits them, removes them with an empty text, and clears the contact data it omits", () => {
    const target = model();
    const id = guardianId(5);
    const base = { firstName: "Elena", lastName: "Cruz", status: "ACTIVE" };

    const kept = call(target, "PUT", `/guardians/${id}`, base);
    expect(kept?.body).toMatchObject({ hasRtn: true, hasNationalId: false, email: null });
    const removed = call(target, "PUT", `/guardians/${id}`, { ...base, rtn: "" });
    expect(removed?.body).toMatchObject({ hasRtn: false });
    const added = call(target, "PUT", `/guardians/${id}`, { ...base, nationalId: "1503-1999-98765" });
    expect(added?.body).toMatchObject({ hasNationalId: true });
  });

  it("refuses every write with 403 and changes nothing when the scenario forbids them", () => {
    const target = model({ writes: "forbidden" });

    expect(call(target, "POST", "/guardians", NEW)?.status).toBe(403);
    expect(call(target, "PUT", `/guardians/${guardianId(1)}`, { ...NEW, status: "ACTIVE" })?.status).toBe(403);
    expect(replace(target, [])?.status).toBe(403);
    expect(target.rows().guardians).toHaveLength(30);
  });
});

describe("the split of the responsibility", () => {
  it("accepts the set that sums exactly 100.00, including 33.33 + 33.33 + 33.34", () => {
    const target = model();

    const answer = replace(target, [link(1, 33.33), link(3, 33.33), link(4, 33.34)]);

    expect(answer?.status).toBe(200);
    expect((answer?.body as { totalPercent: number; links: unknown[] }).links).toHaveLength(3);
    expect((answer?.body as { totalPercent: number }).totalPercent).toBe(100);
  });

  it.each([
    ["99.99", [link(1, 60), link(3, 39.99)]],
    ["100.01", [link(1, 60), link(3, 40.01)]],
    ["60/30", [link(1, 60), link(3, 30)]],
  ])("refuses %s with the reason sum-not-100", (_name, links) => {
    const answer = replace(model(), links);

    expect(answer?.status).toBe(400);
    expect(typeOf(answer)).toBe("invalid-responsibility-split");
    expect(errorsOf(answer)).toEqual([{ field: "links", reason: "sum-not-100" }]);
  });

  it("refuses two primary payers, two billing recipients and a repeated guardian, each with its reason", () => {
    const target = model();

    expect(errorsOf(replace(target, [link(1, 50, { isPrimaryPayer: true }), link(3, 50, { isPrimaryPayer: true })]))[0]?.reason).toBe("multiple-primary-payer");
    expect(errorsOf(replace(target, [link(1, 50, { isBillingRecipient: true }), link(3, 50, { isBillingRecipient: true })]))[0]?.reason).toBe("multiple-billing-recipient");
    expect(errorsOf(replace(target, [link(1, 50), link(1, 50)]))[0]?.reason).toBe("duplicate-guardian");
  });

  it("allows a set with no payer and no recipient, and an empty set (removes everyone)", () => {
    const target = model();

    expect(replace(target, [link(1, 100)])?.status).toBe(200);
    const emptied = replace(target, []);
    expect(emptied?.status).toBe(200);
    expect(emptied?.body).toMatchObject({ links: [], totalPercent: 0 });
  });

  it("refuses a percentage of 0, of 100.01 and with three decimals as a field error of its row", () => {
    const target = model();

    expect(errorsOf(replace(target, [link(1, 0)]))).toEqual([{ field: "links[0].financialResponsibilityPercent", reason: "decimal-min" }]);
    expect(errorsOf(replace(target, [link(1, 50), link(3, 100.01)]))).toEqual([{ field: "links[1].financialResponsibilityPercent", reason: "decimal-max" }]);
    expect(errorsOf(replace(target, [link(1, 33.333)]))).toEqual([{ field: "links[0].financialResponsibilityPercent", reason: "digits" }]);
  });

  it("refuses an inactive guardian, an inactive student and a guardian or student that does not exist", () => {
    const target = model();

    expect(typeOf(replace(target, [link(10, 100)]))).toBe("guardian-not-active");
    expect(typeOf(replace(target, [link(1, 100)], studentId(10)))).toBe("student-not-active");
    expect(typeOf(replace(target, [link(99, 100)]))).toBe("resource-not-found");
    expect(typeOf(replace(target, [link(1, 100)], "00000000-0000-4000-8000-00000000ffff"))).toBe("resource-not-found");
  });

  it("changes nothing when a replacement is refused", () => {
    const target = model();
    const before = JSON.stringify(target.rows().links);

    replace(target, [link(1, 60), link(3, 30)], studentId(1));
    replace(target, [link(10, 100)], studentId(1));

    expect(JSON.stringify(target.rows().links)).toBe(before);
  });
});

describe("the history of the links", () => {
  it("closes a link whose percentage changes and opens a new one; an identical link stays as it is", () => {
    const target = model();
    const first = target.rows().links.find((row) => row.guardianId === guardianId(1));
    const second = target.rows().links.find((row) => row.guardianId === guardianId(2));

    const answer = replace(target, [{ ...link(1, 50, { isPrimaryPayer: true, isBillingRecipient: true }), relationship: "MOTHER" }, { ...link(2, 50), relationship: "FATHER" }], studentId(1));

    expect(answer?.status).toBe(200);
    const history = target.rows().history;
    expect(history.map((row) => row.linkId).sort()).toEqual([first?.linkId, second?.linkId].sort());
    const now = target.rows().links.filter((row) => row.studentId === studentId(1));
    expect(now.map((row) => row.financialResponsibilityPercent)).toEqual([50, 50]);
    expect(now.every((row) => row.validFrom === "2026-11-11")).toBe(true);
  });

  it("leaves an identical replacement without changes", () => {
    const target = model();
    const before = JSON.stringify(target.rows().links);

    replace(
      target,
      [
        { ...link(1, 60, { isPrimaryPayer: true, isBillingRecipient: true }), relationship: "MOTHER" },
        { ...link(2, 40), relationship: "FATHER" },
      ],
      studentId(1),
    );

    expect(JSON.stringify(target.rows().links)).toBe(before);
    expect(target.rows().history).toHaveLength(0);
  });
});

describe("the set of a student", () => {
  it("serves the current links with the sum, and 404 for a student that does not exist", () => {
    const target = model();

    const answer = call(target, "GET", `/students/${studentId(1)}/guardians`);

    expect(answer?.body).toMatchObject({ studentCode: "E-000001", totalPercent: 100 });
    expect((answer?.body as { links: { guardianFirstName: string }[] }).links).toHaveLength(2);
    expect(call(target, "GET", `/students/${studentId(2)}/guardians`)?.body).toMatchObject({ links: [], totalPercent: 0 });
    expect(call(target, "GET", "/students/00000000-0000-4000-8000-00000000ffff/guardians")?.status).toBe(404);
  });

  it("does not answer a path that is not one of its six", () => {
    expect(call(model(), "DELETE", `/guardians/${guardianId(1)}`)).toBeUndefined();
    expect(call(model(), "GET", "/students")).toBeUndefined();
  });
});

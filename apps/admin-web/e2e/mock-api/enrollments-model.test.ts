import { describe, expect, it } from "vitest";
import { createEnrollmentsModel, DEFAULT_ENROLLMENTS, FAILING_ENROLLMENT_READS, type EnrollmentsModel } from "./enrollments-model.ts";
import { enrollmentId } from "./enrollments-seed.ts";
import { createOrganizationModel, DEFAULT_ORGANIZATION } from "./organization-model.ts";
import { SECTION_IDS } from "./organization-seed.ts";
import { createStudentsModel, DEFAULT_STUDENTS } from "./students-model.ts";
import { studentId } from "./students-seed.ts";

// The simulated enrollments over the seeded students and structure: the shapes it answers, and the few rules a journey needs.
// The clock is fixed inside the year in progress (15 January to 30 November 2026).
const NOW = () => new Date("2026-11-11T18:00:00Z");

function model(override: Partial<typeof DEFAULT_ENROLLMENTS> = {}): EnrollmentsModel {
  return createEnrollmentsModel(
    { ...DEFAULT_ENROLLMENTS, ...override },
    { organization: createOrganizationModel(DEFAULT_ORGANIZATION), students: createStudentsModel(DEFAULT_STUDENTS) },
    NOW,
  );
}

const call = (target: EnrollmentsModel, method: string, path: string, body?: unknown, query = "") =>
  target.handle({ method, path: `/api/v1/enrollments${path}`, query: new URLSearchParams(query), body });
const typeOf = (response: { body?: unknown } | undefined): string => ((response?.body as { type?: string } | undefined)?.type ?? "").split("/").pop() ?? "";
const fieldsOf = (response: { body?: unknown } | undefined) => (response?.body as { errors: { field: string; reason: string }[] }).errors;

describe("the list", () => {
  it("serves the year in progress by default, ordered by last name, with the total of every page", () => {
    const answer = call(model(), "GET", "", undefined, "page=0&size=25");

    const body = answer?.body as { items: { studentLastName: string; transfers?: unknown }[]; total: number; page: number };
    expect(answer?.status).toBe(200);
    expect(body.total).toBe(37);
    expect(body.items).toHaveLength(25);
    expect(body.items[0]?.studentLastName).toBe("Aguilar");
    expect(body.items.every((item) => item.transfers === undefined)).toBe(true);
  });

  it("filters by section, grade and state, and refuses a sort or a state it does not know", () => {
    const target = model();
    const total = (query: string) => (call(target, "GET", "", undefined, query)?.body as { total: number }).total;

    expect(total(`sectionId=${SECTION_IDS.secondA}`)).toBe(25);
    expect(total("status=WITHDRAWN")).toBe(6);
    expect(total("status=GRADUATED")).toBe(1);
    expect(call(target, "GET", "", undefined, "sort=code,asc")?.status).toBe(400);
    expect(call(target, "GET", "", undefined, "status=GONE")?.status).toBe(400);
  });

  it("answers empty for an empty scenario and fails the first reads of a failing one", () => {
    expect((call(model({ seed: "empty" }), "GET", "")?.body as { total: number }).total).toBe(0);
    const failing = model({ reads: "failing-first" });
    const statuses = Array.from({ length: FAILING_ENROLLMENT_READS + 1 }, () => call(failing, "GET", "")?.status);
    expect(statuses).toEqual([500, 500, 500, 200]);
  });
});

describe("the writes", () => {
  const FREE = studentId(59);
  const ENROLL = { studentId: FREE, sectionId: SECTION_IDS.firstB, enrolledOn: "2026-03-02" };

  it("enrolls, answers the new row with an empty history, and refuses a second active enrollment in the year", () => {
    const target = model();

    const created = call(target, "POST", "", ENROLL);
    expect(created?.status).toBe(201);
    expect((created?.body as { status: string; transfers: unknown[] }).transfers).toEqual([]);
    expect(typeOf(call(target, "POST", "", { ...ENROLL, sectionId: SECTION_IDS.firstA }))).toBe("duplicate-active-enrollment");
  });

  it("lets a student who left enroll again in the same year", () => {
    expect(call(model(), "POST", "", { ...ENROLL, studentId: studentId(31) })?.status).toBe(201);
  });

  it("refuses a full section, an inactive student, a date out of the year and a body with a year", () => {
    const target = model();

    expect(typeOf(call(target, "POST", "", { ...ENROLL, sectionId: SECTION_IDS.secondA }))).toBe("section-full");
    expect(typeOf(call(target, "POST", "", { ...ENROLL, studentId: studentId(60) }))).toBe("student-not-active");
    expect(fieldsOf(call(target, "POST", "", { ...ENROLL, enrolledOn: "2026-01-14" }))).toEqual([{ field: "enrolledOn", reason: "enrollment-date-out-of-range" }]);
    expect(call(target, "POST", "", { ...ENROLL, enrolledOn: "2026-01-15" })?.status).toBe(201);
    expect(call(target, "POST", "", { ...ENROLL, academicYearId: "x" })?.status).toBe(400);
    expect(typeOf(call(target, "POST", "", { ...ENROLL, studentId: crypto.randomUUID() }))).toBe("resource-not-found");
  });

  it("transfers inside the year, keeps the history, and refuses the same section, a section of another year and a full one", () => {
    const target = model();
    const id = enrollmentId(29);

    expect(fieldsOf(call(target, "POST", `/${id}/transfer`, { toSectionId: SECTION_IDS.firstA, transferredOn: "2026-05-04" }))).toEqual([
      { field: "toSectionId", reason: "same-section" },
    ]);
    expect(typeOf(call(target, "POST", `/${id}/transfer`, { toSectionId: SECTION_IDS.firstAPast, transferredOn: "2026-05-04" }))).toBe("transfer-year-mismatch");
    expect(typeOf(call(target, "POST", `/${id}/transfer`, { toSectionId: SECTION_IDS.secondA, transferredOn: "2026-05-04" }))).toBe("section-full");
    const moved = call(target, "POST", `/${id}/transfer`, { toSectionId: SECTION_IDS.firstB, transferredOn: "2026-05-04", reason: " Cambio de turno " });
    const body = moved?.body as { sectionName: string; transfers: { fromSectionName: string; toSectionName: string; reason: string }[] };
    expect(body.sectionName).toBe("B");
    expect(body.transfers.at(-1)).toMatchObject({ fromSectionName: "A", toSectionName: "B", reason: "Cambio de turno" });
  });

  it("registers an exit with the reason required except for a graduation, and refuses a second one", () => {
    const target = model();
    const id = enrollmentId(29);

    expect(fieldsOf(call(target, "POST", `/${id}/exit`, { status: "WITHDRAWN", withdrawnOn: "2026-05-04" }))).toEqual([{ field: "withdrawalReason", reason: "not-blank" }]);
    expect(fieldsOf(call(target, "POST", `/${id}/exit`, { status: "WITHDRAWN", withdrawnOn: "2026-05-04", withdrawalReason: "ab" }))).toEqual([{ field: "withdrawalReason", reason: "size" }]);
    expect(fieldsOf(call(target, "POST", `/${id}/exit`, { status: "GRADUATED", withdrawnOn: "2026-01-01" }))).toEqual([{ field: "withdrawnOn", reason: "enrollment-date-out-of-range" }]);
    const done = call(target, "POST", `/${id}/exit`, { status: "GRADUATED", withdrawnOn: "2026-11-01" });
    expect((done?.body as { status: string; withdrawalReason: unknown }).status).toBe("GRADUATED");
    expect(typeOf(call(target, "POST", `/${id}/exit`, { status: "GRADUATED", withdrawnOn: "2026-11-01" }))).toBe("invalid-enrollment-state");
    expect(typeOf(call(target, "POST", `/${id}/transfer`, { toSectionId: SECTION_IDS.firstB, transferredOn: "2026-11-01" }))).toBe("invalid-enrollment-state");
  });

  it("refuses every write with 403 when the scenario forbids them, and changes nothing", () => {
    const target = model({ writes: "forbidden" });

    expect(call(target, "POST", "", ENROLL)?.status).toBe(403);
    expect(call(target, "POST", `/${enrollmentId(29)}/exit`, { status: "GRADUATED", withdrawnOn: "2026-11-01" })?.status).toBe(403);
    expect(target.rows()).toHaveLength(37);
    expect(call(target, "GET", "")?.status).toBe(200);
  });

  it("answers the detail with the history, and 404 for an unknown id and for a path it does not simulate", () => {
    const target = model();

    const detail = call(target, "GET", `/${enrollmentId(28)}`)?.body as { transfers: { fromSectionName: string }[] };
    expect(detail.transfers).toHaveLength(1);
    expect(detail.transfers[0]?.fromSectionName).toBe("B");
    expect(typeOf(call(target, "GET", `/${crypto.randomUUID()}`))).toBe("resource-not-found");
    expect(call(target, "DELETE", `/${enrollmentId(28)}`)).toBeUndefined();
  });
});

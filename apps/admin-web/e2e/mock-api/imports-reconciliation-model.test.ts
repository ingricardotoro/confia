import { describe, expect, it } from "vitest";
import { DEFAULT_IMPORTS, SIGNER_ID, createImportsModel, type ImportsOptions } from "./imports-model.ts";
import { yearId } from "./organization-seed.ts";

// The acta of the reconciliation of the simulated API: the counts come from the batches applied and the enrollments of this
// scenario as the real server counts them (created summed over every applied batch, rejected from the newest one), the signing keeps
// every acta, and the 400, 403 and 404 the screen meets exist.
const NOW = () => new Date("2026-11-10T20:00:00Z");
const BOUNDARY = "----test-boundary";
const ENROLLMENT_HEADER = "studentLegacyId,academicYear,modality,grade,section,enrolledOn";
const YEAR_2026 = yearId(2026);
const YEAR_2025 = yearId(2025);
const UNKNOWN_YEAR = "00000000-0000-4000-8000-0000000fffff";
const RECONCILIATIONS = "/api/v1/imports/reconciliations";

function upload(csv: string) {
  const body = Buffer.from(`--${BOUNDARY}\r\nContent-Disposition: form-data; name="file"; filename="e.csv"\r\nContent-Type: text/csv\r\n\r\n${csv}\r\n--${BOUNDARY}--\r\n`);
  return { contentType: `multipart/form-data; boundary=${BOUNDARY}`, body };
}

function model(override: Partial<ImportsOptions> = {}) {
  const imports = createImportsModel({ ...DEFAULT_IMPORTS, seed: "empty", ...override }, NOW);
  const load = (route: string, csv: string) => {
    for (const action of ["simulate", "apply"]) {
      imports.handle({ method: "POST", path: `/api/v1/imports/${route}/${action}`, query: new URLSearchParams(), ...upload(csv) });
    }
  };
  const students = (...ids: string[]) => {
    load("students", ["legacyId,firstName,lastName,birthDate", ...ids.map((id) => `${id},Ana,Pérez,2013-05-01`)].join("\n"));
  };
  const enroll = (...rows: string[]) => {
    load("enrollments", [ENROLLMENT_HEADER, ...rows].join("\n"));
  };
  const preview = (year: string) =>
    imports.handle({ method: "GET", path: `${RECONCILIATIONS}/preview`, query: new URLSearchParams({ academicYearId: year }), contentType: undefined, body: undefined });
  const sign = (body: unknown) =>
    imports.handle({ method: "POST", path: RECONCILIATIONS, query: new URLSearchParams(), contentType: "application/json", body: Buffer.from(JSON.stringify(body)) });
  const list = (query = "") =>
    imports.handle({ method: "GET", path: RECONCILIATIONS, query: new URLSearchParams(query), contentType: undefined, body: undefined });
  return { imports, students, enroll, preview, sign, list };
}

const valid = { academicYearId: YEAR_2026, officialReference: "Oficio 1", officialEnrollmentCount: 120 };
const errorsOf = (response: ReturnType<ReturnType<typeof model>["sign"]>) => (response?.body as { errors?: { field: string; reason: string }[] }).errors;

describe("the preview", () => {
  it("is all zeros for a year with no batch and no enrollment", () => {
    const { preview } = model();

    const answer = preview(YEAR_2026);

    expect(answer?.status).toBe(200);
    expect(answer?.body).toEqual({
      academicYearId: YEAR_2026,
      enrollmentsActive: 0,
      students: { created: 0, rejected: 0 },
      guardians: { created: 0, rejected: 0 },
      guardianLinks: { created: 0, rejected: 0 },
      enrollments: { created: 0, rejected: 0 },
    });
  });

  it("sums the created rows of every applied batch, takes the rejected rows of the newest one, and counts the enrollments of the year", () => {
    const { students, enroll, preview } = model();
    students("S1", "S2", "S3");
    students("S1", "S2", "S3", "S4", "BAD");
    // Rejected: the student S9 does not exist. Created: S1 and S2.
    enroll("S1,2026,Diurna,Primero,A,2026-02-02", "S2,2026,Diurna,Primero,A,2026-02-02", "S9,2026,Diurna,Primero,A,2026-02-02");

    const body = preview(YEAR_2026)?.body as Record<string, { created: number; rejected: number } | number | string>;

    // Three students the first time and two more the second (the three already imported were skipped).
    expect(body.students).toEqual({ created: 5, rejected: 0 });
    expect(body.enrollments).toEqual({ created: 2, rejected: 1 });
    expect(body.enrollmentsActive).toBe(2);
  });

  it("does not count a simulated batch, and counts the enrollments of another year apart", () => {
    const { imports, students, enroll, preview } = model();
    students("S1");
    imports.handle({
      method: "POST",
      path: "/api/v1/imports/students/simulate",
      query: new URLSearchParams(),
      ...upload("legacyId,firstName,lastName,birthDate\nS7,Ana,Pérez,2013-05-01"),
    });
    enroll("S1,2026,Diurna,Primero,A,2026-02-02");

    expect((preview(YEAR_2026)?.body as { students: unknown; enrollmentsActive: number }).students).toEqual({ created: 1, rejected: 0 });
    expect((preview(YEAR_2026)?.body as { enrollmentsActive: number }).enrollmentsActive).toBe(1);
    expect((preview(YEAR_2025)?.body as { enrollmentsActive: number }).enrollmentsActive).toBe(0);
  });

  it("answers 404 for a year of another institution and 400 for something that is not an identifier", () => {
    const { preview } = model();

    expect(preview(UNKNOWN_YEAR)?.status).toBe(404);
    expect(preview("2026")?.status).toBe(400);
  });
});

describe("signing", () => {
  it("answers 201 with the record, the counts of the moment and the difference of the server, and lists it first", () => {
    const { students, enroll, sign, list } = model();
    students("S1", "S2");
    enroll("S1,2026,Diurna,Primero,A,2026-02-02", "S2,2026,Diurna,Primero,A,2026-02-02");

    const answer = sign({ ...valid, officialReference: "  Oficio 7  ", officialEnrollmentCount: 5 });

    expect(answer?.status).toBe(201);
    expect(answer?.body).toMatchObject({
      academicYearId: YEAR_2026,
      officialReference: "Oficio 7",
      officialEnrollmentCount: 5,
      enrollmentsActive: 2,
      difference: -3,
      students: { created: 2, rejected: 0 },
      enrollments: { created: 2, rejected: 0 },
      signedBy: SIGNER_ID,
      signedAt: "2026-11-10T20:00:00.000Z",
    });
    expect((list()?.body as { items: { officialReference: string }[]; total: number }).items.map((item) => item.officialReference)).toEqual(["Oficio 7"]);
  });

  it("signs a year with nothing in it, and a total of zero", () => {
    const { sign } = model();

    expect(sign({ ...valid, officialEnrollmentCount: 0 })?.body).toMatchObject({ enrollmentsActive: 0, difference: 0 });
  });

  it("keeps every acta of the same year, the newest first, and pages them", () => {
    const { sign, list, imports } = model();
    sign({ ...valid, officialReference: "Oficio 1" });
    sign({ ...valid, officialReference: "Oficio 2" });
    sign({ ...valid, officialReference: "Oficio 3" });

    const first = list("page=0&size=2")?.body as { items: { officialReference: string }[]; total: number; page: number; size: number };
    const second = list("page=1&size=2")?.body as { items: { officialReference: string }[] };

    expect(first.items.map((item) => item.officialReference)).toEqual(["Oficio 3", "Oficio 2"]);
    expect(second.items.map((item) => item.officialReference)).toEqual(["Oficio 1"]);
    expect([first.total, first.page, first.size]).toEqual([3, 0, 2]);
    expect(imports.actas()).toHaveLength(3);
  });

  it.each([
    [{ ...valid, officialReference: "" }, [{ field: "officialReference", reason: "not-blank" }]],
    [{ ...valid, officialReference: "   " }, [{ field: "officialReference", reason: "not-blank" }]],
    [{ ...valid, officialReference: "1".repeat(101) }, [{ field: "officialReference", reason: "size" }]],
    [{ ...valid, officialReference: "Oficio\u0007" }, [{ field: "officialReference", reason: "pattern" }]],
    [{ ...valid, officialEnrollmentCount: -1 }, [{ field: "officialEnrollmentCount", reason: "min" }]],
    [{ ...valid, officialEnrollmentCount: 1_000_001 }, [{ field: "officialEnrollmentCount", reason: "max" }]],
    [{ ...valid, officialEnrollmentCount: 1.5 }, [{ field: "officialEnrollmentCount", reason: "not-null" }]],
    [{ ...valid, academicYearId: "2026" }, [{ field: "academicYearId", reason: "invalid-format" }]],
  ])("refuses %j with a 400 that names the field, and records nothing", (body, errors) => {
    const { sign, imports } = model();

    const answer = sign(body);

    expect(answer?.status).toBe(400);
    expect(errorsOf(answer)).toEqual(errors);
    expect(imports.actas()).toHaveLength(0);
  });

  it("accepts a reference of 1 and of 100 characters and a total of 1 000 000", () => {
    const { sign } = model();

    expect(sign({ ...valid, officialReference: "7" })?.status).toBe(201);
    expect(sign({ ...valid, officialReference: "1".repeat(100) })?.status).toBe(201);
    expect(sign({ ...valid, officialEnrollmentCount: 1_000_000 })?.status).toBe(201);
  });

  it("answers 404 for a year of another institution and records nothing", () => {
    const { sign, imports } = model();

    expect(sign({ ...valid, academicYearId: UNKNOWN_YEAR })?.status).toBe(404);
    expect(imports.actas()).toHaveLength(0);
  });

  it("refuses the write with 403 and records nothing, but still reads", () => {
    const { sign, list, preview, imports } = model({ writes: "forbidden" });

    expect(sign(valid)?.status).toBe(403);
    expect(imports.actas()).toHaveLength(0);
    expect(list()?.status).toBe(200);
    expect(preview(YEAR_2026)?.status).toBe(200);
  });

  it("refuses a body that is not JSON", () => {
    const { imports } = model();

    const answer = imports.handle({ method: "POST", path: RECONCILIATIONS, query: new URLSearchParams(), contentType: "application/json", body: Buffer.from("{nope") });

    expect(answer?.status).toBe(400);
  });
});

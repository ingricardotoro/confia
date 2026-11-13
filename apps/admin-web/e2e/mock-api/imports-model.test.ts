import { describe, expect, it } from "vitest";
import { FAILING_IMPORT_READS, DEFAULT_IMPORTS, STORED_NATIONAL_ID, createImportsModel, fileOfMultipart, readCsv, type ImportsOptions } from "./imports-model.ts";

// The student import of the simulated API: the same rules the screen meets in a journey (the template, the required fields, the
// birth date, the repeated and the already imported `legacyId`, the two 409 told apart by the last batch), the file problems of a
// 400, a refused write and a read that fails first, and that no value of a file stays in a batch.
const NOW = () => new Date("2026-11-10T20:00:00Z");
const HEADER = "legacyId,firstName,lastName,birthDate";
const BOUNDARY = "----test-boundary";

function upload(csv: string) {
  const body = Buffer.from(`--${BOUNDARY}\r\nContent-Disposition: form-data; name="file"; filename="e.csv"\r\nContent-Type: text/csv\r\n\r\n${csv}\r\n--${BOUNDARY}--\r\n`);
  return { contentType: `multipart/form-data; boundary=${BOUNDARY}`, body };
}

function model(override: Partial<ImportsOptions> = {}) {
  const imports = createImportsModel({ ...DEFAULT_IMPORTS, seed: "empty", ...override }, NOW);
  const post = (path: "simulate" | "apply", csv: string) =>
    imports.handle({ method: "POST", path: `/api/v1/imports/students/${path}`, query: new URLSearchParams(), ...upload(csv) });
  const get = (query = "") => imports.handle({ method: "GET", path: "/api/v1/imports", query: new URLSearchParams(query), contentType: undefined, body: undefined });
  return { imports, post, get };
}

const csvOf = (...rows: string[]) => [HEADER, ...rows].join("\n");
const bodyOf = (response: ReturnType<ReturnType<typeof model>["post"]>) => response?.body as { batch: Record<string, unknown>; rejections: { line: number; field: string; reason: string }[] };

describe("reading a file", () => {
  it("takes the part `file` of a multipart body and nothing from other parts", () => {
    const { contentType, body } = upload("a,b");
    expect(fileOfMultipart(contentType, body)).toBe("a,b");
    expect(fileOfMultipart("application/json", body)).toBeUndefined();
    expect(fileOfMultipart(contentType, undefined)).toBeUndefined();
  });

  it("reads `;`, a BOM, quotes with a doubled quote and a line break inside a cell, and counts the lines of what follows", () => {
    const records = readCsv(`${String.fromCharCode(0xfeff)}a;b\r\n"x;1";"y ""q"""\r\n"two\nlines";z\r\n\r\nlast;row`);

    expect(records).toEqual([
      { line: 1, cells: ["a", "b"] },
      { line: 2, cells: ["x;1", 'y "q"'] },
      { line: 3, cells: ["two\nlines", "z"] },
      { line: 6, cells: ["last", "row"] },
    ]);
  });
});

describe("simulate", () => {
  it("counts the rows, lists the defects by line and field with their reason, and keeps a batch with no value of the file", () => {
    const { imports, post } = model();

    const answer = post("simulate", csvOf("1,Ana,Pérez,2013-05-01", "2,,López,2013-02-30", "3,Luis,Mora,2999-01-01", "4,Eva,Ruiz,13/05/2013", "1,Zoe,Díaz,2013-05-01"));

    expect(answer?.status).toBe(200);
    expect(bodyOf(answer).rejections).toEqual([
      { line: 3, field: "firstName", reason: "required" },
      { line: 3, field: "birthDate", reason: "invalid-date" },
      { line: 4, field: "birthDate", reason: "future-birth-date" },
      { line: 5, field: "birthDate", reason: "invalid-format" },
      { line: 6, field: "legacyId", reason: "duplicate-in-file" },
    ]);
    expect(bodyOf(answer).batch).toMatchObject({ status: "VALIDATED", totalRows: 5, validRows: 1, rejectedRows: 4, skippedRows: 0, createdRows: 0, appliedAt: null });
    expect(JSON.stringify(imports.batches())).not.toMatch(/Ana|Pérez|López|2013/);
  });

  it("answers the problems of the file as a 400 with their field and reason", () => {
    const { post } = model();

    expect(post("simulate", "")?.body).toMatchObject({ errors: [{ field: "file", reason: "empty-file" }] });
    expect(post("simulate", HEADER)?.body).toMatchObject({ errors: [{ field: "file", reason: "no-rows" }] });
    expect(post("simulate", "legacyId,firstName,hobby\n1,Ana,x")?.body).toMatchObject({
      errors: [
        { field: "columns[2]", reason: "unknown-column" },
        { field: "lastName", reason: "missing-column" },
        { field: "birthDate", reason: "missing-column" },
      ],
    });
  });

  it("refuses a body that is not multipart as 415 and a multipart without the part `file` as a 400", () => {
    const { imports } = model();
    const request = { method: "POST", path: "/api/v1/imports/students/simulate", query: new URLSearchParams() };

    expect(imports.handle({ ...request, contentType: "application/json", body: Buffer.from("{}") })?.status).toBe(415);
    expect(imports.handle({ ...request, contentType: `multipart/form-data; boundary=${BOUNDARY}`, body: Buffer.from(`--${BOUNDARY}--\r\n`) })?.status).toBe(400);
  });
});

describe("apply", () => {
  const file = csvOf("1,Ana,Pérez,2013-05-01", "2,Luis,Mora,2013-06-02", "3,,Ruiz,2013-07-03");

  it("is refused with import-not-simulated when the account never simulated, and with import-file-changed when the last batch is a simulation of another file", () => {
    const { post } = model();
    expect(post("apply", file)?.body).toMatchObject({ type: "https://confia.hn/problems/import-not-simulated", status: 409 });

    post("simulate", file);
    expect(post("apply", `${file}\n9,Zoe,Díaz,2013-08-04`)?.body).toMatchObject({ type: "https://confia.hn/problems/import-file-changed" });
  });

  it("creates the valid rows of the simulated file, counts them in the batch, and leaves the simulation valid for a second apply that creates nothing", () => {
    const { imports, post } = model();
    post("simulate", file);

    const first = post("apply", file);
    const again = post("apply", file);

    expect(bodyOf(first).batch).toMatchObject({ status: "APPLIED", totalRows: 3, validRows: 2, createdRows: 2, rejectedRows: 1, skippedRows: 0 });
    expect(bodyOf(first).batch.appliedAt).not.toBeNull();
    expect(bodyOf(again).batch).toMatchObject({ createdRows: 0, validRows: 0, skippedRows: 2, rejectedRows: 1 });
    expect(bodyOf(again).rejections.filter((entry) => entry.reason === "already-imported")).toEqual([
      { line: 2, field: "legacyId", reason: "already-imported" },
      { line: 3, field: "legacyId", reason: "already-imported" },
    ]);
    expect(imports.batches().map((batch) => batch.status)).toEqual(["VALIDATED", "APPLIED", "APPLIED"]);
  });

  it("does not look at the documents already stored when simulating, and rejects them when applying", () => {
    const { post } = model();
    const withDocument = "legacyId,firstName,lastName,birthDate,nationalId" + "\n" + "A1,N,A,2013-05-01," + "\n" + "A2,N,A,2013-05-02," + STORED_NATIONAL_ID;

    const simulated = bodyOf(post("simulate", withDocument));
    const applied = bodyOf(post("apply", withDocument));

    expect(simulated.batch).toMatchObject({ totalRows: 2, validRows: 2, rejectedRows: 0 });
    expect(simulated.rejections).toEqual([]);
    expect(applied.batch).toMatchObject({ status: "APPLIED", totalRows: 2, validRows: 1, createdRows: 1, rejectedRows: 1 });
    expect(applied.rejections).toEqual([{ line: 3, field: "nationalId", reason: "duplicate-national-id" }]);
  });

  it("keeps the counts adding up: total = valid + rejected + skipped", () => {
    const { post } = model();
    post("simulate", file);
    post("apply", file);

    const { batch } = bodyOf(post("simulate", file));

    expect((batch.validRows as number) + (batch.rejectedRows as number) + (batch.skippedRows as number)).toBe(batch.totalRows);
  });

  it("cuts the rejections at 200 and says so", () => {
    const { post } = model();
    const many = csvOf(...Array.from({ length: 250 }, (_, index) => `${String(index)},,Mora,2013-06-02`));

    const answer = post("simulate", many);

    expect(bodyOf(answer).rejections).toHaveLength(200);
    expect((answer?.body as { rejectionsTruncated: boolean }).rejectionsTruncated).toBe(true);
    expect(bodyOf(answer).batch).toMatchObject({ rejectedRows: 250 });
  });
});

describe("the history, the refused write and the failing read", () => {
  it("lists the newest first and pages as the server does", () => {
    const { post, get } = model();
    post("simulate", csvOf("1,Ana,Pérez,2013-05-01"));
    post("simulate", csvOf("2,Luis,Mora,2013-06-02"));
    post("apply", csvOf("2,Luis,Mora,2013-06-02"));

    const page = get("page=0&size=2")?.body as { items: { status: string }[]; total: number; size: number };

    expect(page.total).toBe(3);
    expect(page.items.map((item) => item.status)).toEqual(["APPLIED", "VALIDATED"]);
    expect(get("size=101")?.status).toBe(400);
  });

  it("has a seeded history of a simulation and its apply, and an empty one", () => {
    expect(createImportsModel(DEFAULT_IMPORTS, NOW).batches().map((batch) => batch.status)).toEqual(["VALIDATED", "APPLIED"]);
    expect(model().imports.batches()).toEqual([]);
  });

  it("refuses every write with 403 and changes nothing, and still serves the read", () => {
    const { imports, post, get } = model({ writes: "forbidden" });

    expect(post("simulate", csvOf("1,Ana,Pérez,2013-05-01"))?.status).toBe(403);
    expect(post("apply", csvOf("1,Ana,Pérez,2013-05-01"))?.status).toBe(403);
    expect(get()?.status).toBe(200);
    expect(imports.batches()).toEqual([]);
  });

  it("fails the first reads with a trace id and then recovers", () => {
    const { get } = model({ reads: "failing-first" });

    for (let attempt = 0; attempt < FAILING_IMPORT_READS; attempt += 1) {
      expect(get()?.status).toBe(500);
    }
    expect(get()?.status).toBe(200);
  });

  it("does not answer a path that is not one of the three operations", () => {
    expect(model().imports.handle({ method: "DELETE", path: "/api/v1/imports", query: new URLSearchParams(), contentType: undefined, body: undefined })).toBeUndefined();
  });
});

// The guardians and the links (imports-guardians cut).
describe("the three kinds", () => {
  const GUARDIAN_HEADER = "legacyId,firstName,lastName,nationalId,rtn,email";
  const LINK_HEADER = "studentLegacyId,guardianLegacyId,relationship,financialResponsibilityPercent,isPrimaryPayer,isBillingRecipient";
  type Route = "students" | "guardians" | "guardian-links";

  function kinds() {
    const imports = createImportsModel({ ...DEFAULT_IMPORTS, seed: "empty" }, NOW);
    const post = (route: Route, action: "simulate" | "apply", csv: string) =>
      imports.handle({ method: "POST", path: `/api/v1/imports/${route}/${action}`, query: new URLSearchParams(), ...upload(csv) });
    const get = (query = "") => imports.handle({ method: "GET", path: "/api/v1/imports", query: new URLSearchParams(query), contentType: undefined, body: undefined });
    const students = (...ids: string[]) => {
      const file = csvOf(...ids.map((id) => `${id},Ana,Pérez,2013-05-01`));
      post("students", "simulate", file);
      post("students", "apply", file);
    };
    const guardians = (...ids: string[]) => {
      const file = [GUARDIAN_HEADER, ...ids.map((id) => `${id},Rosa,Mejía,,,`)].join("\n");
      post("guardians", "simulate", file);
      post("guardians", "apply", file);
    };
    return { imports, post, get, students, guardians };
  }
  const linksOf = (...rows: string[]) => [LINK_HEADER, ...rows].join("\n");
  const reasons = (answer: ReturnType<ReturnType<typeof kinds>["post"]>) =>
    bodyOf(answer).rejections.map((rejection) => `${String(rejection.line)}:${rejection.field}:${rejection.reason}`);

  it("judges the guardians alone: the required fields, the RTN and the e-mail, a document twice in the file, and an imported legacyId skipped", () => {
    const { post, guardians } = kinds();
    guardians("P1");

    const answer = post("guardians", "simulate", [GUARDIAN_HEADER, "P1,Rosa,Mejía,,,", "P2,,Mora,,12,", "P3,Eva,Ruiz,0801-1990-00001,,eva", "P4,Luis,Paz,0801-1990-00001,,"].join("\n"));

    expect(reasons(answer)).toEqual(["3:firstName:required", "3:rtn:invalid-format", "4:email:invalid-format", "5:nationalId:duplicate-in-file", "2:legacyId:already-imported"]);
    expect(bodyOf(answer).batch).toMatchObject({ kind: "GUARDIANS", totalRows: 4, validRows: 0, rejectedRows: 3, skippedRows: 1 });
  });

  it("checks the documents already stored only when applying, for the guardians too", () => {
    const { post } = kinds();
    const file = [GUARDIAN_HEADER, `P1,Rosa,Mejía,${STORED_NATIONAL_ID},,`].join("\n");

    expect(bodyOf(post("guardians", "simulate", file)).batch).toMatchObject({ validRows: 1, rejectedRows: 0 });
    const applied = post("guardians", "apply", file);

    expect(reasons(applied)).toEqual(["2:nationalId:duplicate-national-id"]);
    expect(bodyOf(applied).batch).toMatchObject({ kind: "GUARDIANS", createdRows: 0, rejectedRows: 1 });
  });

  it("takes the 409 of each kind from its own batches and simulations", () => {
    const { post } = kinds();
    const students = csvOf("1,Ana,Pérez,2013-05-01");
    const guardians = [GUARDIAN_HEADER, "P1,Rosa,Mejía,,,"].join("\n");
    post("students", "simulate", students);

    expect(post("guardians", "apply", guardians)?.body).toMatchObject({ type: "https://confia.hn/problems/import-not-simulated" });
    post("guardians", "simulate", guardians);
    expect(post("guardians", "apply", [GUARDIAN_HEADER, "P2,Rosa,Mejía,,,"].join("\n"))?.body).toMatchObject({ type: "https://confia.hn/problems/import-file-changed" });
    expect(post("students", "apply", students)?.status).toBe(200);
  });

  it("names the columns of each kind: a column of another kind is unknown", () => {
    const { post } = kinds();

    expect(post("guardians", "simulate", csvOf("1,Ana,Pérez,2013-05-01"))?.status).toBe(400);
    const errors = (post("guardian-links", "simulate", [GUARDIAN_HEADER, "P1,Rosa,Mejía,,,"].join("\n"))?.body as { errors: unknown[] }).errors;
    expect(errors).toContainEqual({ field: "studentLegacyId", reason: "missing-column" });
  });

  it("accepts a group of 33.33 + 33.33 + 33.34 and rejects 99.99 and 100.01 with the sum on every line, all in exact hundredths", () => {
    const { post, students, guardians } = kinds();
    students("S1", "S2", "S3");
    guardians("G1", "G2", "G3");

    const answer = post(
      "guardian-links",
      "simulate",
      linksOf(
        "S1,G1,FATHER,33.33,true,", "S1,G2,MOTHER,33.33,,", "S1,G3,OTHER,33.34,,",
        "S2,G1,FATHER,33.33,,", "S2,G2,MOTHER,33.33,,", "S2,G3,OTHER,33.33,,",
        "S3,G1,FATHER,33.34,,", "S3,G2,MOTHER,33.34,,", "S3,G3,OTHER,33.33,,",
      ),
    );

    expect(bodyOf(answer).batch).toMatchObject({ kind: "GUARDIAN_LINKS", totalRows: 9, validRows: 3, rejectedRows: 6, skippedRows: 0 });
    expect(reasons(answer)).toEqual([5, 6, 7, 8, 9, 10].map((line) => `${String(line)}:financialResponsibilityPercent:sum-not-100`));
  });

  it("rejects a whole group for a defect of one of its rows, the others as group-rejected, and the valid groups go on", () => {
    const { post, students, guardians } = kinds();
    students("S1", "S2");
    guardians("G1", "G2");

    const answer = post("guardian-links", "simulate", linksOf("S1,G1,FATHER,50,,", "S1,G2,MOTHER,50.555,,", "S2,G1,FATHER,100,,"));

    expect(reasons(answer)).toEqual(["2:row:group-rejected", "3:financialResponsibilityPercent:invalid-percent"]);
    expect(bodyOf(answer).batch).toMatchObject({ totalRows: 3, validRows: 1, rejectedRows: 2 });
  });

  it("rejects two primary payers, two billing recipients and a guardian twice, each on all the lines of the group", () => {
    const { post, students, guardians } = kinds();
    students("S1", "S2", "S3");
    guardians("G1", "G2");

    const answer = post(
      "guardian-links",
      "simulate",
      linksOf("S1,G1,FATHER,50,true,", "S1,G2,MOTHER,50,true,", "S2,G1,FATHER,50,,true", "S2,G2,MOTHER,50,,true", "S3,G1,FATHER,50,,", "S3,G1,MOTHER,50,,"),
    );

    expect(reasons(answer)).toEqual([
      "2:isPrimaryPayer:multiple-primary-payer", "3:isPrimaryPayer:multiple-primary-payer",
      "4:isBillingRecipient:multiple-billing-recipient", "5:isBillingRecipient:multiple-billing-recipient",
      "6:guardianLegacyId:duplicate-guardian", "7:guardianLegacyId:duplicate-guardian",
    ]);
  });

  it("reports an unknown student or guardian by line, and only references this scenario imported count", () => {
    const { post, students, guardians } = kinds();
    students("S1");
    guardians("G1");

    const answer = post("guardian-links", "simulate", linksOf("S9,G1,FATHER,100,,", "S1,G9,FATHER,100,,"));

    expect(reasons(answer)).toEqual(["2:studentLegacyId:unknown-student", "3:guardianLegacyId:unknown-guardian"]);
  });

  it("applies the valid groups once: the second time every line is student-already-linked and counts as skipped, not as an error", () => {
    const { post, students, guardians, get } = kinds();
    students("S1");
    guardians("G1", "G2");
    const file = linksOf("S1,G1,FATHER,60,true,true", "S1,G2,MOTHER,40,,");
    post("guardian-links", "simulate", file);

    const first = post("guardian-links", "apply", file);
    const second = post("guardian-links", "simulate", file);
    const again = post("guardian-links", "apply", file);

    expect(bodyOf(first).batch).toMatchObject({ status: "APPLIED", createdRows: 2, validRows: 2, rejectedRows: 0, skippedRows: 0 });
    expect(bodyOf(second).batch).toMatchObject({ validRows: 0, rejectedRows: 0, skippedRows: 2 });
    expect(reasons(second)).toEqual(["2:studentLegacyId:student-already-linked", "3:studentLegacyId:student-already-linked"]);
    expect(bodyOf(again).batch).toMatchObject({ createdRows: 0, skippedRows: 2 });
    expect((get("kind=guardian-links")?.body as { total: number }).total).toBe(4);
  });

  it("filters the history by the kind of the routes and refuses a kind it does not know", () => {
    const { post, get, students, guardians } = kinds();
    students("S1");
    guardians("G1");
    post("guardians", "simulate", [GUARDIAN_HEADER, "P2,Rosa,Mejía,,,"].join("\n"));

    const only = (kind: string) => get(`kind=${kind}`)?.body as { items: { kind: string }[]; total: number };

    expect(only("students").items.map((item) => item.kind)).toEqual(["STUDENTS", "STUDENTS"]);
    expect(only("guardians").items.map((item) => item.kind)).toEqual(["GUARDIANS", "GUARDIANS", "GUARDIANS"]);
    expect(only("guardian-links").total).toBe(0);
    expect((get("")?.body as { total: number }).total).toBe(5);
    expect(get("kind=teachers")?.status).toBe(400);
  });
});

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

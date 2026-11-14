import { describe, expect, it } from "vitest";
import { importBatch, importBatchPage, importRun, problem } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { acta, actaPage, preview } from "./reconciliation-fixtures.ts";
import { applyImport, listImportBatches, listReconciliations, previewReconciliation, signReconciliation, simulateImport } from "./import-operations.ts";

// The operations of the imports over the real transport and the test server: the path, the multipart body in the part
// `file`, the bearer token, that nothing moves money (no Idempotency-Key), and the schema each answer must meet.
installMswServer();

const file = () => new File(["legacyId,firstName\n1,Ana\n"], "estudiantes.csv", { type: "text/csv" });

describe("simulate and apply", () => {
  it.each([
    ["simulate", simulateImport, "students", "/api/v1/imports/students/simulate"],
    ["apply", applyImport, "students", "/api/v1/imports/students/apply"],
    ["simulate", simulateImport, "guardians", "/api/v1/imports/guardians/simulate"],
    ["apply", applyImport, "guardians", "/api/v1/imports/guardians/apply"],
    ["simulate", simulateImport, "guardian-links", "/api/v1/imports/guardian-links/simulate"],
    ["apply", applyImport, "guardian-links", "/api/v1/imports/guardian-links/apply"],
    ["simulate", simulateImport, "enrollments", "/api/v1/imports/enrollments/simulate"],
    ["apply", applyImport, "enrollments", "/api/v1/imports/enrollments/apply"],
  ] as const)("%s of %s POSTs the file as multipart in the part `file`, with the bearer token and no JSON content type", async (_name, operation, kind, path) => {
    route("post", path, importRun());

    const result = await operation(kind, "tok-1", file());

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(sent?.method).toBe("POST");
    expect(sent?.headers.get("Content-Type")).toMatch(/^multipart\/form-data; boundary=/);
    expect(sent?.body).toContain('name="file"');
    expect(sent?.headers.get("Authorization")).toBe("Bearer tok-1");
    expect(sent?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("answers the two 409 of the import as problems with their code", async () => {
    route("post", "/api/v1/imports/students/apply", problem("import-file-changed", 409), problem("import-not-simulated", 409));

    const changed = await applyImport("students", "tok", file());
    const notSimulated = await applyImport("students", "tok", file());

    expect(changed).toMatchObject({ kind: "problem", problem: { status: 409, code: "import-file-changed" } });
    expect(notSimulated).toMatchObject({ kind: "problem", problem: { status: 409, code: "import-not-simulated" } });
  });

  it("keeps the field errors of a file that cannot be read", async () => {
    route("post", "/api/v1/imports/students/simulate", problem("validation-failed", 400, { errors: [{ field: "file", reason: "no-rows" }] }));

    const result = await simulateImport("students", "tok", file());

    expect(result).toMatchObject({ kind: "problem", problem: { code: "validation-failed", errors: [{ field: "file", reason: "no-rows" }] } });
  });

  it("answers a run that breaks the contract as an unexpected response, with none of its body", async () => {
    route("post", "/api/v1/imports/students/simulate", {
      status: 200,
      body: { batch: { id: 1 }, rejections: [{ line: 2, field: "firstName", reason: "Ana Pérez" }] },
      contentType: "application/json",
    });

    expect(await simulateImport("students", "tok", file())).toEqual({ kind: "unexpected-response", status: 200 });
  });
});

describe("the history", () => {
  it("sends page and size as query parameters and nothing else", async () => {
    route("get", "/api/v1/imports", importBatchPage([importBatch()]));

    const result = await listImportBatches("tok-2", { page: 1, size: 50 });

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(Object.fromEntries(new URLSearchParams(sent?.search))).toEqual({ page: "1", size: "50" });
    expect(sent?.headers.get("Authorization")).toBe("Bearer tok-2");
  });
});

describe("the acta of the reconciliation", () => {
  const YEAR = "11111111-1111-4111-8111-111111111111";

  it("POSTs the year, the reference and the official total as JSON with the bearer token and no Idempotency-Key", async () => {
    route("post", "/api/v1/imports/reconciliations", { status: 201, body: acta(), contentType: "application/json" });

    const result = await signReconciliation("tok-3", { academicYearId: YEAR, officialReference: "Oficio 1", officialEnrollmentCount: 120 });

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(sent?.method).toBe("POST");
    expect(JSON.parse(sent?.body ?? "")).toEqual({ academicYearId: YEAR, officialReference: "Oficio 1", officialEnrollmentCount: 120 });
    expect(sent?.headers.get("Authorization")).toBe("Bearer tok-3");
    expect(sent?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("keeps the field errors of a 400 and the code of a 404", async () => {
    route(
      "post",
      "/api/v1/imports/reconciliations",
      problem("validation-failed", 400, { errors: [{ field: "officialReference", reason: "size" }] }),
      problem("resource-not-found", 404),
    );

    const refused = await signReconciliation("tok", { academicYearId: YEAR, officialReference: "x", officialEnrollmentCount: 1 });
    const missing = await signReconciliation("tok", { academicYearId: YEAR, officialReference: "x", officialEnrollmentCount: 1 });

    expect(refused).toMatchObject({ kind: "problem", problem: { code: "validation-failed", errors: [{ field: "officialReference", reason: "size" }] } });
    expect(missing).toMatchObject({ kind: "problem", problem: { status: 404, code: "resource-not-found" } });
  });

  it("GETs the preview of one year with the year as the only query parameter", async () => {
    route("get", "/api/v1/imports/reconciliations/preview", { status: 200, body: preview(), contentType: "application/json" });

    const result = await previewReconciliation("tok-4", YEAR);

    expect(result).toMatchObject({ kind: "ok", data: { enrollmentsActive: 118 } });
    const [sent] = requests();
    expect(Object.fromEntries(new URLSearchParams(sent?.search))).toEqual({ academicYearId: YEAR });
  });

  it("answers a preview that breaks the contract as an unexpected response", async () => {
    route("get", "/api/v1/imports/reconciliations/preview", { status: 200, body: { enrollmentsActive: "many" }, contentType: "application/json" });

    expect(await previewReconciliation("tok", YEAR)).toEqual({ kind: "unexpected-response", status: 200 });
  });

  it("lists the actas with page and size as query parameters and nothing else", async () => {
    route("get", "/api/v1/imports/reconciliations", { status: 200, body: actaPage([acta()]), contentType: "application/json" });

    const result = await listReconciliations("tok-5", { page: 2, size: 10 });

    expect(result).toMatchObject({ kind: "ok", data: { total: 1 } });
    const [sent] = requests();
    expect(Object.fromEntries(new URLSearchParams(sent?.search))).toEqual({ page: "2", size: "10" });
  });
});

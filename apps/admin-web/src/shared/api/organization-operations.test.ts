import { describe, expect, it } from "vitest";
import { academicYear, grade, modality, organizationPage, organizationRecord, problem, section } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import {
  createAcademicYear,
  createGrade,
  createModality,
  createSection,
  listAcademicYears,
  listGrades,
  listModalities,
  listSections,
  updateAcademicYear,
  updateGrade,
  updateModality,
  updateSection,
} from "./organization-operations.ts";

// The twelve operations of /api/v1/organization over the real transport and the test server: the path, the query,
// the bearer token and the body of each, and the schema each answer must meet. None moves money, so none sends an
// Idempotency-Key.
installMswServer();

const BASE = "/api/v1/organization";
const ID = "7f9c24e8-3b1a-4d6e-9f2a-1c5b8a7d3e40";

describe("the listings", () => {
  it("send page, size and sort as query parameters, and the bearer token, and nothing else", async () => {
    route("get", `${BASE}/academic-years`, organizationPage("academic-years", [academicYear()]));

    const result = await listAcademicYears("tok-1", { page: 2, size: 50, sort: "name,desc" });

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(sent?.method).toBe("GET");
    expect(sent?.path).toBe(`${BASE}/academic-years`);
    expect(Object.fromEntries(new URLSearchParams(sent?.search))).toEqual({ page: "2", size: "50", sort: "name,desc" });
    expect(sent?.headers.get("Authorization")).toBe("Bearer tok-1");
    expect(sent?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("leave out a parameter that is not set", async () => {
    route("get", `${BASE}/modalities`, organizationPage("modalities", []));

    await listModalities("tok", { page: 0, size: 25 });

    expect(requests()[0]?.search).toBe("?page=0&size=25");
  });

  it("carry the filters of grades and of sections", async () => {
    route("get", `${BASE}/grades`, organizationPage("grades", []));
    route("get", `${BASE}/sections`, organizationPage("sections", []));

    await listGrades("tok", { page: 0, size: 25, modalityId: ID });
    await listSections("tok", { page: 0, size: 25, academicYearId: ID, gradeId: ID });

    expect(new URLSearchParams(requests()[0]?.search).get("modalityId")).toBe(ID);
    expect(Object.fromEntries(new URLSearchParams(requests()[1]?.search))).toMatchObject({ academicYearId: ID, gradeId: ID });
  });

  it("answer a page that breaks the contract as an unexpected response, with none of its body", async () => {
    route("get", `${BASE}/grades`, { status: 200, body: { items: [{ id: 1 }], page: 0, size: 25, total: 1 }, contentType: "application/json" });

    expect(await listGrades("tok", { page: 0, size: 25 })).toEqual({ kind: "unexpected-response", status: 200 });
  });

  it("answer a problem as a problem, with its code", async () => {
    route("get", `${BASE}/sections`, problem("forbidden", 403));

    const result = await listSections("tok", { page: 0, size: 25 });

    expect(result).toMatchObject({ kind: "problem", problem: { status: 403, code: "forbidden" } });
  });
});

describe("the writes", () => {
  it("POST each resource with its body and read the 201", async () => {
    route("post", `${BASE}/academic-years`, organizationRecord("academic-years", academicYear(), 201));
    route("post", `${BASE}/modalities`, organizationRecord("modalities", modality(), 201));
    route("post", `${BASE}/grades`, organizationRecord("grades", grade(), 201));
    route("post", `${BASE}/sections`, organizationRecord("sections", section(), 201));

    const results = [
      await createAcademicYear("tok", { name: "A", startDate: "2026-01-01", endDate: "2026-12-01" }),
      await createModality("tok", { code: "C", name: "N" }),
      await createGrade("tok", { modalityId: ID, name: "N", ordinal: 1 }),
      await createSection("tok", { academicYearId: ID, gradeId: ID, name: "A" }),
    ];

    expect(results.map((result) => (result.kind === "ok" ? result.status : result.kind))).toEqual([201, 201, 201, 201]);
    expect(requests().map((sent) => `${sent.method} ${sent.path}`)).toEqual([
      `POST ${BASE}/academic-years`,
      `POST ${BASE}/modalities`,
      `POST ${BASE}/grades`,
      `POST ${BASE}/sections`,
    ]);
    expect(JSON.parse(requests()[1]?.body ?? "{}")).toEqual({ code: "C", name: "N" });
    expect(requests().every((sent) => sent.headers.get("Authorization") === "Bearer tok" && !sent.headers.has("Idempotency-Key"))).toBe(true);
  });

  it("PUT each resource to its own id with the whole body and read the 200", async () => {
    route("put", `${BASE}/academic-years/${ID}`, organizationRecord("academic-years", academicYear()));
    route("put", `${BASE}/modalities/${ID}`, organizationRecord("modalities", modality()));
    route("put", `${BASE}/grades/${ID}`, organizationRecord("grades", grade()));
    route("put", `${BASE}/sections/${ID}`, organizationRecord("sections", section()));

    const results = [
      await updateAcademicYear("tok", ID, { name: "A", startDate: "2026-01-01", endDate: "2026-12-01", status: "ACTIVE", isActive: true }),
      await updateModality("tok", ID, { code: "C", name: "N", isActive: false }),
      await updateGrade("tok", ID, { name: "N", ordinal: 2, isActive: true }),
      await updateSection("tok", ID, { name: "A", isActive: true }),
    ];

    expect(results.map((result) => (result.kind === "ok" ? result.status : result.kind))).toEqual([200, 200, 200, 200]);
    expect(requests().map((sent) => sent.method)).toEqual(["PUT", "PUT", "PUT", "PUT"]);
    expect(JSON.parse(requests()[0]?.body ?? "{}")).toEqual({ name: "A", startDate: "2026-01-01", endDate: "2026-12-01", status: "ACTIVE", isActive: true });
  });

  it("encode the id in the path, so it can never change it", async () => {
    route("put", `${BASE}/grades/:id`, organizationRecord("grades", grade()));

    await updateGrade("tok", "a/b?c", { name: "N", ordinal: 1, isActive: true });

    expect(requests()[0]?.path).toBe(`${BASE}/grades/a%2Fb%3Fc`);
  });

  it("answer a business problem as a problem with its code", async () => {
    route("post", `${BASE}/academic-years`, problem("academic-year-overlap", 409));

    expect(await createAcademicYear("tok", { name: "A", startDate: "2026-01-01", endDate: "2026-12-01" })).toMatchObject({
      kind: "problem",
      problem: { status: 409, code: "academic-year-overlap" },
    });
  });
});

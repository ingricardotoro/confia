import { describe, expect, it } from "vitest";
import { enrollmentPage, enrollmentRecord, problem, studentEnrollment } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { enrollStudent, exitEnrollment, getEnrollment, listEnrollments, transferEnrollment } from "./enrollment-operations.ts";

// The five operations of /api/v1/enrollments over the real transport and the test server: the path, the query, the bearer
// token and the body of each, and the schema each answer must meet. None moves money, so none sends an Idempotency-Key.
installMswServer();

const PATH = "/api/v1/enrollments";
const ID = "7f9c24e8-3b1a-4d6e-9f2a-1c5b8a7d3e40";
const UUID = "11111111-1111-4111-8111-111111111111";

describe("the listing", () => {
  it("sends the page, the order and the filters as query parameters and the bearer token, and leaves out the absent ones", async () => {
    route("get", PATH, enrollmentPage([studentEnrollment()]));

    const result = await listEnrollments("tok-1", { page: 2, size: 50, sort: "status,desc", academicYearId: UUID, status: "ACTIVE", sectionId: undefined });

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(sent?.method).toBe("GET");
    expect(sent?.path).toBe(PATH);
    expect(Object.fromEntries(new URLSearchParams(sent?.search))).toEqual({ page: "2", size: "50", sort: "status,desc", academicYearId: UUID, status: "ACTIVE" });
    expect(sent?.headers.get("Authorization")).toBe("Bearer tok-1");
    expect(sent?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("answers a page that breaks the contract as an unexpected response, with none of its body", async () => {
    route("get", PATH, { status: 200, body: { items: [{ id: 1, studentFirstName: "Ana" }], page: 0, size: 25, total: 1 }, contentType: "application/json" });

    expect(await listEnrollments("tok", { page: 0, size: 25 })).toEqual({ kind: "unexpected-response", status: 200 });
  });
});

describe("the detail and the three writes", () => {
  it("reads one enrollment by id", async () => {
    route("get", `${PATH}/${ID}`, enrollmentRecord(studentEnrollment({ id: ID })));

    expect((await getEnrollment("tok", ID)).kind).toBe("ok");
    expect(requests()[0]?.path).toBe(`${PATH}/${ID}`);
  });

  it("enrolls with a POST of the three fields, without a year and without an Idempotency-Key", async () => {
    route("post", PATH, enrollmentRecord(studentEnrollment(), 201));

    const result = await enrollStudent("tok", { studentId: UUID, sectionId: UUID, enrolledOn: "2026-02-02" });

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(sent?.method).toBe("POST");
    expect(JSON.parse(sent?.body ?? "{}")).toEqual({ studentId: UUID, sectionId: UUID, enrolledOn: "2026-02-02" });
    expect(sent?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("transfers and exits with a POST on the action of the enrollment", async () => {
    route("post", `${PATH}/${ID}/transfer`, enrollmentRecord(studentEnrollment()));
    route("post", `${PATH}/${ID}/exit`, enrollmentRecord(studentEnrollment({ status: "GRADUATED", withdrawnOn: "2026-11-01" })));

    await transferEnrollment("tok", ID, { toSectionId: UUID, transferredOn: "2026-03-02" });
    await exitEnrollment("tok", ID, { status: "GRADUATED", withdrawnOn: "2026-11-01" });

    expect(requests().map((sent) => [sent.method, sent.path])).toEqual([
      ["POST", `${PATH}/${ID}/transfer`],
      ["POST", `${PATH}/${ID}/exit`],
    ]);
  });

  it("answers a business error as a problem with its code", async () => {
    route("post", `${PATH}/${ID}/exit`, problem("invalid-enrollment-state", 409));

    const result = await exitEnrollment("tok", ID, { status: "WITHDRAWN", withdrawnOn: "2026-05-04", withdrawalReason: "Cambio de ciudad" });

    expect(result).toMatchObject({ kind: "problem", problem: { status: 409, code: "invalid-enrollment-state" } });
  });
});

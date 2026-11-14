import { describe, expect, it } from "vitest";
import { problem, student, studentPage, studentRecord } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { createStudent, listStudents, updateStudent } from "./student-operations.ts";

// The three operations of /api/v1/students over the real transport and the test server: the path, the query, the bearer
// token and the body of each, and the schema each answer must meet. None moves money, so none sends an Idempotency-Key.
installMswServer();

const PATH = "/api/v1/students";
const ID = "7f9c24e8-3b1a-4d6e-9f2a-1c5b8a7d3e40";

describe("the listing", () => {
  it("sends page, size, sort, text and status as query parameters, and the bearer token", async () => {
    route("get", PATH, studentPage([student()]));

    const result = await listStudents("tok-1", { page: 2, size: 50, sort: "lastName,desc", q: "pér ez", status: "INACTIVE" });

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(sent?.method).toBe("GET");
    expect(sent?.path).toBe(PATH);
    expect(Object.fromEntries(new URLSearchParams(sent?.search))).toEqual({
      page: "2",
      size: "50",
      sort: "lastName,desc",
      q: "pér ez",
      status: "INACTIVE",
    });
    expect(sent?.headers.get("Authorization")).toBe("Bearer tok-1");
    expect(sent?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("leaves out the text and the status when they are not set", async () => {
    route("get", PATH, studentPage([]));

    await listStudents("tok", { page: 0, size: 25, q: undefined, status: undefined });

    expect(requests()[0]?.search).toBe("?page=0&size=25");
  });

  it("answers a page that breaks the contract as an unexpected response, with none of its body", async () => {
    route("get", PATH, {
      status: 200,
      body: { items: [{ id: 1, nationalId: "0801201412345" }], page: 0, size: 25, total: 1 },
      contentType: "application/json",
    });

    expect(await listStudents("tok", { page: 0, size: 25 })).toEqual({ kind: "unexpected-response", status: 200 });
  });

  it("answers a problem as a problem, with its code", async () => {
    route("get", PATH, problem("forbidden", 403));

    expect(await listStudents("tok", { page: 0, size: 25 })).toMatchObject({ kind: "problem", problem: { status: 403, code: "forbidden" } });
  });
});

describe("the writes", () => {
  it("POSTs the body as it is and reads the 201", async () => {
    route("post", PATH, studentRecord(student({ studentCode: "E-000009" }), 201));

    const result = await createStudent("tok", {
      firstName: "Ana",
      lastName: "Pérez",
      birthDate: "2014-03-09",
      sex: "F",
      nationalId: "0801201412345",
    });

    expect(result).toMatchObject({ kind: "ok", status: 201 });
    expect(JSON.parse(requests()[0]?.body ?? "{}")).toEqual({
      firstName: "Ana",
      lastName: "Pérez",
      birthDate: "2014-03-09",
      sex: "F",
      nationalId: "0801201412345",
    });
    expect(requests()[0]?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("PUTs to the id of the student with the whole body and reads the 200", async () => {
    route("put", `${PATH}/${ID}`, studentRecord(student()));

    const result = await updateStudent("tok", ID, {
      firstName: "Ana",
      lastName: "Pérez",
      birthDate: "2014-03-09",
      sex: "F",
      status: "INACTIVE",
      nationalId: "",
    });

    expect(result).toMatchObject({ kind: "ok", status: 200 });
    expect(requests()[0]?.method).toBe("PUT");
    expect(JSON.parse(requests()[0]?.body ?? "{}")).toMatchObject({ status: "INACTIVE", nationalId: "" });
  });

  it("encodes the id in the path, so it can never change it", async () => {
    route("put", `${PATH}/:id`, studentRecord(student()));

    await updateStudent("tok", "a/b?c", { firstName: "A", lastName: "B", birthDate: "2014-03-09", sex: "M", status: "ACTIVE" });

    expect(requests()[0]?.path).toBe(`${PATH}/a%2Fb%3Fc`);
  });

  it.each([
    ["duplicate-student-code", 409],
    ["duplicate-national-id", 409],
  ] as const)("answers %s as a problem with its code", async (code, status) => {
    route("post", PATH, problem(code, status));

    expect(await createStudent("tok", { firstName: "A", lastName: "B", birthDate: "2014-03-09", sex: "M" })).toMatchObject({
      kind: "problem",
      problem: { status, code },
    });
  });
});

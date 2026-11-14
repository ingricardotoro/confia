import { describe, expect, it } from "vitest";
import { guardian, guardianPage, guardianRecord, problem, studentGuardians, studentLink } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { createGuardian, getGuardian, getStudentGuardians, listGuardians, replaceStudentGuardians, updateGuardian } from "./guardian-operations.ts";

// The six operations of the guardians over the real transport and the test server: the path, the query, the bearer token and
// the body of each, and the schema each answer must meet. None moves money, so none sends an Idempotency-Key.
installMswServer();

const ID = "7f9c24e8-3b1a-4d6e-9f2a-1c5b8a7d3e40";

describe("the listing", () => {
  it("sends page, size, sort, text and status as query parameters, and the bearer token", async () => {
    route("get", "/api/v1/guardians", guardianPage([guardian()]));

    const result = await listGuardians("tok-1", { page: 2, size: 50, sort: "code,desc", q: "ro", status: "INACTIVE" });

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(Object.fromEntries(new URLSearchParams(sent?.search))).toEqual({ page: "2", size: "50", sort: "code,desc", q: "ro", status: "INACTIVE" });
    expect(sent?.headers.get("Authorization")).toBe("Bearer tok-1");
    expect(sent?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("answers a page that breaks the contract as an unexpected response, with none of its body", async () => {
    route("get", "/api/v1/guardians", { status: 200, body: { items: [{ id: 1, nationalId: "0801198012345" }], page: 0, size: 25, total: 1 }, contentType: "application/json" });

    expect(await listGuardians("tok", { page: 0, size: 25 })).toEqual({ kind: "unexpected-response", status: 200 });
  });
});

describe("the other operations", () => {
  it("reads one guardian by id", async () => {
    route("get", `/api/v1/guardians/${ID}`, guardianRecord(guardian({ id: ID })));

    expect((await getGuardian("tok", ID)).kind).toBe("ok");
    expect(requests()[0]?.path).toBe(`/api/v1/guardians/${ID}`);
  });

  it("POSTs a create and PUTs an edit with the body as it is", async () => {
    route("post", "/api/v1/guardians", guardianRecord(guardian(), 201));
    route("put", `/api/v1/guardians/${ID}`, guardianRecord(guardian({ id: ID })));

    await createGuardian("tok", { firstName: "Rosa", lastName: "Mejía", nationalId: "0801198012345" });
    await updateGuardian("tok", ID, { firstName: "Rosa", lastName: "Mejía", status: "ACTIVE" });

    const [create, update] = requests();
    expect(create?.method).toBe("POST");
    expect(JSON.parse(create?.body ?? "{}")).toEqual({ firstName: "Rosa", lastName: "Mejía", nationalId: "0801198012345" });
    expect(update?.method).toBe("PUT");
    expect(JSON.parse(update?.body ?? "{}")).toEqual({ firstName: "Rosa", lastName: "Mejía", status: "ACTIVE" });
  });

  it("reads and replaces the set of guardians of a student", async () => {
    route("get", `/api/v1/students/${ID}/guardians`, studentGuardians([studentLink()]));
    route("put", `/api/v1/students/${ID}/guardians`, studentGuardians([studentLink()]));

    const read = await getStudentGuardians("tok", ID);
    const body = { links: [{ guardianId: ID, relationship: "MOTHER" as const, financialResponsibilityPercent: 100, isPrimaryPayer: true, isBillingRecipient: true }] };
    const replaced = await replaceStudentGuardians("tok", ID, body);

    expect(read.kind).toBe("ok");
    expect(replaced.kind).toBe("ok");
    const [get, put] = requests();
    expect(get?.method).toBe("GET");
    expect(put?.method).toBe("PUT");
    expect(JSON.parse(put?.body ?? "{}")).toEqual(body);
    expect(put?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("answers a problem as a problem, with its code and the reason of a split", async () => {
    route("put", `/api/v1/students/${ID}/guardians`, problem("invalid-responsibility-split", 400, { errors: [{ field: "links", reason: "sum-not-100" }] }));

    expect(await replaceStudentGuardians("tok", ID, { links: [] })).toMatchObject({
      kind: "problem",
      problem: { status: 400, code: "invalid-responsibility-split", errors: [{ field: "links", reason: "sum-not-100" }] },
    });
  });
});

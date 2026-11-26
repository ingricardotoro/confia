import { describe, expect, it } from "vitest";
import { award, awardPage, awardRecord, chargeSimulation, chargeSimulationRecord, scholarship, scholarshipPage, scholarshipRecord } from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { simulateCharges } from "./charges-operations.ts";
import {
  approveAward,
  createScholarship,
  deactivateScholarship,
  getAward,
  listAwards,
  listScholarships,
  reactivateScholarship,
  rejectAward,
  requestAward,
  revokeAward,
  updateScholarship,
} from "./scholarships-operations.ts";

// The operations of /api/v1/scholarships and /api/v1/scholarship-awards over the real transport and the test server: the path, the query, the bearer
// token, the body and the Idempotency-Key of each. Only the three decisions on an award send a key; none sends or parses an amount of its own.
installMswServer();

const SCHOLARSHIPS = "/api/v1/scholarships";
const AWARDS = "/api/v1/scholarship-awards";
const ID = "00000000-0000-4000-8000-0000000e0001";
const KEY = "5b0f0a52-6d2c-4a4e-9a0a-6a1f3f1f0b02";

describe("the scholarships", () => {
  it("lists with page, size, sort and the filters as query parameters, a boolean included, and leaves out a filter that is not set", async () => {
    route("get", SCHOLARSHIPS, scholarshipPage([scholarship()]));

    const result = await listScholarships("tok-1", { page: 2, size: 50, sort: "name,asc", benefitType: "FIXED_AMOUNT", active: false });

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(Object.fromEntries(new URLSearchParams(sent?.search))).toEqual({ page: "2", size: "50", sort: "name,asc", benefitType: "FIXED_AMOUNT", active: "false" });
    expect(sent?.headers.get("Authorization")).toBe("Bearer tok-1");
    expect(sent?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("creates and updates with the body as it is, and sends no Idempotency-Key", async () => {
    route("post", SCHOLARSHIPS, scholarshipRecord(scholarship(), 201));
    route("put", `${SCHOLARSHIPS}/${ID}`, scholarshipRecord(scholarship()));
    const body = { code: "BECA25", name: "Beca", benefitType: "PERCENTAGE" as const, percent: "25", conceptIds: [ID] };

    const created = await createScholarship("tok", body);
    const updated = await updateScholarship("tok", ID, { version: 3, name: "Beca", benefitType: "PERCENTAGE", percent: "25", conceptIds: [ID] });

    expect(created.kind).toBe("ok");
    expect(updated.kind).toBe("ok");
    expect(JSON.parse(requests()[0]?.body ?? "{}")).toEqual(body);
    expect(JSON.parse(requests()[1]?.body ?? "{}")).toMatchObject({ version: 3 });
    expect(requests().every((sent) => !sent.headers.has("Idempotency-Key"))).toBe(true);
  });

  it("deactivates and reactivates by id, with no body", async () => {
    route("post", `${SCHOLARSHIPS}/${ID}/deactivate`, scholarshipRecord(scholarship({ active: false })));
    route("post", `${SCHOLARSHIPS}/${ID}/reactivate`, scholarshipRecord(scholarship()));

    const off = await deactivateScholarship("tok", ID);
    const on = await reactivateScholarship("tok", ID);

    expect(off.kind === "ok" && off.data.active).toBe(false);
    expect(on.kind === "ok" && on.data.active).toBe(true);
    expect(requests().map((sent) => sent.body)).toEqual(["", ""]);
  });

  it("encodes the id, so it can never change the path", async () => {
    route("post", `${SCHOLARSHIPS}/:id/deactivate`, scholarshipRecord(scholarship()));

    await deactivateScholarship("tok", "a/b");

    expect(requests()[0]?.path).toBe(`${SCHOLARSHIPS}/a%2Fb/deactivate`);
  });
});

describe("the awards", () => {
  it("lists with the filters of the status, the scholarship and the student as query parameters", async () => {
    route("get", AWARDS, awardPage([award()]));

    const result = await listAwards("tok", { page: 0, size: 25, sort: "requestedAt,desc", status: "pending", scholarshipId: ID, studentId: undefined });

    expect(result.kind).toBe("ok");
    expect(Object.fromEntries(new URLSearchParams(requests()[0]?.search))).toEqual({ page: "0", size: "25", sort: "requestedAt,desc", status: "pending", scholarshipId: ID });
  });

  it("reads one award, and requests one with its body and no Idempotency-Key", async () => {
    route("get", `${AWARDS}/${ID}`, awardRecord(award({ id: ID })));
    route("post", AWARDS, awardRecord(award(), 201));
    const body = { studentId: ID, scholarshipId: ID, academicYearId: ID, validFrom: "2027-01-01", validTo: "2027-12-31" };

    const read = await getAward("tok", ID);
    const requested = await requestAward("tok", body);

    expect(read.kind === "ok" && read.data.id).toBe(ID);
    expect(requested.kind).toBe("ok");
    expect(JSON.parse(requests()[1]?.body ?? "{}")).toEqual(body);
    expect(requests()[1]?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("approves, rejects and revokes with the bearer token and the Idempotency-Key, and the reason in the body of the last two", async () => {
    route("post", `${AWARDS}/${ID}/approve`, awardRecord(award()));
    route("post", `${AWARDS}/${ID}/reject`, awardRecord(award()));
    route("post", `${AWARDS}/${ID}/revoke`, awardRecord(award()));
    const reason = "El estudiante no cumple el promedio mínimo";

    await approveAward("tok-3", ID, KEY);
    await rejectAward("tok-3", ID, { reason }, KEY);
    await revokeAward("tok-3", ID, { reason }, KEY);

    for (const sent of requests()) {
      expect(sent.headers.get("Idempotency-Key")).toBe(KEY);
      expect(sent.headers.get("Authorization")).toBe("Bearer tok-3");
    }
    expect(requests()[0]?.body).toBe("");
    expect(JSON.parse(requests()[1]?.body ?? "{}")).toEqual({ reason });
    expect(JSON.parse(requests()[2]?.body ?? "{}")).toEqual({ reason });
  });
});

describe("the simulation of charges", () => {
  it("asks for the student and the first day of the period of one plan, and keeps the amounts as strings the server rounded", async () => {
    route("get", `/api/v1/charges/plans/${ID}/simulation`, chargeSimulationRecord(chargeSimulation()));

    const result = await simulateCharges("tok", ID, { studentId: ID, period: "2027-02-01" });

    expect(result.kind).toBe("ok");
    expect(Object.fromEntries(new URLSearchParams(requests()[0]?.search))).toEqual({ studentId: ID, period: "2027-02-01" });
    expect(requests()[0]?.headers.has("Idempotency-Key")).toBe(false);
    expect(result.kind === "ok" && result.data.lines[0]?.netBase).toEqual({ amount: "825.00", currency: "HNL" });
  });

  it("reads the nulls the server writes for an absent member as an absent one", async () => {
    const reply = chargeSimulationRecord(chargeSimulation());
    const body = reply.body as { lines: object[] };
    route("get", `/api/v1/charges/plans/${ID}/simulation`, { ...reply, body: { ...body, lines: body.lines.map((line) => ({ ...line, errorCode: null, existingChargeId: null })) } });

    const result = await simulateCharges("tok", ID, { studentId: ID, period: "2027-02-01" });

    expect(result.kind === "ok" && result.data.lines[0]?.errorCode).toBeUndefined();
  });
});

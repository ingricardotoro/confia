import { describe, expect, it } from "vitest";
import {
  chargePlan,
  chargePlanDetail,
  chargePlanDetailRecord,
  chargePlanItem,
  chargePlanItemRecord,
  chargePlanPage,
  chargePlanRecord,
  noContent,
  problem,
} from "../../test/contract-responses.ts";
import { installMswServer, requests, route } from "../../test/msw-server.ts";
import { addChargePlanItem, createChargePlan, getChargePlan, listChargePlans, removeChargePlanItem, updateChargePlan } from "./charges-operations.ts";

// The six operations of /api/v1/charges/plans over the real transport and the test server: the path, the query, the
// bearer token and the body of each, and the schema each answer must meet. None moves money, so none sends an
// Idempotency-Key.
installMswServer();

const BASE = "/api/v1/charges/plans";
const ID = "7f9c24e8-3b1a-4d6e-9f2a-1c5b8a7d3e40";
const ITEM = "0b6f1f66-5d0b-4a54-8f3a-9d7c1e2a4b10";

describe("the listing", () => {
  it("sends page, size, sort and the filters as query parameters, and the bearer token, and nothing else", async () => {
    route("get", BASE, chargePlanPage([chargePlan()]));

    const result = await listChargePlans("tok-1", { page: 1, size: 50, sort: "name,desc", academicYearId: ID, targetScope: "GRADE", status: "DRAFT" });

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(sent?.method).toBe("GET");
    expect(sent?.path).toBe(BASE);
    expect(Object.fromEntries(new URLSearchParams(sent?.search))).toEqual({
      page: "1",
      size: "50",
      sort: "name,desc",
      academicYearId: ID,
      targetScope: "GRADE",
      status: "DRAFT",
    });
    expect(sent?.headers.get("Authorization")).toBe("Bearer tok-1");
    expect(sent?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("leaves out a filter that is not set", async () => {
    route("get", BASE, chargePlanPage([]));

    await listChargePlans("tok", { page: 0, size: 25, status: undefined });

    expect(requests()[0]?.search).toBe("?page=0&size=25");
  });

  it("answers a page that breaks the contract as an unexpected response, with none of its body", async () => {
    route("get", BASE, { status: 200, body: { items: [{ id: 1 }], page: 0, size: 25, total: 1 }, contentType: "application/json" });

    expect(await listChargePlans("tok", { page: 0, size: 25 })).toEqual({ kind: "unexpected-response", status: 200 });
  });

  it("answers a problem as a problem, with its code", async () => {
    route("get", BASE, problem("forbidden", 403));

    expect(await listChargePlans("tok", { page: 0, size: 25 })).toMatchObject({ kind: "problem", problem: { status: 403, code: "forbidden" } });
  });
});

describe("the detail", () => {
  it("GETs the plan by its id and reads its items", async () => {
    route("get", `${BASE}/${ID}`, chargePlanDetailRecord(chargePlanDetail({ items: [chargePlanItem()] })));

    const result = await getChargePlan("tok", ID);

    expect(result.kind === "ok" ? result.data.items.length : result.kind).toBe(1);
    expect(requests()[0]?.method).toBe("GET");
    expect(requests()[0]?.headers.get("Authorization")).toBe("Bearer tok");
  });

  it("never lets an id change the path", async () => {
    route("get", `${BASE}/*`, chargePlanDetailRecord(chargePlanDetail()));

    await getChargePlan("tok", "a/b?c");

    expect(requests()[0]?.path).toBe(`${BASE}/a%2Fb%3Fc`);
  });
});

describe("the writes", () => {
  it("POSTs the plan with its body and reads the 201", async () => {
    route("post", BASE, chargePlanRecord(chargePlan(), 201));
    const body = { academicYearId: ID, name: "Primaria 2027", targetScope: "GRADE", targetRefId: ITEM, frequency: "MONTHLY", dueDayOfMonth: 5 } as const;

    const result = await createChargePlan("tok", body);

    expect(result.kind === "ok" ? result.status : result.kind).toBe(201);
    expect(requests()[0]?.method).toBe("POST");
    expect(JSON.parse(requests()[0]?.body ?? "{}")).toEqual(body);
    expect(requests()[0]?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("PUTs to its own id with the version the plan was read at and reads the 200", async () => {
    route("put", `${BASE}/${ID}`, chargePlanRecord(chargePlan({ status: "ACTIVE" })));

    const result = await updateChargePlan("tok", ID, { version: 2, name: "Primaria 2027", frequency: "MONTHLY", dueDayOfMonth: 5, status: "ACTIVE" });

    expect(result.kind === "ok" ? result.status : result.kind).toBe(200);
    expect(requests()[0]?.method).toBe("PUT");
    expect(JSON.parse(requests()[0]?.body ?? "{}")).toEqual({ version: 2, name: "Primaria 2027", frequency: "MONTHLY", dueDayOfMonth: 5, status: "ACTIVE" });
  });

  it("POSTs an item to the items of its plan and reads the 201", async () => {
    route("post", `${BASE}/${ID}/items`, chargePlanItemRecord(chargePlanItem()));

    const result = await addChargePlanItem("tok", ID, { paymentConceptId: ITEM, periodsCount: 10, firstPeriodOn: "2027-02-01" });

    expect(result.kind === "ok" ? result.status : result.kind).toBe(201);
    expect(requests()[0]?.method).toBe("POST");
    expect(JSON.parse(requests()[0]?.body ?? "{}")).toEqual({ paymentConceptId: ITEM, periodsCount: 10, firstPeriodOn: "2027-02-01" });
  });

  it("DELETEs an item by its own path and reads the 204 with no body", async () => {
    route("delete", `${BASE}/${ID}/items/${ITEM}`, noContent());

    const result = await removeChargePlanItem("tok", ID, ITEM);

    expect(result.kind === "ok" ? result.status : result.kind).toBe(204);
    expect(requests()[0]?.method).toBe("DELETE");
    expect(requests()[0]?.body).toBe("");
    expect(requests()[0]?.headers.get("Authorization")).toBe("Bearer tok");
  });

  it.each(["plan-not-editable", "plan-without-items", "duplicate-plan-item", "stale-version"] as const)(
    "answers %s as a problem with its code",
    async (code) => {
      route("put", `${BASE}/${ID}`, problem(code, 409));

      expect(await updateChargePlan("tok", ID, { version: 0, name: "x", frequency: "MONTHLY", dueDayOfMonth: 5, status: "ACTIVE" })).toMatchObject({
        kind: "problem",
        problem: { status: 409, code },
      });
    },
  );
});

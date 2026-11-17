import { describe, expect, it } from "vitest";
import {
  accrualPeriod,
  accrualPeriodsRecord,
  accrualPreview,
  accrualPreviewRecord,
  accrualRun,
  accrualRunDetailRecord,
  accrualRunItem,
  accrualRunPage,
  accrualRunRecord,
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
import {
  addChargePlanItem,
  createChargePlan,
  getAccrualRun,
  getChargePlan,
  listAccrualPeriods,
  listAccrualRuns,
  listChargePlans,
  previewAccrual,
  removeChargePlanItem,
  requestAccrualRun,
  updateChargePlan,
} from "./charges-operations.ts";

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

// The accrual engine: the periods, the preview, the request of a run (the one operation here that writes the ledger, so
// the only one that sends an Idempotency-Key) and the reading of runs.
describe("the accrual operations", () => {
  const PLAN = `${BASE}/${ID}`;
  const RUNS = "/api/v1/charges/accrual-runs";
  const KEY = "5d0b6a82-6f7e-4e6c-8b7f-1b0a3f2f7c11";
  const DIGEST = "c".repeat(64);

  it("reads the periods of a plan with the bearer token, and a null last run reads as none", async () => {
    route("get", `${PLAN}/accrual-periods`, accrualPeriodsRecord([accrualPeriod()]));

    const result = await listAccrualPeriods("tok-1", ID);

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(sent?.method).toBe("GET");
    expect(sent?.headers.get("Authorization")).toBe("Bearer tok-1");
    expect(sent?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("accepts the null the server writes for an absent last run", async () => {
    route("get", `${PLAN}/accrual-periods`, {
      status: 200,
      contentType: "application/json",
      body: [{ ...accrualPeriod(), lastRun: null }],
    });

    const result = await listAccrualPeriods("tok", ID);

    expect(result.kind).toBe("ok");
    if (result.kind === "ok") expect(result.data[0]?.lastRun).toBeUndefined();
  });

  it("previews a period with the period in the body and no Idempotency-Key, and keeps the amounts as the strings the server sent", async () => {
    route("post", `${PLAN}/accrual-preview`, accrualPreviewRecord(accrualPreview()));

    const result = await previewAccrual("tok", ID, { periodStart: "2027-02-01" });

    expect(result.kind).toBe("ok");
    if (result.kind === "ok") {
      expect(result.data.totals[0]?.total).toEqual({ amount: "3450.00", currency: "HNL" });
      expect(typeof result.data.totals[0]?.total.amount).toBe("string");
    }
    const [sent] = requests();
    expect(JSON.parse(sent?.body ?? "{}")).toEqual({ periodStart: "2027-02-01" });
    expect(sent?.headers.has("Idempotency-Key")).toBe(false);
  });

  it("accepts the nulls of an item in error (no amounts) in the preview", async () => {
    const body = { ...accrualPreview(), items: [{ studentId: ID, paymentConceptId: ITEM, outcome: "ERROR", errorCode: "NO_PRICE", base: null, tax: null, total: null, existingChargeId: null }] };
    route("post", `${PLAN}/accrual-preview`, { status: 200, contentType: "application/json", body });

    const result = await previewAccrual("tok", ID, { periodStart: "2027-02-01" });

    expect(result.kind).toBe("ok");
    if (result.kind === "ok") expect(result.data.items[0]?.base).toBeUndefined();
  });

  it("requests a run with the Idempotency-Key in the header, the period and the digest in the body, and the 202 answer", async () => {
    route("post", `${PLAN}/accrual-runs`, accrualRunRecord(accrualRun({ status: "QUEUED" })));

    const result = await requestAccrualRun("tok-2", ID, { periodStart: "2027-02-01", previewDigest: DIGEST }, KEY);

    expect(result.kind).toBe("ok");
    if (result.kind === "ok") {
      expect(result.status).toBe(202);
      expect(result.data.status).toBe("QUEUED");
    }
    const [sent] = requests();
    expect(sent?.method).toBe("POST");
    expect(sent?.headers.get("Idempotency-Key")).toBe(KEY);
    expect(sent?.headers.get("Authorization")).toBe("Bearer tok-2");
    expect(JSON.parse(sent?.body ?? "{}")).toEqual({ periodStart: "2027-02-01", previewDigest: DIGEST });
  });

  it("sends the same Idempotency-Key when the same intent is sent again", async () => {
    route("post", `${PLAN}/accrual-runs`, "network-error", accrualRunRecord(accrualRun({ status: "QUEUED" })));

    const first = await requestAccrualRun("tok", ID, { periodStart: "2027-02-01", previewDigest: DIGEST }, KEY);
    const second = await requestAccrualRun("tok", ID, { periodStart: "2027-02-01", previewDigest: DIGEST }, KEY);

    expect(first.kind).toBe("network");
    expect(second.kind).toBe("ok");
    expect(requests().map((sent) => sent.headers.get("Idempotency-Key"))).toEqual([KEY, KEY]);
  });

  it("gives the 409 of a run in progress as a problem", async () => {
    route("post", `${PLAN}/accrual-runs`, problem("accrual-run-in-progress", 409));

    const result = await requestAccrualRun("tok", ID, { periodStart: "2027-02-01" }, KEY);

    expect(result.kind === "problem" && result.problem.code).toBe("accrual-run-in-progress");
  });

  it("lists runs with page, size and the plan as query parameters, and no sort", async () => {
    route("get", RUNS, accrualRunPage([accrualRun()]));

    const result = await listAccrualRuns("tok", { page: 1, size: 25, planId: ID });

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(Object.fromEntries(new URLSearchParams(sent?.search))).toEqual({ page: "1", size: "25", planId: ID });
  });

  it("reads one run with a page of items", async () => {
    const run = accrualRun({ id: ITEM });
    route("get", `${RUNS}/${ITEM}`, accrualRunDetailRecord(run, [accrualRunItem()]));

    const result = await getAccrualRun("tok", ITEM, { page: 2, size: 50 });

    expect(result.kind).toBe("ok");
    const [sent] = requests();
    expect(sent?.path).toBe(`${RUNS}/${ITEM}`);
    expect(Object.fromEntries(new URLSearchParams(sent?.search))).toEqual({ page: "2", size: "50" });
  });

  it("is the unexpected-response result when an answer does not meet the contract", async () => {
    route("get", `${PLAN}/accrual-periods`, { status: 200, contentType: "application/json", body: [{ periodStart: "soon" }] });

    const result = await listAccrualPeriods("tok", ID);

    expect(result.kind).toBe("unexpected-response");
  });
});

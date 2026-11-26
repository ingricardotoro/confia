import { describe, expect, it } from "vitest";
import { runId, STUDENTS_IN_SCOPE } from "./accrual-model.ts";
import { createCatalogModel, DEFAULT_CATALOG } from "./catalog-model.ts";
import { conceptId } from "./catalog-seed.ts";
import { createChargesModel, DEFAULT_CHARGES, FAILING_READS, type ChargesModel } from "./charges-model.ts";
import { PLAN_IDS } from "./charges-seed.ts";
import { createOrganizationModel, DEFAULT_ORGANIZATION } from "./organization-model.ts";
import { createStudentsModel, DEFAULT_STUDENTS } from "./students-model.ts";

// The simulated accrual engine over the seeded plans, students and concepts: the periods a plan lists, the preview with its digest and its
// blockers, the request with its Idempotency-Key (replay, mismatch, in progress), the run that goes QUEUED, RUNNING and terminal over
// three reads, the digest that moves, the list and the detail, and the scenarios. The clock is fixed.
const NOW = () => new Date("2026-11-11T18:00:00Z");
const PLANS = "/api/v1/charges/plans";
const RUNS = "/api/v1/charges/accrual-runs";
const ACTIVE = PLAN_IDS.active;
const FRESH = "2026-05-01";

function model(override: Partial<typeof DEFAULT_CHARGES> = {}): ChargesModel {
  return createChargesModel(
    { ...DEFAULT_CHARGES, ...override },
    { organization: createOrganizationModel(DEFAULT_ORGANIZATION), catalog: createCatalogModel(DEFAULT_CATALOG), students: createStudentsModel(DEFAULT_STUDENTS) },
    NOW,
  );
}

const call = (target: ChargesModel, method: string, path: string, body?: unknown, query = "", key?: string) =>
  target.handle({ method, path, query: new URLSearchParams(query), body, ...(key === undefined ? {} : { headers: { "idempotency-key": key } }) });
const typeOf = (response: { body?: unknown } | undefined): string => ((response?.body as { type?: string } | undefined)?.type ?? "").split("/").pop() ?? "";
type Wire = Record<string, unknown>;
const bodyOf = (response: { body?: unknown } | undefined): Wire => response?.body as Wire;
const request = (target: ChargesModel, key: string | undefined, period = FRESH, previewDigest?: string, plan = ACTIVE) =>
  call(target, "POST", `${PLANS}/${plan}/accrual-runs`, { periodStart: period, ...(previewDigest === undefined ? {} : { previewDigest }) }, "", key);
const preview = (target: ChargesModel, period = FRESH, plan = ACTIVE) => call(target, "POST", `${PLANS}/${plan}/accrual-preview`, { periodStart: period });
const read = (target: ChargesModel, id: string, query = "") => call(target, "GET", `${RUNS}/${id}`, undefined, query);

describe("the periods of a plan", () => {
  it("lists the months its items accrue, with the due day and the concepts of each, and the last run when there is one", () => {
    const answer = call(model(), "GET", `${PLANS}/${ACTIVE}/accrual-periods`);

    const periods = answer?.body as Wire[];
    expect(answer?.status).toBe(200);
    expect(periods.map((period) => period.label)).toEqual(["2026-01", "2026-02", "2026-03", "2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09", "2026-10", "2026-11"]);
    expect(periods[0]).toMatchObject({ periodStart: "2026-01-01", dueOn: "2026-01-05", paymentConceptIds: [conceptId(2)] });
    expect((periods[0]?.lastRun as Wire).status).toBe("COMPLETED");
    expect(periods[4]).toMatchObject({ periodStart: FRESH, paymentConceptIds: [conceptId(1)], lastRun: null });
  });

  it("lists none for a plan with no items, and answers 404 for a plan that does not exist", () => {
    expect(call(model(), "GET", `${PLANS}/${PLAN_IDS.draftEmpty}/accrual-periods`)?.body).toEqual([]);
    expect(typeOf(call(model(), "GET", `${PLANS}/00000000-0000-4000-8000-000000000001/accrual-periods`))).toBe("resource-not-found");
  });
});

describe("the preview", () => {
  it("counts what would be created, writes the amounts as strings of two decimals, and carries a digest", () => {
    const body = bodyOf(preview(model()));

    expect(body).toMatchObject({ planId: ACTIVE, periodStart: FRESH, studentsEvaluated: STUDENTS_IN_SCOPE, toCreate: 9, errors: 3, alreadyAccrued: 0, blockers: [] });
    expect(body.errorsByCode).toEqual({ NO_PRICE: 1, AMBIGUOUS_PRICE: 1, TAX_RATE_MISSING: 1 });
    expect(body.totals).toEqual([
      { currency: "HNL", charges: 9, base: { amount: "13500.00", currency: "HNL" }, benefitAmount: { amount: "0.00", currency: "HNL" }, netBase: { amount: "13500.00", currency: "HNL" }, tax: { amount: "2025.00", currency: "HNL" }, total: { amount: "15525.00", currency: "HNL" } },
    ]);
    expect(body.previewDigest).toMatch(/^[0-9a-f]{64}$/);
    const items = body.items as Wire[];
    expect(items.find((item) => item.errorCode === "NO_PRICE")).toMatchObject({ outcome: "ERROR", base: null, tax: null, total: null });
    expect(items[0]).toMatchObject({ outcome: "CREATED", base: { amount: "1500.00", currency: "HNL" }, tax: { amount: "225.00", currency: "HNL" }, total: { amount: "1725.00", currency: "HNL" } });
  });

  it("shows a period that is already accrued as nothing to create", () => {
    const body = bodyOf(preview(model(), "2026-01-01"));

    expect(body).toMatchObject({ toCreate: 0, alreadyAccrued: STUDENTS_IN_SCOPE, errors: 0, totals: [] });
  });

  it("names the blockers: an active plan only, and the posting accounts of the blocked scenario", () => {
    expect(bodyOf(preview(model({ accrual: "blocked" }))).blockers).toEqual(["posting-accounts-incomplete"]);
    expect(bodyOf(preview(model(), "2026-02-01", PLAN_IDS.draftWithItem)).blockers).toEqual(["plan-not-active"]);
  });

  it("refuses a period the plan does not list, and a body without one, with 400", () => {
    expect(typeOf(preview(model(), "2030-02-01"))).toBe("validation-failed");
    expect(typeOf(call(model(), "POST", `${PLANS}/${ACTIVE}/accrual-preview`, {}))).toBe("validation-failed");
  });

  it("refuses with 403 in the forbidden scenario, and fails once in the preview-fails-first scenario", () => {
    expect(typeOf(preview(model({ accrual: "forbidden" })))).toBe("forbidden");
    const flaky = model({ accrual: "preview-fails-first" });
    expect(preview(flaky)?.status).toBe(500);
    expect(preview(flaky)?.status).toBe(200);
  });
});

describe("the request of a run", () => {
  it("answers 202 with the run queued, and keeps the digest apart from what the response shows", () => {
    const answer = request(model(), "key-1", FRESH, "a".repeat(64));

    expect(answer?.status).toBe(202);
    expect(bodyOf(answer)).toMatchObject({ planId: ACTIVE, periodStart: FRESH, status: "QUEUED", previewChecked: true, startedAt: null, finishedAt: null, failureCode: null, chargesCreated: 0 });
    expect(bodyOf(answer)).not.toHaveProperty("previewDigest");
  });

  it("needs the Idempotency-Key: without one it answers 400 and creates nothing", () => {
    const target = model();

    expect(typeOf(request(target, undefined))).toBe("idempotency-key-missing");
    expect(target.accrual().runs()).toHaveLength(4);
  });

  it("answers the same run to the same key and the same body, and 422 to the same key with another body", () => {
    const target = model();
    const first = bodyOf(request(target, "key-1"));

    expect(bodyOf(request(target, "key-1")).id).toBe(first.id);
    expect(typeOf(request(target, "key-1", "2026-06-01"))).toBe("idempotency-payload-mismatch");
    expect(target.accrual().runs()).toHaveLength(5);
  });

  it("answers 409 to another key while a run of the same plan and period is queued or running, and in the in-progress scenario", () => {
    const target = model();
    request(target, "key-1");

    expect(typeOf(request(target, "key-2"))).toBe("accrual-run-in-progress");
    expect(typeOf(request(model({ accrual: "in-progress" }), "key-1"))).toBe("accrual-run-in-progress");
  });

  it("refuses a period the plan does not list with 400, and a plan that is not active, and 403 in the forbidden scenario", () => {
    expect(typeOf(request(model(), "key-1", "2030-02-01"))).toBe("validation-failed");
    expect(typeOf(request(model(), "key-1", "2026-02-01", undefined, PLAN_IDS.draftWithItem))).toBe("validation-failed");
    expect(typeOf(request(model({ accrual: "forbidden" }), "key-1"))).toBe("forbidden");
  });
});

describe("the progress of a run", () => {
  it("goes QUEUED, RUNNING and then terminal over three reads, and stays terminal", () => {
    const target = model();
    const id = bodyOf(request(target, "key-1", FRESH, bodyOf(preview(target)).previewDigest as string)).id as string;

    const states = [1, 2, 3, 4].map(() => (bodyOf(read(target, id)).run as Wire).status);

    expect(states).toEqual(["QUEUED", "RUNNING", "COMPLETED_WITH_ERRORS", "COMPLETED_WITH_ERRORS"]);
    const done = bodyOf(read(target, id));
    expect(done.run).toMatchObject({ studentsEvaluated: STUDENTS_IN_SCOPE, chargesCreated: 9, chargesFailed: 3, chargesExisting: 0, previewChecked: true });
    expect((done.run as Wire).finishedAt).not.toBeNull();
    expect(done.totalItems).toBe(STUDENTS_IN_SCOPE);
  });

  it("repeats nothing: running the same period again finds the charges already there", () => {
    const target = model();
    const first = bodyOf(request(target, "key-1")).id as string;
    for (let n = 0; n < 3; n += 1) read(target, first);

    const again = bodyOf(request(target, "key-2")).id as string;
    for (let n = 0; n < 3; n += 1) read(target, again);

    expect(bodyOf(read(target, again)).run).toMatchObject({ chargesCreated: 0, chargesExisting: 9, chargesFailed: 3 });
  });

  it("is rejected with preview-outdated, and writes nothing, when the calculation moved after the preview", () => {
    const target = model({ accrual: "digest-changed" });
    const id = bodyOf(request(target, "key-1", FRESH, bodyOf(preview(target)).previewDigest as string)).id as string;

    for (let n = 0; n < 3; n += 1) read(target, id);

    expect(bodyOf(read(target, id)).run).toMatchObject({ status: "REJECTED", failureCode: "preview-outdated", chargesCreated: 0, studentsEvaluated: 0 });
    expect(bodyOf(read(target, id)).items).toEqual([]);
  });

  it("serves the items by page, with their error code, and nulls where the server writes them", () => {
    const target = model();
    const id = bodyOf(request(target, "key-1")).id as string;
    for (let n = 0; n < 3; n += 1) read(target, id);

    const second = bodyOf(read(target, id, "page=1&size=5"));

    expect(second).toMatchObject({ page: 1, size: 5, totalItems: STUDENTS_IN_SCOPE });
    expect(second.items as Wire[]).toHaveLength(5);
    const all = bodyOf(read(target, id, "page=0&size=50")).items as Wire[];
    expect(all.find((item) => item.errorCode === "AMBIGUOUS_PRICE")).toMatchObject({ outcome: "ERROR", chargeId: null });
  });

  it("answers 404 for a run that does not exist, and 400 for a page out of range", () => {
    expect(typeOf(read(model(), "00000000-0000-4000-8000-000000000001"))).toBe("resource-not-found");
    expect(typeOf(read(model(), runId(1), "page=-1"))).toBe("validation-failed");
    expect(typeOf(read(model(), runId(1), "size=500"))).toBe("validation-failed");
  });
});

describe("the list of runs", () => {
  it("serves the four seeded runs, newest first, each state a person meets", () => {
    const body = bodyOf(call(model(), "GET", RUNS, undefined, "page=0&size=25"));

    expect(body.total).toBe(4);
    expect((body.items as Wire[]).map((run) => run.status)).toEqual(["FAILED", "REJECTED", "COMPLETED_WITH_ERRORS", "COMPLETED"]);
    expect((body.items as Wire[])[1]).toMatchObject({ failureCode: "preview-outdated" });
  });

  it("filters by plan and pages, and a new run goes first", () => {
    const target = model();
    request(target, "key-1");

    const first = bodyOf(call(target, "GET", RUNS, undefined, `planId=${ACTIVE}&page=0&size=2`));
    const other = bodyOf(call(target, "GET", RUNS, undefined, `planId=${PLAN_IDS.archived}`));

    expect(first.total).toBe(5);
    expect((first.items as Wire[])[0]).toMatchObject({ status: "QUEUED", periodStart: FRESH });
    expect(first.items as Wire[]).toHaveLength(2);
    expect(other).toMatchObject({ total: 0, items: [] });
  });

  it("is empty in the empty scenario, and fails its first reads in the failing-first one", () => {
    expect(bodyOf(call(model({ accrual: "empty" }), "GET", RUNS)).total).toBe(0);
    const flaky = model({ reads: "failing-first" });
    const statuses = Array.from({ length: FAILING_READS + 1 }, () => call(flaky, "GET", RUNS)?.status);
    expect(statuses).toEqual([500, 500, 500, 200]);
  });

  it("refuses a page or a plan that is not valid with 400", () => {
    expect(typeOf(call(model(), "GET", RUNS, undefined, "page=x"))).toBe("validation-failed");
    expect(typeOf(call(model(), "GET", RUNS, undefined, "planId=nope"))).toBe("validation-failed");
  });

  it("leaves the plans routes as they were", () => {
    expect(call(model(), "GET", PLANS, undefined, "page=0&size=5")?.status).toBe(200);
    expect(call(model(), "PUT", RUNS)).toBeUndefined();
  });
});

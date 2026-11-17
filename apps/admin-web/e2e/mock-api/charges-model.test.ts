import { describe, expect, it } from "vitest";
import { createCatalogModel, DEFAULT_CATALOG } from "./catalog-model.ts";
import { conceptId } from "./catalog-seed.ts";
import { createChargesModel, DEFAULT_CHARGES, FAILING_READS, type ChargesModel } from "./charges-model.ts";
import { PLAN_IDS, planItemId, TOTAL } from "./charges-seed.ts";
import { createOrganizationModel, DEFAULT_ORGANIZATION } from "./organization-model.ts";
import { GRADE_IDS, LAST_YEAR, MODALITY_IDS, SECTION_IDS, yearId } from "./organization-seed.ts";

// The simulated charge plans over the seeded academic structure and catalog: the shapes it answers, and the few rules a
// journey needs (the states of a plan, the items only in a draft, the references, the refusals). The clock is fixed.
const NOW = () => new Date("2026-11-11T18:00:00Z");

function model(override: Partial<typeof DEFAULT_CHARGES> = {}): ChargesModel {
  return createChargesModel(
    { ...DEFAULT_CHARGES, ...override },
    { organization: createOrganizationModel(DEFAULT_ORGANIZATION), catalog: createCatalogModel(DEFAULT_CATALOG) },
    NOW,
  );
}

const call = (target: ChargesModel, method: string, path: string, body?: unknown, query = "") =>
  target.handle({ method, path: `/api/v1/charges/plans${path}`, query: new URLSearchParams(query), body });
const typeOf = (response: { body?: unknown } | undefined): string => ((response?.body as { type?: string } | undefined)?.type ?? "").split("/").pop() ?? "";
const fieldsOf = (response: { body?: unknown } | undefined) => (response?.body as { errors: { field: string; reason: string }[] }).errors;

const NEW_PLAN = {
  academicYearId: yearId(LAST_YEAR),
  name: "Primaria 2027",
  targetScope: "GRADE",
  targetRefId: GRADE_IDS.third,
  frequency: "MONTHLY",
  dueDayOfMonth: 5,
} as const;
const COLEGIATURA = conceptId(1);
const MATRICULA = conceptId(2);
const INACTIVE_CONCEPT = conceptId(6);

describe("the list", () => {
  it("serves the plans by name with the total of every page", () => {
    const answer = call(model(), "GET", "", undefined, "page=0&size=25");

    const body = answer?.body as { items: { name: string }[]; total: number };
    expect(answer?.status).toBe(200);
    expect(body.total).toBe(TOTAL);
    expect(body.items).toHaveLength(25);
    expect(body.items.map((plan) => plan.name)).toEqual([...body.items.map((plan) => plan.name)].sort((a, b) => a.localeCompare(b)));
  });

  it("filters by year, scope and status, and refuses a sort, a scope or a status it does not know", () => {
    const target = model();
    const total = (query: string) => (call(target, "GET", "", undefined, query)?.body as { total: number }).total;

    expect(total("status=ACTIVE")).toBe(1);
    expect(total("status=ARCHIVED")).toBe(1);
    expect(total("targetScope=SECTION")).toBe(1);
    expect(total(`academicYearId=${yearId(1997)}`)).toBe(0);
    expect(call(target, "GET", "", undefined, "sort=code,asc")?.status).toBe(400);
    expect(call(target, "GET", "", undefined, "targetScope=SCHOOL")?.status).toBe(400);
    expect(call(target, "GET", "", undefined, "status=GONE")?.status).toBe(400);
  });

  it("answers empty for an empty scenario and fails the first reads of a failing one", () => {
    expect((call(model({ seed: "empty" }), "GET", "")?.body as { total: number }).total).toBe(0);
    const failing = model({ reads: "failing-first" });
    const statuses = Array.from({ length: FAILING_READS + 1 }, () => call(failing, "GET", "")?.status);
    expect(statuses).toEqual([500, 500, 500, 200]);
  });

  it("serves the detail with its items, and a 404 for a plan that is not there", () => {
    const target = model();

    const answer = call(target, "GET", `/${PLAN_IDS.active}`);
    expect(answer?.status).toBe(200);
    expect((answer?.body as { items: unknown[] }).items).toHaveLength(2);
    expect(call(target, "GET", "/00000000-0000-4000-8000-00000000dead")?.status).toBe(404);
  });

  it("does not answer a path or a method that is not one of its operations", () => {
    const target = model();

    expect(call(target, "DELETE", "")).toBeUndefined();
    expect(call(target, "DELETE", `/${PLAN_IDS.active}`)).toBeUndefined();
    expect(call(target, "GET", `/${PLAN_IDS.active}/items`)).toBeUndefined();
    expect(target.handle({ method: "GET", path: "/api/v1/charges/other", query: new URLSearchParams(), body: undefined })).toBeUndefined();
  });
});

describe("a new plan", () => {
  it("is born a draft at version 0, and is listed afterwards", () => {
    const target = model({ seed: "empty" });

    const answer = call(target, "POST", "", NEW_PLAN);

    expect(answer?.status).toBe(201);
    expect(answer?.body).toMatchObject({ name: "Primaria 2027", status: "DRAFT", version: 0, dueDayOfMonth: 5 });
    expect(target.plans()).toHaveLength(1);
  });

  it("refuses a due day out of 1 to 28, a blank name and a body that does not meet the contract, naming the field", () => {
    const target = model({ seed: "empty" });

    for (const dueDayOfMonth of [0, 29, 31]) expect(call(target, "POST", "", { ...NEW_PLAN, dueDayOfMonth })?.status).toBe(400);
    expect(fieldsOf(call(target, "POST", "", { ...NEW_PLAN, name: "   " }))).toEqual([{ field: "name", reason: "required" }]);
    expect(fieldsOf(call(target, "POST", "", { ...NEW_PLAN, frequency: "WEEKLY" }))[0]?.field).toBe("frequency");
    expect(target.plans()).toHaveLength(0);
  });

  it("answers 404 for a year or a target that is not of the institution, for each scope", () => {
    const target = model({ seed: "empty" });
    const other = "00000000-0000-4000-8000-00000000dead";

    expect(call(target, "POST", "", { ...NEW_PLAN, academicYearId: other })?.status).toBe(404);
    expect(call(target, "POST", "", { ...NEW_PLAN, targetRefId: other })?.status).toBe(404);
    // A grade named as a modality is not a modality.
    expect(call(target, "POST", "", { ...NEW_PLAN, targetScope: "MODALITY" })?.status).toBe(404);
    expect(call(target, "POST", "", { ...NEW_PLAN, targetScope: "MODALITY", targetRefId: MODALITY_IDS.primary })?.status).toBe(201);
    expect(call(target, "POST", "", { ...NEW_PLAN, targetScope: "SECTION", targetRefId: SECTION_IDS.firstA })?.status).toBe(201);
  });
});

describe("the edit and the states", () => {
  const edit = (overrides: Record<string, unknown> = {}, from = PLAN_IDS.draftWithItem) => {
    const target = model();
    const plan = target.plans().find((row) => row.id === from);
    const body = {
      version: plan?.version,
      name: plan?.name,
      frequency: plan?.frequency,
      dueDayOfMonth: plan?.dueDayOfMonth,
      status: plan?.status,
      ...overrides,
    };
    return { target, answer: call(target, "PUT", `/${from}`, body) };
  };

  it("changes the name, the frequency and the due day of a draft and moves its version on", () => {
    const { answer } = edit({ name: "Segundo 2026", frequency: "ANNUAL", dueDayOfMonth: 12 });

    expect(answer?.status).toBe(200);
    expect(answer?.body).toMatchObject({ name: "Segundo 2026", frequency: "ANNUAL", dueDayOfMonth: 12, version: 2, status: "DRAFT" });
  });

  it("activates a draft that has items and freezes it", () => {
    const { target, answer } = edit({ status: "ACTIVE" });

    expect(answer?.body).toMatchObject({ status: "ACTIVE" });
    const frozen = call(target, "PUT", `/${PLAN_IDS.draftWithItem}`, { version: 2, name: "x", frequency: "ANNUAL", dueDayOfMonth: 5, status: "ACTIVE" });
    expect(typeOf(frozen)).toBe("plan-not-editable");
    const renamed = call(target, "PUT", `/${PLAN_IDS.draftWithItem}`, { version: 2, name: "Otro nombre", frequency: "MONTHLY", dueDayOfMonth: 5, status: "ACTIVE" });
    expect(renamed?.status).toBe(200);
  });

  it("refuses to activate a draft with no items", () => {
    const { answer } = edit({ status: "ACTIVE" }, PLAN_IDS.draftEmpty);

    expect(answer?.status).toBe(409);
    expect(typeOf(answer)).toBe("plan-without-items");
  });

  it("archives an active plan, and refuses every change of an archived one and a draft that is archived directly", () => {
    const archived = edit({ status: "ARCHIVED" }, PLAN_IDS.active);
    expect(archived.answer?.body).toMatchObject({ status: "ARCHIVED" });

    expect(typeOf(edit({ name: "Otro" }, PLAN_IDS.archived).answer)).toBe("plan-not-editable");
    expect(typeOf(edit({ status: "ACTIVE" }, PLAN_IDS.archived).answer)).toBe("plan-not-editable");
    expect(typeOf(edit({ status: "ARCHIVED" }).answer)).toBe("plan-not-editable");
  });

  it("refuses a version that is not the one the plan holds, and an edit it does not recognize", () => {
    expect(typeOf(edit({ version: 99 }).answer)).toBe("stale-version");
    expect(edit({ dueDayOfMonth: 29 }).answer?.status).toBe(400);
    expect(call(model(), "PUT", "/00000000-0000-4000-8000-00000000dead", {})?.status).toBe(404);
  });

  it("serves the stale version once in the stale-first scenario, and then lets the reread version through", () => {
    const target = model({ writes: "stale-first" });
    const plan = target.plans().find((row) => row.id === PLAN_IDS.draftWithItem);
    const body = { version: plan?.version, name: "Nuevo nombre", frequency: "MONTHLY", dueDayOfMonth: 5, status: "DRAFT" };

    expect(typeOf(call(target, "PUT", `/${PLAN_IDS.draftWithItem}`, body))).toBe("stale-version");
    expect(call(target, "PUT", `/${PLAN_IDS.draftWithItem}`, { ...body, version: plan?.version })?.status).toBe(200);
  });
});

describe("the items", () => {
  const ITEM = { paymentConceptId: MATRICULA, periodsCount: 1, firstPeriodOn: "2027-02-01" };

  it("adds an item to a draft and keeps it with the plan", () => {
    const target = model();

    const answer = call(target, "POST", `/${PLAN_IDS.draftEmpty}/items`, ITEM);

    expect(answer?.status).toBe(201);
    expect(answer?.body).toMatchObject({ planId: PLAN_IDS.draftEmpty, ...ITEM });
    expect((call(target, "GET", `/${PLAN_IDS.draftEmpty}`)?.body as { items: unknown[] }).items).toHaveLength(1);
  });

  it("refuses periods out of 1 to 36 and a first day that is not the first of a month", () => {
    const target = model();
    const add = (body: object) => call(target, "POST", `/${PLAN_IDS.draftEmpty}/items`, { ...ITEM, ...body });

    expect(add({ periodsCount: 0 })?.status).toBe(400);
    expect(add({ periodsCount: 37 })?.status).toBe(400);
    expect(add({ firstPeriodOn: "2027-02-15" })?.status).toBe(400);
    expect(add({ firstPeriodOn: "1999-12-01" })?.status).toBe(400);
    expect(target.items().filter((item) => item.planId === PLAN_IDS.draftEmpty)).toHaveLength(0);
  });

  it("refuses a repeated concept, an inactive one and one that does not exist", () => {
    const target = model();

    expect(typeOf(call(target, "POST", `/${PLAN_IDS.draftWithItem}/items`, { ...ITEM, paymentConceptId: COLEGIATURA }))).toBe("duplicate-plan-item");
    expect(typeOf(call(target, "POST", `/${PLAN_IDS.draftEmpty}/items`, { ...ITEM, paymentConceptId: INACTIVE_CONCEPT }))).toBe("inactive-reference");
    expect(call(target, "POST", `/${PLAN_IDS.draftEmpty}/items`, { ...ITEM, paymentConceptId: "00000000-0000-4000-8000-00000000dead" })?.status).toBe(404);
  });

  it("adds and removes items only while the plan is a draft", () => {
    const target = model();

    expect(typeOf(call(target, "POST", `/${PLAN_IDS.active}/items`, { ...ITEM, paymentConceptId: conceptId(3) }))).toBe("plan-not-editable");
    expect(typeOf(call(target, "DELETE", `/${PLAN_IDS.active}/items/${planItemId(1)}`))).toBe("plan-not-editable");
    expect(typeOf(call(target, "POST", `/${PLAN_IDS.archived}/items`, { ...ITEM, paymentConceptId: conceptId(3) }))).toBe("plan-not-editable");
  });

  it("removes an item of a draft with a 204, and answers 404 for one that is not there", () => {
    const target = model();

    const removed = call(target, "DELETE", `/${PLAN_IDS.draftWithItem}/items/${planItemId(4)}`);

    expect(removed?.status).toBe(204);
    expect(removed?.body).toBeUndefined();
    expect(target.items().some((item) => item.id === planItemId(4))).toBe(false);
    expect(call(target, "DELETE", `/${PLAN_IDS.draftWithItem}/items/${planItemId(4)}`)?.status).toBe(404);
    // An item of another plan is not an item of this one.
    expect(call(target, "DELETE", `/${PLAN_IDS.draftEmpty}/items/${planItemId(1)}`)?.status).toBe(404);
  });
});

describe("a scenario that forbids writes", () => {
  it("answers 403 to every write, reads on, and changes nothing", () => {
    const target = model({ writes: "forbidden" });

    expect(call(target, "GET", "")?.status).toBe(200);
    expect(call(target, "POST", "", NEW_PLAN)?.status).toBe(403);
    expect(call(target, "PUT", `/${PLAN_IDS.draftEmpty}`, { version: 0, name: "x", frequency: "MONTHLY", dueDayOfMonth: 5, status: "DRAFT" })?.status).toBe(403);
    expect(call(target, "POST", `/${PLAN_IDS.draftEmpty}/items`, { paymentConceptId: MATRICULA, periodsCount: 1, firstPeriodOn: "2027-02-01" })?.status).toBe(403);
    expect(call(target, "DELETE", `/${PLAN_IDS.draftWithItem}/items/${planItemId(4)}`)?.status).toBe(403);
    expect(target.plans()).toHaveLength(TOTAL);
  });
});

import { describe, expect, it } from "vitest";
import { createLedgerModel, DEFAULT_LEDGER, FAILING_READS, POSTING_ACCOUNTS_PATH, type LedgerModel } from "./ledger-model.ts";
import { ACCOUNT_IDS, TOTAL } from "./ledger-seed.ts";

// The simulated chart of accounts and map of posting accounts: the shapes it answers, and the few rules a journey needs (the
// codes, the edit of the name and the state, the account the map still uses, the types the map takes, the map saved whole or
// not at all, the versions, the refusals). The clock is fixed.
const NOW = () => new Date("2026-11-17T18:00:00Z");

function model(override: Partial<typeof DEFAULT_LEDGER> = {}): LedgerModel {
  return createLedgerModel({ ...DEFAULT_LEDGER, ...override }, NOW);
}

const accounts = (target: LedgerModel, method: string, path = "", body?: unknown, query = "") =>
  target.handle({ method, path: `/api/v1/ledger/accounts${path}`, query: new URLSearchParams(query), body });
const map = (target: LedgerModel, method: string, body?: unknown) =>
  target.handle({ method, path: POSTING_ACCOUNTS_PATH, query: new URLSearchParams(), body });
const typeOf = (response: { body?: unknown } | undefined): string => ((response?.body as { type?: string } | undefined)?.type ?? "").split("/").pop() ?? "";
const fieldsOf = (response: { body?: unknown } | undefined) => (response?.body as { errors: { field: string; reason: string }[] }).errors;
const itemsOf = (response: { body?: unknown } | undefined) => (response?.body as { items: Record<string, unknown>[] }).items;

const NEW_ACCOUNT = { code: "ACT-100", name: "Cuenta nueva de prueba", type: "ASSET", holdsStudentBalances: false } as const;

describe("the list of accounts", () => {
  it("serves the twenty eight seeded accounts by code, with the total of every page", () => {
    const answer = accounts(model(), "GET", "", undefined, "page=0&size=25");

    const body = answer?.body as { items: { code: string }[]; total: number };
    expect(answer?.status).toBe(200);
    expect(body.total).toBe(TOTAL);
    expect(body.items).toHaveLength(25);
    expect(body.items.map((row) => row.code)).toEqual([...body.items.map((row) => row.code)].sort((a, b) => a.localeCompare(b)));
  });

  it("filters by type and state, orders by name, and refuses a sort, a type or a state it does not know", () => {
    const target = model();
    const total = (query: string) => (accounts(target, "GET", "", undefined, query)?.body as { total: number }).total;

    expect(total("type=INCOME")).toBe(4);
    expect(total("type=INCOME&active=true")).toBe(3);
    expect(total("active=false")).toBe(1);
    expect(total("type=EQUITY")).toBe(1);
    expect(accounts(target, "GET", "", undefined, "sort=createdAt,asc")?.status).toBe(400);
    expect(accounts(target, "GET", "", undefined, "type=income")?.status).toBe(400);
    expect(accounts(target, "GET", "", undefined, "active=maybe")?.status).toBe(400);
    const byName = itemsOf(accounts(target, "GET", "", undefined, "sort=name,desc&size=200")).map((row) => String(row["name"]).toLowerCase());
    expect(byName).toEqual([...byName].sort().reverse());
  });

  it("serves the empty scenario, and fails the first reads of the failing scenario and then recovers", () => {
    expect(accounts(model({ seed: "empty" }), "GET")?.body).toMatchObject({ total: 0, items: [] });
    const failing = model({ reads: "failing-first" });
    const answers = Array.from({ length: FAILING_READS + 1 }, () => accounts(failing, "GET")?.status);

    expect(answers).toEqual([500, 500, 500, 200]);
  });
});

describe("creating an account", () => {
  it("creates it active at version 0, and lists it", () => {
    const target = model();

    const created = accounts(target, "POST", "", NEW_ACCOUNT);

    expect(created?.status).toBe(201);
    expect(created?.body).toMatchObject({ ...NEW_ACCOUNT, active: true, version: 0 });
    expect((accounts(target, "GET", "", undefined, "size=200")?.body as { total: number }).total).toBe(TOTAL + 1);
  });

  it("refuses a repeated code whatever its capitals, and does not create it", () => {
    const target = model();

    const repeated = accounts(target, "POST", "", { ...NEW_ACCOUNT, code: "act-001" });

    expect([repeated?.status, typeOf(repeated)]).toEqual([409, "duplicate-account-code"]);
    expect(target.accounts()).toHaveLength(TOTAL);
  });

  it("refuses an empty, spaced or long code, a long or blank name, an unknown or lower case type, and the flag on a type that cannot carry it", () => {
    const target = model();
    const refused = (override: object) => accounts(target, "POST", "", { ...NEW_ACCOUNT, ...override });

    expect(refused({ code: "" })?.status).toBe(400);
    expect(fieldsOf(refused({ code: "AC T" }))).toEqual([{ field: "code", reason: "pattern" }]);
    expect(refused({ code: "A".repeat(31) })?.status).toBe(400);
    expect(fieldsOf(refused({ name: "   " }))).toEqual([{ field: "name", reason: "required" }]);
    expect(refused({ name: "N".repeat(121) })?.status).toBe(400);
    expect(refused({ type: "asset" })?.status).toBe(400);
    expect(refused({ type: "BOX" })?.status).toBe(400);
    expect(fieldsOf(refused({ type: "INCOME", holdsStudentBalances: true }))).toEqual([{ field: "holdsStudentBalances", reason: "not-allowed" }]);
    expect(target.accounts()).toHaveLength(TOTAL);
  });

  it("refuses every write with 403 and changes nothing when the person cannot write", () => {
    const target = model({ writes: "forbidden" });

    expect(accounts(target, "POST", "", NEW_ACCOUNT)?.status).toBe(403);
    expect(accounts(target, "PUT", `/${ACCOUNT_IDS.cash}`, { version: 0, name: "Otro", active: true })?.status).toBe(403);
    expect(map(target, "PUT", { entries: [{ purpose: "RECEIVABLE", ledgerAccountId: ACCOUNT_IDS.receivable }] })?.status).toBe(403);
    expect(target.accounts()).toHaveLength(TOTAL);
  });
});

describe("editing an account", () => {
  const edit = { version: 0, name: "Caja renombrada", active: true };

  it("changes the name and the state, moves the version and keeps the code, the type and the flag", () => {
    const target = model();

    const answer = accounts(target, "PUT", `/${ACCOUNT_IDS.cash}`, { ...edit, active: false });

    expect(answer?.status).toBe(200);
    expect(answer?.body).toMatchObject({ code: "ACT-002", type: "ASSET", holdsStudentBalances: false, name: "Caja renombrada", active: false, version: 1 });
  });

  it("refuses a body that brings the code, the type or anything else, a stale version, a missing version and an account that does not exist", () => {
    const target = model();
    const put = (id: string, body: unknown) => accounts(target, "PUT", `/${id}`, body);

    expect(fieldsOf(put(ACCOUNT_IDS.cash, { ...edit, code: "X" }))).toEqual([{ field: "code", reason: "not-allowed" }]);
    expect(fieldsOf(put(ACCOUNT_IDS.cash, { ...edit, type: "INCOME" }))).toEqual([{ field: "type", reason: "not-allowed" }]);
    expect(typeOf(put(ACCOUNT_IDS.cash, { ...edit, version: 4 }))).toBe("stale-version");
    expect(put(ACCOUNT_IDS.cash, { name: "Sin versión", active: true })?.status).toBe(400);
    expect(put(ACCOUNT_IDS.cash, { ...edit, name: "  " })?.status).toBe(400);
    expect(put("00000000-0000-4000-8000-0000000000ff", edit)?.status).toBe(404);
  });

  it("serves a stale version once in the stale scenario, and the next edit at the new version goes through", () => {
    const target = model({ writes: "stale-first" });

    const first = accounts(target, "PUT", `/${ACCOUNT_IDS.cash}`, edit);
    const second = accounts(target, "PUT", `/${ACCOUNT_IDS.cash}`, { ...edit, version: 1 });

    expect(typeOf(first)).toBe("stale-version");
    expect(second?.status).toBe(200);
  });

  it("does not deactivate an account the map points at, and does after the map points at another", () => {
    const target = model({ map: "complete" });
    const body = { version: 0, name: "Otros ingresos (ejemplo)", active: false };

    const refused = accounts(target, "PUT", `/${ACCOUNT_IDS.otherIncome}`, body);
    const moved = map(target, "PUT", { entries: [{ purpose: "REVENUE", ledgerAccountId: ACCOUNT_IDS.tuition, version: 0 }] });
    const allowed = accounts(target, "PUT", `/${ACCOUNT_IDS.otherIncome}`, body);

    expect([refused?.status, typeOf(refused)]).toEqual([409, "account-in-use"]);
    expect(moved?.status).toBe(200);
    expect(allowed?.status).toBe(200);
    // An account the map does not use is deactivated freely, and an account that is already inactive stays so.
    expect(accounts(target, "PUT", `/${ACCOUNT_IDS.cash}`, { version: 0, name: "Caja", active: false })?.status).toBe(200);
  });
});

describe("the map of posting accounts", () => {
  it("serves the three seeds: empty, with the two main accounts, and complete", () => {
    expect(itemsOf(map(model({ map: "empty" }), "GET"))).toHaveLength(0);
    expect(itemsOf(map(model({ map: "partial" }), "GET")).map((row) => row["purpose"])).toEqual(["RECEIVABLE", "TAX_PAYABLE"]);
    expect(itemsOf(map(model({ map: "complete" }), "GET"))).toHaveLength(5);
    expect([model({ map: "empty" }), model({ map: "partial" }), model({ map: "complete" })].map((target) => target.mapComplete())).toEqual([false, false, true]);
  });

  it("saves in steps: only the entries sent are written, a new one with no version, and the answer is the whole map", () => {
    const target = model({ map: "empty" });

    const first = map(target, "PUT", { entries: [{ purpose: "RECEIVABLE", ledgerAccountId: ACCOUNT_IDS.receivable }] });
    const second = map(target, "PUT", {
      entries: [
        { purpose: "TAX_PAYABLE", ledgerAccountId: ACCOUNT_IDS.taxPayable },
        { purpose: "REVENUE", conceptCategory: "TUITION", ledgerAccountId: ACCOUNT_IDS.tuition },
      ],
    });

    expect(itemsOf(first)).toHaveLength(1);
    expect(itemsOf(second).map((row) => [row["purpose"], row["conceptCategory"] ?? null])).toEqual([
      ["RECEIVABLE", null],
      ["TAX_PAYABLE", null],
      ["REVENUE", "TUITION"],
    ]);
    expect(map(target, "GET")?.body).toEqual(second?.body);
  });

  it("moves the version of a row that points at another account, and leaves it alone when the same account is sent again", () => {
    const target = model({ map: "complete" });

    const same = map(target, "PUT", { entries: [{ purpose: "RECEIVABLE", ledgerAccountId: ACCOUNT_IDS.receivable, version: 0 }] });
    const other = map(target, "PUT", { entries: [{ purpose: "REVENUE", ledgerAccountId: ACCOUNT_IDS.tuition, version: 0 }] });

    expect(itemsOf(same).find((row) => row["purpose"] === "RECEIVABLE")?.["version"]).toBe(0);
    expect(itemsOf(other).find((row) => row["purpose"] === "REVENUE" && row["conceptCategory"] == null)).toMatchObject({ ledgerAccountId: ACCOUNT_IDS.tuition, version: 1 });
  });

  it("refuses an account of the wrong type, an inactive one, one that does not exist and a receivable without student balances", () => {
    const target = model({ map: "empty" });
    const put = (purpose: string, ledgerAccountId: string) => map(target, "PUT", { entries: [{ purpose, ledgerAccountId }] });

    expect(typeOf(put("RECEIVABLE", ACCOUNT_IDS.cash))).toBe("posting-account-invalid");
    expect(typeOf(put("RECEIVABLE", ACCOUNT_IDS.taxPayable))).toBe("posting-account-invalid");
    expect(typeOf(put("TAX_PAYABLE", ACCOUNT_IDS.tuition))).toBe("posting-account-invalid");
    expect(typeOf(put("REVENUE", ACCOUNT_IDS.receivable))).toBe("posting-account-invalid");
    expect(typeOf(put("REVENUE", ACCOUNT_IDS.retiredIncome))).toBe("inactive-reference");
    expect(put("REVENUE", "00000000-0000-4000-8000-0000000000ff")?.status).toBe(404);
    expect(target.postingRows()).toHaveLength(0);
  });

  it("saves nothing when one entry of the request is good and another is bad", () => {
    const target = model({ map: "empty" });

    const answer = map(target, "PUT", {
      entries: [
        { purpose: "RECEIVABLE", ledgerAccountId: ACCOUNT_IDS.receivable },
        { purpose: "TAX_PAYABLE", ledgerAccountId: ACCOUNT_IDS.cash },
      ],
    });

    expect(typeOf(answer)).toBe("posting-account-invalid");
    expect(target.postingRows()).toHaveLength(0);
  });

  it("refuses a category outside the revenue, the same key twice, an empty request and one with more than twenty entries", () => {
    const target = model({ map: "empty" });
    const row = { purpose: "RECEIVABLE", ledgerAccountId: ACCOUNT_IDS.receivable };

    expect(fieldsOf(map(target, "PUT", { entries: [{ ...row, conceptCategory: "TUITION" }] }))[0]?.reason).toBe("not-allowed");
    expect(fieldsOf(map(target, "PUT", { entries: [row, row] }))).toEqual([{ field: "entries", reason: "duplicate" }]);
    expect(map(target, "PUT", { entries: [] })?.status).toBe(400);
    expect(map(target, "PUT", { entries: Array.from({ length: 21 }, () => row) })?.status).toBe(400);
    expect(map(target, "PUT", { entries: [{ purpose: "OTHER", ledgerAccountId: ACCOUNT_IDS.receivable }] })?.status).toBe(400);
  });

  it("refuses a stale version, and a version for a row that was never set, and saves nothing", () => {
    const target = model({ map: "partial" });

    const old = map(target, "PUT", { entries: [{ purpose: "RECEIVABLE", ledgerAccountId: ACCOUNT_IDS.receivable, version: 9 }] });
    const absent = map(target, "PUT", { entries: [{ purpose: "RECEIVABLE", ledgerAccountId: ACCOUNT_IDS.receivable }] });
    const neverSet = map(target, "PUT", { entries: [{ purpose: "REVENUE", ledgerAccountId: ACCOUNT_IDS.tuition, version: 0 }] });

    expect([old, absent, neverSet].map(typeOf)).toEqual(["stale-version", "stale-version", "stale-version"]);
    expect(target.postingRows()).toHaveLength(2);
  });

  it("serves a stale version once in the stale scenario, with the rows moved on, and the retry with the new versions goes through", () => {
    const target = model({ writes: "stale-first" });
    const entry = (version: number) => ({ entries: [{ purpose: "REVENUE", ledgerAccountId: ACCOUNT_IDS.tuition, conceptCategory: "TUITION" }, { purpose: "RECEIVABLE", ledgerAccountId: ACCOUNT_IDS.receivable, version }] });

    const first = map(target, "PUT", entry(0));
    const retry = map(target, "PUT", entry(1));

    expect(typeOf(first)).toBe("stale-version");
    expect(retry?.status).toBe(200);
    expect(itemsOf(retry)).toHaveLength(3);
  });

  it("fails the first reads of the map and then recovers, apart from the reads of the accounts", () => {
    const failing = model({ mapReads: "failing-first" });

    expect(Array.from({ length: FAILING_READS + 1 }, () => map(failing, "GET")?.status)).toEqual([500, 500, 500, 200]);
    expect(accounts(failing, "GET")?.status).toBe(200);
  });

  it("answers nothing to a path or a method it does not simulate", () => {
    const target = model();

    expect(accounts(target, "DELETE", `/${ACCOUNT_IDS.cash}`)).toBeUndefined();
    expect(map(target, "POST", {})).toBeUndefined();
    expect(target.handle({ method: "GET", path: "/api/v1/ledger/other", query: new URLSearchParams(), body: undefined })).toBeUndefined();
  });
});

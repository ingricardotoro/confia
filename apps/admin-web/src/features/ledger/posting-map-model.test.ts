import { describe, expect, it } from "vitest";
import { postingAccount } from "../../test/contract-responses.ts";
import { POSTING_ROLES, REVENUE_CATEGORIES, accountFits, changedEntries, missingRoles, savedByRole } from "./posting-map-model.ts";

const A = "00000000-0000-4000-8000-0000000000a1";
const B = "00000000-0000-4000-8000-0000000000a2";

describe("the roles of the map", () => {
  it("has the receivable, the tax payable, the default revenue and one revenue for each of the six categories", () => {
    expect(POSTING_ROLES.map((role) => role.id)).toEqual([
      "RECEIVABLE",
      "TAX_PAYABLE",
      "REVENUE_DEFAULT",
      ...REVENUE_CATEGORIES.map((category) => `REVENUE_${category}`),
    ]);
    expect(REVENUE_CATEGORIES).toHaveLength(6);
  });

  it("asks each role for the type of account the server demands", () => {
    const role = (id: string) => {
      const found = POSTING_ROLES.find((candidate) => candidate.id === id);
      if (found === undefined) throw new Error(`no role ${id}`);
      return found;
    };
    const asset = { type: "ASSET", holdsStudentBalances: true };

    expect(accountFits(role("RECEIVABLE"), asset)).toBe(true);
    expect(accountFits(role("RECEIVABLE"), { type: "ASSET", holdsStudentBalances: false })).toBe(false);
    expect(accountFits(role("TAX_PAYABLE"), { type: "LIABILITY", holdsStudentBalances: false })).toBe(true);
    expect(accountFits(role("TAX_PAYABLE"), { type: "LIABILITY", holdsStudentBalances: true })).toBe(false);
    expect(accountFits(role("REVENUE_TUITION"), { type: "INCOME", holdsStudentBalances: false })).toBe(true);
    expect(accountFits(role("REVENUE_TUITION"), asset)).toBe(false);
  });
});

describe("reading the saved map", () => {
  it("puts each row under its role with its version, and tells the default revenue from the one of a category", () => {
    const saved = savedByRole([
      postingAccount({ purpose: "RECEIVABLE", ledgerAccountId: A, version: 2 }),
      postingAccount({ purpose: "REVENUE", conceptCategory: null, ledgerAccountId: B, version: 0 }),
      postingAccount({ purpose: "REVENUE", conceptCategory: "TUITION", ledgerAccountId: A, version: 5 }),
    ]);

    expect(saved.get("RECEIVABLE")).toMatchObject({ ledgerAccountId: A, version: 2 });
    expect(saved.get("REVENUE_DEFAULT")).toMatchObject({ ledgerAccountId: B, version: 0 });
    expect(saved.get("REVENUE_TUITION")).toMatchObject({ ledgerAccountId: A, version: 5 });
    expect(saved.has("TAX_PAYABLE")).toBe(false);
  });
});

describe("what is missing", () => {
  it("names the receivable, the tax payable and the default revenue when nothing is set", () => {
    expect(missingRoles(new Map())).toEqual(["RECEIVABLE", "TAX_PAYABLE", "REVENUE_DEFAULT"]);
  });

  it("does not ask for the default revenue when every category has its own", () => {
    const rows = [
      postingAccount({ purpose: "RECEIVABLE", ledgerAccountId: A }),
      postingAccount({ purpose: "TAX_PAYABLE", ledgerAccountId: B }),
      ...REVENUE_CATEGORIES.map((category) => postingAccount({ purpose: "REVENUE", conceptCategory: category, ledgerAccountId: A })),
    ];

    expect(missingRoles(savedByRole(rows))).toEqual([]);
  });

  it("is complete with the default revenue and no category of its own", () => {
    const rows = [
      postingAccount({ purpose: "RECEIVABLE", ledgerAccountId: A }),
      postingAccount({ purpose: "TAX_PAYABLE", ledgerAccountId: B }),
      postingAccount({ purpose: "REVENUE", conceptCategory: null, ledgerAccountId: A }),
    ];

    expect(missingRoles(savedByRole(rows))).toEqual([]);
  });
});

describe("the entries to send", () => {
  it("sends nothing when nothing changed, when a row is chosen back to what it holds, or when it is left empty", () => {
    const saved = savedByRole([postingAccount({ purpose: "RECEIVABLE", ledgerAccountId: A, version: 1 })]);

    expect(changedEntries(saved, {})).toEqual([]);
    expect(changedEntries(saved, { RECEIVABLE: A })).toEqual([]);
    expect(changedEntries(saved, { TAX_PAYABLE: "" })).toEqual([]);
  });

  it("sends only the rows that changed, a saved row with its version and a new one with none, and a category only for the revenue", () => {
    const saved = savedByRole([postingAccount({ purpose: "RECEIVABLE", ledgerAccountId: A, version: 4 })]);

    expect(changedEntries(saved, { RECEIVABLE: B, TAX_PAYABLE: B, REVENUE_FINE: A, REVENUE_DEFAULT: A })).toEqual([
      { purpose: "RECEIVABLE", ledgerAccountId: B, version: 4 },
      { purpose: "TAX_PAYABLE", ledgerAccountId: B },
      { purpose: "REVENUE", ledgerAccountId: A },
      { purpose: "REVENUE", conceptCategory: "FINE", ledgerAccountId: A },
    ]);
  });
});

import { describe, expect, it } from "vitest";
import { postingAccount } from "../../test/contract-responses.ts";
import { POSTING_ROLES, REVENUE_CATEGORIES, accountFits, changedEntries, changedPaymentEntries, missingPaymentRoles, missingRoles, savedByRole } from "./posting-map-model.ts";

const A = "00000000-0000-4000-8000-0000000000a1";
const B = "00000000-0000-4000-8000-0000000000a2";

describe("the scholarships row of the map", () => {
  it("is read from the purpose BENEFITS, sent when it changes, and not required for the map to be complete", () => {
    const saved = savedByRole([postingAccount({ purpose: "BENEFITS", ledgerAccountId: A, version: 2 })]);

    expect(saved.get("BENEFITS")).toMatchObject({ ledgerAccountId: A, version: 2 });
    expect(changedEntries(saved, { BENEFITS: B })).toEqual([{ purpose: "BENEFITS", ledgerAccountId: B, version: expect.any(Number) as number }]);
    expect(missingRoles(new Map())).not.toContain("BENEFITS");
  });
});

describe("the roles of the map", () => {
  it("has the receivable, the tax payable, the scholarships, the default revenue, one revenue for each of the six categories, and the cash and the bank of the payments", () => {
    expect(POSTING_ROLES.map((role) => role.id)).toEqual([
      "RECEIVABLE",
      "TAX_PAYABLE",
      "BENEFITS",
      "REVENUE_DEFAULT",
      ...REVENUE_CATEGORIES.map((category) => `REVENUE_${category}`),
      "CASH",
      "BANK",
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
    // The scholarships take a contra-revenue (income) or an expense account that carries no student balance, until the accountant answers.
    expect(accountFits(role("BENEFITS"), { type: "INCOME", holdsStudentBalances: false })).toBe(true);
    expect(accountFits(role("BENEFITS"), { type: "EXPENSE", holdsStudentBalances: false })).toBe(true);
    expect(accountFits(role("BENEFITS"), { type: "LIABILITY", holdsStudentBalances: false })).toBe(false);
    expect(accountFits(role("BENEFITS"), { type: "EXPENSE", holdsStudentBalances: true })).toBe(false);
    // The cash and the bank are assets that carry no student balance.
    expect(accountFits(role("CASH"), { type: "ASSET", holdsStudentBalances: false })).toBe(true);
    expect(accountFits(role("CASH"), asset)).toBe(false);
    expect(accountFits(role("BANK"), { type: "ASSET", holdsStudentBalances: false })).toBe(true);
    expect(accountFits(role("BANK"), { type: "INCOME", holdsStudentBalances: false })).toBe(false);
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

describe("the map of the payments", () => {
  const cash = { id: "00000000-0000-4000-8000-0000000000c1", purpose: "CASH" as const, ledgerAccountId: A, version: 3, updatedAt: "2026-11-01T12:00:00Z" };

  it("puts the cash under its role with its version, apart from the map of the charges", () => {
    const saved = savedByRole([], [cash]);

    expect(saved.get("CASH")).toEqual({ id: cash.id, ledgerAccountId: A, version: 3 });
    expect(saved.has("BANK")).toBe(false);
    expect(saved.has("RECEIVABLE")).toBe(false);
  });

  it("names the cash and the bank that are not set, and leaves them out of the missing of the charges", () => {
    expect(missingPaymentRoles(new Map())).toEqual(["CASH", "BANK"]);
    expect(missingPaymentRoles(savedByRole([], [cash]))).toEqual(["BANK"]);
    expect(missingRoles(new Map())).toEqual(["RECEIVABLE", "TAX_PAYABLE", "REVENUE_DEFAULT"]);
  });

  it("sends the cash and the bank only in the payments entries, a saved one with its version and a new one with none", () => {
    const saved = savedByRole([], [cash]);

    expect(changedPaymentEntries(saved, { CASH: B, BANK: B })).toEqual([
      { purpose: "CASH", ledgerAccountId: B, version: 3 },
      { purpose: "BANK", ledgerAccountId: B },
    ]);
    expect(changedEntries(saved, { CASH: B, BANK: B })).toEqual([]);
    expect(changedPaymentEntries(saved, { CASH: A, RECEIVABLE: B })).toEqual([]);
  });
});

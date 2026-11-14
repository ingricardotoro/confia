import { describe, expect, it } from "vitest";
import { studentLink } from "../../test/contract-responses.ts";
import { bodyOf, draftOf, draftsOf, percentProblem, validateLinks, type LinkDraft } from "./link-model.ts";

const GUARDIAN = { id: "00000000-0000-4000-8000-000000000001", guardianCode: "P-000001", firstName: "Rosa", lastName: "Mejía", status: "ACTIVE" as const };

const row = (override: Partial<LinkDraft> = {}): LinkDraft => ({ ...draftOf(GUARDIAN), relationship: "MOTHER", percent: "60.00", ...override });

describe("percentProblem: what is wrong with a typed percentage, judged from the text and never from a sum", () => {
  it.each(["0.01", "0.5", "1", "33.33", "33.34", "50", "60.00", "99.99", "100", "100.0", "100.00"])("accepts %s", (text) => {
    expect(percentProblem(text)).toBeUndefined();
  });

  it("asks for a value when it is empty", () => {
    expect(percentProblem("")).toBe("required");
  });

  it.each(["abc", "33,33", "33.333", "33.", ".5", "-5", "1e2", "1000", "12 5", "33.330"])("refuses %s as not a number with at most two decimals", (text) => {
    expect(percentProblem(text)).toBe("percent-format");
  });

  it.each(["0", "0.0", "0.00", "00", "100.01", "100.1", "101", "999"])("refuses %s as out of 0.01 to 100", (text) => {
    expect(percentProblem(text)).toBe("percent-range");
  });
});

describe("the rows of the set", () => {
  it("starts a chosen guardian with nothing but the guardian: the person must say the relationship and the percentage", () => {
    const fresh = draftOf(GUARDIAN);

    expect(fresh).toMatchObject({ guardian: GUARDIAN, relationship: "", percent: "", primary: false, billing: false });
    expect(fresh.key).not.toBe(draftOf(GUARDIAN).key);
  });

  it("reads the set the server holds with each percentage written with its two decimals", () => {
    const rows = draftsOf([
      studentLink({ guardianId: GUARDIAN.id, financialResponsibilityPercent: 60, relationship: "MOTHER", isPrimaryPayer: true, isBillingRecipient: false }),
      studentLink({ financialResponsibilityPercent: 33.33, relationship: "FATHER", isPrimaryPayer: false, isBillingRecipient: true }),
    ]);

    expect(rows.map(({ percent, relationship, primary, billing }) => ({ percent, relationship, primary, billing }))).toEqual([
      { percent: "60.00", relationship: "MOTHER", primary: true, billing: false },
      { percent: "33.33", relationship: "FATHER", primary: false, billing: true },
    ]);
    expect(rows[0]?.guardian).toMatchObject({ id: GUARDIAN.id, guardianCode: "P-000001", firstName: "Rosa" });
  });

  it("names the rows that are refused and why, and leaves the good ones out", () => {
    const bad = row({ relationship: "", percent: "101" });
    const worse = row({ percent: "" });
    const good = row();

    expect(validateLinks([bad, worse, good])).toEqual({
      [bad.key]: { relationship: "required", percent: "percent-range" },
      [worse.key]: { percent: "required" },
    });
  });
});

describe("the body of the replacement", () => {
  it("is exactly the members of the contract, with the percentage converted from the typed text and nothing added up", () => {
    const body = bodyOf([row({ percent: "33.33", primary: true }), row({ percent: "66.67", billing: true, relationship: "OTHER" })]);

    expect(body).toEqual({
      links: [
        { guardianId: GUARDIAN.id, relationship: "MOTHER", financialResponsibilityPercent: 33.33, isPrimaryPayer: true, isBillingRecipient: false },
        { guardianId: GUARDIAN.id, relationship: "OTHER", financialResponsibilityPercent: 66.67, isPrimaryPayer: false, isBillingRecipient: true },
      ],
    });
  });

  it("is an empty list when the set is empty (it removes every guardian)", () => {
    expect(bodyOf([])).toEqual({ links: [] });
  });
});

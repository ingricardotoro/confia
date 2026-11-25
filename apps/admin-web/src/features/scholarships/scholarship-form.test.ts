import { describe, expect, it } from "vitest";
import { scholarship } from "../../test/contract-responses.ts";
import {
  conceptIdsOf,
  initialScholarshipValues,
  joinConceptIds,
  saveOf,
  validateScholarship,
  type ScholarshipValues,
} from "./scholarship-form.ts";

// The form model of a scholarship: what is sent for each type of benefit, and what is refused before anything is sent. A percentage is a number of
// points judged on its characters, an amount is never parsed, and the server decides the rest.
const MENS = "00000000-0000-4000-8000-000000007001";
const values = (override: Partial<ScholarshipValues> = {}): ScholarshipValues => ({
  ...initialScholarshipValues(),
  code: "BECA25",
  name: "Beca de excelencia",
  benefitType: "PERCENTAGE",
  percent: "25",
  conceptIds: MENS,
  ...override,
});
const reasons = (override: Partial<ScholarshipValues> = {}, row = undefined as ReturnType<typeof scholarship> | undefined) => validateScholarship(row)(values(override));

describe("the concepts of the form", () => {
  it("are one text of ids and come back as the same list", () => {
    expect(joinConceptIds(["a", "b"])).toBe("a,b");
    expect(conceptIdsOf("a,b")).toEqual(["a", "b"]);
    expect(conceptIdsOf("")).toEqual([]);
  });
});

describe("what is sent", () => {
  it("is a percentage with no fixed amount, and no cap when the cap is empty", () => {
    expect(saveOf(undefined, values())).toEqual({
      mode: "create",
      body: { code: "BECA25", name: "Beca de excelencia", benefitType: "PERCENTAGE", percent: "25", conceptIds: [MENS] },
    });
  });

  it("is a fixed amount in lempiras with no percentage, and the cap in lempiras when there is one", () => {
    expect(saveOf(undefined, values({ benefitType: "FIXED_AMOUNT", percent: "9", fixedAmount: "250.00", periodCap: "1000.00" }))).toEqual({
      mode: "create",
      body: {
        code: "BECA25",
        name: "Beca de excelencia",
        benefitType: "FIXED_AMOUNT",
        fixedAmount: { amount: "250.00", currency: "HNL" },
        periodCap: { amount: "1000.00", currency: "HNL" },
        conceptIds: [MENS],
      },
    });
  });

  it("is an edit with the version it was read at and without the code", () => {
    const row = scholarship({ id: "00000000-0000-4000-8000-0000000e0001", version: 4 });
    const save = saveOf(row, initialScholarshipValues(row));

    expect(save).toMatchObject({ mode: "update", id: row.id, body: { version: 4, name: row.name, benefitType: "PERCENTAGE", percent: "25.00" } });
    expect(save.body).not.toHaveProperty("code");
  });
});

describe("what is refused before sending", () => {
  it("accepts a valid scholarship of each type", () => {
    expect(reasons()).toEqual({});
    expect(reasons({ benefitType: "FIXED_AMOUNT", fixedAmount: "250.00" })).toEqual({});
    expect(reasons({ percent: "100" })).toEqual({});
    expect(reasons({ percent: "0.01" })).toEqual({});
  });

  it.each([
    ["", "required"],
    ["0", "percent-range"],
    ["0.00", "percent-range"],
    ["100.01", "percent-range"],
    ["150", "percent-range"],
    ["12.345", "percent-format"],
    ["-5", "percent-format"],
    ["abc", "percent-format"],
  ])("refuses the percentage %j as %s", (percent, reason) => {
    expect(reasons({ percent }).percent).toBe(reason);
  });

  it.each([
    ["", "required"],
    ["0", "amount-positive"],
    ["-1", "amount-positive"],
    ["12.345", "amount-format"],
    ["1,000", "amount-format"],
  ])("refuses the fixed amount %j as %s", (fixedAmount, reason) => {
    expect(reasons({ benefitType: "FIXED_AMOUNT", fixedAmount }).fixedAmount).toBe(reason);
  });

  it("refuses a cap that is not an amount above zero, and takes an empty one as no cap", () => {
    expect(reasons({ periodCap: "0" }).periodCap).toBe("amount-positive");
    expect(reasons({ periodCap: "12.345" }).periodCap).toBe("amount-format");
    expect(reasons({ periodCap: "" }).periodCap).toBeUndefined();
  });

  it("refuses a code that is empty or outside the whitelist, only when the scholarship is new", () => {
    expect(reasons({ code: "" }).code).toBe("required");
    expect(reasons({ code: "BECA 25" }).code).toBe("code-format");
    expect(reasons({ code: "_BECA" }).code).toBe("code-format");
    expect(reasons({ code: "A".repeat(31) }).code).toBe("code-format");
    expect(reasons({ code: "" }, scholarship()).code).toBeUndefined();
  });

  it("refuses a blank name and a name over 120 characters", () => {
    expect(reasons({ name: "   " }).name).toBe("required");
    expect(reasons({ name: "N".repeat(121) }).name).toBe("too-long");
    expect(reasons({ name: "N".repeat(120) }).name).toBeUndefined();
  });

  it("refuses a missing type and a scholarship with no concept", () => {
    expect(reasons({ benefitType: "" }).benefitType).toBe("required");
    expect(reasons({ conceptIds: "" }).conceptIds).toBe("required");
  });
});

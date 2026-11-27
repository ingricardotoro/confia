import { describe, expect, it } from "vitest";
import { discount } from "../../test/contract-responses.ts";
import { discountSaveOf, initialDiscountValues, validateDiscount, type DiscountValues } from "./discount-form.ts";

// The form model of a discount: what is sent for each type of benefit, the stackable mark and the validity, and what is refused before anything is
// sent. A percentage is a number of points judged on its characters, an amount is never parsed, and the server decides the rest.
const MENS = "00000000-0000-4000-8000-000000007001";
const values = (override: Partial<DiscountValues> = {}): DiscountValues => ({
  ...initialDiscountValues(),
  code: "CAMP10",
  name: "Campaña de matrícula",
  kind: "campaign",
  benefitType: "PERCENTAGE",
  percent: "10",
  validFrom: "2027-01-01",
  validTo: "2027-12-31",
  stackable: "true",
  conceptIds: MENS,
  ...override,
});
const reasons = (override: Partial<DiscountValues> = {}, row = undefined as ReturnType<typeof discount> | undefined) => validateDiscount(row)(values(override));

describe("what is sent", () => {
  it("is a percentage with no fixed amount, the class, the validity and the stackable mark as a boolean", () => {
    expect(discountSaveOf(undefined, values())).toEqual({
      mode: "create",
      body: {
        code: "CAMP10",
        kind: "campaign",
        name: "Campaña de matrícula",
        benefitType: "PERCENTAGE",
        percent: "10",
        conceptIds: [MENS],
        stackable: true,
        validFrom: "2027-01-01",
        validTo: "2027-12-31",
      },
    });
  });

  it("is a fixed amount in lempiras with no percentage, and a discount that does not stack", () => {
    expect(discountSaveOf(undefined, values({ benefitType: "FIXED_AMOUNT", percent: "9", fixedAmount: "250.00", stackable: "false" }))).toMatchObject({
      mode: "create",
      body: { benefitType: "FIXED_AMOUNT", fixedAmount: { amount: "250.00", currency: "HNL" }, stackable: false },
    });
    expect("percent" in (discountSaveOf(undefined, values({ benefitType: "FIXED_AMOUNT", fixedAmount: "250.00" })).body)).toBe(false);
  });

  it("is, for an edit, the version it was read at with neither the code nor the class", () => {
    const row = discount({ id: "00000000-0000-4000-8000-0000000e0001", version: 3, kind: "agreement" });
    const save = discountSaveOf(row, initialDiscountValues(row));

    expect(save).toMatchObject({ mode: "update", id: row.id, body: { version: 3, percent: "10.00", stackable: true } });
    expect("code" in save.body).toBe(false);
    expect("kind" in save.body).toBe(false);
  });

  it("starts a new discount as not stackable and an existing one with the mark it holds", () => {
    expect(initialDiscountValues().stackable).toBe("false");
    expect(initialDiscountValues(discount({ stackable: false })).stackable).toBe("false");
    expect(initialDiscountValues(discount({ stackable: true })).stackable).toBe("true");
  });
});

describe("what is refused before sending", () => {
  it("accepts a complete discount", () => {
    expect(reasons()).toEqual({});
  });

  it("requires the code, the class, the name, the type, the validity and one concept", () => {
    expect(reasons({ code: "", kind: "", name: " ", benefitType: "", validFrom: "", validTo: "", conceptIds: "" })).toMatchObject({
      code: "required",
      kind: "required",
      name: "required",
      benefitType: "required",
      validFrom: "required",
      validTo: "required",
      conceptIds: "required",
    });
  });

  it("does not ask an existing discount for its code or its class", () => {
    const row = discount();
    const own = validateDiscount(row)(initialDiscountValues(row));

    expect(own).toEqual({});
  });

  it.each([
    ["0", "percent-range"],
    ["100.01", "percent-range"],
    ["101", "percent-range"],
    ["12.345", "percent-format"],
    ["abc", "percent-format"],
  ])("refuses the percentage %s", (percent, reason) => {
    expect(reasons({ percent }).percent).toBe(reason);
  });

  it("refuses a fixed amount that is zero or has more than two decimals", () => {
    expect(reasons({ benefitType: "FIXED_AMOUNT", fixedAmount: "0" }).fixedAmount).toBeDefined();
    expect(reasons({ benefitType: "FIXED_AMOUNT", fixedAmount: "10.123" }).fixedAmount).toBeDefined();
    expect(reasons({ benefitType: "FIXED_AMOUNT", fixedAmount: "250.00" }).fixedAmount).toBeUndefined();
  });

  it("allows a validity of one day, because both ends are included, and refuses an end before the start", () => {
    expect(reasons({ validFrom: "2027-03-01", validTo: "2027-03-01" }).validTo).toBeUndefined();
    expect(reasons({ validFrom: "2027-03-02", validTo: "2027-03-01" }).validTo).toBe("validity-order");
  });

  it("refuses a code with a character the server does not accept, and a name that is too long", () => {
    expect(reasons({ code: "CAMP 10" }).code).toBe("code-format");
    expect(reasons({ name: "x".repeat(121) }).name).toBe("too-long");
  });
});

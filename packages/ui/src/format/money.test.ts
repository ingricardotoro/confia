import { afterEach, describe, expect, it, vi } from "vitest";
import { invalidMoneyCases, validMoneyCases } from "./__fixtures__/money-cases";
import { formatMoney, type FormattedMoney, type MoneyFormatOptions, type MoneyValue } from "./money";

const LOCALE = "es-HN";
const PLAIN: MoneyFormatOptions = { sign: "auto", hideCurrency: false };

// Rows are deliberately malformed (wrong runtime types), so they enter through this cast.
function asMoney(amount: unknown, currency: unknown): MoneyValue {
  return { amount, currency } as unknown as MoneyValue;
}

function formatted(result: FormattedMoney): { visual: string; spoken: string } {
  if (result.kind !== "formatted") throw new Error("expected a formatted amount, got invalid");
  return result;
}

describe("formatMoney with valid input", () => {
  it.each(validMoneyCases)(
    "$scenario: $amount $currency ($sign, hideCurrency $hideCurrency) shows $visual",
    ({ amount, currency, sign, hideCurrency, visual }) => {
      const result = formatMoney({ amount, currency }, LOCALE, { sign, hideCurrency });

      expect(formatted(result).visual).toBe(visual);
    },
  );

  it("DS30: gives dollars an indicator distinct from lempiras", () => {
    const hnl = formatted(formatMoney({ amount: "3200.00", currency: "HNL" }, LOCALE, PLAIN));
    const usd = formatted(formatMoney({ amount: "3200.00", currency: "USD" }, LOCALE, PLAIN));

    expect(usd.visual).toContain("USD");
    expect(usd.visual).not.toBe(hnl.visual);
  });

  it("DS49: speaks the amount together with the currency name", () => {
    const hnl = formatted(formatMoney({ amount: "3200.50", currency: "HNL" }, LOCALE, PLAIN));
    const usd = formatted(formatMoney({ amount: "3200.50", currency: "USD" }, LOCALE, PLAIN));

    expect(hnl.spoken).toMatch(/3,200\.50.*lempiras/);
    expect(usd.spoken).toMatch(/3,200\.50.*d.lares/);
  });

  it("DS49: still speaks the currency when the visual form hides it", () => {
    const result = formatted(
      formatMoney({ amount: "3200.50", currency: "HNL" }, LOCALE, { sign: "auto", hideCurrency: true }),
    );

    expect(result.visual).toBe("3,200.50");
    expect(result.spoken).toContain("lempiras");
  });

  it("DS49: speaks a negative amount with its sign and never with parentheses", () => {
    const result = formatted(
      formatMoney({ amount: "-1500.00", currency: "HNL" }, LOCALE, { sign: "accounting", hideCurrency: false }),
    );

    expect(result.spoken).toMatch(/^-1,500\.00 lempiras/);
  });

  it("DS51: gives the plus sign only to positive amounts", () => {
    const options: MoneyFormatOptions = { sign: "always", hideCurrency: false };
    const hasPlus = ["3200.00", "0.00", "-0.00", "-1500.00"].map((amount) =>
      formatted(formatMoney({ amount, currency: "HNL" }, LOCALE, options)).visual.includes("+"),
    );

    expect(hasPlus).toEqual([true, false, false, false]);
  });

  it("returns invalid instead of throwing for a malformed locale tag", () => {
    const result = formatMoney({ amount: "3200.00", currency: "HNL" }, "not_a_locale!", PLAIN);

    expect(result).toEqual({ kind: "invalid" });
  });

  it("formats the same value the same way on a repeated call (cached formatters)", () => {
    const first = formatMoney({ amount: "3200.00", currency: "HNL" }, LOCALE, PLAIN);

    expect(formatMoney({ amount: "3200.00", currency: "HNL" }, LOCALE, PLAIN)).toEqual(first);
  });
});

describe("formatMoney with invalid input", () => {
  it.each(invalidMoneyCases)("$scenario: rejects amount $amount with currency $currency", ({ amount, currency }) => {
    expect(formatMoney(asMoney(amount, currency), LOCALE, PLAIN)).toEqual({ kind: "invalid" });
  });

  it("rejects a missing value object without throwing", () => {
    expect(formatMoney(null as unknown as MoneyValue, LOCALE, PLAIN)).toEqual({ kind: "invalid" });
    expect(formatMoney(undefined as unknown as MoneyValue, LOCALE, PLAIN)).toEqual({ kind: "invalid" });
  });

  it("never rounds: the rejected value produces no digits at all", () => {
    const result = formatMoney({ amount: "3200.005", currency: "HNL" }, LOCALE, PLAIN);

    expect(JSON.stringify(result)).not.toMatch(/3,200/);
  });
});

// ADR-0011 and CLAUDE.md rule 1: the amount string reaches Intl untouched.
describe("formatMoney never converts the amount to a number", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("makes zero calls to Number, parseFloat, parseInt or Math.round for any valid input", () => {
    const spies = [
      vi.spyOn(globalThis, "Number"),
      vi.spyOn(globalThis, "parseFloat"),
      vi.spyOn(globalThis, "parseInt"),
      vi.spyOn(Math, "round"),
    ];

    for (const { amount, currency, sign, hideCurrency } of validMoneyCases) {
      formatMoney({ amount, currency }, LOCALE, { sign, hideCurrency });
    }

    expect(spies.map((spy) => spy.mock.calls.length)).toEqual([0, 0, 0, 0]);
  });
});

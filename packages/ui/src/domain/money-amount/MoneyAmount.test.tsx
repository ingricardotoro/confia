import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { invalidMoneyCases, validMoneyCases } from "../../format/__fixtures__/money-cases";
import { createUiI18n } from "../../i18n/create-ui-i18n";
import { renderWithI18n } from "../../test/render";
import { MoneyAmount } from "./MoneyAmount";
import type { MoneyAmountProps } from "./MoneyAmount";

// The rows of src/format/__fixtures__/money-cases.ts are the same ones money.test.ts checks against
// formatMoney, so the utility and the component cannot drift apart (design.md decision 12).
const UNAVAILABLE = "Importe no disponible";

// The component is one root with two children: the visual form (hidden) and the spoken form. They
// have no role to be found by, so they are reached from the root, which is the only thing rendered.
function rootOf(container: HTMLElement): HTMLElement {
  const root = container.firstElementChild;
  if (!(root instanceof HTMLElement)) throw new Error("nothing was rendered");
  // The class carries the tabular numerals, so every lookup through the root also proves it.
  expect(root).toHaveClass("money-amount");
  return root;
}

function visualOf(container: HTMLElement): string {
  const visual = rootOf(container).firstElementChild;
  expect(visual).toHaveAttribute("aria-hidden", "true");
  return visual?.textContent ?? "";
}

function spokenOf(container: HTMLElement): string {
  const spoken = rootOf(container).lastElementChild;
  expect(spoken).not.toHaveAttribute("aria-hidden");
  // Visually hidden, or the amount would show twice.
  expect(spoken).toHaveClass("sr-only");
  return spoken?.textContent ?? "";
}

function renderMoney(amount: string, currency: string, props: Partial<MoneyAmountProps> = {}) {
  return renderWithI18n(<MoneyAmount value={{ amount, currency }} {...props} />).container;
}

// Rows are deliberately malformed (wrong runtime types), so they enter through this cast.
function renderMalformed(amount: unknown, currency: unknown) {
  const value = { amount, currency } as unknown as MoneyAmountProps["value"];
  return renderWithI18n(<MoneyAmount value={value} />).container;
}

describe("MoneyAmount with a valid amount", () => {
  it.each(validMoneyCases)(
    "$scenario: $amount $currency ($sign, hideCurrency $hideCurrency) shows $visual",
    ({ amount, currency, sign, hideCurrency, visual }) => {
      const container = renderMoney(amount, currency, {
        accounting: sign === "accounting",
        showPositiveSign: sign === "always",
        hideCurrency,
      });

      expect(visualOf(container)).toBe(visual);
    },
  );

  it("DS72: shows zero as 0.00 and never as an empty string", () => {
    expect(visualOf(renderMoney("0.00", "HNL"))).toBe("L\u00A00.00");
  });

  it("DS30: shows dollars with an indicator distinct from lempiras", () => {
    const hnl = visualOf(renderMoney("3200.00", "HNL"));
    const usd = visualOf(renderMoney("3200.00", "USD"));

    expect(usd).toContain("USD");
    expect(usd).not.toBe(hnl);
  });

  it("DS39: matches the platform formatter for es-HN", () => {
    const expected = new Intl.NumberFormat("es-HN", { style: "currency", currency: "HNL" }).format("1500.00");

    expect(visualOf(renderMoney("1500.00", "HNL"))).toBe(expected);
  });

  it("gives accounting precedence over the plus sign, so a negative is never both", () => {
    const container = renderMoney("-50.00", "HNL", { accounting: true, showPositiveSign: true });

    expect(visualOf(container)).toBe("(L\u00A050.00)");
  });
});

describe("MoneyAmount with an input outside the contract", () => {
  it.each(invalidMoneyCases)("$scenario: shows the unavailable text for $amount / $currency", ({ amount, currency }) => {
    const container = renderMalformed(amount, currency);

    expect(container.textContent).toBe(UNAVAILABLE);
  });

  it("DS43: never shows a rounded or guessed figure for 3200.005", () => {
    const container = renderMoney("3200.005", "HNL");

    expect(container.textContent).not.toMatch(/\d/);
  });

  it("DS43: never shows zero for an invalid amount", () => {
    const container = renderMoney("abc", "HNL");

    expect(container.textContent).not.toContain("0");
  });

  it("does not throw for a missing value object", () => {
    const value = null as unknown as MoneyAmountProps["value"];

    expect(() => renderWithI18n(<MoneyAmount value={value} />)).not.toThrow();
    expect(screen.getByText(UNAVAILABLE)).toBeInTheDocument();
  });

  it("DS47: takes the unavailable text from the catalog and not from the component", () => {
    const i18n = createUiI18n();
    i18n.addResourceBundle("es-HN", "ui", { money: { unavailable: "Sin importe" } }, true, true);

    const { container } = renderWithI18n(<MoneyAmount value={{ amount: "abc", currency: "HNL" }} />, { i18n });

    expect(container.textContent).toBe("Sin importe");
  });

  it("keeps the money-amount class on the unavailable text too", () => {
    expect(rootOf(renderMoney("abc", "HNL"))).toHaveClass("money-amount");
  });
});

describe("MoneyAmount for assistive technology", () => {
  it("DS48: always carries the money-amount class that forces tabular numerals", () => {
    const container = renderMoney("3200.00", "HNL", { className: "normal-nums" });

    expect(rootOf(container)).toHaveClass("money-amount");
  });

  it("DS49: hides the visual form and speaks the amount with the lempira name from Intl", () => {
    const container = renderMoney("3200.00", "HNL");

    expect(visualOf(container)).toBe("L\u00A03,200.00");
    expect(spokenOf(container)).toMatch(/3,200\.00.*lempiras/);
  });

  it("DS49: speaks the amount with the dollar name from Intl", () => {
    expect(spokenOf(renderMoney("3200.00", "USD"))).toMatch(/3,200\.00.*d.lares/);
  });

  it("DS49: still speaks the currency when the visual form hides it", () => {
    const container = renderMoney("3200.00", "HNL", { hideCurrency: true });

    expect(visualOf(container)).toBe("3,200.00");
    expect(spokenOf(container)).toContain("lempiras");
  });

  it("DS49: speaks a negative amount with its minus sign even in accounting mode", () => {
    const container = renderMoney("-1500.00", "HNL", { accounting: true });

    expect(spokenOf(container)).toMatch(/^-1,500\.00 lempiras/);
  });

  it("DS50: applies no sign color unless colorBySign is requested", () => {
    const container = renderMoney("-50.00", "HNL");

    expect(rootOf(container).className).not.toMatch(/text-status/);
  });

  it("DS50: with colorBySign, colors by sign with a semantic token and keeps the minus in the text", () => {
    const negative = renderMoney("-50.00", "HNL", { colorBySign: true });
    const positive = renderMoney("50.00", "HNL", { colorBySign: true });
    const zero = renderMoney("0.00", "HNL", { colorBySign: true });

    expect(rootOf(negative)).toHaveClass("text-status-overdue-fg");
    expect(visualOf(negative)).toBe("-L\u00A050.00");
    expect(rootOf(positive)).toHaveClass("text-status-paid-fg");
    expect(rootOf(zero).className).not.toMatch(/text-status/);
  });

  it("DS50: an unavailable amount gets no sign color even with colorBySign", () => {
    const container = renderMoney("-50.005", "HNL", { colorBySign: true });

    expect(rootOf(container).className).not.toMatch(/text-status/);
  });

  it("DS51: gives the plus sign only to positive amounts", () => {
    const plus = ["50.00", "0.00", "-50.00"].map((amount) =>
      visualOf(renderMoney(amount, "HNL", { showPositiveSign: true })).includes("+"),
    );

    expect(plus).toEqual([true, false, false]);
  });

  it("DS51: shows no plus sign unless showPositiveSign is requested", () => {
    expect(visualOf(renderMoney("50.00", "HNL"))).not.toContain("+");
  });
});

describe("MoneyAmount appearance props", () => {
  it.each([
    ["size", "sm", "text-sm"],
    ["size", "md", "text-base"],
    ["size", "lg", "text-lg"],
    ["size", "xl", "text-2xl"],
    ["emphasis", "strong", "font-semibold"],
    ["emphasis", "muted", "text-text-secondary"],
  ] as const)("%s %s adds %s", (prop, option, expected) => {
    const container = renderMoney("3200.00", "HNL", { [prop]: option });

    expect(rootOf(container)).toHaveClass(expected);
  });

  it("passes native attributes and a caller class through to the root", () => {
    const { container } = renderWithI18n(
      <MoneyAmount value={{ amount: "3200.00", currency: "HNL" }} className="ml-2" data-testid="total" />,
    );

    expect(rootOf(container)).toHaveClass("ml-2");
    expect(rootOf(container)).toHaveAttribute("data-testid", "total");
  });
});

import { describe, expect, it } from "vitest";
import { readFixture, realTokenSources, resolveToken, themeDeclarations, type Theme } from "./__fixtures__/css";

// WCAG 2.1 contrast over the pairs of docs/ui-ux/01 section 5, computed from the stylesheets
// themselves (docs/ui-ux/03 section 2: automatic over the tokens). 4.5:1 for text, 3:1 for the
// components of the interface.
const TEXT = 4.5;
const COMPONENT = 3;

interface Pair {
  foreground: string;
  background: string;
  minimum: number;
}

const STATES = ["paid", "pending", "partial", "overdue", "void", "promised", "refunded"] as const;
const SURFACE = "--color-bg-surface";
const SOFT = { foreground: "--color-primary-soft-fg", background: "--color-primary-soft-bg", minimum: TEXT };

// Not enforced, each with its citation. Listed here so the exemption has one home.
const EXEMPT = [
  { token: "--color-text-disabled", reason: "WCAG 1.4.3 exempts inactive components; docs/ui-ux/01 section 5.1" },
  { token: "--color-border-subtle", reason: "decorative divider, WCAG 1.4.11 does not apply; docs/ui-ux/01 section 5.2" },
];

// Every foreground and background token pair declared in semantic.css that a theme can render. The
// text and link tokens are measured on the three surfaces; the brand soft pair and the feedback
// colours (icons, borders) on the base surface.
const sharedPairs: Pair[] = [
  ...["--color-bg-surface", "--color-bg-canvas", "--color-bg-surface-elevated"].flatMap((background) => [
    { foreground: "--color-text-primary", background, minimum: TEXT },
    { foreground: "--color-text-secondary", background, minimum: TEXT },
    { foreground: "--color-text-tertiary", background, minimum: TEXT },
    { foreground: "--color-text-link", background, minimum: TEXT },
  ]),
  { foreground: "--color-border-interactive", background: SURFACE, minimum: COMPONENT },
  { foreground: "--color-border-focus", background: SURFACE, minimum: COMPONENT },
  SOFT,
  ...["success", "warning", "danger", "info"].map((kind) => ({
    foreground: `--color-feedback-${kind}`,
    background: SURFACE,
    minimum: COMPONENT,
  })),
  ...["--color-primary", "--color-primary-hover", "--color-primary-active"].map((background) => ({
    foreground: "--color-primary-fg",
    background,
    minimum: TEXT,
  })),
  ...STATES.flatMap((state) => [
    { foreground: `--color-status-${state}-fg`, background: `--color-status-${state}-bg`, minimum: TEXT },
    { foreground: `--color-status-${state}-border`, background: SURFACE, minimum: COMPONENT },
    { foreground: "--color-primary-fg", background: `--color-status-${state}-solid-bg`, minimum: TEXT },
  ]),
];

const lightPairs: Pair[] = sharedPairs;
const darkPairs: Pair[] = sharedPairs;

function luminance(hex: string): number {
  const channels = [1, 3, 5].map((start) => parseInt(hex.slice(start, start + 2), 16) / 255);
  const [red = 0, green = 0, blue = 0] = channels.map((value) =>
    value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4,
  );
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
}

function ratio(foreground: string, background: string): number {
  const [lighter = 0, darker = 0] = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
  return (lighter + 0.05) / (darker + 0.05);
}

function failingPairs(pairs: Pair[], declarations: Map<string, string>): string[] {
  return pairs.flatMap(({ foreground, background, minimum }) => {
    const measured = ratio(resolveToken(foreground, declarations), resolveToken(background, declarations));
    return measured < minimum
      ? [`${foreground} on ${background}: ${measured.toFixed(2)}:1, needs ${String(minimum)}:1`]
      : [];
  });
}

describe("the contrast of the token pairs", () => {
  it("meets 4.5:1 for text and 3:1 for components in the light theme", () => {
    const declarations = themeDeclarations(realTokenSources(), "light");

    expect(lightPairs.length).toBeGreaterThan(0);
    expect(failingPairs(lightPairs, declarations)).toEqual([]);
  });

  it.each<Theme>(["dark", "dark-media"])("meets the same minimums in the dark theme, %s block", (theme) => {
    const declarations = themeDeclarations(realTokenSources(), theme);

    expect(darkPairs.length).toBeGreaterThan(0);
    expect(failingPairs(darkPairs, declarations)).toEqual([]);
  });

  it("reproduces the ratios published in docs/ui-ux/01 section 5", () => {
    const light = themeDeclarations(realTokenSources(), "light");
    const measure = (foreground: string, background: string) =>
      ratio(resolveToken(foreground, light), resolveToken(background, light)).toFixed(2);

    expect(measure("--color-text-primary", SURFACE)).toBe("17.85");
    expect(measure("--color-text-secondary", SURFACE)).toBe("7.58");
    expect(measure("--color-text-tertiary", SURFACE)).toBe("4.76");
  });

  it("names the pair that falls short [fixture]", () => {
    const declarations = themeDeclarations([readFixture("low-contrast.css")], "light");

    expect(
      failingPairs([{ foreground: "--color-text-secondary", background: SURFACE, minimum: TEXT }], declarations),
    ).toEqual(["--color-text-secondary on --color-bg-surface: 2.56:1, needs 4.5:1"]);
  });

  it("exempts only the disabled text and the decorative border, each with its citation", () => {
    const enforced = new Set([...lightPairs, ...darkPairs].map((pair) => pair.foreground));

    for (const { token, reason } of EXEMPT) {
      expect(enforced.has(token), token).toBe(false);
      expect(reason).toMatch(/docs\/ui-ux\/01 section 5\.[12]/);
    }
  });
});

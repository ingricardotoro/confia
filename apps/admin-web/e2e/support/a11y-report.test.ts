import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { MATRIX, MINIMUM } from "./a11y-matrix.ts";
import { THEMES, assertCoversMinimum, assertNoBlocking, summarizeReport, type GateResult, type Result } from "./a11y-report.ts";
import { SCENARIO_HEADER } from "./scenario-header.ts";
import { SCENARIO_HEADER as SERVER_HEADER } from "../mock-api/server.ts";

const violation = (id: string, impact: Result["impact"]): Result =>
  ({ id, impact, help: `${id} help`, nodes: [{ target: ["#x"] }] }) as unknown as Result;
const result = (blocking: Result[], theme: GateResult["theme"] = "light"): GateResult => ({
  route: "/inaccessible",
  state: "ready",
  theme,
  blocking,
  reported: [],
});

describe("the evaluations of the matrix [BX74, BX77]", () => {
  it("covers every minimum combination in both themes, with the minimum written apart from the matrix", () => {
    const evaluations = assertCoversMinimum(MATRIX, MINIMUM);

    expect(MINIMUM).toHaveLength(22);
    for (const wanted of MINIMUM) {
      const themes = evaluations.filter((e) => e.route === wanted.route && e.state === wanted.state).map((e) => e.theme);
      expect(themes).toEqual([...THEMES]);
    }
    // Every combination of the matrix is evaluated, in both themes: the 22 of the minimum (BX109) and the screens added since.
    expect(evaluations).toHaveLength(MATRIX.length * THEMES.length);
    expect(MATRIX.length).toBeGreaterThanOrEqual(MINIMUM.length);
  });

  it("fails on zero combinations instead of passing", () => {
    expect(() => {
      assertCoversMinimum([], MINIMUM);
    }).toThrow(/zero combinations/);
  });

  it("fails and names the minimum combination the matrix lacks", () => {
    expect(() => {
      assertCoversMinimum(MATRIX.slice(1), MINIMUM);
    }).toThrow(new RegExp(MATRIX[0]?.state ?? "unreachable"));
  });
});

describe("the states of the sign in screen [BX109]", () => {
  const SIGN_IN = "/iniciar-sesion";
  const signIn = MATRIX.filter((combination) => combination.route === SIGN_IN);

  it("holds the eight states, each once, so the gate evaluates 16 combinations of the screen in both themes", () => {
    expect(signIn.map((combination) => combination.state)).toEqual([
      "initial",
      "error-summary",
      "credentials-rejected",
      "rate-limited-countdown",
      "service-busy",
      "sending",
      "password-visible",
      "with-notice",
    ]);
    // The gate evaluates the whole matrix, so the count follows it as screens are added.
    expect(assertCoversMinimum(MATRIX, [...MINIMUM, ...signIn])).toHaveLength(MATRIX.length * THEMES.length);
    expect(new Set(MATRIX.map((combination) => `${combination.route} ${combination.state}`)).size).toBe(MATRIX.length);
  });

  it("reaches the states of a failure through the presets of the simulator, and the rest without one", () => {
    const presets = Object.fromEntries(signIn.map((combination) => [combination.state, combination.preset]));

    expect(presets).toEqual({
      initial: undefined,
      "error-summary": undefined,
      "credentials-rejected": "authentication-failed",
      "rate-limited-countdown": "too-many-requests",
      "service-busy": "capacity-exceeded",
      sending: "slow",
      "password-visible": undefined,
      "with-notice": undefined,
    });
  });

  it("has the eight states in the minimum, now that the last screen is in", () => {
    expect(MINIMUM.filter((wanted) => wanted.route === SIGN_IN)).toHaveLength(8);
  });
});

describe("the states of the verification screen [BX109]", () => {
  const VERIFICATION = "/iniciar-sesion/verificacion";
  const verification = MATRIX.filter((combination) => combination.route === VERIFICATION);

  it("holds the three states, each once, all reached through the preset that asks for the second factor", () => {
    expect(verification.map((combination) => [combination.state, combination.preset])).toEqual([
      ["totp", "second-factor-required"],
      ["recovery", "second-factor-required"],
      ["code-rejected", "second-factor-required"],
    ]);
    expect(assertCoversMinimum(MATRIX, [...MINIMUM, ...verification])).toHaveLength(MATRIX.length * THEMES.length);
  });

  it("has the three states in the minimum", () => {
    expect(MINIMUM.filter((wanted) => wanted.route === VERIFICATION)).toHaveLength(3);
  });
});

describe("the states of the enrollment screen [BX109]", () => {
  const ENROLLMENT = "/iniciar-sesion/inscripcion";
  const enrollment = MATRIX.filter((combination) => combination.route === ENROLLMENT);

  it("holds the five states, each once, all reached through the preset that asks for an enrollment", () => {
    expect(enrollment.map((combination) => [combination.state, combination.preset])).toEqual([
      ["loading", "enrollment-required"],
      ["secret", "enrollment-required"],
      ["recovery-codes", "enrollment-required"],
      ["first-code", "enrollment-required"],
      ["network-error", "enrollment-required"],
    ]);
    expect(assertCoversMinimum(MATRIX, [...MINIMUM, ...enrollment])).toHaveLength(MATRIX.length * THEMES.length);
  });

  it("has the five states in the minimum, and the whole matrix is the 22 states of the three screens and the part 1 [BX109]", () => {
    expect(MINIMUM.filter((wanted) => wanted.route === ENROLLMENT)).toHaveLength(5);
    expect(MINIMUM).toHaveLength(6 + 8 + 3 + 5);
  });
});

describe("the states of the academic structure", () => {
  const organization = MATRIX.filter((combination) => combination.route.startsWith("/organization/"));

  it("holds the four views ready, the open dialog and its error summary, the empty state and the load error, each once", () => {
    expect(organization.map((combination) => [combination.route, combination.state, combination.preset, combination.opensDialog])).toEqual([
      ["/organization/academic-years", "ready", undefined, undefined],
      ["/organization/modalities", "ready", undefined, undefined],
      ["/organization/grades", "ready", undefined, undefined],
      ["/organization/sections", "ready", undefined, undefined],
      ["/organization/modalities", "dialog-open", undefined, true],
      ["/organization/modalities", "dialog-error-summary", undefined, true],
      ["/organization/academic-years", "empty", "organization-empty", undefined],
      ["/organization/academic-years", "load-error", "organization-server-error", undefined],
    ]);
    expect(assertCoversMinimum(MATRIX, [...MINIMUM, ...organization])).toHaveLength(MATRIX.length * THEMES.length);
    expect(new Set(MATRIX.map((combination) => `${combination.route} ${combination.state}`)).size).toBe(MATRIX.length);
  });
});

describe("the verdict [BX75]", () => {
  it("names route, state, theme, rule and impact of every blocking violation", () => {
    expect(() => {
      assertNoBlocking([result([violation("button-name", "critical")], "dark")]);
    }).toThrow(
      "/inaccessible [ready] dark: button-name (critical)",
    );
  });

  it("passes when nothing blocks", () => {
    expect(() => {
      assertNoBlocking([result([])]);
    }).not.toThrow();
  });

  it("summarizes the evaluations without the page markup", () => {
    const report = summarizeReport([
      { ...result([violation("button-name", "critical")]), blocking: [{ rule: "button-name", impact: "critical", help: "h", targets: ["#x"] }], reported: [] },
    ]);

    expect(report).toMatchObject({ evaluated: 1, blocking: 1, reported: 0 });
  });
});

describe("the policy and the header", () => {
  const read = (path: string) => readFileSync(join(import.meta.dirname, path), "utf8");

  it("shares the module of the accessibility policy with the story gate [BX73]", () => {
    const stories = read("../../../../packages/ui/src/a11y/run-gate.ts");

    expect(read("./a11y-gate.ts")).toContain('from "@confia/config/a11y"');
    expect(stories).toContain('from "@confia/config/a11y"');
  });

  it("sends the header the simulator reads", () => {
    expect(SCENARIO_HEADER).toBe(SERVER_HEADER);
  });
});
